package app.vessel.ui.screens

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import kotlinx.coroutines.flow.Flow

/**
 * The session, which is a desktop and two dialogs — and no longer a screen.
 *
 * DESIGN.md, *Four of the five states are not a screen*: Preparing and Starting
 * are [SessionLaunchDialog] over whatever the user was already looking at,
 * Failed and a non-zero Exited are [SessionOutcomeDialog], a clean exit is
 * nothing at all, and only RUNNING gets a destination — [SessionDesktop]. The
 * phase decides which is on screen and [app.vessel.ui.VesselApp] is the one place
 * that decides it; nothing here navigates.
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
 * **A checklist is not a place.** Waiting for six rows to tick is not somewhere
 * a user navigated to, and the screen this used to be gave it a toolbar, a back
 * arrow and a full page of ground to say so anyway. As a dialog it sits over the
 * container the user tapped, which is the thing it is about.
 *
 * [onDismiss] hides the report; it does not cancel the launch. That distinction
 * is why Cancel is a button rather than the dismiss gesture: a container takes
 * minutes to provision on this phone, and a dialog that cannot be put down would
 * be a modal wait — while a back gesture that silently killed a provisioning
 * prefix would be much worse than either. The foreground-service notification is
 * still there, and RUNNING brings the desktop up regardless.
 *
 * Cancel is deliberately *not* behind a confirmation, unlike Stop: nothing inside
 * a container that has not started yet can lose work.
 */
@Composable
fun SessionLaunchDialog(
    state: SessionState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    SessionDialogCard(onDismiss = onDismiss) {
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
            VButton("Hide", onDismiss, style = VButtonStyle.Secondary)
        }
    }
}

/**
 * The dialog shell [VOutcomeDialog] draws, for a body that is not strings.
 *
 * Not a call to that composable, and not a slot added to it: its `evidence` is a
 * `List<String>` on purpose — it is the raw material a bug report quotes — and
 * widening it to a composable slot would let any caller put a scrolling layout
 * inside a dialog that is meant to hold three lines of mono. The checklist is
 * rows with live status glyphs, so it needs the shell and not the component.
 */
@Composable
private fun SessionDialogCard(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The platform's 280 dp card is narrower than this product's gutter and
        // wraps a two-line message to four.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .padding(horizontal = Vessel.metrics.screenGutter)
                .widthIn(max = Vessel.metrics.dialogMaxWidth),
        ) {
            val shape = Vessel.metrics.shapeLg
            Column(
                Modifier
                    .fillMaxWidth()
                    .vElevation(VElev.lg, shape)
                    .background(Vessel.colors.surface, shape)
                    .vRing(VElev.lg.ring, shape)
                    .padding(Vessel.metrics.s17),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
                content = content,
            )
        }
    }
}

/**
 * A checklist, not a spinner.
 *
 * The point is attribution: when a launch fails, the row it failed on is the
 * whole diagnosis. A spinner turns six distinguishable failures into one.
 *
 * Capped and scrollable because it lives in a dialog now — six rows each with a
 * detail line is taller than a landscape window, and a dialog that grows past the
 * screen puts its own buttons out of reach.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ProvisionChecklist(state: SessionState) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = CHECKLIST_MAX)
            .verticalScroll(rememberScrollState()),
    ) {
        // The header is the tense, not the phase: after a run ends this list is a
        // record of what happened, and calling it "Preparing" under a failure
        // dialog reads as though it were still going.
        VSectionHeader(if (state.finished) "Progress" else "Preparing")
        state.steps.forEach { ChecklistRow(it) }
    }

    // **Outside the scroll, and that is the fix.** These two lived inside it, so
    // the container's max height fell across them and cut the last one through
    // the middle of its glyphs — text that is present, unreadable, and cannot be
    // scrolled to because the steps above had already used the height.
    //
    // They are also the wrong things to hide. DESIGN.md: Starting shows the last
    // log line as it goes, "because that is where a missing DLL surfaces". The
    // checklist scrolls; the line that explains it stays put.
    val line = state.lastLine
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
 * The status cell: one 16 dp square whatever the state, so the labels beside it
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

/** Six rows with details, and no more of a landscape window than a dialog may take. */
private val CHECKLIST_MAX = 260.dp

// — Exited and Failed ----------------------------------------------------------

/**
 * Why the session ended — when there is a why.
 *
 * **A clean exit shows nothing and this composable is never called for one.**
 * "The Windows desktop closed · exit code 0" told the user the thing they had
 * just done and then made them tap Close to acknowledge it. Exit code 0 returns
 * silently to wherever they were; see [app.vessel.ui.VesselApp], which clears the
 * runtime instead of calling this.
 *
 * A non-zero exit and a FAILED launch still get it, and must: that is the
 * diagnosis, and swallowing it would leave a container that fell over
 * indistinguishable from one the user closed. The checklist comes with it —
 * [ProvisionChecklist] is the attribution, which of six steps got that far — so
 * the dialog carries the same evidence the old screen did.
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
 * The one destination the session has: a full-bleed surface with a rail swiped in
 * from the left edge.
 *
 * **A rail, not a bottom sheet.** This screen is usually landscape, where
 * vertical space is scarce and horizontal space is not, and a sheet spends the
 * scarce one.
 *
 * **The rail hugs.** It was `fillMaxHeight` with its content at the top, which
 * over a portrait desktop meant a column two thirds of which was an empty
 * translucent slab covering the guest's own window. It is now sized by what is in
 * it and centred against the screen's vertical middle, so the amount of the
 * desktop it hides is the amount it needs.
 */
@Composable
fun SessionDesktop(
    state: SessionState,
    surface: View?,
    pointerMode: PointerMode,
    onOpenLogs: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onTogglePointerMode: () -> Unit,
    onShowKeyboard: () -> Unit,
    onOpenFiles: () -> Unit,
    /**
     * The sampler's window, collected only while the rail is open.
     *
     * Passed as the flow rather than as a collected value on purpose: collecting
     * is what raises the sample rate from 0.1 Hz to 1 Hz, so the collection has
     * to start and stop with the rail's own composition, not with this screen's.
     * Null in previews.
     */
    metrics: Flow<SessionMetricsState>? = null,
) {
    var railOpen by remember { mutableStateOf(false) }
    var confirmingStop by remember { mutableStateOf(false) }

    // Back closes the rail if it is open, and otherwise leaves the screen — the
    // session keeps running, because the foreground service owns it and not this
    // composable.
    //
    // It used to *open* the rail unconditionally, from a time when the rail had
    // no other way in. That made Back a trap: every press reopened the rail and
    // nothing could ever leave a running session. The rail has a full-height edge
    // handle now, so the gesture is free to mean what it means everywhere else.
    // Stopping the container is still only ever the Stop button.
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

/**
 * One card, three bands: the session's own controls, its telemetry, its modes.
 *
 * **It scrolls.** Four graphs, a header and five actions come to a little over
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
            .width(RAIL_WIDTH)
            // Insets, then the margin, then the card: the rail is centred
            // vertically but a tall one still has to clear the status and gesture
            // bars at its ends.
            .systemBarsPadding()
            .padding(Vessel.metrics.s6)
            // The one place this design permits translucency: the rail sits over
            // the guest's own output, and an opaque slab would hide the thing it
            // is a control for.
            .vCard(
                fill = Vessel.colors.surface.copy(alpha = 0.92f),
                elevation = VElev.md,
            )
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
        "${state.containerName.ifBlank { "Session" }} · ${if (state.paused) "paused" else "running"}",
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
 * **The lifetime controls are last because they end the thing above them.** They
 * were briefly at the top right, where a window's controls live on a desktop —
 * but this rail is a column read top to bottom, not a window, and putting the
 * destructive action first meant the first thing under the reader's thumb was the
 * one that closes everything.
 *
 * They stay bare glyphs while everything above them is a labelled row, and that
 * asymmetry is deliberate: a play triangle and a cross are the two icons in this
 * product that carry their meaning without a word, and giving them labels would
 * make six identical rows in which the destructive one is distinguished only by
 * being slightly red.
 *
 * The buttons stay **square** and the *columns* are what divide evenly: each sits
 * centred in a `weight(1f)` box. Passing the weight to `VIconAction` directly
 * does nothing useful — it applies `.size()` after the caller's modifier, so the
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
                icon = if (state.paused) Icons.Filled.PlayArrow else VIcons.Pause,
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
                icon = Icons.Filled.Close,
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
 * They were labelled full-width rows, which read well and cost four rows of a
 * column that also has to hold the metrics. As a single row the rail is shorter
 * than the graphs above it, which is the right proportion for something floating
 * over a desktop the user is trying to see.
 *
 * Each column keeps a **caption under its glyph**. The labels are not decoration:
 * the first thing asked of the earlier icon-only grid was what the icons were,
 * and a glyph can carry an action a user already knows but cannot introduce one.
 * Two words at `overline` size buy that back for eleven dp of height.
 *
 * Pointer mode's caption is the mode it will switch *to*, matching its glyph —
 * an icon alone on a two-state control is a coin toss, and losing that bet
 * mid-game means the cursor stops behaving.
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
            icon = if (trackpad) VIcons.TouchApp else VIcons.Mouse,
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
            VIcons.Folder, "Files", "Open the file manager on C:",
            onOpenFiles, Modifier.weight(1f),
        )
        RailAction(
            Icons.AutoMirrored.Filled.List, "Log", "Open the session log",
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
 * 178 dp leaves 150 dp inside the card, which is what the labelled action rows
 * need — a 18 dp glyph, an 8 dp gap and `Session log` at 12.5 sp with room to
 * spare — and gives each sparkline a 138 dp box, wide enough that a 60-sample
 * window is more than two pixels a sample. It was 162 dp when the rail held a
 * two-column number grid and four unlabelled squares; both are gone.
 */
private val RAIL_WIDTH = 212.dp

// — previews -------------------------------------------------------------------
//
// Fixed states, and only here. The real thing reads the runtime, so a device with
// nothing installed shows the failure rather than a plausible-looking run.

private val PreviewSteps = listOf(
    ProvisionStep("session:components", "Resolve components", ProvisionStatus.DONE, "Wine/1114 · DXVK/271"),
    ProvisionStep("session:fex", "Install FEX", ProvisionStatus.DONE, "libarm64ecfex.dll — 2 file(s) copied into the prefix"),
    ProvisionStep("session:d3d", "Install D3D layers", ProvisionStatus.DONE, "DXVK, vkd3d — 18 file(s) copied into the prefix"),
    ProvisionStep("layout", "Create prefix", ProvisionStatus.SKIPPED, "Already created"),
    ProvisionStep("registry", "First-run registry", ProvisionStatus.DONE, "4 keys written to prefix-seed.reg"),
    ProvisionStep("boot", "Initialise Wine prefix", ProvisionStatus.RUNNING),
)

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 620)
@Composable
private fun SessionLaunchDialogPreview() {
    VesselTheme {
        SessionLaunchDialog(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
                phase = SessionPhase.PREPARING,
                steps = PreviewSteps,
                lastLine = "loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\dxgi.dll\"",
                geometry = DisplayGeometry(1280, 720),
            ),
            onCancel = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 620)
@Composable
private fun SessionFailedDialogPreview() {
    VesselTheme {
        SessionOutcomeDialog(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
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

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 824, heightDp = 392)
@Composable
private fun SessionDesktopPreview() {
    VesselTheme {
        SessionDesktop(
            state = SessionState(
                containerId = "c1",
                containerName = "Default",
                phase = SessionPhase.RUNNING,
                geometry = DisplayGeometry(1280, 720),
            ),
            surface = null,
            pointerMode = PointerMode.TRACKPAD,
            onOpenLogs = {},
            onStop = {},
            onTogglePause = {},
            onTogglePointerMode = {},
            onShowKeyboard = {},
            onOpenFiles = {},
        )
    }
}
