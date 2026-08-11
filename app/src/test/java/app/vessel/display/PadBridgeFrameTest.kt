package app.vessel.display

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The twenty bytes on the wire, checked against the offsets `bus_vessel.c` reads.
 *
 * **This test exists because the other end of this format is C in a Wine patch.**
 * A field at the wrong offset compiles perfectly on both sides and is invisible
 * until a controller is in somebody's hands, on a device, behind a forty-minute
 * build and a full reinstall — which is the most expensive place in this project
 * to discover a typo. The numbers below are transcribed from
 * `patches/wine/0016`'s `vessel_set_state`, not from `PadBridge`, so the two
 * disagreeing is a failure here rather than a pad that does nothing there.
 */
class PadBridgeFrameTest {

    private fun frame(state: PadBridge.State) =
        PadBridge.frame(PadBridge.MSG_STATE, index = 1, state = state)

    private fun i16(f: ByteArray, at: Int): Int {
        val raw = (f[at].toInt() and 0xFF) or ((f[at + 1].toInt() and 0xFF) shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    @Test
    fun `a frame is exactly twenty bytes`() {
        assertEquals(20, frame(PadBridge.State()).size)
        assertEquals(20, PadBridge.FRAME)
    }

    @Test
    fun `the type and the slot are the first two bytes`() {
        val f = frame(PadBridge.State())
        assertEquals(PadBridge.MSG_STATE, f[0].toInt())
        assertEquals(1, f[1].toInt())
    }

    /**
     * Bit *n* is HID button usage *n + 1*, which is the order `xinput1_3` reads:
     * A, B, X, Y, LB, RB, Back, Start, LThumb, RThumb.
     */
    @Test
    fun `buttons are a little-endian mask at offset two`() {
        val state = PadBridge.State(
            buttons = (1 shl PadBridge.BTN_A) or (1 shl PadBridge.BTN_START),
        )
        val f = frame(state)
        assertEquals(0, PadBridge.BTN_A)
        assertEquals(7, PadBridge.BTN_START)
        assertEquals(0b1000_0001, f[2].toInt() and 0xFF)
        assertEquals(0, f[3].toInt())
    }

    @Test
    fun `the hat is a mask at offset four, so two directions can be held`() {
        val f = frame(PadBridge.State(hat = PadBridge.HAT_UP or PadBridge.HAT_RIGHT))
        assertEquals(PadBridge.HAT_UP or PadBridge.HAT_RIGHT, f[4].toInt())
        assertEquals(0b1001, f[4].toInt())
    }

    /** Four signed axes at 6, 8, 10, 12 — the order the bus reads them in. */
    @Test
    fun `the sticks are signed sixteen-bit at six through thirteen`() {
        val f = frame(PadBridge.State(lx = 1000, ly = -2000, rx = 32767, ry = -32768))
        assertEquals(1000, i16(f, 6))
        assertEquals(-2000, i16(f, 8))
        assertEquals(32767, i16(f, 10))
        assertEquals(-32768, i16(f, 12))
    }

    @Test
    fun `the triggers are unsigned at fourteen and sixteen`() {
        val f = frame(PadBridge.State(lt = 0, rt = PadBridge.AXIS_MAX))
        assertEquals(0, i16(f, 14))
        assertEquals(32767, i16(f, 16))
    }

    /**
     * The two spare bytes stay zero.
     *
     * Not pedantry: byte 5 and bytes 18-19 are the only room the format has to
     * grow, and a frame that put rubbish there once would make the first reader
     * that starts checking them fail for a reason nobody would look for.
     */
    @Test
    fun `the reserved bytes are zero`() {
        val f = frame(PadBridge.State(buttons = 0xFFFF, hat = 0xF, lx = -1, rt = 32767))
        assertEquals(0, f[5].toInt())
        assertEquals(0, f[18].toInt())
        assertEquals(0, f[19].toInt())
    }

    /** An added or removed pad carries the slot and nothing else worth reading. */
    @Test
    fun `arrival and departure name the slot`() {
        val added = PadBridge.frame(PadBridge.MSG_ADDED, 2, PadBridge.State())
        assertEquals(PadBridge.MSG_ADDED, added[0].toInt())
        assertEquals(2, added[1].toInt())

        val gone = PadBridge.frame(PadBridge.MSG_REMOVED, 3, PadBridge.State())
        assertEquals(PadBridge.MSG_REMOVED, gone[0].toInt())
        assertEquals(3, gone[1].toInt())
    }

    /**
     * Four slots, because XInput has four.
     *
     * Pinned rather than assumed: the bus indexes a fixed array with the byte at
     * offset one, and a fifth slot on this side would be a write past the end of
     * it if the two ever drifted.
     */
    @Test
    fun `there are four slots`() {
        assertEquals(4, PadBridge.SLOTS)
    }
}
