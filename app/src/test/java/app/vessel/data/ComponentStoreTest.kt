package app.vessel.data

import app.vessel.core.ComponentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The shared component store, and the one bug in it that destroys user data.
 *
 * Wine is 912 MB unpacked and one copy now serves every container, so the
 * question "may this be deleted" has exactly one safe answer and several unsafe
 * ones. Most of what is here is that question: two containers on one version,
 * one of them deleted, a `provisioned.json` written before the reference field
 * existed. Getting any of them wrong deletes a Wine tree somebody is running on,
 * which is why [ComponentStore.prune] is explicit and nothing calls it.
 */
class ComponentStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private lateinit var paths: ContainerPaths
    private lateinit var store: ComponentStore
    private lateinit var packages: File

    @Before
    fun setUp() {
        paths = ContainerPaths(temp.newFolder("files"))
        store = ComponentStore(paths, WcpInstaller(json), json)
        packages = temp.newFolder("packages")
    }

    private fun wine(versionCode: Int = 1013, body: String = "wine-$versionCode"): File =
        TestWcp.standard(
            File(packages, "wine-$versionCode.wcp"),
            type = "Wine",
            versionName = "10.13",
            versionCode = versionCode,
            payload = mapOf("bin/wine" to body),
        )

    private fun dxvk(versionCode: Int = 20701): File =
        TestWcp.standard(
            File(packages, "dxvk-$versionCode.wcp"),
            versionCode = versionCode,
            payload = mapOf("x64/dxgi.dll" to "dxgi"),
        )

    /** A container on disk that references [refs], as `provisioned.json` records them. */
    private fun container(id: String, vararg refs: Pair<ComponentType, Int>) {
        val layout = paths.of(id)
        layout.createDirectories()
        layout.provisionState.writeText(
            json.encodeToString(
                ProvisionedState.serializer(),
                ProvisionedState(
                    componentVersions = refs.associate { (type, code) -> type.wire to code },
                ),
            ),
        )
    }

    private fun installed(): Set<ComponentVersion> =
        runBlocking { store.installed() }.map { it.version }.toSet()

    // — install once ----------------------------------------------------------

    @Test
    fun `a version already in the store is a no-op that still reports success`() {
        val archive = wine()
        val first = runBlocking { store.install(archive, "wine-10.13-canoe") }
            as WcpInstallResult.Installed
        assertFalse(first.reused)

        val payload = File(paths.components.version(ComponentType.WINE, 1013), "bin/wine")
        val stamp = payload.lastModified()

        val second = runBlocking { store.install(archive, "wine-10.13-canoe") }
            as WcpInstallResult.Installed
        assertTrue(second.reused)
        assertEquals(first.directory, second.directory)
        assertEquals("nothing was re-extracted", stamp, payload.lastModified())
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
    }

    @Test
    fun `two versions of one type live side by side`() {
        runBlocking {
            store.install(wine(1013), "wine-10.13-canoe")
            store.install(wine(1100, body = "wine-11.0"), "wine-11.0-canoe")
        }
        assertEquals(
            setOf(
                ComponentVersion(ComponentType.WINE, 1013),
                ComponentVersion(ComponentType.WINE, 1100),
            ),
            installed(),
        )
        assertEquals(
            "wine-1013",
            File(paths.components.version(ComponentType.WINE, 1013), "bin/wine").readText(),
        )
        assertEquals(
            "wine-11.0",
            File(paths.components.version(ComponentType.WINE, 1100), "bin/wine").readText(),
        )
    }

    @Test
    fun `the store reports the package id it was given`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        val stored = runBlocking { store.installed() }.single()
        assertEquals("wine-10.13-canoe", stored.packageId)
        assertEquals(ComponentType.WINE, stored.type)
        assertEquals(1013, stored.versionCode)
        assertEquals("10.13", stored.profile.versionName)
    }

    // — references ------------------------------------------------------------

    @Test
    fun `two containers reference one version`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        container("c1", ComponentType.WINE to 1013)
        container("c2", ComponentType.WINE to 1013)

        assertEquals(
            setOf("c1", "c2"),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
    }

    @Test
    fun `a version nothing references is reported with an empty reference set`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        assertEquals(
            emptySet<String>(),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
    }

    @Test
    fun `a container references what it resolves to`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        container("c1", ComponentType.WINE to 1013)

        assertEquals(
            paths.components.version(ComponentType.WINE, 1013),
            runBlocking { store.directoryFor("c1", ComponentType.WINE) },
        )
        assertNull(runBlocking { store.directoryFor("c1", ComponentType.DXVK) })
        assertNull(runBlocking { store.directoryFor("nobody", ComponentType.WINE) })
    }

    // — adoptLatest: what lets a container launch at all ----------------------

    @Test
    fun `a container with no references adopts the newest of every installed type`() {
        runBlocking {
            store.install(wine(1013), "wine-10.13-canoe")
            store.install(wine(1114), "wine-11.14-canoe")
            store.install(dxvk(), "dxvk-2.7.1-canoe")
        }
        container("fresh")

        val adopted = runBlocking { store.adoptLatest("fresh") }
        assertEquals(1114, adopted[ComponentType.WINE])
        assertEquals(20701, adopted[ComponentType.DXVK])
        assertEquals(
            paths.components.version(ComponentType.WINE, 1114),
            runBlocking { store.directoryFor("fresh", ComponentType.WINE) },
        )
    }

    /**
     * The upgrade that must not happen behind the user's back.
     *
     * A container's prefix was booted against the build it references. Silently
     * re-pointing it at a newer one on the next launch breaks a working setup
     * with no user action to attribute it to.
     */
    @Test
    fun `an existing reference is never re-pointed at a newer version`() {
        runBlocking {
            store.install(wine(1013), "wine-10.13-canoe")
            store.install(wine(1114), "wine-11.14-canoe")
        }
        container("pinned", ComponentType.WINE to 1013)

        assertFalse(ComponentType.WINE in runBlocking { store.adoptLatest("pinned") })
        assertEquals(
            paths.components.version(ComponentType.WINE, 1013),
            runBlocking { store.directoryFor("pinned", ComponentType.WINE) },
        )
    }

    @Test
    fun `adopting nothing writes nothing, and an empty store adopts nothing`() {
        container("empty")
        assertTrue(runBlocking { store.adoptLatest("empty") }.isEmpty())
        assertNull(runBlocking { store.directoryFor("empty", ComponentType.WINE) })
    }

    @Test
    fun `an adopted version counts as a reference, so prune cannot take it`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        container("c1")
        runBlocking { store.adoptLatest("c1") }

        assertEquals(setOf("c1"), runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)])
        assertTrue(runBlocking { store.prune() }.isEmpty)
    }

    /**
     * The case that would delete a live Wine tree.
     *
     * A `provisioned.json` written before `componentVersions` existed has no
     * such key. kotlinx.serialization fills a missing field from its default, so
     * it decodes as referencing nothing — and if that were the only field read,
     * the first `prune` after an upgrade would delete every component on the
     * device while the containers using them still expected them there.
     */
    @Test
    fun `a pre-upgrade container with no componentVersions field still counts as a reference`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }

        val layout = paths.of("old")
        layout.createDirectories()
        layout.provisionState.writeText(
            """
            {
              "schemaVersion": 1,
              "registrySeedVersion": 3,
              "components": {
                "Wine": { "packageId": "wine-10.13-canoe", "versionCode": 1013 }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            setOf("old"),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
        assertTrue(runBlocking { store.prune() }.isEmpty)
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
    }

    // — deletion --------------------------------------------------------------

    @Test
    fun `deleting one container leaves a component the other still uses`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        container("c1", ComponentType.WINE to 1013)
        container("c2", ComponentType.WINE to 1013)

        // What ContainerRepository.delete does to disk.
        paths.of("c1").base.deleteRecursively()

        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
        assertEquals(
            setOf("c2"),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
        assertTrue("c2 is still on it", runBlocking { store.prune() }.isEmpty)
        assertTrue(paths.components.isInstalled(ComponentType.WINE, 1013))
    }

    @Test
    fun `deleting a container does not delete its components on its own`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        container("c1", ComponentType.WINE to 1013)
        paths.of("c1").base.deleteRecursively()

        // Unreferenced, and still there: nothing prunes implicitly. A mistake in
        // the counting has to cost disk, never a 912 MB Wine tree.
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
        assertEquals(
            emptySet<String>(),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
    }

    // — prune -----------------------------------------------------------------

    @Test
    fun `prune removes only the versions nothing references`() {
        runBlocking {
            store.install(wine(1013), "wine-10.13-canoe")
            store.install(wine(1100, body = "wine-11.0"), "wine-11.0-canoe")
            store.install(dxvk(), "dxvk-2.7.1-canoe")
        }
        // One container pinned to the older Wine keeps it alive; nothing uses
        // the newer one or the DXVK.
        container("c1", ComponentType.WINE to 1013)

        val result = runBlocking { store.prune() }

        assertEquals(
            setOf(
                ComponentVersion(ComponentType.WINE, 1100),
                ComponentVersion(ComponentType.DXVK, 20701),
            ),
            result.removed.toSet(),
        )
        assertTrue(result.freedBytes > 0)
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
        assertEquals(
            "wine-1013",
            File(paths.components.version(ComponentType.WINE, 1013), "bin/wine").readText(),
        )
        assertFalse(paths.components.version(ComponentType.WINE, 1100).exists())
        assertFalse(paths.components.record(ComponentType.WINE, 1100).exists())
    }

    @Test
    fun `prune on a store with nothing spare removes nothing and says so`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        container("c1", ComponentType.WINE to 1013)

        val result = runBlocking { store.prune() }
        assertTrue(result.isEmpty)
        assertEquals(0L, result.freedBytes)
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
    }

    @Test
    fun `prune is idempotent`() {
        runBlocking { store.install(wine(), "wine-10.13-canoe") }
        assertEquals(1, runBlocking { store.prune() }.removed.size)
        assertTrue(runBlocking { store.prune() }.isEmpty)
        assertEquals(emptySet<ComponentVersion>(), installed())
    }

    // — migration -------------------------------------------------------------

    /** The old layout: `containers/<id>/components/<Type>/`, with its payload. */
    private fun legacy(
        containerId: String,
        type: ComponentType,
        versionCode: Int,
        packageId: String? = null,
        body: String = "legacy-$versionCode",
    ) {
        val layout = paths.of(containerId)
        layout.createDirectories()
        val directory = File(layout.legacyComponents, type.wire)
        File(directory, "bin").mkdirs()
        File(directory, "bin/wine").writeText(body)
        File(directory, "profile.json").writeText(
            TestWcp.profileJson(
                type = type.wire,
                versionName = "10.13",
                versionCode = versionCode,
                files = listOf("bin/wine"),
            ),
        )
        if (packageId != null) {
            layout.provisionState.writeText(
                json.encodeToString(
                    ProvisionedState.serializer(),
                    ProvisionedState(
                        components = mapOf(type.wire to InstalledRecord(packageId, versionCode)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `a per-container install is moved into the store rather than orphaned`() {
        legacy("c1", ComponentType.WINE, 1013, packageId = "wine-10.13-canoe")

        val result = runBlocking { store.migrate() }

        assertEquals(listOf(ComponentVersion(ComponentType.WINE, 1013)), result.moved)
        assertEquals(emptyList<ComponentVersion>(), result.discarded)
        assertEquals(emptyList<String>(), result.unresolved)

        // The bytes are in the store, the old directory is gone, and the
        // container now references what it used to own.
        assertEquals(
            "legacy-1013",
            File(paths.components.version(ComponentType.WINE, 1013), "bin/wine").readText(),
        )
        assertFalse(paths.of("c1").legacyComponents.exists())
        assertEquals(
            setOf("c1"),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
        assertEquals("wine-10.13-canoe", runBlocking { store.installed() }.single().packageId)
    }

    @Test
    fun `two containers on one build end up sharing a single copy`() {
        legacy("c1", ComponentType.WINE, 1013, packageId = "wine-10.13-canoe")
        legacy("c2", ComponentType.WINE, 1013, packageId = "wine-10.13-canoe")

        val result = runBlocking { store.migrate() }

        assertEquals(1, result.moved.size)
        assertEquals(1, result.discarded.size)
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
        assertEquals(
            setOf("c1", "c2"),
            runBlocking { store.references() }[ComponentVersion(ComponentType.WINE, 1013)],
        )
        assertFalse(paths.of("c1").legacyComponents.exists())
        assertFalse(paths.of("c2").legacyComponents.exists())
        // And neither is now prunable.
        assertTrue(runBlocking { store.prune() }.isEmpty)
    }

    @Test
    fun `a legacy install with no provisioned record is keyed from its own profile`() {
        legacy("c1", ComponentType.DXVK, 20701)

        val result = runBlocking { store.migrate() }

        assertEquals(listOf(ComponentVersion(ComponentType.DXVK, 20701)), result.moved)
        assertTrue(paths.components.isInstalled(ComponentType.DXVK, 20701))
        // No id was ever recorded for it, so the store says so rather than
        // inventing the registry's name for the build.
        assertNull(runBlocking { store.installed() }.single().packageId)
    }

    @Test
    fun `a legacy directory that cannot be keyed is left alone rather than deleted`() {
        val layout = paths.of("c1")
        layout.createDirectories()
        File(layout.legacyComponents, "Wine").mkdirs()
        File(layout.legacyComponents, "Wine/bin").mkdirs()
        File(layout.legacyComponents, "Wine/bin/wine").writeText("no profile, no record")

        val result = runBlocking { store.migrate() }

        assertEquals(listOf("c1/Wine"), result.unresolved)
        assertTrue(File(layout.legacyComponents, "Wine/bin/wine").isFile)
        assertEquals(emptySet<ComponentVersion>(), installed())
    }

    @Test
    fun `migration runs once and a device with nothing to migrate does nothing`() {
        assertTrue(runBlocking { store.migrate() }.isEmpty)

        // A legacy directory appearing after the first read is not picked up
        // again in this process — the migration is a one-shot on first read, and
        // nothing creates that directory any more.
        legacy("c1", ComponentType.WINE, 1013, packageId = "wine-10.13-canoe")
        assertTrue(runBlocking { store.migrate() }.isEmpty)
        assertEquals(emptySet<ComponentVersion>(), installed())
    }

    @Test
    fun `reading the store migrates first, so nothing is left stranded`() {
        legacy("c1", ComponentType.WINE, 1013, packageId = "wine-10.13-canoe")

        // No explicit migrate() call: the first read does it.
        assertEquals(setOf(ComponentVersion(ComponentType.WINE, 1013)), installed())
        assertFalse(paths.of("c1").legacyComponents.exists())
    }
}
