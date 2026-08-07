package app.vessel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing

/**
 * Destructive confirmation.
 *
 * A centred panel rather than a bottom sheet, and that is a correctness decision
 * rather than a taste one. The bottom-anchored version was built first: a
 * `fillMaxSize` scrim with the panel aligned to its bottom edge. On device the
 * dialog window turned out to be positioned below the status bar while still
 * being given the full display height, so the panel's own bottom — buttons
 * included — hung off the screen. `decorFitsSystemWindows = false` did not move
 * it. A wrap-content panel has no such argument with the window manager: the
 * platform sizes the window to the content and centres it, and the buttons are
 * always reachable.
 *
 * Material's `ModalBottomSheet` is not used either — it is behind an opt-in in
 * this Compose version and arrives with its own container tone, scrim and drag
 * handle, none of which this system has a form for. What is left is `surface`,
 * the `lg` ring that `VElev` documents as dialog-only, and two outlined buttons:
 * the destructive one in `danger`, which is the only place that token appears on
 * an action.
 *
 * The panel names what will be removed. "Are you sure?" is not a question anyone
 * can answer; "Delete Canoe test?" is.
 */
@Composable
fun VConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The platform's default width is a fixed 280 dp card, which is narrower
        // than this product's gutter and wraps a two-line message to four.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = Vessel.metrics.screenGutter)) {
            val shape = Vessel.metrics.shapeLg
            Column(
                Modifier
                    .fillMaxWidth()
                    .vElevation(VElev.lg, shape)
                    .background(Vessel.colors.surface, shape)
                    .vRing(VElev.lg.ring, shape)
                    .padding(Vessel.metrics.s22),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
            ) {
                Text(title, style = Vessel.type.subtitle)
                Text(message, style = Vessel.type.body, color = Vessel.colors.textLabel)
                Row(
                    Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6),
                    horizontalArrangement = Arrangement.spacedBy(
                        Vessel.metrics.s8,
                        Alignment.End,
                    ),
                ) {
                    VButton("Cancel", onDismiss, style = VButtonStyle.Secondary)
                    VButton(confirmLabel, onConfirm, style = VButtonStyle.Danger)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 400)
@Composable
private fun VConfirmSheetPreview() {
    VesselTheme {
        VConfirmSheet(
            title = "Delete Canoe test?",
            message = "Its settings are removed from this device. Installed components are " +
                "shared and are not touched.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
