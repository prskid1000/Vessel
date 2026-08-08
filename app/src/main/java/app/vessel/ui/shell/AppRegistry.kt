package app.vessel.ui.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every program the user has added, across every container.
 *
 * **This interface is the shell's boundary, and it is declared here on purpose.**
 * The list has to be persisted, and persistence is `data/`'s — see
 * `out/ui-needs-from-core.md`, which specifies exactly this contract. Declaring
 * it in `ui/` means the launcher, the home tiles and the app sheet are all
 * written against the real shape now, and the day a `data/AppRegistryStore`
 * arrives the only change is which implementation Hilt binds.
 *
 * Reads are a [Flow] rather than a snapshot because two surfaces show the same
 * list at once — the home screen and the launcher over a running desktop — and a
 * program added from one has to appear in the other without either asking.
 *
 * Writes are `suspend`: they touch storage. Implementations must be safe to call
 * from the main dispatcher and must do their own IO handoff, because every caller
 * here is a `viewModelScope`.
 */
interface AppRegistry {

    /** Every shortcut, in the order they were added. */
    val shortcuts: Flow<List<AppShortcut>>

    /**
     * Add [shortcut] and return it with its assigned [AppShortcut.id].
     *
     * Adding the same executable to the same container twice replaces rather than
     * duplicates: a home screen with two identical tiles is a home screen nobody
     * can tidy.
     */
    suspend fun add(shortcut: AppShortcut): AppShortcut

    /** Overwrite by [AppShortcut.id]. A no-op if the id is unknown. */
    suspend fun update(shortcut: AppShortcut)

    /** Forget one. The executable inside the prefix is not touched. */
    suspend fun remove(id: String)

    /** Forget every shortcut belonging to a container that has been deleted. */
    suspend fun removeAllIn(containerId: String)
}

/**
 * The stand-in, until `data/` owns this.
 *
 * **It does not survive the process.** That is a scaffold, not a design: the
 * shape of the contract above is what this pass had to get right, and a UI that
 * writes its own file into `filesDir` would be a second, competing owner of the
 * app's storage layout the day the real one lands. The cost is that a shortcut
 * added now is gone after a cold start, which is stated in
 * `out/ui-needs-from-core.md` and in the release notes for this pass rather than
 * hidden behind a plausible-looking list.
 *
 * A concrete `@Singleton` with an `@Inject` constructor rather than an
 * `@Binds` module, because the Hilt modules live in `di/`, which this pass does
 * not own. Call sites depend on [AppRegistry] through this type; swapping the
 * implementation is a one-line module in `di/`.
 */
@Singleton
class InMemoryAppRegistry @Inject constructor() : AppRegistry {

    private val state = MutableStateFlow<List<AppShortcut>>(emptyList())
    override val shortcuts: Flow<List<AppShortcut>> = state.asStateFlow()

    override suspend fun add(shortcut: AppShortcut): AppShortcut {
        val assigned = shortcut.copy(id = shortcut.id.ifBlank { UUID.randomUUID().toString() })
        state.update { current ->
            val existing = current.indexOfFirst {
                it.containerId == assigned.containerId &&
                    it.executable.equals(assigned.executable, ignoreCase = true)
            }
            if (existing >= 0) {
                current.toMutableList().apply { this[existing] = assigned.copy(id = current[existing].id) }
            } else {
                current + assigned
            }
        }
        return assigned
    }

    override suspend fun update(shortcut: AppShortcut) {
        state.update { current ->
            current.map { if (it.id == shortcut.id) shortcut else it }
        }
    }

    override suspend fun remove(id: String) {
        state.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun removeAllIn(containerId: String) {
        state.update { current -> current.filterNot { it.containerId == containerId } }
    }
}
