package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two numbers the editor exposes, and the one it derives.
 *
 * `releaseZone` is not a setting. Two independent sliders let a user set the
 * release above the engage, and the resulting chatter — a character that stutters
 * instead of walking — is the exact failure the two-threshold design prevents.
 */
class GamepadConfigTest {

    @Test
    fun `the derived release zone reproduces the original pair exactly`() {
        val config = GamepadConfig()
        assertEquals(0.25f, config.deadzone, 0.0001f)
        assertEquals(0.18f, config.releaseZone, 0.0001f)
        assertEquals(900f, config.lookSpeed, 0.0001f)
    }

    @Test
    fun `the release zone is below the deadzone across the whole slider`() {
        var deadzone = GamepadConfig.MIN_DEADZONE
        while (deadzone <= GamepadConfig.MAX_DEADZONE + 0.0001f) {
            val config = GamepadConfig(deadzone = deadzone)
            assertTrue(
                "releaseZone ${config.releaseZone} is not below deadzone $deadzone",
                config.releaseZone < config.deadzone,
            )
            assertTrue("releaseZone ${config.releaseZone} is negative", config.releaseZone > 0f)
            deadzone += 0.01f
        }
    }

    @Test
    fun `the slider bounds bracket the default`() {
        assertTrue(GamepadConfig.MIN_DEADZONE < GamepadConfig.DEFAULT_DEADZONE)
        assertTrue(GamepadConfig.DEFAULT_DEADZONE < GamepadConfig.MAX_DEADZONE)
        assertTrue(GamepadConfig.MIN_LOOK_SPEED < GamepadConfig.DEFAULT_LOOK_SPEED)
        assertTrue(GamepadConfig.DEFAULT_LOOK_SPEED < GamepadConfig.MAX_LOOK_SPEED)
    }

    @Test
    fun `a wider deadzone still engages and releases in the right order`() {
        val config = GamepadConfig(deadzone = GamepadConfig.MAX_DEADZONE)
        val g = GamepadTranslator(GamepadProfile.Default, config)

        // Just under the deadzone: nothing.
        assertEquals(
            emptyList<GuestInput>(),
            g.onSticks(lx = config.deadzone - 0.01f, ly = 0f, rx = 0f, ry = 0f),
        )
        assertEquals(
            listOf(GuestInput.Key(X11.D, 0, pressed = true)),
            g.onSticks(lx = config.deadzone, ly = 0f, rx = 0f, ry = 0f),
        )
        // Between the two thresholds it stays held.
        assertEquals(
            emptyList<GuestInput>(),
            g.onSticks(lx = config.releaseZone + 0.01f, ly = 0f, rx = 0f, ry = 0f),
        )
        assertEquals(
            listOf(GuestInput.Key(X11.D, 0, pressed = false)),
            g.onSticks(lx = config.releaseZone - 0.01f, ly = 0f, rx = 0f, ry = 0f),
        )
    }
}
