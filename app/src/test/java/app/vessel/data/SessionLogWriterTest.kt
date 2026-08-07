package app.vessel.data

import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.core.decodeLogLine
import app.vessel.core.encodeLogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

        val lines = read(directory, 1_000L)
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
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), read(directory, 1_000L).map { it.text })
    }

    @Test
    fun `the header is written once, first, as Vessel lines`() {
        val directory = temporary.newFolder("header")
        write(directory, startedAt = 1_000L) { log ->
            log.header(listOf("wine  wine-11.0-arm64ec", "driver  turnip-25.2.0"))
            log.line(LogSource.WINE, LogLevel.INFO, "started")
        }

        val lines = read(directory, 1_000L)
        assertEquals(3, lines.size)
        assertEquals(LogSource.VESSEL, lines[0].source)
        assertEquals("wine  wine-11.0-arm64ec", lines[0].text)
        assertEquals("driver  turnip-25.2.0", lines[1].text)
        assertEquals("started", lines[2].text)
    }

    @Test
    fun `a flood is rate-limited, and the file says how much it lost`() {
        val directory = temporary.newFolder("flood")
        val flood = 6_000
        val meta = write(directory, startedAt = 1_000L) { log ->
            // Distinct every time, so dedup cannot touch it: this is the
            // wined3d draw-path failure, where two unguarded ERR sites alternate
            // at frame rate and every line differs.
            repeat(flood) { log.line(LogSource.WINE, LogLevel.ERROR, "draw failed at $it") }
        }

        val lines = read(directory, 1_000L)
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
        assertEquals(2, meta.lines)
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

    /** Drive one writer to completion and hand back the sidecar it left. */
    private fun write(
        directory: File,
        startedAt: Long,
        exit: Pair<SessionExit, Int?>? = null,
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
        )
        block(writer)
        if (exit == null) writer.close() else writer.finish(exit.first, exit.second)
        return awaitFinished(directory, startedAt)
    }

    private fun read(directory: File, startedAt: Long) =
        logFile(directory, startedAt).readLines().mapIndexed { index, raw ->
            decodeLogLine(raw, index)
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
