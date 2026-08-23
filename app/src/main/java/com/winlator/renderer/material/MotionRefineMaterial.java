package com.winlator.renderer.material;

/**
 * VESSEL: give the motion field the prior it has never had.
 *
 * <h2>What is actually wrong with the field</h2>
 *
 * <p>Six attempts to remove the waviness on straight edges failed, in six
 * different ways: a wider patch for the cost, an iterated median, a global
 * affine fit, scattering the field, per-pixel refinement, and a two-level
 * pyramid. The explanation carried for a while was the aperture problem -- that
 * on a straight edge the motion <em>along</em> the edge is unobservable, so the
 * error is invisible when constant and a wave when it varies.
 *
 * <p><b>That was measured and refuted.</b> With a structure tensor built over a
 * neighbourhood rather than from a single gradient, 19% of the frame is a moving
 * edge, and of those edges 88% disagree along the edge while 81% disagree across
 * it. The aperture reading predicts along far above across. It is a wash: the
 * field is not specifically blind in the unobservable direction, it is noisy in
 * every direction.
 *
 * <p>Which is a different diagnosis with a different fix. We have a world-class
 * data term -- a fixed-function block matcher, 0.001 ms, searching exhaustively
 * -- and <em>no prior at all</em>. Every block is estimated in complete
 * isolation from its neighbours, and at a 66 ms frame gap where displacing a
 * vector by two whole blocks changes the matching cost by less than the margin
 * across 73-95% of the frame, isolated estimates are free to disagree with each
 * other for no reason the picture supports. Neighbouring blocks disagreeing for
 * no reason <em>is</em> the ripple.
 *
 * <h2>Why the five earlier attempts could not have worked</h2>
 *
 * <p>Every one of them was applied <em>after</em> the search: smooth the field
 * the matcher produced, or change how the frame is compensated with it. That
 * cannot recover information the search discarded, and it faces an impossible
 * trade -- smooth hard enough to remove the disagreement and you also destroy
 * the real motion boundaries, which is precisely what turned the median and the
 * affine fit into blur and smearing.
 *
 * <p>The entire consumer television industry solved this problem the other way
 * round, and did it in 1993. de Haan's 3-D Recursive Search keeps a cheap
 * matcher and puts the engineering into <em>which vectors are allowed to win</em>
 * -- a small candidate set drawn from the neighbours that have already been
 * decided, so a tie resolves toward the field the neighbourhood agreed on rather
 * than toward whichever noise the search happened to land in. The regularisation
 * lives inside the search, so it never has to be undone afterwards. The paper is
 * explicit that its advantage only shows up "measured with criteria relevant for
 * the field rate conversion application", which is the same warning our own
 * numbers gave when every quality metric read healthy against a visibly wrong
 * picture.
 *
 * <h2>The one thing that had to change to bring it here</h2>
 *
 * <p><b>3DRS is recursive and a fragment shader is not.</b> Its candidates come
 * from neighbours already processed in scan order, and there is no scan order
 * here -- every block is shaded at once. So the recursion becomes iteration:
 * each pass reads the previous pass's field, and information propagates one
 * block per pass. Three passes carry an agreement three blocks; the field is
 * 160x90, so this is cheap enough to simply do.
 *
 * <p><b>And the scoring had to change, for a reason specific to our data
 * term.</b> Classic 3DRS penalises only its update candidates and leaves the
 * predictors free, because its own matcher is weak and the candidate set is the
 * whole prior. Ours is not weak -- it is an exhaustive hardware search that
 * already minimises exactly the cost we would be re-scoring with. Offer it as a
 * zero-penalty candidate and it wins every block, every pass, and nothing
 * changes at all.
 *
 * <p>So the smoothness is an explicit term in the energy rather than an
 * implication of the candidate list:
 *
 * <pre>    score(v) = SAD(v) + lambda * |v - neighbourMean|</pre>
 *
 * <p>which is the standard regularised formulation, with the candidate set doing
 * what it does in 3DRS -- keeping the search to a handful of vectors the
 * neighbourhood has already proposed -- and lambda doing what the matcher cannot
 * do for itself.
 *
 * <h2>Where lambda comes from</h2>
 *
 * <p>Not a tuned constant, because an absolute one would be wrong in every scene
 * but the one it was fitted to -- the mistake the previous version of this
 * pipeline made five separate times. It is expressed against a quantity measured
 * per block: the cost of doing nothing, {@code SAD(0)}, which is how different
 * the two frames are here before any compensation. Deviating from the
 * neighbourhood by one whole search block then costs a fixed fraction of that,
 * so the term scales itself with local contrast and with how much is happening,
 * and means the same thing in a black tunnel as in daylight.
 *
 * <p>The fraction is the single number left, and it is the honest place to put
 * one: it says how much matching quality a block must buy to be allowed to
 * disagree with its neighbours.
 */
public class MotionRefineMaterial extends ScreenMaterial {
    public final RefineUniforms refineUniforms = new RefineUniforms();

    public static class RefineUniforms {
        /** The field as the hardware matcher produced it, offered every pass. */
        public final Uniform originalTexture = new Uniform("originalTexture");
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** One luma pixel in texture space: 1 / luma size. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** The block grid's dimensions, so a neighbour is one texel away. */
        public final Uniform vectorSize = new Uniform("vectorSize");
        /** One search block in texture space, the unit the penalty is measured in. */
        public final Uniform blockSpan = new Uniform("blockSpan");
        /** Which way the field points. See {@link SignMaterial}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
        /** How much of SAD(0) a full block of disagreement costs. */
        public final Uniform lambda = new Uniform("lambda");
        /** Which update direction this pass offers. See the shader. */
        public final Uniform updateStep = new Uniform("updateStep");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // The field being refined -- the previous pass's output, or the
            // matcher's own field on the first pass.
            "uniform sampler2D screenTexture;",
            "uniform sampler2D originalTexture;",
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 motionScale;",
            "uniform vec2 vectorSize;",
            "uniform vec2 blockSpan;",
            "uniform float fieldSign;",
            "uniform float lambda;",
            "uniform vec2 updateStep;",

            "varying vec2 vUV;",

            // **The cost of a candidate, scored the way the frame will be built.**
            //
            // Sampled symmetrically about this block and blended at the midpoint,
            // because that is exactly what InterpolateMaterial's predict() does at
            // phase 0.5 -- scoring a displacement any other way would optimise for
            // a frame this pipeline never draws. It is also where the two possible
            // signs of the field are furthest apart, which is why SignMaterial
            // scores at the same place.
            //
            // Nine taps in a 3x3 over the block rather than all 64 of an 8x8. The
            // question is which of seven candidates is best, not what the cost is
            // to three decimal places, and a candidate that wins on nine taps and
            // loses on sixty-four is one where the choice did not matter.
            "float sad(vec2 v) {",
                "float sum = 0.0;",
                "for (int j = -1; j <= 1; j++) {",
                    "for (int i = -1; i <= 1; i++) {",
                        "vec2 p = vUV + vec2(float(i), float(j)) * blockSpan * 0.34;",
                        "sum += abs(texture2D(lumaNewerTexture, clamp(p - v * 0.5, 0.0, 1.0)).r",
                                 "- texture2D(lumaOlderTexture, clamp(p + v * 0.5, 0.0, 1.0)).r);",
                    "}",
                "}",
                "return sum;",
            "}",

            "void main() {",
                "vec2 texel = 1.0 / vectorSize;",

                // The candidates, in luma pixels as stored. Four neighbours from
                // the previous pass, this block's own current answer, and the
                // matcher's original -- kept on offer every pass so a block whose
                // neighbours are all wrong can still return to the measurement.
                "vec2 own = texture2D(screenTexture, vUV).rg;",
                "vec2 nl = texture2D(screenTexture, clamp(vUV - vec2(texel.x, 0.0), 0.0, 1.0)).rg;",
                "vec2 nr = texture2D(screenTexture, clamp(vUV + vec2(texel.x, 0.0), 0.0, 1.0)).rg;",
                "vec2 nu = texture2D(screenTexture, clamp(vUV - vec2(0.0, texel.y), 0.0, 1.0)).rg;",
                "vec2 nd = texture2D(screenTexture, clamp(vUV + vec2(0.0, texel.y), 0.0, 1.0)).rg;",
                "vec2 raw = texture2D(originalTexture, vUV).rg;",

                // **What the block is being asked to agree with.** The mean of the
                // four neighbours, not of the eight: the diagonals are two blocks
                // away in the metric that matters and would drag a corner of a
                // moving object toward the background behind it.
                "vec2 mean = (nl + nr + nu + nd) * 0.25;",

                // One update candidate, so the field can reach a vector no
                // neighbour has proposed. Its direction rotates per pass rather
                // than all four being tried at once -- de Haan's own arrangement,
                // and it costs a quarter as much, because propagation carries a
                // useful update to the neighbours on the following pass anyway.
                "vec2 update = own + updateStep;",

                // The scale everything is measured against: how different the two
                // frames are here with no compensation at all. See the class
                // comment -- this is what keeps lambda meaningful in a dark
                // corridor and in daylight alike.
                "float scale = sad(vec2(0.0));",

                // Deviating from the neighbourhood by one search block costs
                // lambda times that. blockSpan is in texture space and the
                // vectors are in luma pixels, so the block's length in the
                // vectors' own units is what the deviation is divided by.
                "float blockPixels = max(1.0, blockSpan.x / max(motionScale.x, 1.0e-8));",
                "float weight = lambda * scale / blockPixels;",

                "vec2 unit = motionScale * fieldSign;",
                "vec2 best = own;",
                "float bestScore = sad(own * unit) + weight * length(own - mean);",

                // Unrolled rather than looped over an array: GLSL ES 1.00 cannot
                // index an array by a loop variable in every driver, and six
                // comparisons written out is clearer than the machinery to avoid
                // writing them out.
                "float s;",
                "s = sad(nl * unit) + weight * length(nl - mean);",
                "if (s < bestScore) { bestScore = s; best = nl; }",
                "s = sad(nr * unit) + weight * length(nr - mean);",
                "if (s < bestScore) { bestScore = s; best = nr; }",
                "s = sad(nu * unit) + weight * length(nu - mean);",
                "if (s < bestScore) { bestScore = s; best = nu; }",
                "s = sad(nd * unit) + weight * length(nd - mean);",
                "if (s < bestScore) { bestScore = s; best = nd; }",
                "s = sad(raw * unit) + weight * length(raw - mean);",
                "if (s < bestScore) { bestScore = s; best = raw; }",
                "s = sad(update * unit) + weight * length(update - mean);",
                "if (s < bestScore) { bestScore = s; best = update; }",

                // B carries how far this block moved from the matcher's answer, so
                // the pipeline can measure whether the refinement is doing anything
                // at all rather than being believed. A is unused and written 1 so
                // the target never holds an alpha that would surprise a reader.
                "gl_FragColor = vec4(best, length(best - raw), 1.0);",
            "}"
        );
    }
}
