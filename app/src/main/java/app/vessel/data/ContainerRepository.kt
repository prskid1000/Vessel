package app.vessel.data

import androidx.datastore.core.DataStore
import app.vessel.core.ArchProfile
import app.vessel.core.ComponentType
import app.vessel.core.ContainerProfile
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
) {
    val containers: Flow<List<ContainerProfile>> = store.data.map { it.containers }

    suspend fun get(id: String): ContainerProfile? =
        store.data.first().containers.firstOrNull { it.id == id }

    /**
     * A new container, not yet persisted.
     *
     * Universal, because it is the profile that runs everything except the
     * installers Compatibility exists for, and the one where DXVK and vkd3d are
     * native. The name is numbered off the containers that already exist so two
     * taps never produce two identically named tiles.
     */
    suspend fun draft(): ContainerProfile {
        val existing = store.data.first().containers
        val defaults = manifests.load().getOrNull()?.defaults().orEmpty()
        return resolveLabels(
            ContainerProfile(
                id = UUID.randomUUID().toString(),
                name = nextName(existing.map { it.name }),
                archProfile = ArchProfile.UNIVERSAL,
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

    suspend fun delete(id: String) {
        store.updateData { document ->
            document.copy(containers = document.containers.filterNot { it.id == id })
        }
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
