package app.vessel.core

import java.io.File

/**
 * The three value types this seed writes.
 *
 * Not a general facility — it is three because three readers demand different
 * things. [SZ] is the default and is load-bearing for the emulator keys:
 * `load_arm64ec_module` and `get_cpu_dll_name` both test
 * `info->Type == REG_SZ` and ignore the value otherwise, so `REG_EXPAND_SZ`
 * would leave the key present, readable, and silently unused. [DWORD] exists
 * because `ShouldAppsUseDarkMode` reads its value with `RRF_RT_REG_DWORD`,
 * which rejects a string outright. [MULTI_SZ] exists because
 * `load_system_links` walks one value as a run of NUL-terminated strings, and a
 * font-link chain is a list.
 */
enum class RegistryKind {
    SZ,
    DWORD,

    /**
     * `REG_MULTI_SZ` — a list of strings in one value, rendered as `hex(7):`.
     *
     * **Added for [PrefixRegistry.fontLink], which is the only thing that wants
     * a list**, and worth the new kind rather than a workaround because Wine's
     * `load_system_links` reads a font's whole fallback chain out of a single
     * value: the value *name* is the base family and the data is a run of
     * NUL-terminated `filename[,familyname]` entries, walked until a zero-length
     * entry or the end of the data (`dlls/win32u/font.c:2050-2079`). There is no
     * one-value-per-entry form to fall back on.
     *
     * **`hex(7)` is verified against the parser this seed actually reaches, not
     * assumed.** `SessionRuntime.applyRegistry` runs `regedit <file>`, so the
     * parser is `programs/regedit/regproc.c`. Its `parse_data_type`
     * (regproc.c:302-345) holds `{ L"hex(", 4, -1, REG_BINARY }` and, for the
     * `-1` type, reads the number generically with `wcstoul(*line, &end, 16)`
     * (regproc.c:335) and assigns it straight to `parser->data_type`
     * (regproc.c:339) — no whitelist, so type 7 is accepted like any other, and
     * `RegSetValueExW` is then called with it (regproc.c:909). Upstream's own
     * tests cover `hex(7)` specifically (`programs/regedit/tests/regedit.c:351`).
     *
     * **The bytes are single-byte ASCII, NOT UTF-16LE, and getting that backwards
     * is the whole trap.** `ContainerProvisioner` writes the seed as UTF-8 with no
     * BOM; `regproc.c:1044` decides `is_unicode` purely on a UTF-16LE BOM, so this
     * file takes the `get_lineA` path. On that path `prepare_hex_string_data`
     * (regproc.c:466-497) treats the hex bytes as *narrow* text and widens them
     * with `MultiByteToWideChar(CP_ACP, …)` before the value is written. Emitting
     * UTF-16LE bytes here would therefore be widened a second time: `4e,00,6f,00`
     * would import as the strings "N", "o", … and `load_system_links` would walk a
     * chain of one-character filenames, resolve none of them, and log a `TRACE`
     * line per entry. The value would exist, have the right type, and link nothing.
     *
     * See [RegistryValue.multiSz] for the terminator rules and
     * [PrefixRegistry.block] for the rendering.
     */
    MULTI_SZ,

    /**
     * `"name"=-`, which removes one value and leaves the key.
     *
     * **A `.reg` merge adds and replaces; it never removes**, and that gap has
     * bitten this project once already at key level — see [RegistryKey.remove].
     * This is the same problem one level down: taking a value out of [seed] stops
     * it being written to a *new* prefix and does nothing at all to the thousands
     * already carrying it.
     *
     * Added for `opengl32`, which was seeded `native,builtin` and had to stop
     * being — see `SessionEnvironment.WGL_DLL`. Without a delete form, every
     * container created before that change would go on loading Zink into every
     * process for ever, and the fix would only ever reach a fresh install.
     */
    DELETE,
}

/**
 * The separator [RegistryValue.multiSz] joins entries with, and the one
 * [PrefixRegistry.multiSzHex] splits them back on.
 *
 * A NUL rather than a space or a comma: [RegistryValue.multiSz] accepts any
 * printable ASCII in an entry, which includes both, and a comma is meaningful
 * *inside* an entry - it separates a filename from an optional family name
 * (`dlls/win32u/font.c:2065-2071`). A NUL cannot appear in an entry that passed
 * that check, so it is the one unambiguous separator.
 */
internal const val MULTI_SZ_SEPARATOR: String = "\u0000"

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

        /** `"name"=-` — remove this one value, keep the key. See [RegistryKind.DELETE]. */
        fun removed(name: String): RegistryValue =
            RegistryValue(name, "", RegistryKind.DELETE)

        /**
         * A `REG_MULTI_SZ` from [entries], stored NUL-joined and rendered as
         * `hex(7):`. See [RegistryKind.MULTI_SZ] for why the kind exists.
         *
         * [data] holds the entries joined by NUL and with **no** trailing
         * terminators; [PrefixRegistry.block] adds both — a NUL after every entry
         * and one more to end the sequence. Keeping the terminators out of the
         * stored form means there is exactly one place that can get them wrong,
         * and `PrefixRegistryTest` pins the bytes it produces. An off-by-one in a
         * UTF-16 byte run is invisible by eye and shows up only as a font that
         * quietly does not link.
         *
         * **ASCII-only, and it throws rather than encoding.** The rendered bytes
         * are narrow characters that Wine widens with `MultiByteToWideChar(CP_ACP,
         * …)`, and the prefix's ACP is not something this code knows — a non-ASCII
         * filename would be written in one encoding and read in another, which is
         * the silent-wrong-value failure this whole class is careful about.
         * Everything that wants this is a font filename, so the restriction costs
         * nothing and the alternative is a guess.
         *
         * An empty entry is refused for a sharper reason: a zero-length string
         * ends `load_system_links`' walk (`font.c:2056`), so one blank entry in the
         * middle silently discards every entry after it.
         */
        fun multiSz(name: String, entries: List<String>): RegistryValue {
            require(entries.isNotEmpty()) { "$name: a REG_MULTI_SZ needs at least one entry" }
            entries.forEach { entry ->
                require(entry.isNotEmpty()) {
                    "$name: an empty entry ends Wine's SystemLink walk and would " +
                        "discard every entry after it"
                }
                require(entry.all { it.code in 0x20..0x7E }) {
                    "$name: '$entry' is not printable ASCII; the seed is read back " +
                        "through MultiByteToWideChar(CP_ACP) and this code does not " +
                        "know the prefix's ACP"
                }
            }
            // NUL-joined and not space- or comma-joined. Printable ASCII includes
            // both, so either would be ambiguous with an entry containing one —
            // and a comma is meaningful inside an entry, where it separates the
            // filename from an optional family name (`font.c:2065-2071`). A NUL
            // cannot appear in an entry the check above accepted.
            val joined = entries.joinToString(MULTI_SZ_SEPARATOR)
            return RegistryValue(name, joined, RegistryKind.MULTI_SZ)
        }
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
     * 25 added the two programs Tools 1.1.0 puts in the payload — [PWSH_DIR] and
     * [JAVA_DIR]`\bin` on the PATH, plus `JAVA_HOME` as a value of its own in the
     * same Environment key, since a JDK that PATH can reach and Gradle cannot
     * find is a JDK nobody can build with. PowerShell is the one that has a
     * reason beyond completeness: it is what gives Claude Code's shell tool
     * something to run, Git Bash having been measured deadlocking in MSYS2's
     * `fork()` emulation (see `TerminalProfile`). This bump is the half a rebuilt
     * component cannot deliver — a prefix keeps whatever seed provisioned it, so
     * without it every existing container would receive two new programs and a
     * PATH that names neither, and `pwsh` would not resolve;
     * 24 rewrote [toolsPath] for a Tools component that is no longer Git alone
     * and no longer ARM64. Python and Node are in the same payload — they have
     * to be, a container referencing exactly one component per type — so the
     * seed gains `C:\Program Files\Python`, its `Scripts` subdirectory and
     * `C:\Program Files\Node`; and Git's third entry moves from `clangarm64` to
     * `mingw64` with `MSYSTEM` following it from `CLANGARM64` to `MINGW64`,
     * because the x86-64 build of Git for Windows names its prefix differently
     * from the ARM64 one. This is the half of that change a rebuild cannot
     * deliver on its own: an existing prefix keeps the seed it was provisioned
     * with, so without the bump every device that already has a container would
     * install three new programs and leave `PATH` pointing at a `clangarm64`
     * directory the new payload does not contain;
     * 22 removes the `opengl32` DLL override with `.reg`'s value-delete form,
     * because dropping it from the list would only ever reach a new prefix and
     * every existing one would keep loading Zink into every process — see
     * `SessionEnvironment.WGL_DLL` for the measurement that removed it;
     * 21 added `MSYSTEM` beside the PATH in [toolsPath], because a login shell
     * started as `bash.exe` rather than through `git-bash.exe` has nothing else
     * to tell it which MSYS2 prefix it is in;
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
     * 26 made the console ground black. It had been [GuestPalette.BG], the
     * window ground, and the console had nevertheless been black for months --
     * conhost fills its buffer with the built-in 0x000F before any config is
     * read and never repainted those cells, so this key was inert. Once
     * `patches/wine/0052` made the attribute reach existing cells the theme took
     * effect for the first time and the console became a navy pane blending into
     * the desktop behind it. Black is what it always looked like, now asked for
     * deliberately: a console is a box on the themed desktop, not a pane of it.
     * 27 reverted Git's prefix to ARM64's `clangarm64` — [toolsPath]'s third PATH
     * entry and `MSYSTEM`, which move together or not at all. Tools 1.2.0 is the
     * ARM64 payload again: the wheel-ecosystem claim that justified the all-x64
     * 1.1.0 was counted against PyPI on 2026-08-17 and is false (`win_arm64` is at
     * parity, `cryptography` alone missing), and PowerShell x86-64 crashed on the
     * device with two unhandled `c0000005`s inside FEX's JIT buffer. `native/pins.env`
     * has the numbers and the addresses. This bump is the half a rebuilt component
     * cannot deliver: a prefix keeps the seed it was provisioned with, so without
     * it every existing container would get an ARM64 payload with `PATH` and
     * `MSYSTEM` still naming a `mingw64` prefix the payload does not contain —
     * `git` would resolve through `cmd\` and every helper behind it would not,
     * which reads as a working install until the first command that shells out.
     * 28 put [CLAUDE_BIN] on the PATH. Claude Code's installer drops
     * `claude.exe` into the guest profile's `.localin` and then prints a note
     * telling the user to add that directory through System Properties; seeding
     * it means the thing the Tools component exists to serve is reachable the
     * moment it installs. Verified on the device -- 2.1.233, ARM64, at exactly
     * that path -- and the bump is what carries it to prefixes that already
     * exist, since a `.reg` merge replaces the PATH value whole.
     * 29 named a console font. [consoleColours] gains `FaceName` = `Unifont`,
     * which is the half of the box-drawing fix that is not a payload: Claude
     * Code's TUI drew every horizontal rule as `□□□□□`, and the measurement was
     * that `prefix/drive_c/windows/Fonts/` held zero files and this key had no
     * `FaceName` at all, so conhost enumerated a face out of the Wine component's
     * `.fon` bitmaps and Android's two mono TTFs, none of which has a U+2500
     * block. Tools 1.3.0 ships GNU Unifont, whose family name was read out of the
     * OTF's `name` table rather than guessed, and this points conhost at it.
     * `FontSize` and `FontFamily` are deliberately left alone -- see
     * [consoleColours] for why, and for what stays unverified. The bump is what
     * carries it to prefixes that already exist: a `.reg` merge replaces values,
     * so without it the font would arrive and nothing would select it.
     * 30 rewrote [consoleColours] twice over -- the face and the palette -- and it
     * is one seed because it is one screen.
     *
     * **The face.** `FaceName` moves from `Unifont` to `Cascadia Mono`, which Tools
     * 1.4.0 ships in place of Unifont. Seed 29's font worked: the box-drawing
     * glyphs rendered on the device. It is replaced anyway because it is
     * bitmap-derived and illegible at any size, and the cost is stated rather than
     * discovered later -- one face with no font linking means no CJK in a console
     * at all. (That cost was real and its stated cause was not: seed 31 retires the
     * "no font linking" half of it, which was never true. Left standing here because
     * this entry is what seed 30 believed, and 31's entry is where it is corrected.)
     * The family name was read out of the TTF's `name` table and `build/tools.sh`
     * asserts it against `TOOLS_CASCADIA_FAMILY`.
     *
     * **The palette**, which is a measured colour bug and not a retheme.
     * `patches/wine/0052` was taught to quantise `38;5;n` and `38;2;r;g;b` to the
     * sixteen console colours; the device's `vt-trace.log` shows Claude Code
     * emitting 253 of the first form and 44 of the second, and its most common
     * colour, `38;5;238`, is RGB 68,68,68 and quantises to index 8. Seed 26 had
     * made index 8 the *background* so the console would come up black, so the
     * commonest colour in a session would have painted text the colour of the
     * ground behind it. `ScreenColors` and `PopupColors` therefore move to `0x0F`
     * -- which is also exactly `create_screen_buffer`'s own `0x000F` fill, so the
     * two-backgrounds mismatch Wine revision 39 repaints no longer arises at the
     * source -- and all sixteen `ColorTable` entries are written as Campbell,
     * Windows Terminal's default and the scheme Cascadia Mono was designed
     * alongside. Two entries retuned out of a scheme is not a scheme, and
     * quantisation is what makes that matter.
     *
     * The bump carries both to prefixes that already exist: a `.reg` merge
     * replaces values, so without it a container would keep seed 29's `FaceName`
     * naming a font the new payload no longer contains, and keep a background on
     * palette slot 8.
     * 31 added [fontLink], which retires the premise seeds 29 and 30 were both
     * built on. Each of them swapped one console face for another and wrote the
     * other's property down as the price -- 29 had complete coverage in an
     * illegible bitmap face, 30 has a legible face missing 12 glyphs Claude Code's
     * TUI draws on every tool-call line -- and both cited the same sentence:
     * "conhost resolves one face with no font linking". Its second half is false.
     * Per-glyph fallback is GDI's, not conhost's, and Wine implements it; the whole
     * path is verified on [fontLink], including the hop that reaches the text
     * conhost actually draws. So `FaceName` stays `Cascadia Mono` and this key names
     * what it falls back to. Measured, so the trade is not a guess this time:
     * Cascadia maps 2,426 codepoints (1,863 of them BMP) against Unifont's 58,910,
     * it is missing exactly 12 glyphs the TUI uses -- `U+23FA` and `U+23BF` on every
     * tool-call line, the five spinners `U+273B U+273D U+2733 U+2734 U+2736`, the
     * marks `U+2714 U+2717 U+2718`, `U+26A0` and `U+21B5` -- and the union of the
     * chain covers all 12 and 116,125 codepoints. **What it does not buy is
     * geometry**: CJK and emoji stop being tofu and become mis-positioned instead,
     * because conhost has no double-width cells at all, and emoji are monochrome.
     * [fontLink] states both limits rather than leaving them to be found. The bump
     * is what carries the new key to prefixes that already exist -- a `.reg` merge
     * adds and replaces, so without it the fallback would only ever reach a
     * container created after this ships.
     */
    const val SEED_VERSION: Int = 33

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
        values = D3D_DLL_OVERRIDES.map { RegistryValue(it, DLL_OVERRIDE_MODE) } +
            // **Removed, not omitted.** `opengl32` was seeded here and must stop
            // being — `SessionEnvironment.WGL_DLL` has the measurement. Dropping
            // it from the list would only affect prefixes created afterwards; a
            // `.reg` merge does not remove what it no longer mentions, so every
            // existing container would keep loading Zink into every process.
            RegistryValue.removed(WGL_DLL),
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
     * The console font's family name, as `HKCU\Console\FaceName` wants it.
     *
     * Read out of `CascadiaMono-Regular.ttf`'s `name` table — Windows-platform
     * record, platform 3 / encoding 1 / name ID 1 — and not assumed.
     * `build/tools.sh` asserts the same string against `TOOLS_CASCADIA_FAMILY` in
     * `native/pins.env` at build time, because a wrong face name here is silent:
     * `apply_config` reaches `CreateFontIndirectW`, which returns *some* font
     * whatever happens, so a name that does not resolve gives a nearest match with
     * no error and no log line.
     *
     * **Tools 1.4.0 replaced GNU Unifont with this and 1.5.0 stopped that being a
     * trade.** Unifont was chosen for coverage and delivered it — the box-drawing
     * glyphs rendered on the device — and it is bitmap-derived, so it is illegible
     * at any size. 1.4.0's swap cost coverage: counted out of the fonts' own cmaps,
     * Cascadia Mono maps 2,426 codepoints (1,863 BMP) against Unifont's 58,910, and
     * it is missing 12 glyphs Claude Code's TUI draws. 1.5.0 keeps this face and
     * puts Unifont behind it instead of in front of it — see [fontLink], which is
     * where the mechanism, the ordered chain and the measured union coverage are.
     * `native/pins.env` lists what is probed present and absent.
     */
    private const val CONSOLE_FACE = "Cascadia Mono"

    /**
     * Campbell, the sixteen console colours, as `0xFFRRGGBB`.
     *
     * Windows Terminal's default scheme, and chosen rather than invented for two
     * reasons. It is the palette Cascadia Mono was designed alongside, so the pair
     * is what Microsoft actually ships and looks at; and a console palette is not
     * sixteen independent choices — `patches/wine/0052`'s parser quantises
     * `38;5;n` and `38;2;r;g;b` to the nearest of these sixteen, so the *set* has to
     * be coherent or "nearest" starts landing in the wrong place. Half-retuning it
     * out of Nocturne would break exactly that.
     *
     * Index 0 is [GuestPalette.CONSOLE_BG] rather than a literal, because it is the
     * console's ground and that constant is what names it. Its value is Campbell's
     * own `0C0C0C`; see there for why it moved off pure black.
     *
     * The registry wants `0x00BBGGRR`, which is why every one of these goes through
     * [bgr] on the way out. Sixteen hand-swapped literals is precisely the change
     * that gets a byte order slip nobody notices — a plausible wrong colour rather
     * than an error — so nothing here is pre-swapped and `PrefixRegistryTest` pins
     * the rendered values.
     */
    private val CAMPBELL: List<Int> = listOf(
        GuestPalette.CONSOLE_BG,  // 00 black          0C0C0C
        0xFFC50F1F.toInt(),       // 01 red            C50F1F
        0xFF13A10E.toInt(),       // 02 green          13A10E
        0xFFC19C00.toInt(),       // 03 yellow         C19C00
        0xFF0037DA.toInt(),       // 04 blue           0037DA
        0xFF881798.toInt(),       // 05 magenta        881798
        0xFF3A96DD.toInt(),       // 06 cyan           3A96DD
        0xFFCCCCCC.toInt(),       // 07 white          CCCCCC
        0xFF767676.toInt(),       // 08 bright black   767676
        0xFFE74856.toInt(),       // 09 bright red     E74856
        0xFF16C60C.toInt(),       // 10 bright green   16C60C
        0xFFF9F1A5.toInt(),       // 11 bright yellow  F9F1A5
        0xFF3B78FF.toInt(),       // 12 bright blue    3B78FF
        0xFFB4009E.toInt(),       // 13 bright magenta B4009E
        0xFF61D6D6.toInt(),       // 14 bright cyan    61D6D6
        0xFFF2F2F2.toInt(),       // 15 bright white   F2F2F2
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
     * index and the low nibble the foreground. `0x07` — grey on black — is
     * conhost's default and is what originally shipped. Seed 26 made it `0x87`:
     * background index **8**, so that slot 8 could be set to black and the console
     * would come up black rather than as a navy pane of the desktop.
     *
     * **`0x0F` now, and moving the background off slot 8 is half of a measured
     * colour bug.** `patches/wine/0052` was taught to quantise `38;5;n` and
     * `38;2;r;g;b` down to these sixteen; before that both forms were swallowed
     * unrendered, which was fine for Python and Node (they use 30-37 and 90-97) and
     * useless for a TUI. The device's `vt-trace.log` says what Claude Code actually
     * emits: **253 `38;2;r;g;b` and 44 `38;5;n`**, and the single most common colour
     * in a session is `38;5;238` — grey-ramp value `8 + 10*(238-232)` = 68, so RGB
     * 68,68,68, whose nearest of the sixteen is index **8**. With slot 8 doing
     * double duty as the background, the most frequent colour Claude Code asks for
     * paints text the colour of what is behind it. So the parser fix alone would not
     * have fixed what was reported; both halves ship together or neither is worth
     * shipping. Slot 8 is now Campbell's `767676`, a real dark grey.
     *
     * **`0x0F` specifically, and not some other free pair, because it is exactly
     * what conhost fills a fresh buffer with.** `create_screen_buffer` writes
     * `0x000F` into every cell before any config is read. `native/pins.env`'s Wine
     * revision 39 exists because that fill and this value disagreed — "the buffer
     * therefore held two backgrounds", so the moment anything erased, the region
     * below the cursor came out a different colour from the text above it, and 39
     * repaints the cells still carrying the old attribute. Agreeing with the fill
     * removes the disagreement at its source; 39's repaint becomes a safety net for
     * whatever else changes an attribute rather than the thing holding the screen
     * together.
     *
     * **All sixteen entries are written, where seed 26 wrote two.** See [CAMPBELL]
     * for why the whole set and not a retune of the two this happens to need:
     * quantisation picks the nearest of sixteen, so the sixteen have to be a
     * coherent scheme rather than a scheme with two cells redecorated.
     *
     * **`FaceName` is here too, and it is not about colour — it is what stops the
     * console drawing boxes.** Measured on the device: Claude Code's TUI rendered
     * every horizontal rule as `□□□□□`, `prefix/drive_c/windows/Fonts/` held zero
     * files, and this key had no `FaceName` value at all. With `FaceName` empty
     * conhost takes `init_window`'s `!config.face_name[0]` branch
     * (`programs/conhost/window.c:2481-2483`) and picks a face by enumeration —
     * out of the Wine component's `.fon` bitmap faces, its non-monospace TTFs,
     * and the `CutiveMono.ttf` / `DroidSansMono.ttf` Android contributes. None of
     * those has a U+2500 block, so a box was the right thing for the renderer to
     * draw. Tools 1.4.0 puts Cascadia Mono in `windows\Fonts` (see
     * `SessionRuntime.installToolFonts`) and [CONSOLE_FACE] names it.
     *
     * **`FontSize` is deliberately still not set, and the reasoning behind that has
     * changed.** Seed 29 could say the default cell was the *right* answer: 16x8
     * (`window.c:255-257`) was exactly Unifont's native 16x16-on-a-16x8-cell glyph
     * box. Cascadia Mono is a scalable outline with no native pixel grid, so 16x8 is
     * now simply conhost's default rather than a match to anything. It is left alone
     * because a value nobody has looked at on a screen is a guess, and the design
     * proportions do agree: `unitsPerEm` 2048, advance width 1200, ascender 1900 and
     * descender -480 give 1200/2380 = 0.504 against the cell's 8/16 = 0.5, all read
     * off the TTF. **If Cascadia looks wrong at that cell size, `FontSize` is the
     * knob** — note the packing, HIWORD is the height and LOWORD the width
     * (`window.c:180-187`).
     *
     * **`FontFamily` is deliberately not set either.** conhost already defaults
     * it to `FIXED_PITCH | FF_DONTCARE` (`window.c:255`) and needs nothing but
     * `FaceName` to pick a face; a speculative pitch or family value can only
     * make matching fail. One thing the font swap did retire here: Unifont reported
     * `post.isFixedPitch` 0, and Cascadia Mono reports 1, so the standing worry
     * that win32u would build a variable-pitch TEXTMETRIC from the console face is
     * gone. (Its PANOSE is all zeros, so bProportion is 0 (Any) rather than 9
     * (Monospaced) — nothing on the `FaceName` path consults it.)
     *
     * **What is unverified, stated as such: nothing here has run on a device.**
     * Reading the code says a named family is resolved before pitch is ever
     * consulted — with `FaceName` set, `set_first_font` and therefore
     * `validate_font`'s 0x3f weight mask (`window.c:821-847`) are never on the path,
     * and win32u's `find_matching_face` looks the family up in `family_name_tree`
     * first (`dlls/win32u/font.c:2288-2303`). But `apply_config` reaches
     * `CreateFontIndirectW`, which returns *some* font whatever happens, so if that
     * reading is wrong the failure is a nearest match to a different face with no
     * error and no log line. Whether the glyphs appear, and whether this face is
     * legible at this cell size, cannot be settled by reading.
     *
     * **The "one face, no fallback" limitation this used to carry is gone, and it
     * was never true.** Seed 30 said here that emoji, CJK, hiragana, katakana,
     * hangul, Devanagari and Thai could not render because conhost uses a single
     * face and does no font linking. conhost does use a single face; the conclusion
     * did not follow, because per-glyph fallback happens in GDI and Wine implements
     * it. Seed 31 uses it: [fontLink] names what this face falls back to, so the
     * glyphs Cascadia lacks now resolve behind it and nothing changes for the ones
     * it has. What remains is not coverage but geometry and colour — CJK is
     * mis-positioned because conhost has no double-width cells, and emoji are
     * monochrome — and [fontLink] states both.
     */
    val consoleColours: RegistryKey = RegistryKey(
        path = """HKEY_CURRENT_USER\Console""",
        values = listOf(
            RegistryValue("FaceName", CONSOLE_FACE),
            // Background index 0, foreground index 15 — see above for why this
            // pair and not another: `create_screen_buffer`'s own fill is 0x000F.
            RegistryValue.dword("ScreenColors", 0x0F),
            RegistryValue.dword("PopupColors", 0x0F),
        ) + CAMPBELL.mapIndexed { i, argb ->
            // `ColorTable%02d`, which is the format conhost reads them back with
            // (`window.c:153`) — `ColorTable8` would be silently ignored.
            RegistryValue.dword("ColorTable%02d".format(i), bgr(argb))
        },
    )

    /**
     * `\Registry\Machine\...` as a `.reg` file spells it. See [fontLink].
     *
     * The `Windows NT` component carries a space, and that is part of the real key
     * name rather than a separator: a `.reg` key path inside `[...]` is taken
     * literally and needs no escaping, which is why this is one raw string and not
     * assembled from parts.
     */
    private const val FONT_LINK_KEY =
        """HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink"""

    /**
     * The fallback chain for [CONSOLE_FACE], best typography first.
     *
     * **Filenames, not family names, because that is what Wine matches on.**
     * `find_face_from_filename` compares the basename of a registered font's path
     * (`dlls/win32u/font.c:879-893`); the `,familyname` suffix the format allows is
     * optional and omitted here. **A name that does not resolve is dropped with a
     * `TRACE` line and nothing else (`font.c:2076`)** — no error, no warning, no
     * log line at any level a device run would show — so a wrong filename costs
     * exactly the glyphs it was meant to supply and reports nothing. That is why
     * `native/pins.env` spells the two Unifont names as literals
     * (`TOOLS_FONTLINK_UNIFONT_FILE`, `TOOLS_FONTLINK_UNIFONT_UPPER_FILE`) and
     * `build/tools.sh` asserts them against the files it actually staged: the
     * version is inside the filename, so a Unifont bump with no edit here would
     * ship a correct payload, a green build and no fallback.
     *
     * **The order is load-bearing.** `get_glyph_index_linked` takes the *first*
     * child that has the glyph (`font.c:3765-3772`), so this runs from the face
     * that draws a glyph best to the face that merely has it:
     *
     *  1. `NotoSansSymbols-Regular-Subsetted.ttf` — real typography for the
     *     spinners and status marks, which are on screen constantly. Measured on
     *     the device: 708,968 bytes, 4,616 codepoints, covering all five spinner
     *     glyphs (`U+273B U+273D U+2733 U+2734 U+2736`), all five status marks
     *     (`U+2713 U+2714 U+2717 U+2718 U+26A0`) and 100% of Box Drawing, Block
     *     Elements and Braille. It does **not** have `U+23FA` or `U+23BF`, which is
     *     why it cannot be the only fallback.
     *  2. `unifont-17.0.05.otf` — `U+23FA` and `U+23BF`, the two glyphs on every
     *     Claude Code tool-call line that nothing else in reach has, plus the rest
     *     of the BMP as the guarantee against tofu.
     *  3. `unifont_upper-17.0.05.otf` — planes 1-15, which is where emoji live.
     *
     * Put Unifont anywhere but last and it wins glyphs a real typeface would have
     * drawn better, which is the one way to make this change look like a
     * regression.
     *
     * **Two of these three ship and one does not**, and the split is worth stating
     * because 36 MB of Noto in a payload would be a poor trade. The Unifonts are in
     * the Tools payload under `Fonts/` — ~315 KB packed for 11.5 MB raw, measured
     * at 1.3.0, which is also why there is no case for subsetting Unifont: a subset
     * would save almost nothing and destroy the one property that makes it worth
     * shipping, that arbitrary text never comes out empty. Noto Symbols is already
     * on the device in `/system/fonts`, so `SessionRuntime.linkAndroidFonts`
     * symlinks it into `windows\Fonts` and the payload cost is zero.
     *
     * **A missing file degrades quietly and on purpose.** `/system/fonts` names
     * vary by Android version and OEM, so entry 1 may be absent on some device; an
     * unresolvable entry is skipped and the chain falls through. That is exactly
     * why Unifont is last — the backstop is the one tier that ships with the
     * payload and therefore cannot be missing.
     *
     * **Four further tiers were measured on the device and rejected**, so their
     * absence is a decision and not an oversight. `NotoSansCJK-Regular.ttc` is
     * 32 MB and would buy a better typeface for something conhost cannot lay out at
     * all — see [fontLink] on double-width. The Arabic, Hebrew, Devanagari and Thai
     * Noto faces are complex scripts needing shaping conhost does not do (no
     * Uniscribe, no HarfBuzz, one glyph per cell through `ExtTextOutW`), so Arabic
     * renders as isolated unjoined forms however good the font is.
     * `NotoColorEmoji.ttf` is CBDT/CBLC colour bitmaps against an outline
     * rasteriser: an entry that probably does nothing is worse than none, because
     * it makes the chain look like it handles colour emoji. Microsoft's Consolas,
     * Segoe UI, MS Gothic and Segoe UI Emoji were the other obvious answer and are
     * rejected outright — proprietary, and not redistributable in a public release,
     * which is why this chain is built from OFL fonts and the device's own Noto.
     *
     * **Measured union of the base face plus what ships**: 57,070 BMP + 59,055
     * above-BMP = 116,125 codepoints, and **all 12** of the glyphs Cascadia was
     * missing. Cascadia's 1,863 BMP codepoints are a strict subset of Unifont's
     * 57,070, which is what makes a two-file chain complete rather than merely
     * bigger. Counted out of each font's own `cmap` on 2026-08-17.
     */
    internal val FONT_LINK_CHAIN: List<String> = listOf(
        "NotoSansSymbols-Regular-Subsetted.ttf",
        "unifont-17.0.05.otf",
        "unifont_upper-17.0.05.otf",
    )

    /**
     * The [FONT_LINK_CHAIN] entries that come off the device rather than out of the
     * payload. `SessionRuntime.linkAndroidFonts` symlinks these.
     *
     * **Here rather than in `SessionRuntime` so the two lists cannot drift.** A font
     * symlinked into `windows\Fonts` that no chain entry names is a link nothing
     * reaches; a chain entry naming a file nothing links is a tier that silently does
     * not exist. Both failures are invisible -- an unresolvable entry costs one
     * `TRACE` line (`win32u/font.c:2076`) -- so the relationship is expressed as a
     * subset of the chain and checked below rather than left to two files agreeing.
     *
     * One entry, and the shortness is the decision. Measured on the device: 211
     * fonts in `/system/fonts`, of which `NotoSansSymbols-Regular-Subsetted.ttf`
     * (708,968 bytes, 4,616 codepoints) covers all five spinner glyphs, all five
     * status marks and 100% of Box Drawing, Block Elements and Braille.
     * `NotoSansSymbols-Regular-Subsetted2.ttf` (32,996 bytes, 124 codepoints) covers
     * none of that set and `DroidSansMono.ttf` almost nothing, so neither earns a
     * tier. See [FONT_LINK_CHAIN] for why CJK, the complex scripts and colour emoji
     * were measured on the device and deliberately left out.
     */
    internal val ANDROID_LINKED_FONTS: List<String> =
        listOf("NotoSansSymbols-Regular-Subsetted.ttf")

    /**
     * **Why this seed writes one family and not the other thirty-two.**
     *
     * It was tried. Seed 32 wrote all 33, the import succeeded, and every one of
     * Wine's own names was stamped back inside the same session:
     * `update_font_system_link_info` (`win32u/font.c`) rewrites them from a
     * hardcoded table, and the HACK in `update_codepage` calls it on every font
     * init whether anything changed or not. A seed can add a name Wine does not
     * write -- which is why [CONSOLE_FACE] survives here -- and can never keep one
     * it does.
     *
     * So the fallback tier for those families is appended inside Wine instead, by
     * `patches/wine/0056`, where nothing can overwrite it. Do not add them back
     * here; the audit that proved it is in that patch's header.
     */
    /**
     * The fonts [CONSOLE_FACE] falls back to, per glyph, when it has none.
     *
     * **This is Windows' own mechanism and not something Vessel invented**, which
     * is the whole reason it is the answer here. Seeds 29 and 30 each swapped one
     * console face for another and each wrote the other's property down as the
     * unavoidable price — 29 shipped Unifont and got complete coverage in an
     * illegible bitmap face; 30 shipped Cascadia Mono and got a legible face
     * missing 12 glyphs Claude Code's TUI draws. Both were resting on one claim,
     * repeated in four files: "conhost resolves one face with no font linking".
     * The first half is true and the second is false. conhost does select a single
     * `HFONT`, but per-glyph fallback is not conhost's job — it is GDI's, one layer
     * down, and Wine implements it.
     *
     * **Verified end to end in the Wine we ship (`native/wine/`), because a
     * mechanism that stopped short of the text conhost draws would be no use:**
     *
     *  1. `system_link_keyW` (`dlls/win32u/font.c:107-118`) is exactly
     *     [FONT_LINK_KEY] below, in NT form. Wine reads the same key Windows does.
     *  2. `load_system_links` (`font.c:2030-2085`) enumerates that key. **The value
     *     NAME is the base font family** — hence [CONSOLE_FACE] as the name — and
     *     the data is a run of NUL-terminated `filename[,familyname]` strings,
     *     walked until a zero-length entry or the end of the data. Each is resolved
     *     by `find_face_from_filename` (`font.c:896-911`), which compares the
     *     **basename** of a registered font's path, case-insensitively.
     *  3. `create_child_font_list` (`font.c:2775-2816`) attaches the resolved
     *     families as child fonts. Called unconditionally from `create_gdi_font`
     *     (`font.c:4533`), so every font anything selects gets its children.
     *  4. `get_glyph_index_linked` (`font.c:3757-3775`) is the payoff: try the base
     *     font, and on a miss walk the children **in list order**, switching to the
     *     first that has the glyph. Called from `get_glyph_outline` (`font.c:3799`).
     *  5. And it reaches conhost. conhost draws into `CreateCompatibleDC(0)`
     *     (`programs/conhost/window.c:2083`) — a memory DC, so the DIB engine
     *     rather than the X11 driver. The DIB engine rasterises text through
     *     `cache_glyph_bitmap` (`dlls/win32u/dibdrv/graphics.c:745-780`), which
     *     calls `NtGdiGetGlyphOutline` per glyph; that reaches
     *     `font_GetGlyphOutline` (`font.c:4082`) and therefore the linked lookup.
     *
     * **Nothing changes for a glyph Cascadia has.** The base font is tried first
     * and wins, so this is additive by construction: the legibility seed 30 bought
     * is untouched, and only the misses go anywhere else.
     *
     * **[FONT_LINK_CHAIN] is ordered and the order is the design.** See there.
     *
     * **What this does NOT fix, written down rather than left to be discovered.**
     * CJK and emoji stop being tofu and start being *geometrically wrong*, which is
     * a different defect and not a smaller one. conhost has no double-width support
     * at all: `window.c:725` and `:797` both carry "FIXME: use maximum width for
     * DBCS codepages since some chars take two cells", and the draw loop pins every
     * glyph to `i * console->active->font.width` with `dx[] = font.width`
     * (`window.c:483`), so a full-width glyph is drawn in a half-width cell and
     * overlaps its neighbour. Worse than the drawing: the buffer model stores one
     * cell per character where Windows stores a wide character as two cells flagged
     * `COMMON_LVB_LEADING_BYTE` / `COMMON_LVB_TRAILING_BYTE`, so a program that
     * believes a CJK character occupies two columns is mis-aligned before anything
     * is rendered. Fixing that means implementing wide cells in conhost —
     * `write_console`, the buffer model and the renderer — and is deliberately out
     * of scope here. **Emoji are monochrome**, separately: Unifont Upper is
     * outlines and Wine's FreeType path has no colour-glyph format, so "emoji
     * render" must not be read as "emoji work".
     *
     * **Unverified until it runs.** Reading says the `.reg` import writes a
     * REG_MULTI_SZ that `load_system_links` parses and that the chain resolves.
     * Whether the 12 glyphs appear, and whether a 16x16-bitmap-derived glyph beside
     * Cascadia's outlines reads as acceptable rather than merely present, cannot be
     * settled by reading.
     */
    val fontLink: RegistryKey = RegistryKey(
        // `HKEY_LOCAL_MACHINE` and not `HKEY_CURRENT_USER`: `system_link_keyW` is
        // `\Registry\Machine\...`, so a per-user copy would be read by nothing.
        // The machine hive is reachable from this seed and that is measured rather
        // than assumed -- [arm64ecEmulator] writes
        // `HKLM\Software\Microsoft\Wow64\amd64` and translated code runs on the
        // device, which is the same hive and the same `Software` subtree, so
        // nothing redirects between what `regedit` writes and what win32u reads.
        path = FONT_LINK_KEY,
        values = listOf(RegistryValue.multiSz(CONSOLE_FACE, FONT_LINK_CHAIN)),
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

    /**
     * Wine's Bluetooth driver, marked disabled.
     *
     * Every session logged, at error level,
     * `ntoskrnl:ZwLoadDriver failed to create driver L"…\Services\winebth": c00000e5`
     * — `STATUS_INTERNAL_ERROR`, because `winebth.sys` talks to BlueZ over DBus
     * and an Android app has neither a DBus session nor permission to reach the
     * adapter.
     *
     * **This key on its own does nothing, and it was measured doing nothing.**
     * It was added once before, shipped, and the driver still loaded: Wine's PnP
     * path reaches `ZwLoadDriver` from the device node's `SPDRP_SERVICE` and
     * never reads `Start` (`dlls/ntoskrnl.exe/pnp.c`, `load_function_driver`).
     * So it was reverted rather than left as a key a future reader would assume
     * worked. `patches/wine/0031` is the other half — it makes that path honour
     * `SERVICE_DISABLED`, which is what Windows does — and only with both does
     * the driver stop loading. Neither half is useful alone.
     *
     * Disabled rather than removed, because the service entry is Wine's own and
     * `wineboot` recreates it: a deleted key would come back on the next prefix
     * update with the failure back with it. `Start` is the value the loader
     * reads, so setting it is the change that survives.
     *
     * Nothing here wants the driver. Gamepads arrive over the app's own bus
     * (`patches/wine/0016`), not Bluetooth HID, so this turns off no path a user
     * has — only an error line per session that never described anything they
     * could act on.
     *
     * `4` is `SERVICE_DISABLED`, the value `sc config start= disabled` writes.
     */
    val bluetoothService: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\System\CurrentControlSet\Services\winebth""",
        values = listOf(RegistryValue.dword("Start", 4)),
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
     * Wine's own three entries, plus the eight the `Tools` component delivers:
     * Git's three, Python's two, and one each for Node, PowerShell and the JDK's
     * `bin`. `JAVA_HOME` sits beside them for the reason given on that value.
     *
     * **`Vessel Tools` used to be here and was a promise the build did not
     * keep**: it named a directory for a BusyBox nobody ever built, so the value
     * pointed at nothing. The rule that replaced it stands — this seed does not
     * claim a directory in advance. The entries below are different in the way
     * that matters: `build/tools.sh` really does create every one of them, and
     * the entire point of installing the component is that `git`, `python`,
     * `node`, `npm`, `pip`, `pwsh`, `java`, `javac`, `ls`, `grep` and `sed` work
     * from `cmd` without anyone typing a path. `Python\Scripts` is on the list for the same reason
     * even though it starts nearly empty: it is where pip puts console scripts,
     * so it is where everything a user installs will appear, and the build ships
     * a file in it so the directory genuinely arrives.
     *
     * **`clangarm64`, not `mingw64`, and this is easy to get wrong in either
     * direction.** The ARM64 build of Git for Windows uses a `clangarm64` prefix
     * where the x86-64 build uses `mingw64`. This seed said `clangarm64` from
     * seed 19 to seed 23, because the component was the ARM64 build; seed 24
     * moved it to `mingw64` for the all-x64 payload; seed 27 moves it back,
     * because Tools 1.2.0 is ARM64 again — the wheel-ecosystem claim that
     * justified x64 was measured false and PowerShell x86-64 crashed under FEX
     * (see `native/pins.env`). Whichever way the component goes, a PATH naming
     * the other prefix finds `git.exe` through `cmd\` and misses every helper
     * behind it — which looks like a working install right up to the first
     * command that needs one. `build/tools.sh` asserts the payload really
     * contains `clangarm64/bin`, so the two cannot drift apart silently.
     *
     * Written whole rather than appended, because a `.reg` merge replaces a
     * value and there is no append form: this seed is the definition of the
     * machine PATH, so it has to name everything that belongs on it. Naming the
     * component's directories before it is installed is harmless — a PATH entry
     * for a directory that does not exist is something every Windows machine has
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
                    PYTHON_DIR,
                    """$PYTHON_DIR\Scripts""",
                    NODE_DIR,
                    PWSH_DIR,
                    // **Where Claude Code installs itself, and it does not ask.**
                    // `irm https://claude.ai/install.ps1 | iex` drops
                    // `claude.exe` into the guest user's `.local\bin` and then
                    // prints a setup note saying that directory is not on
                    // PATH and to add it through System Properties -- a
                    // dialog this container has no reason to make anyone
                    // visit. Seeded here instead, so the toolchain the Tools
                    // component exists to serve is reachable the moment it
                    // is installed. Verified on the device: version 2.1.233,
                    // installed to this exact path, ARM64.
                    //
                    // Literal rather than `%USERPROFILE%\.local\bin`, because
                    // this seed writes no REG_EXPAND_SZ -- see [RegistryKind],
                    // where the same constraint is load-bearing for the
                    // emulator keys. An expandable string would be left
                    // present, readable and silently unexpanded. It is why
                    // the Windows entries above are literal too rather than
                    // `%SystemRoot%`. (The seed does write REG_DWORD and, since
                    // seed 31, one REG_MULTI_SZ; neither expands either.)
                    //
                    // The profile name is not ours: Wine takes it from the
                    // user it runs as and Valve's tree defaults it to
                    // `steamuser`; nothing in this app sets it. If that ever
                    // changed, this entry would name a directory that does
                    // not exist -- harmless by the rule this key already
                    // follows, and something every Windows machine has
                    // several of.
                    CLAUDE_BIN,
                    // The JDK is the one tree whose PATH entry is not its root:
                    // a JDK root holds `bin`, `lib`, `conf` and `release`, and
                    // only `bin` has launchers in it. [JAVA_HOME] below names the
                    // root, which is what Java tooling actually asks for.
                    """$JAVA_DIR\bin""",
                ).joinToString(";"),
            ),
            // **Which MSYS2 prefix the shell belongs to, and it has to be set
            // before `bash --login` runs.** `/etc/profile` reads `MSYSTEM` to
            // decide what to put on the Unix `PATH` and what `MSYSTEM_PREFIX`
            // is; unset, it falls through to the MSYS prefix and a login shell
            // comes up without `clangarm64/bin` on its path — so `git` works
            // from `cmd` and not from the shell that exists to run it.
            // `git-bash.exe` sets this itself, which is exactly why launching
            // `bash.exe` directly needs it set somewhere else.
            //
            // `CLANGARM64` for the same reason the PATH above says `clangarm64`:
            // the component is the ARM64 build of Git for Windows. It was
            // `MINGW64` for seeds 24-26, while the payload was x86-64, and the
            // two values have to move together — `/etc/profile` derives
            // `MSYSTEM_PREFIX` from this name, so a mismatch is a shell whose
            // prefix directory does not exist.
            //
            // Note what this does NOT change: the shell itself is x86-64 in both
            // builds. `usr/bin/bash.exe` and `usr/bin/msys-2.0.dll` measure
            // 0x8664 in the ARM64 tree, msys-2.0 having no ARM64 port, so this
            // value picks which prefix a translated shell reads — not whether it
            // is translated.
            RegistryValue("MSYSTEM", "CLANGARM64"),
            // **A JDK without `JAVA_HOME` is half-installed.** `PATH` is enough
            // to type `java`, and it is not enough for anything that builds:
            // Gradle, Maven and Ant all look this variable up before they look
            // at `PATH`, and a build tool that cannot find a JDK says so in a
            // way that reads like the JDK is missing rather than unlabelled.
            //
            // The root, not `\bin` — the convention is that `%JAVA_HOME%\bin`
            // is where the launchers are, so pointing it at `bin` gives every
            // consumer `…\bin\bin\java.exe`.
            RegistryValue("JAVA_HOME", JAVA_DIR),
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
     * Where the Python tree of the Tools component installs.
     *
     * `Program Files` beside Git rather than the `C:\PythonXX` that Windows
     * installers default to, because the version is not in the name: the payload
     * carries exactly one Python and a bump should not move the directory the
     * PATH seed points at. Two things have to agree about this — the PATH above
     * and `SessionRuntime.TOOLS_LAYOUT`.
     */
    const val PYTHON_DIR: String = """C:\Program Files\Python"""

    /** Where the Node.js tree of the Tools component installs. See [PYTHON_DIR]. */
    const val NODE_DIR: String = """C:\Program Files\Node"""

    /**
     * Where the PowerShell 7 tree of the Tools component installs. See [PYTHON_DIR].
     *
     * `PowerShell` and not `Pwsh`, even though the payload directory is `Pwsh`:
     * this is the name a user sees in `C:\Program Files` and types into a path,
     * and it is also where a Windows install of PowerShell 7 puts itself (minus
     * the `\7` version directory, which is left off for the same reason nothing
     * else here carries a version — the payload holds exactly one).
     *
     * This tree is the reason the component grew: `TerminalProfile` records the
     * device measurement that rules out Git Bash as Claude Code's shell — a
     * `fork()`-emulation busy loop at 98% of a core — and named PowerShell 7 as
     * the replacement before there was one to name.
     *
     * **Unverified: `pwsh` in a window may render escape sequences as literal
     * text.** PSReadLine is VT-heavy, Wine's conhost has no VT parser, and
     * `SetConsoleMode` stores the VT bits verbatim, so the capability probe every
     * such program makes gets a yes it should get a no. `patches/wine/0052` stops
     * conhost claiming what it cannot do, which should send PowerShell down its
     * Console API rendering path — but that patch is in flight and nothing here
     * has been measured on the device.
     */
    const val PWSH_DIR: String = """C:\Program Files\PowerShell"""

    /**
     * Claude Code's own install location, under the guest user's profile.
     *
     * Not a Vessel-installed tree and not part of the Tools component: the
     * installer script puts it there and this seed only makes it reachable.
     * See the note in [toolsPath] for why the profile name is literal.
     */
    const val CLAUDE_BIN: String = """C:\users\steamuser\.local\bin"""

    /**
     * Where the Temurin JDK tree of the Tools component installs. See [PYTHON_DIR].
     *
     * The root of the JDK, which is deliberately not what goes on `PATH` — the
     * launchers live in `bin` and that is the entry [toolsPath] adds. This is
     * the value `JAVA_HOME` gets, because that is the variable every Java build
     * tool reads and it wants the root.
     *
     * No version in the name, matching every other tree here: the payload
     * carries exactly one JDK, so a bump must not move the directory the seed
     * points at.
     */
    const val JAVA_DIR: String = """C:\Program Files\Java"""

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
        bluetoothService,
        driveTypesReset,
        driveTypes(letters),
        consoleColours,
        // After [consoleColours] rather than before it, because the SystemLink
        // value is keyed on the family name [consoleColours] writes as `FaceName`
        // and reading them in that order is reading one decision. Nothing in
        // `regedit` cares about the order of keys in the file.
        fontLink,
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
                    RegistryKind.DELETE -> append('-')
                    RegistryKind.MULTI_SZ -> append(multiSzHex(value.data))
                }
                append(CRLF)
            }
        }
    }

    /**
     * A NUL-joined [RegistryValue.data] as `hex(7):` and comma-separated bytes.
     *
     * **One byte per character, lower-case hex, comma-separated with no spaces,
     * on one line.** Each of those is a property of the parser rather than a
     * style choice:
     *
     *  - **One byte per character**, because the seed is UTF-8 with no BOM and
     *    `regproc.c:1044` sets `is_unicode` only on a UTF-16LE BOM. On the narrow
     *    path `prepare_hex_string_data` widens the bytes itself with
     *    `MultiByteToWideChar(CP_ACP, …)` (regproc.c:483-495), so writing UTF-16LE
     *    pairs here would be widened twice and import as a chain of
     *    one-character filenames. See [RegistryKind.MULTI_SZ].
     *  - **Comma-separated**, because `convert_hex_csv_to_hex` requires a comma
     *    between bytes and accepts only end-of-line or a `;` comment after one
     *    (regproc.c:282-287) — space-separated bytes are a parse failure.
     *  - **One line**, because the alternative is `\` continuations
     *    (regproc.c:265-273) and there is nothing to buy with them here: the
     *    longest chain this writes is under 300 characters, and a continuation is
     *    one more place for a terminator to go missing.
     *
     * **Both terminators are written here and nowhere else.** A NUL after every
     * entry — including the last — and one further NUL ending the sequence, which
     * is what makes the value a well-formed `REG_MULTI_SZ` rather than one that
     * happens to work because `DataLength` bounds the walk.
     * `prepare_hex_string_data` appends a NUL only if the data does not already
     * end in one, so writing both explicitly means the parser adds nothing and the
     * bytes in the file are the bytes in the hive.
     */
    private fun multiSzHex(nulJoined: String): String {
        val bytes = buildList {
            for (entry in nulJoined.split(MULTI_SZ_SEPARATOR)) {
                entry.forEach { add(it.code) }
                // This entry's own terminator.
                add(0)
            }
            // And the one that ends the sequence.
            add(0)
        }
        return "hex(7):" + bytes.joinToString(",") { "%02x".format(it) }
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
