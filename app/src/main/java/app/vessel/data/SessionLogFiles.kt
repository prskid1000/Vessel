package app.vessel.data

import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.elidedLogMarker
import app.vessel.core.encodeLogLine
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Where a session's bytes live, and the one place the layout is decided.
 *
 * `filesDir/logs/<containerId>/<startedAt>.log` plus a `<startedAt>.meta.json`
 * beside it. While a session is running and has outgrown its head allowance
 * there are also `<startedAt>.log.t0` and `.t1` — the two tail segments — which
 * are merged back into the head file the moment the session is finalised. A
 * reader that finds them is looking at either a live session or one whose
 * process died before it could tidy up; both are handled by merging.
 *
 * The writer and the store both need these names, and a disagreement between
 * them would present as logs that exist and cannot be listed, so they are stated
 * once here rather than twice.
 */
internal const val LOG_SUFFIX = ".log"
internal const val META_SUFFIX = ".meta.json"

internal fun logFile(directory: File, startedAt: Long) =
    File(directory, "$startedAt$LOG_SUFFIX")

internal fun tailFile(directory: File, startedAt: Long, index: Int) =
    File(directory, "$startedAt$LOG_SUFFIX.t$index")

internal fun metaFile(directory: File, startedAt: Long) =
    File(directory, "$startedAt$META_SUFFIX")

/**
 * The physical files one session is made of, in reading order.
 *
 * `t1` before `t0`: `t0` is the segment being written to now, `t1` the one
 * before it, so oldest-first means the previous segment comes first.
 */
internal fun segmentsOf(directory: File, startedAt: Long): List<File> =
    listOf(
        logFile(directory, startedAt),
        tailFile(directory, startedAt, 1),
        tailFile(directory, startedAt, 0),
    ).filter { it.isFile }

/**
 * Fold the tail segments back into the head file, with the elision marker
 * between them.
 *
 * This is the step that makes a finished log a single file: head, then one line
 * saying how much of the middle is gone, then everything retained from the end.
 * It streams line by line — the whole point of the cap is that the file does not
 * fit comfortably in memory, so the tidy-up must not be the thing that loads it.
 *
 * Idempotent, and safe to run on a session left behind by a killed process,
 * which is the other caller: [SessionLogStore] runs it during recovery using the
 * elided count the sidecar recorded as the session ran.
 */
internal fun mergeTailSegments(directory: File, startedAt: Long, elided: Int): Boolean {
    val previous = tailFile(directory, startedAt, 1)
    val current = tailFile(directory, startedAt, 0)
    if (!previous.isFile && !current.isFile) return false

    val head = logFile(directory, startedAt)
    return runCatching {
        // Appending, emphatically: `File.bufferedWriter()` truncates, and
        // truncating here would delete the head half the cap exists to keep.
        FileOutputStream(head, true).bufferedWriter(Charsets.UTF_8).use { out ->
            out.write(encodeLogLine(LogSource.VESSEL, LogLevel.INFO, elidedLogMarker(elided)))
            out.newLine()
            listOf(previous, current).filter { it.isFile }.forEach { segment ->
                segment.forEachLine(Charsets.UTF_8) { line ->
                    out.write(line)
                    out.newLine()
                }
            }
        }
        previous.delete()
        current.delete()
        true
    }.getOrDefault(false)
}

/** The sidecar, written atomically enough for a file nothing else contends on. */
internal fun writeSessionMeta(directory: File, meta: SessionLogMeta, json: Json): Boolean =
    runCatching {
        metaFile(directory, meta.startedAt)
            .writeText(json.encodeToString(SessionLogMeta.serializer(), meta), Charsets.UTF_8)
        true
    }.getOrDefault(false)

/** The sidecar, or null when it is missing or unreadable. */
internal fun readSessionMeta(directory: File, startedAt: Long, json: Json): SessionLogMeta? {
    val file = metaFile(directory, startedAt)
    if (!file.isFile) return null
    return runCatching {
        json.decodeFromString(SessionLogMeta.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()
}

/** Every file belonging to one session. Deleting a session deletes all of them. */
internal fun sessionFiles(directory: File, startedAt: Long): List<File> = listOf(
    logFile(directory, startedAt),
    tailFile(directory, startedAt, 0),
    tailFile(directory, startedAt, 1),
    metaFile(directory, startedAt),
)
