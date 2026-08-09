package app.vessel.core

import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The `dosdevices` invariant, which cost a whole prefix once.
 *
 * Wine creates `drive_c` and `dosdevices/c:` **only** on the pass that creates
 * `dosdevices` itself — `server_init_process_done`, quoted in full on
 * [DriveMap.ensureSystemDrive]. Mapping a drive before the first boot therefore
 * takes that job off Wine and Wine never comes back to it, and the prefix has
 * no C: drive for the rest of its life. These are the tests that would have
 * caught it.
 */
class DriveMapTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Skipped on Windows, out loud, rather than quietly passing.
     *
     * A drive is a file called `c:`, and NTFS will not have a colon in a name —
     * `InvalidPathException: Illegal char <:>` before anything under test runs.
     * The code is Android-only and the CI host is Linux, so these do run; on a
     * developer's Windows machine the honest outcome is "not run", which is
     * what `Assume` reports and a swallowed exception would not.
     */
    private fun prefix(): File {
        val prefix = temp.newFolder("prefix")
        val probe = File(prefix, "probe:")
        val supported = runCatching { probe.createNewFile() }.getOrDefault(false)
        probe.delete()
        Assume.assumeTrue("this filesystem cannot hold a file named 'c:'", supported)
        return prefix
    }

    private fun systemLink(prefix: File) = File(File(prefix, DriveMap.DOSDEVICES), "c:")

    @Test
    fun `mapping into an untouched prefix leaves a C drive behind`() {
        val prefix = prefix()
        val target = temp.newFolder("shared")

        assertTrue(DriveMap.map(prefix, 'd', target))

        assertTrue("drive_c", File(prefix, DriveMap.DRIVE_C).isDirectory)
        assertTrue("dosdevices/c:", Files.isSymbolicLink(systemLink(prefix).toPath()))
        assertEquals(
            "../${DriveMap.DRIVE_C}",
            Files.readSymbolicLink(systemLink(prefix).toPath()).toString().replace('\\', '/'),
        )
    }

    @Test
    fun `the C drive it creates is the one the drive list reports`() {
        val prefix = prefix()
        DriveMap.map(prefix, 'd', temp.newFolder("shared"))

        // Not just "a symlink exists": the seed is rendered from this list, so a
        // C: that does not appear here is a C: Wine will not declare either.
        assertEquals(listOf('c', 'd'), DriveMap.drives(prefix).map { it.letter })
    }

    @Test
    fun `a prefix that already lost its C drive is repaired`() {
        val prefix = prefix()
        // Exactly the state a fresh install reached: `dosdevices` made by the
        // drive mapper, `D:` in it, no `drive_c`, and a `.update-timestamp`
        // from the boot that then failed to copy 773 files into nothing.
        val dosdevices = File(prefix, DriveMap.DOSDEVICES).apply { mkdirs() }
        Files.createSymbolicLink(File(dosdevices, "d:").toPath(), temp.newFolder("shared").toPath())
        val stamp = File(prefix, ".update-timestamp").apply { writeText("1786253850") }

        assertTrue(DriveMap.ensureSystemDrive(prefix))

        assertTrue(File(prefix, DriveMap.DRIVE_C).isDirectory)
        assertTrue(Files.isSymbolicLink(systemLink(prefix).toPath()))
        // The stamp has to go with it, or `wineboot --update` reads it, decides
        // there is nothing to do, and leaves the new `drive_c` empty.
        assertFalse("the update stamp survived the repair", stamp.exists())
    }

    @Test
    fun `a healthy prefix is left alone`() {
        val prefix = prefix()
        DriveMap.map(prefix, 'd', temp.newFolder("shared"))
        val stamp = File(prefix, ".update-timestamp").apply { writeText("1786253850") }

        assertFalse(DriveMap.ensureSystemDrive(prefix))
        assertTrue("a no-op deleted the update stamp", stamp.exists())
    }

    @Test
    fun `an untouched prefix is left to Wine`() {
        val prefix = temp.newFolder("prefix")

        // No `dosdevices`, so Wine's own branch will run and do all of it.
        // Creating a `drive_c` here would cause the very problem this repairs.
        assertFalse(DriveMap.ensureSystemDrive(prefix))
        assertEquals(emptyList<File>(), prefix.listFiles()?.toList().orEmpty())
    }
}
