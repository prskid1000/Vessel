package app.vessel.ui.vm

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DisplayGeometry
import app.vessel.core.SessionDisplayServer
import app.vessel.data.ContainerRepository
import app.vessel.data.FileManagerLaunch
import app.vessel.data.SessionMetricsRecorder
import app.vessel.data.SessionMetricsState
import app.vessel.data.SessionPhase
import app.vessel.data.SessionRuntime
import app.vessel.data.SessionState
import app.vessel.input.PointerMode
import app.vessel.service.SessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    recorder: SessionMetricsRecorder,
) : ViewModel() {

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
     * Why the last file-manager attempt did nothing, or null when there is
     * nothing to say.
     *
     * A refusal has to be visible. Wine's file manager opens *inside* the guest's
     * desktop, so a launch that fails produces no window and no error anywhere the
     * user is looking — pressing the button would simply appear to do nothing,
     * which is the failure mode this project treats as worse than a crash.
     * [FileManagerLaunch.Started] and `AlreadyRunning` say nothing, because in
     * both cases the window is on screen and that is the feedback.
     */
    private val _fileManagerRefusal = MutableStateFlow<String?>(null)
    val fileManagerRefusal: StateFlow<String?> = _fileManagerRefusal.asStateFlow()

    fun launchFileManager() {
        viewModelScope.launch {
            _fileManagerRefusal.value =
                (runtime.launchFileManager() as? FileManagerLaunch.Unavailable)?.reason
        }
    }

    fun dismissFileManagerRefusal() {
        _fileManagerRefusal.value = null
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
