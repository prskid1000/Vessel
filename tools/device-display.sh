#!/usr/bin/env bash
# Put a Windows window on the phone's screen, and prove it.
#
#   ./tools/device-display.sh              # seed, launch, screenshot, grep
#   ./tools/device-display.sh --keep       # reuse whatever is already installed
#
# device-session.sh answers "does a Windows program run" — three translation
# paths, all console, no display anywhere. This answers the question after it:
# does a Windows program get a *window*. Every run of device-session.sh ends with
#
#     err:win:nodrv_CreateWindow Application tried to create a window, but no
#     driver could be loaded.
#
# and the absence of that line is the pass condition here.
#
# ┌─────────────────────────────────────────────────────────────────────────────┐
# │ NEVER EXECUTED. The phone went away before this could be run even once, so   │
# │ every step below is reasoned rather than observed. Treat a failure as a bug  │
# │ in this script until proven otherwise. What *was* verified on device is one  │
# │ thing only: an abstract unix socket named "/tmp/.X11-unix/X0" can be bound   │
# │ AND connected from inside the app's own SELinux domain, proved with a        │
# │ throwaway C probe run under `run-as app.vessel`. Everything the display path │
# │ rests on is downstream of that — see the comment on XServerDisplay.          │
# └─────────────────────────────────────────────────────────────────────────────┘
#
# The shape is different from device-session.sh and the difference is the point:
# there, the script was the whole runtime. Here the *app* is the runtime — it
# owns the X server, and an X server needs a Surface, which needs an Activity.
# So this script seeds the app's own component store and container document,
# deep-links straight to the Session screen, and then joins the party from the
# outside: a second process, under the same uid, runs winemine against the X
# server the app is already hosting. That second half is also the cheapest proof
# that the abstract socket really is reachable across processes.
#
# Requires:
#   - the debug app installed (run-as needs a debuggable package)
#   - dist/ holding at least a wine-*.wcp and a fex-*.wcp
#   - a Wine package built AFTER patches/wine/0005 landed, or MIT-SHM stays off
#     (the script checks and says so rather than reporting a false negative)

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.vessel
STAGE="$REPO/out/display"
CONTAINER_ID=aaaaaaaa-0000-4000-8000-000000000001
CONTAINER_NAME="Display proof"
mkdir -p "$STAGE"

STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"

# See tools/device-smoke.sh: Git Bash rewrites Unix-looking absolute paths before
# adb.exe sees them, which silently misdirects a push.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
note() { printf '  \033[33m--\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
failures=0

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

# For anything that forks a server; see device-session.sh for why `adb shell`
# hangs otherwise. $2 is a log path relative to the app home.
in_app_bg() {
  adb shell "run-as $PKG sh -c '( $1 ) > $2 2>&1 </dev/null; cat $2'" | tr -d '\r'
}

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app 'true' >/dev/null 2>&1 || die "run-as $PKG failed — is the debug build installed?"

# --- 1. seed the app's component store ------------------------------------------
# The app installs .wcp packages itself, from the registry over the network. That
# is the real path and it is not this script's business: what is needed here is
# the *locally built* Wine, which is the only one that can carry patch 0005.
#
# The store layout is ComponentStoreLayout's and nothing more:
#   files/components/<type>/<versionCode>/     the unpacked payload
#   files/components/<type>/<versionCode>.json ComponentRecord — one field
# `type` and `versionCode` are read out of each package's own profile.json, so
# nothing here has to know the version of anything.
if [ "$KEEP" -eq 0 ]; then
  say "1/6  seeding the component store from dist/"
  shopt -s nullglob
  WCPS=("$REPO"/dist/*.wcp)
  shopt -u nullglob
  [ "${#WCPS[@]}" -gt 0 ] || die "no .wcp packages in dist/"

  # Newest wine-*.wcp only. dist/ holds both 10.13 and 11.14 and installing both
  # just costs a gigabyte the phone then has to choose between.
  NEWEST_WINE="$(ls -t "$REPO"/dist/wine-*.wcp 2>/dev/null | head -1)"
  [ -n "$NEWEST_WINE" ] || die "no Wine package in dist/"

  for wcp in "${WCPS[@]}"; do
    case "$(basename "$wcp")" in
      wine-*) [ "$wcp" = "$NEWEST_WINE" ] || continue ;;
    esac
    name="$(basename "$wcp" .wcp)"
    tarball="$STAGE/$name.tar"

    # xz in the container, tar on the phone — the argv[0] symlinks in the Wine
    # package do not survive a Windows-side extract.
    if [ ! -f "$tarball" ] || [ "$wcp" -nt "$tarball" ]; then
      MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
        vessel-build:latest bash -c "xz -dc /src/${wcp#"$REPO/"} > /out/$name.tar" \
        || die "could not decompress $name.wcp"
    fi

    read -r type code < <(python - "$tarball" <<'PY'
import json, sys, tarfile
with tarfile.open(sys.argv[1]) as t:
    for member in ("profile.json", "./profile.json"):
        try:
            profile = json.load(t.extractfile(member))
            break
        except KeyError:
            continue
    else:
        raise SystemExit("no profile.json in " + sys.argv[1])
print(profile["type"], profile["versionCode"])
PY
    ) || die "could not read profile.json out of $name.wcp"

    adb push "$STAGE_MOUNT/$name.tar" /data/local/tmp/component.tar >/dev/null
    in_app "rm -rf files/components/$type/$code && mkdir -p files/components/$type/$code && \
            tar -xf /data/local/tmp/component.tar -C files/components/$type/$code && \
            printf '{\"packageId\":\"$name\"}' > files/components/$type/$code.json"
    adb shell "rm -f /data/local/tmp/component.tar"
    ok "$type/$code  ($name)"
  done
fi

WINE_DIR="$(in_app "ls -d files/components/Wine/* 2>/dev/null | head -1")"
[ -n "$WINE_DIR" ] || die "no Wine in the component store"

# Does the installed Wine carry patch 0005? The variable name is a string
# constant in winex11.so, so grep answers it without running anything. Checked
# before the run rather than after, so "no MIT-SHM" is never mistaken for a
# failure of the socket when it is really a stale package.
if in_app "grep -c WINE_SYSVSHM_SOCKET $WINE_DIR/lib/wine/aarch64-unix/winex11.so 2>/dev/null" \
   | grep -qv '^0$'; then
  MITSHM_EXPECTED=1
  ok "winex11.so carries patch 0005"
else
  MITSHM_EXPECTED=0
  note "winex11.so has no WINE_SYSVSHM_SOCKET — this Wine predates patch 0005."
  note "   Rebuild with ./build/wine.sh to test MIT-SHM; the window test still runs."
fi

# --- 2. seed a container --------------------------------------------------------
# containers.json is a plain-JSON DataStore file, so it can be written from here.
# The app has to be dead first: DataStore caches the document in memory and
# rewrites it on the next save, which would throw this away.
say "2/6  seeding a container"
adb shell "am force-stop $PKG" >/dev/null
cat > "$STAGE/containers.json" <<EOF
{
  "schemaVersion": 1,
  "containers": [
    {
      "id": "$CONTAINER_ID",
      "name": "$CONTAINER_NAME",
      "wineBuild": "wine",
      "driver": "turnip",
      "d3dLayer": "dxvk",
      "params": {}
    }
  ]
}
EOF
adb push "$STAGE_MOUNT/containers.json" /data/local/tmp/containers.json >/dev/null
in_app "mkdir -p files/datastore && cat /data/local/tmp/containers.json > files/datastore/containers.json"
adb shell "rm -f /data/local/tmp/containers.json"
# An empty params map is deliberate: every value then comes from the manifest
# default, which is the configuration a first-run container actually gets.
ok "container $CONTAINER_ID"

# --- 3. launch straight at the session ------------------------------------------
# MainActivity's openSession extra, which the running-session notification uses
# too. Without it this would be `input tap` against a container list, which is a
# test that breaks whenever the layout moves.
say "3/6  opening the Session screen"
adb shell "am start -n $PKG/app.vessel.MainActivity --es openSession $CONTAINER_ID" >/dev/null \
  || die "could not start MainActivity"
ok "started"

# Provisioning copies FEX and the D3D layers into the prefix and runs wineboot
# twice; on this phone that is minutes, not seconds, on a first run.
say "4/6  waiting for the session to reach Running"
LOG=""
for _ in $(seq 1 90); do
  sleep 5
  LOG="$(in_app "ls -t files/logs/*/*.log 2>/dev/null | head -1")"
  [ -n "$LOG" ] || continue
  if in_app "grep -c 'exec .*explorer' $LOG 2>/dev/null" | grep -qv '^0$'; then break; fi
done
[ -n "$LOG" ] || die "no session log appeared — the launch never got as far as Preparing"
ok "log at $LOG"

# --- 5. run a real windowed program into the app's X server ----------------------
# explorer /desktop= is itself a window, so reaching Running is already the
# headline result. winemine is the second half: a program the app did not start,
# in a process the app does not own, finding the X server through the abstract
# socket. If that draws, the display path is not a special case of the launcher.
say "5/6  running winemine against the app's X server"
PREFIX="files/containers/${CONTAINER_ID}/prefix"
SHM_SOCKET="/data/data/$PKG/files/containers/${CONTAINER_ID}/tmp/.sysvshm/SM0"
GUEST_ENV="cd \$PWD && export \
WINEPREFIX=\$PWD/$PREFIX \
HOME=\$PWD/files/containers/$CONTAINER_ID \
TMPDIR=\$PWD/files/containers/$CONTAINER_ID/tmp \
XDG_RUNTIME_DIR=\$PWD/files/containers/$CONTAINER_ID/tmp \
WINEDLLPATH=\$PWD/$WINE_DIR/lib/wine \
WINENLSDIR=\$PWD/$WINE_DIR/share/wine/nls \
LD_LIBRARY_PATH=\$PWD/$WINE_DIR/lib:\$PWD/$WINE_DIR/lib/wine/aarch64-unix \
WINEDEBUG=-all,err+all,+winediag \
DISPLAY=:0 \
WINE_SYSVSHM_SOCKET=$SHM_SOCKET"
in_app_bg "$GUEST_ENV && timeout 60 /system/bin/linker64 \$PWD/$WINE_DIR/bin/winemine" \
  "files/winemine.log" | tail -20 | sed 's/^/       /' || true

say "6/6  screenshot and verdict"
adb exec-out screencap -p > "$STAGE/screen.png" || die "screencap failed"
ok "screenshot at out/display/screen.png — look at it, that is the actual result"

# nodrv_CreateWindow is the one line that says "there was no display driver".
# Checked across both logs: the app's session log and winemine's own.
COMBINED="$STAGE/combined.log"
in_app "cat $LOG files/winemine.log 2>/dev/null" > "$COMBINED"

if grep -q 'nodrv_CreateWindow' "$COMBINED"; then
  bad "nodrv_CreateWindow is still there — winex11 did not load, or could not open :0"
  grep -n 'nodrv_CreateWindow\|cannot open display\|winex11' "$COMBINED" | head -5 | sed 's/^/       /'
else
  ok "no nodrv_CreateWindow anywhere in the run"
fi

if [ "$MITSHM_EXPECTED" -eq 1 ]; then
  if grep -q 'vessel: MIT-SHM is live' "$COMBINED"; then
    ok "MIT-SHM engaged: $(grep -m1 'vessel: MIT-SHM is live' "$COMBINED")"
  elif grep -q 'vessel: ' "$COMBINED"; then
    bad "MIT-SHM was tried and refused"
    grep -n 'vessel: ' "$COMBINED" | head -5 | sed 's/^/       /'
  else
    bad "patch 0005 printed nothing — WINE_SYSVSHM_SOCKET never reached the process"
  fi
else
  note "MIT-SHM not checked: the installed Wine predates patch 0005"
fi

echo
if [ "$failures" -eq 0 ]; then
  printf '\033[32ma Windows program has a window\033[0m\n'
else
  printf '\033[31m%d check(s) failed\033[0m\n' "$failures"
fi
say "stop the session with:  adb shell am force-stop $PKG"
exit "$failures"
