package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture table in `PointerGestures`' doc comment, asserted.
 *
 * This is the machine's whole reason for existing as pure Kotlin: the same
 * seven gestures, driven by hand at chosen timestamps, with no looper, no
 * `MotionEvent` and no device. Every failure mode being defended against here
 * was a real one in some Winlator-family app — the two-finger tap that lands as
 * a left click because one finger lifted first, the long press that fires
 * halfway through a slow drag, the pinch that scrolls.
 */
class PointerGesturesTest {

    private val config = GestureConfig()

    private fun gestures(mode: PointerMode = PointerMode.TRACKPAD) = PointerGestures(mode, config)

    private fun one(x: Float, y: Float) = listOf(Touch(0, x, y))

    @Test
    fun `a quick still touch is a left click`() {
        val g = gestures()
        assertEquals(emptyList<GuestInput>(), g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0))
        assertEquals(
            listOf(
                GuestInput.Button(PointerButton.LEFT, pressed = true),
                GuestInput.Button(PointerButton.LEFT, pressed = false),
            ),
            g.onTouch(TouchPhase.UP, one(100f, 100f), 100),
        )
    }

    @Test
    fun `a slow touch is not a click`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0)
        // Past tapTimeoutMs and no long press was ever collected, so this is a
        // finger that rested and left. Clicking here is what makes a desktop
        // feel like it fires on things the user only looked at.
        assertEquals(emptyList<GuestInput>(), g.onTouch(TouchPhase.UP, one(100f, 100f), 5_000))
    }

    @Test
    fun `a touch that travelled is not a click`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0)
        g.onTouch(TouchPhase.MOVE, one(160f, 100f), 40)
        assertEquals(emptyList<GuestInput>(), g.onTouch(TouchPhase.UP, one(160f, 100f), 80))
    }

    @Test
    fun `two fingers tap right, three tap middle, counted at the peak`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, listOf(Touch(0, 100f, 100f)), 0)
        g.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 140f, 100f)), 10)
        // One finger leaves a frame early, which is what actually happens and
        // what makes a survivor count report a left click about half the time.
        g.onTouch(TouchPhase.POINTER_UP, listOf(Touch(0, 100f, 100f), Touch(1, 140f, 100f)), 90)
        assertEquals(
            listOf(
                GuestInput.Button(PointerButton.RIGHT, pressed = true),
                GuestInput.Button(PointerButton.RIGHT, pressed = false),
            ),
            g.onTouch(TouchPhase.UP, listOf(Touch(0, 100f, 100f)), 100),
        )

        val three = gestures()
        three.onTouch(TouchPhase.DOWN, listOf(Touch(0, 100f, 100f)), 0)
        three.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 140f, 100f)), 5)
        three.onTouch(
            TouchPhase.POINTER_DOWN,
            listOf(Touch(0, 100f, 100f), Touch(1, 140f, 100f), Touch(2, 180f, 100f)),
            10,
        )
        assertEquals(
            listOf(
                GuestInput.Button(PointerButton.MIDDLE, pressed = true),
                GuestInput.Button(PointerButton.MIDDLE, pressed = false),
            ),
            three.onTouch(TouchPhase.UP, listOf(Touch(0, 100f, 100f)), 120),
        )
    }

    // — the long press ---------------------------------------------------------------

    @Test
    fun `a held finger presses the button and moving drags it`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0)

        val deadline = g.timeoutAt
        assertNotNull("the machine has to ask to be woken", deadline)
        assertEquals(config.longPressMs, deadline)

        assertEquals(
            listOf(GuestInput.Button(PointerButton.LEFT, pressed = true)),
            g.onTimeout(deadline!!),
        )
        assertNull("a fired timeout must not fire twice", g.timeoutAt)
        assertEquals(emptyList<GuestInput>(), g.onTimeout(deadline + 1_000))

        assertEquals(
            listOf(GuestInput.MoveBy(30f * config.trackpadGain, 0f)),
            g.onTouch(TouchPhase.MOVE, one(130f, 100f), 500),
        )
        assertEquals(
            listOf(GuestInput.Button(PointerButton.LEFT, pressed = false)),
            g.onTouch(TouchPhase.UP, one(130f, 100f), 900),
        )
    }

    @Test
    fun `a finger that moved past the slop no longer arms the long press`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0)
        g.onTouch(TouchPhase.MOVE, one(200f, 100f), 50)
        assertNull("a slow drag must not press the button halfway through", g.timeoutAt)
        assertEquals(emptyList<GuestInput>(), g.onTimeout(config.longPressMs))
    }

    @Test
    fun `a second finger cancels a pending long press`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, listOf(Touch(0, 100f, 100f)), 0)
        g.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 200f, 100f)), 10)
        assertNull(g.timeoutAt)
    }

    @Test
    fun `a cancelled or reset drag releases the button`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0)
        g.onTimeout(config.longPressMs)
        assertEquals(
            listOf(GuestInput.Button(PointerButton.LEFT, pressed = false)),
            g.onTouch(TouchPhase.CANCEL, one(100f, 100f), 500),
        )
        // And nothing is left held afterwards, so a view torn down twice does
        // not send a second stray release.
        assertEquals(emptyList<GuestInput>(), g.reset())
    }

    // — the two modes ------------------------------------------------------------------

    @Test
    fun `direct mode puts the cursor under the finger, from the first frame`() {
        val g = gestures(PointerMode.DIRECT)
        assertEquals(
            listOf(GuestInput.MoveTo(100f, 100f)),
            g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0),
        )
        assertEquals(
            listOf(GuestInput.MoveTo(140f, 130f)),
            g.onTouch(TouchPhase.MOVE, one(140f, 130f), 20),
        )
    }

    @Test
    fun `trackpad mode emits deltas and never an absolute position`() {
        val g = gestures(PointerMode.TRACKPAD)
        assertEquals(emptyList<GuestInput>(), g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0))
        assertEquals(
            listOf(GuestInput.MoveBy(40f * config.trackpadGain, 30f * config.trackpadGain)),
            g.onTouch(TouchPhase.MOVE, one(140f, 130f), 20),
        )
    }

    @Test
    fun `the modes differ in exactly one thing`() {
        // The claim in PointerMode's doc comment, made falsifiable: the tap is
        // identical in both, which is what makes switching mid-session safe.
        fun tap(mode: PointerMode): List<GuestInput> {
            val g = gestures(mode)
            g.onTouch(TouchPhase.DOWN, one(100f, 100f), 0)
            return g.onTouch(TouchPhase.UP, one(100f, 100f), 80)
        }
        assertEquals(tap(PointerMode.TRACKPAD), tap(PointerMode.DIRECT))
        assertEquals(PointerMode.DIRECT, PointerMode.TRACKPAD.toggled())
        assertEquals(PointerMode.TRACKPAD, PointerMode.DIRECT.toggled())
    }

    // — two fingers -----------------------------------------------------------------------

    @Test
    fun `two fingers dragged together scroll in detents`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, listOf(Touch(0, 100f, 100f)), 0)
        g.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 200f, 100f)), 10)

        // Enough to decide, and the decision itself is not replayed as movement.
        val deciding = g.onTouch(
            TouchPhase.MOVE,
            listOf(Touch(0, 100f, 125f), Touch(1, 200f, 125f)),
            20,
        )
        assertEquals(emptyList<GuestInput>(), deciding)

        val scrolled = g.onTouch(
            TouchPhase.MOVE,
            listOf(Touch(0, 100f, 175f), Touch(1, 200f, 175f)),
            30,
        )
        assertEquals(listOf(GuestInput.Scroll(ScrollAxis.VERTICAL, 1)), scrolled)
    }

    @Test
    fun `sub-detent motion accumulates rather than being dropped`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, listOf(Touch(0, 100f, 100f)), 0)
        g.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 200f, 100f)), 10)
        g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 100f, 125f), Touch(1, 200f, 125f)), 20)

        var y = 125f
        val emitted = buildList {
            repeat(10) {
                y += config.scrollDetent / 4f
                addAll(g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 100f, y), Touch(1, 200f, y)), 30 + it * 10L))
            }
        }
        // Ten quarter-detents is two and a half detents; truncating each one
        // independently would have produced nothing at all.
        assertEquals(2, emitted.size)
        assertTrue(emitted.all { it == GuestInput.Scroll(ScrollAxis.VERTICAL, 1) })
    }

    @Test
    fun `two fingers pulled apart zoom rather than scroll`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, listOf(Touch(0, 150f, 100f)), 0)
        g.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 200f, 100f)), 10)

        // Symmetric, so the centroid does not move at all and only the spread
        // can be the evidence.
        assertEquals(
            emptyList<GuestInput>(),
            g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 50f, 100f), Touch(1, 250f, 100f)), 20),
        )
        assertEquals(
            listOf(GuestInput.Zoom(1)),
            g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 20f, 100f), Touch(1, 280f, 100f)), 30),
        )
        assertEquals(
            listOf(GuestInput.Zoom(-1)),
            g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 50f, 100f), Touch(1, 250f, 100f)), 40),
        )
    }

    @Test
    fun `a two-finger gesture never falls back to cursor motion as fingers lift`() {
        val g = gestures()
        g.onTouch(TouchPhase.DOWN, listOf(Touch(0, 100f, 100f)), 0)
        g.onTouch(TouchPhase.POINTER_DOWN, listOf(Touch(0, 100f, 100f), Touch(1, 200f, 100f)), 10)
        g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 100f, 200f), Touch(1, 200f, 200f)), 20)
        g.onTouch(TouchPhase.POINTER_UP, listOf(Touch(0, 100f, 200f), Touch(1, 200f, 200f)), 30)

        // The surviving finger was never tracked as a cursor. Resuming here
        // teleports it by the distance between the two.
        assertEquals(
            emptyList<GuestInput>(),
            g.onTouch(TouchPhase.MOVE, listOf(Touch(0, 400f, 600f)), 40),
        )
    }

    // — the sub-pixel accumulator ---------------------------------------------------------

    @Test
    fun `slow motion is carried rather than truncated`() {
        val s = SubPixel()
        val taken = (1..5).map { s.take(0.4f, 0f).first }
        // 0.4 five times is 2 px, and truncating each independently is 0.
        assertEquals(2, taken.sum())
        s.reset()
        assertEquals(0 to 0, s.take(0.4f, 0.4f))
    }
}
