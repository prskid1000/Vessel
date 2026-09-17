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
 * **The default is an ordinary profile that happens to be undeletable.** It used
 * to be a constant that could not be written, so the first edit to it silently
 * duplicated it into a real profile under a new name — which meant a slider drag
 * left the list holding a copy nobody asked for, and every path that touched a
 * profile carried a branch for "unless it is the default". Now there is one
 * rule: [InputProfile.Default] is the *seed*, used until something writes over
 * it under the same id, and the only thing special about that id is that
 * [delete] refuses it. Everything else — rename, rebind, reset, duplicate,
 * export — is the same code for every profile.
 *
 * The cost is one document written the first time the default is edited, where
 * before an untouched container wrote nothing. That was worth keeping while the
 * default could not be edited at all; it is not worth a special case now that it
 * can.
 */
@Singleton
class InputProfileRepository @Inject constructor(
    private val store: DataStore<InputProfileDocument>,
) {
    /**
     * Every profile, the default first.
     *
     * The default is whatever is stored under [InputProfile.DEFAULT_ID], or the
     * seed when nothing is. Composed here rather than by each caller so that a
     * list showing an edited default cannot show the seed's name beside it.
     */
    val profiles: Flow<List<InputProfile>> =
        store.data.map { document ->
            val stored = document.profiles.map { it.toProfile() }
            // Each seeded profile as it stands -- what was written for it, or the
            // seed -- then everything else in the order it was written. The
            // seeds are listed from [InputProfile.builtIn] rather than named one
            // by one, so a third of them is a data change here and nowhere else.
            val seeded = InputProfile.builtIn.map { seed ->
                stored.firstOrNull { it.id == seed.id } ?: seed
            }
            seeded + stored.filterNot { it.isBuiltIn }
        }

    /** Kept as a name callers already use; the default is in it either way. */
    val choices: Flow<List<InputProfile>> = profiles

    /** The stored profile with this id, or null when nothing has been written for it. */
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
        if (id == null) return defaultProfile()
        // The seed, when the id names one and nothing has been written for it.
        // **Without this a seeded profile could be selected and never took
        // effect**: `find` reads the store, a seed is not in the store until it
        // is edited, and the fallback then handed back the *default*. Choosing
        // "Keyboard and mouse" left the pad on the glass and in the editor,
        // which reads as the choice not sticking rather than as a lookup that
        // missed.
        return find(id)
            ?: InputProfile.builtIn.firstOrNull { it.id == id }
            ?: defaultProfile()
    }

    /** A seeded profile as it stands: what was written for it, or the seed. */
    private suspend fun seeded(seed: InputProfile): InputProfile = find(seed.id) ?: seed

    /** The default as it stands: what was written for it, or the seed. */
    private suspend fun defaultProfile(): InputProfile = seeded(InputProfile.Default)

    /** Insert or replace, keeping declaration order stable for the list. */
    suspend fun save(profile: InputProfile) {
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
        // The one thing a seeded id still means. Refused here and not only in
        // the interface, because "there is always a profile" is an invariant of
        // the store rather than a rule of one screen -- and because a delete
        // that the next read undoes, which is what deleting a seed would be,
        // is worse than one that refuses.
        if (InputProfile.builtIn.any { it.id == id }) return
        store.updateData { document ->
            document.copy(profiles = document.profiles.filterNot { it.id == id })
        }
    }

    /** A copy under a fresh id, named so it does not collide. */
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
