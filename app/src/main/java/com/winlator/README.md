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
| `app/src/main/java/com/winlator/renderer/GLRenderer.java` | 5, 13, 14 |
| `app/src/main/java/com/winlator/renderer/Texture.java` | 13 |
| `app/src/main/java/com/winlator/renderer/VertexAttribute.java` | 13 |
| `app/src/main/java/com/winlator/renderer/material/ShaderMaterial.java` | 13 |
| `app/src/main/java/com/winlator/sysvshm/SysVSharedMemory.java` | 6 |
| `app/src/main/java/com/winlator/winhandler/WinHandler.java` | 4 |
| `app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java` | 8 |
| `app/src/main/java/com/winlator/xserver/Property.java` | 15 |
| `app/src/main/java/com/winlator/xserver/Window.java` | 15 |
| `app/src/main/java/com/winlator/xserver/WindowManager.java` | 16 |
| `app/src/main/java/com/winlator/xserver/XServer.java` | 1, 2, 3, 10, 20 |
| `app/src/main/java/com/winlator/xserver/extensions/XFixesExtension.java` | 20 |
| `app/src/main/java/com/winlator/xserver/XClientRequestHandler.java` | 19 |
| `app/src/main/java/com/winlator/xserver/errors/XRequestError.java` | 19 |
| `app/src/main/java/com/winlator/xserver/events/ClientMessage.java` | 15 |
| `app/src/main/java/com/winlator/xserver/extensions/DRI3Extension.java` | 17 |
| `app/src/main/java/com/winlator/xserver/extensions/PresentExtension.java` | 17, 18 |
| `app/src/main/cpp/winlator/CMakeLists.txt` | 12 |
| `app/src/main/cpp/winlator/src/xconnector_epoll.c` | 9 |

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
