package app.vessel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.vessel.ui.theme.VColors
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Which of the telemetry colours a series takes.
 *
 * A role rather than a `Color`, so no call site can reach past the tokens.
 * `docs/DESIGN.md` gives graphs `accent` → `accent2` for load ramps, and the two
 * are far enough apart to separate two lines without either becoming a third
 * meaning — the architecture palette is spoken for, and a green line on a
 * telemetry graph would read as "native" to anyone who has seen a [VArchBadge].
 */
enum class VSeriesTone { Primary, Secondary, Neutral }

/**
 * How a series is drawn, which is decided by what the number *is*.
 *
 * Borrowed from the reference implementation's one genuinely good idea: a
 * proportion of a fixed whole gets a fill, because the area under it is the work
 * done, and a level gets a bare line, because nothing meaningful sits underneath
 * one. Drawing memory as a filled area implies a quantity that was consumed.
 */
enum class VSeriesForm { Area, Line, Dashed }

/**
 * One line on a graph.
 *
 * [values] may contain nulls and they are load-bearing: a null is a tick where
 * the source could not be read, and the graph breaks the line rather than
 * bridging it. A bridged gap is an invented measurement, which on a panel whose
 * whole job is to be believed is the one unrecoverable mistake.
 *
 * [ceiling] is the value that reaches the top of the box. A percentage passes
 * 100 and a clock passes the part's rated maximum, so two graphs drawn side by
 * side are comparable rather than each auto-scaled to its own noise.
 */
@Immutable
data class VMetricSeries(
    val label: String,
    val values: List<Int?>,
    val ceiling: Int,
    val floor: Int = 0,
    val tone: VSeriesTone = VSeriesTone.Primary,
    val form: VSeriesForm = VSeriesForm.Area,
)

/**
 * The graph itself: one box, one or more series, no axes.
 *
 * No axis labels and no gridlines beyond a single mid-line. This is an
 * instrument panel on a phone, and at this size a y-axis costs a third of the
 * width to restate what the legend beside it already says. The mid-line stays
 * because without it a filled area can only be read as "more than last time"
 * rather than as a number.
 *
 * Self-contained on purpose — it takes values and nothing else, so it can be
 * embedded in the session rail, in the Metrics tab, or in a preview without any
 * of them knowing where the numbers came from.
 */
@Composable
fun VMetricGraph(
    series: List<VMetricSeries>,
    modifier: Modifier = Modifier,
    height: Dp = Vessel.metrics.graphHeight,
    /**
     * How to write a Y value — `{ "$it%" }`, `{ formatMegahertz(it) }`.
     *
     * Null draws no axis at all, which is right for a spark and for any card
     * whose series do not share a unit. The caller knows which it has; this does
     * not, and guessing would put `100` beside a line measured in megabytes.
     */
    axisStyle: ((Int) -> String)? = null,
    /** The window the samples cover, for the X labels. Zero draws none. */
    spanSeconds: Int = 0,
) {
    val colors = Vessel.colors
    val axisType = Vessel.type.monoSmall
    val metrics = Vessel.metrics
    val divider = colors.divider
    val resolved = series.map { it to it.tone.ink(colors) }

    val measurer = rememberTextMeasurer()
    val axisInk = colors.textMuted

    Canvas(
        modifier
            .fillMaxWidth()
            // Zero means the caller sized it — the full-screen view fills the
            // dialog rather than standing at the inline height in the middle of it.
            .then(if (height > 0.dp) Modifier.height(height) else Modifier)
            .background(colors.surfaceSunken, metrics.shapeSm)
            .padding(horizontal = metrics.s6, vertical = metrics.s3),
    ) {
        // **Axes only when there is room for them.** A rail spark is 44 dp tall
        // and three labels down its side would leave nothing to draw the line
        // in; the number beside it already says what the last reading was. They
        // appear at the height the panel and the full-screen view use, which are
        // the two places somebody is reading the shape rather than glancing.
        val labelled = size.height > 90f && axisStyle != null && series.isNotEmpty()
        val yGutter = if (labelled) 30.dp.toPx() else 0f
        val xGutter = if (labelled) 13.dp.toPx() else 0f
        val plot = Size(size.width - yGutter, size.height - xGutter)

        // **One ceiling for the card, because a card is one unit.** Every series
        // here shares a scale — eight cores in MHz, four sensors in °C, one
        // percentage — which is what makes a labelled Y axis honest at all. The
        // largest ceiling wins so a series normalised against a smaller one
        // still lands inside the box.
        val ceiling = series.maxOf { it.ceiling }
        for (frac in floatArrayOf(0f, 0.5f, 1f)) {
            val y = plot.height - plot.height * frac
            drawLine(
                color = if (frac == 0.5f) divider else divider.copy(alpha = 0.45f),
                start = Offset(yGutter, y),
                end = Offset(size.width, y),
                strokeWidth = metrics.hairline.toPx(),
            )
            if (labelled) {
                val text = measurer.measure(axisStyle!!((ceiling * frac).toInt()), axisType.copy(color = axisInk))
                drawText(
                    text,
                    topLeft = Offset(
                        yGutter - text.size.width - 3.dp.toPx(),
                        (y - text.size.height / 2f).coerceIn(0f, plot.height - text.size.height),
                    ),
                )
            }
        }

        // X is the window the samples cover, oldest on the left. Labelled as
        // "ago" rather than as a clock: the trace is a ring and its left edge is
        // wherever the window starts, not a time of day anyone can act on.
        if (labelled && spanSeconds > 0) {
            for ((frac, text) in listOf(0f to "-${spanSeconds}s", 1f to "now")) {
                val label = measurer.measure(text, axisType.copy(color = axisInk))
                val x = yGutter + (plot.width - label.size.width) * frac
                drawText(label, topLeft = Offset(x, plot.height + 1.dp.toPx()))
            }
        }

        resolved.forEach { (line, ink) ->
            // Each run of consecutive readings is its own path. Two runs either
            // side of a gap are two separate strokes, which is what makes a
            // missing sample look missing.
            runsOf(line, yGutter, plot).forEach { run ->
                drawRun(run, line.form, ink, metrics.graphStroke.toPx(), metrics.graphDot.toPx())
            }
        }
    }
}

/**
 * One unbroken run of points, already mapped to the canvas.
 *
 * The two widths arrive in pixels rather than being read from the theme, because
 * a `DrawScope` extension is not a composable and cannot reach a
 * `CompositionLocal`. Converting them at the one call site above keeps the tokens
 * as the source and this function as arithmetic.
 */
private fun DrawScope.drawRun(
    points: List<Offset>,
    form: VSeriesForm,
    ink: Color,
    strokePx: Float,
    dotRadiusPx: Float,
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        // A single reading between two gaps still happened, and a zero-length
        // path draws nothing at all. It gets a dot.
        drawCircle(ink, radius = dotRadiusPx, center = points.first())
        return
    }

    if (form == VSeriesForm.Area) {
        drawPath(
            path = Path().apply {
                moveTo(points.first().x, size.height)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, size.height)
                close()
            },
            // A vertical fade rather than a flat wash: on a dark ground a solid
            // tint at any alpha low enough not to shout also stops reading as a
            // shape, and the gradient keeps the edge crisp where it matters.
            brush = Brush.verticalGradient(
                listOf(ink.copy(alpha = AREA_TOP_ALPHA), ink.copy(alpha = AREA_BOTTOM_ALPHA)),
            ),
        )
    }

    drawPath(
        path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        },
        color = ink,
        style = Stroke(
            width = strokePx,
            pathEffect = if (form == VSeriesForm.Dashed) {
                PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF))
            } else {
                null
            },
        ),
    )
}

/**
 * Split a series at its gaps and map each run onto the canvas.
 *
 * The x step is fixed by the *declared* length rather than by how many readings
 * survived, so a graph with holes in it keeps its time base: a run of five
 * samples in the middle of a hundred draws in the middle, not across the whole
 * box.
 */
private fun DrawScope.runsOf(
    series: VMetricSeries,
    left: Float = 0f,
    plot: Size = size,
): List<List<Offset>> {
    val count = series.values.size
    if (count == 0) return emptyList()
    val span = (series.ceiling - series.floor).toFloat()
    if (span <= 0f) return emptyList()
    val step = if (count > 1) plot.width / (count - 1) else 0f

    val runs = mutableListOf<List<Offset>>()
    var current = mutableListOf<Offset>()
    series.values.forEachIndexed { index, value ->
        if (value == null) {
            if (current.isNotEmpty()) runs += current
            current = mutableListOf()
            return@forEachIndexed
        }
        val fraction = ((value - series.floor) / span).coerceIn(0f, 1f)
        current += Offset(left + index * step, plot.height - fraction * plot.height)
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

/** One derived number under a graph: `peak 88%`. */
@Immutable
data class VMetricStat(val label: String, val value: String)

/**
 * A graph with its name, its current value, its legend and its statistics — the
 * Metrics tab's unit of layout.
 *
 * **The graph is half of it.** A shape tells you a run ramped; it does not tell
 * you what it ramped *to*, and the number anyone quotes is the peak. So every
 * card carries its own [stats] row rather than the screen carrying one summary
 * table: five metrics with five different units share no columns, and a single
 * table would need a unit column before it was legible at all.
 *
 * [value] and every stat are pre-formatted strings, because the caller knows how
 * many digits a milliwatt deserves and this does not. When [unavailable] is set
 * the card says why instead of drawing an empty box, which is the rule the whole
 * feature exists to serve: never a fabricated or a zero reading.
 *
 * **There is no caption slot, deliberately.** This card had a `note` for a line
 * of explanation under the numbers, and the Metrics tab put one under nearly
 * every card until the whole tab read as prose with graphs in it. A card is its
 * title, its number, its shape and its statistics; anything that has to be said
 * about scope goes in the title, where it is read. Taking the parameter away
 * rather than leaving it unused is the point — a caption cannot drift back in
 * one card at a time.
 */
// FlowRow is still experimental at this Compose BOM. It is used for two rows
// that genuinely wrap — eight core legends and a stats row — and the fallback
// (a Row that clips the last entries off a phone) is worse than the opt-in.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VMetricGraphCard(
    title: String,
    value: String?,
    series: List<VMetricSeries>,
    modifier: Modifier = Modifier,
    unit: String? = null,
    stats: List<VMetricStat> = emptyList(),
    unavailable: String? = null,
    /**
     * Whether tapping the card opens it full screen. See [VMetricGraphDialog].
     *
     * On by default because every card in the Metrics panel wants it, and off
     * for the copy *inside* the dialog, which would otherwise open another one.
     */
    zoomable: Boolean = true,
    /** How to write a Y value for this card's unit. Null draws no Y axis. */
    axisStyle: ((Int) -> String)? = null,
    /** Seconds of history the samples cover, for the X axis. Zero draws none. */
    spanSeconds: Int = 0,
) {
    // **Tap the card, fill the screen with it.**
    //
    // A sparkline about 44 dp tall in a scrolling column is enough to see that
    // something moved and not enough to see when. That is fine for the rail,
    // whose job is a glance, and wrong for the panel, whose job is a diagnosis —
    // and today the difference decided an argument twice: a clock line sagging
    // mid-run, and a frame rate whose three identical baselines varied 20%.
    var zoomed by rememberSaveable(title) { mutableStateOf(false) }
    if (zoomed) {
        VMetricGraphDialog(series, axisStyle, spanSeconds) { zoomed = false }
    }
    Column(
        modifier
            .fillMaxWidth()
            .vCard()
            .then(if (zoomable) Modifier.clickable { zoomed = true } else Modifier)
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                title.uppercase(),
                style = Vessel.type.overline,
                color = Vessel.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(value, style = Vessel.type.metric, color = Vessel.colors.textPrimary)
                if (unit != null) {
                    Text(
                        unit,
                        style = Vessel.type.monoSmall,
                        color = Vessel.colors.textMuted,
                        modifier = Modifier.padding(
                            start = Vessel.metrics.hairGap,
                            bottom = Vessel.metrics.s3,
                        ),
                    )
                }
            }
        }

        if (unavailable != null) {
            Text(unavailable, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
            return@Column
        }

        VMetricGraph(series, axisStyle = axisStyle, spanSeconds = spanSeconds)

        if (series.size > 1) {
            // Wrapping, because eight cores is eight legend entries and a Row
            // would push the last of them off a phone.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
                series.forEach { Legend(it) }
            }
        }

        if (stats.isNotEmpty()) VMetricStatRow(stats)
    }
}

/**
 * The derived numbers under a graph, as label-over-value pairs.
 *
 * Values in `mono` and labels in `monoSmall` beneath them, so the digits line up
 * in a column the eye can compare down — a `label: value` run of text does not,
 * and comparing peak against mean is the only reason the row exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VMetricStatRow(stats: List<VMetricStat>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s17),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        stats.forEach { stat ->
            Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.hairline)) {
                Text(stat.value, style = Vessel.type.mono, color = Vessel.colors.textPrimary)
                Text(
                    stat.label.uppercase(),
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                )
            }
        }
    }
}

/**
 * The mark carries the series' form as well as its colour.
 *
 * A dashed series gets a broken swatch, because form is what actually
 * distinguishes the lines on a crowded axis here — eight per-core clocks share
 * two nearly identical violets, and a legend that showed only colour would claim
 * a distinction the graph does not make.
 */
@Composable
private fun Legend(series: VMetricSeries) {
    val ink = series.tone.ink(Vessel.colors)
    Row(
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (series.form == VSeriesForm.Dashed) {
            Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.hairGap)) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(width = Vessel.metrics.legendDashWidth, height = Vessel.metrics.legendMarkHeight)
                            .background(ink),
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .size(width = Vessel.metrics.legendMarkWidth, height = Vessel.metrics.legendMarkHeight)
                    .background(ink),
            )
        }
        Text(series.label, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
    }
}

/**
 * One quantity, one ceiling, one sparkline — the session rail's unit of layout.
 *
 * **The rail used to draw every series on one shared graph, and that could not
 * be right.** A CPU percentage, a GPU percentage, a clock in MHz and a memory
 * figure in MB have no common vertical axis, so any single ceiling was correct
 * for at most one of them and the rest were drawn against a number that had
 * nothing to do with them. Splitting them is what lets each keep its own honest
 * ceiling — 100% for a load, the part's rated maximum for a clock, device RAM for
 * memory — so that a line near the top of its box means *flat out* rather than
 * *taller than its neighbour*.
 *
 * Compact, because four of these float over a desktop the user is trying to see:
 * the name and the current reading share one line, and the graph under it is
 * [SPARK_GRAPH_HEIGHT]. That is a glance, not a reading; the Metrics tab's
 * [VMetricGraphCard] is where the same series gets room and statistics.
 *
 * It draws no surface of its own — the rail is one translucent card and these are
 * its contents. Nesting a card here would be a ringed box inside a ringed box,
 * 8 dp apart, over a running Windows desktop.
 *
 * [unavailable] is said instead of a graph. A series of nothing but nulls would
 * otherwise draw an empty box that looks exactly like a working graph of a
 * quantity pinned at zero, which is the one mistake this whole feature exists to
 * avoid.
 */
@Composable
fun VMetricSpark(
    label: String,
    value: String?,
    series: VMetricSeries?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tone: VMetricTone = VMetricTone.Normal,
    unavailable: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.hairGap)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                label.uppercase(),
                style = Vessel.type.overline,
                color = Vessel.colors.textMuted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            // Never wraps, never shrinks. `573 MB` broken over three lines in a
            // narrow rail renders as `57 / 3 / MB`, which is a plausible value
            // and a wrong one — see the rule under Typography in DESIGN.md.
            if (value != null) {
                Text(
                    value,
                    style = Vessel.type.metricSmall,
                    color = toneColor(tone),
                    maxLines = 1,
                    softWrap = false,
                )
                if (unit != null) {
                    Text(
                        unit,
                        style = Vessel.type.monoSmall,
                        color = Vessel.colors.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = Vessel.metrics.s3),
                    )
                }
            }
        }
        if (series != null) {
            VMetricGraph(listOf(series), height = Vessel.metrics.sparkHeight)
        } else {
            Text(
                unavailable ?: NO_READING,
                style = Vessel.type.monoSmall,
                color = Vessel.colors.neutral600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun VSeriesTone.ink(colors: VColors): Color = when (this) {
    VSeriesTone.Primary -> colors.accent
    VSeriesTone.Secondary -> colors.accent2
    VSeriesTone.Neutral -> colors.neutral500
}

/** A working source that has produced nothing yet. Not the same as a denied one. */
private const val NO_READING = "no reading"

/**
 * Where a live value sits against its thresholds.
 *
 * Shared by [VMetricSpark] and every caller that formats a reading, so one number
 * cannot be amber in the rail and plain on the Metrics tab.
 */
enum class VMetricTone { Normal, Ok, Warn, Danger }

@Composable
internal fun toneColor(tone: VMetricTone): Color = when (tone) {
    VMetricTone.Normal -> Vessel.colors.textPrimary
    VMetricTone.Ok -> Vessel.colors.ok
    VMetricTone.Warn -> Vessel.colors.warn
    VMetricTone.Danger -> Vessel.colors.danger
}

/** Nocturne's fills sit low; these are the two ends of the area gradient. */
private const val AREA_TOP_ALPHA = 0.26f
private const val AREA_BOTTOM_ALPHA = 0.02f

private const val DASH_ON = 6f
private const val DASH_OFF = 4f

// — previews ---------------------------------------------------------------
//
// Fabricated series, and the only fabricated numbers anywhere in this feature.
// They exist so the stroke weights and the gap handling can be judged without a
// device; nothing that reaches a user is ever generated.

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VMetricGraphCardPreview() {
    VesselTheme {
        Column(
            Modifier.padding(Vessel.metrics.s11),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        ) {
            VMetricGraphCard(
                title = "cpu",
                value = "62",
                unit = "%",
                stats = listOf(
                    VMetricStat("peak", "88%"),
                    VMetricStat("mean", "54%"),
                    VMetricStat("min", "12%"),
                    VMetricStat("samples", "16"),
                ),
                series = listOf(
                    VMetricSeries("cpu", SampleCpu, ceiling = 100),
                    VMetricSeries(
                        "gpu",
                        SampleGpu,
                        ceiling = 100,
                        tone = VSeriesTone.Secondary,
                        form = VSeriesForm.Dashed,
                    ),
                ),
            )
            VMetricGraphCard(
                title = "gpu",
                value = null,
                series = emptyList(),
                unavailable = "The Adreno busy counter is not readable in this app " +
                    "sandbox, so no GPU figure is recorded for this device.",
            )
        }
    }
}

// At the rail's real inner width, which is the only width these have to work at.
@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 150)
@Composable
private fun VMetricSparkPreview() {
    VesselTheme {
        Column(
            Modifier.padding(Vessel.metrics.s8),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        ) {
            VMetricSpark(
                "cpu",
                "62",
                VMetricSeries("cpu", SampleCpu, ceiling = 100),
                unit = "%",
            )
            VMetricSpark(
                "gpu",
                "77",
                VMetricSeries("gpu", SampleGpu, ceiling = 100, tone = VSeriesTone.Secondary),
                unit = "%",
                tone = VMetricTone.Warn,
            )
            // The five-figure reading in a 134 dp box, which is the case that
            // broke the old grid.
            VMetricSpark(
                "memory",
                "3.4 GB",
                VMetricSeries(
                    "rss",
                    SampleCpu,
                    ceiling = 100,
                    tone = VSeriesTone.Secondary,
                    form = VSeriesForm.Line,
                ),
            )
            VMetricSpark("clock", null, null, unavailable = "source unavailable")
        }
    }
}

private val SampleCpu: List<Int?> =
    listOf(12, 18, 31, 44, 62, 71, 66, 58, 61, 74, 88, 79, 65, 60, 57, 62)

/** Two nulls in the middle: the gap is the case most worth being able to see. */
private val SampleGpu: List<Int?> =
    listOf(4, 9, 22, 38, 51, null, null, 64, 70, 77, 81, 72, 68, 71, 75, 77)

/**
 * The chart alone, landscape, edge to edge.
 *
 * **Only the chart, and only sideways.** The first version put the whole card in
 * here — title, value, stats — which is the card again at a larger size, and the
 * card is what you already have. What is missing at 44 dp is the *shape*: where
 * a line sagged, how long it held, whether a dip was one sample or twenty. That
 * needs pixels along the time axis, and the phone has twice as many of them
 * sideways.
 *
 * `requestedOrientation` is set for as long as the dialog lives and restored to
 * whatever it was — not to portrait, because the session may already be
 * landscape and forcing a value nobody asked for is its own bug.
 *
 * A tap anywhere closes it: there is no chrome to hang a button on, which is
 * the point.
 */
@Composable
private fun VMetricGraphDialog(
    series: List<VMetricSeries>,
    axisStyle: ((Int) -> String)?,
    spanSeconds: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findHostActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { previous?.let { activity?.requestedOrientation = it } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Vessel.colors.bg)
                .clickable(onClick = onDismiss)
                .padding(Vessel.metrics.s8),
        ) {
            VMetricGraph(series, Modifier.fillMaxSize(), height = 0.dp, axisStyle = axisStyle, spanSeconds = spanSeconds)
        }
    }
}

/** The Activity behind a Compose context, through any number of wrappers. */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
