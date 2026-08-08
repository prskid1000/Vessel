package app.vessel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/** Where a live value sits against its thresholds. */
enum class VMetricTone { Normal, Ok, Warn, Danger }

/**
 * One reading. [value] is a string rather than a number because the strip does
 * no formatting: a frame time is 16.7 and a memory figure is 3.2, and deciding
 * how many digits each keeps belongs with whatever is sampling them.
 */
@Immutable
data class VMetricValue(
    val label: String,
    val value: String,
    val unit: String? = null,
    val tone: VMetricTone = VMetricTone.Normal,
)

/**
 * One cell: the number, its unit, and the name of the quantity under it.
 *
 * **`softWrap = false` is the whole point of this composable existing.** Every
 * reading here is one token — `573 MB`, `37.8`, `100 %` — and a token that wraps
 * is not a shortened reading, it is a *different number*: `573 MB` broken over
 * three lines in a narrow rail rendered as `57 / 3 / MB`, which is a plausible
 * value and a wrong one. Clipping is not the answer either, for the same reason.
 * The cell refuses to break, and the layouts below are sized so it never has to.
 */
@Composable
private fun VMetricCell(
    metric: VMetricValue,
    valueStyle: TextStyle,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, horizontalAlignment = alignment) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                metric.value,
                style = valueStyle,
                color = toneColor(metric.tone),
                maxLines = 1,
                softWrap = false,
            )
            if (metric.unit != null) {
                Text(
                    metric.unit,
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = Vessel.metrics.s3),
                )
            }
        }
        Text(
            metric.label.uppercase(),
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The wide form: one horizontal row of readings on its own raised card.
 *
 * For a strip pinned across the top of a running desktop, where the width is the
 * screen's and the constraint is height. The narrow form is [VMetricGrid] — they
 * are separate composables rather than one with a flag because the cells are
 * sized differently, and a single component that silently changes its type scale
 * with its width is a component nobody can predict.
 */
@Composable
fun VMetricStrip(
    metrics: List<VMetricValue>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            // The one genuinely floating surface in the product: the strip sits
            // over a running Windows desktop, so it takes `--shadow-md` — the
            // lighter neutral-700 ring plus ambient darkness — rather than the
            // flat hairline every in-page card gets. DESIGN.md's "ring only when
            // raised" is about exactly this distinction.
            .vCard(elevation = VElev.md)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEach { metric ->
            VMetricCell(
                metric = metric,
                valueStyle = Vessel.type.metric,
                alignment = Alignment.CenterHorizontally,
            )
        }
    }
}

/**
 * The narrow form: readings in a fixed grid, no card of its own.
 *
 * Two columns rather than four `weight(1f)` cells, which is what the rail had
 * and what broke it — four weighted columns inside 208 dp gives each reading
 * 45 dp, and no memory figure fits in 45 dp at any type size worth reading. Two
 * columns of [CELL_MIN_WIDTH] give each one twice that, and the grid is fixed so
 * the rail does not breathe in and out by a few pixels as digits change.
 *
 * Unwrapped by design: the caller owns the surface, because in the rail this
 * sits inside the same card as the graph it belongs to. Two nested cards is a
 * box drawn inside a box, which is what the rail looked like before.
 */
@Composable
fun VMetricGrid(
    metrics: List<VMetricValue>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        metrics.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
                row.forEach { metric ->
                    VMetricCell(
                        metric = metric,
                        valueStyle = Vessel.type.metricSmall,
                        modifier = Modifier.widthIn(min = CELL_MIN_WIDTH),
                    )
                }
            }
        }
    }
}

/**
 * How wide a grid cell is before its content.
 *
 * Six mono digits plus a two-character unit at [app.vessel.ui.theme.VType.metricSmall],
 * which covers every reading the sampler can produce — the widest is a
 * five-figure memory value. A minimum rather than a fixed width so a cell that
 * needs one more character takes it instead of refusing to draw it.
 */
private val CELL_MIN_WIDTH: Dp = 62.dp

/** Shared with [VMetricSpark], so one reading cannot be amber in two places and not a third. */
@Composable
internal fun toneColor(tone: VMetricTone): Color = when (tone) {
    VMetricTone.Normal -> Vessel.colors.textPrimary
    VMetricTone.Ok -> Vessel.colors.ok
    VMetricTone.Warn -> Vessel.colors.warn
    VMetricTone.Danger -> Vessel.colors.danger
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VMetricStripPreview() {
    VesselTheme {
        VMetricStrip(
            metrics = listOf(
                VMetricValue("fps", "58", tone = VMetricTone.Ok),
                VMetricValue("frame", "17.2", "ms"),
                VMetricValue("cpu", "71", "%"),
                VMetricValue("gpu", "94", "%", VMetricTone.Warn),
                VMetricValue("temp", "46", "°", VMetricTone.Normal),
            ),
            modifier = Modifier.padding(Vessel.metrics.s11),
        )
    }
}

// The rail's width, so the case that broke — a five-figure memory reading in two
// narrow columns — is the case the preview shows.
@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 150)
@Composable
private fun VMetricGridPreview() {
    VesselTheme {
        VMetricGrid(
            metrics = listOf(
                VMetricValue("cpu", "71", "%"),
                VMetricValue("gpu", "94", "%", VMetricTone.Warn),
                VMetricValue("rss", "573 MB"),
                VMetricValue("temp", "37.8", "°C"),
            ),
            modifier = Modifier.padding(Vessel.metrics.s8),
        )
    }
}
