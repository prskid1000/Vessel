package app.vessel.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.params.ParamSpec
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import app.vessel.core.params.ResolvedParam
import app.vessel.ui.components.VCheckRow
import app.vessel.ui.components.VComponentReadout
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VParamRow
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VStepper
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
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
 * **Nothing is hidden** — no "Show advanced" disclosure. Hierarchy is carried by
 * grouping and order instead: Display first because it is what people change,
 * Compatibility last because it is correct until something breaks.
 *
 * Everything that decides *what* to draw — which clamp is active, what a
 * component selector resolved to — is worked out in [ContainerEditorViewModel].
 * This file only draws.
 */
@Composable
fun ContainerEditorScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
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
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onOpenLogs = onOpenLogs,
    )
}

@Composable
private fun ContainerEditorContent(
    state: EditorUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onParam: (String, ParamValue) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onOpenLogs: () -> Unit = {},
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
                        // Glyphs: in a toolbar a tick and a bin are
                        // unambiguous and a word is a third of the bar. The
                        // confirmation behind the bin is still words.
                        if (!state.creating) {
                            // The only way into the session logs. Inside the
                            // same guard as Delete, because a container that has
                            // never been saved has never run.
                            VIconButton(
                                Icons.AutoMirrored.Filled.List,
                                "Session logs",
                                onOpenLogs,
                            )
                            VIconButton(
                                Icons.Filled.Delete,
                                "Delete container",
                                { confirmingDelete = true },
                                tint = Vessel.colors.danger,
                            )
                        }
                        VIconButton(
                            Icons.Filled.Check,
                            "Save",
                            onSave,
                            tint = Vessel.colors.accent,
                        )
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
                    VSectionHeader("Container")
                    VParamRow(
                        title = "Name",
                        help = "What this container is called on the home screen.",
                        control = { VTextField(state.name, onName, placeholder = "Container") },
                    )
                }

                state.groups.forEach { group ->
                    item(key = "group-${group.id}") { ParamGroupHeader(group) }
                    items(
                        count = group.params.size,
                        key = { index -> group.params[index].resolved.spec.key },
                    ) { index ->
                        ParamControl(group.params[index], onParam)
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
        value = summarise(spec, value),
        // A package id or an `@latest` selector is a machine string, and every
        // machine string in this product is mono. An option label is not.
        valueIsMachine = spec.type == ParamType.COMPONENT,
        note = param.resolved.clampReason ?: param.componentNote,
        warning = param.resolved.warning,
        trailing = if (spec.type != ParamType.BOOL) {
            null
        } else {
            {
                VToggle(
                    checked = (value as? ParamValue.Flag)?.value == true,
                    onCheckedChange = { onParam(spec.key, ParamValue.Flag(it)) },
                )
            }
        },
        control = if (spec.type == ParamType.BOOL) {
            null
        } else {
            {
                when (spec.type) {
                    ParamType.ENUM -> VDropdownField(
                        options = spec.options,
                        labelFor = spec::label,
                        selected = (value as? ParamValue.Text)?.value,
                        onSelect = { onParam(spec.key, ParamValue.Text(it)) },
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
                                        val next = if (option in chosen) {
                                            chosen - option
                                        } else {
                                            chosen + option
                                        }
                                        // Manifest order, not click order:
                                        // TU_DEBUG is read as a string and a
                                        // stable order makes two containers with
                                        // the same flags compare equal.
                                        onParam(
                                            spec.key,
                                            ParamValue.Choices(
                                                spec.options.filter { it in next },
                                            ),
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

                    // Handled by `trailing`, above; the switch is the control.
                    ParamType.BOOL -> Unit
                }
            }
        },
    )
}

/**
 * What the right-hand column of the label line says.
 *
 * One line per [ParamType] and nothing per key, so it stays inside the same
 * boundary the renderer does. A boolean has no entry because its switch already
 * sits in that column, and a `multi` reports a count rather than a list — seven
 * Turnip flag names would not fit and would truncate to nothing readable.
 */
private fun summarise(spec: ParamSpec, value: ParamValue): String? = when (spec.type) {
    ParamType.BOOL -> null
    ParamType.INT -> (value as? ParamValue.Count)?.value?.toString()
    ParamType.ENUM -> (value as? ParamValue.Text)?.value?.let(spec::label)
    ParamType.COMPONENT -> (value as? ParamValue.Text)?.value
    ParamType.MULTI -> (value as? ParamValue.Choices)?.values.orEmpty()
        .let { if (it.isEmpty()) "none" else "${it.size} on" }
}

// — previews ---------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 900)
@Composable
private fun ContainerEditorPreview() {
    VesselTheme {
        ContainerEditorContent(
            state = EditorUiState(
                loading = false,
                creating = false,
                name = "Default",
                groups = PreviewGroups,
            ),
            onBack = {},
            onName = {},
            onParam = { _, _ -> },
            onSave = {},
            onDelete = {},
        )
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
            onSave = {},
            onDelete = {},
        )
    }
}

private fun previewParam(
    spec: ParamSpec,
    value: ParamValue,
    componentId: String? = null,
    componentNote: String? = null,
    warning: String? = null,
) = EditorParam(
    resolved = ResolvedParam(
        spec = spec,
        value = value,
        min = spec.min,
        max = spec.max,
        clampReason = null,
        warning = warning,
    ),
    componentId = componentId,
    componentNote = componentNote,
)

/**
 * The manifest as the editor draws it, frozen.
 *
 * Fixed data so the layout can be inspected without a device: the real screen
 * reads `assets/params-manifest.json`, and anything drawn here that is not in
 * that file is a drawing rather than a claim.
 */
private val PreviewGroups = listOf(
    EditorGroup(
        id = "display",
        title = "Display",
        help = "How big the Windows desktop is and how fast it is allowed to run.",
        params = listOf(
            previewParam(
                ParamSpec(
                    key = "display.resolution",
                    title = "Resolution",
                    help = "Lower resolutions gain a lot of performance on this phone; it has " +
                        "a smaller GPU cache than the full-size chip.",
                    type = ParamType.ENUM,
                    options = listOf("1280x720", "1600x900", "1920x1080", "native"),
                ),
                ParamValue.Text("1280x720"),
            ),
            previewParam(
                ParamSpec(
                    key = "display.fpsLimit",
                    title = "Frame rate limit",
                    help = "Capping frame rate keeps the phone cool, which keeps performance " +
                        "steady over a long session.",
                    type = ParamType.ENUM,
                    options = listOf("30", "45", "60", "unlimited"),
                ),
                ParamValue.Text("60"),
            ),
        ),
    ),
    EditorGroup(
        id = "graphics",
        title = "Graphics",
        help = "Which driver and Direct3D translation this container loads.",
        params = listOf(
            previewParam(
                ParamSpec(
                    key = "gpu.driver",
                    title = "GPU driver",
                    help = "Turnip is the open driver this app builds for your Adreno 829; the " +
                        "system driver is missing features that most games need.",
                    type = ParamType.COMPONENT,
                    componentType = "Turnip",
                ),
                ParamValue.Text("@latest"),
                componentId = "turnip-25.2.0-gen8-canoe",
            ),
            previewParam(
                ParamSpec(
                    key = "gpu.d3dLayer",
                    title = "Direct3D translation",
                    help = "Converts the game's Direct3D calls to Vulkan; must match your GPU " +
                        "driver.",
                    type = ParamType.COMPONENT,
                    componentType = "DXVK",
                ),
                ParamValue.Text("@latest"),
                componentNote = "Nothing installed for this component yet.",
            ),
        ),
    ),
    EditorGroup(
        id = "rendering",
        title = "Rendering",
        help = "How the driver decides to draw a frame; only worth touching when a game looks " +
            "wrong.",
        params = listOf(
            previewParam(
                ParamSpec(
                    key = "turnip.TU_AUTOTUNE_ALGO",
                    title = "Rendering mode preference",
                    help = "Nudges the driver toward direct rendering, which fixes most " +
                        "corruption, while still letting it use the faster tiled path when it " +
                        "is confident.",
                    type = ParamType.ENUM,
                    options = listOf("default", "prefer_sysmem"),
                    optionLabels = mapOf(
                        "default" to "Automatic",
                        "prefer_sysmem" to "Prefer direct rendering",
                    ),
                ),
                ParamValue.Text("default"),
            ),
            previewParam(
                ParamSpec(
                    key = "turnip.TU_DEBUG",
                    title = "Driver mode",
                    help = "Leave on automatic unless a game shows corruption; forcing a mode " +
                        "is a troubleshooting step, not a speed-up.",
                    type = ParamType.MULTI,
                    options = listOf("sysmem", "gmem", "nolrz"),
                    optionLabels = mapOf(
                        "sysmem" to "Force direct rendering",
                        "gmem" to "Force tiled rendering",
                        "nolrz" to "Disable low-resolution Z",
                    ),
                ),
                ParamValue.Choices(emptyList()),
            ),
        ),
    ),
    EditorGroup(
        id = "compatibility",
        title = "Compatibility",
        help = "Correct as they are. Change one only when a specific program misbehaves.",
        params = listOf(
            previewParam(
                ParamSpec(
                    key = "fex.TSOEnabled",
                    title = "Strict memory ordering",
                    help = "Keep this on: turning it off is faster but breaks most " +
                        "multi-threaded programs.",
                    type = ParamType.BOOL,
                ),
                ParamValue.Flag(true),
            ),
            previewParam(
                ParamSpec(
                    key = "fex.VectorTSOEnabled",
                    title = "Ordering for vector memory",
                    help = "Very slow in games and rarely needed; turn on only to fix a " +
                        "specific graphical or audio corruption.",
                    type = ParamType.BOOL,
                ),
                ParamValue.Flag(false),
            ),
            previewParam(
                ParamSpec(
                    key = "wine.sync",
                    title = "Thread synchronisation",
                    help = "esync is the working option on this phone; ntsync needs kernel " +
                        "support Android does not ship yet.",
                    type = ParamType.ENUM,
                    options = listOf("esync", "fsync", "none"),
                ),
                ParamValue.Text("esync"),
            ),
        ),
    ),
)
