package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stick is data now, not a hardwiring.
 *
 * `GamepadTest` pins that the default still behaves exactly as the hardwired
 * version did. This pins the three arrangements the default is not: a right
 * stick sending keys, a left stick looking, and a stick told to do nothing.
 */
class GamepadStickRoleTest {

    private val config = GamepadConfig()

    private fun profile(
        left: StickRole,
        right: StickRole,
        bindings: Map<GamepadControl, GamepadAction> = RIGHT_ARROWS,
    ) = GamepadProfile(
        name = "test",
        bindings = bindings,
        sticks = mapOf(Stick.LEFT to left, Stick.RIGHT to right),
    )

    @Test
    fun `the right stick sends keys when that is its role`() {
        val g = GamepadTranslator(profile(StickRole.None, StickRole.Keys), config)

        assertEquals(
            listOf(GuestInput.Key(X11.RIGHT, 0, pressed = true)),
            g.onSticks(lx = 0f, ly = 0f, rx = 1f, ry = 0f),
        )
        // And it is the *right* stick's controls: the left stick moving in the
        // same direction while set to None must not produce the same key twice.
        assertEquals(emptyList<GuestInput>(), g.onSticks(lx = 1f, ly = 0f, rx = 1f, ry = 0f))
        assertFalse(g.looking)
    }

    @Test
    fun `the left stick looks when that is its role`() {
        val g = GamepadTranslator(profile(StickRole.Look, StickRole.None), config)

        g.onSticks(lx = 1f, ly = 0f, rx = 0f, ry = 0f)
        assertTrue(g.looking)
        g.tick(1_000)
        val move = g.tick(1_100).single() as GuestInput.MoveBy
        assertEquals(config.lookSpeed / 10f, move.dx, 0.01f)

        // The left stick's own half-axis bindings stay silent while it looks.
        assertEquals(
            emptyList<GuestInput>(),
            GamepadTranslator(
                profile(StickRole.Look, StickRole.None, GamepadProfile.Default.bindings),
                config,
            ).onSticks(lx = 1f, ly = 0f, rx = 0f, ry = 0f),
        )
    }

    @Test
    fun `two sticks set to look sum their deflections`() {
        val g = GamepadTranslator(profile(StickRole.Look, StickRole.Look), config)

        // Half deflection each, same direction, is one full deflection.
        val half = config.deadzone + (1f - config.deadzone) / 2f
        g.onSticks(lx = half, ly = 0f, rx = half, ry = 0f)
        g.tick(1_000)
        assertEquals(
            config.lookSpeed / 10f,
            (g.tick(1_100).single() as GuestInput.MoveBy).dx,
            0.01f,
        )

        // Opposite directions cancel, which is the same arithmetic read the
        // other way and the reason the sum is not two independent cursors.
        g.onSticks(lx = 1f, ly = 0f, rx = -1f, ry = 0f)
        assertFalse(g.looking)
    }

    @Test
    fun `a stick set to none is silent`() {
        val g = GamepadTranslator(profile(StickRole.None, StickRole.None), config)
        assertEquals(emptyList<GuestInput>(), g.onSticks(lx = 1f, ly = 1f, rx = 1f, ry = 1f))
        assertFalse(g.looking)
    }

    @Test
    fun `a stick that stops sending keys lets go of what it was holding`() {
        val g = GamepadTranslator(profile(StickRole.None, StickRole.Keys), config)
        assertEquals(
            listOf(GuestInput.Key(X11.RIGHT, 0, pressed = true)),
            g.onSticks(lx = 0f, ly = 0f, rx = 1f, ry = 0f),
        )

        // A guest left holding a key after its binding changed has nothing left
        // that can ever release it, so the change itself releases it.
        g.profile = profile(StickRole.None, StickRole.Look)
        assertEquals(
            listOf(GuestInput.Key(X11.RIGHT, 0, pressed = false)),
            g.onSticks(lx = 0f, ly = 0f, rx = 1f, ry = 0f),
        )
        assertTrue(g.looking)
    }

    @Test
    fun `an absent stick in the map is nothing at all`() {
        val g = GamepadTranslator(
            GamepadProfile(name = "bare", bindings = RIGHT_ARROWS, sticks = emptyMap()),
            config,
        )
        assertEquals(emptyList<GuestInput>(), g.onSticks(lx = 1f, ly = 1f, rx = 1f, ry = 1f))
        assertFalse(g.looking)
    }

    private companion object {
        val RIGHT_ARROWS = mapOf(
            GamepadControl.STICK_R_UP to GamepadAction.Key(X11.UP),
            GamepadControl.STICK_R_DOWN to GamepadAction.Key(X11.DOWN),
            GamepadControl.STICK_R_LEFT to GamepadAction.Key(X11.LEFT),
            GamepadControl.STICK_R_RIGHT to GamepadAction.Key(X11.RIGHT),
        )
    }
}
