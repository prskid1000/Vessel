#!/usr/bin/env bash
# Package Microsoft's Visual C++ redistributable runtimes as one `VCRuntime`
# component.
#
#   ./build/vcruntime.sh        # -> dist/vcruntime-<ver>-any.wcp
#
# **Nothing here is compiled**, the same as build/tools.sh: every byte is an
# official Microsoft redistributable, verified against a sha256 in
# native/pins.env and unpacked. What this script has to get right is the
# unpacking, because none of it is a plain archive.
#
# **Why the component exists.** Wine implements no part of the MSVC runtime from
# 2010 onward -- `mfc42` and `msvcp60` are Windows' own and have builtins, but
# `mfc140u.dll` and its generation are Microsoft's to ship. A game built against
# MFC stops in the loader before it draws a frame:
#
#   Library mfc140u.dll ... not found
#   Importing dlls for L"...\Launcher.exe" failed, status c0000135
#
# [app.vessel.core.PrefixRegistry.vcRuntimes] seeds the registry keys a
# prerequisite checker reads; this is what puts the files behind them. Seeding
# the keys without the files is worse than seeding neither, because the checker
# then passes and the loader fails anyway.
#
# **Why extraction and not "run the installers in the prefix".** The official
# installers do run under Wine ARM64EC -- measured -- but they are WoW64
# processes driving MSI through the emulator, and the cost is minutes per
# container against a file copy that is instant and works offline. The
# installers remain the only way to get real WinSxS assemblies, which is what
# the 2005 and 2008 runtimes need; those two are deliberately out of scope here
# and a game that wants one will have to be handled another way.
#
# --- unpacking, which is three formats -----------------------------------------
#
# 2010 is an IExpress SFX: a cabinet appended to a PE, holding `vc_red.msi` and
# `vc_red.cab`, and the cabinet's members are named `F_CENTRAL_<dll>_<arch>`.
#
# 2012, 2013 and v14 are WiX Burn bundles: a PE followed by *two* cabinets, the
# UX container (the bootstrapper's own UI, ~140 KB) and then the attached
# container holding the .msi files and their payload cabs. `cabextract` and
# `7z` both find the first and stop, which is why the obvious approaches come
# back with 296 KB of dialog resources and no runtime. `carve_cabinets` below
# walks the whole file instead.
#
# The payload cabs name their members `<dll>.dll_<arch>` -- `mfc140u.dll_amd64`,
# `concrt140.dll_arm64` -- so the architecture and the real filename are both in
# the member name and no .msi has to be parsed to lay the files out. That is the
# one piece of luck in the format and it is what keeps this script to shell and
# a cabinet reader rather than needing msitools or a Windows host.
set -euo pipefail

COMPONENT=vcruntime
COMMON_SH_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$COMMON_SH_DIR/common.sh"

load_config

command -v cabextract >/dev/null \
  || die "cabextract is not installed; it is in the Dockerfile, so this is a stale image — rebuild with 'docker build -t vessel-build .'"

VERSION="$VCRUNTIME_VERSION"
# (major*100 + minor) * 1_000_000 + build*10 + packaging revision.
#
# **Not vessel_version_code.** That folds a Vessel revision into the low two
# digits of a dotted version, and 14.51.36247 does not fit: the version alone
# is 145136247, and another two digits overflows a signed 32-bit code. This
# keeps Microsoft's major.minor.build monotonic and leaves ten packaging
# revisions per upstream build, which is what a layout fix needs -- adoption is
# by version code and only ever moves forward, so a repackage that renumbers
# downward pins every existing container to a version that is no longer on disk.
VC_MAJOR="${VERSION%%.*}"
VC_REST="${VERSION#*.}"
VC_MINOR="${VC_REST%%.*}"
VC_BUILD="${VC_REST#*.}"
VERSION_CODE=$(( (VC_MAJOR * 100 + VC_MINOR) * 1000000 + VC_BUILD * 10 + VCRUNTIME_REVISION ))
info "vcruntime $VERSION revision $VCRUNTIME_REVISION as code $VERSION_CODE"

WORK="$WORK_DIR/$COMPONENT"
CACHE="$WORK/downloads"
STAGE="$WORK/stage"
rm -rf "$STAGE"
mkdir -p "$CACHE" "$STAGE"

# --- fetching -------------------------------------------------------------------
# Same shape as tools.sh's fetch_pinned, and for the same reason: a pinned hash
# that is checked is a pin, and one that is only written down is a comment.
#
# The v14 permalink is the one to watch. Microsoft's own page says the version
# "isn't listed ... because it's updated frequently", so aka.ms/vc14 serves new
# bytes whenever they ship a servicing update and the build fails here rather
# than silently packaging something nobody recorded. That failure is the feature;
# bump VCRUNTIME_VERSION and the three hashes together when it fires.
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
    || die "$name sha256 is $got, native/pins.env says $want — Microsoft reships aka.ms/vc14 on their own schedule, so verify the new file and update the pin rather than removing this check"
}

log "fetching Microsoft's redistributables"
fetch_pinned "$VCRUNTIME_V14_X64_URL" vc_redist.x64.exe   "$VCRUNTIME_V14_X64_SHA256"
fetch_pinned "$VCRUNTIME_V14_X86_URL" vc_redist.x86.exe   "$VCRUNTIME_V14_X86_SHA256"
fetch_pinned "$VCRUNTIME_2013_X64_URL" vcredist2013_x64.exe "$VCRUNTIME_2013_X64_SHA256"
fetch_pinned "$VCRUNTIME_2013_X86_URL" vcredist2013_x86.exe "$VCRUNTIME_2013_X86_SHA256"
fetch_pinned "$VCRUNTIME_2012_X64_URL" vcredist2012_x64.exe "$VCRUNTIME_2012_X64_SHA256"
fetch_pinned "$VCRUNTIME_2012_X86_URL" vcredist2012_x86.exe "$VCRUNTIME_2012_X86_SHA256"
fetch_pinned "$VCRUNTIME_2010_X64_URL" vcredist2010_x64.exe "$VCRUNTIME_2010_X64_SHA256"
fetch_pinned "$VCRUNTIME_2010_X86_URL" vcredist2010_x86.exe "$VCRUNTIME_2010_X86_SHA256"

# --- unpacking ------------------------------------------------------------------
# One directory per architecture, named for where copyWindowsPayload mirrors it:
# `system32/` and `syswow64/` are the two it installs, and `arm64/` rides along
# uninstalled because those files would collide with x64 over the same names to
# serve ARM64-native Windows programs, which is not what runs here.
mkdir -p "$STAGE/system32" "$STAGE/syswow64" "$STAGE/arm64"

# Members are `<dll>.dll_<arch>` (Burn) or `F_CENTRAL_<dll>_<arch>` (2010), and
# the Burn form has variants: `vcomp140.dll_system_arm64` carries a `_system`
# marking the install directory, and `mfcm140_arm64.dll_arm64` has the
# architecture in the real filename as well as the suffix. So the architecture
# is taken off the end and the rest is required to look like a DLL, rather than
# matching a fixed list of shapes that a servicing update can add to.
#
# **The architecture suffix is authoritative, not the cabinet it came from.**
# The arm64 payload cab holds `mfcm140.dll_amd64` next to
# `mfcm140_arm64.dll_arm64`: Microsoft ships the x64 managed-MFC wrapper inside
# the ARM64 package for ARM64EC's sake. Sorting by suffix puts that one in
# system32 where it belongs; sorting by which cab it came out of would file an
# x64 binary under arm64.
sort_member() {
  # <path to extracted member> <member name>
  local path="$1" name="$2" dll="" arch="" rest=""
  case "$name" in
    F_CENTRAL_*_x64)  dll="${name#F_CENTRAL_}"; dll="${dll%_x64}.dll" ; arch=system32 ;;
    F_CENTRAL_*_x86)  dll="${name#F_CENTRAL_}"; dll="${dll%_x86}.dll" ; arch=syswow64 ;;
    *_amd64)          rest="${name%_amd64}"; arch=system32 ;;
    *_x86)            rest="${name%_x86}"  ; arch=syswow64 ;;
    *_arm64)          rest="${name%_arm64}"; arch=arm64    ;;
    *) return 0 ;;
  esac
  if [ -n "$rest" ]; then
    # `_system` and `_app` name the install directory the .msi would have used.
    rest="${rest%_system}"; rest="${rest%_app}"
    case "$rest" in *.dll) dll="$rest" ;; *) return 0 ;; esac
  fi
  # `msdia*` is the debug-interface DLL the 2010 package carries for Visual
  # Studio itself. It is not a runtime a program links against and shipping it
  # would be redistributing a developer tool rather than a runtime.
  case "$dll" in msdia*) return 0 ;; esac
  # First writer wins, and the order below is oldest package first, so a newer
  # runtime replaces an older one of the same name rather than the reverse.
  cp -f "$path" "$STAGE/$arch/$dll"
}

unpack_one() {
  # <installer filename>
  local exe="$CACHE/$1" tmp="$WORK/unpack/$1" cab member
  rm -rf "$tmp"; mkdir -p "$tmp/containers" "$tmp/members"

  python3 "$COMMON_SH_DIR/carve_cabinets.py" "$exe" "$tmp/containers" >/dev/null \
    || die "no cabinet found in $1 — the installer format changed, and neither the Burn nor the IExpress shape applies"

  # Every carved container, then every cabinet *inside* one, because the Burn
  # attached container holds the payload cabs rather than the files themselves.
  for cab in "$tmp/containers"/*.cab; do
    cabextract -q -d "$tmp/members" "$cab" >/dev/null 2>&1 || true
  done
  for cab in "$tmp/members"/*; do
    [ -f "$cab" ] || continue
    case "$(file -b "$cab")" in
      *"Cabinet archive"*) cabextract -q -d "$tmp/members" "$cab" >/dev/null 2>&1 || true ;;
    esac
  done

  local n=0
  for member in "$tmp/members"/*; do
    [ -f "$member" ] || continue
    sort_member "$member" "$(basename "$member")" && n=$((n + 1))
  done
  info "$1 unpacked"
}

# Oldest first: see the note in sort_member about which copy wins.
log "unpacking"
for f in vcredist2010_x86.exe vcredist2010_x64.exe \
         vcredist2012_x86.exe vcredist2012_x64.exe \
         vcredist2013_x86.exe vcredist2013_x64.exe \
         vc_redist.x86.exe vc_redist.x64.exe; do
  unpack_one "$f"
done

# The x64 package carries the ARM64 runtime as a third .msi inside it, which is
# Microsoft's own arrangement -- their docs say "The X64 Redistributable package
# contains both ARM64 and X64 binaries" -- so arm64/ fills without a fourth
# download. There is no ARM64 build of 2010, 2012 or 2013 and there never will
# be: Windows on ARM64 postdates all three.
for d in system32 syswow64 arm64; do
  n="$(find "$STAGE/$d" -maxdepth 1 -name '*.dll' | wc -l)"
  [ "$n" -gt 0 ] || die "$d/ came out empty — the cabinet member naming changed"
  ok "$d $n dll ($(du -sh "$STAGE/$d" | cut -f1))"
done

# `mfc140u.dll` is the file a game actually failed on, so it is the one asserted
# rather than a count that could be satisfied by anything.
for d in system32 syswow64 arm64; do
  [ -f "$STAGE/$d/mfc140u.dll" ] || die "$d/mfc140u.dll is missing"
done
ok "mfc140u.dll present for all three architectures"

# --- packaging ------------------------------------------------------------------
write_vcruntime_provenance() {
  cat > "$STAGE/provenance.json" <<EOF
{
  "component": "vcruntime",
  "version": "$VERSION",
  "target": "any",
  "targetDesc": "architecture-independent package; system32/ is x64 and syswow64/ x86, both installed into the prefix, and arm64/ is carried but not installed",
  "sourceRepo": "Microsoft Visual C++ redistributable packages; see native/pins.env",
  "sourceRef": "v14 $VERSION, 2013 $VCRUNTIME_2013_VERSION, 2012 $VCRUNTIME_2012_VERSION, 2010 $VCRUNTIME_2010_VERSION",
  "sourceSha": "vc_redist.x64.exe $VCRUNTIME_V14_X64_SHA256; vc_redist.x86.exe $VCRUNTIME_V14_X86_SHA256; vcredist2013_x64.exe $VCRUNTIME_2013_X64_SHA256; vcredist2013_x86.exe $VCRUNTIME_2013_X86_SHA256; vcredist2012_x64.exe $VCRUNTIME_2012_X64_SHA256; vcredist2012_x86.exe $VCRUNTIME_2012_X86_SHA256; vcredist2010_x64.exe $VCRUNTIME_2010_X64_SHA256; vcredist2010_x86.exe $VCRUNTIME_2010_X86_SHA256",
  "cpuFlags": "none",
  "ndk": "n/a",
  "apiLevel": "n/a",
  "builtBy": "Microsoft redistributable packages, unpacked verbatim and not rebuilt"
}
EOF
}
write_vcruntime_provenance

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type VCRuntime \
  --name "Visual C++ Runtimes (2010-2026)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Microsoft Visual C++ redistributable runtimes, unpacked from the official packages. system32/ (x64) and syswow64/ (x86) each carry 2010, 2012, 2013 and 2015-2026 and are installed into the prefix; arm64/ carries 2015-2026 and is not, since those files would collide with x64 over the same names to serve ARM64-native Windows programs. Wine implements none of these from 2010 on, and a game built against MFC stops in the loader with c0000135 before it draws. The 2005 and 2008 runtimes are deliberately absent: they deploy as WinSxS assemblies, which a file copy cannot reproduce." \
  --out "$DIST_DIR/$COMPONENT-$VERSION-any.wcp"

ok "dist/$COMPONENT-$VERSION-any.wcp"
