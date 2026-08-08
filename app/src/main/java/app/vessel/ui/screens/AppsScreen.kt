package app.vessel.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.BottomDestinations
import app.vessel.ui.Routes
import app.vessel.ui.components.VBottomNav
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.theme.VesselTheme

/**
 * Root 2 — every Windows application found across all containers.
 *
 * TODO: the real screen is a tile grid with icons extracted from PE resources,
 *  a VArchBadge per tile, filters by architecture and container, and long-press
 *  to pin to the Android home screen. It needs the container store and the PE
 *  reader first, so this is the empty state and the route.
 */
@Composable
fun AppsScreen(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    VScaffold(
        toolbar = { VRootToolbar(title = "Apps") },
        bottomBar = { VBottomNav(BottomDestinations, currentRoute) { onNavigate(it.route) } },
    ) {
        VEmptyState(
            icon = Icons.AutoMirrored.Filled.List,
            message = "No Windows applications found. Import an installer or an .exe into a " +
                "container drive and it will appear here with the architecture it was built for.",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun AppsPreview() {
    VesselTheme {
        AppsScreen(currentRoute = Routes.APPS, onNavigate = {})
    }
}
