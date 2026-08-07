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

Early scaffolding. Nothing is usable yet. See the roadmap in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap).

## Credits and licensing

Vessel is downstream of a large amount of other people's work — Wine, Box64,
FEX-Emu, Mesa/Turnip, DXVK, vkd3d-proton, and the Winlator lineage. See
[CREDITS.md](CREDITS.md) for attribution and [docs/LICENSING.md](docs/LICENSING.md)
for the license obligations that apply before this repository is published.
