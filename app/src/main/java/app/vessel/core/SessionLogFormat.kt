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

/**
 * `TF`, `WK`, `ED` — the two-letter prefix, as a key.
 *
 * **The accounting unit for everything downstream**, and the thing that had to
 * be recovered with `cut -c1-2 | sort | uniq -c` on a session before either of
 * the two biggest volume problems could be attributed. Level first and source
 * second, matching [encodeLogLine] character for character so a histogram key
 * and a line prefix can never disagree about what they mean.
 */
fun logPrefixKey(source: LogSource, level: LogLevel): String = "${level.wire}${source.wire}"

/**
 * The legend for those two letters, written into every session's header.
 *
 * **Nothing documented this and it cost real time.** A session log is a wall of
 * `TF`, `WK`, `ED`, `IW` with no key anywhere in the product, the docs or the
 * file itself — so the first thing anyone does with a log they have been handed
 * is work out the alphabet, and the second is get it wrong. Two of the three
 * cheap wins in this area are counts keyed on these letters, and a histogram
 * whose keys nobody can read is a worse artefact than no histogram.
 *
 * Derived from the two enums rather than written out, so a source added to
 * [LogSource] appears here without anyone remembering to say so. In the header
 * rather than in a document because the header travels with the file: a log
 * pasted into a bug report carries its own key.
 */
fun logPrefixLegend(): List<String> = listOf(
    "legend  every line starts with <level><source>, no space between them",
    "legend  level   " + LogLevel.entries.joinToString("  ") { "${it.wire} ${it.label}" },
    "legend  source  " + LogSource.entries.joinToString("  ") { "${it.wire} ${it.label}" },
)

/**
 * `… continues in <name> …` — the last line written into the head file.
 *
 * **The trap this closes produced a wrong answer, not merely lost time.** A
 * session past its head allowance is three files, and `<startedAt>.log` is the
 * *oldest* of them: it holds startup and then stops. Running `tail` on it during
 * a live session shows the last line of the head, which is a DLL load from ten
 * minutes ago, and the honest-looking reading of that is "nothing went wrong
 * after startup". That was reported once and it was false — the live end was in
 * `.log.t0` the whole time.
 *
 * The head now ends by saying where the rest is. One line, written at the moment
 * the head is closed so it can never be stale, and the merge at finalise leaves
 * it in place as a true record of where the head ended.
 */
fun tailContinuedMarker(name: String): String =
    "… this file ends here; the session continues in $name, and while it is running the " +
        "live end is always .log.t0 …"

/**
 * The heading over the digest of distinct errors, written at session end.
 *
 * @param distinct how many different error lines there were.
 * @param total how many error lines altogether, counting repeats.
 */
fun errorDigestHeading(distinct: Int, total: Int): String =
    if (distinct == 0) {
        "… no errors in this session …"
    } else {
        "… $distinct distinct error${if (distinct == 1) "" else "s"}, $total in total, " +
            "most frequent first …"
    }

/** One row of that digest: `×N  <source>  <text>`. */
fun errorDigestLine(source: LogSource, count: Int, text: String): String =
    "×$count  ${source.label}  $text"

/**
 * Said when the digest itself was capped.
 *
 * The digest exists because error lines are buried among hundreds of thousands
 * of others; a digest that silently kept only some of them would recreate that
 * problem one level up, which is the rule every other layer here already
 * follows.
 */
fun errorDigestElided(remaining: Int): String =
    "… and $remaining further distinct errors, not listed …"

/**
 * What the rate limiter says when it refuses output — now naming the source that
 * caused it.
 *
 * **The attribution is the whole point of the change.** The old wording,
 * `… logging rate-limited, 9214 lines dropped …`, is a true statement that
 * answers none of the three questions a reader has: what was shouting, whether
 * the thing they are looking for was in it, and what to turn down. Two separate
 * volume disasters — FEX at 508,000 lines and vkd3d's shader channel at 26,966 —
 * both had to be attributed afterwards by running `cut -c1-2 | sort | uniq -c`
 * over the whole file, and in both cases a single source was over 98% of it.
 * The limiter already knows which source it is refusing at the moment it
 * refuses, so it says so.
 *
 * @param worst the prefix key of the source that lost the most, or null when
 *   nothing was counted per source — in which case the line falls back to the
 *   old wording rather than inventing an attribution.
 */
fun rateLimitedLogMarker(dropped: Int, worst: String?, worstCount: Int): String =
    if (worst == null || worstCount <= 0) {
        rateLimitedLogMarker(dropped)
    } else {
        "… logging rate-limited, $dropped lines dropped, $worstCount of them $worst " +
            "(${describePrefix(worst)}) …"
    }

/**
 * `98% trace/fex` — the one thing worth saying about a session's histogram in a
 * list row.
 *
 * **The histogram's whole value is that one source usually dominates**, and in
 * both measured cases it dominated overwhelmingly: FEX at 99.9% of 508,000
 * lines, then vkd3d's shader channel at 98% of what was left. Neither was
 * suspected until somebody counted. A row that says `98% trace/fex` makes that
 * the first thing seen rather than the conclusion of an investigation.
 *
 * Null below [DOMINANCE_THRESHOLD], and that is the point of having a threshold
 * rather than always naming the largest bucket: a healthy session's biggest
 * source is `IW` at 30% of four hundred lines, which is not a finding, and a
 * badge that appears on every row stops being a signal — the same rule the
 * *errors logged* tag on that row already follows.
 *
 * @param counts [SessionLogMeta.sourceCounts], keyed by [logPrefixKey].
 */
fun dominantSourceLabel(counts: Map<String, Int>): String? {
    val total = counts.values.sum()
    if (total < MIN_LINES_FOR_DOMINANCE) return null
    val top = counts.maxByOrNull { it.value } ?: return null
    val share = top.value.toDouble() / total
    if (share < DOMINANCE_THRESHOLD) return null
    return "${(share * 100).toInt()}% ${describePrefix(top.key)}"
}

/**
 * Two thirds. Chosen against the artefact: a source over two thirds of a log is
 * one whose output is the log, which is the state worth naming, and no healthy
 * session measured here comes close to it.
 */
private const val DOMINANCE_THRESHOLD = 0.66

/**
 * Below this a share is arithmetic rather than evidence — three lines out of
 * four is 75% and means nothing at all.
 */
private const val MIN_LINES_FOR_DOMINANCE = 500

/**
 * `TF` as `trace/fex`, for a marker that has to be readable without the legend.
 *
 * Falls back to the raw key rather than to a guess: a prefix that does not
 * decode belongs to a build that wrote it, and printing it verbatim is what lets
 * somebody recognise it.
 */
fun describePrefix(key: String): String {
    if (key.length != 2) return key
    val level = LogLevel.ofWire(key[0]) ?: return key
    val source = LogSource.ofWire(key[1]) ?: return key
    return "${level.label}/${source.label}"
}

/** The one line that replaces the middle of a log that outgrew its cap. */
fun elidedLogMarker(lines: Int): String = "… $lines lines elided …"

/** What the sink says when it has been shouted at faster than it will write. */
fun rateLimitedLogMarker(dropped: Int): String =
    "… logging rate-limited, $dropped lines dropped …"

/**
 * Lines the producer channel threw away before the writer ever saw them.
 *
 * Distinct wording from [rateLimitedLogMarker] because it is a distinct failure:
 * the rate limiter is a policy the sink chose and this is the sink falling
 * behind, which means the *oldest* queued lines went and the gap is not where
 * the marker is. Named "before the sink" for that reason.
 */
fun overflowLogMarker(dropped: Int): String =
    "… $dropped lines dropped before the sink could write them …"

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

/**
 * Whether a line is a complaint that there is no display, from a phase that
 * deliberately has none.
 *
 * **Not general log-tidying, and deliberately not applied to a whole session.**
 * Building a prefix runs `wineboot`, `regedit`, `services.exe`, `rundll32` and
 * `explorer` before any X server is listening, and it does so on purpose:
 * [BOOTSTRAP_SESSION_ENV] withholds `DISPLAY` from those processes because
 * handing them the session environment was measured to stop `wineboot --init`
 * dead, two minutes in, with `drive_c` still empty. So `winex11.drv` fails
 * `process_attach`, win32u falls back to the null driver, and each of these
 * lines is emitted at error level — around ninety of them per provisioning
 * pass, describing a condition Vessel created on purpose and nobody can act on.
 *
 * The caller gates this on the display server actually being up, so the moment
 * one is listening the same text is an error again. That is the part that keeps
 * it honest: a driver that fails *during a session* still shouts, because then
 * the complaint is true.
 *
 * Matched on the diagnostic's own words rather than on the channel, because
 * `system:`, `winediag:` and `ole:` all contribute to the same cluster.
 */
fun isDisplayAbsenceDiagnostic(text: String): Boolean =
    DISPLAY_ABSENCE_MARKERS.any { it in text }

/**
 * The exact messages the null driver and its callers emit with no display.
 *
 * `win32u`'s `nodrv_CreateWindow` writes the first two, `lock_display_devices`
 * the third, and `ole32` the fourth when it cannot make the apartment window
 * every COM apartment needs. Substrings rather than whole lines: each arrives
 * with a process tag and, in the `ole` case, a trailing error number.
 */
private val DISPLAY_ABSENCE_MARKERS = listOf(
    "no driver could be loaded",
    "The explorer process failed to start.",
    "lock_display_devices Failed to read display config",
    "apartment_createwindowifneeded CreateWindow failed",
)
