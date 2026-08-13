package app.vessel.data

import app.vessel.core.ComponentPackage
import app.vessel.service.CatalogResult
import app.vessel.service.ComponentCatalog
import app.vessel.service.ComponentDownloader
import app.vessel.service.DownloadRequest
import app.vessel.service.DownloadResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Where first-run component installation has got to. */
enum class SetupPhase {
    /** Nothing has been asked of it yet. */
    IDLE,

    /**
     * Reading the bundle's manifests, before it is known whether there is work.
     *
     * Its own phase and not folded into [INSTALLING], because it is the phase in
     * which the answer is *usually* "nothing to do" — every launch after the
     * first — and drawing a setup dialog during it would put a dialog on screen
     * for a fifth of a second on every cold start.
     */
    SCANNING,

    INSTALLING,

    /** Everything the APK carries is in the shared store. */
    READY,

    /** Finished, with at least one package that did not install. */
    INCOMPLETE,
}

/**
 * The whole of first-run setup, as one object the UI draws.
 *
 * The rows are [ProvisionStep]s — the same type the launch checklist uses,
 * deliberately. This is the same kind of report about a different kind of work,
 * and two step models would mean two checklist components a week later.
 */
data class SetupState(
    val phase: SetupPhase = SetupPhase.IDLE,
    val steps: List<ProvisionStep> = emptyList(),
    /** Compressed bytes of the packages finished in this run. */
    val completedBytes: Long = 0,
    /** Compressed bytes consumed of the package being installed right now. */
    val currentBytes: Long = 0,
    /** Compressed bytes of everything this run set out to install. */
    val totalBytes: Long = 0,
) {
    /** Whether work is in flight. [SetupPhase.SCANNING] counts; nothing draws it. */
    val active: Boolean
        get() = phase == SetupPhase.SCANNING || phase == SetupPhase.INSTALLING

    /** Whether a report is worth showing: real work, or a failure to explain. */
    val worthShowing: Boolean
        get() = phase == SetupPhase.INSTALLING || phase == SetupPhase.INCOMPLETE

    /**
     * 0f..1f over the *compressed* bytes of the whole run.
     *
     * Weighted by package size rather than by package count, which is the
     * difference between a bar that crawls for four minutes and jumps five times
     * at the end and one that moves at a steady rate: Wine is 88% of the bundle's
     * bytes and one sixth of its rows.
     */
    val fraction: Float
        get() = if (totalBytes <= 0) {
            0f
        } else {
            ((completedBytes + currentBytes).toFloat() / totalBytes).coerceIn(0f, 1f)
        }

    val failures: List<ProvisionStep>
        get() = steps.filter { it.status == ProvisionStatus.FAILED }
}

/**
 * Unpacks the components bundled in the APK, once, without being asked.
 *
 * **There is no button and no prompt.** The requirement is that installing the
 * APK and opening the app is the whole of setup, so this runs from
 * [app.vessel.MainActivity] on first composition and the UI over it is a
 * progress report rather than a gate. Everything it needs is already inside the
 * package; there is nothing a user could usefully decide.
 *
 * ## Idempotent, resumable, and not repeated
 *
 * Three properties, and each is load-bearing:
 *
 *  - **Idempotent.** [WcpInstaller] skips a version already in the store, so a
 *    second pass over the same bundle extracts nothing and reports six skips.
 *  - **Resumable per package.** The installer extracts into
 *    `components/.staging/` and renames into place only once an archive has been
 *    read to its end, so a process killed mid-Wine leaves no half-tree that the
 *    store would then count as installed — it leaves nothing, and the next launch
 *    redoes that one package and none of the others.
 *  - **Not repeated.** The check is "is this version in the store", read off the
 *    filesystem, never a preference flag. A flag and a filesystem can disagree,
 *    and the way they disagree is a user who cleared storage and now has an app
 *    that thinks it is set up.
 *
 * ## Why not a foreground service
 *
 * A `dataSync` service would keep the unpack alive through the app being swiped
 * away, and `ComponentDownloadService` is already declared for the download path.
 * It is not used here because the honest cost of not having it is small: the work
 * resumes per package on the next launch, and the case it would protect — a user
 * who opens a freshly installed app and immediately swipes it away — costs them
 * one package's progress. A service that promised more would also have to own
 * cancellation, notification actions and a second progress channel, none of which
 * this feature has a use for.
 *
 * ## A failure is not a dead end
 *
 * A package that will not install does not stop the ones after it. Running out of
 * space part-way through is the realistic failure, and a user who ends up with
 * four of six components should see the four, be told which two are missing and
 * why, and still reach the container list. [SetupPhase.INCOMPLETE] is that state,
 * and it is a state the app opens in rather than one it stops at.
 */
@Singleton
class ComponentSetup @Inject constructor(
    private val bundled: BundledComponents,
    private val store: ComponentStore,
    private val catalog: ComponentCatalog,
    private val downloader: ComponentDownloader,
    private val paths: ContainerPaths,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /** Serialises the pass against itself; [started] is only read under it. */
    private val gate = Mutex()
    private var started = false

    /**
     * Whether [start] has been called, set synchronously by it.
     *
     * Distinct from [started], which is the "run at most once" latch and is
     * only touched inside the coroutine under [gate]. This one has to be
     * readable by [awaitFinished] the instant [start] returns.
     */
    private val requested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Run the pass, at most once per process.
     *
     * Called from the Activity, which is recreated on every rotation and on every
     * return from the session — so "at most once" is the whole contract. A second
     * call while the first is still running does nothing; a call after it has
     * finished does nothing either, including after [SetupPhase.INCOMPLETE],
     * because retrying a package that just ran out of space inside the same
     * process would only produce the same message again.
     */
    fun start() {
        // Set here, not inside the coroutine, so [awaitFinished] cannot observe
        // a caller that has already asked for the pass as one that never did.
        requested.set(true)
        scope.launch {
            gate.withLock {
                if (started) return@withLock
                started = true
                install()
            }
        }
    }

    /**
     * Suspend until the bundle's packages are in the store.
     *
     * **A launch that does not wait for this runs the previous build.**
     * [start] hands the work to a background scope, and a session's
     * `adoptLatest` used to race it: on the first launch after an APK update
     * the new `.wcp` was still being unpacked, so adoption saw only the old
     * version and pinned the container to it. Observed on 2026-08-13 with
     * FEXCore — 260803 installed, container referencing 260802, and only the
     * *second* launch picked it up. That is the same class of failure as
     * every stale-component bug before it, one layer further down: the store
     * was right, adoption was right, and adoption simply ran too early.
     *
     * Returns as soon as the pass is finished, whichever way it finished.
     * [SetupPhase.INCOMPLETE] is a finished pass — a package that ran out of
     * space is not going to arrive by waiting longer, and a session that can
     * still start with what did install should be allowed to.
     *
     * Gated on [requested] rather than on the phase being [SetupPhase.IDLE].
     * Waiting for "not IDLE" would reintroduce the race it exists to close:
     * [start] hands off to a coroutine, so a caller can arrive before that
     * coroutine has moved the phase off IDLE, read IDLE, and carry on into
     * exactly the early adoption this prevents. [requested] is set
     * synchronously inside [start], so by the time any caller could observe
     * the effects of [start] it is already true. A process that never calls
     * [start] — every unit test — returns immediately.
     */
    suspend fun awaitFinished() {
        if (!requested.get()) return
        state.first { it.phase == SetupPhase.READY || it.phase == SetupPhase.INCOMPLETE }
    }

    private suspend fun install() {
        _state.value = SetupState(phase = SetupPhase.SCANNING)

        val catalogue = bundled.catalogue()
        val pending = catalogue.filterNot { bundled.isInstalled(it) }
        if (pending.isEmpty()) {
            // Nothing bundled to unpack — the `play` flavour always, and the
            // `sideload` one until assets are added. Not the end of setup any
            // more: what a phone actually needs may be downloadable instead.
            downloadMissing()
            return
        }

        val total = pending.sumOf { it.source.sizeBytes.coerceAtLeast(0) }
        _state.value = SetupState(
            phase = SetupPhase.INSTALLING,
            steps = pending.map { ProvisionStep(it.packageId, "Install ${it.label}") },
            totalBytes = total,
        )

        var completed = 0L
        for (item in pending) {
            mark(item.packageId, ProvisionStatus.RUNNING, "unpacking…")
            var unpacked = 0L
            val result = store.install(
                source = item.source,
                packageId = item.packageId,
            ) { progress ->
                unpacked = progress.unpackedBytes
                _state.update {
                    it.copy(
                        currentBytes = progress.compressedRead,
                        steps = it.steps.map { step ->
                            if (step.id == item.packageId) step.copy(detail = progress.describe()) else step
                        },
                    )
                }
            }

            completed += item.source.sizeBytes.coerceAtLeast(0)
            _state.update { it.copy(completedBytes = completed, currentBytes = 0) }

            when (result) {
                // **Not `result.summary`.** That sentence is written for the
                // download path and ends "checksum not verified (no sidecar)"
                // whenever no digest was published — which for a bundled package
                // is a deliberate non-event (see [AssetWcpSource]) and reads on
                // screen as six warnings about something being wrong. What a
                // person watching an unpack wants is how much came out of it.
                is WcpInstallResult.Installed -> mark(
                    item.packageId,
                    ProvisionStatus.DONE,
                    if (result.reused) {
                        "already in the shared store · ${result.fileCount} files"
                    } else {
                        "${result.fileCount} files · ${unpacked / (1024 * 1024)} MB"
                    },
                )

                is WcpInstallResult.Failure ->
                    mark(item.packageId, ProvisionStatus.FAILED, result.summary)
            }
        }

        _state.update { it.copy(currentBytes = 0) }
        downloadMissing()
    }

    /**
     * Fetch whatever this device still needs, on first run, in the same dialog.
     *
     * **This is the whole install story for a fresh phone, and until now there
     * was none.** The APK bundles no `.wcp` — Wine alone is 84 MB — so a clean
     * install had a component store with nothing in it, no screen that could
     * fill it, and a container that therefore could not be provisioned. The
     * downloader, its resume logic and its digest checks have existed and been
     * tested against a real socket for weeks with nothing calling them.
     *
     * Here rather than on a Components screen because a screen is the wrong
     * shape for it: on first run this is not a choice a user makes, it is what
     * has to happen before anything works, and the setup dialog is already the
     * thing that says so. Later runs skip it — [ComponentStore.isInstalled] is
     * the test, so a phone that has everything sees nothing.
     *
     * Failures are per-package and do not stop the rest: a device that fetched
     * Wine and lost Turnip should end up with Wine. The phase becomes
     * [SetupPhase.INCOMPLETE] and each row keeps the sentence that explains it.
     */
    private suspend fun downloadMissing() {
        val listing = catalog.refresh()
        if (listing !is CatalogResult.Loaded) {
            // No catalogue is not a failure to report on a device that already
            // has its components — which is every launch after the first, and
            // every launch with no network. It is only worth saying when there
            // is nothing installed to fall back on.
            val detail = (listing as? CatalogResult.Unavailable)?.detail
            val anyInstalled = store.installed().isNotEmpty()
            _state.update {
                it.copy(
                    phase = if (it.failures.isEmpty() && (detail == null || anyInstalled)) {
                        SetupPhase.READY
                    } else {
                        SetupPhase.INCOMPLETE
                    },
                    steps = it.steps + if (detail != null && !anyInstalled) {
                        listOf(
                            ProvisionStep(
                                CATALOGUE_STEP,
                                "Find components",
                                ProvisionStatus.FAILED,
                                detail,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )
            }
            return
        }

        /*
         * **Download what is an upgrade, not what is merely absent.**
         *
         * This filtered on `!isInstalled(type, versionCode)` — the *exact*
         * version — so any catalogue entry whose precise code was missing came
         * down even when a newer build of the same type was already installed.
         * Measured on a fresh install: the app bundles the ICD build of Turnip
         * (260301) and deliberately not the HAL build beside it (260300, see
         * `bundledPackages` in app/build.gradle.kts), and setup then downloaded
         * the HAL anyway — 15 MB for a driver `adoptLatest` can never choose,
         * because it takes the highest version code. The same rule would have
         * fetched an obsolete 84 MB Wine 10.13 next to the bundled 11.14.
         *
         * Highest installed code per type, and an entry is wanted only if it
         * beats it. Equality is not an upgrade, which also keeps the old
         * exact-match behaviour for the case it was right about.
         */
        val newestInstalled = store.installed()
            .groupBy { it.type }
            .mapValues { (_, versions) -> versions.maxOf { it.version.versionCode } }
        val wanted = listing.packages.filter {
            it.isDownloadable && it.versionCode > (newestInstalled[it.type] ?: Int.MIN_VALUE)
        }
        if (wanted.isEmpty()) {
            _state.update {
                it.copy(
                    phase = if (it.failures.isEmpty()) SetupPhase.READY else SetupPhase.INCOMPLETE,
                    currentBytes = 0,
                )
            }
            return
        }

        val downloads = paths.downloadsRoot
        val total = wanted.sumOf { it.sizeBytes.coerceAtLeast(0) }
        _state.update {
            it.copy(
                phase = SetupPhase.INSTALLING,
                steps = it.steps + wanted.map { pkg -> ProvisionStep(pkg.id, "Download ${pkg.name}") },
                totalBytes = it.totalBytes + total,
            )
        }

        var completed = _state.value.completedBytes
        for (pkg in wanted) {
            mark(pkg.id, ProvisionStatus.RUNNING, "starting…")

            // Throttled to whole percent. The downloader calls back per 64 KB
            // buffer, which for 84 MB is 1400 state updates and 1400
            // recompositions of a dialog nobody can read that fast.
            var lastPercent = -1
            val request = DownloadRequest.of(pkg)
            if (request == null) {
                mark(pkg.id, ProvisionStatus.FAILED, "the registry entry has no URL or no digest")
                continue
            }
            val fetched = downloader.download(request, downloads) { progress ->
                val percent = progress.fraction?.let { (it * 100).toInt() } ?: return@download
                if (percent == lastPercent) return@download
                lastPercent = percent
                _state.update { state ->
                    state.copy(
                        currentBytes = progress.bytesDownloaded,
                        steps = state.steps.map { step ->
                            if (step.id == pkg.id) step.copy(detail = "$percent%") else step
                        },
                    )
                }
            }

            if (fetched !is DownloadResult.Complete) {
                mark(pkg.id, ProvisionStatus.FAILED, fetched.summary)
                completed += pkg.sizeBytes.coerceAtLeast(0)
                _state.update { it.copy(completedBytes = completed, currentBytes = 0) }
                continue
            }

            mark(pkg.id, ProvisionStatus.RUNNING, "unpacking…")
            val installed = store.install(
                archive = fetched.file,
                packageId = pkg.id,
                expectedSha256 = pkg.sha256,
            )
            completed += pkg.sizeBytes.coerceAtLeast(0)
            _state.update { it.copy(completedBytes = completed, currentBytes = 0) }

            when (installed) {
                is WcpInstallResult.Installed -> {
                    // The archive has served its purpose and is the largest
                    // thing in the app's storage. Keeping it would double the
                    // cost of every component on a phone.
                    runCatching { fetched.file.delete() }
                    mark(
                        pkg.id,
                        ProvisionStatus.DONE,
                        "${installed.fileCount} files installed",
                    )
                }

                is WcpInstallResult.Failure ->
                    mark(pkg.id, ProvisionStatus.FAILED, installed.summary)
            }
        }

        _state.update {
            it.copy(
                phase = if (it.failures.isEmpty()) SetupPhase.READY else SetupPhase.INCOMPLETE,
                currentBytes = 0,
            )
        }
    }

    /** The row that says the catalogue itself could not be read. */
    private val CATALOGUE_STEP = "catalogue"

    private fun mark(id: String, status: ProvisionStatus, detail: String?) {
        _state.update { state ->
            state.copy(
                steps = state.steps.map {
                    if (it.id == id) it.copy(status = status, detail = detail) else it
                },
            )
        }
    }
}

/**
 * What a row says while it is unpacking.
 *
 * Unpacked megabytes and a file count rather than a percentage, because the bar
 * beside it already carries the percentage and this is the line that says the
 * phone is actually doing something. Both numbers are counted, not estimated.
 */
private fun WcpProgress.describe(): String =
    "${unpackedBytes / (1024 * 1024)} MB unpacked · $files files"
