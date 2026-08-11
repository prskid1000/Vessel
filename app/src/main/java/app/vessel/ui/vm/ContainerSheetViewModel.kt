package app.vessel.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.ComponentType
import app.vessel.core.ContainerDiagnostics
import app.vessel.core.ContainerProfile
import app.vessel.core.DisplayGeometry
import app.vessel.core.SessionLogLimits
import app.vessel.core.DisplayParams
import app.vessel.core.parseGeometry
import kotlinx.coroutines.flow.first
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.core.params.ResolvedParam
import app.vessel.core.params.resolve
import app.vessel.data.AndroidDrives
import app.vessel.data.ContainerRepository
import app.vessel.data.ImportResult
import app.vessel.data.InputProfileRepository
import app.vessel.data.InputProfileTransfer
import app.vessel.data.InstalledComponents
import app.vessel.input.InputProfile
import app.vessel.data.ParamManifestStore
import app.vessel.data.SessionLogStore
import app.vessel.ui.shell.AppRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
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
    /**
     * Whether this app may map the phone's storage into the container as `D:`.
     *
     * False is the *interesting* state and the reason this is here at all.
     * All-files access is a per-install grant, so a fresh install has no `D:`
     * and nothing ever asked for it — the manifest declared the permission and
     * left the user to find Android's settings page on their own. Creating a
     * container is the moment the answer matters, because
     * [app.vessel.data.ContainerProvisioner] maps the drive while it builds the
     * prefix.
     *
     * **A blocker, for creation only.** Save is disabled while this is false on
     * a container being created: the mapping happens once, while the prefix is
     * built, and the rest of the product now assumes both drives — Add-a-program
     * and the file browser are both disabled until `D:` exists, so a container
     * created without it is one nothing can be put into.
     *
     * Editing an existing container is not gated. It has already been built, and
     * because the provisioner runs `mapSharedStorage` on *every* provision
     * rather than once, granting the permission later still picks the drive up
     * on the next launch.
     */
    val canMapStorage: Boolean = true,
    val input: InputUiState = InputUiState(),
    /**
     * The desktop size this container will come up at, for the Input screen's
     * subtitle.
     *
     * Resolved from `display.resolution` rather than stored, because `native`
     * only becomes a number once something has measured the panel — and null is
     * the honest answer for a container that has never been launched.
     */
    val guestGeometry: DisplayGeometry? = null,
    /** Whether this container draws the touch overlay. See `ContainerInput`. */
    val touchVisible: Boolean = true,
)

/**
 * Which input profile this container starts with.
 *
 * [missing] is the case worth naming: the container names a profile that has
 * since been deleted. It resolves to the built-in default and the container is
 * **not** rewritten — a profile is shared between containers and deleting one is
 * allowed — so the row says so in words rather than quietly forgetting, and the
 * pointer is only cleared if the user picks something.
 */
data class InputUiState(
    /** Null means the container has chosen nothing, which is the built-in default. */
    val profileId: String? = null,
    val profileName: String = "",
    val missing: Boolean = false,
    /** The resolved profile, which is what the cold editor edits. */
    val profile: InputProfile = InputProfile.Default,
    /** Every stored profile, for the Profiles tab. The built-in default is not one. */
    val profiles: List<InputProfile> = emptyList(),
    /** How many controls the resolved profile puts on the overlay. */
    val overlayCount: Int = 0,
    /** An import that was refused, said once. */
    val notice: String? = null,
    /** The profile as it is stored, for [dirty] to compare the draft against. */
    val saved: InputProfile = InputProfile.Default,
    /** Whether [profile] has edits that are not on disk. */
    val dirty: Boolean = false,
    /**
     * Which overlay control the cold editor has selected.
     *
     * Here rather than on the display seam, which is where the *session's*
     * selection lives: nothing is running, so there is no overlay to select on
     * and no second writer to keep in step.
     */
    val selectedTouchControl: String? = null,
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
    private val sessionLogs: SessionLogStore,
    private val drives: AndroidDrives,
    private val inputProfiles: InputProfileRepository,
    private val json: Json,
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
                canMapStorage = drives.canMap,
            )
        }
        rebuild()
        refreshInput()
        if (!creating) refreshDiagnostics()
    }

    // — input profile ---------------------------------------------------------

    /**
     * Point this container at a profile, or at nothing.
     *
     * **Nothing is stored as null rather than as the default profile's id.** A
     * container that has chosen the default has chosen nothing in particular, and
     * a null resolves to whatever the default currently is — so a container never
     * has to be rewritten when the default is edited or renamed.
     */
    fun setInputProfile(id: String?) {
        val current = draft ?: return
        draft = current.copy(input = current.input.copy(profileId = id))
        refreshInput()
    }

    /**
     * Whether the overlay is drawn, from the cold editor.
     *
     * **Into the draft, unlike the profile edits below it.** This one is a field
     * of *this container* rather than of a shared profile, so it follows the same
     * rule as every other setting on this sheet and lands on Save. The session
     * has its own path — `SessionViewModel.setTouchControlsVisible` — because
     * there the change has to reach a running display as well as the disc.
     *
     * It existed as a toggle with no handler at all until this: the sheet built
     * its actions without one, `InputEditorActions` defaults it to `{}`, and so
     * the control rendered with the stored value and silently refused to move.
     */
    fun setTouchVisible(visible: Boolean) {
        val current = draft ?: return
        draft = current.copy(input = current.input.copy(touchVisible = visible))
        // **Not `refreshInput()`.** That re-reads the profile from disk and would
        // throw away an unsaved binding edit — toggling the overlay is not a
        // reason to lose one. This is a container field; it moves on its own.
        _state.update { it.copy(touchVisible = visible) }
    }

    /**
     * Store an edit made in the cold editor.
     *
     * **Immediate, and not held in the draft like every other field on this
     * sheet.** A profile lives in its own document and is shared between
     * containers, so it is not this container's to hold: deferring it to Save
     * would mean a binding change vanishing when the sheet is swiped away, and
     * committing it would mean this container's Save writing a second document
     * that another container is also using. The same rule Delete and *Copy
     * diagnostics to* already follow.
     *
     * **The default is edited in place, like anything else.** It used to be a
     * constant that could not be saved, so the first edit forked a copy under a
     * new name — which meant a slider drag left a profile nobody asked for. It is
     * an ordinary record now; see `InputProfileRepository`. The consequence worth
     * knowing is that editing the default changes it for **every** container that
     * has not chosen something else, which is what a shared default means.
     */
    fun saveInputProfile(next: InputProfile) {
        // Drafted, not written. Nothing is running for it to apply to, so this is
        // purely what the editor shows until [commitInputProfile]. One rule for
        // every profile, the default included — the fork that used to live here
        // is gone with the constant it existed for; see `InputProfileRepository`.
        _state.update {
            it.copy(input = it.input.copy(profile = next, dirty = next != it.input.saved))
        }
    }

    /** Write the drafted profile. The Save button beside its name. */
    fun commitInputProfile() {
        val next = _state.value.input.profile
        viewModelScope.launch {
            inputProfiles.save(next)
            refreshInput()
        }
    }

    /** A copy of what this container is on, and this container moves to it. */
    fun newInputProfile() {
        viewModelScope.launch {
            val copy = inputProfiles.duplicate(_state.value.input.profile)
            setInputProfile(copy.id)
        }
    }

    fun duplicateInputProfile(profile: InputProfile) {
        viewModelScope.launch {
            inputProfiles.duplicate(profile)
            refreshInput()
        }
    }

    /**
     * Delete a profile, and leave every container's pointer at it alone.
     *
     * A stale id resolves to the built-in default on the next launch and the
     * sheet says so in words; hunting the container document to clear pointers
     * would make deleting a profile a write to the *other* file, which is the
     * coupling the two documents exist to avoid. This container's own draft moves
     * to the default only because it is the one on screen.
     */
    fun deleteInputProfile(profile: InputProfile) {
        if (profile.isBuiltInDefault) return
        viewModelScope.launch {
            inputProfiles.delete(profile.id)
            if (draft?.input?.profileId == profile.id) setInputProfile(null) else refreshInput()
        }
    }

    fun exportInputProfile(profile: InputProfile): String =
        InputProfileTransfer.export(json, profile)

    fun importInputProfile(text: String) {
        viewModelScope.launch {
            if (text.isBlank()) {
                _state.update { it.copy(input = it.input.copy(notice = "That file could not be read.")) }
                return@launch
            }
            val taken = inputProfiles.profiles.first().map { it.name }
            when (val result = InputProfileTransfer.import(json, text, taken)) {
                is ImportResult.Refused ->
                    _state.update { it.copy(input = it.input.copy(notice = result.reason)) }

                is ImportResult.Ok -> {
                    inputProfiles.save(result.profile)
                    setInputProfile(result.profile.id)
                }
            }
        }
    }

    fun dismissInputNotice() =
        _state.update { it.copy(input = it.input.copy(notice = null)) }

    fun selectTouchControl(id: String?) =
        _state.update { it.copy(input = it.input.copy(selectedTouchControl = id)) }

    private fun refreshInput() {
        val current = draft ?: return
        viewModelScope.launch {
            // `profiles` carries the default first, edited or seeded, so both
            // "which one is this container on" and "what is in the list" come
            // from one place and cannot disagree about the default's name.
            val stored = inputProfiles.profiles.first()
            val default = stored.first { it.isBuiltInDefault }
            val wanted = current.input.profileId
            val found = stored.firstOrNull { it.id == wanted }
            val resolved = found ?: default
            val geometry = (current.params[DisplayParams.RESOLUTION] as? ParamValue.Text)
                ?.value
                ?.takeIf { text -> text.contains('x', ignoreCase = true) }
                ?.let { text -> runCatching { parseGeometry(text) }.getOrNull() }
            _state.update {
                it.copy(
                    guestGeometry = geometry,
                    touchVisible = current.input.touchVisible,
                    input = it.input.copy(
                        profileId = wanted,
                        profileName = resolved.name,
                        missing = wanted != null && found == null,
                        profile = resolved,
                        saved = resolved,
                        dirty = false,
                        profiles = stored,
                        overlayCount = resolved.touch.controls.size,
                    ),
                )
            }
        }
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
     * **Written now, not at Save, and that is a deliberate exception.** It was
     * held in the draft like every other field, and the result was a switch that
     * did nothing: you turn on a channel to answer a question, close the sheet to
     * go and reproduce the thing, and the setting goes with it. Reported as
     * "I turned it on, why is it not on" — twice, against `oss`, and it would
     * have happened to every flag on the panel equally.
     *
     * The precedent is beside this: [deleteLogs] and `copyDiagnosticsTo` are
     * already immediate, on the grounds that they are *acts of observation*
     * rather than settings to be committed. Arming a log is the same kind of
     * thing. Cancelling the sheet no longer reverts it, which is the price and
     * is the lesser surprise.
     *
     * A container being created has no record to write to, so there it stays in
     * the draft — Save creates the container and carries it in the same write.
     */
    fun setDiagnostics(diagnostics: ContainerDiagnostics) {
        val current = draft ?: return
        draft = current.copy(diagnostics = diagnostics)
        refreshDiagnostics()
        if (_state.value.creating) return
        viewModelScope.launch { containers.save(draft ?: return@launch) }
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

    /**
     * Re-read the storage grant after a trip to Android's settings.
     *
     * The grant is a toggle on a settings page rather than a dialog, so the
     * activity result carries nothing and whether it was given is only knowable
     * by asking again. Same reason as
     * [app.vessel.ui.vm.FilesViewModel.refreshAfterPermission].
     */
    fun refreshAfterPermission() =
        _state.update { it.copy(canMapStorage = drives.canMap) }

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
            neverLaunched = profile.lastRun == null,
        )
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
