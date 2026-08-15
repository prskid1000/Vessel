package app.vessel.core

/**
 * A failure this launcher can name, recognised from one line of session output.
 *
 * DESIGN.md's **Failed** state promises the failing step, the last error line and
 * two actions — "never a generic 'something went wrong'". The last error line on
 * its own does not deliver that: `err:virtual:map_image_into_view failed to set
 * 60000020 protection on ntdll.dll section .text` is precise and still tells
 * almost nobody what happened. [headline] is what the screen shows instead, with
 * the raw line kept underneath it in mono.
 */
data class SessionDiagnosis(
    /** Stable id, so a test names a case rather than quoting its prose. */
    val id: String,
    /** One clause, in the user's terms. Shown as the failure. */
    val headline: String,
    /** One sentence of what it means and what would change it. */
    val detail: String,
)

/**
 * The failures worth recognising, in the order they are tried.
 *
 * Deliberately short. Every entry here is a failure mode this project has either
 * hit on the target device or documented from source; a speculative entry would
 * put a confident wrong explanation on the one screen a user reads when they are
 * already lost. Anything unmatched falls through to the raw line, which is the
 * honest answer for a failure nobody has seen yet.
 */
private val SIGNATURES: List<Pair<SessionDiagnosis, (String) -> Boolean>> = listOf(
    SessionDiagnosis(
        id = "noexec",
        headline = "Wine could not make its own code executable",
        detail = "The loader mapped a PE section and the kernel refused to mark it " +
            "executable, so nothing inside the prefix can run. This is a known open " +
            "problem in this build, not a fault in the container — the full line is " +
            "below and belongs in the bug report.",
    ) to { line ->
        line.contains("map_image_into_view") || line.contains("noexec filesystem")
    },

    SessionDiagnosis(
        id = "ntdll",
        headline = "Wine could not find its own ntdll.so",
        detail = "The Wine package is incomplete, or WINEDLLPATH did not reach the " +
            "process. Reinstalling the Wine component is the fix; see WineTree.dllPath " +
            "for why this variable is the one that matters.",
    ) to { line ->
        line.contains("could not load ntdll.so")
    },

    SessionDiagnosis(
        id = "server-bind",
        headline = "wineserver could not create its socket",
        detail = "The directory it was pointed at is not writable by this app. Clearing " +
            "the container's scratch directory and launching again usually clears it.",
    ) to { line ->
        line.contains("wineserver") && line.contains("bind:")
    },

    SessionDiagnosis(
        id = "no-display",
        headline = "There is no display for the Windows desktop to open",
        detail = "Wine's X11 driver could not reach an X server on DISPLAY. Until the " +
            "in-app server is available the session runs headless and stops here.",
    ) to { line ->
        val lower = line.lowercase()
        lower.contains("can't open display") || lower.contains("cannot open display") ||
            lower.contains("unable to open display")
    },

    SessionDiagnosis(
        id = "vulkan-device",
        headline = "The graphics driver is missing something DXVK needs",
        detail = "DXVK named the feature it could not find. This is what a silent " +
            "fallback to the phone's stock Vulkan driver looks like — check whether " +
            "TU_DEBUG=startup produced any Turnip output at the top of this log.",
    ) to { line ->
        line.contains("Device does not support required feature") ||
            line.contains("Device does not support Vulkan")
    },

    SessionDiagnosis(
        id = "illegal-instruction",
        headline = "The program used an instruction the translator does not have",
        detail = "FEX reached an x86 instruction it cannot emulate. It is a translator " +
            "gap rather than a configuration mistake, and the instruction is worth " +
            "reporting.",
    ) to { line ->
        line.contains("Unhandled illegal instruction")
    },

    SessionDiagnosis(
        id = "unimplemented",
        headline = "The program called something this Wine build does not implement",
        detail = "Wine names the function it was asked for. A newer Wine component is " +
            "the only thing that changes this.",
    ) to { line ->
        line.contains("unimplemented function")
    },
)

/**
 * The diagnosis for one line of output, or null when there is nothing to say.
 *
 * Pure and cheap: it runs on every line of a session that can emit thousands per
 * second, so it is a handful of `contains` calls and no regular expressions.
 */
fun diagnoseSessionLine(line: String): SessionDiagnosis? =
    SIGNATURES.firstOrNull { (_, matches) -> matches(line) }?.first

/**
 * The one diagnosis to show for a whole session.
 *
 * The **first** match wins, not the last. A failure cascades — a prefix that
 * cannot map executable pages then fails to open a display, then reports an exit
 * code — and the earliest recognised line is the one nearest the cause.
 */
fun diagnoseSession(lines: Iterable<String>): SessionDiagnosis? =
    lines.firstNotNullOfOrNull(::diagnoseSessionLine)

/**
 * Errors that are expected on this platform, and should not lead the digest.
 *
 * **The digest ranks by count, and count is not importance.** Measured on a
 * Requiem session that died: the run produced eight distinct errors, and the
 * three loudest were Wine announcing a setting Vessel had asked for, a COM
 * proxy that does not exist, and Kerberos being absent. The one line that
 * described the failure — a critical section held for two minutes — sat fourth,
 * with a count of two. A summary that puts the harmless first every time is one
 * people learn to skim.
 *
 * **Nothing is hidden.** These still appear, still with their counts; they sort
 * after everything else. Suppressing a Wine ERR would be worse than a bad
 * ordering, because the next person to hit a *real* Kerberos problem would find
 * a log that had decided for them that it did not matter.
 *
 * The bar for adding one: it must be *expected on this device*, unrelated to
 * whether a session works, and something we are not going to fix. Not "we have
 * seen it before" and not "it is noisy" — a noisy error that matters is exactly
 * what the volume note on `Loggable` exists for.
 */
private val ROUTINE_ERRORS: List<String> = listOf(
    // Proton's own announcement of WINE_RAM_REPORTING_BIAS, at ERR level, once
    // per process. It is Vessel's Hardware setting being applied, so a container
    // that reports six gigabytes prints this fifteen times and none of them is a
    // problem. See HardwareLimits.
    "HACK: ram_reporting_bias",
    // Wine's Kerberos SSP finding no krb5 on Android. Nothing in a game needs
    // it, and there is no krb5 to ship.
    "kerberos_LsaApInitializePackage",
    // COM marshalling for an interface with no proxy/stub in the prefix —
    // E_NOINTERFACE from IPSFactory. The caller handles it; writing the proxies
    // is a Wine project.
    "marshal_object Failed to create an IRpcStubBuffer",
)

/**
 * True when [text] is one of the expected errors above.
 *
 * Matched on `contains` rather than equality because a digest key has already
 * had its addresses and thread ids normalised away, but the surrounding format
 * — channel prefixes, a trailing count — still varies.
 */
fun isRoutineError(text: String): Boolean = ROUTINE_ERRORS.any { text.contains(it) }
