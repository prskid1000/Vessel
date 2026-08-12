package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.data.ProvisionStatus
import app.vessel.data.ProvisionStep
import app.vessel.data.SetupPhase
import app.vessel.data.SetupState
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VDialogCard
import app.vessel.ui.components.VProgressBar
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VStepRow
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme

/**
 * First run: what is being unpacked, how far through it is, and nothing to decide.
 *
 * **It is a report, not a gate.** The components are already inside the APK and
 * there is no choice a user could make about them, so this dialog exists for one
 * reason: unpacking roughly a gigabyte takes minutes, and a container list that
 * sits there silently for minutes looks broken. Hide puts the report down and
 * changes nothing about the work — the same distinction [SessionLaunchDialog]
 * draws between Hide and Cancel, except that here there is no Cancel, because
 * cancelling would leave the app unable to do the only thing it is for.
 *
 * A checklist rather than a bar alone, for the reason the launch checklist gives:
 * when something fails, the row it failed on is the diagnosis. Here that matters
 * more than usual, because the realistic failure is running out of space part-way
 * through, and "setup failed" would leave a user with no idea whether they need
 * 40 MB free or 900.
 *
 * The bar is over compressed bytes and is honest about it — see
 * [app.vessel.data.SetupState.fraction]. Wine is 88% of the bundle's bytes and
 * one sixth of its rows, so a bar over rows would sit at 17% for four minutes and
 * then finish in ten seconds.
 *
 * Portrait is the only orientation this is ever seen in — `ui/OrientationLock.kt`
 * pins everything but the running desktop — and it is a capped, gutter-padded
 * card with a scrolling middle, so a landscape window over a foldable gets a
 * dialog rather than a metre of card.
 */
@Composable
fun SetupDialog(state: SetupState, onDismiss: () -> Unit) {
    val failed = state.phase == SetupPhase.INCOMPLETE
    VDialogCard(onDismiss = onDismiss) {
        Text(
            if (failed) "Setup did not finish" else "Setting up",
            style = Vessel.type.subtitle,
            color = if (failed) Vessel.colors.danger else Vessel.colors.textPrimary,
        )
        Text(
            if (failed) {
                "Vessel installed what it could. The components below are missing, and " +
                    "anything that needs them will say so rather than half-work."
            } else {
                "Unpacking the components that came with the app. This happens once, and " +
                    "it does not need the network."
            },
            style = Vessel.type.body,
            color = Vessel.colors.textLabel,
        )

        if (!failed) {
            VProgressBar(state.fraction)
            Text(
                "${percent(state.fraction)} · ${megabytes(state.completedBytes + state.currentBytes)}" +
                    " of ${megabytes(state.totalBytes)} read",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = Vessel.metrics.checklistMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            VSectionHeader("Components")
            state.steps.forEach { VStepRow(it) }
        }

        // The one sentence that turns a failure into something a person can act
        // on. Derived from the failures actually present rather than written for
        // the likeliest one, so it never advises freeing space over a corrupt
        // package.
        remedy(state)?.let {
            Text(it, style = Vessel.type.bodySmall, color = Vessel.colors.textLabel)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8, Alignment.End),
        ) {
            VButton(
                if (failed) "Continue" else "Hide",
                onDismiss,
                style = if (failed) VButtonStyle.Primary else VButtonStyle.Secondary,
            )
        }
    }
}

/**
 * What would fix it, in one sentence, or null when nothing failed.
 *
 * Two cases and no third. Space is the one a user can do something about, so it
 * says the number; anything else is a broken package inside the APK, which is a
 * build problem and is named as one rather than dressed up as something the user
 * could retry into working.
 */
private fun remedy(state: SetupState): String? {
    val failures = state.failures
    if (failures.isEmpty()) return null
    val space = failures.any { it.detail?.startsWith(NOT_ENOUGH_SPACE) == true }
    return if (space) {
        "Free up space on the device and reopen Vessel — setup carries on from where it " +
            "stopped, and the components already installed are kept."
    } else {
        "This is a problem with the app package rather than with the device: these " +
            "components cannot be unpacked from this build of Vessel at all."
    }
}

/** The prefix `WcpInstallResult.InsufficientSpace` writes. Matched, not re-worded. */
private const val NOT_ENOUGH_SPACE = "Not enough space"

private fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"

private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

// — previews -------------------------------------------------------------------

private val PreviewSteps = listOf(
    ProvisionStep(
        "wine-proton-11.0-canoe",
        "Install Wine proton-11.0",
        ProvisionStatus.RUNNING,
        "412 MB unpacked · 2183 files",
    ),
    ProvisionStep("dxvk-2.7.1-canoe", "Install DXVK 2.7.1", ProvisionStatus.PENDING),
    ProvisionStep("vkd3d-3.0.1-canoe", "Install vkd3d 3.0.1", ProvisionStatus.PENDING),
    ProvisionStep(
        "zink-26.3.0-devel-9c475fc3-canoe",
        "Install OpenGL 26.3.0-devel-9c475fc3",
        ProvisionStatus.PENDING,
    ),
    ProvisionStep(
        "turnip-26.3.0-devel-9c475fc3-canoe",
        "Install Turnip 26.3.0-devel-9c475fc3",
        ProvisionStatus.PENDING,
    ),
    ProvisionStep("fex-2608-canoe", "Install FEX 2608", ProvisionStatus.PENDING),
)

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 620)
@Composable
private fun SetupDialogPreview() {
    VesselTheme {
        SetupDialog(
            state = SetupState(
                phase = SetupPhase.INSTALLING,
                steps = PreviewSteps,
                completedBytes = 0,
                currentBytes = 38L * 1024 * 1024,
                totalBytes = 100L * 1024 * 1024,
            ),
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 620)
@Composable
private fun SetupDialogIncompletePreview() {
    VesselTheme {
        SetupDialog(
            state = SetupState(
                phase = SetupPhase.INCOMPLETE,
                steps = PreviewSteps.map {
                    when (it.id) {
                        "wine-proton-11.0-canoe" -> it.copy(
                            status = ProvisionStatus.FAILED,
                            detail = "Not enough space: needs at least 640 MB, 61 MB free",
                        )

                        else -> it.copy(status = ProvisionStatus.DONE, detail = "already in the shared store")
                    }
                },
                totalBytes = 100L * 1024 * 1024,
            ),
            onDismiss = {},
        )
    }
}
