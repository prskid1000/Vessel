package app.vessel.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.PeArchitecture
import app.vessel.ui.components.VAppIcon
import app.vessel.ui.components.VArchBadge
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSheet
import app.vessel.ui.components.VSheetHeader
import app.vessel.ui.components.VSheetRow
import app.vessel.ui.components.VTextField
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.AppSheetUiState
import app.vessel.ui.vm.AppSheetViewModel

/**
 * A program's profile, and the form that adds one — the same sheet twice.
 *
 * **The app-profile screen is deleted.** It was a pushed destination for three
 * fields and one read-only fact about a tile the user had just long-pressed, and
 * pushing it lost the container the program belongs to at exactly the moment that
 * fact mattered.
 *
 * Adding is this sheet with empty values, and it asks for one thing: the file.
 * The name and the architecture are read from it, and the arguments and working
 * directory are not asked for up front — they appear on the profile once the
 * shortcut exists, because a first-time add does not know it needs them.
 *
 * @param shortcut null to add a new one to [containerId].
 */
@Composable
fun AppSheet(
    containerId: String,
    shortcut: AppShortcut?,
    onDismiss: () -> Unit,
    onLaunch: (AppShortcut) -> Unit,
    onBrowse: (String) -> Unit,
    prefilledExecutable: String? = null,
    viewModel: AppSheetViewModel = hiltViewModel(key = shortcut?.id ?: "add-to-$containerId"),
) {
    LaunchedEffect(shortcut?.id, containerId) {
        if (shortcut == null) viewModel.openNew(containerId) else viewModel.openExisting(shortcut)
    }
    // A file chosen in the browser and handed back. Applied once the sheet knows
    // which container it is for, or the drive it resolves against is the wrong one.
    LaunchedEffect(prefilledExecutable, containerId) {
        if (!prefilledExecutable.isNullOrBlank()) viewModel.setExecutable(prefilledExecutable)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDismiss() }

    // Android's own picker. `OpenDocument` rather than `GetContent`, because a
    // persistable URI is what an import needs and `GetContent` does not promise
    // one survives the copy.
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFromAndroid) }

    AppSheetContent(
        state = state,
        // **An edit commits on the way out, and that is deliberate.** The profile
        // has no Save button because it has no draft: Launch and Remove are the
        // two verbs on it, and a form whose only other outcome is "I changed the
        // arguments" should not make the user confirm that they meant it. Adding
        // is different — it has an explicit Add, because until it is pressed
        // there is nothing to edit.
        onDismiss = {
            viewModel.saveEdits()
            onDismiss()
        },
        onSave = viewModel::save,
        onRemove = viewModel::remove,
        onLaunch = { shortcut?.let(onLaunch) },
        onArgs = viewModel::setArgs,
        onWorkingDir = viewModel::setWorkingDir,
        onBrowse = { onBrowse(state.containerId) },
        onImport = { importer.launch(IMPORTABLE_MIME) },
    )
}

/**
 * Everything Wine can be asked to start, and nothing it cannot.
 *
 * Wildcard rather than a list of extensions: Android's document picker filters by
 * MIME type, and a `.bat` or a `.lnk` has no registered one on most providers, so
 * a narrow filter hides the files this is for. The refusal happens after the pick
 * instead, where it can say *why* — which is the more useful place for it anyway.
 */
private val IMPORTABLE_MIME = arrayOf("*/*")

@Composable
private fun AppSheetContent(
    state: AppSheetUiState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onLaunch: () -> Unit,
    onArgs: (String) -> Unit,
    onWorkingDir: (String) -> Unit,
    onBrowse: () -> Unit,
    onImport: () -> Unit,
) {
    VSheet(
        onDismiss = onDismiss,
        header = {
            if (state.creating) {
                VSheetHeader(
                    title = "Add a program",
                    subtitle = "to ${state.containerName}",
                    trailing = { VButton("Add", onSave, enabled = state.canSave) },
                )
            } else {
                VSheetHeader(
                    title = state.name,
                    subtitle = "in ${state.containerName}",
                    leading = { VAppIcon(state.name.firstOrNull()?.uppercase() ?: "?") },
                    trailing = {
                        VButton("Launch", onLaunch, style = VButtonStyle.Primary, icon = VIcons.Play)
                    },
                )
            }
        },
    ) {
        VLabeledField(
            label = "Executable",
            help = when {
                state.refusal != null -> null
                state.executable.isBlank() ->
                    "The name, icon and architecture are read from the file. Nothing here is " +
                        "typed twice."

                else -> state.archNote.ifBlank { null }
            },
        ) {
            // The same box every other field on the sheet wears, even though this
            // one is a readout. A bare line of mono between two bordered fields
            // reads as a caption rather than as the sheet's most important value.
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Vessel.metrics.controlHeight)
                    .background(Vessel.colors.surfaceRaised, Vessel.metrics.shapeMd)
                    .vRing(Vessel.colors.divider, Vessel.metrics.shapeMd)
                    .padding(
                        start = Vessel.metrics.s8,
                        end = if (state.creating) Vessel.metrics.s3 else Vessel.metrics.s8,
                        top = Vessel.metrics.s3,
                        bottom = Vessel.metrics.s3,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.executable.ifBlank { "choose an .exe on C:" },
                    style = Vessel.type.mono,
                    color = if (state.executable.isBlank()) {
                        Vessel.colors.textMuted
                    } else {
                        Vessel.colors.textLabel
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.executable.isNotBlank() && state.refusal == null) {
                    VArchBadge(state.arch)
                }
                // The field is a readout, so the way to fill it lives on it. The
                // two rows below are the same two routes written out; this is the
                // one a user reaches for first.
                if (state.creating) {
                    VIconButton(VIcons.Folder, "Browse this container's C:", onBrowse)
                }
            }
        }

        // A refusal is louder than the field it is about, and it names the thing
        // that is missing rather than apologising. This is the `.ps1` case:
        // Wine's PowerShell is a stub, and a shortcut to one would appear to
        // launch and then do nothing.
        state.refusal?.let {
            Text(it, style = Vessel.type.bodySmall, color = Vessel.colors.danger)
        }
        state.caveat?.let {
            Text(it, style = Vessel.type.bodySmall, color = Vessel.colors.warn)
        }
        // Already on the home row. Not a refusal — the registry would replace it
        // quite happily — but Add would then be a button whose effect nobody can
        // see, so it is disabled and this says why. Warn rather than danger:
        // nothing is wrong, there is just nothing to do.
        state.alreadyAdded?.let {
            Text(it, style = Vessel.type.bodySmall, color = Vessel.colors.warn)
        }
        state.notice?.let {
            Text(it, style = Vessel.type.bodySmall, color = Vessel.colors.accent2)
        }

        if (state.creating) {
            VRule(verticalMargin = Vessel.metrics.s6)
            VSheetRow(
                icon = VIcons.Folder,
                title = "Browse this container's C:",
                help = "Opens the file browser to pick the file. Add it from there and it " +
                    "lands back here.",
                onClick = onBrowse,
            )
            VSheetRow(
                icon = VIcons.Import,
                title = "Import from Android storage",
                help = "Copies the file into the container first, then adds it.",
                onClick = onImport,
            )
        } else {
            VLabeledField(label = "Launch arguments") {
                VTextField(state.args, onArgs, placeholder = "none")
            }
            VLabeledField(
                label = "Working directory",
                help = "Left empty, the executable's own folder is used.",
            ) {
                VTextField(state.workingDir, onWorkingDir, placeholder = "the file's own folder")
            }

            VRule(verticalMargin = Vessel.metrics.s6)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Everything else is the container's. Two places to configure one thing is " +
                        "one too many.",
                    style = Vessel.type.bodySmall,
                    color = Vessel.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                VButton("Remove", onRemove, style = VButtonStyle.Danger)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun AppSheetProfilePreview() {
    VesselTheme {
        AppSheetContent(
            state = AppSheetUiState(
                loading = false,
                creating = false,
                containerName = "Display proof",
                executable = "C:\\Program Files\\Notepad++\\notepad++.exe",
                name = "Notepad++",
                arch = PeArchitecture.ARM64,
                archNote = "Read from the PE header's machine field — IMAGE_FILE_MACHINE_ARM64. " +
                    "It runs without translation.",
                args = "-multiInst -nosession",
                workingDir = "C:\\Program Files\\Notepad++",
            ),
            onDismiss = {},
            onSave = {},
            onRemove = {},
            onLaunch = {},
            onArgs = {},
            onWorkingDir = {},
            onBrowse = {},
            onImport = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun AppSheetAddPreview() {
    VesselTheme {
        AppSheetContent(
            state = AppSheetUiState(loading = false, creating = true, containerName = "Canoe test"),
            onDismiss = {},
            onSave = {},
            onRemove = {},
            onLaunch = {},
            onArgs = {},
            onWorkingDir = {},
            onBrowse = {},
            onImport = {},
        )
    }
}
