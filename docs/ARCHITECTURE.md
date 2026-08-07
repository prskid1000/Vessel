# Architecture

## The problem

An Android phone has an ARM64 CPU. Windows software is mostly x86-64, some is
32-bit x86, and a growing amount is native ARM64. Running all three well needs
different machinery for each, and the naive approach — one global "emulation
mode" the user toggles — gets the tradeoff wrong for at least two of the three.

## Three layers, not one switch

### Layer 1 — Container architecture profile

Chosen when a container is created. It determines what Wine itself is compiled
as, which is not something that can be changed afterward.

**Universal (ARM64EC)** — the default.
Wine's own DLLs are native ARM64/ARM64EC machine code. Only the application's
own x86 code is translated, by FEX loaded as a PE module inside the process:

| App architecture | Path | Translation cost |
|---|---|---|
| ARM64 (native Windows-on-ARM) | Wine directly | none |
| ARM64EC / ARM64X | Wine directly | none |
| x86-64 | `libarm64ecfex.dll` (FEX) | app code only |
| x86-32 | WoW64 + `libwow64fex.dll` (FEX), or WowBox64 | app code + WoW64 thunks |

Because Wine, DXVK, and vkd3d are native in this profile, the graphics
translation layer — usually the hottest code in a game — runs at full ARM64
speed even when the game itself is x86.

**Compatibility (x86_64)** — the fallback.
The classic Winlator arrangement: the entire Wine tree is x86-64 PE code, and
Box64 emulates all of it, Wine and DXVK included. Slower in principle, but it
is a different enough code path that it rescues applications which fail under
ARM64EC — some installers, some DRM, some anti-cheat.

**This profile is 64-bit only.** Box64's Box32 mode, which would add 32-bit x86
support, is written against glibc and does not compile against Android's bionic
libc — verified, with the specific failures recorded in `build/box64.sh`.
Closing that gap would need either a glibc-targeted Box64 or patches under
`patches/box64/`. Until then, 32-bit x86 applications must run in a Universal
container, where FEX's WoW64 path handles them. That is the recommended
placement regardless, since it is also the faster one.

### Layer 2 — Automatic per-application detection

The user should never have to know what architecture an `.exe` is. On launch,
Vessel reads the PE header:

- `IMAGE_FILE_HEADER.Machine` — `0xAA64` ARM64, `0x8664` x64, `0x014C` x86
- ARM64EC/ARM64X are both `0xAA64`; they are distinguished by the load config
  directory (`CHPEMetadataPointer`), so an ARM64X binary is not mistaken for
  pure ARM64

The detected architecture is shown as a badge on the app tile and drives engine
selection inside the container. If an executable cannot run in the container it
was dropped into, Vessel says so and offers to place it in one that can, rather
than failing with a Wine backtrace.

### Layer 3 — Per-application override

A profile attached to a single executable can force an engine, pin a component
version, or change memory-ordering settings. This is where per-game tuning
lives, and it is what the benchmark screen writes into when a configuration
wins.

## Expected performance ordering

Fastest to slowest, as a prior to be tested rather than a claim:

1. Native ARM64 app, Universal container — no translation anywhere.
2. x64 app, Universal container — app code translated; Wine/DXVK/vkd3d native.
3. x86-32 app, Universal container — as above plus WoW64 thunking.
4. Anything in a Compatibility container — Wine, DXVK and app all emulated.

Box64 beats FEX on particular titles, mostly because its default memory model
is laxer than FEX's. There is no universal winner, which is why the benchmark
harness (see the roadmap) exists: every engine claim in this project should be
reproducible on the target device before it is believed.

## Why memory ordering dominates on this chip

x86 guarantees Total Store Order. ARM does not, so an emulator must either
insert barriers or use a hardware TSO mode. Some ARM cores implement `FEAT_TSO`
and can switch it on for free. **Oryon does not**, which was verified on-device.

Consequences:

- FEX emits acquire/release and LRCPC operations instead. `TSOEnabled` must
  stay on — turning it off breaks multithreaded applications — but
  `HalfBarrierTSOEnabled` (cheap form) is on and `VectorTSOEnabled` (very
  expensive, and games are vector-heavy) is off.
- Box64 exposes `BOX64_DYNAREC_STRONGMEM` from 0 (fastest, default) to 4 (full
  TSO mimicry). Start at 0 and raise only for titles showing threading glitches.

These are the highest-leverage runtime dials on this device, which is why the
container editor surfaces them properly instead of hiding them.

## Runtime defaults

Runtime defaults live in exactly one place — `app/src/main/assets/params-manifest.json`
— so a fresh container is correct without the user knowing any of this.
`build/targets/<target>.env` is build-time only. For `canoe`:

| Setting | Value | Reason |
|---|---|---|
| `TU_DEBUG` | `sysmem` | GMEM tiled rendering page-faults on gen8 Adreno; see the warning below |
| FEX `TSOEnabled` | on | required for correctness |
| FEX `HalfBarrierTSOEnabled` | on | cheap ordering, no `FEAT_TSO` on Oryon |
| FEX `VectorTSOEnabled` | off | severe cost on vector-heavy workloads |
| Box64 `STRONGMEM` | 0 | upstream default; contested — see below |
| Box64 `WEAKBARRIER` | 1 | makes raising `STRONGMEM` affordable |
| Wine sync | esync | kernel 6.12 GKI has no `/dev/ntsync` |

Two of these deserve more than a table row, because the widely circulated
advice is wrong:

**`TU_DEBUG=sysmem` is not optional on this GPU.** Popular guides — most
Chinese-language ones, and the K11MCH1 release notes they derive from — say to
disable sysmem and select `gmem`. That guidance is scoped to Adreno 710/720.
On A8xx the fork maintainer reports GMEM write page faults, and the picture
corrupts. This is the single most likely piece of circulating advice to break
a build on this device.

**`STRONGMEM=0` is inherited, not chosen.** It is Box64's own default, and at
0 x86 store ordering is simply not emulated — which is fine until a
multithreaded title desyncs. The best-evidenced way to buy ordering back
cheaply is pairing `STRONGMEM=1` with `WEAKBARRIER=1` or `2`. Measure it on
the target rather than inheriting either value.

Full flag reference, with primary sources and what could not be verified, is
in [TUNING.md](TUNING.md).

## Component pipeline

Native components are not bundled into the APK. Each is built independently and
published as a **`.wcp`** package — a compressed tar carrying a `profile.json`
manifest — which the app downloads and installs at runtime. This is the format
the Winlator ecosystem already uses, so our packages stay compatible with other
apps and vice versa.

```
native/pins.env  ──►  build/fetch.sh  ──►  patches/<c>/*.patch
                                              │
                          build/targets/canoe.env (chip flags)
                                              │
                                       build/<c>.sh
                                              │
                                    build/package_wcp.py
                                              │
                              dist/<c>-<ver>-canoe.wcp  +  sha256
                                              │
                                   registry/contents.json
                                              │
                                  app Components screen ──► device
```

Two consequences worth stating plainly:

- **Updating a component does not require a new APK.** Bumping FEX is a one-line
  change to `pins.env`; CI builds it and the phone offers it as an update.
- **CI only rebuilds what changed.** Each workflow triggers on its own
  `pins.env` key and `patches/<component>/` path.

## Roadmap

| Phase | Deliverable | Why first |
|---|---|---|
| 1 | Repo, pipeline, benchmark harness | Nothing else is measurable without it |
| 2 | Custom Box64 (`SD8EG5`/`oryon-1`) | Simplest build; proves the pipeline end to end |
| 3 | Custom FEX PE DLLs | The primary engine |
| 4 | Custom Turnip gen8 + matched DXVK/vkd3d | Largest expected gain; driver and D3D layer must ship as a pair |
| 5 | Custom Wine 11 ARM64EC | Turns "runs games" into "runs any laptop app" |
| 6 | App shell: containers, components, telemetry | The product |

Phases 2–5 each produce something installable and benchmarkable on its own,
against the phase-1 baseline.
