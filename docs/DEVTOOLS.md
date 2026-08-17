# Devtools

Running Python, Node.js, Claude Code, Claude Desktop and Google Chrome *inside* a
Vessel container: what each one actually is, which of them the container can run
today, which of them it cannot, and what it would cost to find out for the ones
in between.

The rule from `docs/OPTIMIZATION.md` and `docs/BANDWIDTH.md` carries over: **a
claim without a measurement is marked as unmeasured.** Nothing in this document
has been run on the device. Every number below is either read off a file in this
repository, read off an upstream server with an HTTP `HEAD`, or read out of a PE
header on this machine — and each is labelled with which. Everything else is
marked *unverified*.

The premise the whole document turns on is a fact about the component store that
the leading proposal — "package them as `.wcp` like Git" — runs straight into:
**a container references exactly one component per type**
(`app/src/main/java/app/vessel/data/ComponentStore.kt:147`, `referencesOf(...)[type.wire]`),
`Tools` is one type, and Git already occupies it at version code `25500`. That is
not a packaging detail. It is the reason §7 exists.

---

## Verdict

| Program | Recommended approach | Confidence | Biggest risk |
|---|---|---|---|
| **Python 3.13/3.14** | Native ARM64 PE. Ship the **embeddable ARM64 zip**, extracted on the build host, never the installer. | **High** — runs, unverified only in the trivial sense | `pip install` of anything with a C extension: PyPI has few `win_arm64` wheels, and there is no compiler in the prefix |
| **Node.js 22/24** | Native ARM64 PE. Ship the **`win-arm64` zip**, extracted on the build host. | **High** | `node-gyp` / native addons: no MSVC, no prebuilt `win32-arm64` binaries for most packages |
| **Claude Code** | Native ARM64 PE — **it is not a Node program**. Drop `claude.exe` in beside Node/Python. | **Medium-high** that it *starts*; **low** that it is *useful* | **It has no working shell.** Git Bash is measured to hang the phone; Wine's `powershell.exe` is a stub. Fixable — see §4 — but it is the blocker, not Chromium |
| **Google Chrome** | ARM64 MSI, extracted on the build host, launched with `--no-sandbox`. **Try it, expect it to fail.** | **Low** | Chromium's sandbox is architecturally incompatible with Wine, and `--no-sandbox` is the *entry* ticket, not the finish line |
| **Claude Desktop** | Do not attempt until Chrome renders a page. Then the **consumer arm64 `setup` installer**, never the MSIX. | **Very low** | Same Chromium risk plus Squirrel/Electron install machinery. The enterprise MSIX path is **dead on arrival** — Wine implements no part of the Windows app model (`TerminalProfile.kt:6-14`) |

**One correction to the brief that framed this work.** Two of its premises are
wrong against the repo:

- **`Z:` is not `/`.** Wine's default `Z:` → `/` is *deliberately deleted* every
  provision (`app/src/main/java/app/vessel/core/DriveMap.kt:253-256`,
  `removeRootDrive()`), and the shell-namespace node for it is stripped from the
  registry seed too (`PrefixRegistry.kt:727-739`), because `/` from inside the
  guest is this app's own private storage. Guest ↔ Android file exchange goes
  through `D:` (`/storage/emulated/0`, sideload flavour only) or a SAF-picked
  folder on `E:`–`Y:`.
- **There is no ~276 MB proton component.** The Proton artefacts in `dist/` are
  `wine-proton-11.0-canoe.wcp` at 69,497,216 B and `wine-proton-exp-11.0-canoe.wcp`
  at 82,738,720 B (measured, `ls -l`). No 276 MB figure exists anywhere in the
  repo. The number that *does* dominate the disk budget is **Wine at 912 MB
  unpacked** (`ContainerPaths.kt:28`, `ComponentStore.kt:56`, `WcpInstaller.kt:116`).

---

## 1. What the container actually is

Everything below depends on five facts about the runtime, all of them read out
of the repo rather than assumed.

**An arbitrary `.exe` already launches.** This is not a games-only app. The
file browser classifies any file with a valid PE header as launchable
(`app/src/main/java/app/vessel/ui/shell/Launchable.kt:70-99`), "Add as app"
takes an arbitrary guest path, and `SessionShellHost.commandFor`
(`app/src/main/java/app/vessel/data/SessionShellHost.kt:232-244`) builds the
command line:

```kotlin
"exe", "lnk" -> GuestCommand(path, extra)
"bat", "cmd" -> GuestCommand("cmd.exe", listOf("/c", path) + extra)
"msi"        -> GuestCommand("msiexec.exe", listOf("/i", path) + extra)
"vbs", "js"  -> GuestCommand("wscript.exe", listOf(path) + extra)
```

which becomes `wine explorer /desktop=vessel,WxH <program> [args…]`
(`WineLaunch.kt:419-427`). **`AppShortcut.args` is passed through verbatim**
(`AppShortcut.kt:33-34`) — so `--no-sandbox` is typeable in the existing UI with
no code change. That single field is why the Chrome experiment in §9 costs
nothing to run.

**A real Windows console exists, and Vessel did not write it.** `wineconsole`
starts a program with a Win32 console attached and `conhost.exe` draws it
(`TerminalProfile.kt:16-22`) — "a genuine Windows console — `ReadConsoleInput`,
code pages, a selection buffer". `TerminalProfile.COMMAND_PROMPT` (`cmd.exe`) is
in the launcher today. This is the thing a CLI needs and it is already there.

**Three CPU paths, and which one a binary takes is decided by its PE header.**
ARM64 PE runs with no translation; x86-64 runs through `libarm64ecfex.dll` under
ARM64EC; x86-32 runs through `libwow64fex.dll` under WoW64. The hooks are two
registry values, not patches (`PrefixRegistry.kt:294-297`, `:315-318`), and
`Launchable.translationFor()` (`Launchable.kt:131-136`) is the app's own table of
the same thing. README's verification block shows all three producing correct
arithmetic on the device.

**Networking exists and has been fought for, but has never been measured
end-to-end.** Six Wine patches in this tree are about nothing else:

| patch | what Android denied | what it does now |
|---|---|---|
| `patches/wine/0007` | `bind()` on `NETLINK_ROUTE` (`EACCES`) | enumerate interfaces with `getifaddrs` |
| `patches/wine/0013` | everything under `/proc/net` (`EACCES` on `route`, `ipv6_route`, `dev`, `if_inet6`) | tolerate the unreadable table instead of failing `GetAdaptersAddresses` for every caller |
| `patches/wine/0014` | bionic has no `res_getservers`, so `dnsapi.so` exported no `__wine_unix_call_funcs` | do not call through a unix library that failed to load (this was returning `c0000005` from a function documented to return a Win32 status) |
| `patches/wine/0015` | no `/etc/resolv.conf` → `DNS_ERROR_NO_DNS_SERVERS` | an adapter with no DNS servers is not a failure |
| `patches/wine/0035` | no libresolv | a unix backend on `android_res_nquery`/`android_res_nresult` out of `libandroid.so` |
| `patches/wine/0039` | `bind()` on `NETLINK_ROUTE`, again | "returns `EACCES` on every device, in every session, and will keep doing so" — treat it as not-an-error |

So the brief's worry is real and was **already hit and already patched**:
`netlink_route_socket` and `/proc/net/route` are exactly the two denials listed
in patches 0039 and 0013. The consequence for this document is narrower than
"networking is broken":

- `os.networkInterfaces()` (Node) and `socket.if_nameindex()` (Python) go through
  `GetAdaptersAddresses` → `iphlpapi` → `nsiproxy`. Patches 0007/0013/0015 exist
  precisely so that call returns *something* rather than failing. **Unverified
  whether the something is correct** — no session log in this repo shows
  `ipconfig` output.
- DNS goes through `dnsapi` → Android's resolver (patch 0035). **Unverified
  end-to-end**; there is no recorded session where a guest program resolved a
  hostname.
- The one substantive statement anyone has written down is a passing aside in
  `TerminalProfile.kt:34-35`: *"Networking is not the problem and was checked:
  `INTERNET` is granted and Wine uses the host's sockets."* That is not a
  measurement in the sense the rest of this repo means the word.

**The prefix is on internal app storage** (`filesDir/containers/<id>/prefix/`,
`ContainerPaths.kt:14-21`) and components are unpacked once into a shared store
(`filesDir/components/<Type>/<versionCode>/`, `:27-33`). Tools, uniquely, are
then **copied whole into each container's prefix** — see §7.

---

## 2. Python

**Architecture: native ARM64 PE. No translation. High confidence.**

python.org ships an ARM64 installer and an ARM64 embeddable zip for both current
lines. Measured with an HTTP `HEAD` on 2026-08-17:

| asset | bytes | MiB |
|---|---|---|
| `python-3.13.15-embed-arm64.zip` | 10,403,009 | 9.9 |
| `python-3.13.15-arm64.exe` (installer) | 28,799,152 | 27.5 |

`python-3.14.7-arm64.exe` and `python-3.14.7-embed-arm64.zip` exist too. The
"experimental" warning that older write-ups repeat is **no longer on
python.org's own downloads page** — that page lists ARM64 beside 64-bit and
32-bit with no qualifier. (Third-party guides still say experimental; the
primary source does not. Treat the primary source as authoritative and the
package ecosystem, not the interpreter, as the risk.)

**Installer or portable? Portable, and it is not close.**

The `-arm64.exe` installer is a bundled Burn/WiX chainer that wants elevation,
writes to the registry, and installs the Windows launcher `py.exe` with a
`PATHEXT`/file-association story none of which survives a prefix that gets
re-provisioned. The **embeddable zip is literally the answer to this problem**:
it is a flat directory containing `python.exe`, `python313.dll`, `python313.zip`
(the stdlib) and the `.pyd` extension modules, designed by CPython to be
unzipped and shipped inside another application. Extract it on the build host,
put the directory in the payload, done. Nothing runs `msiexec`, nothing needs
admin, nothing touches the registry.

The one thing the embeddable zip omits is `pip` and `venv` wiring — it ships a
`python313._pth` that disables `site`. If `pip` is wanted, the build host runs
`get-pip.py` against the extracted tree and un-comments `import site` in the
`._pth`, then packages the result. That is a build-host step, not a device step.

**The real risk is not Python. It is the wheels.** `pip install` on
`win_arm64` finds far fewer prebuilt wheels than on `win_amd64`; when it finds
none it falls back to building from an sdist, which needs a C compiler that this
prefix does not have and will not get. Pure-Python packages are fine. `numpy`,
`cryptography`, `pydantic-core` and anything else with a compiled core are a
per-package lottery. **Unverified** — no wheel-availability survey was done for
this document.

The alternative — ship the x86-64 Python so `win_amd64` wheels resolve — trades
"some packages don't install" for "everything runs under FEX". Given
`libarm64ecfex.dll` is well-exercised here (README's verification block; the
FEX revision history in `native/pins.env:27-208`), that is a defensible fallback
if the wheel problem turns out to bite. **Do not ship both**: the component store
has one Tools slot (§7) and two Pythons on `PATH` is a support nightmare.

---

## 3. Node.js

**Architecture: native ARM64 PE. No translation. High confidence.**

Windows on ARM64 was promoted to a **Tier 2** Node platform in the v19 cycle
(nodejs/node#47233), and official `win-arm64` archives have shipped ever since.
Measured with an HTTP `HEAD` on 2026-08-17:

| asset | bytes | MiB |
|---|---|---|
| `node-v24.19.0-win-arm64.zip` | 33,463,079 | 31.9 |
| `node-v22.22.0-win-arm64.zip` | 31,300,435 | 29.9 |

`node-v24.19.0-arm64.msi` also exists. **Use the zip.** It is a flat
`node.exe` + `npm`/`npx` shims + `node_modules/npm`; there is no install step at
all, only a `PATH` entry. The MSI buys nothing here and costs the whole of
TODO #17's unknown.

**Node-specific risks, in order:**

1. **libuv is IOCP-based on Windows.** Every socket, file watch and timer goes
   through `CreateIoCompletionPort` / `GetQueuedCompletionStatusEx` and
   `NtDeviceIoControlFile` on `\Device\Afd`. Wine implements this; how completely
   under load on this stack is **unverified**. This is the single most likely
   place `node.exe` misbehaves in a way that is not obvious.
2. **`os.networkInterfaces()`** lands on `GetAdaptersAddresses`, i.e. exactly the
   `nsiproxy` path patches 0007/0013/0015 rescued. Expect it to return
   *something*; expect that something to possibly be a single loopback-ish
   adapter with no gateway. Node itself does not need it, but tools that print a
   LAN URL do.
3. **Native addons.** `node-gyp` needs MSVC. There is none. Packages with
   prebuilt `win32-arm64` binaries (`esbuild`, `@swc/core`, `sharp`) are fine;
   everything else that compiles is not.
4. **`npm` is thousands of small file operations.** On a Wine prefix on Android
   internal storage, `npm install` is going to be slow in a way nobody has
   measured. **Unverified.**

---

## 4. Claude Code

**This is the most important finding in the document, and it inverts the
brief's framing.**

**Claude Code is not a Node program.** From Anthropic's own setup documentation
(`code.claude.com/docs/en/setup`, "Install with npm"):

> The npm package installs the same native binary as the standalone installer.
> npm pulls the binary in through a per-platform optional dependency such as
> `@anthropic-ai/claude-code-darwin-arm64`, and a postinstall step links it into
> place. **The installed `claude` binary does not itself invoke Node.**

and the supported platform list includes **`win32-arm64`**. System requirements
are "x64 or ARM64 processor", Windows 10 1809+, 4 GB+ RAM, and a shell of
"Bash, Zsh, PowerShell, or CMD".

So: **Claude Code is a single native ARM64 Windows PE.** It does not need Node
installed. It does not need npm. `ripgrep` is bundled. The correct way to get it
into a prefix is to fetch the `win32-arm64` binary on the build host — from the
release bucket, whose `manifest.json` is GPG-signed with fingerprint
`31DD DE24 DDFA B679 F42D 7BD2 BAA9 29FF 1A7E CACE` and lists a SHA256 per
platform — verify it, and drop `claude.exe` into the payload. No installer, no
`irm | iex`, no auto-updater (set `DISABLE_UPDATES`, because an updater writing
into a prefix that gets re-provisioned is a support problem, and because the
component store's `payloadSha256` becomes a lie the moment the binary rewrites
itself).

Git is already the component that is *supposed* to be there, and `git.exe`
itself is native ARM64 — see §7's measurement. So three of Claude Code's four
requirements are met.

**The fourth is not, and it is the blocker.** Claude Code executes commands
through one of two tools:

- **The Bash tool**, which on native Windows means Git Bash
  (`CLAUDE_CODE_GIT_BASH_PATH`, defaulting to Git for Windows' `bash.exe`).
- **The PowerShell tool**, used when Git for Windows is absent.

Both are broken here, and both were already investigated for a different reason.
From `app/src/main/java/app/vessel/ui/shell/TerminalProfile.kt:56-70`, measured
on the device:

> *`usr\bin\bash.exe --login -i` under `wineconsole` started and then hung the
> phone.* `ps` during the launch:
> ```
> 317  bash.exe --login -i     1.4%  S   <- parent, waiting
> 336  bash.exe --login -i    98.6%  R   <- child, spinning
> ```
> `wchan` 0, state `R`: a userspace busy loop. That is MSYS2's `fork()`
> emulation deadlocking under Wine — the child never completes the handshake
> that hands it the parent's cloned address space. […] `/etc/profile` forks on
> its way in, so a login shell never reaches a prompt, and reaching one would
> not help: **bash forks for every external command.**

And PowerShell: `Launchable.kt:93-96` refuses `.ps1` outright because "Wine ships
only a stub `powershell.exe`", and `SessionShellHost.kt:228-230` says returning
null "keeps the failure a refusal rather than a launch of Wine's stub PowerShell,
**which would appear to work**".

**Therefore, as the container stands today, Claude Code would start, render its
TUI in `conhost`, read and write files, run `git` — and be unable to execute a
single shell command.** That is not a small degradation; command execution is
most of what the tool is.

**The unlock is PowerShell 7, and it is unusually cheap.** `TerminalProfile.kt:38-45`
already anticipated this — *"PowerShell 7 cannot be built from source and belongs
behind the component downloader"*. Microsoft publishes a portable
`PowerShell-7.6.5-win-arm64.zip`, measured at 99,526,416 B (94.9 MiB) on
2026-08-17. It is a self-contained .NET deployment: it carries its own CoreCLR as
native ARM64 PE and does **not** need wine-mono (which this build deliberately
does not ship — `build/wine.sh:635-697`). Unzip, put `pwsh.exe` on `PATH`, and
Claude Code's PowerShell tool has something real behind it.

Whether a self-contained .NET 8/9 runtime actually starts under Wine ARM64EC is
**unverified and is the single highest-value unknown in this document.** It is
also a five-minute experiment (§9, Phase 2).

Two smaller Claude Code notes:

- **Login opens a browser.** There is no browser in the container (Internet
  Explorer was removed for cause — `TerminalProfile.kt:27-36`). Use
  `ANTHROPIC_API_KEY`, which the docs say prompts once for approval instead of
  opening a browser. The container's env is settable through the Diagnostics
  screen's free-text env table (`ContainerDiagnostics.kt:57-79`, "the escape
  hatch"), filtered only against `RESERVED_SESSION_ENV`
  (`ContainerDiagnostics.kt:246-251`) — `ANTHROPIC_API_KEY` is not in that set,
  so it is settable today with no code change.
- **Network access is mandatory** and is the unmeasured thing from §1. If DNS or
  TLS does not work from inside the prefix, Claude Code fails at the first
  request and nothing else about this section matters.

---

## 5. Google Chrome

**Architecture: a native ARM64 Windows build exists** (Google shipped it in 2024
for Windows-on-Arm). So Chrome is *not* an emulation problem. It is a Wine
problem.

Sizes, measured with HTTP `HEAD` on 2026-08-17:

| asset | bytes | MiB |
|---|---|---|
| `googlechromestandaloneenterprise_arm64.msi` | 169,316,352 | 161.5 |
| `googlechromestandaloneenterprise64.msi` (x64) | 164,831,232 | 157.2 |
| `GoogleChromeEnterpriseBundle64.zip` | 229,503,162 | 218.9 |

**Installer or portable? Portable, and TODO #17 is why.** From `docs/TODO.md`:

> - [ ] **#17 — `.msi` support.** From a launch-type matrix measured before
>   2026-08-14: `msiexec` loads `msi.dll`, `cabinet.dll`, `wintrust.dll` and
>   `comctl32.dll` and gets as far as drawing a window, and the payload is not in
>   `C:\Program Files` afterwards. So it reaches its UI and does not install.
>   Whether the fault is the minimal test package or `msi.dll` has never been
>   tested.

`.msi` therefore has a **measured, open, unexplained failure** in this container.
Betting Chrome on it would confound two unknowns. Extract instead: the Chrome
MSI contains a `chrome.7z` which unpacks to a self-contained
`Chrome-bin\<version>\` tree that runs from anywhere. That is a build-host step
with `msiexec /a` or `7z` and no device involvement.

**The Chromium sandbox does not work under Wine, and this is old and settled.**
WineHQ bug 21232, titled verbatim in the wine-bugs archive:

> *Chromium-based browser engines (Chrome, Opera) crash on startup unless
> `--no-sandbox` is used (native API sandboxing/hooking scheme incompatible with
> Wine)*

The mechanism: Chromium's sandbox intercepts NT syscalls by copying the first
few instructions of a `ntdll` export and patching in a jump. Wine's `ntdll`
exports are ordinary compiled functions with different prologues, so the copier
"stops short in the middle of an opcode sequence" and the process dies. Related:
bug 37585, *64-bit Chromium browser engine […] fails if 64-bit `ntdll.dll.so` is
not mapped at desired fixed address*. Both were resolved on the Wine side as
out-of-scope — there is a native Linux Chrome, so nobody upstream is motivated.

`--no-sandbox` is the workaround and it is the *entry* ticket. What follows it:

1. **Multi-process everything.** Browser, GPU, renderer(s), network service,
   utility. Chromium spawns these with `CreateProcess` plus
   `PROC_THREAD_ATTRIBUTE_MITIGATION_POLICY`, job objects, and AppContainer
   tokens — the parts of the Windows security model Wine implements least. Each
   one is a separate `wineserver` client on a phone.
2. **GPU: ANGLE, and ANGLE's default Windows backend is D3D11.** That path lands
   on DXVK → Turnip, which is the one graphics path this repo has actually run a
   game through (README:132, Metro 2033 Redux). So there is a plausible
   accelerated route. The fallbacks are `--use-angle=vulkan` (straight to
   Turnip), `--use-angle=swiftshader` (software rasteriser, ARM64 JIT), or
   `--disable-gpu` (Skia CPU raster). **All unverified.**
3. **The X server.** Vessel's is a vendored in-app reimplementation of the X11
   core protocol (`app/src/main/java/com/winlator/README.md`), reached over an
   abstract-namespace socket, with BigReq, MIT-SHM, DRI3, Present, SYNC,
   Composite and XFixes. Chrome under Wine talks to `winex11.drv`, not to X
   directly, so the surface area is Wine's — but **there is no window manager**
   (`SessionEnvironment.kt:283-334`, `MANAGED_DESKTOP`: "a compositor with no
   window manager in it", no EWMH at all). Chromium's Windows path does not need
   EWMH, but its own window chrome inside `explorer /desktop=vessel` is
   unexplored territory.
4. **Memory.** A Chrome with three tabs is 800 MB–1.2 GB RSS on desktop
   (unverified for this build), on a phone that is also holding a 912 MB Wine
   tree's worth of mapped PE images.

**Honest position: Chrome is worth one afternoon and no more.** The
`--no-sandbox --disable-gpu --single-process about:blank` experiment in §9 either
paints a window or it does not, and the answer arrives in minutes. If it does
not, do not iterate — the failure is upstream, structural, and 16 years old.

---

## 6. Claude Desktop

**Architecture: Electron, and an ARM64 Windows build does exist.** Anthropic's
download page offers, per architecture:

- consumer: `https://claude.ai/api/desktop/win32/{x64,arm64}/setup/latest/redirect`
- enterprise: `https://claude.ai/api/desktop/win32/{x64,arm64}/msix/latest/redirect`

**The MSIX path is dead on arrival and this repo already wrote down why.**
`TerminalProfile.kt:6-14`, about Windows Terminal:

> `wt.exe` is distributed as an MSIX package and **Wine implements no part of the
> Windows app model — no `AppxManifest` handling, no package activation.**

`Add-AppxPackage` / `Add-AppxProvisionedPackage`, which Anthropic's Windows
deployment guide names as the install commands, do not exist under Wine. The
enterprise guide additionally requires `VirtualMachinePlatform` (a Hyper-V
optional feature) for Cowork, which on Android is not a thing that can be
enabled. **Do not go down this road.**

That leaves the consumer `setup` installer, which for an Electron app of this
shape is a Squirrel-for-Windows or NSIS package: it extracts to
`%LOCALAPPDATA%\Claude\app-<ver>\`, writes a stub `Claude.exe` at the root, and
creates shortcuts. Squirrel installers are per-user and need no elevation, which
is the one good property here. Whether Squirrel's own machinery (it shells out,
touches the registry `Run` key, and creates `.lnk` files) survives Wine is
**unverified**. Extracting the installer on the build host with `7z` and
packaging the `app-<ver>` tree directly is strictly safer and is the
recommendation.

**Everything in §5 about Chromium applies unchanged**, because Electron *is*
Chromium. Add:

- Electron under Wine is reported to work with `--no-sandbox` in the
  general case (the folk knowledge is consistent, the evidence is forum posts
  and gists, not bug reports — **low-quality sources, treat as unverified**).
- Claude Desktop specifically wants a signed-in browser session for OAuth and,
  for MCP, spawns child processes. Both are the failure modes §4 and §5 already
  name.
- **Cowork will not work at all** regardless of the above; it needs a VM.

**Verdict: do not plan work for Claude Desktop.** Make it conditional on
Chrome painting a page. If Chrome cannot, Claude Desktop cannot, and the two
share one experiment.

---

## 7. Packaging: why "like Git" does not work as-is

This is the section the user's steer runs into, and it is a hard structural
blocker rather than an inconvenience.

### 7.1 What the Git component actually is — measured

`dist/git-2.55.0.3-arm64.wcp` is **84,796,248 B (80.9 MiB)** compressed and its
payload tar is **391,065,600 B (373 MiB)**, 7,895 files. It is not a Vessel
build product: there is no `build/git.sh`, no `GIT_REF` in `native/pins.env`, no
`.github/workflows/git.yml`. Its embedded provenance says so —
`"builtBy": "git-for-windows (repackaged verbatim, not rebuilt)"`,
`"sourceSha": "PortableGit-2.55.0.3-arm64.7z.exe"`. It is an upstream release
asset, unpacked and re-tarred.

**`arm64` in the filename is a free-text label, not a build target.**
`build/targets/` contains exactly one file, `canoe.env`, which is the chip-tuning
profile for the actual device (`TARGET_MCPU=oryon-1`, `TARGET_GPU=a829`), and
every compiled component gets `-canoe`. Git got `-arm64` because nothing was
compiled — `"cpuFlags": "none"`. The app never branches on the string; it is a
display field (`ComponentPackage.kt:65-71`).

**But the payload is not one architecture. It is two.** I read
`IMAGE_FILE_HEADER.Machine` out of six PEs extracted from
`out/verify/git-2.55.0.3-arm64.tar` — measured on this machine, 2026-08-17:

| file | `Machine` | architecture |
|---|---|---|
| `cmd/git.exe` | `0xAA64` | ARM64 |
| `bin/git.exe` | `0xAA64` | ARM64 |
| `clangarm64/bin/git.exe` | `0xAA64` | ARM64 |
| `usr/bin/bash.exe` | `0x8664` | **x86-64** |
| `usr/bin/ls.exe` | `0x8664` | **x86-64** |
| `usr/bin/msys-2.0.dll` | `0x8664` | **x86-64** |

This resolves an apparent contradiction in the repo. `README.md:131` calls it
"Native ARM64 Git"; `TerminalProfile.kt:79-85` describes the working pipeline as
"a real pipeline between two **x86-64 MSYS2 processes under FEX**". **Both are
correct.** Git-for-Windows' ARM64 build is native ARM64 for `git` and its
helpers under `clangarm64/`, and still x86-64 for the entire MSYS2 POSIX runtime
under `usr/bin/`, because MSYS2's `msys-2.0` runtime has no ARM64 port. That is
also the root cause of the Git Bash failure in §4: the thing that deadlocks is
`msys-2.0.dll`'s `fork()` emulation, running as x86-64 under FEX, under Wine, on
ARM64.

**The lesson for Python and Node is direct and good:** their Windows ARM64
distributions have no MSYS2 layer at all. They are homogeneously native ARM64.
They are a *better* fit for this container than Git is.

### 7.2 The blocker: one `Tools` slot per container

```kotlin
// ComponentStore.kt:142-150
suspend fun directoryFor(containerId: String, type: ComponentType): File? = …
    val versionCode = referencesOf(paths.of(containerId))[type.wire]
        ?: return@withContext null
```

`referencesOf` returns a `type -> versionCode` map read from the container's
`provisioned.json` (`ComponentStore.kt:62-66`). One version code per type. There
is no list. **A container can reference exactly one `Tools` component.**

And `SessionRuntime.installTools()` hardcodes where it goes:

```kotlin
// SessionRuntime.kt:2908
private const val GIT_PREFIX_DIR = "drive_c/Program Files/Git"
// SessionRuntime.kt:2911
private const val GIT_SENTINEL = "cmd/git.exe"
```

with `source.copyRecursively(staging, overwrite = true)` then `staging.renameTo(target)`
(`:2277-2283`) — a **whole-tree copy into every container's prefix**, deliberately
not a symlink, because "a link would let a guest program write through into the
shared store that every other container on this version reads" (`:2254-2256`).

`PATH` is then supplied by the registry seed, **written whole**:

```kotlin
// PrefixRegistry.kt:778-805
"""C:\windows\system32""", """C:\windows""", """C:\windows\system32\wbem""",
"""$GIT_DIR\cmd""", """$GIT_DIR\usr\bin""", """$GIT_DIR\clangarm64\bin""",
… RegistryValue("MSYSTEM", "CLANGARM64")
```

*"Written whole rather than appended, because a `.reg` merge replaces a value and
there is no append form: this seed is the definition of the machine `PATH`."*

### 7.3 The version-code collision, stated explicitly

The brief is right that this bites silently, and it would bite here immediately.
`build/package_wcp.py:73-90` derives version codes from dotted versions:

- Git 2.55.0.3 → **25500** (this is what the shipped package carries)
- Python 3.13.15 → **31315**
- Node 24.19.0 → **241900**

`adoptLatest()` (`ComponentStore.kt:221-262`) moves a container's reference
forward when `newest > current`. **31315 > 25500 and 241900 > 25500.** So
installing a naively-packaged Python-as-`Tools` package would:

1. take over the container's single `Tools` reference,
2. cause `installTools()` to copy Python's tree into `C:\Program Files\Git`,
3. leave `PATH` pointing at `…\Git\cmd`, `…\Git\usr\bin`, `…\Git\clangarm64\bin`,
   none of which exist in a Python tree,
4. and **silently lose Git**, with `git` disappearing from `cmd.exe` and no error
   anywhere.

The complementary trap is the one `build/common.sh:403-418` documents at length:
a package whose code already exists is not unpacked at all —
`WcpInstaller.kt:290-305`, *"Already there. Same type and same versionCode is the
same build, so there is nothing to do but say so"* — the build says ok, `adb
install` succeeds, and nothing on the device changes.

**So any plan here must state version codes explicitly and must not let two
payloads share the `Tools` type.**

### 7.4 The three real options

| option | change required | verdict |
|---|---|---|
| **A. One combined `Tools` package** — `Git 2.55.0.3 + Python 3.13 + Node 24` in one payload under `C:\Vessel\{git,python,node}` | `installTools()` must stop hardcoding `GIT_DIR`/`GIT_SENTINEL`; `PrefixRegistry.toolsPath` must list the new dirs and `SEED_VERSION` must bump (currently 23) | **Recommended.** Smallest diff that is correct. One version code, no collision surface, one `adoptLatest` winner by construction. Cost: the whole bundle re-downloads when any one part moves |
| **B. New `ComponentType`s** — `PYTHON`, `NODEJS` | add to `ComponentType` (`ComponentPackage.kt:16-42`) *and* to `KNOWN_TYPES` (`package_wcp.py:30-40`), plus a copy step and `PATH` entries each | Cleaner long-term, larger diff, and it forks the `.wcp` format from the Winlator ecosystem the way the `OpenGL` type already did (with a written justification — `package_wcp.py:33-38`). Do this only if independent versioning turns out to matter |
| **C. No packaging at all** — extract the zips on the build host, copy the trees onto the phone, drop them in `drive_c` by hand | **none** | **Do this first.** It answers every architecture and runtime question in §2–§4 with zero shipping-code risk, and it is what §9 Phase 1 is |

Whichever of A or B is chosen, pass `--version-code` **explicitly** to
`package_wcp.py` rather than letting it derive one. Suggested, using the
`vessel_version_code(version, revision) = version_code(version) * 100 + revision`
convention from `build/common.sh:419-423` so there is room for Vessel patch
revisions underneath upstream:

| package | version | derived | with revision 0 | note |
|---|---|---|---|---|
| combined `Tools` (option A) | `1.0.0` | 10000 | **1000000** | > Git's 25500, so it wins `adoptLatest` cleanly; bump the middle digit per contents change |
| `Python` (option B) | `3.13.15` | 31315 | 3131500 | |
| `Node` (option B) | `24.19.0` | 241900 | 24190000 | |

And note the trap in the derivation itself: `version_code` uses
`code*100 + min(part,99)` for parts under 100 and `code*100000 + part`
otherwise, so a version part crossing 100 changes the scale. Node 24 → 2419 →
241900 is fine; a hypothetical Node 100 would not be. Pin the codes; do not
derive them.

---

## 8. Disk and memory budget

All Vessel numbers measured (`ls -l dist/`, `ls -l out/verify/*.tar`,
`docs/ARCHITECTURE.md:590-598`). All upstream numbers measured by HTTP `HEAD`
on 2026-08-17. Unpacked sizes for upstream payloads are **estimates** and are
marked.

**What is already there, per device:**

| | download | unpacked |
|---|---|---|
| Wine (shared store) | 84.2 MB | **912 MB** |
| DXVK + vkd3d + Turnip + FEX + Zink | ~15.9 MB | ~76 MB |
| prefix, per container | — | grows from Wine's staging |

**What each addition costs.** Note the asymmetry: components are unpacked once
into the shared store, **but `Tools` is then copied whole into every container's
prefix** (`SessionRuntime.kt:2277-2283`). Tools payloads therefore cost roughly
double for one container and *N+1* times for *N*.

| payload | `.wcp` / archive | unpacked | in store + 1 prefix | measured? |
|---|---|---|---|---|
| Git 2.55.0.3 (existing) | 80.9 MiB | **373 MiB** | ~746 MiB | measured |
| Python 3.13 embeddable ARM64 | 9.9 MiB | ~30 MiB | ~60 MiB | archive measured, unpacked estimated |
| Python 3.13 + pip + a few pure wheels | — | ~90 MiB | ~180 MiB | estimated |
| Node 24 `win-arm64` | 31.9 MiB | ~110 MiB | ~220 MiB | archive measured, unpacked estimated |
| Claude Code `win32-arm64` | ~60 MiB | ~60 MiB | ~120 MiB | **unverified** — size not probed |
| PowerShell 7.6.5 `win-arm64` | 94.9 MiB | ~270 MiB | ~540 MiB | archive measured, unpacked estimated |
| Chrome ARM64 (`Chrome-bin`) | 161.5 MiB MSI | ~450 MiB | ~900 MiB | MSI measured, unpacked estimated |
| Claude Desktop (Electron arm64) | ~120 MiB | ~450 MiB | ~900 MiB | **unverified** |

**Read this table twice.** Git alone already costs three quarters of a gigabyte
for one container, which is why it is *"built but not published"*
(`README.md:131`, `gen_registry.py:28-31`) rather than shipped. A
Git+Python+Node+Claude Code bundle is **~1.1 GiB unpacked, ~2.2 GiB with one
container's copy**, on top of Wine's 912 MB — before a single npm package or a
single game.

**Two things would change this materially, and neither is in scope here:**

1. **Symlink the Tools tree instead of copying it.** The stated reason for
   copying is write-through into the shared store. A read-only bind or a
   copy-on-write scheme would halve the cost. Out of scope; worth an issue.
2. **Drop Git's `usr/` subtree.** 7,895 files, most of them the x86-64 MSYS2
   userland whose shell is measured to hang the phone. `git.exe` itself and
   `clangarm64/` are what work. **Unverified how much of `usr/` `git.exe`
   actually shells out to** — Git for Windows uses `sh.exe` for several
   porcelain commands (`git rebase -i`, `git bisect`, credential helpers), so
   this is not a free deletion. But if `git` is being used by Claude Code for
   `add`/`commit`/`diff`/`log`, a trimmed tree could plausibly be 60 MiB instead
   of 373.

**Memory** is the softer constraint and the less certain one. Claude Code plus
`pwsh` plus a Node process is maybe 400–600 MB (**unverified**). Chrome or
Claude Desktop is 800 MB–1.2 GB on top of Wine's mapped images (**unverified**).
On a phone that is also the browser-class risk, not just a comfort issue.

---

## 9. Is Wine even the right answer? — the honest comparison

The brief asks whether a native ARM64 *Linux* Python/Node beside the Wine prefix
would be dramatically simpler. It is the right question and the repo already
answered it, at length, in `docs/LINUX-MODE.md`.

**The constraint is SELinux, and it is absolute.** From `docs/LINUX-MODE.md:137-149`,
quoting the device's own policy:

```
30591:(allow untrusted_app_all app_data_file (file (ioctl read getattr lock map execute open watch watch_reads)))
26484:(allow runas_app       app_data_file (file (execute_no_trans)))
```

> `untrusted_app_all` has **`execute`** — the permission a `PROT_EXEC` file
> mapping and `dlopen` need […] — and has **neither `execute_no_trans`** (what
> `execve` needs) **nor `execmod`**.

So the app cannot `execve` anything in its own data directory. The one escape
hatch this project uses is
`execve("/system/bin/linker64", [linker, binary, …])` — which is why every Wine
launch in `WineLaunch.kt:156-177` starts with `/system/bin/linker64`. That hands
the program to **bionic's** dynamic linker.

The consequences, in order of how much they matter here:

1. **A glibc userland (Ubuntu, Debian) cannot run.** `docs/LINUX-MODE.md:39-48`
   is unambiguous, and PRoot does not help because "the first thing it would try
   to do is `execve` the guest's `ld-linux-aarch64.so.1` out of `filesDir`". The
   document's own verdict, after two rounds of device probes:
   **"bionic userland yes, Ubuntu no."**
2. **Therefore Termux-as-such is out**, and so is an Alpine/musl rootfs: musl's
   loader is not bionic either.
3. **A bionic-linked, NDK-built CPython and Node *would* run**, through the same
   `linker64` trampoline Wine uses. Termux proves both build for bionic (Termux
   is a bionic userland). This is genuinely simpler and faster than Wine for the
   interpreter itself — no PE loading, no ARM64EC, no prefix.
4. **But it buys nothing for the thing that matters.** `execve` is still denied,
   so a bionic Python could not spawn a subprocess of its own — no
   `subprocess.run`, no `npm` shelling out to `node`, no `git`. Everything would
   have to be `linker64`-trampolined by the app, which means Vessel would have to
   own process spawning for the whole toolchain. There is no UI for launching a
   Linux process at all today; the only launch path is `WineLaunch`. And
   `docs/LINUX-MODE.md:128-131` records that even the *first* process needs a
   ~40-line `SIGSYS` shim because the app's seccomp filter traps
   `set_robust_list(99)` and `rseq(293)`.
5. **And Claude Code, decisively, cannot go this way.** Its Linux binaries are
   `linux-arm64` (glibc) and `linux-arm64-musl`. Neither can be loaded by
   bionic's linker, and neither can be `execve`'d. **Its Windows ARM64 binary,
   run by Wine, is the only build of Claude Code this device can execute at
   all.** That is a genuinely counter-intuitive result and it settles the
   question.

**Verdict: Wine is the right answer, and not because Wine is good — because the
Windows PE loader is the only loader this app is allowed to use for arbitrary
binaries.** Wine's `ntdll` maps PE images with `PROT_EXEC` private file mappings
(`patches/wine/0002` exists precisely to keep those mappings *clean*, since
`execmod` on a dirtied one is denied), which is the permission the app *does*
have. Every alternative loader needs a permission it does not.

The one place a hybrid would pay is a build-host-side helper — none of this
work needs to happen on the phone. That is already the recommendation
everywhere in §2–§6: extract, verify and stage on the build host; the device
only ever runs already-unpacked trees.

---

## 10. The plan, ordered by value ÷ risk

Each phase names the **cheapest decisive experiment** — the one thing to try
first that proves or kills the phase before real work goes in. No phase begins
until the previous phase's experiment is green.

### Phase 0 — Prove the network. *Blocks everything.*

Nothing above §2 matters if the prefix cannot reach the internet. §1 shows six
Wine patches fighting for this and **zero** recorded end-to-end results.

> **Experiment.** Open Command Prompt in a container. Run `ipconfig` (patches
> 0013/0014/0015 exist because it printed nothing at all), then `ping 1.1.1.1`,
> then `nslookup api.anthropic.com`. Total cost: one session, no build.
>
> **Kills the phase if:** `ipconfig` prints nothing, or DNS does not resolve.
> Then the work is in `nsiproxy`/`dnsapi`, and it is a different document.

### Phase 1 — Python and Node as loose trees. *Highest value, near-zero risk.*

No packaging, no build scripts, no `.wcp`. Extract
`python-3.13.15-embed-arm64.zip` and `node-v24.19.0-win-arm64.zip` on the build
host, put both directories somewhere on `D:` (or import via
`FilesScreen`'s copy-into-`drive_c` path), and run them from Command Prompt with
a full path.

> **Experiment.** `node.exe -e "console.log(process.arch, process.platform)"`
> and `python.exe -c "import sys,ssl;print(sys.version)"`. Then the two that
> actually probe the container:
> `node.exe -e "console.log(require('os').networkInterfaces())"` and
> `node.exe -e "require('https').get('https://api.anthropic.com',r=>console.log(r.statusCode))"`.
>
> **Cost:** one download, one `adb push`, one session. **Zero shipping-code
> change.**
>
> **Kills the phase if:** `node.exe` does not start, or the HTTPS request hangs
> (libuv/IOCP or TLS). Both are deep Wine problems, not packaging ones.

### Phase 2 — Claude Code + a shell that works. *Highest value per line of work.*

Fetch the `win32-arm64` `claude.exe` from the release bucket, verify it against
the GPG-signed `manifest.json`, and drop it beside Node. Extract
`PowerShell-7.6.5-win-arm64.zip` beside it. Set `ANTHROPIC_API_KEY` and
`DISABLE_UPDATES=1` through the Diagnostics env table.

> **Experiment, in this exact order — the second one is the real question.**
> 1. `claude.exe --version` under `wineconsole`. Proves the native ARM64 binary
>    loads and the TUI has a console.
> 2. **`pwsh.exe -NoLogo -Command "Get-ChildItem C:\ | Select-Object -First 3"`.**
>    This is the decisive one: it asks whether a self-contained .NET runtime
>    starts under Wine ARM64EC. If it does, Claude Code has a shell. If it does
>    not, Claude Code is a file editor with no hands, and the phase stops.
> 3. `claude doctor`, then one real prompt that runs one command.
>
> **Cost:** two downloads, one session. **Zero shipping-code change.**
>
> **Kills the phase if:** `pwsh` does not start. Do not fall back to Git Bash —
> `TerminalProfile.kt:56-70` already measured that it pegs a core at 98% and
> hangs the phone.

### Phase 3 — Package it properly. *Only after 1 and 2 are green.*

Option A from §7.4: one combined `Tools` payload. The work is real but small and
entirely known:

- `build/devtools.sh` — fetch pinned upstream archives, verify SHA256, extract,
  stage under `git/`, `python/`, `node/`, `claude/`, `pwsh/`, write provenance,
  call `package_wcp.py --type Tools --version-code 1000000`.
- `native/pins.env` — a `DEVTOOLS_*` pin block with a SHA256 per upstream
  archive and a `DEVTOOLS_REVISION`, matching the existing convention.
- `SessionRuntime.installTools()` — stop hardcoding `GIT_PREFIX_DIR`/`GIT_SENTINEL`;
  take the destination from the payload (a top-level marker file, or the
  component's `name`).
- `PrefixRegistry` — new `PATH` entries, `SEED_VERSION` 23 → 24, and a comment
  recording why, in the style of the existing 9/15/19/21 entries.
- `.github/workflows/` — a `devtools.yml` off `_component.yml`, and remove the
  `--exclude git-…` line from `gen_registry.py` / `source_offer.py` once
  the package is actually published.

> **Experiment.** Build the `.wcp`, install it over an existing container, and
> check that `git --version`, `python --version` and `node --version` all answer
> from a fresh `cmd.exe` **with no path typed**. That single check proves the
> copy, the `PATH` seed, the `SEED_VERSION` bump and the version code all landed.
>
> **Watch for:** the silent no-op. If nothing changes on the device, the version
> code collided — `build/common.sh:403-418` describes exactly this failure and
> what it looks like.

### Phase 4 — Chrome, timeboxed to one afternoon. *Low value ÷ high risk.*

On the build host: `msiexec /a googlechromestandaloneenterprise_arm64.msi` (or
`7z x`) → `chrome.7z` → `Chrome-bin\<version>\`. Copy that tree in. Add it as an
app with `args` set — the field already exists (`AppShortcut.kt:33-34`).

> **Experiment, cheapest first, each one a different hypothesis:**
> 1. `chrome.exe --no-sandbox --disable-gpu --single-process about:blank`
>    — does the process model work at all?
> 2. `chrome.exe --no-sandbox --disable-gpu https://example.com`
>    — does multi-process + network work?
> 3. `chrome.exe --no-sandbox --use-angle=vulkan https://example.com`
>    — does ANGLE reach Turnip?
>
> **Kills the phase if:** (1) does not paint. That is bug 21232's failure mode
> and it is upstream, structural, and resolved-as-wontfix. **Do not iterate past
> two hours.** Write the negative result into this file and stop.

### Phase 5 — Claude Desktop. *Conditional. Do not schedule.*

Only if Phase 4 step (2) rendered a page. Then extract the consumer arm64
`setup` installer on the build host with `7z`, package the `app-<ver>` tree, and
launch `Claude.exe --no-sandbox`. Never the MSIX.

> **Experiment.** Does the window paint and does the login flow start?
>
> **Expect:** it does not. This phase exists so that the decision is recorded,
> not so that the work is planned.

---

## 11. What could not be determined without a device or a build

Everything in this list is a real unknown, not a hedge. Each names the
experiment that would close it.

1. **Whether DNS resolves and TLS completes from inside the prefix.** Six
   patches exist for the plumbing; no session log shows a successful lookup.
   → Phase 0.
2. **Whether a self-contained .NET 8/9 runtime (`pwsh.exe`) starts under Wine
   ARM64EC.** This is the highest-value single unknown in the document, because
   it decides whether Claude Code is useful or ornamental. → Phase 2 step 2.
3. **Whether libuv's IOCP path is complete enough for `node.exe` under load.**
   A `--version` proving nothing here; an HTTPS request and a file watch would.
   → Phase 1.
4. **What `GetAdaptersAddresses` actually returns after patches 0007/0013/0015.**
   The patches make it not-fail. Whether it reports a usable interface is
   unmeasured. → Phase 0/1.
5. **Whether Claude Code's TUI drives `conhost` correctly** — raw mode, resize,
   ANSI, bracketed paste. A `--version` does not exercise any of it.
6. **Whether TODO #17's `.msi` failure is `msi.dll` or the test package.**
   Relevant only if someone insists on running an installer; the whole plan
   routes around it.
7. **PyPI `win_arm64` wheel coverage** for whatever packages actually get used.
   No survey done.
8. **Unpacked sizes** for Python, Node, PowerShell, Chrome and Claude Desktop.
   Every archive size in §8 is measured; every unpacked size is an estimate.
   `unzip -l` on the build host closes this in a minute and nobody has run it.
9. **Claude Desktop's consumer installer format.** The `setup` redirect returned
   403 to an unauthenticated `curl` here, so whether it is Squirrel or NSIS is
   inferred from Electron convention, not observed.
10. **How much of Git's 373 MiB `usr/` tree `git.exe` actually needs.** Deleting
    it would be the single biggest disk win available; `git rebase -i` and the
    credential helpers are the risk.
11. **Peak RSS for any of this on the device.** Every memory number in §8 is a
    desktop-derived guess.
12. **Whether Chromium's `--no-sandbox` path gets past process launch under this
    Wine at all.** Two hours answers it; nothing else will.
