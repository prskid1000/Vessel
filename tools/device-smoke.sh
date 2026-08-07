#!/usr/bin/env bash
# Does our Wine actually run on the phone?
#
#   ./tools/device-smoke.sh
#
# This is the first test that puts the native stack on real hardware. Everything
# before it only proved the components *build*. Run it before writing any more
# launcher code — if Wine cannot start here, no amount of Kotlin will help.
#
# It runs inside the installed app's own uid and data directory, via `run-as`.
# An earlier version ran as the adb shell user out of /data/local/tmp, which is
# simpler and turned out to be untestable: SELinux refuses the shell domain a
# sock_file in shell_data_file, so wineserver could never bind, and the app
# domain — where it is allowed — was never exercised. Testing anywhere other
# than where the code will actually live only proves things about that other
# place.
#
#   PASS = the build is good; anything that then fails in the app is the app's
#          problem (its own exec path, environment, lifecycle).
#   FAIL = the build is wrong, and the app was never going to work.
#
# Requires the debug app installed (`./gradlew :app:installSideloadDebug`),
# because `run-as` only works on a debuggable package.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.vessel
# Relative to the app's home, which is where run-as starts.
APP_DIR=files/smoke
STAGE="$REPO/out/smoke"
mkdir -p "$STAGE"

# Docker Desktop cannot mount a Git Bash path like /c/...; `pwd -W` gives the
# Windows one and is a harmless no-op elsewhere, so this works on Linux too.
STAGE_MOUNT="$(cd "$STAGE" && { pwd -W 2>/dev/null || pwd; })"

# Git Bash also rewrites any argument shaped like a Unix absolute path before a
# native .exe sees it, so `adb push src /data/local/tmp/x` reaches adb as
# `C:/Program Files/Git/data/local/tmp/x` and fails with
#   remote secure_mkdirs() failed: No such file or directory
# while `adb shell "…"` survives, because one quoted argument full of spaces
# does not look like a path. That asymmetry hid the bug for a while: the mkdir
# worked and only the push was misdirected. Turning the rewrite off means local
# paths must be Windows-shaped, which STAGE_MOUNT already is.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
failures=0

# Run a command inside the app's uid, data directory and SELinux domain.
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
adb get-state >/dev/null 2>&1 || die "no device. Plug the phone in and enable USB debugging."
adb shell "pm list packages" | tr -d '\r' | grep -qx "package:$PKG" \
  || die "$PKG is not installed. Run ./gradlew :app:installSideloadDebug first."
in_app 'true' >/dev/null 2>&1 \
  || die "run-as $PKG failed — the installed build is not debuggable."

# Newest by mtime, not first by name. `ls | head -1` sorts alphabetically, so
# with both 10.13 and 11.14 in dist/ it picks 10.13 — "10" sorts before "11" —
# and silently tests the build you just replaced.
WCP="$(ls -t "$REPO"/dist/wine-*.wcp 2>/dev/null | head -1)"
[ -n "$WCP" ] || die "no Wine package in dist/. Run ./build/wine.sh first."
say "package: $(basename "$WCP")"

# --- decompress ----------------------------------------------------------------
# The .wcp is an xz-compressed tar. Only the xz layer is undone here; the tar is
# pushed whole and unpacked on the phone.
#
# It has to work that way because of twelve symlinks — bin/wineboot, winecfg and
# friends all point at bin/wine, and Wine dispatches on argv[0], so they are how
# one binary becomes twelve commands. Windows cannot create them, adb.exe cannot
# even read one back off an NTFS mount ("failed to read all of .../bin/msidb:
# Invalid argument"), and copying them on the device does not work either:
# SELinux denies the app domain getattr on a lnk_file in shell_data_file, so
# `cp -r /data/local/tmp/... files/` drops exactly those twelve entries. Letting
# toybox tar create them directly in app data sidesteps all three.
#
# The xz step stays in the container: Android's toybox has no xz, and the host
# may not either.
TARBALL="$STAGE/wine.tar"
STAMP="$WCP:$(stat -c %Y "$WCP" 2>/dev/null || stat -f %m "$WCP")"
if [ -f "$STAGE/.stamp" ] && [ "$(cat "$STAGE/.stamp")" = "$STAMP" ] && [ -f "$TARBALL" ]; then
  say "reusing decompressed $(basename "$WCP")  (rm -rf out/smoke to force)"
else
  say "decompressing $(basename "$WCP")"
  rm -rf "$STAGE"; mkdir -p "$STAGE"
  REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$STAGE_MOUNT:/out" \
    vessel-build:latest bash -c "xz -dc /src/${WCP#"$REPO/"} > /out/wine.tar"
  printf '%s' "$STAMP" > "$STAGE/.stamp"
fi

# The listing is captured before it is searched rather than piped into grep:
# `tar -tf big.tar | grep -q x` looks right and fails, because grep exits at the
# first match, tar takes SIGPIPE, and `set -o pipefail` reports the whole
# pipeline as failed. `|| true` absorbs the same signal from head.
ENTRIES="$(tar -tf "$TARBALL" 2>/dev/null | head -50 || true)"
grep -qx 'bin/wine' <<<"$ENTRIES" \
  || die "no bin/wine in the package — check ./build/wine.sh output"
ok "$(( $(stat -c %s "$TARBALL" 2>/dev/null || stat -f %z "$TARBALL") / 1048576 )) MB tar"

# --- install into the app ------------------------------------------------------
# Staged through /data/local/tmp because adb cannot write into app data. The app
# can read a regular file there (traverse-only on the directory is enough when
# the name is known), so it untars from there straight into its own home.
say "pushing and unpacking as $PKG"
adb shell "rm -f /data/local/tmp/wine.tar"
adb push "$STAGE_MOUNT/wine.tar" /data/local/tmp/wine.tar >/dev/null
in_app "rm -rf $APP_DIR && mkdir -p $APP_DIR && tar -xf /data/local/tmp/wine.tar -C $APP_DIR"
adb shell "rm -f /data/local/tmp/wine.tar"
in_app "test -x $APP_DIR/bin/wine && test -L $APP_DIR/bin/wineboot" \
  || die "unpack lost bin/wine or the argv[0] symlinks"
ok "installed ($(in_app "du -sm $APP_DIR | cut -f1") MB)"

# --- environment ---------------------------------------------------------------
# WINEDLLPATH is not optional here, and the reason is worth knowing. bin/wine is
# built from tools/wine/wine.c, which finds ntdll.so at <libdir>/wine/<arch>-unix
# with libdir derived from /proc/self/exe. We cannot exec bin/wine directly — an
# app at targetSdk 36 may not execve its own files — so we exec the system
# linker and pass the binary as its argument, which makes /proc/self/exe the
# *linker*. Wine then looks in /apex/com.android.runtime/lib/wine and fails.
# WINEDLLPATH is the documented fallback and fixes it outright.
#
# XDG_RUNTIME_DIR and TMPDIR must be inside app data: wineserver binds a socket
# under the prefix, and app_data_file is the one place the app domain is allowed
# to create one.
ENV_PREFIX="cd $APP_DIR && \
export WINEPREFIX=\$PWD/prefix \
XDG_RUNTIME_DIR=\$PWD/run \
TMPDIR=\$PWD/run \
HOME=\$PWD \
WINEDLLPATH=\$PWD/lib/wine WINENLSDIR=\$PWD/share/wine/nls \
WINEDEBUG=-all,err+all,warn+module,+winediag,+loaddll \
LD_LIBRARY_PATH=\$PWD/lib:\$PWD/lib/wine/aarch64-unix && \
mkdir -p run prefix"

WINE="/system/bin/linker64 \$PWD/bin"

say "1/3  does the loader start at all?"
out="$(in_app "$ENV_PREFIX && $WINE/wine --version 2>&1" || true)"
case "$out" in
  wine-*) ok "$out" ;;
  *)      bad "wine --version did not report a version:"
          printf '%s\n' "$out" | sed 's/^/       /' ;;
esac

say "2/3  can wineserver start?"
in_app_bg "$ENV_PREFIX && $WINE/wineserver -p" "$APP_DIR/wineserver.log" | sed 's/^/       /' || true
if in_app "pgrep -f wineserver >/dev/null && echo yes" | grep -q yes; then
  ok "wineserver is running"
else
  bad "wineserver did not stay up"
fi

# The real test. wineboot builds a prefix from scratch: it registers DLLs, writes
# the registry, and exercises ntdll, the PE loader and the ARM64EC path all at
# once. If this works, the stack works.
say "3/3  can it create a prefix? (wineboot --init — the real test)"
# `wineserver -w` is not tidiness. wineboot --init returns as soon as the work is
# queued; the registry only reaches disk when wineserver shuts down, a few
# seconds after its last client leaves. Checking system.reg without waiting reads
# 0 bytes off a prefix that is in fact complete — indistinguishable from the
# failure this script exists to catch, and it cost a round of false alarms.
in_app_bg "$ENV_PREFIX && rm -rf prefix && mkdir -p prefix && timeout 600 $WINE/wineboot --init; $WINE/wineserver -w" \
  "$APP_DIR/wineboot.log" | tail -40 | sed 's/^/       /' || true

# A stub system.reg is written early and proves nothing on its own — the failed
# run left a 646-byte one behind. A real prefix's registry is orders of magnitude
# larger, so the size is the actual signal.
say "verdict"
REG_SIZE="$(in_app "stat -c %s $APP_DIR/prefix/system.reg 2>/dev/null || echo 0")"
if [ "${REG_SIZE:-0}" -gt 100000 ]; then
  ok "system.reg is $((REG_SIZE / 1024)) KB — a real Wine prefix was built on the device"
else
  bad "system.reg is ${REG_SIZE:-0} bytes; the prefix was not built (a stub is written before the registry is populated)"
fi

echo
if [ "$failures" -eq 0 ]; then
  printf '\033[32mall checks passed\033[0m\n'
else
  printf '\033[31m%d check(s) failed\033[0m\n' "$failures"
fi
say "clean up with:  adb shell run-as $PKG rm -rf $APP_DIR"
exit "$failures"
