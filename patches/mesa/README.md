# Mesa patches

Applied by `build/turnip.sh` on top of the pinned `MESA_REF`, in filename order.
A patch that does not apply is a hard error — see `apply_patches` in
`build/common.sh`.

## 0001-kgsl-timeline-sync-mr39751.patch

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
