package app.vessel.service

import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import app.vessel.core.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download path, against a real socket.
 *
 * These are the cases that only exist because the network is not a function
 * call: a resume that the server honours, a resume it ignores, a body that
 * arrives whole and wrong, and a 404. Each one has a specific *disposal* rule
 * for the part-file, and getting that wrong is how a download becomes
 * unretryable rather than merely slow.
 */
class ComponentDownloaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val downloader = ComponentDownloader()

    /** 300 KB, so several 64 KB buffers and one partial one are exercised. */
    private val payload = ByteArray(300 * 1024) { (it * 31 % 256).toByte() }
    private val digest = Sha256.of(payload.inputStream())

    private fun request(
        server: TestHttpServer,
        sha256: String = digest,
        id: String = "dxvk-2.7.1-canoe",
    ) = DownloadRequest(id, "DXVK 2.7.1", server.url, sha256, payload.size.toLong())

    private fun target(id: String = "dxvk-2.7.1-canoe") = File(temporary.root, "$id.wcp")
    private fun part(id: String = "dxvk-2.7.1-canoe") = File(temporary.root, "$id.wcp.part")

    @Test
    fun `a clean download lands as the wcp and hashes to the registry's digest`() {
        TestHttpServer(payload).use { server ->
            val result = runBlocking { downloader.download(request(server), temporary.root) }
            result as DownloadResult.Complete
            assertEquals(payload.size.toLong(), result.bytes)
            assertEquals(0L, result.resumedFrom)
            assertArrayEquals(payload, target().readBytes())
            assertFalse("the part-file must not survive a success", part().exists())
        }
    }

    @Test
    fun `progress is reported and ends at the total the server declared`() {
        TestHttpServer(payload).use { server ->
            val seen = mutableListOf<DownloadProgress>()
            runBlocking { downloader.download(request(server), temporary.root) { seen += it } }
            assertTrue(seen.isNotEmpty())
            assertEquals(payload.size.toLong(), seen.last().bytesDownloaded)
            assertEquals(payload.size.toLong(), seen.last().totalBytes)
            assertEquals(1f, seen.last().fraction)
            // Monotonic: a bar that goes backwards is worse than no bar.
            assertEquals(seen.map { it.bytesDownloaded }.sorted(), seen.map { it.bytesDownloaded })
        }
    }

    @Test
    fun `a part-file is resumed with a Range header rather than refetched`() {
        val head = 100 * 1024
        part().writeBytes(payload.copyOfRange(0, head))
        TestHttpServer(payload).use { server ->
            val result = runBlocking { downloader.download(request(server), temporary.root) }
            result as DownloadResult.Complete
            assertEquals(head.toLong(), result.resumedFrom)
            assertArrayEquals(payload, target().readBytes())
            val sent = server.requests.single()
            assertTrue(
                "expected a Range header, got: $sent",
                sent.any { it.equals("Range: bytes=$head-", ignoreCase = true) },
            )
        }
    }

    @Test
    fun `a server that ignores Range restarts instead of appending`() {
        // The failure this prevents: 100 KB of part-file plus a 300 KB whole
        // body concatenated into a 400 KB archive, which fails its digest with
        // no hint that the server, not the network, was the problem.
        part().writeBytes(payload.copyOfRange(0, 100 * 1024))
        TestHttpServer(payload, ignoreRange = true).use { server ->
            val result = runBlocking { downloader.download(request(server), temporary.root) }
            result as DownloadResult.Complete
            assertEquals(0L, result.resumedFrom)
            assertArrayEquals(payload, target().readBytes())
        }
    }

    @Test
    fun `a body that is not the published package is refused and the part-file discarded`() {
        val wrong = "b".repeat(64)
        TestHttpServer(payload).use { server ->
            val result = runBlocking { downloader.download(request(server, sha256 = wrong), temporary.root) }
            result as DownloadResult.DigestMismatch
            assertEquals(wrong, result.expected)
            assertEquals(digest, result.actual)
            // The whole point: a resume from these bytes could never succeed.
            assertFalse(part().exists())
            assertFalse(target().exists())
            assertTrue(result.summary.contains("starts from the beginning"))
        }
    }

    @Test
    fun `a 404 names the code and says the release asset is gone`() {
        TestHttpServer(payload, status = 404).use { server ->
            val result = runBlocking { downloader.download(request(server), temporary.root) }
            result as DownloadResult.Rejected
            assertEquals(404, result.code)
            assertTrue(result.summary.contains("404"))
        }
    }

    @Test
    fun `an unreachable host is Unreachable, not Rejected`() {
        val request = DownloadRequest(
            id = "nowhere",
            name = "nowhere",
            // A .invalid TLD can never resolve, by RFC 2606.
            url = "https://vessel-test.invalid/component.wcp",
            sha256 = digest,
            sizeBytes = payload.size.toLong(),
        )
        val result = runBlocking { downloader.download(request, temporary.root) }
        assertTrue("got $result", result is DownloadResult.Unreachable)
    }

    @Test
    fun `an archive already on disk with the right digest is not downloaded again`() {
        target().writeBytes(payload)
        TestHttpServer(payload).use { server ->
            val result = runBlocking { downloader.download(request(server), temporary.root) }
            result as DownloadResult.Complete
            assertTrue("the server should not have been contacted", server.requests.isEmpty())
        }
    }

    @Test
    fun `an archive already on disk with the wrong digest is replaced`() {
        target().writeBytes(ByteArray(payload.size))
        TestHttpServer(payload).use { server ->
            runBlocking { downloader.download(request(server), temporary.root) }
            assertArrayEquals(payload, target().readBytes())
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `an id with a path separator in it cannot escape the download directory`() {
        TestHttpServer(payload).use { server ->
            val nasty = request(server, id = "../../evil")
            runBlocking { downloader.download(nasty, temporary.root) }
            val escaped = File(temporary.root.parentFile, "evil.wcp")
            assertFalse("wrote outside the download directory: $escaped", escaped.exists())
            assertTrue(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".wcp") })
        }
    }

    @Test
    fun `a registry entry with no digest never becomes a request`() {
        val pkg = ComponentPackage(
            id = "dxvk-2.7.1-canoe",
            type = ComponentType.DXVK,
            name = "DXVK",
            versionName = "2.7.1",
            versionCode = 20701,
            sizeBytes = 1,
            installed = false,
            target = "canoe",
            sourceSha = "c3dd74b",
            cpuFlags = "-mcpu=oryon-1",
            sha256 = null,
            url = "https://example.invalid/dxvk.wcp",
        )
        assertFalse(pkg.isDownloadable)
        assertNull(DownloadRequest.of(pkg))
    }
}
