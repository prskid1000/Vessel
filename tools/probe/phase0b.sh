#!/usr/bin/env bash
# Phase 0b of docs/LINUX-MODE.md §7: the same glibc loader probe, run from the
# app's own process instead of through `run-as`.
#
#   ./tools/probe/phase0b.sh
#
# Phase 0 established that a bionic process can map Ubuntu's ld.so and run stock
# glibc binaries — but every probe went through `run-as app.vessel`, which is
# u:r:runas_app with Seccomp 0. The app is u:r:untrusted_app with Seccomp 2.
# That gap already produced one misleading Phase 0 result (exec'ing ld.so
# directly "succeeded" under run-as and is denied to the app), so it has to be
# closed by measurement and not by policy inference.
#
# How this gets into the app's domain without an APK change: the debug build is
# debuggable, so `am attach-agent` makes ART dlopen tools/probe/phase0b_agent.c
# inside the running app process. The agent forks and execs /system/bin/sh on
# the script below. A child of the app inherits
#   - the app's SELinux domain, because no type transition is defined for
#     untrusted_app on system_file, and
#   - the app's seccomp filter, because filters survive execve,
# which is exactly the inheritance wineserver already relies on
# (SessionRuntime.kt, WineLaunch.linkerArgv). Both claims are *verified in the
# output*, not assumed: the script prints /proc/self/attr/current and the
# Seccomp lines for the shell, and glibcload prints its own before every run.
#
# Prerequisites: ./tools/probe/fetch-glibc.sh and ./tools/probe/linuxmode.sh
# have run, so /data/data/app.vessel/linuxprobe/rootfs exists. The app must be
# running. Nothing here stops, installs or force-stops anything.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
OUT="$REPO/out/linuxprobe"
DEV=linuxprobe
DATA=/data/data/$PKG
D=$DATA/$DEV
mkdir -p "$OUT"

# Docker Desktop cannot mount a Git Bash path; `pwd -W` gives the Windows one.
# Computed before anything cds anywhere — a mount derived after a cd has broken
# scripts in this repo twice.
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
OUT_MOUNT="$(cd "$OUT" && { pwd -W 2>/dev/null || pwd; })"

adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }
say() { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"

PID="$(adb shell "pidof $PKG" | tr -d '\r' | awk '{print $1}')"
[ -n "$PID" ] || die "$PKG is not running — start it (do not force-stop anything)"
say "app pid $PID"
adb shell "cat /proc/$PID/attr/current; echo; grep -E 'Seccomp|NoNewPrivs' /proc/$PID/status" | tr -d '\r'

say "compiling glibcload + the attach agent (NDK, in vessel-build)"
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$OUT_MOUNT:/out" \
  -v vessel-work:/work vessel-build:latest bash -c '
    set -euo pipefail
    . /src/build/common.sh
    vessel_init
    setup_ndk
    # -fno-stack-protector: the SIGSYS shim runs after glibc has taken
    # TPIDR_EL0, so nothing in this file may read the TLS stack guard.
    "$NDK_CC" -O1 -Wall -Wextra -fno-stack-protector -o /out/glibcload /src/tools/probe/glibcload.c
    "$NDK_CC" -O1 -Wall -Wextra -fPIC -shared -o /out/phase0b_agent.so \
        /src/tools/probe/phase0b_agent.c -llog
    file /out/glibcload /out/phase0b_agent.so'

# The script the agent runs, inside the app. Written here so the whole probe is
# one file to read. Note what it does *first*: prove the inheritance claim.
cat > "$OUT/phase0b_run.sh" <<EOF
D=$D
LD=\$D/rootfs/lib/ld-linux-aarch64.so.1
LIB=\$D/rootfs/usr/lib/aarch64-linux-gnu

echo "### phase 0b: a child of the app process"
echo "shell pid \$\$ ppid \$(cat /proc/self/stat | awk '{print \$4}')"
echo "shell selinux: \$(cat /proc/self/attr/current)"
grep -E 'Uid:|NoNewPrivs:|Seccomp' /proc/self/status
echo

# Phase 0's probe 1 ran its contrast case under run-as, where it *succeeded* and
# nearly inverted the study. Re-run it here, in the domain that matters. This is
# the question that decides whether a distro is reachable at all, because a
# distro is a process tree: the in-process loader starts the first process and
# says nothing about the second.
echo "### can this domain execve out of app_data_file?"
echo "-- a glibc ELF, directly:"      ; \$D/rootfs/bin/echo direct-glibc-exec  ; echo "rc=\$?"
echo "-- a bionic ELF, directly:"     ; \$D/glibcload --selftest >/dev/null 2>&1; echo "rc=\$?"
echo "-- glibc ELF via linker64:"     ; /system/bin/linker64 \$D/rootfs/bin/echo via-linker64; echo "rc=\$?"
echo "-- glibc ld.so via linker64:"   ; /system/bin/linker64 \$LD --version | head -1; echo "rc=\$?"
echo

echo "### glibcload --selftest (domain + syscall matrix)"
/system/bin/linker64 \$D/glibcload --selftest
echo

echo "### ld.so --version"
/system/bin/linker64 \$D/glibcload \$LD --version
echo

for prog in "echo hello-from-glibc" "uname -a" "ls -l \$D/rootfs/lib"; do
  echo "### guest program: \$prog"
  set -- \$prog
  n=\$1; shift
  /system/bin/linker64 \$D/glibcload \$LD --library-path \$LIB \$D/rootfs/bin/\$n "\$@"
  echo
done

# Whatever the three runs above did, do them again with a SIGSYS handler that
# answers a trapped syscall with -ENOSYS. Android traps rather than kills, so
# this is a real question and not a trick: does a glibc userland survive being
# told it is running on a kernel without the calls the filter withholds?
for prog in "echo hello-from-glibc" "uname -a" "ls -l \$D/rootfs/lib"; do
  echo "### guest program WITH SIGSYS SHIM: \$prog"
  set -- \$prog
  n=\$1; shift
  VESSEL_SIGSYS_SHIM=1 /system/bin/linker64 \$D/glibcload \$LD --library-path \$LIB \$D/rootfs/bin/\$n "\$@"
  echo
done

echo "### done"
EOF

say "pushing"
push_into_app() {  # cp/push keeps the shell_data_file label and the app then
  local src="$1" dst="$2"          # cannot exec it; cat re-creates it as
  adb push "$src" /data/local/tmp/_p0b >/dev/null   # app_data_file.
  adb shell "chmod 644 /data/local/tmp/_p0b"
  adb shell "run-as $PKG sh -c 'cat /data/local/tmp/_p0b > $dst && chmod ${3:-644} $dst'"
}
# The agent .so gets a fresh name every run. Overwriting the previous one in
# place does not work: it is still dlopen'd in the live app, so `dlopen` of the
# same path returns the cached handle and Agent_OnAttach is never called again —
# the second run of this script produced no output at all until this was fixed.
AGENT="phase0b_agent_$(date +%s).so"
push_into_app "$OUT_MOUNT/glibcload"        "$DEV/glibcload"        755
push_into_app "$OUT_MOUNT/phase0b_agent.so" "$DEV/$AGENT"           755
push_into_app "$OUT_MOUNT/phase0b_run.sh"   "$DEV/phase0b_run.sh"   644
adb shell "rm -f /data/local/tmp/_p0b"
adb shell "run-as $PKG rm -f $DEV/phase0b_run.sh.out"

say "attaching the agent to pid $PID"
adb shell "am attach-agent $PID $D/$AGENT=$D/phase0b_run.sh" | tr -d '\r'

say "waiting for the run to finish"
for _ in $(seq 1 40); do
  if adb shell "run-as $PKG sh -c 'tail -1 $DEV/phase0b_run.sh.out 2>/dev/null'" \
       | tr -d '\r' | grep -q '### done'; then break; fi
  sleep 1
done

say "output"
adb shell "run-as $PKG cat $DEV/phase0b_run.sh.out" | tr -d '\r'

say "logcat from the agent"
adb logcat -d -s vessel-phase0b | tail -20 | tr -d '\r'

say "done — the result belongs in docs/LINUX-MODE.md, Phase 0b"
