package app.vessel.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.core.ContainerProfile
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/**
 * The Containers home tile.
 *
 * The name and the launch action, and nothing else. Everything the card used to
 * carry — the Wine build, the driver, the D3D layer, the last-run time — was
 * either identical on every card in a build that compiles in one of each, or a
 * fact the user could not act on from here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VContainerCard(
    container: ContainerProfile,
    onOpen: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .vCard()
            // Long-press is the delete affordance. It is not the only one — the
            // editor carries an explicit Delete — because a gesture nothing
            // advertises cannot be the sole route to a destructive action.
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(Vessel.metrics.s17),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name and launch, and nothing else.
        //
        // The card used to list the Wine build, driver and D3D layer, on the
        // reasoning that they decide whether a program runs. They do — but this
        // build compiles in exactly one of each, so every card carried three
        // identical lines that no user could act on. Same argument as the
        // architecture badge, which was dropped for the same reason.
        Text(
            container.name,
            style = Vessel.type.cardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Outlined and unlabelled, like every primary action in the system. A
        // play triangle beside a named container is unambiguous; the content
        // description carries the label for screen readers.
        VIconAction(
            icon = Icons.Filled.PlayArrow,
            contentDescription = "Launch ${container.name}",
            onClick = onLaunch,
            style = VButtonStyle.Primary,
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
                    wineBuild = "wine-11.0-arm64ec",
                    driver = "turnip-gen8-25.2.0",
                    d3dLayer = "dxvk-2.7.1",
                    lastRun = null,
                ),
                onOpen = {},
                onLaunch = {},
            )
        }
    }
}
