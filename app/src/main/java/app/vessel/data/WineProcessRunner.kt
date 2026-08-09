package app.vessel.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** One child process, fully described. Built by `SessionRuntime`, run by this class. */
data class ProcessSpec(
    /** Always `[/system/bin/linker64, <binary>, …]` — see `app.vessel.core.SYSTEM_LINKER`. */
    val argv: List<String>,
    /** Overlaid onto the inherited environment, never replacing it. */
    val environment: Map<String, String>,
    val workingDirectory: File,
    /**
     * Where merged output goes, or null for a pipe of this process's own.
     *
     * Set to [GuestOutputPipe.file] for anything whose descendants outlive it —
     * the desktop and every launched program. That class has the measurement
     * for why a pipe loses their output.
     */
    val output: File? = null,
) {
    /** The command as one line, for the session log's header. */
    val commandLine: String get() = argv.joinToString(" ")
}

/** How a run ended. A start failure is data, because it is a thing the log must say. */
sealed interface ProcessResult {
    data class Exited(val code: Int) : ProcessResult

    /** The process never started — almost always EACCES from the exec model. */
    data class NotStarted(val reason: String) : ProcessResult
}

/**
 * Starts child processes and drains their output.
 *
 * Thin on purpose: every decision about *what* to run is `SessionRuntime`'s, and
 * everything here is the small set of rules that must hold for any Wine process
 * this app starts.
 *
 * **`stderr` is merged into `stdout` onto one real pipe, and that is a
 * requirement rather than a convenience.** `init_options()` in
 * `dlls/ntdll/unix/debug.c` `fstat`s fd 2, recognises the null device and returns
 * *before parsing `WINEDEBUG` at all* — so a child pointed at `/dev/null` produces
 * no log whatever the channel set says, and because DXVK and vkd3d resolve
 * `__wine_dbg_output` from ntdll, the graphics story goes with it. Winlator ships
 * exactly that redirect. See `docs/LOGGING.md`.
 */
@Singleton
class WineProcessRunner @Inject constructor() {

    /**
     * Start [spec], or say why it could not start.
     *
     * The inherited environment is kept and overlaid rather than cleared:
     * `/system/bin/linker64` reads `ANDROID_ROOT`, `ANDROID_DATA` and the
     * namespace variables before it has loaded anything, so an empty environment
     * fails in the linker with no output at all — which looks exactly like Wine
     * crashing on startup.
     */
    fun start(spec: ProcessSpec): Result<Process> = runCatching {
        val builder = ProcessBuilder(spec.argv)
        builder.directory(spec.workingDirectory)
        builder.redirectErrorStream(true)
        // `appendTo` and not `to`: several processes share the one FIFO and a
        // truncating open would be nonsense on it. On a FIFO the open blocks
        // until a reader exists, which [GuestOutputPipe] guarantees by holding
        // one for the session.
        spec.output?.let { builder.redirectOutput(ProcessBuilder.Redirect.appendTo(it)) }
        builder.environment().putAll(spec.environment)
        builder.start()
    }

    /**
     * Read [process]'s output until the process exits, handing each line to [onLine].
     *
     * **Until the process exits — not until the pipe reaches EOF.** Those are the
     * same thing for an ordinary child and are emphatically not the same thing
     * here. `ProcessBuilder` gives the child one pipe for its merged output, and
     * every Wine process it goes on to spawn — `services.exe`, `rpcss.exe`,
     * `wineserver` and each Windows program — inherits that descriptor. So when
     * the desktop process dies the write end is still open in half a dozen
     * survivors and `readLine` blocks forever on a pipe that will never close.
     *
     * That single fact caused both of the session bugs that mattered:
     *
     *  - a guest that had died left the session stuck in `RUNNING`, because the
     *    `waitFor` that reports the exit code sits after this call; and
     *  - **Stop did nothing at all.** `SessionRuntime.stop` cancels the session
     *    job and joins it while holding the lifecycle lock, but a blocking
     *    `readLine` cannot be interrupted by cancellation, so the job never
     *    completed, the join never returned, and the lock was held for the rest
     *    of the process's life. Nothing could start or stop afterwards.
     *
     * The fix is a watchdog that closes the read end once the process is gone,
     * which is the one thing that *does* unblock a thread parked in `read(2)`.
     * It also runs on cancellation, so destroying the process is no longer the
     * caller's only way out — though it remains the polite one.
     */
    suspend fun drain(process: Process, onLine: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val stream = process.inputStream
            val closer = launch {
                try {
                    // Interruptible, so cancelling this coroutine does not leave
                    // a thread parked in waitFor until the process happens to end.
                    runInterruptible { process.waitFor() }
                    // Descendants may still be mid-write; their last lines are
                    // usually the interesting ones. Bounded, because the whole
                    // point is that this pipe has no natural end.
                    delay(FLUSH_GRACE_MS)
                } finally {
                    // Non-suspending, so it still runs when this coroutine is
                    // cancelled — which is what makes cancellation work.
                    runCatching { stream.close() }
                }
            }
            try {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
            } catch (_: IOException) {
                // The pipe went away underneath us — either the process ended and
                // the watchdog closed it, or it was destroyed. Both are ordinary.
            } finally {
                closer.cancel()
            }
        }
        coroutineContext.ensureActive()
    }

    /** Start, drain and wait. The whole life of a short-lived tool such as `wineboot`. */
    suspend fun run(spec: ProcessSpec, onLine: (String) -> Unit): ProcessResult {
        val process = start(spec).getOrElse {
            return ProcessResult.NotStarted(it.message ?: it.javaClass.simpleName)
        }
        try {
            drain(process, onLine)
            return ProcessResult.Exited(withContext(Dispatchers.IO) { process.waitFor() })
        } catch (cancellation: CancellationException) {
            process.destroyForcibly()
            throw cancellation
        }
    }

    private companion object {
        /**
         * How long to keep reading after the process itself has gone.
         *
         * Long enough for a dying Wine process's last few lines — which is where
         * the reason it died usually is — and short enough that Stop still feels
         * immediate. It is a grace period rather than a wait for EOF because
         * there is no EOF to wait for; see [drain].
         */
        const val FLUSH_GRACE_MS = 250L
    }
}
