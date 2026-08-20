package app.vessel.data

import java.io.File
import java.nio.file.Files

/**
 * Delete every FEX code cache except [keep], and answer how many bytes that
 * freed.
 *
 * **The one cache that multiplies.** `caches/fex/<digest>/` is keyed by
 * `fexCacheKey` — the FEX build's identity plus every `FEX_*` variable the
 * session ends up with — so a new FEX build *or* a changed FEX setting lands in
 * a fresh directory and the previous one is never looked at again. Its siblings
 * do not have this shape: `caches/mesa` is a single directory Mesa size-caps
 * itself, and `caches/vkd3d` is one file per executable.
 *
 * Nothing reclaimed the dead ones. Three FEX revisions in one afternoon left
 * 711 MB, 358 MB and 219 MB side by side, of which only the last was reachable
 * — and the two orphans outlived the FEX builds that wrote them, which
 * [ComponentStore.prune] had already removed. Prune could not see them: it
 * reclaims component directories under `components/`, keyed by version code,
 * while these live under a container and are keyed by a digest, and nothing
 * correlates the two.
 *
 * Safe to be blunt about, because this is derived data. A translation cache
 * that is deleted is rebuilt on the next run at the cost of some JIT time; a
 * cache for a configuration that no longer exists is not rebuilt at all,
 * because nothing will ever ask for it again.
 *
 * [keep] is the directory the session has just resolved. Passing the *resolved*
 * path rather than recomputing the digest here is deliberate: the key is taken
 * over the environment as it ended up, after the manifest and diagnostics
 * stages, and a second computation is a second chance to key on a
 * configuration that did not run.
 */
internal fun sweepStaleFexCaches(fexCacheRoot: File, keep: File): Long {
    if (!fexCacheRoot.isDirectory || Files.isSymbolicLink(fexCacheRoot.toPath())) return 0L

    // The live directory need not exist yet -- the first run with a new key
    // sweeps its predecessors before FEX has written anything. Compare by name,
    // which is the digest, so a not-yet-created directory still matches.
    val live = keep.name

    var freed = 0L
    for (child in fexCacheRoot.listFiles().orEmpty()) {
        if (child.name == live) continue
        if (Files.isSymbolicLink(child.toPath())) continue
        if (!child.isDirectory) continue
        freed += child.deleteTreeCounting()
    }
    return freed
}

private fun File.deleteTreeCounting(): Long {
    if (Files.isSymbolicLink(toPath()) || !isDirectory) return 0L
    var freed = 0L
    for (child in listFiles().orEmpty()) {
        freed += when {
            Files.isSymbolicLink(child.toPath()) -> 0L
            child.isDirectory -> child.deleteTreeCounting()
            // Size read before the delete and counted only if it succeeded: a
            // reclaim figure that includes bytes still on disk is worse than no
            // figure, because it is the number someone checks the fix against.
            child.isFile -> child.length().takeIf { child.delete() } ?: 0L
            else -> 0L
        }
    }
    delete()
    return freed
}
