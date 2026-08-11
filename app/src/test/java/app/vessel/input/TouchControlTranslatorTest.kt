package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fingers on glass, and the guest cannot tell the difference.
 *
 * The claim worth pinning is the one the design rests on: an on-screen stick is
 * not a second implementation of a stick. It computes a deflection and hands it
 * to a [GamepadTranslator], so the deadzone, the release zone and the look tick
 * are the same code a physical pad runs through — which is why
 * [GamepadConfigTest]'s numbers apply here without being restated.
 */
class TouchControlTranslatorTest {

    private val w = 900f
    private val h = 400f

    private val fire = TouchControl(
        id = "fire",
        kind = TouchKind.BUTTON,
        cx = 0.5f,
        cy = 0.5f,
        size = 0.1f,
        action = GamepadAction.Key(X11.SPACE),
    )

    private val stick = TouchControl(
        id = "stick",
        kind = TouchKind.STICK,
        cx = 0.15f,
        cy = 0.6f,
        size = 0.12f,
        role = StickRole.Keys,
        up = GamepadAction.Key(X11.W),
        down = GamepadAction.Key(X11.S),
        left = GamepadAction.Key(X11.A),
        right = GamepadAction.Key(X11.D),
    )

    private val look = TouchControl(
        id = "look",
        kind = TouchKind.STICK,
        cx = 0.85f,
        cy = 0.6f,
        size = 0.14f,
        role = StickRole.Look,
    )

    private fun translator(vararg controls: TouchControl) =
        TouchControlTranslator(TouchLayout(controls.toList()))

    @Test
    fun `a button presses on the way down and releases on the way up`() {
        val t = translator(fire)
        assertEquals(
            listOf(GuestInput.Key(X11.SPACE, 0, pressed = true)),
            t.onDown(0, fire, 450f, 200f, w, h),
        )
        assertEquals(listOf(GuestInput.Key(X11.SPACE, 0, pressed = false)), t.onUp(0))
    }

    /**
     * **Sliding off a button does not release it.** That is what a physical pad
     * does under a thumb, and the opposite makes a sprint button unusable: a
     * finger that drifts two millimetres mid-fight would drop the key.
     */
    @Test
    fun `a finger sliding off a button keeps holding it`() {
        val t = translator(fire)
        t.onDown(0, fire, 450f, 200f, w, h)
        assertEquals(emptyList<GuestInput>(), t.onMove(0, 10f, 10f, w, h))
        assertEquals(listOf(GuestInput.Key(X11.SPACE, 0, pressed = false)), t.onUp(0))
    }

    /**
     * Two buttons bound to the same key are ordinary — a big one for a thumb and
     * a small one for a finger — and lifting either must not release a key the
     * other is still holding.
     */
    @Test
    fun `two fingers on the same binding release once`() {
        val second = fire.copy(id = "fire2", cx = 0.3f)
        val t = translator(fire, second)
        t.onDown(0, fire, 450f, 200f, w, h)
        assertEquals(emptyList<GuestInput>(), t.onDown(1, second, 270f, 200f, w, h))
        assertEquals(emptyList<GuestInput>(), t.onUp(0))
        assertEquals(listOf(GuestInput.Key(X11.SPACE, 0, pressed = false)), t.onUp(1))
    }

    @Test
    fun `a stick past the deadzone holds its direction key`() {
        val t = translator(stick)
        val centreX = stick.centreX(w)
        val centreY = stick.centreY(h)
        val radius = stick.radiusPx(w, h)

        // Straight up, full deflection.
        val out = t.onDown(0, stick, centreX, centreY - radius, w, h)
        assertEquals(listOf(GuestInput.Key(X11.W, 0, pressed = true)), out)

        // Back to the middle: released, through the translator's own hysteresis.
        assertEquals(
            listOf(GuestInput.Key(X11.W, 0, pressed = false)),
            t.onMove(0, centreX, centreY, w, h),
        )
    }

    /** Inside the deadzone is at rest, which is the whole reason there is one. */
    @Test
    fun `a stick inside the deadzone sends nothing`() {
        val t = translator(stick)
        val radius = stick.radiusPx(w, h)
        val out = t.onDown(
            0,
            stick,
            stick.centreX(w),
            stick.centreY(h) - radius * 0.1f,
            w,
            h,
        )
        assertEquals(emptyList<GuestInput>(), out)
    }

    /**
     * A look pad is the pointer, and it is driven by the tick rather than by the
     * finger — a thumb held still on it generates no events at all.
     */
    @Test
    fun `a look pad moves the pointer on the tick`() {
        val t = translator(look)
        val radius = look.radiusPx(w, h)
        t.onDown(0, look, look.centreX(w) + radius, look.centreY(h), w, h)
        assertTrue(t.looking)

        t.tick(1_000)
        val move = t.tick(1_100).single() as GuestInput.MoveBy
        // A tenth of a second at the default look speed, less the deadzone
        // rescale the shared translator applies.
        assertTrue("moves right", move.dx > 0f)
        assertEquals(0f, move.dy, 0.001f)

        t.onUp(0)
        assertTrue("centred", !t.looking)
    }

    @Test
    fun `a stick and a look pad do not fight over the same slot`() {
        val t = translator(stick, look)
        val out = t.onDown(0, stick, stick.centreX(w), stick.centreY(h) - stick.radiusPx(w, h), w, h)
        assertEquals(listOf(GuestInput.Key(X11.W, 0, pressed = true)), out)
        // The look pad is still centred, so nothing is looking yet.
        assertTrue(!t.looking)

        t.onDown(1, look, look.centreX(w) + look.radiusPx(w, h), look.centreY(h), w, h)
        assertTrue(t.looking)
    }

    /**
     * Changing the layout under a finger releases first.
     *
     * Without it the press was sent under the old layout and the release never
     * would be, and nothing left in the system could let the key go — the failure
     * whose symptom is a character walking into a wall forever.
     */
    @Test
    fun `replacing the layout releases what was held`() {
        val t = translator(fire)
        t.onDown(0, fire, 450f, 200f, w, h)
        val released = t.setLayout(TouchLayout())
        assertEquals(listOf(GuestInput.Key(X11.SPACE, 0, pressed = false)), released)
        // And the finger is forgotten, so its lift does not release twice.
        assertEquals(emptyList<GuestInput>(), t.onUp(0))
    }

    @Test
    fun `reset lets go of a button and a stick together`() {
        val t = translator(fire, stick)
        t.onDown(0, fire, 450f, 200f, w, h)
        t.onDown(1, stick, stick.centreX(w), stick.centreY(h) - stick.radiusPx(w, h), w, h)

        val out = t.reset()
        assertTrue(out.contains(GuestInput.Key(X11.SPACE, 0, pressed = false)))
        assertTrue(out.contains(GuestInput.Key(X11.W, 0, pressed = false)))
    }

    @Test
    fun `a d-pad holds two directions at once`() {
        val dpad = TouchControl(
            id = "dpad",
            kind = TouchKind.DPAD,
            cx = 0.2f,
            cy = 0.6f,
            size = 0.12f,
            up = GamepadAction.Key(X11.UP),
            down = GamepadAction.Key(X11.DOWN),
            left = GamepadAction.Key(X11.LEFT),
            right = GamepadAction.Key(X11.RIGHT),
        )
        val t = translator(dpad)
        val r = dpad.radiusPx(w, h)
        val out = t.onDown(0, dpad, dpad.centreX(w) + r, dpad.centreY(h) - r, w, h)
        assertTrue(out.contains(GuestInput.Key(X11.RIGHT, 0, pressed = true)))
        assertTrue(out.contains(GuestInput.Key(X11.UP, 0, pressed = true)))
    }
}
