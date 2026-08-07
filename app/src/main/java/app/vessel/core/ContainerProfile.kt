package app.vessel.core

import app.vessel.core.params.ParamValue
import kotlinx.serialization.Serializable

/**
 * One container, as the Containers screen shows it and as it is stored.
 *
 * There is one kind of container: Wine is built `arm64ec,aarch64,i386` and there
 * is no x86-64 tree, so ARM64 runs on Wine directly, x64 through
 * `libarm64ecfex.dll` and 32-bit x86 through WoW64 and `libwow64fex.dll`.
 * Documents written when an `archProfile` field existed still load, because the
 * reader is configured with `ignoreUnknownKeys`.
 *
 * [wineBuild], [driver] and [d3dLayer] are the *resolved* component labels — what
 * this container will actually load, worked out against the installed set when
 * it is saved. They are stored rather than derived at read time so the home
 * screen can name what a container runs without the card knowing anything about
 * the component store.
 *
 * [params] is the manifest surface: every key in `assets/params-manifest.json`
 * this container has a value for. A map rather than fields on purpose — adding a
 * knob must not need a field here, or the editor stops being data-driven one
 * layer down.
 */
@Serializable
data class ContainerProfile(
    val id: String,
    val name: String,
    val wineBuild: String,
    val driver: String,
    val d3dLayer: String,
    /** Epoch millis, or null when the container has never been launched. */
    val lastRun: Long? = null,
    val params: Map<String, ParamValue> = emptyMap(),
)
