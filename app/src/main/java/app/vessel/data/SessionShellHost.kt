package app.vessel.data

import app.vessel.core.GuestViewport
import app.vessel.core.SessionDisplayServer
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.shell.GuestPath
import app.vessel.ui.shell.GuestWindow
import app.vessel.ui.shell.ShellHost
import app.vessel.ui.shell.TerminalOption
import app.vessel.ui.shell.TerminalProfile
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
            list.map {
                GuestWindow(
                    id = it.id,
                    title = it.title,
                    focused = it.focused,
                    program = it.program,
                    minimized = it.minimized,
                    bounds = it.bounds,
                )
            }
        }

    override val viewport: Flow<GuestViewport> = display.guestViewport

    override val available: Boolean = true

    override val unavailableReason: String? = null

    override suspend fun focus(windowId: Int) = display.focusWindow(windowId)

    override suspend fun minimize(windowId: Int): Boolean = display.minimizeWindow(windowId)

    override suspend fun close(windowId: Int): Boolean = display.closeWindow(windowId)

    override suspend fun kill(windowId: Int): Boolean = display.killWindow(windowId)

    override suspend fun moveResize(windowId: Int, x: Int, y: Int, width: Int, height: Int): Boolean =
        display.moveResizeWindow(windowId, x, y, width, height)

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
        if (running.containerId == null) {
            // Nothing is running at all, which is a different thing from the
            // wrong thing running and used to be reported as it: a tile tapped
            // on home said the program "belongs to a different container", which
            // was untrue, unhelpful, and pointed at the wrong fix. Starting the
            // session is the caller's job — see `SessionViewModel.launchApp` —
            // so reaching here means the session ended between the tap and this
            // line.
            return "No session is running, so there is nowhere to start ${shortcut.name}."
        }
        if (running.containerId != shortcut.containerId) {
            // A tile from a different container than the one on screen. Vessel
            // runs one session at a time by design, so this is a real answer
            // rather than something to silently reinterpret as "restart".
            return "${shortcut.name} belongs to a different container. Stop this session first, " +
                "then launch it from its own."
        }

        // Resolved against the drive the path names, not against `drive_c`.
        // Hardcoding `drive_c` here meant a shortcut to a program on a mapped
        // drive — the whole reason drive mapping exists — was refused as
        // missing while sitting right there on the card.
        val layout = paths.of(shortcut.containerId)
        val file = layout.resolveGuestPath(shortcut.executable)
        if (file == null || !file.isFile) {
            return "${shortcut.executable} is not on this container's drives. " +
                "If it is on removable storage, check that it is still connected."
        }

        val command = commandFor(shortcut) ?: return "Vessel cannot start ${file.name}."

        val workingDirectory = shortcut.workingDir
            .takeIf { it.isNotBlank() }
            ?.let { layout.resolveGuestPath(it) }
            ?.takeIf { it.isDirectory }
        // A working directory that was set and no longer exists is worth saying,
        // rather than quietly starting in the wrong place — a program that writes
        // its saves relative to the cwd would put them somewhere surprising.
        if (shortcut.workingDir.isNotBlank() && workingDirectory == null) {
            return "${shortcut.workingDir} is not a folder on this container's drives."
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

    /**
     * Which shells this container actually has.
     *
     * [TerminalProfile.COMMAND_PROMPT] needs no check: `cmd.exe` is built from
     * `programs/cmd` in the Wine tree this project compiles, so a prefix that can
     * run anything has it. The other two are looked for on `C:` at the path they
     * would install to, and a profile whose file is not there comes back disabled
     * with its own sentence rather than being dropped from the list.
     */
    override suspend fun terminalProfiles(containerId: String): List<TerminalOption> {
        // Against the drive the path names, not against `drive_c`. Everything on
        // the menu is on `C:` today, so this changes no behaviour — it stops the
        // next profile that is not from being refused as missing while sitting
        // right there, which is a defect this project has already shipped once
        // in `launch` above.
        val layout = paths.of(containerId)
        return TerminalProfile.entries.map { profile ->
            val installed = profile.installedAt
                ?.let { layout.resolveGuestPath(it)?.isFile } ?: true
            TerminalOption(profile, if (installed) null else profile.missingReason)
        }
    }

    override suspend fun openTerminal(containerId: String, profile: TerminalProfile): String? {
        val running = runtime.state.value
        if (running.containerId != containerId) {
            return "That container is not the one running. Stop this session first."
        }

        val driveC = File(paths.of(containerId).prefix, GuestPath.DRIVE_C)
        val installedAt = profile.installedAt
        if (installedAt != null && GuestPath.resolve(driveC, installedAt)?.isFile != true) {
            return profile.missingReason
        }

        // **The full path when the profile has one, the bare name otherwise.**
        // Wine resolves a bare name against PATH, which is right for everything
        // it ships — those all live in system32 — and wrong for a component.
        // `git-bash.exe` sits in `C:\Program Files\Git`, and PATH names Git's
        // `cmd`, `usr/bin` and `clangarm64/bin` subdirectories rather than its
        // root, because that is what Git's own installer puts there. So the
        // launch was `wine explorer /desktop=… git-bash.exe`, Wine found
        // nothing, and the button did nothing at all — no window, no error.
        // `installedAt` already names the exact file; it was checked for
        // existence just above and then not used.
        val target = installedAt ?: profile.program

        return when (
            val outcome = runtime.launchProgram(
                // A shell gets a console drawn around it; a Windows application
                // opens its own window and would be given a second, empty one.
                program = if (profile.viaConsole) WINE_CONSOLE else target,
                arguments = if (profile.viaConsole) listOf(target) else emptyList(),
                // The user's own drive, not the shell's install directory. A
                // terminal that opens in `C:\Program Files\PowerShell\7` has put
                // the user somewhere they did not ask to be and cannot write to.
                workingDirectory = driveC,
            )
        ) {
            ProgramLaunch.Started -> null
            is ProgramLaunch.Unavailable ->
                "${profile.label} did not open: ${outcome.reason}"
        }
    }
}

/**
 * The console front end Wine ships, and what it is for.
 *
 * `wineconsole <program>` starts that program with a real Win32 console
 * attached, and `conhost.exe` draws the console as a window — through `win32u`
 * and `winex11.drv` and out to the X server this app is already running. It is
 * why Vessel does not write a terminal: there is a real Windows console in the
 * tree this project compiles, and a program that inspects its console gets true
 * answers from it rather than the shape of one.
 *
 * Not `cmd.exe` directly. A program started the way [SessionShellHost.launch]
 * starts one has its standard handles on a pipe, and Wine gives a console
 * application with redirected handles no console and therefore no window — which
 * is exactly right for a `.bat` whose output belongs in the session log, and
 * exactly wrong for a shell the user is going to type into.
 */
private const val WINE_CONSOLE = "wineconsole"

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
