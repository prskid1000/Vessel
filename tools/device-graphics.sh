#!/usr/bin/env bash
# Render a triangle through every graphics API Vessel ships, on the phone.
#
#   ./tools/device-graphics.sh                 # everything
#   ./tools/device-graphics.sh --only d3d12    # one API, still all three arches
#   ./tools/device-graphics.sh --reuse         # skip the prefix bootstrap
#
# device-session.sh proved the CPU story: ARM64, x86-64 and x86-32 programs all
# run and compute correctly. This answers the question after it — does anything
# reach the GPU — and it answers it by *looking at pixels*. Every probe clears a
# 64x64 render target to blue, draws one red triangle, reads the result back and
# checks three pixels by value. A device that was created but never rendered
# fails here, which is the whole point: "D3D11CreateDevice succeeded" is not
# evidence of anything.
#
# What runs today and what does not:
#
#   D3D11, D3D12, D3D10   headless. No window, no display, no X server. These
#                         are real results right now.
#   D3D9, D3D8, OpenGL    need an HWND. Wine has winex11.drv and the X11 client
#                         libraries, but nothing is serving DISPLAY yet, so
#                         these are attempted, fail at window creation, and are
#                         reported BLOCKED rather than FAIL. They should start
#                         passing unchanged once the display path lands.
#   vulkan                not a rendering test — it enumerates, so we know which
#                         driver every other probe was really talking to.
#
# Requires the debug app installed (run-as needs a debuggable package) and
# ./build/{wine,fex,dxvk,vkd3d,zink,turnip}.sh to have produced dist/*.wcp.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.vessel
APP_DIR=files/graphics
STAGE="$REPO/out/graphics"
mkdir -p "$STAGE"

REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"

# See tools/device-smoke.sh: Git Bash rewrites Unix-looking absolute paths before
# adb.exe sees them, which silently misdirects a push.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
warn() { printf '  \033[33mwarn\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

# For anything that forks a server. `adb shell` waits for EOF on stdout, and a
# daemon keeps that descriptor open, so the call never returns. Sending output
# to a file the daemon can hold for as long as it likes, then reading the file,
# breaks the wait. The subshell is required, not stylistic: a brace group does
# not fork, so the `cd` every caller starts with would leak out and `cat` would
# look for the log under the app directory twice over.
#
# $2 is a log path relative to the app home.
in_app_bg() {
  adb shell "run-as $PKG sh -c '( $1 ) > $2 2>&1 </dev/null; cat $2'" | tr -d '\r'
}

# --- arguments -------------------------------------------------------------
# --reuse exists because bootstrapping the prefix is the slow part (two full
# wineboot passes) and is completely independent of the probes. Iterating on one
# API without it means waiting several minutes to re-learn something already
# known.
ONLY=''
REUSE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="${2:-}"; shift 2 ;;
    --reuse) REUSE=1; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app 'true' >/dev/null 2>&1 || die "run-as $PKG failed — is the debug build installed?"

# Newest by mtime: alphabetically wine-10.13 sorts before wine-11.14.
newest() { ls -t "$REPO"/dist/$1-*.wcp 2>/dev/null | head -1; }
WINE_WCP="$(newest wine)"
FEX_WCP="$(newest fex)"
DXVK_WCP="$(newest dxvk)"
VKD3D_WCP="$(newest vkd3d)"
ZINK_WCP="$(newest zink)"
TURNIP_WCP="$(newest turnip)"
for v in WINE FEX DXVK VKD3D ZINK TURNIP; do
  eval "p=\$${v}_WCP"
  [ -n "$p" ] || die "no $(echo "$v" | tr 'A-Z' 'a-z') package in dist/"
done
say "packages"
for p in "$WINE_WCP" "$FEX_WCP" "$DXVK_WCP" "$VKD3D_WCP" "$ZINK_WCP" "$TURNIP_WCP"; do
  printf '       %s\n' "$(basename "$p")"
done

# --- the probe matrix ------------------------------------------------------
# Ordered so the cheapest ground truth comes first: if vulkan reports no
# physical device, every D3D failure below it has the same single cause and the
# rest of the run is noise.
#
#   name  source          expectation
PROBES="vulkan:vkprobe:headless
d3d11:d3d11probe:headless
d3d12:d3d12probe:headless
d3d10:d3d10probe:headless
d3d9:d3d9probe:windowed
d3d8:d3d8probe:windowed
opengl:glprobe:windowed"

# aarch64 is the control: no translation at all, so a difference between it and
# x86_64 is FEX and nothing else. x86_64 and aarch64 both resolve DLLs out of
# system32 (ARM64EC images are what live there); only i686 uses syswow64, which
# is why the 32-bit column is the one that tests a different set of DXVK builds
# rather than just a different CPU path.
TARGETS="aarch64 x86_64 i686"

selected() { [ -z "$ONLY" ] && return 0; case "$1" in *$ONLY*) return 0 ;; esac; return 1; }

# --- build the probes ------------------------------------------------------
say "compiling the probes for aarch64, x86_64 and i686"
PROBE_SRCS="$(printf '%s\n' "$PROBES" | cut -d: -f2 | tr '\n' ' ')"
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
  -v vessel-work:/work vessel-build:latest bash -c "
    set -euo pipefail
    . /src/build/common.sh >/dev/null
    vessel_init >/dev/null
    setup_mingw >/dev/null
    rm -rf /out/probes && mkdir -p /out/probes
    for t in $TARGETS; do
      for p in $PROBE_SRCS; do
        # -ldxguid supplies IID_ID3D12Device and friends; the probes call COM
        # through the C macros, so there is no __uuidof to fall back on.
        \"\$MINGW_BIN/\$t-w64-mingw32-clang\" -O1 -o \"/out/probes/\$p-\$t.exe\" \
          \"/src/tools/gfx/\$p.c\" -I/src/tools/gfx -ldxguid -luuid -lgdi32 -luser32
      done
    done
    tar -cf /out/probes.tar -C /out/probes .
    ls /out/probes | wc -l" >/dev/null || die "cross-compiling the probes failed"
COUNT=0
for t in $TARGETS; do
  for p in $PROBE_SRCS; do
    [ -f "$STAGE/probes/$p-$t.exe" ] || die "no $p-$t.exe"
    COUNT=$((COUNT + 1))
  done
done
ok "$COUNT probe executables"

# --- unpack the packages ----------------------------------------------------
# Same reasoning as device-smoke.sh: xz in the container, tar on the phone so
# the argv[0] symlinks in the Wine package survive.
say "decompressing packages"
unpack() { # $1 = wcp path, $2 = output tar name
  local stamp="$1:$(stat -c %Y "$1")"
  if [ "$(cat "$STAGE/.$2-stamp" 2>/dev/null)" != "$stamp" ] || [ ! -f "$STAGE/$2.tar" ]; then
    MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
      vessel-build:latest bash -c "xz -dc /src/${1#"$REPO/"} > /out/$2.tar"
    printf '%s' "$stamp" > "$STAGE/.$2-stamp"
  fi
}
unpack "$WINE_WCP" wine
unpack "$FEX_WCP" fex
unpack "$DXVK_WCP" dxvk
unpack "$VKD3D_WCP" vkd3d
unpack "$ZINK_WCP" zink
unpack "$TURNIP_WCP" turnip
ok "decompressed"

if [ "$REUSE" -eq 1 ]; then
  in_app "test -s $APP_DIR/prefix/system.reg" \
    || die "--reuse but there is no prefix at $APP_DIR — run without it once"
  say "reusing the existing prefix at $APP_DIR"
else
  say "installing into the app sandbox"
  for t in wine fex dxvk vkd3d zink turnip; do
    adb shell "rm -f /data/local/tmp/$t.tar"
    adb push "$STAGE_MOUNT/$t.tar" "/data/local/tmp/$t.tar" >/dev/null
  done
  # Each component lands in its own directory under pkg/ and is copied into the
  # prefix later, after wineboot has created system32/syswow64. Unpacking
  # straight into the prefix would be undone by the wineboot passes below.
  in_app "rm -rf $APP_DIR && mkdir -p $APP_DIR/fex $APP_DIR/pkg/dxvk $APP_DIR/pkg/vkd3d \
          $APP_DIR/pkg/zink $APP_DIR/turnip && \
          tar -xf /data/local/tmp/wine.tar   -C $APP_DIR && \
          tar -xf /data/local/tmp/fex.tar    -C $APP_DIR/fex && \
          tar -xf /data/local/tmp/dxvk.tar   -C $APP_DIR/pkg/dxvk && \
          tar -xf /data/local/tmp/vkd3d.tar  -C $APP_DIR/pkg/vkd3d && \
          tar -xf /data/local/tmp/zink.tar   -C $APP_DIR/pkg/zink && \
          tar -xf /data/local/tmp/turnip.tar -C $APP_DIR/turnip"
  adb shell "rm -f /data/local/tmp/wine.tar /data/local/tmp/fex.tar /data/local/tmp/dxvk.tar \
             /data/local/tmp/vkd3d.tar /data/local/tmp/zink.tar /data/local/tmp/turnip.tar"
  in_app "test -f $APP_DIR/fex/libarm64ecfex.dll && test -f $APP_DIR/fex/libwow64fex.dll" \
    || die "the FEX package did not contain both DLLs"
  ok "installed"
fi

say "staging the probe executables"
adb shell "rm -f /data/local/tmp/probes.tar"
adb push "$STAGE_MOUNT/probes.tar" /data/local/tmp/probes.tar >/dev/null
in_app "rm -rf $APP_DIR/probes && mkdir -p $APP_DIR/probes && \
        tar -xf /data/local/tmp/probes.tar -C $APP_DIR/probes"
adb shell "rm -f /data/local/tmp/probes.tar"
ok "staged"

# --- environment ------------------------------------------------------------
# WINEDLLPATH and WINENLSDIR both exist because the linker exec model breaks
# self-location; see tools/device-smoke.sh and patches/wine/0004.
#
# WINEDLLOVERRIDES is the line that makes this suite mean anything. Without it
# Wine's builtin d3d9/d3d11/dxgi/opengl32 win the load and the probes measure
# wined3d, which would pass on some of these and tell us nothing about DXVK.
# `n` is native-only on purpose rather than `n,b`: a fallback to the builtin
# would be invisible in the results.
#
# Note what is NOT in the list. d3d10.dll stays builtin because DXVK ships only
# d3d10core.dll — Wine's d3d10 is the wrapper that calls into it, and forcing
# d3d10 native would ask for a file that does not exist.
OVERRIDES='d3d8,d3d9,d3d10core,d3d11,d3d12,d3d12core,dxgi,opengl32=n'

ENV_PREFIX="cd $APP_DIR && \
export WINEPREFIX=\$PWD/prefix \
XDG_RUNTIME_DIR=\$PWD/run \
TMPDIR=\$PWD/run \
HOME=\$PWD \
WINEDLLPATH=\$PWD/lib/wine \
WINENLSDIR=\$PWD/share/wine/nls \
WINEDEBUG=-all,err+all,+winediag \
LD_LIBRARY_PATH=\$PWD/lib:\$PWD/lib/wine/aarch64-unix && \
mkdir -p run prefix"

# Applied only to the probe runs, not to wineboot: forcing native d3d during
# prefix creation would have wineboot's own DLL registration trip over files
# that are not in place yet.
ENV_GFX="export WINEDLLOVERRIDES='$OVERRIDES' \
DXVK_LOG_LEVEL=info \
DXVK_LOG_PATH=none \
VKD3D_DEBUG=warn \
VKD3D_SHADER_DEBUG=warn \
TU_DEBUG=startup \
DISPLAY=:0"

WINE="/system/bin/linker64 \$PWD/bin"

if [ "$REUSE" -eq 0 ]; then
  say "1/6  building the prefix"
  # wineserver -w waits for the registry to reach disk. wineboot returns before
  # wineserver has written it, so the size check below would read 0 on a prefix
  # that is perfectly good.
  in_app_bg "$ENV_PREFIX && rm -rf prefix && mkdir -p prefix && timeout 900 $WINE/wineboot --init; $WINE/wineserver -w" \
    "$APP_DIR/wineboot.log" | tail -5 | sed 's/^/       /' || true
  REG_SIZE="$(in_app "stat -c %s $APP_DIR/prefix/system.reg 2>/dev/null || echo 0" | tr -cd '0-9')"
  if [ "${REG_SIZE:-0}" -gt 100000 ]; then
    ok "prefix built (system.reg $((REG_SIZE / 1024)) KB)"
  else
    die "no usable prefix (system.reg ${REG_SIZE:-0} bytes) — run ./tools/device-smoke.sh first"
  fi

  # Order is deliberate. Once HKLM\Software\Microsoft\Wow64\amd64 names a DLL,
  # load_arm64ec_module() runs in LdrInitializeThunk -- before kernel32 -- and
  # NtTerminateProcess'es the process if that DLL is missing. So the files go in
  # first and the keys are written second; the reverse bricks every process in
  # the prefix, including native ARM64 ones.
  say "2/6  installing FEX and pointing Wine at it"
  in_app "cp $APP_DIR/fex/libarm64ecfex.dll $APP_DIR/fex/libwow64fex.dll \
          $APP_DIR/prefix/drive_c/windows/system32/"
  # Written here and pushed rather than built with a heredoc on the device:
  # toybox sh puts heredoc bodies in a temp file, and with TMPDIR unset it tries
  # /data/local, which the app may not write.
  cat > "$STAGE/fex.reg" <<'EOF'
Windows Registry Editor Version 5.00

[HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\amd64]
@="libarm64ecfex.dll"

[HKEY_LOCAL_MACHINE\Software\Microsoft\Wow64\x86]
@="libwow64fex.dll"
EOF
  adb push "$STAGE_MOUNT/fex.reg" /data/local/tmp/fex.reg >/dev/null
  in_app "cat /data/local/tmp/fex.reg > $APP_DIR/fex.reg"
  adb shell "rm -f /data/local/tmp/fex.reg"
  # wineserver -w again: regedit writes into the running server's registry, and
  # only the server's shutdown puts it on disk.
  in_app_bg "$ENV_PREFIX && $WINE/regedit \$PWD/fex.reg; $WINE/wineserver -w" \
    "$APP_DIR/regedit.log" | tail -3 | sed 's/^/       /' || true
  if in_app "grep -c 'libarm64ecfex' $APP_DIR/prefix/system.reg" | grep -qv '^0$'; then
    ok "emulator keys are in system.reg"
  else
    die "the FEX registry keys did not apply — every 32-bit probe would fail for that reason alone"
  fi

  # Twice, and deleting .update-timestamp each time, both of which are
  # load-bearing: wineboot compares that stamp against wine.inf and skips
  # everything when it matches, and measured on device one forced pass still
  # leaves syswow64 empty while a second identical pass fills it with 885
  # entries. Without syswow64 the i686 half of the matrix cannot even start.
  say "3/6  re-running wineboot so the 32-bit side initialises"
  for pass in 1 2; do
    in_app_bg "$ENV_PREFIX && rm -f prefix/.update-timestamp && timeout 900 $WINE/wineboot --update; $WINE/wineserver -w" \
      "$APP_DIR/wineboot-update-$pass.log" | tail -2 | sed 's/^/       /' || true
  done
  WOW_COUNT="$(in_app "ls $APP_DIR/prefix/drive_c/windows/syswow64 2>/dev/null | wc -l" | tr -cd '0-9')"
  if [ "${WOW_COUNT:-0}" -gt 100 ]; then
    ok "syswow64 has $WOW_COUNT entries"
  else
    die "syswow64 has ${WOW_COUNT:-0} entries; no 32-bit probe could load"
  fi
fi

# --- install the graphics DLLs ----------------------------------------------
# After the wineboot passes, never before: wineboot --update rewrites
# system32/syswow64 from wine.inf and would put the builtins back over the top.
#
# Only *.dll is copied. The packages also carry .dll.a import libraries, which
# belong to a build machine and would just be clutter in a Windows system
# directory.
say "4/6  installing DXVK, vkd3d and Zink into the prefix"
SYS32="$APP_DIR/prefix/drive_c/windows/system32"
WOW64="$APP_DIR/prefix/drive_c/windows/syswow64"
in_app "cp $APP_DIR/pkg/dxvk/system32/*.dll  $SYS32/ && \
        cp $APP_DIR/pkg/dxvk/syswow64/*.dll  $WOW64/ && \
        cp $APP_DIR/pkg/vkd3d/system32/*.dll $SYS32/ && \
        cp $APP_DIR/pkg/vkd3d/syswow64/*.dll $WOW64/ && \
        cp $APP_DIR/pkg/zink/system32/*.dll  $SYS32/"

# Read the sizes back rather than trusting cp's exit status. A DXVK d3d11.dll is
# ~4 MB and Wine's builtin is ~1.5 MB, so the number alone says which one is
# sitting there — and "the override is set but the file was never replaced" is
# otherwise indistinguishable from "DXVK is broken".
for f in system32/d3d11.dll system32/d3d12core.dll system32/opengl32.dll syswow64/d3d11.dll syswow64/d3d12core.dll; do
  sz="$(in_app "stat -c %s $APP_DIR/prefix/drive_c/windows/$f 2>/dev/null || echo 0" | tr -cd '0-9')"
  printf '       %-28s %s bytes\n' "$f" "${sz:-0}"
done
# Zink ships an ARM64EC opengl32.dll and nothing for i386, so there is no
# 32-bit desktop GL at all. Saying so here means the i686 opengl result reads as
# a packaging gap rather than as a rendering failure.
in_app "test -f $APP_DIR/pkg/zink/syswow64/opengl32.dll" 2>/dev/null \
  || warn "the Zink package has no syswow64/opengl32.dll — 32-bit OpenGL cannot work"
ok "graphics DLLs installed"

# --- Turnip ------------------------------------------------------------------
# Turnip is a bionic ELF that only the Android Vulkan loader can load, and only
# when libadrenotools has hooked android_dlopen_ext to redirect it. Those hooks
# are a native library in the APK. If they are not there, setting
# ADRENOTOOLS_DRIVER_* does nothing at all and the stock Qualcomm driver answers
# every Vulkan call — quietly, which is exactly the failure mode the Winlator
# lineage is known for. So the check is explicit and the answer is printed.
say "5/6  checking whether Turnip can be loaded"
APK_DIR="$(adb shell "pm path $PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's|^package:||;s|/[^/]*$||')"
HOOKS="$APK_DIR/lib/arm64"
if [ -n "$APK_DIR" ] && in_app "test -f $HOOKS/libmain_hook.so" 2>/dev/null; then
  ENV_GFX="$ENV_GFX ADRENOTOOLS_DRIVER_PATH=\$PWD/turnip/ ADRENOTOOLS_HOOKS_PATH=$HOOKS/ ADRENOTOOLS_DRIVER_NAME=libvulkan_freedreno.so"
  ok "libadrenotools hooks found — Turnip will be loaded from $APP_DIR/turnip"
else
  warn "no libadrenotools hooks in $HOOKS — Turnip cannot be loaded and the"
  warn "stock Qualcomm Vulkan driver will answer. The vulkan probe below reports"
  warn "which driver actually replied; treat every adapter string accordingly."
fi

# --- run ---------------------------------------------------------------------
say "6/6  running the probes"

pass=0; mismatch=0; fail=0; blocked=0; missing=0
SUMMARY=""

run_probe() { # $1 = api name, $2 = source stem, $3 = headless|windowed, $4 = target
  local api="$1" stem="$2" kind="$3" target="$4"
  local exe="probes/$stem-$target.exe" out line status
  local bits=64
  [ "$target" = i686 ] && bits=32

  printf '\n  \033[1m%s\033[0m  %s (%s-bit)\n' "$api" "$target" "$bits"

  out="$(in_app_bg "$ENV_PREFIX && $ENV_GFX && timeout 300 $WINE/wine \$PWD/$exe" \
        "$APP_DIR/probe.log" || true)"

  # Info lines first so the adapter and feature level are visible even for a run
  # that then fails; they are usually the most informative part.
  printf '%s\n' "$out" | grep -E 'VESSEL-GFX-INFO' | sed 's/^/       /' || true

  line="$(printf '%s\n' "$out" | grep -E "^VESSEL-GFX api=" | head -1)"
  if [ -z "$line" ]; then
    # No verdict line at all: the process did not reach main, or died. The tail
    # of the raw log is the only evidence, so print it.
    printf '%s\n' "$out" | tail -8 | sed 's/^/       ! /'
    bad "$api/$target produced no result line"
    missing=$((missing + 1))
    SUMMARY="$SUMMARY\n  NO-OUTPUT $api $target"
    return
  fi
  printf '       %s\n' "$line"

  status="$(printf '%s' "$line" | sed -n 's/.*result=\([A-Z-]*\).*/\1/p')"
  case "$status" in
    PASS)
      ok "$api/$target rendered and read back correctly"
      pass=$((pass + 1)); SUMMARY="$SUMMARY\n  PASS      $api $target" ;;
    MISMATCH)
      # The interesting failure: everything succeeded and the pixels are wrong.
      bad "$api/$target drew the wrong pixels"
      mismatch=$((mismatch + 1)); SUMMARY="$SUMMARY\n  MISMATCH  $api $target" ;;
    BLOCKED)
      printf '  \033[33mblocked\033[0m %s/%s — %s\n' "$api" "$target" \
        "$(printf '%s' "$line" | sed -n 's/.*msg=//p')"
      blocked=$((blocked + 1)); SUMMARY="$SUMMARY\n  BLOCKED   $api $target" ;;
    *)
      printf '%s\n' "$out" | grep -viE 'VESSEL-GFX' | tail -6 | sed 's/^/       ! /' || true
      bad "$api/$target failed"
      fail=$((fail + 1)); SUMMARY="$SUMMARY\n  FAIL      $api $target" ;;
  esac
  [ "$kind" = windowed ] || true
}

for entry in $PROBES; do
  api="${entry%%:*}"; rest="${entry#*:}"
  stem="${rest%%:*}"; kind="${rest#*:}"
  selected "$api" || continue
  for target in $TARGETS; do
    run_probe "$api" "$stem" "$kind" "$target"
  done
done

say "shutting the container down"
in_app "$ENV_PREFIX && $WINE/wineserver -k" >/dev/null 2>&1 || true

# --- summary ------------------------------------------------------------------
echo
printf '\033[1mresults\033[0m'
printf '%b\n' "$SUMMARY"
echo
printf '  %d passed, %d mismatched, %d failed, %d blocked on the display path, %d with no output\n' \
  "$pass" "$mismatch" "$fail" "$blocked" "$missing"

# BLOCKED does not fail the run. It is a missing prerequisite outside the thing
# under test, and making it fatal would mean this script cannot be green until
# the X server lands — at which point nobody would run it, which defeats the
# purpose of having written the windowed probes early.
broken=$((mismatch + fail + missing))
echo
if [ "$broken" -eq 0 ] && [ "$blocked" -eq 0 ]; then
  printf '\033[32mevery graphics path renders correctly\033[0m\n'
elif [ "$broken" -eq 0 ]; then
  printf '\033[32mevery runnable graphics path renders correctly\033[0m (%d blocked on the display path)\n' "$blocked"
else
  printf '\033[31m%d graphics paths are broken\033[0m\n' "$broken"
fi
say "clean up with:  adb shell run-as $PKG rm -rf $APP_DIR"
exit "$broken"
