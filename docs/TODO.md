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

- [ ] **#51 — a relocated ntdll lies about its own base, and the game reads it.**
  *(Titled "an unhandled C++ exception at the settings menu" until 2026-08-15;
  that was the symptom. The root cause is at the bottom of this entry.)* The live
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

  **The stack arrived on its own, 2026-08-14, and it is not a fault.** With the
  deadlock in #49 fixed, Requiem reaches its title screen and settings menu and
  is interactive — and then the *game's own crash reporter* draws a dialog
  listing every frame with module and offset. Pressing OK ends the process, so
  this is fatal, not cosmetic. The system frames, resolved against the shipped
  ARM64 `ntdll`, `kernelbase` and `kernel32` full symbol tables (10,502 / 16,158
  / 6,481 symbols; image base `0x180000000`), in call order:

  | Frame | Resolves to |
  |---|---|
  | `ntdll +0xcb2cc` | thread start |
  | `kernel32 +0x541a8`, `+0x5f26c` | `BaseThreadInitThunk` region |
  | `re9.exe +0x5777357` … `+0x5961ff9` | the game, eight frames |
  | **`re9.exe +0x5963933`** | **the throw site** |
  | `VCRUNTIME140 +0x114bb` | `_CxxThrowException` |
  | `ntdll +0xc9ecc` | `KiUserExceptionDispatcher +0x3c` |
  | `ntdll +0x958b0` | `dispatch_exception +0x940` |
  | `ntdll +0xc9574` | `call_seh_handlers +0x280` |
  | `ntdll +0xc9b90` | `call_seh_handlers +0x89c` |
  | `ntdll +0x960f8` | **`call_unhandled_exception_filter +0x78`** |
  | `kernelbase +0x8f568`, `+0xf146c` | `UnhandledExceptionFilter` path |
  | `re9.exe +0x9e82ddd`, `+0x9e835ae` | the game's reporter, which drew the dialog |

  **Third reproduction, and it is deterministic — 2026-08-15.** Same site, same
  size, same offset into the page; only the region base moves:

  | | first | third |
  |---|---|---|
  | site | `VCRUNTIME140 +0x114CC` | `VCRUNTIME140 +0x114CC` |
  | destination (`rcx`) | `0x22EC2FA0` | `0x22EB2FA0` |
  | size (`r8`/`r9`) | `0xE0` | `0xE0` |
  | fault (`info[1]`) | `0x22EC3000` | `0x22EB3000` |

  A 224-byte write starting `0xFA0` into a page runs `0x80` past its end, every
  time. That rules out a wild pointer or corruption. `/proc/<pid>/maps`, read
  while the process sat frozen behind its own dialog, is the same shape on both:

  ```
  000022eb0000-000022eb3000 rw-p    12 KB   committed
  000022eb3000-000022ec0000 ---p    52 KB   reserved, never committed
  ```

  **And the allocation is the guest's own, not Wine's heap.** Wine rounds both
  region and commit to `0x400 * sizeof(void*)` — 8 KB on 64-bit (`heap.c:993`) —
  and this commits `0x3000`, which is not a multiple of that. The base is 64 KB
  aligned, which is Windows' allocation granularity. So the shape is
  `VirtualAlloc(MEM_RESERVE, 64K)` followed by `MEM_COMMIT` of three pages, done
  by the game or its anti-tamper.

  That leaves one question, and it decides whose bug this is: **did the guest ask
  to commit four pages and get three?** If it asked for three and wrote into the
  fourth, Windows would fault too and the game is relying on something else. The
  `virtual` channel logs every `NtAllocateVirtualMemory`, so one run with it on,
  grepped for this range, answers it. *Not yet run.*

  **Run, 2026-08-15, and `+virtual` cannot answer it in the form it shipped in.**
  The channel is unreadable on this stack, and the number says why: of the first
  89,000 lines, **75,505 were `virtual:dump_view` — 85%.** That macro fires on
  every view create, split and protection change, and FEX's JIT changes
  protections continuously, so the 32 MB head budget was gone in 90 seconds and
  1.87 million lines were dropped. The records the run was for —
  `NtAllocateVirtualMemory` and its neighbours, 6% of the same sample — went into
  the elision with everything else.

  Two counts from that session are worth keeping because they look alarming and
  are not: `virtual_setup_exception exception outside of stack limits` fired 1207
  times, at ~1200 *distinct* addresses in one region with the stack pointer
  constant. That is FEX taking signals while running on its own stack, which Wine
  warns about and continues from — not a loop, and not the fault being chased.

  ---

  **THE FAULT ITSELF, FULLY CHARACTERISED 2026-08-15 — and `0044` below is NOT
  its cause.** `0044` fixed a real crash (an early death whose fault was at
  `0x6fffe3003c`); this is a different one, and it survives every fix made today.
  Seven reproductions, byte-identical each time:

  ```
  VCRUNTIME140.dll+0x114EF   vmovdqu %ymm5, -0x20(%rcx,%r8)
  rcx = 0x22F42F20   r8 = 0xF0 (240)   info[1] = 0x22F43000   info[0] = 1 (write)
  ```

  Disassembled from the game's own shipped `VCRUNTIME140.dll` (not packed, unlike
  `re9.exe`): that is the **final tail store of a stock MSVC `memmove`**, which
  rounds the length up to 32 and writes whole 32-byte chunks through a jump table.
  The copy covers `[0x22F42F20, 0x22F43010)`. Traced allocations put the
  destination inside a **three-page** allocation at `0x22F40000`, so the copy runs
  **16 bytes past its end**. `/proc/<pid>/maps` agrees: `22f43000-22f50000 ---p`.

  **A full audit of `unix/virtual.c` says Wine cannot have shorted it.** Sizes
  only ever round *up*; the free-area search *skips* a range too small rather than
  clamping to it (`virtual.c:2341`); a commit past a view's end returns
  `STATUS_NOT_MAPPED_VIEW` rather than committing part. **And the fault report
  proves it independently**: had a larger request been shorted, `view->size` would
  still be the full size — it is recorded before any mmap — and `0043` would have
  printed its *in-view* WARN. It printed `no view covers it`, so Wine's own
  bookkeeping believes the allocation is three pages, and that number is the
  guest's rounded request.

  **The "harmless on Windows" assumption is also false.** Windows aligns
  `VirtualAlloc` bases to 64 KB as well, so nothing can start at `0x22F43000`
  there either; the fourth page would be `MEM_FREE` and the same write would
  fault. So this is not simply a game bug that we happen to expose.

  That leaves exactly three live hypotheses, and they have different fixes:

  1. the guest **computes** the size from something environment-dependent and on
     Windows asks for `>= 0x3010`
  2. the size is mangled **between the guest's syscall and Wine** — FEX
     marshalling, which is outside `virtual.c` and outside what the audit covered
  3. the **destination pointer**, not the size, is the environment-dependent part

  *Done when:* `patches/wine/0045` (one line per successful allocation, on its own
  `virtualres` channel) names the size of the request that returned `0x22F40000`.
  Entry-side tracing cannot answer it: `NtAllocateVirtualMemory` TRACEs its
  arguments on entry, so a `VirtualAlloc(NULL, …)` logs its address as `0x0` and
  the address chosen is never recorded — which is why a day of `+virtual` sessions
  could not join a request to a region.

  ---

  **A relocated ntdll keeps a header that says where it used to be, and
  `patches/wine/0044` is one assignment.** *(A real defect and a real crash —
  but, as recorded above, not the cause of the `memmove` fault.)*

  `virtual_relocate_module` (`unix/virtual.c:4199`) applies the base relocations
  and stops. The map-time path that does the same job — "relocate to dynamic
  base", `virtual.c:3486` — writes `OptionalHeader.ImageBase` **and then**
  relocates. Two paths, one job, one of them doing less; that is the whole
  defect. An image that comes through the second one is at a new address while
  its own header still names the old one, and `ImageBase` is the documented way
  to find a loaded module's base, so the field being wrong is not cosmetic. It is
  a lie told to anything that walks a loaded image.

  The chain, measured end to end in one session:

  | step | evidence |
  |---|---|
  | ntdll cannot have `0x6FFFE30000` and is moved to `0x7FFFE30000` | `virtual_relocate_module 0x6fffe30000 -> 0x7fffe30000` |
  | its header still reads the old base | the assignment above is absent |
  | the game reads that base's `e_lfanew` | guest `rbx = 0x6fffe3003c` = base + `0x3C` |
  | nothing is mapped there | `unrepaired fault at 0x6fffe3003c, no view covers it` |
  | the fault becomes `c0000005` | `dispatch_exception code=c0000005 info[1]=0000006FFFE3003C` |
  | the handler search walks stack *data* | ~180 frames of IEEE-754 doubles, **zero** "exception data not found" |
  | it gives up | `invalid frame 54005e (0000000000022000-0000000000420000)` |
  | nothing is ever offered the exception | `Exception frame is not in stack limits => unable to dispatch` |

  **Everything from row 6 down is the symptom this entry spent days on.** The
  faulting code is protection-generated, has no unwind data, and is therefore
  unwound as a leaf — pop a qword off the stack and call it a return address —
  so the walk cannot fail, it can only wander. That is why `+seh` "produced zero
  lines for it", why the frames resolved to nonsense, and why the crash looked
  like a broken stack rather than a bad pointer.

  **Why this platform and not Windows.** Windows randomises ntdll's base once per
  boot and maps it at that address in *every* process, so a cached base is always
  valid. Here `patches/wine/0002` forces PE images into anonymous memory (SELinux
  refuses `execmod` on a dirtied file mapping), and that patch's own notes record
  ntdll being relocated as the normal case. So the defect is not a corner on this
  stack — it is the common path.

  **Two theories this replaces, both recorded so neither is walked again:**

  - *"ARM64X value fixups leave stale absolute addresses after a rebase."*
    **Disproved by measurement,** not by argument. The shipped ntdll's dynamic
    relocation table holds 11 `VALUE` fixups, **none** of them 8 bytes and none
    writing an address inside the image; every one of the 886 `.reloc` entries is
    `DIR64`. Nothing stale can come from there. (`tools/`-style throwaway parser;
    the numbers are what matter.)
  - *"the fault is a 224-byte `memcpy` running 0x80 past a 3-page commit."* That
    was read off earlier sessions and is a **different** event; no fault of that
    shape appeared in the sessions that produced the root cause above, and no
    faulted view had `committed < size` except thread-stack guard pages.

  *Done when:* Requiem is run on a build carrying `0044` and either reaches
  gameplay or dies somewhere new. **Not yet run** — revision 19 is building.

  ---

  **`patches/wine/0043` is the fix for the instrument, in two halves.** The dump
  moves to its own `virtual_views` channel, so `+virtual` becomes readable;
  nothing below it is silenced, because second place is 4.5% and everything under
  that is what the channel exists to carry. And `virtual_handle_fault` now prints
  the view a fault it *could not repair* landed in — base, size, bytes actually
  committed, page protection — which answers the question above without a traced
  session at all. Silent until it fires, and it only fires on a fault that is
  already fatal.

  **The dialog and the logged fault are one event**, not two: the dialog's frame
  list matches the exception's module bases and offsets exactly, including
  `VCRUNTIME140 +0x114cc`. Anything above that in the dialog is the game's own
  reporter.

  So this is a **C++ `throw` that found no matching handler**: the unwinder ran,
  every SEH handler declined, and the unhandled filter was reached. That is a
  different problem from the one this entry assumed. It also explains the first
  dead end above — `+seh` prints nothing useful because the interesting part is
  not the raise, it is the *search* failing.

  **The live hypothesis, and it is not yet tested.** `virtual_unwind`,
  `dispatch_emulation` and `prepare_exception_arm64ec` all sit in the same
  address region as the frames above, and the throwing code is emulated x86-64
  under ARM64EC. If unwinding across the EC boundary cannot walk the guest's
  frames, the game's `catch` is never offered the exception even though it
  exists. *Next:* determine whether the search reaches the game's own handler at
  all — an instrument in `call_seh_handlers` naming each handler it offers the
  exception to, and what each returns, would settle it in one run.

  *Done when:* it is known whether the handler search reaches the game's frames.
  If it does, this is the game throwing legitimately and the throw's *cause* is
  the question; if it does not, it is an ARM64EC unwind defect and that is the fix.

  *Originally done when:* the throw site is named by something that is not offset
  arithmetic against an export — a `+relay` window around the last successful call,
  an instrumented `__cxa_throw`, or an unwind trace of the kind that named the FEX
  config assert (see the FEX reference at the end of this file). **Satisfied**, by
  the game's own reporter rather than by an instrument of ours.

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

- [x] **#49 — the main process heap's critical section deadlocks, and the
  instrument that should name the holder prints a zero.** **Fixed and watched,
  2026-08-14**, by `patches/fex/0014` — `ImageTracker::HandleImageMap` no longer
  holds `CodeInvalidationMutex` across work that allocates from the Win32 heap.
  Requiem now runs past it to its title screen and settings menu, interactive,
  with neither stuck-lock line and with the GPU actually busy (mean 4.7%, peak
  69%, against 0% in every deadlocked run). The history below is kept because
  three of its conclusions were wrong before they were right. `patches/wine/0030`
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

  **Both holders were named on 2026-08-14, and both are the game's own code.**
  `0030`'s rewrite prints `called from`, and the two deadlocks Requiem alternates
  between resolve to:

  | Section | Holder took it at | Called from |
  |---|---|---|
  | `388A7E28` (unnamed; both threads are Streamline's) | `ntdll +0xe4d10` | **`re9.exe +0x11c18fc`** |
  | `004500D0` (main process heap) | `RtlAllocateHeap +0x6bc` | **`re9.exe +0x2533e6`** |

  So this entry's *done when* is satisfied. What replaces it: the heap holder is
  **asleep inside `RtlAllocateHeap` while holding the heap lock**, and an
  allocation that blocks means the heap is growing — `NtAllocateVirtualMemory`,
  which on ARM64EC notifies the emulator, where FEX takes its own locks.
  `patches/fex/0004` exists because *"an ARM64EC notification hook must not block
  on the way in"*; this looks like a second instance of that shape, and it is now
  the only thing between Requiem and its menu.

  **The cycle is closed, measured on the device, 2026-08-14.** It is a two-thread
  lock-order inversion between Wine's process heap and FEX's
  `CodeInvalidationMutex`, and it took two instruments to see because neither
  half is visible to the other: `patches/wine/0030` sees only
  `RTL_CRITICAL_SECTION`s, and the code mutex is a `WritePriorityMutex` over
  `RtlWaitOnAddress`. `patches/fex/0013` was written to name the other half, and
  on the run that fired both:

  | Thread | Holds | Waits for | At |
  |---|---|---|---|
  | `0188` | main process heap section, taken from `re9.exe +0x2533e6` | `CodeInvalidationMutex` | `InvalidationTracker::InvalidateIntervalInternal +0x54` |
  | `0284` | `CodeInvalidationMutex`, and `loader_section` | main process heap section | `ImageTracker::HandleImageMap +0x60` |

  `018c` and `01fc` queue on the heap behind `0188`; `0288` queues on
  `loader_section` behind `0284`. Both FEX sites resolved against the `260815`
  build's full symbol table with image base `0x180000000`, module base
  `0000006FFC560000` from `loaddll`.

  Read as two edges:

  - **`0188` is ours.** `patches/fex/0002` turns DEP off, so
    `HandleMemoryProtectionNotification` computes `EffectiveExec` from
    readability alone and an ordinary `PAGE_READWRITE` heap block counts as
    executable. Every growth of the heap therefore invalidates code, and the
    notification arrives while `RtlAllocateHeap` holds the heap section across
    `NtAllocateVirtualMemory` (`heap.c:826/998/1004`).
  - **`0284` is upstream's.** `ImageTracker::HandleImageMap`
    (`Source/Windows/Common/ImageTracker.cpp:128`) takes the code mutex as its
    *first statement* and holds it across `LoadImageRelocations`,
    `std::filesystem::exists`, `create_directories`, `fmt::format`,
    `CodeMapWriter` construction and file I/O. FEXCore allocates from rpmalloc,
    which is why reading FEXCore found no reverse edge — but this is Wine-side
    glue and its allocations go to the Win32 process heap.

  **A failed cache load does not avoid this**, and that was checked on the same
  run: `re9.exe`'s cache was rejected by `patches/fex/0011` and the deadlock was
  unchanged. Loading is not the path — `HandleImageMap` takes the lock before any
  cache check, and with `ENABLECODECACHINGWIP` on, the codemap *writing* path
  still runs under it.

  *Now done when:* the inversion is broken — either by narrowing
  `HandleImageMap`'s lock to what genuinely needs serialising against
  invalidation, or by removing edge `0188` so a fresh commit of
  never-translated memory does not invalidate — and Requiem passes the point
  where all 78 threads currently sleep.

  *Originally done when:* the timeout ERR names a module and an offset, and that offset
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

- [x] **The FEX code cache works, end to end, and every stage was watched rather
  than assumed.** Broken since 11 August; verified 2026-08-14 on the device.

  | Stage | Evidence |
  |---|---|
  | guest writes a codemap | `new/` fills while the title runs |
  | compiler imports and compiles | **25 of 25** modules in 5,070 ms, `RWX reprotect FAILED` **0** |
  | cache validated on load | Requiem's malformed table **rejected** with one line, no crash |
  | blocks loaded and used | **62,902 blocks across 31 modules**; new-block discovery fell 1,592 → 110 |

  What fixed it was not a clever patch. `patches/fex/0002` turns DEP off for every
  guest so anti-tamper stubs survive, and the documented cost is that FEX then
  treats every readable page as executable — so the compiler's own 272 MB
  uncommitted `LookupCache` reservation was classified as guest RWX code,
  `HandleRWXAccessViolation` claimed the first-touch fault that `OvercommitTracker`
  exists to receive, could not reprotect a page that was never committed, reported
  it handled, and resumed it. `RWX reprotect FAILED C000002D … occurrence 5668864`.

  Two independent fixes, each measured alone before both were kept:
  `SessionRuntime` sets `FEX_DISABLEDEP=0` for the compiler process only, and
  `patches/fex/0012` stops the reservation being classified at all. With
  `x86:everything` the compiler's SMC intervals are 128 MB (the JIT code buffer)
  and two tiny ones — the 272 MB reservation is **absent**, which is the fix seen
  directly.

  **It was found by running the failing case with one variable changed — 25
  seconds, no build — after hours of comparing whole builds against each other.**
  Three wrong conclusions and two wasted rebuilds came out of the second method
  and none out of the first.

- [ ] **Requiem's own cache is malformed at generation.** Loading is safe now, but
  `re9.exe`'s cache is rejected every time: `Code cache relocation 1918100 of
  6905637 targets 0x63b2038, outside a 0x63b2000 byte code buffer` — 56 bytes past
  the end. The mechanism is written up under `0011` in `patches/fex/README.md`:
  when generation exhausts the 128 MB code buffer, `ClearCodeCache` installs a
  fresh one (`NewCodeBuffer` defaults to `true`) and `LatestOffset` restarts at
  zero, while relocations recorded earlier keep offsets into the old buffer and
  `SaveData` writes them against a size computed from the new one. Only a module
  large enough to exhaust the buffer reaches it, which is why Metro's 62,902 blocks
  load fine and Requiem's 419,897 do not. *Done when:* `re9.exe`'s cache loads
  instead of being rejected.

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

## Opened 2026-08-14, evening

- [ ] **#52 — the game is told it has 15.6 GB of VRAM, because on a UMA part
  that is what the device-local heap is.** `dxgi_adapter.cpp:440-480` sums the
  Vulkan `DEVICE_LOCAL` heaps and reports the total as
  `DedicatedVideoMemory`. Adreno shares memory with the CPU, so the sum is the
  whole of system RAM, and the number Requiem sizes its streaming and texture
  pools from is the same 15.6 GB that Android, FEX, wineserver and shader
  compilation are living in. Measured alongside it: 330 MB free, `am_low_memory`
  in logcat, and the game process at ~3.9 GB RSS.

  This applies to D3D12 titles too — vkd3d-proton uses DXVK's DXGI, which is why
  `DxgiFactory::QueryInterface` appears in a Requiem log at all.

  **Not proven to have caused anything yet**, and that matters: no `lmkd` line
  names the game, so nothing here is established as the cause of the exit 1.
  What makes it worth ranking is the mechanism it would explain — an allocator
  sizing itself to a number the device cannot honour, commits failing under
  pressure, and a caller that does not check writing into the page it believed
  it had. That is exactly the shape of #51.

  DXVK already has the knob and Vessel already exposes it: `dxgi.maxDeviceMemory`
  and `dxgi.maxSharedMemory`, in MB, through the `DXVK_CONFIG` diagnostics row.
  *Done when:* a run with the report capped is compared against one without, on
  RSS, on `am_low_memory`, and on whether #51 still reproduces. If it helps, this
  stops being a diagnostics row and becomes a container parameter, because every
  device will want a different number.

- [ ] **#53 — `0030` prints `took it at 0` again, and this time it is the frame
  walk.** A six-minute wait was reported six times: thread `0290` on the section
  at `re9.exe +0xEAA17D0`, held by `028c`, with both the site and the caller
  zero. #49 records that the *first* zero was a missing recording site in
  `RtlTryEnterCriticalSection`, fixed; the rewrite then walked the `x29` chain to
  find the first return address outside ntdll. This is the first time it has
  fired since, and zero is what that walk stores when it finds nothing.

  So the instrument has a blind spot exactly where it was rebuilt to see: an
  acquisition whose frames do not leave ntdll in a form the walk understands.
  *Done when:* the walk records *something* rather than zero — the entry thunk's
  own return address is worth more than nothing, and the EC context's guest RIP
  is worth more than that.

  **Answered 2026-08-15, and the diagnosis above is wrong.** It was not the frame
  walk. It was eviction: 256 slots, one entry each, and `crit_site_get_site`
  returning NULL on a key mismatch — so a section that had simply been displaced
  by another hashing to the same slot printed identically to one that was never
  recorded. The tell was in the same minute of the same session: one stuck
  section named its holder while another printed zero. `0030` now carries 1024
  slots, four-way probing, and `crit_section_forget_site` on release, and Wine
  revision 17's first run named a holder on the first try:

  ```
  section 0000006FFFF80E18 "/src/native/wine/dlls/ntdll/loader.c: loader_section"
    wait timed out in thread 0160, blocked by 0154 which took it at 0000006FFFED20B8
  ```

  ntdll loaded at `0x6FFFE30000` that run, so the site is `ntdll+0xA20B8`, and
  **the resolution checks itself** — two instructions earlier the same function
  computes the very section the message names:

  ```
  1800a20ac:  add  x21, x21, #0xe18   ; 0x180150e18 -> runtime 0x6FFFF80E18
  1800a20b0:  mov  x0, x21
  1800a20b4:  bl   RtlEnterCriticalSection
  1800a20b8:                          ; <- recorded site: loader_init +0x54
  ```

  `called from` is still zero, which is the frame walk and is the residue of this
  entry rather than its subject. **Not closed on the strength of one line**: that
  session ran under `+virtual` at roughly a hundred times normal cost, so the
  60-second timeout it fired on says nothing about whether that hold was a real
  stall. What is established is that the instrument works. *Done when:* a
  timeout naming a holder is seen in a session that is not being traced.

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
