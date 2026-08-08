package app.vessel.ui.screens

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.DisplayGeometry
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
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VOutcomeDialog
import app.vessel.ui.components.VOutcomeTone
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.SessionViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Five states, not one.
 *
 * DESIGN.md is explicit that most designs for this screen draw only the happy
 * path and the first real launch is then a black rectangle with no explanation.
 * Preparing, Starting, Exited and Failed get the same attention as Running, and
 * on this device they are where the time is actually spent: nothing has ever run
 * a Windows program end to end here yet.
 */
@Composable
fun SessionScreen(
    containerId: String,
    onBack: () -> Unit,
    onOpenLogs: (Long?) -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val surface by viewModel.surface.collectAsStateWithLifecycle()
    val pointerMode by viewModel.pointerMode.collectAsStateWithLifecycle()
    val fileManagerRefusal by viewModel.fileManagerRefusal.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The panel's own size, which is what `display.resolution: native` means.
    // `maximumWindowMetrics` rather than `Resources.displayMetrics`: the latter
    // reports the *window*, and this activity is edge-to-edge but still not
    // guaranteed to be the whole screen on a foldable or in split screen.
    val native = remember(context) {
        context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics
            ?.bounds
            ?.let { DisplayGeometry(it.width(), it.height()) }
    }

    // Idempotent by contract — composition is not a promise about how many times
    // it runs, and a rotation during Preparing must not queue a second launch.
    LaunchedEffect(containerId) { viewModel.launchIfIdle(native) }

    SessionContent(
        state = state,
        surface = surface,
        pointerMode = pointerMode,
        metrics = viewModel.metrics,
        onBack = onBack,
        onOpenLogs = { onOpenLogs(state.startedAt) },
        onStop = viewModel::stop,
        onRetry = { viewModel.retry(native) },
        onTogglePointerMode = viewModel::togglePointerMode,
        onShowKeyboard = viewModel::showKeyboard,
        onOpenFiles = viewModel::launchFileManager,
        onDismiss = {
            viewModel.dismiss()
            onBack()
        },
    )

    // A refusal has to be visible. The file manager opens *inside* the guest's
    // desktop, so a launch that fails draws nothing anywhere the user is looking
    // — without this the button would simply appear dead, which is the one
    // outcome this product treats as worse than an error.
    fileManagerRefusal?.let { reason ->
        VOutcomeDialog(
            title = "The file manager did not open",
            tone = VOutcomeTone.Danger,
            evidence = listOf(reason),
            onDismiss = viewModel::dismissFileManagerRefusal,
            actions = {
                VButton("Close", viewModel::dismissFileManagerRefusal, style = VButtonStyle.Primary)
            },
        )
    }
}

@Composable
private fun SessionContent(
    state: SessionState,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    pointerMode: PointerMode = PointerMode.TRACKPAD,
    onTogglePointerMode: () -> Unit = {},
    onShowKeyboard: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    /**
     * The sampler's window, collected only while the rail is open.
     *
     * Passed as the flow rather than as a collected value on purpose: collecting
     * is what raises the sample rate from 0.1 Hz to 1 Hz, so the collection has
     * to start and stop with the rail's own composition, not with this screen's.
     * Null in previews.
     */
    metrics: Flow<SessionMetricsState>? = null,
    /** The display server's compositor. Null in previews and when it is Absent. */
    surface: View? = null,
) {
    var confirmingStop by remember { mutableStateOf(false) }

    // A finished session has to be dismissed on the way out, and the system back
    // gesture is a way out that nothing was intercepting.
    //
    // The toolbar's arrow and the outcome dialog's buttons both called
    // `onDismiss`, which clears the runtime; a swipe popped the back stack
    // straight past them and left the runtime holding an EXITED or FAILED
    // session. `launchIfIdle` then returns early — it refuses whenever the phase
    // is not IDLE — so the *next* container opened showed the *previous* run's
    // outcome dialog immediately, before anything had launched. That is the bug
    // that reads as "pressing back opens a dialogue".
    BackHandler(enabled = state.finished) { onDismiss() }

    if (state.phase == SessionPhase.RUNNING) {
        RunningSurface(
            state = state,
            surface = surface,
            pointerMode = pointerMode,
            metrics = metrics,
            onOpenFiles = onOpenFiles,
            onOpenLogs = onOpenLogs,
            onStop = { confirmingStop = true },
            onTogglePointerMode = onTogglePointerMode,
            onShowKeyboard = onShowKeyboard,
        )
    } else {
        // The checklist stays up for every non-running phase, terminal ones
        // included. It is the attribution — which of six steps got that far —
        // and the outcome is layered over it rather than replacing it.
        VScaffold(
            toolbar = {
                VPushToolbar(
                    title = state.containerName.ifBlank { "Session" },
                    subtitle = phaseLabel(state.phase),
                    onBack = if (state.finished) onDismiss else onBack,
                )
            },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PreparingPane(state)
                if (state.active) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s11),
                        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                    ) {
                        VButton("Cancel", { confirmingStop = true }, style = VButtonStyle.Danger)
                    }
                }
            }
        }
    }

    when (state.phase) {
        SessionPhase.FAILED -> FailedDialog(state, onOpenLogs, onRetry, onDismiss)
        SessionPhase.EXITED -> ExitedDialog(state, onOpenLogs, onDismiss)
        else -> Unit
    }

    if (confirmingStop) {
        VConfirmSheet(
            title = "Stop ${state.containerName.ifBlank { "this session" }}?",
            message = "Every program running inside the container is closed. Unsaved work " +
                "in them is lost; the container itself and its files are not touched.",
            confirmLabel = "Stop",
            onConfirm = {
                confirmingStop = false
                onStop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

// — Preparing and Starting -----------------------------------------------------

/**
 * A checklist, not a spinner.
 *
 * The point is attribution: when a launch fails, the row it failed on is the
 * whole diagnosis. A spinner turns six distinguishable failures into one.
 */
@Composable
private fun PreparingPane(state: SessionState) {
    // The header is the tense, not the phase: after a run ends this list is a
    // record of what happened, and calling it "Preparing" under a failure dialog
    // reads as though it were still going.
    VSectionHeader(if (state.finished) "Progress" else "Preparing")
    state.steps.forEach { ChecklistRow(it) }

    // DESIGN.md: Starting shows the last log line as it goes, "because that is
    // where a missing DLL surfaces".
    val line = state.lastLine
    if (line != null) {
        Text(
            line,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            maxLines = 3,
            modifier = Modifier.padding(top = Vessel.metrics.s11),
        )
    }
    if (state.phase == SessionPhase.STARTING) {
        Text(
            "Starting the Windows desktop at ${state.geometry}.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textLabel,
            modifier = Modifier.padding(top = Vessel.metrics.s8),
        )
    }
}

@Composable
private fun ChecklistRow(step: ProvisionStep) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        StepGlyph(step.status)
        Column(Modifier.weight(1f)) {
            Text(
                step.label,
                style = Vessel.type.body,
                color = if (step.status == ProvisionStatus.PENDING) {
                    Vessel.colors.textMuted
                } else {
                    Vessel.colors.textPrimary
                },
            )
            step.detail?.let {
                Text(
                    it,
                    style = Vessel.type.monoSmall,
                    color = if (step.status == ProvisionStatus.FAILED) {
                        Vessel.colors.danger
                    } else {
                        Vessel.colors.textMuted
                    },
                )
            }
        }
    }
}

/**
 * The status cell: one 18 dp square whatever the state, so the labels beside it
 * stay on one left edge instead of stepping in and out as rows complete.
 */
@Composable
private fun StepGlyph(status: ProvisionStatus) {
    Box(
        Modifier.padding(top = Vessel.metrics.s3).size(GLYPH),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            ProvisionStatus.DONE ->
                Icon(Icons.Filled.Check, null, Modifier.size(GLYPH), tint = Vessel.colors.ok)

            ProvisionStatus.SKIPPED ->
                Icon(Icons.Filled.Check, null, Modifier.size(GLYPH), tint = Vessel.colors.textMuted)

            ProvisionStatus.FAILED ->
                Icon(Icons.Filled.Clear, null, Modifier.size(GLYPH), tint = Vessel.colors.danger)

            // Running is a filled accent dot and pending an empty ring — the same
            // shape at two weights, which reads as progress down the column
            // without a second animation competing with the log line below it.
            ProvisionStatus.RUNNING ->
                Box(Modifier.size(DOT).background(Vessel.colors.accent, CircleShape))

            ProvisionStatus.PENDING ->
                Box(Modifier.size(DOT).vRing(Vessel.colors.divider, CircleShape))
        }
    }
}

private val GLYPH = 16.dp

/** The running/pending mark: one shape at two weights, filled and ringed. */
private val DOT = 8.dp

// — Exited and Failed ----------------------------------------------------------
//
// Both are dialogs over the checklist, not screens. "No Wine build is installed"
// is two lines and two buttons; given a screen of its own it is two lines, two
// buttons and 900 px of nothing, and it hides the one thing that says how far
// the launch got.

@Composable
private fun ExitedDialog(state: SessionState, onOpenLogs: () -> Unit, onDismiss: () -> Unit) {
    val code = state.exitCode ?: 0
    VOutcomeDialog(
        title = "The Windows desktop closed",
        // A non-zero exit is not a Vessel failure — the guest chose it — but it
        // is the difference between "you closed it" and "it fell over", and the
        // dialog is the only place that distinction is ever shown.
        tone = if (code == 0) VOutcomeTone.Neutral else VOutcomeTone.Danger,
        detail = if (code == 0) null else "The program inside the container ended with an error.",
        evidence = listOf("exit code $code"),
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
 * precise and unreadable —
 * `err:virtual:map_image_into_view failed to set 60000020 protection` is exactly
 * true and tells almost nobody what happened. The raw line stays underneath it in
 * mono, since that is what a bug report quotes.
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
            VButton("Retry", onRetry, style = VButtonStyle.Primary, icon = Icons.Filled.Refresh)
        },
    )
}

// — Running --------------------------------------------------------------------

/**
 * Full-bleed surface with a rail swiped in from the left edge.
 *
 * **A rail, not a bottom sheet.** This screen is usually landscape, where
 * vertical space is scarce and horizontal space is not, and a sheet spends the
 * scarce one.
 *
 * **The rail hugs.** It was `fillMaxHeight` with its content at the top, which
 * over a portrait desktop meant a 208 dp column two thirds of which was an empty
 * translucent slab covering the guest's own window. It is now sized by what is
 * in it and centred against the screen's vertical middle, so the amount of the
 * desktop it hides is the amount it needs.
 */
@Composable
private fun RunningSurface(
    state: SessionState,
    surface: View?,
    pointerMode: PointerMode,
    metrics: Flow<SessionMetricsState>?,
    onOpenFiles: () -> Unit,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
    onTogglePointerMode: () -> Unit,
    onShowKeyboard: () -> Unit,
) {
    var railOpen by remember { mutableStateOf(false) }

    // Back closes the rail if it is open, and otherwise leaves the screen — the
    // session keeps running, because the foreground service owns it and not this
    // composable.
    //
    // It used to *open* the rail unconditionally, from a time when the rail had
    // no other way in. That made Back a trap: every press reopened the rail and
    // nothing could ever leave a running session. The rail has a full-height
    // edge handle now, so the gesture is free to mean what it means everywhere
    // else. Stopping the container is still only ever the Stop button.
    BackHandler(enabled = railOpen) { railOpen = false }

    Box(Modifier.fillMaxSize().background(Vessel.colors.bg)) {
        SessionSurface(state, surface)

        // **Before the rail, not after.** A Box stacks in declaration order and
        // hit-tests from the top down, so a full-size scrim declared last covers
        // the rail it is a scrim *for*: every button tap landed on this and
        // closed the rail instead of firing. It looked like four dead buttons.
        if (railOpen) {
            // Invisible on purpose — the guest's output stays fully readable
            // while the rail is open.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { railOpen = false },
            )
        }

        Row(
            Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = railOpen,
                enter = expandHorizontally(tween(Vessel.metrics.durationStandardMs)),
                exit = shrinkHorizontally(tween(Vessel.metrics.durationStandardMs)),
            ) {
                Column(
                    Modifier
                        .width(RAIL_WIDTH)
                        // Insets, then the margin, then the card: the rail is
                        // centred vertically but a tall one still has to clear
                        // the status and gesture bars at its ends.
                        .systemBarsPadding()
                        .padding(Vessel.metrics.s6)
                        // The one place this design permits translucency: the
                        // rail sits over the guest's own output, and an opaque
                        // slab would hide the thing it is a control for.
                        .vCard(
                            fill = Vessel.colors.surface.copy(alpha = 0.92f),
                            elevation = VElev.md,
                        )
                        .padding(Vessel.metrics.s8),
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                ) {
                    // Composed only while the rail is open, which is the whole
                    // contract: the sampler watches its own subscriber count and
                    // drops back to a tenth of the rate when nothing is looking.
                    // Collecting this at screen level instead would sample at
                    // 1 Hz for the entire session to draw a panel nobody opened.
                    if (metrics != null) {
                        val sample by metrics.collectAsStateWithLifecycle(initialValue = null)
                        SessionMetricsRail(sample)
                        VRule(verticalMargin = 0.dp)
                    }
                    RailActions(
                        pointerMode = pointerMode,
                        onTogglePointerMode = onTogglePointerMode,
                        onShowKeyboard = onShowKeyboard,
                        onOpenFiles = onOpenFiles,
                        onOpenLogs = onOpenLogs,
                        onStop = onStop,
                    )
                }
            }

            if (!railOpen) {
                // The 4 dp handle. A full-height target, so it is reachable
                // without looking on a screen the user is not looking at.
                Box(
                    Modifier
                        .width(HANDLE_TOUCH)
                        .fillMaxHeight()
                        .clickable { railOpen = true },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .width(HANDLE)
                            .fillMaxHeight(0.25f)
                            .background(Vessel.colors.accent.copy(alpha = 0.55f)),
                    )
                }
            }
        }
    }
}

/**
 * The rail's four controls, as a 2×2 block.
 *
 * A vertical stack of four is 4 × 44 dp plus its gaps — 200 dp of column for
 * four glyphs, which is what made the rail a full-height slab in the first
 * place. Two rows of two is 94 dp and reads as one control group rather than as
 * a list of unrelated boxes.
 *
 * Stop keeps the bottom-right corner, diagonally opposite the pointer toggle
 * that gets used most: the destructive action should not be the neighbour of the
 * one a thumb reaches for by habit.
 */
@Composable
private fun RailActions(
    pointerMode: PointerMode,
    onTogglePointerMode: () -> Unit,
    onShowKeyboard: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
) {
    val gap = Arrangement.spacedBy(Vessel.metrics.s6)
    Column(verticalArrangement = gap) {
        Row(horizontalArrangement = gap) {
            // The pointer mode's icon is the mode it will switch *to*, and the
            // description says which that is — an icon alone on a two-state
            // control is a coin toss, and getting it wrong here means the cursor
            // stops behaving mid-game.
            VIconAction(
                icon = if (pointerMode == PointerMode.TRACKPAD) {
                    VIcons.TouchApp
                } else {
                    VIcons.Mouse
                },
                contentDescription = if (pointerMode == PointerMode.TRACKPAD) {
                    "Switch to direct touch — the cursor goes where you touch"
                } else {
                    "Switch to trackpad — drag to push the cursor"
                },
                onClick = onTogglePointerMode,
                size = Vessel.metrics.touchTarget,
            )
            VIconAction(
                VIcons.Keyboard,
                "Keyboard",
                onShowKeyboard,
                size = Vessel.metrics.touchTarget,
            )
        }
        Row(horizontalArrangement = gap) {
            VIconAction(
                VIcons.Folder,
                "Open the file manager on C:",
                onOpenFiles,
                size = Vessel.metrics.touchTarget,
            )
            VIconAction(
                Icons.AutoMirrored.Filled.List,
                "Session log",
                onOpenLogs,
                size = Vessel.metrics.touchTarget,
            )
        }
        // Stop on a row of its own, still bottom-right. A fifth control made the
        // 2×2 an odd number, and of the two ways to break the grid — crowding
        // Stop next to a new neighbour, or giving it its own line — separating
        // the destructive action is the one worth having.
        Row(horizontalArrangement = gap) {
            Spacer(Modifier.size(Vessel.metrics.touchTarget))
            VIconAction(
                Icons.Filled.Close,
                "Stop the session",
                onStop,
                style = VButtonStyle.Danger,
                size = Vessel.metrics.touchTarget,
            )
        }
    }
}

/**
 * Where the Windows desktop is drawn.
 *
 * `app.vessel.core.SessionDisplayServer` owns the view and hands it over through
 * [SessionViewModel.surface]; nothing here references the vendored server's
 * types, and nothing here forwards input — the view is focusable and handles its
 * own touches, so a pointer keeps working while this composable is recomposing.
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
                "The container is running, but no display server came up — open the session " +
                    "log from the rail to see what Wine is doing.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val HANDLE = 4.dp
private val HANDLE_TOUCH = 20.dp

/**
 * The rail's outer width, margins included.
 *
 * Fixed rather than intrinsic, because the content is live: sized to the widest
 * thing in it, the rail would breathe in and out by a few pixels every second as
 * the digits changed, over a desktop somebody is trying to read.
 *
 * The number is the metrics grid — two [app.vessel.ui.components.VMetricGrid]
 * cells at 62 dp with an 8 dp gutter — plus the card's padding and the 6 dp
 * margin. The action block is 94 dp and fits inside it with room. It replaces a
 * 208 dp *inner* width, which came out at 230 dp on screen: the rail was over
 * half the width of a portrait phone to show four numbers.
 */
private val RAIL_WIDTH = 162.dp

private fun phaseLabel(phase: SessionPhase): String = when (phase) {
    SessionPhase.IDLE -> "idle"
    SessionPhase.PREPARING -> "preparing"
    SessionPhase.STARTING -> "starting"
    SessionPhase.RUNNING -> "running"
    SessionPhase.EXITED -> "exited"
    SessionPhase.FAILED -> "failed"
}

// — previews -------------------------------------------------------------------
//
// Fixed states, and only here. The screen itself reads the runtime, so a device
// with nothing installed shows the failure rather than a plausible-looking run.

private val PreviewSteps = listOf(
    ProvisionStep("session:components", "Resolve components", ProvisionStatus.DONE, "Wine/1114 · DXVK/271"),
    ProvisionStep("session:fex", "Install FEX", ProvisionStatus.DONE, "libarm64ecfex.dll — 2 file(s) copied into the prefix"),
    ProvisionStep("session:d3d", "Install D3D layers", ProvisionStatus.DONE, "DXVK, vkd3d — 18 file(s) copied into the prefix"),
    ProvisionStep("layout", "Create prefix", ProvisionStatus.SKIPPED, "Already created"),
    ProvisionStep("registry", "First-run registry", ProvisionStatus.DONE, "4 keys written to prefix-seed.reg"),
    ProvisionStep("boot", "Initialise Wine prefix", ProvisionStatus.RUNNING),
)

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SessionPreparingPreview() {
    VesselTheme {
        SessionContent(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
                phase = SessionPhase.PREPARING,
                steps = PreviewSteps,
                lastLine = "loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\dxgi.dll\"",
                geometry = DisplayGeometry(1280, 720),
            ),
            onBack = {}, onOpenLogs = {}, onStop = {}, onRetry = {}, onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SessionFailedPreview() {
    VesselTheme {
        SessionContent(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
                phase = SessionPhase.FAILED,
                steps = PreviewSteps.map {
                    if (it.id == "boot") it.copy(status = ProvisionStatus.FAILED, detail = "wineboot exited with 1") else it
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
            onBack = {}, onOpenLogs = {}, onStop = {}, onRetry = {}, onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 824, heightDp = 392)
@Composable
private fun SessionRunningPreview() {
    VesselTheme {
        SessionContent(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
                phase = SessionPhase.RUNNING,
                geometry = DisplayGeometry(1280, 720),
            ),
            onBack = {}, onOpenLogs = {}, onStop = {}, onRetry = {}, onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SessionExitedPreview() {
    VesselTheme {
        SessionContent(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
                phase = SessionPhase.EXITED,
                exitCode = 0,
                steps = PreviewSteps,
                geometry = DisplayGeometry(1280, 720),
            ),
            onBack = {}, onOpenLogs = {}, onStop = {}, onRetry = {}, onDismiss = {},
        )
    }
}
