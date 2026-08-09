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

/**
 * The composited frame rate, and enough history to draw its shape.
 *
 * **Frames delivered to the screen, not a program's internal rate.** Vessel
 * composites on damage, so a title rendering faster than the surface reads as
 * the surface's rate and a title that has stopped drawing reads zero. That makes
 * this the honest answer to "is what I am looking at smooth", which is the
 * question a counter on a taskbar is asked, and the wrong answer to "how fast is
 * this engine running", which it must not be quoted for.
 *
 * @property fps frames per second over the last sample window, rounded for
 *   display but kept as a Float so the sparkline is not a staircase.
 * @property history most recent last, oldest first, so a chart can read it
 *   left-to-right without reversing. Bounded by [HISTORY].
 * @property limit the container's own `display.fpsLimit`, or null when it has
 *   none. The readout is coloured against this rather than against 60: a
 *   container asked to run at 30 and running at 30 is healthy, and colouring it
 *   amber for missing a number nobody asked for would be a lie in a traffic
 *   light.
 */
data class FrameRate(
    val fps: Float = 0f,
    val history: List<Float> = emptyList(),
    val limit: Int? = null,
) {
    /**
     * Nothing has been drawn for a whole window.
     *
     * Drawn as a dash rather than as `0`, because zero frames on an idle desktop
     * is not a performance reading — it is the absence of one, and a taskbar
     * flashing red `0` at a user who is simply not doing anything is the counter
     * lying about the thing it exists to report.
     */
    val idle: Boolean get() = fps <= 0f

    /** The rate this is healthy against: the container's limit, or 60. */
    val target: Int get() = limit ?: DEFAULT_TARGET

    companion object {
        /** Samples kept for the sparkline. At 500 ms a sample this is 20 seconds. */
        const val HISTORY: Int = 40

        /** What "smooth" means with no limit set. */
        const val DEFAULT_TARGET: Int = 60
    }
}

/**
 * One mapped top-level window inside the guest.
 *
 * [id] is the X window id, which is what [SessionDisplayServer.focusWindow]
 * takes back. [title] is `WM_NAME` — what the program's own title bar says, and
 * therefore the only string a user will recognise. A window that has not set one
 * yet reports an empty title rather than a placeholder; naming it "Untitled"
 * here would be this layer inventing a fact about the guest.
 */
data class TopLevelWindow(
    val id: Int,
    val title: String,
    val focused: Boolean,
    /**
     * The executable that owns the window, lowercased, no path — `conhost.exe`.
     *
     * Read from `WM_CLASS`, which Wine sets on every window it creates. It is
     * what lets the taskbar draw a terminal glyph for a console instead of the
     * letter C, without anything having to resolve the window back to a file on
     * the guest's disk. Empty when the window will not say.
     */
    val program: String = "",

    /**
     * Hidden by [SessionDisplayServer.minimizeWindow], and still listed.
     *
     * **A minimised window has to stay in the taskbar or it is gone for good.**
     * Iconifying in X11 is unmapping, and the window list is built from mapped
     * windows — so without this the button would disappear at the moment it
     * became the only way back. Wine's desktop has no taskbar of its own.
     */
    val minimized: Boolean = false,
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
     * Top-level guest windows, for the taskbar. Empty when nothing is running.
     *
     * On this seam and not somewhere in `data/` because the X server is the only
     * thing that knows. It already tracks every window's `WM_NAME` and fires on
     * map, unmap and property change, so this is a translation of something
     * present rather than new bookkeeping — and a flow rather than a poll for the
     * same reason: a taskbar refreshed on a timer lags the thing it describes.
     *
     * [TopLevelWindow] deliberately carries no `com.winlator` type. Everything
     * vendored stays on the far side of this interface, which is what lets the
     * taskbar be an ordinary Compose list.
     *
     * **A window here is one the guest mapped, and nothing else.** A program that
     * minimises to a tray icon leaves this list rather than docking somewhere:
     * receiving a tray icon needs a helper process inside the guest and this
     * project ships none. The taskbar says so in words; it must not quietly show
     * a stale entry to paper over it.
     */
    val windows: StateFlow<List<TopLevelWindow>>

    /**
     * How fast the guest's output is reaching the screen, sampled.
     *
     * **This counts composited frames, which is not the same as a game's
     * internal frame rate, and the difference is worth stating because the
     * number goes in front of the user.** The compositor draws when the guest
     * damages the screen, so this is *delivered* frames — what you are actually
     * being shown. A program rendering at 200 fps into a 60 Hz surface reads 60;
     * a program that has drawn nothing reads 0, and 0 is the truth rather than a
     * missing reading. [FrameRate.idle] is what tells the two apart for display
     * purposes.
     *
     * On this seam for the same reason [windows] is: the X server's renderer is
     * the only thing that knows, and nothing vendored crosses the interface.
     */
    val frameRate: StateFlow<FrameRate>

    /**
     * Raise a window and give it the input focus.
     *
     * A no-op for an id the server does not have. A taskbar button pressed while
     * the program is closing is an ordinary race, not an error worth surfacing.
     */
    fun focusWindow(id: Int)

    /**
     * Ask a window to close itself, and say whether anything was asked.
     *
     * **The taskbar could focus a window and could not close one**, and the only
     * other control ends the whole session — so a hung program meant stopping
     * everything else with it. This sends `WM_DELETE_WINDOW`, which
     * `winex11.drv` turns into `WM_CLOSE`, so the program gets to put up its
     * "save changes?" dialog. That is the reason to ask rather than kill.
     *
     * False means the window never advertised the protocol and nothing was
     * sent — the caller's cue to offer ending the process instead. It is not an
     * error, and false for an id the server does not have is the same ordinary
     * race [focusWindow] tolerates.
     */
    /**
     * Hide a window without closing it, and keep it in [windows].
     *
     * Unmaps it, which is how a window manager iconifies and what makes
     * `winex11.drv` report the Win32 window as minimised — so Wine agrees this
     * happened rather than being worked around. [focusWindow] restores it.
     *
     * False when the window is already hidden or gone.
     */
    fun minimizeWindow(id: Int): Boolean

    fun closeWindow(id: Int): Boolean

    /**
     * End the process that owns a window.
     *
     * **The escalation, and it is destructive by design.** No `WM_CLOSE`, no
     * dialog, no chance to save: the program is sent `SIGKILL`. It exists
     * because [closeWindow] cannot help with the case that most needs helping —
     * a program wedged so badly it is not reading its message queue — and
     * because a program that advertises no close protocol has no other way out.
     *
     * The pid comes from the window's own `_NET_WM_PID`, which `winex11.drv`
     * sets to the real unix pid: Wine's processes are ordinary Android
     * processes of this app's uid, so this app may signal them. Returns false
     * when the window set no pid, which is the honest answer — there is nothing
     * to kill and pretending otherwise would leave a dead button.
     */
    fun killWindow(id: Int): Boolean

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

        // Headless: there are no windows, and saying so with an empty list is the
        // honest answer rather than a missing capability the taskbar has to guess at.
        override val windows: StateFlow<List<TopLevelWindow>> =
            MutableStateFlow(emptyList<TopLevelWindow>()).asStateFlow()

        // Nothing composites, so there is no rate. Zero with no history reads as
        // idle, which is what the readout draws as a dash — right for a headless
        // session, where the alternative is a counter reporting a stall.
        override val frameRate: StateFlow<FrameRate> =
            MutableStateFlow(FrameRate()).asStateFlow()

        override fun focusWindow(id: Int) = Unit

        // Headless: there is no window to ask and no process behind one.
        override fun minimizeWindow(id: Int): Boolean = false

        override fun closeWindow(id: Int): Boolean = false

        override fun killWindow(id: Int): Boolean = false

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
