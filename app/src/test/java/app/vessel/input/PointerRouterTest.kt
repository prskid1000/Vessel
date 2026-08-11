package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three traps a hand-written version of this walks into.
 *
 * Every one of them is a bug whose symptom points somewhere else entirely: a
 * fire button that turns a tap into a right click, a trackpad that goes dead
 * once a thumb is resting on a stick, and a key still held after the window went
 * away. They are traps because the *overlay* looks fine in all three.
 */
class PointerRouterTest {

    /** 900 x 400, which is roughly the landscape session. */
    private val w = 900f
    private val h = 400f

    /** One button, dead centre, radius 0.1 of the shorter edge — 40 px. */
    private val layout = TouchLayout(
        listOf(
            TouchControl(
                id = "fire",
                kind = TouchKind.BUTTON,
                cx = 0.5f,
                cy = 0.5f,
                size = 0.1f,
                action = GamepadAction.Key(X11.SPACE),
            ),
        ),
    )

    private fun router() = PointerRouter().apply { enabled = true }

    private fun onButton(id: Int) = Touch(id, 450f, 200f)

    private fun elsewhere(id: Int, x: Float = 100f) = Touch(id, x, 100f)

    @Test
    fun `a finger on a control never reaches the gesture machine`() {
        val router = router()
        val routed = router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)

        assertNull(routed.gesturePhase)
        assertEquals(
            listOf(OverlayTouch.Down(0, layout.controls[0], 450f, 200f)),
            routed.overlay,
        )
    }

    /**
     * **The three-finger trap.**
     *
     * `PointerGestures` counts `peakFingers` and maps one, two and three fingers
     * to left, right and middle click. A thumb resting on a fire button while
     * the other hand taps is *one* finger as far as that machine is concerned —
     * and if the claimed finger leaks into its pointer list, the tap becomes a
     * right click. This is the whole reason the router is a named type.
     */
    @Test
    fun `a held fire button plus a tap is still one finger`() {
        val router = router()
        router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)

        val down = router.route(
            TouchPhase.POINTER_DOWN,
            listOf(onButton(0), elsewhere(1)),
            1,
            layout,
            w,
            h,
        )
        // The phase is rewritten: Android called it POINTER_DOWN, but the gesture
        // machine has no gesture yet and would never start one.
        assertEquals(TouchPhase.DOWN, down.gesturePhase)
        assertEquals(listOf(elsewhere(1)), down.gesturePointers)

        val up = router.route(
            TouchPhase.POINTER_UP,
            listOf(onButton(0), elsewhere(1)),
            1,
            layout,
            w,
            h,
        )
        // And on the way up: the *free* fingers are down to one, so this is the
        // last of them and the machine has to see UP, not POINTER_UP.
        assertEquals(TouchPhase.UP, up.gesturePhase)
        assertEquals(listOf(elsewhere(1)), up.gesturePointers)
    }

    @Test
    fun `two free fingers still read as two`() {
        val router = router()
        val first = router.route(TouchPhase.DOWN, listOf(elsewhere(0)), 0, layout, w, h)
        assertEquals(TouchPhase.DOWN, first.gesturePhase)

        val second = router.route(
            TouchPhase.POINTER_DOWN,
            listOf(elsewhere(0), elsewhere(1, 200f)),
            1,
            layout,
            w,
            h,
        )
        assertEquals(TouchPhase.POINTER_DOWN, second.gesturePhase)
        assertEquals(2, second.gesturePointers.size)

        val lift = router.route(
            TouchPhase.POINTER_UP,
            listOf(elsewhere(0), elsewhere(1, 200f)),
            1,
            layout,
            w,
            h,
        )
        assertEquals(TouchPhase.POINTER_UP, lift.gesturePhase)
    }

    @Test
    fun `a move carries the claimed fingers to the overlay and the rest to the machine`() {
        val router = router()
        router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)
        router.route(TouchPhase.POINTER_DOWN, listOf(onButton(0), elsewhere(1)), 1, layout, w, h)

        val moved = router.route(
            TouchPhase.MOVE,
            listOf(Touch(0, 460f, 210f), elsewhere(1, 150f)),
            0,
            layout,
            w,
            h,
        )
        assertEquals(listOf(OverlayTouch.Move(0, 460f, 210f)), moved.overlay)
        assertEquals(TouchPhase.MOVE, moved.gesturePhase)
        assertEquals(listOf(elsewhere(1, 150f)), moved.gesturePointers)
    }

    @Test
    fun `a move with nothing free says nothing to the machine`() {
        val router = router()
        router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)

        val moved = router.route(TouchPhase.MOVE, listOf(Touch(0, 455f, 205f)), 0, layout, w, h)
        assertNull(moved.gesturePhase)
        assertEquals(1, moved.overlay.size)
    }

    @Test
    fun `cancel clears everything on both sides`() {
        val router = router()
        router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)
        router.route(TouchPhase.POINTER_DOWN, listOf(onButton(0), elsewhere(1)), 1, layout, w, h)

        val cancelled = router.route(TouchPhase.CANCEL, emptyList(), 0, layout, w, h)
        assertEquals(listOf(OverlayTouch.Up(0)), cancelled.overlay)
        assertEquals(TouchPhase.CANCEL, cancelled.gesturePhase)
        assertTrue(router.claimedPointers.isEmpty())

        // And the machine is told DOWN again next time, rather than POINTER_DOWN
        // for a gesture that no longer exists.
        val next = router.route(TouchPhase.DOWN, listOf(elsewhere(0)), 0, layout, w, h)
        assertEquals(TouchPhase.DOWN, next.gesturePhase)
    }

    @Test
    fun `an overlay that is off routes everything to the machine`() {
        val router = PointerRouter()
        val routed = router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)
        assertEquals(TouchPhase.DOWN, routed.gesturePhase)
        assertTrue(routed.overlay.isEmpty())
    }

    /**
     * Edit mode claims bare screen too, and the panel's promise depends on it.
     *
     * "The guest is not receiving input while you edit" has to be literally
     * true — not a filtered subset, none — or you rebind a button by
     * accidentally shooting.
     */
    @Test
    fun `edit mode claims every finger, including one on nothing`() {
        val router = router().apply { editing = true }
        val routed = router.route(TouchPhase.DOWN, listOf(elsewhere(0)), 0, layout, w, h)

        assertNull(routed.gesturePhase)
        assertEquals(listOf(OverlayTouch.Down(0, null, 100f, 100f)), routed.overlay)
    }

    @Test
    fun `reset hands back what the overlay was holding`() {
        val router = router()
        router.route(TouchPhase.DOWN, listOf(onButton(0)), 0, layout, w, h)
        assertEquals(setOf(0), router.reset())
        assertTrue(router.claimedPointers.isEmpty())
    }
}
