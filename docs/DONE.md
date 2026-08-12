# Done

Closed items moved out of `docs/TODO.md`, with the evidence that closed them.
The rule they were held to is that file's rule: nothing was ticked until it had
been watched working on the device.

## Input and audio

- [x] **Guest audio plays, and Vessel never turns it down.** `patches/wine/0008`
  rewrites `wineoss.drv`'s unix half onto AAudio — the NDK's only low-latency
  output — and `mmdevapi` probes `oss` by default, so nothing else had to change
  to make a Windows program audible.

  *Evidence:* heard on the device, 2026-08-11, and the trace explains what the
  ear reported. AudioFlinger's **start threshold is the buffer size**, so a track
  whose queue never reaches it never starts: `wineoss.drv` was handing Metro's
  whole 1440-frame buffer to a device wanting 1736, and the driver logged
  `advanced by 0, held: 1440` for 2072 consecutive passes. Sizing the shared
  buffer to `min(client, device)` fixed it.

  **Wine outputs at full scale and Android owns the volume.** Two attenuations
  in series is a slider that does not mean anything.

- [x] **And it plays without buzzing.** The first fix made the guest audible and
  left a ~55 Hz rasp over otherwise correct audio. That pitch was the diagnosis:
  an underrun does not lose frames, it punches a silence into them, so a gap
  rate *is* a frequency. AudioFlinger counted 54,240 underrun frames per ten
  seconds — 11% of the stream, about 55 gaps a second of 2 ms each.

  **AAudio does not sip; it takes a whole burst.** 868 frames on this device,
  18 ms. Metro asks for a 30 ms buffer and, measured with the `oss` channel on,
  kept between 96 and 960 frames queued — under one gulp. So every pull emptied
  it and the next found nothing.

  Two rebuilds went into the wrong half of that. `in_oss_frames` is by
  construction a subset of `held_frames` — the part of the guest's ring already
  handed over — so **the device queue can never exceed what the guest has
  produced**, and raising the AAudio buffer changes nothing. Verified rather
  than reasoned: the buffer was raised to 1736 and `in_oss: 0` still appeared.

  The fix is to floor the *guest's* ring at three bursts, stated in bursts
  precisely so it costs nothing where it is not needed: a title already asking
  for more keeps exactly the latency it chose, and on a device granting MMAP the
  burst is ~192 and the floor never fires at all. WASAPI allows this —
  `GetBufferSize` returns what the driver chose — and exclusive-mode streams are
  left alone, where the client owns the timing contract.

  *Measured after, same session, ten seconds apart:* **2,112 underrun frames per
  ten seconds, down from 54,240** — 0.44% of the stream against 11%, a 96%
  reduction, with the queue sitting at 1344–1440 of 1736 instead of scraping
  zero. Confirmed by ear: the buzz is gone. The cost is honest — track latency
  went from 43–48 ms to 80–85 ms.

  **The driver now asks for `AAUDIO_SHARING_MODE_EXCLUSIVE` first**, and it is
  refused on this device. The measurements above are therefore the shared path.

  *A theory that was wrong, kept because it was expensive.* The HAL's
  `mmap_no_irq_out` port declares one profile — `AUDIO_FORMAT_PCM_16_BIT`, 48000,
  stereo — and an AudioFlinger dump appeared to show the track as `PCM_FLOAT`, so
  a float-to-int16 conversion was written to unlock the low-latency path. The
  stream then opened as `format 1`, `AAUDIO_FORMAT_PCM_I16`: the guest had been
  sending 16-bit all along, the conversion branch never ran, **and MMAP was
  refused anyway**. Why it is refused is not known, and no third explanation has
  been invented for it. The conversion stays because it is correct and inert for
  a 16-bit guest; the `ERR` line now prints the format so the next person reads
  it rather than deduces it.

  **The device buffer grows on measured underruns**, `AAudioStream_getXRunCount`,
  a burst at a time up to capacity, never shrinking — Android's own sizing loop,
  in place of a one-shot jump to two bursts that charged every stream a burst of
  latency for a cushion most never need. It has not fired once on this device,
  which is the right outcome for insurance.

  *Final, measured on the same session:* **288 underrun frames per ten seconds,
  0.06% of the stream**, from 54,240 and 11%. The last of that came from
  somewhere unpredicted: with the ring floored at 2604 the create-time device
  buffer stops being clamped by the client and lands on 1736 immediately, so the
  floor paid twice. Latency sits at 81 ms against the 43-48 ms it started at, and
  giving some of that back by floor-of-two-bursts plus the xrun loop is the next
  thing to try rather than a thing that has been tried.

- [x] **A gamepad reaches the guest as a gamepad, not as keystrokes.**
  `patches/wine/0016` adds a `winebus` backend fed by the app over a unix socket.
  The guest gets a real HID device — vid 045e, pid 028e, the wired Xbox 360 pad
  every title has been tested against for fifteen years — which XInput,
  DirectInput and winmm all enumerate, and which carries rumble back down the
  same socket to the controller's own motors.

  **Why a socket and not a device node.** `/dev/input` is `root:input` 0660 and
  `untrusted_app` is in neither group, so SDL, udev and libusb can never see a
  controller from this process. Android's `InputDevice` API can, and it lives in
  the app — so the app is the bus and `bus_vessel.c` is its client half.

  *Evidence, 2026-08-11, from a session log with a Bluetooth pad connected:*

  ```
  vessel: pad bus connected to @vessel-pad-0-1
  vessel: frame type 0x1
  vessel: state pad 0 axes 128 128 -3469 -1413 buttons 0
  vessel: queued a report of 17 bytes
  ```

  **It had been shipping as dead code for four test rounds.** The patch carried
  only the new `bus_vessel.c` and none of the five edits that hook it into
  `Makefile.in`, `unixlib.h`, `unixlib.c`, `unix_private.h` and `main.c` —
  because `git diff` on a tree whose only change is an *untracked* file produces
  exactly that, and a `git checkout -- .` cleanup had dropped the rest. The file
  compiled into nothing and the linker said nothing. Two habits come out of it:
  regenerate a patch with `git add -N` so the new file joins the tracked edits,
  and confirm a Wine patch landed by running `strings` over the packaged `.so`
  rather than reading a build's exit code — `build/wine.sh` prints
  `error: ANDROID_NDK_HOME is not set` and exits 0 when run outside Docker.

- [x] **The on-screen pad is a controller, and the guest was never told.**
  `refreshPads` asked Android how many *physical* controllers existed and offered
  the guest that many. With none connected the answer was zero, no HID device was
  created, and every frame the overlay produced went to a slot the guest did not
  have. Reported as "the virtual controller does not work but a real one does",
  which is exactly the shape of it — and it is why the bridge tested clean the
  day it landed, with a Bluetooth pad connected throughout.

  A glass control carrying a pad identity now makes slot 0 present, by the same
  argument `mergedWith` already makes: the overlay and a physical pad are one
  controller to the player. The test is `padControls.isNotEmpty()` rather than
  "the overlay is visible", so a hand-built keyboard layout does not conjure a
  device that answers nothing, and presence is recomputed when the layout changes
  because switching to a pad layout mid-session should give the guest a pad.

- [x] **The input screen is one list of controls.** It had been showing one
  controller under two mental models — a free-form overlay you could add to, and
  a fixed table of twenty-four rows you could only bind — and merging the tabs
  moved the seam into the middle of a scroll instead of removing it. Every row is
  a control now: on the glass, on the pad, or both.

  `GamepadAction.Pad` is what made that honest — a control can send a *control*
  rather than a keystroke, which only became possible once the guest had a pad to
  send one to. The default profile is therefore a controller, with the keyboard
  layout it used to be kept whole and offered by name.

  Two rules that were special cases became ordinary. The default profile is a
  normal editable record whose id means only "delete refuses it", where it used
  to be a constant the store would not write — so the first edit forked a copy,
  and a slider drag forked eight. And edits are a draft that Save writes, rather
  than forty document writes during a drag.

## 1. Blocking a working product

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

- [x] **The drag borders resize the guest, and no EWMH was needed to get there.**
  *Evidence, 2026-08-10, notepad in a live session, `VESSEL_MANAGED=1` read out
  of `/proc/2998/environ` rather than assumed.* Reveal the taskbar, long-press
  the button, Resize, drag the right edge in, Done:

  ```
  before:  id=25165825 mapped=true 684x513+298+103  kids=0
  after:   id=25165825 mapped=true 562x513+298+103  kids=0
  ```

  Screenshotted with the handles cleared: the menu bar and the *Ln 1, Col 1*
  status bar both end exactly at the new right edge, and the vertical scrollbar
  has re-laid out. No clipping and no overflow — the Win32 client resized, which
  is the thing that had never happened.

  **The previous entry here was measured correctly and reasoned about wrongly,
  and the wrong half is worth keeping.** It recorded the frame moving while the
  client stayed `1280x720+0+0` and overflowed, and concluded that the fix was
  "enough ICCCM/EWMH that Wine treats the session as managed" — one piece of
  work shared with Maximize and with restore-from-minimised. That measurement
  was taken with `MANAGED_DESKTOP` **off**. Turning it on is the whole fix for
  resize: `patches/wine/0011` keeps `managed_mode` true in desktop mode, so
  `is_window_managed()` stops returning FALSE unconditionally and a
  `ConfigureNotify` Wine did not ask for becomes a `SetWindowPos` instead of
  being overwritten from the desired state. `WM_STATE` (vendored mod 16) is the
  other half of the pair and was already in.

  So `_NET_SUPPORTING_WM_CHECK` / `_NET_SUPPORTED` / `_NET_ACTIVE_WINDOW` are
  **not** a prerequisite for this, and the two items that cite "the same single
  piece of work" should not be read as blocked on the same thing. Restore and
  Maximize may still need the advertisement — `can_activate_window` is a
  different code path and is untested at the time of writing — but resize does
  not, and bundling them hid a fix that was already installed.

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

- [-] **`MESA_VK_WSI_DEBUG=sw,linear`** — tried on the paper argument, measured,
  and reverted. It removes a 0.60 ms GPU blit and is still ~14% slower on the
  mean and ~35% on the median, three runs of 400 frames each and the same
  direction every time: rendering into a linear image makes the GMEM resolve
  write an untiled layout, which costs more than the blit it saves.

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


## 3a. Found while running the matrix

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
- [x] **The bottom edge had no reveal handle** while the rail's left edge had a
  plainly visible one. `navigationBarsPadding()` was on the 4 dp mark rather
  than the 20 dp touch box around it, so the mark asked to reserve the
  navigation bar inside a parent with no room for it and was clipped away
  entirely. The bar was never missing from the code, it was laid out off-screen.
  It is also square now rather than a pill: Android draws its own gesture pill
  on that edge, centred, at very nearly the same width.


## 4. Real, not blocking

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


## 6. Before the repository goes public

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

## 8a. The study's own record, kept for the measurements

- [-] **PRoot as the enabling mechanism.** Closed before it was started. It
  addresses `chroot`, not W^X, and the exec problem is the whole problem. It
  stays available as the *path-and-bind* layer once something can start a glibc
  process, which is a different item and a much later one.

- [-] **x86 Linux binaries, for now.** FEXLoader plus an x86-64 rootfs is a
  second build target and a second rootfs, and the reason to defer it is not
  effort: **the guest's Mesa would be emulated in the hottest loop in the stack.**
  What makes x86 *Windows* fast here is that DXVK and vkd3d are native ARM64EC
  and only application code is translated (`ARCHITECTURE.md:16-18`). ARM64EC is a
  Windows ABI feature; Linux has no equivalent, so that property does not
  transfer and only FEX host/guest thunk libraries could recover it. ARM64-native
  is the sane first target, and the argument for it is this one rather than
  "ARM64 is obviously first".
