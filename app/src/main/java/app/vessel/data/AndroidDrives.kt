package app.vessel.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import android.util.Log
import app.vessel.core.DriveMap
import app.vessel.core.GuestDrive
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's storage, as drives inside a container.
 *
 * [DriveMap] is the mechanism and knows nothing about Android; this is the part
 * that knows what a phone has and what this app is allowed to read. The split
 * is what keeps the letter allocation and the symlink rules unit-testable on a
 * laptop, which matters because the one mistake this feature must not make —
 * deleting a user's folder instead of a link — is a rule, not a permission.
 */
@Singleton
class AndroidDrives @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Whether this build can map a folder at all.
     *
     * `MANAGE_EXTERNAL_STORAGE` is a settings toggle rather than a runtime
     * dialog, and only the sideload flavour declares it — see
     * `src/sideload/AndroidManifest.xml`. False is a legitimate steady state,
     * not an error: a `play` build simply has no mappable storage, and the UI
     * says so rather than offering a picker that cannot work.
     */
    val canMap: Boolean get() = Environment.isExternalStorageManager()

    /** Every drive the prefix has. Straight through; the reader is the truth. */
    fun drives(prefix: File): List<GuestDrive> = DriveMap.drives(prefix)

    /**
     * Put the user-visible part of the phone on `D:`, if we may.
     *
     * Called on every provision rather than once, so a container created before
     * the permission was granted picks the drive up on its next launch instead
     * of having to be recreated. Idempotent: mapping the same target to the same
     * letter rewrites a symlink to the value it already had.
     *
     * `D:` specifically and not the next free letter. This is the drive every
     * user will type and half of them will have seen on a real machine, and a
     * mapping that lands on `D:` for one container and `F:` for another is a
     * mapping nobody can write a shortcut against.
     */
    fun mapSharedStorage(prefix: File): Boolean {
        if (!canMap) return false
        val shared = Environment.getExternalStorageDirectory()
        if (shared == null || !shared.isDirectory) return false
        val mapped = DriveMap.map(prefix, SHARED_STORAGE_DRIVE, shared)
        if (!mapped) Log.w(TAG, "could not map ${shared.path} to D:")
        return mapped
    }

    /**
     * Map [folder] to the first free letter, and say which one it got.
     *
     * Null when every assignable letter is taken or the link could not be
     * created. The caller reports it; there is no retry that would help, since
     * both causes are facts about the device rather than transient.
     */
    fun mapFolder(prefix: File, folder: File): Char? {
        if (!canMap || !folder.isDirectory) return null
        val taken = DriveMap.drives(prefix).map { it.letter }
        val letter = DriveMap.nextFreeLetter(taken) ?: return null
        return if (DriveMap.map(prefix, letter, folder)) letter else null
    }

    /**
     * Folders the `+` sheet offers: the top level of shared storage.
     *
     * A list rather than Android's folder picker, and that is not a shortcut.
     * `ACTION_OPEN_DOCUMENT_TREE` hands back a `content://` tree, which is not
     * a path — there is nothing to symlink and Wine cannot open it. With
     * `MANAGE_EXTERNAL_STORAGE` this build has real paths, so offering the real
     * folders is both simpler and the only form that can work.
     */
    fun mappableFolders(): List<File> {
        if (!canMap) return emptyList()
        val shared = Environment.getExternalStorageDirectory() ?: return emptyList()
        return shared.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * The folder a picked tree URI names, or null when it has no path.
     *
     * **A `content://` tree is not a path and cannot be symlinked — but its
     * document id is one.** `getTreeDocumentId` gives `primary:Games`, whose
     * left half names a storage volume and whose right half is the path inside
     * it. That is enough to rebuild the real directory, and with
     * `MANAGE_EXTERNAL_STORAGE` this build may then read it. An earlier note in
     * `docs/DRIVE-MAPPING.md` said SAF could not work here; that was true of the
     * tree itself and too absolute about the id.
     *
     * Null for a volume this cannot resolve — a network provider or a cloud
     * document has no path at all, and inventing one would produce a drive that
     * silently lists nothing.
     */
    fun folderFor(tree: Uri): File? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = when {
            volume.equals(PRIMARY_VOLUME, ignoreCase = true) ->
                Environment.getExternalStorageDirectory()
            // A removable card or a USB drive: /storage/<uuid>, which is where
            // Android mounts them and what the volume half of the id names.
            volume.isNotEmpty() -> File(EXTERNAL_MOUNTS, volume)
            else -> null
        } ?: return null
        val folder = if (relative.isEmpty()) root else File(root, relative)
        return folder.takeIf { it.isDirectory }
    }

    /** Remove a mapping. Never touches what it pointed at — see [DriveMap.unmap]. */
    fun unmap(prefix: File, letter: Char): Boolean = DriveMap.unmap(prefix, letter)

    private companion object {
        const val TAG = "VesselDrives"

        /** What a second drive is called on every Windows machine anyone has used. */
        const val SHARED_STORAGE_DRIVE = 'd'

        /** The volume half of a tree document id for built-in storage. */
        const val PRIMARY_VOLUME = "primary"

        /** Where Android mounts a card or a USB drive, by volume uuid. */
        const val EXTERNAL_MOUNTS = "/storage"
    }
}
