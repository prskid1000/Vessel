package app.vessel.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vessel.BuildConfig
import app.vessel.core.ComponentPackage
import app.vessel.core.Sha256
import app.vessel.data.ComponentStore
import app.vessel.data.WcpInstallResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Where `.wcp` downloads run, so they survive the app being swiped away.
 *
 * One queue, one worker, processed in order. Sequential rather than parallel
 * because the packages are 1–90 MB each on a phone radio: two at once finish no
 * sooner, and they turn one honest progress bar into two that each stall.
 *
 * ## The order is the guarantee
 *
 * download → verify against the registry's digest → hand to
 * [ComponentStore.install] → delete the archive. Nothing writes into the store
 * except the installer, which stages the payload on the same filesystem and
 * renames it into place, so there is no window in which a killed process leaves
 * `components/<Type>/<versionCode>/` half-populated for
 * [app.vessel.data.InstalledComponents] to report as present. A download that
 * dies leaves exactly one thing behind: a `.part` file the next attempt resumes
 * from.
 *
 * The digest is checked twice, deliberately. [ComponentDownloader] checks it so
 * that a corrupt transfer can *delete the part-file* — otherwise a resume would
 * rebuild the same wrong bytes forever — and `WcpInstaller` checks it again
 * because "verify before extracting" is its own contract and must not become
 * conditional on who called it.
 *
 * ## `dataSync`, and why that one is honest here
 *
 * [SessionService] argues at length that no defined foreground type describes
 * hosting a Windows process tree, and settles on `specialUse`. This service is
 * the easy case: it transfers files the user asked for over the network, which
 * is exactly what `dataSync` names.
 *
 * `START_REDELIVER_INTENT`, unlike [SessionService]'s `START_NOT_STICKY`, and
 * for a reason that is the mirror image: restarting a *session* the system
 * killed would boot a Wine prefix with no screen showing it, while restarting a
 * *download* resumes from the part-file and finishes the thing the user asked
 * for. A user cancel calls `stopSelf`, which is what stops the intent being
 * redelivered.
 */
@AndroidEntryPoint
class ComponentDownloadService : Service() {

    @Inject
    lateinit var downloader: ComponentDownloader

    @Inject
    lateinit var store: ComponentStore

    @Inject
    lateinit var downloads: ComponentDownloads

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Registry facts for everything queued, by id. Cleared as jobs finish. */
    private val requests = LinkedHashMap<String, DownloadRequest>()

    private var worker: Job? = null

    /** Throttles the notification: a 64 KB buffer over 88 MB is 1400 updates. */
    private var lastNotifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promoted first, before any decision: Android allows a few seconds
        // between startForegroundService and this call, and reading a registry
        // cache or hashing a leftover archive is longer than that.
        notify(downloads.active())

        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelAll()
                return START_NOT_STICKY
            }
        }

        val request = intent?.readRequest()
        if (request == null) {
            // Nothing to do and nothing to say — an empty start is how a
            // redelivered intent arrives after the queue has drained.
            stopIfIdle()
            return START_REDELIVER_INTENT
        }

        if (!BuildConfig.CAN_INSTALL_COMPONENTS) {
            // Refused out loud rather than ignored. The Play build cannot fetch
            // executable code at runtime (see the flavour comment in
            // app/build.gradle.kts), and a Download button that silently does
            // nothing is the failure mode this project treats as worse than an
            // error.
            downloads.enqueue(request.id, request.name)
            downloads.update(request.id) {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    detail = "This build of Vessel cannot download components. Play policy " +
                        "forbids fetching executable code at runtime, so components have to " +
                        "come from the side-loaded build instead.",
                )
            }
            stopIfIdle()
            return START_NOT_STICKY
        }

        requests[request.id] = request
        downloads.enqueue(request.id, request.name)
        startWorker()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** One worker at a time; a second start just adds to the queue it is draining. */
    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    val job = downloads.next() ?: break
                    runJob(job.id)
                }
            } finally {
                stopIfIdle()
            }
        }
    }

    private suspend fun runJob(id: String) {
        val request = requests[id]
        if (request == null) {
            // Queued in a previous process and the intent was not redelivered:
            // the id is known but the URL and digest are not, and inventing
            // either is exactly what this service exists not to do.
            downloads.update(id) {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    detail = "Vessel restarted and no longer has the registry entry for this " +
                        "component. Start the download again from the component list.",
                )
            }
            return
        }

        downloads.update(id) { it.copy(phase = DownloadPhase.DOWNLOADING) }
        notify(downloads.active())

        val result = try {
            downloader.download(request, downloadDirectory) { progress ->
                downloads.update(id) {
                    it.copy(
                        bytesDownloaded = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes,
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastNotifiedAt >= NOTIFY_INTERVAL_MS) {
                    lastNotifiedAt = now
                    notify(downloads.active())
                }
            }
        } catch (e: CancellationException) {
            downloads.update(id) {
                it.copy(phase = DownloadPhase.CANCELLED, detail = DownloadResult.Cancelled.summary)
            }
            throw e
        }

        when (result) {
            is DownloadResult.Failure -> {
                downloads.update(id) { it.copy(phase = DownloadPhase.FAILED, detail = result.summary) }
                requests.remove(id)
                return
            }

            is DownloadResult.Complete -> install(id, request, result.file)
        }
    }

    private suspend fun install(id: String, request: DownloadRequest, archive: File) {
        downloads.update(id) { it.copy(phase = DownloadPhase.INSTALLING) }
        notify(downloads.active())

        val outcome = store.install(
            archive = archive,
            packageId = request.id,
            expectedSha256 = request.sha256,
        )
        when (outcome) {
            is WcpInstallResult.Installed -> {
                // The archive has served its purpose and is up to 90 MB. Kept
                // only when the install failed for a reason a retry might get
                // past, so the second attempt does not re-download it.
                archive.delete()
                downloads.update(id) {
                    it.copy(phase = DownloadPhase.DONE, detail = outcome.summary)
                }
            }

            is WcpInstallResult.ChecksumMismatch -> {
                // Cannot happen after the downloader's own check unless the file
                // changed underneath us; either way the bytes are not the
                // package and keeping them would make every retry fail here.
                archive.delete()
                downloads.update(id) {
                    it.copy(phase = DownloadPhase.FAILED, detail = outcome.summary)
                }
            }

            is WcpInstallResult.Failure -> downloads.update(id) {
                it.copy(phase = DownloadPhase.FAILED, detail = outcome.summary)
            }
        }
        requests.remove(id)
    }

    private fun cancelAll() {
        worker?.cancel()
        worker = null
        for (job in downloads.pending()) {
            downloads.update(job.id) {
                it.copy(phase = DownloadPhase.CANCELLED, detail = DownloadResult.Cancelled.summary)
            }
        }
        requests.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopIfIdle() {
        if (downloads.pending().isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(job: ComponentDownloadJob?) {
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, ComponentDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val queued = downloads.pending().size
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildOngoingNotification(
                context = this,
                channelId = CHANNEL_ID,
                channelName = "Component downloads",
                title = job?.name ?: "Downloading components",
                icon = android.R.drawable.stat_sys_download,
                text = when {
                    job == null -> null
                    job.phase == DownloadPhase.INSTALLING -> "Installing"
                    queued > 1 -> "${megabytes(job.bytesDownloaded)} of " +
                        "${megabytes(job.totalBytes)} · ${queued - 1} more queued"

                    else -> "${megabytes(job.bytesDownloaded)} of ${megabytes(job.totalBytes)}"
                },
                action = NotificationCompat.Action.Builder(0, "Cancel", cancel).build(),
                // Extraction is not measured, so INSTALLING gets an
                // indeterminate bar rather than a determinate one frozen at 100%
                // — which reads as "stuck" rather than "unpacking 912 MB".
                progress = when {
                    job == null -> null
                    job.phase == DownloadPhase.INSTALLING -> 0L to INDETERMINATE
                    job.totalBytes > 0 -> job.bytesDownloaded to job.totalBytes
                    else -> 0L to INDETERMINATE
                },
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * `filesDir/downloads/`, beside `containers/`, `components/` and `logs/`.
     *
     * Internal storage rather than `cacheDir` on purpose: the system may clear
     * the cache directory under memory pressure, and losing an 80 MB part-file
     * halfway through a download on mobile data is the one thing resumability
     * exists to prevent.
     */
    private val downloadDirectory: File get() = File(filesDir, DOWNLOADS_DIRECTORY)

    private fun Intent.readRequest(): DownloadRequest? {
        val id = getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return null
        // The https and digest-shape rules live in ComponentRegistry, which is
        // the only thing that should ever produce one of these. They are checked
        // again here because an Intent is a boundary: the service is
        // exported="false" so nothing outside the app can reach it today, and a
        // rule that holds only while that stays true is a rule nobody will
        // remember to re-check.
        val url = getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://") } ?: return null
        val sha256 = getStringExtra(EXTRA_SHA256)?.takeIf { Sha256.isWellFormed(it) } ?: return null
        return DownloadRequest(
            id = id,
            name = getStringExtra(EXTRA_NAME).orEmpty().ifBlank { id },
            url = url,
            sha256 = sha256,
            sizeBytes = getLongExtra(EXTRA_SIZE, 0L),
        )
    }

    companion object {
        private const val CHANNEL_ID = "components"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CANCEL = 1

        private const val ACTION_CANCEL = "app.vessel.components.CANCEL"
        private const val EXTRA_ID = "packageId"
        private const val EXTRA_NAME = "packageName"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "sizeBytes"

        /** `filesDir/downloads/`. Named here because `ContainerPaths` does not own it yet. */
        const val DOWNLOADS_DIRECTORY = "downloads"

        /** Four notification updates a second is already more than anyone reads. */
        private const val NOTIFY_INTERVAL_MS = 250L

        /**
         * Queue [pkg] for download and install.
         *
         * Returns false — and starts nothing — when the registry entry has no
         * URL or no usable digest, so a caller can say why the button did
         * nothing instead of showing a service that immediately stops.
         */
        fun enqueue(context: Context, pkg: ComponentPackage): Boolean {
            val request = DownloadRequest.of(pkg) ?: return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, ComponentDownloadService::class.java)
                    .putExtra(EXTRA_ID, request.id)
                    .putExtra(EXTRA_NAME, request.name)
                    .putExtra(EXTRA_URL, request.url)
                    .putExtra(EXTRA_SHA256, request.sha256)
                    .putExtra(EXTRA_SIZE, request.sizeBytes),
            )
            return true
        }

        /** Cancel everything queued. The part-files stay, so a restart resumes. */
        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ComponentDownloadService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0) "—" else "${bytes / (1024 * 1024)} MB"
