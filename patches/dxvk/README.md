# DXVK patches

Applied on top of the pinned `DXVK_REF`, in filename order, by `build/dxvk.sh` —
`apply_patches` is keyed on the source name (`dxvk`). A patch that does not apply
is a hard error; see `apply_patches` in `build/common.sh`.

## `0001-vessel-write-the-hud-counters-to-a-file`

Adds `src/dxvk/dxvk_vessel_stats.{cpp,h}` and one call at the end of
`DxvkDevice::presentImage`. Once a second, the counters DXVK already maintains
are written as a single-line JSON snapshot to the path in `VESSEL_GFX_STATS`,
which `app.vessel.data.MetricSampler` reads and the Metrics tab graphs.

DXVK keeps every one of these counters whether or not the HUD is on — the HUD
only reads them — so the patch adds no counting, only a reader. Off unless
`VESSEL_GFX_STATS` names a path, and the environment is read once; when it is
unset the cost is one predictable branch per present.

The file is rewritten in place rather than appended: the host samples on its own
schedule and only ever wants the newest line, and a file that grows for the
length of a session is a log nobody asked for. It adds **no log lines at all**.

Counters that describe a population (pipelines, compile tasks) are absolute;
everything else is a delta over the interval, because a lifetime total says
nothing about the frame being drawn.

### The idle and sync counters, added later

`gpuIdleUsPerFrame`, `gpuSyncUsPerFrame`, `csIdleUsPerFrame`, `csSyncUsPerFrame`
and `drawsMergedPerFrame` were not in the first version, and the omission
mattered more than it looked. Everything the patch dumped counted *work*, and
work alone cannot distinguish a frame that was slow because there was too much
of it from one that was slow because something was blocked.

`GpuIdleTicks` is time the GPU had nothing submitted; `GpuSyncTicks` is time the
CPU spent blocked on the GPU. They are close to mutually exclusive, so which one
is large is the GPU-bound-versus-CPU-bound answer *directly* — rather than by
inference from a GPU load percentage, which cannot separate useful work from
overhead and has already misled this project once, on the DRI3 A/B.

The `Cs` pair asks the same question about DXVK's own command-stream thread,
which is a third place a frame can be lost and one no host counter can see.

These are free: DXVK has always counted them, the snapshot is still one write a
second, and they add nothing to the log. They are therefore always on, like the
rest of the file.

`line[]` went from 768 to 1024 bytes at the same time. Not measured to the byte,
because the failure mode is bad: `snprintf` truncates silently, a truncated line
is invalid JSON, `parseGfxStats` returns null, and the panel reports "no Direct3D
counters" for a title that was drawing.

### The version-code bug this patch was shipping under

`build/dxvk.sh` computed no version code until now. It passed only
`--version "$VERSION"`, so `package_wcp.py` derived the code from `2.7.1` alone
— **20701, unchanged by any patch**. `ComponentStore` is keyed on type and
version code, so a phone that already had DXVK 2.7.1 would accept a rebuilt
`.wcp` and keep what it had. Every DXVK build since this patch landed has been
installable-but-inert on an already-provisioned device, and the only symptom is a
counter that never changes.

Fixed by mirroring `vkd3d.sh`: `DXVK_REVISION` in `native/pins.env`, a
`vessel_version_code` call, and `--version-code` on the package. Any revision
yields `2070100 + revision`, which is far above the bare `20701`, so `1` is
already unambiguous against everything that has shipped.

`build/vkd3d.sh`, `build/wine.sh`, `build/turnip.sh` and `build/fex.sh` all did
this correctly. `build/dxvk.sh` and `build/zink.sh` were the two that did not;
zink's `VERSION` carries a source SHA, so it moves whenever its source does and
has no patch directory to go stale against.

### Verification

**Not compiled.** No DXVK build has been run against the current version of this
patch — the build needs meson and llvm-mingw, neither of which is on the machine
it was edited on. The patch was regenerated from the working tree with
`git diff` rather than hand-edited, and round-trips (`git apply --reverse`, then
`--check`, then re-apply, all clean).

The `snprintf` — the realistic failure mode when a format string grows — was
extracted verbatim into a standalone translation unit and compiled with
`clang++ -Wall -Wextra -Wformat=2`. Clean, so the format string and its twenty
arguments agree.

**Policy.** Vessel-only. Upstream has the HUD for this and would not want a file
writer in `presentImage`. AI-authored in full.
