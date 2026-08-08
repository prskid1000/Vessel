package app.vessel.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue's bookkeeping, which is the only part of the service that is pure. */
class ComponentDownloadsTest {

    private val downloads = ComponentDownloads()

    @Test
    fun `enqueue adds once and reports whether it did`() {
        assertTrue(downloads.enqueue("wine", "Wine 11.14"))
        assertEquals(1, downloads.snapshot().size)
        assertEquals(DownloadPhase.QUEUED, downloads.snapshot().single().phase)
    }

    @Test
    fun `re-enqueuing something in flight is ignored, not duplicated`() {
        downloads.enqueue("wine", "Wine")
        downloads.update("wine") { it.copy(phase = DownloadPhase.DOWNLOADING) }
        assertFalse(downloads.enqueue("wine", "Wine"))
        assertEquals(1, downloads.snapshot().size)
        // Two workers on one part-file would interleave writes.
        assertEquals(DownloadPhase.DOWNLOADING, downloads.snapshot().single().phase)
    }

    @Test
    fun `a failed job can be enqueued again and starts from QUEUED`() {
        downloads.enqueue("wine", "Wine")
        downloads.update("wine") { it.copy(phase = DownloadPhase.FAILED, detail = "no route") }
        assertTrue(downloads.enqueue("wine", "Wine"))
        val job = downloads.snapshot().single()
        assertEquals(DownloadPhase.QUEUED, job.phase)
        assertNull("the old failure must not be shown against a new attempt", job.detail)
    }

    @Test
    fun `next is the first queued and skips anything terminal`() {
        downloads.enqueue("a", "A")
        downloads.enqueue("b", "B")
        downloads.update("a") { it.copy(phase = DownloadPhase.DONE) }
        assertEquals("b", downloads.next()?.id)
    }

    @Test
    fun `pending is what keeps the service alive`() {
        downloads.enqueue("a", "A")
        downloads.enqueue("b", "B")
        downloads.update("a") { it.copy(phase = DownloadPhase.DONE) }
        downloads.update("b") { it.copy(phase = DownloadPhase.FAILED) }
        assertTrue(downloads.pending().isEmpty())
        assertNull(downloads.active())
    }

    @Test
    fun `a finished job stays visible until it is cleared`() {
        downloads.enqueue("a", "A")
        downloads.update("a") { it.copy(phase = DownloadPhase.FAILED, detail = "HTTP 404") }
        // A failure that vanishes from the screen is a failure nobody read.
        assertEquals(1, downloads.snapshot().size)
        downloads.clearFinished()
        assertTrue(downloads.snapshot().isEmpty())
    }
}
