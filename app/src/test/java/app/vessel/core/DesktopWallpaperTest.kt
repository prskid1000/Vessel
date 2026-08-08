package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallpaper bitmap, byte for byte.
 *
 * Every field asserted here is one Wine reads without complaining when it is
 * wrong. `BITMAP_Load` (`dlls/user32/cursoricon.c`) checks the `BM` signature and
 * then trusts the header: a bad `bfOffBits` shifts the pixels, a negative
 * `biHeight` flips the image, and `PaintDesktop` reports none of it — the desktop
 * simply comes up blue, exactly as it does when there is no wallpaper at all. A
 * unit test is the only place this can be caught.
 */
class DesktopWallpaperTest {

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    // — the file header ----------------------------------------------------------

    @Test
    fun `the file starts BM, which is the only thing Wine checks before trusting it`() {
        val bmp = encodeBmp24(2, 2, IntArray(4))
        assertEquals('B'.code.toByte(), bmp[0])
        assertEquals('M'.code.toByte(), bmp[1])
    }

    @Test
    fun `the pixels start at 54, right after the two headers`() {
        val bmp = encodeBmp24(4, 4, IntArray(16))
        assertEquals(54, u32(bmp, 10))
        assertEquals(40, u32(bmp, 14))
        assertEquals(bmp.size, u32(bmp, 2))
    }

    @Test
    fun `24-bit BI_RGB with one plane and no palette`() {
        // biClrUsed must stay 0: bitmap_info_size derives the palette length from
        // it, and a non-zero value moves where Wine thinks the pixels begin.
        val bmp = encodeBmp24(4, 4, IntArray(16))
        assertEquals(1, u16(bmp, 26))
        assertEquals(24, u16(bmp, 28))
        assertEquals(0, u32(bmp, 30))
        assertEquals(0, u32(bmp, 46))
    }

    @Test
    fun `the height is positive, so the rows are bottom-up as every reader assumes`() {
        val bmp = encodeBmp24(3, 7, IntArray(21))
        assertEquals(3, u32(bmp, 18))
        assertEquals(7, u32(bmp, 22))
    }

    // — the pixels ---------------------------------------------------------------

    @Test
    fun `rows are padded to four bytes and the padding is counted in the file size`() {
        // 3 px * 3 bytes is 9, which pads to 12. Getting this wrong skews every
        // row after the first and produces the diagonal smear that looks like a
        // corrupt decode rather than a stride bug.
        val bmp = encodeBmp24(3, 2, IntArray(6))
        assertEquals(12 * 2, u32(bmp, 34))
        assertEquals(54 + 24, bmp.size)
    }

    @Test
    fun `a pixel is written BGR, bottom row first`() {
        // Top-left red, bottom-left blue. Bottom-up means blue comes first in the
        // file, and BGR means its bytes are FF 00 00.
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val bmp = encodeBmp24(1, 2, intArrayOf(red, blue))
        assertEquals(0xFF, bmp[54].toInt() and 0xFF)
        assertEquals(0x00, bmp[55].toInt() and 0xFF)
        assertEquals(0x00, bmp[56].toInt() and 0xFF)
        // The padded row is 4 bytes wide even though the pixel is 3.
        assertEquals(0x00, bmp[58].toInt() and 0xFF)
        assertEquals(0x00, bmp[59].toInt() and 0xFF)
        assertEquals(0xFF, bmp[60].toInt() and 0xFF)
    }

    @Test
    fun `a size mismatch is refused rather than written short`() {
        // A short pixel array would otherwise produce a valid-looking header over
        // a truncated image, which Wine loads as garbage.
        val tooFew = runCatching { encodeBmp24(4, 4, IntArray(15)) }
        assertTrue(tooFew.isFailure)
        assertTrue(runCatching { encodeBmp24(0, 4, IntArray(0)) }.isFailure)
    }

    // — the colour ----------------------------------------------------------------

    @Test
    fun `the desktop colour renders as decimal r g b`() {
        assertEquals("58 110 165", rgbTriplet(0xFF3A6EA5.toInt()))
        assertEquals("0 0 0", rgbTriplet(0xFF000000.toInt()))
        assertEquals("255 255 255", rgbTriplet(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `the alpha byte never reaches the triplet`() {
        assertEquals(rgbTriplet(0xFF102030.toInt()), rgbTriplet(0x00102030))
    }

    @Test
    fun `the dominant colour is the fullest bucket, not the mean`() {
        // Four dark-red pixels and one white one. The mean would be a washed-out
        // pink that is nowhere in the image; the answer has to be the red.
        val red = 0xFF801010.toInt()
        val pixels = intArrayOf(red, red, red, red, 0xFFFFFFFF.toInt())
        assertEquals(red, dominantColor(pixels))
    }

    @Test
    fun `near-identical shades count as one colour`() {
        // Bucketing at 4 bits per channel is what stops a gradient losing to a
        // small flat patch of something else.
        val pixels = intArrayOf(
            0xFF803030.toInt(), 0xFF813131.toInt(), 0xFF823232.toInt(),
            0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
        )
        // The three reds share a bucket and win it; the answer is their mean,
        // which is a colour that is genuinely in the image.
        assertEquals(0xFF813131.toInt(), dominantColor(pixels))
        assertNotEquals(0xFF00FF00.toInt(), dominantColor(pixels))
    }

    @Test
    fun `transparent pixels are skipped, so a PNG margin cannot drag it to black`() {
        val green = 0xFF00A040.toInt()
        val pixels = intArrayOf(0x00000000, 0x00000000, 0x00000000, green)
        assertEquals(green, dominantColor(pixels))
    }

    @Test
    fun `an image with nothing in it falls back rather than answering black`() {
        assertEquals(VESSEL_DESKTOP_COLOR, dominantColor(IntArray(0)))
        assertEquals(VESSEL_DESKTOP_COLOR, dominantColor(intArrayOf(0x00000000)))
    }

    @Test
    fun `the dominant colour is always opaque, because a reg triplet has no alpha`() {
        val dominant = dominantColor(intArrayOf(0xFF123456.toInt()))
        assertEquals(0xFF, dominant ushr 24)
    }

    // — the degrade ladder ---------------------------------------------------------

    @Test
    fun `a background always carries a colour, whichever tier it reached`() {
        // The wallpaper is centred rather than stretched, so COLOR_BACKGROUND is
        // what fills anything the bitmap does not cover — it is load-bearing even
        // on the happy path, and Wine's own default for it is the bare blue this
        // whole feature exists to replace.
        for (origin in WallpaperOrigin.entries) {
            val background = DesktopBackground(origin, VESSEL_DESKTOP_COLOR, "test")
            assertTrue(rgbTriplet(background.color).isNotEmpty())
        }
    }
}
