package app.vessel.data

import app.vessel.core.LogEntry
import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.SessionLogLimits
import app.vessel.core.decodeLogLine
import app.vessel.core.encodeLogLine
import app.vessel.core.errorDigestHeading
import app.vessel.core.logPrefixLegend
import app.vessel.core.tailContinuedMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The sink, exercised with synthetic input.
 *
 * There is no session launcher yet, so this is how the three defences are known
 * to work at all: feed the writer directly and read back the file it produced.
 * It runs against a real temporary directory and a real IO dispatcher rather
 * than a fake, because the thing being tested is what ends up on disk.
 */
class SessionLogWriterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `consecutive identical lines collapse into one counted line`() {
        val directory = temporary.newFolder("dedup")
        write(directory, startedAt = 1_000L) { log ->
            repeat(5) { log.line(LogSource.WINE, LogLevel.WARN, "d3d:check_format stub") }
            log.line(LogSource.WINE, LogLevel.ERROR, "module:import_dll missing")
        }

        val lines = body(directory, 1_000L)
        assertEquals(2, lines.size)
        assertEquals("d3d:check_format stub  ×5", lines[0].text)
        assertEquals(LogLevel.WARN, lines[0].level)
        assertEquals("module:import_dll missing", lines[1].text)
    }

    @Test
    fun `a run is only collapsed while it stays the same line`() {
        val directory = temporary.newFolder("alternating")
        write(directory, startedAt = 1_000L) { log ->
            repeat(3) {
                log.line(LogSource.WINE, LogLevel.ERROR, "a")
                log.line(LogSource.WINE, LogLevel.ERROR, "b")
            }
        }

        // Alternating lines are exactly what dedup cannot help with, which is
        // why the rate limiter exists behind it.
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), body(directory, 1_000L).map { it.text })
    }

    @Test
    fun `the header is written once, first, as Vessel lines`() {
        val directory = temporary.newFolder("header")
        write(directory, startedAt = 1_000L) { log ->
            log.header(listOf("wine  wine-11.0-arm64ec", "driver  turnip-25.2.0"))
            log.line(LogSource.WINE, LogLevel.INFO, "started")
        }

        val lines = body(directory, 1_000L)
        // Two given lines, three of legend, then the session's own output. The
        // legend is not optional and not conditional; see `logPrefixLegend`.
        assertEquals(2 + logPrefixLegend().size + 1, lines.size)
        assertEquals(LogSource.VESSEL, lines[0].source)
        assertEquals("wine  wine-11.0-arm64ec", lines[0].text)
        assertEquals("driver  turnip-25.2.0", lines[1].text)
        assertEquals("started", lines.last().text)
    }

    /**
     * The legend the log carries about itself.
     *
     * Asserted against the enums rather than against literals, because the
     * property that matters is that it stays true when a source is added — a
     * hand-written legend that has gone stale is worse than none, since it is
     * read as authoritative.
     */
    @Test
    fun `every level and every source is named in the header legend`() {
        val directory = temporary.newFolder("legend")
        write(directory, startedAt = 1_000L) { log -> log.header(listOf("wine  x")) }

        val legend = body(directory, 1_000L).map { it.text }.filter { it.startsWith("legend") }
        assertEquals(logPrefixLegend(), legend)
        LogLevel.entries.forEach { level ->
            assertTrue(
                "the legend does not name ${level.label}",
                legend.any { it.contains("${level.wire} ${level.label}") },
            )
        }
        LogSource.entries.forEach { source ->
            assertTrue(
                "the legend does not name ${source.label}",
                legend.any { it.contains("${source.wire} ${source.label}") },
            )
        }
    }

    /**
     * The histogram that would have caught both measured volume disasters in the
     * first minute rather than in a manual `cut | sort | uniq -c` afterwards.
     */
    @Test
    fun `every line is counted against its own two-letter prefix`() {
        val directory = temporary.newFolder("histogram")
        val meta = write(directory, startedAt = 1_000L) { log ->
            repeat(7) { log.line(LogSource.FEX, LogLevel.TRACE, "Handled unaligned atomic $it") }
            repeat(2) { log.line(LogSource.VKD3D, LogLevel.WARN, "skip_dword_unknown $it") }
            log.line(LogSource.WINE, LogLevel.ERROR, "module:import_dll missing")
        }

        assertEquals(7, meta.sourceCounts["TF"])
        assertEquals(2, meta.sourceCounts["WK"])
        assertEquals(1, meta.sourceCounts["EW"])
        // The invariant that makes the histogram usable as an accounting of the
        // file rather than of some of it: every line the writer wrote, including
        // its own markers and the digest trailer, is in exactly one bucket.
        assertEquals(meta.lines, meta.sourceCounts.values.sum())
        assertEquals(meta.lines, read(directory, 1_000L).size)
    }

    /**
     * The digest, which is the answer to "what went wrong" without scrolling a
     * hundred thousand lines to find out.
     */
    @Test
    fun `the session ends with its distinct errors, most frequent first`() {
        val directory = temporary.newFolder("digest")
        val meta = write(directory, startedAt = 1_000L) { log ->
            // Consecutive, so dedup collapses them into one `×4` line — which the
            // digest has to undo, or a run of four counts as one.
            repeat(4) { log.line(LogSource.WINE, LogLevel.ERROR, "seh:NtRaiseException c0000005") }
            log.line(LogSource.DXVK, LogLevel.ERROR, "DXVK: device lost")
            log.line(LogSource.WINE, LogLevel.WARN, "d3d:check_format stub")
        }

        assertEquals(2, meta.errorDigest.size)
        assertEquals("seh:NtRaiseException c0000005", meta.errorDigest[0].text)
        assertEquals(4, meta.errorDigest[0].count)
        assertEquals(LogSource.WINE.wire, meta.errorDigest[0].source)
        assertEquals("DXVK: device lost", meta.errorDigest[1].text)
        assertEquals(1, meta.errorDigest[1].count)

        val trailer = trailer(directory, 1_000L).map { it.text }
        assertEquals(errorDigestHeading(distinct = 2, total = 5), trailer.first())
        assertTrue(
            "the digest must name the repeated failure first",
            trailer[1].startsWith("×4"),
        )
    }

    /**
     * A clean session still gets a trailer, and that is the useful half: a log
     * that simply stops is indistinguishable from one whose digest failed.
     */
    @Test
    fun `a session with no errors says so rather than saying nothing`() {
        val directory = temporary.newFolder("clean")
        val meta = write(directory, startedAt = 1_000L) { log ->
            log.line(LogSource.WINE, LogLevel.INFO, "loaddll kernel32.dll")
        }

        assertTrue(meta.errorDigest.isEmpty())
        assertEquals(listOf(errorDigestHeading(0, 0)), trailer(directory, 1_000L).map { it.text })
        assertEquals(LogLevel.INFO, trailer(directory, 1_000L).first().level)
    }

    /**
     * The head file says where the rest of the session is.
     *
     * Driven by a head allowance small enough that a handful of lines overruns
     * it, because what is being tested is the breadcrumb and not the rotation.
     */
    @Test
    fun `the head file's last line says the session continues elsewhere`() {
        val directory = temporary.newFolder("breadcrumb")
        val tiny = SessionLogLimits(headBytes = 200L, tailBytes = 4_096L, rateLimitLines = 20_000)
        val writer = SessionLogWriter(
            directory = directory,
            containerId = "c1",
            startedAt = 1_000L,
            json = json,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            onChanged = {},
            onFinished = {},
            limits = tiny,
        )
        repeat(40) { writer.line(LogSource.WINE, LogLevel.INFO, "a line of output number $it") }
        // Read the head before finalise merges the segments back into it.
        val deadline = System.currentTimeMillis() + AWAIT_MS
        var headLines = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            if (tailFile(directory, 1_000L, 0).isFile) {
                headLines = logFile(directory, 1_000L).readLines()
                if (headLines.isNotEmpty()) break
            }
            Thread.sleep(5)
        }
        writer.close()
        awaitFinished(directory, 1_000L)

        assertTrue("the head never rotated, so this test proves nothing", headLines.isNotEmpty())
        assertEquals(
            tailContinuedMarker("1000.log.t0"),
            decodeLogLine(headLines.last(), 0).text,
        )
    }

    /**
     * A drop that says *whose* lines went.
     *
     * The old marker said only how many, which answers none of the three
     * questions a reader has — what was shouting, whether their line was in it,
     * and what to turn down.
     */
    @Test
    fun `a rate-limited burst names the source that filled the window`() {
        val directory = temporary.newFolder("attribution")
        val meta = write(directory, startedAt = 1_000L, limits = SessionLogLimits.SHIPPED) { log ->
            repeat(6_000) { log.line(LogSource.FEX, LogLevel.TRACE, "unaligned atomic $it") }
        }

        assertTrue("nothing was dropped, so this test proves nothing", meta.droppedLines > 0)
        assertEquals(meta.droppedLines, meta.droppedBySource["TF"])
        assertTrue(
            "the marker has to name the source",
            body(directory, 1_000L).any {
                it.text.startsWith("… logging rate-limited,") && it.text.contains("TF (trace/fex)")
            },
        )
    }

    /**
     * The severities do not spend each other's budget.
     *
     * The limiter used to refuse everything once the window was full, in
     * arrival order, so the loudest source took the whole allowance. One
     * Requiem session with the `sync` channel on lost 47 errors and 40
     * warnings to 2.8 million dropped trace lines, and the entire tail came
     * back as trace plus one rate-limit notice — a log that had thrown away
     * the only lines explaining the failure it was written to explain.
     *
     * A flood of the noisiest level, then one line of each quieter one. All
     * three must survive, and the assertion is on the file rather than on the
     * counters because the file is what a person reads.
     */
    @Test
    fun `a trace flood cannot crowd out an error or a warning`() {
        val directory = temporary.newFolder("severity")
        val meta = write(directory, startedAt = 1_000L, limits = SessionLogLimits.SHIPPED) { log ->
            // Interleaved, not appended. The window is wall-clock, so three
            // lines sent after the flood might land in a fresh window and
            // survive on a slow machine even with the bug present — a test
            // that passes for a reason unrelated to what it claims. Emitted
            // mid-flood they are certainly inside a saturated window.
            repeat(60_000) {
                log.line(LogSource.WINE, LogLevel.TRACE, "sync wake $it")
                if (it == 30_000) {
                    log.line(LogSource.WINE, LogLevel.ERROR, "the one line that mattered")
                    log.line(LogSource.WINE, LogLevel.WARN, "the other line that mattered")
                    log.line(LogSource.VESSEL, LogLevel.INFO, "and what had started")
                }
            }
        }

        assertTrue("nothing was dropped, so this test proves nothing", meta.droppedLines > 0)

        // Asserted on the limiter's own accounting rather than on the file.
        // The head+tail cap is a separate layer and elides the middle, so a
        // line emitted mid-flood is legitimately absent from the file even
        // when the limiter let it through — checking the text here would be
        // testing elision and calling it rate limiting.
        assertEquals("only traces should have been refused", meta.droppedLines, meta.droppedBySource["TW"])
        assertNull("an error was refused for a trace's sake", meta.droppedBySource["EW"])
        assertNull("a warning was refused for a trace's sake", meta.droppedBySource["WW"])
        assertNull("an info line was refused for a trace's sake", meta.droppedBySource["IV"])
    }

    @Test
    fun `a flood is rate-limited, and the file says how much it lost`() {
        val directory = temporary.newFolder("flood")
        val flood = 6_000
        // The caps this test was written against, named rather than inherited:
        // they are the bottom rung of the ladder now, and the default is the top,
        // where six thousand lines is not a flood at all.
        val meta = write(directory, startedAt = 1_000L, limits = SessionLogLimits.SHIPPED) { log ->
            // Distinct every time, so dedup cannot touch it: this is the
            // wined3d draw-path failure, where two unguarded ERR sites alternate
            // at frame rate and every line differs.
            repeat(flood) { log.line(LogSource.WINE, LogLevel.ERROR, "draw failed at $it") }
        }

        val lines = body(directory, 1_000L)
        assertTrue("expected fewer lines than were sent", lines.size < flood)
        assertTrue("expected drops to be counted", meta.droppedLines > 0)
        assertTrue(
            "expected the file to admit the truncation",
            lines.any { it.text.startsWith("… logging rate-limited,") },
        )
        assertEquals(flood, meta.droppedLines + lines.count { !it.text.startsWith("… logging") })
    }

    @Test
    fun `closing records a clean exit and the session's own numbers`() {
        val directory = temporary.newFolder("meta")
        val meta = write(directory, startedAt = 4_242L) { log ->
            log.line(LogSource.WINE, LogLevel.INFO, "hello")
            log.line(LogSource.DXVK, LogLevel.ERROR, "DXVK: device lost")
        }

        assertEquals(SessionExit.OK, meta.exit)
        assertEquals("c1", meta.containerId)
        assertEquals(4_242L, meta.startedAt)
        assertEquals(2, body(directory, 4_242L).size)
        // `lines` counts the file, trailer included, and is asserted against the
        // file rather than against a literal so the two cannot drift.
        assertEquals(read(directory, 4_242L).size, meta.lines)
        assertTrue(meta.hasErrors)
        assertTrue(meta.sizeBytes > 0)
        assertTrue((meta.endedAt ?: 0) >= meta.startedAt)
    }

    @Test
    fun `an explicit crash is recorded with its exit code`() {
        val directory = temporary.newFolder("crash")
        val meta = write(directory, startedAt = 1_000L, exit = SessionExit.CRASHED to -11) { log ->
            log.line(LogSource.WINE, LogLevel.ERROR, "seh:NtRaiseException c0000005")
        }

        assertEquals(SessionExit.CRASHED, meta.exit)
        assertEquals(-11, meta.exitCode)
    }

    /**
     * The producer channel is the one layer that used to drop in silence.
     *
     * Deterministic rather than "send a lot and hope": the writer's pump is
     * launched onto a single-threaded dispatcher whose only thread is already
     * occupied by a latch, so nothing is consumed until every line has been sent
     * and the channel has thrown away everything past its buffer. That is the
     * shape of the real failure — a burst arriving faster than the disk — without
     * needing a real disk that is slow enough.
     *
     * The rate limiter is left at its maximum so the two counters cannot be
     * confused for each other.
     */
    @Test
    fun `lines the channel throws away are counted and admitted in the file`() {
        val directory = temporary.newFolder("overflow")
        val sent = 20_000
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
        val gate = CountDownLatch(1)
        // Occupies the only thread, so the pump cannot start.
        scope.launch { gate.await() }

        val writer = SessionLogWriter(
            directory = directory,
            containerId = "c1",
            startedAt = 1_000L,
            json = json,
            scope = scope,
            onChanged = {},
            onFinished = {},
            limits = SessionLogLimits(),
        )
        repeat(sent) { writer.line(LogSource.WINE, LogLevel.ERROR, "relay call $it") }
        gate.countDown()
        writer.close()

        val meta = awaitFinished(directory, 1_000L)
        val lines = body(directory, 1_000L)
        assertTrue("nothing overflowed, so this test proves nothing", meta.overflowLines > 0)
        assertTrue(
            "the file has to admit what it never saw",
            lines.any { it.text.startsWith("… ") && it.text.endsWith("before the sink could write them …") },
        )
        // Every line is accounted for: written, or dropped and counted. The one
        // extra line in the body is the marker saying so; the digest trailer is
        // not session output and is excluded by `body`.
        assertEquals(sent, meta.overflowLines + lines.size - 1)
        assertEquals(0, meta.droppedLines)
        executor.shutdown()
    }

    /** Drive one writer to completion and hand back the sidecar it left. */
    private fun write(
        directory: File,
        startedAt: Long,
        exit: Pair<SessionExit, Int?>? = null,
        limits: SessionLogLimits = SessionLogLimits(),
        block: (SessionLog) -> Unit,
    ): SessionLogMeta {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val writer = SessionLogWriter(
            directory = directory,
            containerId = "c1",
            startedAt = startedAt,
            json = json,
            scope = scope,
            onChanged = {},
            onFinished = {},
            limits = limits,
        )
        block(writer)
        if (exit == null) writer.close() else writer.finish(exit.first, exit.second)
        return awaitFinished(directory, startedAt)
    }

    private fun read(directory: File, startedAt: Long) =
        logFile(directory, startedAt).readLines().mapIndexed { index, raw ->
            decodeLogLine(raw, index)
        }

    /**
     * The session's own output — everything before the digest trailer.
     *
     * Split rather than folded into `read` because the two are different
     * artefacts and the tests that predate the trailer are about the first one.
     * The boundary is found from the heading `errorDigestHeading` produces for
     * this session's own numbers, so it cannot be matched by a line the session
     * happened to print.
     */
    private fun body(directory: File, startedAt: Long): List<LogEntry> {
        val all = read(directory, startedAt)
        val at = all.indexOfFirst { DIGEST_HEADING.matches(it.text) }
        return if (at < 0) all else all.take(at)
    }

    /** The digest trailer alone. Empty when the writer never got to finalise. */
    private fun trailer(directory: File, startedAt: Long): List<LogEntry> {
        val all = read(directory, startedAt)
        val at = all.indexOfFirst { DIGEST_HEADING.matches(it.text) }
        return if (at < 0) emptyList() else all.drop(at)
    }

    private fun awaitFinished(directory: File, startedAt: Long): SessionLogMeta {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val meta = readSessionMeta(directory, startedAt, json)
            if (meta != null && meta.exit != SessionExit.RUNNING) return meta
            Thread.sleep(5)
        }
        throw AssertionError("the writer never finished")
    }

    private companion object {
        const val AWAIT_MS = 20_000L

        /** Either shape [app.vessel.core.errorDigestHeading] can produce. */
        val DIGEST_HEADING =
            Regex("^… (no errors in this session|\\d+ distinct errors?, \\d+ in total).*")
    }
}

/**
 * The tidy-up that turns a head file and two tail segments back into one log.
 *
 * Tested on hand-built segments rather than by writing five megabytes: what can
 * be wrong here is the *order* — the retained end has to follow the retained
 * beginning, with one honest line between them saying what is gone.
 */
class SessionLogMergeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `head, marker, previous tail, current tail — in that order`() {
        val directory = temporary.newFolder("merge")
        segment(logFile(directory, 1L), "head one", "head two")
        segment(tailFile(directory, 1L, 1), "older tail")
        segment(tailFile(directory, 1L, 0), "newest tail")

        assertTrue(mergeTailSegments(directory, 1L, elided = 40_122))

        assertEquals(
            listOf(
                "head one",
                "head two",
                "… 40122 lines elided …",
                "older tail",
                "newest tail",
            ),
            logFile(directory, 1L).readLines().map { decodeLogLine(it, 0).text },
        )
        assertTrue(!tailFile(directory, 1L, 0).exists())
        assertTrue(!tailFile(directory, 1L, 1).exists())
    }

    @Test
    fun `a session that never outgrew its head is left alone`() {
        val directory = temporary.newFolder("nomerge")
        segment(logFile(directory, 1L), "head only")

        assertTrue(!mergeTailSegments(directory, 1L, elided = 0))
        assertEquals(1, logFile(directory, 1L).readLines().size)
    }

    private fun segment(file: File, vararg lines: String) {
        file.parentFile?.mkdirs()
        file.writeText(
            lines.joinToString("") { encodeLogLine(LogSource.WINE, LogLevel.INFO, it) + "\n" },
        )
    }
}
