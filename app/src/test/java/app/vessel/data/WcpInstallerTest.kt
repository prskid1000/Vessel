package app.vessel.data

import app.vessel.core.ComponentType
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
import java.nio.file.Files

/**
 * The installer, against archives that are real tars.
 *
 * Three things here are worth more than the rest. The xz tests are the ones that
 * say the app can install a package at all — xz is what `build/package_wcp.py`
 * produces, so an xz package that installs end to end is the difference between
 * a working app and one whose every download is refused. The traversal tests are
 * the security boundary: a `.wcp` is downloaded content, and every one of these
 * entry shapes is a way out of the destination directory. And the symlink tests
 * are both at once — the Wine package's twelve `bin/` links are how `wineboot`
 * exists at all, and a link target is another way out.
 */
class WcpInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val installer = WcpInstaller(json)

    private lateinit var paths: ContainerPaths
    private lateinit var store: ComponentStoreLayout
    private lateinit var packages: File

    /** Where `TestWcp.standard`'s default package lands. */
    private val dxvk: File get() = store.version(ComponentType.DXVK, 20701)

    @Before
    fun setUp() {
        paths = ContainerPaths(temp.newFolder("files"))
        store = paths.components
        packages = temp.newFolder("packages")
    }

    // — compression -----------------------------------------------------------

    @Test
    fun `zstd is recognised and refused, because nothing here can decode it`() {
        // Exactly the magic every package in dist/ starts with.
        val archive = File(packages, "dxvk.wcp")
        archive.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64))

        assertEquals(WcpCompression.ZSTD, WcpArchive.detect(archive))
        val result = installer.installBlocking(archive, store)
        val failure = result as WcpInstallResult.UnsupportedCompression
        assertEquals(WcpCompression.ZSTD, failure.compression)
        assertTrue(failure.summary.contains("zstd"))
        assertFalse(store.root.exists())
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
            installer.installBlocking(archive, store)
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
        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed

        assertEquals(WcpCompression.XZ, installed.compression)
        assertEquals(2, installed.fileCount)
        assertEquals("2.7.1", installed.profile.versionName)

        assertEquals(dxvk, installed.directory)
        assertEquals("dxgi", File(dxvk, "x64/dxgi.dll").readText())
        assertEquals("d3d11", File(dxvk, "x64/d3d11.dll").readText())
        assertTrue(File(dxvk, "profile.json").isFile)
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
        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertEquals(store.version(ComponentType.TURNIP, 20701), installed.directory)
    }

    @Test
    fun `an xz package is gated on its sha256 sidecar like any other`() {
        val good = TestWcp.standard(File(packages, "dxvk.wcp"), codec = TestCodec.XZ)
        TestWcp.writeSidecar(good)
        val installed = installer.installBlocking(good, store) as WcpInstallResult.Installed
        assertTrue(installed.checksumVerified)
        assertEquals(WcpCompression.XZ, installed.compression)

        // The digest covers the compressed bytes, so a package that decodes
        // perfectly well is still refused when it is not the one published.
        val wrong = TestWcp.standard(File(packages, "wine.wcp"), type = "Wine", codec = TestCodec.XZ)
        TestWcp.writeSidecar(wrong, digest = "0".repeat(64))
        val failure = installer.installBlocking(wrong, store) as WcpInstallResult.ChecksumMismatch
        assertEquals("0".repeat(64), failure.expected)
        assertFalse(store.type(ComponentType.WINE).exists())
    }

    @Test
    fun `an xz payload larger than the copy buffer arrives byte for byte`() {
        // The real Wine package is 71 MB and is never held in memory; this is
        // the same path at a size a unit test can afford, and it is here to
        // catch a decode that is streamed but not correct.
        val archive = File(packages, "wine.wcp")
        archive.writeBytes(TestWcp.compress(bulkTar(), TestCodec.XZ))

        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
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
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.Malformed)
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
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.Malformed)
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
        assertFalse(dxvk.exists())
        assertEquals(emptyList<File>(), store.staging.listFiles()?.toList().orEmpty())
    }

    // — the happy path --------------------------------------------------------

    @Test
    fun `a gzip package installs under its type and version, and keeps its profile`() {
        val archive = TestWcp.standard(
            File(packages, "dxvk.wcp"),
            payload = mapOf("x64/dxgi.dll" to "dxgi", "x64/d3d11.dll" to "d3d11"),
            codec = TestCodec.GZIP,
        )

        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed

        assertEquals(WcpCompression.GZIP, installed.compression)
        assertEquals(2, installed.fileCount)
        assertEquals("2.7.1", installed.profile.versionName)
        assertEquals(20701, installed.profile.versionCode)
        assertEquals("canoe", installed.profile.vessel?.provenance?.target)
        assertEquals("-mcpu=oryon-1", installed.profile.vessel?.provenance?.cpuFlags)
        assertEquals("9c51ede5", installed.profile.vessel?.provenance?.sourceSha)

        assertEquals(dxvk, installed.directory)
        assertEquals("dxgi", File(dxvk, "x64/dxgi.dll").readText())
        assertEquals("d3d11", File(dxvk, "x64/d3d11.dll").readText())
        assertTrue(File(dxvk, "profile.json").isFile)
    }

    @Test
    fun `an uncompressed package installs the same way`() {
        val archive = TestWcp.standard(File(packages, "turnip.wcp"), type = "Turnip")
        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertEquals(store.version(ComponentType.TURNIP, 20701), installed.directory)
    }

    @Test
    fun `the package id is recorded beside the version, not inside it`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        installer.installBlocking(archive, store, packageId = "dxvk-2.7.1-canoe")

        val record = store.record(ComponentType.DXVK, 20701)
        assertTrue(record.isFile)
        assertTrue(record.readText().contains("dxvk-2.7.1-canoe"))
        // The version directory holds the package and nothing this app added.
        assertFalse(File(dxvk, "20701.json").exists())
    }

    @Test
    fun `staging is cleaned up, so the store does not accumulate`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        installer.installBlocking(archive, store)
        assertEquals(emptyList<File>(), store.staging.listFiles()?.toList().orEmpty())
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

        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertEquals(1, installed.fileCount)
        assertEquals("payload", File(installed.directory, longName).readText())
    }

    // — symlinks, which the Wine package cannot work without ------------------

    /**
     * Twelve of these ship in the Wine package, all in `bin/` and all pointing
     * at `wine` beside them. Wine dispatches on `argv[0]`, so `bin/wineboot` is
     * not an alias for convenience — it is the only way to initialise a prefix.
     * An installer that drops them reports success and produces a Wine that can
     * never provision a container.
     */
    @Test
    fun `a relative symlink survives installation and resolves to the real file`() {
        val archive = TestWcp.write(
            File(packages, "wine.wcp"),
            listOf(
                TestTarEntry("profile.json", wineProfile()),
                TestTarEntry("bin/wine", "the real binary".toByteArray()),
                TestTarEntry("bin/wineboot", typeFlag = '2', linkTarget = "wine"),
                TestTarEntry("bin/winecfg", typeFlag = '2', linkTarget = "wine"),
            ),
        )

        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        val wine = store.version(ComponentType.WINE, 1013)
        assertEquals(wine, installed.directory)
        assertEquals(3, installed.fileCount)

        assertEquals("the real binary", File(wine, "bin/wineboot").readText())
        assertEquals("the real binary", File(wine, "bin/winecfg").readText())
        if (symlinksSupported) {
            assertTrue(Files.isSymbolicLink(File(wine, "bin/wineboot").toPath()))
            // Relative, so it keeps working after the staging directory is
            // renamed into place — which it already has been by now.
            assertEquals("wine", Files.readSymbolicLink(File(wine, "bin/wineboot").toPath()).toString())
        }
    }

    @Test
    fun `a symlink into a sibling directory is allowed and resolved against its own directory`() {
        val archive = TestWcp.write(
            File(packages, "wine.wcp"),
            listOf(
                TestTarEntry("profile.json", wineProfile()),
                TestTarEntry("lib/wine/libwine.so", "library".toByteArray()),
                TestTarEntry("bin/libwine.so", typeFlag = '2', linkTarget = "../lib/wine/libwine.so"),
            ),
        )
        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertEquals("library", File(installed.directory, "bin/libwine.so").readText())
    }

    @Test
    fun `an absolute symlink target is refused`() {
        val failure = installLink("/system/lib64/libc.so") as WcpInstallResult.UnsafeEntry
        assertEquals("bin/wineboot", failure.entryName)
        assertTrue(failure.why.contains("relative"))
        assertFalse(store.version(ComponentType.WINE, 1013).exists())
    }

    @Test
    fun `a Windows absolute symlink target is refused too`() {
        assertTrue(installLink("""C:\windows\system32\bad.dll""") is WcpInstallResult.UnsafeEntry)
        assertTrue(installLink("""windows\bad.dll""") is WcpInstallResult.UnsafeEntry)
    }

    @Test
    fun `a symlink that escapes the component directory is refused`() {
        assertTrue(installLink("../../../etc/passwd") is WcpInstallResult.UnsafeEntry)
        assertTrue(installLink("../../escaped.so") is WcpInstallResult.UnsafeEntry)
        assertFalse(store.version(ComponentType.WINE, 1013).exists())
    }

    @Test
    fun `an empty symlink target is refused rather than guessed at`() {
        assertTrue(installLink("") is WcpInstallResult.UnsafeEntry)
    }

    @Test
    fun `a hard link is still refused outright`() {
        val archive = TestWcp.write(
            File(packages, "hard.wcp"),
            listOf(
                TestTarEntry("profile.json", wineProfile()),
                TestTarEntry("bin/wine", "the real binary".toByteArray()),
                TestTarEntry("bin/wineboot", typeFlag = '1', linkTarget = "bin/wine"),
            ),
        )
        val failure = installer.installBlocking(archive, store) as WcpInstallResult.UnsafeEntry
        assertTrue(failure.why.contains("hard link"))
        assertFalse(store.version(ComponentType.WINE, 1013).exists())
    }

    @Test
    fun `a device node is still refused`() {
        val archive = TestWcp.write(
            File(packages, "dev.wcp"),
            listOf(
                TestTarEntry("profile.json", TestWcp.profileJson().toByteArray()),
                TestTarEntry("dev/null", ByteArray(0), typeFlag = '3'),
            ),
        )
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.UnsafeEntry)
    }

    @Test
    fun `the link guard accepts what the Wine package contains and rejects every escape`() {
        val destination = temp.newFolder("dest")

        assertEquals(
            File(destination, "bin${File.separator}wine").canonicalFile,
            installer.resolveLink(destination, "bin/wineboot", "wine"),
        )
        assertEquals(
            File(destination, "lib${File.separator}libwine.so").canonicalFile,
            installer.resolveLink(destination, "bin/libwine.so", "../lib/libwine.so"),
        )
        // `.` is the link's own directory, not the extraction root.
        assertEquals(
            File(destination, "bin${File.separator}wine").canonicalFile,
            installer.resolveLink(destination, "bin/wineboot", "./wine"),
        )
        assertEquals(
            File(destination, "wine").canonicalFile,
            installer.resolveLink(destination, "wineboot", "./wine"),
        )

        assertNull(installer.resolveLink(destination, "bin/wineboot", ""))
        assertNull(installer.resolveLink(destination, "bin/wineboot", "  "))
        assertNull(installer.resolveLink(destination, "bin/wineboot", "/etc/passwd"))
        assertNull(installer.resolveLink(destination, "bin/wineboot", "C:/windows/bad.dll"))
        assertNull(installer.resolveLink(destination, "bin/wineboot", """..\..\escaped"""))
        assertNull(installer.resolveLink(destination, "bin/wineboot", "../../escaped"))
        assertNull(installer.resolveLink(destination, "bin/wineboot", "../wine/../../escaped"))
        assertNull(installer.resolveLink(destination, "wineboot", "../escaped"))
        // A link to the extraction root itself names nothing to link to.
        assertNull(installer.resolveLink(destination, "bin/wineboot", ".."))
    }

    /** A `.wcp` whose only link is `bin/wineboot -> [target]`. */
    private fun installLink(target: String): WcpInstallResult {
        val archive = TestWcp.write(
            File(packages, "link-${target.hashCode()}.wcp"),
            listOf(
                TestTarEntry("profile.json", wineProfile()),
                TestTarEntry("bin/wine", "the real binary".toByteArray()),
                TestTarEntry("bin/wineboot", typeFlag = '2', linkTarget = target),
            ),
        )
        return installer.installBlocking(archive, store)
    }

    private fun wineProfile(): ByteArray = TestWcp.profileJson(
        type = "Wine",
        versionName = "10.13",
        versionCode = 1013,
        name = "Wine 10.13 (canoe)",
        files = listOf("bin/wine"),
    ).toByteArray()

    /**
     * Whether this JVM may create a symlink at all.
     *
     * Windows refuses without the create-symlink privilege, which is why the
     * installer falls back to copying the target. The observable outcome — the
     * name resolves to the target's bytes — is asserted either way; only the
     * mechanism is conditional.
     */
    private val symlinksSupported: Boolean by lazy {
        runCatching {
            val probe = temp.newFolder("symlink-probe")
            File(probe, "target").writeText("x")
            Files.createSymbolicLink(File(probe, "link").toPath(), java.nio.file.Paths.get("target"))
            true
        }.getOrDefault(false)
    }

    // — checksums -------------------------------------------------------------

    @Test
    fun `a mismatched sidecar refuses the install and writes nothing`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive, digest = "0".repeat(64))

        val failure = installer.installBlocking(archive, store)
            as WcpInstallResult.ChecksumMismatch
        assertEquals("0".repeat(64), failure.expected)
        assertFalse(dxvk.exists())
    }

    @Test
    fun `a matching sidecar installs and records that it was verified`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive)

        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertTrue(installed.checksumVerified)
    }

    @Test
    fun `an explicitly supplied digest overrides the sidecar`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive)

        val failure = installer.installBlocking(archive, store, expectedSha256 = "f".repeat(64))
        assertTrue(failure is WcpInstallResult.ChecksumMismatch)
    }

    @Test
    fun `no sidecar installs but says so, rather than claiming verification`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        val installed = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertFalse(installed.checksumVerified)
        assertTrue(installed.summary.contains("checksum not verified"))
    }

    @Test
    fun `the sidecar is read in sha256sum format`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        TestWcp.writeSidecar(archive)
        assertEquals(TestWcp.sha256(archive), installer.readSidecar(archive))
    }

    // — free space ------------------------------------------------------------

    @Test
    fun `an install that will not fit is refused before anything is extracted`() {
        // 40 MB free against a 4 MiB package. Wine is 63 MB compressed and
        // 912 MB unpacked, so the archive's own size is the only figure
        // available before extracting and it is multiplied by the worst
        // measured expansion. The number the user is shown is one they can act
        // on; an IOException nine tenths of the way through is not.
        val cramped = WcpInstaller(json) { 40L * 1024 * 1024 }
        val archive = TestWcp.write(
            File(packages, "wine.wcp"),
            listOf(
                TestTarEntry("profile.json", wineProfile()),
                TestTarEntry("bin/wine", ByteArray(4 * 1024 * 1024)),
            ),
        )

        val failure = cramped.installBlocking(archive, store) as WcpInstallResult.InsufficientSpace
        assertEquals(40L * 1024 * 1024, failure.freeBytes)
        assertTrue(failure.requiredBytes > failure.freeBytes)
        assertTrue(failure.summary.contains("40 MB free"))
        assertFalse("nothing was extracted, so the figure is not a floor", failure.partway)
        assertFalse(failure.summary.contains("at least"))
        assertFalse(store.version(ComponentType.WINE, 1013).exists())
        assertEquals(emptyList<File>(), store.staging.listFiles()?.toList().orEmpty())
    }

    @Test
    fun `a package that fits is not refused`() {
        val roomy = WcpInstaller(json) { 8L * 1024 * 1024 * 1024 }
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        assertTrue(roomy.installBlocking(archive, store) is WcpInstallResult.Installed)
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

        val failure = installer.installBlocking(archive, store) as WcpInstallResult.UnsafeEntry
        assertEquals("../../escaped.so", failure.entryName)
        assertFalse(dxvk.exists())
        assertFalse(File(store.root, "escaped.so").exists())
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
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.UnsafeEntry)
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
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.UnsafeEntry)
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

    // — the shared store ------------------------------------------------------

    @Test
    fun `installing a version that is already there extracts nothing and succeeds`() {
        val archive = TestWcp.standard(File(packages, "dxvk.wcp"))
        val once = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertFalse(once.reused)

        val marker = File(dxvk, "x64/dxgi.dll")
        val stamp = marker.lastModified()

        val twice = installer.installBlocking(archive, store) as WcpInstallResult.Installed
        assertTrue("the same version is the same bytes", twice.reused)
        assertEquals(once.directory, twice.directory)
        assertEquals(stamp, marker.lastModified())
        assertTrue(twice.summary.contains("already in the shared store"))
    }

    @Test
    fun `a different version of the same type is installed alongside, not over`() {
        val old = TestWcp.standard(
            File(packages, "dxvk-2.7.1.wcp"),
            versionName = "2.7.1",
            versionCode = 20701,
            payload = mapOf("x64/dxgi.dll" to "old"),
        )
        val new = TestWcp.standard(
            File(packages, "dxvk-2.8.0.wcp"),
            versionName = "2.8.0",
            versionCode = 20800,
            payload = mapOf("x64/dxgi.dll" to "new"),
        )
        installer.installBlocking(old, store)
        installer.installBlocking(new, store)

        // Two containers on two DXVK builds both work.
        assertEquals("old", File(store.version(ComponentType.DXVK, 20701), "x64/dxgi.dll").readText())
        assertEquals("new", File(store.version(ComponentType.DXVK, 20800), "x64/dxgi.dll").readText())
        assertEquals(listOf(20800, 20701), store.versions(ComponentType.DXVK))
    }

    @Test
    fun `re-installing one version leaves no stale file from a failed earlier attempt`() {
        // Same versionCode, different contents: only reachable by clearing the
        // version out first, which is what a re-download after corruption does.
        val first = TestWcp.standard(
            File(packages, "dxvk-a.wcp"),
            payload = mapOf("x64/dxgi.dll" to "old", "x64/gone.dll" to "removed next build"),
        )
        installer.installBlocking(first, store)
        assertTrue(File(dxvk, "x64/gone.dll").isFile)
        dxvk.deleteRecursively()

        val second = TestWcp.standard(
            File(packages, "dxvk-b.wcp"),
            payload = mapOf("x64/dxgi.dll" to "new"),
        )
        installer.installBlocking(second, store)
        assertEquals("new", File(dxvk, "x64/dxgi.dll").readText())
        assertFalse(File(dxvk, "x64/gone.dll").exists())
    }

    // — malformed packages ----------------------------------------------------

    @Test
    fun `an unknown package type is refused by name`() {
        // Box64 is a real Winlator type this app deliberately cannot load.
        val archive = TestWcp.standard(File(packages, "box64.wcp"), type = "Box64")
        val failure = installer.installBlocking(archive, store) as WcpInstallResult.UnknownType
        assertEquals("Box64", failure.wire)
        assertFalse(store.root.listFiles()?.isNotEmpty() ?: false)
    }

    @Test
    fun `an archive with no profile is malformed`() {
        val archive = TestWcp.write(
            File(packages, "bare.wcp"),
            listOf(TestTarEntry("x64/dxgi.dll", "no manifest".toByteArray())),
        )
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.Malformed)
    }

    @Test
    fun `an unparseable profile is malformed rather than a crash`() {
        val archive = TestWcp.write(
            File(packages, "broken.wcp"),
            listOf(TestTarEntry("profile.json", "{not json".toByteArray())),
        )
        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.Malformed)
    }

    @Test
    fun `a missing file is reported as missing`() {
        val result = installer.installBlocking(File(packages, "nope.wcp"), store)
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

        assertTrue(installer.installBlocking(archive, store) is WcpInstallResult.Malformed)
    }
}
