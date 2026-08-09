#!/usr/bin/env bash
# Build, stage and run tools/gfx/wsiprobe.c on the phone, as the app's uid,
# through /system/bin/linker64 — the shape the Wine unix side runs in.
#
#   ./tools/gfx/run-wsiprobe.sh            # build then run
#   ./tools/gfx/run-wsiprobe.sh --reuse    # run what is already on the device
#
# The driver comes from the app's own component store rather than from a copy
# pushed beside the probe, so what is measured is what a session would get.
# There is no ART here and the APK's nativeLibraryDir is not on the default
# namespace's search path, which is why the hooks directory is named explicitly
# on both ADRENOTOOLS_HOOKS_PATH and LD_LIBRARY_PATH — see tools/device-vulkan.sh.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
APP_DIR=files/wsiprobe
STAGE="$REPO/out/gfx-native"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

# Git Bash rewrites Unix-looking absolute paths before adb.exe sees them, which
# silently misdirects a push. Same wrapper as every other tool here.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
in_app_test() { [ "$(in_app "if $1; then echo yes; fi")" = yes ]; }

REUSE=0
[ "${1:-}" = "--reuse" ] && REUSE=1

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app_test 'true' || die "run-as $PKG failed — is the debug build installed?"

if [ "$REUSE" -eq 0 ]; then
  say "building"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v vessel-work:/work \
    vessel-build:latest bash -c "cd /src && VESSEL_WORK_DIR=/work ./tools/gfx/build.sh" \
    >/dev/null || die "tools/gfx/build.sh failed"

  say "staging"
  STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
  adb push "$STAGE_MOUNT/wsiprobe" /data/local/tmp/wsiprobe >/dev/null
  # `cat` rather than `cp`: a file copied in keeps its shell_data_file SELinux
  # label, and the app cannot exec that.
  in_app "mkdir -p $APP_DIR && cat /data/local/tmp/wsiprobe > $APP_DIR/wsiprobe && chmod 755 $APP_DIR/wsiprobe"
  adb shell "rm -f /data/local/tmp/wsiprobe"
fi

in_app_test "test -x $APP_DIR/wsiprobe" || die "no probe on the device — run without --reuse once"

APK_DIR="$(adb shell "pm path $PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's|^package:||;s|/[^/]*$||' || true)"
HOOKS="$APK_DIR/lib/arm64"
in_app_test "test -f $HOOKS/libadrenotools.so" \
  || die "no libadrenotools.so in $HOOKS — the probe would measure the stock Qualcomm driver"

# The newest installed Turnip in the app's component store, by version code.
TURNIP="$(in_app "ls -d files/components/Turnip/*/ 2>/dev/null | sort | tail -1")"
[ -n "$TURNIP" ] || die "no Turnip component installed in $PKG"
say "driver: $TURNIP"

# The daemon-hangs-adb trap does not apply (this exits), but the log file is kept
# anyway so a crash mid-run still leaves the measurements taken before it.
in_app "cd \$PWD && \
  export ADRENOTOOLS_HOOKS_PATH=$HOOKS/ \
         ADRENOTOOLS_DRIVER_PATH=\$PWD/$TURNIP \
         ADRENOTOOLS_DRIVER_NAME=libvulkan_freedreno.so \
         LD_LIBRARY_PATH=$HOOKS:\$PWD/$TURNIP \
         TU_DEBUG=startup && \
  /system/bin/linker64 \$PWD/$APP_DIR/wsiprobe > $APP_DIR/out.log 2>&1; cat $APP_DIR/out.log"
