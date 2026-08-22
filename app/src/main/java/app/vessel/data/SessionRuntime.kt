package app.vessel.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import app.vessel.core.BOOTSTRAP_SESSION_ENV
import app.vessel.core.ComponentType
import app.vessel.core.ContainerInput
import app.vessel.core.ContainerProfile
import app.vessel.core.DEFAULT_DISPLAY
import app.vessel.core.DisplayGeometry
import app.vessel.core.DisplayOutcome
import app.vessel.core.DisplayParams
import app.vessel.core.DisplayRequest
import app.vessel.core.FEX_CACHE_DOS_DIR
import app.vessel.core.FexPackage
import app.vessel.core.GuestUnits
import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.PrefixRegistry
import app.vessel.core.SYSVSHM_SOCKET_ENV
import app.vessel.core.SessionDiagnosis
import app.vessel.core.SessionDisplayServer
import app.vessel.core.SessionPaths
import app.vessel.core.SessionScratch
import app.vessel.core.TurnipDriver
import app.vessel.core.UpscalerRequest
import app.vessel.core.WINE_BOOT
import app.vessel.core.WINE_REGEDIT
import app.vessel.core.WINE_UNIX_ARCH
import app.vessel.core.WcpProfile
import app.vessel.core.WineTree
import app.vessel.core.deleteTree
import app.vessel.core.desktopArgv
import app.vessel.core.deviceCoreCount
import app.vessel.core.deviceTotalRamMb
import app.vessel.core.diagnoseSessionLine
import app.vessel.core.fexCacheHost
import app.vessel.core.fexCacheLink
import app.vessel.core.hardwareLimits
import app.vessel.core.isDisplayAbsenceDiagnostic
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import app.vessel.core.parseFpsLimit
import app.vessel.core.parseGeometry
import app.vessel.core.parseSessionLogLine
import app.vessel.core.programArgv
import app.vessel.core.serverArgv
import app.vessel.core.sessionEnvironment
import app.vessel.core.toolArgv
import app.vessel.core.vesselTmpLink
import app.vessel.core.vkd3dCacheLink
import app.vessel.core.wineLauncherEnvironment
import app.vessel.input.InputProfile
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import app.vessel.core.vkd3dShaderDumpDir
import app.vessel.core.vkd3dShaderOverrideDir

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
    private val setup: ComponentSetup,
    private val inputProfiles: InputProfileRepository,
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

    /**
     * Whether an X server is listening yet.
     *
     * Read by [record] and nothing else. Prefix creation deliberately runs with
     * no display — see `BOOTSTRAP_SESSION_ENV` — so the null driver's complaints
     * are expected until this turns true, and errors again afterwards.
     */
    @Volatile
    private var displayReady: Boolean = false

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
     * The session's one output stream, shared by the desktop and every program.
     *
     * See [GuestOutputPipe] for why a per-process pipe silently loses everything
     * a launched program says. Null when the FIFO could not be made, in which
     * case the old per-process draining still applies and the log is merely
     * poorer.
     */
    @Volatile
    private var guestOutput: GuestOutputPipe? = null

    /**
     * The start-of-session code-cache compile, running beside the desktop.
     *
     * Held so teardown can cancel it before starting its own: the two would
     * otherwise merge the same reference codemaps from two processes. See the
     * call site in [runDesktop] for why it is not awaited there.
     */
    private var codeCacheJob: Job? = null

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

    /**
     * One line into the running session's log, from something outside the
     * launch path.
     *
     * **The direction matters.** [SessionMetricsRecorder] already depends on
     * this class — it watches [state] to know when a session starts — so it can
     * call in here, whereas a version where this class asked the recorder for a
     * summary would be a dependency cycle Hilt would refuse to build. That is
     * why the sampler pushes its report rather than teardown pulling one.
     *
     * Silently does nothing when no session is open, which is the honest
     * behaviour for a caller that is by definition not synchronised with the
     * launch: a line arriving after the log has closed is a line about a session
     * that has already been written up.
     */
    fun note(level: LogLevel, text: String) {
        plan?.log?.line(LogSource.VESSEL, level, text)
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

        // **`RUNNING` means the desktop process was spawned; this waits until it
        // is the desktop.** Starting a program is a second
        // `explorer /desktop=vessel,WxH`, and Wine gives the desktop to whichever
        // of the two reaches `wine_create_desktop` first — see
        // [SessionDisplayServer.desktopUp] for what losing that race did.
        //
        // Bounded, and a timeout starts the program anyway: a desktop that never
        // appears is a different failure, and refusing to launch would hide it
        // behind a message about the launcher.
        if (!display.desktopUp.value) {
            withTimeoutOrNull(DESKTOP_READY_TIMEOUT_MS) { display.desktopUp.first { it } }
                ?: current.log.line(
                    LogSource.VESSEL,
                    LogLevel.WARN,
                    "the desktop has not appeared after " +
                        "${DESKTOP_READY_TIMEOUT_MS / 1000} s; starting $program anyway",
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
            // The launcher `explorer` exits about a second after the program
            // starts. Its pipe dies with it and the program's does not exist —
            // this is the case [GuestOutputPipe] was written for.
            output = guestOutput?.file,
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
        // where a failure is silent. With the FIFO the session reader already
        // has it; without one this is the old, lossy path.
        if (guestOutput == null) {
            scope.launch { runner.drain(process) { line -> record(current.log, line) } }
        }
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

        // **The dangerous diagnostics are spent here, before the session runs.**
        // Writing the record back at launch rather than at teardown is what makes
        // "one session" survive the app being killed mid-run: a `+relay` armed
        // for this launch is already off in the stored document by the time the
        // guest process exists, so the run after a crash gets the ordinary
        // environment with nothing to re-disarm. This session still uses the
        // record as it was read a line above.
        val spentDiagnostics = profile.diagnostics.consumed()
        if (spentDiagnostics != profile.diagnostics) {
            runCatching { containers.save(profile.copy(diagnostics = spentDiagnostics)) }
        }

        val manifest = manifests.load().getOrNull()
        val geometry = parseGeometry(text(profile, manifest, DisplayParams.RESOLUTION), native)
        val fpsLimit = parseFpsLimit(text(profile, manifest, DisplayParams.FPS_LIMIT))
        val upscaler = upscalerOf(profile, manifest)
        val frameGeneration = parseFrameGeneration(
            text(profile, manifest, DisplayParams.FRAME_GENERATION),
        )

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
        val log = logs.open(containerId, startedAt, profile.diagnostics.limits)
        // **The header no longer names component versions, because at this
        // point it cannot know them.**
        //
        // It printed `profile.wineBuild`, `profile.driver` and
        // `profile.d3dLayer`, which are labels `ContainerRepository` computes
        // and persists when a container is drafted or saved. They are stale by
        // construction -- nothing recomputes them when adoption moves a
        // reference -- and they are printed here *before* `adoptLatest` runs a
        // hundred lines below, so even a fresh one would describe the previous
        // session. They also named DXVK as `d3d` on a Direct3D 12 title, where
        // vkd3d is the layer that matters.
        //
        // The `components:` line written after adoption is the honest version
        // of this, and it reads the same reference the staging step does.
        log.header(
            listOf(
                "container  ${profile.name}",
                "desktop    $geometry @ ${fpsLimit?.let { "$it fps" } ?: "unlimited"}",
                "upscaler   $upscaler",
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
            prepare(containerId, profile, manifest, fpsLimit, log) ?: return
            runDesktop(log, geometry, fpsLimit, upscaler, frameGeneration)
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
        /** The same number `runDesktop` paces the compositor with. See below. */
        fpsLimit: Int?,
        log: SessionLog,
    ): LaunchPlan? {
        val layout = paths.of(containerId)

        mark(STEP_COMPONENTS, ProvisionStatus.RUNNING)
        // **Before adoption, not after.** The bundle's packages install on a
        // background scope, and adoption used to race that: on the first launch
        // after an APK update the new `.wcp` was still unpacking, so the newest
        // version did not exist yet and the container was pinned to the previous
        // one. Measured with FEXCore — 260803 installed, container on 260802,
        // and only the second launch corrected it. Every stale-component bug on
        // this project has ended in a session that ran an old build and was read
        // as evidence about a new one; this is that bug with the store and the
        // adoption both correct and only the ordering wrong.
        setup.awaitFinished()
        val adopted = components.adoptLatest(containerId)
        // **Pruned here because this is the moment a version stops being
        // referenced.** `adoptLatest` has just written this container's references
        // to disk, so whatever it moved off is now unreferenced by the only
        // file that decides that. Pruning before adoption would be reading
        // references this session is about to change.
        //
        // [ComponentStore.prune] says nothing calls it automatically, and the
        // reason is that deleting a Wine tree under a running container is not
        // worth any amount of disk. That reason still holds, and it is exactly
        // why this is a plain `prune` and nothing cleverer: it counts
        // references across every container directory on disk, so a running
        // container -- this one included -- protects its own components by
        // having written them down. What it frees is only what nothing names.
        //
        // Not fatal on failure. A session that could not free disk is still a
        // session; one that refused to start because it could not is not.
        // Crash reports are the other unbounded thing in a container, and by far
        // the largest single files on the device -- see [sweepGuestDebris].
        runCatching { sweepGuestDebris(layout.prefix) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { freed ->
                log.line(
                    LogSource.VESSEL,
                    LogLevel.INFO,
                    "swept stale crash reports, freed ${freed / (1024L * 1024L)} MB",
                )
            }
        runCatching { components.prune() }
            .getOrNull()
            ?.takeIf { !it.isEmpty }
            ?.let { pruned ->
                log.line(
                    LogSource.VESSEL,
                    LogLevel.INFO,
                    "pruned ${pruned.removed.size} unreferenced component(s), freed " +
                        "${pruned.freedBytes / (1024L * 1024L)} MB",
                )
            }
        // **Say it, every time, before anything else can be blamed for it.**
        //
        // What is left here after adoptLatest is Wine, which keeps the version
        // its prefix was booted against. That is deliberate, but it is also the
        // shape of a whole day's worth of wrong conclusions: a component was
        // installed, listed as installed, and not loaded, and the session that
        // followed was read as evidence about the new build when it had run the
        // old one. A container that is behind now says so in its own log.
        // **Say which build of every component this session actually runs, every
        // time, whether or not anything is wrong.**
        //
        // The header three lines above prints `wine`, `driver` and `d3d`, and
        // all three are *labels* -- `ContainerRepository` recomputes them from
        // `LATEST` for the container card, and nothing selects a component from
        // them. What a session actually loads is the reference in
        // `provisioned.json`, which they do not read. So the header can name the
        // newest build while the session runs an older one, and it names DXVK as
        // `d3d` even for a Direct3D 12 title, where the layer that matters is
        // vkd3d -- which appears nowhere in the log at all. FEX, Tools and
        // OpenGL do not appear either.
        //
        // [staleReferences] below is not this. It speaks only when a container
        // is *behind*, which leaves the ordinary case -- everything current --
        // recording nothing about what ran. Answering "did the new build load"
        // then means reading `provisioned.json` off the device by hand, which
        // is exactly what this file's comments describe going wrong five times.
        //
        // Read through [ComponentStore.directoryFor], which is the same call
        // the staging step uses, so this line cannot disagree with what is
        // copied into the prefix a moment later.
        val running = ComponentType.entries.mapNotNull { type ->
            components.directoryFor(containerId, type)?.let { "${type.label} ${it.name}" }
        }
        log.line(
            LogSource.VESSEL,
            LogLevel.INFO,
            if (running.isEmpty()) "components: none referenced"
            else "components: ${running.joinToString(", ")}",
        )

        for ((type, versions) in components.staleReferences(containerId)) {
            val (referenced, newest) = versions
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "${type.label} is at $referenced but $newest is installed — this " +
                    "session runs $referenced. Adoption is forward-only and ran " +
                    "before this line, so a reference still behind here means the " +
                    "newer version was not adoptable, not that it was skipped.",
            )
        }
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

        // **Before the promotion below, and before the environment is
        // handed to Wine.** vkd3d is given VKD3D_CACHE_DOS_PATH
        // (`C:\vessel\vkd3dcache\`), not the unix `caches/vkd3d` that Mesa and
        // DXVK get — see that constant for why a unix path there fails the
        // open outright — so the DOS path needs something real to resolve to
        // before the guest starts, the same way [linkFexCache] does for FEX.
        linkVkd3dCache(layout.prefix, File(layout.caches, "vkd3d"), log)

        // Same reason, one directory over: the D3D layers are handed
        // GFX_STATS_DOS_PATH (`C:\vessel\tmp\gfx-stats.json`) because the unix
        // path they used to get could not be opened from inside the prefix by
        // either of them. See that constant.
        linkVesselTmp(layout.prefix, layout.tmp, log)

        // **`.cache.write` is the pipeline cache, not a lock, and this used to
        // delete it on every launch.**
        //
        // It was read as a lock because of what vkd3d does *later*: the stream
        // archive is opened `_O_CREAT | _O_EXCL`
        // (native/vkd3d/libs/vkd3d-common/file_utils.c:93) on the first blob
        // insert, so a file already sitting there fails the open and the session
        // reports `Failed to open stream archive write file exclusively`. Since
        // Vessel's sessions are killed rather than closed, a leftover looked
        // like the permanent state, and deleting it looked like the fix.
        //
        // It is not, because the open is not the first thing that touches the
        // file. `vkd3d_pipeline_library_disk_cache_initial_setup` runs first on
        // the same disk thread and calls `..._merge`, whose very first act is
        // `rename_no_replace(write_path, read_path)` — *this* is how a written
        // cache becomes a readable one (cache.c:3037). Delete the file and that
        // promotion has nothing to promote; the session then logs, in order,
        //
        //     removed a stale vkd3d shader cache lock (…cache.write)
        //     No write cache exists. No need to merge any disk caches.
        //     Failed to map read-only cache: …cache
        //
        // which is Requiem compiling all 3,025 pipelines on every start while
        // writing 6.4 MB it will never read. Measured on 2026-08-16, on the
        // second consecutive launch, with the cache file present beforehand.
        //
        // The exclusive-open failure that motivated the delete has a different
        // cause and was already fixed: before `linkVkd3dCache` above, the DOS
        // path resolved to nothing and *every* open there failed — see
        // VKD3D_CACHE_DOS_PATH, which records the same error string.
        //
        // So promote instead of deleting, which is the same handoff the FEX
        // cache uses. Only when there is nothing to overwrite: when both files
        // exist, vkd3d's own merge is the thing that knows how to fold one into
        // the other and delete the leftover, and a rename here would throw away
        // whichever half it did not pick.
        runCatching {
            File(layout.caches, "vkd3d").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".cache.write") }
                ?.forEach { written ->
                    val promoted = File(written.parentFile, written.name.removeSuffix(".write"))
                    // Read before the rename: afterwards `written` names nothing
                    // and would report a cache of zero bytes, which is the one
                    // number this line exists to distinguish from a real one.
                    val kib = written.length() / 1024

                    if (!promoted.exists() && written.renameTo(promoted)) {
                        log.line(
                            LogSource.VESSEL,
                            LogLevel.INFO,
                            "promoted the vkd3d shader cache from the last session " +
                                "(${promoted.name}, $kib KiB)",
                        )
                    }
                }
        }

        val turnip = turnipDriver(containerId)
        val fex = fexPackage(containerId)
        // `tmp` explicitly, even though the default derives the same directory:
        // it is where the D3D layer writes the counters the Metrics tab graphs,
        // and a reader following that path should find it named rather than
        // inferred.
        val sessionPaths = SessionPaths(
            prefix = layout.prefix,
            logs = layout.logs,
            tmp = layout.tmp,
        )
        // **What the guest is told this phone has**, resolved once here.
        //
        // Once, because two of the three are expressed as a delta from what the
        // device really has and the third has to be written to a file as well as
        // named in the environment. A second reader would have to repeat both
        // the arithmetic and the device query, and the failure mode is the guest
        // being told one number by Wine and another by the driver.
        val hardware = hardwareLimits(
            profile = profile,
            manifest = manifest,
            deviceRamMb = deviceTotalRamMb(),
            deviceCores = deviceCoreCount(),
        )
        val environment = wineLauncherEnvironment(
            tree = tree,
            scratch = SessionScratch(home = layout.base, tmp = layout.tmp),
            // Only when there is a driver to load. See wineLauncherEnvironment:
            // without this the adrenotools dlopen fails on libc++_shared.so and
            // the stock Qualcomm driver answers, silently.
            hooksDir = turnip?.hooksDir,
            driverDir = turnip?.driverDir,
        ) + sessionEnvironment(
            profile = profile,
            manifest = manifest,
            paths = sessionPaths,
            turnip = turnip,
            fex = fex,
            display = DEFAULT_DISPLAY,
            // The same value `runDesktop` gives the compositor, threaded down
            // here rather than parsed a second time. It becomes `DXVK_FRAME_RATE`
            // and `VKD3D_FRAME_RATE`, so the renderer stops producing frames the
            // compositor was already throwing away — measured at 116 rendered for
            // every 24 shown, at 84% GPU. See `sessionEnvironment`.
            fpsLimit = fpsLimit,
            // Resolved here rather than threaded down beside `fpsLimit`: it is
            // read from the same profile and manifest this function already
            // holds, and the environment is the only consumer on this path.
            frameGeneration = parseFrameGeneration(
                text(profile, manifest, DisplayParams.FRAME_GENERATION),
            ),
            frameGenerationDivides = text(profile, manifest, DisplayParams.FRAME_GENERATION_MODE)
                != DisplayParams.FRAME_GENERATION_MODE_SMOOTHNESS,
            hardware = hardware,
            // Read here rather than in `sessionEnvironment` because it needs a
            // `Context` and that function is pure by contract. See the parameter.
            dnsServers = deviceDnsServers(appContext),
        )

        if (turnip == null) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "no Turnip driver is installed; the phone's stock Vulkan driver will be used",
            )
        }

        // **Resolved here rather than in `runDesktop`, because a missing profile
        // is not a failure and must not read like one.** A `profileId` naming a
        // profile that has since been deleted is ordinary — profiles are shared
        // between containers and deleting one is allowed — so it resolves to the
        // built-in default, is said out loud in the log, and **the container is
        // not rewritten**. Rewriting it here would turn "you deleted a profile"
        // into a silent edit of a second document during a launch.
        val wanted = profile.input.profileId
        val input = inputProfiles.resolve(wanted)
        if (wanted != null && wanted != input.id) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "input profile $wanted has been deleted; using ${input.name}",
            )
        } else {
            log.line(LogSource.VESSEL, LogLevel.INFO, "input profile ${input.name}")
        }

        // The guest is told `C:\vessel\fexcache\`; this is what that resolves to.
        val cacheHost = fexCacheHost(sessionPaths, environment, fex)
        // Every other digest under `caches/fex` belongs to a FEX build or a FEX
        // setting this container no longer runs, and nothing else will ever
        // reclaim it -- see [sweepStaleFexCaches]. Swept here rather than beside
        // the component prune because only this line knows which digest is live,
        // and recomputing the key somewhere else is a second chance to get it
        // wrong.
        runCatching { sweepStaleFexCaches(File(sessionPaths.caches, "fex"), cacheHost) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { freed ->
                log.line(
                    LogSource.VESSEL,
                    LogLevel.INFO,
                    "dropped stale FEX code caches, freed ${freed / (1024L * 1024L)} MB",
                )
            }
        linkFexCache(layout.prefix, cacheHost, fex?.compilers.orEmpty(), log)

        val resolved = LaunchPlan(
            layout = layout,
            tree = tree,
            environment = environment,
            log = log,
            offlineCompiler = fex?.offlineCompiler,
            fexCacheHost = cacheHost,
            input = input,
            touchVisible = profile.input.touchVisible,
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
        upscaler: UpscalerRequest,
        frameGeneration: Int,
    ) {
        val current = plan ?: return
        _state.update { it.copy(phase = SessionPhase.STARTING) }

        val request = DisplayRequest(
            display = DEFAULT_DISPLAY,
            geometry = geometry,
            fpsLimit = fpsLimit,
            socketRoot = current.layout.base,
            upscaler = upscaler,
            frameGeneration = frameGeneration,
        )
        // What the server answers with, not what was asked for. `DISPLAY` and
        // `WINE_SYSVSHM_SOCKET` name sockets that either got bound or did not,
        // and the desktop is the first process that has to reach them.
        val displayEnvironment: Map<String, String>
        when (val outcome = display.start(request)) {
            is DisplayOutcome.Started -> {
                displayEnvironment = outcome.environment
                // Only on Started. NotAvailable means the session genuinely has
                // no display, and a complaint about that is worth hearing.
                displayReady = true
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

        // **Pushed after the server is up and before the guest exists.** The view
        // that carries the translator is built by `display.start`, so anything
        // pushed earlier lands on a view that is about to be replaced; anything
        // pushed later means the first seconds of a game run on the wrong table.
        display.setInputProfile(current.input)
        display.setTouchControlsVisible(current.touchVisible)

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

        // **The catch-up compile, and the reason it is here and not only in
        // teardown.** `generateCodeCache` has always run at teardown, which is
        // the better moment — the codemap is complete and no guest is competing
        // for the CPU. It had also never once run: every session ever recorded
        // on the device reads `"exit": "RUNNING", "endedAt": null`, because the
        // process is killed rather than stopped, and a killed process does not
        // reach teardown. Three Metro codemaps sat in `codemap/new/` across two
        // cache keys with no `cache/` directory beside them, which is
        // `ProcessAll` having never started.
        //
        // Start is the moment that cannot be skipped. It is also the moment the
        // result is worth the most: last session's codemaps become this
        // session's cache, before the guest that would load it exists. The cost
        // is paid only when there is something to compile — the first thing
        // [generateCodeCache] does is count `codemap/new` and return — so a
        // session that ended cleanly has already emptied it and this is free.
        //
        // **Launched, not awaited, and that is not a detail.** The first build
        // of this awaited it here, and a compiler that crashes on a stale cache
        // then holds the desktop for the whole of [CODE_CACHE_TIMEOUT_MS]: the
        // session log read "the FEX code cache did not finish in 120 s and was
        // killed" and the container looked like it would not start at all. A
        // best-effort cache is never worth a launch, so it now runs beside the
        // desktop and teardown collects it.
        // **Off by default, because it has never once finished.** Measured on
        // 2026-08-13 across four sessions: the catch-up starts at line 16 of the
        // log, runs 210 s, 230 s and 326 s, and is cancelled by teardown every
        // time. `caches/fex/*/cache/` is empty and `codemap/ready/` holds 69
        // entries, so nothing accumulates between runs either — each session
        // redoes the same work and loses it.
        //
        // What it does cost is real. `cpu %` sat at a steady 12.9 of a
        // hundred for the whole of a Requiem session, and with eight cores that
        // is one core saturated from launch to teardown, competing with the game
        // for the CPU that FEX needs most. It also puts a second Wine process in
        // the prefix, which made a critical-section deadlock impossible to
        // attribute: the timeout lines carry no process name, so "which of these
        // two is holding the heap" could not be answered from the log at all.
        //
        // The feature is not wrong — a persistent code cache is worth having —
        // but it cannot be paid for by every session while never delivering. It
        // needs a moment when nothing else wants the CPU and teardown is not
        // about to kill it, and there is no such moment inside a session.
        // **Publish last session's caches before anything can map one.** This has
        // to happen here — ahead of both the compiler below and the first guest
        // process — and the ordering is the entire mechanism. See
        // [promoteReadyCaches].
        promoteReadyCaches(running, log)

        if (CODE_CACHE_DURING_SESSION) {
            codeCacheJob = scope.launch { generateCodeCache(running, log) }
        }

        // Opened before the first guest process and read for the whole session,
        // because the guest talks for far longer than any one child of ours
        // lives. [GuestOutputPipe] has the measurement.
        val pipe = GuestOutputPipe.create(File(running.layout.base, "tmp/guest.out"))
        guestOutput = pipe
        val reader = pipe?.let { open ->
            scope.launch(Dispatchers.IO) { open.drain { line -> record(log, line) } }
        }

        val spec = ProcessSpec(
            argv = running.tree.desktopArgv(geometry, emptyList()),
            environment = running.environment,
            workingDirectory = running.layout.base,
            output = pipe?.file,
        )
        log.line(LogSource.VESSEL, LogLevel.INFO, "exec ${spec.commandLine}")

        val process = runner.start(spec).getOrElse {
            fail("The Windows desktop could not start: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        desktop = process
        acquireWakeLock()
        _state.update { it.copy(phase = SessionPhase.RUNNING) }

        // With the FIFO in place the desktop's own pipe carries nothing, so
        // waiting on it would only delay noticing that the desktop exited.
        if (pipe == null) runner.drain(process) { line -> record(log, line) }
        val code = withContext(Dispatchers.IO) { runInterruptible { process.waitFor() } }
        reader?.cancel()

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

        // Beside `installTools` and not inside it, because `installTools` returns
        // early when the Tools payload is absent and these fonts are Wine's, not
        // the payload's — a container with no Git would otherwise lose Tahoma.
        runCatching { linkWineFonts(container, layout, current.log) }
            .onFailure { current.log.line(LogSource.VESSEL, LogLevel.WARN, "wine fonts: ${it.message}") }

        runCatching { ensureScriptsDirectory(layout, current.log) }
            .onFailure { current.log.line(LogSource.VESSEL, LogLevel.WARN, "scripts: ${it.message}") }

        // vkd3d writes shader dumps into a directory it does not create, and a
        // path it cannot write to yields no shaders and no error. Made every
        // launch, whether or not the row is on: an empty directory costs a stat,
        // and it removes one more way for a diagnostic to fail in silence.
        runCatching {
            val dumps = vkd3dShaderDumpDir(layout.tmp)
            if (!dumps.isDirectory) dumps.mkdirs()
            val overrides = vkd3dShaderOverrideDir(layout.tmp)
            if (!overrides.isDirectory) overrides.mkdirs()
        }.onFailure {
            current.log.line(LogSource.VESSEL, LogLevel.WARN, "shader dumps: ${it.message}")
        }

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
        // The next session starts with no display of its own, and this object
        // outlives one session. Left true, the session after this would treat a
        // genuine driver failure during its own prefix work as routine.
        displayReady = false

        val current = plan
        // **Say why the session is ending, in the session's own log.**
        //
        // Without this a session that stopped on its own looks identical to one
        // the user stopped: the file simply ends, the sidecar says `CRASHED`
        // with no code, and the only way to tell a cancelled coroutine from a
        // desktop that exited is to reason backwards from a null. Measured on
        // the device while chasing a game that "did nothing": every run ended
        // the same way and the log could not distinguish the causes.
        val ending = _state.value
        log.line(
            LogSource.VESSEL,
            LogLevel.INFO,
            "session ending: phase=${ending.phase}" +
                " exit=${ending.exitCode?.toString() ?: "none"}" +
                " requested=$stopRequested",
        )
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

        // After `wineserver -k`, so the last thing a dying guest says is still
        // read: closing the FIFO is the only thing that ends its reader, and
        // doing it first would drop exactly the lines that explain the exit.
        guestOutput?.close()
        guestOutput = null

        // The start-of-session catch-up may still be going; it is compiling the
        // *previous* run's codemaps and a call here would want this run's, so
        // two at once would have the compiler merging the same reference set
        // from two processes. Cancelled rather than joined: the session is
        // already over, the user is waiting, and whatever the catch-up does not
        // finish is still in `codemap/new` for the next start.
        codeCacheJob?.cancelAndJoin()
        codeCacheJob = null

        // **And nothing is compiled here.** This used to call generateCodeCache
        // once more, on the theory that teardown is the quiet moment. It cannot
        // work from this point: the compiler is a Windows program, every path
        // above has already run `wineserver -k`, and a Wine client with no
        // server to talk to exits immediately. The session log said so on every
        // run that reached it —
        //
        //     IV exec .../bin/wineserver -k
        //     IV building the FEX code cache from 1 new codemap(s)
        //     WV the FEX code cache compiler exited with 127 after 17 ms
        //
        // — 127 being "could not start it at all", 17 ms being how long that
        // takes to discover. Moving the call above the kill would fix the exit
        // code and buy a teardown that blocks for as long as the compile takes,
        // which is the reason it was made a background job in the first place.
        // So the catch-up at session start is now the only place it happens,
        // and `codemap/new` carries the work forward until then.

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

    /**
     * Turn this run's codemaps into a FEX code cache, if there are any.
     *
     * **This is the half of the code-cache feature that is not a flag.**
     * `FEX_ENABLECODECACHINGWIP=1` makes a run record which guest blocks were
     * translated — a *codemap*, under `<cache>/codemap/new/`. It compiles
     * nothing. `FEXOfflineCompiler64.exe process-all` is what merges those into
     * the reference codemaps and emits one cache file per binary, which the next
     * launch maps at image-load time instead of re-JITting. Without this call the
     * flag is a file write per module load that nothing ever reads back, which is
     * exactly why it used to be off.
     *
     * **Best-effort, and every clause of that is deliberate:**
     *
     *  - It runs only when `codemap/new` has something in it, so a session that
     *     translated nothing new — and a device with no FEX package, and a FEX
     *     package too old to carry the compiler — pays a directory listing.
     *  - It is bounded by [CODE_CACHE_TIMEOUT_MS]. A compiler that wedges gets
     *     killed and teardown carries on; the alternative is a Stop button that
     *     never finishes, which is a worse failure than a cold next launch.
     *  - Every outcome is a line in the session log, including the boring one.
     *     A cache that silently fails to build is indistinguishable from a cache
     *     that is not helping, and that is the one thing this must not be:
     *     the whole feature is a claim about launch time, and a claim needs a
     *     record. Nothing here throws — `runCatching` wraps the lot — but nothing
     *     here is swallowed either.
     *
     * Run from the component directory by its unix path rather than from
     * `system32`: `copyWindowsPayload` deploys `.dll` files and this is an
     * `.exe`, and copying a 3 MB tool into every prefix to run it once per
     * session is not worth the disk. Wine takes a unix path as the program
     * argument, and `process-all`'s own re-exec of itself for each binary
     * resolves siblings from `GetModuleFileNameA`, which lands back in the same
     * directory.
     */
    /**
     * Publish the caches the last session finished but could not rename.
     *
     * **The guest cannot do this, and the reason is a rule in our own Wine
     * rather than a quirk of timing.** `server/fd.c:set_fd_name` refuses to
     * replace a destination that any fd still has open — `list_empty(
     * &inode->open)` — and it never consults share modes. A *mapped view* keeps
     * such an fd alive long after every handle is closed, because
     * `server/mapping.c` grabs the mapping's fd for the life of the view. FEX's
     * `ImageTracker` maps a cache at image load and never unmaps it, so from
     * that instant the name cannot be replaced from inside the prefix by
     * anything, at any time, for the rest of the session.
     *
     * Measured on Requiem: `Access denied` on the rename, every launch after the
     * first, an uncaught `filesystem_error` aborting the compiler, and 339 MB of
     * finished cache thrown away each time.
     *
     * So `patches/fex/0018` has the compiler write `<final>.ready` when it
     * cannot publish, and this promotes it — **on the host, where wineserver is
     * not involved and a plain `rename(2)` replaces a mapped file without
     * complaint.**
     *
     * **Ordering is the mechanism, not a detail.** This runs before
     * `generateCodeCache` and before the first guest process, which is the only
     * window in which nothing has mapped a cache yet. Moved after either one and
     * it becomes the same denied rename, in a different language.
     *
     * **Only `.ready`, never `.new`.** `.new` is also what a compiler killed
     * mid-write leaves behind — teardown cancels that job — and promoting one of
     * those would publish a truncated cache over a good one. `0018` creates
     * `.ready` only after `SaveData` returned true, so the suffix is what carries
     * "this file is complete". Stale `.new` files are deleted rather than left,
     * because a leftover beside a real cache is one more thing to explain later;
     * seven of them had accumulated before this existed.
     *
     * Failures are logged and never fatal. A cache that does not get promoted
     * costs recompilation, which is the situation this whole feature exists to
     * improve on — it is not a reason to refuse to start a session.
     */
    private suspend fun promoteReadyCaches(current: LaunchPlan, log: SessionLog) {
        val cache = current.fexCacheHost ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(cache, "cache")
                val files = dir.listFiles() ?: return@runCatching
                var promoted = 0
                var discarded = 0
                for (file in files) {
                    val name = file.name
                    when {
                        name.endsWith(".ready") -> {
                            val target = File(dir, name.removeSuffix(".ready"))
                            if (file.renameTo(target)) promoted++ else {
                                log.line(
                                    LogSource.VESSEL, LogLevel.WARN,
                                    "could not publish ${target.name}; it will be regenerated",
                                )
                            }
                        }
                        // Incomplete by construction: see the note above.
                        name.endsWith(".new") -> if (file.delete()) discarded++
                    }
                }
                // **The inventory is logged every session, and it is what makes
                // "did the cache load?" answerable at all.**
                //
                // FEX reports the two outcomes at different levels:
                // `Failed to load cache:` is EFmt and `Loaded cache:` is IFmt
                // (`ImageTracker.cpp`), and `patches/fex/0003` puts the default
                // ceiling at ERROR — so a failure is always visible and a success
                // never is. Worse, a cache that simply *is not there* logs
                // nothing, so "no cache existed" and "every cache loaded" are the
                // same silence.
                //
                // Raising the FEX side is not the answer twice over: the only
                // stop above ERROR is `stubs`, which is DEBUG, which is the
                // 85,000-lines-a-minute unaligned-atomic flood — and promoting an
                // informational line to ERROR is the mistake this project already
                // paid for once, when a commit-granularity notice at ERR put 156
                // non-errors in the digest (see docs/DEBUGGING.md).
                //
                // So the count comes from this side instead, and the reasoning is
                // by subtraction: if N caches are on disk and FEX logs no
                // `Failed to load cache:` line, then N loaded. One bounded line a
                // session, and it stays honest when the answer is zero — which is
                // the case that used to be invisible.
                val present = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".ready") }.orEmpty()
                val megabytes = present.sumOf { it.length() } / (1024.0 * 1024.0)
                log.line(
                    LogSource.VESSEL, LogLevel.INFO,
                    "FEX code cache: ${present.size} cached binaries, %.1f MB".format(megabytes) +
                        (if (promoted > 0) ", published $promoted" else "") +
                        (if (discarded > 0) ", discarded $discarded incomplete" else "") +
                        " — any that fail to load will say so at error level",
                )
            }.onFailure {
                log.line(
                    LogSource.VESSEL, LogLevel.WARN,
                    "could not publish pending FEX caches: ${it.message ?: it.javaClass.simpleName}",
                )
            }
        }
    }

    private suspend fun generateCodeCache(current: LaunchPlan, log: SessionLog) {
        val compiler = current.offlineCompiler ?: return
        // The host directory, not `FEX_APP_CACHE_LOCATION` — that is now the DOS
        // path the guest sees (`C:\vessel\fexcache\`) and reading it back as a
        // `File` would count codemaps in a directory named `C:` under the
        // process's cwd, find none, and return without ever saying so.
        val cache = current.fexCacheHost ?: return
        val pending = withContext(Dispatchers.IO) {
            runCatching { File(File(cache, "codemap"), "new").listFiles()?.size ?: 0 }.getOrDefault(0)
        }
        if (pending == 0) return

        log.line(
            LogSource.VESSEL,
            LogLevel.INFO,
            "building the FEX code cache from $pending new codemap(s) in ${cache.path}",
        )
        val started = System.currentTimeMillis()
        // The DOS alias, not `compiler.absolutePath`. `ProcessAll` re-execs
        // itself through `GetModuleFileNameA`, and from a unix path every child
        // fails to spawn — see [linkFexCache], which maintains the alias.
        val dosCompiler = FEX_CACHE_DOS_DIR + compiler.name
        val spec = ProcessSpec(
            argv = current.tree.programArgv(dosCompiler, listOf("process-all")),
            // **`FEX_SILENTLOG` is overridden here, and only here.**
            //
            // The session default is silent, because FEX's debug tier was 99.9%
            // of a 49 MB log — a line pair per unaligned atomic, which is a cost
            // of *executing* translated code. This process does not execute any:
            // it reads codemaps and translates them ahead of time, so the flood
            // that justified the default cannot happen here.
            //
            // What silence did cost was the ability to see this fail. The
            // compiler exited 1 after 3m30s on two consecutive sessions and said
            // nothing at all, because `FEX_SILENTLOG=1` means no message handler
            // is installed and `patches/fex/0003`'s error ceiling has nothing to
            // filter. Three and a half minutes of full-CPU work, an empty
            // `cache/` directory, and no diagnostic — which is the worst of the
            // three possible outcomes.
            //
            // **`FEX_DISABLEDEP` is overridden here for the same reason, and it
            // is what makes this process finish at all.** `patches/fex/0002`
            // turns DEP off for every guest because anti-tamper stubs build code
            // at runtime and die on `NoExec instruction in entry block` without
            // it. The cost, which that patch states, is that FEX's permission
            // tracking then treats every readable page as executable — and this
            // process reserves 272 MB for its own `LookupCache`, uncommitted, so
            // that reservation gets classified as guest RWX code.
            // `HandleRWXAccessViolation` then claims the first-touch fault that
            // `OvercommitTracker` exists to receive, cannot reprotect a page that
            // was never committed, reports the fault handled anyway, and resumes
            // it. Measured on the device: `RWX reprotect FAILED C000002D on page
            // 7E4EF44000 … occurrence 5668864`, 54 s of CPU with system time at
            // twice user time, on `winmm.dll`'s two blocks.
            //
            // The compiler runs no anti-tamper code — it translates codemaps
            // ahead of time and executes none of it — so it is the one process
            // that never needed DEP off. Same container, same codemaps, same
            // binary, one variable: with this set, 21 modules including that same
            // `winmm.dll` compiled in 25 seconds and `RWX reprotect FAILED`
            // stayed at zero.
            //
            // Here rather than in FEX because the distinction is *which process*,
            // and FEX cannot see that from inside: an allocation FEXCore makes
            // for itself and one a guest makes are indistinguishable by the time
            // a notification arrives.
            //
            // **This line is also half of the answer to "`DisableDEP` is global
            // and should be per-title", and the whole of that answer is written
            // down beside `RESERVED_SESSION_ENV` in `SessionEnvironment.kt`.**
            // Short version, re-examined 2026-08-16: the name is deliberately
            // *not* reserved, so a container reaches it through the manifest or
            // its own environment table and `fexCacheKey` digests it, which means
            // per-container scoping already works and cannot silently share a
            // cache across the two settings. The default stays on because the two
            // failure directions are not symmetric — DEP on for a title that needs
            // it off is a dead launch, DEP off for one that does not is a latent
            // risk and a mutex — and nothing observed is harmed by it.
            //
            // **One thing this override leaves open, recorded because it is not
            // established either way.** `fexCacheKey` digests the *session's*
            // `FEX_*` variables, and this process deliberately runs with a
            // different value of one of them, so a cache directory keyed on DEP-on
            // holds blocks a compiler produced with DEP off. `patches/fex/README.md`
            // lists `FEX_DISABLEDEP` among the variables that decide "what it
            // treats as code", which is why they had to reach cache generation at
            // all. Whether the two can actually diverge is untested: the compiler
            // works from codemaps of images whose PE sections are already
            // executable, where DEP promotion has nothing to add — a codemap entry
            // recorded from a *runtime-generated* region is the case where it
            // might. Not a claim and not a reason to change this line; the place
            // to look if a loaded cache is ever suspected of running the wrong
            // code, alongside the missing content hash in `docs/TODO.md`.
            environment = current.environment +
                ("FEX_SILENTLOG" to "0") +
                ("FEX_DISABLEDEP" to "0"),
            workingDirectory = current.layout.base,
        )
        val outcome = runCatching {
            withTimeoutOrNull(CODE_CACHE_TIMEOUT_MS) {
                runner.run(spec) { line -> record(log, line) }
            }
        }
        val elapsed = System.currentTimeMillis() - started
        val result = outcome.getOrNull()
        when {
            outcome.isFailure -> log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "the FEX code cache was not built: ${outcome.exceptionOrNull()?.message}",
            )
            result == null -> log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "the FEX code cache did not finish in ${CODE_CACHE_TIMEOUT_MS / 1000} s and was killed",
            )
            result is ProcessResult.NotStarted -> log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "the FEX code cache compiler could not start: ${result.reason}",
            )
            result is ProcessResult.Exited && result.code != 0 -> log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "the FEX code cache compiler exited with ${result.code} after ${elapsed} ms",
            )
            else -> log.line(
                LogSource.VESSEL,
                LogLevel.INFO,
                "FEX code cache built in ${elapsed} ms",
            )
        }
    }

    /**
     * Point `drive_c/vessel/fexcache` at this session's cache directory.
     *
     * FEX is handed [FEX_CACHE_DOS_PATH] because that is the only shape of path
     * it can both write through Win32 and read back through its hand-built
     * `\??\` string. The bytes still have to live under `caches/`, so the DOS
     * path lands on a symlink and the symlink is what moves when the
     * configuration digest changes.
     *
     * Re-pointed rather than created once: [fexCacheHost] is keyed on the whole
     * FEX configuration, so changing a knob changes the target, and a link left
     * pointing at the previous key would hand this session the previous
     * session's cache — the exact silent-wrong-cache failure the digest exists
     * to prevent.
     *
     * Best-effort and never fatal. A prefix that cannot carry a symlink loses
     * the code cache, which is a performance feature; it does not lose a
     * session. A real directory already sitting at the link path is left alone
     * and said out loud, because deleting something in a user's prefix to make
     * room for a cache is a worse outcome than not having the cache.
     */
    private suspend fun linkFexCache(
        prefix: File,
        host: File,
        compilers: List<File>,
        log: SessionLog,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                host.mkdirs()
                val link = fexCacheLink(prefix)
                link.parentFile?.mkdirs()
                val path = link.toPath()
                val target = host.toPath()

                // **Both compilers, under the same DOS-visible directory.**
                // `ProcessAll` re-execs itself once per module with
                // `_spawnv(_P_WAIT, GetModuleFileNameA(), …)`, and a self-path
                // that is a unix path is not something `_spawnv` can launch —
                // every child fails and the parent reports
                // "Cache generation failed" for all of them without saying why.
                // Both bitnesses, because FEX rewrites the trailing name to
                // pick the 32- or 64-bit sibling out of its own directory.
                compilers.forEach { exe ->
                    val alias = File(link.parentFile, exe.name).toPath()
                    runCatching { Files.deleteIfExists(alias) }
                    runCatching { Files.createSymbolicLink(alias, exe.toPath()) }
                }

                if (Files.isSymbolicLink(path)) {
                    if (Files.readSymbolicLink(path) == target) return@runCatching
                    Files.delete(path)
                } else if (link.exists()) {
                    log.line(
                        LogSource.VESSEL,
                        LogLevel.WARN,
                        "${link.path} is a real directory, not a link; " +
                            "the FEX code cache will not be under caches/",
                    )
                    return@runCatching
                }
                Files.createSymbolicLink(path, target)
            }.onFailure {
                log.line(
                    LogSource.VESSEL,
                    LogLevel.WARN,
                    "the FEX code cache could not be linked into the prefix: ${it.message}",
                )
            }
        }
    }

    /**
     * Point `drive_c/vessel/vkd3dcache` at this container's `caches/vkd3d`.
     *
     * Same shape as [linkFexCache] and the same motive: vkd3d-proton is handed
     * a DOS path (`VKD3D_CACHE_DOS_PATH`) because the unix path it used to get
     * is rewritten to `Z:\...` on the way in and Vessel's prefixes carry no
     * `Z:` — see that constant for the failure this replaces. The bytes still
     * live under `caches/`, so the DOS path lands on a symlink, same as FEX's.
     *
     * Never re-pointed once made, unlike [linkFexCache]: FEX's link moves
     * because its target is keyed on a configuration digest that can change
     * between launches, and `caches/vkd3d` is not keyed on anything — it is
     * the same directory for the life of the container, so a link already
     * pointing at it is already correct.
     *
     * Best-effort and never fatal, for the same reason as [linkFexCache]:
     * losing this link costs a slower launch, not a launch.
     */
    /**
     * Point `drive_c/vessel/tmp` at this container's scratch directory.
     *
     * Same shape and same motive as [linkVkd3dCache]: a program running inside
     * the prefix cannot open a unix path with plain C file I/O, so the D3D
     * layers are handed `C:\vessel\tmp\gfx-stats.json` and this is what makes
     * that resolve. [app.vessel.core.GFX_STATS_DOS_PATH] carries the mechanism
     * and the measurement.
     *
     * The host directory is created first: `fopen(…, "w")` creates a file and
     * not the directories above it, so a link pointing at a directory that does
     * not exist yet fails exactly the same way the unix path did, and just as
     * quietly.
     *
     * Never re-pointed once made, for [linkVkd3dCache]'s reason — `tmp` is the
     * same directory for the life of the container, so a link already pointing
     * at it is already correct.
     *
     * Best-effort and never fatal: losing this costs the counters, not the
     * session. It is logged at WARN because a silent loss here is precisely the
     * failure this whole change exists to stop.
     */
    private suspend fun linkVesselTmp(prefix: File, host: File, log: SessionLog) {
        withContext(Dispatchers.IO) {
            runCatching {
                host.mkdirs()
                val link = vesselTmpLink(prefix)
                link.parentFile?.mkdirs()
                val path = link.toPath()
                val target = host.toPath()
                if (Files.isSymbolicLink(path)) {
                    if (Files.readSymbolicLink(path) == target) return@runCatching
                    Files.delete(path)
                } else if (link.exists()) {
                    log.line(
                        LogSource.VESSEL,
                        LogLevel.WARN,
                        "${link.path} is a real directory, not a link; " +
                            "the Direct3D counters will not reach the metrics panel",
                    )
                    return@runCatching
                }
                Files.createSymbolicLink(path, target)
            }.onFailure {
                log.line(
                    LogSource.VESSEL,
                    LogLevel.WARN,
                    "the container's tmp could not be linked into the prefix, " +
                        "so the Direct3D counters have nowhere to land: ${it.message}",
                )
            }
        }
    }

    private suspend fun linkVkd3dCache(prefix: File, host: File, log: SessionLog) {
        withContext(Dispatchers.IO) {
            runCatching {
                host.mkdirs()
                val link = vkd3dCacheLink(prefix)
                link.parentFile?.mkdirs()
                val path = link.toPath()
                val target = host.toPath()
                if (Files.isSymbolicLink(path)) {
                    if (Files.readSymbolicLink(path) == target) return@runCatching
                    Files.delete(path)
                } else if (link.exists()) {
                    log.line(
                        LogSource.VESSEL,
                        LogLevel.WARN,
                        "${link.path} is a real directory, not a link; " +
                            "the vkd3d shader cache will not be under caches/",
                    )
                    return@runCatching
                }
                Files.createSymbolicLink(path, target)
            }.onFailure {
                log.line(
                    LogSource.VESSEL,
                    LogLevel.WARN,
                    "the vkd3d shader cache could not be linked into the prefix: ${it.message}",
                )
            }
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
        // **The thread as well as the program, because a deadlock is named by
        // thread.** `patches/wine/0030` reports a stuck lock as "wait timed out
        // in thread 0274, blocked by 0270", and until now those numbers led
        // nowhere: the parser read Wine's thread id, used it to look up a
        // program name, and dropped it. So a log could say a thread held a lock
        // for two minutes and contain not one other line attributable to that
        // thread — measured on three Requiem sessions, where the only question
        // worth asking was "what was 0270 doing" and nothing could answer it.
        //
        // Kept as `[program:thread]` rather than a column of its own: it is four
        // hex digits on a line that already carries a tag, and the pair is what
        // a reader greps for. The thread alone when the program is not yet known
        // — a unit that has announced no executable is still a thread whose
        // lines belong together.
        val owner = units.label(parsed.unit, parsed.text)
        val tag = when {
            owner != null && parsed.unit != null -> "$owner:${parsed.unit}"
            owner != null -> owner
            else -> parsed.unit
        }
        val text = if (tag == null) parsed.text else "[$tag] ${parsed.text}"

        // **Before the display server answers, "there is no display" is not an
        // error.** Prefix creation runs wineboot, regedit, services.exe,
        // rundll32 and explorer with no X server on purpose, so winex11.drv
        // fails process_attach and the null driver complains — about ninety
        // error-level lines per provisioning pass, all describing a state
        // Vessel arranged and nobody can act on. They crowded out the lines that
        // explain real failures, which is the whole cost.
        //
        // Gated on the server being up rather than on a provisioning flag: the
        // one that exists is set once and never cleared, so it would silence
        // these for the entire session. This way the same text is an error again
        // the moment a display exists, which is when the complaint becomes true.
        val level = if (
            !displayReady &&
            parsed.level == LogLevel.ERROR &&
            isDisplayAbsenceDiagnostic(parsed.text)
        ) {
            LogLevel.INFO
        } else {
            parsed.level
        }

        log.line(parsed.source, level, text)
        if (parsed.text.isBlank()) return

        val diagnosis = diagnoseSessionLine(raw)
        val error = if (level == LogLevel.ERROR) parsed.text else null

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
        val deployed = copyWindowsPayload(source, layout, ComponentType.FEXCORE.wire)
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
            total += copyWindowsPayload(source, layout, type.wire)
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
     * `system32`; Git alone is 9567 of them under a directory that has to keep
     * its shape — `cmd\git.exe` finds its helpers by walking up from where it
     * lives, so flattening or relocating any of it breaks the install in ways
     * that only show up on the first command that shells out.
     *
     * **Several trees now, from a table rather than a constant.** The payload
     * used to be Git and nothing else, so one hardcoded destination was the whole
     * story. It cannot stay that way: a container references exactly one
     * component per type ([ComponentStore.referencesOf] returns a
     * `type -> versionCode` map with no list in it), so Python, Node, PowerShell
     * and the JDK cannot arrive as `Tools` packages of their own — a second one
     * would *replace* the first rather than join it. They arrive inside the same
     * payload, under [TOOLS_LAYOUT]'s directory names, and this walks the table.
     *
     * `C:\Program Files\Git` stays exactly where it was, because that is where
     * Git for Windows puts itself, where every instruction on the internet says
     * it is, and what the launcher's `git-bash` entry depends on. The name is
     * [PrefixRegistry.GIT_DIR], which the machine PATH already lists — the seed
     * names those directories whether or not they exist, so installing the
     * component needs no registry write and no relaunch to be on PATH. Python,
     * Node, PowerShell and Java sit beside it under the same reasoning.
     *
     * Copied rather than symlinked, on the same reasoning as the D3D payload: a
     * link would let a guest program write through into the shared store that
     * every other container on this version reads.
     *
     * Skipped entirely when the component is absent, which is the common case.
     */
    /**
     * Make `C:\Scripts`, which is on the machine PATH and is the user's.
     *
     * **Every launch, not once at provisioning, and both halves of that matter.**
     * A prefix provisioned before [PrefixRegistry.SCRIPTS_DIR] existed has the
     * PATH entry the moment the seed re-runs but would never get the directory;
     * and a directory the user is invited to put files in is one they may also
     * delete, after which the PATH entry points at nothing. Making it every time
     * costs a stat on a directory that almost always already exists.
     *
     * Nothing is ever written into it and nothing is ever removed from it. That
     * is the difference between this and every other directory the runtime
     * creates: `installToolTree` renames a whole tree into place and deletes
     * what was there, which is exactly why a user's own scripts must not live
     * inside a component's directory.
     */
    private suspend fun ensureScriptsDirectory(
        layout: ContainerLayout,
        log: SessionLog,
    ): Unit = withContext(Dispatchers.IO) {
        val dir = File(layout.prefix, GUEST_SCRIPTS_DIR)
        if (dir.isDirectory) return@withContext
        if (dir.mkdirs()) {
            log.line(LogSource.VESSEL, LogLevel.INFO, "scripts: created ${PrefixRegistry.SCRIPTS_DIR}")
        } else {
            log.line(LogSource.VESSEL, LogLevel.WARN, "scripts: could not create ${dir.path}")
        }
    }

    private suspend fun installTools(
        containerId: String,
        layout: ContainerLayout,
        log: SessionLog,
    ): Unit = withContext(Dispatchers.IO) {
        val source = components.directoryFor(containerId, ComponentType.TOOLS) ?: return@withContext

        // **Which payload is this?** The shipped Git-only package puts its tree
        // flat at the component root, with no `Git/` above it, and a phone
        // carrying that package does not stop carrying it because this code
        // changed — the store keeps whatever was installed until a higher
        // version code is adopted. So the layout is detected rather than
        // assumed: a `cmd\git.exe` at the root can only be the old flat tree,
        // and it installs exactly where it always did.
        val trees = if (File(source, LEGACY_TOOLS.sentinel).isFile) {
            listOf(LEGACY_TOOLS)
        } else {
            TOOLS_LAYOUT
        }

        for (tree in trees) {
            val from = if (tree.payloadDir.isEmpty()) source else File(source, tree.payloadDir)
            // A payload that carries some of the trees and not others is a
            // legitimate package, not a broken one — an older `Tools` component
            // predating PowerShell and the JDK is exactly that, and it is what a
            // device that has not adopted 1.1.0 yet is carrying. So an absent
            // directory is silence. A directory
            // that is there without its sentinel is the broken case and says so.
            if (!from.isDirectory) continue
            if (!File(from, tree.sentinel).isFile) {
                log.line(
                    LogSource.VESSEL,
                    LogLevel.WARN,
                    "tools: ${tree.payloadDir}/ has no ${tree.sentinel}; not installed",
                )
                continue
            }
            // Per tree, so one failure does not cost the others. The caller
            // wraps this whole function in the same way and for the same reason.
            runCatching { installToolTree(from, layout, tree, source.name, log) }
                .onFailure {
                    log.line(LogSource.VESSEL, LogLevel.WARN, "tools: ${tree.prefixDir}: ${it.message}")
                }
        }

        // Not a [ToolsTree], deliberately — see [installToolFonts]. Wrapped the
        // same way each tree is, so a font that will not copy does not cost the
        // toolchain that already did.
        runCatching { installToolFonts(source, layout, source.name, log) }
            .onFailure {
                log.line(LogSource.VESSEL, LogLevel.WARN, "tools: fonts: ${it.message}")
            }

        // The other half of the font chain, and it is not in the payload at all —
        // see [linkAndroidFonts]. Wrapped separately from [installToolFonts]
        // because the two fail for unrelated reasons: this one depends on what
        // Android put in `/system/fonts`, which varies by version and OEM.
        runCatching { linkAndroidFonts(layout, log) }
            .onFailure {
                log.line(LogSource.VESSEL, LogLevel.WARN, "tools: system fonts: ${it.message}")
            }
    }

    /**
     * Copy the payload's console face into `windows\Fonts`, leaving whatever else
     * is there alone.
     *
     * **Why this is not a [ToolsTree].** `drive_c\windows\Fonts` is Wine's
     * directory, not ours. [installToolTree] does `deleteTree(target)` and a
     * rename, which is right for `C:\Program Files\Git` — a tree nothing else
     * writes into — and wrong here twice over: it would throw away any font a
     * guest program installed, and it would put this code in a fight with Wine
     * over a directory Wine creates and populates itself. So this copies a *file*
     * in, creates the directory if it is absent, and touches nothing else in it.
     *
     * **Why there is a font to copy at all.** Measured on the device:
     * `prefix/drive_c/windows/Fonts/` was empty, zero files, and Claude Code's
     * TUI drew every horizontal rule as `□□□□□`. That is a missing glyph and not
     * a VT problem — colours and text rendered correctly and `patches/wine/0052`
     * already parses the escape sequences. What conhost had to choose from was
     * the Wine component's `.fon` bitmap faces and non-monospace TTFs, plus the
     * two Android contributes from `/system/fonts` (`CutiveMono.ttf`,
     * `DroidSansMono.ttf`), none of which has a U+2500 block. Tools 1.5.0 ships
     * Cascadia Mono plus the two Unifont files behind it;
     * [PrefixRegistry.consoleColours] names the face in `HKCU\Console\FaceName` and
     * [PrefixRegistry.fontLink] names what it falls back to per glyph.
     *
     * **Every file in `Fonts/`, and the plural is back on purpose.** 1.3.0 shipped
     * two files and this function looped; 1.4.0 shipped one and this function
     * asserted the singular, on the grounds that "conhost uses a single face and
     * does no font linking, so a second file is only ever visible to some other
     * guest program". The second half of that was wrong. Per-glyph fallback is
     * GDI's job rather than conhost's and Wine implements it, so a second file in
     * this directory is reachable from the console the moment
     * `PrefixRegistry.fontLink` names it. 1.5.0 therefore ships three — Cascadia
     * Mono as the face, `unifont` and `unifont_upper` behind it — and this copies
     * whatever it is handed rather than deciding how many there should be. The
     * count assertion is gone because it would now be asserting a payload shape
     * this function has no reason to have an opinion about.
     *
     * **The filenames matter and this function must not rename anything.**
     * `find_face_from_filename` (`win32u/font.c:879-893`) matches a linked font by
     * the basename of its path, so the names in `Fonts/` are the names
     * `PrefixRegistry.FONT_LINK_CHAIN` spells. `build/tools.sh` asserts the two
     * ends against `native/pins.env`; the copy below preserves `font.name`, which
     * is the third link in that chain.
     *
     * **The version stamp, for the reason [installToolTree] grew one.** Keying
     * "already installed" on the file being present let a container reference a
     * new component version while running the old bytes; that cost a full test
     * cycle. Same treatment here: a copy happens unless the font is present *and*
     * the stamp names this component version. It is what carries the 1.3.0 ->
     * 1.4.0 face swap into a prefix that already has a font in this directory.
     *
     * **The stamp sits beside the directory rather than inside it**, and that is
     * not tidiness. `load_directory_fonts` in win32u hands *every* file in
     * `windows\Fonts` to FreeType with no extension filter (win32u/font.c:6560-6571),
     * so a dotfile in there is a failed `FT_New_Face` and a warning on every
     * process start. One level up is a directory nothing scans.
     *
     * **Nothing is ever deleted from Wine's directory**, and that is this
     * function's whole contract rather than an omission. It is also what makes the
     * 1.3.0 -> 1.4.0 -> 1.5.0 history harmless: a container that carried Unifont
     * through 1.3.0 kept the files across 1.4.0's swap, and 1.5.0 now wants them
     * back. Either way this copies the payload's own files over whatever is there
     * under the same names, so the bytes in `windows\Fonts` come from the component
     * the container references and not from an older one that happened to get there
     * first.
     */
    private fun installToolFonts(
        source: File,
        layout: ContainerLayout,
        /** The component's version code, which is the store directory's name. */
        version: String,
        log: SessionLog,
    ) {
        val from = File(source, FONTS_PAYLOAD_DIR)
        // A payload without fonts is a legitimate older `Tools` component — 1.2.0
        // and everything before it — not a broken one, so this is silence.
        if (!from.isDirectory) return
        val fonts = from.listFiles()?.filter { it.isFile }.orEmpty()
        if (fonts.isEmpty()) {
            log.line(LogSource.VESSEL, LogLevel.WARN, "tools: $FONTS_PAYLOAD_DIR/ is empty; no font installed")
            return
        }
        val target = File(layout.prefix, FONTS_PREFIX_DIR)
        val stamp = File(layout.prefix, FONTS_VERSION_STAMP)
        // **Every payload font present, not just one**, and that is what carries a
        // payload whose file *count* changed. 1.4.0 shipped one face and 1.5.0
        // ships three, so a prefix stamped 1.4.0 has Cascadia already there; asking
        // only "is some font present?" would let the stamp be rewritten with the two
        // Unifonts still missing, and the fallback chain would name files that are
        // not in the directory — which fails with one TRACE line per entry and
        // nothing else (`win32u/font.c:2076`).
        if (fonts.all { File(target, it.name).isFile } &&
            runCatching { stamp.readText().trim() }.getOrNull() == version
        ) {
            log.line(LogSource.VESSEL, LogLevel.INFO, "tools: $FONTS_PREFIX_DIR already installed from $version")
            return
        }

        if (!target.isDirectory && !target.mkdirs()) error("could not create ${target.path}")
        // Overwriting, and no staging directory and no rename — the whole
        // difference from [installToolTree]. There is nothing here to swap
        // atomically: these are individual files, and a partly written one is caught
        // next launch by the stamp not matching.
        for (font in fonts) {
            font.copyTo(File(target, font.name), overwrite = true)
        }
        // After every copy, so a launch killed part-way through leaves no stamp
        // claiming a version the directory does not hold.
        runCatching { stamp.writeText(version) }

        log.line(
            LogSource.VESSEL,
            LogLevel.INFO,
            "tools: ${fonts.size} font(s) into $FONTS_PREFIX_DIR " +
                "(${fonts.joinToString(", ") { "${it.name} ${it.length()}b" }})",
        )
    }

    /**
     * Symlink the device's own font files into `windows\Fonts`.
     *
     * **The middle tier of `PrefixRegistry.FONT_LINK_CHAIN`, and the only one that
     * is not in a component.** Android ships a Noto set in [ANDROID_FONTS_DIR] —
     * measured on the device, 211 files — and one of them earns a place between
     * Cascadia Mono and Unifont: `NotoSansSymbols-Regular-Subsetted.ttf`, 708,968
     * bytes and 4,616 codepoints, covering all five spinner glyphs, all five status
     * marks, and 100% of Box Drawing, Block Elements and Braille. Those are on
     * Claude Code's screen constantly, and drawing them from real typography rather
     * than from Unifont's 16x16 bitmap grid is the whole point of the tier.
     *
     * **Symlinked and not copied**, which is why this is a separate step from
     * [installToolFonts]. The files are already on the device; copying would
     * duplicate them into every container's storage for nothing. Same pattern as
     * [linkVesselTmp] and [linkVkd3dCache], and the links go into the directory
     * Wine enumerates rather than beside it, because `load_directory_fonts` is what
     * registers a font and it hands every non-directory entry in `windows\Fonts` to
     * FreeType with no extension filter at all. A unix symlink to a file is a
     * regular file as far as `NtQueryDirectoryFile` is concerned, so a link
     * registers exactly as a copy would.
     *
     * **Missing files are expected, not exceptional, and that is the reason the
     * chain is ordered the way it is.** `/system/fonts` filenames vary by Android
     * version and by OEM, so a name that is right on one device is absent on
     * another. An entry in the SystemLink value that Wine cannot resolve is
     * discarded with a `TRACE` line and nothing else (`win32u/font.c:2076`), so a
     * missing file degrades to the next tier automatically — and the last tier is
     * Unifont, which ships in the payload and therefore cannot be missing. So this
     * skips what is not there rather than failing, and logs at WARN which ones,
     * because a device that silently lost this tier should still be diagnosable
     * from a log.
     *
     * **No stamp, unlike [installToolFonts].** There is nothing to be stale: a
     * symlink either points at the right path or it does not, and the check below is
     * that comparison. Re-pointing an existing link rather than leaving it is
     * deliberate — [linkVesselTmp] can skip that because its target never moves,
     * whereas a link here could have been made by an earlier revision naming a
     * different source directory.
     *
     * **Nothing here is verified on a device.** That these names exist was measured;
     * that Wine registers a symlinked font, and that the linked glyphs are what
     * conhost then draws, is read out of `load_directory_fonts` and
     * `get_glyph_index_linked` and not observed.
     */
    private fun linkAndroidFonts(layout: ContainerLayout, log: SessionLog) {
        val target = File(layout.prefix, FONTS_PREFIX_DIR)
        if (!target.isDirectory && !target.mkdirs()) error("could not create ${target.path}")

        val absent = mutableListOf<String>()
        val linked = mutableListOf<String>()
        for (name in PrefixRegistry.ANDROID_LINKED_FONTS) {
            val source = File(ANDROID_FONTS_DIR, name)
            if (!source.isFile) {
                absent += name
                continue
            }
            val link = File(target, name).toPath()
            val wanted = source.toPath()
            runCatching {
                if (Files.isSymbolicLink(link)) {
                    if (Files.readSymbolicLink(link) == wanted) return@runCatching
                    Files.delete(link)
                } else if (Files.exists(link)) {
                    // A real file under this name is either a font a guest program
                    // installed or one a payload copied. Either way it is not ours
                    // to replace -- this function's contract, like
                    // [installToolFonts]', is that it does not remove things from
                    // Wine's directory.
                    log.line(
                        LogSource.VESSEL,
                        LogLevel.WARN,
                        "tools: $FONTS_PREFIX_DIR/$name is a real file, not a link; left alone",
                    )
                    return@runCatching
                }
                Files.createSymbolicLink(link, wanted)
                linked += name
            }.onFailure {
                absent += "$name (${it.message})"
            }
        }

        if (linked.isNotEmpty()) {
            log.line(
                LogSource.VESSEL,
                LogLevel.INFO,
                "tools: linked ${linked.size} system font(s) into $FONTS_PREFIX_DIR " +
                    "(${linked.joinToString(", ")})",
            )
        }
        // WARN and not INFO: this is the tier that makes the spinners and ticks look
        // drawn rather than plotted, and losing it is invisible on screen -- the
        // glyphs still render, out of Unifont, one tier down.
        if (absent.isNotEmpty()) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "tools: not in $ANDROID_FONTS_DIR on this device, so the font-link " +
                    "chain falls through to Unifont for these: ${absent.joinToString(", ")}",
            )
        }
    }

    /**
     * Link Wine's own faces into `windows\Fonts`, where DirectWrite can see them.
     *
     * Wine ships Tahoma, Symbol, Webdings, Wingdings and Marlett and registers
     * them by bare filename; `win32u` then resolves that name against two
     * directories and DirectWrite against one, so GDI finds them and DirectWrite
     * does not. See [PrefixRegistry.WINE_LINKED_FONTS] for the code on both sides
     * and why this is a link rather than an entry in `patches/wine/0063`'s alias
     * table.
     *
     * Same contract as [linkAndroidFonts], which this is modelled on: a real file
     * already under the name is left alone, because it is either a font a guest
     * installed or one a payload copied and neither is ours to replace.
     */
    private suspend fun linkWineFonts(
        containerId: String,
        layout: ContainerLayout,
        log: SessionLog,
    ): Unit = withContext(Dispatchers.IO) {
        val wine = components.directoryFor(containerId, ComponentType.WINE) ?: return@withContext
        val source = File(wine, WINE_FONTS_DIR)
        if (!source.isDirectory) {
            log.line(LogSource.VESSEL, LogLevel.WARN, "wine fonts: no ${source.path}")
            return@withContext
        }

        val target = File(layout.prefix, FONTS_PREFIX_DIR)
        if (!target.isDirectory && !target.mkdirs()) error("could not create ${target.path}")

        val absent = mutableListOf<String>()
        val linked = mutableListOf<String>()
        for ((shipped, installed) in PrefixRegistry.WINE_LINKED_FONTS) {
            val file = File(source, shipped)
            if (!file.isFile) {
                absent += shipped
                continue
            }
            val link = File(target, installed).toPath()
            val wanted = file.toPath()
            runCatching {
                if (Files.isSymbolicLink(link)) {
                    // Re-pointed rather than kept: the link names a component
                    // directory that carries a version code, so one left alone
                    // would still resolve into the Wine build this container has
                    // stopped running.
                    if (Files.readSymbolicLink(link) == wanted) return@runCatching
                    Files.delete(link)
                } else if (Files.exists(link)) {
                    log.line(
                        LogSource.VESSEL,
                        LogLevel.WARN,
                        "wine fonts: $FONTS_PREFIX_DIR/$installed is a real file, not a link; left alone",
                    )
                    return@runCatching
                }
                Files.createSymbolicLink(link, wanted)
                linked += installed
            }.onFailure {
                absent += "$installed (${it.message})"
            }
        }

        if (linked.isNotEmpty()) {
            log.line(
                LogSource.VESSEL,
                LogLevel.INFO,
                "wine fonts: linked ${linked.size} into $FONTS_PREFIX_DIR " +
                    "(${linked.joinToString(", ")})",
            )
        }
        // WARN, because every name here is one `wine.inf` has already registered:
        // one missing is a `DWRITE_E_FILENOTFOUND` at every startup and a family
        // that falls through to `patches/wine/0063`'s alias with the wrong metrics.
        if (absent.isNotEmpty()) {
            log.line(
                LogSource.VESSEL,
                LogLevel.WARN,
                "wine fonts: not in ${source.path}, so DirectWrite cannot see " +
                    "these: ${absent.joinToString(", ")}",
            )
        }
    }

    /** Copy one tree of the Tools payload into the prefix. See [installTools]. */
    private fun installToolTree(
        source: File,
        layout: ContainerLayout,
        tree: ToolsTree,
        /** The component's version code, which is the store directory's name. */
        version: String,
        log: SessionLog,
    ) {
        val target = File(layout.prefix, tree.prefixDir)

        // **Present already *from this version*?** The sentinel alone is not the
        // question, and answering it that way cost a whole test cycle.
        //
        // A container adopts a new Tools component by version code, and nothing
        // in the prefix changes when it does — the trees are copies, made here.
        // Keyed on the sentinel alone, this function looked at a `pwsh.exe` that
        // was already there and returned "already installed", so a container
        // referencing 1200000 kept running 1100000's binaries. Measured: the
        // store held the ARM64 payload, `provisioned.json` said `Tools: 1200000`,
        // and the `pwsh.exe` in the prefix still reported Machine 0x8664. The log
        // line said the tree was installed, which is the shape of every silent
        // no-op this project keeps finding: something reports success and changes
        // nothing.
        //
        // The stamp is the version code the tree was copied from. Absent means a
        // tree written before this existed, and that re-copies once — cheap, and
        // the alternative is trusting bytes whose provenance nobody recorded.
        val stamp = File(target, TOOLS_VERSION_STAMP)
        if (File(target, tree.sentinel).isFile &&
            runCatching { stamp.readText().trim() }.getOrNull() == version
        ) {
            log.line(
                LogSource.VESSEL,
                LogLevel.INFO,
                "tools: ${tree.prefixDir} already installed from $version",
            )
            return
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

        // After the rename, so a launch killed mid-copy leaves no stamp claiming
        // a version the tree does not hold. Best-effort: a tree that is right
        // with no stamp costs one extra copy next launch, which is the safe
        // direction to fail in.
        runCatching { File(target, TOOLS_VERSION_STAMP).writeText(version) }

        if (tree.msys2) {
            // **The three directories MSYS2's first run tries to make and
            // cannot.** Git's tree only, because Git's tree is where MSYS2 is —
            // Python and Node are plain Windows programs with no POSIX runtime
            // under them and nothing that reads `/etc/fstab`.
            //
            // Opening Git Bash printed, before anything else:
            //
            //   mkdir: cannot create directory '/dev/shm': Read-only file system
            //   Creating /dev/shm directory failed.
            //   POSIX semaphores and POSIX shared memory will not work
            //   mkdir: cannot create directory '/dev/mqueue': Read-only file system
            //
            // That is `/etc/post-install/01-devices.post`, which Git for Windows
            // runs once on first launch. `mkdir` is coreutils, not the runtime,
            // so this is a script failing rather than the emulator — and
            // `/etc/fstab` here is `none / cygdrive`, which leaves the MSYS2
            // root as the directory holding `usr\bin\msys-2.0.dll`: this one.
            //
            // Making them in advance turns the script's `mkdir -p` into a no-op
            // that succeeds. It costs three empty directories and removes four
            // lines of alarming red text from the first thing a user sees in a
            // shell they just opened. If the warning survives this, the path is
            // *not* being resolved against the install root and that is worth
            // knowing — the directories are harmless either way, and nothing
            // here depends on them.
            listOf("dev/shm", "dev/mqueue", "tmp").forEach { File(target, it).mkdirs() }
        }

        val files = target.walkTopDown().count { it.isFile }
        log.line(LogSource.VESSEL, LogLevel.INFO, "tools: $files file(s) into ${tree.prefixDir}")
    }

    /**
     * One tree inside the Tools payload, and where in the prefix it belongs.
     *
     * The table this describes ([TOOLS_LAYOUT]) is the contract between
     * `build/tools.sh`, which creates these directories, and
     * [PrefixRegistry.toolsPath], which puts them on `PATH`. All three have to
     * agree, and there is no runtime check that they do — a disagreement is a
     * program that is installed and unreachable.
     */
    private data class ToolsTree(
        /** Directory at the payload root. Empty means the payload *is* the tree. */
        val payloadDir: String,
        /** Destination under the prefix, as a relative path. */
        val prefixDir: String,
        /** The one file whose presence means this tree finished copying. */
        val sentinel: String,
        /** Whether MSYS2 lives here and wants its first-run directories made. */
        val msys2: Boolean = false,
    )

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
    private fun copyWindowsPayload(source: File, layout: ContainerLayout, key: String): Deployed {
        val windows = File(layout.prefix, DRIVE_C_WINDOWS)
        val system32 = File(windows, SYSTEM32)
        val groups = listOf(
            source.listFiles().orEmpty() to system32,
            File(source, SYSTEM32).listFiles().orEmpty() to system32,
            File(source, SYSWOW64).listFiles().orEmpty() to File(windows, SYSWOW64),
        )

        // The payload's own content hash where the package carries one, and the
        // directory name — the version code — where it does not.
        //
        // The version is a *name*, and a name cannot answer "are these the same
        // bytes": FEX 260801 and 260802 were both exactly 5,152,768 bytes and
        // differed by a thread_local that faulted at startup. The hash is
        // computed once at package time over the file list and their contents,
        // so comparing it is a string compare rather than a re-read of 68 MB of
        // Wine on every launch.
        //
        // The fallback is not a formality. Components installed before
        // `payloadSha256` existed have no digest, and treating that as a
        // mismatch would re-stage every payload on the next launch of every
        // container on the device.
        val version = payloadIdentity(source)
        val staged = stagedVersions(layout)
        val alreadyStaged = staged[key] == version &&
            groups.all { (files, destination) ->
                files.none { it.isFile && it.name.endsWith(DLL_SUFFIX) } ||
                    files.filter { it.isFile && it.name.endsWith(DLL_SUFFIX) }
                        .all { File(destination, it.name).isFile }
            }

        var copied = 0
        var present = 0
        for ((files, destination) in groups) {
            val dlls = files.filter { it.isFile && it.name.endsWith(DLL_SUFFIX) }
            if (dlls.isEmpty()) continue
            if (alreadyStaged) {
                present += dlls.size
                continue
            }
            if (!destination.isDirectory && !destination.mkdirs()) {
                error("could not create ${destination.path}")
            }
            for (file in dlls) {
                file.copyTo(File(destination, file.name), overwrite = true)
                copied++
            }
        }
        if (!alreadyStaged) recordStaged(layout, key, version)
        return Deployed(copied, present)
    }

    /**
     * Which version of each component was last written into this prefix.
     *
     * **This exists because "same size" was being used to mean "same file", and
     * it is not.** The old check skipped a copy whenever the target existed and
     * `target.length() == file.length()`. FEX `260801` and `260802` are both
     * exactly 5,152,768 bytes and have different SHA-256s — one carries a
     * `thread_local` that faults during startup, the other does not — so the
     * newer component installed correctly, the prefix kept the older binary, and
     * two consecutive test sessions crashed at a byte-identical address while
     * apparently running different builds. Nothing anywhere said the prefix was
     * stale; the component list showed both versions present and the newest
     * adopted.
     *
     * A recompile very often changes no size at all, so this was not an unlucky
     * collision — it is the normal case for a patch that adds no code.
     *
     * Version is the right identity and hashing is not needed: components are
     * unpacked into immutable, version-named directories, so the name settles it
     * in one string comparison rather than by reading 68 MB of Wine per session.
     * The presence check beside it covers a prefix that was emptied underneath a
     * marker that survived.
     *
     * Kept in `base/` rather than in the prefix: it is Vessel's bookkeeping, and
     * the guest has no business seeing it on any drive.
     */
    /**
     * What identifies this component's bytes: its payload hash, or its version
     * code when the package predates the hash.
     *
     * Read straight from the component directory's own `profile.json` rather
     * than through [ComponentStore], because this runs per component per launch
     * and the store's accessors migrate and re-scan. A malformed or missing
     * manifest falls back to the directory name, which is the behaviour this
     * replaced and is never worse than it.
     */
    private fun payloadIdentity(source: File): String {
        val profile = File(source, WCP_PROFILE)
        if (!profile.isFile) return source.name
        return runCatching {
            json.decodeFromString(WcpProfile.serializer(), profile.readText())
                .payloadSha256
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: source.name
    }

    private fun stagedVersions(layout: ContainerLayout): Map<String, String> {
        val file = File(layout.base, STAGED_COMPONENTS)
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Record [key] as staged at [version]; see [stagedVersions]. */
    private fun recordStaged(layout: ContainerLayout, key: String, version: String) {
        runCatching {
            val updated = stagedVersions(layout) + (key to version)
            File(layout.base, STAGED_COMPONENTS)
                .writeText(updated.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
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

    /**
     * The installed FEX package, for the code cache. Null when none is installed.
     *
     * Null here is not the same as [installFex] failing — that step is the one
     * that refuses a session with no FEX, and it runs later with the error
     * message. This is only asked for two facts: what to key the cache on, and
     * whether the package carries the offline compiler.
     *
     * [FexPackage.identity] is the store's version code plus the byte lengths of
     * the two CPU DLLs. See the field for what that catches and what it does not.
     */
    private suspend fun fexPackage(containerId: String): FexPackage? {
        val directory = components.directoryFor(containerId, ComponentType.FEXCORE) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val compiler = File(directory, FEX_OFFLINE_COMPILER).takeIf { it.isFile }
                val everyCompiler = directory.listFiles { f: File ->
                    f.isFile && f.name.startsWith("FEXOfflineCompiler") && f.name.endsWith(".exe")
                }?.sorted().orEmpty()
                FexPackage(
                    directory = directory,
                    identity = listOf(
                        directory.name,
                        File(directory, ARM64EC_FEX).length().toString(),
                        File(directory, WOW64_FEX).length().toString(),
                    ).joinToString("/"),
                    offlineCompiler = compiler,
                    compilers = everyCompiler,
                )
            }.getOrNull()
        }
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

    /**
     * An int param, or null when neither the container nor the manifest answers.
     *
     * No fallback of its own, unlike [flag]: the two callers are shader constants
     * whose right value is Qualcomm's, and [UpscalerRequest]'s own defaults are
     * already those. Inventing a number here would put a third copy of them in
     * the codebase and make a disagreement between the three silent.
     */
    private fun count(profile: ContainerProfile, manifest: ParamManifest?, key: String): Int? =
        (profile.params[key] as? ParamValue.Count)?.value
            ?: (manifest?.spec(key)?.defaultValue() as? ParamValue.Count)?.value

    /**
     * The container's upscaler settings, in the shader's units.
     *
     * The manifest collects the two clamps in 255ths because that is how
     * Qualcomm's file writes them and how anyone comparing against it will think
     * of them; the shader wants fractions. Converted here, once, rather than at
     * either end — [UpscalerRequest] documents which it holds.
     *
     * A sharpness that will not parse falls back to the default rather than
     * throwing. It is an enum in the manifest so the only way to reach this is a
     * container file edited by hand, and a session that refuses to start over a
     * sharpening constant would be a much worse answer than one that starts at
     * 2.0 and says so in the log line above.
     */
    /**
     * The container's frame-generation multiple, as a count of presented frames.
     *
     * `off` and anything unparseable both mean 0, which the compositor reads as
     * "do not". A container file edited by hand is the only way to reach the
     * second case — the manifest collects this as an enum — and a session that
     * refused to start over it would be a much worse answer than one that starts
     * without predicted frames.
     */
    private fun parseFrameGeneration(value: String?): Int {
        if (value == null || value == DisplayParams.FRAME_GENERATION_OFF) return 0
        return value.trim().toIntOrNull()?.takeIf { it >= 2 } ?: 0
    }

    private fun upscalerOf(profile: ContainerProfile, manifest: ParamManifest?): UpscalerRequest {
        val fallback = UpscalerRequest()
        val choice = text(profile, manifest, DisplayParams.UPSCALER)
        return UpscalerRequest(
            sgsr = choice != DisplayParams.UPSCALER_BILINEAR,
            edgeDirection = flag(profile, manifest, DisplayParams.SGSR_EDGE_DIRECTION),
            edgeThreshold = count(profile, manifest, DisplayParams.SGSR_EDGE_THRESHOLD)
                ?.let { it / 255f } ?: fallback.edgeThreshold,
            sharpness = text(profile, manifest, DisplayParams.SGSR_SHARPNESS)
                ?.trim()?.toFloatOrNull() ?: fallback.sharpness,
            maxDelta = count(profile, manifest, DisplayParams.SGSR_MAX_DELTA)
                ?.let { it / 255f } ?: fallback.maxDelta,
        )
    }

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
         * `FEXOfflineCompiler64.exe` in the installed FEX package, or null when
         * the package has none. Teardown runs it; see [generateCodeCache].
         *
         * Held on the plan rather than looked up in teardown because teardown has
         * no container id — it has already stopped being a session by then — and
         * because resolving a component is a suspend call against a store this
         * class should not be reaching into while it is shutting one down.
         */
        val offlineCompiler: File? = null,
        /**
         * `caches/fex/<digest>/` — where the code cache really lives.
         *
         * The guest is told `C:\vessel\fexcache\` ([FEX_CACHE_DOS_PATH]) because
         * that is the only shape FEX can both write and read, and a symlink in
         * the prefix points at this. Held separately because the environment no
         * longer carries the host path in any form, and [generateCodeCache]
         * needs to count codemaps with a `File` before it decides to run.
         */
        val fexCacheHost: File? = null,
        /**
         * The bindings this session starts with, already resolved against the
         * profile document. Pushed onto the display seam once the server is up;
         * the panel may replace it at any point after that.
         */
        val input: InputProfile = InputProfile.Default,
        /** Whether this container draws the touch overlay. See [ContainerInput]. */
        val touchVisible: Boolean = false,
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

        /** Which component version is currently in the prefix. See stagedVersions. */
        const val STAGED_COMPONENTS = "staged-components"

        /**
         * Whether to compile the FEX code cache beside a running session.
         *
         * False, and the long comment at the launch site has the measurements.
         * The short version: it took 210-326 s on every session that ran it,
         * was cancelled by teardown every time, produced no cache at all, and
         * cost one saturated core of the eight for the whole session.
         *
         * A constant rather than a deletion because the compiler works -- it
         * parses and merges correctly, it simply never gets to finish -- and
         * what it needs is a place to run, not a rewrite.
         *
         * **Back on, deliberately, with that cost accepted.** The cache is only
         * ever built by a session long enough to outlast the compile, and a
         * session that never runs it never gets one, so leaving it off means it
         * can never come into existence. On means a long run can finish it and
         * every later session loads it.
         *
         * The price is stated once here so it is not rediscovered: a second
         * Wine process in the prefix for the length of the compile, one core of
         * the eight, and critical-section timeout lines that carry no process
         * name -- so while it is running, "which process holds the heap" is not
         * answerable from the log. Turn it off for a session whose purpose is
         * diagnosing a hang.
         *
         * **It does produce a cache now, and the "produced no cache at all"
         * above is history rather than the current state.** A Requiem session
         * leaves ~11 MB under `caches/fex/<digest>/codemap`, split the way the
         * compiler works: finished modules in `ready/` -- kernelbase, winmm,
         * dstorage, sl.dlss_g, amd_fidelityfx_loader_dx12 -- and the executable
         * itself still accumulating in `new/`. That layout is the answer to
         * whether a killed session loses everything: it does not, because
         * `ready/` is per-module and already written.
         */
        const val CODE_CACHE_DURING_SESSION: Boolean = true
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

        /**
         * FEX's ahead-of-time cache compiler, packaged by `build/fex.sh`.
         *
         * The `64` is FEX's own naming and means ARM64EC, not x86-64 —
         * `FEXOfflineCompiler/CMakeLists.txt` names the arm64ec build `…64` and
         * the WoW64 one `…32`. `process-all` re-execs the sibling that matches
         * each binary's bitness, so a 32-bit guest needs `…32.exe` in the same
         * directory; this build ships only the 64-bit one, and a 32-bit binary
         * gets a logged "cache generation failed" rather than a broken cache.
         */
        const val FEX_OFFLINE_COMPILER = "FEXOfflineCompiler64.exe"

        /** Read back off the plan's environment; [app.vessel.core.fexCacheKey] wrote it. */
        const val FEX_CACHE_LOCATION_ENV = "FEX_APP_CACHE_LOCATION"

        /**
         * How long the catch-up compile may run before it is given up on.
         *
         * **Was 120 s, which was a Metro number.** It is not a teardown budget
         * any more — nothing waits on this — so the only thing it has to be is
         * longer than a real compile. Metro's code map is 15,399 blocks and
         * finished inside the old limit; Resident Evil Requiem's is **394,170**,
         * twenty-five times bigger, and was killed at 120 s on every single run:
         *
         *     IW Writing 394170 blocks to C:\vessel\fexcache\codemap/ready/re9.exe-…
         *     WV the FEX code cache did not finish in 120 s and was killed
         *
         * Killed at the same point each time means the cache never appeared, so
         * every launch paid full translation again — `caches/fex/…/cache` was
         * still empty after seven sessions.
         *
         * A limit this long is safe because the job runs beside the desktop and
         * is cancelled at teardown: it can waste background CPU, it cannot delay
         * a launch or a stop. It exists only so a wedged compiler is eventually
         * reaped. A run that still exceeds it loses that merge and nothing else
         * — `process-all` keeps the reference codemaps it already wrote and
         * resumes next start.
         */
        const val CODE_CACHE_TIMEOUT_MS = 900_000L

        /** Long enough for a prefix with processes to shut down, short enough to feel like Stop. */
        const val KILL_TIMEOUT_MS = 8_000L

        /**
         * How long a program waits for the desktop to appear before it gives up
         * waiting and starts anyway.
         *
         * Generous because the thing being waited for is a whole Wine desktop on
         * a cold prefix, and cheap because the wait ends the instant the window
         * maps — on this device that is well under a second, and the timeout is
         * only ever paid by a session that was going to fail regardless.
         */
        const val DESKTOP_READY_TIMEOUT_MS = 20_000L

        const val PUBLISH_INTERVAL_MS = 150L

        const val WAKE_LOCK_TAG = "vessel:session"

        /**
         * What the Tools payload carries and where each part of it goes.
         *
         * The directory names on the left are `build/tools.sh`'s output layout;
         * the paths on the right are [PrefixRegistry.GIT_DIR],
         * [PrefixRegistry.PYTHON_DIR], [PrefixRegistry.NODE_DIR],
         * [PrefixRegistry.PWSH_DIR] and [PrefixRegistry.JAVA_DIR] written as
         * paths under the prefix. Those constants are what the machine `PATH`
         * seed names, so this table and that seed have to say the same thing.
         *
         * The sentinels are each tree's own entry point rather than any file
         * that happens to be present: `cmd\git.exe` is what the launcher runs,
         * `python.exe`, `node.exe`, `pwsh.exe` and `bin\java.exe` are what
         * `PATH` resolves. A tree whose sentinel is missing is not a tree worth
         * copying 400 MB for.
         */
        private val TOOLS_LAYOUT = listOf(
            ToolsTree("Git", "drive_c/Program Files/Git", "cmd/git.exe", msys2 = true),
            ToolsTree("Python", "drive_c/Program Files/Python", "python.exe"),
            ToolsTree("Node", "drive_c/Program Files/Node", "node.exe"),
            // `Pwsh/` in the payload, `PowerShell` in the prefix, and the
            // asymmetry is deliberate on both ends: the payload directory is
            // short because it is only ever typed in `build/tools.sh`, and the
            // installed directory is spelled out because it is what a user sees
            // in `C:\Program Files` and types into a path.
            //
            // This is the tree that makes Claude Code's shell tool work.
            // Claude Code runs commands through a Bash tool that means Git Bash
            // on Windows, or a PowerShell tool when Git for Windows is absent;
            // [app.vessel.ui.shell.TerminalProfile] records the measurement that
            // rules the first one out here — a `bash --login -i` child spinning
            // at 98% of a core inside MSYS2's `fork()` emulation, with
            // `/etc/profile` forking before the shell ever reaches a prompt.
            // `pwsh.exe` forks for nothing.
            ToolsTree("Pwsh", "drive_c/Program Files/PowerShell", "pwsh.exe"),
            // `bin/java.exe` rather than a file at the root, because a JDK has
            // no single entry point at its root — the launchers are all under
            // `bin`, which is also the one directory of this tree that goes on
            // PATH ([PrefixRegistry.JAVA_DIR] plus `\bin`).
            ToolsTree("Java", "drive_c/Program Files/Java", "bin/java.exe"),
            // The browser, and the two things that make it unlike everything
            // above it. It is not a toolchain -- nothing resolves it by name and
            // it is not on `PATH`; what reaches it is
            // [app.vessel.ui.shell.TerminalProfile.PALE_MOON], which names this
            // exact `palemoon.exe` as its `installedAt`.
            //
            // And it is x86-64 where the other five are ARM64, on purpose. An
            // x86-64 process loads our ARM64EC graphics DLLs natively; a classic
            // ARM64 one gets STATUS_INVALID_IMAGE_FORMAT and cannot. Firefox was
            // here first, was ARM64, and never drew a window -- native/pins.env
            // carries that measurement.
            ToolsTree("PaleMoon", "drive_c/Program Files/Pale Moon", "palemoon.exe"),
        )

        /**
         * The payload that shipped before the bundle: Git's tree flat at the
         * component root.
         *
         * Kept because an installed component does not change when this code
         * does. `dist/git-2.55.0.3-arm64.wcp` has no `Git/` directory in it — it
         * is the Git for Windows tree re-tarred as-is — and a phone carrying it
         * keeps carrying it until a package with a higher version code is
         * adopted. Dropping this branch would turn every such device's Git into
         * a component that installs nothing, silently, on the next launch.
         */
        private val LEGACY_TOOLS = ToolsTree(
            payloadDir = "",
            prefixDir = "drive_c/Program Files/Git",
            sentinel = "cmd/git.exe",
            msys2 = true,
        )

        /** [app.vessel.core.PrefixRegistry.SCRIPTS_DIR] as a path under the prefix. */
        const val GUEST_SCRIPTS_DIR = "drive_c/Scripts"

        /** Where a tools tree is assembled before it is renamed into place. */
        const val STAGING_SUFFIX = ".staging"

        /**
         * Which Tools version a prefix tree was copied from.
         *
         * A dotfile inside the tree rather than a record beside it, so it cannot
         * outlive what it describes: deleting the tree deletes the claim about
         * it. Named with a leading dot so it sorts out of the way in a directory
         * a user may open in the Files tab, and so nothing in Git, Python, Node,
         * PowerShell or the JDK collides with it.
         */
        const val TOOLS_VERSION_STAMP = ".vessel-tools-version"

        /**
         * The Tools payload's font directory, holding the one console face.
         *
         * Not in [TOOLS_LAYOUT], and [installToolFonts] says why: the
         * destination belongs to Wine, so it is written into rather than
         * replaced. `build/tools.sh` creates the payload half and puts exactly one
         * file in it.
         */
        /**
         * Android's own font directory, which win32u already reads.
         *
         * `freetype_load_fonts` scans `/system/fonts` on Android, so the faces in
         * here are registered with GDI regardless of this app -- which is how
         * `CutiveMono.ttf` and `DroidSansMono.ttf` were the two monospace faces
         * conhost had to choose from before Tools 1.3.0 shipped one. [linkAndroidFonts]
         * links out of it anyway rather than relying on that scan, because a
         * SystemLink entry is resolved by filename against fonts registered from
         * `windows\Fonts` and the two scans are separate.
         */
        const val ANDROID_FONTS_DIR = "/system/fonts"

        const val FONTS_PAYLOAD_DIR = "Fonts"

        /** Wine's own font directory — see [FONTS_PAYLOAD_DIR]. */
        const val FONTS_PREFIX_DIR = "drive_c/windows/Fonts"

        /**
         * Wine's own font directory inside the component, relative to its root.
         *
         * The directory `win32u` falls back to and DirectWrite never looks in --
         * see [PrefixRegistry.WINE_LINKED_FONTS].
         */
        const val WINE_FONTS_DIR = "share/wine/fonts"

        /**
         * Which Tools version the fonts were copied from.
         *
         * Beside [FONTS_PREFIX_DIR] and not inside it, unlike
         * [TOOLS_VERSION_STAMP]: win32u's `load_directory_fonts` hands every file
         * in `windows\Fonts` to FreeType with no extension filter, so a stamp
         * living there would be a failed `FT_New_Face` and a warning on every
         * process start. `drive_c\windows` is not scanned.
         */
        const val FONTS_VERSION_STAMP = "drive_c/windows/.vessel-fonts-version"

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

/**
 * The resolvers this phone is currently using, as address literals.
 *
 * **The Java half of a problem the guest cannot solve.** Android has no
 * `/etc/resolv.conf` — absent, not empty — so Wine's resolver reports no
 * servers, `DnsQueryConfig` fails, and anything that reads the DNS
 * *configuration* rather than merely resolving a name gives up. Chromium is the
 * one that says so out loud: `Failed to read DnsConfig`, on a ten-second retry,
 * forever. `dlls/dnsapi/android.c` explains why native code cannot answer —
 * netd owns the list, `net.dns1..4` went away in Android 8 — and names
 * `ConnectivityManager`/`LinkProperties` as the only interface that can. This is
 * that call.
 *
 * Two details the address strings need before C can parse them:
 *
 *  - **The scope suffix is stripped.** An IPv6 link-local arrives from
 *    [java.net.InetAddress.getHostAddress] as `fe80::1%wlan0`, and `inet_pton`
 *    rejects the `%wlan0`. Keeping it would silently drop exactly the servers a
 *    phone on Wi-Fi is most likely to be handed.
 *  - **Duplicates are removed**, because a device on Wi-Fi and cellular at once
 *    reports the same resolver twice and the guest would then query it twice.
 *
 * Every failure returns an empty list rather than throwing: no permission, no
 * active network, airplane mode. A session that cannot name its resolvers still
 * resolves names — queries go to netd either way — so this must never be the
 * reason a container fails to start.
 */
private fun deviceDnsServers(context: Context): List<String> = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return@runCatching emptyList()
    val active = manager.activeNetwork ?: return@runCatching emptyList()
    manager.getLinkProperties(active)?.dnsServers.orEmpty()
        .mapNotNull { it.hostAddress }
        .map { it.substringBefore('%') }
        .filter { it.isNotBlank() }
        .distinct()
}.getOrDefault(emptyList())

/** The fields of a Turnip package's `meta.json` this app acts on. */
@Serializable
private data class AdrenotoolsMeta(val libraryName: String? = null)
