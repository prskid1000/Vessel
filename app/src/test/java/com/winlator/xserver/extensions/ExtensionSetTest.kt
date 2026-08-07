package com.winlator.xserver.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extension set Vessel advertises, and the opcode arithmetic behind it.
 *
 * winex11 probes every extension with QueryExtension and adapts to whatever is
 * missing, so absence is safe — but the *names* are matched literally against
 * the strings in the X protocol registry, and the extension-relative error and
 * event bases are what a client adds its own sub-codes to. Both are wire
 * contract.
 *
 * The list is deliberately short. XFixes, XInput2, RandR, RENDER and SHAPE are
 * all absent, and GLX was removed when vendoring; see the comment in
 * XServer.setupExtensions().
 */
class ExtensionSetTest {

    // Constructing with a null server is fine: Extension's constructor only
    // stores the reference, and none of the metadata below reads it.
    private fun build(): List<Extension> {
        var opcode = Extension.START_MAJOR_OPCODE
        return listOf(
            BigReqExtension(null, opcode--),
            MITSHMExtension(null, opcode--),
            DRI3Extension(null, opcode--),
            PresentExtension(null, opcode--),
            SyncExtension(null, opcode--),
            XComposite(null, opcode--),
        )
    }

    @Test
    fun `the advertised names are the ones in the X protocol registry`() {
        assertEquals(
            listOf("BIG-REQUESTS", "MIT-SHM", "DRI3", "Present", "SYNC", "Composite"),
            build().map { it.name },
        )
    }

    @Test
    fun `major opcodes descend from the start opcode and stay negative`() {
        // The dispatcher routes on `opcode < 0`, and XServer.getExtension()
        // indexes the array as START_MAJOR_OPCODE - opcode. Both break if an
        // extension opcode ever reaches 0.
        val extensions = build()
        extensions.forEachIndexed { index, extension ->
            assertEquals(
                Extension.START_MAJOR_OPCODE - index,
                extension.majorOpcode.toInt(),
            )
            assertTrue(extension.majorOpcode < 0)
        }
        assertEquals(-100, Extension.START_MAJOR_OPCODE.toInt())
    }

    @Test
    fun `MIT-SHM is the only extension with its own error and event bases`() {
        val byName = build().associateBy { it.name }

        // ShmCompletion is event 0 relative to the base; ShmSeg is error 0.
        // Byte.MIN_VALUE is how this server spells "far away from the core
        // error codes", which run 1..17.
        assertEquals(64, byName.getValue("MIT-SHM").firstEventId.toInt())
        assertEquals(Byte.MIN_VALUE, byName.getValue("MIT-SHM").firstErrorId)

        byName.filterKeys { it != "MIT-SHM" }.values.forEach {
            assertEquals(0, it.firstEventId.toInt())
            assertEquals(0, it.firstErrorId.toInt())
        }
    }

    @Test
    fun `GLX is not vendored`() {
        // Vessel's guest gets a real ARM64EC opengl32 from Mesa/Zink, so the
        // GLX-over-gladio path upstream ships is dead weight. If someone
        // re-vendors it, this fails and they have to think about it.
        val loader = requireNotNull(javaClass.classLoader)
        // Positive control: without it, a classloader that indexes no main
        // classes at all would make the assertion below pass for free.
        assertNotNull(loader.getResource("com/winlator/xserver/extensions/DRI3Extension.class"))
        assertNull(loader.getResource("com/winlator/xserver/extensions/GLXExtension.class"))
    }
}
