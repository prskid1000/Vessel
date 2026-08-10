package app.vessel.data

import android.content.Context
import android.net.Uri
import app.vessel.core.DriveMap
import app.vessel.ui.shell.GuestPath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry in a container's drive, as read from Android.
 *
 * [file] is carried through rather than discarded because the caller still has
 * to open it — `launchabilityOf` reads a PE header to decide whether a row can
 * be started, and that is a question about a program, not about a filesystem.
 * Everything a row *displays* is captured here at read time, so the browser
 * never stats a file again while it is scrolling: `length()` and
 * `lastModified()` are syscalls, and a `LazyColumn` will happily make them
 * once per row per frame.
 */
data class PrefixEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    /** Meaningless for a directory, and the caller does not read it there. */
    val size: Long,
    val modified: Long,
)

/**
 * The outcome of listing a folder, with the *reason* it did not work.
 *
 * A sealed result rather than a nullable list, because the three failures need
 * three different sentences and the difference is not knowable from an empty
 * list. Collapsing them is exactly the bug this shape prevents: `listFiles()`
 * returns null for a directory Android refuses to read, `.orEmpty()` turned
 * that into no entries, and the browser said "this folder is empty" about a
 * filesystem with everything in it.
 */
sealed interface DriveListing {
    /** Directories first, then files, each case-blind alphabetical. */
    data class Entries(val entries: List<PrefixEntry>) : DriveListing

    /** The prefix has no `drive_c` yet — Wine has never run in this container. */
    data object NoSystemDrive : DriveListing

    /** The drive is there; this path on it is not a folder any more. */
    data class NotAFolder(val guestPath: String) : DriveListing

    /**
     * Android refused to list it. [path] is the *target*, resolved through the
     * `dosdevices` symlink — naming the link tells the user about Vessel's
     * plumbing when what they need is which folder was refused.
     */
    data class Denied(val path: String) : DriveListing
}

/**
 * A container's drives, read from Android.
 *
 * **Wine is not running and does not need to be.** A Wine prefix is an ordinary
 * directory tree; listing it is `File.listFiles()`. That is the whole argument
 * for browsing it from the app rather than starting `winefile` inside the
 * guest: it works before the container has ever launched, it works while a
 * session is running, it costs nothing, and it gets import and export to
 * Android storage for free — which Wine's own file manager cannot do at all,
 * because from inside the guest there is no Android to copy to.
 *
 * **Rooted through `dosdevices`, which is what makes every drive the same
 * thing.** `dosdevices/c:` is a symlink to `drive_c` and `dosdevices/d:` is a
 * symlink to the phone's storage, so opening either is opening a directory —
 * there is no special case for the prefix's own drive and no second code path
 * for a mapped one.
 *
 * Sibling to [AndroidDrives], and the split is the same one: that class decides
 * *which* drives a prefix has and creates the links, this one reads what is
 * behind them. Neither blocks — every call here hits the filesystem and the
 * caller is expected to be on `Dispatchers.IO`. A prefix has tens of thousands
 * of files in it and `drive_c\windows\system32` alone is a slow stat on this
 * device.
 */
@Singleton
class PrefixFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The directory behind `<letter>:` in [prefix]. May not exist. */
    fun driveRoot(prefix: File, letter: Char): File =
        File(File(prefix, DriveMap.DOSDEVICES), "${letter.lowercaseChar()}:")

    /** Whether Wine's first-run setup has created this prefix's own `C:`. */
    fun hasSystemDrive(prefix: File): Boolean = File(prefix, DriveMap.DRIVE_C).isDirectory

    /**
     * Whether `<letter>:` can be read at all.
     *
     * The caller uses this to decide whether to offer the drive, and both ways
     * of failing want the same answer: a card that has been unmounted and a
     * mapped folder that has been deleted each leave a dangling symlink, and
     * `list()` returns null for both — as it does for a directory Android
     * refuses outright.
     */
    fun isListable(prefix: File, letter: Char): Boolean =
        driveRoot(prefix, letter).list() != null

    /**
     * The real directory or file a guest path names, or null if it escapes the
     * drive. [GuestPath.resolve] owns the traversal rules; this is the overload
     * that knows where a drive starts.
     */
    fun resolve(prefix: File, letter: Char, guestPath: String): File? =
        GuestPath.resolve(driveRoot(prefix, letter), guestPath)

    /** List [guestPath] on `<letter>:`, saying why when it cannot. */
    fun list(prefix: File, letter: Char, guestPath: String): DriveListing {
        val directory = resolve(prefix, letter, guestPath)
        if (directory == null || !directory.isDirectory) {
            return if (!hasSystemDrive(prefix)) {
                DriveListing.NoSystemDrive
            } else {
                DriveListing.NotAFolder(guestPath)
            }
        }
        val children = directory.listFiles()
            ?: return DriveListing.Denied(
                runCatching { directory.canonicalPath }.getOrDefault(directory.path),
            )

        // Directories first, then files, each alphabetically and case-blind. A
        // Wine prefix mixes the two heavily and an undirected listing is unusable.
        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        return DriveListing.Entries(
            sorted.map {
                // One stat per file, here, rather than four spread over the
                // render pass. isDirectory is the only one a directory needs.
                val isDirectory = it.isDirectory
                PrefixEntry(
                    file = it,
                    name = it.name,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else it.length(),
                    modified = if (isDirectory) 0L else it.lastModified(),
                )
            },
        )
    }

    /**
     * Copy an Android document into [target], returning the name it landed
     * under.
     *
     * The name comes from the last path segment of the document URI, which is
     * what the Storage Access Framework gives without a second query.
     */
    fun copyIn(source: Uri, target: File): Result<String> = runCatching {
        val name = displayName(source)
        val destination = File(target, name)
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Android would not open $name for reading." }
            destination.outputStream().use { input.copyTo(it) }
        }
        name
    }

    /** Copy [source] out to wherever Android's picker chose. */
    fun copyOut(source: File, destination: Uri): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(destination).use { output ->
            requireNotNull(output) { "Android would not open the destination." }
            source.inputStream().use { it.copyTo(output) }
        }
    }

    /**
     * Free and total bytes of the filesystem [root] is on, or nulls when
     * Android will not say.
     *
     * The app's own filesystem, which is where the prefix lives — not the
     * guest's idea of a disk, which Wine reports from the same place anyway.
     */
    fun capacityOf(root: File): Pair<Long, Long>? {
        val free = runCatching { root.freeSpace }.getOrDefault(0L)
        val total = runCatching { root.totalSpace }.getOrDefault(0L)
        return if (total <= 0L) null else free to total
    }

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "imported.bin"
}
