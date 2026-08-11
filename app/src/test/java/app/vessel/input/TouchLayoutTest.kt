package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a control is, and whether a finger is on it.
 *
 * Every number in here is a fraction of the *surface*, never of the guest
 * desktop — see [TouchControl]. The tests that matter are the ones about the
 * gaps: a touch between two controls must reach the guest, or the overlay
 * silently becomes a lid.
 */
class TouchLayoutTest {

    private val w = 900f
    private val h = 400f

    private fun button(id: String, cx: Float, cy: Float, size: Float = 0.1f) = TouchControl(
        id = id,
        kind = TouchKind.BUTTON,
        cx = cx,
        cy = cy,
        size = size,
    )

    @Test
    fun `inside claims and the gap does not`() {
        val layout = TouchLayout(listOf(button("a", 0.2f, 0.5f)))
        // 0.1 of the shorter edge, 400, is a 40 px radius at (180, 200).
        assertEquals("a", layout.hitTest(180f, 200f, w, h)?.id)
        assertEquals("a", layout.hitTest(215f, 200f, w, h)?.id)
        assertNull(layout.hitTest(230f, 200f, w, h))
        assertNull(layout.hitTest(180f, 250f, w, h))
    }

    /**
     * A round control is hit-tested round.
     *
     * The corner of the bounding box is 1.41 radii out, so a finger there is on
     * the square and off the circle — and the circle is what is drawn.
     */
    @Test
    fun `a round control does not claim its corners`() {
        val layout = TouchLayout(listOf(button("a", 0.5f, 0.5f)))
        assertNull(layout.hitTest(450f + 39f, 200f + 39f, w, h))
        assertEquals("a", layout.hitTest(450f + 27f, 200f + 27f, w, h)?.id)
    }

    @Test
    fun `a d-pad claims its corners, because it is a square`() {
        val layout = TouchLayout(
            listOf(button("d", 0.5f, 0.5f).copy(kind = TouchKind.DPAD)),
        )
        assertEquals("d", layout.hitTest(450f + 39f, 200f + 39f, w, h)?.id)
    }

    /** Painted in order, so the one on top is the last one, and it takes the touch. */
    @Test
    fun `overlaps resolve to the last declared`() {
        val layout = TouchLayout(listOf(button("under", 0.5f, 0.5f), button("over", 0.5f, 0.5f)))
        assertEquals("over", layout.hitTest(450f, 200f, w, h)?.id)
    }

    /**
     * A control clamped to the edge is still wholly on screen, which means wholly
     * hittable — a button whose left half is off the panel cannot be pressed and
     * cannot be dragged back.
     */
    @Test
    fun `a clamped edge control is fully hittable`() {
        val clamped = button("edge", -0.5f, 0.5f).clampedIn(w, h)
        val layout = TouchLayout(listOf(clamped))
        val left = clamped.centreX(w) - clamped.radiusPx(w, h)
        assertTrue("left edge is on screen", left >= -0.01f)
        assertEquals("edge", layout.hitTest(clamped.centreX(w), 200f, w, h)?.id)
    }

    /**
     * The radius is a fraction of the *shorter* edge, so the clamp is a different
     * fraction on each axis — and clamping both to the same number would forbid
     * the outer sixth of a landscape screen, where a thumb actually rests.
     */
    @Test
    fun `the clamp is asymmetric because the radius is not`() {
        val control = button("a", 0.5f, 0.5f, size = 0.14f).clampedIn(w, h)
        assertEquals(0.5f, control.cx, 0.001f)
        val far = button("a", 0.97f, 0.97f, size = 0.14f).clampedIn(w, h)
        // 0.14 * 400 = 56 px. As a fraction of 900 that is 0.062; of 400, 0.14.
        assertEquals(1f - 0.062f, far.cx, 0.002f)
        assertEquals(1f - 0.14f, far.cy, 0.002f)
    }

    @Test
    fun `sane pulls every number back into range`() {
        val wild = TouchControl(
            id = "x",
            kind = TouchKind.BUTTON,
            cx = 4f,
            cy = -2f,
            size = 9f,
            opacity = 3f,
        ).sane()
        assertEquals(1f, wild.cx, 0f)
        assertEquals(0f, wild.cy, 0f)
        assertEquals(TouchControls.MAX_SIZE, wild.size, 0f)
        assertEquals(TouchControls.MAX_OPACITY, wild.opacity, 0f)
    }

    @Test
    fun `a not-a-number never survives into the layout`() {
        val nan = TouchControl(
            id = "x",
            kind = TouchKind.BUTTON,
            cx = Float.NaN,
            cy = Float.NaN,
            size = Float.NaN,
            opacity = Float.NaN,
        ).sane()
        assertFalse(nan.cx.isNaN())
        assertFalse(nan.size.isNaN())
        assertEquals(TouchControls.DEFAULT_OPACITY, nan.opacity, 0f)
    }

    /**
     * The three stock layouts have to be legal, because they are what a fresh
     * container gets and nothing sanitises them on the way in.
     */
    @Test
    fun `every stock layout is already sane and uniquely shaped`() {
        TouchLayouts.stock.forEach { stock ->
            assertEquals(stock.name, stock.layout, stock.layout.sane())
            listOf("Stick", "Look pad", "D-pad").forEach { designation ->
                assertTrue(
                    "${stock.name} has at most one $designation",
                    stock.layout.controls.count { it.designation == designation } <= 1,
                )
            }
        }
    }

    @Test
    fun `the built-in default draws something`() {
        assertFalse(InputProfile.Default.touch.isEmpty)
        assertTrue(InputProfile.Default.touch.has("Stick"))
        assertTrue(InputProfile.Default.touch.has("Look pad"))
    }

    @Test
    fun `a stick reads as its four keys and a look pad reads as the mouse`() {
        val stick = TouchLayouts.Wasd.byId("stick")!!
        assertEquals("W A S D", stick.bindingLabel)
        assertEquals("Mouse look", TouchLayouts.Wasd.byId("look")!!.bindingLabel)
    }
}
