package com.winlator.xserver

import com.winlator.xserver.events.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire constants of the vendored X server, checked against the X11 core
 * protocol rather than against themselves.
 *
 * These numbers are not the server's to choose — they are what the client
 * library on the other end of the socket already believes. A wrong opcode does
 * not fail loudly; it routes a CreateWindow into ChangeGC and the session dies
 * somewhere unrelated. Since the whole point of vendoring is that this code
 * gets re-synced from upstream by hand, a table that pins the spec values is
 * the cheapest guard against a bad merge.
 *
 * Reference: X Window System Protocol, Version 11, Release 7, Appendix B.
 */
class XProtocolConstantsTest {

    @Test
    fun `core request opcodes match the X11 protocol`() {
        assertEquals(1, ClientOpcodes.CREATE_WINDOW.toInt())
        assertEquals(2, ClientOpcodes.CHANGE_WINDOW_ATTRIBUTES.toInt())
        assertEquals(3, ClientOpcodes.GET_WINDOW_ATTRIBUTES.toInt())
        assertEquals(4, ClientOpcodes.DESTROY_WINDOW.toInt())
        assertEquals(7, ClientOpcodes.REPARENT_WINDOW.toInt())
        assertEquals(8, ClientOpcodes.MAP_WINDOW.toInt())
        assertEquals(10, ClientOpcodes.UNMAP_WINDOW.toInt())
        assertEquals(12, ClientOpcodes.CONFIGURE_WINDOW.toInt())
        assertEquals(14, ClientOpcodes.GET_GEOMETRY.toInt())
        assertEquals(16, ClientOpcodes.INTERN_ATOM.toInt())
        assertEquals(18, ClientOpcodes.CHANGE_PROPERTY.toInt())
        assertEquals(20, ClientOpcodes.GET_PROPERTY.toInt())
        assertEquals(25, ClientOpcodes.SEND_EVENT.toInt())
        assertEquals(38, ClientOpcodes.QUERY_POINTER.toInt())
        assertEquals(42, ClientOpcodes.SET_INPUT_FOCUS.toInt())
        assertEquals(53, ClientOpcodes.CREATE_PIXMAP.toInt())
        assertEquals(55, ClientOpcodes.CREATE_GC.toInt())
        assertEquals(62, ClientOpcodes.COPY_AREA.toInt())
        assertEquals(72, ClientOpcodes.PUT_IMAGE.toInt())
        assertEquals(73, ClientOpcodes.GET_IMAGE.toInt())
        assertEquals(93, ClientOpcodes.CREATE_CURSOR.toInt())
        assertEquals(98, ClientOpcodes.QUERY_EXTENSION.toInt())
        assertEquals(101, ClientOpcodes.GET_KEYBOARD_MAPPING.toInt())
        assertEquals(119, ClientOpcodes.GET_MODIFIER_MAPPING.toInt())
        assertEquals(127, ClientOpcodes.NO_OPERATION.toInt())
    }

    @Test
    fun `no two request opcodes collide`() {
        val opcodes = ClientOpcodes::class.java.fields
            .filter { it.type == java.lang.Byte.TYPE }
            .map { it.name to it.getByte(null).toInt() }

        // 60-odd of the 127 core requests are implemented; anything the switch
        // in XClientRequestHandler does not name is answered as unsupported.
        assertTrue("expected a substantial opcode table", opcodes.size > 50)
        assertEquals(opcodes.size, opcodes.map { it.second }.toSet().size)
        assertTrue(opcodes.all { it.second in 1..127 })
    }

    @Test
    fun `event mask bits match the X11 SETofEVENT encoding`() {
        assertEquals(0x00000001, Event.KEY_PRESS)
        assertEquals(0x00000002, Event.KEY_RELEASE)
        assertEquals(0x00000004, Event.BUTTON_PRESS)
        assertEquals(0x00000008, Event.BUTTON_RELEASE)
        assertEquals(0x00000010, Event.ENTER_WINDOW)
        assertEquals(0x00000020, Event.LEAVE_WINDOW)
        assertEquals(0x00000040, Event.POINTER_MOTION)
        assertEquals(0x00008000, Event.EXPOSURE)
        assertEquals(0x00020000, Event.STRUCTURE_NOTIFY)
        assertEquals(0x00080000, Event.SUBSTRUCTURE_NOTIFY)
        assertEquals(0x00100000, Event.SUBSTRUCTURE_REDIRECT)
        assertEquals(0x00200000, Event.FOCUS_CHANGE)
        assertEquals(0x00400000, Event.PROPERTY_CHANGE)
        assertEquals(0x01000000, Event.OWNER_GRAB_BUTTON)
    }

    @Test
    fun `graphics context value mask bits match the X11 CreateGC value list order`() {
        assertEquals(0x000001, GraphicsContext.FLAG_FUNCTION)
        assertEquals(0x000002, GraphicsContext.FLAG_PLANE_MASK)
        assertEquals(0x000004, GraphicsContext.FLAG_FOREGROUND)
        assertEquals(0x000008, GraphicsContext.FLAG_BACKGROUND)
        assertEquals(0x000010, GraphicsContext.FLAG_LINE_WIDTH)
        assertEquals(0x000400, GraphicsContext.FLAG_TILE)
        assertEquals(0x004000, GraphicsContext.FLAG_FONT)
        assertEquals(0x080000, GraphicsContext.FLAG_CLIP_MASK)
        assertEquals(0x400000, GraphicsContext.FLAG_ARC_MODE)
    }

    @Test
    fun `graphics context function codes are in X11 GX order`() {
        // MIT-SHM PutImage refuses anything but COPY, so COPY landing on 3 is
        // load-bearing for the fast 2D path.
        assertEquals(0, GraphicsContext.Function.CLEAR.ordinal)
        assertEquals(3, GraphicsContext.Function.COPY.ordinal)
        assertEquals(6, GraphicsContext.Function.XOR.ordinal)
        assertEquals(15, GraphicsContext.Function.SET.ordinal)
    }

    @Test
    fun `window attribute value mask bits match the X11 CW encoding`() {
        assertEquals(0x0001, WindowAttributes.FLAG_BACKGROUND_PIXMAP)
        assertEquals(0x0002, WindowAttributes.FLAG_BACKGROUND_PIXEL)
        assertEquals(0x0800, WindowAttributes.FLAG_EVENT_MASK)
        assertEquals(0x4000, WindowAttributes.FLAG_CURSOR)
    }

    @Test
    fun `keycodes stay inside the range advertised in the connection setup`() {
        // The setup reply hands the client MIN_KEYCODE and MAX_KEYCODE, and the
        // keymap it later asks for is sized from that range. A keycode outside
        // it is one the client can never be told about.
        val minKeycode = 8
        val maxKeycode = 255

        val real = XKeycode.values().filter { it != XKeycode.KEY_NONE }
        assertTrue(real.isNotEmpty())
        assertTrue(real.all { it.id.toInt() in minKeycode..maxKeycode })
        assertEquals(0, XKeycode.KEY_NONE.id.toInt())

        // Anchors from a standard PC/AT layout as X sees it.
        assertEquals(9, XKeycode.KEY_ESC.id.toInt())
        assertEquals(24, XKeycode.KEY_Q.id.toInt())
        assertEquals(36, XKeycode.KEY_ENTER.id.toInt())
        assertEquals(65, XKeycode.KEY_SPACE.id.toInt())
    }

    @Test
    fun `no two keycodes collide`() {
        // KEY_MAX is declared as an alias of KEY_CUSTOM_17 rather than a key.
        val ids = XKeycode.values()
            .filter { it != XKeycode.KEY_NONE && it != XKeycode.KEY_MAX }
            .map { it.id.toInt() }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(0))
        assertEquals(XKeycode.KEY_CUSTOM_17.id, XKeycode.KEY_MAX.id)
    }
}
