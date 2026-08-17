#!/usr/bin/env bash
# Package Git, Python, Node.js, PowerShell 7 and a JDK as one x86-64 `Tools`
# component.
#
#   ./build/tools.sh            # -> dist/tools-<ver>-x64.wcp
#
# **Nothing here is compiled.** Every byte of the payload is an upstream release
# archive, verified against a sha256 in native/pins.env and unpacked. That makes
# this the odd one out among the build scripts: no setup_ndk, no setup_mingw, no
# fetch_source, no patches. What it does instead is the part that has to be
# right — laying five unrelated trees out so that one `installTools()` pass and
# one PATH seed can find all five.
#
# **Why one package and not three.** A container references exactly one component
# per type: ComponentStore's `referencesOf` returns a type -> versionCode map and
# there is no list in it. `Tools` is one type, so a second `Tools` package does
# not join the first — `adoptLatest` moves the container's single reference to
# whichever has the higher code, `installTools()` copies that one, and the other
# silently disappears. docs/DEVTOOLS.md §7.2 and §7.3 have the full walk-through.
#
# **Why x86-64 for all three, Git included.** The shipped Git component was the
# ARM64 build; this replaces it. The reason is the package ecosystems rather than
# the binaries: PyPI resolves far more `win_amd64` wheels than `win_arm64`, npm
# ships `win32-x64` binaries for packages that have no ARM64 build at all, and
# there is no C compiler in the prefix to build an sdist when a wheel is missing.
# See native/pins.env for the rest of the reasoning, including what Git's ARM64
# build actually was (half of it was x86-64 already).
#
# **Layout: Git/, Python/, Node/, Pwsh/, Java/ at the payload root.** The shipped
# Git package put its tree flat at the root, which works exactly as long as there
# is only one thing in the payload. Five trees need five names, and the names are the
# contract `installTools()` reads — see TOOLS_LAYOUT there. Anything that flattens
# these breaks the install in the way Git's own comment warns about: `cmd\git.exe`
# finds its helpers by walking up from where it lives.
#
# **Why PowerShell is in here at all**, since it is neither a language runtime
# nor a VCS: it is what makes Claude Code's shell tool work. docs/DEVTOOLS.md §4
# works the constraint out — Claude Code runs commands through a Bash tool that
# on Windows means Git Bash, or a PowerShell tool when Git for Windows is absent.
# The first is measured broken here: TerminalProfile.kt:56-70 caught
# `bash --login -i` with a child spinning at 98% inside MSYS2's `fork()`
# emulation, and `/etc/profile` forks before it ever reaches a prompt. The second
# had nothing behind it but Wine's stub `powershell.exe`. TerminalProfile.kt:38-45
# already wrote down that portable PowerShell 7 was the answer; this is that.
#
# **The JDK is the one piece here nobody has watched run.** It is x86-64 by the
# user's explicit choice — consistency with the rest of the payload, chosen with
# the tradeoff in front of them, since an aarch64 Temurin exists and would run
# native. What that buys is JIT-on-JIT: HotSpot writing code at runtime and FEX
# translating it. See TOOLS_JAVA_VERSION in native/pins.env for the cost in full
# and for the plain statement that whether a JVM starts here is unverified.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init

COMPONENT=tools
VERSION="$TOOLS_VERSION"

# **Explicit, never derived, and this is the one component where "derived" is
# actively wrong.** package_wcp.py's version_code packs each dotted part into two
# decimal digits — the shipped Git package derives 25500 from "2.55.0.3" that way
# — so a naive code from a version string like this one lands nowhere useful. It
# is worth writing down what the derivation actually produces, because the number
# is not the obvious one: version_code("1.1.0") is 10100, and vessel_version_code
# multiplies by 100 for the revision, giving 1,010,000. That is above the 1.0.0
# already installed and would technically work, which is exactly what makes it a
# trap — a scheme that happens to be correct is not a scheme.
#
# So the code is a literal. 1,100,000 reads as "1.1.0, revision 0" to a human
# under the same 100-per-part convention the rest of the repo uses, it is
# comfortably above the shipped Git component's 25500, and it leaves two digits
# underneath for packaging-only revisions. TOOLS_REVISION is what those digits
# are for; it is 0 and asserted to be, because a revision bump that this literal
# silently ignored would be the same class of no-op as everything below.
#
# **That no-op has two faces, and adding PowerShell and the JDK walked straight
# at the second one.** Being above the shipped Git is not enough:
# WcpInstaller.kt:290-305 skips unpacking any package whose type+versionCode pair
# the store already holds, and 1.0.0 at 1,000,000 is already installed on the
# device. Rebuilding this payload with two more programs in it under the same
# code would have produced a larger .wcp, a larger APK, a successful
# `adb install`, and a container with no `pwsh` and no `java` in it and nothing
# anywhere saying why. Changing the payload means changing the version:
# TOOLS_VERSION is now 1.1.0. One bump, both programs — they land together, so
# there is nothing for a second bump to distinguish.
VERSION_CODE=1100000
[ "${TOOLS_REVISION:-0}" = 0 ] || die "TOOLS_REVISION is ${TOOLS_REVISION}, but
     VERSION_CODE above is the literal 1100000 and does not read it. Either fold
     the revision into that literal by hand (1100000 + revision) or put the
     derivation back — silently ignoring it is how a rebuild ships under a code
     the store already has and installs nothing."
[ "$VERSION_CODE" -gt 25500 ] || die "version code $VERSION_CODE is not above the
     shipped Git component's 25500, so adoptLatest would refuse to move a
     container's Tools reference forward and this build would install nothing.
     See the version-code note in build/common.sh."

# 7-Zip is the one tool this script needs that the rest of the build does not:
# Git for Windows' portable release is a 7z self-extracting .exe. bsdtar is not a
# substitute here — libarchive scans only the first 0x27000 bytes of an SFX for
# the archive signature and Git's stub is bigger than that. The Dockerfile
# installs p7zip-full for exactly this line.
command -v 7z >/dev/null 2>&1 \
  || die "7z not found on PATH. Git for Windows ships PortableGit as a 7-Zip
     self-extracting archive and this is what reads it (Debian/Ubuntu:
     p7zip-full). Rebuild the image: docker build -t vessel-build ."

GIT_ARCHIVE="PortableGit-$TOOLS_GIT_VERSION-64-bit.7z.exe"
GIT_URL="https://github.com/git-for-windows/git/releases/download/$TOOLS_GIT_TAG/$GIT_ARCHIVE"

PYTHON_ARCHIVE="python-$TOOLS_PYTHON_VERSION-embed-amd64.zip"
PYTHON_URL="https://www.python.org/ftp/python/$TOOLS_PYTHON_VERSION/$PYTHON_ARCHIVE"

NODE_DIRNAME="node-v$TOOLS_NODE_VERSION-win-x64"
NODE_ARCHIVE="$NODE_DIRNAME.zip"
NODE_URL="https://nodejs.org/dist/v$TOOLS_NODE_VERSION/$NODE_ARCHIVE"

PWSH_ARCHIVE="PowerShell-$TOOLS_PWSH_VERSION-win-x64.zip"
PWSH_URL="https://github.com/PowerShell/PowerShell/releases/download/v$TOOLS_PWSH_VERSION/$PWSH_ARCHIVE"

# Temurin spells its build identifier three different ways in three places and
# native/pins.env pins it once, so the other two are derived here rather than
# pinned separately — a second and third copy of "21.0.12+8" is a second and
# third thing to forget on a bump.
#   TOOLS_JAVA_VERSION  21.0.12+8      the version
#   JAVA_DIRNAME        jdk-21.0.12+8  the directory inside the zip
#   JAVA_ARCHIVE        …_21.0.12_8…   `+` -> `_` in the file name
#   JAVA_URL            …jdk-21.0.12%2B8…  `+` percent-encoded in the release tag,
#                       because a raw `+` in a URL path is not a literal plus
JAVA_MAJOR="${TOOLS_JAVA_VERSION%%.*}"
JAVA_DIRNAME="jdk-$TOOLS_JAVA_VERSION"
JAVA_ARCHIVE="OpenJDK${JAVA_MAJOR}U-jdk_x64_windows_hotspot_${TOOLS_JAVA_VERSION/+/_}.zip"
JAVA_URL="https://github.com/adoptium/temurin$JAVA_MAJOR-binaries/releases/download/${JAVA_DIRNAME/+/%2B}/$JAVA_ARCHIVE"

GET_PIP_URL="https://bootstrap.pypa.io/get-pip.py"

# Downloads live in the work volume, not the repo: ~400 MB that a re-run should
# not fetch again, and nothing here belongs in a bind mount. The JDK and
# PowerShell are three quarters of that between them.
CACHE="$WORK_DIR/$COMPONENT-downloads"
mkdir -p "$CACHE"

# Same shape as wine.sh's fetch_addon, and for the same reason: a pinned hash
# that is checked is a pin, and one that is only written down is a comment.
# Verified on every run, not only after a download, so a half-written cache entry
# from a killed build is caught rather than unpacked.
fetch_pinned() {
  # <url> <expected sha256> [name]
  local url="$1" want="$2" name="${3:-$(basename "$1")}" got
  if [ ! -f "$CACHE/$name" ]; then
    info "fetching $name"
    curl -fSL --retry 3 -o "$CACHE/$name.part" "$url" || die "could not download $url"
    mv "$CACHE/$name.part" "$CACHE/$name"
  fi
  got="$(sha256sum "$CACHE/$name" | cut -d' ' -f1)"
  [ "$got" = "$want" ] || die "$name sha256 is
       $got
     but native/pins.env pins
       $want
     Upstream moved, the download is corrupt, or the pin is wrong. Do not
     'fix' this by pasting the computed value in without finding out which."
  info "$name  $(stat -c%s "$CACHE/$name") bytes  sha256 ok"
}

# Every PE in this payload should be x86-64, and that is the whole architecture
# decision expressed as an assertion. `file` reads IMAGE_FILE_HEADER.Machine, so
# an ARM64 binary that slipped in — the wrong release asset, a mirror serving the
# arm64 file under the x64 name — fails here rather than on the phone, where the
# symptom would be a program that simply does not start.
#
# Note this is not verify_pe_dll from common.sh: that one exists to tell ARM64EC
# apart from plain x64, which is a distinction nothing in this payload has.
verify_pe_x64() {
  local exe="$1" label="${2:-$1}" out
  [ -f "$exe" ] || die "$label: expected a file at $exe and there is none"
  out="$(file -b "$exe")"
  grep -Eqi 'PE32\+ executable' <<< "$out" || die "$label is not a 64-bit PE: $out"
  grep -Eqi 'x86-64' <<< "$out" || die "$label is not x86-64: $out"
  info "$label: $out"
}

fetch_pinned "$GIT_URL"    "$TOOLS_GIT_SHA256"
fetch_pinned "$PYTHON_URL" "$TOOLS_PYTHON_SHA256"
fetch_pinned "$NODE_URL"   "$TOOLS_NODE_SHA256"
fetch_pinned "$PWSH_URL"   "$TOOLS_PWSH_SHA256"
fetch_pinned "$JAVA_URL"   "$TOOLS_JAVA_SHA256"
fetch_pinned "$GET_PIP_URL" "$TOOLS_GET_PIP_SHA256"

STAGE="$WORK_DIR/stage-$COMPONENT"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# --- Git ---------------------------------------------------------------------
#
# Straight out of the SFX, unchanged. `post-install.bat` at the root is left
# where it is and not run: it is the script Git for Windows' installer would call
# on the target machine, and running it here would bake this build host's answers
# into the payload. MSYS2's own first-run script runs on the device instead — see
# installTools(), which pre-creates the three directories it cannot make itself.
log "unpacking Git $TOOLS_GIT_VERSION"
7z x -bso0 -bsp0 -o"$STAGE/Git" -y "$CACHE/$GIT_ARCHIVE" \
  || die "7z could not unpack $GIT_ARCHIVE"

verify_pe_x64 "$STAGE/Git/cmd/git.exe" "Git/cmd/git.exe"
# The prefix directory is the thing PrefixRegistry.toolsPath has to agree with,
# and the ARM64 build calls it `clangarm64` while this one calls it `mingw64`.
# Asserting it here is what stops a silent architecture swap upstream turning the
# PATH seed into three entries that point at nothing.
[ -d "$STAGE/Git/mingw64/bin" ] \
  || die "Git payload has no mingw64/bin — this is not the x64 build.
     PrefixRegistry.toolsPath names \$GIT_DIR\\mingw64\\bin and MSYSTEM=MINGW64;
     if upstream has renamed the prefix, both have to move with it."
ok "Git: $(find "$STAGE/Git" -type f | wc -l) file(s)"

# --- Python ------------------------------------------------------------------
log "unpacking Python $TOOLS_PYTHON_VERSION"
mkdir -p "$STAGE/Python"
unzip -q "$CACHE/$PYTHON_ARCHIVE" -d "$STAGE/Python" || die "could not unpack $PYTHON_ARCHIVE"
verify_pe_x64 "$STAGE/Python/python.exe" "Python/python.exe"

# `import site`, uncommented.
#
# The embeddable package ships a `python3XX._pth` beside python.exe, and the
# presence of that file is what puts the interpreter in isolated mode: sys.path
# is exactly the file's contents, `site` does not run, and therefore
# `Lib\site-packages` is not on the path at all. That is correct for CPython's
# stated purpose for this archive — an interpreter embedded in someone else's
# application — and wrong for ours, which is a Python a user installs packages
# into. Uncommenting the line is CPython's own documented way to turn it back on.
#
# Found by glob rather than by name so a version bump in pins.env does not need
# an edit here, and asserted to match exactly one file so a rename upstream is a
# failed build rather than a Python with no pip and no error.
PTH="$(find "$STAGE/Python" -maxdepth 1 -name 'python*._pth')"
[ "$(printf '%s\n' "$PTH" | grep -c .)" = 1 ] \
  || die "expected exactly one python*._pth in the embeddable package, found:
$PTH"
#
# Neither the grep nor the sed anchors at end-of-line, and that is not
# sloppiness: the file ships with CRLF endings, so `^#import site$` matches
# nothing and the first version of this check failed on a perfectly ordinary
# archive. Leaving the `\r` where it is also keeps the file's endings uniform,
# which is what a Windows tool reading it back would expect.
grep -q '^#import site' "$PTH" \
  || die "$(basename "$PTH") has no commented-out 'import site' line to enable.
     Either it is already on (harmless, delete this check) or the embeddable
     package's layout has changed and the pip bootstrap below needs rethinking."
sed -i 's/^#import site/import site/' "$PTH"
ok "$(basename "$PTH"): site enabled"

# pip, installed by the build host into the Windows tree.
#
# **get-pip.py is meant to be run BY the interpreter it is bootstrapping, and
# that is not possible here.** This host is Linux and python.exe is a Windows
# x86-64 PE; there is no Wine in this image and adding one to run a build step
# would be a strange amount of machinery for what the step actually does. So the
# host's python3 runs it with `--target`, which is the flag that turns a pip
# install into "unpack this wheel into that directory and do nothing else".
#
# That substitution is only safe because of what pip is: pip's own wheel is
# `py3-none-any`, pure Python with no compiled extension and no interpreter
# version in its layout, so the bytes that land in Lib\site-packages are the same
# bytes whichever Python unpacked them. It would NOT be safe for a package with a
# C extension, and this script should not grow one.
#
# What is lost is the console-script launchers: pip generates `Scripts\pip.exe`
# from sysconfig of the *running* interpreter, so a Linux host produces unix
# shell scripts or, under --target, nothing at all. The shim below replaces them.
log "bootstrapping pip"
SITE_PACKAGES="$STAGE/Python/Lib/site-packages"
mkdir -p "$SITE_PACKAGES"
python3 "$CACHE/get-pip.py" \
  --target "$SITE_PACKAGES" \
  --no-cache-dir \
  --no-warn-script-location \
  --quiet \
  || die "get-pip.py failed"
# --target leaves a unix `bin/` behind on some pip versions. It is scripts for an
# interpreter that does not exist on the device, so it goes.
rm -rf "$SITE_PACKAGES/bin"
[ -d "$SITE_PACKAGES/pip" ] || die "pip is not in $SITE_PACKAGES after get-pip.py"

# `Scripts\`, and why it holds a .bat rather than the usual .exe.
#
# The PATH seed names `…\Python\Scripts` because that is where pip puts console
# scripts, and PrefixRegistry's rule is that the seed never names a directory the
# build does not deliver. It has to be delivered as a directory with a FILE in
# it: package_wcp.py's payload is a list of files and an empty directory does not
# survive the tar, so a `Scripts` with nothing in it would arrive on the device
# as no `Scripts` at all.
#
# A .bat and not a .exe because a real pip.exe is a distlib launcher stub with a
# zipped entry point appended, built by pip against the target interpreter's
# sysconfig — the same thing the --target install above cannot do from Linux.
# `pip` still resolves from cmd, because PATHEXT includes .BAT, and the first
# real `pip install` on the device writes proper .exe launchers beside this one
# for whatever it installs.
#
# %~dp0 ends with a backslash, hence `%~dp0..\python.exe` and not a separator.
# CRLF, written explicitly, because a heredoc on this host produces LF and every
# other batch file on the system is CRLF. cmd tolerates LF in a file this simple;
# it does not tolerate it reliably around labels and `goto`, and a build host's
# line endings are not a property this file should inherit by accident.
mkdir -p "$STAGE/Python/Scripts"
printf '%s\r\n' \
  '@echo off' \
  'rem Written by build/tools.sh. See the comment there for why this is a .bat.' \
  '"%~dp0..\python.exe" -m pip %*' \
  > "$STAGE/Python/Scripts/pip.bat"
cp "$STAGE/Python/Scripts/pip.bat" "$STAGE/Python/Scripts/pip3.bat"
ok "Python: $(find "$STAGE/Python" -type f | wc -l) file(s), pip in Lib/site-packages"

# --- Node --------------------------------------------------------------------
#
# The zip unpacks to node-v<ver>-win-x64/, one directory deep. Flattened, because
# the payload subdirectory root is what installTools() copies and what the PATH
# seed names — a `Node\node-v26.7.0-win-x64\node.exe` would put the version in
# the PATH and make every future bump a registry seed change.
log "unpacking Node $TOOLS_NODE_VERSION"
NODE_TMP="$WORK_DIR/$COMPONENT-node"
rm -rf "$NODE_TMP"
mkdir -p "$NODE_TMP"
unzip -q "$CACHE/$NODE_ARCHIVE" -d "$NODE_TMP" || die "could not unpack $NODE_ARCHIVE"
[ -d "$NODE_TMP/$NODE_DIRNAME" ] \
  || die "expected $NODE_DIRNAME/ inside $NODE_ARCHIVE; found: $(ls "$NODE_TMP")"
mv "$NODE_TMP/$NODE_DIRNAME" "$STAGE/Node"
rm -rf "$NODE_TMP"

verify_pe_x64 "$STAGE/Node/node.exe" "Node/node.exe"
# npm is a .cmd shim over node_modules/npm; without it `npm` from cmd is nothing.
for f in npm.cmd npx.cmd node_modules/npm/package.json; do
  [ -e "$STAGE/Node/$f" ] || die "Node payload is missing $f"
done
ok "Node: $(find "$STAGE/Node" -type f | wc -l) file(s)"

# --- PowerShell --------------------------------------------------------------
#
# The only one of the five that needs no reshaping at all: the win-x64 zip is
# already flat, with `pwsh.exe` and its ~660 sibling assemblies at the archive
# root and the module tree under them. So it unpacks straight into `Pwsh/`, which
# gets the same result Node's flattening does — nothing with a version string in
# it ends up on PATH, so bumping TOOLS_PWSH_VERSION never touches the registry
# seed.
#
# Self-contained .NET, which is why there is no runtime step here and no
# wine-mono anywhere in this repo: the CoreCLR is in the zip as native x86-64 PE.
# That also rules out the alternative, since a runtime install would mean an
# `.msi` and docs/TODO.md #17 is an open, measured `.msi` failure in this
# container.
log "unpacking PowerShell $TOOLS_PWSH_VERSION"
mkdir -p "$STAGE/Pwsh"
unzip -q "$CACHE/$PWSH_ARCHIVE" -d "$STAGE/Pwsh" || die "could not unpack $PWSH_ARCHIVE"
[ -f "$STAGE/Pwsh/pwsh.exe" ] \
  || die "no pwsh.exe at the root of $PWSH_ARCHIVE; found: $(ls "$STAGE/Pwsh" | head -5)
     This archive has always been flat. If upstream has wrapped it in a
     versioned directory, flatten it here the way the Node block does — the
     PATH seed names \$PWSH_DIR and nothing below it."

verify_pe_x64 "$STAGE/Pwsh/pwsh.exe" "Pwsh/pwsh.exe"
# The CoreCLR itself, checked separately. `pwsh.exe` is a small apphost stub and
# would be the right architecture even in a build whose runtime was not, which is
# the exact shape of the mistake verify_pe_x64 exists to catch.
verify_pe_x64 "$STAGE/Pwsh/coreclr.dll" "Pwsh/coreclr.dll"
ok "Pwsh: $(find "$STAGE/Pwsh" -type f | wc -l) file(s)"

# --- Java --------------------------------------------------------------------
#
# Flattened out of jdk-<ver>/, for the reason the Node block gives: a version
# string inside the tree becomes a version string in PATH, and PrefixRegistry's
# JAVA_DIR would then have to move on every bump.
#
# The JDK rather than the JRE, and Temurin ships no separate JRE for 21 anyway —
# `jlink` is how you get one now. It costs ~196 MB of download for a payload that
# was already the largest thing in the APK; that is the trade and it is stated in
# app/build.gradle.kts where it is felt.
log "unpacking Java $TOOLS_JAVA_VERSION"
JAVA_TMP="$WORK_DIR/$COMPONENT-java"
rm -rf "$JAVA_TMP"
mkdir -p "$JAVA_TMP"
unzip -q "$CACHE/$JAVA_ARCHIVE" -d "$JAVA_TMP" || die "could not unpack $JAVA_ARCHIVE"
[ -d "$JAVA_TMP/$JAVA_DIRNAME" ] \
  || die "expected $JAVA_DIRNAME/ inside $JAVA_ARCHIVE; found: $(ls "$JAVA_TMP")"
mv "$JAVA_TMP/$JAVA_DIRNAME" "$STAGE/Java"
rm -rf "$JAVA_TMP"

verify_pe_x64 "$STAGE/Java/bin/java.exe" "Java/bin/java.exe"
# HotSpot itself, for the same reason coreclr.dll is checked above: java.exe is a
# launcher and the VM is the thing that will actually be executing translated
# code. This is also the assertion that says out loud what the payload signed up
# for — a JIT running under a JIT. Whether it *works* is unverified; see
# native/pins.env.
verify_pe_x64 "$STAGE/Java/bin/server/jvm.dll" "Java/bin/server/jvm.dll"
# `release` is the JDK's own record of what it is, and javac is what makes this a
# JDK rather than a runtime. Both absent means a repackaged or partial tree.
for f in release lib/modules bin/javac.exe; do
  [ -e "$STAGE/Java/$f" ] || die "Java payload is missing $f"
done
ok "Java: $(find "$STAGE/Java" -type f | wc -l) file(s)"

# --- Provenance --------------------------------------------------------------
#
# Written here rather than through common.sh's write_provenance, and the reason
# is that every field that helper fills would be a lie for this component. It
# records the NDK version, the API level and the CPU tuning flags off the
# environment, and this build uses none of them — a `"ndk": "r29"` on a package
# containing three upstream zips is exactly the kind of plausible, unearned
# detail the rest of this repo's documentation rules exist to keep out. The key
# names and shape are kept identical so the Components screen reads it the same
# way, and `builtBy` says plainly what happened, as the hand-made Git package it
# replaces already did.
#
# One version per upstream tree, because "1.1.0" answers nothing anyone would
# ask of this package.
write_tools_provenance() {
  cat > "$STAGE/provenance.json" <<EOF
{
  "component": "$COMPONENT",
  "version": "$VERSION",
  "target": "x64",
  "targetDesc": "x86-64 Windows binaries, run under FEX",
  "sourceRepo": "upstream release archives; see native/pins.env",
  "sourceRef": "git $TOOLS_GIT_VERSION, python $TOOLS_PYTHON_VERSION, node $TOOLS_NODE_VERSION, pwsh $TOOLS_PWSH_VERSION, temurin jdk $TOOLS_JAVA_VERSION",
  "sourceSha": "$GIT_ARCHIVE $TOOLS_GIT_SHA256; $PYTHON_ARCHIVE $TOOLS_PYTHON_SHA256; $NODE_ARCHIVE $TOOLS_NODE_SHA256; $PWSH_ARCHIVE $TOOLS_PWSH_SHA256; $JAVA_ARCHIVE $TOOLS_JAVA_SHA256; get-pip.py $TOOLS_GET_PIP_SHA256",
  "cpuFlags": "none",
  "ndk": "n/a",
  "apiLevel": "n/a",
  "builtBy": "upstream release archives (repackaged verbatim, not rebuilt; pip bootstrapped on the build host)"
}
EOF
}
write_tools_provenance

log "packaging"
python3 "$COMMON_SH_DIR/package_wcp.py" \
  --type Tools \
  --name "Tools $VERSION — Git $TOOLS_GIT_VERSION, Python $TOOLS_PYTHON_VERSION, Node $TOOLS_NODE_VERSION, PowerShell $TOOLS_PWSH_VERSION, JDK $TOOLS_JAVA_VERSION (x64)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Git, Python, Node.js, PowerShell 7 and the Temurin JDK as x86-64 Windows binaries, installed into C:\\Program Files\\{Git,Python,Node,PowerShell,Java}" \
  --out "$DIST_DIR/$COMPONENT-$VERSION-x64.wcp"

ok "dist/$COMPONENT-$VERSION-x64.wcp"
