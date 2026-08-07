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
 * sessions kept per container, eight megabytes each at most. Deleting a
 * container deletes its logs — [ContainerRepository.delete] calls
 * [deleteAll], because a log is a property of a container and not a thing that
 * should outlive it on someone's storage.
 *
 * Reads never load a body into a `String`. [read] takes a byte cursor and
 * returns a page; [export] and [textFor] stream. That is not fastidiousness at
 * this size — an eight megabyte log is a hundred thousand lines, and the one
 * moment a user opens it is the moment something has already gone wrong.
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
     * delete.
     *
     * One signal for both jobs the UI has: re-listing the sessions, and
     * following a live one. A file watcher would be the other way to do it and
     * would mean the writer telling the filesystem which then tells the reader,
     * for a change the writer already knows it made.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // — writing ---------------------------------------------------------------

    /**
     * **The integration point.** Open a log for a session that is starting.
     *
     * Nothing calls this yet. The session launcher — which does not exist, and
     * which will live behind [app.vessel.service.SessionService] — is the only
     * intended caller, and its contract is four lines:
     *
     * ```
     * val log = sessionLogs.open(container.id)
     * log.header(listOf("wine  ${'$'}wine", "driver  ${'$'}driver", "d3d  ${'$'}d3d"))
     * stderr.forEachLine { log.raw(it) }          // on the pipe-drain thread
     * log.finish(if (code == 0) SessionExit.OK else SessionExit.CRASHED, code)
     * ```
     *
     * Two things about the process it will be logging, recorded here because
     * this is where whoever writes that code will be looking:
     *
     *  - **stderr must be a real pipe.** Wine's `init_options()` `fstat`s fd 2,
     *    detects `/dev/null`, and returns *before* it parses `WINEDEBUG`. A
     *    child wired to `/dev/null` therefore ignores the variable entirely, and
     *    every debug channel setting silently does nothing. The Winlator lineage
     *    redirects to `/dev/null` unless its debug dialog is open, which is why
     *    its `WINEDEBUG` setting has no effect in normal use.
     *  - **The channel set is
     *    `WINEDEBUG=-all,err+all,warn+module,+winediag,+loaddll`.**
     *    Order is load-bearing: `-all` first, and `warn+module` after `err+all`
     *    so the module channel inherits ERR and ends up ERR|WARN. It is not
     *    `+err` — that registers a channel literally named "err", which does not
     *    exist, so `+warn,+err,+fixme` configures nothing at all.
     *
     *    `warn+module` is not optional padding: `loader.c`'s WARN tier carries
     *    "No implementation for X.Y imported from Z, setting to stub", which is
     *    a DLL that loaded with a missing export and then dies later at a
     *    confusing address. `err+all` misses it because it is WARN, and
     *    `+loaddll` misses it because the module loaded fine.
     *
     *    The graphics side rides the same pipe: DXVK and vkd3d both write
     *    through `__wine_dbg_output`. Do NOT set `VKD3D_LOG_FILE` — it moves
     *    vkd3d's output off stderr rather than copying it. See docs/LOGGING.md.
     *
     * The returned handle is safe to use from any thread and cannot throw. It
     * must be closed, or the session is recovered as
     * [SessionExit.CRASHED] the next time the list is read.
     */
    fun open(containerId: String, startedAt: Long = System.currentTimeMillis()): SessionLog {
        var stamp = startedAt
        // Two launches inside one millisecond would otherwise share a file. It
        // cannot happen from the UI; it can happen from a test.
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
     * Read the sidecar, repairing what the last run of the app left behind.
     *
     * Two things are repaired. A session still marked `RUNNING` with no writer
     * attached did not end cleanly — the process was killed with the container
     * up — so it becomes `CRASHED`, which is what actually happened. And tail
     * segments left on disk are folded into the head file, using the elided
     * count the sidecar was recording as it went, so a crashed session reads
     * exactly like a finished one.
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
     * The page is bounded twice: by [maxLines] returned, and by [MAX_SCAN_LINES]
     * examined. The second bound is what keeps a filtered read of a log with no
     * errors in it from scanning a hundred thousand lines in one call — it
     * returns an empty page that is not at the end, and the caller asks again.
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
                // An unreadable segment ends the segment rather than the read:
                // the next one may still be there, and half a log beats none.
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
     * The session as plain text, for the clipboard.
     *
     * Bounded, and it says when it has been. A clipboard is a Binder
     * transaction, and an eight megabyte one does not fail politely — the whole
     * log goes to [export] and the share sheet instead.
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
     * The whole session written out as readable text, for the share sheet.
     *
     * Into `cacheDir` rather than `filesDir`: it is a copy made to be handed to
     * another app, and the system is welcome to reclaim it afterwards. Streamed,
     * so exporting the cap costs one line of memory rather than eight megabytes.
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
     * Ten sessions per container, and the eleventh pushes the oldest off.
     *
     * Run when a session opens rather than on a schedule, because that is the
     * only moment the count can go up, and a sweep that runs at any other time
     * is a sweep that has to be remembered.
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
     * A container id is a UUID today, but it arrives here as a string and a
     * string that reaches the filesystem gets sanitised. One separator in the
     * wrong place is a write outside the app's own directory.
     *
     * Delegated to [ContainerPaths], which owns the layout, so this store and
     * [ContainerLayout.logs] can never disagree about which directory a given
     * container id names.
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
