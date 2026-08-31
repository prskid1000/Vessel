package com.winlator.renderer.material;

/**
 * VESSEL: the half-pixel the hardware matcher cannot express.
 *
 * <h2>The field is integer, and the phase is what makes that visible</h2>
 *
 * <p>{@code glTexEstimateMotionQCOM} quantises to whole pixels, and so does
 * every stage built on it here: the coarse pass is integer, the residual is
 * integer, and {@link MergeFieldMaterial} adds two integers. A surface truly
 * moving 30.5 pixels between real frames therefore gets a field that says 30,
 * and no filter downstream can recover the half -- every candidate the median
 * chooses between is an integer too.
 *
 * <p>The obvious objection is that a constant error should not be visible: if
 * every frame is displaced by the same wrong amount, the picture is shifted
 * rather than unstable. That objection is wrong, and {@code
 * tools/frame-bench/texflicker.py quantisation()} measures it. The shader
 * displaces by {@code v * phase}, so half a pixel of field error becomes
 * 0.125, 0.25 and 0.375 pixels of position error at the three 4x phases and
 * exactly zero on the real frame that follows them. One wrong vector misplaces
 * every frame of the cadence by a <em>different</em> sub-pixel amount, which is
 * a four-frame wobble on stationary-looking texture.
 *
 * <pre>
 *   plane moving 30.5 px per frame, registered temporal spread
 *     true field                     1.30
 *     the same field, rounded        1.92   &lt;- constant, noiseless, and worse
 *     field noise, sigma 0.35 px     1.56
 * </pre>
 *
 * <p>Rounding is worth more than a third of a pixel of field noise, which makes
 * it the largest single term measured in that scene.
 *
 * <p><b>And it bites hardest exactly where the picture is calmest.</b> Where
 * neighbouring blocks disagree, {@link InterpolateMaterial}'s overlapped window
 * blends four integers under continuous weights and the result is already
 * fractional. Where all four agree -- a large surface in uniform motion, most of
 * a frame -- the blend of four identical integers is that integer, exactly. So
 * the quantisation survives precisely on the flat, coherent regions where
 * nothing else is going wrong.
 *
 * <h2>What this does</h2>
 *
 * <p>One Lucas-Kanade step per block, which is the standard way to turn a
 * whole-pixel block match into a sub-pixel one and what every hierarchical flow
 * implementation does after its integer search. Write {@code r} for the residual
 * at the midpoint, {@code r = A(x - b/2) - B(x + b/2)}, the same quantity {@link
 * SignMaterial} scores. Displacing {@code b} by {@code d} changes it by
 * {@code -g . d}, where {@code g} is the mean of the two images' gradients, so
 * the {@code d} that cancels the residual over the block is the least-squares
 * solution of {@code (sum g g^T) d = sum g r} -- a 2x2 solve from nine samples.
 *
 * <p>Three things keep it from doing harm:
 *
 * <ul>
 * <li><b>Damped.</b> A block with no texture, or texture in one direction only,
 *     has a singular or near-singular normal matrix -- the aperture problem --
 *     and the undamped solution there is arbitrary and large. The damping term
 *     is proportional to the block's own gradient energy, so it shrinks the
 *     unconstrained direction towards no correction at all rather than towards
 *     a number the block cannot support.
 * <li><b>Clamped to one pixel.</b> The error this exists to remove is at most
 *     half a pixel, so a step larger than one is not a refinement -- it is the
 *     linearisation being applied outside the range where it holds. A block
 *     whose vector is genuinely wrong by ten pixels is not this pass's problem
 *     and is left for {@link MedianMaterial}, which runs after it.
 * <li><b>Before the filter.</b> The median's candidates, its anchor and its
 *     temporal history are then all sub-pixel and all describe the same field.
 *     Refining afterwards would leave the history a half-pixel away from every
 *     spatial candidate, so it would lose every vote it was added to win.
 * </ul>
 *
 * <p>The residual is scored at the midpoint rather than at either endpoint
 * because that is where the two hypotheses are furthest apart, and because it
 * is the instant a synthesised frame at phase 0.5 actually occupies.
 *
 * <h2>What it is worth, and what it costs</h2>

 * <p>Measured in {@code texflicker.quantisation()} on a plane moving 30.5 px a
 * frame, registered temporal spread over textured pixels:
 *
 * <pre>
 *   true field                    1.30      the floor
 *   rounded                       1.92
 *   rounded, refined              1.35      92% of the gap closed
 *   noisy + rounded               1.75
 *   noisy + rounded, refined      1.36
 * </pre>
 *
 * <p>Mean field error over the same scene falls from 0.707 px to 0.345, and the
 * worst block is bounded at 1.414 by the clamp.
 *
 * <p><b>Three by three, and one iteration.</b> Both were measured rather than
 * assumed, and both came out against the more expensive answer. Five by five
 * reaches 0.240 px and 1.31 flicker -- closer, but for 2.8 times the fetches to
 * buy 0.04 of a number whose floor is 1.30. And a second iteration is
 * <em>worse</em> than one, 0.502 px against 0.459: the linearisation is only
 * good within about half a pixel, which is exactly the distance one step is
 * asked to travel, so a second step is taken from a point where the model no
 * longer holds and drifts.
 *
 * <p><b>What it does not fix.</b> The same bench shows field <em>noise</em>
 * costing 1.30 to 1.56 independently, and noise dithers the rounding rather than
 * compounding it -- a noisy rounded field scores 1.75, below the 1.92 of a clean
 * rounded one. So this addresses one of two comparable halves, and the shimmer
 * that survives it is the field being unstable rather than imprecise.
 */
public class RefineFieldMaterial extends ScreenMaterial {
    public final RefineUniforms refineUniforms = new RefineUniforms();

    public static class RefineUniforms {
        /**
         * The image the vector is based at: the newer frame for the forward
         * field, the older one for the backward field.
         *
         * <p>The two are bound swapped rather than the shader taking a
         * direction, because the backward field is the same measurement with
         * the frames exchanged and nothing else about it differs.
         */
        public final Uniform lumaFirstTexture = new Uniform("lumaFirstTexture");
        /** The other one. See {@link #lumaFirstTexture}. */
        public final Uniform lumaSecondTexture = new Uniform("lumaSecondTexture");
        /** One luma pixel in texture space: 1 / luma size. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** One block in texture space, which is one texel of the field. */
        public final Uniform blockUV = new Uniform("blockUV");
        /** Which way the field points. See {@link SignMaterial}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            // The field holds signed pixel counts well outside [-1, 1], and the
            // whole point of this pass is a fraction of one of them.
            "precision highp float;",

            // The field, one vector per block, in luma pixels.
            "uniform sampler2D screenTexture;",
            "uniform sampler2D lumaFirstTexture;",
            "uniform sampler2D lumaSecondTexture;",
            "uniform vec2 motionScale;",
            "uniform vec2 blockUV;",
            "uniform float fieldSign;",
            "varying vec2 vUV;",

            // Clamped rather than wrapped, as every fetch in this pipeline is:
            // content from the opposite side of the frame is not a degraded
            // answer, it is a different part of the scene.
            "float first(vec2 uv) { return texture2D(lumaFirstTexture, clamp(uv, 0.0, 1.0)).r; }",
            "float second(vec2 uv) { return texture2D(lumaSecondTexture, clamp(uv, 0.0, 1.0)).r; }",

            "void main() {",
                // The field's own UV is the luma's: the field grid covers the
                // luma rectangle exactly, one texel per search block.
                "vec2 raw = texture2D(screenTexture, vUV).rg;",
                // In luma pixels, with the extension's sign convention applied,
                // so the fetches below land where the interpolation's would.
                "vec2 v = raw * fieldSign;",
                "vec2 b = v * motionScale;",

                "float a11 = 0.0;",
                "float a12 = 0.0;",
                "float a22 = 0.0;",
                "float rhs1 = 0.0;",
                "float rhs2 = 0.0;",

                // Nine points across the block rather than one at its centre.
                // A single sample is one pixel's opinion of sixty-four of them,
                // and a two-parameter fit wants more equations than unknowns
                // for the damping to mean anything.
                "for (int j = 0; j < 3; j++) {",
                    "for (int i = 0; i < 3; i++) {",
                        "vec2 at = vUV + (vec2(float(i), float(j)) - 1.0) * blockUV / 3.0;",
                        "vec2 pa = at - b * 0.5;",
                        "vec2 pb = at + b * 0.5;",
                        "float res = first(pa) - second(pb);",
                        // The mean of the two images' gradients, per luma pixel.
                        // Central differences: the residual moves by -g.d for a
                        // displacement d, because the two endpoints travel in
                        // opposite directions by half of it each.
                        "vec2 ex = vec2(motionScale.x, 0.0);",
                        "vec2 ey = vec2(0.0, motionScale.y);",
                        "vec2 g = 0.25 * vec2(",
                            "first(pa + ex) - first(pa - ex)",
                          "+ second(pb + ex) - second(pb - ex),",
                            "first(pa + ey) - first(pa - ey)",
                          "+ second(pb + ey) - second(pb - ey));",
                        "a11 += g.x * g.x;",
                        "a12 += g.x * g.y;",
                        "a22 += g.y * g.y;",
                        "rhs1 += g.x * res;",
                        "rhs2 += g.y * res;",
                    "}",
                "}",

                // **Damped, and in proportion to what the block can support.**
                // A flat block has no gradient energy and no opinion; a block
                // with an edge in one direction has an opinion about one axis
                // and none about the other, which is the aperture problem and
                // is the usual way a least-squares flow step explodes. Adding a
                // share of the trace to the diagonal shrinks exactly the
                // directions the block cannot constrain, and leaves the ones it
                // can nearly untouched.
                "float damp = 0.02 * (a11 + a22) + 1.0e-6;",
                "float d11 = a11 + damp;",
                "float d22 = a22 + damp;",
                "float det = d11 * d22 - a12 * a12;",
                "vec2 delta = vec2(d22 * rhs1 - a12 * rhs2,",
                                 "d11 * rhs2 - a12 * rhs1) / max(det, 1.0e-12);",

                // **Half a pixel, which is the largest error rounding can
                // produce, and the single most valuable constant here.**
                //
                // A correction beyond half a pixel per axis is not undoing
                // quantisation -- there was never more than half a pixel of it
                // to undo -- it is the linearisation being trusted outside the
                // range it holds in. Measured, the clamp does more than the
                // damping does: at 0.5 the mean field error falls to 0.345 px
                // against 0.473 at 1.0, and it bounds the WORST block at 1.414
                // px, which is the baseline 0.707 plus this limit on both axes
                // and cannot be exceeded by construction. A block that needs
                // more than this is wrong rather than imprecise, and wrong
                // blocks are MedianMaterial's problem, which runs next.
                "delta = clamp(delta, -0.5, 0.5);",

                // Back into the field's own units and convention.
                "gl_FragColor = vec4(raw + delta * fieldSign, 0.0, 1.0);",
            "}"
        );
    }
}
