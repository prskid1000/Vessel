package app.vessel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vessel.data.ProvisionStatus
import app.vessel.data.ProvisionStep
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing

/**
 * The two dialogs in this product that report *progress* rather than an outcome,
 * and the parts they share.
 *
 * There are exactly two — the session launch checklist and first-run component
 * setup — and they were written a long way apart. They draw the same thing: a
 * card over whatever is behind it, a list of named steps with a status glyph
 * each, and a detail line under any step that has something to say. Keeping two
 * copies of that is how the second one ends up with a different tick.
 *
 * [VOutcomeDialog] is deliberately not the host for either. Its `evidence` is a
 * `List<String>` on purpose — the raw material a bug report quotes — and widening
 * it to a composable slot would let any caller put a scrolling layout inside a
 * dialog meant to hold three lines of mono.
 */

/**
 * The card a progress dialog is drawn on: scrim, gutter, cap, ring, padding.
 *
 * `usePlatformDefaultWidth = false` because the platform's 280 dp card is
 * narrower than this product's gutter and wraps a two-line message to four; the
 * cap that replaces it is [Vessel.metrics.dialogMaxWidth], because handing the
 * dialog the whole window means 927 dp of card in landscape.
 */
@Composable
fun VDialogCard(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
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
 * One checklist row: a status cell, a label, and what happened under it.
 *
 * The point of the whole component is attribution. When something fails, the row
 * it failed on is the diagnosis; a spinner turns six distinguishable failures
 * into one.
 */
@Composable
fun VStepRow(step: ProvisionStep, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        VStepGlyph(step.status)
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
 * The status cell: one square whatever the state, so the labels beside it stay on
 * one left edge instead of stepping in and out as rows complete.
 */
@Composable
fun VStepGlyph(status: ProvisionStatus) {
    val size = Vessel.metrics.iconStatus
    Box(
        Modifier.padding(top = Vessel.metrics.s3).size(size),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            ProvisionStatus.DONE ->
                Icon(VIcons.Check, null, Modifier.size(size), tint = Vessel.colors.ok)

            ProvisionStatus.SKIPPED ->
                Icon(VIcons.Check, null, Modifier.size(size), tint = Vessel.colors.textMuted)

            ProvisionStatus.FAILED ->
                Icon(VIcons.X, null, Modifier.size(size), tint = Vessel.colors.danger)

            // Running is a filled accent dot and pending an empty ring — the same
            // shape at two weights, which reads as progress down the column
            // without a second animation competing with whatever is below it.
            ProvisionStatus.RUNNING ->
                Box(
                    Modifier
                        .size(Vessel.metrics.dot)
                        .background(Vessel.colors.accent, CircleShape),
                )

            ProvisionStatus.PENDING ->
                Box(
                    Modifier
                        .size(Vessel.metrics.dot)
                        .vRing(Vessel.colors.divider, CircleShape),
                )
        }
    }
}

/**
 * A determinate bar, and only ever a determinate one.
 *
 * There is no indeterminate variant here on purpose: a bar that sweeps back and
 * forth is a spinner wearing a bar's clothes, and this product's rule is that
 * progress is either measured or described in words. Where a fraction is not
 * available, the checklist rows say what is happening instead.
 *
 * Drawn as two boxes rather than with `LinearProgressIndicator`, which brings
 * Material's own track colour, its own height and — since Material3 1.3 — a gap
 * and a stop indicator that belong to a different design system.
 */
@Composable
fun VProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    val shape = Vessel.metrics.shapeSm
    Box(
        modifier
            .fillMaxWidth()
            .height(Vessel.metrics.progressTrack)
            .background(Vessel.colors.divider, shape),
    ) {
        // A measured fill rather than `fillMaxWidth(clamped)`, so a fraction of a
        // dp still paints: `fillMaxWidth(0.004f)` rounds to zero pixels and the
        // first few seconds of a 900 MB unpack look like nothing is happening.
        Layout(
            content = {
                Box(Modifier.fillMaxWidth().height(Vessel.metrics.progressTrack).background(Vessel.colors.accent, shape))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val width = (constraints.maxWidth * clamped).toInt().coerceAtLeast(if (clamped > 0f) 1 else 0)
            val placeable = measurables.first().measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
        }
    }
}
