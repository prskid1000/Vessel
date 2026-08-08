package app.vessel.core

import java.io.File

/**
 * One drive letter, and what is behind it.
 *
 * [target] is the *Unix* path the symlink points at, which is the only thing
 * Wine cares about. [label] is what a drive list shows: the folder's own name,
 * or a description for the two Wine creates itself.
 */
data class GuestDrive(
    /** Lowercase, no colon — `c`, `d`, `z`. The file in `dosdevices` is `c:`. */
    val letter: Char,
    val target: String,
    val label: String,
) {
    /** `C:` — how a drive is written everywhere a user sees one. */
    val display: String get() = "${letter.uppercaseChar()}:"

    /** Wine's own, which exist in every prefix and are not the user's to remove. */
    val builtIn: Boolean get() = letter == DriveMap.SYSTEM_DRIVE || letter == DriveMap.ROOT_DRIVE
}

/**
 * The drives a prefix has, as a list and as symlinks.
 *
 * **A drive is a symlink in `dosdevices` and nothing else.** Wine resolves
 * `D:\Games\x.exe` through `dosdevices/d:` and then reads the result with the
 * process's own uid — which, since the guest is a child of this app, is the
 * app's. So mapping the phone's storage into a container is one `symlink(2)`
 * and one Android permission; there is no filesystem to implement and nothing
 * to copy. That is the whole reason this file is short.
 *
 * The letters are deliberately conservative. `A:` and `B:` are skipped because
 * decades of software still treats them as removable floppies and some of it
 * probes them at startup; `C:` is the prefix and `Z:` is the Unix root, both of
 * which Wine creates and neither of which is the user's to reassign.
 */
object DriveMap {

    /** Wine's own: the prefix's `drive_c`. */
    const val SYSTEM_DRIVE = 'c'

    /** Wine's own: the Unix filesystem root. */
    const val ROOT_DRIVE = 'z'

    /** Where the symlinks live, under a prefix. */
    const val DOSDEVICES = "dosdevices"

    /**
     * Letters offered to a new mapping, in the order they are handed out.
     *
     * `D` first because it is what a second drive is called on every Windows
     * machine anyone has used, and the first thing a user maps is the one they
     * will type most.
     */
    val ASSIGNABLE: List<Char> = ('d'..'y').toList()

    /**
     * Every drive the prefix has, in letter order.
     *
     * Reads the directory rather than any record the app keeps, for the same
     * reason the registry seed's marker lives in the hive: a note saying a
     * mapping exists is not the mapping, and the two drift the moment anything
     * goes wrong. A dangling symlink is still listed — it is a real drive that
     * Wine will fail to open, and hiding it would make the failure inexplicable.
     */
    fun drives(prefix: File): List<GuestDrive> {
        val dir = File(prefix, DOSDEVICES)
        val entries = dir.listFiles().orEmpty()
        return entries.mapNotNull { entry ->
            val letter = letterOf(entry.name) ?: return@mapNotNull null
            val target = runCatching { entry.canonicalPath }.getOrDefault(entry.path)
            GuestDrive(letter = letter, target = target, label = labelFor(letter, target))
        }.sortedBy { it.letter }
    }

    /**
     * `c:` becomes `c`; `com1`, `c::`, `.` and anything else becomes null.
     *
     * `dosdevices` also holds serial and parallel port links — `com1`, `lpt1` —
     * which are devices rather than drives and must not appear in a drive list.
     */
    fun letterOf(name: String): Char? {
        if (name.length != 2 || name[1] != ':') return null
        val letter = name[0].lowercaseChar()
        return if (letter in 'a'..'z') letter else null
    }

    /** The first free assignable letter, or null when all of them are taken. */
    fun nextFreeLetter(taken: Collection<Char>): Char? {
        val used = taken.map { it.lowercaseChar() }.toSet()
        return ASSIGNABLE.firstOrNull { it !in used }
    }

    /**
     * What the drive list calls it.
     *
     * The two Wine makes get a description, because "drive_c" and "/" are true
     * and say nothing. Everything else is named after the folder it points at,
     * which is what the user chose and therefore what they will recognise.
     */
    fun labelFor(letter: Char, target: String): String = when {
        letter == SYSTEM_DRIVE -> "Windows"
        letter == ROOT_DRIVE -> "Android"
        else -> {
            val tail = target.trimEnd('/').substringAfterLast('/')
            // `/storage/emulated/0` ends in the Android user id, so the folder
            // name is "0" — a true last segment and a useless drive label. An
            // all-digit tail is one of those rather than a folder somebody named.
            if (tail.isEmpty() || tail.all { it.isDigit() }) PHONE_STORAGE else tail
        }
    }

    /** What `/storage/emulated/<user>` is called, its own name being a number. */
    const val PHONE_STORAGE = "Phone"

    /**
     * Point [letter] at [target], replacing any existing mapping.
     *
     * Returns false rather than throwing, because every caller has somewhere
     * better to put the failure than a crash: the provisioner logs it and
     * carries on without the drive, and the UI says the folder could not be
     * mapped. The usual cause is the one this cannot fix — no permission to
     * read the path — and that has to be reported, not retried.
     */
    fun map(prefix: File, letter: Char, target: File): Boolean = runCatching {
        val dir = File(prefix, DOSDEVICES)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val link = File(dir, "${letter.lowercaseChar()}:")
        // Delete rather than overwrite: a symlink cannot be re-pointed in place,
        // and `createSymbolicLink` refuses an existing name.
        if (link.exists() || isSymlink(link)) link.delete()
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    }.getOrDefault(false)

    /**
     * Remove [letter]'s mapping.
     *
     * **Deletes the link, never the target.** `File.delete` on a symlink unlinks
     * it, which is what is wanted; anything that followed the link and recursed
     * would delete the user's folder, and that is the one mistake this feature
     * must not be able to make.
     */
    fun unmap(prefix: File, letter: Char): Boolean {
        if (letter == SYSTEM_DRIVE || letter == ROOT_DRIVE) return false
        val link = File(File(prefix, DOSDEVICES), "${letter.lowercaseChar()}:")
        return runCatching { link.delete() }.getOrDefault(false)
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
