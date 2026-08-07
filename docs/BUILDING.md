# Building

Every native component builds inside the pinned Docker image. Nothing needs to
be installed on the host except Docker and git.

## Quick start

```bash
git clone <this repo> vessel && cd vessel
docker build -t vessel-build .

# Object files go in a named volume, not the bind mount. This is not optional
# on Windows — see "Why the volume" below.
docker volume create vessel-work

docker run --rm \
  -v "$PWD:/src" \
  -v vessel-work:/work \
  vessel-build ./build/box64.sh
```

The result lands in `dist/`:

```
dist/box64-0.4.4-canoe.wcp
dist/box64-0.4.4-canoe.wcp.sha256
```

One script per component, all with the same shape:

| Script | Produces | Toolchain | Rough time |
|---|---|---|---|
| `build/box64.sh` | Box64 ELF | NDK | ~4 min (verified) |
| `build/fex.sh` | `libarm64ecfex.dll`, `libwow64fex.dll` | llvm-mingw | ~15 min |
| `build/turnip.sh` | `libvulkan_freedreno.so` | NDK | ~10 min |
| `build/dxvk.sh` | DXVK PE DLLs | llvm-mingw | ~10 min |
| `build/vkd3d.sh` | vkd3d-proton PE DLLs | llvm-mingw | ~10 min |
| `build/wine.sh` | Wine tree | llvm-mingw | an hour or more |

## On Windows

Docker Desktop with the WSL2 backend, from PowerShell:

```powershell
docker build -t vessel-build .
docker volume create vessel-work
docker run --rm -v "${PWD}:/src" -v vessel-work:/work vessel-build ./build/box64.sh
```

### Why the volume

`/src` is a bind mount onto NTFS. Reading source across it is tolerable;
writing tens of thousands of object files across it is not, and turns a ten
minute Mesa build into an hour. `VESSEL_WORK_DIR=/work` is a Linux-native
volume, so all intermediate output stays on a Linux filesystem and only the
final `.wcp` crosses back.

If you prefer not to use Docker, WSL2 works — but clone into the WSL
filesystem (`~/vessel`), not `/mnt/c`, for the same reason.

## Changing a version

`native/pins.env` is the only place versions live:

```diff
-FEX_REF=FEX-2608
+FEX_REF=FEX-2609
```

Commit that, and CI builds the new component and publishes it. There is no
second place to update, and no APK rebuild — the phone picks it up from the
component registry.

## Changing chip tuning

`build/targets/canoe.env` holds everything device-specific: ISA flags, GPU
identity, page size, and the runtime defaults baked into new containers.
Supporting a second device means adding `build/targets/<name>.env` and running
with `VESSEL_TARGET=<name>`; no build script changes.

Tuning flags are **probed, not assumed**. If a toolchain rejects
`-mcpu=oryon-1`, the build falls back and prints what it actually used. A build
that quietly loses its chip tuning would be worse than one that fails, so this
is deliberately noisy.

This is not hypothetical. NDK **r28c advertises clang 19.0.1 and still rejects
`-mtune=oryon-1`**, because Android builds clang from an LLVM snapshot that
predates the `oryon-1` definition — its newest known core is `cortex-x4`. A
version check passes there and the build then fails at compile time, which is
why both the Dockerfile and `common.sh` test the flag rather than the number.
r29 (clang 21.0.0) accepts it. Do not "roll back to an older NDK" without
re-probing.

There are two API-level variables and they are not interchangeable:
`TARGET_API` is what the device runs (36); `TARGET_NDK_API` is what native code
compiles against (35, because no NDK ships an API 36 sysroot yet). Requesting a
level the NDK lacks fails with the available list.

## Patching a component

Put a `.patch` in `patches/<component>/`; they apply in filename order on top
of the pinned ref. A patch that does not apply is a hard error, because a
half-patched build is not reproducible.

This is also a license obligation: Wine and vkd3d-proton are LGPL, so our
modifications must remain publicly available. Keeping every change as a patch
file in this repo is how that is satisfied. See [LICENSING.md](LICENSING.md).

## Building the app

Standard Android project. The JDK bundled with Android Studio works and is the
easiest route — verified with its **JDK 21**. A bare `java -version` on this
host reports 25, which AGP 8.9 does not support, so set `JAVA_HOME` explicitly:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleSideloadDebug
```

```bash
JAVA_HOME=/c/'Program Files'/Android/'Android Studio'/jbr ./gradlew :app:assembleSideloadDebug
```

`local.properties` needs `sdk.dir`; it is gitignored, so a fresh clone must
create it (or open the project once in Android Studio). Requires platform
android-36 and build-tools 36.

## Verifying on device

```bash
adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
adb push dist/box64-0.4.4-canoe.wcp /sdcard/Download/
```

Then install the package from the Components screen (Settings ▸ Components). A
component built for the wrong architecture fails silently at load time in most
Winlator-family apps — Vessel's driver and component screens read back what
actually loaded, so check there rather than assuming.

### Checking a component without the app

For anything that is a plain ELF, the fastest confidence check is to run it
directly. Box64, for example:

```powershell
adb push out\box64 /data/local/tmp/box64
adb shell "chmod 755 /data/local/tmp/box64; /data/local/tmp/box64 --version"
# Box64 arm64 v0.4.4 with Dynarec built on ...
```

Better still, prove it *translates*. Any statically linked x86-64 binary the
phone cannot run natively will run under it — the container image is amd64, so
one is a `gcc -static` away:

```bash
adb shell /data/local/tmp/x86test          # not executable: 64-bit ELF file
adb shell "cd /data/local/tmp && ./box64 ./x86test"   # runs
```

`lscpu: inaccessible or not found` on stderr is normal and harmless; Box64
probes for it at startup.
