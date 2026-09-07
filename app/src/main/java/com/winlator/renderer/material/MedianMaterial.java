package com.winlator.renderer.material;

/**
 * VESSEL: reject the vectors that disagree with everything around them, for
 * both fields in one pass.
 *
 * <p>The field is packed: forward in RG, backward in BA. Each half is filtered
 * on its own with the same rule, and the two never mix. One pass over one
 * 160x90 target therefore does what two passes over two targets did.
 *
 * <p>The rule is a vector median: of the nine neighbours, the matcher's own
 * answer for this block, and the block's answer at the previous real frame,
 * the winner is the candidate with the least total distance to the set. The
 * winner is always one of the inputs, so the filter cannot invent a direction
 * no block observed, which is what separates it from an average.
 *
 * <ul>
 * <li>The matcher's own vector stays on offer every pass, so repeated passes
 *     remove noise without drifting away from what was measured. That is what
 *     lets the pass count reach ten.
 * <li>The previous frame's vector is read where this block's content came
 *     from, not where the block sits, and casts {@code temporalWeight} votes.
 *     It breaks near-ties towards continuity, which is the shimmer, and where
 *     the scene really changed it simply loses.
 * </ul>
 */
public class MedianMaterial extends ScreenMaterial {
    public final MedianUniforms medianUniforms = new MedianUniforms();

    public static class MedianUniforms {
        /** One texel of the field. */
        public final Uniform texelSize = new Uniform("texelSize");
        /** The packed field as the matcher produced it, before any pass. */
        public final Uniform originalTexture = new Uniform("originalTexture");
        /** The packed field as filtered at the previous real frame. */
        public final Uniform previousTexture = new Uniform("previousTexture");
        /** 0 until a previous field exists. */
        public final Uniform temporalValid = new Uniform("temporalValid");
        /** One luma pixel in UV, unsigned. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** The extension's sign convention. See {@code FrameSynthesizer.FIELD_SIGN}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
        /** Votes the previous vector casts: 1 is a plain candidate, 3 a prior. */
        public final Uniform temporalWeight = new Uniform("temporalWeight");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            // Signed pixel counts well outside [-1, 1]; mediump would quantise
            // the vectors being sorted.
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform sampler2D originalTexture;",
            "uniform sampler2D previousTexture;",
            "uniform float temporalValid;",
            "uniform vec2 motionScale;",
            "uniform float fieldSign;",
            "uniform float temporalWeight;",
            "uniform vec2 texelSize;",
            "varying vec2 vUV;",

            // The least-total-distance candidate of eleven, seeded with the
            // centre and beaten only strictly, so an agreeing neighbourhood is
            // left exactly as it was. Candidate 10 is the temporal one. Each
            // pair's distance is measured once and credited to both ends.
            "vec2 choose(vec2 c[11]) {",
                "vec2 agree = vec2(0.0);",
                "for (int i = 0; i < 11; i++) agree += abs(c[i] - c[4]);",
                "if (agree.x + agree.y < 1.0e-7) return c[4];",
                "float score[11];",
                "for (int i = 0; i < 11; i++) score[i] = 0.0;",
                "for (int i = 0; i < 11; i++) {",
                    "for (int j = i + 1; j < 11; j++) {",
                        "float d = length(c[i] - c[j]);",
                        "score[i] += d * (j == 10 ? temporalWeight : 1.0);",
                        "score[j] += d;",
                    "}",
                "}",
                "vec2 best = c[4];",
                "float bestScore = score[4];",
                "for (int i = 0; i < 11; i++) {",
                    "if (score[i] < bestScore) { bestScore = score[i]; best = c[i]; }",
                "}",
                "return best;",
            "}",

            "void main() {",
                "vec4 n[9];",
                "n[0] = texture2D(screenTexture, vUV + vec2(-texelSize.x, -texelSize.y));",
                "n[1] = texture2D(screenTexture, vUV + vec2( 0.0,         -texelSize.y));",
                "n[2] = texture2D(screenTexture, vUV + vec2( texelSize.x, -texelSize.y));",
                "n[3] = texture2D(screenTexture, vUV + vec2(-texelSize.x,  0.0));",
                "n[4] = texture2D(screenTexture, vUV);",
                "n[5] = texture2D(screenTexture, vUV + vec2( texelSize.x,  0.0));",
                "n[6] = texture2D(screenTexture, vUV + vec2(-texelSize.x,  texelSize.y));",
                "n[7] = texture2D(screenTexture, vUV + vec2( 0.0,          texelSize.y));",
                "n[8] = texture2D(screenTexture, vUV + vec2( texelSize.x,  texelSize.y));",
                "vec4 original = texture2D(originalTexture, vUV);",

                // Where this block's content was one real frame ago, per
                // field. The forward field (indexed on N) already points back
                // to N-1; the backward field (indexed on N-1) points forward
                // to N, so the step to where its content was in N-2 is the
                // opposite way. Clamped: off the edge the read folds onto the
                // centre and contributes nothing.
                "vec2 step = fieldSign * motionScale;",
                "vec2 cameF = clamp(vUV + n[4].rg * step, 0.0, 1.0);",
                "vec2 cameB = clamp(vUV - n[4].ba * step, 0.0, 1.0);",
                "vec2 prevF = mix(n[4].rg, texture2D(previousTexture, cameF).rg, temporalValid);",
                "vec2 prevB = mix(n[4].ba, texture2D(previousTexture, cameB).ba, temporalValid);",

                "vec2 f[11]; vec2 b[11];",
                "for (int i = 0; i < 9; i++) { f[i] = n[i].rg; b[i] = n[i].ba; }",
                "f[9] = original.rg; b[9] = original.ba;",
                "f[10] = prevF; b[10] = prevB;",
                "gl_FragColor = vec4(choose(f), choose(b));",
            "}"
        );
    }
}
