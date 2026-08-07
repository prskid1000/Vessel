#!/usr/bin/env bash
# Does our Wine actually run on the phone?
#
#   ./tools/device-smoke.sh
#
# This is the first test that puts the native stack on real hardware. Everything
# before it only proved the components *build*. Run it before writing any more
# launcher code — if Wine cannot start here, no amount of Kotlin will help.
#
# It deliberately does NOT use the app. It unpacks the Wine package to
# /data/local/tmp and runs it as the shell user, which sidesteps the app's
# SELinux domain entirely. So:
#
#   PASS here  = the build is good; anything that then fails in the app is the
#                app's problem (exec path, environment, permissions).
#   FAIL here  = the build is wrong, and the app was never going to work.
#
# Separating those two is the whole point. /data/local/tmp is executable for the
# shell user; an app's filesDir is not, which is why the app has to exec through
# the system linker instead (see docs/ARCHITECTURE.md).

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE_DIR=/data/local/tmp/vessel
STAGE="${TMPDIR:-/tmp}/vessel-smoke"

say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device. Plug the phone in and enable USB debugging."

WCP="$(ls "$REPO"/dist/wine-*.wcp 2>/dev/null | head -1)"
[ -n "$WCP" ] || die "no Wine package in dist/. Run ./build/wine.sh first."

# --- unpack on the host ------------------------------------------------------
# Android's toybox has no xz, so the package is expanded here and the tree is
# pushed. ~912 MB over USB is about ten seconds; decompressing on the phone
# would be slower and needs a tool that is not there.
say "unpacking $(basename "$WCP")"
rm -rf "$STAGE"; mkdir -p "$STAGE"
if command -v xz >/dev/null; then
  xz -dc "$WCP" | tar -x -C "$STAGE"
else
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO:/src" -v "$STAGE:/out" \
    vessel-build:latest bash -c "xz -dc /src/${WCP#"$REPO/"} | tar -x -C /out"
fi
[ -x "$STAGE/usr/bin/wine" ] || die "no usr/bin/wine in the package"
ok "unpacked $(du -sh "$STAGE/usr" 2>/dev/null | cut -f1)"

# --- push --------------------------------------------------------------------
say "pushing to $DEVICE_DIR (this is the slow part)"
adb shell "rm -rf $DEVICE_DIR; mkdir -p $DEVICE_DIR"
adb push --sync "$STAGE/usr" "$DEVICE_DIR/" >/dev/null
adb shell "chmod -R 755 $DEVICE_DIR/usr/bin $DEVICE_DIR/usr/lib 2>/dev/null; true"
ok "pushed"

# --- the actual questions ----------------------------------------------------
# LD_LIBRARY_PATH matters: the unix-side .so files live in lib/wine/aarch64-unix
# and nothing on the device knows to look there.
ENV_PREFIX="WINEPREFIX=$DEVICE_DIR/prefix \
WINEDEBUG=-all,err+all,warn+module,+winediag,+loaddll \
WINEESYNC=1 \
LD_LIBRARY_PATH=$DEVICE_DIR/usr/lib:$DEVICE_DIR/usr/lib/wine/aarch64-unix"

say "1/3  does the loader start at all?"
if out="$(adb shell "$ENV_PREFIX $DEVICE_DIR/usr/bin/wine --version 2>&1" | tr -d '\r')"; then
  case "$out" in
    wine-*) ok "$out" ;;
    *)      bad "unexpected output:"; printf '%s\n' "$out" | sed 's/^/       /' ;;
  esac
else
  bad "wine --version did not run"; printf '%s\n' "${out:-}" | sed 's/^/       /'
fi

say "2/3  can wineserver start?"
adb shell "$ENV_PREFIX $DEVICE_DIR/usr/bin/wineserver -p 2>&1" | tr -d '\r' | sed 's/^/       /' || true
if adb shell "pgrep -f wineserver >/dev/null && echo yes" | grep -q yes; then
  ok "wineserver is running"
else
  bad "wineserver did not stay up"
fi

# The real test. wineboot builds a prefix from scratch: it registers DLLs,
# writes the registry, and exercises ntdll, the PE loader and the ARM64EC path
# all at once. If this works, the stack works.
say "3/3  can it create a prefix? (wineboot --init — the real test)"
adb shell "$ENV_PREFIX timeout 300 $DEVICE_DIR/usr/bin/wineboot --init 2>&1" \
  | tr -d '\r' | tail -40 | sed 's/^/       /' || true

say "prefix contents"
adb shell "ls $DEVICE_DIR/prefix 2>/dev/null" | tr -d '\r' | sed 's/^/       /'
if adb shell "test -f $DEVICE_DIR/prefix/system.reg && echo yes" | grep -q yes; then
  ok "system.reg exists — a Wine prefix was created on the device"
else
  bad "no system.reg; the prefix was not built"
fi

say "done. Clean up with:  adb shell rm -rf $DEVICE_DIR"
