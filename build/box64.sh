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
resolve_cpu_flags "$NDK_CC"

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
  -DANDROID_PLATFORM="android-$TARGET_API" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -D"$BOX64_PRESET"=1 \
  -DARM_DYNAREC=ON \
  -DBOX32=ON \
  -DANDROID=1 \
  -DCMAKE_C_FLAGS="${VESSEL_CPU_FLAGS:-}" \
  ${BOX64_EXTRA_CMAKE:-}

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
