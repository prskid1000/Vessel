package app.vessel.core

import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import java.io.File

/**
 * The fixed Wine debug channel set.
 *
 * **Order is load-bearing and this string must not be reformatted** — Wine
 * parses left to right and seeds each newly named channel from `default_flags`
 * as of that moment. Note also that `err` is a class, not a channel: `+err`
 * registers a channel that does not exist.
 *
 * `docs/LOGGING.md` is the source of truth for every term here and why it earns
 * its place. Change that document first.
 */
const val WINEDEBUG_CHANNELS: String = "-all,err+all,warn+module,+winediag,+loaddll,+debugstr"

/**
 * The Direct3D and WGL DLLs that must resolve to the shipped native builds.
 *
 * One list, two consumers with deliberately different modes: the session
 * environment sets them `n` (native only), and [PrefixRegistry] seeds the prefix
 * with `native,builtin` so a prefix launched without the environment still
 * prefers the real thing rather than silently falling back to wined3d.
 *
 * `opengl32` was in this list, on the reasoning that a Mesa/Zink `opengl32.dll`
 * replaces WGL rather than Direct3D. It is out — see [WGL_DLL], which is the
 * measurement that removed it.
 *
 * **This list must name only DLLs we actually ship.** `=n` means native *only*:
 * Wine's `loader.c` turns a missing native file into `STATUS_DLL_NOT_FOUND`
 * rather than falling back to its builtin. So `d3d10` and `d3d10_1` are absent
 * on purpose — DXVK 2.7.1 ships `d3d10core.dll` and no `d3d10.dll`, and adding
 * the names "for completeness" would break every D3D10 title outright.
 *
 * The overrides are not optional, either, and it is worth knowing why the
 * obvious alternative does not work: Wine only prefers a native DLL by default
 * when the builtin was linked with `-Wl,--prefer-native`, and not one of d3d8,
 * d3d9, d3d10core, d3d11, d3d12, d3d12core, dxgi or wined3d carries it. Without
 * the override the builtin silently wins and the DXVK we shipped never loads.
 */
val D3D_DLL_OVERRIDES: List<String> = listOf(
    "d3d8", "d3d9", "d3d10core", "d3d11", "d3d12", "d3d12core", "dxgi",
)

/**
 * **`opengl32` was in the list above and had to come out. It killed games.**
 *
 * Measured on the device with Metro 2033 Redux, twice each way, one variable:
 *
 * | `opengl32` | modules loaded | last line before it died |
 * |---|---|---|
 * | `=n` (Zink) | `libgallium_wgl.dll` | **`libgallium_wgl.dll`** |
 * | `=b` (Wine's) | never loads Zink | `coml2.dll`, nine lines further |
 *
 * The process vanished on the native path with no exception, no tombstone and
 * nothing on `debugstr` — a clean exit during `libgallium_wgl.dll`'s load.
 *
 * **The reason it reached a game that never asked for OpenGL is a static import
 * chain**, and it is why this could not stay a per-container setting:
 *
 * ```
 * metro.exe → d3dx11_43.dll → d3dcompiler_47.dll → wined3d.dll → opengl32.dll
 *                                                              → libgallium_wgl.dll
 * ```
 *
 * `d3dx11_43` is Wine's builtin D3DX11 helper, `d3dcompiler_47` imports
 * `wined3d`, and `wined3d` imports `opengl32`. Those are load-time imports, so
 * merely *touching* D3DX11 pulls in the whole GL stack — and the same run never
 * loaded `d3d11.dll` or `dxgi.dll` at all. A pure Direct3D title was paying
 * Zink's cost, and its crash, on the way to an API it never reached.
 *
 * **What this does not break.** Zink is still built, still packaged and still
 * installed into `system32`; what changed is that nothing is *forced* onto it.
 * A container that wants it says `opengl32=n` in its extra DLL overrides, which
 * is the field that exists for exactly this. And the fallback is not a
 * regression: Wine's builtin `opengl32` goes through `winex11`'s EGL bridge,
 * which on this device already fails with `egl_init Failed to find required
 * extension EGL_KHR_client_get_all_proc_addresses` — so an OpenGL title had no
 * working path either way. The difference is that it now fails at the call
 * instead of killing the process at load.
 *
 * Kept as a named constant rather than deleted so that the reasoning has
 * somewhere to live and so the seed and the environment cannot disagree about
 * it.
 */
const val WGL_DLL: String = "opengl32"

/**
 * `WINEDLLOVERRIDES`, named because two places have to agree about it and one of
 * them has to be able to take it *out* again.
 *
 * See [BOOTSTRAP_SESSION_ENV]: this variable must not be set while a prefix is
 * being built.
 */
const val WINEDLLOVERRIDES_ENV: String = "WINEDLLOVERRIDES"

/**
 * The only variables `wineboot` and `regedit` are given while a prefix is being
 * built.
 *
 * **An allowlist, and it is the reference scripts' own environment.**
 * `tools/device-session.sh` and `tools/device-graphics.sh` both build a prefix
 * from nothing, on this device, with exactly the exec-model variables plus
 * `WINEPREFIX` and `WINEDEBUG` — and `device-graphics.sh` composes a *second*
 * environment for its probe runs specifically so that the graphics variables do
 * not reach `wineboot`, with the reason written beside it: "forcing native d3d
 * during prefix creation would have wineboot's own DLL registration trip over
 * files that are not in place yet".
 *
 * The app was handing `wineboot` the whole session environment, and the result
 * was measured on the device: `wineboot --init` reaches
 * `rundll32 setupapi,InstallHinfSection PreInstall`, logs
 * `winediag:load_libvulkan_adrenotools`, and stops. Two minutes later `drive_c`
 * is still empty and every process is still alive. Nothing about building a
 * prefix needs a display, a Vulkan driver, a shader cache or a DLL override, so
 * none of them is passed.
 *
 * Allow rather than deny, because the failure mode of a denylist is a graphics
 * variable added later that silently rejoins the prefix bootstrap — which is a
 * bug that looks like Wine hanging, six months after the change that caused it.
 *
 * The FEX flags stay because they are correctness rather than graphics: the
 * second `wineboot --update` runs after the emulator key is applied, so it is a
 * translated process, and a translated process with the wrong memory-ordering
 * settings is wrong in the same way here as anywhere else. `WINEESYNC` stays
 * because the server and its clients have to agree about it.
 */
val BOOTSTRAP_SESSION_ENV: Set<String> = setOf(
    // The exec model. See `wineLauncherEnvironment` for why each is required.
    "WINEDLLPATH",
    "WINENLSDIR",
    "LD_LIBRARY_PATH",
    "PATH",
    "HOME",
    "TMPDIR",
    "XDG_RUNTIME_DIR",

    "WINEPREFIX",
    "WINEESYNC",
    "WINEDEBUG",

    "FEX_SILENTLOG",
    "FEX_OUTPUTLOG",
    "FEX_TSOENABLED",
    "FEX_HALFBARRIERTSOENABLED",
    "FEX_VECTORTSOENABLED",
)

/**
 * Whether the session asks win32u to load Turnip.
 *
 * A constant rather than a container setting on purpose: which Vulkan driver
 * answers is not a choice anyone should be offered, and while this was off it
 * was off because of a defect rather than a preference.
 *
 * It was false for one release cycle. The defect was never in
 * `patches/wine/0006`, which is why the note that used to be here named the
 * wrong thing: see the call site for what it actually was and how it was found.
 */
const val TURNIP_ENABLED: Boolean = true

/**
 * Whether Wine treats this session as having a real window manager.
 *
 * **The switch behind move, resize, Maximize and the white strip — all one
 * thing.** `explorer /desktop=` normally sets `managed_mode = FALSE`
 * (`dlls/winex11.drv/desktop.c`), on the reasoning that Wine *is* the window
 * manager inside its own virtual desktop. The consequence, measured on the
 * device: a `ConfigureNotify` Wine did not ask for is recorded as the current
 * state and then overwritten from Wine's desired state instead of becoming a
 * `SetWindowPos`. So the shell's drag borders moved the frame — 1280x720+0+0 to
 * 1047x720+0+149, confirmed in the tree — and the client never followed. What
 * you see is the frame's own background wherever the client stops covering it,
 * which is the white region, and it appears only once a window is dragged
 * larger than its client ever was.
 *
 * `patches/wine/0011` lets `VESSEL_MANAGED=1` keep managed mode, and the
 * vendored server now maintains `WM_STATE` so a managed Wine is not waiting on
 * a window manager that never speaks. Neither half works alone.
 *
 * A constant because it is not a preference, and one line to revert: flipping
 * Wine from unmanaged to managed changes geometry *and* focus handling for
 * every window, not just the one being dragged.
 *
 * **On, and honestly: on by choice rather than on the evidence.** What the
 * device showed is below — no harm found, and no benefit found either. It is
 * enabled because it is the direction this has to go and because leaving a
 * finished prerequisite switched off invites someone to delete it as dead code;
 * it is not enabled because a measurement asked for it.
 *
 * **What was measured, 2026-08-10.** Tried with
 * everything above in place and `VESSEL_MANAGED=1` confirmed present in the
 * guest's own `/proc/<pid>/environ`, so the switch really was reaching Wine.
 * `cmd` and `notepad` mapped and drew normally — and notepad, which *does*
 * reflow when resized, still did not follow a shell drag. The frame moved and
 * the client stayed. That is the one thing this was for, and it did not happen.
 *
 * *A stronger claim was written here first and it was wrong.* One dump caught
 * Metro's window `mapped=false` and this note said managed mode had stopped a
 * fullscreen game appearing. The simpler explanation was already recorded in
 * `docs/TODO.md`: **Metro minimises itself on focus loss**, and the taskbar had
 * just been revealed over it. Nothing here is evidence that managed mode breaks
 * a game.
 *
 * So it is off on "no measured benefit", not on "known harm" — a weaker and
 * more honest reason. The half that was never built is the likely explanation
 * for the lack of benefit: a managed Wine expects `_NET_SUPPORTING_WM_CHECK`,
 * `_NET_SUPPORTED`, `_NET_WM_STATE` and `_NET_ACTIVE_WINDOW`, and this server
 * advertises none of them. `patches/wine/0011` and the server's `WM_STATE` stay
 * in place: both are correct and both are prerequisites, and neither does
 * anything while this is false. *Done when:* EWMH is advertised and a
 * resizable program follows a drag.
 */
const val MANAGED_DESKTOP: Boolean = true

/**
 * Whether presents go through DRI3 instead of a whole-frame CPU copy.
 *
 * True leaves `MESA_VK_WSI_DEBUG` unset so Mesa's X11 WSI chooses DRI3, which
 * is only a choice at all since `patches/mesa/0004` put this build on the
 * `pseudo-drm` platform and gave it `HAVE_X11_DRM`. On the software path a
 * present costs a GPU `vkCmdCopyImageToBuffer` of the entire frame plus an
 * `xcb_put_image` of 3.6 MB at 720p; on DRI3 the swapchain image is the
 * window's own buffer and neither happens.
 *
 * **Failure here is loud, not subtle.** If DRI3 cannot negotiate against the
 * vendored server the result is no swapchain or a black window — not a slower
 * one — so a run that draws normally is most of the evidence needed. The
 * software path stays fully intact behind this constant, including the
 * measured `sw` versus `sw,linear` result at the assignment.
 *
 * **Off: tried on the device 2026-08-10 and the session died.** Metro reached
 * `initialization finished in 14.450132 sec` and then the log ends at
 * `X connection to :0 broken (explicit kill or server shutdown)`, with the app
 * process gone. `libxcb-dri3.so` and `libxcb-present.so` both loaded, so the
 * path was genuinely selected rather than skipped.
 *
 * *No X protocol error was logged*, which rules out the obvious first guess —
 * an unimplemented request coming back as an error reply — and leaves the cause
 * unproven. What is known is a real gap: `DRI3Extension` answers version 1.0
 * and exactly five opcodes (QueryVersion, Open, PixmapFromBuffer,
 * BufferFromPixmap, PixmapFromBuffers) and throws `BadImplementation` for
 * everything else, so `GetSupportedModifiers` (4), `FenceFromFD` (5) and
 * `FDFromFence` (6) are all absent. `FenceFromFD` is the one that matters:
 * Mesa's DRI3 uses an xshmfence to know when the server has finished with a
 * buffer, and there is no honest way to stub it — accepting the fd and ignoring
 * it would hand back frames that are still being read, which is corruption
 * rather than a crash.
 *
 * There is also a deeper question this attempt did not answer. Mesa's DRI3 WSI
 * is written against DRM, which is what `HAVE_X11_DRM` names; Turnip here talks
 * to KGSL and there is no DRM device to hand back from `xcb_dri3_open`. Whether
 * the `pseudo-drm` platform is sufficient for a *Vulkan* driver, or only ever
 * carried Zink, is the thing to establish before writing any server code.
 *
 * *Done when:* `tools/gfx/run-presentbench.sh` completes a run with this true.
 * That harness exists precisely so this can be answered without a game, and
 * using it first would have been the better order.
 */
const val ZERO_COPY_PRESENT: Boolean = false

/** Turnip's own startup channel, and the ground truth for whether it loaded at all. */
const val TU_DEBUG_STARTUP: String = "startup"

/**
 * `TU_DEBUG` values that mean "force nothing".
 *
 * The manifest expresses "leave the driver to decide" as an option value rather
 * than as an absent key, so that the editor can draw it as one choice among
 * several. Passing it through to Turnip would name a flag that does not exist.
 */
val TU_DEBUG_NO_OP: Set<String> = setOf("default", "none", "auto")

/** `DISPLAY` for the app's built-in X server, which Wine's X11 driver connects to. */
const val DEFAULT_DISPLAY: String = ":0"

/**
 * Variables this layer owns outright, which a manifest param may never set.
 *
 * Most are simply not settings — `WINEDEBUG` is fixed per `docs/LOGGING.md`,
 * `WINEESYNC` because esync is the only synchronisation mode that works here
 * (README, Known limitations).
 *
 * `VKD3D_LOG_FILE` is different: it is listed to guarantee its **absence**.
 * `vkd3d_dbg_init_once` is an if/else — set the variable and it opens the file
 * *instead of* resolving `__wine_dbg_output`, so it moves vkd3d's output off the
 * pipe the session log reads rather than copying it.
 */
val RESERVED_SESSION_ENV: Set<String> = setOf(
    "WINEPREFIX",
    "WINEESYNC",
    "WINEDEBUG",
    WINEDLLOVERRIDES_ENV,
    "DISPLAY",
    "DXVK_LOG_LEVEL",
    "DXVK_LOG_PATH",
    "VKD3D_DEBUG",
    "VKD3D_SHADER_DEBUG",
    "VKD3D_LOG_FILE",
    "TU_DEBUG",
    // Owned by the display server, which is the only thing that knows whether a
    // shared-memory socket got bound and where. A manifest param naming it would
    // point winex11 at a socket nothing is listening on, and patch 0005 answers
    // that with a connect(2) failure per damaged region rather than an error.
    SYSVSHM_SOCKET_ENV,
    "VESSEL_VULKAN_ICD",
    // Reserved rather than offered, because a caption is not a preference here:
    // Vessel's taskbar is the only window control on a phone and the shell draws
    // the move/resize borders itself. A container that turned this off would get
    // a 41px strip nothing paints and a client that overflows its own parent.
    "VESSEL_BORDERLESS",
    "VESSEL_MANAGED",
    // Not a driconf file and not a setting: it is a correctness/perf pairing
    // with FEX's store-release behaviour, and FEX would try to set it itself if
    // it could. See where it is assigned.
    "tu_override_uncached_as_cache_coherent",
    "ADRENOTOOLS_DRIVER_PATH",
    "ADRENOTOOLS_HOOKS_PATH",
    "ADRENOTOOLS_DRIVER_NAME",

    // The FEX memory-ordering flags are listed for the same reason as WINEESYNC:
    // they stopped being settings. Reserving them is what makes that stick —
    // a container document saved while they were still switches still carries
    // the old values, and without this the manifest merge would hand them back.
    "FEX_TSOENABLED",
    "FEX_HALFBARRIERTSOENABLED",
    "FEX_VECTORTSOENABLED",
    // The JIT lookup caches. Reserved for a different reason from the three
    // above: those are defaults kept visible, these are a deliberate
    // speed-for-memory trade, and a container that flipped one back would get
    // upstream's stutter with no way to tell that is what happened.
    "FEX_DISABLEL2CACHE",
    "FEX_DYNAMICL1CACHE",
    "FEX_APP_CACHE_LOCATION",

    // FEX's log destination, for the same reason as WINEDEBUG: docs/LOGGING.md
    // says everything diagnostic arrives on fd 2, and FEX's defaults send it
    // somewhere else.
    "FEX_SILENTLOG",
    "FEX_OUTPUTLOG",

    // The shader caches point at container-owned directories this layer chose.
    // A manifest param naming one could send a cache outside the container, or
    // share it between two containers on different driver builds.
    "MESA_SHADER_CACHE_DISABLE",
    "MESA_SHADER_CACHE_DIR",

    // Not a tuning knob on this build: the Turnip we ship has only the software
    // half of Mesa's X11 WSI compiled in, and clearing this sends every
    // swapchain into an `UNREACHABLE`. The whole argument is at the assignment.
    "MESA_VK_WSI_DEBUG",
    "DXVK_STATE_CACHE_PATH",
    "VKD3D_SHADER_CACHE_PATH",
    "VKD3D_CONFIG",
)

/**
 * A Turnip driver that is actually installed, with everything libadrenotools
 * needs to load it.
 *
 * All three fields or none. The Winlator lineage's
 * `AdrenotoolsManager.setDriverById()` falls through **without setting
 * `ADRENOTOOLS_DRIVER_*` and without logging** when a driver id does not
 * resolve, and the system Vulkan driver quietly takes over. Modelling "no
 * driver" as a null [TurnipDriver] makes that state impossible to construct by
 * accident.
 */
data class TurnipDriver(
    /**
     * The directory holding the driver `.so` — the installed Turnip component,
     * in the shared store at `files/components/Turnip/<versionCode>/`.
     *
     * Resolved by `ComponentStore.directoryFor(containerId, Turnip)`, never
     * assembled here: this function is pure and has no disk, and the container
     * does not own the bytes — it records which version it references, and one
     * store directory is shared by every container on that version.
     */
    val driverDir: File,
    /** `libraryName` from the package's `meta.json`, e.g. `libvulkan_freedreno.so`. */
    val libraryName: String,
    /**
     * The APK's own native library directory, where libadrenotools' hook objects
     * live. Supplied by the caller from `applicationInfo.nativeLibraryDir`; there
     * is nothing this layer could derive it from.
     */
    val hooksDir: File,
)

/**
 * The two container directories the environment names.
 *
 * Both are still per container — the prefix is the container, and the logs are
 * its history. Everything *shared* between containers, which is every installed
 * component, is reached through [TurnipDriver] and its siblings rather than from
 * here.
 */
data class SessionPaths(
    /** `WINEPREFIX` — `files/containers/<id>/prefix`. */
    val prefix: File,
    /**
     * `DXVK_LOG_PATH` used to be this, and is now `none`.
     *
     * Still needed: it is where the session log itself is written. But
     * pointing DXVK at it put the D3D layer's output in files beside the
     * log rather than in it — see the assignment in `sessionEnvironment`.
     */
    val logs: File,
    /**
     * Where the three shader caches live, per container.
     *
     * Defaulted so existing callers keep working, but it should be passed: on a
     * phone this is the difference between compiling every pipeline on every
     * launch and compiling it once. Mesa's cache in particular is **disabled by
     * default under Wine** — `MESA_SHADER_CACHE_DISABLE` defaults to true when
     * Mesa thinks it is running under a translation layer — so leaving this
     * unset is not "the usual behaviour", it is off.
     *
     * Per container rather than shared, because each of the three caches keys on
     * a driver/layer build and none of them is safe to share across a component
     * upgrade. Note vkd3d's cache has no pruning and no size cap of its own.
     */
    val caches: File = File(prefix.parentFile ?: prefix, "caches"),
)

/**
 * The environment a session is started with — `docs/LOGGING.md` as code.
 *
 * A pure function of its arguments: no `Context`, no disk, no clock. Every
 * failure mode here is silent (the wrong `WINEDEBUG` string produces an empty
 * log, not an error), so the only way to hold it to the document is to assert
 * the exact output in a unit test.
 *
 * Two things the document explains that are easy to undo from here: setting
 * `WINEDEBUG` correctly is necessary but not sufficient, because Wine skips
 * parsing it entirely when fd 2 is `/dev/null`; and `TU_DEBUG` always includes
 * [TU_DEBUG_STARTUP], which is the only ground truth for whether Turnip loaded.
 *
 * @param profile the container, for its manifest values.
 * @param manifest maps a param key to the environment variable it becomes. Null
 *   produces only the fixed variables — an incomplete environment is better than
 *   a guessed one.
 * @param turnip null when no Turnip package is installed, which omits the
 *   `ADRENOTOOLS_*` variables entirely rather than setting them empty.
 */
fun sessionEnvironment(
    profile: ContainerProfile,
    manifest: ParamManifest?,
    paths: SessionPaths,
    turnip: TurnipDriver? = null,
    display: String = DEFAULT_DISPLAY,
): Map<String, String> {
    val environment = LinkedHashMap<String, String>()

    environment["WINEPREFIX"] = paths.prefix.absolutePath
    environment["WINEESYNC"] = "1"

    // FEX's memory-ordering behaviour is fixed here, not offered as settings.
    //
    // **All three of these are FEX's own defaults, so none of them changes
    // anything.** Checked one by one against
    // `FEXCore/Source/Interface/Config/Config.json.in`: `TSOEnabled` is
    // `"Default": "true"` (:439), `HalfBarrierTSOEnabled` `"true"` (:462),
    // `VectorTSOEnabled` `"false"` (:447). They are kept because the value
    // being visible here is worth a line each — a reader should not have to
    // know FEX's defaults to know what Vessel runs with — but nothing below is
    // a choice Vessel is making at runtime, and the reasons are why the default
    // is right rather than why we overrode it:
    //
    //   TSOENABLED=1           x86 assumes Total Store Order and Arm does not
    //                          guarantee it. Off is faster and breaks any
    //                          multi-threaded program, quietly and at random.
    //   HALFBARRIERTSOENABLED=1  Backpatches *unaligned* loads and stores to
    //                          half-barrier atomics. The "21% cheaper"
    //                          measurement this comment used to cite is the
    //                          LRCPC2 result and belongs to a different knob;
    //                          tools/tso/run.sh now measures this one properly,
    //                          against `=0` and on unaligned traffic — the only
    //                          traffic the backpatch touches.
    //   VECTORTSOENABLED=0     Upstream calls the accurate version a "HUGE"
    //                          performance hit, and the hardware feature that
    //                          would make it cheap (FEAT_LRCPC3) is reported by
    //                          this CPU but not yet used by FEX's code
    //                          generator, so paying for it buys nothing.
    //
    // The names are all-caps with no internal underscores because FEX derives
    // them as `FEX_` + the JSON key uppercased: `TSOEnabled` becomes
    // `FEX_TSOENABLED` and never `FEX_TSOEnabled`. See docs/ARCHITECTURE.md.
    // FEX's own diagnostics reach the same pipe as everything else.
    //
    // SILENTLOG defaults to true, and that hides more than crash messages:
    // FEX_HOSTFEATURES parsing skips tokens it does not recognise with only a
    // log line to say so, so with the default a typo in a host-feature override
    // is completely invisible. Same failure shape as the WINEDEBUG traps
    // docs/LOGGING.md already catalogues. Turning it off is the whole fix —
    // FEX then binds ntdll's `__wine_dbg_output`, which is the pipe the session
    // log already reads.
    //
    // **OUTPUTLOG does nothing on Windows and is kept only as a marker.**
    // `Source/Windows/Common/Logging.cpp` is the entire Windows logging init
    // and it reads `SILENTLOG` and nothing else; if not silent it resolves
    // `__wine_dbg_output` and otherwise falls back to a file under LOCALAPPDATA.
    // `OUTPUTLOG` is a Linux/FEXServer option and is never consulted here. The
    // comment that used to sit above these two lines explained the log routing
    // in terms of `OUTPUTLOG` being `"server"` — the right conclusion reached
    // through a mechanism that does not exist on this platform.
    environment["FEX_SILENTLOG"] = "0"
    environment["FEX_OUTPUTLOG"] = "stderr"

    environment["FEX_TSOENABLED"] = "1"
    environment["FEX_HALFBARRIERTSOENABLED"] = "1"
    environment["FEX_VECTORTSOENABLED"] = "0"

    // **FEX's two JIT lookup caches, both turned back on.** Unlike the three
    // above these are *not* the defaults — they are the first FEX settings
    // Vessel actually changes, and both defaults trade speed for memory in a
    // way upstream itself flags:
    //
    //   DISABLEL2CACHE=0   Default true, "Disables FEXCore's JIT L2 cache
    //                      lookup. Saving memory. Can potentially introduce
    //                      more stutters." With it on, Dispatcher.cpp:177 emits
    //                      an unconditional branch past the entire inline L2
    //                      probe, so **every L1 miss falls out to the C++
    //                      FindBlock slow path**.
    //   DYNAMICL1CACHE=0   Default true, same warning. Dynamic starts L1 at
    //                      MIN_L1_ENTRIES (8K) instead of MAX (1M) and doubles
    //                      at most once per one-second sample period, so a
    //                      launch spends **up to seven seconds** with an
    //                      undersized L1 — and shrinks again when a title goes
    //                      quiet, paying the ramp a second time.
    //
    // **The cost is real and is memory, which is why this is not a free win.**
    // `LookupCacheEntry` is 16 bytes and `MAX_L1_ENTRIES` is 1M, so a fully
    // grown L1 is 16 MB *per guest thread*; a many-threaded game can add
    // hundreds of megabytes of resident memory on a phone, and an OOM kill is a
    // worse regression than a stutter. The address space is reserved either way
    // — dynamic sizing madvises the tail away rather than not mapping it — so
    // what changes is resident pages, not the mapping.
    //
    // **Measured on the device, 2026-08-10, and the honest reading is "no
    // harm", not "a win".** Metro's title screen ran 40 fps before and 40 fps
    // after, with PSS 226.8 MB → 228.8 MB at a comparable point: the feared
    // memory cost did not appear, because the 16 MB ceiling bounds *touched*
    // pages and a title screen touches few of them. But that scene cannot test
    // the benefit either — it is GPU-bound (see the resolution probe in
    // docs/TODO.md) with a tiny code working set, and this knob acts on block
    // dispatch, which surfaces as stutter during loading and level streaming.
    // Kept because the cost is measured at ~nil and upstream documents the
    // defaults as stutter sources; **the benefit is still unmeasured**, and
    // what would settle it is frame-time consistency across a level load, not
    // an average on a still image.
    //
    // **A second measurement, on a CPU-bound scene, also found nothing.** The
    // intro video is software-decoded and is the one part of a Metro launch
    // that is translation-bound rather than pixel-bound, so it should be where
    // block dispatch shows. Same build, same container, same scene: 10 fps with
    // these on, 12 fps with them at FEX's defaults — no gain, and the small
    // difference is noise between two points of the same video.
    //
    // *Worth recording because it nearly became a false result.* An earlier
    // reading of 2 fps on the same intro, taken before the clean reinstall,
    // made this look like a 5x win. It was not: that number came from a
    // different install with the older Wine, and what improved was the rebuild,
    // not these knobs. The pair above is the only comparison that holds one
    // variable.
    environment["FEX_DISABLEL2CACHE"] = "0"
    environment["FEX_DYNAMICL1CACHE"] = "0"

    // FEX's own cache lives beside the shader caches, not in LOCALAPPDATA.
    //
    // Everything else that caches compiled work for this container — Mesa,
    // DXVK, vkd3d — is under `caches/`, and that is also what gets cleared when
    // a container is reset. FEX defaulted to `%LOCALAPPDATA%\fex-emu\` inside
    // the prefix, which survives a cache clear and is invisible to anything
    // that reasons about container size.
    environment["FEX_APP_CACHE_LOCATION"] = File(paths.caches, "fex").absolutePath + File.separator
    //
    // **`FEX_ENABLECODECACHINGWIP` is deliberately NOT set, and this is the
    // reason rather than an oversight.** The flag makes a run write a codemap;
    // it does not compile one. Turning it into a cache needs
    // `FEXOfflineCompiler64.exe generate <codemap>`, which `build/fex.sh` now
    // packages but nothing invokes — so enabling the flag today would add a
    // file write per module load on every launch and never once read a cache
    // back. That is pure cost.
    //
    // Two things to settle before it goes on. Something has to run `generate`
    // — after a session ends is the obvious moment, since the codemap is
    // complete and the device is idle. And `CodeCacheConfigId` is `0 // TODO`
    // upstream (`ImageTracker.cpp`), so a cache is **not keyed on FEX's
    // configuration**: change a TSO setting and the next run silently loads a
    // cache built under the old one. The knobs above are exactly the kind of
    // change that would do it.
    environment["WINEDEBUG"] = WINEDEBUG_CHANNELS
    environment[WINEDLLOVERRIDES_ENV] = dllOverrides(profile, manifest)
    environment["DISPLAY"] = display

    // **No Win32 caption on any top-level window.** `patches/wine/0010` reads
    // this and clears `WS_CAPTION|WS_THICKFRAME` in win32u's own style-correction
    // block — which is where it has to happen, because that block *adds*
    // `WS_CAPTION` to every non-popup top-level and would otherwise put back
    // whatever a program left off.
    //
    // Measured before it was written, on the device: a 1280x720 game window had
    // a 1274x673 client at +3+44. That is a 3px border and a 41px caption; the
    // caption is 6% of a 720-row display, nothing paints it so it shows white,
    // and when the program resized to 640x480 the client kept the +44 and hung
    // 44 rows off the bottom of its own parent. The taskbar already carries
    // minimise, close and force-close, and move/resize is a toggle in the same
    // menu, so removing the frame takes nothing away.
    environment["VESSEL_BORDERLESS"] = "1"

    // See [MANAGED_DESKTOP]: what makes a shell-initiated move or resize reach
    // the client instead of stopping at the frame.
    if (MANAGED_DESKTOP) environment["VESSEL_MANAGED"] = "1"

    environment["DXVK_LOG_LEVEL"] = "info"

    // **`none`, so DXVK's output lands in the session log instead of beside it.**
    //
    // This pointed at `files/logs/<id>`, and the effect was the opposite of what
    // it looks like: DXVK wrote `<exe>_dxgi.log` and `<exe>_d3d11.log` into that
    // directory and the session log got **nothing**. Measured on a real game
    // launch — `grep -c 'info: '` over the session log: zero, while
    // `metro_dxgi.log` sat in the same folder holding the adapter, the enabled
    // extensions and the display mode. The one place a user is told to look was
    // the one place the D3D layer never wrote to.
    //
    // `log.cpp` is explicit about it. On a Wine build `emitMsg` sends every line
    // to `m_wineLogOutput` — Wine's own debug output, which is the pipe the
    // session log reads — *and* to a file if one is open; and `getFileName`
    // opens no file when the path is `none`, or when it is empty and
    // `m_wineLogOutput` exists. So `none` does not lose anything. It routes.
    //
    // This is the rule [RESERVED_SESSION_ENV] already states for
    // `VKD3D_LOG_FILE`, arrived at from the other direction: that one is
    // reserved to guarantee its *absence*, because setting it makes vkd3d open a
    // file **instead of** resolving `__wine_dbg_output`. Same mistake, same fix,
    // and the two layers now behave the same way.
    environment["DXVK_LOG_PATH"] = "none"

    environment["VKD3D_DEBUG"] = "warn"
    environment["VKD3D_SHADER_DEBUG"] = "warn"

    // Ray tracing is not reachable here, so stop games from trying.
    //
    // Turnip exposes VK_KHR_ray_query but not VK_KHR_ray_tracing_pipeline, and
    // vkd3d's tier ladder requires rayTracingPipeline even for Tier 1.0 — so
    // there is no DXR through this stack whatever a title asks for. On KGSL it
    // is worse than absent: raytracing is gated on a hardware fuse read through
    // KGSL_PROP_IS_RAYTRACING_ENABLED, so the acceleration-structure extension
    // may not appear at all.
    //
    // Deliberately NOT here: `force_raw_va_cbv`. vkd3d skips raw-VA CBVs on
    // Qualcomm on purpose — state.c calls the difference "profound (~15% in some
    // cases)" — and setting the flag would undo that. Tiler mode needs no flag
    // either; device.c turns it on for VK_DRIVER_ID_MESA_TURNIP by itself.
    environment["VKD3D_CONFIG"] = "nodxr"

    // The shader caches, all three, pointed at the container.
    //
    // Mesa's is the one that matters and the one most easily missed: it disables
    // itself when it detects a translation layer, so under Wine the default is
    // *off*, and every pipeline is recompiled on every launch. On a phone that
    // is the largest avoidable cost in the whole stack.
    environment["MESA_SHADER_CACHE_DISABLE"] = "false"
    environment["MESA_SHADER_CACHE_DIR"] = File(paths.caches, "mesa").absolutePath
    environment["DXVK_STATE_CACHE_PATH"] = File(paths.caches, "dxvk").absolutePath
    environment["VKD3D_SHADER_CACHE_PATH"] = File(paths.caches, "vkd3d").absolutePath

    environment["TU_DEBUG"] = tuDebugFlags(profile, manifest).joinToString(",")

    // **The only present path this driver has. Without it a swapchain is not an
    // error, it is a crash.**
    //
    // Mesa's X11 WSI is one file with two halves, and the DRI3 half is entirely
    // behind `#ifdef HAVE_X11_DRM`. `meson.build` defines that only when
    // `with_dri_platform == 'drm'`, and `build/turnip.sh` builds for KGSL with
    // no gallium driver, so it is never defined for us. Read out of the shipped
    // `libvulkan_freedreno.so`: `xcb_put_image` is present, and there is not one
    // `xcb_dri3_*`, `xcb_present_*` or `xcb_shm_*` reference in the whole
    // binary. Half a WSI was compiled, and it is the software half.
    //
    // Mesa does not know that at runtime. `wsi_conn->has_dri3` is set from the
    // *server's* extension list, outside the guard, and Vessel's X server does
    // implement DRI3 — so the surface reports support, the swapchain takes the
    // DRI3 branch, and lands on
    //
    //     UNREACHABLE("X11 DRM support missing!")     wsi_common_x11.c
    //
    // which in a release build is `__builtin_unreachable()`. That is the
    // "crashes when it tries to open" that every D3D program has done, and why
    // it was recorded as dying at swapchain creation with no error to read.
    //
    // `sw` flips `wsi_device->sw`, which is checked *before* DRI3 everywhere
    // that matters, and routes presentation to `x11_present_to_x11_sw` —
    // `xcb_put_image` of a mapped CPU buffer, no dma-buf, no fences, no
    // XFixes. Every request it makes is one the vendored X server already
    // implements. It is `|=` in Mesa, not a driver capability, so no Turnip
    // patch is needed.
    //
    // **This is not a debug switch here, which is why it is not a setting.** On
    // a Mesa built with DRI3 it would be one; on this one it is the difference
    // between a present path and no present path. `MESA_VK_WSI_DEBUG` is in
    // [RESERVED_SESSION_ENV] for exactly that reason.
    //
    // **`sw` and not `sw,linear`, and that is a measurement overturning an
    // argument rather than a default nobody questioned.**
    //
    // `sw` alone leaves `wsi_cpu_image_needs_buffer_blit` returning true, so a
    // swapchain image is an OPTIMAL-tiled VkImage *plus* a separate host buffer,
    // and every present does a GPU `vkCmdCopyImageToBuffer` of the whole frame
    // before `xcb_put_image` reads it. Adding `linear` makes that function
    // return false, forces `VK_IMAGE_TILING_LINEAR`, and maps the image's own
    // memory — one allocation instead of two, and no blit. On paper that is
    // strictly less work, and `tools/gfx/wsiprobe.c` costed the blit it removes
    // at 0.60 ms of GPU time a frame.
    //
    // It is slower. Measured on the device with `tools/gfx/x11present.c` —
    // a real Vulkan swapchain against this app's own X server, 400 frames each,
    // three runs apiece, 1280x720:
    //
    //     sw          mean 2.25 / 2.53 / 2.36 ms   p50 1.63 / 2.15 / 1.77
    //     sw,linear   mean 2.62 / 2.61 / 2.90 ms   p50 2.47 / 2.79 / 2.50
    //
    // ~14% worse on the mean and ~35% worse on the median, in the same
    // direction every run. The paper argument left out the side that matters on
    // a tiler: rendering *into* a linear image means the GMEM resolve writes an
    // untiled, uncompressed layout, and that costs more than the tiled resolve
    // plus the blit it was supposed to save. Adreno wants to render tiled and be
    // copied out of, not to render straight into a scanout layout.
    //
    // Leaving `MESA_VK_WSI_DEBUG` unset entirely was not an option and was not
    // merely slower: with no `HAVE_X11_DRM` the DRI3 branch was
    // `__builtin_unreachable()`, and the probe measured 12.8 ms a frame there —
    // a number produced by undefined behaviour, not by a slow path.
    //
    // **That last paragraph is why this was forced, and it stopped being true.**
    // `patches/mesa/0004` puts the build on Mesa's `pseudo-drm` platform, which
    // defines `HAVE_X11_DRM`, so the DRI3 branch is real compiled code now and
    // the driver links `libxcb-dri3`, `libxcb-present`, `libxcb-sync`,
    // `libxcb-shm` and `libxshmfence`. Unset, Mesa picks DRI3 and the swapchain
    // image *is* the window's buffer: no whole-frame `vkCmdCopyImageToBuffer`,
    // no `xcb_put_image`, no CPU copy. That is the zero-copy present this file
    // has been describing as unreachable.
    //
    // Both halves were proven separately before this: `tools/gfx/wsiprobe.c`
    // showed Turnip importing the server's dma-buf and binding a TILING_LINEAR
    // image to it at rowPitch 5120, and the vendored server already answers
    // DRI3 `BufferFromPixmap` over `SCM_RIGHTS` with `presentPixmap`'s flip
    // branch in place. Joining them has never been run.
    //
    // So [ZERO_COPY_PRESENT] is a switch, not a deletion. If DRI3 does not
    // negotiate against this server the symptom will be immediate and total —
    // a black window or no swapchain at all, not a slow one — and the way back
    // is one line. The `sw,linear` measurement above stays because it is still
    // the right answer for the software path.
    if (!ZERO_COPY_PRESENT) environment["MESA_VK_WSI_DEBUG"] = "sw"

    // **FEX asks Turnip for this and cannot deliver it here, so Vessel does.**
    //
    // x86 emulation turns guest stores into store-releases, and those are
    // punishing on uncached/write-combine memory — which is what a host-visible
    // upload or staging allocation usually lands in. The option makes Turnip
    // hand back the cached-coherent memory type instead
    // (`tu_device.cc:1816-1819`, guarded by `has_cached_non_coherent_memory`;
    // declared in `tu_drirc_gen.py:96`, default false).
    //
    // FEX tries to set it itself and silently fails on this Wine.
    // `Source/Windows/Common/EnvironmentVariablesHandling.cpp` resolves
    // `__wine_set_unix_env` out of ntdll and guards on `Sym &&` — and that
    // export **does not exist in Wine 11.14**, so the branch never runs and the
    // option keeps its `false` default. FEX's own comment calls the mechanism
    // "may also not be long-term viable" and suggests exactly this workaround:
    // set the variable in the launch script. Its check is
    // `getenv(...) == nullptr`, so setting it here is also what stops FEX
    // fighting us if that export ever returns.
    //
    // A plain env var works because Mesa checks one per driconf option name and
    // lets it override the built-in default (`util/xmlconfig.c:424-438`).
    // **Unmeasured** — it needs an x86-64 D3D title, so it cannot be attributed
    // by `presentbench`, which runs no guest x86 at all.
    environment["tu_override_uncached_as_cache_coherent"] = "true"

    // Setting these three is what makes win32u open the adrenotools handle
    // instead of `dlopen`ing the platform loader, and for one cycle they were
    // withheld because doing so hung `explorer.exe` before it drew anything.
    //
    // The comment that used to be here blamed the load *order* in
    // patches/wine/0006 — the handle being opened under Android's linker lock —
    // and it was wrong. What the device actually showed, with `+seh` on:
    //
    //   TU: Created an instance                              (logcat)
    //   err:seh:handle_syscall_fault code=c0000005 addr=0x…dcb0 pc=0x…dcb0
    //
    // …dcb0 was `android_get_exported_namespace` in libadrenotools' **.bss**.
    // linkernsbypass keeps the linker's private entry points in variables of
    // that name, and the APK built them with default visibility, so
    // libadrenotools.so exported three 8-byte objects whose names belong to
    // real dynamic-linker functions. libvndksupport calls one of them on the
    // way to every sphal library the Vulkan stack opens, bound to the pointer's
    // address rather than to libdl's function, and branched into data.
    //
    // The hang was collateral: Wine converts that fault to
    // STATUS_ACCESS_VIOLATION and unwinds the syscall, which abandons win32u's
    // `display_lock` — held across `get_vulkan_gpus()` — so the next window
    // creation blocks on it for ever. One thread in `futex_wait_queue` and a
    // black surface is what that looks like from outside, and it looks nothing
    // like its cause. The fix is one line in
    // `app/src/main/cpp/adrenotools/CMakeLists.txt`, which has the full note.
    //
    // Turnip itself was never the problem: it loads, reaches
    // `Found compatible device '/dev/kgsl-3d0'`, and §2 of docs/OPTIMIZATION.md
    // has the driverID.
    if (turnip != null && TURNIP_ENABLED) {
        // Both paths to the same driver file, and win32u picks between them by
        // asking the file itself: patches/wine/0009 dlopens VESSEL_VULKAN_ICD
        // first and keeps it only if it exports `vk_icdGetInstanceProcAddr`,
        // otherwise it closes it and takes the adrenotools path below. So an ICD
        // package gets the ICD path and a HAL package gets the platform loader,
        // with nothing here having to know which one is installed.
        //
        // Which matters because only the ICD can present to a window. The
        // Android platform loader owns the WSI layer, understands only surfaces
        // it made for an ANativeWindow, and faults on the Xlib surface the
        // driver hands back — see docs/GRAPHICS.md.
        environment["VESSEL_VULKAN_ICD"] = File(turnip.driverDir, turnip.libraryName).absolutePath

        // libadrenotools concatenates path and name, so without the trailing
        // separator it looks for `…/componentslibvulkan….so`.
        environment["ADRENOTOOLS_DRIVER_PATH"] = turnip.driverDir.absolutePath + File.separator
        environment["ADRENOTOOLS_HOOKS_PATH"] = turnip.hooksDir.absolutePath + File.separator
        environment["ADRENOTOOLS_DRIVER_NAME"] = turnip.libraryName
    }

    for ((key, value) in manifestEnvironment(profile, manifest)) {
        if (key !in RESERVED_SESSION_ENV) environment[key] = value
    }

    return environment
}

/**
 * Every manifest param that declares an `env`, resolved against this container.
 *
 * A param with no `env` produces nothing: `display.resolution` and
 * `display.fpsLimit` are consumed by the session surface, and inventing a
 * `DXVK_FRAME_RATE` for the latter because it looks like it should exist is the
 * fabrication the manifest exists to prevent.
 */
/**
 * `WINEDLLOVERRIDES`: the D3D and WGL set this build ships, then whatever the
 * container adds.
 *
 * The graphics half is not negotiable — without it Wine loads its own builtin
 * `d3d11` and the DXVK we shipped never runs, which is a silent and very large
 * performance loss rather than an error. So it is composed here rather than
 * exposed as a value the user could delete.
 *
 * The container's own list is appended after, and appending is the point: Wine
 * parses the string left to right and a later entry wins, so a user who really
 * does need `d3d9=b` to work around one program can have it without being able
 * to break the defaults for everything else by accident.
 *
 * `wine.dllOverrides` carries no `env` in the manifest precisely so that
 * [manifestEnvironment] leaves it alone and this function is the only writer.
 */
internal fun dllOverrides(profile: ContainerProfile, manifest: ParamManifest?): String {
    val base = D3D_DLL_OVERRIDES.joinToString(",") + "=n"
    val extra = manifest?.allParams.orEmpty()
        .firstOrNull { it.key == DLL_OVERRIDES_KEY }
        ?.let { spec -> profile.params[spec.key] ?: spec.defaultValue() }
        ?.let { it as? ParamValue.Text }
        ?.value
        .orEmpty()
        .trim()
        .trim(';')
    return if (extra.isEmpty()) base else "$base;$extra"
}

/**
 * The one key this file looks up by name.
 *
 * Everywhere else the manifest drives the environment through `env`, and a
 * `when (key)` is exactly what that design forbids. This is the exception
 * because the value is *composed* with a built-in list rather than copied, and
 * there is no way to express "append to this variable" in the manifest schema.
 * If a second one of these ever appears, the schema needs the feature, not
 * another constant here.
 */
private const val DLL_OVERRIDES_KEY = "wine.dllOverrides"

internal fun manifestEnvironment(
    profile: ContainerProfile,
    manifest: ParamManifest?,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (spec in manifest?.allParams.orEmpty()) {
        val name = spec.env ?: continue
        val value = profile.params[spec.key] ?: spec.defaultValue() ?: continue
        out[name] = value.asEnvValue()
    }
    return out
}

/**
 * `TU_DEBUG` as a flag list: whatever the container asks for, then `startup`.
 *
 * Only text and multi-select params contribute. A boolean or an integer cannot
 * name a Turnip flag, so one carrying `env: TU_DEBUG` is ignored rather than
 * rendered as `1`, which would be a flag Turnip does not have.
 */
internal fun tuDebugFlags(profile: ContainerProfile, manifest: ParamManifest?): List<String> {
    val fromContainer = manifest?.allParams.orEmpty()
        .filter { it.env == "TU_DEBUG" }
        .flatMap { spec ->
            when (val value = profile.params[spec.key] ?: spec.defaultValue()) {
                is ParamValue.Text -> listOf(value.value)
                is ParamValue.Choices -> value.values
                else -> emptyList()
            }
        }
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in TU_DEBUG_NO_OP }

    return (fromContainer + TU_DEBUG_STARTUP).distinct()
}

/**
 * One stored value as an environment variable's text.
 *
 * Booleans are `1`/`0` rather than `true`/`false`, because that is what FEX's
 * config reader takes. Note the variable is `FEX_TSOENABLED`, never
 * `FEX_TSOEnabled` — see `docs/ARCHITECTURE.md`.
 */
internal fun ParamValue.asEnvValue(): String = when (this) {
    is ParamValue.Flag -> if (value) "1" else "0"
    is ParamValue.Count -> value.toString()
    is ParamValue.Text -> value
    is ParamValue.Choices -> values.joinToString(",")
}
