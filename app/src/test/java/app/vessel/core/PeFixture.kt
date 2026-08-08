package app.vessel.core

/**
 * Builds a minimal PE image with a resource tree in it.
 *
 * `PeIconReader` walks five nested structures to reach a single icon, and a
 * fixture on disk would test one arrangement of them. Building the image here
 * means a test can say "two group icons, and the lower id is the one that
 * counts" or "a 32-bit image whose alpha is all zero" and get exactly that.
 *
 * The image is not loadable — there is no code, no imports and no entry point.
 * It is a header, one `.rsrc` section, and correct offsets, which is the whole
 * of what the reader looks at.
 */
object PeFixture {

    const val RT_ICON = 3
    const val RT_GROUP_ICON = 14

    /** One row of a `GRPICONDIR`: a size, and the `RT_ICON` id holding it. */
    data class Row(val width: Int, val height: Int, val bits: Int, val iconId: Int)

    /** Where the `.rsrc` section is mapped, and where its bytes sit in the file. */
    private const val RESOURCE_RVA = 0x1000
    private const val RESOURCE_RAW = 0x400
    private const val PE_AT = 0x80

    /**
     * A PE32+ image whose resources are [groups] (id to rows) and [icons]
     * (`RT_ICON` id to payload).
     */
    fun exe(groups: Map<Int, List<Row>>, icons: Map<Int, ByteArray>): ByteArray {
        val resources = buildMap<Int, Map<Int, ByteArray>> {
            if (groups.isNotEmpty()) {
                put(RT_GROUP_ICON, groups.mapValues { (_, rows) -> groupDirectory(rows, icons) })
            }
            if (icons.isNotEmpty()) put(RT_ICON, icons)
        }
        return image(resourceSection(resources))
    }

    /** A `GRPICONDIR` over [rows], sized from the payloads in [icons]. */
    private fun groupDirectory(rows: List<Row>, icons: Map<Int, ByteArray>): ByteArray {
        val out = ByteArray(6 + rows.size * 14)
        out.le16(0, 0)
        out.le16(2, 1)
        out.le16(4, rows.size)
        rows.forEachIndexed { i, row ->
            val at = 6 + i * 14
            // 256 is written as 0: the field is one byte wide.
            out[at] = (if (row.width == 256) 0 else row.width).toByte()
            out[at + 1] = (if (row.height == 256) 0 else row.height).toByte()
            out.le16(at + 4, 1)
            out.le16(at + 6, row.bits)
            out.le32(at + 8, icons[row.iconId]?.size ?: 0)
            out.le16(at + 12, row.iconId)
        }
        return out
    }

    /**
     * The `.rsrc` payload: root directory, one type directory per type, one
     * language directory per id, one data entry per resource, then the bytes.
     *
     * Laid out in that order and patched afterwards, because every directory
     * points forward.
     */
    private fun resourceSection(resources: Map<Int, Map<Int, ByteArray>>): ByteArray {
        if (resources.isEmpty()) return ByteArray(0)

        val types = resources.keys.sorted()
        var cursor = 16 + 8 * types.size
        val typeDirectoryAt = types.associateWith { type ->
            val at = cursor
            cursor += 16 + 8 * resources.getValue(type).size
            at
        }
        // One language directory per (type, id), then one data entry each.
        val languageDirectoryAt = mutableMapOf<Pair<Int, Int>, Int>()
        for (type in types) {
            for (id in resources.getValue(type).keys.sorted()) {
                languageDirectoryAt[type to id] = cursor
                cursor += 16 + 8
            }
        }
        val dataEntryAt = mutableMapOf<Pair<Int, Int>, Int>()
        for (type in types) {
            for (id in resources.getValue(type).keys.sorted()) {
                dataEntryAt[type to id] = cursor
                cursor += 16
            }
        }
        val payloadAt = mutableMapOf<Pair<Int, Int>, Int>()
        for (type in types) {
            for ((id, bytes) in resources.getValue(type).entries.sortedBy { it.key }) {
                cursor = (cursor + 3) and 3.inv()
                payloadAt[type to id] = cursor
                cursor += bytes.size
            }
        }

        val blob = ByteArray(cursor)
        directory(blob, 0, types.size)
        types.forEachIndexed { i, type ->
            val at = 16 + i * 8
            blob.le32(at, type)
            blob.le32(at + 4, typeDirectoryAt.getValue(type) or DIRECTORY_FLAG)
        }
        for (type in types) {
            val ids = resources.getValue(type).keys.sorted()
            directory(blob, typeDirectoryAt.getValue(type), ids.size)
            ids.forEachIndexed { i, id ->
                val at = typeDirectoryAt.getValue(type) + 16 + i * 8
                blob.le32(at, id)
                blob.le32(at + 4, languageDirectoryAt.getValue(type to id) or DIRECTORY_FLAG)
            }
            for (id in ids) {
                val language = languageDirectoryAt.getValue(type to id)
                directory(blob, language, 1)
                blob.le32(language + 16, 1033)
                blob.le32(language + 20, dataEntryAt.getValue(type to id))

                val entry = dataEntryAt.getValue(type to id)
                val payload = payloadAt.getValue(type to id)
                // The one field in the tree that is an RVA rather than an offset
                // from the start of the section.
                blob.le32(entry, RESOURCE_RVA + payload)
                blob.le32(entry + 4, resources.getValue(type).getValue(id).size)
                resources.getValue(type).getValue(id).copyInto(blob, payload)
            }
        }
        return blob
    }

    private const val DIRECTORY_FLAG = 0x80000000.toInt()

    /** An `IMAGE_RESOURCE_DIRECTORY` with no named entries and [ids] id entries. */
    private fun directory(blob: ByteArray, at: Int, ids: Int) {
        blob.le16(at + 12, 0)
        blob.le16(at + 14, ids)
    }

    /** DOS stub, PE signature, PE32+ optional header, and one `.rsrc` section. */
    private fun image(resources: ByteArray): ByteArray {
        val optionalSize = 112 + 16 * 8
        val out = ByteArray(RESOURCE_RAW + maxOf(resources.size, 1))

        out.le16(0, 0x5A4D)
        out.le32(0x3C, PE_AT)
        out.le32(PE_AT, 0x00004550)
        out.le16(PE_AT + 4, 0xAA64)        // Machine: ARM64
        out.le16(PE_AT + 6, 1)             // NumberOfSections
        out.le16(PE_AT + 20, optionalSize)

        val optional = PE_AT + 24
        out.le16(optional, 0x20B)          // PE32+
        out.le32(optional + 108, 16)       // NumberOfRvaAndSizes
        // DataDirectory[2] — IMAGE_DIRECTORY_ENTRY_RESOURCE.
        out.le32(optional + 112 + 2 * 8, RESOURCE_RVA)
        out.le32(optional + 112 + 2 * 8 + 4, resources.size)

        val section = optional + optionalSize
        ".rsrc".toByteArray().copyInto(out, section)
        out.le32(section + 8, resources.size)   // VirtualSize
        out.le32(section + 12, RESOURCE_RVA)
        out.le32(section + 16, resources.size)  // SizeOfRawData
        out.le32(section + 20, RESOURCE_RAW)

        resources.copyInto(out, RESOURCE_RAW)
        return out
    }

    // --- icon payloads --------------------------------------------------------

    /**
     * A `BITMAPINFOHEADER` icon: [bitCount]-deep colour data plus a 1-bit mask.
     *
     * [pixel] is called with top-left-origin coordinates and returns whatever the
     * depth needs — an ARGB int at 32, a BGR int at 24, a palette index below
     * that. [mask] returns 1 for transparent. The rows are written bottom-up and
     * padded here, so a test never has to think about either.
     */
    fun dib(
        width: Int,
        height: Int,
        bitCount: Int,
        palette: List<Int> = emptyList(),
        compression: Int = 0,
        withMask: Boolean = true,
        mask: (x: Int, y: Int) -> Int = { _, _ -> 0 },
        pixel: (x: Int, y: Int) -> Int,
    ): ByteArray {
        val stride = ((width * bitCount + 31) / 32) * 4
        val maskStride = ((width + 31) / 32) * 4
        val paletteBytes = palette.size * 4
        val maskBytes = if (withMask) maskStride * height else 0
        val out = ByteArray(40 + paletteBytes + stride * height + maskBytes)

        out.le32(0, 40)
        out.le32(4, width)
        out.le32(8, height * 2)
        out.le16(12, 1)
        out.le16(14, bitCount)
        out.le32(16, compression)
        out.le32(32, palette.size)

        palette.forEachIndexed { i, colour ->
            val at = 40 + i * 4
            out[at] = (colour and 0xFF).toByte()
            out[at + 1] = ((colour shr 8) and 0xFF).toByte()
            out[at + 2] = ((colour shr 16) and 0xFF).toByte()
        }

        val pixelsAt = 40 + paletteBytes
        for (y in 0 until height) {
            val row = pixelsAt + (height - 1 - y) * stride
            for (x in 0 until width) {
                val value = pixel(x, y)
                when (bitCount) {
                    32 -> {
                        out[row + x * 4] = (value and 0xFF).toByte()
                        out[row + x * 4 + 1] = ((value shr 8) and 0xFF).toByte()
                        out[row + x * 4 + 2] = ((value shr 16) and 0xFF).toByte()
                        out[row + x * 4 + 3] = ((value ushr 24) and 0xFF).toByte()
                    }

                    24 -> {
                        out[row + x * 3] = (value and 0xFF).toByte()
                        out[row + x * 3 + 1] = ((value shr 8) and 0xFF).toByte()
                        out[row + x * 3 + 2] = ((value shr 16) and 0xFF).toByte()
                    }

                    8 -> out[row + x] = value.toByte()

                    4 -> {
                        val at = row + x / 2
                        val existing = out[at].toInt() and 0xFF
                        out[at] = if (x % 2 == 0) {
                            ((value shl 4) or (existing and 0x0F)).toByte()
                        } else {
                            ((existing and 0xF0) or (value and 0x0F)).toByte()
                        }
                    }

                    1 -> {
                        val at = row + x / 8
                        val bit = 7 - x % 8
                        val existing = out[at].toInt() and 0xFF
                        out[at] = (existing or ((value and 1) shl bit)).toByte()
                    }
                }
            }
        }

        if (withMask) {
            val maskAt = pixelsAt + stride * height
            for (y in 0 until height) {
                val row = maskAt + (height - 1 - y) * maskStride
                for (x in 0 until width) {
                    if (mask(x, y) == 1) {
                        val at = row + x / 8
                        out[at] = ((out[at].toInt() and 0xFF) or (1 shl (7 - x % 8))).toByte()
                    }
                }
            }
        }
        return out
    }

    /** A payload that is a PNG file rather than a DIB, as the 256×256 entry is. */
    fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + ByteArray(32) { it.toByte() }
}

private fun ByteArray.le16(at: Int, value: Int) {
    this[at] = (value and 0xFF).toByte()
    this[at + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.le32(at: Int, value: Int) {
    this[at] = (value and 0xFF).toByte()
    this[at + 1] = ((value shr 8) and 0xFF).toByte()
    this[at + 2] = ((value shr 16) and 0xFF).toByte()
    this[at + 3] = ((value ushr 24) and 0xFF).toByte()
}
