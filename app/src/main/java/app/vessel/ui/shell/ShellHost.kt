package app.vessel.ui.shell

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One top-level window inside the running guest, as the taskbar shows it.
 *
 * [id] is the X window id. [title] is `WM_NAME`, which is what the guest's own
 * title bar says and therefore what the user will recognise.
 */
@Immutable
data class GuestWindow(
    val id: Int,
    val title: String,
    val focused: Boolean = false,
    /** The owning executable, lowercased and without a path. May be empty. */
    val program: String = "",
) {
    /** The tile letter, on the same reasoning as [AppShortcut.initial]. */
    val initial: String get() = title.trim().firstOrNull()?.uppercase() ?: "?"
}

/**
 * What the shell needs from the running session, and does not have.
 *
 * The three operations below are the whole difference between a taskbar that
 * works and a taskbar that is a picture of one. Each needs something inside
 * `data/` or the vendored X server, which this pass does not own —
 * `out/ui-needs-from-core.md` specifies them. Declared here so the taskbar and
 * the launcher are written against the real shape and switch on a Hilt binding
 * rather than a rewrite.
 *
 * **Tray icons are deliberately absent from this interface.** An Android-side
 * taskbar cannot receive a guest tray icon without a helper process inside the
 * guest, and this project does not ship one. A program that minimises to tray
 * disappears from [windows] rather than docking somewhere, and the taskbar says
 * so in words rather than leaving a gap the user reads as a bug. Adding a
 * `trayIcons` flow here would be the first step towards implying otherwise.
 */
interface ShellHost {

    /**
     * Top-level guest windows, newest last, or empty when nothing is running.
     *
     * A flow rather than a poll: the X server already knows when a window is
     * mapped, and a taskbar that refreshes on a timer is a taskbar that lags the
     * thing it is describing.
     */
    val windows: Flow<List<GuestWindow>>

    /**
     * Whether this host can actually do any of it.
     *
     * Not a capability flag for feature-gating — a flag the *UI reads out loud*.
     * When it is false the launcher and the taskbar say which piece is missing
     * instead of drawing controls that do nothing.
     */
    val available: Boolean

    /** Why not, when [available] is false. Shown verbatim. */
    val unavailableReason: String?

    /** Raise and focus a window the guest already has open. */
    suspend fun focus(windowId: Int)

    /**
     * Ask a window to close, the way a title bar's X does.
     *
     * Sends `WM_DELETE_WINDOW`, which reaches the guest as `WM_CLOSE`, so a
     * program with unsaved work gets to say so. False means the window never
     * advertised the protocol and nothing was sent — the caller's cue to offer
     * [kill] instead, which is a different and more destructive act.
     */
    suspend fun close(windowId: Int): Boolean

    /**
     * End the process behind a window. No dialog, no chance to save.
     *
     * The escalation, for the case [close] cannot reach: a program so wedged it
     * is not reading its message queue. False when the window set no
     * `_NET_WM_PID` and there is therefore nothing to end.
     */
    suspend fun kill(windowId: Int): Boolean

    /**
     * Start [shortcut] inside the session that is already running.
     *
     * This is the launcher's whole purpose and the single most important thing
     * missing from the current runtime: `SessionRuntime.start` takes a container
     * and nothing else, so there is no way to ask a live prefix to run one more
     * program. The mechanism is proven — `SessionRuntime.launchFileManager` does
     * exactly this for `winefile` — it is only not generalised.
     *
     * Returns null on success, or the sentence to show the user on refusal.
     */
    suspend fun launch(shortcut: AppShortcut): String?

    /**
     * The shells this container can open a console on, in menu order.
     *
     * Every [TerminalProfile] comes back, including the ones that cannot be
     * opened — a profile the container lacks is returned with its reason rather
     * than filtered out, so the launcher can draw it disabled. Suspending
     * because deciding means looking at `C:`.
     */
    suspend fun terminalProfiles(containerId: String): List<TerminalOption>

    /**
     * Open a console on [profile] inside the session that is already running.
     *
     * Returns null on success, or the sentence to show. Same contract as
     * [launch], and the same mechanism underneath — this is one more program
     * started in a live prefix, which happens to be a shell.
     */
    suspend fun openTerminal(containerId: String, profile: TerminalProfile): String?
}

/**
 * The honest stand-in: it reports that it cannot do any of this, and says why.
 *
 * Every surface that consumes [ShellHost] reads [unavailableReason] and prints
 * it. That is the design rule for this whole pass — a control that cannot work
 * says so and names what would fix it — and it is why this class exists at all
 * rather than the taskbar simply not being built.
 */
@Singleton
class UnavailableShellHost @Inject constructor() : ShellHost {

    override val windows: Flow<List<GuestWindow>> =
        MutableStateFlow<List<GuestWindow>>(emptyList()).asStateFlow()

    override val available: Boolean = false

    override val unavailableReason: String =
        "Vessel cannot see inside the running desktop yet. Listing its windows needs the X " +
            "server to publish them, and starting a second program needs the session runtime " +
            "to accept an executable — neither exists in this build."

    override suspend fun focus(windowId: Int) = Unit

    override suspend fun close(windowId: Int): Boolean = false

    override suspend fun kill(windowId: Int): Boolean = false

    override suspend fun launch(shortcut: AppShortcut): String = unavailableReason

    /**
     * Every profile, all refused with the same sentence.
     *
     * The list is not empty even here. A launcher with no Terminal section at
     * all says Vessel has no terminal; a disabled one that names the missing
     * piece says the terminal is not reachable *yet*, which is the true
     * statement and the one this class exists to make.
     */
    override suspend fun terminalProfiles(containerId: String): List<TerminalOption> =
        TerminalProfile.entries.map { TerminalOption(it, unavailableReason) }

    override suspend fun openTerminal(
        containerId: String,
        profile: TerminalProfile,
    ): String = unavailableReason
}
