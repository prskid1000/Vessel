package app.vessel.ui.vm

import android.content.Context
import android.view.View
import androidx.lifecycle.SavedStateHandle
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
import app.vessel.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Session screen's half of the launcher.
 *
 * It owns almost nothing. [SessionRuntime] is a singleton holding the live
 * session, so the state survives this `ViewModel` being destroyed by a rotation
 * or by the user leaving the screen — which matters more here than anywhere else
 * in the product, because leaving the screen must not stop the container.
 *
 * Starting goes through [SessionService] rather than straight to the runtime, so
 * a session can never be running without the foreground service that keeps its
 * process alive.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedState: SavedStateHandle,
    private val runtime: SessionRuntime,
    private val containers: ContainerRepository,
    private val display: SessionDisplayServer,
    recorder: SessionMetricsRecorder,
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()

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
     * The live sampling window, for the rail's graph strip.
     *
     * Injecting the recorder here is also what brings it into existence: it is a
     * singleton that observes [SessionRuntime] rather than being called by it, so
     * something has to construct it, and this screen is the only way a session is
     * ever reached. Once built it outlives this `ViewModel`, which is why leaving
     * the Session screen does not stop the metrics reaching the log.
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
     * Start this container unless it is already the running one.
     *
     * Called from the screen on first composition, and idempotent, because
     * composition is not a promise about how many times it happens: a rotation
     * during Preparing must not queue a second launch.
     *
     * [native] is the phone's own panel size, which the composable measures and
     * this layer has no way to. It is what `display.resolution: native` becomes.
     */
    fun launchIfIdle(native: DisplayGeometry?) {
        // Any non-idle state, not just this container's. There is room for one
        // session on this device, so arriving here while another container is
        // running shows that one rather than silently queueing a second.
        if (state.value.phase != SessionPhase.IDLE) return
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            SessionService.launch(appContext, containerId, name, native)
        }
    }

    /** Relaunch after a failure, from the same screen. DESIGN.md's Retry. */
    fun retry(native: DisplayGeometry?) {
        runtime.clear()
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            SessionService.launch(appContext, containerId, name, native)
        }
    }

    fun stop() = SessionService.stop(appContext)

    /**
     * Drop a finished session so the screen is not left describing it.
     *
     * Only ever called when leaving a terminal state — the runtime refuses while
     * a session is active, which is what stops a back gesture from wiping the
     * state of a container that is still running.
     */
    fun dismiss() = runtime.clear()
}
