package app.vessel.core

import android.view.View
import app.vessel.input.GamepadControl
import app.vessel.input.InputProfile
import app.vessel.input.PointerMode
import app.vessel.input.TouchLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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

    /**
     * Where the window is, in **guest** pixels, relative to the desktop.
     *
     * Guest pixels and not view pixels, because the view's scale and letterbox
     * change with rotation and with `display.resolution` while this does not.
     * Anything drawing on top of a window converts through
     * [SessionDisplayServer.guestViewport], which is the one place that
     * mapping lives.
     */
    val bounds: WindowBounds = WindowBounds(),
)

/** A window rectangle in guest pixels. */
data class WindowBounds(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * How guest pixels land on the surface, so the shell can draw over a window.
 *
 * The X server renders the guest's [guestWidth] x [guestHeight] desktop into the
 * view scaled by [scale] and letterboxed by [offsetX]/[offsetY] — so a guest
 * point is `offset + guest * scale`, and that is the whole conversion. Published
 * rather than recomputed because the aspect fit is the renderer's decision
 * (`ViewTransformation.update`), and a second implementation of it in the UI
 * would be wrong the first time either dimension changed.
 *
 * All four are view pixels. [scale] is uniform: the fit preserves aspect.
 */
data class GuestViewport(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val guestWidth: Int = 0,
    val guestHeight: Int = 0,
) {
    /** A guest x in view pixels. */
    fun viewX(guestX: Int): Float = offsetX + guestX * scale

    /** A guest y in view pixels. */
    fun viewY(guestY: Int): Float = offsetY + guestY * scale

    /** A view distance back in guest pixels, for turning a drag into a resize. */
    fun toGuest(viewDistance: Float): Int =
        if (scale > 0f) (viewDistance / scale).toInt() else 0
}

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
     * The binding table the running session is using.
     *
     * On this seam alongside [pointerMode] and for the same stated reason: it is
     * a thing the *user* changes mid-session, from the Session rail, and the rail
     * has no reference to the view — it only knows this interface. A flow rather
     * than a getter so the panel's rows match reality after a session restart,
     * which resets it to whatever the container names.
     */
    val inputProfile: StateFlow<InputProfile>

    /**
     * Change what the pad sends, now.
     *
     * **Releases before it changes.** Every held control is let go first:
     * changing a binding while a key is down would otherwise leave the guest
     * holding a key that nothing left in the system can ever release, and the
     * symptom — a character that walks into a wall forever — looks nothing like
     * its cause.
     *
     * Live edits persist immediately and push immediately. A draft-with-Save was
     * rejected: the entire argument for editing bindings from inside a session is
     * that a change is visible the moment it is made.
     */
    fun setInputProfile(profile: InputProfile)

    /**
     * Whether the on-screen overlay is drawn and taking touches.
     *
     * Separate from the profile because it is about *this* container on *this*
     * run — a profile shared with a container played on a real pad should not
     * have to choose.
     */
    val touchControlsVisible: StateFlow<Boolean>

    fun setTouchControlsVisible(visible: Boolean)

    /**
     * Whether the overlay is being laid out rather than played.
     *
     * On the seam for the same reason [touchControlsVisible] is — the panel that
     * toggles it has no reference to the view — and separate from the profile
     * because it is a mode, not a setting: nothing about it is persisted and a
     * session that restarts comes back in play mode.
     *
     * **While it is true the overlay produces no [app.vessel.input.GuestInput] at
     * all.** Not a filtered subset — none, and every finger on the screen is
     * taken by the editor. A mode that half-forwarded would be one where you
     * rebind a button by accidentally shooting.
     */
    val touchEditing: StateFlow<Boolean>

    fun setTouchEditing(editing: Boolean)

    /**
     * The control the editor has selected, by id, or null.
     *
     * It lives here rather than in the panel because both ends write it: the
     * panel's list selects a row, and a finger on the overlay itself selects what
     * it lands on. Two copies would disagree the first time either did.
     */
    val selectedTouchControl: StateFlow<String?>

    fun selectTouchControl(id: String?)

    /**
     * Layouts the user dragged on the overlay itself, one per finished gesture.
     *
     * A flow out rather than a value read back, because the drag happens in the
     * view and the profile lives in a DataStore two layers away — and because the
     * *rate* matters: a move emits a new position every frame, and persisting each
     * would be sixty writes a second. The view publishes once, on release.
     *
     * Empty for [Absent], which has no view to drag anything on.
     */
    val touchLayoutEdits: Flow<TouchLayout>

    /**
     * Which physical pad controls are held down right now.
     *
     * **The thing that turns "which button is X on this pad?" into a press.** The
     * editor keeps feeding the translator while it is open, so a row highlights
     * the moment its control is pressed — which is the only honest way to answer
     * that question for an 8BitDo, a DualSense and a Backbone, all of which
     * report the same axes through this path.
     *
     * Empty when nothing is running, and empty for a session with no pad
     * attached, which is the same thing as far as anything reading it goes.
     */
    val heldControls: StateFlow<Set<GamepadControl>>

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
     * Whether the Windows desktop has actually appeared on this server.
     *
     * **Not the same as "the desktop process started", and the difference ends
     * sessions.** Wine's `explorer.exe /desktop=name,WxH` races to become the
     * owner of the named desktop: the first one to reach `wine_create_desktop`
     * wins and manages it, and every later one starts its command line and
     * *exits*. Vessel starts the bare desktop and then — a millisecond later,
     * as soon as the phase reads `RUNNING` — starts the program through a second
     * `explorer` with the same desktop name. Whichever loses that race exits, and
     * when the loser is the one this app is holding a `Process` for, the session
     * tears down while a perfectly healthy desktop and game keep running
     * underneath it.
     *
     * That is what "I launch a game and it drops me back to the home screen"
     * was. Measured on the device: `session ending: phase=EXITED exit=0
     * requested=false`, about 1.4 s in, with `explorer.exe /desktop=vessel` and
     * the game both still alive afterwards.
     *
     * The X server is the only thing that can answer honestly, because the
     * desktop existing *is* a window on it: the winning explorer maps one child
     * of the root at the full desktop size. False until then, and true for the
     * rest of the session.
     */
    val desktopUp: StateFlow<Boolean>

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
     * Move and resize a window, in guest pixels.
     *
     * **The shell's own window management, because the guest's was removed.**
     * `patches/wine/0010` strips `WS_CAPTION` and `WS_THICKFRAME` from every
     * top-level window, so there is no caption to drag and no sizing border to
     * pull — which is the point on a phone, where both are far too small to hit
     * and the caption alone cost 41 rows nothing ever painted. The shell puts
     * temporary drag borders on screen instead and calls this.
     *
     * Both the move and the resize are applied together because they are one
     * gesture: dragging a corner changes origin and size at once, and doing it
     * in two calls would show the window in a position it was never in.
     *
     * False for an id the server does not have — the same ordinary race
     * [focusWindow] tolerates.
     */
    fun moveResizeWindow(id: Int, x: Int, y: Int, width: Int, height: Int): Boolean

    /**
     * How guest pixels currently map onto the surface. See [GuestViewport].
     *
     * Changes on rotation, on a resolution change, and once at startup when the
     * surface first gets a size, so it is a flow rather than a getter.
     */
    val guestViewport: StateFlow<GuestViewport>

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

        // Headless: there is nothing to send the bindings to. The profile is
        // still reported so an editor opened over a headless session shows the
        // container's real table rather than an empty one — it simply has no
        // effect, which is what the whole of `Absent` is.
        override val inputProfile: StateFlow<InputProfile> =
            MutableStateFlow(InputProfile.Default).asStateFlow()

        override fun setInputProfile(profile: InputProfile) = Unit

        override val touchControlsVisible: StateFlow<Boolean> =
            MutableStateFlow(false).asStateFlow()

        override fun setTouchControlsVisible(visible: Boolean) = Unit

        // Headless: there is no overlay to lay out, so the editor's mode is a
        // constant and nothing can ever be dragged.
        override val touchEditing: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

        override fun setTouchEditing(editing: Boolean) = Unit

        override val selectedTouchControl: StateFlow<String?> =
            MutableStateFlow<String?>(null).asStateFlow()

        override fun selectTouchControl(id: String?) = Unit

        override val touchLayoutEdits: Flow<TouchLayout> = emptyFlow()

        // No view, so no pad events reach anything. An editor's live-press
        // indicator simply never lights, which is true.
        override val heldControls: StateFlow<Set<GamepadControl>> =
            MutableStateFlow(emptySet<GamepadControl>()).asStateFlow()

        // Headless: there are no windows, and saying so with an empty list is the
        // honest answer rather than a missing capability the taskbar has to guess at.
        override val windows: StateFlow<List<TopLevelWindow>> =
            MutableStateFlow(emptyList<TopLevelWindow>()).asStateFlow()

        // Nothing composites, so there is no rate. Zero with no history reads as
        // idle, which is what the readout draws as a dash — right for a headless
        // session, where the alternative is a counter reporting a stall.
        override val frameRate: StateFlow<FrameRate> =
            MutableStateFlow(FrameRate()).asStateFlow()

        // Headless: no desktop will ever appear, so a program launched into this
        // session must not sit waiting for one that cannot come.
        override val desktopUp: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

        override fun focusWindow(id: Int) = Unit

        // Headless: there is no window to ask and no process behind one.
        override fun minimizeWindow(id: Int): Boolean = false

        override fun closeWindow(id: Int): Boolean = false

        override fun killWindow(id: Int): Boolean = false

        override fun moveResizeWindow(id: Int, x: Int, y: Int, width: Int, height: Int) = false

        // Headless: nothing is rendered, so there is no mapping to publish. The
        // default is the identity rather than a zero scale, which would make any
        // caller that divided by it produce infinities instead of nothing.
        override val guestViewport: StateFlow<GuestViewport> =
            MutableStateFlow(GuestViewport()).asStateFlow()

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
