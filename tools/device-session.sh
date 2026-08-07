#!/usr/bin/env bash
# Run real programs, through all three translation paths, on the phone.
#
#   ./tools/device-session.sh
#
# device-smoke.sh answers "does Wine start and build a prefix". This answers the
# question after it: does an executable actually run, and does FEX translate x86
# for us. It installs Wine and FEX into a prefix under the app's own uid, points
# Wine's emulator registry keys at FEX, then runs the same program compiled three
# ways:
#
#   ARM64    -> Wine directly, no translation
#   x86-64   -> libarm64ecfex.dll, via ARM64EC
#   x86-32   -> libwow64fex.dll, via WoW64
#
# Everything is a console program, so none of it needs a display or an X server.
# That is the point: it isolates the CPU story from the graphics story, which are
# otherwise two unknowns failing into one symptom.
#
# Requires the debug app installed (run-as needs a debuggable package) and
# ./build/wine.sh + ./build/fex.sh to have produced packages in dist/.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.vessel
APP_DIR=files/session
STAGE="$REPO/out/session"
mkdir -p "$STAGE"

REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"

# See tools/device-smoke.sh: Git Bash rewrites Unix-looking absolute paths before
# adb.exe sees them, which silently misdirects a push.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
failures=0

in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

# For anything that forks a server. `adb shell` waits for EOF on stdout, and a
# daemon keeps that descriptor open, so the call never returns. This was
# invisible until patches/wine/0004 landed: before it, wineserver died on
# l_intl.nls and the pipe closed with it. Sending output to a file the daemon
# can hold for as long as it likes, then reading the file, breaks the wait.
#
# The subshell is required, not stylistic: a brace group does not fork, so the
# `cd` every caller starts with would leak out and `cat` would look for the log
# under the app directory twice over.
#
# $2 is a log path relative to the app home.
in_app_bg() {
  adb shell "run-as $PKG sh -c '( $1 ) > $2 2>&1 </dev/null; cat $2'" | tr -d '\r'
}

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app 'true' >/dev/null 2>&1 || die "run-as $PKG failed — is the debug build installed?"

# Newest by mtime: alphabetically wine-10.13 sorts before wine-11.14.
WINE_WCP="$(ls -t "$REPO"/dist/wine-*.wcp 2>/dev/null | head -1)"
FEX_WCP="$(ls -t "$REPO"/dist/fex-*.wcp 2>/dev/null | head -1)"
[ -n "$WINE_WCP" ] || die "no Wine package in dist/"
[ -n "$FEX_WCP" ]  || die "no FEX package in dist/"
say "wine: $(basename "$WINE_WCP")   fex: $(basename "$FEX_WCP")"

# --- build the three test programs ---------------------------------------------
# llvm-mingw covers all three targets, so one source proves all three paths and
# any difference in the output is the translation, not the program.
say "compiling hello.c for aarch64, x86_64 and i686"
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
  -v vessel-work:/work vessel-build:latest bash -c '
    set -euo pipefail
    . /src/build/common.sh >/dev/null
    vessel_init >/dev/null
    setup_mingw >/dev/null
    for t in aarch64 x86_64 i686; do
      "$MINGW_BIN/$t-w64-mingw32-clang" -O2 -o "/out/hello-$t.exe" /src/tools/hello.c
    done
    ls -l /out/*.exe' >/dev/null || die "cross-compiling the test programs failed"
for t in aarch64 x86_64 i686; do
  [ -f "$STAGE/hello-$t.exe" ] || die "no hello-$t.exe"
done
ok "three test executables"

# --- unpack Wine + FEX ---------------------------------------------------------
# Same reasoning as device-smoke.sh: xz in the container, tar on the phone so the
# argv[0] symlinks survive.
say "unpacking packages"
WINE_STAMP="$WINE_WCP:$(stat -c %Y "$WINE_WCP")"
if [ "$(cat "$STAGE/.wine-stamp" 2>/dev/null)" != "$WINE_STAMP" ] || [ ! -f "$STAGE/wine.tar" ]; then
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
    vessel-build:latest bash -c "xz -dc /src/${WINE_WCP#"$REPO/"} > /out/wine.tar"
  printf '%s' "$WINE_STAMP" > "$STAGE/.wine-stamp"
fi
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
  vessel-build:latest bash -c "xz -dc /src/${FEX_WCP#"$REPO/"} > /out/fex.tar"
ok "decompressed"

say "installing into the app sandbox"
adb shell "rm -f /data/local/tmp/wine.tar /data/local/tmp/fex.tar"
adb push "$STAGE_MOUNT/wine.tar" /data/local/tmp/wine.tar >/dev/null
adb push "$STAGE_MOUNT/fex.tar"  /data/local/tmp/fex.tar  >/dev/null
in_app "rm -rf $APP_DIR && mkdir -p $APP_DIR/fex && \
        tar -xf /data/local/tmp/wine.tar -C $APP_DIR && \
        tar -xf /data/local/tmp/fex.tar -C $APP_DIR/fex"
adb shell "rm -f /data/local/tmp/wine.tar /data/local/tmp/fex.tar"
in_app "test -f $APP_DIR/fex/libarm64ecfex.dll && test -f $APP_DIR/fex/libwow64fex.dll" \
  || die "the FEX package did not contain both DLLs"
ok "installed"

# --- environment ---------------------------------------------------------------
# WINEDLLPATH and WINENLSDIR both exist because the linker exec model breaks
# self-location; see tools/device-smoke.sh and patches/wine/0004.
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
WINE="/system/bin/linker64 \$PWD/bin"

say "1/6  building the prefix"
# wineserver -w waits for the registry to reach disk. wineboot returns before
# wineserver has written it, so the size check below would read 0 on a prefix
# that is perfectly good.
in_app_bg "$ENV_PREFIX && rm -rf prefix && mkdir -p prefix && timeout 600 $WINE/wineboot --init; $WINE/wineserver -w" \
  "$APP_DIR/wineboot.log" | tail -5 | sed 's/^/       /' || true
REG_SIZE="$(in_app "stat -c %s $APP_DIR/prefix/system.reg 2>/dev/null || echo 0" | tr -cd '0-9')"
if [ "${REG_SIZE:-0}" -gt 100000 ]; then
  ok "prefix built (system.reg $((REG_SIZE / 1024)) KB)"
else
  die "no usable prefix (system.reg ${REG_SIZE:-0} bytes) — run ./tools/device-smoke.sh first"
fi

# --- install FEX ----------------------------------------------------------------
# Order is deliberate. Once HKLM\Software\Microsoft\Wow64\amd64 names a DLL,
# load_arm64ec_module() runs in LdrInitializeThunk -- before kernel32 -- and
# NtTerminateProcess'es the process if that DLL is missing. So the files go in
# first and the keys are written second; the reverse bricks every process in the
# prefix, including native ARM64 ones.
say "2/6  installing FEX and pointing Wine at it"
in_app "cp $APP_DIR/fex/libarm64ecfex.dll $APP_DIR/fex/libwow64fex.dll \
        $APP_DIR/prefix/drive_c/windows/system32/"
# Written here and pushed rather than built with a heredoc on the device:
# toybox sh puts heredoc bodies in a temp file, and with TMPDIR unset it tries
# /data/local, which the app may not write —
#   sh: can't create temporary file /data/local/xxxx.tmp: Permission denied
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
# only the server's shutdown puts it on disk. Reading system.reg without waiting
# reports the keys as missing on a prefix where they were applied fine.
in_app_bg "$ENV_PREFIX && $WINE/regedit \$PWD/fex.reg; $WINE/wineserver -w"   "$APP_DIR/regedit.log" | tail -3 | sed 's/^/       /' || true
# Read it back rather than trusting regedit's exit code: a silently unapplied key
# shows up much later as "xtajit64.dll not found" and looks like a Wine bug.
if in_app "grep -c 'libarm64ecfex' $APP_DIR/prefix/system.reg" | grep -qv '^0$'; then
  ok "emulator keys are in system.reg"
else
  bad "the FEX registry keys did not apply"
fi

# The prefix is booted a second time on purpose. The first boot ran with no
# HKLM\Software\Microsoft\Wow64 value, so Wine fell back to xtajit.dll --
# which this package does not contain, only xtajit64.dll -- could not bring up a
# 32-bit world, and left C:\windows\syswow64 empty while system32 got its 812
# entries. Any i386 program then dies with
#   wine: could not load kernel32.dll, status c0000135
# Now that libwow64fex.dll is in place and the key points at it, --update
# populates syswow64. The order cannot be reversed: writing the key before the
# DLL exists makes every process in the prefix terminate during ntdll init.
say "3/6  re-running wineboot so the 32-bit side initialises"
# Twice, and deleting .update-timestamp each time, both of which are load-bearing:
#
#   - wineboot compares that stamp against wine.inf and skips everything when it
#     matches, so a plain --update does nothing at all here.
#   - measured on device, one forced pass still leaves syswow64 empty and a
#     second identical pass fills it with 885 entries. What the first pass
#     establishes that the second needs is not yet understood.
for pass in 1 2; do
  in_app_bg "$ENV_PREFIX && rm -f prefix/.update-timestamp && timeout 600 $WINE/wineboot --update; $WINE/wineserver -w" \
    "$APP_DIR/wineboot-update-$pass.log" | tail -2 | sed 's/^/       /' || true
done
# `wc -l` pads its output, and a padded string is not an integer to `-gt`.
WOW_COUNT="$(in_app "ls $APP_DIR/prefix/drive_c/windows/syswow64 2>/dev/null | wc -l" | tr -cd '0-9')"
if [ "${WOW_COUNT:-0}" -gt 100 ]; then
  ok "syswow64 has $WOW_COUNT entries"
else
  bad "syswow64 has ${WOW_COUNT:-0} entries; 32-bit programs cannot load"
fi

# --- the three paths ------------------------------------------------------------
# Pushed only now, after the prefix exists, so a failed bootstrap cannot be
# mistaken for a translation failure.
say "staging the test executables"
for t in aarch64 x86_64 i686; do
  adb push "$STAGE_MOUNT/hello-$t.exe" "/data/local/tmp/hello-$t.exe" >/dev/null
  in_app "cat /data/local/tmp/hello-$t.exe > $APP_DIR/hello-$t.exe"
done
adb shell "rm -f /data/local/tmp/hello-aarch64.exe /data/local/tmp/hello-x86_64.exe /data/local/tmp/hello-i686.exe"
ok "staged"

# sum(i*i) for i in 1..100000 = n(n+1)(2n+1)/6 = 333338333350000. Checking the
# number rather than just "it printed something" is what makes this a test of
# translation and not of process startup.
run_case() {
  local label="$1" exe="$2" expect_bits="$3" out
  say "$label"
  out="$(in_app_bg "$ENV_PREFIX && timeout 180 $WINE/wine \$PWD/$exe" "$APP_DIR/run.log" || true)"
  printf '%s\n' "$out" | sed 's/^/       /'
  if grep -q "VESSEL-OK bits=$expect_bits sum=333338333350000" <<<"$out"; then
    ok "$label ran and computed correctly"
  else
    bad "$label did not produce the expected output"
  fi
}

run_case "4/6  ARM64 — no translation"      hello-aarch64.exe 64
run_case "5/6  x86-64 — via libarm64ecfex"  hello-x86_64.exe  64
run_case "6/6  x86-32 — via WoW64/libwow64fex" hello-i686.exe 32

say "shutting the container down"
in_app "$ENV_PREFIX && $WINE/wineserver -k" >/dev/null 2>&1 || true

echo
if [ "$failures" -eq 0 ]; then
  printf '\033[32mall three translation paths work\033[0m\n'
else
  printf '\033[31m%d of 3 paths failed\033[0m\n' "$failures"
fi
say "clean up with:  adb shell run-as $PKG rm -rf $APP_DIR"
exit "$failures"
