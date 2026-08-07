package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the one piece of the logging feature with real logic, and the
 * one most likely to be broken by a Wine or DXVK release rather than by an edit
 * here. These are the shapes it has to keep getting right.
 */
class SessionLogParserTest {

    @Test
    fun `wine err carries the channel and loses the level token`() {
        val parsed = parseSessionLogLine("""err:module:import_dll Library d3dx9_43.dll not found""")
        assertEquals(LogLevel.ERROR, parsed.level)
        assertEquals(LogSource.WINE, parsed.source)
        assertEquals("""module:import_dll Library d3dx9_43.dll not found""", parsed.text)
    }

    @Test
    fun `fixme is a warning, because a stub is a warning about behaviour`() {
        val parsed = parseSessionLogLine("fixme:d3d:wined3d_check_device_format stub")
        assertEquals(LogLevel.WARN, parsed.level)
        assertEquals("d3d:wined3d_check_device_format stub", parsed.text)
    }

    @Test
    fun `wine warn and trace map to their own levels`() {
        assertEquals(LogLevel.WARN, parseSessionLogLine("warn:heap:HEAP_FindFreeBlock out").level)
        assertEquals(LogLevel.TRACE, parseSessionLogLine("trace:seh:call_handler entering").level)
    }

    @Test
    fun `pid and tid prefixes are skipped, and a channel is never mistaken for one`() {
        val withIds = parseSessionLogLine("0024:0028:err:module:import_dll missing")
        assertEquals(LogLevel.ERROR, withIds.level)
        assertEquals(LogSource.WINE, withIds.source)
        assertEquals("module:import_dll missing", withIds.text)

        val withTimestamp = parseSessionLogLine("0.012:0024:0028:warn:heap:blocked")
        assertEquals(LogLevel.WARN, withTimestamp.level)
        assertEquals("heap:blocked", withTimestamp.text)
    }

    @Test
    fun `a non-level word stops the prefix scan`() {
        // `module` is not a level and not a number, so the scan must stop rather
        // than walk into the line and read `import_dll` as a channel.
        val parsed = parseSessionLogLine("module:import_dll something happened")
        assertEquals(LogLevel.INFO, parsed.level)
        assertEquals(LogSource.WINE, parsed.source)
        assertEquals("module:import_dll something happened", parsed.text)
    }

    @Test
    fun `dxvk is recognised by its tag, not by its level token`() {
        val info = parseSessionLogLine("info:  DXVK: v2.7.1")
        assertEquals(LogSource.DXVK, info.source)
        assertEquals(LogLevel.INFO, info.level)
        assertEquals("DXVK: v2.7.1", info.text)

        val failed = parseSessionLogLine("err:   DXVK: Failed to create device")
        assertEquals(LogSource.DXVK, failed.source)
        assertEquals(LogLevel.ERROR, failed.level)
    }

    @Test
    fun `d3d11 and dxgi channels belong to the translation layer`() {
        assertEquals(LogSource.DXVK, parseSessionLogLine("warn:d3d11:something").source)
        assertEquals(LogSource.DXVK, parseSessionLogLine("err:dxgi:swapchain lost").source)
    }

    @Test
    fun `d3d12 and vkd3d resolve to vkd3d`() {
        assertEquals(LogSource.VKD3D, parseSessionLogLine("err:d3d12:create_device failed").source)
        assertEquals(LogSource.VKD3D, parseSessionLogLine("vkd3d_instance_init: no extension").source)
    }

    @Test
    fun `fex brackets are level markers and are consumed`() {
        val parsed = parseSessionLogLine("[ERR] Unhandled SIGSEGV in JIT")
        assertEquals(LogSource.FEX, parsed.source)
        assertEquals(LogLevel.ERROR, parsed.level)
        assertEquals("Unhandled SIGSEGV in JIT", parsed.text)

        assertEquals(LogLevel.WARN, parseSessionLogLine("[WARN] slow path").level)
        assertEquals(LogLevel.TRACE, parseSessionLogLine("[DEBUG] block compiled").level)
    }

    @Test
    fun `mesa prefixes carry the driver source and their own level`() {
        val error = parseSessionLogLine("MESA: error: Failed to create device")
        assertEquals(LogSource.DRIVER, error.source)
        assertEquals(LogLevel.ERROR, error.level)
        assertEquals("Failed to create device", error.text)

        val warning = parseSessionLogLine("MESA-INTEL: warning: performance support disabled")
        assertEquals(LogSource.DRIVER, warning.source)
        assertEquals(LogLevel.WARN, warning.level)
    }

    @Test
    fun `unrecognised output is wine info and is not guessed at`() {
        // The word "error" in prose must not turn a game's splash text red.
        val parsed = parseSessionLogLine("Loading assets, no error so far")
        assertEquals(LogSource.WINE, parsed.source)
        assertEquals(LogLevel.INFO, parsed.level)
        assertEquals("Loading assets, no error so far", parsed.text)
    }

    @Test
    fun `blank input is a blank line and not a crash`() {
        assertEquals("", parseSessionLogLine("").text)
        assertEquals("", parseSessionLogLine("   \r\n").text)
    }

    @Test
    fun `trailing newlines are stripped before anything else`() {
        assertEquals("heap:blocked", parseSessionLogLine("warn:heap:blocked\r\n").text)
    }
}

class SessionLogCodecTest {

    @Test
    fun `a line survives a round trip`() {
        val encoded = encodeLogLine(LogSource.DXVK, LogLevel.WARN, "DXVK: something odd")
        val decoded = decodeLogLine(encoded, index = 7)
        assertEquals(LogSource.DXVK, decoded.source)
        assertEquals(LogLevel.WARN, decoded.level)
        assertEquals("DXVK: something odd", decoded.text)
        assertEquals(7, decoded.index)
    }

    @Test
    fun `embedded newlines become spaces so one stored line is one line`() {
        val encoded = encodeLogLine(LogSource.WINE, LogLevel.INFO, "first\nsecond\r\nthird")
        assertEquals(1, encoded.lines().size)
        assertEquals("first second  third", decodeLogLine(encoded, 0).text)
    }

    @Test
    fun `an overlong line is cut and says so`() {
        val encoded = encodeLogLine(LogSource.WINE, LogLevel.INFO, "x".repeat(20_000))
        val text = decodeLogLine(encoded, 0).text
        assertEquals(MAX_LOG_LINE_CHARS + 1, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `a corrupt line is shown rather than refused`() {
        val decoded = decodeLogLine("this has no prefix at all", 3)
        assertEquals(LogSource.VESSEL, decoded.source)
        assertEquals(LogLevel.INFO, decoded.level)
        assertEquals("this has no prefix at all", decoded.text)
    }

    @Test
    fun `a run of one line carries no decoration`() {
        assertEquals("stub", repeatedLogLine("stub", 1))
        assertEquals("stub  ×1204", repeatedLogLine("stub", 1204))
    }

    @Test
    fun `the markers name their own numbers`() {
        assertEquals("… 40122 lines elided …", elidedLogMarker(40_122))
        assertEquals("… logging rate-limited, 8412 lines dropped …", rateLimitedLogMarker(8_412))
    }
}

class LogFilterTest {

    @Test
    fun `problems keeps errors and warnings only`() {
        assertTrue(LogFilter.PROBLEMS.accepts(LogLevel.ERROR))
        assertTrue(LogFilter.PROBLEMS.accepts(LogLevel.WARN))
        assertTrue(!LogFilter.PROBLEMS.accepts(LogLevel.INFO))
        assertTrue(!LogFilter.PROBLEMS.accepts(LogLevel.TRACE))
        LogLevel.entries.forEach { assertTrue(LogFilter.ALL.accepts(it)) }
    }
}
