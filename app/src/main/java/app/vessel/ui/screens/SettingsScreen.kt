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
import app.vessel.BuildConfig
import app.vessel.ui.BottomDestinations
import app.vessel.ui.Routes
import app.vessel.ui.components.VBottomNav
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard

/**
 * Root 4 — registry, channel, storage, diagnostics, about.
 *
 * TODO: every row here is a readout. Editing the registry URL, choosing an
 *  update channel and reporting real storage all need the stores that do not
 *  exist yet.
 */
@Composable
fun SettingsScreen(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenDrivers: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBenchmark: () -> Unit,
) {
    VScaffold(
        toolbar = { VRootToolbar(title = "Settings") },
        bottomBar = { VBottomNav(BottomDestinations, currentRoute) { onNavigate(it.route) } },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            VSectionHeader("Components")
            SettingsRow("Registry", "registry/contents.json")
            SettingsRow("Update channel", BuildConfig.UPDATE_CHANNEL.lowercase())

            VSectionHeader("Device")
            SettingsRow("GPU drivers", "installed and per-container assignment", onOpenDrivers)
            SettingsRow("Benchmark", "measure a configuration instead of arguing about it", onOpenBenchmark)
            SettingsRow("Diagnostics", "logs, capability report, export bundle", onOpenDiagnostics)

            VSectionHeader("About")
            SettingsRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            SettingsRow(
                "Credits",
                "Wine, Box64, FEX-Emu, Mesa/Turnip, DXVK, vkd3d-proton and the Winlator lineage",
            )
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
            .padding(Vessel.metrics.s12),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = Vessel.type.body)
            Text(value, style = Vessel.type.mono, color = Vessel.colors.textTertiary)
        }
        if (onClick != null) {
            Text("→", style = Vessel.type.body, color = Vessel.colors.accent)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F, widthDp = 392, heightDp = 824)
@Composable
private fun SettingsPreview() {
    VesselTheme {
        SettingsScreen(
            currentRoute = Routes.SETTINGS,
            onNavigate = {},
            onOpenDrivers = {},
            onOpenDiagnostics = {},
            onOpenBenchmark = {},
        )
    }
}
