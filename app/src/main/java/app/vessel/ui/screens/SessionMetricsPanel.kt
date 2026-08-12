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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.FrameRate
import app.vessel.core.MetricHistory
import app.vessel.core.MetricSample
import app.vessel.core.MetricSource
import app.vessel.core.MetricStats
import app.vessel.core.formatDeciCelsius
import app.vessel.core.formatElapsed
import app.vessel.core.formatMegabytes
import app.vessel.core.formatMegahertz
import app.vessel.core.formatWatts
import app.vessel.core.oneDecimal
import app.vessel.data.SessionMetricsState
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VMetricGraphCard
import app.vessel.ui.components.VMetricSeries
import app.vessel.ui.components.VMetricSpark
import app.vessel.ui.components.VMetricStat
import app.vessel.ui.components.VMetricTone
import app.vessel.ui.components.VSeriesForm
import app.vessel.ui.components.VSeriesTone
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import kotlin.math.roundToInt

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
 * **The composable the session rail embeds: four graphs, one per quantity.**
 *
 * One shared graph was wrong on its own terms. CPU and GPU are percentages, the
 * clock is MHz and memory is MB, so a single vertical axis was correct for one
 * series and meaningless for the other three — and the two that could not fit on
 * it were simply left off, which is why the rail showed a number for memory and
 * no history of it at all. Each quantity now carries its own ceiling: 100 for the
 * loads, the part's fastest core for the clock, the device's RAM for memory.
 *
 * Collect [app.vessel.data.SessionMetricsRecorder.watched] to get the state — the
 * act of collecting is what raises the sample rate to 1 Hz, and dropping the
 * collection when the rail closes is what puts it back down.
 *
 * [paused] is not cosmetic. A `SIGSTOP`ped guest keeps being sampled and every
 * reading truthfully falls to idle, which is indistinguishable from a container
 * that has finished loading and is waiting for input — so the caption says which
 * it is rather than leaving four flat lines to be misread.
 */
@Composable
fun SessionMetricsRail(
    state: SessionMetricsState?,
    paused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val sample = state?.history?.latest
    if (state == null || sample == null) {
        Text(
            // The rail's own wording, not the panel's. Its caption is one line
            // of 10 sp mono in a 134 dp column: the panel's "Sampling — the
            // first reading needs two ticks to become a rate." ellipsises to
            // "Sampling — the fir…", which says less than nothing.
            if (state == null) NOT_RUNNING_SHORT else WARMING_UP_SHORT,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            maxLines = 1,
            modifier = modifier,
        )
        return
    }

    val history = state.history
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        // Loads take an area fill and levels take a bare line, which is
        // [VSeriesForm]'s rule and not a decoration: the area under a proportion
        // of a fixed whole is the work done, and there is nothing underneath a
        // clock or a memory figure for a fill to mean.
        VMetricSpark(
            label = "cpu",
            value = sample.cpuPercent?.toString(),
            unit = "%",
            tone = sample.cpuPercent?.let(::loadTone) ?: VMetricTone.Normal,
            series = history.seriesOrNull(100, VSeriesTone.Primary, VSeriesForm.Area, "cpu") {
                it.cpuPercent
            },
            unavailable = state.railGap("cpu"),
        )
        VMetricSpark(
            label = "gpu",
            value = sample.gpuPercent?.toString(),
            unit = "%",
            tone = sample.gpuPercent?.let(::loadTone) ?: VMetricTone.Normal,
            series = history.seriesOrNull(100, VSeriesTone.Secondary, VSeriesForm.Area, "gpu") {
                it.gpuPercent
            },
            unavailable = state.railGap("gpu"),
        )
        VMetricSpark(
            label = "clock",
            value = sample.clockMhz?.let(::formatMegahertz),
            series = state.clockCeilingMhz?.let { ceiling ->
                history.seriesOrNull(ceiling, VSeriesTone.Primary, VSeriesForm.Line, "mean") {
                    it.clockMhz
                }
            },
            unavailable = state.railGap("clock"),
        )
        VMetricSpark(
            label = "memory",
            value = sample.sessionRssMb?.let(::formatMegabytes),
            // Against device RAM rather than its own range, for the same reason
            // the Metrics tab does it: the question anyone brings to this line is
            // whether the container is about to be killed.
            series = sample.ramTotalMb?.let { total ->
                history.seriesOrNull(total, VSeriesTone.Secondary, VSeriesForm.Line, "rss") {
                    it.sessionRssMb
                }
            },
            unavailable = state.railGap("ram"),
        )
        Text(
            if (paused) {
                PAUSED_NOTE
            } else {
                "${history.size} samples · t+${formatElapsed(sample.elapsedMs)}"
            },
            style = Vessel.type.monoSmall,
            color = if (paused) Vessel.colors.warn else Vessel.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
 *
 * **A card, its numbers, and nothing else.** There were captions under most of
 * these — which process tree the CPU figure covers, that the GPU one is
 * device-wide, where the battery current is read from — and section headers
 * above them, and a list of every source at the bottom. All of it is gone. It
 * was a page of prose wrapped around seven graphs, and the person who opens this
 * tab is looking at the graphs. Where a scope genuinely has to be said, it is
 * said in the title.
 *
 * **A source that cannot be read is left off, not drawn empty.** There were four
 * cards here whose whole content was a sentence explaining why they had no
 * content — device-total CPU, GPU clock, RAM clock, per-rail power, none of
 * which this device or this sandbox exposes. Four permanent apologies is not a
 * panel. The probe reason still reaches [VMetricGraphCard.unavailable] for a
 * card that *usually* works and did not this run, which is the case worth
 * reporting.
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

        FrameRateCard(state)

        VMetricGraphCard(
            title = "cpu",
            value = sample.cpuPercent?.toString(),
            unit = "%",
            stats = history.stats { it.cpuPercent }.percentStats(),
            series = listOfNotNull(
                history.seriesOrNull(100, VSeriesTone.Primary, VSeriesForm.Area, "cpu") { it.cpuPercent },
            ),
            axisStyle = { "$it%" },
            spanSeconds = history.spanSeconds,
            unavailable = state.unavailable("cpu"),
        )

        CoreClockCard(state)

        VMetricGraphCard(
            title = "gpu",
            value = sample.gpuPercent?.toString(),
            unit = "%",
            stats = history.stats { it.gpuPercent }.percentStats(),
            series = listOfNotNull(
                history.seriesOrNull(100, VSeriesTone.Secondary, VSeriesForm.Area, "gpu") { it.gpuPercent },
            ),
            axisStyle = { "$it%" },
            spanSeconds = history.spanSeconds,
            unavailable = state.unavailable("gpu"),
        )

        D3dCards(state)

        VMetricGraphCard(
            title = "memory",
            axisStyle = ::formatMegabytes,
            spanSeconds = history.spanSeconds,
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

        TemperatureCard(state)

        val powerCeiling = history.peak { magnitude(it.powerMilliwatts) }
        VMetricGraphCard(
            title = if ((sample.powerMilliwatts ?: 0) < 0) "power · charging" else "power · total draw",
            axisStyle = ::formatWatts,
            spanSeconds = history.spanSeconds,
            value = sample.powerMilliwatts?.let(::formatWatts),
            stats = history.stats { magnitude(it.powerMilliwatts) }.stats(::formatWatts),
            series = listOfNotNull(
                powerCeiling?.takeIf { it > 0 }?.let { ceiling ->
                    history.seriesOrNull(ceiling, VSeriesTone.Neutral, VSeriesForm.Area, "draw") {
                        magnitude(it.powerMilliwatts)
                    }
                },
            ),
            unavailable = state.unavailable("power"),
        )

        BatteryCard(state)
    }
}

/**
 * Frames the compositor delivered, and the four numbers that describe them.
 *
 * **First card on the panel, above cpu.** Every other card answers "what is the
 * phone doing"; this one answers "was it any good", which is the question the
 * others exist to explain. Reading the cause above the effect is the wrong way
 * round.
 *
 * **`1% low` rather than `min`, and that is the whole reason this card does not
 * reuse the shared stats row.** One dropped frame gives a `min` of 0 in every
 * run ever recorded, so `min` sorts nothing and means nothing here. The mean of
 * the slowest 1% is what stutter feels like — see [app.vessel.core.FrameStats].
 * `min` is still shown, because a genuine 0 is worth seeing; it is just not the
 * headline.
 *
 * Scaled to the container's own fps limit, like the taskbar readout, so a
 * container asked for 30 and delivering 30 draws a full graph instead of a half
 * one.
 */
@Composable
private fun FrameRateCard(state: SessionMetricsState) {
    val history = state.history
    val stats = history.frameStats()
    // The limit is not in the sample — it is a property of the container, and a
    // trace replayed on a different container should not be rescaled to the new
    // one's limit. Absent, 60 is the honest default and the same one the taskbar
    // uses.
    // The D3D layer's own present rate shares this axis, so the ceiling has to
    // clear both: a title rendering faster than the surface is composited is
    // exactly the case worth seeing, and clipping it would hide it.
    val presented = history.peak { it.d3dFps.rounded() }
    val ceiling = maxOf(FrameRate.DEFAULT_TARGET, stats?.peak?.roundToInt() ?: 0, presented ?: 0)

    VMetricGraphCard(
        title = "frames",
        axisStyle = { "$it" },
        spanSeconds = history.spanSeconds,
        value = stats?.current?.roundToInt()?.toString(),
        unit = "fps",
        stats = buildList {
            if (stats == null) return@buildList
            add(VMetricStat("1% low", formatFps(stats.onePercentLow)))
            add(VMetricStat("mean", formatFps(stats.mean)))
            add(VMetricStat("peak", formatFps(stats.peak)))
            // A minimum equal to the peak means the rate never moved, and two
            // identical columns is a range that is not one.
            if (stats.min < stats.peak) add(VMetricStat("min", formatFps(stats.min)))
            // **Two different frame rates, and the gap between them is a
            // finding.** `fps` is what the compositor delivered; this is what
            // the D3D layer presented. Vessel composites on damage, so a title
            // drawing faster than the surface reads as the surface — and the
            // only way to know that is happening is to be told both numbers.
            history.mean { it.d3dFps.rounded() }?.let {
                add(VMetricStat("d3d mean", "$it"))
            }
        },
        series = listOfNotNull(
            history.seriesOrNull(ceiling, VSeriesTone.Primary, VSeriesForm.Area, "fps") {
                it.fps?.roundToInt()
            },
            history.seriesOrNull(ceiling, VSeriesTone.Secondary, VSeriesForm.Line, "d3d") {
                it.d3dFps.rounded()
            },
        ),
        // **Not `unavailable`, because it is not.** Every other card's empty
        // state is a `/sys` node an app may not read. Frames are counted by this
        // app, so nothing having been drawn is a measurement — of an idle
        // session — and calling it unavailable would report a working instrument
        // as broken.
        unavailable = when {
            stats == null -> "no frames have been composited yet"
            stats.neverDrew -> "the guest has not drawn anything this session"
            else -> null
        },
    )
}

/**
 * What the D3D layer was asked to do, which is the other half of every other
 * card on this panel.
 *
 * **Everything else here says how hard the phone worked; these say at what.**
 * The two together are what tell a scene that is genuinely heavy from one that
 * is slow for a reason graphics has nothing to do with, and that distinction was
 * worth building this for: Metro's intro reads one draw call and one render pass
 * a frame with the GPU near idle — a fullscreen video blit bottlenecked on a
 * single-threaded CPU decode under FEX — while gameplay in the same title reads
 * thousands of draws with the GPU pinned. Same frame rate, opposite diagnosis,
 * and no combination of load, clock and temperature can separate them.
 *
 * **Three cards when there is something to draw, one sentence when there is
 * not.** The panel's rule is that a source which cannot be read is left off
 * rather than drawn empty, because four permanent apologies is not a panel. But
 * a program that uses no Direct3D is not a permanent fact about the device, it
 * is a fact about this run — and a user who launched a game and finds no
 * graphics counters deserves to be told why rather than shown a gap. So the
 * absence costs one card, not three.
 */
@Composable
private fun D3dCards(state: SessionMetricsState) {
    val history = state.history
    val sample = history.latest ?: return

    // Draw calls decide it: they are the counter every D3D program produces on
    // every frame, so a run that has one has all of them and a run that has none
    // never loaded the layer at all.
    val drawPeak = history.peak { it.d3dDrawCallsPerFrame.rounded() }
    if (drawPeak == null) {
        VMetricGraphCard(
            title = "d3d",
            axisStyle = { "$it" },
            spanSeconds = history.spanSeconds,
            value = null,
            series = emptyList(),
            unavailable = state.unavailable("d3d") ?: NO_D3D,
        )
        return
    }

    // Render passes are drawn against the draw-call ceiling rather than their
    // own, and that is the point of putting them on this card: a pass is a
    // container for draws, so the gap between the two lines is the batching, and
    // normalising them separately would hide exactly that.
    VMetricGraphCard(
        title = "d3d · draw calls",
        axisStyle = { "$it" },
        spanSeconds = history.spanSeconds,
        value = sample.d3dDrawCallsPerFrame?.let(::oneDecimal),
        unit = "/frame",
        stats = buildList {
            addAll(history.stats { it.d3dDrawCallsPerFrame.rounded() }.stats { "$it" })
            history.mean { it.d3dRenderPassesPerFrame.rounded() }?.let {
                add(VMetricStat("passes", "$it"))
            }
            // Only when something is actually dispatching: a title that runs no
            // compute should not carry a permanent zero.
            history.peak { it.d3dDispatchesPerFrame.rounded() }?.takeIf { it > 0 }?.let {
                add(VMetricStat("dispatch peak", "$it"))
            }
        },
        series = listOfNotNull(
            history.seriesOrNull(drawPeak, VSeriesTone.Primary, VSeriesForm.Area, "draws") {
                it.d3dDrawCallsPerFrame.rounded()
            },
            history.seriesOrNull(drawPeak, VSeriesTone.Secondary, VSeriesForm.Line, "passes") {
                it.d3dRenderPassesPerFrame.rounded()
            },
        ),
    )

    // Submissions and barriers share a ceiling because they are the same kind of
    // thing at the same order of magnitude — both are per-frame counts of work
    // the layer had to do around the drawing rather than in it, and both are
    // small. A submission is a queue round trip and a barrier is a pipeline
    // stall, so a frame doing many of either is paying for structure.
    val commandPeak = maxOf(
        history.peak { it.d3dSubmissionsPerFrame.rounded() } ?: 0,
        history.peak { it.d3dBarriersPerFrame.rounded() } ?: 0,
    )
    VMetricGraphCard(
        title = "d3d · submissions",
        axisStyle = { "$it" },
        spanSeconds = history.spanSeconds,
        value = sample.d3dSubmissionsPerFrame?.let(::oneDecimal),
        unit = "/frame",
        stats = buildList {
            addAll(history.stats { it.d3dSubmissionsPerFrame.rounded() }.stats { "$it" })
            history.mean { it.d3dBarriersPerFrame.rounded() }?.let {
                add(VMetricStat("barriers", "$it"))
            }
            history.peak { it.d3dGpuSyncsPerFrame.rounded() }?.takeIf { it > 0 }?.let {
                add(VMetricStat("sync peak", "$it"))
            }
        },
        series = listOfNotNull(
            history.seriesOrNull(commandPeak, VSeriesTone.Primary, VSeriesForm.Line, "submits") {
                it.d3dSubmissionsPerFrame.rounded()
            },
            history.seriesOrNull(commandPeak, VSeriesTone.Neutral, VSeriesForm.Dashed, "barriers") {
                it.d3dBarriersPerFrame.rounded()
            },
        ),
    )

    // **Pipelines are numbers and not a line, and vidmem is the line they sit
    // under.** A pipeline count only ever goes up and then stops, so a graph of
    // one is a staircase that says less than the two numbers at its ends; what
    // is worth watching over time is the memory, which moves in both directions
    // and is the thing that ends a session when it runs out.
    val vramPeak = history.peak { it.d3dMemAllocatedMb } ?: 0
    VMetricGraphCard(
        title = "d3d · video memory",
        axisStyle = ::formatMegabytes,
        spanSeconds = history.spanSeconds,
        value = sample.d3dMemUsedMb?.let(::formatMegabytes),
        stats = buildList {
            addAll(history.stats { it.d3dMemUsedMb }.stats(::formatMegabytes))
            history.peak { it.d3dMemAllocatedMb }?.let {
                add(VMetricStat("allocated", formatMegabytes(it)))
            }
            // The peak rather than the current value, because these only climb
            // and the peak is therefore the answer either way — except for a
            // replayed trace whose last sample predates the last compile.
            history.peak { it.d3dPipelines }?.let { add(VMetricStat("pipelines", "$it")) }
            history.peak { it.d3dPipelineLibraries }?.takeIf { it > 0 }?.let {
                add(VMetricStat("libraries", "$it"))
            }
            history.peak { it.d3dPipelinesCompute }?.takeIf { it > 0 }?.let {
                add(VMetricStat("compute", "$it"))
            }
            // A backlog that is still there at the end of a run is a title that
            // was still compiling, which is a title that was still stuttering.
            sample.d3dPipeTasksPending?.takeIf { it > 0 }?.let {
                add(VMetricStat("compiling", "$it"))
            }
        },
        series = listOfNotNull(
            history.seriesOrNull(vramPeak, VSeriesTone.Primary, VSeriesForm.Area, "used") {
                it.d3dMemUsedMb
            },
            history.seriesOrNull(vramPeak, VSeriesTone.Neutral, VSeriesForm.Line, "allocated") {
                it.d3dMemAllocatedMb
            },
        ),
    )
}

/**
 * A per-frame counter as the graph wants it.
 *
 * Rounded here and nowhere earlier. The sample keeps the fraction because a
 * steady 2.4 submissions a frame stored as an integer alternates 2 and 3 and
 * puts a sawtooth in a flat line; the graph has a pixel per sample and cannot
 * show the difference, so this is the last possible moment and the right one.
 */
private fun Float?.rounded(): Int? = this?.roundToInt()

/** `58` and `59.4` — a fraction only where it says something. */
private fun formatFps(value: Float): String {
    val rounded = value.roundToInt()
    // Whole numbers below 10 are where the fraction matters: 4 fps and 4.4 fps
    // are meaningfully different and both round to "4", whereas nobody needs to
    // know that the mean was 58.3 rather than 58.
    return if (value < 10f && rounded.toFloat() != value) String.format("%.1f", value) else "$rounded"
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

    // **The mean, drawn over the eight and read as the headline.**
    //
    // The card had eight lines and no aggregate, so the one question it is most
    // often asked — "what is this session actually running at" — could only be
    // answered by eyeballing eight traces at once. That number decided every
    // performance argument this project had: 1713 MHz against a 3321 MHz ceiling
    // is the difference between a slow emulator and a platform that never
    // clocked up, and it was visible only in the rail's compact spark and in the
    // raw trace.
    //
    // Against the *fastest* core's ceiling, not each core's own. A mean across
    // clusters has no single rated maximum, and normalising it per-core the way
    // the eight lines are normalised would put it on an axis it does not share
    // with any of them. Drawn `Area` so it reads as the backdrop the per-core
    // lines sit on rather than as a ninth core.
    val meanSeries = fastest
        ?.takeIf { it > 0 }
        ?.let { ceiling ->
            history.seriesOrNull(ceiling, VSeriesTone.Secondary, VSeriesForm.Area, "mean") {
                it.clockMhz
            }
        }

    VMetricGraphCard(
        title = "clock · per core",
        value = history.latest?.clockMhz?.let(::formatMegahertz),
        stats = buildList {
            // Mean and peak lead, because they are the summary; the eight
            // individual cores follow for the "is it using the big cores"
            // question the card was originally built for.
            addAll(history.stats { it.clockMhz }.stats(::formatMegahertz))
            history.latest?.clockPeakMhz?.let { add(VMetricStat("peak core", formatMegahertz(it))) }
            addAll(
                ceilings.indices.map { core ->
                    VMetricStat(
                        "cpu$core",
                        latest.getOrNull(core)?.let(::formatMegahertz) ?: PARKED,
                    )
                },
            )
        },
        series = listOfNotNull(meanSeries) + series,
        axisStyle = ::formatMegahertz,
        spanSeconds = history.spanSeconds,
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
        axisStyle = ::formatDeciCelsius,
        spanSeconds = history.spanSeconds,
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
        series = listOfNotNull(
            history.seriesOrNull(100, VSeriesTone.Primary, VSeriesForm.Line, "level") {
                it.batteryPercent
            },
        ),
        unavailable = state.unavailable("battery"),
    )
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

/**
 * The same question, answered in the four words a 134 dp rail can hold.
 *
 * Null when the source works, in which case [VMetricSpark] says "no reading" —
 * and the difference is the point. A GPU that is idle and a GPU whose counter
 * cannot be opened both draw nothing; only the probe knows which, and the rail
 * has to distinguish them in a phrase. The sentence itself lives on the Metrics
 * tab, where there is room to quote the path.
 */
private fun SessionMetricsState.railGap(label: String): String? =
    if (unavailable(label) == null) null else UNAVAILABLE_SHORT

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

private const val WARN_PERCENT = 80
private const val DANGER_PERCENT = 93

/** 80 °C full scale: high enough that a real throttle is not off the top. */
private const val TEMP_CEILING_DECI_C = 800

private const val PARKED = "parked"

private const val NO_CORE_CLOCKS = "No core reported a clock during this run."

/**
 * Said when a run produced no D3D counters and the probe had nothing to add.
 *
 * Not a failure and phrased so it does not read as one. The counters come from
 * DXVK, DXVK is only loaded by a program that uses Direct3D, and a container
 * running an installer or a shell has no graphics story to tell.
 */
private const val NO_D3D =
    "No Direct3D counters this run. DXVK writes them only while a program is drawing " +
        "through D3D 8/9/10/11, so a session that ran nothing graphical has none."

private const val NO_TRACE =
    "No telemetry was recorded for this session. Runs from before metrics existed have " +
        "none, and neither does a session that failed before it started running."

private const val NOT_RUNNING_SHORT = "not running"

/** The rail's form of a denied source. The reason itself is on the Metrics tab. */
private const val UNAVAILABLE_SHORT = "source unavailable"

/**
 * Said instead of the sample count while the guest is stopped.
 *
 * The readings are not wrong — a suspended process really does use no CPU — but
 * four flat lines with no caption is exactly what a container sitting at an idle
 * desktop looks like, and the difference between those two is the whole reason
 * anyone would look at this while paused.
 */
private const val PAUSED_NOTE = "paused · the guest is stopped, so these fall to idle"

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
@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 150)
@Composable
private fun SessionMetricsRailPreview() {
    VesselTheme {
        SessionMetricsRail(SampleState, modifier = Modifier.padding(Vessel.metrics.s8))
    }
}

/** The case a caption exists for: four honest flat lines that are not idleness. */
@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 150)
@Composable
private fun SessionMetricsRailPausedPreview() {
    VesselTheme {
        SessionMetricsRail(SampleState, paused = true, modifier = Modifier.padding(Vessel.metrics.s8))
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
            // The shape this feature exists to show: an intro at one draw call a
            // frame with the GPU idle, then gameplay at thousands with it pinned.
            d3dFps = if (index < 4) 10f else 28f + cpu / 10f,
            d3dDrawCallsPerFrame = if (index < 4) 1f else 340f + cpu * 22f,
            d3dRenderPassesPerFrame = if (index < 4) 1f else 14f + cpu / 8f,
            d3dSubmissionsPerFrame = if (index < 4) 2f else 3f + cpu / 40f,
            d3dBarriersPerFrame = if (index < 4) 9f else 46f + cpu / 4f,
            d3dDispatchesPerFrame = if (index < 4) 0f else 2f,
            d3dGpuSyncsPerFrame = 0f,
            d3dPipelines = 1 + index * 37,
            d3dPipelineLibraries = 481 + index * 4,
            d3dPipelinesCompute = if (index < 4) 0 else 3,
            d3dPipeTasksPending = if (index in 4..7) 12 - index else 0,
            d3dMemAllocatedMb = 1_083 + index * 40,
            d3dMemUsedMb = 850 + index * 36,
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
            MetricSource("d3d", "the session's VESSEL_GFX_STATS snapshot", true),
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
