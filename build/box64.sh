#!/usr/bin/env bash
# Build Box64 tuned for the target chip and package it as a .wcp.
#
# Box64 is Vessel's fallback engine: it runs x86-64 code in Compatibility
# containers, and its Box32 mode handles 32-bit x86 without any armhf multilib.
# It is built first because it is the simplest component, which makes it the
# right thing to prove the whole pipeline with.
#
#   ./build/box64.sh            # -> dist/box64-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk

COMPONENT=box64
COMPONENT_REF="$BOX64_REF"
VERSION="${BOX64_REF#v}"

fetch_source "$COMPONENT" "$BOX64_REPO" "$BOX64_REF"

# Box64's preset owns the compiler flags. SD8EG5 applies, via COMPILE_OPTIONS on
# its dynarec and interpreter sources:
#   -pipe -march=armv8.7-a+crypto+sm4+sha3+fp16+sve+sve2 -mtune=oryon-1
#
# COMPILE_OPTIONS land AFTER CMAKE_C_FLAGS on the command line, so also passing
# our own -mcpu=oryon-1 would put -mcpu and -march on the same invocation, where
# which one wins depends on the clang version and flag order. The preset is
# upstream-tested and its -march is a superset of what -mcpu=oryon-1 implies, so
# it wins by design and we do not inject anything.
#
# resolve_cpu_flags still runs: it records what this toolchain is capable of into
# the provenance, and its probe is how we find out the compiler cannot target
# this core in seconds rather than after a full build.
resolve_cpu_flags "$NDK_CC"

# The risky part of the preset is oryon-1 recognition, not armv8.7-a. Probe it
# directly so an unsupported toolchain fails here with a clear reason.
if ! probe_cflag "$NDK_CC" "-mtune=$TARGET_MTUNE"; then
  die "this toolchain does not accept -mtune=$TARGET_MTUNE, which the
     $BOX64_PRESET preset requires. Box64 needs clang >= 19 for Oryon;
     bump ANDROID_NDK_VERSION in native/pins.env."
fi
info "preset $BOX64_PRESET supplies -march/-mtune; not overriding"

SRC="$NATIVE_DIR/$COMPONENT"
BUILD="$WORK_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$BUILD" "$STAGE"
mkdir -p "$BUILD" "$STAGE"

# The preset comes from the target file. For canoe that is SD8EG5, which
# upstream defines as exactly this chip's ISA plus -mtune=oryon-1 — so the
# tuning is upstream's own, not something we invented.
log "configuring $COMPONENT ($BOX64_PRESET preset)"

cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$TARGET_ABI" \
  -DANDROID_PLATFORM="android-$NDK_API" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -D"$BOX64_PRESET"=1 \
  -DARM_DYNAREC=ON \
  -DBOX32=OFF \
  -DANDROID=1 \
  ${BOX64_EXTRA_CMAKE:-}

# BOX32=OFF, and this is not a preference — Box32 does not compile against
# bionic. Verified 2026-08-07: with BOX32=ON this build fails at ~320/657
# objects, and every error is in a src/libtools/*32.c file reaching for glibc
# internals that bionic does not provide:
#   libc_net32.c  struct __res_state          (glibc resolver state)
#   myalign32.c   obstack.h                   (glibc obstack)
#   signal32.c    __SI_SIGFAULT_ADDL          (glibc siginfo internals)
#   threads32.c   struct __pthread_mutex_s,
#                 pthread_getattr_default_np  (glibc pthread internals)
#
# Box32 is written against glibc. Winlator-family forks that advertise Box32
# either build Box64 against a glibc rootfs inside the container or carry their
# own patches; an NDK/bionic build cannot enable it as-is.
#
# This costs us nothing architecturally: 32-bit x86 is handled by FEX's WoW64
# path in Universal containers, which is the primary design anyway (see
# docs/ARCHITECTURE.md). The gap is 32-bit x86 inside a Compatibility container,
# which would need either a glibc-targeted Box64 or patches in patches/box64/.
#
# Deliberately NOT set:
#   PAGE16K   — this device uses 4 KB pages; enabling it would break execution.
#   BAD_SIGNAL— costs performance and is only needed on kernels with broken
#               signal delivery; revisit if crashes appear under load.

log "building $COMPONENT"
cmake --build "$BUILD" -j "$(nproc_safe)"

BIN="$BUILD/box64"
[ -f "$BIN" ] || die "build produced no box64 binary (looked in $BUILD)"

# Sanity: it must be an ARM64 Android ELF, not an accidental host build.
file "$BIN" | grep -q 'ARM aarch64' || die "box64 is not an aarch64 binary: $(file "$BIN")"

mkdir -p "$STAGE/bin"
install -m 0755 "$BIN" "$STAGE/bin/box64"
"$NDK_BIN/llvm-strip" --strip-unneeded "$STAGE/bin/box64" || warn "strip failed; shipping unstripped"

write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Box64 \
  --name "Box64 $VERSION ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Box64 $VERSION built for $TARGET_DESC with ${VESSEL_CPU_FLAGS:-generic flags}" \
  --out "$DIST_DIR/box64-$VERSION-$TARGET_NAME.wcp"

ok "dist/box64-$VERSION-$TARGET_NAME.wcp"
