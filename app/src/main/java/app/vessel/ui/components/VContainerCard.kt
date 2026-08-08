package app.vessel.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.ContainerProfile
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/**
 * The Containers home tile.
 *
 * Two lines and the launch action. Everything the card used to carry — the Wine
 * build, the driver, the D3D layer — was identical on every card in a build that
 * compiles in one of each, so it went; [meta] is the exception, because when a
 * container last ran is the one fact about it that is not the same as the next
 * one's.
 *
 * That is also what makes the tile a row rather than a slab. With a single
 * centred word in it the card still measured 78 dp tall and 420 dp wide, which
 * on the home screen of a one-container install is most of a phone spent saying
 * "Default". It is now sized by its content: two lines of type against a 40 dp
 * launch square.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VContainerCard(
    container: ContainerProfile,
    onOpen: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
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
            .padding(
                start = Vessel.metrics.s11,
                end = Vessel.metrics.s6,
                top = Vessel.metrics.s6,
                bottom = Vessel.metrics.s6,
            ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3)) {
            Text(
                container.name,
                style = Vessel.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                // Mono, because "ran 12 minutes ago" is a reading off a clock —
                // and mono is what tells the eye, before it has read a word,
                // that the second line is a fact rather than a description.
                Text(
                    meta,
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

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
        Column(
            Modifier.padding(Vessel.metrics.s11),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        ) {
            VContainerCard(
                container = ContainerProfile(
                    id = "default",
                    name = "Default",
                    wineBuild = "wine-11.0-arm64ec",
                    driver = "turnip-gen8-25.2.0",
                    d3dLayer = "dxvk-2.7.1",
                    lastRun = null,
                ),
                meta = "never launched",
                onOpen = {},
                onLaunch = {},
            )
        }
    }
}
