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
