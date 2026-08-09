package app.vessel.data

import android.content.Context
import android.os.PowerManager
import app.vessel.core.GuestUnits
import app.vessel.core.ComponentType
import app.vessel.core.ContainerProfile
import app.vessel.core.DEFAULT_DISPLAY
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
import app.vessel.core.WINE_REGEDIT
import app.vessel.core.BOOTSTRAP_SESSION_ENV
import app.vessel.core.WINE_UNIX_ARCH
import app.vessel.core.WineTree
import app.vessel.core.desktopArgv
import app.vessel.core.diagnoseSessionLine
import app.vessel.core.programArgv
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import app.vessel.core.parseFpsLimit
import app.vessel.core.parseGeometry
import app.vessel.core.parseSessionLogLine
import app.vessel.core.serverArgv
import app.vessel.core.sessionEnvironment
import app.vessel.core.toolArgv
import app.vessel.core.wineLauncherEnvironment
import app.vessel.core.deleteTree
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
 * What [SessionRuntime.launchProgram] did.
 *
 * No `AlreadyRunning` case. A second copy of a program the user asked for twice
 * is their business, and refusing it would be this layer having an opinion it
 * has no basis for.
 */
sealed interface ProgramLaunch {
    data object Started : ProgramLaunch

    /** Nothing was started, and this is the sentence to show. */
    data class Unavailable(val reason: String) : ProgramLaunch
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

    /**
     * The container [PrefixBootstrap]'s callbacks are working on.
     *
     * Same reasoning as [plan], and the same guarantee behind it: those two
     * methods take a [ContainerLayout] and nothing else, while the component store
     * is keyed by container id, so the id has to arrive out of band. [lifecycle]
     * makes one field safe — one session, and the provisioner is only ever driven
     * from inside it.
     */
    @Volatile
    private var provisioning: String? = null

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
    /**
     * Programs the launcher started into a running session.
     *
     * Held only so the dead ones can be reaped and so teardown knows they
     * existed. Killing them individually is not this list's job — every Windows
     * process in the prefix is a child of `wineserver`, not of us, and
     * `wineserver -k` is what ends them. See the teardown note.
     */
    private val launched = mutableListOf<Process>()

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

    /**
     * Thread id to program name, for the session that is running.
     *
     * Replaced per session rather than cleared: a unit id means nothing
     * across two runs of `wineserver`, and carrying one over would label
     * a new process with a dead one's name.
     */
    private var units = GuestUnits()

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
     * Start one more program inside the session that is already running.
     *
     * The generalisation of [launchFileManager], which was this with `winefile`
     * hard-coded. It is what makes the launcher a launcher: `start` takes a
     * container and nothing else, so before this there was no way to ask a live
     * prefix to run anything.
     *
     * **Deciding *what* to run is not done here.** `ui/shell/Launchable.kt`
     * already maps a file to the program that opens it — a `.bat` to `cmd.exe
     * /c`, an `.msi` to `msiexec.exe /i`, a `.ps1` to a refusal — and has the
     * tests. What arrives here is an executable and its arguments.
     *
     * @param workingDirectory the Unix directory to start in, which is how the
     *   guest's working directory is set: Wine maps the process's cwd onto a
     *   Windows path, so a directory under `drive_c` becomes the matching `C:`
     *   path with no extra argument and no quoting. Null means the container
     *   root, which Wine maps to `Z:` — correct only when the caller genuinely
     *   has no preference.
     */
    suspend fun launchProgram(
        program: String,
        arguments: List<String> = emptyList(),
        workingDirectory: File? = null,
    ): ProgramLaunch = lifecycle.withLock {
        val current = plan
        if (current == null || _state.value.phase != SessionPhase.RUNNING) {
            return@withLock ProgramLaunch.Unavailable(
                "there is no running desktop to start it on",
            )
        }
        // Reaped rather than tracked forever: a session left open all afternoon
        // would otherwise hold a Process object for every program ever started.
        launched.removeAll { !it.isAlive }

        // **Through `explorer`, not straight to the program.** A bare
        // `wine prog.exe` starts a process with no desktop, so its window is a
        // rootless X window — and the vendored server is a compositor with no
        // window manager in it, so nothing draws a caption. That is why a
        // console came up with no title bar and therefore no minimise, maximise,
        // close, drag or resize.
        //
        // Seeding `HKCU\Software\Wine\Explorer` was necessary and not
        // sufficient: those keys are read by `explorer.exe`, which decides the
        // desktop for programs *it* starts, and nothing was starting it.
        //
        // The desktop named here is the one the session is already running, and
        // Wine desktops are named objects — so this attaches to it rather than
        // creating the second full-size desktop window that would otherwise sit
        // over the first. Same name, same geometry, one desktop.
        val spec = ProcessSpec(
            argv = current.tree.desktopArgv(
                geometry = current.geometry ?: return@withLock ProgramLaunch.Unavailable(
                    "the desktop's size is not known yet",
                ),
                // The program and its arguments only — `desktopArgv` supplies
                // the loader and `explorer` itself in front of them.
                program = listOf(program) + arguments,
            ),
            environment = current.environment,
            workingDirectory = workingDirectory?.takeIf { it.isDirectory } ?: current.layout.base,
        )
        current.log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")

        val process = runner.start(spec).getOrElse {
            val reason = it.message ?: it.javaClass.simpleName
            current.log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "$program could not start: $reason",
            )
            return@withLock ProgramLaunch.Unavailable(reason)
        }
        launched += process
        // Its output belongs in the session log like everything else: a program
        // that starts and immediately dies is diagnosed from the lines it wrote,
        // and dropping them would make the launcher the one place in this app
        // where a failure is silent.
        scope.launch { runner.drain(process) { line -> record(current.log, line) } }
        ProgramLaunch.Started
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

        units = GuestUnits()
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

        // **Anything of the guest's still alive is an orphan, and this is the one
        // moment that is certainly true.** Nothing of ours is running yet, so a
        // Wine process found now belongs to a session that ended without
        // teardown — `adb install -r`, a force-stop, a crash. Those are ordinary
        // children of this app, and when the app dies without stopping them they
        // are reparented to init and keep going. Watched on the device after a
        // reinstall: `explorer.exe /desktop`, `wscript.exe` and `winemine` from a
        // container that had since been **deleted**, still serving a prefix that
        // no longer existed.
        //
        // They are not harmless. They hold the abstract X socket and the
        // wineserver socket, so the session about to start either fails to bind
        // or quietly attaches to the wrong server — and the second is far worse,
        // because it looks like the new container behaving strangely.
        //
        // Said out loud rather than swept silently: a line here is evidence that
        // teardown was skipped, and if it appears on every launch then something
        // is wrong with stopping rather than with starting.
        guest.killOrphans().forEach {
            log.line(LogSource.VESSEL, LogLevel.WARN, "killed an orphan from an earlier session: $it")
        }

        try {
            prepare(containerId, profile, manifest, log) ?: return
            runDesktop(log, geometry, fpsLimit)
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
     * **Nothing is copied into the prefix here, and that is the fix.** The FEX and
     * D3D payloads used to be deployed at this point, before `wineboot` had ever
     * run, on the reasoning that [PrefixRegistry.arm64ecEmulator] makes
     * `libarm64ecfex.dll` a load-time dependency of every process in the prefix.
     * The dependency is real; the conclusion was one step too far. The registry
     * key does not exist until `regedit` applies the seed, and `regedit` cannot
     * run until there is a prefix — so the window that argument was closing is
     * between the *seed* and the boot, not between the *launch* and the boot.
     *
     * Deploying that early is what `tools/device-graphics.sh` warns against in so
     * many words ("after the wineboot passes, never before"): `syswow64` does not
     * exist until the 32-bit pass has created it, so the 32-bit half of DXVK was
     * being written into a directory Wine had not made yet.
     *
     * The stronger half of that warning — that `wine.inf` puts its builtins back
     * over the top — turns out **not** to happen, and it is worth writing down
     * which part is measured. `create_dest_file` (`dlls/setupapi/fakedll.c`)
     * refuses to overwrite a file that is not one of Wine's own placeholders, so a
     * real DXVK `d3d11.dll` survives a `wineboot --update`; checked on the device,
     * where `system32/d3d11.dll` kept its 4 087 808 bytes and its original
     * timestamp through a pass that created `syswow64/kernel32.dll` beside it. The
     * ordering here therefore removes a dependency on Wine's behaviour rather than
     * fixing a live bug, and the live bug it *does* fix is `syswow64` not existing.
     *
     * So the order now lives in [createPrefix] and [applyRegistry], which are the
     * only two places that know where `wineboot` is in its run:
     *
     * ```
     * wineserver -f -p
     * wineboot --init                 creates system32 and the hives
     * FEX -> system32                 before the seed names it, after the boot
     * regedit prefix-seed.reg         and read the hive back to prove it landed
     * wineboot --update  (x2)         builds syswow64, needs libwow64fex.dll
     * DXVK / vkd3d / Zink -> prefix   after the last wine.inf pass
     * ```
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

        // Here rather than in provisioning, because provisioning skips the whole
        // layout step for a container whose directories already exist — so every
        // container made before the caches were part of the layout would keep
        // running with three environment variables pointing at nothing. Cheap,
        // idempotent, and the alternative is recompiling every pipeline on every
        // launch.
        layout.cacheDirectories.forEach { if (!it.isDirectory) it.mkdirs() }

        val turnip = turnipDriver(containerId)
        val environment = wineLauncherEnvironment(
            tree = tree,
            scratch = SessionScratch(home = layout.base, tmp = layout.tmp),
            // Only when there is a driver to load. See wineLauncherEnvironment:
            // without this the adrenotools dlopen fails on libc++_shared.so and
            // the stock Qualcomm driver answers, silently.
            hooksDir = turnip?.hooksDir,
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

        val resolved = LaunchPlan(
            layout = layout,
            tree = tree,
            environment = environment,
            log = log,
        )
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

        // Which container the prefix callbacks are working on. They are handed a
        // [ContainerLayout] and nothing else — the interface predates there being
        // anything to launch — and the component store is keyed by container id.
        provisioning = containerId

        // Empty component list: components are installed once per device, either
        // from the packages bundled in the APK ([BundledComponents]) or by a
        // download, and both write straight into the shared store. What the
        // provisioner still owns — the directory layout, the registry seed, and
        // the callbacks into `createPrefix` and `applyRegistry` below — is the
        // part that has to happen per container, before a process can start.
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
        // of this one command: `launchProgram` starts a guest process later and it
        // needs the same DISPLAY and the same shared-memory socket.
        val running = current.copy(environment = current.environment + displayEnvironment)
        plan = running

        // `explorer /desktop=` and nothing on it. The desktop is deliberately a
        // bare background: Vessel's own taskbar and launcher are drawn on the
        // Android side, over this surface, so starting a Windows program here to
        // give the user somewhere to click would put a second, worse shell
        // underneath the real one.
        running.geometry = geometry
        val spec = ProcessSpec(
            argv = running.tree.desktopArgv(geometry, emptyList()),
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
     * Pass one: `wineboot --init`, then FEX into `system32`.
     *
     * **A non-zero exit from `wineboot --init` is expected and is not a failure.**
     * Every PE program in this build is ARM64EC, and an ARM64EC process cannot
     * resolve a single import until `HKLM\Software\Microsoft\Wow64\amd64` names an
     * emulator — which is in the registry seed, which cannot be applied until a
     * prefix exists. So the first boot lays down `system32` and the hives, gets as
     * far as `services.exe`, and then dies on `start.exe` with
     *
     * ```
     * err:module:import_dll Library shell32.dll … not found
     * err:module:loader_init Importing dlls for … start.exe failed, status c0000135
     * wineboot exited with 53
     * ```
     *
     * What *is* believed is the hive on disk: a run that did not leave a
     * `system.reg` worth the name did not build a prefix, whatever it exited with.
     *
     * **FEX is deployed here, between the boot and the seed, and that is the one
     * ordering constraint that has not moved.** `load_arm64ec_module()` runs in
     * `LdrInitializeThunk` before `kernel32` and `NtTerminateProcess`es the process
     * when the DLL the key names is missing, so the files have to be in place
     * before [applyRegistry] writes the key — and they cannot be in place any
     * earlier than this, because `system32` is what `wineboot --init` creates.
     * This is `tools/device-graphics.sh` step 2, in the same order and for the same
     * reason.
     */
    override suspend fun createPrefix(layout: ContainerLayout): BootstrapOutcome {
        val current = plan ?: return BootstrapOutcome.NotAvailable(NO_PLAN)
        val container = provisioning ?: return BootstrapOutcome.NotAvailable(NO_PLAN)
        startWineserver(current)?.let { return it }

        if (hiveExists(layout)) {
            bootProgress("prefix already initialised")
        } else {
            bootProgress("wineboot --init")
            val first = runTool(current, WINE_BOOT, listOf("--init"), "wineboot", BOOT_TIMEOUT_MS)
            if (first is BootstrapOutcome.Failed) {
                current.log.line(
                    LogSource.VESSEL,
                    LogLevel.INFO,
                    "first-pass wineboot did not finish cleanly (${first.reason}); this is " +
                        "expected before the emulator is registered — continuing to the seed",
                )
            }
            // The server still holds the hive in memory when `wineboot` returns,
            // so this is what makes the check below read the real answer.
            flushHive(current)
            if (!hiveExists(layout)) {
                return BootstrapOutcome.Failed(
                    "wineboot --init left no usable registry: ${hiveSize(layout)} bytes in " +
                        "prefix/$SYSTEM_REG, and a fresh prefix is over " +
                        "${MIN_HIVE_BYTES / 1024} KB",
                )
            }
        }

        mark(STEP_FEX, ProvisionStatus.RUNNING)
        val fex = runCatching { installFex(container, layout, current.log) }
            .getOrElse {
                val reason = it.message ?: "could not install FEX"
                mark(STEP_FEX, ProvisionStatus.FAILED, reason)
                return BootstrapOutcome.Failed(reason)
            }
        mark(STEP_FEX, ProvisionStatus.DONE, fex)
        return BootstrapOutcome.Applied
    }

    /**
     * Pass two: apply the seed, **read it back**, build the 32-bit world, and put
     * the graphics layers in last.
     *
     * ## Why the seed used to reach `prefix-seed.reg` and never the hive
     *
     * Not `regedit`. `regedit` was never reached at all, and the reason is one
     * line: the step before it ran `wineserver -w`, which does not mean "flush",
     * it means **wait for every other `wineserver` to exit** (`wait_for_lock`,
     * `server/request.c`, an `F_SETLKW` on the master socket lock). This app
     * starts its own server `-f -p` because Wine cannot exec its own — see the
     * class comment — and `-p` is *persistent*, so there is no idle timeout and
     * the server never exits. `wineserver -w` therefore blocked forever,
     * `createPrefix` never returned, and provisioning sat in PREPARING until the
     * process was killed, with `registrySeedVersion` already written.
     *
     * `tools/device-session.sh` uses `wineserver -w` perfectly happily because it
     * never starts a server of its own: `wineboot` auto-starts a transient one
     * that exits three seconds after it goes idle, and the wait then returns. The
     * technique is correct there and is unusable here, which is exactly the shape
     * of bug that copying a working script into an app produces.
     *
     * So the hive is flushed by **ending the server and starting a new one**
     * ([flushHive]) — `flush_registry()` runs on the server's way out, which is
     * measured to work on the device: `grep libarm64ecfex system.reg` finds
     * nothing while the server is up and finds it immediately after `wineserver
     * -k`.
     *
     * ## And then it is checked
     *
     * `regedit` exits 0 whether or not a key landed, so the exit code is not
     * evidence. The hive is read back and the step fails loudly naming the DLLs
     * that are missing, which is what `tools/device-session.sh` has done all along
     * ("a silently unapplied key shows up much later as `xtajit64.dll not found`
     * and looks like a Wine bug").
     *
     * ## Two `--update` passes
     *
     * `.update-timestamp` is deleted before each, because `wineboot` compares that
     * stamp against `wine.inf` and skips its whole run when they match — which,
     * right after pass one, they do. Two passes rather than one because one forced
     * pass measurably leaves `syswow64` empty and the second fills it; what the
     * first establishes that the second needs is still not understood, and the
     * honest thing is to run both and say so.
     */
    override suspend fun applyRegistry(layout: ContainerLayout, regFile: File): BootstrapOutcome {
        val current = plan ?: return BootstrapOutcome.NotAvailable(NO_PLAN)
        val container = provisioning ?: return BootstrapOutcome.NotAvailable(NO_PLAN)
        if (!regFile.isFile) return BootstrapOutcome.Failed("no ${regFile.name} to apply")

        // The whole of the rest of this method is idempotent but slow — three Wine
        // process lifetimes and two full `wine.inf` passes, minutes on this phone —
        // so a prefix that already carries its result skips it. The condition is
        // read from the prefix itself rather than from `provisioned.json`: a
        // recorded claim is exactly what was wrong before.
        // **The skip is about the emulator keys, and it must not swallow a seed
        // change.** `bootstrapped` asks whether the two DLL values Wine cannot
        // run without are in the hive. They are, for every prefix that has ever
        // booted — so once this returned true, `regedit` never ran again and no
        // later seed reached an existing container. Seeds 9, 10 and 11 were all
        // written to `prefix-seed.reg`, recorded in `provisioned.json`, and
        // never applied; the hive was still carrying seed 8's values. It looked
        // like "the metrics only work on a fresh container", which is what I
        // wrote down, and it was this instead.
        //
        // The seed's own version decides now. It is read from the prefix rather
        // than from `provisioned.json` for the reason the comment below already
        // gives — a recorded claim is exactly what was wrong before — so the
        // marker is a value inside the hive, written by the seed itself.
        //
        // What to look for comes out of `regFile` rather than out of
        // `PrefixRegistry`, and that is the whole point of seed 16's stamp: the
        // file about to be applied is the only thing that knows what applying it
        // would write. A prefix that gained a drive renders a different seed at
        // the same version, and asking the registry object would answer for a
        // container other than this one. Null means a `.reg` from before stamps
        // existed, which is a reason to apply rather than a reason to skip.
        val expected = runCatching { PrefixRegistry.stampOf(regFile.readText()) }.getOrNull()
        val seedApplied = expected != null && hiveText(layout).contains(expected)
        if (bootstrapped(layout) && seedApplied) {
            bootProgress("emulator keys applied · syswow64 ${wow64Entries(layout)} entries")
        } else {
            bootProgress("regedit ${regFile.name}")
            val applied = runTool(
                current,
                WINE_REGEDIT,
                listOf(regFile.absolutePath),
                "regedit",
                REGEDIT_TIMEOUT_MS,
            )
            if (applied is BootstrapOutcome.Failed) return applied
            flushHive(current)

            val missing = PrefixRegistry.missingFromHive(hiveText(layout))
            if (missing.isNotEmpty()) {
                return BootstrapOutcome.Failed(
                    "the registry seed did not reach the hive: prefix/$SYSTEM_REG still does " +
                        "not name ${missing.joinToString(" or ")}, so no translated program " +
                        "can start in this prefix",
                )
            }
            current.log.line(
                LogSource.VESSEL,
                LogLevel.INFO,
                "emulator keys are in $SYSTEM_REG: ${PrefixRegistry.requiredHiveValues.joinToString(", ")}",
            )

            for (pass in 1..WINEBOOT_UPDATE_PASSES) {
                bootProgress("wineboot --update, pass $pass of $WINEBOOT_UPDATE_PASSES")
                runCatching { File(layout.prefix, UPDATE_TIMESTAMP).delete() }
                val update = runTool(
                    current,
                    WINE_BOOT,
                    listOf("--update"),
                    "wineboot --update (pass $pass)",
                    BOOT_TIMEOUT_MS,
                )
                if (update is BootstrapOutcome.Failed) return update
            }
            flushHive(current)

            // **The seed goes on again, and this is not belt and braces.**
            // `wineboot --update` re-runs `wine.inf`, and `wine.inf` writes keys
            // — so anything the seed *deleted* comes straight back, and anything
            // it changed that `wine.inf` also sets is overwritten. Seed 17 is
            // where that stopped being theoretical: the unix root's shell
            // namespace entry was deleted by the first `regedit`, recreated by
            // the boot below it, and `/` was still on the desktop with the
            // registry saying it had been removed.
            //
            // Cheap enough not to think about — a 2 KB file and one Wine process
            // against two full `wine.inf` passes — and it runs *after* the last
            // thing that can undo it, which the first application cannot.
            bootProgress("regedit ${regFile.name}, after wine.inf")
            val reapplied = runTool(
                current,
                WINE_REGEDIT,
                listOf(regFile.absolutePath),
                "regedit (after wine.inf)",
                REGEDIT_TIMEOUT_MS,
            )
            if (reapplied is BootstrapOutcome.Failed) return reapplied
            flushHive(current)

            // **A warning and not a failure, deliberately.** The 32-bit world is
            // one of three translation paths; the 64-bit desktop is the product.
            // Refusing to start a container because `syswow64` came up short would
            // turn a missing capability into a total failure, and the user would
            // have nothing at all instead of everything but x86-32. It is said out
            // loud at ERROR level and carried in the step's own detail, so it
            // cannot be mistaken for working.
            val entries = wow64Entries(layout)
            if (entries < MIN_WOW64_ENTRIES) {
                current.log.line(
                    LogSource.VESSEL,
                    LogLevel.ERROR,
                    "the 32-bit world did not initialise: syswow64 has $entries entries after " +
                        "$WINEBOOT_UPDATE_PASSES wineboot --update passes. 64-bit and ARM64 " +
                        "programs are unaffected; 32-bit x86 ones cannot load kernel32.",
                )
            }
            bootProgress("syswow64 $entries entries")
        }

        // Last, and after every `wine.inf` pass. See the note on [prepare].
        mark(STEP_D3D, ProvisionStatus.RUNNING)
        val d3d = runCatching { installD3dLayers(container, layout, current.log) }
            .getOrElse {
                val reason = it.message ?: "could not install the D3D layers"
                mark(STEP_D3D, ProvisionStatus.FAILED, reason)
                return BootstrapOutcome.Failed(reason)
            }
        mark(STEP_D3D, if (d3d == null) ProvisionStatus.SKIPPED else ProvisionStatus.DONE, d3d ?: NO_D3D)

        // Tools last, and folded into the D3D step's line rather than given a
        // row of its own: a container without Git is not missing anything the
        // desktop needs, so a permanently SKIPPED row would be a checklist
        // entry that means nothing on most launches.
        runCatching { installTools(container, layout, current.log) }
            .onFailure { current.log.line(LogSource.VESSEL, LogLevel.WARN, "tools: ${it.message}") }

        return BootstrapOutcome.Applied
    }

    // — reading the prefix back ------------------------------------------------

    private fun hive(layout: ContainerLayout): File = File(layout.prefix, SYSTEM_REG)

    private fun hiveSize(layout: ContainerLayout): Long = hive(layout).length()

    /**
     * Whether `wineboot --init` has produced a registry at all.
     *
     * A size threshold rather than existence: the server creates the file early
     * and a prefix that died during `wine.inf` leaves a short one. A finished
     * prefix's `system.reg` is several megabytes; the reference scripts use the
     * same 100 KB floor.
     */
    private fun hiveExists(layout: ContainerLayout): Boolean = hiveSize(layout) > MIN_HIVE_BYTES

    /**
     * The hive as text, or empty when there is none.
     *
     * Several megabytes read into memory once per launch. Streaming it would be
     * tidier and is not worth the loss of [PrefixRegistry.missingFromHive] being a
     * pure function over a string that a unit test can write by hand.
     */
    private fun hiveText(layout: ContainerLayout): String =
        runCatching { hive(layout).readText(Charsets.UTF_8) }.getOrDefault("")

    private fun wow64Entries(layout: ContainerLayout): Int =
        File(File(layout.prefix, DRIVE_C_WINDOWS), SYSWOW64).list()?.size ?: 0

    /** Everything [applyRegistry] would do, already true of this prefix. */
    private fun bootstrapped(layout: ContainerLayout): Boolean =
        hiveExists(layout) &&
            PrefixRegistry.missingFromHive(hiveText(layout)).isEmpty() &&
            wow64Entries(layout) >= MIN_WOW64_ENTRIES

    /**
     * Force the hive to disk, by ending the session's server and starting another.
     *
     * `wineserver -w` is the obvious call and it cannot be used — see
     * [applyRegistry] for why at length. `flush_registry()` runs on the server's
     * way out, so `-k` is what actually writes `system.reg`, and a new `-f -p`
     * takes its place immediately because every later step needs a server that
     * Wine itself is not allowed to start.
     */
    private suspend fun flushHive(current: LaunchPlan) {
        stopWineserver(current)
        startWineserver(current)?.let {
            current.log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "could not restart wineserver after flushing the registry: " +
                    (it as? BootstrapOutcome.Failed)?.reason.orEmpty(),
            )
        }
    }

    /**
     * `wineserver -k`, and then reap our own child.
     *
     * Both halves. `-k` signals the server to shut down and write its hives, but
     * the server is also a [Process] we started, and leaving it unreaped leaves a
     * zombie plus a drain coroutine parked on a pipe with no writer left to close
     * it.
     */
    private suspend fun stopWineserver(current: LaunchPlan) {
        val server = wineserver ?: return
        val spec = ProcessSpec(
            argv = current.tree.serverArgv(listOf("-k")),
            environment = current.bootstrapEnvironment,
            workingDirectory = current.layout.base,
        )
        current.log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")
        withTimeoutOrNull(KILL_TIMEOUT_MS) { runner.run(spec) { line -> record(current.log, line) } }
        val exited = withTimeoutOrNull(KILL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { server.waitFor() }
        }
        if (exited == null) {
            current.log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "wineserver did not exit after -k; killing it",
            )
            server.destroyForcibly()
        }
        wineserver = null
    }

    /**
     * Run one Wine tool to completion, or fail when it will not finish.
     *
     * **The timeout is not belt and braces.** Every one of these is a blocking
     * wait on a child that shares one pipe with a whole prefix's worth of
     * processes, and the bug this method now guards against was precisely a Wine
     * tool that never returned: provisioning wedged in PREPARING with a checklist
     * on screen, no error anywhere, and `provisioned.json` already claiming the
     * step had happened. A step that hangs is a step that has failed slowly.
     */
    private suspend fun runTool(
        current: LaunchPlan,
        tool: String,
        arguments: List<String>,
        label: String,
        timeoutMs: Long,
    ): BootstrapOutcome {
        val spec = ProcessSpec(
            argv = current.tree.toolArgv(tool, arguments),
            environment = current.bootstrapEnvironment,
            workingDirectory = current.layout.base,
        )
        current.log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")
        val result = withTimeoutOrNull(timeoutMs) {
            runner.run(spec) { line -> record(current.log, line) }
        } ?: return BootstrapOutcome.Failed(
            "$label did not finish in ${timeoutMs / 1000} s and was killed",
        )
        return when (result) {
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
     * Narrate the boot step while it runs.
     *
     * The provisioner owns that row's status — it marks it RUNNING before calling
     * in and DONE or FAILED after — and this only ever writes its detail, so the
     * two cannot disagree about whether the step finished. It is the one row that
     * spans minutes, and without it the checklist shows a dot for the whole of a
     * two-pass `wineboot` with nothing to say which pass is running.
     */
    private fun bootProgress(detail: String) {
        _state.update { state ->
            state.copy(
                steps = state.steps.map {
                    if (it.id == ContainerProvisioner.STEP_BOOT) it.copy(detail = detail) else it
                },
            )
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
            environment = current.bootstrapEnvironment,
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
     * through the prefix registry rather than through a second
     * `explorer /desktop=`, which would put a full-size second desktop window over
     * everything already on screen.
     */
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
        launched.forEach { it.takeIf(Process::isAlive)?.destroy() }

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
        launched.forEach { it.destroyForcibly() }
        wineserver?.destroyForcibly()
        desktop = null
        launched.clear()
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
        // **Which guest program wrote this.** Every process in the container
        // inherits the desktop's stderr, so without this the log is one
        // interleaved stream in which `explorer.exe`, `services.exe` and a game
        // are indistinguishable — which is the state a crash has to be read in.
        // Wine and FEX both stamp the writing thread on every line and the
        // parser used to discard it; `GuestUnits` turns that stamp into a name
        // learned from the guest's own module-load lines.
        val owner = units.label(parsed.unit, parsed.text)
        val text = if (owner == null) parsed.text else "[$owner] ${parsed.text}"
        log.line(parsed.source, parsed.level, text)
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

    /**
     * Put the Tools component where a Windows program expects to find it.
     *
     * **A whole tree, not a handful of DLLs, which is why this is not
     * [copyWindowsPayload].** DXVK and friends are a few files that belong in
     * `system32`; Git is 7894 of them under a directory that has to keep its
     * shape — `cmd\git.exe` finds its helpers by walking up from where it lives,
     * so flattening or relocating any of it breaks the install in ways that only
     * show up on the first command that shells out.
     *
     * `C:\Program Files\Git` because that is where Git for Windows puts itself
     * and where every instruction on the internet says it is. The name is
     * [PrefixRegistry.GIT_DIR], which the machine PATH already lists — the seed
     * names those directories whether or not they exist, so installing the
     * component needs no registry write and no relaunch to be on PATH.
     *
     * Copied rather than symlinked, on the same reasoning as the D3D payload: a
     * link would let a guest program write through into the shared store that
     * every other container on this version reads.
     *
     * Skipped entirely when the component is absent, which is the common case.
     */
    private suspend fun installTools(
        containerId: String,
        layout: ContainerLayout,
        log: SessionLog,
    ): Unit = withContext(Dispatchers.IO) {
        val source = components.directoryFor(containerId, ComponentType.TOOLS) ?: return@withContext
        val target = File(layout.prefix, GIT_PREFIX_DIR)

        // Present already? The marker is the one file everything else hangs off.
        if (File(target, GIT_SENTINEL).isFile) {
            log.line(LogSource.VESSEL, LogLevel.INFO, "tools: ${PrefixRegistry.GIT_DIR} already installed")
            return@withContext
        }

        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) {
            error("could not create ${target.parentFile!!.path}")
        }
        // Into a sibling and renamed, so a launch killed mid-copy leaves no
        // half-tree that the sentinel check above would then call installed.
        val staging = File(target.parentFile, "${target.name}$STAGING_SUFFIX")
        deleteTree(staging)
        source.copyRecursively(staging, overwrite = true)
        deleteTree(target)
        if (!staging.renameTo(target)) error("could not move the tools payload into place")

        // **The three directories MSYS2's first run tries to make and cannot.**
        //
        // Opening Git Bash printed, before anything else:
        //
        //   mkdir: cannot create directory '/dev/shm': Read-only file system
        //   Creating /dev/shm directory failed.
        //   POSIX semaphores and POSIX shared memory will not work
        //   mkdir: cannot create directory '/dev/mqueue': Read-only file system
        //
        // That is `/etc/post-install/01-devices.post`, which Git for Windows
        // runs once on first launch. `mkdir` is coreutils, not the runtime, so
        // this is a script failing rather than the emulator — and `/etc/fstab`
        // here is `none / cygdrive`, which leaves the MSYS2 root as the
        // directory holding `usr\bin\msys-2.0.dll`: this one.
        //
        // Making them in advance turns the script's `mkdir -p` into a no-op that
        // succeeds. It costs three empty directories and removes four lines of
        // alarming red text from the first thing a user sees in a shell they
        // just opened. If the warning survives this, the path is *not* being
        // resolved against the install root and that is worth knowing — the
        // directories are harmless either way, and nothing here depends on them.
        listOf("dev/shm", "dev/mqueue", "tmp").forEach { File(target, it).mkdirs() }

        val files = target.walkTopDown().count { it.isFile }
        log.line(LogSource.VESSEL, LogLevel.INFO, "tools: $files file(s) into ${PrefixRegistry.GIT_DIR}")
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
     * **The order is the order things finish in**, which is not the order they are
     * declared in anywhere else: FEX and the D3D layers are installed *inside* the
     * boot step now, so they sit under it rather than above it. The boot row is
     * the one that spans minutes, and it narrates itself through [bootProgress]
     * while the two rows under it tick.
     *
     * [ContainerProvisioner.plan]'s last step says "Ready to start" and is
     * dropped, because reaching Starting is what says that and a row claiming it
     * one frame earlier is a row nobody reads.
     */
    private fun checklist(): List<ProvisionStep> {
        val provisioning = provisioner.plan(emptyList())
            .filterNot { it.id == ContainerProvisioner.STEP_READY }
            .associateBy { it.id }
        return listOfNotNull(
            provisioning[ContainerProvisioner.STEP_LAYOUT],
            ProvisionStep(STEP_COMPONENTS, "Resolve components"),
            provisioning[ContainerProvisioner.STEP_REGISTRY],
            provisioning[ContainerProvisioner.STEP_BOOT],
            ProvisionStep(STEP_FEX, "Install FEX"),
            ProvisionStep(STEP_D3D, "Install D3D layers"),
        )
    }

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
        /**
         * The desktop's size, set once the desktop is actually started.
         *
         * A `var` and not a constructor argument because the plan is built
         * before the geometry is resolved — the resolution can be `native`,
         * which only the composable that measures the panel can answer. Null
         * until the desktop launches, and [launchProgram] cannot run before
         * that because it refuses unless the phase is RUNNING.
         */
        var geometry: DisplayGeometry? = null,
    ) {
        /**
         * What `wineboot`, `regedit` and `wineserver` are run with, which is a
         * strict subset of [environment].
         *
         * See [BOOTSTRAP_SESSION_ENV] for the list and the measurement behind it.
         * The short version: nothing about building a prefix needs a display, a
         * Vulkan driver or a DLL override, handing it all three stalls
         * `wineboot --init` in `rundll32 … PreInstall` for as long as you care to
         * wait, and the reference scripts have always composed the two
         * environments separately for exactly that reason.
         *
         * `wineserver` is on this side of the line too. It loads no PE and opens
         * no display, so it needs none of it — and it has to agree with its
         * clients about `WINEESYNC`, which the allowlist keeps.
         *
         * The prefix is not left without an opinion about its DLLs: the registry
         * seed writes the same override set under `HKCU\Software\Wine\DllOverrides`
         * as `native,builtin`, which falls back instead of failing. So the second
         * `wineboot --update` prefers the real DLLs where they exist and boots
         * where they do not, and the *desktop* — which does need them — is started
         * from the full [environment].
         */
        val bootstrapEnvironment: Map<String, String>
            get() = environment.filterKeys { it in BOOTSTRAP_SESSION_ENV }
    }

    private companion object {
        const val STEP_COMPONENTS = "session:components"
        const val STEP_FEX = "session:fex"
        const val STEP_D3D = "session:d3d"

        const val NO_PLAN = "the session was torn down before the prefix could be booted"

        /**
         * `wineboot` skips its whole run when this file matches `wine.inf`'s
         * stamp, so the second pass deletes it first. Named here because the
         * name is Wine's, not ours.
         */
        const val UPDATE_TIMESTAMP = ".update-timestamp"
        const val NO_D3D = "No D3D or OpenGL layer is installed"

        const val DRIVE_C_WINDOWS = "drive_c/windows"
        const val SYSTEM32 = "system32"
        const val SYSWOW64 = "syswow64"
        const val DLL_SUFFIX = ".dll"

        /** The 64-bit hive, and the only file that can prove a prefix was booted. */
        const val SYSTEM_REG = "system.reg"

        /**
         * Below this, `system.reg` is a stub the server made and not a prefix.
         *
         * A finished one is several megabytes. The same floor as
         * `tools/device-session.sh`, which is where the number was measured.
         */
        const val MIN_HIVE_BYTES = 100_000L

        /**
         * Below this, the 32-bit world did not come up.
         *
         * A populated `syswow64` has around 885 entries on this build; the handful
         * that appear without a `wineboot --update` pass are whatever the graphics
         * packages put there. 100 is far enough from both to be unambiguous, and
         * is the reference scripts' own threshold.
         */
        const val MIN_WOW64_ENTRIES = 100

        /**
         * Two, and it is measured rather than superstitious.
         *
         * One forced `wineboot --update` leaves `syswow64` empty and a second
         * identical pass fills it. What the first establishes that the second
         * needs is not understood; the honest response is to run both and record
         * that this is why.
         */
        const val WINEBOOT_UPDATE_PASSES = 2

        /**
         * How long a `wineboot` pass may take before it is treated as wedged.
         *
         * `wine.inf` on this phone is minutes, not seconds, and a cold first boot
         * is the slowest thing this app does — so the ceiling is generous. It
         * exists because the alternative is what actually happened: a Wine tool
         * that never returned, a checklist that never moved, and nothing in the
         * log to say which step it was in. The reference scripts wrap the same
         * calls in `timeout 900`.
         */
        const val BOOT_TIMEOUT_MS = 900_000L

        /** `regedit` on a 1.6 KB file is seconds; a minute is already pathological. */
        const val REGEDIT_TIMEOUT_MS = 120_000L

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
        /** [PrefixRegistry.GIT_DIR] as a path under the prefix. */
        const val GIT_PREFIX_DIR = "drive_c/Program Files/Git"

        /** The one file whose presence means the tree finished copying. */
        const val GIT_SENTINEL = "cmd/git.exe"

        /** Where a tools tree is assembled before it is renamed into place. */
        const val STAGING_SUFFIX = ".staging"

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
