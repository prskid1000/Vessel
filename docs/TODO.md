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

Still open:

- [ ] **DXVK/vkd3d draw throughput** and **shader-cache cold vs warm** —
  unmeasurable until item 1.3.
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
process in 197 ms. The **driver story is done**: Turnip answers inside a Wine
session, proven by `driverID 18` and the winediag line. The **interface is
done** and verified in portrait on the device.

What is not proven is the middle — a Windows program drawing through DXVK onto
the screen. Section 1 is entirely that sentence, and nothing in section 4 or 5
matters until it is true.
