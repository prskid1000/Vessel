package app.vessel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Certificates a container trusts, and the one thing that must not happen: a
 * paste that is not a certificate being stored as though it were.
 *
 * Wine imports whatever is in this directory at start-up, and a bad file there
 * produces a TLS failure inside the guest with nothing to read but the failure.
 * So the check happens here, where there is a person to tell.
 */
class ContainerCertificatesTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** The device certificate, as the proxy hands it out. */
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIDQzCCAiugAwIBAgIJAImD7OPAaqPKMA0GCSqGSIb3DQEBCwUAMBkxFzAVBgNV
        BAMMDjEwMC45MS4yNTEuMTE3MB4XDTI2MDgyMDA5MTMwNFoXDTM2MDgxODA5MTMw
        NFowGTEXMBUGA1UEAwwOMTAwLjkxLjI1MS4xMTcwggEiMA0GCSqGSIb3DQEBAQUA
        A4IBDwAwggEKAoIBAQCWx3R8Wm1iryBMm4O1xp9jieAZyDquLVIUbbj1PwPP1elP
        nFOtz6vXu3mhtkO2+aPDlisN7ZIu6WEPUoic0MCdO4Mt9dKUMDF7aQVL58ETte9m
        yZC2CvjyQncXdUuI2liEF0H07BkBEHSebEtUyuKo4vy5i6YOOCSkkqrlQb32ivdA
        deHNlb5bD3AisKyUz8MKuqIQ0ke73lXbGA6KYG4bqL8TW8YTm6U8Diz9c9MiO106
        gPSbtMx81e9OwlZSGMBkyyI5zddtYsn0Q6GaFnrgRrRFTHM/8aviue4kbJ7+CzoL
        zX3oC+4wdoFQ+wiZ34Wfxw4EJGmbJ2BMjOFIuXO9AgMBAAGjgY0wgYowDwYDVR0T
        AQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAqQwEwYDVR0lBAwwCgYIKwYBBQUHAwEw
        UgYDVR0RBEswSYcEZFv7dYIkbW90b3JvbGEtc2lnbmF0dXJlLnRhaWxhYmI4MTEu
        dHMubmV0hwTAqAEGhwTAAAAChwR/AAABgglsb2NhbGhvc3QwDQYJKoZIhvcNAQEL
        BQADggEBAJJMF9K7jRpvrtC59qm703Xfp8m3SeKoYjyQgYgYRwihthW4nEKmqBjT
        iX6Q5IzsKUcG4t7+yHy7SWQH/gtnvKPzpk0hw9WZriRjAX2MJSBh4hPeSHAVz3DV
        MnyY5fi7pziTjpuGlx1Wo4wxAP/cgOtUxgJfWmS5L1LbAs5Y36HTx3qnUpObnsZk
        WF5/xxIhYUAnFj9s6RB+HA2nKkojBmdYyrZG7UeQS7RxicIHCMpP92nAm0n4aEbo
        4if4FfBz7QvFKfcp4UV7jlAOepm1XufnycAMHRtZPrRVO4sTyfqZ+HLW0ywzf1tI
        ObVxyL8zXNd9o4TOoRk2/Qhtx88Z6SU=
        -----END CERTIFICATE-----
    """.trimIndent()

    @Test
    fun `a real certificate is accepted and named for its subject`() {
        val dir = temp.newFolder("certs")
        val added = ContainerCertificates.add(dir, pem)
        assertNotNull(added)
        assertEquals("100.91.251.117", added!!.subject)
        assertTrue(added.file.name.endsWith(".pem"))
        assertTrue(added.file.isFile)
    }

    @Test
    fun `the same certificate twice is one file, not two`() {
        // Named for the subject rather than a counter, so re-pasting after an
        // edit replaces rather than accumulating near-identical entries.
        val dir = temp.newFolder("certs")
        ContainerCertificates.add(dir, pem)
        ContainerCertificates.add(dir, pem)
        assertEquals(1, ContainerCertificates.list(dir).size)
    }

    @Test
    fun `text that is not a certificate is refused`() {
        // The failure this exists to prevent: stored happily, then a TLS error
        // inside the guest with nothing to read but the error.
        val dir = temp.newFolder("certs")
        assertNull(ContainerCertificates.add(dir, "hello"))
        assertNull(ContainerCertificates.add(dir, ""))
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `a truncated certificate is refused, header notwithstanding`() {
        // A header check would pass this. Parsing is the point.
        val half = pem.lines().take(6).joinToString("\n") + "\n-----END CERTIFICATE-----"
        assertNull(ContainerCertificates.add(temp.newFolder("certs"), half))
    }

    @Test
    fun `listing an absent directory is empty, not a throw`() {
        // Every container has this path and almost none has the directory.
        assertEquals(emptyList<ContainerCertificate>(), ContainerCertificates.list(File(temp.root, "nope")))
    }

    @Test
    fun `a file that is not a certificate is skipped rather than shown`() {
        val dir = temp.newFolder("certs")
        ContainerCertificates.add(dir, pem)
        File(dir, "notes.txt").writeText("something a user dropped here")
        assertEquals(1, ContainerCertificates.list(dir).size)
    }

    @Test
    fun `removing one leaves the others`() {
        val dir = temp.newFolder("certs")
        val added = ContainerCertificates.add(dir, pem)!!
        assertTrue(ContainerCertificates.remove(added))
        assertEquals(0, ContainerCertificates.list(dir).size)
    }
}
