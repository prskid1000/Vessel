package com.winlator.renderer;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import com.winlator.renderer.material.ScreenMaterial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * VESSEL: does {@code GL_QCOM_motion_estimation} actually estimate motion?
 *
 * <p>This exists because its sibling did not. {@code GL_QCOM_frame_extrapolation}
 * is advertised by this driver, accepts the call, reports {@code GL_NO_ERROR} and
 * writes an eight-pixel ramp -- proved by reading the destination back, after
 * five fixes aimed at the call were all wrong. An advertised extension is not a
 * working one, and the only way to tell the difference is to look at what came
 * out.
 *
 * <p>So this asks the same question of the other extension before anything is
 * built on it. The advantage of this one is that its output is *data* rather than
 * a picture: {@code glTexEstimateMotionQCOM(ref, target, output)} writes a motion
 * vector per search block, in pixels, into the R and G channels of an RGBA16F
 * texture. Vectors that are all zero, or all identical, or a repeating pattern,
 * answer the question immediately and without interpreting a screenshot.
 *
 * <p>A probe and not a feature. It runs once per context, logs what it found and
 * frees everything. If the answer is yes, reprojection on top of these vectors is
 * ours to write and every step of it is inspectable -- which is the whole reason
 * to prefer this over the black box.
 */
public class MotionProbe {
    static {
        System.loadLibrary("winlator");
    }

    private static native boolean resolveEntryPoint();

    private static native void texEstimateMotion(int ref, int target, int output);

    /** From the extension's New Tokens. Queried with {@code glGetIntegerv}. */
    private static final int MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM = 0x8C90;
    private static final int MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM = 0x8C91;

    private static final String EXTENSION = "GL_QCOM_motion_estimation";
    private static final String TAG = "MotionProbe";

    /**
     * Luma, because the extension asks for it.
     *
     * <p>Reference and target must be {@code GL_R8}, and the spec says outright
     * that estimation tracks brightness and that the inputs should represent
     * luma. Rendering the colour frame through this into an R8 target is one
     * pass and gets both requirements at once.
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
     * Run the probe once for this context and log the verdict.
     *
     * @param previous colour texture of the older frame
     * @param latest colour texture of the newer frame
     */
    public static void run(GLRenderer renderer, int previous, int latest, int width, int height) {
        if (probedGeneration == GLRenderer.contextGeneration()) return;
        probedGeneration = GLRenderer.contextGeneration();

        final String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        boolean advertised = false;
        if (extensions != null) {
            for (String name : extensions.split(" ")) {
                if (name.equals(EXTENSION)) { advertised = true; break; }
            }
        }
        if (!advertised) { Log.i(TAG, EXTENSION + " not advertised"); return; }
        if (!resolveEntryPoint()) { Log.i(TAG, "advertised but no entry point"); return; }

        final int[] value = new int[1];
        GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM, value, 0);
        final int blockX = Math.max(1, value[0]);
        GLES20.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM, value, 0);
        final int blockY = Math.max(1, value[0]);

        // The inputs must be an exact multiple of the block, so the probe works
        // on the largest such rectangle rather than on the whole frame.
        final int lumaW = (width / blockX) * blockX;
        final int lumaH = (height / blockY) * blockY;
        final int outW = lumaW / blockX;
        final int outH = lumaH / blockY;
        Log.i(TAG, "block " + blockX + "x" + blockY + ", luma " + lumaW + "x" + lumaH
            + ", vectors " + outW + "x" + outH);
        if (outW <= 0 || outH <= 0) { Log.i(TAG, "frame smaller than one block"); return; }

        int refLuma = 0, targetLuma = 0, vectors = 0, refFbo = 0, targetFbo = 0, vecFbo = 0;
        final LumaMaterial material = new LumaMaterial();
        try {
            final int[] names = new int[1];

            refLuma = lumaTexture(lumaW, lumaH, names);
            refFbo = framebufferFor(refLuma, names);
            render(renderer, material, previous, refFbo, lumaW, lumaH);

            targetLuma = lumaTexture(lumaW, lumaH, names);
            targetFbo = framebufferFor(targetLuma, names);
            render(renderer, material, latest, targetFbo, lumaW, lumaH);

            GLES20.glGenTextures(1, names, 0);
            vectors = names[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectors);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, outW, outH);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            vecFbo = framebufferFor(vectors, names);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glFinish();
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }

            // The control, and it is not optional. The spec says a zero vector
            // means "no motion detected OR masked", so an all-zero result from
            // two identical frames is the extension working perfectly. Without
            // knowing whether the inputs differ, the output cannot be read at
            // all -- which is the mistake that cost five builds on the sibling
            // extension.
            final int differing = countDifferences(refFbo, targetFbo, lumaW, lumaH);
            Log.i(TAG, "luma inputs differ in " + differing + " of " + (SAMPLE * SAMPLE)
                + " sampled pixels"
                + (differing == 0 ? "  <-- IDENTICAL, a zero result would be correct" : ""));

            texEstimateMotion(refLuma, targetLuma, vectors);

            final int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "glTexEstimateMotionQCOM failed 0x" + Integer.toHexString(error));
                return;
            }
            describe(vecFbo, outW, outH);
        } catch (Throwable e) {
            Log.e(TAG, "probe failed", e);
        } finally {
            deleteFramebuffers(refFbo, targetFbo, vecFbo);
            deleteTextures(refLuma, targetLuma, vectors);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            renderer.invalidateBoundWindowMaterial();
        }
    }

    private static int probedGeneration = -1;

    /**
     * How many pixels actually changed between the two luma inputs.
     *
     * <p>Read back rather than assumed. R8 is read as RGBA/UNSIGNED_BYTE, which
     * ES guarantees for a colour-renderable attachment, and only the red channel
     * carries anything.
     */
    private static int countDifferences(int refFbo, int targetFbo, int width, int height) {
        // A patch from the middle, not the whole frame. Reading two full 2776x1264
        // surfaces back cost 28 MB of transfer and a three-million-iteration loop
        // on the GL thread, which blocked long enough that Android asked the
        // process for stack traces -- an ANR caused entirely by the instrument.
        // A 256x256 patch answers the same question: did anything move.
        final int x = Math.max(0, (width - SAMPLE) / 2);
        final int y = Math.max(0, (height - SAMPLE) / 2);
        final int w = Math.min(SAMPLE, width);
        final int h = Math.min(SAMPLE, height);
        final byte[] a = readRed(refFbo, x, y, w, h);
        final byte[] b = readRed(targetFbo, x, y, w, h);
        int differing = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) differing++;
        }
        return differing;
    }

    /** Side of the patch sampled for the control. See countDifferences. */
    private static final int SAMPLE = 256;

    private static byte[] readRed(int framebuffer, int x, int y, int width, int height) {
        final ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glReadPixels(x, y, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        final byte[] red = new byte[width * height];
        for (int i = 0; i < red.length; i++) {
            red[i] = pixels.get(i * 4);
        }
        return red;
    }

    private static int lumaTexture(int width, int height, int[] names) {
        GLES20.glGenTextures(1, names, 0);
        final int texture = names[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R8, width, height);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return texture;
    }

    private static int framebufferFor(int texture, int[] names) {
        GLES20.glGenFramebuffers(1, names, 0);
        final int framebuffer = names[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                      GLES20.GL_TEXTURE_2D, texture, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return framebuffer;
    }

    private static void render(GLRenderer renderer, LumaMaterial material, int source,
                               int framebuffer, int width, int height) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_BLEND);
        material.use();
        renderer.quadVertices.bind(material.programId);
        material.setUniformBool(material.uniforms.flipY, false);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source);
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        renderer.viewportNeedsUpdate = true;
    }

    /**
     * Read the vectors back and say what they are.
     *
     * <p>The three answers that matter are all visible in the numbers. All zero
     * means the extension did nothing. A single repeated value, or a run that
     * counts upwards, means a stub like the other one. A spread of small signed
     * values, mostly near zero with larger ones where the scene moved, is real
     * motion -- and then the rest is ours to write.
     */
    private static void describe(int framebuffer, int width, int height) {
        final FloatBuffer pixels = ByteBuffer
            .allocateDirect(width * height * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, pixels);
        final int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "readback failed 0x" + Integer.toHexString(error));
            return;
        }

        pixels.rewind();
        int nonZero = 0, distinct = 0;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        double sumAbs = 0;
        final java.util.HashSet<Long> seen = new java.util.HashSet<>();
        final int count = width * height;
        final StringBuilder first = new StringBuilder();
        for (int i = 0; i < count; i++) {
            final float x = pixels.get(), y = pixels.get();
            pixels.get(); pixels.get();   // B and A are undefined by the spec
            if (x != 0f || y != 0f) nonZero++;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            sumAbs += Math.abs(x) + Math.abs(y);
            if (seen.size() < 4096) seen.add(((long)Float.floatToIntBits(x) << 32)
                                            | (Float.floatToIntBits(y) & 0xffffffffL));
            if (i < 8) first.append(String.format("(%.2f,%.2f) ", x, y));
        }
        distinct = seen.size();
        Log.i(TAG, "vectors " + count + ": nonzero " + nonZero + ", distinct " + distinct
            + ", x [" + minX + ".." + maxX + "], y [" + minY + ".." + maxY + "]"
            + ", mean|v| " + (sumAbs / (2.0 * count)));
        Log.i(TAG, "first 8: " + first);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private static void deleteTextures(int... names) {
        for (int name : names) if (name != 0) GLES20.glDeleteTextures(1, new int[] {name}, 0);
    }

    private static void deleteFramebuffers(int... names) {
        for (int name : names) if (name != 0) GLES20.glDeleteFramebuffers(1, new int[] {name}, 0);
    }
}
