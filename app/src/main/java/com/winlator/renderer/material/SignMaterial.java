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
 * <p>Each texel here votes: it reads the field, scores both signs against the
 * luma pair, and writes whether the positive sign won. Averaging the votes over
 * the frame gives the fraction preferring positive, and the caller latches the
 * answer only when the vote is decisive and enough of the frame is actually
 * moving -- a still scene has no opinion, since both signs then fetch the same
 * content.
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

                // Only pixels that are actually moving get a vote. Below about
                // five luma pixels the two signs fetch nearly the same content,
                // the comparison is noise, and counting it would drag any answer
                // towards a tie. This is why the caller also needs to know how
                // much of the frame voted at all.
                "float moving = step(0.004, length(v));",

                // **How far the matcher says things moved, and whether that is
                // as far as it can say.**
                //
                // The search window is bounded -- measured on this device at
                // roughly 113 pixels -- and a camera turning at full speed across
                // a 67 ms gap can move the scene further than that. The matcher
                // then reports the edge of its window rather than the truth, and
                // every interpolated frame built from it is *under*-compensated:
                // the scene lags where it should have moved. Real frame, three
                // lagging frames, real frame is judder, and it appears only at
                // high speed and only at high multiples, because both widen the
                // gap the motion has to cross.
                //
                // Normalised against 128 pixels so the average is readable, and
                // reported alongside the share at the limit -- a mean that stops
                // rising while the camera keeps accelerating is saturation.
                "float pixels = length(texture2D(screenTexture, vUV).rg);",
                "gl_FragColor = vec4(",
                    // R: votes for the positive sign.
                    "moving * step(cost(v), cost(-v)),",
                    // G: votes cast, so the caller can form a fraction rather
                    // than a share of the whole frame.
                    "moving,",
                    // B: how far this block moved, against 256 px.
                    //
                    // **Not 128.** The coarse pass can express motion out to
                    // about 226 pixels, and normalising against 128 clamped
                    // every one of those to the same reading as a vector pinned
                    // at the fine window -- so the one number that was supposed
                    // to show the extension working could not show it. Against
                    // 256 the whole reachable range is distinguishable.
                    "clamp(pixels / 256.0, 0.0, 1.0),",
                    // A: the share of the field describing motion the fine
                    // pass cannot reach, which is where the coarse vectors are
                    // taken. Above the fine window rather than at it: after
                    // substitution these are real measurements, not pins.
                    "step(100.0, pixels));",
            "}"
        );
    }
}
