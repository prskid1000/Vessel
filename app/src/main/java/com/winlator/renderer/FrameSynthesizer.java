package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.renderer.material.FieldMaterial;
import com.winlator.renderer.material.InterpolateMaterial;
import com.winlator.renderer.material.MedianMaterial;
import com.winlator.renderer.material.SignMaterial;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShiftMaterial;

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
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        void release() {
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
    private final ShiftMaterial shiftMaterial = new ShiftMaterial();
    private final FieldMaterial fieldMaterial = new FieldMaterial();
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
    private float measuredConfidence, measuredDark, measuredShadow, measuredSpeed;
    private long measuredAt = 0;

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
    /**
     * Mean block displacement in pixels, and the share of blocks at the edge of
     * what the matcher can measure.
     *
     * <p>See SignMaterial. A field pinned at its search limit is not a field --
     * every vector reads the same number whatever the truth is, so every
     * interpolated frame under-compensates by however far the scene really went.
     * That produces judder rather than blur, and only when the camera is fast
     * enough and the gap wide enough to reach the limit.
     */
    private float fieldMagnitude = 0f;
    private float fieldAtLimit = 0f;
    /** When the sign was last probed, so a still scene retries rather than sticks. */
    private long signProbedAt = 0;
    /** A decisive vote awaiting a second, agreeing one. See {@link #probeFieldSign}. */
    private float pendingSign = 0f;

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
        // **The sign latches once; the magnitude has to keep being read.** The
        // search window is bounded, and whether the scene is moving further than
        // it can measure changes from moment to moment -- it is a property of how
        // fast the camera is turning, not of the driver. So once a diagnostic
        // asks for the field, this keeps running after the latch purely for the
        // magnitude, at the same once-a-second cost the sign paid.
        if (fieldSign != 0f && !wants("field")) return;
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fieldTexture());
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
        fieldMagnitude = (pixel.get(2) & 0xff) / 255f * 256f;
        fieldAtLimit = (pixel.get(3) & 0xff) / 255f;

        // Nothing below this line is about the magnitude, and the sign only needs
        // deciding once.
        if (fieldSign != 0f) return;

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
        if (smoothedInterval > WORTH_INTERPOLATING_NANOS) return 1;
        final long period = vsyncPeriodNanos();
        if (period <= 0 || smoothedInterval <= 0) return multiple;
        final int refreshes = (int)(smoothedInterval / period);
        return Math.min(multiple, Math.max(1, refreshes));
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

    /** Present the real frame, remember what it looked like, and queue the rest. */
    public void endRealFrame() {
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
        if (!realPresented && realFrames >= 1) {
            presentLatest();
        }

        // Luma for this frame, so the pair is ready without re-deriving the older
        // one every time. Only when tier 1 can actually use it.
        if (motionEstimationSupported()) {
            lumaTimer.begin();
            renderToTarget(lumaMaterial, written.texture, writeLuma());
            // The same frame again at half the size. A plain blit, because the
            // sampler's own filtering is the downsample and the matcher wants
            // brightness rather than detail.
            if (coarseLuma[0] != null) {
                renderToTarget(blitMaterial, writeLuma().texture, coarseLuma[oldest()]);
            }
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
        }

        // Settled once, here, and used by all three of the phase decisions that
        // follow. See effectiveMultiple.
        activeMultiple = effectiveMultiple();

        realPresented = false;
        lastPhase = 0f;
        if (motionValid && activeMultiple >= 2 && clearOfLastPresent()) {
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
            pacer.schedule(smoothedInterval, activeMultiple);
        }
        report();
    }

    /**
     * Draw one synthesised frame, choosing the cheapest tier that applies.
     *
     * @param index which of the N-1 frames this is; t is index/N
     */
    public void presentSynthesized(long vsyncNanos) {
        if (!ensureTargets() || realFrames < 2 || vsyncNanos <= 0) return;

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
            if (phase < 1f && !clearOfLastPresent()) return;
            presentPhase(phase);
            // **Phase 1 is the real frame**, presented through presentLatest by
            // presentPhase. Counting it here inflated the synthesised rate by
            // exactly the real rate -- and at 2x the pacer's single slot always
            // lands on phase 1, so every "synthesised" frame this counted was a
            // real one. It is the number the whole feature was being judged by.
            if (phase < 1f) tier1Frames++;
            lastPhase = phase;
            return;
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
            return;
        }

        // **Nothing to show, so show nothing.** This used to re-present the
        // identical real frame: a second draw of a picture already on screen,
        // which costs an upscale, adds a sample to the cadence statistics that
        // did not correspond to any new content, and counted as a present. The
        // frame is already there; the honest response to having nothing to add
        // is to add nothing.
        skipped++;
    }

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
    /**
     * <b>One refresh, and tightening it was tried and measured worse.</b>
     *
     * <p>This stops two presents sharing a scanout, where the first is drawn,
     * paid for and never seen. It does not make the stream even, and the
     * recording says so: at 4x on a 120 Hz panel, where an even stream holds
     * every picture two refreshes, 38% were held for one, 32% for two and 30% for
     * three or more.
     *
     * <p>The obvious repair is to require a whole slot of spacing rather than a
     * refresh. Simulated in tools/frame-bench/pacing.py against measured guest
     * jitter and GL draw latency, that trades one artefact for a worse one --
     * short holds fall from 21% to 6% and long ones rise from 21% to 38%, with
     * the present rate down a sixth. A 30 ms hitch is more visible than an 8 ms
     * one.
     *
     * <p>And the reason no rule here helps: every variant leaves "even" at 56 to
     * 58 per cent. The spread does not come from when a present is *scheduled*,
     * it comes from the variance between then and when the GL thread has actually
     * drawn and swapped. The lever is that latency, not this inequality.
     */
    private boolean clearOfLastPresent() {
        final long period = vsyncPeriodNanos();
        if (period <= 0 || lastPresentNanos == 0) return true;
        return System.nanoTime() - lastPresentNanos >= period;
    }

    private void presentLatest() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
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
        // Only frames this shader draws carry the stamp, which is what makes
        // them countable in a recording. See InterpolateMaterial's `mark`.
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

        measuredConfidence = (pixel.get(0) & 0xff) / 255f;
        measuredDark = (pixel.get(1) & 0xff) / 255f;
        measuredShadow = (pixel.get(2) & 0xff) / 255f;
        measuredSpeed = (pixel.get(3) & 0xff) / 255f;
        measuredAt = SystemClock.uptimeMillis();

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
        // The coarse pass runs first, because the fine pass is now aimed by it.
        // Free: the matcher measures 0.001 ms, so a second call costs nothing
        // worth counting. See coarseLuma.
        //
        // **Previous first, then latest -- the same order as the fine pass.**
        // This was written the other way round and every vector it produced
        // therefore pointed backwards. Measured on the laptop at a 167 px
        // displacement: 150 px of vector error became 249, and the interpolated
        // frame went from 20.05 RMS to 22.74, which is worse than not
        // compensating at all (21.03). See tools/frame-bench/pyramid.py.
        refinedField = false;
        if (coarseVectors != null && coarseLuma[0] != null) {
            texEstimateMotion(coarseLuma[oldest()].texture, coarseLuma[newest].texture,
                              coarseVectors.texture);
            refinedField = refineAgainstGuess();
        }
        if (!refinedField) {
            texEstimateMotion(previousLuma().texture, latestLuma().texture, vectors.texture);
        }
        final int error = GLES20.glGetError();
        estimateTimer.end();
        if (error != GLES20.GL_NO_ERROR) {
            estimateFailures++;
            Log.e(TAG, "glTexEstimateMotionQCOM failed 0x" + Integer.toHexString(error));
            return false;
        }

        // Filtered before anything reads it, so every pass downstream sees the
        // same field. See MedianMaterial and the `filtered` pair.
        filterField();

        // One bit about the whole field, settled once. See SignMaterial.
        //
        // **After the filter, not before.** The probe used to read `vectors`,
        // the raw matcher output, while the coarse substitution happens inside
        // the median filter -- so the saturation figure it reported described a
        // field nothing downstream uses, and could not move however well or
        // badly the extension worked. It read `mean 119 px, 90% at the search
        // limit` identically with the coarse pass wired up, mis-wired, and
        // absent. A metric that cannot distinguish those three is not a metric.
        probeFieldSign();
        return true;
    }

    /**
     * Aim the fine pass with the coarse field, then measure what is left over.
     *
     * <p>See the {@code shiftedLuma} note for why this beats substituting the
     * coarse vector, and {@link ShiftMaterial} for why the image is displaced
     * rather than the search window -- the extension has no parameter for a
     * search centre, so the only way to move it is to move one of the pictures.
     *
     * @return false if the guess could not be established, in which case the
     *     caller runs the plain fine pass and nothing is lost.
     */
    private boolean refineAgainstGuess() {
        if (shiftedLuma == null || guessProbe == null || biased == null) return false;
        // The shift needs to know which way the field points, and that is not
        // known until the sign is latched. Until then, the ordinary pass.
        if (fieldSign == 0f) return false;

        // One vector for the whole coarse field, by mipmap reduction. The mean
        // rather than the median, which is a compromise the guess can afford:
        // see FieldMaterial.
        fieldMaterial.use();
        fieldMaterial.setUniformFloat(fieldMaterial.fieldUniforms.encode, 1f);
        renderToTarget(fieldMaterial, coarseVectors.texture, guessProbe);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, guessProbe.texture);
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        final java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, guessProbe.topFramebuffer);
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.viewportNeedsUpdate = true;

        // Undo the encoding, then double: the coarse pass measured in its own
        // half-resolution pixels, and everything downstream is in full ones.
        final float range = 2f * FieldMaterial.RANGE;
        guessX = (((pixel.get(0) & 0xff) / 255f) - 0.5f) * range * 2f;
        guessY = (((pixel.get(1) & 0xff) / 255f) - 0.5f) * range * 2f;

        // A wild readback must not turn into a wild shift. Nothing beyond the
        // coarse pass's own reach can be a real measurement.
        final float reach = range * 2f;
        if (!(Math.abs(guessX) <= reach) || !(Math.abs(guessY) <= reach)) return false;

        // Displace the older image onto the newer one. The sign is the latched
        // one: with it positive, latest(x) matches previous(x + v).
        shiftMaterial.use();
        shiftMaterial.setUniformVec2(shiftMaterial.shiftUniforms.offset,
                                     fieldSign * guessX / Math.max(1, luma[0].width),
                                     fieldSign * guessY / Math.max(1, luma[0].height));
        renderToTarget(shiftMaterial, previousLuma().texture, shiftedLuma);

        // The matcher wants no framebuffer bound and no pending error.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        texEstimateMotion(shiftedLuma.texture, latestLuma().texture, vectors.texture);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false;

        // The true vector is the guess plus the residual. Added here, once, so
        // that everything downstream sees an ordinary field.
        fieldMaterial.use();
        fieldMaterial.setUniformFloat(fieldMaterial.fieldUniforms.encode, 0f);
        fieldMaterial.setUniformVec2(fieldMaterial.fieldUniforms.bias, guessX, guessY);
        renderToTarget(fieldMaterial, vectors.texture, biased);
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
        // **The limit is measured, not declared.** The extension does not report
        // its search window, so the number that matters is where the field stops
        // rising: 120 pixels observed, against vectors reaching 113 in the first
        // survey of this driver. A hundred is below both, so a vector at or past
        // it is one the fine pass can no longer be trusted about.
        //
        // **Off entirely once the field is refined.** Substitution exists to
        // rescue vectors pinned at the window; a refined vector is long because
        // the scene moved that far, not because it ran out of window, and
        // replacing those with coarse ones would throw away the precision the
        // refine pass just bought. The two mechanisms answer the same problem and
        // the better one wins.
        medianMaterial.setUniformFloat(medianMaterial.medianUniforms.searchLimit,
                                       (coarseVectors != null && !refinedField) ? 100f : 0f);
        GLES20.glViewport(0, 0, vectors.width, vectors.height);
        renderer.viewportNeedsUpdate = true;

        int source = rawField().texture;
        for (int pass = 0; pass < MEDIAN_PASSES; pass++) {
            final int destination = pass % 2;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filtered[destination].framebuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source);
            medianMaterial.setUniformInt(medianMaterial.uniforms.screenTexture, 0);
            // The matcher's own field, on offer every pass. See MedianMaterial.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawField().texture);
            medianMaterial.setUniformInt(medianMaterial.medianUniforms.originalTexture, 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,
                                 coarseVectors != null ? coarseVectors.texture : 0);
            medianMaterial.setUniformInt(medianMaterial.medianUniforms.coarseTexture, 2);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
            source = filtered[destination].texture;
            filteredIndex = destination;
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        for (int unit = 2; unit >= 0; unit--) {
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

    /**
     * VESSEL: the same luma pair at half resolution, and the field from it.
     *
     * <p><b>The matcher's search window is a hard limit, and at a 67 ms gap the
     * scene routinely moves further than it.</b> Measured on Requiem at 4x while
     * the camera swung at full speed: mean block displacement plateaued at 120
     * pixels and would not rise further, with 94% of blocks reporting the edge of
     * the window. That is not a field. Every vector reads the same number
     * whatever the truth is, so every interpolated frame under-compensates by
     * however far the scene really went -- the picture lags, then snaps to the
     * real frame. Judder with perfect timing, only at high speed, and only at the
     * multiples wide enough to reach the limit.
     *
     * <p>The fix is free, because the matcher is free: 0.001 ms per call, measured.
     * Run it a second time on a half-resolution pair and its vectors come back in
     * half-resolution pixels, so the same window covers twice the distance.
     * Doubling them gives a field that can describe motion the fine pass cannot
     * see at all.
     *
     * <p>Coarser, necessarily -- a block covers 16 full-resolution pixels rather
     * than 8, so it cannot separate two motions as finely. That is the trade, and
     * it is only taken where the fine field has already failed: a coarse vector
     * that is right beats a fine one pinned at its limit.
     *
     * <p>This is the pyramid that was tried this morning and abandoned. It was
     * aimed at accuracy during ordinary motion, where the field is not saturated
     * and there was nothing to win. Its value is range.
     */
    private final Target[] coarseLuma = new Target[2];
    private Target coarseVectors;

    /**
     * VESSEL: the refine step, which is what actually recovers the motion.
     *
     * <p>Substituting the coarse vector where the fine pass is pinned buys reach
     * and pays for it in precision -- a coarse block covers sixteen pixels and
     * cannot separate two motions. Measured against an exact ground truth at a
     * 167 px displacement, substitution took the vector error from 150 px to 93
     * and stopped there, while an unbounded full-resolution search reached 43.
     *
     * <p>A pyramid does not substitute. It uses the coarse result as a starting
     * point and runs the FINE pass again to find what is left over. That is what
     * these three targets are for: {@code guessProbe} reduces the coarse field to
     * one vector, {@code shiftedLuma} holds the older image displaced by it, and
     * {@code biased} holds the residual with the guess added back. The matcher
     * then works at full resolution on a residual that is small by construction.
     *
     * <pre>
     *   field                          vec err   textured   image rms
     *   fine only (bounded)              150.1      141.4       20.05
     *   coarse vector substituted         92.8       76.3       16.74
     *   coarse offset, fine refine         1.4        0.3        2.62
     *   unbounded full-res search         43.1       19.5       12.71
     * </pre>
     *
     * <p>Three tenths of a pixel on the blocks that carry enough detail for a
     * match to mean anything, and five times better than a search with no window
     * at all -- because it has that search's reach and a fine block's precision
     * together. See {@code tools/frame-bench/pyramid.py} and {@link ShiftMaterial}.
     *
     * <p>It needs the sign, so it cannot run until {@link SignMaterial} has
     * latched one; until then the plain fine pass runs and the field is whatever
     * it always was. That is also the fallback if anything here fails.
     */
    private Target shiftedLuma;
    private Target guessProbe;
    private Target biased;
    private float guessX = 0f;
    private float guessY = 0f;
    private boolean refinedField = false;

    /** The field the filter should read: the refined one when there is one. */
    private Target rawField() { return refinedField && biased != null ? biased : vectors; }

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

                // Half of each dimension, rounded down to a whole block so the
                // extension's own requirement still holds.
                final int cw = ((lumaW / 2) / blockX) * blockX;
                final int ch = ((lumaH / 2) / blockY) * blockY;
                if (cw > 0 && ch > 0) {
                    for (int i = 0; i < 2; i++) {
                        coarseLuma[i] = new Target();
                        coarseLuma[i].allocate(cw, ch, GLES30.GL_R8, GLES20.GL_LINEAR);
                    }
                    coarseVectors = new Target();
                    coarseVectors.allocate(cw / blockX, ch / blockY,
                                           GLES30.GL_RGBA16F, GLES20.GL_NEAREST);

                    // The refine step. See the shiftedLuma note.
                    shiftedLuma = new Target();
                    shiftedLuma.allocate(lumaW, lumaH, GLES30.GL_R8, GLES20.GL_LINEAR);
                    biased = new Target();
                    biased.allocate(lumaW / blockX, lumaH / blockY,
                                    GLES30.GL_RGBA16F, GLES20.GL_NEAREST);
                    guessProbe = new Target();
                    guessProbe.allocateAveraging(cw / blockX, ch / blockY);
                }

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
            for (int i = 0; i < 2; i++) if (coarseLuma[i] != null) coarseLuma[i].release();
            if (coarseVectors != null) coarseVectors.release();
            if (shiftedLuma != null) shiftedLuma.release();
            if (biased != null) biased.release();
            if (guessProbe != null) guessProbe.release();
            for (int i = 0; i < 2; i++) if (filtered[i] != null) filtered[i].release();
            if (signProbe != null) signProbe.release();
            if (output != null) output.release();
            if (probe != null) probe.release();
        }
        colour[0] = colour[1] = luma[0] = luma[1] = null;
        vectors = null;
        coarseLuma[0] = coarseLuma[1] = null;
        coarseVectors = null;
        shiftedLuma = null;
        biased = null;
        guessProbe = null;
        refinedField = false;
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
        }

        if (wants("field")) {
            Log.i(TAG, String.format(
                "fg field: block %dx%d, grid %dx%d, luma %dx%d,"
                    + " %s, mean %.0f px, %.0f%% beyond the fine window,"
                    + " %s, matcher refusals %d",
                blockX, blockY,
                vectors != null ? vectors.width : 0,
                vectors != null ? vectors.height : 0,
                luma[0] != null ? luma[0].width : 0,
                luma[0] != null ? luma[0].height : 0,
                filteredIndex >= 0 ? MEDIAN_PASSES + " median passes" : "raw",
                fieldMagnitude, fieldAtLimit * 100f,
                // **Whether the fine pass was aimed, and where.** Without this
                // the refine step is unobservable from the log, which is the
                // mistake the saturation figure already made once: it read the
                // raw field while the change it was meant to judge happened
                // downstream, so it reported the same number whether the coarse
                // pass worked, was mis-wired, or was absent.
                refinedField
                    ? String.format("aimed %.0f,%.0f px then refined", guessX, guessY)
                    : (coarseVectors == null ? "no coarse pass"
                       : (fieldSign == 0f ? "sign not yet latched, unaimed"
                          : "refine declined, unaimed")),
                estimateFailures));
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
            final float harmedShare = moving > 0.001f ? measuredConfidence / moving : 0f;
            Log.i(TAG, String.format(
                "fg truth: %.1f%% of the moving frame is FURTHER from the real"
                    + " frame than doing nothing, %.3f%% invented content,"
                    + " %.0f%% of frame moving, sits %.0f%% of the way between"
                    + " the endpoints (50%% is correct)",
                harmedShare * 100f, measuredDark * 100f,
                moving * 100f, measuredSpeed * 200f));
        }

        tier0Frames = 0;
        tier1Frames = 0;
        skipped = 0;
        estimateFailures = 0;
    }

    private long reportedRealFrames = 0;
}
