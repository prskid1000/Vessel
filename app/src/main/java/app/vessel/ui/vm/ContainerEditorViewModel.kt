package app.vessel.ui.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ArchProfile
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

/**
 * One group as the editor draws it — the manifest's own grouping, filtered to
 * what applies right now.
 */
data class EditorGroup(
    val id: String,
    val title: String,
    val help: String?,
    val advanced: Boolean,
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
    val archProfile: ArchProfile = ArchProfile.UNIVERSAL,
    val groups: List<EditorGroup> = emptyList(),
    val showAdvanced: Boolean = false,
    /** Whether the disclosure has anything behind it for this architecture. */
    val hasAdvanced: Boolean = false,
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
 * the whole promise of the manifest: adding a `BOX64_DYNAREC_*` knob is a data
 * change, and if a key ever needs code, it has leaked.
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
                archProfile = profile.archProfile,
            )
        }
        rebuild()
    }

    // — edits ----------------------------------------------------------------

    fun setName(name: String) {
        draft = draft?.copy(name = name)
        _state.update { it.copy(name = name) }
    }

    /**
     * Only meaningful while creating.
     *
     * ARCHITECTURE.md is explicit that the profile decides what Wine itself is
     * compiled as, which cannot change once a container's tree exists — so the
     * editor shows it as a fact after the first save rather than as a control
     * that would silently do nothing.
     */
    fun setArchProfile(profile: ArchProfile) {
        if (!_state.value.creating) return
        draft = draft?.copy(archProfile = profile)
        _state.update { it.copy(archProfile = profile) }
        rebuild()
    }

    fun setParam(key: String, value: ParamValue) {
        val current = draft ?: return
        draft = current.copy(params = current.params + (key to value))
        // A rebuild rather than a targeted update, because one param can move
        // another's ceiling: choosing WowBox64 drops box64.CALLRET to 1.
        rebuild()
    }

    fun toggleAdvanced() {
        _state.update { it.copy(showAdvanced = !it.showAdvanced) }
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
     * A clamp can start holding after a value was set — pick WowBox64 with
     * `box64.CALLRET` already at 2 and the control drops to 1 — and writing the
     * stale 2 would mean the file disagreed with the screen, which is the sort of
     * gap that surfaces much later as a container behaving unlike its settings.
     * Running every value back through [resolve] is generic: it clamps whatever
     * the manifest says to clamp and touches nothing else.
     */
    private fun clampedParams(profile: ContainerProfile): Map<String, ParamValue> {
        val currentManifest = manifest ?: return profile.params
        val values = currentManifest.defaults() + profile.params
        return values.mapValues { (key, value) ->
            currentManifest.spec(key)?.resolve(values)?.value ?: value
        }
    }

    // — rendering model ------------------------------------------------------

    private fun rebuild() {
        val currentManifest = manifest ?: return
        val current = draft ?: return
        val showAdvanced = _state.value.showAdvanced
        val values = currentManifest.defaults() + current.params

        // Every param that applies to this architecture, advanced or not. The
        // disclosure only exists if hiding something, so this is computed before
        // the advanced filter rather than after it.
        val applicable = currentManifest.groups.map { group ->
            group to group.params.filter { it.appliesTo(current.archProfile) }
        }
        val hasAdvanced = applicable.any { (group, params) ->
            (group.advanced && params.isNotEmpty()) || params.any { it.advanced }
        }

        val groups = applicable.mapNotNull { (group, params) ->
            if (group.advanced && !showAdvanced) return@mapNotNull null
            val visible = params
                .filter { showAdvanced || !it.advanced }
                .mapNotNull { spec -> spec.resolve(values)?.let { toEditorParam(it) } }
            if (visible.isEmpty()) {
                null
            } else {
                EditorGroup(group.id, group.title, group.help, group.advanced, visible)
            }
        }

        _state.update { it.copy(groups = groups, hasAdvanced = hasAdvanced) }
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
