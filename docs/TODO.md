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
through DXVK*. As of 2026-08-09 a Windows program draws on the screen and takes
input; what it does not do is draw through D3D.

- [x] **The registry seed never reaches `system.reg`.** It does now.
  *Evidence:* on the provisioned container, `system.reg` carries
  `[Software\\Microsoft\\Wow64\\amd64] @="libarm64ecfex.dll"` and
  `[Software\\Microsoft\\Wow64\\x86] @="libwow64fex.dll"`, read back out of the
  hive rather than inferred from `regedit`'s exit code. The x86-32 launch below
  is the second, independent proof: without the `x86` key, `wow64.dll` has no
  emulator to load and the process cannot start at all.

- [x] **`libwow64fex.dll` never reaches `syswow64`.** It reaches `system32`,
  which is where WoW64 looks — the emulator DLL named by
  `HKLM\Software\Microsoft\Wow64\x86` is a 64-bit DLL and is loaded into the
  64-bit side of the process. `syswow64` holds the 32-bit DXVK instead.
  *Evidence:* `system32` has `libarm64ecfex.dll`, `libwow64fex.dll` and
  `xtajit64.dll`; `syswow64` has the 32-bit `d3d11.dll` and the rest of DXVK.
  A `.exe` built for i686 and launched **from the app's launcher** printed
  `VESSEL-OK bits=32 sum=333338333350000 argc=1` into the session log, after
  `Loaded L"C:\\windows\\system32\\libwow64fex.dll"` and FEX's own
  `D EC Load module hello-i686.exe`. §3 has the whole matrix.

- [x] **A program launched from the home screen, in a window, taking keyboard
  input.** The thing the shell exists to do.
  *Evidence, 2026-08-09:* `notepad.exe` added from the file browser, tapped on
  its tile on home with nothing running. The container started, the program came
  up with it, the window is centred on the desktop and themed — Nocturne title
  bar, working minimise/maximise/close — the taskbar lists it, and
  `adb shell input text` put **VESSEL-KEYBOARD-OK** into it, `Ln 1, Col 19`.
  Backing out of the desktop and returning left the window and its text intact,
  which is the black-desktop-on-return fix confirmed as well.
  *Three defects found by doing it, all fixed in `6f720d1`:* a tile on home
  refused every launch with "belongs to a different container" because nothing
  was running to compare against; only `C:` shortcuts could resolve at all; and
  windows opened hard against the top-left corner.

- [ ] **At session start the desktop background is black until something
  repaints it.** Narrow and reproducible: with a program's window mapped, the
  area around it is black rather than `#161826`. Leaving the desktop and coming
  back paints it correctly and it stays correct, and an empty desktop is correct
  from the start — so this is paint ordering at startup (the first composite
  happens before Wine's desktop window has painted its background, and nothing
  damages it afterwards) rather than the compositing bug it looks like. Window
  contents are never affected.
  *Not diagnosed further; recorded rather than guessed at.*

- [ ] **The taskbar draws a letter where a program has an icon.** `PeIconReader`
  feeds the app tiles now; a taskbar button still shows the first letter of the
  window title. The button knows `GuestWindow.program`, so the mapping to a
  shortcut's icon exists — it is wiring, not a new capability.

- [ ] **Nothing has ever rendered a triangle.**
  **The reason has changed, and the old one is gone.** With the app's X server
  up, `VK_KHR_win32_surface` *is* reached — DXVK 2.7.1 loads, finds
  `vkGetInstanceProcAddr` in `winevulkan.dll`, and asks for it by name. What
  fails now is one step earlier than the old BLOCKED and it is a build option,
  not a mystery:

  ```
  info:  Enabled instance extensions: … VK_KHR_win32_surface
  err:   DxvkInstance::createInstance: Failed to create Vulkan instance
  err:   D3D11CreateDevice: Failed to create a DXGI factory
  ```

  `winevulkan`'s own `vkEnumerateInstanceExtensionProperties` does not list
  `VK_KHR_win32_surface` among the client extensions, so `wine_vkCreateInstance`
  refuses the one DXVK always enables. It is absent because **the Turnip we
  build has no X11 WSI**: `build/turnip.sh` configures Mesa with
  `-Dplatforms=android`, and `strings` on the shipped `libvulkan_freedreno.so`
  finds exactly two surface extensions — `VK_KHR_android_surface` and
  `VK_EXT_headless_surface`. `winex11.drv` maps `VK_KHR_win32_surface` from
  `VK_KHR_xlib_surface`/`xcb`, and neither exists, so there is nothing to map.
  The stock Qualcomm loader has no xlib surface either, so this is not something
  turning Turnip off would fix.
  *Same failure, one cause, all of them:* d3d11 (`createdevice`), d3d12
  (`createfactory`), d3d9 and d3d8 (`DxvkInstance::createInstance`). The plain
  Vulkan probe passes in the same session — `driver_id=18`, `turnip Mesa driver`,
  `Mesa 26.3.0-devel (git-9c475fc367)`, `Adreno (TM) 829`, api 1.4.358 — so the
  driver underneath is fine and only the window system is missing.
  *The next step used to be `-Dplatforms=x11` for Mesa, and that is no longer
  the whole story.* It was tried, and what it exposed is a kernel-side limit
  rather than a build option: **KGSL cannot export a dma-buf for a buffer Turnip
  allocated.** `kgsl_bo_export_dmabuf` can only re-export an fd it imported, so
  the X11 WSI's "client allocates, the server imports" shape cannot work on this
  driver at all; the shape that can is the Android side allocating and the client
  importing. Every D3D probe still dies, now at swapchain creation — one step
  further along than the extension list, and a design question rather than a
  build flag. Nothing else in this item can be tested until that is answered.
  *Done when:* a D3D probe passes its pixel readback, **and** a real program with
  a 3D window keeps drawing while its screens are navigated by touch.

- [x] **No desktop has been seen since the redesign.**
  *Evidence:* the desktop draws, with Turnip on, in landscape. Three pixels
  sampled out of `adb exec-out screencap` at (926,421), (1390,632) and
  (1945,884) are all `#161826`, the seeded Nocturne background, and the same
  session's log carries the winediag line naming `libvulkan_freedreno.so`. A
  guest window drawn *into* that desktop — `wscript.exe`'s dialog — is in the
  screenshot too, so this is a compositing desktop and not a cleared surface.
  *Not covered:* only one landscape direction was photographed.

## 2. Self-sufficient install

- [~] **Bundle the `.wcp` packages into the `sideload` flavour.**
  Uninstall, install, everything works — no side-loading, no downloads. About
  100 MB of `dist/*.wcp` into `app/src/sideload/assets/`, installed through the
  existing `WcpArchive`/`WcpInstaller` so the store layout is identical to the
  download path. `play` keeps downloading; this is an additional source, not a
  replacement.
  *Done when:* `adb uninstall` then `adb install`, and a container reaches a
  desktop without any script being run or any file pushed by hand.

- [~] **First-run setup progress UI.**
  A named-step checklist in the established style, not a spinner: which
  component, how far, how much left. Honest refusal on failure — say which
  component and what would fix it.
  *Done when:* screenshotted on a genuinely fresh install, in portrait.

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

- [ ] **RpcSs.** `StartServiceW` fails inside the app and succeeds under
  `run-as`. Diagnosed to `dlls/combase/rpc.c:229`; unexplained.
- [ ] **Move the `drive_c` reader out of `ui/vm`.** `FilesViewModel` reads the
  prefix directly with `java.io.File`, which is correct but belongs in `data/`
  alongside the import/export copies. A decision, not an oversight.

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

The remote is set (`prskid1000/Vessel`) and **nothing has been pushed.**

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

*Last rewritten 2026-08-09, after a session spent on the device.*

The **CPU story is done and measured**: ARM64EC plus FEX costs 1.09x native on
integer and 0.99x on memory, x86-32 through WoW64 costs 2.28x, Wine starts a
process in 197 ms, and all three run from the app's own launcher. The **driver
story is done and switched on**: Turnip answers inside a Wine session, proven by
`driverID 18`, `Found compatible device '/dev/kgsl-3d0'` and the winediag line.

The **shell works**, and that is new. Tap a program on the home screen with
nothing running: the container starts, the program comes up with it, its window
is centred and themed with a working title bar, the taskbar lists it, the
keyboard reaches it, and backing out and returning leaves it intact. Every one of
those was a separate unverified claim a day ago.

**Storage works.** Android storage is drives inside the container — shared
storage on `D:`, a folder or a whole USB volume on the next free letter, mapped
from Android's own picker — and Wine's File Explorer lists and opens them. The
unix root does not appear at all any more, by either of the two routes it used
to.

**The middle is still the middle, and its reason has moved.** No Windows program
has drawn through D3D. It is no longer a missing extension: Mesa built with
`-Dplatforms=x11` gets past that and dies at swapchain creation, because KGSL
cannot export a dma-buf for a buffer Turnip allocated. That is a design question
about who allocates, not a build flag, and it is the one thing left that a day of
careful work will not obviously fix.

What is honestly unfinished elsewhere: the desktop background is black at session
start until something repaints it; the taskbar draws letters where it could draw
icons; `.msi` payloads reach their UI and do not install; `RpcSs` will not start
inside the app; the component downloader is written, tested and has never run on
the device; and no CI build has yet published the `contents.json` or the source
offer the workflow now generates.
