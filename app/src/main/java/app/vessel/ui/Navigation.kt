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
 * Everything else in the product — the editor, the session, components, drivers,
 * files — is pushed on top of one of these. A third tab is the first sign that a
 * screen has been added instead of designed.
 *
 * The two that are here are the two things a user comes to this app to do: pick
 * a container, or pick a program. Nothing else is a place you go on purpose.
 *
 * **Settings was a root and is now gone**, which is the clearest case of the
 * rule. It held two readouts that could not be changed — storage location and a
 * container count — and the links onward. Once the readouts were removed for
 * being decoration, what was left was a menu with a tab of its own: a whole
 * destination whose only content was the names of other destinations. Those
 * links now hang off the overflow in the Containers toolbar, which is where a
 * maintainer looks for them and where a user never has to.
 *
 * Components deliberately is not a root either. Vessel ships one current build
 * of each component, compiled for this device, so there is nothing to browse and
 * no choice to make: the set is provisioned on first run. A store front would be
 * UI for a decision the product does not ask the user to make.
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
