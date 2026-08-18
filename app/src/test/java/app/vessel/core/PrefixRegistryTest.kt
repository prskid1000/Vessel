package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `every D3D DLL is overridden native,builtin, and opengl32 is removed`() {
        assertEquals(
            listOf("d3d8", "d3d9", "d3d10core", "d3d11", "d3d12", "d3d12core", "dxgi", "opengl32"),
            PrefixRegistry.dllOverrides.values.map { it.name },
        )
        // Every D3D entry forces the shipped native build...
        assertTrue(
            PrefixRegistry.dllOverrides.values
                .filter { it.name != WGL_DLL }
                .all { it.data == "native,builtin" },
        )
        // ...and `opengl32` is here only to be taken *out* of prefixes that
        // already have it. Seeding it native made `wined3d` — a static import of
        // `d3dcompiler_47` — pull Zink into every Direct3D game, and Zink's load
        // killed the process. See `SessionEnvironment.WGL_DLL`.
        assertEquals(
            RegistryKind.DELETE,
            PrefixRegistry.dllOverrides.values.single { it.name == WGL_DLL }.kind,
        )
    }

    @Test
    fun `the DLL override list is the same one the session environment uses`() {
        // The shipped list, plus the one value the seed has to *remove* from
        // prefixes that already carry it. See RegistryKind.DELETE.
        assertEquals(
            D3D_DLL_OVERRIDES + WGL_DLL,
            PrefixRegistry.dllOverrides.values.map { it.name },
        )
        assertEquals(
            RegistryKind.DELETE,
            PrefixRegistry.dllOverrides.values.last { it.name == WGL_DLL }.kind,
        )
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
            """"opengl32"=-""",
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
        assertEquals(33, PrefixRegistry.SEED_VERSION)
        // 33 takes 32 back out again, and the reason is worth keeping. 32 seeded a
        // live fallback chain for all 32 families Wine writes; the import worked and
        // every one of them was stamped back inside the same session, because
        // `update_font_system_link_info` rewrites them from a hardcoded table and
        // the HACK in `update_codepage` calls it on every font init whether anything
        // changed or not. A seed can add a name Wine does not write, which is why
        // `Cascadia Mono` survives, and can never keep one it does. The tier those
        // families needed is appended inside Wine by `patches/wine/0056` instead, so
        // this key is back to the single value 31 measured.
        // 32 finishes what 31 started. 31 gave the console face a fallback chain and
        // left every other family with the one Wine writes at prefix creation, and
        // those were measured on the device afterwards: 33 chains, 32 of which
        // resolve to nothing at all, every entry naming a Windows CJK face that has
        // never been on this phone. So conhost stopped drawing tofu and every GUI
        // program went on being told there is no fallback glyph, with the two
        // Unifonts sitting in `windows\Fonts` reachable from one family. This seeds
        // all 32, each as the Microsoft names first -- free when absent, since an
        // entry whose file is not found is skipped -- then the same chain 31
        // measured, so Unifont stays last and only ever answers what nothing else
        // could.
        // 31 added [fontLink], which is the first key the seed has gained since 23
        // and the first REG_MULTI_SZ it has ever written. It retires the premise
        // seeds 29 and 30 shared: each swapped one console face for another because
        // "conhost resolves one face with no font linking", and the second half of
        // that is false -- per-glyph fallback is GDI's and Wine implements it. So
        // `FaceName` stays `Cascadia Mono` and this key names what it falls back to,
        // Unifont last so nothing is ever tofu. Measured: Cascadia was missing
        // exactly 12 glyphs the TUI draws, and the chain covers all 12.
        // 30 rewrote [consoleColours] twice and added no key. The face moved from
        // `Unifont` to `Cascadia Mono`, Tools 1.4.0 having swapped the payload -- a
        // trade, not a fix: Unifont's box-drawing glyphs did render on the device and
        // it is bitmap-derived and illegible, and the cost of the swap was no CJK in
        // a console, which is what 31 takes back. The palette moved because
        // `patches/wine/0052` now quantises
        // 38;5;n and 38;2;r;g;b to the sixteen console colours, and the device's
        // vt-trace.log has Claude Code emitting 297 such sequences whose commonest,
        // 38;5;238, is RGB 68,68,68 and lands on index 8 -- which seed 26 had made
        // the *background*, so the commonest colour in a session would have been
        // invisible. ScreenColors is 0x0F now and all sixteen entries are Campbell.
        // A `.reg` merge replaces values, so the bump is what carries both to
        // prefixes that already exist.
        // 29 added `FaceName` to [consoleColours] and no key: the prefix had no
        // fonts at all, so conhost enumerated a bitmap face with no U+2500 block
        // and Claude Code's rules came out as boxes. Tools 1.3.0 ships GNU
        // Unifont and this names it. A `.reg` merge replaces values, so the bump
        // is what carries it to prefixes that already exist.
        // 28 added CLAUDE_BIN to [toolsPath] and no key: Claude Code's installer
        // puts claude.exe in the guest profile's .localin and tells the user to
        // add it to PATH by hand. Seeded instead, and the bump is what carries it
        // to prefixes that already exist.
        // 27 changed two values in [toolsPath] and added no key: Git's PATH entry
        // back to `clangarm64\bin` and `MSYSTEM` back to `CLANGARM64`, Tools 1.2.0
        // being the ARM64 payload again. It needed the bump because a prefix keeps
        // the seed that provisioned it, so an existing container would otherwise
        // hold a PATH naming a `mingw64` directory the new payload does not have.
        // 26 changed a colour and added no key: the console ground moved from
        // GuestPalette.BG to CONSOLE_BG. It needed a seed bump anyway, because a
        // prefix already carrying ColorTable08 = the window ground would
        // otherwise keep a console that blends into the desktop.
        // Fifteen keys, the three the seed deletes, and the stamp `renderSeed`
        // appends — which is not in the list, its value being a hash of the rest.
        // Seed 23 added `bluetoothService`, which only does anything paired with
        // patches/wine/0031. Seeds 24, 25 and 27 all changed [toolsPath]'s
        // contents and added no key — 25 put PowerShell and the JDK on PATH and
        // added `JAVA_HOME` as a *value* in the Environment key that was already
        // there, and 27 rewrote two values that were already there — which is why
        // this count has not moved with any of them. Nor with 29, which added
        // `FaceName` to [consoleColours]: `HKCU\Console` was already in the seed
        // carrying the palette, and the console font belongs on the same key
        // conhost reads everything else from. Nor with 30, which changed that key's
        // face and grew its palette from two entries to sixteen. **31 does move it**,
        // to nineteen: [fontLink] is a new key under HKLM and not a value on one the
        // seed already had, because `system_link_keyW` names that exact path
        // (`win32u/font.c:107-118`) and nothing else in the seed lives there.
        assertEquals(19, PrefixRegistry.seed.size)
    }

    @Test
    fun `the console names a font that has the box-drawing glyphs`() {
        val rendered = PrefixRegistry.render(listOf(PrefixRegistry.consoleColours))
        // The family name out of `CascadiaMono-Regular.ttf`'s own `name` table
        // (platform 3, encoding 1, name ID 1), which `build/tools.sh` asserts
        // against `TOOLS_CASCADIA_FAMILY` at build time. conhost matches this by
        // name and `CreateFontIndirectW` returns *some* font whatever happens, so a
        // typo here is a console rendering in whatever GDI thought was nearest, with
        // nothing anywhere reporting a problem.
        assertTrue(rendered.contains(""""FaceName"="Cascadia Mono""""))
        // **Both of these are absent on purpose and the test is what keeps them
        // absent.** conhost defaults `FontFamily` to `FIXED_PITCH | FF_DONTCARE` and
        // needs nothing but a face name to match, and its default cell is 16x8
        // (`window.c:255-257`) -- no longer a match to a native glyph box the way it
        // was for Unifont's 16x16, but Cascadia's own metrics agree closely enough
        // that a guess here could only make it worse: advance width 1200 over line
        // height 2380 is 0.504 against the cell's 8/16 = 0.5, both read off the TTF.
        // If the face does look wrong at that size, `FontSize` is the knob and
        // HIWORD is the height -- but a value nobody has seen on a screen does not
        // belong here.
        assertFalse(rendered.contains("FontSize"))
        assertFalse(rendered.contains("FontFamily"))
        // Same key as the palette, because it is the same key conhost reads.
        assertTrue(rendered.contains("""[HKEY_CURRENT_USER\Console]"""))
    }

    @Test
    fun `a REG_MULTI_SZ renders as hex(7) with one byte per character`() {
        // **The bytes are pinned rather than recomputed, because that is the only
        // way this test can fail for the right reason.** A UTF-16LE byte run is
        // unreadable by eye, an off-by-one in the terminators is invisible, and the
        // symptom of either is a font that quietly does not link -- Wine drops an
        // unresolvable SystemLink entry with a TRACE line and nothing else
        // (`win32u/font.c:2076`). Asserting against a re-derivation of the same
        // logic would pass for a renderer that is wrong in exactly the way this
        // guards against.
        //
        // `a.ttf` is 61,2e,74,74,66 and `b.otf` is 62,2e,6f,74,66; the three `00`s
        // are this entry's terminator, that entry's terminator, and the one that
        // ends the sequence.
        val value = RegistryValue.multiSz("Base Face", listOf("a.ttf", "b.otf"))
        val rendered = PrefixRegistry.render(
            listOf(RegistryKey("""HKEY_LOCAL_MACHINE\Software\Test""", listOf(value))),
        )
        assertTrue(
            rendered.contains(
                """"Base Face"=hex(7):61,2e,74,74,66,00,62,2e,6f,74,66,00,00""",
            ),
        )
    }

    @Test
    fun `one byte per character and not UTF-16LE, which the seed's encoding decides`() {
        // **This is the trap the kind exists to avoid, so it gets its own test.**
        // `ContainerProvisioner` writes the seed as UTF-8 with no BOM, and
        // `regproc.c:1044` sets `is_unicode` only on a UTF-16LE BOM -- so the file
        // takes the narrow path, where `prepare_hex_string_data` widens the bytes
        // itself with `MultiByteToWideChar(CP_ACP, ...)` (regproc.c:483-495).
        // Emitting UTF-16LE pairs here would be widened a second time and import as
        // a chain of one-character filenames: the value would exist, carry type 7,
        // and link nothing. An interleaved `00` after every character is what that
        // mistake looks like, so assert it is absent.
        val value = RegistryValue.multiSz("Base", listOf("ab"))
        val rendered = PrefixRegistry.render(
            listOf(RegistryKey("""HKEY_LOCAL_MACHINE\Software\Test""", listOf(value))),
        )
        assertTrue(rendered, rendered.contains("hex(7):61,62,00,00"))
        assertFalse(rendered, rendered.contains("61,00,62,00"))
    }

    @Test
    fun `the font-link chain is the exact byte run Wine will walk`() {
        val rendered = PrefixRegistry.render(listOf(PrefixRegistry.fontLink))
        // **The value NAME is the base font family**, which is how
        // `load_system_links` keys a link list (`win32u/font.c:2050-2079`), so it
        // has to be the same string `HKCU\Console\FaceName` carries or the chain
        // hangs off a family nothing selects.
        assertTrue(rendered, rendered.contains(""""Cascadia Mono"=hex(7):"""))
        // The whole run, pinned. Decoded, this is
        // `NotoSansSymbols-Regular-Subsetted.ttf`, `unifont-17.0.05.otf`,
        // `unifont_upper-17.0.05.otf`, each NUL-terminated, then the NUL that ends
        // the sequence. The two Unifont names carry a version, which is why
        // `native/pins.env` spells them as literals and `build/tools.sh` asserts
        // them against the files it staged.
        assertTrue(
            rendered.contains(
                "hex(7):4e,6f,74,6f,53,61,6e,73,53,79,6d,62,6f,6c,73,2d,52,65,67,75," +
                    "6c,61,72,2d,53,75,62,73,65,74,74,65,64,2e,74,74,66,00,75,6e,69," +
                    "66,6f,6e,74,2d,31,37,2e,30,2e,30,35,2e,6f,74,66,00,75,6e,69,66," +
                    "6f,6e,74,5f,75,70,70,65,72,2d,31,37,2e,30,2e,30,35,2e,6f,74,66," +
                    "00,00",
            ),
        )
        // `\Registry\Machine\...` in `system_link_keyW` (font.c:107-118), so
        // HKEY_LOCAL_MACHINE. A per-user copy would be read by nothing.
        assertTrue(
            rendered.contains(
                """[HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink]""",
            ),
        )
    }

    @Test
    fun `the font-link chain ends in Unifont, so nothing is ever tofu`() {
        // `get_glyph_index_linked` takes the FIRST child with the glyph
        // (`font.c:3765-3772`), so order is the design and not presentation. Unifont
        // last is what makes a missing `/system/fonts` file degrade quietly: the
        // backstop is the tier that ships in the payload and therefore cannot be
        // absent. Put it anywhere else and it wins glyphs a real typeface would have
        // drawn better.
        val chain = PrefixRegistry.FONT_LINK_CHAIN
        assertEquals("unifont_upper-17.0.05.otf", chain.last())
        assertEquals("unifont-17.0.05.otf", chain[chain.size - 2])
        // The device-linked tier comes first, for the same reason.
        assertEquals("NotoSansSymbols-Regular-Subsetted.ttf", chain.first())
    }

    @Test
    fun `every font linked out of system fonts is one the chain falls back to`() {
        // A font symlinked into `windows\Fonts` that no SystemLink entry names is a
        // link nothing reaches, and an entry naming a file nothing links is a tier
        // that silently does not exist. The two lists have to agree on the names,
        // which are matched by basename (`font.c:879-893`).
        assertTrue(
            "linked but not in the chain: " +
                PrefixRegistry.ANDROID_LINKED_FONTS.filterNot { it in PrefixRegistry.FONT_LINK_CHAIN },
            PrefixRegistry.FONT_LINK_CHAIN.containsAll(PrefixRegistry.ANDROID_LINKED_FONTS),
        )
    }

    @Test
    fun `a REG_MULTI_SZ refuses what would silently truncate or mis-encode it`() {
        // An empty entry ends `load_system_links`' walk (`font.c:2056`), so one blank
        // in the middle discards every entry after it -- exactly the kind of partial
        // success this codebase keeps finding, so it throws instead.
        assertThrows(IllegalArgumentException::class.java) {
            RegistryValue.multiSz("Base", listOf("a.ttf", "", "b.ttf"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistryValue.multiSz("Base", emptyList())
        }
        // Non-ASCII would be written in one encoding and read back through
        // `MultiByteToWideChar(CP_ACP, ...)` in another, and this code does not know
        // the prefix's ACP. Every entry is a font filename, so the restriction is
        // free and the alternative is a guess.
        assertThrows(IllegalArgumentException::class.java) {
            RegistryValue.multiSz("Base", listOf("unifont-é.otf"))
        }
    }

    @Test
    fun `the console palette is all sixteen Campbell entries, in BGR`() {
        val rendered = PrefixRegistry.render(listOf(PrefixRegistry.consoleColours))
        // **Background index 0, foreground index 15.** It was 0x87 -- index 8 as the
        // background -- and moving it is half of a measured colour bug.
        // `patches/wine/0052` quantises 38;5;n and 38;2;r;g;b to the nearest of these
        // sixteen; the device's vt-trace.log has Claude Code emitting 253 of the
        // first form and 44 of the second, and its commonest colour, 38;5;238, is
        // grey-ramp value 68,68,68 and quantises to index 8. With the background
        // sitting there, the commonest colour in a Claude Code session paints text
        // the colour of the ground behind it. 0x0F is also exactly what
        // `create_screen_buffer` fills a fresh buffer with (0x000F), which is the
        // two-backgrounds mismatch Wine revision 39 exists to repaint.
        assertTrue(rendered.contains(""""ScreenColors"=dword:0000000f"""))
        assertTrue(rendered.contains(""""PopupColors"=dword:0000000f"""))
        // **Campbell, written here as sRGB `RRGGBB` and expected in the rendered
        // file as `00BBGGRR`.** Sixteen hand-swapped literals is exactly the change
        // that takes a byte-order slip nobody notices -- a plausible wrong colour
        // rather than an error -- so the swap is asserted on every entry rather than
        // assumed. `ColorTable%02d`, because that is the format conhost reads them
        // back with (`window.c:153`): `ColorTable8` would be silently ignored.
        val campbell = listOf(
            "0C0C0C", "C50F1F", "13A10E", "C19C00",
            "0037DA", "881798", "3A96DD", "CCCCCC",
            "767676", "E74856", "16C60C", "F9F1A5",
            "3B78FF", "B4009E", "61D6D6", "F2F2F2",
        )
        campbell.forEachIndexed { i, srgb ->
            val swapped = srgb.substring(4, 6) + srgb.substring(2, 4) + srgb.substring(0, 2)
            val expected = """"ColorTable%02d"=dword:00%s""".format(i, swapped.lowercase())
            assertTrue("entry $i ($srgb) should render as $expected", rendered.contains(expected))
        }
        // Slot 0 is the console's ground and comes from the constant that names it
        // rather than from a literal in the table, so this pins the two together.
        assertEquals(0xFF0C0C0C.toInt(), GuestPalette.CONSOLE_BG)
    }

    @Test
    fun `the bluetooth service is disabled rather than deleted`() {
        val rendered = PrefixRegistry.render(listOf(PrefixRegistry.bluetoothService))
        assertTrue(
            rendered.contains(
                """[HKEY_LOCAL_MACHINE\System\CurrentControlSet\Services\winebth]""",
            ),
        )
        // 4 is SERVICE_DISABLED. Any other value and patches/wine/0031 lets the
        // driver load exactly as before, which is what this pair exists to stop.
        assertTrue(rendered.contains(""""Start"=dword:00000004"""))
        // Disabled, not deleted: wineboot recreates the service, so a removed key
        // would come back and bring the load failure with it.
        assertFalse(rendered.contains("[-HKEY_LOCAL_MACHINE"))
    }

    @Test
    fun `the unix root is deleted from the desktop, in both hive views`() {
        // Z: and the `/` node are the same mistake shown twice: the unix root
        // here is Android, and what it hands a guest program is this app's own
        // private storage. `[-key]` and not an empty key — a key that still
        // exists is a shell item that still appears.
        val rendered = PrefixRegistry.renderSeed()
        assertTrue(
            rendered.contains(
                """[-HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Explorer""" +
                    """\Desktop\Namespace\{9D20AAE8-0625-44B0-9CA7-71889C2254D9}]""",
            ),
        )
        // The 32-bit view too, or a WoW64 program still sees the node.
        assertTrue(rendered.contains("""Software\Wow6432Node\Microsoft\Windows"""))
        // And the drive is never declared, whatever is on disk.
        assertTrue(!PrefixRegistry.renderSeed(listOf('c', 'd', 'z')).contains(""""z:"="hd""""))
        // The Drives key is deleted before it is written, so a letter the user
        // unmaps does not keep its type entry for ever.
        val drives = """HKEY_LOCAL_MACHINE\Software\Wine\Drives"""
        assertTrue(rendered.indexOf("[-$drives]") < rendered.indexOf("[$drives]"))
    }

    @Test
    fun `a removal renders as the delete form and carries no values`() {
        val rendered = PrefixRegistry.render(
            listOf(RegistryKey("""HKEY_CURRENT_USER\Software\Gone""", remove = true)),
        )
        assertTrue(rendered.contains("""[-HKEY_CURRENT_USER\Software\Gone]"""))
        assertEquals(1, rendered.lines().count { it.startsWith("[") })
    }

    @Test
    fun `the stamp changes when the seed does, so an old prefix re-applies it`() {
        val withoutE = PrefixRegistry.renderSeed(listOf('c', 'd'))
        val withE = PrefixRegistry.renderSeed(listOf('c', 'd', 'e'))
        // The defect this replaces: both of these are seed 16, so an integer
        // marker would call the second one already applied and the mapped drive
        // would never be declared.
        assertTrue(PrefixRegistry.stampOf(withoutE) != PrefixRegistry.stampOf(withE))
        assertTrue(withE.contains(""""e:"="hd""""))
        assertTrue(!withoutE.contains(""""e:"="hd""""))
    }

    @Test
    fun `the stamp a rendered seed carries is the one it will write`() {
        val text = PrefixRegistry.renderSeed()
        val stamp = PrefixRegistry.stampOf(text)
        assertEquals("VesselSeed${PrefixRegistry.SEED_VERSION}", stamp?.substringBefore('-'))
        // Rendering twice is the same text — no timestamp, no iteration order
        // that a map could reshuffle — or every launch would re-run `regedit`.
        assertEquals(text, PrefixRegistry.renderSeed())
    }

    @Test
    fun `every drive a prefix has is declared, whoever mapped it`() {
        val drives = PrefixRegistry.driveTypes(listOf('d', 'g'))
        assertEquals(
            listOf("c:", "d:", "g:"),
            drives.values.map { it.name },
        )
        // `c:` is added whether or not it was passed: the seed is rendered
        // before `wineboot` creates it on a first provision. `z:` is not, and
        // must not be — seed 17 removes that drive rather than declaring it.
        assertTrue(drives.values.all { it.data == "hd" })
        assertTrue(drives.values.none { it.name == "z:" })
    }

    @Test
    fun `the virtual desktop is named the same as the one the session starts`() {
        // The two have to agree or the second process makes its own desktop
        // instead of joining the session's, which is a full-size window over
        // the one already there.
        val explorer = PrefixRegistry.virtualDesktop
            .single { it.path.endsWith("""Wine\Explorer""") }
        assertEquals(WINE_DESKTOP, explorer.values.single { it.name == "Desktop" }.data)

        val desktops = PrefixRegistry.virtualDesktop
            .single { it.path.endsWith("Desktops") }
        assertTrue(
            "the named desktop has a size",
            desktops.values.single().name == WINE_DESKTOP &&
                desktops.values.single().data.matches(Regex("""\d+x\d+""")),
        )
    }

    @Test
    fun `PATH is the Windows entries and nothing this project invented`() {
        // `C:\Program Files\Vessel Tools` was on here for a component that was
        // never built, so the value named a directory that has never existed.
        // A toolchain a user installs brings its own PATH entry; this seed has
        // no business claiming one in advance.
        val path = PrefixRegistry.toolsPath.values.single { it.name == "PATH" }.data
        assertTrue("system32 is on it", path.contains("""C:\windows\system32"""))
        assertTrue("wbem is on it", path.contains("""C:\windows\system32\wbem"""))
        assertTrue("nothing of ours is", !path.contains("Vessel"))
    }

    @Test
    fun `PATH and MSYSTEM name the ARM64 Git prefix, not the x64 one`() {
        // The one value in this seed that is wrong in a way nothing reports.
        // Git for Windows' ARM64 build puts its helpers under `clangarm64` and
        // its x86-64 build under `mingw64`; Tools 1.2.0 is the ARM64 build, so a
        // PATH or an MSYSTEM naming `mingw64` resolves `git.exe` through `cmd\`
        // and then fails on the first command that shells out to a helper. The
        // two have to agree with each other as well as with the payload —
        // `/etc/profile` derives MSYSTEM_PREFIX from the name.
        //
        // This assertion has been inverted once already: it read `mingw64` for
        // seeds 24-26, when the payload was x86-64. It is written as an equality
        // against the payload rather than against an architecture in the
        // abstract, so whichever way it goes, `build/tools.sh` asserting
        // `clangarm64/bin` exists and this test are the two ends of the same
        // contract.
        val path = PrefixRegistry.toolsPath.values.single { it.name == "PATH" }.data
        val msystem = PrefixRegistry.toolsPath.values.single { it.name == "MSYSTEM" }.data
        assertTrue(
            "clangarm64 is on PATH",
            path.contains("""${PrefixRegistry.GIT_DIR}\clangarm64\bin"""),
        )
        assertTrue("mingw64 is not", !path.contains("mingw64"))
        assertEquals("CLANGARM64", msystem)
    }

    @Test
    fun `every directory the Tools payload delivers is on PATH`() {
        // The seed's own rule is that it never names a directory the build does
        // not deliver, and the inverse is what this checks: build/tools.sh lays
        // out Git, Python, Node, PowerShell and the JDK,
        // SessionRuntime.TOOLS_LAYOUT copies all five into the prefix, and a
        // program that is installed but not on PATH is one nobody can run
        // without typing a path. `Scripts` is included because that is where pip
        // puts console scripts.
        val path = PrefixRegistry.toolsPath.values.single { it.name == "PATH" }.data.split(";")
        assertTrue("git", path.contains("""${PrefixRegistry.GIT_DIR}\cmd"""))
        assertTrue("msys2 userland", path.contains("""${PrefixRegistry.GIT_DIR}\usr\bin"""))
        assertTrue("python", path.contains(PrefixRegistry.PYTHON_DIR))
        assertTrue("pip's scripts", path.contains("""${PrefixRegistry.PYTHON_DIR}\Scripts"""))
        assertTrue("node", path.contains(PrefixRegistry.NODE_DIR))
        assertTrue("pwsh", path.contains(PrefixRegistry.PWSH_DIR))
        // `\bin` and not the root, which is the one tree where those differ: a
        // JDK root has no launchers in it, so a PATH naming it resolves nothing
        // while looking entirely correct.
        assertTrue("java", path.contains("""${PrefixRegistry.JAVA_DIR}\bin"""))
        assertTrue("not the JDK root", !path.contains(PrefixRegistry.JAVA_DIR))
    }

    @Test
    fun `JAVA_HOME names the JDK root, because that is what build tools read`() {
        // PATH is enough to type `java` and not enough to build: Gradle, Maven
        // and Ant read JAVA_HOME first, and report a JDK that is present but
        // unlabelled as one that is missing.
        val javaHome = PrefixRegistry.toolsPath.values.single { it.name == "JAVA_HOME" }.data
        assertEquals(PrefixRegistry.JAVA_DIR, javaHome)
        // The root, never `\bin` — every consumer appends `\bin` itself, so the
        // wrong value here produces `…\bin\bin\java.exe` and no useful error.
        assertTrue("not \\bin", !javaHome.endsWith("""\bin"""))
    }

    @Test
    fun `window metrics are negative twips, which is the only form Wine reads`() {
        // A positive number in these values is a point size, not a length, so
        // getting the sign wrong does not fail — it silently asks for a caption
        // forty points tall. Fifteen twips to the pixel at 96 dpi.
        val values = PrefixRegistry.windowMetrics.values.associate { it.name to it.data }
        assertEquals("-600", values["CaptionHeight"])
        // The buttons are narrower than the caption is tall, so their glyphs
        // stay in proportion. See the note on CaptionWidth.
        assertEquals("-480", values["CaptionWidth"])
        assertEquals("-360", values["ScrollWidth"])
        assertEquals("-120", values["BorderWidth"])
        assertEquals("0", values["PaddedBorderWidth"])
        assertTrue(
            "every metric is zero or a negative integer",
            PrefixRegistry.windowMetrics.values.all { it.data.toInt() <= 0 },
        )
    }

    @Test
    fun `the sizing border is wide enough to hit with a finger`() {
        // Windows' default is 1 px of border and 0 of padding. The pair is what
        // Wine adds up into the resize region, and a 1 px region is the reason
        // the windows in a virtual desktop cannot be resized by touch at all.
        val values = PrefixRegistry.windowMetrics.values.associate { it.name to it.data }
        val border = -values.getValue("BorderWidth").toInt() / 15
        val padded = -values.getValue("PaddedBorderWidth").toInt() / 15
        assertTrue("grab region is at least 8 px", border + padded >= 8)
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




    // — reading the hive back -----------------------------------------------------

    @Test
    fun `the required hive values are the two emulator DLLs and nothing else`() {
        // Derived from the keys rather than spelled out, so renaming an emulator
        // cannot leave the checker looking for the old name and reporting success
        // against a prefix that no longer works.
        assertEquals(
            listOf("libarm64ecfex.dll", "libwow64fex.dll"),
            PrefixRegistry.requiredHiveValues,
        )
    }

    @Test
    fun `a hive with both emulator keys is missing nothing`() {
        // The shape Wine actually writes: roots stripped, separators doubled, a
        // timestamp after the key. Not the `.reg` format the seed is rendered in,
        // which is why the check is a substring search and not a parse.
        val hive = """
            [Software\Microsoft\Wow64\amd64] 1786175298
            #time=1dd270a46d24a8c
            @="libarm64ecfex.dll"

            [Software\Microsoft\Wow64\x86] 1786175298
            @="libwow64fex.dll"
        """.trimIndent()
        assertEquals(emptyList<String>(), PrefixRegistry.missingFromHive(hive))
    }

    @Test
    fun `wine's own defaults count as missing, which is the case that was shipping`() {
        // Measured on the device: `regedit` was never reached, so `wine.inf`'s
        // NOCLOBBER defaults were what the hive held. Both of these are DLLs this
        // project does not ship, and the symptom is a c0000135 much later.
        val hive = """
            [Software\Microsoft\Wow64\amd64] 1786175298
            @="xtajit64.dll"

            [Software\Microsoft\Wow64\x86] 1786175298
            @="xtajit.dll"
        """.trimIndent()
        assertEquals(
            listOf("libarm64ecfex.dll", "libwow64fex.dll"),
            PrefixRegistry.missingFromHive(hive),
        )
    }

    @Test
    fun `one key applied and the other not is reported as the one that is not`() {
        val hive = """@="libarm64ecfex.dll"
@="xtajit.dll""""
        assertEquals(listOf("libwow64fex.dll"), PrefixRegistry.missingFromHive(hive))
    }

    @Test
    fun `an empty hive is missing everything rather than passing vacuously`() {
        assertEquals(PrefixRegistry.requiredHiveValues, PrefixRegistry.missingFromHive(""))
    }
}
