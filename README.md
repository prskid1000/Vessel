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
./build/fex.sh            # -> dist/fex-2608-canoe.wcp
```

Full instructions, including the Windows/WSL2 path: [docs/BUILDING.md](docs/BUILDING.md).

## Status

**Every native component now builds. Nothing has been run yet.** The gap is no
longer a missing component — it is the session launcher that puts them together.

**All six components produce verified packages**, every one compiled for this
chip:

| Component | Package | Verified |
|---|---|---|
| Wine 10.13 | `wine-10.13-canoe.wcp` | `wineserver`/`ntdll.so`/`winex11.so` are aarch64 Android ELF; `ntdll.dll` carries CHPE |
| FEX 2608 | `fex-2608-canoe.wcp` | Both PE modules carry hybrid (CHPE) metadata — genuine ARM64EC |
| Turnip | `turnip-…-canoe.wcp` | Binary carries an explicit `Adreno (TM) 829` entry |
| DXVK 2.7.1 | `dxvk-2.7.1-canoe.wcp` | ARM64EC `system32/` + 32-bit `syswow64/` |
| vkd3d-proton 3.0.1 | `vkd3d-3.0.1-canoe.wcp` | Same layout, D3D12 |
| Zink (OpenGL) | `zink-…-canoe.wcp` | `IMAGE_FILE_MACHINE_ARM64EC` (0xA641) with CHPE; exports all five WGL entry points |

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

The app builds, installs and runs. Containers can be created, configured,
persisted across restarts and deleted, with the settings surface generated from
`params-manifest.json`. Per-container session logging is implemented — storage,
rotation, rate limiting and a viewer — and waiting for something to log. The
screens that need a running session say so rather than pretending.

**What is missing is the session launcher**: create the Wine prefix, install the
components into a container, start the X server, set the environment from
[docs/LOGGING.md](docs/LOGGING.md), launch an executable and drain its stderr
into the log. Until that exists the packages sit on disk and Launch does
nothing.

Two honest caveats. **Nothing has been tested together** — six components built
in isolation is not a working stack, and the first real session is where
mismatches surface, which is exactly why the logging went in first. And the
**X server does not exist in our app yet**: `winex11.so` links against our
libX11 and expects a display to connect to. That is Winlator-lineage code, and
it carries the open licensing question in
[docs/LICENSING.md](docs/LICENSING.md) — worth resolving before it is committed
rather than after.

Roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap).

## Known limitations

### ntsync is not reachable, and an Android upgrade will not change that

Wine 10+ can use `ntsync`, a kernel driver that implements NT synchronisation
primitives properly instead of emulating them with eventfd (`esync`) or futexes
(`fsync`). Vessel uses **esync**, fixed, not configurable.

It is worth writing down why ntsync is out of reach, because the intuitive
assumption — "it will arrive with the next Android version" — is wrong. Three
independent blockers, each sufficient on its own (verified 2026-08-07 against
android.googlesource.com):

1. **The kernel will not move.** The complete driver is Linux 6.14. This device
   runs `android16-6.12`. Android's GKI is backward-compatible by design: a
   device that launches on a given kernel **keeps it across Android upgrades**.
   The Signature is in Motorola's Android 17 beta, and it will still be on
   6.12 afterwards.
2. **Google has not enabled it anywhere.** `CONFIG_NTSYNC` is absent from the
   arm64 `gki_defconfig` on *every* branch checked, including `android17-6.18`
   and `android-mainline`. On `android16-6.12` the Kconfig entry is still
   `depends on BROKEN` — an incomplete 6.10-era stub. So it is both
   unselectable and unselected.
3. **SELinux would block it regardless.** `ntsync` appears nowhere in AOSP's
   `file_contexts` or `device.te`, so a `/dev/ntsync` node would take the
   generic `device` label, which `untrusted_app` has no allow rule for. Even
   on a kernel that had the driver, an ordinary app could not open it.

Getting ntsync here needs root and a custom kernel. The emulation community
reached the same conclusion — the Winlator-Ludashi request for it was closed
two days after it was opened, for exactly these reasons.

**Recheck is one command**, if the situation ever changes:

```bash
adb shell ls -l /dev/ntsync
```

Temper expectations even then. The widely quoted ntsync speedups are Linux
desktop figures measured mostly against *wineserver* sync, not against esync,
which already recovers most of the benefit — and on this device the bottleneck
is FEX translation and the GPU, not synchronisation.

### fsync would crash, not degrade

Not offered as an option, and this one is a trap worth naming. Proton's fsync
probes for the `futex_waitv` syscall and disables itself on `ENOSYS`. The
kernel here has `futex_waitv`, so that looks fine — but Android's seccomp
allowlist for apps does not include it, and the default action is
`SECCOMP_RET_TRAP`, which raises `SIGSYS` and kills the process. Proton's
graceful fallback never runs, because the probe itself is fatal. An fsync
toggle in the UI would have shipped a crash rather than a slow path.

### 4 KB pages only

FEX requires a 4 KB page kernel. This device qualifies (verified via
`getconf PAGESIZE`), but Vessel will not work on a 16 KB-page device even
though the APK is correctly 16 KB-aligned for Play. Packaging compliance and
runtime capability are different things here.

## Credits and licensing

Vessel is downstream of a large amount of other people's work — Wine, Box64,
FEX-Emu, Mesa/Turnip, DXVK, vkd3d-proton, and the Winlator lineage. See
[CREDITS.md](CREDITS.md) for attribution and [docs/LICENSING.md](docs/LICENSING.md)
for the license obligations that apply before this repository is published.
