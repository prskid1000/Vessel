# Logging

Vessel keeps one log per container session, with a **fixed** default channel set
and no picker. This document is the source of truth for that configuration: why
it is fixed, what is in it, and the traps that make naive logging silently do
nothing. Code that implements it (`core/SessionEnvironment.kt`,
`data/SessionLogStore.kt`) points here rather than restating it.

Verified against Wine, DXVK, vkd3d-proton and Winlator-Ludashi source on
2026-08-07. Line references are to those trees at that date.

**The configuration below is not a setting, and the Diagnostics surface shows it
anyway.** `core/ContainerDiagnostics.kt` declares every term here as a read-only
row, so `ui/screens/DiagnosticsSection.kt` is a truthful inventory of what a
session is sent rather than a list of additions over something invisible. Those
rows cannot be edited and have no remove cross; a test asserts that the terms
they display concatenate to the string below character for character, and another
that each fixed variable's displayed value is what the session actually carries.

The *behaviour* is unchanged. An untouched container produces this environment
byte for byte, which `SessionEnvironmentTest` asserts; nothing here is reachable
by a manifest param; and every row a user adds is *appended*, so the parser's
left-to-right rule is what lets them override one channel without being able to
delete the rest. `loaddll` at `Errors` writes `-loaddll,err+loaddll` after the
prefix and quiets that channel alone. The one exception is `all`, which is shown
and never offered: `+all` is every class on every channel and a second `-all`
would erase the prefix, so neither direction is something a row may do.
`docs/DIAGNOSTICS-UI.md` is the design brief for that surface.

## The configuration

```sh
WINEDEBUG=-all,err+all,warn+module,+winediag,+loaddll,+debugstr

DXVK_LOG_LEVEL=info
DXVK_LOG_PATH=none                 # routes, does not silence — see below

VKD3D_DEBUG=warn
VKD3D_SHADER_DEBUG=warn
# VKD3D_LOG_FILE deliberately NOT set — setting it silences stderr

TU_DEBUG=<existing flags>,startup
```

`+debugstr` earns its place below, under *the guest program's own voice*; the
block above omitted it for one cycle while the code set it and the test asserted
it.

`DXVK_LOG_PATH=none` is a correction, not a downgrade, and the measurement is at
`core/SessionEnvironment.kt`'s assignment: pointed at the log directory, DXVK
wrote `metro_dxgi.log` *beside* the session log and the session log got zero
`info:` lines. On a Wine build `emitMsg` sends every line to `__wine_dbg_output`
regardless, and `none` makes `getFileName` open nothing. So the word routes the
output into the log rather than next to it.

## Three traps that make logging a no-op

All three are shipped in Winlator today. None produces an error; you simply get
nothing and conclude the app is quiet.

**1. Wine will not parse `WINEDEBUG` if stderr is `/dev/null`.**
`init_options()` in `dlls/ntdll/unix/debug.c` `fstat`s fd 2, recognises the null
device, sets `default_flags = 0` and **returns before reading the variable at
all**. Winlator redirects the child to `/dev/null` unless its debug dialog is
open (`ProcessHelper.java:91-94`), so its `WINEDEBUG` setting does nothing in
normal use.

This is worse than it first appears: **DXVK and vkd3d-proton both resolve
`__wine_dbg_output` from ntdll and write through it**, so the same redirect
discards the entire graphics story too, not just Wine's own output.

*Requirement:* the launcher must hand the process a real pipe on fd 2.

**2. `+err` is a channel name, not a severity.**
Wine's parser reads a leading `+` as a channel. There is no channel called
`err`, `warn` or `fixme`. Winlator's "enabled" preset expands to
`+warn,+err,+fixme` (`SettingsFragment.java:78`), registering three channels
that do not exist and leaving Wine at its stock defaults.

*Requirement:* class form, `err+all`. And `-all` must come **first** — parsing
is left-to-right and each new channel is seeded from `default_flags` as of that
moment, so a trailing `-all` erases everything before it. `warn+module` must
come *after* `err+all` so the module channel inherits ERR and ends up ERR|WARN.

**3. `VKD3D_LOG_FILE` removes vkd3d from stderr.**
`vkd3d_dbg_init_once` (`libs/vkd3d-common/debug.c`) is an if/else: set the
variable and it `fopen`s the file *instead of* resolving `__wine_dbg_output`.
Emit is `log_file = vkd3d_log_file ? vkd3d_log_file : stderr`. So setting it
does not add a file, it **moves** the output.

DXVK is the opposite — its file write is a separate unconditional block, so
`DXVK_LOG_PATH` is additive. Note that under Wine with no `DXVK_LOG_PATH`, DXVK
creates no file at all and everything rides stderr. Setting the path is worth
it only as insurance for the case where `__wine_dbg_output` fails to resolve.

## Why these channels

| Term | Bounded? | What it buys |
|---|---|---|
| `err+all` | yes, when healthy | Every error on every channel, including channels never named — `__wine_dbg_get_channel_flags` seeds unlisted channels from `default_flags`, and lazy channel init picks it up mid-run. Includes the missing-library `ERR` at `dlls/ntdll/loader.c:1179`, which is how an absent VC++ runtime appears. |
| `warn+module` | yes | The missing-*export* case, which nothing else catches. See below. |
| `+winediag` | yes, init only | 58 sites, 55 of them `ERR_(winediag)`: no Vulkan library, no display driver, broken .NET, missing codecs. Also the renderer-selection line, `"Using the Vulkan renderer."` / `"Using the OpenGL renderer."` — which tells you whether you landed on wined3d at all. |
| `+loaddll` | yes, 6 sites | The DLL inventory. Successful whole-module loads only, so it complements rather than duplicates `err+all`. |

### `warn+module` — why WARN and not just ERR

`err+all` already covers `err:module:`, which makes `warn+module` look
redundant. It is not. `dlls/ntdll/loader.c` carries 17 ERR, **14 WARN**, 6 FIXME
and 46 TRACE sites on the module channel, and the WARN tier holds exactly the
case ERR misses:

- `:1235` `WARN("No implementation for %s.%d imported from %s, setting to %p\n")`
- `:1251` the same, by name
- `:2090` `WARN("%s (ordinal %lu) not found in %s\n")` in `LdrGetProcedureAddress`

That is *"the DLL loaded, but an export is missing, so Wine stubbed it"* — which
later dies at a confusing address via `EXCEPTION_WINE_STUB`, far from the cause.
`err+all` misses it because it is WARN; `+loaddll` misses it because the module
loaded successfully. On a Wine build older than the API an application expects,
this is a common failure.

`warn+module` rather than `+module`, deliberately: the plain form would also
enable the 46 TRACE sites in `loader.c` plus six other files that default to the
module channel.

### `debugstr` — the guest program's own voice

`OutputDebugStringA/W` is how a Windows program reports its own diagnostics, and
it is the only channel a *game* writes to: a GUI application has no console, so
its `printf` goes nowhere, and everything it wants to say about why it is giving
up goes through this call. Wine implements it in `ntdll` and logs it on the
`debugstr` channel — and with the channel off it is discarded silently.

Added after a real failure. Metro 2033 Redux opened its window, ran for 1.2
seconds and exited **cleanly** — no unhandled exception, no tombstone, no
non-zero exit anybody could see. The session log's last line was a DLL load. A
program that exits deliberately has a reason, and this is the channel it would
have said the reason on.

Cheap, unlike the excluded ones below: a program only pays for the strings it
chooses to emit, and most emit none.

### `seh` — excluded, and crashes are free anyway

It is far noisier than its macro count suggests: `dispatch_exception`
(`dlls/ntdll/exception.c:284`) runs for **every raised exception, handled or
not**, emitting a code line, up to 15 `info[]` lines and a full register dump —
and C++ and .NET exceptions are SEH, so a managed app raises these constantly.

The decisive reason is that **`+seh` buys nothing for crashes**. Unhandled
exceptions do not go through the channel system at all: `format_exception_msg`
(`dlls/kernelbase/debug.c:464`) feeds `MESSAGE()`, which is unconditional
`wine_dbg_printf` with no channel and no class check. So

```
wine: Unhandled page fault on read access to ... at address ...
wine: Unhandled illegal instruction
wine: Call from %p to unimplemented function %s.%s, aborting
```

**print regardless of `WINEDEBUG`** — including the illegal-instruction case that
an unsupported instruction under FEX would produce.

### Also excluded

`relay` (every cross-DLL call — hundreds of MB in seconds), `d3d`/`ddraw`/`dxgi`
(per draw, and see below), `heap`, `sync`, `file`, `msg`.

`fixme` is excluded too. It is a genuine runtime message — it fires when the
application calls a stubbed API, not when a developer leaves a note — but a
typical program trips dozens of stubs it never depends on.

## DirectX

Wine's `d3d` channel belongs to **wined3d**. With DXVK installed, `d3d11`/`dxgi`
are DXVK's, so `+d3d` would log a code path that is not running. Excluding it
costs nothing; the DirectX signal comes from the translation layers themselves.

**DXVK at `info`** — its default — already covers what matters.
`checkDeviceCompatibility()` (`src/dxvk/dxvk_device_info.cpp:745`) returns a
*named* rejection: `"Device does not support required feature '<name>'
(extension: <ext>)"`, plus `"Device does not support Vulkan X.Y"`. That is
precisely the failure if Turnip does not load and the stock Qualcomm driver
takes over, since it lacks extensions DXVK 2.x requires. `logDeviceInfo()` dumps
device name, driver, the enabled-extension list and every feature flag.

**`VKD3D_SHADER_DEBUG` is a separate channel** from `VKD3D_DEBUG` — an array of
two, with independent levels. `VKD3D_DEBUG=warn` does **not** carry shader
translation failures. Both default to FIXME, so `warn` adds only the WARN tier,
which is bounded by shader count at pipeline-compile time rather than per-frame.
D3D12 on Adreno is the weakest part of this stack, so these should not be
silent. Do not set `trace` — that is the per-shader firehose.

**D3D9 through wined3d** is a diagnose-on-demand case, not an always-on one.
`err:d3d:` alone is not sufficient (capability shortfalls land at WARN/FIXME),
but `warn+d3d` is not safe either: wined3d has 659 `ERR(` sites with only 19
`once` guards and owns per-draw paths, so its WARN tier is unbounded in a way
`module`'s is not. The `winediag` renderer line already tells you when you have
landed on wined3d, which is the part worth knowing by default.

## `err+all` is bounded only while things work

The same wined3d problem applies to errors: a recurring draw-path failure
(`dlls/wined3d/cs.c:2651`, `:2835`, `:4437`) emits every frame. DXVK keeps
wined3d mostly out of the picture, but "mostly" is not a guarantee.

This is not fixable by excluding channels — the offending sites are ordinary
`ERR`s that matter when they fire once. It is handled at the sink, in three
layers:

0. **The producer channel**, bounded and `DROP_OLDEST`, so a burst can never
   become back-pressure on the process being logged.
1. **Dedup** consecutive identical lines into `line  ×N`.
2. **Rate limit** to a few thousand lines/second with an explicit
   `… rate-limited, N dropped …` marker — catches *alternating* errors in a
   loop, which dedup cannot.
3. **Head+tail cap** — keep the first part (init: module loads, driver and D3D
   setup) and the last part (the crash), eliding the middle.

Every layer announces itself. A log that hides its own truncation is worse than
no log.

**Layer 0 broke that rule until the caps became adjustable**, and it is worth
recording why it went unnoticed: `trySend` reports success whether or not the
channel dropped something, so nothing counted it, and at the old fixed caps it
almost never fired. It is the *first* layer to bite once a channel is raised, so
it counts now — `SessionLogMeta.overflowLines`, and a
`… N lines dropped before the sink could write them …` marker in the file.

The three numbers in layer 2 and 3 are per container
(`core/ContainerDiagnostics.kt`, `SessionLogLimits`) rather than constants, and
they default to the top of their ladders: 32 MB of head, 16 MB of retained tail,
20 000 lines a second — 48 MB a session and 480 MB a container at ten sessions,
against the 5 / 3 / 2 000 and 80 MB this document used to describe. **The byte
caps and the rate limit move together**: at roughly 120 bytes a line the rate
decides how fast the bytes are reached, so raising either alone buys nothing.
Because that ceiling is large enough to matter, the surface that raises it shows
the container's actual log usage and carries a *Delete all logs* action.

## Detecting a silent driver fallback

**No Wine channel reports which Vulkan driver was selected**, and DXVK cannot be
trusted for it in a Winlator-style container either: `VK_ICD_FILENAMES` points
at a wrapper ICD and `WRAPPER_DEVICE_NAME` can overwrite the reported device
string outright.

The failure is real and currently invisible. If a driver id does not resolve,
`AdrenotoolsManager.setDriverById()` (`contents/AdrenotoolsManager.java:205-222`)
falls through **without setting `ADRENOTOOLS_DRIVER_*` and without logging**, and
the system Vulkan driver takes over. Everything appears to work — slower, with
different bugs.

Two cheap signals:

- **Assert on our side.** Log whether `ADRENOTOOLS_DRIVER_PATH` / `_HOOKS_PATH`
  / `_NAME` were set and whether the `.so` exists at that path.
- **`TU_DEBUG=startup`.** Only Turnip honours it. Output means Turnip loaded;
  silence means it did not. This is the ground truth — **and today it is ground
  truth the product cannot see.** Mesa picks its logger at init and under Android
  the default is logcat (`native/mesa/src/util/log.c:118-128`,
  `__android_log_write` at `:388`), and Vessel reads no logcat, so the line lands
  where only `adb logcat` finds it. The fix is one variable Mesa already has:
  `MESA_LOG=file` adds the file logger and `mesa_log_file` defaults to `stderr`
  (`log.c:64-74, 145`), which is the pipe the session log reads. Diagnostics
  offers it as *Driver messages in the log*, off by default.

  **Unverified end to end.** That the mechanism exists is read out of Mesa's
  source; that Turnip's lines actually arrive in the session log has not been
  observed on the device. What would settle it is one session with `MESA_LOG=file`
  set and `grep -c 'TU_DEBUG='` over its log returning non-zero. Until then no
  `TU_DEBUG` *flag* control is offered, because a switch whose output nothing can
  read is worse than no switch.

## FEX: `SILENTLOG`, and nothing else on this platform

This was an open item — `Source/Common/Config.cpp` no longer carries
`silentlog`/`outputlog`, and Ludashi's 15 FEX variables are all tuning knobs. It
is answered at a primary source now.

`native/fex/Source/Windows/Common/Logging.cpp:36-49` is the **entire** Windows
logging init. It reads `SilentLog` and nothing else: when not silent it resolves
`__wine_dbg_output` out of ntdll — the pipe the session log already reads — and
when that does not resolve it falls back to a file under `%LOCALAPPDATA%`.

Two consequences, both already in the code:

- **`FEX_SILENTLOG=0` is the whole configuration.** The default hides more than
  crashes: `FEX_HOSTFEATURES` skips a token it does not recognise with only a log
  line, so with the default a typo in a host-feature override is invisible.
- **`FEX_OUTPUTLOG` does nothing here.** It is a Linux/FEXServer option and is
  never consulted on Windows. Vessel sets `stderr` as a marker of intent and no
  control offers it, because a switch that changes nothing is worse than none.

An unsupported instruction is still visible either way: it surfaces as Wine's
unconditional `wine: Unhandled illegal instruction`, which does not go through
the channel system at all.

## For reference: what Winlator exposes

`app/src/main/assets/wine_debug_channels.json` lists **521 channels** as a flat
list for the user to choose from. The default is `WINEDEBUG=-all` — off — and
the setting is app-global rather than per-container.

Vessel offers none of them. Choosing well among 521 requires knowing what each
costs, and the three traps above mean a user who chooses correctly can still get
nothing. A fixed set that works beats a menu that mostly does not.
