#!/usr/bin/env bash
# Build FEXCore as Windows PE DLLs and package them as a .wcp.
#
#   ./build/fex.sh              # -> dist/fex-<ver>-<target>.wcp
#
# FEX is loaded *inside* Wine as a PE module, so this build uses llvm-mingw, not
# the NDK. Two DLLs from one checkout: libarm64ecfex.dll (ARM64EC, translates
# the x86-64 side of an EC process) and libwow64fex.dll (plain ARM64, the WoW64
# backend for 32-bit x86). Same checkout on purpose — a mismatched pair is a
# well-known way to get crashes that look like FEX bugs and are not.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_mingw

COMPONENT=fex
COMPONENT_REF="$FEX_REF"
# FEX tags are FEX-YYMM; strip the prefix so package_wcp.py orders them.
VERSION="${FEX_REF#FEX-}"
# Vessel patches change what this builds without moving the upstream tag, so the
# store would treat a rebuild as bytes it already has. See vessel_version_code.
VERSION_CODE="$(vessel_version_code "$VERSION" "${FEX_REVISION:-0}")"

fetch_source "$COMPONENT" "$FEX_REPO" "$FEX_REF"

SRC="$NATIVE_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# FEX drives its whole mingw cross-build from this file. Building without it
# "works" and silently produces a host ELF.
TOOLCHAIN="$SRC/Data/CMake/toolchain_mingw.cmake"
[ -f "$TOOLCHAIN" ] || die "FEX has no mingw toolchain file at $TOOLCHAIN.
     Upstream moved it. Find the new path with:
       git -C $SRC ls-files '*toolchain*mingw*'"

for TRIPLE in arm64ec-w64-mingw32 aarch64-w64-mingw32; do
  case "$TRIPLE" in
    arm64ec-*) DLL_NAME=libarm64ecfex.dll ;;
    aarch64-*) DLL_NAME=libwow64fex.dll ;;
    *) die "unhandled triple $TRIPLE" ;;
  esac

  TRIPLE_CC="$MINGW_BIN/$TRIPLE-clang"
  [ -x "$TRIPLE_CC" ] || die "no compiler for $TRIPLE at $TRIPLE_CC"

  # FEX is clang-only — it relies on clang builtins and on the ARM64EC target,
  # which GCC does not have. Checked here rather than 400 lines of template
  # errors later.
  require_clang_major "$TRIPLE_CC" 13 \
    "FEX requires clang >= 13 and does not build with GCC at any version.
     Point LLVM_MINGW_HOME at a newer llvm-mingw."

  # Probed for the record, but deliberately NOT passed as CMAKE_C_FLAGS. FEX's
  # JIT detects the host CPU at runtime, so -mcpu reaches only FEX's own C++ and
  # never the code it generates for the guest. Worse, -mcpu=oryon-1 in
  # CMAKE_C_FLAGS leaks into C++20 module dependency scans, which run under the
  # host triple and reject it with "unsupported option '-mcpu=' for target
  # 'x86_64-unknown-linux-gnu'" repeatedly through an otherwise good build.
  #
  # **That reasoning is still right and it was not the whole story.** FEX has
  # its own knob, -DTUNE_CPU, which lands in FEX_TUNE_COMPILE_FLAGS and is
  # applied with target_compile_options(... PRIVATE) to the FEXCore target only
  # (CMakeLists.txt:524-527, FEXCore/Source/CMakeLists.txt) — so it tunes the
  # hot C++ without ever reaching a dependency scan.
  #
  # It is set here because its **default was quietly wrong**. TUNE_CPU defaults
  # to "native" (CMakeLists.txt:170); for an arm64 target that runs
  # Scripts/aarch64_fit_native.py over `/proc/cpuinfo` — and this build runs in
  # an x86-64 container, where the script finds no ARM part and prints its
  # hardcoded fallback, `largest_big = "cortex-a57"` (:128, printed :145). So
  # every FEX package so far has been tuned for a 2014 ARMv8.0 core while
  # running on Oryon. Naming the CPU removes the dependency on which machine
  # happened to run the build.
  resolve_cpu_flags "$TRIPLE_CC"

  BUILD="$WORK_DIR/$COMPONENT-$TRIPLE"
  rm -rf "$BUILD"
  mkdir -p "$BUILD"

  log "configuring $COMPONENT for $TRIPLE -> $DLL_NAME"

  # BUILD_TESTING is CMake's own CTest variable, NOT BUILD_TESTS — that is what
  # FEX gates on. With tests enabled, configure demands NASM to assemble the x86
  # test corpus and dies before compiling anything we want.
  # LTO off, and now for a reason that can be reproduced rather than a
  # judgement. The original comment said only that LTO "is unreliable across the
  # mingw link"; tried on 2026-08-08, it fails outright, and it fails in a
  # specific and informative way:
  #
  #   ld.lld: error: undefined symbol: std::__1::mutex::lock() (EC symbol)
  #   ld.lld: error: undefined symbol: std::__1::__shared_mutex_base::lock()
  #     (EC symbol)
  #   ...and ~40 more, every one of them tagged (EC symbol)
  #
  # Only the ARM64EC target. Every missing symbol comes from libc++, and ARM64EC
  # reaches those through hybrid mapping — each one needs its mangled EC
  # counterpart to survive to the link. LTO merges the libc++ archive members
  # before the linker gets to apply that mapping, so the EC names are gone by
  # the time anything looks for them. That is a toolchain limitation in
  # llvm-mingw's ARM64EC support, not something to work around here.
  #
  # It stays a switch so the question is one command after a toolchain bump:
  #
  #   VESSEL_FEX_LTO=1 ./build/fex.sh
  #   ./tools/device-bench.sh --only cpu --baseline out/bench/before.txt
  #
  # If a future llvm-mingw links it, benchmark before keeping it: FEX's
  # dispatcher is the hottest code in the project for any non-native program, so
  # the upside is real, and it should be beaten visibly.
  #
  # The bar used to be stated as "2.28x on x86-32 integer". That number is a
  # property of cpubench's 64-bit `int` section compiled for a 32-bit target,
  # not of translation — see docs/OPTIMIZATION.md. Use the `int32` row once it
  # has been run; do not reinstate 2.28x as a target.
  # `if`, not `[ … ] && …`: under `set -e` a failing test as a bare statement
  # takes the whole build down, so the off-by-default path would abort here.
  if [ "${VESSEL_FEX_LTO:-0}" = 1 ]; then
    FEX_LTO=True
    info "ENABLE_LTO=True (VESSEL_FEX_LTO=1)"
  else
    FEX_LTO=False
  fi

  cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DMINGW_TRIPLE="$TRIPLE" \
    -DCMAKE_BUILD_TYPE=Release \
    -DTUNE_CPU=oryon-1 \
    -DENABLE_LTO="$FEX_LTO" \
    -DENABLE_JEMALLOC_GLIBC_ALLOC=False \
    -DBUILD_TESTING=False \
    -DBUILD_FEX_LINUX_TESTS=False

  log "building $DLL_NAME"
  cmake --build "$BUILD" -j "$(build_jobs 1)"

  DLL="$(find "$BUILD" -type f -name "$DLL_NAME" -print -quit)"
  [ -n "$DLL" ] || die "build produced no $DLL_NAME (searched $BUILD).
     If the link failed on jemalloc symbols, add -DENABLE_JEMALLOC=False."

  # The naive "is it aarch64" test rejects a correct ARM64EC DLL — see the
  # verify_pe_dll comment in common.sh.
  verify_pe_dll "$DLL" "$TRIPLE" "$DLL_NAME"

  install -m 0644 "$DLL" "$STAGE/$DLL_NAME"
  ok "$DLL_NAME ($TRIPLE)"

  # The offline code-cache compiler, which cmake already built and this script
  # used to throw away.
  #
  # FEX-2608 has a full AOT pipeline for Windows: a run with
  # FEX_ENABLECODECACHINGWIP=1 writes a codemap under the cache directory,
  # `FEXOfflineCompiler64.exe generate <codemap>` turns that into a cache, and
  # later runs map the cache at image-load time instead of re-JITting. Without
  # this binary in the package the first step has nowhere to go, so shipping it
  # is the prerequisite for the whole feature.
  #
  # Packaged but not yet used: nothing runs `generate` — see
  # SessionEnvironment.kt for why the runtime flag stays off until something
  # does. Best-effort by design; a FEX that stops building it should not fail
  # the Wine-critical part of this package.
  COMPILER="$(find "$BUILD" -type f -name 'FEXOfflineCompiler*.exe' -print -quit || true)"
  if [ -n "$COMPILER" ]; then
    install -m 0644 "$COMPILER" "$STAGE/$(basename "$COMPILER")"
    ok "$(basename "$COMPILER") ($TRIPLE)"
  else
    info "no FEXOfflineCompiler for $TRIPLE — code-cache generation unavailable"
  fi
done

# Both DLLs sit at the package root, which is where the Wine side looks.
[ -f "$STAGE/libarm64ecfex.dll" ] || die "libarm64ecfex.dll missing from payload"
[ -f "$STAGE/libwow64fex.dll" ]   || die "libwow64fex.dll missing from payload"

# Record what was actually applied — no CPU tuning. Claiming -mcpu=oryon-1 here
# because the probe accepted it would put a flag in the UI that never reached a
# compiler. LTO rides along for the same reason: two packages that differ only
# in a link-time flag are otherwise indistinguishable after the fact, and this
# is the one flag anyone is likely to be A/B testing.
VESSEL_CPU_FLAGS="TUNE_CPU=oryon-1 (FEXCore C++ only; the JIT still detects the host CPU at runtime), LTO=$FEX_LTO"
write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type FEXCore \
  --name "FEX $VERSION ($TARGET_NAME)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "FEXCore $VERSION PE DLLs (arm64ec + wow64) built for $TARGET_DESC" \
  --out "$DIST_DIR/fex-$VERSION-$TARGET_NAME.wcp"

ok "dist/fex-$VERSION-$TARGET_NAME.wcp"
