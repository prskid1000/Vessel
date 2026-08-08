#!/usr/bin/env bash
# Cross-build a minimal X11 client stack (plus FreeType) for Android into a
# staging sysroot at $WORK_DIR/androidsysroot.
#
#   ./build/x11-sysroot.sh      # -> $WORK_DIR/androidsysroot/usr/{include,lib}
#
# Wine's winex11.drv.so links -lX11 -lXext -lXrender -lXi and friends. The NDK
# ships no X11 at all, and the container's /usr/lib X11 is x86-64 glibc — worse
# than nothing, because configure finds it and records host libraries in an
# aarch64 binary.
#
# --prefix=/usr with DESTDIR=$SYSROOT (rather than --prefix=$SYSROOT/usr) is
# deliberate: the .pc files then carry a clean /usr prefix and
# PKG_CONFIG_SYSROOT_DIR rewrites them at use time. Both halves are required —
# with PKG_CONFIG_LIBDIR alone, pkgconf decides -I/usr/include is a system path
# and DROPS it, and the build compiles against the container's X11 headers.
#
# Idempotent: a stamp records the pin set, so bumping any X11_* pin in
# native/pins.env rebuilds and nothing else does.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk

SYSROOT="$WORK_DIR/androidsysroot"
SRC_CACHE="$WORK_DIR/x11-src"
BUILD_ROOT="$WORK_DIR/x11-build"
STAMP="$SYSROOT/.vessel-x11-sysroot"

XORG_BASE=https://www.x.org/releases/individual
# SourceForge, not FreeType's own savannah host, which returned 502 for hours on
# 2026-08-07. It is an official mirror with identical tarballs.
FREETYPE_BASE=https://downloads.sourceforge.net/project/freetype/freetype2

# Build order is dependency order: every entry needs the .pc files installed by
# the ones above it.  <url>|<name>-<version>|<extra configure args>
PACKAGES=(
  # util-macros is build-time only. Without its xorg-macros.pc every other
  # package prints "Package 'xorg-macros' ... not found" and carries on — noise
  # that hides the failures that do matter.
  "$XORG_BASE/util/util-macros-$X11_UTIL_MACROS.tar.xz|util-macros-$X11_UTIL_MACROS|"
  "$XORG_BASE/proto/xorgproto-$X11_XORGPROTO.tar.xz|xorgproto-$X11_XORGPROTO|"
  "$XORG_BASE/lib/xtrans-$X11_XTRANS.tar.xz|xtrans-$X11_XTRANS|"
  "$XORG_BASE/lib/libXau-$X11_LIBXAU.tar.xz|libXau-$X11_LIBXAU|"
  "$XORG_BASE/xcb/xcb-proto-$X11_XCBPROTO.tar.xz|xcb-proto-$X11_XCBPROTO|"
  "$XORG_BASE/xcb/libpthread-stubs-$X11_PTHREAD_STUBS.tar.xz|libpthread-stubs-$X11_PTHREAD_STUBS|"
  "$XORG_BASE/xcb/libxcb-$X11_LIBXCB.tar.xz|libxcb-$X11_LIBXCB|"
  # Not Wine's dependency — Mesa's. wsi_x11 keeps one shared-memory fence per
  # DRI3 buffer, so Mesa's `-Dplatforms=x11` will not configure without
  # xshmfence.pc. --with-shared-memory-dir is pinned rather than autodetected:
  # left to itself, configure probes the *container's* directories and bakes
  # whichever it finds into SHMDIR. Neither /dev/shm nor /run/shm exists on
  # Android, but the value is dead code there — xshmfence_alloc() prefers
  # memfd_create(), which bionic has had since API 30 and we compile against 35.
  # Pinning it keeps the built library identical whatever the container has.
  "$XORG_BASE/lib/libxshmfence-$X11_LIBXSHMFENCE.tar.xz|libxshmfence-$X11_LIBXSHMFENCE|--with-shared-memory-dir=/dev/shm"
  "$XORG_BASE/lib/libX11-$X11_LIBX11.tar.xz|libX11-$X11_LIBX11|--disable-specs --without-xmlto --without-fop --without-xsltproc"
  "$XORG_BASE/lib/libXext-$X11_LIBXEXT.tar.xz|libXext-$X11_LIBXEXT|--disable-specs"
  "$XORG_BASE/lib/libXrender-$X11_LIBXRENDER.tar.xz|libXrender-$X11_LIBXRENDER|"
  "$XORG_BASE/lib/libXfixes-$X11_LIBXFIXES.tar.xz|libXfixes-$X11_LIBXFIXES|"
  "$XORG_BASE/lib/libXi-$X11_LIBXI.tar.xz|libXi-$X11_LIBXI|--disable-specs"
  "$XORG_BASE/lib/libXrandr-$X11_LIBXRANDR.tar.xz|libXrandr-$X11_LIBXRANDR|"
  "$XORG_BASE/lib/libXcursor-$X11_LIBXCURSOR.tar.xz|libXcursor-$X11_LIBXCURSOR|"
  "$XORG_BASE/lib/libXcomposite-$X11_LIBXCOMPOSITE.tar.xz|libXcomposite-$X11_LIBXCOMPOSITE|"
  "$XORG_BASE/lib/libXxf86vm-$X11_LIBXXF86VM.tar.xz|libXxf86vm-$X11_LIBXXF86VM|"
  "$XORG_BASE/lib/libXinerama-$X11_LIBXINERAMA.tar.xz|libXinerama-$X11_LIBXINERAMA|"
  # Not X11, but it shares this sysroot and cross harness, and Wine's configure
  # treats a missing FreeType as a hard error. Building it here is what keeps
  # --without-freetype — a Wine with no glyph rasterizer — off the table.
  "$FREETYPE_BASE/$FREETYPE_VERSION/freetype-$FREETYPE_VERSION.tar.xz|freetype-$FREETYPE_VERSION|--without-harfbuzz --without-brotli --without-bzip2 --without-png --with-zlib=yes"
)

# The stamp is the whole pin set, so changing any one component invalidates it.
# A partial sysroot is worse than none: the missing library only surfaces at
# Wine's link step, an hour later.
PIN_ID="$(printf '%s\n' "${PACKAGES[@]}" | sha256sum | cut -d' ' -f1)"

if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$PIN_ID" ]; then
  info "android X11 sysroot already built at $SYSROOT"
  exit 0
fi

log "building the Android X11 sysroot at $SYSROOT"
info "this runs once; later builds reuse it until a X11_* pin changes"

rm -rf "$SYSROOT" "$BUILD_ROOT"
mkdir -p "$SYSROOT/usr/lib/pkgconfig" "$SYSROOT/usr/include" "$SRC_CACHE" "$BUILD_ROOT"

HOST_TRIPLE="aarch64-linux-android$NDK_API"

# The NDK ships no per-triple binutils, only llvm-*, so AC_CHECK_TOOL would fall
# through to the container's x86-64 GNU ar/strip — and naming them explicitly
# keeps libtool on the same toolchain as the compiler.
export CC="$NDK_CC"
export CXX="$NDK_CXX"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export NM="$NDK_BIN/llvm-nm"
export READELF="$NDK_BIN/llvm-readelf"
export OBJDUMP="$NDK_BIN/llvm-objdump"

# X.Org splits .pc files across two directories — compiled code in
# lib/pkgconfig, header- or data-only in share/pkgconfig. xproto.pc is in the
# latter, so a single-directory PKG_CONFIG_LIBDIR fails libXau's very first
# dependency check even though xorgproto installed correctly a step earlier.
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
unset PKG_CONFIG_PATH

export CPPFLAGS="-I$SYSROOT/usr/include"
export CFLAGS="-O2"
export LDFLAGS="-L$SYSROOT/usr/lib"

JOBS="$(build_jobs 1)"

# bionic folds pthread, rt and dl into libc and ships no separate libraries.
# Autotools packages hardcode the link flag from host_os alone — libX11 sets
# XTHREADLIB=-lpthread for anything matching linux*, which linux-android does,
# and the link dies with "unable to find library -lpthread". Empty static
# archives satisfy the reference and contribute nothing to the output.
for stub in pthread rt; do
  "$AR" rcs "$SYSROOT/usr/lib/lib$stub.a" \
    || die "could not create the lib$stub.a stub with $AR"
done

# <values.h> is a glibc-only compatibility header from 4.3BSD and bionic has
# never shipped it. libxshmfence's futex backend includes it for one identifier,
# MAXINT, in one call:
#
#   src/xshmfence_futex.h:51:10: fatal error: 'values.h' file not found
#   src/xshmfence_futex.h:66:  sys_futex(addr, FUTEX_WAKE, MAXINT, ...)
#
# The alternative is --disable-futex, which builds the pthread backend instead —
# but that changes what a fence *is* in shared memory, from a 32-bit futex word
# to a process-shared pthread mutex and condvar. Anything on the other end of
# the fd (an X server implementing DRI3 FenceFromFD) has to agree on that
# layout, and a futex word is the only layout anyone implements. So the futex
# backend is kept and the missing header is supplied.
#
# glibc's values.h is nothing but these aliases over <limits.h>; MAXINT is the
# only one anything here uses, and the others cost nothing and stop the next
# package that reaches for the header from failing the same way.
cat > "$SYSROOT/usr/include/values.h" <<'EOF'
/* Written by build/x11-sysroot.sh. bionic has no <values.h>; this is the
 * legacy BSD spelling of <limits.h>, which libxshmfence's futex backend
 * includes for MAXINT. Not installed on the device — build-time only. */
#ifndef _VESSEL_VALUES_H
#define _VESSEL_VALUES_H
#include <limits.h>
#include <float.h>
#define BITSPERBYTE CHAR_BIT
#define CHARBITS    CHAR_BIT
#define SHORTBITS   (sizeof(short) * CHAR_BIT)
#define INTBITS     (sizeof(int) * CHAR_BIT)
#define LONGBITS    (sizeof(long) * CHAR_BIT)
#define PTRBITS     (sizeof(void *) * CHAR_BIT)
#define MAXSHORT    SHRT_MAX
#define MAXINT      INT_MAX
#define MAXLONG     LONG_MAX
#define MINSHORT    SHRT_MIN
#define MININT      INT_MIN
#define MINLONG     LONG_MIN
#define MAXDOUBLE   DBL_MAX
#define MAXFLOAT    FLT_MAX
#define MINDOUBLE   DBL_MIN
#define MINFLOAT    FLT_MIN
#define DMINEXP     DBL_MIN_EXP
#define FMINEXP     FLT_MIN_EXP
#define DMAXEXP     DBL_MAX_EXP
#define FMAXEXP     FLT_MAX_EXP
#endif
EOF

for entry in "${PACKAGES[@]}"; do
  IFS='|' read -r url dirname extra <<< "$entry"
  tarball="$SRC_CACHE/$(basename "$url")"

  if [ ! -f "$tarball" ]; then
    info "fetching $(basename "$url")"
    curl -fSL --retry 3 -o "$tarball.part" "$url" \
      || die "download failed: $url
     Check the version pin for $dirname in native/pins.env — upstream removes
     nothing, so a 404 here means the version string is wrong."
    mv "$tarball.part" "$tarball"
  fi

  src="$BUILD_ROOT/$dirname"
  rm -rf "$src"
  tar -xJf "$tarball" -C "$BUILD_ROOT" || die "could not unpack $tarball"
  [ -x "$src/configure" ] || die "$dirname ships no configure script.
     Upstream moved this release to meson-only; pin the last autotools version
     in native/pins.env, or port this script to meson for that package."

  log "x11-sysroot: $dirname"

  # --disable-malloc0returnsnull: X.Org decides this with a *run* test a cross
  # build cannot execute, and left to guess it makes every Xlib allocation take
  # a compatibility path. bionic's malloc(0) returns a valid pointer like
  # glibc's, so the answer is a plain no.
  #
  # Unknown --enable/--with options are only a warning in autoconf, which is why
  # one shared flag set covers packages with different option sets.
  # shellcheck disable=SC2086
  ( cd "$src" && ./configure \
      --host="$HOST_TRIPLE" \
      --prefix=/usr \
      --disable-static \
      --enable-shared \
      --disable-malloc0returnsnull \
      --disable-dependency-tracking \
      $extra ) \
    || die "$dirname: configure failed (see $src/config.log)"

  make -C "$src" -j"$JOBS" || die "$dirname: build failed"
  make -C "$src" install DESTDIR="$SYSROOT" || die "$dirname: install failed"

  # Immediately, not at the end of the run. A .la file records the paths its
  # library will have on the *device*, so libxcb.la points at /usr/lib/libXau.la
  # and the next libtool link stops dead on "not a valid libtool archive".
  # Sweeping after the loop is too late — libX11 is linked in the middle of it.
  find "$SYSROOT/usr/lib" -name '*.la' -delete

  # libxcb finds the build-time xcbgen module through
  # pkg-config --variable=pythondir. PYTHONPATH covers the case where its
  # generator runs without that threaded through — otherwise a silent
  # "ModuleNotFoundError: xcbgen".
  if [ "${dirname%%-*}" = "xcb" ]; then
    xcbgen_dir="$(dirname "$(find "$SYSROOT/usr/lib" -type d -name xcbgen -print -quit)")"
    [ -n "$xcbgen_dir" ] && [ -d "$xcbgen_dir" ] \
      || die "xcb-proto installed no xcbgen python module under $SYSROOT/usr/lib"
    export PYTHONPATH="$xcbgen_dir${PYTHONPATH:+:$PYTHONPATH}"
    info "xcbgen at $xcbgen_dir"
  fi
done

# --- Verification --------------------------------------------------------------
# The two things that go wrong quietly: a library that did not get built, and
# one built by the wrong compiler.

for pc in xproto xau xcb x11 xext xrender xfixes xi xrandr xcursor xcomposite \
          xxf86vm xinerama xtrans xshmfence freetype2; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] || [ -f "$SYSROOT/usr/share/pkgconfig/$pc.pc" ] \
    || die "no $pc.pc in the sysroot — the package that provides it did not install.
     Look for '$pc' in the log above."
done

for lib in libX11.so libXext.so libXrender.so libXfixes.so libXi.so \
           libXrandr.so libXcursor.so libXcomposite.so libXxf86vm.so \
           libXinerama.so libxcb.so libXau.so libxshmfence.so libfreetype.so; do
  path="$SYSROOT/usr/lib/$lib"
  [ -e "$path" ] || die "the sysroot has no $lib"
  # A host build reads "x86-64" here, and Wine links against it happily right up
  # until the phone rejects it.
  file -L "$path" | grep -q 'ARM aarch64' \
    || die "$lib in the sysroot is not aarch64: $(file -bL "$path")"
done

# Worth knowing when the runtime side gets built: libtool's linux-android
# configuration produces UNVERSIONED sonames (libX11.so, not libX11.so.6),
# because bionic has no versioned-symlink chain. winex11.drv.so therefore
# records DT_NEEDED libX11.so, and whatever provides X11 on the device must use
# those exact unversioned names.
echo "$PIN_ID" > "$STAMP"
ok "android X11 sysroot ready ($SYSROOT)"
