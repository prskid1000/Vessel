#!/usr/bin/env bash
# Build vkd3d-proton as PE DLLs for an ARM64EC container and package it as a .wcp.
#
# The D3D12 counterpart to build/dxvk.sh, and built the same way and for the
# same reason: the translation layer is hot code, so it runs native (ARM64EC)
# while only the game's own x86 is translated. Same two passes, same layout:
#
#   system32/  arm64ec PE — loaded by native and x86-64-under-EC apps
#   syswow64/  i386 PE    — loaded by 32-bit apps, translated by libwow64fex
#
# Turnip and this must be updated as a pair — vkd3d-proton leans on Vulkan
# extensions that a mismatched driver may not expose.
#
#   ./build/vkd3d.sh            # -> dist/vkd3d-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_mingw

COMPONENT=vkd3d
COMPONENT_REF="$VKD3D_REF"
VERSION="${VKD3D_REF#v}"

fetch_source "$COMPONENT" "$VKD3D_REPO" "$VKD3D_REF"

SRC="$NATIVE_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$STAGE"
mkdir -p "$STAGE"

command -v glslangValidator >/dev/null 2>&1 \
  || die "glslangValidator not found on PATH. vkd3d-proton compiles its meta
     shaders at build time and needs it (Debian/Ubuntu: glslang-tools)."

# vkd3d-proton carries dxil-spirv and the Khronos headers as submodules and
# does not build without them. fetch_source clones recursively, so an empty
# subprojects/ means the clone predates that or a submodule URL moved.
if [ -d "$SRC/subprojects" ] && [ -z "$(ls -A "$SRC/subprojects")" ]; then
  die "$SRC/subprojects is empty — submodules are not checked out.
     Fix with: git -C $SRC submodule update --init --recursive"
fi

# -Denable_tests is the one non-builtin option passed below. Confirm it still
# exists rather than discovering it in a wall of meson output.
MESON_OPTS=""
for candidate in "$SRC/meson.options" "$SRC/meson_options.txt"; do
  if [ -f "$candidate" ]; then
    MESON_OPTS="$candidate"
    break
  fi
done
if [ -n "$MESON_OPTS" ]; then
  grep -q "'enable_tests'" "$MESON_OPTS" \
    || die "meson option 'enable_tests' does not exist in $MESON_OPTS.
     $VKD3D_REF renamed it. Check with: grep -n \"^option(\" $MESON_OPTS"
else
  warn "no meson.options/meson_options.txt in $SRC; cannot pre-check -Denable_tests"
fi

# VERIFY: vkd3d-proton has no ARM64EC CI upstream, and dxil-spirv is the part
# most likely to object (it is the most x86-assuming code in the tree). If the
# build fails there, patch it under patches/vkd3d/ — apply_patches will pick it
# up once the directory exists.

for PASS in 64 32; do
  case "$PASS" in
    64) TRIPLE=arm64ec-w64-mingw32; OUTDIR=system32; CPU_FAMILY=aarch64; CPU=aarch64 ;;
    32) TRIPLE=i686-w64-mingw32;    OUTDIR=syswow64; CPU_FAMILY=x86;     CPU=i686 ;;
    *)  die "unhandled pass $PASS" ;;
  esac

  TRIPLE_CC="$MINGW_BIN/$TRIPLE-clang"
  [ -x "$TRIPLE_CC" ] || die "no compiler for $TRIPLE at $TRIPLE_CC
     (the 32-bit half of a container needs llvm-mingw's i686 target too)"

  # Tuning applies to the ARM64EC half only — the 32-bit DLLs are x86 code that
  # FEX translates. The first pass also leaves VESSEL_CPU_FLAGS set for the
  # provenance record.
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
c = '$MINGW_BIN/$TRIPLE-clang'
cpp = '$MINGW_BIN/$TRIPLE-clang++'
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

  # bindir and libdir both point at the payload directory so the DLLs land flat
  # in one place, the same trick upstream's package-release.sh uses for x64/x86.
  meson setup "$BUILD" "$SRC" \
    --cross-file "$CROSS" \
    --prefix "$STAGE" \
    --bindir "$OUTDIR" \
    --libdir "$OUTDIR" \
    --buildtype release \
    -Dstrip=true \
    -Denable_tests=false

  log "building $COMPONENT ($TRIPLE)"
  ninja -C "$BUILD" -j "$(nproc_safe)"
  ninja -C "$BUILD" install

  # d3d12core.dll holds the implementation; d3d12.dll is the thin loader that
  # finds it. Shipping one without the other produces a container that fails
  # only when a D3D12 title starts, so both are required here.
  for dll in d3d12.dll d3d12core.dll; do
    OUT="$STAGE/$OUTDIR/$dll"
    [ -f "$OUT" ] || die "build produced no $OUTDIR/$dll (looked in $STAGE/$OUTDIR).
     If $VKD3D_REF renamed or merged the loader, update this check — do not
     drop it."
    if [ "$PASS" = 64 ]; then
      file "$OUT" | grep -Eqi 'PE32\+.*(aarch64|arm64)' \
        || die "$OUTDIR/$dll is not an ARM64 PE: $(file -b "$OUT")"
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
  --type VKD3D \
  --name "vkd3d-proton $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "vkd3d-proton $VERSION, arm64ec system32 + i386 syswow64, built for $TARGET_DESC" \
  --out "$DIST_DIR/vkd3d-$VERSION-$TARGET_NAME.wcp"

ok "dist/vkd3d-$VERSION-$TARGET_NAME.wcp"
