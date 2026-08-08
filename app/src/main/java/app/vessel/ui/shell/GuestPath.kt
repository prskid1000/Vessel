package app.vessel.ui.shell

import java.io.File

/**
 * The two ways to name the same file, and the conversion between them.
 *
 * A container's `C:` drive is an ordinary Android directory —
 * `filesDir/containers/<id>/prefix/drive_c` — so the browser reads it with
 * `java.io.File` and never needs Wine running. The guest calls the same file
 * `C:\…`, and a shortcut has to store that form, because it is the guest that
 * will eventually be asked to run it.
 *
 * Everything here is pure string and [File] arithmetic. It touches no disk, which
 * is what lets it be tested without a device.
 */
object GuestPath {

    /** The one drive letter Vessel maps. `Z:` is Wine's own view of `/`, and not ours to browse. */
    const val DRIVE = "C:"

    /** The directory inside a Wine prefix that [DRIVE] names. */
    const val DRIVE_C = "drive_c"

    /**
     * `C:\users\vessel\Downloads` for a file under [root].
     *
     * Backslashes, and no trailing one: this string goes to the guest verbatim,
     * and Wine's own argument parsing is the reason it must look like something a
     * Windows program would have been given.
     *
     * Null when [file] is not inside [root] at all. A caller that gets null has
     * asked about a file outside the container, and the honest answer is that it
     * has no guest name — not a plausible one assembled from the tail.
     */
    fun of(root: File, file: File, drive: String = DRIVE): String? {
        val base = root.canonicalPathOrAbsolute()
        val target = file.canonicalPathOrAbsolute()
        if (target == base) return drive + SEPARATOR
        val prefix = base + File.separator
        if (!target.startsWith(prefix)) return null
        return drive + SEPARATOR + target.removePrefix(prefix).replace(File.separatorChar, SEPARATOR)
    }

    /**
     * The Android file a guest path names, or null if it escapes [root].
     *
     * The containment check is on the resolved path rather than on the input, so
     * `C:\..\..\etc` is refused rather than followed. Nothing in this app builds
     * such a string, but this function's inputs include a text field.
     */
    /**
     * The drive a path names, or null when it does not name one.
     *
     * Any letter, not only C:. The browser is no longer rooted at a single
     * drive — a container can carry the phone's storage on D: and a pinned
     * folder on E: — and a path's own letter is the only thing that says which
     * root it should be resolved against.
     */
    fun driveOf(guestPath: String): String? {
        val trimmed = guestPath.trimStart()
        if (trimmed.length < 2 || trimmed[1] != ':') return null
        val letter = trimmed[0]
        return if (letter.isLetter()) "" + letter.uppercaseChar() + ":" else null
    }

    fun resolve(root: File, guestPath: String): File? {
        val trimmed = guestPath.trim()
        // Any drive, and the caller is trusted to have passed the matching root.
        // Checking the letter here too would be a second source of truth for
        // which drive is open, and the two would disagree the first time one of
        // them was updated.
        val drive = driveOf(trimmed) ?: return null
        val tail = trimmed.drop(drive.length).trimStart(SEPARATOR, '/')
        val candidate = if (tail.isEmpty()) root else File(root, tail.replace(SEPARATOR, '/'))
        val base = root.canonicalPathOrAbsolute()
        val resolved = candidate.canonicalPathOrAbsolute()
        return if (resolved == base || resolved.startsWith(base + File.separator)) candidate else null
    }

    /** The guest's own parent-directory form of [guestPath], or null at the drive root. */
    fun parentOf(guestPath: String): String? {
        val body = guestPath.trimEnd(SEPARATOR)
        val cut = body.lastIndexOf(SEPARATOR)
        if (cut < DRIVE.length) return null
        return body.take(cut).ifEmpty { DRIVE } .let { if (it == DRIVE) DRIVE + SEPARATOR else it }
    }

    /** `C:\users\vessel` becomes `["C:", "users", "vessel"]`, for the breadcrumb. */
    fun segments(guestPath: String): List<String> =
        guestPath.trimEnd(SEPARATOR).split(SEPARATOR).filter { it.isNotEmpty() }

    /** The path a breadcrumb tap goes to: everything up to and including [index]. */
    fun upTo(guestPath: String, index: Int): String {
        val parts = segments(guestPath)
        if (index <= 0) return DRIVE + SEPARATOR
        return parts.take(index + 1).joinToString(SEPARATOR.toString())
    }

    /** The last segment — a file's or a folder's own name. */
    fun nameOf(guestPath: String): String = segments(guestPath).lastOrNull().orEmpty()

    private const val SEPARATOR = '\\'

    /**
     * `canonicalPath` where the filesystem allows it, `absolutePath` otherwise.
     *
     * Canonical resolves `..` and symlinks, which is the containment check's whole
     * point. It also throws on a path the process cannot stat, and a browser that
     * crashed on an unreadable directory would be worse than one that compares the
     * literal path.
     */
    private fun File.canonicalPathOrAbsolute(): String =
        runCatching { canonicalPath }.getOrElse { absolutePath }
}
