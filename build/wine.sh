#!/usr/bin/env bash
# Build ARM64EC-capable Wine and package it as a .wcp.
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
#   ./build/wine.sh             # -> dist/wine-<ver>-<target>.wcp

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init
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

BUILD="$WORK_DIR/$COMPONENT"
TOOLS="$WORK_DIR/$COMPONENT-tools"
STAGE="$WORK_DIR/stage-$COMPONENT"
# --prefix=/usr + DESTDIR=$STAGE puts the tree at $STAGE/usr, and that
# directory — bin/, lib/, share/ — is exactly what goes into the package.
PAYLOAD="$STAGE/usr"
rm -rf "$BUILD" "$TOOLS" "$STAGE"
mkdir -p "$BUILD" "$STAGE"

# Deliberately no resolve_cpu_flags here. One configure builds three PE
# architectures from one set of CROSSCFLAGS, and -mcpu=oryon-1 is meaningless
# to the i386 pass, so there is nowhere safe to put the tuning. Wine's own code
# is not the hot path in any case — the guest code and DXVK are.

if [ ! -x "$SRC/configure" ]; then
  info "no configure script in the tree; running autoreconf"
  ( cd "$SRC" && autoreconf -f -i ) || die "autoreconf failed; install autoconf/automake"
fi

# --- Pass 1: native build tools ---------------------------------------------
# Wine generates a large part of itself with its own tools (winebuild, wrc,
# widl, wmc). Those tools have to run on the *build* machine, so when the unix
# side is cross-compiled they cannot come from the cross-build — they are built
# first, natively, in a throwaway tree and handed over with --with-wine-tools.
#
# When WINE_HOST is unset we are not cross-compiling the unix side, configure
# builds the tools in-tree, and this pass is skipped entirely.
#
# VERIFY: which arrangement this project actually wants. A Wine loader that has
# to run on the phone is a bionic aarch64 binary and needs
# WINE_HOST=aarch64-linux-android with the NDK on PATH (common.sh's setup_ndk
# provides it); a Wine that runs in the container needs neither. Confirm
# against the app's container layout before the first release build.
CONFIGURE_ARGS=(
  --prefix=/usr
  --enable-archs=arm64ec,aarch64,i386
  --with-mingw=clang
  --disable-tests
  # Wine defaults to -g -O2. The debug info multiplies the package size and is
  # of little use on a phone; -O2 alone is valid for every --enable-archs
  # target, which a chip-specific flag would not be.
  CFLAGS=-O2
  CROSSCFLAGS=-O2
)

if [ -n "${WINE_HOST:-}" ]; then
  log "pass 1/2: native build tools (cross build for $WINE_HOST)"
  mkdir -p "$TOOLS"
  ( cd "$TOOLS" && "$SRC/configure" --enable-win64 --disable-tests --without-x --without-freetype )
  # __tooldeps__ builds only the code generators, not all of Wine.
  make -C "$TOOLS" -j"$(nproc_safe)" __tooldeps__
  [ -x "$TOOLS/tools/winebuild/winebuild" ] \
    || die "native tools build produced no winebuild (looked in $TOOLS/tools/winebuild)"
  CONFIGURE_ARGS+=( --host="$WINE_HOST" --with-wine-tools="$TOOLS" )
  ok "native tools ready"
else
  info "not cross-compiling the unix side; configure will build tools in-tree"
fi

# --- Pass 2: the real build --------------------------------------------------

log "configuring $COMPONENT (arm64ec + aarch64 + i386)"
( cd "$BUILD" && "$SRC/configure" "${CONFIGURE_ARGS[@]}" )

log "building $COMPONENT — expect an hour or more"
make -C "$BUILD" -j"$(nproc_safe)"

# install-lib is the runtime half: no headers, no import libraries, no man
# pages. None of it is useful on the device and it is most of the size.
log "installing into staging prefix"
make -C "$BUILD" install-lib DESTDIR="$STAGE"

[ -d "$PAYLOAD" ] || die "make install-lib produced nothing under $PAYLOAD"

# Each --enable-archs target gets its own PE tree. A missing one means configure
# quietly dropped that architecture — usually because the toolchain could not
# target it — and the package would be broken in a way only noticed on device.
for arch in arm64ec aarch64 i386; do
  [ -d "$PAYLOAD/lib/wine/$arch-windows" ] \
    || die "no PE tree for $arch (expected $PAYLOAD/lib/wine/$arch-windows).
     --enable-archs did not build it; check the configure summary for the
     'Wine will be built with' lines."
done

EC_NTDLL="$PAYLOAD/lib/wine/arm64ec-windows/ntdll.dll"
[ -f "$EC_NTDLL" ] || die "the arm64ec tree has no ntdll.dll ($EC_NTDLL)"
file "$EC_NTDLL" | grep -Eqi 'PE32\+.*(aarch64|arm64)' \
  || die "arm64ec ntdll.dll is not an ARM64 PE: $(file -b "$EC_NTDLL")"

[ -x "$PAYLOAD/bin/wine" ] || die "no wine loader at $PAYLOAD/bin/wine"

write_provenance "$PAYLOAD/provenance.json" "$COMPONENT" "$VERSION"

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Wine \
  --name "Wine $VERSION ARM64EC ($TARGET_NAME)" \
  --version "$VERSION" \
  --payload "$PAYLOAD" \
  --provenance "$PAYLOAD/provenance.json" \
  --description "Wine $VERSION with arm64ec/aarch64/i386 PE modules, from $WINE_REF @ ${SOURCE_SHA:0:12}" \
  --out "$DIST_DIR/wine-$VERSION-$TARGET_NAME.wcp"

ok "dist/wine-$VERSION-$TARGET_NAME.wcp"
