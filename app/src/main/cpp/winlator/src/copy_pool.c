/*
 * VESSEL: a persistent worker pool that copies one rectangle as N row bands.
 * No upstream counterpart — upstream Winlator has no DRI3, so nothing there
 * reads a whole frame out of a client's mapping once per vblank.
 *
 * **The number this file exists for.** With DRI3 on, Mesa's X11 WSI allocates
 * the swapchain image from the kernel's system dma-heap and hands the server
 * the fd; DRI3Extension.pixmapFromFd mmaps it and
 * PresentExtension.presentToContent copies the whole image out of that mapping
 * once a frame. Sampled on device (Requiem, guest stack, 1280x720):
 *
 *     Present copyArea x6720 mean=19114us max=385490us last=69539us 1280x720
 *
 * 19.1 ms for a 3.5 MB frame is about 154 MB/s. A cached memcpy on this SoC
 * runs at several GB/s, so the mapping is not cached — and that is the whole
 * justification for this file rather than an accident of it. An uncached read
 * is *latency*-bound, not bandwidth-bound: the core stalls waiting for each
 * line rather than saturating a bus. Latency-bound misses overlap, so several
 * cores each stalling on their own band cost about what one core stalling on
 * one band costs, and the split is expected to scale close to linearly. On a
 * cached buffer, where the single thread already saturates memory, it would
 * buy nothing.
 *
 * **Expected, not measured.** The commit that added this file could not run
 * the guest stack on a device. What is measured is the 19.1 ms above and the
 * 154 MB/s it implies. The post-split mean is unmeasured; the instrumentation
 * in PresentExtension.presentToContent that produced the line above is
 * deliberately still there to produce its successor.
 *
 * **Why C and a pool, and not Java or a thread per frame.** Workers must never
 * see a JNIEnv, so the calling thread resolves both direct-buffer addresses
 * once (drawable.c) and the workers are handed raw pointers — there is then no
 * AttachCurrentThread, no local-reference budget and no JNI transition on any
 * band. And the copy runs at up to 60 Hz: pthread_create per frame is a clone()
 * and a stack mmap per band per frame, which is a cost of the same order as the
 * win. So the pool is created once, on first use, and never torn down — the X
 * server outlives everything that would tear it down.
 *
 * **The calling thread takes a band itself** and takes the *smallest* one (the
 * band loop hands the +1 rows of an uneven split to the workers), because it is
 * also the thread that pays the wake-up and the join.
 *
 * **Below COPY_POOL_MIN_PAYLOAD it does not use the pool at all.** Plain X
 * CopyArea requests land in the same function as the present copy and are
 * usually a few hundred bytes — a cursor, a menu, a title bar. Waking three
 * threads for those costs more than the copy. 256 KB is a judgement, not a
 * measurement: it is roughly where a condvar broadcast and a join (single-digit
 * microseconds) stop being visible next to the copy even at the *cached* rate
 * of several GB/s, which is the conservative end since the copy this exists for
 * is 20x slower than that.
 *
 * **Concurrent callers fall back rather than queue.** Client threads are
 * per-connection (xconnector_epoll.c), so two copies can overlap. The second
 * one takes the single-threaded path instead of waiting for the pool: waiting
 * would make it strictly slower than it is today, and the pool state is a
 * single set of band slots. It is a trylock and never a lock, so a caller can
 * never block on another caller — which matters because callers hold Java
 * monitors across this (Drawable.copyArea, and WINDOW_MANAGER above it).
 */
#include "copy_pool.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <android/log.h>

/* Shares PresentExtension's tag, because the one line this file logs is read
 * next to that extension's `Present copyArea` line or not at all. */
#define COPY_POOL_TAG "VesselXProto"

/*
 * Total participants = the calling thread + (participants - 1) workers.
 *
 * 4 on the device this was written for, an 8-core SM8845. Not 8: the DRI3
 * sessions that produced the 19 ms line measured host CPU at 26.8% mean with
 * the guest game and FEX already resident, so there is headroom but it is not
 * the whole machine, and taking every core for a 60 Hz memcpy would take it
 * from the thing producing the frames. Clamped down by the online core count so
 * a small device does not oversubscribe itself.
 *
 * Overridable on device with no rebuild:
 *
 *     adb shell setprop debug.vessel.copy_threads 6
 *
 * read once, when the pool is created — an override set after the first present
 * has no effect until the process restarts. A property and not an environment
 * variable because the app's own environment comes from the zygote and nothing
 * on the device can change it; the environment Vessel does build is the guest's,
 * and this code is not in it.
 */
#define COPY_POOL_PROPERTY "debug.vessel.copy_threads"
#define COPY_POOL_DEFAULT_PARTICIPANTS 4
#define COPY_POOL_MAX_PARTICIPANTS 16
#define COPY_POOL_MIN_PAYLOAD (256 * 1024)

typedef struct {
    uint8_t *dst;
    const uint8_t *src;
    int dstStride;
    int srcStride;
    int rowBytes;
    int rows;
} CopyBand;

typedef struct {
    CopyBand band;
    int hasWork;
} CopyPoolSlot;

static pthread_once_t poolOnce = PTHREAD_ONCE_INIT;

/* Guards the slots and the pending count, and nothing else. Nothing acquires
 * another lock while holding it and no worker ever touches Java, so it cannot
 * participate in a cycle with any monitor the caller holds. */
static pthread_mutex_t poolMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t poolWork = PTHREAD_COND_INITIALIZER;
static pthread_cond_t poolDone = PTHREAD_COND_INITIALIZER;

/* Held for the duration of one parallel copy, and only ever via trylock. */
static pthread_mutex_t poolInUse = PTHREAD_MUTEX_INITIALIZER;

static CopyPoolSlot poolSlots[COPY_POOL_MAX_PARTICIPANTS - 1];
static int poolWorkers = 0;
static int poolPending = 0;
static int poolNice = 0;

/* One band. The contiguity test is the one drawable.c applied to the whole
 * rectangle, and it holds per band for exactly the same reason: bands are row
 * ranges, so if the rows of the region are contiguous the rows of a band are
 * too. Preserving it per band matters — degrading a matched-stride 720p frame
 * to 720 memcpy calls would give back part of what the split wins. */
static void copyBand(const CopyBand *band) {
    uint8_t *dst;
    const uint8_t *src;
    int y;

    if (band->rowBytes == band->srcStride && band->rowBytes == band->dstStride) {
        memcpy(band->dst, band->src, (size_t)band->rowBytes * (size_t)band->rows);
        return;
    }

    dst = band->dst;
    src = band->src;
    for (y = 0; y < band->rows; y++) {
        memcpy(dst, src, (size_t)band->rowBytes);
        src += band->srcStride;
        dst += band->dstStride;
    }
}

/* Never returns: the pool has no teardown, so there is no quit state to test
 * for. Sleeps on a condvar rather than spinning — at 60 Hz these threads are
 * idle for most of every frame and a spin would burn the cores the guest needs. */
static void *copyPoolWorker(void *arg) {
    CopyPoolSlot *slot = (CopyPoolSlot *)arg;
    char name[16];

    snprintf(name, sizeof(name), "xcopy%d", (int)(slot - poolSlots));
    pthread_setname_np(pthread_self(), name);

    /* Match the thread that created the pool, which is an X request thread.
     * Workers left at the default nice would be scheduled behind the guest's
     * own threads while the request thread that is waiting on them is not, and
     * the join would then run at the speed of the slowest band. */
    setpriority(PRIO_PROCESS, 0, poolNice);

    pthread_mutex_lock(&poolMutex);
    for (;;) {
        CopyBand band;

        while (!slot->hasWork) pthread_cond_wait(&poolWork, &poolMutex);
        band = slot->band;
        slot->hasWork = 0;
        pthread_mutex_unlock(&poolMutex);

        copyBand(&band);

        pthread_mutex_lock(&poolMutex);
        if (--poolPending == 0) pthread_cond_signal(&poolDone);
    }
}

static int copyPoolParticipants(void) {
    char value[PROP_VALUE_MAX];
    int participants;
    long online;

    if (__system_property_get(COPY_POOL_PROPERTY, value) > 0) {
        /* strtol answers 0 for anything unparseable, which the test rejects and
         * so falls through to the derived default. An explicit override is not
         * clamped by the core count — being able to ask for more participants
         * than cores is the point of being able to ask at all. */
        long requested = strtol(value, NULL, 10);
        if (requested >= 1) {
            return requested > COPY_POOL_MAX_PARTICIPANTS ? COPY_POOL_MAX_PARTICIPANTS
                                                          : (int)requested;
        }
    }

    participants = COPY_POOL_DEFAULT_PARTICIPANTS;
    online = sysconf(_SC_NPROCESSORS_ONLN);
    if (online > 0 && online < participants) participants = (int)online;
    return participants;
}

static void copyPoolInit(void) {
    int participants = copyPoolParticipants();
    int i;

    /* getpriority answers -1 both for "nice -1" and for failure, so errno is
     * the only way to tell them apart. Set before any worker starts, because
     * the worker reads it. */
    errno = 0;
    poolNice = getpriority(PRIO_PROCESS, 0);
    if (errno != 0) poolNice = 0;

    for (i = 0; i < participants - 1; i++) {
        pthread_t thread;
        if (pthread_create(&thread, NULL, copyPoolWorker, &poolSlots[i]) != 0) break;
        pthread_detach(thread);
        poolWorkers++;
    }

    /* Once per process. Worth a line: whether the pool got the width it asked
     * for is otherwise invisible, and it is the first thing to check when a
     * `Present copyArea` mean does not move. */
    __android_log_print(ANDROID_LOG_DEBUG, COPY_POOL_TAG,
                        "copy pool: %d participants (caller + %d workers), nice %d, %ld cores online",
                        poolWorkers + 1, poolWorkers, poolNice, sysconf(_SC_NPROCESSORS_ONLN));
}

void copyPoolCopyRows(uint8_t *dst, int dstStride, const uint8_t *src, int srcStride,
                      int rowBytes, int rows) {
    CopyBand whole;
    int bands, base, extra, row, i;

    whole.dst = dst;
    whole.src = src;
    whole.dstStride = dstStride;
    whole.srcStride = srcStride;
    whole.rowBytes = rowBytes;
    whole.rows = rows;

    /* rows <= 1 also covers the zero and negative heights the callers in
     * Drawable.java can produce; the payload product is 64-bit so a large
     * rectangle cannot wrap its way under the threshold. */
    if (rows <= 1 || (int64_t)rowBytes * (int64_t)rows < COPY_POOL_MIN_PAYLOAD) {
        copyBand(&whole);
        return;
    }

    pthread_once(&poolOnce, copyPoolInit);

    /* poolWorkers is written only inside copyPoolInit, so pthread_once orders
     * this read after that write. */
    if (poolWorkers == 0 || pthread_mutex_trylock(&poolInUse) != 0) {
        copyBand(&whole);
        return;
    }

    bands = poolWorkers + 1;
    if (bands > rows) bands = rows;
    base = rows / bands;
    extra = rows % bands;
    row = 0;

    pthread_mutex_lock(&poolMutex);
    poolPending = bands - 1;
    for (i = 0; i < bands - 1; i++) {
        int n = base + (i < extra ? 1 : 0);
        poolSlots[i].band = whole;
        poolSlots[i].band.dst = dst + (size_t)row * (size_t)dstStride;
        poolSlots[i].band.src = src + (size_t)row * (size_t)srcStride;
        poolSlots[i].band.rows = n;
        poolSlots[i].hasWork = 1;
        row += n;
    }
    pthread_cond_broadcast(&poolWork);
    pthread_mutex_unlock(&poolMutex);

    /* The remainder, which is `base` rows — the smallest band, since the loop
     * above gave every +1 row away. `bands <= rows` guarantees base >= 1, so
     * every band including this one is non-empty and the rows are partitioned
     * exactly: base*(bands-1) + extra handed out, base kept. */
    whole.dst = dst + (size_t)row * (size_t)dstStride;
    whole.src = src + (size_t)row * (size_t)srcStride;
    whole.rows = rows - row;
    copyBand(&whole);

    pthread_mutex_lock(&poolMutex);
    while (poolPending > 0) pthread_cond_wait(&poolDone, &poolMutex);
    pthread_mutex_unlock(&poolMutex);

    /* Unlocking poolMutex after the last worker signalled orders every band's
     * writes before this return, which is what lets the caller treat the copy
     * as finished the instant the JNI call comes back. */
    pthread_mutex_unlock(&poolInUse);
}
