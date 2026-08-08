package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading `IMAGE_FILE_HEADER.Machine`.
 *
 * Every malformed case must come back [PeArchitecture.UNKNOWN] rather than throw:
 * this runs over every file in a directory listing, and a Wine prefix is full of
 * things that are not PEs.
 */
class PeReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `each machine word maps to its architecture`() {
        assertEquals(PeArchitecture.ARM64, PeReader.fromMachine(0xAA64))
        assertEquals(PeArchitecture.X64, PeReader.fromMachine(0x8664))
        assertEquals(PeArchitecture.X86, PeReader.fromMachine(0x014C))
        assertEquals(PeArchitecture.UNKNOWN, PeReader.fromMachine(0x0001))
    }

    @Test
    fun `a well-formed PE is read from disk`() {
        assertEquals(PeArchitecture.X64, PeReader.architectureOf(pe(0x8664)))
    }

    @Test
    fun `a file with no MZ is unknown`() {
        assertEquals(PeArchitecture.UNKNOWN, PeReader.architectureOf(text("hello, world")))
    }

    @Test
    fun `a truncated file is unknown rather than an exception`() {
        assertEquals(PeArchitecture.UNKNOWN, PeReader.architectureOf(text("MZ")))
    }

    @Test
    fun `a missing file is unknown`() {
        assertEquals(PeArchitecture.UNKNOWN, PeReader.architectureOf(File(temp.root, "absent.exe")))
    }

    @Test
    fun `a directory is unknown`() {
        assertEquals(PeArchitecture.UNKNOWN, PeReader.architectureOf(temp.newFolder("folder")))
    }

    @Test
    fun `an e_lfanew pointing off the end is refused rather than followed`() {
        val bomb = temp.newFile("bomb.exe")
        val bytes = ByteArray(0x50)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        // 0x7FFFFFFF — well past both the sanity ceiling and the file's length.
        bytes[0x3C] = 0xFF.toByte()
        bytes[0x3D] = 0xFF.toByte()
        bytes[0x3E] = 0xFF.toByte()
        bytes[0x3F] = 0x7F
        bomb.writeBytes(bytes)
        assertEquals(PeArchitecture.UNKNOWN, PeReader.architectureOf(bomb))
    }

    @Test
    fun `MZ without a PE signature is unknown`() {
        val stub = temp.newFile("dos.exe")
        val bytes = ByteArray(0x50)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3C] = 0x40
        // No "PE\0\0" at 0x40 — a genuine DOS-only executable.
        stub.writeBytes(bytes)
        assertEquals(PeArchitecture.UNKNOWN, PeReader.architectureOf(stub))
    }

    private fun pe(machine: Int): File = temp.newFile("app.exe").apply {
        val bytes = ByteArray(0x50)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3C] = 0x40
        bytes[0x40] = 'P'.code.toByte()
        bytes[0x41] = 'E'.code.toByte()
        bytes[0x44] = (machine and 0xFF).toByte()
        bytes[0x45] = ((machine shr 8) and 0xFF).toByte()
        writeBytes(bytes)
    }

    private fun text(content: String): File =
        temp.newFile("plain.exe").apply { writeText(content) }
}
