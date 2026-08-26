package com.winlator.renderer.material;

/**
 * VESSEL: ask the real field the question the laptop model cannot answer.
 *
 * <h2>What this is for</h2>
 *
 * <p>A change that weighted the four OBMC hypotheses by how well each explained
 * its own block -- inverse-variance weighting, which is what the estimation
 * theory behind OBMC prescribes -- scored better than the shipped path on mean
 * error, on error over the blocks that move differently from the frame, and on
 * speckle. It was installed and put black patches on the display within minutes.
 *
 * <p>It was then rebuilt offline and scored against the same recordings, and it
 * comes out <em>cleaner</em> than the baseline: less invented black, fewer
 * ruined cells, and its extra hypothesis leaving the frame no more often than
 * the four it joins. The model cannot reproduce the fault.
 *
 * <p>One difference between the model and the device is large enough to be the
 * whole story. The model's field is dense optical flow averaged to blocks --
 * coherent by construction, with no outliers. The device's comes from
 * {@code glTexEstimateMotionQCOM}, which needed ten anchored median passes
 * before straight edges stopped being wavy. And inverse-variance weighting has
 * exactly one thing it cannot survive:
 *
 * <blockquote>a block whose vector is <em>wrong</em> but which happens to match
 * content somewhere else scores a <em>low</em> fit, and is therefore weighted
 * <em>up</em>.</blockquote>
 *
 * <p>On a clean field that case barely exists, which is why no amount of laptop
 * scoring would ever have found it. On a real one, in a dark corridor, "matches
 * content elsewhere" and "is black" are frequently the same pixels.
 *
 * <h2>Why this is a probe and not the change</h2>
 *
 * <p>Nothing reads what this writes. It renders to its own small target, is
 * reduced to four numbers, and is printed. The interpolation is untouched, so
 * the picture is bit-identical to the build without it, and the hypothesis can
 * be tested without another install that has to be reverted.
 *
 * <h2>What each channel asks</h2>
 *
 * <p>The dangerous weight is {@code 1 / (fit + floor)^2}, so a fit at the floor
 * takes 16,000 times the weight of one at 1.0. The question is not whether that
 * ratio is large -- it is by design -- but whether the blocks reaching it are
 * the ones that deserve it.
 *
 * <ul>
 * <li><b>R</b> -- the block's own fit, so the distribution has a mean.
 * <li><b>G</b> -- fits below the noise floor: the blocks that would take
 *     maximal weight.
 * <li><b>B</b> -- <b>the answer.</b> Blocks that would take maximal weight
 *     <em>and</em> whose vector disagrees with its neighbours by more than two
 *     blocks. A vector no neighbour agrees with, which nonetheless matches its
 *     own content perfectly, is the failure above made visible. If this reads
 *     near zero the hypothesis is wrong and the black patches came from
 *     somewhere else entirely.
 * <li><b>A</b> -- how far the frame's dominant vector sits from this block's
 *     own, in units of the search window, so it is possible to see whether the
 *     fifth hypothesis was ever pointing anywhere near the local truth.
 * </ul>
 */
public class FieldProbeMaterial extends ScreenMaterial {
    public final ProbeUniforms probeUniforms = new ProbeUniforms();

    public static class ProbeUniforms {
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** One field unit in texture space: 1 / luma size. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** One texel of the vector field. */
        public final Uniform texelSize = new Uniform("texelSize");
        /** Which way the field points. See {@link SignMaterial}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 motionScale;",
            "uniform vec2 texelSize;",
            "uniform float fieldSign;",
            "varying vec2 vUV;",

            // Scored at the midpoint: the two fetches are separated by exactly
            // the vector at every phase, and only where that pair sits moves.
            "float sad(vec2 b, vec2 at) {",
                "return abs(texture2D(lumaNewerTexture, clamp(at - b * 0.5, 0.0, 1.0)).r",
                         "- texture2D(lumaOlderTexture, clamp(at + b * 0.5, 0.0, 1.0)).r);",
            "}",

            // Four points inside the block rather than one at its centre. A
            // single sample is one pixel's opinion of sixty-four of them, and on
            // a block straddling two surfaces the centre pixel belongs to
            // whichever happens to cover it.
            "float fitOf(vec2 b) {",
                "vec2 q = motionScale * 2.0;",
                "return 0.25 * (sad(b, vUV + vec2(-q.x, -q.y))",
                             "+ sad(b, vUV + vec2( q.x, -q.y))",
                             "+ sad(b, vUV + vec2(-q.x,  q.y))",
                             "+ sad(b, vUV + vec2( q.x,  q.y)));",
            "}",

            "vec2 vectorAt(vec2 at) {",
                "return texture2D(screenTexture, clamp(at, 0.0, 1.0)).rg * fieldSign;",
            "}",

            "void main() {",
                // In field units -- raw pixel counts -- so the disagreement
                // below can be stated in blocks without a conversion.
                "vec2 own = vectorAt(vUV);",

                "vec2 sum = vec2(0.0);",
                "for (int y = 0; y < 5; y++) {",
                    "for (int x = 0; x < 5; x++) {",
                        "sum += vectorAt((vec2(float(x), float(y)) + 0.5) / 5.0);",
                    "}",
                "}",
                "vec2 dominant = sum / 25.0;",

                // The four neighbours, which is what MedianMaterial's passes
                // would have driven this block towards had it been an outlier.
                "vec2 around = 0.25 * (vectorAt(vUV + vec2(texelSize.x, 0.0))",
                                    "+ vectorAt(vUV - vec2(texelSize.x, 0.0))",
                                    "+ vectorAt(vUV + vec2(0.0, texelSize.y))",
                                    "+ vectorAt(vUV - vec2(0.0, texelSize.y)));",

                "float fit = fitOf(own * motionScale);",
                // Two 8-bit levels: below this a difference is rounding, and it
                // is the floor the weighting adds before squaring.
                "float atFloor = 1.0 - step(2.0 / 255.0, fit);",

                // **Measured, not thresholded, because the threshold was the
                // whole problem.** This began as step(16.0, ...) -- "disagrees
                // with its neighbours by two blocks or more" -- and read 0.00%
                // in every sample, which proves nothing at all: a test that
                // cannot fire and a test that fires and finds nothing look
                // identical in a log.
                //
                // Sixteen pixels was a guess, and it was a bad one for a field
                // that has been through ten anchored median passes whose entire
                // purpose is to delete blocks disagreeing with their
                // neighbours. Of course none are left.
                //
                // That is worth more than the failed test. A wrong vector that
                // SURVIVES the filter is not an isolated block -- it cannot be,
                // by construction -- it is one a whole neighbourhood shares. So
                // the quantity is reported rather than a verdict on it, and the
                // scale it comes back at says what a threshold should have
                // been, instead of a guess deciding whether anything is seen.
                "float disagree = length(own - around);",

                // B is the same quantity restricted to the blocks that would
                // take maximal weight, so B against A says whether a bad fit
                // and a locally-odd vector go together -- which is the
                // inversion inverse-variance weighting cannot survive -- or
                // whether they are unrelated.
                //
                // Both are scaled by 32 px, which is four blocks and comfortably
                // above anything a filtered field should contain, so the mean
                // arrives with room rather than clipped against a ceiling.
                "float scaled = min(disagree / 32.0, 1.0);",
                "gl_FragColor = vec4(",
                    "min(fit * 4.0, 1.0),",
                    "atFloor,",
                    "atFloor * scaled,",
                    "scaled);",
            "}"
        );
    }
}
