#!/usr/bin/env bash
# Run the graphics probes *inside a container*, with that container's settings.
#
#   ./tools/container-probe.sh install     # build, copy in, register as programs
#   ./tools/container-probe.sh results     # read what the last runs reported
#   ./tools/container-probe.sh remove      # take the programs back out
#
# tools/device-graphics.sh answers "does this API draw?" in a prefix it builds
# itself. This answers a different question — "what is a container telling the
# guest, and does every layer agree?" — and it cannot be answered in a scratch
# prefix, because the numbers come from the container's own Hardware settings and
# from the components that container has installed.
#
# The probes print one `VESSEL-HW` line each, through OutputDebugString as well
# as stdout, because a program launched inside a container is a child of `wine
# explorer` and Vessel's session log carries Wine's debug stream rather than a
# child's console. See gfx_emit in tools/gfx/gfxprobe.h.
#
# Launching is the one step this script does not do: a container program is
# started from the app, so `install` registers them and you tap them. Everything
# either side of that is here.
set -euo pipefail

PKG=app.vessel
PROBES="vkprobe d3d11probe d3d12probe glprobe"
GUEST_DIR='C:\vessel\probes'
DEVICE_SUBDIR=prefix/drive_c/vessel/probes

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="${TMPDIR:-/tmp}/vessel-container-probe"
# Made here rather than in `install`, because `remove` and `results` also stage a
# file through it and only `install` used to create it -- so removing the probes
# without having installed them in this shell failed on a missing directory.
mkdir -p "$STAGE"

say()  { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m %s\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Device paths below are written with a leading double slash. Git Bash rewrites
# a /data/... argument into C:/Program Files/Git/data/..., and // suppresses that
# for the remote argument alone -- MSYS_NO_PATHCONV would also stop converting
# the *local* path, which then fails instead.
in_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }

container_id() {
    # The first container, which is the only one on a device used for this.
    in_app "ls files/containers | head -1"
}

case "${1:-install}" in
install)
    say "building the probes for x86_64"
    # x86_64 only. The aarch64 builds cannot load DXVK's PE DLLs and the i686
    # ones answer a question about WoW64 that device-graphics.sh already covers.
    docker run --rm -v "$REPO:/src" -v vessel-work:/work vessel-build bash -c "
        set -e
        . /src/build/common.sh >/dev/null; vessel_init >/dev/null; setup_mingw >/dev/null
        mkdir -p /work/container-probes
        for p in $PROBES; do
          \"\$MINGW_BIN/x86_64-w64-mingw32-clang\" -O1 -Wall -Wextra -Wno-unused-parameter \
            -o \"/work/container-probes/\$p-x86_64.exe\" \"/src/tools/gfx/\$p.c\" \
            -I/src/tools/gfx -ldxguid -luuid -lgdi32 -luser32
        done" >/dev/null || die "cross-compiling the probes failed"

    rm -rf "$STAGE" && mkdir -p "$STAGE"
    docker run --rm -v vessel-work:/work -v "$STAGE:/out" alpine:latest \
        sh -c 'cp /work/container-probes/*.exe /out/' || die "could not collect the probes"
    ok "$(ls "$STAGE" | wc -l) built"

    CID="$(container_id)"
    [ -n "$CID" ] || die "no container on the device"
    say "installing into container $CID"
    in_app "mkdir -p files/containers/$CID/$DEVICE_SUBDIR" >/dev/null
    for p in $PROBES; do
        adb push "$STAGE/$p-x86_64.exe" "//data/local/tmp/$p-x86_64.exe" >/dev/null
        # Through /data/local/tmp and `cat`, because adb cannot write into an
        # app's private storage directly and run-as cannot read /sdcard.
        in_app "cat /data/local/tmp/$p-x86_64.exe > files/containers/$CID/$DEVICE_SUBDIR/$p-x86_64.exe"
        adb shell "rm -f //data/local/tmp/$p-x86_64.exe"
        ok "$p"
    done

    say "registering them as programs"
    adb shell "am force-stop $PKG"
    adb exec-out "run-as $PKG cat files/datastore/shortcuts.json" > "$STAGE/shortcuts.json"
    python - "$STAGE/shortcuts.json" "$STAGE/shortcuts.new.json" "$CID" "$GUEST_DIR" <<'PY'
import json, sys, collections
src, dst, cid, guest_dir = sys.argv[1:5]
d = json.load(open(src, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)
have = {s["name"] for s in d["shortcuts"]}
names = {"vkprobe": "p-vulkan", "d3d11probe": "p-d3d11",
         "d3d12probe": "p-d3d12", "glprobe": "p-opengl"}
for i, (exe, name) in enumerate(sorted(names.items())):
    if name in have:
        continue
    d["shortcuts"].append(collections.OrderedDict([
        ("id", "00000000-0000-4000-8000-%012d" % i),
        ("containerId", cid),
        ("executable", guest_dir + chr(92) + exe + "-x86_64.exe"),
        ("name", name), ("arch", "X64"), ("args", ""), ("workingDir", ""),
    ]))
json.dump(d, open(dst, "w", encoding="utf-8"), indent=4)
print(" ".join(s["name"] for s in d["shortcuts"]))
PY
    adb push "$STAGE/shortcuts.new.json" //data/local/tmp/shortcuts.json >/dev/null
    in_app "cat /data/local/tmp/shortcuts.json > files/datastore/shortcuts.json"
    ok "registered — open the app and tap p-vulkan, p-d3d12, p-opengl, p-d3d11"
    ;;

results)
    CID="$(container_id)"
    say "what each layer reported, newest session first"
    # Every session, because one run holds one probe: they are separate programs
    # and each launch writes its own log.
    in_app "cd files/logs/$CID && grep -h VESSEL-HW \$(ls -t *.log | head -20) 2>/dev/null" \
        | sed 's/.*OutputDebugStringA "//; s/"$//' | sort -u
    ;;

remove)
    CID="$(container_id)"
    say "removing the probe programs from container $CID"
    adb shell "am force-stop $PKG"
    adb exec-out "run-as $PKG cat files/datastore/shortcuts.json" > "$STAGE/shortcuts.json"
    python - "$STAGE/shortcuts.json" "$STAGE/shortcuts.new.json" <<'PY'
import json, sys, collections
src, dst = sys.argv[1:3]
d = json.load(open(src, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)
d["shortcuts"] = [s for s in d["shortcuts"] if not s["name"].startswith("p-")]
json.dump(d, open(dst, "w", encoding="utf-8"), indent=4)
print(" ".join(s["name"] for s in d["shortcuts"]) or "(none left)")
PY
    adb push "$STAGE/shortcuts.new.json" //data/local/tmp/shortcuts.json >/dev/null
    in_app "cat /data/local/tmp/shortcuts.json > files/datastore/shortcuts.json"
    # The binaries stay: they are 700 KB inside the container's own drive_c and
    # leaving them means `install` is only needed again when a probe changes.
    ok "programs removed; the executables stay in $GUEST_DIR"
    ;;

*)
    die "usage: $0 [install|results|remove]"
    ;;
esac
