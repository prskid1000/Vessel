package app.vessel.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import android.os.storage.StorageManager
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
/**
 * The one thing [ContainerProvisioner] needs from the Android side.
 *
 * A seam rather than the whole class, and for a testable reason: mapping
 * shared storage is the provisioner's only interest in Android, while
 * [AndroidDrives] needs a `Context` and `Environment`, neither of which exists
 * in a JVM unit test. The provisioner's tests build a real provisioner and
 * assert on where components land; they should not need a device to do it.
 */
fun interface PrefixDrives {
    /** Put the phone's storage on `D:` in [prefix], if this build may. */
    fun mapSharedStorage(prefix: File): Boolean
}

@Singleton
class AndroidDrives @Inject constructor(
    @ApplicationContext private val context: Context,
) : PrefixDrives {

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

    /**
     * Every drive the prefix has, with removable volumes named the way Android
     * names them.
     *
     * [DriveMap.labelFor] can only see the path, and a removable volume's path
     * ends in its uuid — so an HDD the user calls "HDD" came out as
     * `B210-B412`. Android knows the real name and this is the only layer that
     * can ask it. Volume roots only: a folder *inside* a volume is named after
     * itself, which is what the user picked and what they will recognise.
     *
     * Primary storage is deliberately left alone. Its description is "Internal
     * shared storage", and [DriveMap.PHONE_STORAGE]'s "Phone" is both shorter
     * and what a drive tab has room for.
     */
    fun drives(prefix: File): List<GuestDrive> {
        val named = volumeNames()
        return DriveMap.drives(prefix).map { drive ->
            named[drive.target]?.let { drive.copy(label = it) } ?: drive
        }
    }

    /** Removable volume root path to the name Android shows for it. */
    private fun volumeNames(): Map<String, String> {
        val storage = context.getSystemService(StorageManager::class.java) ?: return emptyMap()
        return runCatching {
            storage.storageVolumes
                .filterNot { it.isPrimary }
                .mapNotNull { volume ->
                    val path = volume.directory?.canonicalPath ?: return@mapNotNull null
                    val name = volume.getDescription(context)?.trim().orEmpty()
                    if (name.isEmpty()) null else path to name
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

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
    override fun mapSharedStorage(prefix: File): Boolean {
        if (!canMap) return false
        val shared = Environment.getExternalStorageDirectory()
        if (shared == null || !shared.isDirectory) return false
        val mapped = DriveMap.map(prefix, DriveMap.SHARED_STORAGE_DRIVE, shared)
        if (!mapped) Log.w(TAG, "could not map ${shared.path} to D:")
        return mapped
    }

    /**
     * Map [folder] to the first free letter, and say which one it got.
     *
     * Null when every assignable letter is taken or the link could not be
     * created. The caller reports it; there is no retry that would help, since
     * both causes are facts about the device rather than transient.
     *
     * **This writes a symlink and nothing else, deliberately.** Wine also wants
     * the drive declared in `HKLM\Software\Wine\Drives`, and an earlier note
     * here said this function would have to write that too. It does not:
     * `PrefixRegistry.driveTypes` reads `dosdevices` and declares whatever it
     * finds, so the entry appears on the container's next launch without this
     * code knowing the registry exists. The cost is that a drive mapped during
     * a running session is not visible to Wine until relaunch — which is true
     * regardless, since Wine builds its drive table when a process starts.
     */
    fun mapFolder(prefix: File, folder: File): Char? {
        if (!canMap || !folder.isDirectory) return null
        val taken = DriveMap.drives(prefix).map { it.letter }
        val letter = DriveMap.nextFreeLetter(taken) ?: return null
        return if (DriveMap.map(prefix, letter, folder)) letter else null
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
            //
            // **Not `/mnt/media_rw/<uuid>`, and this is the one worth knowing.**
            // That path is the same volume, owned by the `media_rw` group, and
            // an app cannot read it even holding `MANAGE_EXTERNAL_STORAGE` —
            // which produces a drive that maps, lists in the UI, and is empty
            // everywhere. Winlator-Ludashi#534 is the same bug and the same
            // conclusion: prefer `/storage`. Where that PR falls back to
            // `/mnt/media_rw` this returns null, because a fallback to an
            // unreadable path is the empty drive rather than a fix for it.
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

        /** The volume half of a tree document id for built-in storage. */
        const val PRIMARY_VOLUME = "primary"

        /** Where Android mounts a card or a USB drive, by volume uuid. */
        const val EXTERNAL_MOUNTS = "/storage"
    }
}
