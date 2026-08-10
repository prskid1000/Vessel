# Vessel

**Windows on Android — compiled for your silicon.**

Vessel runs Windows desktop applications on Android phones. It is a container
manager around Wine, with the translation layer, GPU driver and Direct3D layers
all built from source for one specific chip rather than shipped as generic
prebuilts.

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
- **Memory-ordering defaults** measured on this core, not guessed: there is no
  hardware TSO mode to fall back on. (The "21%" this line used to quote for
  FEX's half-barrier path was the *LRCPC2* result wearing the wrong label —
  `tools/tso/run.sh` never toggled that variable. The knob is currently
  unjustified rather than disproven; see `docs/TODO.md`.)

`-mcpu=oryon-1` reaches Turnip, DXVK, vkd3d-proton and **Wine's unix side**
(`ntdll.so`, `win32u.so`, `winex11.drv.so`). It stays out of Wine's
`CROSSCFLAGS`, which covers arm64ec, aarch64 and i386 PE code with one string
and is meaningless to the last of those. FEX gets `-DTUNE_CPU=oryon-1` for its
own C++ but not `-mcpu`: its JIT detects the host CPU at runtime, so the flag
would reach FEX's code and never the code it generates.

Native components ship as `.wcp` packages published to a rolling
[`components`](https://github.com/prskid1000/Vessel/releases/tag/components)
release, not in the APK, so bumping a version in `native/pins.env` needs no new
APK. Details in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

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
docker run --rm -v "$PWD:/src" -v vessel-work:/work vessel-build ./build/wine.sh
```

> **On Docker Desktop, use a named volume for `/work`.** Its VM mounts `/tmp` as
> tmpfs with `noexec`, so a `configure` script extracted there cannot be run and
> the X11 sysroot step fails with a misleading "ships no configure script".

## Status

**Windows programs run in windows on the phone, take input, and draw through
Direct3D. A commercial game renders in a real container.**

Verified on the device on 2026-08-09, from the app's own launcher rather than a
shell: `notepad.exe` added from the file browser, tapped on its tile with nothing
running. The container started, the program came up with it, the taskbar lists
it with its real icon, and `adb shell input text` put **`VESSEL-KEYBOARD-OK`** into it at
`Ln 1, Col 19`. Leaving the desktop and returning left the window and its text
intact.

Since then: Metro 2033 Redux renders through DXVK on Turnip inside a container,
and a D3D11 triangle reads back correct in both bitnesses
(`tools/device-graphics.sh --only d3d11`). Top-level windows have **no Win32
caption** — a caption is unhittable on a phone and cost 41 unpainted rows, so
`patches/wine/0010` strips it and the shell supplies the window controls. What is
*not* done: presentation is still a CPU copy, zero-copy is in progress, and the
frame is pixel-bound — a game at 1280x720 measures 20-26 fps and the same scene
at 640x360 reaches the 60 fps cap.

All three CPU paths are verified by `./tools/device-session.sh`, which checks the
arithmetic each program produces rather than that it started:

```
VESSEL-OK bits=64 sum=333338333350000   ARM64, no translation
VESSEL-OK bits=64 sum=333338333350000   x86-64 via libarm64ecfex
VESSEL-OK bits=32 sum=333338333350000   x86-32 via WoW64 + libwow64fex
```

| Component | Package | Verified |
|---|---|---|
| Wine 11.14 | `wine-11.14-canoe.wcp` | Builds a prefix and runs programs in windows |
| FEX 2608 | `fex-2608-canoe.wcp` | Translates x86-64 and x86-32 correctly on the device |
| Turnip | `turnip-…-canoe.wcp` | **Answers inside Wine** — `driver_id=18`, Mesa 26.3.0-devel, `Adreno (TM) 829`, api 1.4.358 |
| Git 2.55.0.3 | `git-2.55.0.3-arm64.wcp` | Native ARM64 Git + Git Bash, repackaged verbatim — **not yet installed by the app** |
| DXVK 2.7.1 | `dxvk-2.7.1-canoe.wcp` | Loads and reaches `vkCreateInstance` — **no swapchain** |
| vkd3d-proton 3.0.1 | `vkd3d-3.0.1-canoe.wcp` | Same |
| Zink (OpenGL) | `zink-…-canoe.wcp` | Built — **not yet run** |

Eleven Wine patches, all in `patches/wine/`, each with its reason in its header. The
one that mattered most: a PE image relocated away from its ImageBase can never be
made executable on Android, because making a *modified* private file mapping
executable needs SELinux `execmod`, which apps are not granted and parts of the
policy `dontaudit` — so it fails with `EACCES` and no log line at all.

### What is actually left

**Direct3D, and the reason has moved twice.** It was "no X11 WSI in Turnip";
building Mesa with `-Dplatforms=x11` got past that. Every D3D probe now dies one
step further along, at swapchain creation, because **KGSL cannot export a
dma-buf for a buffer Turnip allocated** — `kgsl_bo_export_dmabuf` can only
re-export an fd it imported. The X11 WSI's "client allocates, server imports"
shape cannot work on this driver; the shape that can is the Android side
allocating and the client importing. That is a design question, not a build flag,
and it is the single thing between here and a triangle.

`tools/gfx/` holds probes for D3D8/9/10/11/12 and OpenGL — 21 binaries across
three architectures, each clearing to blue, drawing one red triangle and
asserting three specific pixels.

**The guest has working sockets and no network adapters.** Measured: a WinHTTP
`GET` from inside the session returned `status=200`, while `ipconfig` printed
nothing at all. Android denies an untrusted app `bind()` on a `NETLINK_ROUTE`
socket and Wine's `nsiproxy` binds one, so `GetAdaptersAddresses` returns an
empty list and any program that gates on connectivity refuses on a phone that is
online. `patches/wine/0007` enumerates through `getifaddrs()` instead — built,
**not yet verified on the device**.

[docs/TODO.md](docs/TODO.md) is the honest list, with the evidence for every
closed item and the open ones stated as they are.

## Known limitations

**ntsync is out of reach, and an Android upgrade will not change that.** Vessel
uses **esync**, fixed. Three independent blockers (verified 2026-08-07 against
android.googlesource.com): the complete driver is Linux 6.14 and GKI keeps a
device on its launch kernel (6.12) across Android upgrades; `CONFIG_NTSYNC` is
absent from arm64 `gki_defconfig` on every branch including `android-mainline`,
and on `android16-6.12` its Kconfig entry still says `depends on BROKEN`; and
`ntsync` appears in no AOSP `file_contexts`, so the node would take the generic
`device` label that `untrusted_app` cannot open.

**fsync would crash, not degrade.** Proton's fsync probes for `futex_waitv` and
disables itself on `ENOSYS`. This kernel has the syscall, but it is absent from
Android's seccomp allowlist for apps, where the default action is
`SECCOMP_RET_TRAP` — the probe itself raises `SIGSYS` and kills the process
before the graceful fallback can run.

**4 KB pages only.** FEX requires a 4 KB page kernel. This device qualifies, but
Vessel will not work on a 16 KB-page device even though the APK is correctly
16 KB-aligned for Play.

**No package manager, and none is coming.** winget ships as an MSIX built on
WinRT, and Wine implements no part of the Windows app model — it cannot start,
let alone install. Chocolatey needs Windows PowerShell 5.1, which is not
distributable and which Wine only stubs. Vessel's own component downloader does
the job those tools would: fetch a verified archive and put it on `PATH`.

## Credits and licensing

Vessel is downstream of Wine, FEX-Emu, Mesa/Turnip, DXVK, vkd3d-proton and the
Winlator lineage. [CREDITS.md](CREDITS.md) has the attribution;
[docs/LICENSING.md](docs/LICENSING.md) has the licence obligations, and
[LICENSE](LICENSE) is Vessel's own — LGPL-2.1-or-later, forced by the X server
vendored into the APK. The app names the X server and its licence on its home
screen and carries every licence text in full.
