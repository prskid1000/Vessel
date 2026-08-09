#!/usr/bin/env bash
# Build the native (Android/aarch64) graphics probes. Runs inside the Docker
# build image, alongside tools/vulkan/build.sh, which builds libadrenotools.
#
#   docker run --rm -v "$PWD:/src" -v vessel-work:/work vessel-build \
#       ./tools/gfx/build.sh
#
# Output: out/gfx-native/wsiprobe
#
# The Windows PE probes in this directory (d3d11probe.c and friends) are built
# by tools/device-graphics.sh with llvm-mingw; this one is a bionic ELF, because
# what it measures — dma-heap access, gralloc layout, Turnip's memory types and
# the cost of a mapped read — is a property of the Android side and has nothing
# to do with Wine.

. "$(dirname "${BASH_SOURCE[0]}")/../../build/common.sh"

vessel_init
setup_ndk

OUT="${VESSEL_PROBE_OUT:-$REPO_ROOT/out/gfx-native}"
mkdir -p "$OUT"

# The Vulkan headers come from the NDK sysroot, which is on the default include
# path for $NDK_CC, so nothing needs adding for them. libandroid supplies
# AHardwareBuffer_* and the undocumented AHardwareBuffer_getNativeHandle the
# vendored X server also uses.
# The same chip flags every shipped component gets (-mcpu=oryon-1 on canoe).
# Not cosmetic here: this probe's headline numbers are memcpy and a scalar read
# loop, and both are codegen-sensitive. Measuring generic-ARM64 code and then
# quoting it as the cost of a frame in a driver built for this core would be
# comparing two different programs.
resolve_cpu_flags "$NDK_CC"

log "building wsiprobe for $TARGET_ABI (api $NDK_API) ${VESSEL_CPU_FLAGS:+with $VESSEL_CPU_FLAGS}"
# shellcheck disable=SC2086
"$NDK_CC" -O2 -Wall -Wextra -fvisibility=default ${VESSEL_CPU_FLAGS:-} \
  -o "$OUT/wsiprobe" "$REPO_ROOT/tools/gfx/wsiprobe.c" \
  -ldl -landroid -llog \
  || die "wsiprobe failed to build"

file "$OUT/wsiprobe" | grep -q 'ARM aarch64' \
  || die "wsiprobe is not an aarch64 binary: $(file -b "$OUT/wsiprobe")"

# --- x11present --------------------------------------------------------------
# Needs the cross-built X11 sysroot for <xcb/xcb.h> and -lxcb. At *runtime* it
# resolves libxcb.so from whichever directory is on LD_LIBRARY_PATH, which is
# deliberately the same copy the Wine session uses: an xcb_connection_t made by
# one libxcb and passed to another is undefined behaviour, and this probe exists
# partly to rule that class of fault in or out.
SYSROOT="$WORK_DIR/androidsysroot"
"$COMMON_SH_DIR/x11-sysroot.sh" >/dev/null \
  || die "x11-sysroot.sh failed; x11present needs libxcb headers and a stub to link against"
[ -f "$SYSROOT/usr/include/xcb/xcb.h" ] || die "no xcb.h in $SYSROOT"

log "building x11present"
# shellcheck disable=SC2086
"$NDK_CC" -O2 -Wall -Wextra -fvisibility=default ${VESSEL_CPU_FLAGS:-} \
  -I"$SYSROOT/usr/include" \
  -o "$OUT/x11present" "$REPO_ROOT/tools/gfx/x11present.c" \
  -L"$SYSROOT/usr/lib" -lxcb -ldl -landroid -llog \
  || die "x11present failed to build"

ok "out/gfx-native: $(ls "$OUT" | tr '\n' ' ')"
