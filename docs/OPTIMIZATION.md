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

### Candidate A — LTO for DXVK and vkd3d

`build/dxvk.sh` and `build/vkd3d.sh` configure `--buildtype release` and nothing
else. Neither passes `-Db_lto=true`. Both are pure translation layers where the
hot path crosses many small functions, which is the case LTO is for, and neither
carries Mesa's refusal.

- **Cost:** longer link, larger peak build memory. No runtime risk beyond the
  usual LTO miscompile tail.
- **Expected:** small but real on draw-submission throughput.
- **Status:** not applied. Cheap to try, must be benchmarked, and the ARM64EC PE
  target is unusual enough that "it linked" is worth confirming separately from
  "it is faster".

### Candidate B — LTO for FEX

`build/fex.sh` sets `-DENABLE_LTO=False`. The reason was recorded when the flag
was first set — *"LTO across the mingw link is unreliable and costs more build
time than it wins"* — and then lost in the refactor at `6d4a821`, leaving a bare
flag. The comment is now restored.

It is still a judgement rather than a measurement, and FEX's dispatcher and JIT
emitter are the hottest code in the project for any non-native program.

- **Cost:** build time. Possibly link trouble under llvm-mingw for ARM64EC,
  which is likely what "unreliable" meant.
- **Expected:** the largest single build-flag win available, if it links.
- **Status:** not applied. If the ARM64EC link is what actually fails, record
  *that* — a reproducible link error is a better comment than a judgement.

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

### Candidate E — big-core affinity

The SM8845 has six Oryon cores at 3.32 GHz and two at 3.80 GHz. Nothing pins
anything. Android's scheduler will usually do the right thing for a foreground
app, and pinning risks *losing* to it by fighting EAS.

- **Status:** not applied, and lower confidence than it looks. Worth measuring
  before believing; a wrong affinity mask is slower than none.

---

## 5. What is not worth doing

- **PGO or BOLT on FEX.** A large amount of machinery for a win that LTO should
  be measured against first. Revisit only if Candidate B lands and helps.
- **`-O3` for Wine's PE side.** Wine upstream ships `-O2` and its PE code is not
  where the time goes. Miscompile risk without a matching payoff.
- **Working around Mesa's LTO refusal.** Upstream turned it off on purpose.

---

## How to measure

```
./tools/device-bench.sh                 # everything, both cold and warm
./tools/device-bench.sh --only cpu      # translation throughput only
./tools/device-bench.sh --baseline out/bench-before.json   # compare two runs
```

Rules that make the numbers mean something, all enforced by the script:

1. **A control group.** Every x86 measurement is paired with the same work built
   for ARM64. If the native number moves between runs, the measurement is
   thermals or scheduling and the x86 delta cannot be read.
2. **Repeat and report spread**, not one run. A phone thermally throttles; a
   single sample of a hot device is a story about the case temperature.
3. **Cold and warm separately.** With shader caches now working, mixing the two
   hides the thing §1 was about.
