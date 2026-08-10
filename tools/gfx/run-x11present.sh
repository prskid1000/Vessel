#!/usr/bin/env bash
# Drive Turnip's X11 WSI against the app's own X server, with no Wine anywhere.
#
#   ./tools/gfx/run-x11present.sh                  # sw baseline, then DRI3
#   ./tools/gfx/run-x11present.sh --wsi dri3       # one mode
#   ./tools/gfx/run-x11present.sh --frames 600
#   ./tools/gfx/run-x11present.sh --reuse          # skip the build
#
# This is the instrument for any question about *presentation*, and
# run-presentbench.sh is not. That one runs a D3D11 program, so a number from it
# is FEX + Wine + winevulkan + DXVK + Mesa + the X server, and a failure in it
# names none of them: asked to compare `sw` against DRI3 on 2026-08-10 it
# returned `c000001d` — an illegal instruction in ARM64 code, at an address
# nowhere near the x86 image — identically in *both* modes, while the same
# device ran Metro at 60 FPS. A harness that fails the same way whatever it is
# measuring has stopped measuring.
#
# tools/gfx/x11present.c has none of those layers: a bionic ELF, as the app's
# uid, opening the abstract-namespace X socket with the *same* libxcb the driver
# links against. It is what produced the project's 2.245 ms present baseline
# (docs/TODO.md, "No D3D program has drawn into a window").
#
# It still needs a live session, because an X server needs a Surface, which
# needs an Activity. It never force-stops the app; if a session is up it joins
# it.
#
# The ad-hoc runners this replaces — out/present/x11p.sh and x11icd.sh — carry a
# hardcoded /data/app/~~<hash>== path, which Android regenerates on every
# install, so they worked exactly until the next sideload.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
APP_DIR=files/wsiprobe
STAGE="$REPO/out/gfx-native"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

# Git Bash rewrites Unix-looking absolute paths before adb.exe sees them.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
note() { printf '  \033[33m--\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
in_app_test() { [ "$(in_app "if $1; then echo yes; fi")" = yes ]; }

# `dri3` is this script's name for *not setting* MESA_VK_WSI_DEBUG. The variable
# is a set of opt-in flags (`wsi_common.c:55-60`); with `sw` absent `wsi->sw` is
# false (`:89`) and X11 takes its DRI3 half. There is no positive spelling, so
# the mode has to be an absence, and an absence needs a name to sit in a list.
#
# Baseline first, candidate second: a DRI3 attempt has already taken a session
# down once, and this ordering means the sitting still yields a comparable `sw`
# number when it does.
WSI_MODES="sw dri3"
FRAMES=300
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --wsi)     WSI_MODES="${2:-}"; shift 2 ;;
    --frames)  FRAMES="${2:-}"; shift 2 ;;
    --reuse)   BUILD=0; shift ;;
    -h|--help) sed -n '2,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app_test 'true' || die "run-as $PKG failed — is the debug build installed?"

# --- build --------------------------------------------------------------------
if [ "$BUILD" -eq 1 ]; then
  say "building x11present"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v vessel-work:/work \
    vessel-build:latest bash -c "cd /src && VESSEL_WORK_DIR=/work ./tools/gfx/build.sh" \
    >/dev/null || die "tools/gfx/build.sh failed"
  STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
  adb push "$STAGE_MOUNT/x11present" /data/local/tmp/x11present >/dev/null
  # `cat` rather than `cp`: a copied file keeps its shell_data_file SELinux
  # label and the app cannot exec that.
  in_app "mkdir -p $APP_DIR && cat /data/local/tmp/x11present > $APP_DIR/x11present && chmod 755 $APP_DIR/x11present"
  adb shell "rm -f /data/local/tmp/x11present"
  ok "staged into $APP_DIR"
fi
in_app_test "test -x $APP_DIR/x11present" || die "no probe on the device — run without --reuse once"

# --- what the probe loads -------------------------------------------------------
APK_DIR="$(adb shell "pm path $PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's|^package:||;s|/[^/]*$||' || true)"
HOOKS="$APK_DIR/lib/arm64"
in_app_test "test -f $HOOKS/libadrenotools.so" \
  || note "no libadrenotools in $HOOKS — this would measure the stock Qualcomm driver"

component() { in_app "ls -d files/components/$1/*/ 2>/dev/null | sort | tail -1"; }
TURNIP="$(component Turnip)"; TURNIP="${TURNIP%/}"
WINE_DIR="$(component Wine)"; WINE_DIR="${WINE_DIR%/}"
[ -n "$TURNIP" ] || die "no Turnip component installed in $PKG"
[ -n "$WINE_DIR" ] || die "no Wine component installed in $PKG"
say "driver $TURNIP"

# DRI3 is not just a code path, it is three more shared libraries. If they are
# absent the driver falls back to the software path without saying so, and the
# run would report a `dri3` number that is really a second `sw` number.
for lib in libxcb-dri3.so libxcb-present.so libxcb-sync.so; do
  if in_app_test "test -f $WINE_DIR/lib/$lib -o -f $TURNIP/$lib"; then
    ok "$lib"
  else
    note "$lib not in the Wine or Turnip component — DRI3 cannot negotiate"
  fi
done

# --- make sure a session is up ---------------------------------------------------
CONTAINER="$(in_app "ls files/containers/ 2>/dev/null | head -1")"
[ -n "$CONTAINER" ] || die "no provisioned container — launch one from the app first"
say "session for $CONTAINER"
adb shell "am start -n $PKG/app.vessel.MainActivity --es openSession $CONTAINER" >/dev/null \
  || die "could not start MainActivity"
for _ in $(seq 1 30); do
  sleep 3
  in_app_test "test -S files/containers/$CONTAINER/tmp/.X11-unix/X0" && break
done
# The socket is bound in the *abstract* namespace as well, which is how the
# probe reaches it; the filesystem node is only the readiness signal, and on a
# build that binds abstract-only it will never appear. Not fatal.
in_app_test "test -S files/containers/$CONTAINER/tmp/.X11-unix/X0" \
  && ok "X socket present" || note "no X socket node — trying the abstract name anyway"

# Wine's lib dir first so there is exactly one libxcb in the process, the same
# copy a session uses: an xcb_connection_t made by one libxcb and passed to
# another is undefined behaviour.
GUEST_ENV="cd \$PWD && export \
LD_LIBRARY_PATH=\$PWD/$WINE_DIR/lib:\$PWD/$TURNIP:$HOOKS \
ADRENOTOOLS_HOOKS_PATH=$HOOKS/ \
ADRENOTOOLS_DRIVER_PATH=\$PWD/$TURNIP \
ADRENOTOOLS_DRIVER_NAME=libvulkan_freedreno.so \
DISPLAY=:0 \
TU_DEBUG=startup \
MESA_LOG=file \
MESA_SHADER_CACHE_DIR=\$PWD/$APP_DIR/cache"

RESULTS=""
for wsi in $WSI_MODES; do
  if [ "$wsi" = dri3 ]; then
    say "MESA_VK_WSI_DEBUG unset (DRI3)"
    WSI_EXPORT="unset MESA_VK_WSI_DEBUG"
  else
    say "MESA_VK_WSI_DEBUG=$wsi"
    WSI_EXPORT="export MESA_VK_WSI_DEBUG=$wsi"
  fi
  # Mesa under Android logs to logcat by default (`util/log.c:119-124`).
  # MESA_LOG=file above redirects it to stderr, but the loader's own complaints
  # and any libvulkan.so fault still land in logcat, and the last DRI3 attempt
  # died with no protocol error on any channel this project was reading.
  adb logcat -c >/dev/null 2>&1 || true
  OUT="$(in_app "$GUEST_ENV && mkdir -p \$PWD/$APP_DIR/cache && $WSI_EXPORT && \
         timeout 180 /system/bin/linker64 \$PWD/$APP_DIR/x11present --frames=$FRAMES \
         > $APP_DIR/out-$wsi.log 2>&1; cat $APP_DIR/out-$wsi.log" || true)"
  printf '%s\n' "$OUT" | grep -E 'VESSEL-X11PRESENT|xcb_|dri3|DRI3' | sed 's/^/       /' || true
  LINE="$(printf '%s\n' "$OUT" | grep -E 'result=' | head -1 || true)"
  case "$LINE" in
    *result=PASS*)
      ok "$wsi"
      RESULTS="$RESULTS\n  $(printf '%-6s' "$wsi") $(printf '%s' "$LINE" | sed 's/.*result=PASS //')" ;;
    "")
      printf '%s\n' "$OUT" | tail -15 | sed 's/^/       ! /'
      adb logcat -d 2>/dev/null | tr -d '\r' | grep -iE 'mesa|turnip|tu_|vulkan|DEBUG.*x11present' \
        | tail -15 | sed 's/^/       ~ /' || true
      bad "$wsi produced no result line"
      RESULTS="$RESULTS\n  $(printf '%-6s' "$wsi") NO-OUTPUT" ;;
    *)
      bad "$wsi: $LINE"
      RESULTS="$RESULTS\n  $(printf '%-6s' "$wsi") $LINE" ;;
  esac
done

echo
printf '\033[1mpresent cost\033[0m'
printf '%b\n' "$RESULTS"
echo
