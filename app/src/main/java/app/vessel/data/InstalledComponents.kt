package app.vessel.data

import android.content.Context
import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is actually unpacked on this device, read from disk.
 *
 * A `.wcp` installs to `files/components/<id>/`, and `build/package_wcp.py`
 * writes a `profile.json` at the root of every one of them. This class reads
 * those and nothing else: there is no seeded list, so before anything has been
 * downloaded it returns empty and every screen that asks says so. That is the
 * honest answer on a fresh install, and a screen that instead showed a driver
 * the device does not have would be worse than an empty one.
 */
@Singleton
class InstalledComponents @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val root: File get() = File(context.filesDir, DIRECTORY)

    private val _packages = MutableStateFlow<List<ComponentPackage>>(emptyList())
    val packages: Flow<List<ComponentPackage>> = _packages.asStateFlow()

    /** Re-read the install directory. Cheap: a handful of small files at most. */
    suspend fun refresh(): List<ComponentPackage> = withContext(Dispatchers.IO) {
        val found = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { read(it) }
            ?.sortedWith(compareBy({ it.type.ordinal }, { -it.versionCode }))
            .orEmpty()
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

    private fun read(directory: File): ComponentPackage? {
        val profileFile = File(directory, PROFILE)
        if (!profileFile.isFile) return null
        val profile = runCatching {
            json.decodeFromString(WcpProfile.serializer(), profileFile.readText())
        }.getOrNull() ?: return null

        // `type` is the `.wcp` wire string, and a package carrying anything
        // outside the known set is skipped rather than shown as "unknown": the
        // app has no code path that could load it.
        val type = ComponentType.entries.firstOrNull { it.wire == profile.type } ?: return null

        return ComponentPackage(
            id = directory.name,
            type = type,
            name = profile.name ?: directory.name,
            versionName = profile.versionName,
            versionCode = profile.versionCode,
            sizeBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            installed = true,
            target = profile.vessel?.provenance?.target ?: UNRECORDED,
            sourceSha = profile.vessel?.provenance?.sourceSha ?: UNRECORDED,
            cpuFlags = profile.vessel?.provenance?.cpuFlags ?: UNRECORDED,
        )
    }

    private companion object {
        const val DIRECTORY = "components"
        const val PROFILE = "profile.json"
        const val LATEST = "@latest"

        /** Said out loud rather than left blank, per the provenance argument in DESIGN.md. */
        const val UNRECORDED = "not recorded in the package"
    }
}

/** A selector, what it resolved to, and why it did not when it did not. */
data class ComponentResolution(
    val selector: String,
    val resolved: ComponentPackage?,
    val note: String?,
)

/**
 * `profile.json` inside a `.wcp`, exactly as `build/package_wcp.py` writes it.
 *
 * Only the fields the app reads are declared; `files`, `description` and
 * `builtAt` are ignored, which is also why decoding is lenient — this format is
 * shared with the wider Winlator ecosystem and a package from another producer
 * must not fail to read because it carries a key we do not know.
 */
@Serializable
private data class WcpProfile(
    val type: String,
    val versionName: String,
    val versionCode: Int,
    val name: String? = null,
    val vessel: WcpVessel? = null,
)

@Serializable
private data class WcpVessel(
    val provenance: WcpProvenance? = null,
)

/** `write_provenance` in `build/common.sh`. */
@Serializable
private data class WcpProvenance(
    val target: String? = null,
    val sourceSha: String? = null,
    @SerialName("cpuFlags") val cpuFlags: String? = null,
)
