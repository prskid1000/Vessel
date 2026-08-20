package app.vessel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FexCacheSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cache(root: File, digest: String, bytes: Int): File =
        File(root, digest).apply {
            File(this, "cache").mkdirs()
            File(this, "cache/blob").writeBytes(ByteArray(bytes))
        }

    @Test
    fun `every digest but the live one is reclaimed`() {
        val root = temp.newFolder("fex")
        val dead1 = cache(root, "d609a3d35ea0", 700)
        val dead2 = cache(root, "355bf7dadd25", 300)
        val live = cache(root, "74b54eb48052", 200)

        val freed = sweepStaleFexCaches(root, live)

        assertEquals(1000L, freed)
        assertFalse(dead1.exists())
        assertFalse(dead2.exists())
        assertTrue(File(live, "cache/blob").exists())
    }

    @Test
    fun `a live directory that does not exist yet still protects its name`() {
        // First run on a new key: the digest directory is created by FEX later,
        // so the sweep has to match by name rather than by existence or it
        // would be sweeping the very cache the session is about to use.
        val root = temp.newFolder("fex")
        val dead = cache(root, "old", 500)
        val live = File(root, "brandnew")

        assertEquals(500L, sweepStaleFexCaches(root, live))
        assertFalse(dead.exists())
    }

    @Test
    fun `a lone live cache is left alone`() {
        val root = temp.newFolder("fex")
        val live = cache(root, "only", 400)
        assertEquals(0L, sweepStaleFexCaches(root, live))
        assertTrue(File(live, "cache/blob").exists())
    }

    @Test
    fun `a missing cache root is not an error`() {
        val root = File(temp.root, "absent")
        assertEquals(0L, sweepStaleFexCaches(root, File(root, "x")))
    }
}
