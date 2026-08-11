package app.vessel.ui

import android.net.Uri
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.vessel.core.DisplayGeometry
import app.vessel.core.GuestViewport
import app.vessel.data.SessionPhase
import app.vessel.data.SessionState
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VOutcomeDialog
import app.vessel.ui.components.VOutcomeTone
import app.vessel.ui.screens.FilesScreen
import app.vessel.ui.screens.HomeScreen
import app.vessel.ui.screens.InputEditorActions
import app.vessel.ui.screens.InputEditorState
import app.vessel.ui.screens.LicenceTextScreen
import app.vessel.ui.screens.LicencesScreen
import app.vessel.ui.screens.SessionDesktop
import app.vessel.ui.screens.SessionLaunchDialog
import app.vessel.ui.screens.SessionLogScreen
import app.vessel.ui.screens.SessionLogsScreen
import app.vessel.ui.screens.SessionOutcomeDialog
import app.vessel.ui.screens.SetupDialog
import app.vessel.ui.vm.SessionViewModel
import app.vessel.ui.vm.SetupViewModel

/**
 * Three destinations, and one of them is the desktop.
 *
 * **Home is the only root and there is no bottom navigation.** Apps was the
 * second root and no longer exists: a program is listed inside the container that
 * owns it, so the list that used to be a tab is now four columns on a card.
 * Everything short — new container, container settings, a program's profile — is
 * a bottom sheet over home rather than a route, because a sheet keeps the thing
 * it is about on screen and a push throws it away.
 *
 * What is left as a push is what you genuinely navigate *into*: the file browser,
 * the session history, one session's log, and the running desktop.
 */
object Routes {
    const val HOME = "home"

    /**
     * A container's `C:`.
     *
     * The container is in the path and not optional, which is what stops the
     * browser ever being reachable without one — the objection that killed Files
     * as a root. `pick` distinguishes browsing from choosing a file for a
     * shortcut; the primary action changes and the result is handed back.
     */
    const val FILES = "files/{containerId}?pick={pick}"

    /**
     * The running desktop, and only the running desktop.
     *
     * **No container in the path, because there is nothing to disambiguate.**
     * [app.vessel.data.SessionRuntime] has room for exactly one session on this
     * device, so the id in the route was never a parameter — it was a second copy
     * of a fact the runtime already owned, and one that could disagree with it
     * after a Retry. The four non-running states are dialogs; see DESIGN.md,
     * *Four of the five states are not a screen*.
     */
    const val SESSION = "session"

    /**
     * Logs hang off a container and are not a destination of their own.
     *
     * There is no global "Logs" screen, because a log is a property of the run
     * that produced it: a list of every session across every container would be a
     * list whose first column is the container name, which is the shape of a
     * screen that should have been two.
     */
    const val SESSION_LOGS = "logs/{containerId}"
    const val SESSION_LOG = "logs/{containerId}/{startedAt}"

    /**
     * What Vessel is made of, and one licence in full.
     *
     * A push and not a sheet, unlike everything else short in this product,
     * because a licence is a document you read rather than a decision you make
     * over the screen behind it — and because the second route pushes a 500-line
     * text on top of the first, which a sheet cannot do.
     */
    const val LICENCES = "licences"
    const val LICENCE = "licences/{title}"

    fun licence(title: String) = "licences/${Uri.encode(title)}"

    const val ARG_LICENCE_TITLE = "title"

    fun files(containerId: String, pick: Boolean = false) =
        "files/${Uri.encode(containerId)}?pick=$pick"

    fun sessionLogs(containerId: String) = "logs/${Uri.encode(containerId)}"

    fun sessionLog(containerId: String, startedAt: Long) =
        "logs/${Uri.encode(containerId)}/$startedAt"

    const val ARG_CONTAINER_ID = "containerId"
    const val ARG_PICK = "pick"
    const val ARG_STARTED_AT = "startedAt"

    /**
     * Where the browser leaves a chosen file for home to pick up.
     *
     * A `SavedStateHandle` key on home's own back-stack entry rather than a
     * shared view model, because that is the one channel whose lifetime is
     * exactly "until home is destroyed" — which is also when the sheet waiting
     * for the answer stops existing.
     */
    const val PICKED_EXECUTABLE = "pickedExecutable"
}

@Composable
fun VesselApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    /**
     * A container to launch straight away.
     *
     * This is how the running-session notification gets back to the thing it is a
     * notification *for*, and it is the only way to reach a session from `adb`,
     * which is what the device scripts under `tools/` need.
     */
    openSession: String? = null,
    /**
     * Goes up once per intent carrying [openSession], including a repeat of one
     * already seen. See `MainActivity.takeOpenSession`: without it, the second
     * tap on the running-session notification is indistinguishable from a
     * recomposition and is dropped.
     */
    openSessionTicket: Int = 0,
) {
    // One session view model for the whole app, above the NavHost. There is one
    // session on this device; hoisting it here is what lets the checklist and the
    // outcome be dialogs over any destination, and what makes the metrics
    // recorder exist from the first frame rather than from the first time
    // somebody opened the Session screen.
    val session: SessionViewModel = hiltViewModel()
    val state by session.state.collectAsStateWithLifecycle()

    // One owner for the whole app's orientation, driven by the route. See
    // `LockOrientation` — the per-destination form races on the way out. A
    // surface inside a destination that needs its own — today only the overlay
    // arranger, which must be landscape even when opened from a portrait sheet —
    // fills the slot rather than writing `requestedOrientation` itself.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val orientationOverride = remember { mutableStateOf<Int?>(null) }
    LockOrientation(
        orientationOverride.value ?: orientationFor(backStackEntry?.destination?.route),
    )

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

    // Keyed on the ticket rather than on the id, so that the same container asked
    // for twice runs this twice. `launch` is idempotent about *starting* — the
    // second call turns into a request to show the desktop already running.
    LaunchedEffect(openSessionTicket, openSession) {
        if (!openSession.isNullOrBlank()) session.launch(openSession, native)
    }

    // First-run setup starts itself. There is no button and no prompt: everything
    // it installs is already inside the APK, so there is nothing for the user to
    // decide and nothing to fetch. It is started here rather than from
    // `VesselApplication` so that it begins with the first frame the user sees,
    // which is also the frame its progress dialog can appear over.
    val setup: SetupViewModel = hiltViewModel()
    val setupState by setup.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { setup.start() }

    CompositionLocalProvider(LocalOrientationOverride provides orientationOverride) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = modifier,
        ) {
            composable(Routes.HOME) { entry ->
                // A file the browser handed back, if the user went and chose one.
                val picked by entry.savedStateHandle
                    .getStateFlow<String?>(Routes.PICKED_EXECUTABLE, null)
                    .collectAsStateWithLifecycle()

                HomeScreen(
                    onOpenFiles = { navController.navigate(Routes.files(it)) },
                    onPickFile = { navController.navigate(Routes.files(it, pick = true)) },
                    onOpenLogs = { navController.navigate(Routes.sessionLogs(it)) },
                    // Launch is not a navigation. The checklist appears over this
                    // list, and the desktop pushes itself once there is one.
                    onLaunch = { session.launch(it, native) },
                    // `native` matters here and not on the desktop: a tile tapped on
                    // home may be the thing that *starts* the container, and the
                    // panel size is what `display.resolution: native` becomes.
                    onLaunchApp = { session.launchApp(it, native) },
                    onOpenLicences = { navController.navigate(Routes.LICENCES) },
                    pickedExecutable = picked,
                    onPickConsumed = {
                        entry.savedStateHandle[Routes.PICKED_EXECUTABLE] = null
                    },
                )
            }

            composable(
                Routes.FILES,
                arguments = listOf(
                    navArgument(Routes.ARG_PICK) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                FilesScreen(
                    picking = entry.arguments?.getBoolean(Routes.ARG_PICK) == true,
                    onBack = { navController.popBackStack() },
                    onPicked = { guestPath ->
                        // Left on the *previous* entry — home's — because that is the
                        // screen still holding the sheet that asked for it.
                        navController.previousBackStackEntry
                            ?.savedStateHandle?.set(Routes.PICKED_EXECUTABLE, guestPath)
                        navController.popBackStack()
                    },
                )
            }

            // Both log routes take the container in the path, which is what keeps the
            // viewer from ever being reachable without one.
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

            composable(Routes.LICENCES) {
                LicencesScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { navController.navigate(Routes.licence(it)) },
                )
            }
            composable(Routes.LICENCE) { entry ->
                LicenceTextScreen(
                    title = entry.arguments?.getString(Routes.ARG_LICENCE_TITLE).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            // The desktop. A route rather than an overlay over the NavHost, because
            // the rail's Session log button pushes a destination and an overlay would
            // be drawn on top of the log it just opened — the back stack is exactly
            // the thing that makes desktop → log → back → desktop work.
            composable(Routes.SESSION) {
                val surface by session.surface.collectAsStateWithLifecycle()
                val pointerMode by session.pointerMode.collectAsStateWithLifecycle()
                val windows by session.windows.collectAsStateWithLifecycle(emptyList())
                val viewport by session.viewport.collectAsStateWithLifecycle(GuestViewport())
                val shortcuts by session.shortcuts.collectAsStateWithLifecycle(emptyList())
                val terminals by session.terminalProfiles.collectAsStateWithLifecycle(emptyList())
                // Collected here rather than inside the taskbar: the readout ticks
                // twice a second, and a collector inside the bar would recompose the
                // bar's whole subtree — every window button and its icon — on every
                // sample. Hoisting it means the taskbar takes a value and only the
                // readout's own draw is invalidated.
                val frameRate by session.frameRate.collectAsStateWithLifecycle()
                val inputProfile by session.inputProfile.collectAsStateWithLifecycle()
                // The pad's held set changes at a stick's report rate. Collected here
                // for the same reason `frameRate` is: the panel takes a value, so only
                // the rows that light are invalidated rather than the desktop.
                val heldControls by session.heldControls.collectAsStateWithLifecycle()
                val inputProfiles by session.inputProfileList
                    .collectAsStateWithLifecycle(emptyList())
                val activeInputProfileId by session.activeInputProfileId
                    .collectAsStateWithLifecycle(null)
                val touchVisible by session.touchControlsVisible.collectAsStateWithLifecycle()
                val touchEditing by session.touchEditing.collectAsStateWithLifecycle()
                val selectedTouchControl by session.selectedTouchControl
                    .collectAsStateWithLifecycle()
                val inputNotice by session.inputNotice.collectAsStateWithLifecycle()
                val inputDirty by session.inputProfileDirty
                    .collectAsStateWithLifecycle(false)
                SessionDesktop(
                    state = state,
                    surface = surface,
                    pointerMode = pointerMode,
                    input = InputEditorState(
                        profile = inputProfile,
                        profiles = inputProfiles,
                        activeProfileId = activeInputProfileId,
                        // A container naming a profile that has been deleted resolves
                        // to the default and is not rewritten, so the two disagree —
                        // which is exactly the case worth saying out loud.
                        missingProfile = activeInputProfileId != null &&
                            inputProfiles.none { it.id == activeInputProfileId },
                        containerName = state.containerName,
                        guest = state.geometry,
                        live = true,
                        held = heldControls,
                        touchVisible = touchVisible,
                        editing = touchEditing,
                        selected = selectedTouchControl,
                        notice = inputNotice,
                        dirty = inputDirty,
                    ),
                    inputActions = InputEditorActions(
                        onProfile = session::setInputProfile,
                        onPickProfile = session::pickInputProfile,
                        onRename = { session.setInputProfile(inputProfile.copy(name = it)) },
                        onNewProfile = session::newInputProfile,
                        onDuplicate = session::duplicateInputProfile,
                        onDelete = session::deleteInputProfile,
                        onImportText = session::importInputProfile,
                        onExportText = { session.exportInputProfile(it) },
                        onTouchVisible = session::setTouchControlsVisible,
                        onEditing = session::setTouchEditing,
                        onSelect = session::selectTouchControl,
                        onDismissNotice = session::dismissInputNotice,
                        onSaveProfile = session::commitInputProfile,
                    ),
                    windows = windows,
                    frameRate = frameRate,
                    shortcuts = shortcuts,
                    shellUnavailableReason = session.shellUnavailableReason,
                    metrics = session.metrics,
                    onOpenLogs = { navController.navigate(state.logRoute()) },
                    onOpenFiles = {
                        state.containerId?.let { navController.navigate(Routes.files(it)) }
                    },
                    onStop = session::stop,
                    onTogglePause = session::togglePause,
                    onTogglePointerMode = session::togglePointerMode,
                    onShowKeyboard = session::showKeyboard,
                    onLaunchApp = session::launchApp,
                    onFocusWindow = session::focusWindow,
                    onMinimizeWindow = session::minimizeWindow,
                    onCloseWindow = session::closeWindow,
                    onKillWindow = session::killWindow,
                    onMoveResizeWindow = session::moveResizeWindow,
                    viewport = viewport,
                    terminals = terminals,
                    onTerminal = session::openTerminal,
                )
            }
        }
    }

    SessionHost(state, session, navController, native)

    // Over the container list, and over everything else too — this is the only
    // dialog in the product that can be up before the user has done anything.
    // Saveable, so a rotation does not put a report back that was put down; keyed
    // on nothing, because setup happens once per install and there is no second
    // run to reset it for.
    var showSetup by rememberSaveable { mutableStateOf(true) }
    if (showSetup && setupState.worthShowing) {
        SetupDialog(setupState) { showSetup = false }
    }
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
    // Whether the log is the thing on screen. The launch dialog is hosted above
    // the NavHost, so it would otherwise cover the log it just opened.
    val route by navController.currentBackStackEntryAsState()
    val onLogRoute = route?.destination?.route?.startsWith(LOGS_PREFIX) == true
    // Whether the checklist is up. Dismissing it hides a progress report and does
    // not cancel anything — see SessionLaunchDialog. Saveable so a process death
    // mid-launch does not reopen a dialog the user put down.
    var showChecklist by rememberSaveable { mutableStateOf(true) }
    // A new run is a new report. Keyed on the start time rather than on the
    // container, so relaunching the same one after a failure shows it again.
    LaunchedEffect(state.startedAt) { showChecklist = true }

    // Keyed on the show counter as well as on the phase, so that "show me the
    // desktop I already have" is a thing the user can ask for. Without it the
    // only navigation to the desktop is the edge into RUNNING, and a user who
    // backs out of a running session can never get back to it — see
    // SessionViewModel.launch.
    val showRequest by session.showRequests.collectAsStateWithLifecycle()
    val running = state.phase == SessionPhase.RUNNING
    LaunchedEffect(running, showRequest) {
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
        state.active && !running && showChecklist && !onLogRoute ->
            SessionLaunchDialog(
                state = state,
                onCancel = session::stop,
                onDismiss = { showChecklist = false },
                // **Navigate without putting the checklist down.** Dismissing it
                // first did keep the dialog off the log screen, and cost the
                // thing the button exists for: coming back left no checklist and
                // no way to get one, so a launch still in progress became
                // untrackable. It is suppressed while the log is on top instead
                // — see `onLogRoute` — and returns with the launch when you go
                // back.
                onOpenLogs = { navController.navigate(state.logRoute()) },
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

    // Hosted above the NavHost because it outlives the screen that asked: a
    // program launch is not implemented at all, so the failure draws nothing
    // anywhere the user is looking — the one outcome this product treats as
    // worse than an error.
    val shellRefusal by session.shellRefusal.collectAsStateWithLifecycle()
    shellRefusal?.let { reason ->
        VOutcomeDialog(
            title = "Vessel cannot start a program yet",
            tone = VOutcomeTone.Danger,
            detail = reason,
            onDismiss = session::dismissShellRefusal,
            actions = {
                VButton("Close", session::dismissShellRefusal, style = VButtonStyle.Primary)
            },
        )
    }
}

/** Both log routes start with this; see [Routes.SESSION_LOGS]. */
private const val LOGS_PREFIX = "logs/"

/**
 * The running session's own log where there is one, and the container's history
 * where the session never got far enough to open a file.
 */
private fun SessionState.logRoute(): String {
    val container = containerId.orEmpty()
    val opened = startedAt
    return if (opened == null) {
        Routes.sessionLogs(container)
    } else {
        Routes.sessionLog(container, opened)
    }
}
