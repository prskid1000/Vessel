# TODO

What is left, why it matters, and what "done" means for each. Ordered by what
blocks the product rather than by effort.

**The rule for this file is the project's rule everywhere else: nothing is ticked
until it has been watched working on the device.** A green build, a passing test,
a patch that applies and a plausible log line are all things that have been wrong
here before. Where an item is closed by evidence, the evidence is named; where a
claim is inherited rather than re-checked, it says so in the sentence.

Status: `[ ]` open · `[~]` in progress · `[x]` done, with evidence · `[-]` closed
as won't-do, with the reason.

**The archival policy is amended, 2026-08-16, and the amendment is the honest
one.** This file used to say *"finished work is deleted rather than archived —
git history is the record"*. That policy was already not being followed: #51 sat
here as `[x]` with its whole cost table for two days, because deleting it would
have thrown away the one thing that stops the next person re-running the
experiment. The rule now matches the practice. **A resolved item keeps a one-line
outcome and whatever reasoning would otherwise be re-derived; everything else
about it goes.** The cost of the old rule was paid twice this week — the
memory-pressure theory for the X death was re-derived after being killed once,
and the "the six display patches broke the menu" theory outlived the evidence
against it. Dead hypotheses are the cheapest thing in this file to keep and the
most expensive to lose.

*Rewritten from scratch on 2026-08-14 (#18); re-ranked and re-checked against the
tree on 2026-08-16 at `cf711c6`. Two things to know about the line references.
`native/wine` and `native/vkd3d` currently have their whole patch series applied,
so line numbers into them are the patched tree's, not upstream's — where that
matters the pristine-tree number is given beside it. `native/fex` is clean, so
line numbers into it are upstream's and the FEX patches are not reflected there.*

---

## Where things stand

**Resident Evil Requiem renders, and it renders at 56 fps.** From a crash dialogue
at the title screen on the morning of 2026-08-15 to an interactive game on the
same day. The stack that does it: Wine ARM64EC plus FEX, DXVK and vkd3d-proton on
Turnip, a D3D12 device, a 1920x1080 swapchain, over three thousand pipelines,
Wwise audio, and a Media Foundation backend behind `IMFSourceReader`.

**The frontier moved four times in five days: does it start, does it load, is
it drawing the right picture — and now it is.** The game loads, runs, holds a
frame rate, plays sound and draws the world. Everything below the graphics
layer — Wine's virtual memory, FEX's translation, the exception path, the
loader, the critical sections — was chased to the bottom days ago and is not
where the failures are.
**#56 is fixed as of 2026-08-19, and it was never a driver bug.** The one
blocker in this file that produced no error message at all turned out to produce
none because nothing had failed: RE Engine's culling shaders read
`WaveGetLaneCount()` and clamp it to their threadgroup in three places out of
five, so on a device reporting 64 where they assume 32 they skip every second
item and draw half the room. `patches/vkd3d/0008`.

Audio came right on 2026-08-16 and the shape of that bug was carried into #56,
where it turned out to be the same shape exactly. Six theories died before the real one:
`oss_get_mix_format` reported int16, but a WASAPI shared-mode mix format has been
32-bit float on every Windows since Vista, so Wwise wrote float samples into an
int16 stream. Nothing logged an error — the stack was doing exactly what it was
told, and what it was told was wrong. It was settled by capturing 363 MB of PCM
and reading the same bytes both ways: as float32 the samples are clean, as int16
the zero-crossing rate is the maximum possible. `patches/wine/0050`. **A symptom
with no error message is settled by capturing the data, not by reasoning about
the code.**

Two supporting systems came right on 2026-08-15, and both are worth knowing
because they change how everything else is measured:

- **The FEX code cache works end to end**, including publication. It had been
  producing a complete 339 MB cache every launch since 11 August and throwing it
  away one line later at a rename. Watched across three Metro runs after
  `1a1c8b1`: `FEX code cache: published 12`, then `Cache up to date` for every
  module on the runs after, and 25 `Loaded cache:` lines with **zero** failures.
- **Default log volume fell about 62%.** `FIXED_VKD3D_DEBUG` and
  `FIXED_VKD3D_SHADER_DEBUG` moved from `warn` to `fixme`
  (`ContainerDiagnostics.kt:1699`, `:1711`) — which is vkd3d's own default
  (`debug.c:96-97`), and in vkd3d's ladder `warn` is *above* `fixme`, so we had
  been strictly louder than upstream. Every line-count in this file taken before
  that change is against the louder baseline.

**Two rankings this file previously asserted are dead, and are recorded here so
neither is re-derived.**

- *"#50 is the live blocker, not #51."* Wrong. #50 was real and is fixed, but the
  thing that stopped a frame was #51's commit granularity.
- *"The menu stopped because of the six revision-9/10 Wine patches, and two of
  them change what the guest believes about the display."* Wrong, and it never
  needed the bisect it demanded. The menu came back — and gameplay with it —
  under `patches/wine/0046`, with all six patches still installed. The display
  patches were never the variable; the 4 KB page size was.

The method lesson from the same week still stands and is the one worth carrying:
**three of the four faults chased on 2026-08-14 were the same defect wearing
different clothes** — *a handler answering "continue execution" for a fault it
did not repair*, so the faulting instruction re-runs forever. It appeared in
FEX's overcommit path, in FEX's RWX path, and in Wine's `RtlIsEcCode`. And
**twice, two components were installed in one step and the wrong half was
blamed.** Neither mistake was a reasoning error; both were measurement errors,
and both were free to avoid.

---

## The blockers, in order

- [x] **#56 — the world is not drawn.** Fixed 2026-08-19; it was an
  application bug, not a driver one. The full account, and the method that
  found it, are in `docs/DEBUGGING.md`.

- [ ] **#57 — VS Code recurses until its stack is gone, and the recursion is
  Chromium failing to match a font.** `Code - Insiders.exe` dies on startup with

      virtual:virtual_setup_exception stack overflow 1664 bytes
        addr 0x6fffef9c34 stack 0x20980 (0x20000-0x21000-0x820000)

  byte-identical across sixteen sessions and five Wine revisions. Wine 49's
  residue reader (`patches/wine/0055`) measured it: **5,224 frames of 1,600
  bytes, which is the whole 8 MB.**

  **The cycle, named.** A whole-frame dump gave five return addresses, and the
  outermost resolves through a string constant sitting beside it in `.rdata`:

      sans\0..\..\ui\gfx\platform_font_skia.cc\0Could not find any font:

  So `Code+0x21dadc0` is Chromium's `ui/gfx/platform_font_skia.cc` default-font
  initialisation, `"sans"` is its `kFallbackFontFamilyName`, and the chain is

      platform_font_skia default-font init
        -> Code+0x704000 -> Code+0x704580 -> Code+0x704850  (refcounted
           singleton accessor, CFG-dispatched virtual call)
        -> gdi32 ARM64EC entry thunk `$ientry_thunk$cdecl$i8$i8i8`
        -> back into the default-font init

  `Code+0x714b00` is the lazy constructor: a once-flag at `0xcdad0f6` tested with
  `jne` past the body, then `new(0x70)`, a vtable, `0x200000101` and a float
  `1.0` — `FontRenderParams`. **The flag is set after construction, so a
  re-entry during construction recurses without bound.** Chromium never reaches
  its own `Could not find any font:` fatal because it recurses first.

  **So the fault is "Chromium cannot match a font"; the recursion is how that
  failure presents.** Every frame carries a UTF-16 `"Tahoma"` LOGFONT at weight
  400 — a font that demonstrably exists in the prefix.

  **Ten hypotheses eliminated, each on the device.** None are to be re-run:

  | eliminated | how |
  |---|---|
  | the stack is too small | the exe's own `SizeOfStackReserve` is 0x800000; 8 MB is what Windows gives it too. Revisions 42-44 were spent here |
  | V8's JS stack guard | `--js-flags=--stack-size=512`, confirmed on the command line, no change |
  | nested ARM64EC exception delivery | `0040`'s counter fires between 256 KB and 16 KB remaining and never printed once |
  | dead GDI font-link chains | 32 of 33 resolved to nothing; `0056` fixed them; crash unchanged |
  | missing font families | `Arial`, `Times New Roman`, `Courier New` substituted onto installed fonts; no change |
  | the GDI font layer failing | `+font` shows it succeeding: metrics return, glyph metrics cached, varied widths |
  | `--in-process-gpu` | dropping it let JS reach `vx.main`; the browser process still loses its main thread |
  | the `.node` modules not self-registering | the real error is `Unable to open registry key` from `GetStringRegKey`, so they load and run |
  | Chromium's own errors | registry key, MAC address, `DCompositionCreateDevice3`, First-Party Sets, DNS: all logged and survived |
  | generic family names | `sans`, `sans-serif`, `serif`, `monospace` substituted onto real fonts; no change |

  **Why the last one failed, and it is the lead.** Skia reaches fonts through
  **DirectWrite**, and `FontSubstitutes` is a GDI mechanism Wine's `dwrite` never
  consults. Every font fix so far has been on the GDI side of a stack Chromium
  does not use for this. The `dwrite` trace shows
  `dwritefontcollection_FindFamilyName L"Tahoma"` succeeding and `L"sans"`
  failing, so the question is what Skia does with what it gets back.

  **Also measured, and separate.** Wine's DirectWrite fallback is quadratic here:
  `fallback_map_characters` walks `mapping->families_count` entries calling
  `create_matching_font` on each, and the system fallback table names dozens of
  Noto faces absent from this device, so every lookup misses and rescans the
  whole collection — about 1,000 `localizedstrings` calls per
  `MapCharacters`. With the trace on it is slow enough to mask the crash
  entirely, which made one session look like a fix. Worth a patch of its own;
  not this bug.

  **UPDATE, and the recursion is fixed.** `patches/wine/0057` gave DirectWrite a
  last-resort collection scan and the stack overflow is gone: zero in three
  sessions with tracing off, where every previous session had one. VS Code now
  reaches `StorageMainService`, writes its own `main.log` for the first time,
  creates a window and checks for updates. What blocks it is now a different
  fault, one layer down.

  **Every Chromium child process dies in Wine's ARM64 unwinder.** Measured:

      virtual_handle_fault unrepaired read fault at 0x0 from ...ea7df8   x7
      gpu_process_host.cc:992  GPU process launch failed: error_code=40  x6
      gpu_data_manager_impl_private.cc:418  GPU process isn't usable. Goodbye.
      wine: Unhandled exception 0x80000003 (EXCEPTION_BREAKPOINT)

  `...ea7df8` is `process_unwind_codes+0x88` in `dlls/ntdll/unwind.c`, and the
  faulting instruction is the `ldr x9,[x8]` / `str x9,[x2,#0xf0]` pair that
  compiles from

      else if (*ptr == 0xea)  /* MSFT_OP_CONTEXT */
      {
          ARM64_NT_CONTEXT *src_ctx = (ARM64_NT_CONTEXT *)context->Sp;
          *context = *src_ctx;

  so `context->Sp` is NULL and the unwinder copies a whole context out of address
  zero. Seven faults, six failed child launches: one per child. The FATAL and the
  breakpoint are consequences, not causes, and neither the GPU nor the sandbox is
  involved -- `--no-sandbox` changes nothing and the same `error_code=40` kills
  the renderer and the GPU process alike, which is process creation rather than
  graphics. The second fault address, `...e97cf0`, is `DbgUiRemoteBreakin`, which
  is Wine's own "starting debugger" path reacting to the crash.

  *Open question:* why an unwind runs at all during child-process startup, and
  what leaves `Sp` at zero. A guard on `Sp == 0` would convert the crash into
  something further along and is worth one build as a probe, not as a fix.

  *Done when:* VS Code opens a window. **Next step:** read
  `dwritefontcollection_FindFamilyName` and `create_matching_font` against what
  Skia's `SkFontMgr_DirectWrite` expects back, rather than guessing at another
  substitution. **Keep the log** for any run that tests a hypothesis.
---

---

## Fixed, and waiting for the run that would prove it

Written, shipped, and not yet watched. This section exists because the file's
top rule makes "fixed" and "proven" different words, and mixing them is how a
regression gets attributed to the wrong change three days later.

- [~] **DRI3's present copy is split across a worker pool, and the number that
  justified it has not been re-taken.** The copy measured `mean=19114us
  max=385490us` at 1280x720 — about 154 MB/s for a 3.5 MB frame, which is
  write-combine read speed, not DRAM. `copy_pool.c` splits it into row bands
  across four participants; uncached reads are latency-bound, so bands should
  overlap misses and scale close to linearly. **That is a mechanism argument, not
  a measurement**, and `ZERO_COPY_PRESENT` was flipped back to `true` on it.

  Correctness *was* measured, and by execution rather than reasoning: `copy_pool.c`
  was cross-compiled for `x86_64-linux-android` and run against a reference
  implementation of the old code over 921 shapes — contiguous and strided, odd
  heights, heights below and above the band count, zero rows, either side of the
  threshold — byte-identical every time, with canaries proving nothing is written
  outside the rectangle.

  **One thing to check before crediting or blaming the pool.** The
  `DMA_BUF_IOCTL_SYNC` bracket sits in `Drawable.java` *outside* the pooled copy,
  and the 19.1 ms figure spans both ioctls. If cache maintenance is the dominant
  term, the pool is parallelising the wrong half. A timer split — sync-in, copy,
  sync-out reported separately — is the cheap experiment that says which.

  *Done when:* one session prints a `Present copyArea` line with the split. Low
  single digits earns the default; still tens of milliseconds means the mechanism
  argument was wrong and the container row turns DRI3 off with no rebuild.

- [ ] **The D3D panel is empty for every D3D12 title, and the fix is unbuilt.**
  `patches/dxvk/0001` hooks `DxvkDevice::presentImage`, so the counters exist for
  D3D 8/9/10/11 and never for D3D 12 — Metro fills the panel, Requiem leaves it
  blank. `patches/vkd3d/0007` is the same instrument on the other side of that
  boundary, writing the same schema to the same path. `patches/dxvk/0001` also
  gained the four idle/sync counters DXVK has always kept and nothing ever read,
  which are the only thing in the family that says whether a frame was GPU-bound
  or CPU-bound rather than how much work was in it.

  *Done when:* a Requiem session fills the `d3d · indirect` card. Both builds
  shipped and installed (DXVK `2070101`, vkd3d `3000107`, verified on device by
  the counter strings in the DLLs themselves) — and **the run produced no
  counters anyway**, see the next item. It was once the next step for #56; that
  entry closed without it, so this is now wanted for its own sake.

---

## Live, and not blocking a frame

- [~] **`is_ec_code`, the unix twin of `RtlIsEcCode`, is bounded —
  `patches/wine/0048`, written and not yet compiled.**
  `dlls/ntdll/unix/unix_private.h:475` had the identical shape and the identical
  hazard as the function `patches/wine/0041` fixes.

  **The "Done when" is answered by counting callers rather than by measuring, and
  the reason this entry stayed open is that one of its own clauses was wrong.** It
  said the change had to be measured because this is *"an inline on a hot path"*.
  It is not on a hot path. `is_ec_code` has exactly one caller,
  `signal_set_full_context` (`unix/signal_arm64.c:366`), and that function has
  exactly two: `NtContinueEx` (`unix/server.c:875`) and `signal_start_thread`
  (`unix/signal_arm64.c:1558`). One call per `NtContinue`, one per thread start —
  syscall granularity, order thousands a session, nothing per frame. The hot path
  the note was protecting belongs to the *other* copy: FEX's
  `BranchTargetInMultiblockRange` calls the exported `RtlIsEcCode`, which has
  paid this comparison since `0041`. So the bound is one compare on a path that
  has just made a syscall, and there is no measurement in which that loses.

  **It is also reachable, which the old entry left as "nothing has been observed
  faulting there".** The value tested is `frame->pc` as `NtSetContextThread` has
  just written it out of a caller-supplied `CONTEXT`, so an `NtContinue` with a
  `Pc` above the address space indexes `Peb->EcCodeBitMap` up to `ptr >> 15`
  bytes past the mapping.

  **Two corrections worth keeping.** *The word "unbounded" meant the index, not a
  search* — the function is a single indexed bitmap load, `page_size` is a
  compile-time constant (`unix_private.h:153`) so the divide is a shift, and there
  is no loop to cap. And *the bound must come from the allocation, not from a
  query about it*: `0048` sets `arm64ec_map_pages` from the size
  `alloc_arm64ec_map` handed to `map_view` (`unix/virtual.c:3052`), because
  `0041`'s first version derived one from `NtQueryVirtualMemory`, got a single
  region instead of the whole reservation, and killed Metro 2033 by reporting real
  EC addresses as not-EC.

  *Done when:* it is compiled and a session runs on it. **`WINE_REVISION` in
  `native/pins.env` is owed** — `patches/wine/README.md` states the rule and a new
  patch with an unchanged revision builds, installs and does nothing.

---

## Backlog

Real work, none of it blocking a frame.

- [ ] **#36 — put a release build back on the phone.** The device is running the
  debug variant; `docs/BUILDING.md:116` and `:152` are the sideload-debug assemble
  and install lines that everything has been measured against. Every performance
  figure in `docs/OPTIMIZATION.md` therefore carries a debug-build caveat that
  nobody has priced — **including the 56 fps in #51.** *Done when:* a
  release-variant APK runs a session on the device and one of the existing
  measurements is repeated on it.

- [ ] **#17 — `.msi` support.** From a launch-type matrix measured before
  2026-08-14: `msiexec` loads `msi.dll`, `cabinet.dll`, `wintrust.dll` and
  `comctl32.dll` and gets as far as drawing a window, and the payload is not in
  `C:\Program Files` afterwards. So it reaches its UI and does not install.
  Whether the fault is the minimal test package or `msi.dll` has never been
  tested. *Done when:* an `.msi` installs a program that then launches from the
  app's own tile grid.

---

## Carried forward, not re-verified this session

Inherited from the previous file. Each was true when it was written and none of it
was re-measured since, so treat each as a claim with a date rather than as current
fact.

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
  (`ContainerDiagnostics.kt:747-751`); that every control on the diagnostics screen
  changes the environment of a real session, read out of `/proc/<pid>/environ`
  rather than asserted in a unit test; and that the one-session tier really is
  spent at the right moment on a real launch. *(2026-08-10.)*
- [~] **Two shell fixes were written and never watched.** The taskbar walk that
  descends into the virtual desktop (`display/XServerDisplay.kt`), and the
  interpreter badge that says `cmd`/`msiexec`/`wscript`/`shortcut` instead of
  `unknown`. *(2026-08-08.)*
- [ ] **A README that is true**, on whatever day it is next looked at.

---

## Where the rest of this lived

Debugging method, solved critical bugs and how each was actually found, the
corrections that must not be re-learned, and the FEX / benchmarking / logging
references have all moved to **`docs/DEBUGGING.md`**, which is now the single
file for that material. This one is the task list.
