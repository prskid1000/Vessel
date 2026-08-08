package app.vessel.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.PeArchitecture
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing

/**
 * One Windows program, as a tile.
 *
 * **Drawn once, used twice** — inside the container card on home, and inside the
 * launcher over a running desktop. That is not code reuse for its own sake: they
 * are the same list of the same programs, and two designs for one concept is how
 * a product ends up with an app that is called one thing in one place and another
 * somewhere else.
 *
 * Three facts and no more: what it is called, what it was built for, and the
 * letter standing in for an icon nobody has extracted yet. The architecture badge
 * keeps its functional colour here as everywhere — green runs natively, blue and
 * amber are translated, grey means the header would not read.
 *
 * Tap launches. Long-press opens the profile sheet, which is the only place a
 * program's arguments and working directory live.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VAppTile(
    shortcut: AppShortcut,
    onLaunch: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Vessel.colors.neutral900, Vessel.metrics.shapeMd)
            .combinedClickable(
                onClickLabel = "Launch ${shortcut.name}",
                onLongClickLabel = "${shortcut.name} settings",
                onLongClick = onOpenProfile,
                onClick = onLaunch,
            )
            .padding(horizontal = Vessel.metrics.s3, vertical = Vessel.metrics.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        VAppIcon(shortcut.initial)
        Text(
            shortcut.name,
            style = Vessel.type.label,
            color = Vessel.colors.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VArchBadge(shortcut.arch)
    }
}

/**
 * The icon square: a letter in a ringed box.
 *
 * `mono` rather than the sans, because the letter is standing in for a machine
 * fact — which file this is — and not for a word. It is also what stops the tile
 * reading as a contact avatar.
 */
@Composable
fun VAppIcon(initial: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(Vessel.metrics.tileIcon)
            .vRing(Vessel.colors.neutral800, Vessel.metrics.shapeMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = Vessel.type.cardTitle, color = Vessel.colors.textMuted)
    }
}

/**
 * The last cell of every grid: add a program to this container.
 *
 * Outlined rather than filled, so it reads as the empty slot it is and not as a
 * fifth program. It keeps the tiles' geometry exactly — a grid whose last cell is
 * a different height is a grid with a ragged bottom edge.
 */
@Composable
fun VAddAppTile(
    onClick: () -> Unit,
    containerName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .vRing(Vessel.colors.divider, Vessel.metrics.shapeMd)
            .clickable(onClickLabel = "Add a program to $containerName", onClick = onClick)
            .padding(horizontal = Vessel.metrics.s3, vertical = Vessel.metrics.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6, Alignment.CenterVertically),
    ) {
        Icon(
            VIcons.Plus,
            contentDescription = null,
            Modifier.size(Vessel.metrics.iconMd),
            tint = Vessel.colors.textMuted,
        )
        Text("Add", style = Vessel.type.overline, color = Vessel.colors.textMuted)
    }
}

/**
 * The tile grid: four across, and the Add cell always last.
 *
 * Rows of weighted cells rather than a `LazyVerticalGrid`, and that is a
 * correctness point rather than a preference — this grid lives inside a container
 * card inside a `LazyColumn`, and a lazy grid nested in a lazy column in the same
 * direction has no finite height. Blank cells pad the final row so the last real
 * tile keeps its column width instead of stretching across the leftovers.
 */
@Composable
fun VAppGrid(
    shortcuts: List<AppShortcut>,
    containerName: String,
    onLaunch: (AppShortcut) -> Unit,
    onOpenProfile: (AppShortcut) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = GRID_COLUMNS,
) {
    // The Add cell is a member of the list for layout purposes, so a container
    // with three programs gets one full row rather than a row of three and an
    // orphan.
    val cells = shortcuts.size + 1
    val rows = (cells + columns - 1) / columns

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    when {
                        index < shortcuts.size -> VAppTile(
                            shortcut = shortcuts[index],
                            onLaunch = { onLaunch(shortcuts[index]) },
                            onOpenProfile = { onOpenProfile(shortcuts[index]) },
                            modifier = Modifier.weight(1f),
                        )

                        index == shortcuts.size -> VAddAppTile(
                            onClick = onAdd,
                            containerName = containerName,
                            modifier = Modifier.weight(1f),
                        )

                        // A spacer, not a tile. It draws nothing and holds a
                        // column open.
                        else -> Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Four across at 421 dp is a 95 dp cell, which is a 44 dp icon with room to label it. */
private const val GRID_COLUMNS = 4

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421)
@Composable
private fun VAppGridPreview() {
    VesselTheme {
        VAppGrid(
            shortcuts = listOf(
                AppShortcut("1", "c", "C:\\npp\\notepad++.exe", "Notepad++", PeArchitecture.ARM64),
                AppShortcut("2", "c", "C:\\winamp\\winamp.exe", "Winamp", PeArchitecture.X86),
                AppShortcut("3", "c", "C:\\stalker\\xr.exe", "Stalker", PeArchitecture.X64),
            ),
            containerName = "Display proof",
            onLaunch = {},
            onOpenProfile = {},
            onAdd = {},
            modifier = Modifier.padding(Vessel.metrics.s11),
        )
    }
}
