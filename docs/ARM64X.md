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

## Status

Not implemented. The recipe above is verified end to end on a toy DLL in the
`vessel-build` image; the meson wiring for DXVK, vkd3d and Zink is not written.
`docs/TODO.md` #57 carries the VS Code side of this.
