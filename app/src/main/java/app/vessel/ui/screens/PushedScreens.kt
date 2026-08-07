package app.vessel.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.theme.VesselTheme

/**
 * The two destinations that are routed but not built.
 *
 * Each says, in the first clause, that it is not implemented — and then what it
 * will do. That order matters. A screen that only describes its future reads as
 * a screen that has failed to load its data, and a blank one reads as a crash;
 * either way the person holding the phone spends time deciding whether the app
 * is broken. Saying "not built yet" costs one clause and settles the question.
 *
 * A stub earns its place only while something real is coming. Where that stops
 * being true, delete the route rather than leave a screen whose only content is
 * an apology.
 */
@Composable
private fun NotBuiltYet(
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
fun AppProfileScreen(appId: String, onBack: () -> Unit) {
    NotBuiltYet(
        title = "App profile",
        subtitle = appId,
        icon = Icons.Filled.Settings,
        message = "Not built yet. This will hold per-executable overrides — engine, pinned " +
            "component versions, memory ordering and launch arguments — along with the " +
            "architecture read from the PE header and how it was determined.",
        onBack = onBack,
    )
}

@Composable
fun FilesScreen(onBack: () -> Unit) {
    NotBuiltYet(
        title = "Files",
        subtitle = null,
        icon = Icons.Filled.Build,
        message = "Not built yet. It will browse the drives inside a container, import and " +
            "export files, and show the folders shared with Android storage. No container " +
            "has a drive to browse until a Wine build is installed.",
        onBack = onBack,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun NotBuiltYetPreview() {
    VesselTheme {
        FilesScreen(onBack = {})
    }
}
