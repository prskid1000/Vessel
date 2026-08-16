package com.winlator.xserver;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.Nullable;

import com.winlator.core.Callback;
import com.winlator.math.Mathf;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xserver.errors.XRequestError;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Drawable extends XResource {
    public final short width;
    public final short height;
    public final Visual visual;
    private Texture texture = new Texture(this);
    private ByteBuffer data;
    private boolean useSharedData;
    private Runnable onDrawListener;
    private boolean offscreenStorage = false;
    private Callback<Drawable> onDestroyListener;
    public final Object renderLock = new Object();

    /**
     * VESSEL: a descriptor for {@link #data} when {@link #data} is a mapped
     * dma-buf, or -1 when it is anything else.
     *
     * <p>Only {@code DRI3Extension.pixmapFromFd} sets this. Everything else
     * that reaches {@link #setData} — MIT-SHM segments, {@code GPUImage}'s
     * locked AHardwareBuffer, the plain {@code allocateDirect} in the
     * constructor — leaves it -1 and pays one predictable branch per copy.
     */
    private int dmaBufFd = -1;

    /**
     * VESSEL: latched false the first time {@link #dmaBufFd} refuses the sync
     * ioctl.
     *
     * A dma-buf whose exporter has no {@code begin_cpu_access} op, or an fd
     * that turns out not to be a dma-buf at all, answers the same way every
     * time. Retrying it 60 times a second is a syscall per frame for a result
     * that cannot change. Not volatile and not synchronised deliberately: the
     * only writer and the only reader are whichever single thread is inside
     * {@link #copyArea}, and a torn read of a boolean that only ever goes
     * true→false costs at most one redundant ioctl.
     */
    private boolean dmaBufSyncable = true;

    /**
     * VESSEL: what the dma-buf bracket actually did on the most recent
     * {@link #copyArea}, recorded so that a phase timing of zero can be told
     * apart from a phase that never ran.
     *
     * <p>Without this the split timings below are ambiguous in the one direction
     * that matters: {@code syncIn=0} could mean the exporter's
     * {@code begin_cpu_access} is nearly free, or it could mean no ioctl was
     * issued at all. Those lead to opposite conclusions about where the present
     * cost lives, and the difference is not otherwise visible in the sampled
     * line — the one-shot refusal log fires once per drawable and is long gone
     * by the time a mean is printed.
     */
    public enum DmaBufSync {
        /** The source held no descriptor. MIT-SHM segments, {@code GPUImage},
         *  and the constructor's {@code allocateDirect} all land here: no ioctl
         *  was issued and none was owed. */
        NONE,
        /** Both halves were issued. A near-zero timing under this state is a
         *  measurement of a cheap {@code begin_cpu_access}, not an absence. */
        LIVE,
        /** The descriptor refused the ioctl and the bracket is latched off for
         *  the life of the drawable — see {@link #dmaBufSyncable}. Reads of it
         *  are uncoordinated, and the sync timings are therefore zero because
         *  no cache maintenance is being done, not because it is free. */
        REFUSED
    }

    /**
     * VESSEL: the most recent {@link #copyArea} split into its three phases, in
     * nanoseconds, plus what the bracket did.
     *
     * <p><b>Why this is here and not at the one hot call site.</b>
     * {@code PresentExtension.presentToContent} times the whole of
     * {@code copyArea} and has done since before the copy was ever measured —
     * that is where the 19.1 ms in the history comes from. But its timer spans
     * the two {@code DMA_BUF_IOCTL_SYNC} calls as well as the copy, and on a
     * cached mapping a {@code begin_cpu_access} walks the scatterlist and
     * invalidates every line of a 3.5 MB buffer, which is not free. So a single
     * combined number cannot say whether the cost is the read or the cache
     * maintenance, and those want opposite fixes. Only this method sees the
     * boundaries, so the split is taken here and read back by the caller.
     *
     * <p>Recorded on the <em>destination</em>, which is the receiver of
     * {@link #copyArea} and therefore the object the caller already holds. The
     * bracket itself belongs to the source; {@link #lastDmaBufSync} carries the
     * source's state across so one object answers the whole question.
     *
     * <p>Not synchronised, for the reason {@link #dmaBufSyncable} gives: a
     * present runs under {@code WINDOW_MANAGER} and the values are read
     * immediately after the call that wrote them, on the thread that wrote them.
     */
    private long lastSyncStartNanos;
    private long lastCopyNanos;
    private long lastSyncEndNanos;
    private DmaBufSync lastDmaBufSync = DmaBufSync.NONE;

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        super(id);
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;
        this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.data);
        return drawable;
    }

    public boolean isOffscreenStorage() {
        return offscreenStorage;
    }

    public void setOffscreenStorage(boolean offscreenStorage) {
        this.offscreenStorage = offscreenStorage;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof GPUImage) data = ((GPUImage)texture).getVirtualData();
        this.texture = texture;
    }

    @Nullable
    public ByteBuffer getData() {
        return data;
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    /**
     * VESSEL: hand this drawable a descriptor for the dma-buf its {@link #data}
     * is a mapping of, so CPU reads of it can be bracketed with
     * {@code DMA_BUF_IOCTL_SYNC}. See {@link #dmaBufFd}.
     *
     * <p>Ownership transfers: the drawable's destroy listener closes it. The
     * caller is expected to pass a descriptor of its own
     * ({@code SysVSharedMemory.dupFd}), never the client's, because the
     * client's is closed as soon as the request that carried it returns.
     */
    public void setDmaBufFd(int dmaBufFd) {
        this.dmaBufFd = dmaBufFd;
    }

    public int getDmaBufFd() {
        return dmaBufFd;
    }

    private short getStride() {
        return texture instanceof GPUImage ? ((GPUImage)texture).getStride() : width;
    }

    public Runnable getOnDrawListener() {
        return onDrawListener;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        if (this.data == null) return;

        if (depth == 1) {
            drawBitmap(width, height, data, this.data);
        }
        else if (depth == 24 || depth == 32) {
            dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
            dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
            if ((dstX + width) > this.width) width = (short)((this.width - dstX));
            if ((dstY + height) > this.height) height = (short)((this.height - dstY));

            copyArea(srcX, srcY, dstX, dstY, width, height, totalWidth, this.getStride(), data, this.data);
        }

        this.data.rewind();
        data.rewind();

        forceUpdate();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (this.data == null) return dstData;

        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea(x, y, (short)0, (short)0, width, height, this.getStride(), width, this.data, dstData);

        this.data.rewind();
        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        if (this.data == null || drawable.data == null) return;

        dstX = (short)Mathf.clamp(dstX, 0, this.width-1);
        dstY = (short)Mathf.clamp(dstY, 0, this.height-1);
        if ((dstX + width) > this.width) width = (short)(this.width - dstX);
        if ((dstY + height) > this.height) height = (short)(this.height - dstY);

        // VESSEL: the dma-buf CPU-access bracket, on the *source*.
        //
        // `drawable` is the source here and `this` is the destination — read the
        // native call below, which passes `drawable.data` as srcData. The source
        // is the one that can be a client-supplied dma-buf: DRI3's
        // PixmapFromBuffer mmaps the fd Mesa hands over and calls setDmaBufFd
        // (DRI3Extension.java, pixmapFromFd), and PresentExtension.presentToContent
        // then reads the whole of it once a frame.
        //
        // Why here rather than at the present call site, which is the only hot
        // caller: this is where the pixels are actually touched, so no future
        // reader of a dma-buf-backed drawable can forget the bracket. The plain
        // X CopyArea request lands here too and needs it just as much — it is
        // rare rather than absent.
        //
        // Not applied to `this.data`. A destination is never a DRI3 pixmap in
        // this tree (a presenting window's content is a GPUImage, whose
        // AHardwareBuffer is locked once for its whole life — GPUImage.java:31-43),
        // and DMA_BUF_SYNC_WRITE on a buffer nothing has written would be a
        // cache clean for no reader.
        //
        // VESSEL: the bracket still spans the whole read now that the native
        // copy is internally parallel. `copyArea` below splits the rectangle
        // into row bands across a worker pool (cpp/winlator/src/copy_pool.c)
        // and joins them before it returns, so there is exactly one START/END
        // pair per copy and no band outlives it. That is the property the pool
        // is joined for; it is not an incidental one.
        //
        // VESSEL: the bracket is timed apart from the copy — see the fields this
        // writes. Four extra `nanoTime` calls per copyArea, unconditionally
        // rather than only for dma-buf sources, because a present from an
        // MIT-SHM pixmap wants the copy phase measured just as much and the
        // branch would cost about what the call does. On this platform
        // `nanoTime` is a vDSO `clock_gettime`, tens of nanoseconds; an X
        // CopyArea request costs microseconds in protocol handling before it
        // reaches here.
        long t0 = System.nanoTime();
        drawable.beginDmaBufRead();
        long t1 = System.nanoTime();
        try {
            if (gcFunction == GraphicsContext.Function.COPY) {
                copyArea(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data);
            }
            else copyAreaOp(srcX, srcY, dstX, dstY, width, height, drawable.getStride(), this.getStride(), drawable.data, this.data, gcFunction.ordinal());
        }
        finally {
            long t2 = System.nanoTime();
            drawable.endDmaBufRead();
            long t3 = System.nanoTime();

            lastSyncStartNanos = t1 - t0;
            lastCopyNanos = t2 - t1;
            lastSyncEndNanos = t3 - t2;
            // Read after `beginDmaBufRead`, deliberately: a descriptor that
            // refuses the ioctl on this very frame latches during that call, and
            // reporting REFUSED for the frame that discovered it is the honest
            // answer — its syncIn includes the failed ioctl and its syncOut is
            // zero because the END half is correctly skipped.
            lastDmaBufSync = drawable.dmaBufFd < 0 ? DmaBufSync.NONE
                    : drawable.dmaBufSyncable ? DmaBufSync.LIVE : DmaBufSync.REFUSED;
        }

        this.data.rewind();
        drawable.data.rewind();

        forceUpdate();
    }

    /**
     * VESSEL: {@code DMA_BUF_IOCTL_SYNC(START | READ)} before a CPU read of
     * this drawable's pixels, if they are a dma-buf mapping.
     *
     * <p>Silent no-op for every other kind of storage, and after the first
     * refusal — see {@link #dmaBufSyncable}. The one log line is deliberate:
     * it fires at most once per drawable, and knowing that the exporter
     * declined the sync is the difference between "we do cache maintenance"
     * and "we believe we do".
     *
     * <p><b>This is a coherency bracket, not a speed one.</b> It cannot change
     * how the buffer is mapped — the memory type was fixed by the exporter's
     * mmap and this ioctl never touches the vma. See
     * {@code sysvshared_memory.c}.
     */
    private void beginDmaBufRead() {
        if (dmaBufFd < 0 || !dmaBufSyncable) return;
        if (!SysVSharedMemory.dmaBufSyncRead(dmaBufFd, true)) {
            dmaBufSyncable = false;
                Log.d(XRequestError.PROTO_TAG, "dma-buf sync unavailable for drawable 0x" + Integer.toHexString(id)
                    + " (fd " + dmaBufFd + "); CPU reads of it are uncoordinated from here on");
        }
    }

    /** VESSEL: the {@code END | READ} half of {@link #beginDmaBufRead}. Only
     * issued if the {@code START} half was, so the exporter always sees a
     * balanced pair. */
    private void endDmaBufRead() {
        if (dmaBufFd < 0 || !dmaBufSyncable) return;
        SysVSharedMemory.dmaBufSyncRead(dmaBufFd, false);
    }

    /**
     * VESSEL: the {@code DMA_BUF_IOCTL_SYNC(START | READ)} half of the most
     * recent {@link #copyArea}, in nanoseconds. See {@link #lastSyncStartNanos}.
     */
    public long getLastSyncStartNanos() {
        return lastSyncStartNanos;
    }

    /**
     * VESSEL: the pixel copy alone — the native call and nothing either side of
     * it — from the most recent {@link #copyArea}, in nanoseconds.
     */
    public long getLastCopyNanos() {
        return lastCopyNanos;
    }

    /** VESSEL: the {@code END | READ} half, in nanoseconds. */
    public long getLastSyncEndNanos() {
        return lastSyncEndNanos;
    }

    /** VESSEL: what the bracket did, so a zero above can be read correctly. */
    public DmaBufSync getLastDmaBufSync() {
        return lastDmaBufSync;
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        if (this.data == null) return;
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)((this.width - x));
        if ((y + height) > this.height) height = (short)((this.height - y));

        fillRect((short)x, (short)y, (short)width, (short)height, color, this.getStride(), this.data);
        this.data.rewind();
        forceUpdate();
    }

    public void drawLines(int color, int lineWidth, short... points) {
        for (int i = 2; i < points.length; i += 2) {
            drawLine(points[i-2], points[i-1], points[i+0], points[i+1], color, (short)lineWidth);
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        if (this.data == null) return;
        x0 = Mathf.clamp(x0, 0, width-lineWidth);
        y0 = Mathf.clamp(y0, 0, height-lineWidth);
        x1 = Mathf.clamp(x1, 0, width-lineWidth);
        y1 = Mathf.clamp(y1, 0, height-lineWidth);

        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.data);

        this.data.rewind();
        forceUpdate();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        if (this.data == null || srcDrawable.data == null || maskDrawable.data == null) return;
        drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, srcDrawable.data, maskDrawable.data, this.data);
        this.data.rewind();

        forceUpdate();
    }

    public void forceUpdate() {
        if (!offscreenStorage) {
            texture.setNeedsUpdate(true);
            if (onDrawListener != null) onDrawListener.run();
        }
    }

    public boolean isUseSharedData() {
        return useSharedData;
    }

    public void setUseSharedData(boolean useSharedData) {
        this.useSharedData = useSharedData;
    }

    private static native void drawBitmap(short width, short height, ByteBuffer srcData, ByteBuffer dstData);

    private static native void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, ByteBuffer srcData, ByteBuffer maskData, ByteBuffer dstData);

    private static native void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, ByteBuffer dstData);

    private static native void copyAreaOp(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcStride, short dstStride, ByteBuffer srcData, ByteBuffer dstData, int gcFunction);

    private static native void fillRect(short x, short y, short width, short height, int color, short stride, ByteBuffer data);

    private static native void drawLine(short x0, short y0, short x1, short y1, int color, short lineWidth, short stride, ByteBuffer data);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer data);
}