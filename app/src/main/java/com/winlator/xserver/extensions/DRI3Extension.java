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
     * Note the version claimed is already too high in the other direction:
     * {@code FenceFromFD} (4) is a 1.0 request and is not implemented. See the
     * default branch of {@link #handleRequest}.
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
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }

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

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        pixmapFromFd(client, pixmapId, width, height, stride, 0, depth, fd, size);
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

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        long size = (long)stride * height;
        pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset, byte depth, int fd, long size)  throws IOException, XRequestError {
        try {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
            if (buffer == null) throw new BadAlloc();

            short totalWidth = (short)(stride / 4);
            Drawable drawable = xServer.drawableManager.createDrawable(pixmapId, totalWidth, height, depth);
            drawable.setData(buffer);
            drawable.setTexture(null);
            drawable.setOnDestroyListener(onDestroyDrawableListener);
            xServer.pixmapManager.createPixmap(drawable);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
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
                // VESSEL: what FenceFromFD(4) turned out to be, since the note
                // above named it as a candidate for that SURFACE_LOST and it
                // was not. The cause there was a missing XFIXES; but refusing
                // FenceFromFD is a real defect of its own, and a worse-behaved
                // one than a returned error. **The request arrives with an fd
                // over SCM_RIGHTS, and refusing it never consumes that fd.**
                // XInputStream keeps one ancillary-fd queue per connection and
                // getAncillaryFd() pops its head, so an unconsumed fd shifts
                // the queue permanently: the next PixmapFromBuffer is handed
                // the *previous* image's 4096-byte xshmfence page in place of
                // its 3686400-byte dma-buf. Measured — the second present took
                // the server down with SIGBUS/BUS_ADRERR in
                // Drawable.copyArea, faulting one page into the source.
                //
                // FenceFromFD is a DRI3 **1.0** request, so this server is not
                // conformant at the version it advertises. Two things are
                // needed to implement it and neither is in this file: the
                // fence must be mapped and triggered through the client's
                // shared page (SyncExtension tracks fences as a boolean and
                // never touches the page), and PresentExtension already calls
                // syncExtension.setTriggered(idleFence) at the right moment.
                // Until then Mesa is told not to ask — patches/mesa/0007, off
                // by default, VESSEL_WSI_DRI3_FENCE=1 to re-measure after.
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
