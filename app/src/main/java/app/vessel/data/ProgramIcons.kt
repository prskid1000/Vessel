package app.vessel.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.vessel.core.DriveMap
import app.vessel.core.PeIcon
import app.vessel.core.PeIconReader
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A program's own icon, decoded once and remembered.
 *
 * [PeIconReader] does the hard half — walking a PE's resource directory to an
 * `RT_GROUP_ICON` and decoding the DIB underneath it. This is the part that
 * knows where a shortcut's file actually is on Android, keeps the answer, and
 * keeps it off the composition thread.
 *
 * **Caching negatives matters as much as caching hits.** A `.bat`, a `.msi` and
 * a program that simply has no icon all return null, and null costs the same
 * resource walk as a hit. Without remembering them, every recomposition of a
 * grid of scripts re-reads every file — so the cache stores the absence too.
 */
@Singleton
class ProgramIcons @Inject constructor(
    private val paths: ContainerPaths,
) {

    /**
     * Keyed by path *and* modification time, so replacing an executable shows
     * its new icon without anything having to invalidate this.
     *
     * Sized in bytes rather than entries: a 128 px icon is 64 KB and a 16 px one
     * is a kilobyte, and an entry count would either waste memory on the small
     * ones or evict too eagerly on the large.
     */
    private val cache = object : LruCache<String, Holder>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Holder): Int =
            value.bitmap?.byteCount ?: EMPTY_ENTRY_BYTES
    }

    /** A cached answer, which may legitimately be "this file has no icon". */
    private class Holder(val bitmap: Bitmap?)

    /**
     * One decode at a time.
     *
     * Not for the cache — [LruCache] is synchronised — but for the work: a grid
     * of twelve tiles composes at once, and twelve concurrent PE walks on a
     * phone is a stutter for no gain over doing them in turn. It also means two
     * tiles for the same program cannot both decode it.
     */
    private val gate = Mutex()

    /**
     * [shortcut]'s icon, or null when it has none and when it cannot be read.
     *
     * Null is a real answer and the caller's fallback — the lettered placeholder
     * — is the right thing to draw for it. A half-copied file, a script, and a
     * program whose icon is in a format the reader will not guess at are all
     * null and none of them is an error.
     */
    suspend fun iconFor(shortcut: AppShortcut): Bitmap? =
        fileFor(shortcut)?.let { iconOf(it) }

    /** The cached decode of one file. Both callers land here. */
    private suspend fun iconOf(file: File): Bitmap? {
        val key = "${file.path}@${file.lastModified()}"
        cache.get(key)?.let { return it.bitmap }

        return withContext(Dispatchers.IO) {
            gate.withLock {
                // Checked again inside the lock: while this call was waiting,
                // the tile beside it may have decoded the same program.
                cache.get(key)?.let { return@withLock it.bitmap }
                val bitmap = runCatching { decode(file) }.getOrNull()
                cache.put(key, Holder(bitmap))
                bitmap
            }
        }
    }

    private fun decode(file: File): Bitmap? =
        when (val icon = PeIconReader.iconOf(file, maxSize = MAX_ICON_PX)) {
            // Straight, non-premultiplied ARGB, top row first — which is exactly
            // what this overload takes, so there is no conversion to get wrong.
            is PeIcon.Pixels -> Bitmap.createBitmap(
                icon.argb,
                icon.width,
                icon.height,
                Bitmap.Config.ARGB_8888,
            )

            // The 256×256 Vista-and-later entry, handed to the platform decoder
            // rather than unpacked here. Uncommon: the reader prefers a Pixels
            // entry whenever one exists at a usable size.
            is PeIcon.Png -> BitmapFactory.decodeByteArray(icon.bytes, 0, icon.bytes.size)

            null -> null
        }

    /**
     * The Android file behind a guest path.
     *
     * The drive letter chooses the root — `D:\Games\x.exe` is under
     * `dosdevices/d:` and not under `drive_c` — and [GuestPath.resolve] refuses
     * anything that climbs out of it. A shortcut on a drive that has since been
     * unmapped resolves to a path that does not exist, which reads as null and
     * leaves the letter drawn.
     */
    private fun fileFor(shortcut: AppShortcut): File? =
        paths.of(shortcut.containerId)
            .resolveGuestPath(shortcut.executable)
            ?.takeIf { it.isFile }

    /**
     * The icon of a program Wine itself provides, by bare file name.
     *
     * The start menu's built-in row — cmd, regedit, explorer, notepad, winecfg —
     * had hand-picked glyphs standing in for icons, and the stand-ins were the
     * giveaway: a bulleted-list glyph for the *registry editor*, because there
     * is no obvious mark for one. These programs are in the prefix and carry
     * their own icons, so there is nothing to choose.
     *
     * By name and not by path, because that is what a `TerminalProfile` knows,
     * and Wine puts these in two places: the shell programs in `windows`, the
     * command-line ones in `windows\system32`. Looked up in that order, which is
     * the order Windows itself would find them for a bare name.
     *
     * **A profile with a real path resolves it instead.** Every entry on the
     * menu was a bare name until Git arrived, and Git is not in `windows` — so
     * the row that most needed an icon to tell it apart was the one that drew a
     * letter. A `\` in the string is the whole test: it is a guest path, so it
     * is resolved against the drive it names, exactly as a shortcut's is.
     */
    suspend fun iconForBuiltIn(containerId: String, program: String): Bitmap? {
        val layout = paths.of(containerId)
        val file = if (program.contains('\\')) {
            layout.resolveGuestPath(program)?.takeIf { it.isFile } ?: return null
        } else {
            val windows = File(layout.prefix, DRIVE_C_WINDOWS)
            sequenceOf(File(windows, program), File(windows, "system32/$program"))
                .firstOrNull { it.isFile }
                ?: return null
        }
        return iconOf(file)
    }

    private companion object {
        /**
         * A 44 dp tile on this phone is about 110 px, so 128 looks identical to
         * 256 and costs a quarter of the memory. The reader's own default is
         * 256, which is the largest an ICO can describe.
         */
        const val MAX_ICON_PX = 128

        /** Where Wine puts the programs it provides, under a prefix. */
        const val DRIVE_C_WINDOWS = "drive_c/windows"

        /** Four megabytes — about sixty 128 px icons, far more than a device has. */
        const val CACHE_BYTES = 4 * 1024 * 1024

        /**
         * What a remembered "no icon" costs the budget.
         *
         * Not zero: [LruCache] treats a zero-sized entry as free and would keep
         * every one of them for ever, so a container full of scripts would grow
         * the map without bound. A nominal kilobyte makes them evictable.
         */
        const val EMPTY_ENTRY_BYTES = 1024
    }
}
