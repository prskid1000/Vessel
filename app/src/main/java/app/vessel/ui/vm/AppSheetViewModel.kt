package app.vessel.ui.vm

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DriveMap
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
    /**
     * Whether this container has been launched at least once.
     *
     * Read as "does `drive_c` exist", which is the same question
     * `HomeViewModel.hasPrefix` asks and for the same reason: the prefix is
     * built by the first session, not by saving the container, so a container
     * that has only ever been created has no `C:` to browse. Browsing one is
     * not an error anybody can act on — the file browser opens on nothing and
     * says "This folder is empty", which reads as a bug in the browser rather
     * than as a container that has not run yet.
     */
    val hasPrefix: Boolean = false,
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

    /**
     * This view model has already committed an add.
     *
     * **Separate from [AppSheetUiState.finished], and the two must not be
     * conflated — I tried, and it broke the other half.** `finished` is a
     * one-shot *event*: the sheet reads it, dismisses, and clears it. This is a
     * durable *fact* about the instance, and `hiltViewModel` hands the same
     * instance back for the next Add on the same container. Testing `finished`
     * in [openNew] worked only until the event was correctly consumed, at which
     * point the reset stopped firing and the second Add came up still holding
     * the program that had just been added.
     */
    private var committed = false

    /**
     * Add a program to [containerId].
     *
     * **The second Add did nothing at all, and this is why.** `AppSheet` asks
     * for its view model with `hiltViewModel(key = "add-to-$containerId")`, and
     * that key is scoped to the nav entry — so every Add on the same container
     * gets back *the same instance*, carrying whatever the last one left in it.
     * Two pieces of that state then conspired:
     *
     *  - `opened` is a one-shot guard, so this function returned immediately and
     *    the draft was never reset — the sheet would have come up still holding
     *    the program just added; and
     *  - `finished` stayed true, and `AppSheet` has
     *    `LaunchedEffect(state.finished) { if (state.finished) onDismiss() }`,
     *    so the sheet dismissed itself in the same frame it opened.
     *
     * The visible symptom was the second one: tap Add, nothing happens, for ever.
     *
     * A finished sheet therefore starts a **new draft** rather than returning
     * early. The guard still holds for a re-entry that has *not* committed —
     * which is the case it was written for: a rotation, or the browser round
     * trip, must not wipe what the user has already chosen.
     */
    fun openNew(containerId: String) {
        if (opened && !committed) return
        committed = false
        opened = true

        // **Synchronously, outside the coroutine, and that is the whole fix.**
        // The first version of this reset inside `viewModelScope.launch`, which
        // is a frame too late: `AppSheet` composes, its dismiss effect reads a
        // `finished` that is still true, and the sheet closes before the reset
        // lands. Same symptom as before the fix, one layer down.
        //
        // A fresh value, not a `copy`. Copying carried `executable`, `name`,
        // `arch`, `args`, `refusal` and `alreadyAdded` over from the last add —
        // so the reset names nothing in order to reset everything, and a field
        // added to the state later cannot be forgotten here.
        _state.value = AppSheetUiState(loading = false, creating = true, containerId = containerId)

        // The name is a read, so it stays asynchronous. It only fills a subtitle.
        // The prefix check rides with it: one `isDirectory` stat, off the main
        // thread, and it decides whether the two browse routes are offered.
        viewModelScope.launch {
            val name = containers.get(containerId)?.name.orEmpty()
            val prefix = hasPrefix(containerId)
            _state.update {
                if (it.containerId == containerId) {
                    it.copy(containerName = name, hasPrefix = prefix)
                } else {
                    it
                }
            }
        }
    }

    /**
     * The sheet has acted on [AppSheetUiState.finished]; clear it.
     *
     * **`finished` is documented as one-shot and was never actually consumed.**
     * It stayed true for the life of the view model, and `AppSheet` asks
     * `hiltViewModel` for one keyed `"add-to-$containerId"` — the same instance
     * for every Add on that container. So the second Add opened a sheet whose
     * very first frame said "you are finished", and it dismissed itself before
     * anyone saw it. From the outside: tapping Add did nothing, for ever.
     *
     * Consuming it here rather than only resetting in [openNew] because the two
     * fix different halves. [openNew] stops the *draft* leaking between adds;
     * this stops the *event* doing it, and an event that survives being handled
     * is a bug waiting for the next reader.
     */
    fun acknowledgeFinished() {
        _state.update { if (it.finished) it.copy(finished = false) else it }
    }

    /**
     * Whether the container's `C:` exists yet.
     *
     * `drive_c` and not the prefix directory: `ContainerProvisioner` makes the
     * layout when the container is created, so the prefix is there long before
     * `wineboot` has put anything in it. `drive_c` is what the first session
     * creates, which is the line between "nothing to browse" and "something to
     * browse" — the same test `HomeViewModel` uses for the card's Files button.
     */
    private suspend fun hasPrefix(containerId: String): Boolean =
        withContext(Dispatchers.IO) {
            File(paths.of(containerId).prefix, DriveMap.DRIVE_C).isDirectory
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
            // **Against the drive the path names, not against `drive_c`.**
            // This resolved every path against C:, so choosing a game on a
            // mapped drive — the entire reason drive mapping exists — came back
            // as "There is no file at D:\Games\…\metro.exe on this container's
            // C: drive", about a file that is there. The sentence even named
            // the wrong drive while quoting the right one.
            val file = paths.of(containerId).resolveGuestPath(guestPath)
            if (file == null || !file.isFile) {
                val drive = GuestPath.driveOf(guestPath)
                _state.update {
                    it.copy(
                        executable = guestPath,
                        refusal = if (drive == null) {
                            "$guestPath does not name a drive, so there is nothing to look on."
                        } else {
                            // Named, because the usual cause on a mapped drive is
                            // that the volume is not plugged in — which is a
                            // thing the user can fix and "not found" is not.
                            "There is no file at $guestPath. If $drive is removable " +
                                "storage, check that it is still connected."
                        },
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
                    // Same rule as the browser's Add as app — see `shortcutName`.
                    // Two places derive a default name and they have to agree, or
                    // the same file added two ways gets two labels.
                    name = it.name.ifBlank { shortcutName(file.name) },
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
            committed = true
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

    /**
     * `C:` specifically, and only for the one caller that means it.
     *
     * Import copies a file *into* the container, and the container's own disk is
     * `C:` — copying onto a mapped drive would be writing into the user's own
     * folders, which import is not for. Everything that *reads* a guest path
     * resolves it against the drive the path names instead; see [setExecutable].
     */
    private fun driveOf(containerId: String): File =
        File(paths.of(containerId).prefix, GuestPath.DRIVE_C)

    private companion object {
        /** Wine's own per-user download folder, and where an import belongs. */
        const val DOWNLOADS = "users/vessel/Downloads"
    }
}
