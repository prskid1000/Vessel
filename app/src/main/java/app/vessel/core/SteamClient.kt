package app.vessel.core

import java.io.File

/**
 * The Steam client emulator (the `Steam` component, `build/gbe.sh`), and how a
 * Steam game is started through it.
 *
 * **Where it lives.** Exactly where a real Steam install puts itself:
 * `C:\Program Files (x86)\Steam`, holding `steamclient.dll`, `steamclient64.dll`
 * and gbe's loader in the place of `steam.exe`. A game keeps its own untouched
 * `steam_api(64).dll`, and on a PC that DLL finds the client through
 * `HKCU\Software\Valve\Steam\ActiveProcess` -- `SteamClientDll64` names the file
 * and `pid` must be a live process, or `SteamAPI_Init` reports Steam is not
 * running. [registryKeys] seeds the static half of that; the loader writes the
 * whole of it on every launch, starts the game, and stays alive for the pid.
 *
 * **Why through the loader and not the keys alone.** The pid. No process of ours
 * lives in the prefix for the life of a game, and a seeded pid is either dead or
 * some other process that happens to have it -- the loader *is* a process that
 * lives exactly as long as the game.
 */
object SteamClient {

    /** The install folder, as the guest sees it. */
    const val INSTALL_DIR: String = """C:\Program Files (x86)\Steam"""

    /** The same folder, relative to the prefix root. */
    const val PREFIX_DIR: String = "drive_c/Program Files (x86)/Steam"

    /** The payload's tree that is laid into [PREFIX_DIR]. */
    const val PAYLOAD_DIR: String = "Steam"

    const val CLIENT_32: String = "steamclient.dll"
    const val CLIENT_64: String = "steamclient64.dll"
    const val LOADER_32: String = "steamclient_loader_x86.exe"
    const val LOADER_64: String = "steamclient_loader_x64.exe"

    /**
     * The files that must all be present for the install to count.
     *
     * Checked at launch rather than trusted from the install step, because a
     * container that has not adopted the component, or whose install failed,
     * must start its games the plain way rather than through a loader that is
     * not there.
     */
    val REQUIRED: List<String> = listOf(CLIENT_32, CLIENT_64, LOADER_32, LOADER_64)

    /**
     * gbe's loader reads `<its own name>.ini` beside itself before falling back
     * to `ColdClientLoader.ini`, so each loader has its own and a 32-bit launch
     * never reads what a 64-bit one wrote.
     */
    fun iniNameFor(loader: String): String = loader.substringBeforeLast('.') + ".ini"

    /** Whether [folder] -- a game's executable folder -- is a Steam game's. */
    fun isSteamGame(folder: File): Boolean =
        STEAM_API_NAMES.any { File(folder, it).isFile }

    /**
     * The game's Steam AppID, from `steam_appid.txt`.
     *
     * The same two places the loader itself looks beside the exe, in the same
     * order. Null when neither holds a number: the loader would then stop on a
     * "You forgot to set the AppId" message box before the game started, so the
     * caller starts the game the plain way instead.
     */
    fun appIdFor(folder: File): String? =
        listOf(File(folder, "steam_settings/$APPID_FILE"), File(folder, APPID_FILE))
            .firstNotNullOfOrNull { file ->
                runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
                    ?.lineSequence()?.firstOrNull()?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            }

    /**
     * Which loader starts [architecture]'s process.
     *
     * The loader warns in a message box when its own bitness differs from the
     * game's, so the match is made here rather than left to that dialog. Null
     * for anything that is neither: an ARM64 game has no x86 steam_api to talk
     * to this client.
     */
    fun loaderFor(architecture: PeArchitecture): String? = when (architecture) {
        PeArchitecture.X64 -> LOADER_64
        PeArchitecture.X86 -> LOADER_32
        else -> null
    }

    /**
     * The folder of gbe's SteamStub DRM helper for [architecture], relative to
     * [INSTALL_DIR], or null when there is none for it.
     *
     * One folder per architecture because the loader injects every DLL in the
     * folder it is given.
     */
    fun extraDllsFor(architecture: PeArchitecture): String? = when (architecture) {
        PeArchitecture.X64 -> "extra_dlls\\x64"
        PeArchitecture.X86 -> "extra_dlls\\x86"
        else -> null
    }

    /**
     * Whether [exe] is wrapped in Steam's DRM (SteamStub), whose unpacker lives
     * in a section named `.bind`.
     *
     * A wrapped exe asks the Steam client to let it run before its real entry
     * point, so it needs gbe's `steamclient_extra` injected at startup -- which
     * is what [loaderIni]'s `injectFolder` is for. Read from the section table
     * rather than guessed from the game: No Man's Sky carries one, and most
     * games do not. Anything unreadable is not wrapped, which leaves a launch
     * exactly as it was.
     */
    fun hasSteamStub(exe: File): Boolean = runCatching {
        java.io.RandomAccessFile(exe, "r").use { f ->
            if (f.length() < 0x40) return false
            f.seek(0x3C)
            val pe = f.readIntLe().toLong()
            if (pe <= 0 || pe > f.length() - 24) return false
            f.seek(pe)
            if (f.readIntLe() != 0x00004550) return false // "PE\0\0"
            f.seek(pe + 6)
            val sections = f.readShortLe()
            f.seek(pe + 20)
            val optional = f.readShortLe()
            val table = pe + 24 + optional
            if (sections > MAX_SECTIONS || table + sections * 40L > f.length()) return false
            val name = ByteArray(8)
            (0 until sections).any { i ->
                f.seek(table + i * 40L)
                f.readFully(name)
                String(name, Charsets.ISO_8859_1).trimEnd('\u0000') == STEAMSTUB_SECTION
            }
        }
    }.getOrDefault(false)

    private fun java.io.RandomAccessFile.readShortLe(): Int {
        val lo = read(); val hi = read()
        return (lo and 0xFF) or ((hi and 0xFF) shl 8)
    }

    private fun java.io.RandomAccessFile.readIntLe(): Int =
        readShortLe() or (readShortLe() shl 16)

    /**
     * The loader's ini for one launch.
     *
     * Paths are Windows paths; the loader resolves relative ones against its own
     * folder, and these are absolute so nothing depends on that.
     * `IgnoreLoaderArchDifference` because [loaderFor] already matched them, and
     * a stray dialog here would sit on an otherwise-working launch.
     */
    fun loaderIni(exe: String, runDir: String, appId: String, injectFolder: String? = null): String = buildString {
        append("[SteamClient]").append(CRLF)
        append("Exe=").append(exe).append(CRLF)
        append("ExeRunDir=").append(runDir).append(CRLF)
        append("ExeCommandLine=").append(CRLF)
        append("AppId=").append(appId).append(CRLF)
        append("SteamClientDll=").append(INSTALL_DIR).append('\\').append(CLIENT_32).append(CRLF)
        append("SteamClient64Dll=").append(INSTALL_DIR).append('\\').append(CLIENT_64).append(CRLF)
        append(CRLF)
        append("[Injection]").append(CRLF)
        append("IgnoreLoaderArchDifference=1").append(CRLF)
        if (injectFolder != null) append("DllsToInjectFolder=").append(injectFolder).append(CRLF)
        append(CRLF)
        append("[Persistence]").append(CRLF)
        append("Mode=0").append(CRLF)
    }

    /**
     * The half of Steam's registry that does not change between launches.
     *
     * What a real install leaves behind, so a game or launcher that asks where
     * Steam is -- `SteamPath`, `InstallPath` -- is answered even before a game
     * has gone through the loader. `pid` is deliberately absent: see the class
     * comment. The loader rewrites `ActiveProcess` on every launch and restores
     * these values when the game exits.
     */
    val registryKeys: List<RegistryKey> = listOf(
        RegistryKey(
            path = """HKEY_CURRENT_USER\Software\Valve\Steam""",
            values = listOf(
                // Steam itself writes these two lower-case with forward slashes.
                RegistryValue("SteamPath", "c:/program files (x86)/steam"),
                RegistryValue("SteamExe", "c:/program files (x86)/steam/$LOADER_64"),
            ),
        ),
        RegistryKey(
            path = """HKEY_CURRENT_USER\Software\Valve\Steam\ActiveProcess""",
            values = listOf(
                RegistryValue("SteamClientDll", "$INSTALL_DIR\\$CLIENT_32"),
                RegistryValue("SteamClientDll64", "$INSTALL_DIR\\$CLIENT_64"),
                RegistryValue("Universe", "Public"),
            ),
        ),
        RegistryKey(
            path = """HKEY_LOCAL_MACHINE\Software\Wow6432Node\Valve\Steam""",
            values = listOf(RegistryValue("InstallPath", INSTALL_DIR)),
        ),
        RegistryKey(
            path = """HKEY_LOCAL_MACHINE\Software\Valve\Steam""",
            values = listOf(RegistryValue("InstallPath", INSTALL_DIR)),
        ),
    )

    private val STEAM_API_NAMES = listOf("steam_api64.dll", "steam_api.dll")
    private const val APPID_FILE = "steam_appid.txt"
    private const val STEAMSTUB_SECTION = ".bind"

    /** The PE format's own ceiling; anything above it is not a real header. */
    private const val MAX_SECTIONS = 96
    private const val CRLF = "\r\n"
}
