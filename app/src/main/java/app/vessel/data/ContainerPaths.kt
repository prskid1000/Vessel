package app.vessel.data

import app.vessel.core.ComponentType
import app.vessel.core.DriveMap
import app.vessel.ui.shell.GuestPath
import java.io.File

/**
 * The on-disk layout, in one place.
 *
 * Every other class asks this one where something lives. A container directory
 * is deleted wholesale, so a path assembled independently elsewhere is a
 * gigabyte of Wine prefix that survives the delete and no screen can reach.
 *
 * ```
 * filesDir/components/<Type>/<versionCode>/   installed .wcp payloads, shared
 * filesDir/components/<Type>/<versionCode>.json  the store's own record
 * filesDir/components/.staging/               install staging
 * filesDir/containers/<id>/
 *   prefix/                the Wine prefix (drive_c, system.reg, …)
 *   tmp/                   scratch
 *   provisioned.json       what [ContainerProvisioner] has already done
 *   prefix-seed.reg        the rendered [app.vessel.core.PrefixRegistry] seed
 * filesDir/logs/<id>/      session logs
 * ```
 *
 * **Components are outside the container directory and that is the point.**
 * Wine is 912 MB unpacked; three containers on the same build used to be three
 * byte-identical copies of it. A container now records *which* versions it uses
 * — see [ProvisionedState.componentVersions] — and [ComponentStore] owns the
 * bytes. The corollary is that deleting a container must never delete a
 * component: [ComponentStore.prune] is the only thing that removes one, it is
 * explicit, and it refuses to touch a version any container still references.
 *
 * **Logs are the other thing not under the container directory.**
 * [SessionLogStore] has owned `filesDir/logs/<id>/` since before this class
 * existed and is the writer; moving the tree would orphan every log already on a
 * device. [safeName] is shared with it so the two cannot disagree about which
 * directory an id names.
 *
 * Constructed from `filesDir` rather than from a `Context` so it can be pointed
 * at a temporary directory in a unit test.
 */
class ContainerPaths(private val filesDir: File) {

    val containersRoot: File get() = File(filesDir, CONTAINERS_DIRECTORY)

    val logsRoot: File get() = File(filesDir, LOGS_DIRECTORY)

    /**
     * Where a downloaded `.wcp` lands before it is unpacked.
     *
     * Named here rather than assembled by whoever downloads, which is this
     * class's whole argument: a path built outside it is a directory nothing
     * can later find, sweep or account for. It was
     * `ComponentDownloadService.DOWNLOADS_DIRECTORY` and reachable only from
     * the service — recorded as misplaced in `out/needs-from-install-agent.md`
     * long before setup needed it too.
     *
     * Part-files are deliberately left behind on a cancel so a retry resumes.
     * Nothing sweeps one that is never resumed; that is still open.
     */
    val downloadsRoot: File get() = File(filesDir, DOWNLOADS_DIRECTORY)

    /** The shared component store — `filesDir/components/`. */
    val components: ComponentStoreLayout get() = ComponentStoreLayout(File(filesDir, COMPONENTS_DIRECTORY))

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

    /**
     * [existing], as layouts.
     *
     * The directory name is already a [safeName], so it round-trips through
     * [of] unchanged — which is what lets [ComponentStore] read every
     * container's `provisioned.json` without assembling that path itself.
     */
    fun existingLayouts(): List<ContainerLayout> = existing().map { of(it.name) }

    companion object {
        const val CONTAINERS_DIRECTORY = "containers"
        const val DOWNLOADS_DIRECTORY = "downloads"
        const val LOGS_DIRECTORY = "logs"
        const val COMPONENTS_DIRECTORY = "components"

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

    /** Scratch belonging to this container. Component staging is the store's, not this. */
    val tmp: File get() = File(base, TMP)

    /**
     * The Android directory a guest path lives in, whichever drive it names.
     *
     * **The reason this exists is a defect it fixes in two places.** Both
     * callers resolved every guest path against `prefix/drive_c`, which is right
     * for `C:` and wrong for every drive the user maps: a shortcut to a game on
     * `F:` resolved to `drive_c/Games/…`, was not there, and was refused with
     * "no longer on this container's C: drive" — a program that exists, on a
     * drive that is mounted, reported missing by the one feature whose whole
     * purpose is to reach it.
     *
     * The letter chooses the root, exactly as it does inside Wine:
     * `dosdevices/f:` is a symlink and `F:\Games\x.exe` is `Games/x.exe` under
     * it. [GuestPath.resolve] still refuses anything that climbs out.
     *
     * Null for a string with no drive letter, and for a path that escapes its
     * drive. A drive that has been unmapped resolves to a link that is not
     * there, which the caller sees as a file that does not exist — the right
     * answer, and the same one it would get for a deleted file.
     */
    fun resolveGuestPath(guestPath: String): File? {
        val letter = guestPath.firstOrNull()?.lowercaseChar() ?: return null
        if (letter !in 'a'..'z') return null
        return GuestPath.resolve(driveRoot(letter), guestPath)
    }

    /** `dosdevices/<letter>:` — Wine's own name for where a drive points. */
    fun driveRoot(letter: Char): File =
        File(File(prefix, DriveMap.DOSDEVICES), "${letter.lowercaseChar()}:")

    /** [ContainerProvisioner]'s record of what is already done. */
    val provisionState: File get() = File(base, PROVISION_STATE)

    /** The rendered registry seed. Written here; applying it needs a process. */
    val registrySeed: File get() = File(base, REGISTRY_SEED)

    /**
     * Where components used to be installed, before the shared store existed.
     *
     * Only [ComponentStore.migrate] should ever look at this. It is not part of
     * the layout any more — it is the thing being migrated out of, and it is
     * named here rather than assembled inside the migration so that this class
     * stays the only place that knows the shape of a container directory.
     */
    val legacyComponents: File get() = File(base, LEGACY_COMPONENTS)

    /**
     * The shader caches, one directory per layer that has one.
     *
     * [SessionEnvironment] names `caches/mesa` directly in
     * `MESA_SHADER_CACHE_DIR`. It is a pure function with no disk, so it can
     * name a directory but cannot make it, and until this existed nothing did:
     * the variables pointed at paths that were not there. Mesa creates its own
     * tree and survived that; a layer that opens its cache as a file would not.
     *
     * There is no `caches/dxvk` any more. DXVK 2.x dropped the on-disk state
     * cache, so the directory was created for a variable nothing reads — and an
     * empty cache directory is indistinguishable from a working one, which is
     * how it came to be cited as evidence that a path scheme worked while the
     * identical scheme was silently failing for vkd3d.
     *
     * `caches/vkd3d` is named too, but `VKD3D_SHADER_CACHE_PATH` does not hold
     * this path — vkd3d-proton runs as a Windows PE DLL inside Wine and
     * rewrites a Unix path here to a `Z:\` one that no Vessel prefix has (see
     * `VKD3D_CACHE_DOS_PATH`), so it is handed a DOS path instead and
     * `SessionRuntime.linkVkd3dCache` symlinks that DOS path to this directory
     * every launch. The bytes still land here either way.
     *
     * This is the difference between compiling every pipeline on every launch
     * and compiling it once, which on a phone is the largest avoidable cost in
     * the stack — so the directories are part of the layout rather than
     * something a session hopes to find.
     */
    val caches: File get() = File(base, CACHES)

    /** Where each layer's cache goes, in the order [SessionEnvironment] names them. */
    val cacheDirectories: List<File>
        get() = listOf("mesa", "vkd3d").map { File(caches, it) }

    /** Every directory the layout promises, created if absent. */
    fun createDirectories(): Boolean =
        (listOf(base, prefix, tmp, logs, caches) + cacheDirectories)
            .all { it.isDirectory || it.mkdirs() }

    /**
     * True when every directory the layout promises is already there.
     *
     * The cache directories are deliberately *not* checked. They are a
     * performance aid, and a container whose caches were cleared is still a
     * valid container — reporting it as unprovisioned would send it back through
     * a full rebuild to recreate three empty directories.
     */
    fun directoriesExist(): Boolean =
        listOf(base, prefix, tmp, logs).all { it.isDirectory }

    private companion object {
        const val PREFIX = "prefix"
        const val TMP = "tmp"
        const val CACHES = "caches"
        const val PROVISION_STATE = "provisioned.json"
        const val REGISTRY_SEED = "prefix-seed.reg"
        const val LEGACY_COMPONENTS = "components"
    }
}

/** The manifest `build/package_wcp.py` writes at the root of every `.wcp`. */
const val WCP_PROFILE: String = "profile.json"

/** One version of one component: the shared store's key, and a container's reference. */
data class ComponentVersion(val type: ComponentType, val versionCode: Int) {
    override fun toString(): String = "${type.wire}/$versionCode"
}

/**
 * The shared component store's layout — `filesDir/components/`.
 *
 * Keyed by type *and* version, not by type alone. Two containers on different
 * Wine builds is a case the app has to support, and a store keyed by type would
 * make installing the second one silently break the first.
 *
 * The version directory is exactly what the package contained, plus its
 * `profile.json`. Everything the store itself needs to remember lives *beside*
 * it in `<versionCode>.json` ([ComponentRecord]), so a directory listing of a
 * version is a listing of the package and nothing else.
 */
data class ComponentStoreLayout(
    /** `filesDir/components/`. */
    val root: File,
) {
    /**
     * Install staging, so the rename into place stays on one filesystem.
     *
     * The leading dot keeps it out of the type namespace: no [ComponentType.wire]
     * can start with one, so this can never be mistaken for a component.
     */
    val staging: File get() = File(root, STAGING)

    /** `components/<Type>/` — every installed version of one type. */
    fun type(type: ComponentType): File = File(root, type.wire)

    /** `components/<Type>/<versionCode>/` — the payload. */
    fun version(type: ComponentType, versionCode: Int): File =
        File(type(type), versionCode.toString())

    /** `components/<Type>/<versionCode>.json` — [ComponentRecord]. */
    fun record(type: ComponentType, versionCode: Int): File =
        File(type(type), "$versionCode$RECORD_SUFFIX")

    /** The unpacked package's own manifest, which is what proves it is one. */
    fun profile(type: ComponentType, versionCode: Int): File =
        File(version(type, versionCode), WCP_PROFILE)

    /** True when [version] holds an unpacked package rather than a stray directory. */
    fun isInstalled(type: ComponentType, versionCode: Int): Boolean =
        profile(type, versionCode).isFile

    /** Installed versions of [type], newest first. */
    fun versions(type: ComponentType): List<Int> =
        type(type).listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { it.name.toIntOrNull() }
            .filter { isInstalled(type, it) }
            .sortedDescending()

    /**
     * Every installed version of every known type.
     *
     * Driven by [ComponentType.entries] rather than by a directory listing, so a
     * directory whose name is not a type this app can load — a stray, or the
     * staging directory — is never mistaken for a component.
     */
    fun installed(): List<ComponentVersion> =
        ComponentType.entries.flatMap { type -> versions(type).map { ComponentVersion(type, it) } }

    fun createDirectories(): Boolean = listOf(root, staging).all { it.isDirectory || it.mkdirs() }

    private companion object {
        const val STAGING = ".staging"
        const val RECORD_SUFFIX = ".json"
    }
}
