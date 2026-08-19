#!/usr/bin/env bash
# Package Git, Python, Node.js, PowerShell 7, a JDK, Cascadia Mono and GNU Unifont
# as one ARM64 `Tools` component.
#
#   ./build/tools.sh            # -> dist/tools-<ver>-arm64.wcp
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
# **Why ARM64 for all five, and why this is a revert.** Vessel runs Wine ARM64EC
# on Android, so an ARM64 Windows PE runs natively and an x86-64 one is
# translated by FEX. 1.1.0 built the whole payload x86-64 on a belief about the
# wheel ecosystem — "PyPI resolves far more `win_amd64` wheels than `win_arm64`"
# — and that belief has now been measured false. Counted against PyPI on
# 2026-08-17, wheels per release:
#
#     numpy 2.5.2        arm64 6   amd64 6
#     pandas 3.0.5       arm64 5   amd64 5
#     pillow 12.3.0      arm64 8   amd64 9
#     lxml 6.1.1         arm64 7   amd64 9
#     pydantic-core 2.48 arm64 7   amd64 9
#     cryptography 50.0  arm64 0   amd64 4
#
# That is parity, not a gap. **cryptography is the one real cost and it is named
# here rather than discovered later**: it publishes no `win_arm64` wheel at all,
# so `pip install cryptography` fails outright on this payload — there is no C
# compiler in the prefix to build the sdist. It fails loudly at install time
# rather than running slowly, which is the better of the two failures but is
# still a real thing a user will hit. Git's tree was ARM64 in the component that
# originally shipped (dist/git-2.55.0.3-arm64.wcp); the all-x64 decision reversed
# it and this reverses that.
#
# **The measured failure that drives it: PowerShell x86-64 crashes.** Two
# unhandled `c0000005` access violations at 0x6f9d20dbec and 0x6f9d22ad05 the
# moment .NET does real work — same region, and inside no module Wine logged,
# which places them in FEX's JIT buffer rather than in any Wine DLL. Not a
# connectivity problem: `ping google.com` from inside the same prefix resolves
# DNS and gets 4/4 replies. So the fault is .NET-under-FEX. ARM64 does not work
# around that, it removes the translation layer the fault lives in.
#
# **Unverified.** Nothing in this payload has been run on the device as ARM64.
# That the crash goes away is the expectation behind the change, not a result.
#
# **Git is mixed-architecture either way, and this is not a caveat about the
# switch — it is true of both builds.** Measured by reading
# IMAGE_FILE_HEADER.Machine off the ARM64 tree: `cmd/git.exe`, `bin/git.exe` and
# `clangarm64/bin/git.exe` are 0xAA64, while `usr/bin/bash.exe`, `usr/bin/ls.exe`
# and `usr/bin/msys-2.0.dll` are 0x8664 — there is no ARM64 port of msys-2.0, so
# the shell layer is translated in both variants. What ARM64 buys is a native
# `git.exe`, which is the binary anything actually invokes. It does NOT make Git
# Bash native and it does not touch the `fork()` emulation TerminalProfile.kt:56-70
# measured pegging a core at 98%; PowerShell is still the answer to that.
#
# **Fonts/ is the sixth tree and the only one that is not a program.** Three faces
# and they are here because the container had no fonts at all: measured on the
# device, `prefix/drive_c/windows/Fonts/` is empty, `HKCU\Console` had no
# `FaceName`, and Claude Code's TUI drew every horizontal rule as `□□□□□`. The Wine
# component ships `.fon` bitmap faces and non-monospace TTFs; Android contributes
# `CutiveMono.ttf` and `DroidSansMono.ttf`. Nothing in that set has a U+2500 block,
# so a box was the correct thing for the renderer to draw.
#
# **1.3.0 answered that with GNU Unifont, 1.4.0 swapped it for Cascadia Mono, and
# 1.5.0 ships both — because the question the first two were answering was the
# wrong question.** Unifont covered everything and is bitmap-derived and ugly;
# Cascadia is legible and is missing 12 glyphs the TUI draws on every tool-call
# line. Each release wrote the other's property down as the unavoidable price, on
# the strength of one claim: "conhost resolves one face with no font linking". The
# second half of that is false. Per-glyph fallback is GDI's job, not conhost's,
# Wine implements it, and 1.5.0 uses it: **Cascadia stays the face and Unifont
# becomes its linked fallback**, so nothing changes for the glyphs Cascadia has and
# everything it lacks resolves behind it. `PrefixRegistry.fontLink` seeds the link;
# TOOLS_UNIFONT_VERSION in native/pins.env has the four-hop mechanism read out of
# `native/wine/dlls/win32u/font.c`, the ordered chain and why it is ordered, the
# measured union coverage, the five tiers that were measured and the four that were
# rejected, and what stays unverified.
#
# **The one thing font linking does NOT fix, so nobody goes looking for it here.**
# CJK and emoji now render rather than being tofu, and they are geometrically
# wrong. conhost has no double-width support at all: `programs/conhost/window.c:725`
# and `:797` both carry "FIXME: use maximum width for DBCS codepages since some
# chars take two cells", and the draw loop pins every glyph to
# `i * console->active->font.width` with `dx[] = font.width` (window.c:483), so a
# full-width glyph overlaps its neighbour. The buffer model is one cell per
# character where Windows stores a wide character as two cells with
# `COMMON_LVB_LEADING_BYTE`/`TRAILING_BYTE`, so a program that thinks a CJK
# character occupies two columns is mis-aligned before anything is drawn. Fixing
# that is wide cells in conhost — `write_console`, the buffer model and the
# renderer — and is out of scope. Emoji are monochrome for a separate reason:
# Unifont Upper is outlines and Wine's FreeType path has no colour-glyph format.
#
# It is in the Tools payload rather than in a component of its own for the reason
# every other tree is: a container references exactly one component per type, so a
# `Fonts` package published as `Tools` would replace this one.
#
# **`Fonts/` is also the one tree that does NOT install like the others.** It goes
# into `drive_c\windows\Fonts`, which is Wine's directory and not ours —
# `installToolTree` does `deleteTree(target)` and a rename, which would throw away
# any font a guest program installed and fight Wine over a directory it owns. So
# SessionRuntime has a separate, gentler step that copies the font *files* in and
# leaves everything else alone. This script's only obligation is the directory
# name.
#
# **Layout: Git/, Python/, Node/, Pwsh/, Java/, Fonts/ at the payload root.** The shipped
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
# **The JDK is the one piece here nobody has watched run**, and the switch is
# worth the most on it. 1.1.0 took the x86-64 Temurin for payload consistency and
# wrote down what that bought: JIT-on-JIT, HotSpot generating and then rewriting
# machine code while FEX translates code that did not exist when the process
# started. That is FEX's hardest path — the whole FEX_REVISION history in
# native/pins.env is about it. The aarch64 Temurin has HotSpot emitting ARM64
# directly, so none of that machinery is on the path at all. Whether a JVM starts
# here is still unverified; see TOOLS_JAVA_VERSION in native/pins.env.

. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

vessel_init

COMPONENT=tools
VERSION="$TOOLS_VERSION"

# **Explicit, never derived, and this is the one component where "derived" is
# actively wrong.** package_wcp.py's version_code packs each dotted part into two
# decimal digits — the shipped Git package derives 25500 from "2.55.0.3" that way
# — so a naive code from a version string like this one lands nowhere useful. It
# is worth writing down what the derivation actually produces, because the number
# is not the obvious one: version_code("1.5.0") is 10500, and vessel_version_code
# multiplies by 100 for the revision, giving 1,050,000. That is BELOW the
# 1,400,000 already on the device, so a derived code here would be adopted as
# older than what is installed and this build would change nothing — a scheme
# that happens to be correct is not a scheme, and this time it would not even
# happen to be correct.
#
# So the code is a literal. 1,500,000 reads as "1.5.0, revision 0" to a human
# under the same 100-per-part convention the rest of the repo uses, it is
# comfortably above the shipped Git component's 25500, and it leaves two digits
# underneath for packaging-only revisions. TOOLS_REVISION is what those digits
# are for; it is 0 and asserted to be, because a revision bump that this literal
# silently ignored would be the same class of no-op as everything below.
#
# **That no-op has two faces, and every contents change walks straight at the
# second one.** Being above the shipped Git is not enough:
# WcpInstaller.kt:290-305 skips unpacking any package whose type+versionCode pair
# the store already holds, and **1.4.0 at 1,400,000 is installed on the device
# right now**. Adding the two Unifont files back under the same code would produce
# a different .wcp, a different APK, a successful `adb install`, and a container
# whose `windows\Fonts` still holds Cascadia alone — so the SystemLink value the
# seed writes would name two files that are not registered, every entry would be
# dropped with a `TRACE` line (win32u/font.c:2076), and the console would render
# exactly as it does today. A font-linking change is the worst possible candidate
# for this no-op, because a chain that silently degrades to its base font is what
# the design does on purpose when a file is missing. So the minor moves:
# TOOLS_VERSION is now 1.7.0. (1.2.0 through 1.6.0 all moved for the same reason,
# one to five steps earlier.)
VERSION_CODE=1700000
[ "${TOOLS_REVISION:-0}" = 0 ] || die "TOOLS_REVISION is ${TOOLS_REVISION}, but
     VERSION_CODE above is the literal 1700000 and does not read it. Either fold
     the revision into that literal by hand (1500000 + revision) or put the
     derivation back — silently ignoring it is how a rebuild ships under a code
     the store already has and installs nothing."
# The floor is what is actually installed, not the oldest thing that ever was.
# It used to be the shipped Git component's 25500, then Tools 1.1.0's 1,100,000,
# then 1.2.0's 1,200,000, then 1.3.0's 1,300,000, then 1.4.0's 1,400,000; Tools
# 1.6.0 at 1,600,000 has since been built and installed on the device, so that is
# the number a new build has to clear. Anything at or under it is adopted as no newer than what
# is there and the build looks exactly like a package that did not install.
[ "$VERSION_CODE" -gt 1600000 ] || die "version code $VERSION_CODE is not above
     Tools 1.6.0's 1600000, which is installed on the device, so
     WcpInstaller would skip unpacking it and adoptLatest would refuse to move a
     container's Tools reference forward — this build would install nothing.
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

# Five upstreams, five spellings of "ARM64" in a file name: `-arm64`, `-arm64`,
# `-win-arm64`, `-win-arm64` and `aarch64`. There is no shared token to factor
# out, so each is written where it is used.
GIT_ARCHIVE="PortableGit-$TOOLS_GIT_VERSION-arm64.7z.exe"
GIT_URL="https://github.com/git-for-windows/git/releases/download/$TOOLS_GIT_TAG/$GIT_ARCHIVE"

PYTHON_ARCHIVE="python-$TOOLS_PYTHON_VERSION-embed-arm64.zip"
PYTHON_URL="https://www.python.org/ftp/python/$TOOLS_PYTHON_VERSION/$PYTHON_ARCHIVE"

NODE_DIRNAME="node-v$TOOLS_NODE_VERSION-win-arm64"
NODE_ARCHIVE="$NODE_DIRNAME.zip"
NODE_URL="https://nodejs.org/dist/v$TOOLS_NODE_VERSION/$NODE_ARCHIVE"

PWSH_ARCHIVE="PowerShell-$TOOLS_PWSH_VERSION-win-arm64.zip"
PWSH_URL="https://github.com/PowerShell/PowerShell/releases/download/v$TOOLS_PWSH_VERSION/$PWSH_ARCHIVE"

# Pale Moon. No architecture in the URL at all -- upstream spells it in the file
# name (`win64`) and nowhere else, which makes this the only one of the six that
# needs no per-upstream ARM64 spelling.
#
# `rm-us` and not the `download.php?mirror=us&bits=64&type=7z` the site links:
# that is a 303 to exactly this path, and a redirect resolved at build time is a
# pin that moves. Resolved and recorded on 2026-08-19.
PALEMOON_DIRNAME="palemoon"
PALEMOON_ARCHIVE="palemoon-$TOOLS_PALEMOON_VERSION.win64.7z"
PALEMOON_URL="https://rm-us.palemoon.org/release/$PALEMOON_ARCHIVE"

# Temurin spells its build identifier three different ways in three places and
# native/pins.env pins it once, so the other two are derived here rather than
# pinned separately — a second and third copy of "21.0.12+8" is a second and
# third thing to forget on a bump.
#   TOOLS_JAVA_VERSION  21.0.12+8      the version
#   JAVA_DIRNAME        jdk-21.0.12+8  the directory inside the zip
#   JAVA_ARCHIVE        …_21.0.12_8…   `+` -> `_` in the file name
#   JAVA_URL            …jdk-21.0.12%2B8…  `+` percent-encoded in the release tag,
#                       because a raw `+` in a URL path is not a literal plus
#
# `aarch64` and not `arm64` in the archive name: Adoptium spells the architecture
# the GNU way while the other four upstreams spell it the Windows way. The
# directory inside the zip is `jdk-21.0.12+8` for both architectures, so nothing
# below this line changed with the switch.
JAVA_MAJOR="${TOOLS_JAVA_VERSION%%.*}"
JAVA_DIRNAME="jdk-$TOOLS_JAVA_VERSION"
JAVA_ARCHIVE="OpenJDK${JAVA_MAJOR}U-jdk_aarch64_windows_hotspot_${TOOLS_JAVA_VERSION/+/_}.zip"
JAVA_URL="https://github.com/adoptium/temurin$JAVA_MAJOR-binaries/releases/download/${JAVA_DIRNAME/+/%2B}/$JAVA_ARCHIVE"

GET_PIP_URL="https://bootstrap.pypa.io/get-pip.py"

# Cascadia Mono. One zip with no architecture in its name — a font is data — and
# one member out of it, named here rather than discovered at extraction time.
#
# **CASCADIA_MEMBER is the whole choice expressed as a path**, so all four
# decisions are readable in one line and each is asserted below:
#   `ttf/`         TrueType rather than the `otf/` or `woff2/` trees beside it.
#   `static/`      a single-weight file, because conhost asks GDI for one weight
#                  through CreateFontIndirectW and a variable font's default
#                  instance is a thing to avoid rather than rely on.
#   `CascadiaMono` Mono and not Code: Code carries programming ligatures, which
#                  draw across cell boundaries in a fixed console grid.
#   `-Regular`     and not `-Light`/`-SemiBold`/`-Italic`, and not the `NF` or
#                  `PL` cuts, which add Nerd Font and powerline glyph sets this
#                  console has no way to reach anyway (see native/pins.env).
# Chosen by listing the archive: it holds 12 files under `ttf/` and 84 under
# `ttf/static/`, and this is the one 575,912-byte member of them all that a
# console wants.
CASCADIA_ARCHIVE="CascadiaCode-$TOOLS_CASCADIA_VERSION.zip"
CASCADIA_URL="https://github.com/microsoft/cascadia-code/releases/download/v$TOOLS_CASCADIA_VERSION/$CASCADIA_ARCHIVE"
CASCADIA_MEMBER="ttf/static/CascadiaMono-Regular.ttf"
CASCADIA_FONT="$(basename "$CASCADIA_MEMBER")"

# GNU Unifont, the fallback tier. Two files from one release directory on
# ftp.gnu.org, and no architecture in either name — a font is data.
#
# `.otf` and not `.ttf`: 17.0.05 ships an OTF build only. unifoundry's
# `font-builds/*.ttf` paths 404 for this release, and there is nothing to work
# around — Wine hands every file in `windows\Fonts` to FreeType without looking at
# the extension (win32u/font.c `load_directory_fonts`), and FreeType reads CFF.
UNIFONT_BASE_URL="https://ftp.gnu.org/gnu/unifont/unifont-$TOOLS_UNIFONT_VERSION"
UNIFONT_ARCHIVE="unifont-$TOOLS_UNIFONT_VERSION.otf"
UNIFONT_UPPER_ARCHIVE="unifont_upper-$TOOLS_UNIFONT_VERSION.otf"

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

# Every PE this script asserts on should be ARM64, and that is the whole
# architecture decision expressed as an assertion.
#
# **This is the guard against silently shipping x64 again, so it reads the header
# itself.** The previous version of this function asserted x86-64 and would have
# passed happily on the payload it was pointed at; an assertion that still passes
# for the wrong architecture is worse than no assertion, because it reads like
# the question was asked. So both halves are checked: `file`'s decoding of
# IMAGE_FILE_HEADER.Machine for a human-readable message, and the Machine word
# read out of the file directly and required to be 0xAA64 (IMAGE_FILE_MACHINE_ARM64).
# The second is the one that cannot be talked into a wrong answer by a phrasing
# change in libmagic's database.
#
# The failure this catches is the wrong release asset — an `-x64` or `_x64_` file
# arriving under an arm64 name, or a mirror serving one for the other. On the
# phone the symptom would be a program that runs under FEX when the whole point
# of 1.2.0 was that it should not.
#
# Note this is not verify_pe_dll from common.sh: that one exists to tell ARM64EC
# apart from plain x64 by its CHPE load-config, and nothing in this payload is
# ARM64EC. These are plain ARM64 PEs, which is what Wine's ARM64EC host runs
# natively — ARM64EC is how Wine's own modules are built, not something an
# upstream release archive would ever be.
verify_pe_arm64() {
  local exe="$1" label="${2:-$1}" out machine
  [ -f "$exe" ] || die "$label: expected a file at $exe and there is none"
  out="$(file -b "$exe")"
  grep -Eqi 'PE32\+ executable' <<< "$out" || die "$label is not a 64-bit PE: $out"
  grep -Eqi 'aarch64|arm64' <<< "$out" || die "$label is not ARM64: $out"
  # e_lfanew at 0x3c, then the Machine word 4 bytes into the PE signature.
  machine="$(python3 -c 'import struct,sys
b=open(sys.argv[1],"rb").read(0x400)
print("0x%04X" % struct.unpack_from("<H", b, struct.unpack_from("<I", b, 0x3c)[0] + 4))' "$exe")"
  [ "$machine" = "0xAA64" ] || die "$label has IMAGE_FILE_HEADER.Machine $machine,
     not 0xAA64 (IMAGE_FILE_MACHINE_ARM64). 0x8664 is x86-64 — the wrong release
     asset. \`file\` said: $out"
  info "$label: $out  Machine=$machine"
}

# The mirror of verify_pe_arm64, for the one tree that is deliberately not ARM64.
#
# It exists so that "this is x86-64" is asserted rather than assumed, exactly as
# the ARM64 case is. A Pale Moon build that arrived as ARM64 would be a different
# browser than the one native/pins.env argues for, and the whole argument turns
# on the architecture: an x86-64 process loads our ARM64EC graphics DLLs, and a
# classic ARM64 one gets STATUS_INVALID_IMAGE_FORMAT. Silently shipping the wrong
# one would reproduce the Firefox failure under a new name.
verify_pe_x64() {
  local exe="$1" label="${2:-$1}" out machine
  [ -f "$exe" ] || die "$label: expected a file at $exe and there is none"
  out="$(file -b "$exe")"
  grep -Eqi 'PE32\+ executable' <<< "$out" || die "$label is not a 64-bit PE: $out"
  grep -Eqi 'x86-64' <<< "$out" || die "$label is not x86-64: $out"
  machine="$(python3 -c 'import struct,sys
b=open(sys.argv[1],"rb").read(0x400)
print("0x%04X" % struct.unpack_from("<H", b, struct.unpack_from("<I", b, 0x3c)[0] + 4))' "$exe")"
  [ "$machine" = "0x8664" ] || die "$label has IMAGE_FILE_HEADER.Machine $machine,
     not 0x8664 (IMAGE_FILE_MACHINE_AMD64). 0xAA64 is ARM64 — the wrong release
     asset, and an ARM64 browser is the configuration that was measured not to
     work here. \`file\` said: $out"
  info "$label: $out  Machine=$machine"
}

fetch_pinned "$PALEMOON_URL" "$TOOLS_PALEMOON_SHA256"
fetch_pinned "$GIT_URL"    "$TOOLS_GIT_SHA256"
fetch_pinned "$PYTHON_URL" "$TOOLS_PYTHON_SHA256"
fetch_pinned "$NODE_URL"   "$TOOLS_NODE_SHA256"
fetch_pinned "$PWSH_URL"   "$TOOLS_PWSH_SHA256"
fetch_pinned "$JAVA_URL"   "$TOOLS_JAVA_SHA256"
fetch_pinned "$GET_PIP_URL" "$TOOLS_GET_PIP_SHA256"
fetch_pinned "$CASCADIA_URL" "$TOOLS_CASCADIA_SHA256"
fetch_pinned "$UNIFONT_BASE_URL/$UNIFONT_ARCHIVE"       "$TOOLS_UNIFONT_SHA256"
fetch_pinned "$UNIFONT_BASE_URL/$UNIFONT_UPPER_ARCHIVE" "$TOOLS_UNIFONT_UPPER_SHA256"

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

verify_pe_arm64 "$STAGE/Git/cmd/git.exe" "Git/cmd/git.exe"
# **The MSYS2 layer is x86-64 and is asserted to be, rather than passed over.**
# There is no ARM64 port of msys-2.0, so `usr/bin` is x86-64 in the ARM64 build
# exactly as it is in the x64 one. Measured on this tree by reading
# IMAGE_FILE_HEADER.Machine: cmd/git.exe, bin/git.exe and clangarm64/bin/git.exe
# are 0xAA64; usr/bin/bash.exe, usr/bin/ls.exe and usr/bin/msys-2.0.dll are
# 0x8664. Asserting the x86-64 half says out loud that the mixture is known and
# intended — and it would catch the day msys2 ports to ARM64, which is a payload
# change worth noticing rather than absorbing silently.
grep -Eqi 'x86-64' <<< "$(file -b "$STAGE/Git/usr/bin/msys-2.0.dll")" \
  || die "Git/usr/bin/msys-2.0.dll is not x86-64: $(file -b "$STAGE/Git/usr/bin/msys-2.0.dll")
     Every ARM64 Git for Windows so far ships an x86-64 MSYS2 runtime because
     msys-2.0 has no ARM64 port. If that has changed, the comments in this file,
     native/pins.env and app/build.gradle.kts all say it has not."
info "Git/usr/bin/msys-2.0.dll: $(file -b "$STAGE/Git/usr/bin/msys-2.0.dll") (x86-64 by design; no ARM64 msys2 exists)"
# The prefix directory is the thing PrefixRegistry.toolsPath has to agree with,
# and the ARM64 build calls it `clangarm64` while the x64 build calls it
# `mingw64`. Asserting it here is what stops a silent architecture swap upstream
# turning the PATH seed into three entries that point at nothing.
[ -d "$STAGE/Git/clangarm64/bin" ] \
  || die "Git payload has no clangarm64/bin — this is not the ARM64 build.
     PrefixRegistry.toolsPath names \$GIT_DIR\\clangarm64\\bin and
     MSYSTEM=CLANGARM64; if upstream has renamed the prefix, both have to move
     with it. A tree with mingw64/bin instead is the x64 asset under an arm64
     name."
ok "Git: $(find "$STAGE/Git" -type f | wc -l) file(s)"

# --- Python ------------------------------------------------------------------
log "unpacking Python $TOOLS_PYTHON_VERSION"
mkdir -p "$STAGE/Python"
unzip -q "$CACHE/$PYTHON_ARCHIVE" -d "$STAGE/Python" || die "could not unpack $PYTHON_ARCHIVE"
verify_pe_arm64 "$STAGE/Python/python.exe" "Python/python.exe"

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
# ARM64 PE; there is no Wine in this image and adding one to run a build step
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
# The zip unpacks to node-v<ver>-win-arm64/, one directory deep. Flattened,
# because the payload subdirectory root is what installTools() copies and what the
# PATH seed names — a `Node\node-v26.7.0-win-arm64\node.exe` would put the version
# AND the architecture in the PATH and make every future bump a registry seed
# change. NODE_DIRNAME above carries the architecture, which is why the switch
# from `-win-x64` needed nothing here.
log "unpacking Node $TOOLS_NODE_VERSION"
NODE_TMP="$WORK_DIR/$COMPONENT-node"
rm -rf "$NODE_TMP"
mkdir -p "$NODE_TMP"
unzip -q "$CACHE/$NODE_ARCHIVE" -d "$NODE_TMP" || die "could not unpack $NODE_ARCHIVE"
[ -d "$NODE_TMP/$NODE_DIRNAME" ] \
  || die "expected $NODE_DIRNAME/ inside $NODE_ARCHIVE; found: $(ls "$NODE_TMP")"
mv "$NODE_TMP/$NODE_DIRNAME" "$STAGE/Node"
rm -rf "$NODE_TMP"

verify_pe_arm64 "$STAGE/Node/node.exe" "Node/node.exe"
# npm is a .cmd shim over node_modules/npm; without it `npm` from cmd is nothing.
for f in npm.cmd npx.cmd node_modules/npm/package.json; do
  [ -e "$STAGE/Node/$f" ] || die "Node payload is missing $f"
done
ok "Node: $(find "$STAGE/Node" -type f | wc -l) file(s)"

# --- PowerShell --------------------------------------------------------------
#
# The only one of the five that needs no reshaping at all: the win-arm64 zip is
# already flat, with `pwsh.exe` and its ~660 sibling assemblies at the archive
# root and the module tree under them — the same shape the win-x64 zip has. So it
# unpacks straight into `Pwsh/`, which gets the same result Node's flattening does
# — nothing with a version string in it ends up on PATH, so bumping
# TOOLS_PWSH_VERSION never touches the registry seed.
#
# Self-contained .NET, which is why there is no runtime step here and no
# wine-mono anywhere in this repo: the CoreCLR is in the zip, and on this asset it
# is a native ARM64 PE. That also rules out the alternative, since a runtime
# install would mean an `.msi` and docs/TODO.md #17 is an open, measured `.msi`
# failure in this container.
#
# **This is the tree the architecture switch is for.** The x86-64 build of this
# same version crashed on the device with two unhandled `c0000005`s the moment
# .NET did real work; see the header. Same PowerShell, same version, different
# machine code.
log "unpacking PowerShell $TOOLS_PWSH_VERSION"
mkdir -p "$STAGE/Pwsh"
unzip -q "$CACHE/$PWSH_ARCHIVE" -d "$STAGE/Pwsh" || die "could not unpack $PWSH_ARCHIVE"
[ -f "$STAGE/Pwsh/pwsh.exe" ] \
  || die "no pwsh.exe at the root of $PWSH_ARCHIVE; found: $(ls "$STAGE/Pwsh" | head -5)
     This archive has always been flat. If upstream has wrapped it in a
     versioned directory, flatten it here the way the Node block does — the
     PATH seed names \$PWSH_DIR and nothing below it."

verify_pe_arm64 "$STAGE/Pwsh/pwsh.exe" "Pwsh/pwsh.exe"
# **The CoreCLR itself, and on this payload it is the single most important
# assertion in the file.** `pwsh.exe` is a small apphost stub and would be the
# right architecture even in a build whose runtime was not — and the runtime is
# what crashed: two `c0000005` faults at 0x6f9d20dbec and 0x6f9d22ad05 inside no
# Wine module, i.e. in FEX's JIT buffer, the moment .NET did real work. An x86-64
# coreclr.dll reaching the device again is that crash reaching the device again.
verify_pe_arm64 "$STAGE/Pwsh/coreclr.dll" "Pwsh/coreclr.dll"
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

verify_pe_arm64 "$STAGE/Java/bin/java.exe" "Java/bin/java.exe"
# HotSpot itself, for the same reason coreclr.dll is checked above: java.exe is a
# launcher and the VM is the thing that will actually be generating code. This is
# the assertion that retires the JIT-on-JIT problem 1.1.0 signed up for — an
# ARM64 jvm.dll emits ARM64 and FEX is not in the loop at all. Whether a JVM
# *starts* here is still unverified; see native/pins.env.
verify_pe_arm64 "$STAGE/Java/bin/server/jvm.dll" "Java/bin/server/jvm.dll"
# `release` is the JDK's own record of what it is, and javac is what makes this a
# JDK rather than a runtime. Both absent means a repackaged or partial tree.
for f in release lib/modules bin/javac.exe; do
  [ -e "$STAGE/Java/$f" ] || die "Java payload is missing $f"
done
ok "Java: $(find "$STAGE/Java" -type f | wc -l) file(s)"

# --- Pale Moon ---------------------------------------------------------------
#
# **Plain 7z with one wrapper directory**, so this is Node's block rather than
# Git's: upstream ships no installer at all, and the whole tree sits under
# `palemoon/`. Flattened for the reason Node's is -- a version-free directory
# name is one the prefix path and PrefixRegistry.PALEMOON_DIR can both hold still.
#
# **This tree is x86-64 and that is the design, not a compromise.** Every other
# program here is ARM64 to avoid FEX; the browser is translated on purpose,
# because an x86-64 process loads ARM64EC DLLs natively and a classic ARM64 one
# cannot. Firefox 140 ESR was ARM64, could not load `dxgi.dll` at all, and never
# drew a window across four measured sessions. native/pins.env has that in full.
log "unpacking Pale Moon $TOOLS_PALEMOON_VERSION"
PALEMOON_TMP="$WORK_DIR/$COMPONENT-palemoon"
rm -rf "$PALEMOON_TMP"
mkdir -p "$PALEMOON_TMP"
7z x -bso0 -bsp0 -o"$PALEMOON_TMP" -y "$CACHE/$PALEMOON_ARCHIVE"   || die "7z could not unpack $PALEMOON_ARCHIVE"
[ -d "$PALEMOON_TMP/$PALEMOON_DIRNAME" ]   || die "expected $PALEMOON_DIRNAME/ inside $PALEMOON_ARCHIVE; found:
     $(ls "$PALEMOON_TMP")"
mv "$PALEMOON_TMP/$PALEMOON_DIRNAME" "$STAGE/PaleMoon"
rm -rf "$PALEMOON_TMP"

verify_pe_x64 "$STAGE/PaleMoon/palemoon.exe" "PaleMoon/palemoon.exe"
# Goanna itself, on the same reasoning the Java block gives for jvm.dll over
# java.exe: the launcher is small and the engine is where the work and the JIT
# are. An ARM64 xul.dll here would mean the wrong archive entirely.
verify_pe_x64 "$STAGE/PaleMoon/xul.dll" "PaleMoon/xul.dll"
# A Goanna tree missing any of these is a partial extraction rather than a
# browser. Note these are NOT Firefox's sentinels, and the first build here
# asserted Firefox's and failed: Pale Moon forked before `omni.ja` existed, so
# the packaged resources are a loose `chrome/` tree and the descriptors are the
# old pair. `application.ini` names the application, `platform.ini` the Goanna
# under it, `dependentlibs.list` is the load order the launcher reads, and
# `browser/` is the application half of the tree.
for f in application.ini platform.ini dependentlibs.list browser; do
  [ -e "$STAGE/PaleMoon/$f" ] || die "Pale Moon payload is missing $f"
done
ok "PaleMoon: $(find "$STAGE/PaleMoon" -type f | wc -l) file(s), $(du -sh "$STAGE/PaleMoon" | cut -f1)"

# --- Fonts -------------------------------------------------------------------
#
# Three faces, flat in `Fonts/`: Cascadia Mono extracted out of Microsoft's
# all-variants zip, plus the two GNU Unifont files copied in whole.
#
# **Cascadia is the console face and the Unifonts are what it falls back to**, and
# the difference between those two roles is entirely in the registry rather than
# here — the seed names Cascadia in `HKCU\Console\FaceName` and names the Unifont
# *files* in the SystemLink chain. This block's obligation is that the three files
# land under the exact names the seed spells, which is asserted below.
#
# **The family name is asserted, not assumed, and that assertion is the whole
# point of this block.** PrefixRegistry.consoleColours seeds
# `HKCU\Console\FaceName` as a *string*, and conhost matches it by name:
# `find_matching_face` in win32u looks the family up in `family_name_tree` and only
# falls back to a pitch-based default if the name is not there
# (win32u/font.c:2288-2303). A name that is one character off is not an error
# anywhere — `apply_config` reaches `CreateFontIndirectW`, which returns *some*
# font whatever happens, so a wrong name gives a nearest match with no error and no
# log line, and the only evidence is a font that is installed and unused. So the
# name comes out of the TTF's own `name` table and has to equal
# TOOLS_CASCADIA_FAMILY, which is the same string PrefixRegistry writes.
#
# Read with struct rather than fontTools: this image has no fontTools, no
# fc-query and no otfinfo (checked), and an sfnt `name` table is a table
# directory, six fixed fields and a string pool. Name ID 1 is the family; the
# Windows platform record (platform 3, encoding 1, UTF-16BE) is the one GDI reads
# and therefore the one to check.
#
# Named for the container format and not for the font in it — an sfnt is an sfnt
# whether its outlines are CFF or `glyf`, and this same function read Unifont's OTF
# before it read Cascadia's TTF.
sfnt_family_name() {
  # <path> -> the Windows-platform name ID 1 string, on stdout
  python3 -c '
import struct, sys
b = open(sys.argv[1], "rb").read()
num = struct.unpack_from(">H", b, 4)[0]
tables = {}
for i in range(num):
    p = 12 + 16 * i
    off, ln = struct.unpack_from(">II", b, p + 8)
    tables[b[p:p+4].decode("latin-1")] = (off, ln)
if "name" not in tables:
    sys.exit("no name table")
o = tables["name"][0]
fmt, count, str_off = struct.unpack_from(">HHH", b, o)
for i in range(count):
    plat, enc, lang, nid, ln, no = struct.unpack_from(">HHHHHH", b, o + 6 + 12 * i)
    if plat == 3 and nid == 1:
        sys.stdout.write(b[o+str_off+no : o+str_off+no+ln].decode("utf-16-be"))
        break
else:
    sys.exit("no Windows-platform family name (platform 3, name ID 1)")
' "$1"
}

# The sfnt version tag, as four hex bytes rather than as a string, because the one
# this payload wants contains NULs and a shell variable cannot hold them.
sfnt_tag() {
  # <path> -> the first four bytes, lower-case hex, on stdout
  python3 -c 'import sys; sys.stdout.write(open(sys.argv[1],"rb").read(4).hex())' "$1"
}

# `-j` flattens the member out of `ttf/static/`, so `Fonts/` ends up holding the
# file and not the archive's directory chain — SessionRuntime.installToolFonts
# copies the files it finds at the top of this directory and nothing below it.
log "staging fonts: Cascadia Mono $TOOLS_CASCADIA_VERSION ($CASCADIA_MEMBER)"
mkdir -p "$STAGE/Fonts"
unzip -q -j -o "$CACHE/$CASCADIA_ARCHIVE" "$CASCADIA_MEMBER" -d "$STAGE/Fonts" \
  || die "could not extract $CASCADIA_MEMBER from $CASCADIA_ARCHIVE.
     The archive holds every variant Microsoft publishes — static and variable
     builds of Cascadia Code and Cascadia Mono, plus their NF and PL cuts, in TTF,
     OTF and WOFF2 — so a path that no longer resolves means upstream reorganised
     it. List the archive and pick the Mono regular static TTF again; do not fall
     back to whatever is nearest."
[ -f "$STAGE/Fonts/$CASCADIA_FONT" ] \
  || die "unzip reported success but Fonts/$CASCADIA_FONT is not there"

CASCADIA_FAMILY="$(sfnt_family_name "$STAGE/Fonts/$CASCADIA_FONT")" \
  || die "could not read the family name out of $CASCADIA_FONT"
[ "$CASCADIA_FAMILY" = "$TOOLS_CASCADIA_FAMILY" ] || die "$CASCADIA_FONT says its
     family name is
       '$CASCADIA_FAMILY'
     but native/pins.env pins TOOLS_CASCADIA_FAMILY='$TOOLS_CASCADIA_FAMILY', which
     is what PrefixRegistry.consoleColours writes to HKCU\\Console\\FaceName.
     conhost matches that value by name and CreateFontIndirectW returns *some* font
     whatever happens, so a mismatch here ships a face nothing selects and a console
     rendering in whatever GDI thought was nearest — no error, no log line. Fix both
     together, taking the name table as authoritative: 'Cascadia Mono' is what
     platform 3 / name ID 1 read on 2026-08-17."
info "Fonts/$CASCADIA_FONT: family '$CASCADIA_FAMILY' (seeded as HKCU\\Console\\FaceName)"

# --- Unifont, the fallback tier ---
#
# Copied whole and not unpacked — these are the fonts, not archives containing
# them.
log "staging fonts: GNU Unifont $TOOLS_UNIFONT_VERSION (fallback tier)"
cp "$CACHE/$UNIFONT_ARCHIVE"       "$STAGE/Fonts/$UNIFONT_ARCHIVE"
cp "$CACHE/$UNIFONT_UPPER_ARCHIVE" "$STAGE/Fonts/$UNIFONT_UPPER_ARCHIVE"

# **The filenames are asserted against pins.env, and this is the assertion that
# matters most in this block.** `find_face_from_filename` matches on the *basename*
# of a registered font (win32u/font.c:879-893), so the SystemLink value
# PrefixRegistry writes has to spell these two names exactly. An entry that does not
# resolve is dropped with a `TRACE` line and nothing else (font.c:2076), which means
# bumping TOOLS_UNIFONT_VERSION would stage `unifont-<new>.otf`, leave the seed
# pointing at the old name, and silently lose the whole fallback tier with a green
# build and a payload that looks right. pins.env spells the names the seed uses as
# literals for exactly this comparison.
[ "$UNIFONT_ARCHIVE" = "$TOOLS_FONTLINK_UNIFONT_FILE" ] || die "this build stages
     Fonts/$UNIFONT_ARCHIVE, but native/pins.env pins
     TOOLS_FONTLINK_UNIFONT_FILE='$TOOLS_FONTLINK_UNIFONT_FILE', which is the name
     PrefixRegistry.FONT_LINK_CHAIN writes into the SystemLink value. Wine matches
     linked fonts by filename, and a name that does not resolve is dropped with a
     TRACE line and nothing else — the console would render in Cascadia alone with
     no error anywhere. Update both, and PrefixRegistry.FONT_LINK_CHAIN with them."
[ "$UNIFONT_UPPER_ARCHIVE" = "$TOOLS_FONTLINK_UNIFONT_UPPER_FILE" ] || die "this
     build stages Fonts/$UNIFONT_UPPER_ARCHIVE, but native/pins.env pins
     TOOLS_FONTLINK_UNIFONT_UPPER_FILE='$TOOLS_FONTLINK_UNIFONT_UPPER_FILE'. See
     the assertion above; the same silent failure applies to plane 1."

UNIFONT_FAMILY="$(sfnt_family_name "$STAGE/Fonts/$UNIFONT_ARCHIVE")" \
  || die "could not read the family name out of $UNIFONT_ARCHIVE"
[ "$UNIFONT_FAMILY" = "$TOOLS_UNIFONT_FAMILY" ] || die "$UNIFONT_ARCHIVE says its
     family name is
       '$UNIFONT_FAMILY'
     but native/pins.env pins TOOLS_UNIFONT_FAMILY='$TOOLS_UNIFONT_FAMILY'. The seed
     links this file by *filename* rather than by family, so a family rename is not
     itself fatal — it is asserted because an upstream change to the name table is
     exactly the kind of thing that should stop a build rather than be found on a
     device."
info "Fonts/$UNIFONT_ARCHIVE: family '$UNIFONT_FAMILY' (BMP fallback, linked)"

# **`unifont_upper` is a SECOND FAMILY, not a style of the first**, and that is
# worth asserting rather than assuming: 'Unifont Upper' is what its name table
# says. It matters because both files are in the same SystemLink chain and
# `find_face_from_filename` resolves each to a family — two files claiming one
# family would let GDI resolve a lookup to the plane-1 file, which covers exactly 1
# BMP codepoint (measured).
UNIFONT_UPPER_FAMILY="$(sfnt_family_name "$STAGE/Fonts/$UNIFONT_UPPER_ARCHIVE")" \
  || die "could not read the family name out of $UNIFONT_UPPER_ARCHIVE"
[ "$UNIFONT_UPPER_FAMILY" = "$TOOLS_UNIFONT_UPPER_FAMILY" ] || die "$UNIFONT_UPPER_ARCHIVE
     says its family name is
       '$UNIFONT_UPPER_FAMILY'
     but native/pins.env pins
     TOOLS_UNIFONT_UPPER_FAMILY='$TOOLS_UNIFONT_UPPER_FAMILY'."
[ "$UNIFONT_UPPER_FAMILY" != "$UNIFONT_FAMILY" ] || die "$UNIFONT_UPPER_ARCHIVE
     reports the same family name as the base font ('$UNIFONT_UPPER_FAMILY'). Two
     files claiming one family is one family with two faces, and GDI would then be
     free to resolve a lookup to the plane-1 file, which covers 1 BMP codepoint.
     Upstream has always shipped these as separate families; if that changed, the
     SystemLink chain needs rethinking rather than patching."
[ "$UNIFONT_UPPER_FAMILY" != "$TOOLS_CASCADIA_FAMILY" ] || die "$UNIFONT_UPPER_ARCHIVE
     reports the same family name as the console face ('$TOOLS_CASCADIA_FAMILY').
     The SystemLink value is keyed on that family name, so this would make a
     fallback font its own base font."
info "Fonts/$UNIFONT_UPPER_ARCHIVE: family '$UNIFONT_UPPER_FAMILY' (planes 1-15, linked)"

# **No verify_pe_arm64 here and nothing to verify.** A font is data, not code —
# there is no IMAGE_FILE_HEADER to read and no architecture to get wrong. What
# stands in for that check is the sfnt tag, because a truncated download or an
# HTML error page saved under a `.ttf` name would otherwise reach the device as a
# font Wine silently declines to register — and a fallback font that fails to
# register is the one failure in this whole change that produces no symptom at all
# beyond the glyphs it was added for still being missing.
#
# The two tags differ because the two sources do: `00010000` is TrueType, which is
# what taking Cascadia's `ttf/` tree rather than `otf/` means, and `OTTO` is the
# CFF-outline tag, which is what "17.0.05 is an OTF-only release" means for Unifont.
tag="$(sfnt_tag "$STAGE/Fonts/$CASCADIA_FONT")"
[ "$tag" = "00010000" ] || die "Fonts/$CASCADIA_FONT does not start with the sfnt
     tag 00010000 (got '$tag'). 4f54544f is 'OTTO', i.e. a CFF font from the otf/
     tree; 74727565 is 'true'; anything else means this is not a font at all."
for f in "$UNIFONT_ARCHIVE" "$UNIFONT_UPPER_ARCHIVE"; do
  tag="$(sfnt_tag "$STAGE/Fonts/$f")"
  # 4f54544f is 'OTTO' in hex — sfnt_tag returns hex rather than a string because
  # the TrueType tag contains NULs and a shell variable cannot hold them.
  [ "$tag" = "4f54544f" ] || die "Fonts/$f does not start with the sfnt tag OTTO
     (got '$tag'). 17.0.05 is an OTF/CFF release; 00010000 would mean a TrueType
     build arrived instead, and anything else means this is not a font at all."
done
ok "Fonts: $(find "$STAGE/Fonts" -type f | wc -l) file(s), $(du -sh "$STAGE/Fonts" | cut -f1)"

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
# One version per upstream tree, because "1.5.0" answers nothing anyone would
# ask of this package.
#
# `targetDesc` states the mixture rather than rounding it to "ARM64", because the
# Components screen is the one place a user reads this and Git's MSYS2 layer is
# genuinely x86-64 — there is no ARM64 msys-2.0. Rounding it would make the
# screen say something the payload does not.
write_tools_provenance() {
  cat > "$STAGE/provenance.json" <<EOF
{
  "component": "$COMPONENT",
  "version": "$VERSION",
  "target": "arm64",
  "targetDesc": "ARM64 Windows binaries, run natively under ARM64EC; Git's MSYS2 layer (usr/bin, msys-2.0.dll) is x86-64 and runs under FEX, no ARM64 port existing; Fonts/ is architecture-independent data",
  "sourceRepo": "upstream release archives; see native/pins.env",
  "sourceRef": "git $TOOLS_GIT_VERSION, python $TOOLS_PYTHON_VERSION, node $TOOLS_NODE_VERSION, pwsh $TOOLS_PWSH_VERSION, temurin jdk $TOOLS_JAVA_VERSION, cascadia mono $TOOLS_CASCADIA_VERSION, gnu unifont $TOOLS_UNIFONT_VERSION",
  "sourceSha": "$GIT_ARCHIVE $TOOLS_GIT_SHA256; $PYTHON_ARCHIVE $TOOLS_PYTHON_SHA256; $NODE_ARCHIVE $TOOLS_NODE_SHA256; $PWSH_ARCHIVE $TOOLS_PWSH_SHA256; $JAVA_ARCHIVE $TOOLS_JAVA_SHA256; get-pip.py $TOOLS_GET_PIP_SHA256; $CASCADIA_ARCHIVE $TOOLS_CASCADIA_SHA256 (computed, GitHub publishes no checksum list; $CASCADIA_MEMBER is the only member shipped); $UNIFONT_ARCHIVE $TOOLS_UNIFONT_SHA256 (computed, GNU publishes no checksum list); $UNIFONT_UPPER_ARCHIVE $TOOLS_UNIFONT_UPPER_SHA256 (computed)",
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
  --name "Tools $VERSION — Git $TOOLS_GIT_VERSION, Python $TOOLS_PYTHON_VERSION, Node $TOOLS_NODE_VERSION, PowerShell $TOOLS_PWSH_VERSION, JDK $TOOLS_JAVA_VERSION, Cascadia Mono $TOOLS_CASCADIA_VERSION + Unifont $TOOLS_UNIFONT_VERSION (arm64)" \
  --version "$VERSION" \
  --version-code "$VERSION_CODE" \
  --payload "$STAGE" \
  --provenance "$STAGE/provenance.json" \
  --description "Git, Python, Node.js, PowerShell 7 and the Temurin JDK as ARM64 Windows binaries, installed into C:\\Program Files\\{Git,Python,Node,PowerShell,Java}, plus three faces into C:\\Windows\\Fonts — the prefix shipped with no fonts at all, so box-drawing glyphs came out as boxes. Cascadia Mono is the console face and GNU Unifont is what it falls back to per glyph through GDI font linking, so the console is legible where Cascadia has the glyph and never tofu where it does not. 1.3.0 shipped Unifont alone and 1.4.0 Cascadia alone; neither had to choose. Git's MSYS2 shell layer is x86-64; no ARM64 port of msys-2.0 exists." \
  --out "$DIST_DIR/$COMPONENT-$VERSION-arm64.wcp"

ok "dist/$COMPONENT-$VERSION-arm64.wcp"
