#!/usr/bin/env bash
# Play a tone inside the running container. Two seconds, no UI, no game.
#
#   ./tools/audio/run-guest-tone.sh            # build, install, run
#   ./tools/audio/run-guest-tone.sh --reuse    # skip the build
#
# Every earlier way of testing guest audio needed a person: launch winecfg,
# five synthetic Ctrl+Tabs to reach the Audio tab, a Space on Test Sound — about
# a minute a run and fragile at every step — or launch Metro and wait three
# minutes. This runs `tonewin.exe` in the container the app already has open and
# prints the result.
#
# It joins a live session rather than making its own, because the thing being
# measured is the audio path of a *session*: the same wineserver, the same
# winedevice, the same environment. A separate prefix would be a different
# question with the same name.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
STAGE="$REPO/out/audio"
mkdir -p "$STAGE"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

BUILD=1
[ "${1:-}" = "--reuse" ] && BUILD=0

if [ "$BUILD" = 1 ]; then
  say "building tonewin.exe (x86_64, so FEX is in the path too)"
  docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" vessel-build bash -lc '
    set -e
    : "${LLVM_MINGW_HOME:?the Docker image sets this}"
    "$LLVM_MINGW_HOME/bin/x86_64-w64-mingw32-clang" -O2 -Wall \
      -o /out/tonewin.exe /src/tools/audio/tonewin.c -lwinmm
  ' || die "build failed"
fi

CONTAINER="$(in_app 'ls files/containers/ 2>/dev/null | head -1')"
[ -n "$CONTAINER" ] || die "no provisioned container"

# drive_c, so the guest path is a plain C:\ one and no drive mapping is involved.
say "installing into $CONTAINER"
adb push "$STAGE_MOUNT/tonewin.exe" /data/local/tmp/tonewin.exe >/dev/null
in_app "cat /data/local/tmp/tonewin.exe > files/containers/$CONTAINER/prefix/drive_c/tonewin.exe"
adb shell "rm -f /data/local/tmp/tonewin.exe" >/dev/null 2>&1 || true

WINE_DIR="files/components/Wine/1114"
in_app "test -x $WINE_DIR/bin/wine" >/dev/null 2>&1 || die "no Wine component at $WINE_DIR"

# The session's own environment, minus the parts that only a session needs. The
# audio channel is on regardless of the container's diagnostics, because a run
# of this script that says nothing is a wasted run.
ENV="cd \$PWD && export \
WINEPREFIX=\$PWD/files/containers/$CONTAINER/prefix \
XDG_RUNTIME_DIR=\$PWD/files/containers/$CONTAINER/tmp \
TMPDIR=\$PWD/files/containers/$CONTAINER/tmp \
HOME=\$PWD/files/containers/$CONTAINER \
WINEDLLPATH=\$PWD/$WINE_DIR/lib/wine \
WINENLSDIR=\$PWD/$WINE_DIR/share/wine/nls \
WINEDEBUG=-all,err+all,+winediag,+oss \
DISPLAY=:0 \
LD_LIBRARY_PATH=\$PWD/$WINE_DIR/lib:\$PWD/$WINE_DIR/lib/wine/aarch64-unix"

say "playing — listen"
# Backgrounded and redirected: a Wine process that inherits the adb pipe hangs
# adb shell forever, which tools/device-bench.sh documents at length.
in_app "$ENV && ( timeout 60 /system/bin/linker64 \$PWD/$WINE_DIR/bin/wine \
  \$PWD/files/containers/$CONTAINER/prefix/drive_c/tonewin.exe \
  > files/containers/$CONTAINER/tmp/tone.log 2>&1 </dev/null ); \
  cat files/containers/$CONTAINER/tmp/tone.log" | grep -E "VESSEL-GUESTTONE|oss:|err:" || true
