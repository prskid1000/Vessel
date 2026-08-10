#!/usr/bin/env bash
# Measure FEX's two TSO knobs on Oryon: the LRCPC2 host feature, and the
# half-barrier backpatch.
#
#   ./tools/tso/run.sh
#
# Runs tsobench.c on the phone several times per architecture and prints the
# best of each set. See tsobench.c for why these questions are open.
#
# Read the LRCPC2 result like this:
#   x86-64 slower with the flag  -> LDAPUR is fine here, leave FEX alone
#   x86-64 faster with the flag  -> LDAPUR is over-ordered on this core, and
#                                   Vessel should be setting the flag
#   ARM64 time moves at all      -> the measurement is noise; the flag cannot
#                                   affect a native binary, so a difference there
#                                   means thermals or scheduling, not ordering
#
# **The half-barrier pair is deliberately `=0` against the default, not `=1`.**
# FEX defaults `HalfBarrierTSOEnabled` to *true*
# (FEXCore/Source/Interface/Config/Config.json.in), so Vessel's
# `FEX_HALFBARRIERTSOENABLED=1` sets what FEX already does and a `1`-vs-default
# pair could not move by construction. Turning it off is the only comparison
# that has two sides. Read it the other way round from the above: if `=0` is
# slower, the backpatch is earning its keep and the default is right — which
# also means Vessel's explicit `1` is redundant rather than wrong.
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
#
# The two phases are minimised **independently**. They are separate measurements
# that happen to share a process, so tying them to the same attempt would let
# noise in one bury a result in the other.
run_set() {
  local arch="$1" flag="$2" label="$3" best_a="" best_u="" out ms mu
  for attempt in 1 2 3; do
    out="$(in_app_bg "$ENV_PREFIX $flag && timeout 300 $WINE/wine \$PWD/tsobench-$arch.exe" \
      "$APP_DIR/tso.log" || true)"
    # `ms=` cannot match inside `ms_unaligned=`, so the first pattern stays
    # unambiguous and the recorded aligned numbers remain comparable.
    ms="$(grep -o 'ms=[0-9.]*' <<<"$out" | head -1 | cut -d= -f2)"
    mu="$(grep -o 'ms_unaligned=[0-9.]*' <<<"$out" | head -1 | cut -d= -f2)"
    [ -n "$ms" ] && [ -n "$mu" ] \
      || { printf '  %-38s FAILED: %s\n' "$label" "$(tail -1 <<<"$out")"; return; }
    if [ -z "$best_a" ] || awk "BEGIN{exit !($ms < $best_a)}"; then best_a="$ms"; fi
    if [ -z "$best_u" ] || awk "BEGIN{exit !($mu < $best_u)}"; then best_u="$mu"; fi
  done
  printf '  %-38s %9s %11s\n' "$label" "$best_a" "$best_u"
}

# Which column answers which question: LRCPC2 acts on the aligned phase, the
# half-barrier backpatch only on the unaligned one.
header() { printf '  %-38s %9s %11s\n' '' 'aligned' 'unaligned'; }

say "x86-64 (translated by FEX — the case under test)"
header
run_set x86_64 "" "default (LRCPC2 on, half-barrier on)"
run_set x86_64 "FEX_HOSTFEATURES=disablelrcpc2" "disablelrcpc2"
run_set x86_64 "FEX_HALFBARRIERTSOENABLED=0" "halfbarriertso off"

say "ARM64 (native — the control, all three rows should match)"
header
run_set aarch64 "" "default"
run_set aarch64 "FEX_HOSTFEATURES=disablelrcpc2" "disablelrcpc2"
run_set aarch64 "FEX_HALFBARRIERTSOENABLED=0" "halfbarriertso off"

say "done"
