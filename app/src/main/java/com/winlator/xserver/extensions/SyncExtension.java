package com.winlator.xserver.extensions;

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
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    anyTriggered = isTriggered(id); // VESSEL: was fences.get(id).
                    if (anyTriggered) break;
                }

                Thread.yield();
            }
            while (!anyTriggered);
        }
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
