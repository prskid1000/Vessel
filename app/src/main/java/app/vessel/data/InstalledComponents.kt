package app.vessel.data

import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is actually unpacked on this device, read from disk.
 *
 * The disk in question is the shared [ComponentStore] —
 * `files/components/<Type>/<versionCode>/`, one copy for every container — and
 * this class is the read-only view of it that the screens use. There is no
 * seeded list, so before anything has been downloaded it returns empty and every
 * screen that asks says so. That is the honest answer on a fresh install, and a
 * screen that instead showed a driver the device does not have would be worse
 * than an empty one.
 */
@Singleton
class InstalledComponents @Inject constructor(
    private val store: ComponentStore,
) {
    private val _packages = MutableStateFlow<List<ComponentPackage>>(emptyList())
    val packages: Flow<List<ComponentPackage>> = _packages.asStateFlow()

    /** Re-read the store. Cheap: a handful of small files at most. */
    suspend fun refresh(): List<ComponentPackage> = withContext(Dispatchers.IO) {
        val found = store.installed()
            .map { it.toPackage() }
            .sortedWith(compareBy({ it.type.ordinal }, { -it.versionCode }))
        _packages.value = found
        found
    }

    /** The current snapshot without touching disk. */
    fun snapshot(): List<ComponentPackage> = _packages.value

    /**
     * Resolve one `component` param's selector.
     *
     * `@latest` is the newest installed package of that type by `versionCode`,
     * which is why the manifest can carry a default at all — a package id like
     * `dxvk-2.7.1-canoe` is a build output, and hardcoding one in the manifest
     * would mean a version bump silently broke every container's default.
     */
    fun resolve(componentType: ComponentType?, selector: String): ComponentResolution {
        if (componentType == null) {
            return ComponentResolution(selector, null, "This setting names no component type.")
        }
        val ofType = snapshot().filter { it.type == componentType }
        return when {
            selector == LATEST -> {
                val newest = ofType.maxByOrNull { it.versionCode }
                ComponentResolution(
                    selector = selector,
                    resolved = newest,
                    note = if (newest == null) {
                        "No ${componentType.label} package is installed, so there is nothing " +
                            "for @latest to resolve to yet."
                    } else {
                        null
                    },
                )
            }

            else -> {
                val pinned = ofType.firstOrNull { it.id == selector }
                ComponentResolution(
                    selector = selector,
                    resolved = pinned,
                    note = if (pinned == null) {
                        "This container pins a ${componentType.label} package that is not " +
                            "installed on this device."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** The label the container card shows for a resolved selector. */
    fun label(componentType: ComponentType?, selector: String): String {
        val resolution = resolve(componentType, selector)
        return resolution.resolved?.id ?: "$selector · not installed"
    }

    private companion object {
        const val LATEST = "@latest"
    }
}

/** A selector, what it resolved to, and why it did not when it did not. */
data class ComponentResolution(
    val selector: String,
    val resolved: ComponentPackage?,
    val note: String?,
)

/** Said out loud rather than left blank, per the provenance argument in DESIGN.md. */
private const val UNRECORDED = "not recorded in the package"

/**
 * One stored version as the screens show it.
 *
 * [ComponentPackage.id] is the registry's id — `dxvk-2.7.1-canoe` — which is
 * what a pinned `component` selector names and what the container card shows.
 * `profile.json` has no field for it, so it comes from the store's own
 * [ComponentRecord]. The fallback is for a version that reached the store
 * without one, in practice a migrated install from a container whose
 * `provisioned.json` had no matching record: it is reconstructed from the type
 * and version rather than left blank, and it will not match a pin, which is the
 * honest outcome when the id was never recorded.
 */
private fun StoredComponent.toPackage(): ComponentPackage {
    val id = packageId ?: "${type.wire.lowercase()}-${profile.versionName}"
    return ComponentPackage(
        id = id,
        type = type,
        name = profile.name ?: id,
        versionName = profile.versionName,
        versionCode = versionCode,
        sizeBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        installed = true,
        target = profile.vessel?.provenance?.target ?: UNRECORDED,
        sourceSha = profile.vessel?.provenance?.sourceSha ?: UNRECORDED,
        cpuFlags = profile.vessel?.provenance?.cpuFlags ?: UNRECORDED,
    )
}

// `profile.json` is declared once, in `app.vessel.core.WcpProfile`, and shared
// with `WcpInstaller`. Do not add a second private copy: decoding is lenient by
// design, so whichever copy missed a new packager field would go on reading
// successfully and silently dropping it.
