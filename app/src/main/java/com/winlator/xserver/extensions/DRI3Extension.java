package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;

import com.winlator.core.Callback;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DRI3Extension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    /**
     * VESSEL: deliberately left at 1.0, and the reasoning is worth keeping
     * because 1.2 looks like the obvious answer.
     *
     * This tree implements {@code PixmapFromBuffers} (opcode 7), which is a
     * DRI3 1.2 request, and its wire parse is byte-correct for it: 60 bytes of
     * request body — pixmap, window, num_buffers + 3 pad, width, height, four
     * stride/offset pairs, depth, bpp + 2 pad, and a 64-bit modifier. But 1.2
     * is also {@code GetSupportedModifiers} (6) and {@code BuffersFromPixmap}
     * (8), neither of which exists here, and this handler ignores both
     * num_buffers and the modifier and takes exactly one fd. Advertising 1.2
     * would promise three things and deliver one.
     *
     * It would also buy nothing. Mesa's {@code has_dri3_modifiers} is
     * {@code dri3 >= 1.2 && present >= 1.2}; with it false the WSI asks for no
     * modifier list, {@code wsi_configure_native_image} falls back to the
     * scanout flag, Turnip makes a {@code DRM_FORMAT_MOD_LINEAR} image, and
     * {@code x11_image_init} sends the single-fd {@code PixmapFromBuffer} (2)
     * — the request this server implements, and the one a CPU mmap of the
     * dma-buf can actually consume. Measured on that path:
     * {@code result=PASS wsi=dri3 mean_ms=0.532} against 1.8–2.1 ms for the
     * software path.
     *
     * <p>VESSEL: the note that used to end this comment — "the version claimed
     * is already too high in the other direction: {@code FenceFromFD} (4) is a
     * 1.0 request and is not implemented" — is no longer true. It is
     * implemented; see {@link #fenceFromFD}. 1.0 is now an honest claim rather
     * than an overstatement, which is a second reason not to touch the number.
     */
    public static final byte MINOR_VERSION = 0;
    private final Callback<Drawable> onDestroyDrawableListener = (drawable) -> {
        ByteBuffer data = drawable.getData();
        SysVSharedMemory.unmapSHMSegment(data, data.capacity());
    };

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte BUFFER_FROM_PIXMAP = 3;
        private static final byte FENCE_FROM_FD = 4; // VESSEL
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }

    /** VESSEL: resolved lazily, the same way PresentExtension does it — the
     * extensions are built in one array and cannot see each other in their
     * constructors. */
    private SyncExtension syncExtension;

    public DRI3Extension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "DRI3";
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(MINOR_VERSION);
            outputStream.writePad(16);
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);

        // VESSEL: the fd comes off the ancillary queue *before* anything below
        // can throw. XInputStream keeps one queue per connection and
        // getAncillaryFd() pops its head, so a request that returns an error
        // without popping shifts the queue for the rest of the session and
        // every later fd belongs to the wrong request. That is the same defect
        // that refusing FenceFromFD had — see fenceFromFD — and a BadWindow or
        // a BadIdChoice here would have caused it just as surely.
        int fd = inputStream.getAncillaryFd();
        try {
            Window window = xServer.windowManager.getWindow(windowId);
            if (window == null) throw new BadWindow(windowId);

            Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
            if (pixmap != null) throw new BadIdChoice(pixmapId);

            pixmapFromFd(client, pixmapId, width, height, stride, 0, depth, fd, size);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    private void bufferFromPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadPixmap(windowId);

        Drawable content = window.getContent();
        final Texture texture = content.getTexture();

        if (!(texture instanceof GPUImage)) {
            xServer.getRenderer().xServerView.queueEvent(texture::destroy);
            content.setTexture(new GPUImage(content, false));
        }

        GPUImage gpuImage = (GPUImage)content.getTexture();
        short stride = gpuImage.getStride();
        int nativeHandle = gpuImage.getNativeHandle();

        xServer.debugPrint("bufferFromPixmap handle "+nativeHandle+", width "+content.width+", height "+content.height+", stride "+stride);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(stride * content.height * 4);
            outputStream.writeShort(content.width);
            outputStream.writeShort(content.height);
            outputStream.writeShort(stride);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)32);
            outputStream.writePad(12);
            outputStream.setAncillaryFd(nativeHandle);
        }
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        inputStream.skip(4);
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        int stride = inputStream.readInt();
        int offset = inputStream.readInt();
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        inputStream.skip(11);

        // VESSEL: pop before validating — see pixmapFromBuffer.
        int fd = inputStream.getAncillaryFd();
        try {
            Window window = xServer.windowManager.getWindow(windowId);
            if (window == null) throw new BadWindow(windowId);

            Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
            if (pixmap != null) throw new BadIdChoice(pixmapId);

            long size = (long)stride * height;
            pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    /**
     * VESSEL: {@code FenceFromFD}, DRI3 opcode 4 — a **1.0** request, so
     * refusing it made this server non-conformant at the version it advertises.
     *
     * <p>Refusing it was also actively destructive, and that is the part worth
     * keeping. The request arrives with a file descriptor over
     * {@code SCM_RIGHTS}, and an error reply never consumes it:
     * {@code XInputStream} keeps one ancillary-fd queue per connection and
     * {@link XInputStream#getAncillaryFd()} pops its head, so one unconsumed fd
     * shifts the queue for the rest of the session. The next
     * {@code PixmapFromBuffer} was then handed the previous image's 4-byte
     * fence object — mapped as a whole page — in place of its 3686400-byte
     * dma-buf, and the second present took the app down with
     * {@code signal 7 (SIGBUS), code 2 (BUS_ADRERR)} inside
     * {@code Drawable.copyArea}, faulting exactly one page into the source.
     * {@code patches/mesa/0007} is the workaround that stopped Mesa asking;
     * {@code VESSEL_WSI_DRI3_FENCE=1} turns the request back on.
     *
     * <p>Implementing it is two things. The fence has to be <em>mapped and
     * triggered through the client's shared page</em>, which is
     * {@link com.winlator.xserver.XShmFence} and
     * {@link SyncExtension#createFenceFromFd}; and something has to trigger it
     * at the right moment, which {@code PresentExtension.presentPixmap} already
     * did — it calls {@code syncExtension.setTriggered(idleFence)} before it
     * sends {@code PresentIdleNotify}, under the window's render lock and after
     * the copy.
     *
     * <p>The wire body is 12 bytes: {@code drawable}, {@code fence},
     * {@code initially_triggered} and three of padding (dri3proto, and
     * {@code xcb_dri3_fence_from_fd_request_t} field for field).
     */
    private void fenceFromFD(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int fenceId = inputStream.readInt();
        boolean initiallyTriggered = inputStream.readByte() != 0;
        inputStream.skip(3);

        // The whole point of this method: the fd is consumed on every path,
        // including the ones that throw.
        int fd = inputStream.getAncillaryFd();
        try {
            if (fd < 0) throw new BadAlloc();

            Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
            if (drawable == null) throw new BadDrawable(drawableId);

            if (!client.isValidResourceId(fenceId)) throw new BadIdChoice(fenceId);

            if (syncExtension == null) syncExtension = (SyncExtension)xServer.getExtensionByName("SYNC");
            if (syncExtension == null) throw new BadImplementation();

            syncExtension.createFenceFromFd(client, fenceId, fd, initiallyTriggered);

            // Three per swapchain, not one per frame, so this is not a hot path
            // — and without it there is no way to tell a fence that was served
            // from one the client never asked for. Both look like a run that
            // passed. Measuring the fence's cost means knowing which happened.
            Log.d(XRequestError.PROTO_TAG, "DRI3 FenceFromFD served fence 0x"
                    + Integer.toHexString(fenceId) + " triggered=" + initiallyTriggered);
        }
        finally {
            // The mapping outlives the descriptor, exactly as in pixmapFromFd.
            XConnectorEpoll.closeFd(fd);
        }
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset, byte depth, int fd, long size)  throws IOException, XRequestError {
        ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
        if (buffer == null) throw new BadAlloc();

        short totalWidth = (short)(stride / 4);
        Drawable drawable = xServer.drawableManager.createDrawable(pixmapId, totalWidth, height, depth);
        // VESSEL: createDrawable answers null when the id is already a drawable,
        // and upstream dereferences it — an NPE out of the request thread, which
        // XClientRequestHandler turns into a dropped connection rather than an X
        // error. It happens whenever a pixmap id outlives its pixmap, which the
        // ownership registration below is what stops.
        if (drawable == null) {
            SysVSharedMemory.unmapSHMSegment(buffer, size);
            throw new BadIdChoice(pixmapId);
        }
        drawable.setData(buffer);
        drawable.setTexture(null);
        drawable.setOnDestroyListener(onDestroyDrawableListener);
        Pixmap pixmap = xServer.pixmapManager.createPixmap(drawable);
        if (pixmap == null) throw new BadIdChoice(pixmapId);
        // VESSEL: the pixmap belongs to the client that asked for it, so that
        // XClient.freeResources() frees it when the connection goes.
        //
        // Upstream's own PixmapRequests.createPixmap does this and this method
        // did not, which is the whole of the "second --wsi dri3 run fails with
        // BadIdChoice" defect: a DRI3 swapchain's pixmaps outlived their client,
        // ResourceIDs handed the same id base to the next connection, and the
        // next run's first PixmapFromBuffer collided with its predecessor's.
        // Runs alternated pass and fail. A crashed guest leaks identically, so
        // this is not only a harness problem.
        client.registerAsOwnerOfResource(pixmap);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.BUFFER_FROM_PIXMAP:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    bufferFromPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.FENCE_FROM_FD: // VESSEL
                try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    fenceFromFD(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            default:
                // VESSEL: name the opcode before refusing it.
                //
                // This extension advertises DRI3 1.0 and implements five of its
                // requests — QueryVersion(0), Open(1), PixmapFromBuffer(2),
                // BufferFromPixmap(3), PixmapFromBuffers(7). Everything else
                // landed here and became a bare BadImplementation: an X error
                // with no record of *which* request the client asked for.
                //
                // That invisibility cost a day. With Mesa's DRI3 WSI compiled
                // in, `vkCreateSwapchainKHR` returns VK_ERROR_SURFACE_LOST_KHR
                // after the surface, the queue, the capabilities and the
                // formats all come back good, and nothing anywhere said why —
                // see docs/TODO.md, "Zero-copy present".
                //
                // VESSEL: FenceFromFD(4) used to arrive here, and what it did
                // on the way out is why this branch is dangerous rather than
                // merely unhelpful. **A request arriving with an fd over
                // SCM_RIGHTS leaves that fd behind when it is refused.**
                // XInputStream keeps one ancillary-fd queue per connection and
                // getAncillaryFd() pops its head, so an unconsumed fd shifts
                // the queue permanently: the next PixmapFromBuffer was handed
                // the previous image's fence page in place of its 3686400-byte
                // dma-buf, and the second present took the server down with
                // SIGBUS/BUS_ADRERR in Drawable.copyArea, faulting one page
                // into the source.
                //
                // FenceFromFD is implemented now (see fenceFromFD), but the
                // hazard is a property of this branch and not of that request:
                // **any** future DRI3 request that carries an fd will do the
                // same thing if it is refused here. Of the opcodes named below,
                // ImportSyncobj(10) is one. A refusal that has to stay a
                // refusal should still pop the fd first.
                //
                // Deliberately WARN and not DEBUG: an unimplemented request is
                // always a defect in this server or a genuine version
                // mismatch, never routine traffic.
                Log.w(XRequestError.PROTO_TAG, "DRI3 request " + opcodeName(opcode) +
                        " is not implemented — replying BadImplementation");
                throw new BadImplementation();
        }
    }

    /** VESSEL: DRI3 1.2's request names, so the log says what was asked for. */
    private static String opcodeName(int opcode) {
        switch (opcode) {
            case 4: return "FenceFromFD(4)";
            case 5: return "FDFromFence(5)";
            case 6: return "GetSupportedModifiers(6)";
            case 8: return "BuffersFromPixmap(8)";
            case 9: return "SetDRMDeviceInUse(9)";
            case 10: return "ImportSyncobj(10)";
            case 11: return "FreeSyncobj(11)";
            default: return "opcode " + opcode;
        }
    }

}
