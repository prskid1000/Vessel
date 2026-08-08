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
    /** The row a long-press or a tap is asking about; drives the action bar. */
    val selected: FileRow? = null,
    /** One-shot prose: an import that failed, a refusal, a completed export. */
    val notice: String? = null,
) {
    val atRoot: Boolean get() = GuestPath.segments(guestPath).size <= 1
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
) : ViewModel() {

    private val containerId: String = savedState.get<String>(Routes.ARG_CONTAINER_ID).orEmpty()

    /** `…/containers/<id>/prefix/drive_c` — the Android side of `C:`. */
    private val driveC: File = File(paths.of(containerId).prefix, GuestPath.DRIVE_C)

    private val _state = MutableStateFlow(FilesUiState(containerId = containerId))
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            _state.update { it.copy(containerName = name) }
        }
        navigateTo(GuestPath.DRIVE + SEPARATOR)
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
        val directory = GuestPath.resolve(driveC, guestPath)
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

        val children = directory.listFiles().orEmpty()
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
            val target = GuestPath.resolve(driveC, state.value.guestPath)
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
                    name = row.name.substringBeforeLast('.'),
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
            val source = GuestPath.resolve(driveC, row.guestPath)
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
