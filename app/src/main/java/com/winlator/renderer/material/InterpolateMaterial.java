package com.winlator.renderer.material;

/**
 * VESSEL: a frame built between two real ones, rather than past the newer.
 *
 * <h2>Why this can be almost right where extrapolation cannot</h2>
 *
 * <p>Extrapolation is asked an unanswerable question. Given frames N-1 and N it
 * must invent what comes after N, and the information for that is in neither
 * input: what is behind an object about to move, what a counter is about to read.
 * No filtering recovers it, because it was never observed.
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
 * <p>Sampling symmetrically about the output pixel like this is bilateral motion
 * compensation, and it is what makes holes and overlaps impossible: every output
 * pixel is written exactly once by construction, so there is no scatter to leave
 * gaps and no second writer to fight with. It is also why there is no inpainting
 * pass here where FSR3 has one.
 *
 * <h2>How much of FSR3 this is, and where it deliberately is not</h2>
 *
 * <p>Half of FSR3's frame interpolation is unavailable to a compositor by
 * construction. It takes motion vectors from the game engine, and it derives
 * disocclusion from the depth buffer -- neither of which exists on this side of
 * the swapchain. What is left, and what is taken from it: the bilateral geometry
 * above, and a median filter over the motion field (see {@link MedianMaterial}).
 * This is structurally in the class of AMD's Fluid Motion Frames and NVIDIA's
 * Smooth Motion, which infer everything from final images, rather than in FSR3's.
 *
 * <p>One divergence is deliberate. FSR3's optical flow picks the best of four
 * candidate vectors under SAD, and this shader used to do the same. That works at
 * FSR3's operating point -- roughly 60 to 120 fps, so about 16 ms between real
 * frames. Ours is 66 ms, four times wider, and at that gap the score cannot
 * discriminate: displacing the chosen vector by two whole search blocks changes
 * the cost by less than the margin across 73-95% of the frame. Best-of-N then
 * degenerates into an arbitrary pick, warped at full strength.
 *
 * <h2>Why this no longer picks a vector</h2>
 *
 * <p><b>It used to, and that was the bug.</b> The previous version scored five
 * candidate vectors against the luma pair, took the winner, warped fully by it,
 * and fell back to the nearest real frame wherever a confidence test failed.
 * Every part of that turned out to be wrong, and each was measured rather than
 * argued:
 *
 * <ul>
 * <li><b>The score cannot pick a winner.</b> The scene translates 64 pixels or
 *     more across a 66 ms gap, and an 8x8 block moved that far through a
 *     repetitive corridor matches in dozens of places equally well.
 * <li><b>Every "is the winner good?" test passed while the picture was wrong.</b>
 *     100% trusted, 0.24% mean residual, neighbours in agreement, sign stable.
 *     They all pass because the winner really is good -- it is simply one of
 *     thousands of equally good answers, and which one came back is arbitrary.
 * <li><b>The fallback was worse than the fault.</b> Dropping to the nearest real
 *     frame makes that region stop advancing, so parts of the picture run at half
 *     the rate of the rest. It reads as objects dragging, and a spatially varying
 *     frame rate is a worse artefact than the one it was hiding.
 * </ul>
 *
 * <p>So the shader stops choosing. Instead of committing to one vector it forms
 * the prediction from <em>all four</em> block vectors around the pixel and blends
 * them under overlapping raised-cosine windows. This is overlapped block motion
 * compensation, the standard answer in the frame-rate-up-conversion literature to
 * exactly this failure: even where two neighbouring blocks disagree, the overlap
 * between their windows removes the discontinuity between their predictions.
 *
 * <p>What that buys, concretely:
 *
 * <ul>
 * <li>Where the four blocks agree -- most of any frame -- the four predictions
 *     coincide and the result is identical to a single fetch. OBMC costs nothing
 *     in quality where nothing is contentious.
 * <li>Where they disagree, a wrong vector contributes a partial, soft ghost
 *     weighted by how far the pixel sits from its block, instead of a hard
 *     full-strength displacement. The artefact degrades into slight softness
 *     rather than into visible distortion.
 * <li>There is one code path. No threshold, no confidence, no fallback, nothing
 *     that can pop as a region crosses it, and no part of the frame running at a
 *     different rate from any other part.
 * </ul>
 *
 * <p><b>There are no tuned constants left.</b> The window is
 * {@code 0.5 - 0.5 cos(pi f)} of the pixel's position inside the block grid --
 * geometry, derived from the block size the driver reports, with nothing fitted
 * to any scene. Every scene-fitted number the old version carried (an agreement
 * band, a neighbour penalty, a darkness threshold, a surround radius, a temporal
 * cap) is gone along with the machinery that needed it.
 *
 * <h2>The two things it still does per pixel</h2>
 *
 * <p><b>It resolves the field's sign.</b> {@code QCOM_motion_estimation} says its
 * output is "the estimated motion in pixels ... from the &lt;ref&gt; texture to
 * the &lt;target&gt; texture" and never says which image the vector is based at,
 * and a reconstruction test on this device could not separate the two hypotheses:
 * against an uncompensated error of 0.00248 they scored 0.00192 and 0.00182, so
 * the winner won by five per cent while both explained barely a quarter of the
 * difference between the frames. Rather than assume, both signs are scored
 * against the luma pair using the <em>blended</em> vector and the better one is
 * applied to all four blocks. The sign is a property of the field, so it is asked
 * once rather than per candidate; measured across a moving scene the answer is
 * consistent to within 0.4% of pixels, so this settles a convention rather than
 * making a choice.
 *
 * <p><b>It projects the field to this instant before reading it.</b> Sampling
 * {@code b} at {@code q} asks for the motion of whatever is at {@code q} in frame
 * N -- but at phase {@code t} the thing at {@code q} is not that. Near a moving
 * object's leading edge {@code q} still holds background, so the background's
 * vector gets applied to the object; at the trailing edge the reverse. That is
 * edge tearing, and it is why FSR3 spends three passes building its field at the
 * interpolated instant rather than sampling the one it was given. One fixed-point
 * step, {@code p <- q - b(1 - t)}, moves the read to where this instant's content
 * actually came from. The field is one vector per 8x8 block, 64 times smaller
 * than the frame and resident in cache, so the extra reads are close to free and
 * the colour fetches are the only real cost.
 *
 * @see MedianMaterial for why the field is filtered before it reaches here, and
 *     why sampling it bilinearly was hiding the very fault it removes.
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
        /** VESSEL: 1 makes this pass report on itself instead of drawing. */
        public final Uniform diagnostic = new Uniform("diagnostic");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            "#define PI 3.14159265",

            // The newer of the two real frames, N.
            "uniform sampler2D screenTexture;",
            // The older one, N-1.
            "uniform sampler2D previousTexture;",
            // One vector per search block, in luma pixels, RG. Sampled with
            // GL_NEAREST: the four blocks are wanted as the four answers they
            // are, and the windows below do the spatial blending. Letting the
            // sampler interpolate as well would blend twice, and would invent a
            // fifth vector that no block ever voted for.
            "uniform sampler2D motionTexture;",
            // The same two frames as luma, used only to settle the field's sign.
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            // One field unit in texture space: 1 / luma size.
            "uniform vec2 motionScale;",
            // Dimensions of the vector texture, which is the block grid.
            "uniform vec2 vectorSize;",
            // Where between the two frames this one sits: 0 is N-1, 1 is N.
            "uniform float phase;",

            // VESSEL: when 1, write four measurements instead of a picture.
            "uniform float diagnostic;",

            "varying vec2 vUV;",

            "float max3(vec3 c) { return max(max(c.r, c.g), c.b); }",

            // How badly a displacement fails to explain this pixel. Used once,
            // for the sign, and for nothing else -- there is no candidate search
            // any more, so this no longer decides what the frame looks like.
            "float cost(vec2 b) {",
                "vec2 fromNewer = clamp(vUV - b * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + b * phase, 0.0, 1.0);",
                "return abs(texture2D(lumaNewerTexture, fromNewer).r",
                         "- texture2D(lumaOlderTexture, fromOlder).r);",
            "}",

            // One bilateral prediction: both endpoints displaced symmetrically
            // about this pixel and blended by phase, so each is trusted most
            // where it is most likely to be right. Clamped rather than wrapped: a
            // vector pointing off the edge has no source, and repeating the
            // border smears where wrapping would fetch the opposite side of the
            // screen -- a much louder wrong answer.
            "vec3 predict(vec2 v) {",
                "vec2 fromNewer = clamp(vUV - v * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + v * phase, 0.0, 1.0);",
                "return mix(texture2D(previousTexture, fromOlder).rgb,",
                           "texture2D(screenTexture, fromNewer).rgb, phase);",
            "}",

            "void main() {",
                // ---- the block grid this pixel sits in -----------------------
                // Half a texel back, so `base` is the block up-and-left of the
                // pixel and the fraction runs 0..1 from that block's centre to
                // the next one's.
                "vec2 texel = 1.0 / vectorSize;",
                "vec2 grid = vUV * vectorSize - 0.5;",
                "vec2 base = floor(grid);",

                // **The overlapped window.** A raised cosine of the position
                // between block centres. The four weights sum to one everywhere,
                // so this is a partition of unity and cannot brighten or darken
                // the picture; and because the window flattens at both ends, two
                // blocks that disagree hand over to each other smoothly instead
                // of at a seam. That smooth hand-over is the whole mechanism --
                // it is what turns a wrong block into a soft ghost rather than a
                // hard displacement.
                "vec2 w = 0.5 - 0.5 * cos(PI * (grid - base));",
                "vec4 weight = vec4((1.0 - w.x) * (1.0 - w.y),",
                                   "w.x * (1.0 - w.y),",
                                   "(1.0 - w.x) * w.y,",
                                   "w.x * w.y);",

                "vec2 m0 = texture2D(motionTexture, (base + vec2(0.5, 0.5)) * texel).rg * motionScale;",
                "vec2 m1 = texture2D(motionTexture, (base + vec2(1.5, 0.5)) * texel).rg * motionScale;",
                "vec2 m2 = texture2D(motionTexture, (base + vec2(0.5, 1.5)) * texel).rg * motionScale;",
                "vec2 m3 = texture2D(motionTexture, (base + vec2(1.5, 1.5)) * texel).rg * motionScale;",

                // ---- which way the field points ------------------------------
                // Asked once, of the blended vector, because the sign is a
                // property of the field rather than of any one block.
                "vec2 mean = weight.x * m0 + weight.y * m1",
                          "+ weight.z * m2 + weight.w * m3;",
                "float direction = cost(mean) <= cost(-mean) ? 1.0 : -1.0;",

                // ---- read the field where this instant's content is ----------
                // See the class comment: sampling at vUV asks about whatever sits
                // there in frame N, which near a moving edge is the wrong object.
                "vec2 p = clamp(vUV - mean * direction * (1.0 - phase), 0.0, 1.0);",
                "vec2 pgrid = p * vectorSize - 0.5;",
                "vec2 pbase = floor(pgrid);",
                "vec2 pw = 0.5 - 0.5 * cos(PI * (pgrid - pbase));",
                "weight = vec4((1.0 - pw.x) * (1.0 - pw.y),",
                              "pw.x * (1.0 - pw.y),",
                              "(1.0 - pw.x) * pw.y,",
                              "pw.x * pw.y);",

                "m0 = texture2D(motionTexture, (pbase + vec2(0.5, 0.5)) * texel).rg * motionScale * direction;",
                "m1 = texture2D(motionTexture, (pbase + vec2(1.5, 0.5)) * texel).rg * motionScale * direction;",
                "m2 = texture2D(motionTexture, (pbase + vec2(0.5, 1.5)) * texel).rg * motionScale * direction;",
                "m3 = texture2D(motionTexture, (pbase + vec2(1.5, 1.5)) * texel).rg * motionScale * direction;",

                // ---- overlapped block motion compensation --------------------
                // Four predictions, one per block, combined under the window.
                // Nothing is discarded and nothing is chosen.
                "vec3 q0 = predict(m0);",
                "vec3 q1 = predict(m1);",
                "vec3 q2 = predict(m2);",
                "vec3 q3 = predict(m3);",
                "vec3 shown = weight.x * q0 + weight.y * q1",
                            "+ weight.z * q2 + weight.w * q3;",

                "if (diagnostic > 0.5) {",
                    // How far apart the four blocks' answers were. This is the
                    // number that says where OBMC is doing work: zero means the
                    // blocks agreed and the blend was a no-op, large means they
                    // disagreed and the old winner-take-all would have committed
                    // to one of them at full strength.
                    "float spread = max(max(length(q0 - shown), length(q1 - shown)),",
                                       "max(length(q2 - shown), length(q3 - shown)));",

                    // Everything below is scored against the scene rather than
                    // against a fixed level, so a reading means the same thing in
                    // a dark corridor and in daylight.
                    "vec3 here = texture2D(screenTexture, vUV).rgb;",
                    "float outLuma = max3(shown);",

                    // A dot is a pixel far darker than everything around it. The
                    // ring is read from the real frame undisplaced, because the
                    // question is what belongs here rather than what the field
                    // claims moved here, and all four must be lit -- a pixel on
                    // the dark side of an ordinary edge has a dark neighbour and
                    // that is not a fault. Six pixels out, because a wrongly
                    // matched 8x8 block makes a patch about that wide and a
                    // one-texel ring read 0.0000% while the screen plainly had
                    // dots on it.
                    "vec2 rx = vec2(6.0 * motionScale.x, 0.0);",
                    "vec2 ry = vec2(0.0, 6.0 * motionScale.y);",
                    "float s0 = max3(texture2D(screenTexture, clamp(vUV + rx, 0.0, 1.0)).rgb);",
                    "float s1 = max3(texture2D(screenTexture, clamp(vUV - rx, 0.0, 1.0)).rgb);",
                    "float s2 = max3(texture2D(screenTexture, clamp(vUV + ry, 0.0, 1.0)).rgb);",
                    "float s3 = max3(texture2D(screenTexture, clamp(vUV - ry, 0.0, 1.0)).rgb);",
                    "float dimmest = min(min(s0, s1), min(s2, s3));",

                    // How far apart the four block vectors were, as a share of
                    // how far they were pointing -- relative, so it reads the
                    // same whether the camera is drifting or whipping round.
                    "vec2 agreed = mean * direction;",
                    "float vspread = max(max(length(m0 - agreed), length(m1 - agreed)),",
                                        "max(length(m2 - agreed), length(m3 - agreed)));",

                    "gl_FragColor = vec4(",
                        // R: how much of the frame OBMC actually had to blend --
                        // where the four blocks predicted materially different
                        // pictures, judged against the brightness of the pixel
                        // itself rather than against a fixed level.
                        "step(0.25, spread / max(outLuma, 0.02)),",
                        // G: a dot this shader produced. Relative now: darker than
                        // a quarter of its dimmest lit neighbour. The old
                        // absolute-threshold version is what proved these were
                        // real; this is the same test without the constant.
                        "step(0.05, dimmest) * step(outLuma, 0.25 * dimmest),",
                        // B: how contentious the block vectors were here, which is
                        // where a winner-take-all search would have been guessing.
                        "step(0.5, vspread / max(length(agreed), 0.5 * motionScale.x)),",
                        // A: how far the result departs from simply showing the
                        // newer frame, which is how much work interpolation did.
                        "clamp(length(shown - here) * 4.0, 0.0, 1.0));",
                    "return;",
                "}",

                "gl_FragColor = vec4(shown, 1.0);",
            "}"
        );
    }
}
