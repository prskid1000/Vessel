package com.winlator.renderer.material;

/**
 * VESSEL: which direction does the block matcher's motion actually point?
 *
 * <p>**The spec never says, and getting it backwards inverts every predicted
 * frame.** {@code GL_QCOM_motion_estimation} defines its output as "the
 * estimated motion in pixels" with X in R and Y in G, and says nothing about
 * whether that motion runs from the reference frame to the target or the other
 * way. Video codecs and most block matchers emit the second: for each block in
 * the *target*, the offset to where it came from in the *reference*. Warping
 * forward along a field that points backward moves everything the wrong way and
 * snaps it back on the next real frame, which is judder that looks exactly like
 * a quality problem in the synthesis.
 *
 * <p>It is a coin flip, and it is settleable rather than arguable. Both frames
 * are known, so the field can be tested against the thing it claims to describe:
 * reconstruct the newer frame from the older one under each sign and see which
 * reconstruction is closer. The wrong sign displaces every block by twice its
 * true motion and scores far worse; there is no ambiguity in the answer.
 *
 * <p>Outputs the mean absolute colour error in the red channel, to be averaged by
 * a readback. Rendered small -- the answer is a single number and does not need
 * megapixels to reach.
 */
public class SignTestMaterial extends ScreenMaterial {
    public final Uniforms signUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform previousTexture = new Uniform("previousTexture");
        public final Uniform motionTexture = new Uniform("motionTexture");
        public final Uniform motionScale = new Uniform("motionScale");
        public final Uniform direction = new Uniform("direction");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // The newer of the two real frames.
            "uniform sampler2D screenTexture;",
            // The older one, which the motion field should transform into it.
            "uniform sampler2D previousTexture;",
            "uniform sampler2D motionTexture;",
            // One field unit in texture space: 1 / luma size.
            "uniform vec2 motionScale;",
            // +1 or -1, the hypothesis under test.
            "uniform float direction;",

            "varying vec2 vUV;",

            "void main() {",
                "vec2 motion = texture2D(motionTexture, vUV).rg;",
                "vec2 source = clamp(vUV + direction * motion * motionScale, 0.0, 1.0);",
                "vec3 newer = texture2D(screenTexture, vUV).rgb;",
                "vec3 older = texture2D(previousTexture, source).rgb;",
                "vec3 error = abs(newer - older);",
                "gl_FragColor = vec4((error.r + error.g + error.b) / 3.0, 0.0, 0.0, 1.0);",
            "}"
        );
    }
}
