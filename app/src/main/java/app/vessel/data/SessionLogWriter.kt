package app.vessel.data

import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.encodeLogLine
import app.vessel.core.parseSessionLogLine
import app.vessel.core.rateLimitedLogMarker
import app.vessel.core.repeatedLogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one implementation of [SessionLog].
 *
 * Everything a caller touches is a `trySend` onto a bounded channel; everything
 * that touches a file happens in a single coroutine on IO, so there is exactly
 * one writer per session and no lock anywhere. The channel drops its oldest
 * element when full rather than suspending the producer, because the producer is
 * the thread draining the session's stderr and a full buffer must never become
 * back-pressure on the process being logged.
 *
 * Three defences, applied in this order, and the order is the design:
 *
 *  1. **Dedup.** Consecutive identical lines collapse to `<line>  ×N`. This is
 *     the common explosion — one `fixme` inside a render loop — and it is
 *     collapsed for the cost of a string comparison.
 *  2. **Rate limit.** At most [RATE_LIMIT_LINES] lines are written per second.
 *     Dedup cannot help when a draw path fails: wined3d has hundreds of
 *     unguarded `ERR` sites and two of them alternating produce a stream of
 *     non-identical lines at frame rate. Over the limit the sink stops writing
 *     and counts; when the rate falls it writes one marker saying how many lines
 *     it refused. It never drops silently — a log that hides its own truncation
 *     turns a gap in the evidence into a claim that nothing happened.
 *  3. **Head + tail cap.** The first [HEAD_LIMIT_BYTES] and the last
 *     [TAIL_SEGMENT_BYTES]–2× of the file are kept, with the middle replaced by
 *     one elision marker. Truncating from the end would throw away the crash;
 *     truncating from the start would throw away why it broke. Both halves
 *     matter, so both halves are kept.
 *
 * Nothing here throws. Every file operation is wrapped, and the first failure
 * puts the writer into a state where it accepts lines and discards them, so a
 * full disk costs the log and never the session.
 */
internal class SessionLogWriter(
    private val directory: File,
    private val containerId: String,
    override val startedAt: Long,
    private val json: Json,
    scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onFinished: (SessionLogWriter) -> Unit,
) : SessionLog {

    private sealed interface Command {
        class Line(val source: LogSource, val level: LogLevel, val text: String) : Command
        class Header(val lines: List<String>) : Command
    }

    private val commands = Channel<Command>(CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val closed = AtomicBoolean(false)

    /**
     * The exit status, set synchronously by [finish] rather than sent down the
     * channel. A channel that drops its oldest element could drop the one
     * message that says the session ended cleanly, and a clean session
     * recovering as "crashed" is the sort of wrong that is never noticed.
     */
    private val requestedExit = AtomicReference<Pair<SessionExit, Int?>?>(null)

    // — writer state, touched only by the pump coroutine ---------------------

    private var head: BufferedWriter? = null
    private var tail: BufferedWriter? = null
    private var headBytes = 0L
    private var tailBytes = 0L
    private var tailLines = 0
    private var previousTailLines = 0

    private var pendingText: String? = null
    private var pendingSource = LogSource.VESSEL
    private var pendingLevel = LogLevel.INFO
    private var pendingCount = 0

    private var windowStartedAt = 0L
    private var windowLines = 0
    private var rateLimited = 0

    private var lines = 0
    private var elided = 0
    private var dropped = 0
    private var hasErrors = false
    private var unflushed = 0
    private var lastPublishedAt = 0L
    private var broken = false

    init {
        scope.launch { pump() }
    }

    // — the producer side: non-blocking, never throws ------------------------

    override fun line(source: LogSource, level: LogLevel, text: String) {
        if (closed.get()) return
        commands.trySend(Command.Line(source, level, text))
    }

    override fun raw(text: String) {
        val parsed = parseSessionLogLine(text)
        line(parsed.source, parsed.level, parsed.text)
    }

    override fun header(lines: List<String>) {
        if (closed.get()) return
        commands.trySend(Command.Header(lines))
    }

    override fun finish(exit: SessionExit, exitCode: Int?) {
        if (!closed.compareAndSet(false, true)) return
        requestedExit.set(exit to exitCode)
        // Closing wakes the pump; anything already buffered is still delivered.
        commands.close()
    }

    override fun close() = finish(SessionExit.OK, null)

    // — the consumer side: one coroutine, all the file work ------------------

    /**
     * Drain, and settle the file whenever there is nothing left to drain.
     *
     * There is no flush timer. When the queue empties, the pump flushes — the
     * buffer, the pending duplicate run and the sidecar — and only then blocks.
     * That gives a live viewer a settled file within one line of real time,
     * without a timeout racing the receive for every line of a hundred-thousand
     * line burst.
     */
    private suspend fun pump() {
        prepare()
        while (true) {
            var command = commands.tryReceive().getOrNull()
            if (command == null) {
                flush()
                command = commands.receiveCatching().getOrNull() ?: break
            }
            apply(command)
            if (unflushed >= FLUSH_LINES) flush()
        }
        finalise()
    }

    private fun prepare() {
        val opened = runCatching {
            directory.mkdirs()
            head = buffered(FileOutputStream(logFile(directory, startedAt), true))
            true
        }.getOrDefault(false)
        if (!opened) {
            broken = true
            return
        }
        windowStartedAt = System.currentTimeMillis()
        publish(force = true)
    }

    private fun apply(command: Command) {
        if (broken) return
        when (command) {
            is Command.Header -> {
                flushPending()
                command.lines.forEach { write(LogSource.VESSEL, LogLevel.INFO, it) }
            }

            is Command.Line -> {
                val text = command.text
                val sameAsPending = pendingCount > 0 &&
                    pendingText == text &&
                    pendingSource == command.source &&
                    pendingLevel == command.level
                if (sameAsPending) {
                    pendingCount++
                } else {
                    flushPending()
                    pendingText = text
                    pendingSource = command.source
                    pendingLevel = command.level
                    pendingCount = 1
                }
            }
        }
    }

    /** Commit the run of identical lines currently being counted. */
    private fun flushPending() {
        val text = pendingText
        if (pendingCount == 0 || text == null) return
        val count = pendingCount
        pendingCount = 0
        pendingText = null
        emit(pendingSource, pendingLevel, repeatedLogLine(text, count))
    }

    /**
     * One line through the rate limiter.
     *
     * The window is wall-clock and coarse on purpose: the limiter exists to stop
     * a runaway loop, not to shape traffic, and a token bucket with a smooth
     * refill would cost more per line than the write it is guarding.
     */
    private fun emit(source: LogSource, level: LogLevel, text: String) {
        if (broken) return
        val now = System.currentTimeMillis()
        if (now - windowStartedAt >= RATE_WINDOW_MS) {
            windowStartedAt = now
            windowLines = 0
            if (rateLimited > 0) {
                val refused = rateLimited
                rateLimited = 0
                write(LogSource.VESSEL, LogLevel.WARN, rateLimitedLogMarker(refused))
            }
        }
        if (windowLines >= RATE_LIMIT_LINES) {
            rateLimited++
            dropped++
            return
        }
        windowLines++
        write(source, level, text)
    }

    /**
     * The only place bytes reach a file, and the only place the cap is enforced.
     *
     * Markers come through here rather than through [emit], so the sink can
     * always say what it did to itself even while it is refusing everything else.
     */
    private fun write(source: LogSource, level: LogLevel, text: String) {
        if (broken) return
        val encoded = encodeLogLine(source, level, text)
        // Approximate, and deliberately so: an exact UTF-8 length per line means
        // encoding every line twice. The cap is a budget, not a contract, and
        // the sidecar reports the real size from the file itself.
        val cost = encoded.length + 1L

        val written = runCatching {
            if (tail == null && headBytes + cost > HEAD_LIMIT_BYTES) startTail()
            if (tail != null && tailBytes + cost > TAIL_SEGMENT_BYTES) rotateTail()
            val target = tail ?: head ?: return
            target.write(encoded)
            target.newLine()
            if (tail == null) headBytes += cost else { tailBytes += cost; tailLines++ }
            true
        }.getOrDefault(false)

        if (!written) {
            giveUp()
            return
        }
        lines++
        unflushed++
        if (level == LogLevel.ERROR) hasErrors = true
    }

    /** The head is full: close it and start writing into the first tail segment. */
    private fun startTail() {
        head?.flush()
        head?.close()
        head = null
        tail = buffered(FileOutputStream(tailFile(directory, startedAt, 0), false))
        tailBytes = 0
        tailLines = 0
    }

    /**
     * Retire the current tail segment and start a fresh one.
     *
     * Two segments are kept, which is what makes the retained tail between one
     * and two segments long rather than sawtoothing to nothing every time it
     * rolls over. The segment that falls off the end is counted into [elided],
     * so the marker written at finalise states a real number.
     */
    private fun rotateTail() {
        tail?.flush()
        tail?.close()
        tail = null

        val previous = tailFile(directory, startedAt, 1)
        val current = tailFile(directory, startedAt, 0)
        elided += previousTailLines
        previous.delete()
        current.renameTo(previous)
        previousTailLines = tailLines

        tail = buffered(FileOutputStream(current, false))
        tailBytes = 0
        tailLines = 0
    }

    private fun flush() {
        if (broken) return
        flushPending()
        runCatching {
            head?.flush()
            tail?.flush()
        }.onFailure { giveUp() }
        unflushed = 0
        publish(force = false)
    }

    /**
     * Rewrite the sidecar so the session list is right for a run in progress.
     *
     * Throttled: at a thousand lines a second the buffer settles constantly, and
     * a JSON write per settle would be the most expensive thing the logger does.
     */
    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishedAt < PUBLISH_INTERVAL_MS) return
        lastPublishedAt = now
        writeSessionMeta(directory, snapshot(SessionExit.RUNNING, null, endedAt = null), json)
        onChanged()
    }

    private fun finalise() {
        val (exit, code) = requestedExit.get() ?: (SessionExit.CRASHED to null)
        if (!broken) {
            flushPending()
            runCatching {
                head?.flush()
                head?.close()
                tail?.flush()
                tail?.close()
            }
            head = null
            tail = null
            if (rateLimited > 0) {
                // Reopened for one line: the sink refused output right up to the
                // end, and the file must say so.
                appendMarker(rateLimitedLogMarker(rateLimited))
                rateLimited = 0
            }
            if (mergeTailSegments(directory, startedAt, elided)) lines++
        }
        writeSessionMeta(directory, snapshot(exit, code, System.currentTimeMillis()), json)
        onFinished(this)
        onChanged()
    }

    private fun appendMarker(text: String) {
        runCatching {
            val target = if (tailFile(directory, startedAt, 0).isFile) {
                tailFile(directory, startedAt, 0)
            } else {
                logFile(directory, startedAt)
            }
            FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { out ->
                out.write(encodeLogLine(LogSource.VESSEL, LogLevel.WARN, text))
                out.newLine()
            }
            lines++
        }
    }

    /** One 64 KB buffer per open segment: the write path never touches the disk per line. */
    private fun buffered(stream: FileOutputStream): BufferedWriter =
        BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), WRITE_BUFFER_BYTES)

    private fun snapshot(exit: SessionExit, exitCode: Int?, endedAt: Long?) = SessionLogMeta(
        containerId = containerId,
        startedAt = startedAt,
        endedAt = endedAt,
        exit = exit,
        exitCode = exitCode,
        lines = lines,
        elidedLines = elided,
        droppedLines = dropped,
        sizeBytes = segmentsOf(directory, startedAt).sumOf { it.length() },
        hasErrors = hasErrors,
    )

    /** One failed write is enough: from here the sink accepts lines and forgets them. */
    private fun giveUp() {
        broken = true
        runCatching {
            head?.close()
            tail?.close()
        }
        head = null
        tail = null
    }

    private companion object {
        /**
         * Five megabytes of head — the init story: module loads, the driver
         * coming up, D3D device creation. Three megabytes of tail at most, which
         * is where the crash is. Eight in total, per session.
         */
        const val HEAD_LIMIT_BYTES = 5L * 1024 * 1024

        /** Two of these are retained, so the tail settles between 1.5 and 3 MB. */
        const val TAIL_SEGMENT_BYTES = 1536L * 1024

        /**
         * Lines per second the sink will write before it starts counting instead.
         *
         * Two thousand is far above anything a working session produces and far
         * below what a failing draw call can produce, which is the gap the limit
         * has to sit in.
         */
        const val RATE_LIMIT_LINES = 2000
        const val RATE_WINDOW_MS = 1000L

        /** Deep enough to absorb a burst, shallow enough to bound the memory. */
        const val CHANNEL_CAPACITY = 8192
        const val FLUSH_LINES = 512
        const val WRITE_BUFFER_BYTES = 64 * 1024
        const val PUBLISH_INTERVAL_MS = 400L
    }
}
