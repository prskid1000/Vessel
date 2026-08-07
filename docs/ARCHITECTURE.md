# Architecture

## The problem

An Android phone has an ARM64 CPU. Windows software is mostly x86-64, some is
32-bit x86, and a growing amount is native ARM64. Running all three well needs
different machinery for each, and the naive approach — one global "emulation
mode" the user toggles — gets the tradeoff wrong for at least two of the three.

## Two layers, and no switch at all

### Layer 1 — One kind of container

There is no architecture profile to choose. Wine is built ARM64EC, so its own
DLLs are native ARM64/ARM64EC machine code, and only the application's own x86
code is translated — by FEX, loaded as a PE module inside the process:

| App architecture | Path | Translation cost |
|---|---|---|
| ARM64 (native Windows-on-ARM) | Wine directly | none |
| ARM64EC / ARM64X | Wine directly | none |
| x86-64 | `libarm64ecfex.dll` (FEX) | app code only |
| x86-32 | WoW64 + `libwow64fex.dll` (FEX) | app code + WoW64 thunks |

Because Wine, DXVK, and vkd3d are all native here, the graphics translation
layer — usually the hottest code in a game — runs at full ARM64 speed even when
the game itself is x86.

### Why there is no Box64 fallback

Earlier designs kept a second "Compatibility" profile in which the entire Wine
tree was x86-64 PE and Box64 emulated all of it, Wine and DXVK included, as a
rescue path for applications that misbehave under ARM64EC.

It was removed, because it could not work as designed: Wine here is built
`--enable-archs=arm64ec,aarch64,i386`, so there is no x86-64 Wine for Box64 to
run. The profile had nothing to execute. Two further facts made the decision
easy — Box64's Box32 mode does not compile against bionic at all (it reaches
for glibc internals, so that profile would have been 64-bit only anyway), and
ARM64EC is proven in production on this exact device by Winlator-Ludashi.

The gain is not just one fewer component. It removed a choice from container
creation, six tuning parameters from the settings surface, and an engine
selector from the UI. If ARM64EC ever proves insufficient, the build script is
in git history.

### Layer 2 — Automatic per-application detection

The user should never have to know what architecture an `.exe` is. On launch,
Vessel reads the PE header:

- `IMAGE_FILE_HEADER.Machine` — `0xAA64` ARM64, `0x8664` x64, `0x014C` x86
- ARM64EC/ARM64X are both `0xAA64`; they are distinguished by the load config
  directory (`CHPEMetadataPointer`), so an ARM64X binary is not mistaken for
  pure ARM64

The detected architecture is shown as a badge on the app tile and selects the
translation path inside the container. Because there is only one kind of
container, an executable can never be "in the wrong one" — the badge is
information, not a warning.

### Layer 3 — Per-application override

A profile attached to a single executable can pin a component version or change
memory-ordering settings, for the occasional title that needs it.

## Expected performance ordering

Fastest to slowest, as a prior to be tested rather than a claim:

1. Native ARM64 app — no translation anywhere.
2. x64 app — app code translated; Wine, DXVK and vkd3d all native.
3. x86-32 app — as above, plus WoW64 thunking.

There is no fourth tier any more, because there is no second engine to be
slower. What remains variable is FEX's own configuration, and memory ordering
in particular — see below.

## Why memory ordering dominates on this chip

x86 guarantees Total Store Order. ARM does not, so an emulator must either
insert barriers or use a hardware TSO mode. Some ARM cores implement `FEAT_TSO`
and can switch it on for free. **Oryon does not**, which was verified on-device.

Consequences:

- FEX emits acquire/release and LRCPC operations instead. `TSOEnabled` must
  stay on — turning it off breaks multithreaded applications — but
  `HalfBarrierTSOEnabled` (cheap form) is on and `VectorTSOEnabled` (very
  expensive, and games are vector-heavy) is off.
These are the highest-leverage runtime dials on this device. They sit behind
the editor's advanced disclosure rather than on its face: every default is
already the correct one, and each is a trade a user would be making blind.

## Runtime defaults

Runtime defaults live in exactly one place — `app/src/main/assets/params-manifest.json`
— so a fresh container is correct without the user knowing any of this.
`build/targets/<target>.env` is build-time only. For `canoe`:

| Setting | Value | Reason |
|---|---|---|
| `TU_DEBUG` | *(none forced)* | Neither rendering mode is forced — see below |
| `TU_AUTOTUNE_ALGO` | `default` | `prefer_sysmem` available if a title corrupts |
| FEX `TSOEnabled` | on | required for correctness |
| FEX `HalfBarrierTSOEnabled` | on | cheap ordering, no `FEAT_TSO` on Oryon |
| FEX `VectorTSOEnabled` | off | severe cost on vector-heavy workloads |
| Wine sync | esync | fixed, not a setting — see README, Known limitations |

**On forcing a rendering mode — a correction.** An earlier version of this
document stated that GMEM is broken on Adreno 829 and `TU_DEBUG=sysmem` is
mandatory. That was wrong. Reading Turnip's source: the GMEM page-fault report
is scoped to Adreno **830**, and Turnip does not set `disable_gmem` for a829 —
so forcing sysmem gives up tiled rendering the hardware can actually do. The
opposite advice, common in Chinese-language guides, is equally unfounded: it is
Adreno 710/720 guidance.

Neither forcing is justified without measuring on this device, so the default
forces nothing. Where a specific title does corrupt, `TU_AUTOTUNE_ALGO=prefer_sysmem`
is the better control than `TU_DEBUG=sysmem` — Turnip's own documentation notes
it still permits the fast path in high-confidence cases, making it a nudge
rather than a veto.

Full flag reference, with primary sources and what could not be verified, is
in [TUNING.md](TUNING.md).

## Running downloaded native code on Android

Android's W^X enforcement is the constraint that decides whether components can
be downloaded at all, so it is worth stating exactly rather than approximately.
It is narrower than the folklore suggests, and the `.wcp` model survives.

**`dlopen` from `filesDir` is permitted, at any targetSdk.** Two independent
layers allow it, and both were checked:

- SELinux — `untrusted_app_all.te` grants `app_data_file:file { r_file_perms
  execute }` to every untrusted app.
- The linker — AOSP `art/libnativeloader/library_namespaces.cpp` sets
  `kAlwaysPermittedDirectories = "/data:/mnt/expand"`, so an absolute path under
  `filesDir` is inside the app classloader namespace's permitted paths.

`darksylinc/AdrenoToolsTest` ships this pattern at targetSdk 34, which is direct
proof it survives a modern target.

**`execve` of a downloaded binary is what is actually blocked** at targetSdk 29
and above. The escape hatch is `system_linker_exec`: exec the system linker and
pass the binary as its argument, rather than exec'ing the binary directly.

```
execve("/system/bin/linker64", ["/system/bin/linker64", "<filesDir>/.../wineserver", ...])
```

The linker lives in a system exec context, so the policy permits it, and it
loads and runs the target. This is how `wineserver` and `bin/wine` start.

### Why Winlator is not a guide here

Winlator execs from `filesDir` with a plain `ProcessBuilder` and no such trick.
It can, because it declares **`targetSdkVersion 28`** — below the threshold
where the restriction applies. That is not a route open to us: Vessel targets
36, and Play requires a recent target in any case. Any Winlator code or
technique that appears to execute downloaded binaries directly should be read
with its manifest in hand.

### What this means per component

| Component | Kind | Delivery |
|---|---|---|
| Wine unix side (`wineserver`, `bin/wine`, `*-unix/*.so`) | host bionic ELF | `.wcp`, started via the system linker |
| Turnip (`libvulkan_freedreno.so`) | host bionic ELF | `.wcp`, `dlopen`ed from `filesDir` |
| FEX DLLs, DXVK, vkd3d, Wine's PE DLLs | Windows PE | `.wcp`; Android's loader never sees them — Wine opens them as data |

The guest PEs are the majority of what we ship and are entirely unaffected. So
**every component remains downloadable**, and the update-without-reinstall
property holds.

### Turnip may not need adrenotools

Worth recording because the assumption is easy to inherit: in Winlator, Turnip
is *not* loaded through adrenotools. It is extracted into the glibc container
rootfs and loaded by the container's glibc loader, so bionic never touches it.
Adrenotools is used only on Winlator's Vortek path, to replace the **host**
`/system/lib64/libvulkan.so`.

Vessel has no glibc container, so our Turnip is host-side bionic — structurally
Winlator's Vortek case, not its Turnip case. But adrenotools is a large piece of
machinery (it recovers bionic's hidden `__loader_android_create_namespace` by
disassembling `dlopen` for its `BL` instruction, then preloads a hook to
interpose `android_dlopen_ext`, and its hook early-outs unless the filename
contains `vulkan.`). Mesa's Turnip talks to `/dev/kgsl` directly and has no
vendor-blob dependencies, so it may load with a plain `dlopen` and need none of
it. **Spike this before adopting adrenotools**, rather than assuming.

If the unix-side Wine `.so` files turn out to need soname resolution rather than
absolute paths, `liblinkernsbypass` alone provides that without the hook chain —
arm64-only and API ≥ 28, both satisfied here.

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

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Repo, pipeline, Docker toolchain | **Done** |
| 2 | ~~Custom Box64~~ | **Removed** — no x86-64 Wine for it to run; see above |
| 3 | Custom FEX PE DLLs | **Done** — CHPE-verified ARM64EC |
| 4 | Custom Turnip gen8 + matched DXVK/vkd3d | **Done** — a829 support confirmed in the binary |
| 5 | Custom Wine ARM64EC | **Blocked** — see below |
| 6 | App shell: containers, components, diagnostics | **Done** for everything not needing a session |
| 7 | Benchmark harness | Not started; needs a running session to measure |

### The one blocker

Wine does not build, and until it does nothing can launch a Windows
application. The failure is not the missing X11 headers `configure` complains
about — that is a symptom. Wine is two halves:

- the **PE side** (arm64ec / aarch64 / i386 Windows modules) via llvm-mingw,
  which is already correct;
- the **Unix side** — `wineserver`, `ntdll.so`, `winex11.drv.so` — which are
  ELF binaries that run on the phone and must therefore be cross-compiled
  against **bionic with the NDK**, not the build machine's glibc.

Satisfying the X11 error by installing host headers would produce a Unix side
linked against x86-64 glibc that cannot run on the device at all.
`--without-x` is not a way out either: the container's display path *is* Wine's
X11 driver talking to the app's built-in X server, so a Wine without X has no
way to put a window on screen. The X11 client stack has to be cross-compiled
for Android as well.

Full reasoning is at the top of `build/wine.sh`.

### What "done" means here

Phases 2–4 each produce an installable `.wcp` whose provenance records the
source commit and the exact compiler flags used, so any claim this project
makes about a component can be checked against the package rather than taken on
trust. Phase 7 is deliberately last: a benchmark harness cannot measure
anything until phase 5 lands.
