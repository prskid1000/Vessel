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

    /**
     * VESSEL: presentproto's completion modes. {@code COPY} is the only one this
     * server ever reports, and this is the record of why — so that "the enum
     * declares FLIP, just send FLIP" is not tried a third time.
     *
     * <p><b>{@code FLIP} is not reachable from where the pixels currently live.</b>
     * Flipping means the presented pixmap's own buffer <em>becomes</em> what the
     * compositor scans out, with no copy. Here a DRI3 pixmap is not a GPU object
     * at all: {@code DRI3Extension.pixmapFromFd} consumes the client's dma-buf
     * with a plain CPU {@code mmap} ({@code DRI3Extension.java:286},
     * {@code sysvshared_memory.c:77}) and then explicitly sets the drawable's
     * texture to null ({@code DRI3Extension.java:301}). Meanwhile the window's
     * content is an {@link GPUImage} — one AHardwareBuffer, allocated once,
     * CPU-locked for its whole life and bound to a GL texture through an
     * {@code EGLImageKHR} ({@code GPUImage.java:31-43}). The compositor samples
     * that one texture object every frame. There is nothing to flip *to*.
     *
     * <p><b>What FLIP would actually require</b>, in order:
     * <ol>
     *   <li>An importer for a client-supplied dma-buf fd as an
     *       {@code EGLImageKHR} — {@code EGL_EXT_image_dma_buf_import} with
     *       {@code EGL_LINUX_DMA_BUF_EXT}, fourcc, stride, offset and modifier.
     *       <b>No such code exists anywhere in this tree.</b> The only
     *       {@code eglCreateImageKHR} call is {@code gpu_image.c:29-46} and it
     *       imports {@code EGL_NATIVE_BUFFER_ANDROID} from an AHardwareBuffer —
     *       the outbound direction, not the inbound one.</li>
     *   <li>Rebinding the window's renderable texture per present, which means
     *       the GL thread owning the swap rather than the X request thread.</li>
     *   <li>Deferring {@link PresentIdleNotify} until the compositor has finished
     *       with that image, instead of sending it the moment the copy returns.
     *       Today "idle" is honest because the server took a copy; under FLIP the
     *       server still holds the client's buffer and telling it otherwise
     *       corrupts the frame.</li>
     * </ol>
     * That is a feature, not an adjustment, and item (1) is the load-bearing part
     * — until an inbound dma-buf importer exists, {@code FLIP} cannot be told
     * truthfully and this server must keep saying {@code COPY}.
     *
     * <p>{@code SKIP} is likewise never sent: it reports a present the server
     * dropped because a later one overtook it, and nothing here queues presents
     * to overtake.
     */
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;

    // VESSEL: see presentPixmap. Unsynchronised, and that is still correct after
    // the render-lock narrowing there: every present runs under the server-wide
    // `WINDOW_MANAGER` lock (handleRequest), so no two presents overlap even
    // though each connection has its own OS thread.
    private static final int COPY_REPORT_EVERY = 120;
    private long copyNanos;
    private long copyMaxNanos;
    private long copyCount;

    // VESSEL: the same total, decomposed. `copyNanos` above spans the whole of
    // `Drawable.copyArea`, which is the two DMA_BUF_IOCTL_SYNC calls *plus* the
    // pixel copy *plus* the rewinds and `forceUpdate` — and the 19.1 ms in the
    // history was never separated into those. Accumulated in the same sampled
    // window and printed on the same line, so the parts and the total can be
    // read against each other and against that history.
    //
    // `syncOtherNanos` is the residual, not a fourth measurement: total minus
    // the three phases the drawable reports. It is the clamping, the two
    // `rewind`s and `forceUpdate` — and `forceUpdate` runs the window's
    // on-draw listener, so it is not automatically small and is worth seeing
    // rather than silently folding into the copy.
    private long syncInNanos;
    private long copyOnlyNanos;
    private long syncOutNanos;
    private long syncOtherNanos;

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

        // VESSEL: the compositor must not wait behind this copy, and on the DRI3
        // path it does not have to.
        //
        // **What the lock was costing.** `content.copyArea` below is a
        // synchronous CPU copy of the whole presented image — 1920x1080x4 is 8 MB
        // a present — and it ran inside `synchronized (content.renderLock)`. The
        // GL thread takes that same monitor once per window per composited frame
        // (`GLRenderer.renderWindowDrawable`, GLRenderer.java:335), and it is the
        // thread that has to meet vblank. Its own hold is microseconds; what it
        // pays is the *wait*. A render request can arrive from anywhere at any
        // time — pointer motion alone posts one (GLRenderer.java:311) and a game
        // with mouse-look posts them continuously — so the GL thread lands on
        // this monitor mid-copy routinely rather than rarely, and each time it
        // does it misses a frame deadline by however long the memcpy has left.
        // Intermittent, scheduling-dependent, and exactly the shape of "run,
        // freeze, run".
        //
        // **Why dropping it is safe here and not in general.** The monitor's real
        // job is to keep a pixel writer away from the compositor while the
        // compositor is *reading those pixels* — which it does in
        // `Texture.updateFromDrawable()`, whose `glTexSubImage2D` uploads
        // straight out of `drawable.getData()` (Texture.java:152). That read does
        // not exist for a `GPUImage`: its `updateFromDrawable()` allocates once
        // and then only clears `needsUpdate` (GPUImage.java:46-49), because the
        // pixels live in an AHardwareBuffer the GPU samples directly through an
        // EGLImage. And a presenting window's content is *always* a `GPUImage` —
        // `selectInput` below converts it (PresentExtension.java:222-225) before
        // Mesa can send its first `PresentPixmap`. So on the path that matters
        // the monitor excludes the compositor from a read the compositor never
        // performs.
        //
        // **What it was never protecting, so nothing is lost.** It is tempting to
        // read this lock as keeping the destination buffer alive across the copy.
        // It does not: `Texture.destroy()` and `GPUImage.destroy()` take no lock
        // at all (Texture.java:236, GPUImage.java:74) and run on the GL thread via
        // `queueEvent(texture::destroy)` — so that race was already open, both
        // before and after this change. What actually keeps the window's content
        // alive across the copy is the `WINDOW_MANAGER` lock this request already
        // holds (handleRequest below), which is why that one is deliberately left
        // in place even though it is the coarser of the two: dropping it would
        // convert a compositor stall into a use-after-free.
        //
        // Nor was it preventing tearing. `glDrawArrays` only enqueues; the GPU
        // samples the AHardwareBuffer some time after the monitor is released, so
        // a present landing in the same buffer could always tear against a frame
        // in flight. Unchanged by this.
        //
        // The non-GPUImage branch keeps the original behaviour verbatim, for the
        // `pixmap == null` / plain-`Texture` cases where the compositor really
        // does read `drawable.getData()`.
        if (content.getTexture() instanceof GPUImage) {
            presentToContent(window, content, pixmap, serial, xOff, yOff, idleFence);
        }
        else synchronized (content.renderLock) {
            presentToContent(window, content, pixmap, serial, xOff, yOff, idleFence);
        }
    }

    /**
     * VESSEL: the body of {@link #presentPixmap}, extracted so the two locking
     * regimes above share one implementation rather than two copies of it.
     *
     * <p><b>The copy stays synchronous, on the request thread. This is the
     * record of why, so the "obviously it should be async" change is not
     * attempted a second time.</b> It was investigated properly; it is not
     * unsafe so much as <em>pointless</em>, and the reason is not visible from
     * this file.
     *
     * <p><b>1. The client's throttle point is idle-notify, not this thread.</b>
     * Mesa's X11 WSI on DRI3 marks a swapchain image free only when
     * {@code XCB_PRESENT_EVENT_IDLE_NOTIFY} arrives — that handler is the sole
     * producer for {@code chain->acquire_queue}
     * ({@code native/mesa/src/vulkan/wsi/wsi_common_x11.c:1664-1676}), and
     * {@code x11_acquire_next_image} is its sole consumer. {@code PresentPixmap}
     * itself has no reply: {@code x11_queue_present} pushes to
     * {@code present_queue} and a WSI-owned manager thread sends the request and
     * flushes, so the application thread never waits on this server at all.
     *
     * <p>Now put those together with the constraint that makes async hard in the
     * first place. {@link #sendIdleNotify} may not be sent until the copy has
     * actually finished — send it at request time and the client starts
     * overwriting a buffer the server is still reading, which is tearing that
     * will be blamed on a driver. So idle must fire at copy-completion under
     * <em>either</em> design. Present N at t0, copy costing T: idle fires at
     * t0+T synchronously, and at t0+T asynchronously. The client is unblocked at
     * the same instant. With a single copy thread the queued case is identical
     * too — the copies are serialised by the same memory bandwidth either way,
     * so a backlog drains at exactly the rate it drains at today. There is no
     * frame-pacing gain to collect.
     *
     * <p><b>2. The lock discipline that would make it safe costs more than it
     * buys.</b> What excludes teardown across the copy today is the
     * {@code WINDOW_MANAGER}+{@code PIXMAP_MANAGER} pair held by
     * {@link #handleRequest}. Hand the copy to another thread and those drop at
     * request return, while the copy is still reading
     * {@code pixmap.drawable.getData()} — a mapping that
     * {@code DRI3Extension}'s destroy listener munmaps, and whose destination is
     * a {@code GPUImage} the GL thread can free. {@code FreePixmap} runs under
     * {@code PIXMAP_MANAGER}+{@code DRAWABLE_MANAGER}
     * ({@code XClientRequestHandler.java:313}) and client disconnect under
     * {@code lockAll()}, so the copy thread would have to hold at least that
     * pair to keep the source alive. But {@code DRAWABLE_MANAGER} is exactly the
     * lock the compositor takes for its whole window pass
     * ({@code GLRenderer.renderWindows}, GLRenderer.java:479) — which the
     * present path deliberately does <em>not</em> take today, and which is why
     * narrowing {@code renderLock} above was enough to get the compositor out
     * from behind this memcpy. Taking it on a copy thread would put the GL
     * thread back behind the copy through a different monitor: the same bug,
     * re-entered by the back door. Avoiding that needs a per-drawable pin with
     * deferred munmap and a matching pin on {@code GPUImage.destroy}, i.e. real
     * machinery, bought with nothing from (1) to pay for it.
     *
     * <p><b>3. What async would actually have bought</b>, for completeness: the
     * request thread returns to its socket sooner, and {@code WINDOW_MANAGER} is
     * released sooner — which matters only to {@code XServer.injectPointerMove}
     * and friends, i.e. input latency on the host's input thread, not frame
     * pacing. If input stalls turn out to be the observed stutter, the fix is to
     * stop holding {@code WINDOW_MANAGER} across the copy, which is a narrowing
     * of {@link #handleRequest} and not an async copy.
     *
     * <p><b>Where the remaining cost really is, and what was done about it.</b>
     * T is now split rather than removed. {@code Drawable.copyArea} hands the
     * rectangle to {@code copyPoolCopyRows} ({@code cpp/winlator/src/copy_pool.c}),
     * which divides it into row bands across a persistent pthread pool — the
     * calling thread takes the smallest band itself and joins the rest before
     * returning. Every property argued for above survives untouched, and that
     * is the reason the pool is joined rather than left running: this request
     * thread still owns the copy from its first byte to its last, so
     * {@link #sendIdleNotify} below still fires exactly at copy completion, the
     * source mapping is still alive for the whole read under the same
     * {@code WINDOW_MANAGER}+{@code PIXMAP_MANAGER} pair, and no lock is added.
     * The 154 MB/s the sampler measured is uncached-read speed, and uncached
     * reads are latency-bound rather than bandwidth-bound, so the bands overlap
     * their stalls instead of queueing for a saturated bus — which is why this
     * split is expected to pay and why it would not on a cached buffer. The
     * post-split mean is <em>unmeasured</em>; the sampler below is what will
     * measure it.
     */
    private void presentToContent(Window window, Drawable content, Pixmap pixmap, int serial,
                                  short xOff, short yOff, int idleFence) {
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
            //
            // VESSEL: read this number first when presentation stutters. The
            // source is an `mmap` of the client's dma-buf, and it is now
            // bracketed with `DMA_BUF_IOCTL_SYNC(START|READ)` /
            // `(END|READ)` — `Drawable.copyArea` calls
            // `beginDmaBufRead`/`endDmaBufRead`, which reach
            // `SysVSharedMemory.dmaBufSyncRead` and
            // `sysvshared_memory.c`'s `dmaBufSyncRead`.
            //
            // That bracket does not change what this number means, and the
            // difference is worth stating because it is easy to assume
            // otherwise: the sync ioctl drives the exporter's
            // begin/end_cpu_access ops, which do *cache maintenance*. It cannot
            // change the memory type of the mapping — that was fixed by the
            // exporter's mmap op setting vm_page_prot and no ioctl revisits it.
            // So if `mean` is tens of milliseconds rather than the low single
            // digits an 8 MB cached copy costs, the mapping is uncached or
            // write-combine and the bracket will not have helped; if anything
            // an invalidate over a few megabytes costs a little more.
            //
            // What the exporter is, since it decides the answer: the guest's
            // Turnip does not allocate exportable images from KGSL at all. It
            // opens `/dev/dma_heap/system` and allocates there
            // (`native/mesa/src/freedreno/vulkan/tu_knl_kgsl.cc`,
            // `bo_init_new_dmaheap` at :173, heap path at :2706), then imports
            // the result into KGSL. So the exporter is the kernel's system
            // dma-heap, whose mmap leaves `vm_page_prot` alone — a cached,
            // writeback mapping — which predicts the low-single-digit case and
            // makes this bracket a correctness fix rather than a speed one.
            // Predicts, not proves: nothing readable on the device reports a
            // mapping's memory type, so this log line remains the only
            // instrument that can tell the two apart.
            //
            // VESSEL: it told them apart, and the prediction above lost.
            //
            //     Present copyArea x6720 mean=19114us max=385490us last=69539us 1280x720
            //
            // 19.1 ms for 3.5 MB is ~154 MB/s — the uncached case, not the low
            // single digits the dma-heap reasoning predicted. That number is
            // why `copyPoolCopyRows` exists (`cpp/winlator/src/copy_pool.c`)
            // and why splitting it across cores is expected to work: an
            // uncached read stalls on latency, and stalls overlap.
            //
            // So what this sampler now measures is the *split* copy, and its
            // successor to the line above has not been captured yet. Read
            // `mean` first, as before; the target set when the default flipped
            // is under about 3 ms. The single line `copy pool: N participants`
            // logged once at startup under this same tag says how many cores
            // it actually got, which is the first thing to check if `mean` has
            // not moved.
            //
            // VESSEL: and the 19.1 ms was never separated into its parts, which
            // is the gap the phase means below close. That number is the whole
            // of `copyArea`, and `copyArea` is two `DMA_BUF_IOCTL_SYNC` calls as
            // well as the copy. On a *cached* mapping a `begin_cpu_access` walks
            // the scatterlist and invalidates every line of 3.5 MB — roughly 900
            // pages — which is not free, so "the copy costs 19 ms" and "the
            // cache maintenance costs 19 ms" were never distinguished by the
            // instrument that produced it. They want opposite fixes: the first
            // is the row-band pool and a cached heap, the second is a narrower
            // sync. Unresolved until the split line below is captured on a
            // device; do not spend anything on either answer before then.
            long t0 = System.nanoTime();
            content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
            long dt = System.nanoTime() - t0;
            copyNanos += dt;
            if (dt > copyMaxNanos) copyMaxNanos = dt;

            // VESSEL: the split. `Drawable.copyArea` is the only code that sees
            // the boundary between the sync ioctls and the copy, so it records
            // the three phases and they are read back here.
            //
            // What each term decides, since that is the point of separating
            // them. **syncIn large** means the cost is cache maintenance, the
            // mapping is cached, and the row-band pool is parallelising the
            // wrong half — the fix would be to invalidate less (a narrower
            // sync, or none where the exporter is already coherent), not to
            // copy faster. **copy large with syncIn near zero and sync=LIVE**
            // means the read itself is slow, which at ~154 MB/s means an
            // uncached or write-combine mapping, and the fix is upstream of
            // this server entirely: allocate the swapchain image from a cached
            // heap. See docs/BANDWIDTH.md. **sync=REFUSED** invalidates the
            // first reading — the zeros are an absence, not a measurement.
            long syncIn = content.getLastSyncStartNanos();
            long copyOnly = content.getLastCopyNanos();
            long syncOut = content.getLastSyncEndNanos();
            syncInNanos += syncIn;
            copyOnlyNanos += copyOnly;
            syncOutNanos += syncOut;
            syncOtherNanos += Math.max(0, dt - syncIn - copyOnly - syncOut);

            if (++copyCount % COPY_REPORT_EVERY == 0) {
                Log.d(XRequestError.PROTO_TAG, "Present copyArea x" + copyCount
                        + " mean=" + (copyNanos / copyCount / 1000) + "us"
                        + " max=" + (copyMaxNanos / 1000) + "us"
                        + " last=" + (dt / 1000) + "us"
                        + " " + pixmap.drawable.width + "x" + pixmap.drawable.height
                        + " means syncIn=" + (syncInNanos / copyCount / 1000) + "us"
                        + " copy=" + (copyOnlyNanos / copyCount / 1000) + "us"
                        + " syncOut=" + (syncOutNanos / copyCount / 1000) + "us"
                        + " other=" + (syncOtherNanos / copyCount / 1000) + "us"
                        + " sync=" + content.getLastDmaBufSync());
            }
            sendIdleNotify(window, pixmap, serial, idleFence);
        }
        else content.forceUpdate();
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, 0, 0);
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
