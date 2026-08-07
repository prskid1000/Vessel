package app.vessel.core

import app.vessel.core.params.ParamValue
import kotlinx.serialization.Serializable

/**
 * What Wine itself is compiled as. Chosen when a container is created and not
 * changeable afterwards, which is why the picker explains rather than toggles.
 */
@Serializable
enum class ArchProfile(val label: String, val explanation: String) {
    UNIVERSAL(
        label = "Universal",
        explanation = "Wine, DXVK and vkd3d are native ARM64EC. Only the application's own " +
            "x86 code is translated, by FEX inside the process.",
    ),
    COMPATIBILITY(
        label = "Compatibility",
        explanation = "The whole Wine tree is x86-64 under Box64. Slower in principle, but a " +
            "different enough code path to rescue installers and anti-cheat that ARM64EC refuses.",
    ),
}

/**
 * One container, as the Containers screen shows it and as it is stored.
 *
 * [wineBuild], [driver] and [d3dLayer] are the *resolved* component labels — what
 * this container will actually load, worked out against the installed set when
 * it is saved. They are stored rather than derived at read time so the home
 * screen can name what a container runs without the card knowing anything about
 * the component store.
 *
 * [params] is the manifest surface: every key in `assets/params-manifest.json`
 * that this container has a value for. It is a map rather than fields on purpose
 * — adding a knob to the manifest must not need a field here, or the promise
 * that the editor is data-driven stops being true one layer down.
 */
@Serializable
data class ContainerProfile(
    val id: String,
    val name: String,
    val archProfile: ArchProfile,
    val wineBuild: String,
    val driver: String,
    val d3dLayer: String,
    /** Epoch millis, or null when the container has never been launched. */
    val lastRun: Long? = null,
    val params: Map<String, ParamValue> = emptyMap(),
)
