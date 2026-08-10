#!/usr/bin/env bash
# Phase 0 of docs/LINUX-MODE.md §7: four probes, one day, no product code.
#
#   ./tools/probe/linuxmode.sh
#
# Answers, on the device, as the app's own uid, the four questions the whole
# Ubuntu-vs-bionic-userland decision hangs on:
#
#   1. Does /system/bin/linker64 run glibc's ld.so as a program?   (§1.2)
#   2. Is ptrace of one's own child permitted?                     (§1.3)
#   3. Is overlayfs / userns / mount(2) available?                 (§5.1)
#   4. Can a bionic process map glibc's ld.so and jump to it?      (§1.4 C)
#
# Probes 1 and 4 need real glibc artifacts. Get them with
# ./tools/probe/fetch-glibc.sh, which extracts files from an arm64 Ubuntu image
# without ever running one.
#
# Same shape as tools/probe/build.sh: compile in the container with the NDK the
# shipped components use, run from the host because adb only exists here.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG=app.vessel
OUT="$REPO/out/linuxprobe"
DEV=linuxprobe                    # under the app's data dir, not files/, as mapexec is
mkdir -p "$OUT"

# Docker Desktop cannot mount a Git Bash path; `pwd -W` gives the Windows one.
# Computed here, before anything cds anywhere — mounting a path derived after a
# cd has broken scripts in this repo twice.
REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
OUT_MOUNT="$(cd "$OUT" && { pwd -W 2>/dev/null || pwd; })"

# Git Bash rewrites Unix-looking absolute paths before adb.exe sees them, which
# silently misdirects a push or a shell command.
adb() { MSYS_NO_PATHCONV=1 command adb "$@"; }

say()  { printf '\n\033[34m==>\033[0m %s\n' "$*"; }
head2(){ printf '\n\033[1m--- %s\033[0m\n' "$*"; }
die()  { printf '\n\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"

[ -f "$OUT/rootfs/lib/ld-linux-aarch64.so.1" ] \
  || die "no glibc artifacts in $OUT/rootfs — run ./tools/probe/fetch-glibc.sh first"

say "compiling (NDK, in vessel-build)"
MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v "$OUT_MOUNT:/out" \
  -v vessel-work:/work vessel-build:latest bash -c '
    set -euo pipefail
    . /src/build/common.sh
    vessel_init
    setup_ndk
    for p in glibcload ptraceprobe nsprobe; do
      "$NDK_CC" -O1 -Wall -Wextra -o "/out/$p" "/src/tools/probe/$p.c"
      file "/out/$p"
    done'

say "pushing"
adb shell "run-as $PKG sh -c 'mkdir -p $DEV/rootfs/lib $DEV/rootfs/usr/lib/aarch64-linux-gnu $DEV/rootfs/bin'"
push_into_app() {  # host file -> app data dir. cp keeps the shell_data_file
  local src="$1" dst="$2"      # SELinux label and the app then cannot exec it;
  adb push "$src" /data/local/tmp/_lmp >/dev/null   # cat re-creates it labelled
  adb shell "chmod 644 /data/local/tmp/_lmp"        # app_data_file.
  adb shell "run-as $PKG sh -c 'cat /data/local/tmp/_lmp > $dst && chmod ${3:-644} $dst'"
}
for p in glibcload ptraceprobe nsprobe; do push_into_app "$OUT_MOUNT/$p" "$DEV/$p" 755; done
push_into_app "$OUT_MOUNT/rootfs/lib/ld-linux-aarch64.so.1" "$DEV/rootfs/lib/ld-linux-aarch64.so.1" 755
for l in "$OUT_MOUNT"/rootfs/usr/lib/aarch64-linux-gnu/*; do
  push_into_app "$l" "$DEV/rootfs/usr/lib/aarch64-linux-gnu/$(basename "$l")" 755
done
for b in "$OUT_MOUNT"/rootfs/bin/*; do push_into_app "$b" "$DEV/rootfs/bin/$(basename "$b")" 755; done
adb shell "rm -f /data/local/tmp/_lmp"

DATA=/data/data/$PKG
LD=$DATA/$DEV/rootfs/lib/ld-linux-aarch64.so.1
LIBDIR=$DATA/$DEV/rootfs/usr/lib/aarch64-linux-gnu

# Anything that could block would hang adb shell waiting for EOF on stdout, so
# every probe writes to a file the child can hold and the file is cat'd after.
runas() { adb shell "run-as $PKG sh -c '$1 >$DEV/log 2>&1; echo rc=\$?' ; run-as $PKG cat $DEV/log" | tr -d '\r'; }

say "PROBE 1 — does bionic's linker64 run glibc's ld.so as a program?"
head2 "run-as $PKG /system/bin/linker64 $LD --version"
runas "/system/bin/linker64 $LD --version"
head2 "and the denied case for contrast: exec the loader directly out of app storage"
runas "$LD --version"

say "PROBE 2 — ptrace"
head2 "run-as $PKG ./$DEV/ptraceprobe"
runas "/system/bin/linker64 $DATA/$DEV/ptraceprobe"

say "PROBE 3 — overlayfs / userns / mount"
head2 "toybox: grep overlay /proc/filesystems"
adb shell "run-as $PKG grep overlay /proc/filesystems; echo rc=\$?" | tr -d '\r'
head2 "toybox: unshare -Ur true"
adb shell "run-as $PKG unshare -Ur true; echo rc=\$?" | tr -d '\r'
head2 "run-as $PKG ./$DEV/nsprobe"
runas "/system/bin/linker64 $DATA/$DEV/nsprobe $DATA/$DEV"

say "PROBE 4 — in-process ELF loader"
head2 "glibcload $LD --version"
runas "/system/bin/linker64 $DATA/$DEV/glibcload $LD --version"
head2 "glibcload $LD --library-path $LIBDIR <a real glibc program>"
for b in "$OUT_MOUNT"/rootfs/bin/*; do
  n="$(basename "$b")"
  case "$n" in
    echo)  a="hello-from-glibc" ;;
    uname) a="-a" ;;
    ls)    a="-l $DATA/$DEV/rootfs/lib" ;;
    *)     a="" ;;
  esac
  head2 "guest program: $n $a"
  runas "/system/bin/linker64 $DATA/$DEV/glibcload $LD --library-path $LIBDIR $DATA/$DEV/rootfs/bin/$n $a"
done

# The probe can only run through run-as, which has no seccomp filter while the
# app process has one. This counts the syscalls the glibc guest actually issues
# so the set can be checked against that filter — and it is the first number for
# §8 question 5, since interception is what PRoot charges for.
head2 "the same run under its own tracer, for the seccomp and PRoot-cost questions"
runas "VESSEL_STRACE=1 /system/bin/linker64 $DATA/$DEV/glibcload $LD --library-path $LIBDIR $DATA/$DEV/rootfs/bin/ls -l $DATA/$DEV/rootfs/lib"

say "done — results belong in docs/LINUX-MODE.md §8"
