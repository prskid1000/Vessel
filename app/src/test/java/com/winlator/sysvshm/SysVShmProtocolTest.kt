package com.winlator.sysvshm

import com.winlator.core.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The fd-passing shared-memory shim, on the parts that do not need a device.
 *
 * The allocation itself is JNI (ashmem, with a memfd fallback — see the class
 * comment on SysVSharedMemory for why neither is shmget), so what is checkable
 * here is the socket protocol: three one-byte request codes that the guest side
 * has to agree with exactly, because the stream carries no framing beyond them.
 */
class SysVShmProtocolTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the request codes are the ones the guest sends`() {
        assertEquals(0, RequestCodes.SHMGET.toInt())
        assertEquals(1, RequestCodes.GET_FD.toInt())
        assertEquals(2, RequestCodes.DELETE.toInt())
    }

    @Test
    fun `the request codes are distinct and fit the single byte the reader takes`() {
        val codes = listOf(RequestCodes.SHMGET, RequestCodes.GET_FD, RequestCodes.DELETE)
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.toInt() in 0..255 })
    }

    @Test
    fun `socket paths resolve to a parent directory the server can create`() {
        assertEquals("/tmp/.sysvshm", FileUtils.getDirname("/tmp/.sysvshm/SM0"))
        assertEquals("/tmp/.X11-unix", FileUtils.getDirname("/tmp/.X11-unix/X0"))

        // A bare name has no parent; upstream throws here.
        assertEquals("", FileUtils.getDirname("SM0"))
        assertEquals("", FileUtils.getDirname(null))
    }

    @Test
    fun `deleting a socket directory removes its contents`() {
        val dir = temp.newFolder("sysvshm")
        File(dir, "SM0").writeText("")
        File(dir, "nested").mkdirs()

        assertTrue(FileUtils.delete(dir))
        assertTrue(!dir.exists())
    }
}
