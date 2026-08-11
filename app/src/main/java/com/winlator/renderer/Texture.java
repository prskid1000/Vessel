package com.winlator.renderer;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

public class Texture {
    /**
     * VESSEL: the EGL context {@link #textureId} was generated in.
     *
     * Leaving the desktop and coming back destroys the SurfaceView, and with it
     * the EGL context and every texture object in it. What survives is this
     * object, still holding a non-zero {@link #textureId} — so {@link
     * #isAllocated()} said yes, {@link #updateFromDrawable()} skipped both the
     * allocate and the upload, and the renderer bound a texture name that no
     * longer refers to anything.
     *
     * A generation rather than a walk over every drawable: the renderer has no
     * enumeration of them, DrawableManager's map being private, and a texture is
     * the only thing that knows whether its own id is stale. The counter itself
     * lives on {@link GLRenderer}, which owns the callback that knows the
     * context is new, and is shared with the two other caches that have exactly
     * this problem.
     */
    private int generation = -1;

    protected int textureId = 0;
    protected int wrapS = GLES20.GL_CLAMP_TO_EDGE;
    protected int wrapT = GLES20.GL_CLAMP_TO_EDGE;
    protected int magFilter = GLES20.GL_LINEAR;
    protected int minFilter = GLES20.GL_LINEAR;
    protected int format = GLES11Ext.GL_BGRA;
    protected boolean needsUpdate = true;
    private boolean flipY = false;
    protected Drawable owner;

    public Texture(Drawable owner) {
        this.owner = owner;
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];
        // VESSEL: stamp the id with the context it came from.
        generation = GLRenderer.contextGeneration();
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
    }

    public void allocateTexture(short width, short height, ByteBuffer data) {
        generateTextureId();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (data != null) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        }

        setTextureParameters();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public Drawable getOwner() {
        return owner;
    }

    public void setOwner(Drawable owner) {
        this.owner = owner;
    }

    public boolean isFlipY() {
        return flipY;
    }

    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public int getWrapS() {
        return wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return needsUpdate;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
    }

    public void updateFromDrawable() {
        if (owner == null || owner.getData() == null) return;

        ByteBuffer data = owner.getData();
        if (!isAllocated()) {
            allocateTexture(owner.width, owner.height, data);
        }
        else if (needsUpdate) {
            // VESSEL: timed, because the size of this cost has only ever been assumed.
            final boolean report = Log.isLoggable(UPLOAD_TAG, Log.DEBUG);
            final long t0 = report ? System.nanoTime() : 0;

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, owner.width, owner.height, format, GLES20.GL_UNSIGNED_BYTE, data);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            needsUpdate = false;

            if (report) recordUpload(System.nanoTime() - t0, owner.width, owner.height);
        }
    }

    /**
     * VESSEL: what the whole-window re-upload actually costs, per window size.
     *
     * <p>{@code docs/TODO.md} item 27 proposes deleting this call by backing
     * every window's content with a {@link GPUImage}, on the strength of "3.6 MB
     * of CPU&rarr;GPU upload per composited frame disappears". That number is an
     * arithmetic product of a resolution and a pixel size, not a measurement,
     * and the arithmetic is only the prize if this line runs for the window that
     * matters. It very likely does not: {@code PresentExtension.selectInput} and
     * {@code DRI3Extension} already swap a presenting window's content to a
     * {@code GPUImage}, whose {@code updateFromDrawable} is a no-op — so for a
     * DXVK client on the Turnip path the big window has been zero-copy all
     * along, and what is left here is Wine's desktop and whatever GDI draws.
     *
     * <p>So this counts, rather than reasons: how many uploads, how long they
     * take, and at what sizes. If the total is a few hundred microseconds a
     * second spread over small windows, item 27 is worth closing with a number
     * instead of building; if a 1280x720 window is really being re-uploaded at
     * frame rate, the item is worth its medium risk. Either way the answer is
     * one session away rather than one argument away.
     *
     * <p>Off unless {@code adb shell setprop log.tag.VesselUpload DEBUG}, and
     * the clock is not read at all when it is off — this sits inside the
     * compositor's per-frame path, which is the one place a diagnostic must not
     * become the thing it measures.
     *
     * <p>Plain statics with no synchronisation: every caller of {@link
     * #updateFromDrawable()} is already on the GL thread, holding the owning
     * drawable's {@code renderLock}, and a counter that needed a lock here would
     * be measuring the lock.
     */
    private static final String UPLOAD_TAG = "VesselUpload";
    private static final int UPLOAD_REPORT_EVERY = 120;
    private static long uploadCount;
    private static long uploadNanos;
    private static long uploadMaxNanos;
    private static long uploadBytes;

    private static void recordUpload(long dt, short width, short height) {
        uploadNanos += dt;
        uploadBytes += (long)width * height * 4;
        if (dt > uploadMaxNanos) uploadMaxNanos = dt;
        if (++uploadCount % UPLOAD_REPORT_EVERY != 0) return;

        Log.d(UPLOAD_TAG, "texSubImage x" + uploadCount
                + " mean=" + (uploadNanos / uploadCount / 1000) + "us"
                + " max=" + (uploadMaxNanos / 1000) + "us"
                + " last=" + (dt / 1000) + "us"
                + " total=" + (uploadNanos / 1000000) + "ms"
                + " " + (uploadBytes >> 20) + "MiB"
                + " last_size=" + width + "x" + height);
    }

    /**
     * VESSEL: allocated means allocated <em>in the context we are drawing in</em>.
     *
     * An id from a destroyed context is not a texture; returning false for it is
     * what sends {@link #updateFromDrawable()} back down the allocate path so the
     * window is re-uploaded from its ByteBuffer.
     */
    public boolean isAllocated() {
        return textureId > 0 && generation == GLRenderer.contextGeneration();
    }

    public int getTextureId() {
        return textureId;
    }

    public void copyFromReadBuffer(short width, short height) {
        if (!isAllocated()) allocateTexture(width, height, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, width, height, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glFlush();
    }

    public void destroy() {
        if (textureId > 0) {
            // VESSEL: only delete an id the current context actually issued.
            // glGenTextures starts from 1 again in a fresh context, so a stale
            // id is not merely dead — it is very likely to have been reissued to
            // somebody else's live texture, and deleting it would blank an
            // unrelated window.
            if (generation == GLRenderer.contextGeneration()) {
                int[] textureIds = new int[]{textureId};
                GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            }
            textureId = 0;
        }
    }
}
