package app.vessel.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.vessel.core.DisplayOutcome
import app.vessel.core.DisplayRequest
import app.vessel.core.SYSVSHM_SOCKET_ENV
import app.vessel.core.SessionDisplayServer
import app.vessel.core.displayNumber
import app.vessel.core.xSocketName
import com.winlator.renderer.ViewTransformation
import com.winlator.sysvshm.SysVSHMConnectionHandler
import com.winlator.sysvshm.SysVSHMRequestHandler
import com.winlator.sysvshm.SysVSharedMemory
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xserver.Pointer
import com.winlator.xserver.SHMSegmentManager
import com.winlator.xserver.ScreenInfo
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

    /** Serialises start against stop, so a fast Retry cannot bind X0 twice. */
    private val lifecycle = Mutex()

    private var session: DisplaySession? = null

    override suspend fun start(request: DisplayRequest): DisplayOutcome = lifecycle.withLock {
        withContext(Dispatchers.Main) {
            teardown()
            try {
                val started = DisplaySession(context, request)
                session = started
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
    }
}

/**
 * The compositor plus input, as one focusable view.
 *
 * Winlator's own display activity puts a stack of overlays over `XServerView` —
 * a touchpad widget, an on-screen gamepad, a magnifier — none of which is
 * vendored. This is the minimum that makes a desktop usable: absolute touch,
 * real mouse buttons and wheel, and hardware keys.
 */
private class SessionSurfaceView(
    context: Context,
    private val xServer: XServer,
    fpsLimit: Int?,
) : FrameLayout(context) {

    private val xServerView = PacedXServerView(context, xServer, fpsLimit)

    val renderer get() = xServerView.renderer

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

    /** Release the GL thread. Separate from detach: the view outlives composition. */
    fun shutdown() = xServerView.onPause()

    // — pointer -----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(event)

        // A finger is an absolute pointing device, so the cursor goes where the
        // finger is rather than being dragged relative to it. Press on DOWN
        // rather than on a tap gesture: it costs the ability to move the cursor
        // without clicking, and buys drag, which is what resizes a window and
        // works a scrollbar.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveTo(event)
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            }

            MotionEvent.ACTION_MOVE -> moveTo(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moveTo(event)
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }

            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
            moveTo(event)
            true
        }

        MotionEvent.ACTION_SCROLL -> {
            scroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
            true
        }

        MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouse(event)

        else -> super.onGenericMotionEvent(event)
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
        moveTo(event)
        val state = event.buttonState
        button(Pointer.Button.BUTTON_LEFT, state and MotionEvent.BUTTON_PRIMARY != 0)
        button(Pointer.Button.BUTTON_RIGHT, state and MotionEvent.BUTTON_SECONDARY != 0)
        button(Pointer.Button.BUTTON_MIDDLE, state and MotionEvent.BUTTON_TERTIARY != 0)
        return true
    }

    private fun button(button: Pointer.Button, pressed: Boolean) {
        if (pressed) xServer.injectPointerButtonPress(button) else xServer.injectPointerButtonRelease(button)
    }

    /** X11 has no wheel axis: a scroll is a press and release of button 4 or 5. */
    private fun scroll(amount: Float) {
        if (abs(amount) < SCROLL_DEADZONE) return
        val button =
            if (amount > 0) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
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
    private fun moveTo(event: MotionEvent) {
        val transformation = renderer.viewTransformation
        val scale = transformation.aspect
        val screen = xServer.screenInfo
        val x: Int
        val y: Int
        if (scale > 0f) {
            x = ((event.x - transformation.viewOffsetX) / scale).toInt()
            y = ((event.y - transformation.viewOffsetY) / scale).toInt()
        } else {
            // Before the first onSurfaceChanged there is no transformation yet.
            // Stretching to the view is wrong, but it is wrong for one frame and
            // the alternative is dropping the event that started the gesture.
            x = (event.x * screen.width / width.coerceAtLeast(1)).toInt()
            y = (event.y * screen.height / height.coerceAtLeast(1)).toInt()
        }
        xServer.injectPointerMove(
            x.coerceIn(0, screen.width - 1),
            y.coerceIn(0, screen.height - 1),
        )
    }

    // — keyboard ------------------------------------------------------------------

    /**
     * Hardware keys straight into the vendored mapping.
     *
     * Back is deliberately excluded. `Keyboard.onKeyEvent` does not map it, but
     * saying so here is what guarantees the Session screen's rail — the only way
     * to reach Stop once a desktop is on screen — cannot be swallowed by a guest
     * that has grabbed the keyboard.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return xServer.keyboard.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private companion object {
        /** Below this a wheel event is a stray sub-detent report, not a scroll. */
        const val SCROLL_DEADZONE = 0.1f
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
