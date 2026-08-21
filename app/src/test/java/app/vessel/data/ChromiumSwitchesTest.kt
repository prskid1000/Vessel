package app.vessel.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChromiumSwitchesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun exe(dir: File, name: String = "app.exe"): File =
        File(dir, name).apply { writeText("MZ") }

    private fun chromiumPayload(dir: File, snapshot: String = "v8_context_snapshot.bin") {
        dir.mkdirs()
        File(dir, "icudtl.dat").writeText("icu")
        File(dir, snapshot).writeText("v8")
    }

    @Test
    fun `a chromium app beside its payload gets the sandbox switch`() {
        val home = temp.newFolder("app")
        chromiumPayload(home)
        assertEquals(listOf("--no-sandbox"), chromiumSwitchesFor(exe(home), emptyList()))
    }

    @Test
    fun `the payload is found one version directory down`() {
        // VS Code's shape: launcher at the top, payload in a hashed directory.
        val home = temp.newFolder("vscode")
        chromiumPayload(File(home, "7e0ab9d167"))
        assertEquals(listOf("--no-sandbox"), chromiumSwitchesFor(exe(home, "Code.exe"), emptyList()))
    }

    @Test
    fun `snapshot_blob counts as well as v8_context_snapshot`() {
        val home = temp.newFolder("cef")
        chromiumPayload(home, snapshot = "snapshot_blob.bin")
        assertEquals(listOf("--no-sandbox"), chromiumSwitchesFor(exe(home), emptyList()))
    }

    @Test
    fun `an ordinary program gets nothing`() {
        val home = temp.newFolder("notepad")
        assertEquals(emptyList<String>(), chromiumSwitchesFor(exe(home), emptyList()))
    }

    @Test
    fun `ICU alone is not enough to call something chromium`() {
        // Other projects embed ICU. Without a V8 snapshot this is not Chromium,
        // and adding --no-sandbox to an unrelated program would be a lie about
        // what it is.
        val home = temp.newFolder("icuuser")
        home.mkdirs()
        File(home, "icudtl.dat").writeText("icu")
        assertEquals(emptyList<String>(), chromiumSwitchesFor(exe(home), emptyList()))
    }

    @Test
    fun `nothing is added when the user already said it`() {
        val home = temp.newFolder("app2")
        chromiumPayload(home)
        assertEquals(
            emptyList<String>(),
            chromiumSwitchesFor(exe(home), listOf("--no-sandbox", "--enable-logging")),
        )
    }

    @Test
    fun `a missing parent is not an error`() {
        assertEquals(emptyList<String>(), chromiumSwitchesFor(File("app.exe"), emptyList()))
    }
}
