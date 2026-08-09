#!/usr/bin/env bash
# Build ARM64EC-capable Wine for Android and package it as a .wcp.
#
#   ./build/wine.sh             # -> dist/wine-<ver>-<target>.wcp
#
# THE LONGEST BUILD IN THE PROJECT — an hour or more — and the most likely to
# need patches, since WINE_REF is a development branch carrying the ARM64EC work
# ahead of mainline. Fixes go in patches/wine/. Three toolchains plus a
# cross-built X11 sysroot; why each is necessary is in docs/ARCHITECTURE.md,
# "Wine is three toolchains, not one".

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk
setup_mingw

COMPONENT=wine
COMPONENT_REF="$WINE_REF"

fetch_source "$COMPONENT" "$WINE_REPO" "$WINE_REF"

SRC="$NATIVE_DIR/$COMPONENT"

# WINE_REF is a branch, so the ref carries no version; the tree's VERSION file
# holds it as "Wine version 10.12".
VERSION="$(sed -n 's/^Wine version[[:space:]]*//p' "$SRC/VERSION" 2>/dev/null | head -1 || true)"
[ -n "$VERSION" ] || die "could not read a version out of $SRC/VERSION"
info "wine version $VERSION (branch $WINE_REF @ ${SOURCE_SHA:0:12})"

BUILD="$WORK_DIR/wine-android"
TOOLS="$WORK_DIR/wine-tools"
STAGE="$WORK_DIR/stage-$COMPONENT"
SYSROOT="$WORK_DIR/androidsysroot"
# --prefix=/usr + DESTDIR=$STAGE puts the tree at $STAGE/usr, which is the payload.
PAYLOAD="$STAGE/usr"

# Deliberately no resolve_cpu_flags: CFLAGS reaches only the unix side and
# CROSSCFLAGS all three PE architectures at once, so -mcpu=oryon-1 has nowhere
# safe to live — it is meaningless to the i386 pass.

if [ ! -x "$SRC/configure" ]; then
  info "no configure script in the tree; running autoreconf"
  ( cd "$SRC" && autoreconf -f -i ) || die "autoreconf failed; install autoconf/automake"
fi

# --- X11 and FreeType for Android ---------------------------------------------
# Before the tools pass, so a broken sysroot fails in seconds rather than after
# Wine's host tools have built. No-op when the sysroot matches the pins.
"$COMMON_SH_DIR/x11-sysroot.sh" \
  || die "x11-sysroot.sh failed; Wine cannot be built with X11 support without it"

for pc in x11 xext freetype2; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] \
    || die "x11-sysroot.sh reported success but left no $pc.pc in $SYSROOT/usr/lib/pkgconfig"
done

# --- Pass 1: native build tools -----------------------------------------------
# __tooldeps__ builds only the code generators, so this pass costs minutes, and
# everything optional is off. FreeType is the exception and is NOT optional:
# Wine generates its bitmap fonts with tools/sfnt2fon, a HOST tool, so the
# aarch64 copy in the Android sysroot is invisible to it. Built
# --without-freetype, sfnt2fon still installs but is a stub, and the failure
# lands in the cross build's fonts/ rules near the very end — an hour discarded
# for a missing host -dev package.
tools_sfnt2fon_has_freetype() {
  local exe="$TOOLS/tools/sfnt2fon/sfnt2fon" out
  [ -x "$exe" ] || return 1
  # Match on the text, not the exit status — it exits non-zero either way. And
  # capture rather than pipe: under `set -o pipefail`, `! cmd | grep -q` reports
  # the failing *producer*, so it would answer "yes, it has FreeType" for
  # precisely the tree that does not.
  out="$("$exe" 2>&1 || true)"
  ! grep -q 'needs to be built with FreeType support' <<< "$out"
}

# The tools tree is only reusable if it was built from *this* Wine. It lives in
# the persistent /work volume, so without a stamp a changed pin silently keeps
# the previous version's binaries — and they are used to generate the makefiles
# for the new source. Moving 10.13 -> 11.14 failed exactly there, well past
# configure and with nothing pointing at the cause:
#
#   Unknown option '-i./conf3bXwBS/makefile'
#   config.status: error: could not create Makefile
#
# 11.14's configure passes makedep an option that 10.13's makedep never had.
TOOLS_STAMP="$TOOLS/.vessel-source-sha"

if [ -x "$TOOLS/tools/winebuild/winebuild" ] && [ -e "$TOOLS/nls/locale.nls" ] \
   && [ "$(cat "$TOOLS_STAMP" 2>/dev/null)" = "$SOURCE_SHA" ] \
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

  # __tooldeps__ builds the binaries but not the NLS tables, which wrc/wmc load
  # at runtime relative to their own executable — inside the TOOLS tree, never
  # the cross tree. Without this the cross build dies on the first resource with
  # "Error: unable to load locale.nls". nls/all just symlinks them in.
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
  # Stamped last, so an interrupted build is not mistaken for a finished one.
  printf '%s' "$SOURCE_SHA" > "$TOOLS_STAMP"
  ok "native tools ready"
fi

# --- Pass 2: the cross build ---------------------------------------------------

# The staging tree is always rebuilt — a leftover file from a previous run is
# how a partial Wine gets packaged. The *build* tree is not; see the stamp below.
rm -rf "$STAGE"
mkdir -p "$STAGE"

HOST_TRIPLE="aarch64-linux-android$NDK_API"

# PKG_CONFIG_LIBDIR *replaces* the default search path rather than prepending,
# which is what stops configure finding the build machine's glibc x86-64
# libraries and adding them to an aarch64 link. (Mesa cost us a day on exactly
# this.) Both directories are listed because X.Org splits .pc files by whether
# they describe compiled code — x11.pc in lib/pkgconfig, xproto.pc in
# share/pkgconfig — and PKG_CONFIG_LIBDIR has no fallback.
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
unset PKG_CONFIG_PATH

# Having isolated pkg-config, hand it back to configure by name: Wine's
# WINE_CHECK_HOST_TOOL looks only for aarch64-linux-android35-pkg-config when
# cross-compiling and leaves PKG_CONFIG empty, so every dependency reports "not
# found" with a good .pc file sitting in the sysroot. A configure argument
# rather than an export, so config.status records it across regeneration.
HOST_PKG_CONFIG="$(command -v pkg-config || true)"
[ -n "$HOST_PKG_CONFIG" ] \
  || die "pkg-config is not on PATH; Wine cannot find the sysroot libraries without it"

# The NDK ships no per-triple binutils, only llvm-*, so AC_CHECK_TOOL would fall
# back to the container's x86-64 GNU ar/strip.
export CC="$NDK_CC"
export CXX="$NDK_CXX"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export NM="$NDK_BIN/llvm-nm"
export OBJCOPY="$NDK_BIN/llvm-objcopy"
export READELF="$NDK_BIN/llvm-readelf"

# --exec-prefix is not redundant with --prefix: on a linux-android host Wine
# rewrites an unset exec_prefix to ${prefix}/arm64-v8a (the NDK multi-ABI
# layout), which is not the layout the package format expects.
#
# enable_wineandroid_drv=no is a configure *variable*, not an option — there is
# no --disable-wineandroid.drv, and configure.ac defaults it on for any
# linux-android host. Off because it is not our display path, its APK rule
# shells out to gradle (absent from the build image), and it does not compile on
# this branch with a modern clang. Side effect: with wineandroid, winemac and
# winewayland all off, configure promotes "X development files not found" from a
# notice to a hard error under --with-x, which is what we want.
#
# GRADLE is not honesty about having gradle; it is the only way to get past a
# check that turning the driver off does not skip. Wine 11 added
#
#   AC_PATH_PROG([GRADLE], [gradle])
#   test -n "$GRADLE" || AC_MSG_ERROR([gradle is required for the Android build])
#
# to configure.ac's linux-android* case, unconditionally — before and regardless
# of enable_wineandroid_drv, so a build that never wants an APK still stops at
#   checking for gradle... configure: error: gradle is required for the Android build
# AC_PATH_PROG lets a preset value through untested ("Let the user override the
# test with a path"), and the only rule that expands $(GRADLE) is the
# wineandroid.drv APK target, which is disabled — so nothing ever runs it. Wine
# 10.13 had no such check; this appeared with the 11.x pin.
CONFIGURE_ARGS=(
  --prefix=/usr
  --exec-prefix=/usr
  --host="$HOST_TRIPLE"
  --with-wine-tools="$TOOLS"
  enable_wineandroid_drv=no
  GRADLE=/bin/true
  PKG_CONFIG="$HOST_PKG_CONFIG"
  --enable-archs=arm64ec,aarch64,i386
  --with-mingw=clang
  --disable-tests

  # --enable-tools looks redundant next to --with-wine-tools and is not.
  # configure.ac turns the tools off as a side effect of being handed a prebuilt
  # tools tree, and that switches off tools/wine — which since Wine 10 is the
  # program that becomes bin/wine (not loader/wine, which installs to
  # lib/wine/aarch64-unix/wine). Without it bin/notepad, bin/winecfg and the rest
  # are symlinks to a "wine" that does not exist. It does NOT redirect code
  # generation; makedep resolves winebuild/wrc/widl through toolsdir regardless.
  --enable-tools
  # AC_PATH_XTRA does not consult pkg-config, so the sysroot has to be named
  # again here even though PKG_CONFIG_SYSROOT_DIR already points at it.
  --with-x
  --x-includes="$SYSROOT/usr/include"
  --x-libraries="$SYSROOT/usr/lib"

  # Host libraries we do not ship. On `auto` each probes the build machine, and
  # the ones that "succeed" are the danger: a found host .so becomes a NEEDED
  # entry in an aarch64 binary.
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
  # OSS itself stays off — Android has no /dev/dsp and the probe must not
  # find the build machine's headers — but wineoss.drv is force-enabled
  # anyway: patches/wine/0008 rebuilds its unix half on AAudio (the NDK's
  # libaaudio.so), which is the one audio API an Android process has.
  # WINE_NOTICE_WITH only defaults enable_wineoss_drv, so an explicit yes
  # here survives the failed OSS probe. Without a driver, mmdevapi logs
  # "No driver from pulse,alsa,oss,coreaudio could be initialized" and
  # every program runs mute; mmdevapi probes "oss" by default, so keeping
  # the wineoss.drv name means nothing else needs to learn a new one.
  enable_wineoss_drv=yes
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

  # No -g: the debug info multiplies package size for little use on a phone.
  # -O2 alone is valid for every --enable-archs target; a chip flag would not be.
  CFLAGS="-O2 -I$SYSROOT/usr/include"
  LDFLAGS="-L$SYSROOT/usr/lib"
  CROSSCFLAGS=-O2
)

# vulkan and inotify are deliberately NOT in the --without- list: the NDK
# sysroot has libvulkan.so, and bionic has inotify natively.

# Recompiling Wine is an hour, so the build tree survives between runs unless
# the configure arguments or the upstream commit changed. Patches are
# deliberately NOT in the stamp: apply_patches works on the source tree without
# moving HEAD, so adding one leaves the configured tree valid and make rebuilds
# only what the patch touched. VESSEL_WINE_CLEAN=1 forces a wipe.
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

# Fail now, not in an hour. --with-x catches a *missing* X11; a winex11.drv in
# DISABLED_SUBDIRS is the quieter case where it was found and then rejected, for
# a missing extension header say.
#
# Captured rather than piped into `grep -q` for the pipefail reason spelled out
# above verify_pe_dll in common.sh: DISABLED_SUBDIRS is one very long line, so a
# matching `grep -q` would SIGPIPE the producer and the check would never fire.
DISABLED_LINE="$(grep -E '^DISABLED_SUBDIRS' "$BUILD/Makefile" || true)"
if grep -q 'dlls/winex11\.drv' <<< "$DISABLED_LINE"; then
  die "configure disabled dlls/winex11.drv despite --with-x.
     Search $BUILD/config.log for 'checking for X' and for the X11 extension
     header checks that follow it."
fi

log "building $COMPONENT — expect an hour or more"
make -C "$BUILD" -j"$(build_jobs 1)" || die "wine build failed"

# install-lib drops headers, import libraries and man pages — most of the size,
# none of it useful on the device.
log "installing into staging prefix"
make -C "$BUILD" install-lib DESTDIR="$STAGE" || die "make install-lib failed"

[ -d "$PAYLOAD" ] || die "make install-lib produced nothing under $PAYLOAD"

# --- Ship the X11/FreeType runtime ----------------------------------------------
# `make install-lib` installs Wine and nothing else, so without this the package
# holds a winex11.so whose NEEDED entries name libX11.so and libXext.so and a
# device that has neither. Wine then has no way to open a window at all, and
# win32u's dlopen of libfreetype.so fails too, which is the
#
#   Wine cannot find the FreeType font library.
#
# banner: compiled *with* FreeType, unable to load it, so no TrueType text
# anywhere. Both were invisible until a prefix built on the phone, because
# wineboot gets a long way without a display or a glyph.
#
# Everything is copied, not just the two NEEDED names: winex11 dlopens Xrender,
# Xcursor, Xfixes, Xi, Xrandr, Xinerama, Xcomposite and Xxf86vm by soname at
# runtime, and configure already decided they exist. The whole set is a few MB.
# Sonames here are unversioned (libX11.so, not libX11.so.6), so a plain copy is
# enough; the launcher puts <wine>/lib on LD_LIBRARY_PATH.
log "adding the X11/FreeType runtime"
shopt -s nullglob
runtime_libs=("$SYSROOT/usr/lib"/*.so)
shopt -u nullglob
[ "${#runtime_libs[@]}" -gt 0 ] \
  || die "no shared libraries in $SYSROOT/usr/lib — run ./build/x11-sysroot.sh first"
install -d "$PAYLOAD/lib"
for lib in "${runtime_libs[@]}"; do
  install -m 0644 "$lib" "$PAYLOAD/lib/"
  "$NDK_BIN/llvm-strip" --strip-unneeded "$PAYLOAD/lib/$(basename "$lib")" 2>/dev/null || true
done
ok "$(( ${#runtime_libs[@]} )) runtime libraries"

# The check that would have caught the above. Every NEEDED entry of every ELF we
# ship must resolve inside the package or be one Android already provides —
# anything else is a library that exists only on the build machine.
BIONIC_PROVIDED="libc.so libm.so libdl.so libz.so liblog.so libandroid.so
libEGL.so libGLESv2.so libGLESv3.so libvulkan.so libnativewindow.so
libsync.so libcamera2ndk.so libaaudio.so ld-android.so"
missing=""
for elf in "$PAYLOAD"/bin/* "$PAYLOAD"/lib/*.so "$PAYLOAD"/lib/wine/aarch64-unix/*.so; do
  [ -f "$elf" ] || continue
  # llvm-readelf output is captured, never piped into grep: grep exits at its
  # first match, readelf takes SIGPIPE, and pipefail turns that into a failure.
  dyn="$("$NDK_BIN/llvm-readelf" -d "$elf" 2>/dev/null || true)"
  for need in $(grep NEEDED <<<"$dyn" | sed 's/.*\[//;s/\]//'); do
    case " $BIONIC_PROVIDED " in *" $need "*) continue ;; esac
    [ -f "$PAYLOAD/lib/$need" ] && continue
    [ -f "$PAYLOAD/lib/wine/aarch64-unix/$need" ] && continue
    missing="$missing
     $(basename "$elf") needs $need"
  done
done
[ -z "$missing" ] || die "the package depends on libraries it does not ship:$missing
     Add them to the X11 sysroot (build/x11-sysroot.sh) or to BIONIC_PROVIDED if
     Android really does supply them."
ok "every NEEDED entry resolves inside the package"

# --- Verification --------------------------------------------------------------
# Each check below has failed during bring-up, and each is otherwise invisible
# until the app tries to start a container on the device.

[ -x "$PAYLOAD/bin/wineserver" ] || die "no wineserver at $PAYLOAD/bin/wineserver"
[ -x "$PAYLOAD/bin/wine" ]       || die "no wine loader at $PAYLOAD/bin/wine"

# The unix side must be bionic aarch64, not host glibc.
#
# Note the unix driver is winex11.so, NOT winex11.drv.so — winex11.drv is the PE
# module in lib/wine/aarch64-windows, and its unix half is named by UNIXLIB in
# dlls/winex11.drv/Makefile.in. Checking for the .drv.so spelling passes over a
# genuinely missing driver every time.
for elf in bin/wineserver bin/wine lib/wine/aarch64-unix/ntdll.so \
           lib/wine/aarch64-unix/win32u.so lib/wine/aarch64-unix/winex11.so \
           lib/wine/aarch64-unix/wineoss.so; do
  path="$PAYLOAD/$elf"
  [ -f "$path" ] || die "missing unix binary $elf (expected $path).
     Without winex11.drv.so in particular, Wine has no way to put a window on
     screen; check the 'checking for X' lines in $BUILD/config.log."
  file "$path" | grep -q 'ARM aarch64' \
    || die "$elf is not an aarch64 ELF: $(file -b "$path")"
done

# THE PE TREES ARE NOT ONE PER --enable-archs VALUE. Wine builds arm64ec as
# ARM64X — the arm64ec objects link *into* the aarch64 DLLs, installed to
# lib/wine/aarch64-windows. There is no arm64ec-windows directory, and no
# x86_64-windows one either, even though arm64ec implies x86_64 as an extra arch.
for tree in aarch64-windows i386-windows aarch64-unix; do
  [ -d "$PAYLOAD/lib/wine/$tree" ] \
    || die "no $tree tree (expected $PAYLOAD/lib/wine/$tree).
     --enable-archs did not build it; check the 'Wine will be built with' lines
     in the configure summary."
done

# So this is where ARM64EC is actually proved. verify_pe_dll's arm64ec branch
# ignores the machine type — an EC image legitimately reports AMD64 — and looks
# for the hybrid (CHPE) load-config directory instead.
verify_pe_dll "$PAYLOAD/lib/wine/aarch64-windows/ntdll.dll" \
  arm64ec-w64-mingw32 "aarch64-windows/ntdll.dll (ARM64X)"

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
