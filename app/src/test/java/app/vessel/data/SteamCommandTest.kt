package app.vessel.data

import app.vessel.core.PrefixRegistry
import app.vessel.core.SteamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which launches go through the Steam client's loader, and what it is told.
 *
 * Every refusal here is a launch that must start the plain way: the loader
 * stops on a message box when it has no AppID or no client, and a game routed
 * into that never starts at all.
 */
class SteamCommandTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var prefix: File
    private lateinit var game: File
    private lateinit var install: File

    private val guestExe = """C:\Games\NMS\Binaries\NMS.exe"""
    private val guestRunDir = """C:\Games\NMS\Binaries"""

    @Before
    fun setUp() {
        prefix = temp.newFolder("prefix")
        game = File(prefix, "drive_c/Games/NMS/Binaries").apply { mkdirs() }
        install = File(prefix, SteamClient.PREFIX_DIR).apply { mkdirs() }
        SteamClient.REQUIRED.forEach { File(install, it).writeText("pe") }
        File(game, "steam_api64.dll").writeText("pe")
        File(game, "steam_appid.txt").writeText("275850\n")
    }

    @Test
    fun `an x64 Steam game starts through the x64 loader with its own arguments`() {
        val command = route(exe(0x8664), listOf("-windowed"))

        assertEquals(
            GuestCommand("""C:\Program Files (x86)\Steam\steamclient_loader_x64.exe""", listOf("-windowed")),
            command,
        )
    }

    @Test
    fun `an x86 Steam game starts through the x86 loader`() {
        assertEquals(
            """C:\Program Files (x86)\Steam\steamclient_loader_x86.exe""",
            route(exe(0x014C))?.program,
        )
    }

    @Test
    fun `the loader's ini names the game, its folder, its AppID and both clients`() {
        route(exe(0x8664))

        val ini = File(install, "steamclient_loader_x64.ini").readText()
        assertTrue(ini, ini.contains("Exe=$guestExe\r\n"))
        assertTrue(ini, ini.contains("ExeRunDir=$guestRunDir\r\n"))
        assertTrue(ini, ini.contains("AppId=275850\r\n"))
        assertTrue(ini, ini.contains("""SteamClient64Dll=C:\Program Files (x86)\Steam\steamclient64.dll"""))
        assertTrue(ini, ini.contains("""SteamClientDll=C:\Program Files (x86)\Steam\steamclient.dll"""))
    }

    @Test
    fun `the AppID in steam_settings is read first, as the loader reads it`() {
        File(game, "steam_settings").mkdirs()
        File(game, "steam_settings/steam_appid.txt").writeText("480")

        route(exe(0x8664))

        assertTrue(File(install, "steamclient_loader_x64.ini").readText().contains("AppId=480\r\n"))
    }

    @Test
    fun `an exe wrapped in Steam's DRM gets gbe's helper injected at startup`() {
        File(install, "extra_dlls/x64").mkdirs()
        route(exe(0x8664, ".text", ".rdata", ".bind"))

        assertTrue(
            File(install, "steamclient_loader_x64.ini").readText()
                .contains("DllsToInjectFolder=C:\\Program Files (x86)\\Steam\\extra_dlls\\x64\r\n"),
        )
    }

    @Test
    fun `an exe without the DRM injects nothing`() {
        File(install, "extra_dlls/x64").mkdirs()
        route(exe(0x8664, ".text", ".rdata"))

        assertTrue(!File(install, "steamclient_loader_x64.ini").readText().contains("DllsToInjectFolder"))
    }

    @Test
    fun `a package without the helper still launches a wrapped exe, injecting nothing`() {
        assertEquals(
            """C:\Program Files (x86)\Steam\steamclient_loader_x64.exe""",
            route(exe(0x8664, ".bind"))?.program,
        )
        assertTrue(!File(install, "steamclient_loader_x64.ini").readText().contains("DllsToInjectFolder"))
    }

    @Test
    fun `a file that is not a PE is not wrapped`() {
        assertTrue(!SteamClient.hasSteamStub(File(game, "steam_appid.txt")))
    }

    @Test
    fun `a folder without steam_api is not a Steam game`() {
        File(game, "steam_api64.dll").delete()
        assertNull(route(exe(0x8664)))
    }

    @Test
    fun `no AppID starts the plain way rather than into the loader's error box`() {
        File(game, "steam_appid.txt").delete()
        assertNull(route(exe(0x8664)))
    }

    @Test
    fun `an AppID that is not a number is no AppID`() {
        File(game, "steam_appid.txt").writeText("nms")
        assertNull(route(exe(0x8664)))
    }

    @Test
    fun `a prefix without the client installed starts the plain way`() {
        File(install, SteamClient.CLIENT_64).delete()
        assertNull(route(exe(0x8664)))
    }

    @Test
    fun `an ARM64 exe has no loader`() {
        assertNull(route(exe(0xAA64)))
    }

    @Test
    fun `a command that is not the shortcut's own exe is left alone`() {
        val exe = exe(0x8664)
        assertNull(
            steamCommand(prefix, exe, guestExe, guestRunDir, GuestCommand("cmd.exe", listOf("/c", guestExe))),
        )
    }

    @Test
    fun `the prefix seed says where Steam is installed`() {
        val paths = PrefixRegistry.seed.map { it.path }
        assertTrue(paths.contains("""HKEY_CURRENT_USER\Software\Valve\Steam\ActiveProcess"""))
        assertTrue(paths.contains("""HKEY_LOCAL_MACHINE\Software\Wow6432Node\Valve\Steam"""))
        val active = PrefixRegistry.seed.first { it.path.endsWith("""Valve\Steam\ActiveProcess""") }
        assertEquals(
            """C:\Program Files (x86)\Steam\steamclient64.dll""",
            active.values.first { it.name == "SteamClientDll64" }.data,
        )
        // No pid: a seeded one is dead or someone else's, and the loader writes it.
        assertTrue(active.values.none { it.name == "pid" })
    }

    private fun route(exe: File, arguments: List<String> = emptyList()): GuestCommand? =
        steamCommand(prefix, exe, guestExe, guestRunDir, GuestCommand(guestExe, arguments))

    /** A PE header with no optional header, and a section table naming [sections]. */
    private fun exe(machine: Int, vararg sections: String): File = File(game, "NMS.exe").apply {
        val bytes = ByteArray(0x58 + sections.size * 40 + 16)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3C] = 0x40
        bytes[0x40] = 'P'.code.toByte()
        bytes[0x41] = 'E'.code.toByte()
        bytes[0x44] = (machine and 0xFF).toByte()
        bytes[0x45] = ((machine shr 8) and 0xFF).toByte()
        bytes[0x46] = sections.size.toByte()
        sections.forEachIndexed { i, name ->
            name.toByteArray().copyInto(bytes, 0x58 + i * 40)
        }
        writeBytes(bytes)
    }
}
