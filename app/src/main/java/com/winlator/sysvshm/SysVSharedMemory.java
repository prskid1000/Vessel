package com.winlator.sysvshm;

import android.util.SparseArray;

import com.winlator.xconnector.XConnectorEpoll;

import java.nio.ByteBuffer;

/**
 * Emulates System V shared memory for the guest, over a unix socket, out of
 * file descriptors. Nothing here calls shmget(2) or shmat(2).
 *
 * <p>VESSEL: this is the answer to "bionic has no SysV shm, so what happens to
 * MIT-SHM?" — and the answer is that MIT-SHM stays on, unmodified, because the
 * design was never using kernel SysV shm on the Android side to begin with.
 *
 * <p>Three facts, in the order they matter:
 *
 * <ol>
 * <li>bionic <em>does</em> declare and export shmget/shmat/shmdt/shmctl from
 *     API 26 — see the NDK's {@code sys/shm.h}, which says in its own header
 *     comment "Not useful on Android because it's disallowed by SELinux". They
 *     link. They fail at runtime. So the failure mode is EACCES at the first
 *     call, not a missing symbol at build time, which is worth knowing because
 *     it means nothing warns you.
 * <li>Even with SELinux out of the way it would not help. A SysV shmid names a
 *     segment in the kernel's IPC namespace; this server is Java and can only
 *     map a file descriptor. There is no route from "the client made segment
 *     N" to "the server has those pages" that does not involve passing an fd.
 *     Any MIT-SHM implementation living in an Android app is therefore
 *     fd-passing, whatever the client thinks it is doing.
 * <li>So the shim already exists and is this class. {@link #get} allocates an
 *     ashmem region ({@code ASharedMemory_create}, a plain NDK call) or a
 *     memfd, hands the id back over the socket, and {@link #getFd} passes the
 *     descriptor itself as an SCM_RIGHTS ancillary message. Winlator needs a
 *     glibc patch for the <em>client</em> half of this, redirecting the guest's
 *     shmget to the socket; that patch is glibc-specific and Vessel does not
 *     carry it.
 * </ol>
 *
 * <p>What is still missing is that client half. Wine's winex11 calls shmget()
 * directly in {@code create_shm_image}; on bionic it returns -1, Wine treats
 * that as "no XShm available" and falls back to XPutImage, which is correct and
 * costs one copy per damaged region. Making it fast means teaching winex11 to
 * ask this socket instead — a Wine patch, noted in docs/ARCHITECTURE.md, not
 * something the display backend can do from its side.
 */
public class SysVSharedMemory {
    private final SparseArray<SHMemory> shmemories = new SparseArray<>();
    private int maxSHMemoryId = 0;

    static {
        System.loadLibrary("winlator");
    }

    private static class SHMemory {
        private int fd;
        private long size;
        private ByteBuffer data;
    }

    public int getFd(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            return shmemory != null ? shmemory.fd : -1;
        }
    }

    public int get(long size) {
        synchronized (shmemories) {
            int index = shmemories.size();
            int fd = ashmemCreateRegion(index, size);
            // VESSEL: memfd fallback. ASharedMemory_create is the documented
            // route and is itself memfd-backed on Android 11 and later, but it
            // goes through libandroid and can fail where a raw memfd_create
            // would not. Both produce an ordinary fd, so the rest of the path
            // does not care which one it got.
            if (fd < 0) fd = createMemoryFd("sysvshm-" + index, (int)size);
            if (fd < 0) return -1;

            SHMemory shmemory = new SHMemory();
            int id = ++maxSHMemoryId;
            shmemory.fd = fd;
            shmemory.size = size;
            shmemories.put(id, shmemory);
            return id;
        }
    }

    public void delete(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory != null) {
                if (shmemory.fd != -1) {
                    XConnectorEpoll.closeFd(shmemory.fd);
                    shmemory.fd = -1;
                }
                shmemories.remove(shmid);
            }
        }
    }

    public void deleteAll() {
        synchronized (shmemories) {
            for (int i = shmemories.size() - 1; i >= 0; i--) delete(shmemories.keyAt(i));
        }
    }

    public ByteBuffer attach(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory != null) {
                if (shmemory.data == null) shmemory.data = mapSHMSegment(shmemory.fd, shmemory.size, 0, true);
                return shmemory.data;
            }
            else return null;
        }
    }

    public void detach(ByteBuffer data) {
        synchronized (shmemories) {
            for (int i = 0; i < shmemories.size(); i++) {
                SHMemory shmemory = shmemories.valueAt(i);
                if (shmemory.data == data) {
                    if (shmemory.data != null) {
                        unmapSHMSegment(shmemory.data, shmemory.size);
                        shmemory.data = null;
                    }
                    break;
                }
            }
        }
    }

    public static native int createMemoryFd(String name, int size);

    private static native int ashmemCreateRegion(int index, long size);

    public static native ByteBuffer mapSHMSegment(int fd, long size, int offset, boolean readonly);

    public static native void unmapSHMSegment(ByteBuffer data, long size);

    /**
     * VESSEL: {@code fcntl(fd, F_DUPFD_CLOEXEC)}. See the native side
     * ({@code sysvshared_memory.c}) — a mapping outlives the request that
     * carried its descriptor, but {@link #dmaBufSyncRead} needs a descriptor,
     * so the DRI3 path keeps one of its own.
     *
     * @return the new descriptor, or -1.
     */
    public static native int dupFd(int fd);

    /**
     * VESSEL: {@code DMA_BUF_IOCTL_SYNC} with {@code DMA_BUF_SYNC_READ} and
     * either {@code _START} or {@code _END}.
     *
     * <p>Read the native comment before using this. The one-line version:
     * it is required by the dma-buf userspace ABI around any CPU access, it
     * buys <em>coherency</em> and not cacheability, and a {@code false} return
     * most often means "this fd is not a dma-buf" rather than "the sync
     * failed".
     *
     * @param start true for {@code START} (before the read), false for
     *              {@code END} (after it).
     * @return whether the kernel performed the sync.
     */
    public static native boolean dmaBufSyncRead(int fd, boolean start);
}
