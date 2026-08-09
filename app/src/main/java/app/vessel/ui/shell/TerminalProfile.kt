package app.vessel.ui.shell

/**
 * The shells a container can open a console on.
 *
 * **Windows Terminal itself cannot run here, and it is worth writing down why so
 * nobody spends a week finding out.** `wt.exe` is distributed as an MSIX package
 * and Wine implements no part of the Windows app model — no `AppxManifest`
 * handling, no package activation. Underneath that it is WinUI 2 through XAML
 * Islands, which needs the `Windows.UI.Xaml` WinRT framework that Wine does not
 * have and is not attempting; and its renderer is Direct2D and DirectWrite on
 * D3D11, which in this project is behind the surface work that has not landed
 * yet. Licensing is not the obstacle — Windows Terminal is MIT — capability is.
 * There is no unpackaged build that avoids the XAML dependency.
 *
 * **The terminal is therefore not something Vessel writes either.** Wine already
 * ships one: `wineconsole` starts a program with a real Win32 console attached
 * and `conhost.exe` draws it as a window, through `win32u` and `winex11.drv` and
 * out to the X server this app already runs. That console is a genuine Windows
 * console — `ReadConsoleInput`, code pages, a selection buffer — rather than a
 * terminal emulator pretending to be one for a program that can tell the
 * difference. Both binaries are in the Wine tree this project builds.
 *
 * So a "profile" here is what Windows Terminal's profiles are: a name and the
 * program to start. The list is fixed rather than user-editable.
 *
 * **Internet Explorer was here and is gone, and the reason is worth keeping.**
 * Wine's `iexplore.exe` is not a browser: it is a thin shell over `mshtml.dll`,
 * and Wine's built-in `mshtml` renders nothing itself — it delegates the whole
 * HTML engine to **wine-gecko**, a packaged Firefox build that Wine downloads
 * separately when a prefix is created. Vessel provisions non-interactively and
 * neither ships nor fetches Gecko, so `C:\windows\system32\gecko` holds an empty
 * `plugin` directory and the window opens with no engine behind it. Networking
 * is not the problem and was checked: `INTERNET` is granted and Wine uses the
 * host's sockets. Bundling Gecko would be ~50 MB for a Firefox fork old enough
 * that modern HTTPS fails before rendering matters.
 *
 * **PowerShell and a POSIX shell were here and are gone.** Both were drawn
 * disabled, truthfully saying they were not installed, and both stayed that way
 * because neither component was ever built — a button that has only ever
 * refused is a feature the launcher is advertising and the product does not
 * have. They come back when there is something behind them: BusyBox-w32 builds
 * from source and belongs in the APK, PowerShell 7 cannot be built from source
 * and belongs behind the component downloader. Until then the honest list is
 * the programs Wine itself provides.
 */
enum class TerminalProfile(
    /** What the launcher calls it. */
    val label: String,

    /** The executable `wineconsole` is asked to start. */
    val program: String,

    /**
     * Where the program lives on `C:`, or null for one Wine itself provides.
     *
     * Null means always available: `cmd.exe` is built from `programs/cmd` in the
     * tree this project compiles, so it cannot be missing from a working prefix.
     * A path means the profile is only offered when that file is really there,
     * which is what stops a tap opening an empty console that closes itself.
     */
    val installedAt: String?,

    /** Said when the profile is offered but the shell is not installed. */
    val missingReason: String,

    /**
     * Whether this needs `wineconsole` in front of it.
     *
     * True for a shell, which has no window of its own and needs a console
     * drawn around it. False for a program that is already a Windows
     * application — `regedit` and `explorer` both open their own window, and
     * putting a console in front of one would give it a second, empty one.
     */
    val viaConsole: Boolean = true,
) {
    // **Everything Wine provides that opens a window, in the order anybody
    // reaches for it.** Enum order is display order and the strip scrolls, so
    // ordering is the whole of the design work here: the first handful are what
    // a user wants on a phone screen without swiping, and the tail is real,
    // niche, and costs nothing to carry because Vessel ships none of it.
    //
    // Every one was verified present in a provisioned prefix on 2026-08-09. They
    // are all built from `programs/` in the Wine tree this project compiles,
    // which is the same argument that makes `cmd.exe` safe to offer with no
    // check — see `installedAt`.

    COMMAND_PROMPT(
        label = "Command Prompt",
        program = "cmd.exe",
        installedAt = null,
        missingReason = "",
    ),

    /**
     * Wine's file manager, beside Vessel's own C: browser rather than instead
     * of it.
     *
     * The two answer different questions. Vessel's browser is for getting a
     * file *in* and finding something to launch; this one is a Windows
     * application that guest programs' Open dialogs look like, and it can do
     * what a Windows file manager does inside the prefix.
     */
    WINE_EXPLORER(
        label = "File Explorer",
        program = "explorer.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * Wine's Notepad, which is a real Win32 program and not a stand-in.
     *
     * Worth a permanent slot for more than editing text: it is the shortest way
     * to prove the guest is *working* - a window, a menu bar, a caret, and a
     * keyboard that reaches it. It is what this project used to prove exactly
     * that.
     */
    NOTEPAD(
        label = "Notepad",
        program = "notepad.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * Wine's own registry editor, interactively this time.
     *
     * Vessel already runs it non-interactively on every launch to apply
     * `prefix-seed.reg`, so it is present in every prefix by definition. Having
     * it on the menu is the difference between a container whose settings are
     * whatever Vessel decided and one the user can inspect and change.
     */
    REGEDIT(
        label = "Registry Editor",
        program = "regedit.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * Wine's own configuration, and the closest thing this product has to
     * settings for a container.
     *
     * `docs/DESIGN.md` removed Vessel's settings screen deliberately - a
     * container is configured correctly for this device and there is nothing
     * for a user to decide. That argument covers *Vessel's* settings and not
     * Wine's: the Windows version a program checks, a DLL override for one
     * title, the audio driver. Those are real, per-prefix, and winecfg is where
     * a Wine user already knows to look. Shipping the tool beats reimplementing
     * a subset of it.
     */
    WINECFG(
        label = "Wine Configuration",
        program = "winecfg.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * The one entry here that does something nothing else in Vessel can.
     *
     * Vessel's rail can pause or stop the whole session. It cannot end *one*
     * guest program that has stopped responding, and there is no other way to:
     * the taskbar focuses a window, it does not kill one. That is why this sits
     * above the rest of the tail.
     */
    TASK_MANAGER(
        label = "Task Manager",
        program = "taskmgr.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * Control Panel - a shell over the applets, winecfg's among them.
     */
    CONTROL_PANEL(
        label = "Control Panel",
        program = "control.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * In Wine this hands off to whatever is registered for `.rtf` and usually
     * lands back on Notepad. Carried because it is what a Windows user expects
     * to find, not because it is a second editor.
     */
    WRITE(
        label = "WordPad",
        program = "write.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * The COM object browser. Niche, and the only window onto the OLE registry.
     */
    OLE_VIEW(
        label = "OLE/COM Object Viewer",
        program = "oleview.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    /**
     * Not a joke.
     *
     * A real Win32 program with a menu, a dialog, a bitmap blit and a mouse,
     * which makes it the cheapest end-to-end 2D smoke test in the prefix and the
     * only program here anybody would open twice.
     */
    MINESWEEPER(
        label = "Minesweeper",
        program = "winemine.exe",
        installedAt = null,
        missingReason = "",
        viaConsole = false,
    ),

    ;

    /** Whether this shell is one Wine provides rather than one somebody installed. */
    val builtIn: Boolean get() = installedAt == null

    /**
     * The caption under the launcher's button — the command, not the product.
     *
     * `cmd`, `pwsh`, `sh`: what you would type, which is both shorter than the
     * label and the thing that tells three identical terminal glyphs apart.
     */
    val shortLabel: String
        get() = when (this) {
            COMMAND_PROMPT -> "cmd"
            WINE_EXPLORER -> "files"
            NOTEPAD -> "notepad"
            REGEDIT -> "reg"
            WINECFG -> "winecfg"
            TASK_MANAGER -> "taskmgr"
            CONTROL_PANEL -> "control"
            WRITE -> "write"
            OLE_VIEW -> "oleview"
            MINESWEEPER -> "winemine"
        }
}

/**
 * A profile as the launcher shows it: the profile, and whether it can be opened.
 *
 * [unavailable] carries [TerminalProfile.missingReason] when the shell is not
 * there. The row is still drawn, disabled, rather than omitted — the same rule
 * the rest of this product follows for a thing it cannot do, because a missing
 * row answers "can Vessel open a PowerShell?" with silence.
 */
data class TerminalOption(
    val profile: TerminalProfile,
    val unavailable: String? = null,
) {
    val enabled: Boolean get() = unavailable == null
}
