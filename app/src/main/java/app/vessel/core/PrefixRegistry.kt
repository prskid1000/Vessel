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
     * 2 added [desktopWallpaper] and [fileManagerDesktop]; 3 added [desktopTheme];
     * 4 added [visualStyles] and [windowsDarkMode]; 5 moved [visualStyles] off
     * `Software\Wine\Themes`, which uxtheme never reads.
     */
    const val SEED_VERSION: Int = 5

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
     * The desktop wallpaper, as Wine sees it. Written by
     * [app.vessel.data.AndroidWallpaper] to `ContainerLayout.wallpaper`.
     *
     * A fixed `C:` path rather than the container's own directory under `Z:`, and
     * that is what keeps this file static data: the path is the same in every
     * prefix, so the seed stays renderable and testable without a container.
     *
     * `web\wallpaper` is where Windows keeps its own, which makes the file
     * recognisable to anyone who opens the prefix. The name is not: nothing
     * shipped is called `vessel.bmp`, so it can never collide with a wallpaper a
     * program installs.
     */
    const val WALLPAPER_PATH: String = """C:\windows\web\wallpaper\vessel.bmp"""

    /**
     * `Wallpaper`, which is the whole of what Wine's desktop reads.
     *
     * Verified against Wine 11.14 rather than assumed. `explorer` calls
     * `SystemParametersInfoW(SPI_SETDESKWALLPAPER, 0, NULL, FALSE)` as it creates
     * the desktop window (`programs/explorer/desktop.c`), that path loads this
     * value, and `PaintDesktop` blits it on `WM_ERASEBKGND`. It runs on a
     * `/desktop=` virtual desktop because all three of those are guarded by
     * `!using_root`, and `using_root` is set only for the literal desktop name
     * `root` — ours is [WINE_DESKTOP].
     *
     * **`WallpaperStyle` and `TileWallpaper` are deliberately absent.**
     * `WallpaperStyle` is read nowhere in the Wine tree, and `TileWallPaper`
     * comes from `win.ini`'s `[desktop]` section via `GetProfileIntA`, not from
     * here — writing either would be two dead values that look like they are
     * doing something. Wine's untiled path centres the bitmap at its own size and
     * never scales it, so "fill" is the writer's job: see `encodeBmp24`.
     */
    val desktopWallpaper: RegistryKey = RegistryKey(
        path = DESKTOP_KEY,
        values = listOf(RegistryValue("Wallpaper", WALLPAPER_PATH)),
    )

    /**
     * Put the file manager on the session's virtual desktop, not on a second one.
     *
     * `explorer /desktop=vessel,WxH winefile.exe` gets the *first* one right for
     * free — the child inherits the desktop from the thread that spawned it. A
     * relaunch from the rail is a fresh top-level process with no Wine parent, and
     * `winstation_init` (`dlls/win32u/winstation.c`) resolves its desktop from
     * `HKCU\Software\Wine\AppDefaults\<exe>\Explorer` → `Desktop` before falling
     * back to `Default`. Without this value the relaunched winefile opens on the
     * Default desktop, which spawns a second `explorer` and leaves two desktops
     * on one X screen.
     *
     * Scoped to `winefile.exe` rather than set at `HKCU\Software\Wine\Explorer`,
     * which is the other place that function looks: the unscoped key would also
     * catch `wineboot` and `regedit` during provisioning, when there is no
     * `vessel` desktop yet and no `DISPLAY` to make one on.
     */
    val fileManagerDesktop: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Software\Wine\AppDefaults\$WINE_FILE_MANAGER\Explorer""",
        values = listOf(RegistryValue("Desktop", WINE_DESKTOP)),
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
     * below is an `RGB_ENTRY` in `dlls/win32u/sysparams.c` — the full set is 31
     * entries and `Background` is the only one missing here, because it is derived
     * per session by [desktopColor].
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
            color("ButtonHilight", GuestPalette.NEUTRAL_700),
            color("ButtonLight", GuestPalette.NEUTRAL_800),
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

    /** Everything a new prefix gets, in the order it is written. */
    val seed: List<RegistryKey> = listOf(
        direct3D,
        dllOverrides,
        arm64ecEmulator,
        x86Emulator,
        desktopWallpaper,
        fileManagerDesktop,
        desktopTheme,
        visualStyles,
        windowsDarkMode,
    )

    /**
     * The desktop's flat colour, which is the one value that cannot be static.
     *
     * `COLOR_BACKGROUND` is what `PaintDesktop` fills with before it blits the
     * wallpaper, and it is Wine's own `RGB(58, 110, 165)` until something writes
     * this — that medium blue *is* the bare background a session currently shows.
     * It stays load-bearing even when a wallpaper exists, because the bitmap is
     * centred rather than stretched.
     *
     * Applied per session from a `.reg` of its own rather than folded into [seed],
     * because it is derived from the phone's current wallpaper and changes when
     * that does.
     */
    fun desktopColor(argb: Int): RegistryKey = RegistryKey(
        path = COLORS_KEY,
        values = listOf(RegistryValue("Background", rgbTriplet(argb))),
    )

    private fun color(name: String, argb: Int) = RegistryValue(name, rgbTriplet(argb))

    private const val DESKTOP_KEY = """HKEY_CURRENT_USER\Control Panel\Desktop"""

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
