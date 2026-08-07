# Tuning — Oryon / Adreno 829 (SM8845, Android 16, 4 KB pages, SVE2@128b, no FEAT_TSO)

Flag names, ranges and defaults below were read from primary sources on 2026-08-07. Anything
not confirmed at a primary source is in §7 rather than stated as fact.

## 1. Box64

[`docs/USAGE.md@main`](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md). All twelve
flag *names* in the original brief are correct; several assumed *defaults* were not.

| Env var | Values | Default | What it does |
|---|---|---|---|
| `BOX64_DYNAREC_STRONGMEM` | 0-4 | **0** | Emulates x86 strong memory ordering: 1 write barriers, 2 +SIMD, 3 +regular, 4 QEMU-style TSO ("for evaluation purposes"). |
| `BOX64_DYNAREC_BIGBLOCK` | 0-3 | **2** | Bigger dynarec blocks. 0 suits thread/JIT-heavy games; 3 is documented "useful for Wine programs". |
| `BOX64_DYNAREC_SAFEFLAGS` | 0-2 | **1** | Flag-emulation care across CALL/RET; 2 handles all edge cases. |
| `BOX64_DYNAREC_CALLRET` | 0-2 | **2** | Skips the CALL/RET jump table. 2 also handles returns into dirty blocks — **"Does not work on WowBox64."** |
| `BOX64_DYNAREC_FORWARD` | 0/128/256/512/1024 | **128** | Max byte gap tolerated before the next forward jump; other values silently fall back to 128. |
| `BOX64_DYNAREC_WEAKBARRIER` | 0-2 | **1** | Weakens the barriers STRONGMEM emits; 2 also drops the final write barriers. |
| `BOX64_DYNAREC_FASTNAN` | 0-1 | **1** | 0 reproduces x86 `-NaN` exactly; 1 is faster. |
| `BOX64_DYNAREC_FASTROUND` | 0-2 | **1** | 0 honours x86 rounding exactly; 2 is exact float→int but fast int→float. |
| `BOX64_DYNAREC_X87DOUBLE` | 0-2 | **0** | 0 uses float where possible; 1 forces double; 2 checks x87 Precision Control. |
| `BOX64_AVX` | 0-2 | **2** on Arm64 | Exposes AVX in CPUID; 2 adds AVX2/BMI2/FMA/ADX/VPCLMULQDQ/RDRAND. |
| `BOX64_DYNACACHE` | 0-2 | **1** | Persists translated code to `$XDG_CACHE_HOME/box64`; 2 reads but never writes. |
| `BOX64_SSE42` | 0-1 | **1** | Exposes SSE4.2 in CPUID. |
| `BOX64_DYNAREC_ALIGNED_ATOMICS` | 0-1 | 0 | 1 is faster/smaller but **SIGBUSes** on unaligned `LOCK` ops. |
| `BOX64_DYNAREC_WAIT` | 0-1 | 1 | 0 interprets instead of blocking on compilation — less JIT stutter. |
| `BOX64_DYNAREC_PAUSE` | 0-3 | 0 | Maps x86 `PAUSE` to YIELD/WFI/SEVL+WFE; helps spinlock-heavy titles. |

Corrections to the brief: `STRONGMEM` has **five** levels and defaults to **0**; `BIGBLOCK`
defaults to **2**; `CALLRET` to **2**; `FORWARD` is enumerated, not a free integer; `BOX64_AVX`
is already **2** on Arm64, so AVX2 is exposed by default.

**`SD8EG5` exists**, and sets exactly ([CMakeLists.txt](https://github.com/ptitSeb/box64/blob/main/CMakeLists.txt)):

```cmake
add_definitions(-DSD8EG5)
set(CFLAGS -pipe -march=armv8.7-a+crypto+sm4+sha3+fp16+sve+sve2 -mtune=oryon-1)
```

Two caveats. It is labelled for **SM8850**, not SM8845. And the in-repo comment states plainly
that the flags are "mostly the same" as `SDORYON1` and that SVE/SVE2 "are not yet used in Box64's
ARM64 dynarec" — so `+sve+sve2` buys nothing today.

## 2. FEX

[`Config.json.in@main`](https://github.com/FEX-Emu/FEX/blob/main/FEXCore/Source/Interface/Config/Config.json.in).

**Naming, definitively: `FEX_TSOENABLED`** — all caps, no underscore.
[`config_generator.py`](https://github.com/FEX-Emu/FEX/blob/main/FEXCore/Scripts/config_generator.py)
emits the enum as `json_name.upper()`, then `Config.cpp` does `GetVar(EnvMap, "FEX_" #enum)`.
So JSON `TSOEnabled` → env `FEX_TSOENABLED` → CLI `--tsoenabled`. Reading `Config.cpp` alone
suggests `FEX_TSOEnabled`; that is wrong, because the generator uppercased it first.

| JSON key (group `Hacks`) | Env var | Default | What it does |
|---|---|---|---|
| `TSOEnabled` | `FEX_TSOENABLED` | **true** | Master TSO switch. "Highly likely to break any multithreaded application if disabled." |
| `HalfBarrierTSOEnabled` | `FEX_HALFBARRIERTSOENABLED` | **true** | Backpatches unaligned accesses to half-barrier atomics; "can be dangerous" as aligned accesses through that code become non-atomic. |
| `VectorTSOEnabled` | `FEX_VECTORTSOENABLED` | **false** | Makes vector load/stores atomic too. |
| `MemcpySetTSOEnabled` | `FEX_MEMCPYSETTSOENABLED` | **false** | Makes `REP MOVS`/`REP STOS` atomic. |
| `StrictInProcessSplitLocks` | `FEX_STRICTINPROCESSSPLITLOCKS` | **false** | Global lock for unaligned atomics crossing 16-byte/cacheline, so split locks cannot tear. |
| `ParanoidTSO` | — | — | **Does not exist in current main.** |
| `KernelUnalignedAtomicBackpatching` | `FEX_KERNELUNALIGNEDATOMICBACKPATCHING` | true | Cuts kernel context switches via backpatching. |
| `VolatileMetadata` | `FEX_VOLATILEMETADATA` | true | Uses PE volatile metadata to skip needless TSO ops. |
| `SMCChecks` | `FEX_SMCCHECKS` | `mtrack` | Self-modifying-code strategy (`none`/`mtrack`/`full`). |
| `HostFeatures` (CPU) | `FEX_HOSTFEATURES` | `off` | Force ISA features; relevant here: `enablelrcpc`, `enablelrcpc2`. |

`ParanoidTSO` was added 2021-04-06 and last touched 2023-12-28; code search over current main
returns zero hits. **Do not ship a `FEX_PARANOIDTSO` knob.**

Two constraints: FEX requires a **4 KB page kernel** (this target qualifies; it is a hard gate,
and the usual reason FEX fails on 16 KB-page Android 16 devices — Box64 has partial non-4K
hacks). And `HostFeatures` has **no TSO member**; FEX accelerates TSO off detected LRCPC/LRCPC2.
`ForceSVEWidth` is a **vixl-simulator debug option**, not a real SVE tuning knob.

## 3. Turnip

[envvars](https://docs.mesa3d.org/envvars.html). `sysmem`, `nolrz`, `forcebin`, `flushall` are
all real. `noconform` is real but **undocumented**.

| `TU_DEBUG` | Effect |
|---|---|
| `sysmem` / `gmem` | Force sysmem (direct) / GMEM (tiled) rendering. |
| `nobin` / `forcebin` | Disable / force hardware binning. |
| `nocb` / `forcecb` | Disable / force concurrent binning. |
| `nolrz` / `nolrzfc` | Disable LRZ / LRZ fast-clear. |
| `noubwc` | Disable UBWC bandwidth compression. |
| `flushall` / `syncdraw` | Flush caches / wait for GPU after every draw. Debug-grade slow. |
| `unaligned_store`, `3d_load`, `dynamic`, `rast_order` | GMEM store/load and renderpass overrides. |
| `fdm` / `nofdm` / `fdmoffset` / `nobinmerging` | Fragment density map controls. |
| `perfc` / `perfcraw` | Performance query support. |
| `noconform` | **Not in the docs.** Added as `TU_DEBUG_NOCONFORM` (bit 24) in `tu_util.cc` to force multiview availability on devices lacking it (motivating case: Adreno 610). **Irrelevant on A829.** |

**The GMEM issue.** Upstream Mesa documents no A8xx Turnip support at all — freedreno.html still
says "Adreno 6xx", and Mesa 26.0's Gen8 work was **Gallium/OpenGL only**, Turnip "to come"
([Phoronix](https://www.phoronix.com/news/Freedreno-Lands-Adreno-Gen-8)). So A829 Vulkan means a
community fork. Per [whitebelyash `tu_v30`](https://github.com/whitebelyash/AdrenoToolsDrivers/releases)
(supports 840/830/**829**/825/810): *"GMEM rendering produces glitchy picture on A830 (write page
faults), disable it with `TU_DEBUG=sysmem`."* The same notes warn GPUs other than 830/840 "can
have reduced performance and/or more glitches" — **A829 is second-tier even in the fork** — and
add fork-only **`TU_DEBUG=deck_emu`** (spoof a Steam Deck for games that reject Qualcomm).
[Banners-Turnip](https://github.com/The412Banner/Banners-Turnip) also recommends `sysmem`, plus
`WRAPPER_BLIT=1` for Winlator.

## 4. Chinese community consensus

**Sourcing caveat.** Tieba was network-blocked and Zhihu returned 403, so **no Tieba or Zhihu
body text was read** — snippets only. Usable material came from Bilibili's API (creator-written
descriptions, verbatim) and dated modder blogs. Separately: ~90% of CSDN hits here are
machine-generated `gitblog_*` pages whose Box64 numbers are demonstrably wrong. **Treat the
widely-reposted `SAFEFLAGS=0` / `FORWARD=1024` / `BOX64_MAXCPU=8` list as spam.**

| Topic | Position | Source |
|---|---|---|
| Approach | Users **switch fork** (Ludashi, CMOD, Winlator-CN) rather than tune containers | [52emu.cn/wp/337.html](http://52emu.cn/wp/337.html) |
| **Bionic mandatory** on 8-Elite class | 「8Elite必须要勾选bionic才能正常运行游戏」 | Bilibili `BV13zVwzrEDQ` |
| Engine split | **FEX/arm64ec for games; Box64 + Proton 9.0 64 for Steam** (arm64ec explicitly rejected there) | `BV12wvwzpEyn`, `BV1xBtuzxE2o` |
| FEX tuning | UI presets only: TSO `Fastest`, X87 `Fast`, MultiBlock on, FEXCore **2508** | `BV12wvwzpEyn` (ETS2, 58-60 fps @ 12-16 W) |
| **`STRONGMEM=1` + `WEAKBARRIER=1\|2`** | The one original Chinese Box64 tip;「可为某些游戏提速」 | 静言思之-SZ, `BV1CGqYYeEiv` |
| DXVK | **`2.6-gplasync-0`, Async + Async Cache both on**; drop to 2.7.1 on render errors | `BV12wvwzpEyn`; 52emu.cn |
| Pairing | 「**Turnip(Adreno) 只能对应 DXVK**」 — never WineD3D | crge.cn |
| Driver | Turnip **26.1.0+**, and it must be a **Gen8/A8XX** build | 52emu.cn |
| Hygiene | **Delete `DXVK_HUD`** (black blocks); run from C:/Z: not D: | 52emu.cn |

**The 鲁大师 spoof is real.** The fork renames its package to **`com.ludashi.benchmark`** because
many Chinese OEM ROMs pin a high-performance governor and relax thermals for recognised benchmark
packages ([procedure](http://52emu.cn/wp/345.html)). Confirmed in release notes — Ludashi v2.9
*"changed the redmagic build package for PUBG … a better option (for more phones) than the
previous one (genshin impact)"*. Whether a Motorola ROM honours this is **untested**.

| Western | Chinese | Verdict |
|---|---|---|
| Force `sysmem` on A8xx | **Uncheck sysmem, use `gmem`** | **Chinese is wrong here** (below) |
| Avoid DXVK async | gplasync, Async + Async Cache on | Contradiction; plausible with no anticheat exposure |
| WineD3D is a free fallback | Turnip pairs **only** with DXVK | Chinese more restrictive |
| Run newest Turnip | Pin a known-good build | Chinese favours pinning |
| Turnip+Zink is fastest | Zink barely mentioned; Ludashi 2.9 *removes* Zink for arm64ec | Contradiction |
| Pin to cores 4-7 | (same advice) | **Both wrong** — no little cores |

**`gmem`, resolved.** The Chinese instruction 「取消勾选…只选择【gmem】」 is a copy-paste of
A710/A720 guidance. The original
([K11MCH1 release notes](https://github.com/K11MCH1/AdrenoToolsDrivers/releases)) is scoped:
*"**a710 and 720** can use regular or Gmem. When using regular driver on Winlator, do uncheck
sysmem and check gmem…"* It says nothing about A8xx, where the fork maintainer reports GMEM
write page faults. **On A829, use `sysmem`.** This is the likeliest circulating advice to break
this build.

## 5. Recommended starting profile

```sh
# Box64 — memory ordering is the highest-value axis on a part without FEAT_TSO
BOX64_DYNAREC_STRONGMEM=1        # contested; see note
BOX64_DYNAREC_WEAKBARRIER=2      # pairs with STRONGMEM, recovers most of its cost
BOX64_DYNAREC_BIGBLOCK=3         # docs: "useful for Wine programs"
BOX64_DYNAREC_FORWARD=512
BOX64_DYNAREC_WAIT=0             # less JIT stutter
BOX64_DYNAREC_ALIGNED_ATOMICS=0  # 1 SIGBUSes on unaligned LOCK ops
# SAFEFLAGS=1 CALLRET=2 FASTNAN=1 FASTROUND=1 X87DOUBLE=0 DYNACACHE=1 AVX=2 SSE42=1 are defaults

# FEX — run at defaults; every TSO key already defaults correctly for a non-FEAT_TSO host
FEX_TSOENABLED=1  FEX_HALFBARRIERTSOENABLED=1  FEX_VECTORTSOENABLED=0
FEX_MEMCPYSETTSOENABLED=0  FEX_STRICTINPROCESSSPLITLOCKS=0

# Turnip
TU_DEBUG=sysmem
WRAPPER_BLIT=1
# nocb (Banners suggests on A8xx) · deck_emu (fork-only, for GPU-vendor checks) — try singly
```

**`STRONGMEM` is the one contested value.** Box64 ships 0; Winlator's COMPATIBILITY uses 1; this
repo's `canoe.env` sets 0. At 0, x86 store ordering is simply not emulated — fine until a
multithreaded title desyncs. The `STRONGMEM=1` + `WEAKBARRIER=1|2` pairing is the best-evidenced
way to buy ordering back cheaply. **Measure it; do not inherit it.**

The FEX tunables (`VECTORTSO`, `MEMCPYSETTSO`, `STRICTINPROCESSSPLITLOCKS`) all *add* correctness
and *cost* speed — they are debugging tools for a specific race, not a speed profile.
`FEX_TSOENABLED=0` is benchmark-only.

Fallback ladder: `BIGBLOCK=0` → `STRONGMEM=2` → `SAFEFLAGS=2` → `X87DOUBLE=1` → `FASTROUND=0`.
DXVK: pin a version rather than tracking latest (upstream is 3.0.2; 2.7.1 closes 2.x).

## 6. Audit of this repo's current settings

Every env var name and range in `app/src/main/assets/params-manifest.json` **matches upstream
exactly**, including the tricky all-caps FEX forms. Five things worth changing:

| # | Finding |
|---|---|
| 1 | **`WEAKBARRIER` is absent repo-wide.** On a no-FEAT_TSO part it is arguably the highest-value knob — the dedicated mechanism for making `STRONGMEM` affordable. Expose it next to `STRONGMEM`. |
| 2 | **`CALLRET` defaults to 2 while `engine.x86` offers `WOWBOX64`.** Box64 documents level 2 as "Does not work on WowBox64". Clamp to ≤1 under WowBox64, or document it. |
| 3 | **`system.cpuAffinity` offers "efficiency", which does not exist here.** `canoe.env` shows 6×3.32 + 2×3.80 GHz — two Oryon tiers, no little cores. Verify index order via `cpuinfo_max_freq` rather than assuming 0-3 vs 4-7. |
| 4 | **`canoe.env`'s `RUNTIME_*` block is dead code.** It claims to be "consumed by the app", but nothing reads `RUNTIME_`; the manifest holds the real defaults. The two already disagree (`RUNTIME_FEX_HALFBARRIERTSO` is not a real env var name). Wire up or delete. |
| 5 | **`canoe.env:56` overstates the match.** `SD8EG5` is labelled SM8850, and its `-march` is a strict subset of the file's own `TARGET_MARCH` (which adds `+i8mm+bf16`, both device-reported). |

Also: `turnip.TU_DEBUG` is an enum of `sysmem`/`default` only, so `nocb` and `deck_emu` are
unreachable from the UI.

## 7. Disputed / unverified

| # | Item |
|---|---|
| 1 | **SM8845 ≠ SM8850.** No Box64 preset is written for this exact SKU. I found no *primary Qualcomm* source for the SM8845 ↔ 8 Gen 5 ↔ Adreno 829 mapping — only spec aggregators. |
| 2 | **No FEAT_TSO — RESOLVED.** Not from vendor docs (found none) but from this repo's own on-device cpuinfo in `canoe.env`: no `tso`. It *does* report `lrcpc3`/`ilrcpc`, which is what makes TSO emulation cheap. Worth confirming FEX actually engages it. |
| 3 | **SVE2@128b has no consumer.** Box64's dynarec doesn't use SVE2 (its own comment); FEX's only SVE-width control is a simulator debug flag. Any claimed SVE2 speedup from `SD8EG5` is wrong. |
| 4 | **`noconform` not re-verified in current main.** Confirmed via its adding commit; gitlab.freedesktop.org is behind Anubis and the GitHub mesa mirror 404s on `tu_util.{c,cc}`. Very likely still present. |
| 5 | **`deck_emu` and `WRAPPER_BLIT=1` are fork/wrapper-only**, not upstream Mesa. |
| 6 | **A8xx Turnip status is contradictory.** Phoronix: Mesa 26.0 Gen8 = OpenGL only. Secondary guides claim Turnip 26.1.0 fixed A830. Both may be true (upstream GL vs fork Vulkan) — but no upstream doc mentions A8xx Turnip. |
| 7 | **Secondary guides contradict Box64's own docs** on defaults (`STRONGMEM=1`, `BIGBLOCK=1` presented as shipped values). They are not. All quoted "15-30% speedup" figures are uncited and unverified. |
| 8 | **Winlator presets ≠ Box64 defaults.** If the build inherits a preset, the §1 defaults are not in effect. |
| 9 | **Chinese sources are snippet-level.** No Tieba/Zhihu body read; no Bilibili/Zhihu source with concrete flag values beyond those cited. Treat §4 as leads to re-verify on device. |
| 10 | **No Chinese-language FEX tuning consensus exists** that I could find — the scene is Box64/preset-only. No Chinese guidance found for `WAIT`, `PAUSE`, `SSE42`, WowBox64, or any `dxvk.conf` key. |
| 11 | **CPU affinity for SM8845 is unknown.** The circulating "cores 4-7" advice is big.LITTLE-era. No source gives a correct prime-core mask. Leave unset until profiled. |
| 12 | **Ludashi package-spoof efficacy on a Motorola ROM is untested**, as are the RTX 2060 device-spoof and PulseAudio-vs-ALSA (single-source anecdotes; cheap to test, safe to revert). |
