/*
 * Probe 4 of docs/LINUX-MODE.md §7 Phase 0: can a bionic process load glibc's
 * dynamic loader itself and jump to it, without ever asking the kernel to
 * execve anything?
 *
 * This is the only route to an Ubuntu userland that keeps targetSdk 36
 * (LINUX-MODE.md §1.4 option C), and the only item in that study with no prior
 * art in this repository. It is not obviously doomed for one measured reason:
 * tools/probe/mapexec.c established that a *clean* private file mapping reaches
 * RX inside the app sandbox and only a *dirtied* one hits execmod
 * (docs/ARCHITECTURE.md:239-249) — and a position-independent ELF, unlike a PE,
 * never relocates its text.
 *
 * What it does, in order:
 *   1. mmap every PT_LOAD of ld-linux-aarch64.so.1 from a clean file mapping,
 *      MAP_FIXED over one PROT_NONE reservation — the same shape mapexec.c
 *      measured as permitted — with the segment's own protection, so the text
 *      segment is asked for PROT_EXEC at mmap time and never mprotect'ed up.
 *   2. Synthesise a System V process stack: argc/argv/envp and an auxv copied
 *      from /proc/self/auxv with AT_PHDR/AT_PHENT/AT_PHNUM/AT_ENTRY/AT_BASE/
 *      AT_EXECFN/AT_RANDOM overridden to describe the loader we just mapped.
 *      AT_ENTRY == the loader's own entry is what tells glibc's rtld it was
 *      invoked directly, which is what makes `--version` and `--library-path`
 *      and "argv[1] is the program" work.
 *   3. Reset the fatal signals to SIG_DFL (bionic's debuggerd handler would run
 *      bionic code against a TCB glibc is about to steal) and branch to the
 *      entry point on the synthetic stack.
 *
 * The jump is one-way: glibc's rtld writes TPIDR_EL0 for its own TLS, which
 * orphans bionic's TCB in the same process. Nothing bionic may be called after
 * it. So the work happens in a forked child and the parent only reports the
 * child's exit status or signal — that status IS the measurement.
 *
 * Build with ./tools/probe/build.sh; driven by ./tools/probe/linuxmode.sh.
 * Run as the app's own uid:
 *   run-as app.vessel ./linuxprobe/glibcload ./linuxprobe/rootfs/lib/ld-linux-aarch64.so.1 --version
 *
 * Phase 0b (LINUX-MODE.md §7) added two things, both because the first run of
 * this probe could only be made through `run-as`, which is a *different SELinux
 * domain and a different seccomp state* from the app:
 *
 *   - every run now opens with self_report(), which prints this process's
 *     /proc/self/attr/current and its Seccomp/NoNewPrivs lines. A result is
 *     only worth anything if it carries the domain it was measured in, and
 *     leaving that to whoever wrote the shell wrapper is how the first run came
 *     to be quoted without it.
 *   - `glibcload --selftest` issues, one forked child each, the syscalls a glibc
 *     guest uses and bionic does not, and reports for each whether it reached
 *     the kernel (any errno) or was stopped by the filter (SIGSYS / ENOSYS).
 *     That turns "something in the guest died" into a syscall number.
 *
 * Both are meant to be run from a child of the app process — see
 * ./tools/probe/phase0b.sh, which gets there without an APK change.
 */

#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

extern char **environ;

static long PAGE;
#define PAGE_DOWN(x) ((uintptr_t)(x) & ~(uintptr_t)(PAGE - 1))
#define PAGE_UP(x)   PAGE_DOWN((uintptr_t)(x) + (uintptr_t)PAGE - 1)

static int failures;

static void report(const char *what, int ok)
{
    if (ok) {
        printf("  ok    %s\n", what);
    } else {
        printf("  FAIL  %s: %s (errno %d)\n", what, strerror(errno), errno);
        failures++;
    }
    fflush(stdout);
}

struct loaded {
    uintptr_t base;     /* load bias */
    uintptr_t entry;    /* base + e_entry */
    uintptr_t phdr;     /* address of the program headers as mapped */
    size_t    phent;
    size_t    phnum;
};

/* Map one ET_DYN ELF at a base we choose. Nothing here is exec'd and nothing
 * written into an executable page, which is the entire point. */
static int load_elf(const char *path, struct loaded *out)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    Elf64_Ehdr eh;
    Elf64_Phdr *ph = NULL;
    uintptr_t minva = UINTPTR_MAX, maxva = 0, base, reserve;
    size_t span;
    int i, rc = -1;

    if (fd < 0) { printf("  FAIL  open %s: %s (errno %d)\n", path, strerror(errno), errno); failures++; return -1; }
    if (pread(fd, &eh, sizeof eh, 0) != (ssize_t)sizeof eh) { report("read ehdr", 0); goto out; }
    if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 || eh.e_ident[EI_CLASS] != ELFCLASS64
        || eh.e_machine != EM_AARCH64) {
        printf("  FAIL  %s is not an aarch64 ELF64\n", path); failures++; goto out;
    }
    printf("  info  %s: type %s, entry 0x%llx, %u phdrs\n", path,
           eh.e_type == ET_DYN ? "ET_DYN" : eh.e_type == ET_EXEC ? "ET_EXEC" : "other",
           (unsigned long long)eh.e_entry, (unsigned)eh.e_phnum);

    ph = malloc((size_t)eh.e_phnum * eh.e_phentsize);
    if (!ph) { report("malloc phdrs", 0); goto out; }
    if (pread(fd, ph, (size_t)eh.e_phnum * eh.e_phentsize, (off_t)eh.e_phoff)
        != (ssize_t)((size_t)eh.e_phnum * eh.e_phentsize)) { report("read phdrs", 0); goto out; }

    for (i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr *p = (Elf64_Phdr *)((char *)ph + (size_t)i * eh.e_phentsize);
        if (p->p_type != PT_LOAD) continue;
        if (p->p_vaddr < minva) minva = p->p_vaddr;
        if (p->p_vaddr + p->p_memsz > maxva) maxva = p->p_vaddr + p->p_memsz;
    }
    if (minva == UINTPTR_MAX) { printf("  FAIL  no PT_LOAD\n"); failures++; goto out; }

    minva = PAGE_DOWN(minva);
    maxva = PAGE_UP(maxva);
    span  = maxva - minva;

    /* One PROT_NONE reservation, then MAP_FIXED each segment over it. mapexec.c
     * measured "MAP_FIXED RX over a PROT_NONE reservation" as ok. */
    reserve = (uintptr_t)mmap(NULL, span, PROT_NONE,
                              MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if ((void *)reserve == MAP_FAILED) { report("reserve span PROT_NONE", 0); goto out; }
    report("reserve span PROT_NONE", 1);
    base = reserve - minva;
    printf("  info  span 0x%zx at base 0x%lx (page %ld)\n", span, (unsigned long)base, PAGE);

    for (i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr *p = (Elf64_Phdr *)((char *)ph + (size_t)i * eh.e_phentsize);
        uintptr_t seg, fileoff, filelen, bss_start, bss_end;
        int prot = 0;
        char what[128];
        void *got;

        if (p->p_type != PT_LOAD) continue;
        if (p->p_flags & PF_R) prot |= PROT_READ;
        if (p->p_flags & PF_W) prot |= PROT_WRITE;
        if (p->p_flags & PF_X) prot |= PROT_EXEC;

        seg     = PAGE_DOWN(base + p->p_vaddr);
        fileoff = PAGE_DOWN(p->p_offset);
        filelen = (base + p->p_vaddr + p->p_filesz) - seg;

        snprintf(what, sizeof what, "PT_LOAD[%d] file map %c%c%c at 0x%lx len 0x%lx",
                 i, (prot & PROT_READ) ? 'r' : '-', (prot & PROT_WRITE) ? 'w' : '-',
                 (prot & PROT_EXEC) ? 'x' : '-', (unsigned long)seg, (unsigned long)PAGE_UP(filelen));

        got = mmap((void *)seg, PAGE_UP(filelen), prot,
                   MAP_PRIVATE | MAP_FIXED, fd, (off_t)fileoff);
        report(what, got != MAP_FAILED);
        if (got == MAP_FAILED) goto out;

        if (p->p_memsz > p->p_filesz) {
            bss_start = base + p->p_vaddr + p->p_filesz;
            bss_end   = base + p->p_vaddr + p->p_memsz;
            /* Tail of the last file page has to be zeroed by hand; it is a
             * writable segment so dirtying it costs nothing (execmod only bites
             * pages that later want PROT_EXEC). */
            if (PAGE_UP(bss_start) > bss_start && (prot & PROT_WRITE))
                memset((void *)bss_start, 0, PAGE_UP(bss_start) - bss_start);
            if (PAGE_UP(bss_end) > PAGE_UP(bss_start)) {
                snprintf(what, sizeof what, "PT_LOAD[%d] anon bss at 0x%lx len 0x%lx", i,
                         (unsigned long)PAGE_UP(bss_start),
                         (unsigned long)(PAGE_UP(bss_end) - PAGE_UP(bss_start)));
                got = mmap((void *)PAGE_UP(bss_start), PAGE_UP(bss_end) - PAGE_UP(bss_start),
                           prot, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
                report(what, got != MAP_FAILED);
                if (got == MAP_FAILED) goto out;
            }
        }
    }

    out->base  = base;
    out->entry = base + eh.e_entry;
    out->phdr  = base + eh.e_phoff;   /* phdrs sit inside the first PT_LOAD here */
    out->phent = eh.e_phentsize;
    out->phnum = eh.e_phnum;
    rc = 0;
out:
    free(ph);
    close(fd);
    return rc;
}

/* /proc/self/auxv, verbatim, so the guest inherits AT_HWCAP, AT_SYSINFO_EHDR
 * (the vDSO stays mapped across the jump), AT_CLKTCK, the uid/gid set and
 * AT_SECURE from the real kernel rather than from a guess. */
#define MAXAUX 64
static size_t read_auxv(uint64_t av[][2])
{
    int fd = open("/proc/self/auxv", O_RDONLY);
    size_t n = 0;
    uint64_t pair[2];
    if (fd < 0) return 0;
    while (n < MAXAUX - 8 && read(fd, pair, sizeof pair) == (ssize_t)sizeof pair) {
        if (pair[0] == AT_NULL) break;
        av[n][0] = pair[0];
        av[n][1] = pair[1];
        n++;
    }
    close(fd);
    return n;
}

static int aux_overridden(uint64_t t)
{
    return t == AT_PHDR || t == AT_PHENT || t == AT_PHNUM || t == AT_ENTRY
        || t == AT_BASE || t == AT_EXECFN || t == AT_RANDOM || t == AT_FLAGS;
}

/* VESSEL_STRACE=1: count the syscalls the glibc guest actually issues.
 *
 * This exists for one reason. The probe can only be run through `run-as`, whose
 * domain is runas_app with Seccomp 0, while the app process is untrusted_app
 * with Seccomp 2 — so the guest here runs without the seccomp filter the app
 * would have. The syscall set below is what a reviewer has to check against
 * that filter, and it is also the input to §1.3's unanswered question about what
 * ptrace interception would cost this workload. Tracing is what PRoot does, so
 * measuring it with the mechanism probe 2 just proved is the cheap way to get it. */
#define NSYS 512
static void trace_child(pid_t pid)
{
    unsigned long counts[NSYS];
    unsigned long other = 0, total = 0;
    int status, entry = 1, i;
    struct user_regs_struct regs;
    struct iovec iov = { .iov_base = &regs, .iov_len = sizeof regs };

    memset(counts, 0, sizeof counts);
    if (waitpid(pid, &status, 0) < 0 || !WIFSTOPPED(status)) {
        printf("strace: child never stopped (status 0x%x)\n", status);
        return;
    }
    ptrace(PTRACE_SETOPTIONS, pid, 0, (void *)(PTRACE_O_TRACESYSGOOD | PTRACE_O_EXITKILL));
    for (;;) {
        if (ptrace(PTRACE_SYSCALL, pid, 0, 0) != 0) break;
        if (waitpid(pid, &status, 0) < 0) break;
        if (!WIFSTOPPED(status)) break;
        if (entry && ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &iov) == 0) {
            unsigned long nr = (unsigned long)regs.regs[8];
            if (nr < NSYS) counts[nr]++; else other++;
            total++;
        }
        entry = !entry;
    }
    printf("\nstrace: %lu syscalls (%lu outside 0..%d)\n", total, other, NSYS - 1);
    printf("strace: nr:count  ");
    for (i = 0; i < NSYS; i++) if (counts[i]) printf("%d:%lu ", i, counts[i]);
    printf("\n");
    if (WIFEXITED(status))        printf("RESULT: guest exited %d\n", WEXITSTATUS(status));
    else if (WIFSIGNALED(status)) printf("RESULT: guest killed by signal %d (%s)\n",
                                         WTERMSIG(status), strsignal(WTERMSIG(status)));
    else                          printf("RESULT: guest status 0x%x\n", status);
}

/* ---- Phase 0b: say which sandbox this run is actually in ----------------
 *
 * The whole point of Phase 0b is that `run-as app.vessel` is u:r:runas_app with
 * Seccomp 0 and the app is u:r:untrusted_app with Seccomp 2. A result that does
 * not carry its own domain is not a measurement of anything in particular, so
 * every mode of this probe prints these three lines first. */
static void cat_first_line(const char *path, const char *label)
{
    char buf[256];
    ssize_t n;
    int fd = open(path, O_RDONLY);
    if (fd < 0) { printf("  info  %s: unreadable (%s)\n", label, strerror(errno)); return; }
    n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) { printf("  info  %s: empty\n", label); return; }
    buf[n] = 0;
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\0')) buf[--n] = 0;
    printf("  info  %s: %s\n", label, buf);
}

static void status_lines(const char *const *keys, size_t nkeys)
{
    char line[512];
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) { printf("  info  /proc/self/status: unreadable (%s)\n", strerror(errno)); return; }
    while (fgets(line, sizeof line, f)) {
        size_t k, n = strlen(line);
        while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
        for (k = 0; k < nkeys; k++)
            if (strncmp(line, keys[k], strlen(keys[k])) == 0) printf("  info  %s\n", line);
    }
    fclose(f);
}

static void self_report(void)
{
    static const char *const keys[] = { "Uid:", "NoNewPrivs:", "Seccomp:", "Seccomp_filters:" };
    printf("self: pid %d ppid %d uid %d\n", (int)getpid(), (int)getppid(), (int)getuid());
    cat_first_line("/proc/self/attr/current", "selinux");
    status_lines(keys, sizeof keys / sizeof keys[0]);
    fflush(stdout);
}

/* ---- Phase 0b: which syscalls does this domain's filter actually allow? ----
 *
 * One forked child per syscall, SIGSYS reset to SIG_DFL so a seccomp trap shows
 * up as WTERMSIG rather than as bionic's SIGSYS logger. Arguments are chosen to
 * be rejected by the kernel, not to succeed: the question is only whether the
 * call *reaches* the kernel. So the readings are
 *
 *   errno N      the filter let it through; the kernel disliked the arguments
 *   ENOSYS       either the kernel has no such call, or the filter answers it
 *                with ENOSYS (Android does this for some) — ambiguous, flagged
 *   SIGSYS       the filter trapped it. This is the failure mode Phase 0b is
 *                looking for; a glibc guest issuing it dies here.
 *
 * The list is the 26 numbers the VESSEL_STRACE=1 run of `ls` produced, minus
 * exit_group, plus the modern calls glibc reaches for that bionic never does
 * (clone3 and faccessat2 in particular — glibc's clone3 fallback keys on ENOSYS
 * and would not survive a SIGSYS). */
struct syscall_probe { long nr; const char *name; long a0, a1, a2; };

static const struct syscall_probe SYSCALL_PROBES[] = {
    /* seen in the traced `ls` run */
    {   9, "lgetxattr",        0, 0, 0 },
    {  11, "listxattr",        0, 0, 0 },
    {  29, "ioctl",           -1, 0, 0 },
    {  43, "statfs",           0, 0, 0 },
    {  48, "faccessat",       -1, 0, 0 },
    {  56, "openat",          -1, 0, 0 },
    {  57, "close",           -1, 0, 0 },
    {  61, "getdents64",      -1, 0, 0 },
    {  62, "lseek",           -1, 0, 0 },
    {  63, "read",            -1, 0, 0 },
    {  64, "write",           -1, 0, 0 },
    {  79, "newfstatat",      -1, 0, 0 },
    {  80, "fstat",           -1, 0, 0 },
    {  96, "set_tid_address",  0, 0, 0 },
    {  98, "futex",            0, 0, 0 },
    {  99, "set_robust_list",  0, 0, 0 },
    { 198, "socket",           0, 0, 0 },
    { 203, "connect",         -1, 0, 0 },
    { 214, "brk",              0, 0, 0 },
    { 215, "munmap",           0, 0, 0 },
    { 222, "mmap",             0, 0, 0 },
    { 226, "mprotect",         0, 0, 0 },
    { 261, "prlimit64",        0, -1, 0 },
    { 278, "getrandom",        0, 0, 0 },
    { 291, "statx",           -1, 0, 0 },
    { 293, "rseq",             0, 0, 0 },
    /* not in that run, but a glibc userland reaches them soon after */
    { 101, "nanosleep",        0, 0, 0 },
    { 123, "sched_getaffinity",0, 0, 0 },
    { 167, "prctl",           -1, 0, 0 },
    { 168, "getcpu",           0, 0, 0 },
    { 260, "wait4",           -1, 0, 0 },
    { 283, "membarrier",       0, 0, 0 },
    { 435, "clone3",           0, 0, 0 },
    { 436, "close_range",     -1, -2, 0 },
    { 437, "openat2",         -1, 0, 0 },
    { 439, "faccessat2",      -1, 0, 0 },
};

static int selftest(void)
{
    size_t i;
    int trapped = 0;

    printf("\nsyscall matrix (one forked child each; SIGSYS = stopped by the filter):\n");
    for (i = 0; i < sizeof SYSCALL_PROBES / sizeof SYSCALL_PROBES[0]; i++) {
        const struct syscall_probe *s = &SYSCALL_PROBES[i];
        pid_t pid;
        int st = 0;

        fflush(stdout);
        pid = fork();
        if (pid < 0) { printf("  FAIL  fork: %s\n", strerror(errno)); failures++; break; }
        if (pid == 0) {
            struct sigaction sa;
            long rc;
            memset(&sa, 0, sizeof sa);
            sa.sa_handler = SIG_DFL;
            sigaction(SIGSYS, &sa, NULL);   /* bionic installs a logging handler */
            errno = 0;
            rc = syscall(s->nr, s->a0, s->a1, s->a2, 0L, 0L, 0L);
            _exit(rc < 0 ? (errno & 0xff) : 0);
        }
        waitpid(pid, &st, 0);
        if (WIFSIGNALED(st)) {
            printf("  %-5s %3ld %-18s killed by signal %d (%s)\n",
                   WTERMSIG(st) == SIGSYS ? "TRAP" : "sig", s->nr, s->name,
                   WTERMSIG(st), strsignal(WTERMSIG(st)));
            if (WTERMSIG(st) == SIGSYS) { trapped++; failures++; }
        } else {
            int e = WEXITSTATUS(st);
            printf("  %-5s %3ld %-18s %s\n",
                   e == ENOSYS ? "nosys" : "ok", s->nr, s->name,
                   e == 0 ? "returned >= 0" : strerror(e));
        }
    }
    printf("\nsyscall matrix: %d trapped by seccomp\n", trapped);
    return trapped;
}

/* ---- Phase 0b: can a SIGSYS handler stand in for the missing syscalls? -----
 *
 * Android's app filter answers a denied syscall with SECCOMP_RET_TRAP, not
 * SECCOMP_RET_KILL — which is why bionic can install a handler that logs before
 * dying. The same door is open to us: a handler that sets x0 to -ENOSYS and
 * returns makes the trapped call look to the caller like a kernel that does not
 * have it, which is a case every one of these callers already handles.
 *
 * Enabled with VESSEL_SIGSYS_SHIM=1, and *measured*, not assumed, because it is
 * doing something delicate: the handler is bionic-compiled code running after
 * glibc has taken TPIDR_EL0 for its own TLS. It therefore touches no TLS at
 * all — no errno, no libc call, no stack protector — and reports through a raw
 * `svc #0` write. Signal dispositions are per-process and survive the jump, so
 * installing it before branching is enough.
 */
static void raw_write(const char *s, unsigned long n)
{
    register long x0 __asm__("x0") = 2;              /* stderr */
    register const char *x1 __asm__("x1") = s;
    register long x2 __asm__("x2") = (long)n;
    register long x8 __asm__("x8") = 64;             /* aarch64 __NR_write */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory", "cc");
}

__attribute__((no_stack_protector))
static void sigsys_shim(int sig, siginfo_t *si, void *uc_)
{
    ucontext_t *uc = (ucontext_t *)uc_;
    char msg[48];
    int n = 0, d, started = 0;
    unsigned nr;

    (void)sig;
    nr = (unsigned)si->si_syscall;

    msg[n++] = 's'; msg[n++] = 'h'; msg[n++] = 'i'; msg[n++] = 'm'; msg[n++] = ' ';
    for (d = 100000; d > 0; d /= 10) {
        unsigned digit = (nr / (unsigned)d) % 10u;
        if (digit || started || d == 1) { msg[n++] = (char)('0' + digit); started = 1; }
    }
    msg[n++] = '\n';
    raw_write(msg, (unsigned long)n);

    /* The kernel did not run the call and pc is already past the svc, so the
     * only thing left is the return value. -ENOSYS is the one answer callers
     * are required to cope with. */
    uc->uc_mcontext.regs[0] = (unsigned long)(-ENOSYS);
}

static void install_sigsys_shim(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = sigsys_shim;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSYS, &sa, NULL);
}

static pid_t child_pid;
static void on_alarm(int sig) { (void)sig; if (child_pid > 0) kill(child_pid, SIGKILL); }

int main(int argc, char *argv[])
{
    struct loaded ld;
    void *stack;
    size_t stacksz = 8u << 20;
    char *sp_top, *strp;
    uint64_t inherited[MAXAUX][2];
    size_t ninherited, nenv = 0, i, vecwords;
    uint64_t *vec;
    uintptr_t sp;
    char **guest_argv = argv + 1;   /* argv[1] is the loader path */
    int guest_argc = argc - 1;
    char *randbytes, *platform, *execfn;
    char **argptr, **envptr;
    pid_t pid;
    int status;
    const char *strace_env = getenv("VESSEL_STRACE");
    int strace = strace_env && *strace_env == '1';

    PAGE = sysconf(_SC_PAGESIZE);
    setvbuf(stdout, NULL, _IONBF, 0);

    /* Before anything else, and in every mode: which domain, which filter. */
    self_report();

    if (guest_argc >= 1 && strcmp(guest_argv[0], "--selftest") == 0)
        return selftest() == 0 ? 0 : 1;

    if (guest_argc < 1) {
        fprintf(stderr, "usage: %s <ld-linux-aarch64.so.1> [args...]\n", argv[0]);
        fprintf(stderr, "       %s --selftest\n", argv[0]);
        return 2;
    }

    printf("glibcload: page size %ld, uid %d\n", PAGE, (int)getuid());
    printf("guest argv:");
    for (i = 0; i < (size_t)guest_argc; i++) printf(" %s", guest_argv[i]);
    printf("\n");

    /* Fork: the jump is one-way and may take a signal. The parent survives to
     * report what happened, which is the whole measurement. */
    pid = fork();
    if (pid < 0) { perror("fork"); return 1; }

    if (pid > 0) {
        int waited;
        child_pid = pid;
        signal(SIGALRM, on_alarm);
        alarm(strace ? 180 : 30);
        if (strace) { trace_child(pid); alarm(0); return 0; }
        waited = waitpid(pid, &status, 0);
        alarm(0);
        if (waited < 0) {
            printf("\nparent: waitpid failed: %s (errno %d) - killing child\n", strerror(errno), errno);
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            return 1;
        }
        printf("\n");
        if (WIFEXITED(status))
            printf("RESULT: guest exited %d\n", WEXITSTATUS(status));
        else if (WIFSIGNALED(status))
            printf("RESULT: guest killed by signal %d (%s)\n", WTERMSIG(status), strsignal(WTERMSIG(status)));
        else
            printf("RESULT: guest status 0x%x\n", status);
        return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
    }

    /* ---- child: map, build a stack, jump. No return from here. ---- */

    printf("mapping:\n");
    if (load_elf(guest_argv[0], &ld) != 0) {
        printf("\n%d failure(s) before the jump\n", failures);
        _exit(3);
    }
    printf("  info  entry 0x%lx phdr 0x%lx phnum %zu\n",
           (unsigned long)ld.entry, (unsigned long)ld.phdr, ld.phnum);

    stack = mmap(NULL, stacksz, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    report("guest stack (8 MiB anon RW)", stack != MAP_FAILED);
    if (stack == MAP_FAILED) _exit(3);

    /* Strings first, downward from the top of the stack region. */
    sp_top = (char *)stack + stacksz;
    strp   = sp_top;

    #define PUSH_STR(s) ({ size_t _n = strlen(s) + 1; strp -= _n; memcpy(strp, (s), _n); strp; })

    execfn   = PUSH_STR(guest_argv[0]);
    platform = PUSH_STR("aarch64");
    strp -= 16;
    strp = (char *)((uintptr_t)strp & ~(uintptr_t)15);
    randbytes = strp;
    {   /* AT_RANDOM: 16 bytes glibc uses for the stack guard and pointer mangling */
        int rf = open("/dev/urandom", O_RDONLY);
        if (rf < 0 || read(rf, randbytes, 16) != 16) memset(randbytes, 0x5a, 16);
        if (rf >= 0) close(rf);
    }

    argptr = calloc((size_t)guest_argc + 1, sizeof *argptr);
    for (i = 0; i < (size_t)guest_argc; i++) argptr[i] = PUSH_STR(guest_argv[i]);
    argptr[guest_argc] = NULL;

    while (environ[nenv]) nenv++;
    envptr = calloc(nenv + 1, sizeof *envptr);
    for (i = 0; i < nenv; i++) envptr[i] = PUSH_STR(environ[i]);
    envptr[nenv] = NULL;
    #undef PUSH_STR

    ninherited = read_auxv(inherited);

    /* argc + argv + NULL + envp + NULL + auxv pairs + AT_NULL pair */
    vecwords = 1 + (size_t)guest_argc + 1 + nenv + 1 + 2 * (ninherited + 9) + 2;
    sp = (uintptr_t)strp - vecwords * 8;
    sp &= ~(uintptr_t)15;                 /* SysV: sp 16-byte aligned at entry */
    vec = (uint64_t *)sp;

    {
        size_t k = 0;
        vec[k++] = (uint64_t)guest_argc;
        for (i = 0; i < (size_t)guest_argc; i++) vec[k++] = (uint64_t)(uintptr_t)argptr[i];
        vec[k++] = 0;
        for (i = 0; i < nenv; i++) vec[k++] = (uint64_t)(uintptr_t)envptr[i];
        vec[k++] = 0;
        for (i = 0; i < ninherited; i++) {
            if (aux_overridden(inherited[i][0])) continue;
            vec[k++] = inherited[i][0];
            vec[k++] = inherited[i][1];
        }
        #define AUX(t, v) do { vec[k++] = (uint64_t)(t); vec[k++] = (uint64_t)(v); } while (0)
        AUX(AT_PHDR,   ld.phdr);
        AUX(AT_PHENT,  ld.phent);
        AUX(AT_PHNUM,  ld.phnum);
        /* AT_BASE 0 and AT_ENTRY == the loader's own entry is exactly what the
         * kernel hands `ld.so /some/prog`, and is how rtld knows it is being
         * run directly rather than as an interpreter. */
        AUX(AT_BASE,   0);
        AUX(AT_ENTRY,  ld.entry);
        AUX(AT_FLAGS,  0);
        AUX(AT_EXECFN, (uintptr_t)execfn);
        AUX(AT_RANDOM, (uintptr_t)randbytes);
        AUX(AT_PLATFORM, (uintptr_t)platform);
        AUX(AT_NULL,   0);
        #undef AUX
    }

    printf("  info  guest sp 0x%lx, %zu inherited auxv entries\n", (unsigned long)sp, ninherited);
    printf("jumping to 0x%lx - anything below this line came from glibc\n", (unsigned long)ld.entry);
    fflush(stdout);

    /* bionic's debuggerd handler would run bionic code with a TCB glibc is
     * about to overwrite. Let the kernel kill us cleanly instead: the parent's
     * WTERMSIG is a better measurement than a hung tombstone. */
    {
        struct sigaction sa;
        int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGSYS, SIGABRT, SIGTRAP };
        size_t s;
        memset(&sa, 0, sizeof sa);
        sa.sa_handler = SIG_DFL;
        for (s = 0; s < sizeof sigs / sizeof sigs[0]; s++) sigaction(sigs[s], &sa, NULL);
    }

    /* …except SIGSYS, if the shim was asked for. Installed last so it wins over
     * the SIG_DFL reset above. */
    if (getenv("VESSEL_SIGSYS_SHIM") && getenv("VESSEL_SIGSYS_SHIM")[0] == '1') {
        install_sigsys_shim();
        printf("sigsys shim: installed (trapped syscalls will return -ENOSYS)\n");
        fflush(stdout);
    }

    if (strace) {
        /* Hand ourselves to the parent one instruction before the point of no
         * return, so the count covers glibc and nothing of the bionic setup. */
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) { perror("PTRACE_TRACEME"); _exit(4); }
        raise(SIGSTOP);
    }

    {
        register uintptr_t r_sp    __asm__("x9")  = sp;
        register uintptr_t r_entry __asm__("x10") = ld.entry;
        __asm__ volatile(
            "mov x0, xzr\n\t"     /* rtld_fini: the kernel passes 0 here */
            "mov x1, xzr\n\t"
            "mov x2, xzr\n\t"
            "mov x3, xzr\n\t"
            "mov x29, xzr\n\t"
            "mov x30, xzr\n\t"
            "mov sp, %0\n\t"
            "br  %1\n"
            :
            : "r"(r_sp), "r"(r_entry)
            : "x0", "x1", "x2", "x3", "x30", "memory");
    }
    __builtin_unreachable();
}
