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

**Windows programs run in windows on the phone, draw through Direct3D, play
sound, and take a real gamepad. A commercial game renders in a real container.**

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
`patches/wine/0010` strips it and the shell supplies the window controls.

**Presentation is DRI3, and it is measured** (2026-08-10, `tools/gfx/x11present.c`
against a live session, 300 frames each):

| | mean | p50 | p95 |
|---|---|---|---|
| DRI3 | **0.546 ms** | 0.484 ms | 0.588 ms |
| software | 2.06 ms | 1.51 ms | 4.56 ms |

The server-side copy inside that path costs **252 us** for 1280x720 — 3.6 MB at
14.3 GB/s — which is what sizes the remaining zero-copy work and is why it is
currently deferred rather than scheduled. A game renders a frame every 80-125 ms,
so presentation is under half a percent of it.

**Sound plays, and a gamepad reaches the guest as a gamepad.** Wine's
`wineoss.drv` was rewritten to speak AAudio (`patches/wine/0008`) — the NDK's
only low-latency output — and `mmdevapi` probes `oss` by default, so guest audio
works with no other change. Vessel never attenuates: the guest outputs at full
scale and Android's volume keys own the rest.

`/dev/input` is `root:input` 0660 and an untrusted app is in neither group, so
SDL, udev and libusb can never enumerate a controller here. `patches/wine/0016`
adds a `winebus` backend fed by the app over a unix socket instead: the app is
the bus, and the guest gets a real HID device that XInput, DirectInput and winmm
all see as an Xbox 360 pad, rumble included. Verified 2026-08-11 with a Bluetooth
controller driving a Windows program.

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
| Git 2.55.0.3 | `git-2.55.0.3-arm64.wcp` | Native ARM64 Git + Git Bash, repackaged verbatim — built but **not published**, so the app cannot fetch it |
| DXVK 2.7.1 | `dxvk-2.7.1-canoe.wcp` | **Runs a game** — Metro 2033 Redux presents through it, graphics pipeline libraries in use |
| vkd3d-proton 3.0.1 | `vkd3d-3.0.1-canoe.wcp` | Built and shipped — **no D3D12 title has been run through it** |
| Zink (OpenGL) | `zink-…-canoe.wcp` | Built — **not yet run** |

Fifteen Wine patches, all in `patches/wine/`, each with its reason in its header. The
one that mattered most: a PE image relocated away from its ImageBase can never be
made executable on Android, because making a *modified* private file mapping
executable needs SELinux `execmod`, which apps are not granted and parts of the
policy `dontaudit` — so it fails with `EACCES` and no log line at all.

### What is actually left

Nothing in this section is a blocker any more. It is the record of the last three
things that were, kept because the diagnoses are reusable.

**Not Direct3D. That is done, and this section said otherwise for a week.**
It used to read that KGSL cannot export a dma-buf for a buffer Turnip allocated,
and that this was "the single thing between here and a triangle". A game has
been rendering through DXVK since; the sentence outlived its subject. What
actually got past it was `patches/mesa/0004` (a pseudo-DRM platform for KGSL)
and `0006` (compiling the WSI's DRM image backend without libdrm), after which
DRI3 present works and is the measured 0.546 ms above.

**The frame rate nobody could explain was the audio, and it is fixed.** Metro's
intro ran at 8-12 fps while the CPU sat at 0-4%, no core rose above 1.7 GHz of
3.3, the GPU reported 14%, and all 47 game threads were asleep when sampled.
Every explanation assumed the frame cost something, and measured — correctly —
that nothing did. `wineoss.drv` was queueing Metro's whole 1440-frame buffer
into a device whose start threshold was 1736, so AudioFlinger never started the
track and the driver logged `advanced by 0, held: 1440` for 2072 consecutive
passes. A game whose audio never drains does not spin, it *waits*. Sizing the
AAudio buffer to `min(client, device)` took the intro from 12 fps to **28**, and
gameplay is now GPU-bound at 97-98%.

That signature is worth keeping: **~85% idle at 10 fps means a stall, not a
cost.** The next time it appears, the question is what a thread is waiting on.

`tools/gfx/` holds probes for D3D8/9/10/11/12 and OpenGL — 21 binaries across
three architectures, each clearing to blue, drawing one red triangle and
asserting three specific pixels.

**The guest has working sockets and no network adapters.** Measured: a WinHTTP
`GET` from inside the session returned `status=200`, while `ipconfig` printed
nothing at all. Android denies an untrusted app `bind()` on a `NETLINK_ROUTE`
socket and Wine's `nsiproxy` binds one, so `GetAdaptersAddresses` returns an
empty list and any program that gates on connectivity refuses on a phone that is
online. `patches/wine/0007` enumerates through `getifaddrs()` instead.

**Verified 2026-08-10: `ipconfig` prints eight adapters and the phone's address.**
Getting there took three wrong explanations and one wrong A/B. The last fault was
not in the network stack at all: this build's `dnsapi.so` has no unix library —
configure found no resolver — so `__wine_unixlib_handle` stayed 0 and the syscall
dispatcher indexed a function table at address zero. The access violation was
*returned* rather than raised, which is why `+seh` printed nothing.
`patches/wine/0013-0015` are the fix. DNS servers are still empty, because no
resolver exists to name them.

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
