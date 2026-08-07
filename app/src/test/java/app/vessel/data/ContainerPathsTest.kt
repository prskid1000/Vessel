package app.vessel.data

import app.vessel.core.ComponentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The layout, which is only worth having as a class if it is the *only* place
 * that knows these paths. The sanitising test is the one with teeth: a container
 * id reaches the filesystem as a string, and one separator in the wrong place is
 * a write outside the app's private directory.
 */
class ContainerPathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var paths: ContainerPaths

    @org.junit.Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        paths = ContainerPaths(filesDir)
    }

    @Test
    fun `a container's directories are where the layout says`() {
        val layout = paths.of("abc")
        assertEquals(File(filesDir, "containers/abc"), layout.base)
        assertEquals(File(filesDir, "containers/abc/prefix"), layout.prefix)
        assertEquals(File(filesDir, "containers/abc/components"), layout.components)
        assertEquals(File(filesDir, "containers/abc/tmp"), layout.tmp)
        assertEquals(File(filesDir, "containers/abc/provisioned.json"), layout.provisionState)
        assertEquals(File(filesDir, "containers/abc/prefix-seed.reg"), layout.registrySeed)
    }

    @Test
    fun `logs stay where SessionLogStore already writes them`() {
        // Deliberately outside the container directory: that store has owned this
        // tree since before the layout existed, and moving it would orphan every
        // log already on a device.
        assertEquals(File(filesDir, "logs/abc"), paths.of("abc").logs)
    }

    @Test
    fun `a component lives under its type`() {
        val layout = paths.of("abc")
        assertEquals(File(filesDir, "containers/abc/components/Wine"), layout.component(ComponentType.WINE))
        assertEquals(File(filesDir, "containers/abc/components/FEXCore"), layout.component(ComponentType.FEXCORE))
        assertEquals(File(filesDir, "containers/abc/components/Turnip"), layout.component(ComponentType.TURNIP))
        assertEquals(File(filesDir, "containers/abc/components/DXVK"), layout.component(ComponentType.DXVK))
        assertEquals(File(filesDir, "containers/abc/components/VKD3D"), layout.component(ComponentType.VKD3D))
    }

    @Test
    fun `an id that would escape the directory is sanitised to one segment`() {
        // Dots are not letters or digits either, so they go too — the result is
        // one segment that can only ever land inside `containers/`.
        assertEquals("______etc_passwd", ContainerPaths.safeName("../../etc/passwd"))
        assertEquals("a_b", ContainerPaths.safeName("a/b"))
        assertEquals("a_b", ContainerPaths.safeName("""a\b"""))
        // Only a genuinely empty id falls back to a name: every other character
        // becomes an underscore, so the result is never blank once there was
        // anything there at all. Either way it is one segment, which is the
        // property that matters.
        assertEquals("unnamed", ContainerPaths.safeName(""))
        assertEquals("___", ContainerPaths.safeName("///"))
        assertEquals("___", ContainerPaths.safeName("   "))

        val escaping = paths.of("../../etc/passwd")
        assertTrue(escaping.base.path.startsWith(File(filesDir, "containers").path))
    }

    @Test
    fun `a UUID passes through untouched, as does a dash or underscore`() {
        val uuid = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        assertEquals(uuid, ContainerPaths.safeName(uuid))
        assertEquals("a_b-c", ContainerPaths.safeName("a_b-c"))
    }

    @Test
    fun `createDirectories makes every directory the layout promises, and is idempotent`() {
        val layout = paths.of("abc")
        assertFalse(layout.directoriesExist())
        assertTrue(layout.createDirectories())
        assertTrue(layout.directoriesExist())
        listOf(layout.base, layout.prefix, layout.components, layout.tmp, layout.logs)
            .forEach { assertTrue("$it should exist", it.isDirectory) }
        assertTrue(layout.createDirectories())
    }

    @Test
    fun `existing lists only container directories that are really there`() {
        assertEquals(emptyList<File>(), paths.existing())
        paths.of("one").createDirectories()
        paths.of("two").createDirectories()
        assertEquals(setOf("one", "two"), paths.existing().map { it.name }.toSet())
    }
}
