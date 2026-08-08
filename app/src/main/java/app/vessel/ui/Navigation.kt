package app.vessel.ui

import android.net.Uri
import android.view.WindowManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.vessel.core.DisplayGeometry
import app.vessel.data.SessionPhase
import app.vessel.data.SessionState
import app.vessel.ui.components.VNavDestination
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VOutcomeDialog
import app.vessel.ui.components.VOutcomeTone
import app.vessel.ui.screens.AppProfileScreen
import app.vessel.ui.screens.AppsScreen
import app.vessel.ui.screens.ContainerEditorScreen
import app.vessel.ui.screens.ContainersScreen
import app.vessel.ui.screens.FilesScreen
import app.vessel.ui.screens.SessionDesktop
import app.vessel.ui.screens.SessionLaunchDialog
import app.vessel.ui.screens.SessionLogScreen
import app.vessel.ui.screens.SessionLogsScreen
import app.vessel.ui.screens.SessionOutcomeDialog
import app.vessel.ui.vm.NEW_CONTAINER
import app.vessel.ui.vm.SessionViewModel

object Routes {
    const val CONTAINERS = "containers"
    const val APPS = "apps"

    const val CONTAINER_EDITOR = "containerEditor/{containerId}"
    const val APP_PROFILE = "appProfile/{appId}"
    const val FILES = "files"

    /**
     * The running desktop, and only the running desktop.
     *
     * **No container in the path, because there is nothing to disambiguate.**
     * [app.vessel.data.SessionRuntime] has room for exactly one session on this
     * device, so the id in the route was never a parameter — it was a second copy
     * of a fact the runtime already owned, and one that could disagree with it
     * after a Retry. The four non-running states used to share this route and are
     * dialogs now; see DESIGN.md, *Four of the five states are not a screen*.
     */
    const val SESSION = "session"

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
 * Everything else — the editor, the desktop, files — is pushed on top of one of
 * these. The two here are the two things a user comes to this app to do: pick a
 * container, or pick a program.
 *
 * There is no Settings, Components or GPU drivers destination. Each was a screen
 * whose content the user could not act on: this build compiles in exactly one
 * version of each component and one driver, so those screens could only recite
 * what was already decided at build time. Settings went the same way earlier —
 * a destination whose only content was the names of other destinations.
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
    /**
     * A container to launch straight away.
     *
     * This is how the running-session notification gets back to the thing it is
     * a notification *for*, and it is the only way to reach a session from `adb`,
     * which is what the device scripts under `tools/` need.
     *
     * It used to navigate to a screen that then decided whether to launch. It now
     * asks [SessionViewModel] to start the container and lets [SessionHost] do
     * the navigating, which is strictly more robust: a second tap of the
     * notification cannot stack a second copy of the desktop, and a tap that
     * arrives while the container is only *preparing* lands on the checklist
     * dialog rather than on a black rectangle.
     */
    openSession: String? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // One session view model for the whole app, above the NavHost. There is one
    // session on this device; hoisting it here is what lets the checklist and the
    // outcome be dialogs over any destination, and what makes the metrics
    // recorder exist from the first frame rather than from the first time
    // somebody opened the Session screen.
    val session: SessionViewModel = hiltViewModel()
    val state by session.state.collectAsStateWithLifecycle()

    // The panel's own size, which is what `display.resolution: native` means.
    // `maximumWindowMetrics` rather than `Resources.displayMetrics`: the latter
    // reports the *window*, and this activity is edge-to-edge but still not
    // guaranteed to be the whole screen on a foldable or in split screen.
    val context = LocalContext.current
    val native = remember(context) {
        context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics
            ?.bounds
            ?.let { DisplayGeometry(it.width(), it.height()) }
    }

    // Keyed on the id, so a second intent for a different container launches and
    // a recomposition does not ask twice. `launch` is idempotent anyway.
    LaunchedEffect(openSession) {
        if (!openSession.isNullOrBlank()) session.launch(openSession, native)
    }

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
                // Launch is not a navigation. The checklist appears over this
                // list, and the desktop pushes itself once there is one.
                onLaunch = { session.launch(it, native) },
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

        // The desktop. A route rather than an overlay over the NavHost, because
        // the rail's Session log button pushes a destination and an overlay would
        // be drawn on top of the log it just opened — the back stack is exactly
        // the thing that makes desktop → log → back → desktop work.
        composable(Routes.SESSION) {
            val surface by session.surface.collectAsStateWithLifecycle()
            val pointerMode by session.pointerMode.collectAsStateWithLifecycle()
            SessionDesktop(
                state = state,
                surface = surface,
                pointerMode = pointerMode,
                metrics = session.metrics,
                onOpenLogs = { navController.navigate(state.logRoute()) },
                onStop = session::stop,
                onTogglePause = session::togglePause,
                onTogglePointerMode = session::togglePointerMode,
                onShowKeyboard = session::showKeyboard,
                onOpenFiles = session::launchFileManager,
            )
        }
        composable(Routes.APP_PROFILE) { entry ->
            AppProfileScreen(
                appId = entry.arguments?.getString(Routes.ARG_APP_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FILES) { FilesScreen(onBack = { navController.popBackStack() }) }
    }

    SessionHost(state, session, navController, native)
}

/**
 * The one place the session's phase decides what is on screen.
 *
 * Nothing else navigates to or from [Routes.SESSION]. Launch does not, Stop does
 * not, and the notification does not — they all change the phase, and this reads
 * it. That is the whole restructure: a session that ends while the user is three
 * screens deep, a session that fails before it starts, and a session that is
 * still preparing when its notification is tapped are the same rule rather than
 * three call sites that have to agree.
 *
 * | Phase | What is on screen |
 * |---|---|
 * | PREPARING / STARTING | The checklist, over the current screen |
 * | RUNNING | [Routes.SESSION] pushed — the desktop |
 * | EXITED, code 0 | Nothing. The route is popped and the runtime cleared |
 * | EXITED non-zero, FAILED | The route is popped; the outcome dialog says why |
 */
@Composable
private fun SessionHost(
    state: SessionState,
    session: SessionViewModel,
    navController: NavHostController,
    native: DisplayGeometry?,
) {
    // Whether the checklist is up. Dismissing it hides a progress report and
    // does not cancel anything — see SessionLaunchDialog. Saveable so a process
    // death mid-launch does not reopen a dialog the user put down.
    var showChecklist by rememberSaveable { mutableStateOf(true) }
    // A new run is a new report. Keyed on the start time rather than on the
    // container, so relaunching the same one after a failure shows it again.
    LaunchedEffect(state.startedAt) { showChecklist = true }

    val running = state.phase == SessionPhase.RUNNING
    LaunchedEffect(running) {
        if (running) {
            // launchSingleTop, because the phase can be observed as RUNNING again
            // after a configuration change or a re-collection, and a second copy
            // of the desktop would be a second AndroidView fighting over one GL
            // surface.
            navController.navigate(Routes.SESSION) { launchSingleTop = true }
        } else {
            // Inclusive, so anything the rail pushed on top of the desktop — the
            // session log — goes with it. A no-op returning false when the route
            // is not on the stack, which is the ordinary case at startup and
            // after the user has already backed out of a running session.
            navController.popBackStack(Routes.SESSION, inclusive = true)
        }
    }

    // **A clean exit shows nothing.** "The Windows desktop closed · exit code 0"
    // told the user the thing they had just done and then asked them to
    // acknowledge it. Clearing the runtime here is also what stops a finished
    // session lingering: `launch` refuses while the phase is not IDLE, so a
    // session nobody dismissed would make the *next* container show this one's
    // outcome before anything had started.
    val quietExit = state.phase == SessionPhase.EXITED && (state.exitCode ?: 0) == 0
    LaunchedEffect(quietExit) { if (quietExit) session.dismiss() }

    when {
        state.active && !running && showChecklist ->
            SessionLaunchDialog(
                state = state,
                onCancel = session::stop,
                onDismiss = { showChecklist = false },
            )

        // A failed launch or a non-zero exit is the diagnosis, and it is the one
        // thing here that must never be swallowed.
        state.finished && !quietExit ->
            SessionOutcomeDialog(
                state = state,
                onOpenLogs = {
                    val route = state.logRoute()
                    session.dismiss()
                    navController.navigate(route)
                },
                onRetry = { session.retry(native) },
                onDismiss = session::dismiss,
            )
    }

    // The file manager opens *inside* the guest's desktop, so a launch that fails
    // draws nothing anywhere the user is looking — without this the button would
    // simply appear dead, which is the one outcome this product treats as worse
    // than an error. Hosted here rather than on the desktop because it outlives
    // the route: a refusal raised as the session ends still has to be readable.
    val refusal by session.fileManagerRefusal.collectAsStateWithLifecycle()
    refusal?.let { reason ->
        VOutcomeDialog(
            title = "The file manager did not open",
            tone = VOutcomeTone.Danger,
            evidence = listOf(reason),
            onDismiss = session::dismissFileManagerRefusal,
            actions = {
                VButton("Close", session::dismissFileManagerRefusal, style = VButtonStyle.Primary)
            },
        )
    }
}

/**
 * The running session's own log where there is one, and the container's history
 * where the session never got far enough to open a file.
 */
private fun SessionState.logRoute(): String {
    val container = containerId.orEmpty()
    val opened = startedAt
    return if (opened == null) Routes.sessionLogs(container) else Routes.sessionLog(container, opened)
}

/** Roots are singletons: switching tabs restores rather than stacks. */
private fun NavHostController.navigateToRoot(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
