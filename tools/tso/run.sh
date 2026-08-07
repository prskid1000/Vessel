#!/usr/bin/env bash
# Measure whether FEX's LRCPC2 TSO path is a win or a loss on Oryon.
#
#   ./tools/tso/run.sh
#
# Runs tsobench.c on the phone twice per architecture — once with FEX's default
# host feature detection, once with FEX_HOSTFEATURES=disablelrcpc2 — and prints
# both times. See tsobench.c for why this question is open.
#
# Read the result like this:
#   x86-64 slower with the flag  -> LDAPUR is fine here, leave FEX alone
#   x86-64 faster with the flag  -> LDAPUR is over-ordered on this core, and
#                                   Vessel should be setting the flag
#   ARM64 time moves at all      -> the measurement is noise; the flag cannot
#                                   affect a native binary, so a difference there
#                                   means thermals or scheduling, not ordering
#
# Requires a prefix already built by tools/device-session.sh, which is also where
# the environment and the two-pass wineboot are explained.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
APP_DIR=files/session
STAGE="$REPO/out/tso"
mkdir -p "$STAGE"

REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"

adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
# A daemon inheriting the adb pipe hangs adb shell forever; the subshell keeps
# the caller's `cd` from leaking into the `cat`. Same helper as device-session.sh.
in_app_bg() {
  adb shell "run-as $PKG sh -c '( $1 ) > $2 2>&1 </dev/null; cat $2'" | tr -d '\r'
}

adb get-state >/dev/null 2>&1 || die "no device"
in_app "test -f $APP_DIR/prefix/system.reg" >/dev/null 2>&1 \
  || die "no prefix at $APP_DIR — run ./tools/device-session.sh first"

say "compiling tsobench for x86_64 and aarch64"
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
  -v vessel-work:/work vessel-build:latest bash -c '
    set -euo pipefail
    . /src/build/common.sh >/dev/null
    vessel_init >/dev/null
    setup_mingw >/dev/null
    for t in x86_64 aarch64; do
      "$MINGW_BIN/$t-w64-mingw32-clang" -O2 -o "/out/tsobench-$t.exe" /src/tools/tso/tsobench.c
    done' >/dev/null || die "compile failed"

for t in x86_64 aarch64; do
  adb push "$STAGE_MOUNT/tsobench-$t.exe" "/data/local/tmp/tsobench-$t.exe" >/dev/null
  in_app "cat /data/local/tmp/tsobench-$t.exe > $APP_DIR/tsobench-$t.exe"
done
adb shell "rm -f /data/local/tmp/tsobench-x86_64.exe /data/local/tmp/tsobench-aarch64.exe"

ENV_PREFIX="cd $APP_DIR && \
export WINEPREFIX=\$PWD/prefix \
XDG_RUNTIME_DIR=\$PWD/run \
TMPDIR=\$PWD/run \
HOME=\$PWD \
WINEDLLPATH=\$PWD/lib/wine \
WINENLSDIR=\$PWD/share/wine/nls \
WINEDEBUG=-all \
LD_LIBRARY_PATH=\$PWD/lib:\$PWD/lib/wine/aarch64-unix"
WINE="/system/bin/linker64 \$PWD/bin"

# Three runs a side, because a phone throttles and a single pair of numbers is a
# coin toss. The best of each set is reported: the fastest run is the one least
# disturbed by whatever else the device was doing.
run_set() {
  local arch="$1" flag="$2" label="$3" best="" out
  for attempt in 1 2 3; do
    out="$(in_app_bg "$ENV_PREFIX $flag && timeout 300 $WINE/wine \$PWD/tsobench-$arch.exe" \
      "$APP_DIR/tso.log" || true)"
    local ms
    ms="$(grep -o 'ms=[0-9.]*' <<<"$out" | head -1 | cut -d= -f2)"
    [ -n "$ms" ] || { printf '  %-28s FAILED: %s\n' "$label" "$(tail -1 <<<"$out")"; return; }
    if [ -z "$best" ] || awk "BEGIN{exit !($ms < $best)}"; then best="$ms"; fi
  done
  printf '  %-28s %8s ms\n' "$label" "$best"
}

say "x86-64 (translated by FEX — the case under test)"
run_set x86_64 "" "default (LRCPC2 on)"
run_set x86_64 "FEX_HOSTFEATURES=disablelrcpc2" "disablelrcpc2"

say "ARM64 (native — the control, both numbers should match)"
run_set aarch64 "" "default"
run_set aarch64 "FEX_HOSTFEATURES=disablelrcpc2" "disablelrcpc2"

say "done"
