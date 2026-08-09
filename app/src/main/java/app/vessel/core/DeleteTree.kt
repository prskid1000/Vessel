package app.vessel.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Delete a directory tree **without ever following a symlink out of it**.
 *
 * ## Why this exists, and it is the most serious defect this project has had
 *
 * `kotlin.io.File.deleteRecursively()` is implemented as `walkBottomUp().all {
 * it.delete() }`, and `FileTreeWalk` enumerates children with
 * `File.listFiles()`. For a symlink that points at a directory, `listFiles()`
 * returns **the target's** children. So the walk goes straight through the link
 * and deletes what is on the other side.
 *
 * A Vessel container has `prefix/dosdevices/d:` → `/storage/emulated/0`, and
 * `e:`, `f:` → whatever folders the user mapped. Deleting a container therefore
 * deleted **the contents of the user's shared storage and of every mapped
 * folder**, then removed the now-empty links and reported success. It was
 * reported as "my downloaded games got deleted twice", and that is exactly what
 * happened.
 *
 * `docs/DRIVE-MAPPING.md` already carried the rule this broke — *"deletes the
 * link, never the target"* — and [DriveMap.unmap] honours it, because
 * `File.delete()` on a symlink unlinks it. The rule was written for the one
 * function that removes a mapping and not for the one that removes everything.
 *
 * ## What this does instead
 *
 * `Files.walkFileTree` **does not follow links by default** — `FOLLOW_LINKS` is
 * opt-in, and it is not passed here. A symlink is therefore visited as a *file*
 * and `Files.delete` unlinks it, which is the whole of the correct behaviour. A
 * real directory is visited on the way out and removed once it is empty.
 *
 * Returns true when nothing is left at [root]. A missing [root] is success:
 * every caller wants it gone, and it is.
 *
 * Failures do not throw. A tree that is partly undeletable — an open file, a
 * revoked permission — leaves what it cannot remove and answers false, which is
 * what the callers already expected from `deleteRecursively()`.
 */
fun deleteTree(root: File): Boolean {
    if (!root.exists() && !isSymlink(root)) return true

    // The root itself may be a link. Unlink it and stop — walking into it is the
    // bug this function exists to prevent.
    if (isSymlink(root)) return runCatching { Files.deleteIfExists(root.toPath()) }.getOrDefault(false)

    return runCatching {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                // Every symlink lands here rather than in preVisitDirectory,
                // because the walk is not following links: a link to a directory
                // is "a file whose attributes could not be read as a directory".
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                // Reached when a directory cannot be opened or read. Keep going
                // and let the emptiness check below decide the outcome, rather
                // than abandoning the rest of a delete the caller asked for.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        !root.exists()
    }.getOrDefault(false)
}

/**
 * Whether [file] is a symlink, by the same test [DriveMap] uses.
 *
 * `File.exists()` follows links, so a link whose target is gone answers false to
 * it and still has to be unlinked — which is why [deleteTree] asks this first.
 */
private fun isSymlink(file: File): Boolean =
    runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
