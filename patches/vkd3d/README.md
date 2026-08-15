# vkd3d patches

Applied on top of the pinned `VKD3D_REF`, in filename order, by `build/vkd3d.sh`
— `apply_patches` is keyed on the source name (`vkd3d`). A patch that does not
apply is a hard error; see `apply_patches` in `build/common.sh`.

This directory did not exist until now. `build/vkd3d.sh:61-64` records that
creating it is the whole of what is needed for the harness to pick it up.

## 0001-dxbc-the-per-shader-header-chatter-is-a-trace.patch

Three `WARN` sites in `libs/vkd3d-shader/dxbc.c` become `TRACE`:
`skip_dword_unknown`'s heading and per-DWORD lines (`:66-73`), and
`Ignoring DXBC checksum` (`:124`).

**Measured, on the device 2026-08-13.** One Resident Evil Requiem session of
about six minutes logged **26,966 lines** from this channel across 3,025
shaders — 98% of everything the session produced once FEX was silenced.

The mechanism is confirmed by arithmetic and not only by grep. `dxbc.c:124-125`
is a checksum warning immediately followed by `skip_dword_unknown(&ptr, 4)`,
which prints a heading plus one line per DWORD: six lines. `:193` adds
`skip_dword_unknown(&ptr, 1)`, two more. Eight per shader, times 3,025, is
24,200 — within 10% of what was counted.

**They are not warnings.** vkd3d ignores the DXBC checksum on every blob it has
ever parsed, and the DWORDs being skipped are reserved fields the container
format specifies. A message that is true of every call is not a warning about
anything, which is why the fix is the level and not the call site.

### Two corrections this patch carries

**The burst was attributed to the wrong variable.** It was recorded as
`VKD3D_DEBUG` and mitigated per-container by setting `VKD3D_DEBUG=err`. That
cannot have worked: `dxbc.c:20` is `#define VKD3D_DBG_CHANNEL
VKD3D_DBG_CHANNEL_SHADER`, and `libs/vkd3d-common/debug.c:49-53` maps that
channel to **`VKD3D_SHADER_DEBUG`**. The gate at `debug.c:181` compares only the
channel's own level, so lowering the API channel had no bearing on these lines.

**It is Vessel's doing rather than upstream's.** `debug.c:96-97` defaults an
unset channel to `FIXME` (4), and both messages are `WARN` (5) in a ladder that
orders `none < err < info < fixme < warn < trace` (`debug.c:38-47`) — so `warn`
is *above* `fixme` and upstream is silent for both. They appear here only because
Vessel sets `VKD3D_SHADER_DEBUG=warn` on purpose, to see shader translation
failures. That tier is exactly what this noise was burying.

### Why not just lower the variable

Because that would silence the failures the variable is set to `warn` for. D3D12
on Adreno is the weakest part of this stack and a shader that fails to translate
has to say so. `TRACE` keeps these three reachable — `VKD3D_SHADER_DEBUG=trace`,
or `VESSEL_TRACE=shaders:everything` — and takes them out of the tier that has to
stay readable. Better signal, not less logging.

### Not compiled

Generated against the pinned tree and verified with `git apply --check`. No
vkd3d build has been run against it. See `docs/TRACING.md`.

## 0002-cache-a-pso-hash-mismatch-must-say-what-mismatched.patch

`PSO compatibility hash mismatch` (`libs/vkd3d/cache.c`) learns to print the two
hashes and whether the shader set moved with them; the caller in
`libs/vkd3d/state.c` learns to print the render target formats, DSV format,
sample description, topology and flags the rejected blob was measured against.

### Why the existing line cannot be acted on

It reports that two 64-bit numbers differ and nothing else, and that fact is
consistent with two situations whose correct responses are opposites:

- The application handed back a blob it captured for a **genuinely different**
  pipeline state. `d3d12_cached_pipeline_state_validate` is *required* to reject
  it — the contract is `E_INVALIDARG`, the application recompiles, and there is
  nothing here to fix.
- The **same** pipeline state hashed differently on two occasions, which would
  be a defect in this tree and would make the cache worth nothing.

The `dxbc_blob_hashes` decide it, and they are already sitting in the same
struct. They come from shader bytecode alone, so *shaders identical* alongside a
differing state hash means the same shaders under different state — and the
state dump then names which part of it. *Shaders also differ* means the blob
belongs to another pipeline entirely. One expected hash appearing against
several stored ones **inside a single run** is the third case, and the only one
that is ours.

### What the run actually shows, measured 2026-08-14

Read from `v8b.log`, 4,476 lines, before writing any of this:

| | |
|---|---|
| Mismatches | **45**, in **3 bursts** |
| Position | lines 4073, 4132–4177, 4398–4424 — the last 10% of the run |
| Largest burst | 34, immediately after `dxgi_vk_swap_chain_ChangeProperties: Reallocating swapchain (1280 x 720)` |
| Other cache failures | none — no root signature mismatch, no DXBC mismatch, no corrupt blob, no build or UUID rejection |
| Total PSO traffic | `Flushing disk cache (wakeup counter since last flush = 1181)` |

**So the note this came from was wrong twice.** It recorded "writes fine now, but
every read is rejected"; the reads are not all rejected — 45 against a wakeup
counter of 1,181 — and they are not spread through the run but clustered at the
two moments the game reconfigures its render targets.

The swapchain reallocation is this stack's doing. Requiem asks for 1920x1080
(`dxgi_vk_swap_chain_init`, line 4036) and the session's desktop is 1280x720
(`wine explorer /desktop=vessel,1280x720`), so the swapchain is reallocated
almost immediately and blobs captured before it are measured against a different
format set after. That is a correct rejection, and this patch exists so it can be
*read* as one instead of inferred.

### What it deliberately does not do

Nothing is suppressed and no threshold is added. If the state genuinely differs,
`E_INVALIDARG` is the answer the API requires, and
`VKD3D_CONFIG_FLAG_PIPELINE_LIBRARY_IGNORE_MISMATCH_DRIVER` covers only
`D3D12_ERROR_ADAPTER_NOT_FOUND` and `D3D12_ERROR_DRIVER_VERSION_MISMATCH` — not
this — which is upstream being right rather than upstream having missed a case.

The blob format is untouched, so `VKD3D_CACHE_BLOB_VERSION` does not move and
existing caches stay valid. Storing per-section sub-hashes would localise a
mismatch to the exact field, but it would change the chunk size, invalidate every
cache on disk, and is not worth it until the cheap reading has been ruled out.

One thing worth noting while passing through: the hash at `cache.c:2661` walks
`blend_state.RenderTarget[i]` and `rtv_formats.RTFormats[i]` for
`i < NumRenderTargets` with the count taken from the application and never
bounded by `ARRAY_SIZE`. The dump added here *is* bounded, and says so in a
comment, but the hash is not. Not provoked by anything observed — recorded
because it was read closely enough to notice.

### Not compiled

Generated against the pinned tree. Verified both directions: `git apply --check
--reverse` against the working tree, and `git apply --check` against a pristine
`git archive HEAD` extraction. No vkd3d build has been run against it — and
`0034` in `patches/wine/` is this session's reminder that applying is not
compiling.

## `0004-cache-node-mask-0-and-1-are-the-same-device`

`0002` made a PSO rejection say what mismatched. This is what it found, and the
answer came out of the arithmetic rather than out of any one line.

Every rejection in a Requiem session — about fifty per launch, permanently —
reported `shaders identical`, and in **every** observed case the two hashes
differed by exactly `0x100000001b3`, the FNV-1 64-bit prime.

That difference names the field. The hash is `h = (h * P) ^ v` and the last two
inputs are `node_mask` then `flags`; the rejection line reports `flags 0`, so the
final XOR contributes nothing and the last step is a pure multiply by P — which
makes a whole-prime difference mean the *penultimate* input differed by one in
bit 0. Nothing else produces that signature: a differing `flags` gives a
difference of one, and any earlier field is multiplied by P again on the way out.

**vkd3d already agrees 0 and 1 are the same device, in its own code.**
`debug_ignored_node_mask` only complains when `mask && mask != 1`, and every entry
point that takes a NodeMask discards it after that check. Pipeline creation never
reads it; `state.c` only copies it. D3D12 treats them as equivalent on a
single-adapter device. Then the raw value went into the cache compatibility key,
so an engine passing 0 down one path and 1 down another is refused here and
matches on Windows.

Checked and ruled out before writing this: it is **not** a stale cache — a blob
with a different `vkd3d_build` is rejected earlier with
`D3D12_ERROR_DRIVER_VERSION_MISMATCH` rather than `E_INVALIDARG`, so anything
reaching this comparison was written by this binary on this GPU, and
`VKD3D_REVISION` is Vessel packaging that is not part of `vkd3d_build`. And it is
not an unstable hash: the function is pure in the descriptor.

Normalised rather than removed, because dropping the field would change the hash
of every existing blob and invalidate caches that are currently valid; mapping 0
to 1 touches only the descriptors that were being refused wrongly.

The cost removed is compile time, not correctness — `docs/TODO.md` #42 records 45
and 50 mismatches across two runs, roughly 1.5% of 3,025 shaders. The log reads
worse than that because each event prints four to six lines.

**Policy.** Vessel's patch on Vessel's build. Defensible upstream as written,
since it is a plain inconsistency in vkd3d's own treatment of the field.
AI-authored in full.
