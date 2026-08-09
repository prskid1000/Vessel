package app.vessel.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import app.vessel.core.DisplayOutcome
import app.vessel.core.DisplayRequest
import app.vessel.core.SYSVSHM_SOCKET_ENV
import app.vessel.core.SessionDisplayServer
import app.vessel.core.TopLevelWindow
import app.vessel.core.displayNumber
import app.vessel.core.xSocketName
import app.vessel.input.GamepadControl
import app.vessel.input.GamepadTranslator
import app.vessel.input.GuestInput
import app.vessel.input.PointerButton
import app.vessel.input.PointerGestures
import app.vessel.input.PointerMode
import app.vessel.input.ScrollAxis
import app.vessel.input.SubPixel
import app.vessel.input.Touch
import app.vessel.input.TouchPhase
import app.vessel.input.X11
import app.vessel.input.X11KeyMap
import app.vessel.input.buttons
import app.vessel.input.toPixel
import com.winlator.renderer.ViewTransformation
import com.winlator.sysvshm.SysVSHMConnectionHandler
import com.winlator.sysvshm.SysVSHMRequestHandler
import com.winlator.sysvshm.SysVSharedMemory
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xserver.Atom
import com.winlator.xserver.events.ConfigureNotify
import com.winlator.xserver.events.Event
import com.winlator.xserver.Pointer
import com.winlator.xserver.Property
import com.winlator.xserver.SHMSegmentManager
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XClientConnectionHandler
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The vendored X server, wired to a real host.
 *
 * **This is the only file in `app.vessel` that imports `com.winlator`**, which is
 * the arrangement `com/winlator/README.md` asks for and the reason the seam in
 * `app.vessel.core.SessionDisplay` exists at all.
 *
 * ## Why the X socket is in the abstract namespace
 *
 * Wine's `winex11.drv` calls `XOpenDisplay`, libX11 hands the display string to
 * libxcb, and `_xcb_open()` turns `:0` into the unix socket `/tmp/.X11-unix/X0`.
 * That path is compiled in — there is no environment variable that moves it —
 * and Android has no `/tmp` and gives an app no way to make one. Winlator does
 * not hit this because its guest runs inside a proot rootfs where that path is a
 * real directory it owns; Vessel's guest is an ordinary child process of the app,
 * so there is no rootfs to put the socket in.
 *
 * The way out is that the libxcb in this Wine package is built with
 * `HAVE_ABSTRACT_SOCKETS` (verified in its `config.h`), and `_xcb_open()` tries
 * the **abstract** name `"\0/tmp/.X11-unix/X0"` before the filesystem one. An
 * abstract name has no filesystem, so binding it needs no writable directory —
 * and it was verified from inside this app's SELinux domain before any of this
 * was built. Wine therefore needs no patch and no configuration: `DISPLAY=:0`
 * simply works.
 *
 * The shared-memory socket is the opposite case and stays on disk, because the
 * guest reaches it through an absolute path in `WINE_SYSVSHM_SOCKET` rather than
 * a name a library already knows.
 *
 * ## What is not here
 *
 * No `WinHandler`. It is the guest-side helper Winlator uses for relative-mouse
 * mode and Win32 window activation; leaving it unset makes both inert and
 * nothing else, which the vendored `XServer` is written for.
 */
@Singleton
class XServerDisplay @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionDisplayServer {

    private val _surface = MutableStateFlow<View?>(null)
    override val surface: StateFlow<View?> = _surface.asStateFlow()

    private val _pointerMode = MutableStateFlow(PointerMode.TRACKPAD)
    override val pointerMode: StateFlow<PointerMode> = _pointerMode.asStateFlow()

    private val _windows = MutableStateFlow<List<TopLevelWindow>>(emptyList())
    override val windows: StateFlow<List<TopLevelWindow>> = _windows.asStateFlow()

    /** Serialises start against stop, so a fast Retry cannot bind X0 twice. */
    private val lifecycle = Mutex()

    private var session: DisplaySession? = null

    /**
     * Both of these are main-thread-only on the view side but are called from
     * the rail, which is already on it — so they post rather than assert. A
     * setter that threw when a user tapped a button would be a worse trade than
     * one frame of latency.
     */
    override fun setPointerMode(mode: PointerMode) {
        _pointerMode.value = mode
        val view = session?.view ?: return
        view.post { view.pointerMode = mode }
    }

    /**
     * Raise a window and focus it.
     *
     * Both halves are needed and they are different operations: raising changes
     * the Z order so the user can see it, focusing decides where the keyboard
     * goes. A taskbar press that did only the first would put the window in
     * front of a program that still has the keys.
     *
     * Silent for an unknown id — a press that races the program's own exit is an
     * ordinary thing to happen, not an error to report.
     */
    override fun focusWindow(id: Int) {
        session?.focusWindow(id)
    }

    override fun showKeyboard() {
        val view = session?.view ?: return
        view.post { view.showSoftKeyboard() }
    }

    override suspend fun start(request: DisplayRequest): DisplayOutcome = lifecycle.withLock {
        withContext(Dispatchers.Main) {
            teardown()
            try {
                val started = DisplaySession(context, request)
                session = started
                // The server publishes its window list into this flow. Wired
                // after construction rather than passed in, so DisplaySession
                // stays a thing that owns sockets and a view and knows nothing
                // about what the taskbar wants.
                started.onWindowsChanged = { list -> _windows.value = list }
                _surface.value = started.view
                DisplayOutcome.Started(started.environment)
            } catch (t: Throwable) {
                // Includes the RuntimeException XConnectorEpoll throws when bind
                // fails, which is the one failure a user can actually cause: a
                // previous session whose sockets have not been released yet.
                Log.e(TAG, "display server did not start", t)
                teardown()
                DisplayOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    override suspend fun stop() = lifecycle.withLock {
        withContext(Dispatchers.Main) { teardown() }
    }

    /** Main thread, lock held. */
    private fun teardown() {
        _surface.value = null
        // Cleared here and not on session end: a taskbar still showing the
        // windows of a session that has stopped is worse than an empty one.
        _windows.value = emptyList()
        session?.let { runCatching { it.stop() }.onFailure { e -> Log.w(TAG, "teardown", e) } }
        session = null
    }

    private companion object {
        const val TAG = "VesselDisplay"
    }
}

/**
 * One running display: two sockets, a server, and the view it composites into.
 *
 * Constructed on the main thread and thrown away whole. Anything that fails in
 * the constructor is cleaned up before it propagates, because a half-built
 * session holds a bound socket that the next attempt cannot rebind.
 */
private class DisplaySession(context: Context, request: DisplayRequest) {

    private val xServer = XServer(ScreenInfo(request.geometry.width, request.geometry.height))
    private val sharedMemory = SysVSharedMemory()
    private var xConnector: XConnectorEpoll? = null
    private var shmConnector: XConnectorEpoll? = null

    val view: SessionSurfaceView
    val environment: Map<String, String>

    /** Set by the adapter once construction has succeeded. */
    var onWindowsChanged: ((List<TopLevelWindow>) -> Unit)? = null

    /**
     * Publish the current top-level window list.
     *
     * Called from the X server's own threads, which is why the whole thing is
     * rebuilt and handed over as an immutable list rather than mutated in place:
     * the collector is a Compose recomposition on the main thread, and a list
     * being appended to underneath it is a crash waiting for the right timing.
     *
     * **Written against a dump of the real tree, after two wrong guesses.** Both
     * are worth recording, because both were plausible and both were confident.
     *
     * The first was that a nameless window is not worth listing. The second was
     * that under `explorer /desktop=` the guest's windows are children of the
     * virtual-desktop window and the walk therefore had to descend. The tree
     * says otherwise on both counts:
     *
     * ```
     * root
     *   id=8388615  mapped 1280x720  name=''  class='explorer.exe'  kids=8
     *   …
     *   id=29360129 mapped  656x400  name=''  class='conhost.exe'   kids=0
     * ```
     *
     * A console window plainly on screen is a **direct child of the root**, a
     * sibling of the desktop rather than a child of it — so the original flat
     * read was right and the walk that replaced it fixed nothing. And **no
     * window has a `WM_NAME` at all.** Wine sets `_NET_WM_NAME`, the UTF-8
     * property, and does not bother with the legacy one; the vendored server has
     * no constant for it because its `Atom` enum stops at the atoms Winlator
     * needed, so `Window.getName()` looked up an empty slot and every window
     * came back nameless. Dropping the nameless ones then dropped all of them.
     * That was the whole bug, and it was there before either rewrite.
     *
     * So: the root's children, and a title from whichever of three sources has
     * one. `_NET_WM_NAME` is looked up by name because it is interned at
     * runtime. `WM_CLASS` is the last resort rather than nothing — a button
     * saying `conhost` is worse than one saying "Command Prompt" and far better
     * than a taskbar that stays empty while a window is on screen.
     *
     * Skipped: the virtual desktop itself, which is the full-screen one; the
     * unmapped; and Wine's 1×1 message windows, of which there are a dozen and
     * none is a window in any sense a user would recognise.
     */
    private fun publishWindows() {
        val listener = onWindowsChanged ?: return
        val manager = xServer.windowManager
        val root = manager.rootWindow
        val focused = manager.focusedWindow?.id
        val list = root.children.mapNotNull { window ->
            if (!window.isRealWindow()) return@mapNotNull null
            if (window.isVirtualDesktop(root)) return@mapNotNull null
            TopLevelWindow(
                window.id,
                window.taskbarTitle(),
                focused = window.id == focused,
                program = window.programName(),
            )
        } + root.children
            // **And the windows inside the desktop, which is where they move to
            // the moment the prefix has a virtual desktop configured.**
            //
            // The two shapes are both real and the taskbar has to handle both. A
            // process that starts rootless puts its window beside the desktop,
            // under the root — that is what the tree dump caught, and it is what
            // the list above reads. Once `PrefixRegistry.virtualDesktop` is
            // seeded, the same window is created *inside* the desktop instead,
            // and reading only the root's children would find nothing but the
            // desktop itself, which is skipped. Adding the caption also took the
            // taskbar away, and one without the other is no good.
            //
            // One level, not a walk. A window inside the desktop is a top-level
            // window; anything below *it* is Wine's own client area.
            .filter { it.isVirtualDesktop(root) }
            .flatMap { desktop ->
                desktop.children.mapNotNull { window ->
                    if (!window.isRealWindow()) return@mapNotNull null
                    TopLevelWindow(
                        window.id,
                        window.taskbarTitle(),
                        focused = window.id == focused,
                        program = window.programName(),
                    )
                }
            }
        // **By id, which is creation order — not by the order they are stacked
        // in.** The list above walks the X child list, and that list *is* the Z
        // order: raising a window moves it to the end. So tapping the first
        // button raised the right window and then redrew it as the *second*
        // button, highlighted — which reads exactly like the wrong button
        // lighting up, and was reported as such. A taskbar button has to stay
        // where the user last saw it; the only thing a switch may move is the
        // highlight.
        //
        // X ids rise as windows are created, per client, so this is creation
        // order in practice and is stable for as long as a window lives — which
        // is the whole requirement.
        listener(list.sortedBy { it.id })
        if (Log.isLoggable(TREE_TAG, Log.DEBUG)) {
            // The tree, whenever it changes, at a tag nothing enables by
            // default. Deciding which window is a taskbar entry is a rule about
            // a shape this code cannot see from here — parents, names, sizes —
            // and every wrong answer so far has been a wrong belief about that
            // shape rather than a wrong rule. `adb shell setprop log.tag.
            // VesselWindows DEBUG` is cheaper than another round of guessing.
            Log.d(TREE_TAG, "published ${list.size}: ${list.joinToString { it.title }}")
            dumpTree(manager.rootWindow, 0)
        }
    }

    private fun dumpTree(window: Window, depth: Int) {
        if (depth > MAX_WINDOW_DEPTH) return
        for (child in window.children) {
            Log.d(
                TREE_TAG,
                "  ".repeat(depth + 1) +
                    "id=${child.id} mapped=${child.attributes.isMapped} " +
                    "${child.width}x${child.height} name='${child.name}' " +
                    "class='${child.className}' kids=${child.children.size}",
            )
            dumpTree(child, depth + 1)
        }
    }

    /**
     * Step a new window off the one already under it.
     *
     * **The single fact behind three complaints.** Every console opens at the
     * same place — `conhost` takes its geometry from `HKCU\Console`, so the
     * second is a pixel-perfect cover for the first. That is why switching
     * looked broken (the raise worked; both windows are identical so nothing
     * appeared to change), why min/max/close are hard to aim at, and why the
     * desktop reads as one window rather than several.
     *
     * A window manager's job, done the way one does it: only when the position
     * is already taken, one step of [CASCADE_STEP] per occupied slot, wrapped
     * back to the origin before it walks off the desktop. A window the guest
     * placed deliberately somewhere free is left exactly where it asked to be.
     *
     * **The client is told, and that is the part that must not be skipped.**
     * Moving the X window without a `ConfigureNotify` would leave Wine drawing
     * its caption and borders against a stale rectangle, so the title bar would
     * drag from the wrong place — worse than not moving it. Both events go out:
     * `STRUCTURE_NOTIFY` to the window for Wine's own top-level bookkeeping,
     * `SUBSTRUCTURE_NOTIFY` to the desktop that owns it, which is exactly what
     * [WindowManager.configureWindow] sends when a client asks for the same
     * thing.
     */
    private fun cascade(window: Window) {
        val desktop = window.parent ?: return

        // **Centred, then stepped off whatever is already there.**
        //
        // Cascading alone was not enough and the reason is easy to miss: it only
        // moved a window whose position was *already taken*, so the first window
        // of a session — the common case, and the one anybody looks at first —
        // stayed exactly where Wine put it, hard against the top-left corner
        // with the whole desktop empty to its right.
        //
        // Only a window that did not ask for a position. `CW_USEDEFAULT` comes
        // through as the desktop origin, so a window at 0,0 is one nobody
        // placed; a window anywhere else was positioned by the program — a game
        // restoring its saved geometry, a dialog put beside its parent — and
        // moving that is a window manager overruling a deliberate decision.
        // (The proper X11 test is `WM_NORMAL_HINTS` `USPosition`/`PPosition`,
        // which this server does not parse. The origin is the approximation, and
        // it is wrong only for a program that genuinely wants 0,0.)
        val unplaced = window.getX().toInt() <= 0 && window.getY().toInt() <= 0
        val maxX = (desktop.width - window.width).coerceAtLeast(0)
        val maxY = (desktop.height - window.height).coerceAtLeast(0)
        var wantX = if (unplaced) maxX / 2 else window.getX().toInt()
        var wantY = if (unplaced) maxY / 2 else window.getY().toInt()

        // One step per attempt rather than one per existing window: counting
        // windows put the third window two steps away from a slot that may have
        // been freed by the second one closing, which cascades into empty space.
        var steps = 0
        while (steps < CASCADE_WRAP && occupied(desktop, window, wantX, wantY)) {
            wantX += CASCADE_STEP
            wantY += CASCADE_STEP
            steps++
        }

        val x = wantX.coerceIn(0, maxX).toShort()
        val y = wantY.coerceIn(0, maxY).toShort()
        if (x == window.getX() && y == window.getY()) return

        runCatching {
            window.setX(x)
            window.setY(y)
            xServer.windowManager.triggerOnUpdateWindowGeometry(window, false)
            val above = window.previousSibling()
            val notify = ConfigureNotify(
                window, window, above, x.toInt(), y.toInt(),
                window.width.toInt(), window.height.toInt(),
                window.borderWidth.toInt(), window.attributes.isOverrideRedirect,
            )
            window.sendEvent(Event.STRUCTURE_NOTIFY, notify)
            desktop.sendEvent(
                Event.SUBSTRUCTURE_NOTIFY,
                ConfigureNotify(
                    desktop, window, above, x.toInt(), y.toInt(),
                    window.width.toInt(), window.height.toInt(),
                    window.borderWidth.toInt(), window.attributes.isOverrideRedirect,
                ),
            )
        }.onFailure { Log.w("VesselDisplay", "could not place a new window", it) }
    }

    /** Whether another real window already has this exact top-left corner. */
    private fun occupied(desktop: Window, window: Window, x: Int, y: Int): Boolean =
        desktop.children.any { other ->
            other !== window && other.isRealWindow() &&
                other.getX().toInt() == x && other.getY().toInt() == y
        }

    /**
     * Mapped, and bigger than Wine's message-only plumbing.
     *
     * Wine litters both the root and the desktop with 1×1 windows it uses to
     * receive messages — a dozen in one session. None is a window in any sense a
     * user would recognise, and none is ever mapped for long.
     */
    private fun Window.isRealWindow(): Boolean =
        attributes.isMapped && width > MIN_WINDOW_EDGE && height > MIN_WINDOW_EDGE

    /**
     * The `explorer /desktop=` window: the one that covers the whole screen.
     *
     * Their sibling before the prefix is configured for one and their parent
     * after, so size rather than position is what tells it apart. That is safe
     * here for a reason worth stating: this
     * product always runs under a virtual desktop, and a guest window can only
     * ever be as large as the desktop it is inside — never larger, and if it
     * were exactly as large it would be indistinguishable, which is a maximised
     * window and the one case where losing the taskbar button is survivable.
     */
    private fun Window.isVirtualDesktop(root: Window): Boolean =
        width >= root.width && height >= root.height

    /** `WM_CLASS`, trimmed to a bare lowercase filename. */
    private fun Window.programName(): String =
        className.orEmpty().trim().substringAfterLast(GUEST_PATH_SEPARATOR, className.orEmpty()).lowercase()

    /**
     * What the taskbar button says, from whichever source has an answer.
     *
     * `_NET_WM_NAME` first because it is the one Wine actually sets, and it is
     * fetched by name because the vendored `Atom` table has no constant for it —
     * it is interned when the first client asks for it, so the id is not known
     * until runtime and is looked up rather than hard-coded.
     */
    private fun Window.taskbarTitle(): String {
        val netWmName = Atom.getId(NET_WM_NAME)
            .takeIf { it > 0 }
            ?.let { getProperty(it)?.toString() }
            ?.trim()
        if (!netWmName.isNullOrEmpty()) return netWmName

        val wmName = name.orEmpty().trim()
        if (wmName.isNotEmpty()) return wmName

        // `conhost.exe` becomes `conhost`. Not a title, but it names the program
        // the window belongs to, which is what the user is choosing between.
        val className = className.orEmpty().trim().substringAfterLast('\\')
        return className.removeSuffix(".exe").ifEmpty { UNTITLED_WINDOW }
    }

    /**
     * Raise then focus. See the note on the adapter's `focusWindow`.
     *
     * Built from primitives the vendored server already exposes —
     * `moveChildAbove`, `triggerOnChangeWindowZOrder`, `setFocus` — rather than
     * by adding a method to `com.winlator`. Every line not added there is a line
     * that does not have to be re-applied when the vendored tree is updated.
     *
     * `moveChildAbove(window, null)` is a raise to the top: the sibling argument
     * is what it goes above, and null means "above all of them". The trigger
     * afterwards is not optional — Z order is what the compositor draws from, and
     * without it the window is logically in front and visually still behind.
     */
    fun focusWindow(id: Int) {
        val manager = xServer.windowManager
        val window = manager.getWindow(id) ?: return
        runCatching {
            window.parent?.moveChildAbove(window, null)
            manager.triggerOnChangeWindowZOrder(window)
            manager.setFocus(window, WindowManager.FocusRevertTo.PARENT)
        }.onFailure { Log.w("VesselDisplay", "could not focus window $id", it) }
        publishWindows()
    }

    init {
        xServer.setDebugSink { line -> Log.d("VesselDisplay", line) }
        // MIT-SHM is advertised unconditionally by XServer.setupExtensions, and
        // its Attach handler dereferences this manager without a null check, so
        // it is set before a client can possibly connect rather than lazily.
        xServer.setSHMSegmentManager(SHMSegmentManager(sharedMemory))

        val display = displayNumber(request.display)
        val socket = UnixSocketConfig.create(
            request.socketRoot.absolutePath,
            UnixSocketConfig.SYSVSHM_SERVER_PATH,
        )

        val surface = SessionSurfaceView(context, xServer, request.fpsLimit)
        view = surface
        xServer.setRenderer(surface.renderer)

        // The taskbar's source of truth. Four of the seven callbacks matter:
        // map and unmap are a window appearing and going away, a property change
        // is usually the title arriving a moment after the map, and a Z-order
        // change is what moves the focus highlight. The other three are geometry
        // and attribute churn that no taskbar entry reflects, and subscribing to
        // them would republish the whole list on every drag of a guest window.
        xServer.windowManager.addOnWindowModificationListener(
            object : WindowManager.OnWindowModificationListener {
                override fun onMapWindow(window: Window) {
                    // **Focus what just appeared, because nothing else will.**
                    // `setFocus` was called from exactly one place — a taskbar
                    // press — so a window that opened and was never tapped never
                    // held the X input focus, and the server delivered every key
                    // to the root instead of to it. That is the same defect from
                    // two directions: typing did not reach the guest, and the
                    // window the user was looking at was not the one listening.
                    //
                    // A real window only. Wine maps a dozen 1x1 message windows
                    // per session and focusing one of those would take the
                    // keyboard away from the program that has it.
                    if (window.isRealWindow()) {
                        cascade(window)
                        runCatching {
                            xServer.windowManager.setFocus(
                                window,
                                WindowManager.FocusRevertTo.PARENT,
                            )
                        }.onFailure { Log.w("VesselDisplay", "could not focus a new window", it) }
                    }
                    publishWindows()
                }
                override fun onUnmapWindow(window: Window) = publishWindows()
                override fun onChangeWindowZOrder(window: Window) = publishWindows()
                override fun onModifyWindowProperty(window: Window, property: Property) =
                    publishWindows()
            },
        )

        try {
            xConnector = XConnectorEpoll(
                UnixSocketConfig.createAbstract(xSocketName(display)),
                XClientConnectionHandler(xServer),
                XClientRequestHandler(),
            ).apply {
                // DRI3 takes the buffer's descriptor out of the request's
                // ancillary data; without this the stream is read with read(2)
                // and PixmapFromBuffer gets -1 for its fd.
                setCanReceiveAncillaryMessages(true)
                // A thread per client. The X protocol is request/reply and a
                // blocking reply on the shared epoll thread stalls every other
                // client, wineserver's included.
                setMultithreadedClients(true)
                setInitialInputBufferCapacity(CLIENT_BUFFER_BYTES)
                setInitialOutputBufferCapacity(CLIENT_BUFFER_BYTES)
                start()
            }

            shmConnector = XConnectorEpoll(
                socket,
                SysVSHMConnectionHandler(sharedMemory),
                SysVSHMRequestHandler(),
            ).apply { start() }
        } catch (t: Throwable) {
            stop()
            throw t
        }

        environment = buildMap {
            // Not request.display verbatim: this is the socket that was actually
            // bound, and saying anything else here is a `cannot open display`
            // two layers away from the mistake.
            put("DISPLAY", ":$display")
            // patches/wine/0005 reads this and nothing else; unset, winex11 uses
            // XPutImage exactly as upstream does. sun_path is 108 bytes and the
            // patch refuses anything longer, so a path that would not fit is
            // dropped here — slower, but not a silent connect(2) failure per
            // damaged region.
            if (socket.path.length < SUN_PATH_MAX) {
                put(SYSVSHM_SOCKET_ENV, socket.path)
            } else {
                Log.w(
                    "VesselDisplay",
                    "sysvshm socket path is ${socket.path.length} bytes and will not fit in " +
                        "sun_path; MIT-SHM stays off and winex11 will use XPutImage",
                )
            }
        }
    }

    fun stop() {
        // Connectors first, view second. Destroying a connector joins its epoll
        // thread, which calls back into the server to free each client's
        // resources; doing that while the GL thread is already parked by
        // onPause() would leave those callbacks waiting on locks nobody releases.
        xConnector?.destroy()
        xConnector = null
        shmConnector?.destroy()
        shmConnector = null
        sharedMemory.deleteAll()
        view.shutdown()
    }

    private companion object {
        /**
         * 64 bytes — the vendored default — is one request. Every X client here
         * is Wine, which pipelines, so the buffer is grown on the first frame and
         * every frame after; starting at 64 KB is one allocation instead of ten.
         */
        const val CLIENT_BUFFER_BYTES = 64 * 1024

        /** `sizeof(sockaddr_un.sun_path)` on Linux, and what patch 0005 checks. */
        const val SUN_PATH_MAX = 108

        /**
         * How far down the window tree the taskbar walk goes.
         *
         * Wine needs three — root, desktop, window — and a fourth for the client
         * area some versions still create. Eight is that with room to spare, and
         * it exists so a client that nests windows without bound cannot recurse
         * this off the X server's thread.
         */
        const val MAX_WINDOW_DEPTH = 8

        /** Off unless `setprop log.tag.VesselWindows DEBUG`. See [publishWindows]. */
        const val TREE_TAG = "VesselWindows"

        /** A Windows path separator, as a char. */
        const val GUEST_PATH_SEPARATOR: Char = 92.toChar()

        /** Interned at runtime; the vendored `Atom` table has no constant for it. */
        const val NET_WM_NAME = "_NET_WM_NAME"

        /**
         * Wine litters the root with 1×1 message-only windows — a dozen of them
         * in one session. Anything this small is plumbing, not a window.
         */
        const val MIN_WINDOW_EDGE = 1

        /** A mapped window that will not say what it is. Should not happen. */
        const val UNTITLED_WINDOW = "Window"

        /**
         * How far a cascaded window steps, in guest pixels.
         *
         * A caption's height plus a little, so the one underneath stays
         * clickable by its title bar — which is the whole point of cascading
         * rather than tiling.
         */
        const val CASCADE_STEP = 48

        /** How many steps before the cascade returns to the origin. */
        const val CASCADE_WRAP = 6
    }
}

/**
 * The one place `GuestInput` becomes an X11 event.
 *
 * Every input source in this file — fingers, a real mouse, a keyboard, a pad —
 * produces `GuestInput` and stops there. That is what lets the gesture machine
 * and the gamepad translator be plain unit-tested Kotlin with no `XServer` in
 * sight; this class is the part that cannot be tested that way, so it is kept
 * as close to a `when` over a sealed type as it can be.
 */
private class GuestInputSink(
    private val xServer: XServer,
    private val transformation: () -> ViewTransformation,
    private val viewSize: () -> Pair<Int, Int>,
) {

    private val subPixel = SubPixel()

    fun accept(inputs: List<GuestInput>) = inputs.forEach { accept(it) }

    fun accept(input: GuestInput) {
        when (input) {
            is GuestInput.MoveTo -> moveTo(input.x, input.y)
            is GuestInput.MoveBy -> moveBy(input.dx, input.dy)
            is GuestInput.Button -> button(input.button, input.pressed)
            is GuestInput.Scroll -> input.buttons().forEach { button(it.button, it.pressed) }
            is GuestInput.Zoom -> zoom(input.ticks)
            is GuestInput.Key -> key(input)
        }
    }

    /** Anything held, released. Called when the view loses its window. */
    fun releaseAll() {
        PointerButton.entries.forEach { xServer.injectPointerButtonRelease(it.toVendored()) }
        subPixel.reset()
    }

    /**
     * View pixels to X screen pixels.
     *
     * The compositor letterboxes: [ViewTransformation] fits the desktop into the
     * surface at one uniform scale and centres what is left over. Undoing exactly
     * that is what keeps the cursor under the finger — scaling by the view's own
     * size instead puts it progressively further off towards the edges, which
     * reads as "the touch is broken" rather than "the maths is wrong".
     */
    private fun moveTo(viewX: Float, viewY: Float) {
        val t = transformation()
        val screen = xServer.screenInfo
        val (w, h) = viewSize()
        val x: Int
        val y: Int
        if (t.aspect > 0f) {
            x = ((viewX - t.viewOffsetX) / t.aspect).toPixel()
            y = ((viewY - t.viewOffsetY) / t.aspect).toPixel()
        } else {
            // Before the first onSurfaceChanged there is no transformation yet.
            // Stretching to the view is wrong, but it is wrong for one frame and
            // the alternative is dropping the event that started the gesture.
            x = (viewX * screen.width / w.coerceAtLeast(1)).toPixel()
            y = (viewY * screen.height / h.coerceAtLeast(1)).toPixel()
        }
        xServer.injectPointerMove(
            x.coerceIn(0, screen.width - 1),
            y.coerceIn(0, screen.height - 1),
        )
    }

    /**
     * A relative move, in view pixels, converted at the same scale.
     *
     * Dividing by the letterbox scale is what makes the cursor keep up with the
     * finger: on a 1280×720 desktop letterboxed into a 2712 px panel the scale is
     * over two, and skipping this makes a trackpad drag feel like it is moving
     * through treacle at exactly the moment the desktop looks fine.
     *
     * `injectPointerMoveDelta` rather than a position of our own, because a
     * fullscreen guest that has warped the cursor is the authority on where it
     * is and a cached copy here would fight it.
     */
    private fun moveBy(dx: Float, dy: Float) {
        val scale = transformation().aspect.takeIf { it > 0f } ?: 1f
        val (x, y) = subPixel.take(dx / scale, dy / scale)
        if (x != 0 || y != 0) xServer.injectPointerMoveDelta(x, y)
    }

    private fun button(button: PointerButton, pressed: Boolean) {
        val vendored = button.toVendored()
        if (pressed) {
            xServer.injectPointerButtonPress(vendored)
        } else {
            xServer.injectPointerButtonRelease(vendored)
        }
    }

    /**
     * `Ctrl` held across the whole run of detents.
     *
     * The ordering is the reason `GuestInput.Zoom` is not decomposed by the
     * caller: a modifier that goes down after the first detent zooms once and
     * then scrolls, which reads as the pinch being ignored.
     */
    private fun zoom(ticks: Int) {
        if (ticks == 0) return
        xServer.injectKeyPress(X11.CTRL_L.toByte(), 0)
        accept(GuestInput.Scroll(ScrollAxis.VERTICAL, ticks))
        xServer.injectKeyRelease(X11.CTRL_L.toByte())
    }

    private fun key(key: GuestInput.Key) {
        val keycode = key.keycode.toByte()
        if (key.pressed) {
            xServer.injectKeyPress(keycode, key.keysym)
        } else {
            xServer.injectKeyRelease(keycode)
        }
    }

    /**
     * `PointerButton` is a copy of `Pointer.Button` — see `GuestInput` for why —
     * and this is the crossing. By ordinal rather than by name because the two
     * disagree on the last two: X calls buttons 6 and 7 horizontal scroll, the
     * vendored enum calls them `SCROLL_CLICK_LEFT`/`RIGHT`. The orders match, and
     * `PointerButtonMapTest` is what keeps them matching.
     */
    private fun PointerButton.toVendored(): Pointer.Button = VENDORED_BUTTONS[ordinal]

    private companion object {
        val VENDORED_BUTTONS: Array<Pointer.Button> = Pointer.Button.values()
    }
}

/**
 * The compositor plus input, as one focusable view.
 *
 * Winlator's own display activity puts a stack of overlays over `XServerView` —
 * a touchpad widget, an on-screen gamepad, a magnifier — none of which is
 * vendored. What is here instead is `app.vessel.input`: one gesture machine for
 * fingers, one translator for pads, and [GuestInputSink] between them and the
 * server. A real mouse and a real keyboard bypass both, because there is nothing
 * to interpret — the hardware already means what it says.
 */
private class SessionSurfaceView(
    context: Context,
    private val xServer: XServer,
    fpsLimit: Int?,
) : FrameLayout(context) {

    private val xServerView = PacedXServerView(context, xServer, fpsLimit)

    val renderer get() = xServerView.renderer

    private val sink = GuestInputSink(
        xServer = xServer,
        transformation = { renderer.viewTransformation },
        viewSize = { width to height },
    )
    private val gestures = PointerGestures()
    private val gamepad = GamepadTranslator()
    private val handler = Handler(Looper.getMainLooper())

    /** Trackpad or direct touch. Read and written by the Session rail. */
    var pointerMode: PointerMode
        get() = gestures.mode
        set(value) {
            sink.accept(gestures.reset())
            gestures.mode = value
        }

    private val longPress = Runnable { sink.accept(gestures.onTimeout(now())) }

    /** Only alive while a look stick is off centre; see [GamepadTranslator.looking]. */
    private val look = object : Runnable {
        override fun run() {
            sink.accept(gamepad.tick(now()))
            if (gamepad.looking) handler.postDelayed(this, LOOK_INTERVAL_MS)
        }
    }
    private var looking = false

    init {
        addView(xServerView)
        // Key events go to the focused view, and Compose gives an AndroidView no
        // focus of its own. FOCUS_BLOCK_DESCENDANTS keeps it here rather than on
        // the GLSurfaceView, which has no key handling.
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        xServerView.onResume()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        // A view that goes away mid-drag leaves the guest holding the button
        // forever, and the symptom — every later click selecting a rectangle —
        // looks nothing like its cause.
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(look)
        looking = false
        sink.accept(gestures.reset())
        sink.accept(gamepad.reset())
        sink.releaseAll()
        super.onDetachedFromWindow()
    }

    /** Release the GL thread. Separate from detach: the view outlives composition. */
    fun shutdown() = xServerView.onPause()

    /** The rail's keyboard button. There is no hardware keyboard on most phones. */
    fun showSoftKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Yes, this view takes text — which is the only reason the IME will open.
     *
     * `showSoftInput` is not a request the framework honours for an arbitrary
     * view: it asks the *focused* view for an `InputConnection` first, and a view
     * that answers null gets no keyboard and no error. The rail's keyboard button
     * did nothing at all for exactly this reason.
     */
    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * `TYPE_NULL` is load-bearing.
     *
     * It tells the IME there is no text field to edit, which makes a soft
     * keyboard fall back to delivering raw `KeyEvent`s instead of composing text
     * against an editor that does not exist. Those arrive at [dispatchKeyEvent]
     * with `deviceId == VIRTUAL_KEYBOARD` and go to the vendored path, which is
     * written for them — it carries `getUnicodeChar` and `ACTION_MULTIPLE`, so a
     * character no keycode names still reaches the guest.
     *
     * `fullEditor = false` for the same reason: a full editor would have
     * `BaseInputConnection` buffer the text locally and never send a key at all.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return BaseInputConnection(this, false)
    }

    // — fingers ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(event)

        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchPhase.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchPhase.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchPhase.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchPhase.POINTER_UP
            MotionEvent.ACTION_UP -> TouchPhase.UP
            MotionEvent.ACTION_CANCEL -> TouchPhase.CANCEL
            else -> return false
        }

        sink.accept(gestures.onTouch(phase, event.touches(), event.eventTime))

        // Rescheduled from scratch every event rather than cancelled selectively:
        // the machine owns the deadline and this only mirrors it, so there is no
        // second copy of the long-press rule to get out of step.
        handler.removeCallbacks(longPress)
        gestures.timeoutAt?.let { handler.postDelayed(longPress, (it - now()).coerceAtLeast(0)) }
        return true
    }

    /** Every finger currently down, in the order Android indexes them. */
    private fun MotionEvent.touches(): List<Touch> =
        (0 until pointerCount).map { Touch(getPointerId(it), getX(it), getY(it)) }

    // — a real mouse ------------------------------------------------------------------

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return joystick(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                sink.accept(GuestInput.MoveTo(event.x, event.y))
                true
            }

            MotionEvent.ACTION_SCROLL -> {
                scroll(event)
                true
            }

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouse(event)

            else -> super.onGenericMotionEvent(event)
        }
    }

    /**
     * A real mouse: position from the event, buttons from its whole state.
     *
     * State rather than edges because `Pointer.setButton` already compares
     * against what it holds and only fires on a change — so replaying the full
     * button state on every event cannot double-press, and cannot miss a release
     * that arrived inside a gesture Android coalesced.
     */
    private fun mouse(event: MotionEvent): Boolean {
        val state = event.buttonState
        sink.accept(
            listOf(
                GuestInput.MoveTo(event.x, event.y),
                GuestInput.Button(PointerButton.LEFT, state and MotionEvent.BUTTON_PRIMARY != 0),
                GuestInput.Button(PointerButton.RIGHT, state and MotionEvent.BUTTON_SECONDARY != 0),
                GuestInput.Button(PointerButton.MIDDLE, state and MotionEvent.BUTTON_TERTIARY != 0),
            ),
        )
        return true
    }

    private fun scroll(event: MotionEvent) {
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        // A wheel reports whole detents already, so this rounds rather than
        // accumulating: a high-resolution wheel that reports 0.25 at a time would
        // otherwise scroll nothing at all.
        if (abs(vertical) >= SCROLL_DEADZONE) {
            sink.accept(GuestInput.Scroll(ScrollAxis.VERTICAL, vertical.roundAwayFromZero()))
        }
        if (abs(horizontal) >= SCROLL_DEADZONE) {
            sink.accept(GuestInput.Scroll(ScrollAxis.HORIZONTAL, horizontal.roundAwayFromZero()))
        }
    }

    private fun Float.roundAwayFromZero(): Int =
        if (this > 0) kotlin.math.ceil(this).toInt() else kotlin.math.floor(this).toInt()

    // — gamepad -------------------------------------------------------------------------

    private fun joystick(event: MotionEvent): Boolean {
        sink.accept(
            gamepad.onSticks(
                lx = event.getAxisValue(MotionEvent.AXIS_X),
                ly = event.getAxisValue(MotionEvent.AXIS_Y),
                rx = event.getAxisValue(MotionEvent.AXIS_Z),
                ry = event.getAxisValue(MotionEvent.AXIS_RZ),
            ),
        )
        sink.accept(gamepad.onHat(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y)))
        // BRAKE/GAS are what a pad reports when it has no LTRIGGER/RTRIGGER axis;
        // taking the larger covers both without knowing which kind this is.
        sink.accept(
            gamepad.onTrigger(
                GamepadControl.L2,
                maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
            ),
        )
        sink.accept(
            gamepad.onTrigger(
                GamepadControl.R2,
                maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
            ),
        )
        syncLookTimer()
        return true
    }

    /** A held stick emits no events at all, so the cursor needs a heartbeat. */
    private fun syncLookTimer() {
        if (gamepad.looking && !looking) {
            looking = true
            gamepad.tick(now())   // seed, so the first real tick has an interval
            handler.postDelayed(look, LOOK_INTERVAL_MS)
        } else if (!gamepad.looking && looking) {
            looking = false
            handler.removeCallbacks(look)
        }
    }

    // — keyboard ------------------------------------------------------------------

    /**
     * Three sources, three paths.
     *
     * Back is excluded first and unconditionally. `Keyboard.onKeyEvent` does not
     * map it either, but saying so here is what guarantees the Session screen's
     * rail — the only way to reach Stop once a desktop is on screen — cannot be
     * swallowed by a guest that has grabbed the keyboard.
     *
     * A **pad** goes to the translator. A **soft keyboard** goes to the vendored
     * path, which is written for exactly that: it carries `getUnicodeChar` and
     * `ACTION_MULTIPLE`, so an IME can type a character no keycode names. A
     * **physical keyboard** goes to [X11KeyMap], because the vendored path
     * synthesises a `Shift_L` press from `isShiftPressed()` and releases it on
     * any key up, which turns a held shift into `Abc`.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        gamepadControl(event)?.let { control ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> sink.accept(gamepad.onButton(control, true))
                KeyEvent.ACTION_UP -> sink.accept(gamepad.onButton(control, false))
            }
            return true
        }

        if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) {
            return xServer.keyboard.onKeyEvent(event) || super.dispatchKeyEvent(event)
        }

        val binding = X11KeyMap[event.keyCode] ?: return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN ->
                sink.accept(X11KeyMap.edgesForDown(binding, repeat = event.repeatCount > 0))

            KeyEvent.ACTION_UP ->
                sink.accept(GuestInput.Key(binding.keycode, pressed = false))

            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * The pad button this event is, or null if it is not from a pad.
     *
     * Sourced rather than keycode-only: `KEYCODE_DPAD_LEFT` arrives from a pad
     * and from an arrow key, and the two want different destinations.
     */
    private fun gamepadControl(event: KeyEvent): GamepadControl? {
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!fromPad) return null
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> GamepadControl.A
            KeyEvent.KEYCODE_BUTTON_B -> GamepadControl.B
            KeyEvent.KEYCODE_BUTTON_X -> GamepadControl.X
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadControl.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadControl.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadControl.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadControl.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> GamepadControl.R2
            KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadControl.SELECT
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE -> GamepadControl.START
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadControl.THUMB_L
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadControl.THUMB_R
            KeyEvent.KEYCODE_DPAD_UP -> GamepadControl.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GamepadControl.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GamepadControl.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadControl.DPAD_RIGHT
            else -> null
        }
    }

    private fun now() = SystemClock.uptimeMillis()

    private companion object {
        /** Below this a wheel event is a stray sub-detent report, not a scroll. */
        const val SCROLL_DEADZONE = 0.1f

        /** ~120 Hz. Faster than the panel is pointless; slower is visibly steppy. */
        const val LOOK_INTERVAL_MS = 8L
    }
}

/**
 * `XServerView`, with `display.fpsLimit` actually applied.
 *
 * The renderer is `RENDERMODE_WHEN_DIRTY` and asks for a frame on every damaged
 * region, so a guest redrawing at 300 Hz costs 300 composites on a panel that
 * presents 165. Dropping the surplus outright would leave the last damage
 * unpresented, so a frame that arrives inside the budget schedules one trailing
 * render that carries everything since.
 */
private class PacedXServerView(
    context: Context,
    xServer: XServer,
    fpsLimit: Int?,
) : XServerView(context, xServer) {

    private val minFrameNanos: Long =
        fpsLimit?.takeIf { it > 0 }?.let { NANOS_PER_SECOND / it } ?: 0L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastFrameNanos = 0L

    @Volatile
    private var pending = false

    private val trailing = Runnable {
        pending = false
        lastFrameNanos = System.nanoTime()
        renderNow()
    }

    init {
        // Keep the EGL context across a pause, so leaving the desktop and coming
        // back does not throw away every window texture in the first place.
        //
        // A hint, not a guarantee — the driver is free to drop the context
        // anyway, and some do under memory pressure. `Texture`'s generation
        // counter is what makes that survivable; this is what makes it rare.
        preserveEGLContextOnPause = true
    }

    override fun requestRender() {
        if (minFrameNanos == 0L) {
            super.requestRender()
            return
        }
        val now = System.nanoTime()
        val wait = minFrameNanos - (now - lastFrameNanos)
        if (wait <= 0) {
            lastFrameNanos = now
            super.requestRender()
            return
        }
        if (!pending) {
            pending = true
            handler.postDelayed(trailing, wait / NANOS_PER_MILLI + 1)
        }
    }

    /** `super` is not reachable from inside the trailing lambda; this is. */
    private fun renderNow() = super.requestRender()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
