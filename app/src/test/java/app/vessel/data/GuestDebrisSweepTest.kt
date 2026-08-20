package app.vessel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GuestDebrisSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun prefix(): File = temp.newFolder("prefix")

    /** `prefix/drive_c/users/steamuser/AppData/Roaming/<app>/Crashpad`. */
    private fun crashpad(prefix: File, app: String = "Code - Insiders"): File =
        prefix.resolve("drive_c/users/steamuser/AppData/Roaming/$app/Crashpad")
            .also { it.mkdirs() }

    private fun dump(crashpad: File, name: String, bytes: Int, modified: Long): File =
        crashpad.resolve("reports").apply { mkdirs() }.resolve(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    @Test
    fun `keeps the newest reports and removes the rest`() {
        val prefix = prefix()
        val cp = crashpad(prefix)
        val oldest = dump(cp, "a.dmp", 300, 1_000L)
        val middle = dump(cp, "b.dmp", 200, 2_000L)
        val newest = dump(cp, "c.dmp", 100, 3_000L)

        val freed = sweepGuestDebris(prefix, keep = 2)

        assertEquals(300L, freed)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `a report under the keep count is left alone`() {
        val prefix = prefix()
        val cp = crashpad(prefix)
        val only = dump(cp, "a.dmp", 500, 1_000L)

        assertEquals(0L, sweepGuestDebris(prefix, keep = 2))
        assertTrue(only.exists())
    }

    @Test
    fun `an attachment directory goes with the report it belongs to`() {
        val prefix = prefix()
        val cp = crashpad(prefix)
        dump(cp, "keep.dmp", 10, 3_000L)
        dump(cp, "drop.dmp", 10, 1_000L)

        val attachments = cp.resolve("attachments")
        val doomed = attachments.resolve("drop").apply { mkdirs() }
        doomed.resolve("blob").writeBytes(ByteArray(64))
        // A sibling whose name merely starts the same must survive: matching by
        // prefix rather than exactly would take this one too.
        val sibling = attachments.resolve("dropped").apply { mkdirs() }
        sibling.resolve("blob").writeBytes(ByteArray(64))

        val freed = sweepGuestDebris(prefix, keep = 1)

        assertEquals(74L, freed)
        assertFalse(doomed.exists())
        assertTrue(sibling.resolve("blob").exists())
    }

    @Test
    fun `nothing outside a Crashpad directory is touched`() {
        val prefix = prefix()
        val cp = crashpad(prefix)
        dump(cp, "a.dmp", 10, 1_000L)
        dump(cp, "b.dmp", 10, 2_000L)
        dump(cp, "c.dmp", 10, 3_000L)

        // The things a user would actually mind losing, all older than every
        // dump so that an age-based mistake would take them first.
        val app = checkNotNull(cp.parentFile)
        val settings = app.resolve("User/settings.json").apply { parentFile!!.mkdirs() }
        settings.writeText("{}")
        settings.setLastModified(1L)
        val db = app.resolve("databases/state.vscdb").apply { parentFile!!.mkdirs() }
        db.writeBytes(ByteArray(128))
        db.setLastModified(1L)

        sweepGuestDebris(prefix, keep = 1)

        assertTrue(settings.exists())
        assertTrue(db.exists())
    }

    /**
     * The one that matters. Container deletion once walked through a prefix's
     * links and deleted the user's real storage; this asserts a disk-reclaim
     * helper cannot repeat it.
     */
    @Test
    fun `the walk refuses to follow a symlink out of the prefix`() {
        val prefix = prefix()
        val outside = temp.newFolder("outside")
        val bystander = outside.resolve("Crashpad/reports").apply { mkdirs() }
        val precious = bystander.resolve("a.dmp")
        precious.writeBytes(ByteArray(999))
        precious.setLastModified(1L)
        bystander.resolve("b.dmp").writeBytes(ByteArray(1))
        bystander.resolve("c.dmp").writeBytes(ByteArray(1))

        val appData = prefix.resolve("drive_c/users/steamuser/AppData/Roaming")
            .apply { mkdirs() }
        // Windows needs developer mode or elevation for this; where it is not
        // permitted there is nothing to assert, so say so rather than pass.
        val linked = runCatching {
            Files.createSymbolicLink(appData.resolve("Linked").toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("symlink creation not permitted here", linked)

        assertEquals(0L, sweepGuestDebris(prefix, keep = 1))
        assertTrue(precious.exists())
        assertEquals(999L, precious.length())
    }

    // — the other dump families ------------------------------------------------

    private fun family(prefix: File, path: String): File =
        prefix.resolve("drive_c/$path").also { it.mkdirs() }

    private fun flat(dir: File, name: String, bytes: Int, modified: Long): File =
        dir.resolve(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    @Test
    fun `Windows Error Reporting CrashDumps are swept flat`() {
        val prefix = prefix()
        val wer = family(prefix, "users/steamuser/AppData/Local/CrashDumps")
        val old = flat(wer, "app.exe.1234.dmp", 400, 1_000L)
        val recent = flat(wer, "app.exe.5678.dmp", 10, 9_000L)

        assertEquals(400L, sweepGuestDebris(prefix, keep = 1))
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `a Gecko minidump takes its extra sidecar with it`() {
        val prefix = prefix()
        val md = family(prefix, "users/steamuser/AppData/Roaming/Pale Moon/minidumps")
        val doomedDump = flat(md, "abc.dmp", 100, 1_000L)
        val doomedExtra = flat(md, "abc.extra", 20, 1_050L)
        val keptDump = flat(md, "xyz.dmp", 100, 9_000L)
        val keptExtra = flat(md, "xyz.extra", 20, 9_050L)

        // One report retained, and a report is a dump plus its sidecar -- not
        // two files, which is what a naive newest-N-files rule would keep.
        assertEquals(120L, sweepGuestDebris(prefix, keep = 1))
        assertFalse(doomedDump.exists())
        assertFalse(doomedExtra.exists())
        assertTrue(keptDump.exists())
        assertTrue(keptExtra.exists())
    }

    @Test
    fun `a dmp outside a known dump directory is never touched`() {
        val prefix = prefix()
        // Same extension, ordinary location: this is a file a user could have
        // put anywhere, and nothing here may assume otherwise.
        val docs = family(prefix, "users/steamuser/Documents")
        val theirs = flat(docs, "notes.dmp", 500, 1L)

        assertEquals(0L, sweepGuestDebris(prefix, keep = 0))
        assertTrue(theirs.exists())
    }
}
