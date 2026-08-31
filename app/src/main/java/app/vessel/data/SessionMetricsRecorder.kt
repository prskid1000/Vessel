package app.vessel.data

import android.os.SystemClock
import app.vessel.core.GfxRunSummary
import app.vessel.core.LogLevel
import app.vessel.core.SessionDisplayServer
import app.vessel.core.MetricHistory
import app.vessel.core.MetricSource
import app.vessel.core.gfxStatsFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The window the graphs draw and the numbers they annotate it with.
 *
 * One type for both the live case and a run replayed from its trace, because
 * every card draws them identically and only the caption differs. [replayed] is
 * what the caption reads.
 */
data class SessionMetricsState(
    /** Which session this window belongs to; null before anything has run. */
    val containerId: String? = null,
    val startedAt: Long? = null,
    /** Whether samples are still arriving. */
    val running: Boolean = false,
    /** True when this came off disk rather than from the live sampler. */
    val replayed: Boolean = false,
    val history: MetricHistory = MetricHistory(),
    val sources: List<MetricSource> = emptyList(),
    /** Each core's rated maximum, so every core's graph has its own honest ceiling. */
    val coreCeilingsMhz: List<Int?> = emptyList(),
) {
    /** The fastest core on the part — the ceiling for the aggregate clock graph. */
    val clockCeilingMhz: Int? get() = coreCeilingsMhz.filterNotNull().maxOrNull()

    /** Whether this window is the live one for a given log. */
    fun isLiveFor(containerId: String, startedAt: Long): Boolean =
        running && this.containerId == containerId && this.startedAt == startedAt
}

/**
 * Samples a running session, once a second, into a trace file beside its log.
 *
 * ## Why this is a singleton and not a view model
 *
 * The trace has to keep being written with no UI attached — a session's metrics
 * are most interesting for the run nobody was watching. Tying sampling to a
 * screen would mean the graph and the trace disagreed about what happened, and
 * the disagreement would always be in the direction of the trace being empty
 * exactly when it was needed.
 *
 * It observes [SessionRuntime] rather than being driven by it, so nothing in the
 * launch path has to know this exists. The cost is that the singleton must be
 * constructed before it can observe anything; it is injected by the session view
 * models, which are the only screens from which a session can be reached.
 *
 * ## Cost, which is the constraint that shaped this
 *
 * This runs beside an emulator on a phone, where CPU is the scarce resource and
 * the thing being measured is the thing being competed with. Three consequences:
 *
 *  1. **Nothing is sampled unless a session is `RUNNING`.** Not while preparing,
 *     not after it exits. The loop is a coroutine that is cancelled outright.
 *  2. **The rate follows the audience.** [SLOW_INTERVAL_MS] when no screen is
 *     looking, which is enough to leave a usable trace; [FAST_INTERVAL_MS] while
 *     a graph is on screen. A closed rail costs one tick every ten seconds.
 *  3. **The trace is appended, never rewritten.** One line per sample, flushed
 *     as it goes, so the cost does not grow with the length of the run and a
 *     session killed at the graphics bug still leaves everything it measured.
 *
 * All of it on [Dispatchers.Default]; none of it on the main thread.
 */
@Singleton
class SessionMetricsRecorder @Inject constructor(
    private val runtime: SessionRuntime,
    private val traces: SessionTraceStore,
    private val sampler: MetricSampler,
    private val display: SessionDisplayServer,
    /**
     * Only to name one file: the container's `tmp`, which is where the D3D layer
     * writes its counters. The sampler reads that file but cannot know which one
     * — it has no container id and should not acquire one — so the path is
     * resolved here, where the session's identity already is.
     */
    private val paths: ContainerPaths,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(SessionMetricsState())
    val state: StateFlow<SessionMetricsState> = _state.asStateFlow()

    /** How many screens are drawing the graph right now. Decides the sample rate. */
    private val watchers = AtomicInteger(0)

    private var sampling: Job? = null

    init {
        scope.launch {
            // Keyed on the identity of the run rather than on the whole state:
            // `SessionState` changes on every published log line, and restarting
            // the sampler for each of those would reset the baseline forever.
            runtime.state
                .map { if (it.phase == SessionPhase.RUNNING) it.containerId to it.startedAt else null }
                .distinctUntilChanged()
                .collect { session ->
                    sampling?.cancel()
                    sampling = null
                    val (containerId, startedAt) = session ?: run {
                        _state.update { it.copy(running = false) }
                        return@collect
                    }
                    if (containerId == null || startedAt == null) return@collect
                    sampling = scope.launch { record(containerId, startedAt) }
                }
        }
    }

    /**
     * The state, plus a note that somebody is watching it.
     *
     * Collect this rather than [state] from a screen. The subscription is what
     * moves the sampler up to 1 Hz, and letting it go is what drops it back down
     * — which is the whole of the "stop when the rail is closed" rule, expressed
     * as something a caller cannot forget to do.
     */
    fun watched(): Flow<SessionMetricsState> = state
        .onStart { watchers.incrementAndGet() }
        .onCompletion { watchers.decrementAndGet() }

    private suspend fun record(containerId: String, startedAt: Long) {
        sampler.reset()
        val ceilings = sampler.coreCeilingsMhz
        val stats = gfxStatsFile(paths.of(containerId).tmp)
        // Any snapshot here belongs to the previous run of this container, and
        // for the first three seconds of this one it would still look fresh. The
        // sampler's staleness rule catches it a moment later; deleting it makes
        // the very first samples of a run honest as well, which is exactly the
        // stretch someone chasing a slow launch is looking at.
        withContext(Dispatchers.IO) { runCatching { stats.delete() } }

        // The device probe, plus the one row that is about the program rather
        // than about the phone. At session start no Direct3D program has drawn,
        // so the `d3d` row opens unavailable and says why — which is the truth
        // for the header, written once and never revised. The live copy in
        // `_state` is re-asked as the run goes, below.
        val sources = sampler.sources + sampler.graphicsSource(stats)
        _state.value = SessionMetricsState(
            containerId = containerId,
            startedAt = startedAt,
            running = true,
            replayed = false,
            history = MetricHistory(),
            sources = sources,
            coreCeilingsMhz = ceilings,
        )

        val header = SessionTraceHeader(
            containerId = containerId,
            startedAt = startedAt,
            intervalMs = FAST_INTERVAL_MS.toInt(),
            cores = sampler.cores,
            coreCeilingsMhz = ceilings,
            ramTotalMb = null,
            sources = sources,
        )
        val writer = withContext(Dispatchers.IO) { traces.open(header) }
        val summary = GfxRunSummary()
        var reported = 0L
        var hintsReported = false

        // Elapsed realtime, not wall clock: a session is routinely long enough
        // for an NTP correction to land inside it, and a graph whose x-axis can
        // step backwards is a graph with a gap nobody can explain.
        val base = SystemClock.elapsedRealtime()
        try {
            while (coroutineContext.isActive) {
                val elapsedMs = SystemClock.elapsedRealtime() - base
                val sample = sampler.sample(
                    elapsedMs = elapsedMs,
                    // The compositor's own current reading. Sampled here at
                    // the recorder's cadence rather than averaged over it:
                    // the display server already computes the rate over its
                    // own half-second window, and averaging an average would
                    // smooth away exactly the dips this graph exists to show.
                    fps = display.frameRate.value.fps.takeIf { it > 0f || display.frameRate.value.history.isNotEmpty() },
                    gfxStats = stats,
                )
                // Said once, into the session log, the first time the display
                // server knows. Not logcat: its main buffer holds under three
                // minutes here, and this answer is decided at the first frame.
                if (!hintsReported) {
                    display.frameHints.value?.let {
                        hintsReported = true
                        runtime.note(LogLevel.INFO, it)
                    }
                }
                // And the rest of what frame generation said, for the same
                // reason and into the same place. `FG_LOG` has always written to
                // logcat only, so a container could be configured for it, run
                // for ten minutes, and end with the entire account already
                // evicted — a diagnostic that needed adb attached while it
                // happened, which is what a session log exists to avoid.
                //
                // Every tick, not once: this is a stream rather than a verdict.
                // Empty for every container with frame generation off, so the
                // call costs a null check and nothing reaches the log.
                display.drainFrameGenerationLog().forEach { runtime.note(LogLevel.INFO, it) }
                summary.add(sample)
                // The device's own story, kept beside the guest's. Separate call
                // because it survives a session that never drew: an installer has
                // clocks and heat and no D3D at all.
                summary.addDevice(sample)
                val d3d = sample.d3dDrawCallsPerFrame != null
                _state.update {
                    if (it.startedAt != startedAt) {
                        it
                    } else {
                        it.copy(
                            history = it.history + sample,
                            // Only when the answer changed. The row flips at
                            // most twice in a run — once when a game creates its
                            // D3D device and once if it exits — and rebuilding
                            // the list every second would recompose every card
                            // on the panel for a value that did not move.
                            sources = if (d3d == it.sources.d3dAvailable) {
                                it.sources
                            } else {
                                sampler.sources + sampler.graphicsSource(stats)
                            },
                        )
                    }
                }
                writer?.append(sample)
                if (elapsedMs - reported >= REPORT_INTERVAL_MS) {
                    reported = elapsedMs
                    report(summary)
                }
                delay(if (watchers.get() > 0) FAST_INTERVAL_MS else SLOW_INTERVAL_MS)
            }
        } finally {
            // Cancellation is the normal exit here — the session ended — so the
            // close has to happen in a context that is not already cancelled.
            withContext(NonCancellable) {
                writer?.close()
                // Best effort, and deliberately not the only report. The log may
                // already be closed by the time a cancelled sampler gets here —
                // teardown and this coroutine race, and teardown is the one with
                // the deadline — which is why the periodic line above exists
                // rather than this being the whole feature.
                report(summary)
            }
        }
    }

    /**
     * The run's graphics counters, in the session's own log.
     *
     * **Once a minute, cumulative, and nothing at all when no Direct3D program
     * drew.** A line a second is what the trace is for; the log is read by
     * somebody hunting a stack of `err:` lines and a telemetry line every second
     * is noise exactly where noise costs the most — the argument
     * [SessionTraceStore] already makes for why metrics stopped being log lines
     * in the first place.
     *
     * Cumulative rather than per interval, so that the *last* line printed is
     * very nearly the whole-run summary. That matters because the last line is
     * the one that gets read: a session that was killed leaves no final report,
     * and a minute-old summary of the whole run is a far better thing to find
     * there than a summary of the last minute of it.
     *
     * One line a minute against `docs/LOGGING.md`'s limits is nothing — the rate
     * limiter is thousands of lines a second and the head allowance is tens of
     * megabytes — so this cannot be the thing that pushes a session into
     * elision.
     */
    private fun report(summary: GfxRunSummary) {
        // Device first, graphics second. The device line is the one that exists
        // for every session — a run with no D3D still has clocks and heat — so a
        // reader scanning a log finds the always-present line above the
        // sometimes-present one rather than hunting for it below.
        summary.deviceLine()?.let { runtime.note(LogLevel.INFO, it) }
        val line = summary.line() ?: return
        runtime.note(LogLevel.INFO, line)
    }

    private companion object {
        /** A screen is watching: one sample a second, which is what a graph needs. */
        const val FAST_INTERVAL_MS = 1_000L

        /** Nobody is watching: only often enough to leave a usable trace. */
        const val SLOW_INTERVAL_MS = 10_000L

        /**
         * How often the graphics summary reaches the session log.
         *
         * A minute, which is sixty times less often than a sample and the same
         * shape of decision `PresentExtension.COPY_REPORT_EVERY` makes for the
         * present copy: measure every time, say something occasionally. Session
         * time rather than a sample count, so the cadence does not change when
         * the sampler drops to its unwatched rate.
         */
        const val REPORT_INTERVAL_MS = 60_000L
    }
}

/**
 * Whether the sources list currently says the D3D counters are arriving.
 *
 * A helper rather than an inline `firstOrNull`, because the recorder compares it
 * against a fresh reading once a second and the comparison is the thing that
 * stops the panel recomposing for a value that did not change.
 */
private val List<MetricSource>.d3dAvailable: Boolean
    get() = any { it.label == "d3d" && it.available }
