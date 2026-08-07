package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The failures the launcher can name, pinned to the lines that produce them.
 *
 * Every string here is real output — from this device, from `docs/LOGGING.md`, or
 * from the Wine and DXVK source those were read against. That matters more than
 * usual: a matcher that has drifted does not fail, it just stops recognising the
 * one line a user needed explained, and the Failed screen quietly falls back to
 * showing them `err:virtual:map_image_into_view failed to set 60000020
 * protection` and nothing else.
 */
class SessionDiagnosisTest {

    private fun idOf(line: String) = diagnoseSessionLine(line)?.id

    @Test
    fun `the executable-mapping failure is recognised, in both of its forms`() {
        assertEquals(
            "noexec",
            idOf(
                "err:virtual:map_image_into_view failed to set 60000020 protection on " +
                    "ntdll.dll section .text, noexec filesystem?",
            ),
        )
        assertEquals("noexec", idOf("err:virtual:something noexec filesystem?"))
    }

    @Test
    fun `a missing ntdll points at the Wine package rather than at the container`() {
        assertEquals(
            "ntdll",
            idOf(
                "wine: could not load ntdll.so: dlopen failed: library " +
                    "\"/apex/com.android.runtime/bin/../lib/wine/aarch64-unix/ntdll.so\" not found",
            ),
        )
    }

    @Test
    fun `the wineserver bind failure is recognised`() {
        assertEquals("server-bind", idOf("wineserver: bind: Permission denied"))
    }

    @Test
    fun `a missing display is recognised however the layer spells it`() {
        assertEquals("no-display", idOf("winex11.drv: Can't open display: :0"))
        assertEquals("no-display", idOf("Application tried to create a window, but cannot open display"))
    }

    @Test
    fun `DXVK naming a missing feature is recognised as a driver problem`() {
        assertEquals(
            "vulkan-device",
            idOf("warn:  Device does not support required feature 'depthClipEnable' (extension: VK_EXT_depth_clip_enable)"),
        )
        assertEquals("vulkan-device", idOf("warn:  Device does not support Vulkan 1.3"))
    }

    @Test
    fun `an unsupported instruction is attributed to the translator, not to Wine`() {
        assertEquals("illegal-instruction", idOf("wine: Unhandled illegal instruction at address 00007FF..."))
    }

    @Test
    fun `a stubbed API is recognised`() {
        assertEquals(
            "unimplemented",
            idOf("wine: Call from 00007F to unimplemented function api-ms-win.dll.Foo, aborting"),
        )
    }

    @Test
    fun `an ordinary line is not diagnosed, because a confident wrong answer is worse`() {
        assertNull(idOf("trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\dxgi.dll\""))
        assertNull(idOf("info:  DXVK: Game: notepad.exe"))
        assertNull(idOf(""))
        assertNull(idOf("err:some:channel a failure nobody has classified yet"))
    }

    @Test
    fun `the first recognised line wins, because a failure cascades`() {
        // The mapping failure comes first and causes the display failure below
        // it; showing the later one would name the symptom and hide the cause.
        val lines = listOf(
            "info:  DXVK: Game: notepad.exe",
            "err:virtual:map_image_into_view failed to set 60000020 protection",
            "winex11.drv: Can't open display: :0",
        )
        assertEquals("noexec", diagnoseSession(lines)?.id)
    }

    @Test
    fun `a clean session has no diagnosis at all`() {
        assertNull(diagnoseSession(listOf("info: fine", "trace: also fine")))
        assertNull(diagnoseSession(emptyList()))
    }

    @Test
    fun `every diagnosis says something a person can act on`() {
        val lines = listOf(
            "err:virtual:map_image_into_view failed",
            "wine: could not load ntdll.so",
            "wineserver: bind: Permission denied",
            "Can't open display",
            "Device does not support Vulkan 1.3",
            "wine: Unhandled illegal instruction",
            "wine: Call to unimplemented function",
        )
        for (line in lines) {
            val diagnosis = diagnoseSessionLine(line)!!
            assert(diagnosis.headline.isNotBlank()) { "no headline for $line" }
            assert(diagnosis.detail.length > diagnosis.headline.length) { "no detail for $line" }
        }
    }
}
