#!/usr/bin/env bash
# Cross-build GLib, GStreamer and FFmpeg for Android into the SAME staging
# sysroot build/x11-sysroot.sh creates, so that Wine can be built
# --with-gstreamer and winegstreamer.so actually exists.
#
#   ./build/gst-sysroot.sh      # -> $WORK_DIR/androidsysroot/usr/{include,lib}
#
# WHY THIS EXISTS. Wine's Media Foundation is not implemented in Wine. mfplat,
# mfreadwrite, mf, mfsrcsnk and the DirectShow parsers are PE modules that
# negotiate formats; the demuxing and decoding is winegstreamer.so on the unix
# side, and the byte stream handler that turns a file into a source
# (CLSID_GStreamerByteStreamHandler, winegstreamer_classes.idl) is registered by
# that module and by nothing else. Configure Wine --without-gstreamer and the
# whole PE half still builds and installs, so the tree looks complete -- and
# every MFCreateSourceReader* call fails with
#
#   MF_E_UNSUPPORTED_BYTESTREAM_TYPE (0xC00D36C4)
#   ole:com_get_class_object class {317df618-...} not registered
#
# which is what stops Resident Evil Requiem on a modal dialog at its intro
# video. See native/pins.env for the version pins and the reasoning on each.
#
# WHY A SECOND SCRIPT rather than more entries in x11-sysroot.sh. Two reasons,
# and neither is taste:
#
#   1. That script's loop is autotools only -- ./configure, make, make install.
#      GLib and every GStreamer module are meson, FFmpeg has a configure script
#      that is not autoconf's, and teaching one loop three build systems makes
#      the X11 stack harder to read for no benefit.
#   2. Its stamp covers its whole package list, so it wipes and rebuilds the
#      sysroot from util-macros down whenever any entry changes. Iterating on
#      the GStreamer stack behind that stamp costs the X11 and GnuTLS builds
#      every time.
#
# The two share $SYSROOT, and the ordering is not optional: x11-sysroot.sh does
# `rm -rf "$SYSROOT"` when its pins change, which would take this stack with it.
# So this script runs x11-sysroot.sh first and folds that script's stamp into
# its own -- an X11 rebuild therefore forces a GStreamer rebuild, which is the
# only correct answer once they live in the same directory.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
setup_ndk

SYSROOT="$WORK_DIR/androidsysroot"
SRC_CACHE="$WORK_DIR/gst-src"
BUILD_ROOT="$WORK_DIR/gst-build"
# INSIDE $SYSROOT, and that placement is the whole point of it.
#
# A per-package stamp is a claim that the package's files are in the sysroot, so
# it has to die with them. It used to live under $BUILD_ROOT, which x11-sysroot.sh
# does not touch: that script's `rm -rf "$SYSROOT" "$BUILD_ROOT"` clears the
# shared sysroot and *its own* build root, so every stamp here survived a wipe
# that had just deleted everything it vouched for. The next run then skipped the
# whole stack -- "glib-2.88.3 already installed in the sysroot" -- and died at the
# first thing that looked for a file rather than a stamp:
#
#     error: no glib-2.0.pc in the sysroot.
#
# The header above says an X11 rebuild must force a GStreamer rebuild, and folds
# x11-sysroot's stamp into PIN_ID to make it so. That is correct and it fired --
# but it only governs $STAMP, the all-or-nothing gate, and the per-package stamps
# were consulted after it. Keeping them here makes the invariant structural
# instead of remembered: the wipe that invalidates them is the wipe that removes
# them, and no future caller has to know the two scripts share a directory.
STAMPS="$SYSROOT/.vessel-gst-stamps"
STAMP="$SYSROOT/.vessel-gst-sysroot"

# glib-mkenums and glib-genmarshal are run by meson's `gnome` module through
# find_program(), which is a BUILD-machine lookup -- the container has no
# libglib2.0-dev, so without help every GStreamer module fails at configure
# with "Program 'glib-mkenums' not found". The cross-built GLib installs those
# two as plain Python scripts (they are architecture-independent; only
# glib-compile-resources and friends are ELF), so a directory of symlinks to
# them, prepended to PATH, satisfies the lookup with the exact version of the
# tools that matches the GLib being linked against. That is strictly better
# than a host GLib from apt, which could be a different series.
NATIVE_BIN="$WORK_DIR/gst-native-bin"

GST_BASE=https://gstreamer.freedesktop.org/src
GLIB_BASE=https://download.gnome.org/sources/glib
FFMPEG_BASE=https://ffmpeg.org/releases

# <url>|<source dir name>|<build system>|<configure arguments>
#
# Build order is dependency order. Every entry below needs the .pc files
# installed by the ones above it, and the meson entries additionally need
# NATIVE_BIN populated, which happens the moment GLib installs.
PACKAGES=(
  # --- GLib and its two hard dependencies -------------------------------------
  # Both are meson subprojects inside GLib when they are missing, and meson
  # would fetch them over the network mid-build. --wrap-mode=nodownload below
  # turns that into a hard error instead, so they are built here.
  #
  # --disable-multi-os-directory: libffi otherwise installs into
  # $prefix/lib64 on a 64-bit target, and nothing else in this sysroot looks
  # there.
  "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz|libffi-$LIBFFI_VERSION|autotools|--disable-docs --disable-multi-os-directory"
  # 8-bit only: GLib's GRegex uses libpcre2-8 and nothing here wants the 16-
  # or 32-bit code units. --disable-jit for the reason in native/pins.env.
  "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_VERSION/pcre2-$PCRE2_VERSION.tar.gz|pcre2-$PCRE2_VERSION|autotools|--enable-pcre2-8 --disable-pcre2-16 --disable-pcre2-32 --enable-unicode --disable-jit --disable-pcre2grep-libz --disable-pcre2grep-libbz2 --disable-pcre2test-libreadline"
  # The gettext stub, and it is required even though nls is disabled below --
  # GLib asks for the `intl` dependency unconditionally. See native/pins.env;
  # this is the exact tarball GLib's own subprojects/proxy-libintl.wrap names.
  "https://github.com/frida/proxy-libintl/archive/refs/tags/$PROXY_LIBINTL_VERSION.tar.gz|proxy-libintl-$PROXY_LIBINTL_VERSION|meson|"
  # nls disabled because there are no translations worth shipping into a phone
  # app's Wine prefix; libmount/selinux/xattr because none of the three exists
  # in the NDK sysroot and each is a hard configure failure rather than a
  # graceful skip.
  "$GLIB_BASE/$GLIB_SERIES/glib-$GLIB_VERSION.tar.xz|glib-$GLIB_VERSION|meson|-Dtests=false -Dinstalled_tests=false -Dnls=disabled -Dlibmount=disabled -Dselinux=disabled -Dxattr=false -Ddtrace=disabled -Dsystemtap=disabled -Dsysprof=disabled -Dman-pages=disabled -Ddocumentation=false -Dintrospection=disabled -Dlibelf=disabled -Dmultiarch=false -Dglib_debug=disabled"

  # --- GStreamer core ----------------------------------------------------------
  # gst_parse needs flex and bison, which the image has. ptp-helper is a setuid
  # helper binary and meaningless in an app sandbox. libunwind/libdw would be
  # extra dependencies for prettier stack traces in a log we do not read.
  "$GST_BASE/gstreamer/gstreamer-$GSTREAMER_VERSION.tar.xz|gstreamer-$GSTREAMER_VERSION|meson|-Dauto_features=disabled -Dgst_debug=true -Dgst_parse=true -Dregistry=true -Dtracer_hooks=true -Dptp-helper=disabled -Dtools=disabled -Dexamples=disabled -Dtests=disabled -Dbenchmarks=disabled -Dintrospection=disabled -Dnls=disabled -Ddoc=disabled -Dbash-completion=disabled"

  # --- gst-plugins-base ---------------------------------------------------------
  # This module carries four of the five pkg-config names Wine's configure asks
  # for: gstreamer-video-1.0, gstreamer-audio-1.0, gstreamer-tag-1.0 and
  # gstreamer-gl-1.0.
  #
  # **-Dgl=enabled is required and is the fiddliest line in this file.** Wine
  # includes <gst/gl/gl.h> from both unixlib.c and wg_parser.c, so a build
  # without the GL library does not merely lose a feature -- configure's
  # five-module pkg-config query returns nothing at all, GSTREAMER_CFLAGS is
  # left empty, and winegstreamer is silently dropped. Android has no GLX, so
  # the platform is EGL; gl_winsys=egl is the surfaceless backend, which needs
  # nothing but libEGL/libGLESv2 from bionic. Note Wine forces GST_GL_WINDOW=x11
  # at init (unixlib.c) and that will not resolve here -- gst_gl_display_new()
  # then logs a GST_ERROR and returns, which is not fatal, and the GL element
  # chain in wg_parser.c is only entered when the caller asked for it.
  #
  # auto_features=disabled and then an explicit list, because the default 'auto'
  # probes the CONTAINER: an x86-64 host library that answers a probe becomes a
  # NEEDED entry in an aarch64 plugin, which is the failure mode Wine's own
  # --without-* list in build/wine.sh exists to prevent.
  "$GST_BASE/gst-plugins-base/gst-plugins-base-$GSTREAMER_VERSION.tar.xz|gst-plugins-base-$GSTREAMER_VERSION|meson|-Dauto_features=disabled -Dgl=enabled -Dgl_api=gles2 -Dgl_platform=egl -Dgl_winsys=egl -Dapp=enabled -Daudioconvert=enabled -Daudioresample=enabled -Daudiorate=enabled -Dvideoconvertscale=enabled -Dvideorate=enabled -Dvolume=enabled -Dplayback=enabled -Dtypefind=enabled -Dsubparse=enabled -Drawparse=enabled -Dencoding=enabled -Dpbtypes=enabled -Dgio=enabled -Ddebugutils=enabled -Dtools=disabled -Dexamples=disabled -Dtests=disabled -Dintrospection=disabled -Dnls=disabled -Ddoc=disabled -Dorc=disabled -Diso-codes=disabled"

  # --- gst-plugins-good ----------------------------------------------------------
  # isomp4 is qtdemux, which is the MP4 demuxer and the single most important
  # plugin in this file for the stated goal. capssetter, deinterlace and
  # videoflip are the other three: wg_parser.c builds every raw-video pad's
  # post-processing chain out of capssetter -> videoconvert -> deinterlace ->
  # videoflip -> videoconvert and returns failure if ANY of them is missing, so
  # a plugin set without them decodes nothing at all. (Wine's source comments
  # tag capssetter "good" and it is: gst/debugutils/gstcapssetter.c lives here,
  # not in -bad, whose debugutils directory is a different plugin.)
  "$GST_BASE/gst-plugins-good/gst-plugins-good-$GSTREAMER_VERSION.tar.xz|gst-plugins-good-$GSTREAMER_VERSION|meson|-Dauto_features=disabled -Disomp4=enabled -Dmatroska=enabled -Davi=enabled -Dflv=enabled -Dwavparse=enabled -Dwavenc=enabled -Daudioparsers=enabled -Dvideofilter=enabled -Ddeinterlace=enabled -Ddebugutils=enabled -Daudiofx=enabled -Dinterleave=enabled -Dautodetect=enabled -Did3demux=enabled -Dicydemux=enabled -Dapetag=enabled -Dxingmux=enabled -Dlaw=enabled -Dvideocrop=enabled -Dvideobox=enabled -Dalpha=enabled -Dmultifile=enabled -Dexamples=disabled -Dtests=disabled -Dnls=disabled -Ddoc=disabled -Dorc=disabled"

  # --- gst-plugins-bad -------------------------------------------------------------
  # Deliberately almost nothing. audiobuffersplit is created by wg_transform.c
  # for the MFT path; videoparsers gives h264parse/h265parse, which decodebin
  # inserts ahead of a decoder and which is what makes a byte-stream H.264 track
  # inside an MP4 negotiate cleanly; mpegtsdemux/mpegdemux cover the .ts and
  # .mpg intros some engines still ship. Everything else in this module wants an
  # external library the sysroot does not have.
  "$GST_BASE/gst-plugins-bad/gst-plugins-bad-$GSTREAMER_VERSION.tar.xz|gst-plugins-bad-$GSTREAMER_VERSION|meson|-Dauto_features=disabled -Daudiobuffersplit=enabled -Dvideoparsers=enabled -Dmpegtsdemux=enabled -Dmpegdemux=enabled -Dcodecalpha=enabled -Dcodectimestamper=enabled -Dexamples=disabled -Dtests=disabled -Dtools=disabled -Dintrospection=disabled -Dnls=disabled -Ddoc=disabled -Dorc=disabled -Dgpl=disabled"

  # --- FFmpeg, and gst-libav in front of it -----------------------------------------
  # THE DECODERS. Without this pair the stack can open an MP4, find an H.264
  # track, and then have nothing to decode it with -- decodebin fails to link
  # and the SourceReader reports no streams, which looks a lot like the failure
  # this whole script is fixing.
  #
  # Encoders and muxers are off: Wine's video_encoder.c and media_sink.c are
  # about transcoding and recording, neither of which a game intro needs, and
  # the encoder tables are most of libavcodec's size. Filters are off except the
  # handful libavfilter needs to instantiate a graph at all -- gst-libav links
  # libavfilter unconditionally (ext/libav/gstavdeinterlace.c) even though
  # GStreamer does its own deinterlacing.
  #
  # --target-os=android is what makes the sonames unversioned; see pins.env.
  "$FFMPEG_BASE/ffmpeg-$FFMPEG_VERSION.tar.xz|ffmpeg-$FFMPEG_VERSION|ffmpeg|"
  "$GST_BASE/gst-libav/gst-libav-$GSTREAMER_VERSION.tar.xz|gst-libav-$GSTREAMER_VERSION|meson|-Dtests=disabled -Ddoc=disabled"
)

# --- stamping -------------------------------------------------------------------
# Two levels, and they answer different questions.
#
# $STAMP (in the sysroot) is what build/wine.sh folds into its CONF_ID, so that
# changing anything here forces Wine to re-run configure instead of reusing a
# tree whose config.h still says winegstreamer is off. It covers the whole list
# plus x11-sysroot.sh's own stamp.
#
# $STAMPS/<name> (in the sysroot, see its definition for why there and not in
# the build tree) is per package, so that editing the gst-plugins-bad line does
# not rebuild GLib. Once any package rebuilds, every package after it rebuilds
# too -- they link against it.

"$COMMON_SH_DIR/x11-sysroot.sh" \
  || die "x11-sysroot.sh failed; the GStreamer stack installs into the same sysroot"

X11_STAMP_ID="$(cat "$SYSROOT/.vessel-x11-sysroot" 2>/dev/null || echo none)"
[ "$X11_STAMP_ID" != none ] \
  || die "no X11 sysroot stamp at $SYSROOT/.vessel-x11-sysroot after x11-sysroot.sh ran"

resolve_cpu_flags "$NDK_CC"

# --- FFmpeg's configure line -------------------------------------------------------
# Long enough to deserve its own function rather than a fifth field in the
# PACKAGES entries -- and defined HERE, above the stamps, because its hash goes
# into PIN_ID and into the FFmpeg entry's own per-package stamp. An FFmpeg
# configure flag is exactly as load-bearing as a version pin, and if editing
# this list did not move those stamps then dropping, say, --disable-encoders
# would rebuild nothing and change nothing.
#
# Read the pins.env note before changing --target-os: it is what makes the
# sonames unversioned.
#
# Not in this list, deliberately, because FFmpeg 8 does not have them:
# --disable-postproc (libpostproc is GPL-only now and self-disables without
# --enable-gpl) and --disable-linux-perf (only the --enable- form exists).
# Both are hard errors from FFmpeg's hand-written configure, not warnings.
ffmpeg_configure_args() {
  printf '%s\n' \
    --prefix=/usr --libdir=/usr/lib --incdir=/usr/include \
    --enable-cross-compile --target-os=android --arch=aarch64 \
    --cc="$NDK_CC" --cxx="$NDK_CXX" \
    --ar="$NDK_BIN/llvm-ar" --nm="$NDK_BIN/llvm-nm" \
    --ranlib="$NDK_BIN/llvm-ranlib" --strip="$NDK_BIN/llvm-strip" \
    --enable-shared --disable-static --enable-pic \
    --disable-programs --disable-doc --disable-debug \
    --disable-avdevice --disable-network \
    --disable-encoders --disable-muxers --disable-devices \
    --disable-hwaccels --disable-v4l2-m2m --disable-vulkan \
    --disable-iconv \
    --disable-protocols --enable-protocol=file --enable-protocol=pipe \
    --disable-filters \
    --enable-filter=aresample,aformat,anull,abuffer,abuffersink \
    --enable-filter=scale,format,null,copy,setpts,asetpts,buffer,buffersink \
    --extra-cflags="-O2 ${VESSEL_CPU_FLAGS:-}"
}

FFMPEG_ARGS_ID="$(ffmpeg_configure_args | sha256sum | cut -d' ' -f1)"

PIN_ID="$(printf '%s\n' "${PACKAGES[@]}" "$X11_STAMP_ID" "$FFMPEG_ARGS_ID" | sha256sum | cut -d' ' -f1)"

if [ -z "${VESSEL_GST_CLEAN:-}" ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$PIN_ID" ]; then
  info "GStreamer stack already built into $SYSROOT"
  exit 0
fi

log "building the Android GStreamer stack into $SYSROOT"
info "GLib $GLIB_VERSION, GStreamer $GSTREAMER_VERSION, FFmpeg $FFMPEG_VERSION"

if [ -n "${VESSEL_GST_CLEAN:-}" ]; then
  info "VESSEL_GST_CLEAN set — discarding every per-package stamp"
  rm -rf "$STAMPS"
fi

mkdir -p "$SRC_CACHE" "$BUILD_ROOT" "$STAMPS" "$NATIVE_BIN"

HOST_TRIPLE="aarch64-linux-android$NDK_API"
JOBS="$(build_jobs 1)"

# Same isolation as x11-sysroot.sh and build/wine.sh: PKG_CONFIG_LIBDIR REPLACES
# the search path so the container's x86-64 .pc files cannot be found, and
# PKG_CONFIG_SYSROOT_DIR rewrites the /usr prefixes the .pc files carry.
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
unset PKG_CONFIG_PATH

export CC="$NDK_CC"
export CXX="$NDK_CXX"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export NM="$NDK_BIN/llvm-nm"
export READELF="$NDK_BIN/llvm-readelf"
export OBJDUMP="$NDK_BIN/llvm-objdump"

export CPPFLAGS="-I$SYSROOT/usr/include"
export CFLAGS="-O2 ${VESSEL_CPU_FLAGS:-}"
export CXXFLAGS="-O2 ${VESSEL_CPU_FLAGS:-}"
export LDFLAGS="-L$SYSROOT/usr/lib"

export PATH="$NATIVE_BIN:$PATH"

HOST_PKG_CONFIG="$(command -v pkg-config || true)"
[ -n "$HOST_PKG_CONFIG" ] || die "pkg-config is not on PATH"

# --- the meson cross file --------------------------------------------------------
# system = 'android' rather than 'linux', and unlike build/turnip.sh's ICD case
# that IS what we want here: meson gives Android shared libraries UNVERSIONED
# file names and sonames (libglib-2.0.so, never libglib-2.0.so.0), which is what
# bionic wants and what build/wine.sh's `*.so` copy glob can see. The check at
# the bottom of this script asserts it rather than trusting it.
# Built as a comma-separated meson array. `if`, not `[ ... ] &&`: the last
# command of a `set -e` script segment returning 1 kills the run, and an empty
# VESSEL_CPU_FLAGS makes both of those tests false.
CPU_ARGS=""
for f in ${VESSEL_CPU_FLAGS:-}; do
  if [ -n "$CPU_ARGS" ]; then CPU_ARGS="$CPU_ARGS, "; fi
  CPU_ARGS="$CPU_ARGS'$f'"
done
if [ -n "$CPU_ARGS" ]; then CPU_ARGS="$CPU_ARGS, "; fi
CPU_ARGS="$CPU_ARGS'-I$SYSROOT/usr/include'"

CROSS="$WORK_DIR/gst-cross.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$NDK_CC'
cpp = '$NDK_CXX'
ar = '$NDK_BIN/llvm-ar'
ranlib = '$NDK_BIN/llvm-ranlib'
strip = '$NDK_BIN/llvm-strip'
nm = '$NDK_BIN/llvm-nm'
readelf = '$NDK_BIN/llvm-readelf'
objcopy = '$NDK_BIN/llvm-objcopy'
pkg-config = '$HOST_PKG_CONFIG'

[properties]
sys_root = '$SYSROOT'
pkg_config_libdir = ['$SYSROOT/usr/lib/pkgconfig', '$SYSROOT/usr/share/pkgconfig']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$CPU_ARGS]
cpp_args = [$CPU_ARGS]
c_link_args = ['-L$SYSROOT/usr/lib']
cpp_link_args = ['-L$SYSROOT/usr/lib']
EOF
info "cross file: $CROSS"

# --- build ------------------------------------------------------------------------

# Once one package rebuilds, everything downstream of it must too.
FORCE_REST=

for entry in "${PACKAGES[@]}"; do
  IFS='|' read -r url dirname buildsys extra <<< "$entry"
  name="${dirname%-*}"
  # Named for the package, not for the URL's last path element. GitHub's archive
  # endpoint serves proxy-libintl as `0.5.tar.gz`, which says nothing and would
  # collide with the next project versioned 0.5. `tar -xf` picks the
  # decompressor out of the file, so dropping the real extension costs nothing.
  tarball="$SRC_CACHE/$dirname.tarball"
  # The FFmpeg entry carries no configure arguments of its own -- they are in
  # ffmpeg_configure_args() -- so its id has to name them explicitly or editing
  # that function would rebuild nothing. Only that entry: folding the id into
  # every package would rebuild GLib for an FFmpeg flag.
  entry_extra=""
  if [ "$buildsys" = ffmpeg ]; then entry_extra="$FFMPEG_ARGS_ID"; fi
  entry_id="$(printf '%s\n%s\n%s\n' "$entry" "$X11_STAMP_ID" "$entry_extra" | sha256sum | cut -d' ' -f1)"
  pkg_stamp="$STAMPS/$name"

  if [ -z "$FORCE_REST" ] && [ -f "$pkg_stamp" ] && [ "$(cat "$pkg_stamp")" = "$entry_id" ]; then
    info "$dirname already installed in the sysroot"
    continue
  fi
  FORCE_REST=1

  if [ ! -f "$tarball" ]; then
    info "fetching $(basename "$url")"
    curl -fSL --retry 3 -o "$tarball.part" "$url" \
      || die "download failed: $url
     Check the version pin for $dirname in native/pins.env."
    mv "$tarball.part" "$tarball"
  fi

  src="$BUILD_ROOT/$dirname"
  rm -rf "$src"
  tar -xf "$tarball" -C "$BUILD_ROOT" || die "could not unpack $tarball"
  [ -d "$src" ] || die "$tarball did not unpack to $src"

  log "gst-sysroot: $dirname ($buildsys)"

  case "$buildsys" in
    autotools)
      # shellcheck disable=SC2086
      ( cd "$src" && ./configure \
          --host="$HOST_TRIPLE" \
          --prefix=/usr \
          --libdir=/usr/lib \
          --disable-static \
          --enable-shared \
          --disable-dependency-tracking \
          $extra ) \
        || die "$dirname: configure failed (see $src/config.log)"
      make -C "$src" -j"$JOBS"                     || die "$dirname: build failed"
      make -C "$src" install DESTDIR="$SYSROOT"    || die "$dirname: install failed"
      ;;

    meson)
      # --wrap-mode=nodownload: a missing dependency must be a loud error, never
      # a subproject silently fetched over the network. That is the same rule
      # native/pins.env applies everywhere else -- what gets built has to be
      # answerable from this repository.
      #
      # --libdir=lib because meson derives the default from the BUILD machine
      # and on Debian/Ubuntu that is lib/x86_64-linux-gnu. Wrong directory, and
      # then nothing finds the .pc files.
      # shellcheck disable=SC2086
      rm -rf "$src/_build"
      ( cd "$src" && meson setup _build \
          --cross-file "$CROSS" \
          --prefix=/usr \
          --libdir=lib \
          --libexecdir=libexec \
          --bindir=bin \
          --includedir=include \
          --datadir=share \
          --buildtype=release \
          --default-library=shared \
          --wrap-mode=nodownload \
          $extra ) \
        || die "$dirname: meson setup failed (see $src/_build/meson-logs/meson-log.txt)"
      meson compile -C "$src/_build" -j "$JOBS" \
        || die "$dirname: build failed"
      DESTDIR="$SYSROOT" meson install -C "$src/_build" --no-rebuild \
        || die "$dirname: install failed"
      ;;

    ffmpeg)
      # FFmpeg's configure is hand written, not autoconf: it rejects
      # --host/--disable-dependency-tracking and spells everything else its own
      # way. Hence a case of its own rather than a longer autotools line.
      local_args=()
      while IFS= read -r a; do local_args+=("$a"); done < <(ffmpeg_configure_args)
      ( cd "$src" && ./configure "${local_args[@]}" ) \
        || die "$dirname: configure failed (see $src/ffbuild/config.log)"
      make -C "$src" -j"$JOBS"                     || die "$dirname: build failed"
      make -C "$src" install DESTDIR="$SYSROOT"    || die "$dirname: install failed"
      ;;

    *)
      die "unknown build system '$buildsys' for $dirname"
      ;;
  esac

  # Immediately, for the reason x11-sysroot.sh gives: a .la file records the
  # paths its library will have on the DEVICE, and the next libtool link stops
  # on "not a valid libtool archive".
  find "$SYSROOT/usr/lib" -name '*.la' -delete

  # GLib is the point at which meson's gnome module becomes usable for
  # everything after it. Done here rather than as a package of its own so that
  # a rebuilt GLib always refreshes the tools that match it.
  if [ "$name" = glib ]; then
    for tool in glib-mkenums glib-genmarshal gdbus-codegen; do
      [ -f "$SYSROOT/usr/bin/$tool" ] \
        || die "GLib installed no $tool -- meson's gnome module cannot run
     mkenums_simple without it and every GStreamer module will fail at setup."
      # These must be interpreted scripts. If a future GLib ships them as ELF,
      # a host GLib (libglib2.0-dev in the image) becomes the only option and
      # this check says so instead of the build dying with 'Exec format error'.
      head -c2 "$SYSROOT/usr/bin/$tool" | grep -q '#!' \
        || die "$SYSROOT/usr/bin/$tool is not a script, so it cannot run on the
     build machine. Add libglib2.0-dev to the Dockerfile and drop \$NATIVE_BIN."
      ln -sf "$SYSROOT/usr/bin/$tool" "$NATIVE_BIN/$tool"
    done
    ok "meson's glib tools linked into $NATIVE_BIN"
  fi

  echo "$entry_id" > "$pkg_stamp"
done

# --- Verification -----------------------------------------------------------------
# Written before the first build ran. Each of these has a stated failure meaning;
# none of them is satisfied by the script exiting 0.

# 1. All five pkg-config modules Wine's configure.ac:1731 asks for, plus the two
#    gst-libav needs. A missing one here means Wine's WINE_PACKAGE_FLAGS returns
#    an empty GSTREAMER_CFLAGS and winegstreamer is dropped WITHOUT an error.
for pc in glib-2.0 gobject-2.0 gio-2.0 gmodule-2.0 \
          gstreamer-1.0 gstreamer-base-1.0 gstreamer-video-1.0 \
          gstreamer-audio-1.0 gstreamer-tag-1.0 gstreamer-gl-1.0 \
          gstreamer-pbutils-1.0 gstreamer-app-1.0 \
          libavcodec libavformat libavutil libavfilter; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] \
    || die "no $pc.pc in the sysroot.
     Wine's configure asks pkg-config for gstreamer-1.0 gstreamer-video-1.0
     gstreamer-audio-1.0 gstreamer-tag-1.0 gstreamer-gl-1.0 in ONE query and
     silently disables winegstreamer if any of them is missing. Look for
     '$pc' in the log above."
done

# 2. Every shared library is aarch64 and its SONAME equals its file name.
#    A versioned soname would install as libfoo.so.0 with libfoo.so a symlink,
#    and build/wine.sh copies `$SYSROOT/usr/lib/*.so` by glob -- the payload
#    would get a file named libfoo.so that no NEEDED entry mentions, and the
#    device would fail to load the plugin with nothing in the log naming this.
shopt -s nullglob
sysroot_libs=("$SYSROOT/usr/lib"/*.so)
shopt -u nullglob
[ "${#sysroot_libs[@]}" -gt 0 ] || die "no shared libraries in $SYSROOT/usr/lib"

for lib in "${sysroot_libs[@]}"; do
  base="$(basename "$lib")"
  file -L "$lib" | grep -q 'ARM aarch64' \
    || die "$base is not aarch64: $(file -bL "$lib")"
  # Captured, never piped into grep -q: readelf would take SIGPIPE and pipefail
  # turns that into a failure of the whole check.
  dyn="$("$NDK_BIN/llvm-readelf" -d "$lib" 2>/dev/null || true)"
  soname="$(grep SONAME <<<"$dyn" | sed 's/.*\[//;s/\]//' | head -1)"
  [ -z "$soname" ] || [ "$soname" = "$base" ] \
    || die "$base records SONAME '$soname', which is not its own file name.
     Something in this script produced a versioned library. bionic has no
     versioned-symlink chain and build/wine.sh copies *.so by glob, so the
     payload would ship a file nothing can resolve. Check the meson cross
     file's host_machine.system -- it must be 'android'."
done

# 3. The plugins that wg_parser.c and wg_transform.c create by name, and the
#    decoder without which the whole exercise is decorative. Checked as files
#    rather than by running gst-inspect, which cannot run here.
PLUGIN_DIR="$SYSROOT/usr/lib/gstreamer-1.0"
[ -d "$PLUGIN_DIR" ] || die "no plugin directory at $PLUGIN_DIR"

for plug in libgstcoreelements.so libgstplayback.so libgsttypefindfunctions.so \
            libgstaudioconvert.so libgstaudioresample.so libgstvideoconvertscale.so \
            libgstapp.so libgstisomp4.so libgstdeinterlace.so libgstvideofilter.so \
            libgstdebug.so libgstaudiobuffersplit.so libgstvideoparsersbad.so \
            libgstlibav.so libgstopengl.so; do
  [ -f "$PLUGIN_DIR/$plug" ] \
    || die "the plugin set has no $plug.
     wg_parser.c's raw-video chain is capssetter (libgstdebug) -> videoconvert
     (libgstvideoconvertscale) -> deinterlace -> videoflip (libgstvideofilter)
     and returns failure if any element cannot be created; libgstisomp4 is
     qtdemux and libgstlibav is the H.264/AAC decoder. Absence of any of these
     is a FAILURE even though the build exited 0."
done

echo "$PIN_ID" > "$STAMP"
ok "android GStreamer stack ready ($(ls "$PLUGIN_DIR" | wc -l) plugins in $PLUGIN_DIR)"
