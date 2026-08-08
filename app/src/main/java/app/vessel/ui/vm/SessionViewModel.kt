package app.vessel.ui.vm

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DisplayGeometry
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /** The taskbar's list of guest windows. Empty while nothing is running. */
    val windows = shell.windows

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

    val state: StateFlow<SessionState> = runtime.state

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
     * Start one program inside its container.
     *
     * **This currently always refuses, and it refuses out loud.**
     * [SessionRuntime.start] takes a container and nothing else, so there is no
     * way to ask a prefix — running or cold — to run a named executable. The
     * mechanism exists and is proven: `SessionRuntime.launchFileManager` does
     * exactly this for `winefile`. It is simply not generalised, and generalising
     * it is `data/`'s to do — see `out/ui-needs-from-core.md`.
     *
     * The alternative considered and rejected was to start the container's plain
     * desktop instead and say nothing. That is a launcher that appears to work:
     * the user taps Notepad++, a Windows desktop appears, and Notepad++ is not on
     * it. A sentence naming the missing piece is worth more than a desktop nobody
     * asked for.
     */
    fun launchApp(shortcut: AppShortcut) {
        viewModelScope.launch { _shellRefusal.value = shell.launch(shortcut) }
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
        if (containerId.isBlank() || state.value.phase != SessionPhase.IDLE) return
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
