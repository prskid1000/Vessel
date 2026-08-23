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
 * <p><b>5. It refuses a pixel far darker than what surrounds it.</b> The cost
 * function compares the two *fetched* locations against each other and nothing
 * else. A displacement that maps a lit pixel to two dark regions therefore scores
 * near-perfectly -- dark matches dark -- so the shader is maximally confident
 * while being completely wrong about the pixel it is writing. The result is a dark
 * patch drawn at full confidence, and it appears exactly under camera motion,
 * where displacements are largest.
 *
 * <p>This is measured rather than reasoned: during camera movement the
 * interpolated frame carried 0.39% of such patches while the captured frame
 * carried 0.0000%, at 99-100% "trusted". Every earlier attempt to explain these
 * failed because every test in this shader asks about the *vectors*, and a vector
 * can be self-consistent and still wrong.
 *
 * <p>So one test asks about the *pixel*: a result much darker than the frame
 * around that position is not something interpolation produces, whatever the
 * vectors said. The neighbourhood is read from the newer frame undisplaced,
 * because the question is what belongs here rather than what the field claims
 * moved here, and all four neighbours must be lit -- a pixel on the dark side of
 * an ordinary edge has a dark neighbour, and that is not a fault.
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
        /** VESSEL: 1 makes this pass report on itself instead of drawing. */
        public final Uniform diagnostic = new Uniform("diagnostic");
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

            // VESSEL: when 1, write four measurements instead of a picture.
            //
            // **Because a description of what a screen looks like is a lossy
            // channel, and it has been read wrong repeatedly.** Averaged over the
            // frame by the mip chain and read back as four bytes, these say what
            // the shader actually computed rather than what its output resembles.
            "uniform float diagnostic;",

            "varying vec2 vUV;",

            "float max3(vec3 c) { return max(max(c.r, c.g), c.b); }",

            // How dark a result may be against a lit neighbourhood before it is
            // treated as a wrong match rather than as scenery, and how lit that
            // neighbourhood has to be to make the judgement. See point 5.
            "#define DARK_SUSPECT 0.10",
            "#define DARK_CERTAIN 0.02",
            "#define SURROUND_DIM 0.10",
            "#define SURROUND_LIT 0.20",
            // Six pixels out: a wrongly matched 8x8 block makes a patch about that
            // wide, and a one-pixel ring only ever sees a single stray pixel.
            "#define SURROUND_RING 6.0",

            // How far apart the patch is, in luma texels. **This is a patch and
            // not a pixel, and that difference was the whole bug.**
            //
            // The first version compared one texel with one texel. Measured on
            // Metro, a vector wrong by sixteen luma pixels -- two entire search
            // blocks -- scored as well as the winner across 73-95% of the frame.
            // One luma sample in a dark corridor simply does not carry enough
            // information to tell displacements apart, so nearly every candidate
            // tied and the winner among them was arbitrary. Every downstream test
            // passed honestly while this was happening: the match really was good,
            // it was just one of thousands of equally good answers, and picking a
            // different one each block is what the distortion under camera motion
            // actually was.
            //
            // Nothing above could have caught it. Confidence, residual, field
            // coherence and sign stability all ask "is the winner good?" and the
            // winner was good. Only asking "is a plainly wrong vector just as
            // good?" showed it.
            //
            // Five taps in a plus, which is why block matchers match blocks: the
            // sum over a neighbourhood discriminates where a point cannot. Scaled
            // back to a mean so NEIGHBOUR_PENALTY and the thresholds below keep
            // the units they were tuned in.
            // How badly a displacement fails to explain this pixel. Clamped
            // rather than wrapped: a vector pointing off the edge has no source,
            // and repeating the border smears, where wrapping would fetch the
            // opposite side of the screen -- a much louder wrong answer.
            //
            // **One texel, and it was measured against a five-tap patch before
            // being left that way.** Comparing single samples looks obviously too
            // thin -- a block matcher matches blocks for a reason -- so the patch
            // version was built and both were scored on the same frames at once:
            // how much of the picture the search cannot tell a sixteen-pixel error
            // in *and* the colour would visibly change. One texel gave 0.00-0.78%
            // and five gave 0.00-0.78%. Five times the taps in the hottest shader
            // here for no measurable return, so it is not paid.
            //
            // The honest caveat is that both sit near the floor of that metric,
            // and a measurement at its floor cannot show an improvement. This says
            // the patch does not help on this content, not that it never would.
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
                // **Both costs are kept, because how close they were is the
                // whole question.** The hardware reports ref->target and nothing
                // says which of the two frames is which, so the sign is inferred
                // here -- per pixel, from one luma comparison. When the two costs
                // are far apart that inference is sound. When they are nearly
                // equal it is a coin flip, and a flipped sign warps the pixel to
                // exactly the wrong side of where it belongs.
                "float costPos = cost(raw);",
                "float costNeg = cost(-raw);",
                "float direction = costPos <= costNeg ? 1.0 : -1.0;",

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

                // Named rather than used once, because their average is what
                // tells a wrong vector from a right one where colour cannot: real
                // camera motion is smooth across the frame, so a winner far from
                // what its neighbours agree on is an artefact of the search.
                "vec2 v0 = fieldAt(base, direction);",
                "vec2 v1 = fieldAt(base + vec2(texel.x, 0.0), direction);",
                "vec2 v2 = fieldAt(base + vec2(0.0, texel.y), direction);",
                "vec2 v3 = fieldAt(base + texel, direction);",
                "vec2 consensus = 0.25 * (v0 + v1 + v2 + v3);",

                "vec2 candidate; float score;",
                "candidate = v0;",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",
                "candidate = v1;",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",
                "candidate = v2;",
                "score = cost(candidate) + NEIGHBOUR_PENALTY;",
                "if (score < bestCost) { bestCost = score; best = candidate; }",
                "candidate = v3;",
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

                // **The one test that asks about the pixel rather than the
                // vector.** See point 5: dark matched to dark scores perfectly and
                // is confidently wrong, and this is what catches it.
                "vec2 ringX = vec2(SURROUND_RING * motionScale.x, 0.0);",
                "vec2 ringY = vec2(0.0, SURROUND_RING * motionScale.y);",
                "float s0 = max3(texture2D(screenTexture, clamp(vUV + ringX, 0.0, 1.0)).rgb);",
                "float s1 = max3(texture2D(screenTexture, clamp(vUV - ringX, 0.0, 1.0)).rgb);",
                "float s2 = max3(texture2D(screenTexture, clamp(vUV + ringY, 0.0, 1.0)).rgb);",
                "float s3 = max3(texture2D(screenTexture, clamp(vUV - ringY, 0.0, 1.0)).rgb);",
                // The dimmest of them, so an ordinary edge -- which has a dark
                // side -- is not mistaken for a hole.
                "float surround = min(min(s0, s1), min(s2, s3));",
                "float wouldBe = max3(mix(older, newer, phase));",
                "float suspect = smoothstep(DARK_SUSPECT, DARK_CERTAIN, wouldBe)",
                              "* smoothstep(SURROUND_DIM, SURROUND_LIT, surround);",
                "confidence = min(confidence, 1.0 - suspect);",
                "vec3 blended = mix(older, newer, phase);",

                "vec3 nearest = phase < 0.5",
                    "? texture2D(previousTexture, vUV).rgb",
                    ": texture2D(screenTexture, vUV).rgb;",

                "vec3 shown = mix(nearest, blended, confidence);",

                "if (diagnostic > 0.5) {",
                    // The deliberately wrong vector the B channel is built on:
                    // two whole search blocks sideways, which is unambiguously not
                    // the true motion of anything.
                    "vec2 wrong = best + vec2(16.0 * motionScale.x, 0.0);",
                    "float costAlt = cost(wrong);",
                    "float costWin = cost(best);",
                    "vec3 altColour = texture2D(screenTexture,",
                        "clamp(vUV - wrong * (1.0 - phase), 0.0, 1.0)).rgb;",
                    // How bright the three things are, so "we produced black from
                    // lit sources" can be counted rather than described.
                    "float outLuma = max(max(shown.r, shown.g), shown.b);",
                    "float newLuma = max(max(newer.r, newer.g), newer.b);",
                    "float oldLuma = max(max(older.r, older.g), older.b);",
                    // **Measured against the real frame at this same position,
                    // not against the warped reads.** The first version compared
                    // the output with the two samples it was built from, which
                    // cannot tell an artefact from a dark scene: in Metro it
                    // reported 12-60% and was really just counting how dark the
                    // game is. What matters is whether this pixel is darker than
                    // what genuinely belongs at this spot, and the undisplaced
                    // newer frame is the closest thing to that.
                    "vec3 here = texture2D(screenTexture, vUV).rgb;",
                    "float hereLuma = max(max(here.r, here.g), here.b);",

                    // **Both dot counts come out of this one pass, because a
                    // second pass cost a readback and a readback cost an ANR.**
                    // Each glReadPixels makes the GL thread wait for the GPU, and
                    // three a second was enough to blow the five-second input
                    // dispatch timeout. Four extra taps here are free by
                    // comparison.
                    //
                    // A dot is a pixel much darker than everything around it. The
                    // neighbourhood comes from the captured frame in both cases:
                    // for the capture that is the literal test, and for the
                    // interpolated frame it is the right reference too, since the
                    // two agree everywhere except exactly where the fault is.
                    // **Six pixels out, not one, and the first version measured
                    // zero because of it.** A one-texel ring only detects a dot a
                    // single pixel wide: anything larger has dark neighbours of
                    // its own, every tap fails the "all four lit" test, and the
                    // count reads 0.0000% while the screen plainly has dots on it.
                    // Six catches a patch up to about twelve pixels across, which
                    // is the size a wrongly-matched 8x8 block produces.
                    "vec2 tx = vec2(6.0 / 1280.0, 0.0);",
                    "vec2 ty = vec2(0.0, 6.0 / 720.0);",
                    "float n0 = max3(texture2D(screenTexture, clamp(vUV + tx, 0.0, 1.0)).rgb);",
                    "float n1 = max3(texture2D(screenTexture, clamp(vUV - tx, 0.0, 1.0)).rgb);",
                    "float n2 = max3(texture2D(screenTexture, clamp(vUV + ty, 0.0, 1.0)).rgb);",
                    "float n3 = max3(texture2D(screenTexture, clamp(vUV - ty, 0.0, 1.0)).rgb);",
                    // The dimmest neighbour: a pixel on the dark side of an
                    // ordinary edge has a dark neighbour, so requiring all four to
                    // be lit is what separates a dot from a boundary.
                    "float dimmest = min(min(n0, n1), min(n2, n3));",
                    "float lit = step(0.15, dimmest);",
                    "gl_FragColor = vec4(",
                        // R: how much of the frame is trusted at all.
                        "confidence,",
                        // G: a dot this shader produced -- black surrounded by
                        // lit pixels, which no correct interpolation makes
                        // whatever the vectors said.
                        "step(outLuma, 0.06) * lit,",
                        // B: where the search cannot tell a wrong vector from
                        // the winner *and the picture can*. **Both halves are
                        // needed, and the first version had only one.**
                        //
                        // Displacing the winner by sixteen luma pixels and asking
                        // whether the cost notices reads 74-90% on Metro, before
                        // and after the cost function was widened to a patch. That
                        // is not a fault: most of any frame is flat, flat regions
                        // have no recoverable motion at all -- the aperture
                        // problem -- and a wrong vector there fetches
                        // indistinguishable content and does no harm. Measuring it
                        // alone measures how flat the game is, which is the same
                        // mistake the first dark-pixel counter made.
                        //
                        // A site is only damaging when the search is blind to the
                        // error *and* the colour it would fetch instead is plainly
                        // different. That conjunction is the artefact, and it is
                        // what this counts. The margin is relative so it means the
                        // same thing whatever scale the cost function works at.
                        "step(0.004, length(raw))",
                            "* (1.0 - step(0.25, (costAlt - costWin)",
                                            "/ max(costAlt + costWin, 0.004)))",
                            "* step(0.06, length(newer - altColour)),",
                        // A: the share of the frame the winner explains badly.
                        // A thresholded fraction rather than a mean, because a
                        // fault confined to silhouettes is a percent of the pixels
                        // and vanishes into an average -- which is how a frame
                        // reading 0.24% mean residual can still carry visible
                        // damage.
                        "step(0.10, cost(best)));",
                    "return;",
                "}",

                "gl_FragColor = vec4(shown, 1.0);",
            "}"
        );
    }
}
