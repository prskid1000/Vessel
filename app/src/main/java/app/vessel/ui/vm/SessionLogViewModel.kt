package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.LogEntry
import app.vessel.core.MetricHistory
import app.vessel.data.LogCursor
import app.vessel.data.SessionExit
import app.vessel.data.SessionLogStore
import app.vessel.data.SessionMetricsRecorder
import app.vessel.data.SessionMetricsState
import app.vessel.data.SessionTraceStore
import app.vessel.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Which half of the screen is on show. */
enum class SessionLogTab { LOG, METRICS }

@Immutable
data class SessionLogUiState(
    val loading: Boolean = true,
    val tab: SessionLogTab = SessionLogTab.LOG,
    val entries: List<LogEntry> = emptyList(),
    /** True while the session is still being written to; drives follow-tail. */
    val live: Boolean = false,
    val atEnd: Boolean = false,
    /**
     * Set when the view hit its own ceiling before the file ended. Said out
     * loud on screen, with the way to get the rest.
     */
    val truncated: Boolean = false,
    val whenLabel: String = "",
    val status: SessionExit = SessionExit.OK,
    val missing: Boolean = false,
)

/**
 * One session's log, paged.
 *
 * The file is never held as a string and never read whole in one go. Reading
 * resumes from a byte cursor, so paging down a long log costs one sequential
 * pass and following a live one costs only the bytes that arrived — the
 * alternative, re-reading from the top on every change, is the thing that makes
 * log viewers stutter.
 *
 * **Every line, always.** There used to be a two-way severity filter here, and
 * it was removed rather than improved: it made the reader choose between "all"
 * and "errors and warnings" before knowing which layer had failed, which is
 * exactly the prediction a log exists so nobody has to make. A missing `fixme`
 * two hundred lines above the crash is the one that explains it.
 */
@HiltViewModel
class SessionLogViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val logs: SessionLogStore,
    private val traces: SessionTraceStore,
    recorder: SessionMetricsRecorder,
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()
    private val startedAt: Long =
        savedState.get<String>(Routes.ARG_STARTED_AT)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(SessionLogUiState())
    val state: StateFlow<SessionLogUiState> = _state.asStateFlow()

    /**
     * This session's metrics: the live window if it is running, its stored trace
     * if it is not.
     *
     * `watched()` rather than the plain state, because collecting it is what
     * tells the recorder a graph is on screen and moves it up to 1 Hz. The screen
     * only collects while the Metrics tab is selected, so a reader sitting on the
     * Log tab of a running session costs nothing.
     *
     * The `isLiveFor` check is the whole reason this is not just the recorder's
     * flow. There is one recorder and one window, and a session log opened from
     * the history while a *different* container is running would otherwise draw
     * that container's graph under this one's timestamp.
     *
     * Falling back to the trace is what makes this screen useful at all today:
     * every session on this device currently dies within half a minute at the
     * graphics bug, so without a replay the tab would only ever say "not
     * running".
     */
    val metrics: Flow<SessionMetricsState?> = recorder.watched().map { live ->
        if (live.isLiveFor(containerId, startedAt)) {
            wasLive = true
            live
        } else {
            replay()
        }
    }

    /**
     * The stored trace, read once and kept.
     *
     * Cached because the recorder's state changes once a second and re-reading a
     * finished run's file on each of those would be the most expensive thing this
     * screen does. Invalidated when a session we were watching live stops, so the
     * replay picks up the samples taken after the last frame we drew.
     */
    private suspend fun replay(): SessionMetricsState? = replaying.withLock {
        if (wasLive) {
            wasLive = false
            replayed = null
            replayLoaded = false
        }
        if (!replayLoaded) {
            replayLoaded = true
            replayed = traces.read(containerId, startedAt)
                ?.takeIf { !it.isEmpty }
                ?.let { trace ->
                    SessionMetricsState(
                        containerId = containerId,
                        startedAt = startedAt,
                        running = false,
                        replayed = true,
                        history = MetricHistory.of(trace.samples),
                        sources = trace.header.sources,
                        coreCeilingsMhz = trace.header.coreCeilingsMhz,
                    )
                }
        }
        replayed
    }

    /** One reader at a time: a scroll and a tail update must not interleave. */
    private val reading = Mutex()

    /** Guards the replay cache, which several emissions a second race for. */
    private val replaying = Mutex()
    private var replayed: SessionMetricsState? = null
    private var replayLoaded = false

    /** Set while this session is the live one, so its end can invalidate the cache. */
    private var wasLive = false

    /** Set while a scroll-driven page is in flight, so scrolling cannot pile them up. */
    private val pulling = AtomicBoolean(false)
    private var cursor = LogCursor()
    private var loaded = mutableListOf<LogEntry>()

    init {
        viewModelScope.launch {
            val meta = logs.meta(containerId, startedAt)
            _state.update {
                it.copy(
                    missing = meta == null,
                    loading = meta != null,
                    whenLabel = relativeLabel(startedAt),
                    status = meta?.exit ?: SessionExit.OK,
                )
            }
            if (meta == null) return@launch
            pull()
            // Every disk change re-checks whether the session is still going and
            // pulls whatever arrived. For a finished session the first read has
            // already reached the end and each bump costs one comparison.
            logs.revision.collect {
                refreshStatus()
                if (_state.value.atEnd) pull()
            }
        }
    }

    /**
     * Called by the viewer as the list approaches the end of what is loaded.
     *
     * Guarded rather than queued. Scrolling emits this many times a second, and
     * a mutex alone would turn every one of them into a coroutine waiting its
     * turn to read a page nobody is scrolled to yet.
     */
    fun loadMore() {
        val current = _state.value
        if (current.atEnd || current.truncated) return
        if (!pulling.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                pull()
            } finally {
                pulling.set(false)
            }
        }
    }

    /**
     * Switch tabs.
     *
     * A plain state change and nothing else: the log keeps whatever it has
     * loaded, so coming back from Metrics does not re-read the file from the top
     * or lose the reader's scroll position.
     */
    fun setTab(tab: SessionLogTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab) }
    }

    /** The text a Copy All puts on the clipboard, bounded by the store. */
    suspend fun clipboardText(): String = logs.textFor(containerId, startedAt)

    /** The whole log written out for the share sheet. */
    suspend fun exportFile(): File? = logs.export(containerId, startedAt)

    /**
     * Pull pages until something arrives, the file ends, or the view is full.
     *
     * Still a loop with no filter to justify it, because the store bounds a read
     * by lines *examined* as well as lines returned: a page can come back short
     * without being at the end, and stopping on the first one would leave the
     * screen short of a screenful and never ask again.
     */
    private suspend fun pull() {
        reading.withLock {
            var rounds = 0
            var added = 0
            while (rounds++ < MAX_ROUNDS) {
                if (loaded.size >= MAX_VIEW_LINES) {
                    _state.update { it.copy(truncated = true, loading = false) }
                    return@withLock
                }
                val chunk = logs.read(
                    containerId = containerId,
                    startedAt = startedAt,
                    cursor = cursor,
                    maxLines = PAGE_LINES,
                )
                cursor = chunk.cursor
                if (chunk.entries.isNotEmpty()) {
                    loaded.addAll(chunk.entries)
                    added += chunk.entries.size
                }
                if (chunk.atEnd) {
                    _state.update {
                        it.copy(entries = loaded.toList(), atEnd = true, loading = false)
                    }
                    return@withLock
                }
                if (added > 0) break
            }
            _state.update { it.copy(entries = loaded.toList(), atEnd = false, loading = false) }
        }
    }

    private suspend fun refreshStatus() {
        val meta = logs.meta(containerId, startedAt) ?: return
        val live = logs.isOpen(containerId, startedAt)
        if (meta.exit == _state.value.status && live == _state.value.live) return
        _state.update { it.copy(status = meta.exit, live = live) }
    }

    private companion object {
        /** One page. Large enough that a flick does not outrun it. */
        const val PAGE_LINES = 2_000

        /**
         * How many lines the viewer will hold.
         *
         * The cap on the *file* is eight megabytes; this is the cap on what one
         * screen turns into objects. Past it the viewer says so and points at
         * Share, which streams the whole thing without holding any of it.
         */
        const val MAX_VIEW_LINES = 50_000

        /** Bounds the work of one `pull` when pages keep coming back short. */
        const val MAX_ROUNDS = 8
    }
}
