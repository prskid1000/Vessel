# Architecture

## One kind of container, no switch

There is no architecture profile to choose. Wine is built ARM64EC, so its own
DLLs are native ARM64/ARM64EC machine code, and only the application's own x86
code is translated — by FEX, loaded as a PE module inside the process:

| App architecture | Path | Translation cost |
|---|---|---|
| ARM64 (native Windows-on-ARM) | Wine directly | none |
| ARM64EC / ARM64X | Wine directly | none |
| x86-64 | `libarm64ecfex.dll` (FEX) | app code only |
| x86-32 | WoW64 + `libwow64fex.dll` (FEX) | app code + WoW64 thunks |

Because Wine, DXVK and vkd3d are all native here, the graphics translation
layer — usually the hottest code in a game — runs at full ARM64 speed even when
the game itself is x86.

### Why there is no Box64 fallback

An earlier design kept a "Compatibility" profile where Box64 emulated an
x86-64 Wine tree wholesale. It was removed because Wine here is built
`--enable-archs=arm64ec,aarch64,i386`, so there is no x86-64 Wine for Box64 to
run — the profile had nothing to execute. Two supporting facts: Box64's Box32
mode does not compile against bionic at all (it reaches for glibc internals),
and ARM64EC is proven in production on this exact device by Winlator-Ludashi.
The build script is in git history if that turns out wrong.

### Per-application detection

The user should never have to know what architecture an `.exe` is. On launch,
Vessel reads `IMAGE_FILE_HEADER.Machine` — `0xAA64` ARM64, `0x8664` x64,
`0x014C` x86. ARM64EC/ARM64X are *also* `0xAA64`; they are distinguished by the
load config directory (`CHPEMetadataPointer`), so an ARM64X binary is not
mistaken for pure ARM64.

The detected architecture is shown as a badge on the app tile. Because there is
only one kind of container, an executable can never be "in the wrong one" — the
badge is information, not a warning. A per-executable profile can pin a
component version or change memory-ordering settings for the occasional title
that needs it.

Expected ordering, as a prior to be tested rather than a claim: native ARM64
(no translation) → x64 (app code only) → x86-32 (plus WoW64 thunking).

## Why memory ordering dominates on this chip

x86 guarantees Total Store Order. ARM does not, so an emulator must either
insert barriers or use a hardware TSO mode. Some ARM cores implement `FEAT_TSO`
and can switch it on for free. **Oryon does not**, verified on-device — but it
does report `lrcpc3`/`ilrcpc`, which is what FEX accelerates TSO off, and makes
emulating it comparatively cheap.

These are the highest-leverage runtime dials on this device, and they sit behind
the editor's advanced disclosure: every default is already the correct one, and
each is a trade a user would be making blind.

FEX configuration traps worth knowing before adding a knob:

- **The env var names are all-caps with no internal underscores.**
  `config_generator.py` emits the enum as `json_name.upper()`, then `Config.cpp`
  does `GetVar(EnvMap, "FEX_" #enum)`. So JSON `TSOEnabled` becomes
  `FEX_TSOENABLED`, never `FEX_TSOEnabled` — reading `Config.cpp` alone gets
  this wrong.
- **`ParanoidTSO` does not exist in current FEX main.** Do not ship a
  `FEX_PARANOIDTSO` knob; it was removed and code search returns zero hits.
- **`HostFeatures` has no TSO member.** It forces ISA features
  (`enablelrcpc`, `enablelrcpc2`); it does not switch TSO.
- **`ForceSVEWidth` is a vixl-simulator debug option**, not an SVE tuning knob.
  Nothing in this stack consumes SVE2 — FEX has no user-facing SVE control, so
  any claimed SVE2 speedup from a chip preset is wrong.
- **FEX requires a 4 KB page kernel.** A hard gate, and the usual reason FEX
  fails on 16 KB-page Android 16 devices.

## Runtime defaults

Runtime defaults live in exactly one place — `app/src/main/assets/params-manifest.json`
— so a fresh container is correct without the user knowing any of this.
`build/targets/<target>.env` is build-time only. For `canoe`:

| Setting | Value | Reason |
|---|---|---|
| `TU_DEBUG` | *(none forced)* | Neither rendering mode is forced — see below |
| `TU_AUTOTUNE_ALGO` | `default` | `prefer_sysmem` available if a title corrupts |
| FEX `TSOEnabled` | on | required for correctness; off breaks multithreaded apps |
| FEX `HalfBarrierTSOEnabled` | on | cheap ordering, no `FEAT_TSO` on Oryon |
| FEX `VectorTSOEnabled` | off | severe cost on vector-heavy workloads |
| Wine sync | esync | fixed, not a setting — see README, Known limitations |

**Neither rendering mode is forced.** The widely repeated "GMEM is broken on
Adreno 829, force `TU_DEBUG=sysmem`" is wrong: Turnip's GMEM page-fault report is
scoped to Adreno **830**, and Turnip does not set `disable_gmem` for a829, so
forcing sysmem gives up tiled rendering the hardware can do. The opposite advice,
common in Chinese-language guides, is Adreno 710/720 guidance and equally
unfounded. Where a specific title does corrupt, `TU_AUTOTUNE_ALGO=prefer_sysmem`
is the better control than `TU_DEBUG=sysmem` — it still permits the fast path in
high-confidence cases, so it is a nudge rather than a veto.

## Running downloaded native code on Android

Android's W^X enforcement decides whether components can be downloaded at all,
and it is narrower than the folklore suggests.

**`dlopen` from `filesDir` is permitted, at any targetSdk.** Two independent
layers allow it: SELinux `untrusted_app_all.te` grants
`app_data_file:file { r_file_perms execute }`, and AOSP
`art/libnativeloader/library_namespaces.cpp` sets
`kAlwaysPermittedDirectories = "/data:/mnt/expand"`.
`darksylinc/AdrenoToolsTest` ships this pattern at targetSdk 34.

**`execve` of a downloaded binary is what is actually blocked** at targetSdk 29
and above. The escape hatch is `system_linker_exec`: exec the system linker and
pass the binary as its argument.

```
execve("/system/bin/linker64", ["/system/bin/linker64", "<filesDir>/.../wineserver", ...])
```

The linker lives in a system exec context, so policy permits it. This is how
`wineserver` and `bin/wine` start.

**Winlator is not a guide here.** It execs from `filesDir` with a plain
`ProcessBuilder` because it declares `targetSdkVersion 28`, below the threshold.
Vessel targets 36. Read any Winlator technique that appears to execute
downloaded binaries directly with its manifest in hand.

| Component | Kind | Delivery |
|---|---|---|
| Wine unix side (`wineserver`, `bin/wine`, `*-unix/*.so`) | host bionic ELF | `.wcp`, started via the system linker |
| Turnip (`libvulkan_freedreno.so`) | host bionic ELF | `.wcp`, `dlopen`ed from `filesDir` |
| FEX DLLs, DXVK, vkd3d, Wine's PE DLLs | Windows PE | `.wcp`; Android's loader never sees them — Wine opens them as data |

### Turnip may not need adrenotools

In Winlator, Turnip is *not* loaded through adrenotools — it is extracted into
the glibc container rootfs and loaded by the container's glibc loader.
Adrenotools is used only on Winlator's Vortek path, to replace the **host**
`/system/lib64/libvulkan.so`.

Vessel has no glibc container, so our Turnip is host-side bionic — structurally
Winlator's Vortek case. But adrenotools is a large piece of machinery (it
recovers bionic's hidden `__loader_android_create_namespace` by disassembling
`dlopen` for its `BL` instruction, then preloads a hook to interpose
`android_dlopen_ext`, and that hook early-outs unless the filename contains
`vulkan.`). Mesa's Turnip talks to `/dev/kgsl` directly and has no vendor-blob
dependencies, so it may load with a plain `dlopen`. **Spike this before adopting
adrenotools.** If the unix-side Wine `.so` files need soname resolution rather
than absolute paths, `liblinkernsbypass` alone provides that.

### Wine is three toolchains, not one

Worth stating because the symptom misleads. Wine builds a **host** pass (the
`winebuild`/`wrc`/`widl`/`wmc` code generators, which must execute on the build
machine and are handed over with `--with-wine-tools`), a **PE** pass
(arm64ec/aarch64/i386 Windows modules via llvm-mingw) and a **Unix** pass
(`wineserver`, `ntdll.so`, `winex11.so`) that runs on the phone and must
therefore be cross-compiled against **bionic with the NDK**.

`configure` complaining about missing X11 headers is a symptom of the last one:
satisfying it with host headers produces a Unix side linked against x86-64 glibc
that cannot run on the device. `--without-x` is not a way out either — the
container's display path *is* Wine's X11 driver talking to the app's built-in X
server. `build/x11-sysroot.sh` cross-builds a minimal X11 client stack for
Android so the cross pass has something correct to link against.

## Component pipeline

Each native component is built independently and published as a **`.wcp`**
package — a compressed tar carrying a `profile.json` manifest — which the app
downloads and installs at runtime. This is the format the Winlator ecosystem
already uses, so our packages stay compatible with other apps and vice versa.

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

Updating a component does not require a new APK, and CI only rebuilds what
changed — each workflow triggers on its own `pins.env` key and
`patches/<component>/` path.

## Roadmap

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Repo, pipeline, Docker toolchain | **Done** |
| 2 | ~~Custom Box64~~ | **Removed** — no x86-64 Wine for it to run |
| 3 | Custom FEX PE DLLs | **Done** — CHPE-verified ARM64EC |
| 4 | Custom Turnip gen8 + matched DXVK/vkd3d | **Done** — a829 support confirmed in the binary |
| 5 | Custom Wine ARM64EC | **Done** — packaged and verified |
| 6 | App shell: containers, components, diagnostics | **Done** for everything not needing a session |
| 7 | Session launcher | Not started; the one blocker for running anything |
| 8 | Benchmark harness | Not started; needs a running session to measure |

Phases 2–5 each produce an installable `.wcp` whose provenance records the
source commit and the exact compiler flags used, so any claim this project makes
about a component can be checked against the package rather than taken on trust.

## Open issue: components are stored per container

Measured 2026-08-07, after the first full set of packages existed:

| Component | Download | On disk |
|---|---|---|
| Wine | 63.1 MB | **912 MB** |
| DXVK | 3.4 MB | 23 MB |
| Zink | 3.0 MB | 20 MB |
| Turnip | 1.6 MB | 14 MB |
| vkd3d | 2.6 MB | 10 MB |
| FEX | 1.1 MB | 9 MB |
| | **75 MB** | **~988 MB** |

`ContainerLayout.components` resolves to `containers/<id>/components/`, so every
container gets its own copy. Three containers on the same Wine build is three
byte-identical 912 MB trees — about 3 GB to store one Wine.

That is not sustainable, and the fix is a **content-addressed shared store**:
install a package once at `filesDir/components/<type>/<versionCode>/` and have
containers reference it rather than own it. Reference counting decides when a
version can be deleted; a container pinned to an older build keeps it alive.

Two reasons it is worth doing before the launcher rather than after:

- The launcher builds the environment from component paths. Changing where
  components live afterwards means changing the launcher, the provisioner, the
  environment builder and their tests together.
- Per-container copies make a component *update* cost a full reinstall per
  container, which undermines the update-without-reinstall property the whole
  `.wcp` design exists for.

The per-container layout was not wrong when written — components were assumed
small. Wine at 912 MB is what changed the answer, and it was only visible once
a real package existed to measure.
