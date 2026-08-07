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

    /**
     * VESSEL: the abstract factory, which is what makes `DISPLAY=:0` work here.
     *
     * [UnixSocketConfig.create] relocates the well-known path under a root the
     * guest sees as "/", which needs a proot rootfs Vessel does not have — and
     * Android has no `/tmp` for the real path either. An abstract name has no
     * filesystem at all, and the guest's libxcb tries it first, so this is the
     * one form that needs neither a rootfs nor a patch to Wine.
     */
    @Test
    fun `an abstract config is the same name with an at-sign and no filesystem`() {
        val config = UnixSocketConfig.createAbstract(UnixSocketConfig.XSERVER_PATH)

        assertEquals("@" + UnixSocketConfig.XSERVER_PATH, config.path)
        assertEquals(UnixSocketConfig.XSERVER_PATH, config.path.substring(1))
    }

    /**
     * Nothing is deleted, which is the half that is easy to lose in a refactor.
     *
     * The filesystem factory wipes the socket's parent directory. Doing that for
     * an abstract name would mean deleting a real directory that merely shares
     * the name — and an abstract socket cannot go stale in the first place, so
     * there is nothing the wipe would buy.
     */
    @Test
    fun `an abstract config leaves an existing directory of the same name alone`() {
        val root = temp.newFolder("prefix")
        val occupied = File(root, "tmp/.X11-unix")
        assertTrue(occupied.mkdirs())
        val bystander = File(occupied, "X0")
        bystander.writeText("not really a socket")

        UnixSocketConfig.createAbstract(File(root, UnixSocketConfig.XSERVER_PATH).path)

        assertTrue(bystander.exists())
    }
}
