package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The digest helper, against values `sha256sum` produced.
 *
 * The two constants below are not this code's own output fed back to it — they
 * are the standard NIST vectors, which is the only way an assertion about a hash
 * function means anything.
 */
class Sha256Test {

    @get:Rule
    val temporary = TemporaryFolder()

    /** `printf '' | sha256sum` */
    private val emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** `printf 'abc' | sha256sum` */
    private val abcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `hashes a file byte for byte`() {
        val file = temporary.newFile("payload")
        file.writeBytes("abc".toByteArray())
        assertEquals(abcDigest, Sha256.of(file))
    }

    @Test
    fun `an empty file has the empty digest, not null`() {
        assertEquals(emptyDigest, Sha256.of(temporary.newFile("nothing")))
    }

    @Test
    fun `a file that is not there is null rather than an exception`() {
        assertNull(Sha256.of(temporary.root.resolve("absent")))
    }

    @Test
    fun `hashes a payload larger than one buffer`() {
        // 3 MiB, past the 1 MiB read buffer, so a bug in the loop's bounds shows
        // up here rather than only on a 90 MB package.
        val file = temporary.newFile("big")
        val chunk = ByteArray(1024) { (it % 251).toByte() }
        file.outputStream().use { out -> repeat(3 * 1024) { out.write(chunk) } }
        assertEquals(Sha256.of(file.inputStream()), Sha256.of(file))
    }

    @Test
    fun `well-formed means sixty-four hex characters and nothing else`() {
        assertTrue(Sha256.isWellFormed(abcDigest))
        assertTrue(Sha256.isWellFormed(abcDigest.uppercase()))
        assertFalse(Sha256.isWellFormed(null))
        assertFalse(Sha256.isWellFormed(""))
        assertFalse(Sha256.isWellFormed("TODO"))
        assertFalse(Sha256.isWellFormed(abcDigest.dropLast(1)))
        assertFalse(Sha256.isWellFormed(abcDigest + "0"))
        // A trailing newline is the shape a `sha256sum` sidecar arrives in when
        // somebody forgets to strip it, and it is a registry bug, not a value.
        assertFalse(Sha256.isWellFormed("$abcDigest\n"))
        assertFalse(Sha256.isWellFormed(abcDigest.replace('a', 'g')))
    }

    @Test
    fun `comparison is case-blind, because sha256sum and other tools disagree`() {
        assertTrue(Sha256.matches(abcDigest, abcDigest.uppercase()))
        assertFalse(Sha256.matches(abcDigest, emptyDigest))
        assertFalse(Sha256.matches(null, abcDigest))
        assertFalse(Sha256.matches(abcDigest, null))
    }
}
