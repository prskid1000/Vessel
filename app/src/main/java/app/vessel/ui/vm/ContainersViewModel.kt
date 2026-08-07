package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import app.vessel.core.ArchProfile
import app.vessel.core.ContainerProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class ContainersViewModel @Inject constructor() : ViewModel() {

    // TODO: reads from Room once the container store exists. Fixed data until
    //  then, so the screen is real and previewable rather than a placeholder.
    private val _state = MutableStateFlow(ContainersUiState(rows = SampleContainerRows))
    val state: StateFlow<ContainersUiState> = _state.asStateFlow()
}

/**
 * One container, and one is the point.
 *
 * There used to be a second row here — an "Installers" container on a legacy
 * x86-64 Wine tree — and it was doing the exact thing DESIGN.md refuses. Legacy
 * Wine is not part of this product: there is one Wine build, compiled for this
 * device, and offering a second tree implies a catalogue the user then has to
 * choose from. The Universal profile is the only example the home screen needs.
 */
internal val SampleContainerRows = listOf(
    ContainerRow(
        profile = ContainerProfile(
            id = "default",
            name = "Default",
            archProfile = ArchProfile.UNIVERSAL,
            wineBuild = "wine-11.0-arm64ec",
            driver = "turnip-gen8-25.2.0",
            d3dLayer = "dxvk-2.7.1",
            lastRun = null,
        ),
        lastRunLabel = "ran 12 minutes ago",
    ),
)
