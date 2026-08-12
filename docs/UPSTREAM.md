# Things to send upstream

Findings from this project that belong to someone else's tree. Each is written
so it can be pasted into an issue without further work.

Nothing here has been filed yet — posting to a third-party tracker is a decision
for whoever owns this repository, not something the build does.

## FEX-Emu: hardware with FEAT_LRCPC3 exists now

**Why it is worth filing.** FEX detects LRCPC3 and then does nothing with it,
and the stated reason is that the hardware did not exist. From
`unittests/InstructionCountCI/FlagM/HotBlocks_TSO_32Bit.json`:

> LRCPC3 isn't used for vector loadstores at all
> … No hardware ships with LRCPC3 yet anyway

That is no longer true, and FEX already knows this part: `CPUID.cpp:192` maps
`{0x51, 0x002}` to **Oryon-3**, which is exactly this device.

**What to include.**

- Device: Motorola Signature, Snapdragon SM8845, Qualcomm Oryon
- `CPU implementer: 0x51`, `midr_el1 = 0x00000000515f0020`, `revidr_el1 = 0x0`
- Android 16, arm64-v8a, 4096-byte pages
- `/proc/cpuinfo` Features, verbatim:

  ```
  fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm
  jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat
  ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svesha3 svesm4
  flagm2 frint svei8mm svebf16 i8mm bf16 rng bti ecv afp rpres sme smei8i32
  smef16f32 smeb16f32 smef32f32 lrcpc3
  ```

- Offer to test a branch. The value to FEX is *vector* TSO: `MemoryOps.cpp`
  currently emits `ldr` + `dmb ishld` for vector loads and `dmb ish` + `str` for
  stores, and FEX-2404 said accurate vector TSO would be revisited "once
  hardware ships that has FEAT_LRCPC3".

**Two things to be accurate about in the issue.**

1. The GPR path already uses LRCPC2 here and it is not affected by erratum
   3877900 — measured, see below. Only the vector path is on the table.
2. Detection reaches `Source/Common/HostFeatures.cpp:237` but there is no
   corresponding field in `FEXCore/include/FEXCore/Core/HostFeatures.h`, so the
   bit never crosses into codegen. `FEXGetConfig` nonetheless prints LRCPC3 as
   the TSO strategy for both GPR and vector, which is misleading on this device
   — worth mentioning as a separate small bug.

**Do not attach a patch.** `native/fex/CLAUDE.md` states that AI must not be
used to generate code for contributions to that project, so anything written
here could not be accepted. Hardware access and test results are the useful
contribution.

## Arm erratum 3877900 does not affect Oryon

Useful to whoever maintains the blocklist in `Source/Common/HostFeatures.cpp:457`
(and the equivalent in [LLVM PR #124274](https://github.com/llvm/llvm-project/pull/124274)),
which disables LRCPC2 on eight Arm-designed cores because `LDAPUR` executes with
full Load-Acquire ordering there.

Qualcomm cores are not on that list, and nobody had checked whether they should
be. Measured with `tools/tso/run.sh` — the same binary, twice, under Wine on the
device:

| | x86-64 (translated by FEX) | ARM64 (native control) |
|---|---|---|
| default | **289.3 ms** | 279.5 ms |
| `FEX_HOSTFEATURES=disablelrcpc2` | **348.8 ms** | 279.3 ms |

Turning the path off costs 21%, and the native control moved 0.2 ms, so that is
the ordering path and not thermals. Oryon does not have the erratum and the
current blocklist is correct.

## Wine: `kernelbase` does not export `FlsGetValue2`

**Why it is worth filing.** The Visual C++ runtime a modern game ships resolves
it by name at startup, so a title carrying its own `VCRUNTIME140.dll` asks for it
on every platform. Wine has `FlsAlloc`, `FlsFree`, `FlsGetValue` and
`FlsSetValue`; the `2` variant is absent from `dlls/kernelbase/kernelbase.spec`
in 11.14 and, as far as could be found, from master. `GetProcAddress` returns
NULL and the caller stores that as a function pointer.

Observed under Wine 11.14 (ARM64EC, FEX-2608, Android):

```
module:LdrGetProcedureAddress "FlsGetValue2" (ordinal 0)
  not found in L"C:\windows\system32\kernelbase.dll"
```

**The implementation is `FlsGetValue` without its `SetLastError(ERROR_SUCCESS)`**,
which is the whole reason Windows carries a second entry point — the write to the
TEB is pure cost on a path the CRT takes constantly:

```c
PVOID WINAPI DECLSPEC_HOTPATCH FlsGetValue2( DWORD index )
{
    void *data;
    if (!set_ntstatus( RtlFlsGetValue( index, &data ))) return NULL;
    return data;
}
```

`patches/wine/0017` carries this and the matching `.spec` entry. Verified in the
built DLL rather than on the build exiting zero — `strings` on
`lib/wine/aarch64-windows/kernelbase.dll` lists it beside `FlsGetValue`, in both
the aarch64 and ARM64EC trees.

**Filed as a gap, not as a fix for the crash it was found chasing.** The title
that surfaced it still dies afterwards, at an unrelated address, so this closes a
real hole in the export table and nothing more. That is worth saying in the issue
so nobody reads it as a regression report.
