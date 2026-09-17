package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * VESSEL: XFIXES, and specifically its regions.
 *
 * <p>Written for one reason, which is worth stating exactly because three
 * earlier explanations of the same failure were wrong. Mesa's X11 WSI creates
 * an XFIXES region per swapchain image on the DRI3 path, and it does so
 * <em>unguarded</em> — `wsi_common_x11.c`:
 *
 * <pre>
 *   if (chain-&gt;base.wsi-&gt;sw &amp;&amp; !chain-&gt;has_mit_shm)
 *      return VK_SUCCESS;               // the software path stops here
 *   #ifdef HAVE_X11_DRM
 *   image-&gt;update_region = xcb_generate_id(chain-&gt;conn);
 *   xcb_xfixes_create_region(chain-&gt;conn, image-&gt;update_region, 0, NULL);
 * </pre>
 *
 * <p>There is a `has_xfixes` flag and this call does not consult it. When the
 * server does not advertise XFIXES, <b>libxcb tears the connection down on the
 * client side</b> with {@code XCB_CONN_CLOSED_EXT_NOTSUPPORTED} rather than
 * sending anything — so nothing reaches this server, no protocol error exists
 * to log, and the application sees the connection simply die.
 *
 * <p>That is why this took so long to find. The visible symptom was
 * `vkCreateSwapchainKHR` returning `VK_ERROR_SURFACE_LOST_KHR` from a failed
 * `GetGeometry`, which is not the cause at all: Mesa tries the swapchain once,
 * the attempt kills the connection, Mesa retries, and the retry fails at the
 * first request it makes. Measured with a probe in the retry path:
 *
 * <pre>
 *   sw:   create_swapchain sw=1 window=0x1800000 conn_err=0  -&gt; PASS
 *   dri3: create_swapchain sw=0 window=0x1800000 conn_err=0
 *         create_swapchain sw=0 window=0x1800000 conn_err=2  -&gt; SURFACE_LOST
 * </pre>
 *
 * <p>It is also, in all likelihood, the original `X connection to :0 broken`
 * that killed a whole session the first time zero-copy was switched on.
 *
 * <h3>What this implements, and what it deliberately does not</h3>
 *
 * <p>Regions only: {@code CreateRegion}, {@code DestroyRegion} and
 * {@code SetRegion}, plus {@code QueryVersion}. That is the entire set Mesa's
 * swapchain touches. Every other XFIXES request — cursors, selection tracking,
 * the region algebra, save-sets — is refused, and refused loudly through the
 * shared unimplemented-request log rather than silently.
 *
 * <p><b>The regions are stored and not yet acted upon, and that is honest
 * rather than lazy.</b> A region reaches this server as the `update` or `valid`
 * area of a `PresentPixmap`, and {@link PresentExtension#presentPixmap} already
 * skips both fields and copies the whole pixmap. So tracking a region changes
 * no pixels today. It still has to be tracked: the ids must be real, because a
 * client may set, reuse and destroy them, and answering `BadValue` for an id we
 * handed out would be a different bug. When `presentPixmap` learns to honour a
 * damage rectangle, the data is already here.
 *
 * <p><b>VESSEL: version 4.0 is reported, and it has to be.</b> 2.0 was chosen
 * as the lowest that satisfies Mesa's `major_version &gt;= 2` check, on the
 * reasoning that claiming more would advertise requests that are then refused.
 * That reasoning holds for 5 and 6 — pointer barriers — and it is wrong for 4,
 * because libXfixes does not send {@code HideCursor} or {@code ShowCursor} at
 * all when the server reports less than 4. It drops them client-side, and the
 * caller cannot tell.
 *
 * <p>Wine warps the pointer like this (dlls/winex11.drv/mouse.c,
 * {@code X11DRV_SetCursorPos}):
 *
 * <pre>
 *   XFixesHideCursor( display, root );
 *   XWarpPointer( display, ..., pos.x, pos.y );
 *   data-&gt;warp_serial = NextRequest( display );
 *   XFixesShowCursor( display, root );
 * </pre>
 *
 * and then discards every motion event whose sequence number is below
 * {@code warp_serial} — that is how it ignores the warp's own motion rather
 * than feeding it back. {@code NextRequest} is the serial {@code ShowCursor} is
 * about to take, so on a server with XFIXES 4 the very next request makes the
 * sequence catch up and real motion is accepted again.
 *
 * <p>At version 2 neither cursor request is sent, nothing follows the warp, and
 * the sequence never reaches {@code warp_serial}: <b>every mouse motion after
 * the first warp is discarded</b>. Measured with Caribbean Legend, which
 * recentres the cursor every frame:
 *
 * <pre>
 *   cursor:X11DRV_SetCursorPos warped to 640,360 serial 114
 *   cursor:X11DRV_MotionNotify pos 1287,329 old serial 113, ignoring
 * </pre>
 *
 * The cursor moved only when something else — a click changing the cursor
 * shape — happened to send a request and push the sequence past the warp.
 *
 * <p>The two requests are answered rather than acted on, which is the honest
 * position: this server hands the cursor to the compositor, which draws what
 * {@code CursorManager} holds, and hiding it for the microseconds of a warp
 * would not change a pixel. What matters to the client is that the request was
 * consumed, and that it counts.
 */
public class XFixesExtension extends Extension {
    public static final byte MAJOR_VERSION = 4;
    public static final byte MINOR_VERSION = 0;

    /** Region id to its rectangles, four shorts each: x, y, width, height. */
    private final SparseArray<short[]> regions = new SparseArray<>();

    /**
     * VESSEL: which client made each region, so {@link #freeClientResources}
     * can drop them. Mesa destroys its regions in {@code x11_image_finish}, so
     * an orderly teardown never needs this — a crashed guest does, and every
     * region it left behind would otherwise sit here for the life of the
     * session. Also the map is written from more than one thread now
     * ({@code setMultithreadedClients(true)}), hence the synchronization added
     * with it; the reads and writes were unguarded before and only got away
     * with it because one client uses XFIXES.
     */
    private final SparseArray<XClient> regionOwners = new SparseArray<>();

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte CREATE_REGION = 5;
        private static final byte DESTROY_REGION = 10;
        private static final byte SET_REGION = 11;
        /** VESSEL: XFIXES 4. See the class comment for what silence here cost. */
        private static final byte HIDE_CURSOR = 29;
        private static final byte SHOW_CURSOR = 30;
    }

    public XFixesExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "XFIXES";
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(8); // client major, client minor

        // Per spec the server replies with the lower of what it and the client
        // support. The client asks for 6.0 here and gets 2.0, which is the
        // negotiation working rather than a downgrade.
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

    /** Reads however many whole RECTANGLEs are left in the request. */
    private short[] readRectangles(XClient client, XInputStream inputStream) throws IOException {
        int remaining = client.getRemainingRequestLength();
        int count = remaining / 8;
        short[] rects = new short[count * 4];
        for (int i = 0; i < count; i++) {
            rects[i * 4]     = inputStream.readShort(); // x
            rects[i * 4 + 1] = inputStream.readShort(); // y
            rects[i * 4 + 2] = inputStream.readShort(); // width
            rects[i * 4 + 3] = inputStream.readShort(); // height
        }
        inputStream.skip(remaining - count * 8);
        return rects;
    }

    private void createRegion(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        int regionId = inputStream.readInt();
        if (regionId == 0) throw new BadValue(regionId);
        // VESSEL: the monitor is held across readRectangles, which is cheap —
        // XInputStream reads out of a buffer the epoll thread has already
        // filled, so nothing here waits on a socket.
        synchronized (regions) {
            regions.put(regionId, readRectangles(client, inputStream));
            regionOwners.put(regionId, client); // VESSEL
        }
    }

    private void setRegion(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        int regionId = inputStream.readInt();
        // Mesa only ever sets a region it created, so an unknown id is a real
        // protocol error rather than something to tolerate quietly.
        synchronized (regions) {
            if (regions.indexOfKey(regionId) < 0) throw new BadValue(regionId);
            regions.put(regionId, readRectangles(client, inputStream));
        }
    }

    private void destroyRegion(XInputStream inputStream) throws IOException, XRequestError {
        int regionId = inputStream.readInt();
        synchronized (regions) {
            if (regions.indexOfKey(regionId) < 0) throw new BadValue(regionId);
            regions.remove(regionId);
            regionOwners.remove(regionId); // VESSEL
        }
    }

    /** The rectangles of a region, or null. For {@code PresentPixmap} damage. */
    public short[] getRegion(int regionId) {
        synchronized (regions) {
            return regions.get(regionId);
        }
    }

    /** VESSEL: see {@link #regionOwners}. */
    @Override
    public void freeClientResources(XClient client) {
        synchronized (regions) {
            // Collected then removed; see SyncExtension.freeClientResources.
            int[] owned = new int[regionOwners.size()];
            int count = 0;
            for (int i = 0; i < regionOwners.size(); i++) {
                if (regionOwners.valueAt(i) == client) owned[count++] = regionOwners.keyAt(i);
            }
            for (int i = 0; i < count; i++) {
                regions.remove(owned[i]);
                regionOwners.remove(owned[i]);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CREATE_REGION:
                createRegion(client, inputStream);
                break;
            case ClientOpcodes.SET_REGION:
                setRegion(client, inputStream);
                break;
            case ClientOpcodes.DESTROY_REGION:
                destroyRegion(inputStream);
                break;
            // VESSEL: answered, not acted on -- the window argument is read so
            // the request is consumed in full, and nothing is hidden because
            // the compositor draws the cursor and a warp lasts microseconds.
            // Being *answered at all* is the point: see the class comment.
            case ClientOpcodes.HIDE_CURSOR:
            case ClientOpcodes.SHOW_CURSOR:
                inputStream.readInt();
                break;
            default:
                android.util.Log.w(XRequestError.PROTO_TAG, "XFIXES request opcode " + opcode +
                        " is not implemented — replying BadImplementation");
                throw new BadImplementation();
        }
    }
}
