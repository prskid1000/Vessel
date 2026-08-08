package app.vessel.core

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

/** One key and the values under it. */
data class RegistryKey(val path: String, val values: List<RegistryValue>)

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
     * 11 narrowed the caption buttons and darkened the 3D highlight, both of
     * which only became visible once seed 8 enlarged the frame;
     * 9 added [toolsPath], which is what puts the Unix tools on `PATH` in every
     * shell rather than in one profile; 10 added [virtualDesktop], without which
     * a program launched into a running session came up rootless and undecorated
     * — no title bar and no minimise, maximise or close.
     */
    const val SEED_VERSION: Int = 11

    /**
     * A value written into the hive that names the seed version that wrote it.
     *
     * The prefix's own record of which seed it carries, and the thing
     * `SessionRuntime` looks for before deciding it can skip `regedit`. It has
     * to live in the hive rather than in `provisioned.json` because that file
     * records what the app *believes* it did — and believing a step happened
     * when it had not is the whole defect this exists to stop.
     */
    val SEED_MARKER: String get() = "VesselSeed$SEED_VERSION"

    private val seedStamp: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Vessel""",
        values = listOf(RegistryValue("Seed", SEED_MARKER)),
    )

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
     * The Unix tools on `PATH`, for every program in the container.
     *
     * **This is what makes one shell's tools available in all of them.** The
     * thing people mean by "give me Git Bash" is almost never `bash` itself —
     * it is `ls`, `grep`, `sed`, `awk`, `find`, `tar`, `curl`. Putting the
     * directory that holds them on the machine `PATH` means they work from
     * Command Prompt, from PowerShell, from a program that shells out, and from
     * the BusyBox shell, rather than only inside one profile that happens to
     * prepend it. That is strictly more useful than a per-profile environment
     * and it is one registry value instead of a launch-time variable per shell.
     *
     * The value replaces rather than appends, because there is nothing to append
     * to yet: Wine seeds exactly the three entries below and this is the first
     * thing that has ever wanted a fourth. Written whole so the result does not
     * depend on what a previous seed left behind.
     *
     * `REG_EXPAND_SZ` would be the Windows-correct type for a `PATH` — it is how
     * a real system stores `%SystemRoot%` in it — and this is deliberately plain
     * [RegistryKind.SZ] instead, because nothing here needs expanding and
     * `RegistryValue` renders one type of string. The literal `C:\windows` paths
     * are the same ones `wine.inf` writes.
     */
    val toolsPath: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\System\CurrentControlSet\Control\Session Manager\Environment""",
        values = listOf(
            RegistryValue(
                "PATH",
                """C:\windows\system32;C:\windows;C:\windows\system32\wbem;$TOOLS_DIR""",
            ),
        ),
    )

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

    /** Everything a new prefix gets, in the order it is written. */
    val seed: List<RegistryKey> = listOf(
        direct3D,
        dllOverrides,
        arm64ecEmulator,
        x86Emulator,
        desktopTheme,
        windowMetrics,
        visualStyles,
        windowsDarkMode,
        toolsPath,
        seedStamp,
    ) + virtualDesktop

    private fun color(name: String, argb: Int) = RegistryValue(name, rgbTriplet(argb))

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
    private const val BORDER_PX = 4
    private const val PADDED_BORDER_PX = 4

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
    fun render(keys: List<RegistryKey> = seed): String = buildString {
        append(HEADER).append(CRLF)
        for (key in keys) {
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
