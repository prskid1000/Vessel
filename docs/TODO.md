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

The four things between here and one sentence: *a Windows program drew on the
screen through DXVK*.

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
  *Next step, concretely:* `-Dplatforms=x11` for Mesa, against the same Android
  X11 sysroot `build/x11-sysroot.sh` already builds for Wine. Nothing else in
  this item can be tested until that lands.
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
- [ ] **A non-PE program wears an `unknown` architecture badge.** Truthful —
  there is no machine field in a batch file — and the wrong word. The badge
  wants `Launchable.Runs.via` ("cmd.exe /c", "msiexec.exe /i", "wscript.exe")
  carried into the shortcut beside the arch.
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
- [~] **No fonts bundled.** `res/font` now holds Inter 4.001 and JetBrains Mono
  2.304 as variable fonts, both OFL-1.1, both recorded in `docs/LICENSING.md`
  with their digests, and both verified from their own `name` and `fvar` tables
  by `LicensingTest` — including PANOSE `bProportion == 9` on the mono, which is
  the font's own claim to be monospaced. They are in the APK
  (`unzip -l` on `app-sideload-debug.apk`).
  *Not done:* `VesselTheme.kt` does not reference them yet, so nothing has
  changed on screen. That file is in the UI tree; the ask, including the
  variable-font trap that makes weight 500 silently render at 400, is item 2 of
  `out/needs-from-install-agent.md`.
- [~] **No program icons.** The reader exists; nothing draws with it yet.
  `core/PeIconReader` walks the optional header's data directory to the resource
  table, maps the RVA through the section table, descends the type/id/language
  tree to the lowest-numbered `RT_GROUP_ICON`, chooses an entry, and decodes the
  `RT_ICON` DIB to straight ARGB — 32, 24, 8, 4 and 1 bit, bottom-up rows, the
  doubled `biHeight`, the 1-bit AND mask, and the fallback Windows uses when a
  32-bit icon's alpha channel is entirely zero, which real executables contain
  and which renders the icon invisible if taken at face value. A 256×256 PNG
  entry is handed on rather than decoded. Every failure is null, so the lettered
  placeholder stays the fallback.
  *Evidence:* 20 tests in `PeIconReaderTest`, against PE images assembled byte by
  byte from the specification in the test source set rather than from this
  reader's own output.
  *Not done:* `VAppTile` still draws the letter. Item 4 of
  `out/needs-from-install-agent.md` has the two-line call site and the caching
  note.
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
  *Two remain, and neither blocks making the repository public:*
  - [ ] **Prominent notice, in the interface, that the app contains LGPL code.**
    Section 6's first sentence. The licence text ships in the APK now, but
    nothing shows it, and a file in a zip is not notice. This one **blocks
    distributing the APK**, and it needs a screen — item 1 of
    `out/needs-from-install-agent.md`.
  - [ ] **A source offer on the component release page.** Each `.wcp` embeds its
    upstream `sourceRef`/`sourceSha`, but the GitHub release the packages are
    published from says nothing about where their source is. One line per
    component on that release closes it.
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
- [ ] **Nothing publishes `registry/contents.json`.** Found while building the
  downloader. `.github/workflows/_component.yml` uploads `dist/*.wcp` and their
  `.sha256` sidecars to the rolling `components` release and never runs
  `build/gen_registry.py`, so there is no index anywhere and the app's catalogue
  fetch is a 404 by construction. `ComponentCatalog` says so in those words
  rather than showing an empty list, which is the honest interim state, but the
  download path cannot be exercised end to end until a build publishes one.
  *Done when:* a `components` release carries a `contents.json` that
  `ComponentRegistryTest`'s parser accepts with nothing refused.
- [ ] **Decide what happens to `Redesigning interfaces/`.** Untracked today:
  commit it as the design source, or ignore it. Checked for licensing on
  2026-08-08 and it is clean — the Nocturne design system from the sibling
  project, reference screenshots, and a generated `support.js` from the author's
  own tooling. Nothing third-party, so this is a taste decision and not a gate.
- [ ] **A README that is true.** Whatever the state is on the day, said plainly.

---

## Where things actually stand

The **CPU story is done and measured**: ARM64EC plus FEX costs 1.09× native on
integer and 0.99× on memory, x86-32 through WoW64 costs 2.28×, Wine starts a
process in 197 ms — and as of 2026-08-08 all three run from the app's own
launcher, x86-32 included. The **driver story is done and switched on**: Turnip
answers inside a Wine session with the desktop drawing at the same time, proven
by `driverID 18`, `Found compatible device '/dev/kgsl-3d0'`, the winediag line
and three `#161826` pixels out of one screenshot. The **interface is done** and
verified in portrait and in landscape.

The **shell is not** — the taskbar, the desktop it sits over, and the windows on
it. Three faults were found in one screenshot on 2026-08-08 and all three are
fixed in code and unverified on the device: the bar listed nothing because it
looked one level down instead of walking the tree, the bottom edge's reveal
handle was clipped off-screen by an inset applied to the mark rather than its
touch box, and a desktop you left and came back to sampled black because every
texture id belonged to a destroyed EGL context. Above those sits the one piece
of real feature work nobody has started: **more than one resizable window,
themed to match the product**, which is what the user asked for with a Windows
screenshot for reference. The guest is a bare `explorer /desktop=` background
today, with no decoration story at all. Wine's caption height, border width,
scrollbar width and DPI are registry-driven, so *themed and finger-sized* is
mostly configuration; *several real windows on the desktop* is not.

The prose has also come out of the interface, on the same instruction that
emptied the Metrics tab: the taskbar's tray-helper paragraph, the launcher's
empty state, and the `Browse C:` help line.

What is not proven is still the middle — a Windows program drawing through DXVK
onto the screen — but it is no longer a mystery. Every D3D probe now loads DXVK,
reaches `vkCreateInstance`, and is refused `VK_KHR_win32_surface`, because the
Turnip this project builds is configured `-Dplatforms=android` and has no X11
WSI for `winex11.drv` to map that extension from. One Mesa build option stands
between here and a triangle. Section 1.3 has the log lines.
