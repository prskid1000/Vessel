package app.vessel.data

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How long an unconsumed download is kept before it is reclaimed.
 *
 * Long enough that the two reasons for keeping one still work. A `.wcp.part` is
 * kept on purpose so a cancelled download resumes instead of restarting — the
 * cancel path says so in as many words — and a whole `.wcp` whose install failed
 * is kept so the next launch can retry it without downloading again.
 *
 * A day covers both, because both are same-sitting behaviours: someone who
 * cancels a download and means to finish it comes back to it, and a failed
 * install is retried on the next launch, not next week. These are the largest
 * files the app ever writes — Wine alone is hundreds of megabytes compressed —
 * so the window is set by how long a retry plausibly takes, not by how long the
 * file might conceivably be wanted.
 */
internal val DOWNLOAD_MAX_AGE_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

/**
 * Delete downloads older than [maxAge] and answer how many bytes that freed.
 *
 * **The success path already cleans up; this is for the other two.**
 * `ComponentSetup` deletes a `.wcp` the moment it installs, because keeping it
 * would double the cost of every component on the phone. What it cannot delete
 * is an archive whose install *failed*, or a `.part` from a download that was
 * cancelled — both are deliberately retained so the next attempt is cheap, and
 * both were retained forever, because nothing ever swept this directory.
 *
 * Age is the only signal available here, and it is the right one. Whether an
 * archive is still wanted cannot be read off the filesystem: the package it
 * belongs to may have been installed since from the bundle, superseded by a
 * newer version, or removed from the catalogue entirely. What can be said is
 * that an archive nothing has consumed in a day is not mid-anything.
 *
 * [now] is a parameter so this is testable without waiting a day.
 */
internal fun sweepStaleDownloads(
    downloads: File,
    maxAge: Long = DOWNLOAD_MAX_AGE_MILLIS,
    now: Long = System.currentTimeMillis(),
): Long {
    if (!downloads.isDirectory) return 0L
    var freed = 0L
    for (file in downloads.listFiles().orEmpty()) {
        // Files only. A directory here is not something this wrote, and
        // guessing at what it is would be worse than leaving it.
        if (!file.isFile) continue
        if (!file.name.endsWith(".wcp") && !file.name.endsWith(".wcp.part")) continue
        // `lastModified` is 0 when it cannot be read. Treating that as "epoch,
        // therefore ancient" would delete on a stat failure, which is the one
        // outcome worth avoiding here.
        val modified = file.lastModified()
        if (modified <= 0L) continue
        if (now - modified < maxAge) continue
        val size = file.length()
        if (file.delete()) freed += size
    }
    return freed
}
