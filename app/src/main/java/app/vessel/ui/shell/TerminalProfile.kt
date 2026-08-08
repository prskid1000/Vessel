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
    COMMAND_PROMPT(
        label = "Command Prompt",
        program = "cmd.exe",
        installedAt = null,
        missingReason = "",
    ),

    /**
     * Wine's own registry editor, interactively this time.
     *
     * Vessel already runs it non-interactively on every launch to apply
     * `prefix-seed.reg`, so it is present in every prefix by definition. Having
     * it on the menu is the difference between a container whose settings are
     * whatever Vessel decided and one the user can actually inspect and change.
     */
    REGEDIT(
        label = "Registry Editor",
        program = "regedit.exe",
        installedAt = null,
        missingReason = "",
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
            REGEDIT -> "reg"
            WINE_EXPLORER -> "files"
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
