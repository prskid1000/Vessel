package app.vessel.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import app.vessel.core.CpuLoad
import app.vessel.core.GfxStats
import app.vessel.core.MetricSample
import app.vessel.core.MetricSource
import app.vessel.core.ThermalRole
import app.vessel.core.parseCpuFreqKhz
import app.vessel.core.parseGfxStats
import app.vessel.core.parseKgslGpuBusy
import app.vessel.core.parseProcPidStatCpuTicks
import app.vessel.core.parseProcPidStatmResidentPages
import app.vessel.core.parseThermalMilliCelsius
import app.vessel.core.thermalRoleOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device, once per tick, and refuses to guess.
 *
 * ## Why every source is probed before it is used
 *
 * The sources this needs live in three different permission regimes — `/proc`,
 * vendor `sysfs`, and framework APIs — and which of them an app may read has
 * changed in most recent Android releases. Rather than assume, [sources] opens
 * each one at construction and records what happened, so a source that is not
 * readable degrades to a labelled gap on the graph and a line in the trace
 * instead of to a zero.
 *
 * What that probe found on the target hardware is written up in
 * [app.vessel.core.MetricSample]'s file. The short version: `/proc/stat` is
 * denied, so there is no device-wide CPU number; KGSL gives up `gpubusy` and
 * nothing else, so there is no GPU clock; `/sys/class/devfreq` is denied to the
 * *shell*, let alone to us, so there is no DDR clock; and this part ships no
 * ODPM energy rails, so per-rail CPU/GPU power does not exist to read.
 *
 * ## Cost
 *
 * One tick is a few dozen small reads from page-cache-resident pseudo-files plus
 * one or two Binder calls, over a process tree that is single digits long. Two
 * things are cached because they are the expensive ones: the pid set, since
 * `/proc` holds nine hundred entries and three of them are ours, and the thermal
 * zone indices, since finding them means reading ninety `type` strings. Both are
 * resolved on a schedule rather than per tick.
 */
@Singleton
class MetricSampler @Inject constructor(
    @ApplicationContext private val context: Context,
    /**
     * The app's shared configuration, for the one source here that is a document
     * rather than a number. `ignoreUnknownKeys` is what it is here for: the
     * snapshot is written by a patch against a vendored DXVK, and a counter
     * added there before this class knows about it must not make the whole line
     * unreadable.
     */
    private val json: Json,
) {
    private val activityManager: ActivityManager? =
        context.getSystemService(ActivityManager::class.java)

    private val batteryManager: BatteryManager? =
        context.getSystemService(BatteryManager::class.java)

    private val uid = Process.myUid()

    /** Eight on this part: cpu0–5 Oryon at 3.32 GHz, cpu6–7 at 3.80 GHz. */
    val cores = Runtime.getRuntime().availableProcessors()

    /** `_SC_CLK_TCK`, which is 100 everywhere Android runs but is not ours to assume. */
    private val ticksPerSecond: Long =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

    private val pageSizeBytes: Long =
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L).coerceAtLeast(1L)

    /** What each number is, where it comes from, and why it is missing when it is. */
    val sources: List<MetricSource> by lazy { probe() }

    /**
     * Each core's rated maximum, which is that core's graph ceiling.
     *
     * Per core rather than one device-wide figure, because this part is two
     * clusters at different clocks and drawing cpu7 against cpu0's 3.32 GHz
     * ceiling would show a big core at 114%.
     */
    val coreCeilingsMhz: List<Int?> by lazy {
        (0 until cores).map { readFile(maxFreqPath(it))?.let(::parseCpuFreqKhz) }
    }

    /** The fastest core on the part — the ceiling for the aggregate clock graph. */
    val clockCeilingMhz: Int? by lazy { coreCeilingsMhz.filterNotNull().maxOrNull() }

    private var cpu = CpuLoad(ticksPerSecond, cores)
    private var pids: List<Int> = emptyList()
    private var tick = 0

    /** Resolved once: which of the ninety-odd thermal zones answer our four questions. */
    private val thermalZones: Map<ThermalRole, List<Int>> by lazy { findThermalZones() }

    /** Cached because battery voltage moves over minutes and the read is a Binder call. */
    private var batteryMillivolts = 0
    private var batteryPercent = -1
    private var batteryReadAt = 0L

    /**
     * Forget the previous session's baselines.
     *
     * Called when a session starts. Without it the first sample of a new session
     * would be a delta against a reading taken before the last one ended, and the
     * graph would open on a spike that never happened.
     */
    fun reset() {
        cpu = CpuLoad(ticksPerSecond, cores)
        pids = emptyList()
        tick = 0
        batteryReadAt = 0L
    }

    /** One tick. Never throws; a source that fails contributes a null. */
    /**
     * @param fps composited frames per second, or null before the compositor
     *   has produced a sample. **Passed in rather than read**: every other field
     *   here comes from `/proc` or `/sys`, and this one comes from the X
     *   server's renderer. Giving this class a display dependency to fetch it
     *   would make the one part of the metrics story that has no Android in it
     *   depend on the part that is nothing but Android.
     * @param gfxStats the D3D layer's counter snapshot, or null when the caller
     *   has no session to point at. **The file is read here and only the path is
     *   passed in**, which is the opposite of [fps] and for a different reason.
     *   There is no dependency problem: it is a plain file, and reading files is
     *   exactly what the rest of this class does — pushing the parse out to the
     *   recorder would put half of one source's logic in a class that has no
     *   other business knowing what a `/proc` node looks like. But *which* file
     *   is a property of the session, and this class knows nothing about
     *   sessions and should not start: it has a `Context` and no container id,
     *   and giving it one would make a sampler that cannot be constructed
     *   without a container. So the caller names the file and this reads it.
     */
    fun sample(
        elapsedMs: Long,
        atMillis: Long = System.currentTimeMillis(),
        fps: Float? = null,
        gfxStats: File? = null,
    ): MetricSample {
        if (tick % RESCAN_TICKS == 0) pids = ourPids()
        tick++

        val ticksByPid = HashMap<Int, Long>(pids.size)
        var residentPages = 0L
        var sawResident = false
        val alive = ArrayList<Int>(pids.size)
        for (pid in pids) {
            val stat = readFile("$PROC/$pid/stat") ?: continue
            val cpuTicks = parseProcPidStatCpuTicks(stat) ?: continue
            ticksByPid[pid] = cpuTicks
            alive += pid
            readFile("$PROC/$pid/statm")
                ?.let(::parseProcPidStatmResidentPages)
                ?.let {
                    residentPages += it
                    sawResident = true
                }
        }
        // Drop the dead so the next tick does not keep paying for them, but keep
        // the scan schedule: a pid that exited is not a reason to go looking for
        // new ones, and a crashing prefix can churn processes for many seconds.
        pids = alive

        val clocks = coreClocksMhz()
        val present = clocks.filterNotNull()
        val memory = deviceMemory()
        refreshBattery(atMillis)
        val d3d = readGfxStats(gfxStats, atMillis)

        return MetricSample(
            elapsedMs = elapsedMs,
            cpuPercent = if (ticksByPid.isEmpty()) null else cpu.update(ticksByPid, atMillis),
            coreClocksMhz = clocks,
            clockMhz = if (present.isEmpty()) null else present.sum() / present.size,
            clockPeakMhz = present.maxOrNull(),
            gpuPercent = gpuPercent(),
            // Deliberately not read: every KGSL clock node is denied to apps on
            // this device. See `sources`, which says so out loud.
            gpuClockMhz = null,
            ramUsedMb = memory?.first,
            ramTotalMb = memory?.second,
            sessionRssMb = if (sawResident) (residentPages * pageSizeBytes / BYTES_PER_MB).toInt() else null,
            ramClockMhz = null,
            cpuTempDeciC = temperature(ThermalRole.CPU),
            gpuTempDeciC = temperature(ThermalRole.GPU),
            batteryTempDeciC = temperature(ThermalRole.BATTERY),
            skinTempDeciC = temperature(ThermalRole.SKIN),
            powerMilliwatts = powerMilliwatts(),
            cpuPowerMilliwatts = null,
            gpuPowerMilliwatts = null,
            batteryPercent = batteryPercent.takeIf { it >= 0 },
            batteryMillivolts = batteryMillivolts.takeIf { it > 0 },
            batteryMilliamps = currentMicroAmps()?.div(1000),
            fps = fps,
            d3dFps = d3d?.fps,
            d3dDrawCallsPerFrame = d3d?.drawCallsPerFrame,
            d3dDispatchesPerFrame = d3d?.dispatchesPerFrame,
            d3dRenderPassesPerFrame = d3d?.renderPassesPerFrame,
            d3dBarriersPerFrame = d3d?.barriersPerFrame,
            d3dSubmissionsPerFrame = d3d?.submissionsPerFrame,
            d3dGpuSyncsPerFrame = d3d?.gpuSyncsPerFrame,
            d3dExecuteIndirectsPerFrame = d3d?.executeIndirectsPerFrame,
            d3dExecuteIndirectCommandsPerFrame = d3d?.executeIndirectCommandsPerFrame,
            d3dCommandListsPerFrame = d3d?.commandListsPerFrame,
            d3dPipelines = d3d?.pipelinesGraphics,
            d3dPipelineLibraries = d3d?.pipelineLibraries,
            d3dPipelinesCompute = d3d?.pipelinesCompute,
            d3dPipeTasksPending = d3d?.pipeTasksPending,
            d3dMemAllocatedMb = d3d?.memAllocatedMb,
            d3dMemUsedMb = d3d?.memUsedMb,
        )
    }

    // — the individual sources -------------------------------------------------

    /**
     * Every pid sharing our uid.
     *
     * `/proc` can be listed by an app but almost nothing in it can be opened, so
     * the filter is `st_uid` on the directory rather than an attempted read of
     * the file inside it — one `stat` against one `open` plus the exception that
     * follows it, nine hundred times over.
     *
     * Under Vessel this set *is* the session: `wineserver` and every Windows
     * process are started by this app and inherit its uid, so the tree covers the
     * emulation without needing to know anything about it.
     */
    private fun ourPids(): List<Int> {
        val entries = File(PROC).list() ?: return listOf(Process.myPid())
        val found = ArrayList<Int>(8)
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            val owned = runCatching { Os.stat("$PROC/$pid").st_uid == uid }.getOrDefault(false)
            if (owned) found += pid
        }
        return if (found.isEmpty()) listOf(Process.myPid()) else found
    }

    private fun gpuPercent(): Int? = readFile(KGSL_GPUBUSY)?.let(::parseKgslGpuBusy)?.percent

    /**
     * The D3D layer's latest snapshot, or null when there is not a current one.
     *
     * **Freshness is the whole of the logic here, and it answers three cases
     * with one rule.** The producer rewrites this file at most once a second and
     * only while a Direct3D program is presenting, so a file whose last write is
     * older than [GFX_STALE_MS] means one of: no D3D program has run yet, the
     * one that was running has exited or stopped drawing, or this is a snapshot
     * left behind by an earlier session. All three are "no reading", which is a
     * gap on the graph — and drawing the last thing that happened instead would
     * put a flat line under a program that is no longer running at all, which is
     * the most convincing kind of wrong number.
     *
     * Three seconds and not one: the producer writes on a present, so a title
     * running at 20 fps with a hitch can legitimately be two seconds late, and a
     * threshold at the sample interval would flicker the series off and on for a
     * program that was drawing perfectly well.
     *
     * `lastModified` before the read, and both are cheap: a `stat` costs nothing
     * next to opening and parsing two hundred bytes, and the common case for a
     * program that uses no Direct3D is a file that is not there at all.
     */
    private fun readGfxStats(file: File?, atMillis: Long): GfxStats? {
        val target = file ?: return null
        val written = runCatching { target.lastModified() }.getOrDefault(0L)
        if (written <= 0L || atMillis - written > GFX_STALE_MS) return null
        // Bounded like every other read here, and for a related reason: this one
        // is written by a process outside this app, so its length is not ours to
        // assume even though we ship the code that writes it.
        val text = readFile(target.path) ?: return null
        return parseGfxStats(text, json)
    }

    /**
     * Whether the D3D counters are arriving, and why not when they are not.
     *
     * Not part of [sources], and the difference is real: everything in that list
     * is a fact about the device that is settled at construction and cannot
     * change during a run. This one is a fact about the *program*, and it flips
     * the moment a title creates its first D3D device — so it is asked for
     * rather than cached, and the recorder re-asks it as the session goes.
     */
    fun graphicsSource(file: File?, atMillis: Long = System.currentTimeMillis()): MetricSource {
        val reading = readGfxStats(file, atMillis)
        return MetricSource(
            label = "d3d",
            origin = file?.path ?: "the session's VESSEL_GFX_STATS snapshot",
            available = reading != null,
            reason = if (reading != null) {
                ""
            } else {
                "no Direct3D counters: DXVK (D3D 8/9/10/11) and vkd3d (D3D 12) write them " +
                    "only while a program is drawing, so a session that runs nothing " +
                    "graphical — or one whose game has exited — leaves these columns empty. " +
                    "This is not a source that failed."
            },
        )
    }

    /**
     * The current clock of every core, indexed by cpu number.
     *
     * A parked or offline core's file fails to read and stays null rather than
     * becoming a zero: a big core the scheduler has put to sleep has no clock,
     * and averaging a zero into the mean would report a thermal throttle that is
     * not happening. The list keeps its length so index *is* cpu number.
     */
    private fun coreClocksMhz(): List<Int?> =
        (0 until cores).map { readFile(scalingCurFreq(it))?.let(::parseCpuFreqKhz) }

    /** Used and total device RAM in MB, from the framework rather than `/proc/meminfo`. */
    private fun deviceMemory(): Pair<Int, Int>? {
        val manager = activityManager ?: return null
        return runCatching {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            val total = (info.totalMem / BYTES_PER_MB).toInt()
            val used = ((info.totalMem - info.availMem) / BYTES_PER_MB).toInt()
            used to total
        }.getOrNull()
    }

    /**
     * Which zones answer each question, resolved once by reading their `type`.
     *
     * By name and never by index: this device numbers ninety-odd zones and the
     * order is not stable across boots, so `thermal_zone7` meaning a CPU today is
     * not a promise about tomorrow. Several zones share a role — eight `gpuss-*`
     * slices, one per shader block — and all of them are kept so the reading can
     * be the hottest, which is the one that throttles.
     */
    private fun findThermalZones(): Map<ThermalRole, List<Int>> {
        val byRole = HashMap<ThermalRole, MutableList<Int>>()
        for (zone in 0 until MAX_THERMAL_ZONES) {
            val type = readFile("$THERMAL/thermal_zone$zone/type") ?: break
            val role = thermalRoleOf(type) ?: continue
            byRole.getOrPut(role) { mutableListOf() } += zone
        }
        return byRole
    }

    /** The hottest zone of a role — the one that decides whether the part throttles. */
    private fun temperature(role: ThermalRole): Int? =
        thermalZones[role]
            ?.mapNotNull { readFile("$THERMAL/thermal_zone$it/temp")?.let(::parseThermalMilliCelsius) }
            ?.maxOrNull()

    /**
     * Battery level and voltage, refreshed on a slow schedule.
     *
     * From the sticky broadcast, which a null receiver reads synchronously — that
     * is a query of the last broadcast rather than a registration, so nothing is
     * subscribed and nothing has to be unregistered.
     * The `power_supply` sysfs nodes hold the same numbers and are denied to
     * apps here, which is why this goes the long way round.
     */
    private fun refreshBattery(atMillis: Long) {
        if (atMillis - batteryReadAt <= BATTERY_INTERVAL_MS) return
        batteryReadAt = atMillis
        runCatching {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@runCatching
            sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }
                ?.let { batteryMillivolts = it }
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
        }
    }

    /**
     * Instantaneous battery current in microamps, or null when unsupported.
     *
     * `BATTERY_PROPERTY_CURRENT_NOW` returns [Int.MIN_VALUE] when the health HAL
     * does not supply it, which is a value and not an error — checking for it is
     * the only way to tell a device that cannot measure current from one drawing
     * none. **Zero is a reading**: a phone on the wall with a full battery draws
     * exactly nothing, and this device reports a flat `0` there. Treating that as
     * unavailable was an earlier version's bug, and it made the trace assert the
     * HAL could not measure current on a device where it plainly could.
     */
    private fun currentMicroAmps(): Int? {
        val manager = batteryManager ?: return null
        val microAmps = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE)
        return microAmps.takeIf { it != Int.MIN_VALUE }
    }

    /**
     * Total draw, as current × voltage. The sign is the platform's: positive is
     * current entering the battery, so a charging phone reports a positive number
     * and the UI has to say so rather than draw it as consumption.
     */
    private fun powerMilliwatts(): Int? {
        val microAmps = currentMicroAmps() ?: return null
        if (batteryMillivolts <= 0) return null
        return (microAmps.toLong() * batteryMillivolts / MICRO_PER_MILLI_SQUARED).toInt()
    }

    // — availability -----------------------------------------------------------

    private fun probe(): List<MetricSource> {
        refreshBattery(System.currentTimeMillis())
        return listOf(
            source(
                label = "cpu",
                origin = "$PROC/<pid>/stat over the session process tree",
                reading = readFile("$PROC/${Process.myPid()}/stat"),
                denied = "$PROC/<pid>/stat cannot be read.",
            ),
            // Not a source that failed — a source that does not exist for an app
            // on this platform. Stated as its own row because "why is there no
            // total CPU line" is the first question this panel invites.
            MetricSource(
                label = "cpu total",
                origin = "$PROC/stat",
                available = false,
                reason = "$PROC/stat is denied to apps on Android 16, so no device-wide " +
                    "CPU figure exists. Per-core cpuidle residency was measured as a " +
                    "substitute and rejected: it reported 14% busy against a true 2%, " +
                    "because this SoC exposes only two idle states and deeper cluster " +
                    "collapse is never counted as idle.",
            ),
            source(
                label = "gpu",
                origin = "$KGSL/gpubusy, device-wide",
                reading = readFile(KGSL_GPUBUSY),
                denied = "$KGSL/gpubusy is not readable in this app sandbox.",
            ),
            MetricSource(
                label = "gpu clock",
                origin = "$KGSL/gpuclk",
                available = false,
                reason = "every KGSL node except gpubusy and gpu_model is denied to apps " +
                    "here, and there is no devfreq directory for the GPU to read instead.",
            ),
            source(
                label = "clock",
                origin = "${scalingCurFreq(0)}, per core across $cores cores",
                reading = readFile(scalingCurFreq(0)),
                denied = "${scalingCurFreq(0)} is not readable.",
            ),
            source(
                label = "ram",
                origin = "ActivityManager.MemoryInfo, plus $PROC/<pid>/statm for the session",
                reading = if (deviceMemory() == null) null else "ok",
                denied = "ActivityManager did not return a memory reading.",
            ),
            MetricSource(
                label = "ram clock",
                origin = "/sys/class/devfreq/*/cur_freq",
                available = false,
                reason = "/sys/class/devfreq is denied to the shell as well as to apps on " +
                    "this device, and the DDR governor exposes no other node.",
            ),
            source(
                label = "temperature",
                origin = "$THERMAL/thermal_zone*, matched by type " +
                    "(${thermalZones.keys.joinToString(", ") { it.name.lowercase() }})",
                reading = if (thermalZones.isEmpty()) null else "ok",
                denied = "no readable thermal zone matched a CPU, GPU, battery or skin type.",
            ),
            source(
                label = "battery",
                origin = "BatteryManager and the ACTION_BATTERY_CHANGED broadcast",
                reading = if (batteryPercent < 0 && batteryMillivolts <= 0) null else "ok",
                denied = "the battery broadcast carried neither a level nor a voltage.",
            ),
            source(
                label = "power",
                origin = "BATTERY_PROPERTY_CURRENT_NOW × the broadcast's voltage, total draw",
                reading = if (powerMilliwatts() == null) null else "ok",
                denied = "BatteryManager returned no current reading, or the battery " +
                    "broadcast carried no voltage to multiply it by.",
            ),
            MetricSource(
                label = "cpu/gpu power",
                origin = "/sys/bus/iio/devices/*/energy_value",
                available = false,
                reason = "this device ships no ODPM energy rails — the IIO devices expose " +
                    "charger current and PMIC temperatures only — so per-rail draw cannot " +
                    "be measured. It is deliberately not estimated by splitting the total.",
            ),
        )
    }

    private fun source(label: String, origin: String, reading: String?, denied: String) =
        MetricSource(
            label = label,
            origin = origin,
            available = reading != null,
            reason = if (reading != null) "" else denied,
        )

    /**
     * A pseudo-file, or null.
     *
     * Bounded by [MAX_READ_BYTES] because these are files whose size `stat`
     * reports as zero and whose real length is whatever the kernel decides to
     * print — reading one unbounded is a promise about a number we do not
     * control.
     */
    private fun readFile(path: String): String? = runCatching {
        File(path).inputStream().use { stream ->
            val buffer = ByteArray(MAX_READ_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) return null
            String(buffer, 0, read, Charsets.US_ASCII)
        }
    }.getOrNull()

    private fun scalingCurFreq(core: Int) =
        "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"

    private fun maxFreqPath(core: Int) =
        "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq"

    private companion object {
        const val PROC = "/proc"
        const val THERMAL = "/sys/class/thermal"

        /**
         * Adreno's busy counter. The path is fixed on every Qualcomm part this
         * product targets; `kgsl-3d0` is the only 3D device node the driver makes.
         */
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        const val KGSL_GPUBUSY = "$KGSL/gpubusy"

        /** Ten seconds at 1 Hz, which is faster than a prefix grows processes. */
        const val RESCAN_TICKS = 10

        /** This device numbers about ninety; the loop stops at the first gap anyway. */
        const val MAX_THERMAL_ZONES = 128

        const val BATTERY_INTERVAL_MS = 10_000L

        /** Longer than any of these files ever prints, and short enough to be free. */
        const val MAX_READ_BYTES = 2048

        /**
         * How old the D3D snapshot may be and still be a reading.
         *
         * Three sample intervals. The producer writes on a present, so a title
         * at 20 fps with a hitch is legitimately late, and a threshold at one
         * interval would flicker the series off and on under a program that was
         * drawing perfectly well.
         */
        const val GFX_STALE_MS = 3_000L

        const val BYTES_PER_MB = 1024L * 1024L

        /** µA × mV lands in nanowatts; this brings it back to milliwatts. */
        const val MICRO_PER_MILLI_SQUARED = 1_000_000L
    }
}
