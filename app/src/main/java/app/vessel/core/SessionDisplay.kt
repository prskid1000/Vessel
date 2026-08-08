package app.vessel.core

import android.view.View
import app.vessel.input.PointerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * What the session asks a display server for.
 *
 * [fpsLimit] is here rather than in the process environment because
 * `display.fpsLimit` declares no `env` in the manifest: frame pacing belongs to
 * whatever is presenting the surface, and the alternative — inventing
 * `DXVK_FRAME_RATE` — would cap the D3D layer instead of the compositor and
 * silently do nothing for an OpenGL title.
 */
data class DisplayRequest(
    /** The `DISPLAY` value Wine's X11 driver will connect to, e.g. `:0`. */
    val display: String,
    val geometry: DisplayGeometry,
    /** Frames per second, or null for unlimited. */
    val fpsLimit: Int?,
    /**
     * A writable directory the server may put filesystem sockets under.
     *
     * The X socket does not need one — see [xSocketName] — but the shared-memory
     * server does, because the guest reaches it by an absolute path in
     * `WINE_SYSVSHM_SOCKET` rather than by a name libxcb already knows.
     */
    val socketRoot: File,
)

/** How [SessionDisplayServer.start] went. Modelled on `BootstrapOutcome`. */
sealed interface DisplayOutcome {
    /**
     * The server is listening.
     *
     * [environment] is what the guest has to be started with for the sockets
     * that were *actually* bound to be reachable — `DISPLAY` and
     * `WINE_SYSVSHM_SOCKET`. It is returned rather than assumed because a
     * display server that binds one thing while the launcher advertises another
     * fails as `winex11: cannot open display`, two layers away from the mistake.
     */
    data class Started(val environment: Map<String, String>) : DisplayOutcome

    /** No server is built in yet. The session continues, headless, and says so. */
    data class NotAvailable(val reason: String) : DisplayOutcome

    data class Failed(val reason: String) : DisplayOutcome
}

/**
 * **The seam with the X server, and the whole of it.**
 *
 * An X server is vendored into `com.winlator`. Nothing in `app.vessel` outside
 * the adapter references that package: this interface is the entire contract.
 * Two reasons, in order of importance — the launcher has to be finishable and
 * testable without it, and a session that can be run headless is the only way to
 * read a Wine failure that happens before any window exists.
 *
 * The implementation is bound in `DataModule`.
 */
interface SessionDisplayServer {

    /** Bring up a server on [DisplayRequest.display] before Wine is started. */
    suspend fun start(request: DisplayRequest): DisplayOutcome

    /** Tear it down. Called on every session end, including a failed start. */
    suspend fun stop()

    /**
     * The view the guest's output is composited into, or null when nothing is
     * running.
     *
     * A bare [View] rather than anything from `com.winlator`, which is what lets
     * the Session screen host the compositor with `AndroidView` while keeping the
     * vendored types on one side of the seam. The view also owns input: it is
     * what turns a touch into a pointer event, so the screen has nothing to
     * forward.
     */
    val surface: StateFlow<View?>

    /**
     * Whether one finger moves the cursor to itself or pushes it along.
     *
     * On the seam rather than inside the view because it is the one input
     * setting the *user* changes mid-session, from the Session rail, and the
     * rail has no reference to the view — it only knows this interface. It is a
     * flow rather than a getter so the rail's label matches reality after a
     * session restart, which resets it.
     */
    val pointerMode: StateFlow<PointerMode>

    fun setPointerMode(mode: PointerMode)

    /**
     * Raise the IME over the guest's output.
     *
     * There is no hardware keyboard on a phone, and a Windows desktop with no
     * way to type is a demo rather than a tool. Nothing dismisses it explicitly:
     * the system back gesture does, and the rail's own Back is already spoken
     * for by Stop.
     */
    fun showKeyboard()

    /**
     * A working configuration, not a stub: no server, said out loud.
     *
     * The session log gets [REASON] as a Vessel line, so a run that dies at
     * `winex11: cannot open display` is already explained by the line above it
     * rather than looking like a driver fault.
     */
    object Absent : SessionDisplayServer {
        const val REASON: String =
            "no X server is built in yet — the session runs headless, so anything that " +
                "needs a window will fail to open one"

        override val surface: StateFlow<View?> = MutableStateFlow<View?>(null).asStateFlow()

        override val pointerMode: StateFlow<PointerMode> =
            MutableStateFlow(PointerMode.TRACKPAD).asStateFlow()

        override fun setPointerMode(mode: PointerMode) = Unit

        override fun showKeyboard() = Unit

        override suspend fun start(request: DisplayRequest): DisplayOutcome =
            DisplayOutcome.NotAvailable(REASON)

        override suspend fun stop() = Unit
    }
}

/** `WINE_SYSVSHM_SOCKET` — what `patches/wine/0005` reads to enable MIT-SHM. */
const val SYSVSHM_SOCKET_ENV: String = "WINE_SYSVSHM_SOCKET"

/**
 * The display number in a `DISPLAY` string, or 0 when there is not one.
 *
 * `:0`, `:0.0` and `localhost:0` all name display 0. Anything unparseable falls
 * back to 0 rather than failing, because the alternative is a session that
 * cannot start over a typo in a string this app writes itself.
 */
fun displayNumber(display: String): Int =
    display.substringAfterLast(':')
        .substringBefore('.')
        .trim()
        .toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: 0

/**
 * The unix socket name libxcb derives from `DISPLAY=:<number>`.
 *
 * Hard-coded in `_xcb_open()` as `/tmp/.X11-unix/X` plus the number, with no
 * environment variable that can move it. On Android that path can never exist,
 * which is why the server binds this name in the *abstract* namespace instead —
 * see `UnixSocketConfig.createAbstract`. Kept as a pure function so the one
 * string both halves depend on is asserted in a test rather than trusted.
 */
fun xSocketName(number: Int): String = "/tmp/.X11-unix/X$number"
