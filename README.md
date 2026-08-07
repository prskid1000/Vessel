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
phone from a 2019 Snapdragon 730 to a 2026 flagship. Vessel picks the opposite
tradeoff: **every native component is compiled for one target**, and the app is
built around a reproducible pipeline so re-tuning is a one-line version bump.

| | Generic build | Vessel |
|---|---|---|
| Box64 | portable ARM64 | `-mtune=oryon-1`, armv8.7-a+sve2 (`SD8EG5` preset) |
| GPU driver | A7xx Turnip | Turnip built from the `gen8` branch for Adreno 8xx / KGSL |
| Engine choice | manual, global | per-container, auto-detected per app |
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

Vessel does **not** have a global "ARM mode / x86 mode" switch. Each container
is created with an architecture profile — **Universal (ARM64EC)**, where Wine's
own DLLs are native ARM64 and only application code is translated by FEX, or
**Compatibility (x86_64)**, where the whole Wine tree is x86 under Box64. When
you launch an executable, Vessel reads its PE header, detects whether it is
ARM64, ARM64EC, x64, or x86, and routes it to the right engine automatically.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

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

Five of six components produce verified packages, all compiled for this chip:

| Component | Package | Verified |
|---|---|---|
| Box64 | `box64-0.4.4-canoe.wcp` | Runs on the target device and translates x86-64 |
| FEX | `fex-2608-canoe.wcp` | Both PE modules carry hybrid (CHPE) metadata — genuine ARM64EC |
| Turnip | `turnip-26.3.0-devel-9c51ede5-canoe.wcp` | Binary carries an explicit `Adreno (TM) 829` entry |
| DXVK | `dxvk-2.7.1-canoe.wcp` | ARM64EC `system32/` + 32-bit `syswow64/` |
| vkd3d-proton | `vkd3d-3.0.1-canoe.wcp` | Same layout, D3D12 |
| Wine | — | **Does not build.** See `build/wine.sh` |

The strongest evidence the pipeline is real: a statically linked x86-64 binary
the phone refuses to execute natively (`not executable: 64-bit ELF file`) runs
correctly under our Box64 build. `build/gen_registry.py` reads all five
packages back and writes `registry/contents.json` with hashes, so the chain is
closed from source to registry.

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
