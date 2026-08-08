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

fetch_source "$SOURCE_NAME" "$MESA_REPO" "$MESA_REF" "${MESA_SHA:-}"

SRC="$NATIVE_DIR/$SOURCE_NAME"
BUILD="$WORK_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
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

CROSS="$WORK_DIR/$COMPONENT-cross.ini"
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
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$CPU_ARGS]
cpp_args = [$CPU_ARGS]
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
log "configuring $COMPONENT (freedreno/$TARGET_KMD, api $NDK_API)"

# Only the Vulkan driver is wanted: no GL, no EGL, no gallium, no window system
# integration. android-stub replaces the platform libraries we cannot link
# against in the container, and libbacktrace is disabled for the same reason.
meson setup "$BUILD" "$SRC" \
  --cross-file "$CROSS" \
  -Dplatforms=android,x11 \
  -Dplatform-sdk-version="$NDK_API" \
  -Dandroid-stub=true \
  -Dandroid-libbacktrace=disabled \
  -Degl=disabled \
  -Dgbm=disabled \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dgallium-drivers= \
  -Dvulkan-drivers=freedreno \
  -Dfreedreno-kmds="$TARGET_KMD" \
  -Dvulkan-beta=true \
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

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Turnip \
  --name "Turnip $VERSION ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Mesa Turnip for $TARGET_GPU built from $MESA_REF @ ${SOURCE_SHA:0:12} with ${VESSEL_CPU_FLAGS:-generic flags}" \
  --out "$DIST_DIR/turnip-$VERSION-$TARGET_NAME.wcp"

ok "dist/turnip-$VERSION-$TARGET_NAME.wcp"
