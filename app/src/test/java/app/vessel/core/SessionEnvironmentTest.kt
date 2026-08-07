package app.vessel.core

import app.vessel.core.params.ParamGroup
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamSpec
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/LOGGING.md`, asserted.
 *
 * This is the piece of the provisioning layer most likely to be silently wrong,
 * because every one of its failure modes is quiet: the wrong `WINEDEBUG` string
 * produces an empty log rather than an error, `VKD3D_LOG_FILE` set by accident
 * moves the D3D12 layer's output off the pipe without complaining, and an
 * `ADRENOTOOLS_*` variable set to the empty string loads the stock Qualcomm
 * driver and everything appears to work. None of those show up on a device
 * without someone already suspecting them, so they are pinned here.
 */
class SessionEnvironmentTest {

    private val prefix = File("/data/user/0/app.vessel/files/containers/c1/prefix")
    private val logs = File("/data/user/0/app.vessel/files/logs/c1")
    private val paths = SessionPaths(prefix = prefix, logs = logs)

    /**
     * A manifest that still declares the three FEX params, which the shipped one
     * no longer does.
     *
     * Kept deliberately stale: these tests are about what happens when a
     * manifest *tries* to set a reserved variable, and a manifest that has
     * already been cleaned up cannot test that.
     */
    private val fexManifest = ParamManifest(
        schemaVersion = 1,
        groups = listOf(
            ParamGroup(
                id = "display",
                title = "Display",
                params = listOf(
                    // No `env`: consumed by the session surface, not by a child
                    // process. Must contribute nothing.
                    ParamSpec(
                        key = "display.resolution",
                        title = "Resolution",
                        type = ParamType.ENUM,
                        options = listOf("1280x720", "native"),
                        default = JsonPrimitive("1280x720"),
                    ),
                ),
            ),
            ParamGroup(
                id = "compatibility",
                title = "Compatibility",
                params = listOf(
                    ParamSpec(
                        key = "fex.TSOEnabled",
                        title = "Strict memory ordering",
                        type = ParamType.BOOL,
                        default = JsonPrimitive(true),
                        env = "FEX_TSOENABLED",
                    ),
                    ParamSpec(
                        key = "fex.HalfBarrierTSOEnabled",
                        title = "Cheap ordering barriers",
                        type = ParamType.BOOL,
                        default = JsonPrimitive(true),
                        env = "FEX_HALFBARRIERTSOENABLED",
                    ),
                    ParamSpec(
                        key = "fex.VectorTSOEnabled",
                        title = "Ordering for vector memory",
                        type = ParamType.BOOL,
                        default = JsonPrimitive(false),
                        env = "FEX_VECTORTSOENABLED",
                    ),
                ),
            ),
        ),
    )

    private fun container(params: Map<String, ParamValue> = emptyMap()) = ContainerProfile(
        id = "c1",
        name = "Container",
        wineBuild = "wine-10.13-canoe",
        driver = "turnip-26.3.0-canoe",
        d3dLayer = "dxvk-2.7.1-canoe",
        params = params,
    )

    /**
     * The driver comes from the shared store — `files/components/Turnip/<code>`
     * — not from `containers/c1/components/Turnip`. One directory, however many
     * containers are on that Turnip build, which is the whole point of the
     * store; the container records the version, not the bytes.
     */
    private val turnip = TurnipDriver(
        driverDir = File("/data/user/0/app.vessel/files/components/Turnip/260300"),
        libraryName = "libvulkan_freedreno.so",
        hooksDir = File("/data/app/app.vessel/lib/arm64"),
    )

    private fun env(
        params: Map<String, ParamValue> = emptyMap(),
        manifest: ParamManifest? = fexManifest,
        driver: TurnipDriver? = null,
        display: String = DEFAULT_DISPLAY,
    ) = sessionEnvironment(container(params), manifest, paths, driver, display)

    // — the logging contract --------------------------------------------------

    @Test
    fun `WINEDEBUG is exactly the documented string, in the documented order`() {
        assertEquals("-all,err+all,warn+module,+winediag,+loaddll", env()["WINEDEBUG"])
    }

    @Test
    fun `WINEDEBUG puts minus-all first and warn plus module after err plus all`() {
        // Restating the two ordering rules as positions, so a reformat that keeps
        // the same tokens but reorders them still fails.
        val value = env()["WINEDEBUG"]!!
        val terms = value.split(",")
        assertEquals("-all", terms.first())
        assertTrue(terms.indexOf("err+all") < terms.indexOf("warn+module"))
        assertFalse("`+err` is a channel name, not a severity", terms.contains("+err"))
        assertFalse(terms.contains("+warn"))
        assertFalse(terms.contains("+fixme"))
        assertFalse("seh buys nothing for crashes and is a firehose", terms.contains("+seh"))
        assertFalse("relay is hundreds of MB in seconds", terms.contains("+relay"))
    }

    @Test
    fun `VKD3D_LOG_FILE is never set, because setting it moves vkd3d off stderr`() {
        assertFalse(env().containsKey("VKD3D_LOG_FILE"))
        assertNull(env()["VKD3D_LOG_FILE"])
        assertTrue("VKD3D_LOG_FILE" in RESERVED_SESSION_ENV)
    }

    @Test
    fun `a manifest param cannot set VKD3D_LOG_FILE either`() {
        val sneaky = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "vkd3d.logFile",
                            title = "Log file",
                            type = ParamType.ENUM,
                            default = JsonPrimitive("/sdcard/vkd3d.log"),
                            env = "VKD3D_LOG_FILE",
                        ),
                    ),
                ),
            ),
        )
        assertFalse(env(manifest = sneaky).containsKey("VKD3D_LOG_FILE"))
    }

    @Test
    fun `a manifest param cannot override WINEDEBUG`() {
        val sneaky = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "wine.debug",
                            title = "Debug",
                            type = ParamType.ENUM,
                            default = JsonPrimitive("-all"),
                            env = "WINEDEBUG",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(WINEDEBUG_CHANNELS, env(manifest = sneaky)["WINEDEBUG"])
    }

    @Test
    fun `the DXVK and vkd3d levels are the documented ones`() {
        val environment = env()
        assertEquals("info", environment["DXVK_LOG_LEVEL"])
        assertEquals(logs.absolutePath, environment["DXVK_LOG_PATH"])
        assertEquals("warn", environment["VKD3D_DEBUG"])
        // A separate channel with its own level: VKD3D_DEBUG does not carry it.
        assertEquals("warn", environment["VKD3D_SHADER_DEBUG"])
    }

    @Test
    fun `TU_DEBUG always carries startup, the only ground truth that Turnip loaded`() {
        assertEquals("startup", env()["TU_DEBUG"])
    }

    // — the driver ------------------------------------------------------------

    @Test
    fun `no Turnip install omits the ADRENOTOOLS vars rather than setting them empty`() {
        val environment = env(driver = null)
        assertFalse(environment.containsKey("ADRENOTOOLS_DRIVER_PATH"))
        assertFalse(environment.containsKey("ADRENOTOOLS_HOOKS_PATH"))
        assertFalse(environment.containsKey("ADRENOTOOLS_DRIVER_NAME"))
        assertTrue(environment.keys.none { it.startsWith("ADRENOTOOLS") })
    }

    @Test
    fun `an installed Turnip sets all three, with a trailing separator on the paths`() {
        val environment = env(driver = turnip)
        assertEquals(
            File("/data/user/0/app.vessel/files/components/Turnip/260300").absolutePath +
                File.separator,
            environment["ADRENOTOOLS_DRIVER_PATH"],
        )
        assertEquals(
            turnip.hooksDir.absolutePath + File.separator,
            environment["ADRENOTOOLS_HOOKS_PATH"],
        )
        assertEquals("libvulkan_freedreno.so", environment["ADRENOTOOLS_DRIVER_NAME"])
    }

    // — the fixed variables ---------------------------------------------------

    @Test
    fun `esync is set unconditionally, because it is not a setting`() {
        assertEquals("1", env()["WINEESYNC"])
        assertFalse(env().containsKey("WINEFSYNC"))
        assertFalse(env().containsKey("WINENTSYNC"))
    }

    @Test
    fun `WINEPREFIX and DISPLAY are set`() {
        assertEquals(prefix.absolutePath, env()["WINEPREFIX"])
        assertEquals(":0", env()["DISPLAY"])
        assertEquals(":1", env(display = ":1")["DISPLAY"])
    }

    @Test
    fun `WINEDLLOVERRIDES names every D3D and WGL DLL as native`() {
        assertEquals(
            "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi,opengl32=n",
            env()["WINEDLLOVERRIDES"],
        )
    }

    @Test
    fun `a container's own DLL overrides are appended after the shipped ones`() {
        // After, not before, and that ordering is the whole safety property:
        // Wine reads the string left to right and the last entry for a DLL wins,
        // so a user can override a default deliberately but cannot lose the D3D
        // set by writing something unrelated.
        val manifest = fexManifest.withDllOverrides()
        val environment = env(
            params = mapOf("wine.dllOverrides" to ParamValue.Text("winhttp=n,b")),
            manifest = manifest,
        )
        assertEquals(
            "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi,opengl32=n;winhttp=n,b",
            environment["WINEDLLOVERRIDES"],
        )
    }

    @Test
    fun `an empty DLL override list leaves the shipped ones alone`() {
        // Including the trailing separator: "…=n;" is not the same string, and a
        // stray semicolon is the kind of thing Wine parses as an empty entry.
        val manifest = fexManifest.withDllOverrides()
        for (blank in listOf("", "   ", ";")) {
            assertEquals(
                "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi,opengl32=n",
                env(mapOf("wine.dllOverrides" to ParamValue.Text(blank)), manifest)["WINEDLLOVERRIDES"],
            )
        }
    }

    /** [fexManifest] plus the free-text override param, as the shipped manifest has it. */
    private fun ParamManifest.withDllOverrides(): ParamManifest = copy(
        groups = groups.map { group ->
            if (group.id != "compatibility") {
                group
            } else {
                group.copy(
                    params = group.params + ParamSpec(
                        key = "wine.dllOverrides",
                        title = "Extra DLL overrides",
                        type = ParamType.TEXT,
                        default = JsonPrimitive(""),
                    ),
                )
            }
        },
    )

    // — manifest params -------------------------------------------------------

    @Test
    fun `FEX memory ordering is fixed, not a setting`() {
        val environment = env()
        assertEquals("1", environment["FEX_TSOENABLED"])
        assertEquals("1", environment["FEX_HALFBARRIERTSOENABLED"])
        assertEquals("0", environment["FEX_VECTORTSOENABLED"])
    }

    @Test
    fun `a container cannot change the memory ordering flags`() {
        // The three toggles were removed from the manifest, and this is the
        // guard that they do not come back through a stale container document:
        // an old container still has fex.VectorTSOEnabled=true saved in it, and
        // honouring that would silently reintroduce a setting whose only effect
        // is to make the container much slower.
        val environment = env(params = mapOf("fex.VectorTSOEnabled" to ParamValue.Flag(true)))
        assertEquals("0", environment["FEX_VECTORTSOENABLED"])
        assertEquals("1", environment["FEX_TSOENABLED"])
    }

    @Test
    fun `a param with no env contributes nothing, invented or otherwise`() {
        val environment = env()
        assertFalse(environment.containsKey("display.resolution"))
        assertFalse(environment.containsKey("DXVK_FRAME_RATE"))
        assertFalse(environment.containsKey("WINE_RESOLUTION"))
    }

    @Test
    fun `int and multi values render as decimal and comma-joined`() {
        val manifest = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "a", title = "A", type = ParamType.INT,
                            default = JsonPrimitive(4), env = "SOME_COUNT",
                        ),
                        ParamSpec(
                            key = "b", title = "B", type = ParamType.MULTI,
                            options = listOf("x", "y"), env = "SOME_SET",
                        ),
                    ),
                ),
            ),
        )
        val environment = env(
            params = mapOf(
                "a" to ParamValue.Count(8),
                "b" to ParamValue.Choices(listOf("x", "y")),
            ),
            manifest = manifest,
        )
        assertEquals("8", environment["SOME_COUNT"])
        assertEquals("x,y", environment["SOME_SET"])
    }

    @Test
    fun `no manifest still produces a complete fixed environment`() {
        val environment = env(manifest = null)
        assertEquals(WINEDEBUG_CHANNELS, environment["WINEDEBUG"])
        assertEquals("startup", environment["TU_DEBUG"])
        // The FEX flags survive a missing manifest because they no longer come
        // from it — they are fixed in sessionEnvironment beside WINEESYNC.
        assertEquals("1", environment["FEX_TSOENABLED"])
        assertEquals("0", environment["FEX_VECTORTSOENABLED"])
    }

    // — TU_DEBUG composition --------------------------------------------------

    private fun turnipManifest(default: String) = ParamManifest(
        schemaVersion = 1,
        groups = listOf(
            ParamGroup(
                id = "rendering", title = "Rendering",
                params = listOf(
                    ParamSpec(
                        key = "turnip.TU_DEBUG",
                        title = "Rendering mode",
                        type = ParamType.ENUM,
                        options = listOf("default", "sysmem"),
                        default = JsonPrimitive(default),
                        env = "TU_DEBUG",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a forced Turnip flag comes first and startup last`() {
        val environment = env(manifest = turnipManifest("sysmem"))
        assertEquals("sysmem,startup", environment["TU_DEBUG"])
    }

    @Test
    fun `the manifest's no-op value does not become a Turnip flag`() {
        // `default` in the manifest means "force nothing", not a flag called
        // "default" — which Turnip does not have.
        assertEquals("startup", env(manifest = turnipManifest("default"))["TU_DEBUG"])
    }

    @Test
    fun `startup is not repeated when the container asks for it too`() {
        val environment = env(
            params = mapOf("turnip.TU_DEBUG" to ParamValue.Text("startup")),
            manifest = turnipManifest("default"),
        )
        assertEquals("startup", environment["TU_DEBUG"])
    }

    @Test
    fun `a multi-valued TU_DEBUG keeps every flag it names`() {
        val manifest = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "r", title = "R",
                    params = listOf(
                        ParamSpec(
                            key = "turnip.flags", title = "Flags", type = ParamType.MULTI,
                            options = listOf("sysmem", "nolrz"), env = "TU_DEBUG",
                        ),
                    ),
                ),
            ),
        )
        val environment = env(
            params = mapOf("turnip.flags" to ParamValue.Choices(listOf("sysmem", "nolrz"))),
            manifest = manifest,
        )
        assertEquals("sysmem,nolrz,startup", environment["TU_DEBUG"])
    }

    @Test
    fun `a boolean cannot name a Turnip flag, so it is ignored rather than sent as 1`() {
        val manifest = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "r", title = "R",
                    params = listOf(
                        ParamSpec(
                            key = "turnip.on", title = "On", type = ParamType.BOOL,
                            default = JsonPrimitive(true), env = "TU_DEBUG",
                        ),
                    ),
                ),
            ),
        )
        assertEquals("startup", env(manifest = manifest)["TU_DEBUG"])
    }

    // — the whole map ---------------------------------------------------------

    @Test
    fun `a fully provisioned container produces exactly this environment`() {
        val environment = env(driver = turnip)
        assertEquals(
            mapOf(
                "WINEPREFIX" to prefix.absolutePath,
                "WINEESYNC" to "1",
                "WINEDEBUG" to "-all,err+all,warn+module,+winediag,+loaddll",
                "WINEDLLOVERRIDES" to "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi,opengl32=n",
                "DISPLAY" to ":0",
                "DXVK_LOG_LEVEL" to "info",
                "DXVK_LOG_PATH" to logs.absolutePath,
                "VKD3D_DEBUG" to "warn",
                "VKD3D_SHADER_DEBUG" to "warn",
                "TU_DEBUG" to "startup",
                "ADRENOTOOLS_DRIVER_PATH" to turnip.driverDir.absolutePath + File.separator,
                "ADRENOTOOLS_HOOKS_PATH" to turnip.hooksDir.absolutePath + File.separator,
                "ADRENOTOOLS_DRIVER_NAME" to "libvulkan_freedreno.so",
                "FEX_TSOENABLED" to "1",
                "FEX_HALFBARRIERTSOENABLED" to "1",
                "FEX_VECTORTSOENABLED" to "0",
            ),
            environment,
        )
    }
}
