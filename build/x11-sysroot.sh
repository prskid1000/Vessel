#!/usr/bin/env bash
# Cross-build a minimal X11 client stack (plus FreeType) for Android into a
# staging sysroot at $WORK_DIR/androidsysroot.
#
# WHY THIS EXISTS. Wine's unix side runs on the phone, and its display driver is
# winex11.drv.so talking to the X server the app embeds. That driver links
# -lX11 -lXext -lXrender -lXi and friends. The Android NDK ships no X11 at all —
# not headers, not libraries — and the container's /usr/lib X11 is x86-64 glibc,
# which is worse than nothing because configure will happily find it and produce
# an aarch64 binary with host libraries recorded in it. So the stack is built
# here, against bionic, before Wine is configured.
#
# Everything installs with --prefix=/usr and DESTDIR=$SYSROOT. That combination
# (rather than --prefix=$SYSROOT/usr) is deliberate: the generated .pc files then
# carry a clean /usr prefix, and PKG_CONFIG_SYSROOT_DIR rewrites them to
# sysroot-absolute paths at use time. Verified behaviour with the container's
# pkgconf 1.8:
#     PKG_CONFIG_LIBDIR=$SYSROOT/usr/lib/pkgconfig PKG_CONFIG_SYSROOT_DIR=$SYSROOT
#   --cflags              -> -I$SYSROOT/usr/include
#   --variable=pythondir  -> $SYSROOT/usr/lib/python3.12/site-packages
# Both halves are required. With PKG_CONFIG_LIBDIR alone pkgconf decides
# -I/usr/include is a system path and DROPS it, and the build then compiles
# against whatever X11 headers the container happens to have.
#
# Idempotent: a stamp file records the exact pin set, and a matching stamp makes
# this a no-op. Bump any X11_* pin in native/pins.env and the sysroot rebuilds.
#
#   ./build/x11-sysroot.sh      # -> $WORK_DIR/androidsysroot/usr/{include,lib}

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk

SYSROOT="$WORK_DIR/androidsysroot"
SRC_CACHE="$WORK_DIR/x11-src"
BUILD_ROOT="$WORK_DIR/x11-build"
STAMP="$SYSROOT/.vessel-x11-sysroot"

XORG_BASE=https://www.x.org/releases/individual
# FreeType's own host is download.savannah.gnu.org, which returned 502 for hours
# on 2026-08-07. SourceForge is FreeType's official mirror and carries identical
# tarballs, so it is the primary here; savannah is the fallback if SF ever goes.
FREETYPE_BASE=https://downloads.sourceforge.net/project/freetype/freetype2

# Build order is dependency order and is not negotiable — every entry below
# needs the .pc files installed by the ones above it.
#
#   <url>|<name>-<version>|<extra configure args>
#
# xcb-proto is pure XML plus the xcbgen Python module; it is "cross-compiled"
# only in the sense that it lands in the sysroot. libxcb's code generator then
# runs on the BUILD machine's python3 and imports xcbgen from there.
PACKAGES=(
  # util-macros is build-time only: it provides xorg-macros.pc, which the other
  # packages' configure scripts query for compiler-warning defaults. Without it
  # every one of them prints "Package 'xorg-macros' ... not found" and carries on
  # with a reduced flag set — noise that hides the failures that do matter.
  "$XORG_BASE/util/util-macros-$X11_UTIL_MACROS.tar.xz|util-macros-$X11_UTIL_MACROS|"
  "$XORG_BASE/proto/xorgproto-$X11_XORGPROTO.tar.xz|xorgproto-$X11_XORGPROTO|"
  "$XORG_BASE/lib/xtrans-$X11_XTRANS.tar.xz|xtrans-$X11_XTRANS|"
  "$XORG_BASE/lib/libXau-$X11_LIBXAU.tar.xz|libXau-$X11_LIBXAU|"
  "$XORG_BASE/xcb/xcb-proto-$X11_XCBPROTO.tar.xz|xcb-proto-$X11_XCBPROTO|"
  "$XORG_BASE/xcb/libpthread-stubs-$X11_PTHREAD_STUBS.tar.xz|libpthread-stubs-$X11_PTHREAD_STUBS|"
  "$XORG_BASE/xcb/libxcb-$X11_LIBXCB.tar.xz|libxcb-$X11_LIBXCB|"
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
  # Not X11, but it shares this sysroot and this cross harness, and Wine's
  # configure treats a missing FreeType as a hard error rather than a notice:
  #   configure: error: FreeType development files not found.
  # Building it here is what keeps --without-freetype (a Wine with no glyph
  # rasterizer at all) off the table.
  "$FREETYPE_BASE/$FREETYPE_VERSION/freetype-$FREETYPE_VERSION.tar.xz|freetype-$FREETYPE_VERSION|--without-harfbuzz --without-brotli --without-bzip2 --without-png --with-zlib=yes"
)

# The stamp is the whole pin set, not a version number, so that changing any one
# component invalidates it. A partial sysroot is worse than none: the missing
# library only surfaces at Wine's link step, an hour later.
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
# through to the container's x86-64 GNU ar/strip. Naming them explicitly also
# keeps libtool from picking a different toolchain than the compiler.
export CC="$NDK_CC"
export CXX="$NDK_CXX"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export NM="$NDK_BIN/llvm-nm"
export READELF="$NDK_BIN/llvm-readelf"
export OBJDUMP="$NDK_BIN/llvm-objdump"

# X.Org splits .pc files across two directories: anything describing compiled
# code goes to lib/pkgconfig, anything header- or data-only to share/pkgconfig.
# xproto.pc is in the latter, so a single-directory PKG_CONFIG_LIBDIR makes
# libXau's very first dependency check fail with
#     Package 'xproto', required by 'virtual:world', not found
# even though xorgproto installed correctly a step earlier.
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
unset PKG_CONFIG_PATH

export CPPFLAGS="-I$SYSROOT/usr/include"
export CFLAGS="-O2"
export LDFLAGS="-L$SYSROOT/usr/lib"

JOBS="$(build_jobs 1)"

# bionic folds pthread, rt and dl into libc and ships no separate libraries for
# them. Autotools packages written against glibc do not know that and hardcode
# the link flag from host_os alone — libX11's configure sets XTHREADLIB=-lpthread
# for anything matching linux*, which linux-android does, and the link then dies:
#     ld.lld: error: unable to find library -lpthread
# Empty static archives satisfy the reference and contribute nothing to the
# output. This is the same shim every Android port of an X.Org library uses; the
# alternative is patching each package's configure.
for stub in pthread rt; do
  "$AR" rcs "$SYSROOT/usr/lib/lib$stub.a" \
    || die "could not create the lib$stub.a stub with $AR"
done

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

  # --disable-malloc0returnsnull: X.Org's XORG_CHECK_MALLOC_ZERO decides this
  # with a *run* test, which a cross build cannot execute. Left to guess it can
  # define MALLOC_0_RETURNS_NULL and make every Xlib allocation take a
  # compatibility path it does not need. bionic's malloc(0) returns a valid
  # pointer, same as glibc, so the answer is a plain no.
  #
  # Unknown --enable/--with options are only a warning in autoconf, which is why
  # one shared flag set can cover packages that do not all have the same options.
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

  # Delete libtool archives immediately, not at the end of the run. A .la file
  # records the paths its library will have once installed on the *device*, so
  # libxcb.la says its dependency lives at /usr/lib/libXau.la — a path that does
  # not exist in the container. The next libtool link that reads it stops dead:
  #     libtool: error: '/usr/lib/libXau.la' is not a valid libtool archive
  # Sweeping them only after the whole loop is too late; libX11 is linked in the
  # middle of it. Nothing here needs .la files at all.
  find "$SYSROOT/usr/lib" -name '*.la' -delete

  # xcbgen is a build-time Python package, and libxcb finds it through
  # pkg-config --variable=pythondir. Exporting PYTHONPATH as well covers the
  # case where libxcb's generator is invoked without that variable threaded
  # through, which is a silent "ModuleNotFoundError: xcbgen" otherwise.
  if [ "${dirname%%-*}" = "xcb" ]; then
    xcbgen_dir="$(dirname "$(find "$SYSROOT/usr/lib" -type d -name xcbgen -print -quit)")"
    [ -n "$xcbgen_dir" ] && [ -d "$xcbgen_dir" ] \
      || die "xcb-proto installed no xcbgen python module under $SYSROOT/usr/lib"
    export PYTHONPATH="$xcbgen_dir${PYTHONPATH:+:$PYTHONPATH}"
    info "xcbgen at $xcbgen_dir"
  fi
done

# --- Verification --------------------------------------------------------------
# Check the two things that can go wrong quietly: a library that did not get
# built at all, and one that was built by the wrong compiler.

for pc in xproto xau xcb x11 xext xrender xfixes xi xrandr xcursor xcomposite \
          xxf86vm xinerama xtrans freetype2; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] || [ -f "$SYSROOT/usr/share/pkgconfig/$pc.pc" ] \
    || die "no $pc.pc in the sysroot — the package that provides it did not install.
     Look for '$pc' in the log above."
done

for lib in libX11.so libXext.so libXrender.so libXfixes.so libXi.so \
           libXrandr.so libXcursor.so libXcomposite.so libXxf86vm.so \
           libXinerama.so libxcb.so libXau.so libfreetype.so; do
  path="$SYSROOT/usr/lib/$lib"
  [ -e "$path" ] || die "the sysroot has no $lib"
  # A host build would produce "ELF 64-bit LSB shared object, x86-64" here, and
  # Wine would link against it happily right up until the phone rejected it.
  file -L "$path" | grep -q 'ARM aarch64' \
    || die "$lib in the sysroot is not aarch64: $(file -bL "$path")"
done

# Worth knowing when the runtime side of this gets built: libtool's linux-android
# configuration produces UNVERSIONED sonames — libX11.so, not libX11.so.6 —
# because bionic has no support for the versioned-symlink chain glibc uses. So
# winex11.drv.so records DT_NEEDED libX11.so, and whatever provides the X11
# client libraries on the device must use those exact unversioned names.
echo "$PIN_ID" > "$STAMP"
ok "android X11 sysroot ready ($SYSROOT)"
