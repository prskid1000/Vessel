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
