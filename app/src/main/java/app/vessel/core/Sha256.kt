package app.vessel.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256, in the one form this project uses it: lowercase hex, compared
 * case-blind.
 *
 * It exists because the digest is now checked in three places and they must not
 * disagree about what "the same hash" means. `build/package_wcp.py` writes a
 * `sha256sum`-format sidecar, `build/gen_registry.py` copies that digest into
 * `registry/contents.json`, the downloader hashes what actually arrived over the
 * network, and [app.vessel.data.WcpInstaller] hashes it again before it extracts
 * anything. Four producers of the same 64 characters, and a mismatch between any
 * two of them is a package that will not install with no useful message.
 *
 * [isWellFormed] is the part worth having as code rather than as a convention.
 * A digest field is a string, and a registry that carries `""`, `null`, `"TODO"`
 * or an uppercase digest with a stray newline is not obviously wrong until an
 * 88 MB download finishes and the comparison fails. Checking the shape up front
 * turns that into a refusal before the first byte.
 *
 * `MessageDigest` rather than `java.security.DigestInputStream`: the download
 * path needs the digest of bytes it is already copying and counting, so it
 * updates the digest itself in the same loop rather than stacking a stream over
 * a stream.
 */
object Sha256 {

    /** Characters in a hex-encoded SHA-256. */
    const val HEX_LENGTH = 64

    private const val BUFFER_BYTES = 1024 * 1024

    private val HEX = "0123456789abcdef".toCharArray()

    /** A new, empty digest. The download loop feeds this as it copies. */
    fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** [digest]'s accumulated value as lowercase hex. Resets nothing. */
    fun MessageDigest.hex(): String = toHex(digest())

    /** The digest of [file], or null when it cannot be read. */
    fun of(file: File): String? = runCatching {
        file.inputStream().use { of(it) }
    }.getOrNull()

    /** The digest of everything [stream] yields. Does not close it. */
    fun of(stream: InputStream): String {
        val digest = digest()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    /**
     * True when [hex] is 64 hex characters — a value some file could actually
     * hash to.
     *
     * Deliberately does not trim or lowercase: this answers "is the registry
     * well formed?", and a digest with whitespace around it is a generator bug
     * worth seeing rather than papering over. [matches] is where leniency
     * belongs, because there the two sides come from different tools.
     */
    fun isWellFormed(hex: String?): Boolean =
        hex != null && hex.length == HEX_LENGTH && hex.all { it.isHexDigit() }

    /**
     * Whether two digests are the same value.
     *
     * Case-blind because `sha256sum` writes lowercase and several other tools
     * write uppercase, and a package rejected over letter case would be rejected
     * with a message that shows two strings a human reads as identical.
     *
     * Not constant-time, and that is correct here rather than an oversight:
     * both sides are public values from a public registry, there is no secret to
     * leak by timing, and the comparison runs once per install.
     */
    fun matches(expected: String?, actual: String?): Boolean =
        expected != null && actual != null && expected.equals(actual, ignoreCase = true)

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
