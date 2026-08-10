/*
 * Probe 3 of docs/LINUX-MODE.md §7 Phase 0: is unprivileged overlayfs, a user
 * namespace, or any mount(2) at all available to the app?
 *
 * §5.1 rules overlayfs out as the shared-base mechanism on *known, not
 * verified* grounds, and then says so out loud: "Worth running rather than
 * assuming — this document's whole standard is that plausible is not measured."
 * This is that run. Three questions, each answered with an errno:
 *
 *   1. Does the kernel even have overlayfs compiled in?  /proc/filesystems
 *   2. Can this uid create a user namespace?             unshare(CLONE_NEWUSER)
 *   3. Can it mount anything?                            mount(2), four shapes
 *
 * Each unshare and each mount runs in a forked child, so a success does not
 * change the state the next case is measured in.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static int failures;

static void result(const char *what, int ok, int err)
{
    if (ok) printf("  ok    %s\n", what);
    else { printf("  FAIL  %s: %s (errno %d)\n", what, strerror(err), err); failures++; }
    fflush(stdout);
}

static void show_file(const char *path)
{
    char buf[128];
    int fd = open(path, O_RDONLY);
    ssize_t n;
    if (fd < 0) { printf("  %-46s absent (%s)\n", path, strerror(errno)); return; }
    n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) { printf("  %-46s empty\n", path); return; }
    buf[n] = 0;
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    printf("  %-46s %s\n", path, buf);
}

/* Run one call in a child; the child's exit code carries the errno back so a
 * namespace it managed to enter dies with it. */
static void in_child(const char *what, int (*fn)(void *), void *arg)
{
    pid_t pid = fork();
    int status;
    if (pid < 0) { result(what, 0, errno); return; }
    if (pid == 0) {
        int rc = fn(arg);
        _exit(rc == 0 ? 0 : (errno ? (errno & 0x7f) : 0x7f));
    }
    waitpid(pid, &status, 0);
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) result(what, 1, 0);
    else if (WIFEXITED(status)) result(what, 0, WEXITSTATUS(status));
    else { printf("  FAIL  %s: killed by signal %d\n", what, WTERMSIG(status)); failures++; }
}

static int do_userns(void *unused)      { (void)unused; return unshare(CLONE_NEWUSER); }
static int do_mountns(void *unused)     { (void)unused; return unshare(CLONE_NEWNS); }
static int do_both_ns(void *unused)     { (void)unused; return unshare(CLONE_NEWUSER | CLONE_NEWNS); }

/* `unshare -Ur true` in C: make the namespace, then map uid 0 to ourselves,
 * which is the step that turns it into the "fake root" PRoot-free designs want. */
static int do_userns_map_root(void *unused)
{
    char buf[64];
    int fd;
    uid_t uid = getuid();
    gid_t gid = getgid();
    (void)unused;
    if (unshare(CLONE_NEWUSER) != 0) return -1;
    fd = open("/proc/self/setgroups", O_WRONLY);
    if (fd >= 0) { (void)!write(fd, "deny", 4); close(fd); }
    fd = open("/proc/self/uid_map", O_WRONLY);
    if (fd < 0) return -1;
    snprintf(buf, sizeof buf, "0 %u 1\n", (unsigned)uid);
    if (write(fd, buf, strlen(buf)) < 0) { int e = errno; close(fd); errno = e; return -1; }
    close(fd);
    fd = open("/proc/self/gid_map", O_WRONLY);
    if (fd < 0) return -1;
    snprintf(buf, sizeof buf, "0 %u 1\n", (unsigned)gid);
    if (write(fd, buf, strlen(buf)) < 0) { int e = errno; close(fd); errno = e; return -1; }
    close(fd);
    return geteuid() == 0 ? 0 : -1;
}

struct mnt { const char *src, *tgt, *type; unsigned long flags; const char *data; };

static int do_mount(void *arg)
{
    struct mnt *m = arg;
    return mount(m->src, m->tgt, m->type, m->flags, m->data);
}

/* The overlay mount the shared-base design of §5.3 would need, inside a user
 * namespace — the only shape that works on a desktop Linux. */
static int do_userns_overlay(void *arg)
{
    struct mnt *m = arg;
    if (do_userns_map_root(NULL) != 0) return -1;
    if (unshare(CLONE_NEWNS) != 0) return -1;
    return mount(m->src, m->tgt, m->type, m->flags, m->data);
}

int main(int argc, char *argv[])
{
    const char *dir = argc > 1 ? argv[1] : ".";
    char lower[512], upper[512], work[512], target[512], opts[2048], bindsrc[512];
    struct mnt m;

    setvbuf(stdout, NULL, _IONBF, 0);
    printf("nsprobe: uid %d gid %d, scratch dir %s\n", (int)getuid(), (int)getgid(), dir);

    printf("\n1. overlayfs support in the kernel:\n");
    {
        FILE *f = fopen("/proc/filesystems", "r");
        char line[128];
        int found = 0, total = 0;
        if (!f) { printf("  /proc/filesystems unreadable: %s\n", strerror(errno)); }
        else {
            while (fgets(line, sizeof line, f)) {
                total++;
                if (strstr(line, "overlay")) { printf("  match: %s", line); found++; }
            }
            fclose(f);
            printf("  %d line(s) matching \"overlay\" out of %d filesystems\n", found, total);
        }
    }
    show_file("/proc/sys/kernel/unprivileged_userns_clone");
    show_file("/proc/sys/user/max_user_namespaces");
    show_file("/proc/sys/user/max_mnt_namespaces");
    show_file("/proc/sys/fs/may_detach_mounts");

    printf("\n2. namespaces:\n");
    in_child("unshare(CLONE_NEWUSER)", do_userns, NULL);
    in_child("unshare(CLONE_NEWNS)", do_mountns, NULL);
    in_child("unshare(CLONE_NEWUSER|CLONE_NEWNS)", do_both_ns, NULL);
    in_child("unshare -Ur equivalent (userns + uid_map 0)", do_userns_map_root, NULL);

    printf("\n3. mount(2):\n");
    snprintf(lower,  sizeof lower,  "%s/ovl-lower", dir);
    snprintf(upper,  sizeof upper,  "%s/ovl-upper", dir);
    snprintf(work,   sizeof work,   "%s/ovl-work",  dir);
    snprintf(target, sizeof target, "%s/ovl-mnt",   dir);
    snprintf(bindsrc, sizeof bindsrc, "%s/bind-src", dir);
    mkdir(lower, 0700); mkdir(upper, 0700); mkdir(work, 0700);
    mkdir(target, 0700); mkdir(bindsrc, 0700);
    snprintf(opts, sizeof opts, "lowerdir=%s,upperdir=%s,workdir=%s", lower, upper, work);

    m = (struct mnt){ "tmpfs", target, "tmpfs", 0, NULL };
    in_child("mount(tmpfs)", do_mount, &m);

    m = (struct mnt){ "overlay", target, "overlay", 0, opts };
    in_child("mount(overlay)", do_mount, &m);

    m = (struct mnt){ bindsrc, target, NULL, MS_BIND, NULL };
    in_child("mount(MS_BIND)", do_mount, &m);

    m = (struct mnt){ "overlay", target, "overlay", 0, opts };
    in_child("mount(overlay) inside a mapped-root userns", do_userns_overlay, &m);

    rmdir(lower); rmdir(upper); rmdir(work); rmdir(target); rmdir(bindsrc);

    printf("\n%d failure(s)\n", failures);
    return failures ? 1 : 0;
}
