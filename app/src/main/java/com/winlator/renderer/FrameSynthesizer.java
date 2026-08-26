package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.renderer.material.InterpolateMaterial;
import com.winlator.renderer.material.MedianMaterial;
import com.winlator.renderer.material.SignMaterial;
import com.winlator.renderer.material.ScreenMaterial;

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
 * <h2>The two tiers, best first</h2>
 *
 * <p><b>Tier 1 -- hardware motion estimation and a bilateral interpolation.</b>
 * {@code GL_QCOM_motion_estimation} is the sibling extension, and unlike the
 * other one it works: measured on this device at 44,590 non-zero vectors across
 * a moving scene, x in [-113, 90], y in [-110, 106]. It is a fixed-function
 * block matcher, so the expensive half of frame generation is free, and what is
 * left to write is a search over what it produced. See {@link
 * com.winlator.renderer.material.InterpolateMaterial} for that, which is where
 * the picture quality actually lives.
 *
 * <p><b>Tier 0 -- window translation.</b> We are the compositor. When a window
 * moves we know the translation exactly, so a synthesised frame re-composites
 * the same window textures at interpolated positions. Not an estimate: the
 * motion, replayed part of the way. No block matching, no warping, no
 * inpainting, and therefore none of their artefacts.
 *
 * <p><b>It is the fallback, and it used to be the preference.</b> Being exact is
 * not the same as being sufficient. The block matcher measures apparent motion
 * whatever causes it, so tier 1's field already contains a window translation --
 * while a window that moves *and* whose contents move is a case tier 0 cannot
 * express at all, because it replays the translation with the contents frozen at
 * frame N. Tier 1 covers the union, so tier 0 now runs only where there is no
 * field to be had: a device without the extension, or the first frame after a
 * resolution change.
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

        /**
         * VESSEL: diagnostics only. A full mip chain, and a second framebuffer
         * bound to the 1x1 top of it.
         *
         * <p>**Averaging on the GPU is what makes measuring the whole frame
         * affordable.** Reading a full-resolution frame back is 14 MB and stalls
         * the render thread -- a diagnostic that did exactly that caused an ANR
         * earlier in this project. {@code glGenerateMipmap} reduces the frame to
         * a single texel, and reading one texel costs four bytes and no stall, so
         * a per-frame statistic over every pixel becomes something that can be
         * left switched on.
         */
        int topFramebuffer;
        int levels = 1;
        /**
         * VESSEL: a framebuffer on the mip level whose texels are about 32px.
         *
         * <p><b>Because the 1x1 top cannot tell a patch from a sprinkle.</b> The
         * whole-frame mean of invented content read 1.5% while the screen had
         * black holes in it, and it would read the same for 1.5% of pixels
         * scattered evenly -- which is invisible. The eye responds to connected
         * area, and a mean over 900,000 pixels is constructed to destroy exactly
         * that information.
         *
         * <p>One level up from nothing costs nothing: 40x22 texels is 3.5 KB,
         * and a readback stalls on the round trip rather than on the bytes, so
         * it is the same stall the 1x1 already pays. Each texel is then the
         * invented fraction of its own 32x32 cell, and counting the cells above
         * a half is the patch metric that a mean cannot express.
         */
        int cellFramebuffer;
        int cellWidth, cellHeight;

        void allocateAveraging(int width, int height) {
            this.width = width;
            this.height = height;
            int count = 1;
            for (int size = Math.max(width, height); size > 1; size >>= 1) count++;
            this.levels = count;

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

            // Level 5 is a 32x32 reduction, so each texel is one cell. Clamped
            // to what the chain actually has, and skipped for a target too
            // small to have that many levels -- the sign probe is 64x36.
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
            if (cellFramebuffer != 0) {
                GLES20.glDeleteFramebuffers(1, new int[] {cellFramebuffer}, 0);
                cellFramebuffer = 0;
            }
            if (topFramebuffer != 0) {
                GLES20.glDeleteFramebuffers(1, new int[] {topFramebuffer}, 0);
                topFramebuffer = 0;
            }
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
    private final SignMaterial signMaterial = new SignMaterial();
    private final MedianMaterial medianMaterial = new MedianMaterial();
    private final InterpolateMaterial interpolateMaterial = new InterpolateMaterial();

    private final GpuTimer captureTimer = new GpuTimer("tier1 capture+blit");
    private final GpuTimer lumaTimer = new GpuTimer("tier1 luma");
    private final GpuTimer estimateTimer = new GpuTimer("tier1 estimate");
    private final GpuTimer medianTimer = new GpuTimer("tier1 median");
    private final GpuTimer interpolateTimer = new GpuTimer("tier1 interpolate");
    private final GpuTimer tier0Timer = new GpuTimer("tier0 recomposite");

    private Target vectors;
    /**
     * **A median filter used to sit between the matcher and the interpolation,
     * and it was removed unmeasured.**
     *
     * <p>It is the filtering pass FSR3 runs between every level of its pyramid,
     * and the motion vector smoothing step the frame-rate-up-conversion
     * literature puts between estimation and compensation. At 14,400 pixels it
     * cost nothing. But unlike the overlapped blending, its benefit here was
     * never demonstrated in isolation, and cheap and standard is not evidence.
     * The field now reaches the interpolation as the matcher produced it; if
     * what it was suppressing comes back, that is the measurement that was
     * missing all along.
     */
    /**
     * Where an interpolated frame is built, at the guest's resolution.
     *
     * <p>It needs a target of its own because the result is upscaled on the way to
     * the screen and the two colour slots hold real frames. One more guest-sized
     * RGBA8 buys interpolating 0.9 megapixels instead of 3.5.
     */
    private Target output;
    /** Diagnostics only; null unless a category that needs it was asked for. */
    private Target probe;
    /**
     * Where {@link SignMaterial} counts its votes. Small on purpose.
     *
     * <p>The question is one bit about the whole field, so it does not need
     * resolution -- it needs enough samples that a handful of ambiguous pixels
     * cannot swing it. 64x36 is 2,304 votes, and the whole thing including the
     * mip reduction is far below the frame it protects.
     */
    private Target signProbe;

    /**
     * VESSEL: where the field is filtered, ping-ponged across the passes.
     *
     * <p><b>This existed, was removed unmeasured, and the removal was wrong.</b>
     * The note left behind said the filter was cheap and standard but that its
     * benefit here had never been demonstrated in isolation, and that if what it
     * suppressed came back, that would be the measurement that was missing. It
     * came back, and it is the waviness on straight edges.
     *
     * <p>Measured on a bench where the answer is known -- a beam and the wall
     * behind it panning at different rates, as depth parallax makes them, with a
     * straight vertical edge between. The edge's deviation from the straight line
     * it should be, in pixels:
     *
     * <pre>
     *   ground truth                0.013
     *   unfiltered field            9.540
     *   two passes, unanchored      2.880
     *   three passes, unanchored    3.496   <- worse; the field had drifted
     *   three passes, anchored      2.136
     *   six passes, anchored        0.576
     * </pre>
     *
     * <p>Ninety-four per cent of the waviness, and the error either side of the
     * edge improves with it rather than being traded away. Unanchored the pass
     * count was a trade -- a third pass measured worse than the second, because
     * each pass drew its candidates only from the one before and the field drifted
     * away from anything the matcher had observed. Keeping the original on offer
     * removes that ceiling; see {@link MedianMaterial}.
     *
     * <p>Two targets because a pass reads the whole field and writes the whole
     * field, so it cannot be its own destination.
     */
    private final Target[] filtered = new Target[2];
    /** Which of {@link #filtered} the interpolation should read, or -1 for none. */
    private int filteredIndex = -1;
    /**
     * <b>Ten, measured on four scenes, and the reason it is not six is that the
     * objection to ten was tested and failed.</b>
     *
     * <p>Anchored passes keep improving the straight edge right to ten, where the
     * waviness reaches 0.013 px -- the ground truth's own figure. That was held
     * back to six at first on the suspicion that reaching it exactly meant the
     * field had converged to piecewise constant, which is correct for the rigid
     * motions both original bench scenes contain and would be wrong for real
     * depth parallax, where motion is a smooth gradient and a locally-constant
     * field would staircase it into block-wide steps.
     *
     * <p>So a third scene was built to catch exactly that: a ground plane with
     * horizontal sweep ramping from 8 px at the horizon to 72 at the front, and a
     * metric that reports the staircase specifically -- the second difference of
     * the field down the rows, which is zero for any straight ramp and spikes at
     * every step. It does not staircase. Banding *falls* with passes and settles:
     *
     * <pre>
     *   passes        waviness   objEdge   gradBand   gradErr    ink
     *   none             9.540      6.70     4.0293     1.287   0.55
     *   six              0.576      4.92     1.1875     0.642   0.62
     *   ten              0.013      4.87     1.1861     0.641   0.68
     *   twenty               -         -     1.1889     0.639      -
     * </pre>
     *
     * <p>The anchor is why. The matcher's original vector encodes the true ramp
     * and stays a candidate on every pass, so the field cannot converge away from
     * it -- the same mechanism that lets the passes repeat is the one that
     * protects the gradient.
     *
     * <p>What ten costs is 0.13 levels on subtitle glyphs, against the 5.8 levels
     * the per-pixel static test wins there, and about 0.9 ms of a 33 ms budget.
     */
    private static final int MEDIAN_PASSES = 10;

    /**
     * Roughly how far {@code GL_QCOM_motion_estimation} can see, in luma pixels.
     *
     * <p>Not reported by the extension, so this is measured rather than declared:
     * vectors reached 113 px in the first survey of this driver and the field
     * stops rising at about 120. Printed beside the mean displacement only so a
     * log line says whether the scene is moving further than the matcher can
     * follow -- nothing branches on it.
     */
    private static final int SEARCH_WINDOW_PX = 112;

    private int blockX = 8, blockY = 8;

    /**
     * VESSEL: which categories the container's {@code FG_LOG} row asked for.
     *
     * <p>Empty is the normal case, and then none of this costs anything: the
     * measurement pass is never allocated, never drawn, and never read.
     */
    private java.util.Set<String> diagnostics = java.util.Collections.emptySet();

    public void setDiagnostics(java.util.Set<String> categories) {
        this.diagnostics = categories == null
            ? java.util.Collections.emptySet() : categories;
        this.announced = false;
    }

    private boolean wants(String category) {
        return diagnostics.contains(category) || diagnostics.contains("all");
    }

    /** Whether the one-time setup line has been printed for this allocation. */
    private boolean announced = false;

    /** Frame-wide measurements, most recently read back. See {@link #measure}. */
    private float measuredDark, measuredShadow;
    /**
     * Mean distance from the truth, synthesised and held, over moving pixels.
     *
     * <p>Their ratio replaces a count of pixels that was dominated by ties and
     * measured nothing for a month. See {@link InterpolateMaterial}.
     */
    private float measuredSynthDistance, measuredBaseDistance;
    private long measuredAt = 0;
    /**
     * The phase the measured frame was actually drawn at.
     *
     * <p><b>Because the alternative was a constant, and the constant was wrong.</b>
     * {@link #measure} samples ONE frame, whichever happens to fall on the
     * once-a-second boundary, and that frame is drawn at 1/K, 2/K ... (K-1)/K --
     * never reliably at a half. The line reported "(50% is correct)" regardless,
     * so at 4x it compared a frame legitimately drawn at 0.25 or 0.75 against
     * 0.5 and called the difference an error. Readings of 55-83% were taken as
     * evidence of a systematic phase bias in the pipeline; they were evidence of
     * this string.
     */
    private float measuredPhase = 0f;
    /**
     * How many 32x32 cells are more than half invented, and the worst one.
     *
     * <p>The whole-frame mean beside them cannot distinguish a hole from a
     * sprinkle, and the difference is the difference between a broken screen
     * and an invisible one. See {@link #measure}.
     */
    private int measuredPatchCells = 0, measuredCellTotal = 0, measuredEdgeCells = 0;
    private float measuredWorstCell = 0f;
    /** Reused across readbacks; see {@link #measure}. */
    private java.nio.ByteBuffer cells;

    private int allocWidth = 0;
    private int allocHeight = 0;
    private int allocGeneration = -1;

    private int multiple = 2;
    /**
     * Which way the field points: +1, -1, or 0 while it is still unknown.
     *
     * <p>Latched, because it is a property of what the driver means by its own
     * output rather than of any frame, and it cannot change while the context
     * lives. See {@link SignMaterial} for why deciding this per pixel -- which is
     * what this replaces -- shredded every synthesised frame.
     */
    private float fieldSign = 0f;
    /** Share of the last probe that was moving enough to have an opinion. */
    private float signVotes = 0f;
    /** When the sign was last probed, so a still scene retries rather than sticks. */
    private long signProbedAt = 0;
    /** A decisive vote awaiting a second, agreeing one. See {@link #probeFieldSign}. */
    private float pendingSign = 0f;

    /**
     * Mean length of the motion field, in luma pixels. See {@link #probeFieldSign}.
     *
     * <p>Reported so that harm and displacement can be read off the same log and
     * plotted against each other. Every estimate of "how fast is too fast" so
     * far has come from a stand-in block matcher on the laptop, and that matcher
     * behaves nothing like the hardware one at low speed -- it put 19% of blocks
     * past 100 pixels on a nearly-static scene, where the device reports 0.0%
     * harm. This is the field the driver actually produced.
     */
    private float fieldMagnitude = 0f;

    /**
     * Vote on the field's sign, and latch the answer once it is decisive.
     *
     * <p>Runs at most once a second and stops entirely once latched, so the
     * steady-state cost is nothing. It has to be a readback -- the sign has to
     * reach a uniform, and the CPU is what sets uniforms -- and one a second is
     * the rate the diagnostics already run at safely. Deciding it on the GPU per
     * pixel is precisely the thing that was wrong.
     */
    private void probeFieldSign() {
        // **Two reasons to run, and the sign is only one of them.**
        //
        // The same reduction that votes on the sign also carries how far the
        // scene moved, in luma pixels, which is the number every attempt to
        // bound motion compensation has been missing. It has to keep coming
        // after the sign has latched, so the probe now runs while `field` is
        // asked for even though it has nothing left to decide.
        final boolean needSign = fieldSign == 0f;
        if (!needSign && !wants("field")) return;
        final long now = SystemClock.uptimeMillis();
        if (signProbedAt != 0 && now - signProbedAt < 1000) return;
        signProbedAt = now;

        if (signProbe == null) {
            signProbe = new Target();
            signProbe.allocateAveraging(64, 36);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, signProbe.framebuffer);
        GLES20.glViewport(0, 0, signProbe.width, signProbe.height);
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        signMaterial.use();
        renderer.quadVertices.bind(signMaterial.programId);
        signMaterial.setUniformBool(signMaterial.uniforms.flipY, false);
        signMaterial.setUniformVec2(signMaterial.signUniforms.motionScale,
                                    1f / Math.max(1, luma[0].width),
                                    1f / Math.max(1, luma[0].height));
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectors.texture);
        signMaterial.setUniformInt(signMaterial.uniforms.screenTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestLuma().texture);
        signMaterial.setUniformInt(signMaterial.signUniforms.lumaNewerTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousLuma().texture);
        signMaterial.setUniformInt(signMaterial.signUniforms.lumaOlderTexture, 2);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        for (int unit = 2; unit >= 0; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.invalidateBoundWindowMaterial();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, signProbe.texture);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        final java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, signProbe.topFramebuffer);
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.viewportNeedsUpdate = true;

        final float positive = (pixel.get(0) & 0xff) / 255f;
        signVotes = (pixel.get(1) & 0xff) / 255f;
        // Mean vector length over the whole field, back in luma pixels. See
        // SignMaterial's blue channel for why this is worth a readback.
        fieldMagnitude = (pixel.get(2) & 0xff);

        // Nothing left to decide once the sign is latched; the magnitude above
        // is the only reason this still runs.
        if (!needSign) return;

        // A still frame has no opinion: both signs fetch the same content, so the
        // vote is a tie whatever the truth is. Wait for real motion rather than
        // latch a coin toss.
        if (signVotes < 0.05f) return;

        // **Two decisive probes that agree, not one.**
        //
        // The underlying cost margin between the two hypotheses was measured at
        // five per cent -- 0.00192 against 0.00182 -- so a single frame voting
        // 65% one way is comfortably inside what chance produces. This latches
        // for the life of the context and there is no path that ever revisits
        // it, so a wrong latch inverts the field for the whole session, and an
        // inverted field displaces every pixel by twice its motion the wrong
        // way. That is the failure mode this same value already caused once,
        // when it was decided per pixel.
        //
        // Eighty per cent, and the same answer from two probes at least a second
        // apart -- which is two different moments of the scene. An undecided
        // probe clears the pending answer rather than leaving it to pair up with
        // a vote from some unrelated moment.
        final float share = positive / signVotes;
        final float vote = share > 0.8f ? 1f : (share < 0.2f ? -1f : 0f);
        if (vote == 0f) { pendingSign = 0f; return; }
        if (pendingSign != vote) { pendingSign = vote; return; }
        fieldSign = vote;

        Log.i(TAG, String.format(
            "fg sign: field points %s (%.0f%% of moving pixels agree, %.0f%% of the"
                + " frame moving) -- latched",
            fieldSign > 0 ? "forward" : "backward", share * 100f, signVotes * 100f));
    }

    /**
     * VESSEL: how long one display refresh lasts, from the display itself.
     *
     * <p>Two separate things needed this and neither had it. The pacer was
     * deciding whether a vsync was the nearest one to its target using half of a
     * 120 Hz refresh, hardcoded -- on a panel running at 60 that is 4 ms where it
     * should be 8.3, so a slot due at 25 ms with a vsync available at 18 ms
     * waited and fired at 34.6 instead: 9.6 ms late rather than 7 ms early. And
     * nothing knew how many frames an interval could physically hold, which is
     * what {@link #effectiveMultiple} exists to answer.
     *
     * <p>Asked of the {@code Display} rather than inferred from timestamps. The
     * panel knows, it is exact, and a sampled estimate would have to distinguish
     * consecutive vsyncs from ones two apart without any way to tell.
     */
    @Override
    public long vsyncPeriodNanos() {
        final android.view.Display display = renderer.xServerView.getDisplay();
        final float hz = display != null ? display.getRefreshRate() : 0f;
        return hz > 1f ? (long)(1_000_000_000L / hz) : 0L;
    }

    /**
     * How many frames this interval can actually carry, which is not always the
     * multiple that was asked for.
     *
     * <p><b>An interval cannot hold more frames than it holds refreshes.</b> At
     * 8x with a 90 fps limit in smoothness the request is 720 presented frames a
     * second into a panel whose fastest mode is 165 -- three quarters of them
     * have nowhere to go, and every one costs a full interpolation pass to be
     * discarded. The clamp is the arithmetic that says so.
     *
     * <p>Runtime rather than a filtered list of settings, because the guest's
     * interval is what decides it and the guest's interval moves. A 30 fps limit
     * in smoothness nominally leaves 33 ms between real frames; when the guest
     * actually delivers every 45 ms the number of refreshes in that gap changes
     * with it, and so does the answer.
     */
    /**
     * The widest gap between real frames still worth interpolating across.
     *
     * <p><b>The multiple adapting to a stall was filling it with invention.</b>
     * Measured on Requiem at 8x: the guest fell to 7.3 frames a second, a 137 ms
     * interval, and the refresh check fitted all eight -- there are sixteen
     * refreshes in 137 ms, so by that test nothing was wrong. But the refresh
     * check asks whether there is somewhere to *put* a frame and never whether
     * the frame is worth making. Every part of this pipeline degrades with the
     * distance between the two real frames it reads, and 137 ms is twice the
     * 66 ms gap that already produces the ambiguity, the ripple and the ghosting.
     *
     * <p>A hundred milliseconds, ten frames a second, and derived rather than
     * chosen: the widest gap any configuration the settings offer can
     * legitimately produce is a 24 fps limit at 2x, which leaves the guest at 12
     * and the gap at 83 ms. So every valid setup passes, and anything wider means
     * the guest has stopped rather than slowed -- where the honest response is to
     * show the frame that exists rather than invent seven around it.
     */
    private static final long WORTH_INTERPOLATING_NANOS = 100_000_000L;

    /**
     * VESSEL: the same question asked of this guest rather than of a constant.
     *
     * <p><b>"The guest has stopped rather than slowed" is a relative statement
     * and 100 ms cannot express it.</b> A hundred milliseconds is a stall for a
     * title running at 15 fps and the perfectly normal interval of one capped at
     * 10, and the fixed number treats them the same. Measured across two games
     * on this device, that is not academic: Metro holds 61 to 69 ms and never
     * approaches the line, while RE9 swings 44.8 to 140 ms and crosses it five
     * times in sixty-one seconds. Each crossing takes the multiple from 4x to 1x
     * -- about sixty presented frames a second to about seven -- and back.
     *
     * <p>{@link #idleGate} already made this move for the other gate in this
     * file, and for the same reason: a threshold that scales with what the
     * pipeline is actually configured to do, rather than one chosen once against
     * one configuration.
     *
     * <p>So the gate is now twice the interval this guest habitually keeps. The
     * floor is the old constant, so nothing is ever gated more eagerly than
     * before; the ceiling is 250 ms, because past that the guest has genuinely
     * stopped -- a loading screen or a shader compile -- and inventing three
     * frames across it is what the original comment was written about.
     *
     * <p><b>What this is not.</b> It is not a claim that the frames it now
     * permits look good. Nothing measured this session predicts harm --
     * displacement, motion diversity, contrast and brightness all came out flat
     * -- so there is no evidence either way about the picture. What it removes is
     * an arbitrary line that one game happens to sit on top of.
     */
    private static final long WORTH_INTERPOLATING_CEILING_NANOS = 250_000_000L;

    /**
     * How far past its own habit a guest may drift before it counts as stopped.
     *
     * <p>Normalised at 4x and scaled inversely with the multiple by {@link
     * #worthInterpolating}, because how stale a pair may be depends on how many
     * frames are built from it. At a 66 ms habit that lands the gate at 198 ms
     * for 4x, 250 for 2x (the ceiling), and 100 for 8x -- which recovers the
     * original hand-picked constant at exactly the configuration it was picked
     * against, Requiem at 8x with a 137 ms interval.
     *
     * <p>Three rather than two, widened after the two-times gate still cut RE9
     * off during ordinary play. Its habit is about 66 ms and its intervals reach
     * 140, so a gate at 132 sat inside the range the game actually occupies and
     * fired five times in sixty-one seconds -- each one a drop from about sixty
     * presented frames a second to about seven. At three the gate lands near 198
     * and the whole of RE9's ordinary variation falls inside it, leaving the
     * ceiling to catch a guest that has genuinely stopped.
     *
     * <p>The ramp between the two was tried and taken out: grading the multiple
     * from the habit to the gate bought a gentler last transition and paid for
     * it with fewer frames through the middle of the range, where the guest
     * spends most of its time. Widening costs nothing in that range at all.
     *
     * <p>What it costs instead is at the far end: frames interpolated from a
     * pair up to 198 ms apart, which the original 100 ms constant existed to
     * forbid. That constant was chosen against Requiem at 8x, and two of its
     * premises did not survive this session -- the matcher turns out to be
     * accurate at 150 px rather than saturating at 112, and harm is flat against
     * displacement from 20 px to 200. Neither says a 198 ms pair is fine; both
     * say the reasoning that produced 100 ms was built on measurements that have
     * since been corrected.
     */
    private static final long GATE_MULTIPLE = 3L;

    /**
     * How the multiple gives ground between the guest's habit and the gate.
     *
     * <p>One would be a straight line, and a straight line was measured worse
     * than the cliff it replaced: it drops to 3x the moment the interval passes
     * the habit, so it spends the busiest part of the range showing fewer frames
     * than doing nothing would have. Below one the curve stays near what was
     * asked for and then turns down sharply, so the top multiple keeps the
     * widest band of interval and each step below it a narrower one.
     */
    private static final double GATE_CURVE = 0.35;

    /**
     * A long-horizon view of the guest's interval, for {@link #worthInterpolating}.
     *
     * <p>Separate from {@link #recent} on purpose. That one holds nine samples so
     * it can follow a real change in rate within about half a second, which is
     * what the aim needs; this one has to describe the guest's habit and must not
     * move when the guest hitches. Sixty-four samples is about four seconds at 15
     * fps -- long enough that a stall lasting several frames cannot shift the
     * middle, short enough to follow a genuine change of scene or settings.
     */
    private final long[] baseline = new long[64];
    private int baselineAt = 0;
    private int baselineHeld = 0;

    private long baselineInterval() {
        if (baselineHeld == 0) return 0;
        final long[] sorted = new long[baselineHeld];
        System.arraycopy(baseline, 0, sorted, 0, baselineHeld);
        java.util.Arrays.sort(sorted);
        return sorted[baselineHeld / 2];
    }

    /** The widest gap still worth interpolating across, for THIS guest. */
    private long worthInterpolating() {
        final long base = baselineInterval();
        if (base <= 0) return WORTH_INTERPOLATING_NANOS;
        // **Tighter the more frames are invented from the pair.** How stale a
        // pair may be depends on how much is built from it: one interpolation
        // from a 200 ms pair is a compromise, seven from the same pair is the
        // Requiem case this gate exists for. So the allowance scales inversely
        // with the multiple, normalised at 4x.
        final long allowed = base * GATE_MULTIPLE * 4L / Math.max(2, multiple);
        return Math.min(Math.max(allowed, WORTH_INTERPOLATING_NANOS),
                        WORTH_INTERPOLATING_CEILING_NANOS);
    }

    private int effectiveMultiple() {
        // **Not clamped to the fps limit, deliberately.**
        //
        // The guest overruns its cap -- measured at 21.5 fps against a 15 fps
        // limit that vkd3d-proton had accepted and applied, because its limiter
        // leans on present-wait timing this driver does not provide. Doubling
        // that presents 43.9 frames a second where the limit says 30.
        //
        // Holding it back to 30 was tried and taken out again. The saving is not
        // where it first appeared to be: at 97% GPU and 15.5 guest frames a
        // second a real frame costs about 63 ms of GPU, against 1.4 ms for a
        // synthesised one -- a factor of forty-five. So the expensive part of an
        // overrun is the six extra *real* frames the guest rendered, which
        // nothing here can decline, and suppressing the cheap half saves under
        // one per cent. The remaining argument was pacing, and more frames
        // unevenly spaced still reads as smoother than fewer frames evenly
        // spaced at this rate.
        //
        // What is left are the two clamps that are not preferences: a gap too
        // wide to interpolate across, and an interval that cannot carry the
        // frames. See WORTH_INTERPOLATING_NANOS for the first.
        if (multiple < 2) return 1;

        // **Steps, but with the widest band first.**
        //
        // A linear ramp was tried and taken out: it fell from 4x to 3x as soon
        // as the interval passed the guest's habit, which spends most of the
        // range showing fewer frames than the plain cliff did, and on screen
        // that is a loss. A cliff is worse at one end; a linear ramp is worse
        // through the middle.
        //
        // The curve holds what was asked for across most of the range and
        // gives ground quickly near the gate, so each lower multiple occupies a
        // narrower band of interval than the one above it. With RE9's habit of
        // about 66 ms and a gate near 198:
        //
        //     up to ~120 ms   4x     the band the guest actually lives in
        //     120 to 170      3x
        //     170 to 197      2x
        //     beyond 198      1x     the guest has stopped
        //
        // The exponent is what makes the bands unequal. At 1.0 this is the
        // linear ramp that lost; below 1 the curve stays high and then turns
        // down, which is the shape asked for -- the gap for each step decreasing
        // progressively.
        final long gate = worthInterpolating();
        if (smoothedInterval > gate) return 1;
        int asked = multiple;
        final long habit = baselineInterval();
        if (habit > 0 && gate > habit && smoothedInterval > habit) {
            final double headroom = Math.max(0.0, Math.min(1.0,
                (double)(gate - smoothedInterval) / (double)(gate - habit)));
            asked = (int)Math.round(1.0 + (multiple - 1)
                * Math.pow(headroom, GATE_CURVE));
            asked = Math.max(1, Math.min(multiple, asked));
        }
        final long period = vsyncPeriodNanos();
        if (period <= 0 || smoothedInterval <= 0) return asked;
        final int refreshes = (int)(smoothedInterval / period);
        return Math.min(asked, Math.max(1, refreshes));
    }

    /**
     * The multiple actually in force for the interval now running.
     *
     * <p>Settled once per real frame and then used for every phase decision in
     * that interval -- the {@code 1/K} the composite presents, the offset in
     * {@link #phaseFor}, and the slots the pacer schedules. Recomputing it
     * per-present would let the three disagree inside one interval, which puts
     * the phases somewhere no frame belongs.
     */
    private int activeMultiple = 2;

    /**
     * The motion field to build frames from: filtered if that ran, raw if not.
     *
     * <p>One accessor rather than a decision at each use, so there is no path
     * where one pass reads the filtered field and another reads the matcher's.
     */
    private int fieldTexture() {
        return filteredIndex >= 0 ? filtered[filteredIndex].texture : vectors.texture;
    }

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
    /**
     * VESSEL: the last few real-frame intervals, for {@link #medianInterval}.
     *
     * <p><b>A mean is the wrong average for this signal.</b> The compositor
     * draws on guest damage, which is bursty: a load hitch, a shader compile or
     * one heavy frame produces a single interval two or three times the usual
     * one, and an exponential mean carries that outlier into the aim for the
     * several frames it takes to decay. Every prediction scheduled meanwhile is
     * aimed at an interval the guest is not running at.
     *
     * <p>A median throws the outlier away outright and returns the interval the
     * guest is actually keeping. This is what DXVK's frame-rate limiter does --
     * median of the last three of its measurements rather than an average -- and
     * AMD document the failure the other way round in FSR3, where the pacing
     * estimate could latch onto a bad sample and sit at the wrong period.
     *
     * <p>Nine samples: long enough that a single hitch cannot move the middle,
     * short enough to follow a real change in the guest's rate within about half
     * a second at 15 fps.
     */
    private final long[] recent = new long[9];
    private int recentAt = 0;
    private int recentHeld = 0;
    /**
     * The vsync a synthesised frame is due at, or zero for none.
     *
     * <p>A timestamp rather than an index, because the phase to show is decided
     * from when the frame actually reaches the display and not from which of the
     * N-1 slots it was scheduled as. See {@link #presentSynthesized}.
     */
    private final java.util.concurrent.atomic.AtomicLong pending =
        new java.util.concurrent.atomic.AtomicLong(0);

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

    /**
     * How far a synthesised frame may sit from a real one before it is invention.
     *
     * <p><b>A flat quarter-second gate switched 8x off entirely.</b> The gate
     * asks "is the picture still moving", and 250 ms answers it correctly at 2x
     * and not at all above that: efficiency mode caps the guest at
     * {@code limit / multiple}, so 30 fps at 8x leaves the guest drawing every
     * 333 ms and every interval fell the wrong side of the line. Not one
     * prediction was ever scheduled, at the setting that asks for the most.
     *
     * <p>The quantity that actually matters is the distance from a synthesised
     * frame to the nearest real one, and that is the interval divided by the
     * multiple. Gating on it is the same 250 ms at 2x -- so nothing changes where
     * the old number was right -- and scales where it was not.
     */
    private static final long SYNTH_MAX_GAP_NANOS = 125_000_000L;

    private long idleGate() {
        return SYNTH_MAX_GAP_NANOS * Math.max(2, multiple);
    }

    /**
     * VESSEL: the spacing of frames as they actually reach the screen.
     *
     * <p><b>Every other number here describes what was drawn; this is the only
     * one that describes when.</b> Fifteen real and fifteen synthesised frames a
     * second is the right count, and it says nothing about whether they arrive
     * 33 ms apart or alternate 20 and 46 -- and the second looks like fifteen
     * frames a second with a limp while every counter reads a perfect thirty.
     *
     * <p>Two frames closer together than one display refresh share a scan, so
     * the earlier of them is never seen at all. That failure is invisible to a
     * frame count by construction, which is exactly why it needs measuring
     * separately.
     *
     * <p>Taken at the moment of presentation from the compositor's own clock,
     * rather than derived from the guest's interval: an estimate of the input
     * cannot describe the output.
     */
    /**
     * VESSEL: the same cadence, from the display instead of from this thread.
     *
     * <p>{@link #notePresented} below records when a draw was *issued*. This
     * records when the compositor says the frame was *shown*. They are separated
     * by a queue nothing here controls, and every pacing conclusion in this file
     * has so far come from the first of them. See {@link FrameTimestamps}.
     *
     * <p>Both are kept rather than one replacing the other: the difference
     * between them is the queue depth, which is itself worth seeing, and the
     * platform is entitled to decline the real one on some display paths.
     */
    private final FrameTimestamps timestamps = new FrameTimestamps();

    /**
     * VESSEL: presents that landed on a refresh another present had already used.
     *
     * <p>The one thing the cadence numbers could never establish. A gap measured
     * on this thread says when a draw was issued, and two draws issued 0.3 ms
     * apart may or may not have shared a scanout depending on a queue nothing
     * here can see. Counting the display's own refreshes settles it: two presents
     * recording the same refresh shared it, and the earlier one was never shown.
     *
     * <p>See FramePacer.vsyncIndex for why this replaces the timestamp the
     * platform refuses to provide.
     */
    private long lastPresentVsync = -1;
    private long collisions = 0;

    private long lastPresentNanos = 0;
    private long presentGapMin = Long.MAX_VALUE;
    private long presentGapMax = 0;
    private long presentGapTotal = 0;
    private long presentGaps = 0;

    private void notePresented() {
        // Before the swap, which is the only moment the frame about to be
        // produced has an id. See FrameTimestamps.onDraw.
        if (wants("pacing")) {
            timestamps.onDraw();
            final long vsync = FramePacer.vsyncIndex();
            if (vsync == lastPresentVsync) collisions++;
            lastPresentVsync = vsync;
        }
        final long now = System.nanoTime();
        if (lastPresentNanos != 0) {
            final long gap = now - lastPresentNanos;
            // Longer than a quarter second is a pause, not a cadence.
            if (gap < 250_000_000L) {
                presentGapMin = Math.min(presentGapMin, gap);
                presentGapMax = Math.max(presentGapMax, gap);
                presentGapTotal += gap;
                presentGaps++;
            }
        }
        lastPresentNanos = now;
    }

    private long tier0Frames = 0;
    private long tier1Frames = 0;
    private long skipped = 0;
    /** Times the matcher refused, which is invisible in the picture. */
    private long estimateFailures = 0;
    private long reportedAt = 0;

    public FrameSynthesizer(GLRenderer renderer) {
        this.renderer = renderer;
        this.pacer = new FramePacer(this);
    }

    public void setMultiple(int multiple) {
        this.multiple = Math.max(2, Math.min(8, multiple));
    }

    /**
     * The one-time line: what was asked for, and what the device can actually do
     * about it. Printed on the first composite after the targets exist, because
     * that is the first moment both halves of the answer are known.
     */
    private void announce() {
        if (announced || diagnostics.isEmpty()) return;
        announced = true;
        Log.i(TAG, "fg setup: asked for " + diagnostics
            + ", " + multiple + "x, guest " + renderer.guestWidth()
            + "x" + renderer.guestHeight()
            + " presented into " + renderer.viewTransformation.viewWidth
            + "x" + renderer.viewTransformation.viewHeight
            + " of " + renderer.surfaceWidth + "x" + renderer.surfaceHeight
            + ", motion estimation " + (motionEstimationSupported()
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
        // **Unpaced on purpose, and pacing it twice was a whole class of
        // judder.** PacedXServerView.requestRender throttles to the container's
        // frame limit and re-posts anything early through Handler.postDelayed --
        // the exact mechanism {@link FramePacer} exists to avoid, applied to the
        // frames it had just finished aiming at a vsync. These are paced already.
        renderer.xServerView.requestRenderUnpaced();
    }

    /** Consume a pending request, so one schedule yields exactly one frame. */
    public long consumePending() {
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
        // **One to one with what the guest drew.** See
        // GLRenderer.capturingAtGuestScale: the compositor emits guest
        // coordinates and the viewport is what upscales them, so binding a
        // guest-sized viewport and a guest-sized target composites at native
        // resolution and the upscale moves to present. Everything from here to
        // the screen is then working on real pixels rather than on invented ones,
        // at a quarter of the area.
        renderer.beginGuestScaleCapture();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeColour().framebuffer);
        return true;
    }

    /**
     * Present the real frame, remember what it looked like, and queue the rest.
     *
     * @return whether anything reached the buffer. **False means the caller must
     *     re-present**, because GLSurfaceView swaps regardless and the buffer it
     *     publishes is two or three presents old -- see {@link
     *     #repeatLastPresent}. This path can now decline: an arrival that would
     *     land in a scanout already taken is handed to the pacer instead of
     *     drawn, and on those draws nothing here writes anything at all.
     */
    public boolean endRealFrame() {
        final Target written = writeColour();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.endGuestScaleCapture();
        captureTimer.end();

        // **The invariant: every real frame is presented exactly once, in
        // order. Only interpolated phases may ever be dropped.**
        //
        // The previous attempt at interpolation left the real frame to the
        // pacer, which discards anything a newer real frame has superseded --
        // so on any short interval that frame was never shown at all, and the
        // display received only half-stale interpolated frames. It froze the
        // game. Presenting it here, before the history rotates, bounds the
        // failure: a real frame can be late by at most one interval and can
        // never be skipped.
        boolean presentedHere = false;
        if (!realPresented && realFrames >= 1) {
            presentLatest();
            presentedHere = true;
        }

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

        // The interval now presents phase 1/K here, then 2/K .. K/K through
        // the pacer -- and K/K is frame N itself, arriving at the end of the
        // interval it belongs to. That delay is the whole latency cost of
        // interpolation and is what buys an answerable question.
        if (wants("setup")) announce();
        // The display's own refresh counter, for the collision check that
        // replaces the scanout timestamp this device will not provide.
        if (wants("pacing")) FramePacer.countRefreshes();

        // **The interval is measured before anything is presented, because the
        // present depends on it.** It used to be taken afterwards, so the
        // {@code 1/K} frame drawn here was placed using an estimate one frame
        // stale while the pacer's slots used the fresh one -- two halves of the
        // same interval laid out against different numbers.
        final long now = System.nanoTime();
        final long interval = lastRealFrameNanos == 0 ? 0 : now - lastRealFrameNanos;
        lastRealFrameNanos = now;

        // **Aimed with the median interval, because the last one is noise and
        // the mean is worse than noise.**
        //
        // Measured at 2x: one prediction per real frame was scheduled and only
        // one in three survived -- the rest were cancelled because the next real
        // frame arrived before the prediction's vsync. The compositor draws on
        // guest damage, which is bursty, so consecutive gaps differ by a factor
        // of two or three and an aim computed from a single gap is wrong most of
        // the time.
        //
        // An exponential mean was the first answer and it has a failure the
        // median does not: one hitch of three times the usual interval stays in
        // the estimate for several frames afterwards, and every prediction
        // scheduled in that window is aimed at a rate the guest is not running
        // at. Measured here across a camera sweep, the interval swung between 62
        // and 88 ms while the guest's typical gap never moved from about 66.
        //
        // It is not a cancellation bug. Presenting a prediction of a moment that
        // has already been drawn for real is worse than presenting nothing, so
        // the check is right; what was wrong was aiming so badly that it kept
        // firing.
        if (interval > 0 && interval <= idleGate()) {
            recent[recentAt] = interval;
            recentAt = (recentAt + 1) % recent.length;
            if (recentHeld < recent.length) recentHeld++;
            smoothedInterval = medianInterval();
            // The guest's habit, on a horizon long enough that a hitch cannot
            // move it. See worthInterpolating.
            baseline[baselineAt] = interval;
            baselineAt = (baselineAt + 1) % baseline.length;
            if (baselineHeld < baseline.length) baselineHeld++;
        }

        // Settled once, here, and used by all three of the phase decisions that
        // follow. See effectiveMultiple.
        activeMultiple = effectiveMultiple();

        realPresented = false;
        lastPhase = 0f;
        // **The arrival never asks.** A failing guard here sends control to
        // the `else`, which presents a SECOND real frame into the same
        // scanout -- 299 collisions in one session, every one a real frame.
        // Only paced frames yield; see clearOfLastPresent.
        // **The arrival now asks, because the trace says it should.** It was
        // made unconditional after a guard here sent control to the `else`,
        // which presented a SECOND real frame into the same scanout -- 299
        // collisions in one session. That fix was right about the branch and
        // wrong about the frame: the answer is not to stop asking, it is to not
        // fall through to presentLatest when the answer is no.
        //
        // Attributed per path, this is where nearly all the waste is:
        //
        //     arrival 7/16, paced 0/36, real 0/16
        //     arrival 8/16, paced 0/39, real 2/16
        //     arrival 9/16, paced 0/39, real 0/16
        //
        // Half of every arrival drawn, upscaled, swapped and overwritten before
        // the panel read it, while the paced slots -- which do ask -- collided
        // not once in a hundred and ten.
        //
        // A collided arrival is handed to the pacer as slot zero instead, due
        // immediately. Dropping it was measured and is worse: two thirds of
        // arrivals disappeared and the present rate fell from 63-65 a second to
        // 55-57. Deferring keeps the frame and costs it one refresh.
        //
        // Nothing is counted or latched here: slot zero goes through
        // presentSynthesized like any other, which counts it and advances
        // lastPhase only once it has actually been drawn. See phaseFor -- a
        // monotonic guard advanced past a moment that was never shown clamps
        // the next genuine frame forward to it.
        boolean deferredArrival = false;
        if (motionValid && activeMultiple >= 2 && !clearOfLastPresent()) {
            deferredArrival = true;
        } else if (motionValid && activeMultiple >= 2) {
            presentPhase(1f / activeMultiple);
            // **Counted here, because nothing else counts it.** At 2x this is the
            // only genuinely synthesised frame an interval produces -- the pacer's
            // single slot lands on phase 1, which is the real frame. Without this
            // the synthesised rate reads a handful a second while fifteen are
            // being made, and that rate is what the feature is judged by.
            tier1Frames++;
            lastPhase = 1f / activeMultiple;
        } else {
            // Nothing to interpolate with, or no refresh to show it in, so the
            // real frame is the only thing worth showing and there is no reason
            // to hold it back.
            presentLatest();
        }

        if (realFrames >= 2 && smoothedInterval > 0 && smoothedInterval <= idleGate()) {
            pacer.schedule(smoothedInterval, activeMultiple, deferredArrival);
        }
        report();
        return !deferredArrival || presentedHere;
    }

    /**
     * Draw one synthesised frame, choosing the cheapest tier that applies.
     *
     * @param index which of the N-1 frames this is; t is index/N
     */
    public boolean presentSynthesized(long vsyncNanos) {
        if (!ensureTargets() || realFrames < 2 || vsyncNanos <= 0) return false;

        final float phase = phaseFor(vsyncNanos);

        // **Tier 1 first, where it used to be second.** The block matcher
        // measures apparent motion whatever causes it, so its field already
        // contains a window translation -- and a window that moves while its
        // contents move is a case tier 0 cannot express at all, because it
        // replays the translation with the contents frozen at frame N. Tier 1
        // covers the union of the two, which is why the cheaper tier is now the
        // fallback for when there is no field rather than the preferred answer.
        if (motionValid) {
            // The real frame is never withheld; an interpolated one yields.
            if (phase < 1f && !clearOfLastPresent()) return false;
            presentPhase(phase);
            // **Phase 1 is the real frame**, presented through presentLatest by
            // presentPhase. Counting it here inflated the synthesised rate by
            // exactly the real rate -- and at 2x the pacer's single slot always
            // lands on phase 1, so every "synthesised" frame this counted was a
            // real one. It is the number the whole feature was being judged by.
            if (phase < 1f) tier1Frames++;
            lastPhase = phase;
            return true;
        }

        // No field: re-composite at carried positions, which is exact for a
        // translation and is all that is available without one.
        if (renderer.anyWindowMoved()) {
            tier0Timer.begin();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            renderer.drawSynthesizedFrame(phase);
            tier0Timer.end();
            // Without this the cadence measurement never sees a tier 0 frame, so
            // on any device lacking the motion extension it reports the gaps
            // between *real* frames and calls them the present cadence -- twice
            // the true figure, with the "shortest gap" check blind. The
            // diagnostic silently described a pipeline that was not running.
            notePresented();
            tier0Frames++;
            lastPhase = phase;
            if (phase >= 1f) realPresented = true;
            return true;
        }

        // **Nothing to show, so show nothing.** This used to re-present the
        // identical real frame: a second draw of a picture already on screen,
        // which costs an upscale, adds a sample to the cadence statistics that
        // did not correspond to any new content, and counted as a present. The
        // frame is already there; the honest response to having nothing to add
        // is to add nothing.
        skipped++;
        return false;
    }

    /**
     * VESSEL: put the frame that is already on screen back into the buffer.
     *
     * <p><b>A draw that declines to draw does not leave the screen alone.</b>
     * {@code GLSurfaceView} swaps whether or not anything was rendered, and the
     * buffer it swaps in is not the one being displayed -- it is the one from two
     * or three presents ago. Every path above that returns without drawing
     * therefore publishes an old frame, and at 120 presents a second that is an
     * old frame several times a second, interleaved with correct ones.
     *
     * <p>Measured on the desktop by tracking the pointer through a recording: the
     * position sequence is not monotonic, and the values that repeat do so
     * EXACTLY -- 383.0 three times, 388.1 four times -- interleaved with a
     * sequence advancing normally. Exact repetition is not a cursor drawn late,
     * it is the same image shown again. 16.2% of frames step backwards against a
     * 1.3% floor measured on the guest's own frames, which is the hand changing
     * direction.
     *
     * <p>The {@code skipped} comment above argued that "the frame is already
     * there; the honest response to having nothing to add is to add nothing".
     * The premise is wrong. The frame is already on the SCREEN; this is about the
     * BUFFER, which holds something else.
     *
     * <p>Repeating costs one upscale and is deliberately not counted as a
     * present: nothing new was shown, and the cadence statistics describe new
     * content. What it buys is that the picture cannot go backwards.
     */
    public void repeatLastPresent() {
        if (repeatTexture == 0) return;
        renderer.presentGuestFrame(repeatTexture, true);
    }

    /** The texture last put on screen. See {@link #repeatLastPresent}. */
    private int repeatTexture = 0;

    /**
     * VESSEL: which moment to show, taken from the clock rather than from a slot.
     *
     * <p>**Content time has to run at the same rate as wall time, or the picture
     * judders however many frames are drawn.** The old code presented phase
     * {@code (i+1)/K} for slot {@code i} while the pacer aimed slot {@code i}
     * nine tenths of the way to {@code i/K}, so K phases were squeezed into
     * {@code 0.9(K-1)/K} of an interval and the display then held the last one
     * for the rest of it. At 2x that is motion running eleven per cent fast
     * followed by a dead stop lasting more than half an interval, every interval
     * -- judder manufactured by the pacing, which would read as a fault in the
     * synthesis.
     *
     * <p>Deriving the phase from the vsync timestamp makes the two clocks the
     * same clock. A callback that fires late shows a later moment, which is
     * correct; one that fires early shows an earlier one. The schedule now only
     * has to wake the renderer near the right time rather than hit it.
     *
     * <p>Never backwards: a phase behind one already shown would rewind the
     * picture, and repeating the last is a duplicate frame rather than a
     * reversal. Never nothing, either -- {@code GLSurfaceView} swaps whether or
     * not anything was drawn, so a frame that declines to draw presents an
     * unwritten buffer, which is the flicker.
     */
    /**
     * The middle of the recent intervals, ignoring the ones not yet collected.
     *
     * <p>Insertion sort over nine longs, once per real frame -- around fifteen
     * times a second. There is no cheaper structure worth the complexity at this
     * size and no allocation here.
     */
    private long medianInterval() {
        if (recentHeld == 0) return 0;
        final long[] sorted = new long[recentHeld];
        System.arraycopy(recent, 0, sorted, 0, recentHeld);
        for (int i = 1; i < recentHeld; i++) {
            final long v = sorted[i];
            int j = i - 1;
            while (j >= 0 && sorted[j] > v) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = v;
        }
        return sorted[recentHeld / 2];
    }

    private float phaseFor(long vsyncNanos) {
        if (smoothedInterval <= 0 || lastRealFrameNanos == 0) return 1f;
        final float elapsed = (float)(vsyncNanos - lastRealFrameNanos) / smoothedInterval;
        float phase = 1f / activeMultiple + elapsed;
        if (phase < lastPhase) phase = lastPhase;
        if (phase > 1f) phase = 1f;
        // **Not assigned here.** This is asked for a phase before anything has
        // decided to present one, and the paths below can decline -- at which
        // point the monotonic guard had already been advanced past a moment that
        // was never shown, so the next genuine frame was clamped forward to it.
        // The callers that actually present set it.
        return phase;
    }

    /** The most recent phase presented, so the picture cannot run backwards. */
    private float lastPhase = 0f;

    /**
     * Show the frame at {@code phase} between the two real frames.
     *
     * <p>Phase 1.0 is the newer real frame exactly, so it is blitted rather than
     * interpolated: the shader would reproduce it, and a copy is both cheaper and
     * exact. That is also the slot that satisfies the invariant, which is why it
     * sets {@link #realPresented}.
     */
    private void presentPhase(float phase) {
        if (phase >= 1f) {
            presentLatest();
            return;
        }
        interpolateTimer.begin();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.framebuffer);
        interpolate(phase);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        repeatTexture = output.texture;
        renderer.presentGuestFrame(output.texture, true);
        notePresented();
        interpolateTimer.end();

        // Once a second, and only if something asked. Deliberately after the
        // frame the user sees, so a measurement can never delay one.
        if ((wants("quality") || wants("field"))
                && SystemClock.uptimeMillis() - measuredAt >= 1000) {
            measure(phase);
        }
    }

    /**
     * Whether a present now would land clear of the one before it.
     *
     * <p><b>The margin that protects the real frame shrinks as 1/K, which is why
     * 2x is clean and 4x is not.</b> Frame N has to be delayed so the
     * interpolations between N-1 and N can be shown before it, so the interval
     * runs 1/K, 2/K ... K/K with K/K being N itself. That puts N's present at
     * (K-1)/K of the way through, while N+1 arrives at the end -- leaving
     * interval/K between them: 33 ms at 2x, 16.5 at 4x, 8.3 at 8x. A few
     * milliseconds of scheduling jitter on either side is nothing against 33 and
     * routine against 8.3.
     *
     * <p>Measured at 4x on a 15 fps guest: gaps between presents from 0.3 ms to
     * 26 ms, and 11 of every 70 presents landing on a refresh another present had
     * already used -- drawn, paid for, never shown.
     *
     * <p>So a present waits rather than colliding. What is skipped is only ever an
     * interpolated frame, and skipping it costs nothing: the pacer's next slot
     * carries a later phase, so the motion continues from where it should rather
     * than from where the skipped frame would have put it.
     */
    private boolean clearOfLastPresent() {
        final long period = vsyncPeriodNanos();
        if (period <= 0 || lastPresentNanos == 0) return true;
        return System.nanoTime() - lastPresentNanos >= period;
    }

    private void presentLatest() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        repeatTexture = latestColour().texture;
        // Upscaled once, here, rather than before any of the work. See
        // GLRenderer.presentGuestFrame.
        renderer.presentGuestFrame(latestColour().texture, true);
        notePresented();
        realPresented = true;
    }

    /** Whether the newest real frame has reached the screen. See the invariant. */
    private boolean realPresented = true;

    /** Blend the two real frames along the field, at {@code phase} between them. */
    private void interpolate(float phase) {
        interpolate(phase, false);
    }

    /**
     * @param measuring when true, writes four per-pixel measurements instead of a
     *     picture, into {@link #probe} rather than to the screen. See
     *     {@link #measure}.
     */
    private void interpolate(float phase, boolean measuring) {
        // Guest resolution, like everything else now. The upscale happens once,
        // when the finished frame is presented.
        GLES20.glViewport(0, 0, colour[0].width, colour[0].height);
        renderer.viewportNeedsUpdate = true;
        GLES20.glDisable(GLES20.GL_BLEND);

        interpolateMaterial.use();
        renderer.quadVertices.bind(interpolateMaterial.programId);
        interpolateMaterial.setUniformBool(interpolateMaterial.uniforms.flipY, false);
        interpolateMaterial.setUniformFloat(interpolateMaterial.interpolateUniforms.phase, phase);
        interpolateMaterial.setUniformFloat(interpolateMaterial.interpolateUniforms.diagnostic,
                                            measuring ? 1f : 0f);
        // **Restored: the one line that made recordings analysable.**
        //
        // The shader has always painted this corner; `5010b3e` dropped the line
        // that switched it on, and the loss was silent. Every ground-truth tool
        // in tools/frame-bench separates real frames from synthesised ones by
        // looking for these pixels, so without it a recording is a wall of
        // frames with no labels: a scan of 5,139 RE9 frames reported "0
        // synthesised, 5,139 real" and could not say whether a broken frame was
        // the pipeline or a muzzle flash.
        //
        // Never while measuring -- the diagnostic pass writes four measurements
        // into the same pixels, and stamping them would corrupt the readback.
        interpolateMaterial.setUniformFloat(interpolateMaterial.interpolateUniforms.mark,
                                            !measuring && wants("mark") ? 1f : 0f);
        // The vectors are in luma pixels, and the luma is the block-rounded frame,
        // so dividing by its size is what puts them in texture space.
        interpolateMaterial.setUniformVec2(interpolateMaterial.interpolateUniforms.motionScale,
                                           1f / Math.max(1, luma[0].width),
                                           1f / Math.max(1, luma[0].height));
        // The block grid, so a single block can be addressed rather than only the
        // filtered average of four. See InterpolateMaterial's third point.
        interpolateMaterial.setUniformVec2(interpolateMaterial.interpolateUniforms.vectorSize,
                                           vectors.width, vectors.height);
        // Uniform, not per pixel. See SignMaterial. Until the probe has an
        // answer the field is taken forward, which is the reading the extension's
        // own wording suggests; the probe overrides it within a second of motion.
        interpolateMaterial.setUniformFloat(interpolateMaterial.interpolateUniforms.fieldSign,
                                            fieldSign != 0f ? fieldSign : 1f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestColour().texture);
        interpolateMaterial.setUniformInt(interpolateMaterial.uniforms.screenTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colour[oldest()].texture);
        interpolateMaterial.setUniformInt(
            interpolateMaterial.interpolateUniforms.previousTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fieldTexture());
        interpolateMaterial.setUniformInt(
            interpolateMaterial.interpolateUniforms.motionTexture, 2);
        // The search runs on these rather than on the colour: one byte a texel
        // instead of four, for the same answer. See InterpolateMaterial.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestLuma().texture);
        interpolateMaterial.setUniformInt(
            interpolateMaterial.interpolateUniforms.lumaNewerTexture, 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousLuma().texture);
        interpolateMaterial.setUniformInt(
            interpolateMaterial.interpolateUniforms.lumaOlderTexture, 4);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());

        for (int unit = 4; unit >= 0; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.invalidateBoundWindowMaterial();
    }

    /**
     * VESSEL: ask the interpolation what it just did, over the whole frame.
     *
     * <p>Runs the same shader a second time in its reporting mode, which writes
     * four measurements per pixel instead of a colour, then lets the GPU average
     * the frame down to one texel and reads that back. Four bytes, no stall.
     *
     * <p>**Why measure rather than describe.** Every fault this feature has had
     * was diagnosed by looking at the screen and reasoning backwards, and a black
     * speck, a shimmer and a frozen patch are indistinguishable in a sentence
     * while having nothing in common in the code. These four numbers separate
     * them: a frame that is mostly falling back reads differently from one that is
     * confidently wrong, and both read differently from one whose vectors are
     * simply zero.
     *
     * <p>Once a second, and only when asked for. The extra pass costs about what
     * one interpolated frame costs, once per second.
     */
    private void measure(float phase) {
        if (probe == null) {
            probe = new Target();
            probe.allocateAveraging(colour[0].width, colour[0].height);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probe.framebuffer);
        interpolate(phase, true);

        // **Unbound first, and this was undefined behaviour.** Generating
        // mipmaps for a texture still attached to the bound framebuffer is not
        // something the spec defines, and a driver is free to stall or to
        // misbehave. It did the latter here for long enough to trip the input
        // dispatch timeout.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, probe.texture);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        final java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probe.topFramebuffer);
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // R and B are sums of distances over the pixels that moved; A is how
        // many of them there were. See InterpolateMaterial for why this is two
        // distances rather than the count of pixels it replaced.
        measuredSynthDistance = (pixel.get(0) & 0xff) / 255f;
        measuredDark = (pixel.get(1) & 0xff) / 255f;
        measuredBaseDistance = (pixel.get(2) & 0xff) / 255f;
        measuredShadow = (pixel.get(3) & 0xff) / 255f;
        measuredPhase = phase;
        measuredAt = SystemClock.uptimeMillis();

        // **And the same channel again, resolved into cells, because the mean
        // above could not see what broke the screen.**
        //
        // A hypothesis-weighting change scored better than the shipped path on
        // mean error, on error over the blocks that move differently from the
        // frame, and on speckle, was installed, and put black patches on the
        // display within minutes. Every one of those numbers is an average or a
        // high-frequency statistic; a patch is neither. `measuredDark` read
        // 1.5%, which is exactly what it reads for 1.5% of pixels scattered
        // evenly across the frame -- and that is invisible.
        //
        // Reading one level of the same mip chain costs one more round trip on
        // a pass that already stalls once a second, and turns the same data
        // into the thing the eye actually responds to: how many 32x32 cells are
        // more than half invented, and how bad the worst one is.
        measuredPatchCells = 0;
        measuredEdgeCells = 0;
        measuredWorstCell = 0f;
        if (probe.cellFramebuffer != 0) {
            final int count = probe.cellWidth * probe.cellHeight;
            if (cells == null || cells.capacity() < count * 4) {
                cells = java.nio.ByteBuffer.allocateDirect(count * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
            }
            cells.position(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probe.cellFramebuffer);
            GLES20.glReadPixels(0, 0, probe.cellWidth, probe.cellHeight,
                                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, cells);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            // **Split border from interior, because only one of them is a bug.**
            //
            // During a pan the leading edge of the frame fetches content that
            // genuinely was not rendered -- it was off-screen in both real
            // frames -- so those cells MUST clamp, and a reading that counts
            // them is reporting camera motion. The first version of this did
            // exactly that and made a still scene read 0 and a pan read 94 of
            // 880, which looks alarming and is unavoidable physics.
            //
            // A cell two or more cells in from every edge has no such excuse.
            // Nothing there should ever need content from outside the frame,
            // because the displacement required to reach it is larger than the
            // matcher's own search window. Interior escapes are the number to
            // watch, and the border count is kept only so the two can be told
            // apart in a log rather than argued about.
            measuredEdgeCells = 0;
            for (int i = 0; i < count; i++) {
                final float escaped = (cells.get(i * 4 + 1) & 0xff) / 255f;
                final int cx = i % probe.cellWidth;
                final int cy = i / probe.cellWidth;
                final boolean edge = cx < 2 || cy < 2
                    || cx >= probe.cellWidth - 2 || cy >= probe.cellHeight - 2;
                // The worst cell is reported for the INTERIOR only. Taken over
                // the whole frame it read 100% in every sample of a moving
                // scene, because a border cell during a pan always does -- so
                // it said nothing at all.
                if (!edge && escaped > measuredWorstCell) measuredWorstCell = escaped;
                if (escaped <= 0.5f) continue;
                if (edge) measuredEdgeCells++; else measuredPatchCells++;
            }
            measuredCellTotal = count;
        }

        renderer.viewportNeedsUpdate = true;
    }

    /**
     * Run the hardware block matcher over the luma pair.
     *
     * @return false if the vectors could not be produced, in which case no warp
     *     should be attempted -- an unwritten field would warp by garbage.
     */
    private boolean estimateMotion() {
        if (luma[0] == null || vectors == null || output == null) return false;
        estimateTimer.begin();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        texEstimateMotion(previousLuma().texture, latestLuma().texture, vectors.texture);
        final int error = GLES20.glGetError();
        estimateTimer.end();
        if (error != GLES20.GL_NO_ERROR) {
            estimateFailures++;
            Log.e(TAG, "glTexEstimateMotionQCOM failed 0x" + Integer.toHexString(error));
            return false;
        }

        // One bit about the whole field, settled once. See SignMaterial.
        probeFieldSign();

        // Filtered before anything reads it, so every pass downstream sees the
        // same field. See MedianMaterial and the `filtered` pair.
        filterField();
        return true;
    }

    /**
     * Reject the vectors that disagree with everything around them.
     *
     * <p>Each pass reads one target and writes the other, starting from the
     * matcher's own output. See {@link MedianMaterial} for why the winner is
     * always one of the nine inputs rather than an average of them, and
     * {@link #filtered} for why there are two passes.
     */
    private void filterField() {
        if (filtered[0] == null) { filteredIndex = -1; return; }
        medianTimer.begin();
        GLES20.glDisable(GLES20.GL_BLEND);

        medianMaterial.use();
        renderer.quadVertices.bind(medianMaterial.programId);
        medianMaterial.setUniformBool(medianMaterial.uniforms.flipY, false);
        medianMaterial.setUniformVec2(medianMaterial.medianUniforms.texelSize,
                                      1f / Math.max(1, vectors.width),
                                      1f / Math.max(1, vectors.height));
        GLES20.glViewport(0, 0, vectors.width, vectors.height);
        renderer.viewportNeedsUpdate = true;

        int source = vectors.texture;
        for (int pass = 0; pass < MEDIAN_PASSES; pass++) {
            final int destination = pass % 2;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filtered[destination].framebuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source);
            medianMaterial.setUniformInt(medianMaterial.uniforms.screenTexture, 0);
            // The matcher's own field, on offer every pass. See MedianMaterial.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectors.texture);
            medianMaterial.setUniformInt(medianMaterial.medianUniforms.originalTexture, 1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
            source = filtered[destination].texture;
            filteredIndex = destination;
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        for (int unit = 1; unit >= 0; unit--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.invalidateBoundWindowMaterial();
        medianTimer.end();
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
        // **The guest's own resolution, not the panel's.** Every pass downstream
        // of the capture inherits this, which is the whole point: the picture
        // carries this much information and no more, so anything larger is work
        // spent on pixels the upscaler invented. See
        // GLRenderer.capturingAtGuestScale.
        final int width = renderer.guestWidth();
        final int height = renderer.guestHeight();
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
        output = new Target();
        output.allocate(width, height, GLES30.GL_RGBA8, GLES20.GL_LINEAR);

        if (motionEstimationSupported()) {
            final int[] value = new int[1];
            GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM, value, 0);
            blockX = Math.max(1, value[0]);
            GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM, value, 0);
            blockY = Math.max(1, value[0]);

            // The luma pair must be an exact multiple of the search block, so the
            // largest such rectangle is used rather than the whole frame. The
            // frame is already the guest's own resolution, so a block covers real
            // rendered pixels rather than ones the upscaler invented.
            final int lumaW = (width / blockX) * blockX;
            final int lumaH = (height / blockY) * blockY;
            if (lumaW > 0 && lumaH > 0) {
                for (int i = 0; i < 2; i++) {
                    luma[i] = new Target();
                    luma[i].allocate(lumaW, lumaH, GLES30.GL_R8, GLES20.GL_LINEAR);
                }
                // **Nearest on both, and that is not an oversight.** The
                // median wants the nine vectors that were actually estimated, and
                // the interpolation wants the four blocks as the four answers they
                // are -- it does its own spatial blending under overlapping
                // windows. Letting the sampler interpolate as well would blend
                // twice and would invent a vector no block ever voted for, which
                // at a motion boundary describes nothing in the scene.
                vectors = new Target();
                vectors.allocate(lumaW / blockX, lumaH / blockY, GLES30.GL_RGBA16F, GLES20.GL_NEAREST);

                // **Rendering to RGBA16F is a capability, not a given.** The
                // matcher writes `vectors` through the extension rather than
                // through the pipeline, so its allocation says nothing about
                // whether a fragment shader may target that format. ES 3.2 makes
                // it colour-renderable and this device reports 3.2, but asking is
                // one call and guessing wrong is a blank field -- every vector
                // zero, every synthesised frame a cross-fade.
                for (int i = 0; i < 2; i++) {
                    filtered[i] = new Target();
                    filtered[i].allocate(lumaW / blockX, lumaH / blockY,
                                         GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filtered[0].framebuffer);
                final int complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                if (complete != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    Log.w(TAG, "cannot render to RGBA16F (0x" + Integer.toHexString(complete)
                        + "); the field will be used as the matcher produced it");
                    filtered[0].release();
                    filtered[1].release();
                    filtered[0] = filtered[1] = null;
                }
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
        recentAt = 0;
        recentHeld = 0;
        return true;
    }

    private void release(boolean deleteObjects) {
        if (deleteObjects) {
            for (int i = 0; i < 2; i++) {
                if (colour[i] != null) colour[i].release();
                if (luma[i] != null) luma[i].release();
            }
            if (vectors != null) vectors.release();
            for (int i = 0; i < 2; i++) if (filtered[i] != null) filtered[i].release();
            if (signProbe != null) signProbe.release();
            if (output != null) output.release();
            if (probe != null) probe.release();
        }
        colour[0] = colour[1] = luma[0] = luma[1] = null;
        vectors = null;
        filtered[0] = filtered[1] = null;
        filteredIndex = -1;
        output = null;
        signProbe = null;
        fieldSign = 0f;
        pendingSign = 0f;
        signVotes = 0f;
        probe = null;
        announced = false;
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
    /**
     * VESSEL: say what the pipeline is doing, in the categories that were asked
     * for and no others.
     *
     * <p>**Every line here exists because its absence cost a day.** Frame
     * generation fails visually, intermittently, and in ways that a description
     * of the screen cannot separate -- a black speck, a shimmer and a frozen
     * patch are one sentence and three different bugs. Each category answers a
     * question that was previously answered by guessing:
     *
     * <ul>
     * <li>{@code setup} -- is the tier even running, and at what sizes. The
     *     feature has silently done nothing before now, and this says so.
     * <li>{@code pacing} -- how many frames are real, how many are invented, how
     *     many were dropped. A stutter is either here or it is not.
     * <li>{@code timing} -- what each pass costs. Reasoning about a shader cannot
     *     answer this: the cost is bandwidth and target switches, not arithmetic.
     *     It also catches thermal throttling, which scales every pass at once and
     *     is otherwise indistinguishable from a change having made things slower.
     * <li>{@code field} -- what the vectors contain. A field that is mostly zero
     *     and a field that is wrong look identical on screen and are opposite
     *     problems.
     * <li>{@code quality} -- what was done with them: how much of the frame was
     *     trusted, how much fell back, and how much came out black from sources
     *     that were not. That last number is the one that separates "this shader
     *     invented a dot" from "this shader faithfully drew a dark pixel the
     *     vector pointed at", which took nine attempts to establish by eye.
     * </ul>
     */
    private void report() {
        final long now = SystemClock.uptimeMillis();
        if (wants("timing")) {
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

        // The one line that is always printed, because a container with frame
        // generation on and nothing to say about it is itself the answer.
        Log.i(TAG, "real " + realFrames + ", tier0 " + tier0Frames
            + ", tier1 " + tier1Frames + ", skipped " + skipped
            + ", " + multiple + "x");

        if (wants("pacing") && timestamps.hasData()) {
            // **The only line here that describes the display rather than this
            // thread.** Where it disagrees with `fg cadence` below, this one is
            // right and that one is measuring the queue.
            Log.i(TAG, timestamps.describe(vsyncPeriodNanos()));
        } else if (wants("pacing") && timestamps.unavailable()) {
            Log.i(TAG, "fg presented: this surface answers none of the frame"
                + " timestamps -- not scanout, not composition, not latch. Every"
                + " cadence figure below is a draw schedule.");
        }

        if (wants("pacing") && presentGaps > 0) {
            // Even spacing is what smooth motion is, not the count. A mean of
            // 33 ms with a spread from 0.2 to 56 is two frames inside one
            // refresh -- the first never scanned out -- and then a long wait.
            Log.i(TAG, String.format(
                "fg cadence: presented every %.1f ms mean, %.1f shortest,"
                    + " %.1f longest, over %d gaps; %d shared a refresh with"
                    + " the present before them and were never shown",
                presentGapTotal / (float)presentGaps / 1e6f,
                presentGapMin / 1e6f, presentGapMax / 1e6f, presentGaps,
                collisions));
            collisions = 0;
            presentGapMin = Long.MAX_VALUE;
            presentGapMax = 0;
            presentGapTotal = 0;
            presentGaps = 0;
        }
        if (wants("pacing")) {
            final float perSecond = realThisSecond * 1000f / elapsed;
            Log.i(TAG, String.format(
                "fg pacing: %.1f real/s, %.1f synthesised/s, %.1f presented/s,"
                    + " %d skipped, interval %.1f ms (%.1f fps),"
                    + " %dx asked / %dx fitted into a %.2f ms refresh, %s",
                perSecond, tier1Frames * 1000f / elapsed,
                (perSecond + tier1Frames * 1000f / elapsed),
                skipped,
                smoothedInterval / 1e6f,
                smoothedInterval > 0 ? 1e9f / smoothedInterval : 0f,
                multiple, activeMultiple, vsyncPeriodNanos() / 1e6f,
                motionValid ? "field valid" : "NO FIELD -- tier 0 or real frames only"));
            // Where the gate currently sits and what it was derived from, so a
            // 4x-to-1x drop can be read rather than guessed at.
            Log.i(TAG, String.format(
                "fg gate: interpolating up to %.0f ms (guest habit %.0f ms),"
                    + " interval now %.0f ms -- %s",
                worthInterpolating() / 1e6f, baselineInterval() / 1e6f,
                smoothedInterval / 1e6f,
                smoothedInterval > worthInterpolating() ? "OFF, guest has stopped"
                                                       : "interpolating"));
        }

        if (wants("field")) {
            Log.i(TAG, String.format(
                "fg field: block %dx%d, grid %dx%d, luma %dx%d,"
                    + " %s, matcher refusals %d, moved %.0f px mean"
                    + " (window about %d)",
                blockX, blockY,
                vectors != null ? vectors.width : 0,
                vectors != null ? vectors.height : 0,
                luma[0] != null ? luma[0].width : 0,
                luma[0] != null ? luma[0].height : 0,
                filteredIndex >= 0 ? MEDIAN_PASSES + " median passes" : "raw",
                estimateFailures, fieldMagnitude, SEARCH_WINDOW_PX));
        }

        if (wants("layers")) {
            // What went into the flattened frame everything downstream works on.
            // See GLRenderer.describeLayers.
            Log.i(TAG, "fg layers: " + renderer.describeLayers());
        }

        if (wants("quality")) {
            // **One line, and the first one that can say frame generation is
            // making things worse.**
            //
            // Everything this replaces was measuring the shader against its own
            // assumptions -- see InterpolateMaterial's diagnostic block for the
            // four ways that failed. These are anchored to the two real captured
            // frames and to nothing else.
            //
            // Read it as: of the part of the frame that actually changed, how
            // much did we push *further* from the truth than simply leaving the
            // old frame up. Anything above a per cent or two means the synthesis
            // is actively damaging, however healthy it looks from the inside.
            final float moving = measuredShadow;
            // Both sums are over the moving pixels, so the ratio needs no
            // denominator -- it cancels. Below 100% the synthesised frame is
            // closer to the real one than leaving the old frame up would be.
            final float closeness = measuredBaseDistance > 0.0001f
                ? measuredSynthDistance / measuredBaseDistance : 0f;
            // **Subtract what the phase alone accounts for, because it is
            // almost all of it.** The distances are measured against frame N,
            // so a frame drawn at phase p and interpolated PERFECTLY sits at
            // exactly (1-p) of the old frame's distance -- that is geometry,
            // not quality. Measured over a capture the raw ratio correlates
            // with (1-phase) at +0.93, so a reading of 80% against one of 21%
            // looks like a large difference in quality and is mostly a
            // difference in when the frame was drawn.
            //
            // What is left is the part the pipeline is responsible for: how
            // much further from the real frame it landed than a perfect
            // interpolation at that same phase would have. Zero is perfect and
            // it means the same thing at every phase, which the ratio does not.
            final float excess = moving > 0.001f
                ? closeness - (1f - measuredPhase) : 0f;
            Log.i(TAG, String.format(
                "fg truth: %+.0f%% further from the real frame than a perfect"
                    + " interpolation at this phase (0%% is perfect), sits at"
                    + " %.0f%% where phase alone gives %.0f%%, %.3f%% fetched"
                    + " off the frame, %.0f%% of frame moving, drawn at %.0f%%",
                excess * 100f, closeness * 100f, (1f - measuredPhase) * 100f,
                measuredDark * 100f, moving * 100f, measuredPhase * 100f));

            // **On its own line, because it is the one that would have caught
            // the change that broke the screen and the line above would not.**
            //
            // Every figure above is a mean or a ratio of means. Black patches
            // are a connected area, and the invented-content mean read 1.5%
            // while the display had holes in it -- the same 1.5% it reads for a
            // scatter nobody can see. These two say how concentrated it is: how
            // many 32x32 cells are more than half invented, and how bad the
            // single worst cell is. A frame with zero cells and a mean of 1.5%
            // is fine. One cell is visible. Twenty is what was reverted.
            Log.i(TAG, String.format(
                "fg patches: %d interior cells of %d take over half their"
                    + " content from off the frame (%d more at the border,"
                    + " which a pan cannot avoid), worst interior cell %.0f%%",
                measuredPatchCells, measuredCellTotal, measuredEdgeCells,
                measuredWorstCell * 100f));
        }

        tier0Frames = 0;
        tier1Frames = 0;
        skipped = 0;
        estimateFailures = 0;
    }

    private long reportedRealFrames = 0;
}
