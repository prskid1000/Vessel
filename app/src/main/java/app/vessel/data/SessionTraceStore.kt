package app.vessel.data

import android.content.Context
import app.vessel.core.MetricSample
import app.vessel.core.MetricSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one session's telemetry was, kept beside its log.
 *
 * The first line of a trace file. Everything here is fixed for the run, so it is
 * written once rather than repeated in every sample.
 */
@Serializable
data class SessionTraceHeader(
    val schemaVersion: Int = SESSION_TRACE_SCHEMA,
    val containerId: String = "",
    val startedAt: Long = 0L,
    /** Nominal milliseconds between samples; the real gap is in each sample. */
    val intervalMs: Int = 1_000,
    val cores: Int = 0,
    /** Each core's rated maximum, so a replayed graph scales the way the live one did. */
    val coreCeilingsMhz: List<Int?> = emptyList(),
    val ramTotalMb: Int? = null,
    /** Why a column is missing, recorded at the time rather than re-probed on read. */
    val sources: List<MetricSource> = emptyList(),
)

/** A whole run, as the Metrics tab redraws it. */
data class SessionTrace(
    val header: SessionTraceHeader,
    val samples: List<MetricSample>,
) {
    val isEmpty: Boolean get() = samples.isEmpty()
}

/**
 * Bumped when the trace's shape changes in a way a reader has to know about.
 *
 * **2** adds the `d3d*` columns — DXVK's own per-frame counters, pipeline
 * populations and video memory — and stops writing fields that hold their
 * default. Both directions still read: a version 1 trace has no `d3d*` keys and
 * every one of them defaults to null, and a version 2 trace read by a build that
 * predates them is covered by `ignoreUnknownKeys`. Nothing here has ever been
 * renamed or removed, which is the change that would actually need a reader to
 * branch on this number; it is a statement of what a file contains rather than a
 * gate, and no code compares it.
 */
const val SESSION_TRACE_SCHEMA: Int = 2

/**
 * Reads and writes the per-session telemetry sidecar.
 *
 * ## Why a file of its own, and not lines in the log
 *
 * Metrics used to be written into the session log as `metrics t+30s …` lines.
 * They are not any more, and the reason is the log's job: it is Wine's output,
 * and it is read by someone hunting a stack of `err:` lines. A telemetry line
 * every second is noise in exactly the place where noise costs the most. The
 * separation also means the Metrics tab can redraw a finished run without
 * parsing prose back into numbers, which was the alternative and is a parser
 * nobody should have to maintain.
 *
 * ## Why JSON Lines and not one JSON document
 *
 * **Because sessions on this device usually die.** A trace has to survive its
 * writer being killed mid-run, and a single serialized object only exists once
 * it has been written whole — kill the process and there is no trace at all, for
 * precisely the runs most worth looking at. One object per line appends in
 * constant time, needs no rewrite of what came before, and a half-written final
 * line is dropped by the reader while every complete line before it survives.
 * Truncated-but-valid beats atomic-or-nothing here.
 *
 * The writer is flushed on every append. At one sample a second that is a few
 * hundred bytes and one `write` syscall, which is cheaper than the sampling that
 * produced the sample, and it is what makes "killed abruptly" lose nothing.
 */
@Singleton
class SessionTraceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    shared: Json,
) {
    /**
     * The app's JSON configuration with pretty-printing forced off.
     *
     * Not a detail. The shared instance sets `prettyPrint = true` so that a
     * container's sidecar can be read over adb, and a pretty-printed object
     * spans a dozen lines — which would silently destroy a format whose every
     * robustness property comes from one object occupying exactly one line.
     * Derived from the shared config rather than built from scratch so that
     * `ignoreUnknownKeys`, which is what lets an older build read a newer
     * trace, cannot be lost here if it is retuned there.
     */
    private val json = Json(from = shared) { prettyPrint = false }

    /**
     * The same configuration again, with fields that hold their default left out.
     *
     * **Used for samples only, and it is a size decision rather than a taste
     * one.** The shared instance sets `encodeDefaults = true`, so every sample
     * was carrying `"gpuClockMhz":null`, `"ramClockMhz":null`,
     * `"cpuPowerMilliwatts":null` and `"gpuPowerMilliwatts":null` — four columns
     * this device can never fill — in every line, for the whole of every run.
     * Adding thirteen `d3d*` fields on top of that would have grown a
     * twelve-hour trace by tens of megabytes to say "no Direct3D program ran"
     * forty-three thousand times.
     *
     * Omitting them costs nothing on read: every field of [MetricSample] except
     * `elapsedMs` has a default, and the default *is* the null the key would
     * have carried. A session that runs no D3D program now writes a **smaller**
     * trace than before this feature existed, and one that does writes the
     * counters and not the absences.
     *
     * The header keeps [json] and its defaults. It is one line per file, so
     * nothing is saved by shortening it, and `schemaVersion` equal to its
     * default is exactly the case where a header must still state it.
     */
    private val compact = Json(from = shared) {
        prettyPrint = false
        encodeDefaults = false
    }

    private val root: File get() = File(context.filesDir, LOGS_DIRECTORY)

    /**
     * Open a trace for a session that is starting.
     *
     * Truncates anything already there: a second run cannot share a `startedAt`
     * with a first, so an existing file is debris from a previous install.
     */
    fun open(header: SessionTraceHeader): SessionTraceWriter? = runCatching {
        val directory = directoryFor(header.containerId)
        directory.mkdirs()
        val stream = FileOutputStream(traceFile(directory, header.startedAt), false)
        val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), BUFFER_BYTES)
        writer.write(json.encodeToString(SessionTraceHeader.serializer(), header))
        writer.newLine()
        writer.flush()
        SessionTraceWriter(writer, compact)
    }.getOrNull()

    /**
     * Read a finished run back, or null when there is no trace for it.
     *
     * Null covers three cases that the caller treats alike: the session predates
     * this feature, it produced no samples, or the file is unreadable. All three
     * mean "nothing to draw", and the panel says so rather than guessing which.
     */
    suspend fun read(containerId: String, startedAt: Long): SessionTrace? =
        withContext(Dispatchers.IO) {
            val file = traceFile(directoryFor(containerId), startedAt)
            if (!file.isFile) return@withContext null
            runCatching {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    parseTrace(reader.lineSequence(), json)
                }
            }.getOrNull()
        }

    /** Whether a session left a trace, without paying to parse it. */
    fun exists(containerId: String, startedAt: Long): Boolean =
        traceFile(directoryFor(containerId), startedAt).isFile

    private fun directoryFor(containerId: String) =
        File(root, ContainerPaths.safeName(containerId))

    private companion object {
        /** The same directory the logs live in, so one delete removes both. */
        const val LOGS_DIRECTORY = "logs"
        const val BUFFER_BYTES = 8 * 1024
    }
}

/**
 * Header line, then one sample per line, stopping at the first line that will
 * not parse.
 *
 * Separated from the file handling so the case that matters can be tested
 * without a device: **the last line of a real trace is routinely half-written**,
 * because the process was killed between the `write` and the `flush` that would
 * have completed it. Stopping there and keeping everything before it is the
 * whole reason the format is one object per line.
 *
 * A file whose *first* line will not parse has no header and is no trace at all
 * — that is a truncated create rather than a truncated append, and there is
 * nothing under it to salvage.
 */
internal fun parseTrace(lines: Sequence<String>, json: Json): SessionTrace? {
    var header: SessionTraceHeader? = null
    val samples = ArrayList<MetricSample>(INITIAL_TRACE_CAPACITY)
    for (line in lines) {
        if (line.isBlank()) continue
        if (header == null) {
            header = runCatching {
                json.decodeFromString(SessionTraceHeader.serializer(), line)
            }.getOrNull() ?: return null
            continue
        }
        if (samples.size >= MAX_TRACE_SAMPLES) break
        val sample = runCatching {
            json.decodeFromString(MetricSample.serializer(), line)
        }.getOrNull() ?: break
        samples += sample
    }
    return header?.let { SessionTrace(it, samples) }
}

private const val INITIAL_TRACE_CAPACITY = 256

/**
 * Twelve hours at 1 Hz. A bound on what one read turns into objects, not on what
 * the file may hold.
 */
private const val MAX_TRACE_SAMPLES = 43_200

/**
 * The append side, held by the recorder for the life of a session.
 *
 * Nothing here throws. A trace is a nicety next to the session it describes, and
 * the first failed write puts the writer into a state where it accepts samples
 * and discards them — the same contract [SessionLog] has, for the same reason.
 */
class SessionTraceWriter internal constructor(
    private var writer: BufferedWriter?,
    private val json: Json,
) {
    fun append(sample: MetricSample) {
        val target = writer ?: return
        val ok = runCatching {
            target.write(json.encodeToString(MetricSample.serializer(), sample))
            target.newLine()
            // Flushed per sample on purpose: see the class comment on
            // SessionTraceStore. A session that is killed must still leave every
            // sample it took, and at 1 Hz this costs one small write a second.
            target.flush()
            true
        }.getOrDefault(false)
        if (!ok) close()
    }

    fun close() {
        val target = writer ?: return
        writer = null
        runCatching {
            target.flush()
            target.close()
        }
    }
}
