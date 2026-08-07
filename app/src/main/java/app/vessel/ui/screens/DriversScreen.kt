package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import app.vessel.data.DeviceNode
import app.vessel.data.SystemGpu
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VTag
import app.vessel.ui.components.VTagTone
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.vm.DriverAssignment
import app.vessel.ui.vm.DriverRow
import app.vessel.ui.vm.DriversUiState
import app.vessel.ui.vm.DriversViewModel

/**
 * Pushed 9 — the driver manager.
 *
 * Two sources, both real: the `.wcp` packages actually unpacked on this device,
 * and what the system driver answers when asked. There is no catalogue here, and
 * an empty installed list is shown as an empty installed list — listing a driver
 * this phone does not have would invite installing it, and a driver that does
 * not claim support for the GPU is a black screen rather than a fallback.
 */
@Composable
fun DriversScreen(
    onBack: () -> Unit,
    viewModel: DriversViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DriversContent(state, onBack)
}

@Composable
private fun DriversContent(state: DriversUiState, onBack: () -> Unit) {
    VScaffold(
        toolbar = {
            VPushToolbar(
                title = "Drivers",
                subtitle = if (state.loading) {
                    "reading the component set"
                } else {
                    "${state.drivers.size} installed"
                },
                onBack = onBack,
            )
        },
    ) {
        if (state.loading) return@VScaffold

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
        ) {
            item(key = "installed-header") { VSectionHeader("Installed drivers") }
            if (state.drivers.isEmpty()) {
                item(key = "installed-none") {
                    Note(
                        "No ${ComponentType.TURNIP.label} package is unpacked in this app's " +
                            "component directory, so there is no driver for a container to " +
                            "load. Vessel builds one for this GPU; installing it is the " +
                            "Components screen's job.",
                    )
                }
            } else {
                items(state.drivers.size, key = { state.drivers[it].pkg.id }) {
                    InstalledDriver(state.drivers[it])
                }
            }

            item(key = "rule-assign") { VRule() }
            item(key = "assign-header") { VSectionHeader("Container assignments") }
            if (state.assignments.isEmpty()) {
                item(key = "assign-none") {
                    Note("No containers exist yet, so nothing has asked for a driver.")
                }
            } else {
                items(
                    state.assignments.size,
                    key = { "assign-${state.assignments[it].containerName}-$it" },
                ) {
                    Assignment(state.assignments[it])
                }
            }

            item(key = "rule-system") { VRule() }
            item(key = "system-header") { VSectionHeader("System driver") }
            item(key = "system") { SystemDriver(state.system, state.kgsl) }
        }
    }
}

/** One installed package, with its provenance and who is using it. */
@Composable
private fun InstalledDriver(row: DriverRow) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.pkg.name,
                style = Vessel.type.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            VTag(row.pkg.target, tone = VTagTone.Accent)
        }
        Fact("id", row.pkg.id)
        Fact("version", row.pkg.versionName)
        Fact("src", row.pkg.sourceSha)
        Fact("flags", row.pkg.cpuFlags)
        Fact(
            "assigned",
            row.assignedTo.joinToString(", ").ifBlank { "no container" },
        )
    }
}

/**
 * What one container asked for and what it got.
 *
 * A selector that resolves to nothing is the row worth reading: the container is
 * configured for a driver this device does not have, which is a black screen
 * waiting to happen rather than a silent fallback to the system driver.
 */
@Composable
private fun Assignment(assignment: DriverAssignment) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        Text(assignment.containerName, style = Vessel.type.body)
        Fact("selector", assignment.selector)
        Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
            Text(
                "resolves",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.width(64.dp),
            )
            Text(
                assignment.resolvedId ?: "nothing installed",
                style = Vessel.type.mono,
                color = if (assignment.resolvedId != null) {
                    Vessel.colors.ok
                } else {
                    Vessel.colors.warn
                },
            )
        }
    }
}

/**
 * What the phone shipped.
 *
 * Asked of GLES rather than Vulkan because Android exposes no Java Vulkan query;
 * the same Adreno userspace answers both, so the renderer string is the system
 * driver's own name for itself either way.
 */
@Composable
private fun SystemDriver(system: SystemGpu?, kgsl: DeviceNode) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        if (system?.error != null) {
            Fact("probe", "failed: ${system.error}")
        } else {
            Fact("renderer", system?.renderer ?: "not reported")
            Fact("vendor", system?.vendor ?: "not reported")
            Fact("gl", system?.glVersion ?: "not reported")
        }
        Fact("kgsl", kgsl.label)
        Text(
            "This is the driver Android ships. It is what a container falls back to, and it is " +
                "missing extensions most Direct3D translation needs — which is why Vessel " +
                "builds Turnip for this GPU.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(top = Vessel.metrics.s6),
        )
    }
}

@Composable
private fun Fact(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
        Text(
            key,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = Vessel.type.mono, color = Vessel.colors.textLabel)
    }
}

/** A plain sentence where a list would be, when the list is empty for a reason. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = Vessel.type.body,
        color = Vessel.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(bottom = Vessel.metrics.s8),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun DriversPreview() {
    VesselTheme {
        // Preview data. The screen reads the component directory and the device.
        DriversContent(
            state = DriversUiState(
                loading = false,
                drivers = listOf(
                    DriverRow(
                        pkg = ComponentPackage(
                            id = "turnip-25.2.0-gen8-canoe",
                            type = ComponentType.TURNIP,
                            name = "Turnip gen8",
                            versionName = "25.2.0",
                            versionCode = 250_200,
                            sizeBytes = 29_100_000,
                            installed = true,
                            target = "canoe",
                            sourceSha = "6b0f83ce1d47",
                            cpuFlags = "-mcpu=oryon-1",
                        ),
                        assignedTo = listOf("Container"),
                    ),
                ),
                assignments = listOf(
                    DriverAssignment("Container", "@latest", "turnip-25.2.0-gen8-canoe"),
                ),
                system = SystemGpu(
                    renderer = "Adreno (TM) 829",
                    vendor = "Qualcomm",
                    glVersion = "OpenGL ES 3.2",
                ),
                kgsl = DeviceNode.PRESENT,
            ),
            onBack = {},
        )
    }
}
