package app.vessel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class DownloadSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = TimeUnit.DAYS.toMillis(1_000)

    /** Age in milliseconds, because the window is a day and the cases straddle it. */
    private fun file(name: String, bytes: Int, age: Long) =
        temp.root.resolve(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(now - age)
        }

    private fun days(n: Long) = TimeUnit.DAYS.toMillis(n)

    private fun hours(n: Long) = TimeUnit.HOURS.toMillis(n)

    @Test
    fun `an archive older than the window is reclaimed`() {
        val stale = file("wine.wcp", 400, age = days(30))
        assertEquals(400L, sweepStaleDownloads(temp.root, now = now))
        assertFalse(stale.exists())
    }

    @Test
    fun `a part file from this session is kept so the download can resume`() {
        val resumable = file("wine.wcp.part", 900, age = hours(6))
        assertEquals(0L, sweepStaleDownloads(temp.root, now = now))
        assertTrue(resumable.exists())
    }

    @Test
    fun `an abandoned part file is reclaimed once the window passes`() {
        val abandoned = file("wine.wcp.part", 900, age = days(30))
        assertEquals(900L, sweepStaleDownloads(temp.root, now = now))
        assertFalse(abandoned.exists())
    }

    @Test
    fun `nothing but wcp archives is considered`() {
        // Same directory, same age, not ours to delete.
        val other = file("notes.txt", 50, age = days(400))
        assertEquals(0L, sweepStaleDownloads(temp.root, now = now))
        assertTrue(other.exists())
    }

    @Test
    fun `an unreadable timestamp is left alone rather than treated as ancient`() {
        val odd = file("wine.wcp", 100, age = days(30))
        // 0 is what `lastModified` returns when it cannot be read; deleting on a
        // stat failure is the one outcome worth avoiding.
        odd.setLastModified(0L)
        assertEquals(0L, sweepStaleDownloads(temp.root, now = now))
        assertTrue(odd.exists())
    }

    @Test
    fun `a missing downloads directory is not an error`() {
        assertEquals(0L, sweepStaleDownloads(temp.root.resolve("absent"), now = now))
    }
}
