# Bandwidth

Every byte this stack moves between the CPU, the GPU and the one pool of LPDDR
they share, where it is moving one it does not have to, and what it would cost
to find out.

The premise of the whole document is a hardware fact that three of the four
layers above the driver were written not to believe. There is **one** Vulkan
memory heap on this part, it is `DEVICE_LOCAL`, and every non-lazy memory type
on it is also `HOST_VISIBLE` and `HOST_COHERENT`
(`native/mesa/src/freedreno/vulkan/tu_device.cc:1798-1837`,
`tu_device.cc:2172-2188`). There is no system-memory heap to fall back to, no
PCIe bus to cross, and no BAR window to run out of. DXVK, vkd3d-proton and
Wine were all written against a machine where those four things are true, and
each of them contains logic whose entire purpose is to manage a scarcity this
device does not have.

The rule from `docs/OPTIMIZATION.md` carries over unchanged: **a claim without a
measurement is marked as unmeasured**, and the measurements that exist are cited
rather than restated. This document does not re-derive §7 of that file — the
Adreno draw-efficiency audit — but it does correct one of its results, in
§"Corrections".

---

## 1. What is already right

**vkd3d's memory topology probe gets this device exactly right, and the log line
in the session trace is the proof.** `vkd3d_memory_topology_is_uma_like`
(`native/vkd3d/libs/vkd3d/resource.c:9605-9613`) returns true on its *first*
clause — `host_only_heap_count == 0` — because the loop at `resource.c:9596-9601`
looks for a memory type that is `HOST_VISIBLE` and **not** `DEVICE_LOCAL` on a
non-device-local heap, and there is no such heap. That answer then propagates
through three separate decisions, all of them correct:

| decision | file:line | outcome here |
|---|---|---|
| `vkd3d_memory_info_decide_hvv_usage` | `resource.c:9636-9659` | `INFO("Topology: UMA-like topology.")`, `is_hvv_use_allowed = true` |
| ReBAR size heuristic (the 7 GB / 9 GB thresholds) | `resource.c:9680-9691` | **skipped entirely** — the block is inside `if (!vkd3d_memory_topology_is_uma_like(topology))` |
| UPLOAD heap properties | `resource.c:9701-9704` | `DEVICE_LOCAL \| HOST_VISIBLE \| HOST_COHERENT` — the CPU writes where the GPU reads |
| ReBAR budget enforcement | `resource.c:9743-9744` | `/* Nothing to do on UMA-style implementations. */`, `rebar_budget_mask = 0` |

The session log's `Topology: UMA-like topology` and `HVV usage is allowed` are
those lines, and they mean the D3D12 UPLOAD heap on this stack already involves
no staging copy at all. `D3D12_HEAP_TYPE_GPU_UPLOAD` is enabled for the same
reason (`resource.c:9714-9726`). **Nothing in this table needs changing**, and
in particular `VKD3D_CONFIG=no_upload_hvv` and `small_vram_rebar` would either
undo it or do nothing.

**vkd3d already skips its per-allocation zero-fill on Turnip.**
`vkd3d_driver_can_zero_clear_alloc` has `case VK_DRIVER_ID_MESA_TURNIP: return
true;` at `native/vkd3d/libs/vkd3d/memory.c:1988`, so a non-suballocated
allocation is trusted to arrive zeroed from the kernel rather than being cleared
by hand. (The suballocated half of this is *not* free — see candidate 5.)

**DXVK turns off budget enforcement and resource eviction on a UMA part, on
purpose.** `dxvk_memory.cpp:612-613` sets `enforceBudget` false unless the
device is a discrete GPU, and `dxvk_memory.cpp:2461` early-returns out of
`evictResources` with the comment *"Doing this on integrated graphics would be
harmful, so don't"*. Evicting a resource on this device would mean copying it
from RAM to RAM.

**Both translation layers attach a `VkImageFormatListCreateInfo` whenever they
set `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT`, which is what keeps UBWC alive.** This
matters more here than on desktop: Turnip's `format_list_ubwc_possible`
(`native/mesa/src/freedreno/vulkan/tu_image.cc:496-519`) returns **false** when a
mutable image arrives with no format list, and UBWC is then switched off for
that image. DXVK only sets the flag when it has a family of more than one format
to declare (`native/dxvk/src/d3d11/d3d11_texture.cpp:85-93`) and chains the list
at creation (`dxvk_image.cpp:186-187`); vkd3d builds a compatibility list and
attaches it at `native/vkd3d/libs/vkd3d/resource.c:850-857`. There is one hole
in vkd3d's version — candidate 3.

**A UAV on a render target does not cost UBWC on this part**, and the widely
repeated Adreno advice that it does is about older hardware. The gate is
`tu_image.cc:377-387`, `!info->props.supports_uav_ubwc`, and a8xx inherits
`supports_uav_ubwc = True` from `a7xx_gen3`
(`native/mesa/src/freedreno/common/freedreno_devices.py:876`). Do not go hunting
for UAV-shaped UBWC losses.

**`MESA_VK_WSI_DEBUG=sw` rather than `sw,linear` is a measurement, not a
default.** The reasoning and the numbers are at
`app/src/main/java/app/vessel/core/SessionEnvironment.kt:1661-1685`: forcing a
linear swapchain image removes a whole-frame blit on paper and is ~14% slower on
the mean, because rendering into a linear image makes the resolve write an
untiled, uncompressed layout. That is the single clearest statement in this repo
of why bandwidth arithmetic loses to a tiler measurement, and it should not be
retried.

**`force_raw_va_cbv` is deliberately absent**
(`SessionEnvironment.kt:1460-1463`), because vkd3d skips raw-VA CBVs on
Qualcomm on purpose and `native/vkd3d/libs/vkd3d/state.c:7367-7374` calls the
difference *"profound (~15% in some cases)"*.

---

## 2. Corrections to what is already recorded

**`docs/OPTIMIZATION.md` §7 lists `noubwc` among the flags that can be switched
mid-session through `TU_DEBUG_FILE`. It cannot be, and the −21% attributed to it
cannot mean what the table says it means.**

`TU_DEBUG_FILE` is watched by an inotify thread on `IN_CLOSE_WRITE`
(`native/mesa/src/util/os_file_notify.c:162-201`) and the callback masks the
file's contents against a fixed allowlist —
`runtime_flags = file_flags & tu_runtime_debug_flags`
(`native/mesa/src/freedreno/vulkan/tu_util.cc:113`). That allowlist is
`tu_util.cc:70-77` and it is nineteen flags long. `TU_DEBUG_NOUBWC` **is not in
it**. Anything outside the list is folded into `start_debug` at init and
re-ORed back on every notification (`tu_util.cc:115`), so a `noubwc` written to
the file mid-session has no effect whatsoever.

Even if the flag had been live, it would have changed almost nothing: UBWC is
decided once per image at creation (`tu_image.cc:683-685`, inside
`vkCreateImage`), so a running game's render targets keep the layout they were
born with. So the `noubwc` row is one of two things, and §7 does not say which:
a cross-session comparison, which that same document warns moves 15% on thermals
alone, or a no-op that happened to land in a bad window. **Either way it is not
evidence that UBWC is worth 21%, and it should not be quoted as such.** The
`nolrz`, `gmem`, `sysmem` and `perf` rows in that table are unaffected — all four
are in the allowlist. `nolrz` is a hybrid: `device->use_lrz` is latched from
`TU_DEBUG_START(NOLRZ)` at `tu_device.cc:3237` so the LRZ buffer is still
allocated, but the live flag at `tu_lrz.cc:316,364` does stop it being used,
which is what that measurement was asking.

Redoing it honestly costs a launch, not a rebuild: put `noubwc` in the container's
`TU_DEBUG` (the `turnip` family, `ContainerDiagnostics.kt:681-687`) and compare
two sessions, with §7's own ±10% floor in mind — which makes a 21% effect only
barely resolvable, and is a reason to reach for the render-pass trace in
candidate 1 instead.

**§7 also sets `d3d11.cachedDynamicResources` aside as fixing "something that is
not the constraint". That is true and incomplete: this stack already does the
same thing, globally, to everything.** See candidate 2 — the option's effect is
to add `HOST_CACHED` to a dynamic buffer's memory flags
(`native/dxvk/src/d3d11/d3d11_buffer.cpp:359-366`), and
`tu_override_uncached_as_cache_coherent` already promotes *every* allocation
DXVK makes to cached memory before that option gets a say. There is nothing left
for it to do here unless the override comes off.

---

## 3. The one number this device will not give up

There is **no reachable DRAM bandwidth or DDR clock counter on this phone.**
`/sys/class/devfreq` is denied to the app *and to the shell*
(`app/src/main/java/app/vessel/data/MetricSampler.kt:463-467`), and KGSL exposes
`gpubusy` and `gpu_model` and nothing else (`MetricSampler.kt:446-449`). DXVK's
counters carry no traffic figure either: the full `DxvkStatCounter` enum
(`native/dxvk/src/dxvk/dxvk_stats.h:13-41`) has twenty-four entries and the only
memory-shaped ones are `DescriptorHeapSize`/`Used`, so the `gfx-stats.json` this
app writes every second (`patches/dxvk/0001`, fields listed at its `:79-104`)
reports draws, passes, barriers, submits, syncs and a footprint in MB — never a
byte moved.

Everything below is therefore either an *estimate the driver computes for
itself* (candidate 1) or a frame-time delta measured against §7's ±10% floor.
Any statement in this document of the form "N MB per frame" is arithmetic from a
resolution and a pixel size, and is labelled as such.

---

## 4. Ranked candidates

Ranked by expected win divided by cost. Cost is counted in what it takes to get
an answer, not in what it takes to ship the fix.

### 1. Turn on the per-render-pass trace and read the bytes — free, and every other candidate needs it

**Mechanism.** Turnip's `render_pass` tracepoint already records, per render
pass, everything this document has been guessing at. From
`native/mesa/src/freedreno/vulkan/tu_tracepoints.py:128-156`, emitted at
`tu_cmd_buffer.cc:3013-3058`:

- `tiledRender` — GMEM or sysmem, per pass, as it actually happened.
- `tilingDisableReason` — the exact string from `use_sysmem_rendering`
  (`tu_cmd_buffer.cc:1366-1451`), which distinguishes *"Autotune selected
  sysmem"* from *"Can't fit attachments into gmem"* from *"Non-framebuffer-space
  barrier"*.
- `clearCPP`, `loadCPP`, `storeCPP` — bytes per pixel moved by the clear, the
  tile load and the tile store. Multiply by `width × height` and you have the
  pass's attachment traffic exactly.
- `ubwc` — a per-attachment `y`/`n` string built at `tu_cmd_buffer.cc:3001-3006`.
  This answers candidate 3 outright.
- `avgPerSampleBandwidth` — the driver's own estimate of per-draw traffic,
  `drawcall_bandwidth_per_sample_sum / drawcall_count`.
- `numberOfBins`, `binWidth`, `binHeight`, `drawCount`, and the whole `lrz*`
  family.

**Why it beats fps.** §7's audit ran aground on a 20% spread across three
identical baseline windows. These fields are counts and byte-per-pixel figures,
not times: they do not move with thermals, so a single run answers questions
that live gameplay cannot resolve at all.

**The experiment.** Both variables are already wired.
`SessionEnvironment.kt:1624-1625` sets `MESA_GPU_TRACEFILE` to
`<container>/tmp/gpu-trace` unconditionally and reserves it; `MESA_GPU_TRACES` is
an ordinary environment row. Set `MESA_GPU_TRACES=print_csv` from Diagnostics →
Environment variables, play for ten seconds, pull the file. The mode strings are
`native/mesa/src/util/perf/u_trace.c:306-317` and the tracefile is opened at
`u_trace.c:337`.

**Expected win: none directly.** It produces no frames. It is first because
every candidate below is currently an argument, and this is the one thing in the
stack that turns them into numbers for the price of one environment variable.

### 2. A/B `tu_override_uncached_as_cache_coherent` — it is not scoped to uploads, and it has never been measured

**Mechanism, and this is the part that is not in `docs/ARCHITECTURE.md`.** The
option is documented as making Turnip *"hand back the cached-coherent memory
type"* for host-visible upload allocations, which is what FEX wants it for. What
it actually does is broader by a wide margin. It does not change the memory-type
table the application sees at all. It sets an index at
`tu_device.cc:1816-1819`, and then `tu_bo_init_new_explicit_iova` —
**the function every BO allocation on this driver passes through** — rewrites the
property flags of any allocation whose flags are *exactly*
`DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT`
(`native/mesa/src/freedreno/vulkan/tu_knl.cc:44-64`). On this device that is
memory type 0 (`tu_device.cc:1804-1808`).

And memory type 0 is what DXVK allocates *everything* from. `getMemoryTypeMask`
resolves every property request to the same mask, because
`determineMemoryTypesWithPropertyFlags` builds a `sysmemMask` that is always
empty here — no type lacks `DEVICE_LOCAL` — and falls through to
`m_memTypesByPropertyFlags[i] = sysmemMask ? sysmemMask : vidmemMask`
(`native/dxvk/src/dxvk/dxvk_memory.cpp:2034`). `allocateMemory` then iterates
`bit::BitMask(memoryTypeMask)` lowest bit first
(`dxvk_memory.cpp:689`). Type 0 wins every time, for render targets, depth
buffers, textures, vertex and index buffers alike.

The consequence lands in one `if` in the kernel interface
(`native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc:374-382`):

```c
   if (mem_property & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) {
      if (mem_property & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) {
         req.flags |= KGSL_MEMFLAGS_IOCOHERENT;
      }
      req.flags |= KGSL_CACHEMODE_WRITEBACK << KGSL_CACHEMODE_SHIFT;
   } else {
      req.flags |= KGSL_CACHEMODE_WRITECOMBINE << KGSL_CACHEMODE_SHIFT;
   }
```

With the option off, every buffer object in the process is write-combine. With
it on, every buffer object in the process is IO-coherent write-back — including
the colour and depth attachments that the tiler streams out at full rate, which
are the largest single consumer of DRAM bandwidth in any frame and are never
read by the CPU at all.

**Expected size: unknown sign, plausibly ±10–30%.** The upside is what FEX
argues for and it is real: emulated x86 stores become store-releases, and a
store-release to write-combine memory stalls. The downside is that render-target
writes now go through the coherent, snooping path on every transaction, and
nothing about a render target benefits from that. Which effect is larger is
exactly the question, and **no measurement of it exists anywhere in this repo** —
`docs/ARCHITECTURE.md` says so in as many words ("**Unmeasured:** it needs a real
x86-64 D3D title") and so does `SessionEnvironment.kt:1735-1736`.

**The cheapest experiment.** It is the one high-value knob here that is *not*
reachable without a build, because it sits in `RESERVED_SESSION_ENV`
(`SessionEnvironment.kt:501-504`) on the argument that a subgroup width and a
memory type are not preferences. The build is Kotlin only — no native component
rebuilds — so the loop is `SessionEnvironment.kt:1737` → `./gradlew
installSideloadDebug` → one session each way, with §7's shell-loop sampling
method (`gpubusy` plus `gfx-stats.json`, once a second, from a shell under the
app's uid) rather than the app's own sampler.

If it is going to be measured more than once, the honest change is to move it
out of `RESERVED_SESSION_ENV` so a container can carry it — which is also the
right shape, because the FEX rationale is per-title (it applies to x86-64 guests)
and the cost is per-allocation.

**A note on the coupling.** DXVK's staging buffers request
`HOST_VISIBLE | HOST_COHERENT` with no `HOST_CACHED`
(`native/dxvk/src/dxvk/dxvk_staging.cpp:37,47`), so on paper every staging upload
is a CPU write into uncached memory. On this stack the override already fixes
that, which is why `d3d11.cachedDynamicResources` has nothing left to do
(§2). **If this candidate concludes the override should come off, that finding
reverses and `d3d11.cachedDynamicResources` becomes the scoped replacement** —
`vi` for vertex and index buffers, `c` for constant buffers, letters parsed at
`native/dxvk/src/d3d11/d3d11_options.cpp:49-58`, reachable through the
`dxvkconfig` family. Decide them together, not separately.

### 3. Find out whether vkd3d is silently dropping UBWC on D3D12 render targets

**Mechanism.** vkd3d has one path that sets `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT`
and then deliberately throws the format list away
(`native/vkd3d/libs/vkd3d/resource.c:373-381`):

```c
    if (list->format_count < 2)
        return false;
    *vk_flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    /* Too many formats to expect compression, just use plain mutable. */
    if (list->format_count == ARRAY_SIZE(list->vk_formats))
        list->format_count = 0;
```

The reasoning is a desktop fact — a fully mutable image loses DCC on AMD and
NVIDIA anyway, so declaring the list buys nothing. On a8xx it is the opposite
way round: `ubwc_all_formats_compatible = True`
(`freedreno_devices.py:880`) puts Turnip on the branch at `tu_image.cc:658-662`
that *keeps* UBWC if a format list is present, and `format_list_ubwc_possible`
hard-fails with no list (`tu_image.cc:506-507`). So a D3D12 resource whose
compatibility list happens to be full arrives as plain mutable and loses lossless
bandwidth compression on a part where it did not have to.

**Expected size: entirely unknown, because the frequency is unknown.** It affects
D3D12 titles only, and only resources whose format family fills
`VKD3D_MAX_COMPATIBLE_FORMAT_COUNT` (`native/vkd3d/libs/vkd3d/vkd3d_private.h:1116`).
If that is a handful of images per frame it is nothing; if it is the main colour
target it is large, because UBWC is compressing the single biggest write stream
in the frame.

**The cheapest experiment: count it, do not fix it.** Two free instruments say
how often it happens. `TU_DEBUG=perf` is in the runtime allowlist
(`tu_util.cc:74`) and emits a `perf_debug` line per image whose UBWC was
disabled; and the `render_pass` trace's `ubwc` string from candidate 1 says
which attachments are compressed while the game runs. **Close this candidate if
the count is zero**, which is the likely outcome and is worth five minutes to
establish before anyone writes a patch.

There is a second unknown behind it: whether attaching the full list would
actually help. `format_list_ubwc_possible` carries
`/* TODO: limit based on compatiblity class */`, so it is not obvious the list
would survive its own check. Establish the frequency first.

### 4. Re-ask the GMEM question with `tilingDisableReason` instead of frames per second

**What is settled.** `docs/OPTIMIZATION.md` §7 measured `TU_DEBUG=gmem` twice —
92.2 and 90.2 fps against a 98–118 baseline — and closed it. `docs/ARCHITECTURE.md`
records why the default is what it is: Turnip ships a compiled-in profile keyed
on `engineName`, `<engine engine_name_match="DXVK|vkd3d">` setting
`tu_autotune_algorithm = prefer_sysmem`
(`native/mesa/src/freedreno/vulkan/00-turnip-defaults.conf:13,36-41`), so every
D3D title on this stack is biased away from tiles before any measurement
happens, and the part's 2 MiB of GMEM (`freedreno_devices.py:1582`) goes mostly
unused. Both of those stand. This candidate does not reopen them.

**What is not settled is *why* each pass is on sysmem**, and that changes what
the lever is. `use_sysmem_rendering` (`tu_cmd_buffer.cc:1366-1451`) has eleven
exits and only the last defers to autotune. One of the others is worth singling
out (`tu_cmd_buffer.cc:10063-10066`):

```c
      if ((srcStage & ~framebuffer_space_stages) ||
          (dstStage & ~framebuffer_space_stages)) {
         cmd->state.rp.disable_gmem = true;
         cmd->state.rp.gmem_disable_reason = "Non-framebuffer-space barrier";
```

If most passes report that string, then `TU_DEBUG=gmem` was never able to tile
them — the flag at `tu_cmd_buffer.cc:1433-1436` sits *below* the correctness
gates — and §7's negative result is a measurement of a flag that did not apply,
rather than evidence that tiling does not pay. The lever in that case is DXVK's
barrier placement, not a driver flag, and it is a different and much more
expensive piece of work.

**The cheapest experiment: read the field.** It comes free with candidate 1, in
the same trace file, on the same run. Tabulate `tilingDisableReason` by pass
count. This costs nothing beyond candidate 1 and it decides whether an expensive
avenue is open or closed.

**Do not** reach for `TU_AUTOTUNE_ALGO=prefer_sysmem` — `docs/ARCHITECTURE.md`
already records that it is a no-op asking for the state the driver is in.
`TU_AUTOTUNE_FLAGS=big_gmem` (`tu_autotune.cc:1859-1861`, GMEM for any pass with
≥10 draws) is the reachable version of "force more tiles" and is a container
param today; it inherits §7's ±10% floor, which is why the trace comes first.

### 5. `VKD3D_CONFIG=memory_allocator_skip_image_heap_clear` — a CPU memset of every suballocation, on a device where every allocation is mapped

**Mechanism.** vkd3d trusts Turnip to hand back zeroed memory, but only for
allocations it does not suballocate — `implementation_can_zero_clear_alloc`
requires `&& !suballocate` (`native/vkd3d/libs/vkd3d/memory.c:2073-2079`).
Everything else is cleared, and the clear is chosen by whether the memory is
mapped (`memory.c:512-558`):

```c
      memset(allocation->cpu_address, value, allocation->resource.size);
      /* Probably faster than doing this on the GPU and having to worry about synchronization */
```

On a discrete GPU that branch is rare, because most allocations are not
CPU-addressable. **Here it is the common case**, because every memory type is
host-visible and DXVK/vkd3d map what they allocate. So every suballocated D3D12
heap costs a full-size CPU memset through DRAM at creation.

**Expected size: it is a load-time and stutter cost, not an fps one.** The
traffic is proportional to allocation churn, so it shows up when a level streams
in, not in a steady scene. Order of magnitude: a title creating 200 MB of placed
resources pays 200 MB of CPU writes it did not need to. That is under 5 ms of
DRAM time at any plausible rate — real, but it competes with shader compilation
for the same window and will be hard to see.

**The cheapest experiment.** `VKD3D_CONFIG` composes through the `vkd3dconfig`
family (`app/src/main/java/app/vessel/core/ContainerDiagnostics.kt:647-653`), so
a word added there joins `nodxr` rather than replacing it. Two words are
available and they are not equally safe:

- `memory_allocator_skip_image_heap_clear` — forces `CREATE_NOT_ZEROED` only on
  heaps that deny buffers (`native/vkd3d/libs/vkd3d/heap.c:339-342`, whose own
  comment is *"Buffers are far more sensitive to memory clears than images"*).
  This is the half to try.
- `memory_allocator_skip_clear` — skips it everywhere (`memory.c:2079`). D3D12
  promises zeroed heaps unless the application asked otherwise, so this one can
  produce garbage in a texture and is a debugging tool, not a setting.

Neither is documented upstream: `native/vkd3d/README.md:173-177` describes only
`no_upload_hvv` and `force_host_cached` out of the twelve memory-related flags in
the table at `native/vkd3d/libs/vkd3d/device.c:1248-1307`.

### 6. `DXVK_CONFIG` line `dxvk.enableMemoryDefrag = False` — defragmenting an address space that is not scarce

**Mechanism.** Memory defragmentation exists to compact a small, precious
host-visible VRAM window so that a large allocation can still find room. DXVK
enables it by default on everything except old Intel ANV
(`native/dxvk/src/dxvk/dxvk_memory.cpp:2602-2618`), and it picks its *aggressive*
tolerance here — `pagesUsed / 8u` rather than `/3u` — because the branch is keyed
on the heap carrying `VK_MEMORY_HEAP_DEVICE_LOCAL_BIT`
(`dxvk_memory.cpp:2375-2378`), which on this part every heap does, there being
only one. So this device gets the settings meant for scarce VRAM while having
15.6 GB of ordinary RAM.

Defragmentation is `vkCmdCopyBuffer` and `vkCmdCopyImage` of live resources.
Every byte of it is bandwidth spent to solve a problem this device does not have.

**Correction, and it closes this entry: defragmentation already does nothing
here, so turning it off wins nothing.** The paragraphs above are right about the
tolerance and about the motivation, and wrong about the consequence, because
they stop one step short of asking *which allocations are eligible*. Only these
are:

```c
if (!(allocationInfo.properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) && allocationInfo.resourceCookie)
    allocation->m_flags.set(DxvkAllocationFlag::CanMove);
```

`dxvk_memory.cpp:1412-1413`, and identically at `:1468-1469` for dedicated
allocations. `CanMove` is set **only when the allocation is not host visible**,
and the relocation scan skips anything without it (`:2506`). DXVK states the
reason in its own comment at `:2589` — *"We cannot do anything about mapped
allocations since we rely on pointer stability there."*

On this part that eliminates almost everything. The device reports four memory
types and three of them are host visible:

```
Type 0: DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT
Type 1: DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT | HOST_CACHED
Type 2: DEVICE_LOCAL | HOST_VISIBLE | HOST_CACHED
Type 3: DEVICE_LOCAL | LAZILY_ALLOCATED
```

Only type 3 can ever carry `CanMove`, and it holds transient attachments that
are lazily allocated precisely so they never occupy real memory. So the entry
guard at `:2375` — the aggressive `/8` tolerance this entry was built on — is
reached with nothing eligible behind it.

What `dxvk.enableMemoryDefrag = False` actually saves is one bookkeeping walk per
500 ms (`performTimedTasks`, `:2551`) and **zero GPU copies**, because there are
no GPU copies. It is not a bandwidth lever on this hardware.

**Kept rather than deleted**, because the mechanism is real on hardware with a
small host-visible window, and because the reasoning that produced the wrong
conclusion — "the settings are wrong for this device, therefore they cost
something" — is the kind that will be produced again. The settings *are* wrong
for this device. They are also inert.

### 7. Retry DRI3 presentation — the one place with a measured 3.5× sitting unused

**Status — this candidate has been taken, and it is now §9's problem.**
`ZERO_COPY_PRESENT` is `true` as of `4e8dad3`
(`app/src/main/java/app/vessel/core/SessionEnvironment.kt`), so DRI3 is the
shipped default and the software path is the fallback rather than the baseline.
It went on, off in `85a8f4d`, and on again: what sent it back was the X server's
own present copy at **19.1 ms per frame**, which is a cost this candidate never
counted because every number below was taken with `tools/gfx/x11present.c`, a
native client whose measurements never included that copy. **§9 is the whole of
that story** — where the 19.1 ms goes, what has been ruled out, and the two log
lines that settle the rest. Everything in the rest of this section stands as
written and as measured; it is simply no longer the last word.

`MESA_VK_WSI_DEBUG` is in `DIAGNOSTIC_SESSION_ENV` and has a declared row in the
`mesa` family, so a container still picks its present path from Diagnostics and
picks it back if the window comes up black — the row now overrides *leftward*,
to *Copy each frame (sw)*, which is what makes moving the default cheap to be
wrong about.

**The failure the constant rested on is fully explained, and it was stale by
104 minutes.** This document used to repeat the KDoc's "predates the
`FenceFromFD` fix". It predates more than that. The failure is commit `5cdb54a`,
2026-08-10 11:29; `186390f` at 13:13 the same day is titled *"implement XFIXES —
the actual cause of the DRI3 failure"*, and its mechanism matches the recorded
symptom exactly — Mesa creates an XFIXES region per swapchain image on the DRI3
path unguarded, and against a server that does not advertise XFIXES libxcb tears
the connection down **client-side** with `XCB_CONN_CLOSED_EXT_NOTSUPPORTED`
before a byte is sent, which is why "X connection broken" appeared with no X
protocol error. `b418feb` at 14:40 records *"DRI3 present works"* with the
numbers below. The constant was simply never revisited.

Both paths measured on this device, 300 frames at 1280×720:

| path | mean | p50 | p95 |
|---|---|---|---|
| `dri3` | 0.602 ms | 0.505 ms | 1.837 ms |
| `sw` | 2.143 ms | 1.580 ms | 4.625 ms |

**The bytes.** On the `sw` path, `wsi_common_x11.c:2133-2134` routes presentation
to `x11_present_to_x11_sw`, which is `xcb_put_image` and nothing else
(`wsi_common_x11.c:1906-1944`). Three consequences at 720p, where a frame is
3.686 MB:

1. Because `wsi_cpu_image_needs_buffer_blit` is true, each present does a GPU
   `vkCmdCopyImageToBuffer` of the whole frame — a tiled read plus a linear
   write (`SessionEnvironment.kt:1664-1667`).
2. `xcb_put_image` pushes those 3.686 MB through a Unix socket, which is two
   kernel copies, and the X server writes them into the window's `Drawable`.
3. **And because the software path never uses the Present extension, the
   compositor's zero-copy texture path never engages.** `PresentExtension` and
   `DRI3Extension` are what swap a window's backing to a `GPUImage`, whose
   `updateFromDrawable` is a no-op
   (`app/src/main/java/com/winlator/renderer/GPUImage.java:46-49`;
   `app/src/main/java/com/winlator/README.md:526-548`). Without them the window
   keeps a plain `Texture` and pays a full-window `glTexSubImage2D` every
   composited frame (`Texture.java:152`).

Adding those up is arithmetic, not a measurement: roughly 25–30 MB of DRAM
traffic per presented frame at 720p that the DRI3 path does not spend. **The
0.602-vs-2.143 ms figure above is the number to quote, not this one.**

**The cheapest experiment, and it is no longer a rebuild.** Diagnostics → add a
row → type `MESA_VK_WSI_DEBUG` → *Zero-copy (DRI3)*, then launch. Turning it back
is the same dropdown. `tools/gfx/run-presentbench.sh` still fixes the variable
from outside the app and runs both paths in one session without a game, and is
the right instrument for a *number*; the row is the one that answers the question
this item is actually blocked on.

**What that question is.** Everything measured so far is
`tools/gfx/x11present.c` — a native Vulkan client making a swapchain against
this X server, in this app's process. A session is not that: it is Wine's
`winex11` holding the X connection, vkd3d or DXVK driving the swapchain, and
Turnip reached through win32u's ICD under FEX. **Joining the two halves has never
been run.** Reading both sides says it should negotiate — the server implements
every DRI3, Present, XFIXES and SYNC request Mesa issues at the versions it
answers, and `xcb_dri3_open` answering `nfd = 0` is *handled*, not fatal
(`wsi_common_x11.c:144-147` returns −1, `:173-175` then assumes a compatible
local device) — but reading is not running.

**Ranked seventh, not first, deliberately.** `docs/TODO.md:472-477` records that
present-path work costs ~0.5 ms against frame times in the tens of milliseconds,
and §7 measured the stack GPU-bound at 95%. 1.5 ms a frame is real and it is
also not where the frame is going.

### 8. vkd3d allocates every CPU-visible texture twice — textbook UMA waste, probably rare in practice

**Mechanism.** vkd3d restricts the CPU-accessible memory domain to *buffer*
memory types, on purpose (`native/vkd3d/libs/vkd3d/resource.c:9942-9945`):

```c
    /* We don't create images in host-visible memory anymore, only buffers */
    info->cpu_accessible_domain.buffer_type_mask = buffer_type_mask & host_visible_mask;
```

So a texture created on an UPLOAD, READBACK or CPU-visible CUSTOM heap gets
`VKD3D_RESOURCE_LINEAR_STAGING_COPY` (`resource.c:4070-4082`) and is allocated
**twice** — a linear buffer in the application's heap, plus a second, private
DEFAULT allocation for the real image (`resource.c:4230-4236` for committed,
`4527-4549` for placed). Every `WriteToSubresource` is then a CPU copy into the
buffer followed by a `vkCmdCopyBufferToImage2` (`resource.c:2492-2584`,
`memory.c:414`). On this device the image's memory is host-visible anyway and
Turnip supports linear-tiled host-visible images, so both the second allocation
and the copy are pure overhead.

**Expected size: probably near zero in practice.** D3D12 titles overwhelmingly
upload through buffers and `CopyTextureRegion` rather than placing textures on
UPLOAD heaps; `WriteToSubresource` is a rarely-used API. This is listed low and
listed at all for one reason: it is the most obvious UMA waste in the tree, it
looks like a large win, and the next person to find it should find this entry
first. There is no flag; confirming it needs a `VKD3D_DEBUG` trace of
`WriteToSubresource` call frequency, and fixing it needs a vkd3d patch.

### 9. Do not turn on `dxvk.zeroMappedMemory`

Default false (`native/dxvk/src/dxvk/dxvk_options.cpp:19`). Setting it adds a
full memset of every chunk at map time (`dxvk_memory.cpp:1702-1703`) and another
on free (`dxvk_memory.cpp:1496`). `native/dxvk/dxvk.conf:358-366` describes it as
drastically increasing CPU overhead. It is an application-bug workaround, and on
a device where every allocation is mapped it is the worst possible place to
enable it. Recorded so it is not tried.

---

## 5. Resolution and format, in bytes

`docs/OPTIMIZATION.md` §7 already closes on the right answer — render resolution
is the lever no experiment was needed for — and gives the 1280×720 → 960×540
figure (44% fewer fragments). This section adds the axes that document does not
cover, and gives them as ratios rather than absolute rates, because §3 explains
why no absolute rate is measurable here.

**Per full-screen attachment, one frame:**

| format | 1280×720 | 1920×1080 |
|---|---|---|
| 8-bit RGBA / BGRA, or D32 / D24S8 | 3.69 MB | 8.29 MB |
| R11G11B10 or R10G10B10A2 HDR target | 3.69 MB | 8.29 MB |
| RGBA16F HDR target | 7.37 MB | 16.6 MB |
| 2× MSAA | ×2 before resolve | ×2 before resolve |
| 4× MSAA | ×4 before resolve | ×4 before resolve |

1920×1080 is exactly **2.25×** the pixels of 1280×720, so every attachment,
every fragment and every byte of attachment traffic scales by that factor. That
multiplier is exact and needs no measurement; it is why the resolution dropdown
is the first parameter in the manifest.

**Choosing RGBA16F over R11G11B10 for an HDR target doubles that target's
traffic**, and on a bandwidth-bound tiler it also doubles the tile store. This is
the game's choice, not the stack's, and neither DXVK nor vkd3d will second-guess
it — but it is the reason two titles at the same resolution can be a factor apart.

**sRGB is free.** Adreno's texture and output units convert in hardware; an
sRGB8 target and a UNORM8 target are the same bytes.

**MSAA is not free and does not tile away here.** With `prefer_sysmem` in force
for every D3D title (§candidate 4), a multisampled target is stored to DRAM at
full sample count before resolve, rather than being resolved out of GMEM. This is
one of the few cases where GMEM would be worth a great deal, and it is also one
of the cases the autotune hard-forces to GMEM anyway when it can
(`tu_autotune.cc:1810-1817`).

**The compositor's own cost does not scale with `display.resolution` at all, and
that is worth knowing before anyone lowers the dial expecting it to.** The
panel is 2780×1264 (`app/src/main/java/app/vessel/core/WineLaunch.kt:299-301`)
and `XServerView` takes the surface at its natural size — there is no
`setFixedSize` anywhere in the tree. SGSR runs when the magnification exceeds
1.02 (`com/winlator/renderer/GLRenderer.java:427-448`), so at the default 1280×720
it reads a 3.69 MB texture and writes **14.06 MB** to a full-panel framebuffer,
every composited frame, regardless of what the guest rendered. At a 60 fps cap
that is roughly 1.06 GB/s that lowering `display.resolution` does not touch.

Whether that is worth reclaiming is a real trade and not obviously a win:
`holder.setFixedSize` at the guest's resolution would hand the upscale to the
display controller's own scaler, removing the SGSR pass entirely — and removing
SGSR's picture with it, which is the thing SGSR exists for. Recorded as a
measured cost with an unattractive fix, not as a candidate.

---

## 6. Dead ends

**MIT-SHM for the software present path is structurally unavailable, and the
zero attaches observed in `app/src/main/java/com/winlator/README.md:509-521` have
a cause.** Mesa only asks for the extension when
`wants_shm = wsi_dev->sw && !(WSI_DEBUG & WSI_DEBUG_NOSHM) &&
wsi_dev->has_import_memory_host` (`native/mesa/src/vulkan/wsi/wsi_common_x11.c:251`),
and `has_import_memory_host` is set from `EXT_external_memory_host`
(`native/mesa/src/vulkan/wsi/wsi_common.c:173-174`). **Turnip does not implement
that extension** — a grep for `external_memory_host` across
`native/mesa/src/freedreno/` returns nothing. So `has_mit_shm` is false for
reasons that have nothing to do with Vessel's X server, and no amount of server
work will change it. This closes the obvious middle ground between the `sw` path
and DRI3: there isn't one, and the choice really is between candidate 7 and the
status quo.

**`VKD3D_CONFIG=no_upload_hvv` and `small_vram_rebar` do nothing useful here.**
The first forces the UPLOAD heap back to non-device-local memory
(`native/vkd3d/libs/vkd3d/resource.c:9672-9676`) — which on a device with no
non-device-local memory resolves to the same types while discarding the
correctly-detected topology. The second adjusts a ReBAR size threshold in a block
that is skipped entirely on UMA (`resource.c:9680`).

**`VKD3D_CONFIG=force_host_cached` is not the scoped version of candidate 2.**
It moves the UPLOAD heap and descriptor heaps to `HOST_CACHED`
(`resource.c:9619-9624`, `9664-9670`) and disables `GPU_UPLOAD`
(`resource.c:9719-9720`) — but `tu_override_uncached_as_cache_coherent` already
makes every allocation cached regardless, so with the override on this flag can
only take capability away. Upstream documents it as a capture accelerant
(`native/vkd3d/README.md:177`).

**DXVK's memory fallback chains are all no-ops on this device and are not worth
reading as candidates.** Four sites drop `VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT`
to retry in system memory (`dxvk_memory.cpp:850-859`, `926-932`, `1032-1040`,
`1054-1060`); all four resolve to the same memory types, for the reason in
candidate 2. `DxvkAllocationMode::NoDeviceMemory` is worse than a no-op — it
clears the mask to zero (`dxvk_memory.cpp:813`, `906`, `1019`), so those
allocations fail rather than fall back. That is a robustness question, not a
bandwidth one, and it is recorded here so nobody chases it as the latter.

**`noubwc` cannot be A/B'd mid-session.** See §2. It is not in
`tu_runtime_debug_flags` (`tu_util.cc:70-77`) and UBWC is latched per image at
`vkCreateImage` (`tu_image.cc:683-685`).

**Raising `TU_GMEM` is not a way to test "would more GMEM help".** The variable
exists (`native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc:2769`) and overrides the
kernel's reported size, but the 2 MiB is physical
(`freedreno_devices.py:1582`); telling the driver there is more would produce
faults, not tiles.

---

## 7. How to run the live A/B, precisely

This is the only way to compare two rendering configurations against one scene at
one temperature, and `docs/OPTIMIZATION.md` is emphatic that nothing else is
trustworthy: three identical baseline windows in one session read 97.9 / 117.5 /
102.7 fps.

`TU_DEBUG_FILE` is set unconditionally for every container
(`SessionEnvironment.kt:1624`) and points at
`/data/data/app.vessel/files/containers/<id>/tmp/tu-debug`. Turnip watches it
with `inotify` on `IN_CLOSE_WRITE`
(`native/mesa/src/util/os_file_notify.c:162-201`) from a dedicated thread, so
there is **no per-frame or per-submit cost** — the file is read only when
something writes and closes it. Each write replaces the whole flag set, and the
new value takes effect at the next evaluation of `TU_DEBUG(x)` anywhere in the
driver (`tu_util.h:28`, a relaxed-acquire atomic load).

```sh
# one shell, under the app's own uid, while the game is running
C=/data/data/app.vessel/files/containers/<id>/tmp
adb shell run-as app.vessel sh -c "printf ''         > $C/tu-debug"   # baseline
adb shell run-as app.vessel sh -c "printf 'gmem'     > $C/tu-debug"   # force tiles
adb shell run-as app.vessel sh -c "printf 'sysmem'   > $C/tu-debug"   # the null control
adb shell run-as app.vessel sh -c "printf 'nolrz'    > $C/tu-debug"
```

**Only these nineteen flags apply** (`tu_util.cc:70-77`): `nir`, `nobin`,
`sysmem`, `gmem`, `forcebin`, `layout`, `nolrz`, `nolrzfc`, `perf`, `flushall`,
`syncdraw`, `rast_order`, `unaligned_store`, `log_skip_gmem_ops`, `3d_load`,
`fdm`, `noconcurrentresolves`, `noconcurrentunresolves`, `nobinmerging`. Anything
else in the file is silently ignored for the rest of the session.

**Two traps.** First, a flag that is *also* in the container's `TU_DEBUG` becomes
permanently stuck on — it lands in `start_debug` and is re-ORed back on every
notification (`tu_util.cc:113-115`), and Turnip warns about exactly this at
`tu_util.cc:146-150`. Vessel always appends `startup` to `TU_DEBUG`
(`SessionEnvironment.kt:2049-2068`), which is not a runtime flag and so is
harmless, but a container that has typed `sysmem` into the `turnip` family cannot
then A/B it. Second, sample with a shell loop reading
`/sys/class/kgsl/kgsl-3d0/gpubusy` and the container's `gfx-stats.json` once a
second, not with the app's own metrics screen — `SessionMetricsRecorder` drops to
`SLOW_INTERVAL_MS` when no graph is on screen, which is the mistake §7 records
making.

And run the null. `sysmem` should land on the baseline, because it asks for the
state the driver is already in (`00-turnip-defaults.conf:40`). If it does not,
the window is not comparable and nothing else measured in it means anything.

---

## 8. What could not be determined

- **Whether `tu_override_uncached_as_cache_coherent` helps or hurts.** No
  measurement exists in this repo or upstream. Its scope — every buffer object,
  not just uploads — is established above from source; its cost is not.
- **Whether the −21% recorded for `noubwc` in `docs/OPTIMIZATION.md` §7 was a
  UBWC effect at all.** The mechanism says it cannot have been, if the flag was
  written to `TU_DEBUG_FILE`; the document does not record which way it was set,
  so the result is unattributable rather than wrong.
- **How often vkd3d's "too many formats" branch fires in a real title**, and
  therefore whether candidate 3 is worth anything. Candidate 1's trace answers
  this for free and it has not been run.
- **Whether attaching a full format list would keep UBWC on a8xx even if it did
  fire.** `format_list_ubwc_possible` carries a `TODO: limit based on
  compatiblity class` (`tu_image.cc:496-519`), so its behaviour with a large list
  is not something reading the source settles.
- **The actual DRAM bandwidth this device is consuming, at any moment, under any
  workload.** §3: there is no counter, for the app or for the shell. Every
  absolute byte figure in this document is arithmetic from a resolution and a
  pixel size, and every one is labelled.
- **Whether IO-coherent buffer objects cost GPU write bandwidth on this SoC**,
  which is the mechanism candidate 2 turns on. This is an interconnect property
  that no interface here exposes, so it can only be inferred from the end-to-end
  A/B, not measured directly.
- **Whether the DRI3 swapchain mapping is actually cached.** §9.1 measured that
  the device has no uncached heap, which kills the write-combine theory; §9.3's
  positive claim that `system` is therefore cached rests on upstream and CLO
  source that is not this vendor build. `tools/bench/heapbench.c` measures it
  directly and has not been run; §9.7.
- **Which of the three dma-buf allocation paths Turnip takes**, which is the one
  remaining way the mapping could be uncached after all. Nothing in the driver
  logged it before `patches/mesa/0008`, and no `adb shell` command can answer it
  because the X server runs as `untrusted_app` and the shell does not; §9.4.
- **Where the DRI3 present copy's 19.1 ms actually goes** — the cache
  maintenance, the copy, or something that blocks. The single timer that
  produced that number could not attribute it, and §9.6's arithmetic accounts
  for barely a fifth of it under any of the three. `885da17` splits it and adds
  a floor; neither has been captured on a device.
- **Whether the row-band worker pool in `f1f8de0` moves that number at all.**
  Its stated justification is now refuted — §9.2 — and on a cached buffer a
  split copy buys little. It is kept because it is joined, correct and inert
  below its threshold, not because it is known to help.
- **Whether the copy's *destination* is cached.** It is an AHardwareBuffer
  allocated with `AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN` and no GPU usage bit,
  then bound as an EGLImage; `CPU_WRITE_OFTEN` is a request for a cached mapping
  and not a guarantee of one, and nothing on the device reports what gralloc
  gave. §9.6(b).
- **Whether `qcom,system` and `system` differ in any way that matters.** They
  may be one heap under two names or two heaps; §9.3. No Turnip heap change is
  justified until `heapbench` shows a difference between them.

---

## 9. Where the DRI3 present copy's 19 ms goes

Its own section rather than a candidate under §4, because it is not a candidate:
DRI3 is the shipped default as of `4e8dad3`, and this is a cost the stack is
paying right now.

    Present copyArea x6720 mean=19114us max=385490us last=69539us 1280x720

3.5 MB in 19.1 ms is **~154 MB/s**. That is the figure the whole investigation
hangs off, and it is a real measurement — the only one in this area taken
through the guest stack rather than through `tools/gfx/x11present.c`.

**This section's shape is deliberate: it is mostly a dead hypothesis and the
evidence that killed it.** That hypothesis was load-bearing — a shipped default
and a worker pool were both built on it — and it was killed by one command. The
live candidates that replace it are at §9.6 and none of them is proven.

### 9.1 What the device says, measured

Read off the phone on 2026-08-16 — motorola signature, SM8845, Android 16,
kernel `6.12.38-android16-5-gdda2539c405d-ab14915528-4k`:

```
$ ls /dev/dma_heap/
qcom,display
qcom,qseecom
qcom,qseecom-ta
qcom,secure-non-pixel
qcom,secure-pixel
qcom,system
system

$ ls /dev/dma_heap/ | grep -i uncach
(nothing)
```

**There is no uncached heap on this device.** No `system-uncached`, no
`qcom,system-uncached`, nothing matching the string at all. That is the measured
fact this section turns on, and everything below is either a consequence of it
or explicitly labelled as inference.

### 9.2 The write-combine hypothesis is dead

`85a8f4d` turned DRI3 off, `f1f8de0` built a row-band worker pool, and
`PresentExtension`'s comment was rewritten — all three on the reading that
154 MB/s is write-combine read speed and therefore the exporter had handed out
an uncached mapping. §9.1 removes its foundation: **there was never an uncached
heap for Turnip to have allocated from.** Turnip picks its exporter purely by
which node it opens (`bo_init_new_dmaheap`,
`native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc:173-200`, path at `:2706`) and
passes no cacheability request — `heap_flags` is left zero. There is no heap on
this device it could have opened that would have given it a write-combine vma.

Recorded rather than deleted, because it was reasonable and will otherwise be
re-derived: 154 MB/s really does sit inside the published write-combine read
band — 234 MiB/s for a non-coherent dma-buf mapping on a Zynq/ARM part
(<https://lkml.rescloud.iu.edu/2111.3/05514.html>), 323 MB/s vectorised and
91 MB/s scalar for x86 write-combine memory
(<https://fgiesen.wordpress.com/2013/01/29/write-combining-is-not-your-friend/>).
No ARM64 write-combine read benchmark was found. Inferring the memory type from
a throughput figure was the right instinct and it produced the wrong answer,
which is the argument for §9.7's direct measurement over any amount of further
reading.

### 9.3a It is not an inference any more — `heapbench` ran, 2026-08-16

§9.3 below is kept because its reasoning is sound and worth having, but its
conclusion is now measured rather than inferred, and §9.6's arithmetic is
measured too. `tools/bench/heapbench.c` on the device, uid 2000
(`u:r:shell:s0`), 3.52 MB, 10 repeats:

```
control malloc->malloc     warm= 45949.3 MB/s  cold= 24944.5 MB/s
heap qcom,system              open FAILED errno=13 (Permission denied)
heap system                   warm= 24359.4 MB/s  cold= 25891.9 MB/s  ratio_vs_control=1.038
                              syncStart=0.000 ms  syncEnd=0.000 ms
                              bracketed(start+copy+end) cold=0.142 ms
heap qcom,display             open FAILED errno=13 (Permission denied)
```

**Three results, in descending order of what they settle.**

1. **The mapping is cached. Measured, not deduced.** `ratio_vs_control=1.038`
   against a `malloc` buffer in the same process leaves no room for it to be
   anything else; a write-combine mapping reads one to two orders of magnitude
   slower. §9.2's obituary for the write-combine theory is now backed by a
   stopwatch as well as by `ls`.

2. **The `DMA_BUF_IOCTL_SYNC` bracket is free here — 0.000 ms on both halves.**
   That was the leading candidate after write-combine died. **It survives only
   in the narrow form given below**, because this buffer has no importer
   attached and `qcom_sg_dma_buf_begin_cpu_access` walks every attachment. The
   real swapchain buffer has KGSL attached. So this measures the floor of the
   sync cost, not the swapchain's, and the gap between them is exactly what the
   `syncIn=`/`syncOut=` split in the present log will report.

3. **The entire operation costs 0.142 ms cold.** Sync in, copy 3.5 MB, sync out.
   The measured present copy is **19.1 ms — 135x that.** §9.6's estimate said
   the parts sum to about a fifth of the whole; the measurement says they sum to
   about **one hundred and thirty-fifth** of it. The gap is far larger than that
   section assumed, and it is not a bandwidth gap, a page-protection gap, or a
   cache-maintenance gap.

**What this leaves.** Candidate (c) in §9.6 — *something blocks* — is now the
only one standing, and `max=385490us` against a 0.142 ms floor is its shape.
Two things this run cannot see, both of which matter and neither of which is a
reason to doubt the three results above:

- **It ran on an idle phone.** The 19.1 ms was measured with Requiem driving
  every core through FEX. Contention for a core, not for memory, is a candidate
  this benchmark is constitutionally unable to test.
- **It ran as `shell`, not as the app.** `qcom,system` and `qcom,display` both
  refused `open` with `EACCES`, so no cross-heap comparison was possible from
  this domain. Cacheability is a property of the heap's `mmap` op and does not
  depend on the caller, so result 1 transfers to the app; nothing else about
  those two heaps was learned.

`min` was added to the present sampler alongside `mean` and `max` for this
reason: a mean of 19.1 ms with a 385 ms tail cannot distinguish "steadily 19 ms"
from "usually a millisecond, occasionally four hundred". Against a 0.142 ms
floor, the second is now much the likelier, and `min` says so in one line.

### 9.3 "Cached" is the surviving inference, and it is an inference

Two independent mechanisms both predict a cached, writeback mapping for
`/dev/dma_heap/system`, and **nothing here distinguishes which one this kernel
uses** — which does not matter for the conclusion, since they agree:

- **Mainline.** On a 6.12 Android Common Kernel, `system` is mainline's
  `drivers/dma-buf/heaps/system_heap.c`, whose `system_heap_mmap()` never
  touches `vma->vm_page_prot`. Cached, writeback.
- **Qualcomm downstream.** On the CLO trees the QCOM dma-heap module registers
  `qcom,system` and adds `system` as an alias over the same heap object —
  `qcom_system_heap_create("qcom,system", "system", false)` in
  `drivers/dma-buf/heaps/qcom_dma_heap.c`, where the third argument is
  `uncached`. `qcom_sg_mmap()` applies `pgprot_writecombine` **only**
  `if (buffer->uncached)`. With `uncached = false`: cached, writeback.

That the device lists *both* `qcom,system` and `system` is consistent with
either reading — the alias, or mainline's heap sitting beside Qualcomm's — so
the listing does not settle it.

**Label this correctly: upstream and CLO source has been read; this vendor
build's has not.** Nobody has confirmed which heap implementation answers to
`system` on this handset, and a vendor is free to change it. The claim "the
mapping is cached" is strongly supported and unproven. §9.7 is how it stops
being an inference.

Two further points that are settled regardless:

- **There is no flag to ask with.** AOSP: *"DMA-BUF heaps don't support heap
  private flags"*, and the ION translation is `ION_FLAG_CACHED` set →
  `Alloc("system")`, unset → `Alloc("system-uncached")`, *"the cached and
  uncached system heap variants are separate heaps"*
  (<https://source.android.com/docs/core/architecture/kernel/dma-buf-heaps>).
  Cacheability is which heap you open, and on this device there is only one to
  open.
- **`CONFIG_QCOM_DMABUF_HEAPS_SYSTEM_UNCACHED` is absent from
  `pineapple_GKI.config`**, the reference config for a neighbouring part
  (SM8650). That is corroboration for §9.1, not evidence about this device,
  which is a different SoC on a newer kernel. Sources:
  <https://raw.githubusercontent.com/LineageOS/android_kernel_qcom_sm8650/lineage-22.2/drivers/dma-buf/heaps/qcom_dma_heap.c>,
  <https://raw.githubusercontent.com/LineageOS/android_kernel_qcom_sm8550/lineage-21/drivers/dma-buf/heaps/qcom_sg_ops.c>.

**Do not switch heaps speculatively.** `qcom,system` is not known to be
equivalent to `system` — it may carry different pooling, different secure-world
semantics, or a different allocator — and swapping Turnip onto it on the theory
that it might be faster would be exactly the move that produced this section.
The evidence that would justify it is a throughput or sync-cost difference
between the two, measured on the same device in the same process; `heapbench`
(§9.7) benchmarks every non-secure heap it finds precisely so that question is
answered rather than assumed. If the two read at the same speed, there is
nothing to switch to.

### 9.4 The one remaining way it could be uncached, still unconfirmed

Turnip's *ION* fallbacks pass `.flags = 0` — **without** `ION_FLAG_CACHED` — in
both `bo_init_new_ion` (`tu_knl_kgsl.cc:203-227`) and `bo_init_new_ion_legacy`
(`:229-271`). `ION_FLAG_CACHED` is defined in the vendored
`src/freedreno/vulkan/ion/ion.h:34` and used nowhere in the tree. Under ION,
no `ION_FLAG_CACHED` does mean a write-combine mapping. So if this device took
an ION path, 154 MB/s is explained after all and §9.2 does not apply to it.

This is unlikely — ION is gone from 6.12 kernels and `/dev/dma_heap/` plainly
exists — but "unlikely" is how the last hypothesis got here. Two things make it
cheap to close:

- `heapbench` prints whether `/dev/ion` exists at all.
- `patches/mesa/0008` prints which path Turnip actually took, **from inside the
  app's own process**, which is the part no `adb shell` command can answer:
  the X server runs as `untrusted_app` and the shell does not, so an `open` that
  succeeds for one may be refused for the other. The enum cannot answer alone —
  `TU_KGSL_DMA_TYPE_ION_LEGACY` is 0 (`tu_device.h:73-78`) and the physical
  device comes from `vk_zalloc`, so "neither node opened" and "legacy ION" are
  the same value, which is why that patch prints `dma_fd` beside the type.

### 9.5 KGSL is ruled out, with a source reference rather than a belief

The importer has no say: `vm_page_prot` is set by the exporter's `.mmap` op via
`dma_buf_mmap`, and KGSL's `kgsl_setup_dma_buf()` only attaches and maps the
attachment. Its own view is recorded in a comment in `_gpuobj_map_dma_buf()` —
*"DMA BUFS are always cached so make sure that is reflected in the memdesc"* —
followed by `KGSL_CACHEMODE_WRITEBACK`. KGSL's `pgprot_writecombine()` calls are
in `kgsl_mmap()`, which is the mapping produced by `mmap`ing `/dev/kgsl-3d0`: a
different vma, driven by the memdesc's cache mode, and nothing to do with the X
server's mapping of the dma-buf fd.

### 9.6 What is left — and the arithmetic fits none of it

If the mapping is cached, 19.1 ms needs a new explanation, and the honest
statement is that **the sum of the best available estimates for every part of
that copy is about a fifth of it.** Order-of-magnitude, at 1280x720:

| term | estimate | basis |
|---|---|---|
| memcpy 3.5 MB, cached → cached | 0.4–1.0 ms | 4–9 GB/s, the band `tools/bench/heapbench.c`'s control measures |
| `DMA_BUF_IOCTL_SYNC` START, cached | 0.3–0.8 ms | ~57,600 cache lines at 64 B; a `dc civac` loop plus a ~900-entry scatterlist walk |
| the same on END | 0.3–0.8 ms | as above; both run per attachment, and KGSL is attached |
| `forceUpdate` → on-draw listener | ~0 | `GLRenderer.onUpdateWindowContent` is `xServerView.requestRender()`, which sets a flag and returns (`GLRenderer.java:291-293`) |
| **total** | **~1–2.6 ms** | against a measured **19.1 ms** |

Nothing here is measured; every row is arithmetic, and that is the point — it
says no combination of the mechanisms currently on the table adds up. So the
candidates are:

**(a) The sync bracket, costing far more than the arithmetic suggests.**
`qcom_sg_dma_buf_begin_cpu_access()` syncs across every attachment and
early-returns only when `buffer->uncached`, so on a cached buffer it always
does the work. If the sync path goes through an IOMMU or bounce layer per
scatterlist entry rather than a flat `dc` loop, it could be much worse than the
table's row. **The ABI offers no way to narrow it** — `struct dma_buf_sync` is a
flags word with no offset or length, so "invalidate less" is not reachable from
userspace; the choices are the whole buffer or nothing.

**(b) The copy, with one end not cached.** Ruled out as a Java artefact: it is
a native `memcpy` over `GetDirectBufferAddress` pointers
(`cpp/winlator/src/drawable.c`, `copy_pool.c`), not a `ByteBuffer` element loop.
Not ruled out at the *destination*, which is a `GPUImage`'s AHardwareBuffer,
locked once for the life of the window, allocated with
`AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN` and **no GPU usage bit at all**
(`cpp/winlator/src/gpu_image.c:53`, locked at `:108`) and then bound as an
EGLImage anyway. `CPU_WRITE_OFTEN` is gralloc's request for a cached mapping,
but a buffer allocated without a GPU usage bit and used by the GPU is exactly
where a gralloc implementation may choose otherwise. Nothing on the device
reports what it chose.

**(c) Something that blocks, rather than a bandwidth limit.** The strongest hint
is in the recorded number and had not been said out loud: `mean=19114us` with
`max=385490us` is a mean with a tail twenty times its own size in it. A `memcpy`
of a fixed size at a fixed rate has a tight distribution. Preemption of the
request thread on a device also running a game under FEX would produce this
shape, and so would a lock or a fence. **This is now directly testable**: the
sampler reports `min` as of this change, and a `min` near the table's 1–2.6 ms
with a mean at 19 proves the cost is not in the copy at all — it is in whatever
makes the bad frames bad.

**The instrument that separates all three** is `885da17`'s phase split, which
needs one session and no rebuild:

    Present copyArea x120 mean=..us min=..us max=..us last=..us 1280x720 \
        means syncIn=..us copy=..us syncOut=..us other=..us sync=LIVE

- `syncIn`+`syncOut` dominant, `sync=LIVE` → **(a)**. The row-band pool in
  `f1f8de0` is parallelising the wrong half; the work is to reduce or remove the
  sync, not to copy faster.
- `copy` dominant with the syncs small → **(b)**, or a cached mapping reading at
  154 MB/s, which would be a genuinely surprising result and gets its own entry
  rather than a shrug. Next step then is `heapbench` on the same device to see
  whether a bare heap mapping reads at that speed outside the app, followed by
  timing a copy into `malloc` instead of into the AHardwareBuffer to put the
  destination on trial.
- `min` ≪ `mean` with any split → **(c)**, and the split says which phase the
  tail lands in.
- `other` dominant → neither; but `requestRender` is non-blocking, so this would
  itself be a finding.
- `sync=REFUSED` → the bracket is latched off, the sync numbers are an absence
  rather than a measurement, and (a) is moot because no maintenance is happening.

### 9.7 Exactly what to run

Nothing in this section beyond the 19.1 ms and §9.1's heap list was measured;
the rest is source-reading and arithmetic. Three instruments close it, in order
of how much they settle per command.

**1. `tools/bench/heapbench.c` — the direct answer, and the only one that does
not depend on agreeing about which kernel is behind the name.** No sysfs file,
no `/proc` entry and no ioctl reports a mapping's memory type; the only
instrument is a stopwatch. It allocates from every non-secure heap on the
device, `mmap`s it, and times a `memcpy` out of it against a `malloc`-to-`malloc`
control of the same size in the same process — so the result is a *ratio* and no
absolute figure has to be trusted. It also times `DMA_BUF_IOCTL_SYNC` START and
END separately, which is candidate (a) measured directly, and reports warm and
cold separately because 3.5 MB fits in a system-level cache and a back-to-back
re-read would report a speed the real path never sees.

```sh
NDK=$ANDROID_HOME/ndk/27.1.12297006/toolchains/llvm/prebuilt/<host>/bin
"$NDK/clang" --target=aarch64-linux-android31 -O2 -static \
    -o /tmp/heapbench tools/bench/heapbench.c
adb push /tmp/heapbench /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/heapbench
adb shell /data/local/tmp/heapbench
```

Reading it: `ratio_vs_control` near 1.0 means cached and §9.3's inference is
confirmed; an order of magnitude below means uncached and everything above is
wrong. `syncStart`/`syncEnd` in milliseconds is candidate (a)'s row of the
table, measured. If `qcom,system` and `system` come back with the same numbers,
there is nothing to gain by switching Turnip between them — which is the
evidence §9.3 asks for before anyone does.

*Caveat on domain:* run under `adb shell` this measures the shell's access, not
the app's. Cacheability is a property of the heap's `mmap` op and does not
depend on the caller, so the throughput answer transfers; only a *refusal* would
have to be re-tested from the app's own domain.

**2. The session log, which needs no adb at all.** Two lines:

- `VESSEL-KGSL dma_type=… dma_fd=… heaps=[…]` — `patches/mesa/0008`, once per
  physical device. Settles §9.4 from inside the app's process.
- `Present copyArea … mean=… min=… max=… means syncIn=… copy=… syncOut=…
  other=… sync=…` — once per 120 presents. Settles §9.6.

**3. Kernel-side confirmation, if the two above disagree.**

```sh
# What the kernel logged while creating the heaps, alias included -- this is
# what would show `system` being registered as an alias of `qcom,system`.
adb shell dmesg | grep -i 'dma-buf\|dma_heap'

# Whether an uncached heap is even compiled in, and which system heap is.
adb shell 'zcat /proc/config.gz' | grep -iE 'QCOM_DMABUF_HEAPS|DMABUF_HEAPS_SYSTEM'

# Permissions and SELinux labels, which decide whether the app -- not the
# shell -- can open these at all.
adb shell ls -laZ /dev/dma_heap/

# Which heap a live buffer came from, if CONFIG_DMABUF_SYSFS_STATS is on.
# Turnip's swapchain BOs should read exporter_name=system, size ~3.5 MB.
adb shell 'for d in /sys/kernel/dmabuf/buffers/*; do \
    echo "$d $(cat $d/exporter_name 2>/dev/null) $(cat $d/size 2>/dev/null)"; done'
```
