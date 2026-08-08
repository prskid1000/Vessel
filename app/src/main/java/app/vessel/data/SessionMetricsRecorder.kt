package app.vessel.data

import android.os.SystemClock
import app.vessel.core.MetricHistory
import app.vessel.core.MetricSource
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
        val sources = sampler.sources
        val ceilings = sampler.coreCeilingsMhz
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

        // Elapsed realtime, not wall clock: a session is routinely long enough
        // for an NTP correction to land inside it, and a graph whose x-axis can
        // step backwards is a graph with a gap nobody can explain.
        val base = SystemClock.elapsedRealtime()
        try {
            while (coroutineContext.isActive) {
                val sample = sampler.sample(SystemClock.elapsedRealtime() - base)
                _state.update {
                    if (it.startedAt == startedAt) it.copy(history = it.history + sample) else it
                }
                writer?.append(sample)
                delay(if (watchers.get() > 0) FAST_INTERVAL_MS else SLOW_INTERVAL_MS)
            }
        } finally {
            // Cancellation is the normal exit here — the session ended — so the
            // close has to happen in a context that is not already cancelled.
            withContext(NonCancellable) { writer?.close() }
        }
    }

    private companion object {
        /** A screen is watching: one sample a second, which is what a graph needs. */
        const val FAST_INTERVAL_MS = 1_000L

        /** Nobody is watching: only often enough to leave a usable trace. */
        const val SLOW_INTERVAL_MS = 10_000L
    }
}
