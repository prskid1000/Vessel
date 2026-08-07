package app.vessel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VCheckRow
import app.vessel.ui.components.VChoiceChip
import app.vessel.ui.components.VChoiceRow
import app.vessel.ui.components.VComponentReadout
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VParamRow
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VStepper
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.vm.ContainerEditorViewModel
import app.vessel.ui.vm.EditorGroup
import app.vessel.ui.vm.EditorParam
import app.vessel.ui.vm.EditorUiState

/**
 * Pushed 5 — create and edit a container.
 *
 * Below the name, the whole screen is rendered from
 * `assets/params-manifest.json`. There is one `when` on this screen and it is
 * over [ParamType]; no key appears anywhere in this file. That is the boundary
 * DESIGN.md draws: adding a FEX TSO or Turnip knob means adding a manifest
 * entry, never touching UI code, and the moment a key needs a special case here
 * the promise has quietly stopped being true.
 *
 * There used to be an architecture-profile picker above the parameters, choosing
 * between an ARM64EC Wine tree and an x86-64 one under Box64. Wine is built
 * `arm64ec,aarch64,i386` and no x86-64 tree exists, so the second option had
 * nothing to run: there is one kind of container now and no choice to present.
 *
 * Everything that decides *what* to draw — which clamp is active, what a
 * component selector resolved to — is worked out in
 * [ContainerEditorViewModel]. This file only draws.
 */
@Composable
fun ContainerEditorScreen(
    onBack: () -> Unit,
    viewModel: ContainerEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Save and Delete both end the screen. The flag is read once rather than
    // being a callback out of the view model, so a recomposition cannot pop twice.
    LaunchedEffect(state.finished) { if (state.finished) onBack() }

    ContainerEditorContent(
        state = state,
        onBack = onBack,
        onName = viewModel::setName,
        onParam = viewModel::setParam,
        onToggleAdvanced = viewModel::toggleAdvanced,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
}

@Composable
private fun ContainerEditorContent(
    state: EditorUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onParam: (String, ParamValue) -> Unit,
    onToggleAdvanced: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    VScaffold(
        toolbar = {
            VPushToolbar(
                title = if (state.creating) "New container" else state.name.ifBlank { "Container" },
                subtitle = if (state.loading) "reading settings" else null,
                onBack = onBack,
                trailing = {
                    if (state.error == null && !state.loading) {
                        VButton("Save", onSave, style = VButtonStyle.Primary)
                    }
                },
            )
        },
    ) {
        when {
            state.error != null -> VEmptyState(icon = Icons.Filled.Info, message = state.error)
            state.loading -> Unit

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
            ) {
                item(key = "name") {
                    VSectionHeader("Name")
                    VTextField(state.name, onName, placeholder = "Container")
                }

                state.groups.forEach { group ->
                    item(key = "rule-${group.id}") { VRule() }
                    item(key = "group-${group.id}") { ParamGroupHeader(group) }
                    items(
                        count = group.params.size,
                        key = { index -> group.params[index].resolved.spec.key },
                    ) { index ->
                        ParamControl(group.params[index], onParam)
                    }
                }

                if (state.hasAdvanced) {
                    item(key = "advanced") {
                        VRule()
                        AdvancedDisclosure(state.showAdvanced, onToggleAdvanced)
                    }
                }

                if (!state.creating) {
                    item(key = "delete") {
                        VRule()
                        VButton("Delete container", { confirmingDelete = true }, style = VButtonStyle.Danger)
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        VConfirmSheet(
            title = "Delete ${state.name}?",
            message = "Its settings are removed from this device. Installed components are " +
                "shared and are not touched.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun ParamGroupHeader(group: EditorGroup) {
    Column {
        VSectionHeader(group.title)
        if (group.help != null) {
            Text(
                group.help,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(bottom = Vessel.metrics.s8),
            )
        }
    }
}

/**
 * One composable per param *type*, and that is the entire dispatch.
 *
 * The bounds, the clamp reason and the resolved component are already decided by
 * the time they get here — see [EditorParam] — so each branch is just the
 * control and the value it writes back.
 */
@Composable
private fun ParamControl(param: EditorParam, onParam: (String, ParamValue) -> Unit) {
    val spec = param.resolved.spec
    val value = param.resolved.value

    VParamRow(
        title = spec.title,
        help = spec.help,
        note = param.resolved.clampReason ?: param.componentNote,
        warning = param.resolved.warning,
    ) {
        when (spec.type) {
            ParamType.ENUM -> VChoiceRow(
                options = spec.options,
                labelFor = spec::label,
                selected = (value as? ParamValue.Text)?.value,
                onSelect = { onParam(spec.key, ParamValue.Text(it)) },
            )

            ParamType.BOOL -> VToggle(
                checked = (value as? ParamValue.Flag)?.value == true,
                onCheckedChange = { onParam(spec.key, ParamValue.Flag(it)) },
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
                                val next = if (option in chosen) chosen - option else chosen + option
                                // Manifest order, not click order: TU_DEBUG is
                                // read as a string and a stable order makes two
                                // containers with the same flags compare equal.
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
        }
    }
}

/** One disclosure for every advanced param and group, collapsed by default. */
@Composable
private fun AdvancedDisclosure(shown: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .vCard()
            .clickable(onClick = onToggle)
            .padding(Vessel.metrics.s11),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3)) {
            Text("Show advanced", style = Vessel.type.body)
            Text(
                "Settings that are correct as they are, and that cost speed or stability when " +
                    "they are not.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
            )
        }
        VChoiceChip(if (shown) "shown" else "hidden", selected = shown, onClick = onToggle)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun ContainerEditorErrorPreview() {
    VesselTheme {
        ContainerEditorContent(
            state = EditorUiState(
                loading = false,
                error = "assets/params-manifest.json could not be read, so there are no " +
                    "settings to show.",
            ),
            onBack = {},
            onName = {},
            onParam = { _, _ -> },
            onToggleAdvanced = {},
            onSave = {},
            onDelete = {},
        )
    }
}
