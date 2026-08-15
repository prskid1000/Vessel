# The graphics probes

Small programs that answer one question each, by value rather than by
impression. They exist because every layer in this stack will tell you it
succeeded: DXVK hands out an `ID3D11Device` long before a Vulkan queue
submission has worked, `wined3d` hands out an identical one, and a container
that reports the wrong machine reports it confidently.

Two harnesses run them, and they answer different questions.

| Harness | Question | Prefix |
|---|---|---|
| `tools/device-graphics.sh` | does this API actually draw? | one it builds itself |
| `tools/container-probe.sh` | what is a container telling the guest? | the container's own |

The second cannot be folded into the first. A container's Hardware settings and
its installed components are properties of *that* container, so a scratch prefix
cannot observe them.

## What each probe answers

| Probe | Draws | Also reports |
|---|---|---|
| `vkprobe` | no — enumerates only | which Vulkan driver answered, and the memory heaps |
| `d3d11probe` | triangle, headless | the DXGI adapter and its dedicated video memory |
| `d3d12probe` | triangle, headless | the DXGI adapter, video and shared memory |
| `d3d10probe` | triangle, headless | — |
| `d3d9probe`, `d3d8probe` | triangle, windowed | — |
| `glprobe` | triangle, windowed | `GL_RENDERER`, and video memory when the extension exists |

`vkprobe` is the ground truth for the rest: DXVK, vkd3d and Zink all report an
"adapter", but they report whatever the Vulkan driver told them.

## The two output lines

```
VESSEL-GFX  api=… result=PASS|MISMATCH|FAIL|BLOCKED …   the verdict, one per run
VESSEL-HW   api=… cpus=… ram_mib=… [vram_mib=…]         what the machine claims to be
```

`VESSEL-HW` is printed by every probe as its first act, before anything is
created, so a probe that fails to make a device still says what it was told.

**It goes to stdout and to `OutputDebugString`, and both are needed.** Under
`device-graphics.sh` the probe owns its stdout and the harness reads it. Inside a
container the probe is a child of `wine explorer`, and Vessel's session log
carries Wine's debug stream rather than a child's console — measured, not
assumed: the log recorded every module the probe loaded and not one line it
printed. See `gfx_emit` in `gfxprobe.h`.

## Why the machine line is on every probe

The container's Hardware settings are applied to the quantity each layer
*derives* its answer from:

- **Cores** and **RAM** come from Wine, so every guest process sees them.
- **VRAM** is the driver's heap. DXVK sums the device-local heaps for
  `DedicatedVideoMemory`, vkd3d reports through DXVK's DXGI, and Zink sums the
  same heaps for GL.

That is a chain of derivations, and a chain is exactly the kind of thing that is
right in three places and quietly wrong in the fourth. So each probe says what
*it* sees, and disagreement localises the break to one layer.

Measured this way on 2026-08-15, container set to 6 cores and 6 GB of each:

```
vulkan  cpus=6 ram_mib=6144  heaps=1 vram_mib=6141
opengl  cpus=6 ram_mib=6144          vram_mib=6141
d3d12   cpus=8 ram_mib=4096          vram_mib=2042   (at an earlier 2 GB setting)
d3d11   cpus=8 ram_mib=4096                          (DXVK logged Heap 0: 1.99 GiB)
```

Zink and raw Vulkan reporting the same figure, with nothing configured for Zink
separately, is the point of setting video memory on the driver rather than per
layer.

## Two traps worth knowing before reading a result

**OpenGL needs the Zink toggle.** With *Use Zink for OpenGL* off, `glprobe`
loads Wine's builtin `opengl32.dll` and stops there, because winex11's EGL
bridge fails on this device with `egl_init Failed to find required extension
EGL_KHR_client_get_all_proc_addresses`. That is not a probe failure. With the
toggle on it loads `vulkan-1.dll` and answers. The toggle is off by default for a
reason that is not GL's fault — see `WGL_DLL` in `SessionEnvironment.kt`.

**A cap can starve the thing being measured.** With four cores the container
booted but launching a game timed out in `run_wineboot`, because the FEX offline
compiler was building a code cache on those same four cores. The probe would have
reported four cores quite happily.
