package app.vessel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing

/** Whether the outcome being reported is a failure or just an ending. */
enum class VOutcomeTone { Danger, Neutral }

/**
 * How a session ended, over whatever was on screen.
 *
 * A dialog rather than a screen, and the reason is the thing it sits on top of.
 * "No Wine build is installed" is two lines and two buttons; given a whole
 * screen it is two lines, two buttons and 900 px of nothing, and — worse — it
 * *replaces* the provisioning checklist, which is the only thing that says which
 * of six steps got that far. Layering keeps the diagnosis and the evidence on
 * screen at the same time.
 *
 * [evidence] is the raw material a bug report quotes: the failing step's own
 * detail, the last error line, the exit code. It is mono, dimmed and scrollable,
 * because it is precise and unreadable — `err:virtual:map_image_into_view failed
 * to set 60000020 protection` is exactly true and tells almost nobody what
 * happened. [title] is what happened; this is what it said.
 */
@Composable
fun VOutcomeDialog(
    title: String,
    onDismiss: () -> Unit,
    tone: VOutcomeTone = VOutcomeTone.Neutral,
    detail: String? = null,
    evidence: List<String> = emptyList(),
    actions: @Composable RowScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Same reasoning as VConfirmSheet: the platform's 280 dp card is
        // narrower than this product's gutter and wraps a two-line message to
        // four.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Capped as well as gutter-padded. `usePlatformDefaultWidth = false`
        // hands the dialog the whole window, which in landscape on this phone
        // is 927 dp — a two-line message set across a metre of card.
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
            ) {
                Text(
                    title,
                    style = Vessel.type.subtitle,
                    color = when (tone) {
                        VOutcomeTone.Danger -> Vessel.colors.danger
                        VOutcomeTone.Neutral -> Vessel.colors.textPrimary
                    },
                )
                detail?.let { Text(it, style = Vessel.type.body, color = Vessel.colors.textLabel) }

                if (evidence.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // Capped and scrollable: a Wine backtrace is not
                            // three lines, and a dialog that grows past the
                            // screen puts its own buttons out of reach.
                            .heightIn(max = EVIDENCE_MAX)
                            .vCard()
                            .verticalScroll(rememberScrollState())
                            .padding(Vessel.metrics.s11),
                        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
                    ) {
                        evidence.forEach {
                            Text(it, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6),
                    horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8, Alignment.End),
                    content = actions,
                )
            }
        }
    }
}

private val EVIDENCE_MAX = 180.dp

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 460)
@Composable
private fun VOutcomeDialogFailedPreview() {
    VesselTheme {
        VOutcomeDialog(
            title = "Wine could not make its own code executable",
            tone = VOutcomeTone.Danger,
            detail = "The loader mapped a PE section and the kernel refused to mark it " +
                "executable, so nothing inside the prefix can run.",
            evidence = listOf(
                "wineboot exited with 1",
                "err:virtual:map_image_into_view failed to set 60000020 protection on " +
                    "ntdll.dll section .text, noexec filesystem?",
                "exit code 1",
            ),
            onDismiss = {},
            actions = {
                VButton("View log", {}, style = VButtonStyle.Secondary)
                VButton("Retry", {}, style = VButtonStyle.Primary)
            },
        )
    }
}
