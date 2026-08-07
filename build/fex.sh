#!/usr/bin/env bash
# Build FEXCore as Windows PE DLLs and package them as a .wcp.
#
# FEX is Vessel's primary engine, and unlike Box64 it is not a standalone Linux
# binary: it is loaded *inside* Wine as a PE module. So this build uses
# llvm-mingw, not the NDK. One source tree, two DLLs, two configure passes:
#
#   libarm64ecfex.dll  — ARM64EC; translates the x86-64 side of an EC process
#   libwow64fex.dll    — plain ARM64; the WoW64 backend for 32-bit x86
#
# Both are built from the same checkout on purpose. A mismatched pair is a
# well-known way to get crashes that look like FEX bugs and are not.
#
#   ./build/fex.sh              # -> dist/fex-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_mingw

COMPONENT=fex
COMPONENT_REF="$FEX_REF"
# FEX tags are FEX-YYMM. Strip the prefix so the package version is the plain
# monthly number; package_wcp.py orders those correctly.
VERSION="${FEX_REF#FEX-}"

fetch_source "$COMPONENT" "$FEX_REPO" "$FEX_REF"

SRC="$NATIVE_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# FEX drives its whole mingw cross-build from this file: it turns MINGW_TRIPLE
# into the compiler, sysroot and target flags. Building without it "works" and
# silently produces a host ELF.
TOOLCHAIN="$SRC/Data/CMake/toolchain_mingw.cmake"
[ -f "$TOOLCHAIN" ] || die "FEX has no mingw toolchain file at $TOOLCHAIN.
     Upstream moved it. Find the new path with:
       git -C $SRC ls-files '*toolchain*mingw*'"

# VERIFY: on first run, confirm both DLLs land under <build>/Bin/. The search
# below is deliberate — if upstream changes the output directory we want a
# failure that names the missing DLL, not a stale hard-coded path.

for TRIPLE in arm64ec-w64-mingw32 aarch64-w64-mingw32; do
  case "$TRIPLE" in
    arm64ec-*) DLL_NAME=libarm64ecfex.dll ;;
    aarch64-*) DLL_NAME=libwow64fex.dll ;;
    *) die "unhandled triple $TRIPLE" ;;
  esac

  TRIPLE_CC="$MINGW_BIN/$TRIPLE-clang"
  [ -x "$TRIPLE_CC" ] || die "no compiler for $TRIPLE at $TRIPLE_CC"

  # FEX is clang-only. GCC does not build it at all (it relies on clang
  # builtins and on the ARM64EC target, which GCC does not have), so check
  # here rather than letting the user read 400 lines of template errors.
  require_clang_major "$TRIPLE_CC" 13 \
    "FEX requires clang >= 13 and does not build with GCC at any version.
     Point LLVM_MINGW_HOME at a newer llvm-mingw."

  # FEX's JIT probes the *host* CPU at runtime and emits code for whatever it
  # finds, so this tuning reaches only FEX's own C++ (the JIT compiler, the
  # thunks, the syscall layer) — never the code FEX generates for the guest.
  # Worth having, not worth expecting a benchmark to move because of it.
  resolve_cpu_flags "$TRIPLE_CC"

  BUILD="$WORK_DIR/$COMPONENT-$TRIPLE"
  rm -rf "$BUILD"
  mkdir -p "$BUILD"

  log "configuring $COMPONENT for $TRIPLE -> $DLL_NAME"

  # ENABLE_LTO=False                  LTO across the mingw link is unreliable
  #                                   and costs more build time than it wins.
  # ENABLE_JEMALLOC_GLIBC_ALLOC=False there is no glibc here; the PE build gets
  #                                   its allocator from the Windows side.
  # BUILD_TESTS=False                 the test suite needs a Linux host FEX.
  cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DMINGW_TRIPLE="$TRIPLE" \
    -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_LTO=False \
    -DENABLE_JEMALLOC_GLIBC_ALLOC=False \
    -DBUILD_TESTS=False \
    -DCMAKE_C_FLAGS="${VESSEL_CPU_FLAGS:-}" \
    -DCMAKE_CXX_FLAGS="${VESSEL_CPU_FLAGS:-}"

  log "building $DLL_NAME"
  cmake --build "$BUILD" -j "$(nproc_safe)"

  DLL="$(find "$BUILD" -type f -name "$DLL_NAME" -print -quit)"
  [ -n "$DLL" ] || die "build produced no $DLL_NAME (searched $BUILD).
     If the link failed on jemalloc symbols, add -DENABLE_JEMALLOC=False."

  # ARM64EC binaries carry the same AA64 machine type as plain ARM64, so file(1)
  # cannot tell the two apart — this only proves we did not accidentally build a
  # host ELF. The real EC check is the CHPE load-config, done on the device.
  file "$DLL" | grep -Eqi 'PE32\+.*(aarch64|arm64)' \
    || die "$DLL_NAME is not an ARM64 PE: $(file -b "$DLL")"

  install -m 0644 "$DLL" "$STAGE/$DLL_NAME"
  ok "$DLL_NAME ($TRIPLE)"
done

# Both DLLs sit at the package root: that is where the Wine side looks for them.
[ -f "$STAGE/libarm64ecfex.dll" ] || die "libarm64ecfex.dll missing from payload"
[ -f "$STAGE/libwow64fex.dll" ]   || die "libwow64fex.dll missing from payload"

# Both passes probe the same llvm-mingw, so the flags recorded here (from the
# last pass) describe both DLLs; resolve_cpu_flags warns if a probe falls back.
write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type FEXCore \
  --name "FEX $VERSION ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "FEXCore $VERSION PE DLLs (arm64ec + wow64) built for $TARGET_DESC" \
  --out "$DIST_DIR/fex-$VERSION-$TARGET_NAME.wcp"

ok "dist/fex-$VERSION-$TARGET_NAME.wcp"
