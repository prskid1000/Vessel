/*
 * VESSEL: the server half of xshmfence, which upstream Winlator has no need of
 * because it implements neither DRI3 FenceFromFD nor SYNC fences over an fd.
 *
 * This file is not a port of libxshmfence — it is a re-implementation of the
 * three operations an X *server* performs on a fence page, against the ABI
 * libxshmfence's futex backend defines. The layout was read out of
 * libxshmfence 1.3.3 (the version `native/pins.env` pins and
 * `build/x11-sysroot.sh` builds), not guessed, because getting it wrong is a
 * memory-corruption bug in another process rather than a logic bug here:
 *
 *     src/xshmfence_futex.h:  struct xshmfence { int32_t v; };
 *     src/xshmfence_alloc.c:  ftruncate(fd, sizeof (struct xshmfence));
 *                             mmap(NULL, sizeof (struct xshmfence), ...)
 *
 * So the object is **one 32-bit word at offset 0** and the file is four bytes
 * long. The mapping is still a whole page — which is exactly how a refused
 * FenceFromFD came to take the server down with SIGBUS one page into a
 * 3686400-byte copy, see DRI3Extension.
 *
 * The word is a three-state, not a boolean, and that is the part worth reading
 * before touching anything here (`src/xshmfence_futex.c`):
 *
 *      0   untriggered, and nobody is blocked on it
 *     -1   untriggered, and a waiter is inside FUTEX_WAIT expecting -1
 *      1   triggered
 *
 *     trigger:  if (CAS(v, 0, 1) == -1) { store(v, 1); futex_wake(v); }
 *     await:    while (CAS(v, 0, -1) != 1) futex_wait(v, -1);
 *     query:    load(v) == 1
 *     reset:    CAS(v, 1, 0)
 *
 * Two consequences the Java side depends on:
 *
 *   - **The page is the state.** Mesa calls `xshmfence_reset()` on its own copy
 *     immediately before each `PresentPixmap` and never tells the server, so a
 *     boolean kept on this side would be stale from the first frame. Anything
 *     that wants to know whether a fence is triggered has to read the word.
 *   - **The wake is not optional.** Storing 1 without FUTEX_WAKE is correct
 *     only for a waiter that has not yet entered the syscall; one that has is
 *     asleep forever. Which is why this is C and not a `ByteBuffer.putInt` —
 *     there is no futex from Java, and `VarHandle`'s compare-and-set is API 33
 *     against this module's `minSdk 31`.
 *
 * FUTEX_WAKE is used and not FUTEX_WAKE_PRIVATE: the waiter is in another
 * process on a MAP_SHARED page and uses the shared form, and the two do not
 * match each other.
 */
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <linux/futex.h>

/* The whole object, as libxshmfence defines it. Named rather than written as
 * `sizeof(int32_t)` at every call site so that a future libxshmfence that grows
 * the struct has one place to fail loudly. */
#define XSHMFENCE_SIZE ((size_t)sizeof(int32_t))

static long futex_wake_all(int32_t *addr) {
    return syscall(SYS_futex, addr, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

static int32_t *fence_of(JNIEnv *env, jobject page) {
    if (page == NULL) return NULL;
    return (int32_t *)(*env)->GetDirectBufferAddress(env, page);
}

JNIEXPORT jobject JNICALL
Java_com_winlator_xserver_XShmFence_map(JNIEnv *env, jclass cls, jint fd) {
    void *addr;
    if (fd < 0) return NULL;
    addr = mmap(NULL, XSHMFENCE_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) return NULL;
    return (*env)->NewDirectByteBuffer(env, addr, (jlong)XSHMFENCE_SIZE);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_XShmFence_unmap(JNIEnv *env, jclass cls, jobject page) {
    int32_t *fence = fence_of(env, page);
    if (fence != NULL) munmap(fence, XSHMFENCE_SIZE);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_XShmFence_trigger(JNIEnv *env, jclass cls, jobject page) {
    int32_t *fence = fence_of(env, page);
    int32_t previous = 0;
    if (fence == NULL) return;

    /* __atomic_compare_exchange_n writes the observed value back into
     * `previous` when it fails, which is what __sync_val_compare_and_swap
     * returns in libxshmfence's own trigger. Both spellings mean: swap 0 for 1
     * and wake nobody, because a value of 0 says nobody is waiting. */
    if (!__atomic_compare_exchange_n(fence, &previous, 1, 0,
                                     __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {
        if (previous == -1) {
            __atomic_store_n(fence, 1, __ATOMIC_SEQ_CST);
            futex_wake_all(fence);
        }
        /* previous == 1: already triggered, and triggering twice is a no-op
         * rather than an error — SyncSetTriggered on a triggered fence is
         * defined that way, and Present sends an idle notify per present. */
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_XShmFence_reset(JNIEnv *env, jclass cls, jobject page) {
    int32_t *fence = fence_of(env, page);
    int32_t triggered = 1;
    if (fence == NULL) return;
    /* Only 1 -> 0. A fence sitting at -1 has a waiter and must not be moved. */
    __atomic_compare_exchange_n(fence, &triggered, 0, 0,
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xserver_XShmFence_query(JNIEnv *env, jclass cls, jobject page) {
    int32_t *fence = fence_of(env, page);
    if (fence == NULL) return JNI_FALSE;
    return __atomic_load_n(fence, __ATOMIC_SEQ_CST) == 1 ? JNI_TRUE : JNI_FALSE;
}
