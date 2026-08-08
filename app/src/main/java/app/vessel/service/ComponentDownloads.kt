package app.vessel.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where one component has got to. */
enum class DownloadPhase {
    QUEUED,
    DOWNLOADING,

    /** Unpacking through `WcpInstaller`. No byte count — extraction is not measured. */
    INSTALLING,

    /** In the store. Terminal. */
    DONE,

    /** Terminal, and [ComponentDownloadJob.detail] says what happened. */
    FAILED,

    /** Terminal. The part-file is kept, so starting again resumes. */
    CANCELLED,
}

/**
 * One component's progress, as a screen would draw it.
 *
 * [detail] is always populated for a terminal phase and is a whole sentence,
 * not a code — it is the [DownloadResult] or [app.vessel.data.WcpInstallResult]
 * summary verbatim. DESIGN.md's rule for the Failed session state applies here
 * unchanged: name the step and the reason, never "something went wrong".
 */
data class ComponentDownloadJob(
    val id: String,
    val name: String,
    val phase: DownloadPhase,
    val bytesDownloaded: Long = 0,
    /** Zero until the server says. [DownloadProgress.fraction] is the honest form. */
    val totalBytes: Long = 0,
    val detail: String? = null,
) {
    val finished: Boolean
        get() = phase == DownloadPhase.DONE ||
            phase == DownloadPhase.FAILED ||
            phase == DownloadPhase.CANCELLED
}

/**
 * The download queue's state, readable from anywhere.
 *
 * Separate from [ComponentDownloadService] for the same reason
 * [app.vessel.data.SessionRuntime] is separate from
 * [app.vessel.service.SessionService]: a `ViewModel` cannot bind to a service to
 * read a progress bar, and a service that owns both its work and the only copy
 * of its state has to be bound to before anything can be drawn.
 *
 * Terminal jobs stay in the list until [clearFinished]. A download that failed
 * and then vanished from the screen is a failure the user never got to read.
 */
@Singleton
class ComponentDownloads @Inject constructor() {

    private val _jobs = MutableStateFlow<List<ComponentDownloadJob>>(emptyList())
    val jobs: Flow<List<ComponentDownloadJob>> = _jobs.asStateFlow()

    fun snapshot(): List<ComponentDownloadJob> = _jobs.value

    /** The one being worked on, or null when the queue is idle. */
    fun active(): ComponentDownloadJob? = _jobs.value.firstOrNull {
        it.phase == DownloadPhase.DOWNLOADING || it.phase == DownloadPhase.INSTALLING
    }

    /**
     * Add [id] to the list, or reset an existing terminal entry back to QUEUED.
     *
     * Re-enqueuing something already in flight is ignored rather than
     * duplicated: two workers writing the same part-file would interleave and
     * produce an archive that fails its digest for no discoverable reason.
     */
    fun enqueue(id: String, name: String): Boolean {
        var added = false
        _jobs.update { jobs ->
            val existing = jobs.firstOrNull { it.id == id }
            when {
                existing == null -> {
                    added = true
                    jobs + ComponentDownloadJob(id, name, DownloadPhase.QUEUED)
                }

                existing.finished -> {
                    added = true
                    jobs.map { if (it.id == id) ComponentDownloadJob(id, name, DownloadPhase.QUEUED) else it }
                }

                else -> jobs
            }
        }
        return added
    }

    internal fun update(id: String, transform: (ComponentDownloadJob) -> ComponentDownloadJob) {
        _jobs.update { jobs -> jobs.map { if (it.id == id) transform(it) else it } }
    }

    internal fun next(): ComponentDownloadJob? =
        _jobs.value.firstOrNull { it.phase == DownloadPhase.QUEUED }

    /** Everything not yet terminal, in order. Used to decide whether to stop the service. */
    internal fun pending(): List<ComponentDownloadJob> = _jobs.value.filterNot { it.finished }

    fun clearFinished() {
        _jobs.update { jobs -> jobs.filterNot { it.finished } }
    }

    private fun MutableStateFlow<List<ComponentDownloadJob>>.update(
        transform: (List<ComponentDownloadJob>) -> List<ComponentDownloadJob>,
    ) {
        while (true) {
            val current = value
            if (compareAndSet(current, transform(current))) return
        }
    }
}
