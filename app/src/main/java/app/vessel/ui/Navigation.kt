package app.vessel.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
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
import app.vessel.ui.screens.ComponentsScreen
import app.vessel.ui.screens.ContainerEditorScreen
import app.vessel.ui.screens.ContainersScreen
import app.vessel.ui.screens.DriversScreen
import app.vessel.ui.screens.FilesScreen
import app.vessel.ui.screens.SessionLogScreen
import app.vessel.ui.screens.SessionLogsScreen
import app.vessel.ui.screens.SessionScreen
import app.vessel.ui.vm.NEW_CONTAINER

object Routes {
    const val CONTAINERS = "containers"
    const val APPS = "apps"
    const val COMPONENTS = "components"

    const val CONTAINER_EDITOR = "containerEditor/{containerId}"
    const val SESSION = "session/{containerId}"
    const val APP_PROFILE = "appProfile/{appId}"
    const val DRIVERS = "drivers"
    const val FILES = "files"

    /**
     * Logs hang off a container and are not a destination of their own.
     *
     * There is no global "Logs" screen and no bottom-nav entry for one, because
     * a log is a property of the run that produced it: a list of every session
     * across every container would be a list whose first column is the container
     * name, which is the shape of a screen that should have been two.
     */
    const val SESSION_LOGS = "logs/{containerId}"
    const val SESSION_LOG = "logs/{containerId}/{startedAt}"

    /** Null is "create one", which the editor is told by [NEW_CONTAINER] rather than by a flag. */
    fun containerEditor(containerId: String? = null) =
        "containerEditor/${Uri.encode(containerId ?: NEW_CONTAINER)}"

    fun session(containerId: String) = "session/${Uri.encode(containerId)}"

    fun appProfile(appId: String) = "appProfile/${Uri.encode(appId)}"

    fun sessionLogs(containerId: String) = "logs/${Uri.encode(containerId)}"

    fun sessionLog(containerId: String, startedAt: Long) =
        "logs/${Uri.encode(containerId)}/$startedAt"

    const val ARG_CONTAINER_ID = "containerId"
    const val ARG_APP_ID = "appId"
    const val ARG_STARTED_AT = "startedAt"
}

/**
 * Two roots and no more.
 *
 * Everything else — the editor, the session, components, drivers, files — is
 * pushed on top of one of these. The two here are the two things a user comes to
 * this app to do: pick a container, or pick a program.
 *
 * Settings and Components are deliberately not roots. Settings became a
 * destination whose only content was the names of other destinations, so its
 * links hang off the Containers overflow instead; Components has one current
 * build of each component and therefore nothing to browse.
 *
 * TODO: Material icons stand in for the bespoke set DESIGN.md implies.
 */
val BottomDestinations = listOf(
    VNavDestination("Containers", Icons.Filled.Home, Routes.CONTAINERS),
    VNavDestination("Apps", Icons.AutoMirrored.Filled.List, Routes.APPS),
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
                onOpenComponents = { navController.navigate(Routes.COMPONENTS) },
                onOpenDrivers = { navController.navigate(Routes.DRIVERS) },
            )
        }
        composable(Routes.APPS) {
            AppsScreen(
                currentRoute = currentRoute,
                onNavigate = { navController.navigateToRoot(it) },
                onOpenFiles = { navController.navigate(Routes.FILES) },
            )
        }

        // — pushes —
        composable(Routes.COMPONENTS) {
            ComponentsScreen(
                onBack = { navController.popBackStack() },
                // TODO: hands off to the download service once it does anything.
                onInstall = {},
            )
        }

        // The editor takes no id argument: it reads `containerId` off the route
        // through its SavedStateHandle, which is also how it survives the
        // process being killed with the screen open.
        composable(Routes.CONTAINER_EDITOR) { entry ->
            val containerId = entry.arguments?.getString(Routes.ARG_CONTAINER_ID).orEmpty()
            ContainerEditorScreen(
                onBack = { navController.popBackStack() },
                onOpenLogs = { navController.navigate(Routes.sessionLogs(containerId)) },
            )
        }

        // Both log routes take the container in the path, which is what keeps
        // the viewer from ever being reachable without one.
        composable(Routes.SESSION_LOGS) { entry ->
            val containerId = entry.arguments?.getString(Routes.ARG_CONTAINER_ID).orEmpty()
            SessionLogsScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { startedAt ->
                    navController.navigate(Routes.sessionLog(containerId, startedAt))
                },
            )
        }
        composable(Routes.SESSION_LOG) {
            SessionLogScreen(onBack = { navController.popBackStack() })
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
        composable(Routes.DRIVERS) { DriversScreen(onBack = { navController.popBackStack() }) }
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
