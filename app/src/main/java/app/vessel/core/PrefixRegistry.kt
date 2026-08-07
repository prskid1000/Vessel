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
