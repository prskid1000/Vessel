package app.vessel.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Every input profile on this device, as one JSON document.
 *
 * **Its own file, following the argument already made for `shortcuts.json`
 * against `containers.json`: separate failure domains.** Losing your bindings
 * must not cost you your containers, and the reverse would be much worse. It also
 * survives a downgrade intact — an older build never opens this file at all, so
 * the profiles are still here afterwards and the user re-picks one per container.
 */
@Serializable
data class InputProfileDocument(
    val schemaVersion: Int = CURRENT_INPUT_SCHEMA,
    val profiles: List<StoredInputProfile> = emptyList(),
)

/** Bumped when the shape changes in a way a reader has to know about. */
const val CURRENT_INPUT_SCHEMA: Int = 1

/** The file the document lives in, under `files/datastore/`. */
const val INPUT_PROFILES_FILE: String = "input-profiles.json"

/**
 * One [GamepadAction], as a **tagged record** rather than as sealed-class
 * polymorphism.
 *
 * Two reasons, and the second is the important one. The app's single `Json` is
 * built with no `SerializersModule`, and adding one changes how every other
 * document in the app is read. And a tagged record keeps the reader *total*: an
 * unrecognised [kind] decodes as nothing rather than throwing, and a throw here
 * is a `CorruptionException` that costs the user every profile they have.
 */
@Serializable
data class StoredAction(
    val kind: String = KIND_NONE,
    val keycode: Int = 0,
    val keysym: Int = 0,
    val button: String = "",
    /** The pad control, by enum name, for [KIND_PAD]. See [StoredShortcut]'s rule. */
    val pad: String = "",
) {
    companion object {
        const val KIND_NONE: String = "none"
        const val KIND_KEY: String = "key"
        const val KIND_BUTTON: String = "button"
        const val KIND_PAD: String = "pad"

        val None = StoredAction()

        fun of(action: GamepadAction): StoredAction = when (action) {
            GamepadAction.None -> None
            is GamepadAction.Key -> StoredAction(KIND_KEY, action.keycode, action.keysym)
            is GamepadAction.Button -> StoredAction(KIND_BUTTON, button = action.button.name)
            is GamepadAction.Pad -> StoredAction(KIND_PAD, pad = action.control.name)
        }
    }

    /**
     * The runtime form, or [GamepadAction.None] for anything this build cannot
     * make sense of.
     *
     * **A keycode outside 8..127 is dropped rather than carried.** The vendored
     * keyboard API is `byte`-typed end to end, so 128 arrives as −128, indexes
     * the keysym array at −272 and throws `ArrayIndexOutOfBoundsException` on the
     * X client thread the first time the key is pressed — two layers away from
     * the file that caused it.
     */
    fun toAction(): GamepadAction = when (kind) {
        KIND_KEY ->
            if (keycode in X11.MIN_KEYCODE..X11.MAX_KEYCODE) {
                GamepadAction.Key(keycode, keysym)
            } else {
                GamepadAction.None
            }

        KIND_BUTTON ->
            PointerButton.entries.firstOrNull { it.name == button }
                ?.let { GamepadAction.Button(it) }
                ?: GamepadAction.None

        // A control this build does not have is unbound rather than a crash, the
        // same rule the keycode range above follows: a profile written by a newer
        // build should lose one binding, not fail to open.
        KIND_PAD ->
            GamepadControl.entries.firstOrNull { it.name == pad }
                ?.let { GamepadAction.Pad(it) }
                ?: GamepadAction.None

        else -> GamepadAction.None
    }
}

/** One on-screen control, as stored. Enums are names; see [StoredShortcut]'s rule. */
@Serializable
data class StoredTouchControl(
    val id: String,
    val kind: String,
    val cx: Float,
    val cy: Float,
    val size: Float,
    val opacity: Float = TouchControls.DEFAULT_OPACITY,
    val label: String = "",
    val action: StoredAction = StoredAction.None,
    val role: String = "Keys",
    val up: StoredAction = StoredAction.None,
    val down: StoredAction = StoredAction.None,
    val left: StoredAction = StoredAction.None,
    val right: StoredAction = StoredAction.None,
    /**
     * The pad control this control *is*, by name, or empty.
     *
     * Stored rather than resolved so the link survives a round trip: a control
     * whose action was flattened on the way to disk would stop following the pad
     * table the moment it was read back, and the whole point of the built-in
     * layout is that it never stops following it. An unknown name degrades to no
     * link, which leaves the control's own stored action in charge.
     */
    val pad: String = "",
    val padStick: String = "",
    /**
     * Whether a long press latches this button down, or null for "never said".
     *
     * **Nullable so a profile written before the setting existed can still get
     * it.** Every such profile decodes `false` for a plain `Boolean`, which is
     * indistinguishable from a user who turned it off � so the feature would
     * arrive switched off for everyone who already had a profile, which is every
     * user, and look broken rather than absent.
     *
     * Null instead means the control has no opinion and takes the built-in one
     * for the pad control it is. Writing always stores a real value, so a
     * profile only answers null once.
     */
    val latching: Boolean? = null,
) {
    /** Null for a kind this build has never heard of — dropped, not guessed at. */
    fun toControl(): TouchControl? {
        val touchKind = TouchKind.entries.firstOrNull { it.name == kind } ?: return null
        return TouchControl(
            id = id,
            kind = touchKind,
            cx = cx,
            cy = cy,
            size = size,
            opacity = opacity,
            label = label,
            action = action.toAction(),
            role = StickRole.byName(role) ?: StickRole.Keys,
            up = up.toAction(),
            down = down.toAction(),
            left = left.toAction(),
            right = right.toAction(),
            // Null takes the built-in answer for this pad control, so L1, L2, L3
            // and R3 latch in a profile written before the setting existed.
            latching = latching
                ?: (GamepadControl.entries.firstOrNull { it.name == pad } in DEFAULT_LATCHING),
            pad = GamepadControl.entries.firstOrNull { it.name == pad },
            padStick = Stick.entries.firstOrNull { it.name == padStick },
        ).sane()
    }

    companion object {
        /**
         * The controls the built-in pad latches, for a profile that predates the
         * setting. The same four [app.vessel.input.TouchLayouts.Gamepad] sets:
         * the ones a hand cannot hold and play at the same time.
         */
        private val DEFAULT_LATCHING = setOf(
            GamepadControl.L1,
            GamepadControl.L2,
            GamepadControl.THUMB_L,
            GamepadControl.THUMB_R,
        )

        fun of(control: TouchControl): StoredTouchControl = with(control.sane()) {
            StoredTouchControl(
                id = id,
                kind = kind.name,
                cx = cx,
                cy = cy,
                size = size,
                opacity = opacity,
                label = label,
                action = StoredAction.of(action),
                role = StickRole.nameOf(role),
                up = StoredAction.of(up),
                down = StoredAction.of(down),
                left = StoredAction.of(left),
                right = StoredAction.of(right),
                latching = latching,
                pad = pad?.name.orEmpty(),
                padStick = padStick?.name.orEmpty(),
            )
        }
    }
}

/**
 * One profile, as stored: a deliberate near-copy of [InputProfile].
 *
 * Same reasoning as [StoredShortcut]. The runtime type is free to move — it
 * carries sealed interfaces and a `GamepadConfig` whose `releaseZone` is derived
 * — while the format on disk holds still. Every enum is stored as its `name` so a
 * value written by a newer build degrades instead of throwing.
 *
 * The pad map is written **in full, including the unbound**, because
 * `GamepadTranslator.emit` already treats a missing key and an explicit `None`
 * identically: writing every control makes a round trip stable and makes the file
 * a complete answer to what the profile does when it is read over adb.
 */
@Serializable
data class StoredInputProfile(
    val id: String,
    val name: String,
    val pad: Map<String, StoredAction> = emptyMap(),
    val sticks: Map<String, String> = emptyMap(),
    val deadzone: Float = GamepadConfig.DEFAULT_DEADZONE,
    val lookSpeed: Float = GamepadConfig.DEFAULT_LOOK_SPEED,
    val touch: List<StoredTouchControl> = emptyList(),
) {
    /**
     * The runtime form. **Total by construction** — an unknown control name, an
     * unknown stick, an unknown role, an out-of-range keycode and an unreadable
     * kind are all dropped, and nothing here can throw.
     */
    fun toProfile(): InputProfile = InputProfile(
        id = id,
        name = name,
        pad = GamepadProfile(
            name = name,
            bindings = pad.mapNotNull { (control, action) ->
                GamepadControl.entries.firstOrNull { it.name == control }
                    ?.let { it to action.toAction() }
            }.toMap(),
            sticks = sticks.mapNotNull { (stick, role) ->
                val which = Stick.entries.firstOrNull { it.name == stick } ?: return@mapNotNull null
                val what = StickRole.byName(role) ?: return@mapNotNull null
                which to what
            }.toMap(),
        ),
        config = GamepadConfig(
            deadzone = deadzone.sane(
                GamepadConfig.MIN_DEADZONE,
                GamepadConfig.MAX_DEADZONE,
                GamepadConfig.DEFAULT_DEADZONE,
            ),
            lookSpeed = lookSpeed.sane(
                GamepadConfig.MIN_LOOK_SPEED,
                GamepadConfig.MAX_LOOK_SPEED,
                GamepadConfig.DEFAULT_LOOK_SPEED,
            ),
        ),
        touch = TouchLayout(touch.mapNotNull { it.toControl() }),
    )

    private fun Float.sane(min: Float, max: Float, fallback: Float): Float =
        if (isNaN()) fallback else coerceIn(min, max)

    companion object {
        fun of(profile: InputProfile): StoredInputProfile = StoredInputProfile(
            id = profile.id,
            name = profile.name,
            // Every control, explicitly, including the unbound. See the class note.
            pad = GamepadControl.entries.associate { control ->
                control.name to StoredAction.of(
                    profile.pad.bindings[control] ?: GamepadAction.None,
                )
            },
            sticks = Stick.entries.associate { stick ->
                stick.name to StickRole.nameOf(profile.pad.roleOf(stick))
            },
            deadzone = profile.config.deadzone,
            lookSpeed = profile.config.lookSpeed,
            touch = profile.touch.controls.map { StoredTouchControl.of(it) },
        )
    }
}

/**
 * The document codec.
 *
 * A file that will not parse raises [CorruptionException] rather than propagating
 * a [SerializationException], because that is the one DataStore's corruption
 * handler catches — and opening with the built-in default is a far better outcome
 * than an app that cannot open. Everything *inside* a document that does parse is
 * read totally, so a single odd value costs one binding rather than every profile.
 */
class InputProfileDocumentSerializer(private val json: Json) : Serializer<InputProfileDocument> {

    override val defaultValue = InputProfileDocument()

    override suspend fun readFrom(input: InputStream): InputProfileDocument {
        val bytes = input.readBytes()
        // First run writes the file before anything is stored in it.
        if (bytes.isEmpty()) return defaultValue
        return try {
            json.decodeFromString(InputProfileDocument.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("$INPUT_PROFILES_FILE could not be read", e)
        }
    }

    override suspend fun writeTo(t: InputProfileDocument, output: OutputStream) {
        output.write(
            json.encodeToString(InputProfileDocument.serializer(), t).encodeToByteArray(),
        )
    }
}
