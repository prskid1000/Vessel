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

case "$WINE_REF" in
  proton*)
    VERSION="proton-$WINE_VERSION"
    VERSION_CODE=$(( WINE_PROTON_EPOCH + $(python3 -c \
      'import sys; sys.path.insert(0, "'"$COMMON_SH_DIR"'"); from package_wcp import version_code; print(version_code(sys.argv[1]))' \
      "$WINE_VERSION") ))
    info "wine version $WINE_VERSION as $VERSION, code $VERSION_CODE (Proton epoch)"
    ;;
  *)
    VERSION="$WINE_VERSION"
    VERSION_CODE=
    info "wine version $VERSION (branch $WINE_REF @ ${SOURCE_SHA:0:12})"
    ;;
esac

# **Proton does not commit Wine's generated sources; upstream Wine does.**
#
# Two generators have to run before configure on a Proton checkout. Upstream
# checks their output in, so on an upstream base both blocks below are skipped
# and nothing changes. Each is guarded on its own output being absent, which is
# also what makes them cheap to leave in place permanently.
#
# The full set of what Proton leaves out was found by listing the known
# generated outputs rather than by hitting them one build at a time:
#
#   dlls/ntdll/ntsyscalls.h     tools/make_specfiles       (perl)
#   dlls/win32u/win32syscalls.h tools/make_specfiles       (perl)
#   include/wine/vulkan.h       dlls/winevulkan/make_vulkan (python)
#   + 7 more winevulkan files   dlls/winevulkan/make_vulkan
#   configure, include/config.h.in                          (autoreconf, below)
#
# server_protocol.h, request.h, trace.c and the NLS tables ARE committed, so
# make_requests and make_unicode are not needed.
#
# ---
#
# **make_specfiles: the syscall tables.**
#
# It walks the spec files and rewrites the syscall dispatch headers that
# `signal_arm64.c`, `signal_arm64ec.c` and `unix/loader.c` include. Absent, the
# build dies inside config.status with
#     signal_arm.c:35: error: ntsyscalls.h: No such file or directory
# for the same reason as the vulkan header: makedep reads the include graph
# while configure is still writing Makefiles.
if [ ! -f "$SRC/dlls/ntdll/ntsyscalls.h" ] && [ -x "$SRC/tools/make_specfiles" ]; then
  log "generating syscall tables (absent from $WINE_REF)"
  ( cd "$SRC" && ./tools/make_specfiles ) || die "make_specfiles failed"
  [ -f "$SRC/dlls/ntdll/ntsyscalls.h" ] \
    || die "make_specfiles ran but did not produce dlls/ntdll/ntsyscalls.h"
  ok "syscall tables generated"
fi

# **winevulkan.**
#
# `dlls/winevulkan/make_vulkan` turns `vk.xml` into eight files — the public
# `include/wine/vulkan.h`, the thunk pairs, the spec and the two ICD manifests.
# Upstream checks the results in, so nothing ever has to run the generator.
# Valve's tree checks in only the inputs, and every one of the eight is absent
# from a fresh checkout.
#
# The failure that finds this names none of it. `tools/makedep` reads the
# `#include` graph while configure is still writing Makefiles, so the whole
# build dies during `config.status` with
#     error: open wine/vulkan.h : No such file or directory
#     config.status: error: could not create Makefile
# which reads like a broken configure rather than a missing generated header.
#
# `-x`/`-X` point the generator at the XML committed beside it. Without them it
# fetches vk.xml from the Khronos registry into $HOME/.cache/wine, which would
# make the build require the network and silently track whatever upstream
# publishes — the tree's own XML is the version Valve's sources were written
# against, and is the only correct input.
#
# Guarded on the header being absent, so an upstream-Wine base skips it: there
# the committed files are the authority and regenerating could only introduce a
# difference. `fetch_source` cleans untracked files, so this reruns per build.
if [ ! -f "$SRC/include/wine/vulkan.h" ] && [ -x "$SRC/dlls/winevulkan/make_vulkan" ]; then
  log "generating winevulkan sources (absent from $WINE_REF)"
  ( cd "$SRC/dlls/winevulkan" && python3 ./make_vulkan -x vk.xml -X video.xml ) \
    || die "make_vulkan failed"
  [ -f "$SRC/include/wine/vulkan.h" ] \
    || die "make_vulkan ran but did not produce include/wine/vulkan.h"
  ok "winevulkan sources generated"
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
  ${VERSION_CODE:+--version-code "$VERSION_CODE"} \
  --payload "$PAYLOAD" \
  --provenance "$PAYLOAD/provenance.json" \
  --description "Wine $WINE_VERSION with arm64ec/aarch64/i386 PE modules and a bionic aarch64 unix side, from $WINE_REF @ ${SOURCE_SHA:0:12}" \
  --out "$DIST_DIR/wine-$VERSION-$TARGET_NAME.wcp"

ok "dist/wine-$VERSION-$TARGET_NAME.wcp"
