# ARM64X, and why a native ARM64 program has no graphics

Vessel's three PE graphics components — DXVK, vkd3d-proton and Mesa/Zink — are
built as **pure ARM64EC**. Wine's own PE DLLs are built as **ARM64X**. That
difference is invisible until a native ARM64 Windows program runs, and then it is
the whole story:

    module:load_dll Failed to load module L"dxgi.dll"; status=c000007b

`0xC000007B` is `STATUS_INVALID_IMAGE_FORMAT`. The ARM64 build of VS Code loads
`ntdll`, `user32` and `gdi32` without complaint — those are Wine's, and they are
ARM64X — and then cannot load a single graphics DLL. No D3D, no GL, no swap
chain. Every ANGLE backend fails identically, which is why `--use-angle=gl`,
`--use-angle=swiftshader` and the D3D11 default all produce the same white
window: there is nothing underneath any of them.

## The three formats, and which loads where

| format | machine | loadable by x64 | loadable by ARM64EC | loadable by classic ARM64 |
|---|---|---|---|---|
| x86-64 | `0x8664` | yes | yes | no |
| **ARM64EC** | **`0x8664`** | yes | yes | **no** |
| **ARM64X** | `0xAA64` | yes | yes | **yes** |
| ARM64 | `0xAA64` | no | no | yes |

**An ARM64EC image declares machine type `0x8664` on purpose**, so that x64
loaders accept it. Reading that field and concluding "this is an x86-64 binary"
is wrong, and it cost an hour here before `llvm-objdump -f` settled it:

    dxgi.dll (DXVK)   file format coff-arm64ec
    ntdll.dll (Wine)  file format coff-arm64x

ARM64X is a *hybrid*: one file carrying both an ARM64 view and an ARM64EC view,
with a redirection table (`.a64xrm`) mapping between them. It is the only format
that serves an emulated x64 process and a native ARM64 process from the same
`system32`, which is exactly what a Wine prefix needs, because a prefix has one
`system32` and not one per architecture.

## The recipe, proven on this toolchain

llvm-mingw 21.1.1 in `vessel-build` can emit ARM64X. Established by experiment,
not by reading release notes:

1. **`lld-link` accepts `-machine:arm64x`** and documents `/defarm64native:<value>`
   ("use a module-definition file for the native view in a hybrid image").
2. **The clang driver accepts it too**, spelled MSVC-style through `-Wl,`:
   `-Wl,/machine:arm64x` is accepted; `-Xlinker -machine:arm64x` and
   `-Wl,--machine=arm64x` are not. That matters because meson drives the linker
   through the compiler.
3. **`__os_arm64x_dispatch_ret` and friends come from `libmingwex.a`**, not from
   `libntdll.a`. Wine declares them in `dlls/ntdll/ntdll_misc.h:188-190` and fills
   them in `signal_arm64ec.c:200-202` from the FEX emulator module, but the link-
   time definitions the entry thunks reference are in mingwex. Linking without it
   fails with `undefined symbol: __os_arm64x_dispatch_ret (EC symbol)`.
4. A minimal two-object link then produces a genuine hybrid:

       lld-link -machine:arm64x -dll -noentry -out:t.dll t_ec.o t_arm64.o \
         -libpath:.../arm64ec-w64-mingw32/lib libmingwex.a

       t.dll:  file format coff-arm64
         .hexpthk   entry thunks
         .a64xrm    ARM64X redirection metadata

5. Linking the EC objects **without** the native ones fails with
   `undefined symbol: DllMainCRTStartup (native symbol)` — the native view needs
   its own entry point and CRT. Both halves must be present at link time.

## Why this is not a one-line change

DXVK, vkd3d and Zink are meson builds, and meson compiles and links in one pass.
ARM64X needs both object sets present at that link, so the build has to produce
the native half first and hand it to the EC link.

**Shape that fits meson.** Build the native (`aarch64-w64-mingw32`) pass first for
its objects only, archive them, then configure the ARM64EC pass with

    c_link_args = ['-Wl,/machine:arm64x', '-Wl,/wholearchive:<native>.a']

`/wholearchive` because ARM64X needs *every* native object, and an archive member
is otherwise pulled only when a symbol demands it.

**Two obstacles, both real.**

- **LTO must go off for these components.** `build/*.sh` passes `-Db_lto`, and
  with LTO the "objects" are LLVM bitcode. Bitcode from two different targets
  cannot be combined in one link. This costs some performance and the trade
  should be measured rather than assumed.
- **Every DLL needs its halves paired.** Both passes must build the same target
  list, and the archive handed to each EC link must be that DLL's native objects
  and no others, or symbols collide across DLLs.

## What this unblocks

Not VS Code alone. Every native ARM64 Windows program has no graphics stack
today, and that is a large and growing set — Microsoft ships ARM64 builds of VS
Code, Edge, Office and the .NET runtime. Running them natively also removes FEX
from the picture, which is the difference between emulating Chromium and running
it.

It is also the second half of a lesson this project already learned once. The GDI
font chains named fonts that were not installed (`patches/wine/0056`) and the
DirectWrite fallback table named 59 more (`0057`); in both cases the machinery
was present and pointed somewhere empty. Here the graphics stack is present,
built, installed and correct — and in a format the process asking for it cannot
load.

## How it is wired

`build/arm64x-cc.in` is a compiler shim. Meson goes in its `c`/`cpp` slot, not
its link args, because meson links in the same pass it compiles and has nowhere
to accept a second object set. The shim passes compiles through untouched and, on
a link, appends the native half.

Each of `build/{dxvk,vkd3d,zink}.sh` now builds the native `aarch64-w64-mingw32`
tree first -- `ninja` and not `ninja install`, because what ships is the one
hybrid and not two DLLs -- and then configures the ARM64EC pass through the shim.
`arm64x_wrappers` in `build/common.sh` generates it.

Three details that are not obvious and each cost a build:

- **Only DLLs.** Meson's sanity check and its feature probes link *executables*,
  which have no native half by construction. Treating that as fatal fails
  configure with "Compiler /work/arm64x-cc cannot compile programs".
- **Resources belong to the image once.** Both halves compile the same
  `version.rc`, and linking both is a hard error: `duplicate resource: type
  VERSIONINFO (ID 16)`. The native copy is dropped, detected by section rather
  than filename because what meson names a windres output follows the `.rc`.
- **vkd3d's widl discovery had to move above the pass loop**, because the native
  tree configures before it and needs the same generator.

Verified on the output rather than assumed -- all twelve `system32` DLLs:

    dxvk    d3d10core d3d11 d3d8 d3d9 dxgi        coff-arm64x
    vkd3d   d3d12 d3d12core                       coff-arm64x
    zink    libEGL libGLESv1_CM libGLESv2         coff-arm64x
            libgallium_wgl opengl32               coff-arm64x
    all     syswow64/                             coff-i386

`syswow64` stays i386: it is x86 PE that FEX translates, and there is no ARM64
view for it to carry.

Zink gained a revision of its own (`ZINK_REVISION`) because Mesa's `VERSION`
plus a commit cannot move a version code, and `ComponentStore` is keyed by type
and code -- a rebuild of the same commit would have installed and never been
unpacked. See the note in `build/zink.sh` about the one-way scale change that
introduces.

## Built, measured, and turned off again

`VESSEL_ARM64X=1` builds hybrids; the default does not, and the reason is a
regression rather than a doubt about the format.

What it fixed is real: a native ARM64 process could not load a single graphics
DLL, and with hybrids it loads them all. `dxgi.dll` goes from `status=c000007b`
to `Loaded ... : native`, the GPU process stops failing to launch, and Turnip is
reached -- `Vulkan: driving the ICD libvulkan_freedreno.so`.

What it did not fix: VS Code still does not paint. The renderer stalls at
`uxtheme` and the GPU process goes quiet after loading `dxgi`, with no DXVK log
line at all, so it never creates a device.

What it broke: **Resident Evil Requiem**, which ran before. Twenty-two
`unrepaired read fault` lines against zero in the run before it, dying inside its
own exception handler -- `re9.exe + 0x9e835ae`, reached through
`kernelbase!RaiseException` and the ARM64EC dispatch region -- after vkd3d had
successfully brought a D3D12 device up. **Metro 2033 was unaffected**, and that
narrows it: Metro is D3D11 through DXVK, Requiem is D3D12 through vkd3d. A hybrid
DXVK is fine under x64 emulation; a hybrid vkd3d is not, and why is unknown.

One more thing that cost a run and is worth knowing before the next attempt:
**changing these components invalidates the FEX AOT code cache, and the failure is
not graceful.** Wine skips stale code maps by image hash and says so, but the AOT
compiler still produced eleven `Invalid instruction in entry block` errors and
Requiem died before reaching `d3d12.dll`. Clearing `caches/fex` and `caches/mesa`
fixed that particular failure and revealed the real one underneath.

`docs/TODO.md` #57 carries the VS Code side of this.
