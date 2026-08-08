package app.vessel.core

import java.io.File
import java.io.RandomAccessFile

/**
 * A program's own icon, as pixels.
 *
 * Two shapes, because the ICO format has two and collapsing them would mean
 * shipping a PNG decoder next to the one Android already has:
 *
 *  - [Pixels] is the classic form and by far the common one — a device
 *    independent bitmap inside the executable, decoded here into straight ARGB
 *    so a caller can hand it to `Bitmap.createBitmap(argb, width, height,
 *    ARGB_8888)` with nothing in between.
 *  - [Png] is the Vista-and-later form used for the 256×256 entry, where the
 *    resource is a PNG file byte for byte. Handed on untouched for
 *    `BitmapFactory.decodeByteArray`.
 *
 * [PeIconReader] prefers a [Pixels] entry when the executable has one at a
 * usable size, so [Png] is the uncommon path rather than the default.
 */
sealed interface PeIcon {

    val width: Int
    val height: Int

    /** Straight (non-premultiplied) ARGB, row-major, top row first. */
    data class Pixels(
        override val width: Int,
        override val height: Int,
        val argb: IntArray,
    ) : PeIcon {
        // Generated equals/hashCode compare IntArray by identity, which makes
        // two decodes of the same icon unequal and every test of this class a
        // lie. Overridden rather than left as a data-class default.
        override fun equals(other: Any?): Boolean =
            other is Pixels && width == other.width && height == other.height &&
                argb.contentEquals(other.argb)

        override fun hashCode(): Int =
            (width * 31 + height) * 31 + argb.contentHashCode()
    }

    /** A PNG file, exactly as it sat in the resource. */
    data class Png(
        override val width: Int,
        override val height: Int,
        val bytes: ByteArray,
    ) : PeIcon {
        override fun equals(other: Any?): Boolean =
            other is Png && width == other.width && height == other.height &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (width * 31 + height) * 31 + bytes.contentHashCode()
    }
}

/**
 * Pulls the application icon out of a PE image.
 *
 * [PeReader] answers "what was this built for?" from four reads of the header.
 * This is the other question the file browser asks — "what does it look like?" —
 * and it is a great deal further in. Reaching an icon means walking, in order:
 *
 * ```
 * optional header → data directory[2]      the resource table's RVA
 * section headers → RVA to file offset     resources are addressed as loaded
 * resource tree   → type / id / language   three levels, each a directory
 * RT_GROUP_ICON   → GRPICONDIR             the sizes this program provides
 * RT_ICON         → BITMAPINFOHEADER + …   the one we chose, as a DIB
 * ```
 *
 * Every level of that has a way of being wrong in a file that is not hostile,
 * only old or truncated, so **every failure is null**. That is the same
 * contract [PeReader] keeps with [PeArchitecture.UNKNOWN]: a program with no
 * icon, a program whose icon is in a format this does not decode, and a
 * half-downloaded file are all "we could not tell", and the tile falls back to
 * the lettered placeholder the design already specifies.
 *
 * ## The parts that are not obvious
 *
 * **An icon's `biHeight` is twice its height.** A DIB inside an ICO holds the
 * colour bitmap and a 1-bit AND mask stacked, and declares the total. Reading
 * the header at face value gets a 32×64 icon out of a 32×32 one, with the bottom
 * half black.
 *
 * **Rows are bottom-up and padded to four bytes** — both bitmaps, independently,
 * because the mask is 1 bpp and pads on its own stride.
 *
 * **A 32-bit icon whose alpha channel is entirely zero is not transparent.**
 * Plenty of real executables ship one, written by tools that filled BGR and left
 * A at zero. Taken literally the icon is invisible. Windows falls back to the
 * AND mask when that happens and so does this, because an invisible icon looks
 * exactly like a bug in the file browser.
 *
 * **`IMAGE_RESOURCE_DATA_ENTRY.OffsetToData` is an RVA**, while every other
 * offset in the resource tree is relative to the start of the resource
 * directory. Mixing those up lands somewhere plausible and reads nonsense.
 *
 * ## Bounds
 *
 * The whole file is treated as untrusted input: it is a `.exe` the user found in
 * a downloads folder. Every offset is range-checked against the file length
 * before a seek, the resource tree walk is depth-limited, and both the pixel
 * count and the resource size are capped, so a header claiming a 65535×65535
 * icon is refused rather than allocating 17 GB.
 */
object PeIconReader {

    /** `RT_ICON`, the individual images. */
    private const val RT_ICON = 3

    /** `RT_GROUP_ICON`, the directory naming which [RT_ICON] entries belong together. */
    private const val RT_GROUP_ICON = 14

    /** No sensible icon is larger than this, and it caps the allocation. */
    private const val MAX_DIMENSION = 512

    /** A single icon resource beyond this is not an icon. 4 MiB is ~1024². */
    private const val MAX_RESOURCE_BYTES = 4 * 1024 * 1024

    /** Type, id, language. A tree deeper than that is malformed. */
    private const val MAX_TREE_DEPTH = 3

    /** The first four bytes of a PNG file, in file order rather than as a word. */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    /**
     * The icon of [file] at the largest size not exceeding [maxSize], or null.
     *
     * [maxSize] defaults to 256 — the largest an ICO can describe — so the
     * caller gets the best available and downscales. A tile drawn at 44 dp on a
     * 480 dpi phone is about 110 px, so passing 128 gets the same result on
     * screen for a sixteenth of the pixels.
     */
    fun iconOf(file: File, maxSize: Int = 256): PeIcon? =
        runCatching {
            RandomAccessFile(file, "r").use { handle -> Image(handle).icon(maxSize) }
        }.getOrNull()

    /**
     * One open PE image, and the offsets worked out from its headers.
     *
     * A class rather than a chain of functions because every read after the
     * first needs the section table to translate an RVA, and threading that
     * through eight signatures is how the resource-entry offset and the data
     * entry's RVA get mixed up.
     */
    private class Image(private val handle: RandomAccessFile) {

        private val length = handle.length()

        /** `virtualAddress until virtualAddress + size` to `pointerToRawData`. */
        private val sections = mutableListOf<Section>()

        private data class Section(val rva: Long, val size: Long, val raw: Long)

        /** Start of the `.rsrc` directory, as a file offset, or -1. */
        private var resourceBase = -1L
        private var resourceSize = 0L

        init {
            readHeaders()
        }

        fun icon(maxSize: Int): PeIcon? {
            if (resourceBase < 0) return null

            // Windows takes the numerically lowest group-icon id as the
            // application icon, and so does everything that shows one in a file
            // listing. Anything else picks a different icon from the one the
            // user sees on a Windows desktop for the same file.
            val group = lowestResource(RT_GROUP_ICON) ?: return null
            val directory = read(group) ?: return null
            val chosen = chooseEntry(directory, maxSize) ?: return null

            val iconData = resourceById(RT_ICON, chosen.id) ?: return null
            val bytes = read(iconData) ?: return null
            return decode(bytes, chosen)
        }

        // --- headers ---------------------------------------------------------

        private fun readHeaders() {
            if (length < 0x40) return
            if (u16(0) != 0x5A4D) return
            val peAt = u32(0x3C)
            if (peAt <= 0 || peAt + 24 > length) return
            if (i32(peAt) != 0x00004550) return

            val sectionCount = u16(peAt + 6)
            val optionalSize = u16(peAt + 20)
            val optionalAt = peAt + 24
            if (optionalAt + optionalSize > length || sectionCount <= 0) return

            // PE32 puts the data directory 96 bytes into the optional header,
            // PE32+ 112, because eight of its fields widen to 64 bits. Getting
            // this wrong reads the wrong directory entry, not an obvious error.
            val directoriesAt = when (u16(optionalAt)) {
                0x10B -> optionalAt + 96
                0x20B -> optionalAt + 112
                else -> return
            }
            val directoryCount = u32(directoriesAt - 4)
            // Index 2 is IMAGE_DIRECTORY_ENTRY_RESOURCE.
            if (directoryCount < 3) return
            val resourceRva = u32(directoriesAt + 2 * 8)
            resourceSize = u32(directoriesAt + 2 * 8 + 4)
            if (resourceRva <= 0) return

            var at = optionalAt + optionalSize
            for (i in 0 until sectionCount) {
                if (at + 40 > length) return
                sections += Section(rva = u32(at + 12), size = u32(at + 8), raw = u32(at + 20))
                at += 40
            }
            resourceBase = offsetOf(resourceRva) ?: return
        }

        /** A relative virtual address as a file offset, or null when unmapped. */
        private fun offsetOf(rva: Long): Long? {
            for (section in sections) {
                // VirtualSize can be zero in object-file-like images; fall back
                // to accepting the section that starts at or below the address.
                val span = if (section.size > 0) section.size else 1
                if (rva >= section.rva && rva < section.rva + span) {
                    val offset = section.raw + (rva - section.rva)
                    return if (offset in 0 until length) offset else null
                }
            }
            return null
        }

        // --- the resource tree ------------------------------------------------

        /** An `IMAGE_RESOURCE_DATA_ENTRY`: where the bytes are, and how many. */
        private data class Data(val offset: Long, val size: Int)

        /**
         * The data entry for the lowest-numbered resource of [type].
         *
         * Three levels down: type, then id, then language. The first language is
         * taken — a program with the same icon in two locales has the same icon.
         */
        private fun lowestResource(type: Int): Data? {
            val typeDirectory = subdirectoryOf(resourceBase, type) ?: return null
            val firstId = entries(typeDirectory).minByOrNull { it.id } ?: return null
            return firstLeaf(firstId, depth = 1)
        }

        private fun resourceById(type: Int, id: Int): Data? {
            val typeDirectory = subdirectoryOf(resourceBase, type) ?: return null
            val entry = entries(typeDirectory).firstOrNull { it.id == id } ?: return null
            return firstLeaf(entry, depth = 1)
        }

        /** Follow subdirectories until a data entry, at most [MAX_TREE_DEPTH] deep. */
        private fun firstLeaf(entry: Entry, depth: Int): Data? {
            if (!entry.isDirectory) return dataAt(entry.target)
            if (depth >= MAX_TREE_DEPTH) return null
            val child = entries(entry.target).minByOrNull { it.id } ?: return null
            return firstLeaf(child, depth + 1)
        }

        private data class Entry(val id: Int, val isDirectory: Boolean, val target: Long)

        private fun subdirectoryOf(directory: Long, id: Int): Long? =
            entries(directory).firstOrNull { it.id == id && it.isDirectory }?.target

        /** Every id-named entry of the directory at [at]. Named entries are skipped. */
        private fun entries(at: Long): List<Entry> {
            if (at < resourceBase || at + 16 > length) return emptyList()
            val named = u16(at + 12)
            val byId = u16(at + 14)
            val total = named + byId
            if (total <= 0 || at + 16 + total * 8L > length) return emptyList()

            val out = ArrayList<Entry>(byId)
            // Named entries come first and are skipped: an icon is addressed by
            // id, and a resource named "MAINICON" as a string is a Delphi-ism
            // that the group-icon directory does not point at.
            for (i in named until total) {
                val record = at + 16 + i * 8L
                val name = u32(record)
                val target = u32(record + 4)
                val isDirectory = target and 0x80000000L != 0L
                out += Entry(
                    id = (name and 0x7FFFFFFF).toInt(),
                    isDirectory = isDirectory,
                    target = resourceBase + (target and 0x7FFFFFFF),
                )
            }
            return out
        }

        private fun dataAt(at: Long): Data? {
            if (at < resourceBase || at + 16 > length) return null
            val rva = u32(at)
            val size = u32(at + 4)
            if (size <= 0 || size > MAX_RESOURCE_BYTES) return null
            // This one field is an RVA; every other offset in the tree is
            // relative to resourceBase.
            val offset = offsetOf(rva) ?: return null
            if (offset + size > length) return null
            return Data(offset, size.toInt())
        }

        private fun read(data: Data): ByteArray? = runCatching {
            handle.seek(data.offset)
            ByteArray(data.size).also { handle.readFully(it) }
        }.getOrNull()

        // --- GRPICONDIR --------------------------------------------------------

        /** One row of the group directory: a size, and the [RT_ICON] id holding it. */
        private data class GroupEntry(val width: Int, val height: Int, val bits: Int, val id: Int)

        /**
         * The largest entry no wider than [maxSize], or the smallest if every
         * entry is bigger.
         *
         * A [PeIcon.Pixels] candidate wins a tie against a PNG one of the same
         * size, so the PNG path stays the exception. Depth breaks the remaining
         * ties: a 32×32 at 32 bpp and a 32×32 at 4 bpp are both present in most
         * older executables and only one of them is worth drawing.
         */
        private fun chooseEntry(directory: ByteArray, maxSize: Int): GroupEntry? {
            if (directory.size < 6) return null
            if (directory.le16(0) != 0 || directory.le16(2) != 1) return null
            val count = directory.le16(4)
            if (count <= 0 || 6 + count * 14 > directory.size) return null

            val entries = (0 until count).mapNotNull { i ->
                val at = 6 + i * 14
                // A zero byte means 256 — the field is one byte and 256 does not
                // fit in it, which is the format's own workaround.
                val width = directory[at].toInt() and 0xFF
                val height = directory[at + 1].toInt() and 0xFF
                GroupEntry(
                    width = if (width == 0) 256 else width,
                    height = if (height == 0) 256 else height,
                    bits = directory.le16(at + 6),
                    id = directory.le16(at + 12),
                ).takeIf { it.width in 1..MAX_DIMENSION && it.height in 1..MAX_DIMENSION }
            }
            if (entries.isEmpty()) return null

            val fitting = entries.filter { it.width <= maxSize }
            return if (fitting.isNotEmpty()) {
                fitting.maxWithOrNull(compareBy({ it.width }, { it.bits }))
            } else {
                entries.minWithOrNull(compareBy({ it.width }, { -it.bits }))
            }
        }

        // --- the icon itself ---------------------------------------------------

        private fun decode(bytes: ByteArray, entry: GroupEntry): PeIcon? {
            if (bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
                return PeIcon.Png(entry.width, entry.height, bytes)
            }
            return decodeDib(bytes)
        }

        /**
         * A `BITMAPINFOHEADER` DIB with its AND mask, as ARGB.
         *
         * `biCompression` other than `BI_RGB` is refused rather than guessed at:
         * an RLE-compressed icon exists in theory and decoding one wrongly
         * produces plausible garbage, which is worse than the letter placeholder.
         */
        private fun decodeDib(bytes: ByteArray): PeIcon.Pixels? {
            if (bytes.size < 40) return null
            val headerSize = bytes.le32(0)
            if (headerSize < 40 || headerSize > bytes.size) return null

            val width = bytes.le32(4)
            val doubledHeight = bytes.le32(8)
            val bitCount = bytes.le16(14)
            val compression = bytes.le32(16)
            if (compression != 0) return null

            // The declared height covers the colour bitmap and the AND mask
            // stacked on top of each other, so it is always even. An odd value
            // is a DIB that is not an icon and is refused rather than halved.
            if (doubledHeight <= 0 || doubledHeight % 2 != 0) return null
            val height = doubledHeight / 2
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null

            val paletteEntries = when (bitCount) {
                1, 4, 8 -> bytes.le32(32).takeIf { it in 1..(1 shl bitCount) } ?: (1 shl bitCount)
                24, 32 -> 0
                else -> return null
            }
            val paletteAt = headerSize
            val pixelsAt = paletteAt + paletteEntries * 4
            val stride = ((width * bitCount + 31) / 32) * 4
            if (pixelsAt + stride.toLong() * height > bytes.size) return null

            val palette = IntArray(paletteEntries) { i ->
                val at = paletteAt + i * 4
                // Palette entries are BGRA with the fourth byte reserved, not
                // alpha — a palettised icon's transparency is the AND mask.
                0xFF000000.toInt() or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    (bytes[at].toInt() and 0xFF)
            }

            val argb = IntArray(width * height)
            for (y in 0 until height) {
                // Bottom-up: DIB row 0 is the last row of the image.
                val row = pixelsAt + (height - 1 - y) * stride
                for (x in 0 until width) {
                    argb[y * width + x] = when (bitCount) {
                        32 -> {
                            val at = row + x * 4
                            ((bytes[at + 3].toInt() and 0xFF) shl 24) or
                                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                                (bytes[at].toInt() and 0xFF)
                        }

                        24 -> {
                            val at = row + x * 3
                            0xFF000000.toInt() or
                                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                                (bytes[at].toInt() and 0xFF)
                        }

                        8 -> palette.getOrElse(bytes[row + x].toInt() and 0xFF) { 0 }

                        4 -> {
                            val byte = bytes[row + x / 2].toInt() and 0xFF
                            val index = if (x % 2 == 0) byte shr 4 else byte and 0x0F
                            palette.getOrElse(index) { 0 }
                        }

                        else -> {
                            val byte = bytes[row + x / 8].toInt() and 0xFF
                            val index = (byte shr (7 - x % 8)) and 1
                            palette.getOrElse(index) { 0 }
                        }
                    }
                }
            }

            applyMask(bytes, argb, width, height, pixelsAt + stride * height, bitCount)
            return PeIcon.Pixels(width, height, argb)
        }

        /**
         * Apply the 1-bit AND mask, where a set bit means transparent.
         *
         * Skipped for a 32-bit icon that already carries alpha, and *not* skipped
         * for one whose alpha is uniformly zero. That second case is common in
         * the wild — tools that wrote BGR and left A alone — and taking it at
         * face value renders the icon invisible, which is indistinguishable from
         * this whole reader being broken.
         *
         * A truncated mask is not an error: some writers omit it entirely for
         * 32-bit icons. What is already decoded stands.
         */
        private fun applyMask(
            bytes: ByteArray,
            argb: IntArray,
            width: Int,
            height: Int,
            maskAt: Int,
            bitCount: Int,
        ) {
            if (bitCount == 32 && argb.any { it ushr 24 != 0 }) return

            val stride = ((width + 31) / 32) * 4
            if (maskAt + stride.toLong() * height > bytes.size) {
                // No mask, and a 32-bit image with no alpha anywhere. Opaque is
                // the only answer that shows something.
                if (bitCount == 32) for (i in argb.indices) argb[i] = argb[i] or 0xFF000000.toInt()
                return
            }
            for (y in 0 until height) {
                val row = maskAt + (height - 1 - y) * stride
                for (x in 0 until width) {
                    val bit = (bytes[row + x / 8].toInt() shr (7 - x % 8)) and 1
                    val at = y * width + x
                    argb[at] = if (bit == 1) argb[at] and 0x00FFFFFF else argb[at] or 0xFF000000.toInt()
                }
            }
        }

        // --- little-endian reads ------------------------------------------------

        private fun u16(at: Long): Int {
            handle.seek(at)
            val low = handle.read()
            val high = handle.read()
            if (low < 0 || high < 0) return -1
            return (high shl 8) or low
        }

        private fun i32(at: Long): Int {
            handle.seek(at)
            val b0 = handle.read()
            val b1 = handle.read()
            val b2 = handle.read()
            val b3 = handle.read()
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
            return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }

        /** Unsigned, because RVAs and sizes routinely exceed `Int.MAX_VALUE / 2`. */
        private fun u32(at: Long): Long = i32(at).toLong() and 0xFFFFFFFFL
    }
}

private fun ByteArray.le16(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.le32(at: Int): Int =
    (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        ((this[at + 3].toInt() and 0xFF) shl 24)
