package app.vessel.data

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * How the tar inside a `.wcp` is compressed, and whether this app can undo it.
 *
 * **The load-bearing fact: [ZSTD] and [XZ] are not decodable here.** Android
 * ships no `zstd` and no `xz` binary, `java.util.zip` implements neither, and
 * the app's declared dependencies (`gradle/libs.versions.toml`) contain no
 * Apache Commons Compress, no `org.tukaani:xz` and no `com.github.luben:zstd-jni`
 * — okio, which arrives transitively via okhttp and DataStore, does gzip and
 * raw deflate only. Every package in `dist/` today is zstd (`28 b5 2f fd`), so
 * with the dependency set as declared **no shipped `.wcp` can be installed**.
 *
 * That is reported rather than papered over. [WcpInstaller] returns
 * [WcpInstallResult.UnsupportedCompression] naming the codec, instead of
 * failing with a corrupt-archive error that would send whoever reads it looking
 * at the wrong thing. Resolving it is a decision above this layer: either
 * `build/package_wcp.py` defaults to a codec the app can read, or a decoder
 * dependency is added deliberately.
 *
 * [NONE] and [GZIP] are decodable with what is already here, which is what
 * makes the extraction path — the traversal guard especially — testable today
 * against real archives rather than against a stub.
 */
enum class WcpCompression(
    /** What to call it in a message a person reads. */
    val label: String,
    /** Whether this app, with its current dependencies, can decode it. */
    val decodable: Boolean,
) {
    NONE("uncompressed tar", true),
    GZIP("gzip", true),
    XZ("xz", false),
    ZSTD("zstd", false),
    UNKNOWN("unrecognised", false),
    ;

    /** What it would take to read this, for the message the installer returns. */
    val requirement: String
        get() = when (this) {
            ZSTD -> "no zstd decoder is available: Android has no zstd, java.util.zip " +
                "cannot decode it, and no declared dependency provides one"
            XZ -> "no xz decoder is available: Android has no xz, java.util.zip cannot " +
                "decode it, and no declared dependency provides one"
            UNKNOWN -> "the file does not begin with a tar header or any compression " +
                "magic this app recognises"
            NONE, GZIP -> "supported"
        }
}

/**
 * Reading the container format of a `.wcp`: which codec, and the tar inside it.
 *
 * Compression is identified by magic bytes rather than by file extension. A
 * `.wcp` is downloaded content and its name is attacker-chosen; the first four
 * bytes are the only honest statement it makes about itself.
 */
internal object WcpArchive {

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

    /** Offset and value of the POSIX ustar magic, used to spot an uncompressed tar. */
    private const val USTAR_OFFSET = 257
    private const val HEADER_SIZE = 512

    fun detect(file: File): WcpCompression =
        runCatching {
            FileInputStream(file).use { stream ->
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
     * The decoded tar bytes.
     *
     * Only ever called for a [WcpCompression.decodable] codec; anything else is
     * refused by [WcpInstaller] before it gets this far, with a message that
     * names the codec.
     */
    fun open(file: File, compression: WcpCompression): InputStream {
        val raw = FileInputStream(file).buffered()
        return when (compression) {
            WcpCompression.NONE -> raw
            WcpCompression.GZIP -> GZIPInputStream(raw)
            else -> {
                raw.close()
                throw IOException("${compression.label}: ${compression.requirement}")
            }
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

    /** Type flags `1` and `2`. Never extracted — a link is a way out of the destination. */
    LINK,

    /** GNU `L`: the next entry's name, too long for the 100-byte field, held in this body. */
    LONG_NAME,

    /** PAX `x`/`g` metadata, and GNU `K` long link names. Skipped. */
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

            if (kind == TarEntryKind.LONG_NAME) {
                pendingLongName = readBody().toString(Charsets.UTF_8).trimEnd('\u0000')
                continue
            }

            val name = pendingLongName ?: shortName(header)
            return TarEntry(
                name = name,
                size = size,
                kind = kind,
                mode = parseOctal(header, MODE_OFFSET, MODE_LENGTH).toInt(),
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
        '1', '2' -> TarEntryKind.LINK
        'L' -> TarEntryKind.LONG_NAME
        'x', 'g', 'K' -> TarEntryKind.METADATA
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
