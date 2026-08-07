package app.vessel.data

import app.vessel.core.ComponentType
import app.vessel.core.PrefixRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Preparing checklist.
 *
 * Two behaviours carry the weight: a failure has to stop at the step that failed
 * and stay attributable to it — DESIGN.md's Failed state exists so a user is
 * never shown "something went wrong" — and a second launch has to skip work it
 * has already done, per component, or every start re-extracts gigabytes.
 */
class ContainerProvisionerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var paths: ContainerPaths
    private lateinit var provisioner: ContainerProvisioner
    private lateinit var packages: File

    private val containerId = "c1"

    @Before
    fun setUp() {
        paths = ContainerPaths(temp.newFolder("files"))
        provisioner = ContainerProvisioner(paths, WcpInstaller(json), json)
        packages = temp.newFolder("packages")
    }

    private fun component(
        type: ComponentType,
        packageId: String,
        versionCode: Int = 1,
        payload: Map<String, String> = mapOf("payload.bin" to packageId),
    ) = ComponentInstall(
        type = type,
        packageId = packageId,
        versionCode = versionCode,
        archive = TestWcp.standard(
            File(packages, "$packageId.wcp"),
            type = type.wire,
            versionName = packageId,
            versionCode = versionCode,
            payload = payload,
        ),
    )

    private fun run(components: List<ComponentInstall>): List<ProvisionProgress> =
        runBlocking { provisioner.provision(containerId, components).toList() }

    // — the plan --------------------------------------------------------------

    @Test
    fun `the checklist is layout, one row per component, registry, boot and ready`() {
        val steps = provisioner.plan(
            listOf(
                component(ComponentType.WINE, "wine-10.13-canoe"),
                component(ComponentType.FEXCORE, "fex-2608-canoe"),
                component(ComponentType.TURNIP, "turnip-26.3.0-canoe"),
                component(ComponentType.DXVK, "dxvk-2.7.1-canoe"),
                component(ComponentType.VKD3D, "vkd3d-3.0.1-canoe"),
            ),
        )
        assertEquals(
            listOf(
                "layout", "component:Wine", "component:FEXCore", "component:Turnip",
                "component:DXVK", "component:VKD3D", "registry", "boot", "ready",
            ),
            steps.map { it.id },
        )
        assertEquals(
            listOf(
                "Create prefix", "Install Wine", "Install FEX", "Install Turnip",
                "Install DXVK", "Install vkd3d", "First-run registry",
                "Initialise Wine prefix", "Ready to start",
            ),
            steps.map { it.label },
        )
        assertTrue(steps.all { it.status == ProvisionStatus.PENDING })
    }

    // — the happy path --------------------------------------------------------

    @Test
    fun `a first run installs everything and ends provisioned`() {
        val components = listOf(
            component(ComponentType.WINE, "wine-10.13-canoe"),
            component(ComponentType.DXVK, "dxvk-2.7.1-canoe"),
        )
        val emissions = run(components)

        val first = emissions.first()
        assertEquals(ProvisionPhase.PREPARING, first.phase)
        assertTrue(first.steps.all { it.status == ProvisionStatus.PENDING })

        val last = emissions.last()
        assertEquals(ProvisionPhase.PROVISIONED, last.phase)
        assertEquals(1f, last.fraction, 0.0f)
        assertEquals(null, last.failedStep)

        val layout = paths.of(containerId)
        assertEquals(
            "wine-10.13-canoe",
            File(layout.component(ComponentType.WINE), "payload.bin").readText(),
        )
        assertEquals(
            "dxvk-2.7.1-canoe",
            File(layout.component(ComponentType.DXVK), "payload.bin").readText(),
        )
    }

    @Test
    fun `every step reports running before it reports done`() {
        val emissions = run(listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe")))
        val everRunning = emissions
            .flatMap { it.steps }
            .filter { it.status == ProvisionStatus.RUNNING }
            .map { it.id }
            .toSet()
        assertTrue("layout" in everRunning)
        assertTrue("component:DXVK" in everRunning)
        assertTrue("registry" in everRunning)
    }

    @Test
    fun `the registry seed is written to the container, not applied`() {
        run(listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe")))
        val layout = paths.of(containerId)
        assertTrue(layout.registrySeed.isFile)
        assertEquals(PrefixRegistry.render(), layout.registrySeed.readText())
    }

    @Test
    fun `the prefix directory exists but is empty, because wineboot has not run`() {
        run(listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe")))
        val prefix = paths.of(containerId).prefix
        assertTrue(prefix.isDirectory)
        assertEquals(emptyList<File>(), prefix.listFiles()?.toList().orEmpty())
    }

    @Test
    fun `the boot step is skipped and names the missing launcher`() {
        val last = run(listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe"))).last()
        val boot = last.steps.single { it.id == "boot" }
        assertEquals(ProvisionStatus.SKIPPED, boot.status)
        assertEquals(PrefixBootstrap.Deferred.REASON, boot.detail)
        // Skipping the integration point must not block the rest.
        assertEquals(ProvisionPhase.PROVISIONED, last.phase)
    }

    @Test
    fun `a bootstrap that works marks the step done instead`() {
        val bootstrap = object : PrefixBootstrap {
            var created = false
            var applied: File? = null
            override suspend fun createPrefix(layout: ContainerLayout): BootstrapOutcome {
                created = true
                return BootstrapOutcome.Applied
            }

            override suspend fun applyRegistry(
                layout: ContainerLayout,
                regFile: File,
            ): BootstrapOutcome {
                applied = regFile
                return BootstrapOutcome.Applied
            }
        }
        val last = runBlocking {
            provisioner.provision(
                containerId,
                listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe")),
                bootstrap,
            ).toList()
        }.last()

        assertTrue(bootstrap.created)
        assertEquals(paths.of(containerId).registrySeed, bootstrap.applied)
        assertEquals(ProvisionStatus.DONE, last.steps.single { it.id == "boot" }.status)
    }

    @Test
    fun `a bootstrap that fails fails the run`() {
        val bootstrap = object : PrefixBootstrap {
            override suspend fun createPrefix(layout: ContainerLayout) =
                BootstrapOutcome.Failed("wineserver did not start")

            override suspend fun applyRegistry(layout: ContainerLayout, regFile: File) =
                BootstrapOutcome.Applied
        }
        val last = runBlocking {
            provisioner.provision(
                containerId,
                listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe")),
                bootstrap,
            ).toList()
        }.last()

        assertEquals(ProvisionPhase.FAILED, last.phase)
        assertEquals("boot", last.failedStep?.id)
        assertEquals("wineserver did not start", last.failedStep?.detail)
    }

    // — resuming --------------------------------------------------------------

    @Test
    fun `a second run skips everything`() {
        val components = listOf(
            component(ComponentType.WINE, "wine-10.13-canoe"),
            component(ComponentType.DXVK, "dxvk-2.7.1-canoe"),
        )
        run(components)
        assertTrue(runBlocking { provisioner.isProvisioned(containerId, components) })

        val last = run(components).last()
        assertEquals(ProvisionPhase.PROVISIONED, last.phase)
        listOf("layout", "component:Wine", "component:DXVK", "registry").forEach { id ->
            assertEquals(
                "$id should have been skipped",
                ProvisionStatus.SKIPPED,
                last.steps.single { it.id == id }.status,
            )
        }
    }

    @Test
    fun `a component update re-runs only that component`() {
        val wine = component(ComponentType.WINE, "wine-10.13-canoe")
        run(listOf(wine, component(ComponentType.DXVK, "dxvk-2.7.1-canoe", versionCode = 20701)))

        val newerDxvk = component(
            ComponentType.DXVK,
            "dxvk-2.8.0-canoe",
            versionCode = 20800,
            payload = mapOf("payload.bin" to "dxvk-2.8.0-canoe"),
        )
        val last = run(listOf(wine, newerDxvk)).last()

        assertEquals(
            ProvisionStatus.SKIPPED,
            last.steps.single { it.id == "component:Wine" }.status,
        )
        assertEquals(
            ProvisionStatus.DONE,
            last.steps.single { it.id == "component:DXVK" }.status,
        )
        assertEquals(
            "dxvk-2.8.0-canoe",
            File(paths.of(containerId).component(ComponentType.DXVK), "payload.bin").readText(),
        )
    }

    @Test
    fun `a recorded install whose directory has gone is redone`() {
        val components = listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe"))
        run(components)
        paths.of(containerId).component(ComponentType.DXVK).deleteRecursively()

        assertFalse(runBlocking { provisioner.isProvisioned(containerId, components) })
        val last = run(components).last()
        assertEquals(
            ProvisionStatus.DONE,
            last.steps.single { it.id == "component:DXVK" }.status,
        )
    }

    @Test
    fun `the state records which versions are installed`() {
        run(
            listOf(
                component(ComponentType.WINE, "wine-10.13-canoe", versionCode = 1013),
                component(ComponentType.DXVK, "dxvk-2.7.1-canoe", versionCode = 20701),
            ),
        )
        val state = runBlocking { provisioner.state(containerId) }
        assertEquals(CURRENT_PROVISION_SCHEMA, state.schemaVersion)
        assertEquals(PrefixRegistry.SEED_VERSION, state.registrySeedVersion)
        assertEquals(InstalledRecord("wine-10.13-canoe", 1013), state.components["Wine"])
        assertEquals(InstalledRecord("dxvk-2.7.1-canoe", 20701), state.components["DXVK"])
    }

    @Test
    fun `invalidate makes the next run redo everything`() {
        val components = listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe"))
        run(components)
        runBlocking { provisioner.invalidate(containerId) }

        assertFalse(runBlocking { provisioner.isProvisioned(containerId, components) })
        val last = run(components).last()
        assertEquals(
            ProvisionStatus.DONE,
            last.steps.single { it.id == "component:DXVK" }.status,
        )
    }

    @Test
    fun `an unprovisioned container is not claimed to be provisioned`() {
        assertFalse(
            runBlocking {
                provisioner.isProvisioned(containerId, listOf(component(ComponentType.DXVK, "d")))
            },
        )
    }

    // — failure ---------------------------------------------------------------

    @Test
    fun `a zstd package fails its own step and stops the run there`() {
        val zstd = File(packages, "wine.wcp")
        zstd.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64))

        val last = run(
            listOf(
                ComponentInstall(ComponentType.WINE, "wine-10.13-canoe", 1013, zstd),
                component(ComponentType.DXVK, "dxvk-2.7.1-canoe"),
            ),
        ).last()

        assertEquals(ProvisionPhase.FAILED, last.phase)
        assertEquals("component:Wine", last.failedStep?.id)
        assertTrue(last.failedStep?.detail.orEmpty().contains("zstd"))

        // The steps after it were never attempted, and say so.
        assertEquals(
            ProvisionStatus.PENDING,
            last.steps.single { it.id == "component:DXVK" }.status,
        )
        assertEquals(ProvisionStatus.PENDING, last.steps.single { it.id == "ready" }.status)
        assertFalse(paths.of(containerId).registrySeed.exists())
    }

    @Test
    fun `a package of the wrong type fails rather than installing somewhere else`() {
        val mislabelled = ComponentInstall(
            type = ComponentType.WINE,
            packageId = "not-wine",
            versionCode = 1,
            archive = TestWcp.standard(File(packages, "mislabelled.wcp"), type = "DXVK"),
        )
        val last = run(listOf(mislabelled)).last()
        assertEquals(ProvisionPhase.FAILED, last.phase)
        assertEquals("component:Wine", last.failedStep?.id)
        assertNotNull(last.failedStep?.detail)
    }

    @Test
    fun `a failed run leaves nothing recorded for the failed component`() {
        val zstd = File(packages, "wine.wcp")
        zstd.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64))
        run(listOf(ComponentInstall(ComponentType.WINE, "wine-10.13-canoe", 1013, zstd)))

        assertTrue(runBlocking { provisioner.state(containerId) }.components.isEmpty())
    }

    @Test
    fun `progress counts finished steps, skipped included`() {
        val last = run(listOf(component(ComponentType.DXVK, "dxvk-2.7.1-canoe"))).last()
        assertEquals(5, last.total)
        assertEquals(5, last.completed)
    }
}
