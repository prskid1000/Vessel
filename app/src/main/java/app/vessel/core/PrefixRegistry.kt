package app.vessel.core

/**
 * One registry value. [name] is empty for a key's default value, which a `.reg`
 * file writes as `@`.
 *
 * Every value here is `REG_SZ`. That is not a simplification — it is what the
 * readers require. `load_arm64ec_module` and `get_cpu_dll_name` both test
 * `info->Type == REG_SZ` and ignore the value otherwise, so emitting one of them
 * as `REG_EXPAND_SZ` would leave the key present, readable, and silently unused.
 */
data class RegistryValue(val name: String, val data: String) {
    companion object {
        /** The name of a key's default value. */
        const val DEFAULT = ""
    }
}

/** One key and the values under it. */
data class RegistryKey(val path: String, val values: List<RegistryValue>)

/**
 * The registry a fresh prefix needs, as data and as a `.reg` file.
 *
 * Applying it needs `regedit` inside a running prefix, which needs a process,
 * which is out of scope here — [app.vessel.data.ContainerProvisioner] renders
 * this to `prefix-seed.reg` and stops. The split is deliberate: the *content* of
 * the seed is a set of facts about Wine that can be reviewed and tested now,
 * independently of how the app eventually executes anything.
 */
object PrefixRegistry {

    /** `.reg` files are CRLF and carry this exact first line. */
    const val HEADER: String = "Windows Registry Editor Version 5.00"

    /**
     * Bumped when [seed] changes in a way an already-provisioned container needs
     * re-applied. [app.vessel.data.ContainerProvisioner] stores it, so a seed
     * change re-runs the registry step and nothing else.
     */
    const val SEED_VERSION: Int = 1

    /** The mode the DLL override values carry. See [D3D_DLL_OVERRIDES]. */
    const val DLL_OVERRIDE_MODE: String = "native,builtin"

    /**
     * **`renderer = vulkan`, and this is the one nobody remembers.**
     *
     * DXVK covers D3D9 through D3D11 and vkd3d covers D3D12, so it is easy to
     * conclude wined3d is not in the picture. It is: **DirectDraw and D3D1–7 go
     * through wined3d**, which defaults to its OpenGL renderer — and Vessel's
     * Wine is built with no GLX at all. Without this value an old title fails
     * with no obvious cause, because nothing on the way down says "I tried to use
     * OpenGL". The only hint is `+winediag`'s renderer-selection line, which is
     * in [WINEDEBUG_CHANNELS] precisely so this failure is visible when it
     * happens anyway.
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
     * Read by `load_arm64ec_module()` at `dlls/ntdll/loader.c:4233` in the tree
     * under `native/wine`: it opens
     * `\Registry\Machine\Software\Microsoft\Wow64\amd64`, takes the default
     * value if it is `REG_SZ`, and appends it to `C:\windows\system32\`. With the
     * key absent it falls back to `libarm64ecfex.dll` — the same answer — so this
     * is an assertion of the value Wine would pick anyway, not a repair.
     *
     * `loader/wine.inf.in:436` writes it during `wineboot` with the NOCLOBBER
     * flag. Seeding it before that leaves ours in place; applying the seed after
     * overwrites with an identical value. Either order lands correctly.
     */
    val arm64ecEmulator: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\amd64""",
        values = listOf(RegistryValue(RegistryValue.DEFAULT, "libarm64ecfex.dll")),
    )

    /**
     * Where Wine looks for the 32-bit x86 emulator under WoW64, i.e. FEX again.
     *
     * Read by `get_cpu_dll_name()` at `dlls/wow64/syscall.c:741`, which opens
     * `…\Wow64\x86` and requires `REG_SZ`. Its built-in fallback is already
     * `libwow64fex.dll` when the native machine is ARM64, and `wine.inf.in:438`
     * writes the same. Recorded for the same reason as [arm64ecEmulator]: the
     * value the container depends on should be visible in Vessel's own seed
     * rather than only in Wine's.
     *
     * TODO: confirm on a booted prefix that these two are the *only* keys the
     *  ARM64EC path consults. `\Registry\Machine\Software\Microsoft\Wow64\arm`
     *  (`wowarmhw.dll`) is the 32-bit-ARM case and is irrelevant here, and no
     *  `FEX_*` value is read from the registry — FEX is configured entirely
     *  through the environment, per `docs/TUNING.md`. Nothing else was found by
     *  reading `dlls/ntdll/loader.c` and `dlls/wow64/syscall.c`, but neither has
     *  been exercised at runtime, because nothing has run yet.
     */
    val x86Emulator: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\x86""",
        values = listOf(RegistryValue(RegistryValue.DEFAULT, "libwow64fex.dll")),
    )

    /** Everything a new prefix gets, in the order it is written. */
    val seed: List<RegistryKey> = listOf(direct3D, dllOverrides, arm64ecEmulator, x86Emulator)

    /**
     * [keys] as the text of a `.reg` file.
     *
     * CRLF throughout and UTF-8 on disk. Wine's `regedit` accepts UTF-8 and LF,
     * but a `.reg` file is a Windows format and there is no reason for the one we
     * generate to be the unusual variant of it.
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
                append('"').append(escape(value.data)).append('"').append(CRLF)
            }
        }
    }

    /**
     * `.reg` string escaping: backslash then quote, in that order.
     *
     * Doing it the other way round would escape the backslashes this function
     * just inserted. Only value text is escaped — a key path in `[...]` carries
     * its separators literally.
     */
    private fun escape(text: String): String =
        text.replace("""\""", """\\""").replace("\"", """\"""")

    private const val CRLF = "\r\n"
}
