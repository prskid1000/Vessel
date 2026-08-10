# Linux mode

Whether a container could be an Ubuntu ARM64 userland instead of a Wine prefix,
what it would cost, and the arguments against doing it at all.

**Every claim about this repository carries a `file:line`. Every claim about the
platform is marked as one of three things: *verified here* (measured on the
device by this project and written down), *known* (independent knowledge, high
confidence, not measured here), or *unsure* — with the experiment that would
settle it.** This project has been wrong three times in one day about confident,
plausible causes (`docs/TODO.md:1152-1158`), so an "unknown" here is deliberate and
is worth more than a guess.

---

## Verdict

> **Amended after Phase 0 was run.** The four probes of §7 have been executed on
> the device and their results are in *Phase 0, measured* below. The short
> version: **probe 4 came back green.** A bionic process mapped Ubuntu 24.04's
> `ld-linux-aarch64.so.1` and ran `/bin/ls` and `/bin/uname` out of app storage
> at `targetSdk` 36, with no `execve` anywhere. The paragraph that follows was
> written when that was the study's biggest unknown, and it is now wrong in its
> strongest form: the wall has a door. Everything it says about `execve`,
> `linker64` and PRoot is confirmed; the conclusion drawn from them is not.
>
> **Amended again after Phase 0b.** Probe 4 has now been re-run from the app's
> own process — `u:r:untrusted_app`, `Seccomp: 2` — and the door is narrower
> than it looked. Unshimmed, all three Ubuntu binaries are **killed by
> `SIGSYS`**: the app's seccomp filter traps `set_robust_list(99)` and
> `rseq(293)` on glibc's startup path. A ~40-line signal handler returning
> `-ENOSYS` makes all three run and exit 0. And a measurement Phase 0 never
> made shows the harder half: **`execve` of `app_data_file` is denied to the
> app for a bionic ELF exactly as for a glibc one**, so the in-process loader
> starts the *first* process of a distro and nothing about the second. See
> *Phase 0b, measured* and *The decision* below. **The decision is: bionic
> userland yes, Ubuntu no.**

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

## Phase 0, measured

Device ZD2232JMB9, Motorola `vantage_g` (Snapdragon SM8845 / Adreno 829),
Android 16, `ro.build.version.sdk = 36`, kernel
`6.12.38-android16-5-gdda2539c405d-ab14915528-4k`, SELinux **Enforcing**, 4 KiB
pages. App `app.vessel`, uid 10453, the shipped debug build, `targetSdk` 36.
Probes in `tools/probe/`: `glibcload.c`, `ptraceprobe.c`, `nsprobe.c`, driven by
`tools/probe/linuxmode.sh`, glibc artifacts extracted by
`tools/probe/fetch-glibc.sh` from `ubuntu:24.04` arm64 (glibc 2.39-0ubuntu8.8).
Everything below is output copied off the device, not paraphrase.

### The one caveat that applies to all four, stated first

Every probe ran through **`run-as app.vessel`**, which is what
`tools/probe/build.sh` has always done. `run-as` gives the app's **uid** and the
app's **MLS categories** but *not* the app's **domain**:

```
$ adb shell "run-as app.vessel id"
uid=10453(u0_a453) … context=u:r:runas_app:s0:c197,c257,c512,c768
$ adb shell "cat /proc/14853/attr/current"          # the live app process
u:r:untrusted_app:s0:c197,c257,c512,c768
```

and it does not carry the app's seccomp filter either:

```
$ adb shell "grep -E 'Seccomp|NoNewPrivs' /proc/14853/status"   # app process
NoNewPrivs:	0
Seccomp:	2
Seccomp_filters:	1
                                                                # run-as process
  NoNewPrivs:	0
  Seccomp:	0
  Seccomp_filters:	0
```

So `runas_app` ≠ `untrusted_app` and Seccomp 0 ≠ Seccomp 2. Where that gap
matters it is closed below by reading the device's own compiled policy,
`/system/etc/selinux/plat_sepolicy.cil`, which is world-readable — that is
*policy inspection*, a weaker thing than a measurement, and it is labelled as
such each time. The one residual that neither a `run-as` run nor the policy can
settle is seccomp, and §*Probe 4* says exactly what is left of it.

> **Closed by Phase 0b, and it did not carry over.** Every inference in this
> section has been re-measured in `untrusted_app` with `Seccomp: 2`. The
> SELinux half held. The seccomp half did **not**: the filter traps two of the
> syscalls glibc issues at startup and the guests die. See *Phase 0b,
> measured*.

Two lines from that policy are load-bearing for everything here, and both are
quoted verbatim from the device:

```
30591:(allow untrusted_app_all app_data_file (file (ioctl read getattr lock map execute open watch watch_reads)))
26484:(allow runas_app       app_data_file (file (execute_no_trans)))
```

`untrusted_app_all` has **`execute`** — the permission a `PROT_EXEC` file
mapping and `dlopen` need, which is `docs/ARCHITECTURE.md:200-205` restated as
policy — and has **neither `execute_no_trans`** (what `execve` needs) **nor
`execmod`**. `runas_app` has `execute_no_trans`. And `plat_seapp_contexts:37`
confirms which domain this app lands in:

```
user=_app minTargetSdkVersion=34 domain=untrusted_app type=app_data_file levelFrom=all
```

with `untrusted_app_25` and `untrusted_app_27` — the `targetSdk` ≤ 27 domains —
being the only ones granted `execute_no_trans` *and* `execmod` on
`app_data_file` (`plat_sepolicy.cil:30438-30440, 30478-30480`). That is
`docs/ARCHITECTURE.md:207-221`'s exec rule and its "Winlator is not a guide
here" note, both visible as four lines of the device's own policy.

### Probe 1 — `linker64` handed glibc's `ld.so`. **Predicted no; measured no.**

```
$ adb shell "run-as app.vessel sh -c '/system/bin/linker64 \
    /data/data/app.vessel/linuxprobe/rootfs/lib/ld-linux-aarch64.so.1 --version'"
Could not find a PHDR: broken executable?
Aborted
rc=134
```

`SIGABRT`, not an errno: bionic's linker aborts before it ever reaches
`DT_NEEDED`. glibc's `ld.so` is a **static PIE with no `PT_PHDR` program
header** — `readelf -l` on the extracted file lists exactly seven segments,
`LOAD LOAD DYNAMIC NOTE GNU_EH_FRAME GNU_STACK GNU_RELRO`, and no `PT_PHDR` —
and bionic's linker refuses anything it cannot find a `PT_PHDR` in. §1.2
predicted failure for a subtler reason (auxv and TLS set up differently); the
real answer is cruder and arrives earlier. Prediction right, reasoning wrong,
and the error message is the record.

**One result here contradicts the study and must not be misread.** The contrast
case — exec'ing the loader *directly* out of app storage, the case §1.2 calls
"the denied case" — **succeeded**:

```
$ adb shell "run-as app.vessel sh -c '/data/data/app.vessel/linuxprobe/rootfs/lib/ld-linux-aarch64.so.1 --version'"
ld.so (Ubuntu GLIBC 2.39-0ubuntu8.8) stable release version 2.39.
rc=0
```

This is **not** a hole in the app sandbox and nothing may be built on it. It is
the `runas_app` line of policy quoted above: `run-as` has `execute_no_trans` on
`app_data_file` and `untrusted_app` does not. It is recorded because it is
exactly the plausible-looking line that has been wrong here before
(`docs/TODO.md:1152-1158`) — a probe that "proves" `execve` works while running
in the wrong domain would have inverted this entire document. `run-as` is a
debugging shim; it is not the app.

### Probe 2 — `ptrace`. **Assumed permitted; measured permitted, in full.**

`run-as app.vessel ./linuxprobe/ptraceprobe`, verbatim:

```
  /proc/sys/kernel/yama/ptrace_scope           (unreadable: No such file or directory)
  TracerPid:	0
  CapEff:	0000000000000000
  NoNewPrivs:	0
  Seccomp:	0

PTRACE_TRACEME + one PTRACE_SYSCALL round trip:
  ok    child stopped under TRACEME
  ok    PTRACE_SETOPTIONS PTRACE_O_TRACESYSGOOD
  ok    PTRACE_SYSCALL (to entry)
  ok    syscall-entry stop
  ok    PTRACE_GETREGSET NT_PRSTATUS
  info  syscall nr x8=172, pc=0x7965744464 (__NR_getpid is 172)
  ok    syscall-exit stop
  info  return value x0=15835 (child pid is 15835)
  ok    PTRACE_PEEKDATA at the tracee's pc
  ok    PTRACE_CONT

PTRACE_ATTACH to a running child:
  ok    PTRACE_ATTACH
  ok    attach stop observed
  ok    PTRACE_DETACH

0 failure(s)
```

No errno anywhere: every call PRoot makes — `TRACEME`, `SETOPTIONS`, the
`PTRACE_SYSCALL` entry/exit pair, `GETREGSET` to read the syscall number and
`PEEKDATA` to reach the tracee's memory, plus `ATTACH`/`DETACH` — works. There is
no Yama `ptrace_scope` on this kernel at all.

This is the one probe whose `runas_app` result carries to `untrusted_app` on
more than a hunch, because the policy grants it explicitly
(`plat_sepolicy.cil:30637`):

```
(allow untrusted_app_all self (process (ptrace)))
```

*Measured under `runas_app`; the permission is granted to `untrusted_app_all` by
policy inspection.*

### Probe 3 — overlayfs, user namespaces, `mount(2)`. **Predicted no; measured no, and worse than "no".**

`run-as app.vessel ./linuxprobe/nsprobe`, verbatim:

```
1. overlayfs support in the kernel:
  /proc/filesystems unreadable: Permission denied
  /proc/sys/kernel/unprivileged_userns_clone     absent (No such file or directory)
  /proc/sys/user/max_user_namespaces             absent (Permission denied)
  /proc/sys/user/max_mnt_namespaces              absent (Permission denied)

2. namespaces:
  FAIL  unshare(CLONE_NEWUSER): Invalid argument (errno 22)
  FAIL  unshare(CLONE_NEWNS): Operation not permitted (errno 1)
  FAIL  unshare(CLONE_NEWUSER|CLONE_NEWNS): Invalid argument (errno 22)
  FAIL  unshare -Ur equivalent (userns + uid_map 0): Invalid argument (errno 22)

3. mount(2):
  FAIL  mount(tmpfs): Permission denied (errno 13)
  FAIL  mount(overlay): Permission denied (errno 13)
  FAIL  mount(MS_BIND): Permission denied (errno 13)
  FAIL  mount(overlay) inside a mapped-root userns: Invalid argument (errno 22)

8 failure(s)
```

and toybox agrees, for both of §5.1's suggested one-liners:

```
$ adb shell "run-as app.vessel grep overlay /proc/filesystems"
grep: /proc/filesystems: Permission denied      (rc=2)
$ adb shell "run-as app.vessel unshare -Ur true"
unshare: Invalid argument                       (rc=1)
```

Three things this settles that the section did not know:

- **`EINVAL` on `CLONE_NEWUSER`, not `EPERM`.** That is the kernel saying the
  flag does not exist, not the sandbox saying no. `/proc/self/ns/` on this
  device contains `cgroup mnt net time time_for_children uts` and **no `user`,
  no `pid`, no `ipc`** — user namespaces are not compiled into this kernel, so
  there is nothing for a policy or a sysctl to permit. Unprivileged overlayfs is
  not merely denied here, it is unreachable by construction.
- **`CLONE_NEWNS` fails differently — `EPERM`** — because mount namespaces *do*
  exist on this kernel and the app simply lacks `CAP_SYS_ADMIN` (`CapEff:
  0000000000000000`). Two different errnos for two different reasons, which is
  the kind of distinction §5.1 was assuming away.
- **§5.1's first suggested check cannot be run by the app at all.**
  `/proc/filesystems` is labelled `u:object_r:proc_filesystems:s0` and no app
  domain is granted it — `plat_sepolicy.cil:11550` neverallows the app
  typeattribute and the 20-odd `allow` lines for it are `init`, `apexd`,
  `installd`, `dex2oat` and friends. Whether the kernel has overlayfs compiled
  in is therefore *not measurable from inside the app*; it is also moot, since
  mounting it needs `CAP_SYS_ADMIN` the app does not have.

`mount(2)` is `EACCES` in all three unprivileged shapes. §5.1's conclusion
stands, its errnos are now on the record, and the hardlink/symlink rejections of
§5.2 remain the only reason the sharing question is interesting at all.

### Probe 4 — the in-process ELF loader. **Unproven; measured working.**

This is the result the document turns on, so it is quoted at length.
`tools/probe/glibcload.c` maps every `PT_LOAD` of `ld-linux-aarch64.so.1` from a
**clean private file mapping**, `MAP_FIXED` over one `PROT_NONE` reservation —
the exact shape `tools/probe/mapexec.c` measured as permitted — synthesises a
System V stack whose auxv is copied from `/proc/self/auxv` with
`AT_PHDR`/`AT_PHENT`/`AT_PHNUM`/`AT_BASE`/`AT_ENTRY`/`AT_EXECFN`/`AT_RANDOM`
overridden, and branches to the entry point. No `execve` is issued at any point,
by the probe or by glibc afterwards.

```
glibcload: page size 4096, uid 10453
mapping:
  info  …/rootfs/lib/ld-linux-aarch64.so.1: type ET_DYN, entry 0x1aa40, 7 phdrs
  ok    reserve span PROT_NONE
  info  span 0x42000 at base 0x6ffc720000 (page 4096)
  ok    PT_LOAD[0] file map r-x at 0x6ffc720000 len 0x27000
  ok    PT_LOAD[1] file map rw- at 0x6ffc75e000 len 0x4000
  info  entry 0x6ffc73aa40 phdr 0x6ffc720040 phnum 7
  ok    guest stack (8 MiB anon RW)
  info  guest sp 0x6f7c3fe570, 20 inherited auxv entries
jumping to 0x6ffc73aa40 - anything below this line came from glibc
ld.so (Ubuntu GLIBC 2.39-0ubuntu8.8) stable release version 2.39.
Copyright (C) 2024 Free Software Foundation, Inc.
…
RESULT: guest exited 0
```

Not just the loader — three stock Ubuntu 24.04 arm64 programs, loaded by that
loader with `--library-path` pointing into app storage, each exiting 0:

```
--- guest program: echo hello-from-glibc
hello-from-glibc
RESULT: guest exited 0

--- guest program: ls -l /data/data/app.vessel/linuxprobe/rootfs/lib
total 200
-rwxr-xr-x. 1 10453 10453 203968 Aug 10 06:33 ld-linux-aarch64.so.1
RESULT: guest exited 0

--- guest program: uname -a
Linux localhost 6.12.38-android16-5-gdda2539c405d-ab14915528-4k #1 SMP PREEMPT Fri Feb 20 19:49:35 UTC 2026 aarch64 aarch64 aarch64 GNU/Linux
RESULT: guest exited 0
```

`ls` is the strong one: glibc's loader resolved a three-deep `DT_NEEDED` graph —
`libselinux.so.1`, `libpcre2-8.so.0`, `libc.so.6` — mapped all of them
`PROT_EXEC` out of `app_data_file`, ran `getdents64`, queried an SELinux xattr
(the `.` after the mode bits is real), tried glibc NSS and fell back to the
numeric uid. That is a functioning glibc userland inside an `untrusted_app`-uid
process at `targetSdk` 36.

Two predictions in §1.1 and §1.4 are confirmed by this and worth ticking
explicitly: a position-independent ELF **does** reach `RX` from a clean file
mapping without ever meeting `execmod` (§1.1's "*reasoned from the recorded
measurement, not itself measured*" is now measured), and the process **is**
glibc's from the jump onward, with bionic's TCB orphaned exactly as §1.4 C
described.

**What probe 4 has *not* settled, precisely.** Two gaps, both named rather than
argued away:

1. **Domain.** It ran in `runas_app`. The permission it depends on is
   `app_data_file:file { map execute }`, which `plat_sepolicy.cil:30591` grants
   to `untrusted_app_all`, and which this app already exercises on every session
   start (`docs/ARCHITECTURE.md:200-205`). It does *not* depend on
   `execute_no_trans` — the permission `runas_app` has and `untrusted_app` does
   not — because it never execs. *Inference from policy, not a measurement.*
2. **Seccomp, and this is the real one.** The app process runs `Seccomp: 2` with
   one filter; `run-as` runs `Seccomp: 0`. A glibc guest issues syscalls bionic
   never does, and if Android's app filter answers one of them with
   `SECCOMP_RET_TRAP` the guest dies of `SIGSYS`. So the probe was re-run under
   its own tracer (`VESSEL_STRACE=1`, using the `ptrace` probe 2 just proved) to
   get the exact set to check. The whole `ls` run is **107 syscalls**:

   ```
   strace: 107 syscalls (0 outside 0..511)
   strace: nr:count  9:2 11:2 29:1 43:2 48:3 56:9 57:13 61:2 62:2 63:6 64:1
                     79:3 80:7 94:1 96:1 98:1 99:1 198:4 203:4 214:3 215:8
                     222:17 226:9 261:1 278:1 291:2 293:1
   ```

   > **Phase 0b answered this, and the guess about which entries to worry
   > about was wrong.** `statx(291)` and both `*xattr` calls are permitted.
   > `rseq(293)` is trapped — and so is `set_robust_list(99)`, which is in the
   > list below and was *not* flagged precisely because bionic issues it too.
   > That is the second time in this study that "bionic does it, so it must be
   > allowed" has been the wrong test. The guests die of `SIGSYS`.

   aarch64 numbering: `lgetxattr(9) listxattr(11) ioctl(29) statfs(43)
   faccessat(48) openat(56) close(57) getdents64(61) lseek(62) read(63) write(64)
   newfstatat(79) fstat(80) exit_group(94) set_tid_address(96) futex(98)
   set_robust_list(99) socket(198) connect(203) brk(214) munmap(215) mmap(222)
   mprotect(226) prlimit64(261) getrandom(278) statx(291) rseq(293)`. Nothing
   exotic; the only entries bionic does not itself routinely issue are
   `rseq(293)`, `statx(291)` and the two `*xattr` calls. **Settling it needs the
   loader run from the app's own process**, which is product code and therefore
   not Phase 0. Until then, treat probe 4 as *green with one unchecked gate*, and
   note that the gate is a 27-entry list rather than an unknown.

That 107 is also the first real datum for §8 question 5: PRoot would pay two
context switches on each of them.

---

## Phase 0b, measured — the loader in the app's own domain

Same device and same artifacts as Phase 0. What changed is the only thing Phase
0 could not change: **these runs are children of the app process**, so they are
`u:r:untrusted_app` with `Seccomp: 2` rather than `u:r:runas_app` with
`Seccomp: 0`. Driver `tools/probe/phase0b.sh`, agent
`tools/probe/phase0b_agent.c`, probe `tools/probe/glibcload.c` (extended with a
self-report, a syscall matrix and an optional SIGSYS shim). Everything below is
copied off the device.

### How it got into the app's domain without an APK change

`am attach-agent` makes ART `dlopen` a JVMTI agent **inside** a debuggable app's
process. `phase0b_agent.c` does nothing with JVMTI: on `Agent_OnAttach` it
double-forks and execs `/system/bin/sh` on a script, with output to a file. That
is the same act a debug hook in Kotlin would have performed — fork, redirect,
exec — so the fidelity is identical and no shipped code changed. It also does
not disturb the app: it never blocks the calling thread and never touches app
state. (One wrinkle worth recording: the agent `.so` must get a **fresh
filename** each run. Overwriting it in place leaves `dlopen` returning the
cached handle for the still-loaded library and `Agent_OnAttach` is never called
again — the second run of the script produced no output at all until that was
fixed.)

### The inheritance claim, verified rather than reasoned

The claim under test was that a child forked/exec'd by the app keeps the app's
SELinux domain (no type transition is defined for `untrusted_app` on
`system_file`) and its seccomp filter (filters survive `execve`). Both hold, at
every depth — the app, the `sh` it exec'd, and the `glibcload` that `sh` started
through `/system/bin/linker64`:

```
==> app pid 11807
u:r:untrusted_app:s0:c197,c257,c512,c768
NoNewPrivs:	0
Seccomp:	2
Seccomp_filters:	1

### phase 0b: a child of the app process
shell selinux: u:r:untrusted_app:s0:c197,c257,c512,c768
Uid:	10453	10453	10453	10453
NoNewPrivs:	0
Seccomp:	2
Seccomp_filters:	1

self: pid 12103 ppid 12095 uid 10453          # glibcload, two execs deep
  info  selinux: u:r:untrusted_app:s0:c197,c257,c512,c768
  info  Seccomp:	2
  info  Seccomp_filters:	1
```

*Verified here.* Every result in this section therefore carries its own domain,
which is the discipline Phase 0 lacked; `glibcload` now prints those five lines
before anything else in every mode, so a future quote cannot lose them.

### The exec wall, measured in the right domain at last

Phase 0's probe 1 ran its contrast case under `run-as`, where exec'ing the
loader out of app storage **succeeded** and would have inverted this document.
Re-run in `untrusted_app`:

```
### can this domain execve out of app_data_file?
-- a glibc ELF, directly:
…/phase0b_run.sh[17]: /data/data/app.vessel/linuxprobe/rootfs/bin/echo: Permission denied
rc=126
-- a bionic ELF, directly:
rc=126
-- glibc ELF via linker64:
CANNOT LINK EXECUTABLE "/data/data/app.vessel/linuxprobe/rootfs/bin/echo": library "libc.so.6" not found: needed by main executable
rc=1
-- glibc ld.so via linker64:
Could not find a PHDR: broken executable?
Aborted
```

(The bionic case prints nothing because its stderr is discarded; `rc=126` is
the shell's own code for "found and not executable". The last case's `rc` is the
pipeline's, not the loader's.)

Four things, all *verified here* and three of them for the first time:

- `execve` of a file in `app_data_file` is denied to the app — **for a bionic
  ELF as much as for a glibc one**. `docs/ARCHITECTURE.md:207-216` said so; this
  is the first time it has been watched in the app's own domain rather than
  inferred from `runas_app` plus policy.
- §1.2's *predicted* failure mode for `linker64` + a glibc program — bionic's
  linker cannot satisfy `DT_NEEDED: libc.so.6` — is now measured. Phase 0 only
  measured the *other* failure, the `PT_PHDR` abort on `ld.so` itself. Both
  reasonings were right, about two different objects.
- `linker64` **is** exec'd successfully out of `/system/bin` and then loads a
  file from `app_data_file`, which is the mechanism `WineLaunch.linkerArgv`
  (`app/src/main/java/app/vessel/core/WineLaunch.kt:139-140`) already depends on,
  now re-confirmed in the domain that matters.
- **The in-process loader answers "how do I start the first process" and says
  nothing about the second.** This is the finding Phase 0 did not have and §1.4
  option C does not mention. See *The exec problem option C does not solve*
  below.

### Seccomp: four syscalls trapped, two of them on glibc's startup path

`glibcload --selftest`, 36 syscalls, one forked child each, `SIGSYS` reset to
`SIG_DFL` so a trap shows as `WTERMSIG`. Arguments are chosen to be rejected by
the kernel, so "an errno" means the filter let the call through:

```
syscall matrix (one forked child each; SIGSYS = stopped by the filter):
  ok      9 lgetxattr          Bad address
  ok     11 listxattr          Bad address
  ok     29 ioctl              Bad file descriptor
  ok     43 statfs             Bad address
  ok     48 faccessat          Bad address
  ok     56 openat             Bad address
  ok     57 close              Bad file descriptor
  ok     61 getdents64         Bad file descriptor
  ok     62 lseek              Bad file descriptor
  ok     63 read               Bad file descriptor
  ok     64 write              Bad file descriptor
  ok     79 newfstatat         Bad address
  ok     80 fstat              Bad file descriptor
  ok     96 set_tid_address    returned >= 0
  ok     98 futex              Bad address
  TRAP   99 set_robust_list    killed by signal 31 (Unknown signal 31)
  ok    198 socket             Permission denied
  ok    203 connect            Bad file descriptor
  ok    214 brk                returned >= 0
  ok    215 munmap             Invalid argument
  ok    222 mmap               Invalid argument
  ok    226 mprotect           returned >= 0
  ok    261 prlimit64          Invalid argument
  ok    278 getrandom          returned >= 0
  ok    291 statx              Bad address
  TRAP  293 rseq               killed by signal 31 (Unknown signal 31)
  ok    101 nanosleep          Bad address
  ok    123 sched_getaffinity  Invalid argument
  ok    167 prctl              Invalid argument
  ok    168 getcpu             returned >= 0
  ok    260 wait4              No child processes
  ok    283 membarrier         returned >= 0
  ok    435 clone3             Invalid argument
  ok    436 close_range        Invalid argument
  TRAP  437 openat2            killed by signal 31 (Unknown signal 31)
  TRAP  439 faccessat2         killed by signal 31 (Unknown signal 31)

syscall matrix: 4 trapped by seccomp
```

(Signal 31 is `SIGSYS`; bionic's `strsignal` has no name for it, hence "Unknown
signal 31". `socket`'s `Permission denied` is SELinux answering `AF_UNSPEC`, not
the seccomp filter — the traced `ls` run issues a real `socket`/`connect` pair
for NSS and gets past both.)

**The prediction in Phase 0 was wrong about which syscalls to worry about.** It
named `rseq(293)`, `statx(291)` and the two `*xattr` calls. `statx`,
`lgetxattr` and `listxattr` are all permitted. `rseq` is indeed trapped — and so
is `set_robust_list(99)`, which was on the 107-syscall list and was *not*
flagged because bionic issues it too. `openat2(437)` and `faccessat2(439)` are
also trapped; those were not on the list at all and were added here because a
glibc userland reaches them soon after. Notably `clone3(435)` is **permitted**,
which matters: glibc's `clone3` fallback keys on `ENOSYS` and would not have
survived a trap.

### The three binaries, unshimmed: all three die

```
### guest program: echo hello-from-glibc
…
  ok    PT_LOAD[0] file map r-x at 0x6e802a4000 len 0x27000
  ok    PT_LOAD[1] file map rw- at 0x6e802e2000 len 0x4000
  ok    guest stack (8 MiB anon RW)
jumping to 0x6e802bea40 - anything below this line came from glibc

RESULT: guest killed by signal 31 (Unknown signal 31)
```

and the same three words for the other two — the run's `RESULT:` lines, in
order, are:

```
RESULT: guest exited 0                                    # ld.so --version
RESULT: guest killed by signal 31 (Unknown signal 31)     # echo
RESULT: guest killed by signal 31 (Unknown signal 31)     # uname -a
RESULT: guest killed by signal 31 (Unknown signal 31)     # ls -l
```

`ld.so --version` alone still exits 0 — it never loads `libc.so.6`, so it never
reaches the two calls. **So Phase 0's headline is half true in the domain that
matters: the loader works, the userland does not.** The three stock Ubuntu
binaries that "ran and exited 0" under `run-as` are killed by the app's filter.

### With a SIGSYS shim: all three run, trapping exactly two calls each

Android answers a denied syscall with `SECCOMP_RET_TRAP`, not
`SECCOMP_RET_KILL` — which is why bionic can install a handler that logs before
dying, and which means the same door is open to us. `VESSEL_SIGSYS_SHIM=1`
installs a handler that sets `x0` to `-ENOSYS` and returns. It touches no TLS
(no errno, no libc call, compiled `-fno-stack-protector`) because it runs after
glibc has taken `TPIDR_EL0`, and it reports through a raw `svc #0` write:

```
### guest program WITH SIGSYS SHIM: echo hello-from-glibc
sigsys shim: installed (trapped syscalls will return -ENOSYS)
shim 99
shim 293
hello-from-glibc
RESULT: guest exited 0

### guest program WITH SIGSYS SHIM: uname -a
shim 99
shim 293
Linux localhost 6.12.38-android16-5-gdda2539c405d-ab14915528-4k #1 SMP PREEMPT Fri Feb 20 19:49:35 UTC 2026 aarch64 aarch64 aarch64 GNU/Linux
RESULT: guest exited 0

### guest program WITH SIGSYS SHIM: ls -l /data/data/app.vessel/linuxprobe/rootfs/lib
shim 99
shim 293
total 200
-rwxr-xr-x. 1 10453 10453 203968 Aug 10 06:41 ld-linux-aarch64.so.1
RESULT: guest exited 0
```

Exactly two traps per run, both on glibc's startup path, both of which glibc is
required to tolerate as `ENOSYS` (`set_robust_list` gives up robust futexes;
`rseq` leaves `__rseq_size` zero and `sched_getcpu` falls back to `getcpu`,
which the matrix shows is permitted). `openat2` and `faccessat2` never came up
in these three programs, and both have `ENOSYS` fallbacks in glibc.

### So: the gate is closed, and the answer is "yes, with a permanent shim"

Stated precisely, because the difference matters:

- **As-is, no.** A glibc userland dies of `SIGSYS` on its first `libc.so.6`
  startup in `untrusted_app`.
- **With ~40 lines of signal handler installed before the jump, yes.** Three
  stock Ubuntu 24.04 binaries run and exit 0 under `Seccomp: 2`.

What that shim is *not* is free, and two of its costs are unmeasured and are
labelled as such:

1. **It must be in every process.** Signal dispositions are per-process, so it
   survives the jump and covers all threads of one process — but it does not
   survive an `execve`, and a guest that installs its own `SIGSYS` disposition
   loses it. *Inference from the signal API, not measured here.*
2. **Only single-threaded, short-lived CLI programs were measured.** glibc
   registers `rseq` and `set_robust_list` per thread, so a threaded guest traps
   once per thread on a bionic-compiled handler. That should work for the same
   reason the main thread does; **not measured** — `echo`, `ls` and `uname` are
   all single-threaded. Settled by running any threaded glibc binary.

### The exec problem option C does not solve

§1.4 option C is described as the route to Ubuntu. It is the route to **one
glibc process**. A distro is a process tree — `apt` runs `dpkg`, `dpkg` runs
maintainer scripts in `/bin/sh`, those run `update-alternatives` and `ldconfig`
— and every one of those is an `execve` of a file in `app_data_file`, which the
measurement above shows is **denied**, for glibc and bionic alike.

There are two ways round it and both are worse than they sound:

- **Interpose on `execve` inside the guest** (an `LD_PRELOAD` that rewrites
  `execve(prog, …)` into `execve("/system/bin/linker64", ["linker64",
  "glibcload", "ld.so", prog, …])`). It works for anything that calls glibc's
  `execve`, and misses statically linked programs, anything issuing the syscall
  directly, and `posix_spawn` implementations that go through `clone`+raw exec.
  A partial interposition fails per-program, unpredictably, which is the worst
  shape a failure can have.
- **Interpose from outside with `ptrace`** — PRoot's shape, and probe 2 shows
  the mechanism is available. It is total, and it costs two context switches on
  each of the 107 syscalls one `ls` issues, on every process in the tree, and it
  has to co-exist with the `SIGSYS` shim, since seccomp traps and ptrace stops
  arrive through the same machinery.

Either way, **every process in an Ubuntu container would be started by a
Vessel-specific loader and supervised by a Vessel-specific shim, forever.** That
is not a packaging problem; it is a second runtime.

By contrast, the equivalent problem on the bionic side is already solved and
already shipped: a bionic child is started as `execve("/system/bin/linker64",
[linker, binary, …])` (`WineLaunch.kt:139-140`), and `patches/wine/0003` pushes
exactly that rewrite down into Wine so grandchildren get it too
(`patches/wine/0003-ntdll-exec-child-loaders-through-the-system-linker.patch:70-71`).
The rewrite is one line and it always works, because bionic's linker can load
every bionic ELF. There is no glibc equivalent, and probe 1 is the reason why.

---

## The decision: a bionic userland is the target, Ubuntu is refused

**Target: a bionic ARM64 userland (§1.4 option A), named in the UI as a bionic
userland and never as Linux, Ubuntu or a distro.**

**Refused: an Ubuntu/glibc userland (§1.4 option C), and with it `apt`, `dpkg`
and the Debian archive.**

This is the choice `docs/TODO.md:1032` asks for and it is made here rather than
deferred, because the failure mode to avoid is shipping the first while
describing it as the second. The standard is this repository's own launch-type
matrix (`docs/TODO.md:488-492`), where `.sh` is *"never offered … `NotAProgram`,
which is a different statement from a refusal and is the right one"*: the
product says exactly what it is, and a capability that does not exist is not
implied.

**Why Ubuntu is refused, in the order the evidence lands.**

1. **The loader starts one process; a distro is a tree, and the tree needs an
   `execve` this domain does not have.** Measured above: `Permission denied`,
   rc 126, in `untrusted_app`. The two workarounds are an incomplete
   `LD_PRELOAD` or a total `ptrace` supervisor at two context switches per
   syscall. Phase 0 did not see this because it ran three programs that spawn
   nothing.
2. **glibc does not start under the app's seccomp filter without a signal
   handler that has to be present in every process, forever.** Measured above:
   `SIGSYS` on `set_robust_list` and `rseq`, fixed by a shim, and the shim does
   not survive an `execve` — so it compounds with (1) rather than being
   independent of it.
3. **The packaging pipeline refuses a rootfs, correctly.** `WcpInstaller`
   rejects hard links (`app/src/main/java/app/vessel/data/WcpInstaller.kt:434-437`),
   device nodes and fifos (`:439-442`) and absolute symlinks (`:587-595`), and a
   distro rootfs is made of all three. Each refusal is argued for in that file
   and each is right, so the cost is a repacking build script (§5.4), not a
   relaxation.
4. **The component store would hand every Windows container a gigabyte of
   Ubuntu.** `ComponentStore.adoptLatest` walks `ComponentType.entries`
   (`app/src/main/java/app/vessel/data/ComponentStore.kt:174-177`), so a
   `LinuxBase` type is adopted by containers that will never open it, and
   `prune()` then correctly refuses to free it (§6.1).
5. **The shipped GPU driver is unusable by a glibc guest.** Turnip is a bionic
   ELF (`build/turnip.sh:6`) and `docs/ARCHITECTURE.md:581-584` already wrote
   down why that matters — "No Vortek" holds only because Vessel's Wine is
   bionic. A glibc guest re-creates the exact problem Vortek exists to solve, so
   graphics means a software renderer first and then a second Mesa against an
   `aarch64-linux-gnu` sysroot (§3.2).
6. **§9 is untouched by any of this.** The licence surface of a redistributed
   distro image is unbounded against this project's own standard (§9.5), it is
   permanently sideload-only (§9.6), every Linux application's bugs arrive as
   Vessel bugs (§9.7), and the core sentence — a Windows program drawing through
   DXVK — is not finished (§9.3).

Items 3, 4 and 5 are bounded engineering; on their own they would be a cost, not
a refusal. **Items 1 and 2 are the refusal**, because together they mean an
Ubuntu container is not "Ubuntu, packaged by Vessel" — it is a bespoke runtime
that loads and supervises every glibc process on the device, and whose failures
are ours and are unpredictable per program. That is the "different product" of
`docs/TODO.md:513-517`, arriving from a direction the note did not anticipate.

**Why the bionic userland is the target, and what it honestly is.** Every
mechanism it needs is already shipped and already measured: `linkerArgv` starts
a bionic ELF out of app storage (`WineLaunch.kt:139-140`), `patches/wine/0003`
proves the child-exec rewrite generalises, no seccomp shim is needed because
bionic issues the syscalls its own platform allows, and `SessionRuntime`
(`app/src/main/java/app/vessel/data/SessionRuntime.kt:157-165`) already starts
guest processes for exactly this reason. It brings **no `apt`, no `dpkg`, no
Debian packages, and nothing we or Termux have not built**. That sentence has to
appear in the product, not only here.

**What would reopen the Ubuntu question**, so the refusal is falsifiable rather
than final: a `targetSdk` drop to 28 (§1.4 B — a regression, and it would make
`execve` legal and delete items 1 and 2 at a stroke), or Android granting
`execute_no_trans` on `app_data_file` to `untrusted_app`, or a demonstration
that total `execve` interposition costs less than the `x11present` baseline can
absorb (§8 question 5). None of those is on the horizon.

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
| `execve` of a binary in `filesDir` is **denied** at `targetSdk` ≥ 29; `execve("/system/bin/linker64", [linker, prog, …])` is **permitted** | `docs/ARCHITECTURE.md:207-216`, `app/src/main/java/app/vessel/core/WineLaunch.kt:5-27` | verified here (`wine --version` answers this way "and in no other way"); re-verified **in `untrusted_app`** at Phase 0b — rc 126, `Permission denied`, for a glibc ELF and a bionic one alike |
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
recorded measurement, not itself measured* — and it is now **measured**: probe 4
maps glibc's `ld.so`, `libc.so.6`, `libselinux.so.1` and `libpcre2-8.so.0`
`PROT_EXEC` from `app_data_file` and runs all of them, meeting `execmod` never.
The policy line behind that, read off the device, is
`(allow untrusted_app_all app_data_file (file (… map execute …)))` —
`execute` granted, `execmod` and `execute_no_trans` not
(`/system/etc/selinux/plat_sepolicy.cil:30591`). It matters only for option C
below, which is the option that turned out to work.

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
asks bionic's linker to run glibc's loader as a program — **measured, and it does
not**:

```
$ run-as app.vessel /system/bin/linker64 <files>/rootfs/lib/ld-linux-aarch64.so.1 --version
Could not find a PHDR: broken executable?
Aborted                                                     (rc=134)
```

*Verified here*, § *Phase 0, measured*, probe 1. The prediction was right and the
reasoning behind it was not: it never reaches auxv or TLS. glibc's `ld.so` is a
static PIE with **no `PT_PHDR` segment** — seven program headers, `LOAD LOAD
DYNAMIC NOTE GNU_EH_FRAME GNU_STACK GNU_RELRO` — and bionic's linker aborts on
that alone, before any `DT_NEEDED` is looked at.

That the *other* half of the corollary still holds — that `execve` of the loader
straight out of `filesDir` is denied — is confirmed from the device's compiled
policy, not from a `run-as` run. Read the caveat at the head of § *Phase 0,
measured* before using the obvious one-liner: under `run-as` that exec
**succeeds**, because `runas_app` holds `execute_no_trans` on `app_data_file` and
`untrusted_app` does not.

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
  on, so it is presumably allowed by `untrusted_app` policy — **measured, and it
  is**. `tools/probe/ptraceprobe.c`, run as the app's uid at `targetSdk` 36 on
  Android 16, reports `0 failure(s)` across `PTRACE_TRACEME`, `SETOPTIONS`, a
  full `PTRACE_SYSCALL` entry/exit pair with `GETREGSET` in between,
  `PEEKDATA`, `CONT`, and `ATTACH`/`DETACH` to a separate running child. There is
  no Yama `ptrace_scope` on this kernel, and `plat_sepolicy.cil:30637` grants
  `(allow untrusted_app_all self (process (ptrace)))`. *Verified here*, §*Phase 0,
  measured*, probe 2. A tracer is therefore mechanically available; whether it is
  affordable is the separate question in the bullet above, and the 107-syscall
  count for one `ls` in probe 4 is the first number to put against it.

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

**Measured, and it works.** `tools/probe/glibcload.c` does exactly this against
Ubuntu 24.04's `ld-linux-aarch64.so.1` (glibc 2.39). `ld.so --version` prints;
so do stock `/bin/echo`, `/bin/ls -l` and `/bin/uname -a` loaded by it out of app
storage, each exiting 0, with `ls` resolving `libselinux.so.1` →
`libpcre2-8.so.0` → `libc.so.6` and mapping all three `PROT_EXEC` from
`app_data_file`. *Verified here*, §*Phase 0, measured*, probe 4 — with one gate
still unchecked, seccomp, named there. This also converts §1.1's fourth-row
reasoning from inference to measurement: a PIE's text does reach `RX` from a
clean file mapping and never meets `execmod`.

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

> **This recommendation survived both phases and is now the decision.** The day
> was spent, twice; see §*The decision*.

> **Amended after Phase 0b, which is the amendment that decides it.** C works
> in `untrusted_app` only with a permanent `SIGSYS` shim, and — the part option
> C never addressed — it loads *one* process. `execve` out of `app_data_file`
> is denied to the app, measured, so every subsequent process in a distro needs
> the loader interposed on it too, by an incomplete `LD_PRELOAD` or by a total
> `ptrace` supervisor. See *Phase 0b, measured* and *The decision*: **option A
> is the target and option C is refused.**

> **Amended after Phase 0.** The day was spent and **C works**, so the
> recommendation's premise — that A is the only route whose execution path is
> proven — no longer holds: C's is proven too, to the extent one probe under
> `run-as` proves anything. What has *not* changed is everything in §5.4 (the
> `.wcp` pipeline cannot carry a rootfs), §3.2 (the shipped Turnip is bionic and
> a glibc guest cannot use it), and all of §9. C being possible moves Ubuntu from
> "blocked" to "expensive", which is a different argument and one this document
> already makes at length. Note also that A and C are not exclusive: the same
> loader shim that starts glibc's `ld.so` is not needed at all for A, and A
> remains the cheaper first shippable thing.

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
does not permit unprivileged processes to create. This was *known, high
confidence, not verified on this device*; it has now been run, and the
conclusion holds with three corrections worth having (*verified here*, §*Phase 0,
measured*, probe 3):

- `unshare(CLONE_NEWUSER)` fails **`EINVAL` (22)**, not `EPERM`. The flag does
  not exist on this kernel: `/proc/self/ns/` contains `cgroup mnt net time
  time_for_children uts` and no `user`, no `pid`, no `ipc`. There is no sysctl
  and no policy to change, because the feature is not compiled in.
- `unshare(CLONE_NEWNS)` fails **`EPERM` (1)** — a different errno for a
  different reason. Mount namespaces exist; `CapEff` is `0000000000000000`.
- `mount(2)` fails **`EACCES` (13)** for tmpfs, for overlay and for `MS_BIND`.

And the check this section proposed first cannot be performed by the app at all:
`/proc/filesystems` is labelled `proc_filesystems`, no app domain is granted it
(`plat_sepolicy.cil:11550` neverallows it to the app typeattribute), and both
toybox `grep` and the probe get `Permission denied`. Whether overlayfs is in the
kernel is unobservable from here and moot either way.

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
`docs/TODO.md:1164-1168` names the general lesson: *"a rule stated for one call
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

**Phase 0 — four probes, one day, no product code. DONE; see §*Phase 0,
measured*.** In `tools/probe/`, next to `mapexec.c`, run as the app's uid:
1. `linker64 <rootfs>/lib/ld-linux-aarch64.so.1 --version` — does bionic's linker
   run glibc's loader at all? (§1.2) → **no**: `Could not find a PHDR: broken
   executable?`, `SIGABRT`.
2. `ptrace` self-test: fork, `PTRACE_TRACEME`, one `PTRACE_SYSCALL` round trip.
   (§1.3) → **yes**, every call, no errno.
3. `mount`/userns/overlayfs: `grep overlay /proc/filesystems`, `unshare -Ur`,
   one `mount()` call, report each `errno`. (§5.1) → **no**: `EINVAL`/`EPERM`/
   `EACCES`, and `/proc/filesystems` is not even readable.
4. In-process ELF loader: `mmap` `ld-linux-aarch64.so.1`, synthesise auxv, jump,
   and try to print from a glibc `hello`. (§1.4 option C) → **yes**: `ld.so
   --version`, and Ubuntu's `echo`, `ls -l` and `uname -a`, all exit 0.

**Nothing after this phase should be started until probes 1 and 4 have answers.**
If both fail, Ubuntu is off the table and the only remaining product is Phase 1.
They did not both fail. Probe 4 passed, so Phase 2 is *unblocked* rather than
*justified* — the gating question moves from §1 to §5.4, §3.2 and §9, none of
which Phase 0 touched.

**Phase 0b — close the one gap probe 4 left. DONE; see §*Phase 0b, measured*.**
Run the same loader from the app's own process rather than through `run-as`, so
it executes in `untrusted_app` with `Seccomp: 2`. It needed no product code in
the end: `am attach-agent` puts `tools/probe/phase0b_agent.c` inside the running
debug build, and it forks and execs exactly as a debug hook would have
(`tools/probe/phase0b.sh`). *Done:* `uname -a` prints from a glibc guest in a
process whose `/proc/self/attr/current` reads `u:r:untrusted_app` and whose
`Seccomp:` reads `2` — **but only with a `SIGSYS` shim**; without one the guest
is killed on `set_robust_list(99)`. And `execve` of `app_data_file` is denied to
that domain, so the loader reaches one process and not a tree.

**Phase 2 is therefore not started, and §*The decision* says why.** The phases
below stand as written; Phase 2, 3 and 4 are refused rather than pending.

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

| # | Question | Status | What settled it / would settle it |
|---|---|---|---|
| 1 | Does `linker64` run glibc's `ld.so` as a program? | **measured: no.** `Could not find a PHDR: broken executable?` then `Aborted` (`SIGABRT`, rc 134). Not an errno — bionic's linker rejects the static PIE for having no `PT_PHDR`, before `DT_NEEDED`. Predicted correctly, for the wrong reason. | §7 probe 1, run as the app's uid; output in §*Phase 0, measured* |
| 2 | Can a bionic process `mmap` glibc's `ld.so` and jump to it? | **measured: yes, and in `untrusted_app`/`Seccomp 2` only with a shim.** Mapping and the jump work in both domains. Under the app's filter the guests are killed by `SIGSYS` on `set_robust_list(99)` and `rseq(293)`; a handler returning `-ENOSYS` makes `echo`, `uname -a` and `ls -l` all exit 0. Gate closed. | §*Phase 0b, measured*, `tools/probe/phase0b.sh` |
| 2b | Can the app `execve` anything out of `app_data_file`? | **measured: no** — `Permission denied`, rc 126, for a glibc ELF *and* for a bionic one, in `untrusted_app`. So the in-process loader starts one process and a distro is a tree. This is the finding that decides §1.4. | §*Phase 0b, measured*; first time this was watched outside `runas_app` |
| 2c | Which syscalls does the app's seccomp filter withhold from a glibc guest? | **measured: 4 of 36 probed** — `set_robust_list(99)`, `rseq(293)`, `openat2(437)`, `faccessat2(439)`, all `SECCOMP_RET_TRAP`. `statx(291)`, both `*xattr` calls and `clone3(435)` are permitted, contradicting Phase 0's guess. | `glibcload --selftest`, §*Phase 0b, measured* |
| 2d | Does the `SIGSYS` shim hold for threaded or exec'ing guests? | **unsure, and not measured.** Dispositions are per-process so all threads of one process are covered; they do not survive `execve`, and only single-threaded `echo`/`ls`/`uname` were run. | Any threaded glibc binary, and any guest that spawns a child |
| 3 | Is `ptrace` of a child permitted to `untrusted_app` at targetSdk 36 / Android 16? | **measured: yes**, `0 failure(s)` — `TRACEME`, `SETOPTIONS`, `PTRACE_SYSCALL` entry+exit, `GETREGSET`, `PEEKDATA`, `CONT`, `ATTACH`, `DETACH`. No Yama `ptrace_scope` on this kernel; `plat_sepolicy.cil:30637` grants it to `untrusted_app_all` too. | §7 probe 2, `tools/probe/ptraceprobe.c` |
| 4 | Is unprivileged overlayfs or userns available? | **measured: no.** `unshare(CLONE_NEWUSER)` → `EINVAL 22` (no `user` entry in `/proc/self/ns/` — not compiled in), `unshare(CLONE_NEWNS)` → `EPERM 1`, `mount()` tmpfs/overlay/bind → `EACCES 13`. `/proc/filesystems` is `Permission denied` to the app, so the "is overlayfs present" half is unanswerable from here and moot. | §7 probe 3, `tools/probe/nsprobe.c` |
| 5 | What does PRoot's syscall interception cost *this* workload? | **still unsure** — but no longer unquantified at the low end: a full `ls` under the glibc loader is **107 syscalls** end to end (`VESSEL_STRACE=1`), so a CLI guest is cheap. A GUI guest is the open half. | Run `x11present` under a tracer against the 2.245 ms baseline (`docs/TODO.md:78-84`) |
| 6 | Does a glibc aarch64 Turnip/KGSL build? | **unsure**, looks more likely than the bionic ICD did (§3.2) | One cross build with an `aarch64-linux-gnu` sysroot |
| 7 | Does a stock glibc libxcb find the abstract-namespace X socket? | **reasoned, not measured** | Any glibc X client, once one can be started |
| 8 | Does FEX do rootfs path redirection without ptrace? | **medium confidence, unverified** | Read FEXCore's syscall layer in `native/fex` |
| 9 | Can an Ubuntu rootfs be repacked to survive `WcpInstaller`'s three refusals without breaking the distro? | **unsure** — merged-`/usr` absolute links are load-bearing in Debian | Repack `ubuntu-base`, install it, run `dpkg --verify` |
| 10 | Licence compliance for redistributing a distro image | **open, unbounded** | See §9.5 |

Risk not in the table because it is not a question: **`adoptLatest` will hand every
Windows container a reference to the rootfs the moment a new `ComponentType`
exists** (§6.1, `ComponentStore.kt:174-177`). Known, and fixable in the same
change that introduces the type — but only if someone remembers, which is exactly
the failure mode `docs/TODO.md:1164-1168` names.

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

> **Amended after Phase 0.** Half of this is now wrong and the half that
> survives is the important one. It *would* mean a glibc rootfs — probe 4 proved
> a glibc rootfs can be started, so that clause is now a cost, not a
> blocker — but it would **not** mean proot: the loader needs no `chroot` and no
> path rewriting to run, and probe 3 shows there is no `mount` or user namespace
> to fake one with anyway. What is untouched is *"which is a different
> product"*, and that is the sentence §9 is actually about. Probe 4 makes Ubuntu
> possible; it does not make it wise, and nothing in §9.2 through §9.7 is
> weakened by it.

> **Amended again after Phase 0b, and this restores the original sentence
> almost intact.** *"A different product"* turns out to be the literal
> description: an Ubuntu container would need a Vessel-written loader to start
> every process and a Vessel-written signal handler to keep each one alive,
> because the app's domain cannot `execve` its own files and its filter traps
> two of glibc's startup syscalls — both measured. Proot is still not the
> answer, but a proot-shaped `ptrace` supervisor is now one of only two ways to
> make exec work at all. §*The decision* refuses Ubuntu on that basis.

**9.2 It reverses the clearest simplification in the architecture.**
`docs/ARCHITECTURE.md:3` is titled "One kind of container, no switch", and
`docs/ARCHITECTURE.md:38-42` derives a real user-facing property from it: an
executable can never be in the wrong container, so the architecture badge is
information rather than a warning. Adding a mode brings back the question, and
§6.1 shows the first bug it causes lands immediately, in `adoptLatest`, in a
function whose whole purpose is to guess correctly on the user's behalf.

**9.3 The core sentence is not finished.** `docs/TODO.md:18-21` states the goal —
*a Windows program drew on the screen through DXVK* — and `docs/TODO.md:1170-1173`
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
