package com.winlator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitmask is how every value-list request is decoded, so its iteration order is
 * a wire-format property, not an implementation detail.
 *
 * X11 sends a BITMASK followed by a LISTofVALUE holding one value per set bit,
 * **in increasing bit order**. Each decoder in `xserver/requests` does
 * `for (int flag : bitmask) switch (flag) { ... readInt() }` and so consumes the
 * list in whatever order this iterator produces. Iterate highest-bit-first and
 * every multi-value CreateWindow, ChangeGC and ConfigureWindow reads its fields
 * transposed — which shows up as nonsense geometry, not as an error.
 */
class BitmaskTest {

    @Test
    fun `iteration yields set bits from lowest to highest, matching LISTofVALUE order`() {
        val mask = Bitmask((1 shl 0) or (1 shl 3) or (1 shl 11) or (1 shl 31))
        assertEquals(
            listOf(1 shl 0, 1 shl 3, 1 shl 11, 1 shl 31),
            mask.toList(),
        )
    }

    @Test
    fun `iterating does not consume the mask`() {
        val mask = Bitmask(0b1011)
        assertEquals(3, mask.count())
        assertEquals(3, mask.count())
        assertEquals(0b1011, mask.bits)
    }

    @Test
    fun `an empty mask yields nothing`() {
        val mask = Bitmask()
        assertTrue(mask.isEmpty)
        assertEquals(0, mask.count())
    }

    @Test
    fun `set, unset and isSet round-trip`() {
        val mask = Bitmask()
        mask.set(0x10)
        mask.set(0x01)
        assertTrue(mask.isSet(0x10))
        assertTrue(mask.isSet(0x01))
        assertFalse(mask.isSet(0x02))

        mask.set(0x10, false)
        assertFalse(mask.isSet(0x10))
        assertEquals(0x01, mask.bits)
    }

    @Test
    fun `isSet is any-of, not all-of`() {
        // Callers pass composite masks (an event mask against a window's
        // selected events), and the protocol question is always "does this
        // window want any of these".
        val mask = Bitmask(0b0100)
        assertTrue(mask.isSet(0b0110))
        assertFalse(mask.isSet(0b1001))
    }

    @Test
    fun `join is a union and intersects is a non-empty overlap`() {
        val a = Bitmask(0b0011)
        val b = Bitmask(0b0110)
        assertTrue(a.intersects(b))
        a.join(b)
        assertEquals(0b0111, a.bits)
        assertFalse(Bitmask(0b0001).intersects(Bitmask(0b0010)))
    }

    @Test
    fun `constructing from a flag array is the same as setting each flag`() {
        assertEquals(0b1101, Bitmask(intArrayOf(0b0001, 0b0100, 0b1000)).bits)
    }
}
