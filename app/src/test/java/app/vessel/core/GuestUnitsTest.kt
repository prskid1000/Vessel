package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Attribution, on lines copied out of a real session log.
 *
 * Every input here is a verbatim line from the log of a Metro 2033 Redux launch
 * on the device — the run whose last entry was a DLL load and which gave no clue
 * which process had died.
 */
class GuestUnitsTest {

    private fun parseAndLabel(units: GuestUnits, raw: String): String? {
        val parsed = parseSessionLogLine(raw)
        return units.label(parsed.unit, parsed.text)
    }

    @Test
    fun `FEX announces a program and every later line of that thread is named`() {
        val units = GuestUnits()
        assertEquals(
            "metro.exe",
            parseAndLabel(units, "D F8 Load module metro.exe (metro.exe-1b95a19fe798bdda): 140000000"),
        )
        // A DLL loaded by the same thread is still that program.
        assertEquals(
            "metro.exe",
            parseAndLabel(units, "D F8 Load module d3dx11_43.dll (d3dx11_43.dll-b2aeef0c026781c5): 7FDCD70000"),
        )
    }

    @Test
    fun `a dll never renames the thread that loaded it`() {
        val units = GuestUnits()
        parseAndLabel(units, "D F8 Load module metro.exe (metro.exe-1b95a19fe798bdda): 140000000")
        parseAndLabel(units, "D F8 Load module wined3d.dll (wined3d.dll-e56dbe6d43b2f666): 7FDC650000")
        parseAndLabel(units, "D F8 Load module opengl32.dll (opengl32.dll-9fc88fc2544f2d0c): 7FDC610000")
        // The last thing this thread loaded was Zink. It is still the game.
        assertEquals(
            "metro.exe",
            parseAndLabel(units, "D F8 Load module libgallium_wgl.dll (libgallium_wgl.dll-31dd): 7FDB290000"),
        )
    }

    @Test
    fun `Wine's own form is read, and the drive letter is not part of the name`() {
        val units = GuestUnits()
        val line = """00f8:trace:loaddll:build_module Loaded L"D:\Games\Metro Redux\2033\metro.exe" at 140000000: native"""
        assertEquals("metro.exe", parseAndLabel(units, line))
    }

    @Test
    fun `two programs on two threads keep their own names`() {
        val units = GuestUnits()
        parseAndLabel(units, "D F8 Load module metro.exe (metro.exe-1b95): 140000000")
        parseAndLabel(units, "D 2B8 Load module explorer.exe (explorer.exe-7d4f): 140000000")
        assertEquals("metro.exe", parseAndLabel(units, "D F8 Load module d3d11.dll (d3d11.dll-aa): 7000"))
        assertEquals("explorer.exe", parseAndLabel(units, "D 2B8 Load module user32.dll (user32.dll-bb): 8000"))
    }

    @Test
    fun `leading zeroes are the same thread`() {
        // Wine pads to four hex digits and FEX does not, and they are the same
        // thread. Without normalising, a game named by FEX would go unnamed for
        // every line Wine wrote about it.
        val units = GuestUnits()
        parseAndLabel(units, "D F8 Load module metro.exe (metro.exe-1b95): 140000000")
        val wine = """00f8:err:module:import_dll Library opengl32.dll not found"""
        assertEquals("metro.exe", parseAndLabel(units, wine))
    }

    @Test
    fun `a line from an unknown thread is not guessed at`() {
        val units = GuestUnits()
        parseAndLabel(units, "D F8 Load module metro.exe (metro.exe-1b95): 140000000")
        assertNull(parseAndLabel(units, "01a4:err:seh:something from a thread nobody announced"))
    }

    @Test
    fun `output with no thread prefix has no owner`() {
        val units = GuestUnits()
        parseAndLabel(units, "D F8 Load module metro.exe (metro.exe-1b95): 140000000")
        // DXVK's own lines carry no unit — they must not inherit the last one.
        assertNull(parseAndLabel(units, "info:  DXVK: v2.7.1"))
        assertNull(parseAndLabel(units, "err:   DxvkInstance::createInstance: Failed"))
    }

    @Test
    fun `FEX's unbracketed form is parsed as FEX rather than as prose`() {
        val parsed = parseSessionLogLine("D F8 Load module metro.exe (metro.exe-1b95): 140000000")
        assertEquals(LogSource.FEX, parsed.source)
        assertEquals(LogLevel.TRACE, parsed.level)
        assertEquals("F8", parsed.unit)
        assertEquals("Load module metro.exe (metro.exe-1b95): 140000000", parsed.text)
    }

    @Test
    fun `an assertion from FEX is an error, because that is the line that matters`() {
        // FEX dies on a forced assert with an `A` line. It was being read as
        // prose at INFO, which is the one level nobody scrolls back for.
        val parsed = parseSessionLogLine("A 1F4 FEXCore::Assert::ForcedAssert")
        assertEquals(LogSource.FEX, parsed.source)
        assertEquals(LogLevel.ERROR, parsed.level)
        assertEquals("1F4", parsed.unit)
    }
}
