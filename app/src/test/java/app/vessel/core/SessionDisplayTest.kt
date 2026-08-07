package app.vessel.core

import com.winlator.xconnector.UnixSocketConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The display seam's pure half.
 *
 * Everything that actually binds a socket needs a device — `XServerDisplay`
 * loads `libwinlator` and a `GLSurfaceView`. What is checkable here is the part
 * that decides *which* socket, and it is the part that fails silently: a name
 * one byte different from the one libxcb derives is `cannot open display`, and a
 * path one byte over `sun_path` is MIT-SHM quietly never engaging.
 */
class SessionDisplayTest {

    // — the X socket name --------------------------------------------------------

    /**
     * The single string the guest and the server have to agree on.
     *
     * `_xcb_open()` builds `/tmp/.X11-unix/X` + the display number with no
     * environment variable that can move it, so this is not a convention the app
     * chose — it is the one libxcb compiled in. The vendored constant is the
     * server's side of the same fact.
     */
    @Test
    fun `the X socket name is the one libxcb derives from DISPLAY`() {
        assertEquals(UnixSocketConfig.XSERVER_PATH, xSocketName(0))
        assertEquals("/tmp/.X11-unix/X0", xSocketName(0))
        assertEquals("/tmp/.X11-unix/X3", xSocketName(3))
    }

    @Test
    fun `the default display resolves to socket zero`() {
        assertEquals(0, displayNumber(DEFAULT_DISPLAY))
        assertEquals(UnixSocketConfig.XSERVER_PATH, xSocketName(displayNumber(DEFAULT_DISPLAY)))
    }

    @Test
    fun `a screen suffix and a host prefix name the same display`() {
        assertEquals(0, displayNumber(":0.0"))
        assertEquals(2, displayNumber(":2.1"))
        assertEquals(1, displayNumber("localhost:1"))
    }

    /**
     * Nonsense falls back to 0 rather than failing.
     *
     * The alternative is a session that will not start over a malformed string
     * this app writes itself, which is a worse outcome than connecting to the
     * display it was always going to be.
     */
    @Test
    fun `an unparseable display is display zero`() {
        assertEquals(0, displayNumber(""))
        assertEquals(0, displayNumber("nonsense"))
        assertEquals(0, displayNumber(":-4"))
    }

    // — the abstract namespace ----------------------------------------------------

    /**
     * The abstract config touches no disk, which is the whole reason it exists.
     *
     * `UnixSocketConfig.create` wipes and recreates the socket's parent
     * directory. There is no directory to wipe for `/tmp/.X11-unix` on Android —
     * that path is not writable and never will be — so the abstract factory has
     * to be inert on the filesystem, not merely tolerant of a missing root.
     */
    @Test
    fun `an abstract socket config creates nothing on disk`() {
        val config = UnixSocketConfig.createAbstract(xSocketName(0))

        assertEquals("@/tmp/.X11-unix/X0", config.path)
        assertTrue("the '@' prefix is what the native bind reads", config.path.startsWith("@"))
        assertEquals(UnixSocketConfig.XSERVER_PATH, config.path.substring(1))
        assertTrue("nothing may be created under /tmp", !File("/tmp/.X11-unix").exists())
    }

    // — the shared-memory socket ---------------------------------------------------

    /**
     * `WINE_SYSVSHM_SOCKET` has to fit in `sockaddr_un.sun_path`.
     *
     * 108 bytes, and `patches/wine/0005` refuses anything longer rather than
     * truncating — correctly, but the result is MIT-SHM silently off. A container
     * id is a UUID and the app's data directory is fixed, so the longest path
     * this can ever produce is known and is asserted here rather than discovered
     * on a phone.
     */
    @Test
    fun `the sysvshm socket path fits sun_path for a real container`() {
        val base = "/data/user/0/app.vessel/files/containers/" +
            "0123abcd-4567-89ef-0123-456789abcdef"
        val path = base + UnixSocketConfig.SYSVSHM_SERVER_PATH

        assertEquals(
            "/data/user/0/app.vessel/files/containers/" +
                "0123abcd-4567-89ef-0123-456789abcdef/tmp/.sysvshm/SM0",
            path,
        )
        assertTrue("$path is ${path.length} bytes, sun_path holds 107", path.length < 108)
    }

    @Test
    fun `the variable patch 0005 reads is spelled the way the patch spells it`() {
        assertEquals("WINE_SYSVSHM_SOCKET", SYSVSHM_SOCKET_ENV)
    }

    /**
     * The display server owns both variables outright.
     *
     * `DISPLAY` was already reserved. `WINE_SYSVSHM_SOCKET` is the one that
     * matters more: a manifest param could otherwise name a socket nothing is
     * listening on, and winex11 would answer with a failed connect per damaged
     * region instead of an error anyone could read.
     */
    @Test
    fun `a manifest param cannot set either display variable`() {
        assertTrue("DISPLAY" in RESERVED_SESSION_ENV)
        assertTrue(SYSVSHM_SOCKET_ENV in RESERVED_SESSION_ENV)
    }

    // — the Absent implementation ---------------------------------------------------

    @Test
    fun `Absent starts nothing, says why, and offers no surface`() = runBlocking {
        val request = DisplayRequest(
            display = DEFAULT_DISPLAY,
            geometry = DEFAULT_GEOMETRY,
            fpsLimit = null,
            socketRoot = File("/nowhere"),
        )

        val outcome = SessionDisplayServer.Absent.start(request)

        assertTrue(outcome is DisplayOutcome.NotAvailable)
        assertEquals(
            SessionDisplayServer.Absent.REASON,
            (outcome as DisplayOutcome.NotAvailable).reason,
        )
        assertNull(SessionDisplayServer.Absent.surface.value)
        SessionDisplayServer.Absent.stop()
    }
}
