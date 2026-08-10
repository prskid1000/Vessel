package app.vessel.data

import androidx.datastore.core.DataStore
import app.vessel.input.InputProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Input-profile CRUD, modelled on [ContainerRepository].
 *
 * **The built-in default is never written to disk.** It is not in [profiles], it
 * cannot be renamed and it cannot be deleted: it is what a container gets when it
 * has never been given anything, which is what makes an untouched container's
 * stored bytes identical to what they were before this feature existed. Editing
 * it in the UI means duplicating it first, which is [duplicate]'s job.
 */
@Singleton
class InputProfileRepository @Inject constructor(
    private val store: DataStore<InputProfileDocument>,
) {
    /** Every profile the user has made, in the order they were made. */
    val profiles: Flow<List<InputProfile>> =
        store.data.map { document -> document.profiles.map { it.toProfile() } }

    /** Every profile including the built-in default, which is always first. */
    val choices: Flow<List<InputProfile>> =
        profiles.map { listOf(InputProfile.Default) + it }

    /** The stored profile with this id, or null. Never returns the built-in default. */
    suspend fun find(id: String): InputProfile? =
        store.data.first().profiles.firstOrNull { it.id == id }?.toProfile()

    /**
     * What a container pointing at [id] actually runs with.
     *
     * A null id and a **stale** id — one naming a profile that has since been
     * deleted — both resolve to the built-in default, and neither is an error:
     * deleting a profile a container was using is an ordinary thing to do. The
     * caller is told which case it got by comparing ids, so a sheet can say "the
     * profile it named has been deleted" rather than silently forgetting.
     */
    suspend fun resolve(id: String?): InputProfile {
        if (id == null || id == InputProfile.DEFAULT_ID) return InputProfile.Default
        return find(id) ?: InputProfile.Default
    }

    /** Insert or replace, keeping declaration order stable for the list. */
    suspend fun save(profile: InputProfile) {
        // The built-in default is a constant, not a record. Saving over it would
        // put a `default` row in the document that every container silently
        // starts resolving to instead.
        if (profile.isBuiltInDefault) return
        val stored = StoredInputProfile.of(profile)
        store.updateData { document ->
            val existing = document.profiles.indexOfFirst { it.id == stored.id }
            val next = document.profiles.toMutableList()
            if (existing >= 0) next[existing] = stored else next += stored
            document.copy(profiles = next)
        }
    }

    /**
     * Remove a profile.
     *
     * **Containers pointing at it are not rewritten.** A stale id resolves to the
     * default on the next launch, and hunting through the container document to
     * clear pointers would make deleting a profile a write to the *other* file —
     * the coupling the separate documents exist to avoid.
     */
    suspend fun delete(id: String) {
        store.updateData { document ->
            document.copy(profiles = document.profiles.filterNot { it.id == id })
        }
    }

    /**
     * A copy under a fresh id, named so it does not collide.
     *
     * This is also how the built-in default becomes editable: there is nothing to
     * write for it, so the first edit duplicates it into a real profile.
     */
    suspend fun duplicate(profile: InputProfile): InputProfile {
        val taken = store.data.first().profiles.map { it.name }
        val copy = profile.copy(
            id = UUID.randomUUID().toString(),
            name = nextName(profile.name, taken),
        )
        save(copy)
        return copy
    }

    /**
     * "Keyboard and mouse", then "Keyboard and mouse (2)".
     *
     * Numbered in brackets rather than suffixed with "copy", because the second
     * copy of a copy reads as "copy copy" and the number does not.
     */
    fun nextName(base: String, taken: List<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }
}
