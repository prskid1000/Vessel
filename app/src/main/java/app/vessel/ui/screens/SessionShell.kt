package app.vessel.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.vessel.core.FrameRate
import app.vessel.core.GuestViewport
import app.vessel.core.PeArchitecture
import app.vessel.core.WindowBounds
import app.vessel.ui.components.VAppGrid
import app.vessel.ui.components.VAppIcon
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VRule
import app.vessel.ui.components.rememberBuiltInIcon
import app.vessel.ui.components.rememberProgramIcon
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestWindow
import app.vessel.ui.shell.TerminalOption
import app.vessel.ui.shell.TerminalProfile
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import app.vessel.ui.theme.vRuleAbove
import kotlin.math.roundToInt

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
    onStart: () -> Unit,
    onFocusWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
    launcherOpen: Boolean = false,
    /**
     * The running container's programs, so a button can draw the real icon.
     *
     * A window knows its executable's *name* and not its path; a shortcut has
     * the path. Matching one to the other is what turns `notepad.exe` into an
     * icon, and it is the reason this list is here rather than in the bar's own
     * business. Empty is fine — every button falls back to its glyph or letter.
     */
    shortcuts: List<AppShortcut> = emptyList(),
    /** Which prefix to read a built-in program's icon out of. */
    containerId: String? = null,
    /** Composited frames per second, drawn at the trailing edge. */
    frameRate: FrameRate = FrameRate(),
    /**
     * Long press → the window's actions.
     *
     * **The panel is the caller's to draw, and that is not a style choice.** It
     * used to be a sheet owned by the button, and it could not be opened: the
     * bar hides itself on a timer, hiding disposes the button, and the disposal
     * took the `menuOpen` state with it — so a long press that outlived the
     * timer showed nothing at all. The launcher already solved this by living
     * above the bar rather than inside it and holding the timer open while it
     * is up. Same shape here.
     */
    onWindowMenu: (GuestWindow) -> Unit = {},
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
                TaskbarWindow(
                    window = window,
                    shortcuts = shortcuts,
                    containerId = containerId,
                    onClick = { onFocusWindow(window.id) },
                    onMenu = { onWindowMenu(window) },
                )
            }
        }

        FrameRateReadout(frameRate)

        // **Pause and Stop are not here.** They were, on the argument that the
        // taskbar is the surface already open when a session needs ending — but
        // the session rail has both, beside the graphs and the rest of the
        // session's own controls, and that is where a control that acts on the
        // *session* belongs. Two buttons in two places is two places to keep in
        // step and one more chance to tap Stop while reaching for a window.
        //
        // What is left is what a taskbar is: a start button, and the windows.
    }
}

/**
 * Frames per second, at the trailing edge of the taskbar.
 *
 * **A history and a number, because one of them without the other is not worth
 * the space.** A bare number tells you what is happening this half-second and
 * nothing about whether it just dropped; a graph alone cannot be read at a
 * glance while you are playing something. Twenty seconds of bars behind a mono
 * figure answers both in one look, which is the whole job of a HUD.
 *
 * **Coloured against the container's own limit, not against 60.** A container
 * configured for 30 fps and delivering 30 is doing exactly what was asked, and
 * an amber readout for missing a number nobody set would be a traffic light
 * that lies. [FrameRate.target] is that limit, or 60 when there is none.
 *
 * **Idle is a dash, not a zero.** Vessel composites on damage, so a desktop with
 * nothing moving genuinely produces no frames — that is the absence of a
 * reading rather than a stall, and a red `0` flashing at somebody who simply is
 * not doing anything would be the counter misrepresenting the one thing it
 * exists to report. The bars stay, greyed, so the shape of what just happened
 * does not vanish the moment a program stops drawing.
 */
@Composable
private fun FrameRateReadout(rate: FrameRate, modifier: Modifier = Modifier) {
    val colors = Vessel.colors
    val metrics = Vessel.metrics

    // Rounded once, here, and used for both the digits and the colour, so the
    // number on screen can never disagree with the colour behind it — 59.6
    // drawn as "60" beside an amber bar is the kind of detail that makes a
    // readout feel broken without anybody being able to say why.
    val shown = rate.fps.roundToInt()
    val ink = when {
        rate.idle -> colors.textMuted
        shown >= rate.target * SMOOTH -> colors.ok
        shown >= rate.target * ROUGH -> colors.warn
        else -> colors.danger
    }

    Row(
        modifier
            .background(colors.surfaceSunken, metrics.shapeSm)
            .padding(horizontal = metrics.s6, vertical = metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(metrics.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrameRateBars(rate, ink)
        Text(
            if (rate.idle) "—" else shown.toString(),
            style = Vessel.type.mono,
            color = ink,
        )
        Text("FPS", style = Vessel.type.overline, color = colors.textMuted)
    }
}

/**
 * The last [FrameRate.HISTORY] samples as bars, newest at the right.
 *
 * Scaled to the target rather than to the highest sample. Auto-scaling would
 * make a run at 12 fps look identical to a run at 60 — the bars would fill the
 * box either way — which is exactly the reassurance a performance readout must
 * not give. A sample above the target is clamped to full height instead, so the
 * chart says "at or over the limit" and does not rescale everything else to
 * accommodate one spike.
 */
@Composable
private fun FrameRateBars(rate: FrameRate, ink: Color, modifier: Modifier = Modifier) {
    val metrics = Vessel.metrics
    val track = Vessel.colors.divider
    val target = rate.target.toFloat().coerceAtLeast(1f)

    Canvas(
        modifier
            .size(width = metrics.sparkWidth, height = metrics.sparkHeight)
            .padding(vertical = metrics.s3),
    ) {
        val slots = FrameRate.HISTORY
        val slot = size.width / slots
        val barWidth = (slot - metrics.hairline.toPx()).coerceAtLeast(1f)
        // Right-aligned: a partly filled history grows leftwards from the newest
        // sample, so the most recent bar is always in the same place and the eye
        // does not have to hunt for it as the buffer fills.
        val samples = rate.history.takeLast(slots)
        val offset = slots - samples.size

        samples.forEachIndexed { index, sample ->
            val fraction = (sample / target).coerceIn(0f, 1f)
            val x = (offset + index) * slot
            // Every slot gets a floor of one pixel so a run of zeroes reads as a
            // measured nothing rather than as an empty chart with no data in it.
            val height = (size.height * fraction).coerceAtLeast(metrics.hairline.toPx())
            drawRect(
                color = if (sample <= 0f) track else ink,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
}

/** At or above this fraction of the target, the readout is green. */
private const val SMOOTH = 0.9f

/** Below this fraction it is red; between the two, amber. */
private const val ROUGH = 0.5f

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

/**
 * One open guest window, as a square button and nothing else.
 *
 * **The title is gone from the face of it.** A window's `_NET_WM_NAME` is
 * whatever the program felt like putting in its caption, and Wine's answer for a
 * console is the full path — `C:\windows\system32\cmd.exe`, which took a third
 * of the bar for one window and would take all of it for three. Every desktop
 * that has ever run out of taskbar collapses to icons, and this one starts with
 * no width to spare.
 *
 * The title is still the click label and the content description, so the button
 * is not anonymous to a screen reader or to a long press — it is anonymous only
 * to the eye, which has the window itself to look at.
 *
 * Three marks, in order of how much they know: the program's **own icon** when
 * the window can be matched to one of the container's shortcuts, a **glyph** for
 * the handful of programs Vessel itself launches, and the window's **initial**
 * when neither applies. A letter is a poor icon and an honest one — a generic
 * application glyph would make four unrelated programs look like one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskbarWindow(
    window: GuestWindow,
    shortcuts: List<AppShortcut>,
    containerId: String?,
    onClick: () -> Unit,
    onMenu: () -> Unit = {},
) {
    val shape = Vessel.metrics.shapeMd
    Box(
        Modifier
            .size(Vessel.metrics.touchTarget)
            .background(
                if (window.focused) Vessel.colors.accentHover else Vessel.colors.bg.copy(alpha = 0f),
                shape,
            )
            .vRing(if (window.focused) Vessel.colors.accent else Vessel.colors.divider, shape)
            // **A hidden window looks hidden.** It is still in the bar because
            // that is the only way back to it, so it has to be tellable from the
            // ones that are on screen — otherwise the bar claims six windows are
            // open when two of them are not.
            .alpha(if (window.minimized) Vessel.colors.disabledAlpha else 1f)
            // **Tap focuses, long press offers to close.** Tapping is the
            // thing done constantly and must stay one gesture with no menu in
            // front of it; closing is done once per program and is destructive
            // enough to be worth reaching for. The same split every desktop
            // taskbar uses, and the reason there is no X on the button: at 44 dp
            // with an icon in it there is no room for a second target a finger
            // could hit reliably, and a mis-hit would close the program.
            .combinedClickable(
                onClickLabel = window.title,
                onClick = onClick,
                onLongClickLabel = "Actions for ${window.title}",
                onLongClick = onMenu,
            )
            .semantics { contentDescription = window.title },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (window.focused) Vessel.colors.textPrimary else Vessel.colors.textLabel

        // The window's program name against each shortcut's file name. A window
        // carries `notepad.exe`; a shortcut carries `C:\windows\notepad.exe`.
        // Case-insensitively, because Windows paths are.
        val owner = remember(window.program, shortcuts) {
            shortcuts.firstOrNull {
                it.executable.substringAfterLast('\\').equals(window.program, ignoreCase = true)
            }
        }
        // **Two lookups, because a window can come from either list.** Matching
        // shortcuts alone left regedit and winecfg drawing a letter: they are
        // opened from the built-in row and are not shortcuts, so nothing owned
        // them. The built-in lookup finds any program Wine provides, by name.
        val owned = rememberProgramIcon(owner)
        val builtIn = rememberBuiltInIcon(containerId, window.program)
        val icon = owned ?: builtIn
        val glyph = windowGlyph(window.program)

        when {
            icon != null -> Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(Vessel.metrics.iconMd),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
            )

            glyph != null ->
                Icon(glyph, contentDescription = null, Modifier.size(Vessel.metrics.iconMd), tint = tint)

            else -> Text(window.initial, style = Vessel.type.mono, color = tint)
        }

    }
}

/**
 * What can be done to one guest window, and what each of them costs.
 *
 * Two verbs, and the difference between them is the whole content of this
 * sheet. **Close** is `WM_CLOSE` — the program is asked, and gets to put up its
 * "save changes?" dialog, so it is the one to reach for and the one drawn first.
 * **Force close** is `SIGKILL` to the process: no dialog, no save, and it is
 * described in those words rather than as "not responding", because whether the
 * program is responding is not something this shell can know.
 *
 * Force close is offered unconditionally rather than only after Close fails. A
 * program wedged badly enough to need it is exactly the program that will not
 * answer the polite request either, and making the user perform a ritual of
 * asking nicely first — then wait, then guess how long — is worse than trusting
 * them with a labelled button.
 */
@Composable
fun WindowActionsPanel(
    window: GuestWindow,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onKill: () -> Unit,
    onResize: () -> Unit,
    resizing: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapeLg
    Column(
        modifier
            // **No width of its own.** Three buttons and a title are the whole
            // panel, so it is as wide as they are. It used to carry a sentence
            // explaining Close against Force close, and that sentence — not the
            // controls — was setting the width: a paragraph-wide slab over the
            // guest for three 44 dp targets.
            .vElevation(VElev.lg, shape)
            .background(Vessel.colors.surface, shape)
            .vRing(VElev.lg.ring, shape)
            // Swallow taps, so a press inside the panel does not reach the
            // scrim that closes it. Same as the launcher's.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(Vessel.metrics.s17),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        // The window's own title, which is the only string the user recognises.
        // A window that has not set one falls back to the program, and to a
        // generic word only if it has neither — never to an empty header.
        Text(
            window.title.ifBlank { window.program.ifBlank { "This window" } },
            style = Vessel.type.title,
            color = Vessel.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
            // Non-destructive first and Restore rather than Minimize when the
            // window is already hidden: one button, saying what it will do.
            WindowAction(
                icon = if (window.minimized) VIcons.ArrowClockwise else VIcons.CaretDown,
                label = if (window.minimized) "Restore" else "Minimize",
                onClick = onMinimize,
            )
            // **The only way to move or size a window.** `patches/wine/0010`
            // removes the caption and the sizing border from every top-level
            // window — both are unhittable on a phone, and the caption alone
            // cost 41 rows nothing painted — so this puts handles on screen for
            // as long as they are wanted and takes them away again. Hidden
            // while the window is minimised, where there is nothing to drag.
            if (!window.minimized) {
                WindowAction(
                    icon = VIcons.CornersOut,
                    label = if (resizing) "Done" else "Resize",
                    tint = if (resizing) Vessel.colors.accent else Vessel.colors.textPrimary,
                    onClick = onResize,
                )
            }
            WindowAction(icon = VIcons.X, label = "Close", onClick = onClose)
            // Destructive, and drawn as such. Offered unconditionally rather
            // than only after Close fails, because the case it exists for is a
            // program that is not reading its message queue — which is exactly
            // the case where Close returns nothing and says nothing.
            WindowAction(
                icon = VIcons.Trash,
                label = "Force close",
                tint = Vessel.colors.danger,
                onClick = onKill,
            )
        }
    }
}

/** One icon button in [WindowActionsPanel], with its label under it. */
@Composable
private fun WindowAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Vessel.colors.textPrimary,
) {
    val shape = Vessel.metrics.shapeMd
    Column(
        Modifier
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        Box(
            Modifier
                .size(Vessel.metrics.touchTarget)
                .background(Vessel.colors.surfaceRaised, shape)
                .vRing(Vessel.colors.divider, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(Vessel.metrics.iconMd), tint = tint)
        }
        Text(label, style = Vessel.type.label, color = Vessel.colors.textLabel, maxLines = 1)
    }
}

/**
 * The mark for a window, chosen by the program that owns it.
 *
 * **From `WM_CLASS`, not from the file.** The obvious answer is the program's
 * real icon, and `PeIconReader` can extract one — but a window knows its
 * executable's *name*, not its path, so drawing the real icon means resolving
 * that name back to a file on the guest's `C:` for every window that appears.
 * These four cover everything Vessel itself launches, at no cost and with no
 * lookup, and they are the same glyphs the launcher uses for the same programs —
 * so the button in the bar matches the button that opened it.
 *
 * Null for anything else, and the caller falls back to the window's initial. A
 * letter is a poor icon and an honest one; a generic application glyph would
 * make four unrelated programs look like the same program.
 */
@Composable
private fun windowGlyph(program: String): ImageVector? = when (program) {
    "conhost.exe", "cmd.exe" -> VIcons.Terminal
    "pwsh.exe", "powershell.exe" -> VIcons.Prompt
    "wscript.exe", "cscript.exe" -> VIcons.Code
    "explorer.exe" -> VIcons.Folder
    else -> null
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
    terminals: List<TerminalOption> = emptyList(),
    onTerminal: (TerminalProfile) -> Unit = {},
    /** Which prefix to read the built-in programs' icons out of. */
    containerId: String? = null,
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
            .padding(Vessel.metrics.s17)
            // **Scrolls, because the panel is taller than the space above the
            // taskbar and was being cut off.** Six programs put the rule under
            // the grid at the very bottom edge of the panel and everything below
            // it — the terminal profiles, Browse C: — outside it, drawn and
            // clipped. It read as a panel that simply ended there, which is the
            // worst version: nothing looked broken, the rows were just missing.
            // The grid inside is a plain Column of Rows rather than a lazy grid,
            // so nesting a scroll here is legal.
            .verticalScroll(rememberScrollState()),
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

        // **Wrapped five to a row, not one row that scrolls.**
        //
        // A scrolling strip was right for four actions and wrong for thirteen:
        // everything past the fifth was off-screen behind a gesture nothing on
        // the panel suggests, so two thirds of the list was invisible unless you
        // guessed it was there. A grid shows all of it at once, and the panel is
        // already the tallest thing on the desktop — the cost is a few dp.
        //
        // Weighted cells and blank spacers on the last row, the same shape
        // [VAppGrid] uses above, so a row of three keeps its column width
        // instead of stretching three buttons across the panel.
        terminals.chunked(BUILT_IN_COLUMNS).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { option ->
                    LauncherAction(
                        // The glyph is the fallback now, not the answer. These
                        // are Wine's own programs and they carry their own
                        // icons; the stand-ins were a giveaway that no good one
                        // existed — a bulleted-list mark for the *registry
                        // editor*.
                        icon = when (option.profile) {
                            TerminalProfile.COMMAND_PROMPT -> VIcons.Terminal
                            TerminalProfile.WINE_EXPLORER -> VIcons.FolderOpen
                            TerminalProfile.REGEDIT -> VIcons.List
                            TerminalProfile.WINECFG -> VIcons.Monitor
                            TerminalProfile.TASK_MANAGER -> VIcons.List
                            // Everything else is a document-shaped program, and
                            // the glyph is only what shows for the moment before
                            // its real icon arrives.
                            else -> VIcons.File
                        },
                        bitmap = rememberBuiltInIcon(containerId, option.profile.program),
                        caption = option.profile.shortLabel,
                        description = option.unavailable ?: "Open ${option.profile.label}",
                        enabled = option.enabled,
                        onClick = { onTerminal(option.profile) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(BUILT_IN_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        // **Vessel's own C: browser is not here, and that is deliberate.**
        // Wine's File Explorer above it already lists the container's drives,
        // and two file managers side by side in one menu is two answers to one
        // question. Ours is still reachable from the container's card on the
        // home screen, which is where getting a file *in* belongs — it is the
        // only one of the two that can import from Android storage or add a
        // program as a shortcut.
    }
}

/**
 * Six across for the built-in row.
 *
 * The tile grid above is four, and these are deliberately narrower: no
 * architecture badge, no program name to fit, just a mark and a command. Six in
 * a 420 dp panel is a 59 dp cell, which holds a 30 dp icon and the longest
 * caption in the list — `iexplore` — at the panel's widened size.
 */
private const val BUILT_IN_COLUMNS = 6

/**
 * A square action in the launcher's bottom row: a glyph over three characters.
 *
 * The caption is not decoration — three terminal glyphs in a row are three
 * identical buttons, and `cmd` against `pwsh` is the whole difference between
 * them. It is `monoSmall`, which is what this product uses everywhere a literal
 * command name appears.
 *
 * A disabled one stays in the row at [VColors.disabledAlpha] rather than being
 * dropped, and its reason moves to the content description: the row answers
 * "can Vessel open a PowerShell" by having a PowerShell button in it, and the
 * sentence explaining why it is dim is worth a long press, not a line of prose
 * under every entry.
 */
@Composable
private fun LauncherAction(
    icon: ImageVector,
    caption: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The program's own icon, when it has one.
     *
     * [icon] stays the fallback rather than being replaced, because it is what
     * shows while the PE is being read and what shows for a container that is
     * not running yet.
     */
    bitmap: ImageBitmap? = null,
) {
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha
    Column(
        modifier
            .vRing(Vessel.colors.divider, Vessel.metrics.shapeMd)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .padding(vertical = Vessel.metrics.s8, horizontal = Vessel.metrics.s3)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(Vessel.metrics.iconMd).alpha(alpha),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(Vessel.metrics.iconMd),
                tint = Vessel.colors.textMuted.copy(alpha = Vessel.colors.textMuted.alpha * alpha),
            )
        }
        Text(
            caption,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted.copy(alpha = Vessel.colors.textMuted.alpha * alpha),
            // Six to a row leaves no slack: a caption that wrapped would make
            // one cell taller than its neighbours and step the whole row.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
    // **Beside Android's gesture pill, not above it.** The mark used to sit
    // centred and one inset higher, which put two horizontal bars on the same
    // edge, stacked, a few pixels apart — the second one reading as a glitch in
    // the first. Sharing the navigation bar's band and taking the left of it
    // makes them one row of two marks: the system's in the middle where it
    // always is, ours at the edge it belongs to.
    //
    // The band's own height, so "same level" is measured rather than nudged. A
    // device with no gesture inset reports zero, and the touch box would vanish
    // with it, so it never shrinks below the target size.
    val band = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(maxOf(band, Vessel.metrics.railHandleTouch))
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, delta -> if (delta < 0f) onReveal() }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Show the taskbar",
                onClick = onReveal,
            ),
        // Centred in the band, which is where the system centres its own pill,
        // so the two line up without either knowing about the other.
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                // Clear of the edge rather than against it. At the screen gutter
                // the bar started where the rounded corner of the display is
                // still curving away, which reads as a bar running off the side
                // rather than one placed at it.
                .padding(start = Vessel.metrics.s22)
                // Square, like the rail's handle, and deliberately not a pill.
                // Android draws its own gesture pill on this edge at very nearly
                // this width — a rounded bar beside it reads as a second system
                // control rather than as ours.
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
            onStart = {},
            onFocusWindow = {},
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

/**
 * Temporary drag handles around one guest window.
 *
 * **The whole of window management in this product, and it is deliberately not
 * always on.** `patches/wine/0010` strips `WS_CAPTION` and `WS_THICKFRAME` from
 * every top-level window, because on a phone a Win32 caption is far too small
 * to hit and the one measured here cost 41 of 720 rows to a strip nothing ever
 * painted. That leaves nothing to grab — so the taskbar menu turns these on for
 * as long as they are wanted, and off again from the same button.
 *
 * The mapping: [viewport] converts guest pixels to view pixels, and it is the
 * renderer's own fit rather than a second copy of it. Everything below is drawn
 * in view pixels and every drag is converted back before it is sent, so the
 * handles stay the same physical size whatever the desktop resolution is —
 * a handle that scaled with the guest would be untouchable at 640x360.
 *
 * **The top edge moves, the other three and the corners resize.** The body is
 * left alone entirely: turning this on mid-game must not take the pointer away
 * from the game, and a mode that swallowed the whole window would do exactly
 * that.
 */
@Composable
fun WindowDragBorders(
    window: GuestWindow,
    viewport: GuestViewport,
    onMoveResize: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (viewport.scale <= 0f) return

    val density = LocalDensity.current

    /**
     * **A full touch target, and placed *inside* the window rather than astride
     * its edge.** 17 dp centred on the edge was the first attempt and it was
     * unusable on a phone: it gives 8 dp either side, well under Android's 48 dp
     * minimum, and for a window flush against the top of the screen half of that
     * is off-screen entirely — so the one window most likely to need moving was
     * the hardest to grab. Inside means the whole band is always reachable, at
     * the cost of overlapping the guest's own top rows while the mode is on,
     * which is a trade the mode is explicitly for.
     */
    val grab = Vessel.metrics.touchTarget

    // The live rectangle, in guest pixels. Seeded from the window and then owned
    // by the gesture: the X server republishes asynchronously, so reading the
    // window back every frame would make the handles stutter a frame behind the
    // finger. It resyncs whenever the window really changes underneath.
    var rect by remember(window.id) { mutableStateOf(window.bounds) }
    LaunchedEffect(window.bounds) { rect = window.bounds }

    val left = with(density) { viewport.viewX(rect.x).toDp() }
    val top = with(density) { viewport.viewY(rect.y).toDp() }
    val width = with(density) { (rect.width * viewport.scale).toDp() }
    val height = with(density) { (rect.height * viewport.scale).toDp() }

    /**
     * One edge or corner. [apply] gets the drag in guest pixels.
     *
     * Drawn, not just hit-tested. An invisible handle is a handle you find by
     * trial and error, so each band carries a translucent accent wash and the
     * corner a stronger one — enough to aim at, transparent enough to see the
     * program underneath while dragging it.
     */
    @Composable
    fun handle(
        alignX: Dp,
        alignY: Dp,
        w: Dp,
        h: Dp,
        strong: Boolean = false,
        apply: (dx: Int, dy: Int) -> WindowBounds,
    ) {
        Box(
            Modifier
                .offset(alignX, alignY)
                .size(w, h)
                .background(
                    Vessel.colors.accent.copy(alpha = if (strong) 0.45f else 0.22f),
                    Vessel.metrics.shapeSm,
                )
                .pointerInput(window.id, viewport) {
                    detectDragGestures(
                        onDragEnd = {
                            // The authoritative call. Every move during the drag
                            // already went out, so this is the one that matters
                            // only if the last frame was coalesced away.
                            onMoveResize(rect.x, rect.y, rect.width, rect.height)
                        },
                    ) { change, drag ->
                        change.consume()
                        rect = apply(viewport.toGuest(drag.x), viewport.toGuest(drag.y))
                        onMoveResize(rect.x, rect.y, rect.width, rect.height)
                    }
                },
        )
    }

    Box(modifier.fillMaxSize()) {
        // The frame itself: an accent outline so it is obvious which window is
        // in the mode, and obvious that the mode is on at all.
        Box(
            Modifier
                .offset(left, top)
                .size(width, height)
                .vRing(Vessel.colors.accent, Vessel.metrics.shapeSm),
        )

        // **Every band sits inside its edge**, so a window flush against the
        // screen edge still offers the whole 44 dp. The vertical bands are
        // inset by `grab` at both ends so they cannot cover the corner, which
        // is declared last and must win the hit test.
        //
        // Move — the top strip only. The body is left to the guest, so turning
        // the mode on during a game does not take the pointer away from it.
        handle(left, top, width, grab) { dx, dy ->
            rect.copy(x = rect.x + dx, y = rect.y + dy)
        }
        // Resize — bottom edge.
        handle(left, top + height - grab, width - grab, grab) { _, dy ->
            rect.copy(height = (rect.height + dy).coerceAtLeast(MIN_DRAG_PX))
        }
        // Resize — left edge. Moves the origin and changes the width by the
        // opposite amount, or the window would slide instead of stretch.
        handle(left, top + grab, grab, height - grab * 2) { dx, _ ->
            val w2 = (rect.width - dx).coerceAtLeast(MIN_DRAG_PX)
            rect.copy(x = rect.x + (rect.width - w2), width = w2)
        }
        // Resize — right edge.
        handle(left + width - grab, top + grab, grab, height - grab * 2) { dx, _ ->
            rect.copy(width = (rect.width + dx).coerceAtLeast(MIN_DRAG_PX))
        }
        // Resize — the bottom-right corner, both axes at once. Declared last so
        // it is hit-tested first, and drawn stronger because it is the one a
        // thumb reaches for.
        handle(
            left + width - grab, top + height - grab, grab, grab, strong = true,
        ) { dx, dy ->
            rect.copy(
                width = (rect.width + dx).coerceAtLeast(MIN_DRAG_PX),
                height = (rect.height + dy).coerceAtLeast(MIN_DRAG_PX),
            )
        }
    }
}

/**
 * The smallest a drag may leave a window, in guest pixels.
 *
 * Mirrors `XServerDisplay.MIN_WINDOW_PX`, which is the one that is enforced —
 * this is here so the handles stop moving at the same place the server stops
 * accepting, rather than the rectangle running on under a window that has
 * stopped shrinking.
 */
private const val MIN_DRAG_PX = 96
