#!/usr/bin/env bash
# Shared harness for every Vessel component build.
#
# Sourced, never executed. Provides:
#   - configuration loading (native/pins.env + build/targets/<target>.env)
#   - logging
#   - toolchain discovery and version enforcement
#   - compiler flag probing (never silently drops a tuning flag)
#   - source fetch + pinned checkout + patch application
#   - build provenance recording, which ends up inside the .wcp
#
# Design note: flags are *probed*, not assumed. If a toolchain does not accept
# -mcpu=oryon-1 we fall back and say so loudly, because a build that silently
# loses its chip tuning is worse than one that fails.

set -euo pipefail

# --- Paths -------------------------------------------------------------------

COMMON_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$COMMON_SH_DIR/.." && pwd)"
NATIVE_DIR="$REPO_ROOT/native"
PATCH_DIR="$REPO_ROOT/patches"
DIST_DIR="${VESSEL_DIST_DIR:-$REPO_ROOT/dist}"

# Object files go somewhere fast. On Windows/WSL this should be a Linux
# filesystem or a Docker volume, never /mnt/c — writing tens of thousands of
# object files across the 9p boundary is what makes builds take an hour.
WORK_DIR="${VESSEL_WORK_DIR:-/tmp/vessel-build}"

mkdir -p "$DIST_DIR" "$WORK_DIR"

# --- Logging -----------------------------------------------------------------

if [ -t 1 ]; then
  _C_RESET=$'\033[0m'; _C_DIM=$'\033[2m'; _C_BLU=$'\033[34m'
  _C_YEL=$'\033[33m'; _C_RED=$'\033[31m'; _C_GRN=$'\033[32m'
else
  _C_RESET=''; _C_DIM=''; _C_BLU=''; _C_YEL=''; _C_RED=''; _C_GRN=''
fi

log()  { printf '%s==>%s %s\n' "$_C_BLU" "$_C_RESET" "$*"; }
info() { printf '%s  - %s%s\n' "$_C_DIM" "$*" "$_C_RESET"; }
ok()   { printf '%s  ok%s %s\n' "$_C_GRN" "$_C_RESET" "$*"; }
warn() { printf '%swarn:%s %s\n' "$_C_YEL" "$_C_RESET" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "$_C_RED" "$_C_RESET" "$*" >&2; exit 1; }

# --- Configuration -----------------------------------------------------------

load_config() {
  [ -f "$NATIVE_DIR/pins.env" ] || die "missing $NATIVE_DIR/pins.env"
  # shellcheck disable=SC1091
  . "$NATIVE_DIR/pins.env"

  local target="${VESSEL_TARGET:-${TARGET:-canoe}}"
  local target_file="$COMMON_SH_DIR/targets/$target.env"
  [ -f "$target_file" ] || die "unknown target '$target' (no $target_file)"
  # shellcheck disable=SC1090
  . "$target_file"

  info "target: $TARGET_NAME — $TARGET_DESC"
}

# --- Toolchain ---------------------------------------------------------------

# The Android NDK, for everything that runs as a Linux/bionic ELF on the device
# (Box64, Turnip).
setup_ndk() {
  ANDROID_NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
  [ -n "$ANDROID_NDK" ] || die "ANDROID_NDK_HOME is not set (the Docker image sets it)"
  [ -d "$ANDROID_NDK" ] || die "ANDROID_NDK_HOME points at a missing directory: $ANDROID_NDK"

  local host_tag=linux-x86_64
  NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$host_tag/bin"
  [ -d "$NDK_BIN" ] || die "no toolchain at $NDK_BIN"

  # Compile against TARGET_NDK_API, not the device's API level — an NDK only
  # ships wrappers and sysroots for the API levels it knows about, which lags
  # the newest Android release.
  local api="${TARGET_NDK_API:-$TARGET_API}"
  NDK_CC="$NDK_BIN/aarch64-linux-android${api}-clang"
  NDK_CXX="$NDK_BIN/aarch64-linux-android${api}-clang++"

  if [ ! -x "$NDK_CC" ]; then
    local available
    available="$(ls "$NDK_BIN" 2>/dev/null \
      | grep -E '^aarch64-linux-android[0-9]+-clang$' \
      | sed -E 's/^aarch64-linux-android([0-9]+)-clang$/\1/' \
      | sort -n | tr '\n' ' ')"
    die "no compiler for API $api in this NDK.
     Available API levels: ${available:-none found}
     Set TARGET_NDK_API in build/targets/$TARGET_NAME.env to one of those, or
     bump ANDROID_NDK_VERSION in native/pins.env to an NDK that has $api."
  fi

  NDK_API="$api"
  export NDK_API

  require_clang_major "$NDK_CC" 19 \
    "-mtune=oryon-1 requires clang >= 19; NDK r26/r27 ship clang 18. Bump ANDROID_NDK_VERSION in native/pins.env to r28 or newer."

  info "ndk: $(basename "$ANDROID_NDK")  clang $(clang_version "$NDK_CC")  api $NDK_API"
}

# llvm-mingw, for everything that is a Windows PE inside the container
# (FEX's DLLs, Wine ARM64EC, DXVK, vkd3d).
setup_mingw() {
  LLVM_MINGW="${LLVM_MINGW_HOME:-}"
  [ -n "$LLVM_MINGW" ] || die "LLVM_MINGW_HOME is not set (the Docker image sets it)"
  [ -d "$LLVM_MINGW/bin" ] || die "no llvm-mingw at $LLVM_MINGW/bin"

  MINGW_BIN="$LLVM_MINGW/bin"
  export PATH="$MINGW_BIN:$PATH"

  # ARM64EC is what makes native-Wine-plus-translated-app possible. If the
  # toolchain cannot target it, stop now rather than producing a build that
  # quietly falls back to plain aarch64.
  if [ ! -x "$MINGW_BIN/arm64ec-w64-mingw32-clang" ]; then
    die "this llvm-mingw has no arm64ec target. Vessel needs an ARM64EC-capable
     toolchain. Set LLVM_MINGW_URL to a build that provides
     arm64ec-w64-mingw32-clang (see docs/BUILDING.md)."
  fi

  info "llvm-mingw: $("$MINGW_BIN/aarch64-w64-mingw32-clang" --version | head -1)"
}

clang_version() { "$1" -dumpversion 2>/dev/null | head -1; }

require_clang_major() {
  local cc="$1" want="$2" msg="$3"
  local have; have="$(clang_version "$cc")"; have="${have%%.*}"
  [ -n "$have" ] || die "could not determine clang version for $cc"
  [ "$have" -ge "$want" ] || die "clang $have is too old (need >= $want). $msg"
}

# --- Compiler flag probing ---------------------------------------------------

# Returns 0 if the compiler accepts the flag. Used so tuning is either applied
# or reported — never dropped in silence.
probe_cflag() {
  local cc="$1" flag="$2"
  local probe="$WORK_DIR/.probe-$$.c" obj="$WORK_DIR/.probe-$$.o"
  printf 'int main(void){return 0;}\n' > "$probe"
  local rc=0
  # shellcheck disable=SC2086
  "$cc" $flag -c "$probe" -o "$obj" >/dev/null 2>&1 || rc=1
  rm -f "$probe" "$obj"
  return $rc
}

# Chooses the best accepted CPU tuning for a given compiler and exports
# VESSEL_CPU_FLAGS. Prefers -mcpu=oryon-1; falls back to -march/-mtune.
resolve_cpu_flags() {
  local cc="$1"
  local chosen=""

  if [ -n "${TARGET_MCPU:-}" ] && probe_cflag "$cc" "-mcpu=$TARGET_MCPU"; then
    chosen="-mcpu=$TARGET_MCPU"
    ok "cpu tuning: $chosen"
  elif probe_cflag "$cc" "-march=$TARGET_MARCH -mtune=$TARGET_MTUNE"; then
    chosen="-march=$TARGET_MARCH -mtune=$TARGET_MTUNE"
    warn "-mcpu=$TARGET_MCPU not accepted; using $chosen"
  elif probe_cflag "$cc" "-march=$TARGET_MARCH"; then
    chosen="-march=$TARGET_MARCH"
    warn "-mtune not accepted; using $chosen (tuning reduced)"
  else
    warn "no chip-specific flags accepted by $cc — building generic ARM64"
    chosen=""
  fi

  VESSEL_CPU_FLAGS="$chosen"
  export VESSEL_CPU_FLAGS
}

# --- Source management -------------------------------------------------------

# Clone (or update) a component to its pinned ref and apply our patches.
# Idempotent: safe to re-run, always ends at exactly the pinned ref.
fetch_source() {
  local name="$1" repo="$2" ref="$3"
  local dir="$NATIVE_DIR/$name"

  if [ ! -d "$dir/.git" ]; then
    log "cloning $name from $repo"
    git clone --recurse-submodules "$repo" "$dir"
  else
    info "fetching $name"
    git -C "$dir" fetch --all --tags --prune
  fi

  log "checking out $name @ $ref"
  git -C "$dir" checkout --force "$ref"
  # Branch pins need an explicit fast-forward; tag pins are already exact.
  if git -C "$dir" symbolic-ref -q HEAD >/dev/null 2>&1; then
    git -C "$dir" reset --hard "origin/$ref"
  fi
  git -C "$dir" submodule update --init --recursive

  apply_patches "$name"

  SOURCE_SHA="$(git -C "$dir" rev-parse HEAD)"
  export SOURCE_SHA
  info "$name at ${SOURCE_SHA:0:12}"
}

# Patches are applied on top of the pinned ref, in filename order. A patch that
# does not apply is a hard error: a half-patched build is not reproducible.
apply_patches() {
  local name="$1"
  local dir="$NATIVE_DIR/$name" pdir="$PATCH_DIR/$name"
  [ -d "$pdir" ] || return 0

  local applied=0
  for p in "$pdir"/*.patch; do
    [ -e "$p" ] || continue
    info "applying $(basename "$p")"
    git -C "$dir" apply --check "$p" \
      || die "patch does not apply: $p (the pinned ref probably moved)"
    git -C "$dir" apply "$p"
    applied=$((applied + 1))
  done
  [ "$applied" -eq 0 ] || ok "$applied patch(es) applied to $name"
}

# --- Provenance --------------------------------------------------------------

# Everything needed to explain or reproduce a build, embedded into the .wcp so
# a package on the phone can always answer "what exactly are you?".
write_provenance() {
  local outfile="$1" name="$2" version="$3"
  cat > "$outfile" <<EOF
{
  "component": "$name",
  "version": "$version",
  "target": "$TARGET_NAME",
  "targetDesc": "$TARGET_DESC",
  "sourceRef": "${COMPONENT_REF:-unknown}",
  "sourceSha": "${SOURCE_SHA:-unknown}",
  "cpuFlags": "${VESSEL_CPU_FLAGS:-none}",
  "ndk": "${ANDROID_NDK_VERSION:-n/a}",
  "apiLevel": "${NDK_API:-${TARGET_NDK_API:-n/a}}",
  "builtBy": "vessel-build"
}
EOF
}

# --- Misc --------------------------------------------------------------------

nproc_safe() { nproc 2>/dev/null || echo 4; }

# Every component script starts here.
vessel_init() {
  load_config
}
