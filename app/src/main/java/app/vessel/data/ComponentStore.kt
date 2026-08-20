package app.vessel.data

import app.vessel.core.ComponentType
import app.vessel.core.WcpProfile
import app.vessel.core.deleteTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One installed version, as the store can describe it from disk. */
data class StoredComponent(
    val version: ComponentVersion,
    /** `components/<Type>/<versionCode>/`. */
    val directory: File,
    val profile: WcpProfile,
    /** The registry's id, when [ComponentRecord] recorded one. */
    val packageId: String?,
) {
    val type: ComponentType get() = version.type
    val versionCode: Int get() = version.versionCode
}

/** What [ComponentStore.prune] removed. */
data class PruneResult(
    val removed: List<ComponentVersion>,
    val freedBytes: Long,
) {
    val isEmpty: Boolean get() = removed.isEmpty()
}

/** What [ComponentStore.migrate] found in the old per-container layout. */
data class MigrationResult(
    /** Moved into the shared store; these bytes were kept. */
    val moved: List<ComponentVersion>,
    /** Already in the store under the same key, so the container's copy was deleted. */
    val discarded: List<ComponentVersion>,
    /** `containers/<id>/components/<name>` this could not key by version. Left alone. */
    val unresolved: List<String>,
) {
    val isEmpty: Boolean get() = moved.isEmpty() && discarded.isEmpty() && unresolved.isEmpty()

    internal companion object {
        val NOTHING = MigrationResult(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The shared component store: one copy of each component version, for every
 * container on the device.
 *
 * Wine is 912 MB unpacked. It used to live at `containers/<id>/components/Wine/`,
 * so three containers on the same build cost 2.7 GB to store one Wine, and a
 * version bump cost a full re-extract per container — which is the opposite of
 * what the `.wcp` mechanism exists for. Now it is installed once at
 * `components/Wine/<versionCode>/` and containers *reference* it.
 *
 * ## Reference counting
 *
 * A container's references are the `type -> versionCode` entries in its own
 * `provisioned.json` ([ProvisionedState.componentVersions]), which is the only
 * file that knows what a container is actually using. The count is taken from
 * **disk**, over every directory under `containers/`, rather than from the
 * container document: a container directory that outlives its document entry
 * still has a prefix that expects its components to be there, and over-counting
 * only costs disk while under-counting deletes a live Wine tree.
 *
 * For the same reason the count also reads the older [ProvisionedState.components]
 * map, whose records carry a `versionCode` too. A `provisioned.json` written
 * before `componentVersions` existed decodes with that field defaulted to empty,
 * and reading only the new field would make every pre-upgrade container look
 * like it referenced nothing.
 *
 * [prune] is the only thing that deletes a component. It is called from two
 * places, both chosen because they are the moments a version can *stop* being
 * referenced: after [adoptLatest] moves a container forward, and after a
 * container is deleted. It is never called speculatively, and it never takes
 * a hint about what to remove -- it recounts references across every container
 * directory on disk and removes only what nothing names.
 *
 * That is what keeps the original hazard closed. The risk was never "prune ran",
 * it was "prune deleted the Wine a running container needs", and a running
 * container protects itself by having written its reference down before it
 * started. The counting above over-counts on purpose for the same reason.
 */
@Singleton
class ComponentStore @Inject constructor(
    private val paths: ContainerPaths,
    private val installer: WcpInstaller,
    private val json: Json,
) {
    /** Where the store lives. Handed to [WcpInstaller], which picks the version directory. */
    val layout: ComponentStoreLayout get() = paths.components

    private val migrationLock = Mutex()
    private var migration: MigrationResult? = null

    /**
     * Install [archive] into the store, or report that its version is already
     * there.
     *
     * [packageId] is the registry's id for the build (`dxvk-2.7.1-canoe`), which
     * `profile.json` does not carry — see [ComponentRecord].
     */
    suspend fun install(
        archive: File,
        packageId: String? = null,
        expectedSha256: String? = null,
    ): WcpInstallResult = install(FileWcpSource(archive), packageId, expectedSha256)

    /**
     * The same install from any [WcpSource] — a downloaded file, or a package
     * bundled in the APK.
     *
     * One entry point on purpose. The store's layout is keyed by type and
     * `versionCode`, and a container's `provisioned.json` references those keys, so
     * an install that produced a different layout would leave every existing
     * container unable to resolve its own components. Both sources therefore go
     * through the same [WcpInstaller] and land in the same place; where the bytes
     * came from is not something the store, or anything downstream of it, can tell.
     */
    suspend fun install(
        source: WcpSource,
        packageId: String? = null,
        expectedSha256: String? = null,
        onProgress: ((WcpProgress) -> Unit)? = null,
    ): WcpInstallResult {
        migrate()
        return installer.install(source, layout, expectedSha256, packageId, onProgress)
    }

    /** Every version of every type in the store, read from disk. */
    suspend fun installed(): List<StoredComponent> = withContext(Dispatchers.IO) {
        migrate()
        layout.installed().mapNotNull { read(it) }
    }

    /**
     * Where a container's [type] resolves to, or null when it references none.
     *
     * This is what builds the paths in [app.vessel.core.sessionEnvironment]: the
     * environment is a pure function of its arguments and has no disk of its
     * own, so the shared-store lookup has to happen here and be handed to it.
     */
    suspend fun directoryFor(
        containerId: String,
        type: ComponentType,
    ): File? = withContext(Dispatchers.IO) {
        migrate()
        val versionCode = referencesOf(paths.of(containerId))[type.wire]
            ?: return@withContext null
        if (layout.isInstalled(type, versionCode)) layout.version(type, versionCode) else null
    }

    /**
     * Point [containerId] at the newest installed version of every type it does
     * not already reference, and return what was newly adopted.
     *
     * This is `@latest` applied to the *store* rather than to the registry, and
     * it is what lets a container launch at all. A reference is only ever written
     * by [ContainerProvisioner], which needs a `.wcp` archive to install — and
     * there is no downloader yet, so a device with components already in the
     * store and a container that has never been provisioned would otherwise have
     * [directoryFor] return null for Wine and no way to fix it from any screen.
     *
     * **A Wine reference is never re-pointed. Every other type is.**
     *
     * The original rule was blanket — no existing reference was ever changed —
     * and the reason given was that a container's prefix was booted against its
     * Wine build, so moving it silently is an upgrade that breaks a working
     * setup with nothing to blame. That is true, and it is still true, *of Wine*.
     *
     * It is not true of anything else here. FEX, DXVK, vkd3d, D8VK, Turnip and
     * OpenGL are payloads copied into `system32` by
     * [SessionRuntime.copyWindowsPayload] on every launch; the prefix holds no
     * state derived from which version was used last. Refusing to re-point them
     * did not protect anything, it just made an installed component unreachable.
     *
     * **What that cost, measured on 2026-08-13.** A FEX build carrying a fix for
     * a startup crash installed correctly, was listed as installed, and was
     * never loaded: the container kept its reference to the previous version. The
     * next session crashed identically, at a byte-identical address, and that was
     * read as "the fix did not work" rather than "the fix did not run". Four test
     * cycles went that way, across three different mechanisms that each end in a
     * container quietly running old components — this one, the version-code
     * collision in the build scripts, and the file-length staging check in
     * SessionRuntime.
     *
     * **Wine no longer holds either, and the fifth repeat is why.** The rule
     * above kept Wine pinned and offered [staleReferences] as the consolation,
     * on the argument that an upgrade under a booted prefix breaks a working
     * setup with nothing to blame. What actually happened on 2026-08-13 is the
     * failure this class was written to end, one more time: a Wine build
     * carrying a new debug channel installed correctly, the container kept
     * 100110003, and the channel was switched on and read as *silence*. Wine
     * ignores an unknown channel in `WINEDEBUG` without complaint, so the
     * instrument reported "the bug is gone" when the instrument was not there.
     * A warning in a list nobody was reading did not prevent that, and a
     * measurement that is confidently wrong is worse than a broken launch,
     * which at least announces itself.
     *
     * So every type is re-pointed now, Wine included. The upgrade risk is real
     * but bounded and loud: a prefix whose Wine moved gets a `wineboot` update
     * on the next launch, which is the path Wine itself supports and the same
     * one a user takes when they change the build by hand. The risk of the old
     * rule was neither bounded nor loud.
     *
     * **Adoption is by version code, and the payload digest works one layer
     * down.** An earlier version of this comment claimed adoption itself was
     * digest-keyed, which is not true and is worth stating plainly rather than
     * quietly correcting: the store's directories *are* the version codes, so
     * choosing between installed versions can only be a comparison of those.
     *
     * The digest ([WcpProfile.payloadSha256]) decides re-staging instead —
     * `SessionRuntime.payloadIdentity` compares it to what a prefix was last
     * given, so a rebuild that keeps its version code but changes its bytes is
     * still copied into `system32` again. That is the case a version-code
     * comparison cannot see, and it is covered, just not here.
     *
     * The two together are what make a component change visible: the code says
     * *which* version a container uses, the digest says whether that version's
     * bytes have moved underneath it.
     */
    suspend fun adoptLatest(containerId: String): Map<ComponentType, Int> =
        withContext(Dispatchers.IO) {
            migrate()
            val container = paths.of(containerId)
            val existing = referencesOf(container)
            val adopted = LinkedHashMap<ComponentType, Int>()
            for (type in ComponentType.entries) {
                val newest = layout.versions(type).firstOrNull() ?: continue
                val current = existing[type.wire]
                when {
                    // Never referenced: adopt, which is what lets a container
                    // launch at all.
                    current == null -> adopted[type] = newest
                    // Only ever forward. A store holding an older build beside a
                    // newer one must not walk a container backwards. Wine is no
                    // longer excepted here — see the note above for the fifth
                    // repeat of the failure that exception caused.
                    newest > current -> adopted[type] = newest
                    else -> continue
                }
            }
            if (adopted.isEmpty()) return@withContext emptyMap()

            val state = readState(container.provisionState) ?: ProvisionedState()
            val written = runCatching {
                container.base.mkdirs()
                container.provisionState.writeText(
                    json.encodeToString(
                        ProvisionedState.serializer(),
                        state.copy(
                            schemaVersion = CURRENT_PROVISION_SCHEMA,
                            componentVersions = state.componentVersions +
                                adopted.mapKeys { it.key.wire },
                        ),
                    ),
                )
            }.isSuccess
            // A reference that did not reach disk is not a reference: reporting it
            // would have the launcher announce components the next `prune` cannot
            // see anything holding.
            if (written) adopted else emptyMap()
        }

    /**
     * Types this container references at an older version than the store holds.
     *
     * In practice this is Wine and only Wine — [adoptLatest] moves everything
     * else forward on the next launch — and it exists so that "your container is
     * on an older build than the one you just installed" is a line in the log
     * rather than something worked out from a crash address. Reported as
     * `type to (referenced, newest)`; empty when nothing is behind.
     */
    suspend fun staleReferences(containerId: String): Map<ComponentType, Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            migrate()
            val existing = referencesOf(paths.of(containerId))
            buildMap {
                for (type in ComponentType.entries) {
                    val current = existing[type.wire] ?: continue
                    val newest = layout.versions(type).firstOrNull() ?: continue
                    if (newest > current) put(type, current to newest)
                }
            }
        }

    /**
     * Which containers reference each stored version.
     *
     * Empty set means nothing does, which is what makes it prunable. Read from
     * disk on every call — a cached count is a count that can be wrong about a
     * 912 MB directory.
     */
    suspend fun references(): Map<ComponentVersion, Set<String>> = withContext(Dispatchers.IO) {
        migrate()
        referencesBlocking()
    }

    /**
     * Delete every version that is neither referenced by a container nor the
     * newest of its type.
     *
     * **Called at app start, at container start and on container deletion.**
     * Those are the three moments a version can stop being referenced, so they
     * are the three worth the walk.
     *
     * The newest is retained regardless, and that is not a nicety -- see the
     * comment on `newest` below for the install it silently undid.
     *
     * This used to be explicit-only, on the reasoning that automatic pruning
     * risks deleting a Wine tree a running container needs. The reasoning was
     * sound and the conclusion was too strong: nothing called it, so a device
     * accumulated every build it had ever installed -- about a gigabyte per
     * Wine revision, four revisions in one day. What actually prevents the
     * hazard is the reference count being taken from disk across every
     * container, which is unchanged; a container that is running has already
     * written down what it uses.
     */
    suspend fun prune(): PruneResult = withContext(Dispatchers.IO) {
        migrate()
        val referenced = referencesBlocking().filterValues { it.isNotEmpty() }.keys
        // **The newest of every type is never garbage, however unreferenced it
        // looks.** A version that has just been installed is referenced by
        // nothing until a container adopts it, and adoption happens at container
        // start -- so between the install and the next launch, the newest build
        // on the device looks exactly like the oldest.
        //
        // Observed, not reasoned about: FEX 260824 was installed from the
        // bundle at app start, pruned milliseconds later by the call that runs
        // after `install()`, and the device kept reporting 260823. The install
        // was visible on screen and the result was gone before it finished.
        //
        // `adoptLatest` takes `versions(type).firstOrNull()`, so the newest is
        // by definition the only version a container can move *to*. Deleting it
        // does not free garbage, it undoes an install.
        val newest = ComponentType.entries.mapNotNull { type ->
            layout.versions(type).firstOrNull()?.let { ComponentVersion(type, it) }
        }.toSet()
        val removed = mutableListOf<ComponentVersion>()
        var freed = 0L
        for (version in layout.installed()) {
            if (version in referenced || version in newest) continue
            val directory = layout.version(version.type, version.versionCode)
            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (!deleteTree(directory)) continue
            layout.record(version.type, version.versionCode).delete()
            removed += version
            freed += size
        }
        PruneResult(removed, freed)
    }

    /**
     * Move anything still in the old per-container layout into the store.
     *
     * Runs once per process, before any read or install. A wipe would also have
     * been defensible for a pre-release app, but the thing being wiped is up to
     * 912 MB per container that the user paid a download for, and orphaning it
     * — leaving `containers/<id>/components/` on disk with no code that can ever
     * reach it — is the one outcome with no argument for it.
     *
     * The version to key by comes from the container's own `provisioned.json`
     * first and from the payload's `profile.json` second. A directory that
     * answers to neither is left exactly where it is and reported in
     * [MigrationResult.unresolved]: it cannot be placed in a store keyed by
     * version, and deleting something merely because this code cannot read it is
     * not a trade worth making.
     */
    suspend fun migrate(): MigrationResult {
        migration?.let { return it }
        return migrationLock.withLock {
            migration ?: withContext(Dispatchers.IO) { migrateBlocking() }.also { migration = it }
        }
    }

    private fun migrateBlocking(): MigrationResult {
        val containers = paths.existingLayouts().filter { it.legacyComponents.isDirectory }
        if (containers.isEmpty()) return MigrationResult.NOTHING

        val moved = mutableListOf<ComponentVersion>()
        val discarded = mutableListOf<ComponentVersion>()
        val unresolved = mutableListOf<String>()
        layout.createDirectories()

        for (container in containers) {
            val legacy = container.legacyComponents
            var state = readState(container.provisionState) ?: ProvisionedState()
            val versions = state.componentVersions.toMutableMap()

            for (directory in legacy.listFiles().orEmpty().filter { it.isDirectory }) {
                val name = "${container.id}/${directory.name}"
                val type = ComponentType.entries.firstOrNull { it.wire == directory.name }
                if (type == null) {
                    unresolved += name
                    continue
                }
                val versionCode = state.components[type.wire]?.versionCode
                    ?: readProfile(File(directory, WCP_PROFILE))?.versionCode
                if (versionCode == null) {
                    unresolved += name
                    continue
                }

                val version = ComponentVersion(type, versionCode)
                if (layout.isInstalled(type, versionCode)) {
                    // Another container already brought this exact version
                    // across. The same key is the same build, so this copy is
                    // duplicate bytes and goes.
                    if (deleteTree(directory)) discarded += version
                } else {
                    val destination = layout.version(type, versionCode)
                    destination.parentFile?.mkdirs()
                    if (!move(directory, destination)) {
                        unresolved += name
                        continue
                    }
                    moved += version
                }
                versions[type.wire] = versionCode
                state.components[type.wire]?.packageId?.let { packageId ->
                    writeRecordIfAbsent(type, versionCode, packageId)
                }
            }

            if (versions != state.componentVersions) {
                state = state.copy(
                    schemaVersion = CURRENT_PROVISION_SCHEMA,
                    componentVersions = versions,
                )
                runCatching {
                    container.base.mkdirs()
                    container.provisionState.writeText(
                        json.encodeToString(ProvisionedState.serializer(), state),
                    )
                }
            }
            // Whatever is left is either nothing or something `unresolved`
            // names; `delete` only succeeds on an empty directory, which is
            // exactly the condition for removing it.
            legacy.delete()
        }
        return MigrationResult(moved, discarded, unresolved)
    }

    /** A rename, which is what this is on internal storage; a copy when it is not. */
    private fun move(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        val copied = runCatching { from.copyRecursively(to, overwrite = true) }.getOrDefault(false)
        if (!copied) {
            deleteTree(to)
            return false
        }
        deleteTree(from)
        return true
    }

    private fun referencesBlocking(): Map<ComponentVersion, Set<String>> {
        val store = layout
        val out = mutableMapOf<ComponentVersion, MutableSet<String>>()
        for (container in paths.existingLayouts()) {
            for ((wire, versionCode) in referencesOf(container)) {
                val type = ComponentType.entries.firstOrNull { it.wire == wire } ?: continue
                out.getOrPut(ComponentVersion(type, versionCode)) { mutableSetOf() } += container.id
            }
        }
        // A version nothing references still has to appear, or `prune` would
        // never see it.
        for (version in store.installed()) out.getOrPut(version) { mutableSetOf() }
        return out
    }

    /** Both reference fields of one container's `provisioned.json`, merged. */
    private fun referencesOf(container: ContainerLayout): Map<String, Int> {
        val state = readState(container.provisionState) ?: return emptyMap()
        val out = mutableMapOf<String, Int>()
        for ((wire, record) in state.components) out[wire] = record.versionCode
        for ((wire, versionCode) in state.componentVersions) out[wire] = versionCode
        return out
    }

    private fun read(version: ComponentVersion): StoredComponent? {
        val directory = layout.version(version.type, version.versionCode)
        val profile = readProfile(layout.profile(version.type, version.versionCode)) ?: return null
        // The directory says one type and the profile another only if something
        // outside this app wrote it. Skipped rather than shown: there is no code
        // path that could load it.
        if (profile.componentType != version.type) return null
        return StoredComponent(
            version = version,
            directory = directory,
            profile = profile,
            packageId = readRecord(version)?.packageId,
        )
    }

    private fun readProfile(file: File): WcpProfile? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(WcpProfile.serializer(), file.readText())
        }.getOrNull()
    }

    private fun readRecord(version: ComponentVersion): ComponentRecord? {
        val file = layout.record(version.type, version.versionCode)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ComponentRecord.serializer(), file.readText())
        }.getOrNull()
    }

    private fun writeRecordIfAbsent(type: ComponentType, versionCode: Int, packageId: String) {
        val file = layout.record(type, versionCode)
        if (file.isFile) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ComponentRecord.serializer(), ComponentRecord(packageId)))
        }
    }

    private fun readState(file: File): ProvisionedState? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ProvisionedState.serializer(), file.readText())
        }.getOrNull()
    }

    private companion object {
        /**
         * Types whose version a prefix carries state from.
         *
         * Wine boots the prefix: the registry, the drive layout and the DLL set
         * under `drive_c/windows` are all written by the build that ran
         * `wineboot`, so a later build inherits a prefix it did not create.
         * Proton is the same thing under another name.
         *
         * **This no longer blocks adoption**, and [adoptLatest] records why. It
         * survives as the reason a Wine change is worth *saying* — a prefix
         * whose Wine moved wants a `wineboot` update — rather than as a reason
         * to leave a container running a build the user believes they replaced.
         */
        val PREFIX_STATEFUL = setOf(ComponentType.WINE, ComponentType.PROTON)
    }
}
