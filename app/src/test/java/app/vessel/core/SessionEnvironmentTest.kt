package app.vessel.core

import app.vessel.core.params.ParamGroup
import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamSpec
import app.vessel.core.params.ParamType
import app.vessel.core.params.ParamValue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private val caches = File("/data/user/0/app.vessel/files/containers/c1/caches")
    private val tmp = File("/data/user/0/app.vessel/files/containers/c1/tmp")
    private val paths = SessionPaths(prefix = prefix, logs = logs, caches = caches, tmp = tmp)

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

    /**
     * A FEX package with the shape the store produces: `FEXCore/<code>`, and the
     * two CPU DLLs' lengths standing in for "which build of it".
     */
    private val fex = FexPackage(
        directory = File("/data/user/0/app.vessel/files/components/FEXCore/2608"),
        identity = "2608/5148672/4616192",
        offlineCompiler = null,
    )

    private fun env(
        params: Map<String, ParamValue> = emptyMap(),
        manifest: ParamManifest? = fexManifest,
        driver: TurnipDriver? = null,
        fexPackage: FexPackage? = fex,
        display: String = DEFAULT_DISPLAY,
        fpsLimit: Int? = null,
    ) = sessionEnvironment(container(params), manifest, paths, driver, fexPackage, display, fpsLimit)

    // — the logging contract --------------------------------------------------

    @Test
    fun `WINEDEBUG is exactly the documented string, in the documented order`() {
        assertEquals("-all,err+all,warn+module,+winediag,+loaddll,+debugstr", env()["WINEDEBUG"])
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

    // — the graphics counters --------------------------------------------------

    @Test
    fun `the D3D counter snapshot lands in the container's own scratch directory`() {
        // Inside app data, because both sides open it by path: Wine writes it
        // through the CRT and the sampler reads it as the app. Anywhere else and
        // one of the two is denied.
        assertEquals(File(tmp, GFX_STATS_FILE).absolutePath, env()["VESSEL_GFX_STATS"])
        // The directory, stated separately from the file name: `tmp` is what
        // `TMPDIR` already points at, and the point of the row is that the two
        // agree. Compared as `File`s rather than as strings because this suite
        // runs on the build host, where a separator is a backslash.
        assertEquals(tmp.absoluteFile, File(env()["VESSEL_GFX_STATS"]!!).absoluteFile.parentFile!!)
    }

    @Test
    fun `the scratch directory is derived from the prefix when it is not given`() {
        // The default matters: every caller that predates this variable keeps
        // pointing it at the same directory `TMPDIR` already names, rather than
        // at the prefix, where nothing would ever prune it.
        val defaulted = SessionPaths(prefix = prefix, logs = logs)
        assertEquals(
            File(prefix.parentFile, "tmp").absolutePath,
            defaulted.tmp.absolutePath,
        )
    }

    @Test
    fun `a manifest param cannot move the D3D counter snapshot out of the container`() {
        val sneaky = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "gfx.stats",
                            title = "Stats",
                            type = ParamType.ENUM,
                            default = JsonPrimitive("/sdcard/anywhere.json"),
                            env = "VESSEL_GFX_STATS",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            File(tmp, GFX_STATS_FILE).absolutePath,
            env(manifest = sneaky)["VESSEL_GFX_STATS"],
        )
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
        // Both layers write to the pipe the session log reads, and neither is
        // allowed to open a file instead. vkd3d does it by having no
        // `VKD3D_LOG_FILE` at all; DXVK needs the word `none`, because an unset
        // `DXVK_LOG_PATH` means "next to the executable" rather than "nowhere".
        assertEquals("none", environment["DXVK_LOG_PATH"])
        assertFalse(environment.containsKey("VKD3D_LOG_FILE"))
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
        assertFalse(environment.containsKey("VESSEL_VULKAN_ICD"))
    }

    @Test
    fun `an installed Turnip is offered to win32u as an ICD as well`() {
        // Both ways to the same file, deliberately. win32u decides between them
        // by reading the file: patches/wine/0009 keeps the ICD path only if the
        // library exports vk_icdGetInstanceProcAddr, and takes the adrenotools
        // path otherwise. So this is set for a HAL package too, and that is the
        // point — nothing here has to know which build is installed.
        val environment = env(driver = turnip)
        assertEquals(
            File(turnip.driverDir, turnip.libraryName).absolutePath,
            environment["VESSEL_VULKAN_ICD"],
        )
    }

    @Test
    fun `an installed Turnip names all three adrenotools variables`() {
        // The inverse of this test stood here for one cycle, asserting that the
        // three variables were withheld, because setting them wedged the desktop.
        // The cause turned out to be libadrenotools exporting three data symbols
        // that shadow dynamic-linker functions — see the note at the call site —
        // and it is fixed in the APK's own CMake, not in Wine.
        //
        // Kept as an assertion on TURNIP_ENABLED rather than only on the map, so
        // that switching the driver off again cannot be done quietly.
        assertTrue(TURNIP_ENABLED)

        val environment = env(driver = turnip)
        assertEquals(
            turnip.driverDir.absolutePath + File.separator,
            environment["ADRENOTOOLS_DRIVER_PATH"],
        )
        assertEquals(
            turnip.hooksDir.absolutePath + File.separator,
            environment["ADRENOTOOLS_HOOKS_PATH"],
        )
        assertEquals(turnip.libraryName, environment["ADRENOTOOLS_DRIVER_NAME"])
    }

    @Test
    fun `the driver path ends in a separator, because libadrenotools concatenates`() {
        // libadrenotools joins ADRENOTOOLS_DRIVER_PATH and ADRENOTOOLS_DRIVER_NAME
        // with nothing between them, so a missing trailing separator makes it look
        // for `…/260300libvulkan_freedreno.so` and fall back to the stock driver
        // with only a winediag line to say so.
        val environment = env(driver = turnip)
        assertTrue(environment["ADRENOTOOLS_DRIVER_PATH"]!!.endsWith(File.separator))
        assertTrue(environment["ADRENOTOOLS_HOOKS_PATH"]!!.endsWith(File.separator))
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
            "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi=n",
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
            "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi=n;winhttp=n,b",
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
                "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi=n",
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
        assertEquals("0", environment["FEX_HALFBARRIERTSOENABLED"])
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
        assertFalse(environment.containsKey("WINE_RESOLUTION"))
        // `display.fpsLimit` is the exception that proves the rule: it declares
        // no `env` either, and it still reaches two variables — but only because
        // `sessionEnvironment` is given the parsed number, which this call is
        // not. An uncapped container writes neither.
        assertFalse(environment.containsKey("DXVK_FRAME_RATE"))
        assertFalse(environment.containsKey("VKD3D_FRAME_RATE"))
    }

    @Test
    fun `a manifest param whose value is empty contributes no variable at all`() {
        // The graphics experiments are off when their variable is *absent* and on
        // when it is present with any content — `TU_AUTOTUNE_FLAGS` and
        // `MESA_GPU_TRACES` both parse a flag list. A param must always produce a
        // value, so the empty string is the manifest's only way to say "leave
        // this alone", and writing it through would be a container declaring an
        // experiment nobody chose.
        val optional = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "turnip.autotuneFlags",
                            title = "Tiling override",
                            type = ParamType.ENUM,
                            options = listOf("", "big_gmem"),
                            default = JsonPrimitive(""),
                            env = "TU_AUTOTUNE_FLAGS",
                        ),
                    ),
                ),
            ),
        )
        assertFalse(env(manifest = optional).containsKey("TU_AUTOTUNE_FLAGS"))
        assertEquals(
            "big_gmem",
            env(
                params = mapOf("turnip.autotuneFlags" to ParamValue.Text("big_gmem")),
                manifest = optional,
            )["TU_AUTOTUNE_FLAGS"],
        )
    }

    @Test
    fun `the Turnip instrument paths stay inside the container`() {
        // Reserved for the reason VESSEL_GFX_STATS is: they name paths this app
        // creates and reads, and a document that could move them would have the
        // driver writing outside the container.
        val environment = env()
        assertEquals(tmp.absoluteFile, File(environment["TU_DEBUG_FILE"]!!).absoluteFile.parentFile)
        assertEquals(tmp.absoluteFile, File(environment["MESA_GPU_TRACEFILE"]!!).absoluteFile.parentFile)
        assertTrue("TU_DEBUG_FILE" in RESERVED_SESSION_ENV)
        assertTrue("MESA_GPU_TRACEFILE" in RESERVED_SESSION_ENV)
    }

    @Test
    fun `a typed environment variable reaches the session and wins over the manifest`() {
        // Last stage, on purpose: the case it exists for is "the value this build
        // chose is wrong for my machine", and an override an earlier stage could
        // overwrite would not be one.
        val diagnosed = container().copy(
            diagnostics = ContainerDiagnostics(
                env = listOf(
                    EnvSetting("TU_AUTOTUNE_ALGO", "bandwidth"),
                    EnvSetting("SOMETHING_NOBODY_ANTICIPATED", "1"),
                ),
            ),
        )
        val environment = sessionEnvironment(diagnosed, fexManifest, paths)
        assertEquals("bandwidth", environment["TU_AUTOTUNE_ALGO"])
        assertEquals("1", environment["SOMETHING_NOBODY_ANTICIPATED"])
    }

    @Test
    fun `a typed environment variable cannot reach a reserved name`() {
        // The boundary that keeps a container document inside its own directory.
        // Every one of these names a path this app writes or reads, or plumbing a
        // session needs to start at all.
        val diagnosed = container().copy(
            diagnostics = ContainerDiagnostics(
                env = RESERVED_SESSION_ENV.map { EnvSetting(it, "hijacked") },
            ),
        )
        val environment = sessionEnvironment(diagnosed, fexManifest, paths)
        assertTrue(environment.values.none { it == "hijacked" })
        // And the rule is assertable without building an environment at all.
        assertTrue(diagnosed.diagnostics.environmentOverrides().isEmpty())
    }

    @Test
    fun `a blank environment name contributes nothing`() {
        // What a row that has just been added is, before it is typed into.
        val diagnosed = container().copy(
            diagnostics = ContainerDiagnostics(env = listOf(EnvSetting("", "orphan"))),
        )
        assertTrue(diagnosed.diagnostics.environmentOverrides().isEmpty())
        assertTrue(sessionEnvironment(diagnosed, fexManifest, paths).values.none { it == "orphan" })
    }

    @Test
    fun `a frame rate limit caps the renderer as well as the compositor`() {
        // The bug this closes, measured on the device: at a 24 fps cap the
        // composited rate was 24.0 and DXVK's own was 116.5, at 84% GPU and ~760
        // draw calls a frame. Four frames in five were rendered at full cost and
        // discarded, because the cap reached the blit and not the renderer.
        val environment = env(fpsLimit = 24)
        assertEquals("24", environment["DXVK_FRAME_RATE"])
        assertEquals("24", environment["VKD3D_FRAME_RATE"])
    }

    @Test
    fun `a container cannot set a renderer frame rate that disagrees with the compositor`() {
        // Both are reserved, so a manifest param declaring either as its `env` is
        // dropped by the manifest stage — the same guard `VKD3D_LOG_FILE` gets
        // above, and here for a sharper reason: the failure it prevents is not an
        // error but a phone running hot at a frame rate the user believes they
        // capped, with nothing anywhere saying why.
        val sneaky = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "sneaky.dxvkFps",
                            title = "DXVK frame rate",
                            type = ParamType.TEXT,
                            default = JsonPrimitive("240"),
                            env = "DXVK_FRAME_RATE",
                        ),
                        ParamSpec(
                            key = "sneaky.vkd3dFps",
                            title = "vkd3d frame rate",
                            type = ParamType.TEXT,
                            default = JsonPrimitive("240"),
                            env = "VKD3D_FRAME_RATE",
                        ),
                    ),
                ),
            ),
        )
        val environment = env(manifest = sneaky, fpsLimit = 30)
        assertEquals("30", environment["DXVK_FRAME_RATE"])
        assertEquals("30", environment["VKD3D_FRAME_RATE"])
        assertTrue("DXVK_FRAME_RATE" in RESERVED_SESSION_ENV)
        assertTrue("VKD3D_FRAME_RATE" in RESERVED_SESSION_ENV)
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
                "WINEDEBUG" to "-all,err+all,warn+module,+winediag,+loaddll,+debugstr",
                "WINEDLLOVERRIDES" to "d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi=n",
                "DISPLAY" to ":0",
                // No Win32 caption on any top-level window. Measured before the
                // patch existed: a 1280x720 game window had a 1274x673 client at
                // +3+44 — a 3px border and a 41px caption that nothing paints and
                // that overflows the parent once the program resizes smaller.
                "VESSEL_BORDERLESS" to "1",
                "VESSEL_MANAGED" to "1",
                "DXVK_LOG_LEVEL" to "info",
                // `none`, not the log directory: on a Wine build DXVK sends every
                // line to `__wine_dbg_output` *and* to a file, and a path here made
                // it write `<exe>_dxgi.log` beside the session log while the session
                // log itself got nothing. Measured on a real game launch.
                "DXVK_LOG_PATH" to "none",
                // The D3D counter snapshot, in the container's scratch. Always
                // set: it is what makes draw calls, submissions and vidmem
                // graphable without a per-game `dxvk.conf` and a screenshot.
                "VESSEL_GFX_STATS" to File(tmp, GFX_STATS_FILE).absolutePath,
                "VKD3D_DEBUG" to "warn",
                "VKD3D_SHADER_DEBUG" to "warn",
                "VKD3D_CONFIG" to "nodxr",
                "MESA_SHADER_CACHE_DISABLE" to "false",
                "MESA_SHADER_CACHE_DIR" to File(caches, "mesa").absolutePath,
                "DXVK_STATE_CACHE_PATH" to File(caches, "dxvk").absolutePath,
                // Not a unix path: see VKD3D_CACHE_DOS_PATH for why the unix
                // form vkd3d used to get here fails the disk-cache open on
                // every launch in a Vessel prefix (no `Z:`).
                "VKD3D_SHADER_CACHE_PATH" to VKD3D_CACHE_DOS_PATH,
                // Version 2, not version 1. A first attempt named the v1
                // extensions, which vkd3d does not use, so it disabled nothing
                // while looking like it had — hence pinning the exact string.
                "VKD3D_DISABLE_EXTENSIONS" to "VK_KHR_present_id2,VK_KHR_present_wait2",
                "TU_DEBUG" to "startup",
                // The two Turnip instrument paths, always set and inert until a
                // flag asks for them. `TU_DEBUG_FILE` is the one that matters:
                // Turnip re-reads it mid-session, which is what makes a tiling
                // A/B possible against one scene at one temperature instead of
                // across two launches that this device's thermals make
                // incomparable.
                "TU_DEBUG_FILE" to File(tmp, TU_DEBUG_FILE_NAME).absolutePath,
                "MESA_GPU_TRACEFILE" to File(tmp, GPU_TRACE_FILE_NAME).absolutePath,
                "MESA_VK_WSI_DEBUG" to "sw",
                // A Turnip driconf option delivered as an env var, because FEX
                // asks Turnip for it through `__wine_set_unix_env` and that
                // export does not exist in Wine 11.14, so FEX's own attempt is
                // guarded out and the option keeps its `false` default.
                "tu_override_uncached_as_cache_coherent" to "true",
                "VESSEL_VULKAN_ICD" to File(turnip.driverDir, turnip.libraryName).absolutePath,
                "ADRENOTOOLS_DRIVER_PATH" to turnip.driverDir.absolutePath + File.separator,
                "ADRENOTOOLS_HOOKS_PATH" to turnip.hooksDir.absolutePath + File.separator,
                "ADRENOTOOLS_DRIVER_NAME" to turnip.libraryName,
                // Silent by default: FEX's debug tier was 99.9% of a 49 MB
                // session log, a line pair per unaligned atomic. Add the
                // diagnostics row at "0" to hear it for one session.
                "FEX_SILENTLOG" to "1",
                "FEX_OUTPUTLOG" to "stderr",
                "FEX_TSOENABLED" to "1",
                "FEX_HALFBARRIERTSOENABLED" to "0",
                "FEX_VECTORTSOENABLED" to "0",
                // Not defaults: FEX ships both on the memory-frugal side and
                // documents both as stutter sources. See the assignment.
                "FEX_DISABLEL2CACHE" to "0",
                "FEX_DYNAMICL1CACHE" to "0",
                // FEX's cache beside the shader caches, not in LOCALAPPDATA,
                // so a container reset clears it with the others — and under a
                // digest of the FEX configuration, because upstream's
                // `CodeCacheConfigId` is `0 // TODO` and will otherwise load a
                // cache built under different codegen settings.
                //
                // **The literal is pinned on purpose and updating it is the
                // signal, not the chore.** Any change to any `FEX_*` variable
                // that is not in `FEX_CACHE_KEY_IGNORED`, and any change to the
                // FEX package, moves this string — which is exactly the event a
                // reader of this file should be made to notice. Deriving it from
                // `fexCacheKey` here would assert nothing at all.
                //
                // The digest no longer appears in this value — FEX cannot read a
                // cache through a unix path, so what it is told is a fixed DOS
                // path and the digest moved to the symlink's target. The pinned
                // literal moved with it, to `the FEX cache directory is keyed on
                // the configuration` below; this row is now only asserting that
                // the guest is handed something `\??\` can be pasted onto.
                "FEX_APP_CACHE_LOCATION" to FEX_CACHE_DOS_PATH,
                "FEX_ENABLECODECACHINGWIP" to "1",
            ),
            environment,
        )
    }

    // — the FEX code-cache key --------------------------------------------------

    @Test
    fun `the FEX cache directory sits under the container's caches`() {
        // The half of the path that is not a digest. `caches/fex/<key>/`, so a
        // container reset takes the code cache with the shader caches and
        // nothing lands in the prefix's LOCALAPPDATA where FEX would default to.
        //
        // Asserted against the *host* directory now. What the guest is told is a
        // DOS path into the prefix, and the two are joined by a symlink that
        // `SessionRuntime.linkFexCache` maintains — so this property lives on
        // [fexCacheHost] and the one below it lives on the variable.
        val host = fexCacheHost(paths, env(driver = turnip), fex)
        assertTrue(host.absolutePath.startsWith(File(caches, "fex").absolutePath + File.separator))
    }

    @Test
    fun `what FEX is told is a DOS path it can also read back`() {
        // Not a style preference. FEX writes through `std::filesystem`, which
        // Wine resolves for a unix path, but reads by pasting `\??\` onto this
        // string and calling `NtOpenFile` — and `\??\/data/...` is refused by
        // `nt_to_unix_file_name_no_root` before it reaches a file. A drive
        // letter and a trailing backslash are what make the read work, so both
        // are the assertion.
        val location = env(driver = turnip)["FEX_APP_CACHE_LOCATION"]!!
        assertTrue(location.matches(Regex("""^[A-Z]:\\.*\\$""")))
    }

    @Test
    fun `the FEX cache directory is keyed on the configuration`() {
        // **The pinned literal, moved here from the golden environment map.**
        // Any change to any `FEX_*` variable not in `FEX_CACHE_KEY_IGNORED`, and
        // any change to the FEX package, moves this string — which is exactly
        // the event a reader should be made to notice. Deriving it from
        // `fexCacheKey` would assert nothing at all.
        assertEquals(
            File(File(caches, "fex"), "96a6dacbc994"),
            fexCacheHost(paths, env(driver = turnip), fex),
        )
    }

    @Test
    fun `changing a FEX codegen knob moves the cache directory`() {
        // The whole point of the digest, stated as the property rather than as
        // an implementation. Upstream keys a cache on `CodeCacheConfigId = 0`,
        // so without this a TSO change would silently load code generated under
        // the old setting.
        val base = env(driver = turnip)
        val changed = base.toMutableMap().also { it["FEX_TSOENABLED"] = "0" }
        assertNotEquals(fexCacheKey(base, fex), fexCacheKey(changed, fex))
    }

    @Test
    fun `changing the FEX package moves the cache directory`() {
        // A new FEX build generates different code for the same guest bytes, and
        // FEX's own cache header does not record which build wrote it — the
        // `// TODO: Also check for matching FEX version from cache header` in
        // FEXOfflineCompiler. So the package has to be in the key.
        val other = fex.copy(identity = "2609/5148700/4616192")
        assertNotEquals(
            fexCacheHost(paths, env(driver = turnip), fex),
            fexCacheHost(paths, env(driver = turnip, fexPackage = other), other),
        )
    }

    @Test
    fun `the log destination is not part of the cache key`() {
        // `FEX_SILENTLOG` and `FEX_OUTPUTLOG` decide where FEX's diagnostics go
        // and change no generated code. Excluding them is not tidiness: both are
        // reachable from Diagnostics, and a user who turns FEX logging on to
        // investigate something would otherwise be handed a cold cache — a
        // different situation from the one they are trying to observe.
        val base = env(driver = turnip)
        val noisy = base.toMutableMap().also {
            it["FEX_SILENTLOG"] = "1"
            it["FEX_OUTPUTLOG"] = "server"
        }
        assertEquals(fexCacheKey(base, fex), fexCacheKey(noisy, fex))
    }

    @Test
    fun `a FEX variable nobody here has heard of is still in the key`() {
        // The reason the key is taken over the environment map and not over a
        // list of knobs. A manifest may declare `FEX_HOSTFEATURES` tomorrow; it
        // changes generated code, and nothing in this file has to be told.
        val base = env(driver = turnip)
        val extended = base.toMutableMap().also { it["FEX_HOSTFEATURES"] = "-crypto" }
        assertNotEquals(fexCacheKey(base, fex), fexCacheKey(extended, fex))
    }

    // — the diagnostics merge stage ---------------------------------------------

    @Test
    fun `the diagnostics set is a strict subset of the reserved one`() {
        // The whole safety property of the third merge stage in one assertion: a
        // manifest param can never reach a diagnostic variable, whatever the
        // Diagnostics surface is allowed to do with it. Strict, because a set
        // equal to the reserved one would mean Diagnostics could write
        // `WINEPREFIX` and `ADRENOTOOLS_DRIVER_NAME`.
        assertTrue(DIAGNOSTIC_SESSION_ENV.all { it in RESERVED_SESSION_ENV })
        assertTrue(DIAGNOSTIC_SESSION_ENV.size < RESERVED_SESSION_ENV.size)
        // The two whose whole purpose is an absence and a fixed value.
        assertFalse("VKD3D_LOG_FILE" in DIAGNOSTIC_SESSION_ENV)
        assertFalse("MESA_VK_WSI_DEBUG" in DIAGNOSTIC_SESSION_ENV)
    }

    @Test
    fun `a container with nothing diagnosed produces the environment above, unchanged`() {
        // The golden map is asserted against a container carrying an *explicit*
        // default record as well as against one carrying none, because the
        // difference between "no diagnostics field" and "a diagnostics field at
        // its defaults" is exactly the difference a container document saved by a
        // newer build would introduce.
        val bare = env(driver = turnip)
        val explicit = sessionEnvironment(
            container().copy(diagnostics = ContainerDiagnostics()),
            fexManifest,
            paths,
            turnip,
            fex,
        )
        assertEquals(bare, explicit)
    }

    @Test
    fun `diagnostics replace WINEDEBUG only by appending to it`() {
        val diagnosed = container().copy(diagnostics = wineRow("file", "EVERYTHING"))
        val environment = sessionEnvironment(diagnosed, fexManifest, paths)
        assertEquals("$WINEDEBUG_CHANNELS,+file", environment["WINEDEBUG"])
        // Order is the semantics, so the prefix must still be the prefix.
        assertEquals("-all", environment["WINEDEBUG"]!!.split(",").first())
    }

    @Test
    fun `a manifest param still cannot set WINEDEBUG when diagnostics are on`() {
        // The manifest stage runs before the diagnostics one and is still
        // filtered, so a container document naming WINEDEBUG loses to both.
        val sneaky = ParamManifest(
            schemaVersion = 1,
            groups = listOf(
                ParamGroup(
                    id = "g", title = "G",
                    params = listOf(
                        ParamSpec(
                            key = "wine.debug", title = "Debug", type = ParamType.ENUM,
                            default = JsonPrimitive("-all"), env = "WINEDEBUG",
                        ),
                    ),
                ),
            ),
        )
        val diagnosed = container().copy(diagnostics = wineRow("file", "OFF"))
        assertEquals(
            "$WINEDEBUG_CHANNELS,-file",
            sessionEnvironment(diagnosed, sneaky, paths)["WINEDEBUG"],
        )
    }

    @Test
    fun `every fixed row states the value the session actually carries`() {
        // The inventory is only worth drawing if it is true. Each read-only row
        // names a variable or a Wine channel the fixed block sets, and this is
        // the assertion that the screen and the environment cannot drift apart.
        val environment = env(driver = turnip)
        assertEquals(FIXED_DXVK_LOG_LEVEL, environment["DXVK_LOG_LEVEL"])
        assertEquals(FIXED_DXVK_LOG_PATH, environment["DXVK_LOG_PATH"])
        assertEquals(FIXED_VKD3D_DEBUG, environment["VKD3D_DEBUG"])
        assertEquals(FIXED_VKD3D_SHADER_DEBUG, environment["VKD3D_SHADER_DEBUG"])
        assertEquals(FIXED_FEX_SILENTLOG, environment["FEX_SILENTLOG"])
        assertEquals(FIXED_FEX_OUTPUTLOG, environment["FEX_OUTPUTLOG"])
        assertEquals(TU_DEBUG_STARTUP, environment["TU_DEBUG"])
        // The one whose fixed value is an absence, and whose row says "not set".
        assertEquals("", FIXED_MESA_LOG)
        assertFalse(environment.containsKey("MESA_LOG"))
        // And the Wine rows are the prefix, term for term.
        assertEquals(
            environment["WINEDEBUG"],
            BASELINE_WINE_TERMS.flatMap { it.second }.joinToString(","),
        )
    }

    @Test
    fun `diagnostics reach the subsystems in their own words`() {
        val diagnosed = container().copy(
            diagnostics = ContainerDiagnostics()
                .withRowAdded().withRowNamed(0, "DXVK_LOG_LEVEL").withRowLevel(0, "debug")
                .withRowAdded().withRowNamed(1, "VKD3D_SHADER_DEBUG").withRowLevel(1, "trace")
                .withRowAdded().withRowNamed(2, "FEX_SILENTLOG").withRowLevel(2, "1")
                .withRowAdded().withRowNamed(3, "MESA_LOG").withRowLevel(3, "file"),
        )
        val environment = sessionEnvironment(diagnosed, fexManifest, paths)
        assertEquals("debug", environment["DXVK_LOG_LEVEL"])
        assertEquals("trace", environment["VKD3D_SHADER_DEBUG"])
        // Untouched, and still its own channel.
        assertEquals("warn", environment["VKD3D_DEBUG"])
        assertEquals("1", environment["FEX_SILENTLOG"])
        assertEquals("file", environment["MESA_LOG"])
        // Still not reachable, by any path.
        assertFalse(environment.containsKey("VKD3D_LOG_FILE"))
        assertEquals("none", environment["DXVK_LOG_PATH"])
    }

    @Test
    fun `a diagnostics record adds no key the fixed block did not already have`() {
        // `MESA_LOG` is the one exception and it is the point of this assertion:
        // everything else Diagnostics writes is a value the environment already
        // carried, so switching a row on cannot change the shape of the map.
        val everything = container().copy(
            diagnostics = ContainerDiagnostics()
                .withRowAdded().withRowNamed(0, "relay")
                .withRowAdded().withRowNamed(1, "DXVK_LOG_LEVEL").withRowLevel(1, "trace")
                .withRowAdded().withRowNamed(2, "VKD3D_DEBUG").withRowLevel(2, "trace")
                .withRowAdded().withRowNamed(3, "MESA_LOG").withRowLevel(3, "file")
                .withRowAdded().withRowNamed(4, "perf"),
        )
        val added = sessionEnvironment(everything, fexManifest, paths).keys -
            sessionEnvironment(container(), fexManifest, paths).keys
        assertEquals(setOf("MESA_LOG"), added)
        // TU_DEBUG was already in the map, and the diagnostics stage adds to the
        // flags rather than replacing them — `startup` stays last.
        assertEquals("perf,startup", sessionEnvironment(everything, fexManifest, paths)["TU_DEBUG"])
    }

    @Test
    fun `a manifest Turnip flag and a diagnostics one both survive`() {
        val diagnosed = container().copy(
            diagnostics = ContainerDiagnostics()
                .withRowAdded().withRowNamed(0, "MESA_LOG").withRowLevel(0, "file")
                .withRowAdded().withRowNamed(1, "nolrz"),
        )
        assertEquals(
            "nolrz,sysmem,startup",
            sessionEnvironment(diagnosed, turnipManifest("sysmem"), paths)["TU_DEBUG"],
        )
    }

    /** One diagnostics row, named and levelled. */
    private fun wineRow(channel: String, level: String) = ContainerDiagnostics()
        .withRowAdded()
        .withRowNamed(0, channel)
        .withRowLevel(0, level)

    // — the prefix bootstrap's own, much smaller environment ---------------------

    @Test
    fun `the bootstrap environment carries no graphics variable at all`() {
        // Measured on the device: handing `wineboot --init` the full session
        // environment stalls it in `rundll32 setupapi,InstallHinfSection
        // PreInstall` with `drive_c` still empty, and the last line in the log is
        // `winediag:load_libvulkan_adrenotools`. Nothing about building a prefix
        // needs a display or a driver, so none of it is passed — and the list is
        // an allowlist so that a graphics variable added later cannot rejoin it.
        val full = sessionEnvironment(profile = container(), manifest = null, paths = paths)
        val bootstrap = full.filterKeys { it in BOOTSTRAP_SESSION_ENV }
        val forbidden = listOf(
            "DISPLAY", WINEDLLOVERRIDES_ENV, "TU_DEBUG",
            "DXVK_LOG_LEVEL", "DXVK_LOG_PATH", "DXVK_STATE_CACHE_PATH",
            "VKD3D_DEBUG", "VKD3D_SHADER_DEBUG", "VKD3D_CONFIG", "VKD3D_SHADER_CACHE_PATH",
            "VKD3D_DISABLE_EXTENSIONS",
            "MESA_SHADER_CACHE_DISABLE", "MESA_SHADER_CACHE_DIR",
            "ADRENOTOOLS_DRIVER_PATH", "ADRENOTOOLS_HOOKS_PATH", "ADRENOTOOLS_DRIVER_NAME",
            "VESSEL_VULKAN_ICD",
        )
        forbidden.forEach { assertFalse("$it reached the prefix bootstrap", bootstrap.containsKey(it)) }
    }

    @Test
    fun `the bootstrap environment still carries the exec model and the prefix`() {
        // The other half of the same rule: strip too much and Wine cannot find
        // its own ntdll. See WineTree.dllPath.
        val launcher = wineLauncherEnvironment(
            tree = WineTree(File("/data/user/0/app.vessel/files/components/Wine/1114")),
            scratch = SessionScratch(home = prefix.parentFile, tmp = File(prefix.parentFile, "tmp")),
        )
        val full = launcher +
            sessionEnvironment(profile = container(), manifest = null, paths = paths)
        val bootstrap = full.filterKeys { it in BOOTSTRAP_SESSION_ENV }
        listOf(
            "WINEDLLPATH", "WINENLSDIR", "LD_LIBRARY_PATH", "PATH",
            "HOME", "TMPDIR", "XDG_RUNTIME_DIR", "WINEPREFIX", "WINEDEBUG", "WINEESYNC",
        ).forEach { assertTrue("$it is missing from the prefix bootstrap", bootstrap.containsKey(it)) }
    }
}
