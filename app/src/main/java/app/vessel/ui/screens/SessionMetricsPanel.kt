package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.MetricHistory
import app.vessel.core.MetricSample
import app.vessel.core.MetricSource
import app.vessel.core.MetricStats
import app.vessel.core.formatDeciCelsius
import app.vessel.core.formatElapsed
import app.vessel.core.formatMegabytes
import app.vessel.core.formatMegahertz
import app.vessel.core.formatWatts
import app.vessel.data.SessionMetricsState
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VMetricGraphCard
import app.vessel.ui.components.VMetricGraphStrip
import app.vessel.ui.components.VMetricSeries
import app.vessel.ui.components.VMetricStat
import app.vessel.ui.components.VMetricTone
import app.vessel.ui.components.VMetricValue
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VSeriesForm
import app.vessel.ui.components.VSeriesTone
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme

/**
 * The session's telemetry, in the two shapes the product shows it in.
 *
 * Both take the same [SessionMetricsState], which is the live window while a
 * session runs and its stored trace once it has finished. A null is the answer
 * rather than an error to hide: no session, or a session that left no trace.
 *
 * These live in `screens/` rather than in `components/` deliberately. The graph
 * itself — [VMetricGraphCard], [VMetricGraphStrip] — is design-system code that
 * knows nothing but numbers; this file is the part that knows what a
 * [SessionMetricsState] means, and putting it beside the two screens that
 * consume it keeps the component layer free of a data-layer import.
 */

/**
 * **The composable the session rail embeds.**
 *
 * One call, no arguments beyond the state, and it renders correctly for every
 * case including "nothing is running". Collect
 * [app.vessel.data.SessionMetricsRecorder.watched] to get the state — the act of
 * collecting is what raises the sample rate to 1 Hz, and dropping the collection
 * when the rail closes is what puts it back down.
 */
@Composable
fun SessionMetricsRail(state: SessionMetricsState?, modifier: Modifier = Modifier) {
    val sample = state?.history?.latest
    if (state == null || sample == null) {
        VMetricGraphStrip(
            metrics = emptyList(),
            series = emptyList(),
            // The rail's own wording, not the panel's. Its caption is one line
            // of 10 sp mono in a 138 dp column: the panel's "Sampling — the
            // first reading needs two ticks to become a rate." ellipsises to
            // "Sampling — the fir…", which says less than nothing.
            note = if (state == null) NOT_RUNNING_SHORT else WARMING_UP_SHORT,
            modifier = modifier,
        )
        return
    }

    val history = state.history
    VMetricGraphStrip(
        // Four readings and no more. The rail is a narrow column over a running
        // desktop, and a fifth would push the graph under the fold on a phone
        // held in landscape — which is how this screen is always held.
        metrics = listOfNotNull(
            sample.cpuPercent?.let { VMetricValue("cpu", "$it", "%", loadTone(it)) },
            sample.gpuPercent?.let { VMetricValue("gpu", "$it", "%", loadTone(it)) },
            sample.sessionRssMb?.let { VMetricValue("rss", formatMegabytes(it), null) },
            sample.cpuTempDeciC?.let {
                VMetricValue("temp", formatDeciCelsius(it), "°C", tempTone(it))
            },
        ),
        series = listOfNotNull(
            history.seriesOrNull(100, VSeriesTone.Primary, VSeriesForm.Area, "cpu") { it.cpuPercent },
            history.seriesOrNull(100, VSeriesTone.Secondary, VSeriesForm.Line, "gpu") { it.gpuPercent },
        ),
        note = "${history.size} samples · t+${formatElapsed(sample.elapsedMs)}",
        modifier = modifier,
    )
}

/**
 * The Session log screen's Metrics tab: a card per quantity, scrolling.
 *
 * A card each rather than one graph with fifteen series, because these are many
 * different units and overlaying them would need a shared scale that none of
 * them share. The one place several series *do* share a card is per-core clock,
 * where eight lines on one axis is the whole point — see [CoreClockCard].
 *
 * Every card carries its own statistics. A shape says a run ramped; only the
 * numbers say what it ramped to, and the peak is what gets quoted.
 */
@Composable
fun SessionMetricsPanel(state: SessionMetricsState?, modifier: Modifier = Modifier) {
    if (state == null) {
        VEmptyState(icon = Icons.Filled.Info, message = NO_TRACE, modifier = modifier)
        return
    }

    val history = state.history
    val sample = history.latest
    if (sample == null) {
        VEmptyState(icon = Icons.Filled.Info, message = WARMING_UP, modifier = modifier)
        return
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Vessel.metrics.s22),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        Text(
            if (state.replayed) {
                "Finished run · ${formatElapsed(history.elapsedMs)} · ${history.size} samples"
            } else {
                "Live · ${formatElapsed(history.elapsedMs)} · ${history.size} samples · 1 Hz"
            },
            style = Vessel.type.monoSmall,
            color = if (state.replayed) Vessel.colors.textMuted else Vessel.colors.accent,
        )

        VSectionHeader("Processor")

        VMetricGraphCard(
            // "session" is not decoration. `/proc/stat` is denied to apps on this
            // platform, so this is the CPU of Vessel's own process tree — which
            // under an emulated desktop is everything the container is doing, and
            // is still not the same number as the device's total load. The GPU
            // figure below *is* device-wide, and two adjacent percentages with
            // different scopes is exactly how someone misreads a panel.
            title = "cpu · session process tree",
            value = sample.cpuPercent?.toString(),
            unit = "%",
            stats = history.stats { it.cpuPercent }.percentStats(),
            note = "Vessel's own processes, including wineserver and every Windows " +
                "process. Not the device.",
            series = listOfNotNull(
                history.seriesOrNull(100, VSeriesTone.Primary, VSeriesForm.Area, "cpu") { it.cpuPercent },
            ),
            unavailable = state.unavailable("cpu"),
        )

        // A card that only ever says why it is empty. It is here because "where
        // is total CPU" is the first question this panel invites, and an absent
        // card answers it with silence.
        state.unavailable("cpu total")?.let {
            VMetricGraphCard(
                title = "cpu · device total",
                value = null,
                series = emptyList(),
                unavailable = it,
            )
        }

        CoreClockCard(state)

        VMetricGraphCard(
            title = "clock · mean of online cores",
            value = sample.clockMhz?.let(::formatMegahertz),
            stats = history.stats { it.clockMhz }.stats(::formatMegahertz),
            note = state.clockCeilingMhz?.let { "part maximum ${formatMegahertz(it)}" },
            series = listOfNotNull(
                state.clockCeilingMhz?.let { ceiling ->
                    history.seriesOrNull(ceiling, VSeriesTone.Primary, VSeriesForm.Line, "mean") {
                        it.clockMhz
                    }
                },
                state.clockCeilingMhz?.let { ceiling ->
                    history.seriesOrNull(ceiling, VSeriesTone.Neutral, VSeriesForm.Dashed, "fastest core") {
                        it.clockPeakMhz
                    }
                },
            ),
            unavailable = state.unavailable("clock"),
        )

        VSectionHeader("Graphics")

        VMetricGraphCard(
            // "device" because KGSL counts every client of the GPU, this app
            // included but not only — the compositor drawing our own UI is in it.
            // This is the total GPU figure; the CPU one above is not a total.
            title = "gpu · device total",
            value = sample.gpuPercent?.toString(),
            unit = "%",
            stats = history.stats { it.gpuPercent }.percentStats(),
            note = "every client of the GPU, not only this session",
            series = listOfNotNull(
                history.seriesOrNull(100, VSeriesTone.Secondary, VSeriesForm.Area, "gpu") { it.gpuPercent },
            ),
            unavailable = state.unavailable("gpu"),
        )

        state.unavailable("gpu clock")?.let {
            VMetricGraphCard("gpu clock", null, emptyList(), unavailable = it)
        }

        VSectionHeader("Memory")

        VMetricGraphCard(
            title = "memory",
            value = sample.sessionRssMb?.let(::formatMegabytes),
            stats = buildList {
                addAll(history.stats { it.sessionRssMb }.stats(::formatMegabytes))
                // The delta is the number that answers "how much did this run
                // cost", as opposed to how much was resident anyway.
                history.stats { it.sessionRssMb }?.let {
                    add(VMetricStat("added", formatMegabytes(it.delta.coerceAtLeast(0))))
                }
                sample.ramUsedMb?.let { used ->
                    sample.ramTotalMb?.let { total ->
                        add(VMetricStat("device free", formatMegabytes(total - used)))
                    }
                }
            },
            note = sample.ramTotalMb?.let { "scaled against ${formatMegabytes(it)} of device RAM" },
            series = listOfNotNull(
                // Scaled against device RAM, not against its own range: the
                // question anyone brings to this graph is whether the container
                // is about to be killed, and only the shared axis answers it.
                sample.ramTotalMb?.let { total ->
                    history.seriesOrNull(total, VSeriesTone.Primary, VSeriesForm.Line, "session") {
                        it.sessionRssMb
                    }
                },
                sample.ramTotalMb?.let { total ->
                    history.seriesOrNull(total, VSeriesTone.Neutral, VSeriesForm.Line, "device") {
                        it.ramUsedMb
                    }
                },
            ),
            unavailable = state.unavailable("ram"),
        )

        state.unavailable("ram clock")?.let {
            VMetricGraphCard("ram clock", null, emptyList(), unavailable = it)
        }

        VSectionHeader("Thermal")

        TemperatureCard(state)

        VSectionHeader("Power")

        val powerCeiling = history.peak { magnitude(it.powerMilliwatts) }
        VMetricGraphCard(
            title = if ((sample.powerMilliwatts ?: 0) < 0) "power · charging" else "power · total draw",
            value = sample.powerMilliwatts?.let(::formatWatts),
            stats = history.stats { magnitude(it.powerMilliwatts) }.stats(::formatWatts),
            note = "battery current × voltage — the whole phone, not this session",
            series = listOfNotNull(
                powerCeiling?.takeIf { it > 0 }?.let { ceiling ->
                    history.seriesOrNull(ceiling, VSeriesTone.Neutral, VSeriesForm.Area, "draw") {
                        magnitude(it.powerMilliwatts)
                    }
                },
            ),
            unavailable = state.unavailable("power"),
        )

        state.unavailable("cpu/gpu power")?.let {
            VMetricGraphCard("power · cpu and gpu rails", null, emptyList(), unavailable = it)
        }

        BatteryCard(state)

        SourceList(state)
    }
}

/**
 * All eight cores' clocks on one axis.
 *
 * One card rather than eight, because the question is never "what is cpu3
 * doing" — it is "is the scheduler using the big cores", and that is a shape you
 * read across lines rather than down cards.
 *
 * Each core is drawn against **its own** rated ceiling, which is what makes the
 * two clusters comparable: cpu0–5 top out at 3.32 GHz and cpu6–7 at 3.80 GHz, so
 * a shared axis would show a saturated little core sitting below a loafing big
 * one. Normalised this way, a line near the top means that core is flat out
 * whichever cluster it belongs to.
 */
@Composable
private fun CoreClockCard(state: SessionMetricsState) {
    val history = state.history
    val ceilings = state.coreCeilingsMhz
    if (ceilings.isEmpty()) return

    val fastest = ceilings.filterNotNull().maxOrNull()
    val series = ceilings.mapIndexedNotNull { core, ceiling ->
        if (ceiling == null || ceiling <= 0) return@mapIndexedNotNull null
        val values = history.coreSeries(core)
        if (values.all { it == null }) return@mapIndexedNotNull null
        val big = fastest != null && ceiling >= fastest
        VMetricSeries(
            label = "cpu$core",
            values = values,
            ceiling = ceiling,
            // The clusters are told apart by **form**, not by colour. Nocturne's
            // two chart tones are `accent` and `accent2`, which are a violet and
            // a slightly lighter violet — fine for two series, useless for eight,
            // where the eye cannot hold the difference. Dashing the two big cores
            // separates the clusters at a glance without inventing a colour the
            // design system does not have.
            tone = if (big) VSeriesTone.Secondary else VSeriesTone.Primary,
            form = if (big) VSeriesForm.Dashed else VSeriesForm.Line,
        )
    }

    val latest = history.latest?.coreClocksMhz.orEmpty()
    VMetricGraphCard(
        title = "clock · per core",
        value = null,
        stats = ceilings.indices.map { core ->
            VMetricStat(
                "cpu$core",
                latest.getOrNull(core)?.let(::formatMegahertz) ?: PARKED,
            )
        },
        note = "each core against its own rated maximum, so the two clusters compare. " +
            ceilings.withIndex()
                .filter { it.value != null }
                .groupBy { it.value }
                .entries
                .joinToString("; ") { (ceiling, cores) ->
                    "cpu${cores.first().index}–${cores.last().index} ${formatMegahertz(ceiling!!)}"
                },
        series = series,
        unavailable = state.unavailable("clock") ?: if (series.isEmpty()) NO_CORE_CLOCKS else null,
    )
}

/** CPU, GPU, battery and skin on one axis — they share a unit, so they share a card. */
@Composable
private fun TemperatureCard(state: SessionMetricsState) {
    val history = state.history
    val sample = history.latest
    VMetricGraphCard(
        title = "temperature",
        value = sample?.cpuTempDeciC?.let(::formatDeciCelsius),
        unit = "°C",
        stats = listOfNotNull(
            sample?.cpuTempDeciC?.let { VMetricStat("cpu", "${formatDeciCelsius(it)}°") },
            sample?.gpuTempDeciC?.let { VMetricStat("gpu", "${formatDeciCelsius(it)}°") },
            sample?.batteryTempDeciC?.let { VMetricStat("battery", "${formatDeciCelsius(it)}°") },
            sample?.skinTempDeciC?.let { VMetricStat("skin", "${formatDeciCelsius(it)}°") },
            history.peak { it.cpuTempDeciC }?.let {
                VMetricStat("cpu peak", "${formatDeciCelsius(it)}°")
            },
            history.peak { it.gpuTempDeciC }?.let {
                VMetricStat("gpu peak", "${formatDeciCelsius(it)}°")
            },
        ),
        note = "hottest zone of each kind, matched by the zone's own type string",
        series = listOfNotNull(
            history.seriesOrNull(TEMP_CEILING_DECI_C, VSeriesTone.Primary, VSeriesForm.Line, "cpu") {
                it.cpuTempDeciC
            },
            history.seriesOrNull(TEMP_CEILING_DECI_C, VSeriesTone.Secondary, VSeriesForm.Line, "gpu") {
                it.gpuTempDeciC
            },
            history.seriesOrNull(TEMP_CEILING_DECI_C, VSeriesTone.Neutral, VSeriesForm.Dashed, "battery") {
                it.batteryTempDeciC
            },
        ),
        unavailable = state.unavailable("temperature"),
    )
}

/** Level, voltage and current — the three numbers a long session moves. */
@Composable
private fun BatteryCard(state: SessionMetricsState) {
    val history = state.history
    val sample = history.latest
    val levelStats = history.stats { it.batteryPercent }
    VMetricGraphCard(
        title = "battery",
        value = sample?.batteryPercent?.toString(),
        unit = "%",
        stats = listOfNotNull(
            sample?.batteryMillivolts?.let { VMetricStat("voltage", "${it / 1000}.${it % 1000 / 10} V") },
            sample?.batteryMilliamps?.let { VMetricStat("current", "$it mA") },
            sample?.batteryTempDeciC?.let { VMetricStat("temp", "${formatDeciCelsius(it)}°") },
            // Signed: a run that charged the phone did not cost 4% of battery.
            levelStats?.let { VMetricStat("used", "${-it.delta}%") },
        ),
        note = "level from the battery broadcast; current from the health HAL",
        series = listOfNotNull(
            history.seriesOrNull(100, VSeriesTone.Primary, VSeriesForm.Line, "level") {
                it.batteryPercent
            },
        ),
        unavailable = state.unavailable("battery"),
    )
}

/** Every source, said out loud, so a gap in a graph is never a mystery. */
@Composable
private fun SourceList(state: SessionMetricsState) {
    if (state.sources.isEmpty()) return
    Column(Modifier.padding(top = Vessel.metrics.s11)) {
        VSectionHeader("Sources")
        state.sources.forEach { source ->
            Text(
                if (source.available) {
                    "${source.label}  ${source.origin}"
                } else {
                    "${source.label}  unavailable — ${source.reason}"
                },
                style = Vessel.type.monoSmall,
                color = if (source.available) Vessel.colors.textMuted else Vessel.colors.neutral600,
                modifier = Modifier.padding(bottom = Vessel.metrics.s3),
            )
        }
    }
}

// — mapping helpers ---------------------------------------------------------

/**
 * The sentence a card shows instead of a graph, or null when the source works.
 *
 * Read from the probe recorded in the trace rather than from the absence of
 * data: a GPU that is idle and a GPU whose counter cannot be opened both produce
 * no line, and only the probe knows which happened.
 */
private fun SessionMetricsState.unavailable(label: String): String? =
    sources.firstOrNull { it.label == label }?.takeIf { !it.available }?.reason

/** Absolute milliwatts. Charging is a negative draw, and the graph plots size. */
private fun magnitude(milliwatts: Int?): Int? = milliwatts?.let { if (it < 0) -it else it }

/** The four numbers a percentage card prints. */
private fun MetricStats?.percentStats(): List<VMetricStat> = stats { "$it%" }

/**
 * Peak, mean, min and coverage, formatted by the caller.
 *
 * `min` is dropped when the value never moved — "min 62% · peak 62%" is a range
 * that is not one, and the row is short enough that a redundant column costs a
 * real column.
 */
private fun MetricStats?.stats(format: (Int) -> String): List<VMetricStat> {
    val stats = this ?: return emptyList()
    return buildList {
        add(VMetricStat("peak", format(stats.peak)))
        add(VMetricStat("mean", format(stats.mean)))
        if (!stats.flat) add(VMetricStat("min", format(stats.min)))
        // Only worth a column when the source actually dropped out.
        if (stats.gaps > 0) add(VMetricStat("gaps", "${stats.gaps}"))
    }
}

/**
 * A series, or null when the window holds no reading of it at all.
 *
 * The distinction matters at the card level: a series of nothing but nulls draws
 * an empty box that looks like a working graph of a quantity pinned at zero.
 */
private fun MetricHistory.seriesOrNull(
    ceiling: Int,
    tone: VSeriesTone,
    form: VSeriesForm,
    label: String,
    field: (MetricSample) -> Int?,
): VMetricSeries? {
    if (ceiling <= 0) return null
    val values = series(field)
    if (values.all { it == null }) return null
    return VMetricSeries(label = label, values = values, ceiling = ceiling, tone = tone, form = form)
}

/**
 * Where a load sits against its thresholds.
 *
 * `docs/DESIGN.md`: telemetry "switch[es] to `warn`/`danger` past thresholds".
 * The two numbers are chosen so that a container running well never shows a
 * colour — a panel that is amber all the time has stopped being a signal.
 */
private fun loadTone(percent: Int): VMetricTone = when {
    percent >= DANGER_PERCENT -> VMetricTone.Danger
    percent >= WARN_PERCENT -> VMetricTone.Warn
    else -> VMetricTone.Normal
}

/** 45 °C is where this part starts throttling; 50 is where it is obvious. */
private fun tempTone(deciC: Int): VMetricTone = when {
    deciC >= 500 -> VMetricTone.Danger
    deciC >= 450 -> VMetricTone.Warn
    else -> VMetricTone.Normal
}

private const val WARN_PERCENT = 80
private const val DANGER_PERCENT = 93

/** 80 °C full scale: high enough that a real throttle is not off the top. */
private const val TEMP_CEILING_DECI_C = 800

private const val PARKED = "parked"

private const val NO_CORE_CLOCKS = "No core reported a clock during this run."

private const val NO_TRACE =
    "No telemetry was recorded for this session. Runs from before metrics existed have " +
        "none, and neither does a session that failed before it started running."

private const val NOT_RUNNING_SHORT = "not running"

private const val WARMING_UP = "Sampling — the first reading needs two ticks to become a rate."

/** The same fact in the rail's one line. */
private const val WARMING_UP_SHORT = "sampling…"

// — previews ---------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 1600)
@Composable
private fun SessionMetricsPanelPreview() {
    VesselTheme {
        SessionMetricsPanel(SampleState, Modifier.padding(Vessel.metrics.s11))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun SessionMetricsPanelNoTracePreview() {
    VesselTheme { SessionMetricsPanel(null) }
}

/** At the rail's real inner width, which is the only width this has to work at. */
@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 146)
@Composable
private fun SessionMetricsRailPreview() {
    VesselTheme {
        SessionMetricsRail(SampleState, Modifier.padding(Vessel.metrics.s8))
    }
}

private val SampleState: SessionMetricsState by lazy {
    val ceilings = listOf(3321, 3321, 3321, 3321, 3321, 3321, 3801, 3801)
    var history = MetricHistory()
    val load = listOf(11, 24, 47, 66, 78, 71, 63, 69, 84, 91, 76, 64)
    load.forEachIndexed { index, cpu ->
        history += MetricSample(
            elapsedMs = index * 1_000L,
            cpuPercent = cpu,
            coreClocksMhz = ceilings.mapIndexed { core, ceiling ->
                // A parked core, so the "parked" case is visible in the preview.
                if (core == 5 && index in 3..6) null else 600 + ceiling * cpu / 140
            },
            clockMhz = 900 + cpu * 20,
            clockPeakMhz = 1400 + cpu * 24,
            gpuPercent = if (index in 4..6) null else (cpu * 3 / 4).coerceAtMost(100),
            ramUsedMb = 7_400 + cpu * 6,
            ramTotalMb = 15_240,
            sessionRssMb = 2_100 + cpu * 9,
            cpuTempDeciC = 360 + cpu,
            gpuTempDeciC = 340 + cpu,
            batteryTempDeciC = 330 + cpu / 2,
            skinTempDeciC = 350 + cpu / 2,
            powerMilliwatts = 2_600 + cpu * 42,
            batteryPercent = 80 - index / 4,
            batteryMillivolts = 4_209,
            batteryMilliamps = -(600 + cpu * 8),
        )
    }
    SessionMetricsState(
        containerId = "preview",
        startedAt = 0L,
        running = true,
        history = history,
        coreCeilingsMhz = ceilings,
        sources = listOf(
            MetricSource("cpu", "/proc/<pid>/stat over the session process tree", true),
            MetricSource(
                "cpu total",
                "/proc/stat",
                false,
                "/proc/stat is denied to apps on Android 16, so no device-wide CPU " +
                    "figure exists.",
            ),
            MetricSource("gpu", "/sys/class/kgsl/kgsl-3d0/gpubusy, device-wide", true),
            MetricSource(
                "gpu clock",
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                false,
                "every KGSL node except gpubusy is denied to apps here.",
            ),
            MetricSource("clock", "scaling_cur_freq, per core across 8 cores", true),
            MetricSource("ram", "ActivityManager.MemoryInfo, plus statm", true),
            MetricSource(
                "ram clock",
                "/sys/class/devfreq/*/cur_freq",
                false,
                "/sys/class/devfreq is denied to the shell as well as to apps.",
            ),
            MetricSource("temperature", "/sys/class/thermal/thermal_zone*, matched by type", true),
            MetricSource("battery", "BatteryManager and ACTION_BATTERY_CHANGED", true),
            MetricSource("power", "CURRENT_NOW × voltage, total draw", true),
            MetricSource(
                "cpu/gpu power",
                "/sys/bus/iio/devices/*/energy_value",
                false,
                "this device ships no ODPM energy rails, so per-rail draw cannot be " +
                    "measured. It is deliberately not estimated from the total.",
            ),
        ),
    )
}
