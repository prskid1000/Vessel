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

    /**
     * The phone's own storage, as a drive.
     *
     * What a second drive is called on every Windows machine anyone has used.
     * Here rather than in [app.vessel.data.AndroidDrives], which maps it,
     * because the home screen has to ask whether it *is* mapped and a letter
     * spelled in two files is a letter that can disagree with itself.
     */
    const val SHARED_STORAGE_DRIVE = 'd'

    /** Wine's own: the Unix filesystem root. */
    const val ROOT_DRIVE = 'z'

    /** Where the symlinks live, under a prefix. */
    const val DOSDEVICES = "dosdevices"

    /** What [SYSTEM_DRIVE] points at, under a prefix. Wine's name, not ours. */
    const val DRIVE_C = "drive_c"

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
        ensureSystemDrive(prefix)
        val link = File(dir, "${letter.lowercaseChar()}:")
        // Delete rather than overwrite: a symlink cannot be re-pointed in place,
        // and `createSymbolicLink` refuses an existing name.
        if (link.exists() || isSymlink(link)) link.delete()
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    }.getOrDefault(false)

    /**
     * `drive_c` and `dosdevices/c:`, if nothing has made them yet.
     *
     * **This is a job taken off Wine, and the job has two halves.**
     * `server_init_process_done` in `ntdll/unix/server.c` reads:
     *
     * ```c
     * if (!mkdir( "dosdevices", 0777 )) {
     *     mkdir( "drive_c", 0777 );
     *     symlink( "../drive_c", "dosdevices/c:" );
     *     symlink( "/", "dosdevices/z:" );
     * }
     * ```
     *
     * — the C: drive is created *only* on the pass that creates the directory.
     * Get there first and `mkdir` returns EEXIST, the whole block is skipped,
     * and the prefix comes up with **no `drive_c` and no `dosdevices/c:` at
     * all**, for ever.
     *
     * Measured on a fresh install, and it is not subtle: 773 lines of
     * `setupapi:create_dest_file failed … (error=3)` as `wine.inf` copied every
     * DLL into a directory that was not there, `wineboot: Cannot set the dir to
     * L"C:\windows" (2)`, and `Couldn't start services.exe: error 267` — which
     * is ERROR_DIRECTORY, and is why `RpcSs` has never started inside the app
     * while starting perfectly under `run-as`. The `run-as` harness lets Wine
     * build its own prefix; the app maps `D:` first.
     *
     * It needed the storage permission to already be granted when the container
     * was created, which is why it survived this long and why it appeared now:
     * `+` asks for the permission, so having it before the first launch is the
     * normal path rather than the unusual one.
     *
     * Written as a repair rather than as an else-branch of the `mkdir` above,
     * because there are prefixes in this state already and nothing else will
     * ever fix one — Wine will not revisit that block for the life of the
     * prefix. `z:` is deliberately not created; see [removeRootDrive].
     *
     * **The stamp goes with it, and a repair is worthless without that.** A
     * broken prefix has already run `wineboot` once, so it has an
     * `.update-timestamp` matching `wine.inf`, and the next `wineboot --update`
     * would look at it, conclude there is nothing to do, and leave `drive_c`
     * the empty directory this just made. Removing the stamp is how you say "no
     * boot has really happened here" — one forced `wine.inf` pass, on a prefix
     * that never got a successful one.
     */
    fun ensureSystemDrive(prefix: File): Boolean = runCatching {
        val dir = File(prefix, DOSDEVICES)
        // **Only when `dosdevices` is already there.** No directory means Wine
        // has not been near this prefix and nothing has got in its way, so its
        // own branch will run and do all four lines properly. Creating a
        // `drive_c` here would be this function causing the situation it exists
        // to repair — and it would put a directory in a prefix the provisioner
        // is entitled to treat as untouched.
        if (!dir.isDirectory) return false
        val link = File(dir, "$SYSTEM_DRIVE:")
        if (link.exists() || isSymlink(link)) return false
        File(prefix, DRIVE_C).mkdirs()
        java.nio.file.Files.createSymbolicLink(link.toPath(), File("../$DRIVE_C").toPath())
        File(prefix, UPDATE_STAMP).delete()
        true
    }.getOrDefault(false)

    /** Wine's record of which `wine.inf` this prefix was last updated against. */
    private const val UPDATE_STAMP = ".update-timestamp"

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

    /**
     * Take `Z:` away, because the unix root is Android and Android is not a
     * drive the user chose.
     *
     * Wine maps `/` to `Z:` on every prefix. On a desktop that is a
     * convenience; here it hands a guest program `/data/user/0/app.vessel` —
     * this app's own private storage, writable — under a drive letter nobody
     * asked for, alongside the whole of `/system` and every other app's
     * sandbox. Vessel's storage model is that a drive is a folder the user
     * picked, and this is the one drive that contradicts it.
     *
     * Separate from [unmap] rather than an exception inside it, and that is the
     * safety argument: [unmap] is what a long press on a tab calls, and `Z:`
     * must stay un-removable *by the user* even while the product removes it.
     * The two are different decisions and they should not share a code path.
     *
     * Called on every provision because `wineboot` recreates the link whenever
     * it initialises a prefix, so removing it once is removing it until the next
     * time. Idempotent: false means it was already gone, which is the steady
     * state and not a failure.
     */
    fun removeRootDrive(prefix: File): Boolean {
        val link = File(File(prefix, DOSDEVICES), "$ROOT_DRIVE:")
        return runCatching { link.delete() }.getOrDefault(false)
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
