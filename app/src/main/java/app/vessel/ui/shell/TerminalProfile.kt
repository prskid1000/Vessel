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
 * shell to start. The list is fixed rather than user-editable, because the three
 * entries are the three shells the platform can offer and a fourth would need a
 * component that does not exist.
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
) {
    COMMAND_PROMPT(
        label = "Command Prompt",
        program = "cmd.exe",
        installedAt = null,
        missingReason = "",
    ),

    POWERSHELL(
        label = "PowerShell",
        program = """C:\Program Files\PowerShell\7\pwsh.exe""",
        installedAt = """C:\Program Files\PowerShell\7\pwsh.exe""",
        // Deliberately names *which* PowerShell. Wine ships a `powershell.exe`
        // that is a stub and cannot run a script, which is why `.ps1` is refused
        // in Launchable; the thing that would work is Microsoft's PowerShell 7,
        // which is a self-contained .NET application and a separate install.
        missingReason = "PowerShell 7 is not installed in this container. Wine's own " +
            "powershell.exe is a stub that cannot run scripts, so Vessel does not offer it.",
    ),

    /**
     * A POSIX shell, and deliberately **not** Git Bash.
     *
     * Git for Windows' `bash.exe` is an MSYS2 program, and the MSYS2 runtime
     * emulates `fork()` on top of Win32 in a way that is notoriously fragile
     * under Wine — job control and process groups are exactly the parts that
     * break, and they are exactly what a shell is. Its ARM64 build is also new
     * and partial, so in practice it would be the x86-64 one through FEX: slow
     * and unreliable at the same time.
     *
     * BusyBox-w32 is a native Win32 PE with no emulation layer under it. One
     * binary carries `sh` plus about 150 applets — `ls`, `grep`, `sed`, `awk`,
     * `find`, `tar`, `xargs` — which is the *toolchain* people actually mean
     * when they say they want Git Bash, without the runtime that cannot survive
     * the trip. It is what [app.vessel.core.PrefixRegistry.toolsPath] puts on the
     * container's `PATH`, so those tools are in Command Prompt and PowerShell
     * too rather than only in this profile.
     */
    BUSYBOX_SH(
        label = "Shell",
        program = """C:\Program Files\Vessel Tools\busybox.exe""",
        installedAt = """C:\Program Files\Vessel Tools\busybox.exe""",
        missingReason = "The Unix tools are not installed in this container.",
    ),
    ;

    /** Whether this shell is one Wine provides rather than one somebody installed. */
    val builtIn: Boolean get() = installedAt == null
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
