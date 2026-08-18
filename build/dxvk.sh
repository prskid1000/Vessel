#!/usr/bin/env bash
# Build DXVK as PE DLLs for an ARM64EC container and package it as a .wcp.
#
# In a Universal container the D3D-to-Vulkan layer is usually the hottest code
# in a game, so it is built native (ARM64EC) rather than left as x86-64 for FEX
# to translate. Two passes, because a container serves both worlds:
#
#   system32/  arm64ec PE — what native and x86-64-under-EC apps load
#   syswow64/  i386 PE    — what 32-bit apps load, translated by libwow64fex
#
# That layout is not ours: it is what Winlator-family apps expect a DXVK
# package to look like, so the .wcp stays installable in them too.
#
#   ./build/dxvk.sh             # -> dist/dxvk-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_mingw

COMPONENT=dxvk
COMPONENT_REF="$DXVK_REF"
VERSION="${DXVK_REF#v}"
# Vessel patches change what this builds without moving the upstream version,
# and the component store is keyed by type and version code -- so an unchanged
# code makes the rebuild a silent no-op on the device.
#
# This script did not compute one until now, which meant *every* DXVK build
# since patches/dxvk/0001 landed has shipped the same code as stock 2.7.1. A
# phone that already had 2.7.1 installed would accept the .wcp and keep what it
# had, and the only symptom is a counter that never changes. vkd3d.sh, wine.sh,
# turnip.sh and fex.sh all did this correctly; dxvk.sh and zink.sh were the two
# that did not, and zink's VERSION carries a source SHA so it moves on its own.
VERSION_CODE="$(vessel_version_code "$VERSION" "${DXVK_REVISION:-0}")"

fetch_source "$COMPONENT" "$DXVK_REPO" "$DXVK_REF"

SRC="$NATIVE_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# DXVK compiles its internal shaders at build time. Without glslang meson fails
# late and cryptically, so say it here.
command -v glslangValidator >/dev/null 2>&1 \
  || die "glslangValidator not found on PATH. DXVK compiles its shaders at build
     time and needs it (Debian/Ubuntu: glslang-tools)."

# Upstream's package-release.sh is not used: its cross files hard-code the
# x86_64/i686 mingw triples, and its output layout is x64/x32 rather than the
# system32/syswow64 pair we have to produce. The meson invocation below is the
# same one it makes, with our triples and our directory names.

# VERIFY: DXVK has no ARM64EC CI upstream. If the build stops on x86 intrinsics
# (__rdtsc, _mm_*), or meson rejects the host cpu_family, the fix is a patch in
# patches/dxvk/ — create the directory and apply_patches will find it.

# Vessel: the native ARM64 half, built first and never installed.
#
# Its objects are the other half of the hybrid the 64-bit pass links below, and
# `arm64x_wrappers` pairs them per target by directory name. Nothing here reaches
# the payload -- `ninja` and not `ninja install` -- because what ships is the one
# ARM64X image, not two. docs/ARM64X.md.
NATIVE_TRIPLE=aarch64-w64-mingw32
NATIVE_CC="$MINGW_BIN/$NATIVE_TRIPLE-clang"
[ -x "$NATIVE_CC" ] || die "no compiler for $NATIVE_TRIPLE at $NATIVE_CC
   (an ARM64X build needs llvm-mingw's aarch64 target as well as arm64ec)"

NATIVE_CROSS="$WORK_DIR/$COMPONENT-$NATIVE_TRIPLE.ini"
cat > "$NATIVE_CROSS" <<EOF
[binaries]
c = '$MINGW_BIN/$NATIVE_TRIPLE-clang'
cpp = '$MINGW_BIN/$NATIVE_TRIPLE-clang++'
ar = '$MINGW_BIN/llvm-ar'
strip = '$MINGW_BIN/llvm-strip'
windres = '$MINGW_BIN/$NATIVE_TRIPLE-windres'

[host_machine]
system = 'windows'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

NATIVE_BUILD="$WORK_DIR/$COMPONENT-$NATIVE_TRIPLE"
rm -rf "$NATIVE_BUILD"
# **ARM64X is opt-in, and the default is off, because it regressed software
# that worked.** Building these three as hybrids is what finally let an ARM64
# build of VS Code load dxgi.dll instead of failing with
# STATUS_INVALID_IMAGE_FORMAT -- a real bug, really fixed. It did not make VS
# Code work: its renderer still does not paint. And Resident Evil Requiem,
# which ran before, began faulting -- 22 unrepaired reads against zero in the
# run before it, dying inside its own exception handler after vkd3d had brought
# a D3D12 device up. Metro 2033 was unaffected, and Metro is D3D11 through DXVK
# where Requiem is D3D12 through vkd3d.
#
# So the capability stays in the tree and the default does not use it. Build
# hybrids with VESSEL_ARM64X=1. docs/ARM64X.md carries the recipe, and what is
# still unknown is why a hybrid vkd3d misbehaves under x64 emulation when a
# hybrid DXVK does not.
if [ "${VESSEL_ARM64X:-0}" = 1 ]; then
  log "configuring $COMPONENT for $NATIVE_TRIPLE (native half of the hybrid)"
  meson setup "$NATIVE_BUILD" "$SRC"   --cross-file "$NATIVE_CROSS"   --buildtype release   -Dstrip=true   -Db_lto=false
  log "building $COMPONENT ($NATIVE_TRIPLE, objects only)"
  ninja -C "$NATIVE_BUILD" -j "$(build_jobs 1)"
fi

for PASS in 64 32; do
  case "$PASS" in
    64) TRIPLE=arm64ec-w64-mingw32; OUTDIR=system32; CPU_FAMILY=aarch64; CPU=aarch64 ;;
    32) TRIPLE=i686-w64-mingw32;    OUTDIR=syswow64; CPU_FAMILY=x86;     CPU=i686 ;;
    *)  die "unhandled pass $PASS" ;;
  esac

  # The hybrid applies to the 64-bit half only: syswow64 is i386 PE that FEX
  # translates, and there is no ARM64 view for it to carry.
  if [ "$PASS" = 64 ] && [ "${VESSEL_ARM64X:-0}" = 1 ]; then
    arm64x_wrappers "$NATIVE_BUILD" "$MINGW_BIN/$TRIPLE-clang" "$MINGW_BIN/$TRIPLE-clang++"
    PASS_CC="$ARM64X_CC"; PASS_CXX="$ARM64X_CXX"
  else
    PASS_CC="$MINGW_BIN/$TRIPLE-clang"; PASS_CXX="$MINGW_BIN/$TRIPLE-clang++"
  fi

  TRIPLE_CC="$MINGW_BIN/$TRIPLE-clang"
  [ -x "$TRIPLE_CC" ] || die "no compiler for $TRIPLE at $TRIPLE_CC
     (the 32-bit half of a container needs llvm-mingw's i686 target too)"

  # Chip tuning only makes sense for the ARM64EC half; the 32-bit DLLs are x86
  # code that FEX translates, where host flags mean nothing. Probing on the
  # first pass also leaves VESSEL_CPU_FLAGS set for the provenance record.
  CPU_ARGS=""
  if [ "$PASS" = 64 ]; then
    resolve_cpu_flags "$TRIPLE_CC"
    for f in ${VESSEL_CPU_FLAGS:-}; do
      if [ -n "$CPU_ARGS" ]; then CPU_ARGS="$CPU_ARGS, "; fi
      CPU_ARGS="$CPU_ARGS'$f'"
    done
  fi

  CROSS="$WORK_DIR/$COMPONENT-$TRIPLE.ini"
  cat > "$CROSS" <<EOF
[binaries]
c = '$PASS_CC'
cpp = '$PASS_CXX'
ar = '$MINGW_BIN/llvm-ar'
strip = '$MINGW_BIN/llvm-strip'
windres = '$MINGW_BIN/$TRIPLE-windres'

[host_machine]
system = 'windows'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[built-in options]
c_args = [$CPU_ARGS]
cpp_args = [$CPU_ARGS]
EOF

  BUILD="$WORK_DIR/$COMPONENT-$TRIPLE"
  rm -rf "$BUILD"

  log "configuring $COMPONENT for $TRIPLE -> $OUTDIR/"

  # bindir and libdir both point at the payload directory: meson puts DLLs in
  # bindir and import libraries in libdir, and we want the DLLs flat in one
  # place. This is exactly what upstream's release script does with x64/x32.
  meson setup "$BUILD" "$SRC" \
    --cross-file "$CROSS" \
    --prefix "$STAGE" \
    --bindir "$OUTDIR" \
    --libdir "$OUTDIR" \
    --buildtype release \
    -Dstrip=true \
    -Db_lto="$(lto_flag)"

  log "building $COMPONENT ($TRIPLE)"
  ninja -C "$BUILD" -j "$(build_jobs 1)"
  ninja -C "$BUILD" install

  # d3d10core/d3d8 come and go between releases; these three are the ones no
  # DXVK build is complete without.
  for dll in dxgi.dll d3d11.dll d3d9.dll; do
    OUT="$STAGE/$OUTDIR/$dll"
    [ -f "$OUT" ] || die "build produced no $OUTDIR/$dll (looked in $STAGE/$OUTDIR)"
    if [ "$PASS" = 64 ]; then
      # Shared with fex.sh and vkd3d.sh — and it has to be shared, because the
      # naive "is it aarch64" test rejects a correct ARM64EC DLL. See the
      # verify_pe_dll comment in common.sh.
      verify_pe_dll "$OUT" "$TRIPLE" "$OUTDIR/$dll"
    else
      file "$OUT" | grep -Eqi 'PE32 .*(80386|intel)' \
        || die "$OUTDIR/$dll is not a 32-bit x86 PE: $(file -b "$OUT")"
    fi
  done

  ok "$OUTDIR/ ($TRIPLE)"
done

write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type DXVK \
  --name "DXVK $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "DXVK $VERSION, arm64ec system32 + i386 syswow64, built for $TARGET_DESC" \
  --out "$DIST_DIR/dxvk-$VERSION-$TARGET_NAME.wcp"

ok "dist/dxvk-$VERSION-$TARGET_NAME.wcp"
