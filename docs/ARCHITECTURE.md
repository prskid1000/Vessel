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

x86 guarantees Total Store Order. Arm does not, so an emulator must insert
barriers. There is no hardware escape on this chip, and the reason is worth
stating precisely because the folklore is wrong in three separate ways.

**There is no `FEAT_TSO`.** Arm does not define a TSO feature at all. The
implementations that exist are vendor-specific: Nvidia Denver/Carmel and Fujitsu
A64FX are always-TSO, and Apple's is IMPDEF — `ACTLR_EL1[1]` toggles it,
`AIDR_EL1[9]` advertises it. Nothing comparable is documented for Oryon.
The widely-repeated claim that Oryon has "hardware accommodations for x86's
memory store architecture" traces to a single unsourced sentence in one review
that never says TSO, names no register, and cites no Qualcomm document.

**The kernel interface does not exist either.** `PR_SET_MEM_MODEL`, which is how
userspace would ask for such a mode, was rejected upstream in 2024 and is absent
from mainline; it ships only in Asahi's tree. FEX carries hardcoded magic
constants for it, which is the signature of an out-of-tree patch rather than an
allocated prctl. `ACTLR_EL1` is EL1-only in any case, so an unrooted app could
not reach it even if Qualcomm had one.

Worth knowing: hardware TSO is not free even where it exists. Measured on an
Apple M1 under Asahi, turning it on cost 19% reader-thread IPC and made real
workloads 11–64% slower. It pays for emulated code and taxes everything else.

What the chip *does* give us is `lrcpc`/`ilrcpc` (FEAT_LRCPC2) and `lrcpc3`, and
only the first of those is currently worth anything — see below.

**FEAT_LRCPC2 is doing real work, measured.** FEX emits `LDAPUR`/`STLUR` for x86
loads and stores when the host reports it. Arm erratum 3877900 says those execute
with full Load-Acquire ordering on some cores, which would make the "fast" path
slower than a barrier; FEX disables it on eight Arm-designed cores for that
reason, and the blocklist is gated on `Implementer_ARM`, so Qualcomm keeps it.
Nobody had checked whether it should. `tools/tso/run.sh` checks:

| | x86-64 (FEX) | ARM64 (control) |
|---|---|---|
| default | **289.3 ms** | 279.5 ms |
| `FEX_HOSTFEATURES=disablelrcpc2` | **348.8 ms** | 279.3 ms |

Turning it off costs 21%, and the native control moved 0.2 ms — so that is the
ordering path and not thermals. Oryon does not have the erratum, FEX is right to
leave it enabled, and Vessel should not touch it.

**That 21% is LRCPC2's and nothing else's.** It was quoted for a second time
against `FEX_HALFBARRIERTSOENABLED`, in this document's settings table and in
`SessionEnvironment.kt`, where it was never measured. Two things were wrong with
that. FEX defaults `HalfBarrierTSOEnabled` to *true*
(`FEXCore/Source/Interface/Config/Config.json.in:462`), so Vessel's `=1` sets
what FEX already does and a `1`-versus-default comparison cannot move at all.
And the harness could not have measured it either way: `tsobench.c`'s loop was
single-byte accesses, every one of them naturally aligned, while the backpatch
this knob controls only ever fires on *unaligned* loads and stores. The
benchmark now has a second, deliberately misaligned phase and `run.sh` compares
`=0` against the default, which is the only pair with two sides. Until that runs
the knob has no number, and the table says so.

The same numbers say something else worth keeping: on this memory-ordering-bound
loop, translated x86-64 is within 4% of native ARM64. That is a workload chosen
to isolate barriers rather than a general claim about emulation speed, but it is
the shape the ARM64EC design was betting on.

**FEAT_LRCPC3 buys nothing today.** The CPU reports it and FEX detects it
(`Source/Common/HostFeatures.cpp:237`), but the field does not exist in the
struct that reaches codegen — the emitter has no RCPC3 encodings at all, and
`Addressing.cpp` says so outright. It would only matter for *vector* TSO, which
Vessel ships off, so there is nothing to gain until that changes.

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

Most of what used to be configurable is now fixed, because measurement kept
producing the same answer: there is one correct value and no user could reach a
better one by guessing. `build/targets/<target>.env` is build-time only. For
`canoe`:

| Setting | Value | Where it lives | Reason |
|---|---|---|---|
| `TU_DEBUG` | *(none forced)* | fixed | Neither rendering mode is forced — see below |
| `TU_AUTOTUNE_ALGO` | `default` | fixed | `prefer_sysmem` available if a title corrupts |
| `FEX_TSOENABLED` | `1` | fixed | **= FEX's default.** Required for correctness; off breaks multithreaded programs quietly |
| `FEX_HALFBARRIERTSOENABLED` | `1` | fixed | **= FEX's default.** Unmeasured here; see below |
| `FEX_VECTORTSOENABLED` | `0` | fixed | **= FEX's default.** Severe cost, and FEAT_LRCPC3 that would make it cheap is unused by FEX |
| `FEX_SILENTLOG` | `0` | fixed | The one FEX variable here that changes behaviour — without it a bad `FEX_HOSTFEATURES` token is silent |
| `FEX_OUTPUTLOG` | `stderr` | fixed | **Dead on Windows.** `Source/Windows/Common/Logging.cpp` reads only `SILENTLOG`; kept as a marker |
| `tu_override_uncached_as_cache_coherent` | `true` | fixed | Turnip driconf, set as an env var. FEX wants it and cannot deliver it — see below |
| Wine sync | esync | fixed | The only mode that works here — README, Known limitations |
| Resolution | `1280x720` | **container** | The single biggest performance dial on this phone |
| Frame rate limit | `60` | **container** | Thermal headroom over a long session |
| Extra DLL overrides | *(empty)* | **container** | The one escape hatch for a single misbehaving program |

Only the last three are in `app/src/main/assets/params-manifest.json` and only
those three appear in the editor. The fixed ones are set in
`SessionEnvironment.kt` beside `WINEESYNC` and are listed in
`RESERVED_SESSION_ENV`, so a manifest entry cannot reintroduce them — which also
means a container saved while they *were* switches cannot resurrect an old value.

**Three of the `FEX_*` rows set FEX's own defaults, and saying so is the point.**
`TSOEnabled` is `"Default": "true"`, `HalfBarrierTSOEnabled` `"true"`,
`VectorTSOEnabled` `"false"`, all in
`FEXCore/Source/Interface/Config/Config.json.in`. Vessel changes none of them.
They stay because a reader should be able to see what the runtime is without
knowing FEX's defaults by heart — but they must not be described as tuning, and
a measurement quoted against one of them is a measurement of nothing. The only
`FEX_*` variable here that changes FEX's behaviour is `SILENTLOG`.

**`tu_override_uncached_as_cache_coherent` is set because FEX asks for it and
cannot.** Emulated x86 turns guest stores into store-releases, which are
punishing on the uncached/write-combine memory a host-visible upload allocation
normally gets; the option makes Turnip return the cached-coherent memory type
instead (`tu_device.cc:1816`). FEX tries to set it through
`__wine_set_unix_env`, an ntdll export that **does not exist in Wine 11.14**, so
its call is guarded out and the option keeps its `false` default. FEX's own
comment suggests this exact workaround — set it in the launch script — and its
guard is `getenv(...) == nullptr`, so doing it here also keeps the two from
fighting if that export ever appears. Mesa picks up any driconf option from a
same-named environment variable (`util/xmlconfig.c:424`). **Unmeasured:** it
needs a real x86-64 D3D title, so no probe in `tools/gfx/` can attribute it.

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

Two consequences of the linker trick are easy to miss and both cost a day:

- `/proc/self/exe` becomes the **linker**, not the binary. Wine's installed
  loader (`tools/wine/wine.c`) derives its library directory from it, so it
  hunts for `ntdll.so` under `/apex/com.android.runtime/lib/wine` and dies.
  Setting **`WINEDLLPATH=<wine>/lib/wine`** — the loader's documented fallback —
  fixes it. Anything else that self-locates will need the same treatment.
- Exec is only permitted out of `app_data_file`. A binary sitting in
  `/data/local/tmp` cannot be run by the app even when it is world-readable:
  the linker reports `couldn't map "…" segment 3: Permission denied`.

### Executable pages must be anonymous

W^X on Android has a second edge that has nothing to do with `execve`, and it is
the one that actually stopped Wine.

Making a page executable takes SELinux `execute` on a clean file mapping, but
**`execmod` on a *modified* one** — and apps are not granted `execmod`. Parts of
the policy `dontaudit` it, so the denial never reaches logcat. Measured with
`tools/probe/mapexec.c`, running as the app, against a real Wine PE:

```
ok    mmap R  /  then mprotect RX
ok    MAP_FIXED RX over a PROT_NONE reservation
ok    clean private page -> RX
FAIL  DIRTIED private page -> RX (execmod): Permission denied (errno 13)
```

Writing one byte is the entire difference. Wine applies relocations to an image
*before* protecting its sections, so any PE loaded away from its `ImageBase`
dirties its own `.text` and can never execute it. That is exactly what happened:
`wineboot.exe` landed at its preferred base `0x140000000` and mapped `c-r-x`
fine, while `ntdll.dll` was relocated to `0x6fffe40000` and failed — leaving a
646-byte stub prefix and a crash the moment anything called into it.

`patches/wine/0002` forces Wine's existing `removable` path, which `pread()`s
each section into anonymous memory, where `PROT_EXEC` needs only `execmem` —
which apps do have, because every JIT on the platform needs it. The cost is
that image pages are private copies rather than shared, demand-paged file pages,
so a session's RSS is well above the on-disk size. There is no cheaper option:
anything that keeps the pages file-backed meets `execmod` again the moment a
relocation lands.

Upstream Wine blames this on a "noexec filesystem?", which on Android is simply
the wrong guess — `/data/user/0` is mounted `rw`, SELinux logs
`granted { execute }`, and the page size is 4096.

| Component | Kind | Delivery |
|---|---|---|
| Wine unix side (`wineserver`, `bin/wine`, `*-unix/*.so`) | host bionic ELF | `.wcp`, started via the system linker |
| Turnip (`libvulkan_freedreno.so` + `libc++_shared.so`) | Android Vulkan HAL, bionic ELF | `.wcp`; loaded by the platform Vulkan loader, which libadrenotools redirects at `filesDir` |
| libadrenotools + its hook objects | host bionic ELF | **inside the APK** — its hooks must sit in `nativeLibraryDir` and nowhere else works |
| FEX DLLs, DXVK, vkd3d, Wine's PE DLLs | Windows PE | `.wcp`; Android's loader never sees them — Wine opens them as data |

### Turnip needs adrenotools — measured, then fixed

This section once said "spike this before adopting adrenotools", on the
reasoning that Mesa's Turnip talks to `/dev/kgsl` directly and has no vendor-blob
dependency, so a plain `dlopen` might do. The answer is no, and the reason is
that Turnip is not an ICD at all.

`libvulkan_freedreno.so` exports exactly one symbol, `HMI` — the Android Vulkan
HAL module. Nothing can load it but Android's own Vulkan loader, and that loader
resolves its driver from `/vendor/lib64/hw/vulkan.*.so` through
`android_dlopen_ext` in a namespace an application cannot reach. So the driver
was inert, and nothing said so:

```
driver_id=8   driver="Qualcomm Technologies Inc. Adreno Vulkan Driver"
device="Adreno (TM) 829"   api=1.4.295   vendor=0x5143
```

**libadrenotools is now vendored into the APK** (`app/src/main/cpp/adrenotools/`,
BSD-2-Clause, commit `8fae8ce`). It loads a private, soname-patched copy of the
platform loader into a linker namespace of its own with an `android_dlopen_ext`
interposer preloaded in front of it, so the HAL that loader picks up is the
driver in app storage. Asking the same question again, on the same device, in a
process started the same way Wine is started:

```
driver_id=18  driver="turnip Mesa driver (whitebelyash branch)"
driverInfo="Mesa 26.3.0-devel (git-9c475fc367)"
device="Adreno (TM) 829 (unknown)"   api=1.4.358   vendor=0x5143
```

18 is `VK_DRIVER_ID_MESA_TURNIP`. That is our build answering, and it settles the
`freedreno-kmds=kgsl` question in `build/turnip.sh` at the same time: a physical
device enumerated, so KGSL alone is the right kernel-driver set.

Four things hold this up, and each of them fails silently if it is undone:

- **`jniLibs.useLegacyPackaging = true`.** libadrenotools loads its hook objects
  by name from `applicationInfo.nativeLibraryDir`, and with legacy packaging off
  that directory does not exist on disk.
- **`ANDROID_STL=c++_shared`.** `libadrenotools` and `libhook_impl` exchange a
  `std::string`-carrying struct across an `.so` boundary, and the driver
  namespace needs a real `libc++_shared.so` file to satisfy Turnip's own NEEDED
  entry. `build/turnip.sh` also ships a copy inside the `.wcp`, built by the same
  NDK as the driver, so the package does not depend on the app module's NDK pin.
- **`-z global` on the hook libraries.** It is what puts them in the namespace's
  preload list; without it the `android_dlopen_ext` override never takes effect.
- **`linkernsbypass` finding `__loader_dlopen`** by scanning forward from
  `&dlopen` for the first `BL`. An AArch64 assumption about bionic's code
  generation, verified on Android 16 / kernel 6.12.38. It fails closed.

**The guest reaches it through a Wine patch, not through the environment alone.**
`ADRENOTOOLS_DRIVER_PATH` and its two siblings have been in `SessionEnvironment`
for a while, and on their own they did nothing at all, because nothing read them:
win32u opens Vulkan with `dlopen(SONAME_LIBVULKAN)` and takes what answers.
`patches/wine/0006` makes `vulkan_init_once` try libadrenotools first when those
three variables are set, and report on the `winediag` channel either way — so a
session log now says which driver the guest got, in words, including when it got
the wrong one. DXVK and vkd3d reach Vulkan through winevulkan and therefore
through that same handle; there is no second path to patch.

`TU_DEBUG=startup` remains a second, independent ground truth, and its absence
from a session log is still a signal.

Two honest limits on what this buys today:

- Turnip is built `-Dplatforms=android`, so its WSI is `VK_KHR_android_surface`.
  Wine's X11 path wants `VK_KHR_xlib_surface`, which neither Turnip nor the stock
  driver offers here. Headless rendering — which is what the D3D11/D3D12 probes
  in `tools/device-graphics.sh` do — is unaffected; presentation still depends on
  the AHardwareBuffer path listed under "Still missing".
- The app process and the guest process load the driver independently. Two
  namespaces, two copies, one GPU. That is how libadrenotools works everywhere
  and it is fine, but it does mean the app's Drivers screen answering "Turnip" is
  evidence about the app, not proof about the session; the session log line is
  the proof about the session.

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
installs at runtime. This is the format the Winlator ecosystem already uses, so
our packages stay compatible with other apps and vice versa.

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

### Two sources, one installer

A package can reach the store two ways, and the store cannot tell which:

| Flavour | Where the `.wcp` comes from | Size |
|---|---|---|
| `sideload` | **inside the APK**, `assets/components/` | 133 MB installed APK |
| `play` | downloaded | 32 MB |

The `sideload` APK carries the whole set, so installing it is the whole of
setup: no side-loading a package by hand, no `tools/device-*.sh`, and no network
on first run. `app/build.gradle.kts` names the six builds it ships — a bill of
materials rather than a `dist/*.wcp` wildcard, because `dist/` accumulates
superseded packages and shipping `wine-10.13` beside `wine-11.14` would cost 66
MB of APK and 900 MB of unpacking for a Wine nothing adopts. They are stored
uncompressed (`androidResources.noCompress`), because a `.wcp` is already xz and
because an inflated asset cannot be read through `openFd`.

`play` ships none, and that is expressed as an absent asset directory rather
than as a flag: `BundledComponents` finds nothing there, the setup dialog never
appears, and the download path is the only source. Both paths go through the
same `WcpInstaller` into the same `components/<Type>/<versionCode>/`, which is
what makes a container's references resolve whichever way its components
arrived.

Unpacking is automatic, once, on first open — see `ComponentSetup`. It is
resumable per package because the installer stages and renames, and it decides
what to do by asking the filesystem whether a version is in the store, never by
reading a flag that a cleared app-storage would leave lying.

## Bootstrapping a prefix, in the one order that works

`SessionRuntime` owns this and nothing else may. The order is not arbitrary and
each step is here because doing it anywhere else broke something measurable:

```
wineserver -f -p                 ours, because Wine may not exec its own
wineboot --init                  creates system32 and the hives
  ↳ believe the hive, not the exit code — a first boot exits 53 by design
FEX -> system32                  after the boot, before the key that names it
regedit prefix-seed.reg
  ↳ flush, then grep system.reg for both emulator DLLs, or fail loudly
rm .update-timestamp; wineboot --update   ×2, and both are needed
DXVK / vkd3d / Zink -> prefix    after the last wine.inf pass
```

**`wineserver -w` cannot be used to flush the registry here, and this is the bug
that cost the most.** It reads like "wait for the registry to reach disk" and it
means "wait for every other `wineserver` to exit" — `wait_for_lock()` in
`server/request.c` takes an `F_SETLKW` on the master socket lock. This app
starts its own server `-f -p`, and `-p` is *persistent*: no idle timeout, so it
never exits and the wait never returns. Provisioning wedged in PREPARING with
`registrySeedVersion` already recorded, `regedit` never ran at all, and the
symptom surfaced minutes later as `xtajit.dll not found` — a Wine-looking error
with no Wine bug behind it. The reference scripts under `tools/` use
`wineserver -w` perfectly happily because they never start a persistent server:
`wineboot` auto-starts a transient one that exits three seconds after it goes
idle.

So the hive is flushed by ending the server and starting another;
`flush_registry()` runs on the way out. Measured on the device: `grep
libarm64ecfex system.reg` finds nothing while the server is up and finds it
immediately after `wineserver -k`.

**Two `wineboot --update` passes, each preceded by deleting
`.update-timestamp`.** `wineboot` compares that stamp against `wine.inf` and
skips its entire run when they match, which right after `--init` they do. One
forced pass measurably leaves `syswow64` empty and a second fills it with 885
entries; what the first establishes that the second needs is not understood, and
the honest response is to run both and say so. Without them there is no 32-bit
world: `syswow64` holds nothing, and every i386 program dies with `could not
load kernel32.dll`.

**The graphics layers go in last.** `tools/device-graphics.sh` warns that
`wine.inf` would otherwise put its builtins back over the top. That half is not
what happens — `create_dest_file` (`dlls/setupapi/fakedll.c`) refuses to
overwrite anything that is not one of Wine's own placeholders, verified on the
device where a 4 087 808-byte DXVK `d3d11.dll` survived a pass that created
`syswow64/kernel32.dll` beside it. The half that *is* real is simpler:
`syswow64` does not exist until the 32-bit pass has made it, so the 32-bit
payload was being written into a directory Wine had not created yet.

Every one of these steps has a timeout. The failure this whole section exists to
end was a step that hung rather than one that errored, and a step that hangs is
a step that has failed slowly.

## Input and audio leave the process the same way: through the app

Two seams that look unrelated share a cause. This is an `untrusted_app`, and the
two obvious Linux answers — open a device node, open a sound device — are both
closed to it. In each case the app already holds an Android API that can do the
job, so the guest is given a socket to the app rather than a device to open.

**Audio: `wineoss.drv` on AAudio.** `patches/wine/0008` replaces the unix half of
Wine's OSS driver with AAudio, the NDK's only low-latency output. `mmdevapi`
probes `oss` by default, so no registry key or override selects it — a guest that
opens WASAPI, DirectSound or winmm lands here without knowing.

The trap worth remembering: **AudioFlinger's start threshold is the buffer size.**
A track whose queue never reaches it never starts, `framesRead` never moves, and
the driver's ring appears to stall with no error anywhere. Sizing the shared
buffer to `min(client, device)` is what makes a guest with a large period audible.

Vessel applies **no attenuation of its own**. The guest outputs at full scale and
Android's volume keys own the rest; two gain stages in series make a slider that
does not mean anything.

**Gamepads: the app is the bus.** `/dev/input` is `root:input` 0660 and this
process is in neither group, so SDL, udev and libusb can never enumerate a
controller — the missing piece is not a driver but a *permission*, and no Wine
backend can earn it. Android's `InputDevice` API can see the pad, and it lives in
the app.

`patches/wine/0016` therefore adds a `winebus` backend whose device nodes are
frames on a unix socket, in the same shape as `patches/wine/0005`'s MIT-SHM
channel: a path in an environment variable, absent when the app opened none, in
which case the backend reports itself unimplemented and winebus behaves exactly
as before. Frames are a fixed 20 bytes both ways, so a short read is a torn frame
rather than a desynchronised stream.

What the guest gets is a **real HID device**, not an XInput shim: vid 045e, pid
028e — the wired Xbox 360 pad — which `winexinput.sys` attaches to, DirectInput
and winmm enumerate, and which carries force feedback back down the same socket
onto the controller's own motors via `InputDevice.getVibrator`. A glass overlay
and a physical pad are merged into one report before it goes on the wire, because
they are one controller to the player: buttons union, and each axis takes
whichever source is further from centre.

**Assembling a HID report is not delivering it.** `hid_device_sync_report` only
swaps the report against the previous one and answers whether it changed; every
backend follows it with `bus_event_queue_input_report`. Without that call a pad
enumerates perfectly, answers all three APIs, and reads dead centre forever.

## Roadmap

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Repo, pipeline, Docker toolchain | **Done** |
| 2 | ~~Custom Box64~~ | **Removed** — no x86-64 Wine for it to run |
| 3 | Custom FEX PE DLLs | **Done** — CHPE-verified ARM64EC |
| 4 | Custom Turnip gen8 + matched DXVK/vkd3d | **Done** — a829 support confirmed in the binary |
| 5 | Custom Wine ARM64EC | **Done** — upstream 11.14, packaged and verified |
| 6 | App shell: containers, components, diagnostics | **Done** for everything not needing a session |
| 7 | Session launcher | In progress |
| 8 | Display backend (X server) | In progress |
| 9 | Benchmark harness | Not started; needs a running session to measure |

On the device itself, `tools/device-smoke.sh` has taken the stack as far as
`wine --version` answering from inside the app's own uid and sandbox. Building a
prefix needs the `execmod` fix above, which is why phase 5 was rebuilt.

Phases 2–5 each produce an installable `.wcp` whose provenance records the
source commit and the exact compiler flags used, so any claim this project makes
about a component can be checked against the package rather than taken on trust.

## Components are shared, not copied per container

Measured 2026-08-07, once a full set of packages existed:

| Component | Download | On disk |
|---|---|---|
| Wine | 63.1 MB | **912 MB** |
| DXVK | 3.4 MB | 23 MB |
| Zink | 3.0 MB | 20 MB |
| Turnip | 1.6 MB | 14 MB |
| vkd3d | 2.6 MB | 10 MB |
| FEX | 1.1 MB | 9 MB |
| | **75 MB** | **~988 MB** |

Components used to live at `containers/<id>/components/`, so three containers on
one Wine build meant three byte-identical 912 MB trees. They now install once to
`components/<type>/<versionCode>/` and containers reference them:

```
filesDir/
  components/<Type>/<versionCode>/     payload + profile.json, shared
  components/<Type>/<versionCode>.json ComponentRecord (registry package id)
  components/.staging/                 install staging, same filesystem
  containers/<id>/                     prefix/ tmp/ provisioned.json
```

A container's references are the `type -> versionCode` entries in its own
`provisioned.json`. The count is taken from **disk**, over every directory under
`containers/`, not from the container database — a container directory that
outlives its document entry still has a prefix expecting those components.
`prune()` deletes only versions nothing references, and nothing calls it
automatically; deleting a container frees the reference but never the bytes.

`ContainerPaths` remains the only place that knows this layout. Devices built
before the change are migrated by **moving** the per-container copies into the
store, keyed from `provisioned.json` first and the payload's own `profile.json`
second. A directory that can be keyed by neither is left alone and reported, not
deleted — up to 912 MB the user paid a download for is not something to discard
because this code could not read it.

The per-container layout was not wrong when written; components were assumed
small. Wine at 912 MB changed the answer, and that was only visible once a real
package existed to measure.

## The display path: a vendored Java X server

Wine needs an X server. There is no X server on Android, and Wine's own
`wineandroid.drv` is switched off in `build/wine.sh` for reasons recorded there,
so something has to answer on `/tmp/.X11-unix/X0` inside the container.

That something is Winlator's X server, vendored wholesale into
`app/src/main/java/com/winlator/` at commit `ca3d735`. It is LGPL-2.1;
`app/src/main/java/com/winlator/README.md` records what was taken, what was
left, and every local change. Writing one instead was never seriously on the
table — this is ~10k lines of Java plus ~2.9k of JNI that already work against
`winex11.drv` specifically, which is a much narrower and better-tested target
than "the X protocol".

### Why a Java X server is not the slow choice it sounds like

The instinct is that a Java X server means CPU-copying every frame. It does
not, because the frames do not go through the protocol at all:

- A window's backing store is a `GPUImage`, which is an `AHardwareBuffer`
  imported as an `EGLImageKHR` and bound with `glEGLImageTargetTexture2DOES`.
- DRI3 `BufferFromPixmap` hands the guest that buffer's **dma-buf fd** over
  SCM_RIGHTS. DXVK renders into it directly, on the GPU, in the guest process.
- `GLRenderer` then composites the window textures into one `GLSurfaceView`
  pass.

So the X protocol carries geometry, input and damage; the pixels never cross it.
The CPU path (`Drawable.drawImage`, the `PutImage` blitter in `drawable.c`) is
the 2D fallback for GDI, not the game path.

### What we did not need, and why

**No Vortek.** Vortek exists because box64 runs a glibc x86-64 Wine, and glibc
code cannot call bionic's Vulkan driver; Vortek marshals Vulkan over a socket to
a native helper that can. Vessel's Wine is bionic-native ARM64EC, so it calls
`libvulkan.so` directly. The problem Vortek solves does not occur here.

**No GLX.** Upstream's `GLXExtension` is a dispatcher onto `libgladiorenderer`,
a GL-over-a-socket translator that exists because Winlator's guest has no real
`opengl32`. Vessel builds Mesa/Zink as an ARM64EC `opengl32.dll`, so the guest
resolves GL in-process and never asks X for a GLX visual. The extension is
simply absent from the advertised set, which winex11 handles — as it does for
XFixes, XInput2, RandR, RENDER and SHAPE, all of which are also missing and all
of which winex11 treats as optional.

**No `android_sysvshm` glibc patch.** See below.

### Shared memory on bionic: MIT-SHM stays on, and was never SysV

The framing "bionic has no shmget, so MIT-SHM must be shimmed or disabled" turns
out to be wrong in both halves, and the truth is more convenient.

bionic *does* export `shmget`/`shmat`/`shmdt`/`shmctl` from API 26. The NDK's
own `sys/shm.h` documents them as "Not useful on Android because it's disallowed
by SELinux" — they link, and they fail at runtime with EACCES. So the failure is
silent at build time, which is the part worth knowing.

But even if SELinux allowed it, it would not help. A SysV `shmid` names a
segment in the kernel's IPC namespace; a Java X server can only `mmap` a file
descriptor. There is no way for it to attach to a segment another process
created by key. **Any** MIT-SHM implementation living in an Android app has to
be fd-passing, whatever the client believes it is doing.

Which is exactly what the vendored `com.winlator.sysvshm` already is: a unix
socket on `/tmp/.sysvshm/SM0` where SHMGET allocates an ashmem region
(`ASharedMemory_create`, with a memfd fallback Vessel added) and GET_FD returns
the descriptor as an SCM_RIGHTS ancillary message. Nothing in it calls
`shmget`. Winlator needs a glibc patch for the *client* half — interposing the
guest's `shmget` onto that socket — and that patch is glibc-specific, which is
the only reason it appears on the "not vendored" list.

So MIT-SHM is enabled, and the extension is complete on the server side.

**What is missing is the client half.** Wine's `winex11.drv` calls `shmget`
directly in `create_shm_image` (`dlls/winex11.drv/bitblt.c`), guarded by
`HAVE_LIBXXSHM`, which our X11 sysroot satisfies. On bionic that call returns
-1, Wine reads that as "XShm unavailable" and falls back to `XPutImage`. That is
correct, and costs one copy per damaged region — a 2D/GDI cost, not a game one.
Making it fast means teaching winex11 to talk to the sysvshm socket instead: a
Wine patch under `patches/wine/`, not something the display backend can do from
its side. Not written yet.

### Still missing

*The "host is not wired up" bullet that stood here until 2026-08-11 described
the day the backend was vendored. `XServerDisplay` has been the real binding for
`SessionDisplayServer` since; sessions run, draw and take input through it.*

- **The AHardwareBuffer/DAC present layer for DXVK is out of scope here and not
  done.** The X server side exists — DRI3 hands out the buffer fd and Present
  routes the flip — but nothing yet drives a real swapchain from DXVK's
  `VK_KHR_present_wait`/DAC path onto that buffer with correct fencing. Until
  then, expect presentation to be composited rather than flipped.
- **No guest-side helper.** `com.winlator.winhandler.WinHandler` is an interface
  with a no-op implementation. Relative mouse mode and window activation from
  the X side into Win32 are therefore inert. The integration point is
  `XServer.setWinHandler()`.
- **No XFixes.** winex11 uses it for cursor images and region ops when present;
  without it the pointer is the server's own cursor. Fine for now, wrong for
  applications that set custom cursors.
