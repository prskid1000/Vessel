package app.vessel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

/** The session overlay's readout: FPS, frame time, CPU, GPU, thermal. */
@Composable
fun VMetricStrip(
    metrics: List<VMetricValue>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .vCard(shape = Vessel.metrics.shapeMd)
            .padding(vertical = Vessel.metrics.s12),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEach { metric ->
            Column(
                Modifier.weight(1f).padding(horizontal = Vessel.metrics.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(metric.value, style = Vessel.type.metric, color = toneColor(metric.tone))
                    if (metric.unit != null) {
                        Text(
                            metric.unit,
                            style = Vessel.type.monoSmall,
                            color = Vessel.colors.textTertiary,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        )
                    }
                }
                Text(
                    metric.label.uppercase(),
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun toneColor(tone: VMetricTone): Color = when (tone) {
    VMetricTone.Normal -> Vessel.colors.textPrimary
    VMetricTone.Ok -> Vessel.colors.ok
    VMetricTone.Warn -> Vessel.colors.warn
    VMetricTone.Danger -> Vessel.colors.danger
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F, widthDp = 392)
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
            modifier = Modifier.padding(16.dp),
        )
    }
}
