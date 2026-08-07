package com.winlator.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predefined atom table.
 *
 * Atoms 1..68 are fixed by the protocol: a client is allowed to use PRIMARY or
 * WM_CLASS without ever calling InternAtom, so if the table shifts by one, a
 * window manager hint silently becomes a different hint. Everything from 69 up
 * is the server's own, and winex11 interns those by name, so their numbering is
 * free but their spelling is not.
 */
class AtomTest {

    @Test
    fun `the predefined atoms keep their protocol ids`() {
        assertEquals(1, Atom.getId("PRIMARY"))
        assertEquals(2, Atom.getId("SECONDARY"))
        assertEquals(4, Atom.getId("ATOM"))
        assertEquals(6, Atom.getId("CARDINAL"))
        assertEquals(23, Atom.getId("RESOURCE_MANAGER"))
        assertEquals(31, Atom.getId("STRING"))
        assertEquals(33, Atom.getId("WINDOW"))
        assertEquals(39, Atom.getId("WM_NAME"))
        assertEquals(67, Atom.getId("WM_CLASS"))
        assertEquals(68, Atom.getId("WM_TRANSIENT_FOR"))
    }

    @Test
    fun `the constants agree with the table they index`() {
        assertEquals("PRIMARY", Atom.getName(Atom.PRIMARY.toInt()))
        assertEquals("RESOURCE_MANAGER", Atom.getName(Atom.RESOURCE_MANAGER.toInt()))
        assertEquals("STRING", Atom.getName(Atom.STRING.toInt()))
        assertEquals("WM_CLASS", Atom.getName(Atom.WM_CLASS.toInt()))
        assertEquals("_NET_WM_PID", Atom.getName(Atom._NET_WM_PID.toInt()))
    }

    @Test
    fun `the Wine-facing atoms are present under the exact names winex11 interns`() {
        // These are how the X server learns a window's HWND and whether it is
        // the wow64 process. Renaming one breaks window activation with no
        // error anywhere.
        assertEquals(Atom._NET_WM_HWND.toInt(), Atom.getId("_NET_WM_HWND"))
        assertEquals(Atom._NET_WM_WOW64.toInt(), Atom.getId("_NET_WM_WOW64"))
        assertEquals(Atom._NET_WM_SURFACE.toInt(), Atom.getId("_NET_WM_SURFACE"))
        assertEquals(Atom._MOTIF_WM_HINTS.toInt(), Atom.getId("_MOTIF_WM_HINTS"))
    }

    @Test
    fun `atom zero is not a valid atom`() {
        assertFalse(Atom.isValid(0))
        assertTrue(Atom.isValid(1))
        assertEquals(0, Atom.getId(null))
    }

    @Test
    fun `interning is idempotent and allocates above the predefined range`() {
        val first = Atom.internAtom("_VESSEL_TEST_ATOM")
        assertTrue(first > 68)
        assertEquals(first, Atom.internAtom("_VESSEL_TEST_ATOM"))
        assertEquals("_VESSEL_TEST_ATOM", Atom.getName(first))
        assertTrue(Atom.isValid(first))

        val second = Atom.internAtom("_VESSEL_TEST_ATOM_2")
        assertNotEquals(first, second)
    }

    @Test
    fun `an unknown name is reported as not found rather than as atom zero`() {
        // InternAtom with only-if-exists relies on this: -1 means "no such
        // atom", 0 means "the client asked for None".
        assertEquals(-1, Atom.getId("_VESSEL_NEVER_INTERNED"))
    }
}
