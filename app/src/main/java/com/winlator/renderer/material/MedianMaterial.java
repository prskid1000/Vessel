package com.winlator.renderer.material;

/**
 * VESSEL: the outlier remover a block-matched motion field cannot do without.
 *
 * <p>**One wrong block drags everything around it.** A block matcher finds the
 * best match for each block independently, and in flat or repeating regions the
 * best match is frequently not the true one -- a patch of sky matches any other
 * patch of sky. Warping along a field containing those mistakes tears a hole in
 * an otherwise correct picture, and because the field is sampled bilinearly the
 * damage spreads into the neighbours that were right.
 *
 * <p>The standard answer, and the one FidelityFX uses as an explicit pass
 * between its search and its upscale, is a 3x3 <em>vector</em> median. Not a
 * per-channel median, which can invent a vector that no block voted for by
 * taking x from one candidate and y from another: for each of the nine
 * candidates the summed distance to all nine is computed, and the one that is
 * collectively closest to the rest wins. The winner is therefore always a vector
 * some block actually reported, and a single outlier can never be it.
 *
 * <p>Written without array indexing, deliberately. GLSL ES 1.00 only guarantees
 * arrays indexed by constant expressions, so a loop over nine samples is a
 * portability gamble for no gain -- the whole thing unrolls to nine taps and
 * eighty-one distances, which is nothing at one texel per 8x8 block.
 *
 * <p>Sampling relies on the field being addressed at texel centres: with offsets
 * of exactly one texel, a linear sampler returns the neighbouring texel's value
 * unblended, so this needs no separate sampler state.
 */
public class MedianMaterial extends ScreenMaterial {
    public final Uniforms medianUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform texelSize = new Uniform("texelSize");
        public final Uniform backwardTexture = new Uniform("backwardTexture");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform sampler2D backwardTexture;",
            "uniform vec2 texelSize;",

            "varying vec2 vUV;",

            // Summed distance from one candidate to all nine. The candidate is
            // included against itself, which contributes zero and keeps the
            // comparison between candidates fair.
            "float cost(vec2 x, vec2 a, vec2 b, vec2 c, vec2 d, vec2 e,",
            "           vec2 f, vec2 g, vec2 h, vec2 i) {",
                "return distance(x, a) + distance(x, b) + distance(x, c)",
                "     + distance(x, d) + distance(x, e) + distance(x, f)",
                "     + distance(x, g) + distance(x, h) + distance(x, i);",
            "}",

            "void main() {",
                "vec2 t = texelSize;",
                "vec2 v0 = texture2D(screenTexture, vUV + vec2(-t.x, -t.y)).rg;",
                "vec2 v1 = texture2D(screenTexture, vUV + vec2( 0.0, -t.y)).rg;",
                "vec2 v2 = texture2D(screenTexture, vUV + vec2( t.x, -t.y)).rg;",
                "vec2 v3 = texture2D(screenTexture, vUV + vec2(-t.x,  0.0)).rg;",
                "vec2 v4 = texture2D(screenTexture, vUV).rg;",
                "vec2 v5 = texture2D(screenTexture, vUV + vec2( t.x,  0.0)).rg;",
                "vec2 v6 = texture2D(screenTexture, vUV + vec2(-t.x,  t.y)).rg;",
                "vec2 v7 = texture2D(screenTexture, vUV + vec2( 0.0,  t.y)).rg;",
                "vec2 v8 = texture2D(screenTexture, vUV + vec2( t.x,  t.y)).rg;",

                "float c0 = cost(v0, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c1 = cost(v1, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c2 = cost(v2, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c3 = cost(v3, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c4 = cost(v4, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c5 = cost(v5, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c6 = cost(v6, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c7 = cost(v7, v0,v1,v2,v3,v4,v5,v6,v7,v8);",
                "float c8 = cost(v8, v0,v1,v2,v3,v4,v5,v6,v7,v8);",

                "vec2 best = v0;",
                "float bestCost = c0;",
                "if (c1 < bestCost) { bestCost = c1; best = v1; }",
                "if (c2 < bestCost) { bestCost = c2; best = v2; }",
                "if (c3 < bestCost) { bestCost = c3; best = v3; }",
                "if (c4 < bestCost) { bestCost = c4; best = v4; }",
                "if (c5 < bestCost) { bestCost = c5; best = v5; }",
                "if (c6 < bestCost) { bestCost = c6; best = v6; }",
                "if (c7 < bestCost) { bestCost = c7; best = v7; }",
                "if (c8 < bestCost) { bestCost = c8; best = v8; }",

                // **Forward-backward consistency, and it is what stops a
                // confident wrong vector smearing the picture.** A block matched
                // in both directions should agree: the motion from the older
                // frame to the newer one is the negative of the motion back.
                // Where it does not agree the block was occluded, or matched a
                // repeating pattern, or found nothing -- and the matcher reports
                // all of those with exactly the same confidence as a correct
                // match, because the extension gives us no residual to ask about.
                //
                // The threshold is the standard one from the optical-flow
                // literature: scaled by the magnitudes, so fast motion is allowed
                // proportionally more disagreement than slow, plus a constant so
                // a near-still block is not failed by rounding.
                "vec2 backward = texture2D(backwardTexture, vUV).rg;",
                "vec2 residual = best + backward;",
                "float slack = 0.01 * (dot(best, best) + dot(backward, backward)) + 0.5;",
                // Zero, not the unreliable vector. An unmoved region is a frame
                // that looks a little stale; a wrongly moved one is a tear.
                "if (dot(residual, residual) > slack) best = vec2(0.0);",

                "gl_FragColor = vec4(best, 0.0, 1.0);",
            "}"
        );
    }
}
