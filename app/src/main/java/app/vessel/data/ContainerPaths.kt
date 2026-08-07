package app.vessel.data

import app.vessel.core.ComponentType
import java.io.File

/**
 * The on-disk layout of a container, in one place.
 *
 * Every other class asks this one where something lives. That is not tidiness:
 * a container directory is deleted wholesale when the container is, and a path
 * assembled independently somewhere else is a directory that survives the
 * delete and a gigabyte of Wine prefix nobody can reach from any screen.
 *
 * ```
 * filesDir/containers/<id>/
 *   prefix/                the Wine prefix (drive_c, system.reg, …)
 *   components/<Type>/     installed .wcp payloads, one directory per type
 *   tmp/                   scratch, including install staging
 *   provisioned.json       what [ContainerProvisioner] has already done
 *   prefix-seed.reg        the rendered [app.vessel.core.PrefixRegistry] seed
 * filesDir/logs/<id>/      session logs
 * ```
 *
 * **Logs are the one thing not under the container directory**, and the reason
 * is worth stating rather than fixing. [SessionLogStore] has owned
 * `filesDir/logs/<id>/` since before this class existed, and it is the writer:
 * moving the tree would orphan every log already on a device for no gain. So
 * [ContainerLayout.logs] reports where the logs *are*, not where a diagram put
 * them, and [safeName] is shared with [SessionLogStore] so the two can never
 * disagree about which directory a given container id names.
 *
 * Constructed from `filesDir` rather than from a `Context` so it can be pointed
 * at a temporary directory in a unit test.
 */
class ContainerPaths(private val filesDir: File) {

    val containersRoot: File get() = File(filesDir, CONTAINERS_DIRECTORY)

    val logsRoot: File get() = File(filesDir, LOGS_DIRECTORY)

    fun of(containerId: String): ContainerLayout {
        val safe = safeName(containerId)
        return ContainerLayout(
            id = containerId,
            base = File(containersRoot, safe),
            logs = File(logsRoot, safe),
        )
    }

    /** Container directories that exist on disk, whatever the document says. */
    fun existing(): List<File> =
        containersRoot.listFiles().orEmpty().filter { it.isDirectory }

    companion object {
        const val CONTAINERS_DIRECTORY = "containers"
        const val LOGS_DIRECTORY = "logs"

        /**
         * A container id turned into one path segment.
         *
         * Ids are UUIDs today, but an id arrives here as a string and a string
         * that reaches the filesystem gets sanitised. One separator in the wrong
         * place is a write outside the app's own directory.
         *
         * [SessionLogStore] calls this too. The two must agree exactly or a
         * container's logs and its prefix end up under different names.
         */
        fun safeName(containerId: String): String =
            containerId
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .ifBlank { "unnamed" }
    }
}

/** Where one container's files are. Pure path arithmetic; touches no disk until asked. */
data class ContainerLayout(
    val id: String,
    /** `filesDir/containers/<id>/`. */
    val base: File,
    /** `filesDir/logs/<id>/` — see the note on [ContainerPaths]. */
    val logs: File,
) {
    /** The Wine prefix. Empty until something runs `wineboot`, which is not this layer. */
    val prefix: File get() = File(base, PREFIX)

    /** Installed `.wcp` payloads, one subdirectory per [ComponentType]. */
    val components: File get() = File(base, COMPONENTS)

    /** Scratch. Install staging happens here so a rename into place stays on one filesystem. */
    val tmp: File get() = File(base, TMP)

    /** [ContainerProvisioner]'s record of what is already done. */
    val provisionState: File get() = File(base, PROVISION_STATE)

    /** The rendered registry seed. Written here; applying it needs a process. */
    val registrySeed: File get() = File(base, REGISTRY_SEED)

    /** Where a `.wcp` of [type] is installed. One version of each type per container. */
    fun component(type: ComponentType): File = File(components, type.wire)

    /** Every directory the layout promises, created if absent. */
    fun createDirectories(): Boolean =
        listOf(base, prefix, components, tmp, logs).all { it.isDirectory || it.mkdirs() }

    /** True when every directory the layout promises is already there. */
    fun directoriesExist(): Boolean =
        listOf(base, prefix, components, tmp, logs).all { it.isDirectory }

    private companion object {
        const val PREFIX = "prefix"
        const val COMPONENTS = "components"
        const val TMP = "tmp"
        const val PROVISION_STATE = "provisioned.json"
        const val REGISTRY_SEED = "prefix-seed.reg"
    }
}
