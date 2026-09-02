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
 * <p>Write {@code b} for the field as this shader uses it: for content sitting
 * at {@code x} in N-1, {@code -b} is the offset to where it is in N. Travelling
 * linearly, at phase {@code t} it is at {@code x - bt}. So whatever the
 * interpolated frame shows at {@code q} came from {@code q + bt} in N-1 and
 * {@code q - b(1 - t)} in N. That is the same derivation FidelityFX uses,
 * sampling the previous backbuffer at {@code uv + mv} and the current at
 * {@code uv - mv}.
 *
 * <p><b>{@code b} is indexed on N-1, and this said "measured on N" until the
 * driver was asked.</b> The distinction is invisible in the fetch sites above --
 * they are the same two expressions either way -- and decisive for anything that
 * SAMPLES the field, because the two indexings are a whole displacement apart.
 * {@code QCOM_motion_estimation} does not say which of its two inputs the vector
 * is based at, and {@link SignMaterial}'s claim that the two readings "differ by
 * a sign" is not right: under a rigid translation both hold the same vector
 * everywhere, so the question is invisible in exactly the camera pan that probe
 * votes on. {@code FrameSynthesizer.probeBasepoint} settles it on a synthetic
 * translation whose answer is known -- the field is indexed in the matcher's
 * {@code ref}, which here is N-1.
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
        /**
         * The same field measured the other way round, and the answer to which
         * source frame can see this pixel.
         *
         * <p>See the shader's consistency block. Zero-filled and switched off by
         * {@link #consistency} when the second estimate could not be produced.
         */
        public final Uniform backMotionTexture = new Uniform("backMotionTexture");
        /** 1 while the backward field is real, 0 to fall back to photometry alone. */
        public final Uniform consistency = new Uniform("consistency");
        public final Uniform motionScale = new Uniform("motionScale");
        public final Uniform vectorSize = new Uniform("vectorSize");
        public final Uniform phase = new Uniform("phase");
        /** VESSEL: 1 makes this pass report on itself instead of drawing. */
        public final Uniform diagnostic = new Uniform("diagnostic");
        /** VESSEL: 1 stamps synthesised frames so they can be told apart. */
        public final Uniform mark = new Uniform("mark");
        /** VESSEL: which way the field points, settled once. See SignMaterial. */
        public final Uniform fieldSign = new Uniform("fieldSign");
        /**
         * Colour size over luma size, per axis: 1 when the guest is a whole
         * number of search blocks, slightly above 1 when it is not.
         *
         * <p>The luma pair and the field cover only the block-rounded top-left
         * of the frame, so reading them at the colour target's UV stretched
         * them over the whole of it -- six pixels of misregistration at the
         * right edge of a 1366-wide guest, and every photometric test in this
         * shader was comparing luma from the wrong pixel there.
         */
        public final Uniform lumaScale = new Uniform("lumaScale");
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
            // **The field measured with the two frames swapped.** See the
            // consistency block below for what it is for; it is the geometric
            // fact that the photometric test underneath it can only guess at.
            "uniform sampler2D backMotionTexture;",
            "uniform float consistency;",
            // One field unit in texture space: 1 / **colour** size, not luma.
            //
            // The vectors are in luma pixels and a luma pixel is a colour pixel
            // -- the luma is the block-rounded top-left of the colour frame, not
            // a rescaled copy -- so one field unit is one colour texel, and the
            // fetch sites in predict() are colour UV. Reading "1 / luma size"
            // here and believing it is how a `motionScale` division ends up in
            // the wrong space; the value the caller sets has been right since
            // the lumaScale fix, and only this line said otherwise.
            "uniform vec2 motionScale;",
            // Dimensions of the vector texture, which is the block grid.
            "uniform vec2 vectorSize;",
            // Where between the two frames this one sits: 0 is N-1, 1 is N.
            "uniform float phase;",
            // The luma pair is no longer read here. Every photometric test
            // scores the colour targets instead; see main().

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
            // Colour UV to luma/field UV. See the Uniform's comment.
            "uniform vec2 lumaScale;",

            "varying vec2 vUV;",

            // How far apart two colours are, as the largest channel step. See
            // the photometric block in main() for why this is colour and not
            // the luma the matcher sees.
            "float maxDiff(vec3 a, vec3 b) { vec3 d = abs(a - b); return max(max(d.r, d.g), d.b); }",

            // The four blocks around a colour-space position and their
            // raised-cosine window. `base` is the block up-and-left of the
            // position and the fraction runs 0..1 from that block's centre to
            // the next one's. The four weights sum to one everywhere, so the
            // blend is a partition of unity and cannot brighten or darken the
            // picture; and because the window flattens at both ends, two blocks
            // that disagree hand over to each other smoothly instead of at a
            // seam -- which is what turns a wrong block into a soft ghost
            // rather than a hard displacement.
            "void readField(vec2 uv, out vec4 w, out vec2 a, out vec2 b, out vec2 c, out vec2 d) {",
                "vec2 texel = 1.0 / vectorSize;",
                "vec2 grid = uv * lumaScale * vectorSize - 0.5;",
                "vec2 base = floor(grid);",
                "vec2 f = 0.5 - 0.5 * cos(PI * (grid - base));",
                "w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y),",
                         "(1.0 - f.x) * f.y, f.x * f.y);",
                "vec2 scale = motionScale * fieldSign;",
                "a = texture2D(motionTexture, (base + vec2(0.5, 0.5)) * texel).rg * scale;",
                "b = texture2D(motionTexture, (base + vec2(1.5, 0.5)) * texel).rg * scale;",
                "c = texture2D(motionTexture, (base + vec2(0.5, 1.5)) * texel).rg * scale;",
                "d = texture2D(motionTexture, (base + vec2(1.5, 1.5)) * texel).rg * scale;",
            "}",
            "vec2 fieldAt(vec2 uv) {",
                "vec4 w; vec2 a, b, c, d;",
                "readField(uv, w, a, b, c, d);",
                "return w.x * a + w.y * b + w.z * c + w.w * d;",
            "}",
            // The backward field at the older frame's site, **under the same
            // window the forward one is read with.**
            //
            // This was a single NEAREST fetch, and the consistency block below
            // subtracts it from a four-block blend. That difference is half the
            // local block-to-block disagreement whether or not anything is
            // occluded, and at a silhouette the neighbouring blocks disagree by
            // definition -- one holding the object, the next the background --
            // so it is the one place in the frame where the error is guaranteed
            // large. The mask stepped at the 8-pixel grid and the step swept
            // along every moving edge.
            //
            // 73f5455 fixed exactly this on the forward side and said so, then
            // deferred the backward side because four more fetches "was bundled
            // into a build that took the GPU down". A later revert kept that
            // commit's comment and dropped its caveat, so the block below has
            // been claiming a symmetry the code did not have. The four taps land
            // in a 160x90 texture that the forward read has already pulled into
            // cache, which is not what took the GPU down.
            "vec2 backAt(vec2 uv) {",
                "vec2 texel = 1.0 / vectorSize;",
                "vec2 grid = uv * lumaScale * vectorSize - 0.5;",
                "vec2 base = floor(grid);",
                "vec2 f = 0.5 - 0.5 * cos(PI * (grid - base));",
                "vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y),",
                              "(1.0 - f.x) * f.y, f.x * f.y);",
                "return (w.x * texture2D(backMotionTexture, (base + vec2(0.5, 0.5)) * texel).rg",
                      "+ w.y * texture2D(backMotionTexture, (base + vec2(1.5, 0.5)) * texel).rg",
                      "+ w.z * texture2D(backMotionTexture, (base + vec2(0.5, 1.5)) * texel).rg",
                      "+ w.w * texture2D(backMotionTexture, (base + vec2(1.5, 1.5)) * texel).rg)",
                      "* motionScale * fieldSign;",
            "}",

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
            // **Bilinear, fractional, and measured to stay that way.**
            //
            // The colour targets are GL_LINEAR, so a fetch displaced by a
            // fraction of a texel is a box blur of that fraction's width, and
            // at 4x the three phases put the same vector on three different
            // fractions while the real frame that follows has none. That is a
            // sharpness pulse on fine texture, and snapping the displacement
            // to whole texels was tried against it. The bench -- once it
            // could see the sampler at all; see texflicker.sampler_flicker --
            // measured it as worse, 2.00 against 1.03 over textured pixels:
            // a half-texel of position is a larger change to fine texture
            // than a half-texel of blur. A Catmull-Rom fetch, which keeps the
            // position and most of the sharpness, read 1.10 -- no better, at
            // four to nine times the fetches. So the fetch is a plain
            // bilinear one, and the texture flicker that remains is the
            // field's (1.80 estimated against 0.87 true on the same bench),
            // not the sampler's.
            // `fit` is how far this prediction's two endpoints disagree: the
            // largest channel step between what the older frame and the newer
            // frame show along this vector. Free, since both were fetched.
            "vec3 predict(vec2 v, float wOlder, float wNewer, out float fit) {",
                "vec2 fromNewer = clamp(vUV - v * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + v * phase, 0.0, 1.0);",
                "vec3 older = texture2D(previousTexture, fromOlder).rgb;",
                "vec3 newer = texture2D(screenTexture, fromNewer).rgb;",
                "fit = maxDiff(older, newer);",
                "return (older * wOlder + newer * wNewer) / max(wOlder + wNewer, 1.0e-4);",
            "}",

            "void main() {",
                // ---- the field, projected to this instant --------------------
                //
                // Sampling at vUV asks about whatever sits there in the frame
                // the field is indexed in, which near a moving edge is the wrong
                // object. One fixed-point step moves the read to where this
                // instant's content came from, and everything below -- the fetch
                // sites, the photometric tests, the consistency test and the
                // four predictions -- uses the field read THERE.
                //
                // **The step used to go the other way, and the driver settled
                // it.** This projected onto `fromNewer`, on the belief stated
                // throughout this file that the field is measured ON frame N:
                // that the vector at q describes what is at q in the newer
                // frame. FrameSynthesizer.probeBasepoint asked the hardware
                // directly -- a 512 px textured stripe translated a known 64 px,
                // through the real matcher at the real luma size -- and the
                // responding block columns began at the stripe's position in the
                // REF image, 256, against 320 for the alternative, with a
                // recovered vector of (+63.2, +0.0) out of +64. Identical across
                // three sessions.
                //
                // Ref is `previousLuma`, so the field is indexed on frame N-1,
                // and the N-1 site for this pixel is `fromOlder`, not
                // `fromNewer`. The two are a whole displacement apart: at the
                // 120-150 px this pipeline runs at, the old step moved the read
                // that far in the wrong direction, so near every motion boundary
                // the vector applied to a pixel belonged to content on the far
                // side of it.
                //
                // Nothing else moves. `mean` is still -r and predict() still
                // fetches at q + r(1-t) and q - r*t, which is the same geometry
                // written in the same units; the sign SignMaterial latched is
                // still the sign. Only the coordinate the field is read AT
                // changes, from the newer site to the older one.
                //
                // It also lands `mean` and `bAtOlder` on the same point for the
                // first time. The consistency test below subtracts one from the
                // other, and the backward field is indexed on the warped newer
                // frame -- N-1's geometry -- and read at `mo`. With the forward
                // field read at `p` = `mo`, eOlder is finally two fields sampled
                // at one position. eNewer is not: it reads the forward field at
                // an N coordinate, which this correction does not fix and which
                // is left alone deliberately rather than rewritten in the same
                // change that moved the projection.
                "vec2 mean = fieldAt(vUV);",
                // The N-1 site, which is `fromOlder` in predict(). See above.
                "vec2 p = clamp(vUV + mean * phase, 0.0, 1.0);",
                "vec4 weight; vec2 m0, m1, m2, m3;",
                "readField(p, weight, m0, m1, m2, m3);",
                "mean = weight.x * m0 + weight.y * m1 + weight.z * m2 + weight.w * m3;",

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
                // **Scored on colour, not on the luma the matcher was fed.**
                //
                // Every test in this block used to read the R8 luma pair. Luma
                // is what the extension needs; it is not what the eye compares.
                // Two surfaces of equal luma and different colour -- a red panel
                // against a green one, a coloured light on a grey wall -- read
                // as UNCHANGED to a luma test, so `fitStill` scored zero at a
                // boundary that plainly moved, `carry` fell to the uncompensated
                // cross-fade, and the chroma edge doubled: colour fringing on
                // every synthesised frame, absent from every real one.
                //
                // The colour targets are already bound, and the sites these
                // tests read are the sites predict() reads for the mean vector,
                // so the fetches are shared rather than added: two at vUV that
                // the `still` fallback needs anyway, two at the mean prediction
                // that the uniform-field branch below reuses outright, and two
                // cross-fetches for the two `stillAt` tests. Eight luma fetches
                // gone, two colour fetches in their place, and the tests no
                // longer go through lumaScale at all -- so the block-rounded
                // strip along the right and bottom edges is finally tested on
                // its own pixels rather than on the last luma column's.
                //
                // The distance is the largest channel difference: one 8-bit
                // step in any channel is one step, so the 2/255 and 12/255
                // ramps below mean exactly what they meant on luma.
                "vec3 hereN = texture2D(screenTexture, vUV).rgb;",
                "vec3 hereO = texture2D(previousTexture, vUV).rgb;",
                "float fitStill = maxDiff(hereN, hereO);",
                // **Kept unclamped as well, because the clamp is a lie the
                // consistency test below must not be told.** See the gate there.
                "vec2 mnRaw = vUV - mean * (1.0 - phase);",
                "vec2 moRaw = vUV + mean * phase;",
                "vec2 mn = clamp(mnRaw, 0.0, 1.0);",
                "vec2 mo = clamp(moRaw, 0.0, 1.0);",
                // The mean prediction's two endpoints, and each frame read at
                // the OTHER endpoint for the two stillness tests.
                "vec3 atMnN = texture2D(screenTexture, mn).rgb;",
                "vec3 atMoO = texture2D(previousTexture, mo).rgb;",
                "vec3 atMnO = texture2D(previousTexture, mn).rgb;",
                "vec3 atMoN = texture2D(screenTexture, mo).rgb;",
                // **Off the frame there is no evidence, and a clamp manufactures
                // some.** Both fetch sites are clamped, so a pixel whose
                // trajectory leaves the frame reads the border texel instead --
                // whatever sits at the edge, not the content this pixel would
                // have fetched. Every test below that compares the two frames
                // along the vector inherits that, so each side carries how far
                // inside the frame its own fetch landed, faded over about
                // twenty-five pixels rather than switched: a hard gate would
                // only move the artefact to wherever the gate turned on. The
                // two fade independently, because during a pan one of them is
                // off the frame long before the other.
                "vec2 edgeNewer = min(mnRaw, 1.0 - mnRaw);",
                "vec2 edgeOlder = min(moRaw, 1.0 - moRaw);",
                "float onNewer = smoothstep(0.0, 0.02, min(edgeNewer.x, edgeNewer.y));",
                "float onOlder = smoothstep(0.0, 0.02, min(edgeOlder.x, edgeOlder.y));",
                // **Scored only where both ends exist.** With one end clamped
                // to the border, `fitMoving` compares real content against the
                // edge texel and loses to `fitStill` for no reason the scene
                // gave -- so `carry` fell to the uncompensated cross-fade along
                // the whole band the scene was entering from, a double image
                // of the frame edge, one displacement wide, on every
                // synthesised frame and never on a real one. A fetch that left
                // the frame refutes nothing, so it contributes nothing: the
                // ratio then falls to motion, which is the answer everywhere
                // that is not an overlay.
                "float fitMoving = maxDiff(atMnN, atMoO) * onNewer * onOlder;",
                "float ratio = fitStill / (fitStill + fitMoving + 1.0 / 2550.0);",
                "float carry = smoothstep(0.3, 0.7, ratio);",

                // How still the content is at each of the two places this pixel
                // reads from. Computed on the mean vector rather than per block:
                // the four predictions sample within a block of each other, so
                // one answer describes all of them and costs two fetches instead
                // of eight. See predict().
                "float stillAtNewer = 1.0 - smoothstep(2.0 / 255.0, 12.0 / 255.0,",
                                                      "maxDiff(atMnN, atMnO));",
                "float stillAtOlder = 1.0 - smoothstep(2.0 / 255.0, 12.0 / 255.0,",
                                                      "maxDiff(atMoN, atMoO));",
                // And whether this pixel is one that moved at all. A still pixel
                // reading still content is a subtitle reading itself, which is
                // correct and must not be disturbed.
                "float moves = smoothstep(2.0 / 255.0, 12.0 / 255.0, fitStill);",

                // ---- which frame can actually see this pixel -----------------
                //
                // **The largest single lever in this shader, and the one every
                // other mechanism here was the wrong axis for.** Measured against
                // recordings: perfect knowledge of which SOURCE to trust is worth
                // 35 to 42% of total error, where perfect knowledge of which
                // VECTOR to use is worth 13.5%. Three times larger.
                //
                // The reason is that at a disocclusion there is no right vector.
                // Where a moving object uncovers background, that background
                // exists in the newer frame and in neither the older one nor any
                // vector pointing into it -- so no choice of motion helps, and
                // the fix is to stop reading the older frame there at all.
                //
                // **The test is a mutual one, which is what makes it geometric.**
                // The photometric weighting below asks "is the content at my
                // fetch site static", which two surfaces of similar brightness
                // defeat. This asks whether the two frames AGREE that they are
                // looking at the same surface. The forward field says the pixel
                // at `mn` in the newer frame corresponds to `mo` in the older.
                // The backward field, measured independently with the frames
                // swapped, is asked what `mo` corresponds to. If the two
                // correspondences are inverses of each other -- `mean` there and
                // `-mean` back -- both frames can see the surface and both are
                // worth reading. If they do not cancel, one of them is looking at
                // something the other cannot see, and the one whose own field
                // disagrees is the one to drop.
                //
                // This is what FSR3 spends a depth buffer on. Vessel has no depth
                // buffer and no engine motion vectors, and a transport for one
                // was built and measured: it delivered three distinct values
                // instead of 254, because the channel it travelled in was two
                // bits wide. The flow already crossing this shader answers the
                // same question without leaving the host.
                // **Both sides are round trips now, and the newer side never
                // was.** What shipped as `eNewer` compared the forward field
                // with ITSELF at two positions -- projected against unprojected
                // -- so it fired on any spatial gradient in the field, which is
                // every motion boundary whether or not anything was occluded.
                // Measured on the bench that is what it did: the mask fired on
                // 27.7% of the frame to cover 1.1% of true disocclusion, and
                // the picture scored better with the term switched off.
                //
                // The forward field at the newer site says mn corresponds to
                // mo. The backward field at mo, measured independently with the
                // frames swapped, says mo corresponds to mo + b. If the older
                // frame can see this surface those cancel: b = -mean, and
                // `eOlder` is zero. If the newer frame can see it, the forward
                // field at the site the backward one claims, mo + b, points
                // back by -b, and `eNewer` is zero. Where a surface is covered
                // in one frame, the field measured ON that frame is describing
                // something else there, and it is that frame's own round trip
                // that fails -- so the failing side is the one to drop.
                //
                // Read under the same window `mean` was built with. A NEAREST
                // sample subtracted from a blended one leaves half the local
                // block-to-block disagreement in the answer with nothing
                // occluded, and at a silhouette the neighbouring blocks
                // disagree by definition. That was a mask stepping at the
                // 8-pixel grid, sweeping along every moving edge.
                "vec2 bAtOlder = backAt(mo);",
                // **Kept, and measured rather than argued.** This reads the
                // forward field at the N site while the field is indexed on
                // N-1, which on paper is the construction that "fired on 27.7%
                // of the frame to cover 1.1%". On the bench's occluder scene
                // (tools/frame-bench/occside.py: background -24 px, object
                // +48 px, both fields built the device's way) it is what
                // catches the COVERED band: error there 10.9 levels with it,
                // 15.1 without, the rest of the frame unchanged at 0.10. The
                // leading band of an opaque object is covered background by
                // definition, so firing along it is the right answer there;
                // whether it also fires on field noise in flat regions is a
                // question for a recording, not for this scene.
                "vec2 fRound = fieldAt(clamp(moRaw + bAtOlder, 0.0, 1.0));",
                // Back into pixels, so the thresholds mean the same thing at
                // every resolution.
                "float eOlder = length((bAtOlder + mean) / motionScale);",
                "float eNewer = length((fRound + bAtOlder) / motionScale);",

                // A dead zone first: the matcher quantises to whole pixels and
                // the two passes are independent, so a couple of pixels of
                // disagreement is the instrument, not the scene. Past six pixels
                // the two fields are describing different surfaces.
                //
                // **Deliberately NOT the 1.0..10.0 ramp that shipped alongside
                // this gate, nor the 0.9 cap beside it.** Both came from 11a535e,
                // which stretched the ramp to damp a flicker -- and most of what
                // was flickering was this same clamped fetch, and the NEAREST
                // -against-blended comparison above, both of which are fixed
                // here directly. With the causes gone the ramp does not have to
                // be long enough to hide them, and a long ramp is a volume
                // control on the largest lever in this shader: at a silhouette,
                // half-strength correction leaves half the wrong frame in the
                // blend. The cap was backwards for the same reason -- it took the
                // same tenth off a twelve-pixel disagreement, where the other
                // frame demonstrably cannot see the surface, as off a two-pixel
                // one where the mask is guessing.
                //
                // **The dead zone is right and a FIXED dead zone is not, and
                // that is why this kept having to be stretched.** Six pixels of
                // round-trip disagreement is a lot at rest and is nothing at
                // 150, which is where this pipeline actually runs -- the field
                // is quantised to whole pixels and the two directions are
                // estimated through separate warp-plus-residual chains, so at
                // that speed ordinary estimation error clears six on its own and
                // the mask fires with nothing occluded. That is the same failure
                // already measured on the old newer-side term, which "fired on
                // 27.7% of the frame to cover 1.1% of true disocclusion", and
                // the reason 11a535e had to stretch the ramp to 1.0..10.0 to
                // damp a flicker rather than remove its cause.
                //
                // The published criterion is proportional. Sundaram, Brox and
                // Keutzer call a pixel occluded when the forward-backward round
                // trip fails
                //
                //     |w_w + w_b|^2 > 0.01(|w_w|^2 + |w_b|^2) + 0.5
                //
                // -- a tolerance that grows with the flow being judged, over a
                // small absolute floor. Their floor is half a square pixel
                // because their flow is subpixel-dense; ours is a whole-pixel
                // block matcher, and 1.5 px is the instrument floor the fixed
                // ramp had already measured. So the floor is kept and the
                // proportional term is added to it.
                //
                // This is the identity at rest -- sqrt(2.25) is 1.5, so a still
                // scene gets exactly the 1.5..6.0 ramp that ships today -- and
                // only diverges where the fixed number was wrong: at 150 px in
                // both directions the tolerance opens to 21 px, which is what
                // the criterion asks for and roughly what two independent
                // whole-pixel fields disagree by at that speed.
                "float magBack  = length(bAtOlder / motionScale);",
                "float magOlder = length(mean / motionScale);",
                "float magNewer = length(fRound / motionScale);",
                "float tolOlder = sqrt(0.01 * (magOlder * magOlder",
                                            "+ magBack * magBack) + 2.25);",
                "float tolNewer = sqrt(0.01 * (magNewer * magNewer",
                                            "+ magBack * magBack) + 2.25);",
                // The 4x span is the shipped ramp's own, so the softening either
                // side of the criterion is unchanged; only where it sits moves.
                "float occNewer = consistency * smoothstep(tolNewer, tolNewer * 4.0, eNewer);",
                "float occOlder = consistency * smoothstep(tolOlder, tolOlder * 4.0, eOlder);",
                "occNewer *= onNewer;",
                "occOlder *= onOlder;",

                // Both terms multiply, and they are asking different questions:
                // the photometric one whether this fetch site holds moving
                // content at all, the geometric one whether the other frame
                // confirms the correspondence. Measured, the photometric
                // weighting is already doing real work -- either source alone
                // scores far worse than the blend -- so it is kept rather than
                // replaced.
                //
                // **And a source whose fetch left the frame is not read.** The
                // edge fade above only switched the consistency test off there,
                // which left that source at FULL weight -- reading the border
                // texel, clamped, for every pixel in a band `v * phase` wide
                // along the edge the scene is entering from. At 4x the first
                // phase carries three quarters of that band from the older
                // frame, which has never seen it; the real frame that follows
                // has the true content, and the band flickers between the two
                // at the cadence. That is the border shimmer, and the honest
                // answer is the one the edge comment already gives: no evidence
                // there, so no weight there. The other frame can see the
                // entering content by construction, and takes over through the
                // same twenty-five-pixel fade.
                "float wOlder = (1.0 - phase) * (1.0 - stillAtOlder * moves) * (1.0 - occOlder) * onOlder;",
                "float wNewer = phase * (1.0 - stillAtNewer * moves) * (1.0 - occNewer) * onNewer;",
                // Both refused: nothing to prefer, so fall back to the plain mix
                // rather than dividing by nothing. This is also what catches the
                // case both consistency terms fire at once, which is a field the
                // shader has no reason to believe rather than an occlusion.
                "if (wOlder + wNewer < 1.0e-3) { wOlder = 1.0 - phase; wNewer = phase; }",


                // ---- overlapped block motion compensation --------------------
                // Four predictions, one per block, combined under the window.
                // Nothing is discarded and nothing is chosen.
                //
                // Where the four blocks agree -- most of the frame, after ten
                // anchored median passes -- the four predictions are the same
                // fetch and the window sums to one, so one prediction is the
                // exact answer for six fewer colour fetches. The branch is
                // coherent: it takes the same side across a whole block.
                "vec3 shown;",
                "vec2 spread = abs(m1 - m0) + abs(m2 - m0) + abs(m3 - m0);",
                "if (spread.x + spread.y < 1.0e-7) {",
                    // Four identical blocks make `mean` equal to m0, so the
                    // mean prediction's endpoints fetched above ARE this
                    // prediction: no colour fetch at all on this path.
                    "shown = (atMoO * wOlder + atMnN * wNewer) / max(wOlder + wNewer, 1.0e-4);",
                "} else {",
                    // **The window says where the pixel sits; the fit says
                    // which block is telling the truth about it.**
                    //
                    // Recorded on device during a pan: a cabinet in front of
                    // a wall, the two at different depths and so moving by
                    // different amounts. Along the cabinet's silhouette the
                    // four blocks hold both motions, and a window blend alone
                    // mixes them across a 16 px band -- the border smeared
                    // into 8 px pieces on every synthesised frame and sharp on
                    // every real one, which is the shimmer on object edges.
                    //
                    // Each prediction has already fetched both of its
                    // endpoints, and a vector that fetched the wall for a
                    // pixel on the cabinet disagrees with itself by the whole
                    // contrast of that edge. So the window is scaled by the
                    // reciprocal of that disagreement. Where the four blocks
                    // agree the fits are equal and this IS the window; it is
                    // not a choice, so nothing pops. Measured on the bench's
                    // occluder and parallax scenes (occside.py): object error
                    // 3.56 to 3.30 and 4.17 to 3.97 levels, covered background
                    // 10.9 to 9.4, nothing worse anywhere. The floor is four
                    // 8-bit steps: a weighting that swings on one step is the
                    // texture-flicker pattern texflicker.py exists for.
                    "vec4 fit;",
                    "vec3 q0 = predict(m0, wOlder, wNewer, fit.x);",
                    "vec3 q1 = predict(m1, wOlder, wNewer, fit.y);",
                    "vec3 q2 = predict(m2, wOlder, wNewer, fit.z);",
                    "vec3 q3 = predict(m3, wOlder, wNewer, fit.w);",
                    "vec4 wf = weight / (fit + 4.0 / 255.0);",
                    "wf /= dot(wf, vec4(1.0));",
                    "shown = wf.x * q0 + wf.y * q1 + wf.z * q2 + wf.w * q3;",
                "}",

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
                "vec3 still = mix(hereO, hereN, phase);",
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
                    "float dSynth = length(shown - hereN);",
                    "float dBase  = length(hereO - hereN);",

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
                    // **Weighted by how much that fetch actually contributes,
                    // because a binary count overstates it and the first
                    // version of this was binary.**
                    //
                    // At 4x the phases are 0.25, 0.5 and 0.75, and the newer
                    // fetch reaches 1-phase of the vector while carrying only
                    // `phase` of the result. So an escape at phase 0.25 travels
                    // furthest and matters least: it darkens the pixel by a
                    // quarter, not to black. Counting it the same as a
                    // fully-weighted escape is what made a legitimate fast pan
                    // read 51 interior cells.
                    //
                    // Weighted, this is literally the share of the output that
                    // came from outside the frame -- which is the quantity that
                    // darkens the picture, and the only one worth a threshold.
                    "vec2 outNewer = vUV - mean * (1.0 - phase);",
                    "vec2 outOlder = vUV + mean * phase;",
                    "float escNewer = min(1.0,",
                        "step(outNewer.x, 0.0) + step(1.0, outNewer.x)",
                      "+ step(outNewer.y, 0.0) + step(1.0, outNewer.y));",
                    "float escOlder = min(1.0,",
                        "step(outOlder.x, 0.0) + step(1.0, outOlder.x)",
                      "+ step(outOlder.y, 0.0) + step(1.0, outOlder.y));",
                    "float escaped = phase * escNewer + (1.0 - phase) * escOlder;",

                    // **This counted pixels and it should have measured
                    // distances, and the difference made it useless.**
                    //
                    // What was here was `step(dBase, dSynth)`: a sign test, one
                    // vote per pixel, unweighted. A pixel that moved 0.009 and
                    // landed 0.010 away counted exactly as much as one that
                    // moved 0.01 and landed 0.8 away. Since most pixels sit
                    // just above the 0.008 floor, where every answer is a coin
                    // flip, the result was dominated by ties.
                    //
                    // Scored on a capture, a plain BLEND -- which applies no
                    // motion and therefore cannot be wrong about motion -- is
                    // charged with harming 30.8% of barely-moved pixels and
                    // 2.6% of decisively-moved ones, while holding the older
                    // frame and showing the real middle frame both read exactly
                    // 0.0 in every band. That is a metric measuring the shape of
                    // its own threshold.
                    //
                    // It reported 10 to 28% for a month and refused to move
                    // under displacement, motion diversity, contrast,
                    // brightness, search reach, occlusion, phase and field
                    // sign. Every one of those was read as a negative result.
                    // None of them was: the number was never responding to any
                    // condition, because it was not measuring one.
                    //
                    // So the two distances are accumulated directly and the
                    // ratio is taken afterwards. Mean distance is what "how
                    // wrong is the picture" means, it has no threshold to be
                    // dominated by, and a blend now scores as a blend.
                    "gl_FragColor = vec4(",
                        // R: how far the synthesised pixel landed from the
                        // truth, where anything moved at all.
                        "changed * min(dSynth, 1.0),",
                        // G: pixels whose fetch left the frame and was clamped
                        // to its edge. See above for what this replaced and why
                        // that could not have worked.
                        "escaped,",
                        // B: how far the OLD frame already was, over the same
                        // pixels. R over B is the whole answer -- below one the
                        // synthesis is closer to the truth than doing nothing,
                        // above one it is further, and unlike the count it
                        // replaces it says by how much.
                        "changed * min(dBase, 1.0),",
                        // A: how much of the frame changed at all. Both sums
                        // above are taken over these pixels, so this is what
                        // turns either of them back into a mean distance.
                        "changed);",
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
