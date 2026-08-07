package app.vessel.ui.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ComponentType
import app.vessel.core.ContainerProfile
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.core.params.ResolvedParam
import app.vessel.core.params.resolve
import app.vessel.data.ContainerRepository
import app.vessel.data.InstalledComponents
import app.vessel.data.ParamManifestStore
import app.vessel.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The id the editor is given when there is no container yet. */
const val NEW_CONTAINER: String = "new"

/** One group as the editor draws it — the manifest's own grouping, in its order. */
data class EditorGroup(
    val id: String,
    val title: String,
    val help: String?,
    val params: List<EditorParam>,
)

/**
 * One param, ready to draw.
 *
 * [componentId] and [componentNote] are filled for [ParamType.COMPONENT] only,
 * by resolving the selector against what is installed. Doing it here rather than
 * in the composable is what keeps the renderer to one branch per *type*: the
 * control receives a resolved id or a null, and never learns what a `.wcp` is.
 */
data class EditorParam(
    val resolved: ResolvedParam,
    val componentId: String? = null,
    val componentNote: String? = null,
)

data class EditorUiState(
    val loading: Boolean = true,
    /** True for a container that has not been saved yet. */
    val creating: Boolean = false,
    val name: String = "",
    val groups: List<EditorGroup> = emptyList(),
    /** Set when the manifest or the container could not be read. Shown, not swallowed. */
    val error: String? = null,
    /** One-shot: the screen pops back and the editor is gone. */
    val finished: Boolean = false,
)

/**
 * The container editor.
 *
 * Everything the screen draws is computed here, including the clamps and the
 * component resolutions, so `ContainerEditorScreen` has exactly one `when` in it
 * — over [ParamType] — and no knowledge of any individual key. That boundary is
 * the whole promise of the manifest: adding a `FEX_*` knob is a data change, and
 * if a key ever needs code, it has leaked.
 */
@HiltViewModel
class ContainerEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val containers: ContainerRepository,
    private val manifests: ParamManifestStore,
    private val components: InstalledComponents,
) : ViewModel() {

    private val containerId: String =
        savedState.get<String>(Routes.ARG_CONTAINER_ID) ?: NEW_CONTAINER

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** The container being edited. Persisted only when Save is pressed. */
    private var draft: ContainerProfile? = null
    private var manifest: ParamManifest? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        components.refresh()
        val loadedManifest = manifests.load()
        if (loadedManifest.isFailure) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "assets/params-manifest.json could not be read, so there are no " +
                        "settings to show. This is a fault in the build, not in the device.",
                )
            }
            return
        }
        manifest = loadedManifest.getOrThrow()

        val creating = containerId == NEW_CONTAINER
        val profile = if (creating) containers.draft() else containers.get(containerId)
        if (profile == null) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "No container with id $containerId. It may have been deleted from " +
                        "another screen while this one was open.",
                )
            }
            return
        }

        draft = profile
        _state.update {
            it.copy(
                loading = false,
                creating = creating,
                name = profile.name,
            )
        }
        rebuild()
    }

    // — edits ----------------------------------------------------------------

    fun setName(name: String) {
        draft = draft?.copy(name = name)
        _state.update { it.copy(name = name) }
    }

    fun setParam(key: String, value: ParamValue) {
        val current = draft ?: return
        draft = current.copy(params = current.params + (key to value))
        // A rebuild rather than a targeted update, because a manifest clamp lets
        // one param move another's ceiling.
        rebuild()
    }

    // — commit ---------------------------------------------------------------

    fun save() {
        val current = draft ?: return
        viewModelScope.launch {
            containers.save(
                current.copy(
                    // An unnamed container would be an unidentifiable tile on the
                    // home screen, so a blank field falls back rather than
                    // blocking Save.
                    name = current.name.trim().ifBlank { "Container" },
                    params = clampedParams(current),
                ),
            )
            _state.update { it.copy(finished = true) }
        }
    }

    fun delete() {
        val current = draft ?: return
        viewModelScope.launch {
            containers.delete(current.id)
            _state.update { it.copy(finished = true) }
        }
    }

    /**
     * Store what the editor was showing, not what was underneath it.
     *
     * A clamp can start holding after a value was set, because the param its
     * condition names was changed afterwards, and writing the stale value would
     * mean the file disagreed with the screen — the sort of gap that surfaces
     * much later as a container behaving unlike its settings. Running every value
     * back through [resolve] is generic: it clamps whatever the manifest says to
     * clamp and touches nothing else.
     *
     * Keys the manifest no longer declares are dropped rather than carried
     * forward. A container saved before Box64 was removed still has its
     * `box64.*` values in the document, and re-writing settings for an engine
     * that is not in the build would keep them alive forever.
     */
    private fun clampedParams(profile: ContainerProfile): Map<String, ParamValue> {
        val currentManifest = manifest ?: return profile.params
        val values = currentManifest.defaults() + profile.params
        return values.mapNotNull { (key, value) ->
            val spec = currentManifest.spec(key) ?: return@mapNotNull null
            key to (spec.resolve(values)?.value ?: value)
        }.toMap()
    }

    // — rendering model ------------------------------------------------------

    /**
     * Every group, every param, in manifest order.
     *
     * There is no filter here any more. The editor used to drop whatever the
     * "Show advanced" disclosure was hiding, which meant this function decided
     * on the user's behalf which of the app's own settings they were not
     * qualified to see — and a param that resolved to nothing visible took its
     * whole group with it. Hierarchy is the manifest's ordering now, and the
     * only thing that can remove a param from the screen is [resolve] failing to
     * give it a value at all.
     */
    private fun rebuild() {
        val currentManifest = manifest ?: return
        val current = draft ?: return
        val values = currentManifest.defaults() + current.params

        val groups = currentManifest.groups.mapNotNull { group ->
            val params = group.params
                .mapNotNull { spec -> spec.resolve(values)?.let { toEditorParam(it) } }
            if (params.isEmpty()) null else EditorGroup(group.id, group.title, group.help, params)
        }

        _state.update { it.copy(groups = groups) }
    }

    private fun toEditorParam(resolved: ResolvedParam): EditorParam {
        if (resolved.spec.type != ParamType.COMPONENT) return EditorParam(resolved)
        val type = ComponentType.entries.firstOrNull { it.wire == resolved.spec.componentType }
        val selector = (resolved.value as? ParamValue.Text)?.value.orEmpty()
        val resolution = components.resolve(type, selector)
        return EditorParam(
            resolved = resolved,
            componentId = resolution.resolved?.id,
            componentNote = resolution.note,
        )
    }
}
