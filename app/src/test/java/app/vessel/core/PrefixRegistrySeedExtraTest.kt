package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-container registry overlay, and the one property that matters about
 * it: a change to it has to change the stamp.
 *
 * `SessionRuntime.applyRegistry` skips `regedit` when the hive already carries
 * the stamp the rendered file names. Anything folded in after the stamp would
 * be written to `prefix-seed.reg`, recorded in `provisioned.json`, and never
 * applied — which is what happened to seeds 9, 10 and 11, and is recorded in
 * that method as having looked like "it only works on a fresh container".
 */
class PrefixRegistrySeedExtraTest {

    private val claudePolicy = """
        [HKEY_LOCAL_MACHINE\Software\Policies\Claude]
        "inferenceProvider"="gateway"
    """.trimIndent()

    @Test
    fun `an overlay reaches the rendered seed`() {
        val seed = PrefixRegistry.renderSeed(listOf('C'), claudePolicy)
        assertTrue(seed.contains("""[HKEY_LOCAL_MACHINE\Software\Policies\Claude]"""))
        assertTrue(seed.contains(""""inferenceProvider"="gateway""""))
    }

    @Test
    fun `an overlay changes the stamp, so regedit runs again`() {
        val plain = PrefixRegistry.renderSeed(listOf('C'))
        val withExtra = PrefixRegistry.renderSeed(listOf('C'), claudePolicy)
        assertNotEquals(PrefixRegistry.stampOf(plain), PrefixRegistry.stampOf(withExtra))
    }

    @Test
    fun `editing the overlay changes the stamp again`() {
        val first = PrefixRegistry.renderSeed(listOf('C'), claudePolicy)
        val second = PrefixRegistry.renderSeed(
            listOf('C'),
            claudePolicy.replace("gateway", "anthropic"),
        )
        assertNotEquals(PrefixRegistry.stampOf(first), PrefixRegistry.stampOf(second))
    }

    @Test
    fun `no overlay renders exactly what it did before`() {
        assertEquals(PrefixRegistry.renderSeed(listOf('C')), PrefixRegistry.renderSeed(listOf('C'), ""))
        assertEquals(PrefixRegistry.renderSeed(listOf('C')), PrefixRegistry.renderSeed(listOf('C'), "   \n\n "))
    }

    @Test
    fun `the overlay's own header is dropped`() {
        // A file exported by regedit carries one, and a second copy part-way
        // through a .reg file is not valid.
        val seed = PrefixRegistry.renderSeed(
            listOf('C'),
            "Windows Registry Editor Version 5.00\n\n$claudePolicy",
        )
        assertEquals(1, seed.split("Windows Registry Editor").size - 1)
    }

    @Test
    fun `the stamp is still the last thing in the file`() {
        val seed = PrefixRegistry.renderSeed(listOf('C'), claudePolicy)
        val stampAt = seed.lastIndexOf("""[HKEY_LOCAL_MACHINE\Software\Vessel]""")
        val extraAt = seed.lastIndexOf("""[HKEY_LOCAL_MACHINE\Software\Policies\Claude]""")
        assertTrue("the overlay must precede the stamp", extraAt < stampAt)
    }
}
