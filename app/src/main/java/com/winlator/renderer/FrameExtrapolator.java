package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
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
     * VESSEL: a colour target the extension will actually accept.
     *
     * <p>Not {@link RenderTarget}, and the difference is the format. That class
     * allocates with {@code glTexImage2D} using {@link Texture#format} as both
     * the internal format and the pixel format, which defaults to
     * {@code GLES11Ext.GL_BGRA} -- right for a window drawable uploaded from the
     * guest, and not one of the formats this extension lists. Setting it to
     * {@code GL_RGBA} gets closer but is still an *unsized* internal format: the
     * spec asks for RGBA8 specifically, and a driver that checks for a sized
     * format does not get one.
     *
     * <p>So the storage is immutable and sized: {@code glTexStorage2D} with
     * {@code GL_RGBA8}, one level. That is unambiguous about what the texture is,
     * which matters for an entry point that writes to a texture by name rather
     * than through the pipeline and cannot renegotiate the format on the way.
     */
    private static final class ColourTarget {
        int texture;
        int framebuffer;

        void allocate(int width, int height) {
            final int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            texture = names[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                                   GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                                   GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                                   GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                                   GLES20.GL_LINEAR);

            GLES20.glGenFramebuffers(1, names, 0);
            framebuffer = names[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                          GLES20.GL_TEXTURE_2D, texture, 0);
            // Once, at allocation, so no target can ever present the contents of
            // whatever last held this memory. The sample does not write its
            // output texture per frame and neither does this any more -- but an
            // allocation that is never cleared at all is how the speckle got on
            // screen in the first place.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        void release() {
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[] {texture}, 0);
            framebuffer = 0;
            texture = 0;
        }
    }

    private final GLRenderer renderer;
    private final ScreenMaterial blitMaterial = new ScreenMaterial();

    private ColourTarget frameA;
    private ColourTarget frameB;
    private ColourTarget predicted;
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
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeTarget().framebuffer);
        return true;
    }

    /** Show the frame that was just composited, and arrange for one that was not. */
    public void endRealFrame() {
        ColourTarget written = writeTarget();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        blit(written.texture);

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
        if (index != 1) return;
        // VESSEL: matched to Qualcomm's own AMFE sample, exactly.
        //
        // That sample is the only working reference for this call, and what it
        // does is narrower than what was built here:
        //
        //     m_glExtrapolateTex2DQCOM(colour[n % 2], colour[n % 2 ? 0 : 1],
        //                              m_extrapolatedTexture, .5f);
        //
        // One call per pair of traditionally rendered frames, always at 0.5, and
        // the output texture is never written by the application between calls.
        // This was doing three calls on the *same* pair at 4x, each into a target
        // it had just blitted a seed frame into -- so every assumption the sample
        // makes was being broken at once, and the result was speckle.
        //
        // Higher multiples are held back rather than deleted: they need the same
        // pair to answer more than one scaleFactor, which nothing in the spec
        // promises and nothing in the sample demonstrates. Prove one prediction
        // first.
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        extrapolateTex2D(previousTarget().texture, latestTarget().texture,
                         predicted.texture, 0.5f);

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
                    + " (src1=" + previousTarget().texture
                    + " src2=" + latestTarget().texture
                    + " out=" + predicted.texture
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
        dumpOnce();
        // Once per context, and late enough that the frames hold a real scene.
        // See MotionProbe: the sibling extension is worth building on only if it
        // actually estimates motion, and that is a question with a measurable
        // answer rather than one to reason about.
        if (presented >= DUMP_AFTER) {
            MotionProbe.run(renderer, previousTarget().texture, latestTarget().texture,
                            allocWidth, allocHeight);
        }
        if (!announced) {
            announced = true;
            android.util.Log.i("FrameExtrapolator",
                "first prediction OK at " + allocWidth + "x" + allocHeight
                    + ", " + multiplier + "x");
        }

        blit(predicted.texture);
    }

    private boolean announced = false;

    /**
     * VESSEL: read the three textures back to disk, once, and look at them.
     *
     * <p>The extension is a black box -- one entry point, no parameters, no error
     * and nothing to query -- and five fixes aimed at it were all wrong. What has
     * never been done is to look at what it actually wrote, and the difference
     * matters: an unrecoverable mess means building the prediction ourselves out
     * of {@code GL_QCOM_motion_estimation}, while a recognisable picture in the
     * wrong channel order is a swizzle.
     *
     * <p>The two sources are dumped alongside it as the control. If those are
     * wrong the fault was never in the extension at all.
     *
     * <p>Raw RGBA rather than PNG: there is no encoder here worth linking for a
     * diagnostic, and the reader on the other end can reshape bytes. Once per
     * context, because each file is width * height * 4 bytes.
     */
    private void dumpOnce() {
        if (dumped) return;
        // Late, not on the first prediction. The first one happens while the
        // desktop is still black -- a dump taken there shows two empty sources
        // and says nothing about what the extension does with a real frame.
        if (!DUMP_ENABLED || presented < DUMP_AFTER) return;
        dumped = true;
        try {
            java.io.File dir = renderer.xServerView.getContext().getFilesDir();
            write(new java.io.File(dir, "fx-previous.rgba"), previousTarget());
            write(new java.io.File(dir, "fx-latest.rgba"), latestTarget());
            write(new java.io.File(dir, "fx-predicted.rgba"), predicted);
            android.util.Log.i("FrameExtrapolator",
                "dumped " + allocWidth + "x" + allocHeight + " RGBA to " + dir);
        } catch (Throwable e) {
            android.util.Log.e("FrameExtrapolator", "dump failed", e);
        }
    }

    private void write(java.io.File file, ColourTarget target) throws java.io.IOException {
        final java.nio.ByteBuffer pixels =
            java.nio.ByteBuffer.allocateDirect(allocWidth * allocHeight * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer);
        GLES20.glReadPixels(0, 0, allocWidth, allocHeight, GLES20.GL_RGBA,
                            GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        pixels.rewind();
        final byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private boolean dumped = false;

    /**
     * Off, and kept rather than deleted, because it is what settled the question.
     *
     * <p>Five fixes aimed at the call were all wrong, and this is what showed
     * why: read the three textures back and look at them. The sources held a real
     * scene -- 14,577 unique colours, mean (46,54,56) -- and the output was an
     * 8-pixel ramp, {@code 16 48 80 112 143 175 207 239} repeating across every
     * row, with a standard deviation of 2.3 and 5.2 in R and G across 8x8 blocks.
     * A fixed pattern, not a frame, and nothing derived from the inputs at all.
     *
     * <p>Turn it on again to validate the motion vectors if the estimation path
     * is ever built: the same instrument answers the same question there, and
     * guessing at a black box is what it exists to stop.
     */
    private static final boolean DUMP_ENABLED = false;

    /** Predictions to let pass before dumping, so the game is really drawing. */
    private static final long DUMP_AFTER = 120;

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
        // One, not multiplier - 1. See presentSynthesizedFrame: the sample makes
        // a single prediction per pair and the higher multiples are held back
        // until that one is proven.
        for (int i = 1; i < 2; i++) {
            final int index = i;
            long delay = Math.max(MIN_DELAY_MS, interval / 2);
            renderer.xServerView.postDelayed(() -> {
                if (realFrameCount != stamp) {
                    cancelled++;
                    return;
                }
                pendingIndex.set(index);
                renderer.xServerView.requestRender();
            }, delay);
        }
        scheduled += 1;
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
    private ColourTarget writeTarget() {
        return latestIsB ? frameA : frameB;
    }

    private ColourTarget latestTarget() {
        return latestIsB ? frameB : frameA;
    }

    private ColourTarget previousTarget() {
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

    private static ColourTarget allocate(int width, int height) {
        ColourTarget target = new ColourTarget();
        target.allocate(width, height);
        return target;
    }

    private void release(boolean deleteObjects) {
        if (deleteObjects) {
            if (frameA != null) frameA.release();
            if (frameB != null) frameB.release();
            if (predicted != null) predicted.release();
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
