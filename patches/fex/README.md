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
