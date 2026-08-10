package com.winlator.xserver;

import java.nio.ByteBuffer;

/**
 * VESSEL: the server side of an xshmfence, which upstream has no use for.
 *
 * <p>A DRI3 {@code FenceFromFD} hands the server a file descriptor holding one
 * shared 32-bit word — libxshmfence's whole object. Triggering that fence means
 * writing the word and waking whatever is blocked on it in the client, and
 * neither of those is expressible in Java: there is no futex, and
 * {@code VarHandle}'s compare-and-set is API 33 against this module's
 * {@code minSdk 31}. So the four operations live in
 * {@code cpp/winlator/src/xshmfence.c} and this class is the handle.
 *
 * <p><b>The page is the state, not a mirror of it.</b> Mesa calls
 * {@code xshmfence_reset()} on its own mapping immediately before every
 * {@code PresentPixmap} and never tells the server
 * ({@code wsi_common_x11.c:1804}), so a boolean kept on this side goes stale on
 * the first frame. {@link SyncExtension} therefore reads {@link #query} for any
 * fence that has a page, and only falls back to its own boolean for a fence
 * created by plain SYNC {@code CreateFence}.
 *
 * <p>The layout — one {@code int32_t} at offset 0, three-valued rather than
 * boolean — is documented at length in the C file, where it can sit next to the
 * code that depends on it. It was read out of libxshmfence 1.3.3, the version
 * {@code native/pins.env} pins, rather than assumed.
 */
public final class XShmFence {
    /**
     * The size of the mapping, in bytes: {@code sizeof(struct xshmfence)}.
     *
     * <p>Four, not 4096. The fd is {@code ftruncate}d to exactly this by
     * {@code xshmfence_alloc_shm()}; the *mapping* rounds up to a page, which is
     * the only reason a {@code PixmapFromBuffer} handed a stray fence fd faulted
     * one page in rather than immediately.
     */
    public static final int SIZE = 4;

    static {
        System.loadLibrary("winlator");
    }

    private XShmFence() {}

    /**
     * Maps a fence fd shared and writable.
     *
     * <p>The caller keeps ownership of {@code fd} and should close it — the
     * mapping outlives it, exactly as in {@code DRI3Extension.pixmapFromFd}.
     *
     * @return a direct buffer of {@link #SIZE} bytes, or null if the mmap failed
     */
    public static native ByteBuffer map(int fd);

    /** Unmaps a buffer returned by {@link #map}. Null is ignored. */
    public static native void unmap(ByteBuffer page);

    /**
     * Sets the fence triggered and wakes every waiter.
     *
     * <p>Idempotent: triggering a triggered fence is defined as a no-op, which
     * matters because Present sends one idle notify per present.
     */
    public static native void trigger(ByteBuffer page);

    /** Sets a triggered fence back to untriggered. A fence with a waiter on it
     * is left alone, which is what {@code xshmfence_reset} does. */
    public static native void reset(ByteBuffer page);

    /** Whether the fence is currently triggered. */
    public static native boolean query(ByteBuffer page);
}
