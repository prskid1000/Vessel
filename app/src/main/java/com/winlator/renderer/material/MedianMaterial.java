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
        /**
         * The field this block carried at the previous real frame.
         *
         * <p>See the shader: this is 3DRS's temporal candidate, and it is what
         * the filter has been missing.
         */
        public final Uniform previousTexture = new Uniform("previousTexture");
        /** 0 on the first frame after allocation, when there is no history yet. */
        public final Uniform temporalValid = new Uniform("temporalValid");
        /**
         * One field unit in texture space: 1 / luma size, <b>signed by which
         * direction this chain is filtering</b>.
         *
         * <p>Needed to read the previous field where this block's content
         * actually was, rather than where the block sits. See the shader.
         *
         * <p>The sign is not decoration. A forward field already points
         * backwards in time -- its vector at p is the offset to where that
         * content sat in the older frame -- so the step towards last frame's
         * vector is a plus. A backward field points forwards, so the same step
         * is a minus. Passing the positive scale to both chains reads the
         * backward history twice the displacement away on the wrong side, which
         * makes the candidate lose every vote in precisely the case it exists
         * for. See {@code FrameSynthesizer.filterField}.
         */
        public final Uniform motionScale = new Uniform("motionScale");
        /** Which way the field points. See {@link SignMaterial}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
        /**
         * How many votes the previous vector casts. One is the candidate as it
         * was; three is a prior. See the shader's score loop.
         */
        public final Uniform temporalWeight = new Uniform("temporalWeight");
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

            // **The other half of 3DRS, and the half this file never had.**
            //
            // The comment above cites de Haan for keeping the matcher's own
            // vector among the candidates. That is the SPATIAL half. 3DRS's
            // other candidate is temporal -- the vector this block carried at
            // the previous frame -- and it is there for exactly the complaint
            // that is left: the field is re-estimated from nothing every real
            // frame, so a block whose two best matches are a near-tie picks one
            // this frame and the other the next, and the region under it changes
            // fifteen times a second while the scene does not. That is the
            // shimmer.
            //
            // Every previous attempt at it was aimed at the MASK -- widening the
            // occlusion ramp, capping the correction, giving the consistency
            // test a memory. None of them touched the thing that is actually
            // unstable, which is the field.
            //
            // A candidate rather than a blend, and that distinction is the whole
            // safety argument. The winner is still whichever vector minimises
            // total distance to the set, so the previous frame's vector can only
            // win by AGREEING with the nine neighbours around it. It cannot drag
            // a block anywhere the current frame does not already support, it
            // cannot invent a vector nobody voted for, and where the scene
            // genuinely changed it simply loses. It breaks ties towards
            // continuity and does nothing else.
            //
            // Read at vUV without motion compensation. For a field -- unlike a
            // mask -- that is defensible: the dominant case here is a camera pan,
            // which produces a nearly uniform field, so the vector a neighbouring
            // position held last frame is the same vector. Where an object moves
            // independently the stale candidate disagrees with its neighbours and
            // loses the vote, which is the safe failure rather than a wrong one.
            "uniform sampler2D previousTexture;",
            "uniform float temporalValid;",
            "uniform vec2 motionScale;",
            "uniform float fieldSign;",
            "uniform float temporalWeight;",
            "uniform vec2 texelSize;",
            "varying vec2 vUV;",

            "void main() {",
                // The nine neighbours, the matcher's own answer, and last
                // frame's. Indexed by loop counters only, which is the one form
                // of array indexing GLSL ES 1.00 guarantees.
                "vec2 c[11];",
                "c[0] = texture2D(screenTexture, vUV + vec2(-texelSize.x, -texelSize.y)).rg;",
                "c[1] = texture2D(screenTexture, vUV + vec2( 0.0,         -texelSize.y)).rg;",
                "c[2] = texture2D(screenTexture, vUV + vec2( texelSize.x, -texelSize.y)).rg;",
                "c[3] = texture2D(screenTexture, vUV + vec2(-texelSize.x,  0.0)).rg;",
                "c[4] = texture2D(screenTexture, vUV).rg;",
                "c[5] = texture2D(screenTexture, vUV + vec2( texelSize.x,  0.0)).rg;",
                "c[6] = texture2D(screenTexture, vUV + vec2(-texelSize.x,  texelSize.y)).rg;",
                "c[7] = texture2D(screenTexture, vUV + vec2( 0.0,          texelSize.y)).rg;",
                "c[8] = texture2D(screenTexture, vUV + vec2( texelSize.x,  texelSize.y)).rg;",
                // The tenth: what the matcher said here, before any pass ran.
                "c[9] = texture2D(originalTexture, vUV).rg;",
                // The eleventh: what this block said one real frame ago. Folded
                // back onto the centre when there is no history, so it costs a
                // duplicated candidate rather than a branch and cannot pull the
                // score anywhere on the first frame.
                // **Read where this block's content WAS, which is the half of
                // 3DRS that was missing.**
                //
                // The note above defended reading at vUV: a pan makes the field
                // nearly uniform, so a neighbouring position held the same
                // vector, and where an object moves independently the stale
                // candidate simply loses the vote. Both halves are true and
                // together they are the fault. A pan is the case that was never
                // unstable. An independently moving object is exactly what this
                // candidate exists to steady -- and there, reading at vUV takes
                // the vector of whatever the object has moved ACROSS, so it
                // disagrees with its neighbours and loses every vote, precisely
                // where it was needed.
                //
                // de Haan's temporal candidate is the previous field at the
                // position the content came from. The content now at p sat at
                // p + b one real frame ago, and b is this block's own vector,
                // so this costs one multiply and no extra fetch. Clamped, so
                // off the edge it folds back onto the centre and contributes
                // nothing -- the same as having no history. Zero before the
                // sign latches, which reads at vUV exactly as it used to.
                "vec2 came = clamp(vUV + c[4] * fieldSign * motionScale, 0.0, 1.0);",
                "c[10] = mix(c[4], texture2D(previousTexture, came).rg, temporalValid);",

                // **Where the set already agrees there is nothing to sort, and
                // the comment below says that is most of the field most of the
                // time -- so it is worth asking before paying for the sort
                // rather than after.**
                //
                // If all eleven candidates are the same vector every score is
                // zero, and `best` is seeded with c[4] and beaten only strictly,
                // so c[4] is the answer. Ten subtract-and-adds establish that,
                // against 55 square roots to reach it the long way. This is the
                // same early-out, and the same 1e-7, that InterpolateMaterial
                // uses to skip its OBMC blend when the four blocks coincide, and
                // the branch is coherent for the same reason: agreement is a
                // property of a region, not of a texel.
                //
                // The threshold cannot cost accuracy. The field is RGBA16F, so
                // at the magnitudes it carries -- pixel counts reaching 150 --
                // one ULP is about 0.06, and even near zero it is 1e-5. Nothing
                // can differ by less than 1e-7 without being bit-identical, so
                // this fires exactly when the long path would have returned
                // c[4] anyway.
                "vec2 agree = abs(c[0] - c[4]) + abs(c[1] - c[4]) + abs(c[2] - c[4])",
                           "+ abs(c[3] - c[4]) + abs(c[5] - c[4]) + abs(c[6] - c[4])",
                           "+ abs(c[7] - c[4]) + abs(c[8] - c[4]) + abs(c[9] - c[4])",
                           "+ abs(c[10] - c[4]);",
                "if (agree.x + agree.y < 1.0e-7) {",
                    "gl_FragColor = vec4(c[4], 0.0, 1.0);",
                    "return;",
                "}",

                // **Each distance once.** The score of a candidate is its total
                // distance to the set, and distance is symmetric, so the 121
                // lengths the unrolled version took are 55: every pair is
                // measured once and credited to both ends.
                // **The previous vector votes more than once.** As one
                // candidate among eleven it could break a tie towards
                // continuity and nothing more: a block with two near-equal
                // answers still picked one this frame and the other the next,
                // and on a flat wall crossed by one object nearly every block
                // is such a block. Counting its vote `temporalWeight` times
                // makes a candidate that agrees with last frame cheaper, so a
                // flip has to be paid for by the neighbours. Measured on a
                // constant pan (tools/frame-bench/temporal2.py): at three,
                // flicker down 6%, field error against the known motion 9.3 to
                // 8.0 px, blocks flipping between real frames 55% to 29%. It
                // is a weight on a vote, not a blend: the winner is still one
                // of the eleven, and where the scene genuinely changed the old
                // vector simply loses three times over.
                "float score[11];",
                "for (int i = 0; i < 11; i++) score[i] = 0.0;",
                "for (int i = 0; i < 11; i++) {",
                    "for (int j = 0; j < 11; j++) {",
                        "if (j > i) {",
                            "float d = length(c[i] - c[j]);",
                            "score[i] += d * (j == 10 ? temporalWeight : 1.0);",
                            "score[j] += d * (i == 10 ? temporalWeight : 1.0);",
                        "}",
                    "}",
                "}",

                // Seeded with the centre and beaten only strictly, so the pass
                // is a no-op wherever the neighbourhood already agrees -- which
                // is most of the field, most of the time -- and ties fall to
                // what the block already said.
                "vec2 best = c[4];",
                "float bestScore = score[4];",
                "for (int i = 0; i < 11; i++) {",
                    "if (score[i] < bestScore) { bestScore = score[i]; best = c[i]; }",
                "}",

                "gl_FragColor = vec4(best, 0.0, 1.0);",
            "}"
        );
    }
}
