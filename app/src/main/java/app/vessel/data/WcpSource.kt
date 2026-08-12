package app.vessel.data

import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where a `.wcp`'s bytes come from.
 *
 * There are two answers and they are not the same shape. A downloaded package is
 * a file with a `.sha256` sidecar beside it; a bundled one is an entry in the
 * APK, which has no directory to put a sidecar in and no `File` to hand a
 * `FileInputStream`. Everything else about installing them is identical — the
 * traversal guard, the staging swap, the free-space check — so the difference
 * stops here rather than becoming a second installer.
 *
 * **[open] must be callable more than once.** [WcpInstaller] makes up to three
 * passes over an archive (digest, `profile.json`, extract) and each one starts
 * from the beginning. Both implementations reopen rather than seek: an
 * `XZInputStream` cannot be rewound, and an asset stream cannot be either.
 */
interface WcpSource {

    /** The file name, `wine-proton-11.0-canoe.wcp`. Used in every message about it. */
    val name: String

    /**
     * The compressed size, or a negative number when the source cannot say.
     *
     * Only [WcpInstaller]'s up-front space estimate reads it, and that estimate is
     * skipped rather than guessed when the answer is unknown — the running check
     * during extraction is the one that cannot be fooled anyway.
     */
    val sizeBytes: Long

    fun open(): InputStream

    /**
     * The digest published with the package, or null when nothing published one.
     *
     * Null means "no claim was made", not "the check passed". [WcpInstaller]
     * records that distinction in
     * [WcpInstallResult.Installed.checksumVerified] and says so in its summary.
     */
    fun publishedSha256(): String?
}

/** The registry's id for a package, which is its file name without `.wcp`. */
fun WcpSource.packageId(): String = name.removeSuffix(WCP_SUFFIX)

/** A `.wcp` on the filesystem — a download, or `dist/` pushed by a device script. */
class FileWcpSource(private val file: File) : WcpSource {

    override val name: String get() = file.name

    override val sizeBytes: Long get() = file.length()

    override fun open(): InputStream = file.inputStream()

    /**
     * `build/package_wcp.py` writes `<hex>  <filename>\n` beside the archive, the
     * `sha256sum` format, so the first whitespace-delimited token is the digest.
     */
    override fun publishedSha256(): String? {
        val sidecar = File(file.parentFile, file.name + SIDECAR_SUFFIX)
        if (!sidecar.isFile) return null
        return runCatching { sidecar.readText().trim().substringBefore(' ').trim() }
            .getOrNull()
            ?.takeIf { it.length == SHA256_HEX_LENGTH }
    }

    /** The file itself, for the callers that still take one. */
    val asFile: File get() = file

    private companion object {
        const val SIDECAR_SUFFIX = ".sha256"
        const val SHA256_HEX_LENGTH = 64
    }
}

/**
 * A `.wcp` shipped inside the APK, under `assets/components/`.
 *
 * Only the `sideload` flavour has any. The `play` flavour ships none, because
 * Play policy forbids executable code outside the package and these are nothing
 * but executable code — so there the asset directory is simply absent and
 * [BundledComponents] finds nothing, which is the correct behaviour rather than a
 * special case.
 *
 * **No digest.** The bytes are inside an APK the platform verified against its
 * signature before it would install it, and re-hashing 100 MB on first run to
 * re-establish what package installation already established costs the user
 * seconds and proves nothing new. A download has no such guarantee and keeps its
 * sidecar check. Corruption that somehow got past the platform still does not get
 * silently installed: the xz stream carries CRC64s and every tar header carries a
 * checksum, and both are verified as the archive is read.
 */
class AssetWcpSource(
    private val assets: AssetManager,
    /** The full asset path, `components/wine-proton-11.0-canoe.wcp`. */
    private val path: String,
) : WcpSource {

    override val name: String get() = path.substringAfterLast('/')

    /**
     * Read once from the asset's descriptor.
     *
     * This only answers because `.wcp` is in `androidResources.noCompress`: a
     * stored asset has a real offset and length inside the APK, while a deflated
     * one throws `FileNotFoundException: This file can not be opened as a file
     * descriptor; it is probably compressed`. The `-1` fallback is honest rather
     * than defensive — if the packaging rule is ever lost, the space estimate goes
     * away and the extraction-time check carries on working.
     */
    override val sizeBytes: Long by lazy {
        runCatching { assets.openFd(path).use { it.length } }.getOrDefault(-1L)
    }

    override fun open(): InputStream = assets.open(path, AssetManager.ACCESS_STREAMING)

    override fun publishedSha256(): String? = null

    companion object {
        /** Where the packages live inside the APK. */
        const val DIRECTORY: String = "components"

        /**
         * Every `.wcp` under [DIRECTORY], or empty when there is no such directory.
         *
         * `AssetManager.list` returns an empty array for a missing directory on
         * some platform versions and throws on others, so both are folded into
         * "there are none" — which is exactly what the `play` flavour is.
         */
        fun listAll(assets: AssetManager): List<AssetWcpSource> =
            runCatching { assets.list(DIRECTORY).orEmpty() }
                .getOrDefault(emptyArray())
                .filter { it.endsWith(WCP_SUFFIX) }
                .sorted()
                .map { AssetWcpSource(assets, "$DIRECTORY/$it") }
    }
}

/** The archive extension, in the one place both sources and the catalogue read it. */
const val WCP_SUFFIX: String = ".wcp"

/**
 * Counts the compressed bytes pulled through it.
 *
 * The only honest basis for a determinate progress bar over a `.wcp`. The
 * *unpacked* total is unknown until the archive has been read — `profile.json`
 * lists the files but not their sizes — whereas the compressed length is exact
 * and known before anything starts, so "how much of this archive has been
 * consumed" is a real fraction and "how many of the 4 000 files are done" is a
 * guess dressed as one.
 */
internal class CountingInputStream(
    private val delegate: InputStream,
    private val onRead: (Long) -> Unit,
) : InputStream() {

    private var total = 0L

    override fun read(): Int {
        val byte = delegate.read()
        if (byte >= 0) advance(1)
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) advance(read.toLong())
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = delegate.skip(n)
        if (skipped > 0) advance(skipped)
        return skipped
    }

    override fun available(): Int = delegate.available()

    @Throws(IOException::class)
    override fun close() = delegate.close()

    private fun advance(count: Long) {
        total += count
        onRead(total)
    }
}
