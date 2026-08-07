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

# --- X11 and FreeType for Android ---------------------------------------------
# Cross-built once into a staging sysroot and reused; the script is a no-op when
# the sysroot already matches the pins. Run before the tools pass so a broken
# sysroot fails in seconds rather than after Wine's host tools have built.
"$COMMON_SH_DIR/x11-sysroot.sh" \
  || die "x11-sysroot.sh failed; Wine cannot be built with X11 support without it"

for pc in x11 xext freetype2; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] \
    || die "x11-sysroot.sh reported success but left no $pc.pc in $SYSROOT/usr/lib/pkgconfig"
done

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
#
# FreeType is the one exception, and it is NOT optional here. Wine generates its
# core bitmap fonts — System, Fixedsys, Terminal, the Courier/MS Sans codepage
# variants — at build time with tools/sfnt2fon, and that is a host tool reading
# the bundled TTFs, so it needs the HOST's FreeType. The aarch64 copy in the
# Android sysroot is invisible to it. Configured --without-freetype, sfnt2fon
# still builds and still installs; its entire body is
#     fprintf( stderr, "%s needs to be built with FreeType support\n", argv[0] );
# so the failure lands in the cross build's fonts/ rules, which run near the very
# end — an hour of work discarded for a missing host -dev package.
#
# sfnt2fon is probed rather than stamped because that is the property we actually
# depend on, and it also catches a tools tree left behind by an older revision of
# this script that did pass --without-freetype.
tools_sfnt2fon_has_freetype() {
  local exe="$TOOLS/tools/sfnt2fon/sfnt2fon" out
  [ -x "$exe" ] || return 1
  # No arguments: with FreeType it prints its usage, without it prints the stub
  # message above. Either way it exits non-zero, so match on the text and not on
  # the status — and capture rather than pipe, because under `set -o pipefail`
  # `! cmd | grep -q` reports the failing *producer*, not the match, and would
  # answer "yes, it has FreeType" for precisely the tree that does not.
  out="$("$exe" 2>&1 || true)"
  ! grep -q 'needs to be built with FreeType support' <<< "$out"
}

if [ -x "$TOOLS/tools/winebuild/winebuild" ] && [ -e "$TOOLS/nls/locale.nls" ] \
   && tools_sfnt2fon_has_freetype; then
  info "reusing native tools in $TOOLS"
else
  log "pass 1/2: native build tools (host gcc)"
  rm -rf "$TOOLS"
  mkdir -p "$TOOLS"
  ( cd "$TOOLS" && "$SRC/configure" \
      --enable-win64 \
      --disable-tests \
      --without-mingw \
      --without-x ) \
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
  tools_sfnt2fon_has_freetype \
    || die "the host tools tree built sfnt2fon without FreeType, so Wine's bitmap
     fonts cannot be generated and the cross build will fail in fonts/.
     The image needs a host FreeType: check that libfreetype-dev is installed and
     that 'pkg-config --exists freetype2' succeeds, then rebuild the image with
     'docker build -t vessel-build .'. Look for 'checking for -lfreetype' in
     $TOOLS/config.log."
  ok "native tools ready"
fi

# --- Pass 2: the cross build ---------------------------------------------------

# The staging tree is always rebuilt from scratch — it is cheap, and a leftover
# file from a previous run is exactly how a partial Wine gets packaged. The
# *build* tree is a different matter; see the configure stamp below.
rm -rf "$STAGE"
mkdir -p "$STAGE"

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

# And having isolated it, we have to hand it back to configure by name. Wine
# uses WINE_CHECK_HOST_TOOL for pkg-config, described in aclocal.m4 as
# "like AC_CHECK_TOOL but without the broken fallback to non-prefixed name":
# when cross-compiling it looks only for aarch64-linux-android35-pkg-config,
# finds nothing, and leaves PKG_CONFIG empty. Every pkg-config-based dependency
# then silently reports "not found" — which surfaced as
#     checking for ft2build.h... no
#     configure: error: FreeType development files not found.
# with a perfectly good freetype2.pc sitting in the sysroot.
#
# That refusal is right in general and wrong here: the danger it guards against
# is pkg-config answering with build-machine paths, and PKG_CONFIG_LIBDIR above
# has already made that impossible.
# Passed as a configure argument rather than exported so config.status records
# it; a Makefile regeneration would otherwise quietly drop it again.
HOST_PKG_CONFIG="$(command -v pkg-config || true)"
[ -n "$HOST_PKG_CONFIG" ] \
  || die "pkg-config is not on PATH; Wine cannot find the sysroot libraries without it"

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
# enable_wineandroid_drv=no is a configure *variable*, not an option, because
# Wine has no --disable-wineandroid.drv. configure.ac turns the driver on by
# default for any linux-android host:
#     linux-android*) enable_wineandroid_drv=${enable_wineandroid_drv:-yes}
# and autoconf eval's VAR=value command-line assignments before that runs, so
# this is the supported way to override it (and unlike an exported variable it
# is recorded in config.status, surviving a Makefile regeneration).
#
# Two reasons to switch it off. It is not our display path — Vessel talks to the
# X server the app embeds, through winex11.drv — and its APK rule shells out to
# gradle, which is not in the build image:
#     make: *** [dlls/wineandroid.drv/wine-debug.apk] Error 127
# It is also simply broken on this branch with a modern clang, in all three PE
# architectures:
#     dllmain.c:126:34: error: incompatible pointer to integer conversion
#     assigning to 'UINT64' from 'NTSTATUS (void *, ULONG) __stdcall'
# That is an upstream bug worth a patch only if the driver were wanted; it is not.
#
# Side effect worth knowing: with wineandroid, winemac and winewayland all off,
# configure promotes "X development files not found" from a notice back to a
# hard error whenever --with-x is requested. That is the behaviour we want.
CONFIGURE_ARGS=(
  --prefix=/usr
  --exec-prefix=/usr
  --host="$HOST_TRIPLE"
  --with-wine-tools="$TOOLS"
  enable_wineandroid_drv=no
  PKG_CONFIG="$HOST_PKG_CONFIG"
  --enable-archs=arm64ec,aarch64,i386
  --with-mingw=clang
  --disable-tests

  # --enable-tools looks redundant next to --with-wine-tools and is not.
  # configure.ac turns the tools off as a side effect of being handed a prebuilt
  # tools tree:
  #     elif test -d "$toolsdir/tools/winebuild"; then
  #         enable_tools=${enable_tools:-no}
  # and one of the things that switches off is tools/wine — which since Wine 10
  # is the program that becomes bin/wine. It is not the same binary as
  # loader/wine (that one installs to lib/wine/aarch64-unix/wine); it is the
  # small front end that resolves libdir, dlopens ntdll.so and calls
  # __wine_main. Without it the install still creates bin/notepad, bin/winecfg
  # and the rest as symlinks to "wine", and every one of them dangles:
  #     error: no wine loader at .../bin/wine
  # Nothing in the container can start a process at that point.
  #
  # Turning the tools back on does NOT redirect code generation: makedep resolves
  # winebuild/wrc/widl through toolsdir regardless, so those still run as x86-64
  # host binaries. All this adds is a second, cross-compiled copy of tools/ for
  # the device, of which install-lib ships exactly one file — tools/wine is the
  # only tools subdirectory with an INSTALL_LIB.
  --enable-tools
  # AC_PATH_XTRA does not consult pkg-config, so the sysroot has to be named
  # again here even though PKG_CONFIG_SYSROOT_DIR already points at it.
  --with-x
  --x-includes="$SYSROOT/usr/include"
  --x-libraries="$SYSROOT/usr/lib"

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

# Reconfiguring means recompiling, and recompiling Wine is an hour. So the build
# tree survives between runs unless something that would actually change its
# contents has changed: the stamp is the whole argument vector plus the upstream
# commit. Anything else and the tree is wiped, because a Makefile regenerated
# under different options is the sort of half-state that produces a build which
# links but does not run.
#
# Patches are deliberately NOT in the stamp. apply_patches works on the source
# tree without moving HEAD, so adding one leaves the configured tree valid and
# make rebuilds exactly the objects the patch touched — which is what makes
# bring-up iteration possible at all. Set VESSEL_WINE_CLEAN=1 to force a wipe.
CONF_STAMP="$BUILD/.vessel-configure"
CONF_ID="$(printf '%s\n' "${CONFIGURE_ARGS[@]}" "$SOURCE_SHA" | sha256sum | cut -d' ' -f1)"

if [ -z "${VESSEL_WINE_CLEAN:-}" ] && [ -f "$BUILD/config.status" ] \
   && [ -f "$CONF_STAMP" ] && [ "$(cat "$CONF_STAMP")" = "$CONF_ID" ]; then
  info "reusing the configured cross tree in $BUILD (same configure args, same source)"
else
  log "configuring $COMPONENT for $HOST_TRIPLE (arm64ec + aarch64 + i386)"
  rm -rf "$BUILD"
  mkdir -p "$BUILD"
  ( cd "$BUILD" && "$SRC/configure" "${CONFIGURE_ARGS[@]}" ) \
    || die "cross configure failed (see $BUILD/config.log)"
  echo "$CONF_ID" > "$CONF_STAMP"
fi

# Fail now, not in an hour. configure records every module it decided to skip in
# DISABLED_SUBDIRS, and a winex11.drv in that list means the X11 sysroot was
# found but rejected for some other reason — a missing extension header, say.
# --with-x already turns a *missing* X11 into a configure error; this catches
# the quieter case.
#
# Captured rather than piped into `grep -q` for the pipefail reason spelled out
# above verify_pe_dll in common.sh: DISABLED_SUBDIRS is one very long line, so a
# matching `grep -q` would SIGPIPE the producer and the check would never fire —
# it would stay quiet on exactly the failure it exists to catch.
DISABLED_LINE="$(grep -E '^DISABLED_SUBDIRS' "$BUILD/Makefile" || true)"
if grep -q 'dlls/winex11\.drv' <<< "$DISABLED_LINE"; then
  die "configure disabled dlls/winex11.drv despite --with-x.
     Search $BUILD/config.log for 'checking for X' and for the X11 extension
     header checks that follow it."
fi

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
# tell the two apart — look for "interpreter /system/bin/linker64" on the
# executables, which no glibc build can produce.
# Note the unix driver is winex11.so, NOT winex11.drv.so. winex11.drv is the
# PE module in lib/wine/aarch64-windows; its unix half is named by UNIXLIB in
# dlls/winex11.drv/Makefile.in, which reads "winex11.so". Checking for the .drv.so
# spelling passes over a genuinely missing driver every time.
for elf in bin/wineserver bin/wine lib/wine/aarch64-unix/ntdll.so \
           lib/wine/aarch64-unix/win32u.so lib/wine/aarch64-unix/winex11.so; do
  path="$PAYLOAD/$elf"
  [ -f "$path" ] || die "missing unix binary $elf (expected $path).
     Without winex11.drv.so in particular, Wine has no way to put a window on
     screen; check the 'checking for X' lines in $BUILD/config.log."
  file "$path" | grep -q 'ARM aarch64' \
    || die "$elf is not an aarch64 ELF: $(file -b "$path")"
done

# THE PE TREES ARE NOT ONE PER --enable-archs VALUE, and expecting that is the
# obvious mistake. Wine builds arm64ec as ARM64X: the arm64ec objects are linked
# *into* the aarch64 DLLs, producing hybrid images that carry both instruction
# sets, and they install to lib/wine/aarch64-windows. There is no
# lib/wine/arm64ec-windows directory and there never will be — nor an
# x86_64-windows one, even though arm64ec implies x86_64 as an extra arch, since
# that side exists only to generate the x64 thunks folded into the same image.
#
# Confirmed against the generated Makefile: the only install destinations are
# $(libdir)/wine/{aarch64-windows,i386-windows,aarch64-unix}.
for tree in aarch64-windows i386-windows aarch64-unix; do
  [ -d "$PAYLOAD/lib/wine/$tree" ] \
    || die "no $tree tree (expected $PAYLOAD/lib/wine/$tree).
     --enable-archs did not build it; check the 'Wine will be built with' lines
     in the configure summary."
done

# So this is where ARM64EC is actually proved. verify_pe_dll's arm64ec branch
# ignores the machine type — an EC image legitimately reports AMD64 — and looks
# for the hybrid (CHPE) load-config directory instead. If the arm64ec half had
# silently fallen out, this file would be a plain ARM64 PE and fail here, which
# is the one failure that would otherwise survive all the way to the device.
verify_pe_dll "$PAYLOAD/lib/wine/aarch64-windows/ntdll.dll" \
  arm64ec-w64-mingw32 "aarch64-windows/ntdll.dll (ARM64X)"

# i386 has no such subtlety; check it is really 32-bit x86 and not a copy of the
# ARM64 build under a different name.
verify_pe_dll "$PAYLOAD/lib/wine/i386-windows/ntdll.dll" \
  i686-w64-mingw32 "i386 ntdll.dll"

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
