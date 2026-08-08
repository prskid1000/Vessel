package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The icon reader, against PE images built a byte at a time by [PeFixture].
 *
 * Two things are worth being explicit about. The first is that the fixture is
 * *independent* of the reader — it writes the format from the specification, so
 * a shared misunderstanding is not possible in the way it is when a test feeds
 * a decoder its own encoder's output. The second is that most of these cases are
 * real files rather than hypotheticals: the doubled height, the bottom-up rows,
 * the 32-bit icon with no alpha, and the 256×256 PNG entry are all things a
 * `.exe` in a downloads folder actually contains.
 */
class PeIconReaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun exeOf(
        groups: Map<Int, List<PeFixture.Row>>,
        icons: Map<Int, ByteArray>,
        name: String = "program.exe",
    ): File = temporary.newFile(name).apply { writeBytes(PeFixture.exe(groups, icons)) }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // --- the common case -------------------------------------------------------

    @Test
    fun `a 32-bit icon decodes to ARGB with the rows the right way up`() {
        // Distinct per-row colours, so a decoder that forgets DIBs are bottom-up
        // passes every other assertion and fails this one.
        val colours = listOf(
            argb(255, 10, 20, 30),
            argb(255, 40, 50, 60),
            argb(128, 70, 80, 90),
            argb(255, 100, 110, 120),
        )
        val payload = PeFixture.dib(4, 4, 32) { _, y -> colours[y] }
        val exe = exeOf(
            groups = mapOf(1 to listOf(PeFixture.Row(4, 4, 32, iconId = 7))),
            icons = mapOf(7 to payload),
        )

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertEquals(4, icon.width)
        assertEquals(4, icon.height)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals("pixel ($x,$y)", colours[y], icon.argb[y * 4 + x])
            }
        }
    }

    @Test
    fun `alpha in a 32-bit icon is kept and the AND mask is not applied over it`() {
        val payload = PeFixture.dib(
            2, 2, 32,
            // A mask that would blank the whole icon if it were wrongly applied
            // to an image that already carries alpha.
            mask = { _, _ -> 1 },
        ) { _, _ -> argb(0x80, 1, 2, 3) }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(2, 2, 32, 7))), mapOf(7 to payload))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertTrue(icon.argb.all { it == argb(0x80, 1, 2, 3) })
    }

    @Test
    fun `a 32-bit icon with no alpha anywhere falls back to the AND mask`() {
        // Written by tools that filled BGR and left A at zero. Taken literally
        // the icon is invisible, which looks exactly like a broken reader.
        val payload = PeFixture.dib(
            2, 2, 32,
            mask = { x, _ -> if (x == 0) 1 else 0 },
        ) { _, _ -> argb(0, 200, 100, 50) }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(2, 2, 32, 7))), mapOf(7 to payload))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        for (y in 0 until 2) {
            assertEquals("left column transparent", 0, icon.argb[y * 2] ushr 24)
            assertEquals("right column opaque", 255, icon.argb[y * 2 + 1] ushr 24)
            assertEquals(0x00C86432, icon.argb[y * 2] and 0x00FFFFFF or 0)
        }
    }

    // --- the older depths ------------------------------------------------------

    @Test
    fun `an 8-bit palettised icon resolves through its palette`() {
        val palette = listOf(0xFF0000, 0x00FF00, 0x0000FF, 0x123456)
        val payload = PeFixture.dib(2, 2, 8, palette = palette) { x, y -> y * 2 + x }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(2, 2, 8, 7))), mapOf(7 to payload))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertEquals(
            palette.map { 0xFF000000.toInt() or it },
            icon.argb.toList(),
        )
    }

    @Test
    fun `a 4-bit icon reads two pixels per byte, high nibble first`() {
        val palette = List(16) { it * 0x010101 }
        val payload = PeFixture.dib(4, 1, 4, palette = palette) { x, _ -> x * 3 }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(4, 1, 4, 7))), mapOf(7 to payload))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertEquals(
            listOf(0, 3, 6, 9).map { 0xFF000000.toInt() or (it * 0x010101) },
            icon.argb.toList(),
        )
    }

    @Test
    fun `a 1-bit icon reads the most significant bit first`() {
        val palette = listOf(0x000000, 0xFFFFFF)
        val payload = PeFixture.dib(8, 1, 1, palette = palette) { x, _ -> if (x % 2 == 0) 1 else 0 }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(8, 1, 1, 7))), mapOf(7 to payload))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        val expected = (0 until 8).map {
            0xFF000000.toInt() or (if (it % 2 == 0) 0xFFFFFF else 0x000000)
        }
        assertEquals(expected, icon.argb.toList())
    }

    @Test
    fun `a 24-bit icon is opaque where the mask is clear`() {
        val payload = PeFixture.dib(
            2, 1, 24,
            mask = { x, _ -> if (x == 1) 1 else 0 },
        ) { _, _ -> 0x336699 }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(2, 1, 24, 7))), mapOf(7 to payload))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertEquals(0xFF336699.toInt(), icon.argb[0])
        assertEquals(0, icon.argb[1] ushr 24)
    }

    // --- choosing which entry --------------------------------------------------

    @Test
    fun `the largest entry that fits is chosen`() {
        val icons = mapOf(
            1 to PeFixture.dib(16, 16, 32) { _, _ -> argb(255, 1, 1, 1) },
            2 to PeFixture.dib(32, 32, 32) { _, _ -> argb(255, 2, 2, 2) },
            3 to PeFixture.dib(64, 64, 32) { _, _ -> argb(255, 3, 3, 3) },
        )
        val rows = listOf(
            PeFixture.Row(16, 16, 32, 1),
            PeFixture.Row(32, 32, 32, 2),
            PeFixture.Row(64, 64, 32, 3),
        )
        val exe = exeOf(mapOf(1 to rows), icons)

        assertEquals(32, PeIconReader.iconOf(exe, maxSize = 48)?.width)
        assertEquals(64, PeIconReader.iconOf(exe, maxSize = 256)?.width)
        // Nothing fits: the smallest is a better answer than none at all.
        assertEquals(16, PeIconReader.iconOf(exe, maxSize = 8)?.width)
    }

    @Test
    fun `depth breaks a tie between two entries of the same size`() {
        val icons = mapOf(
            1 to PeFixture.dib(16, 16, 4, palette = List(16) { 0x111111 * it }) { _, _ -> 1 },
            2 to PeFixture.dib(16, 16, 32) { _, _ -> argb(255, 9, 9, 9) },
        )
        val rows = listOf(PeFixture.Row(16, 16, 4, 1), PeFixture.Row(16, 16, 32, 2))
        val exe = exeOf(mapOf(1 to rows), icons)

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertEquals(argb(255, 9, 9, 9), icon.argb[0])
    }

    @Test
    fun `the lowest group icon id is the application icon`() {
        val icons = mapOf(
            1 to PeFixture.dib(8, 8, 32) { _, _ -> argb(255, 1, 1, 1) },
            2 to PeFixture.dib(8, 8, 32) { _, _ -> argb(255, 2, 2, 2) },
        )
        val exe = exeOf(
            groups = mapOf(
                9 to listOf(PeFixture.Row(8, 8, 32, 2)),
                4 to listOf(PeFixture.Row(8, 8, 32, 1)),
            ),
            icons = icons,
        )

        val icon = PeIconReader.iconOf(exe) as PeIcon.Pixels
        assertEquals("group 4 is the application icon", argb(255, 1, 1, 1), icon.argb[0])
    }

    // --- the PNG entry ---------------------------------------------------------

    @Test
    fun `a PNG entry is handed on rather than decoded`() {
        val png = PeFixture.png()
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(256, 256, 32, 7))), mapOf(7 to png))

        val icon = PeIconReader.iconOf(exe) as PeIcon.Png
        assertEquals(256, icon.width)
        assertTrue(png.contentEquals(icon.bytes))
    }

    @Test
    fun `a DIB is preferred over a PNG of the same size`() {
        val icons = mapOf(
            1 to PeFixture.png(),
            2 to PeFixture.dib(64, 64, 32) { _, _ -> argb(255, 5, 5, 5) },
        )
        // Same width; the DIB has the higher declared depth, which is how the
        // ordering keeps the PNG path exceptional.
        val rows = listOf(PeFixture.Row(64, 64, 24, 1), PeFixture.Row(64, 64, 32, 2))
        val exe = exeOf(mapOf(1 to rows), icons)

        assertTrue(PeIconReader.iconOf(exe) is PeIcon.Pixels)
    }

    // --- honest refusals -------------------------------------------------------

    @Test
    fun `a file that is not a PE has no icon rather than an exception`() {
        val notPe = temporary.newFile("notes.txt").apply { writeText("hello") }
        assertNull(PeIconReader.iconOf(notPe))
    }

    @Test
    fun `a file that is not there has no icon`() {
        assertNull(PeIconReader.iconOf(File(temporary.root, "absent.exe")))
    }

    @Test
    fun `a PE with no resources at all has no icon`() {
        val exe = exeOf(groups = emptyMap(), icons = emptyMap())
        assertNull(PeIconReader.iconOf(exe))
    }

    @Test
    fun `a group icon pointing at an id that is not there has no icon`() {
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(16, 16, 32, iconId = 99))), emptyMap())
        assertNull(PeIconReader.iconOf(exe))
    }

    @Test
    fun `a compressed DIB is refused rather than guessed at`() {
        // BI_RLE8. Decoding it as BI_RGB produces plausible garbage, which is
        // worse than the lettered placeholder.
        val payload = PeFixture.dib(4, 4, 8, palette = List(4) { it }, compression = 1) { _, _ -> 0 }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(4, 4, 8, 7))), mapOf(7 to payload))
        assertNull(PeIconReader.iconOf(exe))
    }

    @Test
    fun `a truncated executable has no icon`() {
        val whole = PeFixture.exe(
            mapOf(1 to listOf(PeFixture.Row(16, 16, 32, 7))),
            mapOf(7 to PeFixture.dib(16, 16, 32) { _, _ -> argb(255, 1, 2, 3) }),
        )
        val cut = temporary.newFile("cut.exe")
        cut.writeBytes(whole.copyOfRange(0, whole.size / 2))
        assertNull(PeIconReader.iconOf(cut))
    }

    @Test
    fun `an icon larger than any real one is refused rather than allocated`() {
        // A DIB header claiming 20000x20000 is 1.6 GB of ints if believed.
        val payload = PeFixture.dib(4, 4, 32) { _, _ -> argb(255, 1, 2, 3) }
        payload.also {
            it[4] = 0x20; it[5] = 0x4E; it[6] = 0; it[7] = 0            // biWidth  = 20000
            it[8] = 0x40; it[9] = 0x9C.toByte(); it[10] = 0; it[11] = 0 // biHeight = 40000
        }
        val exe = exeOf(mapOf(1 to listOf(PeFixture.Row(4, 4, 32, 7))), mapOf(7 to payload))
        assertNull(PeIconReader.iconOf(exe))
    }

    @Test
    fun `the architecture reader still works on the same fixture`() {
        // PeReader and PeIconReader parse the same headers independently; this
        // is the cheap check that the fixture is a real PE and not something
        // only the new reader happens to accept.
        val exe = exeOf(
            mapOf(1 to listOf(PeFixture.Row(8, 8, 32, 7))),
            mapOf(7 to PeFixture.dib(8, 8, 32) { _, _ -> argb(255, 1, 2, 3) }),
        )
        assertEquals(PeArchitecture.ARM64, PeReader.architectureOf(exe))
    }
}
