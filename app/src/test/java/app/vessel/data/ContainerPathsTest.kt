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
    fun `a component lives in the shared store under its type and version`() {
        // Outside the container directory on purpose: Wine is 912 MB, and three
        // containers on the same build must not be three copies of it.
        val store = paths.components
        assertEquals(File(filesDir, "components"), store.root)
        assertEquals(File(filesDir, "components/Wine/1013"), store.version(ComponentType.WINE, 1013))
        assertEquals(File(filesDir, "components/FEXCore/2608"), store.version(ComponentType.FEXCORE, 2608))
        assertEquals(File(filesDir, "components/Turnip/260300"), store.version(ComponentType.TURNIP, 260300))
        assertEquals(File(filesDir, "components/DXVK/20701"), store.version(ComponentType.DXVK, 20701))
        assertEquals(File(filesDir, "components/VKD3D/30001"), store.version(ComponentType.VKD3D, 30001))

        assertEquals(File(filesDir, "components/DXVK/20701.json"), store.record(ComponentType.DXVK, 20701))
        assertEquals(File(filesDir, "components/DXVK/20701/profile.json"), store.profile(ComponentType.DXVK, 20701))
        assertEquals(File(filesDir, "components/.staging"), store.staging)
    }

    @Test
    fun `no container path can reach a component, so deleting one cannot delete the other`() {
        val layout = paths.of("abc")
        val wine = paths.components.version(ComponentType.WINE, 1013)
        assertFalse(wine.path.startsWith(layout.base.path + File.separator))
    }

    @Test
    fun `two versions of one type sit alongside each other`() {
        val store = paths.components
        store.version(ComponentType.WINE, 1013).mkdirs()
        store.version(ComponentType.WINE, 1100).mkdirs()
        // Neither is a package until a profile proves it is one.
        assertEquals(emptyList<Int>(), store.versions(ComponentType.WINE))

        File(store.version(ComponentType.WINE, 1013), "profile.json").writeText("{}")
        File(store.version(ComponentType.WINE, 1100), "profile.json").writeText("{}")
        assertEquals(listOf(1100, 1013), store.versions(ComponentType.WINE))
    }

    @Test
    fun `the staging directory is not mistaken for a component`() {
        val store = paths.components
        assertTrue(store.createDirectories())
        // `.staging` is a directory directly under the store root, and the only
        // thing stopping it being read as a type is that listing is driven by
        // ComponentType rather than by the filesystem.
        assertTrue(store.staging.isDirectory)
        assertEquals(emptyList<ComponentVersion>(), store.installed())
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
        listOf(layout.base, layout.prefix, layout.tmp, layout.logs)
            .forEach { assertTrue("$it should exist", it.isDirectory) }
        // The container does not get a components directory any more.
        assertFalse(layout.legacyComponents.exists())
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
