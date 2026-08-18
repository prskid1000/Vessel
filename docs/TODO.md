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

**The frontier has moved again, and this is the third time in three days.** It
went from "does it start" to "does it load" to **"is it drawing the right
picture"**. The game loads, runs, holds a frame rate and plays sound; what it
does not do is draw the world. Everything below the graphics layer — Wine's
virtual memory, FEX's translation, the exception path, the loader, the critical
sections — has been chased to the bottom and is no longer where the failures are.
**The live blocker is #56, and it is the first one in this file that produces no
error message at all.**

Audio came right on 2026-08-16 and the shape of that bug is worth carrying into
#56, because it is the same shape. Six theories died before the real one:
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

- [ ] **#56 — the world is not drawn.** Standing inside a house basement, the
  room's own geometry is missing while the city outside is visible through where
  the walls should be, and props render as unlit black. The game is interactive
  and does not fault. **The log contains no graphics error, warning or FIXME of
  any kind** — which is itself the strongest fact in this entry, because it rules
  out every failure that announces itself and leaves only ones that do not.

  **What has been eliminated, so none of it is re-run.** Each was tested on the
  device, against the same scene:

  | eliminated | how |
  |---|---|
  | shader and pipeline caches | invalidated; the rebuild was watched happening and the picture did not change |
  | the ir3 compiler | run with `IR3_SHADER_DEBUG` variations plus `nocache`, since the flags do not invalidate the cache on their own |
  | LRZ, and LRZ fast-clear | `TU_DEBUG=nolrz`, `nolrzfc` |
  | UBWC | `TU_DEBUG=noubwc`, and separately `patches/vkd3d/0006` |
  | tiling versus direct rendering | `TU_DEBUG=sysmem`, `nobin`, `forcebin` |
  | driver-side batching | `TU_DEBUG=flushall` |
  | vkd3d's initial layout transition | `force_initial_transition` off |
  | placed versus committed resources | `patches/vkd3d/0006` |
  | the upscalers | already off before the symptom was reported |
  | the present path | reproduces on both DRI3 and the software copy |
  | the `0x100` write faults | handled probes; they recur for 1,500 lines while the game keeps rendering |
  | the RE Engine 16-bit/`min16` quirk (`patches/vkd3d/0008`) | `re9.exe` launched against the installed `3000109` build with the quirk live; the walls were still missing |
  | the compute-barrier quirk (`FORCE_COMPUTE_BARRIER`) | `re9.exe` run on vkd3d `3000111`, prefix `d3d12core.dll` verified byte-identical to that build; walls still missing. `patches/vkd3d/0008` is deleted — the tree carries no RE9 quirk |

  **The standing lead is downgraded, and here is why.** Every session log on
  this device prints `vkd3d_init_device_caps: Not all relevant pipeline stages
  are supported by EXT_dgc. Skipping.` That was read as a clue about this
  scene. It is not one: Turnip implements no part of
  `VK_EXT_device_generated_commands` at all — zero matches across
  `native/mesa/src/freedreno` — so the line fires on **every** Turnip session
  regardless of what the title draws. It is unconditional noise, not evidence
  about the basement.

  **The mechanism read off that line does not exist either.** With EXT_dgc
  unavailable, `requires_state_template` can never be true (the gate is in
  `device.c`), so the compute-shader "patch the argument buffer" path is dead
  code on this device. For a plain draw-only command signature, vkd3d's
  fallback is a **direct passthrough** to `vkCmdDrawIndirect` /
  `vkCmdDrawIndexedIndirect` / `*IndirectCount` — no compute patching runs.
  RE Engine still draws world geometry indirectly and props conventionally,
  the same split as the symptom, but "the patching pass corrupts it" was never
  the right question, because there mostly is no patching pass to blame.

  **There is a genuinely silent drop, and it no longer is one.** `command.c`
  ~17176-17181 discarded a whole `ExecuteIndirect` behind only a GPU
  debug-marker label — invisible without a capture, and logging nothing — when
  `argument_buffer_offset_for_command` is nonzero. It is reachable only for
  signatures that would have needed the state template, and those already log
  a FIXME at signature-creation time (`command.c:25602`); RE9's log shows zero
  such FIXMEs, so this path is probably not hit. "Probably" is exactly the
  ambiguity that cost a session, so it now carries a `FIXME_ONCE` naming the
  byte offset and the command count it drops (`patches/vkd3d/0007`,
  `VKD3D_REVISION=10`).

  **The instrument for the real question exists, shipped, installed — and has
  still never produced a reading.** `patches/vkd3d/0007` counts
  `ExecuteIndirect` calls and the commands they were given, per frame, on the
  `d3d · indirect` card. A frame drawing a basement with no walls should still
  be issuing indirect draws; a zero counter says the app stopped asking, a
  nonzero one says it is still asking and the missing geometry is downstream
  of the call vkd3d actually makes with it — the direct passthrough above, not
  a patching pass.

  The 2026-08-17 session was supposed to be that run. vkd3d `3000107` was
  installed and verified on the device, and the counters still wrote nothing —
  because **neither producer could open `VESSEL_GFX_STATS` at all**, for a reason
  that had nothing to do with this entry and is now fixed host-side (see the
  entry in *Fixed, and waiting for the run that would prove it*). This blocker
  was never tested; it was only ever waiting behind a second, invisible one.

  **`patches/vkd3d/0008` was tried, and it did not fix this.** `re9.exe` is
  present as a string inside the installed
  `components/VKD3D/3000109/system32/d3d12core.dll`, the games container is
  provisioned with that build, and `re9.exe` has already been launched against
  it — walls still missing. Added to the eliminated table above: the RE Engine
  16-bit/`FORCE_MIN16_AS_32BIT` quirk is not this bug, and no shape match
  survives it. The `re_hashes` half was already known not to apply (they are
  RE2/RE4 shader hashes); the global 32-bit half was the part still in play,
  and it is now the part that has been tested and lost.

  **What took a round trip to establish, and the discipline it earns.** No
  `re9.exe` session survived in the retained log set, which this entry first
  read as "never run" — wrong. It was run; the session's own log was deleted
  afterward, so the record of a completed experiment was gone and the entry
  had nothing left to distinguish that from an experiment nobody tried. A
  claim standing on an absence is only as good as the reason for the absence,
  and here the reason was housekeeping, not evidence. **Any run that tests a
  hypothesis for #56 keeps its log**, full stop — this file has now paid once
  for the alternative.

  *Done when:* the room has walls. **Next step:** unchanged from before 0008
  was tried — one session standing in the same basement, reading the indirect
  counter, log kept this time. Nothing else in this entry needs doing first.
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

  *Done when:* a Requiem session fills the `d3d · indirect` card, which is also
  the next step for #56. Both builds shipped and installed (DXVK `2070101`, vkd3d
  `3000107`, verified on device by the counter strings in the DLLs themselves) —
  and **the run produced no counters anyway**, see the next item.

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

## Resolved

Kept with a one-line outcome and the reasoning that would otherwise be
re-derived. Everything procedural about these has been dropped.

- [-] **#39 — fsync. Closed won't-do: it cannot work on Android.** `WINEFSYNC=1`
  makes `fsync_check_support` (`server/fsync.c:58`) probe `futex_waitv`, arm64
  syscall 449. Android's seccomp policy does not allow it — bionic's
  `SECCOMP_ALLOWLIST_COMMON.TXT` carries `futex` and `futex_time64` and no
  `futex_waitv` — and an out-of-allowlist syscall gets `SECCOMP_RET_TRAP`, so
  the probe does not return an error, it kills wineserver. Measured twice, the
  second on a clean install with nothing else changed: `wineboot --init` left a
  0-byte `system.reg` against a fresh prefix's ~97 KB, and the client logged
  `recvmsg: Connection reset by peer` with **no Wine error at all** — the
  absence is the fingerprint, because a killed server logs nothing.

  Also settled on the way: **esync does not exist in this Wine.** No
  `server/esync.c`, no `dlls/ntdll/unix/esync.c`, `WINEESYNC` in zero source
  files — Valve's `experimental_11.0` dropped it. So `WINEESYNC=1` had been
  inert, and `docs/OPTIMIZATION.md`'s "esync is the best available" describes a
  mechanism this build does not contain. Both variables are now unset and both
  stay reserved, so a manifest cannot reach them.

  `server/inproc_sync.c:50-65` therefore resolves to its last rung — server-side
  synchronisation — and that is the only one of the three that can run here, not
  a default nobody revisited. Reopens only if the sandbox ever permits the
  syscall. **ntsync needs no variable and is already compiled in**, so a 6.14+
  kernel would select it at runtime; whether the vendored 2021-2022
  `server/ntsync_tmp.h` still matches the ABI upstreamed in 6.14 is unverified.

- [x] **#43 — the FEX config assert.** `patches/fex/0005`. Closed 2026-08-16 by
  reading the shipped binary rather than by running a session, and the
  distinction is the point: the assert only fires on a config that *fails* to
  parse, so a thousand successful launches never exercise it and were never
  evidence. `ERROR_AND_DIE_FMT("Failed to parse JSON from file '{}' - invalid
  JSON format", …)` is gone from `Config.cpp:43`, and neither literal survives in
  `dist/fex-2608-canoe.wcp`'s `libarm64ecfex.dll` or `libwow64fex.dll` — with
  `FEXCore` present in both as a positive control that the search works on those
  files. What replaces it is an early `return` before any state is touched, so
  "an unreadable override leaves the defaults standing" is true by construction
  rather than by observation.
- [x] **#54 — the session ending when the X server went away.** Closed as a
  blocker: it was always downstream of #55's device loss, and no session has died
  on its own since. One real, independent bug was found under it and is the only
  part worth keeping — `xconnector_epoll.c` had no `EINTR` handling anywhere, and
  `signal(7)` lists `poll`, `epoll_wait` and `select` as never restarted after a
  signal *regardless of `SA_RESTART`*. ART installs handlers on this process, so
  `epoll_wait` returning `-1` fell through to `break` and `killAllConnections()`,
  dropping **every** X client at once. Both paths retry now (`:179`, `:327`),
  `c8b4b30`. If it ever recurs, `logcat -s VesselXServer` rules this mechanism in
  or out in one run.
- [x] **#51 — Requiem renders, at 56 fps.** `patches/wine/0046`.
- [x] **A relocated ntdll kept a header naming where it used to be.** `patches/wine/0044`.
- [x] **#50 — a stack overflow that was never a recursion.** `patches/wine/0041`.
- [x] **#49 — lock-order inversion between Wine's process heap and FEX's code invalidation.** `patches/fex/0019`, `patches/wine/0042`.
- [x] **#53 — `0030`'s second zero was eviction, not the frame walk.**
- [x] **The FEX code cache works end to end.** `patches/fex/0017`, `0018`, `0019`.
- [x] **Requiem's cache was malformed at generation.** `patches/fex/0017`.
- [x] **The six revision-9/10 Wine patches were never separated, and no longer need to be.**
- [x] **"Written, committed, never compiled" is closed as a section.**
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

## Corrections that must not be re-learned

Conclusions this file previously recorded as true, each of which cost a build or
a device session. They are here in the form that is useful — what is actually the
case, and where to look.

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
   upstream leaves the primary flag wherever the display driver put it and winex11
   always names a primary. Size, DPI and fullscreen placement all follow the
   primary device, so this was not cosmetic. `0036` moves the flag to the virtual
   source and keeps upstream's renumbering, which leaves `DISPLAY1` and the
   primary device as the same display again.

3. **Seeding `winebth`'s `Start=4` is a no-op on its own, and guarding the PnP
   path did not fix it either.** The value was verified present in the hive and the
   driver still loaded and still failed with `c00000e5`. `patches/wine/0031`
   guarded `load_function_driver` in `pnp.c`; the load was actually coming through
   winedevice's `device_handler` on `SERVICE_CONTROL_START`. `patches/wine/0034`
   puts the check in `open_driver` in `ntoskrnl.c`, the one place both callers
   meet. 0031 stays: it is insufficient, not wrong. The general shape — *a patch
   that honours a value nothing sets is as inert as a value nothing reads* — is
   the part worth carrying.

4. **`RTL_CRITICAL_SECTION_DEBUG.Spare` has exactly one element on 64-bit.** See
   #49 above. `Spare[1]` does not exist and writing it corrupts memory.

5. **The `.winmd` files Wine generates are genuinely malformed, and the loader
   reacts worse than the warning suggests.** `SizeOfImage` described the file
   rounded up to `0x2000` while the single section sits at `VirtualAddress 0x1000`
   and ends at `0x3000`. `map_image_into_view` sanity-checks each section against
   `SizeOfImage` and on overflow does **`goto done`** (`virtual.c:3378-3380`) — it
   abandons the section loop rather than skipping the offending section, so for a
   metadata-only file whose one section *is* the metadata, nothing beyond the
   headers is ever mapped. It does not "skip and carry on". `patches/wine/0038`
   derives `SizeOfImage` from the section header that was just laid out.

6. **The X server death is not memory pressure, and it was attributed to memory
   pressure twice.** The first time from ActivityManager `cch+95 CEM` lines, which
   are Android trimming cached background processes as it always does. The second
   time from RSS scaling with commit granularity while the system sat at 11-12 GB
   of 15.2. Both are plausible and neither is evidence. The measurement that
   settles it, taken on 2026-08-15 with a device-side sampler and a live
   `logcat` at the moment `re9.exe` died: 3.3 GB MemAvailable, 8.6 GB SwapFree,
   RSS flat-to-falling over the last 15 seconds (5.48 → 5.46 GB), no `lmkd`, no
   tombstone or `SIGSEGV`/`SIGABRT`/ANR, an orderly ~4 s teardown, and
   `app.vessel` alive with the same pid afterwards.

   Two corrections fall out of the same measurement and are the reason it is
   kept after #54 was closed. **`exit code 1` is not the game's**:
   `SessionRuntime` waits on `explorer /desktop=`, not on `re9.exe`, and
   libX11's `_XIOError` calls `exit(1)`, so *every* X client exits 1 the moment
   the connection drops. **And the game did not fault** — 3,942 lines contain no
   `err:seh:`, no unhandled exception, no `CrashReport.exe`, and per
   `TraceSpec.kt`'s `exceptions` topic an unhandled exception prints with no
   channel enabled, so that absence is proof at full strength rather than an
   argument from silence.

7. **`LookupCache::InvalidateRange` does not invalidate a page's worth of
   translations for a data write.** It is page-keyed and iterates only pages that
   have entries (`LookupCache.h:125`), so a page holding no translated code is a
   no-op. The SMC-thrash cost is per-event lock acquisition and page re-arming,
   not mass invalidation. See the live entry above.

8. **DXVK does not sum the device-local heaps for `DedicatedVideoMemory`.** It
   takes the largest (`dxgi_adapter.cpp:448`). On a single-heap UMA part the
   reported figure is the same, so #52's conclusion survives, but a reader looking
   for the sum will not find it.

9. **A path-valued environment variable is not automatically unreachable.**
   Reachability is decided by `RESERVED_SESSION_ENV` (`SessionEnvironment.kt:347`)
   and nothing else; `VKD3D_SHADER_DUMP_PATH` and `VKD3D_SHADER_OVERRIDE` are not
   in it and go through the free-text table today. What `VKD3D_CONFIG` needed was
   *list composition* — `breadcrumbs` had to join `nodxr` rather than replace it —
   which is a different problem with a different fix.

10. **`stack 0x20980` was never evidence of a small stack, and three Wine
    revisions were spent believing it was.** The address is where a thread dies,
    not how much room it had, and the range printed beside it says so. Six VS
    Code Insiders sessions span revisions 41–44 and all four print the same
    `(0x20000-0x21000-0x820000)` — 8 MB, including rev 41, which predates the
    8 MB thread-stack floor (`0053`, rev 42), the CHPE emulator-stack raise
    (`0053` again, rev 43) and the kernel/WoW64 raise (`0054`, rev 44) alike.
    None of those three moved it. The 8 MB is the app's own: `Code -
    Insiders.exe` carries `SizeOfStackReserve 0x800000` in its PE header, the
    figure Chrome, Edge and Firefox all ship with, so the thread already had
    exactly what Windows gives it.

    The fault address is equally not a culprit. `0x6fffef9c34` is
    `prepare_exception_arm64ec` entry **plus four** — its prologue, confirmed
    against the shipped `ntdll.dll` by resolving the only ADRP/ADD pair in
    `.text` that materialises `0040`'s format string. A delivery was already
    under way and could not allocate its 3280 bytes; it is the last frame, never
    the one that spent the stack. `0040`'s own counter, which fires between
    256 KB and 16 KB remaining, printed nothing in any of the six sessions —
    so exception delivery did not spend it either.

    Raising a floor cannot answer a stack that is spent. `patches/wine/0055` adds
    the two readings that can: one line per megabyte descended with the address
    that grew it, and a Misra-Gries pass over the residue at death whose stride
    between repeated return addresses gives frames × bytes-per-frame directly.

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

## Reference: FEX, and which assert can actually fire

Kept because it is expensive to rediscover and three of its four points correct an
earlier version of themselves.

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
under `ASSERTIONS_ENABLED`, which `CMakeLists.txt:189` sets for `DEBUG` builds and
`build/fex.sh:130` builds `Release`. They compile to nothing here.

**`FEX_SILENTLOG=0` works for everything after `ProcessInit` and cannot show an
assert that fires inside it.** `SilentLog` defaults to *true*
(`FEXCore/Source/Interface/Config/Config.json.in:387`), and when it is true
`FEX::Windows::Logging::Init` returns before installing a log handler, so messages
are discarded rather than written anywhere. With it false the handler writes
through `__wine_dbg_output` — the **guest's own stderr**, i.e. `tmp/guest.out`,
and *not* a `WINEDEBUG` channel log; the line to grep for begins `A `, the
single-letter level for `ASSERT`. But `ARM64EC/Module.cpp` calls
`FEX::Config::LoadConfig` before `Logging::Init()`, so a die inside config loading
precedes any handler by two lines and no amount of `SILENTLOG` will show it.

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

*Two device facts from 2026-08-10 that are still the last word on the old "large
PEs assert" theory:* it did not reproduce from the command line in the app's own
container, at any of three environments, so the discriminator is neither the image
nor the guest's `FEX_*` environment. A negative from a hand-run is weaker than the
positive it contradicts, so this is not closed — the next attempt has to be a real
session launch.

---

## Reference: what can and cannot be benchmarked here

- [ ] **DXVK/vkd3d draw throughput, and the shader cache cold against warm.**
  `docs/OPTIMIZATION.md` §1 calls recompiling already-compiled shaders the single
  largest avoidable cost in the stack; the caches were fixed and the fix was
  explicitly left unmeasured. **`tools/device-bench.sh` refuses this on purpose and
  the refusal at `:211-233` is the current, correct one**: `presentbench.c` draws
  one `ClearRenderTargetView` and no shaders by design, so wiping `caches/dxvk`
  around it times DXVK's internal blit pipeline rather than an application's
  shader set — a stable, meaningless, reproducible number.

  What is needed is a shader-heavy workload: a probe that compiles many distinct
  pipelines, or — cheaper, and needing no new code — a real title timed
  launch-to-first-frame with `caches/dxvk` wiped between runs. *Done when:* two
  launch-to-first-frame times, cold and warm, from the same title. **This is now
  worth more than it was**, because #42's verification needs a two-run cold/warm
  protocol anyway and the two can share a session.

- **Present-path micro-optimisations are below the noise floor of the harness.**
  The run-to-run spread of `run-x11present.sh` inside a single build is wider than
  the effect of any of the three present patches that were A/B'd against it. Do not
  spend a device session on a change worth ~0.15 ms until a harness exists whose
  own variance is under ~5%; present costs ~0.5 ms on the DRI3 path against frame
  times measured in tens of milliseconds.

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

**The default is now vkd3d's own**, and the arithmetic is why: `debug.c:96-97`
defaults an unset channel to `FIXME` (4) and our two loudest messages are `WARN`
(5) in a ladder where `warn` is *above* `fixme`, so shipping `warn` made us
strictly louder than upstream for no information. Moving both constants to
`fixme` cut default log volume ~62%.

One more property of the Wine parser, read out of `dlls/ntdll/unix/debug.c` rather
than assumed: a channel is created with `flags = (default_flags & ~clear) | set`
as of that moment, and a later token ORs into the existing entry. So after
`-all,err+all` a "stubs" stop must emit `warn+x,fixme+x` and not `fixme+x` — one
term gives ERR|FIXME and silently skips WARN.
