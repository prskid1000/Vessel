package com.winlator.renderer.material;

/**
 * VESSEL: a frame built between two real ones, rather than past the newer.
 *
 * <h2>Why this can be almost right where extrapolation cannot</h2>
 *
 * <p>Extrapolation is asked an unanswerable question. Given frames N-1 and N it
 * must invent what comes after N, and the information for that is in neither
 * input: what is behind an object about to move, what a counter is about to read.
 * No filtering recovers it, because it was never observed -- which is why a
 * median and a consistency test both failed to fix the picture. They were
 * treating a symptom of the method.
 *
 * <p>Interpolation is asked an answerable one. Both endpoints are known, so a
 * frame between them blends two facts rather than guessing past one. A region
 * uncovered during the interval is invisible in N-1 and plainly visible in N, so
 * the very thing that defeats extrapolation is here for the asking.
 *
 * <p>The price is latency, and it is structural rather than a tuning parameter:
 * N cannot be shown until the frames belonging before it have been.
 *
 * <h2>The geometry</h2>
 *
 * <p>Write {@code b} for the backward field measured on N: for a point at
 * {@code p} in N, the offset to where that same content sat in N-1. Travelling
 * linearly, at phase {@code t} it is at {@code p + b(1 - t)}. So whatever the
 * interpolated frame shows at {@code q} came from {@code q - b(1 - t)} in N and
 * {@code q + bt} in N-1. That is the same derivation FidelityFX uses, sampling
 * the previous backbuffer at {@code uv + mv} and the current at {@code uv - mv}.
 *
 * <h2>Four things this does that a single bilinear fetch does not</h2>
 *
 * <p><b>1. It picks the sign per pixel instead of trusting one global answer.</b>
 * {@code QCOM_motion_estimation} says its output is "the estimated motion in
 * pixels ... from the <ref> texture to the <target> texture" and never says
 * which image the vector is based at -- and the mask wording ("it is possible
 * for an unmasked block to produce a vector that lands in the masked block")
 * reads as a forward field based on the reference, which is the opposite of what
 * a reconstruction test on this device concluded. That test was not conclusive:
 * against an uncompensated error of 0.00248 the two hypotheses scored 0.00192
 * and 0.00182, so the winner won by five per cent while both explained barely a
 * quarter of the difference between the frames. A correct compensation should
 * crush the baseline and the wrong sign should score *worse* than it.
 *
 * <p>So the question is not answered globally at all. Both signs are scored at
 * every pixel and the better one is used. Where motion is real the wrong sign
 * displaces by twice the true motion and loses decisively; where motion is zero
 * the two are identical and the choice does not matter. The global question
 * simply stops existing.
 *
 * <p><b>2. It projects the field to this instant before reading it.</b> Sampling
 * {@code b} at {@code q} asks for the motion of whatever is at {@code q} in
 * frame N -- but at phase {@code t} the thing at {@code q} is not that. Near a
 * moving object's leading edge {@code q} still holds background, so the
 * background's vector is applied to the object; at the trailing edge the reverse.
 * That is the edge tearing, and it is why FSR3 spends three whole passes
 * building its motion field *at the interpolated instant* rather than sampling
 * the one it was given.
 *
 * <p>Scattering into the intermediate frame needs atomics. The gather-side
 * equivalent is a fixed point: {@code p <- q - b(p)(1 - t)}, iterated. Two
 * rounds is enough for the displacements a real interval contains, and each
 * round is one fetch from a texture 64 times smaller than the frame, which is
 * resident in cache after the first.
 *
 * <p><b>3. It chooses between neighbouring blocks instead of averaging them.</b>
 * Bilinear filtering of a per-block field invents a vector between two blocks
 * that disagree, and at a motion boundary -- the edge of every moving object --
 * that invented vector describes nothing in the scene. FidelityFX does not do
 * this either: its optical flow upscales by taking the best of four candidates
 * under SAD rather than by interpolating. Here the four block vectors around the
 * projected point are each scored against the image and the winner is used, with
 * the smooth bilinear answer as a fifth candidate holding a small advantage so
 * that ties, and flat regions where the score is noise, keep the smooth field.
 *
 * <p>This is also what makes a median filter unnecessary and what disambiguates
 * a zero. The spec sets R and G to zero "if no motion is detected for a block",
 * which is indistinguishable from a block the matcher gave up on -- and giving
 * up is most likely exactly where motion is fast. A zero that fails to explain
 * the pixel now loses to a neighbour that does.
 *
 * <p><b>4. It falls back to the nearer real frame in time.</b> Where the two
 * warped samples disagree the correspondence is wrong, and no displacement of
 * either frame is trustworthy. The old code fell back to N always, which at
 * phase 0.25 shows content three quarters of an interval early. The nearer
 * endpoint, undisplaced, is off by at most half an interval and is a real frame
 * rather than a warped one: a moment of judder in that region, which is the
 * honest failure, instead of a smear.
 *
 * <p>All the scoring is done on the R8 luma pair the block matcher already
 * requires, so the search costs single-byte fetches from a texture that is one
 * quarter the bandwidth of the colour, and the expensive RGBA reads happen once
 * with the winning vector.
 */
public class InterpolateMaterial extends ScreenMaterial {
    public final Uniforms interpolateUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform previousTexture = new Uniform("previousTexture");
        public final Uniform motionTexture = new Uniform("motionTexture");
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        public final Uniform motionScale = new Uniform("motionScale");
        public final Uniform vectorSize = new Uniform("vectorSize");
        public final Uniform phase = new Uniform("phase");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // Below this the two warped samples agree well enough to trust the
            // blend completely; above the second, not at all. Between them the
            // frame fades back rather than switching, so a region crossing the
            // threshold does not pop.
            "#define AGREE_FULL 0.12",
            "#define AGREE_NONE 0.40",

            // How much better a neighbouring block must explain this pixel before
            // it displaces the smooth field. Without it the winner in a flat
            // region is decided by sensor-level noise and flips every frame,
            // which is a shimmer along every soft edge. With it the smooth answer
            // holds unless a neighbour is materially right.
            "#define NEIGHBOUR_PENALTY 0.02",

            // The newer of the two real frames, N.
            "uniform sampler2D screenTexture;",
            // The older one, N-1.
            "uniform sampler2D previousTexture;",
            // One vector per search block, in luma pixels, RG.
            "uniform sampler2D motionTexture;",
            // The same two frames as luma, which is what the search runs on.
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            // One field unit in texture space: 1 / luma size.
            "uniform vec2 motionScale;",
            // Dimensions of the vector texture, for addressing single blocks.
            "uniform vec2 vectorSize;",
            // Where between the two frames this one sits: 0 is N-1, 1 is N.
            "uniform float phase;",

            "varying vec2 vUV;",

            // How badly a displacement fails to explain this pixel. Clamped
            // rather than wrapped: a vector pointing off the edge has no source,
            // and repeating the border smears, where wrapping would fetch the
            // opposite side of the screen -- a much louder wrong answer.
            "float cost(vec2 b) {",
                "vec2 fromNewer = clamp(vUV - b * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + b * phase, 0.0, 1.0);",
                "return abs(texture2D(lumaNewerTexture, fromNewer).r",
                         "- texture2D(lumaOlderTexture, fromOlder).r);",
            "}",

            "vec2 fieldAt(vec2 uv, float direction) {",
                "return texture2D(motionTexture, uv).rg * motionScale * direction;",
            "}",

            "void main() {",
                // 1. The smooth field, and which way it points. See the class
                //    comment: this is measured here rather than assumed anywhere.
                "vec2 raw = texture2D(motionTexture, vUV).rg * motionScale;",
                "float direction = cost(raw) <= cost(-raw) ? 1.0 : -1.0;",

                // 2. Walk the field back to where this instant's content sits in
                //    frame N, so the vector read belongs to the right object.
                "vec2 p = clamp(vUV - raw * direction * (1.0 - phase), 0.0, 1.0);",
                "p = clamp(vUV - fieldAt(p, direction) * (1.0 - phase), 0.0, 1.0);",

                // 3. The smooth field at the projected point, then each of the
                //    four blocks around it, scored against the image.
                "vec2 best = fieldAt(p, direction);",
                "float bestCost = cost(best);",

                "vec2 texel = 1.0 / vectorSize;",
                "vec2 base = (floor(p * vectorSize - 0.5) + 0.5) * texel;",

                "vec2 candidate; float score;",
                "candidate = fieldAt(base, direction);",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",
                "candidate = fieldAt(base + vec2(texel.x, 0.0), direction);",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",
                "candidate = fieldAt(base + vec2(0.0, texel.y), direction);",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",
                "candidate = fieldAt(base + texel, direction);",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",

                // 4. One colour fetch each with the winner, blended by phase so
                //    each endpoint is trusted most where it is most likely right.
                //    A region uncovered during the interval is missing from one
                //    and present in the other, which is the disocclusion handling
                //    and why there is no separate hole-filling pass.
                "vec2 fromNewer = clamp(vUV - best * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + best * phase, 0.0, 1.0);",
                "vec3 newer = texture2D(screenTexture, fromNewer).rgb;",
                "vec3 older = texture2D(previousTexture, fromOlder).rgb;",

                // Judged on colour, not on the luma the search used: two
                // different objects of equal brightness pass the search and are
                // caught here. Judged per pixel rather than by a whole-frame
                // scene-change test, because a global one needs the difference
                // summed on the GPU and read back to the CPU every frame -- a
                // pipeline stall in the middle of the thing that exists to make
                // frames smoother -- and it would average away the single wrong
                // block that this catches.
                "float disagreement = length(newer - older);",
                "float confidence = 1.0 - smoothstep(AGREE_FULL, AGREE_NONE, disagreement);",
                "vec3 blended = mix(older, newer, phase);",

                "vec3 nearest = phase < 0.5",
                    "? texture2D(previousTexture, vUV).rgb",
                    ": texture2D(screenTexture, vUV).rgb;",

                "gl_FragColor = vec4(mix(nearest, blended, confidence), 1.0);",
            "}"
        );
    }
}
