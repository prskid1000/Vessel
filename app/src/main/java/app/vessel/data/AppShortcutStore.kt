package app.vessel.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Every app shortcut on this device, as one JSON document.
 *
 * Same shape and same reasoning as [ContainerDocument]: one short aggregate,
 * nothing queries across it, and a serialized document costs less than an entity
 * plus a DAO plus a database class. Kept in its **own** file rather than folded
 * into the container document, because the two have different failure modes — a
 * corrupt shortcut list should cost the user their home-screen tiles and not
 * their containers, and the reverse would be much worse.
 *
 * A shortcut is a pointer, never an installation. Losing this file loses the
 * tiles; every program it named is still sitting in the prefix.
 */
@Serializable
data class AppShortcutDocument(
    val schemaVersion: Int = CURRENT_SHORTCUT_SCHEMA,
    val shortcuts: List<StoredShortcut> = emptyList(),
)

/** Bumped when the shape changes in a way a reader has to know about. */
const val CURRENT_SHORTCUT_SCHEMA: Int = 1

/** The file the document lives in, under `files/datastore/`. */
const val SHORTCUTS_FILE: String = "shortcuts.json"

/**
 * One shortcut, as stored.
 *
 * A deliberate near-copy of `ui.shell.AppShortcut` rather than that class made
 * `@Serializable`. Two reasons, and both are about not letting one decision
 * force the other:
 *
 *  - the UI type carries `@Immutable` and a `PeArchitecture`, so serializing it
 *    directly would put a Compose annotation and an enum's `name` into the
 *    on-disk format, where an enum rename becomes a migration;
 *  - the storage format has to stay still while the UI type is free to move.
 *    The interface pass owns `ui/shell/`, this layer owns the file, and the
 *    mapping between them is the seam that keeps those two facts compatible.
 *
 * [arch] is stored as the enum's `name` in a [String] field rather than as the
 * enum itself, so a value written by a newer build degrades to `UNKNOWN` on read
 * instead of throwing. It is a cache of something the file itself knows: if it
 * is ever wrong, re-reading the PE header fixes it.
 */
@Serializable
data class StoredShortcut(
    val id: String,
    val containerId: String,
    val executable: String,
    val name: String,
    val arch: String = "",
    val args: String = "",
    val workingDir: String = "",
)

/**
 * The document codec.
 *
 * A file that will not parse raises [CorruptionException] rather than
 * propagating a [SerializationException], because that is the one DataStore's
 * corruption handler catches — and an empty tile list is a far better outcome
 * than an app that cannot open.
 */
class AppShortcutDocumentSerializer(private val json: Json) : Serializer<AppShortcutDocument> {

    override val defaultValue = AppShortcutDocument()

    override suspend fun readFrom(input: InputStream): AppShortcutDocument {
        val bytes = input.readBytes()
        // First run writes the file before anything is stored in it.
        if (bytes.isEmpty()) return defaultValue
        return try {
            json.decodeFromString(AppShortcutDocument.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("$SHORTCUTS_FILE could not be read", e)
        }
    }

    override suspend fun writeTo(t: AppShortcutDocument, output: OutputStream) {
        output.write(
            json.encodeToString(AppShortcutDocument.serializer(), t).encodeToByteArray(),
        )
    }
}
