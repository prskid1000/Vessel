package app.vessel.data

import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.SessionLogLimits
import app.vessel.core.encodeLogLine
import app.vessel.core.errorDigestElided
import app.vessel.core.errorDigestHeading
import app.vessel.core.errorDigestLine
import app.vessel.core.logPrefixKey
import app.vessel.core.logPrefixLegend
import app.vessel.core.overflowLogMarker
import app.vessel.core.parseSessionLogLine
import app.vessel.core.rateLimitedLogMarker
import app.vessel.core.repeatedLogLine
import app.vessel.core.tailContinuedMarker
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
import java.util.concurrent.atomic.AtomicInteger
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
 * Four layers, applied in this order:
 *
 *  0. **The producer channel**, which drops its oldest queued line when the pump
 *     falls behind. Counted; see [overflowed].
 *  1. **Dedup.** Consecutive identical lines collapse to one line and a count —
 *     the common explosion, one `fixme` inside a render loop.
 *  2. **Rate limit** to [SessionLogLimits.rateLimitLines] per second, for what
 *     dedup cannot catch: two *alternating* unguarded wined3d `ERR` sites at
 *     frame rate.
 *  3. **Head + tail cap.** [SessionLogLimits.headBytes] from the start and two
 *     [SessionLogLimits.tailSegmentBytes] from the end, middle elided —
 *     truncating from either end alone throws away either the crash or why it
 *     broke.
 *
 * No layer ever drops silently: a log that hides its own truncation turns a gap
 * in the evidence into a claim that nothing happened. **Layer 0 did exactly that
 * until the caps became adjustable.** `Channel(_, DROP_OLDEST)` reports success
 * from `trySend` either way, so nothing counted what it discarded; at the old
 * fixed caps it almost never fired, and at raised caps with `+relay` on it is the
 * first layer to bite. It is counted now, and it says so in the file and in the
 * sidecar.
 *
 * Nothing here throws. Every file operation is wrapped, and the first failure
 * puts the writer into a state where it accepts lines and discards them, so a
 * full disk costs the log and never the session.
 *
 * @param limits the three caps this session runs under, from the container's
 *   diagnostics record. Defaulted so that a caller with no opinion still gets a
 *   bounded log rather than an unbounded one.
 */
internal class SessionLogWriter(
    private val directory: File,
    private val containerId: String,
    override val startedAt: Long,
    private val json: Json,
    scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onFinished: (SessionLogWriter) -> Unit,
    private val limits: SessionLogLimits = SessionLogLimits(),
) : SessionLog {

    private sealed interface Command {
        class Line(val source: LogSource, val level: LogLevel, val text: String) : Command
        class Header(val lines: List<String>) : Command
    }

    /**
     * Lines the channel discarded because the pump had not caught up.
     *
     * `AtomicInteger` because `onUndeliveredElement` runs on the *sender's*
     * thread — the one draining the guest's stderr — while everything else in
     * this class is the pump's. Counting rather than blocking is deliberate: the
     * producer must never become back-pressure on the process being logged, so
     * the choice is between losing lines quietly and losing them out loud.
     */
    private val overflowed = AtomicInteger(0)

    /** How much of [overflowed] the file has already admitted to. */
    private var overflowReported = 0

    private val commands = Channel<Command>(
        CHANNEL_CAPACITY,
        BufferOverflow.DROP_OLDEST,
        // Called for each element DROP_OLDEST throws away. Not called for
        // anything still buffered at `close()`, which delivers what it has —
        // only `cancel()` would, and nothing cancels this channel.
        onUndeliveredElement = { overflowed.incrementAndGet() },
    )
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

    /**
     * Lines written, by `<level><source>` prefix. See
     * [SessionLogMeta.sourceCounts] for why this is recorded at write time and
     * not recovered on read.
     *
     * A plain `LinkedHashMap` and no synchronisation, because like every other
     * counter below it is touched only by the pump coroutine. Bounded by the
     * cross product of the two enums — twenty-four keys at most, most sessions
     * under ten.
     */
    private val sourceCounts = LinkedHashMap<String, Int>()

    /** The same, for lines the rate limiter refused. */
    private val droppedBySource = LinkedHashMap<String, Int>()

    /**
     * Drops attributed within the current rate-limit window only, so the marker
     * can name the source that filled it and then start again.
     */
    private val droppedThisWindow = LinkedHashMap<String, Int>()

    /**
     * Distinct ERROR lines and their counts, in first-seen order.
     *
     * Capped at [MAX_DIGEST_ENTRIES] *keys* rather than by total volume: a
     * session with nine distinct errors repeated ten thousand times each costs
     * nine entries, which is the shape this is for. Past the cap new distinct
     * errors are counted in [digestOverflow] rather than dropped in silence.
     */
    private val errorDigest = LinkedHashMap<String, ErrorTally>()
    private var digestOverflow = 0
    private var errorLines = 0

    private class ErrorTally(val source: LogSource, var count: Int)
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
                // **The legend goes with the versions, and for the same reason
                // they do.** A log without its component versions "cannot be
                // acted on"; a log whose every line begins with two undocumented
                // letters cannot be *read*. Three lines, once, at the top, so a
                // log pasted into a bug report carries its own key rather than
                // depending on the reader having seen this codebase.
                (command.lines + logPrefixLegend()).forEach {
                    write(LogSource.VESSEL, LogLevel.INFO, it)
                }
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
                writeRateLimitMarker(refused)
            }
        }
        if (windowLines >= limits.rateLimitLines) {
            rateLimited++
            dropped++
            // **Attributed, and attributed here rather than reconstructed
            // later.** The limiter is the only layer that knows whose line it is
            // refusing at the moment it refuses; once the line is gone the
            // information is gone with it, which is how two separate volume
            // disasters both ended up being diagnosed by hand afterwards. Two
            // tallies because they answer different questions: the window one is
            // for the marker written into the file, the session one for the
            // sidecar. See [SessionLogMeta.droppedBySource].
            val key = logPrefixKey(source, level)
            droppedBySource[key] = (droppedBySource[key] ?: 0) + 1
            droppedThisWindow[key] = (droppedThisWindow[key] ?: 0) + 1
            return
        }
        windowLines++
        write(source, level, text)
    }

    /**
     * The rate-limit marker, naming whichever source lost the most.
     *
     * Through [write] rather than [emit], like every other marker, so the
     * limiter cannot suppress the line that explains the limiter.
     */
    private fun writeRateLimitMarker(refused: Int) {
        val worst = droppedThisWindow.maxByOrNull { it.value }
        droppedThisWindow.clear()
        write(
            LogSource.VESSEL,
            LogLevel.WARN,
            rateLimitedLogMarker(refused, worst?.key, worst?.value ?: 0),
        )
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
            if (tail == null && headBytes + cost > limits.headBytes) startTail()
            if (tail != null && tailBytes + cost > limits.tailSegmentBytes) rotateTail()
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
        val key = logPrefixKey(source, level)
        sourceCounts[key] = (sourceCounts[key] ?: 0) + 1
        if (level == LogLevel.ERROR) {
            hasErrors = true
            tally(source, text)
        }
    }

    /**
     * Fold one error line into the digest.
     *
     * The `×N` suffix is stripped first, so a run that dedup already collapsed
     * counts as the N it represents rather than as one line with a decoration.
     * Without that, a session whose only error fired ten thousand times in a row
     * would report a count of one.
     */
    private fun tally(source: LogSource, text: String) {
        val marker = text.lastIndexOf(REPEAT_MARKER)
        val repeats = if (marker < 0) {
            1
        } else {
            text.substring(marker + REPEAT_MARKER.length).toIntOrNull() ?: 1
        }
        val key = if (marker < 0 || repeats == 1) text else text.substring(0, marker)
        errorLines += repeats

        val existing = errorDigest[key]
        if (existing != null) {
            existing.count += repeats
            return
        }
        if (errorDigest.size >= MAX_DIGEST_ENTRIES) {
            digestOverflow++
            return
        }
        errorDigest[key] = ErrorTally(source, repeats)
    }

    /**
     * The head is full: close it and start writing into the first tail segment.
     *
     * The breadcrumb is written *before* the head is closed and is the last line
     * in it. See [tailContinuedMarker] for the wrong answer that reading the head
     * of a live session produced, and why one line here is the whole fix.
     *
     * **Straight onto the stream and not through [write]**, which is not
     * fastidiousness: `write` is what calls this, from a branch conditioned on
     * `tail == null && headBytes + cost > headBytes`, and that condition is still
     * true on re-entry. Going back through it is unbounded recursion. Counted by
     * hand for the same reason.
     */
    private fun startTail() {
        val breadcrumb = tailContinuedMarker(tailFile(directory, startedAt, 0).name)
        runCatching {
            head?.write(encodeLogLine(LogSource.VESSEL, LogLevel.WARN, breadcrumb))
            head?.newLine()
        }
        lines++
        val key = logPrefixKey(LogSource.VESSEL, LogLevel.WARN)
        sourceCounts[key] = (sourceCounts[key] ?: 0) + 1

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

    /**
     * Write the marker for anything the channel discarded since the last one.
     *
     * Called when the queue drains rather than per line, which is both cheap and
     * the right moment: overflow happens during a burst and the count is only
     * meaningful once the burst is over. Through [write] rather than [emit], so
     * the rate limiter cannot suppress the line that explains a drop.
     */
    private fun reportOverflow() {
        if (broken) return
        val total = overflowed.get()
        if (total <= overflowReported) return
        val since = total - overflowReported
        overflowReported = total
        write(LogSource.VESSEL, LogLevel.WARN, overflowLogMarker(since))
    }

    private fun flush() {
        if (broken) return
        flushPending()
        reportOverflow()
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
            // Last chance while the segments are still open: a burst that
            // overflowed on the way to the exit must still be admitted.
            reportOverflow()
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
                val worst = droppedThisWindow.maxByOrNull { it.value }
                appendMarker(rateLimitedLogMarker(rateLimited, worst?.key, worst?.value ?: 0))
                droppedThisWindow.clear()
                rateLimited = 0
            }
            if (mergeTailSegments(directory, startedAt, elided)) lines++
            appendDigest()
        }
        writeSessionMeta(directory, snapshot(exit, code, System.currentTimeMillis()), json)
        onFinished(this)
        onChanged()
    }

    /**
     * The digest of distinct errors, appended after the merge.
     *
     * **After the merge, on purpose.** It is the last thing in the finished file,
     * which is where somebody opening a crashed session starts — and putting it
     * anywhere earlier would mean writing it before the errors it summarises had
     * all happened.
     *
     * **Written even when there are none**, and that is the more useful half. A
     * log that ends in `… no errors in this session …` has answered the question
     * "did anything go wrong" for a session whose program vanished cleanly, which
     * is exactly the Metro case in `docs/LOGGING.md` — and it does so without
     * anyone having to trust that they scrolled far enough. The alternative,
     * printing nothing, is indistinguishable from the digest having failed.
     *
     * Ordered by count and not by first appearance: this is a summary and the
     * repeated failure is the one to look at first. The order lines *happened* in
     * is not lost — it is the log, immediately above.
     */
    private fun appendDigest() {
        val body = digestEntries().map { (text, tally) ->
            errorDigestLine(tally.source, tally.count, text)
        }
        val overflow =
            if (digestOverflow > 0) listOf(errorDigestElided(digestOverflow)) else emptyList()
        val heading = errorDigestHeading(errorDigest.size + digestOverflow, errorLines)
        appendMarkers(
            listOf(heading) + body + overflow,
            // INFO when there is nothing to report, so a clean session's trailer
            // is not a page of red for the absence of a problem.
            if (errorDigest.isEmpty()) LogLevel.INFO else LogLevel.ERROR,
        )
    }

    /** The digest, loudest first. Shared by the file trailer and the sidecar. */
    private fun digestEntries(): List<Map.Entry<String, ErrorTally>> =
        errorDigest.entries.sortedByDescending { it.value.count }

    private fun appendMarker(text: String, level: LogLevel = LogLevel.WARN) =
        appendMarkers(listOf(text), level)

    /**
     * Append lines to the finished file, reopening it for one pass.
     *
     * A list rather than one call per line because the digest is up to
     * [MAX_DIGEST_ENTRIES] + 2 of them and each call is an open, a write and a
     * close. Counted into [sourceCounts] as well as [lines], so that
     * `lines == sourceCounts.values.sum()` holds for the whole file rather than
     * for the part written before teardown — an invariant a test can assert, and
     * one a histogram is worth much less without.
     */
    private fun appendMarkers(texts: List<String>, level: LogLevel) {
        if (texts.isEmpty()) return
        runCatching {
            val target = if (tailFile(directory, startedAt, 0).isFile) {
                tailFile(directory, startedAt, 0)
            } else {
                logFile(directory, startedAt)
            }
            FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { out ->
                texts.forEach { text ->
                    out.write(encodeLogLine(LogSource.VESSEL, level, text))
                    out.newLine()
                }
            }
            lines += texts.size
            val key = logPrefixKey(LogSource.VESSEL, level)
            sourceCounts[key] = (sourceCounts[key] ?: 0) + texts.size
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
        overflowLines = overflowed.get(),
        sizeBytes = segmentsOf(directory, startedAt).sumOf { it.length() },
        hasErrors = hasErrors,
        // Copied rather than shared: the snapshot outlives this call and the
        // maps keep being written to while the session runs.
        sourceCounts = LinkedHashMap(sourceCounts),
        droppedBySource = LinkedHashMap(droppedBySource),
        errorDigest = digestEntries().map { (text, tally) ->
            SessionErrorCount(text = text, count = tally.count, source = tally.source.wire)
        },
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
         * The head allowance, the retained tail and the lines-per-second ceiling
         * used to be constants here. They are [SessionLogLimits] now, per
         * container, because the whole point of raising a debug channel is that
         * the run afterwards is bigger than the caps were chosen for — and the
         * arithmetic that ties the three together is stated there.
         */
        const val RATE_WINDOW_MS = 1000L

        /** Deep enough to absorb a burst, shallow enough to bound the memory. */
        const val CHANNEL_CAPACITY = 8192
        const val FLUSH_LINES = 512
        const val WRITE_BUFFER_BYTES = 64 * 1024
        const val PUBLISH_INTERVAL_MS = 400L

        /**
         * How many *distinct* error lines the digest names.
         *
         * Thirty-two, and the number is chosen against the artefact rather than
         * against memory: the digest is a trailer somebody reads on a phone, and
         * past a screenful it stops being a summary. The sessions this is for
         * have single figures of distinct errors repeated thousands of times —
         * the 26,966-line vkd3d burst was two distinct messages. A session with
         * more than thirty-two genuinely different errors has a different
         * problem, and the count of the rest is still reported.
         */
        const val MAX_DIGEST_ENTRIES = 32

        /** The separator [repeatedLogLine] writes, undone by [tally]. */
        const val REPEAT_MARKER = "  ×"
    }
}
