package app.vessel.ui.vm

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DisplayGeometry
import app.vessel.core.FrameRate
import app.vessel.core.SessionDisplayServer
import app.vessel.data.ContainerRepository
import app.vessel.data.SessionMetricsRecorder
import app.vessel.data.SessionMetricsState
import app.vessel.data.SessionPhase
import app.vessel.data.SessionRuntime
import app.vessel.data.SessionState
import app.vessel.input.PointerMode
import app.vessel.service.SessionService
import app.vessel.ui.shell.AppRegistry
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.ShellHost
import app.vessel.ui.shell.TerminalOption
import app.vessel.ui.shell.TerminalProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The session's half of the launcher, for the whole app rather than for a screen.
 *
 * It owns almost nothing. [SessionRuntime] is a singleton holding the live
 * session, so the state survives this `ViewModel` being destroyed by a rotation
 * or by the user leaving the desktop — which matters more here than anywhere else
 * in the product, because leaving must not stop the container.
 *
 * **It no longer reads a `containerId` off a route, and that is the point.** The
 * session used to be a screen with the container in its path, so this class took
 * one from a `SavedStateHandle` and every question about the running session was
 * answered by whichever copy of the screen happened to be composed. There is one
 * session on this device; there is now one of these, hoisted above the `NavHost`
 * in [app.vessel.ui.VesselApp], and the container it is about is whichever one
 * [SessionRuntime] says is running. A launch names its container as an argument,
 * which is the only moment anything needs to.
 *
 * Starting goes through [SessionService] rather than straight to the runtime, so
 * a session can never be running without the foreground service that keeps its
 * process alive.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val runtime: SessionRuntime,
    private val containers: ContainerRepository,
    private val display: SessionDisplayServer,
    private val shell: ShellHost,
    registry: AppRegistry,
    recorder: SessionMetricsRecorder,
) : ViewModel() {

    init {
        awaitPendingProgram()
    }

    /** The taskbar's list of guest windows. Empty while nothing is running. */
    val windows = shell.windows

    /**
     * Composited frames per second, for the taskbar's readout.
     *
     * From the display server rather than from [shell], because the compositor
     * is the only thing that knows how often it drew — the shell layer sees
     * windows and processes, and neither has a frame in it.
     */
    val frameRate: StateFlow<FrameRate> = display.frameRate

    /**
     * The programs belonging to whichever container is running — the launcher's
     * grid.
     *
     * Scoped to the session rather than showing everything, because a program in
     * another container cannot be started into this one: it is a different prefix
     * with a different registry, and the same `.exe` in two containers is two
     * different things.
     */
    val shortcuts: Flow<List<AppShortcut>> =
        combine(runtime.state, registry.shortcuts) { session, all ->
            all.filter { it.containerId == session.containerId }
        }

    /** Whether the shell can see into and act on the running desktop at all. */
    val shellAvailable: Boolean = shell.available

    /** Why not, when it cannot. Printed verbatim by the taskbar and the launcher. */
    val shellUnavailableReason: String? = shell.unavailableReason

    fun focusWindow(id: Int) {
        viewModelScope.launch { shell.focus(id) }
    }

    /**
     * Ask a window to close, and fall through to killing it if it will not be
     * asked.
     *
     * **The fall-through is the point, and it is not the same as force-closing.**
     * A false from [ShellHost.close] means the window never advertised
     * `WM_DELETE_WINDOW`, so nothing was sent and nothing will happen — a Close
     * button that silently did nothing for those windows would be the worst of
     * both. Escalating is the only way to honour the press. It is still
     * distinct from the sheet's Force close, which the user chooses for a window
     * that *did* accept the request and ignored it.
     */
    fun closeWindow(id: Int) {
        viewModelScope.launch {
            if (!shell.close(id)) shell.kill(id)
        }
    }

    fun killWindow(id: Int) {
        viewModelScope.launch { shell.kill(id) }
    }

    val state: StateFlow<SessionState> = runtime.state

    /**
     * A counter that goes up every time something asks to *see* the running
     * desktop rather than to start one.
     *
     * A counter and not a boolean or a one-shot event: the host has to be able to
     * distinguish "asked again" from "still asking", and a `SharedFlow` would be
     * lost across the configuration change that a rotation into the desktop's own
     * orientation lock immediately causes. Nothing consumes or resets it; the
     * value itself is meaningless and only the change matters.
     */
    val showRequests: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * The compositor's view, or null while nothing is running.
     *
     * Passed straight through from the display server rather than created here.
     * The view outlives this `ViewModel` on purpose — it belongs to the session,
     * and a rotation must not throw away the GL surface a guest is drawing into.
     */
    val surface: StateFlow<View?> = display.surface

    /**
     * Whether one finger drives the cursor like a trackpad or points at it.
     *
     * Straight through from the display server, which owns it because the view
     * does — this layer would only be a second copy to keep in step, and the
     * setting survives a rotation for the same reason the surface does.
     */
    val pointerMode: StateFlow<PointerMode> = display.pointerMode

    /**
     * The live sampling window, for the rail's graphs.
     *
     * Injecting the recorder here is also what brings it into existence: it is a
     * singleton that observes [SessionRuntime] rather than being called by it, so
     * something has to construct it. That used to be the Session screen, which
     * meant no session reached before a screen was opened was ever recorded;
     * hoisting this above the `NavHost` means the recorder exists from the first
     * frame of the app and a session launched from a notification is traced too.
     *
     * Collect it only while the rail is open — see
     * [SessionMetricsRecorder.watched]. Nothing collecting means a tenth of the
     * sample rate, which on this screen is the difference that matters.
     */
    val metrics: Flow<SessionMetricsState> = recorder.watched()

    fun togglePointerMode() = display.setPointerMode(pointerMode.value.toggled())

    fun showKeyboard() = display.showKeyboard()

    /**
     * Why the last attempt to start a *program* did nothing.
     *
     * Separate from the file-manager refusal because they fail for different
     * reasons and a user who sees one should not be told about the other.
     */
    private val _shellRefusal = MutableStateFlow<String?>(null)
    val shellRefusal: StateFlow<String?> = _shellRefusal.asStateFlow()

    fun dismissShellRefusal() {
        _shellRefusal.value = null
    }

    /**
     * Start one program, starting its container first if nothing is running.
     *
     * **A tile on home could not start anything, and said something untrue about
     * why.** This went straight to [SessionShellHost.launch], which compares the
     * running session's container against the shortcut's — and with no session
     * running there is no container to compare, so every tile on the home screen
     * refused with "belongs to a different container". One container on the
     * device, its own tile, and the product blaming a container that did not
     * exist.
     *
     * Three cases now, and they are genuinely different:
     *
     * - **Its container is already running.** Start the program in it, which is
     *   what this always did and what worked from the desktop's launcher.
     * - **Nothing is running.** Start the container and remember the program;
     *   [awaitPendingProgram] starts it when the desktop is up. Not "start the
     *   desktop and say nothing", which was considered and rejected long ago and
     *   is still wrong — the user tapped a program and must get that program.
     * - **A different container is running.** Refuse, out loud. There is room
     *   for one session on this device, so this is a real answer rather than
     *   something to silently reinterpret as "restart".
     */
    fun launchApp(shortcut: AppShortcut, native: DisplayGeometry? = null) {
        val session = state.value
        when {
            session.containerId == shortcut.containerId && session.phase == SessionPhase.RUNNING ->
                viewModelScope.launch { _shellRefusal.value = shell.launch(shortcut) }

            session.phase == SessionPhase.IDLE -> {
                pendingProgram = shortcut
                launch(shortcut.containerId, native)
            }

            // Preparing or starting *this* container: the program is queued and
            // the checklist is already on screen, so there is nothing to say.
            session.containerId == shortcut.containerId -> pendingProgram = shortcut

            else -> _shellRefusal.value =
                "${shortcut.name} belongs to a different container. Stop this session first, " +
                    "then launch it from its own."
        }
    }

    /**
     * A program tapped before its container was up, waiting for it.
     *
     * Not in [SessionState]: the runtime is a singleton shared with the service
     * and knows nothing about shortcuts, and a program the user asked for is a
     * fact about this screen's intent rather than about the session.
     */
    private var pendingProgram: AppShortcut? = null

    /**
     * Start the queued program once its desktop exists.
     *
     * Cleared on any terminal phase as well as on success, so a launch that
     * fails does not leave a program to be started by the *next* session the
     * user opens — which would be a program appearing in a container nobody
     * asked to run it in.
     */
    private fun awaitPendingProgram() {
        viewModelScope.launch {
            runtime.state.collect { session ->
                val waiting = pendingProgram ?: return@collect
                when {
                    session.phase == SessionPhase.RUNNING &&
                        session.containerId == waiting.containerId -> {
                        pendingProgram = null
                        _shellRefusal.value = shell.launch(waiting)
                    }

                    session.finished || session.phase == SessionPhase.IDLE ->
                        pendingProgram = null

                    else -> Unit
                }
            }
        }
    }

    /**
     * The shells the running container can open a console on.
     *
     * Recomputed whenever the session changes rather than held as a value,
     * because whether PowerShell is installed is a fact about a container's `C:`
     * and the running container is not fixed. Empty while nothing runs — the
     * launcher only exists over a session, but a container id of `""` would
     * otherwise be looked up and answer "not installed" for everything.
     */
    val terminalProfiles: Flow<List<TerminalOption>> =
        runtime.state.map { session ->
            val id = session.containerId
            if (id.isNullOrBlank()) emptyList() else shell.terminalProfiles(id)
        }

    fun openTerminal(profile: TerminalProfile) {
        val id = state.value.containerId
        // Nothing is running, so there is no prefix to open a console in. Not a
        // refusal to print either: the launcher only exists over a session, so
        // reaching here at all means the session ended between the tap and this
        // line, and a sentence about it would be about a window already gone.
        if (id.isNullOrBlank()) return
        viewModelScope.launch { _shellRefusal.value = shell.openTerminal(id, profile) }
    }

    /**
     * Start [containerId] unless something is already running.
     *
     * Idempotent, and called from two places that cannot coordinate: the launch
     * button on a container card, and the `openSession` extra a notification or
     * `adb` delivers. Composition is not a promise about how many times it
     * happens either, so a recomposition during Preparing must not queue a second
     * launch.
     *
     * [native] is the phone's own panel size, which only a composable can
     * measure. It is what `display.resolution: native` becomes.
     */
    fun launch(containerId: String, native: DisplayGeometry?) {
        // Any non-idle state, not just this container's. There is room for one
        // session on this device, so asking for a second while one runs shows the
        // first rather than silently queueing behind it.
        //
        // "Shows the first" used to be a claim rather than a behaviour, and the
        // gap was reachable in three taps: back out of the desktop, and neither
        // the container card's Launch button nor the `openSession` extra could
        // return to it. Both funnel into here, this line refused because the
        // phase was RUNNING, and SessionHost only navigates on the *transition*
        // into RUNNING — which had already happened. A container that was very
        // much alive read "never launched", and stopping it was the only way out.
        if (containerId.isBlank()) return
        if (state.value.phase != SessionPhase.IDLE) {
            showRequests.value++
            return
        }
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            SessionService.launch(appContext, containerId, name, native)
        }
    }

    /**
     * Relaunch after a failure. DESIGN.md's Retry.
     *
     * The container comes from the failed session's own state rather than from a
     * route argument, because the failure dialog can be on screen over any
     * destination — including one that has never heard of a container.
     */
    fun retry(native: DisplayGeometry?) {
        val containerId = state.value.containerId ?: return
        runtime.clear()
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            SessionService.launch(appContext, containerId, name, native)
        }
    }

    fun stop() = SessionService.stop(appContext)

    /** `SIGSTOP` the guest, or `SIGCONT` it. See [SessionRuntime.setPaused]. */
    fun togglePause() = runtime.setPaused(!state.value.paused)

    /**
     * Drop a finished session so nothing is left describing it.
     *
     * Only ever called from a terminal state — the runtime refuses while a
     * session is active, which is what stops this from wiping the state of a
     * container that is still running.
     *
     * Getting this wrong is a bug with history: an EXITED or FAILED session left
     * in the runtime makes [launch] refuse, so the *next* container opened shows
     * the *previous* run's outcome dialog before anything has launched.
     */
    fun dismiss() = runtime.clear()
}
