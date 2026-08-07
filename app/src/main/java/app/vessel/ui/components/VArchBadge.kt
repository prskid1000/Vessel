package app.vessel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.core.PeArchitecture
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme

/**
 * The architecture palette, in the one place it is decided.
 *
 * ARM64 and ARM64EC are both green because both run without translation, which
 * is the fact the colour is carrying — not the machine word they were built
 * from.
 */
@Composable
@ReadOnlyComposable
fun archColor(arch: PeArchitecture): Color = when (arch) {
    PeArchitecture.ARM64, PeArchitecture.ARM64EC -> Vessel.colors.archNative
    PeArchitecture.X64 -> Vessel.colors.archX64
    PeArchitecture.X86 -> Vessel.colors.archX86
    PeArchitecture.UNKNOWN -> Vessel.colors.archUnknown
}

/** A mono pill naming what an executable or container runs. */
@Composable
fun VArchBadge(
    arch: PeArchitecture,
    modifier: Modifier = Modifier,
) {
    VTonalPill(text = arch.label, tone = archColor(arch), modifier = modifier)
}

/** The badge shape shared by [VArchBadge] and the container profile tag. */
@Composable
fun VTonalPill(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapePill
    Text(
        text,
        style = Vessel.type.monoSmall,
        color = tone,
        modifier = modifier
            .background(tone.copy(alpha = 0.14f), shape)
            .border(Vessel.metrics.hairline, tone.copy(alpha = 0.35f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F)
@Composable
private fun VArchBadgePreview() {
    VesselTheme {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            PeArchitecture.entries.forEach { VArchBadge(it) }
        }
    }
}
