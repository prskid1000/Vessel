#!/usr/bin/env bash
# Measure the things an optimization would move, on the phone.
#
#   ./tools/device-bench.sh                        # everything
#   ./tools/device-bench.sh --only cpu             # cpu | startup | graphics
#   ./tools/device-bench.sh --scale 2              # longer runs, less noise
#   ./tools/device-bench.sh --baseline out/bench/before.txt   # print deltas
#
# docs/OPTIMIZATION.md lists what is worth changing. This is how you find out
# whether changing it helped, and it exists because a build flag that "should"
# help is the easiest thing in systems work to be confidently wrong about.
#
# Three rules are built in, and they are what make the numbers mean anything:
#
#   1. A control group. Every x86 measurement is paired with the same source
#      built for ARM64, which runs natively. If the native number moves between
#      two runs of this script, the device was thermally different and the x86
#      delta cannot be read as a result.
#   2. Best-of-N, not one sample. A phone throttles. The fastest run is the one
#      least disturbed by whatever else the device was doing.
#   3. Checksums. Every CPU section prints one and they must match across all
#      three architectures — a translator that is fast because it skipped work
#      would otherwise read as a win.
#
# Requires a prefix from ./tools/device-session.sh, Docker, and the debug app
# installed (run-as needs a debuggable package).

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.vessel
APP_DIR=files/session
STAGE="$REPO/out/bench"
mkdir -p "$STAGE"

REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"

# See tools/device-smoke.sh: Git Bash rewrites Unix-looking absolute paths before
# adb.exe sees them, which silently misdirects a push. Local paths therefore have
# to be handed over in Windows form — hence STAGE_MOUNT above.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
warn() { printf '  \033[33mwarn\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
# A daemon inheriting the adb pipe hangs adb shell forever; the subshell keeps
# the caller's `cd` from leaking into the `cat`. Same helper as device-session.sh.
in_app_bg() {
  adb shell "run-as $PKG sh -c '( $1 ) > $2 2>&1 </dev/null; cat $2'" | tr -d '\r'
}

ONLY=all
SCALE=1
BASELINE=
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="${2:-}"; shift 2 ;;
    --scale) SCALE="${2:-1}"; shift 2 ;;
    --baseline) BASELINE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
case "$ONLY" in all|cpu|startup|graphics) ;; *) die "--only takes cpu, startup or graphics" ;; esac

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app "test -f $APP_DIR/prefix/system.reg" >/dev/null 2>&1 \
  || die "no prefix at $APP_DIR — run ./tools/device-session.sh first"

RESULTS="$STAGE/results.txt"
: > "$RESULTS"
record() { printf '%s %s\n' "$1" "$2" >> "$RESULTS"; }

# WINEDEBUG=-all here and nowhere else in this repo. Everywhere else the
# diagnostic channels are the point; in a benchmark they are the measurement's
# largest confound, because +loaddll writes a line per module load.
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

ARCHES="aarch64 x86_64 i686"

# --- cpu ----------------------------------------------------------------------
bench_cpu() {
  say "compiling cpubench for $ARCHES"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
    -v vessel-work:/work vessel-build:latest bash -c '
      set -euo pipefail
      . /src/build/common.sh >/dev/null
      vessel_init >/dev/null
      setup_mingw >/dev/null
      # -ffp-contract=off is required, not a preference. Left on, clang fuses
      # the float section into an FMA on ARM64 and emits a separate multiply
      # and add on x86 — one rounding versus two — so the two builds disagree
      # in the last place and the checksum guard voids a perfectly good row for
      # a reason that has nothing to do with FEX. Observed on the first run.
      for t in aarch64 x86_64 i686; do
        "$MINGW_BIN/$t-w64-mingw32-clang" -O2 -ffp-contract=off \
          -o "/out/cpubench-$t.exe" /src/tools/bench/cpubench.c
      done' >/dev/null || die "compile failed"
  ok "built"

  for t in $ARCHES; do
    adb push "$STAGE_MOUNT/cpubench-$t.exe" "/data/local/tmp/cpubench-$t.exe" >/dev/null
    in_app "cat /data/local/tmp/cpubench-$t.exe > $APP_DIR/cpubench-$t.exe"
    adb shell "rm -f /data/local/tmp/cpubench-$t.exe"
  done

  say "cpu — best of 3 per architecture, scale $SCALE"
  printf '  %-8s %10s %10s %10s %10s\n' arch int branch mem float

  # Checksums from the native run become the reference every other arch is held
  # to. Collected as one string per section rather than an associative array:
  # this has to run under whatever /bin/sh the build box has.
  local ref_int= ref_branch= ref_mem= ref_float=

  for t in $ARCHES; do
    local best_int= best_branch= best_mem= best_float= out ms cs section
    local mismatch=

    for attempt in 1 2 3; do
      out="$(in_app_bg "$ENV_PREFIX && timeout 900 $WINE/wine \$PWD/cpubench-$t.exe $SCALE" \
            "$APP_DIR/bench.log" || true)"
      grep -q 'result=OK' <<<"$out" || continue

      while read -r section ms cs; do
        [ -n "$section" ] || continue
        case "$section" in
          int)    if [ -z "$best_int" ]    || awk "BEGIN{exit !($ms<$best_int)}";    then best_int=$ms;    fi
                  if [ "$t" = aarch64 ]; then ref_int=$cs;    elif [ "$cs" != "$ref_int" ];    then mismatch="int"; fi ;;
          branch) if [ -z "$best_branch" ] || awk "BEGIN{exit !($ms<$best_branch)}"; then best_branch=$ms; fi
                  if [ "$t" = aarch64 ]; then ref_branch=$cs; elif [ "$cs" != "$ref_branch" ]; then mismatch="branch"; fi ;;
          mem)    if [ -z "$best_mem" ]    || awk "BEGIN{exit !($ms<$best_mem)}";    then best_mem=$ms;    fi
                  if [ "$t" = aarch64 ]; then ref_mem=$cs;    elif [ "$cs" != "$ref_mem" ];    then mismatch="mem"; fi ;;
          float)  if [ -z "$best_float" ]  || awk "BEGIN{exit !($ms<$best_float)}";  then best_float=$ms;  fi
                  if [ "$t" = aarch64 ]; then ref_float=$cs;  elif [ "$cs" != "$ref_float" ];  then mismatch="float"; fi ;;
        esac
      done < <(sed -n 's/^CPUBENCH .*section=\([a-z]*\) ms=\([0-9.]*\) checksum=\([0-9]*\)$/\1 \2 \3/p' <<<"$out")
    done

    if [ -z "$best_int" ]; then
      printf '  %-8s %s\n' "$t" "no result — see $APP_DIR/bench.log on the device"
      continue
    fi
    printf '  %-8s %10s %10s %10s %10s\n' "$t" "$best_int" "$best_branch" "$best_mem" "$best_float"
    for s in int branch mem float; do
      eval "record cpu.$t.$s \"\$best_$s\""
    done

    # A checksum mismatch voids the row it is on. Printed loudly rather than
    # returned, because the timings above it look perfectly reasonable.
    [ -n "$mismatch" ] && warn "$t computed a different '$mismatch' checksum than ARM64 — its timings are void"
  done

  # The ratio is the result. An absolute millisecond count on a phone is a
  # statement about the case temperature.
  say "translation cost — x86 time divided by native ARM64 time"
  for t in x86_64 i686; do
    for s in int branch mem float; do
      local n x
      n="$(awk -v k="cpu.aarch64.$s" '$1==k{print $2}' "$RESULTS")"
      x="$(awk -v k="cpu.$t.$s"      '$1==k{print $2}' "$RESULTS")"
      [ -n "$n" ] && [ -n "$x" ] || continue
      printf '  %-8s %-7s %sx\n' "$t" "$s" "$(awk "BEGIN{printf \"%.2f\", $x/$n}")"
    done
  done
}

# --- startup ------------------------------------------------------------------
bench_startup() {
  # `cmd /c exit` is the smallest thing that still loads a full PE process:
  # ntdll, kernelbase, the server connection and one image. That is the cost
  # every launch pays before a program's own first instruction.
  say "wine startup — best of 5, cmd.exe /c exit"
  local best= out ms
  for attempt in 1 2 3 4 5; do
    out="$(in_app_bg "$ENV_PREFIX && S=\$(date +%s%N) && \
           timeout 120 $WINE/wine cmd /c exit >/dev/null 2>&1; \
           E=\$(date +%s%N); echo STARTUP_NS=\$((E-S))" "$APP_DIR/bench.log" || true)"
    ms="$(sed -n 's/^STARTUP_NS=\([0-9]*\)$/\1/p' <<<"$out" | head -1)"
    [ -n "$ms" ] || continue
    ms="$(awk "BEGIN{printf \"%.1f\", $ms/1000000}")"
    if [ -z "$best" ] || awk "BEGIN{exit !($ms < $best)}"; then best="$ms"; fi
  done
  if [ -z "$best" ]; then
    warn "no startup measurement — cmd.exe did not run"
    return
  fi
  printf '  %-28s %8s ms\n' "cold process start" "$best"
  record startup.cmd "$best"
}

# --- graphics -----------------------------------------------------------------
bench_graphics() {
  say "graphics — shader cache, cold versus warm"
  # Still an honest refusal, but **the old reason expired and the new one is
  # different**. The old text said the D3D probes cannot start because nothing
  # serves DISPLAY in a headless harness. That has been false since
  # tools/gfx/run-presentbench.sh, which deep-links the app and runs a real
  # D3D11 swapchain against the X server the app is hosting.
  #
  # What blocks it now is the workload, not the display. presentbench.c is built
  # to draw as little as possible — one ClearRenderTargetView, no shaders of its
  # own — precisely so its number is the present path. Wiping caches/dxvk and
  # running it twice would therefore time the compile of DXVK's internal blit
  # pipeline and nothing else: a handful of pipelines, when the cost being
  # chased is an application's whole shader set. That is a stable, meaningless,
  # reproducible number — the worst kind, and the same trap the previous refusal
  # was written to avoid.
  #
  # Lifting this needs a shader-heavy workload, which is one of:
  #   - a probe that compiles many distinct pipelines, cold and warm; or
  #   - a real title, timed launch-to-first-frame with caches/dxvk wiped between.
  # The second is the number anyone actually cares about and needs no new code —
  # only a session, which is why it is not in this headless script.
  warn "not measurable here: presentbench draws no shaders by design, so a"
  warn "cold-vs-warm run of it would time DXVK's blit pipeline, not a shader set."
  warn "Needs a shader-heavy workload — a many-pipeline probe, or a real title"
  warn "timed launch-to-first-frame with caches/dxvk wiped between runs."
  record graphics.status blocked-on-shader-workload
}

case "$ONLY" in
  cpu)      bench_cpu ;;
  startup)  bench_startup ;;
  graphics) bench_graphics ;;
  all)      bench_cpu; bench_startup; bench_graphics ;;
esac

# --- comparison ---------------------------------------------------------------
if [ -n "$BASELINE" ]; then
  [ -f "$BASELINE" ] || die "no baseline file at $BASELINE"
  say "versus $BASELINE"
  printf '  %-24s %10s %10s %9s\n' metric before after change
  while read -r key before; do
    after="$(awk -v k="$key" '$1==k{print $2}' "$RESULTS")"
    [ -n "$after" ] || continue
    case "$before$after" in *[!0-9.]*) continue ;; esac
    # Negative means faster. Printed as a percentage because that is the only
    # form in which two runs on a phone are worth comparing at all.
    printf '  %-24s %10s %10s %8s%%\n' "$key" "$before" "$after" \
      "$(awk "BEGIN{printf \"%+.1f\", ($after-$before)*100/$before}")"
  done < "$BASELINE"
fi

say "results written to $RESULTS"
echo "  keep it as a baseline:  cp $RESULTS $STAGE/before.txt"
