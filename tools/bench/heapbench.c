/*
 * heapbench -- is a DMA-BUF heap mapping cached, and what does the CPU-access
 * bracket around it cost?
 *
 * Both questions are asked about the same buffer the DRI3 present path uses:
 * Turnip allocates the swapchain image from `/dev/dma_heap/system`
 * (`native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc:2706`), Vessel's X server
 * mmaps the fd and CPU-reads the whole image once a frame, and that read
 * measured ~154 MB/s through the guest stack. `docs/BANDWIDTH.md` §9 has the
 * chain of inference about why it should nonetheless be a *cached* mapping.
 *
 * **This program exists because that chain is inference and nothing on the
 * device reports a mapping's memory type.** There is no sysfs file, no
 * /proc entry and no ioctl that answers "is this vma cacheable". The only
 * instrument is a stopwatch: a cached read runs at several GB/s on this class
 * of part and a write-combine one runs at 100-400 MB/s, and those do not
 * overlap. So the measurement *is* the answer, and it needs no kernel source
 * and no agreement about which heap implementation is behind the name.
 *
 * It also times `DMA_BUF_IOCTL_SYNC(START|READ)` and `(END|READ)` separately,
 * because on a cached buffer those drive real cache maintenance across every
 * attachment and are the leading candidate for where the 19 ms actually goes.
 * The X server brackets its copy with exactly this pair
 * (`Drawable.beginDmaBufRead`/`endDmaBufRead`).
 *
 * Three properties make the numbers mean something, and they are the same
 * three `tools/device-bench.sh` is built on:
 *
 *   1. **A control.** Every heap read is paired with a malloc-to-malloc copy of
 *      the same size in the same process. That is a known-cached read. If the
 *      heap number and the control number are the same, the heap is cached; if
 *      the heap is an order of magnitude slower, it is not. No absolute figure
 *      has to be trusted, only the ratio.
 *   2. **Best-of-N**, not one sample. A phone throttles and this runs next to
 *      whatever else is on it.
 *   3. **Cold and warm, reported separately.** 3.5 MB fits in a system-level
 *      cache on this class of SoC, so a back-to-back re-read can hit SLC and
 *      report a speed the real path never sees -- the real path reads a buffer
 *      the GPU has just written. The cold figure walks an eviction buffer
 *      first and is the one comparable with the 19 ms.
 *
 * Destination is always malloc'd, because the X server's real destination is a
 * gralloc AHardwareBuffer whose cacheability is its own open question
 * (§9.4(b)); pinning it to ordinary memory keeps this measurement about the
 * source.
 *
 * Build (static, so nothing has to exist on the device):
 *
 *   $NDK/toolchains/llvm/prebuilt/<host>/bin/clang \
 *       --target=aarch64-linux-android31 -O2 -static -o heapbench heapbench.c
 *
 * Run:
 *
 *   adb push heapbench /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/heapbench
 *   adb shell /data/local/tmp/heapbench
 *
 * Takes an optional size in bytes (default 3686400, one 1280x720 BGRA frame)
 * and an optional repeat count.
 *
 * **It reports what it could not do rather than skipping it.** An `open` or an
 * `ioctl` refused by SELinux is a result -- it says this uid cannot reach the
 * heap and the run has to be repeated from the app's own domain -- so every
 * failure prints its errno and the program carries on to the next heap.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <linux/dma-buf.h>
#include <linux/dma-heap.h>

#define HEAP_DIR "/dev/dma_heap"
#define DEFAULT_SIZE (1280 * 720 * 4)
#define DEFAULT_REPEATS 10
/* Larger than any plausible last-level/system cache on a phone, so a walk of it
 * evicts the buffer under test. Read-and-sum so the compiler cannot drop it. */
#define EVICT_BYTES (64u << 20)

static volatile uint64_t sink;

static double now_ms(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

static void evict(unsigned char *scratch)
{
   uint64_t sum = 0;
   for (size_t i = 0; i < EVICT_BYTES; i += 64)
      sum += scratch[i];
   sink += sum;
}

/* Best (fastest) of `repeats`, in MB/s, for a memcpy of `size` out of `src`. */
static double copy_mbs(void *dst, const void *src, size_t size, int repeats,
                       unsigned char *scratch)
{
   double best = 1e18;
   for (int i = 0; i < repeats; i++) {
      if (scratch)
         evict(scratch);
      double t0 = now_ms();
      memcpy(dst, src, size);
      double dt = now_ms() - t0;
      sink += ((unsigned char *) dst)[size - 1];
      if (dt < best)
         best = dt;
   }
   return best > 0 ? (size / 1048576.0) / (best / 1000.0) : 0;
}

/* Best-of-N for one DMA_BUF_IOCTL_SYNC, in milliseconds. Negative on refusal. */
static double sync_ms(int fd, int start, int repeats)
{
   double best = 1e18;
   for (int i = 0; i < repeats; i++) {
      struct dma_buf_sync s = {
         .flags = DMA_BUF_SYNC_READ |
                  (start ? DMA_BUF_SYNC_START : DMA_BUF_SYNC_END),
      };
      double t0 = now_ms();
      int ret = ioctl(fd, DMA_BUF_IOCTL_SYNC, &s);
      double dt = now_ms() - t0;
      if (ret < 0)
         return -1;
      if (dt < best)
         best = dt;
   }
   return best;
}

static void bench_heap(const char *name, size_t size, int repeats,
                       unsigned char *dst, unsigned char *scratch,
                       double control_mbs)
{
   char path[256];
   snprintf(path, sizeof(path), HEAP_DIR "/%s", name);

   int heap_fd = open(path, O_RDONLY | O_CLOEXEC);
   if (heap_fd < 0) {
      printf("heap %-24s open FAILED errno=%d (%s)\n", name, errno,
             strerror(errno));
      return;
   }

   struct dma_heap_allocation_data alloc = {
      .len = size,
      .fd_flags = O_RDWR | O_CLOEXEC,
   };
   if (ioctl(heap_fd, DMA_HEAP_IOCTL_ALLOC, &alloc) < 0) {
      printf("heap %-24s alloc FAILED errno=%d (%s)\n", name, errno,
             strerror(errno));
      close(heap_fd);
      return;
   }
   close(heap_fd);

   unsigned char *map = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED,
                             alloc.fd, 0);
   if (map == MAP_FAILED) {
      printf("heap %-24s mmap FAILED errno=%d (%s)\n", name, errno,
             strerror(errno));
      close(alloc.fd);
      return;
   }

   /* Fault every page in and give the buffer known content, so the timed
    * passes measure reads and not minor faults. */
   memset(map, 0x5a, size);

   double warm = copy_mbs(dst, map, size, repeats, NULL);
   double cold = copy_mbs(dst, map, size, repeats, scratch);

   double t_start = sync_ms(alloc.fd, 1, repeats);
   double t_end = sync_ms(alloc.fd, 0, repeats);

   /* The bracketed shape the X server actually issues, end to end. */
   double bracket = 1e18;
   for (int i = 0; i < repeats; i++) {
      struct dma_buf_sync s0 = { .flags = DMA_BUF_SYNC_READ | DMA_BUF_SYNC_START };
      struct dma_buf_sync s1 = { .flags = DMA_BUF_SYNC_READ | DMA_BUF_SYNC_END };
      evict(scratch);
      double t0 = now_ms();
      ioctl(alloc.fd, DMA_BUF_IOCTL_SYNC, &s0);
      memcpy(dst, map, size);
      ioctl(alloc.fd, DMA_BUF_IOCTL_SYNC, &s1);
      double dt = now_ms() - t0;
      if (dt < bracket)
         bracket = dt;
   }

   printf("heap %-24s warm=%8.1f MB/s  cold=%8.1f MB/s  ratio_vs_control=%.3f\n",
          name, warm, cold, control_mbs > 0 ? cold / control_mbs : 0);
   if (t_start < 0)
      printf("     %-24s syncStart REFUSED errno=%d (%s)  syncEnd n/a\n", "",
             errno, strerror(errno));
   else
      printf("     %-24s syncStart=%.3f ms  syncEnd=%.3f ms\n", "", t_start,
             t_end);
   printf("     %-24s bracketed(start+copy+end) cold=%.3f ms\n", "", bracket);

   munmap(map, size);
   close(alloc.fd);
}

int main(int argc, char **argv)
{
   size_t size = argc > 1 ? (size_t) strtoul(argv[1], NULL, 0) : DEFAULT_SIZE;
   int repeats = argc > 2 ? atoi(argv[2]) : DEFAULT_REPEATS;

   if (size < 4096 || repeats < 1) {
      fprintf(stderr, "usage: heapbench [bytes] [repeats]\n");
      return 2;
   }

   printf("heapbench size=%zu bytes (%.2f MB) repeats=%d\n", size,
          size / 1048576.0, repeats);

   /* Which domain this ran in, because an SELinux refusal below is only
    * interpretable next to it -- shell and untrusted_app are not the same
    * question, and the X server is the latter. */
   {
      char ctx[256] = "";
      int fd = open("/proc/self/attr/current", O_RDONLY);
      if (fd >= 0) {
         ssize_t n = read(fd, ctx, sizeof(ctx) - 1);
         if (n > 0)
            ctx[n] = '\0';
         close(fd);
      }
      printf("uid=%d selinux=%s\n", (int) getuid(), ctx[0] ? ctx : "(unknown)");
   }

   printf("/dev/ion %s\n", access("/dev/ion", F_OK) == 0 ? "present" : "absent");

   unsigned char *dst = malloc(size);
   unsigned char *ctrl = malloc(size);
   unsigned char *scratch = malloc(EVICT_BYTES);
   if (!dst || !ctrl || !scratch) {
      fprintf(stderr, "out of memory\n");
      return 1;
   }
   memset(ctrl, 0xa5, size);
   memset(scratch, 1, EVICT_BYTES);

   /* The control: a read of ordinary, definitely-cached memory, same size,
    * same repeats, same eviction. Every heap figure is reported as a ratio
    * against this so no absolute number has to be believed. */
   double control_warm = copy_mbs(dst, ctrl, size, repeats, NULL);
   double control_cold = copy_mbs(dst, ctrl, size, repeats, scratch);
   printf("control malloc->malloc     warm=%8.1f MB/s  cold=%8.1f MB/s\n",
          control_warm, control_cold);

   DIR *dir = opendir(HEAP_DIR);
   if (!dir) {
      printf("%s unreadable errno=%d (%s)\n", HEAP_DIR, errno, strerror(errno));
      return 0;
   }

   struct dirent *ent;
   while ((ent = readdir(dir)) != NULL) {
      if (ent->d_name[0] == '.')
         continue;
      /* Secure heaps hand back memory the CPU may not map at all, and asking
       * costs a scary-looking SELinux denial in everyone's logs for an answer
       * nobody needs: the swapchain does not come from one. */
      if (strstr(ent->d_name, "secure") || strstr(ent->d_name, "qseecom"))
         continue;
      bench_heap(ent->d_name, size, repeats, dst, scratch, control_cold);
   }
   closedir(dir);

   return 0;
}
