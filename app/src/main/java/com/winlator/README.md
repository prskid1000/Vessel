# Vendored code — Winlator X server

Everything under `com/winlator/` in this module, plus `app/src/main/cpp/winlator/`,
is **not Vessel's code**. It is vendored from Winlator and stays under its
original package names so that upstream diffs remain applyable by hand.

| | |
|---|---|
| Upstream | <https://github.com/brunodev85/winlator-app> |
| Commit | `ca3d735a60d653a787daf16d14fafef28d9c2c23` ("gladio: Skip glFinish by default", 2026-07-28) |
| Author | Bruno Rodrigues (brunodev85) and contributors |
| Licence | **LGPL-2.1** — see `LICENSE-LGPL-2.1` at the repo root |
| Vendored on | 2026-08-07 |

Winlator's own `LICENSE` file is GNU LGPL 2.1. Some downstream forks declare
MIT; that cannot apply to code descended from this repository. See
`docs/LICENSING.md`.

## What was taken

- `com/winlator/xserver/**` — the X11 core protocol server: request decoding,
  resource managers, window tree, input, and the extensions.
- `com/winlator/renderer/**` — the GL compositor that turns X windows into one
  textured pass onto a `GLSurfaceView`.
- `com/winlator/xconnector/**` — the epoll unix-socket connector and the wire
  streams, both thin Java over JNI.
- `com/winlator/sysvshm/**` — the fd-passing shared-memory server.
- `com/winlator/{core,math,widget,winhandler,inputcontrols}/` — only the
  handful of support classes the above reach. Each of those files carries a
  `// VESSEL:` header saying what was dropped and why.
- `app/src/main/cpp/winlator/` — `libwinlator`, the JNI half. Nothing in the
  Java above runs without it.
- `app/src/main/res/drawable-nodpi/cursor.png` — the 16×16 root cursor, from
  upstream `res/drawable-hdpi/cursor.png`.

## What was deliberately not taken

| Upstream component | Why not |
|---|---|
| `GLXExtension` + `libgladiorenderer` | ~5k lines of GL-over-a-socket, needed only because Winlator's guest has no real `opengl32`. Vessel builds Mesa/Zink as an ARM64EC `opengl32.dll`, so the guest never speaks GLX. |
| `vortekrenderer` | Exists so box64/glibc code can reach bionic's Vulkan driver. Vessel is bionic-native; the problem does not occur. |
| `virglrenderer` | Superseded by DXVK/vkd3d on Turnip. |
| `winhandler`, `inputcontrols`, `contentdialog`, `widget` (beyond `XServerView`), `XServerDisplayActivity` | Winlator's app shell. Vessel has its own. |
| `glibc_patches/android_sysvshm` | glibc-specific, and the server side of that protocol is already bionic-native here. See `SysVSharedMemory`. |
| `gpu_helper.c`, `wine_registry_editor.c` | The Vulkan probe and shared EGLContext only served gladio; Vessel probes the GPU from Kotlin and writes the registry from the container layer. |

## Local modifications

Every deviation from upstream is marked with a `// VESSEL:` comment, and the
table at the end of this section lists every file that carries one. Both halves
matter: LGPL-2.1 section 6(a) asks for "the complete corresponding
machine-readable source code for the Library **including whatever changes were
used in the work**", and a change nobody wrote down is a change nobody can
reproduce.

**The list is checked, not asserted.** `build/verify_vendored.py` fetches
`brunodev85/winlator-app` at the pinned commit and diffs it file by file against
this tree; `LicensingTest` then asserts, offline, that the set of files carrying
a `// VESSEL:` marker is exactly the set named in the table. Run on 2026-08-08
the diff was **16 modified, 140 byte-identical, 0 files without an upstream
counterpart**, and all 16 were marked. The 140 includes
`app/src/main/res/drawable-nodpi/cursor.png`, which lives outside both trees and
is byte-identical to upstream's `res/drawable-hdpi/cursor.png`.

The modifications, in the order they were made:

1. **`XServer`** — `public final XServerDisplayActivity activity` is gone,
   replaced by a settable `Callback<String> debugSink` that `debugPrint()`
   writes to. Vessel's display host is a Service, not that Activity. The
   constructor therefore takes only `ScreenInfo`.
2. **`XServer`** — `GLXExtension` removed from `setupExtensions()`.
3. **`XServer`** — `winHandler` defaults to `WinHandler.NULL` instead of null,
   because `DesktopHelper` and `InputDeviceManager` dereference it unchecked.
4. **`com/winlator/winhandler/WinHandler`** — rewritten from a 700-line UDP
   client into the two-method interface the X server actually uses, so those two
   call sites stay byte-identical to upstream.
5. **`GLRenderer`** — imports `app.vessel.R` rather than `com.winlator.R`
   (`android.nonTransitiveRClass` is on), and drops the
   `GPUHelper.setGlobalEGLContext()` call that only gladio needed.
6. **`SysVSharedMemory`** — a memfd fallback when `ASharedMemory_create` fails,
   and a long comment recording the bionic shared-memory decision.
7. **`FileUtils.getDirname`** — guarded against a path with no separator, which
   upstream lets throw.
8. **`UnixSocketConfig.createAbstract`** — a second factory that names an
   *abstract* unix socket instead of a filesystem one, and touches no disk.
   Upstream only needs the filesystem form because Winlator's guest runs inside
   a proot rootfs where `/tmp/.X11-unix/X0` is a real directory it owns. Vessel's
   guest is a plain child process of the app: no rootfs, and Android has no
   `/tmp`. The abstract namespace is what makes `DISPLAY=:0` reach this server
   with an unmodified Wine — libxcb (built here with `HAVE_ABSTRACT_SOCKETS`)
   tries `"\0/tmp/.X11-unix/X0"` before the filesystem path.
9. **`xconnector_epoll.c: createServerSocket`** — binds into the abstract
   namespace when the path starts with `@`, which is what the factory above
   produces. Filesystem paths behave exactly as upstream, `unlink` included.
10. **`XServer`** — two additive overloads, `injectKeyPress(byte, int)` and
   `injectKeyRelease(byte)`. Upstream addresses keys only through the `XKeycode`
   enum, which has no entry for Super, Menu or PrtScn; a physical keyboard has
   all three. Reaching `keyboard.setKeyPress` directly would skip the lock the
   existing overloads take, and the listener that runs under it touches
   `windowManager`. Nothing existing changed, so upstream diffs still apply.
11. **Trimmed support classes** — `core/{AppUtils,ArrayUtils,FileUtils,ImageUtils,StringUtils}`
   and `inputcontrols/ExternalController` are subsets, each with a header
   comment listing what survived. Diffs to those files will not apply cleanly;
   diffs to everything else should.
12. **`cpp/winlator/CMakeLists.txt`** — `project(Winlator C)` rather than
   `Project(Winlator)`, so CMake does not enable CXX and go looking for a C++
   compiler this pure-C sub-project has no source for; and `gpu_helper.c` and
   `wine_registry_editor.c` dropped from the source list, matching *What was
   deliberately not taken* above.
13. **`GLRenderer.contextGeneration()`, and the three caches that consult it** —
   `Texture`, `ShaderMaterial` and `VertexAttribute`. Upstream never loses its
   EGL context: Winlator's X server owns a whole Activity for the whole session.
   Vessel's is one screen among several, so leaving the desktop and coming back
   destroys the `SurfaceView` and every GL object in the context — while the
   Java objects holding their names live straight through. All three test their
   id against zero to decide whether they still have to create it, and a stale
   id is not zero. `Texture` bound dead texture names; `ShaderMaterial`'s
   `use()` called `glUseProgram` on a dead program, which is the one that blanks
   the screen by itself because after it fails nothing is drawn at all;
   `VertexAttribute` sourced vertices from a dead buffer and cached an attribute
   location against a program that had been recompiled. The counter is on
   `GLRenderer` because `onSurfaceCreated` is the callback that knows the
   context is new. Deleting the stale names was not an option — a fresh context
   issues them from 1 again, so a delete would very likely destroy whatever now
   owns that number — so each holder abandons its id and recreates. Uniform and
   attribute locations carry the generation too, since they belong to a linked
   program rather than to the context.

   *Fixing only `Texture` left the desktop exactly as black as before.* That is
   worth recording: the texture is the visible symptom and the program is the
   cause, and the first fix was verified on the device as not working.

14. **`GLRenderer.compositedFrames()`** — a `volatile long` incremented in
   `onDrawFrame`, and a full re-upload of every window's texture whenever
   `updateScene()` rebuilds the scene. The counter is what Vessel's taskbar
   frame-rate readout and its session metrics are computed from; a counter
   rather than a rate because turning two reads and a wall clock into frames per
   second belongs to the Android side, which already has both. The re-upload
   fixes a black desktop at session start: a texture is uploaded once at
   allocation and after that only on damage, and the desktop's background paint
   lands in the gap — after its texture was allocated empty, before anything
   else drew, with nothing to damage it again. A map, an unmap or a resize is
   the one moment the scene is known to be wrong, so it is where every texture
   in it is distrusted.
15. **`Window.requestClose()`, `Property.getIntCount()` and the new
   `events/ClientMessage`** — the vendored server had no `ClientMessage` (event
   code 33) at all, so there was no way to send `WM_DELETE_WINDOW` and Vessel's
   taskbar could focus a guest window but never close one; the only other
   control ends the entire session. `requestClose()` checks the window's
   `WM_PROTOCOLS` list — hence `getIntCount()`, since every existing property
   caller reads word 0 and knows that is all there is — and sends the message
   only if the client asked to receive it, returning false otherwise so the
   caller can decide whether to escalate to killing the process. It is delivered
   to every listener rather than through `isInterestedIn`, because a
   ClientMessage from the window manager is not selected for by any event mask.
   `winex11.drv` turns it into `WM_CLOSE`, so the program still gets its "save
   changes?" dialog — which is the whole reason to ask rather than kill.
16. **`WindowManager.moveResizeWindow()`** — the server had no way to place a
   window from its own side. `changeWindowGeometry` is private and every route
   to it starts from a client request with an `XInputStream` to parse, so a
   window manager written above this tree could focus, map and unmap a window
   but never move or size one. Vessel needs it because
   `patches/wine/0010` strips `WS_CAPTION` and `WS_THICKFRAME` from every
   top-level window — a caption is unhittable on a phone and cost 41 rows that
   nothing painted — so the shell offers temporary drag borders from the taskbar
   menu instead, and those have to be able to move the window they are drawn
   around. Deliberately the same shape as the non-redirect branch of
   `configureWindow`: change the geometry, then `ConfigureNotify` to the window
   and to its parent, so a client cannot tell a shell drag from an ordinary
   window-manager configure. The resize path matters most — it is
   `changeWindowGeometry` that recreates the backing `Drawable` at the new size,
   and a resize that skipped it would leave the window sampling a stale buffer.

17. **`DRI3Extension` and `PresentExtension` name the request they refuse** —
   both dispatch switches ended in a bare `default: throw new
   BadImplementation()`, so an unimplemented extension request reached the
   client as an X error and left **no record of which request it was**. That
   cost a day: with Mesa's DRI3 WSI compiled in for the first time,
   `vkCreateSwapchainKHR` returns `VK_ERROR_SURFACE_LOST_KHR` after the surface,
   the queue, the capabilities and the formats all come back good, and no
   channel anywhere said why — see `docs/TODO.md`, *Zero-copy present*. Both
   defaults now log at WARN, under one tag, with DRI3's request names spelled
   out; the refusal itself is unchanged. WARN and not DEBUG because an
   unimplemented request is always either a defect in this server or a genuine
   version mismatch, never routine traffic. Two candidates this makes decidable
   rather than arguable: DRI3 `FenceFromFD(4)`, which Mesa issues once per
   swapchain image, and Present `QueryCapabilities(4)` — this tree implements
   DRI3 opcodes 0,1,2,3,7 and Present 0,1,3, and neither list contains a 4.

18. **`PresentExtension.queryCapabilities()`** — `PresentQueryCapabilities`,
   opcode 4, which this tree never implemented. Mesa's X11 WSI issues it while
   creating a DRI3 swapchain and treats a failure as fatal, so refusing it
   produced `vkCreateSwapchainKHR -> VK_ERROR_SURFACE_LOST_KHR` *after* the
   surface, the queue, the capabilities and the formats had all come back good.
   Found by modification 17 rather than by reading: one WARN line,
   `Present request opcode 4 is not implemented`, during a
   `tools/gfx/run-x11present.sh --wsi dri3` run. Both standing theories —
   `xcb_dri3_open` and the missing DRM fd — were wrong, and neither is reached.
   The reply is one `CARD32` of capability bits and this server answers
   **`None`**, which is the honest answer and not a stub: async present, fence
   support and a real UST clock are each things it does not have, the UST it
   reports being `System.nanoTime()` against a fabricated 60 Hz interval. A
   client's fallback for each missing bit is the path it already takes today.

19. **`XRequestError.sendError()` and `XClientRequestHandler`'s unknown-opcode
   branch both say what they refused** — the server could reject a request two
   different ways and neither left a record. `sendError` is the single choke
   point every X error to every client passes through; it now logs the error,
   the major opcode and the minor, because either opcode alone is ambiguous
   (the major names the extension, allocated from -100 upward at runtime, and
   the minor names the request within it). The second is worse and is not an X
   error at all: an unknown *major* opcode throws `UnsupportedOperationException`,
   a `RuntimeException` that bypasses the `XRequestError` catch entirely,
   unwinds the request handler and drops the connection — which reaches the
   client as *"X connection to :0 broken"* with no protocol error before it,
   exactly how the first zero-copy attempt presented. Neither branch changes
   behaviour; both only start speaking.

   *What they then proved, which is the point of adding them:* with both in
   place a `--wsi dri3` run logs **nothing at all**, so
   `vkCreateSwapchainKHR -> VK_ERROR_SURFACE_LOST_KHR` is **not** this server
   refusing anything. Worth knowing before anyone implements DRI3 `FenceFromFD`
   on the theory that a missing request is the cause. Registered extensions are
   BigReq, MIT-SHM, DRI3, Present, SYNC and Composite — no XFIXES, which Mesa
   probes but guards every use of behind `has_xfixes`.

20. **`XFixesExtension`, a new file, and it is why zero-copy never worked** —
   Mesa's X11 WSI creates an XFIXES region per swapchain image on the DRI3 path
   and does so **unguarded**, with a `has_xfixes` flag sitting right there
   unconsulted (`wsi_common_x11.c`, `x11_image_init`). When the server does not
   advertise XFIXES, **libxcb tears the connection down client-side** with
   `XCB_CONN_CLOSED_EXT_NOTSUPPORTED` before sending anything — so no request
   arrives, no protocol error exists to log, and the application just loses its
   display. Unfindable from this side, which is why three earlier explanations
   were wrong.

   *Evidence, the probe in the retry path, before and after:*

   ```
   before  dri3: conn_err=0 then conn_err=2  -> SURFACE_LOST (-1000000000)
   after   dri3: conn_err=0 then conn_err=0  -> got past it
   ```

   The visible symptom had been `vkCreateSwapchainKHR` failing on a NULL
   `GetGeometry`, which was never the cause: Mesa tries the swapchain, the
   attempt kills the connection, Mesa retries, and the retry fails at its first
   request. The software path is unaffected because it returns from
   `x11_image_init` one line before the region is created.

   Regions only — `QueryVersion`, `CreateRegion`, `SetRegion`, `DestroyRegion`
   — which is the whole set Mesa's swapchain touches; everything else is
   refused through modification 17's log. Version **2.0** is reported, the
   version regions arrived in and the lowest that satisfies Mesa's
   `major_version >= 2`; claiming 5 or 6 would advertise cursor and
   pointer-barrier requests that would then be refused at the first call, which
   is the same shape of failure this file exists to remove. The rectangles are
   stored and **not yet acted on**, because `PresentExtension.presentPixmap`
   skips the `update` and `valid` fields and copies the whole pixmap — the ids
   must still be real, since a client sets, reuses and destroys them.

21. **`WindowManager.moveResizeWindow()` sends `Expose`** — modification 16
   added the geometry change and the two `ConfigureNotify`s and stopped there. A
   `ConfigureNotify` tells a client its geometry moved; it does not tell it to
   draw, and `changeWindowGeometry` reallocates the backing `Drawable` at the
   new size, so after a resize the window's pixels are whatever the fresh buffer
   holds until something paints them. Wine erases with the class background
   brush — `COLOR_WINDOW`, white — and then waits for a `WM_PAINT` that never
   arrives. Reported as a white region after dragging a resize border **that
   clears if you minimise and restore**, which is the diagnosis in one sentence:
   `mapWindow` (:110) already sends `Expose`, so remapping produces the repaint
   resizing never asked for. The whole window rather than the newly exposed
   strip, because the `Drawable` was reallocated so none of it is known good,
   and because a *shrink* exposes no new area yet still needs the client to lay
   out again.

   **This did NOT fix the reported symptom, and it never could have. The cause
   is now measured and it is on the Wine side — see `patches/wine/0012`.** The
   entry stays because removing the line would be churn, but nothing here is
   load-bearing and the next reader should know two things about it.

   *First, it is a duplicate.* `changeWindowGeometry` (:228-230, **upstream**
   code) already ends with `window.sendEvent(new Expose(window))` on every
   resize of a mapped input-output window — and that overload (`Window.java:400`)
   delivers to **every** listener regardless of event mask, so it is strictly
   wider than the masked send added here. Wine was already receiving an `Expose`
   at the full new size before this change, and now receives two per resize
   step. Confirmed in the trace: `X11DRV_Expose win 0x1006a (1800001) 0,0
   685x513` appears twice around each `ConfigureNotify`.

   *Second, `Expose` cannot repair this even in principle.* `X11DRV_Expose`
   hands the rectangle to `NtUserExposeWindowSurface`, and
   `expose_window_surface` (`dlls/win32u/window.c:2436`) — when the window has a
   surface, which a mapped window always does — **ignores its redraw flags
   entirely** and only re-flushes existing surface bits, after
   `intersect_rect( &exposed_rect, &exposed_rect, &surface->rect )`. The surface
   is still the pre-resize size, so the newly exposed strip is clipped away
   before anything is flushed. An `Expose` asks Wine to re-send pixels it has;
   it does not ask Wine to make pixels it does not have.

   *What the white actually is.* Wine never resizes the **Win32** window in
   response to a WM-initiated resize, because `window_update_client_config`
   returns 0 at `if (is_virtual_desktop()) return 0;` and Vessel runs
   `explorer /desktop=`. So the X window and its freshly allocated `Drawable`
   grow while Wine's window surface does not, and the new strip is never
   painted by anybody. Minimise/restore clears it because
   `window_update_client_state` has no such guard: the `WM_STATE` Iconic→Normal
   transition yields `SC_RESTORE`, whose win32u branch calls
   `set_window_normal_placement()` with the host rect — the only path in the
   whole stack that copies the WM's geometry onto the Win32 window. The
   compositor was never involved, and neither was the class background brush.

21. **`DRI3Extension` records what its two version numbers actually mean** —
   comments only, no behaviour change, and both of them correct a note that
   would otherwise send the next person the wrong way.

   The first is the **advertised minor version, deliberately still 0**. The
   obvious move once DRI3 negotiates is to bump it to 2, because this tree
   implements `PixmapFromBuffers` (opcode 7) and that is a 1.2 request — and
   its wire parse *is* byte-correct for 1.2, 60 bytes of body checked field by
   field. But 1.2 is also `GetSupportedModifiers` (6) and `BuffersFromPixmap`
   (8), neither of which exists here, and the handler ignores both
   `num_buffers` and the modifier. Claiming 1.2 promises three requests and
   delivers one. It also buys nothing: with `has_dri3_modifiers` false Mesa
   asks for no modifier list, Turnip makes a `DRM_FORMAT_MOD_LINEAR` image and
   `x11_image_init` sends the single-fd `PixmapFromBuffer` (2) — which is the
   request this server implements, and the only shape a CPU `mmap` of the
   dma-buf can consume. That path measures `mean_ms=0.532` against 1.8–2.1 ms
   for the software path.

   The second is **`FenceFromFD` (4), which modification 17 named as a
   candidate for the `SURFACE_LOST` and which was not that** — the cause there
   was the missing XFIXES of modification 20. It is still a real defect, and a
   nastier one than a returned error: the request arrives with an fd over
   `SCM_RIGHTS`, and refusing it **never consumes that fd**. `XInputStream`
   holds one ancillary-fd queue per connection and `getAncillaryFd()` pops its
   head, so one unconsumed fd shifts the queue for the rest of the session —
   the next `PixmapFromBuffer` is handed the previous image's 4096-byte
   xshmfence page instead of its 3686400-byte dma-buf. Measured: the second
   present took the whole app down with
   `signal 7 (SIGBUS), code 2 (BUS_ADRERR)` inside `Drawable.copyArea`,
   faulting exactly one page into the source.

   `FenceFromFD` is a DRI3 **1.0** request, so this server is non-conformant at
   the version it already advertises. Implementing it needs two things that are
   not in this file: the fence has to be mapped and triggered through the
   client's shared page, and `SyncExtension` tracks fences as a boolean and
   never touches a page — `PresentExtension.presentPixmap` already calls
   `syncExtension.setTriggered(idleFence)` at exactly the right moment, so that
   half is done. Until then `patches/mesa/0007` stops Mesa asking for the
   fence, which is sound against *this* server because `presentPixmap` copies
   synchronously before it sends `PresentIdleNotify`; `VESSEL_WSI_DRI3_FENCE=1`
   turns the request back on to re-measure once the server can answer it.

22. **`SGSRMaterial`, a new file, and the two vendored files that reach it —
   `GLRenderer` and `ShaderMaterial`.** The compositor's only upscale was
   `GL_LINEAR` (`Texture.java:33-34`), so running the guest below the screen's
   resolution meant accepting a soft picture. **Snapdragon Game Super Resolution
   1.0** is Qualcomm's single-pass edge-directed spatial upscale, written for
   Adreno, and it is structurally a drop-in for the existing blit: one fragment
   shader, one extra uniform, no history buffer and no extra render target.

   *Licence, and it is not the one this was requested as.* SGSR is
   **BSD-3-Clause**, not Apache-2.0 — `SPDX-License-Identifier: BSD-3-Clause`,
   Copyright (c) 2023 Qualcomm Innovation Center, Inc. Clause 1 asks that
   redistributed source retain the notice, so the header is reproduced verbatim
   above the shader body; the full text ships as `res/raw/license_bsd_sgsr.txt`
   and is listed on the Licences screen. `docs/LICENSING.md` carries the entry
   and `LicensingTest` asserts both halves. The shader is
   `sgsr/v1/include/glsl/sgsr1_shader_mobile.frag` from
   `SnapdragonStudios/snapdragon-gsr`, and the four adaptations it needed — the
   `#version` line, a constant `textureGather` component, dropped `layout`
   qualifiers and the removed Vulkan uniform-block branch — are each argued for
   in `SGSRMaterial.SGSR_FRAGMENT_BODY`'s comment. Nothing in the algorithm or
   its constants was touched.

   *It is off unless it helps*, which is the part that made this worth doing
   rather than a switch. `useSGSRFor()` engages only when the window's texture is
   drawn into more pixels than it has texels — on the smaller axis, so a
   one-axis stretch does not qualify — and never for a transparent window,
   because SGSR's own last line is `color.w = 1.0; //assume alpha channel is not
   used`. At 1:1 the shader is not merely a no-op, it is never bound.
   `SGSRMaterial.isSupported()` is the third gate: `textureGather` is not in GLSL
   ES 3.00, so the shading-language version is read back once per context and the
   shader is compiled at what the driver reports rather than at a guess. Below
   3.10 nothing is compiled at all — `compileShaders` throws on failure and a
   black desktop is a far worse outcome than a soft one.

   *What this cost in the vendored files.* `GLRenderer.renderWindows()` bound one
   material outside the window loop; with two of them the bind moves inside it,
   behind `bindWindowMaterial()`, which keeps the common case at exactly one
   `glUseProgram` per frame as before. `ShaderMaterial` gains an additive
   `setUniformVec4`, so SGSR's one `ViewportInfo[0]` uniform shares the
   per-program location cache instead of being the only uniform in the renderer
   that calls `glGetUniformLocation` every frame. The new vertex shader pins
   `position` to `layout(location = 0)` because `VertexAttribute` resolves an
   attribute location once per context and reuses it across programs — the
   cursor and window materials already rely on a lone attribute landing at 0, and
   a third program that only *probably* does would turn that into an assumption.

23. **`DRI3Extension.fenceFromFD()`, and the two new files it needs —
   `XShmFence` and `cpp/winlator/src/xshmfence.c`.** `FenceFromFD` is DRI3
   opcode **4**, a **1.0** request, and this tree advertises 1.0 and did not
   implement it. That is a conformance defect on its own, and a destructive one
   rather than a returned error: the request arrives with a file descriptor over
   `SCM_RIGHTS`, and refusing it never consumes that fd. `XInputStream` keeps one
   ancillary-fd queue per connection and `getAncillaryFd()` pops its head, so one
   unconsumed fd shifts the queue for the rest of the session — the next
   `PixmapFromBuffer` was handed the previous image's 4-byte fence object,
   mapped as a whole page, in place of its 3686400-byte dma-buf. Measured: the
   second present took the app down with `signal 7 (SIGBUS), code 2
   (BUS_ADRERR)` inside `Drawable.copyArea`, faulting exactly one page into the
   source.

   *What an implementation needs, and where each half lives.* The fence has to
   be **mapped and triggered through the client's shared page**, because that
   page is the state and not a mirror of it: Mesa calls `xshmfence_reset()` on
   its own mapping immediately before every `PresentPixmap`
   (`wsi_common_x11.c:1804`) and never tells the server, so a boolean kept on
   this side is stale from the first frame. The other half already existed —
   `PresentExtension.presentPixmap` calls `syncExtension.setTriggered(idleFence)`
   after the copy, under the window's render lock, before it sends
   `PresentIdleNotify`.

   *The layout was read, not guessed*, out of libxshmfence 1.3.3 — the version
   `native/pins.env` pins and `build/x11-sysroot.sh` builds — because getting it
   wrong corrupts another process's memory rather than producing a wrong answer
   here. `struct xshmfence` is **one `int32_t` at offset 0**; `xshmfence_alloc_shm`
   `ftruncate`s the fd to exactly `sizeof(struct xshmfence)`, and only the
   *mapping* rounds up to a page, which is why the stray-fd copy above faulted
   one page in rather than immediately. The word is three-valued and not
   boolean: `0` untriggered with no waiter, `-1` untriggered with a waiter inside
   `FUTEX_WAIT`, `1` triggered. The four operations are transcribed from
   `xshmfence_futex.c` in `cpp/winlator/src/xshmfence.c`, which is C and not a
   `ByteBuffer.putInt` for two reasons: a store without `FUTEX_WAKE` loses a
   waiter that is already asleep, and `VarHandle`'s compare-and-set is API 33
   against this module's `minSdk 31`.

   `SyncExtension` gains a `FenceInfo` beside its `SparseBooleanArray` holding
   the page and the owning client; every read and write goes to the page when
   there is one and to the boolean when there is not, so a fence made by plain
   SYNC `CreateFence` behaves exactly as upstream. `fenceFromFD` pops the fd
   *before* any validation can throw, and `pixmapFromBuffer` and
   `pixmapFromBuffers` were changed to do the same — a `BadWindow` or
   `BadIdChoice` from either of those would have shifted the queue just as
   surely as the refusal did. `pixmapFromFd` also stops dereferencing a null
   `createDrawable` result, which upstream turns into an NPE out of the request
   thread and therefore a dropped connection rather than an X error.

   **Written and compiled; not measured.** Nothing exercises this path yet:
   `patches/mesa/0007` still stops Mesa asking for an idle fence, and it takes
   `VESSEL_WSI_DRI3_FENCE=1` to turn the request back on. Until
   `tools/gfx/run-x11present.sh --wsi dri3` has been run with that set, whether
   the fence costs anything is unknown, and so is whether this implementation is
   correct on the device. `DRI3Extension.MINOR_VERSION` stays at 0 — see item 21
   for why 1.2 is the wrong lever; what changed is only that 1.0 is now an honest
   claim rather than an overstatement.

24. **Per-client resource reclamation for the extensions —
   `Extension.freeClientResources()`, `XServer.freeClientExtensionResources()`,
   `XClient.freeResources()`, and the three extensions that implement it
   (`SyncExtension`, `PresentExtension`, `XFixesExtension`) — plus the missing
   `registerAsOwnerOfResource` in `DRI3Extension`.**

   `XClient.freeResources()` frees windows, pixmaps, graphics contexts and
   cursors, and it frees exactly those that were registered through
   `registerAsOwnerOfResource`. **`DRI3Extension.pixmapFromFd` never registered
   anything**, so a DRI3 swapchain's pixmaps outlived their client. That is not
   a slow leak: `ResourceIDs.free()` returns a departing client's id base to a
   sorted set and `get()` takes the smallest, so the *next* connection is handed
   the same base, generates the same XIDs, and collides with its own
   predecessor. Measured as alternating pass and fail on a second
   `tools/gfx/run-x11present.sh --wsi dri3` run against one live session,
   reported as `BadIdChoice`.

   The extensions have the same problem one layer up and no mechanism at all for
   it, so `Extension` gains a no-op `freeClientResources(XClient)` that `XServer`
   fans out and `XClient.freeResources()` calls — ordered deliberately *before*
   `resourceIDs.free(resourceIDBase)`, so no id can be reissued while a stale one
   is still registered. Three extensions had something to release:

   - **SYNC** — fences, which upstream never removes even on `DestroyFence`
     failure paths. With item 23 in place these also own an mmap, so leaking one
     leaks a mapping as well as an id.
   - **Present** — the `SelectInput` event contexts. This one is worse than a
     collision: a stale entry makes the next client's `SelectInput` throw
     `BadMatch` (`event.client != client`), Mesa issues that request unchecked,
     and the swapchain then runs with an event id the server never associated
     with it — so every `PresentCompleteNotify` and `PresentIdleNotify` goes to a
     dead connection and the client waits for an idle image forever. A hang, not
     an error.
   - **XFIXES** — the regions. Mesa destroys its own in `x11_image_finish`, so
     this only matters for a guest that crashed; the map is now also
     `synchronized`, which it should have been from the start given
     `setMultithreadedClients(true)`.

   *The harness leaked too and has been fixed separately* —
   `tools/gfx/x11present.c` returned without destroying its swapchain, so
   `xcb_free_pixmap` was never even sent — but the server-side fix is the one
   that matters, because a crashed guest leaks identically and cannot be asked
   to be polite. **Written and compiled; not measured.** Nothing has yet run two
   `--wsi dri3` passes against one session to see the `BadIdChoice` gone.

   *Measured on the device 2026-08-10 and both halves hold.* Six probe clients
   in succession against one live session all returned `result=PASS`, where the
   second used to die. Then the case the fix actually exists for: a probe run
   to `first_frame_ms=1.99` — inside the present loop, holding three DRI3
   pixmaps, a fence each and a Present event context — was `kill -9`'d, and the
   next client connected and passed with no `VesselXProto` line of any kind.

25. **`MITSHMExtension.attach()` logs that it served the request** — one
   `Log.d`, and the reason it is worth a vendored modification is that it
   decides the fate of a Mesa patch.

   Mesa's `has_mit_shm` is **not** read off the extension list. It comes from
   `x11_xcb_display_supports_xshm()`, a real `ShmAttach` round trip
   (`wsi_common_x11.c:366`), and it gates `defers_sw_blit_wait =
   wsi->sw && !chain->has_mit_shm` — the whole of `patches/mesa/0003`. So
   "`XServer` registers MIT-SHM, therefore 0003 is dead code" is an inference
   with a false premise, and it was written into `docs/TODO.md` for an hour
   before this line disproved it: a 200-frame `--wsi sw` run produces **zero**
   attaches, so `has_mit_shm` is false and 0003 is live.

   Kept rather than reverted after the fact because the question recurred twice
   in one afternoon, and because the answer changes whenever Mesa's build flags
   do — `HAVE_X11_DRM` and `HAVE_SYS_SHM_H` are both in the condition, and
   `patches/mesa/0006` is in the business of moving exactly those.

26. **`Texture.updateFromDrawable()` times the whole-window re-upload** — a
   nanosecond clock around the one `glTexSubImage2D`, a running mean and max,
   and a line every 120 uploads, all of it behind
   `setprop log.tag.VesselUpload DEBUG` with the clock unread when it is off.

   It is here to settle `docs/TODO.md` item 27, which proposes deleting this
   upload by backing every window's content with a `GPUImage` and prices the
   change at "3.6 MB of CPU→GPU upload per composited frame". That figure is
   1280 × 720 × 4 — a product of a resolution and a pixel size, not something
   anyone has watched happen. And the reason to doubt it is in this same tree:
   `PresentExtension.selectInput` and `DRI3Extension` already swap a presenting
   window's content to a `GPUImage`, whose `updateFromDrawable` is a no-op, so
   on the shipping Turnip → DRI3 path the window the projection is about has
   been zero-copy since before the item was written. What still runs through
   here is Wine's desktop and whatever GDI paints.

   Deciding that by measurement rather than by argument is the whole point:
   an item priced from arithmetic has already been wrong once in this file —
   the same audit's 0.60 ms fence-wait projection came back as 0.15 ms and then
   as nothing at all. Kept rather than reverted after the answer arrives,
   because the answer changes the moment a client stops using Present.

27. **`DMA_BUF_IOCTL_SYNC` around the CPU read of a DRI3 pixmap —
   `sysvshared_memory.c`, `SysVSharedMemory`, `Drawable`, `DRI3Extension`,
   `PresentExtension`.** Upstream maps the client's dma-buf and reads it. That
   is not the dma-buf userspace ABI: a CPU accessor must bracket every access
   with `DMA_BUF_IOCTL_SYNC`, `START` before and `END` after, which is what
   drives the exporter's `begin_cpu_access` / `end_cpu_access` ops and
   therefore the cache maintenance. Without the `START | READ` the CPU may read
   lines that predate the GPU's writes into the swapchain image, and a stale or
   half-stale frame looks exactly like a driver fault.

   The pieces: `dmaBufSyncRead()` in `sysvshared_memory.c` issues the ioctl
   (`<linux/dma-buf.h>` **is** in the NDK r27 sysroot, so nothing is declared
   locally); `dupFd()` next to it exists because the ioctl needs a descriptor
   and `DRI3Extension` closes the client's as soon as the request returns, so
   the mapping outlives it and an fd would not. `Drawable` carries that dup and
   brackets the **source** side of `copyArea` — the pixels are touched there,
   so no future reader can forget it — with the answer latched off after the
   first refusal, since a `-ENOTTY` fd answers the same way sixty times a
   second.

   **It buys coherency, not cacheability, and the difference is the whole
   reason it is not a stutter fix.** The mapping's memory type is fixed at
   `mmap` time by the exporter's `.mmap` op setting `vm_page_prot`; the sync
   ioctl never touches the vma. If the read side is slow because it is
   uncached, this does not help and an invalidate over a few megabytes costs a
   little more. What the exporter is says the mapping is *cached*: the guest's
   Turnip does not allocate exportable images from KGSL at all but from
   `/dev/dma_heap/system` (`tu_knl_kgsl.cc`, `bo_init_new_dmaheap`, heap path
   at `:2706`), and the kernel's system dma-heap leaves `vm_page_prot` alone.
   Inference, not measurement — nothing readable on the device reports a
   mapping's memory type, and `PresentExtension`'s copy timer is still the only
   instrument that can separate the two.

   `PresentExtension.presentToContent` also carries, in the same change, the
   record of why the copy stays **synchronous on the request thread**: Mesa's
   DRI3 swapchain frees an image only on `PresentIdleNotify`
   (`wsi_common_x11.c:1664`), idle may not be sent before the copy completes
   under any design, so an async copy unblocks the client at the same instant a
   synchronous one does — while the lock discipline needed to keep the source
   mapping alive across it would put the compositor back behind the memcpy
   through `DRAWABLE_MANAGER` (`GLRenderer.renderWindows`), which is the bug
   the `renderLock` narrowing had just removed.

28. **The `copyArea` copy split into row bands across a worker pool — a new
   file `cpp/winlator/src/copy_pool.c` with its header, plus
   `cpp/winlator/src/drawable.c`, `CMakeLists.txt`, `Drawable`, and
   `PresentExtension`.** Upstream's `Java_..._Drawable_copyArea` is a single
   `memcpy` when the strides match and a per-row loop when they do not, both on
   the calling thread. That is the right shape for upstream, which has no DRI3
   and so nothing that copies a whole frame out of a client's mapping every
   vblank. On the path item 27 describes it is not: with the source an `mmap` of
   Mesa's dma-buf, `PresentExtension`'s timer measured `mean=19114us` for a
   1280x720 frame — about 154 MB/s, which is uncached read speed rather than
   the several GB/s a cached `memcpy` reaches on this part.

   So the rectangle is now handed to `copyPoolCopyRows`, which divides it into
   row bands across a persistent pthread pool. Four participants by default on
   this eight-core device, clamped down by `sysconf(_SC_NPROCESSORS_ONLN)` and
   overridable at run time with the `debug.vessel.copy_threads` system property,
   read once when the pool is created. Payloads under 256 KB — which is nearly
   all ordinary X `CopyArea` traffic, since plain requests land in this same
   function — skip the pool entirely, and so does a second concurrent caller,
   which falls back to the single-threaded path rather than queue behind the
   first. Both of upstream's shapes survive intact *per band*: a band whose
   strides match is still one `memcpy`.

   **Why splitting is expected to pay here and would not on a cached buffer.**
   An uncached read is latency-bound, not bandwidth-bound — the core stalls
   waiting for each line rather than saturating a bus — and stalls issued from
   different cores overlap. On a cached copy the single thread already saturates
   memory and the split would buy nothing. The post-split number is **not
   measured**; the timer that produced the 19 ms is deliberately still in
   `presentToContent` to produce its successor.

   The pool is *joined* before `copyPoolCopyRows` returns, and that is the
   load-bearing property rather than an implementation detail. It keeps the copy
   synchronous on the request thread, so everything item 27 records stays true
   without re-argument: one `DMA_BUF_IOCTL_SYNC` `START`/`END` pair spans every
   byte read, `PresentIdleNotify` still fires exactly at copy completion, the
   source mapping is alive for the whole read under the locks the request
   already holds, and no new lock is taken. Workers never see a `JNIEnv` — the
   calling thread resolves both direct-buffer addresses once and hands over raw
   pointers — so there is no `AttachCurrentThread` anywhere in it. The pool is
   created on first use and never torn down; the X server outlives anything that
   would tear it down.

   One deliberate arithmetic divergence in `drawable.c`: byte counts are
   computed in `int` where upstream does `width *= 4` on a `jshort`. The two
   differ only above 8191 pixels of width or stride, where upstream overflows to
   a negative `short` and hands `memcpy` a length that converts to an enormous
   `size_t`. That is a crash, not a copy, so nothing the widening changes is an
   input upstream survived.

29. **The present copy timed in phases rather than as one number — `Drawable`
   and `PresentExtension`.** The `Present copyArea` sampler item 27 describes
   spans the whole of `Drawable.copyArea`, and that is two
   `DMA_BUF_IOCTL_SYNC` calls as well as the pixel copy. The 19.1 ms in this
   file's history was therefore never attributed: "the read is slow" and "the
   cache maintenance is slow" both produce it, and they want opposite fixes —
   the first a cached mapping and a parallel copy, the second a narrower sync.
   Item 28 was built on the first reading without the second having been ruled
   out.

   So `Drawable.copyArea` now records the three phases it is the only code to
   see the boundaries of — `syncIn`, the native copy, `syncOut` — plus a
   `DmaBufSync` state, and `presentToContent` accumulates them into the same
   sampled line beside the unchanged total. The residual (`other`) is the
   clamping, the rewinds and `forceUpdate`, which runs the window's on-draw
   listener and is not automatically small.

   The `DmaBufSync` state is the part that makes the line interpretable rather
   than merely more detailed. `syncIn=0` otherwise has two readings — the
   exporter's `begin_cpu_access` is nearly free, or no ioctl was issued at all
   — and they are opposite conclusions. `NONE` says the source held no
   descriptor, `REFUSED` says it refused the ioctl once and the bracket is
   latched off, `LIVE` says both halves ran and a zero is a real measurement.

   Cost: four `System.nanoTime()` calls per `copyArea`, unconditionally rather
   than only for dma-buf sources. On this platform that is a vDSO
   `clock_gettime` each, tens of nanoseconds, against an X request that has
   already cost microseconds in protocol handling. Not conditional, because a
   present from an MIT-SHM pixmap wants the copy phase measured too and the
   branch would cost about what the call does.

   Unmeasured: no device was attached when this landed, so the split line has
   never been captured and item 28's premise is still formally open.
   `docs/BANDWIDTH.md` carries what is known about the cacheability half of the
   question.

30. **The selection protocol, which was half-built, and the clipboard that needs
   it — `ClientOpcodes`, `XClientRequestHandler`, `SelectionManager`,
   `requests/SelectionRequests`, `requests/WindowRequests`, `WindowManager`,
   `XServer`, and four new files: `ClipboardSelection`,
   `events/SelectionRequest`, `events/SelectionNotify`.**

   Upstream implements `SetSelectionOwner` (22) and `GetSelectionOwner` (23) and
   stops. **`ConvertSelection` (24) was not declared in `ClientOpcodes` at all**,
   so it did not merely go unanswered — an undeclared major opcode falls through
   the dispatch switch's default, which throws `UnsupportedOperationException`
   and drops the connection (modification 19). There was no `SelectionRequest`
   event and no `SelectionNotify` event either. So a guest could *claim* the
   clipboard and nothing in the universe could ask what was in it. That is the
   whole reason clipboard did not work, and it is a shorter explanation than it
   looks: `ChangeProperty` (18) and `GetProperty` (20) were already here, and in
   X11 the property **is** the clipboard — only the handshake around it was
   missing.

   *The three pieces, and why the data does not travel in any of them.* A
   requestor sends `ConvertSelection`. The server routes a `SelectionRequest` to
   whoever owns the selection. The owner writes the value into a property **on
   the requestor's window** and answers with `SelectionNotify`, whose `property`
   field names it — or is `None`, which means refused and must be sent rather
   than dropped, because a requestor with no reply waits and a paste that hangs
   is worse than a paste that does nothing. The requestor then reads it with
   `GetProperty`. Nothing new carries bytes; `SelectionNotify` is 32 bytes like
   every other event.

   `TARGETS` is answerable, because it is how a client discovers what is on
   offer, and the offered list is exactly `TARGETS`, `UTF8_STRING`, `STRING`,
   `TEXT`. **Text only, deliberately.** Images and arbitrary formats are refused
   with `property = None`; so are `TIMESTAMP`, `MULTIPLE` and `INCR`, and none of
   the three is advertised — advertising a target and refusing it at the first
   call is the failure shape modification 20 exists to remember. `INCR` is the
   one that will bite: Wine switches to it above its own selection-size limit, so
   a very large copy out of the guest is recognised by property type, logged at
   WARN and dropped rather than pasted as the byte count it actually contains.

   *Two latent defects on the paths this had to cross, both of which drop the
   connection rather than returning an error.* `SetSelectionOwner` with owner
   `None` is how a client **releases** a selection, and `winex11.drv` does
   exactly that when the Windows clipboard is emptied; upstream looked the window
   up unconditionally and threw `BadWindow`, and worse, `SelectionManager` then
   built a `SelectionClear` around the *incoming* owner — so a release produced
   an event carrying null and `SelectionClear.send` dereferenced it, an NPE out of
   the request thread. The event now names the window losing the selection, which
   is what the protocol says that field is. Separately, `WindowRequests.sendEvent`
   dereferenced `destination.originClient` unconditionally for an empty event
   mask, and that is null for every window the server made rather than a client —
   the root window included. `onFreeResource` also now clears the owning client
   and not only the owner window, since `ResourceIDs` hands a departing client's
   id base straight to the next connection.

   *The Android half, and the boundary.* `ClipboardSelection` is the server's own
   participant, and it plays **both** roles, which is the shape of the problem.
   As **owner** it claims `CLIPBOARD` and `PRIMARY` for a server-internal window
   whenever Android says its clipboard changed, and answers the resulting
   conversion. As **requestor** — the harder direction — it needs a window to
   receive `SelectionNotify` and a property to receive the data, so
   `WindowManager.createServerWindow()` was added: unparented, with no `Drawable`,
   because both the compositor and Vessel's taskbar walk the window tree from the
   root and a window in that tree is a window somebody has to remember to skip.
   Borrowing a client's window was the alternative and is worse late rather than
   early — the client owns its lifetime, so the requestor would vanish
   mid-transfer whenever that program exited. `SendEvent` to that window is
   routed to the shim instead of to a socket, because it has no socket.

   Nothing Android-facing is in this tree: `ClipboardSelection.Bridge` is a
   two-method interface (`getText`, `setText`) and
   `app.vessel.display.AndroidClipboard` implements it, wired up in
   `XServerDisplay` — still the only file in `app.vessel` that imports from here.

   *Reading Android's clipboard is lazy on purpose and it is not about
   performance.* Android logs an access notification every time an app reads the
   clipboard and from Android 12 shows the user a toast, so a poll or a read on
   every change notification would produce a stream of them while the user did
   nothing. Ownership is claimed knowing only that *something* changed; the
   content is fetched at conversion time, which is when a guest program actually
   pastes.

   *The echo loop, which is not optional.* Pushing text to Android makes
   Android's clipboard change, which fires the listener, which would claim the X
   selection straight back and send the guest a `SelectionClear` for the copy it
   just made — after which every paste in the guest is served out of a stale
   Android clip. Two guards, catching different halves: `ClipboardSelection`
   remembers the last string handed *to* the guest and does not write it back if
   the guest hands it home again, and `AndroidClipboard` counts its own writes so
   the callback each one causes is consumed rather than acted on. The count lapses
   after two seconds, so a write whose callback never arrives costs one ignored
   change instead of a permanently one-way clipboard. It would not spin forever
   without them — the cycle needs a user to copy again — but the selection flaps
   on every copy, and against a Wine that re-asserted ownership on
   `SelectionClear` it would spin.

   **Written and compiled; none of it has been run.** A clipboard round trip needs
   a live Wine, a real `ClipboardManager` and a user copying something, so there
   is nothing about it a JVM test can see. `SelectionProtocolTest` covers what one
   can: the opcode, the atom names and ids, the offered target list against what
   is actually answerable, and the property encodings including the NUL truncation
   and the refusal to decode `INCR`. Unverified on the device: whether Wine
   accepts these answers at all, whether it asks `TARGETS` first or goes straight
   for `UTF8_STRING`, whether `getPrimaryClip` succeeds from an X request thread
   (Android 10+ refuses an app without focus), how often `INCR` is reached in
   practice, and whether the self-write suppression matches how many callbacks
   `setPrimaryClip` really produces.

31. **`FrameExtrapolator`, `frame_extrapolation.c`, and the renderer hook they
   need — frames the guest never drew.** The temporal counterpart to
   modification 22: SGSR reconstructs across space, from a frame rendered below
   the panel's resolution, and this reconstructs across time, from two frames
   rendered below its refresh rate. Both sit in the compositor and both ask the
   driver before they act.

   `GL_QCOM_frame_extrapolation` is a registered Khronos extension implemented by
   the vendor GLES driver, so `glExtrapolateTex2DQCOM(src1, src2, output,
   scaleFactor)` predicts a third frame from two real ones and nothing here
   implements the prediction. It has no Java binding — `android.opengl.GLES2x`
   stops at the core API — so `frame_extrapolation.c` resolves it through
   `eglGetProcAddress` and exposes two statics, and it is the only new file in
   `cpp/winlator` since `xshmfence.c`.

   **Extrapolation and not interpolation, which is the reason for choosing it.**
   Interpolating between two real frames means holding the newer one back until
   the frame after it exists, at a cost of one full frame of latency. Predicting
   forward costs none; the price is accuracy instead, and a prediction is most
   wrong where something occluded is being revealed. The next real frame replaces
   it outright, so the error is bounded to one frame.

   `GLRenderer.onDrawFrame` gains three things: `extrapolating()`, which gates on
   the container's setting, the absence of effects and
   `FrameExtrapolator.isSupported()`; a branch that composites into an offscreen
   target instead of the screen; and `invalidateBoundWindowMaterial()`, because
   the extrapolator's blit binds a program behind `bindWindowMaterial`'s back and
   modification 22's one-`glUseProgram`-per-frame bookkeeping would otherwise let
   the next window pass draw with the blit shader.

   Effects are excluded structurally rather than by policy: `EffectComposer`
   binds framebuffer 0 for its last pass, so the finished picture goes straight
   to the screen and there is nothing left to capture. Targets are RGBA8 rather
   than `Texture`'s `GL_BGRA` default, which the extension rejects outright.

   **The multiple is the whole tunable surface.** `scaleFactor` aims at a time
   rather than at a fixed midpoint — 1.0 is a full time-delta past `src2`, and
   negative values aim before it — so N-1 predictions at `i/N` fill the gap
   between two real frames evenly, and 2x, 3x and 4x are that one number. The
   extension defines no other entry point, no new tokens and no new state, so
   there is nothing else to expose; accuracy is what pays for the larger
   multiples, since the last prediction at 4x aims three times as far as the
   first on exactly the same two frames.

   **What `display.fpsLimit` counts is now a container setting, because the two
   readings are different features.** `display.frameGenerationMode` at its
   default, `efficiency`, caps the guest at `fpsLimit / N` through
   `dxvk.maxFrameRate` and `VKD3D_FRAME_RATE`: the limit means what the *screen*
   shows and the game renders its fraction of it, which is where the power saving
   comes from. `smoothness` leaves the guest at the whole limit and lets the
   compositor present the multiple.

   Smoothness is the one that looks right, and the reason is the distance between
   the two real frames. Every part of the interpolation degrades with it — the
   block matcher's search range, the area uncovered during the interval, and the
   assumption that anything moved in a straight line while it passed. Dividing a
   24 fps limit by 2 leaves the guest drawing every 83 ms, and interpolating
   across 83 ms of a moving scene is a far harder question than the same code
   answers easily across 17 ms. The added latency is a fixed fraction of that
   same interval, so it falls by the same factor.

   **`PacedXServerView` no longer sees the synthesised frames at all.** It drops
   or re-posts through a `Handler` any `requestRender` arriving sooner than the
   limit allows, which is right for the guest's damage and wrong for a frame
   `FramePacer` has just aimed at a vsync — re-posting it through a delay queue
   is exactly the scheduling the pacer exists to avoid. They go through
   `XServerView.requestRenderUnpaced` instead. The rate is unaffected, because
   the source rate already is.

   **The idle gate was 40 ms and that switched the feature off entirely.** 40 ms
   is 25 fps, and a container capped at 24 fps composites every 41.7 ms, so every
   frame fell the wrong side of it and not one prediction was ever scheduled —
   for exactly the containers frame generation exists for. 250 ms now. A slow
   renderer is not an idle one; a genuinely idle desktop damages nothing and
   never reaches the gate at all.

   **The extension is advertised and does not work on this driver, and that was
   proved by reading its output back rather than by guessing.** The three
   textures were dumped mid-gameplay: both sources held a real scene, 14,577
   unique colours at mean (46,54,56), and the output was
   {@code 16 48 80 112 143 175 207 239} repeating every eight pixels across every
   row -- a fixed ramp, standard deviation 2.3 and 5.2 in R and G across 8x8
   blocks, with no scene structure at any scale. Not a frame, and not derived
   from the inputs.

   So `glExtrapolateTex2DQCOM` on Adreno 829 driver `V@0842.36` accepts the call,
   reports `GL_NO_ERROR`, and fills the destination with a pattern. Nothing on
   the API side can fix that, which is why five attempts at it all failed:

   **The sibling extension does work, and that is the way forward.**
   `MotionProbe` asks the same question of `GL_QCOM_motion_estimation`, with a
   control the extrapolation work lacked: it reads a patch of both luma inputs
   back first, because the spec says a zero vector means "no motion detected OR
   masked" and an all-zero result from two identical frames is the extension
   behaving perfectly. Measured on device during camera movement:

       block 8x8, luma 2776x1264, vectors 347x158
       luma inputs differ in 57013 of 65536 sampled pixels
       vectors 54826: nonzero 44590, x [-113..90], y [-110..106], mean|v| 14.08

   Real signed motion, correct block size queried from the driver, no error. An
   earlier run of the same probe returned all zeros and would have been read as a
   second stub -- it was a static scene, and the control is what makes the
   difference legible. So one of the two extensions is a stub and the other is
   not, on the same driver, which is exactly why every one of these has to be
   measured rather than trusted.

   The probe cost an ANR before it was cheap enough: two full 2776x1264 readbacks
   and a three-million-iteration loop on the GL thread. A 256x256 patch answers
   the same question.

   **The five, kept because each was a real bug and none was the cause.** The setting is gone from the manifest, so nothing can turn
   it on; the code is kept because the findings below are worth more than the
   diff, and because the failure is in one call rather than in the surrounding
   machinery.

   What was tried, in order, each built and run on device, each still showing
   dense colour speckle on the predicted frames -- reported as "dot + flicker",
   which is exactly right, since at 4x three frames in four are predicted:

   1. **Blending disabled for the blit.** The compositor leaves
      SRC_ALPHA/ONE_MINUS_SRC_ALPHA on from context creation, so a prediction
      carrying alpha blended with the previous screen. Real bug, not this one.
   2. **The output target seeded** with the newest real frame before predicting,
      so an untouched target could not present uninitialised memory. Real bug --
      `glTexImage2D(..., null)` leaves whatever that page held -- and not this one.
   3. **The framebuffer unbound** before the call. The target was still the colour
      attachment of the bound FBO while the driver wrote to it by name, which is a
      feedback loop and undefined. Real bug, and not this one either.
   4. **Immutable sized storage**, `glTexStorage2D` with `GL_RGBA8` instead of
      `glTexImage2D` with unsized `GL_RGBA`, since the spec names RGBA8 and a
      driver checking for a sized format would not have got one. Still not it.

   `glGetError` returns `GL_NO_ERROR` throughout, and the counters prove the call
   runs: `scheduled 31617, cancelled 10334, presented 10788` in one session. So
   the driver accepts the call, writes *something*, and what it writes is not a
   frame -- 423 distinct colours across a 400x300 sample with two dominant, which
   is a repeating pattern rather than a picture.

   The spec is the problem as much as the driver: it defines one entry point, no
   tokens, no state, and says outright that "extrapolation quality is not
   defined". There is nothing to query, nothing to configure, and no way to ask
   whether the output is valid. Anything further needs a working reference to
   compare against -- Qualcomm's own sample, or a device where this is known good
   -- rather than a fifth guess.

   Everything up to the call is sound and stays: the offscreen capture, the
   history pair, the pacing, the guest-cap division, and the counters that
   separate "the driver did nothing" from "nothing was scheduled".

   **What was true before it was withdrawn.** Measured on device with
   Metro 2033 Redux at 4x: `scheduled 1674, cancelled 521, presented 587`, no GL
   error, and a title screen that renders sharp. The dense colour speckle two
   earlier builds put on screen was, first, an output target still holding
   uninitialised GPU memory -- fixed by seeding it with the newest real frame --
   and second, and the one that actually mattered, a feedback loop: the target
   was still the colour attachment of the bound framebuffer while the extension
   wrote to it by name, which the spec leaves undefined. Unbinding before the
   call is what fixed it.

   **The multiple divides an already-low cap, and 24 fps at 4x means the game
   renders six.** The same measurement shows intervals alternating 41 ms and
   124 ms, the latter being the guest doing as it was told. The multiple wants a
   Frame rate limit high enough to survive the division -- 120 at 2x renders 60
   -- and a container left at 24 gets a prediction aimed a sixth of a second past
   anything real. Worth saying in the UI rather than leaving to arithmetic.

   **Off by default.** The
   extension is present on this one — Adreno 829, GLES 3.2, driver `V@0842.36`,
   reporting `GL_QCOM_frame_extrapolation` and `GL_QCOM_motion_estimation` — and
   the visible corruption an early build produced (dense colour noise, which was
   the uninitialised output target blended over the previous frame) is gone with
   blending disabled for the blit. But the counters in `report()` exist because
   two guesses about why nothing was happening were both wrong: the first was the
   idle gate, the second was that the container's setting had been reset to off.
   Nothing here is confirmed working until that log line prints.

### Every file that differs from upstream

This table is the machine-checkable form of the list above — `LicensingTest`
compares it against a grep for `VESSEL:`, so adding a marked file without adding
a row here, or removing the last marker from a file without removing its row,
fails the build.

| File | Items |
|---|---|
| `app/src/main/java/com/winlator/core/AppUtils.java` | 11 |
| `app/src/main/java/com/winlator/core/ArrayUtils.java` | 11 |
| `app/src/main/java/com/winlator/core/FileUtils.java` | 7, 11 |
| `app/src/main/java/com/winlator/core/ImageUtils.java` | 11 |
| `app/src/main/java/com/winlator/core/StringUtils.java` | 11 |
| `app/src/main/java/com/winlator/inputcontrols/ExternalController.java` | 11 |
| `app/src/main/java/com/winlator/renderer/GLRenderer.java` | 5, 13, 14, 22, 31 |
| `app/src/main/java/com/winlator/renderer/FramePacer.java` | 31 |
| `app/src/main/java/com/winlator/renderer/FrameSynthesizer.java` | 31 |
| `app/src/main/java/com/winlator/renderer/FrameTimestamps.java` | 31 |
| `app/src/main/java/com/winlator/renderer/GpuTimer.java` | 31 |
| `app/src/main/java/com/winlator/renderer/RenderableWindow.java` | 31 |
| `app/src/main/java/com/winlator/renderer/Texture.java` | 13, 26 |
| `app/src/main/java/com/winlator/renderer/VertexAttribute.java` | 13 |
| `app/src/main/java/com/winlator/renderer/material/InterpolateMaterial.java` | 31 |
| `app/src/main/java/com/winlator/renderer/material/MedianMaterial.java` | 31 |
| `app/src/main/java/com/winlator/renderer/material/SGSRMaterial.java` | 22 |
| `app/src/main/java/com/winlator/renderer/material/ShaderMaterial.java` | 13, 22 |
| `app/src/main/java/com/winlator/renderer/material/SignMaterial.java` | 31 |
| `app/src/main/java/com/winlator/sysvshm/SysVSharedMemory.java` | 6, 27 |
| `app/src/main/java/com/winlator/widget/XServerView.java` | 31 |
| `app/src/main/java/com/winlator/winhandler/WinHandler.java` | 4 |
| `app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java` | 8 |
| `app/src/main/java/com/winlator/xserver/ClientOpcodes.java` | 30 |
| `app/src/main/java/com/winlator/xserver/ClipboardSelection.java` | 30 |
| `app/src/main/java/com/winlator/xserver/Drawable.java` | 27, 28, 29 |
| `app/src/main/java/com/winlator/xserver/Property.java` | 15 |
| `app/src/main/java/com/winlator/xserver/SelectionManager.java` | 30 |
| `app/src/main/java/com/winlator/xserver/Window.java` | 15 |
| `app/src/main/java/com/winlator/xserver/WindowManager.java` | 16, 21, 30 |
| `app/src/main/java/com/winlator/xserver/XClient.java` | 24 |
| `app/src/main/java/com/winlator/xserver/XServer.java` | 1, 2, 3, 10, 20, 24, 30 |
| `app/src/main/java/com/winlator/xserver/XShmFence.java` | 23 |
| `app/src/main/java/com/winlator/xserver/extensions/XFixesExtension.java` | 20, 24 |
| `app/src/main/java/com/winlator/xserver/XClientRequestHandler.java` | 19, 30 |
| `app/src/main/java/com/winlator/xserver/errors/XRequestError.java` | 19 |
| `app/src/main/java/com/winlator/xserver/events/ClientMessage.java` | 15 |
| `app/src/main/java/com/winlator/xserver/events/SelectionNotify.java` | 30 |
| `app/src/main/java/com/winlator/xserver/events/SelectionRequest.java` | 30 |
| `app/src/main/java/com/winlator/xserver/requests/SelectionRequests.java` | 30 |
| `app/src/main/java/com/winlator/xserver/requests/WindowRequests.java` | 30 |
| `app/src/main/java/com/winlator/xserver/extensions/DRI3Extension.java` | 17, 21, 23, 24, 27 |
| `app/src/main/java/com/winlator/xserver/extensions/Extension.java` | 24 |
| `app/src/main/java/com/winlator/xserver/extensions/MITSHMExtension.java` | 25 |
| `app/src/main/java/com/winlator/xserver/extensions/PresentExtension.java` | 17, 18, 24, 27, 28, 29 |
| `app/src/main/java/com/winlator/xserver/extensions/SyncExtension.java` | 23, 24 |
| `app/src/main/cpp/winlator/CMakeLists.txt` | 12, 23, 28 |
| `app/src/main/cpp/winlator/include/copy_pool.h` | 28 |
| `app/src/main/cpp/winlator/src/copy_pool.c` | 28 |
| `app/src/main/cpp/winlator/src/drawable.c` | 28 |
| `app/src/main/cpp/winlator/src/frame_extrapolation.c` | 31 |
| `app/src/main/cpp/winlator/src/frame_timestamps.c` | 31 |
| `app/src/main/cpp/winlator/src/sysvshared_memory.c` | 27 |
| `app/src/main/cpp/winlator/src/xconnector_epoll.c` | 9 |
| `app/src/main/cpp/winlator/src/xshmfence.c` | 23 |

## Integration points

Nothing in this tree starts itself. The session layer owns the lifecycle; this
is the whole surface it needs, and none of it requires editing anything here.

The session side already declares the seam: `app.vessel.core.SessionDisplayServer`,
with `SessionDisplayServer.Absent` bound in `DataModule` until a real one exists.
Wiring this backend up means writing one adapter that implements that interface
using the calls below, and changing the binding. `app.vessel` should keep its
single import of `com.winlator` inside that adapter.

```java
XServer xServer = new XServer(new ScreenInfo("1280x720"));
xServer.setDebugSink(line -> sessionLog.debug(line));   // optional
xServer.setWinHandler(myWinHandler);                    // optional, see below

// The view is also the renderer's owner; add it to the Activity/Service window.
XServerView view = new XServerView(context, xServer);
xServer.setRenderer(view.getRenderer());

// Two sockets. The X one is abstract — see local modification 8; there is no
// container root to relocate it into and Android has no /tmp.
XConnectorEpoll xConnector = new XConnectorEpoll(
    UnixSocketConfig.createAbstract("/tmp/.X11-unix/X0"),
    new XClientConnectionHandler(xServer),
    new XClientRequestHandler());

SysVSharedMemory shm = new SysVSharedMemory();
xServer.setSHMSegmentManager(new SHMSegmentManager(shm));
XConnectorEpoll shmConnector = new XConnectorEpoll(
    UnixSocketConfig.create(containerRoot, UnixSocketConfig.SYSVSHM_SERVER_PATH),
    new SysVSHMConnectionHandler(shm),
    new SysVSHMRequestHandler());
```

- `UnixSocketConfig.create` **wipes and recreates** the socket's parent
  directory, so call it before binding and after any previous session.
  `createAbstract` does neither, and needs to do neither: an abstract name lives
  only as long as its socket, so it cannot go stale.
- The guest needs `DISPLAY=:0`, and that works because the abstract name matches
  the one `_xcb_open()` compiles in. Nothing configures it; a name one byte
  different is `cannot open display`.
- `app.vessel.display.XServerDisplay` is the adapter, and the only file in
  `app.vessel` that imports from here.
- `WinHandler` is an interface here, not an implementation. Leave it unset and
  relative-mouse mode and Win32 window activation are inert but harmless.
- Nothing here reads container config, spawns processes or touches
  `SessionEnvironment`. Keep it that way — the coupling is one-directional on
  purpose.

## Things worth knowing before touching this

- `libwinlator` links `AHardwareBuffer_getNativeHandle`, which is exported by
  `libandroid.so` but is **not** a public NDK symbol and has no header. It is
  declared by hand in `gpu_image.c`. If a future Android release stops exporting
  it, DRI3 loses its fd and there is no compile-time warning.
- `XInputStream`, `XOutputStream`, `XConnectorEpoll`, `Drawable`, `Pixmap`,
  `GPUImage` and `SysVSharedMemory` all `System.loadLibrary("winlator")` in a
  static initialiser, so none of them can be touched from a JVM unit test. The
  tests in `app/src/test/java/com/winlator/` cover the pure-Java protocol
  constants and the socket path handling for that reason, not by preference.
- Upstream ships no tests. Everything under `app/src/test/java/com/winlator/`
  is Vessel's.
