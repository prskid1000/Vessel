package app.vessel.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The installer, against archives that are real tars.
 *
 * Two things here are worth more than the rest. The xz tests are the ones that
 * say the app can install a package at all — xz is what `build/package_wcp.py`
 * produces, so an xz package that installs end to end is the difference between
 * a working app and one whose every download is refused. And the traversal tests
 * are the security boundary: a `.wcp` is downloaded content, and every one of
 * these entry shapes is a way out of the destination directory.
 */
class WcpInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val installer = WcpInstaller(json)

    private lateinit var paths: ContainerPaths
    private lateinit var layout: ContainerLayout
    private lateinit var packages: File

    @Before
    fun setUp() {
        paths = ContainerPaths(temp.newFolder("files"))
        layout = paths.of("container-a")
        packages = temp.newFolder("packages")
    }

    // — compression -----------------------------------------------------------

    @Test
    fun `zstd is recognised and refused, because nothing here can decode it`() {
        // Exactly the magic every package in dist/ starts with.
        val archive = File(packages, "dxvk.wcp")
        archive.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64))

        assertEquals(WcpCompression.ZSTD, WcpArchive.detect(archive))
        val result = installer.installBlocking(archive, layout)
        val failure = result as WcpInstallResult.UnsupportedCompression
        assertEquals(WcpCompression.ZSTD, failure.compression)
        assertTrue(failure.summary.contains("zstd"))
        assertFalse(layout.components.exists())
    }

    @Test
    fun `zstd is the only codec left undecodable`() {
        assertEquals(
            setOf(WcpCompression.NONE, WcpCompression.GZIP, WcpCompression.XZ),
            WcpCompression.entries.filter { it.decodable }.toSet(),
        )
        assertFalse(WcpCompression.ZSTD.decodable)
    }

    @Test
    fun `an uncompressed tar is recognised by its ustar magic`() {
        val archive = TestWcp.standard(File(packages, "plain.wcp"))
        assertEquals(WcpCompression.NONE, WcpArchive.detect(archive))
    }

    @Test
    fun `a file that is neither tar nor a known codec is unknown, not assumed`() {
        val archive = File(packages, "junk.wcp")
        archive.writeBytes("this is not an archive".toByteArray())
        assertEquals(WcpCompression.UNKNOWN, WcpArchive.detect(archive))
        assertTrue(
            installer.installBlocking(archive, layout)
                is WcpInstallResult.UnsupportedCompression,
        )
    }

    // — xz, the codec every shipped package uses ------------------------------

    @Test
    fun `an xz package installs end to end`() {
        val archive = TestWcp.standard(
            File(packages, "dxvk.wcp"),
            payload = mapOf("x64/dxgi.dll" to "dxgi", "x64/d3d11.dll" to "d3d11"),
            codec = TestCodec.XZ,
        )

        assertEquals(WcpCompression.XZ, WcpArchive.detect(archive))
        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed

        assertEquals(WcpCompression.XZ, installed.compression)
        assertEquals(2, installed.fileCount)
        assertEquals("2.7.1", installed.profile.versionName)

        val destination = File(layout.components, "DXVK")
        assertEquals(destination, installed.directory)
        assertEquals("dxgi", File(destination, "x64/dxgi.dll").readText())
        assertEquals("d3d11", File(destination, "x64/d3d11.dll").readText())
        assertTrue(File(destination, "profile.json").isFile)
    }

    @Test
    fun `an xz package is recognised by its magic bytes, whatever it is called`() {
        // A downloaded file's name proves nothing. This one lies twice: the
        // extension says gzip and the base name says a different component.
        val archive = TestWcp.standard(
            File(packages, "turnip.tar.gz"),
            type = "Turnip",
            codec = TestCodec.XZ,
        )
        assertEquals(WcpCompression.XZ, WcpArchive.detect(archive))
        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertEquals(File(layout.components, "Turnip"), installed.directory)
    }

    @Test
    fun `an xz package is gated on its sha256 sidecar like any other`() {
        val good = TestWcp.standard(File(packages, "dxvk.wcp"), codec = TestCodec.XZ)
        TestWcp.writeSidecar(good)
        val installed = installer.installBlocking(good, layout) as WcpInstallResult.Installed
        assertTrue(installed.checksumVerified)
        assertEquals(WcpCompression.XZ, installed.compression)

        // The digest covers the compressed bytes, so a package that decodes
        // perfectly well is still refused when it is not the one published.
        val wrong = TestWcp.standard(File(packages, "wine.wcp"), type = "Wine", codec = TestCodec.XZ)
        TestWcp.writeSidecar(wrong, digest = "0".repeat(64))
        val failure = installer.installBlocking(wrong, layout) as WcpInstallResult.ChecksumMismatch
        assertEquals("0".repeat(64), failure.expected)
        assertFalse(File(layout.components, "Wine").exists())
    }

    @Test
    fun `an xz payload larger than the copy buffer arrives byte for byte`() {
        // The real Wine package is 71 MB and is never held in memory; this is
        // the same path at a size a unit test can afford, and it is here to
        // catch a decode that is streamed but not correct.
        val archive = File(packages, "wine.wcp")
        archive.writeBytes(TestWcp.compress(bulkTar(), TestCodec.XZ))

        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertEquals(1, installed.fileCount)
        assertArrayEquals(bulkPayload, File(installed.directory, "x64/dxgi.dll").readBytes())
    }

    @Test
    fun `a truncated xz package leaves no half-installed component directory`() {
        val full = TestWcp.compress(bulkTar(), TestCodec.XZ)
        val archive = File(packages, "cut.wcp")
        // Header intact, so it is still detected as xz and reaches the decoder.
        archive.writeBytes(full.copyOfRange(0, full.size * 2 / 3))

        assertEquals(WcpCompression.XZ, WcpArchive.detect(archive))
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.Malformed)
        assertNoResidue()
    }

    @Test
    fun `a corrupt xz package is malformed, not a crash and not a leaked handle`() {
        val bytes = TestWcp.compress(bulkTar(), TestCodec.XZ)
        // Byte 8 is inside the stream header's CRC32, past the six magic bytes.
        // So the file is still unmistakably xz, and the failure happens in
        // XZInputStream's constructor — before the installer holds the stream,
        // which is the case WcpArchive.open has to close the file for.
        bytes[8] = (bytes[8] + 1).toByte()
        val archive = File(packages, "bitrot.wcp")
        archive.writeBytes(bytes)

        assertEquals(WcpCompression.XZ, WcpArchive.detect(archive))
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.Malformed)
        assertNoResidue()
    }

    /**
     * A payload big enough that xz emits a stream worth cutting up, and big
     * enough to need several passes of the extractor's 64 KB copy buffer.
     * Incompressible on purpose: it keeps the compressed size predictable, so
     * "two thirds of the file" is reliably two thirds of the way through the
     * payload rather than somewhere unintended.
     */
    private val bulkPayload: ByteArray =
        ByteArray(512 * 1024).also(java.util.Random(20701)::nextBytes)

    private fun bulkTar(): ByteArray = TestWcp.tar(
        listOf(
            TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
            TestTarEntry("x64/dxgi.dll", bulkPayload),
        ),
    )

    /** Nothing installed, and no staging directory left behind. */
    private fun assertNoResidue() {
        assertFalse(File(layout.components, "DXVK").exists())
        assertEquals(emptyList<File>(), layout.tmp.listFiles()?.toList().orEmpty())
    }

    // — the happy path --------------------------------------------------------

    @Test
    fun `a gzip package installs under its type and keeps its profile`() {
        val archive = TestWcp.standard(
            File(packages, "dxvk.wcp"),
            payload = mapOf("x64/dxgi.dll" to "dxgi", "x64/d3d11.dll" to "d3d11"),
            codec = TestCodec.GZIP,
        )

        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed

        assertEquals(WcpCompression.GZIP, installed.compression)
        assertEquals(2, installed.fileCount)
        assertEquals("2.7.1", installed.profile.versionName)
        assertEquals(20701, installed.profile.versionCode)
        assertEquals("canoe", installed.profile.vessel?.provenance?.target)
        assertEquals("-mcpu=oryon-1", installed.profile.vessel?.provenance?.cpuFlags)
        assertEquals("9c51ede5", installed.profile.vessel?.provenance?.sourceSha)

        val destination = File(layout.components, "DXVK")
        assertEquals(destination, installed.directory)
        assertEquals("dxgi", File(destination, "x64/dxgi.dll").readText())
        assertEquals("d3d11", File(destination, "x64/d3d11.dll").readText())
        assertTrue(File(destination, "profile.json").isFile)
    }

    @Test
    fun `an uncompressed package installs the same way`() {
        val archive = TestWcp.standard(File(packages, "turnip.wcp"), type = "Turnip")
        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertEquals(File(layout.components, "Turnip"), installed.directory)
    }

    @Test
    fun `staging is cleaned up, so tmp does not accumulate`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        installer.installBlocking(archive, layout)
        assertEquals(emptyList<File>(), layout.tmp.listFiles()?.toList().orEmpty())
    }

    @Test
    fun `a GNU long name entry is read from its own body`() {
        // Over the 100-byte name field, so it has to come from the GNU 'L' entry,
        // but short enough not to trip Windows' own path limit in a temp folder.
        val longName = "x64/" + "d".repeat(105) + ".dll"
        val archive = TestWcp.write(
            File(packages, "long.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("././@LongLink", (longName + "\u0000").toByteArray(), typeFlag = 'L'),
                TestTarEntry(longName.take(100), "payload".toByteArray()),
            ),
        )

        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertEquals(1, installed.fileCount)
        assertEquals("payload", File(installed.directory, longName).readText())
    }

    // — checksums -------------------------------------------------------------

    @Test
    fun `a mismatched sidecar refuses the install and writes nothing`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive, digest = "0".repeat(64))

        val failure = installer.installBlocking(archive, layout)
            as WcpInstallResult.ChecksumMismatch
        assertEquals("0".repeat(64), failure.expected)
        assertFalse(File(layout.components, "DXVK").exists())
    }

    @Test
    fun `a matching sidecar installs and records that it was verified`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive)

        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertTrue(installed.checksumVerified)
    }

    @Test
    fun `an explicitly supplied digest overrides the sidecar`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive)

        val failure = installer.installBlocking(archive, layout, expectedSha256 = "f".repeat(64))
        assertTrue(failure is WcpInstallResult.ChecksumMismatch)
    }

    @Test
    fun `no sidecar installs but says so, rather than claiming verification`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        val installed = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertFalse(installed.checksumVerified)
        assertTrue(installed.summary.contains("checksum not verified"))
    }

    @Test
    fun `the sidecar is read in sha256sum format`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive)
        assertEquals(TestWcp.sha256(archive), installer.readSidecar(archive))
    }

    // — the traversal guard ---------------------------------------------------

    @Test
    fun `a dotdot entry is refused and nothing is installed`() {
        val archive = TestWcp.write(
            File(packages, "evil.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("../../escaped.so", "pwned".toByteArray()),
            ),
        )

        val failure = installer.installBlocking(archive, layout) as WcpInstallResult.UnsafeEntry
        assertEquals("../../escaped.so", failure.entryName)
        assertFalse(File(layout.components, "DXVK").exists())
        assertFalse(File(layout.base.parentFile, "escaped.so").exists())
    }

    @Test
    fun `a dotdot buried mid-path is refused too`() {
        val archive = TestWcp.write(
            File(packages, "evil2.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("x64/../../../escaped.so", "pwned".toByteArray()),
            ),
        )
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.UnsafeEntry)
    }

    @Test
    fun `an absolute entry is refused`() {
        val archive = TestWcp.write(
            File(packages, "abs.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("/data/data/app.vessel/files/owned.so", "pwned".toByteArray()),
            ),
        )
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.UnsafeEntry)
    }

    @Test
    fun `a symlink entry is refused rather than followed`() {
        val archive = TestWcp.write(
            File(packages, "link.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("x64/dxgi.dll", ByteArray(0), typeFlag = '2'),
            ),
        )
        val failure = installer.installBlocking(archive, layout) as WcpInstallResult.UnsafeEntry
        assertTrue(failure.why.contains("link"))
    }

    @Test
    fun `a device node is refused`() {
        val archive = TestWcp.write(
            File(packages, "dev.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("dev/null", ByteArray(0), typeFlag = '3'),
            ),
        )
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.UnsafeEntry)
    }

    @Test
    fun `the guard accepts ordinary nested paths and rejects every escape`() {
        val destination = temp.newFolder("dest")

        assertEquals(
            File(destination, "x64${File.separator}dxgi.dll").canonicalFile,
            installer.resolveEntry(destination, "x64/dxgi.dll"),
        )
        assertEquals(
            File(destination, "dxgi.dll").canonicalFile,
            installer.resolveEntry(destination, "./dxgi.dll"),
        )

        assertNull(installer.resolveEntry(destination, "../escaped"))
        assertNull(installer.resolveEntry(destination, "a/../../escaped"))
        assertNull(installer.resolveEntry(destination, "/absolute"))
        assertNull(installer.resolveEntry(destination, """C:\windows\system32\bad.dll"""))
        assertNull(installer.resolveEntry(destination, """windows\bad.dll"""))
        assertNull(installer.resolveEntry(destination, ""))
        assertNull(installer.resolveEntry(destination, "  "))
        assertNull(installer.resolveEntry(destination, "."))
    }

    // — idempotency -----------------------------------------------------------

    @Test
    fun `installing over an existing version replaces it, leaving no stale files`() {
        val first = TestWcp.standard(
            File(packages, "dxvk-2.7.1.wcp"),
            versionName = "2.7.1",
            versionCode = 20701,
            payload = mapOf("x64/dxgi.dll" to "old", "x64/gone.dll" to "removed next version"),
        )
        installer.installBlocking(first, layout)
        val destination = File(layout.components, "DXVK")
        assertTrue(File(destination, "x64/gone.dll").isFile)

        val second = TestWcp.standard(
            File(packages, "dxvk-2.8.0.wcp"),
            versionName = "2.8.0",
            versionCode = 20800,
            payload = mapOf("x64/dxgi.dll" to "new"),
        )
        val installed = installer.installBlocking(second, layout) as WcpInstallResult.Installed

        assertEquals("2.8.0", installed.profile.versionName)
        assertEquals("new", File(destination, "x64/dxgi.dll").readText())
        assertFalse(
            "a file the new build dropped must not survive as a stale copy",
            File(destination, "x64/gone.dll").exists(),
        )
    }

    @Test
    fun `installing the same package twice is stable`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        val once = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        val twice = installer.installBlocking(archive, layout) as WcpInstallResult.Installed
        assertEquals(once.fileCount, twice.fileCount)
        assertEquals(once.directory, twice.directory)
    }

    // — malformed packages ----------------------------------------------------

    @Test
    fun `an unknown package type is refused by name`() {
        // Box64 is a real Winlator type this app deliberately cannot load.
        val archive = TestWcp.standard(File(packages, "box64.wcp"), type = "Box64")
        val failure = installer.installBlocking(archive, layout) as WcpInstallResult.UnknownType
        assertEquals("Box64", failure.wire)
        assertFalse(layout.components.listFiles()?.isNotEmpty() ?: false)
    }

    @Test
    fun `an archive with no profile is malformed`() {
        val archive = TestWcp.write(
            File(packages, "bare.wcp"),
            listOf(TestTarEntry("x64/dxgi.dll", "no manifest".toByteArray())),
        )
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.Malformed)
    }

    @Test
    fun `an unparseable profile is malformed rather than a crash`() {
        val archive = TestWcp.write(
            File(packages, "broken.wcp"),
            listOf(TestTarEntry("profile.json", "{not json".toByteArray())),
        )
        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.Malformed)
    }

    @Test
    fun `a missing file is reported as missing`() {
        val result = installer.installBlocking(File(packages, "nope.wcp"), layout)
        assertTrue(result is WcpInstallResult.NotFound)
    }

    @Test
    fun `a corrupt tar header stops the read instead of extracting garbage`() {
        val bytes = TestWcp.tar(
            listOf(TestTarEntry("profile.json", TestWcp.profileJson().toByteArray())),
        )
        // Break the checksum field of the first header.
        bytes[148] = '9'.code.toByte()
        val archive = File(packages, "corrupt.wcp")
        // Keep the ustar magic intact so it is still detected as a tar.
        archive.writeBytes(bytes)

        assertTrue(installer.installBlocking(archive, layout) is WcpInstallResult.Malformed)
    }
}
