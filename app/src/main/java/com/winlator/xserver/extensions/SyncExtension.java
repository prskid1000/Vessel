package com.winlator.xserver.extensions;

import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.XShmFence;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class SyncExtension extends Extension {
    private final SparseBooleanArray fences = new SparseBooleanArray();

    /**
     * VESSEL: the per-fence state a {@link SparseBooleanArray} cannot hold.
     *
     * <p>Two things, and each of them is a defect on its own if it is missing.
     *
     * <p><b>The page.</b> A fence created by DRI3 {@code FenceFromFD} is backed
     * by a word of memory shared with the client, and <em>that word is the
     * state</em>: Mesa calls {@code xshmfence_reset()} on its own mapping before
     * every {@code PresentPixmap} and never sends a request saying so
     * ({@code wsi_common_x11.c:1804}), so the boolean beside it is stale from
     * the first frame. Every read and write below therefore goes to the page
     * when there is one, and to {@code fences} only when there is not — which is
     * every fence a plain SYNC {@code CreateFence} makes, so upstream's
     * behaviour for those is unchanged.
     *
     * <p><b>The owner.</b> Upstream never removes a fence, so a client that
     * disconnects leaves its ids behind, and because {@code ResourceIDs.free()}
     * hands the same id base to the next client the next run collides on its own
     * predecessor's fence. That is the same defect the DRI3 pixmaps had; see
     * {@link #freeClientResources}.
     */
    private static class FenceInfo {
        private final XClient owner;
        private ByteBuffer page;

        private FenceInfo(XClient owner, ByteBuffer page) {
            this.owner = owner;
            this.page = page;
        }
    }

    /** VESSEL: see {@link FenceInfo}. Keyed the same as {@link #fences}. */
    private final SparseArray<FenceInfo> fenceInfos = new SparseArray<>();

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    public SyncExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) putTriggered(id, true);
        }
    }

    /**
     * VESSEL: registers a fence backed by a page the client mapped and passed
     * over {@code SCM_RIGHTS}. Called by {@link DRI3Extension}'s
     * {@code FenceFromFD}, which is a DRI3 1.0 request this server advertises
     * and used not to implement.
     *
     * <p>The fd is mapped but <em>not</em> adopted — the caller closes it, as
     * {@code DRI3Extension.pixmapFromFd} does for a dma-buf. An mmap keeps the
     * object alive on its own.
     *
     * @throws BadIdChoice if the id is already a fence
     * @throws BadAlloc if the fd could not be mapped
     */
    public void createFenceFromFd(XClient client, int id, int fd, boolean initiallyTriggered)
            throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            ByteBuffer page = XShmFence.map(fd);
            if (page == null) throw new BadAlloc();

            fences.put(id, initiallyTriggered);
            fenceInfos.put(id, new FenceInfo(client, page));
            // The request's own flag, applied to the page rather than only to
            // the boolean — this is what miSyncShmScreenCreateFence does, and
            // the client is entitled to read the word straight back. Mesa asks
            // for false and then triggers it itself one line later.
            if (initiallyTriggered) XShmFence.trigger(page);
        }
    }

    /**
     * VESSEL: releases every fence a disconnecting client owned.
     *
     * <p>Without this the ids leak for the life of the session and the next
     * client — which gets the same resource-id base back out of
     * {@code ResourceIDs} — fails its first {@code FenceFromFD} with
     * {@code BadIdChoice}. Runs alternate pass and fail, which is exactly what a
     * second {@code --wsi dri3} run against a live session did.
     */
    @Override
    public void freeClientResources(XClient client) {
        synchronized (fences) {
            // Collected first and removed after, rather than deleting inside the
            // walk: SparseArray.delete() only tombstones, and the next keyAt()
            // compacts. Backwards iteration happens to survive that, and
            // depending on it is not worth the reader's time.
            int[] owned = new int[fenceInfos.size()];
            int count = 0;
            for (int i = 0; i < fenceInfos.size(); i++) {
                if (fenceInfos.valueAt(i).owner == client) owned[count++] = fenceInfos.keyAt(i);
            }
            for (int i = 0; i < count; i++) {
                unmapFence(owned[i]);
                fences.delete(owned[i]);
            }
            // VESSEL: a fence that has gone away is not a fence that will never
            // trigger — it is a BadFence, and awaitFence re-checks membership on
            // every wakeup so that it reports one instead of waiting out its
            // timeout. Same reasoning in destroyFence.
            fences.notifyAll();
        }
    }

    /** VESSEL: the page is authoritative when there is one. See {@link FenceInfo}. */
    private boolean isTriggered(int id) {
        FenceInfo info = fenceInfos.get(id);
        if (info != null && info.page != null) return XShmFence.query(info.page);
        return fences.get(id);
    }

    /** VESSEL: the write half of {@link #isTriggered}. */
    private void putTriggered(int id, boolean triggered) {
        FenceInfo info = fenceInfos.get(id);
        if (info != null && info.page != null) {
            if (triggered) XShmFence.trigger(info.page);
            else XShmFence.reset(info.page);
        }
        fences.put(id, triggered);
        // VESSEL: wake anything parked in awaitFence. Every caller of this
        // method already holds the monitor (setTriggered, triggerFence,
        // resetFence), which is what makes the notify legal here rather than at
        // each call site. See awaitFence for why the waiter is on this monitor
        // at all instead of spinning on it.
        fences.notifyAll();
    }

    /** VESSEL: drops a fence's page, if it has one. Callers hold {@code fences}. */
    private void unmapFence(int id) {
        FenceInfo info = fenceInfos.get(id);
        if (info == null) return;
        if (info.page != null) {
            XShmFence.unmap(info.page);
            info.page = null;
        }
        fenceInfos.delete(id);
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            inputStream.skip(4);
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);

            fences.put(id, initiallyTriggered);
            // VESSEL: an owner even for a page-less fence, so that this one is
            // reclaimed on disconnect too. See freeClientResources.
            fenceInfos.put(id, new FenceInfo(client, null));
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            putTriggered(id, true); // VESSEL: reaches the shared page too.
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);

            boolean triggered = isTriggered(id); // VESSEL: was fences.get(id).
            if (!triggered) throw new BadMatch();

            putTriggered(id, false);
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            unmapFence(id); // VESSEL: the mapping goes with the fence.
            fences.delete(id);
            fences.notifyAll(); // VESSEL: see freeClientResources.
        }
    }

    /**
     * VESSEL: how long a client thread may sit in {@link #awaitFence} before the
     * server gives up on it, and how often it re-reads the shared pages while it
     * waits.
     *
     * <p><b>Why there is a timeout at all</b>, when X's AwaitFence has none and a
     * real server is entitled to block a client forever: a blocked client here is
     * a blocked <em>OS thread</em>, because {@code XServerDisplay.kt:1175} calls
     * {@code setMultithreadedClients(true)} and {@code xconnector_epoll.c:292}
     * gives every connection its own {@code pollThread}. Tearing a connection
     * down runs {@code XConnectorEpoll_killConnection}, which does
     * {@code requestShutdown(shutdownFd)} then {@code pthread_join(pollThread)}
     * ({@code xconnector_epoll.c:238-243}) — and a thread parked in Java inside
     * {@code handleExistingConnection} never returns to {@code waitForSocketRead}
     * to notice the shutdown fd. An unbounded wait therefore does not merely hang
     * one client, it hangs session teardown on a {@code pthread_join} that can
     * never complete. Five seconds is far past any legitimate fence wait and far
     * short of a user deciding the app is dead.
     *
     * <p><b>Why it polls as well as waiting on the monitor.</b> The monitor
     * notify in {@link #putTriggered} covers every fence this server triggers.
     * It does not cover a page-backed fence the <em>client</em> triggers through
     * its own mapping with {@code xshmfence_trigger}, which writes memory and
     * issues a futex wake that no Java monitor hears. The poll is the safety net
     * for that case only; the notify is what makes the common case immediate.
     */
    private static final long AWAIT_POLL_MILLIS = 4;
    private static final long AWAIT_TIMEOUT_NANOS = 5_000_000_000L;

    /**
     * VESSEL: SYNC {@code AwaitFence}, rewritten because the version it replaces
     * could not terminate.
     *
     * <p><b>The defect.</b> Upstream held {@code synchronized (fences)} across a
     * {@code do { ... Thread.yield(); } while (!anyTriggered)} spin.
     * {@code Thread.yield()} does not release a monitor — only {@code wait()}
     * does — so the waiting thread kept the one lock every possible releaser
     * needs. There are exactly two ways a fence becomes triggered in this server
     * and both take that monitor first: {@link #triggerFence} (a client's
     * {@code TriggerFence}) and {@link #setTriggered} (called from
     * {@code PresentExtension.sendIdleNotify} on the present path). So the loop's
     * exit condition could only ever be satisfied by a fence that was
     * <em>already</em> triggered when the request arrived. Anything else was a
     * hard deadlock: the awaiting client's thread spun at 100% of a core forever,
     * and because {@code fences} is one lock shared by every connection, every
     * other client's SYNC request and every {@code PresentPixmap} carrying an
     * idle fence blocked behind it for the rest of the session.
     *
     * <p><b>What this is not.</b> Nothing in the current stack sends this
     * request — a grep of all of {@code native/} for {@code sync_await_fence},
     * {@code XSyncAwait} and {@code xcb_sync_await} finds no call site in Mesa,
     * Wine, DXVK, vkd3d or FEX. Mesa's X11 WSI waits on its <em>own</em> mapping
     * with {@code xshmfence_await} ({@code wsi_common_x11.c:2253}) and never asks
     * the server to wait for it. So this fix is not the cause of any stutter
     * observed today and must not be reported as one; it is a loaded gun that
     * fires the first time any client uses a request this server advertises.
     *
     * <p><b>The lock discipline now.</b> The request body is drained first, on
     * this connection's own thread, before anything can block — a wait with an
     * unconsumed request in the buffer would leave the stream mis-framed for the
     * rest of the session. Then the monitor is taken, and inside it the loop
     * alternates between checking under the lock and {@code fences.wait(...)},
     * which <em>releases</em> the monitor for the duration of the wait. That is
     * the whole of the fix: the releaser can now get in. Membership is re-checked
     * on every wakeup rather than once at entry, so a fence destroyed while a
     * client waits on it answers {@code BadFence} instead of timing out.
     */
    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        // Drained before the monitor is taken and before anything can block.
        // Counted rather than decremented to zero: upstream's `while (length !=
        // 0) length -= 4` walks past the end of `ids` on any length that is not a
        // multiple of four, which is a client-controlled ArrayIndexOutOfBounds
        // and therefore a dropped connection.
        int length = client.getRemainingRequestLength();
        int count = length / 4;
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) ids[i] = inputStream.readInt();
        inputStream.skip(length - count * 4);

        // AwaitFence over an empty fence list has nothing to wait for and
        // returns. Upstream's do/while evaluated its condition on an
        // `anyTriggered` no loop body had touched, so an empty list was the
        // second way to spin forever.
        if (count == 0) return;

        final long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
        synchronized (fences) {
            while (true) {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    if (isTriggered(id)) return; // VESSEL: reads the page, was fences.get(id).
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) break;
                try {
                    // Never zero, which would mean "wait forever".
                    fences.wait(Math.min(AWAIT_POLL_MILLIS, remainingNanos / 1000000L + 1));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // Deliberately WARN and deliberately outside the monitor. AwaitFence has
        // no reply, so returning here is indistinguishable on the wire from a
        // fence that triggered — which is a lie, and the log line is the only
        // record that it was told. If this ever appears, the fence in question
        // has a triggerer nobody has written yet.
        Log.w(XRequestError.PROTO_TAG, "SYNC AwaitFence gave up after "
                + (AWAIT_TIMEOUT_NANOS / 1000000L) + "ms on " + count
                + " fence(s), first 0x" + Integer.toHexString(ids[0])
                + " — returning as if triggered");
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
