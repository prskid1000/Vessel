package app.vessel.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.ContainerDiagnostics
import app.vessel.core.params.ParamSpec
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.core.params.ResolvedParam
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VCaution
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VCheckRow
import app.vessel.ui.components.VComponentReadout
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VComboField
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSheet
import app.vessel.ui.components.VSheetHeader
import app.vessel.ui.components.VSheetRow
import app.vessel.ui.components.VStepper
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.vm.ContainerSheetUiState
import app.vessel.ui.vm.ContainerSheetViewModel
import app.vessel.ui.vm.EditorGroup
import app.vessel.ui.vm.EditorParam
import app.vessel.ui.vm.InputUiState

/**
 * A container's settings, over the container.
 *
 * New and edit are the same sheet: creating one is this with empty values and no
 * Delete or Session logs row. Two forms for one object is how a product ends up
 * with a setting that can be changed in one and not the other.
 *
 * Below the name, every control is rendered from `assets/params-manifest.json`.
 * There is one `when` in this file and it is over [ParamType]; no manifest key
 * appears anywhere in it. That is the boundary `docs/DESIGN.md` draws — adding a
 * knob is a data change, and the moment a key needs a special case here the
 * promise has quietly stopped being true.
 *
 * @param containerId null to create one.
 */
@Composable
fun ContainerSheet(
    containerId: String?,
    onDismiss: () -> Unit,
    onOpenLogs: (String) -> Unit,
    // Keyed on the container, so opening a second one does not reuse the first
    // one's draft — a sheet has no route, so nothing else would separate them.
    viewModel: ContainerSheetViewModel = hiltViewModel(key = containerId ?: NEW_KEY),
) {
    LaunchedEffect(containerId) { viewModel.open(containerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Save and Delete both end the sheet. Read once as a flag rather than through
    // a callback out of the view model, so a recomposition cannot dismiss twice.
    LaunchedEffect(state.finished) { if (state.finished) onDismiss() }

    val context = LocalContext.current
    // Comes back with no result — all-files access is a settings toggle, not a
    // dialog — so the grant is re-read on return rather than trusted from a
    // callback. Same contract as FilesScreen's launcher.
    val allFilesAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshAfterPermission() }

    // Placing controls takes the whole screen, and turns it landscape on the way.
    // The sheet is portrait and 421 dp wide; the thing being arranged is a
    // landscape overlay. See `TouchArrange`.
    var arranging by remember { mutableStateOf(false) }

    ContainerSheetContent(
        state = state,
        onDismiss = onDismiss,
        onName = viewModel::setName,
        onParam = viewModel::setParam,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onOpenLogs = { onOpenLogs(state.containerId) },
        onDiagnostics = viewModel::setDiagnostics,
        inputActions = InputEditorActions(
            onProfile = viewModel::saveInputProfile,
            onPickProfile = viewModel::setInputProfile,
            onRename = { name ->
                viewModel.saveInputProfile(viewModel.state.value.input.profile.copy(name = name))
            },
            onNewProfile = viewModel::newInputProfile,
            onDuplicate = viewModel::duplicateInputProfile,
            onDelete = viewModel::deleteInputProfile,
            onImportText = viewModel::importInputProfile,
            onExportText = { viewModel.exportInputProfile(it) },
            onSelect = { viewModel.selectTouchControl(it) },
            onDismissNotice = viewModel::dismissInputNotice,
            onArrange = { arranging = true },
        ),
        onDeleteLogs = viewModel::deleteLogs,
        onCopyDiagnostics = viewModel::copyDiagnosticsTo,
        onGrantStorage = {
            allFilesAccess.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
    )

    if (arranging) {
        TouchArrangeDialog(
            layout = state.input.profile.overlay,
            selected = state.input.selectedTouchControl,
            onSelect = { viewModel.selectTouchControl(it) },
            onLayout = { viewModel.saveInputProfile(state.input.profile.copy(touch = it)) },
            onDone = { arranging = false },
        )
    }
}

private const val NEW_KEY = "new-container"

@Composable
private fun ContainerSheetContent(
    state: ContainerSheetUiState,
    onDismiss: () -> Unit,
    onName: (String) -> Unit,
    onParam: (String, ParamValue) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onOpenLogs: () -> Unit,
    onDiagnostics: (ContainerDiagnostics) -> Unit,
    inputActions: InputEditorActions,
    onDeleteLogs: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onGrantStorage: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    // **Diagnostics takes the sheet over rather than growing it.** It is more
    // controls than the settings form above it, and a sheet holding both would be
    // past the height `docs/DESIGN.md:298-303` already calls full. Taking over
    // also fixes the header: while it is open the commit action is meaningless
    // there, so Save becomes a chevron that puts the form back.
    var diagnosticsOpen by remember { mutableStateOf(false) }

    /**
     * Input takes the sheet over for the same reason Diagnostics does: what is
     * behind the row is a table, not a control. Today it is only the per-container
     * profile picker — *which* table this container starts with, which is a
     * setting about the container and belongs here rather than in a session.
     */
    var inputOpen by remember { mutableStateOf(false) }

    // Back closes Diagnostics before it closes the sheet. Registered inside
    // VSheet's own handler, so it wins while it is enabled — the innermost
    // enabled callback is the one the dispatcher runs.
    BackHandler(enabled = diagnosticsOpen) { diagnosticsOpen = false }
    BackHandler(enabled = inputOpen) { inputOpen = false }

    val editorState = InputEditorState(
        profile = state.input.profile,
        profiles = state.input.profiles,
        activeProfileId = state.input.profileId,
        missingProfile = state.input.missing,
        containerName = state.name,
        guest = state.guestGeometry,
        // Cold: the diagram cannot light up, the overlay is a preview, and there
        // is no session for a change to take effect in.
        live = false,
        touchVisible = state.touchVisible,
        selected = state.input.selectedTouchControl,
        notice = state.input.notice,
    )

    VSheet(
        onDismiss = onDismiss,
        // The editor brings its own scrolling — it is one lazy list from the map
        // to the profile — and a scroll inside a scroll measures the inner one
        // with infinite height.
        scrollable = !inputOpen,
        header = {
            if (diagnosticsOpen) {
                DiagnosticsHeader(
                    containerName = state.name.ifBlank { "Container" },
                    onCollapse = { diagnosticsOpen = false },
                )
            } else if (inputOpen) {
                InputEditorHeader(
                    state = editorState,
                    actions = inputActions,
                    leading = {
                        VIconAction(
                            icon = VIcons.ArrowLeft,
                            contentDescription = "Back to the container's settings",
                            onClick = { inputOpen = false },
                            style = VButtonStyle.Ghost,
                        )
                    },
                )
            } else {
                VSheetHeader(
                    title = if (state.creating) {
                        "New container"
                    } else {
                        state.name.ifBlank { "Container" }
                    },
                    trailing = {
                        if (state.error == null && !state.loading) {
                            // **Creating is blocked without storage access; editing
                            // is not.** The permission decides whether the
                            // provisioner can map D: while it builds the prefix,
                            // and that only happens once — so the cost of getting
                            // it wrong falls entirely on creation. An existing
                            // container has already been built, and refusing to
                            // let its name be changed over a drive would be a
                            // gate on the wrong action.
                            // **Beside Save rather than a row of its own.**
                            // Input was a full VSheetRow with a title, two lines
                            // of help and a chip — the largest thing in the sheet,
                            // for a destination rather than a setting. Everything
                            // else in the body changes a value in place; this one
                            // opens another screen, and that belongs with the
                            // other header action.
                            VButton(
                                "Input",
                                { inputOpen = true },
                                style = VButtonStyle.Ghost,
                            )
                            VButton(
                                "Save",
                                onSave,
                                style = VButtonStyle.Primary,
                                enabled = !state.creating || state.canMapStorage,
                            )
                        }
                    },
                )
            }
        },
    ) {
        if (diagnosticsOpen) {
            DiagnosticsPanel(
                state = state.diagnostics,
                onChange = onDiagnostics,
                onDeleteLogs = onDeleteLogs,
                onCopyTo = onCopyDiagnostics,
            )
            return@VSheet
        }
        if (inputOpen) {
            InputEditor(
                state = editorState,
                actions = inputActions,
            )
            return@VSheet
        }
        when {
            state.error != null -> Text(
                state.error,
                style = Vessel.type.body,
                color = Vessel.colors.danger,
            )

            state.loading -> Text(
                "reading settings",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
            )

            else -> {
                VLabeledField(label = "Name") {
                    VTextField(state.name, onName, placeholder = "Container")
                }
                state.groups.forEach { group -> ParamGroup(group, onParam) }

                // **Asked for here because here is where the answer is used,
                // and required rather than offered.** ContainerProvisioner maps
                // the phone's storage to D: while it builds the prefix, and
                // mapSharedStorage returns false without this grant — so a
                // container created without it came up with a C: and nothing
                // else, and nothing on screen ever said why.
                //
                // A container is not allowed to be created in that state. It is
                // a one-shot decision that shapes the prefix, and the rest of
                // the product now assumes both drives: Add-a-program and the
                // file browser are both disabled until D: exists, so a container
                // built without it is one you cannot put a program into.
                //
                // Editing is deliberately still allowed — see the Save button.
                if (!state.canMapStorage) {
                    VCaution(
                        if (state.creating) {
                            "Vessel needs access to the phone's storage before it " +
                                "can build this container: without it there is no " +
                                "D: drive, and no way to add a program."
                        } else {
                            "Vessel cannot see the phone's storage, so this " +
                                "container has no D: drive. Allow it and D: is " +
                                "mapped on the next launch."
                        },
                    )
                    VButton(
                        "Allow storage access",
                        onGrantStorage,
                        style = VButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // **A route rather than a control, because what is behind it is a
                // table.** It sits in the manifest's own order — after the display
                // settings, before the destructive actions — and it exists on a
                // container being created as well, unlike everything below the
                // rule: picking which bindings a container starts with needs no
                // prefix and no session, and it is the one thing a user setting up
                // a container with a pad in their hands will want first.

                // Nothing below this line changes a setting: one destructive
                // action, one destination, and one section that changes what the
                // next run *says* rather than how it runs.
                //
                // **Nothing below it exists on a container being created**, and
                // Diagnostics is the reason that matters rather than a tidiness
                // rule: a container with no prefix has no session to diagnose and
                // no logs to raise a limit on, and the dangerous tier reaches
                // `wineboot` — see BOOTSTRAP_SESSION_ENV — so arming it before
                // the prefix exists would make the first launch look like a hang.
                if (!state.creating) {
                    VRule(verticalMargin = Vessel.metrics.s6)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VButton(
                            "Delete",
                            { confirmingDelete = true },
                            style = VButtonStyle.Danger,
                            icon = VIcons.Trash,
                        )
                        Row(Modifier.weight(1f)) {}
                        // The pairing reads correctly and is why Diagnostics is a
                        // button of the same weight rather than a row above them:
                        // Session logs is what the last run said, Diagnostics is
                        // what the next one will be asked to say.
                        VButton(
                            "Diagnostics",
                            { diagnosticsOpen = true },
                            style = VButtonStyle.Secondary,
                            icon = VIcons.Info,
                        )
                        VButton(
                            "Session logs",
                            onOpenLogs,
                            style = VButtonStyle.Secondary,
                            icon = VIcons.List,
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        VConfirmSheet(
            title = "Delete ${state.name}?",
            message = "Its settings are removed from this device, along with the programs added " +
                "to it. Installed components are shared and are not touched.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * One manifest group.
 *
 * The group's title is deliberately *not* drawn. The manifest declares two groups
 * for four controls, and a heading above a single field on a sheet this short is
 * a label for a label. The group's help sentence is kept, because that one says
 * something the field names do not.
 */
@Composable
private fun ParamGroup(group: EditorGroup, onParam: (String, ParamValue) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
        // Two selects to a row where the manifest declares two in a row, and one
        // per row otherwise. A *shape* rule rather than a key rule: it is about
        // the control being a short closed choice, and it keeps the boundary
        // intact — Resolution and Frame rate pair up because they are adjacent
        // enums, not because anything here knows their names. Anything with a
        // help sentence long enough to matter still gets the full width, because
        // the sentence is under the field and two of them side by side is four
        // lines of prose in two columns.
        var index = 0
        while (index < group.params.size) {
            val here = group.params[index]
            val next = group.params.getOrNull(index + 1)
            if (here.pairs() && next != null && next.pairs()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
                    Column(Modifier.weight(1f)) { ParamControl(here, onParam) }
                    Column(Modifier.weight(1f)) { ParamControl(next, onParam) }
                }
                index += 2
            } else {
                ParamControl(here, onParam)
                index += 1
            }
        }
    }
}

/** A short closed choice, which is what can sit in half a sheet's width. */
private fun EditorParam.pairs(): Boolean =
    resolved.spec.type == ParamType.ENUM || resolved.spec.type == ParamType.ENUM_OR_TEXT

/**
 * One composable per param *type*, and that is the entire dispatch.
 *
 * The bounds, the clamp reason and the resolved component are all decided by the
 * time they get here — see [EditorParam] — so each branch is the control and the
 * value it writes back, and nothing else.
 */
@Composable
private fun ParamControl(param: EditorParam, onParam: (String, ParamValue) -> Unit) {
    val spec = param.resolved.spec
    val value = param.resolved.value
    val note = param.resolved.clampReason ?: param.componentNote

    /*
     * **`spec.help` is deliberately not rendered, and this sheet is its only
     * reader** — so it is now carried in the manifest and shown nowhere. That
     * is the honest state and it is a deliberate one: the text is worth keeping
     * as the field's documentation next to its definition, and it was not worth
     * the three paragraphs of static prose it put under three controls whose
     * titles already say what they do, which pushed the controls themselves off
     * screen. The group's own help sentence is unaffected and still renders.
     *
     * What survives is [note] — the clamp reason and the component note — and
     * the warning below. Those are the opposite kind of text: they are not
     * documentation, they are *this* value's current situation ("this was
     * lowered because the screen cannot show it"), they appear only when there
     * is something to say, and nothing else on screen says it.
     */
    val help = note

    // A boolean is a row rather than a field: the switch *is* the control, and a
    // label above an empty box with a switch beside it reads as a broken field.
    if (spec.type == ParamType.BOOL) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
            ) {
                Text(spec.title, style = Vessel.type.body)
                if (help != null) {
                    Text(help, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
                }
            }
            VToggle(
                checked = (value as? ParamValue.Flag)?.value == true,
                onCheckedChange = { onParam(spec.key, ParamValue.Flag(it)) },
            )
        }
        param.resolved.warning?.let {
            Text(
                it,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.warn,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
        return
    }

    VLabeledField(label = spec.title, help = help) {
        when (spec.type) {
            ParamType.ENUM -> VDropdownField(
                options = spec.options,
                labelFor = spec::label,
                selected = (value as? ParamValue.Text)?.value,
                onSelect = { onParam(spec.key, ParamValue.Text(it)) },
            )

            // VESSEL note: the presets plus anything the setting's parser takes.
            // `display.resolution` is the case this exists for — parseGeometry
            // has always accepted any WxH, so the closed list was narrowing the
            // product below what the code supports.
            ParamType.ENUM_OR_TEXT -> {
                val current = (value as? ParamValue.Text)?.value.orEmpty()
                VComboField(
                    value = spec.label(current).takeIf { it != current && current in spec.options } ?: current,
                    options = spec.options.map(spec::label),
                    onValueChange = { typed ->
                        // A label came back if they picked; a raw value if they
                        // typed. Map labels home so the document never stores
                        // "1280 x 720  (720p)".
                        val wire = spec.options.firstOrNull { spec.label(it) == typed } ?: typed
                        onParam(spec.key, ParamValue.Text(wire))
                    },
                    placeholder = "1280x720",
                    isError = current.isNotBlank() &&
                        current !in spec.options &&
                        !Regex("""^\s*\d{1,5}\s*[xX]\s*\d{1,5}\s*$""").matches(current),
                )
            }

            ParamType.TEXT -> VTextField(
                value = (value as? ParamValue.Text)?.value.orEmpty(),
                onValueChange = { onParam(spec.key, ParamValue.Text(it)) },
                placeholder = "name=native,builtin",
            )

            ParamType.INT -> VStepper(
                value = (value as? ParamValue.Count)?.value ?: 0,
                min = param.resolved.min ?: 0,
                max = param.resolved.max ?: Int.MAX_VALUE,
                onChange = { onParam(spec.key, ParamValue.Count(it)) },
            )

            ParamType.MULTI -> {
                val chosen = (value as? ParamValue.Choices)?.values.orEmpty()
                Column {
                    spec.options.forEach { option ->
                        VCheckRow(
                            label = spec.label(option),
                            checked = option in chosen,
                            onToggle = {
                                val next =
                                    if (option in chosen) chosen - option else chosen + option
                                // Manifest order, not click order: the value is
                                // read back as a string, and a stable order makes
                                // two containers with the same flags compare equal.
                                onParam(
                                    spec.key,
                                    ParamValue.Choices(spec.options.filter { it in next }),
                                )
                            },
                        )
                    }
                }
            }

            ParamType.COMPONENT -> VComponentReadout(
                selector = (value as? ParamValue.Text)?.value.orEmpty(),
                resolvedId = param.componentId,
            )

            ParamType.BOOL -> Unit // handled above
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun ContainerSheetPreview() {
    VesselTheme {
        ContainerSheetContent(
            state = ContainerSheetUiState(
                loading = false,
                creating = false,
                name = "Display proof",
                containerId = "c1",
                groups = listOf(
                    EditorGroup(
                        id = "display",
                        title = "Display",
                        help = null,
                        params = listOf(
                            preview(
                                ParamSpec(
                                    key = "display.resolution",
                                    title = "Resolution",
                                    help = "Lower resolutions gain a lot of performance on " +
                                        "this phone.",
                                    type = ParamType.ENUM,
                                    options = listOf("1280x720", "1920x1080"),
                                ),
                                ParamValue.Text("1280x720"),
                            ),
                            preview(
                                ParamSpec(
                                    key = "display.fileManager",
                                    title = "Open a file manager",
                                    help = "Starts Wine's own file manager alongside the desktop.",
                                    type = ParamType.BOOL,
                                ),
                                ParamValue.Flag(true),
                            ),
                        ),
                    ),
                ),
            ),
            onDismiss = {},
            onName = {},
            onParam = { _, _ -> },
            onSave = {},
            onDelete = {},
            onOpenLogs = {},
            onDiagnostics = {},
            inputActions = InputEditorActions(),
            onDeleteLogs = {},
            onCopyDiagnostics = {},
            onGrantStorage = {},
        )
    }
}

private fun preview(spec: ParamSpec, value: ParamValue) = EditorParam(
    ResolvedParam(
        spec = spec,
        value = value,
        min = spec.min,
        max = spec.max,
        clampReason = null,
        warning = null,
    ),
)
