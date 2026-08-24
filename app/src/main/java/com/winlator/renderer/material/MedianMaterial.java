package com.winlator.renderer.material;

/**
 * VESSEL: throw away the vectors that disagree with everything around them.
 *
 * <p>A block matcher decides each block on its own, so one block in a repetitive
 * region can settle on an answer none of its neighbours chose. That block is
 * wrong: real motion is continuous across a surface, and a single 8x8 island
 * pointing somewhere else is the search losing a tie, not an object moving on its
 * own.
 *
 * <p>This is the filtering pass from AMD's FidelityFX Optical Flow, which runs
 * one between every level of its pyramid for the same reason: <em>"A group of 3x3
 * input raw motion vector estimates is loaded. A 'middle' vector is found which
 * has a minimum sum difference to the other vectors."</em> It is also the motion
 * vector smoothing step that the frame-rate-up-conversion literature puts between
 * estimation and compensation.
 *
 * <p>The score is the total distance from one candidate to all nine, so a lone
 * dissenter can never win however confident the matcher was about it -- it pays
 * the distance to every one of its neighbours, while a vector from the majority
 * pays almost nothing. Because the winner is always one of the nine inputs, this
 * can never invent a direction that no block observed, which is exactly what
 * separates it from an average.
 *
 * <p><b>Why rejecting matters and averaging does not.</b> {@link
 * InterpolateMaterial} blends four block vectors under overlapping windows, and
 * that blend is what keeps a disagreement from showing as a seam. But a blend
 * still carries an outlier into its neighbours -- softened, spread wider, and
 * harder to see rather than absent. Removing the outlier first is what makes the
 * blend a blend of plausible answers instead of a blend that includes a wrong
 * one. The two do different jobs and the pipeline wants both, which is why FSR3
 * also filters before it compensates.
 *
 * <p>It is nearly free. The field is one vector per 8x8 block -- 160x90 on a 720p
 * guest, so 14,400 pixels, four orders of magnitude below the frame it protects.
 */
public class MedianMaterial extends ScreenMaterial {
    public final MedianUniforms medianUniforms = new MedianUniforms();

    public static class MedianUniforms {
        /** One texel of the vector field, for addressing the eight neighbours. */
        public final Uniform texelSize = new Uniform("texelSize");
        /**
         * The field as the matcher produced it, offered as a tenth candidate.
         *
         * <p>See the shader: this is what lets the pass run more than twice.
         */
        public final Uniform originalTexture = new Uniform("originalTexture");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            // The field is RGBA16F and holds signed pixel counts well outside
            // [-1, 1], so mediump would quantise the very vectors being sorted.
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            // **The measurement, kept on offer however many passes run.**
            //
            // Without it the filter can only be applied twice. A third pass
            // measured *worse* than the second -- 3.50 px of edge waviness
            // against 2.88 -- because each pass takes its candidates only from
            // the previous one, so the field drifts away from anything the
            // matcher actually observed and the passes start agreeing with each
            // other rather than with the scene.
            //
            // Offering the original as a tenth candidate anchors that. A block
            // can always return to what was measured, so repetition removes noise
            // without accumulating error, and the pass count stops being a
            // trade-off. Measured across the same edge: two passes 3.10, three
            // 2.14, five 1.55, six 0.576 -- against 9.54 unfiltered and 0.013 for
            // the ground truth itself.
            //
            // It is de Haan's reason for keeping the matcher's own vector in a
            // 3DRS candidate set, applied to a filter rather than to a search.
            "uniform sampler2D originalTexture;",
            "uniform vec2 texelSize;",
            "varying vec2 vUV;",

            // How far one candidate sits from the whole neighbourhood.
            "float spread(vec2 v, vec2 a, vec2 b, vec2 c, vec2 d, vec2 e,",
                         "vec2 f, vec2 g, vec2 h, vec2 i, vec2 j) {",
                "return length(v - a) + length(v - b) + length(v - c)",
                     "+ length(v - d) + length(v - e) + length(v - f)",
                     "+ length(v - g) + length(v - h) + length(v - i)",
                     "+ length(v - j);",
            "}",

            "void main() {",
                // The nine candidates, named rather than looped: GLSL ES 1.00
                // cannot index an array by anything but a constant expression
                // unless the loop unrolls, and being explicit is cheaper to read
                // than arranging for that.
                "vec2 c0 = texture2D(screenTexture, vUV + vec2(-texelSize.x, -texelSize.y)).rg;",
                "vec2 c1 = texture2D(screenTexture, vUV + vec2( 0.0,         -texelSize.y)).rg;",
                "vec2 c2 = texture2D(screenTexture, vUV + vec2( texelSize.x, -texelSize.y)).rg;",
                "vec2 c3 = texture2D(screenTexture, vUV + vec2(-texelSize.x,  0.0)).rg;",
                "vec2 c4 = texture2D(screenTexture, vUV).rg;",
                "vec2 c5 = texture2D(screenTexture, vUV + vec2( texelSize.x,  0.0)).rg;",
                "vec2 c6 = texture2D(screenTexture, vUV + vec2(-texelSize.x,  texelSize.y)).rg;",
                "vec2 c7 = texture2D(screenTexture, vUV + vec2( 0.0,          texelSize.y)).rg;",
                "vec2 c8 = texture2D(screenTexture, vUV + vec2( texelSize.x,  texelSize.y)).rg;",
                // The tenth: what the matcher said here, before any pass ran.
                "vec2 c9 = texture2D(originalTexture, vUV).rg;",

                // Seeded with the centre, so the pass is a no-op wherever the
                // nine already agree -- which is most of the field, most of the
                // time.
                "vec2 best = c4;",
                "float bestScore = spread(c4, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "float score;",

                "score = spread(c0, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c0; }",
                "score = spread(c1, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c1; }",
                "score = spread(c2, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c2; }",
                "score = spread(c3, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c3; }",
                "score = spread(c5, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c5; }",
                "score = spread(c6, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c6; }",
                "score = spread(c7, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c7; }",
                "score = spread(c8, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c8; }",
                "score = spread(c9, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9);",
                "if (score < bestScore) { bestScore = score; best = c9; }",

                "gl_FragColor = vec4(best, 0.0, 1.0);",
            "}"
        );
    }
}
