package app.vessel.display

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Tells Android's scheduler what this session's frame deadline is (ADPF).
 *
 * **The problem this exists for, measured.** A Metro session ran its whole
 * length with the cores at 1713-1977 MHz against a 3321/3801 MHz ceiling — under
 * 60% of the clock that was sitting there — while the GPU was at 84%. The
 * scheduler had no way to know the difference between this app and a text
 * editor, so EAS did what EAS does with a workload that looks like it is keeping
 * up: it kept the clocks down.
 *
 * The wrong fix for that is a CPU affinity mask, and this project has the number
 * to prove it: pinning to the 3.80 GHz prime pair measured **19% slower** than
 * letting the scheduler decide (`docs/OPTIMIZATION.md` §4, Candidate E). A hard
 * mask *removes* runqueues from a session that is wineserver plus guest threads
 * plus FEX threads, and the contention costs more than the extra 480 MHz
 * returns.
 *
 * ADPF is the same wish expressed in a way the platform can act on. Instead of
 * naming cores, a session names *threads* and a *deadline*, reports how long the
 * work actually took, and lets the platform choose placement and frequency —
 * so the failure mode that produced the 19% cannot occur, because nothing is
 * taken away. On this device the platform is already listening:
 * `debug.sf.enable_adpf_cpu_hint` is `true`, `performance_hint` is a registered
 * service, and there is a `motorola.hardware.power.IMdpfExt` next to Qualcomm's
 * `IPowerModule`.
 *
 * ## What it covers, and what it does not
 *
 * **The compositor's GL thread, and the guest only if the platform allows it.**
 * A hint session is documented against the calling process's thread group, and
 * Wine runs as separate processes — same uid, different pids. Whether a
 * same-uid tid from another process is accepted is not documented consistently
 * and is not worth guessing about, so [attachGuest] *tries*, says in the log
 * whether it worked, and carries on with the compositor thread alone if it did
 * not. That turns an unknown into a line in a session log rather than into a
 * comment asserting something nobody checked.
 *
 * **No GPU duration is reported**, though the API takes one from Android 15. It
 * would need an `EXT_disjoint_timer_query` around the composite, which this
 * renderer does not issue, and reporting a fabricated or CPU-derived GPU time to
 * a system that will clock the GPU from it is worse than reporting none. Left
 * undone deliberately rather than approximated.
 *
 * **Its value here is unmeasured.** The compositor was never the bottleneck —
 * the guest is, at 97-98% GPU — so the honest expectation for this class alone
 * is better frame pacing rather than a higher frame rate. The lever that reaches
 * the guest is `appCategory="game"` in the manifest, which is a separate change
 * made at the same time.
 */
class FrameHints private constructor(
    private val session: PerformanceHintManager.Session,
    private val targetNanos: Long,
) : AutoCloseable {

    private var frameStartNanos = 0L

    /** True once [attachGuest] has run, however it went. It runs once. */
    private var guestAttached = false

    /** Called on the GL thread, immediately before the composite. */
    fun frameBegin() {
        frameStartNanos = System.nanoTime()
    }

    /**
     * Called on the GL thread, immediately after the composite.
     *
     * A duration of zero or less is dropped rather than reported. The API treats
     * a non-positive duration as an error, and a clock that went backwards
     * across a frame is not a measurement worth acting on.
     */
    fun frameEnd() {
        val elapsed = System.nanoTime() - frameStartNanos
        if (elapsed <= 0L) return
        runCatching { session.reportActualWorkDuration(elapsed) }
    }

    /**
     * Ask for the guest's threads to be covered too, once.
     *
     * [pid] is a Wine process — normally the one that owns the desktop window,
     * read from `_NET_WM_PID`. Its threads come from `/proc/<pid>/task`, which is
     * readable for a process of this app's own uid.
     *
     * **Best effort, and loud about which way it went.** `setThreads` is API 34,
     * so on 31-33 this does nothing at all. Where it exists it may still reject
     * tids outside the calling thread group, and that rejection is an ordinary
     * outcome rather than a fault: the compositor session that was already
     * running stays exactly as it was.
     *
     * The compositor's own thread is included in the new list. Replacing the
     * thread set means replacing it — a call that named only the guest would
     * silently drop the one thread this class was created for.
     */
    fun attachGuest(pid: Int) {
        if (guestAttached || pid <= 0) return
        guestAttached = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val guest = threadsOf(pid)
        if (guest.isEmpty()) {
            Log.i(TAG, "no readable threads for guest pid $pid; hints stay on the compositor")
            return
        }
        // Bounded, because a Wine session can carry a lot of threads and a hint
        // spread across all of them is not a hint. The first few of
        // `/proc/<pid>/task` are the earliest created, which for a Wine process
        // is the main thread and the ones the loader made — not a guarantee that
        // they are the render threads, which is the other reason to keep this
        // small and say so.
        val tids = (listOf(Process.myTid()) + guest.take(MAX_GUEST_THREADS)).toIntArray()
        val outcome = runCatching { session.setThreads(tids) }
        if (outcome.isSuccess) {
            Log.i(TAG, "frame hints now cover ${tids.size} threads, including guest pid $pid")
        } else {
            Log.i(
                TAG,
                "the platform refused guest threads from pid $pid " +
                    "(${outcome.exceptionOrNull()?.javaClass?.simpleName}); " +
                    "hints stay on the compositor",
            )
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    /** The target this session was created with, in nanoseconds. For the log. */
    val targetMillis: Double get() = targetNanos / 1_000_000.0

    companion object {
        private const val TAG = "VesselFrameHints"

        /** See [attachGuest]. A hint spread over everything is not a hint. */
        private const val MAX_GUEST_THREADS = 8

        /** What a container with no `display.fpsLimit` is paced against. */
        private const val DEFAULT_TARGET_FPS = 60

        /**
         * Open a session for the calling thread, or null if the platform declines.
         *
         * **Must be called from the thread whose deadline this is** — it registers
         * `Process.myTid()` — which for the compositor means the first
         * `onDrawFrame`, because that is the first moment the GL thread exists and
         * is running our code.
         *
         * Null is an ordinary answer and not an error. `getSystemService` returns
         * nothing on a device with no power HAL support for hints, and
         * `createHintSession` returns null when the HAL declines, which is
         * documented behaviour. A caller that got null simply composites without
         * telling anyone, exactly as this app did before.
         */
        fun create(context: Context, fpsLimit: Int?): FrameHints? {
            val manager = runCatching {
                context.getSystemService(PerformanceHintManager::class.java)
            }.getOrNull() ?: return null

            val fps = fpsLimit?.takeIf { it > 0 } ?: DEFAULT_TARGET_FPS
            val target = 1_000_000_000L / fps

            val session = runCatching {
                manager.createHintSession(intArrayOf(Process.myTid()), target)
            }.getOrNull() ?: run {
                Log.i(TAG, "the platform declined a hint session; frames are not hinted")
                return null
            }

            Log.i(TAG, "frame hints open, target ${target / 1_000_000.0} ms ($fps fps)")
            return FrameHints(session, target)
        }

        /**
         * The tids under `/proc/<pid>/task`, or empty.
         *
         * Empty covers every way this can fail — the process exited between the
         * window reporting its pid and this read, or `/proc` denied the listing —
         * and all of them mean the same thing to the one caller: hint what we can
         * and say so.
         */
        private fun threadsOf(pid: Int): List<Int> =
            runCatching {
                File("/proc/$pid/task").list().orEmpty().mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
    }
}
