package app.vessel.data

import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * One certificate authority a container trusts.
 *
 * @property file what it is stored as, and the handle for removing it.
 * @property subject who it is for, as the certificate says.
 * @property expires the not-after date, so an expired one can be seen to be
 *   expired rather than silently failing every request.
 */
data class ContainerCertificate(
    val file: File,
    val subject: String,
    val expires: String,
)

/**
 * The certificate authorities a container trusts, on top of the ones Wine ships.
 *
 * **Files in a directory, not a document.** Wine imports every file in
 * `WINE_ADDITIONAL_CERTS_DIR` into the root store at start-up — `crypt32`,
 * `load_root_certs` — so the directory *is* the format, and a list kept
 * elsewhere would be a second copy able to disagree with it. Removing a
 * certificate is deleting a file, and the next session simply does not import
 * it.
 *
 * That store is the one Chromium consults on Windows, which is why this exists
 * at all: `NODE_EXTRA_CA_CERTS` reaches Node's TLS and misses Chromium's own
 * network stack, and the network stack is what makes the request an Electron
 * application actually fails on.
 */
object ContainerCertificates {

    /**
     * Whether [text] is a certificate this can store, and what it says if so.
     *
     * Parsed rather than pattern-matched. `-----BEGIN CERTIFICATE-----` is four
     * seconds of typing and proves nothing; a paste that is truncated, or is a
     * private key, or is a certificate for a host nobody meant, all look correct
     * to a header check and all fail later inside Wine where there is nothing to
     * read but a TLS error.
     */
    fun parse(text: String): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(text.trim().toByteArray()))
            as? X509Certificate
    }.getOrNull()

    /** Every certificate in [dir], newest last. Unreadable files are skipped. */
    fun list(dir: File): List<ContainerCertificate> =
        dir.listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }
            .mapNotNull { file ->
                val cert = runCatching { parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
                ContainerCertificate(
                    file = file,
                    subject = cert.subjectX500Principal.name.removePrefix("CN="),
                    expires = cert.notAfter.toInstant().toString().take(10),
                )
            }

    /**
     * Store [text] in [dir], or null if it is not a certificate.
     *
     * Named for the subject rather than by a counter, so the directory reads as
     * a list of who is trusted and adding the same certificate twice replaces it
     * rather than accumulating. Non-filename characters go to `_`: a subject is
     * arbitrary text from an untrusted document and this is a path.
     */
    fun add(dir: File, text: String): ContainerCertificate? {
        val cert = parse(text) ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val subject = cert.subjectX500Principal.name.removePrefix("CN=")
        val name = subject.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("")
            .take(60)
            .ifBlank { "certificate" }
        val file = File(dir, "$name.pem")
        return runCatching {
            file.writeText(text.trim() + "\n")
            ContainerCertificate(
                file = file,
                subject = subject,
                expires = cert.notAfter.toInstant().toString().take(10),
            )
        }.getOrNull()
    }

    /** Forget one. The next session does not import what is not there. */
    fun remove(certificate: ContainerCertificate): Boolean =
        runCatching { certificate.file.delete() }.getOrDefault(false)
}
