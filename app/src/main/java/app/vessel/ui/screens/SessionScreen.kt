package app.vessel.ui.screens

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.DisplayGeometry
import app.vessel.core.FrameRate
import app.vessel.core.GuestViewport
import app.vessel.core.SessionDiagnosis
import app.vessel.data.ProvisionStatus
import app.vessel.data.ProvisionStep
import app.vessel.data.SessionMetricsState
import app.vessel.data.SessionPhase
import app.vessel.data.SessionState
import app.vessel.input.PointerMode
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDialogCard
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VOutcomeDialog
import app.vessel.ui.components.VOutcomeTone
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VStepRow
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestWindow
import app.vessel.ui.shell.TerminalOption
import app.vessel.ui.shell.TerminalProfile
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Hide Android's bars for as long as the desktop is on screen.
 *
 * **Not for the extra pixels — because the status bar was eating the title
 * bar.** A guest window opens at the desktop's top-left, Wine's caption is
 * therefore in the top few dozen pixels of the panel, and that strip belongs to
 * Android's status bar: a drag that starts there pulls down the notification
 * shade instead of moving the window. Measured, not assumed — the first attempt
 * to drag a console by its caption produced a screenshot of the shade. Move was
 * not broken; it was unreachable.
 *
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` rather than hiding them outright, so
 * the bars are still one deliberate edge swipe away. A desktop session is the
 * one screen in this app that should own the whole panel, and it is also the one
 * where the user still needs the clock and the battery on demand.
 *
 * Restored on dispose, which is what stops the rest of the app inheriting an
 * immersive window after backing out of a session.
 */
@Composable
private fun ImmersiveWhileRunning() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val restore = controller.systemBarsBehavior
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = restore
            }
        }
    }
}

/**
 * The session: a desktop, a shell over it, and two dialogs — and not a screen for
 * four of its five states.
 *
 * DESIGN.md, *Four of the five states are not a screen*: Preparing and Starting
 * are [SessionLaunchDialog] over whatever the user was already looking at, Failed
 * and a non-zero Exited are [SessionOutcomeDialog], a clean exit is nothing at
 * all, and only RUNNING gets a destination — [SessionDesktop]. The phase decides
 * which is on screen and [app.vessel.ui.VesselApp] is the one place that decides
 * it; nothing here navigates.
 *
 * The five states still get equal attention. Most designs for this draw only the
 * happy one, and the first real launch is then a black rectangle with no
 * explanation; on this device the other four are where the time is actually
 * spent.
 */

// — Preparing and Starting, as a dialog ----------------------------------------

/**
 * The provisioning checklist, over whatever is behind it.
 *
 * **A checklist is not a place.** Waiting for six rows to tick is not somewhere a
 * user navigated to, and the screen this used to be gave it a toolbar, a back
 * arrow and a full page of ground to say so anyway. As a dialog it sits over the
 * container the user tapped, which is the thing it is about.
 *
 * [onDismiss] hides the report; it does not cancel the launch. That distinction
 * is why Cancel is a button rather than the dismiss gesture: a container takes
 * minutes to provision on this phone, and a dialog that cannot be put down would
 * be a modal wait — while a back gesture that silently killed a provisioning
 * prefix would be much worse than either.
 *
 * Cancel is deliberately *not* behind a confirmation, unlike Stop: nothing inside
 * a container that has not started yet can lose work.
 */
@Composable
fun SessionLaunchDialog(
    state: SessionState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Open the log this launch is writing, while it is still writing it.
     *
     * **The one line under the checklist is not enough and reads worse than the
     * truth.** Provisioning runs `wineboot` and `regedit` before any X server
     * exists, so Wine loads no display driver and logs `Initialization of
     * winex11.drv failed`, `no driver could be loaded` and `The explorer process
     * failed to start` — three alarming lines about a boot that is going fine.
     * Whichever happens to be last is what the dialog showed, with nothing
     * around it to say so.
     *
     * The log has carried all of this from the first line — `wineboot.exe`,
     * `regedit`, `services.exe` are the top of the file — and until now there
     * was no way to reach it during a launch: the rail exists only over a
     * running session, and the container's history is behind the dialog. A
     * button here is the whole fix.
     */
    onOpenLogs: () -> Unit,
) {
    VDialogCard(onDismiss = onDismiss) {
        Text(
            state.containerName.ifBlank { "Session" },
            style = Vessel.type.subtitle,
            color = Vessel.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProvisionChecklist(state)
        Row(
            Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8, Alignment.End),
        ) {
            VButton("Cancel", onCancel, style = VButtonStyle.Danger)
            // Only once there is a file to open. `startedAt` is what names it,
            // and it is null until the runtime has opened one.
            if (state.startedAt != null) {
                VButton("Log", onOpenLogs, style = VButtonStyle.Secondary)
            }
            VButton("Hide", onDismiss, style = VButtonStyle.Secondary)
        }
    }
}

/**
 * A checklist, not a spinner.
 *
 * The point is attribution: when a launch fails, the row it failed on is the
 * whole diagnosis. A spinner turns six distinguishable failures into one.
 *
 * Capped and scrollable because it lives in a dialog — six rows each with a
 * detail line is taller than a landscape window, and a dialog that grows past the
 * screen puts its own buttons out of reach.
 */
@Composable
private fun ColumnScope.ProvisionChecklist(state: SessionState) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = Vessel.metrics.checklistMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        // The header is the tense, not the phase: after a run ends this list is a
        // record of what happened, and calling it "Preparing" under a failure
        // dialog reads as though it were still going.
        VSectionHeader(if (state.finished) "Progress" else "Preparing")
        state.steps.forEach { VStepRow(it) }
    }

    // **Outside the scroll, and that is the fix.** These two lived inside it, so
    // the container's max height fell across them and cut the last one through
    // the middle of its glyphs — text that is present, unreadable, and cannot be
    // scrolled to because the steps above had already used the height.
    //
    // DESIGN.md: Starting shows the last log line as it goes, "because that is
    // where a missing DLL surfaces". The intent is right and the literal reading
    // was wrong, because of what a *healthy* boot's tail looks like here:
    // provisioning runs `wineboot` and `regedit` before any X server exists, so
    // Wine loads no display driver and the last line is reliably one of
    // `Initialization of winex11.drv failed`, `no driver could be loaded`, or
    // `The explorer process failed to start`. Three sentences that read like a
    // broken boot, on every successful boot, with nothing around them to say so.
    //
    // So the tail is shown when a step has actually failed — which is the case
    // DESIGN.md was describing — and the checklist's own step details carry the
    // narration the rest of the time. Nothing is hidden: `Log` opens the whole
    // file, benign lines and all, while the launch is still running.
    val failed = state.steps.any { it.status == ProvisionStatus.FAILED }
    val line = state.lastLine?.takeIf { failed || state.finished }
    if (line != null) {
        Text(
            line,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state.phase == SessionPhase.STARTING) {
        Text(
            "Starting the Windows desktop at ${state.geometry}.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textLabel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// — Exited and Failed ----------------------------------------------------------

/**
 * Why the session ended — when there is a why.
 *
 * **A clean exit shows nothing and this composable is never called for one.**
 * "The Windows desktop closed · exit code 0" told the user the thing they had
 * just done and then made them tap Close to acknowledge it. Exit code 0 returns
 * silently to wherever they were.
 *
 * A non-zero exit and a FAILED launch still get it, and must: that is the
 * diagnosis, and swallowing it would leave a container that fell over
 * indistinguishable from one the user closed.
 */
@Composable
fun SessionOutcomeDialog(
    state: SessionState,
    onOpenLogs: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.phase == SessionPhase.FAILED) {
        FailedDialog(state, onOpenLogs, onRetry, onDismiss)
    } else {
        ExitedDialog(state, onOpenLogs, onDismiss)
    }
}

/** A non-zero exit only. The guest chose it, so it is not a Vessel failure. */
@Composable
private fun ExitedDialog(state: SessionState, onOpenLogs: () -> Unit, onDismiss: () -> Unit) {
    VOutcomeDialog(
        title = "The program ended with an error",
        tone = VOutcomeTone.Danger,
        detail = "The Windows desktop closed on its own. Nothing in Vessel stopped it.",
        evidence = listOfNotNull(state.lastError, "exit code ${state.exitCode}"),
        onDismiss = onDismiss,
        actions = {
            VButton("View log", onOpenLogs, style = VButtonStyle.Secondary)
            VButton("Close", onDismiss, style = VButtonStyle.Primary)
        },
    )
}

/**
 * DESIGN.md: the failing step, the last error line, and two actions.
 *
 * The headline is a [SessionDiagnosis] where one matched, because the raw line is
 * precise and unreadable — `err:virtual:map_image_into_view failed to set
 * 60000020 protection` is exactly true and tells almost nobody what happened. The
 * raw line stays underneath it in mono, since that is what a bug report quotes.
 */
@Composable
private fun FailedDialog(
    state: SessionState,
    onOpenLogs: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val step = state.failedStep
    VOutcomeDialog(
        title = state.diagnosis?.headline
            ?: state.failure
            ?: step?.label?.let { "$it did not finish" }
            ?: "The session stopped.",
        tone = VOutcomeTone.Danger,
        detail = state.diagnosis?.detail,
        evidence = listOfNotNull(
            step?.detail?.takeIf { it != state.failure },
            state.lastError,
            state.exitCode?.let { "exit code $it" },
        ),
        onDismiss = onDismiss,
        actions = {
            VButton("View log", onOpenLogs, style = VButtonStyle.Secondary)
            VButton("Retry", onRetry, style = VButtonStyle.Primary, icon = VIcons.ArrowClockwise)
        },
    )
}

// — Running --------------------------------------------------------------------

/**
 * The one destination the session has: a full-bleed surface with two overlays
 * that both auto-hide.
 *
 * **Two edges, two gestures, and they must not collide.** The rail comes in from
 * the *left* through a full-height 20 dp target with a 4 dp accent mark; the
 * taskbar comes up from the *bottom* and takes the gesture bar's own width as its
 * handle. An Android overlay always covers a fullscreen Windows application —
 * there is no z-order in which either sits under the guest's output — so both are
 * hidden by default and the taskbar hides itself again after a few seconds of no
 * touch.
 *
 * **The rail hugs.** It is sized by what is in it and centred against the
 * screen's vertical middle, so the amount of the desktop it hides is the amount
 * it needs.
 */
@Composable
fun SessionDesktop(
    state: SessionState,
    surface: View?,
    pointerMode: PointerMode,
    onOpenLogs: () -> Unit,
    onOpenFiles: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onTogglePointerMode: () -> Unit,
    onShowKeyboard: () -> Unit,
    onLaunchApp: (AppShortcut) -> Unit,
    onFocusWindow: (Int) -> Unit,
    onMinimizeWindow: (Int) -> Unit,
    onCloseWindow: (Int) -> Unit,
    onKillWindow: (Int) -> Unit,
    /** Move and resize, in guest pixels — what a drag border does. */
    onMoveResizeWindow: (id: Int, x: Int, y: Int, width: Int, height: Int) -> Unit = { _, _, _, _, _ -> },
    /** How guest pixels land on the surface, so the borders can be placed. */
    viewport: GuestViewport = GuestViewport(),
    windows: List<GuestWindow> = emptyList(),
    /** Composited frames per second, for the taskbar's readout. */
    frameRate: FrameRate = FrameRate(),
    shortcuts: List<AppShortcut> = emptyList(),
    terminals: List<TerminalOption> = emptyList(),
    onTerminal: (TerminalProfile) -> Unit = {},
    shellUnavailableReason: String? = null,
    /**
     * The sampler's window, collected only while the rail is open.
     *
     * Passed as the flow rather than as a collected value on purpose: collecting
     * is what raises the sample rate from 0.1 Hz to 1 Hz, so the collection has to
     * start and stop with the rail's own composition, not with this screen's.
     * Null in previews.
     */
    metrics: Flow<SessionMetricsState>? = null,
) {
    var railOpen by remember { mutableStateOf(false) }
    var taskbarOpen by remember { mutableStateOf(false) }
    var launcherOpen by remember { mutableStateOf(false) }
    var launcherQuery by remember { mutableStateOf("") }
    var confirmingStop by remember { mutableStateOf(false) }

    /** The window whose actions are showing, or null. Held here, not in the bar. */
    var windowMenu by remember { mutableStateOf<GuestWindow?>(null) }

    /**
     * The window wearing drag borders, or null. An id and not the window itself,
     * so the borders follow the live list as it is republished — a window whose
     * geometry the server has just changed must redraw its handles at the new
     * place, and holding the object would pin them to the old one.
     */
    var resizingWindowId by remember { mutableStateOf<Int?>(null) }

    // The taskbar puts itself away. An overlay over a fullscreen game that stays
    // up is an overlay covering the game, and there is no z-order that fixes it.
    // Keyed so any reveal or menu toggle restarts the clock.
    //
    // **`windowMenu` is in the condition for the same reason `launcherOpen` is,
    // and leaving it out was a bug that made the long-press menu unreachable.**
    // The menu used to be state inside the taskbar button. The timer would fire
    // while a finger was still down, hiding the bar, disposing the button, and
    // taking the menu's state with it — so the press appeared to do nothing.
    LaunchedEffect(taskbarOpen, launcherOpen, windowMenu) {
        if (taskbarOpen && !launcherOpen && windowMenu == null) {
            delay(TASKBAR_LINGER_MS)
            taskbarOpen = false
        }
    }

    // Back peels one layer at a time and then leaves. It used to *open* the rail
    // unconditionally, which made Back a trap: every press reopened it and
    // nothing could ever leave a running session. Leaving does not stop the
    // container — the foreground service owns it, not this composable.
    BackHandler(enabled = railOpen || taskbarOpen || launcherOpen || windowMenu != null) {
        when {
            windowMenu != null -> windowMenu = null
            launcherOpen -> launcherOpen = false
            taskbarOpen -> taskbarOpen = false
            else -> railOpen = false
        }
    }

    ImmersiveWhileRunning()

    Box(Modifier.fillMaxSize().background(Vessel.colors.bg)) {
        SessionSurface(state, surface)

        // **Before the rail, not after.** A Box stacks in declaration order and
        // hit-tests from the top down, so a full-size scrim declared last covers
        // the rail it is a scrim *for*: every button tap landed on it and closed
        // the rail instead of firing. It looked like four dead buttons.
        if (railOpen || launcherOpen) {
            // Invisible on purpose — the guest's output stays fully readable
            // while either is open.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        railOpen = false
                        launcherOpen = false
                    },
            )
        }

        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = railOpen,
                enter = expandHorizontally(tween(Vessel.metrics.durationStandardMs)),
                exit = shrinkHorizontally(tween(Vessel.metrics.durationStandardMs)),
            ) {
                SessionRail(
                    state = state,
                    pointerMode = pointerMode,
                    metrics = metrics,
                    onStop = { confirmingStop = true },
                    onTogglePause = onTogglePause,
                    onTogglePointerMode = onTogglePointerMode,
                    onShowKeyboard = onShowKeyboard,
                    onOpenFiles = onOpenFiles,
                    onOpenLogs = onOpenLogs,
                )
            }

            if (!railOpen) {
                // The edge handle. A full-height target, so it is reachable
                // without looking on a screen the user is not looking at.
                Box(
                    Modifier
                        .width(Vessel.metrics.railHandleTouch)
                        .fillMaxHeight()
                        // The mark goes at the bottom of this edge, not the
                        // middle, so it meets the taskbar's mark at the corner
                        // and the two read as one L rather than two unrelated
                        // bars. The inset keeps it clear of the navigation band
                        // that the horizontal one sits inside.
                        .navigationBarsPadding()
                        .clickable(onClickLabel = "Show the session rail") { railOpen = true },
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Box(
                        Modifier
                            // The same gap from the bottom that the taskbar's
                            // mark keeps from the left, so the corner is square
                            // — plus the bar's own height when the bar is up.
                            //
                            // Without that second term the two marks are the
                            // same 108 dp and the vertical one *looks* shorter,
                            // because its bottom third is behind the taskbar
                            // that is drawn over it. Measured, not guessed: 322
                            // device pixels of horizontal mark against 221 of
                            // visible vertical one.
                            .padding(
                                bottom = Vessel.metrics.s22 +
                                    if (taskbarOpen) Vessel.metrics.taskbarHeight else 0.dp,
                            )
                            // The same mark as the taskbar's, turned on its side:
                            // 4 dp thick and 108 dp long on both edges. It used to
                            // be a quarter of the screen's height, which on a 2780
                            // px phone is a 232 dp stripe down the side of a running
                            // desktop — far more of a mark than an edge hint needs,
                            // and nothing like the bar on the bottom edge it is
                            // supposed to rhyme with. The touch target is unchanged
                            // and still full height; only the drawn bar shrank.
                            .width(Vessel.metrics.railHandle)
                            .height(Vessel.metrics.edgeHandleLength)
                            .background(Vessel.colors.edgeHandle),
                    )
                }
            }
        }

        // The launcher, anchored above the start button rather than filling the
        // screen: launching a second program is not a reason to hide the first.
        if (launcherOpen) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(
                        start = Vessel.metrics.s11,
                        bottom = Vessel.metrics.touchTarget + Vessel.metrics.s22,
                        end = Vessel.metrics.s11,
                    ),
            ) {
                SessionLauncher(
                    containerName = state.containerName.ifBlank { "this container" },
                    containerId = state.containerId,
                    shortcuts = shortcuts,
                    terminals = terminals,
                    onTerminal = {
                        launcherOpen = false
                        onTerminal(it)
                    },
                    unavailableReason = shellUnavailableReason,
                    query = launcherQuery,
                    onQuery = { launcherQuery = it },
                    onLaunch = {
                        launcherOpen = false
                        onLaunchApp(it)
                    },
                    onBrowse = {
                        launcherOpen = false
                        onOpenFiles()
                    },
                )
            }
        }

        // **Above the guest and below the taskbar.** A handle the taskbar covered
        // would be unreachable for exactly the window docked under it, and the
        // borders have to be over the surface or they would be composited behind
        // the thing they are framing.
        resizingWindowId?.let { id ->
            val target = windows.firstOrNull { it.id == id && !it.minimized }
            // Gone, or minimised out from under the mode: drop it rather than
            // leaving handles floating over nothing.
            if (target == null) {
                LaunchedEffect(id) { resizingWindowId = null }
            } else {
                WindowDragBorders(
                    window = target,
                    viewport = viewport,
                    onMoveResize = { x, y, w, h -> onMoveResizeWindow(id, x, y, w, h) },
                )
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            TaskbarTransition(visible = taskbarOpen) {
                SessionTaskbar(
                    windows = windows,
                    launcherOpen = launcherOpen,
                    onStart = { launcherOpen = !launcherOpen },
                    onFocusWindow = onFocusWindow,
                    // The same list the launcher above draws. A window knows its
                    // executable's name; a shortcut knows its path, which is
                    // what an icon can be read out of. The container is for the
                    // other half — a window opened from the built-in row has no
                    // shortcut, and its icon is read out of the prefix by name.
                    shortcuts = shortcuts,
                    containerId = state.containerId,
                    frameRate = frameRate,
                    onWindowMenu = { windowMenu = it },
                )
            }
        }

        // The window's actions, anchored above its button — the launcher's
        // placement, because it is the same kind of thing: a panel over the
        // guest that the bar opened and the bar does not own.
        windowMenu?.let { target ->
            // **Declared before the panel, which is what makes it a scrim and
            // not a lid.** A Box hit-tests its children last-to-first, so a
            // full-size layer declared *after* the panel would swallow every
            // press meant for the buttons — the same trap the rail's scrim
            // documents above. Invisible: the panel already separates itself
            // with elevation and a ring, and a wash over a running game to
            // explain a three-button menu is not a trade worth making.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Dismiss",
                    ) { windowMenu = null },
            )

            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(
                        start = Vessel.metrics.s11,
                        bottom = Vessel.metrics.touchTarget + Vessel.metrics.s22,
                        end = Vessel.metrics.s11,
                    ),
            ) {
                WindowActionsPanel(
                    // Redrawn from the live list, so a window that minimises
                    // itself while the panel is up says Restore rather than
                    // going stale. Gone from the list means gone: the panel
                    // closes rather than acting on a window that has exited.
                    window = windows.firstOrNull { it.id == target.id } ?: target,
                    onMinimize = {
                        windowMenu = null
                        onMinimizeWindow(target.id)
                    },
                    // The same call the taskbar icon makes, which is the one
                    // that was already working: `focus` remaps a minimised
                    // window before raising it. Wiring Restore to `minimize`
                    // was the whole of the bug.
                    onRestore = {
                        windowMenu = null
                        onFocusWindow(target.id)
                    },
                    onClose = {
                        windowMenu = null
                        onCloseWindow(target.id)
                    },
                    onKill = {
                        windowMenu = null
                        onKillWindow(target.id)
                    },
                    // **The panel closes and the borders stay.** Leaving it up
                    // would cover the window being dragged with the menu that
                    // started the drag. Tapping the same button again is how it
                    // ends, which is why the menu remembers the mode rather than
                    // the borders owning it.
                    onResize = {
                        resizingWindowId = if (resizingWindowId == target.id) null else target.id
                        windowMenu = null
                    },
                    resizing = resizingWindowId == target.id,
                )
            }
        }

        if (!taskbarOpen) TaskbarHandle { taskbarOpen = true }
    }

    if (confirmingStop) {
        VConfirmSheet(
            title = "Stop ${state.containerName.ifBlank { "this session" }}?",
            message = "Every program running inside the container is closed. Unsaved work in " +
                "them is lost; the container itself and its files are not touched.",
            confirmLabel = "Stop",
            onConfirm = {
                confirmingStop = false
                onStop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

/** Four seconds is long enough to aim at a button and short enough not to be in the way. */
private const val TASKBAR_LINGER_MS = 4_000L

/**
 * One card, three bands: the session's own status, its telemetry, its tools.
 *
 * **It scrolls.** Four graphs, a header and six actions come to a little over
 * 400 dp, and a landscape window on this phone is 421 dp before the gesture
 * inset. That fits today and the margin is one design change wide, so the failure
 * mode of adding a row is a scrollbar rather than a Stop button below the fold.
 *
 * Nothing inside draws a surface of its own. Nesting a readout card inside the
 * rail card is a ringed box inside a ringed box, 8 dp apart, over a running
 * Windows desktop.
 */
@Composable
private fun SessionRail(
    state: SessionState,
    pointerMode: PointerMode,
    metrics: Flow<SessionMetricsState>?,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onTogglePointerMode: () -> Unit,
    onShowKeyboard: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Column(
        Modifier
            .width(Vessel.metrics.railWidth)
            // Insets, then the margin, then the card: the rail is centred
            // vertically but a tall one still has to clear the status and gesture
            // bars at its ends.
            .systemBarsPadding()
            .padding(Vessel.metrics.s6)
            // The one place this design permits translucency: the rail sits over
            // the guest's own output, and an opaque slab would hide the thing it
            // is a control for.
            .vCard(fill = Vessel.colors.surfaceFloating, elevation = VElev.md)
            .padding(Vessel.metrics.s8)
            .verticalScroll(rememberScrollState()),
    ) {
        RailStatus(state)
        VRule(verticalMargin = Vessel.metrics.s6)

        // Composed only while the rail is open, which is the whole contract: the
        // sampler watches its own subscriber count and drops back to a tenth of
        // the rate when nothing is looking. Collecting this at screen level
        // instead would sample at 1 Hz for the entire session to draw a panel
        // nobody opened.
        if (metrics != null) {
            val sample by metrics.collectAsStateWithLifecycle(initialValue = null)
            SessionMetricsRail(sample, paused = state.paused)
            VRule(verticalMargin = Vessel.metrics.s6)
        }

        RailActions(
            pointerMode = pointerMode,
            onTogglePointerMode = onTogglePointerMode,
            onShowKeyboard = onShowKeyboard,
            onOpenFiles = onOpenFiles,
            onOpenLogs = onOpenLogs,
        )

        VRule(verticalMargin = Vessel.metrics.s6)
        RailTransport(state, onTogglePause, onStop)
    }
}

/** What the session is doing, in one line at the top of the rail. */
@Composable
private fun RailStatus(state: SessionState) {
    Text(
        "${state.containerName.ifBlank { "Session" }} \u00b7 " +
            if (state.paused) "paused" else "running",
        style = Vessel.type.monoSmall,
        color = if (state.paused) Vessel.colors.warn else Vessel.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Pause and Stop, at the foot of the rail, as two equal squares.
 *
 * **The lifetime controls are last because they end the thing above them.** This
 * rail is a column read top to bottom, not a window, and putting the destructive
 * action first means the first thing under the reader's thumb is the one that
 * closes everything.
 *
 * They stay bare glyphs while everything above them is captioned, and that
 * asymmetry is deliberate: a triangle and a cross are the two marks in this
 * product that carry themselves.
 *
 * The buttons stay **square** and the *columns* divide evenly: each sits centred
 * in a `weight(1f)` box. Passing the weight to `VIconAction` directly does
 * nothing useful — it applies `.size()` after the caller's modifier, so the
 * square wins and the row ends up left-packed with a ragged gap after it.
 */
@Composable
private fun RailTransport(state: SessionState, onTogglePause: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            VIconAction(
                icon = if (state.paused) VIcons.Play else VIcons.Pause,
                contentDescription = if (state.paused) {
                    "Resume the session"
                } else {
                    "Pause the session — the guest stops until you resume it"
                },
                onClick = onTogglePause,
                // Primary while paused: resuming is the one thing a frozen
                // desktop is waiting for, and it should be the accented control.
                style = if (state.paused) VButtonStyle.Primary else VButtonStyle.Secondary,
                size = Vessel.metrics.touchTarget,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            VIconAction(
                icon = VIcons.X,
                contentDescription = "Stop the session",
                onClick = onStop,
                style = VButtonStyle.Danger,
                size = Vessel.metrics.touchTarget,
            )
        }
    }
}

/**
 * The four tools, one row, equal columns.
 *
 * Each column keeps a **caption under its glyph**. The labels are not decoration:
 * the first thing asked of the earlier icon-only grid was what the icons were,
 * and a glyph can carry an action a user already knows but cannot introduce one.
 * Two words at `overline` size buy that back for eleven dp of height.
 *
 * Pointer mode's caption is the mode it will switch *to*, matching its glyph — an
 * icon alone on a two-state control is a coin toss, and losing that bet mid-game
 * means the cursor stops behaving.
 *
 * **Files opens Vessel's own browser, not Wine's.** `winefile` runs inside the
 * guest, so it cannot reach Android storage at all; the browser here reads
 * `drive_c` directly and imports and exports for free.
 */
@Composable
private fun RailActions(
    pointerMode: PointerMode,
    onTogglePointerMode: () -> Unit,
    onShowKeyboard: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val trackpad = pointerMode == PointerMode.TRACKPAD
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        RailAction(
            icon = if (trackpad) VIcons.HandPointing else VIcons.Cursor,
            caption = if (trackpad) "Touch" else "Pad",
            contentDescription = if (trackpad) {
                "Switch to direct touch — the cursor goes where you touch"
            } else {
                "Switch to trackpad — drag to push the cursor"
            },
            onClick = onTogglePointerMode,
            modifier = Modifier.weight(1f),
        )
        RailAction(
            VIcons.Keyboard, "Keys", "Show the on-screen keyboard",
            onShowKeyboard, Modifier.weight(1f),
        )
        RailAction(
            VIcons.Folder, "Files", "Browse this container's C: drive",
            onOpenFiles, Modifier.weight(1f),
        )
        RailAction(
            VIcons.List, "Log", "Open the session log",
            onOpenLogs, Modifier.weight(1f),
        )
    }
}

/**
 * One column: a square button with a word under it.
 *
 * The square is the touch target and stays at `touchTarget`; the column around it
 * is what `weight` divides, so the four sit on even centres whatever the rail's
 * width. Sizing the *button* by weight instead would stretch four squares into
 * four rectangles of whatever width was left over.
 */
@Composable
private fun RailAction(
    icon: ImageVector,
    caption: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        VIconAction(
            icon = icon,
            contentDescription = contentDescription,
            onClick = onClick,
            size = Vessel.metrics.touchTarget,
        )
        Text(
            caption,
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where the Windows desktop is drawn.
 *
 * `app.vessel.core.SessionDisplayServer` owns the view and hands it over through
 * [app.vessel.ui.vm.SessionViewModel.surface]; nothing here references the
 * vendored server's types, and nothing here forwards input — the view is
 * focusable and handles its own touches, so a pointer keeps working while this
 * composable is recomposing.
 *
 * The `update` block re-parents rather than assuming: this composable leaves and
 * comes back whenever the phase does, while the view outlives all of it because
 * the guest is still drawing into its GL surface.
 */
@Composable
private fun SessionSurface(state: SessionState, surface: View?) {
    if (surface != null) {
        AndroidView(
            factory = { FrameLayout(it) },
            modifier = Modifier.fillMaxSize(),
            update = { host ->
                if (surface.parent !== host) {
                    (surface.parent as? ViewGroup)?.removeView(surface)
                    host.removeAllViews()
                    host.addView(
                        surface,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            },
            onRelease = { host -> host.removeAllViews() },
        )
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(Vessel.metrics.s22).widthIn(max = Vessel.metrics.dialogMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        ) {
            state.geometry?.let {
                Text(it.toString(), style = Vessel.type.metric, color = Vessel.colors.textMuted)
            }
            Text(
                "The container is running, but no display server came up — open the session log " +
                    "from the rail to see what Wine is doing.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// — previews -------------------------------------------------------------------
//
// Fixed states, and only here. The real thing reads the runtime, so a device with
// nothing installed shows the failure rather than a plausible-looking run.

private val PreviewSteps = listOf(
    ProvisionStep("layout", "Create container", ProvisionStatus.SKIPPED, "Already created"),
    ProvisionStep(
        "session:components",
        "Resolve components",
        ProvisionStatus.DONE,
        "Wine/1114 · DXVK/20701",
    ),
    ProvisionStep(
        "registry",
        "Write registry seed",
        ProvisionStatus.DONE,
        "7 keys written to prefix-seed.reg",
    ),
    ProvisionStep(
        "boot",
        "Initialise Wine prefix",
        ProvisionStatus.RUNNING,
        "wineboot --update, pass 2 of 2",
    ),
    ProvisionStep(
        "session:fex",
        "Install FEX",
        ProvisionStatus.DONE,
        "libarm64ecfex.dll — 2 file(s) copied into the prefix",
    ),
    ProvisionStep("session:d3d", "Install D3D layers", ProvisionStatus.PENDING),
)

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 620)
@Composable
private fun SessionLaunchDialogPreview() {
    VesselTheme {
        SessionLaunchDialog(
            state = SessionState(
                containerId = "c1",
                containerName = "Display proof",
                phase = SessionPhase.PREPARING,
                steps = PreviewSteps,
                lastLine = "loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\dxgi.dll\"",
                geometry = DisplayGeometry(1280, 720),
            ),
            onCancel = {},
            onDismiss = {},
            onOpenLogs = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 620)
@Composable
private fun SessionFailedDialogPreview() {
    VesselTheme {
        SessionOutcomeDialog(
            state = SessionState(
                containerId = "c1",
                containerName = "Display proof",
                phase = SessionPhase.FAILED,
                steps = PreviewSteps.map {
                    if (it.id == "boot") {
                        it.copy(status = ProvisionStatus.FAILED, detail = "wineboot exited with 1")
                    } else {
                        it
                    }
                },
                lastError = "virtual:map_image_into_view failed to set 60000020 protection on " +
                    "ntdll.dll section .text, noexec filesystem?",
                diagnosis = SessionDiagnosis(
                    id = "noexec",
                    headline = "Wine could not make its own code executable",
                    detail = "The loader mapped a PE section and the kernel refused to mark it " +
                        "executable, so nothing inside the prefix can run.",
                ),
                geometry = DisplayGeometry(1280, 720),
            ),
            onOpenLogs = {},
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 927, heightDp = 422)
@Composable
private fun SessionDesktopPreview() {
    VesselTheme {
        SessionDesktop(
            state = SessionState(
                containerId = "c1",
                containerName = "Display proof",
                phase = SessionPhase.RUNNING,
                geometry = DisplayGeometry(1280, 720),
            ),
            surface = null,
            pointerMode = PointerMode.TRACKPAD,
            onOpenLogs = {},
            onOpenFiles = {},
            onStop = {},
            onTogglePause = {},
            onTogglePointerMode = {},
            onShowKeyboard = {},
            onLaunchApp = {},
            onFocusWindow = {},
            onMinimizeWindow = {},
            onCloseWindow = {},
            onKillWindow = {},
        )
    }
}
