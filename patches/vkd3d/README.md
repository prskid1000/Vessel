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
