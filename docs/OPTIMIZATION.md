# Optimization

Every place this stack leaves performance on the table, what it would cost to
take it, and — where a number exists — what it actually bought.

The rule for this document is the project's rule everywhere else: **a claim
without a measurement is marked as unmeasured.** Build flags that "should" help
are the easiest thing in systems work to be confidently wrong about, and an
optimization that was never benchmarked is a guess wearing a commit message.

Measure with `tools/device-bench.sh`, which exists so that before/after here
means the same thing twice.

---

## The shape of the problem

A Windows program on this phone passes through four layers, and they do not cost
the same:

| Layer | What it is | Where the time goes |
|---|---|---|
| FEX | x86-64/x86-32 → ARM64 translation | Only for non-native code. Free for ARM64EC. |
| Wine | Win32 → POSIX | Syscall-shaped. Startup-heavy, then cheap. |
| DXVK / vkd3d | D3D → Vulkan | Shader compilation, then draw submission. |
| Turnip | Vulkan → Adreno | The actual GPU work. |

The single largest avoidable cost is **not** any of these steady-state paths. It
is recompiling shaders that were already compiled — see §1.

---

## 1. Shader caches that pointed at nothing — **fixed**

`sessionEnvironment` sets all three cache paths:

```
MESA_SHADER_CACHE_DIR    files/containers/<id>/caches/mesa
DXVK_STATE_CACHE_PATH    files/containers/<id>/caches/dxvk
VKD3D_SHADER_CACHE_PATH  files/containers/<id>/caches/vkd3d
```

None of those directories existed. `ContainerLayout.createDirectories()` made
`base`, `prefix`, `tmp` and `logs` and stopped there, and the environment
function is pure — it can name a path but cannot create one. Confirmed on the
device: a fully provisioned container had no `caches/` at all.

Mesa builds its own tree and survived. DXVK opens its state cache as a *file*
and does not. So every pipeline was being recompiled on every launch, which on a
phone is the largest avoidable cost in the whole stack.

Fixed in two places, because one was not enough: the directories are now part of
`ContainerLayout`, **and** the session creates them at launch — provisioning
skips the entire layout step for a container whose directories already exist, so
every container made before this change would otherwise never get them.

`MESA_SHADER_CACHE_DISABLE=false` was already set, and that is not redundant:
Mesa disables its own cache by default when it detects a translation layer, so
under Wine the default is *off*.

> **Unmeasured.** Cold-vs-warm launch is exactly what `device-bench.sh` measures;
> run it before quoting a number.

---

## 2. The custom driver was never loading inside Wine — **fixed**

Not a tuning question — the GPU was the wrong GPU driver entirely. Full detail in
§"Turnip" below and in `patches/wine/0006-*`. Summary: a `dlopen` of
`libadrenotools.so` failed on its `libc++_shared.so` dependency, win32u fell back,
and the stock Qualcomm blob answered every Vulkan call. Measured before and
after, in a Wine session:

| | driverID | driver | Vulkan |
|---|---|---|---|
| before | 8 | Qualcomm Adreno proprietary | 1.4.295 |
| after | 18 | turnip Mesa 26.3.0-devel | 1.4.358 |

Whether Turnip is *faster* than the Qualcomm blob on this part is a separate
question and is **unmeasured**. Turnip is the reason DXVK and vkd3d can work at
all — the stock driver is not the baseline to beat, it is the thing that made
the rest of the stack unreachable.

---

## 3. Build flags

### What is already right

| Component | Flags | Verdict |
|---|---|---|
| Turnip / Zink | `-Dbuildtype=release` (`-O3`), `-mcpu=oryon-1` via `VESSEL_CPU_FLAGS` | Correct. Mesa **refuses** LTO by explicit check (`meson.build:52`); do not work around it. |
| Wine PE side | `CROSSCFLAGS=-O2` | Correct. One flag covers arm64ec, aarch64 and i386 at once, so a chip flag has nowhere safe to live. |
| DXVK / vkd3d | `--buildtype release` (`-O3`), `-Dstrip=true` | `-O3` is right; LTO is missing — see below. |

### Candidates A and B — LTO — **the whole avenue is closed**

LTO was the obvious remaining build-flag win: FEX, DXVK and vkd3d are all C++
translation layers whose hot paths cross many small functions, which is exactly
the case LTO exists for. FEX had it off for a recorded judgement (*"LTO across
the mingw link is unreliable"*); DXVK and vkd3d had simply never been asked.

All three were tested. **None of them link**, and all three fail identically:

```
ld.lld: error: undefined symbol: std::__1::mutex::lock()                (EC symbol)
ld.lld: error: undefined symbol: std::__1::__next_prime(unsigned long long) (EC symbol)
ld.lld: error: undefined symbol: __gxx_personality_seh0                 (EC symbol)
…roughly forty more per project, every one tagged (EC symbol)
```

**Only on the ARM64EC target, and every missing symbol is libc++.** ARM64EC
reaches libc++ through hybrid mapping: each symbol needs its mangled EC
counterpart to survive to the link. LTO merges the libc++ archive members before
the linker applies that mapping, so the EC names are gone by the time anything
looks for them.

That is a limitation in llvm-mingw's ARM64EC support, not a property of any of
these three projects — which is why finding it once in FEX and once in DXVK is
worth more than finding it once. Nothing here compiles for ARM64EC with LTO, and
nothing in this repo should try to work around it.

Mesa (Turnip, Zink) is unaffected by the reasoning and closed anyway: it refuses
LTO by explicit check in its own `meson.build`.

- **Status:** closed, all three. Kept as switches so a toolchain bump is one
  command:

  ```
  VESSEL_FEX_LTO=1 ./build/fex.sh      # FEX has its own, predating the shared one
  VESSEL_LTO=1 ./build/dxvk.sh         # meson -Db_lto, via lto_flag() in common.sh
  VESSEL_LTO=1 ./build/vkd3d.sh
  ```

  If a future llvm-mingw links them, benchmark before keeping it — **2.28× on
  x86-32 integer is the number to beat**, and it should be beaten visibly.

### Candidate C — `-mcpu=oryon-1` for Wine's unix side

`build/wine.sh` sets `CFLAGS="-O2 …"` and declines CPU tuning, explaining:

> CFLAGS reaches only the unix side and CROSSCFLAGS all three PE architectures at
> once, so `-mcpu=oryon-1` has nowhere safe to live — it is meaningless to the
> i386 pass.

The second half is right and the first half contradicts it. `CROSSCFLAGS` does
cover three architectures and must stay generic. But `CFLAGS` reaches **only**
the host build, and the host is arm64 Android — there is no i386 unix pass. So
`-mcpu=oryon-1` is valid there, and it would tune `ntdll.so`, `win32u.so` and
`winex11.drv.so`, which are respectively the syscall layer, the blitter and the
MIT-SHM path. That is real hot code.

- **Cost:** one hour of rebuild. Ties the package to Oryon, which this project
  does deliberately everywhere else.
- **Status:** not applied, pending a benchmark that can see a win this size.

---

## 4. Runtime settings

### Already tuned

- **`WINEESYNC=1`.** Correct: `/dev/ntsync` does not exist on this device
  (checked), so the kernel's ntsync path is unavailable, and this Wine has no
  fsync. esync is the best available.
- **FEX TSO:** `FEX_TSOENABLED=1`, `FEX_HALFBARRIERTSOENABLED=1`,
  `FEX_VECTORTSOENABLED=0`. A tuned middle ground rather than a default.
  `tools/tso/run.sh` exists to re-ask the narrower question of whether Oryon's
  LDAPUR is over-ordered; it has a control group, which is why its answer can be
  trusted.
- **`VKD3D_CONFIG=nodxr`.** Ray tracing is genuinely unreachable — Turnip has
  `VK_KHR_ray_query` but not `VK_KHR_ray_tracing_pipeline`, and vkd3d's tier
  ladder needs the pipeline extension even for Tier 1.0. Stopping titles from
  asking is faster than letting them fail.
- **Deliberately absent: `force_raw_va_cbv`.** vkd3d skips raw-VA CBVs on
  Qualcomm on purpose and calls the difference "profound (~15% in some cases)".
  Setting it would undo that.

### Candidate D — the logging channels

```
WINEDEBUG=-all,err+all,warn+module,+winediag,+loaddll
```

`+loaddll` logs every module load and `warn+module` every resolution warning.
This costs measurably at startup and little in steady state.

**Recommendation: leave it.** The diagnostics are the product's story — this is
the setting that turned "the GPU is slow" into "the driver never loaded", twice.
`docs/LOGGING.md` is a contract and changing it starts there, not here. If
startup time ever becomes the complaint, the honest fix is a per-container
"quiet" switch, not a silent default.

### Candidate E — big-core affinity — **measured, do not do it**

The SM8845 has six Oryon cores at 3.32 GHz and two "prime" cores at 3.80 GHz.
The obvious optimization is to pin the guest to the fast pair. Measured, x86-64
integer, three runs each back to back:

| affinity | time | vs default |
|---|---|---|
| none (scheduler decides) | 232.0 ms | — |
| `taskset c0` — cpu6-7, the 3.80 GHz pair | 275.4 ms | **19% slower** |
| `taskset 3f` — cpu0-5, the 3.32 GHz six | 220.2 ms | 5% faster |

**Pinning to the fast cores is the worst of the three**, by a wide margin.
A Wine session is not one thread — there is `wineserver`, the guest's own
threads, and FEX's — and squeezing all of them onto two cores costs more in
contention than the extra 480 MHz returns. The clock speed on the box is not the
throughput of the workload.

The 3.32 GHz cluster being 5% ahead of the scheduler is suggestive, not a
result: it is small, it has no control group in this measurement, and beating
EAS by fighting it tends not to survive contact with a real workload that has a
different thread count.

- **Status:** not applied. The interesting half of this is the 19% — it is a
  concrete reason not to add the "pin to big cores" setting that every phone
  emulator eventually grows.

---

## 5. What is not worth doing

- **PGO or BOLT on FEX.** A large amount of machinery for a win that LTO should
  be measured against first. Revisit only if Candidate B lands and helps.
- **`-O3` for Wine's PE side.** Wine upstream ships `-O2` and its PE code is not
  where the time goes. Miscompile risk without a matching payoff.
- **Working around Mesa's LTO refusal.** Upstream turned it off on purpose.

---

## Measured baseline

Motorola Signature, SM8845, `tools/device-bench.sh --scale 1`, best of three,
2026-08-08. Kept in `out/bench/before.txt`. Two consecutive runs agreed to
within 1%, and the `branch` figures were bit-identical, so this is a stable
baseline rather than a snapshot of one thermal state.

| section | ARM64 (ms) | x86-64 | x86-32 |
|---|---|---|---|
| int | 244.1 | 270.0 — **1.09×** | 560.6 — **2.28×** |
| branch | 233.6 | 191.3 — 0.82× | 154.4 — 0.66× |
| mem | 139.3 | 140.1 — **0.99×** | 107.8 — 0.79× |
| float | 328.2 | 403.2 — **1.23×** | 403.2 — 1.23× |

Wine process start (`cmd /c exit`, best of five): **197 ms**.

**What this says.** ARM64EC plus FEX costs about **9% on integer throughput** and
nothing at all on memory — the `mem` row landing on 0.99× is the benchmark
validating itself, since a memory-bound loop should be almost
translation-independent and it is. Float costs 23%. x86-32 through WoW64 is a
different story: **2.28× on integer**, so the 32-bit path is roughly twice as
expensive as the 64-bit one and that gap, not the 64-bit one, is where
translation work would pay off.

**The `branch` row does not mean x86 is faster than native, and must not be
quoted that way.** It is reproducible to the millisecond, so it is not noise —
which makes it a codegen difference rather than a translation result. The same
source compiled for three targets is not the same machine code, and for an
unpredictable data-dependent branch the choice between a real branch and a
conditional select is worth more than everything translation does. That section
measures compiler choice plus translation, and the two cannot be separated from
outside. `i686 mem` at 0.79× is the same kind of artefact.

Left in rather than deleted, because a benchmark that only reports the rows
that flatter the system is not a benchmark. `int`, `mem` and `float` are the
rows to read.

## How to measure

```
./tools/device-bench.sh                                 # everything
./tools/device-bench.sh --only cpu                      # cpu | startup | graphics
./tools/device-bench.sh --scale 2                       # longer runs, less noise
./tools/device-bench.sh --baseline out/bench/before.txt # print deltas
```

It needs a prefix from `tools/device-session.sh`. Results go to
`out/bench/results.txt`; keep a copy as `before.txt` and pass it back with
`--baseline` after a change.

Rules that make the numbers mean something, all enforced by the script:

1. **A control group.** Every x86 measurement is paired with the same source
   built for ARM64, which runs natively. If the native number moves between two
   runs, the device was thermally different and the x86 delta cannot be read.
   This is why the headline output is a *ratio*, not a millisecond count — an
   absolute time on a phone is a statement about the case temperature.
2. **Best of N**, not one sample. The fastest run is the one least disturbed by
   whatever else the device was doing.
3. **Checksums.** Every CPU section prints one, and they must match across all
   three architectures. A translator that is fast because it skipped work would
   otherwise read as a win; a mismatch voids the row it is on.

`WINEDEBUG=-all` here and nowhere else in the repo. Everywhere else the
diagnostic channels are the point; in a benchmark `+loaddll` writes a line per
module load and is the measurement's largest confound.

### Absolute times do not survive between sessions

Back to back, two runs agree to within 1% and the `branch` figures come out
bit-identical. Hours apart, the same x86-64 integer measurement moved from
270 ms to 232 ms — 15%, with no code change, purely the device's thermal and
scheduling state.

So: **compare within a run, never across sessions.** That is why the headline
output is a ratio against the ARM64 control measured in the same invocation, and
why `--baseline` is for a before/after taken close together rather than a number
kept from last week. A 5% improvement quoted across two sessions is noise
wearing a result's clothes.

### What it cannot measure yet

The shader-cache question from §1 — cold versus warm — needs a D3D probe that
reaches instance creation, and every one of them is currently BLOCKED because
Vulkan does not advertise `VK_KHR_win32_surface` without a display driver
loaded. The graphics harness is headless. Timing a path that refuses to start
would give a stable, reproducible, meaningless number, so the script says so
instead. This unblocks when the probes run under the app's own X server.
