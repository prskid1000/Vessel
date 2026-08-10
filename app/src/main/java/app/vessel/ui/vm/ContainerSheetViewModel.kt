package app.vessel.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ComponentType
import app.vessel.core.ContainerDiagnostics
import app.vessel.core.ContainerProfile
import app.vessel.core.SessionLogLimits
import kotlinx.coroutines.flow.first
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.core.params.ResolvedParam
import app.vessel.core.params.resolve
import app.vessel.data.ContainerRepository
import app.vessel.data.InstalledComponents
import app.vessel.data.ParamManifestStore
import app.vessel.data.SessionLogStore
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
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
)

/**
 * The Diagnostics section, with every number already worked out.
 *
 * The labels are composed here rather than in the composable for the reason the
 * rest of this view model exists: a byte count and a worst case are decisions
 * about wording and rounding, and the section should be handed the sentence
 * rather than the arithmetic.
 */
data class DiagnosticsUiState(
    val diagnostics: ContainerDiagnostics = ContainerDiagnostics(),
    /** The container's log directory, right now — `14.2 MB`. */
    val usageLabel: String = "0 B",
    /** That usage against the ceiling the current limits imply, for the bar. */
    val usageFraction: Float = 0f,
    /** `10 sessions · 480 MB at these limits`. */
    val ceilingLabel: String = "",
    val sessionCount: Int = 0,
    /** The one line the collapsed row carries: `all off`, or what is on. */
    val summary: String = "all off",
    /**
     * Whether this container has never been launched.
     *
     * The one-session warning says something extra when it is true: `WINEDEBUG`
     * is in `BOOTSTRAP_SESSION_ENV`, so whatever is armed also reaches `wineboot`
     * while the prefix is being built, and a `wineboot` given too much is a hang
     * with an empty `drive_c` two minutes later.
     */
    val neverLaunched: Boolean = true,
    /** The other containers this record can be copied to, as `id to name`. */
    val otherContainers: List<Pair<String, String>> = emptyList(),
) {
    /**
     * True while nothing in the translators group has been moved off its default.
     *
     * Derived from the declared lists rather than from named fields, so a fourth
     * translator or a third switch is covered without editing this.
     */
    val translatorsAreDefault: Boolean
        get() = diagnostics.subsystemLevels.isEmpty() &&
            diagnostics.subsystemFlags.isEmpty() &&
            diagnostics.turnipFlags.isEmpty()
}

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
    private val sessionLogs: SessionLogStore,
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
        if (!creating) refreshDiagnostics()
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

    /**
     * Replace the whole diagnostics record.
     *
     * One setter for the whole thing rather than one per control, because
     * [ContainerDiagnostics] owns the invariant that ties them together — a
     * dangerous value and its one-launch arm are set in the same copy, and a
     * per-field setter here would be a second place able to break that.
     *
     * Held in the draft like every other edit: nothing reaches the container
     * document until Save, which is what makes the sheet's Save mean the same
     * thing for every control on it.
     */
    fun setDiagnostics(diagnostics: ContainerDiagnostics) {
        val current = draft ?: return
        draft = current.copy(diagnostics = diagnostics)
        refreshDiagnostics()
    }

    /**
     * Every log this container has, gone.
     *
     * Immediate rather than deferred to Save, and that is the same rule the
     * sheet's Delete follows: this is an action on the device, not a setting to
     * be committed, and an action that waited for Save would be a delete the user
     * could cancel by swiping the sheet away.
     */
    fun deleteLogs() {
        val current = draft ?: return
        viewModelScope.launch {
            sessionLogs.deleteAll(current.id)
            refreshDiagnostics()
        }
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

    // — diagnostics ------------------------------------------------------------

    /**
     * What the log directory holds, cached between reads.
     *
     * Read off the disk rather than kept in the container document, because it is
     * a fact about the device and not about the container: a log pruned by
     * another screen, or an export the system reclaimed, would leave a stored
     * figure wrong with nothing to correct it.
     */
    private var logUsageBytes = 0L
    private var logSessionCount = 0

    /** Recompose the section now, and correct its two disk-backed numbers shortly. */
    private fun refreshDiagnostics() {
        _state.update { it.copy(diagnostics = diagnosticsState()) }
        val current = draft ?: return
        viewModelScope.launch {
            logUsageBytes = sessionLogs.usageBytes(current.id)
            logSessionCount = sessionLogs.sessionsNow(current.id).size
            copyTargets = containers.containers.first()
                .filterNot { it.id == current.id }
                .map { it.id to it.name }
            _state.update { it.copy(diagnostics = diagnosticsState()) }
        }
    }

    /**
     * Put this container's diagnostics on another one, now.
     *
     * Immediate rather than deferred to Save, like every other action that
     * changes something outside this sheet: Save commits *this* container, and a
     * copy that waited for it would be a change to a second container hidden
     * behind a button that does not mention it.
     *
     * The record is copied as it stands, arms included — the point of copying is
     * usually to run the same loud configuration against a second container, and
     * that container spends its own arm on its own next launch.
     */
    fun copyDiagnosticsTo(containerId: String) {
        val current = draft ?: return
        viewModelScope.launch {
            containers.get(containerId)?.let {
                containers.save(it.copy(diagnostics = current.diagnostics))
            }
        }
    }

    /** The other containers, for *Copy to another container*. Read once at load. */
    private var copyTargets: List<Pair<String, String>> = emptyList()

    private fun diagnosticsState(): DiagnosticsUiState {
        val profile = draft ?: return DiagnosticsUiState()
        val diagnostics = profile.diagnostics
        val ceiling = diagnostics.limits.worstCaseBytesPerContainer
        return DiagnosticsUiState(
            diagnostics = diagnostics,
            otherContainers = copyTargets,
            usageLabel = sizeLabel(logUsageBytes),
            // Against the ceiling the *current* limits imply, so raising a cap
            // visibly shortens the bar rather than leaving it where it was. The
            // bar's job is "how much of what you have allowed is spent".
            usageFraction = if (ceiling > 0) (logUsageBytes.toDouble() / ceiling).toFloat() else 0f,
            ceilingLabel = "${SessionLogLimits.SESSIONS_KEPT} sessions · " +
                "${sizeLabel(ceiling)} at these limits",
            sessionCount = logSessionCount,
            summary = summaryOf(diagnostics),
            neverLaunched = profile.lastRun == null,
        )
    }

    /**
     * The one line the collapsed Diagnostics row carries.
     *
     * A count of what is switched on, not of what has been *changed*: the log
     * limits are deliberately not counted, because they are neither on nor off
     * and the storage group already shows its own state. "all off" has to be the
     * literal truth for a fresh container, which is the whole reason this row
     * carries a state at all.
     */
    private fun summaryOf(diagnostics: ContainerDiagnostics): String {
        val on = diagnostics.wineChannels.size +
            diagnostics.subsystemLevels.size +
            diagnostics.subsystemFlags.size +
            diagnostics.turnipFlags.size
        return if (on == 0) "all off" else "$on on"
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
