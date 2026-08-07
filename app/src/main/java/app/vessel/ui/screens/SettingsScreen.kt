package app.vessel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.ui.BottomDestinations
import app.vessel.ui.Routes
import app.vessel.ui.components.VBottomNav
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/**
 * Root 4 — storage, theme, and the way into Diagnostics. Three things.
 *
 * Deliberately *not* here, and each was here once:
 *
 *  - **The registry URL.** Build plumbing. A user has no reason to change where
 *    `contents.json` is fetched from and no way to evaluate a different value,
 *    so the row was a readout dressed as a setting. If it ever needs to be
 *    visible it belongs in Diagnostics, next to the other facts about how this
 *    build was assembled.
 *  - **The update channel.** Same argument, and worse: it read `BuildConfig`,
 *    so it could not be changed at all. A control that cannot control anything
 *    teaches the user that this whole screen is decorative.
 *  - **About and credits.** Credits live in `CREDITS.md` in the repository,
 *    which is where anyone who cares about them already is. A version string on
 *    a settings screen exists for the person writing a bug report, and the
 *    export bundle in Diagnostics already carries it.
 *
 * TODO: storage and theme are readouts. Both need stores that do not exist yet;
 *  neither pretends to work.
 */
@Composable
fun SettingsScreen(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenDrivers: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenComponents: () -> Unit,
) {
    VScaffold(
        toolbar = { VRootToolbar(title = "Settings") },
        bottomBar = { VBottomNav(BottomDestinations, currentRoute) { onNavigate(it.route) } },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            VSectionHeader("Storage")
            SettingsRow("Location", "internal · Android/data/app.vessel")
            SettingsRow("Containers", "1 container · 612 MiB")

            // Freestanding rules between groups: they fade at both ends, which
            // is the Nocturne signature a plain divider would lose.
            // No Appearance section: the product is dark-only by design, so a
            // theme row would be a control that offers no choice.

            VRule()

            VSectionHeader("Device")
            SettingsRow("Components", "6 builds installed · compiled for this device", onOpenComponents)
            SettingsRow("GPU drivers", "installed and per-container assignment", onOpenDrivers)
            SettingsRow("Benchmark", "measure a configuration instead of arguing about it", onOpenBenchmark)
            SettingsRow("Diagnostics", "logs, capability report, export bundle", onOpenDiagnostics)
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Vessel.metrics.s11),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = Vessel.type.body)
            Text(value, style = Vessel.type.mono, color = Vessel.colors.textMuted)
        }
        if (onClick != null) {
            Text("→", style = Vessel.type.body, color = Vessel.colors.accent)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SettingsPreview() {
    VesselTheme {
        SettingsScreen(
            currentRoute = Routes.SETTINGS,
            onNavigate = {},
            onOpenDrivers = {},
            onOpenDiagnostics = {},
            onOpenBenchmark = {},
            onOpenComponents = {},
        )
    }
}
