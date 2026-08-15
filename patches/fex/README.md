# FEX patches

Applied on top of the pinned `FEX_REF`, in filename order, by `build/fex.sh` —
`apply_patches` is keyed on the source name (`fex`). A patch that does not apply
is a hard error; see `apply_patches` in `build/common.sh`.

**Upstream's contribution policy.** `native/fex/CLAUDE.md` says "AI must not be
used to generate code for contributions to this project." Nothing here has been
sent upstream, and nothing here should be sent upstream as written — if one of
these is worth contributing, it needs writing again by a person. The patches are
Vessel's, applied to Vessel's build.

## 0001-offlinecompiler-initialise-config-before-logging.patch

`FEXOfflineCompiler64.exe` faults on its **first statement**, for every
subcommand, on Windows. Measured on the device 2026-08-10, invoked by hand
against an existing cache key:

```
D 120 Load module FEXOfflineCompiler64.exe (…): 140000000
D 120 Exception: Code: C0000005 Address: 1400439E8
wine: Unhandled page fault on read access to 0000000000000018
      at address 00000001400439E8 (thread 0120)
EXIT=5
```

Only `ntdll`, `kernel32`, `kernelbase` and `ucrtbase` are loaded when it dies,
and no directory is created — `ProcessAll()` creates `cache/` and
`codemap/ready/` as its first two acts, so neither existing means it never
started.

The chain, read out of the source rather than inferred from the address:

| Where | What |
|---|---|
| `Source/Tools/FEXOfflineCompiler/Main.cpp:850` | `main()`'s first statement is `FEX::Windows::Logging::Init()` |
| `Source/Windows/Common/Logging.cpp:37` | which opens with `FEX_CONFIG_OPT(SilentLog, SILENTLOG)` |
| `FEXCore/include/FEXCore/Config/Config.h:293` | `Getter` constructs **eagerly** — the ctor reads the value |
| `FEXCore/Source/Interface/Config/Config.cpp:451` | → `Value<bool>::GetIfExists` → `GetConv<bool>` |
| `FEXCore/Source/Interface/Config/Config.cpp:432` | → `Meta->GetConv<T>(Option)` |
| `FEXCore/Source/Interface/Config/Config.cpp:116` | `static MetaLayer* Meta {}` — **null** until `Initialize()` assigns it at `:239` |

A member read through a null `this` at a small offset is exactly `C0000005`
reading `0x18`.

`Initialize()` is reached only through `FEX::Config::LoadConfig()`, which
`GenerateCache` calls at `Main.cpp:615` — *after* the dispatch — and which
`ProcessAll` never calls at all. So the ordering is wrong for both subcommands
and the tool has never run on Windows. That it went unnoticed is consistent with
the feature living behind `FEX_ENABLECODECACHINGWIP`.

The fix is one call, in the `#else` arm that is already Windows-only.

**Why a bare `Initialize()` and not hoisting `LoadConfig`.** `LoadConfig` wants
a program name, an `envp` and a `PortableInformation`, none of which `main` has
resolved yet, and it is the right call in `GenerateCache` where it already is.
`Initialize()` on its own creates the `LAYER_TOP` `MetaLayer` and nothing else,
so `SilentLog()` reads its default — the same answer a loaded config with no
`SILENTLOG` in it would give.

**It is safe to call twice.** `GenerateCache` still calls `LoadConfig`, which
calls `Initialize()` again. `AddLayer` is a `map::emplace` on `LAYER_TOP`, which
does nothing when the key is present, and the following
`Meta = ConfigLayers.begin()->second.get()` still finds that same `MetaLayer`
because it is the only entry at that moment. `Meta` ends up where it should.

**What this does not fix.** Nothing about *where* the cache lives:
`FEX::Config::GetCacheDirectory()` reads `getenv("FEX_APP_CACHE_LOCATION")`
directly (`Source/Common/Config.cpp:710`), no config layer involved, so Vessel's
DOS path arrives regardless. And it does not fix
`ImageTracker::LoadAOTImages`, which composes `\??\` + the cache directory by
string concatenation and hands it to `NtOpenFile` — that one is worked around
from Vessel's side by handing FEX a DOS path in the first place. See
`docs/TODO.md`.

## 0003-logging-a-level-ceiling-driven-by-vessel-trace.patch

**FEX has two logging states and no level, and that is why Vessel ships it
silent.**

`Source/Windows/Common/Logging.cpp` is the entire Windows logging init.
`MsgHandler` never compares `Level` against anything — it uses it only to pick a
prefix character — and `FEX_SILENTLOG` decides, before `InstallHandler` runs,
whether any handler exists at all. There is no level option to reach for: the
`Logging` group of `FEXCore/Source/Interface/Config/Config.json.in:386-401` is
`SilentLog`, `OutputLog`, `TelemetryDirectory` and `ProfileStats`, and nothing in
the tree exposes a channel or a verbosity. `MSG_LEVEL` is
`constexpr DebugLevels MSG_LEVEL = INFO` in
`FEXCore/Include/FEXCore/Utils/LogManager.h:41`, the numeric maximum, so every
`DFmt` guard is statically false and every one of them is compiled in.

What that costs, measured on the device 2026-08-13: one Resident Evil Requiem
session of about six minutes at `FEX_SILENTLOG=0` produced **49 MB and ~508,000
lines**, of which 99.9% were a single pair per unaligned atomic —

```
TF Exception: Code: 80000002 Address: <pc>
TF Handled unaligned atomic: new pc: <pc>
```

`LogMan::Msg::DFmt` at `Source/Windows/ARM64EC/Module.cpp:716`. Every other
source in that session came to roughly 350 lines. So "let FEX speak" and "read
anything else in this log" were mutually exclusive.

**Silence hides the things worth hearing.** FEX's host-feature parser reports an
unrecognised token with `EFmt` and nothing else
(`Source/Common/HostFeatures.cpp:149`, `:180`), so with the shipped default a
typo in `FEX_HOSTFEATURES` is completely invisible — the same shape of silent
no-op `docs/LOGGING.md` catalogues for `WINEDEBUG`.

The patch adds a ceiling to `MsgHandler`, defaulting to `ERROR`, read once in
`Init()` from the `x86` topic of `VESSEL_TRACE`:

| `VESSEL_TRACE` | ceiling | what survives |
|---|---|---|
| unset, or `x86:errors` / `x86:warnings` | `ERROR` | asserts, errors, host-feature complaints |
| `x86:stubs` | `DEBUG` | the above plus the unaligned-atomic tier |
| `x86:everything` | `INFO` | everything, i.e. today's `FEX_SILENTLOG=0` |
| `x86:off` | `ASSERT` | assertions only |

Three notes on the shape:

- **The filter is before the format, not after.** The cost being removed is
  508,000 `fextl::fmt::format` calls, and a filter that formatted first would
  drop the output while keeping most of the expense.
- **`FEX_SILENTLOG` still means silence** and is unchanged. This adds a middle
  where there was none; it does not replace the switch.
- **`VESSEL_TRACE` is read here rather than translated by the app** because
  there is no FEX variable to translate it into. That is one of the two reasons
  Vessel passes the raw spec string through to the guest; see `docs/TRACING.md`.

`windef.h` and friends define `ERROR` (and sometimes `DEBUG`) as object-like
macros in some header sets, which would turn `LogMan::DebugLevels::ERROR` into a
syntax error rather than into anything diagnosable. The patch `#undef`s both
after the Windows includes; nothing else in the file wants either name.

**Not compiled.** Generated against the pinned tree and verified with
`git apply --check`; no FEX build has been run against it.

**Policy, again.** Not for upstream, for the reason stated at the top of this
file — and pointedly so for this one, since the honest version of this change is
a real config option in `Config.json.in` rather than a Vessel environment
variable read from a Windows-only file.

## `0004-arm64ec-a-notification-hook-must-not-block-on-the-way-in`

The Resident Evil Requiem deadlock, fixed at the lock rather than at the
trigger. The commit message carries the full derivation; the short version is
that `ThreadCreationMutex` is held across the memory and read syscalls, because
Wine calls those notifications as a before/after pair, and that leaves FEX
sitting on both sides of a lock order it does not own:

```
0188  heap section held  ->  NtAllocateVirtualMemory  ->  wants ThreadCreationMutex
0280  ThreadCreationMutex held  ->  Wine's own syscall allocates  ->  wants heap section
```

- **The unnamed section was identified by arithmetic, not by guessing.**
  `libarm64ecfex.dll` is mapped at `0x6FFDCB0000` and is 5,148,672 bytes, so the
  waited-on `0x6FFE04D498` is `0x39D498` into its data. `std::recursive_mutex`
  is what `RTL_CRITICAL_SECTION` backs, which is what `RtlpWaitForCriticalSection`
  waits on, and `ThreadCreationMutex` is the only such object on those paths.
- **`try_lock` is the whole fix.** A cycle needs both edges; this removes the one
  Wine forces from inside a syscall. Nothing is skipped when the try fails — the
  notification still runs, and the interval map stays consistent because
  `InvalidationTracker` locks `IntervalsLock` around every mutation of it.
- **What is actually given up** is coarse serialisation against thread creation,
  narrowing to a race the two halves of the pair already allowed. That is a real
  cost and it is why this is a mitigation rather than a redesign: the honest fix
  is for the notification hooks not to share a mutex with thread bookkeeping.
- **Per-level tracking, not one flag.** The pairs nest, so a failed try at one
  level must not release a mutex a different level acquired.
- **Deliberately not touched.** `BTCpu64NotifyReadFile` also holds
  `CTX->GetCodeInvalidationMutex()` across the read, and `NotifyMapViewOfSection`
  still blocks on a `scoped_lock`. Neither was in the measured cycle, and neither
  can be made non-blocking without dropping work that has to happen.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file.

## `0007-logging-a-tool-that-cannot-speak-cannot-be-debugged`

`FEXOfflineCompiler64.exe generate` prints `Compiling code...` and then never
returns — 180 s, no file in `cache/`, killed by the timeout, and **not one
diagnostic line**. `generateCodeCache`'s own note in `SessionRuntime.kt` records
the same shape from `process-all`: "three and a half minutes of full-CPU work,
an empty `cache/` directory, and no diagnostic". The same tool produced 25 cache
files in 4897 ms on 2026-08-11.

An earlier draft of this slot guessed at the hang itself. **This one does not.**
It fixes the reason the guess could not be checked: the tool has never been able
to emit a single `LogMan` message, on any entry point, under any environment.
Until that is untrue, every hypothesis about the hang costs a rebuild to test
and returns the same empty log.

**Half one: `Logging::Init()` reads a config that is not there yet.**

| Where | What |
|---|---|
| `Source/Windows/Common/Logging.cpp:140` | `FEX_CONFIG_OPT(SilentLog, SILENTLOG)`, then `if (SilentLog()) return;` |
| `FEXCore/Source/Interface/Config/Config.json.in:387-389` | `SilentLog` default is **`true`** |
| `Source/Tools/FEXOfflineCompiler/Main.cpp:856-857` | the tool calls `Config::Initialize()` (patch `0001`) then `Init()` — `Initialize()` creates the meta layer and loads nothing into it |
| `Source/Tools/FEXOfflineCompiler/Main.cpp:615` | `FEX::Config::LoadConfig`, the first thing that loads a layer, runs *after* — and only on the `generate` path |
| `Source/Windows/ARM64EC/Module.cpp:657`, `WOW64/Module.cpp:519` | the two modules have the same ordering from `ProcessInit` |

So `SilentLog()` can only ever return the compiled-in `true`, `Init()` returns
without installing a handler, and nothing calls `Init()` again — every `EFmt`,
every `IFmt`, and the message half of every `ERROR_AND_DIE_FMT` is discarded for
the life of the process. `FEX_SILENTLOG=0`, which `SessionRuntime.kt` sets for
this process specifically and documents at length, has never done anything.

**How that was established rather than assumed.** Run by hand under Wine, no
guest, nothing else in the prefix, `FEX_SILENTLOG=0` in the environment: the two
lines that appeared were `Parsed 26 codemap entries for …` and `Compiling
code...`, which are `fmt::print` (`Main.cpp:596`, `:519`). The two `LogMan` lines
the same twenty lines of code emit — `Relocated image {:X} -> {:X}` and `Mapped
image: {} @ {:X}` (`Main.cpp:284`, `:342`) — did not appear. Two prints present
and two logs absent from the same code path is the proof; nothing about the hang
is claimed by it.

The fix reads `FEX_SILENTLOG` from the environment directly, exactly as `0003`
already reads `VESSEL_TRACE` two functions above, and lets it win over a config
value that at that moment is only a default. The conversion is FEX's own —
`strtoull(Value, nullptr, 0)` cast to `bool`
(`FEXCore/Source/Common/StringConv.h:11-18`) — so the two cannot disagree, which
also means **`FEX_SILENTLOG=true` parses as zero and asks for *not* silent**.
It is hand-rolled rather than a call to `strtoull` because this file links into
`libarm64ecfex.dll` against FEX's own minimal CRT, which has `getenv`
(`CRT/Misc.cpp:58`) and no string-to-integer conversion at all (`CRT/String.cpp`).

**Half two: `GenerateCache` installs an empty environment layer.**
`Main.cpp:613` built the layer from `char* envp[] = {nullptr}`, and
`EnvLoader::Load` iterates precisely the array it is handed
(`Source/Common/Config.cpp:307`). So no `FEX_*` variable of any kind reached
cache generation — not overridden, not warned about, never read. That is
`FEX_MAXINST` and `FEX_MULTIBLOCK`, which decide how much of a block the
compiler may see; `FEX_SMCCHECKS` and `FEX_DISABLEDEP`, which decide what it
treats as code; and `FEX_HOSTFEATURES`, which decides what it compiles for. A
cache is only useful if it holds the code the session would have generated, and
this was generating it from a different configuration. `envp` now comes down
from `main()`, matching `FEXGetConfig/Main.cpp:416` and fixing the Linux build of
the same shared function; the modules use `_environ` because a DLL has no
`main()` to take it from.

**What this does not do.** It does not fix the hang, and it is not a guess about
the hang. It makes the next run of the tool able to report where it is.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## `0008-overcommit-a-fault-that-was-not-repaired-must-not-be-resumed`

`0007` was written so the next run could report where it was. It did, and this
is the answer.

**The measurement, from a device session on 2026-08-14.**
`fexofflinecompiler32.exe`: 33 modules parsed, 34 caches populated, zero
failures. `fexofflinecompiler64.exe` on
`\??\d:\games\resident evil requiem\amd_fidelityfx_loader_dx12.dll`
(digest `0425782af0c68fb4`): 0 parsed, 0 populated, and **155,039 log lines that
are all the same line** — `seh:RtlInitializeExtendedContext2 context
000000000011E630` — with the identical context address every time, and
**exactly one `dispatch_exception`** in the whole log. Same context pointer
means the same stack frame, so it is one fault being prepared, "repaired",
resumed and re-faulted, not a recursion. The same module and digest reproduces
under a real session with full permissions, so it is not the `run-as`/`D:`
permission artifact the harness warns about.

**Where the loop closes, read out of Wine rather than guessed.**
`prepare_exception_arm64ec` (`dlls/ntdll/signal_arm64ec.c:1343-1366`) calls
`RtlInitializeExtendedContext`, then `pResetToConsistentState`, and only then
returns to `KiUserExceptionDispatcher`, which calls `dispatch_exception` — where
vectored handlers run. 155,039 context preparations against one
`dispatch_exception` therefore proves the fault was resumed **before** vectored
dispatch, inside the emulation module's `ResetToConsistentState`
(`ARM64EC/Module.cpp:818`), and that the offline compiler's own vectored handler
was never reached at all.

That also explains the 32-vs-64 split exactly: `FEXOfflineCompiler32.exe` is a
plain ARM64 PE, so Wine loads no EC emulator for it, `pResetToConsistentState`
does not exist, and its faults go straight to `dispatch_exception` and the
tool's own handler. `FEXOfflineCompiler64.exe` is an ARM64EC image and is
intercepted first. Every game module is 64-bit, which is the `0 of 49
binary(ies)` history.

**The defect.** `OvercommitTracker::HandleAccessViolation` reported whether the
address was *recognised*, not whether the fault was *repaired* — returning
`true` on the interval query alone, having discarded `NtQueryVirtualMemory`'s
`NTSTATUS`, read an uninitialised `MEMORY_BASIC_INFORMATION`, and discarded
`VirtualAlloc`'s return. Both callers turn `true` into "resume the faulting
instruction". A commit that did not happen therefore answered "fixed" forever.

What changed: `Info` is initialised and the query's status *and* returned length
are checked; `MEM_RESERVE` is required, so an already-committed page that still
faults declines instead of spinning; the commit covers
`Info.BaseAddress`/`Info.RegionSize` — the uncommitted run containing the fault
— rather than `AllocationBase` up to the end of that run; and `VirtualAlloc`'s
result is returned. The non-Wine branch gets the same treatment. Every failure
path names itself at `ERROR`, which `0003`'s default ceiling passes and `0007`
now makes reachable.

**The bound is kept even though the `MEM_RESERVE` check already limits this to
two attempts**, because the property worth having is not "this repair was
fixed" but "a repair that does not take cannot spin", and that has to hold for
whatever the next reason turns out to be. It is safe *in this function*
specifically: a committed page does not fault again for want of committing, so
a repeat is always a failure — unlike RWX/SMC faults, which legitimately repeat
on one page and which `0006` counts.

**Two additions that change no behaviour.** `HandleRWXAccessViolation` also
discarded its `NtProtectVirtualMemory` status and can answer "handled" without
having changed anything (a reprotect fails on a reserved page); it now says so
at `ERROR`, rate-limited, but still returns what it returned — a breaker there
would risk the hot SMC path to fix a branch not yet shown to be looping. And
`ResetToConsistentState` now names which of its resume paths keeps returning to
the same page, so the next run identifies the branch instead of needing another
round of inference.

**What is not claimed.** Nothing here explains *why* a commit would fail, if it
does. It makes the failure survivable and nameable, which is the next fact
needed.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## `0009-opcodedispatcher-mov-to-fs-gs-selector-in-64bit-mode`

`mov Sreg, r/m16` for FS and GS in 64-bit mode set `DecodeFailure = true` and
logged "We don't support modifying GS/FS selector in 64bit mode!".

**A decode failure is not a skipped instruction — it is a block that can never
run.** `Core.cpp:724-728`: a dispatch error with `TotalInstructions == 0`
returns `{std::nullopt, 0, 0, 0, 0}`, so no code is produced for the block at
all. `Core.cpp:730-735`: if instructions were decoded first, the block is ended
early at the failing RIP, which makes the next entry there a first-instruction
failure and lands in the first case. Either way that RIP is permanently
unexecutable by any path. Resident Evil Requiem hits four of these on GS and one
on FS immediately after its Denuvo blob loads, then dies with a guest stack
overflow inside ARM64EC exception delivery.

**Selector only, base untouched**, which is what the function's own existing
comment says the hardware does: "AMD documentation is /wrong/ in this regard …
the instructions will /actually/ load 16bits in to the selector portion of the
register! Tested on a Zen+ CPU, the selector is the portion that is modified! …
The loads here also load the selector, NOT the base." In 64-bit mode the FS/GS
bases live in `IA32_FS_BASE`/`IA32_GS_BASE`, reachable only through
WRFSBASE/WRGSBASE or MSR writes; user mode cannot install a descriptor, only
load a selector the OS already put there; and on Windows the GS base holds the
TEB.

**`UpdatePrefixFromSegment` is deliberately not called, and that is a departure
from the 32-bit arm rather than an oversight.** That function walks the GDT for
the selector and writes the descriptor's 32-bit base into `gs_cached`/
`fs_cached`. In 64-bit mode that would replace the real 64-bit base — the TEB
pointer — with a value derived from a descriptor table the guest never set up.
Calling it is exactly what would break every subsequent GS-relative access.

**Checked before writing, because it decides whether the approach is sound at
all:** 64-bit segment-prefixed addressing derives from the base and never from
the selector index. `GetSegment` (`OpcodeDispatcher.cpp:4052-4090`) — the one
place a prefix becomes an address component — returns `fs_cached`/`gs_cached` in
its `Is64BitMode` branch and reads no `*_idx`; the interpreted path does the
same. Across FEXCore and Source the only readers of `fs_idx`/`gs_idx` are this
function's read side, the segment-register-as-operand path, and the Linux signal
frame code. Storing `gs_idx` cannot perturb any address computation.

**The bet, stated as one.** That no guest reads the base back expecting a
selector load to have changed it. True of the hardware being modelled and of
every Windows user-mode idiom, but an assumption, not a proof. What is *not* a
bet is the alternative: today the block cannot execute at all.

**Known asymmetry, left alone deliberately.** The read side (`mov r/m16, Sreg`)
still returns a constant `0` for FS/GS in 64-bit mode, so a guest that writes a
selector and reads it back sees `0`. Fixing that changes what every existing
guest observes from `mov r, gs`, which is a wider behaviour change than the one
being made here, and the write side alone is what unblocks the block. It is the
obvious follow-up if a save/restore idiom turns out to care.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Watched on the device 2026-08-14.** The five lines went to zero and stayed
there. It did **not** fix the crash it was found next to — Requiem died shortly
afterwards at `LoadLibraryExW +0x6` — which is what `0010` addresses, and which
only became reachable because this patch stopped those blocks failing to decode.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## `0010-opcodedispatcher-push-and-pop-of-a-segment-must-not-touch-the-base`

`push`/`pop` of a segment register touched the segment **base** in 64-bit mode,
and on Windows that base is the TEB pointer.

`POPSegmentOp` (`OpcodeDispatcher.cpp:463-495`) stored the selector correctly and
then called `UpdatePrefixFromSegment` **unguarded**. That helper walks the GDT for
the selector and stores the descriptor's **32-bit** base into `<seg>_cached`
(`:4262-4282`) — and `gs_cached` is a `uint64_t` holding the TEB
(`Source/Windows/ARM64EC/Module.cpp`: `State.gs_cached = TEB`). So one `pop gs`
in a 64-bit guest overwrote the low half of the TEB pointer with a base derived
from a table that has no meaning in 64-bit mode, where the base comes from the
FS/GS MSRs. Every `gs:[…]` access afterwards read from a corrupted base.

`PUSHSegmentOp` (`:373-428`) was the mirror image: its 64-bit arm loaded
`<seg>_cached`, the base, where the hardware pushes a 16-bit selector. Together
the ordinary save/restore idiom was destructive rather than merely wrong — it
pushed half a TEB pointer and then fed it back through a GDT walk.

**`MOVSegOp` already guards the same call**, which is what makes the omission
visible once the three are read together. Upstream's own FEX-2302 notes say of
segment push/pop that *"no one really uses that feature on 64-bit CPUs today"* —
anti-tamper code does, and Requiem only reaches these instructions because `0009`
stopped the blocks containing them failing to decode.

The fix guards `UpdatePrefixFromSegment` on `!Is64BitMode` and loads `<seg>_idx`
on both arms of the push. The selector store stays in both modes: that part is
what the instruction actually does.

**Watched on the device 2026-08-14.** The `LoadLibraryExW +0x6` fault it was
written for did not recur, and Requiem now reaches Vulkan, a swapchain and PSO
compilation on every run.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## WITHDRAWN — `invalidationtracker-a-reservation-is-not-code-and-a-failed-reprotect-is-not-a-repair`

*Shipped as `0010`, then reverted and removed from the tree. The number `0010`
now belongs to a different patch. Kept because the misclassification it aimed at
is real and the reason it was wrong is not obvious — read this and the withdrawn
entry below it before attempting the same fix again.*


`0008` fixed the overcommit branch and left a diagnostic on the SMC branch,
because that branch had not been *shown* to loop. On the next device run it was
— a Metro 2033 session on FEX 260806:

```
RWX reprotect FAILED C000002D on page 7E5E616000 (fault 7E5E616608,
  pc 140088F10, occurrence 13864960); the fault is being resumed unrepaired
Fault on page 7E5E616000 resumed 13864960 times running by
  ResetToConsistentStateImpl
```

13,864,960 resumes, same page, same fault address, same pc, and **zero
`Overcommit:` lines in the whole session** — so `0008` was correct hardening and,
as it said it might be, not the cure.

**The status code is the diagnosis, and it was initially decoded wrongly.**
`C000002D` is `STATUS_NOT_COMMITTED` (`include/ntstatus.h:262`), not
`STATUS_CONFLICTING_ADDRESSES`, which is `C0000018` (`:241`). Wine's
`NtProtectVirtualMemory` has exactly three outcomes
(`dlls/ntdll/unix/virtual.c`): no view → `STATUS_INVALID_PARAMETER`; view found
but `get_committed_size()` short → `STATUS_NOT_COMMITTED`; else success. So the
view exists and the page is **reserved and not committed**. Nothing crosses a
boundary and no range is miscomputed — the reprotect is a single page,
`TmpSize = 1` rounded up, inside a view that exists. There was no range bug to
fix.

**What the page is.** `pc 140088F10` is inside the compiler's own image, which
loads at `140000000`. The faulting range is `FEXOfflineCompiler64.exe`'s own
`LookupCache`: reserved with `Commit = false` (`LookupCache.cpp:41`) and handed
to `MarkOvercommitRange` (`:49`) — i.e. the region whose whole design is to
fault on first touch and be committed by `OvercommitTracker`. The zero
`Overcommit:` lines corroborate it: the emulation module's tracker is empty in a
process with no guest thread, and the tracker that owns this range is the
compiler's own, downstream of this handler.

**The defect is classification, not the ignored failure.**
`HandleMemoryProtectionNotification` classified on `Prot` alone, and
`NotifyMemoryAlloc` dropped the allocation `Type`. With `DisableDEP` on
(`0002`, default true) every readable range counts as executable and every
writable one as SMC-capable, so a 272 MB `MEM_RESERVE` of `PAGE_READWRITE` was
recorded as guest RWX code. `HandleRWXAccessViolation` then claimed the fault
first, could not reprotect an uncommitted page, reported "handled", and resumed.
**DEP promotion was stealing faults from the mechanism designed to receive
them.**

The two sweeps in the same file already get this right — the constructor and
`HandleProcessExecuteFlagsChange` both test `Info.State == MEM_COMMIT` before
promoting. Only the notification path did not. `Type` now comes through from
both modules' notify hooks, so all three paths agree, and the reservation never
enters the interval map: the fault is not claimed here at all and falls through
to the handler that owns the memory.

**The decline is unconditional on failure — not status-specific, not bounded —
and the three options are not really distinct.** The only thing this function
does to let the faulting instruction progress is the reprotect. If it failed the
page is still not writable, so the same store faults again the instant it
resumes, by construction, for any status and any number of retries. Bounding
would make the loop finite; conditioning on one status would leave the next one
looping.

**What it cannot regress, and what it can.** SMC faults repeating on one page
stay normal — this fires only when the reprotect *failed*, and on the working
path it succeeds. No currently-working guest can depend on the old behaviour,
because the old behaviour on this branch is an infinite loop: "working" and
"reached this line" are mutually exclusive. What does change is the failure's
shape — a hang becomes an access violation with an address. The one case I can
construct where that is a real loss is a genuine SMC page transiently
unprotectable through a race with another thread's decommit, which used to spin
until the other thread finished and will now crash. Accepted, because a spin is
not a recovery.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.


## WITHDRAWN — `invalidationtracker-take-back-the-mem-commit-gate-that-killed-a-guest`

*The revert of the entry above. Both were removed from the tree together, so
neither ships; `0011` now belongs to the code-cache validation patch below. The
A/B measurement in this section is the reason the gate must not be reattempted.*


**`0010` was half right and the wrong half shipped.** This takes back its
classification change and keeps its decline. Written as a revert-on-top rather
than an edit to `0010` so the series records what was tried and why it failed.

**The A/B, same game, one component version apart, nothing else changed:**

| | Metro 2033 game process | `RWX reprotect FAILED` |
|---|---|---|
| 260806 (`0008`+`0009`) | runs; **zero** `running the stack out` | 3,386 (in the compiler) |
| 260807 (+`0010`) | **dies** — 27 `running the stack out`, then `virtual_setup_exception stack overflow 3616 bytes … (0x20000-0x21000-0x420000)` | **zero anywhere** |

Zero reprotect failures on 260807 is the tell. The handler did not start
failing; it stopped being asked. The faulting exception, from Wine patch
`0040`'s telemetry:

```
[metro.exe] prepare_exception_arm64ec … code c0000005 flags 0
            at 0000006FFC4311A2, info 1 6ffc2e5a3c
```

`info[0]=1` is a write — the C runtime storing into a page that had been
write-protected for SMC detection. On 260806 `HandleRWXAccessViolation`
unprotected it **successfully**, which is exactly why the game never logged a
failure there. With `0010`'s gate the page was no longer tracked, nothing
claimed the fault, nothing unprotected it, and the identical store re-faulted
until a 4 MB guest stack was gone at ~7000 bytes per delivery.

**Where the argument for the gate was wrong**, written out because the
misclassification it aimed at is real and someone will try this again. It asked
only whether a reserve notification could *remove* tracking — it cannot,
`MEM_RESERVE` only succeeds over free address space — and never asked whether
reserve notifications were *adding* tracking that mattered once the range was
committed. They were. The assumption underneath, "when something commits them
`NtAllocateVirtualMemory` notifies again with `MEM_COMMIT` over the same range",
is not something this code can rely on: commits notify their own sub-range
rather than the reservation, and Wine defers and replays some of these
notifications (`dlls/ntdll/signal_arm64ec.c:989`), so neither coverage nor
ordering is the 1:1 the gate assumed. This was named as the risk in `0010`'s own
report — "SMC tracking would be lost … wrong behaviour rather than a crash,
which is harder to spot" — and the consequence turned out to be worse than
predicted: an unrepairable write fault, not stale code.

**What is kept.** The failed-reprotect decline in `HandleRWXAccessViolation` is
untouched. It did not cause this — on the working path the reprotect succeeds
and the decline never runs — and it is what converts the compiler's
13,864,960-iteration spin into a locatable fault.

**What is still broken, and deliberately not fixed here.** The compiler's own
272 MB `LookupCache` reservation is still classified as guest RWX code, so
`FEXOfflineCompiler64.exe` will still fail — now by declining and reporting
rather than by spinning. See the next section for the shape a correct fix needs.

### The exclusion that would actually work, not implemented

The distinction is not reserved-versus-committed. It is **FEX's own bookkeeping
memory versus the guest's**, and `HandleMemoryProtectionNotification` cannot see
it: by the time a notification arrives, an allocation FEXCore made for itself
and one a guest made are indistinguishable.

The range is already known to FEX — `LookupCache.cpp:49` hands it to
`MarkOvercommitRange`, and no guest allocation ever reaches that call. So the
natural exclusion is "never classify a range the OvercommitTracker owns", with
`MarkRange` also removing the range from `XIntervals`/`RWXIntervals` (the
allocation is notified at `LookupCache.cpp:41`, *before* it is marked at `:49`,
so insertion has already happened and must be undone rather than prevented).

**The obstacle, stated so nobody starts before reading it:** in the offline
compiler the two live in different modules. `MarkOvercommitRange` goes to the
*tool's* tracker (`FEXOfflineCompiler/Main.cpp:131`) while the notification is
delivered to the *emulation module's* `InvalidationTracker`, and there is no
channel between them today. In a normal session both are the module's own and
the fix is local; for the tool it needs one. The two candidate shapes are a
FEXCore-side registry of emulator-private ranges that the module's notify hook
consults — `FEXCore::Allocator::VirtualName` already tags this exact allocation
`"FEXMem_Lookup"` and would be the natural place to record it — or reserving
FEX-internal regions `PAGE_NOACCESS` so `ProtIsReadable` is false and DEP
promotion never picks them up, which is a one-line change but only covers the
reserve step and still misclassifies each run once it is committed.

Neither belongs in a revert. Both need their own measurement.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## `0011-codecache-a-cache-that-does-not-validate-must-not-be-loaded`

`0007` made generation diagnosable and generation now works — 52 modules, 51 s,
72 entries. This is the other half: **loading** killed Resident Evil Requiem
before the guest executed an instruction.

```
Loaded L"D:\Games\Resident Evil Requiem\re9.exe" at 0000000140000000: native
Loaded L"C:\windows\system32\libarm64ecfex.dll" at 0000006FFC760000: builtin
EW [re9.exe] seh:NtRaiseException Unhandled exception code c0000005 at 0x6ffc7fbb04
```

Two modules, then dead. Moving `cache/` aside made the identical launch load 67
modules and reach Vulkan — single variable, clean result.

**The site, derived from the instruction rather than from a symbol.** `+0x9bb04`
against the shipped `libarm64ecfex.dll` (ImageBase `0x180000000`, so VMA
`0x18009bb04`) disassembles to:

```
18009bafc: f940040c   ldr  x12, [x0, #0x8]     ; emitter cursor
18009bb00: 2a02014a   orr  w10, w10, w2
18009bb04: b900018a   str  w10, [x12]          ; <-- c0000005
18009bb08: f940040a   ldr  x10, [x0, #0x8]
18009bb0c: 9100114a   add  x10, x10, #0x4      ; advance one instruction
```

That is the `movz`/`movk` emit loop inside `Arm64Emitter::LoadConstant`, storing
through the emitter's cursor — so the nearest-symbol guess was right, and the
`+0x654` offset lands inside it. On the cache-load path `LoadConstant` is
reached only from `ApplyCodeRelocations`, via `RELOC_GUEST_RIP_MOVE` or
`RELOC_NAMED_THUNK_MOVE`. The cursor comes from `Reloc.Header.Offset`, and the
only two things bounding it were `LOGMAN_THROW_A_FMT`, which compile to nothing
in this Release build. A relocation offset outside the code buffer was therefore
a wild write rather than a refusal.

**Why `re9.exe` and not `metro.exe`, which loaded from cache in the same session
without incident.** It is size, not content. When generation exhausts the 128 MB
code buffer, `JIT.cpp:1094-1096` calls `ClearCodeCache`, whose `NewCodeBuffer`
parameter **defaults to `true`** (`Context.h:220`), so a fresh buffer is
installed and `LatestOffset` restarts at zero. Relocations are deliberately not
cleared while generating (`Core.cpp:913-915`), so every relocation recorded
before that point keeps an offset into the old buffer, and `SaveData` writes all
of them against a `header.CodeBufferSize` computed from the *new* `LatestOffset`.
The table ends up both out of range and out of order — and `LoadCache`'s page
partitioning uses `std::upper_bound`, which silently mispartitions unsorted
input. A Denuvo-protected AAA main executable is large enough to reach that;
Metro's is not.

**That last paragraph is inference, not observation** — the corpus was cleared
with the container, so the offending file no longer exists. It is why every
rejection below names the invariant and its numbers: one Metro run regenerates a
cache and the log will say which check fires, or that none does.

**The runtime-decryption theory is a real problem and is not this one.** It was
suggested that Requiem decrypting its own code makes a cache describe bytes that
are no longer there. That would be a genuine validity failure — but it cannot
produce *this* crash, because **nothing on the load path reads the guest image**.
`LoadCache`, `EnableLoadedSection`, `FinalizeCodePages` and
`ApplyCodeRelocations` touch only the cache file and the code buffer, so
mismatched image content has nothing to fault on there; it would surface later,
as wrong execution, with the loader long since finished — not with two modules
loaded.

**Where the fix goes.** `LoadCache` is the only place a cache can be refused for
free: it already returns `nullptr` on several paths, its one caller
(`ImageTracker::HandleImageMap`) simply skips `EnableLoadedSection` when it does,
and nothing has been mutated yet. By the time `EnableLoadedSection` has run there
is no way back — block mappings are inserted into the `LookupCache` *before*
`FinalizeCodePages` relocates the code, with no rollback. So validation happens
there, and checks every structure the loader will later walk with a bare cursor:

- the file is large enough for a header, and `CodeBufferSize` is non-zero and page aligned;
- the block-list walk stays inside the mapped view (it previously had no bound at all);
- each block's `HostCode` is inside the code buffer — `EnableLoadedSection` turns it into `&Code.CodeBuffer[HostCode]` and stores that as executable host code;
- the relocation table fits the file, every offset admits `MaxRelocationSize`, and offsets are non-decreasing (the `upper_bound` partitioning requires it);
- the code region and the code-page list both fit the file.

`ApplyCodeRelocations` additionally refuses to write outside its buffer — a hard
refusal rather than a skip, because a dropped relocation leaves a wrong constant
in code that will still execute, which is worse than not using the cache. With
validation in front of it that guard should be unreachable; it is what makes
"should" not matter.

**What this deliberately does not do.** It does not repair a bad cache and it
does not fix generation. A cache that fails validation is skipped and the title
runs cold, which is exactly what the `nullptr` return already meant — and
strictly better than today, because generation is where the 13,864,960-resume
loop lived and it is untouched here.

### Binding a cache to the bytes it was built from — not implemented

Worth recording because the decryption question will come back. **Nothing in the
format ties a cache to the image contents.** `CodeCacheHeader` carries `Magic`,
`FormatVersion`, `FEXVersion` (the FEX git hash), `NumBlocks`, `NumCodePages`,
`CodeBufferSize`, `NumRelocations` and `SerializedBaseAddress` — no content hash,
no source timestamp. The only identity is the filename's `FileId`, and
`ComputeCodeMapId` (`ImageTracker.cpp:38-41`) hashes the lowercased basename
XORed with `TimeDateStamp` and `SizeOfImage` from the PE header. For a packed
binary all three are constant across runs while the mapped bytes are not, so the
id cannot distinguish a decrypted image from a packed one.

Two shapes would fix it, both needing their own measurement. Add a digest of the
covered code ranges to the header, computed at save and re-checked at load —
correct but it costs a hash of every cached range on every image load. Or decline
to cache images FEX already knows rewrite themselves: the SMC/RWX machinery
tracks exactly that in `RWXIntervals`, so a cache could record "this image had
writable executable sections" and be refused on load. The second is cheaper and
strictly conservative; it would also disable caching for the titles that most
need it.

## `0012-invalidationtracker-fex-own-memory-is-not-guest-code`

The exclusion designed under the withdrawn `0011` and not written at the time.
Two halves, and **the second one is what fixes the reported bug** — that is
worth stating up front because the first is the one that was asked for.

**The measurement that scoped it.** A run that drained 52 modules with zero
`RWX reprotect FAILED` lines looked like generation was fixed. It had skipped
the offender: `Cache up to date: winmm.dll-115ee75136dc2892`. On a fresh
container winmm.dll is first in the queue and the loop returns —
`RWX reprotect FAILED C000002D on page 7E4EF44000 (fault 7E4EF44B08,
pc 140088F10, occurrence 5668864)` and climbing, 54 s of CPU with system time at
twice user. `SessionRuntime.kt`'s long-standing comment names the same module
independently. Reverting FEX to the 0001-0009 set did not stop it, so `0010`,
`0011` and `wine/0041` are all exonerated: the variable was never the build.

**winmm.dll is two blocks**, which kills the buffer-exhaustion story for *this*
loop — 128 MB is nowhere near exhausted. (That story may still be right about
re9.exe's relocation table; that is a different failure and is what `0011`
validates against.) What is left is the classification: with `DisableDEP` on
(`0002`, default true) the tool's own 272 MB `LookupCache` **reservation** is
recorded as guest RWX code, so the SMC handler claims a fault on a page that was
never committed, cannot reprotect it, reports it handled, and resumes forever.

**Half one — the exclusion.** `MarkPrivateRange` records the range and removes
it from `XIntervals`, `RWXIntervals` and `DEPPromotedIntervals`;
`HandleMemoryProtectionNotification` then declines to put it back. Wired into
`MarkOvercommitRange`/`UnmarkOvercommitRange` in both modules.

Ownership is the right distinction and reserved-versus-committed was not. The
withdrawn gate asked "is this committed?" and a real guest depended on
reserve-time classification. This asks "is this ours?", and the answer is exact:
`MarkOvercommitRange`'s only caller is `LookupCache.cpp:49`, on memory
`LookupCache.cpp:41` just reserved for FEXCore itself. **No guest allocation
ever reaches it**, so guest classification changes in exactly zero cases.
Removal rather than prevention, because the allocation is notified at `:41` and
marked at `:49` — by the time we are told it is private it has already been
classified.

**Half two — the decline, restored.** `HandleRWXAccessViolation` again returns
false when `NtProtectVirtualMemory` fails. This was written once, withdrawn
along with an unrelated change that shared its patch, and the loop came straight
back. It is unconditional on failure because the alternatives are not really
alternatives: the reprotect is the only thing that lets the faulting store
progress, so if it failed the store faults again the instant it resumes — for
any status, at any retry count. Bounding makes the loop finite; conditioning on
one status leaves the next one spinning.

**Why the exclusion alone is not enough, and why this is the half that matters.**
In a session the `LookupCache` and the `InvalidationTracker` belong to the same
module and the exclusion is exact and free. In `FEXOfflineCompiler` they do not:
`MarkOvercommitRange` goes to the tool's own `OvercommitTracker`
(`FEXOfflineCompiler/Main.cpp:131`) while the notification is delivered to the
emulation module's `InvalidationTracker` — two FEXCore instances in one process
with no channel between them. Nothing here crosses that boundary. The decline
does not need to: it hands the fault back to Wine's dispatcher, which reaches
the tool's own vectored handler and its own `OvercommitTracker` — the tracker
that actually owns the reservation — where `0008`'s hardened repair commits the
page.

**The two channels considered, and what each would have cost.** Exporting a
mark/unmark pair from `libarm64ecfex.dll` for the tool to resolve with
`GetProcAddress` would work, but ARM64EC export tables carry `EXP+#` aliasing
and fast-forward sequences, so a wrongly-resolved pointer is a plausible silent
failure that cannot be checked without a device — a poor trade for a case the
decline already covers. Reserving FEX-private regions `PAGE_NOACCESS` so
`ProtIsReadable()` is false is a one-line change, but it only hides the
*reserve* step: the moment `OvercommitTracker` commits a run as
`PAGE_READWRITE`, DEP promotion reclassifies it and the lookup table goes back
to being trapped and invalidated on every write. That converts a hang into a
permanent tax and leaves the classification wrong, which is the actual defect.

**Falsifiable on the next run:** winmm.dll compiles, `Successfully populated
cache .../winmm.dll-...` appears, and `RWX reprotect FAILED` stays at zero for
the whole session.

**Watched on the device 2026-08-14, and it passed on its own terms.** A real
session compiled **25 of 25** modules in 5,070 ms — `winmm.dll` among them — with
`RWX reprotect FAILED` at **zero**, `declining` at **zero**, and `Overcommit:` at
zero. The middle number is the one worth keeping: the exclusion did the work and
the failed-reprotect decline never fired, so the half that carried regression
risk was not exercised at all.

**Directly observed, not inferred.** With `VESSEL_TRACE=x86:everything` the
compiler's registered SMC intervals are 128 MB (the JIT code buffer, legitimately
RWX) and two of 16 KB and 4 KB. **The 272 MB `LookupCache` reservation is absent
from the list**, which is this patch working, seen from the guest's own logging
rather than deduced from an absence of failures.

**One caveat, recorded because it is easy to miss.** `SessionRuntime` also sets
`FEX_DISABLEDEP=0` for the compiler process, which independently stops DEP
promotion ever asking. Each was measured alone before both were kept — this patch
with DEP promotion on (16 modules, zero failures), and the environment variable on
FEX 260813 without this patch (21 modules, zero failures). They are belt and
braces, not one fix counted twice.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## `0013-writeprioritymutex-name-the-holder-of-a-wait-that-does-not-end`

**Diagnostic only. No path changes behaviour unless it is already stuck.** This
ships instead of a fix, deliberately.

**The state it is for.** Requiem now initialises fully — Denuvo, XeSS, Vulkan,
swapchain, PSOs, Wwise — and then deadlocks with all 78 threads sleeping.
`patches/wine/0030` names both critical sections and both holders, and both are
the game's own code; thread 0190 is asleep *inside* `RtlAllocateHeap` at `+0x6bc`
while holding the process heap section.

**One edge is confirmed, the other is refuted.**

*Heap → code mutex, confirmed by reading both halves.* `RtlAllocateHeap` takes
`heap_lock` and calls `heap_allocate_block` under it, and growth goes
`find_free_block` → `subheap_commit` (`heap.c:826`) or `create_subheap`
(`:998`, `:1004`) → `NtAllocateVirtualMemory`, all inside the section. On the FEX
side `NotifyMemoryAlloc(After=TRUE)` → `HandleMemoryProtectionNotification`, and
with `0002`'s `DisableDEP` an ordinary `PAGE_READWRITE` heap block gives
`EffectiveExec = true`, so `InvalidateIntervalInternal` takes
`CodeInvalidationMutex` **exclusively** (`InvalidationTracker.cpp:502`). Without
`DisableDEP` the same notification takes no lock at all.

*Code mutex → heap, refuted.* `BTCpu64NotifyReadFile` does hold the mutex across
the syscall (`Module.cpp:1021`), but only when `BeginUntrackedWriteLocked`
returns true, and the syscall is served by Wine's **unix-side** `NtReadFile`,
which allocates with libc `malloc` — the Win32 heap is not reachable from there.
Every other exclusive holder (`ImageTracker.cpp:128/205`,
`InvalidationTracker.cpp:177/341/462`) does work that either stays in FEXCore —
which allocates from rpmalloc, not the process heap — or is another syscall.

**So the cycle could not be closed by reading, and `0030` cannot close it
either**: it sees `RTL_CRITICAL_SECTION`s, and this is a `WritePriorityMutex`
over `RtlWaitOnAddress`. The missing fact is missing because nothing in the
stack can currently observe it. That is what this adds.

**What it does.** Records the acquiring return address and thread id on
exclusive acquisition; on an exclusive wait that passes five seconds, emits one
`EFmt` naming the mutex, both thread ids, the holder's site and the waiter's
site, phrased to read beside `0030`'s output. Then falls back to the untimed
wait for the rest of that acquisition, so a genuinely stuck thread costs no more
than it did before.

**Reading the outcomes — each is decisive.**

| What appears | What it means |
|---|---|
| Holder inside `NotifyReadFile` or `HandleImageMap` | the cycle is real and that is the reverse edge; break the cheaper end |
| Holder tid `0000` | held by **readers**, not an exclusive owner — `CompileBlock` takes it shared. A different cycle than either edge considered |
| **Nothing, game still deadlocked** | the code mutex is **not in the cycle**; 0190 is asleep elsewhere inside `NtAllocateVirtualMemory` — Wine's `virtual_mutex` or the server. As valuable as the others, and the reason this is a diagnostic |

**It fired, and the answer was the first row.** Watched on the device
2026-08-14, `260815`:

```
WritePriorityMutex 0000007FE00B0100: wait timed out in thread 0188 after 5000 ms,
  blocked by 0284; which took it at 0000006FFC6E727C,
  and the waiter wants it at 0000006FFC6EAA18
```

Against the module base `0000006FFC560000` that `loaddll` prints, and this
build's full symbol table at image base `0x180000000`:

| Address | Symbol |
|---|---|
| `0000006FFC6E727C` — the **holder**, `0284` | `FEX::Windows::ImageTracker::HandleImageMap +0x60` |
| `0000006FFC6EAA18` — the **waiter**, `0188` | `FEX::Windows::InvalidationTracker::InvalidateIntervalInternal +0x54` |

Beside it, `0030` reported `0284` waiting on the *main process heap section*,
held by `0188`. So the reverse edge is `HandleImageMap`, and the refutation
above was right about FEXCore and wrong about the boundary: `ImageTracker` is
Wine-side glue, and its `std::filesystem` and `fmt` work allocates from the
Win32 process heap, not rpmalloc.

Two notes for the next reader. **A rejected cache does not avoid this** — on
that run `re9.exe`'s cache failed `0011`'s validation and the deadlock was
identical, because the lock is taken before any cache check and codemap
*writing* still runs under it. And **the run that fired it had
`VK_KHR_present_wait` disabled**, which fixed nothing but changed the timing
enough to make the inversion reproducible; the same stall with the extension
enabled produced no line from either instrument.

**Known gap.** Only *exclusive* waits are watched. A thread stuck in
`lock_shared()` reports nothing, so "no line" means "nobody waited exclusively",
not "nobody waited". Closing that is a small extension of the same shape — a
stuck reader is by definition blocked by an exclusive holder, and `OwnerTID` and
`OwnerSite` already name it.

**Cost.** On acquisition: two relaxed stores and one `GetCurrentThreadId`, which
FEX's own shim implements as two TEB loads (`Common/WinAPI/Sync.cpp:114`) — no
syscall, no allocation, no stack walk. On release: two relaxed stores. On a wait
that completes normally: nothing, because only the first wait is bounded. The
site is `__builtin_return_address(0)`; after inlining that is the return address
of the frame that took the lock, so resolve it as module+offset against
`loaddll`, the same arithmetic `0030`'s sites need.

**Deliberate omissions.** Windows only — the Linux path uses `FUTEX_WAIT_BITSET`,
whose timeout is absolute rather than relative, and getting that wrong would
change behaviour on a platform nobody is debugging. And no `thread_local`,
because `0004` records that TLS is unusable on these paths for a BT-loaded
module and that a first attempt using it faulted during emulator startup.

### Extended to shared waits (revision 16)

**The first revision shipped as 260815, ran, and never fired — and that was not
conclusive.** `wine/0030` did not fire either, despite the stall running well
past its 60 s threshold, and 26 of 78 threads sat in `futex_wait` unattributed.
Exclusive-only instrumentation can therefore only establish *nobody waited
exclusively*, which is not the same as ruling the mutex out.

`lock_shared()` now carries the same instrument: same five-second first wait,
same one-shot latch, same per-mutex budget. Readers deliberately record no
owner — there can be many and none is the blocker — but a stuck reader is by
definition waiting on an exclusive holder, so `OwnerTID`/`OwnerSite` name the
blocker directly. That makes the shared report the *less* ambiguous of the two:
the exclusive one can come back with tid `0000` meaning "held by readers", and
the shared one cannot.

The timed reader wait mirrors `FutexWaitForReadAvailable` exactly — `&Futex + 2`,
16-bit width, expected value `Expected >> 16`. That pairing with
`WakeByAddressAll` on the same offset address is the part that was audited clean
in 2608, and a mismatch in address or width here would reintroduce precisely the
class of bug that audit cleared.

With both paths instrumented, **silence now means no thread waited on this mutex
at all** — a negative complete enough to write into the TODO.

**Not compiled.** Verified with `git apply --check` against the pinned tree.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

**Correction, and the reason the project rule exists.** The paragraph above said
"not compiled", and it was committed on that basis. It did not compile: the
inserted paragraph closed the block comment early with a stray `*/`, leaving the
rest of the prose to be parsed as declarations — 20 errors in
`WritePriorityMutex.h`, none of them in the patch's actual code. `git apply
--check` proves a patch applies, never that the result builds. Fixed, and the
build below is what verified it.

## `0014-imagetracker-prepare-outside-the-code-mutex-publish-under-it`

**The fix for the deadlock `0013` measured, and it works.** Watched on the
device 2026-08-14 as `260816`: Requiem runs past the point where every previous
run stopped, with no `WritePriorityMutex` line and no `main process heap
section` timeout. The device counters are the cleanest evidence — a deadlocked
run sat at 1-2% CPU and **0% GPU**; this one reports GPU mean 4.7% and peak
**69%**, which is a process drawing rather than a process asleep.

**What it changes.** `HandleImageMap` acquired `CodeInvalidationMutex` as its
first statement and held it across relocation loading, the `MappedImages`
insert, `fmt::format`, `std::filesystem::exists`/`create_directories`,
`CodeMapWriter` construction, file I/O and `LoadAOTImages`. Several of those
allocate from the **Win32 process heap** — the filesystem calls and the file
open through kernelbase, and `LoadAOTImages`' `ScopedUnicodeString` via
`RtlCreateUnicodeStringFromAsciiz` (`Common/Priv.h:41-43`). That is the edge:
FEX held its own lock across the guest's.

Three publications genuinely need the mutex and they are all that stays inside
it now: `CTX.SetCodeMapWriter` (read by `CompileBlock` under a shared lock,
`Core.cpp:938`), `CodeCache::EnableLoadedSection`, and
`CTX.AddForceTSOInformation`, which asserts the mutex is held (`Core.cpp:1066`).
`MappedImages` is guarded by `ImagesLock`, not by this mutex. In
`LoadAOTImages`, only `RegisterMappedCodeBuffer` takes it.

**Why narrowing cannot race an invalidation against the insert.** The whole
function runs inside the guest's `NtMapViewOfSection`, before that call returns
and therefore before any thread can hold an address in the new image, with the
loader section held throughout; concurrent entry is prevented separately by
`ThreadCreationMutex` (`ARM64EC/Module.cpp:961`), and the two `ProcessInit`
calls run single-threaded. No block from the image can be compiled between the
insert and the publication.

**Why not `0004`'s shape.** That patch made a notification hook `try_lock` and
proceed, which is safe there because nothing is skipped when the try fails.
Here all three operations are unskippable — dropping ForceTSO registration
silently mis-orders atomics in the new image, dropping `EnableLoadedSection`
loses the cache. So the fix is to stop *holding* the lock over work that never
needed it, not to stop blocking.

**The other edge is still there, deliberately.** `patches/fex/0002` disables
DEP, so an ordinary `PAGE_READWRITE` heap block still counts as executable and
`InvalidateIntervalInternal` still takes this mutex from an allocation
notification arriving under Wine's heap lock. Breaking either edge breaks the
cycle, and this is the cheaper end: a cold, serialised path inside a call the
guest cannot yet observe, against the SMC hot path whose last "obviously
redundant" change killed Metro 2033.

**Policy.** Not for upstream, for the reason at the top of this file. This one
is AI-authored in full.

## 0016-codecache-a-cache-that-cannot-be-written-must-not-be-half-written.patch

**The code cache has never once loaded for Resident Evil Requiem, and the
failure was invisible.** Every session spent about 74 seconds compiling ahead of
time, wrote roughly 100 MB, and had it refused on the next launch — while the
game ran on the runtime JIT throughout, looking like it had a warm cache.

Observed in every session in the 2026-08-15 corpus, from both ends:

```
Code cache relocation 1863801 offset 0x6630e70 (base 0x0) does not fit a
  107151044 byte buffer; refusing to apply            <- generation
Code cache relocation 1878114 of 7952524 targets 0x66f403c, outside a
  0x66f4000 byte code buffer                          <- load
Failed to load cache: re9.exe-a7b10b28eeac3098
```

**Why the table is bad was already written down under `0011`, as an inference
drawn after the corpus had been cleared. These logs are the observation that
confirms it**: when generation exhausts the code buffer, `JIT.cpp` calls
`ClearCodeCache`, which installs a fresh buffer and restarts `LatestOffset` at
zero, while relocations are deliberately kept across that point. Everything
recorded before the swap keeps an offset into a buffer that no longer exists,
and both the block list and the relocation table are then written against the
*new* `LatestOffset`.

This patch fixes two things, and deliberately not the third:

1. **`SaveData` validated after five writes.** Its only check was
   `ApplyCodeRelocations`, whose failure path is `LOGMAN_THROW_A_FMT(false, …)`
   followed by `return false` — and that macro is compiled out of this Release
   build. So a rejected cache was a silent return leaving a header, a block list
   and a relocation table on disk with no code behind them. The same invariants
   the loader enforces are now checked *before the first write*.
2. **The caller ignored the result.** `Main.cpp` dropped `SaveData`'s `bool` and
   renamed `.new` into place unconditionally — the atomic-publish dance
   faithfully publishing a file that could not be read. It now unlinks and
   publishes nothing.

### What this does not fix, and why not

**The swap itself.** Raising `MAX_CODE_SIZE` is not available: its own comment
says "we don't want to move above 128MB atm because that means we will have to
encode longer jumps", which is the ±128 MB reach of an AArch64 `b`/`bl`. A cache
that spans a swap cannot be repaired at save time either, because the code those
offsets referred to is gone. Stopping compilation *before* the buffer fills
would produce a valid partial cache and is the obvious next step —

**— and it must not be done alone, which is the point worth recording.** Today
Requiem's cache is refused on every load, so the risk of executing a stale
translation is exactly zero. Making the cache loadable would replace that with
FEX executing code translated from Denuvo's *encrypted* bytes, which differ from
what is in memory at runtime: silent wrong execution, and far harder to diagnose
than an honest refusal. `0011` flagged this and it is still true — the format has
no field binding a cache to the bytes it was generated from. So the buffer-swap
fix and a content hash in the header are one change, not two.

**Policy.** Not for upstream, for the reason at the top of this file. AI-authored
in full.
