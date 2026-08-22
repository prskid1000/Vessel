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
 * N cannot be shown until the frames belonging before it have been. It is
 * {@code (K-1)/K} of one source interval, and no amount of optimisation reaches
 * past it, because holding N back *is* the method.
 *
 * <h2>The geometry</h2>
 *
 * <p>Write {@code b} for the displacement the search settles on. For content at
 * {@code q} in the frame being built, where it sits in N is {@code q - b(1 - t)}
 * and where it sat in N-1 is {@code q + bt}. Both are sampled and blended by
 * {@code t}, which is what makes this bilateral rather than a one-sided warp:
 * near {@code t = 0} the older frame dominates and near {@code t = 1} the newer
 * does, so each endpoint is trusted most where it is most likely correct.
 *
 * <h2>What the search knows</h2>
 *
 * <p>The hardware block matcher answers one question at one scale, and its spec
 * declines to define the quality of the answer. Everything below exists because
 * that answer, used directly, is not a field anyone should warp a picture with.
 *
 * <p><b>1. The direction is chosen per pixel.</b> The extension never says whether
 * its vectors run from reference to target or back, and a reconstruction test
 * could not settle it: against an uncompensated 0.00248 the two hypotheses scored
 * 0.00192 and 0.00182, five per cent apart, while both explained barely a quarter
 * of the difference between the frames. So the question is not answered globally.
 * Where motion is real the wrong sign displaces by twice it and loses outright;
 * where motion is zero the two are identical and it does not matter.
 *
 * <p><b>2. The field is projected to this instant before it is read.</b> Sampling
 * {@code b} at {@code q} gives the motion of whatever is at {@code q} in frame N
 * -- and at an intermediate instant the thing at {@code q} is not that. A moving
 * object's leading edge reads the background's vector and its trailing edge
 * applies the object's vector to background pixels. Scattering into the
 * intermediate frame needs atomics; the gather-side equivalent is the fixed point
 * {@code p <- q - b(p)(1 - t)}, iterated twice.
 *
 * <p><b>3. Neighbouring blocks are chosen between, not averaged.</b> Bilinear
 * filtering of a per-block field invents a vector between two blocks that
 * disagree, and at the edge of every moving object that invented vector describes
 * nothing in the scene. FidelityFX upscales its own optical flow the same way:
 * best of four under SAD, rather than by interpolating.
 *
 * <p><b>4. There is a second, coarser level.</b> The search window is finite and
 * the spec makes "no match" and "no motion" the same answer -- zero either way --
 * so a block whose displacement runs off the end of the range reports stillness
 * exactly where the picture moves fastest. Halving the resolution halves every
 * displacement, so the same silicon follows twice the motion. Free: fixed
 * function, and all the estimates together measure 0.001 ms.
 *
 * <p><b>5. The matcher is run both ways.</b> For a correspondence that really
 * exists the two fields are negatives of each other. Where they are not, one frame
 * does not hold the content at all -- which is the occlusion test -- and the
 * reverse field is additionally an independent second opinion on the displacement.
 *
 * <p><b>6. Standing still is on the ballot.</b> Every other candidate comes from a
 * block matcher, and a block matcher asked about a stationary HUD over a moving
 * world reports the world's motion. It is offered unpenalised, because it is not
 * another block's guess to be discounted; it is the answer whenever nothing moved.
 * It is also the one displacement that resamples nothing, so a static region comes
 * out bit-exact rather than bilinearly approximated.
 *
 * <p><b>7. The previous interval's field is a candidate, and a bounded prior.</b>
 * Motion is coherent in time -- what moved this way a frame ago is probably still
 * moving this way -- which is why the temporal predictor is among the strongest in
 * every video codec. It is offered as a candidate, and additionally every candidate
 * is charged a little for departing from it, which is what keeps a near-tie in an
 * ambiguous region from being re-decided differently every frame.
 *
 * <p>**The charge is capped, and the first version of it froze moving objects.**
 * Unbounded, the term is proportional to how far a candidate sits from last
 * interval's motion -- so for an object moving a twentieth of the screen it grew
 * larger than the penalty that ranks whole candidate classes, and the search
 * stopped being a search. Worse, it pulls hardest toward zero exactly where the
 * previous field *is* zero, which is the matcher's way of saying it failed. A
 * genuinely moving object over a failed prior was being assigned no motion at all
 * and frozen in place, which is stutter manufactured out of a stabiliser.
 *
 * <p>A prior is meant to break ties, not to steer. The cap is a third of the
 * neighbour penalty, so it can decide between candidates the image cannot separate
 * and can never override one the image can.
 *
 * <h2>How a match is judged</h2>
 *
 * <p><b>8. At two scales, not on one texel.</b> A single luma difference is a very
 * weak discriminator and the search leans on it hard; in a textured region several
 * wrong displacements will match one pixel by luck. A full 8x8 SAD is out of reach
 * -- sixty-four taps per candidate -- but the half-size level is already built, and
 * one of its texels is the average of four.
 *
 * <p><b>9. On detail rather than brightness.</b> The extension tracks brightness,
 * and brightness is not conserved: a muzzle flash, a lamp coming into view or a
 * shadow crossing a wall changes every luma value in a region without anything
 * moving. The half-size level *is* a local mean, so {@code full - half} is the
 * detail with the local brightness removed, and comparing two of those is blind to
 * any illumination change flat across a few pixels. The coarse term stays
 * brightness-sensitive on purpose: a real lighting change lifts every candidate
 * together, which leaves the ranking alone and pushes the pixel below the
 * confidence threshold, so a frame during a flash falls back to something real.
 *
 * <p><b>10. And on colour, not only brightness.</b> Two different objects of equal
 * brightness are indistinguishable to a luma match; the old code only noticed
 * afterwards, when the winning vector's two colour reads disagreed, and by then all
 * it could do was decline. Carrying a chroma channel beside the luma costs nothing
 * per candidate -- one RG fetch returns both -- so the search can reject those
 * during the search instead of regretting them after it.
 *
 * <h2>What is done with the answer</h2>
 *
 * <p><b>11. Three ways, not two.</b> Where the fields agree both endpoints are real
 * and the bilateral blend is right. Where they disagree only one frame holds the
 * content, so it is sampled along the motion and used alone -- correctly placed, no
 * ghost, nothing frozen. Only where nothing explains the pixel at all does it fall
 * back to a real frame undisplaced. That is the structure FSR3 builds with its two
 * disocclusion masks.
 *
 * <p><b>12. Consistency is judged relative to speed.</b> Two fields describing a
 * 4-pixel motion should agree to within a fraction of a pixel; two describing a
 * 200-pixel motion cannot be expected to. A fixed bound calls every fast-moving
 * region occluded, which is precisely where interpolation is most wanted.
 *
 * <p><b>13. Reads off the frame are known to be off the frame.</b> Toward the
 * borders, and especially under camera motion where displacement is largest exactly
 * there, the source of a pixel lies outside what was captured. Clamping silently
 * returns the border pixel and smears it inward, and the score does not object
 * because a border pixel matches a border pixel. The distance between the
 * coordinate asked for and the one actually read is that error, for free.
 *
 * <p><b>14. Blending happens in linear light.</b> Averaging gamma-encoded values is
 * not averaging light -- the midpoint of a dark pixel and a bright one comes out
 * darker than the light between them actually is. On a smooth gradient nobody
 * notices; on the edge of a light shaft against a dark wall it darkens the seam
 * into a visible band.
 *
 * <p><b>15. A warped frame is sharpened back to parity with a real one, the way
 * AMD does it.</b> A real frame reaches the screen as a pixel-exact blit; a warped
 * one is bilinearly resampled and therefore softer. At 2x those alternate, which
 * is a sharpness oscillation at half the presented rate, on exactly the moving
 * content this feature exists to smooth.
 *
 * <p>**Two attempts at this put black and white dots across every detailed
 * surface, and the reason was the method, not the strength.** An unsharp mask adds
 * an amplified difference to a finished pixel, and nothing about that is bounded:
 * on high-contrast texture it overshoots past black and past white, and the final
 * clamp turns the overshoot into hard dots. Reducing the gain made them fainter;
 * capping the magnitude did not help either, because a dark pixel has almost no
 * room to be darkened at all and a fixed cap still clips it.
 *
 * <p>FidelityFX CAS -- Timothy Lottes' contrast-adaptive sharpening -- does not do
 * that. Two things about it matter here. It is a *normalised weighted average of
 * real neighbourhood samples*, {@code (b*w + d*w + f*w + h*w + e) / (1 + 4w)} with
 * {@code w} negative, so the result is bounded by the values actually present
 * around the pixel and cannot overshoot by construction. And its strength is
 * literally headroom: {@code amp = saturate(min(mn, 1 - mx) / mx)}, where
 * {@code mn} is the neighbourhood's distance to black and {@code 1 - mx} its
 * distance to white, so a neighbourhood already near either limit sharpens itself
 * by nothing. That is the black-dot case, switched off at the source rather than
 * clamped after the fact.
 *
 * <p>Two details of the integration follow from AMD's own notes. CAS wants linear
 * input, which this shader now has anyway for the blending. And the strength is
 * scaled by how far the sample actually moved, because a static pixel landed on a
 * texel centre, was never resampled, and has no lost detail to restore.
 *
 * <p><b>What remains out of reach, honestly.</b> A volumetric light shaft, a
 * reflection or a particle moves differently from the geometry behind it, so one
 * pixel carries two motions and a single vector per block cannot express either.
 * Nothing here will interpolate those correctly; the most it can do is notice that
 * no displacement explains the pixel and decline. DLSS 3 names particles,
 * reflections and shadows as the specific reason it needs a trained network on top
 * of its optical flow, and FSR3 spends two of its nine passes inpainting what its
 * own field could not describe.
 */
public class InterpolateMaterial extends ScreenMaterial {
    public final Uniforms interpolateUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform previousTexture = new Uniform("previousTexture");
        public final Uniform motionTexture = new Uniform("motionTexture");
        public final Uniform coarseTexture = new Uniform("coarseTexture");
        public final Uniform reverseTexture = new Uniform("reverseTexture");
        public final Uniform historyTexture = new Uniform("historyTexture");
        public final Uniform matchNewerTexture = new Uniform("matchNewerTexture");
        public final Uniform matchOlderTexture = new Uniform("matchOlderTexture");
        public final Uniform broadNewerTexture = new Uniform("broadNewerTexture");
        public final Uniform broadOlderTexture = new Uniform("broadOlderTexture");
        public final Uniform motionScale = new Uniform("motionScale");
        public final Uniform coarseScale = new Uniform("coarseScale");
        public final Uniform vectorSize = new Uniform("vectorSize");
        public final Uniform colourTexel = new Uniform("colourTexel");
        public final Uniform phase = new Uniform("phase");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // --- how much a candidate must beat the smooth answer by -----------
            //
            // Without these the winner in a flat region is decided by sensor-level
            // noise and flips every frame, which shimmers along every soft edge.
            "#define NEIGHBOUR_PENALTY 0.02",
            // The coarse level is a blurrier description of the same motion, so it
            // wins only where the fine level failed rather than by a shade.
            "#define COARSE_PENALTY 0.03",
            // What a candidate is charged per unit of departure from the motion
            // this pixel had last interval, and the most that charge may ever
            // amount to. See point 7: uncapped, this stops being a tie-break and
            // starts being a bias toward standing still.
            "#define TEMPORAL_WEIGHT 0.25",
            "#define TEMPORAL_CAP 0.006",

            // How much a chroma disagreement counts. Below the luma terms, because
            // chroma is the coarser signal and subsampled in most content anyway;
            // enough that two objects of equal brightness and different colour
            // cannot be confused.
            "#define CHROMA_WEIGHT 0.35",

            // --- when the answer stops being believed --------------------------
            //
            // How well the winner had to explain the picture. Above the second
            // nothing matched at all and the winner is only the least bad, which
            // is what a disoccluded region looks like from in here.
            "#define SEARCH_FULL 0.04",
            "#define SEARCH_NONE 0.15",
            // How far the two warped colour reads may differ. Catches what the
            // luma search cannot: the right brightness on the wrong object.
            "#define AGREE_FULL 0.12",
            "#define AGREE_NONE 0.40",
            // How far the forward and reverse fields may drift apart before the
            // pixel counts as seen by only one frame, plus the share of the
            // measured displacement forgiven on top. See point 12.
            "#define AGREE_BOTH 0.004",
            "#define AGREE_ONE 0.015",
            "#define DRIFT_SHARE 0.15",
            // How far a read may fall off the frame before that endpoint counts as
            // holding nothing. The taper exists only so the edge is not a line.
            "#define EDGE_FADE 0.01",

            // The negative lobe of the CAS kernel at full strength. Negative
            // because that is what sharpens; small because this is restoring
            // detail resampling lost, not adding contrast that was never there.
            // AMD's own range runs to -1/5 for maximum sharpness; this is nearer
            // their minimum. See point 15.
            "#define CAS_PEAK -0.125",

            // The two real frames, in colour.
            "uniform sampler2D screenTexture;",
            "uniform sampler2D previousTexture;",
            // One vector per 8x8 block of the full-size luma, this interval.
            "uniform sampler2D motionTexture;",
            // The same of the half-size luma: twice the reach.
            "uniform sampler2D coarseTexture;",
            // The same pair matched the other way round.
            "uniform sampler2D reverseTexture;",
            // The fine field of the *previous* interval.
            "uniform sampler2D historyTexture;",
            // Luma in R and chroma in G, at full size and at half. One fetch
            // returns both channels, which is why point 10 costs nothing.
            "uniform sampler2D matchNewerTexture;",
            "uniform sampler2D matchOlderTexture;",
            "uniform sampler2D broadNewerTexture;",
            "uniform sampler2D broadOlderTexture;",
            // Each field's units into texture space: 1 / that level's size.
            "uniform vec2 motionScale;",
            "uniform vec2 coarseScale;",
            // Dimensions of the fine grid, for addressing single blocks.
            "uniform vec2 vectorSize;",
            // One pixel of the presented frame, for the sharpening taps.
            "uniform vec2 colourTexel;",
            // Where between the two frames this one sits: 0 is N-1, 1 is N.
            "uniform float phase;",

            "varying vec2 vUV;",

            // The motion this pixel had last interval. A global because the
            // ranking function needs it and GLSL ES has no closures.
            "vec2 gPrior;",

            // How badly a displacement fails to explain this neighbourhood.
            // Clamped rather than wrapped: a vector pointing off the edge has no
            // source, and repeating the border smears, where wrapping would fetch
            // the opposite side of the screen -- a much louder wrong answer.
            "float cost(vec2 b) {",
                "vec2 a = clamp(vUV - b * (1.0 - phase), 0.0, 1.0);",
                "vec2 c = clamp(vUV + b * phase, 0.0, 1.0);",

                "vec2 fineNewer = texture2D(matchNewerTexture, a).rg;",
                "vec2 fineOlder = texture2D(matchOlderTexture, c).rg;",
                "vec2 wideNewer = texture2D(broadNewerTexture, a).rg;",
                "vec2 wideOlder = texture2D(broadOlderTexture, c).rg;",

                // Detail with the local brightness removed: blind to any
                // illumination change flat across a few pixels. See point 9.
                "float detail = abs((fineNewer.r - wideNewer.r)",
                                  "- (fineOlder.r - wideOlder.r));",
                // Deliberately still brightness-sensitive. See point 9.
                "float broad = abs(wideNewer.r - wideOlder.r);",
                // Chroma judged at the coarse scale, where it is least noisy.
                "float chroma = abs(wideNewer.g - wideOlder.g);",

                "return 0.5 * (detail + broad) + CHROMA_WEIGHT * chroma;",
            "}",

            // What a candidate is worth: how well it explains the picture, what it
            // costs to prefer it, and how far it strays from last interval.
            "float rank(vec2 b, float penalty) {",
                "return cost(b) + penalty",
                     "+ min(TEMPORAL_WEIGHT * length(b - gPrior), TEMPORAL_CAP);",
            "}",

            "vec2 fieldAt(vec2 uv, float direction) {",
                "return texture2D(motionTexture, uv).rg * motionScale * direction;",
            "}",

            "vec2 coarseAt(vec2 uv, float direction) {",
                "return texture2D(coarseTexture, uv).rg * coarseScale * direction;",
            "}",

            "vec2 reverseAt(vec2 uv, float direction) {",
                "return texture2D(reverseTexture, uv).rg * motionScale * direction;",
            "}",

            "vec2 priorAt(vec2 uv, float direction) {",
                "return texture2D(historyTexture, uv).rg * motionScale * direction;",
            "}",

            "void main() {",
                // 1. The smooth field, and which way it points. Measured here
                //    rather than assumed anywhere. See point 1. The prior is zero
                //    for this comparison, so it cannot tilt the direction test.
                "gPrior = vec2(0.0);",
                "vec2 raw = texture2D(motionTexture, vUV).rg * motionScale;",
                "float direction = cost(raw) <= cost(-raw) ? 1.0 : -1.0;",

                // 2. Walk the field back to where this instant's content sits in
                //    frame N, so the vector read belongs to the right object.
                "vec2 p = clamp(vUV - raw * direction * (1.0 - phase), 0.0, 1.0);",
                "p = clamp(vUV - fieldAt(p, direction) * (1.0 - phase), 0.0, 1.0);",

                // The prior, read at the projected point like everything else.
                "gPrior = priorAt(p, direction);",

                // 3. The smooth field at the projected point, then every rival.
                "vec2 best = fieldAt(p, direction);",
                "float bestCost = rank(best, 0.0);",
                // The winner's own explanatory power, without the penalty or the
                // prior that only ordered the search: a vector that explains the
                // picture is trustworthy however it was found.
                "float bestRaw = cost(best);",

                "vec2 candidate; float score;",
                "vec2 texel = 1.0 / vectorSize;",
                "vec2 base = (floor(p * vectorSize - 0.5) + 0.5) * texel;",

                "candidate = fieldAt(base, direction);",
                "score = rank(candidate, NEIGHBOUR_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                "candidate = fieldAt(base + vec2(texel.x, 0.0), direction);",
                "score = rank(candidate, NEIGHBOUR_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                "candidate = fieldAt(base + vec2(0.0, texel.y), direction);",
                "score = rank(candidate, NEIGHBOUR_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                "candidate = fieldAt(base + texel, direction);",
                "score = rank(candidate, NEIGHBOUR_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                // The coarse level: the only candidate with an answer when the
                // fine search ran out of range. See point 4.
                "candidate = coarseAt(p, direction);",
                "score = rank(candidate, COARSE_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                // The reverse field: an independent second opinion, negated
                // because it describes the journey the other way. See point 5.
                "candidate = -reverseAt(p, direction);",
                "score = rank(candidate, NEIGHBOUR_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                // Last interval's motion, on its own account. See point 7.
                "candidate = gPrior;",
                "score = rank(candidate, NEIGHBOUR_PENALTY);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                // Standing still, unpenalised: the null hypothesis rather than
                // another block's guess. See point 6.
                "candidate = vec2(0.0);",
                "score = rank(candidate, 0.0);",
                "if (score < bestCost) { bestCost = score; bestRaw = cost(candidate); best = candidate; }",

                // --- reading the two endpoints --------------------------------
                //
                // Kept before and after clamping, because the difference is
                // exactly how far the read fell off the frame. See point 13.
                "vec2 wantNewer = vUV - best * (1.0 - phase);",
                "vec2 wantOlder = vUV + best * phase;",
                "vec2 fromNewer = clamp(wantNewer, 0.0, 1.0);",
                "vec2 fromOlder = clamp(wantOlder, 0.0, 1.0);",
                "float insideNewer = 1.0 - smoothstep(0.0, EDGE_FADE,",
                    "length(wantNewer - fromNewer));",
                "float insideOlder = 1.0 - smoothstep(0.0, EDGE_FADE,",
                    "length(wantOlder - fromOlder));",

                "vec3 newer = texture2D(screenTexture, fromNewer).rgb;",
                "vec3 older = texture2D(previousTexture, fromOlder).rgb;",

                // --- how much of it to believe ---------------------------------
                "float disagreement = length(newer - older);",
                "float explained = 1.0 - smoothstep(SEARCH_FULL, SEARCH_NONE, bestRaw);",
                "float agreed = 1.0 - smoothstep(AGREE_FULL, AGREE_NONE, disagreement);",
                "float confidence = min(explained, agreed);",
                // Neither endpoint on the frame at all: nothing to warp from.
                "confidence = min(confidence, max(insideNewer, insideOlder));",

                // Whether both frames really see this. For a correspondence that
                // exists the reverse field is the negative of the forward one.
                "vec2 back = reverseAt(fromOlder, direction);",
                "float drift = length(best + back);",
                "float tolerance = AGREE_BOTH + length(best) * DRIFT_SHARE;",
                "float bilateral = 1.0 - smoothstep(",
                    "tolerance, tolerance + (AGREE_ONE - AGREE_BOTH), drift);",
                // Both endpoints have to exist to be blended.
                "bilateral = min(bilateral, min(insideNewer, insideOlder));",

                // --- putting it together, in linear light ----------------------
                //
                // Nothing explained it at all: a real frame, undisplaced. A moment
                // of judder confined to those pixels is the honest failure; a
                // confident smear is not.
                "vec3 nearest = phase < 0.5",
                    "? texture2D(previousTexture, vUV).rgb",
                    ": texture2D(screenTexture, vUV).rgb;",

                // Squared in, square-rooted out. See point 14 -- and the round
                // trip is exact wherever nothing is actually being mixed.
                "vec3 newerLin = newer * newer;",
                "vec3 olderLin = older * older;",
                // Seen by one: whichever still holds the content. Usually the
                // newer, since that is what a disocclusion reveals -- but at an
                // edge the camera is moving away from it is the older.
                // --- sharpness parity with a real frame, and the blend ---------
                //
                // Scaled by how far the sample actually moved, in pixels: a static
                // pixel landed on a texel centre and is already exact, so it is
                // left alone. See point 15.
                "float moved = clamp(length(best) / max(colourTexel.x, 1e-6), 0.0, 1.0);",
                "vec3 tb = texture2D(screenTexture, clamp(fromNewer - vec2(0.0, colourTexel.y), 0.0, 1.0)).rgb;",
                "vec3 td = texture2D(screenTexture, clamp(fromNewer - vec2(colourTexel.x, 0.0), 0.0, 1.0)).rgb;",
                "vec3 tf = texture2D(screenTexture, clamp(fromNewer + vec2(colourTexel.x, 0.0), 0.0, 1.0)).rgb;",
                "vec3 th = texture2D(screenTexture, clamp(fromNewer + vec2(0.0, colourTexel.y), 0.0, 1.0)).rgb;",
                // Linear, like everything else here and like CAS asks for.
                "tb *= tb; td *= td; tf *= tf; th *= th;",

                // Headroom toward both limits, which is what makes this adaptive:
                // a neighbourhood already near black or near white sharpens by
                // nothing, and that is the case that produced the dots.
                "vec3 mn = min(min(min(tb, td), min(tf, th)), newerLin);",
                "vec3 mx = max(max(max(tb, td), max(tf, th)), newerLin);",
                "vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 1e-4), 0.0, 1.0));",

                // A normalised weighted average of samples that really exist, so
                // it is bounded by them and cannot overshoot. See point 15.
                "vec3 w = amp * (CAS_PEAK * moved * confidence);",
                "vec3 sharpLin = (tb + td + tf + th) * w + newerLin;",
                "sharpLin /= (1.0 + 4.0 * w);",
                // **Saturated, because AMD saturates and the reason is not
                // cosmetic.** A normalised weighted average is bounded by its
                // inputs only when the weights are positive. Here w is negative,
                // so the numerator is (sum of neighbours)*w + centre, and a dark
                // pixel surrounded by bright ones drives it below zero -- which is
                // precisely the pixel a black dot appears on. Negative light then
                // reaches the sqrt below and comes back NaN, and a NaN resolves to
                // black or white depending on the hardware. CAS ends on ASatF1 for
                // exactly this; leaving it off is what kept the dots alive across
                // two different sharpeners.
                "sharpLin = clamp(sharpLin, 0.0, 1.0);",

                // The sharpened newer frame replaces the plain one in the blend,
                // so the correction is carried in proportion to how much of this
                // pixel came from N in the first place.
                "vec3 oneSided = insideNewer >= insideOlder ? sharpLin : olderLin;",
                "vec3 blended = mix(oneSided, mix(olderLin, sharpLin, phase), bilateral);",
                // max() before the root for the same reason: nothing downstream
                // of a NaN can be trusted, and one instruction makes it impossible.
                "vec3 result = sqrt(max(mix(nearest * nearest, blended, confidence), 0.0));",

                "gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);",
            "}"
        );
    }
}
