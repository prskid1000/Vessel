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

    /**
     * The binding wins over the identity, which is the whole of "I can switch the
     * mapping if I want": the glass `A` is made to press `B` on the guest's pad.
     */
    @Test
    fun `a control bound to a pad control sends that one, not the one it is`() {
        val a = TouchControl(
            id = "a",
            kind = TouchKind.BUTTON,
            cx = 0.5f,
            cy = 0.5f,
            size = 0.1f,
            pad = GamepadControl.A,
            action = GamepadAction.Pad(GamepadControl.B),
        )
        val t = translator(a)
        // Nothing on the X11 seam: a pad binding travels the socket instead.
        assertEquals(emptyList<GuestInput>(), t.onDown(0, a, 450f, 200f, w, h))
        assertEquals(setOf(GamepadControl.B), t.padSnapshot().pressed)
    }

    /** An unbound control that *is* one of the twenty-four still sends itself. */
    @Test
    fun `identity is the fallback when nothing is bound`() {
        val a = TouchControl(
            id = "a",
            kind = TouchKind.BUTTON,
            cx = 0.5f,
            cy = 0.5f,
            size = 0.1f,
            pad = GamepadControl.A,
        )
        val t = translator(a)
        t.onDown(0, a, 450f, 200f, w, h)
        assertEquals(setOf(GamepadControl.A), t.padSnapshot().pressed)
    }

    /**
     * **The regression this pins: every direction on the built-in d-pad also
     * reported up.** [TouchControl.pad] on a cross is its identity -- one link
     * field for a control with four directions, and the stock layout fills it
     * with `DPAD_UP`. Read as a press, a finger anywhere on the cross put
     * `DPAD_UP` in the snapshot and the session's merge then added the real
     * direction on top, so pressing right sent up *and* right and pressing down
     * sent up *and* down. The deflection is the whole answer for a d-pad.
     */
    @Test
    fun `a d-pad reports its direction only, never the control it is named for`() {
        val dpad = TouchControl(
            id = "dpad",
            kind = TouchKind.DPAD,
            cx = 0.115f,
            cy = 0.40f,
            size = 0.120f,
            pad = GamepadControl.DPAD_UP,
        )
        val t = translator(dpad)
        val radius = dpad.radiusPx(w, h)

        // A thumb hard right of centre.
        t.onDown(0, dpad, dpad.centreX(w) + radius, dpad.centreY(h), w, h)
        val right = t.padSnapshot()
        assertEquals(emptySet<GamepadControl>(), right.pressed)
        assertEquals(1f, right.hatX, 0.001f)
        assertEquals(0f, right.hatY, 0.001f)

        // And hard down, the direction that used to arrive as up-and-down at once.
        t.onMove(0, dpad.centreX(w), dpad.centreY(h) + radius, w, h)
        val down = t.padSnapshot()
        assertEquals(emptySet<GamepadControl>(), down.pressed)
        assertEquals(0f, down.hatX, 0.001f)
        assertEquals(1f, down.hatY, 0.001f)

        t.onUp(0)
        assertTrue(t.padSnapshot().idle)
    }

    /**
     * The whole d-pad path on the stock controller, end to end, one direction at
     * a time -- laid out, hit-tested, resolved against the pad table and pressed.
     *
     * **This is the claim the split was made for.** Four controls that each
     * carry the direction they send cannot report a direction they do not send,
     * and this asserts it the way a thumb would find out: land on the arm, see
     * what the guest gets. Both halves of the seam, because a d-pad direction can
     * be either -- arrow keys on the default profile, and the guest's own hat
     * when the pad table says `Pad`.
     */
    @Test
    fun `each stock d-pad arm sends its own direction and nothing else`() {
        val keys = mapOf(
            GamepadControl.DPAD_UP to X11.UP,
            GamepadControl.DPAD_DOWN to X11.DOWN,
            GamepadControl.DPAD_LEFT to X11.LEFT,
            GamepadControl.DPAD_RIGHT to X11.RIGHT,
        )
        // `KeyboardAndMouse` binds the four to arrow keys, so on that profile
        // they travel the X11 seam. Resolved, because that is what a session
        // actually runs -- the arms hold no binding of their own to read.
        val layout = InputProfile.Default.copy(pad = GamepadProfile.KeyboardAndMouse).overlay
        val t = TouchControlTranslator(layout)

        keys.forEach { (direction, keycode) ->
            val arm = layout.controls.single { it.pad == direction }
            // Found by hit test, not by hand: the arms have to be separately
            // reachable or none of the rest of this means anything.
            val hit = layout.hitTest(arm.centreX(w), arm.centreY(h), w, h)
            assertEquals("$direction is under its own centre", arm.id, hit?.id)

            assertEquals(
                listOf(GuestInput.Key(keycode, 0, pressed = true)),
                t.onDown(0, hit!!, arm.centreX(w), arm.centreY(h), w, h),
            )
            // **And nothing at all on the pad.** This profile bound the arm to
            // a key, so the key is the whole of what it sends -- an arm that
            // also pressed itself on a gamepad would have a game that reads both
            // moving twice per press, which is exactly what `KeyboardAndMouse`
            // exists to avoid for a guest that has no pad to read.
            assertTrue(t.padSnapshot().idle)
            assertEquals(listOf(GuestInput.Key(keycode, 0, pressed = false)), t.onUp(0))
        }
    }

    /**
     * The same four on the shipping default, where every control sends itself to
     * the guest's own pad -- so this is the path a user actually gets.
     */
    @Test
    fun `each stock d-pad arm reaches the guest hat as itself`() {
        val layout = InputProfile.Default.overlay
        val t = TouchControlTranslator(layout)

        TouchControls.DPAD_DIRECTIONS.forEach { direction ->
            val arm = layout.controls.single { it.pad == direction }
            // Silent on the X11 seam -- a pad binding travels the socket.
            assertEquals(
                emptyList<GuestInput>(),
                t.onDown(0, arm, arm.centreX(w), arm.centreY(h), w, h),
            )
            val snapshot = t.padSnapshot()
            assertEquals("$direction alone", setOf(direction), snapshot.pressed)
            // And no deflection: an arm is a button, and the hat it feeds is the
            // one in `pressed`. This is the assertion the old cross failed.
            assertEquals(0f, snapshot.hatX, 0.0001f)
            assertEquals(0f, snapshot.hatY, 0.0001f)
            t.onUp(0)
            assertTrue(t.padSnapshot().idle)
        }
    }

    /**
     * A pad control bound to a key sends the key and *only* the key.
     *
     * **The identity is a fallback, not an addition.** It read `?: control.pad`
     * for every action that was not a `Pad`, so a key binding fell through to it
     * and the control went out on both wires at once. The whole stock controller
     * on `KeyboardAndMouse` did this: `A` sent the space bar and pressed `A` on
     * the guest's gamepad, and a game reading both got two of everything.
     */
    @Test
    fun `a pad control bound to a key stays off the guest pad`() {
        val a = TouchControl(
            id = "a",
            kind = TouchKind.BUTTON,
            cx = 0.5f,
            cy = 0.5f,
            size = 0.1f,
            pad = GamepadControl.A,
            action = GamepadAction.Key(X11.SPACE),
        )
        val t = translator(a)
        assertEquals(
            listOf(GuestInput.Key(X11.SPACE, 0, pressed = true)),
            t.onDown(0, a, 450f, 200f, w, h),
        )
        assertTrue("bound to a key, so nothing on the pad", t.padSnapshot().idle)

        // A pointer button is the same story on the same wire.
        val click = a.copy(id = "click", action = GamepadAction.Button(PointerButton.RIGHT))
        val u = translator(click)
        u.onDown(0, click, 450f, 200f, w, h)
        assertTrue("bound to a click, so nothing on the pad", u.padSnapshot().idle)
    }

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
