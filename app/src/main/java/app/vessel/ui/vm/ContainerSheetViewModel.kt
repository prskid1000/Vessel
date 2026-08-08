package app.vessel.ui.vm

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
import app.vessel.ui.shell.AppRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The id the sheet is given when there is no container yet. */
const val NEW_CONTAINER: String = "new"

/** One group as the sheet draws it — the manifest's own grouping, in its order. */
data class EditorGroup(
    val id: String,
    val title: String,
    val help: String?,
    val params: List<EditorParam>,
)

/**
 * One param, ready to draw.
 *
 * [componentId] and [componentNote] are filled for [ParamType.COMPONENT] only, by
 * resolving the selector against what is installed. Doing it here rather than in
 * the composable is what keeps the renderer to one branch per *type*: the control
 * receives a resolved id or a null, and never learns what a `.wcp` is.
 */
data class EditorParam(
    val resolved: ResolvedParam,
    val componentId: String? = null,
    val componentNote: String? = null,
)

data class ContainerSheetUiState(
    val loading: Boolean = true,
    /** True for a container that has not been saved yet. */
    val creating: Boolean = false,
    val name: String = "",
    /** Empty until saved; the sheet needs it to open this container's logs. */
    val containerId: String = "",
    val groups: List<EditorGroup> = emptyList(),
    /** Set when the manifest or the container could not be read. Shown, not swallowed. */
    val error: String? = null,
    /** One-shot: the sheet closes and this view model is done with. */
    val finished: Boolean = false,
)

/**
 * The container sheet — five fields, and the manifest decides which five.
 *
 * **It is not a screen any more.** Creating and editing a container were two
 * pushed destinations with toolbars and back arrows, for a form whose whole
 * content is a name and four settings *about the card the user just tapped*.
 * Pushing a screen to show them threw away the context that made them make sense.
 *
 * What did not change is that everything below the name is rendered from
 * `assets/params-manifest.json`, and the manifest currently declares exactly the
 * four the design asks for — resolution, frame rate, the file manager toggle and
 * the DLL overrides. That is not a coincidence to rely on: the sheet draws
 * whatever the manifest holds, and a fifth entry would appear here without a line
 * of UI changing. If the manifest ever grows past what a sheet can hold, the
 * answer is to cut knobs, not to add a disclosure.
 *
 * **No `SavedStateHandle`.** A sheet has no route to read an argument off, so
 * [open] is called once by the composable that raises it. Keyed by container id
 * at the call site, so opening a second container does not reuse the first one's
 * draft.
 */
@HiltViewModel
class ContainerSheetViewModel @Inject constructor(
    private val containers: ContainerRepository,
    private val manifests: ParamManifestStore,
    private val components: InstalledComponents,
    private val registry: AppRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(ContainerSheetUiState())
    val state: StateFlow<ContainerSheetUiState> = _state.asStateFlow()

    /** The container being edited. Persisted only when Save is pressed. */
    private var draft: ContainerProfile? = null
    private var manifest: ParamManifest? = null
    private var opened = false

    /**
     * Load [containerId], or a fresh draft when it is null or [NEW_CONTAINER].
     *
     * Idempotent, because it is called from a `LaunchedEffect` and composition is
     * not a promise about how many times something happens.
     */
    fun open(containerId: String?) {
        if (opened) return
        opened = true
        viewModelScope.launch { load(containerId ?: NEW_CONTAINER) }
    }

    private suspend fun load(containerId: String) {
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
                containerId = profile.id,
            )
        }
        rebuild()
    }

    // — edits ----------------------------------------------------------------

    /**
     * Rename.
     *
     * Anything the user types is accepted. A container's directory is its UUID
     * put through `ContainerPaths.safeName`, so the name never reaches the
     * filesystem and there is nothing here to sanitise or limit.
     */
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
                    // An unnamed container would be an unidentifiable card on the
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
            // The programs inside it go with it. A shortcut whose container has
            // been deleted is a tile that launches nothing.
            registry.removeAllIn(current.id)
            _state.update { it.copy(finished = true) }
        }
    }

    /**
     * Store what the sheet was showing, not what was underneath it.
     *
     * A clamp can start holding after a value was set, because the param its
     * condition names was changed afterwards, and writing the stale value would
     * mean the file disagreed with the screen. Running every value back through
     * [resolve] is generic: it clamps whatever the manifest says to clamp and
     * touches nothing else.
     *
     * Keys the manifest no longer declares are dropped rather than carried
     * forward, so settings for a component that has left the build do not live on
     * in every container document.
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
     * Every group, every param, in manifest order — no filter. Hierarchy is the
     * manifest's ordering, and the only thing that can remove a param from the
     * sheet is [resolve] failing to give it a value at all.
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
