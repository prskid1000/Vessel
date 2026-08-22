package com.winlator.renderer;

import android.opengl.GLES20;
import android.os.SystemClock;

import com.winlator.renderer.material.ScreenMaterial;

/**
 * VESSEL: frames the guest never drew, predicted by the GPU.
 *
 * <p>The temporal half of what {@link com.winlator.renderer.material.SGSRMaterial}
 * started. SGSR reconstructs detail across space, from one frame rendered below
 * the panel's resolution; this reconstructs it across time, from two frames
 * rendered below the panel's refresh rate. Both live in the compositor and both
 * are chosen by measurement rather than by a setting alone.
 *
 * <h2>Why this and not the alternatives</h2>
 *
 * <p>The obvious candidate was a Vulkan layer on the guest's presents -- what
 * lsfg-vk does, and what has been shipped on Android elsewhere. Three things
 * ruled it out. It is GPL-3.0, which {@code docs/LICENSING.md} records as
 * deliberately rejected for this project; it needs a proprietary DLL the user
 * must own and we may not redistribute; and it only ever sees Vulkan, so a
 * plain GDI window would get nothing. {@code GL_QCOM_frame_extrapolation} is a
 * registered Khronos extension implemented by the vendor driver, so it carries
 * no licence obligation at all, needs nothing from the user, and sits after
 * composition -- where DXVK, vkd3d, Zink and GDI have already become one image.
 *
 * <p>It is also <em>extrapolation</em>. Interpolating between two real frames
 * means holding the newer one back until the frame after it exists, which costs
 * a full frame of latency by construction. Predicting forward costs none.
 *
 * <h2>What it does to the picture</h2>
 *
 * <p>A predicted frame is a guess, and it is most wrong where the guess has
 * least to go on: edges that were occluded a frame ago and are being revealed
 * now, and anything that changes without moving, like a HUD counter ticking
 * over. It is drawn from frames the user has already seen, so the error is
 * bounded and short-lived -- the next real frame replaces it outright.
 *
 * <p>The touch overlay is safe by construction and not by care taken here: it
 * is an Android {@code Canvas} view stacked above the {@code SurfaceView}, so
 * it is never in the texture this predicts from.
 */
public class FrameExtrapolator {
    static {
        System.loadLibrary("winlator");
    }

    private static native boolean resolveEntryPoint();

    private static native void extrapolateTex2D(int src1, int src2, int output, float scaleFactor);

    /**
     * VESSEL: the extension, asked for rather than assumed.
     *
     * <p>Cached against {@link GLRenderer#contextGeneration()} for the reason
     * {@code SGSRMaterial.isSupported()} is: leaving the desktop and returning
     * destroys the EGL context, and an answer resolved in the old one describes
     * a driver that is no longer the one being drawn to.
     *
     * <p>Both halves are needed. The extension string says the driver has it;
     * {@link #resolveEntryPoint()} says {@code eglGetProcAddress} will actually
     * hand over a function. A driver that advertises the string and returns null
     * would otherwise crash the GL thread on the first synthesised frame.
     */
    public static boolean isSupported() {
        int current = GLRenderer.contextGeneration();
        if (supportedGeneration != current) {
            supportedGeneration = current;
            supported = hasExtension() && resolveEntryPoint();
        }
        return supported;
    }

    private static int supportedGeneration = -1;
    private static boolean supported = false;

    /**
     * Split on spaces rather than {@code contains()}: the extension list is
     * space-delimited and a substring test would also match a longer name that
     * happens to carry this one as a prefix.
     */
    private static boolean hasExtension() {
        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (extensions == null) return false;
        for (String name : extensions.split(" ")) {
            if (name.equals(EXTENSION)) return true;
        }
        return false;
    }

    private static final String EXTENSION = "GL_QCOM_frame_extrapolation";

    /**
     * VESSEL: RGBA8, because the extension will not take BGRA.
     *
     * <p>{@link Texture#format} defaults to {@code GLES11Ext.GL_BGRA}, which is
     * right for a window drawable uploaded from the guest and wrong here: the
     * extension's supported formats are RGBA8, RGB8, R8 and the float variants,
     * and a BGRA source is an {@code INVALID_OPERATION} rather than a wrong
     * colour. {@code GL_RGBA} with {@code GL_UNSIGNED_BYTE} is RGBA8.
     */
    private static final class Rgba8Target extends RenderTarget {
        Rgba8Target() {
            format = GLES20.GL_RGBA;
        }
    }

    private final GLRenderer renderer;
    private final ScreenMaterial blitMaterial = new ScreenMaterial();

    private RenderTarget frameA;
    private RenderTarget frameB;
    private RenderTarget predicted;
    private boolean latestIsB = false;

    private int allocWidth = 0;
    private int allocHeight = 0;
    private int allocGeneration = -1;

    /**
     * Presented frames per real frame: 2 shows one prediction, 4 shows three.
     *
     * <p>The extension predicts to an arbitrary point rather than to a fixed
     * one -- {@code scaleFactor} 1.0 is a full time-delta past the newest real
     * frame, 0.5 the midpoint -- so N-1 predictions at {@code i/N} fill the gap
     * evenly and each is presented at the moment it was aimed at.
     *
     * <p>**Accuracy falls as the aim goes further out.** At 4x the last
     * prediction is three quarters of a frame past anything that was actually
     * drawn, working from the same two frames as the first, so it is guessing
     * three times as far on the same evidence. That is a real cost and it is why
     * the manifest says so rather than offering larger multiples: the useful
     * ceiling is where the multiplied rate meets the panel, and past that the
     * extra frames are discarded by the display anyway.
     */
    private int multiplier = 2;

    /**
     * VESSEL: {@code volatile} because two threads share it.
     *
     * <p>The counter is written only by the GL thread, and read by the delayed
     * runnable on the UI thread to decide whether the frame it was posted for is
     * still the newest one. A stale read costs one skipped prediction.
     */
    private volatile long realFrameCount = 0;

    /**
     * Which prediction the next pass should draw: 0 for none, otherwise the
     * {@code i} of {@code i/N}.
     */
    private final java.util.concurrent.atomic.AtomicInteger pendingIndex =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private long lastRealFrameAt = 0;

    /**
     * Above this gap between real frames, treat the picture as not really moving.
     *
     * <p>**It was 40 ms, and 40 ms is 25 fps, which switched the feature off for
     * every container that needed it.** Measured on device: a container capped at
     * 24 fps composites every 41.7 ms, so every single frame fell the wrong side
     * of the gate and not one prediction was ever scheduled. A slow renderer is
     * not an idle one, and 24 fps is the case frame generation exists for.
     *
     * <p>250 ms -- four frames a second -- is where the guard actually belongs.
     * A genuinely idle desktop damages nothing and composites nothing, so it
     * never reaches here at all; what this is really guarding against is the
     * pathological one-frame-every-few-seconds case, where a prediction aimed
     * a second into the future would be pure invention.
     */
    private static final long IDLE_INTERVAL_MS = 250;

    /** Nothing useful is gained by waking the GL thread sooner than this. */
    private static final long MIN_DELAY_MS = 4;

    public FrameExtrapolator(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public void setMultiplier(int multiplier) {
        this.multiplier = Math.max(2, Math.min(MAX_MULTIPLIER, multiplier));
    }

    /**
     * The largest multiple offered, and it is a judgement rather than a limit.
     *
     * <p>The extension defines no maximum and exposes nothing to query one with
     * -- no new tokens, no new state -- and {@code scaleFactor} is a float that
     * takes any non-zero value, so any multiple is expressible. What actually
     * bounds it is the panel and the arithmetic: a 165 Hz screen cannot show
     * 24 fps at 8x (192), and at that multiple the last prediction aims seven
     * eighths of a frame past anything real, from the same two frames the first
     * one used. 8 is where both of those stop being worth it.
     */
    private static final int MAX_MULTIPLIER = 8;

    /**
     * Whether this pass should draw a predicted frame instead of compositing.
     *
     * <p>Consuming rather than merely reading: the flag exists to turn exactly
     * one {@code requestRender()} into exactly one synthesised frame, and a
     * second pass that found it still set would predict twice from the same
     * pair.
     */
    public int consumeSynthesizedFrame() {
        if (failed) return 0;
        return pendingIndex.getAndSet(0);
    }

    /**
     * Set when the driver refused a prediction, and never cleared for the life of
     * the context.
     *
     * <p>A refusal is a property of the driver and the formats, not of the
     * moment, so retrying every frame would log the same failure sixty times a
     * second and show the user noise while doing it. Real frames keep being
     * composited through the offscreen target either way, which costs one blit
     * and is correct.
     */
    private volatile boolean failed = false;

    /**
     * Point the compositor at the offscreen target instead of the screen.
     *
     * @return false if the targets could not be allocated, in which case the
     *     caller must composite straight to the screen as it always did.
     */
    public boolean beginRealFrame() {
        if (!ensureTargets()) return false;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeTarget().getFramebuffer());
        return true;
    }

    /** Show the frame that was just composited, and arrange for one that was not. */
    public void endRealFrame() {
        RenderTarget written = writeTarget();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        blit(written.getTextureId());

        latestIsB = !latestIsB;
        realFrameCount++;

        long now = SystemClock.uptimeMillis();
        long interval = lastRealFrameAt == 0 ? 0 : now - lastRealFrameAt;
        lastRealFrameAt = now;
        scheduleSynthesis(interval);
    }

    /**
     * Predict the {@code index}-th frame of this interval from the last two real
     * ones and show it.
     *
     * <p>One output target for all of them rather than one each: a prediction is
     * blitted before the next is asked for, and GL orders the two against each
     * other on the same context, so the texture is free again by the time it is
     * rewritten.
     */
    public void presentSynthesizedFrame(int index) {
        if (!ensureTargets() || realFrameCount < 2) return;
        if (index < 1 || index >= multiplier) return;
        // VESSEL: seed the target with the newest real frame before predicting
        // into it.
        //
        // **An unwritten target is not blank, it is noise.** RenderTarget
        // allocates with glTexImage2D(..., null), so until something writes it
        // the texture holds whatever that page of GPU memory last contained --
        // which presents as dense colour speckle over the whole screen, twice
        // observed on device. glGetError cannot save us from that: it reports a
        // call the driver *rejected*, and an extension that quietly does nothing
        // returns GL_NO_ERROR having written not a pixel.
        //
        // So the worst case is made harmless rather than merely detected. Seeded
        // this way, a prediction that never happens shows the frame before it --
        // a repeat, visible as a moment of judder and nothing worse -- and the
        // counters still say a prediction was presented, which is what separates
        // "the driver did nothing" from "nothing was scheduled".
        //
        // One extra full-screen blit per predicted frame. That is a real cost and
        // it buys the guarantee that this feature can never put garbage on the
        // screen, which is worth more than the blit.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, predicted.getFramebuffer());
        blit(latestTarget().getTextureId());

        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        extrapolateTex2D(previousTarget().getTextureId(), latestTarget().getTextureId(),
                         predicted.getTextureId(), (float)index / (float)multiplier);

        // VESSEL: the call either wrote the texture or it did not, and the
        // difference is not visible in the output -- an untouched target still
        // has whatever the allocation left in it, which is uninitialised GPU
        // memory and reads as dense colour noise. So the error is checked rather
        // than assumed, once per context, and a failure switches the whole thing
        // off instead of presenting garbage every other frame.
        final int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            android.util.Log.e("FrameExtrapolator",
                "glExtrapolateTex2DQCOM failed with 0x" + Integer.toHexString(error)
                    + " (src1=" + previousTarget().getTextureId()
                    + " src2=" + latestTarget().getTextureId()
                    + " out=" + predicted.getTextureId()
                    + " scale=" + ((float)index / (float)multiplier)
                    + " " + allocWidth + "x" + allocHeight + ") -- frame generation off");
            failed = true;
            return;
        }

        // VESSEL: say once that a prediction actually happened.
        //
        // Silence is ambiguous on its own: the idle gate means an unchanging
        // desktop schedules nothing, so "no errors logged" reads identically to
        // "never ran". One line at the first success separates the two, and it
        // is one line per context rather than per frame.
        presented++;
        if (!announced) {
            announced = true;
            android.util.Log.i("FrameExtrapolator",
                "first prediction OK at " + allocWidth + "x" + allocHeight
                    + ", " + multiplier + "x");
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        blit(predicted.getTextureId());
    }

    private boolean announced = false;

    /**
     * Ask for one extra pass, half way to where the next real frame is expected.
     *
     * <p>The stamp is what keeps this from fighting the guest. If a real frame
     * lands before the timer does, the prediction it was posted for is already
     * history and drawing it would show the user a guess at a frame they have
     * been given for real.
     */
    private void scheduleSynthesis(long interval) {
        if (realFrameCount < 2) { report("warming up"); return; }
        if (interval <= 0) { report("no interval yet"); return; }
        if (interval > IDLE_INTERVAL_MS) { report("idle, " + interval + " ms between frames"); return; }

        final long stamp = realFrameCount;
        for (int i = 1; i < multiplier; i++) {
            final int index = i;
            long delay = Math.max(MIN_DELAY_MS, (interval * i) / multiplier);
            renderer.xServerView.postDelayed(() -> {
                if (realFrameCount != stamp) {
                    cancelled++;
                    return;
                }
                pendingIndex.set(index);
                renderer.xServerView.requestRender();
            }, delay);
        }
        scheduled += multiplier - 1;
        report("scheduled, " + interval + " ms between frames");
    }

    /**
     * VESSEL: say once a second why predictions are or are not happening.
     *
     * <p>Silence has already cost two wrong guesses about this. Nothing here is
     * observable from the outside -- a prediction that is scheduled and then
     * cancelled looks exactly like one that was never scheduled, and both look
     * exactly like a driver that refused -- so the counters are printed rather
     * than reasoned about. Rate-limited to one line a second because the thing
     * being measured runs at the frame rate.
     */
    private void report(String reason) {
        long now = SystemClock.uptimeMillis();
        if (now - lastReportAt < 1000) return;
        lastReportAt = now;
        android.util.Log.i("FrameExtrapolator",
            reason + " -- " + multiplier + "x, scheduled " + scheduled + ", cancelled " + cancelled
                + ", presented " + presented);
    }

    private long lastReportAt = 0;
    private long scheduled = 0;
    private volatile long cancelled = 0;
    private long presented = 0;

    /** The older of the pair, which is the one the next real frame overwrites. */
    private RenderTarget writeTarget() {
        return latestIsB ? frameA : frameB;
    }

    private RenderTarget latestTarget() {
        return latestIsB ? frameB : frameA;
    }

    private RenderTarget previousTarget() {
        return latestIsB ? frameA : frameB;
    }

    /**
     * Allocate the three targets, or re-allocate them when the surface or the
     * context has changed under us.
     *
     * <p>The history is dropped on every reallocation, not carried across. Two
     * frames captured at different sizes are not a sequence the extension can
     * read, and the pair is refilled within two composites.
     */
    private boolean ensureTargets() {
        final int generation = GLRenderer.contextGeneration();
        final int width = renderer.surfaceWidth;
        final int height = renderer.surfaceHeight;
        if (width <= 0 || height <= 0) return false;

        if (frameA != null && allocWidth == width && allocHeight == height
                && allocGeneration == generation) {
            return true;
        }

        // Only delete when the objects can still be named. A new context
        // generation means the old ones went with the context that held them,
        // and deleting those ids would be deleting whatever now has them.
        release(frameA != null && allocGeneration == generation);

        frameA = allocate(width, height);
        frameB = allocate(width, height);
        predicted = allocate(width, height);

        allocWidth = width;
        allocHeight = height;
        allocGeneration = generation;
        latestIsB = false;
        realFrameCount = 0;
        lastRealFrameAt = 0;
        failed = false;
        announced = false;
        return true;
    }

    private static RenderTarget allocate(int width, int height) {
        RenderTarget target = new Rgba8Target();
        target.allocateFramebuffer(width, height);
        return target;
    }

    private void release(boolean deleteObjects) {
        if (deleteObjects) {
            int[] framebuffers = new int[3];
            int[] textures = new int[3];
            RenderTarget[] targets = {frameA, frameB, predicted};
            for (int i = 0; i < targets.length; i++) {
                framebuffers[i] = targets[i] != null ? targets[i].getFramebuffer() : 0;
                textures[i] = targets[i] != null ? targets[i].getTextureId() : 0;
            }
            GLES20.glDeleteFramebuffers(framebuffers.length, framebuffers, 0);
            GLES20.glDeleteTextures(textures.length, textures, 0);
        }
        frameA = null;
        frameB = null;
        predicted = null;
    }

    /**
     * Draw a texture over the whole surface.
     *
     * <p>{@code viewportNeedsUpdate} is set rather than the letterbox viewport
     * restored here: the next composite is what knows which viewport it wants,
     * and it already recomputes one when told to.
     */
    private void blit(int textureId) {
        GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        renderer.viewportNeedsUpdate = true;

        // Blending off for this one draw, and it is not a micro-optimisation.
        // The renderer enables SRC_ALPHA/ONE_MINUS_SRC_ALPHA once at context
        // creation and leaves it on, which is right for compositing windows over
        // each other and wrong for a whole-surface copy: the letterbox is clear
        // colour at alpha zero, and a prediction's alpha is whatever the
        // extension wrote. Blended, either one lets the previous screen show
        // through -- a second, older image under the current one, which is
        // exactly what frame generation must not produce.
        GLES20.glDisable(GLES20.GL_BLEND);

        blitMaterial.use();
        renderer.quadVertices.bind(blitMaterial.programId);
        blitMaterial.setUniformVec2(blitMaterial.uniforms.resolution,
                                    renderer.surfaceWidth, renderer.surfaceHeight);
        blitMaterial.setUniformBool(blitMaterial.uniforms.flipY, false);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        blitMaterial.setUniformInt(blitMaterial.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glEnable(GLES20.GL_BLEND);

        // The blit rebinds the program, so whatever the window pass had bound is
        // no longer current. Saying so is cheaper than the alternative, which is
        // a window pass that skips its own bind and draws with this shader.
        renderer.invalidateBoundWindowMaterial();
    }
}
