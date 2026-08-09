package app.vessel.core

import kotlinx.serialization.Serializable

/**
 * What a running session costs, and the arithmetic that recovers it.
 *
 * Everything here is pure and has no Android in it, for the same reason
 * [parseSessionLogLine] is: this is the half of the metrics feature that can be
 * wrong without anybody noticing. A graph that draws a plausible-looking line
 * from a misread counter is worse than a graph that draws nothing, because the
 * wrong number is the one that gets quoted in a bug report.
 *
 * ## What this device actually lets an app read
 *
 * Every path below was read as the app's own uid on the target hardware
 * (SM8845, Android 16, kernel 6.12.38) and cross-checked against the device's
 * own loaded SELinux policy through `/sys/fs/selinux/access`. `adb shell` alone
 * proves nothing here — it runs in a different domain and is granted strictly
 * more than an app is.
 *
 * Readable: `/proc/<pid>/stat` and `statm` for our own uid, KGSL `gpubusy`,
 * `scaling_cur_freq` per core, every `thermal_zone*`.
 *
 * Not readable, and therefore reported as unavailable rather than substituted:
 * `/proc/stat` (so there is **no** device-wide CPU figure), KGSL `gpuclk` and
 * every other KGSL node except `gpubusy` and `gpu_model`, `/sys/class/devfreq`
 * (so no DDR frequency), the `power_supply` nodes, and the IIO devices. This
 * part ships no ODPM `energy_value` at all, so per-rail CPU and GPU power do not
 * exist to be read — see [MetricSample.cpuPowerMilliwatts].
 *
 * A note on one rejected source, because "we tried and it was wrong" is worth
 * more than a silent omission: each core's `cpuidle` state residency **is**
 * readable and looks like a way to recover device-wide CPU as
 * `1 − Δidle/Δwall`. Measured
 * against `/proc/stat` read from the shell over the same window it reported
 * 14.1% busy on a device that was genuinely 2.1% busy, because this SoC exposes
 * only two idle states and time spent in deeper cluster collapse is never
 * counted as idle. It is not a 12-point error to calibrate away; it is a floor
 * under every reading. Total CPU is therefore unavailable.
 */

/** Where one number came from, and what to say when it did not arrive. */
@Serializable
data class MetricSource(
    val label: String,
    /** The path or API consulted, quoted verbatim so a bug report can repeat it. */
    val origin: String,
    val available: Boolean,
    /** Why not, in a sentence a user can act on. Empty when [available]. */
    val reason: String = "",
)

/**
 * One tick of sampling.
 *
 * Every field is nullable and a null means *not measured*, never zero. The
 * distinction is the whole point of the type: an idle GPU and a GPU whose
 * counter we cannot read look identical if both are stored as 0.
 *
 * Serialized straight into the session's trace sidecar, so field names are a
 * storage format — rename one and old traces lose that series. New fields must
 * carry a default for the same reason.
 */
@Serializable
data class MetricSample(
    /** Milliseconds since the session's first sample, so the x-axis needs no clock. */
    val elapsedMs: Long,

    // — processor —
    /**
     * 0–100 across all cores, for the session's process tree.
     *
     * Not the device. `/proc/stat` is denied to apps on this platform, so this
     * counts only processes sharing our uid — which under Vessel is the whole
     * emulation, and is still a different number from system load. Everything
     * that displays it says "session".
     */
    val cpuPercent: Int? = null,
    /** Current clock of each core, indexed by cpu number; null where unreadable. */
    val coreClocksMhz: List<Int?> = emptyList(),
    /** Mean current clock across the cores that reported one. */
    val clockMhz: Int? = null,
    /** Highest current clock of any core, which is where a thermal cap shows first. */
    val clockPeakMhz: Int? = null,

    // — graphics —
    /** 0–100, device-wide: KGSL counts every client of the GPU, not only us. */
    val gpuPercent: Int? = null,
    /** Adreno clock. Unavailable on this device — `gpuclk` is denied to apps. */
    val gpuClockMhz: Int? = null,

    // — memory —
    /** Device RAM in use, from `ActivityManager.MemoryInfo`. */
    val ramUsedMb: Int? = null,
    val ramTotalMb: Int? = null,
    /**
     * Resident memory of the session's own process tree.
     *
     * The number worth watching: device RAM moves for reasons that have nothing
     * to do with the container, and this one does not. Summed over the same pids
     * the CPU figure uses, so it costs one extra file read per process and no
     * extra walk.
     */
    val sessionRssMb: Int? = null,
    /** DDR clock. Unavailable — `/sys/class/devfreq` is denied even to the shell. */
    val ramClockMhz: Int? = null,

    // — thermal, in tenths of a degree so a trace stays integers —
    val cpuTempDeciC: Int? = null,
    val gpuTempDeciC: Int? = null,
    val batteryTempDeciC: Int? = null,
    /** The chassis proxy — `quiet-therm` here. What the user actually feels. */
    val skinTempDeciC: Int? = null,

    // — power —
    /** Total battery draw. Negative is charging; graphs plot the magnitude. */
    val powerMilliwatts: Int? = null,
    /**
     * Per-rail draw, and always null on this device.
     *
     * Kept as fields rather than dropped because their absence is a fact worth
     * stating: splitting the total by a guessed ratio would be the single most
     * tempting fabrication this panel could commit, and a named null is what
     * stops someone adding it later "just as an estimate".
     */
    val cpuPowerMilliwatts: Int? = null,
    val gpuPowerMilliwatts: Int? = null,

    // — battery —
    val batteryPercent: Int? = null,
    val batteryMillivolts: Int? = null,
    /** Instantaneous current in mA. Negative is discharging, per the platform. */
    val batteryMilliamps: Int? = null,

    // — display —
    /**
     * Composited frames per second over the sample window.
     *
     * **The only figure in this record that comes from inside Vessel rather than
     * from the kernel**, and the only one that is therefore exactly right: it is
     * a counter this app increments and a clock this app reads, with nothing to
     * misparse. Every other field here is a `/proc` or `/sys` node that may be
     * unreadable, which is why they are all nullable — this one is null only
     * before the compositor has produced its first sample.
     *
     * Frames *delivered*, not a program's internal rate: Vessel composites on
     * damage, so a title rendering faster than the surface reads as the surface
     * and an idle desktop reads 0. Zero is a measurement here, not a gap — see
     * [app.vessel.core.FrameRate].
     *
     * A Float, and stored as one. Rounding at the sample would turn a steady
     * 29.6 into an alternating 29/30 and put a sawtooth in a graph of something
     * that was not changing.
     */
    val fps: Float? = null,
)

/**
 * A bounded window of samples.
 *
 * A ring rather than a growing list: a session left running overnight is 28 800
 * samples, and the graph can only draw as many points as it has pixels. Holding
 * more than the window would cost memory to store detail no one can see.
 */
class MetricHistory private constructor(
    private val samples: List<MetricSample>,
    val capacity: Int,
) {
    constructor(capacity: Int = DEFAULT_CAPACITY) : this(emptyList(), capacity)

    val size: Int get() = samples.size
    val isEmpty: Boolean get() = samples.isEmpty()
    val latest: MetricSample? get() = samples.lastOrNull()

    /** How long the window spans, which for a replayed trace is the run's length. */
    val elapsedMs: Long get() = samples.lastOrNull()?.elapsedMs ?: 0L

    operator fun plus(sample: MetricSample): MetricHistory {
        val next = if (samples.size < capacity) samples + sample else samples.drop(1) + sample
        return MetricHistory(next, capacity)
    }

    /** One field across the window, nulls preserved so the graph can draw a gap. */
    fun series(field: (MetricSample) -> Int?): List<Int?> = samples.map(field)

    /** The nth core's clock across the window. Out-of-range indices are all gaps. */
    fun coreSeries(core: Int): List<Int?> = samples.map { it.coreClocksMhz.getOrNull(core) }

    /** Every derived number one card prints, or null if the field never reported. */
    fun stats(field: (MetricSample) -> Int?): MetricStats? {
        val present = samples.mapNotNull(field)
        if (present.isEmpty()) return null
        return MetricStats(
            current = samples.lastOrNull()?.let(field),
            min = present.min(),
            peak = present.max(),
            mean = present.sum() / present.size,
            first = present.first(),
            samples = present.size,
            gaps = samples.size - present.size,
        )
    }

    fun peak(field: (MetricSample) -> Int?): Int? = samples.mapNotNull(field).maxOrNull()

    /**
     * Frame-rate statistics, which are not the same statistics as everything
     * else on this panel.
     *
     * **`peak / mean / min` is the wrong summary for frames and the right one
     * for every other field here.** A single dropped frame is a `min` of 0 and
     * tells you nothing; what a user feels is the *sustained* worst case, which
     * is why the whole industry reports a 1% low. So this returns its own type
     * rather than reusing [MetricStats].
     */
    fun frameStats(): FrameStats? {
        val present = samples.mapNotNull { it.fps }
        if (present.isEmpty()) return null
        val sorted = present.sorted()
        // The mean of the slowest 1%, with a floor of one sample. This is the
        // definition CapFrameX and most reviewers use; the other one in
        // circulation is "the value at the 1st percentile", which on a short run
        // is a single sample and jumps around. Written down because a 1% low
        // whose definition is not stated is a number nobody can compare with
        // anything.
        val worstCount = maxOf(1, sorted.size / 100)
        return FrameStats(
            current = samples.lastOrNull()?.fps,
            min = sorted.first(),
            peak = sorted.last(),
            mean = present.sum() / present.size,
            onePercentLow = sorted.take(worstCount).sum() / worstCount,
            samples = present.size,
        )
    }

    fun mean(field: (MetricSample) -> Int?): Int? {
        val present = samples.mapNotNull(field)
        return if (present.isEmpty()) null else present.sum() / present.size
    }

    fun toList(): List<MetricSample> = samples

    companion object {
        /**
         * Twenty minutes at 1 Hz. Long enough to show a thermal ramp — the thing
         * anyone actually looks at this graph for — and short enough that the
         * whole window is one screen's worth of columns.
         */
        const val DEFAULT_CAPACITY: Int = 1_200

        /** Rebuild a window from a stored trace, keeping the most recent samples. */
        fun of(samples: List<MetricSample>, capacity: Int = DEFAULT_CAPACITY): MetricHistory =
            MetricHistory(samples.takeLast(capacity), capacity)
    }
}

/**
 * The numbers printed under one graph.
 *
 * The reference implementation puts these in a table at the bottom of the
 * screen; here they belong to the card, because five metrics with five different
 * units share no rows and a single table would need a unit column to be legible
 * at all.
 */
data class MetricStats(
    val current: Int?,
    val min: Int,
    val peak: Int,
    val mean: Int,
    /** The first reading of the run, so a level can be shown as a change. */
    val first: Int,
    /** How many ticks produced a reading. */
    val samples: Int,
    /** How many did not — a source that drops out says so. */
    val gaps: Int,
) {
    /** How far the value moved from where the run started. */
    val delta: Int get() = (current ?: peak) - first

    /** True when the value never moved, so a card can print one number not a range. */
    val flat: Boolean get() = min == peak
}

/**
 * What a run's frame rate did, summarised the way frame rates are summarised.
 *
 * No `gaps` field, and that is the difference from [MetricStats]. Every other
 * metric on the panel is a `/proc` node that can be unreadable, so "how many
 * ticks produced no reading" is real information. Frames are counted by this
 * app, so a sample is never missing — a zero is a measured zero, meaning the
 * guest drew nothing, which is a fact about the guest and not a gap in the
 * instrument.
 */
data class FrameStats(
    val current: Float?,
    val min: Float,
    val peak: Float,
    val mean: Float,
    /**
     * The mean of the slowest 1% of samples, floor one.
     *
     * The number that describes stutter. A run averaging 60 with a 1% low of 12
     * and a run averaging 60 with a 1% low of 55 feel like different games, and
     * `mean` alone cannot tell them apart.
     */
    val onePercentLow: Float,
    val samples: Int,
) {
    /** True when the guest never drew at all — an idle session, not a stalled one. */
    val neverDrew: Boolean get() = peak <= 0f
}

/**
 * CPU time in, a percentage out.
 *
 * Stateful because a jiffy counter is cumulative: the answer is always a
 * difference between two readings, and the first reading of a session can only
 * establish the baseline. [update] returns null for it rather than reporting the
 * process's whole lifetime of CPU as one second's worth.
 *
 * Keyed by pid so a Windows process starting or exiting mid-session cannot
 * corrupt the total. Only pids seen in *both* readings contribute: a pid that
 * appeared since the last tick has no baseline to subtract, and counting its
 * cumulative total would show a spike to 100% every time an installer spawned a
 * child. Pids that vanished are simply dropped, which loses the final fraction
 * of a second of a process that exited — the honest cost of not being able to
 * observe a process after it is gone.
 */
class CpuLoad(
    private val ticksPerSecond: Long,
    private val cores: Int,
) {
    private var previous: Map<Int, Long> = emptyMap()
    private var previousAt: Long = 0L

    /**
     * Explicit, rather than inferring "no baseline yet" from a zero timestamp.
     * The clock this is called with is the caller's business — a test passes an
     * elapsed time that legitimately starts at zero — and a sentinel that is also
     * a valid value is a bug waiting for the one caller who uses it.
     */
    private var sampled = false

    fun update(ticksByPid: Map<Int, Long>, atMillis: Long): Int? {
        val before = previous
        val beforeAt = previousAt
        val hadBaseline = sampled
        previous = ticksByPid
        previousAt = atMillis
        sampled = true

        if (!hadBaseline || before.isEmpty()) return null
        val elapsedMs = atMillis - beforeAt
        if (elapsedMs <= 0L || ticksPerSecond <= 0L || cores <= 0) return null

        var deltaTicks = 0L
        for ((pid, now) in ticksByPid) {
            val then = before[pid] ?: continue
            // A pid can be recycled onto a shorter-lived process, which reads as
            // a counter going backwards. Clamping is the only sane answer; the
            // alternative is a negative contribution that cancels real work.
            if (now > then) deltaTicks += now - then
        }

        val capacityTicks = elapsedMs.toDouble() / 1000.0 * ticksPerSecond * cores
        if (capacityTicks <= 0.0) return null
        return (deltaTicks / capacityTicks * 100.0).toInt().coerceIn(0, 100)
    }
}

/** One reading of KGSL's busy counter, in microseconds. */
data class GpuBusy(val busyMicros: Long, val totalMicros: Long) {
    /**
     * The percentage, where a window of zero means the GPU was powered down.
     *
     * That is a genuine 0% and not a missing reading — see [parseKgslGpuBusy].
     */
    val percent: Int
        get() = if (totalMicros <= 0L) 0 else (busyMicros * 100 / totalMicros).toInt().coerceIn(0, 100)
}

/**
 * `  25641 1004321` → busy and total microseconds.
 *
 * **This is not a delta counter, and reading it as one produces nonsense.** KGSL
 * publishes the last *completed* measurement window of its own power governor,
 * which is about 1.004 s wide and advances on the governor's schedule rather than
 * ours. Two reads inside one window return byte-identical numbers — observed on
 * device, twice in a row at 1 Hz — so a caller that subtracts consecutive reads
 * gets zero for a busy GPU.
 *
 * A read also has a side effect the kernel source makes explicit: the counters
 * are zeroed after being printed *if the GPU bus is off*. `0 0` therefore means
 * the GPU is powered down, which is a real 0% and the one case where a zero here
 * is not a fabrication. Anything unparseable is null, which is the missing
 * reading.
 */
fun parseKgslGpuBusy(text: String): GpuBusy? {
    val fields = text.trim().split(WHITESPACE)
    if (fields.size < 2) return null
    val busy = fields[0].toLongOrNull() ?: return null
    val total = fields[1].toLongOrNull() ?: return null
    if (busy < 0L || total < 0L) return null
    return GpuBusy(busy, total)
}

/**
 * `utime + stime` from one line of `/proc/<pid>/stat`, in clock ticks.
 *
 * The parse starts at the **last** `)` rather than splitting on spaces, because
 * field two is the executable name unquoted and unescaped: a Windows program
 * called `Rocket League (Epic).exe` puts both a space and a parenthesis inside
 * it, and every naive split lands on the wrong column from there on. Wine
 * processes are named after the executable, so this is a case that will happen.
 */
fun parseProcPidStatCpuTicks(line: String): Long? {
    val commEnd = line.lastIndexOf(')')
    if (commEnd < 0 || commEnd + 2 >= line.length) return null
    val fields = line.substring(commEnd + 2).split(WHITESPACE)
    // Fields resume at `state`, which is number 3 in proc(5)'s one-based
    // numbering, so utime (14) and stime (15) sit at offsets 11 and 12.
    if (fields.size <= STIME_OFFSET) return null
    val utime = fields[UTIME_OFFSET].toLongOrNull() ?: return null
    val stime = fields[STIME_OFFSET].toLongOrNull() ?: return null
    if (utime < 0L || stime < 0L) return null
    return utime + stime
}

/**
 * Resident pages from one line of `/proc/<pid>/statm`.
 *
 * Field two, and no parenthesised name to step over — `statm` is seven plain
 * numbers, which is why the session's memory is read from here rather than from
 * the `rss` column of `stat` beside it.
 */
fun parseProcPidStatmResidentPages(line: String): Long? {
    val fields = line.trim().split(WHITESPACE)
    if (fields.size < 2) return null
    val resident = fields[1].toLongOrNull() ?: return null
    return if (resident < 0L) null else resident
}

/** A `cpufreq` file's kHz reading as whole MHz, or null when it did not parse. */
fun parseCpuFreqKhz(text: String): Int? {
    val khz = text.trim().toLongOrNull() ?: return null
    if (khz <= 0L) return null
    return (khz / 1000L).toInt()
}

/**
 * A thermal zone's `temp` reading as tenths of a degree.
 *
 * The kernel prints millidegrees here and degrees on a few older platforms; a
 * reading above [MILLIDEGREE_THRESHOLD] is millidegrees, below it is already
 * degrees. Both appear on Qualcomm parts, and getting it wrong is a graph that
 * says the phone is at 40 000 °C — which at least fails loudly, unlike most of
 * the mistakes available here.
 *
 * Zero is rejected. Several zones on this device (`pm8550-bcl-lvl*`) are
 * threshold flags rather than sensors and sit permanently at 0, and a 0.0 °C
 * line on a temperature graph is a fabrication.
 */
fun parseThermalMilliCelsius(text: String): Int? {
    val raw = text.trim().toLongOrNull() ?: return null
    if (raw <= 0L) return null
    val deciC = if (raw >= MILLIDEGREE_THRESHOLD) raw / 100L else raw * 10L
    return if (deciC > MAX_PLAUSIBLE_DECI_C) null else deciC.toInt()
}

/**
 * Which of the four temperatures a zone's `type` string is, or null to ignore it.
 *
 * Matched on the type rather than the index because the index is not stable
 * across boots, let alone across devices. The names are this SoC's:
 * `cpu-0-0-0`… per core, `cpullc-*` for the cluster caches, `gpuss-0`… for the
 * graphics subsystem, `batt-therm`, and `quiet-therm` for the chassis. Prefix
 * matching rather than equality so the per-core and per-slice zones all fold
 * into one reading.
 *
 * **Threshold zones are rejected first, and that rejection is load-bearing.**
 * This device registers `cpu-hw-trip-0` and `cpu-hw-trip-1` alongside the real
 * sensors, both pinned at 105 °C — they are the hardware shutdown trip, not a
 * measurement. They match every sensible `cpu` prefix, and because a role's
 * reading is the hottest of its zones they won every single sample: the panel
 * showed a rock-steady "CPU 105.0 °C" on a phone sitting at 37 °C. Caught on
 * device, and the reason this filter runs before anything else.
 */
fun thermalRoleOf(type: String): ThermalRole? {
    val name = type.trim().lowercase()
    if (THRESHOLD_MARKERS.any { it in name }) return null
    return when {
        name.startsWith("cpu") -> ThermalRole.CPU
        name.startsWith("gpu") -> ThermalRole.GPU
        // `battery` and `main_battery` are the pack; `batt-therm` is the sensor
        // against it. Any of them answers the question being asked.
        name.startsWith("batt") || name == "main_battery" -> ThermalRole.BATTERY
        name.startsWith("quiet") || name.startsWith("skin") -> ThermalRole.SKIN
        else -> null
    }
}

/**
 * Substrings that mark a zone as a configured limit rather than a sensor.
 *
 * A trip point reads as a plausible temperature and never moves, which makes it
 * the worst possible thing to average or take a maximum over: it is wrong in a
 * way that looks like a working reading.
 */
private val THRESHOLD_MARKERS = listOf("trip", "lvl", "alarm", "shutdown", "limit", "warn")

/** The four temperatures worth showing out of the ninety-odd zones this part has. */
enum class ThermalRole { CPU, GPU, BATTERY, SKIN }

/** `9s`, `1m30s`, `2h05m` — as short as it can be and still unambiguous. */
fun formatElapsed(millis: Long): String {
    val total = (millis / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return when {
        hours > 0L -> "${hours}h${minutes.toString().padStart(2, '0')}m"
        minutes > 0L -> "${minutes}m${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

/** Milliwatts as `4.82 W`, sign dropped — the caller says what a negative means. */
fun formatWatts(milliwatts: Int): String {
    val magnitude = if (milliwatts < 0) -milliwatts else milliwatts
    return "${magnitude / 1000}.${(magnitude % 1000 / 10).toString().padStart(2, '0')} W"
}

/** Megabytes as `3.4 GB` past a gigabyte, whole MB below it. */
fun formatMegabytes(megabytes: Int): String =
    if (megabytes >= 1024) "${megabytes / 1024}.${megabytes % 1024 * 10 / 1024} GB" else "$megabytes MB"

/** Tenths of a degree as `41.3`, with the caller supplying the unit. */
fun formatDeciCelsius(deciC: Int): String = "${deciC / 10}.${deciC % 10}"

/** MHz as `3.32 GHz` past a gigahertz, whole MHz below it. */
fun formatMegahertz(mhz: Int): String =
    if (mhz >= 1000) "${mhz / 1000}.${(mhz % 1000 / 10).toString().padStart(2, '0')} GHz" else "$mhz MHz"

private const val UTIME_OFFSET = 11
private const val STIME_OFFSET = 12

/** Above this a thermal reading is millidegrees; below it, whole degrees. */
private const val MILLIDEGREE_THRESHOLD = 1000L

/** 200 °C. Past this the parse is wrong, not the phone. */
private const val MAX_PLAUSIBLE_DECI_C = 2000L

private val WHITESPACE = Regex("\\s+")
