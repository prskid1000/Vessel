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

ok "out/gfx-native: $(ls "$OUT" | tr '\n' ' ')"
