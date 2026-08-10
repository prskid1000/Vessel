# TODO

What is left, why it matters, and what "done" means for each. Ordered by what
blocks the product rather than by effort.

**The rule for this file is the project's rule everywhere else: nothing is ticked
until it has been watched working on the device.** A green build, a passing test
and a plausible log line are all things that have been wrong here before. Where
an item is closed by evidence, the evidence is named.

Status: `[ ]` open · `[~]` in progress · `[x]` done, with evidence · `[-]` closed
as won't-do, with the reason.

---

## 1. Blocking a working product

What stands between here and one sentence: *a Windows program drew on the screen
through DXVK*. As of 2026-08-09 **half of that sentence is true** — a Windows
program draws through D3D11 and the pixels read back correct — and what is left
is putting those pixels in a window.

- [x] **Nothing had ever rendered a triangle. It has now, in both bitnesses.**
  *Evidence, 2026-08-09,* `tools/device-graphics.sh --only d3d11` on the device:

  ```
  VESSEL-GFX api=d3d11 bits=64 result=PASS in=ffff0000 out_a=ff0000ff out_b=ff0000ff
  VESSEL-GFX api=d3d11 bits=32 result=PASS in=ffff0000 out_a=ff0000ff
  adapter="Adreno (TM) 829 (unknown)" vendor=0x5143 feature_level=12_0
  ```

  Cleared to blue, one red triangle, read back, three pixels checked by value.
  That is FEX → ARM64EC → DXVK → winevulkan → adrenotools → Turnip → KGSL
  proven end to end. Headless: these probes create no swapchain, which is why
  the item below is separate rather than the same item.

- [x] **32-bit D3D11, which failed for a reason that never looked like OpenGL.**
  `WINEDLLOVERRIDES` sets `opengl32=n` for every process, `wined3d` imports
  `opengl32`, and the OpenGL package shipped `system32/` only — so a 32-bit
  program died with `cannot load d3dcompiler_47.dll`. `build/zink.sh` builds
  twice now, arm64ec into `system32` and i386 into `syswow64`, the same two-pass
  shape `dxvk.sh` and `vkd3d.sh` already used. `tools/device-graphics.sh` was
  never copying the package's `syswow64` half into the prefix either.

- [x] **`MESA_VK_WSI_DEBUG=sw`, which is not a debug switch on this build.**
  Mesa's X11 WSI is one file with two halves and the DRI3 half is entirely
  behind `#ifdef HAVE_X11_DRM`, which `meson.build` defines only when
  `with_dri_platform == 'drm'` — never, for a KGSL build with no gallium driver.
  Read out of the shipped `libvulkan_freedreno.so`: `xcb_put_image` is present
  and there is not one `xcb_dri3_*`, `xcb_present_*` or `xcb_shm_*` reference.
  Half a WSI was compiled and it is the software half. Unset, a present measures
  12.8 ms — which is `__builtin_unreachable()` running off the end of a
  function, not a slow path.
  *The note this replaces blamed KGSL for being unable to export a dma-buf.*
  Wrong twice: that code is not compiled in, and `/dev/dma_heap/system` opens
  and allocates from this app's uid anyway — `open=OK alloc=OK bytes=3686400`.

- [ ] **No D3D program has drawn into a window, and the cause is now known and
  is not what any previous note said.**

  **Turnip was built as an Android Vulkan HAL.** Its only dynamic symbol is
  `HMI`, so only Android's `libvulkan.so` can load it — and that loader does not
  forward the window-system layer. It implements
  `vkGetPhysicalDeviceSurfaceCapabilitiesKHR` and `vkCreateSwapchainKHR` itself
  against its own `ANativeWindow`-backed surface, and casts an X11 surface
  handle to the wrong type. `tools/gfx/x11present.c` reaches `surface created`,
  then takes a SIGSEGV whose top two frames are in `libvulkan.so` — the same
  failure, in the same call, that Wine reported as
  `err:vulkan:vkGetPhysicalDeviceSurfaceCapabilitiesKHR Exception 0xc0000005 in
  Unix call`. The X11 WSI was in the driver the whole time and nothing could
  reach it.

  Built as an ordinary ICD instead — `VESSEL_TURNIP_ICD=1 ./build/turnip.sh`,
  which drops `android` from `-Dplatforms` and exports
  `vk_icdGetInstanceProcAddr` — a swapchain presents to Vessel's own X server:

  ```
  VESSEL-X11PRESENT device="Adreno (TM) 829" driver_id=18  swapchain images=3
  result=PASS wsi="sw" 1280x720 frames=400
    mean_ms=2.245 p50_ms=1.632 p95_ms=4.945 fps=445.5
  ```

  **~2.4 ms a frame, about 14% of a 60 Hz budget**, end to end including the
  Java X server. That is the present baseline this project never had.
  *Done when:* a windowed D3D11 program draws through the ICD inside a session.

- [x] **Wine can load the ICD.** `patches/wine/0009` tries the ICD entry point
  first and falls back to the adrenotools path, exactly as this note predicted:
  `VESSEL_VULKAN_ICD` is dlopen'ed by absolute path and kept only if it exports
  `vk_icdGetInstanceProcAddr`. The one wrinkle the note did not foresee is that
  `vkGetDeviceProcAddr` cannot be resolved at dlopen time — an ICD exports no
  other Vulkan symbol and answers a NULL instance for the four global commands
  only — so the instance is caught in a `vkGetInstanceProcAddr` wrapper and the
  device entry point resolved from it on first use.

  The ICD package is now in the sideload bill of materials. Verified on device
  before the Wine side existed, in a plain `linker64` process as the app's uid:
  `dlopen` ok, loader interface version 5, `VK_KHR_xlib_surface` advertised,
  instance created, `Adreno (TM) 829` enumerated at api 1.4.358.

- [x] **A D3D program draws in a session, and the HAL package is retired.**
  Metro 2033 Redux renders through the ICD inside a real container — the thing
  no D3D program had ever done. So the HAL left the sideload bill of materials:
  it cannot present to a window, and `adoptLatest` would never pick it over the
  ICD's higher versionCode anyway. It still builds by default and still sits in
  `dist/`; both `GpuProbe` and `patches/wine/0009` ask the *file* which shape it
  is (`vk_icdGetInstanceProcAddr` present → ICD, else libadrenotools), so
  installing one by hand still works.

  `GpuProbe` had to learn the ICD shape in the same change or it would have
  reported a working driver as absent — it reaches drivers through
  libadrenotools by construction, and the container now references the ICD.

## The graphics bugs Metro exposed

Running an actual game found four things at once. Two are fixed; two are not,
and the two that are not share a root cause.

- [x] **A stopped program kept its taskbar button for the rest of the session.**
  `OnWindowModificationListener` has **no destroy callback** — the taskbar only
  ever kept up because `destroyWindow` unmaps first and *that* publishes. But
  `unmapWindow` is guarded by `isMapped()`, so destroying an already-unmapped
  window notified nothing and `removeAllSubwindowsAndWindow` took it out of the
  tree in silence. Two ways in: stopping a minimised program, and any child of a
  destroyed window, since the recursion unmaps none of them. Fixed by listening
  to `onFreeResource`, which fires once per window removed, mapped or not.

- [x] **The long-press window menu could not be opened.** The taskbar hides
  itself after 4 s and the menu was state *inside* the taskbar button, so the
  timer disposed the button mid-press and took the menu with it. Moved beside
  the launcher, which had already solved exactly this, and added to the linger
  effect's condition. Redrawn as a panel with a row of icon buttons, and given
  the scrim it needed to be dismissable.

- [ ] **A game that minimises itself cannot be restored, and Maximize cannot be
  built.** Both are the same missing piece. Measured on device: Metro minimises
  itself on focus loss — open `cmd` and the game's X window goes `1280x720` →
  `160x46`, which is Wine shrinking it to the Win32 iconic size. It stays in the
  taskbar, but tapping it does nothing, and Wine says why:

  ```c
  if (data->current_state.wm_state == IconicState) style |= WS_MINIMIZE;
  ...
  if (style & WS_MINIMIZE) return FALSE;      /* can_activate_window() */
  ```
  `dlls/winex11.drv/event.c:445`

  Wine refuses to activate a minimised window, and the vendored server sets **no
  `WM_STATE` at all** (zero hits across `com/winlator/xserver/`), so there is no
  protocol-level way to say "restore". Wine is running *unmanaged* — it is
  authoritative over its own geometry, which is also why a WM-initiated resize
  would not give us Maximize.

  *The fix is one piece of work for both:* enough ICCCM/EWMH that Wine treats the
  session as managed — `_NET_SUPPORTING_WM_CHECK`, `_NET_SUPPORTED`, and a
  maintained `WM_STATE` — after which restore is setting `WM_STATE` to
  `NormalState` and activation is `_NET_ACTIVE_WINDOW`. **The risk is the point:**
  flipping Wine from unmanaged to managed changes geometry and focus handling for
  every window, not just the one being restored, so it needs on-device retesting
  of the cases that work today. *Done when:* a fullscreen game survives a round
  trip to `cmd` and back, and Maximize is a control that does something.

- [x] **The white bar is gone: no top-level window has a caption any more.**
  *Evidence, 2026-08-10, Metro 2033 Redux in a real session on a clean install.*
  `patches/wine/0010` clears `WS_CAPTION|WS_THICKFRAME` in win32u's own
  style-correction block, and the tree says it took:

  ```
  before:  frame 1280x720+0+0   client 1274x673+3+44
  after:   frame 1280x720+0+0   client 1280x720+0+0
  ```

  The frame also stopped being 1286x746. Screenshotted: the game fills the
  window edge to edge and the white strip is not there. The taskbar reads
  *Metro Redux* with the game's own icon.

- [ ] **The drag borders move and size the X frame, and the guest ignores it.**
  Half of this works and the half that does not is the interesting half.
  *Measured the same sitting:* the Resize toggle appears in the long-press menu,
  the accent border draws around the window, a right-edge drag took the frame
  `1280x720+0+0` → `1047x720+0+0` (width only), a top-strip drag took it to
  `1047x720+0+149` (position only), and Done cleared the handles. Every one of
  those is the X server doing exactly what it was asked.

  **The picture did not change.** The client child stays `1280x720+0+0` inside
  the resized frame and overflows it, so the composited output still fills the
  original rectangle — confirmed by forcing a repaint, which redrew the new
  scene at the old full size.

  *This is the same root cause as the missing Maximize, and it was already
  written down two items below: Wine runs **unmanaged** here, so it is
  authoritative over its own geometry and does not act on a WM-initiated
  `ConfigureNotify`.* Resizing the frame without Wine's agreement was never
  going to move the client. **The fix is therefore the same single piece of
  work** — enough ICCCM/EWMH that Wine treats the session as managed — and the
  drag borders are the interface waiting for it rather than a separate feature.
  Until then the toggle should probably not be offered, or should say what it
  cannot do.

- [~] **A white bar across the top of a fullscreen game — and it is a fixed
  height in *guest* pixels, which rules out most of the candidates.**
  *Superseded by the two items above; kept for the measurement.*
  *Measured on the device, 2026-08-10,* by reproducing it at three different
  sizes in one sitting and converting each back to guest rows:

  | Desktop | Window | Bar, in guest rows |
  |---|---|---|
  | 1280x720 | fullscreen | ~38 of 720 |
  | 640x360 | fullscreen | ~42 of 360 |
  | 1280x720 | windowed, ~640 wide | ~43 |

  **~40 guest pixels every time**, independent of both the desktop resolution
  and the window size. That is the signature of a fixed-height decoration and
  not of anything proportional: a scaling artefact, a letterbox miscalculation
  or an off-by-one in the blit would all have moved with the geometry, and none
  of them did. A Win32 caption is ~30–40 px. The bar also spans exactly the
  window's width and sits *above* the rendered content, with the content pushed
  down — which is the "client offset down inside a correct parent" case rather
  than "window placed too low".

  Still `[~]` because the mechanism is inferred from geometry rather than read
  out. `dumpTree` now prints `WxH+X+Y` and the `_MOTIF_WM_HINTS` mask by name,
  so the confirming evidence is one `setprop log.tag.VesselWindows DEBUG` away:
  *Done when:* a dump shows the client child at a `+Y` of ~40 inside its parent,
  and says whether the parent carries `TITLE`. If it does, this is a Wine window
  style and the fix is on the Wine side, not in the compositor.

- [x] **The frame is pixel-bound, and "4 FPS in Metro" was never a property of
  the stack.** *Measured on the device, 2026-08-10, Metro 2033 Redux in a real
  session.* The resolution probe was run and it answered cleanly:

  | Render size | Scene | FPS |
  |---|---|---|
  | 1280x720 | title screen | **20–26** |
  | 1280x720 | intro video | **2** |
  | 640x360 | main menu | **60** (at the container's `fpsLimit`, so ≥60) |
  | ~640 wide, windowed | title screen | **56** |

  Quarter the pixels and the rate goes to the cap. **GPU/pixel-bound, not
  CPU/FEX-bound** — which retires the "the game is x86-64 so FEX is on the hot
  path" suspicion for this title, and matches `docs/ARCHITECTURE.md` already
  calling resolution the single biggest dial on this phone.

  *The 4 FPS figure was a sampling accident.* The readout is a live number and
  the earlier reading was taken while something slow was on screen. The intro
  video measures 2 FPS — it is CPU-decoded and blitted, so it is the one part of
  a Metro launch that resolution does **not** help — and loading screens are
  lower still. The rendered scenes were never that slow.

  The last row was unplanned and is the cleanest evidence of the four: after the
  640x360 run Metro saved that resolution as its own, so the next 1280x720
  session opened it *windowed at ~640 wide* — the game re-ran the experiment on
  itself, same desktop, same session, and landed at 56. Note this as a side
  effect: **Metro's own graphics setting was changed by this probe** and wants
  resetting in its Options menu if 720p is wanted back.

  *What this deletes:* the present-path work below stays deprioritised, but for
  a better reason than before. At 2.245 ms against a 40 ms frame it is ~6%, not
  ~1% — real, but still not the thing to fix first. What it promotes is anything
  that reduces pixels or GPU work per pixel.

## Cheap wins, queued behind attributing the frame

Everything here comes out of the present-path audit. Each is hours, not weeks,
and none of them should jump ahead of the resolution probe above — at 4 FPS the
whole present chain is ~1% of the frame, so these are worth doing when it is the
frame that is fast and the plumbing that is slow. File references are the
audit's; they are pointers, not independently re-verified.

- [ ] **Move Mesa's software fence wait off the application thread.**
  `wsi_common.c:2882-2885` blocks the app's `vkQueuePresentKHR` on the blit
  fence, but `x11_queue_present` only pushes to a queue
  (`wsi_common_x11.c:2249`) and the put-image runs on the present-manager thread
  (`:2510`, `:2559`). The wait belongs there, immediately before
  `x11_present_to_x11_sw`. Up to the **0.60 ms** blit latency off the app thread
  — 27% of the measured 2.245 ms mean, and the p95 of 4.945 ms suggests it does
  not always overlap with the next frame's CPU work. ~30 lines. Needs a
  `patches/mesa/` directory, which does not exist yet; `docs/UPSTREAM.md` has the
  shape. *Done when:* `run-presentbench.sh --wsi sw --frames 600` before and
  after, watching p95 rather than the mean.

- [ ] **Drop the per-present `GetGeometry` round trip.** One request and one
  reply per frame (`wsi_common_x11.c:1854` + `:1917`), on the present thread,
  ordered behind the 3.6 MB PutImage on the same connection, and used for
  nothing but detecting a resize (`:1919-1925`). Selecting `StructureNotify` and
  reading `ConfigureNotify` answers the same question for free. Small
  `patches/mesa/` patch, low risk, **win unmeasured** — it is on the present
  thread, so it may only bound that thread's rate. A `Trace` section around the
  reply would size it before the patch is written.

- [ ] **Back the window drawable with a `GPUImage` on the software path.**
  Today `Texture.updateFromDrawable` re-uploads the **whole window every frame**
  (`Texture.java:147`) — the damage rectangle is computed and then discarded into
  a boolean (`Drawable.java:194-199`). When a drawable's texture is a `GPUImage`,
  `Drawable.setData` repoints its `ByteBuffer` at the AHardwareBuffer's mapped
  memory (`Drawable.java:57-58`), the upload becomes a no-op
  (`GPUImage.java:46-49`) and the GL texture is an `EGLImageKHR` over the same
  buffer (`GPUImage.java:42`). Mesa's `xcb_put_image` would then memcpy straight
  into the buffer the compositor samples, and **3.6 MB of CPU→GPU upload per
  composited frame disappears**. The machinery already ships and is already used
  by `PresentExtension` and `DRI3Extension`; a plain `PutImage` simply never
  triggers it. Medium risk: the buffer is allocated `CPU_WRITE_OFTEN` only
  (`gpu_image.c:53`) and held locked (`GPUImage.java:32-35`), so any X op that
  *reads* window content becomes an uncached read, and stride must come from
  `getStride()` rather than being assumed equal to width. *Note:* this is not
  inside the 2.245 ms — it will show as composite headroom and lower bandwidth,
  not as present latency.

- [ ] **Measure the shader cache, cold against warm.** `docs/OPTIMIZATION.md:31`
  calls pipeline recompilation "the single largest avoidable cost in the whole
  stack". It was fixed (`SessionEnvironment.kt:430-433`) and explicitly left
  **Unmeasured** (`:61-63`).

  *This entry previously said the job was one hour of running
  `run-presentbench.sh` twice with `caches/dxvk` wiped between, because
  `device-bench.sh`'s refusal cited a headless harness with no DISPLAY. Half of
  that was right.* The refusal's **reason** was indeed stale — a D3D11 swapchain
  does now run against the app's own X server. But `presentbench.c` draws one
  `ClearRenderTargetView` and no shaders **on purpose**, so that its number is
  the present path; wiping the cache around it times DXVK's internal blit
  pipeline, not an application's shader set. The refusal in `device-bench.sh`
  has been rewritten to say that instead, so the next person does not rediscover
  it.

  What is actually needed is a shader-heavy workload: either a probe that
  compiles many distinct pipelines, or — better, and needing no new code — a
  real title timed launch-to-first-frame with `caches/dxvk` wiped between runs.
  Metro is already installed and is the obvious subject. *Done when:* two
  launch-to-first-frame times, cold and warm, from the same title.

- [ ] **`FEX_HALFBARRIERTSOENABLED=1` is an unmeasured setting wearing a measured
  number.** `docs/ARCHITECTURE.md:135` and `SessionEnvironment.kt:352-353` both
  justify it as "21% cheaper than the alternative" and cite `tools/tso/run.sh` —
  which never touches that variable. It toggles `FEX_HOSTFEATURES=disablelrcpc2`
  and nothing else (`run.sh:7, 93, 97`), and the 21% is the **LRCPC2** result
  (289.3 → 348.8 ms, `docs/ARCHITECTURE.md:84-87`). Fix is cheap: add a third
  pair to `run_set`. The harness has a control group already, so the answer would
  be trustworthy. Either the number moves to the right knob or the knob loses its
  justification — both are better than today.

- [ ] **`-mcpu=oryon-1` for Wine's unix side** (`docs/OPTIMIZATION.md:138-156`).
  Still undone and still right in principle, but **rank it last for games**: on
  the DXVK present path the unix side is only win32u's thin Vulkan thunk, and the
  heavy native code is Turnip, which already gets the flag. It matters for
  `winex11.drv.so`'s MIT-SHM blitter — the GDI path, not a game.

- [-] **`MESA_VK_WSI_DEBUG=sw,linear`** — tried on the paper argument, measured,
  and reverted. It removes a 0.60 ms GPU blit and is still ~14% slower on the
  mean and ~35% on the median, three runs of 400 frames each and the same
  direction every time: rendering into a linear image makes the GMEM resolve
  write an untiled layout, which costs more than the blit it saves.

- [ ] **Zero-copy present. Specified, unstarted, and both halves proven
  separately.** The route is DRI3 `BufferFromPixmap`: the vendored X server
  already hands back the window's AHardwareBuffer dma-buf fd over `SCM_RIGHTS`
  and `presentPixmap` already has the flip branch, and `tools/gfx/wsiprobe.c`
  proved Turnip imports that fd and binds a `TILING_LINEAR` image to it at
  rowPitch 5120. Two things that were assumed and are false on this part:
  gralloc returns a **tight linear** buffer for `GPU_COLOR_OUTPUT` alone, so
  there is no UBWC problem; and `VK_EXT_external_memory_host` is absent, so
  Mesa's MIT-SHM path was never reachable. Needs a `patches/mesa/` WSI image
  type plus `xcb-dri3`/`xcb-present` in the package. **Note the direction**: the
  server's `pixmapFromBuffer` mmaps the client's fd on the CPU, so the other
  DRI3 direction is one copy, not zero.

- [ ] **FEX asserts on any large PE inside a container, and it blocks the test
  loop.** x86-64 and ARM64EC binaries over roughly 200 KB die at
  `FEXCore::Assert::ForcedAssert` — `hlt #1`, `libarm64ecfex.dll` RVA
  `0x1766E4` — during `BTCpuProcessInit`, before FEX logs anything.
  `hello-x86_64.exe` (88 KB) runs in the same container with the same
  environment; `d3d11probe-x86_64.exe` runs in the standalone `files/graphics`
  prefix and not in the app's container. Only two `ERROR_AND_DIE` sites exist in
  FEX's Windows layer — `"Unhandled relocation"` and `"Couldn't detect CPU
  features"`. Undiagnosed, and it is why every D3D result above comes from the
  standalone harness rather than from a session.

- [ ] **At session start the desktop background is black until something
  repaints it.** Fixed in code and not yet watched: a texture is uploaded at
  allocation and after that only on damage, and the desktop's background paint
  lands in the gap. `GLRenderer.updateScene` now distrusts every texture in the
  scene whenever the scene is rebuilt. *Done when:* a program is launched with
  the session and the area around its window is `#161826` in a screenshot.

- [x] **The prefix had no `C:` drive at all, and it was one cause with a long
  tail.** `server_init_process_done` creates `drive_c` and `dosdevices/c:`
  **only** on the pass that creates `dosdevices` itself; Vessel maps shared
  storage before the first boot, so `mkdir` returned `EEXIST` and Wine skipped
  the block for the life of the prefix. That produced 773
  `setupapi:create_dest_file failed … (error=3)` lines, `wineboot: Cannot set
  the dir to L"C:\windows" (2)`, and `Couldn't start services.exe: error 267`
  — ERROR_DIRECTORY. It needed the storage permission to be granted *before*
  the container was created, which `+` has now made the normal path.
  *Evidence:* `DriveMap.ensureSystemDrive` repaired a broken container on the
  device — `c: -> ../drive_c`, 852 files in `system32` — and a fresh container
  built the same way. A boot log went from 918 lines and 796 errors to **201
  lines and 5**.

- [x] **`RpcSs` would not start inside the app.** It was the missing `C:` drive:
  `services.exe` could not start at all with `error 267`, so nothing it hosts
  could. No separate fix was needed and the `dlls/combase/rpc.c:229` lead was a
  dead end.
  *Evidence:* `ps` in a session now lists `rpcss.exe`, `services.exe`,
  `plugplay.exe` and `svchost.exe -k LocalServiceNetworkRestricted`.

- [x] **The taskbar drew a letter where a program has an icon.** It draws the
  real icon now, from the shortcut when there is one and from the prefix by name
  when there is not.
  *Worth recording rather than chasing:* five of the programs on the launcher's
  built-in row carry **no icon resource at all** — `control.exe`,
  `explorer.exe`, `oleview.exe`, `write.exe` and `wineconsole.exe` have only a
  manifest, checked by reading their PE resource directories. `cmd`, `notepad`,
  `regedit`, `taskmgr`, `winecfg` and `winemine` do have one. A glyph for the
  first five is the right answer, not a fallback.

- [ ] **The guest has working sockets and no network adapters.** Unchanged in
  substance: a WinHTTP `GET` returns 200 and `ipconfig` prints nothing, because
  Android denies an untrusted app `bind()` on a `NETLINK_ROUTE` socket.
  `patches/wine/0007` is built and its rate-limiting half is confirmed working —
  the log carries **one** `nsi:poll_events bind failed, errno 13;
  address-change notifications are off.` where there used to be fifty. Its
  `getifaddrs` half is still unproven: the count it logs is on the `nsi` warn
  channel, which `WINEDEBUG` does not enable, so the fallback has never been
  read. *Done when:* a session runs with `+nsi` and either `ipconfig` names an
  adapter or the log says `getifaddrs found 0` — the second answer closes the
  Wine-patch approach for good and moves it to the Android side.

## 2. Self-sufficient install

- [x] **Bundle the `.wcp` packages into the `sideload` flavour.**
  *Evidence, 2026-08-09:* `adb uninstall` then `adb install` of the signed
  release APK, twice. The setup dialog unpacked 180 MB of assets with no
  network, downloaded Git from the components release, and a container reached a
  desktop — no script run, no file pushed by hand. The APK is 108 MB and carries
  Wine, DXVK, vkd3d, Turnip, Zink and FEX.

- [x] **First-run setup progress UI.**
  A named-step checklist with per-component byte counts, in the launch dialog
  rather than on a screen of its own. *Evidence:* watched on a genuinely fresh
  install — `64% · 115 MB of 180 MB read`, then a row per component with its
  file count and unpacked size.

## 3. The launch-type matrix

One program of each kind, launched from the app's own UI and observed — not
inferred from an exit code.

All nine were added and launched from the app's own UI on 2026-08-08, from
`C:\users\u0_a443\Downloads` in the one provisioned container, with Turnip on.
(`u0_a443`, not `vessel`: Wine takes the profile name from the unix user, and
the unix user is the app's uid.)

- [x] `.exe` ARM64 — `IV VESSEL-OK bits=64 sum=333338333350000 argc=1`, after
  `Loaded L"C:\users\u0_a443\Downloads\hello-aarch64.exe" … native`.
  **No taskbar entry** — a console program that exits in milliseconds, so there
  is nothing to dock. See the taskbar defect below, which is a separate matter.
- [x] `.exe` x86-64 — `VESSEL-OK bits=64`, after
  `Loaded L"…\libarm64ecfex.dll"` and FEX's own
  `D F4 Load module hello-x86_64.exe (…): 140000000`. No taskbar entry, same
  reason.
- [x] `.exe` x86-32 — **the row that had never been seen working, works.**
  `VESSEL-OK bits=32 sum=333338333350000 argc=1`, after `wow64.dll`,
  `Loaded L"C:\windows\system32\libwow64fex.dll"`, `wow64win.dll`, and FEX's
  `D EC Load module hello-i686.exe (…): 400000` / `Load module ntdll.dll (…):
  7BF40000`. The 32-bit `ntdll` at `7BF40000` is the WoW64 side really being
  built. No taskbar entry, same reason.
- [x] `.bat` — `cmd.exe` loaded, `IW VESSEL-BAT-OK` in the log.
  *One real defect found:* `echo VESSEL-BAT-OK > C:\vessel-bat.txt` produced
  `Invalid name.` and no file. Wine's `cmd` refuses a redirect to the drive root
  here; the same script's `echo` to the console worked. Not Vessel's bug, but it
  is what a `.bat` writing to `C:\` will do on this build.
- [x] `.msi` — `IV exec … wine msiexec.exe /i C:\users\u0_a443\Downloads\
  vessel-hello.msi`, then `msiexec.exe`, `msi.dll`, `cabinet.dll`,
  `wintrust.dll`, `comctl32.dll` from `winsxs`, and finally `winex11.drv` +
  `uxtheme.dll` — i.e. it got as far as building a window. *Not proven:* the
  payload is not in `C:\Program Files` afterwards, so it reached its UI and did
  not complete an install. The MSI is a minimal one built by `out/matrix/
  mkmsi.py`; whether the fault is the package or `msi.dll` is untested.
- [x] `.vbs` — `wscript.exe` loaded `vbscript.dll` and `scrrun.dll` and **drew a
  real window on the desktop**, screenshotted. The dialog is the partial-WSH
  caveat made concrete: it is a script error at
  `C:\users\u0_a443\Downloads\vessel-hello.vbs(3, 1)`, which is the
  `FileSystemObject.CreateTextFile(…).WriteLine` line. So `wscript` runs, the
  engine parses, and a real script stops part-way exactly as the caveat says.
- [x] `.ps1` — **refused.** Selecting it disables "Add as app" (`enabled="false"`
  on the button in the accessibility tree) and prints, in warn colour above the
  listing: *"Wine ships a stub PowerShell that cannot run scripts — this needs a
  real PowerShell, which Vessel does not include."*
- [x] `.sh` — **never offered.** "Add as app" disabled, and no refusal banner:
  it is `NotAProgram`, which is a different statement from a refusal and is the
  right one.
- [x] Linux ELF — same, using a real aarch64 Android ELF
  (`out/vulkan/vkdriverprobe`). Disabled, no banner.

**The taskbar never lists anything, and that is a defect of its own.** With
`wscript`'s dialog visibly on the desktop, the bar still read *"Nothing has
opened a window yet."* `DisplaySession.publishWindows` listed mapped, named
children of the **root** window; under `explorer /desktop=vessel,1280x720` every
guest window is a child of the virtual-desktop window instead, so the root had
exactly one child and it was the desktop.

**Code fixed, not yet watched working.** `publishWindows` walks down now: a
named mapped window is an entry and the walk stops there, and a window is a
container — descended into, never listed — when it has no name or when something
named and mapped is underneath it. That second rule identifies the virtual
desktop as *the window with the programs inside it*, without hard-coding
`WINE_DESKTOP` or matching on geometry; an empty desktop, where it cannot fire,
is caught by being a full-screen direct child of the root instead. The walk is
depth-bounded so a client nesting windows without limit cannot recurse it off
the X server's thread. **What still has to be confirmed on the device** is that
a `wscript` dialog now produces exactly one button and that a program with a
client-area child produces one rather than two.

The last three are the point, not an afterthought. They cannot work here —
Android is bionic, not glibc, and Vessel ships FEX as Wine's two translation
DLLs, not FEXLoader — so the test is that the UI never presents them and never
fails silently. Supporting them would mean a glibc rootfs and proot, which is a
different product.

## 3a. Found while running the matrix

Four things the interface said that were not true, and one that still is.

- [x] **There was no way back to a running desktop.** Back out of it once and
  neither the container card's Launch button nor `am start --es openSession`
  could return: `SessionViewModel.launch` refused for any non-IDLE phase and
  `SessionHost` navigates only on the *edge* into RUNNING. The card read "never
  launched" beside six live Wine processes. Fixed in `cec23d4`; verified on the
  device — Back, then Launch, and the route and the landscape lock both come
  back.
- [x] **`exec ${'$'}{spec.commandLine}`** in the session log — one of six copies
  of that line had its `$` escaped, so every program launched into a running
  session logged the template rather than the command, and then ran perfectly.
  Two more of the same in the failure line beside it and in
  `XServerDisplay.focusWindow`. Fixed in `cec23d4`.
- [x] **Three tiles called `vessel-hello`.** Not duplicates — three distinct
  `executable` paths in `shortcuts.json` whose labels collided because the
  extension was always stripped. Fixed in `5c71ce2`; the tiles now read
  `vessel-hello.bat`, `.msi` and `.vbs`.
- [~] **A non-PE program wears an `unknown` architecture badge.** Truthful —
  there is no machine field in a batch file — and the wrong word. Three tiles in
  a row all reading `unknown` said Vessel had failed to understand three files
  it understood perfectly.

  **Fixed in code, not yet seen on the device.** `AppShortcut.badge` says `cmd`,
  `msiexec`, `wscript` or `shortcut`, from `interpreterFor(executable)`. Derived
  from the extension rather than stored beside the arch as first planned: an
  architecture has to be persisted because reading it means opening the file,
  and the extension is already in the path, so there is no schema change, no
  migration and nothing that can go stale. Colour stays `UNKNOWN`'s neutral grey
  — a script does not run natively and must not wear the green that says so.
- [~] **Returning to the desktop leaves a black surface.** Reproduced twice: the
  route is right, the orientation lock is right, the pixels are gone. Not the
  old Turnip wedge — see §1.

  **Cause found, fix not yet watched working.** It is not that there is no
  damage to replay; the window contents are still in the X server's drawables
  the whole time. Destroying the `SurfaceView` destroys the EGL context and
  every texture object in it, but the `Texture` objects survive holding
  non-zero ids — so `isAllocated()` answered yes, `updateFromDrawable()` skipped
  both the allocate and the upload, and the renderer bound texture names that no
  longer referred to anything. Every window sampled black, and an idle desktop
  never repaints itself to recover. Fixed with a static context-generation
  counter stamped onto each id when it is generated and checked by
  `isAllocated()` and `destroy()` — the latter because a fresh context issues
  names from 1 again, so deleting a stale id would very likely blank whatever
  now owns that number. `preserveEGLContextOnPause` is set as well, so the
  common case avoids the loss rather than recovering from it. Vendored item 13.
  **To confirm on the device:** leave the desktop and come back, twice, and
  check the pixels return without touching the guest.
- [x] **The bottom edge had no reveal handle** while the rail's left edge had a
  plainly visible one. `navigationBarsPadding()` was on the 4 dp mark rather
  than the 20 dp touch box around it, so the mark asked to reserve the
  navigation bar inside a parent with no room for it and was clipped away
  entirely. The bar was never missing from the code, it was laid out off-screen.
  It is also square now rather than a pill: Android draws its own gesture pill
  on that edge, centred, at very nearly the same width.

## 4. Real, not blocking

- [~] **`ComponentDownloadService` has no downloader.** It now has one, and it
  has never run on the device. `ComponentDownloader` fetches with resume
  (`Range`, and a restart when the server ignores it), verifies the registry's
  digest over the completed file, deletes the part-file on a mismatch so a retry
  cannot rebuild the same wrong bytes, reports progress, and returns a sentence
  for every failure. The service runs it as one queue behind a `dataSync`
  foreground notification with a Cancel action, then hands the archive to
  `ComponentStore.install`, which stages and swaps — so a killed process cannot
  leave a half-installed component the store reports as present. `play` refuses
  out loud, naming Play policy, because `CAN_INSTALL_COMPONENTS` is false there.
  *Evidence so far:* 11 tests against a real socket in the test source set
  (`ComponentDownloaderTest`) covering resume, an ignored `Range`, a digest
  mismatch, a 404, an unreachable host, an already-downloaded archive, and a
  path-traversing package id. *Not yet done:* nothing is wired to a screen (see
  `out/needs-from-install-agent.md`), and **nothing publishes `contents.json`**
  — see §6.
- [x] **`ComponentPackage` carries no `sha256` or `url`.** Both are on it now,
  nullable and defaulted so the store path is unchanged, with `isDownloadable`
  as the single predicate over the pair. `core/ComponentRegistry` parses
  `contents.json` and *refuses* an entry with no digest, a malformed digest, an
  unknown type or a non-`https` URL, returning the reason rather than dropping
  it. `core/Sha256` is the one definition of what "the same digest" means.
  *Evidence:* `ComponentRegistryTest` parses the repository's own
  `registry/contents.json` and asserts every entry in it is downloadable, so a
  `gen_registry.py` that stopped writing the field would fail here.
- [x] **Drive mapping.** Android storage is drives in the container, and Wine
  can see them. `D:` is shared storage, `+` opens Android's folder picker and
  takes the next free letter, a long press unmaps without touching what it
  pointed at, and a drive whose volume is unplugged drops out of the tab row and
  comes back with its letter when the volume returns.
  *Evidence, 2026-08-09:* a USB HDD mapped to `F:` from the picker and listed its
  six folders; Wine's own File Explorer listed `(C:) (D:) (E:) (F:)` and opened
  them. `E:` reading empty was the folder being empty — `ls -1` gives 0 entries
  and Android's picker says "No items" for the same path.
  *Two things this cost, both worth knowing:* a symlink is enough for Wine to
  *resolve* a drive and not to *show* one — `HKLM\Software\Wine\Drives` decides
  that, and the seed now derives it from `dosdevices` — and `/mnt/media_rw/<uuid>`
  is the same volume as `/storage/<uuid>` and unreadable even with
  `MANAGE_EXTERNAL_STORAGE`, which is Winlator-Ludashi#534 found independently.
  See `docs/DRIVE-MAPPING.md`, *What the design got wrong*.

- [x] **`Z:` and the `/` node handed the guest this app's private storage.**
  Wine maps the unix root to `Z:` and registers a shell namespace extension so
  the desktop tree carries a `/`. On Android the unix root is
  `/data/user/0/app.vessel` plus every other app's sandbox. Both removed —
  the symlink by `DriveMap.removeRootDrive`, the namespace key by the seed's new
  `[-key]` delete form.
  *Evidence:* `dosdevices` is `c/d/e/f`, the hive's `Drives` key matches with no
  `z:`, and Wine's desktop tree is My Computer / Documents / Trash.
  *What it cost, and this is the one to remember:* **`wineboot --update` re-runs
  `wine.inf` after `regedit`, so anything the seed deletes comes back and
  anything it sets that `wine.inf` also sets is overwritten.** The namespace key
  was deleted, recreated by the boot, and `/` was still on screen with the
  registry saying it had gone. The seed is applied a second time after the boot
  passes now. Every seed value `wine.inf` also touches was subject to this and
  nobody had looked.

- [x] **No fonts bundled.** Inter and JetBrains Mono are in the APK *and* drawn.
  `VesselTheme` references them with `FontVariation.weight` per entry — without
  which a variable font renders at its default instance and every "medium" in
  the product silently draws at 400 with nothing looking broken.
  *Evidence:* screenshotted on the device.

- [x] **No program icons.** `PeIconReader` feeds the tiles through
  `data/ProgramIcons`, which resolves a shortcut's guest path against the drive
  it names, caches by path and mtime, caches negatives as hard as hits, and
  decodes one at a time off the composition thread. The lettered placeholder
  stays the fallback.
  *Evidence:* `regedit` and `notepad` tiles on the device draw Wine's own icons.

- [x] **`RpcSs`.** Closed by the `C:` drive fix; see §1.
- [x] **Deleting a container deleted the user's mapped folders.** The most
  serious defect this project has had. `File.deleteRecursively()` walks with
  `listFiles()`, and `listFiles()` on a symlink to a directory returns the
  *target's* children — and a container's `dosdevices` is nothing but such
  symlinks. Deleting a container emptied the phone's shared storage and every
  mapped folder, removed the now-empty links, and reported success. Reported as
  downloaded games disappearing, twice.
  `core/deleteTree` replaces it at all ten call sites: `Files.walkFileTree` does
  not follow links unless asked, so a symlink is visited as a file and unlinked.
  `ContainerRepository.delete` also unmaps every drive first, because the two
  failures are independent. `DeleteTreeTest` covers a symlinked directory, a
  symlinked file, a symlinked root, a dangling link and a real nested tree;
  every case fails against the old call.
- [x] **Wine processes surviving a reinstall — and the diagnosis was wrong.**
  Measured: `am force-stop` and `kill -9` of the app process each took the whole
  Wine tree with them, because Android kills the process group. What survived
  was `u0_a443`, the **previous installation's** uid, which the current install
  cannot signal. Those go on reboot and nothing here can hurry it.
  `GuestProcessTree.killOrphans()` went in anyway, called before a session
  starts — the one moment anything of the guest's that is alive is by definition
  an orphan — because the reachable case is a session that ended without
  teardown while the app kept running, and a leftover `wineserver` holds the
  sockets the next session needs. Every kill is logged at WARN.
- [ ] **Move the `drive_c` reader out of `ui/vm`.** `FilesViewModel` reads the
  prefix directly with `java.io.File`, which is correct but belongs in `data/`
  alongside the import/export copies. A decision, not an oversight.
- [x] **Every drive, not just `C:`.** `GuestPath.upTo` and `parentOf` wrote the
  literal `C:`, so the first breadcrumb crumb navigated to `C:\` from any
  drive; the browser then resolved that against whichever drive was open, listed
  one drive under another drive's name, and built every row's path as `C:\…`.
  Downstream that made "Add as app" store a shortcut to a path that does not
  exist, made Import and Export look C:-only, and made the `D:` tab do nothing.
  One cause, four reports. `AppSheetViewModel` also resolved every executable
  against `drive_c`, refusing a real file with "there is no file at
  `D:\Games\…` on this container's C: drive".
- [x] **The shell's window actions.** Long-pressing a taskbar button offers
  Minimize, Close and Force close. Close sends `WM_DELETE_WINDOW` — the vendored
  X server had no `ClientMessage` event at all, so `events/ClientMessage` and
  `Window.requestClose()` are new — which `winex11.drv` turns into `WM_CLOSE`,
  so a program still gets its "save changes?" dialog. Minimize unmaps, which is
  what iconifying *is*, and keeps the button in the bar: Wine's desktop has no
  taskbar, so an unmapped window would otherwise be unreachable.
  *Maximize is deliberately absent* — Wine draws its own caption with a working
  maximise button, geometry inside the desktop is Wine's WM's job, and
  `_NET_WM_STATE_MAXIMIZED` needs an EWMH-aware WM which Wine-as-client is not.
- [x] **A frame-rate readout.** `GLRenderer` counts composited frames;
  `XServerDisplay` turns two reads and a clock into a rate twice a second. The
  taskbar draws 20 seconds of history behind the number, coloured against the
  container's own `display.fpsLimit`. The session metrics get a `frames` card
  whose headline is the **1% low** — the mean of the slowest 1% — because `min`
  is 0 in every run that ever dropped a frame.
  *Evidence:* watched on the device.
- [x] **Git, and the shell that cannot work here.** The component installs and
  `git version 2.55.0.windows.3`, `ls (GNU coreutils) 8.32`, `sed (GNU sed)
  4.9`, `awk` and real pipelines all run from `cmd` — `ls --version | grep -i
  coreutils` is two x86-64 MSYS2 processes under FEX with cmd's pipe between
  them.
  **Git Bash is not offered.** `bash.exe --login -i` starts and hangs: parent
  waiting, child spinning a whole core at 98.6% with `wchan` 0 and state `R`,
  which is MSYS2's `fork()` emulation deadlocking under Wine. `/etc/profile`
  forks on its way in and bash forks for every external command, so there is no
  version of this that works. `git-bash.exe` fails earlier and more quietly —
  it launches `mintty`, which wants a pty from the same DLL. The whole
  measurement is on `TerminalProfile`'s doc so nobody re-tries it.

## 5. Performance

`docs/OPTIMIZATION.md` is the ranked audit and the measured baseline. Closed
with evidence:

- [x] **Shader caches pointed at directories that did not exist** — fixed; the
  three cache paths are part of the layout and created at launch.
- [x] **Turnip never loaded inside Wine** — fixed; `driver_id` 8 → 18, Mesa
  26.3.0-devel answering.
- [-] **LTO** — does not link for ARM64EC in llvm-mingw, in FEX *and* DXVK, all
  ~40 undefined symbols tagged `(EC symbol)`. A toolchain limitation, not a
  project one. Kept as switches so a toolchain bump is one command.
- [-] **Big-core affinity** — pinning to the two 3.80 GHz cores is **19%
  slower** than leaving the scheduler alone. A concrete reason not to add the
  setting every phone emulator grows.

- [x] **Turnip could not be switched on at all.** It is on now
  (`SessionEnvironment.TURNIP_ENABLED`), and the thing stopping it was never
  `patches/wine/0006`: the APK's `libadrenotools.so` exported three data symbols
  — `android_get_exported_namespace`, `android_link_namespaces`,
  `android_link_namespaces_all_libs` — whose names belong to dynamic-linker
  *functions*, so a caller's PLT bound to a pointer's address and branched into
  `.bss`. Wine turned the fault into `STATUS_ACCESS_VIOLATION`, unwound the
  syscall, and left `display_lock` held across `get_vulkan_gpus()` for ever.
  One line of visibility in `app/src/main/cpp/adrenotools/CMakeLists.txt`.
  *Evidence:* `7f883cb`, and §1's desktop item.

Still open:

- [ ] **DXVK/vkd3d draw throughput** and **shader-cache cold vs warm** —
  unmeasurable until item 1.3, which now has a named cause and a named next
  step. `tools/device-bench.sh` was not run: with no D3D device there is nothing
  for it to time that `docs/OPTIMIZATION.md` does not already have.
- [ ] **`-mcpu=oryon-1` for Wine's unix side.** Valid there even though it is
  not for `CROSSCFLAGS`: `CFLAGS` reaches only the arm64 host build. Would tune
  `ntdll.so`, `win32u.so` and `winex11.drv.so`. An hour's rebuild for a win the
  current harness can barely see, so it waits for a benchmark that can.

## 6. Before the repository goes public

The remote is set (`prskid1000/Vessel`), everything is pushed, and
**v0.2.0 is published** with a signed 108 MB APK that carries all six
components.

- [~] **`docs/LICENSING.md` blockers.** Eight of ten closed; that document now
  ends in a table with the status and the evidence for each. What was found and
  fixed: `LICENSE` still repeated the retracted "libadrenotools is LGPL-3.0"
  claim; the APK contained no copy of the LGPL at all, which section 6 requires
  independently of everything else; and the vendored tree's list of local
  modifications was missing `cpp/winlator/CMakeLists.txt`. The vendored tree was
  diffed against upstream file by file — 13 modified, 143 byte-identical, 0
  without an upstream counterpart, every difference marked — by
  `build/verify_vendored.py`, which is now in the repository so the claim stays
  checkable. `LicensingTest` asserts the offline half of all of it.
  *Both closed on 2026-08-09:*
  - [x] **Prominent notice, in the interface, that the app contains LGPL code.**
    A permanent line at the foot of home — present in the empty-device state and
    the full one — naming the Winlator X server and the LGPL 2.1, opening a
    Licences screen with five entries and each licence's full text out of
    `res/raw`. libadrenotools' BSD-2-Clause notice is in the APK for the first
    time. *Evidence:* screenshotted on the device; `LicensingTest` asserts the
    words are in the screen, that home reaches it, and that every `R.raw` the
    list names is a real non-empty file.
    *Worth recording rather than quietly fixing:* a release was published while
    this was open, which is exactly the state the item said not to distribute in.
  - [x] **A source offer on the component release page.** `build/source_offer.py`
    renders the release body from each package's own provenance — component,
    version, upstream repository, ref, commit, `patches/<name>/` — and
    `_component.yml` writes it with `gh release edit` after each publish.
    Generated rather than hand-written because a hand-maintained list goes stale
    on the first pin bump, and a stale source offer is worse than none.
    *Unproven until a component build actually runs.*
- [x] **Line-ending corruption in `native/wine`.** It was not line endings. All
  53 modified files were `100755 -> 100644` with an empty diff: the clone had
  `core.filemode=true` from being made through a bind mount, and
  Git-for-Windows reports 0644 for everything on NTFS. The `.bmp` fixtures are
  simply among the files Wine marks executable, which is why it looked like
  binary corruption. `core.autocrlf=true` was a second, separate defect that had
  not bitten yet — `git diff` warned that LF would become CRLF for 37 more
  files, `configure` and `tools/make_makefiles` among them, on the next touch.
  Fixed in `harden_checkout()` in `build/common.sh`, which owns every upstream
  checkout; `assert_pristine()` now refuses to apply patches to a dirty tree.
  *Evidence:* `native/wine` went from 53 dirty entries to 0 with `git diff
  --stat` empty and no CRLF warnings, and all five patches then applied leaving
  exactly the five files they touch. dxvk (2), fex (24), vkd3d (4) and mesa
  (149) were in the same state and are now 0.
- [~] **Nothing publishes `registry/contents.json`.** It does now, in code.
  `_component.yml` downloads the release's own `.wcp` files back, runs
  `build/gen_registry.py` over all of them, and uploads `contents.json` beside
  them — from the release rather than from `dist/`, because a component build
  produces one package and the index has to name every one. The job is
  serialised on a `components-release` concurrency group, or two components
  finishing at once would each publish an index missing the other's package.
  *Still open until a build runs.* *Done when:* a `components` release carries a
  `contents.json` that `ComponentRegistryTest`'s parser accepts with nothing
  refused.
- [ ] **Decide what happens to `Redesigning interfaces/`.** Untracked today:
  commit it as the design source, or ignore it. Checked for licensing on
  2026-08-08 and it is clean — the Nocturne design system from the sibling
  project, reference screenshots, and a generated `support.js` from the author's
  own tooling. Nothing third-party, so this is a taste decision and not a gate.
- [ ] **A README that is true.** Whatever the state is on the day, said plainly.

---

## Where things actually stand

*Last rewritten 2026-08-09, after a day on the device.*

The **CPU story is done and measured**: ARM64EC plus FEX costs 1.09x native on
integer and 0.99x on memory, x86-32 through WoW64 costs 2.28x, and all three run
from the app's own launcher.

**The middle has moved, and it moved a long way.** A Windows program renders
through D3D11 and reads its pixels back correct, in both bitnesses. Separately,
a Vulkan swapchain presents to Vessel's own X server at 2.245 ms a frame. Those
are two halves of the same sentence and they have not been joined yet: joining
them needs Wine to load an ICD instead of an Android HAL, which is a small
change to `patches/wine/0006`, and it needs the FEX assert on large PEs solved
before it can be tested inside a session at all.

**Three recorded causes turned out to be wrong today**, which is worth more than
any of the fixes. The D3D blocker was never KGSL's inability to export a
dma-buf. It was not `HAVE_X11_DRM` either — that switch is real and needed, but
what actually killed a windowed program was Android's `libvulkan.so` owning the
WSI for a HAL-shaped driver. And the Wine processes surviving a reinstall were
from a previous installation's uid, not a teardown bug. Each of those had a
plausible story attached to it and each story was false.

**The shell is in good shape.** Drives, icons, the launcher, window actions, a
frame-rate readout, and a first-run install that needs no network. What is
missing is not shell work.

**One defect cost user data** — deleting a container followed the drive symlinks
and emptied the folders behind them. It is fixed, tested, and the rule it broke
was already written down in `docs/DRIVE-MAPPING.md` for a different function.
That is the failure mode to watch for in this codebase: a rule stated for one
call site and not applied to the next one.

Honestly unfinished: no D3D window; FEX asserts on large PEs in a container;
presentation is a CPU copy and zero-copy is specified but unstarted; `ipconfig`
still prints nothing and the patch that should fix it has never had its output
read; `.msi` packages reach their UI and do not install; and there is no sound.
