package app.vessel.data

import android.content.Context
import android.os.Process
import android.system.Os
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One live guest process: its pid, and the argv that says which one it is.
 *
 * The command line is carried rather than looked up twice — the same read that
 * proves a process is not one of the app's own is the read that finds
 * `wineserver`, and `/proc` is listed nine hundred entries at a time.
 */
data class GuestProcess(val pid: Int, val cmdline: String) {
    /** The root of the tree, which is stopped last and continued first. */
    val isWineserver: Boolean get() = GuestProcessTree.WINESERVER in cmdline
}

/**
 * The guest's processes, and the two signals that suspend and resume them.
 *
 * ## Why this can walk `/proc` at all
 *
 * `wineserver` and every Windows process are `ProcessBuilder` children of this
 * app, so they inherit its uid — which is the whole trick. `/proc` can be listed
 * by an app and almost nothing in it can be opened, but `stat(2)` on the
 * directory answers "is this one of ours" for free.
 * [MetricSampler.ourPids] does exactly this to find the tree it measures, and the
 * filter here is deliberately the same one: two different answers to "what is the
 * session" would mean the thing being paused and the thing being graphed were
 * different sets of processes.
 *
 * It is a separate implementation only because that method's set is not this
 * one's. **The sampler includes our own pid on purpose** — the app's CPU and RSS
 * are part of what a session costs — and sending `SIGSTOP` to that pid freezes
 * the UI that would have to send the `SIGCONT`. So this class subtracts the app's
 * own processes, twice over: by pid, and by the process name Android gives them.
 *
 * ## Why `SIGSTOP` and not something politer
 *
 * There is nothing politer. Wine has no suspend interface, the guest is a tree of
 * processes rather than one, and the only thing that reliably stops an arbitrary
 * Windows program mid-frame is the kernel refusing to schedule it.
 * `android.os.Process.sendSignal` is public API and is a plain `kill(2)`, which
 * the kernel permits within a uid — no root, no shell, no `ptrace`.
 */
@Singleton
class GuestProcessTree @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val uid = Process.myUid()

    /** Android names an app's processes `<package>` or `<package>:<suffix>`. */
    private val appProcessName = appContext.packageName

    /**
     * Every guest process under this uid, with the command line that identifies
     * it.
     *
     * Empty is a legitimate answer and means the session has no live guest —
     * which is what the caller sees between `wineserver -k` and teardown, and is
     * why nothing here treats an empty list as an error.
     */
    fun scan(): List<GuestProcess> {
        val entries = File(PROC).list() ?: return emptyList()
        val self = Process.myPid()
        val found = ArrayList<GuestProcess>(8)
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            if (pid == self) continue
            val owned = runCatching { Os.stat("$PROC/$pid").st_uid == uid }.getOrDefault(false)
            if (!owned) continue
            val cmdline = cmdlineOf(pid) ?: continue
            if (isOurOwnProcess(cmdline)) continue
            found += GuestProcess(pid, cmdline)
        }
        return found
    }

    /**
     * Stop the tree, and return what was stopped so [resume] can undo exactly it.
     *
     * **Clients first, `wineserver` last.** Every Windows process talks to the
     * server, so a server stopped ahead of its clients leaves each of them
     * blocking on a request that cannot be answered — for the fraction of a
     * second before they are stopped too, which is harmless, and then for as long
     * as the pause lasts, which is not: a client parked inside a server call is a
     * client whose own signal handling and timeouts are already ticking. Stopping
     * the leaves before the root means nothing is ever waiting on something that
     * cannot answer.
     */
    fun pause(): List<Int> {
        val tree = scan()
        val server = tree.firstOrNull { it.isWineserver }?.pid
        tree.forEach { if (it.pid != server) Process.sendSignal(it.pid, SIGSTOP) }
        if (server != null) Process.sendSignal(server, SIGSTOP)
        return tree.map { it.pid }
    }

    /**
     * Continue the tree, in the reverse order.
     *
     * **`wineserver` first.** A client resumed while its server is still stopped
     * makes a request into a socket nobody is reading and blocks there — the
     * desktop would come back visibly frozen, which is the worst of the two
     * failures because it looks like the pause broke rather than the resume.
     *
     * The server is found again rather than remembered, which is free: a stopped
     * process cannot exit, so the pid that was `wineserver` at pause is still
     * `wineserver` now.
     *
     * [known] is what [pause] returned, unioned with a fresh scan. The union
     * matters in one direction only and is free in the other: a pid in [known]
     * that has since exited cannot have been recycled, for the same reason —
     * while a pid that appeared since (a file manager the user started before
     * pausing, finishing its fork) is running normally, and `SIGCONT` to a
     * process that is not stopped does nothing at all.
     */
    fun resume(known: Collection<Int>) {
        val tree = scan()
        val server = tree.firstOrNull { it.isWineserver }?.pid
        if (server != null) Process.sendSignal(server, SIGCONT)
        (known.toSet() + tree.map { it.pid })
            .forEach { if (it != server) Process.sendSignal(it, SIGCONT) }
    }

    /**
     * Whether a command line is one of ours rather than one of the guest's.
     *
     * By name, because pid is not enough: this app is one process today and the
     * `:restart` pattern in `docs/DESIGN.md` would make it two, at which point
     * `myPid()` alone would stop the UI process and freeze the other one. A
     * guest's `cmdline` starts `/system/bin/linker64` — see
     * `app.vessel.core.SYSTEM_LINKER` — so the two sets cannot collide.
     */
    private fun isOurOwnProcess(cmdline: String): Boolean {
        val argv0 = cmdline.substringBefore(ARGUMENT_SEPARATOR).trim()
        return argv0 == appProcessName || argv0.startsWith("$appProcessName:")
    }

    /**
     * A process's argv, NUL separators and all.
     *
     * Unreadable means "skip", which is the safe direction on both counts: the
     * cost is a guest process that runs through a pause, against freezing
     * something we cannot identify and might never be able to wake. In practice
     * it means the process exited between the listing and this read.
     */
    private fun cmdlineOf(pid: Int): String? = runCatching {
        File("$PROC/$pid/cmdline").inputStream().use { stream ->
            val buffer = ByteArray(MAX_CMDLINE_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) null else String(buffer, 0, read, Charsets.US_ASCII)
        }
    }.getOrNull()

    companion object {
        private const val PROC = "/proc"

        /**
         * The two signal numbers, which `android.os.Process` does not name.
         *
         * It publishes `SIGNAL_KILL`, `SIGNAL_QUIT` and `SIGNAL_USR1` and nothing
         * else, so these are written out. They are the generic Linux values and
         * are the same on aarch64 as on x86 — the architectures that renumber
         * them (MIPS, Alpha, SPARC) are not ones Android runs on.
         */
        private const val SIGCONT = 18
        private const val SIGSTOP = 19

        /** Longer than any argv this app produces, and short enough to be free. */
        private const val MAX_CMDLINE_BYTES = 512

        /**
         * `cmdline` separates arguments with NUL.
         *
         * `Char(0)` rather than the escape, because a NUL escape in a source file
         * is one careless editor away from being an actual NUL byte in it.
         */
        private val ARGUMENT_SEPARATOR: Char = Char(0)

        /**
         * How `wineserver` is told apart from every other guest process.
         *
         * By its path in argv, because the pid cannot come from the [Process]
         * object this app started: Android's `java.lang.Process` has no `pid()`
         * — it is Java 9 API that libcore does not carry — and reaching for the
         * private field by reflection to avoid one file read would be the worse
         * trade. `-f` keeps the server in the foreground, so there is exactly one
         * of these while a session runs; the short-lived `wineserver -k` only
         * exists during teardown, by which point nothing is paused.
         */
        const val WINESERVER = "/bin/wineserver"
    }
}
