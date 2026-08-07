package app.vessel.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold

/**
 * The six pushed destinations, as routes with a frame and nothing in them yet.
 *
 * They exist so the navigation graph in `ui/Navigation.kt` is complete and
 * every root's affordances go somewhere, rather than being commented out until
 * the screen behind them is written. Each says what it will be; none pretends
 * to work.
 */
@Composable
private fun PushedStub(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    message: String,
    onBack: () -> Unit,
) {
    VScaffold(
        toolbar = { VPushToolbar(title = title, onBack = onBack, subtitle = subtitle) },
    ) {
        VEmptyState(icon = icon, message = message)
    }
}

@Composable
fun ContainerEditorScreen(containerId: String, onBack: () -> Unit) {
    PushedStub(
        title = if (containerId == NEW_CONTAINER) "New container" else "Edit container",
        subtitle = containerId,
        icon = Icons.Filled.Settings,
        message = "The architecture profile picker, Wine build, driver, D3D layer and the full " +
            "parameter surface rendered from assets/params-manifest.json.",
        onBack = onBack,
    )
}

@Composable
fun SessionScreen(containerId: String, onBack: () -> Unit) {
    PushedStub(
        title = "Session",
        subtitle = containerId,
        icon = Icons.Filled.PlayArrow,
        message = "The Vulkan surface for the running Windows desktop, with the edge-swipe " +
            "overlay carrying the metric strip, profile switch, input mode and kill switch.",
        onBack = onBack,
    )
}

@Composable
fun AppProfileScreen(appId: String, onBack: () -> Unit) {
    PushedStub(
        title = "App profile",
        subtitle = appId,
        icon = Icons.Filled.Settings,
        message = "Per-executable overrides: engine, pinned component versions, memory ordering " +
            "and launch arguments, plus how the architecture was detected.",
        onBack = onBack,
    )
}

@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    PushedStub(
        title = "Benchmark",
        subtitle = null,
        icon = Icons.Filled.PlayArrow,
        message = "A standard workload against the current configuration, stored and comparable " +
            "run to run. This is what turns an engine argument into a measurement.",
        onBack = onBack,
    )
}

@Composable
fun DriversScreen(onBack: () -> Unit) {
    PushedStub(
        title = "Drivers",
        subtitle = null,
        icon = Icons.Filled.Build,
        message = "Installed GPU drivers, what each reports at runtime, per-container " +
            "assignment, and a warning when one does not claim support for this GPU.",
        onBack = onBack,
    )
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    PushedStub(
        title = "Diagnostics",
        subtitle = null,
        icon = Icons.Filled.Info,
        message = "Wine, FEX, Box64 and Turnip output in a log pane, the device capability " +
            "report, and a one-tap export bundle for a bug report.",
        onBack = onBack,
    )
}

@Composable
fun FilesScreen(onBack: () -> Unit) {
    PushedStub(
        title = "Files",
        subtitle = null,
        icon = Icons.Filled.Build,
        message = "Browse container drives, import and export, and the folders shared with " +
            "Android storage.",
        onBack = onBack,
    )
}

/** The id a container editor is given when there is no container yet. */
const val NEW_CONTAINER = "new"
