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
