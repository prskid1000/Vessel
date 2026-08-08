package app.vessel.ui.vm

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.PeArchitecture
import app.vessel.data.ContainerPaths
import app.vessel.data.ContainerRepository
import app.vessel.ui.shell.AppRegistry
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.Launchable
import app.vessel.ui.shell.archProvenance
import app.vessel.ui.shell.launchabilityOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@Immutable
data class AppSheetUiState(
    val loading: Boolean = true,
    /** True while adding; false when editing one that already exists. */
    val creating: Boolean = true,
    val containerId: String = "",
    val containerName: String = "",
    /** Empty until a file has been chosen. */
    val executable: String = "",
    val name: String = "",
    val arch: PeArchitecture = PeArchitecture.UNKNOWN,
    /** How the architecture was determined — the sheet says this out loud. */
    val archNote: String = "",
    val args: String = "",
    val workingDir: String = "",
    /** Set when the chosen file cannot be run, with the sentence that says why. */
    val refusal: String? = null,
    /** True when the file runs but not completely — Windows Script Host. */
    val caveat: String? = null,
    /** One-shot prose after an import or a failed launch. */
    val notice: String? = null,
    /** One-shot: the sheet closes. */
    val finished: Boolean = false,
    /**
     * Set when this container already has a shortcut to the chosen executable.
     *
     * Adding it again is not an error — the registry replaces rather than
     * duplicating — but offering Add for something already on the home screen is
     * a button whose effect the user cannot see. So it is disabled and this
     * sentence says why, rather than letting a press appear to do nothing.
     *
     * Only while [creating]. Editing a shortcut is *supposed* to hit the same
     * executable, and disabling Save there would make the sheet read-only.
     */
    val alreadyAdded: String? = null,
) {
    /**
     * Add stays disabled until a runnable file has been chosen — and stays
     * disabled if that file is already on this container's home row.
     */
    val canSave: Boolean
        get() = executable.isNotBlank() && refusal == null && alreadyAdded == null
}

/**
 * The app sheet — a program's whole profile, and the form that creates one.
 *
 * **Three fields and one read-only fact**, and the fact is the interesting one.
 * The executable's architecture is read off its PE header and stated together
 * with how it was determined, because `unread` is a different claim from `x86`
 * and only the sentence separates them.
 *
 * Everything else about how a program runs belongs to its container — the driver,
 * the D3D layer, the memory-ordering flags. Two places to configure one thing is
 * one too many, and this sheet says so rather than growing a second copy of the
 * container's settings.
 *
 * The one place this refuses is a file the engine cannot serve. See
 * [app.vessel.ui.shell.launchabilityOf]: a `.ps1` is rejected with the reason,
 * because Wine's PowerShell is a stub that would appear to launch and then do
 * nothing.
 */
@HiltViewModel
class AppSheetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paths: ContainerPaths,
    private val containers: ContainerRepository,
    private val registry: AppRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(AppSheetUiState())
    val state: StateFlow<AppSheetUiState> = _state.asStateFlow()

    private var shortcutId: String = ""
    private var opened = false

    /** Add a program to [containerId]. */
    fun openNew(containerId: String) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = false,
                    creating = true,
                    containerId = containerId,
                    containerName = containers.get(containerId)?.name.orEmpty(),
                )
            }
        }
    }

    /** Open the profile of a program that already exists. */
    fun openExisting(shortcut: AppShortcut) {
        if (opened) return
        opened = true
        shortcutId = shortcut.id
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = false,
                    creating = false,
                    containerId = shortcut.containerId,
                    containerName = containers.get(shortcut.containerId)?.name.orEmpty(),
                    executable = shortcut.executable,
                    name = shortcut.name,
                    arch = shortcut.arch,
                    archNote = archProvenance(shortcut.arch),
                    args = shortcut.args,
                    workingDir = shortcut.workingDir,
                )
            }
        }
    }

    // — the one field that reads the file ------------------------------------

    /**
     * Point the shortcut at a guest path, and read everything else off the file.
     *
     * The name, the architecture and whether it can run at all come from the
     * bytes. Nothing here is typed twice, which is the whole reason the Add form
     * has one field.
     */
    fun setExecutable(guestPath: String) {
        val containerId = state.value.containerId
        viewModelScope.launch {
            val drive = driveOf(containerId)
            val file = GuestPath.resolve(drive, guestPath)
            if (file == null || !file.isFile) {
                _state.update {
                    it.copy(
                        executable = guestPath,
                        refusal = "There is no file at $guestPath on this container's C: drive.",
                    )
                }
                return@launch
            }
            val verdict = withContext(Dispatchers.IO) { launchabilityOf(file) }
            val arch = (verdict as? Launchable.Runs)?.arch ?: PeArchitecture.UNKNOWN

            // Only while creating: an existing shortcut is *supposed* to point at
            // its own executable, and flagging that would make editing one
            // impossible. Case-insensitive, and matched the same way the registry
            // matches — a Windows path, so `C:\x.exe` and `c:\X.EXE` are one file.
            val duplicate = state.value.creating &&
                registry.shortcuts.first().any {
                    it.containerId == containerId &&
                        it.executable.equals(guestPath, ignoreCase = true)
                }

            _state.update {
                it.copy(
                    executable = guestPath,
                    name = it.name.ifBlank { file.nameWithoutExtension },
                    arch = arch,
                    alreadyAdded = if (duplicate) {
                        "${file.name} is already on ${it.containerName}'s home row."
                    } else {
                        null
                    },
                    archNote = when (verdict) {
                        is Launchable.Runs ->
                            if (verdict.arch != null) {
                                archProvenance(verdict.arch)
                            } else {
                                "Started through ${verdict.via}."
                            }

                        else -> ""
                    },
                    refusal = (verdict as? Launchable.Refused)?.reason,
                    caveat = (verdict as? Launchable.Runs)?.caveat,
                )
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun setArgs(args: String) = _state.update { it.copy(args = args) }

    fun setWorkingDir(dir: String) = _state.update { it.copy(workingDir = dir) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Copy a file out of Android storage into the container, then point at it.
     *
     * It lands in `C:\users\vessel\Downloads` when that exists and at the drive
     * root when it does not — a container that has never run has neither, and the
     * import is refused rather than creating a tree Wine has not made yet.
     */
    fun importFromAndroid(source: Uri) {
        val containerId = state.value.containerId
        viewModelScope.launch {
            val drive = driveOf(containerId)
            if (!drive.isDirectory) {
                _state.update {
                    it.copy(
                        notice = "This container has no C: drive yet. Launch it once and Wine " +
                            "will create one, then import into it.",
                    )
                }
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = File(drive, DOWNLOADS).takeIf { it.isDirectory } ?: drive
                    val name = source.lastPathSegment?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() } ?: "imported.exe"
                    val destination = File(folder, name)
                    context.contentResolver.openInputStream(source).use { input ->
                        requireNotNull(input) { "Android would not open $name for reading." }
                        destination.outputStream().use { input.copyTo(it) }
                    }
                    GuestPath.of(drive, destination)
                }
            }
            outcome.fold(
                onSuccess = { guestPath ->
                    if (guestPath == null) {
                        _state.update { it.copy(notice = "The copy landed outside the drive.") }
                    } else {
                        setExecutable(guestPath)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            notice = "The import failed: " +
                                (error.message ?: error::class.simpleName),
                        )
                    }
                },
            )
        }
    }

    // — commit ---------------------------------------------------------------

    fun save() {
        val current = state.value
        if (!current.canSave) return
        viewModelScope.launch {
            registry.add(
                AppShortcut(
                    id = shortcutId,
                    containerId = current.containerId,
                    executable = current.executable.trim(),
                    name = current.name.trim().ifBlank { GuestPath.nameOf(current.executable) },
                    arch = current.arch,
                    args = current.args.trim(),
                    workingDir = current.workingDir.trim(),
                ),
            )
            _state.update { it.copy(finished = true) }
        }
    }

    /**
     * Persist an edit to a shortcut that already exists.
     *
     * Called when the profile sheet closes. A no-op while adding, because until
     * Add is pressed there is nothing to update — writing a half-filled draft
     * into the registry on dismiss would put a nameless tile on the home screen
     * every time somebody opened the sheet and changed their mind.
     */
    fun saveEdits() {
        if (state.value.creating || shortcutId.isBlank()) return
        save()
    }

    fun remove() {
        if (shortcutId.isBlank()) return
        viewModelScope.launch {
            registry.remove(shortcutId)
            _state.update { it.copy(finished = true) }
        }
    }

    /**
     * The registry's own copy of this shortcut, for a caller that needs to launch
     * it rather than edit it.
     */
    suspend fun current(): AppShortcut? =
        registry.shortcuts.first().firstOrNull { it.id == shortcutId }

    private fun driveOf(containerId: String): File =
        File(paths.of(containerId).prefix, GuestPath.DRIVE_C)

    private companion object {
        /** Wine's own per-user download folder, and where an import belongs. */
        const val DOWNLOADS = "users/vessel/Downloads"
    }
}
