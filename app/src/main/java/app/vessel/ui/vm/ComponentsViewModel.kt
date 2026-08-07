package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import app.vessel.data.InstalledComponents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One section of the components screen.
 *
 * [warning] is how a broken matched set is reported: a Turnip build and the
 * DXVK validated against it are published together, and installing one without
 * the other is the failure mode most likely to be blamed on the driver.
 */
@Immutable
data class ComponentSection(
    val title: String,
    val items: List<ComponentPackage>,
    val warning: String? = null,
)

@Immutable
data class ComponentsUiState(
    val sections: List<ComponentSection> = emptyList(),
    /** False until the first read of the install directory has answered. */
    val loaded: Boolean = false,
)

/**
 * What is actually installed, and nothing else.
 *
 * This used to serve a fixed list of six packages. It read well and it was a
 * lie: [InstalledComponents] scans the `profile.json` in every
 * `files/components/<id>` directory, so on a device with nothing unpacked the
 * driver manager correctly reported zero Turnip
 * builds while this screen claimed six installed components — including the very
 * Turnip the other screen said was missing. Two screens disagreeing about the
 * same directory is worse than an empty screen, because it makes the honest one
 * look broken.
 *
 * So there is one source now, and it is the disk. The fixed list survives only
 * as `@Preview` data in `ComponentsScreen`, where nobody can mistake it for a
 * fact about their phone.
 *
 * TODO: the *available* half — packages not yet downloaded — arrives with the
 *  registry read and the downloader. Until then "installed" is the whole story
 *  this class can honestly tell.
 */
@HiltViewModel
class ComponentsViewModel @Inject constructor(
    private val installed: InstalledComponents,
) : ViewModel() {

    private val _state = MutableStateFlow(ComponentsUiState())
    val state: StateFlow<ComponentsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-scan the install directory. Cheap: a handful of small files at most. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = ComponentsUiState(sections = sectionsOf(installed.refresh()), loaded = true)
        }
    }
}

/**
 * The install set, grouped the way DESIGN.md names the groups.
 *
 * The grouping is over [ComponentType] rather than anything in the package, and
 * an empty group is dropped rather than shown with a zero: a heading with nothing
 * under it is a promise that something belongs there, which is the store-front
 * shape this product is arguing against.
 */
internal fun sectionsOf(packages: List<ComponentPackage>): List<ComponentSection> =
    SECTION_TYPES.mapNotNull { (title, types) ->
        val items = packages.filter { it.type in types }
        if (items.isEmpty()) null else ComponentSection(title, items)
    }

private val SECTION_TYPES: List<Pair<String, Set<ComponentType>>> = listOf(
    "Engines" to setOf(ComponentType.FEXCORE, ComponentType.BOX64, ComponentType.WOWBOX64),
    "Wine builds" to setOf(ComponentType.WINE, ComponentType.PROTON),
    "GPU drivers" to setOf(ComponentType.TURNIP),
    "D3D layers" to setOf(ComponentType.DXVK, ComponentType.VKD3D, ComponentType.D8VK),
    "Tools" to setOf(ComponentType.TOOLS),
)
