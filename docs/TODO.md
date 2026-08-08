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

- [~] **The registry seed never reaches `system.reg`.**
  `prefix-seed.reg` contains the `HKLM\Software\Microsoft\Wow64\{amd64,x86}`
  keys, `regedit` reports success, `provisioned.json` records the seed as
  applied, and `grep Wow64 prefix/system.reg` finds neither key after a clean
  provision. `tools/device-session.sh` does the same thing successfully and
  **reads the key back rather than trusting the exit code** — do the same, and
  fail the step loudly when it did not land. A step that reports DONE while
  having done nothing is the failure mode this project cares most about.
  *Done when:* both keys are in the hive after a fresh provision, and a
  deliberately corrupted seed makes provisioning fail rather than pass.

- [~] **`libwow64fex.dll` never reaches `syswow64`.**
  The 32-bit x86 side is dead: `xtajit.dll` fails `c0000135` and
  `wow:load_64bit_module` gives up. `libarm64ecfex.dll` is in `system32` but its
  counterpart is missing, because components are copied into the prefix *before*
  `wineboot` creates `system32` and `syswow64`. `tools/device-graphics.sh` stages
  under `pkg/` and copies after the boot for exactly this reason; the app does
  not.
  *Done when:* both FEX DLLs and the DXVK/VKD3D/Zink DLLs are present in the
  right directories after a fresh provision, and an x86-32 `.exe` runs.

- [ ] **Nothing has ever rendered a triangle.**
  All fifteen D3D probes report BLOCKED at instance creation: Vulkan does not
  advertise `VK_KHR_win32_surface`, which Wine only offers with a display driver
  loaded, and `tools/device-graphics.sh` is headless. Turnip *loads* — that is
  proven — but DXVK and vkd3d have never drawn. With the app's own X server up,
  this blocker should be gone.
  *Done when:* a D3D probe passes its pixel readback, **and** a real program with
  a 3D window keeps drawing while its screens are navigated by touch.

- [ ] **No desktop has been seen since the redesign.**
  `start.exe` loading is proven; pixels are not. These are different claims and
  only the second one matters.
  *Done when:* photographed, in both landscape directions.

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

- [ ] `.exe` ARM64 — runs natively
- [ ] `.exe` x86-64 — ARM64EC + `libarm64ecfex.dll`
- [ ] `.exe` x86-32 — WoW64 + `libwow64fex.dll` *(gated on item 1.2; the row most likely to fail)*
- [ ] `.bat` — `cmd.exe /c`
- [ ] `.msi` — `msiexec.exe /i` reaches its UI
- [ ] `.vbs` — `wscript.exe`, with the partial-WSH caveat shown
- [ ] `.ps1` — **refused**, naming Wine's stub PowerShell
- [ ] `.sh` — **never offered as launchable**
- [ ] Linux ELF — **never offered as launchable**

The last three are the point, not an afterthought. They cannot work here —
Android is bionic, not glibc, and Vessel ships FEX as Wine's two translation
DLLs, not FEXLoader — so the test is that the UI never presents them and never
fails silently. Supporting them would mean a glibc rootfs and proot, which is a
different product.

## 4. Real, not blocking

- [ ] **`ComponentDownloadService` has no downloader.** It is a manifest
  placeholder for the `dataSync` foreground type. The `play` flavour therefore
  has no way to obtain components at all.
- [ ] **`ComponentPackage` carries no `sha256` or `url`.** Nothing verifies a
  package's integrity; the registry already publishes both.
- [ ] **No fonts bundled.** `DESIGN.md` promises Inter and JetBrains Mono as
  variable fonts; `res/font` is empty, so the type scale is honest and the
  letterforms are system defaults — "mono" does not read as monospaced.
- [ ] **No program icons.** Tiles show a letter in a ringed square, which is an
  honest placeholder. Real icons need the PE resource directory and
  `RT_GROUP_ICON` unpacked.
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

Still open:

- [ ] **DXVK/vkd3d draw throughput** and **shader-cache cold vs warm** —
  unmeasurable until item 1.3.
- [ ] **`-mcpu=oryon-1` for Wine's unix side.** Valid there even though it is
  not for `CROSSCFLAGS`: `CFLAGS` reaches only the arm64 host build. Would tune
  `ntdll.so`, `win32u.so` and `winex11.drv.so`. An hour's rebuild for a win the
  current harness can barely see, so it waits for a benchmark that can.

## 6. Before the repository goes public

The remote is set (`prskid1000/Vessel`) and **nothing has been pushed.**

- [ ] **`docs/LICENSING.md` blockers.** Its own opening says they must be
  resolved first — chiefly that the vendored `com.winlator` LGPL-2.1 packages
  stay replaceable, which is the section 6 obligation.
- [ ] **Line-ending corruption in `native/wine`.** Binary `.bmp` files show as
  modified; a checkout is not clean.
- [ ] **Decide what happens to `Redesigning interfaces/`.** Untracked today:
  commit it as the design source, or ignore it.
- [ ] **A README that is true.** Whatever the state is on the day, said plainly.

---

## Where things actually stand

The **CPU story is done and measured**: ARM64EC plus FEX costs 1.09× native on
integer and 0.99× on memory, x86-32 through WoW64 costs 2.28×, Wine starts a
process in 197 ms. The **driver story is done**: Turnip answers inside a Wine
session, proven by `driverID 18` and the winediag line. The **interface is
done** and verified in portrait on the device.

What is not proven is the middle — a Windows program drawing through DXVK onto
the screen. Section 1 is entirely that sentence, and nothing in section 4 or 5
matters until it is true.
