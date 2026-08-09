package app.vessel.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.PeArchitecture
import app.vessel.ui.components.VArchBadge
import app.vessel.ui.components.VBottomBar
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.archColor
import app.vessel.core.GuestDrive
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.Launchable
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.theme.vRuleBelow
import app.vessel.ui.vm.FileRow
import app.vessel.ui.vm.FilesUiState
import app.vessel.ui.vm.FilesViewModel

/**
 * A container's `C:` drive — a push, because you navigate *into* it.
 *
 * **Read straight off Android, with Wine stopped.** A Wine prefix is an ordinary
 * directory tree, so listing it is `File.listFiles()` and nothing more. That is
 * what makes this work before the container has ever launched and while a session
 * is running, and it is what gets import and export to Android storage for free —
 * neither of which Wine's own file manager can do, because from inside the guest
 * there is no Android to copy to.
 *
 * Back goes up one folder while the path has depth and leaves the screen at the
 * drive root, so Back and the toolbar's arrow never disagree.
 *
 * @param picking true when the browser was opened to choose a file for a shortcut.
 *   The primary action then hands the path back instead of adding it here.
 */
@Composable
fun FilesScreen(
    onBack: () -> Unit,
    onPicked: (String) -> Unit,
    picking: Boolean = false,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // **Android's own folder picker, not a list of ours.** A tree URI is not a
    // path, but its document id is — `primary:Games` rebuilds to a real
    // directory — so the picker can be used for what it is good at, which is
    // letting the user go anywhere rather than choosing from a top level we
    // decided on.
    var unmapping by remember { mutableStateOf<GuestDrive?>(null) }

    val context = LocalContext.current

    // Comes back with no result — the grant is a settings toggle, not a dialog —
    // so the drive list is re-read on return rather than trusting a callback.
    val allFilesAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshAfterPermission() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree -> if (tree != null) viewModel.mapPickedFolder(tree) }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    val selected = state.selected
    val exporter = rememberLauncherForActivityResult(
        // The MIME type is `application/octet-stream` for everything: a Wine
        // prefix holds registry hives, DLLs and save games, and claiming a type
        // this app has not inspected would send half of them to the wrong app.
        ActivityResultContracts.CreateDocument(EXPORT_MIME),
    ) { uri -> if (uri != null && selected != null) viewModel.export(selected, uri) }

    // Up first, out second. The toolbar arrow calls the same function, which is
    // the only way the two can be guaranteed to agree.
    BackHandler { if (!viewModel.up()) onBack() }

    FilesContent(
        state = state,
        picking = picking,
        onBack = { if (!viewModel.up()) onBack() },
        onOpen = viewModel::open,
        onCrumb = viewModel::goTo,
        onDrive = viewModel::openDrive,
        // **When the permission is missing, `+` asks for it instead of refusing.**
        // All-files access is a per-install grant, so an uninstall silently takes
        // it away — a reinstalled app has no `D:` and a `+` that did nothing,
        // with the reason nowhere on screen because the sheet that used to carry
        // it was replaced by Android's own picker. Nothing in Vessel had ever
        // asked for the permission at all; the manifest declared it and the user
        // was left to find the settings page themselves.
        onAddDrive = {
            if (state.canMapDrives) {
                folderPicker.launch(null)
            } else {
                allFilesAccess.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }
        },
        onImport = { importer.launch(IMPORT_MIME) },
        onExport = { selected?.let { exporter.launch(it.name) } },
        onAddAsApp = {
            val row = state.selected ?: return@FilesContent
            if (picking) onPicked(row.guestPath) else viewModel.addAsApp(row)
        },
        onDismissNotice = viewModel::dismissNotice,
        onUnmapDrive = { unmapping = it },
    )

    // **Confirmed, and the confirmation says the one thing a user needs to
    // hear.** A drive is a pointer, so removing it removes the pointer — but
    // "remove drive E:" reads exactly like "delete the folder", and the folder
    // is the user's, may be their only copy, and is not ours to be ambiguous
    // about. Wine's own drives are never offered for removal, so this only ever
    // asks about a mapping somebody made.
    unmapping?.let { drive ->
        VConfirmSheet(
            title = "Remove ${drive.display}?",
            message = "${drive.label} stays exactly where it is on the phone. " +
                "Only the drive letter goes, and you can map it again.",
            confirmLabel = "Remove drive",
            onConfirm = {
                viewModel.unmapDrive(drive.letter)
                unmapping = null
            },
            onDismiss = { unmapping = null },
        )
    }

}

private const val EXPORT_MIME = "application/octet-stream"
private val IMPORT_MIME = arrayOf("*/*")

@Composable
private fun FilesContent(
    state: FilesUiState,
    picking: Boolean,
    onBack: () -> Unit,
    onOpen: (FileRow) -> Unit,
    onCrumb: (Int) -> Unit,
    onDrive: (Char) -> Unit,
    onAddDrive: () -> Unit,
    onUnmapDrive: (GuestDrive) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAddAsApp: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val selected = state.selected
    VScaffold(
        toolbar = {
            VPushToolbar(
                title = state.containerName.ifBlank { "Files" },
                subtitle = state.storage.ifBlank { null },
                onBack = onBack,
            )
        },
        bottomBar = {
            VBottomBar {
                VButton(
                    "Import",
                    onImport,
                    modifier = Modifier.weight(1f),
                    icon = VIcons.Import,
                    enabled = state.error == null,
                )
                VButton(
                    "Export",
                    onExport,
                    modifier = Modifier.weight(1f),
                    icon = VIcons.Export,
                    enabled = selected != null && !selected.isDirectory,
                )
                VButton(
                    if (picking) "Choose" else "Add as app",
                    onAddAsApp,
                    modifier = Modifier.weight(1f),
                    style = VButtonStyle.Primary,
                    // Only a file the engine can actually start, and only one that
                    // is not already a program. A `.ps1` is refused here rather
                    // than at launch, because Wine's PowerShell is a stub that
                    // would appear to work; something already on the home row is
                    // refused because adding it again is a press with no visible
                    // effect. The row itself says which of the two it is.
                    enabled = state.canAddAsApp,
                )
            }
        },
    ) {
        DriveTabs(
            drives = state.drives,
            current = GuestPath.driveOf(state.guestPath),
            canMap = state.canMapDrives,
            onDrive = onDrive,
            onAdd = onAddDrive,
            onUnmap = onUnmapDrive,
        )
        Breadcrumb(state.guestPath, onCrumb)

        // Why "Add as app" is greyed out for a file that plainly runs. Without
        // this the button is simply dead and the user has to guess; the design's
        // rule is that anything which cannot happen says so and names the reason.
        if (state.selectedAlreadyAdded) {
            Text(
                "${state.selected?.name} is already a program on ${state.containerName}.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.warn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s3),
            )
        }

        state.notice?.let { notice ->
            Text(
                notice,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.accent2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Dismiss", onClick = onDismissNotice)
                    .padding(vertical = Vessel.metrics.s6),
            )
        }

        // A refusal about the selected file, said where the button that would
        // have run it is, rather than after a launch that does nothing.
        (selected?.launchable as? Launchable.Refused)?.let {
            Text(
                it.reason,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.danger,
                modifier = Modifier.fillMaxWidth().padding(bottom = Vessel.metrics.s6),
            )
        }

        when {
            state.error != null -> VEmptyState(icon = VIcons.Info, message = state.error)
            state.loading -> Unit
            state.rows.isEmpty() -> VEmptyState(
                icon = VIcons.Folder,
                message = "This folder is empty.",
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
            ) {
                if (!state.atRoot) {
                    item(key = "..") { ParentRow { onCrumb(GuestPath.segments(state.guestPath).size - 2) } }
                }
                items(state.rows, key = { it.guestPath }) { row ->
                    FileListRow(row, selected = row == selected) { onOpen(row) }
                }
                item(key = "provenance") {
                    Text(
                        "read directly from the prefix on Android — Wine is not running, and " +
                            "does not need to be",
                        style = Vessel.type.monoSmall,
                        color = Vessel.colors.neutral600,
                        modifier = Modifier.padding(top = Vessel.metrics.s17),
                    )
                }
            }
        }
    }
}

/**
 * `C:\users\vessel\Downloads`, with every segment but the last a target.
 *
 * The accent is on the parts you can go to and the current folder is plain, which
 * is the only cue needed: a breadcrumb where every crumb looks tappable does not
 * say where you are.
 */
/**
 * One tab per drive, and a `+`.
 *
 * **This is *This PC*, flattened into a row.** A drive is the first thing a
 * Windows user looks for and the browser used to show a static `C:` label — the
 * container could carry the phone's storage on `D:` and there was no way to
 * reach it from here. A tab is a drive, permanent and always visible, so
 * switching is one tap and nothing is hidden behind a menu.
 *
 * The letter is the tab and the label sits under it: `D:` is what a user types
 * into a program's Open box, and the folder's name is what tells them which
 * `D:` it is. Both matter and neither is enough alone.
 *
 * Scrolls horizontally, because a container with several pinned folders has
 * more drives than a phone has width, and a row that silently dropped the last
 * of them would hide exactly the one that was just added.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveTabs(
    drives: List<GuestDrive>,
    current: String?,
    canMap: Boolean,
    onDrive: (Char) -> Unit,
    onAdd: () -> Unit,
    onUnmap: (GuestDrive) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        drives.forEach { drive ->
            val active = drive.display.equals(current, ignoreCase = true)
            Column(
                Modifier
                    .background(
                        if (active) Vessel.colors.accentSoft else Color.Transparent,
                        Vessel.metrics.shapeMd,
                    )
                    .vRing(
                        if (active) Vessel.colors.accent else Vessel.colors.divider,
                        Vessel.metrics.shapeMd,
                    )
                    .combinedClickable(
                        onClickLabel = drive.label,
                        onClick = { onDrive(drive.letter) },
                        // Only a mapping can be removed. Wine's own C: and Z:
                        // are the prefix and the filesystem; there is no sense
                        // in which a user could give either of them up.
                        onLongClick = { if (!drive.builtIn) onUnmap(drive) },
                    )
                    .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    drive.display,
                    style = Vessel.type.control,
                    color = if (active) Vessel.colors.accent else Vessel.colors.textLabel,
                )
                Text(drive.label, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            }
        }

        // Drawn dimmed without the permission, and still tappable: it opens
        // Android's All-files-access page rather than doing nothing. Disabled was
        // the old behaviour and it was a dead end — the reason was meant to live
        // "on the sheet it would have opened", and that sheet no longer exists.
        Box(
            Modifier
                .size(Vessel.metrics.touchTarget)
                .vRing(Vessel.colors.divider, Vessel.metrics.shapeMd)
                .clickable(
                    onClickLabel = if (canMap) {
                        "Map a folder as a drive"
                    } else {
                        "Give Vessel access to all files, so a folder can be mapped"
                    },
                    onClick = onAdd,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                VIcons.Plus,
                contentDescription = null,
                Modifier.size(Vessel.metrics.iconMd),
                tint = Vessel.colors.textMuted.copy(
                    alpha = Vessel.colors.textMuted.alpha *
                        if (canMap) 1f else Vessel.colors.disabledAlpha,
                ),
            )
        }
    }
}

@Composable
private fun Breadcrumb(guestPath: String, onCrumb: (Int) -> Unit) {
    val parts = GuestPath.segments(guestPath)
    Row(
        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            val last = index == parts.lastIndex
            if (index > 0) {
                Text("\\", style = Vessel.type.mono, color = Vessel.colors.textMuted)
            }
            Text(
                part,
                style = Vessel.type.mono,
                color = if (last) Vessel.colors.textPrimary else Vessel.colors.accent,
                maxLines = 1,
                modifier = if (last) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = "Go to $part") { onCrumb(index) }
                },
            )
        }
    }
}

@Composable
private fun ParentRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Up one folder", onClick = onClick)
            .vRuleBelow(Vessel.colors.divider)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(vertical = Vessel.metrics.s8),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            VIcons.FolderOpen,
            contentDescription = null,
            Modifier.size(Vessel.metrics.iconMd),
            tint = Vessel.colors.textMuted,
        )
        Text("..", style = Vessel.type.body)
    }
}

/**
 * One entry: an icon, a name, its size and date, and — for a program — the
 * architecture badge.
 *
 * The executable's mark is a ringed square in the architecture's own colour
 * rather than a generic file glyph, because in a folder of downloads the fact
 * anybody is looking for is which of these will run natively.
 */
@Composable
private fun FileListRow(row: FileRow, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) Vessel.colors.accentSoft else Color.Transparent,
                Vessel.metrics.shapeSm,
            )
            .clickable(onClickLabel = row.name, onClick = onClick)
            .vRuleBelow(Vessel.colors.divider)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(vertical = Vessel.metrics.s8, horizontal = Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileMark(row)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        ) {
            Text(
                row.name,
                style = Vessel.type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail?.let {
                Text(it, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            }
        }
        row.arch?.let { VArchBadge(it) }
    }
}

/**
 * The row's leading mark.
 *
 * A folder gets the accent glyph. **A program gets a ringed square in its own
 * architecture's colour with an `E` in it, and a data file gets the same square
 * empty and grey** — which is the design's own treatment and better than a file
 * glyph here: in a folder of downloads the fact anybody is looking for is which
 * of these will run natively, and a colour answers that before a name is read.
 */
@Composable
private fun FileMark(row: FileRow) {
    if (row.isDirectory) {
        Icon(
            VIcons.Folder,
            contentDescription = null,
            Modifier.size(Vessel.metrics.iconMd),
            tint = Vessel.colors.accent,
        )
        return
    }
    val tone = row.arch?.let { archColor(it) } ?: Vessel.colors.neutral700
    Box(
        Modifier
            .size(Vessel.metrics.iconMd)
            .vRing(tone, Vessel.metrics.shapeSm),
        contentAlignment = Alignment.Center,
    ) {
        if (row.arch != null) {
            Text("E", style = Vessel.type.monoSmall, color = tone)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun FilesPreview() {
    VesselTheme {
        FilesContent(
            state = FilesUiState(
                loading = false,
                containerName = "Display proof",
                guestPath = "C:\\users\\vessel\\Downloads",
                storage = "14.2 GB free of 62 GB",
                rows = listOf(
                    preview("Android download", true, null, null),
                    preview(
                        "npp.8.6.9.portable.arm64.exe",
                        false,
                        "4.8 MB \u00b7 12 Aug 19:04",
                        PeArchitecture.ARM64,
                    ),
                    preview("7z2408-x64.exe", false, "1.6 MB \u00b7 11 Aug 22:31", PeArchitecture.X64),
                    preview("savegame.dat", false, "228 KB \u00b7 today 07:14", null),
                    preview("dxdiag.txt", false, "42 KB \u00b7 12 Aug 19:22", null),
                ),
            ),
            picking = false,
            onBack = {},
            onOpen = {},
            onCrumb = {},
            onDrive = {},
            onAddDrive = {},
            onUnmapDrive = {},
            onImport = {},
            onExport = {},
            onAddAsApp = {},
            onDismissNotice = {},
        )
    }
}

private fun preview(
    name: String,
    directory: Boolean,
    detail: String?,
    arch: PeArchitecture?,
) = FileRow(
    name = name,
    guestPath = "C:\\users\\vessel\\Downloads\\$name",
    isDirectory = directory,
    detail = detail,
    arch = arch,
    launchable = if (arch != null) Launchable.Runs("natively") else Launchable.NotAProgram,
)
