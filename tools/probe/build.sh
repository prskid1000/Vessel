#!/usr/bin/env bash
# Build the mapexec probe, push it, and run it under the app's own uid.
#
#   ./tools/probe/build.sh [pe-file-on-device]
#
# The probe answers a question the Wine log refuses to: which mmap/mprotect call
# is actually refused, and with what errno. See mapexec.c for how to read it.
#
# Compiled in the container with the same NDK and API level as the shipped
# components, so "works here" means something for them; pushed and run from the
# host, because adb only exists here.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
OUT="$REPO/out/probe"
mkdir -p "$OUT"

# Docker Desktop cannot mount a Git Bash path; `pwd -W` gives the Windows one
# and is a harmless no-op elsewhere. Same reason as tools/device-smoke.sh.
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
OUT_MOUNT="$(cd "$OUT" && { pwd -W 2>/dev/null || pwd; })"

# Git Bash also rewrites Unix-looking absolute paths before adb.exe sees them,
# which silently misdirects a push. Turning that off means local paths must be
# Windows-shaped, which the *_MOUNT values above already are.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"

say "compiling"
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$OUT_MOUNT:/out" \
  vessel-build:latest bash -c '
    set -euo pipefail
    . /src/build/common.sh
    vessel_init
    setup_ndk
    "$NDK_CC" -O1 -Wall -o /out/mapexec /src/tools/probe/mapexec.c
    file /out/mapexec'

say "pushing"
adb push "$OUT_MOUNT/mapexec" /data/local/tmp/mapexec >/dev/null
# The app cannot exec out of /data/local/tmp (that is shell_data_file, and the
# app domain is refused execute on it), so the probe is copied into app data via
# a plain cat — which is the filesystem under test anyway.
adb shell "chmod 644 /data/local/tmp/mapexec"
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/mapexec > mapexec && chmod 700 mapexec'"

say "running as $PKG"
adb shell "run-as $PKG ./mapexec ${1:-}" | tr -d '\r'
