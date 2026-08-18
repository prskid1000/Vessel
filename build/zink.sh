#!/usr/bin/env bash
# Build Mesa/Zink as an opengl32.dll for both halves of the container and
# package it as a .wcp.
#
#   ./build/zink.sh             # -> dist/zink-<ver>-<target>.wcp
#
#   system32/  arm64ec PE — what native and x86-64-under-EC apps load
#   syswow64/  i386 PE    — what 32-bit apps load, translated by libwow64fex
#
# The 32-bit half is not a nicety, and leaving it out broke something that looks
# unrelated. `WINEDLLOVERRIDES` sets `opengl32=n` for every process in the
# container, so a 32-bit process has no native opengl32 to load and Wine refuses
# the builtin — and because wined3d imports opengl32, the failure surfaces as
#
#   Library opengl32.dll (which is needed by wined3d.dll) not found
#   cannot load d3dcompiler_47.dll
#
# from a 32-bit D3D11 program that never asked for OpenGL at all. Shipping only
# system32/ made every i686 D3D probe fail for a packaging reason.
#
# A native opengl32.dll in system32 takes the entire WGL surface and
# winex11.drv is never consulted (see the export check near the bottom). That is
# the only option here — our Wine is built without GLX, so otherwise an OpenGL
# app has nothing to load at all. Upstream converges on the same design in Wine
# MR !10531. Zink runs desktop GL over Vulkan and needs no LLVM, so
# -Dllvm=disabled is correct rather than a compromise.
#
# Nobody had built Mesa for arm64ec-w64-mingw32 before (mmozeiko/build-mesa is
# plain ARM64 with MSVC, and a plain ARM64 PE will NOT load into our ARM64EC
# Wine processes). It took one patch: clang for arm64ec predefines __x86_64__
# and __amd64__ — truthfully, EC is x64-ABI-compatible — but NOT __aarch64__,
# so Mesa's arch detection put the whole build on the x86 path and died inside
# clang's own <mmintrin.h>. See patches/mesa/0002-arm64ec-arch-detection.patch.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_mingw

COMPONENT=zink

# Same Mesa checkout as the Turnip build, so the two graphics components cannot
# drift onto different Mesa revisions. ZINK_MESA_REPO / ZINK_MESA_REF in
# native/pins.env are the escape hatch: if the gen8 Vulkan fork breaks the
# Windows/WGL path (it carries out-of-tree freedreno work and nothing in its CI
# builds for Windows), set them and this build moves to native/mesa-zink without
# touching Turnip.
if [ -n "${ZINK_MESA_REF:-}" ]; then
  SOURCE_NAME=mesa-zink
  SOURCE_REPO="${ZINK_MESA_REPO:-https://gitlab.freedesktop.org/mesa/mesa.git}"
  COMPONENT_REF="$ZINK_MESA_REF"
  SOURCE_SHA_PIN=""
  warn "building Zink from a separate Mesa pin ($SOURCE_REPO @ $ZINK_MESA_REF),
     not the Turnip tree. Clear ZINK_MESA_REF in native/pins.env to share it."
else
  SOURCE_NAME=mesa
  SOURCE_REPO="$MESA_REPO"
  COMPONENT_REF="$MESA_REF"
  # Same checkout as Turnip, so it must take the same pin or the two components
  # would report different Mesa versions from one tree.
  SOURCE_SHA_PIN="${MESA_SHA:-}"
fi

fetch_source "$SOURCE_NAME" "$SOURCE_REPO" "$COMPONENT_REF" "${SOURCE_SHA_PIN:-}"

SRC="$NATIVE_DIR/$SOURCE_NAME"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# Mesa's VERSION file says "26.3.0-devel" for months at a time, so the commit is
# the real identity.
MESA_VERSION="$(tr -d '[:space:]' < "$SRC/VERSION" 2>/dev/null || true)"
[ -n "$MESA_VERSION" ] || die "no VERSION file in $SRC — is this really a Mesa tree?"
VERSION="$MESA_VERSION-${SOURCE_SHA:0:8}"

# Vessel: Mesa's VERSION plus a commit is not enough to move a version code.
# `ComponentStore` is keyed by type and version code, so a package whose code
# already exists on the phone is treated as bytes the device has and is never
# unpacked -- it builds, installs, and does nothing, silently. Rebuilding the
# same Mesa commit as ARM64X is exactly that case, so this carries a revision
# of its own like every other component. Bump ZINK_REVISION in native/pins.env
# whenever what this produces changes without the source moving.
#
# **This changes the scale of the code, once, and it cannot be undone.** Mesa's
# version already reaches six digits -- version_code("26.3.0-devel") is 260300 --
# and vessel_version_code multiplies by a further 100, so the first revisioned
# build is 26030001 against the 260300 that shipped before it. Higher still wins,
# so the store adopts it and nothing breaks. But reverting to the unrevisioned
# call would emit 260300 again, which is *lower* than what is already on the
# phone, and `adoptLatest` only ever moves forward -- the package would install
# and never be chosen. If this ever has to go back, the revision goes up, not
# away.
VERSION_CODE="$(vessel_version_code "$MESA_VERSION" "${ZINK_REVISION:-0}")"

# pkg_config_libdir points at an empty directory ON PURPOSE. In a cross build
# meson resolves `auto` features with the BUILD machine's pkg-config, and will
# report that the container's x86-64 Linux zlib/expat/zstd satisfy a
# Windows-on-ARM dependency — "found, wrong architecture" rather than a clean
# "not found".
PKG_CONFIG="$(command -v pkg-config || true)"
[ -n "$PKG_CONFIG" ] || die "pkg-config not found on PATH; meson wants it even when nothing should be found"

EMPTY_PKGDIR="$WORK_DIR/$COMPONENT-empty-pkgconfig"
rm -rf "$EMPTY_PKGDIR"
mkdir -p "$EMPTY_PKGDIR"

# --- option sanity -----------------------------------------------------------
# Mesa renames options between releases and this is a fast-moving fork, so name
# the wrong one rather than burying it in meson output. gallium-wgl-dll-name is
# listed not because we set it (we must not) but because its presence proves
# this tree still has the megadriver split packaging assumes.
MESON_OPTS=""
for candidate in "$SRC/meson.options" "$SRC/meson_options.txt"; do
  if [ -f "$candidate" ]; then MESON_OPTS="$candidate"; break; fi
done
[ -n "$MESON_OPTS" ] || die "no meson.options or meson_options.txt in $SRC — is this really a Mesa tree?"

for opt in platforms gallium-drivers vulkan-drivers llvm egl gles1 gles2 opengl \
           video-codecs spirv-tools shared-glapi gallium-wgl-dll-name; do
  grep -q "'$opt'" "$MESON_OPTS" \
    || die "meson option '$opt' does not exist in $MESON_OPTS.
     The $COMPONENT_REF branch renamed or dropped it. Check with:
       grep -n \"^option(\" $MESON_OPTS"
done

# src/gallium/meson.build only descends into targets/libgl-gdi under
# `if with_platform_windows and with_opengl`. If that guard moves, the build
# succeeds and produces no opengl32.dll at all.
grep -q "targets/libgl-gdi" "$SRC/src/gallium/meson.build" \
  || die "src/gallium/meson.build no longer builds targets/libgl-gdi.
     That subdir is what produces opengl32.dll. Find where it moved:
       grep -rn libgl-gdi $SRC/src/gallium/"

# --- the two passes ----------------------------------------------------------
#
# Same source, same options, two toolchains and two output directories. The
# 32-bit pass is not optional: see the header comment — a container whose
# WINEDLLOVERRIDES says opengl32=n and whose syswow64 has no opengl32.dll cannot
# start a 32-bit wined3d-linked program at all, and the error it prints names
# d3dcompiler rather than OpenGL.
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
  meson setup "$NATIVE_BUILD" "$SRC" \
    --cross-file "$NATIVE_CROSS" \
    --default-library=static \
    -Dbuildtype=release \
    -Db_ndebug=true \
    -Db_lto=false \
    -Dllvm=disabled \
    -Dplatforms=windows \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers= \
    -Dvideo-codecs= \
    -Degl=enabled \
    -Dgles1=enabled \
    -Dgles2=enabled \
    -Dspirv-tools=disabled \
    -Dstrip=true
  log "building $COMPONENT ($NATIVE_TRIPLE, objects only)"
  ninja -C "$NATIVE_BUILD" -j "$(build_jobs 1)"
fi

for PASS in 64 32; do
  case "$PASS" in
    # cpu_family is 'aarch64' even though the triple is arm64ec: the code
    # generated is ARM64, and EC only changes the PE container and the thunks.
    # Saying 'x86_64' — which is what the resulting PE header's machine type
    # says — would make Mesa compile x86 intrinsics clang cannot codegen for an
    # ARM64 backend.
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
  # code FEX translates, where host flags mean nothing. Probing on the first
  # pass also leaves VESSEL_CPU_FLAGS set for the provenance record.
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
pkg-config = '$PKG_CONFIG'

[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['$EMPTY_PKGDIR']

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

  log "configuring $COMPONENT (gallium/zink, $TRIPLE -> $OUTDIR/)"

  # The load-bearing options:
  #
  #   -Dllvm=disabled          Zink needs no LLVM. On `auto`, meson finds the
  #                            container's x86-64 LLVM and tries to link it into
  #                            an ARM64 PE.
  #   -Dvulkan-drivers=        no ICD in this package; Zink resolves vulkan-1.dll
  #                            at runtime, so there is no link-time dependency.
  #   --default-library=static makes opengl32.dll self-contained apart from
  #                            libgallium_wgl.dll.
  #
  # No -Db_lto: Mesa refuses to configure with it, by explicit check in
  # meson.build ("Building Mesa with LTO is not supported"). Do not work around it.
  #
  # -Dspirv-tools=disabled is the concrete case of the empty pkg_config_libdir
  # above, and the second component to hit it: on `auto`, meson finds the
  # container's SPIRV-Tools, defines HAVE_SPIRV_TOOLS, and the cross compile dies
  # on 'spirv-tools/libspirv.h'. Stated explicitly so the build does not depend on
  # the empty search path working.
  #
  # Deliberately NOT set: -Dgallium-wgl-dll-name. The WGL megadriver is
  # libgallium_wgl.dll and targets/libgl-gdi builds a thin opengl32.dll on top of
  # it; renaming the megadriver would collide with that target. Both ship.
  meson setup "$BUILD" "$SRC" \
    --cross-file "$CROSS" \
    --default-library=static \
    --prefix "$STAGE" \
    --bindir "$OUTDIR" \
    --libdir "$OUTDIR" \
    -Dbuildtype=release \
    -Db_ndebug=true \
    -Dllvm=disabled \
    -Dplatforms=windows \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers= \
    -Dvideo-codecs= \
    -Degl=enabled \
    -Dgles1=enabled \
    -Dgles2=enabled \
    -Dspirv-tools=disabled \
    -Dstrip=true

  log "building $COMPONENT ($TRIPLE)"
  ninja -C "$BUILD" -j "$(build_jobs 1)"
  ninja -C "$BUILD" install

  # --- artifacts -------------------------------------------------------------
  # libgallium_wgl.dll is checked as hard as opengl32.dll: the latter is a thin
  # shim that imports from it, so both are required at runtime.
  for dll in opengl32.dll libgallium_wgl.dll; do
    OUT="$STAGE/$OUTDIR/$dll"
    [ -f "$OUT" ] || die "build produced no $OUTDIR/$dll.
     Locate what it did produce with:
       find $BUILD -name '*.dll'"
    # Do not replace this with a file(1) test for the 64-bit pass. An EC image's
    # machine type is IMAGE_FILE_MACHINE_ARM64EC (0xA641), which file(1) renders
    # as "x86-64" — indistinguishable by eye from a plain AMD64 build. The CHPE
    # load-config directory is the only thing separating the two, and a plain
    # ARM64 PE has no CHPE either, so both silent fallbacks look fine without
    # this check. verify_pe_dll knows the i686 case too.
    verify_pe_dll "$OUT" "$TRIPLE" "$OUTDIR/$dll"
    info "$dll: $(file -b "$OUT")"
  done

  # Wine's gdi32 does not link opengl32 — dlls/gdi32/opengl.c resolves these five
  # by name, lazily, on the first pixel-format or buffer-swap call, and falls back
  # to its own stub if one is missing, with no load-time error. A
  # correctly-architectured DLL that does not export them is still a failed build.
  #
  # The 32-bit DLL exports them with a stdcall @N suffix in the symbol table but
  # a clean name in the export directory, which is what this reads, so one check
  # covers both passes.
  EXPORTS="$("$MINGW_BIN/llvm-readobj" --coff-exports "$STAGE/$OUTDIR/opengl32.dll" 2>/dev/null || true)"
  for sym in wglSetPixelFormat wglChoosePixelFormat wglDescribePixelFormat \
             wglGetPixelFormat wglSwapBuffers; do
    grep -qE "^[[:space:]]*Name: $sym\$" <<< "$EXPORTS" \
      || die "$OUTDIR/opengl32.dll does not export $sym.
     Wine's gdi32 GetProcAddress()es it (dlls/gdi32/opengl.c) and silently
     stubs the call if it is absent, so this DLL would load and do nothing.
     Check src/gallium/targets/libgl-gdi/opengl32.def.in in the Mesa tree."
  done
  ok "$OUTDIR/opengl32.dll exports the WGL entry points gdi32 resolves by name"

  # EGL and GLES are a bonus, not the deliverable; losing them is worth a warning
  # but not a failed build.
  for dll in libEGL.dll libGLESv1_CM.dll libGLESv2.dll; do
    OUT="$STAGE/$OUTDIR/$dll"
    if [ -f "$OUT" ]; then
      verify_pe_dll "$OUT" "$TRIPLE" "$OUTDIR/$dll"
    else
      warn "no $OUTDIR/$dll — shipping desktop GL only for this architecture"
      rm -f "$OUT"
    fi
  done

  # meson install also drops import libraries, .pc files and headers into the
  # prefix, and package_wcp.py would faithfully ship every byte of it.
  find "$STAGE/$OUTDIR" -type f ! -name '*.dll' -delete
  find "$STAGE/$OUTDIR" -mindepth 1 -type d -exec rm -rf {} + 2>/dev/null || true

  ok "$OUTDIR/ ($TRIPLE)"
done

# Anything meson put outside the two payload directories (include/, lib/pkgconfig
# and friends) belongs to a build machine, not to a Windows system directory.
find "$STAGE" -mindepth 1 -maxdepth 1 -type d ! -name system32 ! -name syswow64 \
  -exec rm -rf {} +

# The whole point of the second pass. If a future Mesa stops producing an i386
# opengl32, this must fail rather than quietly ship the package that broke
# 32-bit D3D11.
[ -f "$STAGE/syswow64/opengl32.dll" ] \
  || die "no syswow64/opengl32.dll in the payload — 32-bit programs would fail
     to start with 'Library opengl32.dll (which is needed by wined3d.dll) not
     found', because WINEDLLOVERRIDES sets opengl32=n for every process."

log "payload:"
( cd "$STAGE" && find . -type f | sort | sed 's/^\./  /' )

write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type OpenGL \
  --name "Zink $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Mesa Zink desktop OpenGL over Vulkan, arm64ec system32 + i386 syswow64, built from $COMPONENT_REF @ ${SOURCE_SHA:0:12} for $TARGET_DESC" \
  --out "$DIST_DIR/zink-$VERSION-$TARGET_NAME.wcp"

ok "dist/zink-$VERSION-$TARGET_NAME.wcp"
