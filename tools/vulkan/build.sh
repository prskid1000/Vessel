#!/usr/bin/env bash
# Build the standalone Vulkan driver probe and the libadrenotools libraries it
# needs, for arm64 Android. Runs inside the Docker build image.
#
#   docker run --rm -v "$PWD:/src" -v vessel-work:/work vessel-build \
#       ./tools/vulkan/build.sh
#
# Output: out/vulkan/{vkdriverprobe,libadrenotools.so,libhook_impl.so,
#                     libmain_hook.so,libfile_redirect_hook.so,
#                     libgsl_alloc_hook.so,libc++_shared.so}
#
# These are the SAME sources the APK compiles (app/src/main/cpp/adrenotools and
# app/src/main/cpp/vessel), built here with the component NDK instead of
# Gradle's. The point is to be able to run them in a plain exec'd process — the
# shape the Wine unix side runs in — which no Gradle output can be.
#
# Built with a shell script rather than CMake on purpose: the CMake here is
# driven by Gradle's Android toolchain file and reproducing that outside Gradle
# is more moving parts than seven compiler invocations.

. "$(dirname "${BASH_SOURCE[0]}")/../../build/common.sh"

vessel_init
setup_ndk

CPP="$REPO_ROOT/app/src/main/cpp"
AT="$CPP/adrenotools"
OUT="${VESSEL_PROBE_OUT:-$REPO_ROOT/out/vulkan}"

[ -d "$AT" ] || die "no vendored libadrenotools at $AT"

rm -rf "$OUT"
mkdir -p "$OUT"

# c++_shared, not c++_static, and the .so is shipped beside the rest. Two
# reasons, both load-bearing:
#   - libadrenotools and libhook_impl pass a std::string-carrying struct
#     (HookImplParams) across an .so boundary, so they must agree on one libc++.
#   - the driver namespace libadrenotools creates uses hookLibDir as its default
#     library path, and the Turnip build has libc++_shared.so in its NEEDED list.
#     Without a copy the driver dlopen fails with "library not found" and the
#     stock blob answers instead.
STL_DIR="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"
[ -f "$STL_DIR/libc++_shared.so" ] || die "no libc++_shared.so in $STL_DIR"
install -m 0644 "$STL_DIR/libc++_shared.so" "$OUT/libc++_shared.so"

COMMON_FLAGS="-O2 -fPIC -fvisibility=hidden -Wall -Wextra"
AT_INCLUDES="-I$AT/include -I$AT/lib/linkernsbypass -I$AT/src/hook"

log "building libadrenotools for $TARGET_ABI (api $NDK_API)"

# linkernsbypass, compiled into both consumers exactly as upstream's CMake does
# (a static library linked into libadrenotools and libhook_impl).
"$NDK_CXX" $COMMON_FLAGS $AT_INCLUDES -c "$AT/lib/linkernsbypass/android_linker_ns.cpp" -o "$OUT/ns.o"
"$NDK_CXX" $COMMON_FLAGS $AT_INCLUDES -c "$AT/lib/linkernsbypass/elf_soname_patcher.cpp" -o "$OUT/soname.o"

"$NDK_CXX" $COMMON_FLAGS $AT_INCLUDES -shared -o "$OUT/libhook_impl.so" \
  "$AT/src/hook/hook_impl.cpp" "$OUT/ns.o" "$OUT/soname.o" -llog

# -fvisibility=default for this one, overriding COMMON_FLAGS. Upstream's CMake
# sets CXX_VISIBILITY_PRESET hidden on the hook targets only, and adrenotools'
# own entry points carry no visibility attribute — so building it hidden
# produces a .so whose only defect is that `dlsym(h, "adrenotools_open_libvulkan")`
# returns NULL, which reads exactly like a missing library.
"$NDK_CXX" $COMMON_FLAGS -fvisibility=default $AT_INCLUDES -shared -o "$OUT/libadrenotools.so" \
  "$AT/src/driver.cpp" "$OUT/ns.o" "$OUT/soname.o" -landroid -llog

# -z global is the mechanism, not an optimisation: it is what puts these into the
# namespace's preload list when adrenotools dlopens them RTLD_GLOBAL, and without
# it the android_dlopen_ext override never takes effect.
for hook in main_hook file_redirect_hook gsl_alloc_hook; do
  "$NDK_CC" $COMMON_FLAGS $AT_INCLUDES -shared -Wl,-z,global \
    -o "$OUT/lib$hook.so" "$AT/src/hook/$hook.c" \
    -L"$OUT" -l:libhook_impl.so
done

log "building the probe"
"$NDK_CC" $COMMON_FLAGS -fvisibility=default \
  -I"$CPP/vessel/include" -I"$AT/include" \
  -o "$OUT/vkdriverprobe" \
  "$REPO_ROOT/tools/vulkan/driverprobe.c" "$CPP/vessel/src/vulkan_driver.c" \
  -ldl -llog

rm -f "$OUT/ns.o" "$OUT/soname.o"

for f in vkdriverprobe libadrenotools.so libhook_impl.so libmain_hook.so \
         libfile_redirect_hook.so libgsl_alloc_hook.so libc++_shared.so; do
  [ -f "$OUT/$f" ] || die "build produced no $f"
done

# The probe is exec'd on the device, so it must be a real executable rather than
# the PIE-shared-object-with-an-interpreter that a stray -shared would make.
file "$OUT/vkdriverprobe" | grep -q 'ARM aarch64' \
  || die "vkdriverprobe is not an aarch64 binary: $(file -b "$OUT/vkdriverprobe")"

ok "out/vulkan: $(ls "$OUT" | tr '\n' ' ')"
