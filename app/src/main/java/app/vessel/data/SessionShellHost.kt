package app.vessel.data

import app.vessel.core.SessionDisplayServer
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.GuestWindow
import app.vessel.ui.shell.ShellHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ShellHost], for real — the taskbar and the launcher stop refusing.
 *
 * Replaces `ui.shell.UnavailableShellHost`, which reported `available = false`
 * with a sentence naming the two missing pieces. Both now exist:
 * [SessionDisplayServer.windows] publishes the guest's top-level windows, and
 * [SessionRuntime.launchProgram] starts one more program inside a live prefix.
 *
 * Same layering trade as [AppRegistryStore]: the interface is declared in
 * `ui/shell/` and implemented here, because the interface pass owns `ui/` and
 * this layer owns processes and the X server. Moving `ShellHost` into `core/`
 * later is a package change.
 */
@Singleton
class SessionShellHost @Inject constructor(
    private val runtime: SessionRuntime,
    private val display: SessionDisplayServer,
    private val paths: ContainerPaths,
) : ShellHost {

    override val windows: Flow<List<GuestWindow>> =
        display.windows.map { list ->
            list.map { GuestWindow(id = it.id, title = it.title, focused = it.focused) }
        }

    override val available: Boolean = true

    override val unavailableReason: String? = null

    override suspend fun focus(windowId: Int) = display.focusWindow(windowId)

    /**
     * Start [shortcut] in the session that is already running.
     *
     * Returns null on success, or the sentence to show. The refusals worth
     * distinguishing are all here rather than pushed down: the runtime knows
     * whether a desktop is running, but only this layer knows which container
     * the shortcut belongs to and where its `C:` is.
     */
    override suspend fun launch(shortcut: AppShortcut): String? {
        val running = runtime.state.value
        if (running.containerId != shortcut.containerId) {
            // A tile from a different container than the one on screen. Vessel
            // runs one session at a time by design, so this is a real answer
            // rather than something to silently reinterpret as "restart".
            return "${shortcut.name} belongs to a different container. Stop this session first, " +
                "then launch it from its own."
        }

        val driveC = File(paths.of(shortcut.containerId).prefix, GuestPath.DRIVE_C)
        val file = GuestPath.resolve(driveC, shortcut.executable)
        if (file == null || !file.isFile) {
            return "${shortcut.executable} is no longer on this container's C: drive."
        }

        val command = commandFor(shortcut) ?: return "Vessel cannot start ${file.name}."

        val workingDirectory = shortcut.workingDir
            .takeIf { it.isNotBlank() }
            ?.let { GuestPath.resolve(driveC, it) }
            ?.takeIf { it.isDirectory }
        // A working directory that was set and no longer exists is worth saying,
        // rather than quietly starting in the wrong place — a program that writes
        // its saves relative to the cwd would put them somewhere surprising.
        if (shortcut.workingDir.isNotBlank() && workingDirectory == null) {
            return "${shortcut.workingDir} is not a folder on this container's C: drive."
        }

        return when (
            val outcome = runtime.launchProgram(
                program = command.program,
                arguments = command.arguments,
                workingDirectory = workingDirectory ?: file.parentFile,
            )
        ) {
            ProgramLaunch.Started -> null
            is ProgramLaunch.Unavailable -> "${shortcut.name} did not start: ${outcome.reason}"
        }
    }
}

/** An executable Wine can be handed, and the arguments to hand it with. */
private data class GuestCommand(val program: String, val arguments: List<String>)

/**
 * What to actually run for a shortcut.
 *
 * The executable half of the same table `ui/shell/Launchable.kt` describes in
 * prose. That file decides *whether* something runs and says how in a sentence
 * for the user; this decides the argv. They must agree, which is why both are
 * keyed on the extension and neither infers from the other's output — a sentence
 * is not a parseable command line.
 *
 * Null for anything not runnable. `.ps1` reaches here only if the UI let it
 * through, and returning null keeps the failure a refusal rather than a launch
 * of Wine's stub PowerShell, which would appear to work.
 */
private fun commandFor(shortcut: AppShortcut): GuestCommand? {
    val path = shortcut.executable
    val extra = splitArguments(shortcut.args)
    return when (path.substringAfterLast('.', "").lowercase()) {
        // Handed to the loader by its Windows path. `.lnk` too: Wine resolves a
        // shortcut to its target itself, so there is nothing for us to read.
        "exe", "lnk" -> GuestCommand(path, extra)
        "bat", "cmd" -> GuestCommand("cmd.exe", listOf("/c", path) + extra)
        "msi" -> GuestCommand("msiexec.exe", listOf("/i", path) + extra)
        "vbs", "js" -> GuestCommand("wscript.exe", listOf(path) + extra)
        else -> null
    }
}

/**
 * A user's argument string, split into argv entries.
 *
 * Quote-aware, because the arguments most likely to need splitting are the ones
 * containing a path — `-config "C:\Program Files\x\a.ini"` — and a plain split on
 * whitespace would hand Wine three broken arguments instead of two good ones.
 *
 * Deliberately simple: double quotes group, everything else is literal, and there
 * is no escape character. Windows' own command-line parsing has corners
 * (backslash runs before a quote) that no user typing into a one-line field is
 * relying on, and implementing half of them would be worse than implementing
 * none — the half would be the part nobody could predict.
 */
internal fun splitArguments(raw: String): List<String> {
    val out = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    for (c in raw) {
        when {
            c == '"' -> quoted = !quoted
            c.isWhitespace() && !quoted -> {
                if (token.isNotEmpty()) {
                    out += token.toString()
                    token.clear()
                }
            }

            else -> token.append(c)
        }
    }
    if (token.isNotEmpty()) out += token.toString()
    return out
}
