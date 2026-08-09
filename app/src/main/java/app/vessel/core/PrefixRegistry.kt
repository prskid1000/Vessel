package app.vessel.core

import java.io.File

/**
 * The two value types this seed writes.
 *
 * Not a general facility — it is two because two readers demand different
 * things. [SZ] is the default and is load-bearing for the emulator keys:
 * `load_arm64ec_module` and `get_cpu_dll_name` both test
 * `info->Type == REG_SZ` and ignore the value otherwise, so `REG_EXPAND_SZ`
 * would leave the key present, readable, and silently unused. [DWORD] exists
 * because `ShouldAppsUseDarkMode` reads its value with `RRF_RT_REG_DWORD`,
 * which rejects a string outright.
 */
enum class RegistryKind { SZ, DWORD }

/**
 * One registry value. [name] is empty for a key's default value, which a `.reg`
 * file writes as `@`.
 */
data class RegistryValue(
    val name: String,
    val data: String,
    val kind: RegistryKind = RegistryKind.SZ,
) {
    companion object {
        /** The name of a key's default value. */
        const val DEFAULT = ""

        /** A `REG_DWORD`, rendered as `dword:` and eight lowercase hex digits. */
        fun dword(name: String, value: Int): RegistryValue =
            RegistryValue(name, "%08x".format(value), RegistryKind.DWORD)
    }
}

/**
 * One key and the values under it, or a key to delete.
 *
 * [remove] renders `[-path]`, which is `.reg`'s delete form and the only way to
 * take a key out of a hive that `wine.inf` put there. It exists because two of
 * the things this seed has to change are keys Wine creates and Vessel does not
 * want — a value written over them would leave the key present and the shell
 * item with it.
 */
data class RegistryKey(
    val path: String,
    val values: List<RegistryValue> = emptyList(),
    val remove: Boolean = false,
)

/**
 * `r g b`, decimal and space-separated, which is the only form Wine parses.
 *
 * `get_rgb_entry` (`dlls/win32u/sysparams.c`) reads the value with three
 * `wcstoul` calls and bails the moment one of them stops at a NUL, so `#161826`
 * or a comma-separated triplet leaves the colour at its built-in default with
 * nothing logged.
 *
 * Top level rather than private to [PrefixRegistry] because it is the shared
 * spelling of a [GuestPalette] entry, and the two have to agree.
 */
fun rgbTriplet(argb: Int): String =
    "${(argb shr 16) and 0xFF} ${(argb shr 8) and 0xFF} ${argb and 0xFF}"

/**
 * The registry a fresh prefix needs, as data and as a `.reg` file.
 *
 * [app.vessel.data.ContainerProvisioner] renders it to `prefix-seed.reg`;
 * `app.vessel.data.SessionRuntime` runs `regedit` on that file once the prefix
 * has been booted. The split keeps the seed's *content* reviewable and testable
 * without a device, which is why it survived the launcher being written.
 *
 * Two of the four keys are load-bearing rather than advisory: without
 * [arm64ecEmulator] and [x86Emulator] Wine looks for Microsoft's `xtajit64.dll`
 * and `xtajit.dll`, finds neither, and no translated code runs at all.
 */
object PrefixRegistry {

    /** `.reg` files are CRLF and carry this exact first line. */
    const val HEADER: String = "Windows Registry Editor Version 5.00"

    /**
     * Bumped when [seed] changes in a way an already-provisioned container needs
     * re-applied. [app.vessel.data.ContainerProvisioner] stores it, so a seed
     * change re-runs the registry step and nothing else.
     *
     * 2 added a wallpaper key and a file-manager desktop key; 3 added [desktopTheme];
     * 4 added [visualStyles] and [windowsDarkMode]; 5 moved [visualStyles] off
     * `Software\Wine\Themes`, which uxtheme never reads; 6 removed the wallpaper
     * key and folded the desktop's `Background` into [desktopTheme], which is
     * what makes an already-provisioned container stop pointing at a bitmap that
     * no longer gets written; 7 removed the `winefile.exe` AppDefaults key along
     * with the file manager itself — Vessel's own C: browser replaced it, so
     * nothing puts a second Wine program on the desktop any more; 8 added
     * [windowMetrics], so an existing container's windows get captions, borders
     * and scrollbars a finger can hit rather than Windows' mouse-sized ones;
     * 20 moved the applied-stamp into the machine hive, which is the one
     * SessionRuntime reads - it was written to user.reg and looked for in
     * system.reg, so every launch re-ran regedit and two wine.inf passes;
     * 19 put Git's three directories on PATH, so git and the POSIX tools
     * work from cmd the moment the component is installed - clangarm64 and
     * not mingw64, which is what the ARM64 build calls its prefix;
     * 18 named the graphics driver, so Wine stops probing for `winemac.drv` it
     * cannot have and logging the failure twenty-two times a session;
     * 17 took the unix root off the desktop — see [unixNamespace] — which with
     * `DriveMap.removeRootDrive` is what stops `Z:` and `/` handing a guest
     * program this app's own private storage;
     * 16 made the drive list come from `dosdevices` instead of a hardcoded
     * three, so a folder the user maps is declared like the seeded drives are,
     * and made the applied-marker a hash of the rendered text rather than this
     * number — see [stampFor];
     * 15 took `Vessel Tools` back off `PATH`, no component ever having put
     * anything there — a toolchain a user installs brings its own entry;
     * 14 declared the drive types, without which Wine may guess a mapped drive
     * is removable and a shell view then treats it as absent;
     * 13 moved the whole sizing border into BorderWidth, PaddedBorderWidth
     * being sized but seemingly not painted;
     * 12 set the console's own palette, which is where the white rim around a
     * console came from — conhost ignores the system colours entirely;
     * 11 narrowed the caption buttons and darkened the 3D highlight, both of
     * which only became visible once seed 8 enlarged the frame;
     * 9 added [toolsPath], which is what puts the Unix tools on `PATH` in every
     * shell rather than in one profile; 10 added [virtualDesktop], without which
     * a program launched into a running session came up rootless and undecorated
     * — no title bar and no minimise, maximise or close.
     */
    const val SEED_VERSION: Int = 20

    /**
     * A value written into the hive naming the exact seed that wrote it.
     *
     * The prefix's own record of what it carries, and the thing `SessionRuntime`
     * looks for before deciding it can skip `regedit`. It has to live in the hive
     * rather than in `provisioned.json` because that file records what the app
     * *believes* it did — and believing a step happened when it had not is the
     * whole defect this exists to stop.
     *
     * **The version alone is not enough any more, so this is a fingerprint of
     * the rendered text.** Seed 16 made the drive list depend on the prefix: a
     * folder the user maps is a new value in [driveTypes], and two containers on
     * the same seed version now legitimately want different registry text. An
     * integer cannot express that — it would say "seed 16 is applied" for a
     * prefix whose newest drive has never been declared, which is the same class
     * of lie the marker was introduced to stop. Hashing what is about to be
     * written makes the question exact: this hive either carries *these* keys or
     * it does not, whatever the reason.
     */
    fun stampFor(regText: String): String = "VesselSeed$SEED_VERSION-${fingerprint(regText)}"

    /**
     * The stamp a rendered seed will write, read back out of it.
     *
     * How the applier learns what to look for without re-deriving it: the `.reg`
     * file on disk is the thing that will be applied, so the stamp it carries is
     * by definition the right one to expect in the hive afterwards. Null for a
     * file written before stamps existed, which the caller treats as "apply it".
     */
    fun stampOf(regText: String): String? =
        STAMP_PATTERN.find(regText)?.groupValues?.get(1)

    private val STAMP_PATTERN = Regex(""""Seed"="([^"]+)"""")

    /**
     * **`HKEY_LOCAL_MACHINE`, and the machine hive is the whole point.**
     *
     * This was `HKEY_CURRENT_USER`, which `regedit` writes into `user.reg` —
     * while `SessionRuntime.hiveText` reads `system.reg`, because that is where
     * the emulator keys it also checks live. So the stamp was written to one
     * file and looked for in another, the skip could never fire, and **every
     * launch re-ran `regedit` and two full `wine.inf` passes**: minutes on this
     * phone, on a prefix that was already correct. Measured before the fix —
     * three consecutive sessions each execed `wineboot` twice and `regedit`
     * twice, on a container that had been provisioned once.
     *
     * Moving it rather than teaching the reader about a second file: one hive to
     * read is the simpler invariant, and everything else this seed asserts about
     * an applied prefix is already in `system.reg`.
     */
    private fun stampKey(stamp: String) = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Vessel""",
        values = listOf(RegistryValue("Seed", stamp)),
    )

    /**
     * Eight hex characters of SHA-256 — enough, and this is not a security claim.
     *
     * The failure this must avoid is two *different* seeds hashing the same, and
     * the population is one string per container per app version. At 32 bits a
     * collision needs on the order of 2^16 distinct seeds before it is even
     * likely, and the consequence of one would be a skipped `regedit` rather
     * than anything unsafe.
     */
    private fun fingerprint(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }

    /** Only used when a process somehow creates the desktop. See [virtualDesktop]. */
    private const val DEFAULT_DESKTOP_SIZE = "1280x720"

    /** The mode the DLL override values carry. See [D3D_DLL_OVERRIDES]. */
    const val DLL_OVERRIDE_MODE: String = "native,builtin"

    /**
     * **`renderer = vulkan`, and this is the one nobody remembers.**
     *
     * DXVK covers D3D9–11 and vkd3d covers D3D12, which makes it easy to
     * conclude wined3d is out of the picture. It is not: **DirectDraw and D3D1–7
     * go through wined3d**, which defaults to its OpenGL renderer — and our Wine
     * has no GLX at all. Without this value an old title fails with nothing on
     * the way down saying "I tried to use OpenGL"; the only hint is
     * `+winediag`'s renderer-selection line.
     */
    val direct3D: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Wine\Direct3D""",
        values = listOf(RegistryValue("renderer", "vulkan")),
    )

    /**
     * The Direct3D and WGL DLLs, pointed at the native builds.
     *
     * `native,builtin` rather than the session environment's bare `n`: the
     * environment is authoritative when a session sets it, and this is the
     * fallback that keeps a prefix sane without it. Falling back to `builtin` is
     * the right second choice — it is wined3d, which with [direct3D] set at least
     * runs on Vulkan.
     */
    val dllOverrides: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Wine\DllOverrides""",
        values = D3D_DLL_OVERRIDES.map { RegistryValue(it, DLL_OVERRIDE_MODE) },
    )

    /**
     * Where Wine looks for the ARM64EC emulator, i.e. FEX.
     *
     * **Required, not an assertion.** Read by `load_arm64ec_module()`
     * (`dlls/ntdll/loader.c:4275` in Wine 11.14), which starts from the literal
     * `C:\windows\system32\xtajit64.dll` and overwrites only the filename with
     * this value. Absent, Wine looks for Microsoft's `xtajit64.dll`, which we do
     * not ship — and the miss is *fatal*: `load_arm64ec_module` ends in
     * `NtTerminateProcess`, so every x86-64 program dies at load with nothing but
     * `could not load …xtajit64.dll` to go on.
     *
     * An earlier version of this comment claimed the built-in fallback was
     * already `libarm64ecfex.dll`, and that has not been true on any tree this
     * project builds. The value must be written.
     *
     * The data must be a bare filename — the reader substitutes it into a fixed
     * `system32` path — and `REG_SZ`, which `info->Type == REG_SZ` enforces.
     * `loader/wine.inf.in:400` writes the key during `wineboot` with NOCLOBBER
     * (`FLG_ADDREG_NOCLOBBER`), so applying this seed *after* `wineboot`
     * overwrites Wine's default and applying it before is preserved: either
     * ordering lands correctly, which is what lets `SessionRuntime` boot the
     * prefix first and apply the registry second.
     */
    val arm64ecEmulator: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\amd64""",
        values = listOf(RegistryValue(RegistryValue.DEFAULT, "libarm64ecfex.dll")),
    )

    /**
     * Where Wine looks for the 32-bit x86 emulator under WoW64, i.e. FEX again.
     *
     * **Also required.** Read by `get_cpu_dll_name()`
     * (`dlls/wow64/syscall.c:909` in Wine 11.14). Its built-in fallback on an
     * ARM64 host is `xtajit.dll`, *not* `libwow64fex.dll` — the previous comment
     * here had that backwards, and with the key unwritten no 32-bit x86 program
     * can start. `loader/wine.inf.in:402` writes `xtajit.dll` during `wineboot`,
     * which is exactly the value this has to replace.
     *
     * The DLL is loaded by `load_64bit_module`, which resolves it against
     * `get_machine_wow64_dir(IMAGE_FILE_MACHINE_TARGET_HOST)` — `system32`, the
     * *64-bit* directory, not `syswow64`. `libwow64fex.dll` therefore deploys
     * alongside `libarm64ecfex.dll`, which is what `SessionRuntime`'s root-level
     * `.dll` rule already does for the FEX package.
     */
    val x86Emulator: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\x86""",
        values = listOf(RegistryValue(RegistryValue.DEFAULT, "libwow64fex.dll")),
    )

    
    /**
     * The guest's chrome in Nocturne, so a Windows window reads as part of
     * Vessel rather than as stock grey Wine.
     *
     * **Flat system colours are all this can promise, and that is checked rather
     * than hoped.** Wine loads `uxtheme.dll` in every session (it is in the log),
     * but visual styles need a `.msstyles` package and an active theme under
     * `HKCU\Software\Wine\Themes`; this project ships neither, so uxtheme stays in
     * classic mode and classic mode draws from exactly these values. Every name
     * below is an `RGB_ENTRY` in `dlls/win32u/sysparams.c`.
     *
     * `Background` is here rather than derived per session, and that is the whole
     * of what is left of the wallpaper feature. It used to be the fallback colour
     * under a bitmap taken from the phone; on this device every
     * `WallpaperManager` bitmap entry point refuses an app with no storage
     * permission, so the fallback was the only tier that ever ran. The desktop is
     * now simply Nocturne's window ground, chosen rather than settled for. Wine's
     * own `COLOR_BACKGROUND` is `RGB(58, 110, 165)` — the medium blue a bare
     * session shows — so this value is what stops it being that.
     *
     * The mapping is Nocturne's own: `bg` is the ground a window sits on,
     * `surface` is the raised chrome (dialogs, menus, toolbars), elevation is a
     * hairline of `neutral-800` rather than a bevel, and selection is the tag
     * fill — `accent-800` ground, `accent-100` text. What is deliberately *not*
     * mapped is `accent` onto anything large: DESIGN.md's first rule is that
     * colour carries meaning, and a violet title bar is decoration.
     */
    val desktopTheme: RegistryKey = RegistryKey(
        path = COLORS_KEY,
        values = listOf(
            // The desktop itself — what `PaintDesktop` fills on WM_ERASEBKGND.
            color("Background", GuestPalette.BG),

            // Windows and their text.
            color("Window", GuestPalette.BG),
            color("WindowText", GuestPalette.TEXT),
            color("WindowFrame", GuestPalette.NEUTRAL_800),
            // winefile is MDI, so this is the ground its child windows sit on.
            color("AppWorkSpace", GuestPalette.BG),

            // Dialogs, toolbars and buttons. The four "3D" colours are a bevel
            // Nocturne does not have, so they are flattened onto the ramp: light
            // and dark differ just enough to keep a focus ring visible.
            color("ButtonFace", GuestPalette.SURFACE),
            color("ButtonText", GuestPalette.TEXT),
            // **The raised edge, deliberately almost invisible.** These two are
            // the light half of a classic 3D bevel, and seed 8 made them matter:
            // at a 1 px border nobody saw them, at 4+4 px they became a bright
            // rim around every window that read as a white border against a
            // near-black desktop. Nocturne has no bevel, so the light half is
            // one step off the ground rather than a highlight.
            color("ButtonHilight", GuestPalette.NEUTRAL_800),
            color("ButtonLight", GuestPalette.NEUTRAL_900),
            color("ButtonShadow", GuestPalette.NEUTRAL_900),
            color("ButtonDkShadow", GuestPalette.BG),
            color("ButtonAlternateFace", GuestPalette.NEUTRAL_900),

            // Menus.
            color("Menu", GuestPalette.SURFACE),
            color("MenuText", GuestPalette.TEXT),
            color("MenuBar", GuestPalette.SURFACE),
            color("MenuHilight", GuestPalette.ACCENT_800),

            // Selection, which is Nocturne's tag fill.
            color("Hilight", GuestPalette.ACCENT_800),
            color("HilightText", GuestPalette.ACCENT_100),
            color("HotTrackingColor", GuestPalette.ACCENT),

            // Title bars. Wine gradients from the flat colour to the Gradient one,
            // so the pair has to stay close or the bar bands visibly.
            color("ActiveTitle", GuestPalette.NEUTRAL_900),
            color("GradientActiveTitle", GuestPalette.NEUTRAL_800),
            color("TitleText", GuestPalette.TEXT),
            color("InactiveTitle", GuestPalette.BG),
            color("GradientInactiveTitle", GuestPalette.NEUTRAL_900),
            color("InactiveTitleText", GuestPalette.NEUTRAL_500),
            color("ActiveBorder", GuestPalette.NEUTRAL_800),
            color("InactiveBorder", GuestPalette.NEUTRAL_900),

            // The rest.
            color("Scrollbar", GuestPalette.NEUTRAL_900),
            color("GrayText", GuestPalette.NEUTRAL_600),
            color("InfoWindow", GuestPalette.SURFACE),
            color("InfoText", GuestPalette.TEXT),
        ),
    )

    /**
     * Turn Wine's visual styles off, which is what lets [desktopTheme] show at all.
     *
     * **This is not a preference, it is the difference between the theme applying
     * and not.** Wine ships `aero.msstyles` in `lib/wine/aarch64-windows` and
     * `wine.inf` activates it, and a themed control draws from the `.msstyles`
     * package rather than from `Control Panel\Colors`. Measured on device before
     * this value existed: winefile's panes came up Nocturne dark (they are
     * unthemed) while its status bar came up `#F5F5F5`, which is Aero's light
     * grey and no colour anything else in the session had asked for.
     *
     * **The key is `ThemeManager`, not `Software\Wine\Themes`.** The Wine-branded
     * one is the obvious guess and this code shipped it once: the value lands in
     * `user.reg`, reads correctly, and is loaded by nothing. `UXTHEME_LoadTheme`
     * opens exactly `szThemeManager` — `Software\Microsoft\Windows\CurrentVersion
     * \ThemeManager` (`dlls/uxtheme/system.c:44`) — and `wine.inf` seeds
     * `ThemeActive="1"` there. Verified by the status bar staying `#F5F5F5`
     * through a session that had the wrong key written.
     *
     * `bThemeActive = (tmp[0] != '0')` runs at DLL init, so `"0"` disables it for
     * every process in the prefix. The result is Wine's classic drawing over our
     * system colours — flat, square, consistently dark, which is DESIGN.md's
     * "flat and precise over soft and shadowed" rather than a compromise.
     * `ColorName`, `SizeName` and `DllName` are left alone: `bThemeActive` false
     * short-circuits before any of them is used.
     *
     * A dark `.msstyles` would be the richer answer and there is not one to ship;
     * recolouring Aero is not a thing the registry can do.
     */
    val visualStyles: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\ThemeManager""",
        values = listOf(RegistryValue("ThemeActive", "0")),
    )

    /**
     * Title bars, borders and scrollbars sized for a finger.
     *
     * **This is the half of "windows you can actually use" that colour cannot
     * do.** Wine's virtual desktop already gives every top-level window a
     * caption, a sizing border and a full non-client frame — that part is not
     * missing and never was. What is missing is that they are sized for a mouse:
     * Windows' defaults are a 22 px caption, a **1 px** sizing border and a 17 px
     * scrollbar, and a 1 px grab region on a phone is a border nobody can hit.
     * Two pointer modes make it worse, not better: trackpad mode can land on one
     * pixel, direct-touch mode cannot come close.
     *
     * Everything here is in **twips at 96 dpi, negative, fifteen per pixel** —
     * `-15 * pixels` — which is the form `SPI_GETNONCLIENTMETRICS` reads back and
     * the only one `sysparams.c` parses. A positive number is a *point* size and
     * means something else entirely.
     *
     * The sizes, and why each is what it is:
     *
     * - **Caption 40 px.** Twice Windows' 22, and the number that has to carry
     *   both a drag handle and three buttons. Bigger reads as a phone app's
     *   toolbar rather than a window; on a 720 px-tall guest desktop, 40 px is
     *   5.5% of the height per window and three stacked windows still leave room
     *   to work.
     * - **Border 4 px plus padded border 4 px = an 8 px grab region.** The pair
     *   is how Windows separates the drawn frame from the invisible margin
     *   around it, and Wine adds them the same way; widening the padded half is
     *   what buys a resize target without drawing a thick frame.
     * - **Scrollbars 24 px**, from 17. A scrollbar is the one control a program
     *   gives you no alternative to, and the thumb has to be draggable.
     * - **Small caption and menu 32 px.** Tool windows and menu bars, one step
     *   down: they are chrome you aim at rather than drag.
     *
     * **Not scaled through DPI, deliberately.** Raising `LogPixels` would size
     * all of this in one number and scale fonts with it, which sounds better
     * until the desktop is 1280×720 and every logical pixel costs 1.5 real ones:
     * the guest loses a third of its working area to get a bigger title bar. The
     * metrics are set directly so the trade is only paid where it buys something
     * to touch.
     *
     * Fonts are left alone here for the same reason and one more: `CaptionFont`
     * and its four siblings are `LOGFONTW` structs written as binary, not
     * strings, and this seed writes `.reg` text.
     */
    val windowMetrics: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Control Panel\Desktop\WindowMetrics""",
        values = listOf(
            twips("CaptionHeight", CAPTION_PX),
            // Narrower than the caption is tall. `CaptionWidth` sizes the
            // *buttons*, and Wine scales their glyphs to fit — at 40 px square
            // the close cross and the maximise box came out as large white
            // marks that read as three lit panels rather than three buttons.
            // 32 keeps them comfortably thumb-sized with the glyph in
            // proportion.
            twips("CaptionWidth", SMALL_CAPTION_PX),
            twips("SmCaptionHeight", SMALL_CAPTION_PX),
            twips("SmCaptionWidth", SMALL_CAPTION_PX),
            twips("MenuHeight", SMALL_CAPTION_PX),
            twips("MenuWidth", SMALL_CAPTION_PX),
            twips("BorderWidth", BORDER_PX),
            twips("PaddedBorderWidth", PADDED_BORDER_PX),
            twips("ScrollHeight", SCROLLBAR_PX),
            twips("ScrollWidth", SCROLLBAR_PX),
        ),
    )

    /**
     * **Every process joins the virtual desktop, which is what gives a window
     * its title bar.**
     *
     * This is the answer to "where are the minimise, maximise and close
     * buttons", and the tree dump is what found it. A program started into a
     * live session — `wine wineconsole cmd.exe` — is a *new* Wine process, and
     * it does not inherit the desktop the session's `explorer /desktop=` created
     * on its command line. It starts rootless instead, so its window is a direct
     * child of the X root rather than a child of the desktop window, which is
     * exactly what the dump showed:
     *
     * ```
     * root
     *   id=8388615  1280x720  class='explorer.exe'   <- the desktop
     *   id=29360129  656x400  class='conhost.exe'    <- a sibling, not a child
     * ```
     *
     * A rootless X window is decorated by the window manager, and the vendored
     * server is a compositor with no window manager in it. Nothing draws a
     * caption, so there is no title bar, no buttons, no drag and no resize —
     * not because the window lacks them but because nobody is drawing them.
     *
     * These two values are what winecfg's *Emulate a virtual desktop* writes,
     * and they apply to the whole prefix rather than to one command line. Every
     * process then attaches to the named desktop — Wine desktops are named
     * objects, so the second process joins the first one's rather than making
     * another — and Wine's own window manager inside that desktop draws the
     * frame, the caption and the three buttons, and handles dragging and
     * resizing. [windowMetrics] is what makes that frame finger-sized; until
     * this key existed, those metrics were sizing a frame nothing drew.
     *
     * The size here is a fallback and not the truth. Whoever creates the desktop
     * first fixes its size, and in a Vessel session that is always the explorer
     * the runtime starts with the container's real geometry on its command line.
     * This value only decides what a process would get if it somehow arrived
     * first, so it matches the default rather than trying to track the setting.
     */
    val virtualDesktop: List<RegistryKey> = listOf(
        RegistryKey(
            path = """HKEY_CURRENT_USER\Software\Wine\Explorer""",
            values = listOf(RegistryValue("Desktop", WINE_DESKTOP)),
        ),
        RegistryKey(
            path = """HKEY_CURRENT_USER\Software\Wine\Explorer\Desktops""",
            values = listOf(RegistryValue(WINE_DESKTOP, DEFAULT_DESKTOP_SIZE)),
        ),
    )

    /**
     * The console's own colours, which are not the system's.
     *
     * **Every one of Windows' 31 system colours is already dark and the console
     * still came up with a white rim.** That is because `conhost` does not use
     * them: a console window paints from `HKCU\Console`'s own 16-entry palette
     * and a `ScreenColors` byte picking a foreground and background out of it.
     * The default background is entry 0 and the default *border* fill is the
     * light grey at entry 7, which is the white edge around the black text area.
     *
     * `ScreenColors` is `0xF0 >> 4` style: the high nibble is the background
     * index and the low nibble the foreground. `0x07` — grey on black — is the
     * default and is what shipped. `0x08` here is entry 8 as the background,
     * which the table below sets to Nocturne's window ground, with entry 7 as
     * the text.
     *
     * Only the two entries this needs are overridden. The other fourteen are
     * ANSI colours that programs ask for by name and a shell that prints red
     * should get red, not a palette somebody redecorated.
     */
    val consoleColours: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Console""",
        values = listOf(
            RegistryValue.dword("ScreenColors", 0x87),
            RegistryValue.dword("PopupColors", 0x87),
            // Entry 8 is the ground; entry 7 is the text on it.
            RegistryValue.dword("ColorTable08", bgr(GuestPalette.BG)),
            RegistryValue.dword("ColorTable07", bgr(GuestPalette.TEXT)),
        ),
    )

    /**
     * What kind of disk each drive is, which is what makes Wine list it.
     *
     * **A symlink in `dosdevices` is enough for Wine to *resolve* a path and
     * not always enough for it to *show* the drive.** `GetDriveType` falls back
     * to guessing from the target when there is no entry here, and a guess of
     * `DRIVE_UNKNOWN` or `DRIVE_REMOVABLE` is what makes a shell view treat a
     * perfectly good drive as an empty or absent one. This is the key winecfg
     * writes for exactly the same reason, and its absence is the most likely
     * explanation for a drive that our own browser lists and Wine's does not.
     *
     * `hd` for all of them, including the phone's storage: `floppy` and `cdrom`
     * make Wine poll for media that will never change, and `network` makes it
     * treat the drive as slow and unreliable in ways that show up as dialogs.
     * Internal storage is not removable in any sense the guest can act on.
     *
     * **Derived from the prefix, not listed here.** The first version of this
     * named `c:`, `d:` and `z:` and left a note that a folder the user maps
     * later would need the mapper to write its own entry. That was the wrong
     * shape: it puts the same fact in two places and makes every future way of
     * gaining a drive a new place to remember. `dosdevices` is already the only
     * record of what drives exist — [DriveMap.drives] reads it for the UI — so
     * the seed reads the same directory and declares whatever is actually
     * there. A drive gains its type by existing, whoever made it.
     *
     * `c:` is added whether or not it is on disk, because the seed is rendered
     * before `wineboot` has created it on a first provision and it is never
     * absent afterwards. `z:` is *not*, and no longer exists — see
     * [unixNamespace].
     */
    /**
     * The `Drives` key, deleted before it is written.
     *
     * **A `.reg` file merges; it does not replace.** So a drive the user unmaps
     * leaves its `"e:"="hd"` behind for ever, and the key slowly becomes a list
     * of every letter the container has ever had. Harmless on its own — a type
     * without a `dosdevices` link is not a drive — but it is a record that
     * disagrees with reality, and the whole point of deriving this key from
     * `dosdevices` was to have one truth. Deleting first makes each write a
     * replacement. `z:` was the case that made it visible: the symlink went and
     * the value stayed.
     */
    /**
     * The one graphics driver this device has, named so Wine stops guessing.
     *
     * With no value here Wine walks its built-in list and tries each in turn.
     * On this device that means loading `winemac.drv`, which does not exist in
     * an Android build, and logging `Failed to load module L"winemac.drv";
     * status=c0000135` every time — twenty-two lines in one session, all of them
     * red, none of them a problem. Naming the driver removes the probe and the
     * noise together.
     *
     * `winex11.drv` is the only one that can work: Vessel runs an X server in
     * the app and Wine talks to it over a unix socket. It still fails to
     * initialise during provisioning, because `wineboot` and `regedit` run
     * before any X server exists — that failure is expected and is a different
     * line.
     */
    val graphicsDriver: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Wine\Drivers""",
        values = listOf(RegistryValue("Graphics", "x11")),
    )

    private val driveTypesReset = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Wine\Drives""",
        remove = true,
    )

    fun driveTypes(letters: Collection<Char> = DEFAULT_DRIVES): RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Wine\Drives""",
        values = (letters.map { it.lowercaseChar() } + DriveMap.SYSTEM_DRIVE)
            .distinct()
            // Never `z:`, even if a link for it is on disk. The provisioner
            // removes that drive on every launch, and there is a window between
            // `wineboot` recreating it and the removal running — declaring it in
            // that window would write a value for a drive the same pass deletes.
            .filterNot { it == DriveMap.ROOT_DRIVE }
            .sorted()
            .map { RegistryValue("$it:", "hd") },
    )

    /**
     * The two ways Wine puts the whole Android filesystem in front of the user,
     * both removed.
     *
     * **`Z:` and `/` are the same mistake shown twice.** Wine maps the unix root
     * to `Z:` on every prefix, and registers a shell namespace extension so the
     * desktop tree also carries a `/` node. In a desktop Wine that is a
     * convenience; here the unix root is Android, and what it exposes to a guest
     * program is `/data/user/0/app.vessel` — this app's own private storage,
     * writable, one `del /s` away. Vessel's whole storage model is that a drive
     * is a folder the user chose, so a drive nobody chose that reaches
     * everything is the one mapping the product should not ship.
     *
     * It was already hidden from Vessel's own browser as unreadable. That was
     * treating the symptom: Wine's File Explorer listed it, and so would every
     * Open dialog in every guest program.
     *
     * The namespace key is deleted rather than emptied, because a key that still
     * exists is a shell item that still appears. `Wow6432Node` gets the same
     * treatment — a 32-bit program reads its own view of the hive and would
     * otherwise still see the node. The `Z:` symlink itself is
     * [DriveMap.removeRootDrive]'s job; a registry entry cannot unmap a drive.
     */
    val unixNamespace: List<RegistryKey> = listOf(
        RegistryKey(path = """HKEY_LOCAL_MACHINE\$NAMESPACE\$UNIX_FOLDER_CLSID""", remove = true),
        RegistryKey(path = """HKEY_LOCAL_MACHINE\$WOW_NAMESPACE\$UNIX_FOLDER_CLSID""", remove = true),
    )

    /** Wine's `ShellFSFolder` for the unix root — the `/` in the desktop tree. */
    private const val UNIX_FOLDER_CLSID = "{9D20AAE8-0625-44B0-9CA7-71889C2254D9}"

    private const val NAMESPACE =
        """Software\Microsoft\Windows\CurrentVersion\Explorer\Desktop\Namespace"""

    private const val WOW_NAMESPACE =
        """Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Explorer\Desktop\Namespace"""

    /**
     * What [driveTypes] declares when nothing has read a prefix.
     *
     * `d:` is in it because shared storage is mapped on every provision, so a
     * caller with no prefix to read is still describing a container that will
     * have one. `z:` is not, because seed 17 removes that drive.
     */
    val DEFAULT_DRIVES: List<Char> = listOf('c', 'd')

    /**
     * The machine `PATH`, written whole.
     *
     * Wine's own three entries, plus Git's three.
     *
     * **`Vessel Tools` used to be here and was a promise the build did not
     * keep**: it named a directory for a BusyBox nobody ever built, so the value
     * pointed at nothing. The rule that replaced it stands — this seed does not
     * claim a directory in advance. Git's entries are different in the way that
     * matters: the component really does put those directories there, and the
     * entire point of installing it is that `git`, `ls`, `grep` and `sed` work
     * from `cmd` without anyone typing a path.
     *
     * **`clangarm64`, not `mingw64`.** The ARM64 build of Git for Windows uses a
     * `clangarm64` prefix where the x86-64 build uses `mingw64`. Copying the
     * usual x64 instructions gives a PATH that finds `git.exe` through `cmd\`
     * and misses every helper behind it — which looks like a working install
     * until the first command that needs one.
     *
     * Written whole rather than appended, because a `.reg` merge replaces a
     * value and there is no append form: this seed is the definition of the
     * machine PATH, so it has to name everything that belongs on it. Naming
     * Git's directories before Git is installed is harmless — a PATH entry for a
     * directory that does not exist is something every Windows machine has
     * several of, and it means installing the component needs no second write.
     *
     * Kept as a key rather than deleted so an existing prefix is *corrected*.
     */
    val toolsPath: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\System\CurrentControlSet\Control\Session Manager\Environment""",
        values = listOf(
            RegistryValue(
                "PATH",
                listOf(
                    """C:\windows\system32""",
                    """C:\windows""",
                    """C:\windows\system32\wbem""",
                    """$GIT_DIR\cmd""",
                    """$GIT_DIR\usr\bin""",
                    """$GIT_DIR\clangarm64\bin""",
                ).joinToString(";"),
            ),
        ),
    )

    /**
     * Where the Git component installs.
     *
     * Named once because three things have to agree about it: the installer that
     * unpacks the payload, the PATH above, and the launcher's `git-bash` entry.
     */
    const val GIT_DIR: String = """C:\Program Files\Git"""

    /**
     * Tell dark-aware Windows programs that this is a dark system.
     *
     * `ShouldAppsUseDarkMode` and `ShouldSystemUseDarkMode`
     * (`dlls/uxtheme/system.c`) are real implementations over exactly these two
     * values, so a program that asks gets the right answer.
     *
     * It changes nothing Wine itself draws, and the reason is worth recording so
     * nobody expects more from it: `AllowDarkModeForWindow` and
     * `SetPreferredAppMode` next to them are `FIXME` stubs. Wine's own chrome
     * comes from [desktopTheme] and [wineThemes]; this is for the guest's
     * applications.
     */
    val windowsDarkMode: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize""",
        values = listOf(
            RegistryValue.dword("AppsUseLightTheme", 0),
            RegistryValue.dword("SystemUsesLightTheme", 0),
        ),
    )

    /**
     * The values whose presence in a booted prefix's `system.reg` proves the seed
     * reached the **hive** and not merely the `.reg` file beside it.
     *
     * Derived from [arm64ecEmulator] and [x86Emulator] rather than spelled out, so
     * renaming an emulator DLL cannot leave a checker looking for the old name and
     * reporting success against a prefix that no longer works.
     *
     * These two and no others. Every other key in [seed] is cosmetic or a
     * fallback — a missing `Control Panel\Colors` entry costs a grey title bar —
     * whereas without these nothing in the prefix runs at all: `load_arm64ec_module`
     * ends in `NtTerminateProcess` when the DLL the `amd64` key names is absent,
     * and `get_cpu_dll_name` falls back to Microsoft's `xtajit.dll`, which we do
     * not ship, when the `x86` one is.
     */
    val requiredHiveValues: List<String> =
        listOf(arm64ecEmulator, x86Emulator).flatMap { key -> key.values.map { it.data } }

    /**
     * Which of [requiredHiveValues] are missing from the text of a `system.reg`.
     *
     * A substring search over the hive rather than a parse of it. Wine's hive
     * format is not the `.reg` format — keys are written as `[Software\\Microsoft
     * \\Wow64\\amd64]` with a timestamp and the roots stripped — and the question
     * being asked is narrow enough that the answer does not need a parser: these
     * two strings are DLL filenames that appear nowhere else in a fresh hive, so
     * finding one is finding the key.
     *
     * Empty means applied. The caller reports the returned names verbatim, which
     * is why they are the DLL names a person can then look for themselves.
     */
    fun missingFromHive(hive: String): List<String> =
        requiredHiveValues.filterNot { hive.contains(it) }

    /**
     * Everything a prefix with these drives gets, in the order it is written.
     *
     * Without the stamp: [renderSeed] appends that, because its value is a hash
     * of everything above it and cannot be known until the rest is rendered.
     */
    fun seedFor(letters: Collection<Char> = DEFAULT_DRIVES): List<RegistryKey> = listOf(
        direct3D,
        dllOverrides,
        arm64ecEmulator,
        x86Emulator,
        desktopTheme,
        windowMetrics,
        visualStyles,
        windowsDarkMode,
        toolsPath,
        graphicsDriver,
        driveTypesReset,
        driveTypes(letters),
        consoleColours,
    ) + virtualDesktop + unixNamespace

    /** The seed for a container whose drives nobody has looked at. */
    val seed: List<RegistryKey> get() = seedFor()

    private fun color(name: String, argb: Int) = RegistryValue(name, rgbTriplet(argb))

    /**
     * `0x00BBGGRR`, which is what a console colour table entry is.
     *
     * Not the `r g b` string [rgbTriplet] writes — that form is `Control Panel
     * \Colors`' alone. A console entry is a `REG_DWORD` with the channels in
     * the opposite order to the one everybody expects, and getting it backwards
     * gives a plausible wrong colour rather than an error.
     */
    private fun bgr(argb: Int): Int =
        ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)

    /**
     * A non-client metric, in the twips [windowMetrics] explains.
     *
     * Negative because that is what marks the number as a length rather than a
     * point size, and `TWIPS_PER_PIXEL` rather than a literal 15 so the two
     * facts — the unit, and that it assumes 96 dpi — stay written down together.
     */
    private fun twips(name: String, pixels: Int) =
        RegistryValue(name, "${-pixels * TWIPS_PER_PIXEL}")

    /** Twips per pixel at 96 dpi: 1440 twips to the inch over 96 pixels. */
    private const val TWIPS_PER_PIXEL = 15

    private const val CAPTION_PX = 40
    private const val SMALL_CAPTION_PX = 32
    private const val SCROLLBAR_PX = 24
    /**
     * The whole grab region in one value, with nothing in the padded half.
     *
     * `PaddedBorderWidth` is a Windows concept Wine reads when it *sizes* a
     * frame and does not appear to paint: with 4+4 the window came up with a
     * 4 px unpainted band around its client area showing white, which survived
     * every system colour being set dark, `ThemeActive=0`, and the console's own
     * palette. Putting the full 8 px in `BorderWidth` keeps the region the same
     * size and hands all of it to the code that actually fills it.
     */
    private const val BORDER_PX = 8
    private const val PADDED_BORDER_PX = 0

    /**
     * Where the container's Unix tools live.
     *
     * Under `Program Files` rather than somewhere Vessel-shaped like
     * `C:\vessel\bin`, because a container is a Windows machine and a user who
     * opens a shell in one should find it laid out the way Windows is. The name
     * is ours because the contents are.
     */
    const val TOOLS_DIR: String = """C:\Program Files\Vessel Tools"""

    private const val COLORS_KEY = """HKEY_CURRENT_USER\Control Panel\Colors"""

    /**
     * [keys] as the text of a `.reg` file.
     *
     * CRLF throughout, UTF-8 on disk. Wine's `regedit` accepts LF too, but a
     * `.reg` file is a Windows format.
     */
    fun render(keys: List<RegistryKey> = seed): String = HEADER + CRLF + block(keys)

    /**
     * The whole seed for a prefix with [letters], stamped with its own hash.
     *
     * The one every caller wants. [render] stays public for the tests, which
     * render one key at a time to assert on its text.
     */
    fun renderSeed(letters: Collection<Char> = DEFAULT_DRIVES): String {
        val body = render(seedFor(letters))
        return body + block(listOf(stampKey(stampFor(body))))
    }

    /** The drives a prefix has, as [renderSeed] wants them. */
    fun drivesOf(prefix: File): List<Char> = DriveMap.drives(prefix).map { it.letter }

    private fun block(keys: List<RegistryKey>): String = buildString {
        for (key in keys) {
            if (key.remove) {
                append(CRLF).append("[-").append(key.path).append(']').append(CRLF)
                continue
            }
            append(CRLF)
            append('[').append(key.path).append(']').append(CRLF)
            for (value in key.values) {
                if (value.name == RegistryValue.DEFAULT) {
                    append('@')
                } else {
                    append('"').append(escape(value.name)).append('"')
                }
                append('=')
                when (value.kind) {
                    RegistryKind.SZ -> append('"').append(escape(value.data)).append('"')
                    RegistryKind.DWORD -> append("dword:").append(value.data)
                }
                append(CRLF)
            }
        }
    }

    /**
     * `.reg` string escaping: backslash then quote, in that order.
     *
     * The other way round escapes the backslashes this function just inserted.
     * Only value text is escaped; a key path in `[...]` carries its separators
     * literally.
     */
    private fun escape(text: String): String =
        text.replace("""\""", """\\""").replace("\"", """\"""")

    private const val CRLF = "\r\n"
}
