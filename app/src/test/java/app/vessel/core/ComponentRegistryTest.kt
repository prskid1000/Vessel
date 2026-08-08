package app.vessel.core

import app.vessel.RepoFiles
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading `registry/contents.json`.
 *
 * The last test in this file reads the *real* registry out of the repository
 * rather than a fixture, which is the point of it: `build/gen_registry.py` and
 * this parser are two independent transcriptions of one format, and a fixture
 * only ever proves that the parser agrees with whoever wrote the fixture.
 */
class ComponentRegistryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val digest = "180d0a9004071fae86d0b2a1470f4c644037e3b60a68098962c6603d0d51d4c6"

    private fun entry(
        id: String = "dxvk-2.7.1-canoe",
        type: String = "DXVK",
        sha256: String? = digest,
        url: String? = "https://example.invalid/dxvk.wcp",
    ) = buildString {
        append("""{"id":"$id","type":"$type","name":"DXVK","versionName":"2.7.1",""")
        append(""""versionCode":20701,"sizeBytes":3572468,""")
        append(if (sha256 == null) """"sha256":null,""" else """"sha256":"$sha256",""")
        append(if (url == null) """"url":null,""" else """"url":"$url",""")
        append(""""target":"canoe","sourceSha":"c3dd74b","cpuFlags":"-mcpu=oryon-1"}""")
    }

    private fun document(vararg entries: String, schemaVersion: Int = 1) =
        """{"schemaVersion":$schemaVersion,"generator":"vessel/gen_registry.py",""" +
            """"components":[${entries.joinToString(",")}]}"""

    private fun parse(text: String) = ComponentRegistry.parse(json, text)

    @Test
    fun `a well-formed entry becomes a downloadable package`() {
        val result = parse(document(entry())) as ComponentRegistry.Result.Available
        assertEquals(1, result.packages.size)
        val pkg = result.packages.single()
        assertEquals(ComponentType.DXVK, pkg.type)
        assertEquals(digest, pkg.sha256)
        assertEquals("https://example.invalid/dxvk.wcp", pkg.url)
        assertTrue(pkg.isDownloadable)
        // The registry is a catalogue, not this device's answer.
        assertFalse(pkg.installed)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `a type this build cannot load is refused with the type named`() {
        val result = parse(document(entry(type = "Box64"))) as ComponentRegistry.Result.Available
        assertTrue(result.packages.isEmpty())
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().why.contains("Box64"))
    }

    @Test
    fun `no digest means refused, because nothing could check the download`() {
        val result = parse(document(entry(sha256 = null))) as ComponentRegistry.Result.Available
        assertTrue(result.packages.isEmpty())
        assertTrue(result.rejected.single().why.contains("SHA-256"))
    }

    @Test
    fun `a digest that is not a digest is refused before the download, not after it`() {
        val result = parse(document(entry(sha256 = "TODO"))) as ComponentRegistry.Result.Available
        assertTrue(result.packages.isEmpty())
        assertTrue(result.rejected.single().why.contains("hex"))
    }

    @Test
    fun `plain http is refused, because a wcp is executed after it is unpacked`() {
        val result = parse(document(entry(url = "http://example.invalid/dxvk.wcp")))
            as ComponentRegistry.Result.Available
        assertTrue(result.packages.isEmpty())
        assertTrue(result.rejected.single().why.contains("https"))
    }

    @Test
    fun `one bad entry does not take the good ones down with it`() {
        val result = parse(
            document(entry(id = "good"), entry(id = "bad", url = null), entry(id = "good-2")),
        ) as ComponentRegistry.Result.Available
        assertEquals(listOf("good", "good-2"), result.packages.map { it.id })
        assertEquals(listOf("bad"), result.rejected.map { it.id })
    }

    @Test
    fun `a newer schema is refused rather than read leniently`() {
        val result = parse(document(entry(), schemaVersion = 99))
        assertTrue(result is ComponentRegistry.Result.Unreadable)
        assertTrue(result.summary.contains("99"))
    }

    @Test
    fun `bytes that are not JSON are Unreadable, not an empty catalogue`() {
        val result = parse("<html>404</html>")
        assertTrue(result is ComponentRegistry.Result.Unreadable)
    }

    @Test
    fun `the registry this repository publishes parses, with nothing refused`() {
        val text = RepoFiles.file("registry/contents.json").readText()
        val result = ComponentRegistry.parse(json, text)
        assertTrue("registry/contents.json did not parse: ${result.summary}", result is ComponentRegistry.Result.Available)
        result as ComponentRegistry.Result.Available

        assertEquals(
            "every component in registry/contents.json should be loadable: " +
                result.rejected.joinToString { "${it.id} — ${it.why}" },
            emptyList<ComponentRegistry.RejectedEntry>(),
            result.rejected,
        )
        assertTrue(result.packages.isNotEmpty())
        // This is the field the TODO said was missing and nothing verified. If
        // gen_registry.py ever stops writing it, or writes it under another
        // name, this is where that shows up.
        assertTrue(result.packages.all { it.isDownloadable })
    }
}
