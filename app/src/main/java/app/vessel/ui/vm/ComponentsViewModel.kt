package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * One section of the `.wcp` store.
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
)

@HiltViewModel
class ComponentsViewModel @Inject constructor() : ViewModel() {

    // TODO: reads registry/contents.json over the network once the downloader
    //  exists. Fixed data until then.
    private val _state = MutableStateFlow(ComponentsUiState(sections = SampleComponentSections))
    val state: StateFlow<ComponentsUiState> = _state.asStateFlow()
}

private fun sample(
    id: String,
    type: ComponentType,
    name: String,
    version: String,
    versionCode: Int,
    sizeBytes: Long,
    installed: Boolean,
) = ComponentPackage(id, type, name, version, versionCode, sizeBytes, installed)

// Ids are `.wcp` stems, which is what build/gen_registry.py uses as the id.
internal val SampleComponentSections = listOf(
    ComponentSection(
        title = "Engines",
        items = listOf(
            sample("fex-2608-canoe", ComponentType.FEXCORE, "FEX (oryon-1)", "2608", 2608, 43_400_000, true),
            sample("box64-0.4.4-canoe", ComponentType.BOX64, "Box64 (SD8EG5)", "0.4.4", 4004, 9_300_000, true),
            sample("wowbox64-0.4.4-canoe", ComponentType.WOWBOX64, "WOWBox64", "0.4.4", 4004, 3_200_000, false),
        ),
    ),
    ComponentSection(
        title = "Wine builds",
        items = listOf(
            sample("wine-11.0-arm64ec-canoe", ComponentType.WINE, "Wine ARM64EC", "11.0", 110000, 537_000_000, true),
            sample("wine-9.22-x86_64-canoe", ComponentType.WINE, "Wine x86_64", "9.22", 90022, 509_000_000, true),
        ),
    ),
    ComponentSection(
        title = "GPU drivers",
        items = listOf(
            sample("turnip-25.2.0-canoe", ComponentType.TURNIP, "Turnip gen8 (Adreno 8xx)", "25.2.0", 250200, 29_100_000, true),
            sample("turnip-25.1.4-canoe", ComponentType.TURNIP, "Turnip a7xx", "25.1.4", 250104, 28_200_000, false),
        ),
    ),
    ComponentSection(
        title = "D3D layers",
        items = listOf(
            sample("dxvk-2.7.1-canoe", ComponentType.DXVK, "DXVK", "2.7.1", 20701, 14_900_000, true),
            sample("vkd3d-2.14-canoe", ComponentType.VKD3D, "vkd3d-proton", "2.14", 21400, 12_100_000, false),
            sample("d8vk-1.0-canoe", ComponentType.D8VK, "D8VK", "1.0", 10000, 5_000_000, false),
        ),
        warning = "vkd3d-proton 2.14 was validated against Turnip 25.1.4, not the 25.2.0 build " +
            "installed here. Install the matched pair or expect D3D12 titles to fault.",
    ),
)
