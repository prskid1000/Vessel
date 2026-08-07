package app.vessel.core

import app.vessel.core.params.ParamValue
import kotlinx.serialization.Serializable

/**
 * One container, as the Containers screen shows it and as it is stored.
 *
 * There is one kind of container. Wine is built `arm64ec,aarch64,i386` and there
 * is no x86-64 tree to run, so what a container *is* was never a choice the user
 * could make: ARM64 programs run on Wine directly, x64 through
 * `libarm64ecfex.dll` and 32-bit x86 through WoW64 and `libwow64fex.dll`. The
 * architecture profile that used to sit at the top of the editor selected between
 * that and a Box64 tree which does not exist, so the field and its picker are
 * gone. Documents written before that carry an `archProfile` key; the reader is
 * configured with `ignoreUnknownKeys`, so they still load.
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
    val wineBuild: String,
    val driver: String,
    val d3dLayer: String,
    /** Epoch millis, or null when the container has never been launched. */
    val lastRun: Long? = null,
    val params: Map<String, ParamValue> = emptyMap(),
)
