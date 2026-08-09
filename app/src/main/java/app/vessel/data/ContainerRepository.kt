package app.vessel.data

import androidx.datastore.core.DataStore
import app.vessel.core.ComponentType
import app.vessel.core.ContainerProfile
import app.vessel.core.DriveMap
import app.vessel.core.deleteTree
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Container CRUD, and the one place a container's defaults are decided.
 *
 * "Correct defaults for this device" is a product promise, not a convenience —
 * DESIGN.md says a new container needs zero configuration — so [draft] fills
 * every manifest key from the manifest rather than leaving the map empty and
 * letting each reader fall back on its own. A container's stored values are then
 * the complete answer to what it will run with.
 */
@Singleton
class ContainerRepository @Inject constructor(
    private val store: DataStore<ContainerDocument>,
    private val manifests: ParamManifestStore,
    private val components: InstalledComponents,
    private val componentStore: ComponentStore,
    private val paths: ContainerPaths,
    private val sessionLogs: SessionLogStore,
) {
    val containers: Flow<List<ContainerProfile>> = store.data.map { it.containers }

    suspend fun get(id: String): ContainerProfile? =
        store.data.first().containers.firstOrNull { it.id == id }

    /**
     * A new container, not yet persisted.
     *
     * There is one kind of container — Wine, DXVK and vkd3d are ARM64EC and FEX
     * translates the application's own x86 code inside the process — so there is
     * nothing to choose here beyond the manifest defaults. The name is numbered
     * off the containers that already exist so two taps never produce two
     * identically named tiles.
     */
    suspend fun draft(): ContainerProfile {
        val existing = store.data.first().containers
        val defaults = manifests.load().getOrNull()?.defaults().orEmpty()
        return resolveLabels(
            ContainerProfile(
                id = UUID.randomUUID().toString(),
                name = nextName(existing.map { it.name }),
                wineBuild = "",
                driver = "",
                d3dLayer = "",
                lastRun = null,
                params = defaults,
            ),
        )
    }

    /** Insert or replace, keeping declaration order stable for the list. */
    suspend fun save(profile: ContainerProfile) {
        val resolved = resolveLabels(profile)
        store.updateData { document ->
            val existing = document.containers.indexOfFirst { it.id == resolved.id }
            val next = document.containers.toMutableList()
            if (existing >= 0) next[existing] = resolved else next += resolved
            document.copy(containers = next)
        }
    }

    /**
     * Remove a container, its directory, and its session logs.
     *
     * The logs go because they are a property of the container and of nothing
     * else: keeping them would leave up to eighty megabytes on the device
     * belonging to something the user has just said they are finished with, and
     * reachable from no screen, since every route into the viewer starts at a
     * container that no longer exists.
     *
     * **Components do not go.** They are not in the container directory any more
     * — they are in the shared [ComponentStore], where another container may be
     * using the same 912 MB Wine tree. Dropping the container's directory drops
     * its `provisioned.json`, which is what stops it counting as a reference;
     * whether that makes anything deletable is [ComponentStore.prune]'s question
     * and it is deliberately not asked here. A bug in the counting should cost
     * disk, not somebody's Wine install.
     *
     * The migration runs first for the same reason: on a device that has not
     * been read since the upgrade, `containers/<id>/components/` is still where
     * the only copy of a component lives, and deleting the directory before
     * moving it out would take that copy with it.
     */
    suspend fun delete(id: String) {
        componentStore.migrate()
        store.updateData { document ->
            document.copy(containers = document.containers.filterNot { it.id == id })
        }
        sessionLogs.deleteAll(id)

        // **Every mapping is unlinked before anything recursive runs, and this
        // is not belt-and-braces — it is the line that must never be removed.**
        //
        // A container's `prefix/dosdevices` holds a symlink per drive: `d:` to
        // `/storage/emulated/0`, and one per folder the user mapped. Deleting
        // the container used to be `base.deleteRecursively()`, which walks with
        // `listFiles()` — and `listFiles()` on a link to a directory returns the
        // *target's* children. So the delete went through every mapping and
        // removed the contents of the user's shared storage and of every folder
        // they had mapped, then unlinked the now-empty links and reported
        // success. Reported as "my downloaded games got deleted twice", and that
        // is precisely what happened.
        //
        // [deleteTree] is the real fix and refuses to follow a link at all. This
        // is here as well because the two failures are independent: this leaves
        // nothing for a walk to follow even if one is reintroduced, and a
        // deleted container should stop pointing at the user's folders whether
        // or not the rest of the delete succeeds.
        val layout = paths.of(id)
        DriveMap.drives(layout.prefix).forEach { DriveMap.unmap(layout.prefix, it.letter) }
        deleteTree(layout.base)
    }

    /**
     * Fill the three component labels the home card shows from the container's
     * own `component` params, resolved against what is installed.
     *
     * Wine has no manifest param — there is one Wine build and no choice to make
     * — so it is resolved directly. Anything the manifest does declare is found
     * by [ParamType.COMPONENT] rather than by key, so a fourth component param
     * would need no change here beyond deciding whether the card shows it.
     */
    private suspend fun resolveLabels(profile: ContainerProfile): ContainerProfile {
        components.refresh()
        val manifest = manifests.load().getOrNull()
        fun labelFor(componentType: ComponentType): String {
            val spec = manifest?.allParams?.firstOrNull {
                it.type == ParamType.COMPONENT && it.componentType == componentType.wire
            }
            val selector = (spec?.let { profile.params[it.key] } as? ParamValue.Text)?.value
                ?: (spec?.defaultValue() as? ParamValue.Text)?.value
                ?: LATEST
            return components.label(componentType, selector)
        }
        return profile.copy(
            wineBuild = components.label(ComponentType.WINE, LATEST),
            driver = labelFor(ComponentType.TURNIP),
            d3dLayer = labelFor(ComponentType.DXVK),
        )
    }

    /** "Container", then "Container 2", and so on — never a duplicate on screen. */
    private fun nextName(taken: List<String>): String {
        if (BASE_NAME !in taken) return BASE_NAME
        var n = 2
        while ("$BASE_NAME $n" in taken) n++
        return "$BASE_NAME $n"
    }

    private companion object {
        const val LATEST = "@latest"
        const val BASE_NAME = "Container"
    }
}
