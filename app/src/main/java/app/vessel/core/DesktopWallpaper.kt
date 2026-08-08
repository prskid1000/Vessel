package app.vessel.core

/**
 * Where the Windows desktop's background came from.
 *
 * Ordered by preference, and the order is a promise: Vessel never presents a
 * lower tier as if it were a higher one. A live wallpaper has no still frame to
 * read, and a `WallpaperManager` call that answers with the *stock* image rather
 * than the user's is the same failure wearing a picture — both land on
 * [DESKTOP_COLOR] with [DesktopBackground.summary] saying so.
 */
enum class WallpaperOrigin {
    /** The phone's own home-screen wallpaper, read from `WallpaperManager`. */
    SYSTEM_WALLPAPER,

    /** A still the user put in the container directory themselves. */
    USER_IMAGE,

    /** No image. The desktop is one flat colour, and the colour is honest. */
    DESKTOP_COLOR,
}

/**
 * What the desktop background resolved to for one session.
 *
 * [color] is always set, even when an image was written: Wine centres the
 * wallpaper without stretching it (see [encodeBmp24]) so anything the bitmap does
 * not reach is painted with `COLOR_BACKGROUND`, and leaving that at Wine's own
 * `RGB(58, 110, 165)` is the bare blue this feature exists to replace.
 *
 * [attempts] is the evidence rather than a debug aid. Which `WallpaperManager`
 * entry point works is a per-device, per-targetSdk question that cannot be
 * answered from documentation, so every call is recorded with what it answered
 * and the whole list goes into the session log.
 */
data class DesktopBackground(
    val origin: WallpaperOrigin,
    /** ARGB. `HKCU\Control Panel\Colors` → `Background`, via [rgbTriplet]. */
    val color: Int,
    /** One sentence, shown on the Preparing checklist row and logged. */
    val summary: String,
    /** Every API tried, in order, and what each one answered. */
    val attempts: List<String> = emptyList(),
)

/**
 * The desktop colour Vessel falls back to when nothing about the wallpaper could
 * be read at all — Nocturne's window ground, `docs/DESIGN.md`.
 *
 * Deliberately not a guess at what the wallpaper might look like. A colour lifted
 * from the app's own palette is visibly a Vessel decision; a plausible teal would
 * read as a wallpaper that failed to load.
 */
const val VESSEL_DESKTOP_COLOR: Int = 0xFF161826.toInt()

/**
 * `r g b`, decimal and space-separated, which is the only form Wine parses.
 *
 * `get_rgb_entry` (`dlls/win32u/sysparams.c`) reads the value with three
 * `wcstoul` calls and bails the moment one of them stops at a NUL, so `#161826`
 * or a comma-separated triplet leaves `COLOR_BACKGROUND` at its built-in blue
 * with nothing logged.
 */
fun rgbTriplet(argb: Int): String =
    "${(argb shr 16) and 0xFF} ${(argb shr 8) and 0xFF} ${argb and 0xFF}"

/**
 * The colour that occupies most of [pixels], as ARGB.
 *
 * A mean would be the obvious choice and is the wrong one: averaging a
 * photograph gives mud, and mud beside the photograph looks like a bug. This
 * buckets to 4 bits per channel, takes the fullest bucket, and returns the mean
 * *within* that bucket — so the answer is a colour that is actually in the image
 * rather than the centre of a quantisation cell.
 *
 * Fully transparent pixels are skipped; a wallpaper has none, but a user-supplied
 * PNG can, and counting them drags the result toward black.
 */
fun dominantColor(pixels: IntArray, fallback: Int = VESSEL_DESKTOP_COLOR): Int {
    if (pixels.isEmpty()) return fallback

    val counts = IntArray(BUCKETS)
    val red = LongArray(BUCKETS)
    val green = LongArray(BUCKETS)
    val blue = LongArray(BUCKETS)

    for (pixel in pixels) {
        if ((pixel ushr 24) < ALPHA_FLOOR) continue
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        counts[bucket]++
        red[bucket] += r.toLong()
        green[bucket] += g.toLong()
        blue[bucket] += b.toLong()
    }

    var best = -1
    for (bucket in 0 until BUCKETS) {
        if (best < 0 || counts[bucket] > counts[best]) best = bucket
    }
    if (best < 0 || counts[best] == 0) return fallback

    val n = counts[best].toLong()
    return 0xFF shl 24 or
        ((red[best] / n).toInt() shl 16) or
        ((green[best] / n).toInt() shl 8) or
        (blue[best] / n).toInt()
}

/** 4 bits per channel. Coarse enough that a gradient counts as one colour. */
private const val BUCKETS = 16 * 16 * 16

private const val ALPHA_FLOOR = 8

/**
 * [pixels] as a 24-bit `BI_RGB` Windows bitmap file.
 *
 * **BMP, and only BMP.** Wine's desktop loads the wallpaper with
 * `LoadImageW(…, IMAGE_BITMAP, …, LR_LOADFROMFILE)`, and `BITMAP_Load`
 * (`dlls/user32/cursoricon.c`) rejects anything whose first two bytes are not
 * `BM`. There is no PNG or JPEG path — a `.png` written here loads as nothing at
 * all, and `PaintDesktop` falls through to the flat colour with no message.
 *
 * **The caller must pass the desktop's exact size.** `PaintDesktop`
 * (`dlls/user32/desktop.c`) centres the bitmap and never scales it, and it
 * ignores `WallpaperStyle` entirely — that value is read nowhere in Wine 11.14.
 * A bitmap smaller than the desktop is a picture in the middle of a coloured
 * rectangle; one larger is cropped from the top-left. Matching the size is the
 * whole of "fill".
 *
 * Rows are bottom-up (positive `biHeight`) and padded to a 4-byte boundary,
 * which is the layout every BMP reader assumes and the one `StretchDIBits` is
 * handed directly.
 *
 * @param pixels ARGB, row-major from the top-left — `Bitmap.getPixels` order.
 */
fun encodeBmp24(width: Int, height: Int, pixels: IntArray): ByteArray {
    require(width > 0 && height > 0) { "a wallpaper needs a size, got ${width}x$height" }
    require(pixels.size == width * height) {
        "expected ${width * height} pixels for ${width}x$height, got ${pixels.size}"
    }

    val stride = (width * 3 + 3) and 3.inv()
    val imageSize = stride * height
    val out = ByteArray(FILE_HEADER + INFO_HEADER + imageSize)
    var at = 0

    fun u16(value: Int) {
        out[at++] = (value and 0xFF).toByte()
        out[at++] = ((value shr 8) and 0xFF).toByte()
    }

    fun u32(value: Int) {
        out[at++] = (value and 0xFF).toByte()
        out[at++] = ((value shr 8) and 0xFF).toByte()
        out[at++] = ((value shr 16) and 0xFF).toByte()
        out[at++] = ((value shr 24) and 0xFF).toByte()
    }

    // BITMAPFILEHEADER
    out[at++] = 'B'.code.toByte()
    out[at++] = 'M'.code.toByte()
    u32(out.size)
    u16(0)
    u16(0)
    u32(FILE_HEADER + INFO_HEADER)

    // BITMAPINFOHEADER. biClrUsed stays 0: `bitmap_info_size` derives the palette
    // length from it, and a non-zero value on a 24-bit image moves where Wine
    // thinks the pixels start.
    u32(INFO_HEADER)
    u32(width)
    u32(height)
    u16(1)
    u16(24)
    u32(0) // BI_RGB
    u32(imageSize)
    u32(PIXELS_PER_METRE)
    u32(PIXELS_PER_METRE)
    u32(0)
    u32(0)

    for (row in 0 until height) {
        // Bottom-up: the last source row is written first.
        var source = (height - 1 - row) * width
        var target = at + row * stride
        repeat(width) {
            val pixel = pixels[source++]
            out[target++] = (pixel and 0xFF).toByte()
            out[target++] = ((pixel shr 8) and 0xFF).toByte()
            out[target++] = ((pixel shr 16) and 0xFF).toByte()
        }
    }
    return out
}

private const val FILE_HEADER = 14
private const val INFO_HEADER = 40

/** 72 dpi. Nothing reads it; a zero here trips some stricter loaders. */
private const val PIXELS_PER_METRE = 2835
