package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exec model, asserted.
 *
 * Every fact here was established on the target device and every one of them
 * fails silently or unhelpfully if it regresses: a direct exec dies with EACCES
 * before Wine's `main`, and a missing `WINEDLLPATH` dies looking for `ntdll.so`
 * inside the Android runtime APEX. Neither is a mistake anyone makes twice, and
 * neither is visible from a unit test unless the exact strings are pinned.
 */
class WineLaunchTest {

    private val tree = WineTree(File("/data/user/0/app.vessel/files/components/Wine/111400"))
    private val scratch = SessionScratch(
        home = File("/data/user/0/app.vessel/files/containers/c1"),
        tmp = File("/data/user/0/app.vessel/files/containers/c1/tmp"),
    )

    // — the linker trick -------------------------------------------------------

    @Test
    fun `every command goes through the system linker, never the binary directly`() {
        val argv = linkerArgv(tree.loader, listOf("--version"))
        assertEquals("/system/bin/linker64", argv.first())
        assertEquals(tree.loader.absolutePath, argv[1])
        assertEquals(listOf("--version"), argv.drop(2))
    }

    @Test
    fun `a tool with a bin symlink is exec'd by its own path, so argv0 dispatch works`() {
        // bin/wineboot is a symlink to bin/wine and Wine reads argv[0] to decide
        // what it is. The linker shifts argv, so the program still sees this path.
        val argv = tree.toolArgv(WINE_BOOT, listOf("--init"), hasBinary = true)
        assertEquals(
            listOf(SYSTEM_LINKER, tree.tool("wineboot").absolutePath, "--init"),
            argv,
        )
    }

    @Test
    fun `a tool with no bin entry is passed to the loader as an argument`() {
        // `explorer` is a PE program in lib/wine/aarch64-windows with nothing in
        // bin/, so it can only be reached as `wine explorer …`.
        val argv = tree.toolArgv(WINE_EXPLORER, listOf("/desktop=vessel,1280x720"), hasBinary = false)
        assertEquals(
            listOf(SYSTEM_LINKER, tree.loader.absolutePath, "explorer", "/desktop=vessel,1280x720"),
            argv,
        )
    }

    @Test
    fun `the desktop command names the virtual desktop and its size`() {
        val argv = tree.desktopArgv(DisplayGeometry(1600, 900), hasBinary = false)
        assertEquals("/desktop=vessel,1600x900", argv.last())
    }

    // — what starts on the desktop ------------------------------------------------

    @Test
    fun `a program on the desktop command line follows the desktop option`() {
        // manage_desktop splits its argument at the first whitespace and
        // CreateProcessWs the remainder, so the order is the whole mechanism:
        // reversed, explorer parses "winefile.exe" as the desktop name.
        val argv = tree.desktopArgv(
            DisplayGeometry(1280, 720),
            program = FILE_MANAGER_COMMAND,
            hasBinary = false,
        )
        assertEquals(
            listOf(
                SYSTEM_LINKER,
                tree.loader.absolutePath,
                "explorer",
                "/desktop=vessel,1280x720",
                "winefile.exe",
                """C:\""",
            ),
            argv,
        )
    }

    @Test
    fun `no program leaves the desktop command exactly as it was`() {
        assertEquals(
            tree.desktopArgv(DisplayGeometry(1280, 720), hasBinary = false),
            tree.desktopArgv(DisplayGeometry(1280, 720), program = emptyList(), hasBinary = false),
        )
    }

    @Test
    fun `the file manager is relaunched by name through the loader`() {
        // Not through bin/winefile, which exists as a symlink to bin/wine and
        // would work: the AppDefaults key that puts this window on the vessel
        // desktop is filed under the image's base name, and naming the program
        // explicitly is what makes that name predictable.
        assertEquals(
            listOf(SYSTEM_LINKER, tree.loader.absolutePath, "winefile.exe", """C:\"""),
            tree.fileManagerArgv(),
        )
    }

    @Test
    fun `the file manager opens on the container's C drive, not on the Android tree`() {
        // With no argument winefile falls back to GetCurrentDirectoryW, and the
        // session's working directory is a Unix path that Wine maps onto Z: — so
        // the default view is the phone's filesystem rather than the container's
        // Windows drive, which is the one place the user's programs are.
        assertEquals("""C:\""", WINE_FILE_MANAGER_ROOT)
        assertEquals(WINE_FILE_MANAGER_ROOT, tree.fileManagerArgv().last())
        assertEquals(WINE_FILE_MANAGER_ROOT, FILE_MANAGER_COMMAND.last())
        assertTrue(FILE_MANAGER_COMMAND.none { it.startsWith("Z:") || it.startsWith("/") })
    }

    @Test
    fun `the file manager's name carries the extension the registry key is filed under`() {
        assertTrue(WINE_FILE_MANAGER.endsWith(".exe"))
        assertTrue(PrefixRegistry.fileManagerDesktop.path.endsWith("""\$WINE_FILE_MANAGER\Explorer"""))
    }

    @Test
    fun `builtin programs are looked for in the PE directory, not in bin`() {
        // `explorer` has no bin/ entry at all, so hasTool says nothing about
        // whether Wine ships it. lib/wine/aarch64-windows is where both it and
        // winefile.exe actually live — confirmed against the package, which holds
        // aarch64-windows, i386-windows and aarch64-unix and no arm64ec-windows.
        assertEquals(
            File(tree.root, "lib/wine/aarch64-windows").absolutePath,
            tree.peLib.absolutePath,
        )
    }

    @Test
    fun `wineserver is a real binary and is never routed through the loader`() {
        val argv = tree.serverArgv(listOf("-k"))
        assertEquals(listOf(SYSTEM_LINKER, tree.server.absolutePath, "-k"), argv)
    }

    // — the environment --------------------------------------------------------

    @Test
    fun `WINEDLLPATH is lib slash wine, which is the whole reason Wine starts at all`() {
        val environment = wineLauncherEnvironment(tree, scratch)
        assertEquals(
            File(tree.root, "lib/wine").absolutePath,
            environment["WINEDLLPATH"],
        )
    }

    @Test
    fun `LD_LIBRARY_PATH carries the tree's lib and its unix module directory`() {
        val environment = wineLauncherEnvironment(tree, scratch)
        assertEquals(
            listOf(
                File(tree.root, "lib").absolutePath,
                File(tree.root, "lib/wine/aarch64-unix").absolutePath,
            ).joinToString(":"),
            environment["LD_LIBRARY_PATH"],
        )
    }

    @Test
    fun `the runtime and temp directories are inside app data`() {
        // Outside it — /data/local/tmp, as an adb shell test would use — SELinux
        // denies the sock_file create and wineserver dies on bind.
        val environment = wineLauncherEnvironment(tree, scratch)
        assertEquals(scratch.tmp.absolutePath, environment["TMPDIR"])
        assertEquals(scratch.tmp.absolutePath, environment["XDG_RUNTIME_DIR"])
        assertEquals(scratch.home.absolutePath, environment["HOME"])
        assertTrue(environment.values.none { it.contains("/data/local/tmp") })
    }

    @Test
    fun `PATH keeps system bin, which is where the linker lives`() {
        val path = wineLauncherEnvironment(tree, scratch)["PATH"]!!
        assertTrue(path.startsWith(File(tree.root, "bin").absolutePath))
        assertTrue(path.split(":").contains("/system/bin"))
    }

    @Test
    fun `the launcher sets nothing the logging contract owns`() {
        // The two environments are merged at launch and must not overlap: this
        // one is the exec model, `sessionEnvironment` is docs/LOGGING.md.
        val environment = wineLauncherEnvironment(tree, scratch)
        assertTrue(environment.keys.none { it in RESERVED_SESSION_ENV })
        assertNull(environment["WINEPREFIX"])
    }

    // — display.resolution -----------------------------------------------------

    @Test
    fun `a WxH option parses to those pixels`() {
        assertEquals(DisplayGeometry(1280, 720), parseGeometry("1280x720"))
        assertEquals(DisplayGeometry(1920, 1080), parseGeometry("1920x1080"))
    }

    @Test
    fun `native resolves to the panel size when one was measured`() {
        val panel = DisplayGeometry(2712, 1220)
        assertEquals(panel, parseGeometry(NATIVE_RESOLUTION, panel))
    }

    @Test
    fun `native with no measurement falls back to the default, never to zero`() {
        // A zero-sized desktop reaches the X server as a window with no pixels,
        // and nothing downstream says why.
        assertEquals(DEFAULT_GEOMETRY, parseGeometry(NATIVE_RESOLUTION, null))
        assertEquals(DEFAULT_GEOMETRY, parseGeometry(null, null))
        assertEquals(DEFAULT_GEOMETRY, parseGeometry("nonsense", null))
        assertEquals(DEFAULT_GEOMETRY, parseGeometry("0x0", null))
        assertEquals(DEFAULT_GEOMETRY, parseGeometry("-1x-1", null))
    }

    // — display.fpsLimit -------------------------------------------------------

    @Test
    fun `the frame rate limit is a number, and unlimited is null`() {
        assertEquals(60, parseFpsLimit("60"))
        assertEquals(30, parseFpsLimit("30"))
        assertNull(parseFpsLimit(UNLIMITED_FPS))
        assertNull(parseFpsLimit(null))
        assertNull(parseFpsLimit("0"))
    }

    @Test
    fun `the frame rate limit becomes no environment variable`() {
        // It is carried on DisplayRequest instead. Inventing DXVK_FRAME_RATE here
        // would cap the D3D layer rather than the compositor, and would do
        // nothing at all for an OpenGL title.
        val environment = wineLauncherEnvironment(tree, scratch)
        assertTrue(environment.keys.none { it.contains("FRAME") || it.contains("FPS") })
    }

    // — the manifest keys ------------------------------------------------------

    @Test
    fun `the display param keys are the ones the shipped manifest declares`() {
        assertEquals("display.resolution", DisplayParams.RESOLUTION)
        assertEquals("display.fpsLimit", DisplayParams.FPS_LIMIT)
        assertEquals("display.fileManager", DisplayParams.FILE_MANAGER)
    }
}
