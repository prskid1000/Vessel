package app.vessel.ui.shell

import app.vessel.core.PeArchitecture
import app.vessel.core.PeReader
import java.io.File

/**
 * What Vessel can do with a file, decided before anything is launched.
 *
 * The table below is measured against the Wine tree this project builds and
 * installs, not assumed from what Windows can normally run. Two of the rows are
 * refusals, and they are the reason this type exists at all: a shortcut that
 * appears to launch and then does nothing is the failure mode this product treats
 * as worse than an error.
 *
 * | Extension | Verdict |
 * |---|---|
 * | `.exe` | runs — ARM64 native, x86-64 via `libarm64ecfex.dll`, x86-32 via WoW64 |
 * | `.bat` `.cmd` | runs — `cmd.exe /c`, and Wine's `cmd` is real |
 * | `.msi` | runs — `msiexec.exe /i`, real |
 * | `.lnk` | runs — Wine resolves shortcuts |
 * | `.vbs` `.js` | runs, with caveats — `wscript.exe` is real but WSH coverage is partial |
 * | `.ps1` | **refused** — Wine's `powershell.exe` is a stub and cannot run a script |
 * | anything else | not launchable; still browsable, importable and exportable |
 *
 * **Linux binaries and `.sh` are deliberately absent.** Android is bionic rather
 * than glibc, so a desktop Linux executable does not run here; and FEX ships as
 * Wine's two translation DLLs rather than as `FEXLoader`, so an x86 Linux binary
 * has no path either. Neither is offered anywhere in the interface, because
 * offering a file type the engine cannot serve is the same mistake as `.ps1` with
 * an extra step.
 */
sealed interface Launchable {

    /** What the launcher will actually run, for a caller that is going to run it. */
    val runnable: Boolean get() = this is Runs

    /** Vessel can start this. [via] is how, in the user's words. */
    data class Runs(
        val via: String,
        /** Set for a PE only; everything else is architecture-free. */
        val arch: PeArchitecture? = null,
        /**
         * A true statement that is not a refusal — Windows Script Host works, but
         * not completely. Shown beside the file rather than instead of it.
         */
        val caveat: String? = null,
    ) : Launchable

    /**
     * Vessel will not start this, and says why in one sentence.
     *
     * [reason] is user-facing prose, not a code. It is shown at the point the user
     * tries, and it names the missing thing rather than apologising.
     */
    data class Refused(val reason: String) : Launchable

    /** Not a program at all. A data file, browsable and exportable and nothing more. */
    data object NotAProgram : Launchable
}

/**
 * Classify [file] by its extension, and by its header where the extension claims
 * to be a PE.
 *
 * A `.exe` that is not a PE is refused rather than accepted on the strength of
 * its name — a truncated download and a renamed archive both end in `.exe`, and
 * the PE header is the only thing that actually knows.
 */
fun launchabilityOf(file: File): Launchable = when (file.extension.lowercase()) {
    EXE -> {
        val arch = PeReader.architectureOf(file)
        if (arch == PeArchitecture.UNKNOWN) {
            Launchable.Refused(
                "This file ends in .exe but has no PE header, so it is not a Windows program. " +
                    "A partly-downloaded file looks exactly like this.",
            )
        } else {
            Launchable.Runs(via = translationFor(arch), arch = arch)
        }
    }

    BAT, CMD -> Launchable.Runs(via = "cmd.exe /c")
    MSI -> Launchable.Runs(via = "msiexec.exe /i")
    LNK -> Launchable.Runs(via = "the shortcut's own target")

    VBS, JS -> Launchable.Runs(
        via = "wscript.exe",
        caveat = "Wine's Windows Script Host is real but incomplete, so a script that uses an " +
            "unimplemented object will stop part-way.",
    )

    PS1 -> Launchable.Refused(
        "Wine ships a stub PowerShell that cannot run scripts — this needs a real " +
            "PowerShell, which Vessel does not include.",
    )

    else -> Launchable.NotAProgram
}

/**
 * The one word for *what interprets this*, or null for a file that is its own
 * program.
 *
 * The badge on a tile normally names an architecture, read out of the PE header.
 * A batch file, an installer and a script have no PE header and no machine
 * field, so [PeReader] correctly answers `UNKNOWN` — and `unknown` is then the
 * wrong word to print. Nothing is unknown about a `.bat`: it is a batch file and
 * `cmd.exe` runs it. This is what the tile shows instead.
 *
 * Derived from the path rather than stored on the shortcut, and that is the
 * whole reason it is cheap: an architecture has to be persisted because reading
 * it means opening the file, whereas the extension is already in the path the
 * shortcut carries. Nothing to migrate, and nothing that can go stale against
 * the file it names.
 *
 * The words are the executable's stem rather than [Launchable.Runs.via]'s prose
 * — `cmd`, not `cmd.exe /c` — because this goes in a pill beside a tile and the
 * switches are not what is being said.
 */
fun interpreterFor(path: String): String? =
    when (path.substringAfterLast('.', "").lowercase()) {
        BAT, CMD -> "cmd"
        MSI -> "msiexec"
        VBS, JS -> "wscript"
        LNK -> "shortcut"
        else -> null
    }

/** The one-line answer to "how does this run on an ARM phone". */
private fun translationFor(arch: PeArchitecture): String = when (arch) {
    PeArchitecture.ARM64, PeArchitecture.ARM64EC -> "natively, without translation"
    PeArchitecture.X64 -> "ARM64EC with libarm64ecfex.dll"
    PeArchitecture.X86 -> "WoW64 with libwow64fex.dll"
    PeArchitecture.UNKNOWN -> "unknown"
}

/**
 * How the architecture was determined, for the profile sheet's read-only line.
 *
 * The design asks the sheet to say this out loud, and it is worth the line:
 * `unread` is a different statement from `x86`, and only the sentence tells them
 * apart.
 */
fun archProvenance(arch: PeArchitecture): String = when (arch) {
    PeArchitecture.ARM64, PeArchitecture.ARM64EC ->
        "Read from the PE header's machine field — IMAGE_FILE_MACHINE_ARM64. It runs " +
            "without translation."

    PeArchitecture.X64 ->
        "Read from the PE header's machine field — IMAGE_FILE_MACHINE_AMD64. FEX translates " +
            "it inside the process."

    PeArchitecture.X86 ->
        "Read from the PE header's machine field — IMAGE_FILE_MACHINE_I386. It runs through " +
            "WoW64 and libwow64fex.dll."

    PeArchitecture.UNKNOWN ->
        "The PE header could not be read, so what this was built for is unknown. It may not be " +
            "a Windows program at all."
}

/** Extensions the shell knows. Lower case throughout; the comparison lowers its input. */
private const val EXE = "exe"
private const val BAT = "bat"
private const val CMD = "cmd"
private const val MSI = "msi"
private const val LNK = "lnk"
private const val VBS = "vbs"
private const val JS = "js"
private const val PS1 = "ps1"
