package app.vessel.data

import java.io.File
import java.nio.file.Files

/**
 * How many crash reports a container keeps, per application, per family.
 *
 * One rather than zero: the newest dump is the one worth reading when something
 * has just gone wrong. Beyond that they are only ever storage.
 *
 * **It was two, for the comparison a second dump allows, and one Chromium dump
 * is why it is not.** Measured on device: a single Electron crash left a 117 MB
 * `.dmp` -- Chromium dumps carry the whole renderer heap, so the second copy is
 * not a rounding error on a phone, it is another tenth of a gigabyte held
 * against a comparison nobody had asked for. A crash that needs two dumps can
 * be reproduced; a device out of storage cannot run anything at all.
 */
internal const val KEEP_CRASH_REPORTS: Int = 1

/**
 * Directories whose *entire purpose* is holding crash artifacts.
 *
 * Matched by name, case-insensitively, anywhere under `drive_c` within
 * [MAX_DEPTH]. Matching on the directory rather than on a file extension is what
 * makes this safe to run unattended: a `.dmp` can be anything a user saved, but
 * a file inside a directory called `minidumps` is a minidump, because that is
 * the only thing the program that made the directory puts there.
 *
 *  - `Crashpad` — Chromium, Electron, and everything embedding CEF. Reports live
 *    in `reports/`, with a parallel `attachments/<id>/` per report.
 *  - `CrashDumps` — Windows Error Reporting's own directory, under
 *    `AppData/Local`. Flat, one `.dmp` per crash.
 *  - `minidumps` — Gecko, so Firefox and Pale Moon. Flat, `.dmp` plus a
 *    matching `.extra`.
 *  - `Crash Reports` — Gecko's outer directory, holding `pending/` and
 *    `submitted/`.
 */
private val DEBRIS_DIRECTORIES = listOf("Crashpad", "CrashDumps", "minidumps", "Crash Reports")

/**
 * How deep under `drive_c` the walk will look for one of [DEBRIS_DIRECTORIES].
 *
 * `users/<name>/AppData/Roaming/<app>/Crashpad` is six, and the Gecko one is
 * seven with `Crash Reports/pending`. Ten leaves room without turning this into
 * a full-tree walk of a prefix that may hold an entire game install.
 */
private const val MAX_DEPTH: Int = 10

/**
 * Delete stale crash artifacts from a prefix, newest [keep] retained per
 * directory, and answer how many bytes that freed.
 *
 * **This is the largest single thing a container accumulates.** A Crashpad
 * minidump from an Electron application is a *full memory* dump: one measured
 * here was 385 MB, for one crash, of one renderer. Nothing in the guest ever
 * removes them — Crashpad's retention applies to reports it has uploaded, and
 * nothing here uploads, so every crash is kept forever. A device that had
 * crashed an Electron app repeatedly was holding tens of gigabytes of them, and
 * that is the whole reason this exists.
 *
 * ## What it will touch, and what it will not
 *
 * Only files inside a directory named in [DEBRIS_DIRECTORIES]. Everything else
 * in the prefix — settings, profiles, extensions, saves, installed programs,
 * anything a user wrote or downloaded — is out of scope by construction,
 * because the walk never deletes anything outside one of those directories.
 *
 * ## Why the walk refuses links
 *
 * A Wine prefix is full of symlinks: `dosdevices` points drives at real storage,
 * and the shell folders under `users/<name>` are routinely linked out to
 * `/sdcard`. `File.listFiles()` on a link to a directory returns the *target's*
 * children, so a walk that does not check would follow `Documents` into the
 * user's actual documents and start deleting whatever matched. That bug has
 * already happened once on this project — `deleteRecursively` in container
 * deletion, reported as the user's downloaded games being deleted twice — and
 * it is not going to happen again through a disk-reclaim helper. Every
 * directory this walk steps into is checked first, and every file it deletes is
 * checked again.
 */
internal fun sweepGuestDebris(prefix: File, keep: Int = KEEP_CRASH_REPORTS): Long {
    val root = prefix.resolve("drive_c").realDirectory() ?: return 0L
    var freed = 0L
    val queue = ArrayDeque(listOf(root to 0))
    while (queue.isNotEmpty()) {
        val (directory, depth) = queue.removeFirst()
        if (depth >= MAX_DEPTH) continue
        for (child in directory.realDirectories()) {
            if (DEBRIS_DIRECTORIES.any { it.equals(child.name, ignoreCase = true) }) {
                // Not descended into afterwards: everything inside belongs to
                // this family, and the sweep below owns all of it.
                freed += child.sweep(keep)
            } else {
                queue += child to depth + 1
            }
        }
    }
    return freed
}

/**
 * Keep the newest [keep] reports here and remove the rest.
 *
 * Handles both shapes with one rule. Crashpad nests its dumps in `reports/` and
 * its per-report extras in `attachments/<id>/`; Gecko and WER put dumps directly
 * in the directory, with sidecars beside them. So the dumps are whichever
 * regular files sit in this directory or in a `reports`/`pending`/`submitted`
 * child, and anything sharing a dump's stem goes when the dump goes.
 */
private fun File.sweep(keep: Int): Long {
    val holders = listOfNotNull(
        realDirectory(),
        resolve("reports").realDirectory(),
        resolve("pending").realDirectory(),
        resolve("submitted").realDirectory(),
    )
    val attachments = resolve("attachments").realDirectory()

    var freed = 0L
    for (holder in holders) {
        val files = holder.realFiles()
        // Group by stem so a `.dmp` and its `.extra`/`.meta` count as one report
        // and are retained or removed together. Ordered by the newest file in
        // each group, because a sidecar is often written a moment after its dump.
        // `substringBeforeLast`, not `substringBefore`: Windows Error Reporting
        // names a dump `<exe>.<pid>.dmp`, so splitting on the first dot groups
        // every crash of one program together and the retention keeps all of
        // them. The last dot is the extension in all three families, which is
        // what makes `abc.dmp` and `abc.extra` one report and `app.exe.1.dmp`
        // and `app.exe.2.dmp` two.
        val reports = files.groupBy { it.name.substringBeforeLast('.') }
            .toList()
            .sortedByDescending { (_, group) -> group.maxOf { it.lastModified() } }
        for ((stem, group) in reports.drop(keep)) {
            group.forEach { freed += it.deleteCounting() }
            // Only an exact name match: a prefix match would take a sibling
            // whose id merely started with the same characters.
            attachments?.realDirectories()
                ?.firstOrNull { it.name == stem }
                ?.let { freed += it.deleteTreeCounting() }
        }
    }
    return freed
}

// — link-refusing filesystem helpers ------------------------------------------
//
// `Files.isSymbolicLink` rather than a canonical-path comparison: the latter
// also resolves links in the *parents*, so a prefix reached through one would
// make every child look like a link and this would silently do nothing.

private fun File.isLink(): Boolean = Files.isSymbolicLink(toPath())

private fun File.realDirectory(): File? = takeIf { it.isDirectory && !it.isLink() }

private fun File.realDirectories(): List<File> =
    if (isLink()) emptyList() else listFiles().orEmpty().filter { it.isDirectory && !it.isLink() }

private fun File.realFiles(): List<File> =
    if (isLink()) emptyList() else listFiles().orEmpty().filter { it.isFile && !it.isLink() }

private fun File.deleteCounting(): Long {
    if (isLink() || !isFile) return 0L
    val size = length()
    return if (delete()) size else 0L
}

private fun File.deleteTreeCounting(): Long {
    if (isLink() || !isDirectory) return 0L
    var freed = 0L
    for (child in listFiles().orEmpty()) {
        freed += if (child.isDirectory && !child.isLink()) child.deleteTreeCounting()
        else child.deleteCounting()
    }
    delete()
    return freed
}
