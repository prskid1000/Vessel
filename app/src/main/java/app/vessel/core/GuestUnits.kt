package app.vessel.core

/**
 * Which guest program a log line came from.
 *
 * ## Why this exists
 *
 * A session log is one pipe. Every process inside the container inherits the
 * desktop's stderr, so `explorer.exe`, `services.exe`, `rpcss.exe`, the shell
 * and the game all write into the same stream, interleaved, with nothing saying
 * which is which. Reading a crash in that is guesswork — and it is exactly the
 * state a crash has to be read in. A game died leaving a log whose last line was
 * a DLL load, and there was no way to tell whether the lines around it were the
 * game's at all.
 *
 * Wine and FEX both already stamp every line with the thread that wrote it —
 * `0150:err:module:import_dll …`, `D F8 Load module …` — and
 * [parseSessionLogLine] used to discard both. It keeps them now, as
 * [ParsedLogLine.unit], and this turns a unit into a name.
 *
 * ## How the name is learned
 *
 * From the guest's own output, because nothing else knows. Two lines announce a
 * program, both carrying the unit that will go on to write everything else:
 *
 * ```
 * 00f8:trace:loaddll:build_module Loaded L"D:\Games\…\metro.exe" … native
 * D F8 Load module metro.exe (metro.exe-1b95a19fe798bdda): 140000000
 * ```
 *
 * Only an **executable** claims a unit. A DLL load is announced by the same
 * unit and must not rename it: `metro.exe` loading `d3d11.dll` is still
 * `metro.exe`, and taking the last-loaded module as the name would relabel every
 * process after its most recent dependency — which is worse than no name,
 * because it looks authoritative.
 *
 * ## What it deliberately does not do
 *
 * No pid lookup, no `/proc`, no correlation with [GuestProcessTree]. Wine's unit
 * is a *thread*, not a process, and Vessel launches processes without knowing
 * the thread ids they will use. Reading the name out of the same stream the
 * lines arrive on is the only source that is guaranteed to agree with them.
 *
 * Not thread-safe: one instance per session, touched only by the log-recording
 * path, which is a single coroutine.
 */
class GuestUnits {

    private val names = HashMap<String, String>()

    /**
     * Learn from [text] if it announces a program, then return the name for
     * [unit].
     *
     * One call per line, doing both jobs, because the announcing line is itself
     * output from the program it names and should carry the name it just
     * taught.
     */
    fun label(unit: String?, text: String): String? {
        if (unit == null) return null
        val key = unit.trimStart('0').ifEmpty { "0" }.lowercase()
        executableIn(text)?.let { names[key] = it }
        return names[key]
    }

    /**
     * The executable named by a module-load line, or null for anything else.
     *
     * `.exe` only — see the class note on why a DLL must not rename a unit.
     */
    private fun executableIn(text: String): String? {
        for (pattern in ANNOUNCEMENTS) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues[1].substringAfterLast('\\').substringAfterLast('/')
            if (name.endsWith(".exe", ignoreCase = true)) return name.lowercase()
        }
        return null
    }

    private companion object {
        /**
         * Wine's and FEX's ways of saying "this unit is now running X".
         *
         * Wine quotes a full guest path in a `L"…"` literal; FEX prints a bare
         * file name followed by its hash in parentheses. Both are matched on the
         * *whole* text rather than anchored, because the level and channel have
         * already been stripped by the time this sees it.
         */
        val ANNOUNCEMENTS = listOf(
            Regex("""build_module Loaded L"([^"]+)""""),
            Regex("""^Load module (\S+\.exe) \("""),
        )
    }
}
