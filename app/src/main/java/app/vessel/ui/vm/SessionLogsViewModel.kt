package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.data.ContainerRepository
import app.vessel.data.SessionExit
import app.vessel.data.SessionLogMeta
import app.vessel.data.SessionLogStore
import app.vessel.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * One session, ready to draw.
 *
 * Every label is formatted here. The row shows four facts about a run that has
 * already finished, and none of them are things a composable should be doing
 * clock or byte arithmetic for.
 */
@Immutable
data class SessionRow(
    val startedAt: Long,
    val whenLabel: String,
    val durationLabel: String,
    val sizeLabel: String,
    val status: SessionExit,
    val hasErrors: Boolean,
)

@Immutable
data class SessionLogsUiState(
    val loading: Boolean = true,
    val containerName: String = "",
    val rows: List<SessionRow> = emptyList(),
)

/**
 * The session list for one container.
 *
 * It reads the sidecars and never a log body — see [SessionLogStore] — so the
 * screen costs ten small JSON reads however large the logs behind it are. The
 * list re-reads on the store's revision, which is what makes a session that is
 * running right now show a growing size and a `running` tag without this class
 * knowing anything about sessions being live.
 */
@HiltViewModel
class SessionLogsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val logs: SessionLogStore,
    private val containers: ContainerRepository,
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()

    private val _state = MutableStateFlow(SessionLogsUiState())
    val state: StateFlow<SessionLogsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            _state.update { it.copy(containerName = name) }
        }
        viewModelScope.launch {
            logs.sessions(containerId).collectLatest { sessions ->
                _state.update {
                    it.copy(loading = false, rows = sessions.map(::toRow))
                }
            }
        }
    }

    fun delete(startedAt: Long) {
        viewModelScope.launch { logs.delete(containerId, startedAt) }
    }

    fun deleteAll() {
        viewModelScope.launch { logs.deleteAll(containerId) }
    }

    private fun toRow(meta: SessionLogMeta) = SessionRow(
        startedAt = meta.startedAt,
        whenLabel = relativeLabel(meta.startedAt),
        durationLabel = durationLabel(meta.startedAt, meta.endedAt),
        sizeLabel = sizeLabel(meta.sizeBytes),
        status = meta.exit,
        hasErrors = meta.hasErrors,
    )
}

/**
 * "12 minutes ago", and "just now" under a minute.
 *
 * The one clock in the presentation layer. [lastRunLabel] wraps it rather than
 * repeating it, so a container card and a session row can never disagree about
 * how long ago the same moment was.
 */
internal fun relativeLabel(then: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - then).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute${plural(minutes)} ago"
        hours < 24 -> "$hours hour${plural(hours)} ago"
        else -> "$days day${plural(days)} ago"
    }
}

/** `41 s`, `4 m 07 s`, `1 h 12 m` — and "running" while it is still going. */
internal fun durationLabel(startedAt: Long, endedAt: Long?): String {
    if (endedAt == null) return "running"
    val seconds = TimeUnit.MILLISECONDS.toSeconds((endedAt - startedAt).coerceAtLeast(0))
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        seconds < 60 -> "$seconds s"
        minutes < 60 -> "$minutes m ${(seconds % 60).toString().padStart(2, '0')} s"
        else -> "$hours h ${(minutes % 60).toString().padStart(2, '0')} m"
    }
}

/** Decimal units, because a log's size is read as a magnitude and never as a block count. */
internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${bytes / 1_000} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
}

private fun plural(n: Long) = if (n == 1L) "" else "s"
