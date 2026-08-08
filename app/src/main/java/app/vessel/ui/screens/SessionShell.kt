package app.vessel.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.PeArchitecture
import app.vessel.ui.components.VAppGrid
import app.vessel.ui.components.VAppIcon
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSheetRow
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestWindow
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import app.vessel.ui.theme.vRuleAbove

/**
 * The Vessel shell — a taskbar and a launcher, drawn in Compose over the GL
 * surface rather than inside Wine.
 *
 * That decision is settled and worth restating, because it looks like the wrong
 * one at first glance. Launching a program into a running desktop is already
 * proven from the Android side; theme, fonts and 44 dp touch targets come free;
 * and a Win32 shell would mean owner-drawing a whole UI toolkit to avoid using
 * the one already here.
 *
 * **Two constraints, both found on the device, and both shape what is below.**
 *
 * An Android overlay *always* covers a fullscreen Windows application — there is
 * no z-order in which a Compose layer sits under the guest's output. So the
 * taskbar auto-hides and is revealed by an edge gesture, exactly as the rail is.
 * Neither is ever simply "on".
 *
 * **Tray icons cannot work.** Receiving one needs a helper process inside the
 * guest, which this project deliberately does not ship. A program that minimises
 * to tray vanishes from [SessionTaskbar] rather than docking in it, and the bar
 * says so in words where a user would go looking for the icon — an empty corner
 * would read as a bug in the bar.
 */

/**
 * The taskbar: what Vessel launched, and the two controls that end it.
 *
 * It shows guest windows, not shortcuts. The launcher above it shows shortcuts.
 * Those are two different lists and conflating them is the mistake this layout
 * exists to avoid: one is "what is running", the other is "what could run".
 */
@Composable
fun SessionTaskbar(
    windows: List<GuestWindow>,
    paused: Boolean,
    unavailableReason: String?,
    onStart: () -> Unit,
    onFocusWindow: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    launcherOpen: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Vessel.colors.surfaceFloating)
            .vRuleAbove(Vessel.colors.divider)
            .navigationBarsPadding()
            .padding(
                horizontal = Vessel.metrics.s11,
                vertical = Vessel.metrics.s6,
            ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StartButton(open = launcherOpen, onClick = onStart)

        Box(
            Modifier
                .size(
                    width = Vessel.metrics.hairline,
                    height = Vessel.metrics.taskbarDividerHeight,
                )
                .background(Vessel.colors.divider),
        )

        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **An empty bar when nothing is open, and no sentence explaining it.**
            // There were two paragraphs here — one about the system tray needing a
            // helper process, one carrying the shell's unavailable reason — and
            // together they filled the whole strip with 13 sp prose that ellipsised
            // mid-word on a phone. A taskbar with no buttons on it is a taskbar with
            // nothing open, which every user of a desktop already knows how to read.
            // The unavailable reason still has a home: the launcher panel prints it
            // above Browse C:, where there is room for a sentence.
            windows.forEach { window ->
                TaskbarWindow(window) { onFocusWindow(window.id) }
            }
        }

        // Repeated from the rail on purpose: the taskbar is the surface that is
        // already open when a session needs ending.
        VIconAction(
            icon = if (paused) VIcons.Play else VIcons.Pause,
            contentDescription = if (paused) "Resume the session" else "Pause the session",
            onClick = onTogglePause,
            style = if (paused) VButtonStyle.Primary else VButtonStyle.Secondary,
            size = Vessel.metrics.touchTarget,
        )
        VIconAction(
            icon = VIcons.X,
            contentDescription = "Stop the session",
            onClick = onStop,
            style = VButtonStyle.Danger,
            size = Vessel.metrics.touchTarget,
        )
    }
}

/**
 * The start button — four dots, and not a logo.
 *
 * A 2×2 grid of accent squares rather than a glyph, because this is the one
 * control on screen with no Windows equivalent to borrow from and no name to
 * write on it. It reads as "everything" without borrowing anybody's mark.
 */
@Composable
private fun StartButton(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Vessel.metrics.touchTarget)
            .background(
                if (open) Vessel.colors.accentPressed else Vessel.colors.accentHover,
                Vessel.metrics.shapeMd,
            )
            .vRing(Vessel.colors.accent, Vessel.metrics.shapeMd)
            .clickable(onClickLabel = "Programs", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.hairGap)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.hairGap)) {
                    repeat(2) {
                        Box(Modifier.size(Vessel.metrics.s6).background(Vessel.colors.accent))
                    }
                }
            }
        }
    }
}

/** One open guest window: its icon letter and its `WM_NAME`. */
@Composable
private fun TaskbarWindow(window: GuestWindow, onClick: () -> Unit) {
    val shape = Vessel.metrics.shapeMd
    Row(
        Modifier
            .heightIn(min = Vessel.metrics.touchTarget)
            .background(
                if (window.focused) Vessel.colors.accentHover else Vessel.colors.bg.copy(alpha = 0f),
                shape,
            )
            .vRing(if (window.focused) Vessel.colors.accent else Vessel.colors.divider, shape)
            .clickable(onClickLabel = window.title, onClick = onClick)
            .padding(start = Vessel.metrics.s6, end = Vessel.metrics.s11),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Vessel.metrics.taskbarIcon)
                .vRing(Vessel.colors.neutral800, Vessel.metrics.shapeSm),
            contentAlignment = Alignment.Center,
        ) {
            Text(window.initial, style = Vessel.type.mono, color = Vessel.colors.textLabel)
        }
        Text(
            window.title,
            style = Vessel.type.control,
            color = if (window.focused) Vessel.colors.textPrimary else Vessel.colors.textLabel,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The launcher — the same tiles as home, scoped to the container that is running.
 *
 * **One concept drawn once.** The home screen's grid and this are the same
 * component at the same 44 dp; the only difference is which container's programs
 * are in it. Two designs for one list is how a product ends up calling the same
 * thing two names.
 *
 * It anchors to the start button rather than filling the screen, because
 * launching a second program is not a reason to hide the first.
 */
@Composable
fun SessionLauncher(
    containerName: String,
    shortcuts: List<AppShortcut>,
    unavailableReason: String?,
    query: String,
    onQuery: (String) -> Unit,
    onLaunch: (AppShortcut) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapeLg
    val matching = remember(shortcuts, query) {
        if (query.isBlank()) shortcuts else shortcuts.filter { it.name.contains(query, true) }
    }

    Column(
        modifier
            .widthIn(max = Vessel.metrics.launcherWidth)
            .vElevation(VElev.lg, shape)
            .background(Vessel.colors.surface, shape)
            .vRing(VElev.lg.ring, shape)
            // Swallow taps so a press inside the panel does not reach the scrim
            // that closes it.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(Vessel.metrics.s17),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        app.vessel.ui.components.VTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search programs",
        )

        Text(
            "IN ${containerName.uppercase()}",
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
        )

        if (shortcuts.isEmpty()) {
            Text(
                "No programs yet.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
            )
        } else {
            VAppGrid(
                shortcuts = matching,
                containerName = containerName,
                onLaunch = onLaunch,
                onOpenProfile = onLaunch,
                onAdd = onBrowse,
            )
        }

        // The one thing the launcher must say when it cannot do its job. It is
        // below the grid rather than instead of it, because the tiles are still
        // the right list — it is starting them that is not wired up.
        unavailableReason?.let {
            Text(it, style = Vessel.type.bodySmall, color = Vessel.colors.warn)
        }

        VRule(verticalMargin = Vessel.metrics.s3)

        VSheetRow(
            icon = VIcons.Folder,
            title = "Browse C:",
            help = null,
            onClick = onBrowse,
        )
    }
}

/**
 * The bottom edge, when the taskbar is hidden.
 *
 * A swipe up reveals it. The mark is the gesture bar's own width in the accent at
 * 55%, which is the same language the rail's left-edge handle speaks — two edges,
 * two gestures, one vocabulary.
 */
@Composable
fun BoxScope.TaskbarHandle(onReveal: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // **The inset goes on the touch box, not on the mark inside it.**
            // It used to sit on the mark, which asked a 4 dp bar to also reserve
            // the navigation bar's height inside a 20 dp parent. The child wanted
            // more room than the parent had, so it was pushed past the bottom of
            // its own box and clipped away entirely — which is why this edge had
            // no handle at all while the rail's left edge had a plainly visible
            // one. The bar was never missing from the code; it was laid out
            // off-screen.
            .navigationBarsPadding()
            .height(Vessel.metrics.railHandleTouch)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, delta -> if (delta < 0f) onReveal() }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Show the taskbar",
                onClick = onReveal,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                // Square, like the rail's handle, and deliberately not a pill.
                // Android draws its own gesture pill on this edge, centred, at
                // very nearly this width — a rounded bar here is read as the
                // system's and not as ours. Same colour, same thickness, same
                // proportion of its edge as the left handle; the corners are the
                // one thing that has to differ, because the system took that
                // shape first.
                .width(Vessel.metrics.edgeHandleLength)
                .height(Vessel.metrics.railHandle)
                .background(Vessel.colors.edgeHandle),
        )
    }
}

/** The taskbar's slide, and the only motion the shell has. */
@Composable
fun TaskbarTransition(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(Vessel.metrics.durationSheetMs)) { it },
        exit = slideOutVertically(tween(Vessel.metrics.durationSheetMs)) { it },
    ) {
        content()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF292B31, widthDp = 927, heightDp = 120)
@Composable
private fun SessionTaskbarPreview() {
    VesselTheme {
        SessionTaskbar(
            windows = listOf(
                GuestWindow(1, "Notepad++", focused = true),
                GuestWindow(2, "Stalker"),
            ),
            paused = false,
            unavailableReason = null,
            onStart = {},
            onFocusWindow = {},
            onTogglePause = {},
            onStop = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF292B31, widthDp = 460, heightDp = 420)
@Composable
private fun SessionLauncherPreview() {
    VesselTheme {
        SessionLauncher(
            containerName = "Display proof",
            shortcuts = listOf(
                AppShortcut("1", "c", "C:\\npp\\notepad++.exe", "Notepad++", PeArchitecture.ARM64),
                AppShortcut("2", "c", "C:\\winamp\\winamp.exe", "Winamp", PeArchitecture.X86),
                AppShortcut("3", "c", "C:\\stalker\\xr.exe", "Stalker", PeArchitecture.X64),
            ),
            unavailableReason = null,
            query = "",
            onQuery = {},
            onLaunch = {},
            onBrowse = {},
            modifier = Modifier.padding(Vessel.metrics.s11),
        )
    }
}
