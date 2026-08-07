package app.vessel.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.vessel.ui.components.VNavDestination
import app.vessel.ui.screens.AppProfileScreen
import app.vessel.ui.screens.AppsScreen
import app.vessel.ui.screens.BenchmarkScreen
import app.vessel.ui.screens.ComponentsScreen
import app.vessel.ui.screens.ContainerEditorScreen
import app.vessel.ui.screens.ContainersScreen
import app.vessel.ui.screens.DiagnosticsScreen
import app.vessel.ui.screens.DriversScreen
import app.vessel.ui.screens.FilesScreen
import app.vessel.ui.screens.NEW_CONTAINER
import app.vessel.ui.screens.SessionScreen
import app.vessel.ui.screens.SettingsScreen

object Routes {
    const val CONTAINERS = "containers"
    const val APPS = "apps"
    const val COMPONENTS = "components"
    const val SETTINGS = "settings"

    const val CONTAINER_EDITOR = "containerEditor/{containerId}"
    const val SESSION = "session/{containerId}"
    const val APP_PROFILE = "appProfile/{appId}"
    const val BENCHMARK = "benchmark"
    const val DRIVERS = "drivers"
    const val DIAGNOSTICS = "diagnostics"
    const val FILES = "files"

    /** Null is "create one", which the editor is told by [NEW_CONTAINER] rather than by a flag. */
    fun containerEditor(containerId: String? = null) =
        "containerEditor/${Uri.encode(containerId ?: NEW_CONTAINER)}"

    fun session(containerId: String) = "session/${Uri.encode(containerId)}"

    fun appProfile(appId: String) = "appProfile/${Uri.encode(appId)}"

    const val ARG_CONTAINER_ID = "containerId"
    const val ARG_APP_ID = "appId"
}

/**
 * Three roots and no more.
 *
 * Everything else in the product — the editor, the session, components,
 * benchmarks, drivers, diagnostics, files — is pushed on top of one of these. A
 * fourth tab is the first sign that a screen has been added instead of designed.
 *
 * Components deliberately is not a root. Vessel ships one current build of each
 * component, compiled for this device, so there is nothing to browse and no
 * choice to make: the set is provisioned on first run and updated from a row in
 * Settings. A store front would be UI for a decision the product does not ask
 * the user to make.
 *
 * TODO: Material icons stand in for the bespoke set DESIGN.md implies.
 */
val BottomDestinations = listOf(
    VNavDestination("Containers", Icons.Filled.Home, Routes.CONTAINERS),
    VNavDestination("Apps", Icons.AutoMirrored.Filled.List, Routes.APPS),
    VNavDestination("Settings", Icons.Filled.Settings, Routes.SETTINGS),
)

@Composable
fun VesselApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavHost(
        navController = navController,
        startDestination = Routes.CONTAINERS,
        modifier = modifier,
    ) {
        composable(Routes.CONTAINERS) {
            ContainersScreen(
                currentRoute = currentRoute,
                onNavigate = { navController.navigateToRoot(it) },
                onOpenContainer = { navController.navigate(Routes.containerEditor(it)) },
                onCreateContainer = { navController.navigate(Routes.containerEditor()) },
                onLaunch = { navController.navigate(Routes.session(it)) },
            )
        }
        composable(Routes.APPS) {
            AppsScreen(
                currentRoute = currentRoute,
                onNavigate = { navController.navigateToRoot(it) },
                onOpenFiles = { navController.navigate(Routes.FILES) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                currentRoute = currentRoute,
                onNavigate = { navController.navigateToRoot(it) },
                onOpenComponents = { navController.navigate(Routes.COMPONENTS) },
                onOpenDrivers = { navController.navigate(Routes.DRIVERS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenBenchmark = { navController.navigate(Routes.BENCHMARK) },
            )
        }

        // — pushes —
        composable(Routes.COMPONENTS) {
            ComponentsScreen(
                onBack = { navController.popBackStack() },
                // TODO: hands off to the download service once it does anything.
                onInstall = {},
                onRefresh = {},
            )
        }

        composable(Routes.CONTAINER_EDITOR) { entry ->
            ContainerEditorScreen(
                containerId = entry.arguments?.getString(Routes.ARG_CONTAINER_ID) ?: NEW_CONTAINER,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SESSION) { entry ->
            SessionScreen(
                containerId = entry.arguments?.getString(Routes.ARG_CONTAINER_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.APP_PROFILE) { entry ->
            AppProfileScreen(
                appId = entry.arguments?.getString(Routes.ARG_APP_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BENCHMARK) { BenchmarkScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.DRIVERS) { DriversScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.FILES) { FilesScreen(onBack = { navController.popBackStack() }) }
    }
}

/** Roots are singletons: switching tabs restores rather than stacks. */
private fun NavHostController.navigateToRoot(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
