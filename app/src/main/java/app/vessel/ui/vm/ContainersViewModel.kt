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
    // The elapsed-time wording lives in `relativeLabel`, beside the session list
    // that also uses it. Two copies of "12 minutes ago" is two places for the
    // same moment to be described two different ways on two screens.
    return "ran ${relativeLabel(lastRun, now)}"
}

/**
 * One container, and one is the point.
 *
 * Preview data only — the screen itself reads the store, so nothing here reaches
 * a device. One row, deliberately: a second implies a catalogue of Wine trees
 * this product does not have.
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
