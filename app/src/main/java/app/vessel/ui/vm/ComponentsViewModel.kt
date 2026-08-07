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

/**
 * The one target this build was compiled for.
 *
 * `build/targets/canoe.env` — Snapdragon SM8845 (Oryon) / Adreno 829. Every
 * package in the list below carries it, which is the point: a second value here
 * would mean the list had started listing things we did not build for this
 * phone.
 */
private const val TARGET = "canoe"

/**
 * `resolve_cpu_flags` in `build/common.sh` prefers `-mcpu=oryon-1` and records
 * whatever it actually settled on. Wine is the exception — one configure builds
 * three PE targets, so there is no single CPU flag to record for it.
 */
private const val ORYON = "-mcpu=oryon-1"

private fun sample(
    id: String,
    type: ComponentType,
    name: String,
    version: String,
    versionCode: Int,
    sizeBytes: Long,
    installed: Boolean,
    sourceSha: String,
    cpuFlags: String = ORYON,
) = ComponentPackage(
    id = id,
    type = type,
    name = name,
    versionName = version,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    installed = installed,
    target = TARGET,
    sourceSha = sourceSha,
    cpuFlags = cpuFlags,
)

/**
 * Six components: one current build of each thing this device needs, and
 * nothing else.
 *
 * This list is the deliberate difference from every other app in this space.
 * Those ship a catalogue — a dozen Wine versions, DXVK 1.x through 2.x, a Turnip
 * for every Adreno generation — and leave the user to guess which combination
 * works. Vessel has exactly one, compiled here, so there is nothing to guess
 * about.
 *
 * What was removed and why:
 *   - **A Turnip for an older Adreno generation.** A driver for a GPU this
 *     phone does not have. Listing it invites installing it, and a driver that
 *     does not claim support for the GPU is a black screen, not a fallback.
 *   - **The legacy x86-64 Wine tree.** Superseded by the ARM64EC build.
 *   - **Older DXVK and vkd3d-proton majors.** Older than the Turnip they would
 *     be paired with, so the pair was never validated together.
 *   - **WOWBox64 and D8VK.** Not built for this target.
 *
 * Older builds remain installable as a rollback path, but they live behind an
 * explicit "previous builds" disclosure, not here.
 *
 * There is no matched-set [ComponentSection.warning] on any section, and that is
 * a result rather than an omission: DXVK 2.7.1 and vkd3d-proton 3.0.1 were both
 * validated against the Turnip 25.2.0 build in the same list. A warning appears
 * the moment that stops being true.
 *
 * Ids are `.wcp` stems, which is what `build/gen_registry.py` uses as the id.
 */
internal val SampleComponentSections = listOf(
    ComponentSection(
        title = "Engines",
        items = listOf(
            sample(
                id = "box64-0.4.4-canoe",
                type = ComponentType.BOX64,
                name = "Box64",
                version = "0.4.4",
                versionCode = 4004,
                sizeBytes = 9_300_000,
                installed = true,
                sourceSha = "8f2c1d4a9b03",
            ),
            sample(
                id = "fexcore-2608-canoe",
                type = ComponentType.FEXCORE,
                name = "FEXCore",
                version = "2608",
                versionCode = 2608,
                sizeBytes = 43_400_000,
                installed = true,
                sourceSha = "c17ae5039f6b",
            ),
        ),
    ),
    ComponentSection(
        title = "Wine builds",
        items = listOf(
            sample(
                id = "wine-11.0-arm64ec-canoe",
                type = ComponentType.WINE,
                name = "Wine ARM64EC",
                version = "11.0",
                versionCode = 110_000,
                sizeBytes = 537_000_000,
                installed = true,
                sourceSha = "a4d90b7e2c58",
                // build/wine.sh runs one configure for three PE targets, so
                // there is no single -mcpu to record. Saying "none" is the
                // honest answer; inventing one would be worse than blank.
                cpuFlags = "none (multi-target PE build)",
            ),
        ),
    ),
    ComponentSection(
        title = "GPU drivers",
        items = listOf(
            sample(
                id = "turnip-25.2.0-gen8-canoe",
                type = ComponentType.TURNIP,
                name = "Turnip gen8",
                version = "25.2.0",
                versionCode = 250_200,
                sizeBytes = 29_100_000,
                installed = true,
                sourceSha = "6b0f83ce1d47",
            ),
        ),
    ),
    ComponentSection(
        title = "D3D layers",
        items = listOf(
            sample(
                id = "dxvk-2.7.1-canoe",
                type = ComponentType.DXVK,
                name = "DXVK",
                version = "2.7.1",
                versionCode = 20_701,
                sizeBytes = 14_900_000,
                installed = true,
                sourceSha = "d52814fb96a0",
            ),
            sample(
                id = "vkd3d-proton-3.0.1-canoe",
                type = ComponentType.VKD3D,
                name = "vkd3d-proton",
                version = "3.0.1",
                versionCode = 30_001,
                sizeBytes = 12_100_000,
                installed = false,
                sourceSha = "1e7fa2c40b93",
            ),
        ),
    ),
)
