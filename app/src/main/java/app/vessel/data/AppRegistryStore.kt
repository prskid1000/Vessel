package app.vessel.data

import androidx.datastore.core.DataStore
import app.vessel.core.PeArchitecture
import app.vessel.ui.shell.AppRegistry
import app.vessel.ui.shell.AppShortcut
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppRegistry], persisted.
 *
 * Replaces `ui.shell.InMemoryAppRegistry`, which was an honest scaffold — it
 * held the right shape and lost every tile on a cold start, and said so. This is
 * the same contract over [AppShortcutDocument].
 *
 * The interface is declared in `ui/shell/` and implemented here, which points
 * the wrong way for a layer diagram and is the right trade in this one case: the
 * interface pass owns `ui/`, this layer owns storage, and the alternative is
 * moving a type both are already written against so that a dependency arrow can
 * look tidier. If that ever stops being true, moving `AppRegistry` and
 * `AppShortcut` into `core/` is a package change and an import fix.
 *
 * No `Dispatchers.IO` wrapper anywhere in here. DataStore's `updateData` does
 * its own handoff and is documented as main-safe, and adding a second one would
 * imply a guarantee this class is not the one making.
 */
@Singleton
class AppRegistryStore @Inject constructor(
    private val store: DataStore<AppShortcutDocument>,
) : AppRegistry {

    override val shortcuts: Flow<List<AppShortcut>> =
        store.data.map { document -> document.shortcuts.map { it.toShortcut() } }

    /**
     * Add, or replace a shortcut already pointing at the same executable.
     *
     * The replacement keeps the **existing** id, so anything already holding one
     * — a launcher tile mid-recomposition, an open sheet — keeps resolving. The
     * match is case-insensitive because the guest path is a Windows path and
     * `C:\Games\x.exe` and `c:\games\X.EXE` are the same file to everything that
     * will ever open it.
     */
    override suspend fun add(shortcut: AppShortcut): AppShortcut {
        val assigned = shortcut.copy(id = shortcut.id.ifBlank { UUID.randomUUID().toString() })
        var result = assigned
        store.updateData { document ->
            val merged = mergeShortcut(document.shortcuts, assigned)
            result = merged.added
            document.copy(shortcuts = merged.shortcuts)
        }
        return result
    }

    override suspend fun update(shortcut: AppShortcut) {
        store.updateData { document ->
            document.copy(
                shortcuts = document.shortcuts.map {
                    if (it.id == shortcut.id) shortcut.toStored() else it
                },
            )
        }
    }

    override suspend fun remove(id: String) {
        store.updateData { document ->
            document.copy(shortcuts = document.shortcuts.filterNot { it.id == id })
        }
    }

    /**
     * Forget every shortcut in a deleted container.
     *
     * Idempotent, because it is called from more than one place and a container
     * can be deleted from either — the second call must not be an error.
     */
    override suspend fun removeAllIn(containerId: String) {
        store.updateData { document ->
            document.copy(
                shortcuts = document.shortcuts.filterNot { it.containerId == containerId },
            )
        }
    }
}

/** The outcome of [mergeShortcut]: the new list, and the shortcut as it now is. */
internal data class ShortcutMerge(
    val shortcuts: List<StoredShortcut>,
    val added: AppShortcut,
)

/**
 * Add [incoming] to [current], replacing any shortcut already pointing at the
 * same executable in the same container.
 *
 * A pure function, and separate from [AppRegistryStore.add] for a reason worth
 * recording: DataStore cannot be exercised more than once per file in a unit
 * test on Windows. Its write is "write a `.tmp`, rename over the target", and
 * `File.renameTo` on Windows will not overwrite an existing destination — so the
 * first write to a fresh file succeeds and every later one throws `IOException`.
 * On Android `rename(2)` overwrites atomically and the same code is fine. Rather
 * than leave the replace rule untested on the machine it is written on, the rule
 * lives here and is tested directly; [AppRegistryStore] keeps only the single
 * write that a Windows host can still prove.
 *
 * The replacement keeps the **existing** id, so anything already holding one — a
 * launcher tile mid-recomposition, an open sheet — keeps resolving. The match is
 * case-insensitive because the guest path is a Windows path, and `C:\Games\x.exe`
 * and `c:\games\X.EXE` are the same file to everything that will ever open it.
 */
internal fun mergeShortcut(
    current: List<StoredShortcut>,
    incoming: AppShortcut,
): ShortcutMerge {
    val existing = current.indexOfFirst {
        it.containerId == incoming.containerId &&
            it.executable.equals(incoming.executable, ignoreCase = true)
    }
    if (existing < 0) return ShortcutMerge(current + incoming.toStored(), incoming)

    val kept = incoming.copy(id = current[existing].id)
    return ShortcutMerge(
        current.toMutableList().apply { this[existing] = kept.toStored() },
        kept,
    )
}

private fun StoredShortcut.toShortcut(): AppShortcut = AppShortcut(
    id = id,
    containerId = containerId,
    executable = executable,
    name = name,
    // An architecture written by a newer build, or a renamed enum constant,
    // reads back as UNKNOWN rather than throwing. It is a cache of what the PE
    // header says, so the cost of being wrong is one re-read.
    arch = PeArchitecture.entries.firstOrNull { it.name == arch } ?: PeArchitecture.UNKNOWN,
    args = args,
    workingDir = workingDir,
)

private fun AppShortcut.toStored(): StoredShortcut = StoredShortcut(
    id = id,
    containerId = containerId,
    executable = executable,
    name = name,
    arch = arch.name,
    args = args,
    workingDir = workingDir,
)
