#!/usr/bin/env bash
# Ask the phone which Vulkan driver actually answers — with and without ours.
#
#   ./tools/device-vulkan.sh              # build, push, run
#   ./tools/device-vulkan.sh --reuse      # skip the container build
#
# This is the ground truth for the single claim this project most easily gets
# wrong. `dist/turnip-*.wcp` installing successfully says nothing about whether
# Turnip runs: it is an Android Vulkan HAL with one exported symbol, so it is
# reachable only through the platform loader with libadrenotools' hook in front
# of it, and every failure in that chain ends with the stock Qualcomm driver
# answering silently.
#
# The probe runs as the app's uid, in a plain process started through
# /system/bin/linker64 — deliberately the same shape as the Wine unix side,
# because libadrenotools' whole mechanism is linker-namespace surgery and "it
# works inside the app's ART process" is not evidence about a process that has
# no ART. It exits non-zero unless Mesa/Turnip is what replied.
#
# Requires the debug app installed (run-as needs a debuggable package), Docker,
# and ./build/turnip.sh to have produced dist/turnip-*.wcp.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.vessel
APP_DIR=files/vkprobe
STAGE="$REPO/out/vulkan"

REPO_MOUNT="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
# The local side of `adb push` has to be a Windows path, because the wrapper
# below turns Git Bash's path rewriting off wholesale and adb.exe cannot stat a
# /c/... path. Same pair as every other tool here.
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

# `in_app` ends in a pipe, so its status is `tr`'s and is always 0. Conditions
# travel back as text instead. Getting this wrong here would make the script
# claim the driver loaded when the stock blob answered, which is the one thing it
# exists to rule out.
in_app_test() { [ "$(in_app "if $1; then echo yes; fi")" = yes ]; }

REUSE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --reuse) REUSE=1; shift ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v adb >/dev/null || die "adb not on PATH"
adb get-state >/dev/null 2>&1 || die "no device"
in_app_test 'true' || die "run-as $PKG failed — is the debug build installed?"

# Newest by mtime rather than by name: version strings do not sort.
TURNIP_WCP="$(ls -t "$REPO"/dist/turnip-*.wcp 2>/dev/null | head -1 || true)"
[ -n "$TURNIP_WCP" ] || die "no turnip package in dist/ — run ./build/turnip.sh"

if [ "$REUSE" -eq 0 ]; then
  say "building the probe and libadrenotools ($(basename "$TURNIP_WCP"))"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" -v vessel-work:/work \
    vessel-build:latest bash -c \
    "cd /src && VESSEL_WORK_DIR=/work ./tools/vulkan/build.sh" >/dev/null \
    || die "tools/vulkan/build.sh failed"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO_MOUNT:/src" vessel-build:latest bash -c \
    "xz -dc /src/dist/$(basename "$TURNIP_WCP") > /src/out/vulkan/turnip.tar" \
    || die "could not decompress $(basename "$TURNIP_WCP")"
  ok "built"
fi

# `-f`, not `-x`. The build runs in a container writing through an NTFS bind
# mount, which has no execute bit to set, so the staged probe always comes back
# 0644 on Windows and `-x` reported a missing file that was sitting right there.
# The mode that matters is the one applied on the device, below.
[ -f "$STAGE/vkdriverprobe" ] || die "no $STAGE/vkdriverprobe — run without --reuse once"

say "staging into the app sandbox"
adb shell "rm -rf /data/local/tmp/vkprobe && mkdir -p /data/local/tmp/vkprobe"
for f in "$STAGE"/*.so "$STAGE"/vkdriverprobe "$STAGE"/turnip.tar; do
  [ -f "$f" ] || continue
  n="$(basename "$f")"
  adb push "$STAGE_MOUNT/$n" "/data/local/tmp/vkprobe/$n" >/dev/null
done

# Copied with `cat` rather than `cp`: the shell user can write /data/local/tmp
# and the app can read it, but only the app can create files under its own
# directory, and `run-as cp` from one to the other keeps the source's SELinux
# label. A file labelled shell_data_file cannot be dlopen'ed by the app.
in_app "rm -rf $APP_DIR && mkdir -p $APP_DIR/turnip"
in_app "for f in /data/local/tmp/vkprobe/*.so /data/local/tmp/vkprobe/vkdriverprobe; do \
        cat \$f > $APP_DIR/\$(basename \$f); done; chmod 755 $APP_DIR/*; \
        tar -xf /data/local/tmp/vkprobe/turnip.tar -C $APP_DIR/turnip"
adb shell "rm -rf /data/local/tmp/vkprobe"

in_app_test "test -f $APP_DIR/turnip/libvulkan_freedreno.so" \
  || die "the turnip package contained no libvulkan_freedreno.so"
in_app_test "test -f $APP_DIR/libmain_hook.so" \
  || die "libmain_hook.so did not reach the device"
ok "staged"

# --- also check the APK's own copy -------------------------------------------
# The probe below runs against the copies just pushed, which proves the *code*
# works. What ships is the APK's copy in nativeLibraryDir, and that is a
# different question: it depends on jniLibs.useLegacyPackaging being on, which is
# exactly the setting whose absence makes libadrenotools fail silently.
say "checking the APK's own hook libraries"
APK_DIR="$(adb shell "pm path $PKG" 2>/dev/null | tr -d '\r' | head -1 | sed 's|^package:||;s|/[^/]*$||' || true)"
HOOKS="$APK_DIR/lib/arm64"
if [ -n "$APK_DIR" ] && in_app_test "test -f $HOOKS/libmain_hook.so" \
   && in_app_test "test -f $HOOKS/libadrenotools.so" \
   && in_app_test "test -f $HOOKS/libc++_shared.so"; then
  ok "$HOOKS has libadrenotools.so, libmain_hook.so and libc++_shared.so"
else
  warn "the installed APK has no extracted hook libraries in $HOOKS."
  warn "Either it predates libadrenotools, or jniLibs.useLegacyPackaging is off —"
  warn "in which case the app and the guest will both fall back to the stock driver."
fi

# --- run ----------------------------------------------------------------------
say "asking Vulkan what answers"
OUT="$(in_app "cd \$PWD/$APP_DIR && export LD_LIBRARY_PATH=\$PWD && \
       /system/bin/linker64 \$PWD/vkdriverprobe \$PWD \$PWD/turnip libvulkan_freedreno.so" \
       2>&1 || true)"
printf '%s\n' "$OUT" | sed 's/^/  /'

SYSTEM_LINE="$(printf '%s\n' "$OUT" | grep '^VESSEL-VK source=system' | head -1 || true)"
CUSTOM_LINE="$(printf '%s\n' "$OUT" | grep '^VESSEL-VK source=adrenotools' | head -1 || true)"

echo
if [ -z "$CUSTOM_LINE" ]; then
  bad "the probe produced no adrenotools result line at all"
  exit 1
fi

case "$CUSTOM_LINE" in
  *turnip=yes*)
    ok "Mesa/Turnip is what answered"
    printf '\n\033[32mthe custom driver loads on this device\033[0m\n'
    printf '  before: %s\n' "${SYSTEM_LINE#VESSEL-VK }"
    printf '  after:  %s\n' "${CUSTOM_LINE#VESSEL-VK }"
    exit 0 ;;
  *)
    bad "the custom driver did not answer"
    printf '\n\033[31mVulkan calls are going to the stock driver\033[0m\n'
    exit 1 ;;
esac
