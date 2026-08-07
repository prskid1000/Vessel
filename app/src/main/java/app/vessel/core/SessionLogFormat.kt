package app.vessel.core

/**
 * Which engine produced a line.
 *
 * A session's output is one interleaved stream — Wine, the translator, the D3D
 * layer and the driver all write to the same file descriptor — so the source is
 * something the log has to *recover* rather than something it is told. It is
 * carried per line because "which layer said this" is the first question anyone
 * reading a crash asks, and answering it by eye means knowing that `fixme:d3d:`
 * is Wine while `DXVK:` is not.
 *
 * [wire] is the single character the on-disk format uses; it never changes for a
 * given entry, because old log files are read by new builds.
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
 * Four levels and no more.
 *
 * Wine's `fixme` maps to [WARN] rather than to a level of its own: it means "this
 * call is stubbed", which is a warning about behaviour, and a fifth level would
 * need a fifth colour in a palette where colour is reserved for meaning.
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
 * [index] is the line's position in the file, counted from zero over every line
 * including the ones a filter hides. It is the viewer's `LazyColumn` key, which
 * is why it has to come from the file's own ordering rather than from the
 * position in a filtered list — a key that changes when the filter changes is a
 * key that recomposes the whole list.
 */
data class LogEntry(
    val index: Int,
    val source: LogSource,
    val level: LogLevel,
    val text: String,
)

/**
 * The viewer's severity filter.
 *
 * A *view* over lines already captured, and deliberately not a capture setting.
 * Choosing which channels to record is the mistake this product refuses
 * elsewhere too: it asks the user to predict, before the crash, which layer will
 * turn out to be at fault. Everything is captured; this decides what is drawn.
 */
enum class LogFilter {
    ALL,
    PROBLEMS,
    ;

    fun accepts(level: LogLevel): Boolean = when (this) {
        ALL -> true
        PROBLEMS -> level == LogLevel.ERROR || level == LogLevel.WARN
    }
}

/** What [parseSessionLogLine] recovered from one line of raw output. */
data class ParsedLogLine(
    val source: LogSource,
    val level: LogLevel,
    val text: String,
)

/**
 * The longest single line that will ever be stored.
 *
 * A `fixme` carrying a serialised structure can be tens of kilobytes, and one
 * such line per frame is how a log goes from readable to unopenable. Truncation
 * is visible — the line ends in an ellipsis — because a silently shortened line
 * is a line that lies.
 */
const val MAX_LOG_LINE_CHARS: Int = 4096

/**
 * Raw stderr in, `(source, level, text)` out.
 *
 * Pure, and in `core/` on purpose: this is the one piece of the logging feature
 * with real logic, and it is the piece a new Wine or DXVK release is most likely
 * to break. The session launcher will feed every line of the process's stderr
 * through it — see `SessionLogStore.open`.
 *
 * What it recognises:
 *
 *  - **Wine channels** — `err:module:import_dll …`, `fixme:d3d:…`, `warn:heap:…`,
 *    `trace:seh:…`. The leading level token is consumed; the channel is kept,
 *    because `module:import_dll` is the most useful part of the line and the
 *    level is already carried out of band.
 *  - **Wine's pid/tid and timestamp prefixes** — `0024:0028:err:…` and
 *    `0.012:0024:0028:err:…`, which appear when `WINEDEBUG` carries `+pid`,
 *    `+tid` or `+timestamp`. Only fields that look like numbers are skipped, so
 *    a channel name can never be mistaken for a prefix.
 *  - **DXVK** — `info:  DXVK: v2.3`, `warn:`, `err:`. DXVK uses the same level
 *    tokens as Wine, so the level comes from the token and the source from the
 *    `DXVK:`/`D3D11:`/`DXGI:` tag that follows it.
 *  - **FEX** — `[ERR] …`, `[WARN] …`, `[INFO] …`, `[ASSERT] …`. The bracket is
 *    consumed; it is a level marker and nothing else.
 *  - **Mesa/Turnip** — `MESA: error: …`, `MESA-INTEL: warning: …`. The whole
 *    prefix is consumed here, because the source column already says `driver`
 *    and repeating it in the text costs a third of a phone's line width.
 *
 * Anything it does not recognise is `(WINE, INFO)` with the text untouched.
 * Wine is the process that owns the pipe, so unprefixed output on it is Wine's
 * until something says otherwise, and guessing a level from prose would put a
 * red line on screen for a game that printed the word "error" in its splash.
 */
fun parseSessionLogLine(raw: String): ParsedLogLine {
    val line = raw.trimEnd('\n', '\r', ' ', '\t')
    if (line.isBlank()) return ParsedLogLine(LogSource.WINE, LogLevel.INFO, "")

    FEX_BRACKET.matchAt(line, 0)?.let { match ->
        val level = LEVEL_WORDS[match.groupValues[1].lowercase()] ?: LogLevel.INFO
        return ParsedLogLine(LogSource.FEX, level, line.substring(match.range.last + 1).trim())
    }

    MESA_PREFIX.matchAt(line, 0)?.let { match ->
        val level = LEVEL_WORDS[match.groupValues[2].lowercase()] ?: LogLevel.INFO
        return ParsedLogLine(LogSource.DRIVER, level, line.substring(match.range.last + 1).trim())
    }

    val marker = findLevelToken(line)
    val body = if (marker == null) line.trim() else line.substring(marker.second + 1).trimStart()
    val level = marker?.first ?: LogLevel.INFO
    return ParsedLogLine(sourceOf(body), level, body)
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
 * Which layer a line's body belongs to.
 *
 * `d3d11`, `d3d9` and `dxgi` resolve to DXVK rather than to Wine: this product
 * always installs a D3D translation layer, so a line on one of those channels is
 * that layer's, and filing it under `wine` would send someone reading a
 * rendering bug to the wrong component.
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
 * of thousands of these to draw one screen, and the prefix means it can colour a
 * line without re-running the parser — which would also mean re-deciding the
 * source every time the file is scrolled, and getting a different answer after a
 * parser change than the session actually recorded.
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
 * The inverse, with a fallback rather than an exception.
 *
 * A line whose prefix does not decode is shown verbatim as a Vessel INFO line. A
 * log file is the thing someone reaches for when everything else has already
 * gone wrong, and refusing to open one because three bytes are wrong is the
 * worst moment to be strict.
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

/**
 * What the sink says when it has been shouted at faster than it will write.
 *
 * Never silent. A log that hides its own truncation is worse than no log: it
 * turns a gap in the evidence into a false claim that nothing happened.
 */
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

private val FEX_BRACKET = Regex("^\\[(ERR|ERROR|CRIT|ASSERT|WARN|WARNING|INFO|DEBUG|TRACE)]\\s*")

private val MESA_PREFIX =
    Regex("^(MESA[A-Za-z-]*):\\s*(error|warning|info|debug)?:?\\s*", RegexOption.IGNORE_CASE)

private val DXVK_CHANNELS = setOf("dxvk", "d3d8", "d3d9", "d3d10", "d3d11", "dxgi")
private val VKD3D_CHANNELS = setOf("d3d12", "vkd3d")
private val DRIVER_CHANNELS = setOf("vulkan", "winevulkan", "turnip")
