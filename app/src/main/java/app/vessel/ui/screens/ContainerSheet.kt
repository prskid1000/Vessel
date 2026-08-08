package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.params.ParamSpec
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.core.params.ResolvedParam
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VCheckRow
import app.vessel.ui.components.VComponentReadout
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSheet
import app.vessel.ui.components.VSheetHeader
import app.vessel.ui.components.VStepper
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.vm.ContainerSheetUiState
import app.vessel.ui.vm.ContainerSheetViewModel
import app.vessel.ui.vm.EditorGroup
import app.vessel.ui.vm.EditorParam

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

    ContainerSheetContent(
        state = state,
        onDismiss = onDismiss,
        onName = viewModel::setName,
        onParam = viewModel::setParam,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onOpenLogs = { onOpenLogs(state.containerId) },
    )
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
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    VSheet(onDismiss = onDismiss) {
        VSheetHeader(
            title = if (state.creating) "New container" else state.name.ifBlank { "Container" },
            trailing = {
                if (state.error == null && !state.loading) {
                    VButton("Save", onSave, style = VButtonStyle.Primary)
                }
            },
        )

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

                // Nothing below this line changes a setting: one destructive
                // action and one destination.
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
        group.params.forEach { ParamControl(it, onParam) }
    }
}

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
    val help = listOfNotNull(spec.help, note).joinToString(" ").ifBlank { null }

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
