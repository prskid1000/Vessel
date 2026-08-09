# Graphics: the Vulkan stack, and how to debug it

This is the graphics half of a Vessel session — how a Windows game's Direct3D
reaches the Adreno — and a worked example of debugging a native crash inside it,
kept because the *method* is what you will need again, not the one bug.

## The stack, top to bottom

```
  game (Direct3D 9/10/11/12)
    └─ DXVK / vkd3d-proton        PE DLLs, translate D3D → Vulkan
        └─ winevulkan (PE)        the app-side Vulkan ICD Wine presents
            └─ win32u (unixlib)   dlls/win32u/vulkan.c — the Wine/host boundary
                └─ Android platform loader   /system/lib64/libvulkan.so
                    └─ Turnip     libvulkan_freedreno.so — Mesa, the real driver
                        └─ KGSL   the Adreno kernel driver
```

Two facts about the bottom of this stack drive almost every graphics bug here,
and both are Android-specific:

1. **Turnip is an Android Vulkan HAL, not a Khronos ICD.**
   `libvulkan_freedreno.so` exports exactly one symbol, `HMI` (the Android
   hardware-module structure). It has no `vk_icdGetInstanceProcAddr`, no
   `vkGetInstanceProcAddr` — nothing Wine can dlopen and call directly. The only
   thing that can load it is the Android platform loader, through a linker
   namespace an app cannot normally reach. That is what `patches/wine/0006`
   (libadrenotools) exists to arrange: it loads a private copy of
   `/system/lib64/libvulkan.so` with Turnip bound behind it.

2. **The Android platform loader is not the desktop Khronos loader.** It owns the
   WSI surface/swapchain layer itself (`swapchain.cpp`), and that layer only
   understands surfaces it created for an `ANativeWindow`. It has no Xlib WSI —
   so it *forwards* `vkCreateXlibSurfaceKHR` to the driver, but keeps functions
   like `vkGetPhysicalDeviceSurfaceCapabilities2KHR` for itself. That split is a
   trap; see below.

`win32u/vulkan.c` is where Wine meets all of this. It creates the host surface
from the X11 window (`X11DRV_vulkan_surface_create` → `vkCreateXlibSurfaceKHR`),
wraps host handles as client handles, and forwards each Vulkan call. When a
graphics bug is Android-shaped, it is usually here or in one of the `patches/wine`
files that this document's example produced.

## Worked example: the 0xc0000005 at first swapchain

**Symptom.** Metro 2033 Redux (any DXVK title) initialised fully — engine up,
device created, pipelines compiled — then died the instant it built its
swapchain:

```
err:vulkan:vkGetPhysicalDeviceSurfaceCapabilitiesKHR Exception 0xc0000005 in Unix call.
```

That `err` line is emitted by winevulkan's `UNIX_CALL_CHECKED` macro
(`vulkan_loader.h`) whenever a Unix-side Vulkan thunk faults. **It names the
thunk, not the culprit** — the fault happened somewhere below `win32u`, and the
label is just which call was in flight. Do not trust it as the location.

### Finding the real fault

1. **Turn on SEH tracing.** Add `+seh` to `WINEDEBUG` for the run (in
   `SessionEnvironment.WINEDEBUG_CHANNELS`). Wine's `handle_syscall_fault` then
   prints the register file and, crucially, the faulting PC and address:

   ```
   seh:handle_syscall_fault code=c0000005 addr=0x72d92704a0 pc=0x72d92704a0
   seh:handle_syscall_fault  info[0]=0000000000000000   (0 = read)
   seh:handle_syscall_fault  info[1]=0000000000000094   (faulting address = 0x94)
   ```

   A read of address `0x94` is a NULL pointer dereference at field offset `0x94`.

2. **Map the PC to a library.** Snapshot the process map while it is alive
   (`run-as app.vessel cp /proc/<pid>/maps …`, pulled off the device), then find
   the segment containing the PC:

   ```
   72d925d000-72d9277000 r-xp 00018000 … /memfd:/system/lib64/libvulkan.so (deleted)
   ```

   The PC is inside the **platform loader** — the adrenotools-loaded memfd copy —
   not inside Turnip (`libvulkan_freedreno.so`, mapped elsewhere). That single
   fact reframes the whole bug: it is the Android loader crashing, not the driver.

3. **Symbolise the faulting instruction.** The file offset is
   `0x18000 + (PC − 0x72d925d000) = 0x2b4a0`. Pull the device's
   `/system/lib64/libvulkan.so` and disassemble around it with the NDK's
   `llvm-objdump -d`:

   ```
   ldr x20, [x8]          ; x20 = *surface  (first qword of the surface object)
   ldr x8,  [x20, #0x90]  ; FAULT: x20 == 4, so this reads 0x94
   blr x8                 ; …then calls through it
   ```

   `*surface == 4`. The nearest exported symbols bracket this as a private helper
   just past `vkGetPhysicalDeviceSurfaceCapabilities2KHR`, and its prologue scans
   the `pNext` chain for `VK_STRUCTURE_TYPE_SURFACE_PRESENT_MODE_KHR` — so this is
   Android's **`GetPhysicalDeviceSurfaceCapabilities2KHR`** implementation.

### The mechanism

`4` is `VK_ICD_WSI_PLATFORM_XLIB` — the first field of a Mesa `VkIcdSurfaceXlib`.
The surface was created by **Turnip** (the loader forwarded
`vkCreateXlibSurfaceKHR` to it), so it is a raw ICD Xlib surface whose first word
is that platform tag. But the capabilities-2 query is serviced by **Android's own
loader**, which expects a surface *it* wrapped, whose first word is a pointer to a
dispatch object with a method at `+0x90`. It reads the tag `4` as that pointer and
jumps through `4 + 0x90`. Two owners, one surface, incompatible layouts.

DXVK reaches the *2* form (not the base one) only because it enables
`VK_KHR_get_surface_capabilities2`. The **base**
`vkGetPhysicalDeviceSurfaceCapabilitiesKHR` is a plain physical-device
dispatch-table entry (`ldr x8,[x0]; ldr x16,[x8,#0x78]; br x16`) that the loader
forwards straight to Turnip — so it is safe.

### The fix

`patches/wine/0009-win32u-emulate-surface-capabilities2-on-android.patch`.
On Android, never call the host's `…Capabilities2KHR`; emulate it on top of the
base query, which works, and fill any chained `VK_EXT_surface_maintenance1`
output structs conservatively (no scaling; each present mode compatible only with
itself — the client just recreates the swapchain to change mode). A compile-time
`host_surface_capabilities2_faults` keeps the direct host call on every other
platform, where the 2 form is correct.

## The method, in general

Any `Exception 0x… in Unix call` follows the same four steps:

1. `+seh` → faulting **pc** and **address** (`info[1]`), and read/write from
   `info[0]`.
2. `/proc/<pid>/maps` → which **library** the pc is in. Being in the platform
   loader vs. the driver vs. `winex11.drv` usually decides the fix's owner.
3. `llvm-objdump -d` / `llvm-nm -D` (NDK `toolchains/llvm/prebuilt/*/bin`) at the
   file offset → the faulting **instruction** and the nearest **symbol**.
4. Read the offset the code dereferenced against the struct it *thinks* it has.
   A small constant where a pointer belongs (here `4`) means two components
   disagree about the object's type — the interesting bugs almost always do.

Keep the fix in `patches/wine/` (or the relevant `patches/<component>/`), never
as an edit to the checked-out `native/` tree — the build resets that tree to its
pinned ref before every build and applies the patch series on top. See
`docs/BUILDING.md` and `docs/UPSTREAM.md`.
