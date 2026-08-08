package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry seed, as data and as the file it renders to.
 *
 * The `renderer = vulkan` assertion is the one that matters most and is the
 * easiest to lose in a refactor: DirectDraw and D3D1–7 go through wined3d, which
 * defaults to OpenGL, and Vessel's Wine has no GLX at all. Losing this value
 * fails old titles with no message that points at the cause.
 */
class PrefixRegistryTest {

    @Test
    fun `wined3d is pointed at Vulkan, because its default is OpenGL and we have no GLX`() {
        assertEquals("""HKEY_CURRENT_USER\Software\Wine\Direct3D""", PrefixRegistry.direct3D.path)
        assertEquals(
            listOf(RegistryValue("renderer", "vulkan")),
            PrefixRegistry.direct3D.values,
        )
    }

    @Test
    fun `every D3D and WGL DLL is overridden native,builtin`() {
        assertEquals(
            listOf("d3d8", "d3d9", "d3d10core", "d3d11", "d3d12", "d3d12core", "dxgi", "opengl32"),
            PrefixRegistry.dllOverrides.values.map { it.name },
        )
        assertTrue(PrefixRegistry.dllOverrides.values.all { it.data == "native,builtin" })
    }

    @Test
    fun `the DLL override list is the same one the session environment uses`() {
        assertEquals(D3D_DLL_OVERRIDES, PrefixRegistry.dllOverrides.values.map { it.name })
    }

    @Test
    fun `the ARM64EC and WoW64 emulator keys name the FEX modules`() {
        // load_arm64ec_module (dlls/ntdll/loader.c:4275) and get_cpu_dll_name
        // (dlls/wow64/syscall.c:909), in Wine 11.14, read these as the key's
        // default value and require REG_SZ. Both are load-bearing: the built-in
        // fallbacks are Microsoft's xtajit64.dll and xtajit.dll, which we do not
        // ship.
        assertEquals(
            """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\amd64""",
            PrefixRegistry.arm64ecEmulator.path,
        )
        assertEquals(
            listOf(RegistryValue("", "libarm64ecfex.dll")),
            PrefixRegistry.arm64ecEmulator.values,
        )
        assertEquals(
            """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\x86""",
            PrefixRegistry.x86Emulator.path,
        )
        assertEquals(
            listOf(RegistryValue("", "libwow64fex.dll")),
            PrefixRegistry.x86Emulator.values,
        )
    }

    @Test
    fun `the seed never leaves Wine on Microsoft's own translators`() {
        // The failure this guards is silent and total. Absent these keys, Wine
        // loads C:\windows\system32\xtajit64.dll, does not find it, and
        // load_arm64ec_module terminates the process — every process, including
        // a native ARM64 one, because it runs before kernel32 in ntdll init.
        val rendered = PrefixRegistry.render().lowercase()
        assertTrue(!rendered.contains("xtajit"))
        assertTrue(!rendered.contains("wow64cpu"))
        assertTrue(rendered.contains("libarm64ecfex.dll"))
        assertTrue(rendered.contains("libwow64fex.dll"))
    }

    @Test
    fun `there is no Box64 key, because there is no x86-64 Wine for it to run`() {
        val rendered = PrefixRegistry.render().lowercase()
        assertTrue(!rendered.contains("box64"))
        assertTrue(!rendered.contains("wowbox64"))
    }

    @Test
    fun `the seed renders to exactly this reg file`() {
        val expected = listOf(
            "Windows Registry Editor Version 5.00",
            "",
            """[HKEY_CURRENT_USER\Software\Wine\Direct3D]""",
            """"renderer"="vulkan"""",
            "",
            """[HKEY_CURRENT_USER\Software\Wine\DllOverrides]""",
            """"d3d8"="native,builtin"""",
            """"d3d9"="native,builtin"""",
            """"d3d10core"="native,builtin"""",
            """"d3d11"="native,builtin"""",
            """"d3d12"="native,builtin"""",
            """"d3d12core"="native,builtin"""",
            """"dxgi"="native,builtin"""",
            """"opengl32"="native,builtin"""",
            "",
            """[HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\amd64]""",
            """@="libarm64ecfex.dll"""",
            "",
            """[HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\x86]""",
            """@="libwow64fex.dll"""",
        ).joinToString("\r\n", postfix = "\r\n")

        // The theme block is asserted by name and value below rather than being
        // transcribed here: thirty colours in a golden string is a test nobody
        // reads and everybody regenerates.
        val beforeTheme = PrefixRegistry.seed.takeWhile { it != PrefixRegistry.desktopTheme }
        assertEquals(expected, PrefixRegistry.render(beforeTheme))
    }

    @Test
    fun `the rendered file is CRLF throughout, as a reg file is`() {
        val rendered = PrefixRegistry.render()
        assertTrue(rendered.startsWith("Windows Registry Editor Version 5.00\r\n"))
        // No bare LF survives once every CRLF pair is removed.
        assertTrue(!rendered.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun `a default value is written as @ and a named one in quotes`() {
        val rendered = PrefixRegistry.render(
            listOf(
                RegistryKey(
                    """HKEY_CURRENT_USER\Software\Test""",
                    listOf(
                        RegistryValue(RegistryValue.DEFAULT, "unnamed"),
                        RegistryValue("named", "value"),
                    ),
                ),
            ),
        )
        assertTrue(rendered.contains("""@="unnamed""""))
        assertTrue(rendered.contains(""""named"="value""""))
    }

    @Test
    fun `backslashes and quotes in a value are escaped, and the path is not`() {
        val rendered = PrefixRegistry.render(
            listOf(
                RegistryKey(
                    """HKEY_CURRENT_USER\Software\Wine\Test""",
                    listOf(RegistryValue("path", """C:\windows\a "quoted" name""")),
                ),
            ),
        )
        assertTrue(rendered.contains("""[HKEY_CURRENT_USER\Software\Wine\Test]"""))
        assertTrue(rendered.contains(""""path"="C:\\windows\\a \"quoted\" name""""))
    }

    @Test
    fun `the seed version is recorded so a change can re-run only that step`() {
        assertEquals(7, PrefixRegistry.SEED_VERSION)
        assertEquals(7, PrefixRegistry.seed.size)
    }

    @Test
    fun `visual styles are switched off, or the colour theme does nothing`() {
        // Measured on device: with Aero active, unthemed Win32 panes came up
        // Nocturne dark and its themed status bar came up #F5F5F5. A themed
        // control draws from the .msstyles package, never from Control Panel\Colors.
        assertEquals(
            """HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\ThemeManager""",
            PrefixRegistry.visualStyles.path,
        )
        assertEquals(listOf(RegistryValue("ThemeActive", "0")), PrefixRegistry.visualStyles.values)
    }

    @Test
    fun `the theme switch is not under Software Wine, which uxtheme never opens`() {
        // It was, once. UXTHEME_LoadTheme opens szThemeManager and only that, so
        // the Wine-branded key is a value that sits in user.reg reading correctly
        // and doing nothing — the exact failure this project refuses to ship.
        assertTrue(PrefixRegistry.seed.none { it.path.endsWith("""Software\Wine\Themes""") })
    }

    @Test
    fun `the dark-mode hints are DWORDs, which is what RRF_RT_REG_DWORD demands`() {
        // ShouldAppsUseDarkMode reads these with RRF_RT_REG_DWORD, so a REG_SZ "0"
        // is rejected and the default (light) wins with nothing logged.
        val rendered = PrefixRegistry.render(listOf(PrefixRegistry.windowsDarkMode))
        assertTrue(rendered.contains(""""AppsUseLightTheme"=dword:00000000"""))
        assertTrue(rendered.contains(""""SystemUsesLightTheme"=dword:00000000"""))
        assertTrue(PrefixRegistry.windowsDarkMode.values.all { it.kind == RegistryKind.DWORD })
    }

    @Test
    fun `a dword renders as eight hex digits and is never quoted`() {
        val rendered = PrefixRegistry.render(
            listOf(
                RegistryKey(
                    """HKEY_CURRENT_USER\Software\Test""",
                    listOf(RegistryValue.dword("n", 0x1F)),
                ),
            ),
        )
        assertTrue(rendered.contains(""""n"=dword:0000001f"""))
    }

    // — the guest's dark theme -----------------------------------------------------

    /** Every `RGB_ENTRY` in `dlls/win32u/sysparams.c`, transcribed from Wine 11.14. */
    private val wineColorNames = setOf(
        "Scrollbar", "Background", "ActiveTitle", "InactiveTitle", "Menu", "Window",
        "WindowFrame", "MenuText", "WindowText", "TitleText", "ActiveBorder",
        "InactiveBorder", "AppWorkSpace", "Hilight", "HilightText", "ButtonFace",
        "ButtonShadow", "GrayText", "ButtonText", "InactiveTitleText", "ButtonHilight",
        "ButtonDkShadow", "ButtonLight", "InfoText", "InfoWindow", "ButtonAlternateFace",
        "HotTrackingColor", "GradientActiveTitle", "GradientInactiveTitle", "MenuHilight",
        "MenuBar",
    )

    @Test
    fun `every themed colour is one Wine actually reads`() {
        // A misspelled entry here is a value that sits in the registry looking
        // correct and is loaded by nothing. get_rgb_entry only ever looks up the
        // names in its own table.
        val written = PrefixRegistry.desktopTheme.values.map { it.name }
        assertTrue(written.all { it in wineColorNames })
        assertEquals(written.size, written.distinct().size)
    }

    @Test
    fun `the theme covers every colour Wine draws from, with none left over`() {
        // `Background` used to be the one exception, written per session by the
        // wallpaper feature. That feature is gone and the desktop is simply
        // Nocturne's ground, so the set is now exactly complete — and a name Wine
        // does not read would be a value that looks load-bearing and is not.
        val written = PrefixRegistry.desktopTheme.values.map { it.name }.toSet()
        assertEquals(emptySet<String>(), wineColorNames - written)
        assertEquals(emptySet<String>(), written - wineColorNames)
    }

    @Test
    fun `the guest chrome is Nocturne, not a second dark theme`() {
        val values = PrefixRegistry.desktopTheme.values.associate { it.name to it.data }
        assertEquals(rgbTriplet(GuestPalette.BG), values["Window"])
        assertEquals(rgbTriplet(GuestPalette.TEXT), values["WindowText"])
        assertEquals(rgbTriplet(GuestPalette.SURFACE), values["ButtonFace"])
        assertEquals(rgbTriplet(GuestPalette.SURFACE), values["Menu"])
        // Nocturne's tag fill: accent-800 ground, accent-100 text.
        assertEquals(rgbTriplet(GuestPalette.ACCENT_800), values["Hilight"])
        assertEquals(rgbTriplet(GuestPalette.ACCENT_100), values["HilightText"])
    }

    @Test
    fun `nothing in the guest is painted white, which is what stock Wine does`() {
        // The regression this catches is a colour left at its Wine default by
        // accident: Window is RGB(255,255,255) and ButtonFace RGB(212,208,200)
        // out of the box, and either one is a white slab in a dark session.
        val stockLight = setOf("255 255 255", "212 208 200", "255 255 225")
        assertTrue(PrefixRegistry.desktopTheme.values.none { it.data in stockLight })
    }

    @Test
    fun `every themed value is a decimal triplet`() {
        val triplet = Regex("""\d{1,3} \d{1,3} \d{1,3}""")
        assertTrue(PrefixRegistry.desktopTheme.values.all { triplet.matches(it.data) })
    }

    // — the desktop's own colour --------------------------------------------------

    @Test
    fun `the desktop colour is a decimal RGB triplet, which is the only form Wine parses`() {
        // get_rgb_entry reads it with three wcstoul calls separated by one
        // character each, so "#161826" and "22,24,38" both leave COLOR_BACKGROUND
        // at Wine's built-in RGB(58, 110, 165) — the medium blue a bare session
        // shows — with nothing logged.
        val background = PrefixRegistry.desktopTheme.values.single { it.name == "Background" }
        assertEquals(rgbTriplet(GuestPalette.BG), background.data)
        assertEquals("22 24 38", background.data)
    }

    @Test
    fun `the desktop colour is in the seed, because nothing derives it any more`() {
        // It was written per session by the removed wallpaper feature, whose only
        // reachable tier on this device was this colour: every WallpaperManager
        // bitmap entry point refuses an app holding no storage permission. The
        // desktop is Nocturne's ground because that is what it is, not because
        // reading the phone failed.
        assertTrue(PrefixRegistry.seed.any { it.path.endsWith("""Control Panel\Colors""") })
        assertTrue(PrefixRegistry.seed.flatMap { it.values }.any { it.name == "Background" })
    }

    @Test
    fun `nothing in the seed mentions a wallpaper`() {
        // Removed rather than disabled. A surviving Wallpaper value would point
        // every prefix at a bitmap nothing writes any more, and Wine would paint
        // the colour underneath it and look exactly like this — which is why the
        // absence is worth a test rather than a glance.
        assertTrue(!PrefixRegistry.render().lowercase().contains("wallpaper"))
    }



}
