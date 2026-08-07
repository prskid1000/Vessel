package app.vessel.data

import android.content.Context
import app.vessel.core.LogEntry
import app.vessel.core.LogFilter
import app.vessel.core.decodeLogLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every session log on this device: writing, rotation, listing and reading.
 *
 * One file per session at `filesDir/logs/<containerId>/<startedAt>.log`, ten
 * sessions kept per container, eight megabytes each at most.
 * [ContainerRepository.delete] calls [deleteAll], so logs do not outlive their
 * container.
 *
 * Reads never load a body into a `String`: [read] takes a byte cursor and
 * returns a page, [export] and [textFor] stream. An eight megabyte log is a
 * hundred thousand lines.
 */
@Singleton
class SessionLogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val root: File get() = File(context.filesDir, DIRECTORY)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Sessions with a writer attached in this process. */
    private val writers = ConcurrentHashMap<String, SessionLogWriter>()

    private val _revision = MutableStateFlow(0L)

    /**
     * Bumped whenever anything on disk changes — a flush, a session ending, a
     * delete. One signal serves both re-listing the sessions and following a
     * live one, without a file watcher.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // — writing ---------------------------------------------------------------

    /**
     * **The integration point.** Open a log for a session that is starting.
     *
     * Nothing calls this yet; the session launcher, behind
     * [app.vessel.service.SessionService], is the only intended caller:
     *
     * ```
     * val log = sessionLogs.open(container.id)
     * log.header(listOf("wine  ${'$'}wine", "driver  ${'$'}driver", "d3d  ${'$'}d3d"))
     * stderr.forEachLine { log.raw(it) }          // on the pipe-drain thread
     * log.finish(if (code == 0) SessionExit.OK else SessionExit.CRASHED, code)
     * ```
     *
     * **The child's fd 2 must be a real pipe**, not `/dev/null`: Wine's
     * `init_options()` `fstat`s it and returns before parsing `WINEDEBUG` if it
     * is the null device, so every channel setting silently does nothing — and
     * DXVK and vkd3d write through `__wine_dbg_output`, so the same redirect
     * discards the graphics story too. The channel set and the rest of the
     * environment are `docs/LOGGING.md`'s business; do not restate it here.
     *
     * The returned handle is safe to use from any thread and cannot throw. It
     * must be closed, or the session is recovered as [SessionExit.CRASHED] the
     * next time the list is read.
     */
    fun open(containerId: String, startedAt: Long = System.currentTimeMillis()): SessionLog {
        var stamp = startedAt
        // Two launches inside one millisecond would otherwise share a file.
        while (writers.containsKey(key(containerId, stamp))) stamp++

        val writer = SessionLogWriter(
            directory = directoryFor(containerId),
            containerId = containerId,
            startedAt = stamp,
            json = json,
            scope = scope,
            onChanged = { _revision.update { it + 1 } },
            onFinished = { writers.remove(key(containerId, it.startedAt)) },
        )
        writers[key(containerId, stamp)] = writer
        scope.launch { prune(containerId) }
        return writer
    }

    /** Whether a session is being written to right now by this process. */
    fun isOpen(containerId: String, startedAt: Long): Boolean =
        writers.containsKey(key(containerId, startedAt))

    // — listing ---------------------------------------------------------------

    /** Every session for a container, newest first, re-read on every change. */
    fun sessions(containerId: String): Flow<List<SessionLogMeta>> =
        revision.map { sessionsNow(containerId) }

    suspend fun sessionsNow(containerId: String): List<SessionLogMeta> = withContext(Dispatchers.IO) {
        val directory = directoryFor(containerId)
        stampsIn(directory)
            .sortedDescending()
            .map { recover(directory, containerId, it) }
    }

    suspend fun meta(containerId: String, startedAt: Long): SessionLogMeta? =
        withContext(Dispatchers.IO) {
            val directory = directoryFor(containerId)
            if (!logFile(directory, startedAt).isFile) null
            else recover(directory, containerId, startedAt)
        }

    /**
     * Read the sidecar, repairing what the last run of the app left behind: a
     * session still marked `RUNNING` with no writer attached becomes `CRASHED`,
     * and stray tail segments are folded into the head file so a crashed session
     * reads exactly like a finished one.
     */
    private fun recover(directory: File, containerId: String, startedAt: Long): SessionLogMeta {
        val stored = readSessionMeta(directory, startedAt, json)
            ?: SessionLogMeta(containerId = containerId, startedAt = startedAt)
        if (isOpen(containerId, startedAt)) return stored

        val strayTails = segmentsOf(directory, startedAt).size > 1
        val stale = stored.exit == SessionExit.RUNNING
        if (!strayTails && !stale) {
            return stored.copy(sizeBytes = logFile(directory, startedAt).length())
        }

        val merged = if (strayTails) mergeTailSegments(directory, startedAt, stored.elidedLines) else false
        val repaired = stored.copy(
            containerId = containerId.ifBlank { stored.containerId },
            startedAt = startedAt,
            exit = if (stale) SessionExit.CRASHED else stored.exit,
            endedAt = stored.endedAt ?: logFile(directory, startedAt).lastModified(),
            lines = stored.lines + if (merged) 1 else 0,
            sizeBytes = logFile(directory, startedAt).length(),
        )
        writeSessionMeta(directory, repaired, json)
        return repaired
    }

    // — reading ---------------------------------------------------------------

    /**
     * One page of a session, resuming from [cursor].
     *
     * Bounded twice: by [maxLines] returned and by [MAX_SCAN_LINES] examined.
     * The second bound stops a filtered read of an error-free log scanning a
     * hundred thousand lines in one call — it returns an empty page that is not
     * at the end, and the caller asks again.
     */
    suspend fun read(
        containerId: String,
        startedAt: Long,
        cursor: LogCursor,
        maxLines: Int,
        filter: LogFilter,
    ): LogChunk = withContext(Dispatchers.IO) {
        val directory = directoryFor(containerId)
        val segments = segmentsOf(directory, startedAt)
        val entries = ArrayList<LogEntry>(minOf(maxLines, INITIAL_PAGE_CAPACITY))

        var segment = cursor.segment
        var offset = cursor.offset
        var index = cursor.index
        var scanned = 0

        while (segment < segments.size && entries.size < maxLines && scanned < MAX_SCAN_LINES) {
            var exhausted = false
            runCatching {
                FileInputStream(segments[segment]).use { stream ->
                    if (offset > 0) stream.channel.position(offset)
                    val reader = stream.bufferedReader(Charsets.UTF_8)
                    while (entries.size < maxLines && scanned < MAX_SCAN_LINES) {
                        val raw = reader.readLine()
                        if (raw == null) {
                            exhausted = true
                            break
                        }
                        // Exact, because every line this store writes is
                        // terminated by exactly one '\n' in UTF-8.
                        offset += raw.toByteArray(Charsets.UTF_8).size + 1L
                        scanned++
                        val entry = decodeLogLine(raw, index)
                        index++
                        if (filter.accepts(entry.level)) entries += entry
                    }
                }
            }.onFailure {
                // An unreadable segment ends the segment, not the read: the next
                // one may still be there, and half a log beats none.
                exhausted = true
            }
            if (!exhausted) break
            segment++
            offset = 0
        }

        LogChunk(
            entries = entries,
            cursor = LogCursor(segment, offset, index),
            atEnd = segment >= segments.size,
        )
    }

    /**
     * The session as plain text, for the clipboard. Bounded, and it says when it
     * has been: a clipboard is a Binder transaction and an eight megabyte one
     * does not fail politely. The whole log goes through [export] instead.
     */
    suspend fun textFor(
        containerId: String,
        startedAt: Long,
        filter: LogFilter,
        maxChars: Int = CLIPBOARD_LIMIT_CHARS,
    ): String = withContext(Dispatchers.IO) {
        val builder = StringBuilder()
        var truncated = false
        forEachEntry(containerId, startedAt) { entry ->
            if (builder.length >= maxChars) {
                truncated = true
                return@forEachEntry false
            }
            if (filter.accepts(entry.level)) {
                builder.append(render(entry)).append('\n')
            }
            true
        }
        if (truncated) {
            builder.append("… truncated for the clipboard; use Share to export the whole log …\n")
        }
        builder.toString()
    }

    /**
     * The whole session as readable text, for the share sheet. Into `cacheDir`
     * rather than `filesDir` — the system is welcome to reclaim it — and
     * streamed, so exporting the cap costs one line of memory.
     */
    suspend fun export(containerId: String, startedAt: Long): File? = withContext(Dispatchers.IO) {
        val directory = directoryFor(containerId)
        if (!logFile(directory, startedAt).isFile) return@withContext null
        val target = File(context.cacheDir, EXPORT_DIRECTORY)
        runCatching {
            target.mkdirs()
            val file = File(target, "vessel-session-$startedAt.log")
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                forEachEntry(containerId, startedAt) { entry ->
                    out.write(render(entry))
                    out.newLine()
                    true
                }
            }
            file
        }.getOrNull()
    }

    /** Stream every entry of a session; the block returns false to stop early. */
    private fun forEachEntry(
        containerId: String,
        startedAt: Long,
        block: (LogEntry) -> Boolean,
    ) {
        val directory = directoryFor(containerId)
        var index = 0
        for (segment in segmentsOf(directory, startedAt)) {
            var stop = false
            runCatching {
                segment.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val raw = reader.readLine() ?: break
                        if (!block(decodeLogLine(raw, index++))) {
                            stop = true
                            break
                        }
                    }
                }
            }
            if (stop) return
        }
    }

    /** `error  wine   module:import_dll …` — the exported and copied form. */
    private fun render(entry: LogEntry): String =
        "${entry.level.label.padEnd(5)}  ${entry.source.label.padEnd(6)}  ${entry.text}"

    // — deleting --------------------------------------------------------------

    suspend fun delete(containerId: String, startedAt: Long) = withContext(Dispatchers.IO) {
        writers[key(containerId, startedAt)]?.finish(SessionExit.CRASHED, null)
        sessionFiles(directoryFor(containerId), startedAt).forEach { it.delete() }
        _revision.update { it + 1 }
    }

    /** Every log for one container. Called when the container itself is deleted. */
    suspend fun deleteAll(containerId: String) = withContext(Dispatchers.IO) {
        writers.keys.filter { it.startsWith("$containerId/") }
            .forEach { writers[it]?.finish(SessionExit.CRASHED, null) }
        directoryFor(containerId).deleteRecursively()
        _revision.update { it + 1 }
    }

    /**
     * Ten sessions per container, and the eleventh pushes the oldest off. Run
     * when a session opens, which is the only moment the count can go up.
     */
    private fun prune(containerId: String) {
        val directory = directoryFor(containerId)
        stampsIn(directory)
            .sortedDescending()
            .drop(KEEP_SESSIONS)
            .filterNot { isOpen(containerId, it) }
            .forEach { stamp -> sessionFiles(directory, stamp).forEach { it.delete() } }
        _revision.update { it + 1 }
    }

    private fun stampsIn(directory: File): List<Long> =
        directory.listFiles().orEmpty()
            .mapNotNull { file ->
                file.name
                    .takeIf { it.endsWith(LOG_SUFFIX) }
                    ?.removeSuffix(LOG_SUFFIX)
                    ?.toLongOrNull()
            }

    private fun directoryFor(containerId: String) = File(root, safeName(containerId))

    /**
     * A container id is a UUID today, but it arrives as a string, and one
     * separator in the wrong place is a write outside the app's own directory.
     * Delegated to [ContainerPaths] so this store and [ContainerLayout.logs] can
     * never disagree about which directory an id names.
     */
    private fun safeName(containerId: String): String = ContainerPaths.safeName(containerId)

    private fun key(containerId: String, startedAt: Long) = "$containerId/$startedAt"

    private companion object {
        const val DIRECTORY = "logs"
        const val EXPORT_DIRECTORY = "log-export"

        /** Ten runs is enough to compare a regression against what worked. */
        const val KEEP_SESSIONS = 10

        const val INITIAL_PAGE_CAPACITY = 512
        const val MAX_SCAN_LINES = 20_000
        const val CLIPBOARD_LIMIT_CHARS = 512 * 1024
    }
}
