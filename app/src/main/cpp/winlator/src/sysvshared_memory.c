#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <fcntl.h>
#include <stdbool.h>
#include <pthread.h>
#include <sys/ipc.h>
#include <sys/syscall.h>
#include <jni.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include <android/sharedmem.h>

#define __u32 uint32_t
#include <linux/ashmem.h>

// VESSEL: DMA_BUF_IOCTL_SYNC, for the CPU side of a DRI3 pixmap.
//
// <linux/dma-buf.h> *is* in the NDK sysroot this project builds against
// (ndkVersion 27.0.12077973, app/build.gradle.kts:167 —
// toolchains/llvm/prebuilt/<host>/sysroot/usr/include/linux/dma-buf.h), and it
// carries struct dma_buf_sync, the DMA_BUF_SYNC_* flags and DMA_BUF_IOCTL_SYNC
// itself. So there is nothing to declare locally and no reason to; a hand-rolled
// copy of a uapi struct is one kernel revision away from being wrong.
#include <linux/dma-buf.h>

int ashmemCreateRegion(const char* name, int64_t size) {
#if __ANDROID_API__ >= 26
    int fd = ASharedMemory_create(name, size);
    if (fd < 0) return -1;
    return fd;
#else
    int fd = open("/dev/ashmem", O_RDWR);
    if (fd < 0) return -1;

    char nameBuffer[ASHMEM_NAME_LEN] = {0};
    strncpy(nameBuffer, name, sizeof(nameBuffer));
    nameBuffer[sizeof(nameBuffer) - 1] = 0;

    int ret = ioctl(fd, ASHMEM_SET_NAME, nameBuffer);
    if (ret < 0) goto error;

    ret = ioctl(fd, ASHMEM_SET_SIZE, size);
    if (ret < 0) goto error;

    return fd;
error:
    close(fd);
    return -1;
#endif
}

static int memfd_create(const char *name, unsigned int flags) {
#ifdef __NR_memfd_create
    return syscall(__NR_memfd_create, name, flags);
#else
    return -1;
#endif
}

int createMemoryFd(const char* name, int64_t size) {
    int fd = memfd_create(name, MFD_ALLOW_SEALING);
    if (fd < 0) return -1;

    int res = ftruncate(fd, size);
    if (res < 0) {
        close(fd);
        return -1;
    }

    return fd;
}

JNIEXPORT jint JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_ashmemCreateRegion(JNIEnv *env, jobject obj, jint index,
                                                              jlong size) {
    char name[32];
    sprintf(name, "sysvshm-%d", index);
    return ashmemCreateRegion(name, size);
}

JNIEXPORT jobject JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_mapSHMSegment(JNIEnv *env, jobject obj, jint fd, jlong size, jint offset, jboolean readonly) {
    char *data = mmap(NULL, size, readonly ? PROT_READ : PROT_WRITE | PROT_READ, MAP_SHARED, fd, offset);
    if (data == MAP_FAILED) return NULL;
    return (*env)->NewDirectByteBuffer(env, data, size);
}

JNIEXPORT void JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_unmapSHMSegment(JNIEnv *env, jobject obj, jobject data,
                                                           jlong size) {
    char *dataAddr = (*env)->GetDirectBufferAddress(env, data);
    munmap(dataAddr, size);
}

/**
 * VESSEL: duplicate a descriptor, so a mapping can outlive the request that
 * carried it *and still be syncable*.
 *
 * The DRI3 requests close the client's fd the moment the mmap has been taken
 * (DRI3Extension.java:148, :213 — `finally { XConnectorEpoll.closeFd(fd); }`),
 * because the mapping is all the server needed. DMA_BUF_IOCTL_SYNC is issued on
 * the descriptor, not on the address, so bracketing a CPU read means keeping one
 * open for the life of the drawable. This is that descriptor.
 */
JNIEXPORT jint JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_dupFd(JNIEnv *env, jclass obj, jint fd) {
    if (fd < 0) return -1;
    int newFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    return newFd < 0 ? -1 : newFd;
}

/**
 * VESSEL: the dma-buf CPU-access bracket the DRI3 present path was missing
 * entirely.
 *
 * The dma-buf userspace ABI is not "mmap and read". A CPU accessor must wrap
 * every access in DMA_BUF_IOCTL_SYNC — START before, END after — and that is
 * what drives the exporter's .begin_cpu_access / .end_cpu_access dma_buf_ops.
 * Those ops are where cache maintenance happens: on a cached mapping, a
 * begin/READ invalidates so the CPU sees writes the device made, and without it
 * the CPU can legally read stale lines. The server read the client's swapchain
 * image ~60 times a second with no bracket at all, which is a correctness bug
 * that presents as intermittent stale or half-stale frames — the exact shape of
 * damage that gets blamed on a GPU driver.
 *
 * <b>What this does not do, so nobody expects the wrong thing from it.</b> It
 * does not change how the buffer is mapped. The mapping's memory type is fixed
 * at mmap(2) time by the exporter's .mmap op setting vma->vm_page_prot; the sync
 * ioctl never touches the vma and cannot convert a write-combine mapping into a
 * cached one. It buys coherency, not cacheability. If the copy in
 * PresentExtension is slow because reads are uncached, this will not make it
 * fast — and may cost a little more, since an invalidate over a few megabytes is
 * not free.
 *
 * `start` selects START vs END; the direction is always READ, because the server
 * only ever reads a client-supplied buffer (Drawable.copyArea's source). Do not
 * widen this to RW on the assumption that it is harmless — SYNC_WRITE makes the
 * exporter clean/flush for a device read that is not going to happen.
 *
 * @return JNI_TRUE if the kernel performed the sync, JNI_FALSE otherwise. False
 *         is not necessarily an error: ENOTTY means the fd is not a dma-buf at
 *         all (ashmem and memfd both land here, and MIT-SHM segments are
 *         memfds), and the caller latches that to stop asking.
 */
JNIEXPORT jboolean JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_dmaBufSyncRead(JNIEnv *env, jclass obj, jint fd,
                                                          jboolean start) {
    if (fd < 0) return JNI_FALSE;

    struct dma_buf_sync sync = {
        .flags = DMA_BUF_SYNC_READ | (start ? DMA_BUF_SYNC_START : DMA_BUF_SYNC_END)
    };

    // EINTR/EAGAIN are retried rather than treated as failure: the exporter's
    // begin_cpu_access can take an interruptible mutex, and giving up there
    // would silently skip the cache maintenance we came for. Mesa's own
    // sync_dma_buf loops the same way.
    int ret;
    do {
        ret = ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    } while (ret < 0 && (errno == EINTR || errno == EAGAIN));

    if (ret < 0) {
        // Logged once per call site by the caller, not here: this runs on the
        // present path and a line a frame is itself a cost.
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_createMemoryFd(JNIEnv *env, jclass obj, jstring name,
                                                          jint size) {
    const char *namePtr = (*env)->GetStringUTFChars(env, name, 0);

    int fd = createMemoryFd(namePtr, size);
    (*env)->ReleaseStringUTFChars(env, name, namePtr);
    if (fd < 0) return -1;

    return fd;
}