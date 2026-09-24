#!/usr/bin/env bash
# Build the Steam client emulator (gbe_fork) as one `Steam` component.
#
#   ./build/gbe.sh              # -> dist/gbe-<ver>-any.wcp
#
# In Docker, like every other component:
#
#   docker run --rm -v "C:\Users\prith\Vessel:/src" -v vessel-work:/work vessel-build ./build/gbe.sh
#
# **What is built.** gbe_fork's *steamclient* shape, not its steam_api shape:
# steamclient.dll and steamclient64.dll, which are what a real Steam install
# keeps in C:\Program Files (x86)\Steam, and the loader that stands in for
# steam.exe. The app lays the payload's `Steam/` tree into exactly that folder.
# A game keeps its own untouched steam_api(64).dll, and that DLL finds the
# client the way it does on a PC -- through
# HKCU\Software\Valve\Steam\ActiveProcess -- which the loader writes before it
# starts the game and holds for as long as the game runs, because Valve's
# steam_api checks that the pid in there is alive. Nothing in a game folder is
# replaced, and one install serves every game.
#
# **Microsoft's ABI, not MinGW's.** Valve's steam_api64.dll is an MSVC binary
# and talks to steamclient64.dll through C++ interfaces, so the client has to
# lay out vtables and return values the way MSVC does. A MinGW build compiles
# and links cleanly and is wrong: overloaded virtuals are ordered differently,
# virtual destructors take two slots, and a class like CSteamID comes back in a
# register instead of through a hidden pointer. Measured: No Man's Sky loaded
# the MinGW client, logged `SteamAPI_Init(): Loaded ... OK`, and died on a read
# of an address that was a Steam ID; upstream's MSVC client ran the same game in
# the same prefix.
#
# So the image's clang targets `*-pc-windows-msvc`, against Microsoft's CRT and
# Windows SDK as `xwin` lays them out in /opt/xwin (Dockerfile), linking with
# lld-link and the static CRT (/MT, as upstream). gbe's own premake still drives
# the build -- `gmake` for `--os=windows` -- and `msvc-cc` below translates the
# few GNU linker flags those makefiles carry into lld-link's.
#
# `premake5 --genproto` runs the *Windows* protoc under Wine, which the image
# does not have, so a Linux protoc is built from the same protobuf archive and
# the three generate lines are run here instead.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init

COMPONENT=gbe
COMPONENT_REF="$GBE_REF"
# release-2026_08_23 -> 2026.8.23
VERSION="$(printf '%s' "${GBE_REF#release-}" | awk -F_ '{ printf "%d.%d.%d", $1, $2, $3 }')"
case "$VERSION" in *.*.*) ;; *) die "cannot read a version out of GBE_REF=$GBE_REF (expected release-YYYY_MM_DD)" ;; esac
VERSION_CODE="$(vessel_version_code "$VERSION" "${GBE_REVISION:-0}")"
info "gbe $VERSION revision ${GBE_REVISION:-0} as code $VERSION_CODE"

LLVM="${LLVM_MINGW_HOME:-/opt/llvm-mingw}/bin"
[ -x "$LLVM/clang" ] && [ -x "$LLVM/lld-link" ] || die "no clang/lld-link in $LLVM (the Docker image provides them)"

# --- Microsoft's CRT and Windows SDK -------------------------------------------
#
# Downloaded here rather than baked into the image: the image is published, and
# Microsoft's license allows downloading and using these, not redistributing
# them. `--accept-license` accepts it for whoever runs this build. Pinned to the
# versions in native/pins.env, so a rebuild next year gets the same SDK, and
# kept in the work volume, so it is fetched (~700 MB, under a minute) once. A
# stamp names the versions it holds; a pin change re-fetches.
XWIN="${XWIN_HOME:-$WORK_DIR/xwin}"
XWIN_WANT="xwin $XWIN_VERSION manifest $XWIN_MANIFEST sdk $XWIN_SDK crt $XWIN_CRT x86_64,x86"
if [ "$(cat "$XWIN/.vessel-xwin" 2>/dev/null)" != "$XWIN_WANT" ]; then
  command -v xwin >/dev/null 2>&1 || die "xwin is not installed; it is in the Dockerfile, so this is a stale image"
  [ "$(xwin --version | awk '{print $2}')" = "$XWIN_VERSION" ] \
    || die "the image has $(xwin --version) but native/pins.env pins xwin $XWIN_VERSION; rebuild the image"
  log "fetching Microsoft's CRT and Windows SDK ($XWIN_WANT)"
  rm -rf "$XWIN" "$WORK_DIR/xwin-cache"
  xwin --accept-license --manifest-version "$XWIN_MANIFEST" --sdk-version "$XWIN_SDK" \
      --crt-version "$XWIN_CRT" --arch x86_64,x86 --cache-dir "$WORK_DIR/xwin-cache" \
      splat --output "$XWIN" > "$WORK_DIR/xwin.log" 2>&1 \
    || { tail -20 "$WORK_DIR/xwin.log"; die "xwin could not fetch the pinned SDK/CRT"; }
  rm -rf "$WORK_DIR/xwin-cache"
  printf '%s' "$XWIN_WANT" > "$XWIN/.vessel-xwin"
fi
[ -f "$XWIN/crt/lib/x86_64/libcmt.lib" ] && [ -f "$XWIN/sdk/include/um/windows.h" ] \
  || die "the Microsoft CRT/SDK at $XWIN is incomplete; delete it and run again"

fetch_source "$COMPONENT" "$GBE_REPO" "$GBE_REF" "$GBE_COMMIT"

# --- A working copy on the work volume -----------------------------------------
#
# Not built in native/gbe: premake writes its projects and generated sources into
# the tree, the bind mount on a Windows host drops the exec bit on the premake
# binary it ships, and tens of thousands of objects across that mount are what
# make a build crawl.
SRC="$WORK_DIR/gbe-src"
DEPS="$WORK_DIR/gbe-deps"
OUT="$WORK_DIR/gbe-out"
TOOLS="$WORK_DIR/gbe-tools"
STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$SRC" "$DEPS" "$OUT" "$TOOLS" "$STAGE"
mkdir -p "$SRC" "$DEPS" "$OUT" "$TOOLS" "$STAGE"
tar -C "$NATIVE_DIR/$COMPONENT" --exclude=.git -cf - . | tar -C "$SRC" -xf -

PREMAKE="$SRC/third-party/common/linux/premake/premake5"
[ -f "$PREMAKE" ] || die "no premake at $PREMAKE -- the third-party/common/linux submodule did not check out"
chmod +x "$PREMAKE"
command -v 7za >/dev/null 2>&1 || die "7za is not installed; it is in the Dockerfile, so this is a stale image"

JOBS="$(build_jobs 1)"

# --- The MSVC-target compiler ---------------------------------------------------

# msvc-cc <triple> <arch dir> <clang|clang++>: clang for Microsoft's ABI with the
# CRT and SDK on its paths, and gbe's GNU linker flags made lld-link's.
write_msvc_cc() {
  local triple="$1" arch="$2" driver="$3" out="$TOOLS/$1-$3"
  cat > "$out" <<EOF
#!/usr/bin/env bash
args=()
for a in "\$@"; do
  case "\$a" in
    # lld-link names the import library itself; GNU ld had to be told.
    -Wl,--out-implib=*) args+=("-Wl,/implib:\${a#-Wl,--out-implib=}") ;;
    # GNU-only: grouping (lld resolves archives in any order), stripping,
    # static libgcc, ELF symbol hiding, and PIC (meaningless for PE, an error
    # for this target).
    -Wl,--start-group|-Wl,--end-group|-s|-static|-Wl,--exclude-libs,ALL|-fPIC|-fpic) ;;
    *) args+=("\$a") ;;
  esac
done
# /Brepro: the PE timestamp becomes a hash of the image, so two builds of the
# same pins are byte-identical -- without it the link time was the only
# difference between them.
exec "$LLVM/$driver" --target=$triple -fuse-ld=lld -fms-runtime-lib=static -Wl,/Brepro \\
  -idirafter "$XWIN/crt/include" -idirafter "$XWIN/sdk/include/ucrt" \\
  -idirafter "$XWIN/sdk/include/um" -idirafter "$XWIN/sdk/include/shared" -idirafter "$XWIN/sdk/include/winrt" \\
  -L"$XWIN/crt/lib/$arch" -L"$XWIN/sdk/lib/um/$arch" -L"$XWIN/sdk/lib/ucrt/$arch" \\
  -Wno-unused-command-line-argument -Wno-c++11-narrowing -Wno-invalid-token-paste \
  -lkernel32 -luser32 -lgdi32 -lwinspool -lcomdlg32 -ladvapi32 -lshell32 -lole32 -loleaut32 -luuid \\
  "\${args[@]}"
EOF
  chmod +x "$out"
}
# clang-cl <triple>: the same compiler with MSVC's command line, for the CMake
# dependencies. CMake then treats it as the MSVC it is standing in for, which
# is what their build files expect -- mbedtls adds /W3 and /utf-8 on any MSVC
# target, and a GNU-style driver takes those for file names.
write_clang_cl() {
  local triple="$1" out="$TOOLS/$1-clang-cl"
  cat > "$out" <<EOF
#!/usr/bin/env bash
# premake5-deps.lua gives the overlay two MinGW fixes whenever the action is
# gmake: GCC's force-include, which is /FI here, and -fpermissive, whose job
# MSVC mode already does.
args=()
while [ \$# -gt 0 ]; do
  case "\$1" in
    -include) args+=("/FI\$2"); shift ;;
    -fpermissive) ;;
    # The static CRT for every dependency, as upstream builds them: mbedtls
    # and portaudio choose the DLL runtime themselves on an MSVC target, and
    # their objects then import rand() and wcscpy() that nothing exports.
    /MD|-MD|/MDd|-MDd) args+=("/MT") ;;
    *) args+=("\$1") ;;
  esac
  shift
done
exec "$LLVM/clang" --driver-mode=cl --target=$triple \\
  /imsvc "$XWIN/crt/include" /imsvc "$XWIN/sdk/include/ucrt" \\
  /imsvc "$XWIN/sdk/include/um" /imsvc "$XWIN/sdk/include/shared" /imsvc "$XWIN/sdk/include/winrt" \\
  -Wno-unused-command-line-argument "\${args[@]}"
EOF
  chmod +x "$out"
}
for spec in x86_64-pc-windows-msvc:x86_64 i686-pc-windows-msvc:x86; do
  write_msvc_cc "${spec%%:*}" "${spec##*:}" clang
  write_msvc_cc "${spec%%:*}" "${spec##*:}" clang++
  write_clang_cl "${spec%%:*}"
done

# --- Dependencies ----------------------------------------------------------------

log "extracting dependencies"
( cd "$SRC" && "$PREMAKE" --file=premake5-deps.lua --all-ext --custom-extractor=7za \
    --custom-cmake=cmake --deps-dir="$DEPS" --os=windows gmake ) > "$WORK_DIR/gbe-extract.log" 2>&1 \
  || { tail -20 "$WORK_DIR/gbe-extract.log"; die "extracting gbe's dependency archives failed"; }

premake_deps() {
  # <bits> <toolchain> <premake build switches...>
  local bits="$1" toolchain="$2"; shift 2
  ( cd "$SRC" && CMAKE_GENERATOR="Unix Makefiles" "$PREMAKE" --file=premake5-deps.lua \
      "$@" "--$bits-build" --custom-cmake=cmake --custom-extractor=7za \
      --cmake-toolchain="$toolchain" --deps-dir="$DEPS" --j="$JOBS" --os=windows gmake ) \
      >> "$WORK_DIR/gbe-deps$bits.log" 2>&1 \
    || { grep -m20 -B2 -A4 'error' "$WORK_DIR/gbe-deps$bits.log" || tail -30 "$WORK_DIR/gbe-deps$bits.log"
         die "gbe dependencies ($bits-bit: $*) failed; full log: $WORK_DIR/gbe-deps$bits.log"; }
}

build_deps() {
  # <bits> <triple> <cmake processor> <xwin arch dir>
  local bits="$1" triple="$2" processor="$3" arch="$4"
  local toolchain="$TOOLS/toolchain-$triple.cmake"
  local libpaths="/libpath:$XWIN/crt/lib/$arch /libpath:$XWIN/sdk/lib/um/$arch /libpath:$XWIN/sdk/lib/ucrt/$arch"
  cat > "$toolchain" <<EOF
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR $processor)
set(CMAKE_C_COMPILER $TOOLS/$triple-clang-cl)
set(CMAKE_CXX_COMPILER $TOOLS/$triple-clang-cl)
set(CMAKE_RC_COMPILER $LLVM/llvm-rc)
set(CMAKE_LINKER $LLVM/lld-link)
set(CMAKE_AR $LLVM/llvm-lib)
set(CMAKE_MT $LLVM/llvm-mt)
set(CMAKE_EXE_LINKER_FLAGS_INIT "$libpaths")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "$libpaths")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "$libpaths")
set(CMAKE_FIND_ROOT_PATH $XWIN)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
# The static release CRT (/MT) everywhere, as upstream builds, and for CMake's
# own probe programs too: those default to Debug, whose CRT xwin does not fetch.
# The policy default makes CMAKE_MSVC_RUNTIME_LIBRARY hold for projects whose
# cmake_minimum_required predates it.
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreaded)
set(CMAKE_TRY_COMPILE_CONFIGURATION Release)
# Nothing here runs a dependency's tests, and zlib's build them with --coverage,
# whose runtime this toolchain does not carry.
set(BUILD_TESTING OFF CACHE BOOL "")
set(ZLIB_BUILD_TESTING OFF CACHE BOOL "")
EOF
  : > "$WORK_DIR/gbe-deps$bits.log"
  log "building dependencies ($bits-bit, $triple)"
  # zlib and mbedtls first: the deps script names their archives the way a
  # GNU toolchain would (libzs.a) when curl and protobuf are pointed at them,
  # and an MSVC-target build writes zs.lib. The GNU names are made to exist
  # before the libraries that link them are configured.
  premake_deps "$bits" "$toolchain" --build-zlib --build-mbedtls
  local lib name
  for lib in zlib/install$bits/lib/zs mbedtls/install$bits/lib/mbedtls \
             mbedtls/install$bits/lib/mbedcrypto mbedtls/install$bits/lib/mbedx509; do
    name="$(basename "$lib")"
    [ -f "$DEPS/$lib.lib" ] || die "the $bits-bit build produced no $lib.lib"
    ln -sf "$name.lib" "$DEPS/$(dirname "$lib")/lib$name.a"
  done
  premake_deps "$bits" "$toolchain" --build-ssq --build-curl --build-protobuf \
    --build-ingame_overlay --build-opus --build-portaudio --build-sdl
  ok "dependencies ($bits-bit)"
}
build_deps 64 x86_64-pc-windows-msvc AMD64 x86_64
build_deps 32 i686-pc-windows-msvc X86 x86

# --- Protobuf sources, with a Linux protoc --------------------------------------

log "generating protobuf sources"
PROTOC_BUILD="$WORK_DIR/gbe-protoc-host"
rm -rf "$PROTOC_BUILD"
cmake -S "$DEPS/protobuf" -B "$PROTOC_BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF -Dprotobuf_WITH_ZLIB=OFF \
    > "$WORK_DIR/gbe-protoc.log" 2>&1 \
  && ninja -C "$PROTOC_BUILD" -j "$JOBS" protoc >> "$WORK_DIR/gbe-protoc.log" 2>&1 \
  || { tail -20 "$WORK_DIR/gbe-protoc.log"; die "building a host protoc failed"; }
PROTOC="$PROTOC_BUILD/protoc"
PROTO_INC="$DEPS/protobuf/src"
(
  cd "$SRC"
  mkdir -p proto_gen/win/tf2
  # The same three invocations as premake5.lua's genproto().
  "$PROTOC" dll/gc_steam/steammessages.proto -I./dll/gc_steam -I"$PROTO_INC" --cpp_out=proto_gen/win
  "$PROTOC" dll/gc_tf2/*.proto -I./dll/gc_steam -I./dll/gc_tf2 -I"$PROTO_INC" --cpp_out=proto_gen/win/tf2 2>/dev/null
  "$PROTOC" dll/net.proto -I./dll/ -I"$PROTO_INC" --cpp_out=proto_gen/win
) || die "protoc failed"
ok "protobuf sources ($("$PROTOC" --version))"

# --- The emulator ---------------------------------------------------------------

log "generating the emulator's makefiles"
( cd "$SRC" && "$PREMAKE" --file=premake5.lua --os=windows --deps-dir="$DEPS" --build-dir="$OUT" \
    --emubuild="vessel-$SOURCE_SHA" gmake ) > "$WORK_DIR/gbe-premake.log" 2>&1 \
  || { tail -20 "$WORK_DIR/gbe-premake.log"; die "premake failed"; }
MAKEDIR="$SRC/build/project/gmake/win"
TARGETS="steamclient_experimental steamclient_experimental_loader steamclient_experimental_extra lib_game_overlay_renderer"

# Every library the makefiles name, made findable under exactly that name.
#
# Two conventions meet here and neither matches premake's -l names: the SDK
# ships `ws2_32.lib` and `WS2_32.lib` but the makefiles say `-lWs2_32` (MSVC's
# file system never cared), and CMake named the dependencies `libcurl.lib` and
# `libprotobuf.lib` where the makefiles say `-lcurl`. A name that resolves to
# nothing is an error here, with the name, rather than an lld-link error later.
resolve_libs() {
  # <bits> <xwin arch dir> <shim dir>
  local bits="$1" arch="$2" shim="$3" n want hit dir
  rm -rf "$shim"; mkdir -p "$shim"
  local dirs=("$XWIN/sdk/lib/um/$arch" "$XWIN/sdk/lib/ucrt/$arch" "$XWIN/crt/lib/$arch")
  for dir in "$DEPS"/*/install$bits/lib "$DEPS"/*/deps/*/install$bits/lib "$DEPS"/*/build$bits; do
    [ -d "$dir" ] && dirs+=("$dir")
  done
  for n in $(grep -ho -- ' -l[A-Za-z0-9_.+-]*' "$MAKEDIR"/*/Makefile | sed 's/^ -l//' | sort -u); do
    want="$n.lib"
    hit=""
    for dir in "${dirs[@]}"; do
      [ -f "$dir/$want" ] && { hit=exact; break; }
    done
    [ -n "$hit" ] && continue
    for dir in "${dirs[@]}"; do
      hit="$(find "$dir" -maxdepth 1 \( -iname "$want" -o -iname "lib$want" \) -print -quit 2>/dev/null)"
      [ -n "$hit" ] && break
    done
    [ -n "$hit" ] || die "no library for -l$n ($bits-bit) in the SDK or the built dependencies"
    ln -sf "$hit" "$shim/$want"
  done
}

build_emu() {
  # <premake config> <triple> <bits> <xwin arch dir>
  local config="$1" triple="$2" bits="$3" arch="$4"
  local shim="$TOOLS/libs$bits"
  resolve_libs "$bits" "$arch" "$shim"
  log "building the emulator ($config, $triple)"
  make -C "$MAKEDIR" -j "$JOBS" config="$config" \
      CC="$TOOLS/$triple-clang" CXX="$TOOLS/$triple-clang++" LDFLAGS="-L$shim" \
      AR="$LLVM/llvm-ar" RESCOMP="$LLVM/llvm-rc" \
      $TARGETS > "$WORK_DIR/gbe-emu$bits.log" 2>&1 \
    || { grep -m20 -B1 -A3 'error:\|undefined' "$WORK_DIR/gbe-emu$bits.log" || tail -30 "$WORK_DIR/gbe-emu$bits.log"
         die "the emulator ($config) failed; full log: $WORK_DIR/gbe-emu$bits.log"; }
  ok "emulator ($config)"
}
build_emu release_x64 x86_64-pc-windows-msvc 64 x86_64
build_emu release_x86 i686-pc-windows-msvc 32 x86

# --- Stage --------------------------------------------------------------------

log "staging"
BUILT="$OUT/win/gmake/release"
STEAM="$STAGE/Steam"
mkdir -p "$STEAM/steam_settings"

stage_pe() {
  # <built file name> <expected machine: x86-64|80386> [dir under Steam/]
  local name="$1" machine="$2" into="$STEAM/${3:-}" found
  found="$(find "$BUILT" -type f -name "$name" | head -1)"
  [ -n "$found" ] || die "the build produced no $name under $BUILT"
  file -b "$found" | grep -q "$machine" || die "$name is not $machine: $(file -b "$found")"
  mkdir -p "$into"
  cp "$found" "$into/$name"
}
stage_pe steamclient64.dll           x86-64
stage_pe steamclient.dll             80386
stage_pe steamclient_loader_x64.exe  x86-64
stage_pe steamclient_loader_x86.exe  80386
stage_pe GameOverlayRenderer64.dll   x86-64
stage_pe GameOverlayRenderer.dll     80386
# gbe's SteamStub DRM helper, one folder per architecture because the loader
# injects everything in the folder it is given. Injected at startup by the
# loader, and only for an exe carrying the DRM's `.bind` section
# (app.vessel.core.SteamClient.hasSteamStub). Never `steam_settings/load_dlls`:
# gbe's own README warns that loading it there costs "a huge FPS drop".
stage_pe steamclient_extra_x64.dll   x86-64 extra_dlls/x64
stage_pe steamclient_extra_x86.dll   80386  extra_dlls/x86

for pe in "$STEAM"/*.dll "$STEAM"/*.exe "$STEAM"/extra_dlls/*/*.dll; do
  # A game's steam_api resolves the client through `CreateInterface`; a build
  # that lost its exports would install cleanly and then fail every game.
  case "$(basename "$pe")" in
    steamclient.dll|steamclient64.dll)
      "$LLVM/llvm-readobj" --coff-exports "$pe" | grep -q 'Name: CreateInterface$' \
        || die "$(basename "$pe") does not export CreateInterface" ;;
  esac
  # The static CRT is what makes these self-contained; a DLL importing the
  # debug or dynamic MSVC runtime would need a redistributable the prefix may
  # not have.
  if "$LLVM/llvm-readobj" --coff-imports "$pe" | grep -qiE 'Name: (msvcp|vcruntime)[0-9]*d?\.dll|ucrtbased\.dll'; then
    die "$(basename "$pe") imports the MSVC runtime DLLs; it must link the static CRT"
  fi
done

# Settings every game shares, read from beside steamclient(64).dll.
#
# steam_input=1 turns the controller code on from the first call, which the
# emulator otherwise does only when it has action sets -- that is what the
# per-game steam_settings/controller/*.txt files were doing for No Man's Sky.
# With patches/gbe/0001 the action sets come from the game's own Steam Input
# manifest, so no per-game file is needed.
cat > "$STEAM/steam_settings/configs.app.ini" <<'EOF'
[app::controller]
steam_input=1
EOF

cat > "$STAGE/provenance.json" <<EOF
{
  "component": "$COMPONENT",
  "version": "$VERSION",
  "target": "any",
  "targetDesc": "x86_64 and i686 Windows PEs (MSVC ABI), laid into C:\\\\Program Files (x86)\\\\Steam",
  "sourceRepo": "$SOURCE_REPO",
  "sourceRef": "$GBE_REF",
  "sourceSha": "$SOURCE_SHA",
  "cpuFlags": "none",
  "ndk": "n/a",
  "apiLevel": "n/a",
  "builtBy": "vessel-build (clang $("$LLVM/clang" -dumpversion), MSVC target, xwin $XWIN_VERSION)"
}
EOF

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Steam \
  --name "Steam client emulator (gbe_fork $VERSION)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "gbe_fork $GBE_REF with Vessel's patches, in its steamclient shape: steamclient.dll, steamclient64.dll and the loader, installed into C:\\Program Files (x86)\\Steam. A game keeps its own steam_api(64).dll, which finds this client through HKCU\\Software\\Valve\\Steam\\ActiveProcess the way it finds Steam on a PC; Vessel starts Steam games through the loader, which writes those keys and holds them while the game runs." \
  --out "$DIST_DIR/$COMPONENT-$VERSION-any.wcp"

ok "dist/$COMPONENT-$VERSION-any.wcp"
