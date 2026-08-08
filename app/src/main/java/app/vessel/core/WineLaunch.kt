package app.vessel.core

import java.io.File

/**
 * The system dynamic linker, and the only path this app ever hands to `execve`.
 *
 * **An app at `targetSdk` 36 cannot exec a binary in its own `filesDir`.** SELinux
 * grants `untrusted_app` execute on `app_data_file` for *mapping*, which is why
 * `dlopen` from `filesDir` works, but not `execute_no_trans` — so a plain
 * `ProcessBuilder("<filesDir>/…/bin/wine")` dies with EACCES before Wine's `main`
 * is reached. The linker lives in a system exec context that policy does permit,
 * and it takes the program to run as its first argument:
 *
 * ```
 * /system/bin/linker64  <filesDir>/…/bin/wine  --version
 * ```
 *
 * Verified on the target device: `wine --version` answers `wine-10.13` from
 * inside the app sandbox this way and in no other way. Winlator execs its tree
 * directly only because it declares `targetSdkVersion 28`, below the threshold —
 * see `docs/ARCHITECTURE.md`, "Running downloaded native code on Android".
 *
 * The linker shifts `argv`, so the program still sees its own path in `argv[0]`.
 * That is what keeps [WineTree]'s `bin/wineboot -> wine` symlinks working.
 */
const val SYSTEM_LINKER: String = "/system/bin/linker64"

/** The Unix-side module directory in a Wine tree built for this device. */
const val WINE_UNIX_ARCH: String = "aarch64-unix"

/**
 * The PE module directory — Wine's own Windows-side programs and DLLs.
 *
 * `aarch64-windows` even though this tree is built `arm64ec,aarch64,i386`:
 * ARM64EC modules are ARM64 PE images and Wine puts them here, so there is no
 * `arm64ec-windows` directory to look in. Confirmed against the package —
 * `lib/wine/` holds exactly `aarch64-windows`, `i386-windows` and
 * `aarch64-unix`.
 */
const val WINE_PE_ARCH: String = "aarch64-windows"

/**
 * An installed Wine package, as paths.
 *
 * The root is a shared-store directory — `files/components/Wine/<versionCode>/`
 * — resolved through `ComponentStore.directoryFor`, never assembled here. One
 * tree is shared by every container on that build; a container owns the prefix,
 * not the 912 MB of Wine.
 */
data class WineTree(val root: File) {

    val bin: File get() = File(root, "bin")

    val lib: File get() = File(root, "lib")

    /**
     * `lib/wine` — the value of `WINEDLLPATH`, and the single most important
     * variable in this file.
     *
     * `bin/wine` is built from `tools/wine/wine.c`, which locates `ntdll.so` as
     * `<libdir>/wine/<arch>-unix/ntdll.so` where `libdir` is derived from
     * `/proc/self/exe`. Under the [SYSTEM_LINKER] trick `/proc/self/exe` is the
     * *linker*, so Wine looks inside the runtime APEX and fails:
     *
     * ```
     * wine: could not load ntdll.so: dlopen failed: library
     *   "/apex/com.android.runtime/bin/../lib/wine/aarch64-unix/ntdll.so" not found
     * ```
     *
     * `wine.c` falls back to `WINEDLLPATH`, and pointing it here fixes it
     * completely — verified on device. The two facts are inseparable: the exec
     * model that makes Wine start is the same thing that makes it unable to find
     * itself.
     */
    val dllPath: File get() = File(lib, "wine")

    /** `lib/wine/aarch64-unix` — `ntdll.so`, `win32u.so`, `winex11.so`. */
    val unixLib: File get() = File(dllPath, WINE_UNIX_ARCH)

    /** `lib/wine/aarch64-windows` — `explorer.exe`, `winefile.exe`, the PE DLLs. */
    val peLib: File get() = File(dllPath, WINE_PE_ARCH)

    /**
     * Whether Wine ships this builtin program.
     *
     * [hasTool] cannot answer for these: `bin/` carries a symlink for `winefile`
     * but not for `explorer`, so its absence there says nothing about whether the
     * program exists. This looks where the PE actually lives.
     */
    fun hasProgram(name: String): Boolean = File(peLib, name).isFile

    /**
     * `share/wine/nls` — the value of `WINENLSDIR`, and [dllPath]'s twin.
     *
     * `wineserver` finds its case-mapping table by `realpath("/proc/self/exe")`,
     * which under the [SYSTEM_LINKER] trick is the linker, and its three
     * fallbacks are absolute paths under `/usr` on a read-only partition. So it
     * fails with
     *
     * ```
     * wineserver: failed to load l_intl.nls
     * ```
     *
     * while the file is in the package. That is a `fatal_error` upstream;
     * `patches/wine/0004` adds this variable ahead of the built-in search.
     *
     * `ntdll` needs no equivalent — it resolves its own NLS through `dladdr` on
     * `ntdll.so`, which stays correct under the linker.
     */
    val nlsPath: File get() = File(File(root, "share"), "wine/nls")

    /** `bin/wine`. Every tool below is a symlink to it; `bin/wineserver` is not. */
    val loader: File get() = File(bin, LOADER)

    val server: File get() = File(bin, SERVER)

    fun tool(name: String): File = File(bin, name)

    /**
     * Whether `bin/<name>` is there to be exec'd.
     *
     * `exists()` follows symlinks, which is the question being asked: a dangling
     * `bin/wineboot` is not something to hand the linker.
     */
    fun hasTool(name: String): Boolean = tool(name).exists()

    /** True when this looks like an unpacked Wine package rather than any directory. */
    val isUsable: Boolean get() = loader.exists() && File(unixLib, NTDLL).isFile

    companion object {
        const val LOADER: String = "wine"
        const val SERVER: String = "wineserver"
        const val NTDLL: String = "ntdll.so"
    }
}

/** `linker64 <binary> <arguments…>` — the one shape of command this app runs. */
fun linkerArgv(binary: File, arguments: List<String> = emptyList()): List<String> =
    listOf(SYSTEM_LINKER, binary.absolutePath) + arguments

/**
 * The command line for one Wine tool.
 *
 * Two forms, because a Wine tree carries two kinds of tool. `wineboot`, `regedit`
 * and the rest are **symlinks to `bin/wine`** and Wine dispatches on `argv[0]`,
 * so those are exec'd by their own path. `explorer` has no entry in `bin/` at all
 * — it is a PE program in `lib/wine/aarch64-windows` — so it is passed to the
 * loader as an argument instead.
 *
 * [hasBinary] is a parameter rather than an inlined disk check so the two forms
 * can be asserted without a Wine install.
 */
fun WineTree.toolArgv(
    name: String,
    arguments: List<String> = emptyList(),
    hasBinary: Boolean = hasTool(name),
): List<String> =
    if (hasBinary) linkerArgv(tool(name), arguments) else linkerArgv(loader, listOf(name) + arguments)

/** `wineserver`, which is a real binary and never a symlink. */
fun WineTree.serverArgv(arguments: List<String>): List<String> = linkerArgv(server, arguments)

/** The two writable directories a session needs that are not the prefix. */
data class SessionScratch(
    /** `HOME` — `files/containers/<id>/`. */
    val home: File,
    /** `TMPDIR` and `XDG_RUNTIME_DIR` — `files/containers/<id>/tmp/`. */
    val tmp: File,
)

/**
 * The variables that let a Wine tree *start*, as opposed to the ones in
 * [sessionEnvironment] that decide what it *reports*.
 *
 * Kept apart from that function deliberately. `docs/LOGGING.md` is a contract
 * about diagnostics and graphics and is asserted key-for-key in its own test;
 * this is the exec model, and it changes when Android does rather than when the
 * logging document does.
 *
 * Every path here is verified on the target device — see [WineTree.dllPath] for
 * the one that is not obvious. `XDG_RUNTIME_DIR` and `TMPDIR` must both be inside
 * app data: run as the adb `shell` user against `/data/local/tmp` instead and
 * `wineserver` dies with `bind: Permission denied`, because SELinux denies that
 * domain the `sock_file` create. From the app's own uid it binds.
 */
fun wineLauncherEnvironment(tree: WineTree, scratch: SessionScratch): Map<String, String> =
    linkedMapOf(
        "WINEDLLPATH" to tree.dllPath.absolutePath,
        "WINENLSDIR" to tree.nlsPath.absolutePath,
        // `tree.lib` is not decoration next to `unixLib`: winex11.so has libX11
        // and libXext as NEEDED entries, and the package ships those (plus
        // FreeType, which win32u dlopens) at the top of `lib/`. Drop this entry
        // and Wine loses its display driver and all TrueType text at once.
        "LD_LIBRARY_PATH" to listOf(tree.lib, tree.unixLib).joinToString(":") { it.absolutePath },
        // `/system/bin` stays on PATH: Wine shells out to it, and dropping it
        // would break the linker lookup this whole scheme rests on.
        "PATH" to listOf(tree.bin.absolutePath, SYSTEM_BIN).joinToString(":"),
        "HOME" to scratch.home.absolutePath,
        "TMPDIR" to scratch.tmp.absolutePath,
        "XDG_RUNTIME_DIR" to scratch.tmp.absolutePath,
    )

private const val SYSTEM_BIN = "/system/bin"

// — the display, which is `display.resolution` and nothing else ----------------

/**
 * The Windows desktop's size in pixels.
 *
 * `docs/ARCHITECTURE.md`: on this phone resolution is the single biggest
 * performance dial there is, which is why it is the first param in the manifest
 * and why it reaches both the X server and Wine's own `/desktop=` argument.
 */
data class DisplayGeometry(val width: Int, val height: Int) {
    /** The form `explorer /desktop=` and an X server both want. */
    override fun toString(): String = "${width}x$height"
}

/**
 * `display.resolution`'s old option meaning "whatever this screen is".
 *
 * No longer offered — the manifest now lists the panel's actual 2780x1264 as the
 * top rung instead, because a slider needs a real number at each stop. Still
 * understood here, and deliberately so: a container saved before that change has
 * the string `native` in its document, and dropping the case would resolve it to
 * a fallback size with no indication why the desktop got smaller.
 */
const val NATIVE_RESOLUTION: String = "native"

/** The manifest default, repeated here for the case where there is no manifest. */
val DEFAULT_GEOMETRY: DisplayGeometry = DisplayGeometry(1280, 720)

/**
 * `display.resolution` resolved against this device.
 *
 * [native] is the phone's own panel size, which only the UI layer can measure —
 * null when it could not be, in which case `native` falls back to the manifest
 * default rather than to zero. A geometry of `0x0` reaches the X server as a
 * window with no pixels in it, and nothing downstream says why.
 */
fun parseGeometry(value: String?, native: DisplayGeometry? = null): DisplayGeometry {
    if (value == null || value == NATIVE_RESOLUTION) return native ?: DEFAULT_GEOMETRY
    val parts = value.split('x', limit = 2)
    if (parts.size != 2) return native ?: DEFAULT_GEOMETRY
    val width = parts[0].trim().toIntOrNull() ?: return DEFAULT_GEOMETRY
    val height = parts[1].trim().toIntOrNull() ?: return DEFAULT_GEOMETRY
    if (width <= 0 || height <= 0) return DEFAULT_GEOMETRY
    return DisplayGeometry(width, height)
}

/**
 * `display.fpsLimit`'s old option meaning "do not pace frames at all".
 *
 * Also no longer offered. The manifest now lists only the six rates the panel
 * reports (24/30/60/90/120/165), because a cap the display cannot present is a
 * cap that does nothing, and 165 already *is* the ceiling. Kept for the same
 * reason as [NATIVE_RESOLUTION]: containers saved earlier still say `unlimited`.
 */
const val UNLIMITED_FPS: String = "unlimited"

/**
 * `display.fpsLimit` as a number, or null for unlimited.
 *
 * It becomes no environment variable. The manifest declares no `env` for it on
 * purpose — see `manifestEnvironment` — so it is carried to the frame-pacing side
 * of the display seam instead. Inventing `DXVK_FRAME_RATE` here because the name
 * exists would set a cap the user never chose on the one layer that is not doing
 * the compositing.
 */
fun parseFpsLimit(value: String?): Int? {
    if (value == null || value == UNLIMITED_FPS) return null
    return value.trim().toIntOrNull()?.takeIf { it > 0 }
}

/** The manifest keys this file consumes. Named once so a rename is one edit. */
object DisplayParams {
    const val RESOLUTION: String = "display.resolution"
    const val FPS_LIMIT: String = "display.fpsLimit"

    /**
     * Whether [WINE_FILE_MANAGER] starts with the desktop.
     *
     * A setting rather than unconditional because the desktop is also what a
     * fullscreen game runs on, and a file manager under it is a window the game
     * has to be told about. The rail button ignores it: pressing a button is a
     * request, not a preference.
     */
    const val FILE_MANAGER: String = "display.fileManager"
}

/**
 * Wine's virtual-desktop name.
 *
 * `explorer /desktop=<name>,<w>x<h>` gives the session one top-level X window at
 * a fixed size, which is what makes `display.resolution` mean anything: without
 * it every Windows program gets its own X window sized by itself, and there is
 * no desktop for the launcher to show.
 */
const val WINE_DESKTOP: String = "vessel"

/** `explorer` — the virtual desktop, and the process whose exit ends the session. */
const val WINE_EXPLORER: String = "explorer"

/** `wineboot`, a symlink to `bin/wine`. */
const val WINE_BOOT: String = "wineboot"

/** `regedit`, likewise a symlink. */
const val WINE_REGEDIT: String = "regedit"

/**
 * Wine's own file manager, and the only thing on the desktop by default.
 *
 * `explorer /desktop=` draws a background and nothing else — no icons, no
 * taskbar, no Start menu, because Wine's explorer is not a shell. A session that
 * starts nothing into it is a coloured rectangle with no way to reach a program.
 *
 * The `.exe` is part of the name on purpose. It is what `wine` is handed, which
 * makes it what lands in `ImagePathName`, which is the key
 * [PrefixRegistry.fileManagerDesktop] is filed under — `get_default_desktop`
 * matches on the image's base name, so `wine winefile` and
 * `wine winefile.exe` are not interchangeable there.
 */
const val WINE_FILE_MANAGER: String = "winefile.exe"

/**
 * The directory the file manager opens on: the container's own Windows drive.
 *
 * Passed explicitly, and it has to be. `show_frame` (`programs/winefile/`) falls
 * back to `GetCurrentDirectoryW` when it gets no argument, and the session's
 * working directory is `files/containers/<id>/` — a Unix path, which Wine maps
 * onto `Z:`. So the default view is the *Android* filesystem seen through the
 * root drive, which is a real place and entirely the wrong one: the container's
 * programs, its `system32`, and everything a user installs are on `C:`.
 *
 * `_wsplitpath` gets no filename component out of a bare drive root, so winefile
 * treats it as a directory to open rather than a file to select.
 */
const val WINE_FILE_MANAGER_ROOT: String = """C:\"""

/**
 * The desktop process: `wine explorer /desktop=vessel,1280x720 [program]`.
 *
 * [program] is started *by* explorer, after the desktop window exists and on the
 * same Wine desktop — `manage_desktop` creates the window, then `CreateProcessW`s
 * the rest of its command line with `lpDesktop` unset, so the child inherits the
 * desktop from the thread that spawned it. Starting the same program as a
 * separate top-level process instead would race the desktop window into
 * existence and lose that inheritance; see [PrefixRegistry.fileManagerDesktop]
 * for what it costs to do it the other way.
 *
 * It stays one process either way, which is what keeps DESIGN.md's rule intact:
 * the session ends when *this* exits.
 */
fun WineTree.desktopArgv(
    geometry: DisplayGeometry,
    program: List<String> = emptyList(),
    hasBinary: Boolean = hasTool(WINE_EXPLORER),
): List<String> = toolArgv(
    name = WINE_EXPLORER,
    arguments = listOf("/desktop=$WINE_DESKTOP,$geometry") + program,
    hasBinary = hasBinary,
)

/** [WINE_FILE_MANAGER] and the directory it opens on, as explorer's trailing command line. */
val FILE_MANAGER_COMMAND: List<String> = listOf(WINE_FILE_MANAGER, WINE_FILE_MANAGER_ROOT)

/**
 * `wine winefile.exe`, for relaunching it into a desktop that already exists.
 *
 * Routed through the loader rather than through `bin/winefile`, which is there
 * and would work: the symlink form leaves `ImagePathName` derived from the
 * symlink rather than from the resolved builtin, and the AppDefaults lookup that
 * puts this window on the `vessel` desktop keys on that name. Naming the program
 * explicitly is one argument and removes the question.
 */
fun WineTree.fileManagerArgv(): List<String> =
    toolArgv(name = WINE_FILE_MANAGER, arguments = listOf(WINE_FILE_MANAGER_ROOT), hasBinary = false)
