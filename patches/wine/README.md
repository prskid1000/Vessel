# Wine patches

Applied on top of the pinned `WINE_REF` (`experimental_11.0`), in filename order,
by `build/wine.sh` — `apply_patches` is keyed on the source name (`wine`). A
patch that does not apply is a hard error; see `apply_patches` in
`build/common.sh`.

**Bump `WINE_REVISION` in `native/pins.env` whenever a patch here changes.** The
version code is `(epoch + upstream) * 100 + WINE_REVISION`, and `ComponentStore`
is keyed by type *and version code* — a package whose code already exists on the
phone is treated as bytes the device already has and is never unpacked. A new
patch with an unchanged revision therefore builds, installs, and does nothing.

**Where the rationale lives.** Unlike the other components, this set is large and
each patch carries its own prose header above the diff, in the style of
`0002` — `git apply` skips leading text, so the argument travels with the change
rather than in a catalogue that drifts away from it. Read the head of a `.patch`
file for why it exists. This README covers the ones that need more context than
a single patch can hold, or that are referenced from `docs/TODO.md`.

**Two rules learned the expensive way, both from this directory:**

1. **`git apply --check` proves a patch applies. It never proves it builds.**
   `0013`'s first version applied cleanly and failed the build with 20 errors —
   a stray `*/` had closed a comment early.
2. **Never hand-edit a hunk body without recomputing its `@@` header.** Doing so
   produced `corrupt patch at line 234` and cost a build. Generate patches by
   diffing a copy against the tree instead.

---

## 0044-ntdll-an-image-that-has-moved-must-say-so-in-its-own-header.patch

**The root cause of `#51`, and of a crash that presented as four different
bugs.** One assignment.

Two paths relocate a PE image and they do not do the same amount of work:

| path | applies `.reloc` | writes `OptionalHeader.ImageBase` |
|---|---|---|
| `map_image_view`, "relocate to dynamic base" (`unix/virtual.c:3486`) | yes | **yes** |
| `virtual_relocate_module` (`unix/virtual.c:4199`) | yes | **no** |

The second is called by `load_ntdll` and `load_ntdll_wow64` (`unix/loader.c:2168`
and `:2268`) when ntdll cannot keep the address it was mapped at. Those two are
its only callers, so in practice this is ntdll — which is also the module every
protection layer walks.

`ImageBase` is the documented way to find a loaded module's base from its own
headers. On Windows it is always correct, because the loader keeps it correct.
Here an image could sit at one address while its header named another.

### What that did to Resident Evil Requiem

```
virtual:virtual_relocate_module 0x6fffe30000 -> 0x7fffe30000
virtual:virtual_handle_fault unrepaired fault at 0x6fffe3003c, no view covers it
seh:dispatch_exception code=c0000005 info[1]=0000006FFFE3003C rbx=0000006fffe3003c
```

`0x6fffe30000 + 0x3c` is `e_lfanew` in the DOS header of a module that had moved.
The game read `ImageBase`, believed it, and dereferenced where ntdll used to be.

### Why it took days to find

Nothing downstream of the bad pointer points back at it. The faulting code is
protection-generated — no module, no unwind data — and an x86-64 frame with no
function entry is unwound as a *leaf*: pop a qword off the stack and treat it as
a return address. The handler search therefore cannot fail, it can only wander.
It walked ~180 frames of stack **data** (visibly IEEE-754 doubles), never once
reporting `exception data not found`, and ended at:

```
seh:call_seh_handlers invalid frame 54005e (0000000000022000-0000000000420000)
seh:NtRaiseException Exception frame is not in stack limits => unable to dispatch
```

`0x54005e` fails `is_valid_arm64ec_frame` on its first test, alignment. Nothing
was ever offered the exception, so the game drew an unhandled-exception dialog
whose frame list described the broken *search* and not the bad *pointer*. Every
earlier theory in `docs/TODO.md#51` — a C++ throw, an EC unwind defect, a
`memcpy` overrunning a commit — was read off that dialog.

### Why this platform and not Windows

Windows randomises ntdll's base once per boot and maps it at that same address in
every process, so a cached base is always valid there. `0002` forces PE images
into anonymous memory on Android because SELinux refuses `execmod` on a dirtied
file mapping, and its own notes record ntdll being relocated as the normal case.
This is the common path here, not a corner.

### A theory this replaces

That ARM64X `VALUE` fixups leave stale absolute addresses after a rebase —
plausible, and **disproved by measurement**. The shipped ntdll's dynamic
relocation table holds 11 `VALUE` fixups, none of them 8 bytes and none writing
an address inside the image, while all 886 `.reloc` entries are `DIR64`. Nothing
stale can come from there.

### Note

The header page is protected separately from the section loop above it:
`SizeOfHeaders` covers the DOS stub, the NT headers and the section table, none
of which lives inside a section.

---

## 0043-ntdll-a-dump-that-drowns-the-channel-and-a-fault-that-names-nothing.patch

**The instrument that found `0044`.** Two changes, both about being able to read
the `virtual` channel at all.

`VIRTUAL_DEBUG_DUMP_VIEW` moves to its own `virtual_views` channel. It fires on
every view create, split and protection change, and FEX's JIT changes protections
continuously — so on a real title `+virtual` cannot be read. Censused on a
Requiem session: **75,505 of the first 89,000 lines were that one macro, 85% of
the log.** It filled a 32 MB head budget in 90 seconds and dropped 1.87 million
lines, taking the records the channel exists for (`NtAllocateVirtualMemory` and
its neighbours, 6% of the same sample) into the elision with them. After the
split, the same 8 MB window held 9,208 allocation records instead of 1,239.

Nothing below it is silenced: second place in that census is 4.5%, and quieting
more would hide the record rather than reveal it. `dump_view`'s own `TRACE`s move
with it — gating the call while leaving them on the default channel would make
`+virtual_views` alone print nothing.

Second, `virtual_handle_fault` now names the view a fault it *could not repair*
landed in — base, size, bytes actually committed, page protection — and, in the
branch that mattered, says when no view covers the address at all. Silent until
it fires, and it fires on a fault that is already fatal, which is why the walk
over the view's pages is unbounded.

**Caveat for whoever reads its output next:** "unrepaired by this layer" is not
"fatal". FEX handles many of these itself; one session logged 522 on `re9.exe`'s
own image from write-watch alone.

---

## 0030-ntdll-name-the-code-that-took-a-critical-section.patch

Names the holder of a critical section that times out, which
`RtlpWaitForCriticalSection` otherwise reports only as a thread id.

**`took it at 0` was the instrument, not the lock.** The recording table had 256
slots with one entry each, and `crit_site_get_site` returned NULL on a key
mismatch — so a section displaced by another hashing to the same slot printed
*identically* to one that was never recorded. The tell was in one session: one
stuck section named its holder while another printed zero, in the same minute.
Now 1024 slots, four-way probing, and `crit_section_forget_site` on release.

First run afterwards, naming a holder immediately:

```
section 0000006FFFF80E18 "loader.c: loader_section" wait timed out in thread 0160,
  blocked by 0154 which took it at 0000006FFFED20B8
```

ntdll at `0x6FFFE30000` makes that `ntdll+0xA20B8`, and **the resolution checks
itself** — two instructions earlier the same function computes `0x180150e18`, the
exact section the message names:

```
1800a20ac:  add  x21, x21, #0xe18   ; -> runtime 0x6FFFF80E18
1800a20b4:  bl   RtlEnterCriticalSection
1800a20b8:                          ; <- recorded site: loader_init +0x54
```

---

## 0002-ntdll-map-pe-images-anonymously-on-android.patch

Forces image mappings through Wine's `removable` path, which `pread()`s sections
into anonymous memory instead of mapping the file.

Worth reading before touching anything about image loading, because it is *why*
relocation is routine on this platform: SELinux refuses `execmod` on a **dirtied**
private file mapping, Wine writes relocations into an image before protecting its
sections, and so any PE loaded away from its `ImageBase` could never execute. Its
own measurement — `wineboot.exe` at its preferred base mapped `c-r-x` fine while a
relocated `ntdll.dll` failed — is the same relocation that `0044` is about.
