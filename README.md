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
- **Memory-ordering defaults** measured on this core, not guessed: there is no
  hardware TSO mode to fall back on, and FEX's cheap barrier path is worth 21%.

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
| ISA | ARMv8.7+, SVE2 (128-bit), SME, i8mm, bf16, LSE, LRCPC2/3, PAuth/BTI — **no hardware TSO mode** |
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

**Windows programs run on the phone. Nothing has drawn a pixel yet.**

All three CPU paths are verified on the device — inside the app's own uid and
SELinux domain, not as the adb shell user — by `./tools/device-session.sh`,
which checks the arithmetic each program produces rather than that it started:

```
ok prefix built (system.reg 1861 KB)
ok emulator keys are in system.reg
ok syswow64 has 885 entries
VESSEL-OK bits=64 sum=333338333350000   ARM64, no translation
VESSEL-OK bits=64 sum=333338333350000   x86-64 via libarm64ecfex
VESSEL-OK bits=32 sum=333338333350000   x86-32 via WoW64 + libwow64fex
```

| Component | Package | Verified |
|---|---|---|
| Wine 11.14 | `wine-11.14-canoe.wcp` | Builds a real prefix on the device and runs programs |
| FEX 2608 | `fex-2608-canoe.wcp` | Translates x86-64 and x86-32 correctly on the device |
| Turnip | `turnip-…-canoe.wcp` | Binary carries an explicit `Adreno (TM) 829` entry |
| DXVK 2.7.1 | `dxvk-2.7.1-canoe.wcp` | ARM64EC `system32/` + 32-bit `syswow64/` — **not yet run** |
| vkd3d-proton 3.0.1 | `vkd3d-3.0.1-canoe.wcp` | Same layout, D3D12 — **not yet run** |
| Zink (OpenGL) | `zink-…-canoe.wcp` | ARM64EC with CHPE, exports all five WGL entry points — **not yet run** |

Getting there took four Wine patches, all in `patches/`, and the reason each
exists is recorded in its header. The one that mattered most: a PE image
relocated away from its ImageBase can never be made executable on Android,
because making a *modified* private file mapping executable needs SELinux
`execmod`, which apps are not granted and parts of the policy `dontaudit` — so
it fails with `EACCES` and no log line at all.

**The graphics stack is the whole of what is left**, and probing it turned up
three things worth stating plainly:

- **Every D3D version is blocked on the display path, not just the windowed
  ones.** DXVK enables `VK_KHR_win32_surface` at *instance* creation
  unconditionally, and Wine only advertises it once a display driver is loaded,
  so `vkCreateInstance` fails before any device exists. "D3D11 and D3D12 can run
  headless" is true of D3D and false of DXVK.
- **Turnip is not loaded yet.** `libvulkan_freedreno.so` exports only `HMI`, so
  it needs libadrenotools' `android_dlopen_ext` hook, and the APK ships none.
  Vulkan on the device currently answers `"Qualcomm Technologies Inc. Adreno
  Vulkan Driver"` — the stock blob. Everything this README says about Turnip
  describes a binary that has not run.
- **DXVK, vkd3d and Zink are pure ARM64EC, not ARM64X**, so a native ARM64
  process cannot load them (`ERROR_BAD_EXE_FORMAT`). That is fine for real
  Windows programs, which run as ARM64EC, but it rules out a native control.

`tools/gfx/` holds probes for D3D8/9/10/11/12 and OpenGL — 21 binaries across
three architectures, each clearing to blue, drawing one red triangle and
asserting three specific pixels. They compile; none has rendered anything.

Also unfinished: the AHardwareBuffer present path that would let DXVK bypass X11
entirely, and `patches/wine/0005`, which implements MIT-SHM over the app's
fd-passing socket and has been compiled but never executed — it is inert unless
`WINE_SYSVSHM_SOCKET` is set, deliberately.

Vessel is **LGPL-2.1-or-later**; see [LICENSE](LICENSE) for why that was the only
available choice and why "or later" is load-bearing.

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
[docs/LICENSING.md](docs/LICENSING.md) has the licence obligations, and
[LICENSE](LICENSE) is Vessel's own — LGPL-2.1-or-later, forced by the X server
vendored into the APK.
