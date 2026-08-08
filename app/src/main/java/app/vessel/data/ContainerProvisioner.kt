package app.vessel.data

import app.vessel.core.ComponentType
import app.vessel.core.PrefixRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where one provisioning step got to.
 *
 * [SKIPPED] is a first-class outcome rather than a quiet [DONE]. A second launch
 * should visibly skip five steps in one frame — that is the difference between
 * "it is doing nothing" and "there was nothing to do", and only one of those is
 * worth a checklist.
 */
enum class ProvisionStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED }

/** One row of the Preparing checklist. */
data class ProvisionStep(
    val id: String,
    val label: String,
    val status: ProvisionStatus = ProvisionStatus.PENDING,
    /** What happened, when there is something worth saying. Shown under [label]. */
    val detail: String? = null,
) {
    val finished: Boolean
        get() = status == ProvisionStatus.DONE || status == ProvisionStatus.SKIPPED
}

/**
 * Which of DESIGN.md's five session states provisioning is in.
 *
 * There are three rather than five because provisioning only ever occupies the
 * first: it maps onto **Preparing**, and leaves via **Failed** or by handing over.
 * [PROVISIONED] is deliberately not called "ready" — everything this layer owns
 * is done, and the prefix still has to be booted by something that can start a
 * process.
 */
enum class ProvisionPhase { PREPARING, PROVISIONED, FAILED }

/** The whole checklist and where it is. One of these per emission. */
data class ProvisionProgress(
    val containerId: String,
    val phase: ProvisionPhase,
    val steps: List<ProvisionStep>,
) {
    /** The step that failed, which is what the Failed state names. */
    val failedStep: ProvisionStep? get() = steps.firstOrNull { it.status == ProvisionStatus.FAILED }

    val completed: Int get() = steps.count { it.finished }

    val total: Int get() = steps.size

    /** 0f..1f, for a determinate progress indicator. */
    val fraction: Float get() = if (total == 0) 1f else completed.toFloat() / total
}

/**
 * One component to put in the container.
 *
 * The archive is supplied rather than fetched. There is no downloader yet —
 * [app.vessel.service.ComponentDownloadService] is still a shell — and a
 * provisioner that invented a URL would be shipping a fabricated component
 * source. [packageId] is the registry's id for the build, which the profile
 * inside the archive does not carry.
 */
data class ComponentInstall(
    val type: ComponentType,
    val packageId: String,
    val versionCode: Int,
    val archive: File,
    /** The published digest. Falls back to the `.sha256` sidecar when null. */
    val sha256: String? = null,
)

/** What is already installed for one type. Compared to decide whether to re-run. */
@Serializable
data class InstalledRecord(val packageId: String, val versionCode: Int)

/**
 * What provisioning has already done to a container, kept in the container's own
 * directory.
 *
 * Per container rather than in the shared document on purpose: it describes the
 * container, so deleting `containers/<id>/` has to invalidate it, and the only
 * way to guarantee that is for the record to live inside it.
 *
 * It is also the **reference list** the shared component store counts against —
 * see [ComponentStore]. A container owns no component bytes; it owns this file
 * saying which versions it uses.
 */
@Serializable
data class ProvisionedState(
    val schemaVersion: Int = CURRENT_PROVISION_SCHEMA,
    /** [PrefixRegistry.SEED_VERSION] as of the last successful registry step. */
    val registrySeedVersion: Int = 0,
    /** Keyed by [ComponentType.wire]. */
    val components: Map<String, InstalledRecord> = emptyMap(),

    /**
     * Which shared-store version of each type this container uses, keyed by
     * [ComponentType.wire]. `Wine -> 1013` means `components/Wine/1013/`.
     *
     * Defaulted rather than required, and that is doing real work: a
     * `provisioned.json` written before this field existed simply has no such
     * key, and kotlinx.serialization fills a missing field from its default —
     * which is a different mechanism from `ignoreUnknownKeys`, and would throw
     * `MissingFieldException` without it. Every schema-1 file on a device
     * decodes as referencing nothing here, which is why [ComponentStore] also
     * counts [components] (whose records carry a `versionCode`) as references.
     * Reading only this field would make a pre-upgrade container look unused and
     * let `prune` delete the Wine it is running on.
     */
    val componentVersions: Map<String, Int> = emptyMap(),
)

/** 2 added [ProvisionedState.componentVersions], when components moved to the shared store. */
const val CURRENT_PROVISION_SCHEMA: Int = 2

/** This container, now referencing [versionCode] of [type] in the shared store. */
fun ProvisionedState.withReference(type: ComponentType, versionCode: Int): ProvisionedState =
    copy(componentVersions = componentVersions + (type.wire to versionCode))

/** The outcome of asking something outside this layer to touch a live prefix. */
sealed interface BootstrapOutcome {
    data object Applied : BootstrapOutcome

    /** No implementation exists yet. The step is skipped, not failed. */
    data class NotAvailable(val reason: String) : BootstrapOutcome

    data class Failed(val reason: String) : BootstrapOutcome
}

/**
 * **The integration point. This is where provisioning stops.**
 *
 * Everything above this interface is file manipulation. Everything below needs a
 * *process* — `wineboot` for the prefix, `regedit` for the seed — which is why
 * this file can be finished before the launcher exists.
 *
 * Until then [Deferred] reports [BootstrapOutcome.NotAvailable] for both steps,
 * and they appear in the checklist as skipped with the reason attached rather
 * than claiming a prefix was created when the directory is empty.
 *
 * The launcher implements this. It must not be implemented anywhere else: a
 * second thing that knows how to start a Wine process is a second thing that has
 * to get `docs/LOGGING.md`'s pipe-on-fd-2 requirement right.
 */
interface PrefixBootstrap {

    /** `wineboot -u` against [layout]'s prefix, creating `drive_c` and `system.reg`. */
    suspend fun createPrefix(layout: ContainerLayout): BootstrapOutcome

    /** `regedit <regFile>` inside the booted prefix. */
    suspend fun applyRegistry(layout: ContainerLayout, regFile: File): BootstrapOutcome

    /** The only implementation today. Does nothing and says so. */
    object Deferred : PrefixBootstrap {
        const val REASON: String =
            "no process launcher yet — the prefix is booted on first start"

        override suspend fun createPrefix(layout: ContainerLayout) =
            BootstrapOutcome.NotAvailable(REASON)

        override suspend fun applyRegistry(layout: ContainerLayout, regFile: File) =
            BootstrapOutcome.NotAvailable(REASON)
    }
}

/**
 * Everything a container needs before a process can be started in it.
 *
 * Drives DESIGN.md's **Preparing** state: create the layout, install each
 * component, write the registry seed, hand over. Progress is a [Flow] of the
 * whole checklist rather than of the current step, because the screen draws all
 * the rows at once and a failed step has to stay on screen showing which one it
 * was.
 *
 * **Resumable, and that is the point of [ProvisionedState].** A second launch of
 * an unchanged container skips every step. Bumping one component re-runs that
 * component and nothing else — no re-extracting three gigabytes of Wine because
 * DXVK moved.
 *
 * Components go into the shared [ComponentStore], not into the container. The
 * second container asking for a Wine build the first already installed does no
 * work at all: its step records the reference and skips. What this class writes
 * per container is the *reference*, in [ProvisionedState.componentVersions].
 */
@Singleton
class ContainerProvisioner @Inject constructor(
    private val paths: ContainerPaths,
    private val store: ComponentStore,
    private val json: Json,
    private val drives: PrefixDrives,
) {

    /** The checklist for [components], all pending. What the screen draws first. */
    fun plan(components: List<ComponentInstall>): List<ProvisionStep> = buildList {
        // "Create container", not "Create prefix": this step makes directories.
        // The prefix itself is `wineboot`'s doing, three rows down, and two rows
        // both claiming to create it is how a reader ends up unable to say which
        // one failed.
        add(ProvisionStep(STEP_LAYOUT, "Create container"))
        components.forEach { add(ProvisionStep(componentStepId(it.type), "Install ${it.type.label}")) }
        add(ProvisionStep(STEP_REGISTRY, "Write registry seed"))
        add(ProvisionStep(STEP_BOOT, "Initialise Wine prefix"))
        add(ProvisionStep(STEP_READY, "Ready to start"))
    }

    /** What has already been done to [containerId]. */
    suspend fun state(containerId: String): ProvisionedState = withContext(Dispatchers.IO) {
        readState(paths.of(containerId))
    }

    /**
     * Whether [provision] would do any work.
     *
     * The Session screen asks this to decide whether to show Preparing at all —
     * DESIGN.md scopes that state to "first launch of a container only", and a
     * checklist that flashes past on every launch is worse than no checklist.
     */
    suspend fun isProvisioned(
        containerId: String,
        components: List<ComponentInstall>,
    ): Boolean = withContext(Dispatchers.IO) {
        store.migrate()
        val layout = paths.of(containerId)
        val state = readState(layout)
        layout.directoriesExist() &&
            state.registrySeedVersion == PrefixRegistry.SEED_VERSION &&
            layout.registrySeed.isFile &&
            components.all { upToDate(state, it) }
    }

    /** Forget everything, so the next [provision] redoes all of it. */
    suspend fun invalidate(containerId: String) = withContext(Dispatchers.IO) {
        paths.of(containerId).provisionState.delete()
        Unit
    }

    /**
     * Run the checklist, emitting after every status change.
     *
     * Cold: nothing happens until it is collected, and collecting it twice does
     * the work twice. A failure stops the flow — the remaining steps stay
     * [ProvisionStatus.PENDING], which is honest about the fact that they were
     * never attempted, and the last emission carries [ProvisionPhase.FAILED].
     */
    fun provision(
        containerId: String,
        components: List<ComponentInstall>,
        bootstrap: PrefixBootstrap = PrefixBootstrap.Deferred,
    ): Flow<ProvisionProgress> = flow {
        store.migrate()
        val layout = paths.of(containerId)
        var state = readState(layout)
        var steps = plan(components)

        suspend fun emitSteps(phase: ProvisionPhase) {
            emit(ProvisionProgress(containerId, phase, steps))
        }

        suspend fun mark(id: String, status: ProvisionStatus, detail: String? = null) {
            steps = steps.map {
                if (it.id == id) it.copy(status = status, detail = detail) else it
            }
            emitSteps(if (status == ProvisionStatus.FAILED) ProvisionPhase.FAILED else ProvisionPhase.PREPARING)
        }

        emitSteps(ProvisionPhase.PREPARING)

        // — layout ------------------------------------------------------------
        if (layout.directoriesExist()) {
            mark(STEP_LAYOUT, ProvisionStatus.SKIPPED, "Already created")
        } else {
            mark(STEP_LAYOUT, ProvisionStatus.RUNNING)
            if (!layout.createDirectories()) {
                mark(STEP_LAYOUT, ProvisionStatus.FAILED, "Could not create ${layout.base}")
                return@flow
            }
            mark(STEP_LAYOUT, ProvisionStatus.DONE)
        }

        // — components --------------------------------------------------------
        for (component in components) {
            val id = componentStepId(component.type)
            if (upToDate(state, component)) {
                // The reference is written even on a skip. A container
                // provisioned before the shared store existed has an
                // `InstalledRecord` and no `componentVersions` entry, and the
                // store counts the record too — but leaving the two disagreeing
                // is how they drift.
                state = state.withReference(component.type, component.versionCode)
                writeState(layout, state)
                mark(id, ProvisionStatus.SKIPPED, "${component.packageId} already installed")
                continue
            }
            mark(id, ProvisionStatus.RUNNING)
            val result = store.install(
                archive = component.archive,
                packageId = component.packageId,
                expectedSha256 = component.sha256,
            )
            when (result) {
                is WcpInstallResult.Installed -> {
                    if (result.type != component.type) {
                        mark(
                            id,
                            ProvisionStatus.FAILED,
                            "Package is ${result.type.label}, not ${component.type.label}",
                        )
                        return@flow
                    }
                    state = state.copy(
                        components = state.components + (
                            component.type.wire to InstalledRecord(
                                packageId = component.packageId,
                                versionCode = result.profile.versionCode,
                            )
                            ),
                    ).withReference(component.type, result.profile.versionCode)
                    writeState(layout, state)
                    mark(id, ProvisionStatus.DONE, result.summary)
                }

                is WcpInstallResult.Failure -> {
                    mark(id, ProvisionStatus.FAILED, result.summary)
                    return@flow
                }
            }
        }

        // — registry seed -----------------------------------------------------
        val seedCurrent = state.registrySeedVersion == PrefixRegistry.SEED_VERSION &&
            layout.registrySeed.isFile
        if (seedCurrent) {
            mark(STEP_REGISTRY, ProvisionStatus.SKIPPED, "Seed v${PrefixRegistry.SEED_VERSION} already written")
        } else {
            mark(STEP_REGISTRY, ProvisionStatus.RUNNING)
            val written = runCatching {
                layout.registrySeed.writeText(PrefixRegistry.render(), Charsets.UTF_8)
            }.isSuccess
            if (!written) {
                mark(STEP_REGISTRY, ProvisionStatus.FAILED, "Could not write ${layout.registrySeed.name}")
                return@flow
            }
            state = state.copy(registrySeedVersion = PrefixRegistry.SEED_VERSION)
            writeState(layout, state)
            mark(
                STEP_REGISTRY,
                ProvisionStatus.DONE,
                "${PrefixRegistry.seed.size} keys written to ${layout.registrySeed.name}",
            )
        }

        // Shared storage as a drive, once the prefix exists to put it in. After
        // the registry and before the boot report, so a container that gains
        // the permission later picks the drive up on its next launch rather
        // than needing to be recreated.
        drives.mapSharedStorage(layout.prefix)

        // — hand over ---------------------------------------------------------
        mark(STEP_BOOT, ProvisionStatus.RUNNING)
        val booted = bootstrap.createPrefix(layout)
        val applied = if (booted is BootstrapOutcome.Applied) {
            bootstrap.applyRegistry(layout, layout.registrySeed)
        } else {
            booted
        }
        when (applied) {
            is BootstrapOutcome.Applied -> mark(STEP_BOOT, ProvisionStatus.DONE)
            is BootstrapOutcome.NotAvailable ->
                mark(STEP_BOOT, ProvisionStatus.SKIPPED, applied.reason)

            is BootstrapOutcome.Failed -> {
                mark(STEP_BOOT, ProvisionStatus.FAILED, applied.reason)
                return@flow
            }
        }

        mark(STEP_READY, ProvisionStatus.DONE, "${components.size} component(s) installed")
        emit(ProvisionProgress(containerId, ProvisionPhase.PROVISIONED, steps))
    }.flowOn(Dispatchers.IO)

    /**
     * Whether this component can be skipped.
     *
     * Both halves matter. The record has to match — a version bump changes
     * [ComponentInstall.versionCode] and re-runs the step — and the version has
     * to still be in the shared store, because a record claiming an install that
     * a failed swap, a `prune`, or a user clearing storage removed is the one
     * case where trusting the record silently produces a container with no Wine
     * in it.
     */
    private fun upToDate(
        state: ProvisionedState,
        component: ComponentInstall,
    ): Boolean {
        val record = state.components[component.type.wire] ?: return false
        if (record.packageId != component.packageId) return false
        if (record.versionCode != component.versionCode) return false
        return store.layout.isInstalled(component.type, record.versionCode)
    }

    private fun readState(layout: ContainerLayout): ProvisionedState {
        val file = layout.provisionState
        if (!file.isFile) return ProvisionedState()
        return runCatching {
            json.decodeFromString(ProvisionedState.serializer(), file.readText())
        }.getOrElse {
            // An unreadable record means "provision from scratch", which is slow
            // and correct. The alternative is refusing to launch over a JSON
            // parse error in a file the user has never heard of.
            ProvisionedState()
        }
    }

    private fun writeState(layout: ContainerLayout, state: ProvisionedState) {
        runCatching {
            layout.base.mkdirs()
            layout.provisionState.writeText(
                json.encodeToString(
                    ProvisionedState.serializer(),
                    state.copy(schemaVersion = CURRENT_PROVISION_SCHEMA),
                ),
            )
        }
    }

    private fun componentStepId(type: ComponentType) = "$STEP_COMPONENT_PREFIX${type.wire}"

    companion object {
        const val STEP_LAYOUT = "layout"
        const val STEP_COMPONENT_PREFIX = "component:"
        const val STEP_REGISTRY = "registry"
        const val STEP_BOOT = "boot"
        const val STEP_READY = "ready"
    }
}
