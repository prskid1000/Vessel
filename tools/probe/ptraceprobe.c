/*
 * Probe 2 of docs/LINUX-MODE.md §7 Phase 0: may an app process ptrace its own
 * child at targetSdk 36 on Android 16?
 *
 * PRoot is a ptrace supervisor — it stops the guest on every syscall entry and
 * exit and rewrites path arguments (LINUX-MODE.md §1.3). Termux relies on this
 * working, so the study assumes untrusted_app policy permits it, and marks the
 * assumption *unsure* because nobody here has run it on this device at this
 * targetSdk. This probe does the minimum PRoot needs and no more:
 *
 *   fork -> PTRACE_TRACEME -> SIGSTOP -> one PTRACE_SYSCALL round trip,
 *   reading the syscall number out of NT_PRSTATUS in between.
 *
 * It then does the second thing PRoot needs, PTRACE_ATTACH to an already
 * running child, because Yama's ptrace_scope governs that separately from
 * TRACEME even when both are the same process tree.
 *
 * Every failure prints its errno; that errno is the measurement.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

static int failures;

static void report(const char *what, int ok)
{
    if (ok) printf("  ok    %s\n", what);
    else { printf("  FAIL  %s: %s (errno %d)\n", what, strerror(errno), errno); failures++; }
    fflush(stdout);
}

static void show_file(const char *path)
{
    char buf[256];
    int fd = open(path, O_RDONLY);
    ssize_t n;
    if (fd < 0) { printf("  %-44s (unreadable: %s)\n", path, strerror(errno)); return; }
    n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) { printf("  %-44s (empty)\n", path); return; }
    buf[n] = 0;
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    printf("  %-44s %s\n", path, buf);
}

/* Seccomp / NoNewPrivs are the two lines of /proc/self/status that decide
 * whether a tracer can do anything useful here; printing all of it is noise. */
static void show_status_lines(void)
{
    FILE *f = fopen("/proc/self/status", "r");
    char line[256];
    if (!f) { printf("  /proc/self/status unreadable: %s\n", strerror(errno)); return; }
    while (fgets(line, sizeof line, f))
        if (!strncmp(line, "Seccomp", 7) || !strncmp(line, "NoNewPrivs", 10)
            || !strncmp(line, "TracerPid", 9) || !strncmp(line, "CapEff", 6))
            printf("  %s", line);
    fclose(f);
}

static int traceme_round_trip(void)
{
    pid_t pid;
    int status;
    struct user_regs_struct regs;
    struct iovec iov = { .iov_base = &regs, .iov_len = sizeof regs };

    printf("PTRACE_TRACEME + one PTRACE_SYSCALL round trip:\n");

    pid = fork();
    if (pid < 0) { report("fork", 0); return -1; }
    if (pid == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) _exit(90 + (errno & 0x1f));
        raise(SIGSTOP);
        (void)syscall(__NR_getpid);   /* the syscall the tracer should catch */
        _exit(0);
    }

    if (waitpid(pid, &status, 0) < 0) { report("waitpid for the TRACEME stop", 0); goto kill; }
    if (!WIFSTOPPED(status)) {
        if (WIFEXITED(status) && WEXITSTATUS(status) >= 90) {
            errno = WEXITSTATUS(status) - 90;
            report("child PTRACE_TRACEME", 0);
        } else {
            printf("  FAIL  child did not stop; status 0x%x\n", status);
            failures++;
        }
        return -1;
    }
    report("child stopped under TRACEME", 1);

    errno = 0;
    report("PTRACE_SETOPTIONS PTRACE_O_TRACESYSGOOD",
           ptrace(PTRACE_SETOPTIONS, pid, 0, (void *)PTRACE_O_TRACESYSGOOD) == 0);

    errno = 0;
    if (ptrace(PTRACE_SYSCALL, pid, 0, 0) != 0) { report("PTRACE_SYSCALL (to entry)", 0); goto kill; }
    report("PTRACE_SYSCALL (to entry)", 1);
    if (waitpid(pid, &status, 0) < 0 || !WIFSTOPPED(status)) {
        printf("  FAIL  no syscall-entry stop; status 0x%x\n", status); failures++; goto kill;
    }
    report("syscall-entry stop", 1);

    errno = 0;
    if (ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &iov) != 0) {
        report("PTRACE_GETREGSET NT_PRSTATUS", 0);
    } else {
        report("PTRACE_GETREGSET NT_PRSTATUS", 1);
        printf("  info  syscall nr x8=%llu, pc=0x%llx (__NR_getpid is %d)\n",
               (unsigned long long)regs.regs[8], (unsigned long long)regs.pc, __NR_getpid);
    }

    errno = 0;
    if (ptrace(PTRACE_SYSCALL, pid, 0, 0) != 0) { report("PTRACE_SYSCALL (to exit)", 0); goto kill; }
    if (waitpid(pid, &status, 0) < 0 || !WIFSTOPPED(status)) {
        printf("  FAIL  no syscall-exit stop; status 0x%x\n", status); failures++; goto kill;
    }
    report("syscall-exit stop", 1);
    errno = 0;
    if (ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &iov) == 0)
        printf("  info  return value x0=%lld (child pid is %d)\n",
               (long long)regs.regs[0], (int)pid);

    /* PRoot also pokes the tracee's memory to rewrite path arguments. */
    errno = 0;
    {
        long word = ptrace(PTRACE_PEEKDATA, pid, (void *)(uintptr_t)regs.pc, 0);
        report("PTRACE_PEEKDATA at the tracee's pc", !(word == -1 && errno != 0));
    }

    errno = 0;
    report("PTRACE_CONT", ptrace(PTRACE_CONT, pid, 0, 0) == 0);
    waitpid(pid, &status, 0);
    printf("  info  tracee final status 0x%x\n", status);
    return 0;
kill:
    kill(pid, SIGKILL);
    waitpid(pid, &status, 0);
    return -1;
}

static void attach_test(void)
{
    pid_t pid;
    int status;

    printf("PTRACE_ATTACH to a running child:\n");
    pid = fork();
    if (pid < 0) { report("fork", 0); return; }
    if (pid == 0) { for (;;) pause(); }

    usleep(100000);
    errno = 0;
    if (ptrace(PTRACE_ATTACH, pid, 0, 0) != 0) {
        report("PTRACE_ATTACH", 0);
    } else {
        report("PTRACE_ATTACH", 1);
        waitpid(pid, &status, 0);
        report("attach stop observed", WIFSTOPPED(status));
        errno = 0;
        report("PTRACE_DETACH", ptrace(PTRACE_DETACH, pid, 0, 0) == 0);
    }
    kill(pid, SIGKILL);
    waitpid(pid, &status, 0);
}

int main(void)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("ptraceprobe: uid %d pid %d\n", (int)getuid(), (int)getpid());
    printf("kernel knobs:\n");
    show_file("/proc/sys/kernel/yama/ptrace_scope");
    show_status_lines();
    printf("\n");

    traceme_round_trip();
    printf("\n");
    attach_test();

    printf("\n%d failure(s)\n", failures);
    return failures ? 1 : 0;
}
