package app.vessel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing

/**
 * A named engine build — `FEX 2608`, `Box64 0.4.4`.
 *
 * Mono, because the version is a machine fact and the whole point of the chip
 * is that you can read which build you are actually running. Tappable where
 * there is something to switch to.
 */
@Composable
fun VEngineChip(
    engine: String,
    version: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // The `.seg-opt` form: transparent ground, a ring, and the accent doing the
    // selecting. Never a filled chip — see VButton for why nothing in this
    // system carries a solid accent ground.
    val shape = Vessel.metrics.shapeMd
    val fill = if (selected) Vessel.colors.accentSoft else Color.Transparent
    val stroke = if (selected) Vessel.colors.accent else Vessel.colors.divider
    val text = if (selected) Vessel.colors.accent else Vessel.colors.textLabel

    Row(
        modifier
            .background(fill, shape)
            .vRing(stroke, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Vessel.metrics.s11, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(engine, style = Vessel.type.label, color = text)
        Text(version, style = Vessel.type.mono, color = text)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun VEngineChipPreview() {
    VesselTheme {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VEngineChip("FEX", "2608", selected = true, onClick = {})
            VEngineChip("Box64", "0.4.4", onClick = {})
        }
    }
}
