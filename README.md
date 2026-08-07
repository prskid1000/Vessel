# Vessel

**Windows on Android — compiled for your silicon.**

Vessel runs Windows desktop applications and games on Android phones. It is a
container manager around Wine, with the translation layer, GPU driver and
Direct3D layers all built from source for one specific chip rather than shipped
as generic prebuilts.

> **Working name.** `Vessel` is a placeholder — change `PRODUCT_NAME` in
> `gradle.properties` and the `applicationId` in `app/build.gradle.kts` to rename.

## What makes it different

Most Winlator-family apps ship generic ARM64 binaries that must run on every
phone from a 2019 Snapdragon 730 to a 2026 flagship. Vessel builds every native
component from source for one target. Compiler flags are the smallest part of
that; the decisions that move the needle are architectural:

- **Wine is ARM64EC**, so Wine, DXVK and vkd3d run as native ARM64 code and only
  the application's own x86 instructions are translated — by FEX, loaded as a PE
  module inside the process. There is no "ARM mode / x86 mode" switch and no
  container type to choose: Vessel reads the PE header on launch and routes
  ARM64, ARM64EC, x64 or x86 accordingly.
- **Turnip built for Adreno gen8** — the stock Qualcomm driver lacks extensions
  DXVK 2.x requires, so this is enablement, not optimisation.
- **A KGSL timeline-semaphore patch** the community gen8 builds do not carry.
- **Memory-ordering defaults** chosen for a core without `FEAT_TSO`.

`-mcpu=oryon-1` reaches Turnip, DXVK and vkd3d-proton, but deliberately not FEX
(its JIT detects CPU features at runtime) and not Wine (one `CROSSCFLAGS` covers
three PE architectures and is meaningless to the i386 one).

Native components ship as `.wcp` packages the app downloads at runtime, not in
the APK, so bumping a version in `native/pins.env` needs no new APK. Details in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Target hardware — Motorola Signature

| | |
|---|---|
| SoC | Snapdragon SM8845 (`canoe`) |
| CPU | 8× Qualcomm Oryon — 6× 3.32 GHz + 2× 3.80 GHz |
| ISA | ARMv8.7+, SVE2 (128-bit), SME, i8mm, bf16, LSE, LRCPC3, PAuth/BTI — **no FEAT_TSO** |
| GPU | Adreno 829 (gen8) on KGSL |
| Memory | 16 GB, 4 KB pages |
| OS | Android 16, kernel 6.12.38 (GKI, **no `/dev/ntsync`**) |

Other devices are not a goal yet, but chip-specific flags live in
`native/pins.env` and `build/targets/`, so adding a target needs no script changes.

## Building

Everything builds in Docker, on Windows or Linux, one command per component.
Full instructions, including the Windows/WSL2 path: [docs/BUILDING.md](docs/BUILDING.md).

```bash
docker build -t vessel-build .
./build/fex.sh            # -> dist/fex-2608-canoe.wcp
```

## Status

**Every native component builds and produces a verified package. On the device,
Wine's loader starts inside the app's own sandbox; nothing has been run together
yet.**

| Component | Package | Verified |
|---|---|---|
| Wine 11.14 | `wine-11.14-canoe.wcp` | `wineserver`/`ntdll.so`/`winex11.so` are aarch64 Android ELF; `ntdll.dll` carries CHPE |
| FEX 2608 | `fex-2608-canoe.wcp` | Both PE modules carry hybrid (CHPE) metadata — genuine ARM64EC |
| Turnip | `turnip-…-canoe.wcp` | Binary carries an explicit `Adreno (TM) 829` entry |
| DXVK 2.7.1 | `dxvk-2.7.1-canoe.wcp` | ARM64EC `system32/` + 32-bit `syswow64/` |
| vkd3d-proton 3.0.1 | `vkd3d-3.0.1-canoe.wcp` | Same layout, D3D12 |
| Zink (OpenGL) | `zink-…-canoe.wcp` | `IMAGE_FILE_MACHINE_ARM64EC` (0xA641) with CHPE; exports all five WGL entry points |

The app builds, installs and runs. Containers can be created, configured,
persisted and deleted; per-container session logging works; the session launcher
and an X server are both in the tree. **None of it has driven a Windows program
yet** — the launcher's argv and environment come from reading Wine's source and
from what has been run by hand on the device, not from a completed session, and
the X server is not wired to a host.

Three things are known to be missing rather than merely untested: the
AHardwareBuffer present path that lets DXVK bypass X11, a `winex11.drv` patch so
MIT-SHM uses the app's fd-passing shim instead of `shmget` (bionic exports it but
SELinux refuses it, so Wine falls back to `XPutImage` and copies), and a licence
for Vessel itself — LGPL-2.1 code is now in the APK, which makes
[docs/LICENSING.md](docs/LICENSING.md) a blocking decision rather than an open
question.

Roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap).

## Known limitations

**ntsync is out of reach, and an Android upgrade will not change that.** Vessel
uses **esync**, fixed. Three independent blockers (verified 2026-08-07 against
android.googlesource.com): the complete driver is Linux 6.14 and GKI keeps a
device on its launch kernel (6.12) across Android upgrades; `CONFIG_NTSYNC` is
absent from arm64 `gki_defconfig` on every branch including `android-mainline`,
and on `android16-6.12` its Kconfig entry still says `depends on BROKEN`; and
`ntsync` appears in no AOSP `file_contexts`, so the node would take the generic
`device` label that `untrusted_app` cannot open. Recheck with
`adb shell ls -l /dev/ntsync`; temper expectations even then, since the quoted
ntsync speedups are measured against *wineserver* sync, not esync.

**fsync would crash, not degrade.** Proton's fsync probes for `futex_waitv` and
disables itself on `ENOSYS`. This kernel has the syscall, but it is absent from
Android's seccomp allowlist for apps, where the default action is
`SECCOMP_RET_TRAP` — the probe itself raises `SIGSYS` and kills the process
before the graceful fallback can run.

**4 KB pages only.** FEX requires a 4 KB page kernel. This device qualifies, but
Vessel will not work on a 16 KB-page device even though the APK is correctly
16 KB-aligned for Play: packaging compliance and runtime capability differ.

## Credits and licensing

Vessel is downstream of Wine, FEX-Emu, Mesa/Turnip, DXVK, vkd3d-proton and the
Winlator lineage. [CREDITS.md](CREDITS.md) has the attribution;
[docs/LICENSING.md](docs/LICENSING.md) has the license obligations that apply
before this repository is published.
