# How to debug this stack

Written after a day where five hypotheses died before the real cause, and the
answer turned out to have been sitting in a log for hours. The findings are in
`docs/TODO.md` and the task list; this is the method, which transferred better
than any of the individual fixes.

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
