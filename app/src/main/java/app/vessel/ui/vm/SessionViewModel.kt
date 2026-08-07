package app.vessel.ui.vm

import android.content.Context
import android.view.View
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DisplayGeometry
import app.vessel.core.SessionDisplayServer
import app.vessel.data.ContainerRepository
import app.vessel.data.SessionPhase
import app.vessel.data.SessionRuntime
import app.vessel.data.SessionState
import app.vessel.service.SessionService
import app.vessel.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
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
    display: SessionDisplayServer,
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
