package app.vessel.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.core.DriveMap
import app.vessel.core.ContainerProfile
import app.vessel.core.PeArchitecture
import app.vessel.core.params.ParamValue
import app.vessel.data.ContainerPaths
import app.vessel.data.ContainerRepository
import app.vessel.ui.shell.AppRegistry
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.AppShortcut
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * One container and everything the home card draws for it.
 *
 * [meta] is assembled here rather than in the card because it is three facts from
 * two sources — when the container last ran, and the two display settings out of
 * its param map — and a composable doing clock arithmetic and map lookups is a
 * composable that cannot be read.
 */
@Immutable
data class HomeContainer(
    val profile: ContainerProfile,
    val meta: String,
    val shortcuts: List<AppShortcut>,
    /**
     * Whether this container has a `drive_c` to browse.
     *
     * **Read off the filesystem, not inferred from `lastRun`.** The prefix is
     * created by `wineboot` rather than by saving the settings, so a container
     * that has never run usually has no drive — but "usually" is the wrong word
     * for a folder button: a launch that failed after `wineboot` leaves a real
     * drive behind a null `lastRun`, and a container whose directory was cleared
     * out from under the app has the opposite. The directory is the fact.
     */
    val hasPrefix: Boolean,

    /**
     * Both drives a container is supposed to have are actually there.
     *
     * `C:` and `D:` — the prefix's own system drive, and the phone's storage.
     * Separate from [hasPrefix], which asks only about `C:`, because the two
     * gate different things: `C:` is enough to *browse* a container, and adding
     * a program needs somewhere to add one *from*.
     *
     * **`D:` is the one that is conditional.** `ContainerProvisioner` maps it
     * only when all-files access has been granted, so a container built without
     * that permission comes up with a `C:` and nothing else — and the file
     * picker then opens on a prefix containing no program anyone put there.
     * Offering "Add" in that state is offering a control that cannot succeed.
     *
     * Read off `dosdevices` rather than remembered, on the rule the rest of the
     * drive code follows: a note saying a mapping exists is not the mapping. A
     * card that has been unmounted leaves a dangling symlink, and `exists()`
     * follows the link, so this goes false exactly when the drive stops working.
     */
    val drivesReady: Boolean,
)

@Immutable
data class HomeUiState(
    val containers: List<HomeContainer> = emptyList(),
    /**
     * False only until the store's first emission. Without it the home screen
     * flashes the "no containers yet" empty state on every cold start, which
     * reads as data loss rather than as a read in progress.
     */
    val loaded: Boolean = false,
) {
    /** `2 containers · 6 programs`, or null on a first run with nothing to count. */
    val subtitle: String?
        get() {
            if (containers.isEmpty()) return null
            val programs = containers.sumOf { it.shortcuts.size }
            return "${count(containers.size, "container")} · ${count(programs, "program")}"
        }

    private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
}

/**
 * Both `C:` and `D:` resolve to something readable under [prefix].
 *
 * `exists()` rather than a remembered flag, and it follows the symlink: every
 * drive is a link under `dosdevices`, so a card that has been unmounted or a
 * mapping whose target was deleted goes false here without another line of code.
 */
private fun drivesReady(prefix: File): Boolean =
    File(prefix, GuestPath.DRIVE_C).isDirectory &&
        File(File(prefix, DriveMap.DOSDEVICES), "${DriveMap.SHARED_STORAGE_DRIVE}:").exists()

/**
 * Home — the only root.
 *
 * It joins two sources that have no knowledge of each other: the container store,
 * and the app registry the shell keeps. The join belongs here because a shortcut
 * knows which container it is in and a container knows nothing about shortcuts,
 * which is the right way round — deleting a container must not need the registry
 * to have been consulted first.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val containers: ContainerRepository,
    private val registry: AppRegistry,
    private val paths: ContainerPaths,
) : ViewModel() {

    val state: StateFlow<HomeUiState> =
        combine(containers.containers, registry.shortcuts) { profiles, shortcuts ->
            HomeUiState(
                containers = profiles.map { profile ->
                    HomeContainer(
                        profile = profile,
                        meta = metaLine(profile),
                        shortcuts = shortcuts.filter { it.containerId == profile.id },
                        hasPrefix = File(
                            paths.of(profile.id).prefix,
                            GuestPath.DRIVE_C,
                        ).isDirectory,
                        drivesReady = drivesReady(paths.of(profile.id).prefix),
                    )
                },
                loaded = true,
            )
        }
            // The `isDirectory` stat per container is small but it is still disk,
            // and this flow re-emits on every registry write.
            .flowOn(Dispatchers.IO)
            .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            initialValue = HomeUiState(),
        )

    /**
     * Delete a container, and forget the programs that lived in it.
     *
     * The registry is cleared *after* the repository, in that order: a shortcut
     * pointing at a container that no longer exists is a tile that launches
     * nothing, and it is better to be briefly missing a tile than briefly holding
     * a dead one.
     */
    fun delete(id: String) {
        viewModelScope.launch {
            containers.delete(id)
            registry.removeAllIn(id)
        }
    }
}

/** Five seconds of grace, so a rotation does not tear down and re-read the store. */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * `ran 12 minutes ago · 1280×720 · 60 fps`.
 *
 * The one line under a container's name, and it is three facts rather than one
 * because the card is otherwise a name and two buttons. Never launched is stated
 * rather than left blank: a brand-new container genuinely has not run, and an
 * empty line where every other card has a timestamp looks like a failed read.
 *
 * The two display values come out of the param map by key. That is the only place
 * in the UI that names a manifest key, and it is here rather than in the card so
 * there is exactly one.
 */
internal fun metaLine(profile: ContainerProfile, now: Long = System.currentTimeMillis()): String {
    val ran = profile.lastRun?.let { "ran ${relativeLabel(it, now)}" } ?: "never launched"
    val resolution = profile.text(KEY_RESOLUTION)?.replace("x", "\u00d7")
    val fps = profile.text(KEY_FPS_LIMIT)?.let { "$it fps" }
    return listOfNotNull(ran, resolution, fps).joinToString(" \u00b7 ")
}

private fun ContainerProfile.text(key: String): String? =
    (params[key] as? ParamValue.Text)?.value?.takeIf { it.isNotBlank() }

private const val KEY_RESOLUTION = "display.resolution"
private const val KEY_FPS_LIMIT = "display.fpsLimit"

/**
 * "ran 12 minutes ago", and "never launched" when it has not been.
 *
 * Kept as its own function because [metaLine] is not the only caller that wants
 * the phrase on its own — the container sheet's header uses it too.
 */
internal fun lastRunLabel(lastRun: Long?, now: Long = System.currentTimeMillis()): String {
    if (lastRun == null) return "never launched"
    return "ran ${relativeLabel(lastRun, now)}"
}

/**
 * One container with two programs in it — preview data, and only preview data.
 *
 * The screen itself reads the store, so a device with nothing installed shows the
 * empty state rather than a plausible-looking home page of containers that do not
 * exist.
 */
internal val SampleHomeContainers = listOf(
    HomeContainer(
        profile = ContainerProfile(
            id = "display-proof",
            name = "Display proof",
            wineBuild = "wine-11.0-arm64ec",
            driver = "turnip-25.2.0-gen8-canoe",
            d3dLayer = "dxvk-2.7.1",
        ),
        meta = "ran 12 minutes ago \u00b7 1280\u00d7720 \u00b7 60 fps",
        shortcuts = listOf(
            AppShortcut(
                id = "1",
                containerId = "display-proof",
                executable = "C:\\Program Files\\Notepad++\\notepad++.exe",
                name = "Notepad++",
                arch = PeArchitecture.ARM64,
            ),
            AppShortcut(
                id = "2",
                containerId = "display-proof",
                executable = "C:\\Program Files\\Winamp\\winamp.exe",
                name = "Winamp",
                arch = PeArchitecture.X86,
            ),
        ),
        hasPrefix = true,
        drivesReady = true,
    ),
)
