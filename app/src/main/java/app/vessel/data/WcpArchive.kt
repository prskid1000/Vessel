package app.vessel.data

import org.tukaani.xz.XZInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * How the tar inside a `.wcp` is compressed, and whether this app can undo it.
 *
 * **[XZ] is the codec that matters**: it is what `build/package_wcp.py` produces
 * and therefore what every shipped package is. Android provides no xz and
 * `java.util.zip` cannot decode it, so the app carries `org.tukaani:xz` — pure
 * Java, no native code, declared in `gradle/libs.versions.toml`. That dependency
 * is not optional: drop it and no shipped `.wcp` installs.
 *
 * [ZSTD] stays undecodable, deliberately — adding it means a native dependency
 * for packages we no longer produce. It is still *named* so an old zstd package
 * on a device fails as [WcpInstallResult.UnsupportedCompression] rather than
 * reaching the tar reader and looking like a corrupt archive.
 *
 * [NONE] and [GZIP] cost nothing to support and keep the extraction path — the
 * traversal guard especially — testable against archives a test can build with
 * the JDK alone.
 */
enum class WcpCompression(
    /** What to call it in a message a person reads. */
    val label: String,
    /** Whether this app, with its current dependencies, can decode it. */
    val decodable: Boolean,
) {
    NONE("uncompressed tar", true),
    GZIP("gzip", true),
    XZ("xz", true),
    ZSTD("zstd", false),
    UNKNOWN("unrecognised", false),
    ;

    /** What it would take to read this, for the message the installer returns. */
    val requirement: String
        get() = when (this) {
            ZSTD -> "no zstd decoder is available: Android has no zstd, java.util.zip " +
                "cannot decode it, and no declared dependency provides one — this " +
                "package predates the switch to xz and needs repackaging"
            UNKNOWN -> "the file does not begin with a tar header or any compression " +
                "magic this app recognises"
            NONE, GZIP, XZ -> "supported"
        }
}

/**
 * Reading the container format of a `.wcp`: which codec, and the tar inside it.
 *
 * Compression is identified by magic bytes rather than by file extension. A
 * `.wcp` is downloaded content and its name is attacker-chosen; the leading
 * bytes are the only honest statement it makes about itself.
 */
internal object WcpArchive {

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

    /** Offset and value of the POSIX ustar magic, used to spot an uncompressed tar. */
    private const val USTAR_OFFSET = 257
    private const val HEADER_SIZE = 512

    fun detect(file: File): WcpCompression = detect(FileWcpSource(file))

    fun detect(source: WcpSource): WcpCompression =
        runCatching {
            source.open().use { stream ->
                val head = ByteArray(HEADER_SIZE)
                val read = stream.readFullyUpTo(head)
                detect(head, read)
            }
        }.getOrElse { WcpCompression.UNKNOWN }

    /** [length] is how much of [head] was actually filled. */
    fun detect(head: ByteArray, length: Int): WcpCompression = when {
        head.startsWith(ZSTD_MAGIC, length) -> WcpCompression.ZSTD
        head.startsWith(XZ_MAGIC, length) -> WcpCompression.XZ
        head.startsWith(GZIP_MAGIC, length) -> WcpCompression.GZIP
        length >= USTAR_OFFSET + 5 && String(
            head, USTAR_OFFSET, 5, Charsets.US_ASCII,
        ) == "ustar" -> WcpCompression.NONE

        else -> WcpCompression.UNKNOWN
    }

    /**
     * The decoded tar bytes, as a stream.
     *
     * A stream and never a byte array: the Wine package is 71 MB compressed and
     * several times that expanded, and the whole point of installing on a phone
     * is that it works on one that is already short of memory. Every caller
     * copies through a fixed buffer.
     *
     * Only ever called for a [WcpCompression.decodable] codec; anything else is
     * refused by [WcpInstaller] before it gets this far, with a message that
     * names the codec. The `else` branch is the belt-and-braces case and closes
     * the file rather than leaking the descriptor.
     */
    fun open(file: File, compression: WcpCompression): InputStream =
        open(FileWcpSource(file), compression)

    /**
     * @param onRawRead called with the running total of *compressed* bytes pulled
     *   from the source. The one exact measure of how far through an archive an
     *   install is — see [CountingInputStream]. Null costs nothing.
     */
    fun open(
        source: WcpSource,
        compression: WcpCompression,
        onRawRead: ((Long) -> Unit)? = null,
    ): InputStream {
        val counted = source.open().let { if (onRawRead == null) it else CountingInputStream(it, onRawRead) }
        val raw = counted.buffered()
        return try {
            when (compression) {
                WcpCompression.NONE -> raw
                WcpCompression.GZIP -> GZIPInputStream(raw)
                // No memory limit: `xz -9` writes a 64 MiB dictionary size into
                // the block header and the decoder must be allowed to honour it,
                // or every package we ship fails with MemoryLimitException. The
                // dictionary is the one large allocation in this path and it is
                // bounded by the encoder preset, not by the file's size.
                WcpCompression.XZ -> XZInputStream(raw)
                else -> throw IOException("${compression.label}: ${compression.requirement}")
            }
        } catch (e: Throwable) {
            // XZInputStream reads and validates the stream header in its
            // constructor, so a corrupt package throws here, before anything
            // holds the stream. Without this the file descriptor leaks on every
            // bad archive. The close is swallowed so it cannot mask the real
            // reason the archive would not open.
            runCatching { raw.close() }
            throw e
        }
    }

    private fun ByteArray.startsWith(magic: ByteArray, length: Int): Boolean {
        if (length < magic.size) return false
        return magic.indices.all { this[it] == magic[it] }
    }
}

/** What a tar header's type flag says an entry is. */
internal enum class TarEntryKind {
    FILE,
    DIRECTORY,

    /**
     * Type flag `2`. Extracted, but only after [TarEntry.linkTarget] has been
     * proved to stay inside the destination — see `WcpInstaller.resolveLink`.
     *
     * The Wine package contains twelve of these, all in `bin/` and all pointing
     * at `wine` beside them. They are not aliases: Wine dispatches on `argv[0]`,
     * so `bin/wineboot` *is* how a prefix is initialised. Dropping them installs
     * a Wine that cannot be provisioned, and reports success doing it.
     */
    SYMLINK,

    /**
     * Type flag `1`. Never extracted.
     *
     * A hard link names an existing archive member by path and is a second way
     * to reach a file the extractor has already placed; nothing we publish
     * contains one, and it has no legitimate use in a `.wcp`.
     */
    HARDLINK,

    /** GNU `L`: the next entry's name, too long for the 100-byte field, held in this body. */
    LONG_NAME,

    /** GNU `K`: the next entry's *link target*, too long for the 100-byte field. */
    LONG_LINK,

    /** PAX `x`/`g` metadata. Skipped. */
    METADATA,

    /** Character device, block device, fifo. Never extracted. */
    SPECIAL,
}

/** One tar member header. [size] is the body length in bytes, before padding. */
internal data class TarEntry(
    val name: String,
    val size: Long,
    val kind: TarEntryKind,
    val mode: Int,
    /** The `linkname` field, empty for everything that is not a link. */
    val linkTarget: String = "",
)

/**
 * A tar reader, because there is not one on Android and a `.wcp` is a tar.
 *
 * Deliberately strict and deliberately small: it reads ustar and the GNU long
 * name extension, which is what `build/package_wcp.py` emits (`GNU_FORMAT`),
 * verifies every header checksum, and classifies everything else as something
 * the installer will refuse. A permissive tar reader is how archive extractors
 * grow path-traversal bugs; this one hands the installer facts and makes no
 * decisions about them.
 *
 * Not a decompressor. Tar is an archive format with no compression in it — the
 * codec question is [WcpCompression]'s and is answered before this runs.
 */
internal class TarReader(private val input: InputStream) : AutoCloseable {

    private var remaining = 0L
    private var padding = 0
    private var finished = false

    /** The next entry, or null at the end of the archive. */
    fun next(): TarEntry? {
        if (finished) return null
        skipBody()

        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        while (true) {
            val header = ByteArray(BLOCK)
            val read = input.readFullyUpTo(header)
            if (read == 0) {
                finished = true
                return null
            }
            if (read < BLOCK) throw IOException("truncated tar header ($read of $BLOCK bytes)")
            if (header.all { it == 0.toByte() }) {
                // The end-of-archive marker is two zero blocks; one is enough to stop.
                finished = true
                return null
            }
            verifyChecksum(header)

            val kind = kindOf(header[TYPE_FLAG])
            val size = parseSize(header)
            remaining = size
            padding = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()

            if (kind == TarEntryKind.LONG_LINK) {
                // GNU `K`: the next entry's link target, too long for the
                // 100-byte field. Reading it matters even though our own
                // packages have none — falling back to the truncated field
                // would build a link pointing at something that was never in
                // the archive.
                pendingLongLink = readBody().toString(Charsets.UTF_8).takeWhile { it.code != 0 }
                skipBody()
                continue
            }

            if (kind == TarEntryKind.LONG_NAME) {
                pendingLongName = readBody().toString(Charsets.UTF_8).trimEnd('\u0000')
                // readBody consumes the body but not the block padding after it,
                // and the next header must start on a block boundary. Without
                // this the read walks into the padding and reports a truncated
                // header two entries later, which is a confusing way to say
                // "long names are broken".
                skipBody()
                continue
            }

            val name = pendingLongName ?: shortName(header)
            return TarEntry(
                name = name,
                size = size,
                kind = kind,
                mode = parseOctal(header, MODE_OFFSET, MODE_LENGTH).toInt(),
                linkTarget = pendingLongLink ?: header.asciiField(LINK_OFFSET, LINK_LENGTH),
            )
        }
    }

    /** Copy the current entry's body to [out]. Valid once per entry. */
    fun copyBodyTo(out: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) throw EOFException("tar body ended ${remaining} bytes early")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** The current entry's body in memory. Only used for `profile.json` and GNU long names. */
    fun readBody(): ByteArray {
        val out = java.io.ByteArrayOutputStream(remaining.coerceAtMost(MAX_IN_MEMORY).toInt())
        if (remaining > MAX_IN_MEMORY) throw IOException("entry too large to read in memory")
        copyBodyTo(out)
        return out.toByteArray()
    }

    override fun close() = input.close()

    /** Discard whatever of the current body was not read, plus its block padding. */
    private fun skipBody() {
        input.skipExactly(remaining + padding)
        remaining = 0
        padding = 0
    }

    private fun shortName(header: ByteArray): String {
        val name = header.asciiField(NAME_OFFSET, NAME_LENGTH)
        val prefix = header.asciiField(PREFIX_OFFSET, PREFIX_LENGTH)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun kindOf(flag: Byte): TarEntryKind = when (flag.toInt().toChar()) {
        '0', '\u0000', '7' -> TarEntryKind.FILE
        '5' -> TarEntryKind.DIRECTORY
        '1' -> TarEntryKind.HARDLINK
        '2' -> TarEntryKind.SYMLINK
        'L' -> TarEntryKind.LONG_NAME
        'K' -> TarEntryKind.LONG_LINK
        'x', 'g' -> TarEntryKind.METADATA
        else -> TarEntryKind.SPECIAL
    }

    /**
     * Size as octal ASCII, or GNU base-256 when the high bit of the first byte
     * is set — which is how tar encodes a size the twelve octal digits cannot
     * hold.
     */
    private fun parseSize(header: ByteArray): Long {
        if (header[SIZE_OFFSET].toInt() and 0x80 != 0) {
            var value = (header[SIZE_OFFSET].toLong() and 0x7F)
            for (i in 1 until SIZE_LENGTH) {
                value = (value shl 8) or (header[SIZE_OFFSET + i].toLong() and 0xFF)
            }
            return value
        }
        return parseOctal(header, SIZE_OFFSET, SIZE_LENGTH)
    }

    /**
     * The header checksum: the sum of every header byte with the checksum field
     * itself read as spaces. Historic tars wrote it signed, so both are accepted
     * — but a header that matches neither is corruption and stops the read.
     */
    private fun verifyChecksum(header: ByteArray) {
        val stored = parseOctal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        var unsigned = 0L
        var signed = 0L
        for (i in header.indices) {
            val byte = if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                ' '.code.toByte()
            } else {
                header[i]
            }
            unsigned += byte.toInt() and 0xFF
            signed += byte.toInt()
        }
        if (stored != unsigned && stored != signed) {
            throw IOException("tar header checksum mismatch (stored $stored)")
        }
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var seenDigit = false
        for (i in offset until offset + length) {
            val c = header[i].toInt().toChar()
            // Octal fields are NUL- or space-padded on either side of the digits.
            if (c == '\u0000' || c == ' ') {
                if (seenDigit) break else continue
            }
            if (c < '0' || c > '7') throw IOException("tar header field is not octal")
            seenDigit = true
            value = value * 8 + (c - '0')
        }
        return value
    }

    private fun ByteArray.asciiField(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    private companion object {
        const val BLOCK = 512
        const val COPY_BUFFER = 64 * 1024

        /** `profile.json` and GNU long names only; nothing else is read whole. */
        const val MAX_IN_MEMORY = 4L * 1024 * 1024

        const val NAME_OFFSET = 0
        const val NAME_LENGTH = 100
        const val MODE_OFFSET = 100
        const val MODE_LENGTH = 8
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val TYPE_FLAG = 156
        const val LINK_OFFSET = 157
        const val LINK_LENGTH = 100
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
    }
}

/**
 * Fill [buffer] as far as the stream allows, returning how many bytes landed.
 *
 * `InputStream.readNBytes` would do this and is Java 9, but Android only gained
 * it at API 33 and this app ships to API 31.
 */
internal fun InputStream.readFullyUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}

/** Discard exactly [count] bytes, or fail. `skipNBytes` is Java 12 and unavailable here. */
internal fun InputStream.skipExactly(count: Long) {
    var left = count
    val scratch = ByteArray(8 * 1024)
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
            continue
        }
        val read = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
        if (read < 0) throw EOFException("stream ended with $left bytes left to skip")
        left -= read
    }
}
