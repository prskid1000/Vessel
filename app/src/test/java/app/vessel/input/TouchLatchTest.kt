package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.vessel.data.StoredTouchControl
import org.junit.Test

/**
 * A long press latches a button down and the hand leaves.
 *
 * The four it applies to are the ones a hand cannot hold and play at the same
 * time. `L3` is sprint, pressed by the thumb that is steering with the stick it
 * sits beside; `L2` is aim, held by the index finger the phone is also resting
 * on. On a pad those are comfortable holds. On glass they are a thumb in two
 * places at once.
 *
 * What is pinned here is the arithmetic of the gesture, because every part of it
 * is invisible: nothing about a press says how long it lasted, and a latch that
 * releases when it should hold leaves a key down in the guest with no finger
 * anywhere near it.
 */
class TouchLatchTest {

    private val w = 900f
    private val h = 400f

    private fun button(id: String, pad: GamepadControl, latching: Boolean) = TouchControl(
        id = id,
        kind = TouchKind.BUTTON,
        cx = 0.5f,
        cy = 0.5f,
        size = 0.08f,
        action = GamepadAction.Pad(pad),
        pad = pad,
        latching = latching,
    )

    private val l3 = button("l3", GamepadControl.THUMB_L, latching = true)
    private val l2 = button("l2", GamepadControl.L2, latching = true)
    private val r2 = button("r2", GamepadControl.R2, latching = false)

    private fun translator(vararg controls: TouchControl) =
        TouchControlTranslator(TouchLayout(controls.toList()))

    /** Down at [at], up [heldFor] later. */
    private fun tap(
        t: TouchControlTranslator,
        control: TouchControl,
        at: Long,
        heldFor: Long,
    ): List<GuestInput> {
        val out = mutableListOf<GuestInput>()
        out += t.onDown(1, control, w / 2, h / 2, w, h, at)
        out += t.onUp(1, at + heldFor)
        return out
    }

    @Test
    fun `a short press is the press it always was`() {
        val t = translator(l3)
        tap(t, l3, at = 1_000L, heldFor = 120L)
        assertTrue("a tap must not latch", t.latchedIds.isEmpty())
    }

    @Test
    fun `a three second press latches the button down`() {
        val t = translator(l3)
        tap(t, l3, at = 1_000L, heldFor = 3_000L)
        assertEquals(setOf("l3"), t.latchedIds)
    }

    @Test
    fun `a latched button stays down for the guest with no finger on it`() {
        val t = translator(l3)
        tap(t, l3, at = 0L, heldFor = 3_500L)
        // The pad snapshot is what the guest reads, and it must still say L3.
        assertTrue(GamepadControl.THUMB_L in t.padSnapshot().pressed)
        assertFalse("no finger is left on the glass", t.busy)
    }

    @Test
    fun `a second long press lets it go`() {
        val t = translator(l3)
        tap(t, l3, at = 0L, heldFor = 3_500L)
        assertEquals(setOf("l3"), t.latchedIds)

        tap(t, l3, at = 10_000L, heldFor = 3_500L)
        assertTrue(t.latchedIds.isEmpty())
        assertFalse(GamepadControl.THUMB_L in t.padSnapshot().pressed)
    }

    @Test
    fun `a tap on a latched button does not drop the latch`() {
        // The way out is the way in. A game that taps the same button in play
        // would otherwise clear the latch by accident.
        val t = translator(l3)
        tap(t, l3, at = 0L, heldFor = 3_500L)
        tap(t, l3, at = 10_000L, heldFor = 100L)
        assertEquals(setOf("l3"), t.latchedIds)
        assertTrue(GamepadControl.THUMB_L in t.padSnapshot().pressed)
    }

    @Test
    fun `only a control that says it latches does`() {
        // The flag decides, not the button it happens to be. L2 here has it on,
        // R2 does not, and they are held for exactly as long as each other.
        val t = translator(l2, r2)
        tap(t, l2, at = 0L, heldFor = 4_000L)
        tap(t, r2, at = 10_000L, heldFor = 4_000L)
        assertEquals(setOf("l2"), t.latchedIds)
    }

    @Test
    fun `a button that does not latch never does, however long it is held`() {
        val fire = TouchControl(
            id = "fire",
            kind = TouchKind.BUTTON,
            cx = 0.5f,
            cy = 0.5f,
            size = 0.08f,
            action = GamepadAction.Key(X11.SPACE),
        )
        val t = translator(fire)
        tap(t, fire, at = 0L, heldFor = 30_000L)
        assertTrue(t.latchedIds.isEmpty())
    }

    @Test
    fun `reset lets go of a latch`() {
        // A stopping session or a changed layout must not leave a key held that
        // nothing left in the system can release.
        val t = translator(l3)
        tap(t, l3, at = 0L, heldFor = 3_500L)
        assertTrue(GamepadControl.THUMB_L in t.padSnapshot().pressed)

        t.reset()

        assertTrue(t.latchedIds.isEmpty())
        // The pad snapshot rather than the returned inputs: a control bound to
        // a pad control sends no key event at all -- it reaches the guest as a
        // controller, and the snapshot is where that is visible.
        assertFalse(GamepadControl.THUMB_L in t.padSnapshot().pressed)
    }

    @Test
    fun `the boundary is exactly the latch threshold`() {
        // Against the constant, not against a literal. This test named three
        // seconds and hard-coded 2_999/3_000, so shortening the threshold to one
        // second turned a correct change into a failing test about a number
        // nobody meant to pin. What is worth pinning is that the comparison is
        // strict on one side and inclusive on the other.
        val ms = TouchControlTranslator.LATCH_HOLD_MS
        val below = translator(l3).also { tap(it, l3, at = 0L, heldFor = ms - 1) }
        assertTrue(below.latchedIds.isEmpty())
        val at = translator(l3).also { tap(it, l3, at = 0L, heldFor = ms) }
        assertEquals(setOf("l3"), at.latchedIds)
    }
    @Test
    fun `the built-in pad latches the four a hand cannot hold`() {
        // The model takes no view; the layout does. This is where the argument
        // about thumbs and index fingers actually lands, so it is pinned here
        // rather than left as a comment.
        val latching = TouchLayouts.Gamepad.controls
            .filter { it.latching }
            .mapNotNull { it.pad }
            .toSet()
        assertEquals(
            setOf(
                GamepadControl.L1,
                GamepadControl.L2,
                GamepadControl.THUMB_L,
                GamepadControl.THUMB_R,
            ),
            latching,
        )
    }

    @Test
    fun `a latching button survives a round trip to disk`() {
        // A flag that is not stored is a setting the user sets once and loses.
        assertEquals(true, StoredTouchControl.of(l3).toControl()?.latching)
        assertEquals(false, StoredTouchControl.of(r2).toControl()?.latching)
    }

    @Test
    fun `a profile written before the setting existed still latches L3`() {
        // Every existing profile decodes null here. Reading that as false would
        // ship the feature switched off for everyone who already had a profile --
        // which is everyone -- and it would look broken rather than absent.
        val old = StoredTouchControl(
            id = "btn-l3",
            kind = "BUTTON",
            cx = 0.245f,
            cy = 0.72f,
            size = 0.040f,
            pad = GamepadControl.THUMB_L.name,
        )
        assertEquals(null, old.latching)
        assertEquals(true, old.toControl()?.latching)
    }

    @Test
    fun `a profile written before the setting existed leaves A alone`() {
        val old = StoredTouchControl(
            id = "btn-a",
            kind = "BUTTON",
            cx = 0.5f,
            cy = 0.5f,
            size = 0.05f,
            pad = GamepadControl.A.name,
        )
        assertEquals(false, old.toControl()?.latching)
    }

    @Test
    fun `a user who turned it off keeps it off`() {
        // The point of the null: false has to mean "they said no", not "nobody
        // asked", or turning it off would not survive a reload.
        val off = StoredTouchControl.of(l3).copy(latching = false)
        assertEquals(false, off.toControl()?.latching)
    }
}
