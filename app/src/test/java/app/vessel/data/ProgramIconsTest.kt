package app.vessel.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgramIconsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun programFiles(): File = temp.newFolder("Program Files")

    private fun install(root: File, dir: String, exe: String): File {
        val home = File(root, dir).apply { mkdirs() }
        return File(home, exe).apply { writeText("MZ") }
    }

    @Test
    fun `a bundled program is found one directory down`() {
        // The taskbar only knows `palemoon.exe`: WM_CLASS carries the bare
        // process name, never a path.
        val root = programFiles()
        val exe = install(root, "Pale Moon", "palemoon.exe")
        assertEquals(listOf(exe), installedProgram(root, "palemoon.exe").filter { it.isFile }.toList())
    }

    @Test
    fun `a name that matches nothing yields nothing`() {
        val root = programFiles()
        install(root, "Pale Moon", "palemoon.exe")
        assertEquals(emptyList<File>(), installedProgram(root, "notepad.exe").filter { it.isFile }.toList())
    }

    @Test
    fun `a missing Program Files is not an error`() {
        // A fresh prefix has no bundled programs yet, and asking for an icon
        // must not throw on the way to the letter fallback.
        val root = File(temp.root, "absent")
        assertEquals(emptyList<File>(), installedProgram(root, "palemoon.exe").toList())
    }

    @Test
    fun `a file sitting directly in Program Files is not a candidate`() {
        // Only directories are searched. A stray file named like the program
        // would otherwise be offered as its own icon source.
        val root = programFiles()
        File(root, "palemoon.exe").writeText("MZ")
        assertEquals(emptyList<File>(), installedProgram(root, "palemoon.exe").toList())
    }

    @Test
    fun `the search does not go two levels deep`() {
        // Bundled programs put the executable at the top of their own
        // directory; a full walk is disk this lookup should not spend.
        val root = programFiles()
        install(root, "Java/bin", "java.exe")
        assertEquals(emptyList<File>(), installedProgram(root, "java.exe").filter { it.isFile }.toList())
    }
}
