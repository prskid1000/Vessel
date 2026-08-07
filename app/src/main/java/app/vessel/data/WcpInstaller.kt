package app.vessel.data

import app.vessel.core.ComponentType
import app.vessel.core.WcpProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened when a `.wcp` was installed into a container.
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
    ) : WcpInstallResult {
        override val summary: String
            get() = "${profile.versionName} — $fileCount file(s)" +
                if (checksumVerified) "" else ", checksum not verified (no sidecar)"
    }

    /** Every failure. Nothing is written to the component directory when one occurs. */
    sealed interface Failure : WcpInstallResult

    data class NotFound(val archive: File) : Failure {
        override val summary get() = "Package file is missing: ${archive.name}"
    }

    /**
     * The archive is zstd or xz, and this app has no decoder for either.
     *
     * The single most likely reason an install fails today. See [WcpCompression].
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

    data class UnknownType(val wire: String) : Failure {
        override val summary
            get() = "Package type '$wire' is not one this app can load"
    }

    data class Malformed(val detail: String) : Failure {
        override val summary get() = "Package is not readable: $detail"
    }
}

/**
 * Installs a `.wcp` into one container's `components/<Type>/` directory.
 *
 * The order is the whole point, and it is: identify the codec, **verify the
 * digest**, read `profile.json`, extract to a staging directory inside the same
 * container, and only then swap it into place. Nothing touches the live
 * component directory until an archive has proved it is the one that was
 * published and that every entry in it stays inside its destination.
 *
 * Idempotent by construction. Installing over an existing version replaces the
 * directory wholesale rather than merging into it, so a file that a new build
 * dropped cannot survive as a stale copy the loader would still find.
 *
 * Three passes over the file: hash, `profile.json`, extract. The hash pass reads
 * compressed bytes and the profile pass stops at the first entry, so it is two
 * decompressions in practice, on a local file, once per component per container.
 */
@Singleton
class WcpInstaller @Inject constructor(private val json: Json) {

    suspend fun install(
        archive: File,
        into: ContainerLayout,
        /** Overrides the sidecar. Supply the digest the registry published. */
        expectedSha256: String? = null,
    ): WcpInstallResult = withContext(Dispatchers.IO) {
        installBlocking(archive, into, expectedSha256)
    }

    /** The whole of [install], synchronously. Separated so tests need no dispatcher. */
    internal fun installBlocking(
        archive: File,
        into: ContainerLayout,
        expectedSha256: String? = null,
    ): WcpInstallResult {
        if (!archive.isFile) return WcpInstallResult.NotFound(archive)

        val compression = WcpArchive.detect(archive)
        if (!compression.decodable) {
            return WcpInstallResult.UnsupportedCompression(compression)
        }

        val expected = (expectedSha256 ?: readSidecar(archive))?.lowercase()
        if (expected != null) {
            val actual = sha256(archive)
            if (!actual.equals(expected, ignoreCase = true)) {
                return WcpInstallResult.ChecksumMismatch(expected, actual)
            }
        }

        val profileBytes = runCatching { readProfileBytes(archive, compression) }
            .getOrElse { return WcpInstallResult.Malformed(it.message ?: "unreadable archive") }
            ?: return WcpInstallResult.Malformed("no $PROFILE at the root of the archive")

        val profile = runCatching {
            json.decodeFromString(WcpProfile.serializer(), profileBytes.toString(Charsets.UTF_8))
        }.getOrElse { return WcpInstallResult.Malformed("$PROFILE is not valid JSON") }

        val type = profile.componentType
            ?: return WcpInstallResult.UnknownType(profile.type)

        if (!into.createDirectories()) {
            return WcpInstallResult.Malformed("could not create ${into.base}")
        }

        val staging = File(into.tmp, "install-${type.wire}-${System.nanoTime()}")
        try {
            if (!staging.mkdirs()) {
                return WcpInstallResult.Malformed("could not create staging directory")
            }
            val extracted = when (val outcome = extract(archive, compression, staging)) {
                is ExtractOutcome.Refused -> return outcome.failure
                is ExtractOutcome.Ok -> outcome.fileCount
            }

            File(staging, PROFILE).writeBytes(profileBytes)

            val destination = into.component(type)
            if (!swapIntoPlace(staging, destination)) {
                return WcpInstallResult.Malformed("could not replace ${destination.name}")
            }

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
            staging.deleteRecursively()
        }
    }

    /** How [extract] ended: a file count, or the failure that stopped it. */
    private sealed interface ExtractOutcome {
        data class Ok(val fileCount: Int) : ExtractOutcome
        data class Refused(val failure: WcpInstallResult.Failure) : ExtractOutcome
    }

    /**
     * Extract every payload entry into [destination].
     *
     * The first unsafe entry aborts the whole extraction. [destination] is a
     * staging directory the caller throws away on any failure, so a partial
     * extraction never reaches the live component directory.
     */
    private fun extract(
        archive: File,
        compression: WcpCompression,
        destination: File,
    ): ExtractOutcome {
        var files = 0
        TarReader(WcpArchive.open(archive, compression)).use { tar ->
            while (true) {
                val entry = tar.next() ?: break
                when (entry.kind) {
                    TarEntryKind.METADATA, TarEntryKind.LONG_NAME -> continue

                    TarEntryKind.LINK -> return refuse(
                        entry.name,
                        "links are never extracted — a link is a way out of the destination",
                    )

                    TarEntryKind.SPECIAL -> return refuse(
                        entry.name,
                        "only regular files and directories are extracted",
                    )

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
                        if (entry.name == PROFILE) continue
                        val target = resolveEntry(destination, entry.name)
                            ?: return refuse(entry.name, TRAVERSAL)
                        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                        target.outputStream().buffered().use { tar.copyBodyTo(it) }
                        if (entry.mode and OWNER_EXECUTE != 0) target.setExecutable(true, true)
                        files++
                    }
                }
            }
        }
        return ExtractOutcome.Ok(files)
    }

    private fun refuse(entryName: String, why: String): ExtractOutcome =
        ExtractOutcome.Refused(WcpInstallResult.UnsafeEntry(entryName, why))

    /**
     * Replace [destination] with [staging], leaving nothing of the old version.
     *
     * A rename, because staging lives in the same container directory and so on
     * the same filesystem. The recursive copy is the fallback for the case where
     * it does not, which on internal storage it should not.
     */
    private fun swapIntoPlace(staging: File, destination: File): Boolean {
        destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
        if (destination.exists() && !destination.deleteRecursively()) return false
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
     * `profile.json`, without extracting anything.
     *
     * `build/package_wcp.py` writes it as the archive's first entry, so this
     * almost always stops after one member. It scans the whole archive rather
     * than requiring that, because the format is shared with other producers.
     */
    private fun readProfileBytes(archive: File, compression: WcpCompression): ByteArray? {
        TarReader(WcpArchive.open(archive, compression)).use { tar ->
            while (true) {
                val entry = tar.next() ?: return null
                if (entry.kind == TarEntryKind.FILE && entry.name == PROFILE) {
                    return tar.readBody()
                }
            }
        }
    }

    /**
     * The digest published beside the package.
     *
     * `build/package_wcp.py` writes `<hex>  <filename>\n`, the `sha256sum`
     * format, so the first whitespace-delimited token is the digest.
     */
    internal fun readSidecar(archive: File): String? {
        val sidecar = File(archive.parentFile, archive.name + SIDECAR_SUFFIX)
        if (!sidecar.isFile) return null
        return runCatching {
            sidecar.readText().trim().substringBefore(' ').trim()
        }.getOrNull()?.takeIf { it.length == SHA256_HEX_LENGTH }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
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
        const val PROFILE = "profile.json"
        const val SIDECAR_SUFFIX = ".sha256"
        const val SHA256_HEX_LENGTH = 64
        const val OWNER_EXECUTE = 0b001_000_000
        const val TRAVERSAL = "the path escapes the component directory"
    }
}
