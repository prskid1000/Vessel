package app.vessel.service

import app.vessel.core.ComponentPackage
import app.vessel.core.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Everything a download needs, and nothing else.
 *
 * A [ComponentPackage] carries provenance, an install flag and a component type
 * that the transfer has no use for, and — crucially — a nullable URL and digest.
 * Narrowing to this type at the edge means the downloader has no
 * "what if there is no URL" branch at all: [of] is the single place that
 * question is asked, and it is asked before anything is queued rather than after
 * a notification has already promised a download.
 *
 * It is also what crosses the `Intent` boundary into the service, which is the
 * other reason it is five flat fields rather than a domain object.
 */
data class DownloadRequest(
    val id: String,
    val name: String,
    val url: String,
    val sha256: String,
    /** The registry's figure, used only to check for room before starting. */
    val sizeBytes: Long,
) {
    companion object {
        /** [pkg] as a request, or null when the registry did not describe one. */
        fun of(pkg: ComponentPackage): DownloadRequest? {
            if (!pkg.isDownloadable) return null
            return DownloadRequest(
                id = pkg.id,
                name = pkg.name,
                url = pkg.url ?: return null,
                sha256 = pkg.sha256 ?: return null,
                sizeBytes = pkg.sizeBytes,
            )
        }
    }
}

/** How far a download has got. Reported often; must stay allocation-cheap. */
data class DownloadProgress(
    val bytesDownloaded: Long,
    /** Zero when the server would not say. Never guess from the registry here. */
    val totalBytes: Long,
) {
    /** 0f..1f, or null when [totalBytes] is unknown — a bar that cannot lie. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * What a download did.
 *
 * A sealed result rather than an exception for the same reason
 * [app.vessel.data.WcpInstallResult] is one: every outcome here is a sentence
 * some surface has to *say*, and "download failed" is not a sentence anybody can
 * act on. Each failure names the thing that went wrong and, where there is one,
 * the thing that would fix it.
 */
sealed interface DownloadResult {

    val summary: String

    data class Complete(
        val file: File,
        val bytes: Long,
        /** Non-zero when a part-file from an earlier attempt was continued. */
        val resumedFrom: Long,
    ) : DownloadResult {
        override val summary: String
            get() = "Downloaded ${megabytes(bytes)}" +
                if (resumedFrom > 0) " (resumed from ${megabytes(resumedFrom)})" else ""
    }

    sealed interface Failure : DownloadResult

    /** The registry entry has no URL, or no usable digest. See [ComponentPackage.isDownloadable]. */
    data class NotDownloadable(val id: String) : Failure {
        override val summary
            get() = "The registry does not say where to download $id from, or what it should " +
                "hash to, so there is nothing safe to fetch."
    }

    /** The server answered, and the answer was not the file. */
    data class Rejected(val code: Int, val url: String) : Failure {
        override val summary
            get() = when (code) {
                404 -> "The server does not have this package (HTTP 404). The registry is " +
                    "pointing at a release asset that has been removed or renamed."

                403 -> "The server refused to serve this package (HTTP 403)."
                else -> "The server answered HTTP $code for $url."
            }
    }

    /** Nothing was reachable. Distinguished from [Rejected]: one is us, one is them. */
    data class Unreachable(val detail: String) : Failure {
        override val summary get() = "Could not reach the server: $detail"
    }

    /**
     * The bytes arrived and are not the published package.
     *
     * The part-file is deleted before this is returned. That matters more than
     * it looks: a resumable download whose tail is wrong would otherwise resume
     * from the same wrong bytes forever, and every retry would fail identically
     * with no indication that retrying could never work.
     */
    data class DigestMismatch(val expected: String, val actual: String) : Failure {
        override val summary
            get() = "The file that arrived is not the one the registry describes " +
                "(expected ${expected.take(16)}…, got ${actual.take(16)}…). The partial " +
                "download has been discarded, so trying again starts from the beginning."
    }

    data class NoSpace(val requiredBytes: Long, val freeBytes: Long) : Failure {
        override val summary
            get() = "Not enough space to download this: needs ${megabytes(requiredBytes)}, " +
                "${megabytes(freeBytes)} free."
    }

    data class Storage(val detail: String) : Failure {
        override val summary get() = "Could not write the download: $detail"
    }

    /** The user cancelled, or the service was torn down. The part-file is kept. */
    data object Cancelled : Failure {
        override val summary = "Download cancelled. The part that arrived is kept, so " +
            "starting it again continues from there."
    }
}

private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

/**
 * Fetches one `.wcp` and proves it is the published one.
 *
 * Four properties, and each is here because the obvious implementation of a
 * download does not have it:
 *
 *  - **Resumable.** Wine is 88 MB and this is a phone on mobile data. The bytes
 *    land in `<name>.wcp.part` and a second attempt sends `Range: bytes=N-`, so
 *    a dropped connection costs the tail rather than the file. A server that
 *    ignores the range and answers 200 is handled by starting over, because a
 *    200 body is the *whole* file and appending it to a part-file would produce
 *    a corrupt archive that hashes wrong for a reason nobody would guess.
 *
 *  - **Verified against the registry, not against itself.** The digest comes
 *    from `contents.json`, is checked over the completed file, and a mismatch
 *    deletes the part-file. `Content-Length` is deliberately *not* treated as
 *    proof of anything: it is the same server's claim about the same bytes.
 *
 *  - **Honest about the failure.** Every branch returns a [DownloadResult] with
 *    a sentence. There is no path that returns null.
 *
 *  - **Cancellable mid-copy.** The loop checks the coroutine on every buffer, so
 *    stopping a 900 MB download is immediate rather than "after the current
 *    request finishes", which for a single GET is never.
 *
 * Transparent gzip is turned off with an explicit `Accept-Encoding: identity`.
 * OkHttp adds `gzip` when the caller sets no encoding and then silently
 * decompresses, which makes `Content-Length`, the byte count and the resume
 * offset three different numbers — and a `.wcp` is xz already, so there is
 * nothing to gain by it either.
 */
@Singleton
class ComponentDownloader @Inject constructor() {

    /**
     * Built here rather than injected because nothing else in the app makes an
     * HTTP call, and the one setting that matters is a *missing* call timeout:
     * OkHttp's default of none is correct for an 88 MB body, and a shared client
     * configured for small JSON requests would cut it off partway.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Download [pkg] into [directory], resuming a part-file if there is one.
     *
     * [onProgress] is called from the IO dispatcher on every buffer; throttle it
     * at the call site, because a notification update per 64 KB of an 88 MB file
     * is 1400 updates.
     */
    suspend fun download(
        request: DownloadRequest,
        directory: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val url = request.url
        val expected = request.sha256

        if (!directory.isDirectory && !directory.mkdirs()) {
            return@withContext DownloadResult.Storage("could not create ${directory.name}")
        }

        // The id comes from the registry and reaches the filesystem here, so it
        // is sanitised for the same reason `ContainerPaths.safeName` exists: an
        // id containing a separator would put the part-file somewhere this class
        // never intended and the rename below would land there too.
        val stem = request.id.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")
            .ifBlank { "component" }
        val target = File(directory, "$stem.wcp")
        val part = File(directory, "$stem.wcp.part")

        // A completed file from a previous run that was never installed. Verify
        // rather than re-fetch: the bytes are already here and the only question
        // was ever whether they are the right ones.
        if (target.isFile && target.length() > 0) {
            val actual = Sha256.of(target)
            if (Sha256.matches(expected, actual)) {
                return@withContext DownloadResult.Complete(target, target.length(), 0)
            }
            target.delete()
        }

        val transferred = when (val fetched = fetch(url, part, request.sizeBytes, onProgress)) {
            is DownloadResult.Failure -> return@withContext fetched
            is DownloadResult.Complete -> fetched
        }

        val actual = Sha256.of(part)
            ?: return@withContext DownloadResult.Storage("could not read back ${part.name}")
        if (!Sha256.matches(expected, actual)) {
            part.delete()
            return@withContext DownloadResult.DigestMismatch(expected, actual)
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            return@withContext DownloadResult.Storage("could not move ${part.name} into place")
        }
        DownloadResult.Complete(target, target.length(), transferred.resumedFrom)
    }

    /**
     * The transfer itself. On success returns [DownloadResult.Complete] naming
     * the *part*-file; the caller owns verification and the rename into place.
     */
    private suspend fun fetch(
        url: String,
        part: File,
        declaredSize: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        var already = if (part.isFile) part.length() else 0L

        // Room for what is left, plus the headroom the installer also insists
        // on, so a download does not fill the device to the point where nothing
        // can be unpacked either.
        if (declaredSize > 0) {
            val needed = declaredSize - already + HEADROOM_BYTES
            val free = part.parentFile?.usableSpace ?: 0L
            if (free in 1 until needed) {
                return DownloadResult.NoSpace(needed, free)
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: UnknownHostException) {
            return DownloadResult.Unreachable("no route to ${e.message ?: "the host"}")
        } catch (e: SocketTimeoutException) {
            return DownloadResult.Unreachable("timed out (${e.message ?: "no response"})")
        } catch (e: IOException) {
            return DownloadResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }

        response.use {
            // 416 means the part-file is at least as long as the resource — a
            // truncated resource, or a part-file from a different build under
            // the same id. Neither can be resumed; the retry starts clean.
            if (response.code == 416) {
                part.delete()
                return DownloadResult.Rejected(416, url)
            }
            if (!response.isSuccessful) {
                return DownloadResult.Rejected(response.code, url)
            }
            // Asked to resume and got the whole thing anyway: the server does
            // not honour Range. Append would corrupt; start over.
            if (already > 0 && response.code != 206) {
                part.delete()
                already = 0L
            }

            val body = response.body ?: return DownloadResult.Unreachable("the response had no body")
            val remaining = body.contentLength().takeIf { it >= 0 } ?: -1L
            val total = when {
                remaining >= 0 -> already + remaining
                declaredSize > 0 -> declaredSize
                else -> 0L
            }

            return try {
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(already)
                    out.setLength(already)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = already
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            onProgress(DownloadProgress(written, total))
                        }
                    }
                    DownloadResult.Complete(part, written, already)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrown rather than swallowed: the caller's scope is being
                // torn down and must not go on to install anything. The
                // part-file stays on disk, which is the whole point of it.
                throw e
            } catch (e: IOException) {
                DownloadResult.Storage(e.message ?: "I/O error while writing ${part.name}")
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /** The same 64 MiB floor `WcpInstaller` keeps. One device, one rule. */
        const val HEADROOM_BYTES = 64L * 1024 * 1024
    }
}
