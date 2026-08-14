# TODO

What is left, why it matters, and what "done" means for each. Ordered by what
blocks the product rather than by effort.

**The rule for this file is the project's rule everywhere else: nothing is ticked
until it has been watched working on the device.** A green build, a passing test,
a patch that applies and a plausible log line are all things that have been wrong
here before. Where an item is closed by evidence, the evidence is named; where a
claim is inherited rather than re-checked, it says so in the sentence.

Status: `[ ]` open · `[~]` in progress · `[x]` done, with evidence · `[-]` closed
as won't-do, with the reason. **Finished work is deleted rather than archived** —
git history is the record, and a file that accumulates everything ever done stops
being read. The cost is accepted knowingly: a decision closed as won't-do can be
reopened by someone who never saw the reasoning, so anything that must survive
belongs in a comment beside the code it constrains, not in a list.

*Rewritten from scratch on 2026-08-14 (#18). Every claim below about the code was
re-read against the tree that day; the previous file had drifted far enough that
softening its wrong entries would have been worse than deleting them. Two things
to know about the line references. `native/wine` currently has the whole patch
series applied — a build is running — so line numbers into it are the patched
tree's, not upstream's. `native/fex` is clean, so line numbers into it are
upstream's and the FEX patches are not reflected there.*

---

## Where things stand

**Resident Evil Requiem renders its first-run settings menu** — voice language,
display language, subtitles, audio device. Every earlier attempt died before a
frame. That is the current high-water mark of the whole stack: Wine ARM64EC plus
FEX, DXVK and vkd3d-proton on Turnip, a D3D12 device, a 1920x1080 swapchain, over
a thousand pipelines compiled, Wwise audio, and now a Media Foundation backend
behind `IMFSourceReader`.

What moved it was #46, closed below: `mfplat` had no decoder behind it at all.

Measured on the device the same day, and these are counts rather than
impressions: Media Foundation failures **0** · `Compiled without DH support`
**0**, gmp now being built shared so GnuTLS gives bcrypt real Diffie-Hellman ·
the demoted Vessel status lines at `atError=0` · the display-absence diagnostics
68 lines, all `atError=0` · `wineusb` in the shipped `wine.inf` **1 → 0** · seed
23 present in the hive.

What is not true yet: the game does not get past that menu. Three faults sit in
the way and are the whole of the next section. Behind them, six patches are
written and have never been through a compiler.

**Later the same day the menu stopped happening, and it is not the six patches.**
They were compiled and shipped as Wine revision 10, every session since has
failed, and the obvious reading — that one of the six did it — was tested and is
wrong. Revision 8, the exact payload the menu was seen on, was rebuilt from
`11bdc37`, installed over a wiped app, and pointed at the same game: it stalls
*earlier* than revision 10 did, at `virtual_setup_exception stack overflow 1776
bytes` immediately after the Denuvo `.v38` blobs, with no D3D12 device at all.
Revision 10 at least reached a 1920x1080 swapchain, three images, a thousand
PSOs and Wwise before showing nothing.

So the ranking below is now wrong in one place and it matters: **#50 is the live
blocker, not #51.** Nothing has reached the menu since, which means #51 cannot be
worked on, and the fault that stops the run first is the stack overflow.

The one variable that did change alongside it, and has not been separated:
**the recreated container came back without `VESSEL_TRACE=loader,x86:errors`**
while every other parameter was preserved. A comparison run is not honest until
that is put back.

**By the end of that day #50 was root-caused, the FEX code cache produced a cache
for the first time since 11 August, and two of our own patches had to be taken
back out after breaking a game that worked.** The detail is in the entries below;
what is worth saying at the top is the shape of it. Three of the four faults
chased that day were the same defect wearing different clothes — *a handler
answering "continue execution" for a fault it did not repair*, so the faulting
instruction re-runs forever. It appeared in FEX's overcommit path, in FEX's RWX
path, and in Wine's `RtlIsEcCode`. Once named, each was a small fix; before being
named, each looked like a hang with no information in it.

The other lesson is about method and cost us most of the afternoon. **Two
components were installed in one step twice, and both times the wrong half was
blamed.** Wine `0041` and FEX `0010` went to the device together, Metro broke, FEX
was accused, reverted, and Metro stayed broken — the cause was `0041`. Then FEX
`0010`'s decline half was installed and Metro hung before Vulkan, which only
became attributable once Wine was held patch-identical across two runs. Neither
mistake was a reasoning error; both were measurement errors, and both were free to
avoid.

---

## The blockers, in order

- [ ] **#51 — an unhandled C++ exception at the settings menu.** The live
  blocker, and the only one whose fix is on the critical path to a frame of
  gameplay. Two dead ends are recorded here so that neither is walked a second
  time:

  - **The `seh` debug channel produces zero lines for it.** Whatever raises it is
    not reaching Wine's exception dispatch in a form `+seh` prints, so turning
    that channel on again buys nothing.
  - **ARM64EC RVA→export resolution is useless on these modules.** Every frame
    resolves to `tan + 0x65800`. An export table is a *floor*, not a symbol
    table: nearest-preceding-export arithmetic against a module that exports a
    handful of C runtime names will name whichever of them happens to sit lowest
    below the address, for every address. A six-figure offset from `tan` is the
    method admitting it has no information, not a location.

  *Done when:* the throw site is named by something that is not offset arithmetic
  against an export — a `+relay` window around the last successful call, an
  instrumented `__cxa_throw`, or an unwind trace of the kind that named the FEX
  config assert (see the FEX reference at the end of this file).

- [~] **#50 — a stack overflow, and it was never a recursion.** Two runs produced
  **byte-identical** traces, so this is reproducible on demand rather than a
  race. It ends the run through `abort_thread`. Anything this file or its
  predecessor said about it being intermittent is wrong.

  **Named, 2026-08-14, and the answer was not the one this entry was looking
  for.** `patches/wine/0040` logs the exception code and address for the last ~74
  deliveries before a guest stack dies, and on its first run it returned 34 lines
  that were all the same line:

  ```
  prepare_exception_arm64ec exception delivery is running the stack out:
    261000 bytes left, room for 79 more, code c0000005 flags 0 at 0000007FFFF09F44
  ```

  One access violation, at one fixed address, re-raised until the stack was gone
  — 7200 bytes per delivery, because `KiUserExceptionDispatcher` opens with
  `sub sp, sp, #0xcd0` and nothing unwinds between deliveries. So there is no
  recursing function to name: it is one fault being resumed at the same
  instruction forever.

  `0000007FFFF09F44` is `RtlIsEcCode +0x10`, resolved against the shipped ntdll's
  full symbol table. That function indexes `Peb->EcCodeBitMap` by
  `ptr / page_size` **with no bound**, while `alloc_arm64ec_map` sizes the map for
  the address space below `address_space_limit` and no further — and FEX asks it
  about every guest branch target under ARM64EC
  (`BranchTargetInMultiblockRange`). One target above the limit reads off the end
  of the allocation and raises an access violation inside a query whose honest
  answer is `FALSE`.

  `patches/wine/0041` bounds the read. **Two warnings for whoever reads this
  next.** The first version derived the bound from `NtQueryVirtualMemory` at first
  use, which answers with one region rather than the whole reservation — the bound
  came out short, real EC addresses began reporting as not-EC, and Metro 2033 went
  from working to dying in an unrepairable write fault. The bound now comes from
  `HighestUserAddress`, which is the quantity that sized the map. And the fix
  removes the *fault*, not the *loop*: something is still resuming unrepaired
  faults in the guest process, which is the same defect as the two in
  `patches/fex/` and is still open.

  *Done when:* a session runs with `0041` installed and produces neither the
  overflow nor the `running the stack out` lines. Built, not yet watched.

- [~] **#49 — the main process heap's critical section deadlocks, and the
  instrument that should name the holder prints a zero.** `patches/wine/0030`
  records where a critical section was acquired and prints it on the wait
  timeout, so the log alone can name a DLL and offset once resolved against the
  module bases `loaddll` already prints. It fires. It prints
  `which took it at 0`.

  **The reason is read out of the patched tree rather than guessed.** Both
  recording sites are inside `RtlEnterCriticalSection`
  (`native/wine/dlls/ntdll/sync.c:406`, on the `RtlTryEnterCriticalSection` fast
  path, and `:436` after the wait), and **`RtlTryEnterCriticalSection` itself
  (`:444`) records nothing**. A caller that takes the lock by calling the try
  form directly — which is what the heap does — leaves the slot at its initial
  zero, and the ERR faithfully prints it. The fix is a third
  `crit_section_note_site` call inside `RtlTryEnterCriticalSection`'s two
  success paths.

  *Why a side table and not the debug info:* `RTL_CRITICAL_SECTION_DEBUG.Spare`
  is `DWORD_PTR Spare[8/sizeof(DWORD_PTR)]` under `__WINESRC__`
  (`native/wine/include/winnt.h:6254`) — **one element on 64-bit**, and Wine
  already keeps the section's name in it. `Spare[1]` does not exist and writing
  it corrupts whatever follows the struct. 0030 uses a fixed 256-slot table
  indexed by the section's own address instead: deliberately lossy, never
  allocates, and a collision costs one wrong diagnostic line.

  **The zero is fixed and the fix was not enough, 2026-08-14.** `0030` now
  records in `RtlTryEnterCriticalSection` too, and the ERR duly printed a real
  address — `which took it at 0000006FFFF246B0`. It resolved to `ntdll +0xe46b0`,
  whose nearest symbol is `$ientry_thunk$cdecl$i8$i8`: **ntdll's own ARM64EC
  entry-thunk table.** The lock had been taken by emulated x86-64 code calling in
  through a thunk, so the recorded return address was the thunk's, and *every*
  x64-origin acquisition resolves into that same anonymous region no matter how
  often it fires. `0030` was rewritten again to walk the `x29` frame chain and
  record the first return address outside ntdll, which for that path is the guest
  caller in a module `loaddll` has named. **It has not fired since**, so the
  rewrite is verified by construction and disassembly and not by a log line.

  A second acquisition was named the same day, on the other section:
  `RtlAllocateHeap +0x6bc` held the process heap while asleep, with the loader
  section stacked behind it — resolved against ntdll's *full* symbol table
  (10,493 symbols) rather than its exports, which is the difference between that
  answer and `tan +0x1a69c`.

  *Done when:* the timeout ERR names a module and an offset, and that offset
  resolves to a function.

- [ ] **#42 — the PSO compatibility hash mismatch is a real defect, not
  self-healing.** Measured across two runs: **45 mismatches in one, 50 in the
  next.** A cache that healed itself would trend to zero on the second run of the
  same title; this does not. Not investigated beyond establishing that, and it is
  ranked below the three above because it costs compile time rather than
  correctness. *Done when:* the mismatching field is named — what vkd3d hashes
  into the compatibility key that is not stable across runs here.

---

## Written, committed, never compiled

Six patches are staged for the build that is running now: **`0032` (rewritten),
`0034`, `0035`, `0036`, `0037`, `0038`.** All 37 patches, `0002`–`0038`, apply
cleanly to a clean worktree of `native/wine` at `19d8528d64db`. **None of those
six has been through a compiler.**

That distinction is not pedantry and this session proved it: `0034` shipped with
a literal newline inside a C string literal —

```c
TRACE( "service %s is disabled
", debugstr_us(service_name) );
```

— an unterminated string that could not have compiled, mangled by the script that
generated the patch, and caught by review rather than by the person who wrote it.
"34 patches apply" was true and said nothing about whether they build. Until this
build finishes, treat everything in this section as unproven.

| Patch | What it does | Where |
|---|---|---|
| `0032` | Synthesizes an EDID when the driver supplies none | win32u `sysparams.c`, in `add_monitor` (`:2201`) |
| `0034` | Refuses to load a driver whose service is `SERVICE_DISABLED` | ntoskrnl `open_driver` |
| `0035` | A dnsapi unix backend over Android's resolver | new `dlls/dnsapi/android.c` |
| `0036` | The virtual desktop takes the primary-display flag | win32u `add_virtual_source` (`:3034`) |
| `0037` | Exports `DllCanUnloadNow` from ole32, always `S_FALSE` | `compobj.c` + `ole32.spec` |
| `0038` | `SizeOfImage` must describe the virtual layout | `tools/widl/metadata.c` |

**All six were compiled and shipped as Wine revision 10 on 2026-08-14**, so this
section is history. What it should have said, and did not, is that they went to
the device **as a block**: not one of the six has ever been separated from the
others, and they landed on the morning the menu stopped. See the entry below.

---

## Opened 2026-08-14

- [ ] **The six revision-9/10 Wine patches have never been separated, and they
  landed on the day the menu stopped.** `0032`, `0034`, `0035`, `0036`, `0037`,
  `0038` were compiled together and installed together at 10:14. The menu was last
  seen at roughly 09:00 on revision 8. Revision 8 was rebuilt and reinstalled the
  same afternoon and did *not* bring it back — but that test ran on a wiped app
  with a cold prefix and a cold PSO cache, so it separated the payload and nothing
  else. **This is the loose thread most likely to explain the thing we care about
  most.** Two of the six change what the guest believes about the display (`0032`
  synthesizes an EDID for every monitor, `0036` moves the primary flag), which is
  the shape of a game that initialises fully and then draws nothing. *Done when:*
  each of the two display patches has been run with and without, on a prefix that
  has been through at least one prior session.

- [ ] **Loading a FEX code cache crashes the emulator before the guest starts.**
  Generation works now (see below); loading does not. A launch of Requiem with a
  cache present dies at `libarm64ecfex.dll +0x9bb04` with only `re9.exe` and
  `libarm64ecfex` loaded. Proven by moving the key's `cache/` aside and changing
  nothing else: the same title then loaded 67 modules instead of 2, went past the
  Denuvo blobs and into Vulkan. **Metro is the counter-example that narrows it** —
  it ran normally with its own `metro.exe` cached, so this is not "loading is
  broken", it is something about *that* image. The candidate is that Requiem
  decrypts its own code at runtime, so a cache built from the decrypted bytes
  describes code that is not there on the next run. If so the fix is a validity
  check, not a repair. *Done when:* a title with a cache present starts, twice.

- [ ] **`CODE_CACHE_DURING_SESSION` is a live hazard while the above is open.**
  It is `true`, generation works, so every session now writes a cache that can
  kill the next launch of that title — and it will look like a random regression
  to whoever hits it, including us. Either gate loading off or set the constant
  false until the entry above is closed.

- [ ] **`is_ec_code`, the unix twin of `RtlIsEcCode`, is still unbounded.**
  `dlls/ntdll/unix/unix_private.h:477` has the identical shape and the identical
  hazard as the function `patches/wine/0041` fixes. It was left alone deliberately
  — nothing has been observed faulting there, and it is an inline on a hot path
  the change has not been measured against — but it is the same bug.

- [ ] **`DisableDEP` is a global default and should be per-title.**
  `patches/fex/0002` defaults it to true for every guest, and its own description
  names the cost: *"a genuinely wild jump is translated instead of faulting, so a
  bug that would have been a clean access violation becomes silent corruption."*
  It exists for anti-tamper stubs, which is a per-title need. Tested and exonerated
  as the cause of Metro's regression on 2026-08-14, so this is hygiene rather than
  a suspicion.

---

## Backlog

Real work, none of it blocking a frame.

- [ ] **#36 — put a release build back on the phone.** The device is running the
  debug variant; `docs/BUILDING.md:116` and `:152` are the sideload-debug
  assemble and install lines that everything has been measured against. Every
  performance figure in `docs/OPTIMIZATION.md` therefore carries a debug-build
  caveat that nobody has priced. *Done when:* a release-variant APK runs a
  session on the device and one of the existing measurements is repeated on it.

- [ ] **#43 — the FEX config assert.** `FEX::Config::JSON::LoadJSonConfig` calls
  `ERROR_AND_DIE_FMT` on a per-app config it cannot parse
  (`native/fex/Source/Common/Config.cpp:43`), from `ProcessInit`, *two lines
  before* a log handler exists — so the process died with an unexplained
  `c000001d` and no message. `patches/fex/0005` makes an unreadable override
  leave the defaults standing, which is what an override should do.
  `WINEDEBUG=+seh,+unwind` is what named the site, 5/5 reproducible, and the
  route is written up in the FEX reference below. **What is not established from
  the repository is what remains open under this number** — the patch exists and
  its commit claims the built `libarm64ecfex.dll` no longer contains the
  "invalid JSON format" string. Treat it as needing one confirming session
  rather than as work not started.

- [ ] **#39 — fsync.** The session runs esync; `docs/OPTIMIZATION.md:196` records
  esync as the best available and `docs/ARCHITECTURE.md` fixes Wine sync to it.
  `patches/wine/0020` exists so that fsync can work without `shm_open`, and
  `patches/wine/0022` stops the server probing `futex_waitv` before winefsync is
  asked for, so the groundwork is in the tree and unproven. *Done when:* a
  session runs with fsync selected and either beats esync on a measured workload
  or is written up as not worth it.

- [ ] **#17 — `.msi` support.** From a launch-type matrix measured before
  2026-08-14: `msiexec` loads `msi.dll`, `cabinet.dll`, `wintrust.dll` and
  `comctl32.dll` and gets as far as drawing a window, and the payload is not in
  `C:\Program Files` afterwards. So it reaches its UI and does not install.
  Whether the fault is the minimal test package or `msi.dll` has never been
  tested. *Done when:* an `.msi` installs a program that then launches from the
  app's own tile grid.

---

## Corrections that must not be re-learned

Five conclusions this file previously recorded as true, each of which cost a
build or a device session. They are here in the form that is useful — what is
actually the case, and where to look.

1. **An EDID cannot be seeded into the registry.** `prepare_devices()`
   (`native/wine/dlls/win32u/sysparams.c:911`) calls
   `reg_empty_key( enum_key, "DISPLAY", FALSE )` at `:928` on *every* display
   initialisation, so anything written by hand under `Enum\DISPLAY` is deleted
   before Wine repopulates the key. This was tried, shipped, and measured to
   vanish. The EDID has to be synthesized inside Wine, and in **win32u's
   `add_monitor` (`:2201`)** rather than in winex11's `display.c` — `add_monitor`
   is the single funnel that winex11's enumeration, the no-driver fallback in
   `default_update_display_devices`, and `add_virtual_source` all reach. Supplied
   from winex11 it lands on the physical monitor, which is the one the guest never
   draws into and DXVK never reads.

2. **Two monitors was never the bug; the primary flag was.** The guest draws into
   the virtual desktop while DXVK was reading the physical monitor, because
   upstream leaves the primary flag wherever the display driver put it and
   winex11 always names a primary. Size, DPI and fullscreen placement all follow
   the primary device, so this was not cosmetic. `0036` moves the flag to the
   virtual source and keeps upstream's renumbering, which leaves `DISPLAY1` and
   the primary device as the same display again.

3. **Seeding `winebth`'s `Start=4` is a no-op on its own, and guarding the PnP
   path did not fix it either.** The value was verified present in the hive and
   the driver still loaded and still failed with `c00000e5`. `patches/wine/0031`
   guarded `load_function_driver` in `pnp.c`; the load was actually coming through
   winedevice's `device_handler` on `SERVICE_CONTROL_START`. `patches/wine/0034`
   puts the check in `open_driver` in `ntoskrnl.c`, the one place both callers
   meet, where the service config is already being queried and `dwStartType` was
   sitting in the same struct unread. 0031 stays: it is insufficient, not wrong.
   The general shape — *a patch that honours a value nothing sets is as inert as
   a value nothing reads* — is the part worth carrying.

4. **`RTL_CRITICAL_SECTION_DEBUG.Spare` has exactly one element on 64-bit.** See
   #49 above. `Spare[1]` does not exist and writing it corrupts memory.

5. **The `.winmd` files Wine generates are genuinely malformed, and the loader
   reacts worse than the warning suggests.** `SizeOfImage` described the file
   rounded up to `0x2000` while the single section sits at `VirtualAddress
   0x1000` and ends at `0x3000`. `map_image_into_view` sanity-checks each section
   against `SizeOfImage` and on overflow does **`goto done`**
   (`native/wine/dlls/ntdll/unix/virtual.c:3378-3380`) — it abandons the section
   loop rather than skipping the offending section, so for a metadata-only file
   whose one section *is* the metadata, nothing beyond the headers is ever
   mapped. It does not "skip and carry on". `patches/wine/0038` derives
   `SizeOfImage` from the section header that was just laid out, so the two
   cannot drift apart again.

---

## Carried forward, not re-verified this session

Inherited from the previous file. Each was true when it was written and none of
it was re-measured on 2026-08-14, so treat each as a claim with a date rather
than as current fact.

- [~] **`ComponentDownloadService` has a downloader that has never run on the
  device.** `app/src/main/java/app/vessel/service/ComponentDownloader.kt` exists
  and is covered by eleven tests against a real socket. Nothing is wired to a
  screen. *(2026-08-09.)*
- [~] **`registry/contents.json` is published in CI and the CI has not run.**
  `.github/workflows/_component.yml:117-141` downloads the release's own `.wcp`
  files back, runs `build/gen_registry.py` over all of them and uploads the index
  beside them, serialised so two components finishing at once cannot each publish
  an index missing the other. *Done when:* a `components` release carries a
  `contents.json` that `ComponentRegistryTest`'s parser accepts. *(2026-08-09.)*
- [~] **Three diagnostics claims still need a device.** That Turnip's lines
  actually arrive in the session log with `MESA_LOG=file`
  (`ContainerDiagnostics.kt:747-751`); that every control on the diagnostics
  screen changes the environment of a real session, read out of
  `/proc/<pid>/environ` rather than asserted in a unit test; and that the
  one-session tier really is spent at the right moment on a real launch.
  *(2026-08-10.)*
- [~] **Two shell fixes were written and never watched.** The taskbar walk that
  descends into the virtual desktop (`display/XServerDisplay.kt`), and the
  interpreter badge that says `cmd`/`msiexec`/`wscript`/`shortcut` instead of
  `unknown`. *(2026-08-08.)*
- [ ] **A README that is true**, on whatever day it is next looked at.

---

## Reference: FEX, and which assert can actually fire

Kept because it is expensive to rediscover and three of its four points correct
an earlier version of themselves.

**There are two `ERROR_AND_DIE` sites in `Source/Windows/`** —
`"Couldn't detect CPU features"` (`Common/CPUFeatures.cpp:62`) and
`"Unhandled relocation"` (`Common/ImageTracker.cpp:107`) — **and naming a site by
elimination between them was never available.** `libarm64ecfex.dll` also links
FEXCore and `Source/Common`, which add about forty more: 7 in
`Interface/Core/CodeCache.cpp`, 10 in `JIT/JITClass.h`, and the rest scattered.

**One of the two Windows sites cannot fire in a session.**
`"Unhandled relocation"` lives in `LoadImageRelocations`, which `HandleImageMap`
calls only `if (IsGeneratingCache)` (`ImageTracker.cpp:135`), and both session
modules construct the tracker as `ImageTracker.emplace(*CTX, false)`
(`ARM64EC/Module.cpp:615`, `WOW64/Module.cpp:547`). Only `FEXOfflineCompiler`
passes `true`.

**The candidate set is the `ERROR_AND_DIE_FMT` sites and nothing else.**
`LOGMAN_MSG_A_FMT` and `LOGMAN_THROW_A_FMT` also end in `ForcedAssert`, but only
under `ASSERTIONS_ENABLED`, which `CMakeLists.txt:189` sets for `DEBUG` builds
and `build/fex.sh:130` builds `Release`. They compile to nothing here.

**`FEX_SILENTLOG=0` works for everything after `ProcessInit` and cannot show an
assert that fires inside it.** `SilentLog` defaults to *true*
(`FEXCore/Source/Interface/Config/Config.json.in:387`), and when it is true
`FEX::Windows::Logging::Init` returns before installing a log handler, so
messages are discarded rather than written anywhere. With it false the handler
writes through `__wine_dbg_output` — the **guest's own stderr**, i.e.
`tmp/guest.out`, and *not* a `WINEDEBUG` channel log; the line to grep for begins
`A `, the single-letter level for `ASSERT`. But `ARM64EC/Module.cpp` calls
`FEX::Config::LoadConfig` at `:585` and `Logging::Init()` at `:587`, so a die
inside config loading precedes any handler by two lines and no amount of
`SILENTLOG` will show it.

**What did work was `WINEDEBUG=+seh,+unwind`, and the reason is worth keeping.**
`+seh`'s register dump comes from `TRACE_CONTEXT`, and in an ARM64EC ntdll the
`__x86_64__` variant is selected — it prints the AMD64-named subset only, and the
link register is aliased onto `FloatRegisters[0]`, which that macro never prints.
`RtlVirtualUnwind2` opens with a `TRACE` naming base, pc and rva; `ForcedAssert`
is `naked` with no unwind data, so it takes the leaf path (`context->Pc =
context->Lr`) and the *second* of those lines carries the caller's base and rva
directly, with no arithmetic. `llvm-objdump` on the shipped `libarm64ecfex.dll`
then names the symbol. That is the sequence that produced
`ProcessInit → LoadConfig → AppLoader::AppLoader → Load → LoadJSonConfig →
ForcedAssert`, 5/5 reproducible.

*Two device facts from 2026-08-10 that are still the last word on the old
"large PEs assert" theory:* it did not reproduce from the command line in the
app's own container, at any of three environments, so the discriminator is
neither the image nor the guest's `FEX_*` environment. A negative from a hand-run
is weaker than the positive it contradicts, so this is not closed — the next
attempt has to be a real session launch.

---

## Reference: what can and cannot be benchmarked here

- [ ] **DXVK/vkd3d draw throughput, and the shader cache cold against warm.**
  `docs/OPTIMIZATION.md` §1 calls recompiling already-compiled shaders the single
  largest avoidable cost in the stack; the caches were fixed and the fix was
  explicitly left unmeasured. **`tools/device-bench.sh` refuses this on purpose
  and the refusal at `:211-233` is the current, correct one**: `presentbench.c`
  draws one `ClearRenderTargetView` and no shaders by design, so wiping
  `caches/dxvk` around it times DXVK's internal blit pipeline rather than an
  application's shader set — a stable, meaningless, reproducible number.

  What is needed is a shader-heavy workload: a probe that compiles many distinct
  pipelines, or — cheaper, and needing no new code — a real title timed
  launch-to-first-frame with `caches/dxvk` wiped between runs. *Done when:* two
  launch-to-first-frame times, cold and warm, from the same title.

- **Present-path micro-optimisations are below the noise floor of the harness.**
  The run-to-run spread of `run-x11present.sh` inside a single build is wider than
  the effect of any of the three present patches that were A/B'd against it. Do
  not spend a device session on a change worth ~0.15 ms until a harness exists
  whose own variance is under ~5%; present costs ~0.5 ms on the DRI3 path against
  frame times measured in tens of milliseconds.

---

## Reference: the logging vocabularies

The diagnostics screen deliberately does not smooth these together, and neither
should anything else that displays them.

**vkd3d keeps its own words in its own order** — `none, err, info, fixme, warn,
trace` (`native/vkd3d/libs/vkd3d-common/debug.c:38-45`), where `info` is *less*
verbose than `fixme` and `warn`. **DXVK's ladder is different** — `trace, debug,
info, warn, error, none` (`native/dxvk/src/util/log/log.cpp:145-152`), with the
same six positions meaning different things.

Two subsystems with different vocabularies drawn as one control is a screen that
lies about what it sets. `ContainerDiagnosticsTest`'s *DXVK and vkd3d keep their
own words in their own order* asserts that they stay apart.

One more property of the Wine parser, read out of `dlls/ntdll/unix/debug.c`
rather than assumed: a channel is created with
`flags = (default_flags & ~clear) | set` as of that moment, and a later token ORs
into the existing entry. So after `-all,err+all` a "stubs" stop must emit
`warn+x,fixme+x` and not `fixme+x` — one term gives ERR|FIXME and silently skips
WARN.
