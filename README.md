# Vessel

**Windows on Android — compiled for your silicon.**

Vessel runs Windows desktop applications and games on Android phones. It is a
container manager around Wine, with the translation layer, GPU driver, and
Direct3D layers all built from source with flags tuned for one specific chip
rather than shipped as generic prebuilts.

> **Working name.** `Vessel` is a placeholder — change `PRODUCT_NAME` in
> `gradle.properties` and the `applicationId` in `app/build.gradle.kts` to rename.

---

## What makes it different

Most Winlator-family apps ship generic ARM64 binaries that must run on every
phone from a 2019 Snapdragon 730 to a 2026 flagship. Vessel builds every native
component from source for one target, through a reproducible pipeline where
re-tuning is a one-line version bump.

**What "built for this device" actually means here**, because the phrase is
easy to oversell. Compiler flags are the smallest part of it: `-mcpu=oryon-1`
reaches Turnip, DXVK and vkd3d-proton, but deliberately not FEX (its JIT
detects CPU features at runtime, so the flag would only tune FEX's own C++) and
not Wine (one `CROSSCFLAGS` covers three PE architectures, and it is meaningless
to the i386 one).

The device-specific decisions that actually move the needle are architectural:

- **Wine is ARM64EC**, so Wine, DXVK and vkd3d run as native ARM64 code instead
  of being emulated. Only the application's own x86 instructions are
  translated. This is worth far more than any compiler flag.
- **Turnip built for Adreno gen8** — the stock Qualcomm driver lacks extensions
  DXVK 2.x requires, so this is enablement rather than optimisation.
- **A KGSL timeline-semaphore patch** the community gen8 builds do not carry.
- **Memory-ordering defaults** chosen for a core without `FEAT_TSO`, which is
  the highest-leverage runtime setting on this chip.
- Everything compiled against this exact ISA, 4 KB pages, and API 35.

| | Generic build | Vessel |
|---|---|---|
| GPU driver | A7xx Turnip | Turnip built from the `gen8` branch for Adreno 8xx / KGSL |
| D3D layers | prebuilt x86 | DXVK and vkd3d-proton built as ARM64EC PE |
| Engine choice | manual, global | none to make — detected from the executable |
| Component updates | new APK | `.wcp` package, no reinstall |

## Target hardware

The first target is a **Motorola Signature**:

| | |
|---|---|
| SoC | Snapdragon SM8845 (`canoe`) |
| CPU | 8× Qualcomm Oryon — 6× 3.32 GHz + 2× 3.80 GHz |
| ISA | ARMv8.7+, SVE2 (128-bit), SME, i8mm, bf16, LSE, LRCPC3, PAuth/BTI — **no FEAT_TSO** |
| GPU | Adreno 829 (gen8) on KGSL |
| Memory | 16 GB, 4 KB pages |
| OS | Android 16, kernel 6.12.38 (GKI, **no `/dev/ntsync`**) |

Other devices are not a goal yet, but nothing in the design prevents adding
targets: chip-specific flags live in `native/pins.env` and `build/targets/`.

## Architecture in one paragraph

Vessel has **no "ARM mode / x86 mode" switch, and no container types to choose
between.** Wine is built as ARM64EC, so its own DLLs — and DXVK and
vkd3d-proton with them — are native ARM64 code. Only the application's own x86
instructions are translated, by FEX loaded as a PE module inside the process.
When you launch an executable, Vessel reads its PE header, detects whether it
is ARM64, ARM64EC, x64 or x86, and routes it accordingly. Native ARM64 Windows
apps are not emulated at all. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository layout

```
app/                 Android app (Jetpack Compose, single module)
native/pins.env      Single source of truth for every component version
patches/<component>/ Our patches, applied on top of the pinned upstream ref
build/               One hermetic build script per component + .wcp packager
registry/            contents.json — the component registry the app reads
docs/                Architecture, design system, build and licensing docs
.github/workflows/   Per-component CI; builds only what changed
```

## Building

Everything builds in Docker, on Windows or Linux, with one command per
component:

```bash
docker build -t vessel-build .
./build/box64.sh          # -> dist/box64-0.4.4-canoe.wcp
```

Full instructions, including the Windows/WSL2 path: [docs/BUILDING.md](docs/BUILDING.md).

## Status

**Not usable yet — Wine does not build, so nothing can launch a Windows
application.** Everything underneath it does, though.

Four of five components produce verified packages, all compiled for this chip:

| Component | Package | Verified |
|---|---|---|
| FEX | `fex-2608-canoe.wcp` | Both PE modules carry hybrid (CHPE) metadata — genuine ARM64EC |
| Turnip | `turnip-26.3.0-devel-9c51ede5-canoe.wcp` | Binary carries an explicit `Adreno (TM) 829` entry |
| DXVK | `dxvk-2.7.1-canoe.wcp` | ARM64EC `system32/` + 32-bit `syswow64/` |
| vkd3d-proton | `vkd3d-3.0.1-canoe.wcp` | Same layout, D3D12 |
| Wine | — | **Does not build.** See `build/wine.sh` |

`build/gen_registry.py` reads the packages back and writes
`registry/contents.json` with hashes, so the chain is closed from source to
registry.

**Box64 was removed**, deliberately. It only had a role in a Compatibility
profile where the whole Wine tree is x86-64 emulated wholesale — and since Wine
here is ARM64EC, there is no x86-64 Wine for it to run. FEX covers every case.
Removing it also removed a container-type choice, six tuning parameters, and a
build. It is recoverable from git history if the ARM64EC path ever proves
insufficient, which production use of ARM64EC in Winlator-Ludashi on this same
device suggests it will not.

The app builds, installs, and runs: containers can be created, configured,
persisted across restarts, and deleted, with the settings UI generated from
`params-manifest.json`. Diagnostics reads real device capabilities. The screens
that need a running Wine session — session view, benchmark, app library — say
so rather than pretending.

Wine is the gap, and it is not a small one: its Unix half must be
cross-compiled against bionic with the NDK while its PE half uses llvm-mingw,
and it needs an X11 client stack the NDK does not ship. The reasoning is
recorded at the top of `build/wine.sh`.

Roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap).

## Credits and licensing

Vessel is downstream of a large amount of other people's work — Wine, Box64,
FEX-Emu, Mesa/Turnip, DXVK, vkd3d-proton, and the Winlator lineage. See
[CREDITS.md](CREDITS.md) for attribution and [docs/LICENSING.md](docs/LICENSING.md)
for the license obligations that apply before this repository is published.
