package app.vessel.core

/**
 * One registry value. [name] is empty for a key's default value, which a `.reg`
 * file writes as `@`.
 *
 * Every value here is `REG_SZ` because the readers require it:
 * `load_arm64ec_module` and `get_cpu_dll_name` both test `info->Type == REG_SZ`
 * and ignore the value otherwise, so `REG_EXPAND_SZ` would leave the key
 * present, readable, and silently unused.
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
 * Applying it needs `regedit` in a running prefix, so
 * [app.vessel.data.ContainerProvisioner] renders this to `prefix-seed.reg` and
 * stops. The split keeps the seed's *content* reviewable and testable now,
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
     * Read by `load_arm64ec_module()` (`dlls/ntdll/loader.c:4233`), which appends
     * the default value to `C:\windows\system32\`. Absent, it falls back to
     * `libarm64ecfex.dll` — the same answer — so this asserts the value Wine
     * would pick anyway rather than repairing anything. `wine.inf.in:436` writes
     * it during `wineboot` with NOCLOBBER, so either ordering lands correctly.
     */
    val arm64ecEmulator: RegistryKey = RegistryKey(
        path = """HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\amd64""",
        values = listOf(RegistryValue(RegistryValue.DEFAULT, "libarm64ecfex.dll")),
    )

    /**
     * Where Wine looks for the 32-bit x86 emulator under WoW64, i.e. FEX again.
     *
     * Read by `get_cpu_dll_name()` (`dlls/wow64/syscall.c:741`). Its built-in
     * fallback is already `libwow64fex.dll` on an ARM64 native machine; recorded
     * for the same reason as [arm64ecEmulator].
     *
     * TODO: confirm on a booted prefix that these two are the *only* keys the
     *  ARM64EC path consults. Reading `dlls/ntdll/loader.c` and
     *  `dlls/wow64/syscall.c` found nothing else — FEX is configured entirely
     *  through the environment, not the registry — but neither has been
     *  exercised at runtime.
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
                append('"').append(escape(value.data)).append('"').append(CRLF)
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
