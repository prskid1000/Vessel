package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.SignTestMaterial;
import com.winlator.renderer.material.WarpMaterial;

/**
 * VESSEL: frames the guest never drew, built from what the compositor knows.
 *
 * <p>The temporal counterpart to {@link com.winlator.renderer.material.SGSRMaterial}:
 * that reconstructs across space, from a frame rendered below the panel's
 * resolution; this reconstructs across time, from frames arriving below its
 * refresh rate.
 *
 * <h2>Why this is not a call to the driver</h2>
 *
 * <p>It was, and the driver lied. {@code GL_QCOM_frame_extrapolation} is
 * advertised on this device, accepts the call, returns {@code GL_NO_ERROR} and
 * writes an eight-pixel ramp -- {@code 16 48 80 112 143 175 207 239} repeating
 * across every row, standard deviation 2.3 in R across 8x8 blocks, no scene
 * structure at any scale. Proved by reading the destination back after five
 * fixes aimed at the call were all wrong. The spec declines to define output
 * quality and there is no conformance test, so a placeholder implementation is
 * undetectable except by looking, and nobody had looked: no public code anywhere
 * calls that entry point outside Qualcomm's own sample, whose only published
 * numbers are from a 2021 Adreno 660.
 *
 * <h2>The two tiers, cheapest first</h2>
 *
 * <p><b>Tier 0 -- window translation.</b> We are the compositor. When a window
 * moves we know the translation exactly, so a synthesised frame re-composites
 * the same window textures at carried-forward positions. Not an estimate: the
 * motion, replayed a fraction of a frame on. No block matching, no warping, no
 * inpainting, and therefore none of their artefacts. This is available to us and
 * to nobody else working from a screen capture, and for a Windows desktop --
 * dragged windows, sliding panels -- it is most of the motion there is.
 *
 * <p><b>Tier 1 -- hardware motion estimation and a backward warp.</b> When the
 * windows are still but their contents are not, the picture has to be examined.
 * {@code GL_QCOM_motion_estimation} is the sibling extension, and unlike the
 * other one it works: measured on this device at 44,590 non-zero vectors across
 * a moving scene, x in [-113, 90], y in [-110, 106]. It is a fixed-function
 * block matcher, so the expensive half of frame generation is free, and what is
 * left to write is a gather.
 *
 * <p>Nothing else is attempted. A tier that cannot run yields, and a frame that
 * cannot be synthesised is simply not drawn -- which costs a little smoothness
 * and never costs correctness.
 */
public class FrameSynthesizer implements FramePacer.Target {
    private static final String TAG = "FrameSynthesizer";

    static {
        System.loadLibrary("winlator");
    }

    private static native boolean resolveMotionEntryPoint();

    private static native void texEstimateMotion(int ref, int target, int output);

    private static final int MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM = 0x8C90;
    private static final int MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM = 0x8C91;
    private static final String MOTION_EXTENSION = "GL_QCOM_motion_estimation";

    /**
     * Whether tier 1 is available, asked of the driver rather than assumed.
     *
     * <p>Cached against {@link GLRenderer#contextGeneration()} for the reason
     * {@code SGSRMaterial.isSupported()} is: a new EGL context is a different
     * driver state, and an answer from the old one describes something that is no
     * longer being drawn to. Both halves are needed -- the string says the driver
     * claims it, the resolve says {@code eglGetProcAddress} will hand over a
     * function, and the extrapolation work is what proved those are different
     * questions from "does it work".
     */
    public static boolean motionEstimationSupported() {
        final int generation = GLRenderer.contextGeneration();
        if (motionGeneration != generation) {
            motionGeneration = generation;
            motionSupported = false;
            final String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (extensions != null) {
                for (String name : extensions.split(" ")) {
                    if (name.equals(MOTION_EXTENSION)) { motionSupported = true; break; }
                }
            }
            if (motionSupported) motionSupported = resolveMotionEntryPoint();
        }
        return motionSupported;
    }

    private static int motionGeneration = -1;
    private static boolean motionSupported = false;

    /**
     * Luma, because the extension requires it.
     *
     * <p>Reference and target must be {@code GL_R8}, and the spec says outright
     * that estimation tracks brightness. Perceptual weights rather than a plain
     * average: matching on what the eye calls brightness makes the block
     * differences perceptually uniform, which is the same reason FidelityFX
     * converts to L* before its own search.
     */
    private static final class LumaMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return String.join("\n",
                "precision mediump float;",
                "uniform sampler2D screenTexture;",
                "varying vec2 vUV;",
                "void main() {",
                    "vec3 c = texture2D(screenTexture, vUV).rgb;",
                    "gl_FragColor = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);",
                "}"
            );
        }
    }

    /**
     * A colour, luma or vector target with sized immutable storage.
     *
     * <p>Not {@link RenderTarget}: that allocates with {@code glTexImage2D} using
     * {@link Texture#format} for both the internal and pixel format, defaulting to
     * {@code GLES11Ext.GL_BGRA} -- right for a guest window upload and not a
     * format any of this accepts. {@code glTexStorage2D} with a sized format is
     * unambiguous, which matters for an extension that writes to a texture by
     * name rather than through the pipeline.
     */
    private static final class Target {
        int texture;
        int framebuffer;
        int width;
        int height;

        void allocate(int width, int height, int sizedFormat, int filter) {
            this.width = width;
            this.height = height;
            final int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            texture = names[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, sizedFormat, width, height);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glGenFramebuffers(1, names, 0);
            framebuffer = names[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                          GLES20.GL_TEXTURE_2D, texture, 0);
            // Cleared once at allocation. Nothing may ever present the contents of
            // whatever last held this memory -- that is how the speckle got on
            // screen while the extrapolation call was still trusted.
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
    private final FramePacer pacer;
    private final ScreenMaterial blitMaterial = new ScreenMaterial();
    private final LumaMaterial lumaMaterial = new LumaMaterial();
    private final WarpMaterial warpMaterial = new WarpMaterial();
    private final SignTestMaterial signTestMaterial = new SignTestMaterial();

    private final GpuTimer captureTimer = new GpuTimer("tier1 capture+blit");
    private final GpuTimer lumaTimer = new GpuTimer("tier1 luma");
    private final GpuTimer estimateTimer = new GpuTimer("tier1 estimate");
    private final GpuTimer warpTimer = new GpuTimer("tier1 warp");
    private final GpuTimer tier0Timer = new GpuTimer("tier0 recomposite");

    private Target vectors;
    private int blockX = 8, blockY = 8;

    private int allocWidth = 0;
    private int allocHeight = 0;
    private int allocGeneration = -1;

    private int multiple = 2;
    /** Whether the field estimated at the last real frame is usable. */
    private boolean motionValid = false;
    private volatile long realFrames = 0;
    private long lastRealFrameNanos = 0;
    private long smoothedInterval = 0;

    /**
     * Weight of the running mean of the real-frame interval.
     *
     * <p>Four, which is short enough to follow a genuine change in frame
     * rate within a few frames and long enough that one late frame does not
     * move the aim. Integer arithmetic throughout -- these are nanoseconds
     * and a double buys nothing at that scale.
     */
    private static final long SMOOTHING = 4;
    private final java.util.concurrent.atomic.AtomicInteger pending =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Above this gap between real frames the picture is not really moving.
     *
     * <p>It was 40 ms once, which is 25 fps, and that switched the whole feature
     * off for every container that needed it: a 24 fps cap composites every
     * 41.7 ms, so every frame fell the wrong side of the gate and not one
     * prediction was ever scheduled. A slow renderer is not an idle one. 250 ms
     * is four frames a second -- below that a synthesised frame is aimed so far
     * from anything real that it is invention rather than prediction.
     */
    private static final long IDLE_NANOS = 250_000_000L;

    private long tier0Frames = 0;
    private long tier1Frames = 0;
    private long skipped = 0;
    private long reportedAt = 0;

    public FrameSynthesizer(GLRenderer renderer) {
        this.renderer = renderer;
        this.pacer = new FramePacer(this);
    }

    public void setMultiple(int multiple) {
        this.multiple = Math.max(2, Math.min(8, multiple));
    }

    @Override
    public long realFrameCount() {
        return realFrames;
    }

    @Override
    public void onSynthesisDue(int index) {
        pending.set(index);
        renderer.xServerView.requestRender();
    }

    /** Consume a pending request, so one schedule yields exactly one frame. */
    public int consumePending() {
        return pending.getAndSet(0);
    }

    /**
     * Point the compositor at the offscreen colour target.
     *
     * @return false when the targets could not be allocated, in which case the
     *     caller composites to the screen exactly as it did before this existed.
     */
    public boolean beginRealFrame() {
        if (!ensureTargets()) return false;
        captureTimer.begin();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeColour().framebuffer);
        return true;
    }

    /** Present the real frame, remember what it looked like, and queue the rest. */
    public void endRealFrame() {
        final Target written = writeColour();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        blit(blitMaterial, written.texture, renderer.surfaceWidth, renderer.surfaceHeight);
        captureTimer.end();

        // Luma for this frame, so the pair is ready without re-deriving the older
        // one every time. Only when tier 1 can actually use it.
        if (motionEstimationSupported()) {
            lumaTimer.begin();
            renderToTarget(lumaMaterial, written.texture, writeLuma());
            lumaTimer.end();
        }

        // The slot just written is now the newest. Everything above read
        // the old arrangement; everything below reads the new one.
        newest = oldest();
        realFrames++;
        renderer.latchWindowPositions();

        // Estimated once here, for the whole interval that follows. Every
        // prediction between now and the next real frame shares this pair, so
        // every prediction shares these vectors.
        motionValid = realFrames >= 2 && motionEstimationSupported() && estimateMotion();
        if (motionValid) runSignTestOnce();

        final long now = System.nanoTime();
        final long interval = lastRealFrameNanos == 0 ? 0 : now - lastRealFrameNanos;
        lastRealFrameNanos = now;

        // **Aimed with a smoothed interval, because the last one is noise.**
        // Measured at 2x: one prediction per real frame was scheduled and only
        // one in three survived -- the rest were cancelled because the next real
        // frame arrived before the prediction's vsync. The compositor draws on
        // guest damage, which is bursty, so consecutive gaps differ by a factor
        // of two or three and an aim computed from a single gap is wrong most of
        // the time. A running mean is what the gap actually is.
        //
        // It is not a cancellation bug. Presenting a prediction of a moment that
        // has already been drawn for real is worse than presenting nothing, so
        // the check is right; what was wrong was aiming so badly that it kept
        // firing.
        if (interval > 0 && interval <= IDLE_NANOS) {
            smoothedInterval = smoothedInterval == 0
                ? interval
                : (smoothedInterval * (SMOOTHING - 1) + interval) / SMOOTHING;
        }

        if (realFrames >= 2 && smoothedInterval > 0 && smoothedInterval <= IDLE_NANOS) {
            pacer.schedule(smoothedInterval, multiple);
        }
        report();
    }

    /**
     * Draw one synthesised frame, choosing the cheapest tier that applies.
     *
     * @param index which of the N-1 frames this is; t is index/N
     */
    public void presentSynthesized(int index) {
        if (!ensureTargets() || realFrames < 2) return;
        if (index < 1 || index >= multiple) return;
        final float t = (float)index / (float)multiple;

        // Tier 0. Exact, and cheap enough that it is worth testing for first.
        if (renderer.anyWindowMoved()) {
            tier0Timer.begin();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            renderer.drawSynthesizedFrame(t);
            tier0Timer.end();
            tier0Frames++;
            return;
        }

        // Tier 1. The field was estimated once in endRealFrame, from the pair
        // this whole interval shares -- re-running it here produced the identical
        // vectors N-1 times per real frame for nothing.
        if (motionValid) {
            warpTimer.begin();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            warp(latestColour(), t);
            warpTimer.end();
            tier1Frames++;
            return;
        }

        // **Neither tier applies, and this path must still draw.** GLSurfaceView
        // swaps the buffer after onDrawFrame whether or not anything was rendered
        // into it, so returning here does not skip a frame -- it presents an
        // unwritten back buffer, which on a double-buffered surface holds the
        // frame from two swaps ago. The display then alternates between the
        // current picture and an old one, which reads as flicker and looks like a
        // fault in the synthesis rather than in the decision not to synthesise.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        blit(blitMaterial, latestColour().texture,
             renderer.surfaceWidth, renderer.surfaceHeight);
        skipped++;
    }

    /**
     * Run the hardware block matcher over the luma pair.
     *
     * @return false if the vectors could not be produced, in which case no warp
     *     should be attempted -- an unwritten field would warp by garbage.
     */
    private boolean estimateMotion() {
        if (luma[0] == null || vectors == null) return false;
        estimateTimer.begin();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        texEstimateMotion(previousLuma().texture, latestLuma().texture, vectors.texture);
        final int error = GLES20.glGetError();
        estimateTimer.end();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glTexEstimateMotionQCOM failed 0x" + Integer.toHexString(error));
            return false;
        }
        return true;
    }

    /**
     * VESSEL: settle which way the motion field points, once, by measuring.
     *
     * <p>See {@link SignTestMaterial}. Both real frames are in hand, so the field
     * can be tested against what it claims to describe rather than argued about:
     * reconstruct the newer frame from the older under each sign and keep the one
     * with the lower error. The wrong sign displaces every block by twice its
     * true motion, so the two numbers are not close.
     *
     * <p>Deferred until the picture is actually moving. Two identical frames
     * reconstruct perfectly under either sign and the test would report a tie,
     * which is how a static menu screen would have taught us the wrong answer.
     */
    private void runSignTestOnce() {
        if (signTested || luma[0] == null) return;

        final float scaleX = 1f / Math.max(1, luma[0].width);
        final float scaleY = 1f / Math.max(1, luma[0].height);
        // **The control, and without it the comparison is uninterpretable.** A
        // direction of zero compensates for nothing, so this is simply how much
        // the two frames differ. If neither hypothesis beats it by a wide margin
        // the field is doing no work -- either it is all zeros or the scene was
        // static -- and the winner between them is noise. The first run of this
        // test reported -motion ahead by 5.6% with both errors at 0.0025, which
        // is exactly that failure and would have been read as a verdict.
        final float baseline = signTestError(0f, scaleX, scaleY);
        final float minus = signTestError(-1f, scaleX, scaleY);
        final float plus = signTestError(1f, scaleX, scaleY);

        final float best = Math.min(minus, plus);
        // The right sign should explain a real share of the difference between
        // the frames. A fifth is a low bar and still far outside noise.
        if (baseline <= 1e-4f || best > baseline * 0.8f) return;

        signTested = true;
        final boolean backward = plus < minus;
        Log.i(TAG, String.format(
            "sign test: uncompensated %.5f, with -motion %.5f, with +motion %.5f"
                + " -> vectors point %s; the warp should use %s",
            baseline, minus, plus,
            backward ? "BACKWARD (target to reference)" : "FORWARD (reference to target)",
            backward ? "uv + motion*t" : "uv - motion*t"));
    }

    private boolean signTested = false;
    private Target signTarget;

    /**
     * Mean absolute reconstruction error for one hypothesis.
     *
     * <p>Rendered into a small target and averaged on the CPU: the answer is a
     * single number, and a 96x54 readback is 20 KB rather than the 14 MB a
     * full-resolution one would cost on the GL thread -- which is how an earlier
     * diagnostic here caused an ANR.
     */
    private float signTestError(float direction, float scaleX, float scaleY) {
        if (signTarget == null) {
            signTarget = new Target();
            signTarget.allocate(SIGN_TEST_W, SIGN_TEST_H, GLES30.GL_RGBA8, GLES20.GL_NEAREST);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, signTarget.framebuffer);
        GLES20.glViewport(0, 0, SIGN_TEST_W, SIGN_TEST_H);
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        signTestMaterial.use();
        renderer.quadVertices.bind(signTestMaterial.programId);
        signTestMaterial.setUniformBool(signTestMaterial.uniforms.flipY, false);
        signTestMaterial.setUniformVec2(signTestMaterial.signUniforms.motionScale, scaleX, scaleY);
        signTestMaterial.setUniformFloat(signTestMaterial.signUniforms.direction, direction);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestColour().texture);
        signTestMaterial.setUniformInt(signTestMaterial.uniforms.screenTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colour[oldest()].texture);
        signTestMaterial.setUniformInt(signTestMaterial.signUniforms.previousTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectors.texture);
        signTestMaterial.setUniformInt(signTestMaterial.signUniforms.motionTexture, 2);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());

        final java.nio.ByteBuffer pixels = java.nio.ByteBuffer
            .allocateDirect(SIGN_TEST_W * SIGN_TEST_H * 4)
            .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, SIGN_TEST_W, SIGN_TEST_H, GLES20.GL_RGBA,
                            GLES20.GL_UNSIGNED_BYTE, pixels);

        for (int unit = 2; unit >= 0; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.invalidateBoundWindowMaterial();

        long total = 0;
        for (int i = 0; i < SIGN_TEST_W * SIGN_TEST_H; i++) {
            total += pixels.get(i * 4) & 0xff;
        }
        return total / (255f * SIGN_TEST_W * SIGN_TEST_H);
    }

    private static final int SIGN_TEST_W = 96;
    private static final int SIGN_TEST_H = 54;

    /** Gather the latest frame along the field, aimed t of the way forward. */
    private void warp(Target source, float t) {
        GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        warpMaterial.use();
        renderer.quadVertices.bind(warpMaterial.programId);
        warpMaterial.setUniformBool(warpMaterial.uniforms.flipY, false);
        // The field is in pixels between two real frames; dividing by the frame
        // size puts it in texture space, and t aims it.
        warpMaterial.setUniformVec2(warpMaterial.warpUniforms.motionScale,
                                    t / Math.max(1, luma[0].width), t / Math.max(1, luma[0].height));

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.texture);
        warpMaterial.setUniformInt(warpMaterial.uniforms.screenTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectors.texture);
        warpMaterial.setUniformInt(warpMaterial.warpUniforms.motionTexture, 1);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.invalidateBoundWindowMaterial();
    }

    private void renderToTarget(ScreenMaterial material, int source, Target destination) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destination.framebuffer);
        blit(material, source, destination.width, destination.height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * Draw a texture over the whole of the current target.
     *
     * <p>Blending off for the duration. The compositor enables
     * SRC_ALPHA/ONE_MINUS_SRC_ALPHA once at context creation and leaves it on,
     * which is right for stacking windows and wrong for a whole-surface copy: the
     * letterbox is clear colour at alpha zero, so blended it would let the
     * previous screen show through as a second, older image underneath.
     */
    private void blit(ScreenMaterial material, int texture, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        material.use();
        renderer.quadVertices.bind(material.programId);
        material.setUniformBool(material.uniforms.flipY, false);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glEnable(GLES20.GL_BLEND);
        // The blit bound a program behind bindWindowMaterial's back, and its
        // one-glUseProgram-per-frame bookkeeping would otherwise let the next
        // window pass draw with this shader.
        renderer.invalidateBoundWindowMaterial();
    }

    /**
     * VESSEL: the history, addressed by which slot is newest rather than by a
     * boolean two accessors can agree on by accident.
     *
     * <p>These used to be five one-line ternaries, and two of them --
     * {@code writeLuma()} and {@code previousLuma()} -- had *identical bodies*.
     * They returned different buffers only because one was called before the
     * newest slot flipped and the other after, so the code was correct entirely
     * by statement order and would have broken the first time a line moved. An
     * index makes the question "which slot" separate from the question "when".
     */
    private int newest = 1;

    private int oldest() { return 1 - newest; }

    /** The slot the next real frame is composited into: the older of the two. */
    private Target writeColour() { return colour[oldest()]; }

    private Target latestColour() { return colour[newest]; }

    private Target writeLuma() { return luma[oldest()]; }

    private Target latestLuma() { return luma[newest]; }

    private Target previousLuma() { return luma[oldest()]; }

    private final Target[] colour = new Target[2];
    private final Target[] luma = new Target[2];

    private boolean ensureTargets() {
        final int generation = GLRenderer.contextGeneration();
        final int width = renderer.surfaceWidth;
        final int height = renderer.surfaceHeight;
        if (width <= 0 || height <= 0) return false;
        if (colour[0] != null && allocWidth == width && allocHeight == height
                && allocGeneration == generation) {
            return true;
        }

        // Only delete when the names still mean something. A new generation means
        // the objects went with the context that held them, and deleting those ids
        // would destroy whatever now has them.
        release(colour[0] != null && allocGeneration == generation);

        for (int i = 0; i < 2; i++) {
            colour[i] = new Target();
            colour[i].allocate(width, height, GLES30.GL_RGBA8, GLES20.GL_LINEAR);
        }

        if (motionEstimationSupported()) {
            final int[] value = new int[1];
            GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM, value, 0);
            blockX = Math.max(1, value[0]);
            GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM, value, 0);
            blockY = Math.max(1, value[0]);

            // The luma pair must be an exact multiple of the search block, so the
            // largest such rectangle is used rather than the whole surface.
            final int lumaW = (width / blockX) * blockX;
            final int lumaH = (height / blockY) * blockY;
            if (lumaW > 0 && lumaH > 0) {
                for (int i = 0; i < 2; i++) {
                    luma[i] = new Target();
                    luma[i].allocate(lumaW, lumaH, GLES30.GL_R8, GLES20.GL_LINEAR);
                }
                vectors = new Target();
                vectors.allocate(lumaW / blockX, lumaH / blockY, GLES30.GL_RGBA16F, GLES20.GL_LINEAR);
                Log.i(TAG, "tier 1 ready: block " + blockX + "x" + blockY
                    + ", luma " + lumaW + "x" + lumaH
                    + ", vectors " + (lumaW / blockX) + "x" + (lumaH / blockY));
            }
        }

        allocWidth = width;
        allocHeight = height;
        allocGeneration = generation;
        newest = 1;
        realFrames = 0;
        lastRealFrameNanos = 0;
        return true;
    }

    private void release(boolean deleteObjects) {
        if (deleteObjects) {
            for (int i = 0; i < 2; i++) {
                if (colour[i] != null) colour[i].release();
                if (luma[i] != null) luma[i].release();
            }
            if (vectors != null) vectors.release();
        }
        colour[0] = colour[1] = luma[0] = luma[1] = null;
        vectors = null;
    }

    /**
     * Say once a second which tier is carrying the frames, and what it costs.
     *
     * <p>Both halves matter and neither is guessable. The counters say whether
     * tier 0 is doing the work -- which it should be, for a desktop -- and the
     * timers say whether the passes fit in the budget, which reasoning about the
     * shader cannot answer because the cost is bandwidth and render-target
     * switches rather than arithmetic.
     */
    private void report() {
        final long now = SystemClock.uptimeMillis();
        captureTimer.report(now);
        lumaTimer.report(now);
        estimateTimer.report(now);
        warpTimer.report(now);
        tier0Timer.report(now);
        if (now - reportedAt < 1000) return;
        reportedAt = now;
        Log.i(TAG, "real " + realFrames + ", tier0 " + tier0Frames
            + ", tier1 " + tier1Frames + ", skipped " + skipped
            + ", " + multiple + "x");
        tier0Frames = 0;
        tier1Frames = 0;
        skipped = 0;
    }
}
