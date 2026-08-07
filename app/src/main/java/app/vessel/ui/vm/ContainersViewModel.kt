package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ContainerProfile
import app.vessel.data.ContainerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * A container plus the one thing the card cannot work out for itself.
 *
 * `lastRun` is epoch millis on the domain object; turning it into "12 minutes
 * ago" is a presentation decision, and doing it here keeps the card pure.
 */
@Immutable
data class ContainerRow(
    val profile: ContainerProfile,
    val lastRunLabel: String,
)

@Immutable
data class ContainersUiState(
    val rows: List<ContainerRow> = emptyList(),
    /**
     * False only until the store's first emission. Without it the home screen
     * flashes the "no containers yet" empty state on every cold start, which
     * reads as data loss rather than as a read in progress.
     */
    val loaded: Boolean = false,
)

@HiltViewModel
class ContainersViewModel @Inject constructor(
    private val containers: ContainerRepository,
) : ViewModel() {

    val state: StateFlow<ContainersUiState> = containers.containers
        .map { profiles ->
            ContainersUiState(
                rows = profiles.map { ContainerRow(it, lastRunLabel(it.lastRun)) },
                loaded = true,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContainersUiState(),
        )

    fun delete(id: String) {
        viewModelScope.launch { containers.delete(id) }
    }
}

/**
 * "ran 12 minutes ago", and "never launched" when it has not been.
 *
 * The null case is a fact rather than an empty string: a brand-new container has
 * genuinely never run, and a blank line where every other card has a timestamp
 * looks like the timestamp failed to load.
 */
internal fun lastRunLabel(lastRun: Long?, now: Long = System.currentTimeMillis()): String {
    if (lastRun == null) return "never launched"
    val elapsed = (now - lastRun).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "ran just now"
        minutes < 60 -> "ran $minutes minute${plural(minutes)} ago"
        hours < 24 -> "ran $hours hour${plural(hours)} ago"
        else -> "ran $days day${plural(days)} ago"
    }
}

private fun plural(n: Long) = if (n == 1L) "" else "s"

/**
 * One container, and one is the point.
 *
 * Preview data only — the screen itself reads the store, so nothing here reaches
 * a device. There used to be a second row and it was doing the exact thing
 * DESIGN.md refuses: legacy Wine is not part of this product, and offering a
 * second tree implies a catalogue the user then has to choose from.
 */
internal val SampleContainerRows = listOf(
    ContainerRow(
        profile = ContainerProfile(
            id = "default",
            name = "Default",
            wineBuild = "wine-11.0-arm64ec",
            driver = "turnip-gen8-25.2.0",
            d3dLayer = "dxvk-2.7.1",
            lastRun = null,
        ),
        lastRunLabel = "ran 12 minutes ago",
    ),
)
