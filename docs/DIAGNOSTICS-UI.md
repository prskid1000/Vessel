# Diagnostics UI — design brief

A new surface reached from the container view: the debug channels Vessel already
sets, plus the ones it could set, each with its own level, all off by default and
invisible until asked for.

This document states what exists today with evidence, what is being asked for,
and the decisions the design pass has to make. It does not specify pixels.
Every factual claim carries `file:line`; anything not verified says so.

Verified against this tree on 2026-08-10.

---

## Files to read first

Eight files, in this order.

| File | Why |
|---|---|
| `docs/LOGGING.md` | The logging contract. Why the channel set is fixed, why `err` is a class and not a channel, and the three traps that make logging silently do nothing. Read this before forming any opinion. |
| `app/src/main/java/app/vessel/core/SessionEnvironment.kt` | Every diagnostic variable Vessel sets, the reason beside each, `RESERVED_SESSION_ENV` (:217) and `BOOTSTRAP_SESSION_ENV` (:131). The merge loop at :741-743 is where the whole integration problem lives. |
| `app/src/main/assets/params-manifest.json` | The settings law, in its `_comment` block (:3-25): nothing hidden, order is the hierarchy, and a setting that cannot be explained in one plain sentence does not belong in the file at all. |
| `app/src/main/java/app/vessel/core/params/ParamManifest.kt` | The six control types the editor can draw (:69-86) and the four value shapes they store (:196-217). A seventh type is the one manifest change that also needs UI (:66-67). |
| `app/src/main/java/app/vessel/ui/screens/ContainerSheet.kt` | The renderer. One `when`, over `ParamType` (:224-316), no manifest key anywhere in it. The Session logs button at :149-154 is the nearest thing to an entry point today. |
| `app/src/main/java/app/vessel/data/SessionLogWriter.kt` | Where the caps live: 5 MB head, 3 MB tail, 2000 lines/second (:395-421), and the three defences described at :31-44. |
| `app/src/main/java/app/vessel/data/SessionLogStore.kt` | Ten sessions per container (:354), the byte-cursor read (:168-218), and the note at :74-79 about fd 2 having to be a real pipe. |
| `app/src/main/java/app/vessel/ui/screens/SessionLogScreen.kt` | The viewer this surface has to sit next to, including why the severity filter that used to be there was removed (:70-75). |

Two more if the Turnip and app-side channels stay in scope:
`app/src/main/java/app/vessel/display/XServerDisplay.kt:476-517, 988-991` (the
one app-side channel, gated on a system property) and
`docs/ARCHITECTURE.md:141-193` (why no `TU_DEBUG` rendering mode is forced).

---

## 1. The problem, and the constraint that makes it interesting

Vessel runs a Windows program through five layers that each fail differently —
Wine, FEX, DXVK, vkd3d, Turnip — and when one of them goes wrong the only
evidence is a log. Today that log's content is one fixed string
(`SessionEnvironment.kt:18`) chosen once, in `docs/LOGGING.md`, for a session
that is behaving. When a session is not behaving, there is no way to ask a
louder question without rebuilding the app.

So the ask is a Diagnostics surface. The constraint is that this codebase has
already written down, twice and emphatically, why it does not have one:

> "'help' is shown next to the control. It must be one plain sentence that a
> user who does not know what a translator is can act on. If a setting cannot be
> explained that way, it does not belong in this file at all."
> — `app/src/main/assets/params-manifest.json:9-12`

> "Nothing here is hidden any more: there is no 'advanced' flag and no
> disclosure in the editor. That means ORDER IS THE HIERARCHY."
> — `params-manifest.json:14-16`

> "**There is no 'Show advanced'.** If the manifest ever grows past what a sheet
> can hold, the answer is to cut knobs, not to add a disclosure."
> — `docs/DESIGN.md:301-303`

`VKD3D_SHADER_DEBUG` cannot be explained to a user who does not know what a
translator is. Neither can `warn+module`. Under the manifest's own law these do
not belong in the manifest — and that law is right: it is what kept the
container sheet at three controls while the environment grew to thirty-odd
variables.

### The resolution

**A separate Diagnostics screen, with its own rules, reached from the container
view. Not more rows in the container sheet, and not a manifest group.**

The manifest's law is about *settings* — things a user changes to make a program
run better. Diagnostics are not settings. They change nothing about how the
program runs; they change what the run says about itself, they are entered only
when something is already broken, and their audience is someone who has been
told by a maintainer, an issue thread or this document what to switch on. A
surface with a different audience gets different rules:

| | Container sheet | Diagnostics |
|---|---|---|
| Audience | anyone with a container | someone diagnosing a failure |
| Explainable in one plain sentence? | required | replaced by "what question does this answer?" |
| Hidden by default | never | the whole screen is, and every control in it starts off |
| Persists | yes | yes, except the dangerous tier — see §7 |
| Declared in | `params-manifest.json` | its own schema; see §6 |

The two must not merge. Putting a `VKD3D_SHADER_DEBUG` row in the container
sheet would break the manifest law on the first commit; putting the resolution
picker on the Diagnostics screen would hide a setting people use daily behind a
screen labelled for people whose sessions are crashing.

**Entry point.** The container sheet's bottom row already carries one
destructive action and one destination (`ContainerSheet.kt:135-156`). A second
destination beside *Session logs* is the natural place, and the pairing reads
correctly: *Session logs* is what the run said, *Diagnostics* is what it will be
asked to say next time. Route it like the log routes — hanging off a container,
never a global destination, for the reason `Navigation.kt:82-89` gives.

---

## 2. Everything Vessel sets today

Read end to end from `SessionEnvironment.kt`. **`R` = in `RESERVED_SESSION_ENV`
(:217-283), so a manifest param may not set it. `B` = in
`BOOTSTRAP_SESSION_ENV` (:131-150), so it also reaches `wineboot` and `regedit`
while the prefix is being built.**

### Diagnostic proper

| Variable | Value today | Set at | R | B | The reason on record |
|---|---|---|---|---|---|
| `WINEDEBUG` | `-all,err+all,warn+module,+winediag,+loaddll,+debugstr` | :18, assigned :520 | ✓ | ✓ | Fixed per `docs/LOGGING.md`. "Order is load-bearing and this string must not be reformatted" (:10-11); `err` is a class, not a channel (:12-14). |
| `DXVK_LOG_LEVEL` | `info` | :543 | ✓ | | DXVK's own default, and `LOGGING.md:150-157` argues it already names the rejection reason when a device is unsuitable. |
| `DXVK_LOG_PATH` | `none` | :566 | ✓ | | **Routes, does not silence.** Pointed at the log directory, DXVK wrote `metro_dxgi.log` beside the session log and the session log got zero `info:` lines — measured (:545-559). `none` makes `getFileName` open nothing, and `emitMsg` still writes through `__wine_dbg_output`. |
| `VKD3D_DEBUG` | `warn` | :568 | ✓ | | |
| `VKD3D_SHADER_DEBUG` | `warn` | :569 | ✓ | | A separate channel array with an independent level; `VKD3D_DEBUG` does not carry shader translation failures (`LOGGING.md:158-163`). |
| `VKD3D_LOG_FILE` | **never set** | — | ✓ | | Reserved to guarantee its *absence*: `vkd3d_dbg_init_once` is an if/else, so setting it opens a file **instead of** resolving `__wine_dbg_output` (:212-216, `LOGGING.md:56-62`). |
| `TU_DEBUG` | `startup` | :597 via :818-832 | ✓ | | `startup` is appended unconditionally (:191, :831) — "the only ground truth for whether Turnip loaded" (:363-364). No container declares a `TU_DEBUG` param today, so the composed value is exactly `startup` (asserted, `SessionEnvironmentTest.kt:207-209`). |
| `FEX_SILENTLOG` | `0` | :434 | ✓ | ✓ | The default `true` hides more than crashes: `FEX_HOSTFEATURES` skips unrecognised tokens with only a log line, so a typo is invisible (:418-424). Off makes FEX bind ntdll's `__wine_dbg_output`. |
| `FEX_OUTPUTLOG` | `stderr` | :435 | ✓ | ✓ | **Dead on Windows, kept as a marker** (:426-433). Confirmed independently: `native/fex/Source/Windows/Common/Logging.cpp:36-49` is the entire Windows logging init and reads `SilentLog` and nothing else, falling back to `%LOCALAPPDATA%\fex-<pid>.log` when `__wine_dbg_output` does not resolve. |

### Reserved but not diagnostic — do not offer these

**`MESA_VK_WSI_DEBUG` moved onto this surface and is no longer in this
paragraph.** It said "not a debug switch on this build: unset, the DRI3 branch is
`__builtin_unreachable()` and a present measured 12.8 ms". That was true and
stopped being: `patches/mesa/0004` and `0006` compiled the DRI3 half, and
`patches/mesa/README.md` measures it at 0.602 ms against the software path's
2.143 ms. It is a declared row in the `mesa` family now, with two stops — `sw`
and the empty string, which is DRI3 — and it is the only row on this screen that
changes the frame rather than the log. The default does not move; see
`ZERO_COPY_PRESENT` in `SessionEnvironment.kt` for what is still unproven and why
a per-container switch was the right first step.

`VKD3D_CONFIG=nodxr` (:584) gates ray tracing.
`MESA_SHADER_CACHE_DISABLE` / `_DIR` (:592-593), `DXVK_STATE_CACHE_PATH` (:594),
`VKD3D_SHADER_CACHE_PATH` (:595) and `FEX_APP_CACHE_LOCATION` (:503) are cache
locations. `tu_override_uncached_as_cache_coherent` (:688) is a
correctness/performance pairing with FEX's store-release behaviour.
`FEX_TSOENABLED` / `_HALFBARRIERTSOENABLED` / `_VECTORTSOENABLED` (:437-439) and
`FEX_DISABLEL2CACHE` / `_DYNAMICL1CACHE` (:493-494) stopped being settings on
purpose, and the comment at :249-252 explains that reserving them is *what makes
that stick* — a container document saved while they were switches still carries
the old values, and unreserving hands them straight back.

**That last sentence is the single most important thing in this document. See
§6.**

---

## 3. Where the output actually goes — and one place it does not

Everything diagnostic is supposed to arrive on fd 2 of the Wine process, where
`SessionLogStore.open` reads it (`SessionLogStore.kt:61-84`). Wine writes there
directly; DXVK and vkd3d resolve `__wine_dbg_output` out of ntdll and write
through it; FEX does the same once `FEX_SILENTLOG=0`.

**Turnip does not.** Mesa picks its logger at init and under Android the default
is logcat:

```c
if (!(mesa_log_control & MESA_LOG_CONTROL_LOGGER_MASK)) {
#if DETECT_OS_ANDROID
   mesa_log_control |= MESA_LOG_CONTROL_ANDROID;
```
— `native/mesa/src/util/log.c:119-124`, with `logger_android` calling
`__android_log_write` at `:388`.

Vessel reads no logcat: a grep for `logcat` across `app/src/main/java` returns
one comment and no code. So **`TU_DEBUG=startup`, which `docs/LOGGING.md:209-210`
calls "the ground truth", produces output the product cannot see.** It is
visible over `adb logcat` and nowhere else. Any Diagnostics control that offers a
`TU_DEBUG` flag today would be offering a switch whose result never reaches the
log the user is being sent to read.

The fix is one variable and it is already in Mesa: `MESA_LOG=file`
(`log.c:64-74`) adds the file logger, and `mesa_log_file` defaults to `stderr`
(`log.c:145`) — the pipe the session log already reads. The line parser is
ready for it: `DRIVER_CHANNELS` is `{vulkan, winevulkan, turnip}` plus any
`mesa`/`tu_` prefix (`SessionLogFormat.kt:198-199, 307`) and `LogSource.DRIVER`
exists (`:16`). Note `MESA_LOG_LEVEL` is a second, separate gate defaulting to
`MESA_LOG_INFO` in a release build (`log.h:49-53`, `log.c:134-138`), which
passes Turnip's `mesa_logi` startup lines (`tu_util.cc:135-136`) and drops its
`mesa_logd` ones (`tu_util.cc:108`).

**Unverified:** that `MESA_LOG=file` in fact lands Turnip's lines in the session
log. What would settle it is one device session with `MESA_LOG=file` set and
`grep -c 'TU_DEBUG=' ` over the session log returning non-zero.

The app-side channel has the same shape of problem. `XServerDisplay` dumps the
window tree only when `Log.isLoggable("VesselWindows", DEBUG)`
(`XServerDisplay.kt:476, 991`), which is enabled by `adb shell setprop
log.tag.VesselWindows DEBUG` (:481-482). **An app cannot set that property**, so
an in-app toggle has to be a second condition beside the `isLoggable` call, and
the dump has to be written to the `SessionLog` rather than to `Log.d` if it is
to be readable on the device.

---

## 4. The level vocabularies, verified

The four subsystems have four different models. The UI must not draw them as one
control repeated four times.

### Wine — classes per channel, and the order is the semantics

Four classes: `fixme`, `err`, `warn`, `trace`
(`native/wine/dlls/ntdll/unix/debug.c:65`). `default_flags` starts as
`ERR|FIXME` (:60). The parser walks left to right, and when a token names a
channel for the first time that channel is created with
`flags = (default_flags & ~clear) | set` — `default_flags` **as of that moment**
(`debug.c:122`). A token naming a channel that already exists ORs into it
(`debug.c:103-107`). `-all` and `+all` write `default_flags` directly
(`debug.c` `parse_options`, "all" branch). A bare `+x` sets `~0`, all four
classes; a bare `-x` clears `~0`.

That is exactly what `LOGGING.md:53-55` states, and it is why
`SessionEnvironment.kt:10-11` forbids reformatting the string.

Given the fixed prefix `-all,err+all`, a curated channel `x` at each level
requires:

| Level shown | Terms to emit | Resulting flags |
|---|---|---|
| Off | `-x` | none |
| Errors | *(nothing)* | ERR, inherited from `default_flags` |
| + Warnings | `warn+x` | ERR \| WARN |
| + Stubs | `warn+x,fixme+x` | ERR \| WARN \| FIXME |
| Everything | `+x` | all four |

Note the third row: a lone `fixme+x` would give ERR|FIXME and **skip WARN**,
because inheritance is from `default_flags` and not from the previous level. A
"level" ladder that emits one term per stop is wrong. This is derived from the
parser source above; **unverified against a run** — what would settle it is a
unit test asserting the composed string for each stop, in the shape
`SessionEnvironmentTest.kt:126-138` already uses.

Two more parser facts the UI has to respect:

- **`WINEDEBUG=help` kills the process.** `debug_usage()` writes a usage block to
  fd 2 and calls `exit(1)` (`debug.c`, `debug_usage`). A free-text field that
  passes the literal `help` through hands the user a session that dies at
  startup with no explanation.
- **Per-program scoping exists.** `process:class+channel` matches the token
  before `:` against `argv[1]`'s basename, case-insensitively (`debug.c`,
  `parse_options`). `metro.exe:+relay` is a legal way to make a firehose
  affect one program. Worth offering in the escape hatch's help text.

### DXVK — one minimum severity, six stops

`DXVK_LOG_LEVEL` ∈ `trace, debug, info, warn, error, none`
(`native/dxvk/src/util/log/log.cpp:146-152`), enum `Trace=0 … None=5`
(`log.h:12-19`), filtered as `if (level >= m_minLevel)` (`log.cpp:48`).
So it is a floor, `trace` is the loudest, and the default is `Info`
(`log.cpp:162`) — which is what Vessel sets.

### vkd3d — two channels, six stops, **and the order is not what you would guess**

```c
/* VKD3D_DBG_LEVEL_NONE  */ "none",
/* VKD3D_DBG_LEVEL_ERR   */ "err",
/* VKD3D_DBG_LEVEL_INFO  */ "info",
/* VKD3D_DBG_LEVEL_FIXME */ "fixme",
/* VKD3D_DBG_LEVEL_WARN  */ "warn",
/* VKD3D_DBG_LEVEL_TRACE */ "trace",
```
— `native/vkd3d/libs/vkd3d-common/debug.c:38-47`, matching
`include/private/vkd3d_debug.h:38-47`. Emission is
`if (vkd3d_dbg_get_level(channel) < level) return` (`debug.c:181`), so a higher
name in that list includes every lower one. **`info` sits between `err` and
`fixme`**, and `warn` therefore already carries `info` and `fixme`. The default
is `FIXME` (`debug.c:96-97`), so Vessel's `warn` adds exactly the WARN tier —
which is what `LOGGING.md:160-162` says.

Two channels, two variables: `VKD3D_DEBUG` is the API channel and
`VKD3D_SHADER_DEBUG` the shader channel (`debug.c:49-53`). They are independent
and both must appear as separate controls.

### Turnip — a flag list, not a level

`TU_DEBUG` is parsed with `parse_debug_string` against a 42-entry table
(`native/mesa/src/freedreno/vulkan/tu_util.cc:21-61`, parsed at `:133`). There
is no severity anywhere in it. Vessel composes it as a comma-joined list and
always appends `startup` (`SessionEnvironment.kt:818-832`), filtering the
manifest's "leave the driver to decide" placeholders (`:200, :829`). A boolean or
integer param carrying `env: TU_DEBUG` is ignored rather than rendered as `1`,
because `1` is not a Turnip flag (`:816-817, 483-490` in the test).

Drawing this as a level would be a lie. It is a multi-select, and see §3 for
whether its output is visible at all.

### FEX — one boolean, and one variable that does nothing

`FEX_SILENTLOG=0` is the whole of it. `FEX_OUTPUTLOG` is inert on this platform
(`Logging.cpp:36-49`) and is set only as a marker. Do not draw a control for it.

---

## 5. Log capture, limits and retention today

One file per session at `filesDir/logs/<containerId>/<startedAt>.log`, with a
`.meta.json` sidecar beside it (`SessionLogFiles.kt:12-45`,
`SessionLogStore.kt:43, 350`). Four limits, in the order a line meets them:

| Limit | Value | Where | Announces itself? |
|---|---|---|---|
| Producer channel | 8192 entries, `DROP_OLDEST` | `SessionLogWriter.kt:63, 417` | **No** |
| Dedup | consecutive identical lines → `line ×N` | `:177-204` | yes |
| Rate limit | 2000 lines/second | `:213-232, 413-414` | yes, `… rate-limited, N dropped …` |
| Head cap | 5 MB | `:401` | yes, via the elision marker |
| Tail | 2 × 1536 KB retained, so 1.5–3 MB | `:404, 285-300` | yes |
| Sessions kept | 10 per container | `SessionLogStore.kt:318-326, 354` | no — pruning is silent |

Eight megabytes per session, eighty per container, worst case. Reads take a byte
cursor rather than a line number because "resuming a tail must not cost a rescan
of everything already read — following a live session would otherwise re-read
the whole file every quarter second" (`SessionLog.kt:116-121`); `read` is
additionally bounded by `MAX_SCAN_LINES = 20_000` per call
(`SessionLogStore.kt:357`) and the clipboard by 512 KB of characters (`:358`,
with the truncation stated in the copied text at `:241`).

The viewer is `SessionLogScreen.kt`; the per-container history list is
`SessionLogsScreen.kt`; routes are `logs/{containerId}` and
`logs/{containerId}/{startedAt}` (`Navigation.kt:90-91`).

### What "limits and retention to maximum" has to mean

The caps are not incidental — they are the third of the three layers
`LOGGING.md:172-190` prescribes as the answer to `err+all` being unbounded when
things go wrong. Raising them is undoing a deliberate design, so it has to be
done with the arithmetic visible:

- At 2000 lines/second and roughly 120 bytes a line, a runaway fills the 8 MB
  cap in about 35 seconds. So raising the byte cap without raising the rate
  limit buys a longer window at the same fidelity, and raising the rate limit
  without raising the byte cap just reaches the cap sooner. **Neither number
  moves alone.**
- The one layer that drops silently is the producer channel
  (`SessionLogWriter.kt:63`). It is bounded at 8192 and `DROP_OLDEST`, and
  nothing counts what it discarded. At today's limits it almost never fires; at
  "maximum" limits with `+relay` on it is the first thing to bite, and it will
  do so invisibly. **`docs/LOGGING.md:190` — "A log that hides its own
  truncation is worse than no log" — is currently violated by exactly one code
  path, and turning the limits up is what makes it matter.** Fix that before
  raising anything.
- Storage is `filesDir`, internal. Ten sessions per container is a history
  budget, not a fidelity budget, and it should stay ten: a diagnostician
  compares a bad run against a good one, and ten runs is what
  `SessionLogStore.kt:353` says that takes.

Proposed shape, for the designer to price rather than to accept as given:
raise head to 32 MB and each tail segment to 8 MB (48 MB a session), raise the
rate limit to 20 000 lines/second, keep ten sessions. Worst case becomes 480 MB
per container, which is too much to spend silently — so the Diagnostics screen
must show current log usage for this container as a number, and carry a *Delete
all logs* action. **A screen that raises a storage ceiling and does not show the
storage is not finished.**

An alternative worth considering instead: keep the per-session cap where it is
and make "maximum" a *container-wide budget* the store enforces by pruning
oldest-first. That bounds the worst case at one number the user can see and set,
rather than at a product of three.

---

## 6. The integration detail that decides whether any of this works

`sessionEnvironment` composes the fixed variables first, then merges the
manifest's contributions, and the merge is filtered:

```kotlin
for ((key, value) in manifestEnvironment(profile, manifest)) {
    if (key !in RESERVED_SESSION_ENV) environment[key] = value
}
```
— `SessionEnvironment.kt:741-743`

Every variable a Diagnostics control needs to write — `WINEDEBUG`,
`DXVK_LOG_LEVEL`, `VKD3D_DEBUG`, `VKD3D_SHADER_DEBUG`, `TU_DEBUG`,
`FEX_SILENTLOG` — is in `RESERVED_SESSION_ENV`. A param naming one is **dropped
silently**: no error, no log line, no failing build. The test suite guards this
deliberately (`SessionEnvironmentTest.kt:170-190`, `148-168`).

### Do not unreserve

The obvious move — take the diagnostic variables out of `RESERVED_SESSION_ENV`
and declare them as manifest params — is wrong for a reason already written down
in the file:

> "The FEX memory-ordering flags are listed for the same reason as `WINEESYNC`:
> they stopped being settings. Reserving them is what makes that stick — a
> container document saved while they were still switches still carries the old
> values, and without this the manifest merge would hand them back."
> — `SessionEnvironment.kt:249-252`

Unreserving `WINEDEBUG` re-opens exactly that: a container saved by a future
build, or hand-edited, could set an arbitrary `WINEDEBUG` through the ordinary
param path and bypass every ordering rule `LOGGING.md` exists to enforce. It
would also put a diagnostics control in the manifest, which §1 rules out.

### Do this instead — a third merge stage

Three stages, in this order, with the diagnostics stage last:

```
1. fixed          the values in §2, exactly as today
2. manifest       for each param with an `env`: skip if key ∈ RESERVED_SESSION_ENV
3. diagnostics    for each enabled diagnostic control:
                     require key ∈ DIAGNOSTIC_SESSION_ENV, then write
```

with a new constant beside the existing one:

```
DIAGNOSTIC_SESSION_ENV ⊂ RESERVED_SESSION_ENV
  = { WINEDEBUG, DXVK_LOG_LEVEL, VKD3D_DEBUG, VKD3D_SHADER_DEBUG,
      TU_DEBUG, FEX_SILENTLOG, MESA_LOG, MESA_LOG_LEVEL,
      VKD3D_CONFIG, DXVK_CONFIG, VESSEL_AUDIO_DUMP, MESA_VK_WSI_DEBUG }
```

Four properties this buys, and each is the answer to a way the obvious
alternatives fail:

1. **`RESERVED_SESSION_ENV` keeps its whole meaning for the manifest path.** No
   container document can reach any of these through a param, ever. The
   partition is explicit rather than implied by absence.
2. **The diagnostics stage is a *narrowing*, not a replacement.** `WINEDEBUG` is
   composed by a function that starts from `WINEDEBUG_CHANNELS` and appends —
   never a value handed over whole. This is the same shape `dllOverrides` already
   uses, and for the same stated reason: "Wine parses the string left to right
   and a later entry wins, so a user who really does need `d3d9=b` to work
   around one program can have it without being able to break the defaults for
   everything else by accident" (`SessionEnvironment.kt:765-771`).
3. **`VKD3D_LOG_FILE` stays unreachable**, because it is not in
   `DIAGNOSTIC_SESSION_ENV`. The variable whose whole purpose is an absence
   (`:212-216`) cannot be reached by any path.

   *`MESA_VK_WSI_DEBUG` was named here too and is now in the set.* The two were
   never the same case: one must be *absent* or vkd3d's output leaves the pipe
   the session log reads, the other merely had a *fixed value* — fixed because
   half of Mesa's X11 WSI was not compiled, which `patches/mesa/0004` and `0006`
   fixed. See the entry in `DIAGNOSTIC_SESSION_ENV`.
4. **The set is assertable.** A test that `DIAGNOSTIC_SESSION_ENV ⊆
   RESERVED_SESSION_ENV`, and that a diagnostics profile with everything off
   produces byte-for-byte the environment `SessionEnvironmentTest.kt:504-568`
   already pins, is the whole safety property in two assertions.

### The bootstrap consequence, stated rather than discovered later

`WINEDEBUG`, `FEX_SILENTLOG` and `FEX_OUTPUTLOG` are in
`BOOTSTRAP_SESSION_ENV` (`:143, 145, 146`), so whatever Diagnostics composes for
`WINEDEBUG` **also reaches `wineboot` and `regedit` during prefix creation**.
That is mostly desirable — a prefix that fails to build is worth diagnosing —
but it is not free. The comment at `:113-119` records `wineboot --init` reaching
`rundll32 setupapi,InstallHinfSection PreInstall` and stopping, with `drive_c`
still empty two minutes later, when it was handed the full session environment.
Turning `+relay` on for a first launch would make prefix creation dramatically
slower and would look exactly like that hang.

**Decision for the designer:** either the dangerous tier is refused on a
container that has never been launched, or the warning copy says that the first
launch after enabling it will take much longer. Do not leave it unsaid.

---

## 7. The proposed controls

Curated, not complete. `docs/LOGGING.md:222-230` is explicit about why:

> "`wine_debug_channels.json` lists **521 channels** as a flat list for the user
> to choose from… Vessel offers none of them. Choosing well among 521 requires
> knowing what each costs, and the three traps above mean a user who chooses
> correctly can still get nothing. A fixed set that works beats a menu that
> mostly does not."

The rule for this table: **every row answers a question someone has actually
had.** A channel that does not have a question against it does not get a row —
it gets the escape hatch.

### Tier 0 — the default, and it is invisible

Nothing on screen is switched on. The environment is byte-for-byte what §2
lists, which is what `SessionEnvironmentTest.kt:504-568` already asserts. A
fresh container has an empty diagnostics record and produces no diagnostics
merge at all.

The screen states this as one line at the top — *"Vessel already records
errors, missing DLLs, loaded modules and the program's own messages. Everything
below adds to that."* — because a screen full of Off switches otherwise reads as
"logging is disabled", which is the opposite of true.

### Tier 1 — curated channels with levels

Wine rows. Level control is the five-stop ladder from §4:
**Off · Errors · + Warnings · + Stubs · Everything.**

| Label | Writes | Default | One-sentence help |
|---|---|---|---|
| Missing DLLs and exports | `module` | + Warnings *(today's `warn+module`)* | Says when a library loaded but an entry point inside it is missing, which is what later crashes far from the cause. |
| Loaded modules | `loaddll` | Everything *(today's `+loaddll`)* | Lists every DLL the program successfully loaded. |
| Wine's own warnings | `winediag` | Everything *(today's `+winediag`)* | Wine's report on its own health: no Vulkan library, no display driver, broken .NET. |
| The program's messages | `debugstr` | Everything *(today's `+debugstr`)* | What the program itself chose to print — often the only reason a game gives for quitting. |
| Graphics through Wine | `d3d` | Off | Only relevant when a program falls back to Wine's own Direct3D instead of DXVK. |
| Vulkan setup | `vulkan` | Off | How Wine found and opened the graphics driver. |
| File access | `file` | Off | Every file the program opens, and every one it fails to open. |

`winediag`, `loaddll` and `debugstr` have no useful intermediate level — they are
`ERR`-heavy or single-class in practice — so their ladder can collapse to
On/Off. `module` needs the full ladder because the whole argument in
`LOGGING.md:76-95` is about the WARN tier specifically.

`d3d` gets a level and a caution rather than a warning gate: `err+d3d` is
survivable, `warn+d3d` is not — "wined3d has 659 `ERR(` sites with only 19 `once`
guards and owns per-draw paths, so its WARN tier is unbounded in a way
`module`'s is not" (`LOGGING.md:164-170`). Cap the ladder at *+ Warnings* with
the count in the help text, or move the higher stops into tier 3.

Subsystem rows, each with its own native vocabulary:

| Label | Writes | Type | Default | One-sentence help |
|---|---|---|---|---|
| DXVK (Direct3D 9–11) | `DXVK_LOG_LEVEL` | 6-stop: none / error / warn / info / debug / trace | `info` | How the Direct3D translator reports itself; `info` already names the reason a device was rejected. |
| vkd3d (Direct3D 12) | `VKD3D_DEBUG` | 6-stop: none / err / info / fixme / warn / trace | `warn` | The Direct3D 12 translator's own messages. |
| vkd3d shader translation | `VKD3D_SHADER_DEBUG` | same 6 stops | `warn` | Shader compilation failures, which the row above does not carry. |
| FEX messages | `FEX_SILENTLOG` inverted | boolean | on | Whether the x86 translator is allowed to speak; off hides typos in its own configuration. |
| Turnip driver | `TU_DEBUG` | multi-select over a curated subset | `startup` only | Flags for the graphics driver; these are switches, not levels. |
| Driver messages in the log | `MESA_LOG=file` | boolean | **see §3** | Without this the driver's output goes to the Android system log, where Vessel cannot read it. |
| Window tree | app-side, no variable | boolean | off | Dumps the X window layout whenever it changes, for when the taskbar shows the wrong windows. |

The vkd3d ladders must print vkd3d's own words in vkd3d's own order — `info`
before `fixme` before `warn` — and not be silently normalised to DXVK's. Two
adjacent six-stop pickers whose stops read differently is correct here and the
design should not smooth it out.

The Turnip subset should be small and each entry should answer something. From
the 42 in `tu_util.cc:21-61`, the defensible ones are `startup` (always, not
shown), `perf` (why a frame is slow), `sysmem` and `gmem` (force one rendering
path when a title corrupts — but see `ARCHITECTURE.md:186-193`, which argues
`TU_AUTOTUNE_ALGO=prefer_sysmem` is the better control), and `nolrz` /
`noubwc` (turn off an optimisation to see whether it is the cause). The rest are
Mesa-developer flags and belong to the escape hatch, not to a curated list.
**Do not offer any of them until §3 is resolved** — a switch with invisible
output is worse than no switch.

### Tier 2 — the raw escape hatch

One free-text field, `WINEDEBUG` extras, **appended** to the composed string so
a later term wins and the fixed prefix cannot be deleted — the `dllOverrides`
shape (`SessionEnvironment.kt:773-784`). Placeholder `+relay,-heap`, help
`Extra Wine debug terms, appended to Vessel's own. Leave empty unless following
specific advice.` — deliberately the same wording shape as the DLL overrides
field (`params-manifest.json:104`), because it is the same kind of field for the
same kind of user.

Three validations it needs, all from §4:

- Reject the literal `help`, which makes Wine exit(1) before the program starts.
- Warn on a leading `-all`, which erases every term before it.
- Mention `program.exe:+channel` in the help, because scoping a firehose to one
  program is the difference between a usable log and a full disk.

`ParamType.TEXT` exists and its doc explains itself as "the deliberate exception,
not a loophole" (`ParamManifest.kt:72-80`). This is a second instance of the same
exception, in a different schema, and it should carry the same caveat.

### Tier 3 — the dangerous tier

Behind an explicit warning, and **reset to off after one session**.

| Control | Why it is here |
|---|---|
| `+relay` (via the escape hatch or a named row) | "every cross-DLL call — hundreds of MB in seconds" (`LOGGING.md:136-137`). |
| `+seh` | "runs for **every raised exception, handled or not**, emitting a code line, up to 15 `info[]` lines and a full register dump — and C++ and .NET exceptions are SEH" (`LOGGING.md:115-119`). And it buys nothing for crashes: unhandled exceptions go through `MESSAGE()`, unconditionally, with no channel check (`:125-134`). |
| `warn+d3d` and above | 659 unguarded `ERR` sites on per-draw paths (`LOGGING.md:164-170`). |
| `DXVK_LOG_LEVEL=trace` / `debug` | per-draw. |
| `VKD3D_SHADER_DEBUG=trace` | "that is the per-shader firehose" (`LOGGING.md:163`). |
| `+heap`, `+sync`, `+msg` | excluded by name (`LOGGING.md:136-138`). |

**One-session auto-reset.** The diagnostics record stores, per dangerous
control, the `startedAt` of the session it was armed for; when the next session
starts with a different stamp, the control reads as off and is cleared. Storing
the stamp rather than a boolean is what makes it survive the app being killed
mid-session — the same reasoning `SessionLogWriter.kt:66-72` uses for keeping
the exit status out of the channel.

The warning copy must say the three concrete things: the log will hit its cap in
seconds, the session will be much slower, and the setting turns itself off after
one run. Naming the mechanism is what stops it reading as a scary-sounding
dialog people learn to dismiss.

---

## 8. Non-goals, and traps to avoid

**Not a channel picker.** See `LOGGING.md:222-230`, quoted in §7. If a channel
cannot be given a question and a one-line answer, it belongs in the escape
hatch.

**Do not add `+err`, `+warn` or `+fixme`.** They are class names, not channels;
`+err` registers a channel that does not exist and leaves Wine at its defaults.
Winlator ships exactly this bug (`LOGGING.md:45-51`), and
`SessionEnvironmentTest.kt:133-135` asserts against it.

**Do not put a trailing `-all` anywhere**, and do not let the escape hatch's
value be prepended rather than appended. Parsing is left to right and a trailing
`-all` erases everything before it (`LOGGING.md:53-55`, verified at
`debug.c:122`).

**Do not offer `VKD3D_LOG_FILE` or `DXVK_LOG_PATH` as "also write to a file".**
For vkd3d it is not additive — it *moves* the output off the pipe
(`LOGGING.md:56-62`). For DXVK it is additive but the current value `none` is a
measured fix for output landing beside the log instead of in it
(`SessionEnvironment.kt:545-565`).

**Do not reintroduce a severity filter in the viewer.** It was there and was
removed for a stated reason: "It asked the reader to decide which layer had
failed before showing them anything, and the `fixme` that explains a crash is
routinely two hundred lines above it and not a warning at all"
(`SessionLogScreen.kt:70-75`). A Diagnostics screen that turns channels on and a
viewer that filters them back off is two controls fighting.

**Do not assume raising a level makes output appear.** Wine skips parsing
`WINEDEBUG` entirely when fd 2 is the null device (`debug.c`, `init_options`,
and `LOGGING.md:32-43`). Every control on this screen depends on the launcher
handing the process a real pipe, which it does — but a Diagnostics screen is
where that assumption becomes user-visible, so if the pipe is ever absent the
screen should say so rather than offer switches that cannot work.

**Do not draw `FEX_OUTPUTLOG`.** It does nothing on this platform
(`Logging.cpp:36-49`).

---

## 9. Open questions for the designer

Each is a decision with a cost on both sides. None has been made.

1. **One screen or a sheet?** Every short thing in this product is a bottom
   sheet over the container it is about (`Navigation.kt:50-56`), but this is
   fifteen-plus controls in four groups with a warning gate — longer than the
   container sheet, which `DESIGN.md:298-303` already treats as at its limit.
   A push loses the container behind it; a sheet this tall is a screen wearing a
   sheet's chrome.
2. **Does the level ladder read as levels or as checkboxes?** Wine's model is a
   set of class bits, and a five-stop ladder is a simplification that hides
   `err`-without-`warn` combinations. The ladder is easier to use; the checkbox
   set is honest about the model. The ladder is recommended, with the escape
   hatch covering anyone who needs the combination the ladder cannot express.
3. **Where does the raw field live?** Beside the Wine channels, where it belongs
   logically, or at the bottom under the warning, where it is harder to reach by
   accident. It can produce `+relay` either way, which argues for the bottom.
4. **Does a preview of the composed `WINEDEBUG` string belong on screen?** It is
   the single most direct way to make an expert trust the screen, and it is the
   single most direct way to make a non-expert feel they are in the wrong place.
   A collapsed *"what this sends"* row is the middle, and this product does not
   otherwise use disclosure.
5. **Is retention one number or three?** §5 offers a container budget (one
   number, one slider, worst case visible) against per-session caps (three
   numbers, matches the code, worst case is a product). The budget is
   recommended; it is more code.
6. **Do dangerous controls reset after one session or after one *successful*
   session?** A session that crashes in three seconds is exactly the one someone
   wants to re-run with the same flags. Resetting after a crash makes them
   re-arm it every time; not resetting makes "one session" a lie.
7. **What happens on a container that has never been launched?** See §6 — the
   dangerous tier reaches `wineboot`. Refuse, or warn about a slow first launch.
8. **Is there a "copy diagnostics settings to another container"?** Diagnosing
   usually means comparing two containers, and re-arming fifteen controls by
   hand is where people give up. Out of scope for a first pass, but the schema
   should not make it hard.

---

## 10. Documentation the implementation must fix

Found while verifying, all small and all in documents this brief treats as
authoritative:

- **`docs/LOGGING.md:15` is stale.** It gives
  `WINEDEBUG=-all,err+all,warn+module,+winediag,+loaddll` — no `+debugstr` —
  while the code sets it (`SessionEnvironment.kt:18`) and the test asserts it
  (`SessionEnvironmentTest.kt:122`). The document's own §`debugstr` (`:98-113`)
  argues for it. The configuration block was not updated when the channel was
  added.
- **`docs/LOGGING.md:20` is stale.** It gives
  `DXVK_LOG_PATH=<container>/logs  # insurance only`, which is the value that
  was measured to be wrong; the code sets `none`
  (`SessionEnvironment.kt:566`) and the reason is at `:545-565`.
- **`docs/LOGGING.md:212-220`, "Open item: FEX", is answered.**
  `native/fex/Source/Windows/Common/Logging.cpp:36-49` is the whole Windows
  logging init: `SILENTLOG` and nothing else, `__wine_dbg_output` when not
  silent, a `%LOCALAPPDATA%` file when that does not resolve.
- **`docs/DESIGN.md:298-299` counts four manifest controls** — resolution, frame
  rate, a file-manager toggle and DLL overrides. The manifest declares three;
  there is no `display.fileManager` param (`params-manifest.json:31-107`). It
  survives only as a preview fixture (`ContainerSheet.kt:345-352`).

Fixing `LOGGING.md` is part of this work, not adjacent to it: the file says of
itself that it is the source of truth and that code points at it rather than
restating it (`LOGGING.md:5-7`), and `SessionEnvironment.kt:15-16` says "Change
that document first."
