#!/system/bin/sh
# The container side of tools/fex/run-offline-compiler.sh. Runs on the phone.
#
#   sh offline-compiler.container.sh <container-id> [args for the compiler...]
#
# A separate file rather than a string passed to `sh -c`, because the one thing
# this has to get right is a DOS path — `C:\vessel\fexcache\` — and that string
# cannot survive `adb shell "run-as pkg sh -c '…'"`. Three levels of quoting,
# two of which eat backslashes, and the first attempt died on `no closing
# quote`. A file has no quoting levels.

set -u

CONTAINER="$1"
shift

FEX_DIR=files/components/FEXCore/2608
WINE_DIR=files/components/Wine/1114
BASE=files/containers/$CONTAINER
PREFIX=$BASE/prefix
HOST_CACHE=$BASE/caches/fex/probe

# The link that makes the DOS path resolve. Recreated every run: a container
# that has not started since the path change has none, and the point of this
# script is to work against whatever state the phone is in.
mkdir -p "$HOST_CACHE" "$PREFIX/drive_c/vessel"
rm -rf "$PREFIX/drive_c/vessel/fexcache"
ln -s "$PWD/$HOST_CACHE" "$PREFIX/drive_c/vessel/fexcache"

export WINEPREFIX="$PWD/$PREFIX"
export XDG_RUNTIME_DIR="$PWD/$BASE/tmp"
export TMPDIR="$PWD/$BASE/tmp"
export HOME="$PWD/$BASE"
export WINEDLLPATH="$PWD/$WINE_DIR/lib/wine"
export WINENLSDIR="$PWD/$WINE_DIR/share/wine/nls"
export WINEDEBUG=-all,err+all,+winediag
export LD_LIBRARY_PATH="$PWD/$WINE_DIR/lib:$PWD/$WINE_DIR/lib/wine/aarch64-unix"
export FEX_APP_CACHE_LOCATION="C:\\vessel\\fexcache\\"

# printf, not echo. Android's sh is mksh and its echo expands backslash escapes,
# so `C:\vessel\fexcache\` printed as `C:esselexcache\` — \v and \f eaten — and
# for a minute it looked as though the variable itself had been mangled.
printf 'FEX_APP_CACHE_LOCATION=%s\n' "$FEX_APP_CACHE_LOCATION"

# `--pe <program>` runs an x86 guest binary instead of the compiler, with code
# caching on. There has to be a codemap before there is anything to compile, and
# the only thing that writes one is FEX translating a non-native PE — so a fresh
# cache key cannot be exercised end to end without first making one. Any x86_64
# PE will do; the probes already in drive_c are the cheapest to hand.
if [ "${1:-}" = "--pe" ]; then
  shift
  PROGRAM="$1"
  shift
  export FEX_ENABLECODECACHINGWIP=1
  ( timeout 180 /system/bin/linker64 "$PWD/$WINE_DIR/bin/wine" "$PROGRAM" "$@" \
      > "$BASE/tmp/fexc.log" 2>&1 < /dev/null )
  echo "EXIT=$?"
  tail -20 "$BASE/tmp/fexc.log"
  echo "--- cache tree ---"
  ls -R "$HOST_CACHE" 2>/dev/null | head -30
  exit 0
fi

# Backgrounded and redirected: a Wine process that inherits the adb pipe hangs
# adb shell forever, which tools/device-bench.sh documents at length.
( timeout 180 /system/bin/linker64 "$PWD/$WINE_DIR/bin/wine" \
    "$PWD/$FEX_DIR/FEXOfflineCompiler64.exe" "$@" \
    > "$BASE/tmp/fexc.log" 2>&1 < /dev/null )
echo "EXIT=$?"
cat "$BASE/tmp/fexc.log"

echo "--- cache tree ---"
ls -R "$HOST_CACHE" 2>/dev/null | head -30
