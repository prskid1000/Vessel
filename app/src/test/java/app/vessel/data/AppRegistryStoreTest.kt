package app.vessel.data

import androidx.datastore.core.DataStoreFactory
import app.vessel.core.PeArchitecture
import app.vessel.ui.shell.AppShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The registry's behaviour, against a real DataStore on a real temporary file.
 *
 * Not a fake. The interesting parts of this class exist only because it
 * persists — that a reopened store still has the list, and that `add` for an
 * executable already present replaces rather than duplicates — and a fake would
 * answer the first of those by construction.
 */
class AppRegistryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * A store, plus the scope that owns it.
     *
     * DataStore refuses a second instance over a file that already has a live
     * one, and it releases the claim when the owning scope's job completes — so
     * a reopen test has to actually retire the first scope rather than just drop
     * the reference. Hence returning the scope instead of hiding it.
     */
    private fun open(file: File): Pair<AppRegistryStore, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = AppRegistryStore(
            DataStoreFactory.create(
                serializer = AppShortcutDocumentSerializer(Json { encodeDefaults = true }),
                scope = scope,
                produceFile = { file },
            ),
        )
        return store to scope
    }

    private fun file(): File = File(temp.newFolder(), "shortcuts.json")

    private fun shortcut(
        executable: String,
        name: String = "Program",
        container: String = "c1",
        args: String = "",
    ) = AppShortcut(
        id = "",
        containerId = container,
        executable = executable,
        name = name,
        arch = PeArchitecture.X64,
        args = args,
    )

    /** `mergeShortcut` takes an already-assigned shortcut; the store assigns the id. */
    private fun AppShortcut.withId(value: String) = copy(id = value)

    @Test
    fun `a shortcut survives the store being reopened`() = runBlocking {
        val path = file()
        val (first, scope) = open(path)
        val added = first.add(shortcut("C:\\Games\\x.exe", name = "X"))
        assertTrue("the registry must assign an id", added.id.isNotBlank())
        scope.coroutineContext.job.cancelAndJoin()

        // A second instance over the same file is the cold start this class
        // exists for. The in-memory scaffold it replaced passed every other
        // assertion in this file and failed this one.
        val (reopened, second) = open(path)
        val all = reopened.shortcuts.first()
        assertEquals(1, all.size)
        assertEquals("X", all.single().name)
        assertEquals(added.id, all.single().id)
        assertEquals(PeArchitecture.X64, all.single().arch)
        second.coroutineContext.job.cancelAndJoin()
    }

    // — the replace rule ------------------------------------------------------
    //
    // Against `mergeShortcut` rather than through the store, and that is a host
    // limitation rather than a preference. DataStore writes a `.tmp` and renames
    // it over the target; on Windows `File.renameTo` will not overwrite an
    // existing destination, so the first write to a fresh file succeeds and every
    // later one throws IOException. On Android `rename(2)` overwrites atomically
    // and the same code is fine. Testing the rule directly is how it stays tested
    // on the machine it is written on.

    @Test
    fun `adding the same executable again replaces it and keeps the original id`() {
        val first = mergeShortcut(emptyList(), shortcut("C:\\Games\\x.exe", name = "X").withId("a"))
        // Different case, because the guest path is a Windows path and these are
        // the same file to everything that will ever open it.
        val again = mergeShortcut(
            first.shortcuts,
            shortcut("c:\\games\\X.EXE", name = "X renamed", args = "-w").withId("b"),
        )

        assertEquals(
            "two tiles for one program is a home screen nobody can tidy",
            1,
            again.shortcuts.size,
        )
        assertEquals("the surviving id must be the one already handed out", "a", again.added.id)
        assertEquals("a", again.shortcuts.single().id)
        assertEquals("X renamed", again.shortcuts.single().name)
        assertEquals("-w", again.shortcuts.single().args)
    }

    @Test
    fun `the same executable in a different container is a different shortcut`() {
        val one = mergeShortcut(
            emptyList(),
            shortcut("C:\\Games\\x.exe", container = "c1").withId("a"),
        )
        val two = mergeShortcut(
            one.shortcuts,
            shortcut("C:\\Games\\x.exe", container = "c2").withId("b"),
        )

        assertEquals(2, two.shortcuts.size)
        assertNotEquals(two.shortcuts[0].id, two.shortcuts[1].id)
    }

    @Test
    fun `a new executable is appended, so the list stays in the order things were added`() {
        val one = mergeShortcut(emptyList(), shortcut("C:\\a.exe").withId("a"))
        val two = mergeShortcut(one.shortcuts, shortcut("C:\\b.exe").withId("b"))

        assertEquals(listOf("a", "b"), two.shortcuts.map { it.id })
    }

    @Test
    fun `an architecture this build does not know reads back as UNKNOWN`() = runBlocking {
        // Written by a newer build, or by one that renamed the constant. The
        // stored value is a cache of what the PE header says, so degrading beats
        // throwing — the cost of being wrong is one re-read.
        val path = file()
        path.writeText(
            """{"schemaVersion":1,"shortcuts":[{"id":"i","containerId":"c1",""" +
                """"executable":"C:\\x.exe","name":"X","arch":"RISCV128"}]}""",
        )
        val (registry, scope) = open(path)

        assertEquals(PeArchitecture.UNKNOWN, registry.shortcuts.first().single().arch)
        scope.coroutineContext.job.cancelAndJoin()
    }
}
