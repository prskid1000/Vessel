package app.vessel.data

import app.vessel.input.GamepadAction
import app.vessel.input.GamepadControl
import app.vessel.input.InputProfile
import app.vessel.input.TouchControl
import app.vessel.input.TouchControls
import app.vessel.input.TouchKind
import app.vessel.input.TouchLayout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A file arriving from outside the app, and the one place a version number is
 * actually consulted.
 *
 * Every other reader in this product is deliberately total — it drops what it
 * cannot understand and carries on — because a throw inside `containers.json`
 * costs the user every container they have. An import is the opposite situation:
 * nothing is lost by refusing, the user chose the file, and reading a newer
 * schema optimistically is how a keycode the vendored server would throw on ends
 * up in a binding table.
 */
class InputProfileImportTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun round(profile: InputProfile, taken: List<String> = emptyList()): ImportResult =
        InputProfileTransfer.import(json, InputProfileTransfer.export(json, profile), taken)

    private val sample = InputProfile(
        id = "abc",
        name = "Racing",
        touch = TouchLayout(
            listOf(
                TouchControl(
                    id = "a",
                    kind = TouchKind.BUTTON,
                    cx = 0.4f,
                    cy = 0.4f,
                    action = GamepadAction.Key(app.vessel.input.X11.SPACE),
                ),
            ),
        ),
    )

    @Test
    fun `a profile survives a round trip`() {
        val result = round(sample) as ImportResult.Ok
        assertEquals("Racing", result.profile.name)
        assertEquals(1, result.profile.touch.controls.size)
        assertEquals(
            sample.pad.bindings[GamepadControl.A],
            result.profile.pad.bindings[GamepadControl.A],
        )
    }

    /** A kept id would replace the profile it collided with, and the user asked to *add* one. */
    @Test
    fun `an import always gets a fresh id`() {
        val result = round(sample) as ImportResult.Ok
        assertNotEquals("abc", result.profile.id)
        assertTrue(result.profile.id.isNotBlank())
    }

    @Test
    fun `a colliding name is numbered rather than shadowing`() {
        val result = round(sample, taken = listOf("Racing")) as ImportResult.Ok
        assertEquals("Racing (2)", result.profile.name)

        val again = round(sample, taken = listOf("Racing", "Racing (2)")) as ImportResult.Ok
        assertEquals("Racing (3)", again.profile.name)
    }

    @Test
    fun `an unknown schema version is refused with a sentence`() {
        val text = json.encodeToString(
            InputProfileEnvelope.serializer(),
            InputProfileEnvelope(CURRENT_INPUT_SCHEMA + 1, StoredInputProfile.of(sample)),
        )
        val result = InputProfileTransfer.import(json, text, emptyList()) as ImportResult.Refused
        assertTrue(result.reason.contains("different version"))
        assertTrue(result.reason.contains("${CURRENT_INPUT_SCHEMA + 1}"))
    }

    @Test
    fun `something that is not a profile at all is refused rather than thrown`() {
        val result = InputProfileTransfer.import(json, "{\"nope\":1}", emptyList())
        assertTrue(result is ImportResult.Refused)
    }

    /**
     * **A keycode outside 8..127 is dropped.** The vendored keyboard API is
     * `byte`-typed end to end, so 128 arrives as −128 and throws on the X client
     * thread the first time the key is pressed — two layers from the file.
     */
    @Test
    fun `an out-of-range keycode does not survive`() {
        val text = json.encodeToString(
            InputProfileEnvelope.serializer(),
            InputProfileEnvelope(
                CURRENT_INPUT_SCHEMA,
                StoredInputProfile.of(sample).copy(
                    pad = mapOf("A" to StoredAction(StoredAction.KIND_KEY, keycode = 200)),
                ),
            ),
        )
        val result = InputProfileTransfer.import(json, text, emptyList()) as ImportResult.Ok
        assertEquals(GamepadAction.None, result.profile.pad.bindings[GamepadControl.A])
    }

    /** An imported control is always on screen and always big enough to hit. */
    @Test
    fun `a control positioned off screen is clamped and floored`() {
        val text = json.encodeToString(
            InputProfileEnvelope.serializer(),
            InputProfileEnvelope(
                CURRENT_INPUT_SCHEMA,
                StoredInputProfile.of(sample).copy(
                    touch = listOf(
                        StoredTouchControl(
                            id = "wild",
                            kind = "BUTTON",
                            cx = 9f,
                            cy = -3f,
                            size = 0f,
                            opacity = 5f,
                        ),
                    ),
                ),
            ),
        )
        val control = (InputProfileTransfer.import(json, text, emptyList()) as ImportResult.Ok)
            .profile.touch.controls.single()
        assertEquals(1f, control.cx, 0f)
        assertEquals(0f, control.cy, 0f)
        assertEquals(TouchControls.MIN_SIZE, control.size, 0f)
        assertEquals(TouchControls.MAX_OPACITY, control.opacity, 0f)
    }

    @Test
    fun `an unreadable control kind is dropped rather than guessed at`() {
        val text = json.encodeToString(
            InputProfileEnvelope.serializer(),
            InputProfileEnvelope(
                CURRENT_INPUT_SCHEMA,
                StoredInputProfile.of(sample).copy(
                    touch = listOf(
                        StoredTouchControl(id = "x", kind = "TRACKBALL", cx = 0.5f, cy = 0.5f, size = 0.1f),
                    ),
                ),
            ),
        )
        val result = InputProfileTransfer.import(json, text, emptyList()) as ImportResult.Ok
        assertTrue(result.profile.touch.isEmpty)
    }

    @Test
    fun `an exported file is named after the profile`() {
        assertEquals("Racing.vessel-input.json", InputProfileTransfer.fileName(sample))
        assertEquals(
            "input-profile.vessel-input.json",
            InputProfileTransfer.fileName(sample.copy(name = "   ")),
        )
    }
}
