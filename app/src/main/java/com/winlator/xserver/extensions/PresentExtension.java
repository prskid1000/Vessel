package com.winlator.xserver.extensions;

import android.util.Log;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.core.Bitmask;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.PresentCompleteNotify;
import com.winlator.xserver.events.PresentIdleNotify;

import java.io.IOException;

public class PresentExtension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 0;
    private static final int FAKE_INTERVAL = 1000000 / 60;

    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;

    // VESSEL: see presentPixmap. Touched only from the X request thread.
    private static final int COPY_REPORT_EVERY = 120;
    private long copyNanos;
    private long copyMaxNanos;
    private long copyCount;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
        private static final byte QUERY_CAPABILITIES = 4; // VESSEL
    }

    /**
     * VESSEL: Present capability bits, from presentproto.
     *
     * Declared in full and reported as {@link #CAPABILITY_NONE}, because none
     * of the other three is true of this server: there is no async (tearing)
     * present, no fence support, and the UST reported by
     * {@link #sendCompleteNotify} is `System.nanoTime()` against a fabricated
     * 60 Hz interval rather than a real vblank clock.
     */
    private static final int CAPABILITY_NONE = 0;

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    public PresentExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "Present";
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);
        if (events.size() == 0) return;

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(this, event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        if (events.size() == 0) return;

        if (ust == 0 && msc == 0) {
            ust = System.nanoTime() / 1000;
            msc = ust / FAKE_INTERVAL;
        }

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(this, event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
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

    /**
     * VESSEL: `PresentQueryCapabilities`, which was the whole of the zero-copy
     * blocker.
     *
     * Mesa's X11 WSI issues this while creating a DRI3 swapchain and treats a
     * failure as fatal, so refusing it produced
     * `vkCreateSwapchainKHR -> VK_ERROR_SURFACE_LOST_KHR` *after* the surface,
     * the queue, the capabilities and the formats had all come back good — an
     * error with no cause attached to it anywhere. Found by measurement, not by
     * reading: the WARN added to this switch's default branch printed exactly
     * one line, `Present request opcode 4 is not implemented`, during a
     * `tools/gfx/run-x11present.sh --wsi dri3` run. The two standing theories,
     * `xcb_dri3_open` and the absent DRM fd, were both wrong and neither is
     * even reached.
     *
     * The request carries a target (a window or pixmap) and the reply is one
     * `CARD32` of capability bits. **Answering `None` is the honest reply and
     * not a stub:** every bit this could set would be a promise this server
     * does not keep, and the client's fallback for each is the path already
     * taken today. Reading the target and ignoring it is per spec — the
     * capabilities are a property of the screen's presentation engine, and
     * there is one here.
     */
    private void queryCapabilities(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // target
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(CAPABILITY_NONE);
            outputStream.writePad(20);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);

        Drawable content = window.getContent();
        if (pixmap != null && content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        synchronized (content.renderLock) {
            if (pixmap != null) {
                // VESSEL: time the copy, because nothing else can see it.
                //
                // The 0.560 ms `x11present` reports is the *client's*
                // vkQueuePresentKHR round trip, and with three swapchain images
                // the client is usually not waiting on this copy when it
                // returns. So the one number that decides whether item 27
                // (GPU-backing the drawable to delete this memcpy) is worth
                // building has never been visible from either end. Sampled
                // rather than logged per present: at 60 Hz a line a frame is
                // itself a cost.
                long t0 = System.nanoTime();
                content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
                long dt = System.nanoTime() - t0;
                copyNanos += dt;
                if (dt > copyMaxNanos) copyMaxNanos = dt;
                if (++copyCount % COPY_REPORT_EVERY == 0) {
                    Log.d(XRequestError.PROTO_TAG, "Present copyArea x" + copyCount
                            + " mean=" + (copyNanos / copyCount / 1000) + "us"
                            + " max=" + (copyMaxNanos / 1000) + "us"
                            + " last=" + (dt / 1000) + "us"
                            + " " + pixmap.drawable.width + "x" + pixmap.drawable.height);
                }
                sendIdleNotify(window, pixmap, serial, idleFence);
            }
            else content.forceUpdate();
            sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, 0, 0);
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Drawable content = window.getContent();
        final Texture texture = content.getTexture();

        if (!(texture instanceof GPUImage)) {
            xServer.getRenderer().xServerView.queueEvent(texture::destroy);
            content.setTexture(new GPUImage(content));
        }

        if (eventId > 0) {
            synchronized (events) {
                Event event = events.get(eventId);
                if (event != null) {
                    if (event.window != window || event.client != client) throw new BadMatch();

                    if (!mask.isEmpty()) {
                        event.mask = mask;
                    }
                    else events.remove(eventId);
                }
                else {
                    event = new Event();
                    event.id = eventId;
                    event.window = window;
                    event.client = client;
                    event.mask = mask;
                    events.put(eventId, event);
                }
            }
        }
    }

    /**
     * VESSEL: drops the event contexts a disconnecting client registered.
     *
     * <p>An event id is a client-generated XID like any other, and
     * {@code ResourceIDs.free()} hands a departing client's id base straight to
     * the next connection — so a leftover context is not a slow leak, it is the
     * next client's first {@code SelectInput} failing. That request finds the
     * stale entry, sees {@code event.client != client}, and throws
     * {@code BadMatch}; Mesa issues it unchecked, so the swapchain is built
     * against an event id the server has never associated with it and every
     * {@code PresentCompleteNotify} and {@code PresentIdleNotify} is delivered
     * to a dead connection. The client then waits for an idle image forever.
     *
     * <p>Each entry also pins a {@link Window} and an {@link XClient} for the
     * life of the session, which is the ordinary leak underneath the collision.
     */
    @Override
    public void freeClientResources(XClient client) {
        synchronized (events) {
            // Collected then removed — SparseArray.removeAt() only tombstones
            // and the next accessor compacts, so mutating inside the walk is a
            // correctness argument nobody should have to re-derive.
            int[] owned = new int[events.size()];
            int count = 0;
            for (int i = 0; i < events.size(); i++) {
                if (events.valueAt(i).client == client) owned[count++] = events.keyAt(i);
            }
            for (int i = 0; i < count; i++) events.remove(owned[i]);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = (SyncExtension)xServer.getExtensionByName("SYNC");

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.QUERY_CAPABILITIES: // VESSEL
                queryCapabilities(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                // VESSEL: same reasoning as DRI3Extension's default branch.
                // Present is the other half of the zero-copy path, so a
                // swapchain that dies without a protocol error could be
                // refused here just as easily as there.
                Log.w(XRequestError.PROTO_TAG, "Present request opcode " + opcode +
                        " is not implemented — replying BadImplementation");
                throw new BadImplementation();
        }
    }
}
