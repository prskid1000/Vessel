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
 * <h2>What it still does per pixel, and what it deliberately does not</h2>
 *
 * <p><b>The field's sign is no longer decided here, and that was the worst bug in
 * this file.</b> It used to score both signs at every pixel and take the better,
 * on the reasoning that a wrong sign displaces by twice the true motion and so
 * loses decisively. The reasoning is sound; the implementation shredded every
 * synthesised frame. Where the two costs are a near-tie the binary choice flips on
 * image noise, and a flipped sign does not nudge a pixel -- it throws it twice the
 * vector in the wrong direction, which during a fast rotation is on the order of
 * 128 pixels. Neighbouring pixels then fetch unrelated parts of the scene, and in
 * a dark game half of them land in shadow.
 *
 * <p>No measurement caught it, and could not have: every diagnostic asked whether
 * the chosen vector explained the pixel, while the sign was chosen to minimise
 * exactly that quantity. Decision and measurement optimised the same thing, so the
 * measurement could only agree. It took stepping through a screen recording frame
 * by frame -- every second frame, the synthesised one, eaten through with
 * hard-edged black speckle wherever the scene had detail.
 *
 * <p>The sign is a property of the field, so {@link SignMaterial} settles it once
 * over the whole frame and it arrives here as a uniform. Every pixel gets the same
 * answer, there is no branch left to flip, and the interpolation loses four luma
 * fetches per pixel as well.
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
        /** VESSEL: 1 stamps synthesised frames so they can be told apart. */
        public final Uniform mark = new Uniform("mark");
        /** VESSEL: which way the field points, settled once. See SignMaterial. */
        public final Uniform fieldSign = new Uniform("fieldSign");
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
            // One field unit in texture space: 1 / luma size.
            "uniform vec2 motionScale;",
            // Dimensions of the vector texture, which is the block grid.
            "uniform vec2 vectorSize;",
            // Where between the two frames this one sits: 0 is N-1, 1 is N.
            "uniform float phase;",
            // **The luma pair, for the static test only.** Both were already
            // bound by FrameSynthesizer and both had Uniform objects here -- only
            // the GLSL declarations were missing, deleted along with the
            // per-pixel sign scoring that used to be the sole reader. Binding a
            // sampler a shader does not declare is silent; reading one it does
            // not declare is a compile error, which is how this surfaced.
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",

            // VESSEL: when 1, write four measurements instead of a picture.
            "uniform float diagnostic;",
            // **A corner this shader paints and a real frame never does.**
            //
            // Real and synthesised frames cannot be told apart in a recording by
            // inference, and it was tried: screenrecord captures at the panel's
            // rate while the compositor presents at its own, so the two are not
            // locked. A 4x periodicity that looked clean across thirty frames --
            // the synthesised ones 10% softer -- washed out to 0.4% across a
            // thousand, and the classification was worthless.
            //
            // Sixteen magenta pixels settle it. Every GPU vendor ships the same
            // idea: FSR3 has FFX_FRAMEGENERATION_FLAG_DRAW_DEBUG_TEAR_LINES "to
            // assist visualizing if interpolated frames are getting presented",
            // and Intel tags interpolated frames with purple boxes.
            "uniform float mark;",
            // **Which way the field points, as one number for the whole frame.**
            // This used to be decided here, per pixel, by scoring both signs and
            // taking the better -- and that was the artefact. See SignMaterial:
            // where the two costs are a near-tie the decision flips on image
            // noise, and a flipped sign displaces the pixel by twice the vector
            // in the wrong direction, so neighbouring pixels fetch unrelated
            // parts of the scene. In a dark game half of those land in shadow,
            // which is hard-edged black speckle through every detailed region of
            // every synthesised frame.
            "uniform float fieldSign;",

            "varying vec2 vUV;",

            // One bilateral prediction: both endpoints displaced symmetrically
            // about this pixel and blended by phase, so each is trusted most
            // where it is most likely to be right. Clamped rather than wrapped: a
            // vector pointing off the edge has no source, and repeating the
            // border smears where wrapping would fetch the opposite side of the
            // screen -- a much louder wrong answer.
            // **A moving pixel has no business fetching from an overlay.**
            //
            // The subtitle ghost has two halves and only one of them is the
            // glyph being displaced. The other is the background beside it doing
            // everything right: those pixels really did move, so they really are
            // compensated, and the position they sample in the other frame is
            // where a subtitle happens to be sitting. A subtitle does not move,
            // so it is in both frames at the same place, and a background pixel
            // reaching past it collects it. Photographed during a fast pan:
            // "Ranger's Badge, on the spot!" legible a second time, offset down
            // and right, while the real text beside it was sharp and correct.
            //
            // Handing that case perfect motion knowledge makes it worse rather
            // than better -- measured at 25.85 levels against 6.25 -- because the
            // background behind an overlay was never photographed and no vector
            // can recover it. What can be done is to notice the contamination:
            // if this pixel moved and the place it is sampling did not, that
            // sample is an overlay and the other endpoint is the honest one.
            //
            // So the two endpoints carry weights instead of a straight phase mix.
            // Where neither is contaminated the weights are (1-phase) and phase
            // and nothing changes at all.
            "vec3 predict(vec2 v, float wOlder, float wNewer) {",
                "vec2 fromNewer = clamp(vUV - v * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + v * phase, 0.0, 1.0);",
                "vec3 older = texture2D(previousTexture, fromOlder).rgb;",
                "vec3 newer = texture2D(screenTexture, fromNewer).rgb;",
                "return (older * wOlder + newer * wNewer) / max(wOlder + wNewer, 1.0e-4);",
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

                "vec2 scale = motionScale * fieldSign;",
                "vec2 m0 = texture2D(motionTexture, (base + vec2(0.5, 0.5)) * texel).rg * scale;",
                "vec2 m1 = texture2D(motionTexture, (base + vec2(1.5, 0.5)) * texel).rg * scale;",
                "vec2 m2 = texture2D(motionTexture, (base + vec2(0.5, 1.5)) * texel).rg * scale;",
                "vec2 m3 = texture2D(motionTexture, (base + vec2(1.5, 1.5)) * texel).rg * scale;",

                "vec2 mean = weight.x * m0 + weight.y * m1",
                          "+ weight.z * m2 + weight.w * m3;",

                // ---- read the field where this instant's content is ----------
                // See the class comment: sampling at vUV asks about whatever sits
                // there in frame N, which near a moving edge is the wrong object.
                "vec2 p = clamp(vUV - mean * (1.0 - phase), 0.0, 1.0);",
                "vec2 pgrid = p * vectorSize - 0.5;",
                "vec2 pbase = floor(pgrid);",
                "vec2 pw = 0.5 - 0.5 * cos(PI * (pgrid - pbase));",
                "weight = vec4((1.0 - pw.x) * (1.0 - pw.y),",
                              "pw.x * (1.0 - pw.y),",
                              "(1.0 - pw.x) * pw.y,",
                              "pw.x * pw.y);",

                "m0 = texture2D(motionTexture, (pbase + vec2(0.5, 0.5)) * texel).rg * scale;",
                "m1 = texture2D(motionTexture, (pbase + vec2(1.5, 0.5)) * texel).rg * scale;",
                "m2 = texture2D(motionTexture, (pbase + vec2(0.5, 1.5)) * texel).rg * scale;",
                "m3 = texture2D(motionTexture, (pbase + vec2(1.5, 1.5)) * texel).rg * scale;",

                // ---- a pixel that did not change did not move ----------------
                //
                // **This is the subtitle ghost, and it is the one artefact a
                // compositor can fix that FSR3 cannot.**
                //
                // The field is one vector per 8x8 block. A block holding a
                // subtitle over a panning background reports the *background's*
                // motion, because the background is most of the block and the
                // matcher minimises the sum over all of it. The glyph is static in
                // screen space, so its correct vector is zero -- and compensating
                // it by the background's vector sends one bilateral sample to the
                // glyph and the other to where the glyph would be had it moved.
                // Blended at phase 0.5 that is the text at half strength plus a
                // displaced copy of the text at half strength. Photographed
                // during a camera pan: "a cadet before they accepted" legible
                // twice, the second offset right and down along the pan.
                //
                // The 3D scene never shows it because its motion genuinely is the
                // background's, so both samples land on the right content. Only
                // screen-space overlays are wrong, and they are wrong in exactly
                // the places the block matcher cannot know about.
                //
                // The test is the definition rather than a heuristic: if this
                // pixel's brightness is the same in both real frames then nothing
                // passed through it, whatever the block around it did. An opaque
                // glyph is its own colour in both frames and scores zero; the
                // background beside it changes and scores high. So the mask lands
                // on the glyph without being told what a glyph is.
                //
                // It cannot backfire. Where it suppresses, the two frames agree,
                // so a compensated fetch and an uncompensated one return the same
                // content and the choice is invisible. Where anything genuinely
                // moved, carry is 1 and nothing changes at all.
                //
                // The floor is the source's own 8-bit quantisation -- one step is
                // 1/255, and two is the smallest difference that is a signal
                // rather than rounding. The ramp reaches full strength at six
                // times that, which is where a difference stops being plausibly
                // noise and starts being content. Neither is fitted to a scene:
                // both are properties of the buffer's precision.

                // ---- did this pixel move, and is what it reads trustworthy? --
                //
                // Both questions are answered here, above the predictions,
                // because both feed them: `carry` decides how far the result is
                // pulled back towards a stationary reading, and the two endpoint
                // weights decide which of the two frames is worth reading at all.
                "float fitStill = abs(texture2D(lumaNewerTexture, vUV).r",
                                   "- texture2D(lumaOlderTexture, vUV).r);",
                "vec2 mn = clamp(vUV - mean * (1.0 - phase), 0.0, 1.0);",
                "vec2 mo = clamp(vUV + mean * phase, 0.0, 1.0);",
                "float fitMoving = abs(texture2D(lumaNewerTexture, mn).r",
                                    "- texture2D(lumaOlderTexture, mo).r);",
                "float ratio = fitStill / (fitStill + fitMoving + 1.0 / 2550.0);",
                "float carry = smoothstep(0.3, 0.7, ratio);",

                // How still the content is at each of the two places this pixel
                // reads from. Computed on the mean vector rather than per block:
                // the four predictions sample within a block of each other, so
                // one answer describes all of them and costs two fetches instead
                // of eight. See predict().
                "float stillAtNewer = 1.0 - smoothstep(2.0 / 255.0, 12.0 / 255.0,",
                    "abs(texture2D(lumaNewerTexture, mn).r",
                      "- texture2D(lumaOlderTexture, mn).r));",
                "float stillAtOlder = 1.0 - smoothstep(2.0 / 255.0, 12.0 / 255.0,",
                    "abs(texture2D(lumaNewerTexture, mo).r",
                      "- texture2D(lumaOlderTexture, mo).r));",
                // And whether this pixel is one that moved at all. A still pixel
                // reading still content is a subtitle reading itself, which is
                // correct and must not be disturbed.
                "float moves = smoothstep(2.0 / 255.0, 12.0 / 255.0, fitStill);",
                "float wOlder = (1.0 - phase) * (1.0 - stillAtOlder * moves);",
                "float wNewer = phase * (1.0 - stillAtNewer * moves);",
                // Both refused: nothing to prefer, so fall back to the plain mix
                // rather than dividing by nothing.
                "if (wOlder + wNewer < 1.0e-3) { wOlder = 1.0 - phase; wNewer = phase; }",


                // ---- overlapped block motion compensation --------------------
                // Four predictions, one per block, combined under the window.
                // Nothing is discarded and nothing is chosen.
                "vec3 q0 = predict(m0, wOlder, wNewer);",
                "vec3 q1 = predict(m1, wOlder, wNewer);",
                "vec3 q2 = predict(m2, wOlder, wNewer);",
                "vec3 q3 = predict(m3, wOlder, wNewer);",
                "vec3 shown = weight.x * q0 + weight.y * q1",
                            "+ weight.z * q2 + weight.w * q3;",

                // ---- did this pixel move, or is it painted on the screen? ----
                //
                // **The subtitle ghost, and the two wrong answers before this
                // one.** The field is one vector per 8x8 block, so a block
                // holding a subtitle over a panning background reports the
                // background's motion -- the background is most of the block and
                // the matcher minimises the sum over all of it. Measured on a
                // known input: the true vector under the text is zero and the
                // block reports 30 of the background's 41 pixels. Compensating a
                // static glyph by three quarters of the scene's motion sends one
                // bilateral sample to the glyph and the other to where the glyph
                // would have been, and the blend is the text plus a displaced
                // copy of the text.
                //
                // The first attempt asked "did this pixel change between the two
                // real frames" and scaled the vectors by the answer. The second
                // asked the same question and blended the predictions instead.
                // Both improved the text and both wrecked the scene -- error
                // outside the subtitle went from 1.12 to 3.03 levels, tripling
                // across the 99% of the frame that was already right. The reason
                // is that the question is wrong: a flat wall is also unchanged at
                // this pixel, and it is not standing still. The test could not
                // separate an overlay from a smooth surface, so it suppressed
                // both.
                //
                // What separates them is which hypothesis explains the pixel.
                // Score staying put against moving by this block's vector, and
                // let the pixel choose:
                //
                //   still  -- the two frames agree here without compensation
                //   moving -- they agree here after it
                //
                // For a subtitle, staying put wins outright: the glyph is
                // identical in both frames and the compensated fetch lands on
                // background. For a flat wall neither hypothesis is contradicted
                // and the two tie. For anything textured and moving, moving wins.
                //
                // The ramp is symmetric about the tie rather than fitted: 0.5 is
                // exactly where the two explanations are equally good, so 0.3 to
                // 0.7 suppresses only a decisive win for staying put and lets a
                // tie fall to motion -- which is the answer that is right
                // everywhere except on an overlay. Measured against a
                // ground-truth midpoint frame, this takes the error under the
                // text from 4.62 levels to 3.00 while leaving the rest of the
                // scene at 1.17 against 1.12. The two earlier attempts bought the
                // text at the scene's expense; this one does not.
                // What a stationary thing looks like at this instant: the same
                // two frames read at this exact pixel. Exact for an overlay, and
                // what the blend falls back towards where motion is refuted.
                "vec3 still = mix(texture2D(previousTexture, vUV).rgb,",
                                 "texture2D(screenTexture, vUV).rgb, phase);",
                "shown = mix(still, shown, carry);",

                "if (diagnostic > 0.5) {",
                    // ---- measurements that the shader cannot flatter ---------
                    //
                    // **Everything measured here before this was worthless, and
                    // the way it was worthless is worth writing down.**
                    //
                    // It reported 100% trusted and 0.24% mean residual while
                    // every synthesised frame was being shredded with black
                    // speckle. Four separate reasons, all of them structural:
                    //
                    // 1. It was circular. The sign of the field was chosen to
                    //    minimise a cost, and then that same cost was reported as
                    //    "residual". A measurement downstream of an optimisation
                    //    of itself can only ever agree with it.
                    // 2. It averaged. A mean over 900,000 pixels reads fine while
                    //    a few per cent of them are destroyed.
                    // 3. It thresholded absolutely. `step(0.05, brightness)`
                    //    switches the detector off across most of a dark corridor
                    //    -- which is exactly where the fault lived.
                    // 4. It had no ground truth. Every test compared the output
                    //    against the inputs it was built from, so nothing was ever
                    //    checked against something it had not already assumed.
                    //
                    // What replaces it is anchored to the two real frames and to
                    // nothing else, and asks a question the shader has no way to
                    // optimise: at phase t the synthesised pixel should sit
                    // *between* the endpoints. So compare how far it lands from
                    // frame N against how far frame N-1 already was. If the
                    // synthesis is further from the truth than simply showing the
                    // old frame unchanged, frame generation is doing harm at that
                    // pixel, and no amount of internal self-consistency changes
                    // that.
                    "vec3 here  = texture2D(screenTexture,   vUV).rgb;",
                    "vec3 there = texture2D(previousTexture, vUV).rgb;",
                    "float dSynth = length(shown - here);",
                    "float dBase  = length(there - here);",

                    // Only pixels that actually changed between the two real
                    // frames can say anything: where nothing moved, both
                    // distances are zero and the ratio is noise over noise.
                    "float changed = step(0.008, dBase);",

                    // **Fetches that left the frame, and this replaces a test
                    // that could not have worked.**
                    //
                    // What was here asked whether the output was darker than
                    // anything real within six pixels of either endpoint, on
                    // the reasoning that such a pixel came from nowhere. Two
                    // things are wrong with it, and a control on a recording
                    // showed both.
                    //
                    // It cannot fire for the reason it claims. Every output
                    // pixel is a convex combination of two FETCHED pixels, so
                    // it can never be darker than the darker of the two.
                    // Content from nowhere is not a thing this shader can
                    // produce.
                    //
                    // And what it does fire on is correct behaviour. A dark
                    // beam at the right of frame N-1 and the left of frame N
                    // belongs, at the midpoint, exactly where both real frames
                    // show bright wall. Scored on a capture, the guest's OWN
                    // middle frames trip this test in 32% of frames against the
                    // synthesised frames' 23% -- a perfect answer accused more
                    // often than the pipeline's.
                    //
                    // A black patch is real, but its cause is a fetch landing
                    // where there is nothing: outside the frame, clamped to the
                    // edge, which on a letterboxed guest is the black bar. That
                    // is exact, needs no threshold, and cannot be tripped by
                    // legitimate motion of anything.
                    "vec2 outNewer = vUV - mean * (1.0 - phase);",
                    "vec2 outOlder = vUV + mean * phase;",
                    "float escaped = min(1.0,",
                        "step(outNewer.x, 0.0) + step(1.0, outNewer.x)",
                      "+ step(outNewer.y, 0.0) + step(1.0, outNewer.y)",
                      "+ step(outOlder.x, 0.0) + step(1.0, outOlder.x)",
                      "+ step(outOlder.y, 0.0) + step(1.0, outOlder.y));",

                    "gl_FragColor = vec4(",
                        // R: **the number that matters.** Pixels where the
                        // synthesised frame is further from frame N than frame
                        // N-1 already was -- worse than having done nothing at
                        // all. Counted only where something actually changed.
                        "changed * step(dBase, dSynth),",
                        // G: pixels whose fetch left the frame and was clamped
                        // to its edge. See above for what this replaced and why
                        // that could not have worked.
                        "escaped,",
                        // B: how much of the frame changed at all, which is the
                        // denominator R has to be read against. Without it a
                        // small R could mean "almost nothing was harmed" or
                        // "almost nothing was moving".
                        "changed,",
                        // A: how far the synthesis sits between the endpoints, on
                        // average. Half is what a correct interpolation at the
                        // midpoint looks like; approaching one means it is barely
                        // better than the old frame, and beyond one it is worse.
                        "clamp(dSynth / max(dBase, 0.008) * 0.5, 0.0, 1.0));",
                    "return;",
                "}",

                // The stamp, last, so nothing downstream can overwrite it.
                "if (mark > 0.5 && vUV.x < 16.0 * motionScale.x",
                                "&& vUV.y < 16.0 * motionScale.y) {",
                    "gl_FragColor = vec4(1.0, 0.0, 1.0, 1.0);",
                    "return;",
                "}",

                "gl_FragColor = vec4(shown, 1.0);",
            "}"
        );
    }
}
