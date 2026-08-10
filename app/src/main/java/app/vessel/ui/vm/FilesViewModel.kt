package app.vessel.ui.vm

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DriveMap
import app.vessel.core.PeArchitecture
import app.vessel.data.ContainerPaths
import app.vessel.data.DriveListing
import app.vessel.data.PrefixEntry
import app.vessel.data.PrefixFiles
import app.vessel.data.ContainerRepository
import app.vessel.ui.Routes
import app.vessel.ui.shell.AppRegistry
import app.vessel.ui.shell.AppShortcut
import app.vessel.core.GuestDrive
import app.vessel.data.AndroidDrives
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.Launchable
import app.vessel.ui.shell.launchabilityOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import javax.inject.Inject

/**
 * One row of the browser.
 *
 * Every label is formatted here. A composable doing byte arithmetic and date
 * formatting is a composable nobody can read, and a `SimpleDateFormat`
 * constructed inside a `LazyColumn` item is one per row per frame.
 */
@Immutable
data class FileRow(
    val name: String,
    val guestPath: String,
    val isDirectory: Boolean,
    /** `4.8 MB · 12 Aug 19:04`, or null for a directory. */
    val detail: String?,
    /** Set for a PE only. The row's tint and its badge both come from this. */
    val arch: PeArchitecture?,
    /** Null when the file is not a program Vessel can start. */
    val launchable: Launchable,
)

@Immutable
data class FilesUiState(
    val loading: Boolean = true,
    val containerId: String = "",
    val containerName: String = "",
    /** `C:\users\vessel\Downloads` — the breadcrumb reads its segments. */
    val guestPath: String = GuestPath.DRIVE + "\\",
    val rows: List<FileRow> = emptyList(),
    /** `14.2 GB free of 62 GB`. */
    val storage: String = "",
    /** Set when the drive is not there at all. Stated, never an empty list. */
    val error: String? = null,
    /** Every drive the container has, in letter order. One tab each. */
    val drives: List<GuestDrive> = emptyList(),
    /** Whether this build may map a folder at all. See [AndroidDrives.canMap]. */
    val canMapDrives: Boolean = false,
    /** The row a long-press or a tap is asking about; drives the action bar. */
    val selected: FileRow? = null,
    /** One-shot prose: an import that failed, a refusal, a completed export. */
    val notice: String? = null,
    /**
     * Guest paths this container already has a shortcut to, lower-cased.
     *
     * Lower-cased because these are Windows paths: `C:\x.exe` and `c:\X.EXE` are
     * one file, and the registry matches them that way too.
     */
    val addedExecutables: Set<String> = emptySet(),
) {
    val atRoot: Boolean get() = GuestPath.segments(guestPath).size <= 1

    /** True when [selected] is already on this container's home row. */
    val selectedAlreadyAdded: Boolean
        get() = selected?.guestPath?.lowercase() in addedExecutables

    /**
     * Whether "Add as app" does anything.
     *
     * Runnable, and not already there. Adding a second time is not an error — the
     * registry replaces rather than duplicating — but it is a press with no
     * visible effect, and a control that controls nothing is the thing DESIGN.md
     * is most insistent about.
     */
    val canAddAsApp: Boolean
        get() = selected?.launchable?.runnable == true && !selectedAlreadyAdded
}

/**
 * The container's drives, as a browser.
 *
 * **This class no longer touches the filesystem.** [PrefixFiles] reads it and
 * says what it found; everything here is state, wording and formatting. The
 * split is worth keeping: the reader sits in `data/` beside [AndroidDrives],
 * which creates the very `dosdevices` links it reads through, and a screen's
 * view model is the wrong place to learn that a Wine prefix is an ordinary
 * directory tree.
 *
 * Reads are dispatched to [Dispatchers.IO] from here, because that is a
 * property of *this* call site rather than of the reader: a prefix has tens of
 * thousands of files in it and `drive_c\windows\system32` alone is a slow stat
 * on this device.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val paths: ContainerPaths,
    private val containers: ContainerRepository,
    private val registry: AppRegistry,
    private val drives: AndroidDrives,
    private val files: PrefixFiles,
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()

    private val prefix: File = paths.of(containerId).prefix

    /**
     * **Derived from the path being shown, not remembered beside it.**
     *
     * This was a `var` set only by [openDrive], and it was a second source of
     * truth for which drive is open. The two disagreed the moment anything else
     * changed the path — and [GuestPath.rootOf] describes exactly that: a
     * breadcrumb tap moved `guestPath` to another drive's root while this stayed
     * put, so the browser listed one drive under another drive's name, the tab
     * row highlighted the wrong tab, and tapping the right tab did nothing
     * because [openDrive] compared against this and returned early.
     *
     * A path carries its own drive letter. Reading it is not slower than
     * remembering it, and it cannot drift.
     */
    private val driveLetter: Char
        get() = state.value.guestPath.firstOrNull()?.lowercaseChar()
            ?.takeIf { it in 'a'..'z' }
            ?: DriveMap.SYSTEM_DRIVE

    private val _state = MutableStateFlow(FilesUiState(containerId = containerId))
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            _state.update { it.copy(containerName = name) }
        }
        // Collected rather than read once, because "Add as app" adds to this very
        // list — the button has to disable itself the moment it is pressed, not on
        // the next visit to the folder.
        viewModelScope.launch {
            registry.shortcuts.collect { shortcuts ->
                val added = shortcuts
                    .filter { it.containerId == containerId }
                    .mapTo(mutableSetOf()) { it.executable.lowercase() }
                _state.update { it.copy(addedExecutables = added) }
            }
        }
        refreshDrives()
        navigateTo(GuestPath.DRIVE + SEPARATOR)
    }

    /**
     * Re-read the drive list from `dosdevices`.
     *
     * From the directory rather than from anything remembered, on the same rule
     * the rest of this feature follows: a note saying a mapping exists is not
     * the mapping.
     */
    private fun refreshDrives() {
        // **A drive this app cannot list is not offered as a tab.** A tab that
        // could only ever open onto a refusal is a destination the user has to
        // learn to avoid.
        //
        // This was written for `Z:`, which no longer exists — the unix root is
        // removed from the prefix outright now, for the reason in
        // `DriveMap.removeRootDrive`. The rule is kept because it was never
        // really about `Z:`: a card that has been unmounted and a mapped folder
        // that has been deleted both leave a dangling symlink, and both drop out
        // of the row here without another line of code. The mapping stays in
        // `dosdevices` on purpose, so plugging the card back in brings the drive
        // back with the letter it had.
        val list = drives.drives(prefix).filter { files.isListable(prefix, it.letter) }
        _state.update { it.copy(drives = list, canMapDrives = drives.canMap) }
    }

    /**
     * Map the folder behind a picked tree URI.
     *
     * The picker hands back a `content://` tree; [AndroidDrives.folderFor] turns
     * its document id into the real directory, which is the thing a drive can
     * point at. A tree with no path — a cloud provider — is refused in words
     * rather than mapped to a drive that would list nothing.
     */
    fun mapPickedFolder(tree: Uri) {
        val folder = drives.folderFor(tree)
        if (folder == null) {
            _state.update {
                it.copy(error = "That location is not a folder on this device, so it cannot be a drive.")
            }
            return
        }
        mapFolder(folder)
    }

    /** Show [letter], from its own root. Silent for a drive that is not mapped. */
    fun openDrive(letter: Char) {
        if (state.value.drives.none { it.letter == letter }) return
        // No early return for "already on it". It used to be here and it was the
        // thing that made the state above unrecoverable: once the browser
        // believed it was on a drive it was not on, the only control that could
        // have corrected it refused to act. Re-opening a drive you are already
        // on is a jump to its root, which is a reasonable thing for a tab to do.
        navigateTo("${letter.uppercaseChar()}:" + SEPARATOR)
    }

    /**
     * Map [folder] to the next free letter and show it.
     *
     * Returns the letter, or null with the reason already in state — every
     * letter taken, no permission, or the link could not be made.
     */
    fun mapFolder(folder: File) {
        val letter = drives.mapFolder(prefix, folder)
        if (letter == null) {
            _state.update { it.copy(error = "That folder could not be mapped to a drive.") }
            return
        }
        refreshDrives()
        openDrive(letter)
    }

    /**
     * Re-read the drives after a trip to Android's settings.
     *
     * The All-files-access grant is a toggle on a settings page, not a dialog,
     * so the activity result carries nothing — whether it was given is only
     * knowable by asking again.
     */
    fun refreshAfterPermission() = refreshDrives()

    /** Remove a mapping. The folder it pointed at is untouched. */
    fun unmapDrive(letter: Char) {
        if (!drives.unmap(prefix, letter)) return
        refreshDrives()
        if (driveLetter == letter) openDrive(DriveMap.SYSTEM_DRIVE)
    }

    // — navigation -----------------------------------------------------------

    fun open(row: FileRow) {
        if (row.isDirectory) {
            navigateTo(row.guestPath)
        } else {
            _state.update { it.copy(selected = row) }
        }
    }

    fun select(row: FileRow?) = _state.update { it.copy(selected = row) }

    /** Up one folder. Returns false at the drive root, which is Back's cue to leave. */
    fun up(): Boolean {
        val parent = GuestPath.parentOf(state.value.guestPath) ?: return false
        navigateTo(parent)
        return true
    }

    /** A breadcrumb tap: everything up to and including [index]. */
    fun goTo(index: Int) = navigateTo(GuestPath.upTo(state.value.guestPath, index))

    fun refresh() = navigateTo(state.value.guestPath)

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun navigateTo(guestPath: String) {
        _state.update { it.copy(loading = true, guestPath = guestPath, selected = null) }
        viewModelScope.launch {
            val listing = withContext(Dispatchers.IO) { read(guestPath) }
            _state.update {
                it.copy(
                    loading = false,
                    rows = listing.rows,
                    error = listing.error,
                    storage = listing.storage,
                )
            }
        }
    }

    /**
     * Turn what [PrefixFiles] found into rows, or into the sentence that says
     * why there are none.
     *
     * **Every failure gets its own words.** Which one it is comes back typed
     * rather than as an empty list, because "Android refused this folder" and
     * "this folder has nothing in it" look identical from a list and mean
     * opposite things — the `Z:` case, where SELinux denies `readdir` on `/` to
     * `untrusted_app`, and the browser used to report a filesystem with
     * everything in it as empty.
     */
    private fun read(guestPath: String): Listing = when (
        val listing = files.list(prefix, driveLetter, guestPath)
    ) {
        is DriveListing.Entries ->
            Listing(rows = listing.entries.map { toRow(it, guestPath) }, storage = storageLine())

        DriveListing.NoSystemDrive -> Listing(
            error = "This container has no C: drive yet. It is created the first time the " +
                "container is launched, by Wine's own first-run setup.",
            storage = storageLine(),
        )

        is DriveListing.NotAFolder -> Listing(
            error = "${listing.guestPath} is not a folder on this drive any more.",
            storage = storageLine(),
        )

        is DriveListing.Denied -> Listing(
            error = "Android does not allow this app to list ${listing.path}. " +
                "Wine creates this drive; the phone's own storage is on D:.",
            storage = storageLine(),
        )
    }

    private fun toRow(entry: PrefixEntry, parent: String): FileRow {
        val guestPath = parent.trimEnd(SEPARATOR) + SEPARATOR + entry.name
        val launchable =
            if (entry.isDirectory) Launchable.NotAProgram else launchabilityOf(entry.file)
        return FileRow(
            name = entry.name,
            guestPath = guestPath,
            isDirectory = entry.isDirectory,
            detail = if (entry.isDirectory) {
                null
            } else {
                "${sizeLabel(entry.size)} · ${DATE.format(Date(entry.modified))}"
            },
            arch = (launchable as? Launchable.Runs)?.arch,
            launchable = launchable,
        )
    }


    // — import and export ----------------------------------------------------

    /**
     * Copy [source] out of Android storage and into the folder being browsed.
     *
     * The name comes from the last path segment of the document URI, which is
     * what the Storage Access Framework gives without a second query, and is
     * corrected to the display name where the provider offers one.
     */
    fun import(source: Uri) {
        viewModelScope.launch {
            val target = files.resolve(prefix, driveLetter, state.value.guestPath)
            if (target == null || !target.isDirectory) {
                _state.update { it.copy(notice = "That folder is not on this drive any more.") }
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) { files.copyIn(source, target) }
            _state.update {
                it.copy(
                    notice = outcome.fold(
                        onSuccess = { name -> "Copied $name into ${state.value.guestPath}." },
                        onFailure = { error ->
                            "The import failed: ${error.message ?: error::class.simpleName}"
                        },
                    ),
                )
            }
            refresh()
        }
    }

    /**
     * Add the selected file to this container's programs, from here.
     *
     * The browser is the natural place to do it — you have just found the file —
     * so the shortcut is created without a round trip through the sheet. The
     * refusal path is the same one the sheet uses: [Launchable.Runs] or nothing
     * happens, and the reason is already on screen beside the button.
     */
    fun addAsApp(row: FileRow) {
        val verdict = row.launchable
        if (verdict !is Launchable.Runs) return
        viewModelScope.launch {
            registry.add(
                AppShortcut(
                    id = "",
                    containerId = containerId,
                    executable = row.guestPath,
                    name = shortcutName(row.name),
                    arch = verdict.arch ?: PeArchitecture.UNKNOWN,
                ),
            )
            _state.update {
                it.copy(notice = "Added ${row.name} to ${it.containerName}. It is on the home screen.")
            }
        }
    }

    /** Copy the selected file out to wherever Android's picker chose. */
    fun export(row: FileRow, destination: Uri) {
        viewModelScope.launch {
            val source = files.resolve(prefix, driveLetter, row.guestPath)
            if (source == null || !source.isFile) {
                _state.update { it.copy(notice = "${row.name} is not on this drive any more.") }
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) { files.copyOut(source, destination) }
            _state.update {
                it.copy(
                    notice = outcome.fold(
                        onSuccess = { "Exported ${row.name}." },
                        onFailure = { error ->
                            "The export failed: ${error.message ?: error::class.simpleName}"
                        },
                    ),
                )
            }
        }
    }

    /** `14.2 GB free of 62 GB`, or nothing at all when Android will not say. */
    private fun storageLine(): String {
        val (free, total) = files.capacityOf(paths.containersRoot) ?: return ""
        return "${gigabytes(free)} free of ${gigabytes(total)}"
    }

    private data class Listing(
        val rows: List<FileRow> = emptyList(),
        val error: String? = null,
        val storage: String = "",
    )

    private companion object {
        const val SEPARATOR = '\\'
        val DATE: SimpleDateFormat = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    }
}

private fun gigabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)

/**
 * What a program is called on a tile.
 *
 * The extension comes off a `.exe` because "Notepad++.exe" is nobody's idea of
 * a program's name, and stays on everything else because it is the only thing
 * telling two of them apart. Measured on the device: a `.bat`, a `.msi` and a
 * `.vbs` sitting beside each other in Downloads produced three tiles all
 * reading `vessel-hello`, with no way to tell which was which and no duplicate
 * anywhere in `shortcuts.json` — three distinct `executable` paths, one label.
 *
 * `.exe` alone rather than a list, because `.exe` is the only extension here
 * that carries no information: everything else in [launchabilityOf] is a
 * *different kind of thing* — a batch file, an installer, a script — and the
 * suffix is what says so, since the architecture badge cannot (a `.bat` has no
 * PE header and is honestly `UNKNOWN`).
 */
internal fun shortcutName(fileName: String): String =
    if (fileName.substringAfterLast('.', "").equals("exe", ignoreCase = true)) {
        fileName.substringBeforeLast('.')
    } else {
        fileName
    }
