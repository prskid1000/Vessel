#!/usr/bin/env bash
# Build Mesa's Turnip Vulkan driver for Adreno gen8 and package it as a .wcp.
#
# The stock Qualcomm driver on this device reports Vulkan 1.4 but is missing
# extensions DXVK 2.x needs, which is the whole reason Turnip is here. Turnip
# is a bionic ELF loaded by libadrenotools at runtime, so it is built with the
# NDK — not llvm-mingw — and packaged in the adrenotools layout (the driver
# .so plus a meta.json beside it).
#
#   ./build/turnip.sh           # -> dist/turnip-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk

COMPONENT=turnip
# The tree is all of Mesa; only the freedreno Vulkan driver is built out of it.
# native/ and patches/ are keyed on the source name, the package on the driver.
SOURCE_NAME=mesa
COMPONENT_REF="$MESA_REF"

# --- ICD mode ------------------------------------------------------------------
#
#   ./build/turnip.sh                        # -> dist/turnip-<ver>-icd-<target>.wcp
#   VESSEL_TURNIP_HAL=1 ./build/turnip.sh    # -> dist/turnip-<ver>-<target>.wcp
#
# **The ICD is the default, and it is the default because it is what ships.**
# app/build.gradle.kts names `turnip-<ver>-icd-<target>.wcp` in its bill of
# materials -- the APK carries the ICD and nothing else. This script defaulting
# to the HAL meant a plain `./build/turnip.sh` produced a package the APK does
# not reference: the build succeeded, dist/ gained a new file, the version code
# went up, and the phone kept running the previous ICD. That happened, and it
# cost a device session testing a driver change that was never installed. A
# default that silently builds the thing nobody ships is a trap, so the default
# moved rather than the person being expected to remember.
#
# The HAL is still buildable and still described below; it is now opt-in.
#
# The Android Vulkan HAL: `-Dplatforms=android,x11` makes
# Mesa export exactly one dynamic symbol, `HMI`, so the only thing that can load
# the driver is Android's own `libvulkan.so`, with libadrenotools redirecting its
# HAL lookup. That is what ships, and for headless rendering it is correct and
# proven — D3D11 passes its pixel readback through it.
#
# It cannot present to an X server, and the reason is structural rather than a
# missing build flag. Android's platform loader does not forward the WSI: it
# implements `vkGetPhysicalDeviceSurfaceCapabilitiesKHR`, `vkCreateSwapchainKHR`
# and the rest itself, against its own `Surface` struct wrapping an
# `ANativeWindow`. Hand it a `VkSurfaceKHR` that Turnip's `vkCreateXcbSurfaceKHR`
# made and it casts the handle to the wrong type. Measured on the device with
# `tools/gfx/x11present.c`, which gets as far as
#     VESSEL-X11PRESENT surface created
#     VESSEL-X11PRESENT queue 0 graphics=1 present=1
# and then takes SIGSEGV at a null dereference inside
# `/memfd:/system/lib64/libvulkan.so`, two frames below the probe — the loader,
# not the driver. The X11 WSI is compiled into the shipped driver (`xcb_put_image`
# and the whole `xcb_randr_*` set are in its symbol table); nothing can reach it.
#
# Dropping `android` from `-Dplatforms` makes Mesa emit an ordinary Vulkan ICD —
# `vk_icdGetInstanceProcAddr`, `vk_icdNegotiateLoaderICDInterfaceVersion` and
# `vkGetInstanceProcAddr` as real exports — which a plain `dlopen` can drive with
# no Android loader in the path at all. Turnip talks to `/dev/kgsl-3d0` directly
# and needs nothing from the HAL, so the driver underneath is the same driver.
#
# Kept as a flag rather than a replacement because the two builds are not
# interchangeable: the ICD has no `VK_ANDROID_external_memory_android_hardware_buffer`
# and nothing in the app can load it through `AdrenotoolsManager`. Which one a
# session should use is a decision for whoever wires it up, and it wants both
# packages present to be able to compare them.
#
# The cross file's `system` moves with it, and that is not cosmetic. Mesa keys
# `DETECT_OS_ANDROID` off it, and `vk_enum_defines.h` then includes
# `vk_android_native_buffer.h`, which includes `<cutils/native_handle.h>` —
# a header only `-Dandroid-stub=true` supplies, and that option is refused
# without `platforms=android`. So an Android-system ICD build cannot compile:
#   include/vulkan/vk_android_native_buffer.h:22:10:
#     fatal error: 'cutils/native_handle.h' file not found
# `system = 'linux'` is also the truthful description of what this build is: a
# normal Linux Vulkan ICD that happens to be linked against bionic and talks to
# /dev/kgsl. It is the shape Termux's freedreno ICD package already ships.
# VESSEL_TURNIP_ICD is still honoured so an existing command line keeps working;
# it is now the default rather than the switch, and VESSEL_TURNIP_HAL is what
# turns it off.
if [ "${VESSEL_TURNIP_HAL:-0}" = "1" ] || [ "${VESSEL_TURNIP_ICD:-1}" = "0" ]; then
  TURNIP_PLATFORMS=android,x11
  TURNIP_VARIANT=hal
  TURNIP_MESON_SYSTEM=android
else
  TURNIP_PLATFORMS=x11
  TURNIP_VARIANT=icd
  TURNIP_MESON_SYSTEM=linux
fi

fetch_source "$SOURCE_NAME" "$MESA_REPO" "$MESA_REF" "${MESA_SHA:-}"

SRC="$NATIVE_DIR/$SOURCE_NAME"
BUILD="$WORK_DIR/$COMPONENT-$TURNIP_VARIANT"
STAGE="$WORK_DIR/stage-$COMPONENT-$TURNIP_VARIANT"
SYSROOT="$WORK_DIR/androidsysroot"
rm -rf "$BUILD" "$STAGE"
mkdir -p "$STAGE"

# --- X11, for the WSI ----------------------------------------------------------
# Turnip is built for two window systems, not one. `android` is what the plain
# Vulkan probe goes through and is the only thing proven to work on this device,
# so it stays. `x11` is added because Wine's winex11.drv can only advertise
# VK_KHR_win32_surface to DXVK if the implementation underneath offers
# VK_KHR_xlib_surface or VK_KHR_xcb_surface, and an android-only Turnip offers
# neither — which is why every D3D probe dies in vkCreateInstance.
#
# Same sysroot Wine links against; no-op when it is already built to the pins.
"$COMMON_SH_DIR/x11-sysroot.sh" \
  || die "x11-sysroot.sh failed; Turnip cannot be built with X11 WSI without it"
for pc in xcb xcb-dri3 xcb-present xcb-sync xcb-shm xcb-xfixes xcb-randr \
          x11-xcb xshmfence; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] \
    || die "x11-sysroot.sh left no $pc.pc in $SYSROOT/usr/lib/pkgconfig — Mesa's
     x11 platform needs all of xcb, xcb-dri3, xcb-present, xcb-sync, xcb-shm,
     xcb-xfixes, xcb-randr, x11-xcb and xshmfence."
done

# MESA_REF is a branch, not a tag: gen8 Vulkan is out-of-tree and moves weekly.
# Mesa's own VERSION file only says something like "26.0.0-devel", which is the
# same string for months, so SOURCE_SHA is the real version identity here and
# is carried in both the package version and the provenance.
MESA_VERSION="$(tr -d '[:space:]' < "$SRC/VERSION" 2>/dev/null || true)"
if [ -z "$MESA_VERSION" ]; then
  warn "no VERSION file in $SRC; naming the package from the commit alone"
  MESA_VERSION="$TARGET_GPU_GEN"
fi
VERSION="$MESA_VERSION-${SOURCE_SHA:0:8}"
[ "$TURNIP_VARIANT" = icd ] && VERSION="$VERSION-icd"

resolve_cpu_flags "$NDK_CC"

# tu_device.cc includes tu_version.h, which is NOT committed to this branch —
# every community builder generates it before configuring. Our current pin
# happens to build without it, so this only writes the file when it is missing:
# if a future pin reintroduces the include, the build keeps working instead of
# failing on a header nobody can find in the tree.
TU_VERSION_H="$SRC/src/freedreno/vulkan/tu_version.h"
if [ ! -f "$TU_VERSION_H" ] && grep -rq 'tu_version\.h' "$SRC/src/freedreno/vulkan/" 2>/dev/null; then
  info "generating tu_version.h (not committed upstream)"
  echo '#define TUGEN8_DRV_VERSION "vessel"' > "$TU_VERSION_H"
fi

# --- meson cross file --------------------------------------------------------
# Meson has no NDK integration, so the toolchain is spelled out. Written into
# WORK_DIR rather than the source tree so the checkout stays pristine.

PKG_CONFIG="$(command -v pkg-config || true)"
[ -n "$PKG_CONFIG" ] || die "pkg-config not found on PATH; meson needs it even for a stubbed Android build"

# pkg_config_libdir used to point at an EMPTY directory, and the reason is still
# live: without a restricted search path meson resolves `auto` dependencies
# against the BUILD machine's pkg-config and cheerfully reports that the
# container's x86-64 Linux zlib satisfies an aarch64 Android build, which fails
# at link:
#   ld.lld: /usr/lib/x86_64-linux-gnu/libz.so is incompatible with aarch64linux
#
# The X11 WSI needs nine real packages, so the path is now the Android sysroot
# rather than nothing. That keeps the property that matters — every host package
# is still invisible, because the sysroot contains only cross-built aarch64
# libraries — while letting xcb, x11-xcb and xshmfence be found. Anything not in
# the sysroot (zlib, libdrm, expat) still resolves to "not found" and falls back
# to a wrap subproject built for the target.
#
# sys_root is required alongside it, not optional: the .pc files carry a clean
# /usr prefix, so without PKG_CONFIG_SYSROOT_DIR meson would emit -I/usr/include
# and -L/usr/lib and compile against the container's X11 headers.
PKGDIR="$SYSROOT/usr/lib/pkgconfig"
PKGDIR_SHARE="$SYSROOT/usr/share/pkgconfig"

# VESSEL_CPU_FLAGS is a shell string ("-mcpu=oryon-1"); meson wants a list.
CPU_ARGS=""
for f in ${VESSEL_CPU_FLAGS:-}; do
  if [ -n "$CPU_ARGS" ]; then CPU_ARGS="$CPU_ARGS, "; fi
  CPU_ARGS="$CPU_ARGS'$f'"
done

# The ICD build still compiles for bionic, so the NDK's clang defines
# __ANDROID__ whatever meson's host system says, and Mesa's DETECT_OS_ANDROID is
# that macro and not a build option. `vk_enum_defines.h` therefore includes
# `vk_android_native_buffer.h`, which includes `<cutils/native_handle.h>` for one
# typedef:
#   include/vulkan/vk_android_native_buffer.h:22:10:
#     fatal error: 'cutils/native_handle.h' file not found
# Mesa ships that header in include/android_stub, but only puts it on the include
# path `if with_platform_android`. Adding it by hand is the whole fix: header
# only, no stub libraries, and a no-op for the HAL build which already has it.
if [ "$TURNIP_VARIANT" = icd ]; then
  [ -f "$SRC/include/android_stub/cutils/native_handle.h" ] \
    || die "no include/android_stub/cutils/native_handle.h in $SRC — Mesa moved
     the stub headers, and the ICD build cannot compile without them."
  if [ -n "$CPU_ARGS" ]; then CPU_ARGS="$CPU_ARGS, "; fi
  CPU_ARGS="$CPU_ARGS'-I$SRC/include/android_stub'"

  # Second header the same __ANDROID__-vs-meson mismatch costs.
  # src/freedreno/meson.build asks for libarchive unconditionally with
  # `allow_fallback: true`, so the bundled wrap gets configured and built even
  # though nothing we ship links it, and its archive.h does
  #     #if defined(__ANDROID__)
  #     #include "android_lf.h"
  # expecting the header AOSP's own build supplies. On a 64-bit bionic target
  # every off_t is already 64-bit, so an empty header is the correct content —
  # this is the same trick x11-sysroot.sh plays with <values.h>, and for the
  # same reason: written into WORK_DIR so the checkout stays pristine.
  ICD_SHIM_INC="$WORK_DIR/$COMPONENT-icd-shim-include"
  mkdir -p "$ICD_SHIM_INC"
  cat > "$ICD_SHIM_INC/android_lf.h" <<'SHIM'
/* Written by build/turnip.sh. libarchive's archive.h includes this on
 * __ANDROID__ to pick up large-file support; on a 64-bit bionic target off_t is
 * already 64-bit, so there is nothing to define. Build-time only. */
#ifndef _VESSEL_ANDROID_LF_H
#define _VESSEL_ANDROID_LF_H
#endif
SHIM
  CPU_ARGS="$CPU_ARGS, '-I$ICD_SHIM_INC'"
fi

# Third consequence of the same mismatch, and the last one. `src/util/log.c`
# calls `__android_log_write` under __ANDROID__, but the `-llog` that satisfies
# it is added by meson only under `with_platform_android`, so every link — the
# driver and Mesa's own ir3 test executables, which build by default — ends in
#     ld.lld: error: undefined symbol: __android_log_write
# liblog is a real platform library present on every Android device, so linking
# it is correct rather than a workaround. Empty for the HAL build, which already
# gets it from Mesa.
#
# Three more come with it. Turnip's KGSL backend waits on sync fds
# (`sync_wait`/`sync_merge`, libsync) and reaches for a buffer's native handle
# (`AHardwareBuffer_getNativeHandle`, libnativewindow) whatever the window system
# is, because those are properties of the *kernel driver*, not of the platform:
#     ld.lld: error: undefined symbol: sync_wait
#     ld.lld: error: undefined symbol: AHardwareBuffer_getNativeHandle
# All four are real platform libraries on every Android device, so linking them
# is correct rather than a workaround. Empty for the HAL build, which gets them
# from Mesa's own `dep_android`.
LINK_ARGS=""
if [ "$TURNIP_VARIANT" = icd ]; then
  LINK_ARGS="'-llog', '-lsync', '-lnativewindow', '-landroid'"

  # …except `sync_wait`, which is the one that is genuinely not there. The NDK's
  # libsync.so exports only sync_merge, sync_file_info and sync_file_info_free —
  # `sync_wait` is platform-internal, which is why Mesa's android-stub carries a
  # sync_stub.cpp at all. Mesa's stub is a stub; a driver that waits on real KGSL
  # fences needs the real behaviour, and the real behaviour is four lines of
  # poll(2) — that is all AOSP's libsync does. Compiled here and linked in by
  # object path so nothing has to be patched into the Mesa tree.
  ICD_SHIM_C="$WORK_DIR/$COMPONENT-icd-sync-shim.c"
  ICD_SHIM_O="$WORK_DIR/$COMPONENT-icd-sync-shim.o"
  cat > "$ICD_SHIM_C" <<'SHIM'
/* Written by build/turnip.sh — see the comment there. AOSP's sync_wait, which
 * the NDK does not export: poll the fence fd until it signals, and report a
 * timeout as ETIME the way callers expect. */
#include <errno.h>
#include <poll.h>

int sync_wait(int fd, int timeout);

int sync_wait(int fd, int timeout)
{
    struct pollfd fds = { .fd = fd, .events = POLLIN };
    int ret;

    do {
        ret = poll(&fds, 1, timeout);
    } while (ret == -1 && (errno == EINTR || errno == EAGAIN));

    if (ret == 0) { errno = ETIME; return -1; }
    if (ret < 0) return -1;
    if (fds.revents & (POLLERR | POLLNVAL)) { errno = EINVAL; return -1; }
    return 0;
}
SHIM
  "$NDK_CC" -O2 -fPIC -c "$ICD_SHIM_C" -o "$ICD_SHIM_O" \
    || die "could not compile the sync_wait shim"
  LINK_ARGS="$LINK_ARGS, '$ICD_SHIM_O'"
fi

CROSS="$WORK_DIR/$COMPONENT-$TURNIP_VARIANT-cross.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$NDK_CC'
cpp = '$NDK_CXX'
ar = '$NDK_BIN/llvm-ar'
strip = '$NDK_BIN/llvm-strip'
pkg-config = '$PKG_CONFIG'

[properties]
sys_root = '$SYSROOT'
pkg_config_libdir = ['$PKGDIR', '$PKGDIR_SHARE']

[host_machine]
system = '$TURNIP_MESON_SYSTEM'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$CPU_ARGS]
cpp_args = [$CPU_ARGS]
c_link_args = [$LINK_ARGS]
cpp_link_args = [$LINK_ARGS]
EOF

info "cross file: $CROSS"

# --- option sanity -----------------------------------------------------------
# Every one of these is a hard error in meson if the name is wrong, and on a
# fast-moving out-of-tree branch names do change. Checking here means the
# failure says "freedreno-kmds is gone" instead of burying it in meson output.

MESON_OPTS=""
for candidate in "$SRC/meson.options" "$SRC/meson_options.txt"; do
  if [ -f "$candidate" ]; then
    MESON_OPTS="$candidate"
    break
  fi
done
[ -n "$MESON_OPTS" ] || die "no meson.options or meson_options.txt in $SRC — is this really a Mesa tree?"

for opt in platforms platform-sdk-version android-stub android-libbacktrace \
           egl gbm gles1 gles2 gallium-drivers vulkan-drivers freedreno-kmds; do
  grep -q "'$opt'" "$MESON_OPTS" \
    || die "meson option '$opt' does not exist in $MESON_OPTS.
     The $MESA_REF branch renamed or dropped it. Check with:
       grep -n \"^option(\" $MESON_OPTS"
done

# Both of the VERIFY questions that used to be here are answered, on device,
# 2026-08-08 (tools/device-vulkan.sh):
#
#   1. freedreno-kmds='kgsl' alone is correct for this device. The driver built
#      this way opens the GPU and enumerates a physical device — vkGetPhysical-
#      DeviceProperties2 reports driverID 18 (VK_DRIVER_ID_MESA_TURNIP),
#      "Adreno (TM) 829 (unknown)", Vulkan 1.4.358. Adding 'msm' would only add
#      the DRM path, which this kernel does not expose for the GPU.
#   2. the .so does land at src/freedreno/vulkan/, and the check below keeps it
#      honest if a future rebase moves it.
log "configuring $COMPONENT (freedreno/$TARGET_KMD, api $NDK_API, platforms=$TURNIP_PLATFORMS)"

# Only the Vulkan driver is wanted: no GL, no EGL, no gallium, no window system
# integration. android-stub replaces the platform libraries we cannot link
# against in the container, and libbacktrace is disabled for the same reason.
#
# `android-stub` is refused outright without `platforms=android`:
#   meson.build:1122: ERROR: Problem encountered: `-D android-stub=true` makes
#   no sense without `-D platforms=android` or emulated Android
# which is correct — the ICD build needs none of libcutils/liblog/libnativewindow,
# so there is nothing to stub. Passed as an array so the option disappears
# entirely rather than being set to false, which is a different thing to meson.
ANDROID_OPTS=()
if [ "$TURNIP_VARIANT" = hal ]; then
  ANDROID_OPTS=(-Dandroid-stub=true -Dandroid-libbacktrace=disabled)
fi

# driconf, the per-application workaround database, is the only thing in this
# build that wants expat. Mesa switches it off for Android automatically ("We
# don't require expat on Android or Windows"), and with system = 'linux' that
# exemption stops applying: meson builds the bundled expat wrap and the driver
# comes out with DT_NEEDED libexpat.so.1, which nothing on the device provides
# and the closure walk below refuses. Shipping an expat to satisfy a database of
# workarounds for other people's GPUs is not a trade worth making, so the ICD
# build says no to it explicitly.
EXPAT_OPT=()
if [ "$TURNIP_VARIANT" = icd ]; then
  EXPAT_OPT=(-Dexpat=disabled)
fi

meson setup "$BUILD" "$SRC" \
  --cross-file "$CROSS" \
  -Dplatforms="$TURNIP_PLATFORMS" \
  -Dplatform-sdk-version="$NDK_API" \
  "${ANDROID_OPTS[@]}" \
  -Degl=disabled \
  -Dgbm=disabled \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dgallium-drivers= \
  -Dvulkan-drivers=freedreno \
  -Dfreedreno-kmds="$TARGET_KMD" \
  -Dvulkan-beta=true   "${EXPAT_OPT[@]}" \
  -Dvideo-codecs= \
  -Dbuildtype=release \
  -Dstrip=true \
  -Dspirv-tools=disabled

# No -Db_lto: Mesa refuses to configure with it, by explicit check —
#   meson.build:52: "Building Mesa with LTO is not supported."
# It is not a toolchain limitation on our side, so do not try to work around it.
#
# -Dspirv-tools=disabled is not cosmetic. The option defaults to `auto`, and
# meson resolves it with the pkg-config on PATH — which in a cross build is the
# BUILD machine's. It finds the container's SPIRV-Tools, defines
# HAVE_SPIRV_TOOLS, and then the aarch64 compile dies on a header that was never
# in the target sysroot:
#   vtn_debug.c:11: fatal error: 'spirv-tools/libspirv.h' file not found
# SPIRV-Tools only exists here to dump SPIR-V for debugging, so a shipping
# driver does not want it regardless. Any other `auto` feature can leak the same
# way; if a future build fails on a missing host-detected header, this is why.

log "building $COMPONENT"
ninja -C "$BUILD" -j "$(build_jobs 1)"

SO="$BUILD/src/freedreno/vulkan/libvulkan_freedreno.so"
[ -f "$SO" ] || die "build produced no libvulkan_freedreno.so (expected $SO).
     If Mesa moved the target, locate it with:
       find $BUILD -name libvulkan_freedreno.so"

file "$SO" | grep -q 'ARM aarch64' \
  || die "libvulkan_freedreno.so is not an aarch64 binary: $(file -b "$SO")"
file "$SO" | grep -q 'shared object' \
  || die "libvulkan_freedreno.so is not a shared object: $(file -b "$SO")"

# What the .so exports decides what can load it, and both wrong answers are
# silent: a HAL build dlopen'd directly resolves no entry point, and an ICD
# build handed to libadrenotools is not a HAL, so the stock Qualcomm driver
# answers instead with nothing said anywhere.
EXPORTS="$("$NDK_BIN/llvm-readelf" --dyn-syms "$SO" 2>/dev/null | grep -v ' UND ' || true)"
if [ "$TURNIP_VARIANT" = icd ]; then
  grep -q 'vk_icdGetInstanceProcAddr' <<< "$EXPORTS" \
    || die "the ICD build exports no vk_icdGetInstanceProcAddr.
     -Dplatforms=$TURNIP_PLATFORMS was supposed to drop the Android HAL
     packaging and emit a normal ICD. See what it did export with:
       $NDK_BIN/llvm-readelf --dyn-syms $SO"
  ok "exports vk_icdGetInstanceProcAddr — a plain dlopen can drive this"
else
  grep -qw 'HMI' <<< "$EXPORTS" \
    || die "the HAL build exports no HMI; Android's Vulkan loader could not load it"
  ok "exports HMI — the Android Vulkan loader can load this"
fi

install -m 0644 "$SO" "$STAGE/libvulkan_freedreno.so"
# -Dstrip=true only applies at `meson install`, and we copy straight out of the
# build tree, so the strip is done explicitly.
"$NDK_BIN/llvm-strip" --strip-unneeded "$STAGE/libvulkan_freedreno.so" \
  || warn "strip failed; shipping unstripped"

# --- the C++ runtime the driver links against ---------------------------------
# libvulkan_freedreno.so has libc++_shared.so in its NEEDED list, and that is not
# a system library — nothing on the device provides it.
#
# libadrenotools loads the driver into a linker namespace whose ld_library_path
# is this package's directory and whose default_library_path is the APK's
# nativeLibraryDir. Either could satisfy it, and the APK's does, but the APK's
# copy comes from Gradle's NDK while the driver was compiled against this one.
# Shipping the matching copy here makes the package self-contained and puts it
# first in the search order, so the answer does not depend on which NDK the app
# module happens to be pinned to.
#
# Without it the driver dlopen fails with "library libc++_shared.so not found",
# libadrenotools falls back, and the stock Qualcomm driver answers with no error
# anywhere — the failure shape this whole component exists to stop.
NDK_STL="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
[ -f "$NDK_STL" ] || die "no libc++_shared.so at $NDK_STL; the driver could not load without it"
install -m 0644 "$NDK_STL" "$STAGE/libc++_shared.so"
"$NDK_BIN/llvm-strip" --strip-unneeded "$STAGE/libc++_shared.so" || true

# --- the X11 client libraries the WSI links against ---------------------------
# -Dplatforms=x11 puts libxcb and friends in the driver's NEEDED list, and none
# of them exist on Android. The Wine package ships its own copies, but they are
# out of reach here: libadrenotools loads the driver into a linker namespace
# whose ld_library_path is THIS package's directory, so Wine's lib directory is
# not on the search path. A missing one means the driver dlopen fails,
# libadrenotools falls through, and the stock Qualcomm blob answers silently —
# the failure shape this component exists to prevent.
#
# The closure is walked rather than the whole sysroot copied: libX11-xcb pulls
# libX11 which pulls libxcb which pulls libXau, and copying only the direct
# NEEDED entries would leave the second hop missing.
BIONIC_LIBS="libc.so libm.so libdl.so libz.so liblog.so libandroid.so
libnativewindow.so libsync.so libhardware.so libvulkan.so ld-android.so"

# Word-splitting over the variable, not a substring match on it: the list spans
# two lines, and `case " $VAR " in *" $need "*` never matches the first name
# after a newline. That silently reported libnativewindow.so as missing.
bionic_provides() {
  local lib
  for lib in $BIONIC_LIBS; do [ "$lib" = "$1" ] && return 0; done
  return 1
}

needed_of() {
  "$NDK_BIN/llvm-readelf" -d "$1" 2>/dev/null | grep NEEDED | sed 's/.*\[//;s/\]//'
}

log "resolving the driver's shared-library closure"
pending="$STAGE/libvulkan_freedreno.so"
copied=""
turnip_missing=""
while [ -n "$pending" ]; do
  elf="${pending%% *}"
  case "$pending" in *" "*) pending="${pending#* }" ;; *) pending="" ;; esac
  for need in $(needed_of "$elf"); do
    bionic_provides "$need" && continue
    [ -f "$STAGE/$need" ] && continue
    if [ -f "$SYSROOT/usr/lib/$need" ]; then
      install -m 0644 "$SYSROOT/usr/lib/$need" "$STAGE/$need"
      "$NDK_BIN/llvm-strip" --strip-unneeded "$STAGE/$need" 2>/dev/null || true
      copied="$copied $need"
      pending="$pending $STAGE/$need"
      continue
    fi
    turnip_missing="$turnip_missing $need"
  done
done
[ -z "$copied" ] || ok "bundled from the X11 sysroot:$copied"
[ -z "$turnip_missing" ] \
  || die "the driver needs libraries neither the package nor Android provides:$turnip_missing"
ok "every NEEDED entry of the driver resolves"

# --- adrenotools metadata ----------------------------------------------------
# libadrenotools reads this to decide whether the driver can be loaded at all;
# minApi and libraryName are the two fields it actually acts on.
cat > "$STAGE/meta.json" <<EOF
{
  "schemaVersion": 1,
  "name": "Turnip $VERSION",
  "description": "Mesa Turnip Vulkan driver for $TARGET_GPU ($TARGET_GPU_GEN), $TARGET_KMD kernel driver. Built from $MESA_REF @ ${SOURCE_SHA:0:12}.",
  "author": "Vessel",
  "packageVersion": "$VERSION",
  "vendor": "Mesa",
  "driverVersion": "$MESA_VERSION",
  "minApi": $NDK_API,
  "libraryName": "libvulkan_freedreno.so"
}
EOF

write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

# Both variants come off the same Mesa commit, and package_wcp.py derives the
# version code from the first three numbers in the version string — so
# "26.3.0-devel-9c475fc3" and "26.3.0-devel-9c475fc3-icd" both yield 260300, and
# installing one would overwrite the other in components/Turnip/<code>/ with
# nothing said anywhere. The ICD takes the next code up so the two can sit side
# by side in the store, which is what makes comparing them on one device possible.
TURNIP_VERSION_CODE="$(VESSEL_V="$VERSION" VESSEL_COMMON="$COMMON_SH_DIR" VESSEL_TURNIP_REVISION="${TURNIP_REVISION:-0}" python3 -c "
import os, sys
sys.path.insert(0, os.environ['VESSEL_COMMON'])
from package_wcp import version_code
v = os.environ['VESSEL_V']
# Vessel's patch revision, times two so it cannot land on the ICD's +1.
# Without it a rebuild carrying a new patch keeps the old code and the store
# silently serves the previous build -- the collision this file already warns
# about between the two variants, in its other direction.
rev = int(os.environ.get('VESSEL_TURNIP_REVISION', '0'))
print(version_code(v) + (1 if v.endswith('-icd') else 0) + 2 * rev)
")"
[ -n "$TURNIP_VERSION_CODE" ] || die "could not derive a version code for $VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Turnip \
  --version-code "$TURNIP_VERSION_CODE" \
  --name "Turnip $VERSION ($TARGET_NAME, $TURNIP_VARIANT)" \
  --version "$VERSION" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Mesa Turnip for $TARGET_GPU built from $MESA_REF @ ${SOURCE_SHA:0:12} with ${VESSEL_CPU_FLAGS:-generic flags}" \
  --out "$DIST_DIR/turnip-$VERSION-$TARGET_NAME.wcp"

ok "dist/turnip-$VERSION-$TARGET_NAME.wcp"
