package app.vessel.data

import app.vessel.input.InputProfile
import app.vessel.input.TouchControl
import app.vessel.input.TouchControls
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One profile on its way in or out of the app, wrapped so the file says what it
 * is.
 *
 * Self-describing rather than a bare profile: a file with no version in it is a
 * file a future build has to guess about, and the guess it would have to make —
 * "this was written by whatever wrote it" — is the one that cannot be checked.
 */
@Serializable
data class InputProfileEnvelope(
    val schemaVersion: Int = CURRENT_INPUT_SCHEMA,
    val profile: StoredInputProfile,
)

/** How an import went. A refusal carries the sentence the user is shown. */
sealed interface ImportResult {
    data class Ok(val profile: InputProfile) : ImportResult
    data class Refused(val reason: String) : ImportResult
}

/**
 * Reading and writing one profile as a file, and the sanitising that stands
 * between a file and the store.
 *
 * **The only place in the app that consults a version number.** Every other
 * reader is deliberately total — it drops what it cannot understand and carries
 * on, because a throw inside `containers.json` costs the user every container
 * they have. A file arriving from outside the app is the opposite situation:
 * nothing is lost by refusing it, the user chose it deliberately and can be told
 * why, and reading a newer schema optimistically is how a keycode that does not
 * exist ends up in a table.
 */
object InputProfileTransfer {

    fun export(json: Json, profile: InputProfile): String =
        json.encodeToString(
            InputProfileEnvelope.serializer(),
            InputProfileEnvelope(CURRENT_INPUT_SCHEMA, StoredInputProfile.of(profile)),
        )

    /**
     * Read a file, and either produce a profile fit to store or say why not.
     *
     * [taken] is the names already in use, so the import can be numbered rather
     * than silently shadowing a profile the user already has.
     */
    fun import(json: Json, text: String, taken: List<String>): ImportResult {
        val envelope = try {
            json.decodeFromString(InputProfileEnvelope.serializer(), text)
        } catch (e: SerializationException) {
            return ImportResult.Refused(
                "That file is not a Vessel input profile — ${e.message ?: "it would not parse"}.",
            )
        }
        if (envelope.schemaVersion != CURRENT_INPUT_SCHEMA) {
            return ImportResult.Refused(
                "That profile was written by a different version of Vessel " +
                    "(schema ${envelope.schemaVersion}; this build reads " +
                    "$CURRENT_INPUT_SCHEMA). It has not been imported.",
            )
        }
        return ImportResult.Ok(sanitize(envelope.profile.toProfile(), taken))
    }

    /**
     * Everything an imported profile is allowed to be.
     *
     * [StoredInputProfile.toProfile] has already dropped the unreadable — an
     * unknown control name, an unknown stick, an out-of-range keycode, an
     * unrecognised action kind — because that reader is total by construction.
     * What is left for here is the two things a *file* can do that a document
     * this app wrote cannot: collide with something already on the device, and
     * carry a control positioned somewhere no finger can reach.
     *
     * - **A fresh id, always.** An import that kept its id would replace the
     *   profile it collided with, and the user asked to add one.
     * - **A de-duplicated name**, by the same `nextName` shape a duplicate uses.
     * - **Every control clamped and floored**, so an imported button is always on
     *   screen and always big enough to hit. [TouchControl.sane] is the same
     *   clamp the editor applies; running it here means a hand-written file
     *   cannot reach the overlay with a size of zero.
     */
    fun sanitize(profile: InputProfile, taken: List<String>): InputProfile = profile.copy(
        id = UUID.randomUUID().toString(),
        name = nextName(profile.name.trim().ifBlank { "Imported profile" }, taken),
        touch = profile.touch.sane(),
    )

    /**
     * The suffix `InputProfileRepository.nextName` uses, duplicated here as a
     * pure function so the import can be tested without a DataStore.
     */
    fun nextName(base: String, taken: List<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    /** What an exported file is called before the user renames it. */
    fun fileName(profile: InputProfile): String {
        val stem = profile.name.map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "input-profile" }
        return "$stem.vessel-input.json"
    }

    /** Named so the clamp's floor has a symbol rather than being a magic number. */
    val smallestControl: Float get() = TouchControls.MIN_SIZE
}
