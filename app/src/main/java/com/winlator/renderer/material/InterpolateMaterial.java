package com.winlator.renderer.material;

/**
 * VESSEL: a frame built between two real ones.
 *
 * <p>Both endpoints are known, so every output pixel is a blend of two
 * observed pixels rather than a guess past one. The price is one interval of
 * latency: frame N is shown only after the frames belonging before it.
 *
 * <h2>The geometry</h2>
 *
 * <p>The field carries one vector per search block, in pixels. With {@code v}
 * the vector as this shader uses it (the raw field times {@code fieldSign}),
 * content that is at {@code q} at phase {@code t} was at {@code q + v*t} in
 * N-1 and is at {@code q - v*(1-t)} in N. Sampling both endpoints
 * symmetrically about the output pixel is bilateral motion compensation: every
 * output pixel is written exactly once, so there are no holes and no overlaps.
 *
 * <h2>Where the field is indexed, and why that is N</h2>
 *
 * <p>The forward field comes from the two-stage search: the older frame is
 * warped onto the newer one by a coarse prior and the fine matcher runs on
 * that warped image, which sits in frame N's geometry. The matcher indexes its
 * vectors on its {@code ref}, so the merged field describes, at each N
 * position, where that content came from. The backward field is built the
 * other way round and is indexed on N-1.
 *
 * <p>So the field is read at this pixel's N site, {@code q - v*(1-t)}, after
 * one fixed-point step from the vector at {@code q}. Reading it at {@code q}
 * applies, near a moving edge, the vector of whatever sits at {@code q} in N
 * rather than of the content passing through {@code q} now; reading it at the
 * N-1 site (which f74b9bc did, on the strength of a probe that ran the
 * single-pass search whose ref really is N-1) is off by a whole displacement.
 * Measured on the device's own dumps by closing the forward-backward round
 * trip under both readings: N-indexed closes at half the residual of N-1
 * indexed on every textured, moving scene ({@code tools/frame-bench/indexing.py}).
 *
 * <h2>What is done per pixel</h2>
 *
 * <ul>
 * <li>Overlapped block motion compensation: the four blocks around the pixel
 *     each predict it, blended under a raised-cosine window scaled by how well
 *     each prediction's two endpoints agree. Nothing is chosen, so nothing
 *     pops.
 * <li>Photometric source weights: an endpoint reading content that did not
 *     change between the frames, for a pixel that did, is reading an overlay
 *     and is weighted down.
 * <li>Geometric source weights: the forward and backward fields are inverses
 *     of each other where both frames see the surface. Where the round trip
 *     fails on one side, that frame cannot see the surface and is dropped,
 *     with a tolerance proportional to the flow (Sundaram, Brox, Keutzer).
 * <li>A fetch that leaves the frame carries no evidence and gets no weight.
 * <li>{@code carry}: where the two frames agree at this pixel better than they
 *     agree along the vector, the pixel is a screen-space overlay and the
 *     stationary reading wins. That is the subtitle ghost.
 * </ul>
 *
 * @see MedianMaterial for the filter the field goes through first.
 */
public class InterpolateMaterial extends ScreenMaterial {
    public final Uniforms interpolateUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform previousTexture = new Uniform("previousTexture");
        /** The packed field: forward in RG (indexed on N), backward in BA (on N-1). */
        public final Uniform motionTexture = new Uniform("motionTexture");
        /** One colour texel in UV: the field is in colour pixels. */
        public final Uniform motionScale = new Uniform("motionScale");
        public final Uniform vectorSize = new Uniform("vectorSize");
        public final Uniform phase = new Uniform("phase");
        /** 1 makes this pass report on itself instead of drawing. */
        public final Uniform diagnostic = new Uniform("diagnostic");
        /** 1 stamps synthesised frames so recordings can tell them apart. */
        public final Uniform mark = new Uniform("mark");
        /** The extension's sign convention. See {@code FrameSynthesizer.FIELD_SIGN}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
        /**
         * Colour size over luma size, per axis: 1 when the guest is a whole
         * number of search blocks, slightly above 1 when it is not. The field
         * covers only the block-rounded top-left of the frame.
         */
        public final Uniform lumaScale = new Uniform("lumaScale");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "#define PI 3.14159265",

            // Frame N, frame N-1, and the packed field: RG forward (indexed on
            // N), BA backward (indexed on N-1), both in colour pixels, sampled
            // NEAREST so the four blocks arrive as the four answers they are.
            "uniform sampler2D screenTexture;",
            "uniform sampler2D previousTexture;",
            "uniform sampler2D motionTexture;",
            // One colour texel in UV. A luma pixel is a colour pixel: the luma
            // is the block-rounded top-left of the colour frame.
            "uniform vec2 motionScale;",
            // The block grid's size in texels.
            "uniform vec2 vectorSize;",
            // 0 is N-1, 1 is N.
            "uniform float phase;",
            // 1 writes four measurements per pixel instead of a picture.
            "uniform float diagnostic;",
            // 1 stamps sixteen magenta pixels so recordings can tell
            // synthesised frames from real ones.
            "uniform float mark;",
            // The extension's sign convention, one number for the whole field.
            "uniform float fieldSign;",
            // Colour UV to field UV: the field covers only the block-rounded
            // top-left of the frame.
            "uniform vec2 lumaScale;",
            "varying vec2 vUV;",

            "float maxDiff(vec3 a, vec3 b) { vec3 d = abs(a - b); return max(max(d.r, d.g), d.b); }",

            // The four blocks around a colour-space position, and their
            // raised-cosine window. The weights are a partition of unity, so
            // the blend cannot brighten or darken, and two disagreeing blocks
            // hand over smoothly instead of at a seam.
            "void window(vec2 uv, out vec2 base, out vec4 w) {",
                "vec2 grid = uv * lumaScale * vectorSize - 0.5;",
                "base = floor(grid);",
                "vec2 f = 0.5 - 0.5 * cos(PI * (grid - base));",
                "w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y),",
                         "(1.0 - f.x) * f.y, f.x * f.y);",
            "}",
            "void readForward(vec2 uv, out vec4 w, out vec2 a, out vec2 b, out vec2 c, out vec2 d) {",
                "vec2 texel = 1.0 / vectorSize;",
                "vec2 base;",
                "window(uv, base, w);",
                "vec2 scale = motionScale * fieldSign;",
                "a = texture2D(motionTexture, (base + vec2(0.5, 0.5)) * texel).rg * scale;",
                "b = texture2D(motionTexture, (base + vec2(1.5, 0.5)) * texel).rg * scale;",
                "c = texture2D(motionTexture, (base + vec2(0.5, 1.5)) * texel).rg * scale;",
                "d = texture2D(motionTexture, (base + vec2(1.5, 1.5)) * texel).rg * scale;",
            "}",
            "vec2 forwardAt(vec2 uv) {",
                "vec4 w; vec2 a, b, c, d;",
                "readForward(uv, w, a, b, c, d);",
                "return w.x * a + w.y * b + w.z * c + w.w * d;",
            "}",
            // The backward field under the same window, so the two can be
            // subtracted without half the local block disagreement leaking in.
            "vec2 backwardAt(vec2 uv) {",
                "vec2 texel = 1.0 / vectorSize;",
                "vec2 base; vec4 w;",
                "window(uv, base, w);",
                "return (w.x * texture2D(motionTexture, (base + vec2(0.5, 0.5)) * texel).ba",
                      "+ w.y * texture2D(motionTexture, (base + vec2(1.5, 0.5)) * texel).ba",
                      "+ w.z * texture2D(motionTexture, (base + vec2(0.5, 1.5)) * texel).ba",
                      "+ w.w * texture2D(motionTexture, (base + vec2(1.5, 1.5)) * texel).ba)",
                      "* motionScale * fieldSign;",
            "}",

            // One bilateral prediction along `v`, and how far its two endpoints
            // disagree. Clamped, not wrapped: the far side of the screen is a
            // louder wrong answer than a repeated border.
            "vec3 predict(vec2 v, float wOlder, float wNewer, out float fit) {",
                "vec3 older = texture2D(previousTexture, clamp(vUV + v * phase, 0.0, 1.0)).rgb;",
                "vec3 newer = texture2D(screenTexture, clamp(vUV - v * (1.0 - phase), 0.0, 1.0)).rgb;",
                "fit = maxDiff(older, newer);",
                "return (older * wOlder + newer * wNewer) / max(wOlder + wNewer, 1.0e-4);",
            "}",

            "void main() {",
                // ---- the field, read where this pixel's content sits in N --
                // The forward field is indexed on N. One fixed-point step from
                // the vector at vUV lands on the N site of the content passing
                // through vUV at this phase, and everything below reads the
                // field there.
                "vec2 mean = forwardAt(vUV);",
                "vec2 p = clamp(vUV - mean * (1.0 - phase), 0.0, 1.0);",
                "vec4 weight; vec2 m0, m1, m2, m3;",
                "readForward(p, weight, m0, m1, m2, m3);",
                "mean = weight.x * m0 + weight.y * m1 + weight.z * m2 + weight.w * m3;",

                // ---- the mean prediction's two endpoints, and both frames at
                // each. Scored on colour: two surfaces of equal luma and
                // different colour read as unchanged to a luma test.
                "vec3 hereN = texture2D(screenTexture, vUV).rgb;",
                "vec3 hereO = texture2D(previousTexture, vUV).rgb;",
                "float fitStill = maxDiff(hereN, hereO);",
                "vec2 mnRaw = vUV - mean * (1.0 - phase);",
                "vec2 moRaw = vUV + mean * phase;",
                "vec2 mn = clamp(mnRaw, 0.0, 1.0);",
                "vec2 mo = clamp(moRaw, 0.0, 1.0);",
                "vec3 atMnN = texture2D(screenTexture, mn).rgb;",
                "vec3 atMoO = texture2D(previousTexture, mo).rgb;",
                "vec3 atMnO = texture2D(previousTexture, mn).rgb;",
                "vec3 atMoN = texture2D(screenTexture, mo).rgb;",

                // How far inside the frame each fetch landed, in pixels, faded
                // over 24. A fetch that left the frame reads the border texel,
                // which is evidence of nothing.
                "vec2 edgeNewer = min(mnRaw, 1.0 - mnRaw) / motionScale;",
                "vec2 edgeOlder = min(moRaw, 1.0 - moRaw) / motionScale;",
                "float onNewer = smoothstep(0.0, 24.0, min(edgeNewer.x, edgeNewer.y));",
                "float onOlder = smoothstep(0.0, 24.0, min(edgeOlder.x, edgeOlder.y));",

                // ---- overlay or motion? ------------------------------------
                // If the two frames agree at this pixel without compensation
                // better than they agree along the vector, this pixel is
                // painted on the screen and did not move. A tie falls to
                // motion, which is right everywhere but on an overlay.
                "float fitMoving = maxDiff(atMnN, atMoO) * onNewer * onOlder;",
                "float ratio = fitStill / (fitStill + fitMoving + 1.0 / 2550.0);",
                "float carry = smoothstep(0.3, 0.7, ratio);",
                // A moving pixel whose fetch site holds still content is
                // reading an overlay from the other frame.
                "float stillAtNewer = 1.0 - smoothstep(2.0 / 255.0, 12.0 / 255.0, maxDiff(atMnN, atMnO));",
                "float stillAtOlder = 1.0 - smoothstep(2.0 / 255.0, 12.0 / 255.0, maxDiff(atMoN, atMoO));",
                "float moves = smoothstep(2.0 / 255.0, 12.0 / 255.0, fitStill);",

                // ---- which frame can see this pixel -------------------------
                // Forward at the N site says mn corresponds to mo. Backward at
                // mo, measured independently, says mo corresponds to mo + b.
                // Where both frames see the surface, b = -mean and eOlder is
                // zero; the forward field at mo + b then points back by -b and
                // eNewer is zero. The side whose own round trip fails is the
                // frame that cannot see the surface, and is dropped.
                "vec2 bAtOlder = backwardAt(mo);",
                "vec2 fRound = forwardAt(clamp(moRaw + bAtOlder, 0.0, 1.0));",
                "float eOlder = length((bAtOlder + mean) / motionScale);",
                "float eNewer = length((fRound + bAtOlder) / motionScale);",
                // Tolerance: a 1.5 px instrument floor for a whole-pixel block
                // matcher, plus a term proportional to the flow, since two
                // independent fields disagree more the faster the scene moves.
                "float magBack  = length(bAtOlder / motionScale);",
                "float magOlder = length(mean / motionScale);",
                "float magNewer = length(fRound / motionScale);",
                "float tolOlder = sqrt(0.01 * (magOlder * magOlder + magBack * magBack) + 2.25);",
                "float tolNewer = sqrt(0.01 * (magNewer * magNewer + magBack * magBack) + 2.25);",
                "float occNewer = smoothstep(tolNewer, tolNewer * 4.0, eNewer) * onNewer;",
                "float occOlder = smoothstep(tolOlder, tolOlder * 4.0, eOlder) * onOlder;",

                "float wOlder = (1.0 - phase) * (1.0 - stillAtOlder * moves) * (1.0 - occOlder) * onOlder;",
                "float wNewer = phase * (1.0 - stillAtNewer * moves) * (1.0 - occNewer) * onNewer;",
                // Both refused is a field not worth believing, not an
                // occlusion: fall back to the plain mix.
                "if (wOlder + wNewer < 1.0e-3) { wOlder = 1.0 - phase; wNewer = phase; }",

                // ---- overlapped block motion compensation --------------------
                // Where the four blocks agree the mean prediction IS the answer
                // and its endpoints are already fetched. Otherwise four
                // predictions, each weighted by the window over its endpoint
                // disagreement: a block whose vector fetched the wall for a
                // pixel on the cabinet disagrees with itself by the contrast
                // of the edge. Floored at four 8-bit steps so the weighting
                // does not swing on noise.
                "vec3 shown;",
                "vec2 spread = abs(m1 - m0) + abs(m2 - m0) + abs(m3 - m0);",
                "if (spread.x + spread.y < 1.0e-7) {",
                    "shown = (atMoO * wOlder + atMnN * wNewer) / max(wOlder + wNewer, 1.0e-4);",
                "} else {",
                    "vec4 fit;",
                    "vec3 q0 = predict(m0, wOlder, wNewer, fit.x);",
                    "vec3 q1 = predict(m1, wOlder, wNewer, fit.y);",
                    "vec3 q2 = predict(m2, wOlder, wNewer, fit.z);",
                    "vec3 q3 = predict(m3, wOlder, wNewer, fit.w);",
                    "vec4 wf = weight / (fit + 4.0 / 255.0);",
                    "wf /= dot(wf, vec4(1.0));",
                    "shown = wf.x * q0 + wf.y * q1 + wf.z * q2 + wf.w * q3;",
                "}",

                // What a stationary thing looks like now: exact for an overlay.
                "vec3 still = mix(hereO, hereN, phase);",
                "shown = mix(still, shown, carry);",

                "if (diagnostic > 0.5) {",
                    // Anchored to the two real frames only: how far the result
                    // sits from N against how far N-1 already was, over pixels
                    // that changed; and how much of the result came from
                    // outside the frame. See FrameSynthesizer.measure.
                    "float dSynth = length(shown - hereN);",
                    "float dBase  = length(hereO - hereN);",
                    "float changed = step(0.008, dBase);",
                    "vec2 outNewer = vUV - mean * (1.0 - phase);",
                    "vec2 outOlder = vUV + mean * phase;",
                    "float escNewer = min(1.0, step(outNewer.x, 0.0) + step(1.0, outNewer.x)",
                                            "+ step(outNewer.y, 0.0) + step(1.0, outNewer.y));",
                    "float escOlder = min(1.0, step(outOlder.x, 0.0) + step(1.0, outOlder.x)",
                                            "+ step(outOlder.y, 0.0) + step(1.0, outOlder.y));",
                    "float escaped = phase * escNewer + (1.0 - phase) * escOlder;",
                    "gl_FragColor = vec4(changed * min(dSynth, 1.0), escaped,",
                                        "changed * min(dBase, 1.0), changed);",
                    "return;",
                "}",

                "if (mark > 0.5 && vUV.x < 16.0 * motionScale.x && vUV.y < 16.0 * motionScale.y) {",
                    "gl_FragColor = vec4(1.0, 0.0, 1.0, 1.0);",
                    "return;",
                "}",
                "gl_FragColor = vec4(shown, 1.0);",
            "}"
        );
    }
}
