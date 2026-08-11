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

## The graphics bugs Metro exposed

Running an actual game found four things at once. Two are fixed; two are not,
and the two that are not share a root cause.

- [x] **A game that minimises itself cannot be restored, and Maximize cannot be
  built.** Both are the same missing piece. Measured on device: Metro minimises
  itself on focus loss — open `cmd` and the game's X window goes `1280x720` →
  `160x46`, which is Wine shrinking it to the Win32 iconic size. It stays in the
  taskbar, but tapping it does nothing, and Wine says why:

  **Fixed — confirmed on the device by the reporter, 2026-08-10**, on the build carrying `patches/wine/0012`, the DRI3 present path (`patches/mesa/0006`, `0007`) and SGSR. Which of those is responsible was not isolated per symptom; the record below is what was believed before the fix and is kept because several of its explanations were wrong and worth not repeating.

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

  *Amended 2026-08-10, and the amendment is the useful part.* "One piece of work
  for both" quietly became "one piece of work for three" when the drag borders
  were written up as sharing this cause. They do not: managed mode plus
  `WM_STATE` was the entire fix for resize, measured on the device, with no
  EWMH advertisement present.

  *Amended again 2026-08-10, and this sentence was wrong.* Managed mode plus
  `WM_STATE` fixed **minimise and restore**, not resize. A WM-initiated resize
  still never reaches the Win32 side, because `window_update_client_config`
  refuses at `is_virtual_desktop()` — a second guard `patches/wine/0011` does
  not clear — while `window_update_client_state` has no such guard. What looked
  like a working resize was the *restore* applying the dragged geometry. See the
  white-region entry below for the trace. `WM_STATE` is also already maintained, so of the
  three things named above only `_NET_SUPPORTING_WM_CHECK` and `_NET_SUPPORTED`
  are actually outstanding. Whether *restore* needs them is still open —
  `can_activate_window` reads `wm_state`, which it now has — so the next step
  here is to test restore, not to write the advertisement.

- [x] **The Files control and Add-a-program are gated, and the gate works.**
  *Confirmed on the device 2026-08-10, on a clean install.* Both are disabled
  until the container has **both** drives, not just `C:` — see
  `HomeViewModel.drivesReady`. `D:` is the conditional one, because it is only
  mapped when all-files access has been granted, so without it the picker used
  to open on a prefix holding nothing the user had put there. Disabled rather
  than hidden, with the reason printed under the grid.

  Creating a container is now blocked outright while the permission is missing,
  which is the other half: the mapping happens once, while the prefix is built,
  so a container created without it is one nothing can ever be added to.

- [x] **Restore from minimised — works.** *Confirmed on the device 2026-08-10.*

  The note below is the state before that, kept only because it is a good
  example of why a contended device is not evidence: the earlier attempt was
  written up as inconclusive rather than as a defect, and it was right to be.

  ~~Restore from minimised is still unverified, and the attempt took the
  session down.~~ Minimize via the long-press menu works and is visible in the
  tree (`mapped=false 562x513+298+103`, button retained). Tapping the button to
  restore was not observed: the app went to the Android launcher mid-sequence
  and came back with a new pid and no Wine processes, with an **empty crash
  buffer** — so not a Java exception. A second probe run was using the device at
  the same time, which is the most likely explanation and is exactly why it is
  not being written up as a defect. *Done when:* minimise and restore are driven
  with nothing else touching the device, and either the window comes back or
  `can_activate_window` is shown refusing it.

- [x] **The white region on resize — fixed, and confirmed on the device.**
  *2026-08-10.* `patches/wine/0012` built into `wine-11.14-canoe.wcp`, installed,
  and watched: a border drag now repaints the newly exposed area immediately,
  with no minimise/restore. Confirmed by the reporter, who is the person who
  found the symptom and the only one who had been able to reproduce it reliably.

  **A pass here cannot be a stale-binary artefact, which is why one report is
  enough.** The pre-patch code *cannot* produce this behaviour:
  `expose_window_surface` intersects the exposed rectangle with the
  **pre-resize** `surface->rect`, so the new strip is clipped away before
  anything is flushed, and `window_update_client_config` refused before the
  Win32 window could be resized at all. There is no path by which the old
  binary repaints on a drag. Seeing the repaint is therefore proof the new one
  is running — the failure mode of a stale component is "still broken", not a
  false pass.

  Three explanations were wrong before this one — a Win32 caption, a compositor
  coverage gap, and a missing X `Expose` — and this one was not a fourth guess:
  it was a trace diff taken on the device.

  **Wine never resizes the Win32 window when the window manager resizes the X
  window, because `window_update_client_config()` returns 0 at
  `if (is_virtual_desktop()) return 0;`** (`dlls/winex11.drv/window.c:1749`), and
  every Vessel session runs `explorer /desktop=`, so that is unconditionally
  true. The X window and its freshly allocated backing `Drawable` grow; Wine's
  window surface stays the old size; the newly exposed strip is painted by
  nobody.

  *Measured with `WINEDEBUG=-all,err+all,+x11drv,+event` on notepad, by dragging
  the shell's right resize border and then minimising and restoring the same
  window in the same session.* Per drag step:

  ```
  X11DRV_Expose win 0x1006a (1800001) 0,0 685x513
  warn:x11drv:handle_state_change window 0x1006a/1800001
       unexpected config (298,103)-(983,616)/90
  X11DRV_Expose win 0x1006a (1800001) 0,0 685x513
  ```

  and then, at the end of the drag, `X11DRV_GetWindowStateUpdates … state_cmd 0,
  swp_flags 0`. **No `config changed` line, and no `X11DRV_WindowPosChanging`,
  ever.** The restore, for the same window, one gesture later:

  ```
  x11drv:window_update_client_state restoring win 0x1006a/1800001
  X11DRV_GetWindowStateUpdates … state_cmd 0x1f120, rect (298,103)-(1162,616)
  X11DRV_WindowPosChanging hwnd 0x1006a … window (298,103)-(1162,616)
  ```

  — the Win32 window finally takes the dragged 864x513, its edit control goes to
  `(0,0)-(864,438)` and its status bar to `(0,438)-(864,480)`, and the white is
  gone. `window_update_client_state()` carries **no** `is_virtual_desktop()`
  guard, which is the entire asymmetry: `SC_RESTORE` reaches win32u, whose
  branch calls `set_window_normal_placement()` with the host rect. That is the
  only path in the stack that copies the WM's geometry onto the Win32 side.

  *Why `Expose` was never going to work, so nobody tries it a second time.*
  `X11DRV_Expose` calls `NtUserExposeWindowSurface`, and `expose_window_surface`
  (`dlls/win32u/window.c:2436`) **ignores its redraw flags when the window has a
  surface** and only re-flushes existing bits, having first done
  `intersect_rect( &exposed_rect, &exposed_rect, &surface->rect )` against the
  *pre-resize* surface. The new strip is clipped away before anything is sent.
  Vendored modification 21 is also a literal duplicate: `changeWindowGeometry`
  already sends an unmasked `Expose` on every resize, which is why two identical
  `X11DRV_Expose` lines bracket each `ConfigureNotify` above.

  *And `patches/wine/0011` is incomplete, which is the part worth remembering.*
  Its own comment says it exists so that "a ConfigureNotify that Wine did not ask
  for … becomes a SetWindowPos". Setting `managed_mode = TRUE` only clears the
  *first* guard in `window_update_client_config` (`if (!data->managed)`); the
  `is_virtual_desktop()` guard one line down still refuses. The claim in this
  file that "managed mode plus `WM_STATE` was the entire fix for resize" is
  therefore wrong: what managed mode fixed was minimise and restore, and the
  resize was being carried by the restore all along.

  **`patches/wine/0012-winex11-accept-wm-geometry-on-a-managed-desktop.patch`**
  is the fix — `if (is_virtual_desktop() && !managed_mode) return 0;`, so that
  0011 stays the single switch and upstream, which sets `managed_mode = FALSE`
  for every virtual desktop, is bit-for-bit unaffected. Verified with
  `git apply --check`. **Not built and not watched working** — Wine is the
  longest build in the project and this was not run. *Done when:* a border drag
  repaints the newly exposed area with no minimise/restore, watched on the
  device, and the trace carries a `config changed` line where today it carries
  `unexpected config`.

- [x] **A white bar across the top of a fullscreen game — and it is a fixed
  height in *guest* pixels, which rules out most of the candidates.**
  *Superseded — the caption is gone (`patches/wine/0010`) and the drag borders
  resize the guest; both are in `docs/DONE.md`. Kept here for the measurement,
  because the white **still appears on resize** and this table is the only
  quantified thing anyone has about it. The caption theory it was written to
  support is closed; the current reading is Wine erasing with the class brush
  over an area the client has not yet repainted, which is guest-side — the
  compositor clears to transparent black (`GLRenderer.java:131`), so a coverage
  gap would show `#161826`, not white.*
  *Measured on the device, 2026-08-10,* by reproducing it at three different
  sizes in one sitting and converting each back to guest rows:

  **Fixed — confirmed on the device by the reporter, 2026-08-10**, on the build carrying `patches/wine/0012`, the DRI3 present path (`patches/mesa/0006`, `0007`) and SGSR. Which of those is responsible was not isolated per symptom; the record below is what was believed before the fix and is kept because several of its explanations were wrong and worth not repeating.

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

  **Written in `ca2f5b5` and still `[ ]`, which is correct rather than an
  oversight.** `patches/mesa/0003` exists and does exactly this — a new
  `defers_sw_blit_wait` on `wsi_swapchain`, the `WaitForFences` in
  `wsi_common.c` skipped when it is set, and the wait moved into
  `x11_present_to_x11_sw`. What has not happened is the *Done when*: no
  before-and-after has been run, so the p95 this was aimed at is unknown. That
  commit did not touch this file, which is how a shipped change came to look
  unstarted for a day; the ticked box waits on the measurement, not the patch.

  **First numbers, 2026-08-10, and they do not support the projection.**
  `run-x11present.sh --wsi sw --frames 300` against the 400-frame figure this
  file already records:

  | | mean | p50 | p95 |
  |---|---|---|---|
  | before (recorded) | 2.245 | 1.632 | 4.945 |
  | after (`0003`) | **2.097** | **1.541** | **4.794** |

  About 5–7% on mean and median and **3% on p95** — roughly 0.15 ms, not the
  "up to 0.60 ms" this entry projected, and smallest on the statistic the patch
  was aimed at. Read it as suggestive and not as the measurement: different
  frame counts, a historical rather than a paired run, and one sample each.
  A real A/B needs the patch reverted and both runs taken in one sitting.

  **The real A/B was run on 2026-08-10 and found nothing. Treat the table above
  as withdrawn.** Two Turnip ICDs were built from the same tree differing only
  in `0003` (and in `0005`, which would not apply without it — 32 `fprintf`s on
  error returns, so not a second real variable), swapped on the device by
  overwriting `libvulkan_freedreno.so` and verified by sha256, two runs each:

  | | mean | p50 | p95 |
  |---|---|---|---|
  | with `0003`, `sw` | 2.057 / 2.027 | 1.499 / 1.525 | 4.595 / 4.478 |
  | without, `sw` | 2.483 / 2.055 | 2.113 / 1.452 | 5.422 / 4.826 |
  | with `0003`, `sw,noshm` | 2.113 / 2.280 | 1.540 / 1.664 | 4.734 / 5.080 |
  | without, `sw,noshm` | 2.490 / 1.901 | 2.018 / 1.486 | 5.992 / 4.475 |

  The run-to-run spread inside one build (1.901 to 2.490 ms without the patch)
  is larger than any gap between builds. **`0003` is not measurable with this
  harness**, in either direction — but it *is* live code on the `sw` path, not
  the dead code an earlier draft of this entry claimed. It stays: it removes a
  real wait from the application's thread, the reason it cannot be seen is the
  harness rather than the patch, and `sw` is the fallback if DRI3 ever fails.

  *Two things learned that cost runs, so that the next person does not repeat
  them.* First, `--wsi dri3` cannot see this patch **at all**: every hunk is
  inside `x11_present_to_x11_sw` or guarded by `wsi->sw`, so a dri3 A/B is
  guaranteed to be a null result and the first one taken was exactly that.
  Second — **and the first version of this paragraph got it backwards, which is
  worth keeping visible** — `defers_sw_blit_wait` is `wsi->sw &&
  !chain->has_mit_shm`, and it was written here that the vendored server *does*
  register MIT-SHM (`XServer.java:230`) so the patch never arms even in `sw`.
  That inference is wrong. Mesa does not read the extension list for this:
  `has_mit_shm` comes from `x11_xcb_display_supports_xshm()`, a real `ShmAttach`
  round trip (`wsi_common_x11.c:366`). `MITSHMExtension.attach()` now logs every
  request it serves (vendored item 25), and a 200-frame `--wsi sw` run produces
  **zero** attaches. So `has_mit_shm` is false, `defers_sw_blit_wait` is true,
  and **0003 was armed for every `sw` row in the table above** — the A/B was
  valid and still found nothing. The `sw,noshm` rows turn out to be a duplicate
  of the `sw` rows rather than the only live ones.

  *Done when:* a harness whose own variance is below ~5% exists. Until then this
  is unanswerable rather than unanswered, and it is worth remembering what the
  answer would buy: present costs 0.5 ms on the DRI3 path while Metro renders a
  frame every 80–125 ms, so 0.15 ms of present is under 0.2% of a frame.

- [x] **Drop the per-present `GetGeometry` round trip.** One request and one
  reply per frame (`wsi_common_x11.c:1854` + `:1917`), on the present thread,
  ordered behind the 3.6 MB PutImage on the same connection, and used for
  nothing but detecting a resize (`:1919-1925`). Selecting `StructureNotify` and
  reading `ConfigureNotify` answers the same question for free. Small
  `patches/mesa/` patch, low risk, **win unmeasured** — it is on the present
  thread, so it may only bound that thread's rate. A `Trace` section around the
  reply would size it before the patch is written.

  **Also shipped in `ca2f5b5`, and the patch is not what this entry describes.**
  Selecting `StructureNotify` was audited and rejected as unsafe: for an Xlib
  surface `chain->conn` is `XGetXCBConnection(dpy)` — the *application's*
  connection — so changing the event mask on the window would clobber Wine's
  own. `patches/mesa/0003` pipelines the reply one frame instead
  (`sw_geom_pending` / `sw_geom_cookie`, with `xcb_discard_reply` in destroy),
  which keeps the round trip but takes it off the critical path. Still `[ ]` for
  the same reason as the item above: unmeasured. Leave the `Trace` suggestion
  standing — it is now the way to find out whether this was worth doing.

  **Closed 2026-08-11 without the `Trace`, because there is nothing on the
  shipping path to trace.** Every `xcb_get_geometry` in
  `native/mesa/src/vulkan/wsi/wsi_common_x11.c` was read and attributed:

  | Site | When it runs |
  |---|---|
  | `:471`, `:787`, `:1115`, `:3139` | surface capability and creation queries — once |
  | `:1854` + `:1917` | `x11_present_to_x11_sw` — **per present, software path only** |
  | `:2926` | `wsi_x11_swapchain_query_dri3_modifiers_changed`, reached only from the `SUBOPTIMAL_COPY` arm of `x11_swapchain_result` (`:1724`) — an Xwayland case, and guarded by `!wsi_device->sw` besides |

  So the per-present round trip this entry is named after **exists only in
  `x11_present_to_x11_sw`**. On DRI3 resize is reported through
  `PresentConfigureNotify`, and no geometry is asked for at all. The item
  therefore cannot pay anything in the configuration the device runs: `0003`
  pipelines the only per-frame instance, on the only path that has one, and that
  path is the fallback.

  Which also settles the `Trace`: it would have been taken against `--wsi sw`,
  measured the same harness whose run-to-run spread already swallowed the whole
  of `0003` in the entry above, and reported the same nothing. Both halves of
  this pair are written, both are `sw`-only, and both are unmeasurable for the
  same reason — not two open items but one closed one.

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

  **Measured 2026-08-10, and the prize is 252 microseconds.** The copy is now
  timed in `PresentExtension.presentPixmap` itself and reported every 120
  presents, because neither end could see it: `x11present`'s 0.546 ms is the
  *client's* `vkQueuePresentKHR` round trip and with three swapchain images the
  client is usually not waiting on this copy when it returns. Over 600 presents
  at 1280x720: **mean 252 us, max 1032 us** — 3.6 MB in 252 us is 14.3 GB/s, so
  the memcpy is not slow, there is simply 3.6 MB of it.

  That is about 46% of the client-visible present cost and it is held under
  `content.renderLock`, which the compositor also takes, so deleting it is
  worth something. But it is **1.5% of a frame at 60 Hz and 0.25% of one at the
  8-12 fps Metro actually renders**. *Decided 2026-08-10: deferred, not
  dropped.* Unlike the flip branch below there is no closed gate here — the
  machinery exists and the change is buildable today — so this waits on
  priority rather than on feasibility.
  The same measurement session found the device ~85% idle at 10 fps with every
  game thread asleep; 252 us cannot be what is missing, and this item should
  wait until whatever costs the other 99.75% of the frame is named.

  **It has been named, and it was the audio.** 2026-08-11, after
  `0bbd8b0` sized the AAudio buffer to `min(client, device)`, Metro's intro went
  from 12 fps to **28** — reported by the ear-and-eye that had been watching it
  all along, not by a harness.

  The mechanism fits the one fact that never fitted anything else. `wineoss.drv`
  was queueing Metro's whole 1440-frame buffer into a device whose start
  threshold was 1736, so AudioFlinger never started the track, `framesRead`
  never moved, and the driver's trace read `advanced by 0, held: 1440` for 2072
  consecutive passes. A game whose audio never drains does not spin — it
  *waits*, and so does whatever it feeds. **That is why the SoC was idle: the
  frame was not expensive, it was blocked.** Every explanation this file tried
  before assumed the frame cost something, and measured, correctly, that nothing
  did.

  Two consequences worth carrying forward. First, "~85% idle at 10 fps" is a
  signature, not a puzzle: it means a stall, and the next time it appears the
  question to ask is what a thread is waiting *on*. Second, item 27's arithmetic
  gets better rather than worse — 252 us against a 36 ms frame is 0.7%, still
  not the thing to fix.

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

- [ ] **The FEX code cache is wired up end to end and has never been timed.**
  Both of the blockers that kept `FEX_ENABLECODECACHINGWIP` switched off are
  answered, so the flag is now on:

  - *Nothing ran the compiler.* `SessionRuntime.generateCodeCache` runs
    `FEXOfflineCompiler64.exe process-all` at teardown, once the guest is dead
    and before the wake lock goes. It skips entirely when `<cache>/codemap/new`
    is empty or the FEX package carries no compiler, is bounded by
    `CODE_CACHE_TIMEOUT_MS`, cannot throw, and writes every outcome — including
    the successful one, with a duration — into the session log.
  - *A cache was not keyed on FEX's configuration.* Upstream's
    `CodeCacheConfigId` is `0 // TODO` (`ImageTracker.cpp`), so FEX itself will
    load a cache built under a different TSO or CPU setting. `fexCacheKey`
    closes it from Vessel's side without a FEX patch: `FEX_APP_CACHE_LOCATION`
    is now `caches/fex/<digest>/`, where the digest covers **every** `FEX_*`
    variable in the environment that actually ran — after the manifest and
    diagnostics stages, so a knob added later is in the key without anything
    being told — plus the FEX package's version code and the byte lengths of its
    two CPU DLLs. Three exclusions, in `FEX_CACHE_KEY_IGNORED`, each with a
    reason at the declaration. A configuration change moves the whole directory,
    so a stale cache becomes *unreachable* rather than silently wrong.

  Unit tests cover the properties (a codegen knob moves the directory, the log
  destination does not, an unknown `FEX_*` variable still counts, the package
  identity counts), and the golden environment map pins the digest so that any
  change to it has to be looked at. **None of that is a launch-time result.**
  *Done when:* the same program is launched twice on the device with
  `caches/fex` wiped before the first, and the two launch-to-first-window times
  are recorded — including if the second is no faster, which is a real answer
  and would mean the codemap/cache is not covering the launch path.

  **Read the code before booking the measurement: the cache cannot be loaded at
  all in this configuration, and that is settled from source, not suspected.**
  `FEX_APP_CACHE_LOCATION` is a *unix* path —
  `SessionEnvironment.kt:1073` builds it with `File(...).absolutePath +
  File.separator`, so FEX receives `/data/user/0/app.vessel/files/containers/
  <id>/caches/fex/<digest>/`. FEX uses that string two different ways:

  - **Writing works.** `ImageTracker.cpp:158` and `FEXOfflineCompiler`'s
    `ProcessAll` go through `std::filesystem` and `_sopen`, which are Win32
    calls, and Wine's `RtlGetFullPathName_UEx` has an explicit case for them:
    `RtlPathTypeRooted` with `name[0] == '/'` calls `get_unix_full_path()`,
    which resolves the path through `\??\unix\…` (`ntdll/path.c:694, 509`).
    That is why `caches/fex` fills.
  - **Reading cannot.** `ImageTracker::LoadAOTImages` does not use a Win32 API.
    It composes an NT path by string concatenation —
    `fmt::format("\\??\\{}cache\\{}-{:016x}", GetCacheDirectory(), …)`
    (`ImageTracker.cpp:238`) — and hands it to `NtOpenFile`. The result is
    `\??\/data/user/0/…/cache/<id>-…`, and `nt_to_unix_file_name_no_root`
    strips `\??\`, takes everything up to the first backslash as the DOS device
    prefix, and ends at `if (wcschr( prefix, '/' )) return
    STATUS_OBJECT_PATH_NOT_FOUND` (`ntdll/unix/file.c:3793`). The only NT path
    that reaches a unix file is `\??\unix\…`, and this is not one.
    `LoadAOTImages` logs nothing when `NtOpenFile` fails — the `if
    (NT_SUCCESS(...))` simply falls through — so the failure is silent.

  **So the prediction for the measurement is that warm equals cold**, and that
  `Loaded cache: <id>` never appears in a session log. That is worth running
  anyway, because it is the falsification test for the paragraph above.

  **Measured on the device 2026-08-10, and the timing cannot be taken yet
  because two things upstream of it have never worked. Neither is the path bug
  above.**

  *One: the compiler has never run, so there is nothing to load.* The container
  holds two cache keys — `caches/fex/431d9812d0f4` and `caches/fex/96a6dacbc994`,
  so the digest scheme is doing its job across a configuration change — and
  between them **three Metro codemaps, all still in `codemap/new/`**. There is no
  `codemap/ready/` and **no `cache/` directory at all**. `ProcessAll()` creates
  both as its first two acts, so neither existing means it has never started.
  The reason is in the session metadata: **every session ever recorded on this
  device reads `"exit": "RUNNING", "endedAt": null`** — the process was killed,
  teardown never ran, and `generateCodeCache` is called from teardown. It is not
  that the compile step is broken; it is that nothing has ever reached it.

  *Two: the compiler crashes at startup when it is run by hand.* Invoked
  directly against an existing key, with `FEX_APP_CACHE_LOCATION` pointing at it:

  ```
  D 120 Load module FEXOfflineCompiler64.exe (…): 140000000
  D 120 Exception: Code: C0000005 Address: 1400439E8
  wine: Unhandled page fault on read access to 0000000000000018
        at address 00000001400439E8 (thread 0120)
  EXIT=5
  ```

  Only ntdll, kernel32, kernelbase and ucrtbase are loaded when it dies, and no
  directory is created, so it faults before `ProcessAll` does anything. Had
  teardown ever run, the session log would have said "the FEX code cache
  compiler exited with 5".

  *What the run did confirm.* Writing works exactly as the source reading said:
  a probe launch with `FEX_ENABLECODECACHINGWIP=1` and a unix
  `FEX_APP_CACHE_LOCATION` created `…/codemap/new/pb.exe-0ffc5f8403f7ac8b.<ts>.bin`
  at the **real** unix path, and there is no stray `drive_c/data/…` tree anywhere
  in the prefix. So the asymmetry is confirmed from both ends: Win32 writes
  resolve, the hand-built `\??\` read cannot.

  **Order of work, and the cold/warm number is last:** make a session reach
  teardown (or move the compile step somewhere a killed process still reaches),
  fix the compiler's startup fault, give `FEX_APP_CACHE_LOCATION` a DOS path.
  Only then is there a measurement to take.

  **All three done 2026-08-11, and the pipeline now runs end to end on the
  device.** `tools/fex/run-offline-compiler.sh` is the vehicle — it joins the
  container the app already has, maintains the symlink, and can run an x86 PE
  with caching on (`--pe`) to make a codemap before compiling one.

  | Step | Before | After |
  |---|---|---|
  | compiler starts | `C0000005` at `1400439E8` | prints its usage, `EXIT=1` |
  | `process-all` | never ran | `EXIT=0`, creates `cache/` and `codemap/ready/` |
  | codemap written | only via a unix path | `codemap/new/tonewin.exe-9cafc1787594cfda.…bin`, through `C:\vessel\fexcache\` and the symlink |

  - *The startup fault was an ordering bug and is fixed in `patches/fex/0001`.*
    `main()`'s first statement is `FEX::Windows::Logging::Init()`, which reads
    `SILENTLOG` through `FEX_CONFIG_OPT`; `Getter` constructs eagerly, so the
    ctor reaches `Meta->GetConv(Option)` — and `Meta` is a file-static
    `MetaLayer*` that is null until `Initialize()` assigns it. A member read
    through a null `this` is exactly a fault at `0x18`. `Initialize()` is
    reached only from `FEX::Config::LoadConfig`, which `GenerateCache` calls
    *after* the dispatch and `ProcessAll` never calls at all — so the tool had
    never run on Windows, for any argument.
  - *`FEX_APP_CACHE_LOCATION` is now `C:\vessel\fexcache\`*, a symlink in the
    prefix pointing at `caches/fex/<digest>/`. Confirmed by watching the codemap
    land under `caches/` while FEX was handed a DOS path.
  - *The compile step now also runs at session start*, since teardown has still
    never fired. It is **launched, not awaited** — the first version awaited it,
    and a compiler crashing on a stale cache then held the desktop for the whole
    120 s timeout, which from outside looked like a container that would not
    start.

  **What is left is a codemap with anything in it.** `process-all` correctly
  reported `Found zero-size code map …, deleting`. That is not a bug in the
  path: `CodeMapWriter` buffers 4096 bytes and `Flush` runs from
  `~CodeMapWriter` (`CodeCache.cpp:150`), so a program that translates less than
  one buffer *and* exits without that destructor running leaves a file that was
  created by `O_CREAT|O_TRUNC` and never written. `tonewin.exe` is a three-second
  tone; it is the wrong workload. The three Metro codemaps this entry recorded
  earlier may well have had content, but they were cleared before anyone looked.

  *Done when:* a real title runs, leaves a non-zero codemap, `process-all`
  imports it into `cache/`, and the same title is then launched twice with
  `caches/fex` wiped before the first — including if the second is no faster.

  The fix is one value, not one code path: `FEX_APP_CACHE_LOCATION` has to be a
  **DOS path ending in `\`**, which satisfies both uses at once — `C:\…\codemap
  \new\` for the writer, `\??\C:\…\cache\…` for the reader. Where that DOS path
  should point is a product decision and is **not** made here: putting it inside
  the prefix (`C:\…`) contradicts the reason the cache was moved out of
  `%LOCALAPPDATA%` in the first place — it must live under `caches/` so a
  container reset clears it — and mapping a drive letter at `caches/` makes it
  a user-visible drive in the Files tab and in the registry seed. Whichever is
  chosen, `SessionRuntime.generateCodeCache` also needs the host path
  separately, since it reads `FEX_APP_CACHE_LOCATION` back as a `File`
  (`SessionRuntime.kt:1423`).

  *The `DriveMap.removeRootDrive()`-called-twice suspicion is not a defect.*
  Both calls are deliberate and documented at their sites
  (`ContainerProvisioner.kt:387, 432`): once before the registry seed is
  rendered, because the seed is rendered from `dosdevices` and must not declare
  a drive that is about to go, and once after `wineboot --init`, which recreates
  `dosdevices/z:` itself. `removeRootDrive` is a single `File.delete()` wrapped
  in `runCatching`; `false` means "already gone". The underlying worry — a PE
  handed a `/data/…` path with no drive letter to resolve through — is real, but
  `Z:` is not what would have rescued it: Wine resolves a leading `/` through
  `\??\unix\…` and not through any drive, and the path that actually breaks
  breaks inside FEX's own `\??\` string concatenation, where no drive mapping
  can reach it.

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

- [x] **DRI3 present works, and it is 3.4× cheaper than the software path.**
  *Measured 2026-08-10 with `tools/gfx/run-x11present.sh`, no Wine and no FEX in
  the process, on the device:*

  ```
  VESSEL-WSI image_init dma_buf_fd=6 num_planes=1 modifier=0xffffffffffffff
             row_pitch=5120 size=3686400 dri3_modifiers=0
  result=PASS wsi="dri3" 1280x720 frames=300
    mean_ms=0.602 p50_ms=0.505 p95_ms=1.837 fps=1662
  result=PASS wsi="sw"   1280x720 frames=300
    mean_ms=2.143 p50_ms=1.580 p95_ms=4.625 fps=467
  ```

  A real dma-buf, allocated by Turnip out of `/dev/dma_heap/system`, exported
  over `SCM_RIGHTS` and imported by the X server as a pixmap. **It is not yet
  zero copies**: `PresentExtension.presentPixmap` still `memcpy`s the pixmap
  into the window's drawable. What went away is the *client* copy — Mesa's
  software path maps the image and pushes 3.6 MB through `xcb_put_image` per
  frame. The last copy needs `presentPixmap` to bind the dma-buf as a texture
  and flip; that is a separate item and it is not blocking anything.

  Three defects, in the order they were peeled off, because each one hid the
  next and only the first was where anybody was looking:

  1. **No XFIXES.** libxcb tore the connection down client-side before a single
     request was sent. Fixed by `XFixesExtension.java` (`186390f`, vendored item
     20).
  2. **Mesa's DRM image backend was not compiled in, and nothing said so.**
     `src/vulkan/wsi/meson.build` gates `wsi_common_drm.c` on
     `dep_libdrm.found()`, and Mesa deliberately sets `system_has_kms_drm =
     false` for a kgsl-only Turnip so that nothing links libdrm on Android. So
     `HAVE_X11_DRM` compiled the DRI3 *half* of `wsi_common_x11.c` (patch 0004)
     while `WSI_IMAGE_TYPE_DRM` had no backend at all. That is not a build
     error: `get_blit_type()` and `configure_image()` put the DRM case behind
     `#ifdef HAVE_LIBDRM` and end in `default: UNREACHABLE()`, so with one live
     label a release compiler drops the switch and calls
     `wsi_configure_cpu_image()` unconditionally. A DRI3 swapchain silently got
     CPU-linear images, and `x11_image_init` then `dup(-1)`'d:
     `dma_buf_fd=-1 num_planes=1 modifier=0x0 row_pitch=5120`, `errno=9`,
     `VK_ERROR_OUT_OF_HOST_MEMORY`. Field for field what
     `wsi_create_cpu_linear_image_mem` leaves behind. Fixed by
     `patches/mesa/0006`, which compiles `wsi_common_drm.c` on any pseudo-drm
     platform against the stubs Mesa already ships in `src/util/libdrm.h`
     (`drmIoctl` and `drmDevicesEqual` added there) under a new `HAVE_WSI_DRM`.
     *`kgsl_bo_export_dmabuf`'s `assert(bo->shared_fd != -1)` was never the
     problem and is never reached with a bad BO — `TU_BO_ALLOC_SHAREABLE` was
     not set because `wsi_create_native_image_mem`, the only thing that asks
     for exportable memory, was not in the binary.*
  3. **Refusing DRI3 `FenceFromFD` (4) leaks the fd that came with it.** With
     images now real, the *second* present killed the app:
     `signal 7 (SIGBUS), code 2 (BUS_ADRERR)` in `__memmove_aarch64_nt` under
     `Drawable.copyArea` / `PresentExtension.presentPixmap`, faulting exactly
     one page into the source. `XInputStream` keeps one ancillary-fd queue per
     connection and `getAncillaryFd()` pops its head; a refused request never
     pops, so every later `PixmapFromBuffer` was handed the previous image's
     4096-byte xshmfence page instead of its 3686400-byte dma-buf. Worked
     around by `patches/mesa/0007`, which stops Mesa asking for an idle fence —
     sound against *this* server because `presentPixmap` copies synchronously
     before it sends `PresentIdleNotify`, so the idle event is already a
     complete signal. `VESSEL_WSI_DRI3_FENCE=1` turns the request back on.

  *What is left on this, and none of the three pieces is a DRI3 problem:*

  - [~] **`FenceFromFD`, a DRI3 1.0 request the server did not implement** — so
    it was non-conformant at the version it advertises, and any other DRI3
    client would walk into the same fd-queue shift.

    **Written 2026-08-10, compiles, and not measured on the device.**
    `DRI3Extension.fenceFromFD` (opcode 4), backed by a new
    `com.winlator.xserver.XShmFence` over a new
    `cpp/winlator/src/xshmfence.c`, with `SyncExtension` keeping the client's
    page per fence and reading it rather than its own boolean. Vendored items
    23 and 24. The layout was **read out of libxshmfence 1.3.3** — the version
    `native/pins.env` pins — and not guessed: `struct xshmfence` is one
    `int32_t` at offset 0, `ftruncate`d to four bytes, and its value is
    three-state (`0` untriggered, `-1` untriggered-with-a-waiter, `1`
    triggered) rather than boolean. The trigger is C and not a
    `ByteBuffer.putInt` because a store without `FUTEX_WAKE` loses a waiter
    that is already inside the syscall, and `VarHandle`'s compare-and-set is
    API 33 against `minSdk 31`.

    **Measured on the device 2026-08-10, and the fence is free.** Same live
    session, same 300 frames, back to back:

    | | mean | p50 | p95 | max |
    |---|---|---|---|---|
    | fence off | 0.555 ms | 0.483 ms | 1.662 ms | 1.933 ms |
    | fence on | 0.565 ms | 0.487 ms | 1.667 ms | 1.877 ms |

    +0.010 ms on the mean, which is inside the spread of four fence-off runs
    taken the same hour (0.534, 0.553, 0.555, 0.558).

    **It was proved to be running, not assumed.** A run that skips the fence
    and a run that serves it both print `result=PASS`, so the pass says
    nothing; `DRI3Extension.fenceFromFD` therefore logs each fence it serves,
    and the count is **0 with the flag unset and exactly 3 with it set** — one
    per swapchain image — with no `VesselXProto` error on either. That log line
    is why the number above can be read as the fence's cost rather than as the
    cost of Mesa quietly not asking.

    Do not bump `DRI3Extension.MINOR_VERSION` to 2 — see vendored item 21 for
    why that is the wrong lever and buys nothing.

    **`patches/mesa/0007` is deleted.** Turnip was rebuilt without it and
    installed on the device; with no environment variable set at all a run is
    `result=PASS` at mean 0.560 ms and the server serves three fences. The
    fence is the default now, which also removes the hazard the flip branch
    would otherwise have walked into — 0007's safety argument depended on
    `presentPixmap` copying synchronously, and the flip removes that copy.

    *What is left:* only `x11present` has exercised the fence. A Wine title has
    not, and the code runs for every app now.

  - [~] **A second `--wsi dri3` run against a live session fails with
    `BadIdChoice`.** The cause is now read rather than inferred:
    `DRI3Extension.pixmapFromFd` never called `registerAsOwnerOfResource`, so a
    swapchain's pixmaps were owned by nobody and `XClient.freeResources()` did
    not free them — while `ResourceIDs.free()` returns a departing client's id
    base to a sorted set whose `get()` takes the smallest, so the next
    connection is handed the same base and regenerates the same XIDs.

    **Written 2026-08-10, compiles, and not measured.** The registration is
    added, and with it a general `Extension.freeClientResources(XClient)` fanned
    out by `XServer` from `XClient.freeResources()` — because SYNC's fences,
    Present's `SelectInput` contexts and XFIXES' regions leak the same way and
    are not `XResource`s. Present's is the worst of the three and is a *hang*
    rather than an error: a stale context makes the next client's `SelectInput`
    throw `BadMatch`, Mesa issues it unchecked, and the swapchain then runs
    against an event id the server never associated with it, so no idle notify
    ever arrives. Vendored item 24. `tools/gfx/x11present.c` now destroys its
    swapchain and flushes before disconnecting, so the default sw-then-dri3
    invocation stops relying on the server-side reclamation it is also testing.

    **Confirmed fixed on the device 2026-08-10.** Six probe clients in
    succession against one live session — `sw`, then `dri3`, then four more
    `dri3` runs — every one `result=PASS`, where the second used to die with
    `BadIdChoice`. No `VesselXProto` line on any of them.

    **The crashed-guest case is confirmed too.** A probe was started with
    `--frames=200000`, allowed to get past `first_frame_ms=1.99` — so it was
    inside the present loop, holding a swapchain's three DRI3 pixmaps, a SYNC
    fence each and a Present event context — and then `kill -9`'d. The next
    client connected and ran to `result=PASS` at mean 0.560 ms with **no
    `VesselXProto` line of any kind**. That is the case the reclamation was
    written for: nothing asked the server to free those resources, and it freed
    them anyway.

  - [~] **The last copy: a flip branch in `PresentExtension`. Closed as
    won't-do, 2026-08-10 — the gate it depended on is shut.** The
    specification below is kept because it is the reasoning, not the plan.

    The flip needs the app's EGL context to make a texture out of a Turnip
    dma-buf. There are two routes and this device offers neither cleanly:
    `dumpsys SurfaceFlinger` lists 42 EGL extensions and
    **`EGL_EXT_image_dma_buf_import` is not among them**, which closes the
    direct one; the remaining route is `AHardwareBuffer_createFromHandle`,
    which is not in the NDK.

    Two other things say the same. The payoff is now measured and it is **252
    us** — see item 27 above, which times the copy this would delete — against
    a frame that takes 80-125 ms in the title that motivated it. And the
    structural blocker is gone in a way that removes the urgency rather than
    creating it: `patches/mesa/0007` is deleted, so the DRI3 idle fence is the
    default, so the flip no longer has a missing-fence hazard to design around.

    *Reopen when:* something makes a Turnip dma-buf importable into the app's
    GL context without a private API, **and** a profile shows the copy costing
    a meaningful fraction of a frame. Neither is true today.

  *Kept from the original specification, still true:* the server's
  `pixmapFromBuffer` mmaps the client's fd on the CPU, gralloc returns a tight
  linear buffer for `GPU_COLOR_OUTPUT` alone so there is no UBWC problem, and
  `VK_EXT_external_memory_host` is absent so Mesa's MIT-SHM path was never
  reachable.

### Specification: the flip branch in `PresentExtension`

*Written 2026-08-10 after reading the compositor, and deliberately stopping at
a specification.* This is the last copy in the present path and it is the most
invasive change of the three; landing it half-done would break every window in
the session and not only the one presenting. Everything below that is a
measured fact says so; everything else is marked as inference.

**What the copy actually is, and what it is not.** `presentPixmap` calls
`content.copyArea(...)`, which is one 3.6 MB `memmove` out of the mmapped
dma-buf into the window `Drawable`'s buffer. That is the *whole* remaining copy:
the second one people expect — the compositor re-uploading the window every
frame — **is already gone for any window Present touches**, because
`PresentExtension.selectInput` converts the window's content texture to a
`GPUImage` and `GPUImage.updateFromDrawable()` is a no-op over an
`EGLImageKHR` on the AHardwareBuffer (`GPUImage.java:38-49`). So the flip buys
exactly one CPU memcpy per present and nothing else. **How much that is worth
is unmeasured** — the DRI3 mean of 0.602 ms is a client-side round trip and
`presentPixmap` runs after `vkQueuePresentKHR` has returned, so an unknown
fraction of the copy is already off the measured path. *Measure it before
building it:* time `content.copyArea` inside `presentPixmap` and log the mean.
That is ten lines and it decides whether the rest of this is worth doing.

**The one gate that decides whether it is possible at all.** A flip means the
window samples the presented image, so the app's EGL context has to be able to
make a texture out of a Turnip dma-buf. Today the server only ever *CPU-maps*
that fd. Two candidate routes, and **neither has been tried**:

  - `eglCreateImageKHR(EGL_LINUX_DMA_BUF_EXT)`, which needs
    `EGL_EXT_image_dma_buf_import` in the app's EGL. Android's EGL usually does
    not expose it, preferring AHardwareBuffer; whether Adreno's does here is a
    one-line `eglQueryString(EGL_EXTENSIONS)` away.
  - `AHardwareBuffer_createFromHandle`, wrapping the fd back into an AHWB and
    reusing the `GPUImage` path unchanged. Not a public NDK symbol — the same
    class of hazard as `AHardwareBuffer_getNativeHandle`, which
    `cpp/winlator/src/gpu_image.c` already declares by hand.

  *Do this probe first.* If both routes fail, the flip is impossible as
  described and the entry should be closed as won't-do, not carried.

**Why it cannot land before `FenceFromFD` works.** `patches/mesa/0007`'s
justification is that "`presentPixmap` copies synchronously, under the window's
render lock, before it sends `PresentIdleNotify`, so the idle event is already
a complete signal". **A flip destroys exactly that property.** After a flip the
presented image is still being sampled by the compositor, so the server must
not report it idle until the pixmap has stopped being the window's texture
*and* the GL frame that sampled it has completed. That is the case an idle
fence exists for, and it is the reason item 1 is a hard dependency and not a
sequencing preference.

**The shape of the change.**

  1. **Swap the `Texture`, never the `Drawable`.** `GLRenderer` caches
     `RenderableWindow.content` as a `final Drawable` and only rebuilds that
     list in `updateScene()` — on map, unmap, z-order and resize
     (`GLRenderer.java:466`). So `window.setContent(pixmap.drawable)` would need
     an `updateScene()` per present, on the GL thread, taking
     `WINDOW_MANAGER` + `DRAWABLE_MANAGER`. It would also change the identity of
     `content.renderLock`, which `renderWindowDrawable` synchronizes on. Set the
     window `Drawable`'s *texture* instead: `renderWindowDrawable` re-reads
     `drawable.getTexture()` every frame, so that is picked up with no scene
     rebuild and no lock-identity change.
  2. **A flip is conditional and the fallback must stay.** Only when the pixmap
     matches the window drawable exactly — width, height, depth, and stride, and
     `xOff == yOff == 0` — and the request carries no `valid`/`update` region
     narrowing it. Anything else copies, as today. This is what
     `present_check_flip` does upstream and it is not optional: the geometry
     stops matching on the first resize.
  3. **Unflip on any drawing to the window.** With the texture flipped, the
     window's `Drawable.getData()` still points at the old buffer, so core
     `PutImage`/`CopyArea`/`GetImage` on that window would read and write pixels
     nobody is showing. Either repoint `data` at the pixmap's mapping too, or
     revert to the window's own buffer the moment anything draws into it.
  4. **Lifetime.** The window has to hold a reference to the flipped pixmap
     until the next present replaces it, and `freePixmap` on a currently
     flipped pixmap has to unflip first — otherwise `DrawableManager.
     removeDrawable` destroys a texture the compositor is about to bind. The
     `contextGeneration` rule of vendored item 13 applies to whatever texture
     object is created here.
  5. **`Mode.FLIP`.** `PresentCompleteNotify` should say `FLIP` when it flipped;
     the enum already has it and `sendCompleteNotify` currently hardcodes
     `Mode.COPY`. No `PresentQueryCapabilities` bit changes — the four bits are
     async/fence/UST/tearing and none of them is "can flip".
  6. **Resize.** `changeWindowGeometry` reallocates the window's backing
     `Drawable`; anything flipped onto the old one has to be dropped there.

*Done when:* `run-x11present.sh --wsi dri3` passes with the flip taken (proved
by a counter, not by the absence of a crash), its mean is compared against the
copy path in the same sitting, and a session survives a resize and a
minimise/restore of a presenting window. **If the EGL probe in the gate above
comes back negative, stop and close this — the specification is the deliverable
in that case.**

- [ ] **FEX asserts inside a container. The assert is real; "any large PE" is
  not the discriminator.**
  *Re-measured 2026-08-10 with `+seh,+module` and `FEX_SILENTLOG=0`.* The fault
  is `c000001d EXCEPTION_ILLEGAL_INSTRUCTION` at `0x7FFFBF44F4`, and the module
  map puts `libarm64ecfex.dll` at `0x7FFFA80000` — so **RVA `0x1744F4`**, which
  is essentially the `FEXCore::Assert::ForcedAssert` site this entry already
  recorded at `0x1766E4` in an older build. Five SEH handlers are called and all
  five return `ExceptionContinueSearch`, then `NtRaiseException` gives up. So
  the recorded cause stands and the earlier doubt about it was wrong.
  *What is wrong is the framing.* "Over roughly 200 KB" cannot be the rule:
  Metro 2033 Redux is far larger and runs at 60 fps in the same container.
  `presentbench-x86_64.exe` is 238 KB, built by llvm-mingw with `-O1` against
  `dxguid/uuid/gdi32/user32`; `hello-x86_64.exe` is 88 KB from the same
  toolchain and runs. The discriminator is therefore something about the image,
  not its size, and FEX's Windows layer has only two `ERROR_AND_DIE` sites —
  `"Unhandled relocation"` and `"Couldn't detect CPU features"`. The first is
  the candidate worth testing next.
  *Confirmed at the byte level, 2026-08-10.* `llvm-objdump` of the shipped
  `libarm64ecfex.dll` (ImageBase `0x180000000`, so VMA `0x1801744F4`):

  ```
  00000001801744f4 <_ZN7FEXCore6Assert12ForcedAssertEv>:
  1801744f4: d4400020    hlt #0x1
  ```

  Exactly the symbol, exactly `hlt #1`. No inference left in that half.
  Directly above it sits `LogMan::Msg::MFmtImpl`, so **FEX does log a reason
  before dying** — `ERROR_AND_DIE` formats a message and then asserts.

  **The blocker is now that the message reaches nowhere we read, and that is a
  new finding.** `FEX_SILENTLOG=0` in the guest environment produced no FEX
  output in a Wine log captured with `+seh,+module,+winediag`, and the
  `%LOCALAPPDATA%` fallback the Windows logging init falls back to does not
  exist — `.config/fex-emu/` in the container is empty and there is no `*.log`
  under the container from that run. So either `__wine_dbg_output` resolution is
  failing silently and the fallback is not being taken either, or the message is
  written somewhere neither path predicts.
  *Done when:* one `ERROR_AND_DIE` message from FEX is read, by any means —
  after which naming the site is free, since there are only two. x86-64 and ARM64EC binaries over roughly 200 KB die at
  `FEXCore::Assert::ForcedAssert` — `hlt #1`, `libarm64ecfex.dll` RVA
  `0x1766E4` — during `BTCpuProcessInit`, before FEX logs anything.
  `hello-x86_64.exe` (88 KB) runs in the same container with the same
  environment; `d3d11probe-x86_64.exe` runs in the standalone `files/graphics`
  prefix and not in the app's container. Only two `ERROR_AND_DIE` sites exist in
  FEX's Windows layer — `"Unhandled relocation"` and `"Couldn't detect CPU
  features"`. Undiagnosed, and it is why every D3D result above comes from the
  standalone harness rather than from a session.

  **Four corrections from reading the FEX tree, before any of this is run
  again. The plan survives; three of the four reasons given for it do not.**

  1. ***"Only two `ERROR_AND_DIE` sites" is false, and the elimination argument
     built on it does not work.*** Two is the count in `Source/Windows/`.
     `libarm64ecfex.dll` also links FEXCore and `Source/Common`, which add about
     thirty more — seven in `Interface/Core/CodeCache.cpp` alone, ten in
     `JIT/JITClass.h`, two each in `Core.cpp`, `Arm64Relocations.cpp`,
     `Utils/Threads.cpp` and `x87StackOptimizationPass.cpp`, three in
     `Common/Async.h`. Naming the site by elimination was never available.
  2. ***One of the two Windows sites is unreachable in a session, so it is not
     the candidate to test next.*** `"Unhandled relocation"` lives in
     `LoadImageRelocations`, which `HandleImageMap` calls only
     `if (IsGeneratingCache)` (`ImageTracker.cpp:135`). Both session modules
     construct the tracker as `ImageTracker.emplace(*CTX, false)`
     (`ARM64EC/Module.cpp:615`, `WOW64/Module.cpp:547`); only
     `FEXOfflineCompiler` passes `true`. In a session that site cannot fire.
  3. ***The candidate set is exactly the `ERROR_AND_DIE_FMT` sites, and nothing
     else.*** `LOGMAN_MSG_A_FMT` and `LOGMAN_THROW_A_FMT` also end in
     `ForcedAssert`, but only under `ASSERTIONS_ENABLED`, which FEX's
     `CMakeLists.txt:183` sets for `DEBUG` builds only and `build/fex.sh:127`
     builds `Release`. They compile to nothing here.
  4. ***`FEX_SILENTLOG=0` is load-bearing in a way the entry did not say, and
     the output does not go where the entry looked.*** `SilentLog` **defaults to
     true** (`Config.json.in:387`). When it is true `FEX::Windows::Logging::Init`
     returns *before* `LogMan::Msg::InstallHandler`, and `MFmtImpl` with no
     handler installed discards the message without writing anywhere — so the
     `%LOCALAPPDATA%` fallback is not "not taken", it does not exist on that
     path either. When it is false the handler writes through
     `__wine_dbg_output`, which is unconditional: it goes to the **guest's own
     stderr**, i.e. `tmp/guest.out`, and *not* into a `WINEDEBUG`-channel log.
     The line to grep for is one starting `A ` — `MsgHandler` formats
     `"{} {:X} {}\n"` with `DebugLevelStr(ASSERT)` being the single letter `A`.
     `Logging::Init()` runs early in `ProcessInit`, before both candidate sites,
     so ordering is not the problem.

  **The link-register plan is still the right one, and it needs a different
  vehicle than `+seh`.** `+seh`'s register dump comes from `TRACE_CONTEXT` in
  `ntdll_misc.h`, and in an ARM64EC ntdll the `__x86_64__` variant is selected —
  it prints the AMD64-named subset only. In `ARM64EC_NT_CONTEXT` the link
  register is aliased onto `FloatRegisters[0]` at offset `0x120`
  (`include/winnt.h:1969`), which that macro never prints. So `lr` is not in the
  trace already taken and will not appear by rerunning with `+seh`.

  What does print it, with no rebuild, is **`+unwind`**: `RtlVirtualUnwind2`
  opens with `TRACE( "type %lx base %I64x pc %I64x rva %I64x sp %I64x\n", … )`
  (`ntdll/unwind.c:799`). `ForcedAssert` is `naked` with no unwind data, so it
  takes the leaf path — `context->Pc = context->Lr` — and the *second* of those
  lines carries the caller's `base` and `rva` directly, with no arithmetic. Then
  `llvm-objdump` on the shipped `libarm64ecfex.dll` names the symbol, exactly as
  it did for `ForcedAssert` itself.

  *Order to run it in:* `WINEDEBUG=+seh,+unwind` with `FEX_SILENTLOG=0`,
  capturing **`tmp/guest.out`** and not only the channel log, and grep it for
  `^A ` first — if FEX's own message is there the site is named outright and the
  unwind trace is only corroboration.

  **Run on the device 2026-08-10, and it did not reproduce. That is the whole
  result and it is not a fix.** `presentbench-x86_64.exe` — 238 KB, built by the
  same llvm-mingw line `device-graphics.sh` uses, `-O1` against
  `dxguid/uuid/gdi32/user32` — was run in the app's own container prefix from
  the command line, three times:

  | environment | outcome |
  |---|---|
  | `FEX_SILENTLOG=0` only | ran; `result=BLOCKED stage=createwindow err=203`, EXIT=1 |
  | `+ FEX_ENABLECODECACHINGWIP=1`, `FEX_APP_CACHE_LOCATION` | same, and it wrote a codemap |
  | `+` the whole session knob set (`TSOENABLED`, `HALFBARRIERTSOENABLED=0`, `VECTORTSOENABLED`, `DISABLEL2CACHE`, `DYNAMICL1CACHE`) | same |

  No `c000001d`, no `ForcedAssert`, no line starting `A `. It loaded d3d11 and
  dxgi and got as far as `CreateWindow`, which fails only because a bare
  command-line run has no X server. **So the discriminator is neither the image
  nor the guest's `FEX_*` environment** — the two things this entry has blamed —
  and what is left is the session harness itself, or a change since the assert
  was last seen. It is not closed on that: a negative from a hand-run is weaker
  than the positive it is contradicting, and the next attempt has to be a real
  session launch.

  **One blocker is closed either way, and it is the one that mattered.** With
  `FEX_SILENTLOG=0` FEX's own log reaches the guest's stderr and is readable —
  every run above produced `D <tid> Load module …` lines in the captured output.
  So "the message reaches nowhere we read" was wrong: the sink works, and when
  an `ERROR_AND_DIE` does fire its text will be on that stream as a line
  beginning `A `. Whatever the earlier run did, it was not reading that stream
  with `SilentLog` off in the process that died.

- [x] **At session start the desktop background is black until something
  repaints it.** Fixed in code and not yet watched: a texture is uploaded at
  allocation and after that only on damage, and the desktop's background paint
  lands in the gap. `GLRenderer.updateScene` now distrusts every texture in the
  scene whenever the scene is rebuilt. *Done when:* a program is launched with
  the session and the area around its window is `#161826` in a screenshot.

  **Fixed — confirmed on the device by the reporter, 2026-08-10**, on the build carrying `patches/wine/0012`, the DRI3 present path (`patches/mesa/0006`, `0007`) and SGSR. Which of those is responsible was not isolated per symptom; the record below is what was believed before the fix and is kept because several of its explanations were wrong and worth not repeating.

- [~] **`ipconfig` prints nothing, and interface enumeration was never the
  reason.** *Two patches verified working; a third defect stands behind them.* `patches/wine/0007` works — both halves. Measured 2026-08-10 on the
  device, and the log line the entry has been asking for since it was written:

  ```
  0058:warn:nsi:update_if_table if_nameindex gave nothing (errno 13); getifaddrs found 8.
  ```

  So the fallback fires, `getifaddrs()` answers where bionic's `if_nameindex()`
  is refused, and the NDIS interface table comes back populated — nine entries
  by the time `GetAdaptersAddresses` walks it, with nine matching
  `ConvertInterfaceLuidToGuid` calls. The rate-limiting half was already
  confirmed and still is: **one** `nsi:poll_events bind failed, errno 13`.

  **Two earlier explanations were wrong and both are worth recording, because
  each of them looked settled.**

  *"The count is on the `nsi` warn channel, which `WINEDEBUG` does not enable."*
  True about the class and useless as a diagnosis. Running `ipconfig` with
  `WINEDEBUG=-all,+nsi` enables all four classes and still shows none of patch
  0007's messages — because `nsi.dll` reaches the interface table through
  `DeviceIoControl` on `\\.\Nsi`, and the driver that answers lives in
  **`winedevice.exe`**, a different process. `winedevice` is started once per
  prefix and *survives `wineserver -k`*: measured, its `/proc/<pid>/fd/2` pointed
  at the session's `tmp/guest.out` and its environment carried the session's
  `WINEDEBUG`, not the one on the `ipconfig` command line. The messages appeared
  the moment every Wine process was `kill -9`ed so a fresh `winedevice` inherited
  `+nsi`. **Anything logged by an nsiproxy, mountmgr or plugplay code path has to
  be read this way; a `WINEDEBUG` on a client command line cannot reach it.**

  *"Android denies `bind()` on a `NETLINK_ROUTE` socket, so Wine has no
  interfaces."* The `bind()` denial is real and rate-limiting it was right, but
  it costs only address-change notifications. Enumeration works.

  **The actual cause, measured end to end.** `GetAdaptersAddresses` returns
  `50` — `ERROR_NOT_SUPPORTED`, read straight off a relay trace
  (`Ret iphlpapi.GetAdaptersAddresses() retval=00000032`). Wine's `ipconfig`
  calls it once with a 4 KB buffer and, on anything that is neither
  `ERROR_SUCCESS` nor `ERROR_BUFFER_OVERFLOW`, `get_adapters()` returns NULL and
  `print_basic_information()` calls `exit(1)` — which is why there was no output
  *at all*, not even a header, and why the exit code is 1. The 50 comes from
  `gateway_and_prefix_addresses_alloc(AF_INET)` →
  `NsiAllocateAndGetTable(NPI_MS_IPV4_MODULEID, NSI_IP_FORWARD_TABLE)` →
  `ipv4_forward_enumerate_all`, whose first act after `getifaddrs()` is
  `if (!(fp = fopen( "/proc/net/route", "r" ))) return STATUS_NOT_SUPPORTED;`.
  Android denies an app uid every file under `/proc/net` — verified as
  `app.vessel`: `route`, `ipv6_route`, `dev` and `if_inet6` all `EACCES`. One
  unreadable file in the *route* table therefore discards nine perfectly good
  *interfaces* on the way out.

  It is an upstream inconsistency rather than an Android quirk: the IPv6 sibling
  forty lines down does `*count = 0; return STATUS_SUCCESS` on the identical
  `fopen` failure, which is why the IPv6 forward table succeeded in the same run.

  `patches/wine/0013` makes the two agree, and **it is built, shipped and
  measured — it does exactly what it claims, and `ipconfig` still prints
  nothing.** Run 2026-08-10 against `wine-11.14-canoe.wcp` on the device, with
  the patch confirmed present in the shipped binary (`grep -c unreadable
  lib/wine/aarch64-unix/nsiproxy.so` → 1) rather than assumed from the build log:

  ```
  0058:trace:nsi:ipv4_forward_enumerate_all 0x72b0161cc4 48 0x72b01628c4 32 ...
  0058:warn:nsi:ipv4_forward_enumerate_all /proc/net/route unreadable; reporting 2 route(s).
  00e0:trace:nsi:NsiFreeTable ...
  ```

  The route table now returns rather than failing — the 2 routes are the
  loopback entries `getifaddrs()` had already synthesized before the `fopen`, so
  the count is real and the buffer behind it is filled. Both patches on this
  path are therefore doing their job: 0007 finds 8 interfaces, 0013 stops one
  unreadable file discarding them. **`ipconfig` still exits 1 with zero bytes of
  stdout.**

  **Measured 2026-08-10, and it refutes the paragraph that used to be here.**
  A `+relay` trace restricted to `iphlpapi.GetAdaptersAddresses` via
  `HKCU\Software\Wine\Debug` gives the return value directly:

  ```
  00e0:Call iphlpapi.GetAdaptersAddresses(00000000,00000080,00000000,...) ret=140010144
  00e0:Ret  iphlpapi.GetAdaptersAddresses() retval=c0000005
  ```

  `c0000005` is `STATUS_ACCESS_VIOLATION`. **It is not `ERROR_NOT_SUPPORTED`
  any more, and it is not `dnsapi`** — the previous note named
  `dns_info_alloc`/libresolv as "the strongest candidate", and that was
  inference and it was wrong. The `err:dnsapi:DllMain No libresolv support`
  line is real but is not what fails this call.

  **This is a change in failure mode that arrived with `patches/wine/0013`, and
  it is arguably a regression.** Same program, same device: before 0013 the call
  returned `00000032` (50, a clean `ERROR_NOT_SUPPORTED`); after 0013 it returns
  an access violation. 0013 is the only nsiproxy change between the two runs.
  `ipconfig` fails identically either way — no output, exit 1 — so nothing got
  worse for the user, but a memory fault is a worse thing to ship than a clean
  refusal and 0013 should not be treated as settled.

  *What is not yet known.* Reading `ipv4_forward_enumerate_all` does not explain
  it: the loopback loop fills both entries on the data call (`num < *count` with
  `*count == 2`), so the early return does not obviously leave a short buffer
  behind a non-zero count. `+seh` on the same run prints nothing, which fits the
  status being *returned* rather than raised — so the fault is being caught and
  converted somewhere between `nsi_ioctl` and the caller. **No cause is named
  here on purpose.** Two explanations for this item have already been wrong, and
  both were written down before they were measured.

  **The A/B was run, and 0013 is the cause.** Two Wine builds differing only in
  that patch, same device, same relay probe, the control confirmed by
  `grep -c unreadable …/nsiproxy.so` returning 0:

  | build | `GetAdaptersAddresses` |
  |---|---|
  | with `0013` | `c0000005` — access violation |
  | without `0013` | `00000032` — clean `ERROR_NOT_SUPPORTED` |

  So the patch *introduces* the fault; it does not expose a pre-existing one.
  **`patches/wine/0013` is therefore reverted** — it delivered no user-visible
  improvement (`ipconfig` printed nothing either way) and traded a clean error
  for a memory fault, which is strictly worse to ship. The patch text is in git
  history at `ea5b239` if it is picked up again.

  ***Wrong, and this is the fourth wrong explanation on this item — but the
  first three were guesses and this one was a measurement read backwards, which
  is a different mistake and worth naming separately.** The table is correct.
  "Introduces" versus "exposes" is not something an A/B can tell you when the
  patch's whole effect is to let execution continue: removing a failure moves
  the program past it. The next section is what the code says happens next, and
  it was reverted back in.*

  **The A/B was read wrongly, and the real cause is now named with evidence
  rather than inference. 0013 does not fault — it *unblocks* a fault that was
  always there.** The A/B result is genuine and its interpretation was not: a
  patch that turns a failure into a success moves execution past the failure,
  and what it reached is where the access violation lives.

  `adapters_addresses_alloc` runs three stages in order
  (`iphlpapi_main.c:1310-1320`): unicast addresses, then
  `call_families( gateway_and_prefix_addresses_alloc )`, then `dns_info_alloc`.
  Each one `goto err`s on failure. **Without 0013 the second stage returns 50
  and `dns_info_alloc` is never called at all.** With 0013 the route table
  succeeds and `dns_info_alloc` runs for the first time — and its first act is
  `DnsQueryConfig( DnsConfigDnsServersUnspec, ... )`, which is
  `RESOLV_CALL( get_serverlist, ... )`, which is
  `__wine_unix_call( __wine_unixlib_handle, unix_get_serverlist, &params )`.

  Four facts, each checked rather than reasoned about:

  1. **`dnsapi`'s unix half does not exist in this build.** `libresolv.c` is
     `#ifdef HAVE_RESOLV` from its first line to its last, and this build's own
     configure log says `checking for resolver library... not found` — bionic
     has no `res_getservers`. The `dnsapi.so` inside `wine-11.14-canoe.wcp` is
     4992 bytes and its symbol table holds `crtbegin_so.c`, `libresolv.c` and
     nothing else: **neither `__wine_unix_call_funcs` nor
     `__wine_unix_lib_init`**. Read straight out of the shipped tarball, not
     from the build.
  2. **So the handle stays zero.** `get_unixlib_funcs` (`unix/virtual.c:843`)
     returns `STATUS_ENTRYPOINT_NOT_FOUND` when neither symbol is present,
     `__wine_init_unix_call()` fails, and `__wine_unixlib_handle` — a
     *per-module* variable defined in `libs/winecrt0/unix_lib.c` — is left at 0.
     That is precisely the condition that prints the
     `err:dnsapi:DllMain No libresolv support` line this entry already recorded
     twice. The message was never decoration; it was the diagnosis.
  3. **A zero handle is a null function table, and the dispatcher indexes it.**
     `__wine_unix_call_dispatcher` on aarch64 is
     `ldr x16, [x0, x1, lsl 3]` (`unix/signal_arm64.c:1830`) with `x0` the
     handle and `x1` the call code. With `x0 == 0` it loads from address
     `code * 8`.
  4. **That fault is *returned*, not raised — which is why `+seh` printed
     nothing.** The `ldr` executes after `mov sp, x10` has switched to the
     kernel stack, so `is_inside_syscall()` is true and `handle_syscall_fault`
     takes the `get_syscall_frame` branch: `REGn_sig(0, context) =
     rec->ExceptionCode`. `c0000005` comes back in `x0` as the return value of
     `__wine_unix_call`. `DnsQueryConfig` returns it, `dns_info_alloc` returns
     it at `if (err) return err`, `adapters_addresses_alloc` `goto err`s with
     it, and `GetAdaptersAddresses` returns it — exactly the relay line above,
     with no exception ever dispatched.

  The relay arguments corroborate the path: `GetAdaptersAddresses(00000000,
  00000080, ...)` is `AF_UNSPEC` with `GAA_FLAG_INCLUDE_GATEWAYS` and *without*
  `GAA_FLAG_SKIP_DNS_SERVER`, which is what makes `dns_info_alloc` reach the
  resolver at all.

  *So the earlier note that named `dns_info_alloc` was right about the function
  and wrong about the mechanism — it blamed the missing `res_init`/`res_query`
  set for a bad answer, when the actual defect is that there is no unix library
  to answer at all and the call jumps through a null table. The paragraph that
  declared it "refuted" was itself inference: the relay read the value, not the
  site.*

  **Three patches, and all three are needed:**

  - `patches/wine/0013` is reinstated unchanged apart from its comment, which
    now records why the revert was wrong.
  - `patches/wine/0014` guards `RESOLV_CALL` on `__wine_unixlib_handle` and
    returns `DNS_ERROR_NO_DNS_SERVERS` when it is zero. Not an invented error:
    it is what `resolv_get_serverlist` itself returns when the resolver has no
    servers, so every caller already handles it.
  - `patches/wine/0015` stops `dns_info_alloc` treating that as fatal. An
    adapter with no DNS servers is an adapter, and Windows lists it. This is an
    upstream bug independent of Android — any host with an empty
    `/etc/resolv.conf` fails `GetAdaptersAddresses` the same way. It also
    initialises `size`, which was read uninitialised on the first
    `DnsQueryConfig` of every call since `3bace8862f1`.

  **Fixed. Measured on the device 2026-08-10, in the standalone `files/session`
  prefix and again in the app's own container**, on a Wine carrying 0013, 0014
  and 0015 (`grep -c unreadable …/nsiproxy.so` → 1 in the installed component,
  so the binary was checked and not assumed):

  ```
  Unknown adapter wlan0
      IPv6 address. . . . . . . . . . . : 2401:4900:1c37:faa4:c024:82ff:fe84:bc1
      IPv4 address. . . . . . . . . . . : 192.168.1.5
  …8 adapters, EXIT=0
  ```

  `err:dnsapi:DllMain No libresolv support` is still printed and is now correct
  and harmless: there is no resolver, `RESOLV_CALL` says so instead of faulting,
  and the adapter list is printed anyway. **The DNS server list is empty**, which
  is honest for a container and is the remaining gap — a real one would have to
  come from Android's `net.dns*` properties, and that is a separate item nobody
  has asked for yet.

  *Kept, and it reads differently now: this note named the right function
  before anything had measured it, and was then thrown away for the wrong
  reason. What it got wrong was only the mechanism — the missing
  `res_init`/`res_query` set is not what fails the call; the missing unix
  library behind them is.* An
  `+iphlpapi` trace shows exactly **one** `GetAdaptersAddresses` call, eight
  `ConvertInterfaceLuidToGuid` calls, the route enumeration above, and then
  nothing — no second call, so it never got `ERROR_BUFFER_OVERFLOW`, and no
  output, so it did not get `ERROR_SUCCESS` either. The strongest candidate is
  the defect this entry already predicted: `dns_info_alloc`, the step after the
  one 0013 fixes, which calls `DnsQueryConfig`, and `dnsapi` logs
  `err:dnsapi:DllMain No libresolv support, expect problems` twice in the same
  run because Wine's configure wants `res_init`/`res_query`/`ns_initparse` and
  bionic does not offer the set. Consistent with everything observed, **but the
  return code has not been read** and this project's record on unmeasured
  causes is poor. *Done when:* `GetAdaptersAddresses`' return value is read at
  the point of failure — a relay trace, or an instrumented `dns_info_alloc` —
  and `ipconfig` names an adapter.

## 2. Self-sufficient install

## 3. The launch-type matrix

One program of each kind, launched from the app's own UI and observed — not
inferred from an exit code.

All nine were added and launched from the app's own UI on 2026-08-08, from
`C:\users\u0_a443\Downloads` in the one provisioned container, with Turnip on.
(`u0_a443`, not `vessel`: Wine takes the profile name from the unix user, and
the unix user is the app's uid.)

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
- [x] **Returning to the desktop leaves a black surface.** Reproduced twice: the
  route is right, the orientation lock is right, the pixels are gone. Not the
  old Turnip wedge — see §1.

  **Fixed — confirmed on the device by the reporter, 2026-08-10**, on the build carrying `patches/wine/0012`, the DRI3 present path (`patches/mesa/0006`, `0007`) and SGSR. Which of those is responsible was not isolated per symptom; the record below is what was believed before the fix and is kept because several of its explanations were wrong and worth not repeating.

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
- [x] **Move the `drive_c` reader out of `ui/vm`.** *Done 2026-08-10.*
  `data/PrefixFiles.kt` owns every `java.io.File` call the browser makes —
  listing, guest-path resolution, the SAF import/export copies and the free-space
  query — and `FilesViewModel` no longer imports `java.io.File` for anything but
  the prefix it is handed. The one thing worth more than the tidiness: a listing
  failure now comes back **typed** (`NoSystemDrive` / `NotAFolder` / `Denied`)
  instead of as a nullable list, because "Android refused this folder" and "this
  folder is empty" are indistinguishable from a list and mean opposite things —
  which is the bug that once reported a filesystem with everything in it as an
  empty folder.
## 5. Performance

`docs/OPTIMIZATION.md` is the ranked audit and the measured baseline. Closed
with evidence:

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

## 7. Diagnostics: there is a way to ask louder, and three claims about it are unverified

`docs/DIAGNOSTICS-UI.md` is the design brief and carries the evidence for every
claim below. The problem this section opened on: `WINEDEBUG` was one string
chosen once for a session that is behaving (`core/SessionEnvironment.kt:19`), and
when a session misbehaved the only way to ask a different question was to rebuild
the app. The answer was a Diagnostics surface off the container view — not more
rows in the container sheet, which `params-manifest.json:9-16` and
`docs/DESIGN.md:301` both rule out for good reasons.

**Five of the eight are closed; the three that are not need the device.** The
surface is built (`core/ContainerDiagnostics.kt`, `ui/screens/DiagnosticsSection.kt`,
`ui/vm/ContainerSheetViewModel.kt`) and 78 unit tests across
`ContainerDiagnosticsTest` (36), `SessionEnvironmentTest` (42) hold it to
`docs/LOGGING.md`. What no test can settle is whether the environment those
controls compose is the environment a real session receives, and whether the
output they turn on arrives anywhere a person can read it. Each remaining item
says which measurement would close it.

Reviewed against the code on 2026-08-10; the statuses below are what the tree
says, not what the section said when it was written.

Ordered so that each item is independently shippable and the ones that make the
later ones honest come first.

- [x] **`docs/LOGGING.md` no longer describes what the code does, and everything
  else here cites it.** *Done 2026-08-10.* Three corrections, all found by reading the code against
  the document. `:15` omits `+debugstr` from the configuration block while the
  code sets it (`SessionEnvironment.kt:18`), the test asserts it
  (`SessionEnvironmentTest.kt:122`) and the document's own `debugstr` section
  (`:98-113`) argues for it. `:20` still gives
  `DXVK_LOG_PATH=<container>/logs`, which is the value that was measured wrong —
  DXVK wrote `metro_dxgi.log` beside the log and the log got zero `info:` lines
  — and the code sets `none` (`SessionEnvironment.kt:566`). And "Open item: FEX"
  (`:212-220`) is answered: `native/fex/Source/Windows/Common/Logging.cpp:36-49`
  is the entire Windows logging init, it reads `SILENTLOG` and nothing else,
  resolves `__wine_dbg_output` when not silent and falls back to a
  `%LOCALAPPDATA%` file when that fails. Cheap, and it goes first because
  `SessionEnvironment.kt:15-16` says to change that document before the code.
  A fourth was found on the way and is fixed with them: the block also omitted
  `FEX_SILENTLOG=0` and `FEX_OUTPUTLOG=stderr`, which are logging configuration
  and were the only two variables the code sent that the block did not name.
  *Evidence:* every logging variable in the block was diffed against the map
  `SessionEnvironmentTest`'s *a fully provisioned container produces exactly
  this environment* pins — `WINEDEBUG`, `DXVK_LOG_LEVEL`, `DXVK_LOG_PATH`,
  `VKD3D_DEBUG`, `VKD3D_SHADER_DEBUG`, `TU_DEBUG`, `FEX_SILENTLOG`,
  `FEX_OUTPUTLOG` all equal, and `VKD3D_LOG_FILE` and `MESA_LOG` absent from
  both, which is what "deliberately not set" has to mean to be worth writing.
  Zero mismatches. The FEX section is now *FEX: `SILENTLOG`, and nothing else on
  this platform* and states the finding at `Logging.cpp:36-49`.

- [x] **One log limit drops lines silently, and it is the one that fires first
  when the limits go up.** *Done 2026-08-10.* `SessionLogWriter` announces every truncation it
  performs — dedup, rate limit, elision — and `LOGGING.md:190` states the rule:
  "A log that hides its own truncation is worse than no log." The producer
  channel breaks it. It is `Channel(8192, BufferOverflow.DROP_OLDEST)`
  (`SessionLogWriter.kt:63, 417`), `trySend` reports success either way, and
  nothing counts what it discarded. At today's caps it almost never fires; with
  `+relay` on and the caps raised it is the first thing to bite and the only one
  that will not say so. Counting is a `Channel.onUndeliveredElement` or a
  monotonic counter compared against `lines + dropped` at flush; either way the
  count has to reach the sidecar and the marker.
  It counts now. `onUndeliveredElement` on the channel increments an
  `AtomicInteger` — on the *sender's* thread, which is why it is atomic
  (`SessionLogWriter.kt:89, 94-101`) — `SessionLogMeta.overflowLines`
  (`SessionLog.kt:111`) carries it into the sidecar, and `overflowLogMarker`
  (`SessionLogFormat.kt:270`) puts it in the file both mid-run and at finalise
  (`SessionLogWriter.kt:348-355, 389`).
  *Evidence:* `SessionLogWriterTest`'s *lines the channel throws away are
  counted and admitted in the file* pins the pump behind a latch, sends 20 000
  lines, and asserts the marker is in the file and that
  `overflowLines + lines - 1 == 20 000` — every line either written or dropped
  and counted, the one extra being the marker that says so.

- [~] **Turnip's diagnostics do not reach the session log, so the switch that
  proves it loaded proves it to nobody.** *Built; the measurement that closes it
  needs the device.* Mesa picks its logger at init and
  under Android the default is logcat — `mesa_log_control |=
  MESA_LOG_CONTROL_ANDROID` (`native/mesa/src/util/log.c:119-124`),
  `__android_log_write` at `:388` — and Vessel reads no logcat: grepping
  `app/src/main/java` for it returns one comment and no code. So
  `TU_DEBUG=startup`, which `LOGGING.md:209-210` calls "the ground truth", lands
  somewhere the product cannot see, and every `TU_DEBUG` control the diagnostics
  screen might offer would be a switch with invisible output. The fix is already
  in Mesa: `MESA_LOG=file` (`log.c:64-74`) adds the file logger and
  `mesa_log_file` defaults to `stderr` (`log.c:145`), which is the pipe the
  session log reads. The line parser is ready — `DRIVER_CHANNELS` is
  `{vulkan, winevulkan, turnip}` plus any `mesa`/`tu_` prefix
  (`SessionLogFormat.kt:198-199, 307`). Note `MESA_LOG_LEVEL` is a second gate
  defaulting to `MESA_LOG_INFO` in a release build (`log.h:49-53`), which passes
  Turnip's `mesa_logi` startup lines (`tu_util.cc:135-136`) and drops its
  `mesa_logd` ones (`:108`).
  `MESA_LOG` is now a diagnostics control — *Driver messages in the log*, levels
  `not set` and `file`, off by default (`ContainerDiagnostics.kt:494-508`,
  `FIXED_MESA_LOG` at `:673`) — and it is in `DIAGNOSTIC_SESSION_ENV`
  (`SessionEnvironment.kt:416`) and in `RESERVED_SESSION_ENV` beneath it, so no
  manifest param can reach it. The three Turnip flag rows (`perf`, `nolrz`,
  `noubwc`) are **gated** on it (`ContainerDiagnostics.kt:571-584`): with the
  driver logger off they draw disabled with the reason, because a switch whose
  output nothing can read is worse than no switch.
  `ContainerDiagnosticsTest`'s *Turnip flags are unreachable until the driver
  logger is on* holds that.
  **Still open, and no test can close it:** that the mechanism exists is read
  out of Mesa's source; that Turnip's lines actually arrive in the session log
  has not been observed. `docs/LOGGING.md` marks it *Unverified end to end* in
  those words.
  *Done when:* a device session with `MESA_LOG=file` set has a non-zero
  `grep -c 'TU_DEBUG='` over its session log, and the lines are tagged `driver`
  in the viewer rather than falling through to `wine`.

- [x] **`RESERVED_SESSION_ENV` blocks every variable diagnostics needs to
  write, and unreserving is the wrong fix.** *Done 2026-08-10.* The manifest merge was
  `if (key !in RESERVED_SESSION_ENV) environment[key] = value`
  (`SessionEnvironment.kt:741-743`), and `WINEDEBUG`, `DXVK_LOG_LEVEL`,
  `VKD3D_DEBUG`, `VKD3D_SHADER_DEBUG`, `TU_DEBUG` and `FEX_SILENTLOG` are all in
  the set — a param naming one is dropped with no error and no log line.
  Taking them out would undo the property the file already states for the FEX
  flags at `:249-252`: reserving them is what makes "they stopped being
  settings" stick, because a container document saved while they were switches
  still carries the old values and the merge would hand them back. So a third
  merge stage instead, after the manifest one, gated on a new
  `DIAGNOSTIC_SESSION_ENV` declared as a subset of `RESERVED_SESSION_ENV`. That
  keeps `VKD3D_LOG_FILE` (reserved to guarantee an absence, `:212-216`) and
  `MESA_VK_WSI_DEBUG` (not a debug switch on this build, `:600-663`)
  unreachable by any path.
  That is what was built. `DIAGNOSTIC_SESSION_ENV` is seven names
  (`SessionEnvironment.kt:409-417`) — `WINEDEBUG`, `DXVK_LOG_LEVEL`,
  `VKD3D_DEBUG`, `VKD3D_SHADER_DEBUG`, `TU_DEBUG`, `FEX_SILENTLOG`, `MESA_LOG` —
  and the third stage (`:1059-1062`) runs after the manifest one and drops any
  key not in it. The record reaches `sessionEnvironment` on the container rather
  than as an argument (`ContainerProfile.diagnostics`), which is the same thing
  for this purpose and means a container document carries its own diagnostics.
  *Evidence:* three tests, as specified. `SessionEnvironmentTest` asserts
  `DIAGNOSTIC_SESSION_ENV ⊆ RESERVED_SESSION_ENV` and that it is strictly
  smaller, and that `VKD3D_LOG_FILE` and `MESA_VK_WSI_DEBUG` are in neither;
  *a container with nothing diagnosed produces the environment above, unchanged*
  pins the empty-record case against the golden map; and *a manifest param still
  cannot set `WINEDEBUG` when diagnostics are on* covers the third.

- [x] **The `WINEDEBUG` composer, which is where this gets silently wrong.**
  *Done 2026-08-10.*
  Diagnostics never hands Wine a value; it appends to `WINEDEBUG_CHANNELS`, the
  shape `dllOverrides` already uses and for the reason stated at `:765-771` — a
  later term wins, so a user can add without being able to delete the defaults.
  The per-channel ladder has one non-obvious rule, read out of the parser rather
  than assumed: a channel is created with `flags = (default_flags & ~clear) |
  set` as of that moment (`native/wine/dlls/ntdll/unix/debug.c:122`), and a
  later token ORs into the existing entry (`:103-107`). So after `-all,err+all`
  the "+ Stubs" stop must emit `warn+x,fixme+x` and not `fixme+x` — one term
  gives ERR|FIXME and skips WARN. Two input validations belong here too:
  `WINEDEBUG=help` calls `debug_usage()` which writes to fd 2 and `exit(1)`, so
  the raw field must refuse the literal word or hand the user a session that
  dies before the program starts; and a leading `-all` in the raw field erases
  everything before it.
  `composeWineDebug` (`ContainerDiagnostics.kt:851`) is the one composer, and it
  can only append: it splits the constant and joins the rows' terms after it.
  *Evidence:* `ContainerDiagnosticsTest`'s *each stop emits the terms that force
  exactly its classes* pins the composed string for every stop of every curated
  channel; *the stubs stop names warn as well, because inheritance is from the
  defaults* is the `warn+x,fixme+x` rule specifically; and *every stop of every
  Wine entry leaves minus-all first and the prefix intact* asserts
  `terms.first() == "-all"` and exactly one `-all` across the whole product of
  entries and stops.
  **The raw-field clause was answered by deleting the raw field**, which is
  worth recording rather than ticking. There is no free-text `WINEDEBUG` box:
  the surface is rows only, `isLoggableName` refuses `,+-:`, whitespace and
  anything reaching `char name[15]`, and both hazards are therefore unreachable
  by construction — `help` typed as a channel name composes `-help,err+help,…`
  and never the bare word Wine tests for, and no row can emit a leading `-all`.
  *the word that kills the process cannot be composed, whatever is typed* pins
  that, because "unreachable by construction" is a claim about a shape and a
  later raw-input row would break it in silence.

- [~] **The screen.** *Built; the measurement that closes it needs the device.*
  `docs/DIAGNOSTICS-UI.md` §7 has the control table and §9
  the eight decisions that were open. Four groups — Wine channels with
  levels, the subsystems in their own vocabularies, the raw escape hatch, the
  dangerous tier behind a warning — reached from a row beside *Session logs* in
  the container sheet (`ContainerSheet.kt:149-154`). The one thing not open to
  taste: the vkd3d ladders print vkd3d's own words in vkd3d's own order — `none,
  err, info, fixme, warn, trace` (`native/vkd3d/libs/vkd3d-common/debug.c:38-47`)
  — beside DXVK's `none, error, warn, info, debug, trace`
  (`native/dxvk/src/util/log/log.cpp:146-152`), and the difference is not
  smoothed out. Two subsystems with different vocabularies drawn as one control
  is a screen that lies about what it sets.
  It exists as `ui/screens/DiagnosticsSection.kt` — a panel that takes over the
  container sheet rather than a separate route, entered from `ContainerSheet.kt:275`.
  Three of the four groups shipped; **the raw escape hatch was dropped**, see the
  item above. No string in that file names a channel or a variable: the rows come
  from `ADDABLE_LOGGABLES`, so the screen cannot offer something the composer
  does not implement. The vkd3d/DXVK vocabularies are kept apart as specified and
  *DXVK and vkd3d keep their own words in their own order* asserts it.
  **Still open, and no test can close it:** every assertion here is about the
  map `sessionEnvironment` returns, not about the environment a process on the
  phone actually has. Between the two are `SessionRuntime`, the bootstrap
  filter, and the exec.
  *Done when:* every control on the screen is watched changing the environment
  of a real session on the device — the composed `WINEDEBUG` read out of
  `/proc/<pid>/environ`, not out of a unit test — and a fresh container shows
  nothing switched on.

- [x] **Limits and retention to maximum, with the storage on screen.** *Done
  2026-08-10.* Before:
  5 MB head, two 1536 KB tail segments so 1.5–3 MB of tail, 2000 lines a second,
  ten sessions a container (`SessionLogWriter.kt:401-414`,
  `SessionLogStore.kt:354`) — eight megabytes a session and eighty a container,
  worst case. The caps are not incidental; they are the third of the three
  layers `LOGGING.md:172-190` prescribes as the answer to `err+all` being
  unbounded when things go wrong, so raising them undoes a deliberate design and
  has to be done with the arithmetic visible. At 2000 lines a second and roughly
  120 bytes a line a runaway fills 8 MB in about 35 seconds, so the byte cap and
  the rate limit move together or not at all. And whatever the numbers become,
  the screen that raised them shows the container's current log usage and offers
  *Delete all logs* — a ceiling raised silently is a phone that fills up for a
  reason nobody can find. `docs/DIAGNOSTICS-UI.md` §5 sets out the alternative
  worth pricing first: one container-wide budget the store prunes against,
  rather than a product of three numbers.
  Decided as three ladders rather than one budget, per container, defaulting to
  the top rung: 32 MB head, 16 MB retained tail, 20 000 lines a second
  (`SessionLogLimits`, `ContainerDiagnostics.kt:943-979`). That is 48 MB a
  session and 480 MB a container at ten sessions, against 8 and 80 before, and
  `DIAGNOSTICS-UI.md:344-345` states that arithmetic. The three move together in
  the interface for the reason given above.
  *Evidence:* *the caps default to the top of every ladder* asserts the two
  worst-case numbers directly, so a ladder cannot be extended without moving the
  figure the storage card shows; *the first rung of every ladder is what shipped
  before this was a setting* pins 5 MB / 1536 KB / 2000 as rung one. The screen
  reads real bytes — `SessionLogStore.usageBytes(containerId)` off the container's
  log directory (`ContainerSheetViewModel.kt:372, 412-416`) — and carries
  *Delete all logs* (`DiagnosticsSection.kt:336`).
  *One caveat, stated rather than glossed:* the elision marker is proven at the
  mechanism (`head, marker, previous tail, current tail — in that order`, over
  `mergeTailSegments`), not by a session that actually writes 32 MB. Writing
  48 MB in a unit test to prove an `if` on a byte count would buy the
  arithmetic, which is already asserted, at the cost of a slow suite.

- [~] **The dangerous tier turns itself off after one session.** *Built; the
  measurement that closes it needs the device.* `+relay`,
  `+seh`, `warn+d3d` and above, `DXVK_LOG_LEVEL` at `debug`/`trace` and
  `VKD3D_SHADER_DEBUG=trace` can each fill the cap in seconds — `+seh` runs for
  every raised exception handled or not, with a full register dump, and C++ and
  .NET exceptions are SEH (`LOGGING.md:115-119`) — and none of them is something
  to leave armed. Store the `startedAt` of the session each was armed for rather
  than a boolean, so a control reads as off the moment a session with a
  different stamp starts; that is what survives the app being killed mid-run,
  the same reasoning `SessionLogWriter.kt:66-72` uses for keeping the exit status
  out of the channel. One consequence to decide rather than discover:
  `WINEDEBUG` is in `BOOTSTRAP_SESSION_ENV` (`SessionEnvironment.kt:143`), so
  whatever the tier composes also reaches `wineboot` during prefix creation —
  and the comment at `:113-119` records what a `wineboot` given too much looks
  like, which is a hang with an empty `drive_c` two minutes later. Either refuse
  the tier on a container that has never launched, or say in the warning that
  the first launch will take much longer.
  Built, though not as a stamp. It is a property of the *level*, not of the
  control: each `Loggable` declares `oneSessionFrom`, the first rung loud enough
  to be spent (`ContainerDiagnostics.kt:285, 311`), and `consumed()` (`:78`)
  drops exactly the rows sitting at or above it. A row turned *down* survives.
  That is stronger than the stamp the item proposed — there is no timestamp to
  compare and nothing to get wrong when the app is killed mid-run, because the
  record is rewritten when the session starts rather than interpreted later.
  The `BOOTSTRAP_SESSION_ENV` consequence was taken as the second option: the
  confirmation sheet says the first launch will take much longer
  (`DiagnosticsSection.kt:163-172`).
  *Evidence:* four tests — *one session is a property of the level, not of the
  thing*, *one session spends the row, and a quiet one survives*, *turning a
  loud row down keeps it across launches*, *a fresh container has nothing to
  spend* — plus *the cost warning says the concrete things and names the
  mechanism*.
  **Still open, and no test can close it:** whether the record is actually
  rewritten at the right moment on a real launch.
  *Done when:* a container armed with `+relay` runs one session, and the second
  session's environment — read off the device, not asserted in a test — carries
  the ordinary `WINEDEBUG` string with the screen showing the control off.

## 8. Linux mode — closed

**Decided 2026-08-10 and closed as won't-do.** `docs/LINUX-MODE.md` carries the
full study and the Phase 0/0b measurements; this is the decision and its reason.

Ubuntu is refused, and not on effort. Measured in the app's own domain
(`u:r:untrusted_app:s0`, `Seccomp: 2`, verified at three process depths):

- Stock Ubuntu binaries loaded by an in-process ELF loader are killed by
  **`SIGSYS`**. The trapped syscalls are `set_robust_list(99)`, `rseq(293)`,
  `openat2(437)` and `faccessat2(439)`.
- **`execve` out of `app_data_file` is denied — for a *bionic* ELF exactly as
  for a glibc one** (`rc=126`). The in-process loader starts the *first* process
  of a distro and says nothing about the second, and `apt` → `dpkg` →
  maintainer scripts is a process tree.

Together those mean every process in a distro container would need a Vessel
loader and a permanent `SIGSYS` supervisor. That is the refusal.

**A bionic userland is refused too, for a quieter reason:** it hits the same
exec wall, so it could only ever run a fixed toolset shipped in the APK's native
library directory — no package manager, nothing user-supplied. And Vessel
already delivers a working POSIX toolset through the Windows side: `git version
2.55.0.windows.3`, `ls (GNU coreutils) 8.32`, `sed`, `awk` and real pipelines
from `cmd`, all measured in §4. A second, native copy of that is duplication.

It would also cost `ContainerProfile.mode`, which ends *"one kind of container,
no switch"* — the property `ARCHITECTURE.md:38-42` derives the architecture
badge's meaning from — and force mode-aware `ComponentStore.adoptLatest` before
a `LinuxBase` type could exist at all.

*If the ask returns*, the cheap honest answer is a Terminal front-end onto the
MSYS2 toolchain that already runs: no container mode, no component type, no exec
problem. `tools/probe/` and `docs/LINUX-MODE.md` stay in the repository so this
can be re-answered in an afternoon if Android's rules change.

## 8a. The study's own record, kept for the measurements

`docs/LINUX-MODE.md` is the feasibility study and carries the `file:line` for
every claim below. The short version, and it is not the encouraging one: **PRoot
does not solve the problem this needs solved.** An app at `targetSdk` 36 may not
`execve` a file in `filesDir` (§ *Running downloaded native code*,
`ARCHITECTURE.md:207-216`), the escape hatch is `/system/bin/linker64`
(`WineLaunch.kt:5-27`), and that is **bionic's** linker, which cannot load a
glibc ELF. PRoot is a `ptrace` supervisor that fakes `chroot`; it grants no
permission the process did not already have, and the first thing it would do is
exec the guest's `ld-linux-aarch64.so.1` out of app storage. So the Ubuntu
question is not "is proot fast enough", it is "can a glibc program be started at
all", and nothing in this repository can start one today.

Nothing here is a commitment to build it. §3 of this file closed Linux support
deliberately — *"a glibc rootfs and proot, which is a different product"* — and
`docs/LINUX-MODE.md` § *Reasons this might be the wrong product direction*
argues that the refusal is still correct. The items below are ordered so that the
cheap ones answer whether the expensive ones are possible, and **the first item
is the only one worth doing before that question has an answer.**

- [ ] **Four probes, one day, no product code.** Every downstream decision hangs
  on facts nobody here has measured, and three of the four are one command each.
  They go in `tools/probe/` beside `mapexec.c`, which is the precedent: that
  probe is the reason the `execmod` story is a measurement rather than a theory
  (`ARCHITECTURE.md:239-249`).
  1. `linker64 <rootfs>/lib/ld-linux-aarch64.so.1 --version`, as the app's uid.
     Does bionic's linker run glibc's loader as a program? Predicted no.
  2. `ptrace`: fork, `PTRACE_TRACEME`, one `PTRACE_SYSCALL` round trip. Termux
     relies on this working; nobody has checked it at `targetSdk` 36 on
     Android 16.
  3. `grep overlay /proc/filesystems`, `unshare -Ur true`, one `mount()` call.
     Expected to fail on all three, which is what rules out overlayfs as the
     shared-base mechanism — and "expected" is exactly the word this file
     distrusts.
  4. An in-process ELF loader: `mmap` `ld-linux-aarch64.so.1` from a clean file
     mapping, synthesise auxv, jump, print from a glibc `hello`. This is the
     only route to Ubuntu that keeps `targetSdk` 36, and it is the one thing in
     the whole study with no prior art in this repository. The reason it is not
     obviously doomed is that the `execmod` measurement says a *clean* file
     mapping reaches `RX` (`ARCHITECTURE.md:245-247`) and ELF, unlike PE, does
     not relocate text.
  *Done when:* four `errno`s or four successes are written into
  `docs/LINUX-MODE.md`'s open-questions table, replacing the word "unsure" in
  each row, and read off the device rather than reasoned about.

- [ ] **Decide the product question before the engineering one: Ubuntu, or a
  bionic userland?** These are not the same feature and only one of them is
  reachable today. A Termux-shaped **bionic** userland — NDK-built busybox and
  bash, started with `linkerArgv` exactly as `wineserver` is
  (`SessionRuntime.kt:157-165`) — needs no new mechanism at all, and delivers no
  `apt`, no `dpkg` and nothing from Debian's archive. Ubuntu delivers those and
  needs probe 4 to come back green. Shipping the first while calling it the
  second is the failure mode to avoid, and this file's own launch-type matrix is
  the standard: `.sh` is *"never offered … `NotAProgram`, which is a different
  statement from a refusal and is the right one"*.
  *Done when:* one of the two is written down as the target in
  `docs/LINUX-MODE.md`, with the other named as refused and why.

- [ ] **`ContainerProfile.mode` brings back "the wrong container", and the first
  bug is already identifiable.** `ARCHITECTURE.md:3` is titled "One kind of
  container, no switch" and derives a real property from it: an executable can
  never be in the wrong one, so the architecture badge is information rather than
  a warning (`ARCHITECTURE.md:38-42`). A mode ends that. The concrete cost lands
  immediately in `ComponentStore.adoptLatest`, which walks `ComponentType.entries`
  and adopts the newest version of every type a container does not already
  reference (`ComponentStore.kt:174-177`) — so the moment a `LinuxBase` type
  exists, **every Windows container silently takes a reference to a gigabyte of
  rootfs it will never open**, and `prune()` then correctly refuses to free it.
  Known in advance, and therefore exactly the shape of defect this file warns
  about: *"a rule stated for one call site and not applied to the next one"*.
  *Done when:* adoption is mode-aware, and a test creates a Windows container
  with a `LinuxBase` package installed and asserts `references()` shows nothing
  holding it.

- [ ] **The `.wcp` installer cannot carry a distro rootfs, and should not be
  taught to.** `WcpInstaller` refuses hard links (`WcpInstaller.kt:434-437`),
  device nodes and fifos (`:439-442`) and absolute symlinks (`:587-595`) — and an
  Ubuntu root filesystem is made of all three: coreutils ships hardlinks, `/dev`
  is device nodes, and merged-`/usr` is absolute links. Each refusal is argued
  for in that file and each is right for the packages this project publishes, so
  the answer is to **repack the distro tarball into a relocatable payload in a
  build script** — drop `/dev`, rewrite absolute links relative, break hardlinks
  into copies, record every change — rather than weaken the installer. One
  installer, one security posture, and the mess somewhere it can be diffed.
  *Done when:* a repacked `ubuntu-base` installs through the unmodified
  `WcpInstaller` with nothing refused, and `dpkg --verify` inside it reports no
  differences the repack caused.

- [ ] **A shared base with per-container writable directories, and no symlink
  farm.** Sharing must not be done by hardlinking or symlinking the base into
  each container: a guest write through either mutates the shared inode for every
  container, silently. The symlink version is worse still — it recreates the
  hazard class of the worst defect this project has had (§4, the
  `deleteRecursively` incident) at a hundred thousand files instead of four.
  The shape that works is a read-only `components/LinuxBase/<versionCode>/` with
  per-container writable `etc/ var/ home/ root/ usr-local/`, joined by bind
  mounts rather than by filesystem objects. **The rule the design must hold:
  nothing inside `containers/<id>/linux/` may be a symlink pointing outside it** —
  Android storage reaches a Linux container through a bind, never a link, which
  is the exact opposite of the Windows side where a drive *is* a symlink
  (`DriveMap.kt:28-33`). The honest consequence, which has to reach the
  interface: with `/usr` shared and read-only, **`apt install` cannot work per
  container**, and a base image that is the package set is the version of this
  that does not lie.
  *Done when:* two containers run off one base, each sees its own
  `/etc/hostname`, deleting one leaves the other and the base untouched, and a
  test asserts the no-outward-symlink rule over a provisioned Linux container.

- [ ] **A glibc guest cannot use the Turnip that ships.** `build/turnip.sh:6`
  says what it is — a bionic ELF — and `ARCHITECTURE.md:581-584` already wrote
  the rule down while explaining why Vortek was *not* needed: glibc code cannot
  call bionic's Vulkan driver. Put a glibc userland in a container and the
  problem Vortek solves occurs here. Reinventing Vortek is refused; the two real
  options are a software renderer (right for the *first* GUI, because it
  separates "does X work for a foreign client" from "does a glibc Turnip exist")
  and a second Mesa built against an `aarch64-linux-gnu` sysroot. The second
  looks *more* likely to build cleanly than the bionic ICD did: the KGSL backend
  is gated only on the kmd option (`mesa/src/freedreno/vulkan/meson.build:130-132`),
  `tu_knl_kgsl.cc` contains no `__ANDROID__` at all, and the three platform
  libraries plus the hand-written `sync_wait` that the ICD needed
  (`build/turnip.sh:206-235`) exist only because the NDK's clang defines
  `__ANDROID__` even when meson is told `system = 'linux'` (`:166-169`).
  *Done when:* either a glibc X client draws through the vendored X server with a
  software renderer, or a glibc `vkcube` reports `driver_id=18` — one of the two,
  watched on the device.

- [ ] **The X server needs nothing, and that should be checked rather than
  assumed.** The display seam is already compatible with a foreign client because
  of a change made for Vessel's own reasons: the server binds `/tmp/.X11-unix/X0`
  in the **abstract** namespace (`UnixSocketConfig.java:34-53`,
  `SessionDisplay.kt:459-468`), which has no filesystem, so a guest with a faked
  root — or no root at all — finds it with an unmodified libxcb and no
  configuration. That is reasoned from the code and from how libxcb opens a
  display; no non-Wine client has ever connected to this server.
  *Done when:* any glibc or bionic X client that is not Wine draws a window in a
  session and gets a taskbar button.

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
read; and `.msi` packages reach their UI and do not install.

Sound came off that list on 2026-08-11. A `waveOut` tone played from inside a
container reaches AAudio and drains completely — `blocks_done=6 of 6`, the play
position walking 0 → 28992 → 124896 → 144000 where it had been frozen at 4800.
The cause was not where two sessions of notes said it was, and the note left
behind is worth more than the fix: `oss_write_data` topped the device up to
three periods, AudioFlinger will not start a track until its queue reaches the
stream's buffer size, and on this device the first number is smaller than the
second. Falling short of a start threshold looks exactly like a dead timer
thread, because the code path that decides "nothing to add" returned without
saying so. It says so now.
