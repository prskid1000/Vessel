#!/usr/bin/env bash
# Play a tone through AAudio from a bare process running as the app's uid.
#
#   ./tools/audio/run-tone.sh
#
# This exists to split one question in two. Vessel's guest audio is silent even
# though the driver's own trace says the write succeeded and the stream is
# STARTED; AudioFlinger says the track never went active. Either Wine's driver
# is doing something wrong, or a stream opened this way from a process the app
# forked never plays on this device. tools/audio/aaudiotone.c does exactly what
# the driver does and nothing else, so which of those it is stops being a guess.
#
# Run as the app's uid via run-as, not as shell: audio policy is keyed on the
# uid and its package, and a shell-uid result would answer a question nobody
# asked.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
APP_DIR=files/audioprobe
STAGE="$REPO/out/audio"
mkdir -p "$STAGE"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

# Git Bash rewrites Unix-looking absolute paths before adb.exe sees them.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

BUILD=1
ARGS=""
for a in "$@"; do
  case "$a" in
    --reuse) BUILD=0 ;;
    *) ARGS="$ARGS $a" ;;
  esac
done

if [ "$BUILD" = 1 ]; then
  say "building aaudiotone"
  # -landroid for AAudio; the NDK sysroot supplies aaudio/AAudio.h.
  docker run --rm \
    -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
    vessel-build bash -lc '
      set -e
      . /src/build/common.sh >/dev/null 2>&1 || true
      : "${ANDROID_NDK_HOME:?the Docker image sets this}"
      CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang"
      "$CC" -O2 -Wall -Wextra -o /out/aaudiotone /src/tools/audio/aaudiotone.c -laaudio -lm
    ' || die "build failed"

  adb push "$STAGE_MOUNT/aaudiotone" /data/local/tmp/aaudiotone >/dev/null
  in_app "mkdir -p $APP_DIR && cat /data/local/tmp/aaudiotone > $APP_DIR/aaudiotone && chmod 755 $APP_DIR/aaudiotone"
  adb shell "rm -f /data/local/tmp/aaudiotone" >/dev/null 2>&1 || true
fi

say "playing a 440 Hz tone for three seconds — listen"
in_app "cd \$PWD && ./$APP_DIR/aaudiotone$ARGS 2>&1"

say "what AudioFlinger thinks of the app's tracks"
adb shell 'dumpsys media.audio_flinger 2>/dev/null | grep -E "10458|Active tracks" | tail -4' || true
