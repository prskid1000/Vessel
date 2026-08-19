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

# --- The work directory must be case-sensitive -------------------------------

# A case-insensitive WORK_DIR miscompiles, and the error names the wrong thing.
#
# The image sets VESSEL_WORK_DIR=/work (Dockerfile), which is also where the
# repo is usually mounted -- and on a Windows host that bind mount is NTFS,
# which is case-insensitive. libX11 then builds with its own include/X11 on the
# header path, bionic's <locale.h> does #include <xlocale.h>, and that resolves
# to libX11's Xlocale.h rather than the NDK's. locale_t is never declared and
# the NDK's own headers stop parsing:
#
#   locale.h:102:26: error: nullability specifier '_Nonnull' cannot be applied
#                           to non-pointer type 'int'
#   locale.h:101:30: error: unknown type name 'locale_t'
#
# Whatever fails next reports something unrelated -- x11-sysroot.sh blames the
# version pin in native/pins.env -- and none of it points here. Two whole builds
# went into finding it. CI never sees this, because a Linux runner is
# case-sensitive, so nothing upstream of a local build catches it either.
#
# Probed rather than inferred from the platform: a Docker named volume mounted
# at this very path is case-sensitive, so the host OS does not decide it and
# uname cannot answer the question.
_case_probe="$WORK_DIR/.vessel-case-probe"
rm -rf "$_case_probe"
mkdir -p "$_case_probe"
: > "$_case_probe/Xlocale.h"
if [ -e "$_case_probe/xlocale.h" ]; then
  rm -rf "$_case_probe"
  die "the work directory is on a case-insensitive filesystem:

     $WORK_DIR

   libX11 ships include/X11/Xlocale.h, and there the NDK's #include <xlocale.h>
   finds that instead of its own -- so locale_t vanishes and the NDK headers
   stop compiling, several steps before anything blames the right thing.

   Point VESSEL_WORK_DIR at a case-sensitive path. Inside the build image any
   path that is not the bind mount will do, and a named volume also keeps the
   downloaded tarballs between runs:

     docker volume create vessel-work
     docker run --rm -v \"\$(pwd):/work\" -w /work -v vessel-work:/tmp/vessel-build -e VESSEL_WORK_DIR=/tmp/vessel-build vessel-build:latest ./build/turnip.sh"
fi
rm -rf "$_case_probe"
unset _case_probe


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

# `true` / `false` for meson's -Db_lto, from VESSEL_LTO.
#
# Off by default and a switch rather than a constant, for the reason
# docs/OPTIMIZATION.md exists: an optimization nobody benchmarked is a guess
# wearing a commit message, and one nobody can re-test after a toolchain bump is
# a guess that has stopped being falsifiable. FEX is the cautionary case — its
# LTO was off for a judgement that turned out to be right for a reason nobody
# had written down (ARM64EC hybrid symbols do not survive the archive merge).
#
# Mesa is deliberately not wired to this: it refuses LTO by explicit check in
# its own meson.build. That is upstream's decision, not ours to route around.
lto_flag() {
  if [ "${VESSEL_LTO:-0}" = 1 ]; then printf 'true'; else printf 'false'; fi
}

# Vessel: the compiler shims that make meson emit ARM64X instead of ARM64EC.
#
# **A pure ARM64EC DLL cannot be loaded by a native ARM64 process.** It declares
# machine type 0x8664 so x64 loaders take it, and a classic ARM64 loader refuses
# it with STATUS_INVALID_IMAGE_FORMAT -- which is why the ARM64 build of VS Code
# loads Wine's DLLs, all ARM64X, and not one of ours. A prefix has a single
# system32, so the only format that serves both an emulated x64 process and a
# native ARM64 one is the hybrid. docs/ARM64X.md has the whole argument.
#
# Meson has no notion of a hybrid image and links in the same pass it compiles,
# so this goes in the cross file's `c`/`cpp` slot rather than into link args:
# [arm64x-cc.in] passes compiles through untouched and appends the native half on
# a link. Callers build the native tree first and hand its path in here.
#
# Sets ARM64X_CC and ARM64X_CXX.
arm64x_wrappers() {
  local native_build="$1" ec_cc="$2" ec_cxx="$3"
  local here nat_lib tmpl
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  tmpl="$here/arm64x-cc.in"
  nat_lib="$LLVM_MINGW/aarch64-w64-mingw32/lib"

  [ -f "$tmpl" ] || die "arm64x: missing $tmpl"
  # The native CRT is not optional and its absence is silent at configure time:
  # the link fails much later with "DllMainCRTStartup (native symbol)".
  [ -f "$nat_lib/dllcrt2.o" ] || die "arm64x: no native CRT at $nat_lib
     (llvm-mingw must carry the aarch64 target, not only arm64ec)"
  [ -d "$native_build" ] || die "arm64x: native build tree $native_build does not exist
     (build the aarch64 pass before configuring the arm64ec one)"

  ARM64X_CC="$WORK_DIR/arm64x-cc"
  ARM64X_CXX="$WORK_DIR/arm64x-cxx"

  sed -e "s|@REAL@|$ec_cc|" -e "s|@NATIVE_BUILD@|$native_build|"       -e "s|@NATIVE_LIB@|$nat_lib|" -e "s|@OBJDUMP@|$MINGW_BIN/llvm-objdump|"       "$tmpl" > "$ARM64X_CC"
  sed -e "s|@REAL@|$ec_cxx|" -e "s|@NATIVE_BUILD@|$native_build|"       -e "s|@NATIVE_LIB@|$nat_lib|" -e "s|@OBJDUMP@|$MINGW_BIN/llvm-objdump|"       "$tmpl" > "$ARM64X_CXX"
  chmod +x "$ARM64X_CC" "$ARM64X_CXX"
}

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

# Make one upstream checkout independent of whoever's git config it lands in.
#
# This exists because of a bug that cost real time. `native/*` is gitignored, so
# each upstream tree is its own repository and Vessel's `.gitattributes` says
# nothing about it — it inherits the *host's* global git config instead. On the
# Windows workstation this project is developed on that config is
# `core.autocrlf=true`, and the result was a Wine checkout that could never be
# clean:
#
#   $ git -C native/wine status --short
#    M configure
#    M dlls/iyuv_32/tests/i420frame.bmp
#    M dlls/mf/tests/nv12frame-crop.bmp
#    ... 41 files, none of which anyone had edited
#
# Two separate defects were tangled together there, and it is worth naming both
# because only one of them is the one everybody guesses:
#
#  - **Mode, not content.** All 41 were `100755 -> 100644` with an empty diff.
#    Wine marks a handful of data files executable (the `.bmp` fixtures among
#    them, which is why the symptom looked like binary corruption), the clone was
#    made through a bind mount where git probed `chmod` as working and therefore
#    set `core.filemode=true`, and Git-for-Windows reading the same NTFS tree
#    reports every file as 0644. So git compares a mode it cannot observe.
#    `core.filemode=false` is the fix and it costs nothing: checkout still
#    applies the index's mode when it writes a file, so `configure` is still
#    executable inside the container. Only the read-back is disabled.
#
#  - **Content, waiting to happen.** `git diff` also printed *"LF will be
#    replaced by CRLF the next time Git touches it"* for 37 more files. Nothing
#    had been mangled yet, but the next checkout or `git apply` would have, and a
#    CRLF'd `configure` or `tools/make_makefiles` fails inside the Linux
#    container with an error that names none of this.
#
# The cost of leaving it was not cosmetic. `apply_patches` below is a hard gate
# on `git apply --check`, and a dirty tree has already made that gate report the
# wrong thing once. A build harness whose first act is to lie about whether the
# source is pristine has given up the property it exists to provide.
#
# Config *and* attributes, deliberately: `core.autocrlf` can be overridden per
# invocation (`git -c`, `GIT_CONFIG_*`), and `.git/info/attributes` cannot be.
# `* -text` is the strongest available statement of "never rewrite a byte of
# this tree", which is exactly right for a tree we neither author nor commit to.
harden_checkout() {
  local dir="$1" name="$2"

  # Only the first pass has anything to do; written as a guard because the
  # renormalize below is expensive on a tree Wine's size.
  local before
  before="$(git -C "$dir" config --local --get core.autocrlf || true)"

  git -C "$dir" config core.autocrlf false
  git -C "$dir" config core.eol lf
  git -C "$dir" config core.filemode false

  mkdir -p "$dir/.git/info"
  printf '%s\n' \
    '# Written by build/common.sh: harden_checkout(). Do not edit by hand.' \
    '# Vessel never commits to this tree, so no byte of it should ever be' \
    '# rewritten by an eol filter — see the comment on harden_checkout.' \
    '* -text' \
    > "$dir/.git/info/attributes"

  # Config alone does not undo a checkout that was already converted: git trusts
  # its stat cache and will not rewrite a file whose size and mtime are
  # unchanged. Emptying the index forces every path to be written again. This is
  # the recipe gitattributes(5) gives for renormalizing, and it runs at most once
  # per tree — on the pass that finds the config wrong.
  if [ "$before" != "false" ]; then
    info "$name: normalising the checkout (was core.autocrlf='${before:-inherited}')"
    git -C "$dir" rm --cached -r -q . >/dev/null 2>&1 || true
    git -C "$dir" reset --hard --quiet HEAD
  fi
}

# Fail loudly if an upstream tree is not pristine before patches go on.
#
# `checkout --force` above should guarantee this. The check is here because when
# it does *not* hold the consequence is a patch gate testing the wrong tree, and
# the whole reason `apply_patches` is a hard error is that a half-patched build
# is not reproducible. Submodules are excluded: they are updated on the next
# line and their own dirtiness is not this tree's.
#
# **Untracked files count, and used to not.** `checkout --force` resets tracked
# content and leaves everything else alone, so a patch that *adds* a file --
# 0016's dlls/winebus.sys/bus_vessel.c, 0005's include/wine/vessel_shm.h -- left
# it behind for the next build to trip over. With `--untracked-files=no` this
# guard could not see them, so the run got all the way to
#
#     error: dlls/winebus.sys/bus_vessel.c: already exists in working directory
#     error: patch does not apply: 0016-winebus-a-bus-the-app-feeds-over-a-socket
#
# with fifteen patches already on the tree: a half-patched build, arrived at
# through the check written to prevent one. The message blamed a moved pin,
# which was wrong and would have sent the next reader to pins.env. fetch_source
# now cleans before calling this, and this now looks.
assert_pristine() {
  local dir="$1" name="$2"
  local dirty
  dirty="$(git -C "$dir" status --porcelain --untracked-files=normal --ignore-submodules=all)"
  [ -z "$dirty" ] || die "$name: the checkout is not clean after 'checkout --force':

$dirty

     Nothing should modify native/$name — it is fetched, patched and built, and
     Vessel's edits live in patches/$name/. If these are mode-only changes, the
     harden_checkout() config did not take; if they are content, something wrote
     into the tree."
}

# Clone (or update) a component to its pinned ref and apply our patches.
# Idempotent: safe to re-run, always ends at exactly the pinned ref.
# fetch_source <name> <repo> <ref> [exact_sha]
#
# The optional fourth argument freezes a branch ref to one commit. Branches move
# — the Mesa gen8 branch advanced mid-session and both Mesa-derived components
# silently changed version — so a pin is the difference between a reproducible
# build and whatever upstream pushed this morning. Empty means track the ref.
fetch_source() {
  local name="$1" repo="$2" ref="$3" exact="${4:-}"
  local dir="$NATIVE_DIR/$name"

  # Remembered for write_provenance, and through it for the source offer on the
  # components release. LGPL 2.1 section 6(a) wants the Library's complete
  # source "including whatever changes were used in the work" — the ref and the
  # commit were already recorded and are useless without saying which repository
  # they are commits *of*. Set here because this is the one place that is told.
  SOURCE_REPO="$repo"

  if [ ! -d "$dir/.git" ]; then
    log "cloning $name from $repo"
    # The two -c flags apply to the clone's own checkout, which happens before
    # there is a repository to configure. harden_checkout() then makes them
    # permanent; see its comment for why both halves are needed.
    git -c core.autocrlf=false -c core.eol=lf \
      clone --recurse-submodules "$repo" "$dir"
  else
    # **A failed fetch is not a failed build, when the pin is already here.**
    # Every source is pinned to an exact ref, so the fetch exists to *acquire*
    # it, not to decide what to build. gitlab.winehq.org refused three times in
    # one afternoon -- "not valid: could not determine hash algorithm" -- and
    # each time the commit being built was already in the local clone, so the
    # only thing the outage cost was the build. If the pin is missing the
    # checkout below still fails, loudly, which is the case that should fail.
    info "fetching $name"
    if ! git -C "$dir" fetch --all --tags --prune; then
      warn "$name: fetch failed; continuing against the local clone"
    fi
  fi

  harden_checkout "$dir" "$name"

  log "checking out $name @ $ref"
  git -C "$dir" checkout --force "$ref"
  # Branch pins need an explicit fast-forward; tag pins are already exact.
  if git -C "$dir" symbolic-ref -q HEAD >/dev/null 2>&1; then
    git -C "$dir" reset --hard "origin/$ref"
  fi
  # Freeze to the exact commit if one is pinned. After the branch checkout, so
  # the pin is a hard override rather than an alternative path — and verified to
  # exist, because a typo'd SHA must fail here and not silently build the head.
  if [ -n "$exact" ]; then
    git -C "$dir" rev-parse --verify --quiet "$exact^{commit}" >/dev/null       || die "$name: pinned commit '$exact' does not exist in $repo.
     Clear the pin in native/pins.env to track '$ref', or correct the SHA."
    log "pinning $name to $exact"
    git -C "$dir" checkout --force --detach "$exact"
  fi

  # Remove what `checkout --force` cannot: the files our own patches added on a
  # previous run. Untracked residue is the normal end state of a patched build,
  # not a symptom of anything wrong, so it is cleaned rather than reported.
  # Ignored files are left (-d, not -dx): those are build output, and Wine's
  # tree is not where this builds anyway.
  git -C "$dir" clean -ffdq

  assert_pristine "$dir" "$name"

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
      || die "patch does not apply: $p

     Either the pinned ref moved under the patch, or the tree was not clean
     when patching started — 'already exists in working directory' means the
     second, and fetch_source's clean is what should have prevented it."
    git -C "$dir" apply "$p"
    applied=$((applied + 1))
  done
  [ "$applied" -eq 0 ] || ok "$applied patch(es) applied to $name"
}

# --- Version codes -----------------------------------------------------------

# The integer the component store orders by, with room for Vessel's own patch
# revisions underneath the upstream version.
#
# **This exists because of a silent failure that is very hard to read as one.**
# `ComponentStore` is keyed by type *and version code*, so a package whose code
# already exists on the phone is treated as the same bytes and is not unpacked.
# Every component here derives its version from an upstream tag, which does not
# move when a patch under `patches/` is added — so rebuilding with a new patch
# produced a package the app then ignored. The build says "ok", the .wcp is on
# disk with the new bytes in it, `adb install` succeeds, and nothing on the
# device changes. It looks exactly like a patch that did not work.
#
# Multiplying by 100 leaves two digits that upstream can never collide with:
# FEX-2608 revision 1 is 260801, and the next tag FEX-2609 is 260900, still
# above it. Bump `<COMPONENT>_REVISION` in native/pins.env whenever a patch
# changes what a component builds.
vessel_version_code() {
  local version="$1" revision="${2:-0}"
  python3 -c 'import sys; sys.path.insert(0, sys.argv[3]); from package_wcp import version_code; print(version_code(sys.argv[1]) * 100 + int(sys.argv[2]))' \
    "$version" "$revision" "$COMMON_SH_DIR"
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
  "sourceRepo": "${SOURCE_REPO:-unknown}",
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

# Verify a built Windows DLL is the architecture we asked for.
#
# This exists because the obvious check is wrong for ARM64EC. An ARM64EC image
# reports machine type AMD64 — file(1) says "PE32+ executable (DLL) x86-64" and
# that is CORRECT, because presenting an x64-compatible machine type is the
# entire point of EC: x64 code has to be able to call into it. Grepping for
# "aarch64" here rejects perfectly good EC builds, which it did for both FEX and
# DXVK before this was centralised.
#
# So the real test for EC is the hybrid (CHPE) load-config directory, which only
# an EC/ARM64X image carries. That also catches the failure that actually
# matters: an arm64ec target silently falling back to plain x64.
#
#   verify_pe_dll <path> <triple> [label]
# Note on the shape of the code below: every check captures its command's output
# into a variable and greps the variable, rather than piping producer into
# `grep -q`. That is not a style preference, it is required by `set -o pipefail`
# at the top of this file.
#
# `grep -q` exits the instant it matches. If the producer is still writing, it
# takes SIGPIPE and exits 141, and pipefail then makes the whole pipeline
# non-zero — so a SUCCESSFUL match reports failure. Whether that happens depends
# on nothing more meaningful than output size: anything under the 64 KB pipe
# buffer is written and the producer exits cleanly first. llvm-readobj
# --coff-load-config on Wine's ntdll.dll emits 5080 lines with the CHPE match on
# line 38, which is exactly the losing case, and it failed here as
#     error: aarch64-windows/ntdll.dll (ARM64X) has no CHPE load-config
# on a DLL that demonstrably has one. The same pipeline had always passed for
# FEX's and DXVK's much smaller DLLs.
verify_pe_dll() {
  local dll="$1" triple="$2" label="${3:-$(basename "$1")}"
  local file_out loadcfg

  [ -f "$dll" ] || die "$label: no such file: $dll"
  file_out="$(file -b "$dll")"

  # PE32 and PE32+ are both PE DLLs; the "+" only means a 64-bit optional header.
  # A 32-bit x86 module is therefore PE32 and asserting PE32+ up here rejects a
  # perfectly correct i386 build:
  #     error: i386 ntdll.dll is not a PE32+ DLL:
  #            PE32 executable (DLL) (console) Intel 80386, for MS Windows
  # The 32-vs-64 question belongs to the per-triple checks below, which know
  # which one they asked for. (This branch went unexercised for a long time
  # because dxvk.sh and vkd3d.sh only route their 64-bit pass through here.)
  grep -Eqi 'PE32\+? executable \(DLL\)' <<< "$file_out" \
    || die "$label is not a PE DLL: $file_out"

  case "$triple" in
    arm64ec-*)
      loadcfg="$("$MINGW_BIN/llvm-readobj" --coff-load-config "$dll" 2>/dev/null || true)"
      if grep -qE 'CHPEMetadata|HybridMetadataPointer' <<< "$loadcfg"; then
        info "$label carries hybrid (CHPE) metadata — genuine ARM64EC"
      else
        die "$label has no CHPE load-config, so it is not really ARM64EC.
     A plain x64 DLL here means the arm64ec target silently fell back."
      fi
      ;;
    aarch64-*)
      grep -Eqi 'PE32\+ executable' <<< "$file_out" \
        || die "$label should be a 64-bit PE but is: $file_out"
      grep -Eqi 'aarch64|arm64' <<< "$file_out" \
        || die "$label should be an ARM64 PE but is: $file_out"
      ;;
    i686-*)
      # No EC-style subtlety here: a 32-bit x86 module is PE32 + Intel 80386, and
      # file's machine type is the truth. The failure worth catching is a 64-bit
      # or ARM image installed under an i386 name.
      grep -Eqi 'PE32 executable' <<< "$file_out" \
        || die "$label should be a 32-bit PE but is: $file_out"
      grep -Eqi '80386|intel' <<< "$file_out" \
        || die "$label should be a 32-bit x86 PE but is: $file_out"
      ;;
    x86_64-*)
      grep -Eqi 'PE32\+ executable' <<< "$file_out" \
        || die "$label should be a 64-bit PE but is: $file_out"
      grep -Eqi 'x86-64' <<< "$file_out" \
        || die "$label should be an x86-64 PE but is: $file_out"
      ;;
    *)
      warn "$label: no architecture check defined for triple $triple"
      ;;
  esac
}

nproc_safe() { nproc 2>/dev/null || echo 4; }

# Parallelism capped by MEMORY, not just core count.
#
# C++ template-heavy builds (DXVK and vkd3d-proton especially) can hold well
# over a gigabyte per compiler process. On a machine with many cores and a
# modest Docker VM, `-j$(nproc)` does not merely swap — it got the Docker engine
# itself OOM-killed mid-build, which surfaces as
#   error waiting for container: unexpected EOF
# and then a 500 from the daemon, with no compiler error to explain it.
#
# One job per GB of available memory, clamped to the core count, floor of 1.
# Override with VESSEL_JOBS to force a value.
build_jobs() {
  local per_job_gb="${1:-1}"
  if [ -n "${VESSEL_JOBS:-}" ]; then echo "$VESSEL_JOBS"; return; fi

  local cores mem_kb mem_gb jobs
  cores="$(nproc_safe)"
  mem_kb="$(awk '/MemAvailable/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
  mem_gb=$(( mem_kb / 1024 / 1024 ))

  if [ "$mem_gb" -le 0 ]; then echo "$cores"; return; fi

  jobs=$(( mem_gb / per_job_gb ))
  [ "$jobs" -lt 1 ] && jobs=1
  [ "$jobs" -gt "$cores" ] && jobs="$cores"
  echo "$jobs"
}

# Every component script starts here.
vessel_init() {
  load_config
}
