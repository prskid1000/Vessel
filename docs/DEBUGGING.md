# How to debug this stack

Written after a day where five hypotheses died before the real cause, and the
answer turned out to have been sitting in a log for hours.

**This is the single file for debugging material.** The method, every solved
critical bug with the steps that actually found it, the corrections that must
not be re-learned, and the FEX / benchmarking / logging references all live
here. `docs/TODO.md` is the task list and nothing else.

## When a blocker is solved, it comes here

Closing an entry in `docs/TODO.md` means writing it up in this file first, in
[Solved, and how each was found](#solved-and-how-each-was-found), with four
things:

1. **The symptom**, as it presented -- not as it was later understood.
2. **The cause**, stated as a mechanism precise enough to predict the symptom.
3. **The fix**, by patch name, and why that fix and not a narrower or broader
   one.
4. **How it was found** -- the instruments in order, *including the ones that
   led nowhere*, and which observation was decisive.

The fourth is the one that keeps getting skipped and the only one that
transfers. A fix is worth one bug; a method is worth the next one. Record the
dead ends by name, because the cost of this project's failures has been
re-walking them, not missing them the first time.

## The failure mode this project actually has

Not wrong code. **Confident wrong conclusions**, produced by an instrument that
was not running.

Four separate mechanisms have each ended in a session that ran an old build
while being read as evidence about a new one: a version-code collision in the
build scripts, a file-length staging check, a container reference that was
never re-pointed, and adoption racing the install. All four are fixed. A fifth
will exist.

The cost is always the same shape: a fix is applied, the symptom does not
change, and the fix is blamed. **A measurement that is confidently wrong is
worse than a broken build**, because a broken build announces itself.

## Verify the artifact, never the exit code

The single highest-value habit here. A build that exits 0 says nothing about
whether your change is in the thing you are about to ship.

```sh
# Wine patch landed?
strings .../aarch64-windows/ntdll.dll | grep -c 'futexwho'
# FEX patch landed? (0005 REMOVED a string, so absence is the proof)
strings libarm64ecfex.dll | grep -c 'invalid JSON format'
# App change survived R8?
python -c "import zipfile; ..."   # count the string in classes.dex
```

Write the check **before** the build, and state what result would mean failure.
Twice in one day a build reported success while the change was absent — once
because a patch had been generated against the wrong baseline, once because
GnuTLS failed to link inside a script that still exited 0. Both were caught by a
check written in advance. Neither would have been caught by reading the log.

The same rule on device: confirm the *container* is running the version you
think it is, from `provisioned.json` and `/proc/<pid>/environ`, not from the
store listing.

## Read the log you already have before adding a new one

The Streamline deadlock was named by a line that had been in the log for hours:

```
RtlpWaitForCriticalSection section 00000000388A7E28 "?"
  wait timed out in thread 0184, blocked by 021c
```

`0x0184` is 388 and `0x021c` is 540 in decimal — and the lines immediately above
were `[streamline][error][tid:388]` and `[tid:540]`. Same two threads. Wine had
named the culprit; the ids were just in a different base than the middleware
printed.

**Convert every identifier into every base and unit before concluding you need
more instrumentation.** Hex thread ids, decimal tids, page-aligned addresses,
version codes.

## The line you have already decided is noise

The section above is about a log nobody read. This one is worse, because the log
*was* read.

Requiem's crash was chased for days through a stale `ImageBase`, an ARM64EC
unwind theory, an ARM64X relocation theory and a `memcpy` overrun. All of that
time this sat in the error census, third by count:

```
12  vkd3d-proton:d3d12_pipeline_state_validate_blend_state: Enabling blending on
    RT 5 with format DXGI_FORMAT_R32G32B32A32_UINT, but using integer format is
    not supported.
```

It was printed at **ERR**, it appeared in the digest, and it was in output that
had already been read aloud — and it was skipped every time, because the theory
under investigation was about memory and this line was about pipelines. It is
`return E_INVALIDARG` from `CreateGraphicsPipelineState`: thirteen pipelines the
game asked for and did not get, which is why it stopped moving right after
compiling shaders.

**A hypothesis makes some evidence invisible, and the evidence it hides is
exactly the evidence that would kill it.** Two habits are cheap enough to be
worth doing every time:

- **Census the errors before forming the hypothesis, and read every distinct
  line once, out loud, including the ones from a layer you are not thinking
  about.** `grep '^E' | sed 's/0x[0-9a-f]*/A/g' | sort | uniq -c | sort -rn` is
  the whole technique. Distinct-and-rare matters more than frequent.
- **Say what each error would mean if it were the cause**, before deciding it is
  not. "vkd3d refused a pipeline" survives that question in one sentence. The
  ones that do not survive it are the ones safe to set aside.

The instrument was never missing here. The attention was.

## A correlation is not a mechanism

The section above is still the right lesson, and the answer it produced was
still wrong. Worth keeping both facts next to each other.

The thread ids really did match: Wine's blocked pair and Streamline's two
complaining threads were the same two threads. What did not follow was the fix.
Streamline was disabled for every title with an empty `WINEDLLOVERRIDES` entry,
on the reasoning that a title would then "take the path it takes on any machine
without Streamline".

That sentence is where it went wrong, and the flaw is only visible once it is
stated plainly: on a non-NVIDIA PC `sl.interposer.dll` **is present and loads
fine**, and merely reports no NVIDIA features. An empty override makes
`LoadLibrary` *fail* — a situation that happens on no real machine, and one
Resident Evil Requiem does not handle. The log then reads
`Failed to load module L"sl.interposer.dll"; status=c0000135`, twice, followed
by `CrashReport.exe`. A deadlock had been traded for a crash and counted as a
fix.

Allowing Streamline to load again took the title **further than it had ever
got**, past `dstorage`, `steam_api64` and `voices38` — with no `sl.*` load in
the trace and no critical-section timeout at all.

Two habits come out of this:

- **Say what the fix makes the machine look like, not what you want it to
  achieve.** "Disable Streamline" hid the actual change, which was "make a DLL
  the game ships with fail to load". The second phrasing is obviously wrong; the
  first is not.
- **A fix that changes the symptom is not a fix.** Deadlock became crash, and
  the new symptom was read as new progress. The question to ask of any fix is
  whether the thing got *further*, and progress needs a marker chosen in
  advance — here, which DLLs load, not whether it still fails.

## Prefer an instrument that can return a negative

`patches/fex/0006` counted RWX invalidations and reported **zero**. That killed a
hypothesis in one run, which was worth more than a positive would have been —
a positive only confirms what you already suspected.

Ask of any new trace: *what result would rule this out?* If there isn't one,
it is not an instrument, it is a confirmation.

## A trace that destroys evidence is worse than no trace

`sync` at `EVERYTHING` produced 4,802,393 lines, dropped 2,841,303, and among the
casualties were **47 errors and 40 warnings** — the only lines that could explain
the failure. The whole 16,289-line tail was trace spam plus one rate-limit
notice.

The limiter now gives each severity its own budget (`SessionLogWriter.emit`), so
this specific loss cannot recur. The general lesson stands: before enabling a
firehose, ask what it will push out.

## Correct the record in the file, not just in conversation

Two comments written during the same day were wrong — one claimed component
adoption was digest-keyed when it compares version codes, one told users to
create a new container to pick up a build that adoption now takes
automatically. Both were corrected explicitly, in place, saying what was wrong
and why.

`docs/TODO.md` also claimed `FEX_SILENTLOG=0` makes an early assert readable. It
cannot: the die is at `Module.cpp:657` and `Logging::Init()` is line 659. A
wrong claim in a document costs a run every time someone believes it.

## Suspect your own layer last, not first

Four of the five dead hypotheses blamed FEX. FEX is the component Winlator also
uses, on the same device, with the same driver — and it runs fine there. The
comparison was available the whole time.

When another runtime works on the same hardware, **diff the runtimes before
instrumenting yours**. What differs is a short list; what you wrote is a long
one.

## Read what the shader assumes about the device

**The longest-running bug in this project was in the application, and sixteen
hypotheses about the driver died first.**

`#56` -- half a room's geometry missing, deterministically, with no error from
any layer -- was RE Engine's culling shaders reading `WaveGetLaneCount()` and
clamping it to their 32-thread group in three places out of five. On a device
reporting 64 the unclamped reads stride twice as far as the lanes that exist,
so every second cluster is never processed. The driver was correct throughout.

What made it invisible is worth naming, because the same properties will
reappear:

- **It is arithmetic, so it is perfectly deterministic.** That reads like a
  driver bug -- races are not this stable -- and it was used as evidence for
  one.
- **Nothing failed.** No validation error, no `VK_ERROR`, no FIXME. The shader
  ran and did exactly what it was told, and what it was told was wrong. The
  audio bug of 2026-08-16 has the identical shape: a WASAPI mix format that
  was float32 while the code said int16, with every layer reporting success.
- **The damage was partial**, which invites the theory that something is
  corrupting *some* state. It was partial because the 128-thread variants of
  the same shaders clamp correctly and only the 32-thread ones are wrong.
- **It works on desktop.** NVIDIA reports a 32-wide wave and RADV picks wave32
  for small compute groups, so the assumption holds there and nowhere in the
  title's own testing would it have shown.

So: **when a title misbehaves on one device and not another, read what the
shader assumes about the device before deciding the device is wrong.** The
assumptions worth checking first are the ones a desktop GPU makes true by
accident -- wave width, subgroup size, workgroup packing, and anything derived
from them.

### The disassembly said so hours before anyone understood it

This is the part to actually learn from. The shaders were dumped with
`VKD3D_SHADER_DUMP_PATH`, disassembled with `spirv-dis`, and the output
contained, side by side:

    %364 = OpExtInst %uint %320 UMin %uint_32 %363   ; clamped
    %403 = OpIMul  %uint %402 %401                   ; raw, unclamped

That asymmetry was printed, read, written down as "exactly where a wave-size
assumption could break" -- and then not followed, because a compiler warning
elsewhere in the same session looked more like a lead. The warning was noise.

**A noticed anomaly that is not chased is worse than one never seen**, because
it is spent: nobody looks again. When something reads as odd enough to write
down, resolve it to a yes or a no before moving to the next hypothesis.

### The instruments that found it, in the order they were used

Recorded because half of them contributed nothing and it was not obvious in
advance which half.

| # | Instrument | What it gave |
|---|---|---|
| 1 | `VKD3D_SHADER_DEBUG=warn` | five `no candidate for ladder merging` warnings. **The lead was false** -- the restructurer's output is valid and faithful -- but it named five shaders out of thousands, which is the only reason the next step was tractable |
| 2 | `VKD3D_SHADER_DUMP_PATH` | every translated shader written out as `<hash>.spv` / `.dxil`. Forces `pipeline_library_ignore_spirv`, so the run is a full rebuild and slow |
| 3 | pairing 1 and 2 by thread id | the five hashes. The warning does not name a shader; the dump does not say which warned. Both in one fresh-compile session is what joins them |
| 4 | `spirv-dis` (`spirv-tools` in the build image) | the disassembly, where the unclamped `SubgroupSize` read sits four lines from the clamped one |
| 5 | `VKD3D_SHADER_OVERRIDE` | **the decisive one.** Replacing the five with rewritten modules changed the picture, which proved they execute in that scene. Nothing else could establish relevance rather than correlation |
| 6 | `strings` on the `.dxil` | the entry point name `PersistentClusterCulling`, which is what connects this title to the one upstream already fixed |
| 7 | `freedreno_devices.py` / `tu_device.cc` | `threadsize_base = 64`, unoverridden -- the other half of the arithmetic |
| 8 | `grep` in `application_shader_quirks[]` | PRAGMATA's entry, the fix, and the comment describing the identical mistake |
| 9 | the `components:` session line | that the built driver was the one that ran. Added *because* of this bug; three separate times it was answered by pulling `provisioned.json` off the device by hand |

Two things generalise from that table.

**A false lead that narrows the search is still worth having.** The ladder
warning was noise about the actual defect and indispensable anyway: it cut
thousands of shaders to five. Do not discard an instrument because its theory
turned out wrong -- ask what it selected.

**Relevance and correctness are separate questions, and relevance comes
first.** For weeks the entry had a list of suspects and no way to ask "does
this even run in the broken scene?". `VKD3D_SHADER_OVERRIDE` answers exactly
that, in one run, by substitution -- and the answer reframed everything that
followed. Reach for a substitution instrument before another observation one.

### Check whether upstream already fixed it for a sibling title

vkd3d-proton had shipped the fix three months earlier, for PRAGMATA, under the
comment *"These shaders clamp the wave size to 32, but misses this in a few
places of course ..."*. PRAGMATA is the same engine from the same studio, and
the entry point name -- `PersistentClusterCulling` -- is byte-identical in
both. Grepping the vendored tree for the symptom's vocabulary (`wave`,
`subgroup`, `WaveGetLaneCount`) in `application_shader_quirks[]` would have
found it in a minute.

**A per-title workaround table is a list of bugs other people have already
diagnosed.** Read it before instrumenting anything.

## Tools that work here, and one that does not

- `WINEDEBUG=+seh,+unwind` gives a clean ARM64 unwind and named the FEX config
  assert without FEX's own log. This is the technique with a track record.
- `uiautomator dump` plus `input tap` drives the phone headlessly — resolve the
  tile by label each time rather than hardcoding coordinates.
- `adb exec-out`, never `adb shell`, for binaries. Shell line-ending translation
  corrupts them and `llvm-objdump` then fails with an unrelated CHPE error.
- `winedbg` **attaches but `bt all` never unwinds** on ARM64EC. It prints
  `WineDbg attached to pid ...` and hangs. Do not spend time there.
- Git Bash mangles inline `python -c` and eats backslashes in quoted heredocs.
  Write the script to a file and run it. This has cost time three times.
- `MSYS_NO_PATHCONV=1` before any `adb push`/`shell` with an absolute device
  path, or Git Bash rewrites `/data/local/tmp` into a Windows path.

## Generating a patch for a vendored tree

`git diff` against HEAD sweeps in **every other patch's** changes to the same
file, because patches are applied to the working tree and never committed. A
`patches/fex/0006` generated that way carried `0002`'s hunks and failed to
apply.

Reset the tree, apply the earlier patches in order, `git add -A`, then make your
edit and diff. The result should be only your change — check the line count.

---

# Solved, and how each was found

The account of each closed blocker: what it looked like, what it actually was,
and the route between the two.

## #56 — the world is not drawn

*Fixed 2026-08-19. An application bug, not a driver one: the shaders ask for
the wave size and are told twice what they expect.*

**Symptom.** Standing inside a house
basement, the room's own geometry was missing while the city outside showed
through where the walls should be. Interactive, no fault, no error logged by
any layer, deterministic to the object.

**The cause.** RE Engine's cluster-culling compute shaders read
`WaveGetLaneCount()` six times. Three reads go through `UMin(32,
SubgroupSize)`. The load-bearing ones do not:

    base    = waveSize * groupIdx
    offset  = base + WavePrefixCountBits(true)   // lane rank, 0..31
    stride += waveSize
    groups  = ceil(count / waveSize)

The shader means wave size to equal its threadgroup, 32. Turnip reports 64 --
`threadsize_base` defaults to 64 (`freedreno_dev_info.py:108`) and neither
`a7xx_base` nor `a8xx_base` overrides it. Lane ranks then cover 0..31 of a
window advanced by 64, and `ceil(count / 64)` dispatches half the groups.
**Exactly every second item is never processed.**

**Why it looked like a driver bug for weeks, and was not.** Every property
that made this hard to find falls out of that one line of arithmetic:

| observation | why |
|---|---|
| perfectly deterministic | it is arithmetic, not a race |
| collision holds against invisible walls | the CPU never sees it; only the GPU's output list is short |
| not one error in any layer | nothing failed — the shader ran and did what it was told |
| only *some* geometry missing | the `LocalSize 128` shaders in the same set clamp to 128 and are correct |
| fine on desktop | NVIDIA reports 32; RADV picks wave32 for small compute groups |

**The fix is `patches/vkd3d/0008`**, and upstream had already written it for
the same engine. `device.c` carries `pragmata_hashes` under the comment
"These shaders clamp the wave size to 32, but misses this in a few places of
course ..." — PRAGMATA is Capcom RE Engine too. Requiem simply had no entry.
Applied game-wide rather than by hash: the quirk gates itself on the
entry point name, not the executable: all five shaders dumped from Requiem
carry `PersistentClusterCulling`, byte-identical to the name PRAGMATA already
matches, because it is RE Engine's own culling pass rather than anything
per-game. One entry therefore covers both titles and any later one shipping
the pass unchanged. Confirmed on device — vkd3d `3000116`, `Detected game
re9.exe, adding shader quirks`, and the room has walls.

**What this entry cost, and the one lesson worth keeping.** Six theories died
before it: LRZ, UBWC, tiling, depth compression, the upscalers, the present
path, placed-versus-committed resources, `instruction_qa_checks`,
`descriptor_qa_checks`, `skip_application_workarounds`, the whole upstream
stack being old, an RE Engine 16-bit quirk, a compute-barrier quirk, 64-bit
image atomics, a GPU capture route that produced 1.5 GB and zero command
streams, and finally a dxil-spirv ladder-merging warning that turned out to
be noise. Every one of them was a driver hypothesis. **The answer was in the
shader, and it had been printed on screen hours before it was understood** —
`UMin(32, SubgroupSize)` beside a raw `OpLoad %SubgroupSize` in the same
disassembly, noted as "exactly where a wave-size assumption could break" and
then not followed. When a title misbehaves on one device and not another,
read what the shader assumes about the device before deciding the device is
wrong.

**How it was found.** The instruments, in order and including the ones that
led nowhere, are tabulated under [The instruments that found it, in the order
they were used](#the-instruments-that-found-it-in-the-order-they-were-used).
The decisive one was `VKD3D_SHADER_OVERRIDE`: substituting the five shaders
changed the picture, which established that they run in the broken scene at
all. Every earlier hypothesis had died for want of exactly that.

Three driver changes were made chasing it and two of them stay on merit:
`patches/mesa/0009` prints the branchstack when it exceeds the cap, because
nothing in Mesa ever printed it; `patches/vkd3d/0008` is the fix.
`patches/mesa/0010` raised the cap and is a measured no-op here — it was kept
only until the measurement existed, and the measurement now says remove it.

## Resolved, in brief

Kept with a one-line outcome and the reasoning that would otherwise be
re-derived. Everything procedural about these has been dropped.

- [-] **#39 — fsync. Closed won't-do: it cannot work on Android.** `WINEFSYNC=1`
  makes `fsync_check_support` (`server/fsync.c:58`) probe `futex_waitv`, arm64
  syscall 449. Android's seccomp policy does not allow it — bionic's
  `SECCOMP_ALLOWLIST_COMMON.TXT` carries `futex` and `futex_time64` and no
  `futex_waitv` — and an out-of-allowlist syscall gets `SECCOMP_RET_TRAP`, so
  the probe does not return an error, it kills wineserver. Measured twice, the
  second on a clean install with nothing else changed: `wineboot --init` left a
  0-byte `system.reg` against a fresh prefix's ~97 KB, and the client logged
  `recvmsg: Connection reset by peer` with **no Wine error at all** — the
  absence is the fingerprint, because a killed server logs nothing.

  Also settled on the way: **esync does not exist in this Wine.** No
  `server/esync.c`, no `dlls/ntdll/unix/esync.c`, `WINEESYNC` in zero source
  files — Valve's `experimental_11.0` dropped it. So `WINEESYNC=1` had been
  inert, and `docs/OPTIMIZATION.md`'s "esync is the best available" describes a
  mechanism this build does not contain. Both variables are now unset and both
  stay reserved, so a manifest cannot reach them.

  `server/inproc_sync.c:50-65` therefore resolves to its last rung — server-side
  synchronisation — and that is the only one of the three that can run here, not
  a default nobody revisited. Reopens only if the sandbox ever permits the
  syscall. **ntsync needs no variable and is already compiled in**, so a 6.14+
  kernel would select it at runtime; whether the vendored 2021-2022
  `server/ntsync_tmp.h` still matches the ABI upstreamed in 6.14 is unverified.

- [x] **#43 — the FEX config assert.** `patches/fex/0005`. Closed 2026-08-16 by
  reading the shipped binary rather than by running a session, and the
  distinction is the point: the assert only fires on a config that *fails* to
  parse, so a thousand successful launches never exercise it and were never
  evidence. `ERROR_AND_DIE_FMT("Failed to parse JSON from file '{}' - invalid
  JSON format", …)` is gone from `Config.cpp:43`, and neither literal survives in
  `dist/fex-2608-canoe.wcp`'s `libarm64ecfex.dll` or `libwow64fex.dll` — with
  `FEXCore` present in both as a positive control that the search works on those
  files. What replaces it is an early `return` before any state is touched, so
  "an unreadable override leaves the defaults standing" is true by construction
  rather than by observation.
- [x] **#54 — the session ending when the X server went away.** Closed as a
  blocker: it was always downstream of #55's device loss, and no session has died
  on its own since. One real, independent bug was found under it and is the only
  part worth keeping — `xconnector_epoll.c` had no `EINTR` handling anywhere, and
  `signal(7)` lists `poll`, `epoll_wait` and `select` as never restarted after a
  signal *regardless of `SA_RESTART`*. ART installs handlers on this process, so
  `epoll_wait` returning `-1` fell through to `break` and `killAllConnections()`,
  dropping **every** X client at once. Both paths retry now (`:179`, `:327`),
  `c8b4b30`. If it ever recurs, `logcat -s VesselXServer` rules this mechanism in
  or out in one run.
- [x] **#51 — Requiem renders, at 56 fps.** `patches/wine/0046`.
- [x] **A relocated ntdll kept a header naming where it used to be.** `patches/wine/0044`.
- [x] **#50 — a stack overflow that was never a recursion.** `patches/wine/0041`.
- [x] **#49 — lock-order inversion between Wine's process heap and FEX's code invalidation.** `patches/fex/0019`, `patches/wine/0042`.
- [x] **#53 — `0030`'s second zero was eviction, not the frame walk.**
- [x] **The FEX code cache works end to end.** `patches/fex/0017`, `0018`, `0019`.
- [x] **Requiem's cache was malformed at generation.** `patches/fex/0017`.
- [x] **The six revision-9/10 Wine patches were never separated, and no longer need to be.**
- [x] **"Written, committed, never compiled" is closed as a section.**
---

---

## Corrections that must not be re-learned

Conclusions this file previously recorded as true, each of which cost a build or
a device session. They are here in the form that is useful — what is actually the
case, and where to look.

1. **An EDID cannot be seeded into the registry.** `prepare_devices()`
   (`native/wine/dlls/win32u/sysparams.c:911`) calls
   `reg_empty_key( enum_key, "DISPLAY", FALSE )` at `:928` on *every* display
   initialisation, so anything written by hand under `Enum\DISPLAY` is deleted
   before Wine repopulates the key. This was tried, shipped, and measured to
   vanish. The EDID has to be synthesized inside Wine, and in **win32u's
   `add_monitor` (`:2201`)** rather than in winex11's `display.c` — `add_monitor`
   is the single funnel that winex11's enumeration, the no-driver fallback in
   `default_update_display_devices`, and `add_virtual_source` all reach. Supplied
   from winex11 it lands on the physical monitor, which is the one the guest never
   draws into and DXVK never reads.

2. **Two monitors was never the bug; the primary flag was.** The guest draws into
   the virtual desktop while DXVK was reading the physical monitor, because
   upstream leaves the primary flag wherever the display driver put it and winex11
   always names a primary. Size, DPI and fullscreen placement all follow the
   primary device, so this was not cosmetic. `0036` moves the flag to the virtual
   source and keeps upstream's renumbering, which leaves `DISPLAY1` and the
   primary device as the same display again.

3. **Seeding `winebth`'s `Start=4` is a no-op on its own, and guarding the PnP
   path did not fix it either.** The value was verified present in the hive and the
   driver still loaded and still failed with `c00000e5`. `patches/wine/0031`
   guarded `load_function_driver` in `pnp.c`; the load was actually coming through
   winedevice's `device_handler` on `SERVICE_CONTROL_START`. `patches/wine/0034`
   puts the check in `open_driver` in `ntoskrnl.c`, the one place both callers
   meet. 0031 stays: it is insufficient, not wrong. The general shape — *a patch
   that honours a value nothing sets is as inert as a value nothing reads* — is
   the part worth carrying.

4. **`RTL_CRITICAL_SECTION_DEBUG.Spare` has exactly one element on 64-bit.** See
   #49 above. `Spare[1]` does not exist and writing it corrupts memory.

5. **The `.winmd` files Wine generates are genuinely malformed, and the loader
   reacts worse than the warning suggests.** `SizeOfImage` described the file
   rounded up to `0x2000` while the single section sits at `VirtualAddress 0x1000`
   and ends at `0x3000`. `map_image_into_view` sanity-checks each section against
   `SizeOfImage` and on overflow does **`goto done`** (`virtual.c:3378-3380`) — it
   abandons the section loop rather than skipping the offending section, so for a
   metadata-only file whose one section *is* the metadata, nothing beyond the
   headers is ever mapped. It does not "skip and carry on". `patches/wine/0038`
   derives `SizeOfImage` from the section header that was just laid out.

6. **The X server death is not memory pressure, and it was attributed to memory
   pressure twice.** The first time from ActivityManager `cch+95 CEM` lines, which
   are Android trimming cached background processes as it always does. The second
   time from RSS scaling with commit granularity while the system sat at 11-12 GB
   of 15.2. Both are plausible and neither is evidence. The measurement that
   settles it, taken on 2026-08-15 with a device-side sampler and a live
   `logcat` at the moment `re9.exe` died: 3.3 GB MemAvailable, 8.6 GB SwapFree,
   RSS flat-to-falling over the last 15 seconds (5.48 → 5.46 GB), no `lmkd`, no
   tombstone or `SIGSEGV`/`SIGABRT`/ANR, an orderly ~4 s teardown, and
   `app.vessel` alive with the same pid afterwards.

   Two corrections fall out of the same measurement and are the reason it is
   kept after #54 was closed. **`exit code 1` is not the game's**:
   `SessionRuntime` waits on `explorer /desktop=`, not on `re9.exe`, and
   libX11's `_XIOError` calls `exit(1)`, so *every* X client exits 1 the moment
   the connection drops. **And the game did not fault** — 3,942 lines contain no
   `err:seh:`, no unhandled exception, no `CrashReport.exe`, and per
   `TraceSpec.kt`'s `exceptions` topic an unhandled exception prints with no
   channel enabled, so that absence is proof at full strength rather than an
   argument from silence.

7. **`LookupCache::InvalidateRange` does not invalidate a page's worth of
   translations for a data write.** It is page-keyed and iterates only pages that
   have entries (`LookupCache.h:125`), so a page holding no translated code is a
   no-op. The SMC-thrash cost is per-event lock acquisition and page re-arming,
   not mass invalidation. See the live entry above.

8. **DXVK does not sum the device-local heaps for `DedicatedVideoMemory`.** It
   takes the largest (`dxgi_adapter.cpp:448`). On a single-heap UMA part the
   reported figure is the same, so #52's conclusion survives, but a reader looking
   for the sum will not find it.

9. **A path-valued environment variable is not automatically unreachable.**
   Reachability is decided by `RESERVED_SESSION_ENV` (`SessionEnvironment.kt:347`)
   and nothing else; `VKD3D_SHADER_DUMP_PATH` and `VKD3D_SHADER_OVERRIDE` are not
   in it and go through the free-text table today. What `VKD3D_CONFIG` needed was
   *list composition* — `breadcrumbs` had to join `nodxr` rather than replace it —
   which is a different problem with a different fix.

10. **`stack 0x20980` was never evidence of a small stack, and three Wine
    revisions were spent believing it was.** The address is where a thread dies,
    not how much room it had, and the range printed beside it says so. Six VS
    Code Insiders sessions span revisions 41–44 and all four print the same
    `(0x20000-0x21000-0x820000)` — 8 MB, including rev 41, which predates the
    8 MB thread-stack floor (`0053`, rev 42), the CHPE emulator-stack raise
    (`0053` again, rev 43) and the kernel/WoW64 raise (`0054`, rev 44) alike.
    None of those three moved it. The 8 MB is the app's own: `Code -
    Insiders.exe` carries `SizeOfStackReserve 0x800000` in its PE header, the
    figure Chrome, Edge and Firefox all ship with, so the thread already had
    exactly what Windows gives it.

    The fault address is equally not a culprit. `0x6fffef9c34` is
    `prepare_exception_arm64ec` entry **plus four** — its prologue, confirmed
    against the shipped `ntdll.dll` by resolving the only ADRP/ADD pair in
    `.text` that materialises `0040`'s format string. A delivery was already
    under way and could not allocate its 3280 bytes; it is the last frame, never
    the one that spent the stack. `0040`'s own counter, which fires between
    256 KB and 16 KB remaining, printed nothing in any of the six sessions —
    so exception delivery did not spend it either.

    Raising a floor cannot answer a stack that is spent. `patches/wine/0055` adds
    the two readings that can: one line per megabyte descended with the address
    that grew it, and a Misra-Gries pass over the residue at death whose stride
    between repeated return addresses gives frames × bytes-per-frame directly.

---

---

# Reference

## FEX, and which assert can actually fire

Kept because it is expensive to rediscover and three of its four points correct an
earlier version of themselves.

**There are two `ERROR_AND_DIE` sites in `Source/Windows/`** —
`"Couldn't detect CPU features"` (`Common/CPUFeatures.cpp:62`) and
`"Unhandled relocation"` (`Common/ImageTracker.cpp:107`) — **and naming a site by
elimination between them was never available.** `libarm64ecfex.dll` also links
FEXCore and `Source/Common`, which add about forty more: 7 in
`Interface/Core/CodeCache.cpp`, 10 in `JIT/JITClass.h`, and the rest scattered.

**One of the two Windows sites cannot fire in a session.**
`"Unhandled relocation"` lives in `LoadImageRelocations`, which `HandleImageMap`
calls only `if (IsGeneratingCache)` (`ImageTracker.cpp:135`), and both session
modules construct the tracker as `ImageTracker.emplace(*CTX, false)`
(`ARM64EC/Module.cpp:615`, `WOW64/Module.cpp:547`). Only `FEXOfflineCompiler`
passes `true`.

**The candidate set is the `ERROR_AND_DIE_FMT` sites and nothing else.**
`LOGMAN_MSG_A_FMT` and `LOGMAN_THROW_A_FMT` also end in `ForcedAssert`, but only
under `ASSERTIONS_ENABLED`, which `CMakeLists.txt:189` sets for `DEBUG` builds and
`build/fex.sh:130` builds `Release`. They compile to nothing here.

**`FEX_SILENTLOG=0` works for everything after `ProcessInit` and cannot show an
assert that fires inside it.** `SilentLog` defaults to *true*
(`FEXCore/Source/Interface/Config/Config.json.in:387`), and when it is true
`FEX::Windows::Logging::Init` returns before installing a log handler, so messages
are discarded rather than written anywhere. With it false the handler writes
through `__wine_dbg_output` — the **guest's own stderr**, i.e. `tmp/guest.out`,
and *not* a `WINEDEBUG` channel log; the line to grep for begins `A `, the
single-letter level for `ASSERT`. But `ARM64EC/Module.cpp` calls
`FEX::Config::LoadConfig` before `Logging::Init()`, so a die inside config loading
precedes any handler by two lines and no amount of `SILENTLOG` will show it.

**What did work was `WINEDEBUG=+seh,+unwind`, and the reason is worth keeping.**
`+seh`'s register dump comes from `TRACE_CONTEXT`, and in an ARM64EC ntdll the
`__x86_64__` variant is selected — it prints the AMD64-named subset only, and the
link register is aliased onto `FloatRegisters[0]`, which that macro never prints.
`RtlVirtualUnwind2` opens with a `TRACE` naming base, pc and rva; `ForcedAssert`
is `naked` with no unwind data, so it takes the leaf path (`context->Pc =
context->Lr`) and the *second* of those lines carries the caller's base and rva
directly, with no arithmetic. `llvm-objdump` on the shipped `libarm64ecfex.dll`
then names the symbol. That is the sequence that produced
`ProcessInit → LoadConfig → AppLoader::AppLoader → Load → LoadJSonConfig →
ForcedAssert`, 5/5 reproducible.

*Two device facts from 2026-08-10 that are still the last word on the old "large
PEs assert" theory:* it did not reproduce from the command line in the app's own
container, at any of three environments, so the discriminator is neither the image
nor the guest's `FEX_*` environment. A negative from a hand-run is weaker than the
positive it contradicts, so this is not closed — the next attempt has to be a real
session launch.

---

## What can and cannot be benchmarked here

- [ ] **DXVK/vkd3d draw throughput, and the shader cache cold against warm.**
  `docs/OPTIMIZATION.md` §1 calls recompiling already-compiled shaders the single
  largest avoidable cost in the stack; the caches were fixed and the fix was
  explicitly left unmeasured. **`tools/device-bench.sh` refuses this on purpose and
  the refusal at `:211-233` is the current, correct one**: `presentbench.c` draws
  one `ClearRenderTargetView` and no shaders by design, so wiping `caches/dxvk`
  around it times DXVK's internal blit pipeline rather than an application's
  shader set — a stable, meaningless, reproducible number.

  What is needed is a shader-heavy workload: a probe that compiles many distinct
  pipelines, or — cheaper, and needing no new code — a real title timed
  launch-to-first-frame with `caches/dxvk` wiped between runs. *Done when:* two
  launch-to-first-frame times, cold and warm, from the same title. **This is now
  worth more than it was**, because #42's verification needs a two-run cold/warm
  protocol anyway and the two can share a session.

- **Present-path micro-optimisations are below the noise floor of the harness.**
  The run-to-run spread of `run-x11present.sh` inside a single build is wider than
  the effect of any of the three present patches that were A/B'd against it. Do not
  spend a device session on a change worth ~0.15 ms until a harness exists whose
  own variance is under ~5%; present costs ~0.5 ms on the DRI3 path against frame
  times measured in tens of milliseconds.

---

## The logging vocabularies

The diagnostics screen deliberately does not smooth these together, and neither
should anything else that displays them.

**vkd3d keeps its own words in its own order** — `none, err, info, fixme, warn,
trace` (`native/vkd3d/libs/vkd3d-common/debug.c:38-45`), where `info` is *less*
verbose than `fixme` and `warn`. **DXVK's ladder is different** — `trace, debug,
info, warn, error, none` (`native/dxvk/src/util/log/log.cpp:145-152`), with the
same six positions meaning different things.

Two subsystems with different vocabularies drawn as one control is a screen that
lies about what it sets. `ContainerDiagnosticsTest`'s *DXVK and vkd3d keep their
own words in their own order* asserts that they stay apart.

**The default is now vkd3d's own**, and the arithmetic is why: `debug.c:96-97`
defaults an unset channel to `FIXME` (4) and our two loudest messages are `WARN`
(5) in a ladder where `warn` is *above* `fixme`, so shipping `warn` made us
strictly louder than upstream for no information. Moving both constants to
`fixme` cut default log volume ~62%.

One more property of the Wine parser, read out of `dlls/ntdll/unix/debug.c` rather
than assumed: a channel is created with `flags = (default_flags & ~clear) | set`
as of that moment, and a later token ORs into the existing entry. So after
`-all,err+all` a "stubs" stop must emit `warn+x,fixme+x` and not `fixme+x` — one
term gives ERR|FIXME and silently skips WARN.
