package com.winlator.renderer.material;

/**
 * VESSEL: settle, once for the whole field, which way its vectors point.
 *
 * <p>{@code QCOM_motion_estimation} says its output is "the estimated motion in
 * pixels ... from the &lt;ref&gt; texture to the &lt;target&gt; texture" and never
 * says which of the two images the vector is based at. Those two readings differ
 * by a sign, and a reconstruction test on this device could not separate them:
 * against an uncompensated error of 0.00248 the two hypotheses scored 0.00192 and
 * 0.00182, so the winner won by five per cent while both explained barely a
 * quarter of the difference between the frames.
 *
 * <h2>Why this is a whole pass instead of one line in the interpolation</h2>
 *
 * <p><b>It was one line, and it was the artefact.</b> The interpolation used to
 * settle the sign per pixel -- score both, take the better -- on the reasoning
 * that where motion is real the wrong sign displaces by twice the true motion and
 * loses decisively. That reasoning is sound and the implementation was still
 * wrong, because it is a <em>binary</em> decision made independently at every
 * pixel from a cost that is frequently a near-tie.
 *
 * <p>Where the two costs are close the result flips on image noise, and a flipped
 * sign does not nudge the pixel: it displaces it by twice the vector, in the
 * wrong direction. During a fast camera rotation that is on the order of 128
 * pixels. Neighbouring pixels therefore fetch from unrelated parts of the scene,
 * and in a dark game roughly half of those land in shadow.
 *
 * <p>The result on screen is hard-edged black speckle that eats into exactly the
 * detailed, high-contrast regions -- wood grain, hazard stripes, panel edges --
 * and leaves flat areas alone, because only where there is detail do the two
 * costs differ enough for noise to swing them. Recovered frame by frame from a
 * screen recording during a 360 degree spin: every second frame, the synthesised
 * one, is shredded this way.
 *
 * <h2>Why no measurement caught it</h2>
 *
 * <p>Every diagnostic asked whether the chosen vector explained the pixel well,
 * and the answer was always yes: 100% trusted, 0.24% mean residual, neighbours in
 * agreement. Those numbers are worthless here, because <em>the sign was chosen to
 * minimise exactly that cost</em>. The measurement and the decision optimised the
 * same quantity, so the measurement could only ever agree with the decision. It
 * took looking at the frames.
 *
 * <h2>What this pass does instead</h2>
 *
 * <p>The sign is a property of the field -- of what the driver means by its own
 * output -- and not of any pixel. So it is measured once, over the whole frame,
 * and then latched and passed to the interpolation as a uniform. Every pixel gets
 * the same answer, there is no per-pixel branch left to flip, and the
 * interpolation loses four luma fetches per pixel into the bargain.
 *
 * <p>Each texel scores how strongly it prefers one sign over the other, per
 * axis, and the caller averages those and latches once the evidence is decisive
 * and enough of the frame is actually moving -- a still scene has no opinion,
 * since both signs then fetch the same content.
 *
 * <p><b>Per axis, because the two can disagree.</b> GL texture space runs y
 * upward and image-space block matchers conventionally run y downward, so a
 * field arriving with x correct and y negated is the ordinary outcome of two
 * sensible conventions meeting rather than an exotic failure. A single scalar
 * cannot express that: it has to choose between horizontal and vertical, and the
 * axis it does not choose is displaced backwards. Under a mostly horizontal
 * camera pan the result is light regional distortion, which is quiet enough to
 * survive a long time.
 */
public class SignMaterial extends ScreenMaterial {
    public final SignUniforms signUniforms = new SignUniforms();

    public static class SignUniforms {
        /** The motion field. Bound to the base {@code screenTexture} sampler. */
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** One field unit in texture space: 1 / luma size. */
        public final Uniform motionScale = new Uniform("motionScale");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // The motion field.
            "uniform sampler2D screenTexture;",
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 motionScale;",
            "varying vec2 vUV;",

            // Scored at the midpoint, which is where a synthesised frame sits and
            // so where the two hypotheses are furthest apart.
            "float cost(vec2 b) {",
                "return abs(texture2D(lumaNewerTexture, clamp(vUV - b * 0.5, 0.0, 1.0)).r",
                         "- texture2D(lumaOlderTexture, clamp(vUV + b * 0.5, 0.0, 1.0)).r);",
            "}",

            "void main() {",
                "vec2 v = texture2D(screenTexture, vUV).rg * motionScale;",

                // **Costs are summed and then compared, not compared and then
                // averaged.** This is the third version of this vote and the
                // first that can decide anything.
                //
                // Counting binary votes failed because most pixels are near-ties
                // and a hair's preference counted as much as a certainty.
                // Normalising each pixel's preference before averaging failed the
                // same way for a subtler reason: it gives every pixel equal say,
                // so the overwhelming majority that genuinely cannot tell the two
                // signs apart -- at 64 pixels of displacement through a
                // repetitive corridor, that is most of them -- drown out the few
                // that can. Measured, it read 0.514 and 0.485 with 15% of the
                // probe moving: a dead tie, and both axes ran on unverified
                // defaults for a whole session because of it.
                //
                // Summing the raw costs first fixes exactly that. A pixel that
                // cannot tell the signs apart contributes the same amount to both
                // sums and cancels out of their difference, costing nothing; a
                // pixel that can contributes its full margin. The frame's two
                // totals then differ by the accumulated evidence of only the
                // pixels that had any.
                //
                // Per axis, because the two can disagree: GL texture space runs y
                // upward and image-space block matchers conventionally run y
                // downward, so x correct and y negated is the ordinary outcome of
                // two sensible conventions meeting.
                "float keepX = cost(vec2( v.x, v.y));",
                "float flipX = cost(vec2(-v.x, v.y));",
                "float flipY = cost(vec2( v.x, -v.y));",

                // Scaled so an 8-bit channel holds a useful range of luma
                // differences rather than saturating on the first strong edge.
                "float movingX = step(0.004, abs(v.x));",
                "float movingY = step(0.004, abs(v.y));",

                "gl_FragColor = vec4(",
                    // R,G: the cost of keeping the sign, and of flipping x.
                    "movingX * clamp(keepX * 4.0, 0.0, 1.0),",
                    "movingX * clamp(flipX * 4.0, 0.0, 1.0),",
                    // B: the cost of flipping y, against the same R.
                    "movingY * clamp(flipY * 4.0, 0.0, 1.0),",
                    // A: how much of the probe had an opinion, so the caller can
                    // tell a tie from an empty frame.
                    "max(movingX, movingY));",
            "}"
        );
    }
}
