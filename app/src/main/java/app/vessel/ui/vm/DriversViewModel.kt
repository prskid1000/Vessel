package app.vessel.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentType
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.data.ContainerRepository
import app.vessel.data.DeviceNode
import app.vessel.data.GpuProbe
import app.vessel.data.InstalledComponents
import app.vessel.data.ParamManifestStore
import app.vessel.data.SystemGpu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One installed driver package, and the containers that have chosen it. */
data class DriverRow(
    val pkg: ComponentPackage,
    val assignedTo: List<String>,
)

/**
 * What one container asked for.
 *
 * Kept separate from [DriverRow] because the interesting case is the one with no
 * row to hang off: a container whose selector resolves to nothing has an
 * assignment and no driver, and that is precisely the state worth showing.
 */
data class DriverAssignment(
    val containerName: String,
    val selector: String,
    val resolvedId: String?,
)

data class DriversUiState(
    val loading: Boolean = true,
    val drivers: List<DriverRow> = emptyList(),
    val assignments: List<DriverAssignment> = emptyList(),
    val system: SystemGpu? = null,
    val kgsl: DeviceNode = DeviceNode.UNREADABLE,
)

/**
 * The driver manager.
 *
 * Two sources and no third: the installed component set on disk, and what the
 * system driver reports when asked. Nothing is listed that is not one of those —
 * a driver invented for the sake of a fuller screen is the exact failure this
 * product is arguing against, since installing a driver that does not claim
 * support for the GPU is a black screen rather than a fallback.
 */
@HiltViewModel
class DriversViewModel @Inject constructor(
    private val components: InstalledComponents,
    private val containers: ContainerRepository,
    private val manifests: ParamManifestStore,
    private val gpu: GpuProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(DriversUiState())
    val state: StateFlow<DriversUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val installed = components.refresh().filter { it.type == ComponentType.TURNIP }

        // Which manifest key holds the driver is a question for the manifest, not
        // for this class: the driver param is the `component` one whose type is
        // Turnip, whatever it happens to be called.
        val driverKey = manifests.load().getOrNull()?.allParams?.firstOrNull {
            it.type == ParamType.COMPONENT && it.componentType == ComponentType.TURNIP.wire
        }

        val assignments = containers.containers.first().map { container ->
            val selector = (container.params[driverKey?.key] as? ParamValue.Text)?.value
                ?: (driverKey?.defaultValue() as? ParamValue.Text)?.value
                ?: "@latest"
            DriverAssignment(
                containerName = container.name,
                selector = selector,
                resolvedId = components.resolve(ComponentType.TURNIP, selector).resolved?.id,
            )
        }

        _state.value = DriversUiState(
            loading = false,
            drivers = installed.map { pkg ->
                DriverRow(
                    pkg = pkg,
                    assignedTo = assignments.filter { it.resolvedId == pkg.id }
                        .map { it.containerName },
                )
            },
            assignments = assignments,
            system = gpu.probe(),
            kgsl = gpu.kgslNode(),
        )
    }
}
