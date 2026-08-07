package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.LogEntry
import app.vessel.core.LogFilter
import app.vessel.data.LogCursor
import app.vessel.data.SessionExit
import app.vessel.data.SessionLogStore
import app.vessel.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@Immutable
data class SessionLogUiState(
    val loading: Boolean = true,
    val filter: LogFilter = LogFilter.ALL,
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
 * The severity filter is applied at read time rather than over the loaded list.
 * Filtering afterwards would mean a session with three errors in a hundred
 * thousand lines showing three rows and never asking for more, because the list
 * that drives paging would never grow.
 */
@HiltViewModel
class SessionLogViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val logs: SessionLogStore,
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()
    private val startedAt: Long =
        savedState.get<String>(Routes.ARG_STARTED_AT)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(SessionLogUiState())
    val state: StateFlow<SessionLogUiState> = _state.asStateFlow()

    /** One reader at a time: a scroll and a tail update must not interleave. */
    private val reading = Mutex()

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

    fun setFilter(filter: LogFilter) {
        if (filter == _state.value.filter) return
        viewModelScope.launch {
            reading.withLock {
                cursor = LogCursor()
                loaded = mutableListOf()
                _state.update {
                    it.copy(
                        filter = filter,
                        entries = emptyList(),
                        atEnd = false,
                        truncated = false,
                        loading = true,
                    )
                }
            }
            pull()
        }
    }

    /** The text a Copy All puts on the clipboard, bounded by the store. */
    suspend fun clipboardText(): String =
        logs.textFor(containerId, startedAt, _state.value.filter)

    /** The whole log written out for the share sheet, filter ignored. */
    suspend fun exportFile(): File? = logs.export(containerId, startedAt)

    /**
     * Pull pages until something arrives, the file ends, or the view is full.
     *
     * The loop is what makes the severity filter work: a page can legitimately
     * come back empty because it scanned twenty thousand `trace` lines and none
     * of them were problems, and stopping there would leave a screen that says
     * nothing and never asks again.
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
                    filter = _state.value.filter,
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

        /** Bounds the work of one `pull` when a filter is rejecting everything. */
        const val MAX_ROUNDS = 8
    }
}
