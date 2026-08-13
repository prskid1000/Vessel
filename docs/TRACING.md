# Tracing

`docs/LOGGING.md` is about **what a session logs by default and why that set is
fixed**. This document is about **how to see something it does not show**,
without editing C and waiting forty minutes.

Those are different problems and they had different answers, and the second one
did not have a good one. This is the design for it, what was built, and what was
deliberately not.

## The complaint, restated precisely

Three separate costs, all of them measured on this project:

1. **Five vocabularies.** Wine has four class bits and 521 channels; DXVK has a
   six-stop minimum severity; vkd3d has two independent channels on a six-stop
   ladder that puts `fixme` *below* `warn`; Mesa has a logger bitmask and a
   separate four-word level; FEX has a boolean. Nothing in the stack answers the
   question anybody actually has, which is "show me the graphics path".
2. **The ladders do not say what they cost.** Two twenty-minute device sessions
   were spent finding out: `seh` at *Everything* produced 191,000 lines of
   `RtlInitializeExtendedContext2` and zero exceptions, because its ERR tier was
   already on through `err+all`; `vulkan` at *+ Stubs* produced nothing at all,
   because that channel has no FIXME sites and everything above WARN is TRACE.
3. **Anything not already instrumented needs a rebuild.** `patches/wine/0025`
   hand-wrote thirteen `TRACE` lines into `dlls/win32u/vulkan.c` to find which
   pointer faulted during swapchain creation. Forty minutes per iteration, and
   the patch is now permanent scaffolding around one bug.

## The scheme: `VESSEL_TRACE`

One variable, typed into the container's existing environment table. Grammar:

```
VESSEL_TRACE = term (',' term)*
term         = topic [':' level]
level        = off | errors | warnings | stubs | everything     (default warnings)
```

A **topic** names an outcome, not a tool. A **level** is one ladder for all of
them. Examples:

```sh
VESSEL_TRACE=graphics:stubs,x86:errors
VESSEL_TRACE=shaders                       # warnings, the default stop
VESSEL_TRACE=all:errors                    # every topic, quietest useful stop
```

| Topic | What it covers | Composes into |
|---|---|---|
| `graphics` | draw call to pixel, whole chain | Wine `vulkan`, `DXVK_LOG_LEVEL`, `VKD3D_DEBUG`, `VKD3D_SHADER_DEBUG`, `MESA_LOG`, `MESA_LOG_LEVEL`, `TU_DEBUG` |
| `d3d` | the two Direct3D translators only | `DXVK_LOG_LEVEL`, `VKD3D_DEBUG` |
| `shaders` | shader translation only | `VKD3D_SHADER_DEBUG` |
| `driver` | Turnip / Mesa only | `MESA_LOG`, `MESA_LOG_LEVEL`, `TU_DEBUG` |
| `x86` | the translator | `FEX_SILENTLOG`, and `VESSEL_TRACE` itself — see below |
| `loader` | what loaded, what did not, missing exports | Wine `module`, `file` |
| `audio` | guest writes vs device takes | Wine `oss` |
| `input` | the pad bridge and its messages | Wine `winebus`, `msg` |
| `sync` | whether fsync started, and every wait | Wine `fsync`, `sync` |
| `exceptions` | every exception raised | Wine `seh` |
| `calls` | every call between libraries | Wine `relay` |

Implemented in `core/TraceSpec.kt`. Three properties are worth naming because
each is the answer to a way this could have gone wrong:

- **It composes through the existing `Emit` strategies**, so `-all` is still
  first in `WINEDEBUG`, the fixed prefix is still un-deletable, and an untouched
  container still produces the golden environment byte for byte.
- **A hand-added Diagnostics row still wins.** The topics fold first and the rows
  second; Wine's parser takes the last term and a `LinkedHashMap` takes the last
  `put`, so the ordering *is* the precedence rule. A topic is the broad brush, a
  row is the instrument.
- **Nothing is silently dropped.** A misspelt topic or level produces a term
  carrying a *problem string* rather than an absence, and a stop a topic does not
  have rounds **down** — never up — and says it did. Silent rounding is what made
  `vulkan:stubs` look like a setting.

### Every stop carries a volume, and its provenance

`TraceStop.linesPerMinute` and `TraceStop.basis`. The basis must open with
**measured** (a device session), **counted** (source sites), **estimated** (a
guess, said so) or **documented** (a project's claim about itself); a test
enforces it. `TraceSpec.minutesToFill()` turns a rate into the only number that
matters before launching — how long until the log's whole byte budget is gone.

The same idea is applied to the per-row ladder as `Loggable.volumes`, populated
only where a real figure exists. `seh` at *Everything* now says *191,000 lines in
one ~20-minute session, measured*; `vulkan` at *+ Stubs* says *nothing beyond
warnings — this channel has no stub tier, measured*. Both sentences are the
device runs that produced them, written down so nobody buys them again.

## Generic function tracing without hand-written `TRACE`

This is the part of the brief that asked for `-finstrument-functions`. The
honest answer is **not yet, and here is the order to do it in** — because the
investigation turned up a mechanism that already exists and a set of facts that
change the cost estimate.

### What already exists, and is unproven here

Wine has generic, named, argument-carrying, per-function-scopable entry/exit
tracing: `+relay`. It is not a blunt instrument — `dlls/ntdll/relay.c:162-190`
reads six scoping lists out of `HKCU\Software\Wine\Debug`, and `check_list`
(`:196-222`) matches `module.function`, `module.*` or a bare function name, with
`RelayFromInclude` scoping by *caller* as well.

`RelayInclude=winevulkan.*` is exactly "trace every function in this DLL with its
arguments and return values, and nothing else", needing no rebuild at all. It
would not have solved patch 0025's problem — that fault was in a static inside
`win32u`, not at an export — but it covers the majority of cases that are
currently answered by adding `TRACE` lines.

**Two things stand in the way, and the first is decisive:**

1. **Relay is compiled out for ARM64EC.** `dlls/ntdll/relay.c:37` is
   `#if (…) && !defined(__arm64ec__)`, and the `#else` at `:913-925` stubs
   `RELAY_SetupDLL` and `RELAY_GetProcAddress` to nothing. Vessel's PE modules
   are ARM64X — arm64ec objects linked into the aarch64 DLLs (`build/wine.sh`
   :503-518) — so whether the aarch64 half keeps its relay thunks is a question
   nobody here has answered.
2. **The scoping lists are registry-only.** There is no environment variable for
   any of the six, and Vessel's prefix registry is seeded at provisioning rather
   than per session.

**So the first experiment is one device session**, not a rebuild: set the `relay`
channel to *Everything* on a throwaway container and see whether a single line
appears. That costs one run and settles whether the cheapest generic tracer in
the stack exists on this base at all. If it does, `calls:<module>` becomes a
small `relay.c` patch reading the include list from `VESSEL_TRACE` — which is
the second reason the raw spec string is passed through to the guest.

### `-finstrument-functions`, assessed

For the residual case relay cannot reach — a static function inside one module —
the mechanism is compiler instrumentation. Four findings that the brief's
framing did not have:

- **`-finstrument-functions-exclude-file-list` is a GCC flag and this is an
  all-clang toolchain.** NDK r29 (clang 21) for the unix side, llvm-mingw
  20250910 for every PE target, and `build/fex.sh:46-51` records that FEX is
  clang-only by necessity. Clang's equivalents are the
  `__attribute__((no_instrument_function))` attribute and `-fprofile-list=`; the
  exclude-file-list spelling does not exist. Any plan written against it needs
  rewriting.
- **It is not free when disabled.** Clang emits real calls to
  `__cyg_profile_func_enter` / `_exit` at every entry and exit; a disabled
  runtime is still two calls and a global load per function boundary. That is
  acceptable on `dlls/win32u/vulkan.c` and not acceptable on anything per-draw.
  Clang's zero-cost alternative is XRay (`-fxray-instrument`, nop sleds patched
  at runtime), whose compiler-rt runtime is not available for PE/arm64ec.
- **Per-TU flags need a patch, in every component.** No build script here has a
  per-translation-unit flag facility: the three meson components set `c_args`
  globally in a cross file, and Wine has only the `CFLAGS` (unix, one target)
  versus `CROSSCFLAGS` (all three PE architectures at once) split. Scoping
  instrumentation to selected files means patching the component's own
  `Makefile.in` / `meson.build`. For Wine that is cheap to iterate on —
  `build/wine.sh:380-386` excludes patches from the configure stamp, so adding
  one rebuilds only the touched TUs rather than reconfiguring.
- **Symbolisation has no `dladdr` inside a PE.** `this_fn` is an address;
  internal statics are not exported, so the honest output is
  `module + RVA`, resolved offline against the build's own symbols. That is a
  tool (`nm`/`llvm-symbolizer` over `dist/`), not a runtime feature.

**Recommended order**, therefore: (a) one session to find out whether relay
exists on ARM64X; (b) if it does, the `relay.c` env-scoping patch, which gets
named per-function tracing for every export at zero build cost; (c) only then
`-finstrument-functions` on an opt-in file list, for statics, with the runtime
reaching the log through `__wine_dbg_output` — the same export FEX, DXVK and
vkd3d already use — and a symbolisation tool beside it.

Step (c) is not built. Building it before (a) risks spending a week
reimplementing something the tree already has.

## Volume control that is measured, not guessed

`docs/LOGGING.md` describes four layers at the sink and the rule that none of
them may drop silently. Two gaps are now closed:

- **Drops are attributed.** The rate limiter counts per `<level><source>` prefix
  and its marker names the worst offender:
  `… logging rate-limited, 9214 lines dropped, 9200 of them TF (trace/fex) …`.
  The old wording answered none of the three questions a reader has — what was
  shouting, whether their line was in it, what to turn down — and both volume
  disasters had to be attributed by hand afterwards. The session total is in
  `SessionLogMeta.droppedBySource`.
- **The producer channel's drops are still unattributed, on purpose.**
  `onUndeliveredElement` runs on the sender's thread holding the *oldest queued*
  element, not the one being sent, so counting it there would credit the loss to
  whichever source happened to be sending. `overflowLines` stays a bare count
  rather than a wrong attribution.

## The three cheap wins

**1. Per-source histogram.** `SessionLogMeta.sourceCounts`, keyed by
`<level><source>`, written as the session runs. `lines == sourceCounts.values
.sum()` is an invariant and is asserted. It is surfaced where it would have been
seen: the session list row says `99% trace/fex` when one source is over two
thirds of a run (`dominantSourceLabel`), which is the state both disasters were
in and neither announced.

**2. Error digest.** The finished log ends with every distinct `E*` line and its
count, loudest first, and the same list is in the sidecar as
`SessionLogMeta.errorDigest`. Distinctness is exact text after the `×N` repeat
suffix is stripped — deliberately the weakest normalisation that works, because
collapsing digits would merge `Library d3dx9_43.dll not found` with
`Library d3dx11_43.dll not found`. A clean session gets `… no errors in this
session …` rather than nothing, since printing nothing is indistinguishable from
the digest having failed.

**3. Volume hints.** Covered above.

## The two-letter prefix legend

Every stored line is `<level><source>` and no space:

| | |
|---|---|
| level | `E` error · `W` warn · `I` info · `T` trace |
| source | `W` wine · `F` fex · `D` dxvk · `K` vkd3d · `R` driver · `V` vessel |

So `TF` is trace/fex, `WK` is warn/vkd3d, `EW` is error/wine. Nothing documented
this and it had to be recovered twice with `cut -c1-2 | sort | uniq -c`.

It is now written into **every session's header**, derived from the two enums
rather than restated, so a log pasted into a bug report carries its own key.
`logPrefixLegend()` in `core/SessionLogFormat.kt` is the one definition; a test
asserts it names every member of both enums.

## Finding the live end of a running session

A session past its head allowance is three files, and `<startedAt>.log` is the
**oldest**: head, then `.log.t1`, then `.log.t0`, which is the one being written.
Running `tail` on `<startedAt>.log` during a live session shows a DLL load from
ten minutes ago, and the honest-looking reading of that is "nothing went wrong
after startup". That was reported once and it was false.

The head file now ends with its own breadcrumb, written at the moment it is
closed so it cannot go stale:

```
… this file ends here; the session continues in 1750000000000.log.t0,
  and while it is running the live end is always .log.t0 …
```

The merge at finalise leaves the line in place, where it is an accurate record of
where the head ended.

## Native patches

Three, all small, **none of them built yet** — see the caveat at the end.

### `patches/vkd3d/0001` — the per-shader header chatter is a trace

+23 / −3 in `libs/vkd3d-shader/dxbc.c`.

**This also corrects a mis-attribution.** The 26,966-line burst that was 98% of a
session once FEX was silenced — `skip_dword_unknown` and `parse_dxbc: Ignoring
DXBC checksum` — was attributed to `VKD3D_DEBUG` and mitigated by setting
`VKD3D_DEBUG=err` on the container. That mitigation cannot have worked. Both
messages come from a TU that opens with
`#define VKD3D_DBG_CHANNEL VKD3D_DBG_CHANNEL_SHADER` (`dxbc.c:20`), and
`libs/vkd3d-common/debug.c:49-53` maps that channel to **`VKD3D_SHADER_DEBUG`**.
The variable that was lowered has no bearing on the lines that were counted.

The burst is also Vessel's own doing rather than upstream's: `debug.c:96-97`
defaults an unset channel to `FIXME` (4), and both messages are `WARN` (5) in a
ladder where `warn` sits *above* `fixme` (`debug.c:38-47`), so upstream is silent
for both. Vessel sets `VKD3D_SHADER_DEBUG=warn` deliberately, to see shader
translation failures — and that is exactly the tier this noise was burying.

Arithmetic confirming the mechanism rather than merely the grep: `dxbc.c:124-125`
is a checksum line followed by `skip_dword_unknown(&ptr, 4)`, which prints a
heading plus one line per DWORD — six lines — plus two more at `:193`. Eight
times 3,025 shaders is 24,200, within 10% of the 26,966 counted.

The patch moves the three sites to `TRACE`. Nothing is lost: they stay reachable
at `VKD3D_SHADER_DEBUG=trace`, and the `warn` tier becomes readable. Lowering the
variable instead would have silenced real shader warnings, which is why it was
not done.

### `patches/wine/0026` — a long log line must not kill the process

+65 / −10 in `dlls/ntdll/thread.c` and `dlls/ntdll/unix/debug.c`.

`__wine_dbg_output` buffers into `char output[1020]`. Over-long input was not
rejected, it was **fatal**: the PE side raised `STATUS_BUFFER_OVERFLOW`
(`thread.c:81`) and the unix side called `abort()` (`unix/debug.c:89`).

That export is not Wine's private channel. FEX (`Logging.cpp:42`), DXVK
(`log.cpp:62`) and vkd3d (`debug.c:125`) all resolve it and write every
diagnostic through it, and two of the three format into buffers larger than 1020
bytes — vkd3d into `char local_buffer[4096]` (`debug.c:239`), FEX into an
unbounded `fextl::fmt::format`. DXVK is the only one that defends against it, and
its comment says why: *"__wine_dbg_output tries to buffer lines up to 1020
characters … and will cause a hang if we submit anything longer than that"*
(`log.cpp:81-86`). Wine's own unix side can reach it unaided, since
`wine_dbg_vprintf` formats into `char buffer[1024]`.

It belongs in this document rather than in a bug list because **the failure gets
more likely the more tracing is switched on**: a vkd3d trace line carrying a
shader name, or a `fixme` carrying a serialised structure, is routinely over a
kilobyte. A tracing facility whose loud settings can kill the session is not one.

The patch flushes what is buffered and passes the long line straight through.
The `ERR_(thread)` that announced it is dropped, because it reported a
Wine-internal programming error and now fires on the ordinary behaviour of three
shipped components.

### `patches/fex/0003` — a level ceiling driven by `VESSEL_TRACE`

+100 / −0 in `Source/Windows/Common/Logging.cpp`.

FEX has two states and no level. `MsgHandler` never compares `Level` against
anything (it uses it only to pick a prefix character), the `Logging` group of
`Config.json.in:386-401` contains no level option, and `MSG_LEVEL` is a
`constexpr = INFO` in `LogManager.h:41`, so every `DFmt` in the tree is compiled
in. Hence 49 MB and ~508,000 lines from one six-minute session, 99.9% of it one
`DFmt` per unaligned atomic at `ARM64EC/Module.cpp:716` — and hence Vessel
shipping `FEX_SILENTLOG=1`, which also hides FEX's host-feature parse errors
(`Source/Common/HostFeatures.cpp:149`, `:180`, both `EFmt`).

The patch adds a ceiling, defaulting to `ERROR`, read once in `Init()` from the
`x86` topic of `VESSEL_TRACE`. The filter is before the format, not after,
because the cost being removed is 508,000 `fextl::fmt::format` calls. `off` still
lets an assert through; `FEX_SILENTLOG` remains the way to ask for true silence.

*Policy.* `native/fex/CLAUDE.md` forbids AI-generated code in *contributions to
that project*. This is a Vessel-local patch applied to Vessel's build and is not
for upstream, which is the position `patches/fex/README.md` already records for
the two patches beside it. If it is ever worth contributing it needs writing
again by a person.

**None of the three has been compiled.** They were generated against the patched
base and each verified with `git apply --check`, which is what stops
`apply_patches` hard-failing every future build — but no build was started,
because the device is mid-investigation on a separate bug and a rebuild changes
the shipped binary. Until the components are rebuilt: `VESSEL_TRACE` works
end-to-end for every topic except the differentiation of `x86`'s stops, the
vkd3d burst is still present at the shipped `warn`, and a >1019-byte log line is
still fatal.

## What is deliberately not here

- **`-finstrument-functions`.** Reasoned above: relay may already do this job,
  and one device session settles it. Building the harder thing first is the
  expensive order.
- **A `VESSEL_TRACE` control of its own on the Diagnostics screen.** It is a row
  in the environment table, which already takes a name and a value and now
  offers this one with its help text. A dedicated field would mean a schema
  change and a migration to gain nothing.
- **Runtime level changes.** No component in the stack supports it except
  Turnip: DXVK's `m_minLevel` is `const`, vkd3d's levels are set in a
  `pthread_once`, Mesa's in a `call_once`, Wine's channels latch on first use,
  and FEX reads `SILENTLOG` once. `TU_DEBUG_FILE` is the sole exception and is
  already wired up.
- **Anything reading logcat.** `MESA_LOG=file` moves Mesa onto the pipe this
  product already reads, which is cheaper than teaching the app a second source.
