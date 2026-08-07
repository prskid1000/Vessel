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

  # Probe the tuning flags for the record, but do NOT pass them to this build.
  #
  # FEX's JIT probes the host CPU at runtime and emits code for whatever it
  # finds, so -mcpu would reach only FEX's own C++ (the JIT compiler, thunks,
  # syscall layer) and never the code FEX generates for the guest. The upside is
  # slight and the downside is measured: putting -mcpu=oryon-1 in CMAKE_C_FLAGS
  # leaks it into C++20 module dependency scans, which run under the host triple
  # and reject it —
  #     error: unsupported option '-mcpu=' for target 'x86_64-unknown-linux-gnu'
  # — printed repeatedly through an otherwise good build. Noise that trains you
  # to ignore errors is worse than a tuning flag is worth.
  #
  # If FEX's own C++ is ever worth tuning, the flag belongs in the mingw
  # toolchain file, where it applies to the target and nothing else.
  resolve_cpu_flags "$TRIPLE_CC"

  BUILD="$WORK_DIR/$COMPONENT-$TRIPLE"
  rm -rf "$BUILD"
  mkdir -p "$BUILD"

  log "configuring $COMPONENT for $TRIPLE -> $DLL_NAME"

  # ENABLE_LTO=False                  LTO across the mingw link is unreliable
  #                                   and costs more build time than it wins.
  # ENABLE_JEMALLOC_GLIBC_ALLOC=False there is no glibc here; the PE build gets
  #                                   its allocator from the Windows side.
  # BUILD_TESTING=False               CMake's own CTest variable, NOT BUILD_TESTS
  #                                   — that is what FEX actually gates on. With
  #                                   tests enabled, configure demands NASM to
  #                                   assemble the x86 test corpus and dies
  #                                   before compiling anything we want.
  cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DMINGW_TRIPLE="$TRIPLE" \
    -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_LTO=False \
    -DENABLE_JEMALLOC_GLIBC_ALLOC=False \
    -DBUILD_TESTING=False \
    -DBUILD_FEX_LINUX_TESTS=False

  log "building $DLL_NAME"
  cmake --build "$BUILD" -j "$(build_jobs 1)"

  DLL="$(find "$BUILD" -type f -name "$DLL_NAME" -print -quit)"
  [ -n "$DLL" ] || die "build produced no $DLL_NAME (searched $BUILD).
     If the link failed on jemalloc symbols, add -DENABLE_JEMALLOC=False."

  # Shared with dxvk.sh and vkd3d.sh. The naive "is it aarch64" test rejects a
  # correct ARM64EC DLL — see the verify_pe_dll comment in common.sh.
  verify_pe_dll "$DLL" "$TRIPLE" "$DLL_NAME"

  install -m 0644 "$DLL" "$STAGE/$DLL_NAME"
  ok "$DLL_NAME ($TRIPLE)"
done

# Both DLLs sit at the package root: that is where the Wine side looks for them.
[ -f "$STAGE/libarm64ecfex.dll" ] || die "libarm64ecfex.dll missing from payload"
[ -f "$STAGE/libwow64fex.dll" ]   || die "libwow64fex.dll missing from payload"

# Record what was actually applied, which for FEX is no CPU tuning at all — see
# the note above the configure step. Claiming -mcpu=oryon-1 here because the
# probe accepted it would put a flag in the UI that never reached a compiler.
VESSEL_CPU_FLAGS="none (JIT detects host CPU at runtime)"
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
