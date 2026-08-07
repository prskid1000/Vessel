package app.vessel.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.core.ArchProfile
import app.vessel.core.ContainerProfile
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/**
 * The Containers home tile.
 *
 * Everything on it is a machine fact except the name, so everything but the
 * name is mono. [lastRunLabel] arrives already formatted — the card does no
 * clock arithmetic, which also makes it previewable and testable.
 */
@Composable
fun VContainerCard(
    container: ContainerProfile,
    lastRunLabel: String,
    onOpen: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .vCard()
            .clickable(onClick = onOpen)
            .padding(Vessel.metrics.s17),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                container.name,
                style = Vessel.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            VTonalPill(container.archProfile.label, archProfileColor(container.archProfile))
        }

        // Wine, driver and D3D layer are what actually decides whether a given
        // program runs, so they sit on the tile rather than behind an edit.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            VContainerFact("wine", container.wineBuild)
            VContainerFact("driver", container.driver)
            VContainerFact("d3d", container.d3dLayer)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lastRunLabel,
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            // Outlined, like every primary action in the system. This used to be
            // a solid accent slab and was the single loudest thing on the home
            // screen — which is exactly backwards for a card whose job is to
            // show machine facts.
            VButton(
                label = "Launch",
                onClick = onLaunch,
                style = VButtonStyle.Primary,
                icon = Icons.Filled.PlayArrow,
            )
        }
    }
}

/** The profile colours by what Wine itself is: native ARM64EC, or x86 under Box64. */
@Composable
private fun archProfileColor(profile: ArchProfile): Color = when (profile) {
    ArchProfile.UNIVERSAL -> Vessel.colors.archNative
    ArchProfile.COMPATIBILITY -> Vessel.colors.archX64
}

@Composable
private fun VContainerFact(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
        Text(
            key,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.width(48.dp),
        )
        Text(
            value,
            style = Vessel.type.mono,
            color = Vessel.colors.textLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VContainerCardPreview() {
    VesselTheme {
        // One container, because there is one build of everything for this
        // device. A second card showing a legacy Wine tree would be advertising
        // a catalogue this product deliberately does not have.
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            VContainerCard(
                container = ContainerProfile(
                    id = "default",
                    name = "Default",
                    archProfile = ArchProfile.UNIVERSAL,
                    wineBuild = "wine-11.0-arm64ec",
                    driver = "turnip-gen8-25.2.0",
                    d3dLayer = "dxvk-2.7.1",
                    lastRun = null,
                ),
                lastRunLabel = "ran 12 minutes ago",
                onOpen = {},
                onLaunch = {},
            )
        }
    }
}
