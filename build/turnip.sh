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

fetch_source "$SOURCE_NAME" "$MESA_REPO" "$MESA_REF"

SRC="$NATIVE_DIR/$SOURCE_NAME"
BUILD="$WORK_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$BUILD" "$STAGE"
mkdir -p "$STAGE"

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

# VERIFY: two things on the first successful run —
#   1. freedreno-kmds is an array option; TARGET_KMD is 'kgsl' alone here. If
#      the driver fails to open /dev/kgsl-3d0 on device, try 'kgsl,msm'.
#   2. that the .so really lands at src/freedreno/vulkan/ in the build tree
#      (checked below, so a move fails loudly rather than shipping nothing).
log "configuring $COMPONENT (freedreno/$TARGET_KMD, api $NDK_API)"

# Only the Vulkan driver is wanted: no GL, no EGL, no gallium, no window system
# integration. android-stub replaces the platform libraries we cannot link
# against in the container, and libbacktrace is disabled for the same reason.
meson setup "$BUILD" "$SRC" \
  --cross-file "$CROSS" \
  -Dplatforms=android \
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
