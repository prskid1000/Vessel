package app.vessel.data

import app.vessel.core.ComponentType
import app.vessel.core.WcpProfile
import app.vessel.core.deleteTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the store remembers about an installed version that the package itself
 * does not say.
 *
 * Exactly one field, and it exists because there is nothing in `profile.json` to
 * derive it from: `build/gen_registry.py` sets a package's id from the archive's
 * filename (`wcp.stem`), so `dxvk-2.7.1-canoe` is knowable only from whoever
 * handed the archive over. Without it the Components screen and any pinned
 * `component` selector would be talking about different names for the same
 * build.
 *
 * Kept *beside* the version directory rather than inside it, so a listing of
 * `components/<Type>/<versionCode>/` is a listing of the package and nothing
 * this app added.
 */
@Serializable
data class ComponentRecord(val packageId: String)

/**
 * What happened when a `.wcp` was installed into the shared store.
 *
 * A sealed result rather than an exception, because every one of these is a
 * thing the Preparing checklist has to *say* — DESIGN.md's fourth session state
 * exists precisely so a failure is attributable to a step rather than to "it
 * didn't work", and that needs the reason as data, not as a stack trace.
 */
sealed interface WcpInstallResult {

    /** One line, addressed to whoever is looking at the failed step. */
    val summary: String

    data class Installed(
        val profile: WcpProfile,
        val type: ComponentType,
        val directory: File,
        val fileCount: Int,
        val compression: WcpCompression,
        /** False when no `.sha256` sidecar existed and no digest was supplied. */
        val checksumVerified: Boolean,
        /**
         * True when this version was already in the store and nothing was
         * extracted.
         *
         * The store is keyed by type *and* `versionCode`, so the same version is
         * the same bytes — a second container asking for the Wine that is
         * already there gets it for free rather than re-extracting 912 MB.
         */
        val reused: Boolean = false,
    ) : WcpInstallResult {
        override val summary: String
            get() = if (reused) {
                "${profile.versionName} — already in the shared store ($fileCount file(s))"
            } else {
                "${profile.versionName} — $fileCount file(s)" +
                    if (checksumVerified) "" else ", checksum not verified (no sidecar)"
            }
    }

    /** Every failure. Nothing is written to the component directory when one occurs. */
    sealed interface Failure : WcpInstallResult

    data class NotFound(val archive: File) : Failure {
        override val summary get() = "Package file is missing: ${archive.name}"
    }

    /**
     * The archive uses a codec this app has no decoder for — in practice zstd,
     * which nothing we publish has used since packaging moved to xz, but which
     * an older package still on a device will be. See [WcpCompression].
     */
    data class UnsupportedCompression(val compression: WcpCompression) : Failure {
        override val summary
            get() = "Cannot read a ${compression.label} package: ${compression.requirement}"
    }

    data class ChecksumMismatch(val expected: String, val actual: String) : Failure {
        override val summary
            get() = "Checksum does not match: expected ${expected.take(16)}…, " +
                "got ${actual.take(16)}…"
    }

    /**
     * An archive entry tried to write outside the destination.
     *
     * A `.wcp` is downloaded content. This is refused loudly rather than
     * sanitised quietly: an archive containing `../` is not a package with a
     * typo in it, and installing the rest of it would be installing something
     * whose producer is not behaving.
     */
    data class UnsafeEntry(val entryName: String, val why: String) : Failure {
        override val summary get() = "Refusing unsafe archive entry '$entryName': $why"
    }

    /**
     * There is not enough room for the unpacked payload.
     *
     * Reported before anything is extracted wherever possible. Wine is 63 MB
     * compressed and 912 MB unpacked, and failing nine tenths of the way through
     * that with a bare `IOException` tells the user nothing they can act on.
     */
    data class InsufficientSpace(
        val requiredBytes: Long,
        val freeBytes: Long,
        /** True when extraction had already begun, so [requiredBytes] is a floor. */
        val partway: Boolean = false,
    ) : Failure {
        override val summary
            get() = "Not enough space: needs ${if (partway) "at least " else ""}" +
                "${megabytes(requiredBytes)}, ${megabytes(freeBytes)} free"
    }

    data class UnknownType(val wire: String) : Failure {
        override val summary
            get() = "Package type '$wire' is not one this app can load"
    }

    data class Malformed(val detail: String) : Failure {
        override val summary get() = "Package is not readable: $detail"
    }
}

private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

/**
 * How far through one package's extraction the installer is.
 *
 * **The fraction is over the compressed bytes, and that is the only honest one
 * available.** `profile.json` lists the payload's file names and not their
 * sizes, so the unpacked total is unknown until the archive has been read to the
 * end — a bar over "files done" would be a bar whose denominator is a guess, and
 * one over unpacked bytes would need a denominator that does not exist. The
 * compressed length is exact and known before anything starts.
 *
 * [unpackedBytes] and [files] are reported anyway, because they are what a
 * person watching wants to see: "412 MB unpacked" is the sentence that says the
 * phone is doing something, and the bar is the one that says how much is left.
 */
data class WcpProgress(
    val name: String,
    val compressedRead: Long,
    /** The archive's own length, or a negative number when the source cannot say. */
    val compressedTotal: Long,
    val unpackedBytes: Long,
    val files: Int,
) {
    /** 0f..1f, or null when [compressedTotal] is unknown and a bar would be a lie. */
    val fraction: Float?
        get() = if (compressedTotal <= 0) null else (compressedRead.toFloat() / compressedTotal).coerceIn(0f, 1f)
}

/**
 * How much room is left where a component is being unpacked.
 *
 * An interface only so a test can say "400 MB". There is no way to make a real
 * filesystem small, and the refusal is the whole point of the check.
 */
fun interface FreeSpace {
    fun usableBytes(directory: File): Long

    companion object {
        /**
         * The real answer. Walks up to the nearest directory that exists,
         * because the store's own directories may not have been created yet and
         * `getUsableSpace` on a path that is not there returns zero — which
         * would refuse every first install on the device.
         */
        val Filesystem: FreeSpace = FreeSpace { directory ->
            var candidate: File? = directory.absoluteFile
            while (candidate != null && !candidate.exists()) candidate = candidate.parentFile
            candidate?.usableSpace ?: 0L
        }
    }
}

/**
 * Installs a `.wcp` into the shared component store.
 *
 * The order is the whole point, and it is: identify the codec, **verify the
 * digest**, read `profile.json`, check there is room, extract to a staging
 * directory on the same filesystem, and only then swap it into place. Nothing
 * touches the live component directory until an archive has proved it is the one
 * that was published and that every entry in it stays inside its destination.
 *
 * **Install once, keyed by type and `versionCode`.** The destination is
 * `components/<Type>/<versionCode>/`, shared by every container. Installing a
 * version that is already there extracts nothing and reports success; installing
 * a *different* version of the same type puts it alongside, so two containers on
 * two Wine builds both work.
 *
 * Three passes over the file: hash, `profile.json`, extract. The hash pass reads
 * compressed bytes and the profile pass stops at the first entry, so it is two
 * decompressions in practice, on a local file, once per component per *device*
 * rather than per container.
 */
@Singleton
class WcpInstaller @Inject constructor(
    private val json: Json,
    private val space: FreeSpace = FreeSpace.Filesystem,
) {

    suspend fun install(
        archive: File,
        into: ComponentStoreLayout,
        /** Overrides the sidecar. Supply the digest the registry published. */
        expectedSha256: String? = null,
        /** The registry's id for this build. Recorded beside the payload. */
        packageId: String? = null,
    ): WcpInstallResult = install(FileWcpSource(archive), into, expectedSha256, packageId)

    suspend fun install(
        source: WcpSource,
        into: ComponentStoreLayout,
        expectedSha256: String? = null,
        packageId: String? = null,
        /**
         * Called as the archive is consumed, on the calling coroutine's IO thread.
         *
         * Throttled by [PROGRESS_INTERVAL_BYTES] rather than per entry: the Wine
         * package holds around four thousand files and a callback per file is a
         * state update per file, which is a recomposition per file.
         */
        onProgress: ((WcpProgress) -> Unit)? = null,
    ): WcpInstallResult = withContext(Dispatchers.IO) {
        installBlocking(source, into, expectedSha256, packageId, onProgress)
    }

    /** The whole of [install], synchronously. Separated so tests need no dispatcher. */
    internal fun installBlocking(
        archive: File,
        into: ComponentStoreLayout,
        expectedSha256: String? = null,
        packageId: String? = null,
    ): WcpInstallResult =
        if (!archive.isFile) {
            WcpInstallResult.NotFound(archive)
        } else {
            installBlocking(FileWcpSource(archive), into, expectedSha256, packageId, null)
        }

    internal fun installBlocking(
        source: WcpSource,
        into: ComponentStoreLayout,
        expectedSha256: String?,
        packageId: String?,
        onProgress: ((WcpProgress) -> Unit)?,
    ): WcpInstallResult {
        val compression = WcpArchive.detect(source)
        if (!compression.decodable) {
            return WcpInstallResult.UnsupportedCompression(compression)
        }

        val expected = (expectedSha256 ?: source.publishedSha256())?.lowercase()
        if (expected != null) {
            val actual = sha256(source)
            if (!actual.equals(expected, ignoreCase = true)) {
                return WcpInstallResult.ChecksumMismatch(expected, actual)
            }
        }

        val profileBytes = runCatching { readProfileBytes(source, compression) }
            .getOrElse { return WcpInstallResult.Malformed(it.message ?: "unreadable archive") }
            ?: return WcpInstallResult.Malformed("no $WCP_PROFILE at the root of the archive")

        val profile = runCatching {
            json.decodeFromString(WcpProfile.serializer(), profileBytes.toString(Charsets.UTF_8))
        }.getOrElse { return WcpInstallResult.Malformed("$WCP_PROFILE is not valid JSON") }

        val type = profile.componentType
            ?: return WcpInstallResult.UnknownType(profile.type)

        val destination = into.version(type, profile.versionCode)

        // Already there. Same type and same versionCode is the same build, so
        // there is nothing to do but say so — and record the package id, which a
        // migrated install or an earlier caller may not have supplied.
        if (into.isInstalled(type, profile.versionCode)) {
            writeRecord(into, type, profile.versionCode, packageId)
            return WcpInstallResult.Installed(
                profile = profile,
                type = type,
                directory = destination,
                fileCount = countFiles(destination),
                compression = compression,
                checksumVerified = expected != null,
                reused = true,
            )
        }

        if (!into.createDirectories()) {
            return WcpInstallResult.Malformed("could not create ${into.root}")
        }

        estimateSpace(source, into.root)?.let { return it }

        val staging = File(into.staging, "install-${type.wire}-${System.nanoTime()}")
        try {
            if (!staging.mkdirs()) {
                return WcpInstallResult.Malformed("could not create staging directory")
            }
            val extracted = when (
                val outcome = extract(source, compression, staging, onProgress)
            ) {
                is ExtractOutcome.Refused -> return outcome.failure
                is ExtractOutcome.Ok -> outcome.fileCount
            }

            File(staging, WCP_PROFILE).writeBytes(profileBytes)

            if (!swapIntoPlace(staging, destination)) {
                return WcpInstallResult.Malformed("could not replace ${destination.name}")
            }
            writeRecord(into, type, profile.versionCode, packageId)

            return WcpInstallResult.Installed(
                profile = profile,
                type = type,
                directory = destination,
                fileCount = extracted,
                compression = compression,
                checksumVerified = expected != null,
            )
        } catch (e: IOException) {
            return WcpInstallResult.Malformed(e.message ?: "I/O error while extracting")
        } finally {
            deleteTree(staging)
        }
    }

    /**
     * Refuse, before extracting anything, when the payload plainly will not fit.
     *
     * `profile.json` lists the files but not their sizes, so the only figure
     * available up front is the archive's own. The multiplier is taken from the
     * measured set — Wine expands 63.1 MB to 912 MB, which is 14.5× and the
     * worst of the six — plus headroom so the device is not left with nothing.
     *
     * Deliberately an estimate and deliberately not the only check: [extract]
     * watches the real figure as it goes, because an estimate that is too low is
     * exactly the case this is here to make legible.
     */
    private fun estimateSpace(source: WcpSource, root: File): WcpInstallResult.InsufficientSpace? {
        // A source that cannot state its own size is not guessed at: extraction's
        // own running check is the one that cannot be fooled, and inventing a
        // figure here would produce a refusal message with a made-up number in it.
        if (source.sizeBytes <= 0) return null
        val required = source.sizeBytes * EXPANSION_ESTIMATE + HEADROOM_BYTES
        val free = space.usableBytes(root)
        return if (free < required) {
            WcpInstallResult.InsufficientSpace(requiredBytes = required, freeBytes = free)
        } else {
            null
        }
    }

    /** How [extract] ended: a file count, or the failure that stopped it. */
    private sealed interface ExtractOutcome {
        data class Ok(val fileCount: Int) : ExtractOutcome
        data class Refused(val failure: WcpInstallResult.Failure) : ExtractOutcome
    }

    /** A symlink whose target has been checked, waiting for that target to exist. */
    private data class PendingLink(val link: File, val target: String, val resolved: File)

    /**
     * Extract every payload entry into [destination].
     *
     * The first unsafe entry aborts the whole extraction. [destination] is a
     * staging directory the caller throws away on any failure, so a partial
     * extraction never reaches the live component directory.
     *
     * Symlinks are created **last**, after every regular file is in place: a
     * relative link written before its target would dangle, and a dangling link
     * is indistinguishable from a broken install by anything that later looks at
     * it.
     */
    private fun extract(
        source: WcpSource,
        compression: WcpCompression,
        destination: File,
        onProgress: ((WcpProgress) -> Unit)?,
    ): ExtractOutcome {
        var files = 0
        var written = 0L
        var checkedAt = 0L
        val links = mutableListOf<PendingLink>()

        // The compressed position, updated by the counting stream underneath the
        // decompressor. `total` is the archive's own length, so the ratio is exact
        // rather than an estimate of an unpacked size nobody knows yet.
        var compressedRead = 0L
        var reportedAt = 0L
        fun report(force: Boolean) {
            if (onProgress == null) return
            if (!force && compressedRead - reportedAt < PROGRESS_INTERVAL_BYTES) return
            reportedAt = compressedRead
            onProgress(
                WcpProgress(
                    name = source.name,
                    compressedRead = compressedRead,
                    compressedTotal = source.sizeBytes,
                    unpackedBytes = written,
                    files = files,
                ),
            )
        }

        val counter: ((Long) -> Unit)? =
            if (onProgress == null) null else { at -> compressedRead = at }

        TarReader(WcpArchive.open(source, compression, counter)).use { tar ->
            while (true) {
                val entry = tar.next() ?: break
                when (entry.kind) {
                    TarEntryKind.METADATA, TarEntryKind.LONG_NAME, TarEntryKind.LONG_LINK -> continue

                    TarEntryKind.HARDLINK -> return refuse(
                        entry.name,
                        "hard links are never extracted — nothing we publish contains one",
                    )

                    TarEntryKind.SPECIAL -> return refuse(
                        entry.name,
                        "only regular files, directories and relative symlinks are extracted",
                    )

                    TarEntryKind.SYMLINK -> {
                        val link = resolveEntry(destination, entry.name)
                            ?: return refuse(entry.name, TRAVERSAL)
                        val resolved = resolveLink(destination, entry.name, entry.linkTarget)
                            ?: return refuse(entry.name, LINK_TRAVERSAL)
                        links += PendingLink(link, entry.linkTarget, resolved)
                    }

                    TarEntryKind.DIRECTORY -> {
                        val target = resolveEntry(destination, entry.name)
                            ?: return refuse(entry.name, TRAVERSAL)
                        if (!target.isDirectory && !target.mkdirs()) {
                            return ExtractOutcome.Refused(
                                WcpInstallResult.Malformed("could not create ${entry.name}"),
                            )
                        }
                    }

                    TarEntryKind.FILE -> {
                        // `profile.json` is written from the bytes already read and
                        // validated, so the copy in the archive is not extracted twice.
                        if (entry.name == WCP_PROFILE) continue
                        val target = resolveEntry(destination, entry.name)
                            ?: return refuse(entry.name, TRAVERSAL)
                        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                        target.outputStream().buffered().use { tar.copyBodyTo(it) }
                        if (entry.mode and OWNER_EXECUTE != 0) target.setExecutable(true, true)
                        files++
                        written += entry.size
                        report(force = false)
                        if (written - checkedAt >= SPACE_CHECK_INTERVAL) {
                            checkedAt = written
                            val free = space.usableBytes(destination)
                            if (free < HEADROOM_BYTES) {
                                return ExtractOutcome.Refused(
                                    WcpInstallResult.InsufficientSpace(
                                        requiredBytes = written + HEADROOM_BYTES,
                                        freeBytes = free,
                                        partway = true,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        for (link in links) {
            if (!createLink(link.link, link.target, link.resolved)) {
                return ExtractOutcome.Refused(
                    WcpInstallResult.Malformed("could not create the link ${link.link.name}"),
                )
            }
            files++
        }
        report(force = true)
        return ExtractOutcome.Ok(files)
    }

    private fun refuse(entryName: String, why: String): ExtractOutcome =
        ExtractOutcome.Refused(WcpInstallResult.UnsafeEntry(entryName, why))

    /**
     * Create one symlink, or copy its target when the filesystem cannot.
     *
     * The link is relative, exactly as the archive wrote it, because that is what
     * makes `bin/wineboot -> wine` keep working after the store directory is
     * renamed into place.
     *
     * The copy is a fallback for a filesystem with no symlink support — a JVM on
     * Windows without the create-symlink privilege, which is where these tests
     * run. It costs disk rather than correctness: Wine dispatches on `argv[0]`,
     * so a real file named `wineboot` behaves the same as a link to `wine`.
     */
    private fun createLink(link: File, target: String, resolved: File): Boolean {
        link.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
        if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()

        val linked = runCatching {
            Files.createSymbolicLink(
                link.toPath(),
                Paths.get(target.replace('/', File.separatorChar)),
            )
        }.isSuccess
        if (linked) return true

        if (!resolved.isFile) return false
        return runCatching { resolved.copyTo(link, overwrite = true) }.isSuccess
    }

    /**
     * Replace [destination] with [staging], leaving nothing of the old version.
     *
     * A rename, because staging lives under the store root and so on the same
     * filesystem. The recursive copy is the fallback for the case where it does
     * not, which on internal storage it should not.
     */
    private fun swapIntoPlace(staging: File, destination: File): Boolean {
        destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
        if (destination.exists() && !deleteTree(destination)) return false
        if (staging.renameTo(destination)) return true
        return runCatching {
            staging.copyRecursively(destination, overwrite = true)
        }.getOrDefault(false)
    }

    /**
     * The archive entry's destination, or null when it would escape.
     *
     * Four checks, and all four matter:
     *
     *  - an empty name, or one containing a backslash, which is not a separator
     *    on this filesystem but is one on the platform these payloads target;
     *  - an absolute path, POSIX (`/etc/…`) or Windows (`C:\…`);
     *  - a `..` segment anywhere, which is the ordinary form of the attack;
     *  - and finally the canonical path, which catches everything the first
     *    three miss — including the case where [destination] itself is reached
     *    through a symlink.
     */
    internal fun resolveEntry(destination: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        if (entryName.contains('\\')) return null
        if (entryName.startsWith('/')) return null
        if (entryName.length >= 2 && entryName[1] == ':') return null

        val segments = entryName.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null

        return within(destination, segments)
    }

    /**
     * Where a symlink points, or null when it points anywhere this app will not
     * follow.
     *
     * Resolved against the link's *own* directory, which is what a relative
     * symlink means: `bin/wineboot -> wine` is `bin/wine`, not `wine`. The
     * `..` walk is done lexically and refuses the moment it would step above the
     * extraction root, so an escape is rejected rather than clamped — and the
     * canonical containment check underneath catches anything the walk missed.
     *
     * Absolute targets are refused outright, POSIX and Windows alike. There is
     * no such thing as a legitimate absolute link in a relocatable package: the
     * store directory it lands in is not the directory it was built in.
     */
    internal fun resolveLink(destination: File, entryName: String, linkTarget: String): File? {
        if (linkTarget.isBlank()) return null
        if (linkTarget.contains('\\')) return null
        if (linkTarget.startsWith('/')) return null
        if (linkTarget.length >= 2 && linkTarget[1] == ':') return null

        val linkSegments = entryName.split('/').filter { it.isNotEmpty() && it != "." }
        if (linkSegments.isEmpty() || linkSegments.any { it == ".." }) return null

        val segments = linkSegments.dropLast(1).toMutableList()
        for (segment in linkTarget.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> continue
                segment == ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.size - 1)
                }

                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null

        return within(destination, segments)
    }

    /** [segments] under [destination], or null when the canonical path escapes it. */
    private fun within(destination: File, segments: List<String>): File? {
        val root = destination.canonicalFile
        val target = File(root, segments.joinToString(File.separator))
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path
        if (canonical.path != rootPath && !canonical.path.startsWith(rootPath + File.separator)) {
            return null
        }
        return canonical
    }

    /**
     * A package's `profile.json`, read without unpacking it.
     *
     * The catalogue of bundled packages needs the type and `versionCode` of each
     * one before it can say whether the store already has it, and that is the only
     * place those two live. Reading the archive's first entry is cheap — the
     * packager writes `profile.json` first, so the xz stream is decoded for about
     * a kilobyte and closed.
     *
     * Null for anything that is not a readable `.wcp`, with no distinction between
     * the reasons: the caller's next move is the same either way, and the real
     * message comes from [install], which fails with the specific failure.
     */
    suspend fun profileOf(source: WcpSource): WcpProfile? = withContext(Dispatchers.IO) {
        val compression = WcpArchive.detect(source)
        if (!compression.decodable) return@withContext null
        val bytes = runCatching { readProfileBytes(source, compression) }.getOrNull()
            ?: return@withContext null
        runCatching {
            json.decodeFromString(WcpProfile.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * `profile.json`, without extracting anything.
     *
     * `build/package_wcp.py` writes it as the archive's first entry, so this
     * almost always stops after one member. It scans the whole archive rather
     * than requiring that, because the format is shared with other producers.
     */
    private fun readProfileBytes(source: WcpSource, compression: WcpCompression): ByteArray? {
        TarReader(WcpArchive.open(source, compression)).use { tar ->
            while (true) {
                val entry = tar.next() ?: return null
                if (entry.kind == TarEntryKind.FILE && entry.name == WCP_PROFILE) {
                    return tar.readBody()
                }
            }
        }
    }

    private fun writeRecord(
        into: ComponentStoreLayout,
        type: ComponentType,
        versionCode: Int,
        packageId: String?,
    ) {
        if (packageId == null) return
        runCatching {
            val file = into.record(type, versionCode)
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            file.writeText(json.encodeToString(ComponentRecord.serializer(), ComponentRecord(packageId)))
        }
    }

    private fun countFiles(directory: File): Int =
        directory.walkTopDown().count { it.isFile }

    /**
     * The digest published beside the package. See [FileWcpSource.publishedSha256].
     */
    internal fun readSidecar(archive: File): String? = FileWcpSource(archive).publishedSha256()

    internal fun sha256(file: File): String = sha256(FileWcpSource(file))

    internal fun sha256(source: WcpSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.open().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String {
        val digits = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    private companion object {
        const val OWNER_EXECUTE = 0b001_000_000
        const val TRAVERSAL = "the path escapes the component directory"
        const val LINK_TRAVERSAL =
            "a link target must be relative and stay inside the component directory"

        /** Wine, the worst of the measured set, expands 14.5×. */
        const val EXPANSION_ESTIMATE = 15L

        /** Never fill the device: 64 MiB stays free whatever the package needs. */
        const val HEADROOM_BYTES = 64L * 1024 * 1024

        /** How much is written between two `usableSpace` calls during extraction. */
        const val SPACE_CHECK_INTERVAL = 64L * 1024 * 1024

        /**
         * How much of the archive is consumed between two progress callbacks.
         *
         * 1 MiB of *compressed* input, which for the Wine package is around 90
         * updates over 88 MB — a bar that moves visibly and a recomposition rate
         * a phone does not notice. Per entry would be four thousand.
         */
        const val PROGRESS_INTERVAL_BYTES = 1L * 1024 * 1024
    }
}
