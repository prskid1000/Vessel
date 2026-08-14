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

fetch_source "$COMPONENT" "$WINE_REPO" "$WINE_REF" "${WINE_EXACT:-}"

SRC="$NATIVE_DIR/$COMPONENT"

# WINE_REF is a branch, so the ref carries no version; the tree's VERSION file
# holds it as "Wine version 10.12".
WINE_VERSION="$(sed -n 's/^Wine version[[:space:]]*//p' "$SRC/VERSION" 2>/dev/null | head -1 || true)"
[ -n "$WINE_VERSION" ] || die "could not read a version out of $SRC/VERSION"

# **A Proton build is not ordered by its Wine version, and must not be.**
#
# package_wcp.py derives a monotonic integer from the dotted version, and the
# app picks a component by taking the HIGHEST code: ComponentStore.adoptLatest
# for a new container, and ComponentSetup's "wanted" filter for what is even
# offered for install. Proton 11.0 is rebased on Wine *11.0*, so the honest
# Wine number is 11.0 -> 1100, which sorts BELOW the 11.14 -> 1114 already on
# the phone. The package would install and then never be chosen, and setup
# would not offer it at all -- a silent wrong-component run, which has already
# cost this project two test sessions.
#
# So a Proton build declares an epoch that outranks any plain-Wine build. The
# number stops being "which Wine is newer" and becomes "which base did we
# choose", which is the question the app is actually asking.
#
# THE HAZARD CUTS BOTH WAYS, so it is written here rather than discovered:
# while this epoch exists, a plain-Wine component can never outrank a Proton
# one. Moving back to upstream Wine means BOTH restoring pins.env AND deleting
# the installed Proton component from the phone -- changing pins.env alone will
# build a package the app then ignores, which looks exactly like a build that
# did not take.
WINE_PROTON_EPOCH=1000000

# Is this a Valve base? Decided once, here, because the answer is needed in two
# places and they must never disagree.
#
# **They did disagree, and it is why this is a variable.** Both tests originally
# matched only `proton*`. Pointing WINE_REF at `experimental_11.0` -- as much a
# Proton base as `proton_11.0` is, same Wine 11.0, 461 commits further on --
# missed both: the version arm below fell through to plain Wine and produced an
# empty VERSION_CODE, and the generated-sources block was skipped entirely, so
# configure died with
#
#     error: open wine/vulkan.h : No such file or directory
#
# naming a file no part of the build had said anything about. One predicate,
# used twice, cannot drift like that.
case "$WINE_REF" in
  proton*|experimental*) VALVE_BASE=1 ;;
  *)                     VALVE_BASE= ;;
esac

case "$WINE_REF" in
  proton*|experimental*)
    # Named apart so the two are distinguishable in dist/ and in the app: both
    # are Wine 11.0 and both would otherwise package as `proton-11.0`, which
    # would make an experimental build silently look like the stable one.
    case "$WINE_REF" in
      experimental*) VERSION="proton-exp-$WINE_VERSION" ;;
      *)             VERSION="proton-$WINE_VERSION" ;;
    esac
    # Epoch first, then two digits for Vessel's own patch revision. The epoch
    # says which base was chosen; the revision says which patch set, and
    # without it a rebuild carrying a new patch keeps its version code and
    # ComponentStore treats it as bytes it already has. See vessel_version_code.
    VERSION_CODE=$(( (WINE_PROTON_EPOCH + $(python3 -c \
      'import sys; sys.path.insert(0, "'"$COMMON_SH_DIR"'"); from package_wcp import version_code; print(version_code(sys.argv[1]))' \
      "$WINE_VERSION")) * 100 + ${WINE_REVISION:-0} ))
    info "wine version $WINE_VERSION as $VERSION, code $VERSION_CODE (Proton epoch)"
    ;;
  *)
    VERSION="$WINE_VERSION"
    VERSION_CODE=
    info "wine version $VERSION (branch $WINE_REF @ ${SOURCE_SHA:0:12})"
    ;;
esac

# **Valve's tree does not keep Wine's generated sources current; upstream does.**
#
# Wine generates several sources from other sources. Upstream commits the
# results, so a checkout is ready to configure. Valve's tree is not: some of
# those files are absent, and — worse — some are present but STALE.
#
# Stale is the case that matters, and the one that cost a build here. The first
# version of this block guarded each generator on its output being missing,
# which is exactly the wrong test: `include/wine/server_protocol.h` IS committed
# in proton_11.0, so the guard skipped `make_requests`, and the header it left
# in place was protocol version 930 describing a `server/protocol.def` that had
# moved on to 931. Nothing complains at configure time. The build gets a long
# way in and then fails in ntdll with errors that name none of it:
#
#     fsync.c: field has incomplete type 'enum fsync_type'
#     fsync.c: use of undeclared identifier 'FSYNC_SHM_PAGE_SIZE'
#     file.c:  no member named 'query_directory_file_request' in 'union generic_request'
#     registry.c: variable has incomplete type 'enum prefix_type'
#
# Every one of those symbols is defined in protocol.def and absent from the
# committed header. So on a Proton base every generator runs unconditionally;
# presence is not evidence of currency, and regenerating a file that was already
# correct costs seconds and changes nothing.
#
#   tools/make_requests    (perl)   include/wine/server_protocol.h, server/request_*.h
#   tools/make_specfiles   (perl)   dlls/ntdll/ntsyscalls.h, dlls/win32u/win32syscalls.h
#   .../winevulkan/make_vulkan (py) include/wine/vulkan.h + 7 more
#   configure, config.h.in          autoreconf, below
#
# make_unicode is NOT run: it rebuilds the NLS tables from Unicode data it
# downloads, which would make the build need the network to produce files this
# tree already has and does not disagree with.
#
# On an upstream-Wine base this whole block is skipped. There the committed
# files are the authority, they are current, and regenerating could only
# introduce a difference.
#
# `fetch_source` cleans untracked files, so all of this reruns per build.
if [ -n "$VALVE_BASE" ]; then
  log "regenerating Wine's generated sources ($WINE_REF ships them stale or absent)"

  # The server protocol first: ntdll and wineserver both compile against it.
  ( cd "$SRC" && ./tools/make_requests ) || die "make_requests failed"

  # Syscall dispatch tables, included by signal_arm64.c/signal_arm64ec.c.
  ( cd "$SRC" && ./tools/make_specfiles ) || die "make_specfiles failed"

  # winevulkan. `-x`/`-X` point at the XML committed beside the generator;
  # without them it fetches vk.xml from the Khronos registry into
  # $HOME/.cache/wine, which would make the build require the network and
  # silently track whatever upstream publishes. The tree's own XML is the
  # version Valve's sources were written against.
  ( cd "$SRC/dlls/winevulkan" && python3 ./make_vulkan -x vk.xml -X video.xml )     || die "make_vulkan failed"

  for f in include/wine/server_protocol.h dlls/ntdll/ntsyscalls.h            dlls/win32u/win32syscalls.h include/wine/vulkan.h; do
    [ -f "$SRC/$f" ] || die "generators ran but $f is still missing"
  done
  ok "generated sources rebuilt"
fi

BUILD="$WORK_DIR/wine-android"
TOOLS="$WORK_DIR/wine-tools"
STAGE="$WORK_DIR/stage-$COMPONENT"
SYSROOT="$WORK_DIR/androidsysroot"
# --prefix=/usr + DESTDIR=$STAGE puts the tree at $STAGE/usr, which is the payload.
PAYLOAD="$STAGE/usr"

# CPU tuning, and the distinction the previous comment here collapsed.
#
# It said "-mcpu=oryon-1 has nowhere safe to live" because CFLAGS reaches only
# the unix side and CROSSCFLAGS all three PE architectures at once. The second
# half is right and the flag stays out of CROSSCFLAGS for exactly that reason:
# --enable-archs builds arm64ec, aarch64 *and* i386 PE code with one CROSSCFLAGS,
# and -mcpu=oryon-1 is not a thing you can say to an i386 compiler.
#
# The first half was the error. CFLAGS reaching only the unix side is precisely
# what makes it *safe*: that side is one target, aarch64-linux-android, built by
# $NDK_CC for this phone and nothing else. So the flag has a home, and it is
# this one. It tunes ntdll.so, win32u.so and winex11.drv.so.
#
# Rank the win honestly, because docs/OPTIMIZATION.md does: on the DXVK present
# path the unix side is only win32u's thin Vulkan thunk, and the heavy native
# code is Turnip, which already gets the flag from build/turnip.sh. Where this
# earns its place is winex11.drv.so's MIT-SHM blitter — the GDI path — and
# ntdll's syscall dispatch, which every guest thread pays for.
resolve_cpu_flags "$NDK_CC"

if [ ! -x "$SRC/configure" ]; then
  info "no configure script in the tree; running autoreconf"
  ( cd "$SRC" && autoreconf -f -i ) || die "autoreconf failed; install autoconf/automake"
fi

# --- X11, FreeType, GStreamer and FFmpeg for Android --------------------------
# Before the tools pass, so a broken sysroot fails in seconds rather than after
# Wine's host tools have built. No-op when the sysroot matches the pins.
#
# gst-sysroot.sh runs x11-sysroot.sh itself — they share one directory and the
# X11 script wipes it when its pins move — so calling the second one is calling
# both, in the only order that is safe.
"$COMMON_SH_DIR/gst-sysroot.sh" \
  || die "gst-sysroot.sh failed; Wine cannot be built with X11 or GStreamer support without it"

# Exactly the modules Wine's configure.ac queries, restated here so a missing
# one is named in seconds instead of turning into a silent
# `--without-gstreamer` an hour later. configure asks for the five GStreamer
# names in ONE pkg-config invocation (WINE_PACKAGE_FLAGS, configure.ac:1731):
# if any single one fails, GSTREAMER_CFLAGS comes back empty, the whole
# AC_CHECK_HEADER block is skipped, and winegstreamer is dropped with a notice
# rather than an error.
for pc in x11 xext freetype2 \
          gstreamer-1.0 gstreamer-video-1.0 gstreamer-audio-1.0 \
          gstreamer-tag-1.0 gstreamer-gl-1.0 \
          libavutil libavformat libavcodec; do
  [ -f "$SYSROOT/usr/lib/pkgconfig/$pc.pc" ] \
    || die "the sysroot scripts reported success but left no $pc.pc in $SYSROOT/usr/lib/pkgconfig"
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

  # --- The media stack, and both halves of it are required ---------------------
  #
  # **Wine implements no codec and no demuxer.** mfplat, mfreadwrite, mf,
  # mfsrcsnk, mfmediaengine and the DirectShow parsers are PE modules that do
  # format negotiation; every byte of demuxing and decoding happens on the unix
  # side in winedmo.so (FFmpeg) and winegstreamer.so (GStreamer). Built without
  # either, ALL of those PE DLLs still install — so `ls lib/wine/aarch64-windows`
  # shows a complete Media Foundation and every media open fails anyway.
  #
  # The failure is precise and was measured with Resident Evil Requiem:
  #
  #   Failed to create SourceReader with registered byte stream handler.
  #   error(c00d36c4)                       MF_E_UNSUPPORTED_BYTESTREAM_TYPE
  #   ole:com_get_class_object class {317df618-5e5a-468a-9f15-d827a9a08162}
  #                                                             not registered
  #
  # and the mechanism is one function, mfsrcsnk/media_source.c:2094 —
  #
  #   if ((status = winedmo_demuxer_check("video/mp4")) || use_gst_byte_stream_handler())
  #       return CoCreateInstance(&CLSID_GStreamerByteStreamHandler, ...);
  #
  # winedmo's check fails because its unix half was compiled with no FFmpeg, so
  # it falls back to the GStreamer handler, which is not registered because
  # winegstreamer was not built either. Two missing dependencies, one dialog.
  #
  # **Only the GStreamer half is built, and the reason is a version wall, not a
  # preference.** This was tried with `--with-ffmpeg` against the FFmpeg 8.0.3
  # in the sysroot and the build dies an hour in, in Valve's own code:
  #
  #   dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c:39:
  #     error: no member named 'internal' in 'struct AVBSFContext'
  #   :80:  error: no member named 'channels' in 'struct AVCodecParameters'
  #   :152: error: field designator 'filter' does not refer to any field in
  #         type 'const AVBitStreamFilter'
  #
  # That file hand-rolls an AVBitStreamFilter and reaches into FFmpeg's private
  # state, and every one of those three fields was removed from the public
  # headers years ago: `AVBSFContext.internal` after 4.4, the AVBitStreamFilter
  # function pointers in 5.1 (moved to the private FFBitStreamFilter), and
  # `AVCodecParameters.channels` in 7.0. It is not a portability bug — it is
  # written against the FFmpeg Proton ships, and Proton's `ffmpeg` submodule on
  # this very branch is a77521cd, which is **FFmpeg 4.3.3, from October 2021**.
  #
  # So the two consumers want incompatible FFmpegs. gst-libav 1.28 is built
  # against the modern one and is where H.264 and AAC actually get decoded;
  # winedmo wants 4.3, which no current GStreamer supports. One sysroot cannot
  # hold both under one soname, and the choice between them is not close:
  # winedmo only demuxes, and mfsrcsnk already has a complete, older, better
  # tested path for exactly this case.
  #
  # That path is the fallback in media_source.c quoted above. With FFmpeg
  # absent, `winedmo_demuxer_check` returns a failure status immediately and
  # every byte stream plugin factory routes to
  # CLSID_GStreamerByteStreamHandler — which is now registered and now has a
  # real GStreamer behind it, which it did not before. The reported symptom is
  # fixed by that branch being taken successfully rather than by never reaching
  # it.
  #
  # Making winedmo work would mean porting that file to modern FFmpeg
  # internals: a patch that mirrors FFBSFContext and FFBitStreamFilter by
  # layout, i.e. one that silently corrupts memory if a future FFmpeg reorders
  # a private struct. Not worth it for a second demuxer.
  --without-ffmpeg

  # `--with-`, not bare: WINE_NOTICE_WITH turns an explicit --with-foo whose
  # probe failed into AC_MSG_ERROR, and leaves a plain notice otherwise. Given
  # docs/DEBUGGING.md's opening — a build that exits 0 with the change absent —
  # a configure that stops is exactly what is wanted here.
  --with-gstreamer

  # Host libraries we do not ship. On `auto` each probes the build machine, and
  # the ones that "succeed" are the danger: a found host .so becomes a NEEDED
  # entry in an aarch64 binary.
  --without-alsa
  --without-capi
  --without-coreaudio
  --without-cups
  --without-dbus
  --without-gphoto
  --without-gssapi
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
  #
  # CFLAGS carries the chip flag and CROSSCFLAGS deliberately does not — see the
  # comment at the top of this file. CROSSCFLAGS is one string for arm64ec,
  # aarch64 and i386 PE code; CFLAGS is one target, this phone's.
  CFLAGS="-O2 ${VESSEL_CPU_FLAGS:-} -I$SYSROOT/usr/include"
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
#
# **The sysroot stamp is in the id, and it has to be.** configure's answers are
# a function of three things, not two: the arguments, the Wine source, and what
# is sitting in $SYSROOT when it runs. Every library check above — X11, FreeType,
# GnuTLS, gmp — reads that directory, and its results are frozen into
# include/config.h. Change a package in build/x11-sysroot.sh and, without this,
# the arguments and the source SHA are both unchanged, so the stale tree is
# reused and the build reports success with none of the new answers in it.
#
# That is not hypothetical: it is exactly how a rebuilt sysroot carrying a shared
# libgmp.so would have produced a Wine that still had `/* #undef SONAME_LIBGMP */`
# in config.h and still logged "Compiled without DH support." — a build that
# exits 0 with the change absent, the failure mode docs/DEBUGGING.md opens with.
#
# The stamp file is the sha256 of the whole PACKAGE list, written by
# x11-sysroot.sh, so any pin or configure-flag change to any package moves it.
#
# **BOTH sysroot stamps, because there are two scripts filling one directory.**
# gst-sysroot.sh writes its own, and it is folded in for exactly the reason
# above: adding GStreamer to the sysroot changes no configure argument and no
# Wine commit, so without this line the tree configured yesterday — the one
# whose config.h says `/* #undef HAVE_GSTREAMER */` — is reused, make has
# nothing to rebuild, and the run ends with `ok dist/wine-...wcp` and no
# winegstreamer.so in it.
SYSROOT_STAMP_ID="$(cat "$SYSROOT/.vessel-x11-sysroot" 2>/dev/null || echo none)"
GST_STAMP_ID="$(cat "$SYSROOT/.vessel-gst-sysroot" 2>/dev/null || echo none)"
CONF_STAMP="$BUILD/.vessel-configure"
CONF_ID="$(printf '%s\n' "${CONFIGURE_ARGS[@]}" "$SOURCE_SHA" "$SYSROOT_STAMP_ID" "$GST_STAMP_ID" | sha256sum | cut -d' ' -f1)"

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

# The same shape for the media stack, and it needs its own check for a reason
# --with-gstreamer does not cover. WINE_NOTICE_WITH turns a failed *pkg-config*
# probe into an error, but configure.ac:1740 has a second path that does not go
# through it:
#
#   ac_glib2_broken=yes
#   enable_winegstreamer=${enable_winegstreamer:-no}
#   WINE_NOTICE([glib-2.0 pkgconfig configuration is for the wrong architecture])
#
# That fires when gst/gst.h is found but its gint64 is not 64-bit — i.e. when a
# HOST GLib answered the query — and it disables the module with a notice, not
# an error. A sysroot leak would land there, so DISABLED_SUBDIRS is checked
# directly rather than trusted.
if grep -q 'dlls/winegstreamer' <<< "$DISABLED_LINE"; then
  die "configure disabled dlls/winegstreamer despite --with-gstreamer.
     Without it no byte stream handler is registered, MFCreateSourceReader*
     returns MF_E_UNSUPPORTED_BYTESTREAM_TYPE (0xC00D36C4) for every file, and
     the PE half of Media Foundation installs anyway so the tree looks fine.
     Search $BUILD/config.log for 'gstreamer-1.0 gstreamer-video-1.0' — the
     line after it prints the flags pkg-config returned — and for
     'whether gint64 defined by gst/gst.h is indeed 64-bit'."
fi

# The inverse of the usual check, and it is here because the sysroot now
# CONTAINS an FFmpeg that Wine must not find. `--without-ffmpeg` is in the
# argument list above; if it is ever dropped, the probe succeeds, HAVE_FFMPEG
# is defined, and the build dies an hour later inside
# dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c against private FFmpeg
# structures that stopped existing after 4.4. Failing in seconds, here, with
# the reason attached, is worth four lines.
if grep -q '^#define HAVE_FFMPEG 1' "$BUILD/include/config.h"; then
  die "configure defined HAVE_FFMPEG. This tree cannot build winedmo against a
     modern FFmpeg — see the --without-ffmpeg comment in this script — and the
     failure is an hour away in pcm_byte_order_reverse_bsf.c. Restore
     --without-ffmpeg, or pin FFmpeg back to the 4.3.3 Proton bundles and give
     gst-libav an FFmpeg of its own."
fi

# And the positive form of the winegstreamer check, from the same file. The
# DISABLED_SUBDIRS test above catches configure switching the module off;
# this catches the case where it is on but compiled without the flags, which
# would fail later and much less clearly.
grep -q '^GSTREAMER_LIBS *=.*-lgstreamer-1\.0' "$BUILD/Makefile" \
  || die "the generated Makefile has no GSTREAMER_LIBS naming -lgstreamer-1.0.
     Search $BUILD/config.log for the line beginning
     'gstreamer-1.0 gstreamer-video-1.0 gstreamer-audio-1.0 ... libs:'."

info "configure: winegstreamer enabled, FFmpeg deliberately not used by Wine"

log "building $COMPONENT — expect an hour or more"
make -C "$BUILD" -j"$(build_jobs 1)" || die "wine build failed"

# install-lib drops headers, import libraries and man pages — most of the size,
# none of it useful on the device.
log "installing into staging prefix"
make -C "$BUILD" install-lib DESTDIR="$STAGE" || die "make install-lib failed"

[ -d "$PAYLOAD" ] || die "make install-lib produced nothing under $PAYLOAD"

# --- Ship Wine's own addons, Mono and Gecko -------------------------------------
# `make install-lib` does not include them, and a package without them is a
# container that stops on a dialog. `dlls/appwiz.cpl/addons.c:765` searches
# `$WINEDATADIR/<subdir>/` and `$INSTALL_DATADIR/wine/<subdir>/` before it offers
# to download; configure ran with --prefix=/usr, so INSTALL_DATADIR is
# /usr/share and the second path is exactly what lands here. Present, wineboot
# installs them silently and offline. Absent, the user is asked, and then 233 MB
# comes over the network per container — measured on the device.
#
# addons.c refuses any file whose SHA-256 is not the one it was compiled with, so
# these are verified here rather than trusted: a mismatch means the pins have
# drifted from the Wine base and is worth failing the build for, not shipping a
# file Wine will silently reject on the phone.
#
# Both Gecko architectures, because appwiz.cpl is built for i386 as well as
# arm64ec and each copy asks for its own (addons.c:47-52). Mono ships a single
# x86 .msi that serves every architecture (addons.c:60-61).
fetch_addon() {
  # <url> <destination directory> <expected sha256>
  local url="$1" dir="$2" want="$3" name got
  name="$(basename "$url")"
  mkdir -p "$dir"
  if [ ! -f "$dir/$name" ]; then
    info "wine addons: fetching $name"
    curl -fSL --retry 3 -o "$dir/$name.part" "$url" \
      || die "could not download $url"
    mv "$dir/$name.part" "$dir/$name"
  fi
  got="$(sha256sum "$dir/$name" | cut -d' ' -f1)"
  [ "$got" = "$want" ] \
    || die "$name sha256 is $got, addons.c expects $want — update native/pins.env to match the Wine base"
}

# **Mono only. Gecko is deliberately not shipped.** Measured: mono is 85.5 MB and
# the two Gecko builds are 55.2 + 53.9 MB, and none of it compresses — xz -9 took
# the mono .msi from 85,504,000 to 83,246,320 bytes, 2.6%, because an .msi is a
# CAB and already compressed. So Gecko is 109 MB of package for the embedded
# MSHTML browser control, which games do not use: engines that want web content
# bundle CEF, and Resident Evil Requiem asked for neither addon — the 233 MB that
# appeared in a prefix here was Wine offering them at creation, not a program
# requiring them. An app that does want Gecko gets the same download prompt it
# would have got anyway.
#
# Mono stays because .NET is what launchers, patchers and mod tools are written
# in, and a missing one stops an install behind a dialog. Note Unity games ship
# their own Mono and never consult this one.
# Neither is staged today. The helper and the pins stay because the decision is
# about *packaging*, not about whether the addons are wanted — see native/pins.env
# for the sizes and the reasoning, and add two lines here to bring either back:
#
#   WINE_ADDON_CACHE="$WORK_DIR/wine-addons"
#   fetch_addon "https://dl.winehq.org/wine/wine-mono/$WINE_MONO_VERSION/wine-mono-$WINE_MONO_VERSION-x86.msi" \
#     "$WINE_ADDON_CACHE/mono" "$WINE_MONO_SHA256"
#   mkdir -p "$PAYLOAD/share/wine/mono"
#   cp "$WINE_ADDON_CACHE/mono/"*.msi "$PAYLOAD/share/wine/mono/"
#
# Measured, so the next person does not have to: bundling both took the package
# from 66.3 to 249.6 MiB, mono alone to 146.1 MiB. The right home for them is a
# component of their own — the store is already keyed by type and version code,
# so a `WineMono` component keeps the base small, makes the addon opt-in per
# container, and stops a Mono update forcing a full Wine re-download. That is the
# same mechanism the VC++ runtimes and a real .NET runtime would need, since Wine
# Mono covers .NET Framework only: no WPF, and nothing for .NET 5 and later.

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

# --- Ship the GStreamer plugins ---------------------------------------------------
# The loop above copies $SYSROOT/usr/lib/*.so, which is every library — GLib,
# the GStreamer core libraries, FFmpeg — but NOT the plugins, which live one
# directory down in lib/gstreamer-1.0 and are the entire point of shipping any
# of it. libgstreamer-1.0.so with no plugin directory is a registry with zero
# features: gst_init() succeeds, decodebin cannot be created, and every media
# open fails exactly as it did before the build.
#
# **The directory has to be named at runtime and nothing does it by default.**
# GStreamer compiles its plugin path from --libdir, so libgstreamer-1.0.so
# looks in /usr/lib/gstreamer-1.0 — an absolute path that does not exist on
# Android. app/src/main/java/app/vessel/core/WineLaunch.kt sets
# GST_PLUGIN_SYSTEM_PATH to this directory for that reason; the two have to
# agree, and this is the half that decides the layout.
log "adding the GStreamer plugins"
shopt -s nullglob
gst_plugins=("$SYSROOT/usr/lib/gstreamer-1.0"/*.so)
shopt -u nullglob
[ "${#gst_plugins[@]}" -gt 0 ] \
  || die "no plugins in $SYSROOT/usr/lib/gstreamer-1.0 — run ./build/gst-sysroot.sh first"
install -d "$PAYLOAD/lib/gstreamer-1.0"
for plug in "${gst_plugins[@]}"; do
  install -m 0644 "$plug" "$PAYLOAD/lib/gstreamer-1.0/"
  "$NDK_BIN/llvm-strip" --strip-unneeded "$PAYLOAD/lib/gstreamer-1.0/$(basename "$plug")" 2>/dev/null || true
done
ok "$(( ${#gst_plugins[@]} )) GStreamer plugins"

# The check that would have caught the above. Every NEEDED entry of every ELF we
# ship must resolve inside the package or be one Android already provides —
# anything else is a library that exists only on the build machine.
BIONIC_PROVIDED="libc.so libm.so libdl.so libz.so liblog.so libandroid.so
libEGL.so libGLESv2.so libGLESv3.so libvulkan.so libnativewindow.so
libsync.so libcamera2ndk.so libaaudio.so ld-android.so"

# **The line breaks above are cosmetic and were very nearly a defect.** The test
# below is `case " $BIONIC_PROVIDED " in *" $need "*`, which requires a SPACE on
# both sides of the name — and a token sitting at the start or the end of a line
# has a NEWLINE on one side instead. That silently excluded four entries that
# are in the list and look present: libandroid.so, libEGL.so, libnativewindow.so
# and libsync.so.
#
# It went unnoticed because nothing shipped had linked any of them. GStreamer's
# libgstgl-1.0.so does — libEGL.so is a NEEDED entry of the EGL backend — and
# the packaging step then refused to build over a library Android has always
# shipped, naming it as one "the package depends on and does not ship". A list
# that reads correctly and does not match is exactly the confidently-wrong
# instrument docs/DEBUGGING.md is about, so the layout is collapsed away here
# rather than the list being reflowed onto one unreadable line.
BIONIC_PROVIDED="$(tr '\n' ' ' <<<"$BIONIC_PROVIDED")"

missing=""
for elf in "$PAYLOAD"/bin/* "$PAYLOAD"/lib/*.so "$PAYLOAD"/lib/gstreamer-1.0/*.so \
           "$PAYLOAD"/lib/wine/aarch64-unix/*.so; do
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
#
# winegstreamer.so and winedmo.so are in this list for the reason the whole
# media block above exists: their PE halves install regardless, so the
# aarch64-windows tree is identical whether or not the unix side was built and
# the only place the difference shows is here. Absence is a FAILURE however the
# build exited.
for elf in bin/wineserver bin/wine lib/wine/aarch64-unix/ntdll.so \
           lib/wine/aarch64-unix/win32u.so lib/wine/aarch64-unix/winex11.so \
           lib/wine/aarch64-unix/wineoss.so lib/wine/aarch64-unix/winegstreamer.so \
           lib/wine/aarch64-unix/winedmo.so; do
  path="$PAYLOAD/$elf"
  [ -f "$path" ] || die "missing unix binary $elf (expected $path).
     Without winex11.drv.so in particular, Wine has no way to put a window on
     screen; check the 'checking for X' lines in $BUILD/config.log.
     Without winegstreamer.so, no byte stream handler is registered and every
     MFCreateSourceReader* call returns MF_E_UNSUPPORTED_BYTESTREAM_TYPE."
  file "$path" | grep -q 'ARM aarch64' \
    || die "$elf is not an aarch64 ELF: $(file -b "$path")"
done

# And that winegstreamer really did link the GStreamer it is supposed to use,
# rather than compiling to a module that loads and can do nothing. A NEEDED
# entry is the only evidence of that which survives into the shipped file.
wg_dyn="$("$NDK_BIN/llvm-readelf" -d "$PAYLOAD/lib/wine/aarch64-unix/winegstreamer.so" 2>/dev/null || true)"
for need in libgstreamer-1.0.so libgstvideo-1.0.so libgstaudio-1.0.so \
            libgsttag-1.0.so libgstgl-1.0.so libglib-2.0.so; do
  grep -q "NEEDED.*\[$need\]" <<<"$wg_dyn" \
    || die "winegstreamer.so does not record $need as NEEDED.
     It was built, but against something other than the sysroot's GStreamer.
     Full NEEDED list:
$(grep NEEDED <<<"$wg_dyn")"
done
ok "winegstreamer.so links the sysroot GStreamer"

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
  --version-code "$VERSION_CODE" \
  ${VERSION_CODE:+--version-code "$VERSION_CODE"} \
  --payload "$PAYLOAD" \
  --provenance "$PAYLOAD/provenance.json" \
  --description "Wine $WINE_VERSION with arm64ec/aarch64/i386 PE modules and a bionic aarch64 unix side, from $WINE_REF @ ${SOURCE_SHA:0:12}" \
  --out "$DIST_DIR/wine-$VERSION-$TARGET_NAME.wcp"

ok "dist/wine-$VERSION-$TARGET_NAME.wcp"
