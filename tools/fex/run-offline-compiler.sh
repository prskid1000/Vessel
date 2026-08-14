#!/usr/bin/env bash
# Run FEXOfflineCompiler64.exe in the container, by hand.
#
#   ./tools/fex/run-offline-compiler.sh              # no arguments: prints usage
#   ./tools/fex/run-offline-compiler.sh process-all  # what a session runs
#
#   VESSEL_WINE_DIR=files/components/Wine/1114 \
#     ./tools/fex/run-offline-compiler.sh process-all   # the same run, on 11.14
#
# That last form is the Wine-version A/B: the container side otherwise takes the
# newest installed Wine, so naming an older component runs the identical compile
# against it. Install the other build from the Components screen first.
#
# The code cache has three failure modes that look identical from outside — the
# compiler never ran, the compiler ran and crashed, the compiler ran and found
# nothing to do — and a session log only distinguishes them if teardown was
# reached, which for most of this project's life it never was. This asks the
# question directly, in about two seconds.
#
# No-argument runs are the useful ones for a startup fault: the tool should
# print its usage text and exit non-zero. A page fault instead means it died
# before `main` dispatched anything, which is what patches/fex/0001 fixes.
#
# It joins the container the app already has, for the same reason
# tools/audio/run-guest-tone.sh does: the environment a session runs with is
# part of what is being tested.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# `adb push` needs a path Windows can stat, and MSYS_NO_PATHCONV below stops Git
# Bash rewriting one for us — so the local side is resolved here instead.
REPO_WIN="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
PKG=app.vessel

# Git Bash rewrites Unix-looking absolute paths before adb.exe sees them.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

CONTAINER="$(in_app 'ls files/containers/ 2>/dev/null | head -1')"
[ -n "$CONTAINER" ] || die "no provisioned container"

# The work happens in a file on the phone, not in a string. The one thing this
# has to get right is `C:\vessel\fexcache\`, and that cannot survive
# `adb shell "run-as pkg sh -c '…'"` — three quoting levels, two of which eat
# backslashes. The first version of this script died on `no closing quote`.
say "installing the container-side script"
adb push "$REPO_WIN/tools/fex/offline-compiler.container.sh" \
  /data/local/tmp/offline-compiler.sh >/dev/null
in_app "cat /data/local/tmp/offline-compiler.sh > files/offline-compiler.sh"
adb shell "rm -f /data/local/tmp/offline-compiler.sh" >/dev/null 2>&1 || true

say "running FEXOfflineCompiler64.exe ${*:-<no arguments>}"
in_app "${VESSEL_WINE_DIR:+VESSEL_WINE_DIR=$VESSEL_WINE_DIR }sh files/offline-compiler.sh $CONTAINER $*"
