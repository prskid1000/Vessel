package app.vessel.ui.shell

import app.vessel.core.PeArchitecture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the shell will and will not start.
 *
 * The two refusals are the point of these tests. A `.ps1` must be rejected with a
 * reason, because Wine's `powershell.exe` is a stub that would appear to launch
 * and then do nothing; and a `.exe` with no PE header must be rejected too,
 * because a partly-downloaded file has exactly that shape.
 */
class LaunchableTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a PowerShell script is refused, and the reason names the stub`() {
        val verdict = launchabilityOf(temp.newFile("setup.ps1"))
        assertTrue(verdict is Launchable.Refused)
        assertTrue((verdict as Launchable.Refused).reason.contains("stub PowerShell"))
    }

    @Test
    fun `a file ending in exe with no PE header is refused`() {
        val notReallyAnExe = temp.newFile("truncated.exe").apply { writeText("not a PE at all") }
        val verdict = launchabilityOf(notReallyAnExe)
        assertTrue(verdict is Launchable.Refused)
        assertTrue((verdict as Launchable.Refused).reason.contains("no PE header"))
    }

    @Test
    fun `an ARM64 PE runs natively`() {
        val verdict = launchabilityOf(pe("native.exe", PeArchitecture.ARM64.machine))
        assertTrue(verdict is Launchable.Runs)
        assertEquals(PeArchitecture.ARM64, (verdict as Launchable.Runs).arch)
        assertTrue(verdict.via.contains("without translation"))
    }

    @Test
    fun `an x86-64 PE names the translation layer it needs`() {
        val verdict = launchabilityOf(pe("game.exe", PeArchitecture.X64.machine)) as Launchable.Runs
        assertEquals(PeArchitecture.X64, verdict.arch)
        assertTrue(verdict.via.contains("libarm64ecfex"))
    }

    @Test
    fun `a batch file runs through cmd`() {
        val verdict = launchabilityOf(temp.newFile("install.bat")) as Launchable.Runs
        assertEquals("cmd.exe /c", verdict.via)
    }

    @Test
    fun `an installer runs through msiexec`() {
        val verdict = launchabilityOf(temp.newFile("thing.msi")) as Launchable.Runs
        assertEquals("msiexec.exe /i", verdict.via)
    }

    @Test
    fun `a script host file runs but carries its caveat`() {
        val verdict = launchabilityOf(temp.newFile("thing.vbs")) as Launchable.Runs
        assertTrue(verdict.caveat!!.contains("incomplete"))
    }

    @Test
    fun `a shell script is not a program this engine can serve`() {
        // Android is bionic and FEX ships as Wine's DLLs rather than as
        // FEXLoader, so neither a `.sh` nor a Linux ELF has any path here. They
        // must not be offered as launchable anywhere in the interface.
        assertEquals(Launchable.NotAProgram, launchabilityOf(temp.newFile("run.sh")))
    }

    @Test
    fun `a data file is not a program`() {
        assertEquals(Launchable.NotAProgram, launchabilityOf(temp.newFile("savegame.dat")))
        assertEquals(Launchable.NotAProgram, launchabilityOf(temp.newFile("dxdiag.txt")))
    }

    @Test
    fun `provenance says how the architecture was determined`() {
        assertTrue(archProvenance(PeArchitecture.X86).contains("IMAGE_FILE_MACHINE_I386"))
        assertTrue(archProvenance(PeArchitecture.UNKNOWN).contains("could not be read"))
    }

    /** The smallest byte sequence that is a PE as far as the machine field. */
    private fun pe(name: String, machine: Int): File = temp.newFile(name).apply {
        val header = ByteArray(0x50)
        header[0] = 'M'.code.toByte()
        header[1] = 'Z'.code.toByte()
        // e_lfanew = 0x40, little-endian
        header[0x3C] = 0x40
        header[0x40] = 'P'.code.toByte()
        header[0x41] = 'E'.code.toByte()
        header[0x44] = (machine and 0xFF).toByte()
        header[0x45] = ((machine shr 8) and 0xFF).toByte()
        writeBytes(header)
    }
}
