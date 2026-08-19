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
# Vessel patches change what this builds without moving the upstream
# version, and the component store is keyed by type and version code --
# so an unchanged code makes the rebuild a silent no-op on the device.
VERSION_CODE="$(vessel_version_code "$VERSION" "${VKD3D_REVISION:-0}")"

fetch_source "$COMPONENT" "$VKD3D_REPO" "$VKD3D_REF" "${VKD3D_EXACT:-}"

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

# vkd3d-proton generates its COM headers from .idl at configure time and hard
# requires widl. There is never a plain `widl` here: mingw-w64-tools installs
# it prefixed, and llvm-mingw ships its own i686 copy. widl emits
# architecture-independent C headers, so any of them will do — including for
# the arm64ec pass, which has no widl of its own.
WIDL=""
for cand in /usr/bin/x86_64-w64-mingw32-widl \
            /usr/bin/i686-w64-mingw32-widl \
            "$MINGW_BIN/i686-w64-mingw32-widl" \
            "$(command -v widl 2>/dev/null || true)"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then WIDL="$cand"; break; fi
done
[ -n "$WIDL" ] || die "no widl found. vkd3d-proton cannot configure without it.
   Install mingw-w64-tools (provides x86_64-w64-mingw32-widl)."
#
# Hoisted above the pass loop by the ARM64X work: the native ARM64 half
# configures before that loop runs and needs the same generator.
info "widl: $WIDL"

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
widl-mingw-tools-fallback = '$WIDL'

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
c = '$PASS_CC'
cpp = '$PASS_CXX'
ar = '$MINGW_BIN/llvm-ar'
strip = '$MINGW_BIN/llvm-strip'
windres = '$MINGW_BIN/$TRIPLE-windres'
widl-mingw-tools-fallback = '$WIDL'

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
  # VKD3D_BREADCRUMBS=1 builds the one instrument that can explain a
  # VK_ERROR_DEVICE_LOST, and it is opt-in because it is not free.
  #
  # `enable_trace` is `auto`, which resolves to `vkd3d_debug` -- false under
  # `--buildtype release` -- and `meson.build` derives `enable_breadcrumbs` from
  # it, so a normal Vessel build has `VKD3D_ENABLE_BREADCRUMBS` undefined.
  # Confirmed on the shipped 3.0.1 payload: `d3d12core.dll` contains the config
  # *names* `breadcrumbs`, `breadcrumbs_sync` and `breadcrumbs_trace` and no
  # report strings at all, so `VKD3D_CONFIG=breadcrumbs` parses and then does
  # nothing. That is the trap this flag exists to avoid walking into twice.
  #
  # What it buys: on device loss vkd3d replays the command buffers it had in
  # flight and names the last draw/dispatch the GPU acknowledged, which is the
  # difference between "the GPU died" and knowing which command killed it.
  # Requiem currently loses the device during vkd3d's memory transfer queue,
  # before a swapchain exists, and nothing in Turnip's own log mentions it.
  #
  # **On by default since 2026-08-16, and the paragraph that used to be here
  # explaining why not is kept below, because it was right about the build it
  # described.** It said: "this is a diagnostic build; ship the default one."
  # What made that true was a single line, and `patches/vkd3d/0005` fixes it.
  #
  # `resource.c:4618` armed `initial_layout_transition_validate_only` on every
  # placed render target and depth buffer whenever breadcrumbs were *compiled
  # in*, without checking the runtime `VKD3D_CONFIG_FLAG_BREADCRUMBS` that the
  # other thirty-six sites in the file all check. The result was 46,591 ERR
  # lines in one Requiem session -- 93% of the log -- on a build nobody had
  # asked for a trace from. That is what made the diagnostic build unshippable,
  # and it was never the instrumentation itself.
  #
  # Audited before flipping this, rather than assumed: every `#ifdef
  # VKD3D_ENABLE_BREADCRUMBS` block in `libs/` that can emit was checked for the
  # runtime gate. Two came back ungated. `command.c:24471`'s per-command-list
  # INFO is a false positive -- its producer at `:22032` allocates
  # `breadcrumb_indices` only under `VKD3D_CONFIG_FLAG_BREADCRUMBS_TRACE`, so the
  # count is zero and the loop never runs. `resource.c:4618` was the real one.
  #
  # What remains is `-Denable_trace=true` compiling in every TRACE message. They
  # stay gated at runtime by `VKD3D_DEBUG`, which Vessel pins at `fixme`, so it
  # is binary size and not output: measured at 2.8 MiB against 2.6 MiB for the
  # release build, on a 78.9 MiB Wine package. That is the whole price.
  #
  # What it buys is that the instrument stops needing a rebuild at the moment
  # somebody needs it, which is always the worst moment to need one -- and the
  # Diagnostics screen's `breadcrumbs` row stops being a control that parses and
  # does nothing.
  #
  # Set VKD3D_BREADCRUMBS=0 for a build without it.
  VKD3D_TRACE_FLAG="-Denable_trace=true"
  if [ "${VKD3D_BREADCRUMBS:-1}" = "0" ]; then
    log "breadcrumbs disabled -- the instrument will not be available at runtime"
    VKD3D_TRACE_FLAG="-Denable_trace=false"
  fi

  meson setup "$BUILD" "$SRC" \
    --cross-file "$CROSS" \
    --prefix "$STAGE" \
    --bindir "$OUTDIR" \
    --libdir "$OUTDIR" \
    --buildtype release \
    -Dstrip=true \
    -Denable_tests=false \
    "$VKD3D_TRACE_FLAG" \
    -Db_lto="$(lto_flag)"

  log "building $COMPONENT ($TRIPLE)"
  ninja -C "$BUILD" -j "$(build_jobs 1)"
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
      # See verify_pe_dll in common.sh: an ARM64EC DLL reports machine type
      # AMD64, so checking for "aarch64" here would reject a good build.
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
  --type VKD3D \
  --name "vkd3d-proton $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "vkd3d-proton $VERSION, arm64ec system32 + i386 syswow64, built for $TARGET_DESC" \
  --out "$DIST_DIR/vkd3d-$VERSION-$TARGET_NAME.wcp"

ok "dist/vkd3d-$VERSION-$TARGET_NAME.wcp"
