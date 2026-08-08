package app.vessel.data

import android.content.Context
import android.os.PowerManager
import app.vessel.core.ComponentType
import app.vessel.core.ContainerProfile
import app.vessel.core.DEFAULT_DISPLAY
import app.vessel.core.FILE_MANAGER_COMMAND
import app.vessel.core.DisplayGeometry
import app.vessel.core.DisplayOutcome
import app.vessel.core.DisplayParams
import app.vessel.core.DisplayRequest
import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.PrefixRegistry
import app.vessel.core.SessionDiagnosis
import app.vessel.core.SessionDisplayServer
import app.vessel.core.SYSVSHM_SOCKET_ENV
import app.vessel.core.SessionPaths
import app.vessel.core.SessionScratch
import app.vessel.core.TurnipDriver
import app.vessel.core.WINE_BOOT
import app.vessel.core.WINE_FILE_MANAGER
import app.vessel.core.WINE_REGEDIT
import app.vessel.core.WINE_UNIX_ARCH
import app.vessel.core.WineTree
import app.vessel.core.desktopArgv
import app.vessel.core.diagnoseSessionLine
import app.vessel.core.fileManagerArgv
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import app.vessel.core.parseFpsLimit
import app.vessel.core.parseGeometry
import app.vessel.core.parseSessionLogLine
import app.vessel.core.serverArgv
import app.vessel.core.sessionEnvironment
import app.vessel.core.toolArgv
import app.vessel.core.wineLauncherEnvironment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DESIGN.md's five session states, plus the one before any of them.
 *
 * ```
 * PREPARING ──► STARTING ──► RUNNING ──► EXITED
 *      │             │           │
 *      └─────────────┴───────────┴──────► FAILED
 * ```
 */
enum class SessionPhase { IDLE, PREPARING, STARTING, RUNNING, EXITED, FAILED }

/**
 * Everything the Session screen draws, and the only thing this layer publishes.
 *
 * One object rather than a state per phase: the checklist has to stay on screen
 * after a failure showing which row failed, so the Failed state is Preparing's
 * data plus a reason rather than a different screen.
 */
data class SessionState(
    val containerId: String? = null,
    val containerName: String = "",
    val phase: SessionPhase = SessionPhase.IDLE,
    /** The Preparing checklist, in order. Empty before the first launch. */
    val steps: List<ProvisionStep> = emptyList(),
    /**
     * The most recent line of output, while the screen is still a checklist.
     *
     * DESIGN.md: Starting shows this, "because that is where a missing DLL
     * surfaces". Not published once Running, where nothing draws it and the
     * process can emit thousands of lines a second.
     */
    val lastLine: String? = null,
    /** The last line classified as an error, which is what Failed shows. */
    val lastError: String? = null,
    /** The first recognised failure signature. See [SessionDiagnosis]. */
    val diagnosis: SessionDiagnosis? = null,
    /** Set when the launcher itself refused, as opposed to Wine failing. */
    val failure: String? = null,
    val exitCode: Int? = null,
    val startedAt: Long? = null,
    val geometry: DisplayGeometry? = null,
    val fpsLimit: Int? = null,
    /**
     * Whether the guest's processes are `SIGSTOP`ped.
     *
     * A flag on RUNNING rather than a sixth phase, and the reason is what reads
     * the phase. [SessionMetricsRecorder] keys its sampler on `RUNNING`, and a
     * `PAUSED` phase would silently stop the trace — so a run the user paused for
     * five minutes would have a five-minute hole in it rather than five minutes
     * of the flat line that is the honest record. `SessionService` reads the
     * phase too, and a paused session is emphatically still a foreground one.
     *
     * Every reading therefore keeps arriving and every one of them falls to
     * idle, which is indistinguishable from a container waiting for input. This
     * flag is what the UI labels that with; see the rail.
     */
    val paused: Boolean = false,
) {
    val active: Boolean
        get() = phase == SessionPhase.PREPARING ||
            phase == SessionPhase.STARTING ||
            phase == SessionPhase.RUNNING

    val finished: Boolean
        get() = phase == SessionPhase.EXITED || phase == SessionPhase.FAILED

    /** The step the Failed state names. */
    val failedStep: ProvisionStep?
        get() = steps.firstOrNull { it.status == ProvisionStatus.FAILED }
}

/**
 * What [SessionRuntime.launchFileManager] did.
 *
 * A result rather than a `Boolean` because the two ways it can decline are
 * different things to say: one is "you already have one open" and the other names
 * a package that cannot provide it. DESIGN.md's rule about a control that
 * controls nothing applies to a button that silently does nothing too.
 */
sealed interface FileManagerLaunch {
    data object Started : FileManagerLaunch

    /** One this class started is still alive. Nothing was launched. */
    data object AlreadyRunning : FileManagerLaunch

    data class Unavailable(val reason: String) : FileManagerLaunch
}

/**
 * Starts a container and owns the processes while it runs.
 *
 * A singleton with room for exactly one session, which is a product decision
 * rather than a simplification: two Wine trees on this phone is two 912 MB
 * prefixes fighting over one GPU, and there is one screen to show either of them
 * on.
 *
 * ## Why the launcher starts `wineserver` itself
 *
 * Wine starts its own server when a client finds no socket — `start_server()`
 * forks and `execv`s `bin/wineserver` directly. That exec is a plain `execve` of
 * a file in `filesDir`, which this app's SELinux domain denies (see
 * `app.vessel.core.SYSTEM_LINKER`). Only *we* can build the `linker64` argv, so
 * the server is started here, before anything connects, and every later client
 * finds it already listening.
 *
 * The same limitation is not fully solved: Wine also re-execs its loader to
 * create each new Windows process, so an installer that spawns a child will hit
 * it. That needs a change on the Wine side and is out of this class's reach —
 * the failure at least lands in the session log rather than vanishing.
 *
 * It also implements [PrefixBootstrap], which is the integration point
 * [ContainerProvisioner] was written against and left [PrefixBootstrap.Deferred]
 * until something could start a process. It must stay the only implementation:
 * a second one is a second place that has to get the fd-2 pipe rule right.
 */
@Singleton
class SessionRuntime @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val containers: ContainerRepository,
    private val provisioner: ContainerProvisioner,
    private val components: ComponentStore,
    private val manifests: ParamManifestStore,
    private val paths: ContainerPaths,
    private val logs: SessionLogStore,
    private val runner: WineProcessRunner,
    private val guest: GuestProcessTree,
    private val display: SessionDisplayServer,
    private val json: Json,
) : PrefixBootstrap {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Serialises start against stop, so a fast Retry cannot overlap two sessions. */
    private val lifecycle = Mutex()
    private var session: Job? = null

    /**
     * What the running session resolved to.
     *
     * Mutable field rather than a parameter because [PrefixBootstrap]'s two
     * methods take only a [ContainerLayout] — they were designed before there was
     * anything to launch with. The [lifecycle] mutex is what makes one field
     * safe: there is never more than one session, and the provisioner is only
     * ever driven from inside it.
     */
    @Volatile
    private var plan: LaunchPlan? = null

    @Volatile
    private var wineserver: Process? = null

    @Volatile
    private var desktop: Process? = null

    /**
     * Every file manager this class started, alive or not.
     *
     * A list rather than one slot because the rail button can be pressed again,
     * and the point of the list is teardown: `wineserver -k` ends the guest
     * processes but these are also *our* children, and a `Process` nobody
     * destroys leaves a zombie and a drain coroutine reading a pipe that will
     * never close. Guarded by [lifecycle] on every path that touches it.
     */
    private val fileManagers = mutableListOf<Process>()

    /**
     * Whether the user asked for this.
     *
     * A session the user stopped ends [SessionExit.OK]. Without the flag it would
     * be recorded as `CRASHED` — the honest reading of a session that never got
     * to say goodbye, but a deliberate Stop is exactly the case the store *can*
     * account for, and a history where every stopped run is red is a history
     * nobody can find a real crash in.
     */
    @Volatile
    private var stopRequested = false

    /**
     * Serialises pause against resume against teardown.
     *
     * Its own lock rather than [lifecycle], which `stop` holds across a
     * `cancelAndJoin` — and teardown, which runs inside that join, has to be able
     * to continue a stopped tree before anything tries to kill it. Sharing the
     * lock would be a deadlock with the guest left suspended, which is the one
     * state nothing can recover from.
     */
    private val signals = Mutex()

    /** What [pause] stopped, so [resume] undoes exactly it. Empty when running. */
    @Volatile
    private var pausedPids: List<Int> = emptyList()

    private var wakeLock: PowerManager.WakeLock? = null

    /** Throttles [SessionState.lastLine]; the log itself is never throttled here. */
    @Volatile
    private var lastPublishedAt = 0L

    // — control ---------------------------------------------------------------

    /**
     * Launch [containerId], replacing whatever the state was showing.
     *
     * [native] is the phone's own panel size, which only the UI can measure. It
     * is what `display.resolution: native` resolves to; null falls back to the
     * manifest default rather than to a zero-sized desktop.
     */
    fun start(containerId: String, native: DisplayGeometry? = null) {
        scope.launch {
            lifecycle.withLock {
                if (session?.isActive == true) return@withLock
                stopRequested = false
                // The previous run's set, if teardown raced a pause. Nothing in
                // it can be alive, and carrying it forward would send SIGCONT to
                // whatever inherited those pids next.
                pausedPids = emptyList()
                session = scope.launch { runSession(containerId, native) }
            }
        }
    }

    /** Stop the session and everything it started. Safe to call when there is none. */
    fun stop() {
        stopRequested = true
        scope.launch {
            lifecycle.withLock { session?.cancelAndJoin() }
        }
    }

    /**
     * Suspend or resume the guest, with `SIGSTOP` and `SIGCONT`.
     *
     * The X server is ours and is not in the tree, so the last frame the guest
     * drew stays on screen: a paused desktop looks frozen rather than blank,
     * which is what a pause should look like.
     *
     * Not routed through [app.vessel.service.SessionService] the way Stop is.
     * Stop goes that way because it ends the service's own reason to exist and
     * the notification offers it too; a pause changes nothing about the
     * service's lifetime, and putting it behind an intent would only add a way
     * for the two to disagree about whether the tree is stopped.
     */
    fun setPaused(paused: Boolean) {
        scope.launch {
            signals.withLock { if (paused) pauseGuest() else resumeGuest() }
        }
    }

    private fun pauseGuest() {
        val current = _state.value
        if (current.phase != SessionPhase.RUNNING || current.paused) return
        val stopped = guest.pause()
        pausedPids = stopped
        // A pause that stopped nothing is not a pause, and saying so is cheaper
        // than a frozen-looking UI over a desktop that is still running.
        plan?.log?.line(
            LogSource.VESSEL,
            if (stopped.isEmpty()) LogLevel.WARN else LogLevel.INFO,
            if (stopped.isEmpty()) {
                "pause found no guest process to stop"
            } else {
                "paused ${stopped.size} guest process(es): ${stopped.joinToString(" ")}"
            },
        )
        if (stopped.isEmpty()) return
        _state.update { it.copy(paused = true) }
    }

    /**
     * Continue the tree. Safe, and deliberately unconditional, when nothing is
     * paused — teardown calls it on every path rather than checking first,
     * because the check is exactly the thing that would be wrong in the case
     * that matters.
     */
    private fun resumeGuest() {
        val known = pausedPids
        if (known.isEmpty() && !_state.value.paused) return
        guest.resume(known)
        pausedPids = emptyList()
        plan?.log?.line(LogSource.VESSEL, LogLevel.INFO, "resumed ${known.size} guest process(es)")
        _state.update { it.copy(paused = false) }
    }

    /** Forget a finished session, so the screen can be left without it lingering. */
    fun clear() {
        if (_state.value.active) return
        _state.value = SessionState()
    }

    /**
     * Open Wine's file manager on the running desktop. The rail's button.
     *
     * Independent of `display.fileManager`, which only decides what the desktop
     * starts with: a button press is a request and overrides a preference.
     *
     * **This process is not the session.** It is started, drained into the same
     * log, and destroyed at teardown, but nothing waits on it and its exit code is
     * never read — the session still ends when and only when the desktop process
     * exits. A failure to start is reported to the caller and does not touch
     * [SessionState].
     */
    suspend fun launchFileManager(): FileManagerLaunch = lifecycle.withLock {
        val current = plan
        if (current == null || _state.value.phase != SessionPhase.RUNNING) {
            return@withLock FileManagerLaunch.Unavailable("there is no running desktop to open it on")
        }
        fileManagers.removeAll { !it.isAlive }
        if (fileManagers.isNotEmpty()) return@withLock FileManagerLaunch.AlreadyRunning
        if (!current.tree.hasProgram(WINE_FILE_MANAGER)) {
            return@withLock FileManagerLaunch.Unavailable(
                "this Wine package has no $WINE_FILE_MANAGER in lib/wine/${current.tree.peLib.name}",
            )
        }
        startFileManager(current)
    }

    // — the session -----------------------------------------------------------

    private suspend fun runSession(containerId: String, native: DisplayGeometry?) {
        val startedAt = System.currentTimeMillis()
        val profile = containers.get(containerId)
        if (profile == null) {
            _state.value = SessionState(
                containerId = containerId,
                phase = SessionPhase.FAILED,
                failure = "This container no longer exists.",
            )
            return
        }

        val manifest = manifests.load().getOrNull()
        val geometry = parseGeometry(text(profile, manifest, DisplayParams.RESOLUTION), native)
        val fpsLimit = parseFpsLimit(text(profile, manifest, DisplayParams.FPS_LIMIT))

        _state.value = SessionState(
            containerId = containerId,
            containerName = profile.name,
            phase = SessionPhase.PREPARING,
            steps = checklist(),
            startedAt = startedAt,
            geometry = geometry,
            fpsLimit = fpsLimit,
        )

        val log = logs.open(containerId, startedAt)
        log.header(
            listOf(
                "container  ${profile.name}",
                "wine       ${profile.wineBuild}",
                "driver     ${profile.driver}",
                "d3d        ${profile.d3dLayer}",
                "desktop    $geometry @ ${fpsLimit?.let { "$it fps" } ?: "unlimited"}",
            ),
        )

        try {
            prepare(containerId, profile, manifest, log) ?: return
            runDesktop(log, geometry, fpsLimit, flag(profile, manifest, DisplayParams.FILE_MANAGER))
        } finally {
            // Teardown runs after a cancellation as well as after an exit, and
            // every step of it suspends, so it needs a context that is not
            // already cancelled.
            withContext(NonCancellable) { teardown(log) }
        }
    }

    /**
     * DESIGN.md's Preparing state. Returns the plan, or null when a step failed.
     *
     * **The order is forced, and the forcing constraint is not the obvious one.**
     * The PE payloads go into `drive_c\windows\system32` *before* `wineboot`
     * runs, not after, because [PrefixRegistry.arm64ecEmulator] makes
     * `libarm64ecfex.dll` a load-time dependency of **every** process in the
     * prefix — `load_arm64ec_module()` runs in `LdrInitializeThunk` before
     * `kernel32` and terminates the process if the DLL named by the key is
     * missing. Deploying afterwards would leave a window in which the registry
     * points at a file that is not there yet, and the next thing to start —
     * including a retry's own `wineboot` — would die on it.
     *
     * A fresh prefix bootstraps anyway because Wine builds its own stub
     * `xtajit64.dll` (`configure.ac`: `enable_xtajit64=arm64ec`) as a builtin in
     * `lib/wine/aarch64-windows`, which is what the key falls back to before
     * `regedit` has run. That is the only reason this is not a deadlock.
     */
    private suspend fun prepare(
        containerId: String,
        profile: ContainerProfile,
        manifest: ParamManifest?,
        log: SessionLog,
    ): LaunchPlan? {
        val layout = paths.of(containerId)

        mark(STEP_COMPONENTS, ProvisionStatus.RUNNING)
        val adopted = components.adoptLatest(containerId)
        val wineDir = components.directoryFor(containerId, ComponentType.WINE)
        if (wineDir == null) {
            return failStep(
                STEP_COMPONENTS,
                "No Wine build is installed. Install the Wine component before launching.",
                log,
            )
        }
        val tree = WineTree(wineDir)
        if (!tree.isUsable) {
            return failStep(
                STEP_COMPONENTS,
                "The installed Wine package is incomplete — no bin/wine or " +
                    "lib/wine/$WINE_UNIX_ARCH/ntdll.so under ${wineDir.name}.",
                log,
            )
        }

        val turnip = turnipDriver(containerId)
        val environment = wineLauncherEnvironment(
            tree = tree,
            scratch = SessionScratch(home = layout.base, tmp = layout.tmp),
        ) + sessionEnvironment(
            profile = profile,
            manifest = manifest,
            paths = SessionPaths(prefix = layout.prefix, logs = layout.logs),
            turnip = turnip,
            display = DEFAULT_DISPLAY,
        )

        if (turnip == null) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "no Turnip driver is installed; the phone's stock Vulkan driver will be used",
            )
        }

        val resolved = LaunchPlan(layout = layout, tree = tree, environment = environment, log = log)
        plan = resolved

        mark(
            STEP_COMPONENTS,
            ProvisionStatus.DONE,
            components.references()
                .filterValues { containerId in it }
                .keys
                .sortedBy { it.type.ordinal }
                .joinToString(" · ") { it.toString() }
                .ifBlank { "nothing referenced" } +
                if (adopted.isEmpty()) "" else " (${adopted.size} newly linked)",
        )

        mark(STEP_FEX, ProvisionStatus.RUNNING)
        val fex = runCatching { installFex(containerId, layout, log) }
            .getOrElse { return failStep(STEP_FEX, it.message ?: "could not install FEX", log) }
        mark(STEP_FEX, ProvisionStatus.DONE, fex)

        mark(STEP_D3D, ProvisionStatus.RUNNING)
        val d3d = runCatching { installD3dLayers(containerId, layout, log) }
            .getOrElse { return failStep(STEP_D3D, it.message ?: "could not install the D3D layers", log) }
        mark(STEP_D3D, if (d3d == null) ProvisionStatus.SKIPPED else ProvisionStatus.DONE, d3d ?: NO_D3D)

        // Empty component list: there is no downloader, so nothing here can hand
        // the provisioner an archive. What it still owns — the directory layout,
        // the registry seed, and the callbacks into `createPrefix` and
        // `applyRegistry` below — is the part that has to happen before a process
        // can start.
        var failed = false
        provisioner.provision(containerId, emptyList(), bootstrap = this).collect { progress ->
            merge(progress.steps)
            if (progress.phase == ProvisionPhase.FAILED) failed = true
        }
        if (failed) {
            _state.update { it.copy(phase = SessionPhase.FAILED) }
            return null
        }

        return resolved
    }

    /** DESIGN.md's Starting and Running states. */
    private suspend fun runDesktop(
        log: SessionLog,
        geometry: DisplayGeometry,
        fpsLimit: Int?,
        fileManager: Boolean,
    ) {
        val current = plan ?: return
        _state.update { it.copy(phase = SessionPhase.STARTING) }

        val request = DisplayRequest(
            display = DEFAULT_DISPLAY,
            geometry = geometry,
            fpsLimit = fpsLimit,
            socketRoot = current.layout.base,
        )
        // What the server answers with, not what was asked for. `DISPLAY` and
        // `WINE_SYSVSHM_SOCKET` name sockets that either got bound or did not,
        // and the desktop is the first process that has to reach them.
        val displayEnvironment: Map<String, String>
        when (val outcome = display.start(request)) {
            is DisplayOutcome.Started -> {
                displayEnvironment = outcome.environment
                log.line(
                    LogSource.VESSEL,
                    LogLevel.INFO,
                    "display ${outcome.environment["DISPLAY"] ?: DEFAULT_DISPLAY} at $geometry" +
                        (fpsLimit?.let { ", capped at $it fps" } ?: ""),
                )
                outcome.environment[SYSVSHM_SOCKET_ENV]?.let {
                    log.line(LogSource.VESSEL, LogLevel.INFO, "MIT-SHM over $it")
                }
            }

            is DisplayOutcome.NotAvailable -> {
                displayEnvironment = emptyMap()
                log.line(LogSource.VESSEL, LogLevel.WARN, outcome.reason)
            }

            is DisplayOutcome.Failed -> {
                fail("The display server could not start: ${outcome.reason}")
                return
            }
        }

        // What the display server answered with becomes part of the plan, not just
        // of this one command: `launchFileManager` starts a guest process later and
        // it needs the same DISPLAY and the same shared-memory socket.
        val running = current.copy(environment = current.environment + displayEnvironment)
        plan = running

        // Started by explorer rather than beside it. `explorer /desktop=` gives a
        // bare background — no icons, no taskbar — so without something on it a
        // new session is an empty rectangle with no way to reach a program.
        val program = when {
            !fileManager -> emptyList()
            running.tree.hasProgram(WINE_FILE_MANAGER) -> FILE_MANAGER_COMMAND
            else -> {
                log.line(
                    LogSource.VESSEL,
                    LogLevel.WARN,
                    "this Wine package has no $WINE_FILE_MANAGER; the desktop will be empty",
                )
                emptyList()
            }
        }

        val spec = ProcessSpec(
            argv = running.tree.desktopArgv(geometry, program),
            environment = running.environment,
            workingDirectory = running.layout.base,
        )
        log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")

        val process = runner.start(spec).getOrElse {
            fail("The Windows desktop could not start: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        desktop = process
        acquireWakeLock()
        _state.update { it.copy(phase = SessionPhase.RUNNING) }

        runner.drain(process) { line -> record(log, line) }
        val code = withContext(Dispatchers.IO) { process.waitFor() }

        // A non-zero exit is Failed rather than Exited even though DESIGN.md's
        // Exited state "states the exit code plainly". It does — but Failed is
        // the state that also carries the last error line, the diagnosis and a
        // Retry, and every one of those is what someone whose game just closed
        // itself actually needs.
        _state.update {
            it.copy(
                phase = if (code == 0) SessionPhase.EXITED else SessionPhase.FAILED,
                exitCode = code,
            )
        }
    }

    // — PrefixBootstrap: the two things that need a live process ---------------

    /**
     * `wineboot --init`, with a `wineserver` of our own started first.
     *
     * `--init` rather than `-u`: this runs once on a prefix that does not exist
     * yet, and it is the call that creates `drive_c`, `system.reg` and the rest.
     */
    override suspend fun createPrefix(layout: ContainerLayout): BootstrapOutcome {
        val current = plan ?: return BootstrapOutcome.NotAvailable(NO_PLAN)
        startWineserver(current)?.let { return it }
        return runTool(current, WINE_BOOT, listOf("--init"), "wineboot")
    }

    override suspend fun applyRegistry(layout: ContainerLayout, regFile: File): BootstrapOutcome {
        val current = plan ?: return BootstrapOutcome.NotAvailable(NO_PLAN)
        if (!regFile.isFile) return BootstrapOutcome.Failed("no ${regFile.name} to apply")
        return runTool(current, WINE_REGEDIT, listOf(regFile.absolutePath), "regedit")
    }

    private suspend fun runTool(
        current: LaunchPlan,
        tool: String,
        arguments: List<String>,
        label: String,
    ): BootstrapOutcome {
        val spec = ProcessSpec(
            argv = current.tree.toolArgv(tool, arguments),
            environment = current.environment,
            workingDirectory = current.layout.base,
        )
        current.log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")
        return when (val result = runner.run(spec) { line -> record(current.log, line) }) {
            is ProcessResult.NotStarted -> BootstrapOutcome.Failed("$label could not start: ${result.reason}")
            is ProcessResult.Exited ->
                if (result.code == 0) {
                    BootstrapOutcome.Applied
                } else {
                    // The diagnosis is the useful half. The bare code is kept
                    // because it is what a bug report quotes.
                    val why = _state.value.diagnosis?.headline ?: _state.value.lastError
                    BootstrapOutcome.Failed(
                        "$label exited with ${result.code}" + if (why == null) "" else " — $why",
                    )
                }
        }
    }

    /**
     * Start the prefix's server, or report why the boot step cannot proceed.
     *
     * `-f` keeps it in the foreground so its output reaches the same pipe as
     * everything else; `-p` makes it persistent, so it does not exit in the gap
     * between `wineboot` finishing and the desktop connecting.
     */
    private fun startWineserver(current: LaunchPlan): BootstrapOutcome? {
        if (wineserver?.isAlive == true) return null
        val spec = ProcessSpec(
            argv = current.tree.serverArgv(listOf("-f", "-p")),
            environment = current.environment,
            workingDirectory = current.layout.base,
        )
        current.log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")
        val process = runner.start(spec).getOrElse {
            return BootstrapOutcome.Failed(
                "wineserver could not start: ${it.message ?: it.javaClass.simpleName}",
            )
        }
        wineserver = process
        scope.launch { runner.drain(process) { line -> record(current.log, line) } }
        return null
    }

    /**
     * `wine winefile.exe`, as a process of its own.
     *
     * Nothing joins this: the drain runs on [scope], which outlives the session
     * job, so a file manager the user leaves open cannot hold up a Stop and its
     * exit cannot be mistaken for the desktop's. It reaches the `vessel` desktop
     * through [PrefixRegistry.fileManagerDesktop] rather than through a second
     * `explorer /desktop=`, which would put a full-size second desktop window over
     * everything already on screen.
     */
    private fun startFileManager(current: LaunchPlan): FileManagerLaunch {
        val spec = ProcessSpec(
            argv = current.tree.fileManagerArgv(),
            environment = current.environment,
            workingDirectory = current.layout.base,
        )
        current.log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")
        val process = runner.start(spec).getOrElse {
            val reason = it.message ?: it.javaClass.simpleName
            current.log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "$WINE_FILE_MANAGER could not start: $reason",
            )
            return FileManagerLaunch.Unavailable(reason)
        }
        fileManagers += process
        scope.launch { runner.drain(process) { line -> record(current.log, line) } }
        return FileManagerLaunch.Started
    }

    // — teardown ---------------------------------------------------------------

    /**
     * Stop everything this session started, in the one order that works.
     *
     * `wineserver -k` first and `destroyForcibly` second. Killing our own child
     * is not enough: every Windows process in the prefix is a child of the
     * *server*, not of us, so destroying the desktop leaves the rest running with
     * nothing on screen and no way to reach them. `-k` terminates all of them.
     *
     * **And `SIGCONT` before any of it.** A `SIGSTOP`ped process cannot be killed
     * by `SIGTERM`: the signal is marked pending and delivered the next time the
     * process is scheduled, which for a stopped process is never. So Stop on a
     * paused session would queue a `destroy()` that never lands, then run
     * `wineserver -k` — itself a fresh client that blocks talking to a server
     * which cannot answer — and burn the eight-second timeout before forcing it,
     * leaving a tree of stopped orphans behind. `SIGKILL` does reach a stopped
     * process, which is exactly why the bug hides: the *forcible* half of teardown
     * appears to work while the polite half silently does nothing. The same shape
     * as the `drain` deadlock in [WineProcessRunner], and worth the two lines to
     * never meet again.
     */
    private suspend fun teardown(log: SessionLog) {
        val current = plan
        signals.withLock { resumeGuest() }
        desktop?.takeIf { it.isAlive }?.destroy()
        // Ahead of `wineserver -k` for the same reason as the desktop: these are
        // our own children as well as the server's, so closing their pipes first
        // lets the drain coroutines finish instead of blocking on a read.
        fileManagers.forEach { it.takeIf(Process::isAlive)?.destroy() }

        if (current != null && wineserver != null) {
            val spec = ProcessSpec(
                argv = current.tree.serverArgv(listOf("-k")),
                environment = current.environment,
                workingDirectory = current.layout.base,
            )
            log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")
            withTimeoutOrNull(KILL_TIMEOUT_MS) {
                runner.run(spec) { line -> record(log, line) }
            } ?: log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "wineserver -k did not finish in ${KILL_TIMEOUT_MS / 1000} s; killing it",
            )
        }

        desktop?.destroyForcibly()
        fileManagers.forEach { it.destroyForcibly() }
        wineserver?.destroyForcibly()
        desktop = null
        fileManagers.clear()
        wineserver = null
        plan = null

        runCatching { display.stop() }
        releaseWakeLock()

        val final = _state.value
        log.finish(
            if (final.phase == SessionPhase.EXITED || stopRequested) {
                SessionExit.OK
            } else {
                SessionExit.CRASHED
            },
            final.exitCode,
        )
        // The container's own record of when it last ran. Written here rather
        // than at launch so a container that failed to start does not claim it.
        if (final.phase == SessionPhase.EXITED || final.phase == SessionPhase.RUNNING) {
            runCatching {
                containers.get(final.containerId.orEmpty())?.let {
                    containers.save(it.copy(lastRun = final.startedAt))
                }
            }
        }
        if (final.active) {
            // Cancelled mid-run: the phase never advanced on its own.
            _state.update { it.copy(phase = SessionPhase.EXITED, exitCode = it.exitCode ?: 0) }
        }
    }

    // — output -----------------------------------------------------------------

    /**
     * One line of process output: into the log, and into whatever the screen
     * needs from it.
     *
     * Parsed once. [SessionLog.raw] would parse it again, and this runs on the
     * pipe-drain thread of a process that can emit thousands of lines a second.
     */
    private fun record(log: SessionLog, raw: String) {
        val parsed = parseSessionLogLine(raw)
        log.line(parsed.source, parsed.level, parsed.text)
        if (parsed.text.isBlank()) return

        val diagnosis = diagnoseSessionLine(raw)
        val error = if (parsed.level == LogLevel.ERROR) parsed.text else null

        // Only the checklist states draw the running line, and a state update per
        // line at full rate is a recomposition per line. A diagnosis or an error
        // is published immediately; ordinary progress waits its turn.
        val now = System.currentTimeMillis()
        val throttled = now - lastPublishedAt < PUBLISH_INTERVAL_MS
        if (diagnosis == null && error == null) {
            if (throttled || _state.value.phase == SessionPhase.RUNNING) return
        }
        lastPublishedAt = now
        _state.update {
            it.copy(
                lastLine = parsed.text,
                lastError = error ?: it.lastError,
                diagnosis = it.diagnosis ?: diagnosis,
            )
        }
    }

    // — the prefix's Windows side ---------------------------------------------

    /**
     * Put FEX in the prefix, and refuse to launch without it.
     *
     * **FEX is not optional here, and not only for x86 programs.** The registry
     * seed points `HKLM\Software\Microsoft\Wow64\amd64` at `libarm64ecfex.dll`,
     * and `load_arm64ec_module()` loads whatever that names during *every*
     * process's ntdll init — a native ARM64 Windows program included. With the
     * key written and the DLL absent, the prefix does not run anything at all, so
     * failing the step here is strictly better than booting a prefix that is
     * already poisoned.
     *
     * `libwow64fex.dll` is a warning rather than a failure: `get_cpu_dll_name()`
     * is only consulted when a 32-bit process starts, so its absence costs the
     * WoW64 path and nothing else. Both go in `system32` — the 64-bit directory —
     * because that is where `load_64bit_module` resolves the CPU DLL from.
     */
    private suspend fun installFex(
        containerId: String,
        layout: ContainerLayout,
        log: SessionLog,
    ): String = withContext(Dispatchers.IO) {
        val source = components.directoryFor(containerId, ComponentType.FEXCORE)
            ?: error(
                "FEX is not installed. Wine is told to load ${ARM64EC_FEX} for every " +
                    "process in the prefix, so nothing can start without it.",
            )
        if (!File(source, ARM64EC_FEX).isFile) {
            error("The installed FEX package has no $ARM64EC_FEX in it.")
        }
        if (!File(source, WOW64_FEX).isFile) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "no $WOW64_FEX in the FEX package; 32-bit x86 programs will not start",
            )
        }
        val deployed = copyWindowsPayload(source, layout)
        "$ARM64EC_FEX — ${deployed.describe()}"
    }

    /**
     * Put DXVK, vkd3d, D8VK and Zink in the prefix. Null when none is installed.
     *
     * Best effort, unlike FEX: a container with no D3D layer runs anything that
     * does not ask for Direct3D. It is still worth a warning, because
     * `WINEDLLOVERRIDES=…=n` says *native only* — with nothing native to find,
     * a D3D program fails to load its own `d3d11.dll` rather than quietly
     * falling back to wined3d.
     */
    private suspend fun installD3dLayers(
        containerId: String,
        layout: ContainerLayout,
        log: SessionLog,
    ): String? = withContext(Dispatchers.IO) {
        val installed = mutableListOf<String>()
        var total = Deployed()
        for (type in D3D_COMPONENTS) {
            val source = components.directoryFor(containerId, type) ?: continue
            installed += type.label
            total += copyWindowsPayload(source, layout)
        }
        if (installed.isEmpty()) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "no D3D or OpenGL layer is installed; Direct3D programs will fail to load " +
                    "their DLLs because WINEDLLOVERRIDES asks for native ones",
            )
            return@withContext null
        }
        "${installed.joinToString(", ")} — ${total.describe()}"
    }

    /** What one package's payload cost. */
    private data class Deployed(val copied: Int = 0, val present: Int = 0) {
        operator fun plus(other: Deployed) = Deployed(copied + other.copied, present + other.present)

        fun describe(): String = when {
            copied == 0 && present == 0 -> "nothing to copy"
            copied == 0 -> "$present file(s) already in place"
            else -> "$copied file(s) copied into the prefix"
        }
    }

    /**
     * Mirror one package's Windows payload into the prefix.
     *
     * `WINEDLLOVERRIDES=…=n` tells Wine to prefer the native DLL; it does not tell
     * it where one is. Native DLLs are found in `C:\windows\system32`, so the
     * payload has to be *in* the prefix — the shared store is where it is kept,
     * not where Wine looks.
     *
     * **Copied, not symlinked**, which is the expensive choice on purpose.
     * `wineboot` writes into `system32`, and a symlink would let it write through
     * into a shared-store directory every other container on that version reads.
     * Around 35 MB per container, against the 912 MB of Wine that stays shared.
     *
     * The rule is the package layout and nothing per component: `system32/` and
     * `syswow64/` mirror into their namesakes, and a `.dll` at the package root
     * lands in `system32`. Import libraries (`.dll.a`) are link-time artifacts
     * and are skipped, as is anything already there at the same size.
     */
    private fun copyWindowsPayload(source: File, layout: ContainerLayout): Deployed {
        val windows = File(layout.prefix, DRIVE_C_WINDOWS)
        val system32 = File(windows, SYSTEM32)
        val groups = listOf(
            source.listFiles().orEmpty() to system32,
            File(source, SYSTEM32).listFiles().orEmpty() to system32,
            File(source, SYSWOW64).listFiles().orEmpty() to File(windows, SYSWOW64),
        )

        var copied = 0
        var present = 0
        for ((files, destination) in groups) {
            val dlls = files.filter { it.isFile && it.name.endsWith(DLL_SUFFIX) }
            if (dlls.isEmpty()) continue
            if (!destination.isDirectory && !destination.mkdirs()) {
                error("could not create ${destination.path}")
            }
            for (file in dlls) {
                val target = File(destination, file.name)
                if (target.isFile && target.length() == file.length()) {
                    present++
                    continue
                }
                file.copyTo(target, overwrite = true)
                copied++
            }
        }
        return Deployed(copied, present)
    }

    // — resolution helpers -----------------------------------------------------

    /**
     * The Turnip package this container references, with the library name the
     * package's own `meta.json` declares.
     *
     * All three [TurnipDriver] fields or nothing: `AdrenotoolsManager` in the
     * Winlator lineage falls through without setting `ADRENOTOOLS_*` and without
     * logging when a driver does not resolve, and the stock Qualcomm driver
     * quietly takes over. Returning null here makes that state say so instead.
     */
    private suspend fun turnipDriver(containerId: String): TurnipDriver? {
        val directory = components.directoryFor(containerId, ComponentType.TURNIP) ?: return null
        val library = withContext(Dispatchers.IO) {
            runCatching {
                val meta = File(directory, ADRENOTOOLS_META)
                if (!meta.isFile) return@runCatching null
                json.decodeFromString(AdrenotoolsMeta.serializer(), meta.readText()).libraryName
            }.getOrNull()
        } ?: return null
        if (!File(directory, library).isFile) return null
        return TurnipDriver(
            driverDir = directory,
            libraryName = library,
            hooksDir = File(appContext.applicationInfo.nativeLibraryDir),
        )
    }

    private fun text(profile: ContainerProfile, manifest: ParamManifest?, key: String): String? =
        (profile.params[key] as? ParamValue.Text)?.value
            ?: (manifest?.spec(key)?.defaultValue() as? ParamValue.Text)?.value

    /**
     * A boolean param, defaulting to true when nothing can answer.
     *
     * The fallback is not neutral and is chosen for the one caller: with no
     * manifest to read, an empty desktop is the failure this feature exists to
     * remove, and a file manager nobody wanted is closable.
     */
    private fun flag(profile: ContainerProfile, manifest: ParamManifest?, key: String): Boolean =
        (profile.params[key] as? ParamValue.Flag)?.value
            ?: (manifest?.spec(key)?.defaultValue() as? ParamValue.Flag)?.value
            ?: true

    // — checklist plumbing -----------------------------------------------------

    /**
     * The Preparing rows, all pending, before anything runs.
     *
     * [ContainerProvisioner.plan]'s last step says "Ready to start" and is
     * dropped, because reaching Starting is what says that and a row claiming it
     * one frame earlier is a row nobody reads.
     */
    private fun checklist(): List<ProvisionStep> =
        listOf(
            ProvisionStep(STEP_COMPONENTS, "Resolve components"),
            ProvisionStep(STEP_FEX, "Install FEX"),
            ProvisionStep(STEP_D3D, "Install D3D layers"),
        ) + provisioner.plan(emptyList()).filterNot { it.id == ContainerProvisioner.STEP_READY }

    private fun mark(id: String, status: ProvisionStatus, detail: String? = null) {
        _state.update { state ->
            state.copy(
                steps = state.steps.map {
                    if (it.id == id) it.copy(status = status, detail = detail) else it
                },
            )
        }
    }

    /** Fold the provisioner's own rows into ours, leaving our two alone. */
    private fun merge(steps: List<ProvisionStep>) {
        val byId = steps.associateBy { it.id }
        _state.update { state -> state.copy(steps = state.steps.map { byId[it.id] ?: it }) }
    }

    private fun failStep(id: String, reason: String, log: SessionLog): LaunchPlan? {
        log.line(LogSource.VESSEL, LogLevel.ERROR, reason)
        mark(id, ProvisionStatus.FAILED, reason)
        _state.update { it.copy(phase = SessionPhase.FAILED, failure = reason) }
        return null
    }

    private fun fail(reason: String) {
        _state.update { it.copy(phase = SessionPhase.FAILED, failure = reason) }
    }

    // — residency --------------------------------------------------------------

    /**
     * A partial wake lock for as long as a process is alive.
     *
     * The foreground service stops the process being reclaimed; it does not stop
     * the CPU idling. Without this a session pauses the moment the screen goes
     * off, which for a program mid-install looks like a hang.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = appContext.getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { runCatching { it.acquire() } }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    /** What one running session resolved to. Immutable for its whole life. */
    private data class LaunchPlan(
        val layout: ContainerLayout,
        val tree: WineTree,
        /** The launcher and logging environments, already merged. */
        val environment: Map<String, String>,
        /** Carried so [PrefixBootstrap]'s callbacks can write to the same file. */
        val log: SessionLog,
    )

    private companion object {
        const val STEP_COMPONENTS = "session:components"
        const val STEP_FEX = "session:fex"
        const val STEP_D3D = "session:d3d"

        const val NO_PLAN = "the session was torn down before the prefix could be booted"
        const val NO_D3D = "No D3D or OpenGL layer is installed"

        const val DRIVE_C_WINDOWS = "drive_c/windows"
        const val SYSTEM32 = "system32"
        const val SYSWOW64 = "syswow64"
        const val DLL_SUFFIX = ".dll"

        /** Named by [app.vessel.core.PrefixRegistry.arm64ecEmulator]; required. */
        const val ARM64EC_FEX = "libarm64ecfex.dll"

        /** Named by [app.vessel.core.PrefixRegistry.x86Emulator]; WoW64 only. */
        const val WOW64_FEX = "libwow64fex.dll"

        /** `libadrenotools` reads this file; `build/turnip.sh` writes it. */
        const val ADRENOTOOLS_META = "meta.json"

        /** Long enough for a prefix with processes to shut down, short enough to feel like Stop. */
        const val KILL_TIMEOUT_MS = 8_000L

        const val PUBLISH_INTERVAL_MS = 150L

        const val WAKE_LOCK_TAG = "vessel:session"

        /**
         * The graphics translation layers, all optional.
         *
         * FEX is deliberately not in this list — it is mandatory and has a step
         * of its own, because a missing FEX stops the prefix running anything at
         * all rather than only its Direct3D.
         */
        val D3D_COMPONENTS = listOf(
            ComponentType.DXVK,
            ComponentType.VKD3D,
            ComponentType.D8VK,
            ComponentType.OPENGL,
        )
    }
}

/** The fields of a Turnip package's `meta.json` this app acts on. */
@Serializable
private data class AdrenotoolsMeta(val libraryName: String? = null)
