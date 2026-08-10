package app.vessel.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.ContainerProfile
import app.vessel.core.PeArchitecture
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/**
 * A container and everything in it — the whole of the home screen.
 *
 * **The programs live inside the container that owns them, and that is the
 * navigation decision this card carries.** There used to be an Apps root: a flat
 * grid of every executable across every container, whose first job was to tell
 * you which container each belonged to. A program is meaningless without its
 * prefix — the same `.exe` in two containers is two different things, with two
 * different drivers and two different registries — so the list that says so is
 * the only list worth having. Apps is not a screen any more; it is these four
 * columns.
 *
 * Three targets on the header row, and they are three different verbs:
 *
 * - the name and its meta line open the container's settings sheet,
 * - the folder button pushes Files on this container's `C:`,
 * - the play button starts the desktop with nothing running on it.
 *
 * The folder button is on the row rather than one level down in settings because
 * browsing a drive is a daily action and configuring a container is not — and
 * because the row is the only place the container is unambiguous without asking,
 * which was the whole objection to Files having a root of its own.
 */
@Composable
fun VContainerCard(
    container: ContainerProfile,
    shortcuts: List<AppShortcut>,
    onOpenSettings: () -> Unit,
    onLaunch: () -> Unit,
    onLaunchApp: (AppShortcut) -> Unit,
    onOpenAppProfile: (AppShortcut) -> Unit,
    onAddApp: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether this container has somewhere to add a program *from*.
     *
     * False until both `C:` and `D:` exist. `D:` is the conditional one — it is
     * the phone's storage, and it is only mapped when all-files access has been
     * granted — so without it the picker opens on a prefix that contains no
     * program anyone put there. See [app.vessel.ui.vm.HomeContainer.drivesReady].
     */
    canAddApp: Boolean = true,
    meta: String? = null,
    /**
     * Browse this container's `C:`, or null when there is nothing to browse.
     *
     * Null before the first launch: there is no `drive_c` until the prefix has
     * been created, and a folder button that opens an empty directory reads as a
     * broken feature rather than an empty one.
     */
    onBrowseFiles: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .vCard()
            .padding(
                start = Vessel.metrics.s11,
                end = Vessel.metrics.s6,
                top = Vessel.metrics.s6,
                bottom = Vessel.metrics.s8,
            ),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only the text column is the settings target. The card as a whole
            // used to be, which made every tap that missed a button open the
            // editor — including the ones aimed at a program.
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "${container.name} settings", onClick = onOpenSettings)
                    .padding(vertical = Vessel.metrics.s3),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
            ) {
                Text(
                    container.name,
                    style = Vessel.type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) {
                    // Mono, because "ran 12 minutes ago · 1280×720 · 60 fps" is a
                    // set of readings — and mono is what tells the eye, before it
                    // has read a word, that the second line is fact and not
                    // description.
                    Text(
                        meta,
                        style = Vessel.type.monoSmall,
                        color = Vessel.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (onBrowseFiles != null) {
                VIconAction(
                    icon = VIcons.Folder,
                    contentDescription = "Browse ${container.name} files",
                    onClick = onBrowseFiles,
                )
            }

            VIconAction(
                icon = VIcons.Play,
                contentDescription = "Launch ${container.name}",
                onClick = onLaunch,
                style = VButtonStyle.Primary,
            )
        }

        VAppGrid(
            shortcuts = shortcuts,
            containerName = container.name,
            onLaunch = onLaunchApp,
            onOpenProfile = onOpenAppProfile,
            onAdd = onAddApp,
            modifier = Modifier.padding(end = Vessel.metrics.s3),
            addEnabled = canAddApp,
        )
        if (!canAddApp) {
            // The reason, because a dimmed control with no explanation is the
            // thing the disabled state was supposed to avoid.
            Text(
                "Allow storage access in this container's settings to add programs.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(
                    start = Vessel.metrics.s3,
                    end = Vessel.metrics.s3,
                    top = Vessel.metrics.s6,
                ),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421)
@Composable
private fun VContainerCardPreview() {
    VesselTheme {
        Column(
            Modifier.padding(Vessel.metrics.s11),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        ) {
            VContainerCard(
                container = ContainerProfile(
                    id = "default",
                    name = "Display proof",
                    wineBuild = "wine-11.0-arm64ec",
                    driver = "turnip-gen8-25.2.0",
                    d3dLayer = "dxvk-2.7.1",
                ),
                shortcuts = listOf(
                    AppShortcut("1", "d", "C:\\npp\\notepad++.exe", "Notepad++", PeArchitecture.ARM64),
                    AppShortcut("2", "d", "C:\\winamp\\winamp.exe", "Winamp", PeArchitecture.X86),
                ),
                meta = "ran 12 minutes ago · 1280×720 · 60 fps",
                onOpenSettings = {},
                onLaunch = {},
                onLaunchApp = {},
                onOpenAppProfile = {},
                onAddApp = {},
                onBrowseFiles = {},
            )
        }
    }
}
