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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.VColors
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

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
    height: Dp = GRAPH_HEIGHT,
) {
    val colors = Vessel.colors
    val metrics = Vessel.metrics
    val divider = colors.divider
    val resolved = series.map { it to it.tone.ink(colors) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.surfaceSunken, metrics.shapeSm)
            .padding(horizontal = metrics.s6, vertical = metrics.s3),
    ) {
        drawLine(
            color = divider,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1f,
        )

        resolved.forEach { (line, ink) ->
            // Each run of consecutive readings is its own path. Two runs either
            // side of a gap are two separate strokes, which is what makes a
            // missing sample look missing.
            runsOf(line).forEach { run -> drawRun(run, line.form, ink) }
        }
    }
}

/** One unbroken run of points, already mapped to the canvas. */
private fun DrawScope.drawRun(points: List<Offset>, form: VSeriesForm, ink: Color) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        // A single reading between two gaps still happened, and a zero-length
        // path draws nothing at all. It gets a dot.
        drawCircle(ink, radius = DOT_RADIUS_DP.dp.toPx(), center = points.first())
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
            width = STROKE_DP.dp.toPx(),
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
private fun DrawScope.runsOf(series: VMetricSeries): List<List<Offset>> {
    val count = series.values.size
    if (count == 0) return emptyList()
    val span = (series.ceiling - series.floor).toFloat()
    if (span <= 0f) return emptyList()
    val step = if (count > 1) size.width / (count - 1) else 0f

    val runs = mutableListOf<List<Offset>>()
    var current = mutableListOf<Offset>()
    series.values.forEachIndexed { index, value ->
        if (value == null) {
            if (current.isNotEmpty()) runs += current
            current = mutableListOf()
            return@forEachIndexed
        }
        val fraction = ((value - series.floor) / span).coerceIn(0f, 1f)
        current += Offset(index * step, size.height - fraction * size.height)
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
    note: String? = null,
    unavailable: String? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .vCard()
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
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                    )
                }
            }
        }

        if (unavailable != null) {
            Text(unavailable, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
            return@Column
        }

        VMetricGraph(series)

        if (series.size > 1) {
            // Wrapping, because eight cores is eight legend entries and a Row
            // would push the last of them off a phone.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
                series.forEach { Legend(it) }
            }
        }

        if (stats.isNotEmpty()) VMetricStatRow(stats)

        if (note != null) {
            Text(note, style = Vessel.type.monoSmall, color = Vessel.colors.neutral600)
        }
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
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(width = LEGEND_DASH_WIDTH, height = LEGEND_MARK_HEIGHT)
                            .background(ink),
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .size(width = LEGEND_MARK_WIDTH, height = LEGEND_MARK_HEIGHT)
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            VMetricGraph(listOf(series), height = SPARK_GRAPH_HEIGHT)
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

/** Tall enough to show a shape, short enough that four of them are one screen. */
private val GRAPH_HEIGHT = 56.dp

/**
 * The rail's, and the number the whole rail is budgeted around.
 *
 * Four sparks at 22 dp of graph plus their label lines come to 170 dp, which
 * leaves the header, the two rules and five actions inside a landscape window on
 * this phone. At 22 dp the box has 16 dp of drawable height after
 * [VMetricGraph]'s own padding — enough for a shape to be a shape, and the point
 * at which a fifth quantity would have to displace one of the four rather than
 * squeeze in beside them.
 */
private val SPARK_GRAPH_HEIGHT = 22.dp

/** A working source that has produced nothing yet. Not the same as a denied one. */
private const val NO_READING = "no reading"

private val LEGEND_MARK_WIDTH = 9.dp
private val LEGEND_MARK_HEIGHT = 2.dp

/** Two of these plus the gap add up to [LEGEND_MARK_WIDTH], so marks stay aligned. */
private val LEGEND_DASH_WIDTH = 3.5.dp

private const val STROKE_DP = 1.6f
private const val DOT_RADIUS_DP = 1.2f

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
                title = "cpu · session",
                value = "62",
                unit = "%",
                stats = listOf(
                    VMetricStat("peak", "88%"),
                    VMetricStat("mean", "54%"),
                    VMetricStat("min", "12%"),
                    VMetricStat("samples", "16"),
                ),
                note = "session process tree, not the device",
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
