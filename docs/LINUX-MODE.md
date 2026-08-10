# Linux mode

Whether a container could be an Ubuntu ARM64 userland instead of a Wine prefix,
what it would cost, and the arguments against doing it at all.

**Every claim about this repository carries a `file:line`. Every claim about the
platform is marked as one of three things: *verified here* (measured on the
device by this project and written down), *known* (independent knowledge, high
confidence, not measured here), or *unsure* — with the experiment that would
settle it.** This project has been wrong three times in one day about confident,
plausible causes (`docs/TODO.md:837-843`), so an "unknown" here is deliberate and
is worth more than a guess.

---

## Verdict

**An Ubuntu ARM64 userland cannot be run from this app as it is built today, and
PRoot does not change that.** The blocker is not `chroot`, not glibc's presence
on disk, and not the GPU. It is that **Android will not `execve` a file in the
app's own data directory at `targetSdk` 29 and above** — verified here,
`docs/ARCHITECTURE.md:207-216` — and the one escape hatch this project uses,
`execve("/system/bin/linker64", [linker, binary, …])`, hands the program to
**bionic's** dynamic linker, which cannot load a glibc ELF. PRoot is a `ptrace`
supervisor that fakes a root directory; it intercepts syscalls, it does not grant
permissions the process did not already have, and the first thing it would try to
do is `execve` the guest's `ld-linux-aarch64.so.1` out of `filesDir`.

So the honest verdict has three parts:

1. **Ubuntu ARM64: not without one of two things this repository does not have.**
   Either a **custom in-process ELF loader** — a bionic process that `mmap`s
   glibc's `ld.so` itself and jumps to it, never asking the kernel to exec
   anything (unproven; see *The four ways round it*, option C) — or **dropping
   `targetSdk` to 28** (`app/build.gradle.kts:114` says 36), which is what Termux
   and Winlator do and which is a product regression, not a fix.

2. **A bionic ARM64 userland — Termux-shaped, not Ubuntu — is feasible today**,
   using mechanisms this repository has already measured on this device. It is
   the only version of "Linux mode" whose execution path is *proven* rather than
   hoped for. It brings no `apt`, no `dpkg`, and no Ubuntu packages.

3. **The GUI half is the cheap half.** The vendored X server, the compositor and
   the taskbar need no change at all: the server binds its socket in the
   *abstract* namespace (`app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java:51-53`),
   which any process on the device in the same network namespace can reach with
   an unmodified libxcb. The expensive part of graphics is the driver, not the
   display — see §3.

**Cost, if pursued.** Roughly: one day of probes to find out whether option C is
even possible; about a week for a bionic CLI userland; two to four weeks for a
glibc rootfs *if* the loader works and unbounded if it does not; two to three
weeks more for a GUI; four to eight for x86. Those are estimates in a project
whose last three confident estimates were wrong, and the first number is the only
one worth acting on.

**And read §9 before acting on any of it.** This project refused Linux support
once, on record, for reasons that are still true.

---

## What this repository already decided, and why

`docs/TODO.md:513-517`, closing the launch-type matrix:

> The last three are the point, not an afterthought. They cannot work here —
> Android is bionic, not glibc, and Vessel ships FEX as Wine's two translation
> DLLs, not FEXLoader — so the test is that the UI never presents them and never
> fails silently. Supporting them would mean a glibc rootfs and proot, which is a
> different product.

The two entries that refusal closes are `docs/TODO.md:488-492`:

> - `[x]` `.sh` — **never offered.** "Add as app" disabled, and no refusal banner:
>   it is `NotAProgram`, which is a different statement from a refusal and is the
>   right one.
> - `[x]` Linux ELF — same, using a real aarch64 Android ELF
>   (`out/vulkan/vkdriverprobe`). Disabled, no banner.

Note what was tested: **a real aarch64 *Android* ELF** — a bionic binary — was
refused by the UI. That refusal is a UI contract, not a capability statement. The
same repository execs bionic ELFs out of `filesDir` every time a session starts.

The other standing decision this reverses is `docs/ARCHITECTURE.md:3-18`, **"One
kind of container, no switch"**, and specifically `docs/ARCHITECTURE.md:38-42`:

> Because there is only one kind of container, an executable can never be "in the
> wrong one" — the badge is information, not a warning.

A mode switch is exactly the thing that makes "in the wrong container" possible
again. §6 says what it would take to keep that honest; §9 argues it may not be
worth it.

---

## 1. Execution: the wall, precisely

### 1.1 The three rules Android actually applies

All three are *verified here* — measured by this project on this device, with the
probe named.

| Rule | Where | Status |
|---|---|---|
| `dlopen` of a `.so` in `filesDir` is **permitted**, at any `targetSdk` | `docs/ARCHITECTURE.md:200-205` | verified here |
| `execve` of a binary in `filesDir` is **denied** at `targetSdk` ≥ 29; `execve("/system/bin/linker64", [linker, prog, …])` is **permitted** | `docs/ARCHITECTURE.md:207-216`, `app/src/main/java/app/vessel/core/WineLaunch.kt:5-27` | verified here (`wine --version` answers this way "and in no other way") |
| Exec is permitted **only out of `app_data_file`** — a binary in `/data/local/tmp` cannot be run by the app even world-readable | `docs/ARCHITECTURE.md:230-232` | verified here |
| Making a **dirtied** private page executable fails with `execmod`; a **clean** file mapping → `RX` succeeds | `docs/ARCHITECTURE.md:239-249`, probe `tools/probe/mapexec.c` | verified here, four-line measurement |

The fourth row is the one that killed Wine's PE loader and forced
`patches/wine/0002` (`docs/ARCHITECTURE.md:251-264`). **It is probably not a
problem for ELF**, and the reason is worth stating because it is the one piece of
good news in this section: Wine dirties a PE's `.text` because it applies
relocations *into* the code before protecting it. Position-independent ELF does
not relocate text — it relocates the GOT and `.data.rel.ro`, which are never
executable. So an ELF loaded from a clean file mapping should reach `RX` on the
`ok clean private page -> RX` line of that measurement. *Reasoned from the
recorded measurement, not itself measured.* It matters only for option C below.

### 1.2 `linker64` does not generalise to glibc. This is the key question, and the
answer is no.

`app/src/main/java/app/vessel/core/WineLaunch.kt:139-140` is the only shape of
command this app runs:

```kotlin
fun linkerArgv(binary: File, arguments: List<String> = emptyList()): List<String> =
    listOf(SYSTEM_LINKER, binary.absolutePath) + arguments
```

and `patches/wine/0003` pushes the same trick down into Wine so that *child*
processes get it too (`patches/wine/0003-ntdll-exec-child-loaders-through-the-system-linker.patch:70-71`).
It works for Wine's unix side because that side is a **host bionic ELF** — the
delivery table at `docs/ARCHITECTURE.md:270-275` labels it exactly that.

`/system/bin/linker64` is bionic's dynamic linker. Handed a glibc executable it
would map the image and then try to satisfy `DT_NEEDED: libc.so.6`, which is
glibc's C library — and glibc's `libc.so.6` is not a self-contained shared object.
It depends on symbols and on private ABI that only glibc's own
`ld-linux-aarch64.so.1` provides (`_rtld_global`, `_rtld_global_ro`,
`_dl_find_object`, the `__libc_early_init` handshake, glibc's own TLS/TCB layout,
`dl_iterate_phdr` semantics). Bionic's linker provides none of it, and bionic's
TCB layout is not glibc's. *Known, high confidence; not measured here.*

The corollary matters as much: **you cannot fix this by exec'ing glibc's loader
either.** `execve("<rootfs>/lib/ld-linux-aarch64.so.1", …)` is an exec of a file
in `filesDir`, which is the denied case. And
`execve("/system/bin/linker64", [linker, "<rootfs>/lib/ld-linux-aarch64.so.1", prog])`
asks bionic's linker to run glibc's loader as a program — *unsure*, and the
cheapest experiment in this whole document:

```
run-as app.vessel /system/bin/linker64 <files>/rootfs/lib/ld-linux-aarch64.so.1 --version
```

One adb command from the app's own uid. Predicted to fail (glibc's `ld.so` is a
static PIE that expects to *be* the interpreter, with its own auxv/TLS setup that
bionic's linker has already done differently), but predicted is not measured, and
this is a five-minute answer to the question the whole document turns on.

### 1.3 What PRoot is, and what it is not

*Known, high confidence; not verified here — nothing in this repository has ever
run PRoot.*

PRoot is a `ptrace`-based supervisor. It starts the guest with
`PTRACE_TRACEME`, stops it on syscall entry and exit (`PTRACE_SYSCALL`), and
rewrites path arguments so that `/usr/bin/apt` reaches the kernel as
`<rootfs>/usr/bin/apt`. It also fakes `chroot`, fakes uid 0 (`-0`), and
implements bind mounts (`-b host:guest`) as more path rewriting. Everything it
does is a lie told to the guest about paths and identity.

Three consequences, each load-bearing here:

- **It grants no permission.** The guest runs as the app's uid in the app's
  SELinux domain. Every `execve` PRoot lets through is still an `execve` the
  kernel and SELinux judge on their own terms. PRoot solves `chroot`; it does not
  solve W^X. **This is the misconception the whole idea rests on.**
- **The cost is two context switches per intercepted syscall**, plus the ptrace
  stop/continue round trip and any memory the tracer has to peek/poke. That is
  cheap for a compute loop and expensive for anything syscall-bound. A GUI
  workload is squarely in the second category: X protocol traffic, `poll`,
  `recvmsg` with SCM_RIGHTS, `ioctl` on `/dev/kgsl-3d0` per submit. Published
  PRoot overheads on file-heavy work are commonly quoted in the 2-10× range and
  near-zero on pure compute; *unsure* as to what it costs *this* workload, and
  the only thing that would settle it is running the present benchmark
  (`tools/gfx/x11present.c`, baseline 2.245 ms/frame at `docs/TODO.md:78-84`)
  under a tracer and comparing.
- **`ptrace` of one's own children inside an app sandbox** is what Termux relies
  on, so it is presumably allowed by `untrusted_app` policy — *unsure at
  `targetSdk` 36 on Android 16*, and settled by a ten-line probe next to
  `tools/probe/mapexec.c`: fork, `PTRACE_TRACEME`, `PTRACE_SYSCALL` loop, report
  the first `errno`.

### 1.4 The four ways round it, ranked

**A. A bionic userland — no rootfs, no PRoot, no glibc.** *Feasible today.* Build
coreutils/busybox/bash against the NDK, ship them as a `.wcp`, start them with
`linkerArgv` exactly as `wineserver` is started. This is what Termux's package
set actually is: bionic binaries with a `$PREFIX` that is not `/`. Every
mechanism it needs is already proven in this repository. What it is *not* is
Ubuntu: no `apt`, no `dpkg`, no Debian package archive, and every program must be
built by us or by Termux.

**B. Drop `targetSdk` to 28.** *Feasible, and a regression.* `docs/ARCHITECTURE.md:218-221`
records that this is precisely why Winlator can do what it does — "Read any
Winlator technique that appears to execute downloaded binaries directly with its
manifest in hand." It ends the `play` flavour's reason to exist
(`app/build.gradle.kts:175-179`), gives up scoped-storage behaviour the drive
mapper is written against, and trades the project's own stated standard for
someone else's shortcut. Listed because it is real, not because it is
recommended.

**C. A custom in-process ELF loader.** *Unproven, and the only route to Ubuntu
that keeps `targetSdk` 36.* A small native library in the APK — `dlopen` from
`nativeLibraryDir` is unrestricted (`docs/ARCHITECTURE.md:200-205`) — that
`mmap`s `<rootfs>/lib/ld-linux-aarch64.so.1` from a clean file mapping, builds a
synthetic stack and auxv (`AT_PHDR`, `AT_ENTRY`, `AT_BASE`, `AT_RANDOM`,
`AT_HWCAP`…), and jumps to its entry point. The kernel never execs anything, so
the `execve` rule never applies; the pages are clean file mappings, so `execmod`
never applies (§1.1). From that point the process *is* a glibc process: glibc's
`ld.so` sets `TPIDR_EL0` for its own TLS, which orphans bionic's TCB in the same
process — so nothing bionic (including the JVM, `liblog`, or any Android API) can
be called afterwards. That is acceptable if the process is a dedicated guest
launcher and fatal if it is the app's own process.
*Unsure.* What would settle it: a probe that does exactly this against a real
`ld-linux-aarch64.so.1` and prints from a `hello` linked against glibc. Same
directory, same shape as `tools/probe/mapexec.c`.

**D. A user-mode emulator as the loader.** *Feasible, and slow, and interesting
for one case only.* `qemu-aarch64` and FEXLoader both load the guest ELF
themselves in userspace and never issue `execve` for it. Running aarch64 guests
on an aarch64 host under `qemu-user` is possible and absurd — it is a full
interpreter/JIT for code the CPU could execute directly. But it inverts for x86:
see §4.

**Recommendation.** If Linux mode is done at all, do **A** and be honest in the
UI that it is a bionic userland and not Ubuntu, and spend one day on the **C**
probe before promising anything Debian-shaped. Do not build anything on PRoot
until the exec question is answered, because PRoot answers a different question.

---

## 2. glibc and bionic in one process

Three separate problems, often conflated:

1. **Loading.** Covered in §1.2. Bionic's linker cannot load glibc objects;
   glibc's loader cannot be exec'd. This is the wall.
2. **Coexistence.** If option C works, the process has bionic's libc mapped (the
   launcher loaded it) and glibc's libc mapped (the guest's loader loaded it),
   with one `TPIDR_EL0` between them. They cannot both own thread-local storage.
   The workable shape is a hard boundary: bionic before the jump, glibc after,
   and no calls back. Anything wanting to cross that boundary — a log line, a
   Vulkan call into an Android driver — has to go over a socket, which is
   precisely the Vortek architecture this project congratulated itself on not
   needing (`docs/ARCHITECTURE.md:581-584`).
3. **Syscalls.** Both libcs talk to the same Linux kernel with the same ABI, so
   below libc there is no problem at all. Android's kernel is Linux; the
   restrictions are SELinux and seccomp on the app domain, and those apply to the
   guest identically because the guest inherits the domain.

The one thing that does *not* need solving: `/dev/kgsl-3d0`. The app's uid can
open it today — that is what makes Turnip work (`docs/ARCHITECTURE.md:302-308`) —
and a child process, PRoot'd or not, inherits the same uid and domain. A glibc
guest would have the same access to the same device node.

---

## 3. Graphics

### 3.1 What is reusable, unchanged

**The X server, entirely.** `docs/ARCHITECTURE.md:549-561` vendors Winlator's
server into `app/src/main/java/com/winlator/`, and Vessel's own change to it is
the one that makes this section easy —
`app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java:34-53`:

> Vessel's guest is an ordinary child process of the app, so there is no root to
> relocate `XSERVER_PATH` into and Android has no `/tmp` for it to land in
> either. The abstract namespace has no filesystem, so the guest's unmodified
> libxcb — which tries `"\0/tmp/.X11-unix/X0"` before the filesystem path — finds
> the server with no configuration at all.

The name is derived in one place, `app/src/main/java/app/vessel/core/SessionDisplay.kt:459-468`.
An abstract-namespace socket lives in the network namespace, not the filesystem,
so **a PRoot'd guest with a fake `/` reaches it exactly as a bare child process
does** — the guest's rootfs does not need `/tmp/.X11-unix` to exist. A glibc
libxcb tries the abstract name first for the same reason Wine's does. `DISPLAY`
is `:0` (`app/src/main/java/app/vessel/core/SessionEnvironment.kt:202-203`) and
`XDG_RUNTIME_DIR` already points at the container's `tmp/`
(`app/src/main/java/app/vessel/core/WineLaunch.kt:229-231`). *Reasoned from the
code and from how libxcb opens a display; not measured with a non-Wine client.*

The compositor, the taskbar, the frame-rate readout and the session metrics all
sit above the X protocol and know nothing about what is on the other end.

**MIT-SHM is the one display-side thing that would need work.** The fd-passing
shm server binds a *filesystem* path, `/tmp/.sysvshm/SM0`
(`app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java:8`), and Wine
finds it through an environment variable Vessel added
(`app/src/main/java/app/vessel/core/SessionDisplay.kt:441-442`). A stock Linux
client has no such variable. Under PRoot this is the one place PRoot *helps*: a
bind mount can put that socket at the path the client already looks for. Without
PRoot, a Linux guest would fall back to `XPutImage`, which costs one copy per
damaged region — the same 2D-not-game cost recorded at
`docs/ARCHITECTURE.md:622-626`.

### 3.2 The driver is the expensive part, and the existing package cannot be reused

`build/turnip.sh:6` states what the shipped driver is:

> Turnip is a bionic ELF loaded by libadrenotools at runtime, so it is built with
> the NDK — not llvm-mingw

and the ICD variant, which is the one actually shipped
(`app/build.gradle.kts` bill of materials, the `turnip-…-icd-canoe.wcp` entry),
is described at `build/turnip.sh:66-68` as:

> a normal Linux Vulkan ICD that happens to be linked against bionic and talks to
> /dev/kgsl. It is the shape Termux's freedreno ICD package already ships.

**"Linked against bionic" is the whole answer.** `docs/ARCHITECTURE.md:581-584`
already wrote the rule down, for a different reason:

> **No Vortek.** Vortek exists because box64 runs a glibc x86-64 Wine, and glibc
> code cannot call bionic's Vulkan driver […] Vessel's Wine is bionic-native
> ARM64EC, so it calls `libvulkan.so` directly. The problem Vortek solves does
> not occur here.

Put a glibc userland in a container and **the problem Vortek solves occurs here**.
So, plainly: **the existing Turnip package cannot be reused by a glibc guest.**

Three ways out, in increasing cost:

- **A software renderer.** llvmpipe/lavapipe built for glibc aarch64, no GPU at
  all. Correct pixels, no driver risk, useless for anything demanding. This is
  the right *first* GUI target because it decouples "does X work for a Linux
  client" from "does a glibc Turnip exist".
- **A second Turnip, built glibc.** Cost is a second cross toolchain
  (`aarch64-linux-gnu` plus a glibc sysroot) beside the NDK that `build/turnip.sh:15`
  sets up, and a second `.wcp`. The encouraging part, checked in the vendored
  tree: the KGSL backend is gated only on the kmd option
  (`native/mesa/src/freedreno/vulkan/meson.build:130-132`) and
  `native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc` contains **no `__ANDROID__`
  or `DETECT_OS_ANDROID` reference at all**. The three Android platform libraries
  the bionic ICD had to be given — `-llog`, `-lsync`, `-lnativewindow`, plus a
  hand-written `sync_wait` (`build/turnip.sh:206-235`) — are needed *because the
  NDK's clang defines `__ANDROID__` even when meson is told `system = 'linux'`*
  (`build/turnip.sh:166-169`). Under a real glibc toolchain `DETECT_OS_ANDROID`
  is false, the AHardwareBuffer path at
  `native/mesa/src/freedreno/vulkan/tu_device.cc:3758-3765` compiles out, and
  `util/libsync.h` uses its own poll-based implementation
  (`native/mesa/src/util/libsync.h:50-55`). So a glibc KGSL Turnip looks *more*
  likely to build cleanly than the bionic ICD did. **Never attempted.** *Unsure*;
  settled by one build.
- **A Vulkan-over-socket proxy** — i.e. reinvent Vortek. Rejected: it is the
  architecture this project explicitly avoided, it puts a marshalling layer in
  the hot path, and the glibc build above is strictly less work if it works.

---

## 4. x86 Linux binaries

Vessel ships FEX as Wine's two translation DLLs — `libarm64ecfex.dll` and
`libwow64fex.dll` (`docs/ARCHITECTURE.md:9-14`, `registry/contents.json` `fex-2608-canoe`),
not FEXLoader. Running x86 *Linux* binaries needs FEXLoader plus an x86-64
rootfs. Three costs, and the third is the one that decides it:

1. **A second FEX build target.** `build/fex.sh` produces PE DLLs today; a
   FEXLoader for Android/bionic aarch64 is a different output from the same tree.
   Real work, bounded.
2. **An x86-64 rootfs**, which is larger than the arm64 one and doubles whatever
   the storage design in §5 costs.
3. **The graphics driver would be emulated, and there is no ARM64EC to save it.**
   The reason x86 Windows is fast here is stated at `docs/ARCHITECTURE.md:16-18`:
   "Because Wine, DXVK and vkd3d are all native here, the graphics translation
   layer — usually the hottest code in a game — runs at full ARM64 speed even
   when the game itself is x86." **ARM64EC is a Windows ABI feature. Linux has no
   equivalent.** An x86 Linux guest's Mesa would be x86 code translated
   instruction by instruction, in the hottest loop in the stack, unless FEX's
   host/guest thunk libraries are built and wired up — which is a third build
   target and a whole ABI surface of its own.

One inversion worth recording, because it is counter-intuitive: FEXLoader does
its **own** ELF loading and its **own** rootfs path redirection in its syscall
layer, so it needs neither `execve` of guest binaries nor PRoot. *Medium
confidence on the path-redirection detail; not verified here.* If true, x86
Ubuntu is *mechanically* easier to start than ARM64 Ubuntu — and much worse to
run, for reason 3. That is not a reason to do it; it is a reason to distrust
"ARM64 first" as self-evident and to state the actual argument, which is: **ARM64
native is the sane first target because it is the only one where the guest's
graphics stack runs at full speed.**

---

## 5. Sharing a base userspace

### 5.1 The constraint that eliminates the obvious answer

**Unprivileged `overlayfs` is not available.** An Android app cannot call
`mount(2)` — SELinux does not grant it to `untrusted_app` — and unprivileged
overlayfs elsewhere on Linux depends on user namespaces, which Android generally
does not permit unprivileged processes to create. *Known, high confidence; not
verified on this device.* Settled in one minute by three checks from the app's
uid: `grep overlay /proc/filesystems`, `cat /proc/sys/kernel/unprivileged_userns_clone`
(or `unshare -Ur true`), and an actual `mount()` attempt. Worth running rather
than assuming — this document's whole standard is that plausible is not measured.

Also unavailable: **reflink/`FICLONE` copy-on-write**. ext4 and f2fs, which is
what Android internal storage is, do not support it. *Known, high confidence.* So
"copy the base per container" means a real byte-for-byte copy.

### 5.2 The three sharing designs, and why two are rejected

| Design | Mechanism | Verdict |
|---|---|---|
| Read-only shared base + per-container writable dirs, joined by **PRoot bind mounts** | `proot -r <base> -b <ctr>/etc:/etc -b <ctr>/home:/home …` | **The only viable one** — and only if PRoot is viable at all (§1) |
| **Hardlink farm** — link every base file into each container | `link(2)` per file | **Rejected.** A guest writing through a hardlink mutates the shared inode. Every container sees the edit. Nothing in a Linux userland breaks links before writing; that is the filesystem's job and this filesystem does not do it. Silent cross-container corruption. |
| **Symlink tree** (`lndir`-style) — a per-container tree of symlinks into the base | `symlink(2)` per file | **Rejected twice.** Same write-through problem as hardlinks, *and* it recreates the exact hazard class of this project's worst defect at a hundred thousand files instead of four. |

That last point is not rhetorical. `docs/TODO.md:651-663`:

> **Deleting a container deleted the user's mapped folders.** The most serious
> defect this project has had. `File.deleteRecursively()` walks with
> `listFiles()`, and `listFiles()` on a symlink to a directory returns the
> *target's* children — and a container's `dosdevices` is nothing but such
> symlinks. Deleting a container emptied the phone's shared storage and every
> mapped folder, removed the now-empty links, and reported success. Reported as
> downloaded games disappearing, twice.

It is fixed — `app/src/main/java/app/vessel/core/DeleteTree.kt:36,48` uses
`Files.walkFileTree`, which does not follow links — and
`docs/TODO.md:849-853` names the general lesson: *"a rule stated for one call
site and not applied to the next one."* A symlink farm would put a hundred
thousand new call sites' worth of links inside the very directory that gets
deleted wholesale.

### 5.3 The design, if it is built

```
filesDir/
  components/LinuxBase/<versionCode>/     read-only, shared, one copy per device
  containers/<id>/
    prefix/                               untouched — Windows mode's Wine prefix
    linux/
      etc/    var/    home/    root/      per-container, writable, seeded from base
      usr-local/  opt/  srv/              per-container, writable, empty at first
    tmp/                                  already exists; XDG_RUNTIME_DIR
```

Rules the design must hold to, each traceable to something that already went
wrong or to something the code already promises:

1. **The shared base is a `ComponentType`, so the store's reference counting owns
   its lifetime.** `ComponentStore.prune()` is the only thing that deletes a
   component, nothing calls it automatically, and it refuses to touch a version
   any container references (`app/src/main/java/app/vessel/data/ComponentStore.kt:212-235`).
   Deleting a container must free a reference and never a byte
   (`docs/ARCHITECTURE.md:531-536`). That property is already correct and must be
   inherited, not re-implemented.
2. **Nothing inside `containers/<id>/linux/` may be a symlink pointing outside
   it.** Android storage reaches a Linux container only through a PRoot bind,
   never through a symlink — the exact opposite of the Windows side, where a
   drive *is* a symlink (`app/src/main/java/app/vessel/core/DriveMap.kt:28-33`).
   This is the rule that keeps §5.2's incident from recurring, and it should be
   asserted in a test, not written in a comment.
3. **Per-container copies are the small mutable directories only.** `/etc` and
   `/var` on a minimal Ubuntu base are single-digit to low-tens of MB; `/usr` and
   `/lib` are the hundreds. Sharing the second set and copying the first is the
   entire saving.
4. **The consequence of rule 3 has to be said out loud in the product: `apt
   install` cannot work per container.** `/usr` is read-only and shared. Either
   the base image *is* the package set — which fits Vessel's component model
   exactly, one `.wcp` per curated userland, versioned and provenance-stamped —
   or every container gets a writable copy of `/usr` and the sharing is gone.
   Pretending otherwise produces a container where `apt` appears to work and
   changes a file every other container reads.

### 5.4 The `.wcp` pipeline cannot carry an Ubuntu rootfs as it stands

This is concrete and cheap to check, and it is a real blocker rather than a
detail. `WcpInstaller.extract` refuses three things an Ubuntu root filesystem
tarball is full of:

- **hard links** — `app/src/main/java/app/vessel/data/WcpInstaller.kt:434-437`,
  *"hard links are never extracted — nothing we publish contains one"*. Debian's
  coreutils and busybox packages ship them.
- **device nodes, fifos, anything not a file/dir/symlink** —
  `WcpInstaller.kt:439-442`. `/dev` in a rootfs tarball is exactly this.
- **absolute symlinks** — `WcpInstaller.kt:587-595`, *"There is no such thing as
  a legitimate absolute link in a relocatable package"*. In a rootfs there is
  nothing but: `/etc/localtime`, `/etc/alternatives/*`, `/usr/lib/…` merged-usr
  links.

Every one of those refusals is correct for the packages this project publishes,
and each is argued for in the file (`WcpInstaller.kt:100-110` on why an unsafe
entry is refused loudly rather than sanitised). So a rootfs component means
either **repacking the distro tarball** into a relocatable form (drop `/dev`,
rewrite absolute links relative, break hardlinks into copies, and record what was
changed) or **a second installer path** with its own weaker rules. The first is
better: it keeps one installer, one security posture, and puts the mess in a
build script where it can be diffed.

Size, for scale. `docs/ARCHITECTURE.md:509-517` measures the current set at 75 MB
downloaded and ~988 MB unpacked, and the APK already carries 180 MB of assets to
land at 108 MB (`docs/TODO.md:431-434`, `docs/TODO.md:757`). An `ubuntu-base`
ARM64 tarball is roughly 30 MB compressed and ~110 MB unpacked; add X11, GTK and
one real application and it is 400 MB to 1.5 GB. **A rootfs does not go in the
APK.** It goes in the download channel — which means it exists only on the
`sideload` flavour, because `play` sets `CAN_INSTALL_COMPONENTS = false`
(`app/build.gradle.kts:175-179`) and the downloader refuses out loud there
(`docs/TODO.md:589`).

---

## 6. What "mode: Windows | Linux" means concretely

### 6.1 What changes

**`ContainerProfile`** (`app/src/main/java/app/vessel/core/ContainerProfile.kt:26-36`)
gains one field:

```kotlin
enum class ContainerMode { WINDOWS, LINUX }
val mode: ContainerMode = ContainerMode.WINDOWS
```

Defaulted, so every document already on a device loads unchanged — the file
already relies on that mechanism for the removed `archProfile` field
(`ContainerProfile.kt:8-13`). `wineBuild`, `driver` and `d3dLayer` are resolved
component labels for the Windows path and become meaningless in Linux mode; they
should be nullable or moved behind the mode rather than left holding stale
strings the home card will draw.

**`ComponentType`** (`app/src/main/java/app/vessel/core/ComponentPackage.kt:16-42`)
gains `LINUX_BASE("LinuxBase", "Linux base")`, and `build/package_wcp.py:29-40`
gains the matching entry. There is precedent for a Vessel-only type — `OPENGL`
was added the same way with the reasoning written down at
`ComponentPackage.kt:25-39`.

**`ComponentStore.adoptLatest` has a real bug waiting in that change.** It walks
`ComponentType.entries` and adopts the newest installed version of every type the
container does not already reference
(`app/src/main/java/app/vessel/data/ComponentStore.kt:174-177`). Add a rootfs type
and **every Windows container silently takes a reference to a gigabyte of Ubuntu
it will never open**, and `prune()` will then correctly refuse to delete it. The
fix is that adoption becomes mode-aware — which is the first place the "one kind
of container" simplification stops paying for itself, and it is worth noticing
that it appears immediately.

**The launcher** gains a second argv builder beside
`WineTree.desktopArgv` / `programArgv`
(`app/src/main/java/app/vessel/core/WineLaunch.kt:346-354, 377-380`) — a
`LinuxRoot` type with the same shape: a data class over a store directory,
pure path arithmetic, argv as a function. Whatever mechanism §1.4 settles on
(`linkerArgv(prootBinary, …)`, or a loader shim) it stays one place.

**The session environment is a new function, not a parameter on the old one.**
`sessionEnvironment()` (`app/src/main/java/app/vessel/core/SessionEnvironment.kt:373`)
is Wine-shaped throughout — `WINEPREFIX`, `WINEDLLOVERRIDES`, the DXVK and vkd3d
cache paths, `MESA_VK_WSI_DEBUG` — and its key set is asserted key-for-key
against `docs/LOGGING.md`. A Linux session needs `DISPLAY`, `XDG_RUNTIME_DIR`,
`HOME`, `PATH`, `LD_LIBRARY_PATH` for a *glibc* search order, and
`VK_ICD_FILENAMES`. Folding those into one function makes both contracts harder
to state. Two functions, one shared display seam.

**`SessionRuntime`** holds one session at a time by design
(`app/src/main/java/app/vessel/data/SessionRuntime.kt:149-155`) and starts
`wineserver` itself because only the app can build the `linker64` argv
(`SessionRuntime.kt:157-165`). Both facts carry over: a Linux session is still
one session, and its process still has to be started by the one component that
knows how to start a process here. The teardown story changes — there is no
`wineserver -k`, so ending a Linux session means signalling the process group,
which `GuestProcessTree` already reasons about
(`docs/TODO.md:664-673`).

**The launch-type matrix gains rows.** `docs/TODO.md:488-492` currently asserts
that `.sh` and Linux ELF are *never offered*. In a Linux-mode container they must
be offered, and in a Windows-mode container they must still be refused with the
same wording. That is two UI states where there was one, and it is the concrete
form of the "in the wrong container" problem `docs/ARCHITECTURE.md:38-42` says
cannot currently exist.

### 6.2 What does not change

The X server and its whole stack (`app/src/main/java/com/winlator/xserver/`), the
abstract-socket display seam, `GLRenderer` and the compositor, the taskbar and
its window actions, the frame-rate readout, the session metrics and log store,
`ContainerPaths` as the single owner of the layout
(`app/src/main/java/app/vessel/data/ContainerPaths.kt:8-43`), the component
download/verify/stage/swap pipeline, `deleteTree`, and the Components screen.
That is a genuinely large amount of reuse, and it is the strongest argument *for*
the idea.

---

## 7. Phasing

Each phase ends in one sentence that has been *watched* on the device, which is
this project's rule (`docs/TODO.md:6-9`).

**Phase 0 — four probes, one day, no product code.** In `tools/probe/`, next to
`mapexec.c`, run as the app's uid:
1. `linker64 <rootfs>/lib/ld-linux-aarch64.so.1 --version` — does bionic's linker
   run glibc's loader at all? (§1.2)
2. `ptrace` self-test: fork, `PTRACE_TRACEME`, one `PTRACE_SYSCALL` round trip.
   (§1.3)
3. `mount`/userns/overlayfs: `grep overlay /proc/filesystems`, `unshare -Ur`,
   one `mount()` call, report each `errno`. (§5.1)
4. In-process ELF loader: `mmap` `ld-linux-aarch64.so.1`, synthesise auxv, jump,
   and try to print from a glibc `hello`. (§1.4 option C)

**Nothing after this phase should be started until probes 1 and 4 have answers.**
If both fail, Ubuntu is off the table and the only remaining product is Phase 1.

**Phase 1 — a bionic CLI userland.** One `.wcp` of NDK-built busybox + bash,
started with `linkerArgv`, output into the existing session log pipe.
*Done when:* a shell command typed in the app prints its output in the session
log, in a container marked Linux mode.

**Phase 2 — a glibc ARM64 rootfs, CLI only.** Whatever mechanism Phase 0 blessed,
plus the repacked-rootfs `.wcp` of §5.4 and the shared-base layout of §5.3.
*Done when:* `cat /etc/os-release` inside the container prints Ubuntu's, and two
containers on one base each see their own `/etc/hostname`.

**Phase 3 — GUI, software first.** A glibc X client through the existing X server
with a software renderer. Only then attempt the glibc Turnip build.
*Done when:* an `xterm` (or any glibc X client) draws in the session and its
window gets a taskbar button; then, separately, a `vkcube` reports
`driver_id=18`.

**Phase 4 — x86.** FEXLoader plus an x86-64 rootfs, and only after reading §4
again.

---

## 8. Risks and open questions

| # | Question | Status | What would settle it |
|---|---|---|---|
| 1 | Does `linker64` run glibc's `ld.so` as a program? | **unsure**, predicted no | One adb command, §7 probe 1 |
| 2 | Can a bionic process `mmap` glibc's `ld.so` and jump to it? | **unsure** — the whole Ubuntu route depends on it | §7 probe 4 |
| 3 | Is `ptrace` of a child permitted to `untrusted_app` at targetSdk 36 / Android 16? | **unsure** | §7 probe 2 |
| 4 | Is unprivileged overlayfs or userns available? | **known**: no. **Not verified on this device.** | §7 probe 3 |
| 5 | What does PRoot's syscall interception cost *this* workload? | **unsure** — quoted ranges are for file IO, not X + KGSL | Run `x11present` under a tracer against the 2.245 ms baseline (`docs/TODO.md:78-84`) |
| 6 | Does a glibc aarch64 Turnip/KGSL build? | **unsure**, looks more likely than the bionic ICD did (§3.2) | One cross build with an `aarch64-linux-gnu` sysroot |
| 7 | Does a stock glibc libxcb find the abstract-namespace X socket? | **reasoned, not measured** | Any glibc X client, once one can be started |
| 8 | Does FEX do rootfs path redirection without ptrace? | **medium confidence, unverified** | Read FEXCore's syscall layer in `native/fex` |
| 9 | Can an Ubuntu rootfs be repacked to survive `WcpInstaller`'s three refusals without breaking the distro? | **unsure** — merged-`/usr` absolute links are load-bearing in Debian | Repack `ubuntu-base`, install it, run `dpkg --verify` |
| 10 | Licence compliance for redistributing a distro image | **open, unbounded** | See §9.5 |

Risk not in the table because it is not a question: **`adoptLatest` will hand every
Windows container a reference to the rootfs the moment a new `ComponentType`
exists** (§6.1, `ComponentStore.kt:174-177`). Known, and fixable in the same
change that introduces the type — but only if someone remembers, which is exactly
the failure mode `docs/TODO.md:849-853` names.

---

## 9. Reasons this might be the wrong product direction

This section exists because the project already refused Linux support once, in
writing, and a plan that ignores that is not a plan.

**9.1 The refusal was correct and nothing has changed.** `docs/TODO.md:513-517`
says supporting Linux ELF "would mean a glibc rootfs and proot, which is a
different product". Every finding above confirms the technical half of that
sentence and adds one the note did not have: *proot is not even sufficient*,
because it does not address W^X. The platform has not moved. If anything the
answer is worse than the note assumed.

**9.2 It reverses the clearest simplification in the architecture.**
`docs/ARCHITECTURE.md:3` is titled "One kind of container, no switch", and
`docs/ARCHITECTURE.md:38-42` derives a real user-facing property from it: an
executable can never be in the wrong container, so the architecture badge is
information rather than a warning. Adding a mode brings back the question, and
§6.1 shows the first bug it causes lands immediately, in `adoptLatest`, in a
function whose whole purpose is to guess correctly on the user's behalf.

**9.3 The core sentence is not finished.** `docs/TODO.md:18-21` states the goal —
*a Windows program drew on the screen through DXVK* — and `docs/TODO.md:855-858`
lists what is honestly unfinished: no D3D window, FEX asserting on large PEs,
presentation still a CPU copy with zero-copy specified and unstarted, `ipconfig`
printing nothing, `.msi` reaching a UI and not installing, and no sound. Linux
mode is a second product started before the first one works.

**9.4 The differentiator does not transfer.** What makes Vessel unusual is
ARM64EC Wine with FEX as two translation DLLs, so that DXVK and vkd3d run native
while only application code is translated (`docs/ARCHITECTURE.md:16-18`). None of
that helps a Linux container: ARM64EC is a Windows ABI feature, and §4 shows the
x86 Linux case *loses* precisely the property that makes the x86 Windows case
fast. Meanwhile Termux, proot-distro and UserLAnd have done Linux-on-Android for
a decade, are better at it, and are not fighting `targetSdk` 36.

**9.5 The licensing cost is unbounded, and §6 is already an open blocker.**
`docs/LICENSING.md` is not yet closed (`docs/TODO.md:760-787`), the project built
`build/verify_vendored.py` and `build/source_offer.py` specifically so its
obligations stay checkable, and it recorded as a fault that a release went out
while a notice item was open (`docs/TODO.md:779-780`). Redistributing a distro
image means taking on the licences of every package in it — thousands, including
GPL source-offer obligations — and doing it to the standard this project has set
for six components. That is not a small increment on an existing process; it is a
different order of problem.

**9.6 It is sideload-only, permanently.** A rootfs cannot go in the APK (§5.4),
and the `play` flavour cannot download components at all
(`app/build.gradle.kts:175-179`). So Linux mode would be a feature half the
distribution channels can never have.

**9.7 The support surface.** Today a bug is Wine's, FEX's, Mesa's or Vessel's,
and the project has been rigorous about attributing which. Every Linux
application's every bug would arrive as a Vessel bug, in a userland Vessel
assembled.

**The honest counter-argument**, stated fairly: §6.2's reuse list is long. The X
server, compositor, taskbar, component pipeline, storage layout and delete safety
all carry over untouched, and the display seam turns out to be *already*
compatible with a foreign client because of a change Vessel made for its own
reasons (`UnixSocketConfig.java:34-53`). If the Phase 0 probes come back
favourable — particularly probe 4 — then the remaining work is a rootfs packaging
problem and a second Mesa build, both bounded. That is a real case. It is just
not a case for starting now, and it is not a case for Ubuntu specifically over
the bionic userland of §1.4 option A.
