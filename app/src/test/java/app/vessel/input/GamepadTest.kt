package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pad translator, at the two places it is easy to get wrong.
 *
 * A stick is analogue and a key is not, so every half-axis is a threshold — and
 * a single threshold chatters. The other is the look stick, which is the only
 * control that has to produce output while no event is arriving at all.
 */
class GamepadTest {

    private val config = GamepadConfig()

    private fun pad() = GamepadTranslator(GamepadProfile.KeyboardAndMouse, config)

    @Test
    fun `the left stick becomes the keys the keyboard profile binds`() {
        val g = pad()
        assertEquals(
            listOf(GuestInput.Key(X11.D, 0, pressed = true)),
            g.onSticks(lx = 1f, ly = 0f, rx = 0f, ry = 0f),
        )
        assertEquals(
            listOf(
                GuestInput.Key(X11.D, 0, pressed = false),
                GuestInput.Key(X11.A, 0, pressed = true),
            ),
            g.onSticks(lx = -1f, ly = 0f, rx = 0f, ry = 0f),
        )
        // Down is positive y on a screen and on a pad, and it is `S`, not `W`.
        assertEquals(
            listOf(
                GuestInput.Key(X11.A, 0, pressed = false),
                GuestInput.Key(X11.S, 0, pressed = true),
            ),
            g.onSticks(lx = 0f, ly = 1f, rx = 0f, ry = 0f),
        )
    }

    @Test
    fun `a stick resting inside the deadzone holds nothing`() {
        val g = pad()
        // A worn stick that rests off-centre would otherwise hold W down
        // forever, and the symptom looks like possession rather than hardware.
        //
        // 0.15 on each axis is a deflection of 0.212, inside the deadzone. It
        // used to read 0.2 and 0.2, which is 0.283 -- outside a *circle* of
        // radius 0.25 and inside the square the old per-axis threshold drew.
        // A stick pushed that far has been pushed; the reason the old number
        // passed is the reason diagonals did not work.
        assertEquals(emptyList<GuestInput>(), g.onSticks(lx = 0.15f, ly = 0.15f, rx = 0f, ry = 0f))
    }

    /**
     * The failure this pins: a thumb pushed north-west walked nowhere.
     *
     * Each direction used to threshold its own axis, which makes the dead area
     * a square. Reaching the deadzone on both axes at once takes a push of
     * `deadzone * 1.41`, so a deflection of 0.3 -- plenty straight up -- put
     * 0.21 on each axis diagonally and pressed nothing at all.
     */
    @Test
    fun `a diagonal just past the deadzone presses both its keys`() {
        val g = pad()
        val diagonal = 0.3f / SQRT_2
        assertTrue(diagonal < config.deadzone)
        assertEquals(
            listOf(
                GuestInput.Key(X11.D, 0, pressed = true),
                GuestInput.Key(X11.S, 0, pressed = true),
            ),
            g.onSticks(lx = diagonal, ly = diagonal, rx = 0f, ry = 0f),
        )
    }

    /**
     * Eight sectors of 45 degrees, and which one the stick is in is the whole
     * answer. Straight up is one key however hard it is pushed -- a cardinal
     * that also pressed its neighbour would be a character that strafes when
     * asked to walk.
     */
    @Test
    fun `a cardinal push is one key and a diagonal is two, at any distance`() {
        for (push in listOf(0.26f, 0.5f, 1f)) {
            assertEquals(
                "straight up at $push",
                listOf(GuestInput.Key(X11.W, 0, pressed = true)),
                pad().onSticks(lx = 0f, ly = -push, rx = 0f, ry = 0f),
            )
            assertEquals(
                "north-west at $push",
                listOf(
                    GuestInput.Key(X11.A, 0, pressed = true),
                    GuestInput.Key(X11.W, 0, pressed = true),
                ),
                pad().onSticks(lx = -push / SQRT_2, ly = -push / SQRT_2, rx = 0f, ry = 0f),
            )
        }
    }

    /**
     * A thumb resting on a sector boundary must not flicker its second key.
     * The key that is on leaves by a wider angle than it entered by, which is
     * the same two-threshold trick the deadzone uses on distance.
     */
    @Test
    fun `a direction already held survives a wobble across the sector line`() {
        val g = pad()
        // 45 degrees: both on.
        g.onSticks(lx = 0.7f, ly = 0.7f, rx = 0f, ry = 0f)

        // 20 degrees, just outside the 22.5 the second key enters at.
        val rad = Math.toRadians(20.0)
        assertEquals(
            emptyList<GuestInput>(),
            g.onSticks(
                lx = Math.cos(rad).toFloat(),
                ly = Math.sin(rad).toFloat(),
                rx = 0f,
                ry = 0f,
            ),
        )

        // 10 degrees, past the wider angle it leaves by.
        val out = Math.toRadians(10.0)
        assertEquals(
            listOf(GuestInput.Key(X11.S, 0, pressed = false)),
            g.onSticks(
                lx = Math.cos(out).toFloat(),
                ly = Math.sin(out).toFloat(),
                rx = 0f,
                ry = 0f,
            ),
        )
    }

    @Test
    fun `a direction releases lower than it engages`() {
        val g = pad()
        g.onSticks(lx = 1f, ly = 0f, rx = 0f, ry = 0f)

        // Between releaseZone and deadzone: already held, so it stays held. One
        // threshold here is a character that stutters instead of walking.
        assertTrue(config.releaseZone < 0.2f && 0.2f < config.deadzone)
        assertEquals(emptyList<GuestInput>(), g.onSticks(lx = 0.2f, ly = 0f, rx = 0f, ry = 0f))

        assertEquals(
            listOf(GuestInput.Key(X11.D, 0, pressed = false)),
            g.onSticks(lx = 0.1f, ly = 0f, rx = 0f, ry = 0f),
        )
    }

    @Test
    fun `a trigger past the threshold is a mouse button`() {
        val g = pad()
        assertEquals(emptyList<GuestInput>(), g.onTrigger(GamepadControl.R2, 0.2f))
        assertEquals(
            listOf(GuestInput.Button(PointerButton.LEFT, pressed = true)),
            g.onTrigger(GamepadControl.R2, 0.9f),
        )
        assertEquals(
            listOf(GuestInput.Button(PointerButton.LEFT, pressed = false)),
            g.onTrigger(GamepadControl.R2, 0f),
        )
    }

    @Test
    fun `a button press is not repeated while it is held`() {
        val g = pad()
        assertEquals(
            listOf(GuestInput.Key(X11.SPACE, 0, pressed = true)),
            g.onButton(GamepadControl.A, true),
        )
        assertEquals(emptyList<GuestInput>(), g.onButton(GamepadControl.A, true))
        assertEquals(
            listOf(GuestInput.Key(X11.SPACE, 0, pressed = false)),
            g.onButton(GamepadControl.A, false),
        )
        assertEquals(emptyList<GuestInput>(), g.onButton(GamepadControl.A, false))
    }

    @Test
    fun `an unbound control produces nothing`() {
        val g = GamepadTranslator(GamepadProfile("empty", emptyMap()), config)
        assertEquals(emptyList<GuestInput>(), g.onButton(GamepadControl.A, true))
        assertEquals(emptyList<GuestInput>(), g.reset())
    }

    // — the look stick -------------------------------------------------------------

    @Test
    fun `the look stick moves the cursor on a clock, not on events`() {
        val g = pad()
        assertFalse(g.looking)
        g.onSticks(lx = 0f, ly = 0f, rx = 1f, ry = 0f)
        assertTrue(g.looking)

        // The first tick has no interval to work from, and guessing one gives a
        // single enormous jump.
        assertEquals(emptyList<GuestInput>(), g.tick(1_000))

        val moved = g.tick(1_100)
        assertEquals(1, moved.size)
        val move = moved.single() as GuestInput.MoveBy
        // Deflection 1.0 rescales to 1.0 past the deadzone, so 100 ms at
        // lookSpeed is a tenth of it.
        assertEquals(config.lookSpeed / 10f, move.dx, 0.01f)
        assertEquals(0f, move.dy, 0.01f)
    }

    @Test
    fun `deflection is rescaled from the deadzone edge`() {
        val g = pad()
        // Halfway between the deadzone and full deflection should be half speed,
        // not (deadzone + half). Without the rescale the cursor jumps to a
        // quarter speed the instant the stick leaves centre.
        val half = config.deadzone + (1f - config.deadzone) / 2f
        g.onSticks(lx = 0f, ly = 0f, rx = half, ry = 0f)
        g.tick(1_000)
        val move = g.tick(1_100).single() as GuestInput.MoveBy
        assertEquals(config.lookSpeed / 20f, move.dx, 0.01f)
    }

    @Test
    fun `a long gap does not fling the cursor`() {
        val g = pad()
        g.onSticks(lx = 0f, ly = 0f, rx = 1f, ry = 0f)
        g.tick(1_000)
        // The app was backgrounded for ten seconds. Ten seconds of travel at
        // lookSpeed is most of a desktop.
        val move = g.tick(11_000).single() as GuestInput.MoveBy
        assertEquals(config.lookSpeed / 10f, move.dx, 0.01f)
    }

    @Test
    fun `reset releases everything a disconnecting pad was holding`() {
        val g = pad()
        g.onButton(GamepadControl.A, true)
        g.onSticks(lx = 1f, ly = 0f, rx = 1f, ry = 0f)

        val released = g.reset()
        assertEquals(
            setOf(
                GuestInput.Key(X11.SPACE, 0, pressed = false),
                GuestInput.Key(X11.D, 0, pressed = false),
            ),
            released.toSet(),
        )
        assertFalse(g.looking)
        assertEquals(emptyList<GuestInput>(), g.reset())
    }

    @Test
    fun `the hat is the d-pad for a pad that reports it as an axis`() {
        val g = pad()
        assertEquals(
            listOf(GuestInput.Key(X11.RIGHT, 0, pressed = true)),
            g.onHat(1f, 0f),
        )
        assertEquals(
            listOf(
                GuestInput.Key(X11.RIGHT, 0, pressed = false),
                GuestInput.Key(X11.UP, 0, pressed = true),
            ),
            g.onHat(0f, -1f),
        )
    }

    @Test
    fun `every default binding names a keycode the server can carry`() {
        GamepadProfile.KeyboardAndMouse.bindings.forEach { (control, action) ->
            if (action is GamepadAction.Key) {
                assertTrue(
                    "$control binds ${action.keycode}, which is outside the server's range",
                    action.keycode in X11.MIN_KEYCODE..X11.MAX_KEYCODE,
                )
            }
        }
    }

    private companion object {
        /** A diagonal of length L puts L / sqrt(2) on each axis. */
        const val SQRT_2 = 1.41421356f
    }

}
