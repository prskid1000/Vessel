package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.renderer.material.ConfidenceMaterial;
import com.winlator.renderer.material.DownsampleLumaMaterial;
import com.winlator.renderer.material.InterpolateMaterial;
import com.winlator.renderer.material.MedianMaterial;
import com.winlator.renderer.material.MergeFieldMaterial;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.WarpLumaMaterial;

/**
 * VESSEL: frames the guest never drew, built from the two it did.
 *
 * <p>Every real frame is composited at the guest's resolution into one of two
 * colour targets, and a motion field between the two newest is estimated with
 * {@code GL_QCOM_motion_estimation}. Between real frames, {@link FramePacer}
 * wakes the renderer at display refreshes and {@link InterpolateMaterial}
 * builds the frame that belongs at that moment. Frame N itself is shown at the
 * end of its interval, which is the one interval of latency interpolation
 * costs.
 *
 * <h2>The passes, per real frame</h2>
 *
 * <ol>
 * <li>luma of the new frame, and a quarter-size copy of it;
 * <li>the matcher on the quarter-size pair: a coarse prior that reaches four
 *     times further than the matcher's window;
 * <li>a one-texel confidence pass over that prior, read back through a pixel
 *     buffer at the end of the frame;
 * <li>the older luma warped forward by the prior, the matcher on that against
 *     the newer luma; the newer luma warped back, the matcher on that against
 *     the older. Two residual fields, each within the window by construction;
 * <li>one merge pass: prior plus residuals, forward in RG and backward in BA
 *     of one RGBA16F texture;
 * <li>{@link #MEDIAN_PASSES} vector-median passes over that one texture, the
 *     last of which is kept as next frame's temporal candidate;
 * <li>the interpolation at phase 1/K, presented.
 * </ol>
 *
 * <p>The field's sign convention is fixed: the extension reports motion from
 * {@code ref} to {@code target}, and the shader's vector points the other
 * way. See {@link #FIELD_SIGN}. The forward field is indexed on frame N, the
 * backward on N-1; see {@link InterpolateMaterial}.
 *
 * <p>Two tiers remain. Tier 1 is the above. Tier 0, used only where there is
 * no field, re-composites the windows at interpolated positions when one of
 * them moved, which is exact for a window drag and nothing else.
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
     * Whether the matcher exists, asked of the driver once per EGL context.
     * Both halves are needed: the extension string says the driver claims it,
     * the resolve says {@code eglGetProcAddress} hands over a function.
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
     * The extension's sign convention, applied where the field is read.
     *
     * <p>The spec says a texel holds the motion "from the ref texture to the
     * target texture": with the older frame as ref that is the displacement
     * {@code d} of content from N-1 to N. The shaders use {@code v = -d}, the
     * offset from a pixel back to where its content was, so the field is
     * multiplied by -1 on the way in. Measured on this device by a vote over
     * moving pixels in every session it was ever probed: -1, without exception.
     */
    static final float FIELD_SIGN = -1f;

    /** Luma, because the extension requires R8 and matches on brightness. */
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
     * A texture with sized immutable storage and a framebuffer on it.
     *
     * <p>Not {@link RenderTarget}, which allocates with {@code glTexImage2D}
     * and a BGRA format the extension does not accept.
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
            // Cleared once: nothing may ever present whatever last held this
            // memory. The compositor's own clear colour is put back after.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        /**
         * Diagnostics only: a full mip chain with framebuffers on its 1x1 top
         * and on the level whose texels are 32 px cells, so a whole-frame
         * measurement is a four-byte readback and a patch count is a 3.5 KB
         * one, instead of a 14 MB stall.
         */
        int topFramebuffer;
        int cellFramebuffer;
        int cellWidth, cellHeight;

        void allocateAveraging(int width, int height) {
            this.width = width;
            this.height = height;
            int count = 1;
            for (int size = Math.max(width, height); size > 1; size >>= 1) count++;

            final int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            texture = names[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, count, GLES30.GL_RGBA8, width, height);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                                   GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glGenFramebuffers(1, names, 0);
            framebuffer = names[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                          GLES20.GL_TEXTURE_2D, texture, 0);

            GLES20.glGenFramebuffers(1, names, 0);
            topFramebuffer = names[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, topFramebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                          GLES20.GL_TEXTURE_2D, texture, count - 1);

            final int cellLevel = Math.min(5, count - 1);
            cellWidth = Math.max(1, width >> cellLevel);
            cellHeight = Math.max(1, height >> cellLevel);
            GLES20.glGenFramebuffers(1, names, 0);
            cellFramebuffer = names[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cellFramebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                          GLES20.GL_TEXTURE_2D, texture, cellLevel);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        void release() {
            if (cellFramebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[] {cellFramebuffer}, 0);
            if (topFramebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[] {topFramebuffer}, 0);
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[] {texture}, 0);
            cellFramebuffer = topFramebuffer = framebuffer = texture = 0;
        }
    }

    private final GLRenderer renderer;
    private final FramePacer pacer;
    private final LumaMaterial lumaMaterial = new LumaMaterial();
    private final DownsampleLumaMaterial downsampleMaterial = new DownsampleLumaMaterial();
    private final WarpLumaMaterial warpMaterial = new WarpLumaMaterial();
    private final MergeFieldMaterial mergeMaterial = new MergeFieldMaterial();
    private final MedianMaterial medianMaterial = new MedianMaterial();
    private final ConfidenceMaterial confidenceMaterial = new ConfidenceMaterial();
    private final InterpolateMaterial interpolateMaterial = new InterpolateMaterial();

    private final GpuTimer captureTimer = new GpuTimer("tier1 capture+blit");
    private final GpuTimer lumaTimer = new GpuTimer("tier1 luma");
    private final GpuTimer estimateTimer = new GpuTimer("tier1 estimate");
    private final GpuTimer medianTimer = new GpuTimer("tier1 median");
    private final GpuTimer interpolateTimer = new GpuTimer("tier1 interpolate");
    private final GpuTimer tier0Timer = new GpuTimer("tier0 recomposite");

    // ---- targets -----------------------------------------------------------

    /** The two real frames, at the guest's resolution. */
    private final Target[] colour = new Target[2];
    /** Where an interpolated frame is built, before the one upscale at present. */
    private Target output;
    /** Their luma, block-rounded, and the quarter-size copies. */
    private final Target[] luma = new Target[2];
    private final Target[] lumaCoarse = new Target[2];
    /** Which of the pairs holds the newest real frame. */
    private int newest = 1;
    /** The coarse prior, LINEAR because it is a warp's displacement. */
    private Target coarseVectors;
    /** One luma frame moved by the prior; serves both directions in turn. */
    private Target warpedLuma;
    /** The two residual fields the matcher writes, forward and backward. */
    private Target residual, residualBack;
    /** Prior plus residuals, packed: forward RG, backward BA. */
    private Target merged;
    /**
     * Three packed field slots. One holds the previous frame's filtered field
     * (the median's temporal candidate), the other two ping-pong this frame's
     * passes, and the last pass's slot becomes the history for the next frame.
     * No copies.
     */
    private final Target[] fields = new Target[3];
    private int fieldHistory = -1;
    private int fieldCurrent = -1;
    /** The one-texel confidence target and the pixel buffer it is read through. */
    private Target confidence;
    private int confidenceBuffer = 0;
    private boolean confidencePending = false;
    /** Diagnostics only. */
    private Target probe;

    private int blockX = 8, blockY = 8;
    private int allocWidth = 0, allocHeight = 0, allocGeneration = -1;

    /**
     * How much smaller the coarse pass is. The matcher's window is about 112
     * luma pixels and a fast scene moves more than that between real frames;
     * at a quarter size the same motion is well inside the window and the
     * full-resolution pass only has to find the remainder.
     */
    private static final int COARSE_DIVISOR = 4;

    /**
     * Ten anchored passes, measured on a bench where the answer is known: edge
     * waviness 9.54 px unfiltered, 0.58 at six passes, 0.013 at ten (the
     * ground truth's own figure), with no staircasing of smooth parallax
     * because the matcher's own vector stays a candidate on every pass.
     */
    private static final int MEDIAN_PASSES = 10;

    /**
     * The whole-frame guard. Below {@link #MIN_AGREEMENT} the coarse field did
     * not agree on a motion (a cut, a menu, a flat wall with one object
     * crossing it); above {@link #MAX_FRAME_DIFF} the two frames are not the
     * same scene. Either way the interval shows real frames only.
     */
    private static final float MIN_AGREEMENT = 0.20f;
    private static final float MAX_FRAME_DIFF = 40f / 255f;
    private float lastAgreement = 1f, lastFrameDiff = 0f, lastDominantPx = 0f;
    private boolean confidentNow = true;
    private int lowStreak = 0, highStreak = 0;
    private long lowConfidenceIntervals = 0;

    // ---- diagnostics -------------------------------------------------------

    private java.util.Set<String> diagnostics = java.util.Collections.emptySet();
    private boolean timing = false;
    private boolean announced = false;

    public void setDiagnostics(java.util.Set<String> categories) {
        this.diagnostics = categories == null ? java.util.Collections.emptySet() : categories;
        this.timing = wants("timing");
        this.announced = false;
    }

    private boolean wants(String category) {
        return diagnostics.contains(category) || diagnostics.contains("all");
    }

    /**
     * Lines for the session log, which outlives logcat's three minutes. A queue
     * because the producer is the GL thread and the consumer a coroutine; the
     * oldest are dropped, and counted, if nothing drains it.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> sessionLines =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger sessionQueued =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final int SESSION_LINE_LIMIT = 512;
    private int sessionDropped = 0;

    private void say(String line) {
        Log.i(TAG, line);
        if (sessionQueued.get() >= SESSION_LINE_LIMIT && sessionLines.poll() != null) {
            sessionQueued.decrementAndGet();
            sessionDropped++;
        }
        sessionLines.add(line);
        sessionQueued.incrementAndGet();
    }

    /** Called off the GL thread. Empty is the normal answer. */
    public java.util.List<String> drainDiagnostics() {
        if (sessionLines.isEmpty() && sessionDropped == 0) {
            return java.util.Collections.emptyList();
        }
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String line = sessionLines.poll(); line != null; line = sessionLines.poll()) {
            sessionQueued.decrementAndGet();
            out.add(line);
        }
        if (sessionDropped > 0) {
            out.add("fg log: " + sessionDropped + " lines dropped before this one --"
                + " nothing was draining the queue");
            sessionDropped = 0;
        }
        return out;
    }

    /** Whole-frame measurements, most recently read back. See {@link #measure}. */
    private float measuredDark, measuredShadow, measuredSynthDistance, measuredBaseDistance;
    private float measuredPhase = 0f;
    private long measuredAt = 0;
    private int measuredPatchCells = 0, measuredCellTotal = 0, measuredEdgeCells = 0;
    private float measuredWorstCell = 0f;
    private java.nio.ByteBuffer cells;

    // ---- pacing ------------------------------------------------------------

    private int multiple = 2;
    /** The multiple in force for the interval now running. See {@link #effectiveMultiple}. */
    private int activeMultiple = 2;
    private boolean motionValid = false;
    private volatile long realFrames = 0;
    private long lastRealFrameNanos = 0;
    private long smoothedInterval = 0;

    /**
     * The last nine real-frame intervals. A median, because the compositor
     * draws on guest damage and one hitch would sit in a mean for several
     * frames, aiming every prediction meanwhile at a rate the guest is not
     * running at.
     */
    private final long[] recent = new long[9];
    private final long[] recentSorted = new long[9];
    private int recentAt = 0, recentHeld = 0;

    /**
     * A long-horizon view of the same interval, for {@link #worthInterpolating}:
     * sixty-four samples describe the guest's habit and do not move when it
     * hitches.
     */
    private final long[] baseline = new long[64];
    private final long[] baselineSorted = new long[64];
    private int baselineAt = 0, baselineHeld = 0;
    /** Settled once per real frame; both sorts are then done once. */
    private long baselineInterval = 0;

    private final java.util.concurrent.atomic.AtomicLong pending =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Above this distance from a synthesised frame to the nearest real one, a
     * frame is invention rather than prediction. Scaled by the multiple, so
     * 250 ms between real frames at 2x.
     */
    private static final long SYNTH_MAX_GAP_NANOS = 125_000_000L;

    private long idleGate() {
        return SYNTH_MAX_GAP_NANOS * Math.max(2, multiple);
    }

    /**
     * The widest gap still worth interpolating across: the guest's habit times
     * {@link #GATE_MULTIPLE}, scaled inversely with the multiple and normalised
     * at 4x, floored at 100 ms and capped at 250. Past the cap the guest has
     * stopped (a load, a shader compile) and inventing frames across it is
     * worse than showing the one that exists.
     */
    private static final long WORTH_INTERPOLATING_NANOS = 100_000_000L;
    private static final long WORTH_INTERPOLATING_CEILING_NANOS = 250_000_000L;
    private static final long GATE_MULTIPLE = 3L;
    /**
     * How the multiple gives ground between the habit and the gate. Below one
     * the curve holds what was asked for across most of the range and turns
     * down near the gate; a linear ramp was measured worse than a cliff.
     */
    private static final double GATE_CURVE = 0.35;

    private long worthInterpolating() {
        if (baselineInterval <= 0) return WORTH_INTERPOLATING_NANOS;
        final long allowed = baselineInterval * GATE_MULTIPLE * 4L / Math.max(2, multiple);
        return Math.min(Math.max(allowed, WORTH_INTERPOLATING_NANOS),
                        WORTH_INTERPOLATING_CEILING_NANOS);
    }

    /**
     * How many frames this interval can carry: what was asked for, reduced as
     * the interval approaches the gate, and never more than the interval holds
     * refreshes. Not clamped to the fps limit: the expensive half of a guest
     * overrunning its cap is the real frames, which nothing here can decline.
     */
    private int effectiveMultiple() {
        if (multiple < 2) return 1;
        final long gate = worthInterpolating();
        if (smoothedInterval > gate) return 1;
        int asked = multiple;
        final long habit = baselineInterval;
        if (habit > 0 && gate > habit && smoothedInterval > habit) {
            final double headroom = Math.max(0.0, Math.min(1.0,
                (double)(gate - smoothedInterval) / (double)(gate - habit)));
            asked = (int)Math.round(1.0 + (multiple - 1) * Math.pow(headroom, GATE_CURVE));
            asked = Math.max(1, Math.min(multiple, asked));
        }
        final long period = vsyncPeriodNanos();
        if (period <= 0 || smoothedInterval <= 0) return asked;
        return Math.min(asked, Math.max(1, (int)(smoothedInterval / period)));
    }

    private static long median(long[] values, long[] scratch, int held) {
        if (held == 0) return 0;
        System.arraycopy(values, 0, scratch, 0, held);
        java.util.Arrays.sort(scratch, 0, held);
        return scratch[held / 2];
    }

    /** One display refresh, from the panel itself. */
    @Override
    public long vsyncPeriodNanos() {
        final android.view.Display display = renderer.xServerView.getDisplay();
        final float hz = display != null ? display.getRefreshRate() : 0f;
        return hz > 1f ? (long)(1_000_000_000L / hz) : 0L;
    }

    /** The display's own account of when frames were shown. Diagnostics. */
    private final FrameTimestamps timestamps = new FrameTimestamps();
    private long lastPresentVsync = -1;
    private long collisions = 0;
    private long lastPresentNanos = 0;
    private long presentGapMin = Long.MAX_VALUE, presentGapMax = 0, presentGapTotal = 0, presentGaps = 0;

    private void notePresented() {
        if (wants("pacing")) {
            timestamps.onDraw();
            final long vsync = FramePacer.vsyncIndex();
            if (vsync == lastPresentVsync) collisions++;
            lastPresentVsync = vsync;
        }
        final long now = System.nanoTime();
        if (lastPresentNanos != 0) {
            final long gap = now - lastPresentNanos;
            if (gap < 250_000_000L) {
                presentGapMin = Math.min(presentGapMin, gap);
                presentGapMax = Math.max(presentGapMax, gap);
                presentGapTotal += gap;
                presentGaps++;
            }
        }
        lastPresentNanos = now;
    }

    private long tier0Frames = 0, tier1Frames = 0, skipped = 0, estimateFailures = 0;
    private long reportedAt = 0, reportedRealFrames = 0;

    public FrameSynthesizer(GLRenderer renderer) {
        this.renderer = renderer;
        this.pacer = new FramePacer(this);
    }

    public void setMultiple(int multiple) {
        this.multiple = Math.max(2, Math.min(8, multiple));
    }

    private void announce() {
        if (announced || diagnostics.isEmpty()) return;
        announced = true;
        say("fg setup: asked for " + diagnostics + ", " + multiple + "x, guest "
            + renderer.guestWidth() + "x" + renderer.guestHeight()
            + " presented into " + renderer.viewTransformation.viewWidth
            + "x" + renderer.viewTransformation.viewHeight
            + " of " + renderer.surfaceWidth + "x" + renderer.surfaceHeight
            + ", motion estimation " + (tier1Ready()
                ? "available, block " + blockX + "x" + blockY
                : "NOT AVAILABLE -- tier 1 will never run"));
    }

    @Override
    public long realFrameCount() {
        return realFrames;
    }

    @Override
    public void onSynthesisDue(long vsyncNanos) {
        pending.set(vsyncNanos);
        // Unpaced: the pacer has already aimed this at a vsync, and the view's
        // frame limiter would re-post it through a delay queue.
        renderer.xServerView.requestRenderUnpaced();
    }

    /** Consume a pending request, so one schedule yields exactly one frame. */
    public long consumePending() {
        return pending.getAndSet(0);
    }

    // ---- the real frame ----------------------------------------------------

    /**
     * Point the compositor at the offscreen colour target, at guest scale.
     *
     * @return false when the targets could not be allocated; the caller then
     *     composites to the screen as it did before this existed.
     */
    public boolean beginRealFrame() {
        if (!ensureTargets()) return false;
        if (timing) captureTimer.begin();
        renderer.beginGuestScaleCapture();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeColour().framebuffer);
        return true;
    }

    /**
     * Present the real frame, estimate the field for the interval, queue the rest.
     *
     * <p>The invariant: every real frame is presented exactly once, in order;
     * only interpolated phases may be dropped. Frame N is presented as phase
     * K/K at the end of its interval by the pacer, and here as a fallback if
     * the pacer never got to it.
     *
     * @return whether anything reached the buffer. False means the caller must
     *     re-present the last frame, because the view swaps regardless.
     */
    public boolean endRealFrame() {
        final Target written = writeColour();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.endGuestScaleCapture();
        if (timing) captureTimer.end();

        boolean presentedHere = false;
        if (!realPresented && realFrames >= 1) {
            presentLatest();
            presentedHere = true;
        }

        if (tier1Ready()) {
            if (timing) lumaTimer.begin();
            renderToTarget(lumaMaterial, written.texture, writeLuma());
            downsampleMaterial.use();
            downsampleMaterial.setUniformVec2(downsampleMaterial.downsampleUniforms.texelSize,
                                              0.25f / lumaCoarse[0].width, 0.25f / lumaCoarse[0].height);
            renderToTarget(downsampleMaterial, writeLuma().texture, lumaCoarse[oldest()]);
            if (timing) lumaTimer.end();
        }

        // The slot just written is now the newest.
        newest = oldest();
        realFrames++;
        renderer.latchWindowPositions();

        motionValid = realFrames >= 2 && tier1Ready() && estimateMotion();

        if (wants("setup")) announce();
        if (wants("pacing")) FramePacer.countRefreshes();

        // The interval is measured before anything is presented, because the
        // present depends on it.
        final long now = System.nanoTime();
        final long interval = lastRealFrameNanos == 0 ? 0 : now - lastRealFrameNanos;
        lastRealFrameNanos = now;
        // A gap past the idle gate is a stall, not a cadence. It is kept out
        // of the interval estimate -- and the interval it starts is shown as
        // real frames only. Without the second half, the stale estimate
        // scheduled a full set of frames interpolated across the stall: the
        // pre-stall picture blended into whatever the guest drew next, for
        // one interval, every time it paused.
        final boolean stalled = interval > idleGate();
        if (stalled) motionValid = false;
        if (interval > 0 && !stalled) {
            recent[recentAt] = interval;
            recentAt = (recentAt + 1) % recent.length;
            if (recentHeld < recent.length) recentHeld++;
            smoothedInterval = median(recent, recentSorted, recentHeld);
            baseline[baselineAt] = interval;
            baselineAt = (baselineAt + 1) % baseline.length;
            if (baselineHeld < baseline.length) baselineHeld++;
            baselineInterval = median(baseline, baselineSorted, baselineHeld);
        }
        activeMultiple = effectiveMultiple();

        realPresented = false;
        lastPhase = 0f;
        // Phase 1/K is drawn here on arrival unless that would share a refresh
        // with the present before it; then it is handed to the pacer as slot
        // zero, due immediately, rather than drawn into a scanout already
        // taken. Dropping it was measured worse than deferring it.
        boolean deferredArrival = false;
        if (motionValid && activeMultiple >= 2 && !clearOfLastPresent()) {
            deferredArrival = true;
        } else if (motionValid && activeMultiple >= 2) {
            presentPhase(1f / activeMultiple);
            tier1Frames++;
            lastPhase = 1f / activeMultiple;
        } else {
            presentLatest();
        }

        if (realFrames >= 2 && !stalled && smoothedInterval > 0 && smoothedInterval <= idleGate()) {
            pacer.schedule(smoothedInterval, activeMultiple, deferredArrival);
        }
        report();
        return !deferredArrival || presentedHere;
    }

    // ---- the synthesised frame ---------------------------------------------

    /** Draw one synthesised frame for the vsync it was due at. */
    public boolean presentSynthesized(long vsyncNanos) {
        if (!ensureTargets() || realFrames < 2 || vsyncNanos <= 0) return false;
        final float phase = phaseFor(vsyncNanos);

        if (motionValid) {
            // The real frame is never withheld; an interpolated one yields.
            if (phase < 1f && !clearOfLastPresent()) return false;
            presentPhase(phase);
            if (phase < 1f) tier1Frames++;
            lastPhase = phase;
            return true;
        }

        // Tier 0: no field, but a window moved. Exact for a translation.
        if (renderer.anyWindowMoved()) {
            if (timing) tier0Timer.begin();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            renderer.drawSynthesizedFrame(phase);
            if (timing) tier0Timer.end();
            notePresented();
            tier0Frames++;
            lastPhase = phase;
            if (phase >= 1f) realPresented = true;
            return true;
        }

        skipped++;
        return false;
    }

    /**
     * Put the frame that is already on screen back into the buffer.
     *
     * <p>The view swaps whether or not anything was drawn, and the buffer it
     * swaps in is two or three presents old, so a draw that declines to draw
     * would publish an old frame. Not counted as a present: nothing new is
     * shown. The cursor is drawn fresh on top, which is why this is also the
     * right answer to a pointer move.
     *
     * @return false when there is nothing to repeat yet.
     */
    public boolean repeatLastPresent() {
        // A name from a dead context means nothing in the live one; the next
        // real frame reallocates.
        if (repeatTexture == 0 || allocGeneration != GLRenderer.contextGeneration()) return false;
        renderer.presentGuestFrame(repeatTexture, true);
        return true;
    }

    private int repeatTexture = 0;

    /**
     * Which moment to show, from the vsync timestamp rather than from a slot
     * index, so content time runs at wall time however late a callback fires.
     * Never backwards; the callers that actually present advance
     * {@link #lastPhase}.
     */
    private float phaseFor(long vsyncNanos) {
        if (smoothedInterval <= 0 || lastRealFrameNanos == 0) return 1f;
        final float elapsed = (float)(vsyncNanos - lastRealFrameNanos) / smoothedInterval;
        float phase = 1f / activeMultiple + elapsed;
        if (phase < lastPhase) phase = lastPhase;
        if (phase > 1f) phase = 1f;
        return phase;
    }

    private float lastPhase = 0f;

    /** Phase 1 is the newer real frame exactly, so it is blitted rather than interpolated. */
    private void presentPhase(float phase) {
        if (phase >= 1f) {
            presentLatest();
            return;
        }
        if (timing) interpolateTimer.begin();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.framebuffer);
        interpolate(phase, false);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        repeatTexture = output.texture;
        renderer.presentGuestFrame(output.texture, true);
        notePresented();
        if (timing) interpolateTimer.end();
        if (wants("dump")) maybeDump(phase);
        if (wants("quality") && SystemClock.uptimeMillis() - measuredAt >= 1000) measure(phase);
    }

    /**
     * Whether a present now would land clear of the one before it. The margin
     * shrinks as 1/K, and at 4x a few milliseconds of jitter is routine.
     */
    private boolean clearOfLastPresent() {
        final long period = vsyncPeriodNanos();
        if (period <= 0 || lastPresentNanos == 0) return true;
        return System.nanoTime() - lastPresentNanos >= period;
    }

    private void presentLatest() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        repeatTexture = latestColour().texture;
        renderer.presentGuestFrame(latestColour().texture, true);
        notePresented();
        realPresented = true;
    }

    private boolean realPresented = true;

    /**
     * Blend the two real frames along the field at {@code phase}.
     *
     * @param measuring writes four per-pixel measurements instead of a picture,
     *     into {@link #probe}. See {@link #measure}.
     */
    private void interpolate(float phase, boolean measuring) {
        GLES20.glViewport(0, 0, colour[0].width, colour[0].height);
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        interpolateMaterial.use();
        renderer.quadVertices.bind(interpolateMaterial.programId);
        final InterpolateMaterial.Uniforms u = interpolateMaterial.interpolateUniforms;
        interpolateMaterial.setUniformBool(interpolateMaterial.uniforms.flipY, false);
        interpolateMaterial.setUniformFloat(u.phase, phase);
        interpolateMaterial.setUniformFloat(u.diagnostic, measuring ? 1f : 0f);
        interpolateMaterial.setUniformFloat(u.mark, !measuring && wants("mark") ? 1f : 0f);
        // The field is in luma pixels and a luma pixel is a colour pixel, so
        // one field unit is one colour texel; lumaScale maps colour UV onto
        // the block-rounded luma.
        interpolateMaterial.setUniformVec2(u.motionScale, 1f / colour[0].width, 1f / colour[0].height);
        interpolateMaterial.setUniformVec2(u.lumaScale,
                                           colour[0].width / (float)luma[0].width,
                                           colour[0].height / (float)luma[0].height);
        interpolateMaterial.setUniformVec2(u.vectorSize, merged.width, merged.height);
        interpolateMaterial.setUniformFloat(u.fieldSign, FIELD_SIGN);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestColour().texture);
        interpolateMaterial.setUniformInt(interpolateMaterial.uniforms.screenTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colour[oldest()].texture);
        interpolateMaterial.setUniformInt(u.previousTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fields[fieldCurrent].texture);
        interpolateMaterial.setUniformInt(u.motionTexture, 2);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());

        for (int unit = 2; unit >= 0; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.invalidateBoundWindowMaterial();
    }

    // ---- the field ---------------------------------------------------------

    /** Whether every tier 1 target exists. Decided once per allocation. */
    private boolean tier1Ready() {
        return merged != null;
    }

    /**
     * Estimate the field for the interval that starts now. See the class
     * comment for the passes.
     *
     * @return false if the interval must show real frames only: the matcher
     *     refused, or the guard declined.
     */
    private boolean estimateMotion() {
        if (timing) estimateTimer.begin();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }

        final Target older = luma[oldest()], newer = luma[newest];
        final Target olderCoarse = lumaCoarse[oldest()], newerCoarse = lumaCoarse[newest];
        // Both grids are rounded down to whole blocks, so the ratio differs per axis.
        final float ratioX = (float)older.width / coarseVectors.width / blockX;
        final float ratioY = (float)older.height / coarseVectors.height / blockY;

        boolean ok = estimateOnce(olderCoarse.texture, newerCoarse.texture, coarseVectors.texture);
        if (ok) {
            issueConfidence(ratioX, ratioY, olderCoarse, newerCoarse);
            // Forward: the older frame moved onto the newer, then the leftovers.
            warpLuma(older.texture, +1f);
            ok = estimateOnce(warpedLuma.texture, newer.texture, residual.texture);
        }
        if (ok) {
            // Backward: the newer frame moved onto the older, by the same
            // prior negated. A prior only has to be close.
            warpLuma(newer.texture, -1f);
            ok = estimateOnce(warpedLuma.texture, older.texture, residualBack.texture);
        }
        if (timing) estimateTimer.end();
        if (!ok) return false;

        mergeFields(ratioX, ratioY);
        filterField();

        // Last, so the guard's texel has had the whole frame's work to arrive
        // behind, and the map does not wait.
        if (!confident()) {
            lowConfidenceIntervals++;
            return false;
        }
        return true;
    }

    /**
     * One call to the matcher, with the error check the extension needs. It
     * returns nothing and a failure writes nothing, so an unchecked call would
     * leave the previous frame's field in place.
     */
    private boolean estimateOnce(int ref, int target, int out) {
        texEstimateMotion(ref, target, out);
        final int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            estimateFailures++;
            Log.e(TAG, "glTexEstimateMotionQCOM failed 0x" + Integer.toHexString(error));
            return false;
        }
        return true;
    }

    /** Move one luma frame by the coarse prior. See {@link WarpLumaMaterial}. */
    private void warpLuma(int source, float direction) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, warpedLuma.framebuffer);
        warpMaterial.use();
        warpMaterial.setUniformVec2(warpMaterial.warpUniforms.warpScale,
                                    FIELD_SIGN / lumaCoarse[0].width, FIELD_SIGN / lumaCoarse[0].height);
        warpMaterial.setUniformFloat(warpMaterial.warpUniforms.direction, direction);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coarseVectors.texture);
        warpMaterial.setUniformInt(warpMaterial.warpUniforms.motionTexture, 1);
        blit(warpMaterial, source, warpedLuma.width, warpedLuma.height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Prior plus both residuals, into the packed field. See {@link MergeFieldMaterial}. */
    private void mergeFields(float ratioX, float ratioY) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, merged.framebuffer);
        mergeMaterial.use();
        mergeMaterial.setUniformVec2(mergeMaterial.mergeUniforms.coarseFactor, ratioX, ratioY);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, residualBack.texture);
        mergeMaterial.setUniformInt(mergeMaterial.mergeUniforms.backTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coarseVectors.texture);
        mergeMaterial.setUniformInt(mergeMaterial.mergeUniforms.coarseTexture, 2);
        blit(mergeMaterial, residual.texture, merged.width, merged.height);
        for (int unit = 2; unit >= 1; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * The median passes over the packed field. Ping-pongs between the two
     * slots that do not hold the previous frame's result, which is bound as
     * the temporal candidate throughout; the slot the last pass lands in is
     * then the history for the next frame.
     */
    private void filterField() {
        if (timing) medianTimer.begin();
        // The two slots that are not the history.
        final int[] ping = fieldHistory == 0 ? new int[] {1, 2}
                         : fieldHistory == 1 ? new int[] {0, 2}
                         : new int[] {0, 1};
        final boolean haveHistory = fieldHistory >= 0;

        GLES20.glDisable(GLES20.GL_BLEND);
        medianMaterial.use();
        renderer.quadVertices.bind(medianMaterial.programId);
        final MedianMaterial.MedianUniforms u = medianMaterial.medianUniforms;
        medianMaterial.setUniformBool(medianMaterial.uniforms.flipY, false);
        medianMaterial.setUniformVec2(u.texelSize, 1f / merged.width, 1f / merged.height);
        medianMaterial.setUniformVec2(u.motionScale, 1f / luma[0].width, 1f / luma[0].height);
        medianMaterial.setUniformFloat(u.fieldSign, FIELD_SIGN);
        medianMaterial.setUniformFloat(u.temporalValid, haveHistory ? 1f : 0f);
        // Three votes for the previous vector, measured on a constant pan:
        // flicker down 6%, blocks flipping between frames halved; at five
        // the flicker turns back up. One while there is no history.
        medianMaterial.setUniformFloat(u.temporalWeight, haveHistory ? 3f : 1f);
        // The matcher's own answer, on offer every pass so the field cannot
        // drift from what was measured.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, merged.texture);
        medianMaterial.setUniformInt(u.originalTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, haveHistory ? fields[fieldHistory].texture : 0);
        medianMaterial.setUniformInt(u.previousTexture, 2);
        GLES20.glViewport(0, 0, merged.width, merged.height);
        renderer.viewportNeedsUpdate = true;

        int chain = merged.texture;
        int index = -1;
        for (int pass = 0; pass < MEDIAN_PASSES; pass++) {
            index = ping[pass % 2];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fields[index].framebuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, chain);
            medianMaterial.setUniformInt(medianMaterial.uniforms.screenTexture, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
            chain = fields[index].texture;
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        for (int unit = 2; unit >= 0; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.invalidateBoundWindowMaterial();

        fieldCurrent = index;
        fieldHistory = index;
        if (timing) medianTimer.end();
    }

    /**
     * The guard's one texel, rendered right after the coarse estimate and
     * read into a pixel buffer so the render thread never waits for it. See
     * {@link ConfidenceMaterial}.
     */
    private void issueConfidence(float ratioX, float ratioY, Target olderCoarse, Target newerCoarse) {
        confidencePending = false;
        if (confidenceBuffer == 0) {
            final int[] names = new int[1];
            GLES20.glGenBuffers(1, names, 0);
            confidenceBuffer = names[0];
            GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, confidenceBuffer);
            GLES20.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, 4, null, GLES30.GL_STREAM_READ);
            GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, confidence.framebuffer);
        confidenceMaterial.use();
        final ConfidenceMaterial.ConfidenceUniforms u = confidenceMaterial.confidenceUniforms;
        confidenceMaterial.setUniformVec2(u.coarseSize, coarseVectors.width, coarseVectors.height);
        confidenceMaterial.setUniformVec2(u.coarseFactor, ratioX, ratioY);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, newerCoarse.texture);
        confidenceMaterial.setUniformInt(u.lumaNewerTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, olderCoarse.texture);
        confidenceMaterial.setUniformInt(u.lumaOlderTexture, 2);
        blit(confidenceMaterial, coarseVectors.texture, 1, 1);
        for (int unit = 2; unit >= 1; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, confidenceBuffer);
        GLES30.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
        final int error = GLES20.glGetError();
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.viewportNeedsUpdate = true;
        confidencePending = error == GLES20.GL_NO_ERROR;
    }

    /**
     * The decision. A failed map counts as confident: a broken diagnostic must
     * not switch the feature off. With hysteresis, because without it the whole
     * frame flipped between interpolated and real every few intervals when the
     * agreement hovered at the floor: two readings below to switch off, two
     * above a higher bar to switch back on. A cut is one reading.
     */
    private boolean confident() {
        if (!confidencePending) return true;
        confidencePending = false;
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, confidenceBuffer);
        final java.nio.Buffer mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, 4, GLES30.GL_MAP_READ_BIT);
        if (!(mapped instanceof java.nio.ByteBuffer)) {
            GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            return true;
        }
        final java.nio.ByteBuffer pixel = (java.nio.ByteBuffer)mapped;
        final int r = pixel.get(0) & 0xff, g = pixel.get(1) & 0xff, b = pixel.get(2) & 0xff;
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

        lastAgreement = r / 255f;
        lastDominantPx = g / 255f * 1024f;
        lastFrameDiff = b / 255f;

        if (lastFrameDiff > MAX_FRAME_DIFF) {
            confidentNow = false;
            lowStreak = 2;
            highStreak = 0;
            return false;
        }
        if (lastAgreement < MIN_AGREEMENT) {
            lowStreak++;
            highStreak = 0;
        } else if (lastAgreement >= MIN_AGREEMENT + 0.10f) {
            highStreak++;
            lowStreak = 0;
        } else {
            lowStreak = 0;
            highStreak = 0;
        }
        if (confidentNow && lowStreak >= 2) confidentNow = false;
        if (!confidentNow && highStreak >= 2) confidentNow = true;
        return confidentNow;
    }

    // ---- diagnostics: measure and dump -------------------------------------

    /**
     * Run the interpolation again in its reporting mode, average the frame to
     * one texel on the GPU, and read that back; then one small readback of the
     * 32 px cells, because a mean cannot tell a black patch from a scatter.
     */
    private void measure(float phase) {
        if (probe == null) {
            probe = new Target();
            probe.allocateAveraging(colour[0].width, colour[0].height);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probe.framebuffer);
        interpolate(phase, true);
        // Unbound before the mip generation, which is undefined on a texture
        // still attached to the bound framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, probe.texture);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        final java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probe.topFramebuffer);
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        measuredSynthDistance = (pixel.get(0) & 0xff) / 255f;
        measuredDark = (pixel.get(1) & 0xff) / 255f;
        measuredBaseDistance = (pixel.get(2) & 0xff) / 255f;
        measuredShadow = (pixel.get(3) & 0xff) / 255f;
        measuredPhase = phase;
        measuredAt = SystemClock.uptimeMillis();

        measuredPatchCells = 0;
        measuredEdgeCells = 0;
        measuredWorstCell = 0f;
        if (probe.cellFramebuffer != 0) {
            final int count = probe.cellWidth * probe.cellHeight;
            if (cells == null || cells.capacity() < count * 4) {
                cells = java.nio.ByteBuffer.allocateDirect(count * 4).order(java.nio.ByteOrder.nativeOrder());
            }
            cells.position(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probe.cellFramebuffer);
            GLES20.glReadPixels(0, 0, probe.cellWidth, probe.cellHeight,
                                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, cells);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            // Border cells legitimately fetch off the frame during a pan;
            // interior ones never should.
            for (int i = 0; i < count; i++) {
                final float escaped = (cells.get(i * 4 + 1) & 0xff) / 255f;
                final int cx = i % probe.cellWidth, cy = i / probe.cellWidth;
                final boolean edge = cx < 2 || cy < 2
                    || cx >= probe.cellWidth - 2 || cy >= probe.cellHeight - 2;
                if (!edge && escaped > measuredWorstCell) measuredWorstCell = escaped;
                if (escaped <= 0.5f) continue;
                if (edge) measuredEdgeCells++; else measuredPatchCells++;
            }
            measuredCellTotal = count;
        }
        renderer.viewportNeedsUpdate = true;
    }

    /**
     * Under {@code FG_LOG=dump}: the shader's inputs and its output, written to
     * app storage a few times per session for {@code tools/frame-bench/dump.py}
     * to replay. It stalls, so it is gated, spaced and counted.
     */
    private static final long DUMP_INTERVAL_MS = 4000;
    private static final int DUMP_LIMIT = 6;
    private long dumpedAt = 0;
    private int dumpsWritten = 0;

    private void maybeDump(float phase) {
        if (dumpsWritten >= DUMP_LIMIT) return;
        final long now = SystemClock.uptimeMillis();
        if (dumpedAt != 0 && now - dumpedAt < DUMP_INTERVAL_MS) return;
        // Near the middle, where a fault is largest, and only while the scene
        // moves by more than the matcher's own noise.
        if (Math.abs(phase - 0.5f) > 0.13f || lastDominantPx < 24f) return;
        dumpedAt = now;

        final java.io.File dir = new java.io.File(
            renderer.xServerView.getContext().getFilesDir(),
            "fgdump/" + String.format(java.util.Locale.US, "%02d", dumpsWritten));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            say("fg dump: cannot create " + dir);
            dumpsWritten = DUMP_LIMIT;
            return;
        }
        try {
            readTarget(colour[oldest()], GLES20.GL_UNSIGNED_BYTE, 4, new java.io.File(dir, "older.rgba"));
            readTarget(latestColour(), GLES20.GL_UNSIGNED_BYTE, 4, new java.io.File(dir, "newer.rgba"));
            readTarget(output, GLES20.GL_UNSIGNED_BYTE, 4, new java.io.File(dir, "shown.rgba"));
            // Packed: forward RG, backward BA. Filtered, and before the median.
            readTarget(fields[fieldCurrent], GLES20.GL_FLOAT, 16, new java.io.File(dir, "field.f32"));
            readTarget(merged, GLES20.GL_FLOAT, 16, new java.io.File(dir, "merged.f32"));
            final String json = String.format(java.util.Locale.US,
                "{\"width\": %d, \"height\": %d, \"lumaWidth\": %d, \"lumaHeight\": %d,"
                    + " \"gridWidth\": %d, \"gridHeight\": %d, \"blockX\": %d, \"blockY\": %d,"
                    + " \"phase\": %.4f, \"fieldSign\": %.0f, \"consistency\": 1, \"packed\": 1,"
                    + " \"fieldMagnitude\": %.0f, \"interval\": %d, \"multiple\": %d,"
                    + " \"realFrames\": %d, \"agreement\": %.3f, \"frameDiff\": %.4f,"
                    + " \"dominantPx\": %.1f}\n",
                colour[0].width, colour[0].height, luma[0].width, luma[0].height,
                merged.width, merged.height, blockX, blockY,
                phase, FIELD_SIGN, lastDominantPx, smoothedInterval / 1000000L, activeMultiple,
                realFrames, lastAgreement, lastFrameDiff, lastDominantPx);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "meta.json"))) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            dumpsWritten++;
            say("fg dump: wrote " + dir + " at phase " + phase);
        } catch (java.io.IOException e) {
            say("fg dump: " + e);
            dumpsWritten = DUMP_LIMIT;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            renderer.viewportNeedsUpdate = true;
        }
    }

    private void readTarget(Target target, int type, int bytesPerTexel, java.io.File file)
            throws java.io.IOException {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer
            .allocateDirect(target.width * target.height * bytesPerTexel)
            .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer);
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        GLES20.glReadPixels(0, 0, target.width, target.height, GLES20.GL_RGBA, type, buffer);
        final int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new java.io.IOException("glReadPixels 0x" + Integer.toHexString(error)
                + " for " + file.getName());
        }
        buffer.rewind();
        try (java.nio.channels.FileChannel channel = new java.io.FileOutputStream(file).getChannel()) {
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private void renderToTarget(ScreenMaterial material, int source, Target destination) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destination.framebuffer);
        blit(material, source, destination.width, destination.height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * Draw a texture over the whole of the current target, blending off: the
     * compositor leaves SRC_ALPHA blending on, which is wrong for a copy.
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
        renderer.invalidateBoundWindowMaterial();
    }

    private int oldest() { return 1 - newest; }

    /** The slot the next real frame is composited into: the older of the two. */
    private Target writeColour() { return colour[oldest()]; }

    private Target latestColour() { return colour[newest]; }

    private Target writeLuma() { return luma[oldest()]; }

    // ---- allocation --------------------------------------------------------

    private boolean ensureTargets() {
        final int generation = GLRenderer.contextGeneration();
        final int width = renderer.guestWidth();
        final int height = renderer.guestHeight();
        if (width <= 0 || height <= 0) return false;
        if (colour[0] != null && allocWidth == width && allocHeight == height
                && allocGeneration == generation) {
            return true;
        }

        // Only delete names that still mean something: a new generation means
        // the objects went with the context, and the names may be reused.
        release(colour[0] != null && allocGeneration == generation);

        for (int i = 0; i < 2; i++) {
            colour[i] = new Target();
            colour[i].allocate(width, height, GLES30.GL_RGBA8, GLES20.GL_LINEAR);
        }
        output = new Target();
        output.allocate(width, height, GLES30.GL_RGBA8, GLES20.GL_LINEAR);

        if (motionEstimationSupported()) allocateTier1(width, height);

        allocWidth = width;
        allocHeight = height;
        allocGeneration = generation;
        newest = 1;
        realFrames = 0;
        lastRealFrameNanos = 0;
        recentAt = 0;
        recentHeld = 0;
        return true;
    }

    /**
     * Everything tier 1 needs, or nothing. The luma pair must be a whole
     * number of search blocks; the coarse pair likewise; and the field targets
     * must be colour-renderable, which ES 3.2 guarantees for RGBA16F and is
     * checked anyway because guessing wrong is a blank field.
     */
    private void allocateTier1(int width, int height) {
        final int[] value = new int[1];
        GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM, value, 0);
        blockX = Math.max(1, value[0]);
        GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM, value, 0);
        blockY = Math.max(1, value[0]);

        final int lumaW = (width / blockX) * blockX;
        final int lumaH = (height / blockY) * blockY;
        final int coarseW = (lumaW / COARSE_DIVISOR / blockX) * blockX;
        final int coarseH = (lumaH / COARSE_DIVISOR / blockY) * blockY;
        if (lumaW <= 0 || lumaH <= 0 || coarseW < blockX * 2 || coarseH < blockY * 2) {
            say("tier 1 unavailable: guest " + width + "x" + height + " is too small for block "
                + blockX + "x" + blockY + " at a quarter size");
            return;
        }
        final int gridW = lumaW / blockX, gridH = lumaH / blockY;

        for (int i = 0; i < 2; i++) {
            luma[i] = new Target();
            luma[i].allocate(lumaW, lumaH, GLES30.GL_R8, GLES20.GL_LINEAR);
            lumaCoarse[i] = new Target();
            lumaCoarse[i].allocate(coarseW, coarseH, GLES30.GL_R8, GLES20.GL_LINEAR);
        }
        warpedLuma = new Target();
        warpedLuma.allocate(lumaW, lumaH, GLES30.GL_R8, GLES20.GL_LINEAR);
        // NEAREST on every field the interpolation reads: the four blocks are
        // wanted as four answers, and the shader does its own blending. LINEAR
        // on the prior, which is a warp displacement and would otherwise step
        // the warped picture in 32 px terraces.
        coarseVectors = new Target();
        coarseVectors.allocate(coarseW / blockX, coarseH / blockY, GLES30.GL_RGBA16F, GLES20.GL_LINEAR);
        residual = new Target();
        residual.allocate(gridW, gridH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
        residualBack = new Target();
        residualBack.allocate(gridW, gridH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
        final Target packed = new Target();
        packed.allocate(gridW, gridH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
        for (int i = 0; i < 3; i++) {
            fields[i] = new Target();
            fields[i].allocate(gridW, gridH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
        }
        confidence = new Target();
        confidence.allocate(1, 1, GLES30.GL_RGBA8, GLES20.GL_NEAREST);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, packed.framebuffer);
        final int complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (complete != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "cannot render to RGBA16F (0x" + Integer.toHexString(complete)
                + "); tier 1 is off");
            packed.release();
            releaseTier1(true);
            return;
        }
        merged = packed;
        fieldHistory = -1;
        fieldCurrent = -1;
        say("tier 1 ready: block " + blockX + "x" + blockY + ", luma " + lumaW + "x" + lumaH
            + ", grid " + gridW + "x" + gridH + ", coarse " + coarseW + "x" + coarseH
            + " (window x" + (lumaW / coarseW) + ")");
    }

    private void releaseTier1(boolean deleteObjects) {
        if (deleteObjects) {
            for (int i = 0; i < 2; i++) {
                if (luma[i] != null) luma[i].release();
                if (lumaCoarse[i] != null) lumaCoarse[i].release();
            }
            for (int i = 0; i < 3; i++) if (fields[i] != null) fields[i].release();
            if (warpedLuma != null) warpedLuma.release();
            if (coarseVectors != null) coarseVectors.release();
            if (residual != null) residual.release();
            if (residualBack != null) residualBack.release();
            if (merged != null) merged.release();
            if (confidence != null) confidence.release();
            if (confidenceBuffer != 0) GLES20.glDeleteBuffers(1, new int[] {confidenceBuffer}, 0);
        }
        luma[0] = luma[1] = lumaCoarse[0] = lumaCoarse[1] = null;
        fields[0] = fields[1] = fields[2] = null;
        warpedLuma = coarseVectors = residual = residualBack = merged = confidence = null;
        confidenceBuffer = 0;
        confidencePending = false;
        fieldHistory = fieldCurrent = -1;
        motionValid = false;
    }

    private void release(boolean deleteObjects) {
        if (deleteObjects) {
            for (int i = 0; i < 2; i++) if (colour[i] != null) colour[i].release();
            if (output != null) output.release();
            if (probe != null) probe.release();
        }
        releaseTier1(deleteObjects);
        colour[0] = colour[1] = null;
        output = probe = null;
        // A name held outside a Target; repeatLastPresent guards on it.
        repeatTexture = 0;
        announced = false;
    }

    // ---- the log -----------------------------------------------------------

    /**
     * Once a second, in the categories asked for. One line is always printed:
     * a container with frame generation on and nothing to say is the answer.
     */
    private void report() {
        final long now = SystemClock.uptimeMillis();
        if (timing) {
            captureTimer.report(now);
            lumaTimer.report(now);
            estimateTimer.report(now);
            medianTimer.report(now);
            interpolateTimer.report(now);
            tier0Timer.report(now);
        }
        if (now - reportedAt < 1000) return;
        final long elapsed = reportedAt == 0 ? 1000 : Math.max(1, now - reportedAt);
        reportedAt = now;
        final long realThisSecond = realFrames - reportedRealFrames;
        reportedRealFrames = realFrames;

        say("real " + realFrames + ", tier0 " + tier0Frames + ", tier1 " + tier1Frames
            + ", skipped " + skipped + ", " + multiple + "x");

        if (wants("pacing")) {
            if (timestamps.hasData()) {
                say(timestamps.describe(vsyncPeriodNanos()));
            } else if (timestamps.unavailable()) {
                say("fg presented: this surface answers none of the frame timestamps;"
                    + " every cadence figure below is a draw schedule.");
            }
            if (presentGaps > 0) {
                say(String.format(
                    "fg cadence: presented every %.1f ms mean, %.1f shortest, %.1f longest,"
                        + " over %d gaps; %d shared a refresh with the present before them",
                    presentGapTotal / (float)presentGaps / 1e6f,
                    presentGapMin / 1e6f, presentGapMax / 1e6f, presentGaps, collisions));
                collisions = 0;
                presentGapMin = Long.MAX_VALUE;
                presentGapMax = 0;
                presentGapTotal = 0;
                presentGaps = 0;
            }
            final float perSecond = realThisSecond * 1000f / elapsed;
            say(String.format(
                "fg pacing: %.1f real/s, %.1f synthesised/s, %.1f presented/s, %d skipped,"
                    + " interval %.1f ms (%.1f fps), %dx asked / %dx fitted into a %.2f ms refresh, %s",
                perSecond, tier1Frames * 1000f / elapsed, perSecond + tier1Frames * 1000f / elapsed,
                skipped, smoothedInterval / 1e6f,
                smoothedInterval > 0 ? 1e9f / smoothedInterval : 0f,
                multiple, activeMultiple, vsyncPeriodNanos() / 1e6f,
                motionValid ? "field valid" : "NO FIELD -- tier 0 or real frames only"));
            say(String.format(
                "fg confidence: %d intervals shown as real frames only this second;"
                    + " last agreement %.0f%% (floor %.0f%%), frame difference %.0f levels"
                    + " (ceiling %.0f), dominant motion %.0f px",
                lowConfidenceIntervals, lastAgreement * 100f, MIN_AGREEMENT * 100f,
                lastFrameDiff * 255f, MAX_FRAME_DIFF * 255f, lastDominantPx));
            say(String.format(
                "fg gate: interpolating up to %.0f ms (guest habit %.0f ms), interval now %.0f ms -- %s",
                worthInterpolating() / 1e6f, baselineInterval / 1e6f, smoothedInterval / 1e6f,
                smoothedInterval > worthInterpolating() ? "OFF, guest has stopped" : "interpolating"));
        }

        if (wants("field")) {
            say(String.format(
                "fg field: block %dx%d, grid %dx%d, luma %dx%d, coarse %dx%d, %d median passes,"
                    + " matcher refusals %d, dominant motion %.0f px, agreement %.0f%%",
                blockX, blockY,
                merged != null ? merged.width : 0, merged != null ? merged.height : 0,
                luma[0] != null ? luma[0].width : 0, luma[0] != null ? luma[0].height : 0,
                coarseVectors != null ? coarseVectors.width : 0,
                coarseVectors != null ? coarseVectors.height : 0,
                MEDIAN_PASSES, estimateFailures, lastDominantPx, lastAgreement * 100f));
        }

        if (wants("layers")) say("fg layers: " + renderer.describeLayers());

        if (wants("quality")) {
            // Of the part of the frame that changed: how much further from
            // frame N the synthesis landed than a perfect interpolation at
            // this phase would have. Zero is perfect at every phase.
            final float moving = measuredShadow;
            final float closeness = measuredBaseDistance > 0.0001f
                ? measuredSynthDistance / measuredBaseDistance : 0f;
            final float excess = moving > 0.001f ? closeness - (1f - measuredPhase) : 0f;
            say(String.format(
                "fg truth: %+.0f%% further from the real frame than a perfect interpolation"
                    + " at this phase (0%% is perfect), sits at %.0f%% where phase alone gives"
                    + " %.0f%%, %.3f%% fetched off the frame, %.0f%% of frame moving, drawn at %.0f%%",
                excess * 100f, closeness * 100f, (1f - measuredPhase) * 100f,
                measuredDark * 100f, moving * 100f, measuredPhase * 100f));
            say(String.format(
                "fg patches: %d interior cells of %d take over half their content from off"
                    + " the frame (%d more at the border, which a pan cannot avoid),"
                    + " worst interior cell %.0f%%",
                measuredPatchCells, measuredCellTotal, measuredEdgeCells, measuredWorstCell * 100f));
        }

        tier0Frames = 0;
        tier1Frames = 0;
        skipped = 0;
        estimateFailures = 0;
        lowConfidenceIntervals = 0;
    }
}
