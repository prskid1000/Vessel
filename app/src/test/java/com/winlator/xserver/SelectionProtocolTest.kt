package com.winlator.xserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The selection half of the X11 core protocol, as far as a JVM can see it.
 *
 * **What this can and cannot cover, stated up front.** The atoms, the opcode and
 * the property encodings are pure arithmetic over `String` and `ByteArray`, so they
 * are checked here and a wrong answer fails the build. Everything else about
 * clipboard — whether Wine accepts these answers, whether `ClipboardManager`
 * fires when expected, whether a paste actually pastes — needs a device, a live
 * Wine and a user copying something, and is **unverified**. Nothing in
 * [ClipboardSelection] that touches a [Window] can even be constructed off-device:
 * `Window` holds an `android.util.SparseArray`, and the unit-test `android.jar`
 * throws from its constructor.
 *
 * Same reasoning as `XProtocolConstantsTest`: these numbers are not this server's
 * to choose, they are what libX11 on the other end of the socket already believes.
 */
class SelectionProtocolTest {

    @Test
    fun `ConvertSelection is opcode 24`() {
        // It was not declared at all, so opcode 24 fell through
        // XClientRequestHandler's default and dropped the connection — which is
        // why a guest could claim CLIPBOARD and nothing could ask what was in it.
        assertEquals(24, ClientOpcodes.CONVERT_SELECTION.toInt())
    }

    @Test
    fun `the selection opcodes are the three consecutive ones X11 defines`() {
        assertEquals(22, ClientOpcodes.SET_SELECTION_OWNER.toInt())
        assertEquals(23, ClientOpcodes.GET_SELECTION_OWNER.toInt())
        assertEquals(24, ClientOpcodes.CONVERT_SELECTION.toInt())
        assertEquals(25, ClientOpcodes.SEND_EVENT.toInt())
    }

    @Test
    fun `PRIMARY and STRING are taken from the predefined table, not interned`() {
        // A selection atom that drifted would make the server and Wine disagree
        // about which selection was claimed, with no error anywhere.
        assertEquals(1, ClipboardSelection.ATOM_PRIMARY)
        assertEquals(31, ClipboardSelection.ATOM_STRING)
        assertEquals(4, ClipboardSelection.ATOM_ATOM)
        assertEquals("PRIMARY", Atom.getName(ClipboardSelection.ATOM_PRIMARY))
        assertEquals("STRING", Atom.getName(ClipboardSelection.ATOM_STRING))
    }

    @Test
    fun `CLIPBOARD TARGETS UTF8_STRING and TEXT are interned under their exact names`() {
        // None of the four is a predefined atom — the protocol's fixed table stops
        // at 68 — so both sides reach them by name. A misspelling here is a
        // clipboard that silently never matches.
        assertEquals("CLIPBOARD", Atom.getName(ClipboardSelection.ATOM_CLIPBOARD))
        assertEquals("TARGETS", Atom.getName(ClipboardSelection.ATOM_TARGETS))
        assertEquals("UTF8_STRING", Atom.getName(ClipboardSelection.ATOM_UTF8_STRING))
        assertEquals("TEXT", Atom.getName(ClipboardSelection.ATOM_TEXT))
        assertEquals("INCR", Atom.getName(ClipboardSelection.ATOM_INCR))
        assertTrue(ClipboardSelection.ATOM_CLIPBOARD > 68)
        assertTrue(ClipboardSelection.ATOM_TARGETS > 68)
    }

    @Test
    fun `the offered targets are exactly the four text ones, and no more`() {
        // The list is what a guest discovers with, and advertising anything that
        // would then be refused is the failure shape README item 20 exists for.
        assertArrayEquals(
            intArrayOf(
                ClipboardSelection.ATOM_TARGETS,
                ClipboardSelection.ATOM_UTF8_STRING,
                ClipboardSelection.ATOM_STRING,
                ClipboardSelection.ATOM_TEXT,
            ),
            ClipboardSelection.offeredTargets(),
        )
    }

    @Test
    fun `every offered target except TARGETS itself is answerable as text`() {
        for (target in ClipboardSelection.offeredTargets()) {
            if (target == ClipboardSelection.ATOM_TARGETS) continue
            assertTrue(
                "${Atom.getName(target)} is advertised and would be refused",
                ClipboardSelection.isTextTarget(target),
            )
        }
    }

    @Test
    fun `image and chunked targets are refused rather than guessed at`() {
        assertFalse(ClipboardSelection.isTextTarget(ClipboardSelection.ATOM_INCR))
        assertFalse(ClipboardSelection.isTextTarget(Atom.internAtom("image/png")))
        assertFalse(ClipboardSelection.isTextTarget(Atom.internAtom("TIMESTAMP")))
        assertFalse(ClipboardSelection.isTextTarget(Atom.internAtom("MULTIPLE")))
        assertFalse(ClipboardSelection.isTextTarget(Atom.PIXMAP.toInt()))
        // None of those is on the offered list either, which is the other half.
        val offered = ClipboardSelection.offeredTargets().toSet()
        assertFalse(Atom.internAtom("TIMESTAMP") in offered)
        assertFalse(Atom.internAtom("MULTIPLE") in offered)
        assertFalse(ClipboardSelection.ATOM_INCR in offered)
    }

    @Test
    fun `the target list encodes as a little-endian ATOM array`() {
        val bytes = ClipboardSelection.encodeTargets()
        assertEquals(4 * 4, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val decoded = IntArray(4) { buffer.getInt() }
        assertArrayEquals(ClipboardSelection.offeredTargets(), decoded)
    }

    @Test
    fun `TEXT is answered as UTF8_STRING and STRING as itself`() {
        // ICCCM: TEXT means "any encoding, name it in the reply type". UTF-8 is the
        // only choice that cannot lose a character the user copied.
        assertEquals(
            ClipboardSelection.ATOM_UTF8_STRING,
            ClipboardSelection.replyType(ClipboardSelection.ATOM_TEXT),
        )
        assertEquals(
            ClipboardSelection.ATOM_UTF8_STRING,
            ClipboardSelection.replyType(ClipboardSelection.ATOM_UTF8_STRING),
        )
        assertEquals(
            ClipboardSelection.ATOM_STRING,
            ClipboardSelection.replyType(ClipboardSelection.ATOM_STRING),
        )
    }

    @Test
    fun `text encodes without a terminating NUL`() {
        // A property carries its own length, so a NUL here is a character and not a
        // terminator — and a requestor that pastes it puts a box in a document.
        val encoded = ClipboardSelection.encodeText(ClipboardSelection.ATOM_UTF8_STRING, "ab")
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 'b'.code.toByte()), encoded)
    }

    @Test
    fun `a UTF8_STRING round trip keeps characters Latin-1 cannot hold`() {
        val text = "café — 日本語"
        val encoded = ClipboardSelection.encodeText(ClipboardSelection.ATOM_UTF8_STRING, text)
        assertEquals(text, ClipboardSelection.decodeText(ClipboardSelection.ATOM_UTF8_STRING, encoded))
    }

    @Test
    fun `a STRING round trip is Latin-1, one byte per character`() {
        val text = "café"
        val encoded = ClipboardSelection.encodeText(ClipboardSelection.ATOM_STRING, text)
        assertEquals(4, encoded.size)
        assertEquals(text, ClipboardSelection.decodeText(ClipboardSelection.ATOM_STRING, encoded))
    }

    @Test
    fun `decoding stops at the first NUL`() {
        // Wine's exporters have been seen to pad. Unverified on the device, and
        // cheap to be right about either way.
        val bytes = byteArrayOf('h'.code.toByte(), 'i'.code.toByte(), 0, 'x'.code.toByte())
        assertEquals("hi", ClipboardSelection.decodeText(ClipboardSelection.ATOM_UTF8_STRING, bytes))
    }

    @Test
    fun `decoding refuses a type it does not understand rather than guessing`() {
        // INCR's property body is a byte count. Decoding it as text would paste
        // "1048576" into somebody's document, which is worse than pasting nothing.
        val incr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1048576).array()
        assertNull(ClipboardSelection.decodeText(ClipboardSelection.ATOM_INCR, incr))
        assertNull(ClipboardSelection.decodeText(ClipboardSelection.ATOM_ATOM, incr))
        assertNull(ClipboardSelection.decodeText(ClipboardSelection.ATOM_TEXT, incr))
        assertNull(ClipboardSelection.decodeText(ClipboardSelection.ATOM_UTF8_STRING, null))
    }

    @Test
    fun `the transfer property is named, so it cannot collide with a client's`() {
        assertEquals("_VESSEL_CLIPBOARD_IN", Atom.getName(ClipboardSelection.ATOM_TRANSFER_PROPERTY))
        assertFalse(
            "the shim's transfer property must not be one it also advertises",
            ClipboardSelection.ATOM_TRANSFER_PROPERTY in ClipboardSelection.offeredTargets().toSet(),
        )
    }
}
