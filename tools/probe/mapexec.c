/*
 * Why can't Wine make a PE section executable inside the app sandbox?
 *
 *   err:virtual:map_image_into_view failed to set 60000020 protection on
 *       ntdll.dll section .text, noexec filesystem?
 *
 * Wine prints that without an errno, and the three obvious explanations were
 * all ruled out on the device by hand: SELinux logs `granted { execute }` on
 * app_data_file, /data/user/0 is not mounted noexec, and the kernel page size
 * is 4096 so PE sections are not sub-page. This probe exists because guessing
 * had run out — it does the same mmap/mprotect calls Wine does and prints the
 * errno Wine swallows.
 *
 * Build with ./tools/probe/build.sh, run under the app's own uid:
 *   adb shell run-as app.vessel ./mapexec <a-pe-dll>
 *
 * Read the results as a decision tree:
 *   anon RWX fails            -> no W^X escape at all; the design needs rework
 *   anon ok, file PROT_EXEC   -> file-backed exec is the restriction; a Wine
 *     at mmap time fails         patch mapping images anonymously is the fix
 *   mmap ok, mprotect fails   -> only *raising* protection is blocked, so
 *                                mapping the image PROT_EXEC up front is enough
 *   everything passes         -> the cause is specific to Wine's own mapping
 *                                (fixed address, MAP_FIXED over a reservation)
 *                                and the next step is to reproduce that shape
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static int failures;

static void report(const char *what, int ok)
{
    if (ok) {
        printf("  ok    %s\n", what);
    } else {
        printf("  FAIL  %s: %s (errno %d)\n", what, strerror(errno), errno);
        failures++;
    }
}

int main(int argc, char *argv[])
{
    const char *path = argc > 1 ? argv[1] : NULL;
    long page = sysconf(_SC_PAGESIZE);
    void *p;
    int fd;
    struct stat st;

    printf("page size %ld\n", page);

    /* Anonymous exec memory. Every JIT on Android needs this, so it should
     * pass; if it does not, nothing else here matters. */
    printf("anonymous:\n");
    p = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    report("mmap RW", p != MAP_FAILED);
    if (p != MAP_FAILED) {
        report("mprotect RX", mprotect(p, page, PROT_READ | PROT_EXEC) == 0);
        munmap(p, page);
    }
    p = mmap(NULL, page, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    report("mmap RWX", p != MAP_FAILED);
    if (p != MAP_FAILED) munmap(p, page);

    if (!path) {
        printf("\nno file given; skipping the file-backed cases\n");
        return failures ? 1 : 0;
    }

    fd = open(path, O_RDONLY);
    if (fd < 0) {
        printf("open %s: %s\n", path, strerror(errno));
        return 1;
    }
    fstat(fd, &st);
    printf("file-backed (%s, %lld bytes):\n", path, (long long)st.st_size);

    /* Wine maps the image PROT_READ first and then raises each section's
     * protection, so both orders are worth distinguishing. */
    p = mmap(NULL, page, PROT_READ, MAP_PRIVATE, fd, 0);
    report("mmap R", p != MAP_FAILED);
    if (p != MAP_FAILED) {
        report("  then mprotect RX", mprotect(p, page, PROT_READ | PROT_EXEC) == 0);
        munmap(p, page);
    }

    p = mmap(NULL, page, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    report("mmap RX directly", p != MAP_FAILED);
    if (p != MAP_FAILED) munmap(p, page);

    /* Wine's actual shape: reserve a range, then map the image over it with
     * MAP_FIXED. MAP_FIXED over an existing reservation is the one case that
     * behaves differently on some kernels. */
    p = mmap(NULL, page * 4, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p != MAP_FAILED) {
        void *q = mmap(p, page, PROT_READ | PROT_EXEC, MAP_PRIVATE | MAP_FIXED, fd, 0);
        report("MAP_FIXED RX over a PROT_NONE reservation", q != MAP_FAILED);
        munmap(p, page * 4);
    }

    /* Wine writes relocations into the image, so .text often ends up RWX. */
    p = mmap(NULL, page, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE, fd, 0);
    report("mmap RWX (private, for relocations)", p != MAP_FAILED);
    if (p != MAP_FAILED) munmap(p, page);

    /* The case that actually matters, and the reason this probe was extended.
     *
     * A PE loaded away from its ImageBase gets relocations written into it
     * before its sections are protected. Those writes dirty COW pages of a
     * private file mapping, and making *modified* file-backed pages executable
     * is a separate SELinux permission — execmod, not execute. Android does not
     * grant apps execmod, and parts of the policy dontaudit it, so it is denied
     * with no log line: exactly the silence observed here.
     *
     * If the clean case passes and the dirtied one fails, that is the bug: Wine
     * must put relocated images in anonymous memory (execmem, which apps do
     * have) rather than file-backed pages. */
    p = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    if (p != MAP_FAILED) {
        report("clean private page -> RX", mprotect(p, page, PROT_READ | PROT_EXEC) == 0);
        munmap(p, page);
    }
    p = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    if (p != MAP_FAILED) {
        ((volatile char *)p)[0] = 0x42;   /* the relocation write */
        report("DIRTIED private page -> RX (execmod)",
               mprotect(p, page, PROT_READ | PROT_EXEC) == 0);
        munmap(p, page);
    }

    close(fd);
    printf("\n%d failure(s)\n", failures);
    return failures ? 1 : 0;
}
