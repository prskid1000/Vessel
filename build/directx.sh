#!/usr/bin/env bash
# Package Microsoft's DirectX End-User Runtime (June 2010) as one `DirectX`
# component.
#
#   ./build/directx.sh        # -> dist/directx-<ver>-any.wcp
#
# **Nothing here is compiled**, the same as build/vcruntime.sh: every byte is
# Microsoft's, verified against a sha256 in native/pins.env and unpacked.
#
# **Why the component exists.** Wine's D3DX and D3DCompiler builtins hand effect
# compilation to vkd3d-shader, and that cannot compile the effects real games
# ship. Caribbean Legend's 54 `.fx` files were compiled on the build host, one
# vkd3d at a time:
#
#   vkd3d 1.18 (the copy inside our Wine)            0 / 54
#   vkd3d 2.1, and upstream master on 2026-09-16      8 / 54
#   master + patches/wine's state-value lexer fix    45 / 54
#
# and on the device every effect failed, every technique lookup failed after
# that, and the game drew a still scene with no menu on it while system.log grew
# past two million lines of "technique (interfacefont) not found". The nine that
# still fail include the ocean and the world map: inline `asm { }` shader blocks,
# for which vkd3d has no assembler at all. With Microsoft's d3dx9_43.dll and
# d3dcompiler_43.dll the same session compiled all of them and logged 7 KB.
#
# **Which of these Wine is told to prefer is decided in SessionEnvironment, not
# here**: D3DX and D3DCompiler native first, audio and input left builtin. This
# script packages the whole runtime, oldest to newest, so that the decision can
# change without a new download.
#
# --- the redistributable --------------------------------------------------------
#
# directx_Jun2010_redist.exe is a cabinet SFX, so cabextract opens it directly --
# no carving, unlike the Burn bundles in vcruntime.sh. Inside are 151 cabinets,
# one per (release, library, architecture):
#
#   Feb2005_d3dx9_24_x64.cab ... Jun2010_d3dx9_43_x86.cab
#   Aug2009_D3DCompiler_42_x64.cab, Mar2008_X3DAudio_x86.cab, ...
#
# each holding the DLL beside its .inf and .cat. The architecture is in the
# cabinet name and nowhere else, which is why the layout is decided per cabinet.
#
# Not packaged, each for a reason:
#   DXSETUP.exe, DSETUP.dll, dsetup32.dll, dxupdate.cab -- the installer itself
#   dxdllreg_x86.cab -- dxdllreg.exe, the installer's COM registration helper
#   Apr2006_MDX1_x86_Archive.cab -- Managed DirectX 1.1: .NET Framework GAC
#     assemblies, which a file copy into system32 does not install
set -euo pipefail

COMPONENT=directx
COMMON_SH_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$COMMON_SH_DIR/common.sh"

load_config

command -v cabextract >/dev/null \
  || die "cabextract is not installed; it is in the Dockerfile, so this is a stale image — rebuild with 'docker build -t vessel-build .'"

VERSION="$DIRECTX_VERSION"
# major * 10_000_000 + minor * 10_000 + build, then one digit of packaging
# revision: 9.29.1974 revision 0 is 902919740, inside a signed 32-bit code with
# room to spare. Adoption only moves forward, so a repackage bumps
# DIRECTX_REVISION rather than reusing a code with different bytes.
DX_MAJOR="${VERSION%%.*}"
DX_REST="${VERSION#*.}"
DX_MINOR="${DX_REST%%.*}"
DX_BUILD="${DX_REST#*.}"
VERSION_CODE=$(( (DX_MAJOR * 10000000 + DX_MINOR * 10000 + DX_BUILD) * 10 + DIRECTX_REVISION ))
info "directx $VERSION revision $DIRECTX_REVISION as code $VERSION_CODE"

WORK="$WORK_DIR/$COMPONENT"
CACHE="$WORK/downloads"
STAGE="$WORK/stage"
UNPACK="$WORK/unpack"
rm -rf "$STAGE" "$UNPACK"
mkdir -p "$CACHE" "$STAGE/system32" "$STAGE/syswow64" "$UNPACK/cabs"

# --- fetching -------------------------------------------------------------------
fetch_pinned() {
  # <url> <filename> <expected sha256>
  local url="$1" name="$2" want="$3" got
  if [ ! -f "$CACHE/$name" ]; then
    info "fetching $name"
    curl -fSL --retry 3 -o "$CACHE/$name.part" "$url" || die "could not download $url"
    mv "$CACHE/$name.part" "$CACHE/$name"
  fi
  got="$(sha256sum "$CACHE/$name" | cut -d' ' -f1)"
  [ "$got" = "$want" ] \
    || die "$name sha256 is $got, native/pins.env says $want — verify the new file and update the pin rather than removing this check"
}

log "fetching Microsoft's DirectX End-User Runtime"
fetch_pinned "$DIRECTX_REDIST_URL" directx_redist.exe "$DIRECTX_REDIST_SHA256"

# --- unpacking ------------------------------------------------------------------
log "unpacking"
cabextract -q -d "$UNPACK/cabs" "$CACHE/directx_redist.exe" >/dev/null 2>&1 \
  || die "cabextract could not open directx_redist.exe — it is no longer a plain cabinet SFX"

# Oldest release first, so that where two releases carry the same file name --
# x3daudio1_0.dll ships in four of them -- the newest copy is the one that stays.
# The cabinet names spell the month three ways (Apr, APR, apr), so the sort key
# is computed rather than trusted to a lexical sort, which would put every
# "Apr" before every "Aug" regardless of year.
month_number() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    jan) echo 01 ;; feb) echo 02 ;; mar) echo 03 ;; apr) echo 04 ;;
    may) echo 05 ;; jun) echo 06 ;; jul) echo 07 ;; aug) echo 08 ;;
    sep) echo 09 ;; oct) echo 10 ;; nov) echo 11 ;; dec) echo 12 ;;
    *) echo "" ;;
  esac
}

sorted_cabs() {
  local cab name mon yr m
  for cab in "$UNPACK/cabs"/*_x64.cab "$UNPACK/cabs"/*_x86.cab; do
    [ -f "$cab" ] || continue
    name="$(basename "$cab")"
    # The installer's own registration helper; see the list at the top.
    case "$name" in dxdllreg_*) continue ;; esac
    mon="${name:0:3}"; yr="${name:3:4}"
    m="$(month_number "$mon")"
    [ -n "$m" ] && [[ "$yr" =~ ^[0-9]{4}$ ]] \
      || die "$name does not start with a release date — the cabinet naming changed"
    printf '%s%s %s\n' "$yr" "$m" "$cab"
  done | sort -k1,1n -k2,2 | cut -d' ' -f2-
}

# Into a file first rather than straight into the loop: `die` inside a process
# substitution exits only the subshell, and the first version of this script
# printed "does not start with a release date" and then packaged anyway.
sorted_cabs > "$UNPACK/order.txt" || die "could not order the cabinets"

n_cabs=0
while IFS= read -r cab; do
  name="$(basename "$cab")"
  case "$name" in
    *_x64.cab) dest="$STAGE/system32" ;;
    *_x86.cab) dest="$STAGE/syswow64" ;;
  esac
  tmp="$UNPACK/members/$name"
  mkdir -p "$tmp"
  cabextract -q -d "$tmp" "$cab" >/dev/null 2>&1 || die "cabextract failed on $name"
  for f in "$tmp"/*; do
    [ -f "$f" ] || continue
    base="$(basename "$f" | tr '[:upper:]' '[:lower:]')"
    # The .inf and .cat beside each DLL are installer metadata; only the
    # library itself is a runtime.
    case "$base" in *.dll) cp -f "$f" "$dest/$base" ;; esac
  done
  n_cabs=$((n_cabs + 1))
done < "$UNPACK/order.txt"
info "$n_cabs cabinets unpacked"

# `d3dx9_43.dll` and `d3dcompiler_43.dll` are the two a game was measured failing
# without, so they are asserted by name rather than satisfied by a count.
for d in system32 syswow64; do
  n="$(find "$STAGE/$d" -maxdepth 1 -name '*.dll' | wc -l)"
  [ "$n" -gt 0 ] || die "$d/ came out empty — the cabinet naming changed"
  for must in d3dx9_24.dll d3dx9_43.dll d3dcompiler_43.dll d3dx10_43.dll d3dx11_43.dll xaudio2_7.dll xinput1_3.dll; do
    [ -f "$STAGE/$d/$must" ] || die "$d/$must is missing"
  done
  ok "$d $n dll ($(du -sh "$STAGE/$d" | cut -f1))"
done

# --- packaging ------------------------------------------------------------------
cat > "$STAGE/provenance.json" <<EOF
{
  "component": "directx",
  "version": "$VERSION",
  "target": "any",
  "targetDesc": "architecture-independent package; system32/ is x64 and syswow64/ x86, both installed into the prefix",
  "sourceRepo": "Microsoft DirectX End-User Runtime (June 2010); see native/pins.env",
  "sourceRef": "directx_Jun2010_redist.exe $VERSION",
  "sourceSha": "directx_Jun2010_redist.exe $DIRECTX_REDIST_SHA256",
  "cpuFlags": "none",
  "ndk": "n/a",
  "apiLevel": "n/a",
  "builtBy": "Microsoft redistributable cabinets, unpacked verbatim and not rebuilt"
}
EOF

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type DirectX \
  --name "DirectX Runtimes (2005-2010)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Microsoft's DirectX End-User Runtime (June 2010), unpacked from the official redistributable: D3DX9 24-43, D3DX10 33-43, D3DX11 42-43, D3DCompiler 33-43, D3DCSX, XAudio2, XACT, X3DAudio, XAPOFX and XInput, x64 in system32/ and x86 in syswow64/. D3DX and D3DCompiler are loaded native-first, because Wine's builtins compile effects through vkd3d-shader and it cannot compile the ones games ship; audio and input stay Wine's own, which FAudio and Vessel's gamepad bridge serve. Direct3D itself is DXVK and vkd3d-proton, not this." \
  --out "$DIST_DIR/$COMPONENT-$VERSION-any.wcp"

ok "dist/$COMPONENT-$VERSION-any.wcp"
