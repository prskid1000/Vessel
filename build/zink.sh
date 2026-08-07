#!/usr/bin/env bash
# Build Mesa/Zink as an ARM64EC PE opengl32.dll and package it as a .wcp.
#
#   ./build/zink.sh             # -> dist/zink-<ver>-<target>.wcp
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
BUILD="$WORK_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$BUILD" "$STAGE"
mkdir -p "$STAGE/system32"

# Mesa's VERSION file says "26.3.0-devel" for months at a time, so the commit is
# the real identity.
MESA_VERSION="$(tr -d '[:space:]' < "$SRC/VERSION" 2>/dev/null || true)"
[ -n "$MESA_VERSION" ] || die "no VERSION file in $SRC — is this really a Mesa tree?"
VERSION="$MESA_VERSION-${SOURCE_SHA:0:8}"

TRIPLE=arm64ec-w64-mingw32
TRIPLE_CC="$MINGW_BIN/$TRIPLE-clang"
[ -x "$TRIPLE_CC" ] || die "no compiler for $TRIPLE at $TRIPLE_CC"

resolve_cpu_flags "$TRIPLE_CC"

CPU_ARGS=""
for f in ${VESSEL_CPU_FLAGS:-}; do
  if [ -n "$CPU_ARGS" ]; then CPU_ARGS="$CPU_ARGS, "; fi
  CPU_ARGS="$CPU_ARGS'$f'"
done

# --- meson cross file --------------------------------------------------------
#
# cpu_family is 'aarch64' even though the triple is arm64ec: the code generated
# is ARM64, and EC only changes the PE container and the thunks. Saying 'x86_64'
# — which is what the resulting PE header's machine type says — would make Mesa
# compile x86 intrinsics clang cannot codegen for an ARM64 backend.
#
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

CROSS="$WORK_DIR/$COMPONENT-cross.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$MINGW_BIN/$TRIPLE-clang'
cpp = '$MINGW_BIN/$TRIPLE-clang++'
ar = '$MINGW_BIN/llvm-ar'
strip = '$MINGW_BIN/llvm-strip'
windres = '$MINGW_BIN/$TRIPLE-windres'
pkg-config = '$PKG_CONFIG'

[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['$EMPTY_PKGDIR']

[host_machine]
system = 'windows'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$CPU_ARGS]
cpp_args = [$CPU_ARGS]
EOF

info "cross file: $CROSS"

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

log "configuring $COMPONENT (gallium/zink, $TRIPLE)"

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
  --bindir system32 \
  --libdir system32 \
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

log "building $COMPONENT"
ninja -C "$BUILD" -j "$(build_jobs 1)"
ninja -C "$BUILD" install

# --- artifacts ---------------------------------------------------------------
# libgallium_wgl.dll is checked as hard as opengl32.dll: the latter is a thin
# shim that imports from it, so both are required at runtime.
for dll in opengl32.dll libgallium_wgl.dll; do
  OUT="$STAGE/system32/$dll"
  [ -f "$OUT" ] || die "build produced no system32/$dll.
     Locate what it did produce with:
       find $BUILD -name '*.dll'"
  # Do not replace this with a file(1) test. An EC image's machine type is
  # IMAGE_FILE_MACHINE_ARM64EC (0xA641), which file(1) renders as "x86-64" —
  # indistinguishable by eye from a plain AMD64 build. The CHPE load-config
  # directory is the only thing separating the two, and a plain ARM64 PE has no
  # CHPE either, so both silent fallbacks look fine without this check.
  verify_pe_dll "$OUT" "$TRIPLE" "system32/$dll"
  info "$dll: $(file -b "$OUT")"
done

# Wine's gdi32 does not link opengl32 — dlls/gdi32/opengl.c resolves these five
# by name, lazily, on the first pixel-format or buffer-swap call, and falls back
# to its own stub if one is missing, with no load-time error. A
# correctly-architectured DLL that does not export them is still a failed build.
EXPORTS="$("$MINGW_BIN/llvm-readobj" --coff-exports "$STAGE/system32/opengl32.dll" 2>/dev/null || true)"
for sym in wglSetPixelFormat wglChoosePixelFormat wglDescribePixelFormat \
           wglGetPixelFormat wglSwapBuffers; do
  grep -qE "^[[:space:]]*Name: $sym\$" <<< "$EXPORTS" \
    || die "opengl32.dll does not export $sym.
     Wine's gdi32 GetProcAddress()es it (dlls/gdi32/opengl.c) and silently
     stubs the call if it is absent, so this DLL would load and do nothing.
     Check src/gallium/targets/libgl-gdi/opengl32.def.in in the Mesa tree."
done
ok "opengl32.dll exports the WGL entry points gdi32 resolves by name"

# EGL and GLES are a bonus, not the deliverable; losing them is worth a warning
# but not a failed build.
for dll in libEGL.dll libGLESv1_CM.dll libGLESv2.dll; do
  OUT="$STAGE/system32/$dll"
  if [ -f "$OUT" ]; then
    verify_pe_dll "$OUT" "$TRIPLE" "system32/$dll"
  else
    warn "no system32/$dll — shipping desktop GL only"
    rm -f "$OUT"
  fi
done

# meson install also drops import libraries, .pc files and headers into the
# prefix, and package_wcp.py would faithfully ship every byte of it.
find "$STAGE" -mindepth 1 -maxdepth 1 -type d ! -name system32 -exec rm -rf {} +
find "$STAGE/system32" -type f ! -name '*.dll' -delete
find "$STAGE/system32" -mindepth 1 -type d -exec rm -rf {} + 2>/dev/null || true

log "payload:"
( cd "$STAGE" && find . -type f | sort | sed 's/^\./  /' )

write_provenance "$STAGE/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type OpenGL \
  --name "Zink $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Mesa Zink desktop OpenGL over Vulkan as an ARM64EC opengl32.dll, built from $COMPONENT_REF @ ${SOURCE_SHA:0:12} for $TARGET_DESC" \
  --out "$DIST_DIR/zink-$VERSION-$TARGET_NAME.wcp"

ok "dist/zink-$VERSION-$TARGET_NAME.wcp"
