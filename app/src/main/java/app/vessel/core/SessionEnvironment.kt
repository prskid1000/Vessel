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
const val WINEDEBUG_CHANNELS: String = "-all,err+all,warn+module,+winediag,+loaddll,warn+debugstr"

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
/*
 * **Asked a second time, for audio, and answered no again. 2026-08-16.**
 *
 * The first run (below) was about a deadlock. This one was about the buzz:
 * Requiem buzzes and Metro does not, and the delivery path had already been
 * eliminated — the two `wineoss` traces are statistically identical (burst 868,
 * capacity 1736, client buffer 2604; `held` mean 1870 vs 1909; `in_oss` 1106 vs
 * 1144; 41% vs 40% partial writes; **zero AAudio xruns in either**). Same
 * driver, same numbers, one buzzes. So the samples Requiem hands us are already
 * wrong, and Wwise's DSP is exactly the SIMD-heavy multi-threaded code a relaxed
 * 128-bit store would corrupt.
 *
 * All three closed, verified in the guest's own `/proc/<pid>/environ` rather
 * than assumed: `FEX_VECTORTSOENABLED=1`, `FEX_MEMCPYSETTSOENABLED=1`,
 * `FEX_VOLATILEMETADATA=0`.
 *
 * **It did not fix the buzz. It stopped the game reaching a frame at all** —
 * `vkd3d_wait_for_gpu_timeline_semaphore ... vr -4` and
 * `d3d12_command_queue_execute: Failed to submit queue(s), vr -4`, with the CPU
 * at 1.1% and the GPU at 0.2%, i.e. everything idle. `tu_restrict_subgroup_size_64`
 * was confirmed still applied and acknowledged by Turnip nine times in the same
 * session, so this was not the subgroup hang returning. Turning the constant
 * back off restored a running game.
 *
 * So ordering is eliminated for the audio question too, and the mechanism is
 * somewhere else: FEX's SIMD codegen itself, or the game's mixer starved of CPU.
 * The next instrument is capturing the PCM to see whether the buzz is noise,
 * repetition or clipping — three different bugs that sound alike.
 */
/*
 * **Asked a third time, for VS Code, and this time the thread is awake.
 * 2026-08-20.**
 *
 * Both runs above eliminated ordering for a *sleeping* stall, and both said so
 * on the same evidence: every thread asleep, no CPU burning anywhere. That is
 * only half the shape a lost publication can take. The other half is a spin —
 * a thread that polls a location the publisher already wrote and never sees the
 * new value — and it looks nothing like the first: it burns a core flat out.
 *
 * VS Code is that half, measured rather than assumed. `CrRendererMain` sits at
 * 103% of one core with `/proc/<pid>/syscall` reading `running` on every sample
 * and `VmRSS` byte-identical at 312528 kB across twelve seconds: no syscalls, no
 * allocation, no progress. Four threads where a healthy renderer has fifteen, so
 * it never finished startup. Chromium's own log puts the stall precisely — the
 * last thing it says is `FileURLLoader::Start: .../workbench/workbench.html`,
 * then thirty seconds of silence and Electron's `detected unresponsive`. The GPU
 * process is at exactly 0%: idle, waiting for work that never comes.
 *
 * So the two "no"s above do not cover this. On for one run to answer it, and
 * back to `false` the moment it does — the "HUGE" vector-TSO cost the comment
 * below describes has still never been paid for by a measurement.
 *
 * **Run, and it answered no. 2026-08-20.** All three closed and verified in the
 * renderer's own `/proc/<pid>/environ` rather than assumed —
 * `FEX_VECTORTSOENABLED=1`, `FEX_MEMCPYSETTSOENABLED=1`,
 * `FEX_VOLATILEMETADATA=0` — and the spin did not move: 508 ticks over five
 * seconds, 102% of one core, against 103% on the same measurement before the
 * change. Not a shift, the same number.
 *
 * So ordering is eliminated for both shapes now, asleep and awake, and this is
 * off again. What is left for VS Code is what the spin itself says: no syscalls,
 * no allocation, one thread, stalled the instant `workbench.html` starts
 * loading. That points at FEX's translation of whatever V8 runs first, not at
 * the memory model, and the next instrument is a sample of where the JIT is
 * spinning rather than another env-var run.
 */
const val STRICT_MEMORY_ORDERING: Boolean = false

/**
 * Whether Adreno's subgroups are pinned to 64 lanes instead of a possible 128.
 *
 * **On, because at 128 Resident Evil Requiem hangs the GPU — and a constant
 * rather than a bare assignment because the cost has never been measured.**
 *
 * What it does: clears Turnip's `expose_double_threadsize`
 * (`tu_device.cc:1796`), so `maxSubgroupSize` becomes `threadsize_base` rather
 * than twice it.
 *
 * **What it fixes, watched on the device.** Requiem hung on a `256x1x1` compute
 * dispatch; vkd3d breadcrumbs named the command and the shader dump showed a
 * wave-aggregated allocation loop — ballot for the active count, an exclusive
 * scan for this lane's index, one lane doing the `atomicAdd`, a broadcast to
 * share the base back, loop while the index is in range. With this on and **no
 * shader overrides at all**, the title survived 600 s with `vr -4` at zero and
 * five swapchains created. With it off it never reached a loading screen.
 *
 * **The diagnosis in Mesa's own words may not be ours.** The option is described
 * as working around "games assuming desktop GPU 32/64 sizes", and Turnip ships
 * it on for No Man's Sky. But the shader here **hardcodes no wave width** — it
 * is written against ballots and scans, which are size-agnostic by contract. So
 * either the title relies on a reconvergence guarantee Vulkan does not give, or
 * Turnip's 128-lane path is itself wrong; a ballot is a `uvec4`, exactly 128
 * bits, which is where an off-by-one would live. **That is unresolved**, and it
 * matters: if it is a driver defect then the right fix is in Turnip and this
 * restriction is a tax paid for nothing.
 *
 * **The cost is unmeasured and that is the point of this constant.** Half the
 * lanes per wave sounds expensive and may be nothing — Turnip already chooses 64
 * for many shaders on its own. It cannot be measured on Requiem, which does not
 * run with it off. It *can* be measured on a title that runs either way: flip
 * this, rebuild, and compare the frame counter on the same scene. Until someone
 * does that, nobody should claim this is free or that it is expensive.
 *
 * *Done when:* either a measurement says the cost is acceptable, or Turnip's
 * 128-lane subgroup path is fixed and this goes back to false.
 */
/*
 * **Off since `patches/mesa/0007`, which fixes the cause instead of negating
 * it.** That patch clears `supports_double_threadsize` for `a8xx_base`, which is
 * the flag this option exists to negate (`tu_device.cc:1796`) — so with a driver
 * built from it, this is a no-op that would only ever hide whether the patch
 * worked. Kept rather than deleted because it is the fastest way to put a stock
 * driver back into the fixed state if 0007 ever has to be dropped.
 */
const val RESTRICT_SUBGROUP_SIZE_64: Boolean = false

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
 * settings is wrong in the same way here as anywhere else. `WINEFSYNC` stays
 * because the server and its clients have to agree about it -- a client that
 * thinks fsync is on while the server does not is the one failure mode
 * `dlls/ntdll/unix/fsync.c:477` warns about by name.
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
    "WINEFSYNC",
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
 * unproven at the time.
 *
 * **It is not unproven any more, and the note that lived here was three
 * corrections behind the tree.** The failure is `5cdb54a`, 2026-08-10 11:29.
 * Every cause it named was then found and fixed *the same afternoon*, and
 * nobody came back to this constant. In commit order:
 *
 *  - **11:29 `5cdb54a`** — this constant set false. Cause unknown.
 *  - **13:13 `186390f`** — *"implement XFIXES — the actual cause of the DRI3
 *    failure"*. Mesa creates an XFIXES region per swapchain image on the DRI3
 *    path, unguarded, with `has_xfixes` sitting beside it unconsulted
 *    (`x11_image_init`, `wsi_common_x11.c`). Against a server that does not
 *    advertise XFIXES, **libxcb tears the connection down client-side** with
 *    `XCB_CONN_CLOSED_EXT_NOTSUPPORTED` before sending anything — so no request
 *    arrives, no protocol error exists, and nothing on this side can see it.
 *    That is exactly the "connection broken, no X error" above, and the commit
 *    says so. The software path was unaffected because `x11_image_init` returns
 *    one line before the region is created, which is why `sw` passed and `dri3`
 *    did not through identical code. Probe: `conn_err=0 then conn_err=2` before,
 *    `conn_err=0 then conn_err=0` after.
 *  - **14:40 `b418feb`** — *"DRI3 present works"*, with the real blocker named
 *    (`patches/mesa/0006`: the DRM image backend was gated on
 *    `dep_libdrm.found()`, so the DRI3 half was compiled with a CPU image
 *    behind it) and **measured on this device**: mean 0.602 ms against
 *    2.143 ms, 300 frames at 1280x720, `dma_buf_fd=6, size=3686400`.
 *  - **16:22 `65bfea7`** — `FenceFromFD` implemented, backed by a real futex in
 *    `app/src/main/cpp/winlator/src/xshmfence.c`.
 *  - **16:54 `a9a4d89`** — the idle fence measured free (+0.010 ms, inside the
 *    spread of four control runs) *and counted*: the server served exactly 3
 *    fences with it on and 0 with it off, which is what makes the null result
 *    mean something rather than being Mesa quietly not asking.
 *
 * So the 2026-08-10 result does not merely "predate the `FenceFromFD` fix", as
 * `patches/mesa/README.md` puts it — it predates the diagnosis of its own
 * cause by 104 minutes. It is evidence about a server that no longer exists.
 *
 * **The old note was also wrong about the opcode numbers, twice.** It said
 * `GetSupportedModifiers` (4), `FenceFromFD` (5), `FDFromFence` (6). The
 * protocol's are `FenceFromFD` 4, `FDFromFence` 5, `GetSupportedModifiers` 6.
 * What the server answers today is version 1.0 with opcodes 0, 1, 2, 3, 4 and
 * 7 — QueryVersion, Open, PixmapFromBuffer, BufferFromPixmap, FenceFromFD and
 * PixmapFromBuffers. At 1.0 Mesa asks for no more: modifiers and
 * `BuffersFromPixmap` are 1.2 features it will not reach for, so `FDFromFence`
 * and `GetSupportedModifiers` being absent is not a gap at the version we
 * advertise.
 *
 * **The `pseudo-drm` question is answered too.** This note used to ask whether
 * Mesa's DRM-shaped WSI could work over KGSL, where `xcb_dri3_open` has no DRM
 * device to hand back. `b418feb` settles it: *"The DRI3 version was fine at 1.0,
 * and `kgsl_bo_export_dmabuf` was never reached"* — the fd from `Open` is not
 * what carries the buffer, `BufferFromPixmap` is, and Turnip imports that.
 *
 * **The join has now been run, and this is why the default moved.** Everything
 * proven before 2026-08-16 was `tools/gfx/x11present.c` — a native Vulkan client
 * making a swapchain against this X server in this app's process — and the guest
 * stack is not that. A session is Wine's `winex11` holding the X connection,
 * vkd3d/DXVK driving the swapchain, and Turnip reached through win32u's ICD path
 * under FEX. That combination ran on 2026-08-16 with the Diagnostics row at
 * `WSI_DRI3`, on Resident Evil Requiem, and it drew:
 *
 *     VESSEL-WSI image_init image_type=0 dma_buf_fd=348 num_planes=1
 *                modifier=0xffffffffffffff row_pitch=7936 size=8794112
 *
 * Six of those, three per swapchain across a 1920x1080 and a 1280x720
 * reallocation — Turnip importing the server's own dma-buf through the guest
 * stack. Zero device losses in that session. The prior flip that took a session
 * down was `5cdb54a`, which predates the diagnosis of its own cause by 104
 * minutes: `186390f`, *"implement XFIXES — the actual cause of the DRI3
 * failure"*, landed the same afternoon and nobody came back to this constant.
 *
 * **What the first run also found, and what had to be fixed before flipping.**
 * The session stuttered. `PresentExtension.presentPixmap` did its 8 MB
 * `copyArea` inside the window's `renderLock`, which the GL thread takes once per
 * composited frame and must hold to meet vblank — so the compositor blocked on
 * the present copy whenever it woke mid-copy, which with mouse-look is
 * continuously. That lock is now taken only when the content is not a
 * `GPUImage`, i.e. only when the compositor genuinely reads those pixels. See
 * that method.
 *
 * **And then it was measured, and it goes back off. 2026-08-16.** The
 * `Present copyArea` sampler had existed for weeks with nothing in this repo
 * recording its output; the first capture of it reads
 *
 *     Present copyArea x6720 mean=19114us max=385490us last=69539us 1280x720
 *
 * **19 ms per present**, for 3.7 MB — about 154 MB/s, which is uncached read
 * speed rather than the several GB/s a cached memcpy manages on this SoC.
 *
 * **That inference was wrong, and the correction is measured. 2026-08-16,
 * later the same day.** `tools/bench/heapbench.c`, run on the phone, allocates
 * from `/dev/dma_heap/system` — the heap Turnip actually opens — maps it, and
 * times a copy out against a `malloc`-to-`malloc` control in the same process:
 *
 *     control malloc->malloc  warm=45949 MB/s  cold=24944 MB/s
 *     heap system             warm=24359 MB/s  cold=25892 MB/s  ratio=1.038
 *                             syncStart=0.000 ms  syncEnd=0.000 ms
 *                             bracketed(start+copy+end) cold=0.142 ms
 *
 * **The mapping is cached** — a ratio of 1.038 against a `malloc` buffer leaves
 * no room for it to be anything else — and the whole operation this note blames,
 * sync in, copy 3.5 MB, sync out, costs **0.142 ms**. The measured present copy
 * is 19.1 ms, which is **135x** that. So the code-reading argument that was
 * called "an argument that lost to a number" was right after all, and the number
 * it lost to was being read as a bandwidth figure when it is not one. Neither
 * page protection nor memory bandwidth explains this copy, and neither does the
 * `DMA_BUF_IOCTL_SYNC` bracket that replaced them as the favourite.
 *
 * One limit on that, stated because it is the obvious next objection: the
 * benchmark's buffer has no importer attached, and
 * `qcom_sg_dma_buf_begin_cpu_access` walks every attachment. The real swapchain
 * buffer has KGSL attached to it, so `syncStart=0.000` is honest for an
 * unattached buffer and not yet proof for the attached one. `docs/BANDWIDTH.md`
 * §9 carries the rest; the `syncIn=/copy=/syncOut=` split now in the present
 * log is what closes it, and it costs one session.
 *
 * What the switch appeared to cost, from two sessions of Requiem at a 24 fps
 * cap, 47 samples against 44 — **read the caveat under the table before using
 * these numbers**:
 *
 *     | metric        | DRI3    | sw      |
 *     |---------------|---------|---------|
 *     | GPU mean      |  15.5%  |  57.3%  |
 *     | GPU peak      |    96%  |    96%  |
 *     | CPU mean      |  26.8%  |  19.7%  |
 *     | GPU temp mean | 45.7 C  | 48.3 C  |
 *
 * That was read as "the GPU does 3.7x less work and the CPU does 36% more,
 * because the GPU is idle waiting on a core memcpying an uncached buffer".
 *
 * **The GPU half of that reading is withdrawn.** The two paths do different
 * amounts of *GPU* work by construction: on the software path the server
 * uploads each frame with `glTexSubImage2D` (`Texture.updateFromDrawable`),
 * and on DRI3 the content is a `GPUImage` sampled straight from an
 * AHardwareBuffer and that upload does not happen at all. So the software arm's
 * 57.3% includes per-frame texture uploads DRI3 never pays, and a GPU
 * utilisation figure cannot separate useful work from overhead. The CPU column
 * stands and favours the software path; the GPU column proves nothing either
 * way. The buffer is not uncached, so that clause of the original reading is
 * false outright.
 *
 * **None of this retracts that DRI3 works.** It negotiates, six dma-bufs import
 * through the full guest stack, and no session lost a device. The X server bugs
 * found under it are real and stay fixed: the present copy no longer holds the
 * compositor's `renderLock`, and `awaitFence` no longer spins holding the
 * monitor its own releaser needs. What is not yet true is that it is *faster*.
 *
 * *Done when:* the copy is cheap. Uncached reads are latency-bound rather than
 * bandwidth-bound, so splitting the frame into row bands across a worker pool —
 * joined before the copy returns, so every ordering, lifetime and idle-notify
 * property is unchanged — should scale close to linearly on eight cores. Get
 * `mean` under about 3 ms and re-run the table above.
 *
 * **And the copy was split, so it goes back on. 2026-08-16.** That *done when*
 * has been built: `Drawable.copyArea` now runs through `copyPoolCopyRows`
 * (`app/src/main/cpp/winlator/src/copy_pool.c`), which divides the rectangle
 * into row bands across a persistent pthread pool — four participants by
 * default on this eight-core part, the calling thread taking one band itself
 * and joining the workers before it returns. Payloads under 256 KB skip the
 * pool entirely, so ordinary X `CopyArea` traffic is untouched.
 *
 * **What has changed is the cost of the copy, and nothing else on this page.**
 * Every measurement above still stands as taken. The 19.1 ms line is real, the
 * table of two Requiem sessions is real, and the reason the table looked the
 * way it did — a GPU idling at 15.5% and 45.7 C behind one core memcpying an
 * uncached buffer — is exactly the thing that has been attacked. What has *not*
 * happened is a re-measurement: **the post-split `mean` has not been captured on
 * a device, and neither has a new version of that table.** The argument for
 * flipping is the mechanism, not a number: 154 MB/s is uncached-read speed,
 * uncached reads stall on latency rather than saturating a bus, and stalls from
 * different cores overlap. That predicts close to linear scaling and it is the
 * same class of code-reading argument that lost once already on this page. It
 * is being trusted this time because the thing it predicts is directly
 * measurable by an instrument already in the tree: the `Present copyArea`
 * sampler in `PresentExtension.presentToContent` prints `mean` every 120
 * presents, and one session says whether this was right.
 *
 * *Done when:* that sampler reads under about 3 ms and the table above has a
 * third column. Until then this default is a considered bet, not a result.
 *
 * `MESA_VK_WSI_DEBUG` is in [DIAGNOSTIC_SESSION_ENV], so the way back is one row
 * and no rebuild: Diagnostics, `MESA_VK_WSI_DEBUG`, *Copy each frame (sw)*. That
 * row is unchanged by this flip — it overrides in both directions now, which is
 * what makes moving the default a cheap thing to be wrong about. The failure
 * mode it guards is total and instant — a black window or no swapchain, never a
 * slow one — so it is unmistakable and the way back is unambiguous.
 */
const val ZERO_COPY_PRESENT: Boolean = true

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
 * `WINEFSYNC` because the synchronisation mode is not the container's to pick:
 * `server/inproc_sync.c:50-65` chooses ntsync, then fsync, then a server-side
 * fallback, and only the middle one is reachable on this device.
 *
 * `VKD3D_LOG_FILE` is different: it is listed to guarantee its **absence**.
 * `vkd3d_dbg_init_once` is an if/else — set the variable and it opens the file
 * *instead of* resolving `__wine_dbg_output`, so it moves vkd3d's output off the
 * pipe the session log reads rather than copying it.
 */
val RESERVED_SESSION_ENV: Set<String> = setOf(
    "WINEPREFIX",
    "WINEFSYNC",
    "WINEDEBUG",
    WINEDLLOVERRIDES_ENV,
    "DISPLAY",
    "DXVK_LOG_LEVEL",
    "DXVK_LOG_PATH",
    // Reserved the day the session started sending a value of its own. It used
    // to be an ordinary free-text environment row, which was fine while Vessel
    // set nothing: a row there *replaces* the variable, so the moment
    // [FIXED_DXVK_MAX_SHARED_MEMORY] went in, anyone who typed a d3d11 option
    // would have handed the doubled memory report back to the guest without a
    // word about it. `VKD3D_CONFIG` above cost a device run to learn exactly
    // this. Reachable now through the `dxvkconfig` family, which composes.
    "DXVK_CONFIG",
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
    // The GPU command-stream capture, and where it lands. Reserved for the
    // reason MESA_GPU_TRACEFILE above it is: a container document that could
    // choose this path could have the driver writing outside the container.
    // FD_RD_DUMP is a declared row; FD_RD_DUMP_PATH is derived beside it and is
    // reachable from no document at all.
    "FD_RD_DUMP",
    "FD_RD_DUMP_PATH",
    // Where vkd3d writes every compiled shader. Reserved for the reason its
    // neighbours are: a document that could choose this path could have the
    // guest writing outside the container. The row is a switch; the path is
    // derived below.
    "VKD3D_SHADER_DUMP_PATH",
    // Where a replaced shader is read from. Reserved for a stronger reason than
    // its neighbour: a document that could choose this path could make the guest
    // run shader code from anywhere on the device.
    "VKD3D_SHADER_OVERRIDE",
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
    // Where vkd3d writes its submission timeline, and reserved for exactly the
    // reason its neighbour above is: the variable *is* the destination. It is
    // also in [DIAGNOSTIC_SESSION_ENV], which is where the switch that turns it
    // on lives, and that set's own comment says how a switch becomes a path.
    "VKD3D_QUEUE_PROFILE",
    // Where the audio driver tees what it hands the device — `patches/wine/0047`.
    // Reserved for the same reason as its neighbour and with one addition: this
    // one is *written to* rather than read, at ten writes a second for as long
    // as the session lasts, so a container document that could point it at
    // `/sdcard/…` would have Wine filling a directory outside the container with
    // a file nothing prunes. The value the Diagnostics switch sends is `1`, and
    // the driver resolves that to `$TMPDIR/vessel-audio.pcm` — inside the
    // container's own scratch, cleared with it.
    "VESSEL_AUDIO_DUMP",
    // Reserved rather than offered, because a caption is not a preference here:
    // Vessel's taskbar is the only window control on a phone and the shell draws
    // the move/resize borders itself. A container that turned this off would get
    // a 41px strip nothing paints and a client that overflows its own parent.
    "VESSEL_BORDERLESS",
    "VESSEL_MANAGED",
    // Reserved because it is an observation, not a choice. Android's DNS servers
    // live in netd, and `dlls/dnsapi/android.c` documents why native code cannot
    // read them: `net.dns1..4` was removed in Android 8, and the only remaining
    // source is `ConnectivityManager`/`LinkProperties` on the Java side. This is
    // how that answer crosses into the guest, so its value is whatever the phone
    // is actually resolving through right now. A container that could type its
    // own would be naming servers the device is not using, and the failure — a
    // name that resolves on the phone and not in the guest — would look like a
    // network fault rather than a setting.
    "VESSEL_DNS_SERVERS",
    // Not a driconf file and not a setting: it is a correctness/perf pairing
    // with FEX's store-release behaviour, and FEX would try to set it itself if
    // it could. See where it is assigned.
    "tu_override_uncached_as_cache_coherent",
    // Same reason as its neighbour, and a sharper one: this pins Adreno's
    // subgroup width to 64 because shaders that assume it hang the GPU
    // otherwise. A container that could turn it off would get a freeze that
    // looks like the game hanging, with nothing on screen to say why.
    "tu_restrict_subgroup_size_64",
    "ADRENOTOOLS_DRIVER_PATH",
    "ADRENOTOOLS_HOOKS_PATH",
    "ADRENOTOOLS_DRIVER_NAME",

    // The FEX memory-ordering flags are listed for the same reason as WINEFSYNC:
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

    // **`FEX_DISABLEDEP` is deliberately absent from this set, and the absence is
    // the answer to "it is applied globally where it should be scoped".**
    //
    // Written down here because an omission is invisible: correction 9 in
    // `docs/TODO.md` is that reachability is decided by this set and nothing
    // else, so the only place a reader can learn that this name is reachable is
    // a comment in the set that does not contain it.
    //
    // *What it turns off.* `patches/fex/0002` adds the option and defaults it to
    // true, then routes it through `HandleProcessExecuteFlagsChange(
    // MEM_EXECUTE_OPTION_ENABLE)` in `InvalidationTracker`'s constructor rather
    // than assigning the flag, so the DEP-promoted interval bookkeeping runs as
    // it would for a guest that asked. FEX's page permissions are its own
    // interval map and not a query to the host, so with this on every readable
    // guest page counts as executable and every writable one as SMC-capable.
    //
    // *Why it is on for every guest.* Without it a program that builds code at
    // runtime — packers, anti-tamper stubs, some JITs — fails to decode an entry
    // block at all: `NoExec instruction in entry block`. The one title in this
    // project that has reached gameplay is a Denuvo title.
    //
    // *What it costs, and both halves are already paid for.* `0002`'s own
    // description names the first: a genuinely wild jump is translated instead of
    // faulting, so what would have been a clean access violation becomes silent
    // corruption. The second is a lock — with DEP off an ordinary
    // `PAGE_READWRITE` heap block gives `EffectiveExec = true`, so every growth of
    // the guest heap takes `CodeInvalidationMutex` exclusively
    // (`InvalidationTracker.cpp:502`), where without it the same notification
    // takes no lock at all. That is the `0188` edge of the #49 deadlock, and the
    // classification defect `patches/fex/0012` fixes has the same root. Two
    // defects, one default.
    //
    // *So why not narrow the default.* The two failure directions are not
    // symmetric. DEP **on** for a title that needs it off is a hard, loud death
    // before the first frame; DEP **off** for a title that does not need it is a
    // latent risk and a mutex. And there is no evidence any title is harmed by
    // it: it was tested and exonerated as the cause of Metro's 2026-08-14
    // regression. A per-title default would need a table of titles that nothing
    // in this project has, and guessing wrong in the safe-looking direction is
    // the direction that stops a game starting.
    //
    // *It is nevertheless already scoped two ways, both live, both measured, and
    // this was re-examined on 2026-08-16 rather than assumed:*
    //
    //  1. **Per process.** `SessionRuntime.generateCodeCache` sets
    //     `FEX_DISABLEDEP=0` for the offline compiler alone — the one process
    //     that runs no anti-tamper code — and that is what stopped its own 272 MB
    //     `LookupCache` reservation being classified as guest RWX. Measured: 21
    //     modules, zero `RWX reprotect FAILED`.
    //  2. **Per container.** Because the name is not in this set, both the
    //     manifest stage (a `ParamSpec` carrying `env: FEX_DISABLEDEP`) and the
    //     container's own environment table already reach it, and stage four runs
    //     last so it wins. It is also not in [FEX_CACHE_KEY_IGNORED], so
    //     [fexCacheKey] digests it: a container that turns DEP off lands in a
    //     *different* FEX cache directory instead of loading blocks generated
    //     with it on. The setting and the code it produced cannot come apart,
    //     which is what makes per-container scoping safe rather than merely
    //     possible.
    //
    // *What was considered and rejected: writing `FEX_DISABLEDEP = "1"` into the
    // environment below to make the default visible.* It changes no behaviour —
    // it is FEX's own default under `0002` — and it changes [fexCacheKey] for
    // every container that exists, discarding a 339 MB code cache that took until
    // 2026-08-15 to work at all, in exchange for a line of documentation. This
    // comment is that line, and it costs nothing. **Reopen only if a title is
    // found that DEP-off actually breaks**; the shape then is a manifest param
    // with a default, not a new name in this set, because reserving it would take
    // away both mechanisms above.

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

    // **Reserved, and the reason changed underneath the name.** It used to be
    // "this Turnip has only the software half of Mesa's X11 WSI compiled in, so
    // clearing this sends every swapchain into an `UNREACHABLE`". That stopped
    // being true with `patches/mesa/0004` and `0006`: both halves are compiled
    // now and both work. It is reserved today because it selects between two
    // *present paths* — one measured 3.5x cheaper, one that has never been run
    // under a guest — and a manifest param is the wrong surface for a choice
    // whose failure mode is a black window. It is in [DIAGNOSTIC_SESSION_ENV]
    // below, which is the surface that is. The whole argument is at the
    // assignment and at [ZERO_COPY_PRESENT].
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
 *  3. **`VKD3D_LOG_FILE` stays unreachable by any path**, because it is not
 *     here. The variable whose whole purpose is an *absence* cannot be written
 *     by a param or by Diagnostics.
 *
 *     *This clause used to name `MESA_VK_WSI_DEBUG` alongside it and no longer
 *     does.* The two were never the same case and being listed together made
 *     them look it: `VKD3D_LOG_FILE` must be absent or vkd3d's output leaves the
 *     pipe the session log reads, whereas `MESA_VK_WSI_DEBUG` merely had a fixed
 *     value, and it had that value because half of Mesa's X11 WSI was not
 *     compiled. `patches/mesa/0004` and `0006` compiled it, and
 *     `patches/mesa/README.md` measures the other path at 0.602 ms against
 *     2.143 ms. A fixed value with a 3.5x alternative behind it is a setting
 *     nobody had got round to exposing, not an invariant. The default has since
 *     moved to DRI3 and back and to DRI3 again; see [ZERO_COPY_PRESENT] for the
 *     chronology and for what is measured at each step.
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
    // **`VKD3D_QUEUE_PROFILE` is a path, and the row that switches it on does
    // not carry one.** The row sends `on` or `off`; the session turns `on` into
    // [vkd3dQueueProfileFile] and `off` into an empty string, in the diagnostics
    // stage below. That indirection is the whole reason it can be here at all:
    // the variable vkd3d reads *is* the destination, so a surface that could set
    // it literally could have vkd3d writing anywhere on the device. Reserved for
    // the same reason as `VESSEL_GFX_STATS`, and exposed for the same reason as
    // `VKD3D_CONFIG` — it holds an instrument nothing else in the stack has.
    "VKD3D_QUEUE_PROFILE",
    "WINEDEBUG",
    "DXVK_LOG_LEVEL",
    "VKD3D_DEBUG",
    "VKD3D_SHADER_DEBUG",
    "TU_DEBUG",
    "FEX_SILENTLOG",
    "MESA_LOG",
    "MESA_LOG_LEVEL",
    // freedreno's own capture of the GPU command stream, and the path it writes
    // to. The only instrument on this surface that records what the hardware was
    // actually told to do rather than what a layer says about itself -- depth
    // compare op, clear value, viewport range and LRZ direction are all in a
    // capture and in no log, which is why docs/TODO.md #56 outlasted six sessions
    // of reading logs.
    //
    // **A declared row is not enough on its own, and that is what this set is
    // for.** The row composes the variable; this list decides whether it leaves
    // the app. FD_RD_DUMP was declared, composed, and dropped at the `continue`
    // below in silence -- an empty capture directory and nothing in the log to
    // say why. Both names are needed: FD_RD_DUMP alone writes beside the working
    // directory, and the path is what puts captures somewhere readable.
    "FD_RD_DUMP",
    // Where vkd3d writes compiled shaders. Here for the same reason FD_RD_DUMP
    // above it is: the row composes the variable and this set is what lets it
    // leave the app. Also in [RESERVED_SESSION_ENV], as this set must be a
    // strict subset of it, so the path cannot be chosen by a document.
    "VKD3D_SHADER_DUMP_PATH",
    // Its counterpart: the dump writes `<hash>.spv` files, this reads them back
    // in place of translating. Also reserved above, as this set must stay a
    // strict subset of that one.
    "VKD3D_SHADER_OVERRIDE",
    // **`VKD3D_CONFIG` is here for one word: `breadcrumbs`.**
    //
    // It stays in [RESERVED_SESSION_ENV] — this set is a strict subset — so no
    // manifest param can reach it and no container document can set it through
    // the params path. What changes is that the *Diagnostics* surface can, and
    // the reason is that this variable holds the only instrument in the stack
    // that can explain a `VK_ERROR_DEVICE_LOST`.
    //
    // Requiem loses the device on a timeline-semaphore wait, in vkd3d's memory
    // transfer queue, before a swapchain exists — and Turnip's own log says
    // nothing about it. Breadcrumbs replay the command buffers that were in
    // flight and name the last command the GPU acknowledged, which is the
    // difference between "the GPU died" and knowing what killed it.
    //
    // Two things make this safe to expose rather than merely useful. It is
    // *narrowing* like everything else on that surface: `sessionEnvironment`
    // has already written the fixed `nodxr`, and a diagnostics row replaces
    // that value for one session rather than editing the baseline. And it is
    // inert on a shipped build: `enable_trace` is `auto`, which resolves to
    // false under `--buildtype release`, so `VKD3D_ENABLE_BREADCRUMBS` is not
    // defined and the word parses to nothing. `VKD3D_BREADCRUMBS=1
    // ./build/vkd3d.sh` is what makes it mean something, and that build is
    // deliberately not the default.
    //
    // Verified on the shipped payload before adding this: `d3d12core.dll` from
    // a normal build contains the config *names* and no report strings at all,
    // so a container that sets this against a normal component gets silence and
    // not a lie. Turnip does support the `VK_AMD_buffer_marker` the tracer
    // needs (`tu_device.cc`), so on the diagnostic build it really reports.
    "VKD3D_CONFIG",
    // Here for the same reason as its neighbour and with the same shape: the
    // session sends one line in it, a diagnostics row composes rather than
    // replaces, and the three d3d11 options worth trying on a shared-memory part
    // are only reachable this way now that the variable is reserved.
    "DXVK_CONFIG",
    // **The only instrument left for the buzz, and the reason is that every
    // cheaper one has already been spent.**
    //
    // Requiem buzzes and Metro does not, through the same driver on the same
    // device, and the two traces are the same numbers: burst 868, capacity 1736,
    // client buffer 2604 in both; `held` 1870 against 1909; `in_oss` 1106
    // against 1144; 41% against 40% partial writes; zero AAudio xruns in either.
    // Neither end starves and the device never reported an underrun, so the
    // frames are already wrong when they arrive — and a counter cannot say in
    // what way. Noise, gaps and clipping sound alike and want different fixes.
    //
    // On this surface rather than a manifest param for the reason the reserved
    // set gives at the name: it writes a file, ten times a second, for as long
    // as the session runs. That is a thing to switch on for one session while
    // looking at a bug, which is exactly what this surface is.
    "VESSEL_AUDIO_DUMP",
    // **The only entry here that changes how the frame is presented rather than
    // what gets said about it, and it is here because the alternative is a
    // rebuild in the middle of a black screen.**
    //
    // `docs/BANDWIDTH.md` item 7 ranks DRI3 zero-copy present as the one
    // *measured* frame-time win in the stack that is switched off: 0.602 ms
    // against 2.143 ms, 300 frames at 1280x720, `patches/mesa/README.md`. The
    // switch was [ZERO_COPY_PRESENT], a compile-time constant, and the failure
    // mode of getting it wrong is stated at the assignment and confirmed once on
    // the device — not a slow session, a dead one.
    //
    // A constant is the wrong shape for that. It means the way back from a black
    // window is a Gradle build, which is exactly what you do not have when the
    // thing you were measuring is a title that takes fourteen seconds to
    // initialise. On this surface it is a dropdown, per container, and the
    // container that broke is the only one affected.
    //
    // *Why not a manifest param, which is where a per-container setting normally
    // lives.* Two reasons, and the second is the one that decides it. It fails
    // the manifest's own law — "explainable in one plain sentence to someone who
    // does not know what a translator is" (`assets/params-manifest.json:9-12`) —
    // and unreserving it would let a *hand-edited container document* pick a
    // present path, which is a document that can stop a session starting with no
    // UI anywhere having offered the choice. Reserved-plus-declared keeps the
    // only writer a control that shows the caution.
    //
    // *Why not `oneSessionFrom`, which would disarm it automatically after one
    // launch.* Considered and rejected. `consumed()`'s contract is "every row
    // loud enough to be one-session", i.e. it is about log *volume*, and
    // borrowing it for risk would make the mechanism mean two things. It would
    // also make the setting useless the day it starts working: a win you have to
    // re-arm every launch is not a win you can ship.
    "MESA_VK_WSI_DEBUG",
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
     * `WINE_ADDITIONAL_CERTS_DIR` -- `files/containers/<id>/certs`.
     *
     * Certificate authorities the user added. Wine imports every file in the
     * directory into the root store at start-up (`crypt32`, `load_root_certs`),
     * which is the store Chromium consults on Windows -- so a self-signed gateway
     * is trusted by an Electron application, not only by whatever honours a
     * `--cacert` flag.
     *
     * Derived from the prefix like [caches], for the same reason: the container
     * directory is the thing both belong to, and deriving it here keeps the one
     * construction site from having to know.
     */
    val certificates: File = File(prefix.parentFile ?: prefix, "certs"),
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

/**
 * The DOS path the D3D layers are handed for [GFX_STATS_FILE], and the reason
 * they are not handed the unix one.
 *
 * Both producers open this with plain C file I/O from *inside* the prefix,
 * where a leading `/` is not the root: msvcrt resolves it against the current
 * drive, so `/data/user/0/…` becomes `C:\data\user\0\…` and the open fails
 * because nothing ever created those directories. vkd3d does not even get that
 * far — `vkd3d_vessel_stats.c` rewrites a leading `/` to `Z:\…` on the standard
 * Wine assumption that `Z:` is the unix root, and [DriveMap.removeRootDrive]
 * takes `Z:` out of every Vessel prefix on purpose. That is the same wall
 * [VKD3D_CACHE_DOS_PATH] already documents one layer down, hit a second time
 * by code whose comment asserted the assumption instead of checking it.
 *
 * **Neither failure says anything.** `fopen` returns NULL, the producer
 * returns, and an unwritten snapshot is indistinguishable from a title that
 * draws no Direct3D — so the panel reported "no Direct3D counters" for a game
 * that was rendering. Measured on Resident Evil Requiem: `VESSEL_GFX_STATS`
 * present in the process environment, the string present in the shipped
 * `d3d11.dll` and `d3d12core.dll`, and no `gfx-stats.json` anywhere in the
 * container after 9,120 presents.
 *
 * A path that already names a drive skips vkd3d's rewrite and is what msvcrt
 * wants, so one DOS path fixes both producers and neither component has to be
 * rebuilt. [vesselTmpLink] is what makes it resolve.
 */
const val GFX_STATS_DOS_PATH: String = "C:\\vessel\\tmp\\$GFX_STATS_FILE"

/**
 * The host side of [GFX_STATS_DOS_PATH]'s directory — `drive_c/vessel/tmp`
 * pointed at the container's scratch directory.
 *
 * The directory rather than the file, deliberately: the reader keeps naming the
 * same inode through [gfxStatsFile] with no change at all, and everything else
 * already in `tmp` — [TU_DEBUG_FILE_NAME], [GPU_TRACE_FILE_NAME] — gains a DOS
 * name for free, which is what the next instrument that has to be opened from
 * inside the prefix will need. Same shape and same parent directory as
 * [fexCacheLink] and [vkd3dCacheLink].
 */
internal fun vesselTmpLink(prefix: File): File =
    File(File(File(prefix, DriveMap.DRIVE_C), "vessel"), "tmp")

/** `TU_DEBUG_FILE` — Turnip re-reads this mid-session. See `RESERVED_SESSION_ENV`. */
const val TU_DEBUG_FILE_NAME: String = "tu-debug"

/** `MESA_GPU_TRACEFILE` — where a per-render-pass trace lands. */
const val GPU_TRACE_FILE_NAME: String = "gpu-trace.csv"

/** The live Turnip flag file for a container, for anything that wants to write it. */
fun turnipDebugFile(tmp: File): File = File(tmp, TU_DEBUG_FILE_NAME)

/** The per-render-pass trace for a container. */
fun gpuTraceFile(tmp: File): File = File(tmp, GPU_TRACE_FILE_NAME)

/**
 * `VKD3D_QUEUE_PROFILE` — vkd3d's submission timeline, when the row asks for it.
 *
 * A Chrome trace: open it in `chrome://tracing` or Perfetto. It is the D3D12
 * counterpart to nothing — DXVK has no equivalent and neither does Turnip — and
 * it is the only instrument on this stack that can say when a submission
 * started and when it finished rather than how many there were.
 */
const val VKD3D_QUEUE_PROFILE_FILE_NAME: String = "vkd3d-queue-profile.json"

/** The submission timeline for a container. */
fun vkd3dQueueProfileFile(tmp: File): File = File(tmp, VKD3D_QUEUE_PROFILE_FILE_NAME)

/**
 * Where vkd3d writes compiled shaders when the dump row is on.
 *
 * A directory rather than a file, and one vkd3d expects to exist already: it
 * opens files inside it and a path it cannot write to produces no shaders and no
 * complaint. `SessionRuntime` creates it.
 */
fun vkd3dShaderDumpDir(tmp: File): File = File(tmp, "shaders")

/**
 * Where vkd3d reads replacement shaders from when the override row is on.
 *
 * Deliberately not [vkd3dShaderDumpDir]. A dump is thousands of files and gets
 * cleared; an override is the handful somebody chose, and pointing the two at
 * one directory would mean every dumped shader silently replaced itself and a
 * clear-out threw away the experiment.
 */
fun vkd3dShaderOverrideDir(tmp: File): File = File(tmp, "shader-override")

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
     * `display.frameGeneration`, already parsed. Below 2 means off.
     *
     * Here because it divides [fpsLimit] rather than because the guest is told
     * about it — nothing inside the container knows or should know that the
     * compositor is inventing frames. See the D3D limiter block below.
     */
    frameGeneration: Int = 0,
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
    /**
     * The resolvers this phone is currently using, already looked up.
     *
     * Passed in for the reason this whole function is pure: reading them means
     * `ConnectivityManager`, and a `Context` here would make the exact-output
     * test in `SessionEnvironmentTest` depend on whichever network the machine
     * running it happened to be on. The caller has the `Context`; this only has
     * to decide how the answer is spelled.
     *
     * Empty — no network, or a device that reports none — omits the variable
     * rather than setting it blank, so `dlls/dnsapi/android.c` keeps returning
     * `DNS_ERROR_NO_DNS_SERVERS` and callers see the same honest "none" they saw
     * before this existed. An empty string would instead be a list with one
     * unparseable entry in it.
     */
    dnsServers: List<String> = emptyList(),
): Map<String, String> {
    val environment = LinkedHashMap<String, String>()

    environment["WINEPREFIX"] = paths.prefix.absolutePath

    // **Certificate authorities the user added, imported into the root store.**
    //
    // Wine reads every file in this directory at start-up and adds it to the
    // root store -- `crypt32`, `load_root_certs` -- which is the store Chromium
    // consults on Windows. That is what makes a self-signed gateway work for an
    // Electron application: `NODE_EXTRA_CA_CERTS` reaches Node's TLS and misses
    // Chromium's own network stack, which is the one that makes the request.
    //
    // Always set, and the directory need not exist: Wine opens it, fails, and
    // carries on. Setting it unconditionally means a certificate added later is
    // picked up by the next session with nothing else to remember.
    environment["WINE_ADDITIONAL_CERTS_DIR"] = paths.certificates.absolutePath

    // **How netd's answer reaches the guest.** Android has no `/etc/resolv.conf`
    // — checked on device, it is not merely empty but absent — so Wine's
    // resolver finds no servers and every `DnsQueryConfig(DnsConfigDnsServers*)`
    // fails. `dlls/dnsapi/android.c` says why that is unfixable from C and names
    // the one interface that can see them, which is on the Java side; this is
    // that answer arriving. Comma-separated because the parser reading it is
    // eleven lines of C and a separator that never appears in an address literal
    // is worth more there than any structure would be.
    if (dnsServers.isNotEmpty()) {
        environment["VESSEL_DNS_SERVERS"] = dnsServers.joinToString(",")
    }

    // **fsync, and `WINEESYNC` is gone because esync is not in this Wine.**
    //
    // Checked rather than assumed, and the answer was a surprise: there is no
    // `server/esync.c`, no `dlls/ntdll/unix/esync.c`, and `WINEESYNC` appears in
    // zero source files anywhere in the tree. Valve's experimental_11.0 dropped
    // esync for ntsync and fsync. So the `WINEESYNC=1` that used to be on this
    // line was an inert string, and `docs/OPTIMIZATION.md`'s "esync is the best
    // available" describes a mechanism this build does not contain.
    //
    // The real ladder is `server/inproc_sync.c:50-65`, in this order:
    //
    //     /dev/ntsync opens          -> "ntsync: up and running."
    //     else do_fsync()            -> fsync
    //     else                       -> "using server-side synchronization."
    //
    // `/dev/ntsync` does not exist on this device (checked) and this Wine has no
    // ntsync.c, so that first branch is unreachable here. With WINEFSYNC unset
    // every session so far therefore took the *last* branch -- server-side
    // synchronisation, where each wait is a round trip to wineserver. This is
    // not a marginal upgrade over esync; it is leaving the fallback.
    //
    // fsync's advantage over the fallback is structural: WaitForMultipleObjects
    // becomes one futex_waitv instead of a server round trip, and the
    // uncontended case -- almost all of them -- stays in userspace on the futex
    // word entirely.
    //
    // **The risk, and it is not the usual kind.** `patches/wine/0022` records
    // that under Android's seccomp policy futex_waitv (arm64 449) is not
    // refused with ENOSYS, it is *fatal* -- SIGSYS, and wineserver dies inside
    // the probe before serving a request. That patch stops the probe running
    // unless WINEFSYNC is set, which is exactly what this line now does, so the
    // probe will run. If this device's policy kills it, the symptom is a session
    // that never starts, loudly and immediately, and the fix is deleting this
    // line. That is a survivable failure and a legible one, which is why it is
    // worth finding out; `docs/TODO.md` #39 has been open on precisely this
    // question with nobody willing to flip the switch.
    //
    // **Unmeasured.** No session has run with fsync on this device. The claim
    // above is a mechanism argument, not a number, and #39 does not close until
    // one run either beats esync on a real workload or is written up as not
    // worth it.
    // **Explicitly "0", because fsync cannot work on Android and the reason is
    // upstream of this device.**
    //
    // `fsync_check_support` (`server/fsync.c:58`) returns 0 without issuing any
    // syscall when this is unset or zero, and probes `futex_waitv` (arm64 449)
    // when it is set. Android's seccomp policy does not allow that syscall:
    // bionic's `SECCOMP_ALLOWLIST_COMMON.TXT` carries `futex` and
    // `futex_time64` and no `futex_waitv`, `SYSCALLS.TXT` defines none either,
    // and that file states the resulting policy "is applied only to zygote
    // spawned processes" -- which is every app, this one included. A syscall
    // outside the allowlist gets SECCOMP_RET_TRAP, i.e. SIGSYS, so the probe
    // does not return an error, it kills wineserver.
    //
    // **Measured twice, the second time on a clean install with nothing else
    // changed**: `wineboot --init` left `prefix/system.reg` at 0 bytes against
    // a fresh prefix's ~97 KB, and the session reported "Initialise Wine prefix
    // did not finish". The *absence* of a Wine error is the fingerprint rather
    // than a gap in the evidence: a clean decline prints "wineserver: using
    // server-side synchronization", and a dead server prints nothing at all --
    // which is why Vessel's own 0-byte registry check is the only thing that
    // can report this failure.
    //
    // **Not set at all, rather than set to "0".** Unset and zero take the same
    // branch in `fsync_check_support`, so an explicit zero would buy nothing at
    // runtime, and this file's own rule is that it does not add variables it
    // does not need. What keeps the decision from being re-made by accident is
    // the entry in [RESERVED_SESSION_ENV] below: WINEFSYNC stays reserved, so a
    // container manifest cannot set it either, and a switch that is fatal on
    // this platform is not reachable from a settings screen.
    //
    // `patches/wine/0020` and `0022` stay. They are not wasted: 0022 is why an
    // unset variable is a fast decline instead of a probe, and both become live
    // again on any platform whose sandbox permits the syscall.
    //
    // So `server/inproc_sync.c:50-65` resolves here to its last rung, and not
    // by neglect: `/dev/ntsync` needs Linux 6.14 and this device runs 6.12, and
    // fsync needs a syscall the sandbox kills for. Server-side synchronisation
    // is the only one of the three that can run.

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
    //  1. **Something runs the compiler.** `SessionRuntime.start` launches
    //     `FEXOfflineCompiler64.exe process-all` beside the desktop, gated on
    //     `CODE_CACHE_DURING_SESSION`; it merges the run's codemaps into the
    //     reference set and generates a cache per binary. It is best-effort and
    //     cannot fail a session; see the method for what it does when it fails.
    //
    //     *This said "teardown runs it, once the guest is dead" and that had
    //     stopped being true.* Teardown only cancels the job — by then
    //     `wineserver -k` has run and there is nothing left to compile in. The
    //     wrong version mattered rather than being merely untidy: it is the
    //     sentence that would convince a reader the compiler cannot be running
    //     while the game holds a cache open, which is exactly the race that
    //     `patches/fex/0018` and `promoteReadyCaches` exist to answer.
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

    // **Stop telling the guest it has twice the memory that exists.** Adreno has
    // one heap and it is device-local, so DXVK finds nothing to report as shared
    // and copies the device figure into it rather than reporting none — see
    // [FIXED_DXVK_MAX_SHARED_MEMORY] for the code and for the measurement that
    // says a title adds the two.
    //
    // Set for every container rather than only for the ones that hit it, because
    // there is nothing container-specific about the fault: the heap is one heap
    // on every device this runs on, and a container that has not been diagnosed
    // is precisely the one whose owner will not know to add the line. It is also
    // *narrowing* — it lowers a number that was invented, and a title that reads
    // the D3D12 budget API instead sees no change at all.
    //
    // Both halves together, so the two figures the guest adds sum to the
    // container's own `hardware.vram` rather than to something near it. See
    // [HardwareLimits.dxvkMemoryConfig] for why the margin is taken off the
    // dedicated side.
    val dxvkConfig = (hardware?.dxvkMemoryConfig ?: listOf(FIXED_DXVK_MAX_SHARED_MEMORY)).toMutableList()

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
    // A DOS path, not `gfxStatsFile(paths.tmp).absolutePath` — the unix path
    // reached both producers and neither could open it. See [GFX_STATS_DOS_PATH].
    environment["VESSEL_GFX_STATS"] = GFX_STATS_DOS_PATH

    // **Every .NET program dies on Wine's ICU stub without this, and the death
    // looks like nothing at all.**
    //
    // `dlls/icu/icu.spec` is forwarders and only forwarders — the whole
    // directory is `Makefile.in` and that spec, and every line reads
    // `@ cdecl -norelay <name>() icuuc68.<name>_68`. There is no `icuuc68.dll`
    // in this build, so each forward dangles. .NET requires ICU for
    // globalization, resolves one of those exports during startup, and exits.
    //
    // Measured on the device with PowerShell 7, which is the first .NET program
    // this project has shipped. The session log ends:
    //
    //   loaddll:build_module Loaded L"C:\windows\system32\icu.dll": builtin
    //   module:load_dll Failed to load module L"icuuc68.dll"; status=c0000135
    //   module:find_forwarded_export module not found for forward
    //     'icuuc68.u_charsToUChars_68' used by L"...\icu.dll"
    //   module:LdrGetProcedureAddress "u_charsToUChars" (ordinal 0) not found
    //
    // From the outside that is a console window that appears and vanishes, with
    // no error anywhere a user can see. Note what it is *not*: `clrjit.dll`
    // loads successfully a few lines earlier, so CoreCLR runs under FEX fine and
    // the JIT was never the problem — worth recording because it was the first
    // theory and it was wrong.
    //
    // Invariant globalization is .NET's own documented answer to a missing ICU.
    // The cost is culture-aware string comparison and formatting, which a shell
    // does not need; the alternative is shipping ICU or implementing Wine's,
    // both much larger than one variable. Set for every session rather than for
    // PowerShell alone, because the wall belongs to .NET and not to pwsh.
    environment["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"

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
    environment["VKD3D_CONFIG"] = FIXED_VKD3D_CONFIG

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
    //
    // **Divided by the frame-generation multiple, and that division is the whole
    // feature.** `display.fpsLimit` is what the *screen* shows; with predicted
    // frames turned on, only one presented frame in N was rendered by the guest,
    // so the guest's own cap has to be that fraction. A container asking for 120
    // fps at 2x renders 60 and is shown 120.
    //
    // Getting this wrong costs the feature entirely, in both directions. Leave
    // the guest uncapped and it spends the whole GPU on real frames, so there is
    // no headroom left to synthesise with and nothing is gained — which is the
    // objection that frame generation only pays when the renderer is held below
    // the target. Cap the guest at the full number instead and the compositor is
    // asked to present N times that, past what the panel can show, and the extra
    // frames are discarded by the display.
    //
    // It also keeps the compositor's own pacer out of the way. `PacedXServerView`
    // drops a `requestRender` that arrives sooner than the limit allows, and a
    // predicted frame arrives between two real ones by construction — so the two
    // rates have to be the ones described here or the pacer suppresses exactly
    // the frames this exists to add.
    fpsLimit?.takeIf { it > 0 }?.let { limit ->
        val rendered = if (frameGeneration >= 2) maxOf(1, limit / frameGeneration) else limit
        // **`DXVK_FRAME_RATE` does not exist in the DXVK this ships, and setting
        // it has never capped anything.** The variable was removed upstream --
        // vkd3d's own changelog records dropping its copy "to align with DXVK's
        // removal" -- and the limiter is now a config option. So every D3D9, 10
        // and 11 title has been rendering flat out while this code believed it
        // was capped, which is precisely the 116-rendered-for-24-shown the note
        // above measured and thought it had fixed. Confirmed by grep: zero
        // occurrences of DXVK_FRAME_RATE anywhere in native/dxvk/src.
        //
        // `dxvk.maxFrameRate` rather than the per-API `dxgi.` and `d3d9.` names:
        // both dxgi_options.cpp and d3d9_options.cpp read the `dxvk.` one first
        // and fall back to their own, so one option covers every Direct3D
        // version DXVK serves. DXVK_CONFIG is semicolon-separated `key = value`
        // and is parsed on top of any dxvk.conf, so this does not displace a
        // file a user has put beside their game.
        //
        // Appended to the memory options rather than assigned over them. The
        // variable is one string and DXVK_CONFIG is the only way in, so writing
        // it twice would silently drop whichever line ran first -- which would
        // have taken the shared-memory fix with it.
        dxvkConfig += "dxvk.maxFrameRate = $rendered"
        // vkd3d kept its variable, and reads it in swapchain.c.
        environment["VKD3D_FRAME_RATE"] = rendered.toString()
    }

    // Written once, after every contributor has had its say, and outside the
    // limit block: an uncapped container still needs the shared-memory options,
    // and assigning inside the block would have dropped them for exactly the
    // containers that set no frame rate.
    environment["DXVK_CONFIG"] = dxvkConfig.joinToString(";")

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
    // **This used to say "not a debug switch here, which is why it is not a
    // setting" — the difference between a present path and no present path.**
    // That is history: `patches/mesa/0004` and `0006` compiled the DRI3 half, so
    // it is now a choice between two working paths. It stays in
    // [RESERVED_SESSION_ENV] because a manifest param is the wrong surface for a
    // choice whose failure mode is a black window, and it is in
    // [DIAGNOSTIC_SESSION_ENV] because that surface is the right one.
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
    // image to it at rowPitch 5120, and the vendored server answers every DRI3,
    // Present, XFIXES and SYNC request Mesa issues at the versions it advertises.
    // Joining them under a *guest* has never been run.
    //
    // **One claim that used to be here is false and is corrected rather than
    // deleted, because it was load-bearing for how much this buys.** It said
    // `presentPixmap` had "the flip branch in place". It does not.
    // `PresentExtension` declares `Mode.FLIP` and never uses it: `presentPixmap`
    // does a synchronous CPU `copyArea` under the render lock and then sends
    // `sendCompleteNotify(…, Mode.COPY, …)` unconditionally. So the DRI3 path as
    // it stands removes the *client-side* copy — the 3.6 MB `xcb_put_image` and
    // the whole-frame `vkCmdCopyImageToBuffer` — and still pays one server-side
    // copy, which `patches/mesa/README.md` says in as many words and which is
    // already inside the 0.602 ms. The flip branch is the next win after this
    // one, not a prerequisite for it.
    //
    // So [ZERO_COPY_PRESENT] is a switch, not a deletion. If DRI3 does not
    // negotiate against this server the symptom will be immediate and total —
    // a black window or no swapchain at all, not a slow one. The `sw,linear`
    // measurement above stays because it is still the right answer for the
    // software path.
    //
    // **Always assigned, where this used to be `if (!ZERO_COPY_PRESENT)`, and
    // the difference is not cosmetic.** Two things fall out of it:
    //
    //  1. *The switch is symmetric.* The diagnostics stage below can only
    //     rewrite keys, never remove them — `environment[key] = value` and
    //     nothing else. With the key absent on the DRI3 side, a container could
    //     turn zero-copy *on* and never turn it off again once
    //     [ZERO_COPY_PRESENT] flips, because there would be no key to rewrite.
    //     That is precisely the escape hatch this whole change exists to build,
    //     failing in the direction it was built to protect.
    //  2. *It is visible in `/proc/<pid>/environ`.* This project checks the
    //     guest's own environ rather than trusting the map — three separate
    //     notes in this file record doing exactly that. An absent variable and a
    //     variable that never got written look identical there. `MESA_VK_WSI_DEBUG=`
    //     is unambiguous.
    //
    // **Empty is unset, and that is a property of Mesa's parser rather than a
    // hope.** `wsi_common.c:80` is
    // `WSI_DEBUG = parse_debug_string(os_get_option("MESA_VK_WSI_DEBUG"), …)`,
    // and `parse_debug_string` (`util/u_debug.c`) walks the string with
    // `for (; n = strcspn(s, ", \n"), *s; …)` — on `""` the loop body never
    // runs and it returns 0, the same value it returns for `NULL`. There is no
    // flag whose absence means something different from its being unnamed.
    //
    // One real asymmetry, recorded because it is invisible and in our favour:
    // `os_get_option_internal` (`util/os_misc.c`) falls back to
    // `os_get_android_option(name)` **only when `getenv` returns NULL**. An
    // empty value is not NULL, so setting this suppresses an Android system
    // property that could otherwise supply a value nobody here chose. Nothing on
    // this device sets one; the point is that unset was the weaker guarantee.
    environment["MESA_VK_WSI_DEBUG"] = FIXED_MESA_VK_WSI_DEBUG

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

    /*
     * **Adreno's subgroups are 128 wide and a great many shipped shaders assume
     * 64. This is the option Mesa ships for exactly that, and it is not a
     * setting.**
     *
     * Turnip exposes `minSubgroupSize = threadsize_base` and `maxSubgroupSize =
     * threadsize_base * 2` when the part supports the double size
     * (`tu_device.cc:1111-1113`), so a dispatch can get 64 or 128. A shader
     * written against a desktop GPU's fixed 32 or 64 gets whichever it is given.
     * `tu_restrict_subgroup_size_64` pins it to 64 — the option's own
     * description in `tu_drirc_gen.py:99-101` is "work around games assuming
     * desktop GPU 32/64 sizes", and `00-turnip-defaults.conf` already turns it
     * on for No Man's Sky, whose comment describes a lighting compute shader
     * that "does a write once per subgroup (assuming 64)" and bands vertically
     * at 128.
     *
     * **Measured here, and it is the whole of a blocker.** Resident Evil Requiem
     * hung its GPU on a `256x1x1` compute dispatch. vkd3d breadcrumbs named the
     * command; the shader dump showed a wave-aggregated allocation loop —
     * `WaveActiveBallot` for the active count, an exclusive scan for this lane's
     * index, one lane doing the `atomicAdd`, `WaveReadLaneFirst` to share the
     * base back, loop while the index is in range. Its exit depends on every
     * lane agreeing about the wave, so at the wrong width the index stops
     * advancing and the dispatch never retires: `VK_ERROR_DEVICE_LOST`, then a
     * critical section held by the thread that noticed, then a frozen loading
     * screen. **33 of the 2,867 shaders that one session compiled carry the same
     * loop**, so it was never going to be one shader.
     *
     * *A per-shader `VKD3D_SHADER_OVERRIDE` was built first and works — it is in
     * this file's history — and it is the wrong fix.* It covers only shaders
     * already seen, rewrites the game's own algorithm, needs redoing after a game
     * update, and forces `pipeline_library_ignore_spirv`, which takes the PSO
     * cache out of the picture. This costs nothing per command and covers every
     * shader, including ones nobody has compiled yet.
     *
     * **Reserved rather than offered**, like its neighbour above: a subgroup
     * width is not a preference, and a container that turned this off would get
     * a GPU hang that looks like the game freezing. The cost is real and is
     * accepted — half the lanes per wave on parts that could do 128 — because a
     * title that renders slower is worth more than one that does not render.
     */
    if (RESTRICT_SUBGROUP_SIZE_64) environment["tu_restrict_subgroup_size_64"] = "true"

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
    for (
        (key, value) in diagnosticEnvironment(
            profile.diagnostics,
            tuDebugFlags(profile, manifest),
            dxvkConfig,
        )
    ) {
        if (key !in DIAGNOSTIC_SESSION_ENV) continue
        // `VKD3D_QUEUE_PROFILE` names its own destination, so the switch cannot
        // send its value through unchanged: vkd3d would `fopen("on")` and drop a
        // file called `on` in whatever directory the guest happened to be in.
        // Resolved here rather than in the row because this is the only place
        // that knows the container's paths, and off must be empty rather than
        // absent — `vkd3d_get_env_var` treats an empty value as unset, and
        // leaving the key out entirely would let stage four's escape hatch or an
        // inherited value stand in for a switch the user turned off.
        environment[key] = if (key == "VKD3D_QUEUE_PROFILE") {
            if (value == Emit.ON) vkd3dQueueProfileFile(paths.tmp).absolutePath else ""
        } else {
            value
        }

        // FD_RD_DUMP names a destination too, and unlike the profile it names a
        // *directory* that Turnip both writes captures into and creates its
        // trigger file in (freedreno_rd_output.c:185-189). Derived here for the
        // same reason: this is the only place that knows the container's paths,
        // and a document that could choose it could have the driver writing
        // outside the container.
        // The shader dump names a directory rather than a file, and vkd3d does
        // not create it -- an unwritable path is silence, not an error. Derived
        // here for the reason the profile above is, and created by
        // SessionRuntime before the guest starts.
        if (key == "VKD3D_SHADER_OVERRIDE") {
            environment[key] =
                if (value == Emit.ON) vkd3dShaderOverrideDir(paths.tmp).absolutePath else ""
        }

        if (key == "VKD3D_SHADER_DUMP_PATH") {
            environment[key] =
                if (value == Emit.ON) vkd3dShaderDumpDir(paths.tmp).absolutePath else ""
        }

        if (key == "FD_RD_DUMP" && value.isNotEmpty()) {
            environment["FD_RD_DUMP_PATH"] = paths.tmp.absolutePath
        }
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

    /**
     * The `DXVK_CONFIG` lines that make the guest's memory report add up to
     * [vramMb], in order, and never empty.
     *
     * **Two numbers, because the guest adds them.** `DXGI_ADAPTER_DESC` carries
     * dedicated and shared separately and RE Engine's *Max VRAM* readout is the
     * sum, so capping only one of them leaves a total that is still not the
     * setting. Setting `hardware.vram` to 4 GB and being told 7.98 GB is what
     * this fixes; the split is 3,968 + 128.
     *
     * The shared side is [FIXED_DXVK_SHARED_MEMORY_MB] and the dedicated side is
     * whatever is left, rather than the other way around, because the shared
     * figure is the invented one — Adreno has a single device-local heap and
     * DXVK copies the device size into `sharedMemory` only so that games allergic
     * to a zero are not handed one (`dxgi_adapter.cpp:455`). The margin comes out
     * of the number that is real precisely so the total is the number that was
     * asked for.
     *
     * Both options only ever *lower* what the heaps report (`:480-486`), so a
     * setting larger than the device can back is inert rather than a lie in the
     * other direction — which is the safe way round for this to be wrong.
     *
     * Falls back to the shared cap alone when there is no [vramMb] to split, or
     * when it is so small that the dedicated side would not be positive. The
     * doubling is wrong at every size, so the half of the fix that needs no
     * setting is not made conditional on one.
     */
    val dxvkMemoryConfig: List<String>
        get() {
            val want = vramMb
            if (want == null || want <= FIXED_DXVK_SHARED_MEMORY_MB) {
                return listOf(FIXED_DXVK_MAX_SHARED_MEMORY)
            }
            return listOf(
                "dxgi.maxDeviceMemory = ${want - FIXED_DXVK_SHARED_MEMORY_MB}",
                FIXED_DXVK_MAX_SHARED_MEMORY,
            )
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
 * A memory size as the Hardware fields accept one: a number, optionally with a
 * unit.
 *
 * **The unit is optional and a bare number means gigabytes**, which is what every
 * container document already stores and what every preset writes, so widening the
 * field costs no migration. `g`/`gb` is therefore only ever redundant, and it is
 * accepted because the field is pre-filled with the option's label — `6 GB` — and
 * a person editing that in place will leave the unit behind. That is not a
 * mistake to reject; it is the most likely way anyone reaches a size the presets
 * do not offer.
 *
 * `m`/`mb` is the reason to parse the unit at all rather than just strip it: 4 GB
 * and 6 GB are a long way apart on a phone that has to fit Android, the
 * translator and a shader compiler in the same pool, and there was no way to ask
 * for anything between them.
 *
 * Case-insensitive, and whitespace anywhere it could plausibly be typed.
 */
private val MEMORY_SIZE = Regex("""\s*(\d{1,6})\s*([gGmM][bB]?)?\s*""")

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

    // Megabytes, or null for `auto` and for anything that will not parse.
    // Unparseable is deliberately the same as auto: the field is free text so a
    // user can type a size the presets do not offer, and the honest failure is to
    // report the real machine rather than to invent a number.
    //
    // **`4gb` used to be one of the unparseable ones, and that is why the unit is
    // read now.** The field is pre-filled with the option's *label* — `6 GB`, not
    // `6` — so editing it in place is how anyone reaches a size the presets do
    // not offer, and every such edit produced a string ending in ` GB`. That
    // failed the param's pattern, drew the danger ring, and then parsed to null,
    // so the guest was told the whole 15.6 GB device. Two failures from one
    // typing, and the one that mattered was silent. Seen on device with
    // `hardware.ram` reading `7 GB`.
    fun megabytes(key: String): Int? {
        val text = (value(key) as? ParamValue.Text)?.value?.trim().orEmpty()
        if (text.isEmpty() || text.equals(HARDWARE_AUTO, ignoreCase = true)) return null
        val match = MEMORY_SIZE.matchEntire(text) ?: return null
        val size = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        // A bare number is gigabytes, which is what every stored document means
        // and what the presets write, so no container has to be migrated to read
        // this. A unit is only ever an addition to that.
        return if (match.groupValues[2].startsWith("m", ignoreCase = true)) size else size * 1024
    }

    val cores = (value(CORES_KEY) as? ParamValue.Choices)
        ?.values
        .orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .filter { it >= 0 && (deviceCores <= 0 || it < deviceCores) }

    return HardwareLimits(
        cores = cores,
        ramMb = megabytes(RAM_KEY),
        vramMb = megabytes(VRAM_KEY),
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
/**
 * How many CPU cores this device *has*, which is not what the JVM will tell you.
 *
 * **`Runtime.availableProcessors()` reports cores that are online right now**,
 * and Android parks cores aggressively when idle. Read on a quiet phone it
 * returns four on an eight-core chip, and the caller then filtered a six-core
 * selection down to "cores 0-3, because 4 and 5 are not on this device" —
 * measured, from a session where the container was set to six and the guest was
 * told four. The setting silently became a different setting.
 *
 * `/sys/devices/system/cpu/present` is the kernel's list of cores that exist
 * whether or not they are running, in the form `0-7`. Falling back to the JVM's
 * answer is still better than zero, which would disable the setting entirely.
 */
fun deviceCoreCount(present: File = File("/sys/devices/system/cpu/present")): Int = runCatching {
    // "0-7" -> 8, "0" -> 1, "0-3,6-7" -> the highest index plus one, because the
    // selection is by core number and a gap is still a number that exists.
    present.readText().trim()
        .split(",")
        .flatMap { it.split("-") }
        .mapNotNull { it.trim().toIntOrNull() }
        .maxOrNull()
        ?.plus(1)
        ?: 0
}.getOrDefault(0).takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()

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

