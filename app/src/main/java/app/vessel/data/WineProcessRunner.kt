package app.vessel.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        builder.environment().putAll(spec.environment)
        builder.start()
    }

    /**
     * Read [process]'s output until it closes, handing each line to [onLine].
     *
     * Returns when the pipe reaches EOF, which is when the process has exited or
     * been destroyed. There is no way to interrupt a blocking `readLine`, so
     * cancellation works by the caller destroying the process: that closes the
     * pipe, this returns, and the `ensureActive` below turns it into the
     * `CancellationException` the caller is waiting for.
     */
    suspend fun drain(process: Process, onLine: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
            } catch (_: IOException) {
                // The pipe went away underneath us, which is what destroying the
                // process looks like from here. Not worth a line in the log.
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
}
