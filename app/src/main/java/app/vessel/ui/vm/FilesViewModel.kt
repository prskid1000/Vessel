package app.vessel.ui.vm

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.PeArchitecture
import app.vessel.data.ContainerPaths
import app.vessel.data.ContainerRepository
import app.vessel.ui.Routes
import app.vessel.ui.shell.AppRegistry
import app.vessel.ui.shell.AppShortcut
import app.vessel.core.DriveMap
import app.vessel.core.GuestDrive
import app.vessel.data.AndroidDrives
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.Launchable
import app.vessel.ui.shell.launchabilityOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * The container's `C:` drive, read directly from Android.
 *
 * **Wine is not running and does not need to be.** A Wine prefix is an ordinary
 * directory tree; listing it is `File.listFiles()`. That is the whole argument
 * for browsing it here rather than starting `winefile` inside the guest: it works
 * before the container has ever launched, it works while a session is running, it
 * costs nothing, and it gets import and export to Android storage for free —
 * which Wine's own file manager cannot do at all, because from inside the guest
 * there is no Android to copy to.
 *
 * Reads are on [Dispatchers.IO]. A prefix has tens of thousands of files in it and
 * `drive_c\windows\system32` alone is a slow stat on this device.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val paths: ContainerPaths,
    private val containers: ContainerRepository,
    private val registry: AppRegistry,
    private val drives: AndroidDrives,
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()

    private val prefix: File = paths.of(containerId).prefix

    /**
     * The drive the browser is showing, and the folder behind it.
     *
     * **Rooted through `dosdevices`, which is what makes every drive the same
     * thing.** `dosdevices/c:` is a symlink to `drive_c` and `dosdevices/d:` is
     * a symlink to the phone's storage, so opening either is opening a
     * directory — there is no special case for the prefix's own drive and no
     * second code path for a mapped one. The browser was rooted at `drive_c`
     * directly, which is why `D:` existed in the container and could not be
     * reached from here.
     */
    private var driveLetter: Char = DriveMap.SYSTEM_DRIVE

    private val driveRoot: File
        get() = File(File(prefix, DriveMap.DOSDEVICES), "$driveLetter:")

    /** Kept for the callers that still mean the prefix's own C:. */
    private val driveC: File = File(prefix, GuestPath.DRIVE_C)

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
        // **A drive this app cannot list is not offered as a tab.** `Z:` is the
        // Unix root, which SELinux denies to `untrusted_app`, so its tab could
        // only ever open onto a refusal — and a destination that always refuses
        // is one the user has to learn to avoid. It stays in the prefix, because
        // Wine creates it and reaches absolute Unix paths through it; it simply
        // is not somewhere to send anybody.
        //
        // The rule generalises past `Z:`, which is why it is a readability test
        // rather than a special case: a card that has been unmounted and a
        // mapped folder that has been deleted both disappear from the row for
        // the same reason and without further code.
        val list = drives.drives(prefix).filter { drive ->
            File(File(prefix, DriveMap.DOSDEVICES), "${drive.letter}:").list() != null
        }
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
        if (letter == driveLetter) return
        if (state.value.drives.none { it.letter == letter }) return
        driveLetter = letter
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

    private fun read(guestPath: String): Listing {
        val directory = GuestPath.resolve(driveRoot, guestPath)
        if (directory == null || !directory.isDirectory) {
            return Listing(
                error = if (!driveC.isDirectory) {
                    "This container has no C: drive yet. It is created the first time the " +
                        "container is launched, by Wine's own first-run setup."
                } else {
                    "$guestPath is not a folder on this drive any more."
                },
                storage = storageLine(),
            )
        }

        // **Null is not an empty array, and collapsing them was a lie.** `Z:` is
        // the Unix root, which an Android app is not allowed to list — SELinux
        // denies readdir on `/` to `untrusted_app`. `listFiles()` returns null,
        // `.orEmpty()` turned that into no entries, and the browser said "This
        // folder is empty" about a filesystem with everything in it. A thing
        // that cannot be done says so and names why; that is the rule.
        val children = directory.listFiles()
        if (children == null) {
            return Listing(
                // The target, not the link. Every drive is reached through
                // `dosdevices/<letter>:`, so `directory.path` is always that
                // symlink — naming it tells the user about Vessel's plumbing
                // when what they need to know is which folder was refused.
                error = "Android does not allow this app to list " +
                    runCatching { directory.canonicalPath }.getOrDefault(directory.path) +
                    ". Wine creates this drive; the phone's own storage is on D:.",
                storage = storageLine(),
            )
        }

        // Directories first, then files, each alphabetically and case-blind. A
        // Wine prefix mixes the two heavily and an undirected listing is unusable.
        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        return Listing(rows = sorted.map { toRow(it, guestPath) }, storage = storageLine())
    }

    private fun toRow(file: File, parent: String): FileRow {
        val guestPath = parent.trimEnd(SEPARATOR) + SEPARATOR + file.name
        val launchable = if (file.isDirectory) Launchable.NotAProgram else launchabilityOf(file)
        return FileRow(
            name = file.name,
            guestPath = guestPath,
            isDirectory = file.isDirectory,
            detail = if (file.isDirectory) {
                null
            } else {
                "${sizeLabel(file.length())} \u00b7 ${DATE.format(Date(file.lastModified()))}"
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
            val target = GuestPath.resolve(driveRoot, state.value.guestPath)
            if (target == null || !target.isDirectory) {
                _state.update { it.copy(notice = "That folder is not on this drive any more.") }
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(source)
                    val destination = File(target, name)
                    context.contentResolver.openInputStream(source).use { input ->
                        requireNotNull(input) { "Android would not open $name for reading." }
                        destination.outputStream().use { input.copyTo(it) }
                    }
                    name
                }
            }
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
            val source = GuestPath.resolve(driveRoot, row.guestPath)
            if (source == null || !source.isFile) {
                _state.update { it.copy(notice = "${row.name} is not on this drive any more.") }
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Android would not open the destination." }
                        source.inputStream().use { it.copyTo(output) }
                    }
                }
            }
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

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "imported.bin"

    /**
     * `14.2 GB free of 62 GB`.
     *
     * The app's own filesystem, which is where the prefix lives — not the guest's
     * idea of a disk, which Wine reports from the same place anyway.
     */
    private fun storageLine(): String {
        val root = paths.containersRoot
        val free = runCatching { root.freeSpace }.getOrDefault(0L)
        val total = runCatching { root.totalSpace }.getOrDefault(0L)
        if (total <= 0L) return ""
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
