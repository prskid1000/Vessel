#!/usr/bin/env bash
# Build ARM64EC-capable Wine for Android and package it as a .wcp.
#
# This is what makes the Universal container profile possible: Wine's own DLLs
# are native ARM64EC, so only the application's x86 code is translated (by FEX,
# loaded as a PE module). Three PE architectures come out of one configure:
#
#   arm64ec  — the EC side; x86-64 apps call into native Wine through it
#   aarch64  — plain ARM64, for native Windows-on-ARM apps and the WoW64 side
#   i386     — 32-bit x86 modules, run under WoW64 + libwow64fex.dll
#
# ---------------------------------------------------------------------------
# THIS IS THE LONGEST BUILD IN THE PROJECT. An hour or more on a fast machine,
# several on anything else, and it is by far the most likely component to need
# patches: WINE_REF is a development branch carrying the ARM64EC work ahead of
# mainline. When it breaks, the fix belongs in patches/wine/ — apply_patches
# picks up anything dropped there, in filename order.
# ---------------------------------------------------------------------------
#
# THREE TOOLCHAINS, NOT ONE. Wine is not a single build; it is two halves plus
# the generators that stitch them together:
#
#   host   — winebuild/wrc/widl/wmc. Code generators that must execute on the
#            BUILD machine, so they are compiled with the container's gcc into
#            a throwaway tree and handed to the cross pass via
#            --with-wine-tools. Without this, a cross build tries to run
#            aarch64 tools on x86-64 and dies at the first generated file.
#   PE     — arm64ec/aarch64/i386 Windows modules, via llvm-mingw (--with-mingw).
#   Unix   — wineserver, ntdll.so, win32u.so, winex11.drv.so. These are ELF
#            binaries that run ON THE PHONE, so they are cross-compiled with the
#            NDK against bionic. Building them with the container's gcc produces
#            x86-64 glibc objects that cannot load on the device — which is what
#            the old "X 64-bit development files not found" failure was really
#            telling us: configure was probing the build machine.
#
# The NDK ships no X11, and --without-x is not shippable — the container's
# display path is Wine's X11 driver talking to the app's built-in X server. So
# build/x11-sysroot.sh cross-builds a minimal X11 client stack into
# $WORK_DIR/androidsysroot first, and the cross pass links winex11.drv.so
# against that.
#
#   ./build/wine.sh             # -> dist/wine-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk
setup_mingw

COMPONENT=wine
COMPONENT_REF="$WINE_REF"

fetch_source "$COMPONENT" "$WINE_REPO" "$WINE_REF"

SRC="$NATIVE_DIR/$COMPONENT"

# WINE_REF is a branch, so the ref carries no version. Wine keeps its own in a
# VERSION file at the tree root: "Wine version 10.12".
VERSION="$(sed -n 's/^Wine version[[:space:]]*//p' "$SRC/VERSION" 2>/dev/null | head -1 || true)"
[ -n "$VERSION" ] || die "could not read a version out of $SRC/VERSION"
info "wine version $VERSION (branch $WINE_REF @ ${SOURCE_SHA:0:12})"

BUILD="$WORK_DIR/wine-android"
TOOLS="$WORK_DIR/wine-tools"
STAGE="$WORK_DIR/stage-$COMPONENT"
SYSROOT="$WORK_DIR/androidsysroot"
# --prefix=/usr + DESTDIR=$STAGE puts the tree at $STAGE/usr, and that
# directory — bin/, lib/, share/ — is exactly what goes into the package.
PAYLOAD="$STAGE/usr"

# Deliberately no resolve_cpu_flags here. CFLAGS reaches only the unix side and
# CROSSCFLAGS all three PE architectures at once, so -mcpu=oryon-1 has nowhere
# safe to live: it is meaningless to the i386 pass and the unix side is loader
# and syscall plumbing, not a hot path. The guest code and DXVK are where the
# time goes, and both are tuned in their own builds.

if [ ! -x "$SRC/configure" ]; then
  info "no configure script in the tree; running autoreconf"
  ( cd "$SRC" && autoreconf -f -i ) || die "autoreconf failed; install autoconf/automake"
fi

# STAGE 1 SCRATCH: x11-sysroot.sh call disabled while the dual-toolchain build
# is being proven. Restored before Stage 2.
mkdir -p "$SYSROOT/usr/lib/pkgconfig" "$SYSROOT/usr/include"

# --- Pass 1: native build tools -----------------------------------------------
# Wine generates a large part of itself with its own tools (winebuild, wrc,
# widl, wmc). Those tools have to run on the *build* machine, so in a cross
# build they cannot come from the cross tree — they are built first, natively,
# and handed over with --with-wine-tools.
#
# __tooldeps__ builds only the code generators, not all of Wine, so this pass
# costs a couple of minutes rather than an hour.
#
# The tools tree is configured with everything optional switched off: none of it
# affects the generators, and every probe it skips is one fewer host library
# that has to be present in the image.
if [ -x "$TOOLS/tools/winebuild/winebuild" ] && [ -e "$TOOLS/nls/locale.nls" ]; then
  info "reusing native tools in $TOOLS"
else
  log "pass 1/2: native build tools (host gcc)"
  rm -rf "$TOOLS"
  mkdir -p "$TOOLS"
  ( cd "$TOOLS" && "$SRC/configure" \
      --enable-win64 \
      --disable-tests \
      --without-mingw \
      --without-x \
      --without-freetype ) \
    || die "native tools configure failed (see $TOOLS/config.log)"
  make -C "$TOOLS" -j"$(build_jobs 1)" __tooldeps__ \
    || die "native tools build failed"

  # __tooldeps__ builds the binaries but not the NLS tables, and wrc/wmc load
  # those at runtime to resolve codepages. They find them relative to their own
  # executable — tools/wrc/../../nls, i.e. inside the TOOLS tree, never the cross
  # tree — so a cross build with only __tooldeps__ dies on the first resource:
  #     Error: unable to load locale.nls
  #     make: *** [dlls/appwiz.cpl/appwiz.res] Error 2
  # nls/all just symlinks the tables in from the source tree; it costs nothing.
  make -C "$TOOLS" nls/all || die "native tools: nls/all failed"

  [ -x "$TOOLS/tools/winebuild/winebuild" ] \
    || die "native tools build produced no winebuild (looked in $TOOLS/tools/winebuild)"
  [ -e "$TOOLS/nls/locale.nls" ] \
    || die "native tools tree has no nls/locale.nls; wrc will not be able to run"
  ok "native tools ready"
fi

# --- Pass 2: the cross build ---------------------------------------------------

rm -rf "$BUILD" "$STAGE"
mkdir -p "$BUILD" "$STAGE"

HOST_TRIPLE="aarch64-linux-android$NDK_API"

# pkg-config isolation. Wine's configure resolves optional dependencies through
# pkg-config, and with the default search path that is the BUILD machine's
# /usr/lib/pkgconfig — it would find glibc-linked x86-64 libraries and cheerfully
# add them to an aarch64 link. PKG_CONFIG_LIBDIR *replaces* the default path
# rather than prepending to it, so pointing it at the Android sysroot alone is
# what makes the leak impossible. Mesa already cost us a day on exactly this
# (it found the host's SPIRV-Tools); see the note in build/turnip.sh.
#
# Both directories are listed because X.Org splits its .pc files by whether they
# describe compiled code: libX11 puts x11.pc in lib/pkgconfig, xorgproto puts
# xproto.pc in share/pkgconfig. PKG_CONFIG_LIBDIR has no fallback, so leaving
# share/ out simply makes half the stack invisible.
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
unset PKG_CONFIG_PATH

# The NDK ships no per-triple binutils, only llvm-*. configure's AC_CHECK_TOOL
# would fall back to the container's /usr/bin/ar and /usr/bin/strip, which are
# x86-64 GNU tools; llvm's handle aarch64 objects correctly and match the
# compiler that produced them.
export CC="$NDK_CC"
export CXX="$NDK_CXX"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export NM="$NDK_BIN/llvm-nm"
export OBJCOPY="$NDK_BIN/llvm-objcopy"
export READELF="$NDK_BIN/llvm-readelf"

# --exec-prefix is not redundant with --prefix. On a linux-android host Wine's
# configure rewrites an unset exec_prefix to ${prefix}/arm64-v8a, because
# upstream targets the Android NDK's multi-ABI layout. That would install to
# usr/arm64-v8a/bin and usr/arm64-v8a/lib, which is not the layout the package
# format or the app's container expects.
CONFIGURE_ARGS=(
  --prefix=/usr
  --exec-prefix=/usr
  --host="$HOST_TRIPLE"
  --with-wine-tools="$TOOLS"
  --enable-archs=arm64ec,aarch64,i386
  --with-mingw=clang
  --disable-tests
  --without-x
  --without-freetype

  # Everything below is a host library we do not ship and do not want configure
  # hunting for. Left on `auto` each one probes the build machine, and the ones
  # that "succeed" are the dangerous case — a found host .so becomes a NEEDED
  # entry in an aarch64 binary. Audio, capture and device access all belong to
  # the Android side of the app, not to Wine.
  --without-alsa
  --without-capi
  --without-coreaudio
  --without-cups
  --without-dbus
  --without-ffmpeg
  --without-gphoto
  --without-gssapi
  --without-gstreamer
  --without-krb5
  --without-netapi
  --without-opencl
  --without-oss
  --without-pcap
  --without-pcsclite
  --without-pulse
  --without-sane
  --without-sdl
  --without-udev
  --without-usb
  --without-v4l2
  --without-wayland

  # Wine defaults to -g -O2. The debug info multiplies the package size and is
  # of little use on a phone; -O2 alone is valid for every --enable-archs
  # target, which a chip-specific flag would not be.
  CFLAGS="-O2 -I$SYSROOT/usr/include"
  LDFLAGS="-L$SYSROOT/usr/lib"
  CROSSCFLAGS=-O2
)

# vulkan is deliberately NOT in the --without- list: the NDK sysroot has
# libvulkan.so, so it configures cleanly and is the whole point of shipping
# DXVK. inotify likewise — bionic has inotify natively.

log "configuring $COMPONENT for $HOST_TRIPLE (arm64ec + aarch64 + i386)"
( cd "$BUILD" && "$SRC/configure" "${CONFIGURE_ARGS[@]}" ) \
  || die "cross configure failed (see $BUILD/config.log)"

log "building $COMPONENT — expect an hour or more"
make -C "$BUILD" -j"$(build_jobs 1)" || die "wine build failed"

# install-lib is the runtime half: no headers, no import libraries, no man
# pages. None of it is useful on the device and it is most of the size.
log "installing into staging prefix"
make -C "$BUILD" install-lib DESTDIR="$STAGE" || die "make install-lib failed"

[ -d "$PAYLOAD" ] || die "make install-lib produced nothing under $PAYLOAD"

# --- Verification --------------------------------------------------------------
# Never package a partial Wine. Each check below has actually failed at some
# point during bring-up, and each one is invisible until the app tries to start
# a container on the device.

[ -x "$PAYLOAD/bin/wineserver" ] || die "no wineserver at $PAYLOAD/bin/wineserver"
[ -x "$PAYLOAD/bin/wine" ]       || die "no wine loader at $PAYLOAD/bin/wine"

# The unix side must be bionic aarch64. A host-glibc binary here is the exact
# failure this rewrite exists to prevent, and `file` is the only cheap way to
# tell the two apart.
for elf in bin/wineserver lib/wine/aarch64-unix/ntdll.so; do
  path="$PAYLOAD/$elf"
  [ -f "$path" ] || die "missing unix binary $elf (expected $path)"
  file "$path" | grep -q 'ARM aarch64' \
    || die "$elf is not an aarch64 ELF: $(file -b "$path")"
done

# Each --enable-archs target gets its own PE tree. A missing one means configure
# quietly dropped that architecture — usually because the toolchain could not
# target it — and the package would be broken in a way only noticed on device.
for arch in arm64ec aarch64 i386; do
  [ -d "$PAYLOAD/lib/wine/$arch-windows" ] \
    || die "no PE tree for $arch (expected $PAYLOAD/lib/wine/$arch-windows).
     --enable-archs did not build it; check the configure summary for the
     'Wine will be built with' lines."
done

# Shared with fex.sh, dxvk.sh and vkd3d.sh. The naive "is it aarch64" test
# rejects a correct ARM64EC DLL — see the verify_pe_dll comment in common.sh.
verify_pe_dll "$PAYLOAD/lib/wine/arm64ec-windows/ntdll.dll" \
  arm64ec-w64-mingw32 "arm64ec ntdll.dll"

write_provenance "$PAYLOAD/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Wine \
  --name "Wine $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$PAYLOAD" \
  --provenance "$PAYLOAD/provenance.json" \
  --description "Wine $VERSION with arm64ec/aarch64/i386 PE modules and a bionic aarch64 unix side, from $WINE_REF @ ${SOURCE_SHA:0:12}" \
  --out "$DIST_DIR/wine-$VERSION-$TARGET_NAME.wcp"

ok "dist/wine-$VERSION-$TARGET_NAME.wcp"
