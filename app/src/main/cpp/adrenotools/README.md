# Vendored code — libadrenotools

Everything under this directory is **not Vessel's code**. It is vendored from
libadrenotools and its `linkernsbypass` submodule, with upstream's directory
layout, file names and copyright headers intact so that upstream diffs stay
applyable by hand.

| | |
|---|---|
| Upstream | <https://github.com/bylaws/libadrenotools> |
| Commit | `8fae8ce` ("Update linkernsbypass") |
| Submodule | `lib/linkernsbypass` — <https://github.com/bylaws/liblinkernsbypass> @ `aa39758` |
| Author | Billy Laws (bylaws) and contributors |
| Licence | **BSD-2-Clause** — `LICENSE` here, and `lib/linkernsbypass/LICENSE` |
| Vendored on | 2026-08-08 |

The pin is restated in `native/pins.env` so that one file answers "what versions
does Vessel build?" for vendored code as well as fetched code.

## Why this is in the APK and not a `.wcp`

Every other native component Vessel builds is downloaded at runtime. This one
cannot be, and the reason is a property of the loader rather than a policy:

- libadrenotools loads `libmain_hook.so` and `libhook_impl.so` **by name** out of
  the directory it is handed, and upstream's header says in capitals that the
  directory must be `applicationInfo.nativeLibraryDir`.
- That same directory becomes the *default library path* of the linker namespace
  the GPU driver is loaded into, which is how a dependency of the driver that is
  not a system library resolves at all.

There is no directory in app data with those properties. It also has to be there
before any component is installed, because a Vulkan driver with nothing to load
it is exactly the state this code exists to end.

## Why it is needed at all

Mesa's Turnip builds for Android as a **Vulkan HAL**, not an ICD:
`libvulkan_freedreno.so` exports exactly one symbol, `HMI`. Only Android's own
Vulkan loader can load it, and that loader resolves its driver from
`/vendor/lib64/hw/vulkan.*.so` through `android_dlopen_ext` in a namespace an
application cannot reach. libadrenotools loads a private, soname-patched copy of
the platform loader into a namespace of its own with an `android_dlopen_ext`
interposer preloaded in front of it, so the HAL that loader picks up is the
driver in app storage.

Measured on this device before it existed, with the Turnip package installed:
`driverID 8`, `"Qualcomm Technologies Inc. Adreno Vulkan Driver"`. After:
`driverID 18` (`VK_DRIVER_ID_MESA_TURNIP`), `"Mesa 26.3.0-devel (git-9c475fc367)"`.

## What was taken

- `include/adrenotools/{driver.h,priv.h}` — the API Vessel calls.
- `src/driver.cpp` — `adrenotools_open_libvulkan` and the KGSL helpers.
- `src/hook/` — the `android_dlopen_ext` interposer (`main_hook.c`), its
  implementation (`hook_impl.cpp`), the two optional-feature hooks
  (`file_redirect_hook.c`, `gsl_alloc_hook.c`) and `kgsl.h`.
- `lib/linkernsbypass/` — the linker-namespace bypass the whole thing rests on.

## What was deliberately not taken

| Upstream component | Why not |
|---|---|
| `src/bcenabler.cpp`, `gen/bcenabler_patch.h`, `src/bcenabler_patch.s`, `build_asm.sh` | Enables BCn texture support by patching the **stock Qualcomm blob's** machine code. Vessel replaces that driver rather than patching it, and Turnip already exposes BCn. It is also the only part of upstream that rewrites vendor code, which is not something to ship without needing it. |
| `tools/qtimapper-shim`, `tools/acc-shim`, `tools/blob-patcher.py` | Build-host and vendor-image tooling, nothing an app links. |
| Upstream `CMakeLists.txt` | Replaced — see the comment at the top of the local one. Upstream's builds `adrenotools` as a static library unless `BUILD_SHARED_LIBS` is set, and Vessel needs it shared so the Wine unix side can `dlopen` it. |

## Local modifications

**None to any upstream source file.** The only local file is `CMakeLists.txt`,
which is a replacement rather than an edit, and this README. If a change to
upstream code ever becomes necessary, mark it `// VESSEL:` in-source and list it
here, as `app/src/main/java/com/winlator/README.md` does.

## Things worth knowing before touching this

- **`jniLibs.useLegacyPackaging = true` is load-bearing**, and it is set in
  `app/build.gradle.kts`. Without it the `.so` files are mapped straight out of
  the APK, `nativeLibraryDir` names a directory that does not exist, and both
  bullet points at the top of this file stop being true. The failure is silent.
- **`ANDROID_STL=c++_shared` is load-bearing too.** `libadrenotools` hands
  `libhook_impl` a struct containing `std::string`s across an `.so` boundary, so
  they must share one libc++; and the driver namespace needs a real
  `libc++_shared.so` file in `nativeLibraryDir`, which `c++_static` would not
  produce.
- **The hook libraries' file names are an API.** `driver.cpp` asks for
  `libhook_impl.so` and `libmain_hook.so` by string, and `hook_impl.cpp` asks for
  `libfile_redirect_hook.so` and `libgsl_alloc_hook.so`. Renaming a CMake target
  breaks the load with nothing but a logcat line.
- **`-z global` on the hook libraries is the mechanism**, not an optimisation. It
  is what puts them in the namespace's preload list when they are opened
  `RTLD_GLOBAL`; without it the `android_dlopen_ext` override never takes effect
  and the stock driver loads as if nothing had been asked for.
- **`linkernsbypass` finds `__loader_dlopen` by disassembling `dlopen`.** It
  scans forward from `&dlopen` for the first `BL` instruction and follows it.
  That is an AArch64-specific assumption about bionic's code generation, and it
  is the part most likely to break on a future Android release. It fails closed —
  `linkernsbypass_load_status()` returns false and
  `adrenotools_open_libvulkan` returns NULL — and every caller in Vessel reports
  that rather than falling through quietly. Verified working on Android 16
  (kernel 6.12.38), 2026-08-08.
