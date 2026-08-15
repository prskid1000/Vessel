package app.vessel.core

import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import java.io.File
import java.security.MessageDigest

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
 * Whether every memory-ordering knob FEX has is closed, not just the scalar one.
 *
 * **An experiment with a stated cost, not a setting.** See where it is read for
 * what it turns on and why; the short version is that vector stores, memcpy and
 * ARM64EC volatile metadata are all at FEX's relaxed default, so a value
 * published by a 128-bit store need not be visible to another thread in program
 * order — and a lost publication is indistinguishable from a deadlock when
 * every thread is asleep, which is what Requiem does.
 *
 * Turn it off again once the run has answered. Leaving it on because a session
 * looked fine would ship upstream's "HUGE" vector-TSO cost on the strength of
 * one observation, which is the kind of trade this file records rather than
 * makes by accident.
 *
 * **Run, and it answered no. 2026-08-14.** All three closed, verified present in
 * the guest's own `/proc/<pid>/environ` rather than assumed, and Resident Evil
 * Requiem stalled in exactly the same place: the same Streamline critical
 * section `388A7E28`, the same acquire site `0x6FFFF246B0`, only the thread ids
 * rotated. So the deadlock is not a lost publication, and this is off because it
 * buys nothing here and costs frames. It is kept rather than deleted because
 * "ordering was eliminated" is only worth anything if the next person can
 * re-run the thing that eliminated it.
 */
const val STRICT_MEMORY_ORDERING: Boolean = false

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

    // Also the exec model, and they belong here for the same reason
    // `WINEDLLPATH` does: they say where part of the Wine tree is, not what the
    // session should report. `wineboot --init` registers the builtin DLLs, and
    // that includes `winegstreamer` — which loads its unix half and calls
    // `gst_init()`. With no plugin path that scan finds nothing and writes an
    // empty registry into the container directory, and every later process
    // reuses it. So the cost of omitting these is not "the bootstrap has no
    // media", it is a poisoned cache. See `wineLauncherEnvironment`.
    "GST_PLUGIN_SYSTEM_PATH",
    "WINE_GST_REGISTRY_DIR",
    "GST_REGISTRY_FORK",

    "WINEPREFIX",
    "WINEESYNC",
    "WINEDEBUG",

    "FEX_SILENTLOG",
    "FEX_OUTPUTLOG",
    "FEX_TSOENABLED",
    "FEX_HALFBARRIERTSOENABLED",
    "FEX_VECTORTSOENABLED",
    "FEX_MEMCPYSETTSOENABLED",
    "FEX_VOLATILEMETADATA",
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
 * an unimplemented request coming back as an error reply — and left the cause
 * unproven.
 *
 * **The gap that explained it has since been closed, and this note was wrong
 * about it twice.** It said the server implemented five opcodes and was missing
 * `GetSupportedModifiers` (4), `FenceFromFD` (5) and `FDFromFence` (6). Those
 * numbers are not the protocol's: `FenceFromFD` is 4, `FDFromFence` is 5 and
 * `GetSupportedModifiers` is 6. And `FenceFromFD` is no longer missing —
 * `DRI3Extension.ClientOpcodes` carries it, `fenceFromFD` is implemented
 * against `SyncExtension`, and `app/src/main/cpp/winlator/src/xshmfence.c`
 * backs it with a real futex rather than the stub this note said would be
 * dishonest. That was written after the failed attempt, so the 2026-08-10
 * result predates the fix and is not evidence about the current server.
 *
 * What the server answers today is version 1.0 with opcodes 0, 1, 2, 3, 4 and
 * 7 — QueryVersion, Open, PixmapFromBuffer, BufferFromPixmap, FenceFromFD and
 * PixmapFromBuffers. At 1.0 Mesa asks for no more than that: modifiers and
 * `BuffersFromPixmap` are 1.2 features it will not reach for, so `FDFromFence`
 * and `GetSupportedModifiers` being absent is not a gap at the version we
 * advertise.
 *
 * So this is worth retrying, and `tools/gfx/run-presentbench.sh` is how —
 * it fixes `MESA_VK_WSI_DEBUG` from outside the app and puts both paths side by
 * side in one run, without reinstalling anything or needing a game.
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
    // Composed from `display.fpsLimit` alongside the compositor's own pacing, so
    // that one setting caps the renderer and the blit together. Reserved because
    // a container able to write either of these directly could set a renderer cap
    // that disagrees with the compositor's — and the symptom of that is not an
    // error but a phone that runs hot at a frame rate the user thought they had
    // limited, which is precisely the bug this pair was added to fix.
    "DXVK_FRAME_RATE",
    "VKD3D_FRAME_RATE",
    // Two paths, not two settings — which is why they are here and the flags
    // that make them useful are ordinary manifest params.
    //
    // `TU_DEBUG_FILE` names a file Turnip *watches*: a subset of its flags
    // (`perf`, `sysmem`, `gmem`, `nolrz`, `forcebin`, `log_skip_gmem_ops` —
    // `tu_util.cc:70`) can be changed **while the game is running**. That is the
    // only way this project can get a GMEM-versus-sysmem number worth quoting:
    // `docs/OPTIMIZATION.md` records the same measurement moving 15% between
    // sessions on thermals alone, so a comparison across two launches is noise.
    // Within one session, same scene, seconds apart, it is a measurement.
    //
    // `MESA_GPU_TRACEFILE` is where the per-render-pass trace lands when
    // `MESA_GPU_TRACES` asks for one. Both are reserved for the reason
    // `VESSEL_GFX_STATS` is: they name paths this app creates and reads, and a
    // container document that could move them could have the driver writing
    // outside the container.
    "TU_DEBUG_FILE",
    "MESA_GPU_TRACEFILE",
    // Owned by the display server, which is the only thing that knows whether a
    // shared-memory socket got bound and where. A manifest param naming it would
    // point winex11 at a socket nothing is listening on, and patch 0005 answers
    // that with a connect(2) failure per damaged region rather than an error.
    SYSVSHM_SOCKET_ENV,
    "VESSEL_VULKAN_ICD",
    // Where the D3D layer writes the counters this app graphs. Reserved for the
    // reason the long comment above this set gives: a variable a manifest param
    // could overwrite is a variable a container document can corrupt, and this
    // one names a path the app opens and reads. A document that pointed it at
    // `/sdcard/…` would have Wine writing outside the container; one that
    // pointed it at the prefix would have the sampler reading a file nothing
    // updates and drawing a flat line that is not a measurement. Neither is a
    // preference anyone wants to be able to express, and the graphs are not
    // improved by being able to move their own source.
    "VESSEL_GFX_STATS",
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
    "FEX_MEMCPYSETTSOENABLED",
    "FEX_VOLATILEMETADATA",
    // The JIT lookup caches. Reserved for a different reason from the three
    // above: those are defaults kept visible, these are a deliberate
    // speed-for-memory trade, and a container that flipped one back would get
    // upstream's stutter with no way to tell that is what happened.
    "FEX_DISABLEL2CACHE",
    "FEX_DYNAMICL1CACHE",
    "FEX_APP_CACHE_LOCATION",
    // Reserved because it is half of a pair. The flag makes a run *write* a
    // codemap; teardown is what turns one into a cache. A container that turned
    // the flag off would keep the teardown step and get nothing to compile; one
    // that turned it on where Vessel had turned it off would write codemaps
    // nothing reads. Neither is a setting anyone wants to be able to express.
    "FEX_ENABLECODECACHINGWIP",

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
    "VKD3D_SHADER_CACHE_PATH",
    "VKD3D_CONFIG",

    // Which logger Mesa picks. Reserved rather than merely unset, because it is
    // in [DIAGNOSTIC_SESSION_ENV] below and that set is required to be a subset
    // of this one: the guarantee is that a *manifest param* can never reach a
    // diagnostic variable, whatever the Diagnostics surface may do with it.
    "MESA_LOG",
    // And which severity floor it applies, which is a second variable and not a
    // spelling of the first — see [FIXED_MESA_LOG_LEVEL]. Reserved for the same
    // reason as its neighbour, and added at the same time as the `driver` trace
    // topic, which is the first thing in this product that sets it.
    "MESA_LOG_LEVEL",
)

/**
 * The variables the Diagnostics surface may write, and nothing else.
 *
 * **A strict subset of [RESERVED_SESSION_ENV], and that is the whole design.**
 * The obvious move — take these out of the reserved set and declare them as
 * manifest params — is wrong for the reason the reserved set already states for
 * the FEX flags at the comment above: reserving is what makes "this stopped being
 * a setting" stick, because a container document saved while it *was* a setting
 * still carries the old value and the manifest merge would hand it back.
 * Unreserving `WINEDEBUG` re-opens exactly that, and would let a hand-edited
 * document bypass every ordering rule `docs/LOGGING.md` exists to enforce.
 *
 * So the manifest stage keeps its filter untouched and a third stage runs after
 * it, gated on this set. Four properties fall out, and each is the answer to a
 * way the alternatives fail:
 *
 *  1. No container document can reach any of these through a param, ever. The
 *     partition is explicit rather than implied by absence.
 *  2. The diagnostics stage *narrows*: `WINEDEBUG` is composed by
 *     [composeWineDebug], which starts from [WINEDEBUG_CHANNELS] and appends, so
 *     the fixed prefix cannot be deleted by anything on that screen. Same shape
 *     as [dllOverrides], same reason.
 *  3. **`VKD3D_LOG_FILE` and `MESA_VK_WSI_DEBUG` stay unreachable by any path**,
 *     because they are not here. The two variables whose whole purpose is an
 *     absence and a fixed value cannot be written by a param or by Diagnostics.
 *  4. The set is assertable: ⊆ [RESERVED_SESSION_ENV], and an untouched record
 *     produces an empty diagnostics map. Two assertions, and they are in
 *     `SessionEnvironmentTest`.
 *
 * **`TU_DEBUG` is here but its control is gated in the UI**, not in this set.
 * Turnip's output goes to logcat, which Vessel does not read, so a Turnip flag is
 * only useful once `MESA_LOG=file` is on — and the Diagnostics surface says so
 * and disables the control until it is, rather than pretending the flags do not
 * exist. `diagnosticEnvironment` clears them for the same reason, so a
 * hand-edited document cannot reach them either.
 */
val DIAGNOSTIC_SESSION_ENV: Set<String> = setOf(
    "WINEDEBUG",
    "DXVK_LOG_LEVEL",
    "VKD3D_DEBUG",
    "VKD3D_SHADER_DEBUG",
    "TU_DEBUG",
    "FEX_SILENTLOG",
    "MESA_LOG",
    "MESA_LOG_LEVEL",
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
 * The installed FEX package, for the two things the environment needs from it.
 *
 * Resolved by `ComponentStore.directoryFor(containerId, FEXCORE)` and handed in
 * for the same reason as [TurnipDriver]: this function is pure and has no disk.
 *
 * Null is a real state and not an error here — the *session* cannot start
 * without FEX, but [sessionEnvironment] is also called by tests and by the
 * container screens, and an environment that omits the cache key is better than
 * one that invents a package identity.
 */
data class FexPackage(
    /** `files/components/FEXCore/<versionCode>/`. */
    val directory: File,
    /**
     * What identifies this build of FEX to the code cache.
     *
     * **Honest about its own resolution.** The version code alone would call two
     * rebuilds of FEX-2608 the same package, and they generate different code;
     * the DLL lengths catch a rebuild that changed anything at all, which every
     * real one does. What it cannot catch is two builds whose DLLs happen to be
     * the same length and differ inside — nothing cheap can, and FEX's own cache
     * header has the identical gap (`// TODO: Also check for matching FEX
     * version from cache header`, `FEXOfflineCompiler/Main.cpp`). Hashing 10 MB
     * of DLL on every launch would close it and is not worth the launch time.
     */
    val identity: String,
    /**
     * `FEXOfflineCompiler64.exe`, or null when the installed package predates
     * `build/fex.sh` packaging it. The whole cache feature is off in that case:
     * writing codemaps nothing compiles is the pure cost this used to be.
     */
    val offlineCompiler: File?,
    /**
     * Every `FEXOfflineCompiler*.exe` the package ships, 32- and 64-bit.
     *
     * Both, because `ProcessAll` re-execs itself per module and rewrites the
     * trailing name of its own path to pick the sibling matching that module's
     * bitness — so the 32-bit one has to be reachable from wherever the 64-bit
     * one was launched, even though nothing ever invokes it directly.
     */
    val compilers: List<File> = listOfNotNull(offlineCompiler),
)

/**
 * `FEX_*` variables that are deliberately **not** part of the cache key.
 *
 * The key is derived from the environment map rather than from a list of knobs,
 * because a list of knobs goes stale the first time someone adds one and the
 * failure mode of a stale *inclusion* list is a cache silently built under
 * different codegen settings. So the rule is "every `FEX_*` variable", and this
 * is the exception list — which fails the safe way round: forgetting to exclude
 * something costs a rebuild, forgetting to include something is a correctness
 * bug, and only the first can happen now.
 *
 * Three entries, each for a stated reason:
 *
 *  - `FEX_APP_CACHE_LOCATION` is the *output*. Including it would be circular.
 *  - `FEX_SILENTLOG` and `FEX_OUTPUTLOG` choose where FEX's diagnostics go and
 *    have no effect on generated code. Excluding them is not just tidiness:
 *    both are reachable from the Diagnostics screen, and a user who turns FEX
 *    logging on to investigate a problem would otherwise be handed a *cold*
 *    cache — a different situation from the one they are trying to observe.
 */
val FEX_CACHE_KEY_IGNORED: Set<String> = setOf(
    "FEX_APP_CACHE_LOCATION",
    "FEX_SILENTLOG",
    "FEX_OUTPUTLOG",
)

/**
 * A short digest of everything about FEX that decides what code it generates.
 *
 * **This is Vessel's answer to `CodeCacheConfigId` being `0 // TODO` upstream.**
 * `ImageTracker.cpp` keys a cache file on the guest binary and on a config id
 * that is hardcoded to zero, so FEX will happily load a cache built under a
 * different TSO setting or a different CPU tuning and run it. That is a
 * correctness hazard rather than a performance one, and it needs no FEX patch
 * to fix from here: Vessel owns `FEX_APP_CACHE_LOCATION`, so putting the digest
 * in the *path* means a configuration change lands in a different directory and
 * the old cache becomes unreachable instead of silently wrong.
 *
 * Derived from [environment] as it actually ended up — after the manifest and
 * diagnostics stages, so a knob a container adds is in the key without anything
 * here being told about it. See [FEX_CACHE_KEY_IGNORED] for the three
 * exceptions and why the exception list is the safe direction.
 *
 * Twelve hex characters of SHA-256. It is a directory name, not a security
 * boundary: 48 bits is far past the point where an accidental collision between
 * two configurations of one phone is worth thinking about, and a shorter path is
 * a readable one.
 */
internal fun fexCacheKey(environment: Map<String, String>, fex: FexPackage?): String {
    val material = buildString {
        append("fex=").append(fex?.identity ?: "none").append('\n')
        environment.keys
            .filter { it.startsWith("FEX_") && it !in FEX_CACHE_KEY_IGNORED }
            .sorted()
            .forEach { append(it).append('=').append(environment[it]).append('\n') }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}

/**
 * What FEX is told, and the only shape of string it can both write and read.
 *
 * A DOS path ending in a backslash, because `ImageTracker::LoadAOTImages`
 * concatenates `\??\` onto it and hands the result to `NtOpenFile`. See the
 * `FEX_APP_CACHE_LOCATION` assignment in `sessionEnvironment` for the whole
 * asymmetry and why a unix path filled the cache and never read it back.
 *
 * A constant rather than a per-container value: it is a path *inside* the
 * prefix, so it is already scoped to the container, and [fexCacheLink] is what
 * makes it land in the right place.
 */
internal const val FEX_CACHE_DOS_PATH = "C:\\vessel\\fexcache\\"

/**
 * The DOS directory the cache link and the compiler aliases both live in.
 *
 * The compiler has to be *launched* from a DOS path as well as pointed at one:
 * `ProcessAll` re-execs itself per module through `GetModuleFileNameA`, and a
 * unix self-path is not something `_spawnv` can start.
 */
internal const val FEX_CACHE_DOS_DIR = "C:\\vessel\\"

/**
 * The host side of [FEX_CACHE_DOS_PATH] — the symlink the session maintains.
 *
 * Wine resolves `C:` through `dosdevices/c:` to `drive_c`, so this is the file
 * that has to exist and point at [fexCacheHost] before the guest starts.
 */
internal fun fexCacheLink(prefix: File): File =
    File(File(File(prefix, DriveMap.DRIVE_C), "vessel"), "fexcache")

/**
 * What vkd3d-proton is told, and the only shape of string that reaches
 * `caches/vkd3d` rather than a drive that is never there.
 *
 * vkd3d's disk cache treats a value starting with `/` as a Unix path and
 * rewrites it to `Z:\...` before opening it — "Match DXVK style here",
 * `native/vkd3d/libs/vkd3d/cache.c:vkd3d_pipeline_library_init_disk_cache` —
 * on the standard Wine assumption that `Z:` is a symlink to the Unix root.
 * [DriveMap.removeRootDrive] takes `Z:` out of every prefix on purpose, so
 * that rewrite names a drive that has no `dosdevices/z:` to resolve through,
 * `_open()` fails on the very first launch rather than only after a killed
 * one, and vkd3d logs
 *
 *     Failed to open stream archive write file exclusively: Z:\...cache.write
 *
 * and never writes a pipeline cache — every shader recompiles on every start.
 * `DXVK_STATE_CACHE_PATH` right below this does not hit the same wall only
 * because DXVK 2.x no longer has an on-disk state cache to open; the variable
 * is set and never read.
 *
 * A path that is already Windows-shaped skips the rewrite (the `else if
 * (path)` branch beside it), so this is a DOS path under `C:` — the one
 * drive every prefix has — the same fix [FEX_APP_CACHE_LOCATION] already
 * uses for the same reason. [vkd3dCacheLink] is what makes it resolve to
 * `caches/vkd3d`.
 *
 * **Confirmed on device.** After the change Requiem logs `Performing async
 * setup of stream archive ... Done` where it used to log the failure, and
 * `caches/vkd3d/vkd3d-proton.re9.exe.cache.write` is a real file with real
 * bytes in it. Both halves matter: the absence of an error message would also
 * be what a silently skipped cache looks like.
 */
internal const val VKD3D_CACHE_DOS_PATH = "C:\\vessel\\vkd3dcache\\"

/**
 * The host side of [VKD3D_CACHE_DOS_PATH] — the symlink the session
 * maintains. See [fexCacheLink], which does the same thing for FEX.
 */
internal fun vkd3dCacheLink(prefix: File): File =
    File(File(File(prefix, DriveMap.DRIVE_C), "vessel"), "vkd3dcache")

/**
 * Where the bytes actually live: `caches/fex/<digest>/`.
 *
 * The digest is [fexCacheKey] over [environment], so this must be called with
 * the environment as it ended up — after the manifest and diagnostics stages —
 * or the cache is keyed on a configuration that did not run.
 *
 * Under `caches/` rather than in the prefix so that a container reset clears it
 * and container-size accounting can see it. That was the reason FEX's cache was
 * moved out of `%LOCALAPPDATA%` in the first place, and pointing
 * [FEX_CACHE_DOS_PATH] at a real directory inside the prefix would have quietly
 * undone it.
 */
internal fun fexCacheHost(paths: SessionPaths, environment: Map<String, String>, fex: FexPackage?): File =
    File(File(paths.caches, "fex"), fexCacheKey(environment, fex))

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
    /**
     * `TMPDIR` and `XDG_RUNTIME_DIR` — `files/containers/<id>/tmp`.
     *
     * Named here as well as in [SessionScratch] because one thing in this
     * function's output lives in it: [GFX_STATS_FILE], the snapshot the D3D
     * layer writes its counters to. The container's scratch is the right home
     * for it — it is a file with the lifetime of a session, it is inside the
     * app's own data so both sides can open it without a permission question,
     * and a container reset takes it with everything else. Beside the log would
     * put a file nothing appends to in a directory whose whole contents are
     * append-only history; beside the caches would put a file that is rewritten
     * every second among files that exist to survive.
     *
     * Defaulted the same way [caches] is, so a caller that predates this keeps
     * working, and derived from the prefix's parent for the same reason.
     */
    val tmp: File = File(prefix.parentFile ?: prefix, "tmp"),
)

/**
 * The D3D layer's counter snapshot, inside the container's scratch directory.
 *
 * One fixed name rather than one per session. The container's `tmp` is already
 * per container and only one session of a container runs at a time, so the file
 * *is* per session in practice — and a name carrying a timestamp would leave a
 * snapshot behind for every run that ever happened, in a directory nothing
 * prunes. What the reader does about a file left over from the previous session
 * is a freshness check rather than a unique name: see
 * [app.vessel.data.MetricSampler], which treats a snapshot older than a few
 * seconds as no reading at all, because that is also the answer for a program
 * that has stopped drawing.
 */
const val GFX_STATS_FILE: String = "gfx-stats.json"

/** [GFX_STATS_FILE] inside a container's scratch directory, for both sides of it. */
fun gfxStatsFile(tmp: File): File = File(tmp, GFX_STATS_FILE)

/** `TU_DEBUG_FILE` — Turnip re-reads this mid-session. See `RESERVED_SESSION_ENV`. */
const val TU_DEBUG_FILE_NAME: String = "tu-debug"

/** `MESA_GPU_TRACEFILE` — where a per-render-pass trace lands. */
const val GPU_TRACE_FILE_NAME: String = "gpu-trace.csv"

/** The live Turnip flag file for a container, for anything that wants to write it. */
fun turnipDebugFile(tmp: File): File = File(tmp, TU_DEBUG_FILE_NAME)

/** The per-render-pass trace for a container. */
fun gpuTraceFile(tmp: File): File = File(tmp, GPU_TRACE_FILE_NAME)

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
 * Composed in three stages, in this order: the fixed values below, then the
 * manifest's contributions filtered against [RESERVED_SESSION_ENV], then the
 * container's [ContainerDiagnostics] filtered against [DIAGNOSTIC_SESSION_ENV].
 * The third stage is empty for a container nobody has diagnosed, which is what
 * makes "a fresh container runs with exactly the environment above" true rather
 * than merely intended.
 *
 * @param profile the container, for its manifest values and its diagnostics.
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
    fex: FexPackage? = null,
    display: String = DEFAULT_DISPLAY,
    /**
     * `display.fpsLimit`, already parsed. Null caps nothing.
     *
     * Passed in rather than read from [profile] here, even though this function
     * has the manifest and could call `parseFpsLimit` itself, because the session
     * has already resolved it for the compositor and the two must be the same
     * number. Two readers of one param is how a container ends up rendering at
     * one rate and presenting at another.
     */
    fpsLimit: Int? = null,
    /**
     * What the guest is told this phone has, already resolved.
     *
     * Passed in for the same reason [fpsLimit] is: the session needs these
     * numbers itself — it writes the driconf file from them — and a second
     * reader of the same params is how the guest ends up being told two
     * different things. Null, or a value that matches the device, adds no
     * variables at all.
     */
    hardware: HardwareLimits? = null,
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
    //   HALFBARRIERTSOENABLED=0  **Measured, and the opposite of FEX's default.**
    //                          It backpatches *unaligned* loads and stores to
    //                          half-barrier atomics, and on this core that
    //                          costs 3x on exactly the traffic it touches.
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
    //
    // **Do not turn `FEX_OUTPUTLOG` into a control.** It is sent because it is
    // the right value if FEX ever consults it, and for no other reason: nothing
    // on this platform reads it, so a setting built on it would be a switch
    // wired to nothing — which is worse than no switch, because it looks like it
    // was tried. The Diagnostics screen keeps a read-only row saying the same
    // thing; this says it where the control would actually be added.
    environment["FEX_SILENTLOG"] = FIXED_FEX_SILENTLOG
    environment["FEX_OUTPUTLOG"] = FIXED_FEX_OUTPUTLOG

    environment["FEX_TSOENABLED"] = "1"

    /*
     * **Off, against FEX's own default, because it is measured to cost 3x here.**
     *
     * `tools/tso/run.sh`, twice, on the device (`ZD2232JMB9`, Oryon). Times are
     * the best of each set, in the units tsobench prints:
     *
     *                                          aligned   unaligned
     *   default (half-barrier on)                145.8       514.5
     *   half-barrier off                         144.3       167.5
     *
     * 3.07x on unaligned traffic, which is the only traffic the backpatch
     * touches, and aligned traffic does not move. **The native ARM64 control is
     * flat across all three rows of the same run** (165-170 aligned, 183.0
     * unaligned) — a native binary cannot be affected by a FEX knob, so a flat
     * control is what says this is ordering and not thermals or scheduling.
     * Both runs reproduced the figures to a tenth.
     *
     * This reverses what this file did before, and the reversal is the point:
     * FEX defaults `HalfBarrierTSOEnabled` to *true*, so Vessel's previous `1`
     * was setting what FEX already did and could never have been shown wrong by
     * a `1`-vs-default comparison. `run.sh` compares against `=0` for exactly
     * that reason, and `=0` won.
     *
     * **`TSOENABLED` above stays 1 and this does not weaken it.** That one is
     * correctness — x86 assumes Total Store Order, Arm does not guarantee it,
     * and turning it off breaks multi-threaded programs quietly. This knob only
     * chooses *how* unaligned accesses are made ordered, not whether they are.
     *
     * If a future core or FEX version changes the answer, re-run `run.sh`; the
     * comparison it makes is the one that can move.
     */
    environment["FEX_HALFBARRIERTSOENABLED"] = "0"

    /*
     * **The rest of the ordering surface, and it is an experiment rather than a
     * default.** `TSOENABLED=1` above makes scalar accesses ordered and
     * `HALFBARRIERTSOENABLED` only chooses how; three knobs decide whether
     * anything else is, and all three are at FEX's relaxed setting:
     *
     *   VectorTSOEnabled      false  vector load/stores are not made atomic
     *   MemcpySetTSOEnabled   false  memcpy/memset are not made atomic
     *   VolatileMetadata      true   ARM64EC PE metadata may relax individual
     *                                instructions below the settings above
     *
     * So a value published by a 128-bit store, or by a memcpy, is not
     * guaranteed visible to another thread in program order. That is the only
     * unenforced ordering left in the session, and it produces exactly the
     * failure being chased on 2026-08-14: every thread asleep, one holding a
     * critical section it never releases, no CPU burning anywhere. A lost
     * publication and a lock nobody wakes from look identical from outside.
     *
     * [STRICT_MEMORY_ORDERING] closes all three so that one run can say whether
     * ordering is the cause. **It is not free** — upstream calls accurate vector
     * TSO a "HUGE" performance cost, which is why FEX ships it off — so this
     * does not become the default on the strength of one green run. If the
     * stall survives it, ordering is eliminated and this goes back to `false`;
     * if it clears, the next question is which of the three did it, one at a
     * time, and what the frame cost of keeping that one is.
     */
    if (STRICT_MEMORY_ORDERING) {
        environment["FEX_VECTORTSOENABLED"] = "1"
        environment["FEX_MEMCPYSETTSOENABLED"] = "1"
        environment["FEX_VOLATILEMETADATA"] = "0"
    } else {
        environment["FEX_VECTORTSOENABLED"] = "0"
    }

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
    // **A DOS path, and it has to be one.** FEX uses this string two ways and
    // only a DOS path satisfies both. The writer — `ImageTracker.cpp:158` and
    // `FEXOfflineCompiler`'s `ProcessAll` — goes through `std::filesystem` and
    // `_sopen`, which are Win32, and Wine's `RtlGetFullPathName_UEx` resolves a
    // leading `/` through `\??\unix\…`; that is why a unix path filled
    // `codemap/new/` and looked like it worked. The reader does not use a Win32
    // API at all: `ImageTracker::LoadAOTImages` composes an NT path by string
    // concatenation — `fmt::format("\\??\\{}cache\\{}-{:016x}", …)`
    // (`ImageTracker.cpp:238`) — and hands it to `NtOpenFile`. Given a unix
    // path that yields `\??\/data/user/0/…`, and `nt_to_unix_file_name_no_root`
    // takes everything up to the first backslash as the DOS device prefix and
    // bails at `if (wcschr( prefix, '/' ))` (`ntdll/unix/file.c:3793`). It logs
    // nothing when the open fails, so a cache was being written every session
    // and silently never read.
    //
    // `C:\vessel\fexcache\` satisfies both: Win32 resolves it, and `\??\C:\…`
    // is a DOS device path `nt_to_unix_file_name_no_root` accepts.
    //
    // The bytes still live under `caches/`. [fexCacheHost] is the real
    // directory and the session points `drive_c/vessel/fexcache` at it as a
    // symlink before the guest starts, so a container reset still clears the
    // cache and container-size accounting still sees it — which is the whole
    // reason it was moved out of `%LOCALAPPDATA%`. A drive letter would have
    // worked too and was rejected: `PrefixRegistry` derives the drive list from
    // `dosdevices` on purpose, so a cache drive would appear in the Files tab
    // and in the registry seed, and hiding it again would mean special-casing
    // the one thing that file exists to keep single-sourced.
    //
    // The configuration digest moved out of this value and into the symlink's
    // target — see [fexCacheHost]. Keeping it here would have meant handing FEX
    // a DOS path that changes whenever a knob changes, which is the same
    // information in a place nothing can read it back from.
    environment["FEX_APP_CACHE_LOCATION"] = FEX_CACHE_DOS_PATH

    // **`FEX_ENABLECODECACHINGWIP` is on, and both of the reasons it used to be
    // off are now answered.**
    //
    // The flag makes a run write a *codemap* — it does not compile one. On its
    // own that is pure cost: a file write per module load on every launch and
    // never a cache read back. Two things had to exist first, and both do:
    //
    //  1. **Something runs the compiler.** `SessionRuntime.teardown` runs
    //     `FEXOfflineCompiler64.exe process-all` once the guest is dead, which
    //     merges the run's codemaps into the reference set and generates a cache
    //     per binary. Teardown is the right moment because the codemap is
    //     complete and nothing else is using the CPU. It is best-effort and
    //     cannot fail a teardown; see the method for what it does when it fails.
    //  2. **The cache is keyed on FEX's configuration.** Upstream's
    //     `CodeCacheConfigId` is `0 // TODO` (`ImageTracker.cpp`), so FEX itself
    //     would load a cache built under a different TSO or CPU setting without
    //     noticing. [fexCacheKey] closes that from this side — a configuration
    //     change moves the whole cache directory, so a stale cache becomes
    //     unreachable rather than silently wrong.
    environment["FEX_ENABLECODECACHINGWIP"] = "1"

    // The fixed channel set. The diagnostics stage at the end of this function
    // may replace it — with a string that still *starts* with this one, because
    // [composeWineDebug] appends and never substitutes.
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

    environment["DXVK_LOG_LEVEL"] = FIXED_DXVK_LOG_LEVEL

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
    environment["DXVK_LOG_PATH"] = FIXED_DXVK_LOG_PATH

    // **The D3D layer's own counters, permanently on, into a file this app
    // reads.**
    //
    // DXVK maintains every counter its HUD draws whether or not the HUD is on —
    // the HUD only reads them — and `patches/dxvk/0001` writes a snapshot of
    // them to this path once a second. That is the whole cost: when the variable
    // is unset the patch is one predictable branch per present, and when it is
    // set it is a truncate-and-write of about two hundred bytes a second, off
    // any per-frame path.
    //
    // Set unconditionally rather than behind a switch, because the thing it
    // replaces is a per-game `dxvk.conf` with `dxvk.hud=full` in it and a person
    // reading numbers off a screenshot. That worked exactly once and it was
    // worth it — a single frame of HUD showed `Draw calls: 1, Render passes: 1,
    // GPU: 2%` during Metro's intro and settled that the intro is a fullscreen
    // video blit bottlenecked on a single-threaded CPU decode under FEX, not a
    // graphics problem at all. A diagnosis that good should not need a
    // screenshot, a config file, or the foresight to have turned something on
    // before the run that went wrong.
    //
    // A program that uses no Direct3D never loads DXVK and so never writes this,
    // which is not a failure and is reported as such: see the `d3d` row in
    // [app.vessel.data.MetricSampler]'s sources.
    environment["VESSEL_GFX_STATS"] = gfxStatsFile(paths.tmp).absolutePath

    environment["VKD3D_DEBUG"] = FIXED_VKD3D_DEBUG
    environment["VKD3D_SHADER_DEBUG"] = FIXED_VKD3D_SHADER_DEBUG

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

    // **`display.fpsLimit`, applied to the renderer and not only to the screen.**
    //
    // The compositor has always paced itself to this number — `PacedXServerView`
    // drops a `requestRender` that arrives too soon — and that is a cap on the
    // blit, which is the last thing in the chain and the cheapest. The guest was
    // left to render as fast as it could.
    //
    // Measured, Metro at a 24 fps cap, one second of the session trace:
    //
    //     fps 24.0   d3dFps 116.5   gpu 84%   draws/frame 759
    //
    // Twenty-four frames on the screen, a hundred and sixteen rendered. Four out
    // of every five complete frames — geometry, shading, resolve, ~760 draw calls
    // each — were computed at full GPU cost and then discarded, because nothing
    // downstream of DXVK can un-spend work that has already been submitted. That
    // is the whole of "I capped it to 24 and the phone is still hot".
    //
    // So the limit is set on the two D3D layers as well. Both limiters are real
    // and already in the vendored source: DXVK reads `DXVK_FRAME_RATE` in
    // `util_fps_limiter.cpp` and vkd3d reads `VKD3D_FRAME_RATE` when it
    // initialises a swapchain, and each sleeps in Present rather than dropping —
    // so the frames that stop being drawn stop being *rendered*, which is the
    // point.
    //
    // Unset for an unlimited container rather than written as 0 or -1: DXVK
    // treats a value it cannot parse as no limit anyway, and an absent variable
    // is the honest way to say "no cap" to two projects with different opinions
    // about which sentinel means that.
    //
    // The compositor's own pacing stays exactly as it was. It is not redundant —
    // it is the only cap that applies to an OpenGL or GDI title, which is the
    // objection that used to argue against setting these at all, and it costs
    // nothing to keep as the backstop for everything the D3D variables cannot
    // reach.
    fpsLimit?.takeIf { it > 0 }?.let { limit ->
        environment["DXVK_FRAME_RATE"] = limit.toString()
        environment["VKD3D_FRAME_RATE"] = limit.toString()
    }

    // **What the guest is told this phone has**, from the Hardware group. Each
    // of the three is written to the variable the other layers *derive from*
    // rather than to each layer in turn — see [HardwareLimits] for why that
    // distinction is the whole point of the group.
    //
    // Nothing is written for a container that has not touched them, so the
    // golden environment is unaffected.
    hardware?.cpuTopology?.let { environment["WINE_CPU_TOPOLOGY"] = it }
    hardware?.ramBiasMb?.let { environment["WINE_RAM_REPORTING_BIAS"] = it.toString() }

    // **A driconf option set as a plain variable, and the first attempt at this
    // was wrong in a way worth recording.**
    //
    // It first wrote a `drirc` XML into the container and named it with
    // `DRIRC_CONFIGDIR`, which is how Mesa's documentation says to do this. On
    // the device the driver kept reporting 11.16 GiB of a 14.88 GiB phone —
    // exactly `os_gpu_heap_size_calculate`'s 0.75 heuristic for the
    // `percent == 0` case (`os_misc.h:170`), which is to say the option never
    // arrived. The reason is ours: `build/turnip.sh` builds the ICD with
    // `-Dexpat=disabled`, because the only thing in Mesa that wants expat is
    // driconf and shipping libexpat to the device to carry a database of other
    // people's GPU workarounds was not a trade worth making. That switch sets
    // `WITH_XMLCONFIG=0`, which compiles the whole `DRIRC_CONFIGDIR` path out.
    //
    // `driParseOptionInfo` overrides any option from a variable of the same
    // name (`xmlconfig.c:424`), and that code is *not* inside the expat guard —
    // the `#if WITH_XMLCONFIG` at `:40` closes at `:46` around the include
    // alone. `os_get_option` is `getenv` first (`os_misc.c:228`). So the option
    // name is the variable name, lower case, and it works in the build we ship.
    hardware?.heapMemoryPercent?.let { environment["heap_memory_percent"] = it.toString() }

    // The shader caches, all three, pointed at the container.
    //
    // Mesa's is the one that matters and the one most easily missed: it disables
    // itself when it detects a translation layer, so under Wine the default is
    // *off*, and every pipeline is recompiled on every launch. On a phone that
    // is the largest avoidable cost in the whole stack.
    environment["MESA_SHADER_CACHE_DISABLE"] = "false"
    environment["MESA_SHADER_CACHE_DIR"] = File(paths.caches, "mesa").absolutePath
    // `DXVK_STATE_CACHE_PATH` used to be set here and is deliberately gone.
    // DXVK 2.x removed the on-disk state cache -- graphics pipeline libraries
    // replaced it -- and nothing in the vendored 2.7.1 source reads the name or
    // implements the cache, so it set a variable no one would ever read and
    // created an empty `caches/dxvk` beside two directories that fill up.
    //
    // Removed rather than left harmless because that empty directory was read
    // as proof the unix-path scheme worked, and it sent the vkd3d disk-cache
    // hunt looking for a difference between two paths when the truth was that
    // only one of them ever opened a file. A cache that cannot fail looks
    // exactly like a cache that works.
    //
    // A DXVK title is cached by `MESA_SHADER_CACHE_DIR` above, which is 7.5 MB
    // after one session. If DXVK restores its own cache, add it back then.
    // Not a unix path, unlike its two neighbours above: see
    // VKD3D_CACHE_DOS_PATH for why that fails on every launch here.
    environment["VKD3D_SHADER_CACHE_PATH"] = VKD3D_CACHE_DOS_PATH

    // **Resident Evil Requiem faults inside the driver at vkCreateSwapchainKHR
    // unless these two are off.** Metro Redux reaches the same call with the same
    // surface, the same capabilities, the same extent and the same format, and
    // gets a swapchain; the difference is the pNext chain, which is what the
    // create_info dump (patches/wine/0028) was written to show. vkd3d-proton
    // attaches present-id and present-wait structures there and DXVK attaches
    // nothing, so the extended path is the one Android's WSI cannot survive.
    //
    // Version 2 names, not version 1: vkd3d uses `VK_KHR_present_id2` and
    // `VK_KHR_present_wait2`, and a first attempt that named the v1 extensions
    // disabled nothing while looking like it had. Whatever replaces this must be
    // checked against the guest's own extension list, not against memory.
    //
    // **Two, not four, and that is measured.** This began as four names set by
    // hand on one container, `swapchain_maintenance1` included on suspicion.
    // With only the pair below disabled Requiem logs `Creating swapchain
    // (1920 x 1080), BufferCount = 3` and then `Got 3 swapchain images` inside
    // the present task, so maintenance1 is innocent and stays enabled.
    //
    // The value was confirmed to arrive by reading `/proc/<pid>/environ` of the
    // live `re9.exe`, not by reading this file. Setting a variable the guest
    // never receives is the specific way this question was answered wrongly
    // once already, and it looks exactly like a negative result.
    //
    // Losing them costs nothing here. Both exist to let an application pace
    // itself against real present completion, and Vessel composites through the
    // Android surface anyway, so the timing they would report is not the timing
    // the user sees.
    // **The version 1 pair is here for the same reason, and it closes an
    // unbounded wait.** Disabling only v2 left `chain->present.wait` true,
    // because the v1 feature bit alone satisfies it (`swapchain.c:1497`), and
    // the swapchain's wait thread then calls `vkWaitForPresentKHR` with a
    // `UINT64_MAX` timeout (`swapchain.c:3699`). Only a WSI backend can retire
    // a present id, and Mesa has no Android backend in `src/vulkan/wsi` at all,
    // so that is an uninterruptible wait on a path nothing here implements.
    // Disabling v1 costs exactly what disabling v2 costs, for the reason above:
    // Vessel composites through the Android surface, so present timing does not
    // describe the timing a user sees either way.
    //
    // **It was not what hung Requiem, and this is recorded so it is not
    // re-tried as a fix.** With both pairs disabled -- `has_extension` logs all
    // four names, so this does reach the guest -- Requiem hung in the same
    // place, and that run is what finally named the cause: the lock-order
    // inversion in `docs/TODO.md`. One trap for whoever tests this again:
    // `dxgi_vk_swap_chain_init_sync_objects` prints `Ensure maximum latency of
    // N frames with KHR_present_wait` *unconditionally*, so that line is not
    // evidence that present wait is in use. The `has_extension` lines are.
    environment["VKD3D_DISABLE_EXTENSIONS"] =
        "VK_KHR_present_id,VK_KHR_present_wait,VK_KHR_present_id2,VK_KHR_present_wait2"

    environment["TU_DEBUG"] = tuDebugFlags(profile, manifest).joinToString(",")

    // **The two Turnip paths, always set, because both are inert until asked
    // for.** An empty `TU_DEBUG_FILE` costs one `stat` per frame boundary at
    // most, and `MESA_GPU_TRACEFILE` is opened only when `MESA_GPU_TRACES` names
    // a format. Setting them unconditionally means a measurement never fails
    // because a second setting was forgotten — which is the failure this project
    // has already had twice, with the shader caches and with `TU_DEBUG`
    // needing `MESA_LOG`.
    //
    // Under `tmp` rather than `logs`: neither is a session log, both are
    // instrument output, and `tmp` is already where `VESSEL_GFX_STATS` writes.
    environment["TU_DEBUG_FILE"] = File(paths.tmp, TU_DEBUG_FILE_NAME).absolutePath
    environment["MESA_GPU_TRACEFILE"] = File(paths.tmp, GPU_TRACE_FILE_NAME).absolutePath

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

    // **Stage three: diagnostics, and it is the only thing allowed past the
    // reserved set.** See [DIAGNOSTIC_SESSION_ENV] for why this is a third stage
    // rather than six variables taken out of the reserved one.
    //
    // The gate is a filter and not an assertion because a key outside the set
    // would be a fault in this build rather than in a document — but it is
    // checked anyway, so that adding a control and forgetting to widen the set
    // fails as "the switch does nothing" rather than as a variable nobody
    // intended reaching a session. `diagnosticEnvironment` is tested to produce
    // nothing outside the set for any record.
    //
    // Note this can only *rewrite* keys the fixed block above already set, never
    // append new ones in a new position: `LinkedHashMap` keeps a key's original
    // insertion point on reassignment, so the environment's order is the same
    // whether or not anything here is switched on.
    for ((key, value) in diagnosticEnvironment(profile.diagnostics, tuDebugFlags(profile, manifest))) {
        if (key !in DIAGNOSTIC_SESSION_ENV) continue
        environment[key] = value
    }

    // **Stage four: the container's own environment table, last.**
    //
    // Last because it is the escape hatch, and an escape hatch that could be
    // overwritten by an earlier stage would not be one — the case it exists for
    // is exactly "the value this build chose is wrong for my machine". It can
    // therefore rewrite anything the three stages above set, *except* the
    // reserved names, which `environmentOverrides` has already dropped.
    //
    // Reserved is a deny-list here and an allow-list for `diagnosticEnvironment`
    // above, and the asymmetry is deliberate. That stage backs a curated screen
    // where every row is a thing Vessel understands, so it can enumerate them.
    // This one is for variables nobody has anticipated, so it can only enumerate
    // what must never be reached: the paths this app writes and reads, and the
    // plumbing a session needs to start at all.
    //
    // Same `LinkedHashMap` property as the diagnostics stage: rewriting a key
    // keeps its original position, so the environment's order does not change
    // depending on what a container overrode.
    for ((key, value) in profile.diagnostics.environmentOverrides()) {
        environment[key] = value
    }

    // The configuration digest used to be appended to `FEX_APP_CACHE_LOCATION`
    // here, last, so that it was taken over the environment as it actually ended
    // up. It still is taken over exactly that environment — [fexCacheHost] is
    // called by the session with this map once it is complete — but the value
    // itself is now a fixed DOS path, because FEX cannot read a cache through a
    // unix one. See the comment at the `FEX_APP_CACHE_LOCATION` assignment.

    return environment
}

/**
 * Every manifest param that declares an `env`, resolved against this container.
 *
 * A param with no `env` produces nothing. `display.resolution` is consumed by the
 * session surface. `display.fpsLimit` reaches `DXVK_FRAME_RATE` and
 * `VKD3D_FRAME_RATE` as well as the compositor, but it gets there through
 * [sessionEnvironment]'s own code and not through an `env` declaration — the same
 * shape as [dllOverrides], and for the same reason: one setting composed into
 * several variables is a thing code does, not a thing a document can express.
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
internal fun dllOverrides(profile: ContainerProfile, manifest: ParamManifest?): String =
    appendedTo(WINEDLLOVERRIDES_ENV, D3D_DLL_OVERRIDES.joinToString(",") + "=n", profile, manifest)

/**
 * [variable]'s value: what Vessel requires, then every manifest term for it.
 *
 * **This is the general form of what used to be a hardcoded key.** One param
 * declaring `appendTo` is a data change; the previous shape — a `when`-free
 * function that nonetheless looked up one key by name — was a special case with
 * a note admitting it would not survive a second one. It did not: the OpenGL
 * driver toggle is a second thing that contributes to `WINEDLLOVERRIDES`.
 *
 * [base] goes first because it is not the user's to lose, and appending is the
 * point: Wine reads the list left to right and a later term wins, so a container
 * can override a default for one program without being able to delete it for
 * everything. Manifest order decides the rest, which makes a named control lose
 * to the free-text field below it — correct, because typing an override is the
 * more specific instruction.
 *
 * Empty terms are dropped rather than joined, so an untouched container produces
 * exactly [base] and the golden environment is unchanged.
 */
internal fun appendedTo(
    variable: String,
    base: String,
    profile: ContainerProfile,
    manifest: ParamManifest?,
): String {
    val terms = manifest?.allParams.orEmpty()
        .filter { it.appendTo == variable }
        .mapNotNull { spec ->
            when (val value = profile.params[spec.key] ?: spec.defaultValue()) {
                is ParamValue.Flag -> spec.appendValue?.takeIf { value.value }
                is ParamValue.Text -> value.value
                else -> null
            }
        }
        .map { it.trim().trim(';') }
        .filter { it.isNotEmpty() }

    val separator = manifest?.allParams.orEmpty()
        .firstOrNull { it.appendTo == variable }
        ?.appendSeparator
        ?: ";"

    return (listOf(base) + terms).filter { it.isNotEmpty() }.joinToString(separator)
}

/*
 * The key this file used to look up by name is gone, and the note that stood
 * here is worth keeping as a record of why.
 *
 * It read: "Everywhere else the manifest drives the environment through `env`,
 * and a `when (key)` is exactly what that design forbids. This is the exception
 * because the value is *composed* with a built-in list rather than copied, and
 * there is no way to express 'append to this variable' in the manifest schema.
 * If a second one of these ever appears, the schema needs the feature, not
 * another constant here."
 *
 * A second one appeared — the OpenGL driver toggle — so the schema got the
 * feature: `appendTo` on ParamSpec, composed by [appendedTo]. Both params are
 * now data, and this file names neither.
 */

/*
 * Do not add NVIDIA Streamline (`sl.interposer` and friends) back here. It was
 * disabled as a default, it was measured, and it was wrong.
 *
 * The reasoning was: Wine printed
 * `RtlpWaitForCriticalSection section 00000000388A7E28 wait timed out in
 * thread 0184, blocked by 021c`, and 0x184/0x21c are 388/540 in decimal — the
 * same two thread ids as the `[streamline][error][tid:388]` /
 * `[tid:540]` lines directly above, which complain that Reflex and PCL have no
 * context because there is no NVIDIA GPU. So Streamline was refused for every
 * title with an empty override, on the grounds that a title would then take the
 * path it takes on any machine without Streamline.
 *
 * That last step is the error, and it is worth naming precisely: on a
 * non-NVIDIA PC `sl.interposer.dll` **is present and loads fine** — it just
 * reports no NVIDIA features. An empty override makes `LoadLibrary` *fail*,
 * which is a different situation, and Resident Evil Requiem does not handle it.
 * With the override in place the log shows
 * `Failed to load module L"sl.interposer.dll"; status=c0000135` twice, followed
 * by the signature-verification cluster and `CrashReport.exe`. The deadlock had
 * been traded for a crash.
 *
 * With Streamline allowed to load again the title gets **further than it ever
 * had** — past `dstorage`, `steam_api64` and `voices38` — and the trace shows
 * no `sl.*` load and no critical-section timeout at all. The thread-id
 * correlation was real; the conclusion drawn from it was not.
 *
 * Wine's default for a DLL it has no builtin for is already native-first, so
 * removing the entry restores stock behaviour; nothing replaces it. A user who
 * wants Streamline gone for one title can still say so through
 * `wine.dllOverrides`, which is appended after this and therefore wins.
 */

internal fun manifestEnvironment(
    profile: ContainerProfile,
    manifest: ParamManifest?,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (spec in manifest?.allParams.orEmpty()) {
        val name = spec.env ?: continue
        val value = profile.params[spec.key] ?: spec.defaultValue() ?: continue
        val rendered = value.asEnvValue()
        // **An empty value means "not set", and that is the only way a manifest
        // param can express it.**
        //
        // Several of the graphics experiments are off when their variable is
        // absent and *on* when it is present with any content — `TU_AUTOTUNE_FLAGS`
        // and `MESA_GPU_TRACES` both parse a flag list, and `DXVK_CONFIG` is
        // matched as a config line. Writing them as empty strings would be a
        // container declaring an experiment it had not chosen, and the manifest
        // has no other vocabulary for "leave this alone": every param must
        // produce a value, so the empty one has to be the absence.
        //
        // `wine.dllOverrides` is unaffected — it declares no `env` and is composed
        // by `dllOverrides` instead — and nothing else in the manifest defaults to
        // an empty string.
        if (rendered.isEmpty()) continue
        out[name] = rendered
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

/**
 * What the guest is told this phone has.
 *
 * **One declared number per thing, and every layer configured from it.** The
 * failure this exists to prevent is the one `display.fpsLimit` documents above:
 * two readers of one setting, disagreeing. A game that is told it has four cores
 * by Wine while DXVK sizes its compiler pool from eight, or that reads 15.6 GB
 * of video memory from the driver and 4 GB from DXGI, is being lied to
 * inconsistently — which is worse than not being limited at all, because its own
 * budgeting arithmetic stops adding up.
 *
 * So each field below is resolved once, here, and the composer writes it to
 * whichever variable is *upstream* of the rest:
 *
 * | Field | Written to | Reaches |
 * |---|---|---|
 * | [cores] | `WINE_CPU_TOPOLOGY` | what the guest enumerates as CPUs |
 * | [ramMb] | `WINE_RAM_REPORTING_BIAS` | `GlobalMemoryStatusEx`, `SystemBasicInformation` |
 * | [vramMb] | `heap_memory_percent` | Turnip's heap, and therefore DXVK, vkd3d and Zink |
 *
 * The video memory one is the reason this is worth doing carefully. Capping it
 * in DXVK would leave OpenGL titles and the Vulkan budget itself reporting the
 * old number; capping it in the driver caps the quantity all three of them are
 * *derived from* — `dxgi_adapter.cpp` sums the `DEVICE_LOCAL` heaps, vkd3d
 * reports through DXVK's DXGI, and Zink reads the same heaps.
 *
 * Every field is nullable or empty for "report what the device really has", and
 * a container that has touched none of them adds no variables at all — which is
 * what keeps the golden environment golden.
 */
data class HardwareLimits(
    /**
     * Host CPU ids the guest may see, in ascending order. Empty means all of
     * them, and so does a selection that covers every core the device has.
     */
    val cores: List<Int> = emptyList(),
    /** Megabytes of system memory to report. Null reports the device's own. */
    val ramMb: Int? = null,
    /** Megabytes of video memory to report. Null reports the shared pool. */
    val vramMb: Int? = null,
    /** What the device really has, for the two settings expressed as a delta. */
    val deviceRamMb: Int = 0,
    val deviceCores: Int = 0,
) {
    /**
     * `WINE_CPU_TOPOLOGY`, or null when there is nothing to say.
     *
     * The explicit `N:list` form rather than a bare count, because which cores
     * is the point on a phone: these are not eight equal cores, and "give the
     * guest four" is a different instruction from "give the guest these four".
     */
    val cpuTopology: String?
        get() {
            val chosen = cores.distinct().sorted()
            if (chosen.isEmpty() || (deviceCores > 0 && chosen.size >= deviceCores)) return null
            return "${chosen.size}:${chosen.joinToString(",")}"
        }

    /**
     * `WINE_RAM_REPORTING_BIAS`, in megabytes, or null.
     *
     * Wine subtracts this from what it reports rather than clamping to a total
     * (`loader.c:2321`, `virtual.c:4001`), so the variable is the *difference* —
     * and a bias larger than the machine would report a negative amount of
     * memory, hence the floor.
     */
    val ramBiasMb: Int?
        get() {
            val want = ramMb ?: return null
            if (deviceRamMb <= 0 || want <= 0 || want >= deviceRamMb) return null
            return deviceRamMb - want
        }

    /**
     * driconf `heap_memory_percent`, or null.
     *
     * A fraction of total system memory rather than an absolute size, because
     * that is the only shape the option has (`driconf.h:473`, range 0.0-1.0,
     * "0 = driver default"). Rounded to three places: the option is a float and
     * the difference between 0.256 and 0.2564 is 6 MB on this device, which is
     * far below anything a game's budgeting will notice.
     */
    val heapMemoryPercent: Double?
        get() {
            val want = vramMb ?: return null
            if (deviceRamMb <= 0 || want <= 0 || want >= deviceRamMb) return null
            return ((want.toDouble() / deviceRamMb) * 1000.0).toInt() / 1000.0
        }

    /** True when nothing here differs from the machine underneath. */
    val isEmpty: Boolean
        get() = cpuTopology == null && ramBiasMb == null && heapMemoryPercent == null
}

/** The manifest keys this resolver reads. Grouped, so the set is greppable. */
private const val CORES_KEY = "hardware.cores"
private const val RAM_KEY = "hardware.ram"
private const val VRAM_KEY = "hardware.vram"

/** `auto` is the manifest's word for "report what the device really has". */
private const val HARDWARE_AUTO = "auto"

/**
 * Read the three Hardware params, in gigabytes, into [HardwareLimits].
 *
 * Resolved by the caller and passed to [sessionEnvironment] rather than read
 * there, for the reason `fpsLimit` gives: the session composes the environment
 * *and* has to know these numbers itself, and two readers of one param is how
 * they end up disagreeing.
 *
 * @param deviceRamMb total physical memory, which both memory settings are a
 *   delta from. Zero disables them rather than guessing.
 * @param deviceCores what the device really has, so "all of them ticked" can be
 *   recognised as "say nothing" rather than written out as an identity mapping.
 */
fun hardwareLimits(
    profile: ContainerProfile,
    manifest: ParamManifest?,
    deviceRamMb: Int,
    deviceCores: Int,
): HardwareLimits {
    fun value(key: String): ParamValue? =
        manifest?.allParams.orEmpty().firstOrNull { it.key == key }
            ?.let { spec -> profile.params[spec.key] ?: spec.defaultValue() }

    // A gigabyte count, or null for `auto` and for anything that will not parse.
    // Unparseable is deliberately the same as auto: the field is free text so a
    // user can type a size the presets do not offer, and the honest failure for
    // "4gb" is to report the real machine rather than to invent a number.
    fun gigabytes(key: String): Int? = (value(key) as? ParamValue.Text)
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(HARDWARE_AUTO, ignoreCase = true) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.times(1024)

    val cores = (value(CORES_KEY) as? ParamValue.Choices)
        ?.values
        .orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .filter { it >= 0 && (deviceCores <= 0 || it < deviceCores) }

    return HardwareLimits(
        cores = cores,
        ramMb = gigabytes(RAM_KEY),
        vramMb = gigabytes(VRAM_KEY),
        deviceRamMb = deviceRamMb,
        deviceCores = deviceCores,
    )
}

/**
 * Total physical memory in megabytes, from `/proc/meminfo`.
 *
 * Read here rather than through `ActivityManager.MemoryInfo.totalMem` so this
 * file keeps no Android dependency and stays testable, and because the two
 * agree: `totalMem` is the same `MemTotal`.
 *
 * Returns 0 when it cannot be read, which disables both memory settings rather
 * than sizing them against a guess.
 */
fun deviceTotalRamMb(meminfo: File = File("/proc/meminfo")): Int = runCatching {
    meminfo.useLines { lines ->
        lines.firstOrNull { it.startsWith("MemTotal:") }
            ?.split(Regex("""\s+"""))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { (it / 1024L).toInt() }
            ?: 0
    }
}.getOrDefault(0)

