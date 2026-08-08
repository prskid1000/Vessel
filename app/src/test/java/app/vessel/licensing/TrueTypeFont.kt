package app.vessel.licensing

import java.io.File

/**
 * Just enough of the OpenType format to ask a font file what it is.
 *
 * `res/font/inter_variable.ttf` is 880 KB of binary that no test can read by
 * looking at it, and the honest failure mode of "bundle Inter" is a file that is
 * *called* Inter. The `name` and `fvar` tables answer the two questions that
 * matter — which family this is, and whether it is actually variable — from the
 * font's own bytes rather than from its filename or its digest.
 *
 * Three tables, big-endian, offsets relative to the file:
 *
 * ```
 * 0x00  sfntVersion   uint32   0x00010000 for TrueType outlines
 * 0x04  numTables     uint16
 * 0x0C  table records          tag[4] checksum offset length, 16 bytes each
 * ```
 */
class TrueTypeFont(bytes: ByteArray) {

    constructor(file: File) : this(file.readBytes())

    private val data = bytes
    private val tables: Map<String, Int> = buildMap {
        require(data.size > 12) { "not a font: ${data.size} bytes" }
        val count = data.u16(4)
        for (i in 0 until count) {
            val record = 12 + 16 * i
            put(String(data, record, 4, Charsets.ISO_8859_1), data.i32(record + 8))
        }
    }

    /** One variation axis: its tag and the range it may be set to. */
    data class Axis(val tag: String, val minimum: Float, val default: Float, val maximum: Float)

    /** `name` table entries, by name ID, preferring the Windows platform records. */
    private val names: Map<Int, String> = buildMap {
        val table = tables["name"] ?: return@buildMap
        val count = data.u16(table + 2)
        val storage = table + data.u16(table + 4)
        for (i in 0 until count) {
            val record = table + 6 + 12 * i
            val platform = data.u16(record)
            val nameId = data.u16(record + 6)
            val length = data.u16(record + 8)
            val offset = data.u16(record + 10)
            // Platform 3 is Windows, whose strings are UTF-16BE. Platform 1
            // (Macintosh) records carry the same values in MacRoman and are
            // skipped rather than decoded wrongly.
            if (platform != 3 || containsKey(nameId)) continue
            put(nameId, String(data, storage + offset, length, Charsets.UTF_16BE))
        }
    }

    /** Name ID 0. */
    val copyright: String? get() = names[0]

    /** Name ID 1 — the family a font matcher keys on. */
    val family: String? get() = names[1]

    /** Name ID 5. */
    val version: String? get() = names[5]

    /** The `fvar` axes, empty when the font is static rather than variable. */
    val axes: List<Axis> by lazy {
        val table = tables["fvar"] ?: return@lazy emptyList()
        val axesOffset = data.u16(table + 4)
        val axisCount = data.u16(table + 8)
        val axisSize = data.u16(table + 10)
        (0 until axisCount).map { i ->
            val at = table + axesOffset + i * axisSize
            Axis(
                tag = String(data, at, 4, Charsets.ISO_8859_1),
                minimum = data.fixed(at + 4),
                default = data.fixed(at + 8),
                maximum = data.fixed(at + 12),
            )
        }
    }

    /**
     * PANOSE byte 3 (`bProportion`) out of `OS/2`. 9 means monospaced.
     *
     * The one metadata field that answers DESIGN.md's actual complaint — that
     * "mono does not read as monospaced" — rather than restating the filename.
     */
    val panoseProportion: Int?
        get() = tables["OS/2"]?.let { (data[it + 32 + 3].toInt() and 0xFF) }

    private fun ByteArray.u16(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.i32(at: Int): Int =
        (u16(at) shl 16) or u16(at + 2)

    /** F2Dot14 is not used for axis values; axis ranges are Fixed (16.16). */
    private fun ByteArray.fixed(at: Int): Float = i32(at) / 65536f
}
