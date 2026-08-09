package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The one test in this project that is about not destroying the user's data.
 *
 * `File.deleteRecursively()` walks with `listFiles()`, and `listFiles()` on a
 * symlink to a directory returns the *target's* children — so deleting a
 * container went through `dosdevices/d:` and emptied the phone's shared
 * storage. It was reported as downloaded games disappearing, twice. Every case
 * below fails against `deleteRecursively()` and passes against [deleteTree].
 */
class DeleteTreeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun symlinksWork(link: File, target: File): Boolean =
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess

    @Test
    fun `a symlinked directory is unlinked, and its contents are untouched`() {
        val container = temp.newFolder("container")
        val outside = temp.newFolder("shared")
        val game = File(outside, "Game.exe").apply { writeText("do not delete me") }
        val dosdevices = File(container, "prefix/dosdevices").apply { mkdirs() }

        Assume.assumeTrue(
            "this host cannot create symlinks",
            symlinksWork(File(dosdevices, "d"), outside),
        )

        assertTrue(deleteTree(container))

        assertFalse("the container survived", container.exists())
        assertTrue("the mapped folder was deleted", outside.isDirectory)
        assertTrue("a file behind the mapping was deleted", game.isFile)
        assertEquals("do not delete me", game.readText())
    }

    @Test
    fun `a symlink to a single file is unlinked, and the file survives`() {
        val container = temp.newFolder("container2")
        val keep = File(temp.newFolder("elsewhere"), "save.dat").apply { writeText("save") }
        val link = File(container, "save.lnk")

        Assume.assumeTrue("this host cannot create symlinks", symlinksWork(link, keep))

        assertTrue(deleteTree(container))
        assertFalse(container.exists())
        assertTrue("the linked file was deleted", keep.isFile)
    }

    @Test
    fun `the root itself being a symlink deletes the link and nothing else`() {
        val outside = temp.newFolder("target")
        File(outside, "keep.txt").writeText("keep")
        val link = File(temp.root, "link")

        Assume.assumeTrue("this host cannot create symlinks", symlinksWork(link, outside))

        assertTrue(deleteTree(link))
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertTrue(outside.isDirectory)
        assertTrue(File(outside, "keep.txt").isFile)
    }

    @Test
    fun `real nested content is still deleted`() {
        val root = temp.newFolder("tree")
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/c/file.bin").writeText("x")
        File(root, "a/top.txt").writeText("y")

        assertTrue(deleteTree(root))
        assertFalse(root.exists())
    }

    @Test
    fun `a path that is not there is already deleted`() {
        assertTrue(deleteTree(File(temp.root, "never-existed")))
    }

    @Test
    fun `a dangling symlink is removed rather than reported missing`() {
        val gone = File(temp.root, "gone")
        val link = File(temp.root, "dangling")
        Assume.assumeTrue("this host cannot create symlinks", symlinksWork(link, gone))

        // `exists()` follows the link and answers false, so a naive
        // "if (!exists()) return true" would leave the link behind for ever.
        assertFalse(link.exists())
        assertTrue(deleteTree(link))
        assertFalse(Files.isSymbolicLink(link.toPath()))
    }
}
