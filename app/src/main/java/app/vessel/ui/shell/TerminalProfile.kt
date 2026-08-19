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
 *
 * **Git was here, in three forms, and is gone — because [COMMAND_PROMPT]
 * already is it.** The Git component is still installed and still on the
 * machine PATH (`PrefixRegistry.toolsPath`); what was removed is a button, and
 * only after measuring what a button could add.
 *
 * *`git-bash.exe` did nothing at all.* It is a launcher for `mintty`, which
 * wants a pty from `msys-2.0.dll`, and it is GUI-subsystem — so it started,
 * failed, and exited with no window and no error.
 *
 * *`usr\bin\bash.exe --login -i` under `wineconsole` started and then hung the
 * phone.* `ps` during the launch:
 *
 * ```
 * 317  bash.exe --login -i     1.4%  S   <- parent, waiting
 * 336  bash.exe --login -i    98.6%  R   <- child, spinning
 * ```
 *
 * `wchan` 0, state `R`: a userspace busy loop. That is MSYS2's `fork()`
 * emulation deadlocking under Wine — the child never completes the handshake
 * that hands it the parent's cloned address space. Device-wide CPU sat at 96%
 * user until it was killed, which is also what the "Git Bash freezes the
 * pointer" report really was. `/etc/profile` forks on its way in, so a login
 * shell never reaches a prompt, and reaching one would not help: bash forks for
 * every external command.
 *
 * *`git-cmd.exe` worked, and was still not worth a button.* It reached a `C:\>`
 * prompt and `git --version` answered `git version 2.55.0.windows.3`. But all
 * it does beyond `cmd.exe` is set two variables and try to make a `doskey`
 * macro so `git` resolves without PATH — and it printed an error doing even
 * that, because Wine ships no `doskey.exe`. With Git's three directories
 * already on the machine PATH the macro was redundant before it failed.
 *
 * **What the Command Prompt gives you instead, watched working on the device:**
 * `git version 2.55.0.windows.3`, `ls (GNU coreutils) 8.32`, `sed (GNU sed)
 * 4.9`, `awk "BEGIN{print 7*6}"` → `42`, and `ls --version | grep -i coreutils`
 * — a real pipeline between two x86-64 MSYS2 processes under FEX, because cmd
 * pipes with two processes and a handle rather than with `fork()`. The only
 * thing missing is the shell language itself: subshells, `$(…)`, and `.sh`
 * scripts.
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
     * PowerShell 7, and the one entry here that is not a Wine program.
     *
     * **Beside the Command Prompt because it is the shell language `cmd` does
     * not have.** The note above this enum measured what the Command Prompt can
     * already do — `git`, `ls`, `sed`, `awk`, real pipelines between two x86-64
     * MSYS2 processes under FEX — and named the one thing missing: the shell
     * language itself, subshells, `$(…)`, scripts. This is that, without the
     * `fork()` MSYS2 needs and deadlocks on, because PowerShell is a single
     * Win32 process that spawns children with `CreateProcess`.
     *
     * **The header's "PowerShell 7 cannot be built from source and belongs
     * behind the component downloader" is now out of date.** It does not need
     * building: the `win-x64` release is a self-contained .NET application, so
     * `build/tools.sh` unpacks the published zip into the `Tools` payload
     * alongside Git, Python, Node and the JDK, and the APK carries it. The rule
     * that comment was defending still holds and is why [installedAt] is set
     * here and not null — a button that has only ever refused is worse than no
     * button, so this one is offered only when the file is really there.
     *
     * **Unverified, and this is a real caveat rather than a formality.**
     * PSReadLine drives the terminal with VT escape sequences, and Wine's
     * conhost has no VT parser — it stored the mode word verbatim, so
     * `SetConsoleMode(ENABLE_VIRTUAL_TERMINAL_PROCESSING)` always succeeded and
     * every program that probes that way believed it. Python's and Node's REPLs
     * both printed their escapes as literal text on the device for exactly this
     * reason. `patches/wine/0052` stops conhost claiming the capability, which
     * should make PowerShell take its Console API rendering path instead; until
     * that is measured on the device, expect this to look wrong rather than to
     * fail.
     */
    POWERSHELL(
        label = "PowerShell",
        program = "pwsh.exe",
        installedAt = """C:\Program Files\PowerShell\pwsh.exe""",
        missingReason = "PowerShell arrives with the Tools component; install it from Components",
    ),

    /**
     * A web browser, and the second entry here that Wine does not provide.
     *
     * **Pale Moon, because Firefox was measured here and does not work.** Four
     * consecutive sessions ended on the same line — `RoGetActivationFactory`
     * failing to find `Windows.System.Profile.WindowsIntegrityPolicy`, a WinRT
     * namespace Wine does not ship — with no window, no content process and 0%
     * CPU. Goanna forked Gecko before 2017 and so predates the Windows app model
     * entirely; it never makes the call. It is also single-process, which is the
     * other thing neither Firefox nor VS Code got through.
     *
     * **The cost is real and is not hidden here: this browser cannot render much
     * of the modern web.** A 2017-era engine is the same fact that makes it start
     * and makes sites break. It is the browser that works, not the browser that
     * is good.
     *
     * **x86-64, deliberately**, where everything else in the payload is ARM64.
     * That is what lets it load Vessel's ARM64EC graphics DLLs at all; see
     * `SessionRuntime.TOOLS_LAYOUT` and native/pins.env. It costs FEX translation
     * on the browser's own code and buys back the entire graphics stack.
     *
     * **Not `viaConsole`, obviously, and not `iexplore` either.** The note at the
     * top of this file records that Wine's `iexplore.exe` is a thin shell over
     * `mshtml` rather than a browser; that is why this entry has to come from the
     * payload.
     */
    PALE_MOON(
        label = "Pale Moon",
        program = "palemoon.exe",
        installedAt = """C:\Program Files\Pale Moon\palemoon.exe""",
        missingReason = "Pale Moon arrives with the Tools component",
        viaConsole = false,
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
            POWERSHELL -> "pwsh"
            // The one caption here that is not a command you would type, because
            // nothing types this one — see [PALE_MOON], which is deliberately off
            // `PATH`. "web" says what the button is for in the three characters
            // the others use to say what they are.
            PALE_MOON -> "web"
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
