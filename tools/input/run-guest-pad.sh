#!/usr/bin/env bash
# Ask the container whether a gamepad exists, three ways. Seconds, no game.
#
#   ./tools/input/run-guest-pad.sh              # build, install, run
#   ./tools/input/run-guest-pad.sh --reuse      # skip the build
#   ./tools/input/run-guest-pad.sh --arch x86_64
#
# The question "is the controller reaching the game" has three separate
# answers inside the guest — XInput, DirectInput and winmm's joystick API — and
# they can disagree. Finding out by launching a game costs three minutes and
# tells you only that one of them failed. `padwin.exe` asks all three and prints
# what each said.
#
# It joins the live session rather than making its own, for the reason
# tools/audio/run-guest-tone.sh does: the thing being measured is a property of
# a *session* — the same wineserver, the same winedevice, the same environment —
# and a separate prefix would be a different question with the same name.
#
# The default architecture is aarch64, because that is what an ARM64EC guest
# loads and it is the shortest path. Pass --arch x86_64 to put FEX in the path
# too, which is what a real x86 game does.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
STAGE="$REPO/out/input"
mkdir -p "$STAGE"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

BUILD=1
ARCH=aarch64
SECONDS_ARG=3
while [ $# -gt 0 ]; do
  case "$1" in
    --reuse) BUILD=0 ;;
    --arch) ARCH="$2"; shift ;;
    --seconds) SECONDS_ARG="$2"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

if [ "$BUILD" = 1 ]; then
  say "building padwin.exe ($ARCH)"
  docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" vessel-build bash -lc "
    set -e
    . /src/build/common.sh >/dev/null; vessel_init >/dev/null; setup_mingw >/dev/null
    \"\$MINGW_BIN/$ARCH-w64-mingw32-clang\" -O2 -Wall -o /out/padwin.exe /src/tools/input/padwin.c
  " || die "build failed"
fi

CONTAINER="$(in_app 'ls files/containers/ 2>/dev/null | head -1')"
[ -n "$CONTAINER" ] || die "no provisioned container"

# drive_c, so the guest path is a plain C:\ one and no drive mapping is involved.
say "installing into $CONTAINER"
adb push "$STAGE_MOUNT/padwin.exe" /data/local/tmp/padwin.exe >/dev/null
in_app "cat /data/local/tmp/padwin.exe > files/containers/$CONTAINER/prefix/drive_c/padwin.exe"
adb shell "rm -f /data/local/tmp/padwin.exe" >/dev/null 2>&1 || true

WINE_DIR="$(in_app 'ls -d files/components/Wine/* 2>/dev/null | head -1')"
[ -n "$WINE_DIR" ] || die "no Wine component installed"
in_app "test -x $WINE_DIR/bin/wine" >/dev/null 2>&1 || die "no wine binary at $WINE_DIR"

# The session's own environment, minus the parts only a session needs. The
# input channels are turned up regardless of the container's diagnostics,
# because a run of this script that says nothing is a wasted run.
ENV="cd \$PWD && export \
WINEPREFIX=\$PWD/files/containers/$CONTAINER/prefix \
XDG_RUNTIME_DIR=\$PWD/files/containers/$CONTAINER/tmp \
TMPDIR=\$PWD/files/containers/$CONTAINER/tmp \
HOME=\$PWD/files/containers/$CONTAINER \
WINEDLLPATH=\$PWD/$WINE_DIR/lib/wine \
WINENLSDIR=\$PWD/$WINE_DIR/share/wine/nls \
WINEDEBUG=-all,err+all,+winediag,+xinput,+dinput,+hid,+plugplay \
DISPLAY=:0 \
LD_LIBRARY_PATH=\$PWD/$WINE_DIR/lib:\$PWD/$WINE_DIR/lib/wine/aarch64-unix"

say "asking the guest"
# Backgrounded and redirected: a Wine process that inherits the adb pipe hangs
# adb shell forever, which tools/device-bench.sh documents at length.
in_app "$ENV && ( timeout 90 /system/bin/linker64 \$PWD/$WINE_DIR/bin/wine \
  \$PWD/files/containers/$CONTAINER/prefix/drive_c/padwin.exe $SECONDS_ARG \
  > files/containers/$CONTAINER/tmp/pad.log 2>&1 </dev/null ); \
  cat files/containers/$CONTAINER/tmp/pad.log" \
  | grep -E "VESSEL-GUESTPAD|xinput:|dinput:|hid:|plugplay:|winebus|err:" || true
