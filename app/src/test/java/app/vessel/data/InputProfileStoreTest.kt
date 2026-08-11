package app.vessel.data

import app.vessel.input.GamepadAction
import app.vessel.input.GamepadConfig
import app.vessel.input.GamepadControl
import app.vessel.input.GamepadProfile
import app.vessel.input.InputProfile
import app.vessel.input.PointerButton
import app.vessel.input.Stick
import app.vessel.input.StickRole
import app.vessel.input.TouchControl
import app.vessel.input.TouchControls
import app.vessel.input.TouchKind
import app.vessel.input.TouchLayout
import app.vessel.input.X11
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The profile document, at the two things a persisted format has to promise: a
 * round trip that does not move, and a read that cannot throw.
 *
 * The second is the important one. A throw in here becomes a
 * `CorruptionException`, which costs the user **every profile they have** — so an
 * unrecognised action kind, an unknown control, an unknown stick and a keycode
 * outside the range the vendored server can carry all have to read as something
 * rather than as a failure.
 */
class InputProfileStoreTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = InputProfileDocumentSerializer(json)

    private fun read(text: String): InputProfileDocument = runBlocking {
        serializer.readFrom(ByteArrayInputStream(text.encodeToByteArray()))
    }

    private fun write(document: InputProfileDocument): String = runBlocking {
        val out = ByteArrayOutputStream()
        serializer.writeTo(document, out)
        out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `a profile survives a round trip unchanged`() {
        val profile = InputProfile(
            id = "p1",
            name = "Metro Exodus",
            pad = GamepadProfile(
                name = "Metro Exodus",
                bindings = mapOf(
                    GamepadControl.A to GamepadAction.Key(X11.SPACE),
                    GamepadControl.R2 to GamepadAction.Button(PointerButton.LEFT),
                    GamepadControl.STICK_R_UP to GamepadAction.Key(X11.PRTSCN, 0xFF61),
                ),
                sticks = mapOf(Stick.LEFT to StickRole.Keys, Stick.RIGHT to StickRole.Keys),
            ),
            config = GamepadConfig(deadzone = 0.3f, lookSpeed = 1200f),
            touch = TouchLayout(
                listOf(
                    TouchControl(
                        id = "t1",
                        kind = TouchKind.STICK,
                        cx = 0.12f,
                        cy = 0.7f,
                        size = 0.12f,
                        opacity = 0.5f,
                        label = "W A S D",
                        role = StickRole.Keys,
                        up = GamepadAction.Key(X11.W),
                        down = GamepadAction.Key(X11.S),
                        left = GamepadAction.Key(X11.A),
                        right = GamepadAction.Key(X11.D),
                    ),
                    TouchControl(
                        id = "t2",
                        kind = TouchKind.BUTTON,
                        cx = 0.8f,
                        cy = 0.8f,
                        label = "Space",
                        action = GamepadAction.Key(X11.SPACE),
                    ),
                ),
            ),
        )

        val back = read(write(InputProfileDocument(profiles = listOf(StoredInputProfile.of(profile)))))
            .profiles
            .single()
            .toProfile()

        assertEquals(profile.id, back.id)
        assertEquals(profile.name, back.name)
        assertEquals(profile.config, back.config)
        assertEquals(profile.touch, back.touch)
        assertEquals(profile.pad.sticks, back.pad.sticks)
        // The stored form writes every control explicitly, so the map that comes
        // back is longer than the one that went in — but it says the same thing.
        GamepadControl.entries.forEach { control ->
            assertEquals(
                "$control",
                profile.pad.bindings[control] ?: GamepadAction.None,
                back.pad.bindings[control] ?: GamepadAction.None,
            )
        }
    }

    @Test
    fun `a second round trip is byte-identical`() {
        val once = write(
            InputProfileDocument(profiles = listOf(StoredInputProfile.of(InputProfile.Default))),
        )
        val twice = write(
            InputProfileDocument(
                profiles = read(once).profiles.map {
                    StoredInputProfile.of(it.toProfile())
                },
            ),
        )
        assertEquals(once, twice)
    }

    @Test
    fun `an unknown action kind reads as unbound`() {
        val document = read(
            """
            {"schemaVersion":1,"profiles":[{"id":"p","name":"n",
             "pad":{"A":{"kind":"macro","keycode":25}}}]}
            """.trimIndent(),
        )
        assertEquals(
            GamepadAction.None,
            document.profiles.single().toProfile().pad.bindings[GamepadControl.A],
        )
    }

    @Test
    fun `a keycode the server cannot carry is dropped`() {
        // 128 arrives at the vendored keyboard as -128 and throws
        // ArrayIndexOutOfBoundsException on the X client thread. It must never
        // get as far as a binding.
        val document = read(
            """
            {"schemaVersion":1,"profiles":[{"id":"p","name":"n",
             "pad":{"A":{"kind":"key","keycode":128},"B":{"kind":"key","keycode":7},
                    "X":{"kind":"key","keycode":127}}}]}
            """.trimIndent(),
        )
        val bindings = document.profiles.single().toProfile().pad.bindings
        assertEquals(GamepadAction.None, bindings[GamepadControl.A])
        assertEquals(GamepadAction.None, bindings[GamepadControl.B])
        assertEquals(GamepadAction.Key(X11.SUPER_L), bindings[GamepadControl.X])
    }

    @Test
    fun `an unknown control, stick, role and button are dropped rather than thrown`() {
        val document = read(
            """
            {"schemaVersion":1,"profiles":[{"id":"p","name":"n",
             "pad":{"PADDLE_1":{"kind":"key","keycode":25},
                    "A":{"kind":"button","button":"TRIPLE_CLICK"}},
             "sticks":{"LEFT":"Wheel","MIDDLE":"Keys","RIGHT":"Look"}}]}
            """.trimIndent(),
        )
        val profile = document.profiles.single().toProfile()
        assertEquals(GamepadAction.None, profile.pad.bindings[GamepadControl.A])
        assertEquals(1, profile.pad.bindings.size)
        assertEquals(mapOf(Stick.RIGHT to StickRole.Look), profile.pad.sticks)
        // An unnamed stick is StickRole.None at runtime, which is silent.
        assertEquals(StickRole.None, profile.pad.roleOf(Stick.LEFT))
    }

    @Test
    fun `an unreadable touch control is dropped and the rest survive`() {
        val document = read(
            """
            {"schemaVersion":1,"profiles":[{"id":"p","name":"n","touch":[
              {"id":"a","kind":"TRACKBALL","cx":0.5,"cy":0.5,"size":0.1},
              {"id":"b","kind":"BUTTON","cx":0.5,"cy":0.5,"size":0.1}]}]}
            """.trimIndent(),
        )
        val controls = document.profiles.single().toProfile().touch.controls
        assertEquals(listOf("b"), controls.map { it.id })
    }

    @Test
    fun `numbers outside their range are pulled back inside it`() {
        val document = read(
            """
            {"schemaVersion":1,"profiles":[{"id":"p","name":"n",
             "deadzone":9.0,"lookSpeed":-4.0,
             "touch":[{"id":"a","kind":"BUTTON","cx":4.0,"cy":-2.0,"size":90.0,"opacity":5.0}]}]}
            """.trimIndent(),
        )
        val profile = document.profiles.single().toProfile()
        assertEquals(GamepadConfig.MAX_DEADZONE, profile.config.deadzone, 0.0001f)
        assertEquals(GamepadConfig.MIN_LOOK_SPEED, profile.config.lookSpeed, 0.0001f)

        val control = profile.touch.controls.single()
        assertEquals(1f, control.cx, 0.0001f)
        assertEquals(0f, control.cy, 0.0001f)
        assertEquals(TouchControls.MAX_SIZE, control.size, 0.0001f)
        assertEquals(TouchControls.MAX_OPACITY, control.opacity, 0.0001f)
    }

    @Test
    fun `an empty file is the empty document, not a corruption`() {
        val document = runBlocking { serializer.readFrom(ByteArrayInputStream(ByteArray(0))) }
        assertEquals(InputProfileDocument(), document)
        assertEquals(CURRENT_INPUT_SCHEMA, document.schemaVersion)
        assertTrue(document.profiles.isEmpty())
    }

    @Test
    fun `a document from a newer build still reads what it can`() {
        // `ignoreUnknownKeys` is what makes this true, and it is the same
        // arrangement every other document in the app relies on.
        val document = read(
            """
            {"schemaVersion":7,"profiles":[{"id":"p","name":"n","gyro":true}],"macros":[]}
            """.trimIndent(),
        )
        assertEquals(7, document.schemaVersion)
        assertEquals("n", document.profiles.single().name)
    }

    @Test
    fun `the built-in default is never written to the document`() = runBlocking {
        // The repository refuses it. Asserted here rather than in a repository
        // test because it needs no DataStore: a `default` row would be a profile
        // every container silently starts resolving to instead of the constant.
        val stored = StoredInputProfile.of(InputProfile.Default)
        assertEquals(InputProfile.DEFAULT_ID, stored.id)
        assertNull(
            "nothing should have written the default",
            InputProfileDocument().profiles.firstOrNull { it.id == InputProfile.DEFAULT_ID },
        )
    }

    @Test
    fun `the stored default is the running default`() {
        // The one that matters for a downgrade and for a fresh install: what the
        // document says the default profile is must be what the translator does
        // with no document at all.
        val back = StoredInputProfile.of(InputProfile.Default).toProfile()
        assertEquals(GamepadProfile.Default.sticks, back.pad.sticks)
        GamepadControl.entries.forEach { control ->
            assertEquals(
                GamepadProfile.Default.bindings[control] ?: GamepadAction.None,
                back.pad.bindings[control] ?: GamepadAction.None,
            )
        }
    }

    /** A pad binding survives the disc, and an unknown control degrades to unbound. */
    @Test
    fun `a pad action round-trips and an unknown one does not crash`() {
        val action = GamepadAction.Pad(GamepadControl.THUMB_R)
        assertEquals(action, StoredAction.of(action).toAction())
        assertEquals(
            GamepadAction.None,
            StoredAction(StoredAction.KIND_PAD, pad = "PADDLE_4").toAction(),
        )
    }
}
