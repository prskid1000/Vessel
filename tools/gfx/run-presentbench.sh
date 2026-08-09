#!/usr/bin/env bash
# Measure what one flip costs, on the phone, against the app's own X server.
#
#   ./tools/gfx/run-presentbench.sh                      # every WSI mode
#   ./tools/gfx/run-presentbench.sh --wsi sw             # one mode
#   ./tools/gfx/run-presentbench.sh --arch x86_64        # one architecture
#   ./tools/gfx/run-presentbench.sh --frames 600
#
# The shape is device-display.sh's, and for the same reason: an X server needs a
# Surface, which needs an Activity, so the *app* has to be the runtime. This
# script deep-links the app at its already-provisioned container, waits for the
# session, and then joins from outside — a second process, same uid, DISPLAY=:0,
# running tools/gfx/presentbench.c against the X server the app is hosting.
#
# Running the probe ourselves rather than through the launcher is what makes the
# comparison possible: MESA_VK_WSI_DEBUG is fixed in SessionEnvironment and
# reserved, so this is the only way to put two present paths side by side
# without reinstalling the APK between them.
#
# It never force-stops the app. If a session is already up it uses it.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
STAGE="$REPO/out/present"
mkdir -p "$STAGE"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
note() { printf '  \033[33m--\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
in_app_test() { [ "$(in_app "if $1; then echo yes; fi")" = yes ]; }
# adb shell waits for EOF on stdout, and anything that forks a server keeps it
# open. Redirect to a file the child can hold, then read the file.
in_app_bg() { adb shell "run-as $PKG sh -c '( $1 ) > $2 2>&1 </dev/null; cat $2'" | tr -d '\r'; }

WSI_MODES="sw sw,linear"
# x86_64 rather than aarch64, and the reason is not obvious enough to leave
# unsaid: DXVK is built arm64ec only, so system32 holds ARM64EC images. An
# x86-64 process runs on Wine's ARM64EC ntdll and loads them natively — the
# translation is of the *probe*, not of DXVK. A pure-ARM64 probe gets the
# aarch64 ntdll instead, which cannot load an EC image at all:
#     VESSEL-PRESENT result=BLOCKED stage=loadlibrary-d3d11 err=193
# (ERROR_BAD_EXE_FORMAT), which reads like a broken DXVK and is not.
ARCHES="x86_64"
FRAMES=300
CONTAINER=""
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --wsi)       WSI_MODES="${2:-}"; shift 2 ;;
    --arch)      ARCHES="${2:-}"; shift 2 ;;
    --frames)    FRAMES="${2:-}"; shift 2 ;;
    --container) CONTAINER="${2:-}"; shift 2 ;;
    --reuse)     BUILD=0; shift ;;
    -h|--help)   sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app_test 'true' || die "run-as $PKG failed — is the debug build installed?"

# --- the container ------------------------------------------------------------
# Whatever the app has already provisioned. Making one here would mean
# re-running two wineboot passes for a measurement that does not need them.
if [ -z "$CONTAINER" ]; then
  CONTAINER="$(in_app "ls files/containers/ 2>/dev/null | head -1")"
fi
[ -n "$CONTAINER" ] || die "no provisioned container in $PKG — launch one from the app first"
PREFIX="files/containers/$CONTAINER/prefix"
in_app_test "test -s $PREFIX/system.reg" \
  || die "container $CONTAINER has no prefix — launch it from the app once"
say "container $CONTAINER"

# --- the components the guest needs --------------------------------------------
component() { in_app "ls -d files/components/$1/*/ 2>/dev/null | sort | tail -1"; }
WINE_DIR="$(component Wine)"
TURNIP_DIR="$(component Turnip)"
[ -n "$WINE_DIR" ] || die "no Wine component installed"
[ -n "$TURNIP_DIR" ] || die "no Turnip component installed"
WINE_DIR="${WINE_DIR%/}"
APK_DIR="$(adb shell "pm path $PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's|^package:||;s|/[^/]*$||')"
HOOKS="$APK_DIR/lib/arm64"
in_app_test "test -f $HOOKS/libadrenotools.so" \
  || note "no libadrenotools in $HOOKS — this would measure the stock Qualcomm driver"

# --- build ---------------------------------------------------------------------
if [ "$BUILD" -eq 1 ]; then
  say "compiling presentbench"
  # shellcheck disable=SC2016
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
    -v vessel-work:/work vessel-build:latest bash -c '
      set -e
      . /src/build/common.sh >/dev/null; vessel_init >/dev/null; setup_mingw >/dev/null
      for t in arm64ec aarch64 x86_64 i686; do
        "$MINGW_BIN/$t-w64-mingw32-clang" -O1 -Wall -Wextra -Wno-unused-parameter \
          -o "/out/presentbench-$t.exe" /src/tools/gfx/presentbench.c \
          -I/src/tools/gfx -ldxguid -luuid -lgdi32 -luser32
      done' >/dev/null || die "compiling presentbench failed"
  for t in $ARCHES; do
    adb push "$STAGE_MOUNT/presentbench-$t.exe" "/data/local/tmp/presentbench-$t.exe" >/dev/null
    in_app "cat /data/local/tmp/presentbench-$t.exe > $PREFIX/drive_c/presentbench-$t.exe"
    adb shell "rm -f /data/local/tmp/presentbench-$t.exe"
  done
  ok "staged into drive_c"
fi

# --- make sure a session is up --------------------------------------------------
# Never force-stopped: if the user has a session running this joins it, and if
# they have not, the deep link is the same one the running-session notification
# uses.
say "making sure the session is up"
if in_app_test "test -S files/containers/$CONTAINER/tmp/.X11-unix/X0" \
   || [ -n "$(adb shell 'ps -A' | grep -c wineserver || true)" ]; then
  : # something may already be there; the probe will say if not
fi
adb shell "am start -n $PKG/app.vessel.MainActivity --es openSession $CONTAINER" >/dev/null \
  || die "could not start MainActivity"

LOG=""
for _ in $(seq 1 60); do
  sleep 5
  LOG="$(in_app "ls -t files/logs/*/*.log 2>/dev/null | head -1")"
  [ -n "$LOG" ] || continue
  if in_app "grep -c 'exec .*explorer' $LOG 2>/dev/null" | grep -qv '^0$'; then break; fi
done
[ -n "$LOG" ] || die "no session log appeared — the launch never reached Preparing"
ok "session log $LOG"

# --- the guest environment -------------------------------------------------------
# Reproduces SessionEnvironment.kt for everything that affects presentation, and
# deliberately leaves MESA_VK_WSI_DEBUG out: that is the variable under test and
# it is set per run below.
SHM_SOCKET="/data/data/$PKG/files/containers/$CONTAINER/tmp/.sysvshm/SM0"
GUEST_ENV="cd \$PWD && export \
WINEPREFIX=\$PWD/$PREFIX \
HOME=\$PWD/files/containers/$CONTAINER \
TMPDIR=\$PWD/files/containers/$CONTAINER/tmp \
XDG_RUNTIME_DIR=\$PWD/files/containers/$CONTAINER/tmp \
WINEDLLPATH=\$PWD/$WINE_DIR/lib/wine \
WINENLSDIR=\$PWD/$WINE_DIR/share/wine/nls \
LD_LIBRARY_PATH=\$PWD/$WINE_DIR/lib:\$PWD/$WINE_DIR/lib/wine/aarch64-unix:$HOOKS \
WINEESYNC=1 \
WINEDEBUG=-all,err+all,+winediag \
WINEDLLOVERRIDES=d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi,opengl32=n \
DXVK_LOG_LEVEL=info \
DXVK_LOG_PATH=none \
DISPLAY=:0 \
TU_DEBUG=startup \
WINE_SYSVSHM_SOCKET=$SHM_SOCKET \
ADRENOTOOLS_DRIVER_PATH=\$PWD/$TURNIP_DIR \
ADRENOTOOLS_HOOKS_PATH=$HOOKS/ \
ADRENOTOOLS_DRIVER_NAME=libvulkan_freedreno.so"

WINE="/system/bin/linker64 \$PWD/$WINE_DIR/bin/wine"

# --- run --------------------------------------------------------------------------
RESULTS=""
for arch in $ARCHES; do
  for wsi in $WSI_MODES; do
    say "$arch · MESA_VK_WSI_DEBUG=$wsi"
    OUT="$(in_app_bg "$GUEST_ENV && export MESA_VK_WSI_DEBUG=$wsi && \
           timeout 180 $WINE c:\\\\presentbench-$arch.exe --frames=$FRAMES" \
           "files/presentbench.log" || true)"
    printf '%s\n' "$OUT" | grep -E 'VESSEL-PRESENT' | sed 's/^/       /' || true
    LINE="$(printf '%s\n' "$OUT" | grep -E '^VESSEL-PRESENT api=' | head -1 || true)"
    if [ -z "$LINE" ]; then
      printf '%s\n' "$OUT" | tail -12 | sed 's/^/       ! /'
      bad "$arch/$wsi produced no result line"
      RESULTS="$RESULTS\n  NO-OUTPUT  $arch  $wsi"
    else
      case "$LINE" in
        *result=PASS*)
          ok "$arch/$wsi"
          RESULTS="$RESULTS\n  $(printf '%-8s %-10s' "$arch" "$wsi") $(printf '%s' "$LINE" | sed 's/.*result=PASS //')" ;;
        *)
          bad "$arch/$wsi: $LINE"
          RESULTS="$RESULTS\n  $(printf '%-8s %-10s' "$arch" "$wsi") $LINE" ;;
      esac
    fi
  done
done

echo
printf '\033[1mpresent cost\033[0m'
printf '%b\n' "$RESULTS"
echo
