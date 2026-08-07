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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import app.vessel.data.SessionPhase
import app.vessel.data.SessionState
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.SessionViewModel

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
        onBack = onBack,
        onOpenLogs = { onOpenLogs(state.startedAt) },
        onStop = viewModel::stop,
        onRetry = { viewModel.retry(native) },
        onDismiss = {
            viewModel.dismiss()
            onBack()
        },
    )
}

@Composable
private fun SessionContent(
    state: SessionState,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    /** The display server's compositor. Null in previews and when it is Absent. */
    surface: View? = null,
) {
    var confirmingStop by remember { mutableStateOf(false) }

    if (state.phase == SessionPhase.RUNNING) {
        RunningSurface(
            state = state,
            surface = surface,
            onOpenLogs = onOpenLogs,
            onStop = { confirmingStop = true },
        )
    } else {
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
                when (state.phase) {
                    SessionPhase.EXITED -> ExitedPane(state, onOpenLogs)
                    SessionPhase.FAILED -> FailedPane(state, onOpenLogs, onRetry)
                    else -> PreparingPane(state)
                }
                if (state.active) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s17),
                        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                    ) {
                        VButton("Cancel", { confirmingStop = true }, style = VButtonStyle.Danger)
                    }
                }
            }
        }
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
    VSectionHeader("Preparing")
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
        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
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
    Box(Modifier.padding(top = 3.dp).size(GLYPH), contentAlignment = Alignment.Center) {
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
                Box(Modifier.size(9.dp).background(Vessel.colors.accent, CircleShape))

            ProvisionStatus.PENDING ->
                Box(Modifier.size(9.dp).vRing(Vessel.colors.divider, CircleShape))
        }
    }
}

private val GLYPH = 18.dp

// — Exited and Failed ----------------------------------------------------------

@Composable
private fun ExitedPane(state: SessionState, onOpenLogs: () -> Unit) {
    VSectionHeader("Exited")
    Text(
        "The Windows desktop closed.",
        style = Vessel.type.body,
    )
    Text(
        "exit code ${state.exitCode ?: 0}",
        style = Vessel.type.mono,
        color = Vessel.colors.textMuted,
        modifier = Modifier.padding(top = Vessel.metrics.s6),
    )
    Row(
        Modifier.padding(top = Vessel.metrics.s17),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        VButton("View log", onOpenLogs, style = VButtonStyle.Secondary)
    }
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
private fun FailedPane(state: SessionState, onOpenLogs: () -> Unit, onRetry: () -> Unit) {
    VSectionHeader("Failed")

    val step = state.failedStep
    Text(
        state.diagnosis?.headline
            ?: state.failure
            ?: step?.label?.let { "$it did not finish" }
            ?: "The session stopped.",
        style = Vessel.type.cardTitle,
        color = Vessel.colors.danger,
    )
    state.diagnosis?.detail?.let {
        Text(
            it,
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textLabel,
            modifier = Modifier.padding(top = Vessel.metrics.s6),
        )
    }

    val evidence = listOfNotNull(
        step?.detail?.takeIf { it != state.failure },
        state.lastError,
        state.exitCode?.let { "exit code $it" },
    )
    if (evidence.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = Vessel.metrics.s11)
                .vCard()
                .padding(Vessel.metrics.s11),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        ) {
            evidence.forEach {
                Text(it, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            }
        }
    }

    Row(
        Modifier.padding(top = Vessel.metrics.s17),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        VButton("View log", onOpenLogs, style = VButtonStyle.Secondary)
        VButton("Retry", onRetry, style = VButtonStyle.Primary, icon = Icons.Filled.Refresh)
    }
}

// — Running --------------------------------------------------------------------

/**
 * Full-bleed surface with a rail swiped in from the left edge.
 *
 * **A rail, not a bottom sheet.** This screen is usually landscape, where
 * vertical space is scarce and horizontal space is not, and a sheet spends the
 * scarce one.
 */
@Composable
private fun RunningSurface(
    state: SessionState,
    surface: View?,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
) {
    var railOpen by remember { mutableStateOf(false) }

    // Back does not leave a running session. DESIGN.md forwards it to the guest
    // as `Esc`, which needs a display server to send it to; until there is one,
    // opening the rail — where Stop is — is the behaviour that does not silently
    // discard the gesture.
    BackHandler(enabled = true) { railOpen = true }

    Box(Modifier.fillMaxSize().background(Vessel.colors.bg)) {
        SessionSurface(state, surface)

        Row(Modifier.fillMaxHeight()) {
            AnimatedVisibility(
                visible = railOpen,
                enter = expandHorizontally(tween(Vessel.metrics.durationStandardMs)),
                exit = shrinkHorizontally(tween(Vessel.metrics.durationStandardMs)),
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .systemBarsPadding()
                        .padding(Vessel.metrics.s8)
                        // The one place this design permits translucency: the
                        // rail sits over the guest's own output, and an opaque
                        // slab would hide the thing it is a control for.
                        .vCard(
                            fill = Vessel.colors.surface.copy(alpha = 0.92f),
                            elevation = VElev.md,
                        )
                        .padding(Vessel.metrics.s6),
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
                ) {
                    VIconAction(Icons.AutoMirrored.Filled.List, "Session log", onOpenLogs)
                    VIconAction(Icons.Filled.Close, "Stop the session", onStop, style = VButtonStyle.Danger)
                    // Metrics, keyboard and input mode belong here per DESIGN.md
                    // and are deliberately absent: each needs the display server
                    // to have something to act on, and a metric strip with no
                    // sampler behind it would be a made-up number on the one
                    // screen where numbers are the product.
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

        if (railOpen) {
            // Anywhere else closes it, and the scrim is invisible: the guest's
            // output stays fully readable while the rail is open.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { railOpen = false },
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
            Modifier.padding(Vessel.metrics.s22),
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
