package app.vessel.ui.shell

import kotlinx.coroutines.flow.Flow

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
