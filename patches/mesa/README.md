# Mesa patches

Applied on top of the pinned `MESA_REF`, in filename order, by **both**
`build/turnip.sh` and `build/zink.sh` — `apply_patches` is keyed on the source
name (`mesa`), not the component, and those two components are built from one
checkout. So a patch here lands in both builds; each one below says which it is
for and why it is inert in the other. A patch that does not apply is a hard
error — see `apply_patches` in `build/common.sh`.

## 0001-kgsl-timeline-sync-mr39751.patch

For **turnip**. Inert in the Zink build, which compiles no freedreno code.

Native KGSL timeline-semaphore support. Mesa MR
[!39751](https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/39751),
still Draft as of 2026-08-07 and **not** present in the `turnip/gen8` branch —
verified by the absence of `kgsl_profiling_alloc` and `KGSL_TIMELINE` symbols
in the tree.

Source: `whitebelyash/freedreno_turnip-CI`, `patches/39751.diff`. It is what
distinguishes that builder's `-sync` driver variant, and the A8XX v27 notes
describe it as "required for the thing to work (VulkanMod)".

Touches `tu_common.h`, `tu_device.h`, `tu_knl_kgsl.cc`, `tu_queue.h`, and
`vk_semaphore.c`.

**Why this is here at all.** Research into the community Gen8 builds produced a
useful negative result: StevenMXZ's V33/V34 apply *no* patches — their CI runs
`build_turnip.sh` and nothing else, and the `.patch` files in that repository
are unreferenced leftovers. So a plain build of `turnip/gen8` already matches
V34; we were never behind. This patch is the one place a community builder does
something the branch does not, which makes it the only current opportunity to
be genuinely ahead rather than equal.

**If it stops applying**, the likely cause is the MR landing upstream or the
branch rebasing onto it. Check for `KGSL_TIMELINE` in
`native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc` first; if it is there, delete
this file rather than fixing the conflict.

## 0002-arm64ec-arch-detection.patch

For **zink**. Inert in the Turnip build: it is guarded entirely on
`__arm64ec__`, which no Android/NDK compiler defines.

Mesa already handles ARM64EC, but only as MSVC spells it (`_M_ARM64EC`).
`arm64ec-w64-mingw32-clang` predefines `__arm64ec__` **and** `__x86_64__` /
`__amd64__` — correctly, since EC code is x64-ABI-compatible — but does *not*
predefine `__aarch64__`. So `src/util/detect_arch.h` sets
`DETECT_ARCH_X86_64` on a target whose backend is AArch64, and everything
downstream takes the x86 path: `half_float.h` pulls in `<xmmintrin.h>`,
`bitscan.h` emits `popcnt` inline asm, and blake3 dispatches to SSE2/AVX2
entry points meson never built (it picked `blake3_neon.c` from
`cpu_family() == 'aarch64'`). The first of those is a hard compile error
inside clang's own `mmintrin.h`.

The patch extends the exclusions upstream already wrote for MSVC to the macro
clang uses, in `detect_arch.h` and `blake3/blake3_impl.h`. It is upstreamable
as-is; **delete it** once Mesa carries the `__arm64ec__` spelling. To check:

```sh
grep -rn '__arm64ec__' native/mesa/src/util/detect_arch.h
```

Two other raw `__x86_64__` tests in the tree were examined and deliberately
left alone — `util/hash_table.c:726` (`_WIN64 || __x86_64__`, choosing a
64-bit hash) and `util/xxhash.h:2317` (unaligned access permitted). Both are
true and correct statements about an ARM64EC target.

## 0003-wsi-x11-take-the-present-stalls-off-the-hot-paths.patch

For **turnip**, on the software present path. Compiles in the Zink build and is
inert there — nothing sets `defers_sw_blit_wait`.

Two stalls, both in `vkQueuePresentKHR` on the application's own thread:
`wsi_common_queue_present` waits on the software blit fence even for a backend
that only queues the frame, and `x11_present_to_x11_sw` blocks on a
`GetGeometry` reply that is sitting behind the multi-megabyte `PutImage` it
just sent on the same connection. The patch adds an opt-in
`defers_sw_blit_wait` so the backend waits immediately before it reads the
pixels, and pipelines the geometry cookie so the reply is collected one frame
later. Every backend that has not opted in keeps the existing ordering exactly.

**Unmeasured.** This shipped without a paired A/B, which is why it is still an
open item in `docs/TODO.md`. It also matters less than it did: DRI3 does not
take either path.

## 0004-x11-wsi-pseudo-drm-for-kgsl-without-zink.patch

For **turnip**, and the reason a DRI3 half exists at all. One conditional in
the top-level `meson.build`: upstream selects `with_dri_platform =
'pseudo-drm'` for KGSL only when Zink is also being built, so a Vulkan-only
Turnip got `'none'`, `HAVE_X11_DRM` went undefined, and every
`xcb_dri3_*`/`xcb_present_*` call site compiled out. Zink is the consumer
upstream had in mind; nothing under `src/vulkan/wsi/` needs it.

**Necessary but not sufficient** — see 0006, which is the other half. Between
the two, `MESA_VK_WSI_DEBUG=sw` stopped being a requirement.

## 0005-diagnostic-name-the-surface-lost-return.patch

For **turnip**. Diagnostic, and **deliberately shipped** rather than dropped
after the investigation it was written for.

`wsi_common_x11.c` has 9 sites returning `VK_ERROR_SURFACE_LOST_KHR` and 21
returning `VK_ERROR_OUT_OF_HOST_MEMORY`, and a Vulkan error names none of them.
The macros make the return say `func:line` and, for OOM, `errno`. That is what
turned four wrong guesses about the DRI3 failure into `x11_image_init:2733
errno=9` in one run, and it is why this stays in: the failures it names are
ones no other layer can name, and it costs a `fprintf` on a path that is
already returning a fatal error.

The one probe that is not on an error path prints the image backing
(`image_type`, `dma_buf_fd`, `num_planes`, `modifier`, `row_pitch`, `size`)
once per swapchain image — a handful of lines per swapchain, and the single
fastest way to tell a real dma-buf from a CPU-linear image pretending to be
one. **Delete this file** if the log noise ever costs more than that.

## 0006-wsi-compile-the-drm-image-backend-without-libdrm.patch

For **turnip**, and the actual zero-copy blocker.

`src/vulkan/wsi` is two halves. 0004 turned on `wsi_common_x11.c`'s DRI3 half;
the image backend it calls into — `wsi_common_drm.c`, which owns
`WSI_IMAGE_TYPE_DRM` — was gated on `dep_libdrm.found()` instead, and Mesa
deliberately sets `system_has_kms_drm = false` for a KGSL-only Turnip so that
nothing links libdrm on Android. **This was not a build error**:
`configure_image()` puts its DRM case behind `#ifdef HAVE_LIBDRM` and ends in
`default: UNREACHABLE()`, so with one reachable label the release compiler
drops the switch and calls `wsi_configure_cpu_image()` unconditionally. A DRI3
swapchain got CPU-linear images, and `x11_image_init` then `dup(-1)`'d its way
to `VK_ERROR_OUT_OF_HOST_MEMORY`.

The fix splits the question in two: `HAVE_WSI_DRM` ("the WSI can make dma-buf
images") stops being a synonym for `HAVE_LIBDRM` ("libdrm is linkable"), and is
defined for any pseudo-drm platform. `wsi_common_drm.c` needs the drm-uapi
headers, which are in tree, plus `drmIoctl` and four `drmDevice` helpers, which
Mesa already stubs in `src/util/libdrm.h` for exactly this situation — two were
missing and are added there.

Measured on device, 1280x720, 300 frames, `tools/gfx/run-x11present.sh`:

| path | mean | p50 | p95 |
|---|---|---|---|
| `dri3` | 0.602 ms | 0.505 ms | 1.837 ms |
| `sw`   | 2.143 ms | 1.580 ms | 4.625 ms |

A second `dri3` run measured `mean 0.555 / p50 0.487 / p95 1.088`, so treat the
p95 as the noisy figure and the ~3.5× as the result. Note this is not yet *zero*
copies end to end — the client-side copy is gone (the software path pushes
3.6 MB through `xcb_put_image` every frame), but `presentPixmap` still copies
the dma-buf into the window drawable. Removing that last one is a flip branch in
`PresentExtension`, not a Mesa change.

**Upstreamable in shape**, though upstream would want the `HAVE_LIBDRM` →
`HAVE_WSI_DRM` rename argued on its own. If it lands, this file goes.

## 0007 — deleted 2026-08-10, because the fence was measured

There used to be a patch here called
`0007-wsi-x11-no-dri3-idle-fence-for-a-server-without-FenceFromFD.patch`. It
stopped Mesa asking for a DRI3 idle fence, because Vessel's X server did not
implement `FenceFromFD` (opcode 4) and *refusing* the request was not free: the
fd that arrived with it over `SCM_RIGHTS` stayed in the connection's
ancillary-fd queue, so every later `PixmapFromBuffer` popped the previous
image's 4096-byte fence page instead of its own dma-buf, and the second present
killed the server with `SIGBUS`/`BUS_ADRERR` in `Drawable.copyArea`.

The server implements `FenceFromFD` now (vendored items 23 and 24), so the
patch was measured and then removed. Same live session, same 300 frames, back
to back:

| | mean | p50 | p95 | max |
|---|---|---|---|---|
| fence off | 0.555 ms | 0.483 ms | 1.662 ms | 1.933 ms |
| fence on | 0.565 ms | 0.487 ms | 1.667 ms | 1.877 ms |

\+0.010 ms, inside the spread of four fence-off runs from the same hour
(0.534, 0.553, 0.555, 0.558). With the patch gone and no environment variable
set at all, a run is `result=PASS` at mean 0.560 ms and the server serves three
fences, one per swapchain image.

**The count is the part that matters.** A run that asks for the fence and a run
that does not both print `result=PASS`, so the pass proves nothing;
`DRI3Extension.fenceFromFD` logs each fence it serves, and the count was 0 with
the flag unset and exactly 3 with it set, with no protocol error either way.
Without that line the table above could just as easily have been the cost of
Mesa quietly not asking.

Deleting the patch also removes a trap that was written down here before it
could be sprung: the old justification was "the idle event is already a
complete signal, because `presentPixmap` copies synchronously before sending
it", which is true only while `presentPixmap` **copies**. The flip branch
specified in `docs/TODO.md` would remove that copy. With the fence now the
default, the flip no longer has a missing-fence hazard to walk into.

## Considered and not taken

**DiskDVD `A8XX-Y` branch** — carries explicit `is_a829` branches
(`chip_id == 0x44030A20`) and claims "Adreno 829/825 full working
(Gmem/Sysmem)". It is two squashed commits across ~40 files rather than clean
cherry-picks, so it is a fork to evaluate against, not a patch to carry blind.
Worth benchmarking once the driver runs on device:
`https://github.com/whitebelyash/mesa-unified/compare/turnip/gen8...DiskDVD:A8XX-Y:A8XX.diff`

**StevenMXZ's seven `sed` edits** — they target the older `mesa-tu8` tree. On
`turnip/gen8` the `" (%s)"` string they rewrite no longer exists, the
`u_gralloc` UBWC change is already committed, and three of the seven exist only
to repair damage a fourth one causes. Neither whitebelyash nor DiskDVD uses
them.

## `0007-freedreno-a8xx-does-not-get-128-wide-waves`

**This fork enables a mode upstream disables on this chip, and it is the whole
of Requiem's GPU hang.**

`a8xx_base` in `freedreno_devices.py` sets both `supports_double_threadsize` and
`has_dual_wave_dispatch`. `freedreno_dev_info.h:235-241` says they are
alternatives — *"Dual-wave dispatch replaces THREAD128
(supports_double_threadsize) on newer devices"* — and the same comment names the
failure before anyone hit it: *"while a wave may not diverge, a pair of waves
might, for example subgroup-ops."*

That is exactly what broke. Requiem hung on a `256x1x1` compute dispatch;
breadcrumbs named the command and the shader dump showed a wave-aggregated
allocation loop whose exit depends on every lane agreeing about the wave —
ballot, exclusive scan, one lane's `atomicAdd`, `WaveReadLaneFirst` to share the
base back. Across a dual-wave pair they stop agreeing, the index stops
advancing, and the dispatch never retires. 33 of the 2,867 shaders one session
compiled carry the same loop.

**The shader is size-agnostic, so "a game assuming 64 lanes" is not the
diagnosis.** It hardcodes no width. The driver is wrong, not the title. That
distinction is why this is a patch here rather than a per-title drirc entry.

Blamed to `7e0c63c3f51` ("freedreno/common: update a8xx family configs",
2026-04-15) on `turnip/gen8` — one line inside a commit about GMEM sizes and
tile alignment, whose message never mentions subgroups.

**64 is not a reduction.** `threadsize_base` defaults to 64
(`freedreno_dev_info.py:108`) and a8xx does not override it, so 64 is the native
width and 128 is the opt-in. `minSubgroupSize` is already 64 unconditionally
(`tu_device.cc:1111`).

*Watched on the device by the equivalent route, not yet by this patch.*
`tu_restrict_subgroup_size_64=true` is defined as the negation of this same flag
(`tu_device.cc:1796`); with it, Requiem survived 600 s with `vr -4` at zero, five
swapchains created and no shader overrides at all. Without it, it never reached a
loading screen. This patch reaches the same state at the source, and until it has
had its own run that is an inference rather than an observation.

**`TURNIP_REVISION` exists because of this patch.** Turnip's version code is
derived from the Mesa version string alone, so a rebuild carrying a new patch
produced the code already in the store and the old driver kept being served —
the collision `build/turnip.sh` already warns about between its two variants, in
the other direction.

## `0008-diagnostic-name-the-dma-heap-the-swapchain-comes-from`

For **turnip**. Inert in the Zink build, which compiles no freedreno code.

One `fprintf` at physical-device creation, naming which of the three exportable
allocation paths this kernel gave Turnip, and listing what
`/dev/dma_heap/` actually holds:

    VESSEL-KGSL dma_type=dmaheap dma_fd=7 heaps=[qcom,system system linux,cma]

**Why it is worth a patch.** On the DRI3 path Vessel's X server CPU-reads every
presented image out of this allocation once a frame, and measured ~154 MB/s
doing it (`docs/BANDWIDTH.md` §9). Whether that is explicable depends entirely
on which path ran, and the three do not agree with each other:

- **dma-heap** — `bo_init_new_dmaheap` opens `/dev/dma_heap/system` and passes
  no cacheability request at all (`heap_flags` is left zero). On Qualcomm's
  downstream kernel that name is an alias for `qcom,system`, registered
  `uncached = false`, so the mapping is **cached** — see §9.1 for the source
  chain.
- **ION / legacy ION** — both pass `.flags = 0`, i.e. *without*
  `ION_FLAG_CACHED`, which under ION means a **write-combine** mapping. If this
  is the path, 154 MB/s is fully explained and §9.1 does not apply.

Nothing in the driver said which. `tu_knl_kgsl_load` records `kgsl_dma_type` and
never logs it, and the enum cannot answer alone: `TU_KGSL_DMA_TYPE_ION_LEGACY`
is 0 and the physical device comes from `vk_zalloc`, so "neither node opened"
and "legacy ION" are the same value. **`dma_fd` is printed for exactly that
reason** — a negative fd is the only thing that separates them, and the patch
prints `dma_type=none` when it is negative rather than reporting a path that
never ran.

**The heap listing is the half that could not be got any other way.** Knowing
`/dev/dma_heap/system` opened does not say whether a cached or uncached
alternative sits beside it under another name, and that is the question a fix
would turn on. It costs one `opendir` at device creation. Space-separated, not
comma-separated: Qualcomm's heap names contain commas themselves (`qcom,system`),
so a comma-joined list cannot be split back into its entries.

`fprintf(stderr)` and not `mesa_log`, for the same reason as `0005` — `mesa_log`
goes to logcat, which the session log does not read.

**It changes no behaviour.** No allocation, no heap choice and no flag moves;
this is the instrument that has to exist before any of those can be changed on
evidence. Specifically it does **not** switch heaps: §9.1 concludes there is no
cached heap to switch *to*, because the one already in use is the cached one,
and the uncached variant is not even compiled into the SM8650 reference config.

**Verified by compiling and running the inserted block**, not by `git apply
--check`: the block was extracted verbatim into a standalone translation unit,
compiled `-Wall -Wextra` for `aarch64-linux-android` and built and executed for
`x86_64-linux-android`, exercising the multi-entry, empty-directory,
missing-directory and buffer-overflow-truncation paths. The full Turnip build was
not run — no Linux build host was available in the session that wrote it — so
the patch is verified to apply to the post-`0001` tree and the code is verified
to compile and behave, but the two have not been verified together.

**If it stops applying**, the cause is almost certainly `0001`, the only other
patch that touches `tu_knl_kgsl.cc`; this hunk sits between two of its hunks in
`tu_knl_kgsl_load`. Rebase this one, not that one — it is a diagnostic and
deleting it costs nothing but the answer.
