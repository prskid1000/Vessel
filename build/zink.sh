#!/usr/bin/env bash
# Build Mesa/Zink as an ARM64EC PE opengl32.dll and package it as a .wcp.
#
# WHY A NATIVE opengl32.dll AND NOT winex11.drv
#
# Wine's gdi32 does not own pixel formats. SetPixelFormat, ChoosePixelFormat,
# DescribePixelFormat, GetPixelFormat and SwapBuffers each lazily do
#   LoadLibraryW(L"opengl32.dll"); GetProcAddress(..., "wglSetPixelFormat")
# in dlls/gdi32/opengl.c and hand the call straight over. So dropping a native
# opengl32.dll into system32 takes the entire WGL surface and winex11.drv is
# never consulted — exactly the substitution DXVK makes for d3d11.dll.
#
# That is not merely convenient here, it is the only option: our Wine is built
# without GLX (the Android sysroot has no desktop libGL), so without this
# package an OpenGL application has nothing to load at all. Wine upstream is
# converging on the same architecture in MR !10531, "opengl32: Just use Zink".
#
# Zink implements desktop GL on top of Vulkan, so the stack on device is
#   app -> opengl32.dll (Zink) -> vulkan-1.dll -> winevulkan -> Turnip
# and it needs no LLVM: -Dllvm=disabled is correct, not a compromise. There is
# therefore no cross-LLVM problem of the kind an llvmpipe build would have.
#
#   ./build/zink.sh             # -> dist/zink-<ver>-<target>.wcp
#
# No published Mesa build targets arm64ec-w64-mingw32 — the reference
# Windows-on-ARM Mesa builds (mmozeiko/build-mesa) are plain ARM64 with MSVC,
# and a plain ARM64 PE will NOT load into our ARM64EC Wine processes. It took
# exactly one patch to get there: clang for arm64ec predefines __x86_64__ and
# __amd64__ (truthfully — EC is x64-ABI-compatible) but not __aarch64__, so
# Mesa's arch detection put the whole build on the x86 path and died inside
# clang's own <mmintrin.h>. See patches/mesa/0002-arm64ec-arch-detection.patch.
# verify_pe_dll below is what keeps a silent fallback to plain x64 or plain
# ARM64 from ever shipping — see its CHPE check in common.sh.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_mingw

COMPONENT=zink

# Same Mesa checkout as the Turnip build, deliberately. native/ and patches/
# are keyed on the source name, the package on the driver — the same split
# turnip.sh makes. One tree means one fetch in CI and no chance of the two
# graphics components drifting onto different Mesa revisions.
#
# ZINK_MESA_REPO / ZINK_MESA_REF exist in native/pins.env as an escape hatch:
# if the gen8 Vulkan fork ever breaks the Windows/WGL path (it carries
# out-of-tree freedreno work, and nothing in its CI builds for Windows), set
# them to upstream Mesa and this build moves to native/mesa-zink without
# touching Turnip. Leave them empty to share the tree.
if [ -n "${ZINK_MESA_REF:-}" ]; then
  SOURCE_NAME=mesa-zink
  SOURCE_REPO="${ZINK_MESA_REPO:-https://gitlab.freedesktop.org/mesa/mesa.git}"
  COMPONENT_REF="$ZINK_MESA_REF"
  warn "building Zink from a separate Mesa pin ($SOURCE_REPO @ $ZINK_MESA_REF),
     not the Turnip tree. Clear ZINK_MESA_REF in native/pins.env to share it."
else
  SOURCE_NAME=mesa
  SOURCE_REPO="$MESA_REPO"
  COMPONENT_REF="$MESA_REF"
fi

fetch_source "$SOURCE_NAME" "$SOURCE_REPO" "$COMPONENT_REF"

SRC="$NATIVE_DIR/$SOURCE_NAME"
BUILD="$WORK_DIR/$COMPONENT"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$BUILD" "$STAGE"
mkdir -p "$STAGE/system32"

# Same versioning rationale as turnip.sh: Mesa's VERSION file says something
# like "26.3.0-devel" for months at a time, so the commit is the real identity.
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
# cpu_family is 'aarch64' even though the triple is arm64ec. That is the honest
# answer to the question meson is asking — the code generated is ARM64, and the
# only thing EC changes is the PE container and the calling-convention thunks.
# Telling meson 'x86_64' (which is what the machine type in the resulting PE
# header says) would make Mesa compile x86 intrinsics that clang cannot codegen
# for an ARM64 backend.
#
# pkg_config_libdir is pointed at an empty directory ON PURPOSE. In a cross
# build meson resolves `auto` features with whatever pkg-config is on PATH,
# which is the BUILD machine's, and it will happily report that the container's
# x86-64 Linux zlib/expat/zstd satisfy a Windows-on-ARM dependency. That is the
# same trap documented under -Dspirv-tools below, generalised: an empty search
# path makes every host package invisible so every auto feature resolves to
# "not found" instead of "found, wrong architecture".
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
# Mesa renames options between releases and this tree is a fast-moving fork, so
# a wrong name here should say which name is wrong rather than being buried in
# meson output. gallium-wgl-dll-name is on the list not because we set it (we
# must not — see below) but because its presence is what tells us this tree
# still has the megadriver split the packaging step assumes.
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

# src/gallium/meson.build only descends into targets/wgl and targets/libgl-gdi
# under `if with_platform_windows and with_opengl`. If that guard moves, the
# build succeeds and produces no opengl32.dll at all, so assert on it here
# where the message can name the file.
grep -q "targets/libgl-gdi" "$SRC/src/gallium/meson.build" \
  || die "src/gallium/meson.build no longer builds targets/libgl-gdi.
     That subdir is what produces opengl32.dll. Find where it moved:
       grep -rn libgl-gdi $SRC/src/gallium/"

log "configuring $COMPONENT (gallium/zink, $TRIPLE)"

# Option-by-option, because most of these are load-bearing:
#
#   -Dgallium-drivers=zink   the whole point. No llvmpipe, no softpipe: both
#                            would drag in either LLVM or a lot of dead code
#                            for a fallback that would be unusably slow anyway.
#   -Dllvm=disabled          Zink needs no LLVM. Verified by mmozeiko's arm64
#                            Windows builds, which use exactly this. Leaving it
#                            `auto` invites meson to find the container's x86-64
#                            LLVM and try to link it into an ARM64 PE.
#   -Dvulkan-drivers=        we ship no ICD in this package; the Vulkan driver
#                            is Turnip, installed separately. Zink resolves
#                            vulkan-1.dll at runtime, so there is no link-time
#                            Vulkan dependency to satisfy here.
#   -Dplatforms=windows      selects the WGL/GDI window-system integration.
#   -Dvideo-codecs=          no VA/codec surface on Windows; keeps the tree small.
#   -Degl/-Dgles1/-Dgles2    free with the WGL target and useful to any app that
#                            asks for GLES. Dropped automatically below if they
#                            do not produce DLLs.
#   --default-library=static everything except the DLL targets themselves links
#                            in statically, which is what makes opengl32.dll
#                            self-contained apart from libgallium_wgl.dll.
#
# No -Db_lto: Mesa refuses to configure with it, by explicit check in
# meson.build ("Building Mesa with LTO is not supported"). Not our limitation;
# do not try to work around it.
#
# -Dspirv-tools=disabled is not cosmetic, and this is the second component to
# hit it. The option defaults to `auto`, meson resolves it with the pkg-config
# on PATH — the BUILD machine's — finds the container's SPIRV-Tools, defines
# HAVE_SPIRV_TOOLS, and the cross compile then dies on a header that was never
# in the target sysroot:
#     vtn_debug.c:11: fatal error: 'spirv-tools/libspirv.h' file not found
# The empty pkg_config_libdir above should already prevent it, but stating the
# option means the build does not depend on that working.
#
# Deliberately NOT set: -Dgallium-wgl-dll-name. In this Mesa the WGL megadriver
# is libgallium_wgl.dll and targets/libgl-gdi builds a thin opengl32.dll on top
# of it; renaming the megadriver to opengl32 would collide with that target.
# Both DLLs ship, side by side in system32/, which is where the loader looks.
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
#
# opengl32.dll is the deliverable and its absence is fatal. libgallium_wgl.dll
# is equally required at runtime — opengl32.dll is a thin shim that imports
# from it — so it is checked just as hard.
for dll in opengl32.dll libgallium_wgl.dll; do
  OUT="$STAGE/system32/$dll"
  [ -f "$OUT" ] || die "build produced no system32/$dll.
     Locate what it did produce with:
       find $BUILD -name '*.dll'"
  # The check that actually matters, and do not replace it with a file(1) test.
  # An EC image's machine type is IMAGE_FILE_MACHINE_ARM64EC (0xA641), which
  # file(1) prints as "x86-64" — correct in spirit, since the whole point of EC
  # is presenting an x64-callable surface, but indistinguishable from a plain
  # AMD64 build by eye. The CHPE load-config directory is the only thing that
  # separates the two, and a plain ARM64 PE has no CHPE either. Since nobody
  # had built Mesa for this triple before, a silent fallback to one of those
  # two is exactly the outcome worth catching.
  verify_pe_dll "$OUT" "$TRIPLE" "system32/$dll"
  info "$dll: $(file -b "$OUT")"
done

# The architecture in one assertion.
#
# Wine's gdi32 does not link opengl32 — dlls/gdi32/opengl.c resolves these five
# by name, lazily, on the first pixel-format or buffer-swap call. If any one of
# them is missing from the export table, gdi32 falls back to its own stub and
# the application gets no OpenGL, with no load-time error to explain it. So a
# correctly-architectured DLL that does not export them is still a failed
# build, and it is worth failing here rather than on the phone.
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

# EGL and the GLES libraries are a bonus, not the deliverable. If Mesa's
# Windows path stops producing them, that is worth saying but not worth
# failing a build whose whole purpose is desktop OpenGL.
for dll in libEGL.dll libGLESv1_CM.dll libGLESv2.dll; do
  OUT="$STAGE/system32/$dll"
  if [ -f "$OUT" ]; then
    verify_pe_dll "$OUT" "$TRIPLE" "system32/$dll"
  else
    warn "no system32/$dll — shipping desktop GL only"
    rm -f "$OUT"
  fi
done

# meson install also drops import libraries (.a/.lib), .pc files and headers
# into the prefix. None of that belongs in a container payload, and
# package_wcp.py would faithfully ship every byte of it.
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
