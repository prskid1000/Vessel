package com.winlator.xconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Where the sockets land, and the cleanup that has to happen before binding.
 *
 * `/tmp/.X11-unix/X0` inside the container is the only reason DISPLAY=:0 works
 * without configuration, and a stale socket file from a killed session makes
 * bind(2) fail with EADDRINUSE. The directory is therefore wiped, not just
 * created.
 */
class UnixSocketConfigTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the well-known paths are the ones the guest is configured for`() {
        assertEquals("/tmp/.X11-unix/X0", UnixSocketConfig.XSERVER_PATH)
        assertEquals("/tmp/.sysvshm/SM0", UnixSocketConfig.SYSVSHM_SERVER_PATH)
    }

    @Test
    fun `creating a config makes the socket directory under the given root`() {
        val root = temp.newFolder("prefix")
        val config = UnixSocketConfig.create(root.path, UnixSocketConfig.XSERVER_PATH)

        assertEquals(File(root, "/tmp/.X11-unix/X0").path, config.path)
        assertTrue(File(root, "tmp/.X11-unix").isDirectory)
    }

    @Test
    fun `a stale socket directory from a previous session is removed`() {
        val root = temp.newFolder("prefix")
        val stale = File(root, "tmp/.X11-unix")
        assertTrue(stale.mkdirs())
        val staleSocket = File(stale, "X0")
        staleSocket.writeText("not really a socket")

        UnixSocketConfig.create(root.path, UnixSocketConfig.XSERVER_PATH)

        assertTrue(stale.isDirectory)
        assertFalse(staleSocket.exists())
    }
}
