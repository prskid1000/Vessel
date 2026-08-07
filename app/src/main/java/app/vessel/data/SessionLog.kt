package app.vessel.data

import app.vessel.core.LogEntry
import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import kotlinx.serialization.Serializable
import java.io.Closeable

/**
 * A writer for one session's log.
 *
 * Opened when a container launches, closed when it exits. Every method on it is
 * non-blocking and cannot throw: a call hands the line to a channel and returns,
 * and the file work happens on an IO coroutine. That is not an optimisation —
 * the caller is the thread pumping the Wine process's stderr, and a logger that
 * can block it is a logger that can stall the session it is describing.
 *
 * The same rule covers failure. If the directory cannot be created, or the disk
 * fills, or a write throws, the sink degrades to doing nothing at all and the
 * session carries on. Losing the log is bad; killing the run the log was
 * recording is worse.
 */
interface SessionLog : Closeable {

    /** The session's identity, and the name of its file. Epoch millis. */
    val startedAt: Long

    /** One line, already classified. */
    fun line(source: LogSource, level: LogLevel, text: String)

    /**
     * One line of raw process output, classified by
     * [app.vessel.core.parseSessionLogLine].
     *
     * This is the pipe path: the launcher reads a line off stderr and hands it
     * over without deciding anything about it.
     */
    fun raw(text: String)

    /**
     * The resolved component versions, written once at open.
     *
     * A log without them is a log that cannot be acted on — "it crashed" is not
     * a bug report until it says which Wine, which driver and which D3D layer
     * crashed, and those are exactly the things that change between two runs
     * that behave differently.
     */
    fun header(lines: List<String>)

    /**
     * Record how the session ended.
     *
     * Called by whoever waited on the process, with its exit status. A session
     * whose writer is closed without this — because the app was killed with the
     * container running — is recovered as [SessionExit.CRASHED] on next read,
     * which is the honest reading of a session that never got to say goodbye.
     */
    fun finish(exit: SessionExit, exitCode: Int? = null)

    /** [finish] with [SessionExit.OK]. Safe to call twice; the first wins. */
    override fun close()
}

/**
 * How a session ended, as the list view's status tag.
 *
 * Three states and no "unknown": a session is running, it ended cleanly, or it
 * did not. Anything the store cannot account for is [CRASHED], because that is
 * the state worth looking at and a fourth word for "we lost track" would only
 * ever appear on sessions that did in fact die.
 */
@Serializable
enum class SessionExit { RUNNING, OK, CRASHED }

/**
 * The sidecar written beside every log, as `<startedAt>.meta.json`.
 *
 * It exists so the session list never has to open a log body. Ten sessions of up
 * to eight megabytes each is eighty megabytes of scanning to draw a screen whose
 * rows say "12 minutes ago, 41 s, 2.1 MB, crashed" — four facts that are cheap
 * to record while writing and ruinous to recover afterwards.
 *
 * It is rewritten as the session runs, not only at the end, so the list is
 * correct for a session that is still going and survives the process being
 * killed mid-run.
 */
@Serializable
data class SessionLogMeta(
    val schemaVersion: Int = SESSION_LOG_SCHEMA,
    val containerId: String = "",
    val startedAt: Long = 0L,
    /** Epoch millis, or null while the session is still running. */
    val endedAt: Long? = null,
    val exit: SessionExit = SessionExit.RUNNING,
    val exitCode: Int? = null,
    /** Lines actually in the file, after dedup and elision. */
    val lines: Int = 0,
    /** Lines the 8 MB cap threw away from the middle. */
    val elidedLines: Int = 0,
    /** Lines the rate limiter refused. */
    val droppedLines: Int = 0,
    val sizeBytes: Long = 0L,
    /**
     * Whether the session recorded an ERROR at any point.
     *
     * Stored rather than derived: it is the one thing the list wants to say
     * about a session that exited cleanly and still went wrong, and recovering
     * it means reading the body, which is what this file exists to avoid.
     */
    val hasErrors: Boolean = false,
)

/** Bumped when the sidecar's shape changes in a way a reader has to know about. */
const val SESSION_LOG_SCHEMA: Int = 1

/**
 * Where a read has got to.
 *
 * A byte offset rather than a line number, because resuming a tail must not cost
 * a rescan of everything already read — following a live session would otherwise
 * re-read the whole file every quarter second.
 *
 * [segment] indexes the physical files a session is made of. A finished session
 * is one file; a session still running that has passed the head cap is a head
 * plus up to two tail segments, and the cursor walks them in order.
 */
data class LogCursor(
    val segment: Int = 0,
    val offset: Long = 0L,
    /** The next line's index in the file, counted over unfiltered lines. */
    val index: Int = 0,
)

/** One page of a log: the lines that passed the filter, and where to resume. */
data class LogChunk(
    val entries: List<LogEntry>,
    val cursor: LogCursor,
    val atEnd: Boolean,
)
