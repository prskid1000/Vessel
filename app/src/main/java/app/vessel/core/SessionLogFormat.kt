package app.vessel.core

/**
 * Which engine produced a line.
 *
 * A session's output is one interleaved stream — Wine, the translator, the D3D
 * layer and the driver share a file descriptor — so the source is *recovered*
 * rather than told. [wire] is the single character the on-disk format uses and
 * must never change for a given entry: old log files are read by new builds.
 */
enum class LogSource(val wire: Char, val label: String) {
    WINE('W', "wine"),
    FEX('F', "fex"),
    DXVK('D', "dxvk"),
    VKD3D('K', "vkd3d"),
    DRIVER('R', "driver"),

    /** Vessel's own narration: the header, the elision marker, launcher notes. */
    VESSEL('V', "vessel"),
    ;

    companion object {
        fun ofWire(wire: Char): LogSource? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Four levels and no more. Wine's `fixme` maps to [WARN] — it means "this call is
 * stubbed", a warning about behaviour, and a fifth level would need a fifth
 * colour in a palette where colour is reserved for meaning.
 */
enum class LogLevel(val wire: Char, val label: String) {
    ERROR('E', "error"),
    WARN('W', "warn"),
    INFO('I', "info"),
    TRACE('T', "trace"),
    ;

    companion object {
        fun ofWire(wire: Char): LogLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One line as the viewer draws it.
 *
 * [index] is the position in the file, counted over every line including those a
 * filter hides, because it is the `LazyColumn` key: a key that changes when the
 * filter changes recomposes the whole list.
 */
data class LogEntry(
    val index: Int,
    val source: LogSource,
    val level: LogLevel,
    val text: String,
)

/** What [parseSessionLogLine] recovered from one line of raw output. */
data class ParsedLogLine(
    val source: LogSource,
    val level: LogLevel,
    val text: String,
    /**
     * Which thread inside the guest wrote this, as the emitter spelled it.
     *
     * **Wine and FEX both stamp every line with the thread that produced it and
     * this was being thrown away.** Wine writes `0150:err:module:import_dll …`
     * and FEX writes `D F8 Load module …`; the parser consumed both prefixes to
     * get at the useful part and kept nothing. So a session log was one
     * undifferentiated stream in which `explorer.exe`, `services.exe` and a game
     * were indistinguishable — which is exactly the state a crash has to be read
     * in, and it is why a game dying produced a log that simply stopped.
     *
     * Kept raw rather than parsed to a number: Wine prints hex without `0x`, FEX
     * prints hex with no padding, and the only thing anyone does with it is
     * compare it to the next line's. Null for output with no prefix at all —
     * DXVK's `info:` lines, Mesa's, and anything a guest program prints itself.
     *
     * [GuestUnits] turns this into a program name.
     */
    val unit: String? = null,
)

/**
 * The longest single line that will ever be stored. A `fixme` carrying a
 * serialised structure can be tens of kilobytes, and one per frame is how a log
 * goes from readable to unopenable. Truncation ends the line in an ellipsis.
 */
const val MAX_LOG_LINE_CHARS: Int = 4096

/**
 * Raw stderr in, `(source, level, text)` out.
 *
 * Pure, and in `core/` on purpose: this is the one piece of the logging feature
 * with real logic, and the piece a new Wine or DXVK release is most likely to
 * break.
 *
 * It recognises Wine channels (`err:module:import_dll …`), Wine's optional
 * pid/tid/timestamp prefixes (`0.012:0024:0028:err:…`, from `+pid`/`+tid`/
 * `+timestamp`), DXVK's `info:  DXVK:` form, FEX's `[ERR]` brackets and Mesa's
 * `MESA: error:`. Wine's leading level token is consumed but the channel kept,
 * since `module:import_dll` is the useful part; Mesa's whole prefix is consumed,
 * since the source column already says `driver`.
 *
 * Anything unrecognised is `(WINE, INFO)` with the text untouched. Wine owns the
 * pipe, and guessing a level from prose would put a red line on screen for a
 * game that printed the word "error" in its splash.
 */
fun parseSessionLogLine(raw: String): ParsedLogLine {
    val line = raw.trimEnd('\n', '\r', ' ', '\t')
    if (line.isBlank()) return ParsedLogLine(LogSource.WINE, LogLevel.INFO, "")

    FEX_BRACKET.matchAt(line, 0)?.let { match ->
        val level = LEVEL_WORDS[match.groupValues[1].lowercase()] ?: LogLevel.INFO
        return ParsedLogLine(LogSource.FEX, level, line.substring(match.range.last + 1).trim())
    }

    // FEX's other form, and the one it actually uses here: `D F8 Load module …`
    // — a one-letter level, then the thread in hex, then the message. It is not
    // the bracketed form above and was falling through to the generic path,
    // which read the whole thing as prose.
    FEX_UNIT.matchAt(line, 0)?.let { match ->
        val level = FEX_LEVEL_LETTERS[match.groupValues[1]] ?: LogLevel.INFO
        return ParsedLogLine(
            LogSource.FEX,
            level,
            line.substring(match.range.last + 1).trim(),
            match.groupValues[2],
        )
    }

    MESA_PREFIX.matchAt(line, 0)?.let { match ->
        val level = LEVEL_WORDS[match.groupValues[2].lowercase()] ?: LogLevel.INFO
        return ParsedLogLine(LogSource.DRIVER, level, line.substring(match.range.last + 1).trim())
    }

    val marker = findLevelToken(line)
    val body = if (marker == null) line.trim() else line.substring(marker.second + 1).trimStart()
    val level = marker?.first ?: LogLevel.INFO
    // The prefix `findLevelToken` walked over is Wine's `pid:tid:` — the last
    // numeric field before the level is the thread, and it is the only thing in
    // the line that says which guest process wrote it.
    val unit = marker?.let { unitOf(line, it.second) }
    return ParsedLogLine(sourceOf(body), level, body, unit)
}

/**
 * The last numeric field before the level token — Wine's thread id.
 *
 * `0.012:0024:0028:err:…` is timestamp, pid, tid: the field nearest the level
 * is the thread, which is what identifies the writer. A line with no numeric
 * prefix at all (`err:module:…`, which is what `WINEDEBUG` produces without
 * `+tid`) has no unit, and null is the honest answer rather than inventing one.
 */
private fun unitOf(line: String, levelColon: Int): String? {
    // `levelColon` is the colon *after* the level word, so the span still ends
    // in the level itself — `00f8:trace`. Drop that last field first, or the
    // answer is always the word `trace` and never a thread.
    val beforeLevel = line.substring(0, levelColon).substringBeforeLast(':', "")
    val last = beforeLevel.substringAfterLast(':').trim()
    return last.takeIf { it.isNotEmpty() && NUMERIC_FIELD.matches(it) }
}

/**
 * The level token and the offset of its colon, skipping Wine's numeric prefixes.
 *
 * Scanning stops at the first field that is neither a level word nor a number:
 * without that, `module:import_dll` would be walked into and `import_dll` read
 * as a channel of the non-existent level `module`.
 */
private fun findLevelToken(line: String): Pair<LogLevel, Int>? {
    var cursor = 0
    var fields = 0
    while (fields < MAX_PREFIX_FIELDS) {
        val colon = line.indexOf(':', cursor)
        if (colon < 0) return null
        val token = line.substring(cursor, colon).trim()
        LEVEL_WORDS[token.lowercase()]?.let { return it to colon }
        if (!NUMERIC_FIELD.matches(token)) return null
        cursor = colon + 1
        fields++
    }
    return null
}

/**
 * Which layer a line's body belongs to. `d3d11`, `d3d9` and `dxgi` resolve to
 * DXVK, not Wine: this product always installs a D3D translation layer, so
 * filing them under `wine` sends a rendering bug to the wrong component.
 */
private fun sourceOf(body: String): LogSource {
    val channel = body.substringBefore(' ').substringBefore(':').lowercase()
    val lower = body.lowercase()
    return when {
        channel in DXVK_CHANNELS -> LogSource.DXVK
        channel in VKD3D_CHANNELS || lower.startsWith("vkd3d") -> LogSource.VKD3D
        channel.startsWith("fex") -> LogSource.FEX
        channel in DRIVER_CHANNELS || lower.startsWith("mesa") || lower.startsWith("tu_") ->
            LogSource.DRIVER
        channel == "vessel" -> LogSource.VESSEL
        else -> LogSource.WINE
    }
}

/**
 * One line, as it is stored: `<level><source> <text>`.
 *
 * Two characters and a space rather than JSON per line. The viewer decodes tens
 * of thousands of these per screen, and the prefix means it never re-runs the
 * parser — which would re-decide the source on every scroll and, after a parser
 * change, give a different answer than the session recorded.
 *
 * Newlines and carriage returns inside [text] become spaces: one stored line is
 * one line of output, or the file's own line count stops meaning anything.
 */
fun encodeLogLine(source: LogSource, level: LogLevel, text: String): String {
    val flattened = buildString(text.length) {
        for (character in text) {
            append(if (character == '\n' || character == '\r') ' ' else character)
        }
    }
    val bounded = if (flattened.length <= MAX_LOG_LINE_CHARS) {
        flattened
    } else {
        flattened.take(MAX_LOG_LINE_CHARS) + "…"
    }
    return "${level.wire}${source.wire} $bounded"
}

/**
 * The inverse, with a fallback rather than an exception: a line whose prefix does
 * not decode is shown verbatim as a Vessel INFO line. A log is what someone
 * reaches for when everything else has gone wrong — the worst moment to refuse
 * over three bad bytes.
 */
fun decodeLogLine(raw: String, index: Int): LogEntry {
    if (raw.length >= 3 && raw[2] == ' ') {
        val level = LogLevel.ofWire(raw[0])
        val source = LogSource.ofWire(raw[1])
        if (level != null && source != null) {
            return LogEntry(index, source, level, raw.substring(3))
        }
    }
    return LogEntry(index, LogSource.VESSEL, LogLevel.INFO, raw)
}

/**
 * `<line>  ×N` — how a run of identical lines is written.
 *
 * A count of one is the line itself, so the common case carries no decoration.
 */
fun repeatedLogLine(text: String, count: Int): String =
    if (count <= 1) text else "$text  ×$count"

/** The one line that replaces the middle of a log that outgrew its cap. */
fun elidedLogMarker(lines: Int): String = "… $lines lines elided …"

/** What the sink says when it has been shouted at faster than it will write. */
fun rateLimitedLogMarker(dropped: Int): String =
    "… logging rate-limited, $dropped lines dropped …"

/** Wine prints at most a timestamp, a pid and a tid before the level. */
private const val MAX_PREFIX_FIELDS = 4

private val LEVEL_WORDS: Map<String, LogLevel> = mapOf(
    "err" to LogLevel.ERROR,
    "error" to LogLevel.ERROR,
    "crit" to LogLevel.ERROR,
    "assert" to LogLevel.ERROR,
    "fixme" to LogLevel.WARN,
    "warn" to LogLevel.WARN,
    "warning" to LogLevel.WARN,
    "info" to LogLevel.INFO,
    "trace" to LogLevel.TRACE,
    "debug" to LogLevel.TRACE,
)

/** A pid, a tid, or a `12.345` timestamp — hex or decimal, never a channel name. */
private val NUMERIC_FIELD = Regex("^[0-9A-Fa-f]+(\\.[0-9A-Fa-f]+)?$")

/**
 * `D F8 Load module …` — FEX's unbracketed line: level letter, thread, message.
 *
 * Anchored on a single letter followed by a hex field so it cannot match prose.
 * `D` is what FEX emits at the default verbosity here; the rest are included
 * because the letter set is fixed and a build with more logging on should not
 * suddenly fall through to the generic path.
 */
private val FEX_UNIT = Regex("^([DIWEA]) ([0-9A-Fa-f]{1,8}) ")

/** FEX's one-letter levels. `A` is an assertion, which is the interesting one. */
private val FEX_LEVEL_LETTERS = mapOf(
    "D" to LogLevel.TRACE,
    "I" to LogLevel.INFO,
    "W" to LogLevel.WARN,
    "E" to LogLevel.ERROR,
    "A" to LogLevel.ERROR,
)

private val FEX_BRACKET = Regex("^\\[(ERR|ERROR|CRIT|ASSERT|WARN|WARNING|INFO|DEBUG|TRACE)]\\s*")

private val MESA_PREFIX =
    Regex("^(MESA[A-Za-z-]*):\\s*(error|warning|info|debug)?:?\\s*", RegexOption.IGNORE_CASE)

private val DXVK_CHANNELS = setOf("dxvk", "d3d8", "d3d9", "d3d10", "d3d11", "dxgi")
private val VKD3D_CHANNELS = setOf("d3d12", "vkd3d")
private val DRIVER_CHANNELS = setOf("vulkan", "winevulkan", "turnip")
