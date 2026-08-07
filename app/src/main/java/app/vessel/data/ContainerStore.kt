package app.vessel.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.vessel.core.ContainerProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Every container on this device, as one JSON document.
 *
 * DataStore over Room, deliberately. There is exactly one aggregate here — a
 * short list of containers, each with a map of manifest values — and nothing
 * queries across it: the home screen reads all of them, the editor reads one by
 * id. Room would buy indexes and joins nobody needs, and would cost an entity, a
 * DAO, a database class and a type converter for [ContainerProfile.params],
 * which is the part the manifest promise depends on staying schemaless. A
 * serialized document keeps that map honest — a new manifest key persists with
 * no migration at all.
 *
 * [schemaVersion] is the hook for when that stops being true.
 */
@Serializable
data class ContainerDocument(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val containers: List<ContainerProfile> = emptyList(),
)

/** Bumped when the shape changes in a way a reader has to know about. */
const val CURRENT_SCHEMA: Int = 1

/** The file the document lives in, under `files/datastore/`. */
const val CONTAINERS_FILE: String = "containers.json"

/**
 * The document codec.
 *
 * A file that will not parse raises [CorruptionException] rather than
 * propagating a [SerializationException], because that is the one DataStore's
 * corruption handler catches — and being reset to an empty list is a far better
 * outcome than an app that cannot open its home screen.
 */
class ContainerDocumentSerializer(private val json: Json) : Serializer<ContainerDocument> {

    override val defaultValue = ContainerDocument()

    override suspend fun readFrom(input: InputStream): ContainerDocument {
        val bytes = input.readBytes()
        // First run writes the file before anything is stored in it.
        if (bytes.isEmpty()) return defaultValue
        return try {
            json.decodeFromString(ContainerDocument.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("$CONTAINERS_FILE could not be read", e)
        }
    }

    override suspend fun writeTo(t: ContainerDocument, output: OutputStream) {
        output.write(json.encodeToString(ContainerDocument.serializer(), t).encodeToByteArray())
    }
}
