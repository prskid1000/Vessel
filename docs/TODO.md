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
tree on 2026-08-16 at `3d03289`. Two things to know about the line references.
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

**The frontier has moved from "does it start" to "does it load".** The game
reaches its loading screen and the GPU dies there. Everything below that layer —
Wine's virtual memory, FEX's translation, the exception path, the loader, the
critical sections — has been chased to the bottom and is no longer where the
failures are. **The live blocker is one compute shader, and the layer it lives in
had never been examined until 2026-08-15.**

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

- [ ] **#55 — Requiem's loading screen is one compute shader hanging the GPU, and
  the shader is now in hand.** **The live blocker.** vkd3d breadcrumbs, on a
  build made with `VKD3D_BREADCRUMBS=1`, localised it to a single command:

  ```
  Found pending command list context 24064 in executable state,
      TOP_OF_PIPE marker 85, BOTTOM_OF_PIPE marker 84.
  ...
  ===== Potential crash region BEGIN =====
    Command: top_marker  marker: 85
      Set arg: 256 (#100)     threadgroups X
      Set arg: 1   (#1)       Y
      Set arg: 1   (#1)       Z
    Command: dispatch
    Command: bottom_marker  marker: 85
  ===== Potential crash region END =====
  ```

  The GPU started marker 85 and retired only through 84. The shader bound for it
  is set at marker 84: **`hash: 07051afbddeed881, stage: 20`** — `0x20` is
  `VK_SHADER_STAGE_COMPUTE_BIT`. So **one compute shader, dispatched 256×1×1,
  never retires**, and everything downstream is consequence:
  `vkd3d_wait_for_gpu_timeline_semaphore … vr -4`, `d3d12_device_mark_as_removed`,
  and then the deadlock in #54 because the thread that observed the loss was
  holding a Requiem critical section.

  **The shader has since been dumped and read, and it is not an ordinary
  dispatch.** Both the `.dxil` and the `.spv` are in hand. `spirv-val` accepts
  the module, so this is not a malformed shader and not a vkd3d-shader
  translation bug that a validator would catch. What the SPIR-V contains:

  | property | value |
  |---|---|
  | capabilities | `GroupNonUniformBallot`, `PhysicalStorageBufferAddresses` |
  | `LocalSize` | 256 × 1 × 1 |
  | `OpLoopMerge` | 4 |
  | `OpControlBarrier` | 4 |
  | `OpGroupNonUniform*` | 10 |
  | shape | a `WaveActiveBallot` / `WaveReadLaneFirst` work-distribution loop, entered under **divergent** control flow |

  **That combination is the classic non-terminating-dispatch shape, and
  `spirv-val` passing says nothing about it.** Vulkan requires `OpControlBarrier`
  to be reached in control flow uniform across the workgroup; the validator does
  not check uniformity, so a module that is structurally valid can still be
  undefined at execution. A ballot-driven loop whose exit condition depends on
  every lane agreeing, with a barrier inside the divergent region, terminates on
  one subgroup size and spins forever on another — and the subgroup width here is
  Adreno's, not the one the title was validated against. **This is a hypothesis
  with a mechanism, not a conclusion**: nothing has yet been run that
  distinguishes it from a driver miscompile of the same code.

  *What this rules out, each on its own evidence:*

  | theory | how it died |
  |---|---|
  | memory pressure | 3.3 GB free, 8.6 GB swap free, RSS flat at the moment of death |
  | an Android kill | no `lmkd`, no tombstone, no signal; `app.vessel` survives with the same pid |
  | the FEX AOT cache | quarantined re9's 322 MB cache and the failure reproduced identically |
  | a Wine or FEX fault | no unhandled exception in ~500k lines, and CPU is 748% idle at the hang |
  | the vkd3d warnings | alignment and DSV both shown benign against vkd3d's own conformance test |
  | a malformed or mistranslated shader | `spirv-val` passes the dumped module |

  *Next, and the tooling is already there.* `VKD3D_SHADER_OVERRIDE` names a
  directory and vkd3d reads `<path>/<hash>.spv` from it
  (`native/vkd3d/libs/vkd3d-shader/vkd3d_shader_main.c:102`; the export variant at
  `:121` uses `<hash>.lib.<export>.spv`). Setting it also forces
  `pipeline_library_ignore_spirv` (`libs/vkd3d/device.c:1126`), so a replacement
  actually takes effect rather than being served from the PSO cache — which is
  the detail that makes this a usable bisect and not just a theory.

  *Done when:* `07051afbddeed881` is replaced through `VKD3D_SHADER_OVERRIDE`
  with a module that returns immediately, and the loading screen either advances
  or does not. **Either answer closes the question this entry is about.** If it
  advances, the hang is this dispatch and the next question is which construct in
  it; if it does not, the dispatch is a symptom and the device loss is elsewhere.

  *Correction to this entry's own previous "Done when", 2026-08-16.* It said
  `VKD3D_SHADER_DUMP_PATH` "needs the same treatment `VKD3D_CONFIG` just got — it
  names a path, so it belongs to the app rather than to a container document."
  That was wrong on the mechanism. Neither `VKD3D_SHADER_DUMP_PATH` nor
  `VKD3D_SHADER_OVERRIDE` is in `RESERVED_SESSION_ENV`
  (`SessionEnvironment.kt:347`), so the free-text environment table already
  reaches both, and the dump was obtained without a new knob. The reserved list is
  what makes a name unreachable; being a path does not.

  *Do not chase the SMC thrash for this.* See the low-priority entry below: it is
  real, it is measured, and it costs CPU at a moment when there was none being
  spent.

- [ ] **#54 — the session ends when the X server goes away, and it is probably
  #55 wearing a different hat.** Every run ends with

  ```
  X connection to :0 broken (explicit kill or server shutdown)
  XIO: fatal IO error 2 (No such file or directory) on X server ":0"
  ```

  and `exit CRASHED code 1`, after the game has been rendering.

  **Ranked second, and the ranking is the point of this paragraph.** The X death
  is *downstream* of the GPU device loss in #55: the thread that observed
  `VK_ERROR_DEVICE_LOST` was holding a Requiem critical section and never
  released it, and the teardown that follows is what drops the display
  connection. **#54 may be entirely explained by #55.** It is kept as its own
  entry rather than merged, for one reason that is not sentiment: the `EINTR`
  defect found under it is a genuine, independent bug in Vessel's own X server
  that would drop every client on any signal, whether or not a GPU ever hung.
  Merging the entries would delete a real fix's only record. It is closed by
  fixing #55 **only if** a session that survives #55 also stops producing this,
  and that has not been observed.

  **The app-side surface has been examined and it cleared every theory this entry
  used to hold — including the memory one.** Measured on 2026-08-15 with a
  device-side sampler and a live `logcat`, at the moment `re9.exe` died:

  | check | result |
  |---|---|
  | `app.vessel` after the death | **still alive, same pid** — the app never died |
  | `lmkd` / lowmemorykiller | nothing |
  | tombstone, `SIGSEGV`, `SIGABRT`, ANR | nothing |
  | MemAvailable / SwapFree | **3.3 GB / 8.6 GB** free |
  | RSS over the last 15 s | **flat-to-falling** (5.48 → 5.46 GB) |
  | teardown | ~4 s, orderly |

  So memory is ruled out, not merely unproven, and the app is not what goes away.
  Two further corrections fall out of that:

  - **`exit code 1` is not the game's.** `SessionRuntime` waits on
    `explorer /desktop=`, not on `re9.exe`. libX11's `_XIOError` calls `exit(1)`,
    so *every* X client exits 1 the moment the connection drops — the desktop
    included. The code is a consequence of the X death, not a report about the
    game.
  - **The game did not fault.** 3,942 lines contain no `err:seh:`, no unhandled
    exception, no `CrashReport.exe`. Per `TraceSpec.kt`'s `exceptions` topic, an
    unhandled exception prints *without* any channel enabled — so that absence is
    already proof at full strength, and `+seh` would add nothing. (The
    `unrepaired write fault at 0x100` entries are handled probes: they recur on
    thread `0194` for 1,500 lines while the game keeps rendering.)

  **A mechanism was found in Vessel's own X server and fixed in `c8b4b30`, and it
  is still unverified.** `xconnector_epoll.c` had no `EINTR` handling anywhere —
  the string did not appear in `app/src/main/cpp` at all — and `signal(7)` lists
  `poll`, `epoll_wait` and `select` as never restarted after a signal *regardless
  of `SA_RESTART`*. ART installs handlers on this process (SIGSEGV for implicit
  null checks, SIGQUIT for thread dumps, SIGUSR1) and profilers use `SIGRTMIN+n`.
  On the epoll thread `epoll_wait` returning `-1` fell through to `break` and then
  `killAllConnections()`, dropping **every** X client at once — which is exactly
  the observed three simultaneous `X connection broken` lines with the app still
  running.

  Both paths now retry on `EINTR` (`xconnector_epoll.c:179`, `:327`), the JNI
  upcalls check for pending exceptions, and the Java side catches
  `RuntimeException` so one client's bad request cannot reach native with an
  exception pending.

  **Unverified, and the reason is stated so it is not mistaken for a pass: no
  session has died on its own since the fix.** Every session since has ended at
  #55's device loss, which is a different death. A fix that has not had the chance
  to fail is not a fix that worked.

  *Done when:* a session survives, **or** a session dies while `logcat -s
  VesselXServer` stays silent — which rules this mechanism out in one run. The log
  line is deliberately there so the fix can return a negative rather than become
  a standing assumption.

---

## Fixed, and waiting for the run that would prove it

Written, shipped, and not yet watched. This section exists because the file's
top rule makes "fixed" and "proven" different words, and mixing them is how a
regression gets attributed to the wrong change three days later.

- [~] **#42 — the PSO compatibility hash mismatch was `node_mask`, and the field
  is named.** *Its "Done when" — "the mismatching field is named" — is met.*

  Measured across two runs: **45 mismatches in one, 50 in the next**, roughly 1.5%
  of 3,025 shaders, and not trending to zero the way a self-healing cache would.
  `patches/vkd3d/0002` made a rejection say *what* mismatched, and what it said
  named the field by arithmetic rather than by guessing. Every rejection reported
  `shaders identical` and `flags 0`, and in **every** observed case the two hashes
  differed by exactly `0x100000001b3` — the FNV-1 64-bit prime.

  The hash is `h = (h * P) ^ v` and the last two inputs are `node_mask` then
  `flags`. With `flags` zero the final XOR is a no-op and the last step is a pure
  multiply by P, so a whole-prime difference means the **penultimate** input
  differed by one in bit 0. Nothing else produces that signature: a differing
  `flags` gives a difference of one, and anything earlier is multiplied by P again
  on the way out.

  **vkd3d already treats 0 and 1 as the same device everywhere except here.**
  `debug_ignored_node_mask` only complains when `mask && mask != 1`, and every
  entry point taking a NodeMask discards it after that check —
  `CreateRootSignature`, `GetCustomHeapProperties`, `GetResourceAllocationInfo3`,
  `CreateCommandList`. Pipeline creation never reads it; `state.c` only copies it
  out of the descriptor. D3D12 treats them as equivalent on a single-adapter
  device. Then the raw value went into the cache compatibility key, so an engine
  passing 0 down one path and 1 down another is refused here and matches on
  Windows.

  `patches/vkd3d/0004` normalises rather than removes — `H32(desc->node_mask ?
  desc->node_mask : 1)` at `native/vkd3d/libs/vkd3d/cache.c:2810` in the patched
  tree (pristine hunk `@@ -2773,7`; a mask of 1 hashes exactly as before, so
  existing valid blobs keep their hashes). Ruled out first, both by measurement:
  not a stale cache — a differing `vkd3d_build` is rejected earlier with
  `D3D12_ERROR_DRIVER_VERSION_MISMATCH` rather than `E_INVALIDARG`, and
  `VKD3D_REVISION` is Vessel packaging outside `vkd3d_build` — and not an unstable
  hash, the function being pure in the descriptor.

  **What it cost was compile time, not correctness**, which is why this was
  correctly ranked below the render blockers all along.

  *Now done when:* two consecutive RE9 runs on a build carrying `0004` — the
  first writes the pipeline cache, the second compares against it — and the second
  reports **zero** `PSO compatibility hash mismatch` lines. One run cannot answer
  this; a cold cache has nothing to mismatch against.

- [ ] **The FEX cache is loadable now, and the content-hash guarantee that made
  that safe does not exist yet.** This is a *new* risk created by fixing the cache,
  and `patches/fex/README.md` records it as the standing warning that just became
  live.

  `0011` and `0016` both note that Requiem's cache being refused on every load is
  what kept the risk of executing a stale translation at exactly zero. `0017` and
  `0018` deliver the first half of a loadable cache — the buffer-swap fix and
  host-side publication — without the second. **The format still has no field
  binding a cache to the bytes it was generated from, and Denuvo decrypts code at
  runtime**, so a cache generated from encrypted bytes can now be loaded and run.
  That is silent wrong execution, and far harder to diagnose than the honest
  refusal it replaces.

  A second, smaller thing `0017` does not fix: it stops at the 128 MB code buffer
  ceiling and `Main.cpp` walks `BlockList` in *address* order, so re9's cache
  covers an arbitrary quarter of its blocks rather than a hot one. Each
  regeneration caches a *different* quarter, which is why one `.new` file came out
  1.9 MB smaller than the published one. Hotness-ordered codemaps are the change
  that would make the 128 MB the useful 128 MB.

  *Done when:* the cache header carries a hash of the bytes the cache was
  generated from, and a cache whose source bytes have changed is refused with one
  line. Until then, **the first session after any cache-affecting change must be
  an observed one** — a wrong translation will not announce itself.

---

## Live, and not blocking a frame

- [ ] **SMC thrash is real, measured, and cheaper than it looks.** 3,656
  self-modifying-code handles in one run, **79% of them two PCs writing one
  address**. It is documented in `InvalidationTracker.cpp:315-330` as the
  code-and-data-share-a-page shape, and that comment's own pathological bar is *"a
  million cycles… a process that burns a core and renders nothing"*. At the
  measured rate — order 10-20 events/sec — this is nowhere near it.

  **The theory this entry used to carry is wrong, and the correction is the useful
  part.** It said the cost was *invalidating a whole page of translations for a
  data write*. `LookupCache::InvalidateRange`
  (`native/fex/FEXCore/Source/Interface/Core/LookupCache.h:125`) is already
  page-keyed: it takes `CodePages.lower_bound(Start >> 12)` through
  `upper_bound((Start + Length - 1) >> 12)` and iterates only the pages that have
  entries. **A page holding no translated code is a no-op** — the loop body never
  runs and `CodePages.erase` removes an empty range. There is no mass
  invalidation to eliminate.

  The real cost is write-lock acquisition per code buffer per thread, plus
  re-arming the page protection after each fault. Both are per-event and small.

  *Done when:* nothing, unless it is measured above the bar. Recorded so the next
  person who sees 3,656 of anything does not spend a day on it. **Reopen if** the
  rate is ever seen above ~1,000/sec, or if a profile attributes measurable wall
  time to `InvalidateRange` or to the fault path around it.

- [ ] **#52 — the game is told it has 15.6 GB of VRAM, because on a UMA part that
  is what the device-local heap is.** Adreno shares memory with the CPU, so the
  figure DXVK reports as `DedicatedVideoMemory` is the whole of system RAM — and
  it is the number Requiem sizes its streaming and texture pools from, in the same
  15.6 GB that Android, FEX, wineserver and shader compilation are living in.
  Measured alongside it: 330 MB free, `am_low_memory` in logcat, and the game
  process at ~3.9 GB RSS.

  This applies to D3D12 titles too — vkd3d-proton uses DXVK's DXGI, which is why
  `DxgiFactory::QueryInterface` appears in a Requiem log at all.

  **Correction to this entry's mechanism, 2026-08-16.** It said DXVK *"sums the
  Vulkan `DEVICE_LOCAL` heaps and reports the total"*. It does not. It takes
  `deviceMemory = std::max(heap.size, deviceMemory)` over the device-local heaps
  (`native/dxvk/src/dxgi/dxgi_adapter.cpp:448`) and sums only the non-device-local
  ones into `sharedMemory` (`:451`); when there is no shared memory at all it
  copies the device figure into it (`:455-458`, whose own comment names integrated
  GPUs as the case). **The conclusion survives the correction** — on a single-heap
  UMA part the largest heap *is* all of RAM, so the reported figure is the same —
  but the sentence describing how it got there was wrong, and someone reading it
  would have looked for a sum that is not in the code.

  **Demoted, 2026-08-16, and the reason is that its motivation died.** This entry
  used to end *"that is exactly the shape of #51"*, tying it to a fault where an
  allocator sized itself to a number the device could not honour. #51 turned out
  to be a 4 KB-versus-16 KB page-granularity divergence with nothing to do with
  how much memory was reported, and #55's measurements put 3.3 GB free at the
  moment of death. Nothing currently observed is explained by this number. It
  remains a thing we tell the guest that is not true, which is reason enough to
  fix, and not reason enough to rank.

  DXVK already has the knob and Vessel already exposes it: `dxgi.maxDeviceMemory`
  and `dxgi.maxSharedMemory`, in MB, through the `DXVK_CONFIG` diagnostics row.
  *Done when:* a run with the report capped is compared against one without, on
  RSS and on `am_low_memory`. If it helps, this stops being a diagnostics row and
  becomes a container parameter, because every device will want a different
  number.

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

- [-] **`DisableDEP` is a global default, it stays one, and the per-title need it
  was raised for is already met.** Examined 2026-08-16; the finding is recorded
  at `RESERVED_SESSION_ENV` in `core/SessionEnvironment.kt`, which is the file
  that decides it, and pointed at from the compiler override in
  `SessionRuntime.kt`.

  `patches/fex/0002` defaults it to true for every guest, and its own description
  names the cost: *"a genuinely wild jump is translated instead of faulting, so a
  bug that would have been a clean access violation becomes silent corruption."*
  The second cost is a lock: with DEP off an ordinary `PAGE_READWRITE` heap block
  gives `EffectiveExec = true`, so every guest heap growth takes
  `CodeInvalidationMutex` exclusively (`InvalidationTracker.cpp:502`) where
  otherwise the notification takes no lock at all. Two fixed defects have it at
  their root (`patches/fex/0012`, and the `0188` edge in #49).

  **Why it is closed rather than done.** *It is already scoped, twice, and both
  mechanisms are live.* Per process: `SessionRuntime` sets `FEX_DISABLEDEP=0` for
  the offline compiler alone, measured at 21 modules with zero `RWX reprotect
  FAILED`. Per container: the name is deliberately **not** in
  `RESERVED_SESSION_ENV`, so the manifest stage and the container's own
  environment table both reach it — and it is not in `FEX_CACHE_KEY_IGNORED`
  either, so `fexCacheKey` digests it and a container that turns DEP off gets its
  own FEX cache directory rather than loading blocks generated with it on. The
  setting and the code it produced cannot come apart, which is what makes
  per-container scoping safe rather than merely possible.

  *What is not done, and why narrowing the default would be the wrong move.* The
  failure directions are not symmetric: DEP **on** for a title that needs it off
  is a dead launch before the first frame; DEP **off** for a title that does not
  need it is a latent risk and a mutex. Nothing observed is harmed by it — tested
  and exonerated as the cause of Metro's 2026-08-14 regression — and a per-title
  default would need a table of titles this project does not have. **Reopen only
  if a title is found that DEP-off actually breaks**, and the shape then is a
  manifest param with a default, not a reserved name, because reserving it would
  remove both mechanisms above.

  *One thing left open and recorded at the override site rather than here:*
  `fexCacheKey` digests the session's value while the compiler runs with the
  other one, so a cache keyed on DEP-on holds blocks generated with DEP off.
  Untested in both directions.

- [x] **`TraceSpec.kt`'s `shaders` comment says what the constant says.** It
  described `FIXED_VKD3D_SHADER_DEBUG` as `warn`; it has been `fixme` since
  `a647bb5` — the same day the comment was corrected, and still long enough to
  need an entry here. The arithmetic in that comment — 8 lines × 3,025 parses ≈
  24,200, within 10% of the 26,966 counted — was always correct and is kept.

  *Kept as method, because this will recur:* the stale clause restated the
  constant's **value**, which nothing compiles and nothing checks, where the same
  sentence naming the constant would still be true. The replacement links
  `[FIXED_VKD3D_SHADER_DEBUG]` instead of quoting it. Note also that this entry
  cited `ContainerDiagnostics.kt:1574` for that constant and it is at `:1711`
  (`FIXED_VKD3D_DEBUG` at `:1699`) — the same failure one level up.

---

## Resolved

Kept with a one-line outcome and the reasoning that would otherwise be
re-derived. Everything procedural about these has been dropped.

- [x] **#51 — Requiem renders, at 56 fps. `patches/wine/0046`.** *(Titled "an
  unhandled C++ exception at the settings menu" until 2026-08-15, then "a
  relocated ntdll lies about its own base"; both were symptoms this entry chased
  before the cause was found.)*

  **The cause, in one sentence:** the guest commits `0x3000` and writes 16 bytes
  past it, which faults on this 4 KB-page kernel and is silently absorbed on the
  16 KB-page phones the game is validated against, so Wine now rounds commits to a
  chosen granularity. `WINE_COMMIT_GRANULARITY_KB` overrides it without a rebuild.

  **The cost is real and measured**, and it is why the setting exists:

  | granularity | peak session RSS | peak fps | how the session ended |
  |---|---|---|---|
  | grow-on-fault quirk (1 page, on demand) | 4.6 GB | — | `VK_ERROR_DEVICE_LOST` |
  | 8 KB | 6.0 GB | 40 | X server died |
  | 16 KB | 7.5 GB | **56** | X server died |

  **The arithmetic that named it**, because it is the shape to recognise next
  time. Seven byte-identical reproductions of a fault at
  `VCRUNTIME140.dll+0x114EF vmovdqu %ymm5, -0x20(%rcx,%r8)`, `rcx = 0x22F42F20`,
  `r8 = 0xF0`, faulting at `0x22F43000` — the final tail store of a stock MSVC
  `memmove`, which rounds the length up to 32. Destination `0x2F20` into the
  block; `0x2F20 + 0xE0 = 0x3000`, exactly what the game committed;
  `0x2F20 + 0xF0 = 0x3010`, what `memmove` writes. **The allocation and the copy
  disagree by sixteen bytes**, and `0xE0` had been sitting in the first
  reproduction's notes for days without being put beside `0xF0`.

  **Four things this cost a day each, all of them dead ends worth keeping:**

  - **Wine never shorted the allocation, and this was proved twice.** A full audit
    of `unix/virtual.c`: sizes only ever round *up*, the free-area search *skips* a
    range too small rather than clamping (`virtual.c:2341`), and a commit past a
    view's end returns `STATUS_NOT_MAPPED_VIEW` rather than committing part. Then
    `patches/wine/0045` showed it directly — `virtualres: returned 0x22f40000 size
    00003000` on the same thread as the fault. **The guest asked for `0x3000` and
    received `0x3000`.** FEX passed the size through intact. Nothing below the
    guest was implicated.
  - **The address is invariant, which killed every "environment" hypothesis.**
    `0x22f43000` byte for byte with RAM at 6 GB, 8 GB and `auto` (the full
    14.9 GB); with resolution at 1280×720 and 640×360; with VRAM at 6 GB and
    `auto`. Nothing we *report* to the guest moved it and nothing we *give* it was
    short.
  - **"Harmless on Windows" was false.** Windows aligns `VirtualAlloc` bases to
    64 KB too, so nothing can start at `0x22F43000` there either and the same
    write would fault — on a 4 KB-page Windows. The 16 KB page size of the phones
    the title ships against is what absorbs it, and that is a property of the
    *device*, not of the OS.
  - **Entry-side tracing could never have answered it.**
    `NtAllocateVirtualMemory` TRACEs its arguments on entry, so a
    `VirtualAlloc(NULL, …)` logs its address as `0x0` and the address chosen is
    never recorded — which is why a day of `+virtual` sessions could see a request
    and see a region and never join the two. The fix was one line at the *exit*,
    on a channel of its own: 230,713 lines with **elided 0, dropped 0**, where
    `+virtual` elided 74% of a comparable session.

  **Two build-costing mistakes inside the fix itself.** The first version rounded
  only the *commit-into-an-existing-reservation* path, and its own guard — never
  grow past the end of the owning view — then declined to round whenever the
  reservation was itself `0x3000`. Two correct-looking pieces composing into a
  no-op. And the notice went in at **ERR**, putting 156 identical lines into one
  digest and burying the real entries, which is exactly what `0043` exists to
  prevent.

- [x] **`patches/wine/0044` — a relocated ntdll kept a header naming where it used
  to be. A real defect and a real crash, and *not* #51's cause.** This is recorded
  separately because the entry above spent two days believing they were the same
  bug.

  `virtual_relocate_module` (`unix/virtual.c:4199`) applied the base relocations
  and stopped; the map-time path doing the same job (`virtual.c:3486`) writes
  `OptionalHeader.ImageBase` **and then** relocates. Two paths, one job, one of
  them doing less. An image through the second is at a new address while its own
  header names the old one, and `ImageBase` is the documented way to find a loaded
  module's base — so the field being wrong is a lie told to anything that walks a
  loaded image.

  The chain, measured end to end in one session, and **everything from row 6 down
  is the symptom that misled this entry for days**:

  | step | evidence |
  |---|---|
  | ntdll cannot have `0x6FFFE30000` and is moved to `0x7FFFE30000` | `virtual_relocate_module 0x6fffe30000 -> 0x7fffe30000` |
  | its header still reads the old base | the assignment is absent |
  | the game reads that base's `e_lfanew` | guest `rbx = 0x6fffe3003c` = base + `0x3C` |
  | nothing is mapped there | `unrepaired fault at 0x6fffe3003c, no view covers it` |
  | the fault becomes `c0000005` | `dispatch_exception code=c0000005 info[1]=0000006FFFE3003C` |
  | the handler search walks stack *data* | ~180 frames of IEEE-754 doubles, **zero** "exception data not found" |
  | it gives up | `invalid frame 54005e (0000000000022000-0000000000420000)` |
  | nothing is ever offered the exception | `Exception frame is not in stack limits => unable to dispatch` |

  The faulting code is protection-generated, has no unwind data, and is therefore
  unwound as a leaf — pop a qword off the stack and call it a return address — so
  the walk cannot fail, it can only wander. **That is why `+seh` "produced zero
  lines for it", why every frame resolved to nonsense, and why a bad pointer
  looked like a broken stack.**

  **Why this platform and not Windows.** Windows randomises ntdll's base once per
  boot and maps it there in *every* process, so a cached base is always valid.
  Here `patches/wine/0002` forces PE images into anonymous memory (SELinux refuses
  `execmod` on a dirtied file mapping), and that patch's own notes record ntdll
  relocation as the normal case. The defect is on the common path, not in a
  corner.

  **Two theories this replaced, both kept so neither is walked again:**

  - *"ARM64X value fixups leave stale absolute addresses after a rebase."*
    **Disproved by measurement.** The shipped ntdll's dynamic relocation table
    holds 11 `VALUE` fixups, **none** of them 8 bytes and none writing an address
    inside the image; every one of the 886 `.reloc` entries is `DIR64`.
  - *"ARM64EC RVA→export resolution can name the throw site."* It cannot. Every
    frame resolves to `tan + 0x65800`. An export table is a *floor*, not a symbol
    table: nearest-preceding-export arithmetic against a module exporting a handful
    of C runtime names will name whichever sits lowest below the address, for every
    address. A six-figure offset from `tan` is the method admitting it has no
    information. Full symbol tables (ntdll 10,502 / kernelbase 16,158 / kernel32
    6,481, image base `0x180000000`) are what resolved the frames that mattered.

- [x] **#50 — a stack overflow that was never a recursion. `patches/wine/0041`.**
  Two runs produced **byte-identical** traces, so it was reproducible on demand
  rather than a race. `patches/wine/0040` logged the last ~74 exception deliveries
  before a guest stack died and returned 34 lines that were all the same line:

  ```
  prepare_exception_arm64ec exception delivery is running the stack out:
    261000 bytes left, room for 79 more, code c0000005 flags 0 at 0000007FFFF09F44
  ```

  One access violation at one fixed address, re-raised until the stack was gone —
  7,200 bytes per delivery, because `KiUserExceptionDispatcher` opens with
  `sub sp, sp, #0xcd0` and nothing unwinds between deliveries. **There was no
  recursing function to name: it was one fault being resumed at the same
  instruction forever** — the third instance of the "continue execution for a
  fault that was not repaired" defect.

  `0000007FFFF09F44` is `RtlIsEcCode +0x10`. That function indexes
  `Peb->EcCodeBitMap` by `ptr / page_size` **with no bound**, while
  `alloc_arm64ec_map` sizes the map for the address space below
  `address_space_limit` and no further — and FEX asks it about every guest branch
  target under ARM64EC (`BranchTargetInMultiblockRange`). One target above the
  limit reads off the end of the allocation and raises an access violation inside
  a query whose honest answer is `FALSE`.

  **The warning that cost a build:** the first version derived the bound from
  `NtQueryVirtualMemory` at first use, which answers with one region rather than
  the whole reservation. The bound came out short, real EC addresses began
  reporting as not-EC, and Metro 2033 went from working to dying in an
  unrepairable write fault. The bound now comes from `HighestUserAddress`, the
  quantity that sized the map.

  *Outcome:* closed by absence rather than by a named grep. Every session since
  carries `0041`, sessions now reach gameplay at 56 fps, and no `running the stack
  out` line has been reported since. **Reopen on the first one that appears.**

- [x] **#49 — a two-thread lock-order inversion between Wine's process heap and
  FEX's `CodeInvalidationMutex`. `patches/fex/0014`.** Fixed and watched
  2026-08-14: `ImageTracker::HandleImageMap` no longer holds
  `CodeInvalidationMutex` across work that allocates from the Win32 heap. Requiem
  ran past it to its title screen, interactive, with neither stuck-lock line and
  with the GPU actually busy (mean 4.7%, peak 69%, against 0% in every deadlocked
  run).

  **The cycle, measured on the device**, and it took two instruments because
  neither half is visible to the other — `patches/wine/0030` sees only
  `RTL_CRITICAL_SECTION`s, and the code mutex is a `WritePriorityMutex` over
  `RtlWaitOnAddress`, which is why `patches/fex/0013` had to be written:

  | Thread | Holds | Waits for | At |
  |---|---|---|---|
  | `0188` | main process heap section, taken from `re9.exe +0x2533e6` | `CodeInvalidationMutex` | `InvalidationTracker::InvalidateIntervalInternal +0x54` |
  | `0284` | `CodeInvalidationMutex`, and `loader_section` | main process heap section | `ImageTracker::HandleImageMap +0x60` |

  - **`0188` is ours.** `patches/fex/0002` turns DEP off, so
    `HandleMemoryProtectionNotification` computes `EffectiveExec` from readability
    alone and an ordinary `PAGE_READWRITE` heap block counts as executable. Every
    growth of the heap therefore invalidates code, and the notification arrives
    while `RtlAllocateHeap` holds the heap section across
    `NtAllocateVirtualMemory` (`heap.c:826/998/1004`).
  - **`0284` is upstream's.** `HandleImageMap`
    (`Source/Windows/Common/ImageTracker.cpp:128`) took the code mutex as its
    *first statement* and held it across `LoadImageRelocations`,
    `std::filesystem::exists`, `create_directories`, `fmt::format`,
    `CodeMapWriter` construction and file I/O. FEXCore allocates from rpmalloc,
    which is why reading FEXCore found no reverse edge — but this is Wine-side glue
    and its allocations go to the Win32 process heap.

  **Three instrument failures on the way, and they are the part worth keeping.**

  1. `0030` printed `which took it at 0` because both recording sites were inside
     `RtlEnterCriticalSection` (`sync.c:406`, `:436`) and
     **`RtlTryEnterCriticalSection` itself (`:444`) recorded nothing** — and the
     heap takes the lock through the try form.
  2. With that fixed it printed a real address that resolved to
     `$ientry_thunk$cdecl$i8$i8`: **ntdll's own ARM64EC entry-thunk table.** The
     lock had been taken by emulated x86-64 code calling in through a thunk, so
     *every* x64-origin acquisition resolves into the same anonymous region. `0030`
     was rewritten to walk the `x29` frame chain and record the first return
     address outside ntdll.
  3. Then it printed zero again — see #53 below, where the diagnosis "the frame
     walk found nothing" was itself wrong.

  *Why a side table and not the debug info:* `RTL_CRITICAL_SECTION_DEBUG.Spare` is
  `DWORD_PTR Spare[8/sizeof(DWORD_PTR)]` under `__WINESRC__` (`winnt.h:6254`) —
  **one element on 64-bit**, and Wine already keeps the section's name in it.
  `Spare[1]` does not exist and writing it corrupts whatever follows the struct.

- [x] **#53 — `0030`'s second zero was eviction, not the frame walk.** The
  diagnosis this entry opened with was wrong and the correction is the item.

  A six-minute wait was reported six times with both the site and the caller zero,
  and it was read as the `x29` walk failing to leave ntdll. It was not. **It was
  eviction: 256 slots, one entry each, and `crit_site_get_site` returning NULL on
  a key mismatch** — so a section displaced by another hashing to the same slot
  printed identically to one that was never recorded. The tell was in the same
  minute of the same session: one stuck section named its holder while another
  printed zero.

  `0030` now carries 1024 slots, four-way probing, and `crit_section_forget_site`
  on release. Wine revision 17's first run named a holder immediately, and **the
  resolution checks itself** — two instructions before the recorded site, the same
  function computes the very section the message names:

  ```
  1800a20ac:  add  x21, x21, #0xe18   ; 0x180150e18 -> runtime 0x6FFFF80E18
  1800a20b0:  mov  x0, x21
  1800a20b4:  bl   RtlEnterCriticalSection
  1800a20b8:                          ; <- recorded site: loader_init +0x54
  ```

  *Outcome:* the instrument works. **What was not established and should not be
  inherited:** that session ran under `+virtual` at roughly a hundred times normal
  cost, so the 60-second timeout it fired on says nothing about whether that hold
  was a real stall. `called from` was still zero, which is the frame walk and is
  the residue rather than the subject. Neither has cost anything since.

- [x] **The FEX code cache works end to end.** Broken since 11 August; mechanism
  fixed across `patches/fex/0012`, `0016`, `0017`, `0018`, `0019` and watched on
  the device.

  **Two separate defects, and conflating them cost days.**

  *The first was classification.* `patches/fex/0002` turns DEP off for every
  guest, so FEX treats every readable page as executable — and the offline
  compiler's own 272 MB uncommitted `LookupCache` reservation was classified as
  guest RWX code. `HandleRWXAccessViolation` claimed the first-touch fault that
  `OvercommitTracker` exists to receive, could not reprotect a page that was never
  committed, reported it handled, and resumed it:
  `RWX reprotect FAILED C000002D … occurrence 5668864`. **The second instance of
  the "continue execution for an unrepaired fault" defect, found the same day as
  the other two.** Fixed twice over, each measured alone: `SessionRuntime` sets
  `FEX_DISABLEDEP=0` for the compiler process only, and `0012` stops the
  reservation being classified at all.

  *The second was publication, and the error message pointed at the wrong thing
  for four days.* `Cache generation failed for re9.exe-…` was read as "generation
  failed". It did not fail. **A complete, validated 339 MB cache was produced
  every session and discarded one line later**, because
  `std::filesystem::rename` could not replace the previous one and its throwing
  overload was caught nowhere — not in `GenerateSingleCache`, not in
  `GenerateCache`, not in `main`. `std::terminate` took the child and the parent
  printed that one line in place of every diagnostic the child still had.

  **The denial is a rule in our own Wine, not a file-in-use heuristic.**
  `server/fd.c:set_fd_name` refuses to replace a destination any fd still has open
  — `list_empty(&inode->open)` — and never consults share modes, while
  `server/mapping.c` keeps an fd alive for the life of a mapped view. FEX's
  `ImageTracker` maps a cache at image load and never unmaps it, so from that
  instant the name cannot be replaced from inside the prefix **by anything**.
  `FILE_SHARE_DELETE` would not help; nor would closing handles earlier, since
  they are closed already. So publication left the guest: `0018` writes
  `<final>.ready`, and `promoteReadyCaches` (`SessionRuntime.kt:1732`) promotes it
  on the host before the compiler and before the first guest process — the only
  window where nothing has mapped a cache yet — where a plain Linux `rename(2)`
  replaces a mapped file without complaint. **`.ready` and never `.new`**, because
  `.new` is also what a compiler killed mid-write leaves.

  **The self-perpetuating loop this ended, which was blamed on Denuvo and was
  not.** The up-to-date test compares mtimes; the failed rename froze the cache's
  mtime older than the codemap's, so every session regenerated ~337 MB even when
  it discovered no new blocks at all.

  *Watched across three Metro runs after `1a1c8b1`:* `FEX code cache: published
  12`, then `Cache up to date` for every module on the runs after, and 25
  `Loaded cache:` lines with **zero** failures. Earlier the same week: **25 of 25**
  modules imported and compiled in 5,070 ms with `RWX reprotect FAILED` **0**, and
  **62,902 blocks across 31 modules** loaded, new-block discovery falling
  1,592 → 110.

  **How it was found, which is the transferable part.** By running the failing
  case with one variable changed — 25 seconds, no build — after hours of comparing
  whole builds against each other. Three wrong conclusions and two wasted rebuilds
  came out of the second method and none out of the first.

  *What is not closed:* whether re9's own cache loads has not been separately
  reported, and the content-hash guarantee is owed. Both are in the live section
  above.

- [x] **Requiem's own cache was malformed at generation, and `0017` is why it no
  longer is.** `re9.exe`'s cache was rejected every time with
  `Code cache relocation 1918100 of 6905637 targets 0x63b2038, outside a 0x63b2000
  byte code buffer` — 56 bytes past the end. When generation exhausted the 128 MB
  code buffer, `ClearCodeCache` installed a fresh one (`NewCodeBuffer` defaults to
  `true`) and `LatestOffset` restarted at zero, while relocations recorded earlier
  kept offsets into the old buffer and `SaveData` wrote them against a size
  computed from the new one. **Only a module large enough to exhaust the buffer
  reached it**, which is why Metro's 62,902 blocks loaded fine and Requiem's
  419,897 did not. `0017` stops before the buffer is replaced.

- [-] **The six revision-9/10 Wine patches were never separated, and no longer
  need to be.** `0032`, `0034`, `0035`, `0036`, `0037`, `0038` were compiled
  together and installed together on the morning the settings menu stopped
  appearing, and two of them change what the guest believes about the display
  (`0032` synthesizes an EDID for every monitor, `0036` moves the primary flag) —
  which is the shape of a game that initialises fully and then draws nothing. This
  entry called it *"the loose thread most likely to explain the thing we care
  about most"* and demanded each display patch be run with and without.

  **Closed by outcome rather than by the experiment it asked for.** The menu came
  back and gameplay with it, under `patches/wine/0046`, with all six still
  installed. The variable was the 4 KB page size, not the display patches. The
  correlation was real and the inference from it was wrong — a revision-8 rebuild
  was tried the same afternoon and stalled *earlier* than revision 10, which
  should have been the tell that the payload was not the variable.

  *Kept as method:* it also ran on a wiped app with a cold prefix and a cold PSO
  cache, so it separated the payload and nothing else. That is the same
  two-things-at-once measurement error as the Wine `0041` / FEX `0010` pair, for
  the third time in one week.

- [x] **"Written, committed, never compiled" is closed as a section.** It tracked
  six patches staged for a build; all six were compiled and shipped as Wine
  revision 10 on 2026-08-14, and the tree now carries `0002`–`0046`, 45 patches.

  **The reason the section existed is the part to keep.** `0034` shipped with a
  literal newline inside a C string literal —

  ```c
  TRACE( "service %s is disabled
  ", debugstr_us(service_name) );
  ```

  — an unterminated string that could not have compiled, mangled by the script
  that generated the patch, and caught by review rather than by the person who
  wrote it. **"34 patches apply" was true and said nothing about whether they
  build.** `patches/vkd3d/README.md` still carries the same caveat on its own
  unbuilt patches, which is where the habit now lives.

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

- [ ] **#43 — the FEX config assert.** `FEX::Config::JSON::LoadJSonConfig` calls
  `ERROR_AND_DIE_FMT` on a per-app config it cannot parse (`Config.cpp:43`), from
  `ProcessInit`, *two lines before* a log handler exists — so the process died
  with an unexplained `c000001d` and no message. `patches/fex/0005` makes an
  unreadable override leave the defaults standing, which is what an override
  should do. `WINEDEBUG=+seh,+unwind` named the site, 5/5 reproducible; the route
  is in the FEX reference below. **What is not established from the repository is
  what remains open under this number** — the patch exists and its commit claims
  the built `libarm64ecfex.dll` no longer contains the "invalid JSON format"
  string. Treat it as needing one confirming session rather than as work not
  started.

- [ ] **#39 — fsync.** The session runs esync; `docs/OPTIMIZATION.md:196` records
  esync as the best available and `docs/ARCHITECTURE.md` fixes Wine sync to it.
  `patches/wine/0020` exists so fsync can work without `shm_open`, and
  `patches/wine/0022` stops the server probing `futex_waitv` before winefsync is
  asked for, so the groundwork is in the tree and unproven. *Done when:* a session
  runs with fsync selected and either beats esync on a measured workload or is
  written up as not worth it.

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
   settles it is in #54: 3.3 GB MemAvailable, 8.6 GB SwapFree, RSS flat-to-falling
   over the last 15 seconds, no `lmkd`, and `app.vessel` alive with the same pid
   afterwards.

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
