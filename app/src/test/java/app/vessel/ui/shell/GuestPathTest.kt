package app.vessel.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two ways to name one file, and the boundary between them.
 *
 * The containment tests are the ones that matter: [GuestPath.resolve] takes a
 * string that reaches it from a text field, and a path that escapes `drive_c` is
 * a read or a write outside the container.
 */
class GuestPathTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val drive: File get() = temp.root

    @Test
    fun `the drive root is C backslash`() {
        assertEquals("C:\\", GuestPath.of(drive, drive))
    }

    @Test
    fun `a nested file becomes a backslashed guest path`() {
        val file = File(drive, "users/vessel/Downloads/npp.exe").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        assertEquals("C:\\users\\vessel\\Downloads\\npp.exe", GuestPath.of(drive, file))
    }

    @Test
    fun `a file outside the drive has no guest name`() {
        val outside = temp.newFolder("elsewhere").let { File(it, "other.exe") }
        assertNull(GuestPath.of(File(drive, "drive"), outside))
    }

    @Test
    fun `resolve round-trips what of produced`() {
        val file = File(drive, "windows/system32/notepad.exe").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        val guest = GuestPath.of(drive, file)!!
        assertEquals(file.canonicalPath, GuestPath.resolve(drive, guest)!!.canonicalPath)
    }

    @Test
    fun `resolve refuses a path that climbs out of the drive`() {
        assertNull(GuestPath.resolve(drive, "C:\\..\\..\\etc\\passwd"))
    }

    @Test
    fun `resolve refuses anything that does not name a drive`() {
        // No longer "anything that is not on C". A container has more than one
        // drive now — the phone's storage is D: — so the letter chooses which
        // root a path resolves against and the caller passes that root. Another
        // drive's path against this root is a caller error rather than a
        // refusal this function can make. What it still refuses is a string
        // with no drive in it at all.
        assertNull(GuestPath.resolve(drive, "/etc/passwd"))
        assertNull(GuestPath.resolve(drive, "users"))
        assertNull(GuestPath.resolve(drive, ""))
    }

    @Test
    fun `parentOf climbs one level and stops at the root`() {
        assertEquals("C:\\users", GuestPath.parentOf("C:\\users\\vessel"))
        assertEquals("C:\\", GuestPath.parentOf("C:\\users"))
        assertNull(GuestPath.parentOf("C:\\"))
    }

    @Test
    fun `segments and upTo drive the breadcrumb`() {
        val path = "C:\\users\\vessel\\Downloads"
        assertEquals(listOf("C:", "users", "vessel", "Downloads"), GuestPath.segments(path))
        assertEquals("C:\\", GuestPath.upTo(path, 0))
        assertEquals("C:\\users", GuestPath.upTo(path, 1))
        assertEquals("C:\\users\\vessel", GuestPath.upTo(path, 2))
    }

    @Test
    fun `nameOf is the last segment`() {
        assertEquals("npp.exe", GuestPath.nameOf("C:\\users\\vessel\\npp.exe"))
    }
}
