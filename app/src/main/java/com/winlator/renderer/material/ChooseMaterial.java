package com.winlator.renderer.material;

/**
 * VESSEL: two motion fields on offer, and each block takes the one that fits it.
 *
 * <h2>Why one field is not enough</h2>
 *
 * <p>The refine pass aims the matcher with a single vector for the whole frame --
 * the mean of the coarse field -- because a camera rotation moves everything
 * together and one number describes it. Parallax breaks that: what is close moves
 * further than what is distant, and a guess that fits the scenery does not fit
 * the railing in front of it.
 *
 * <p>The residual pass absorbs most of the difference, since it still has its
 * whole window available on top of the guess. What it cannot absorb is a guess
 * pulled towards the wrong layer. Measured on the laptop against an exact ground
 * truth, two depths moving 60 and 170 pixels:
 *
 * <pre>
 *   field                        far err   near err   image rms
 *   fine only (bounded)             13.0      172.5       14.53
 *   aimed at one global vector      26.1       46.5       10.54
 *   two hypotheses, per block       12.9       61.6        9.55
 * </pre>
 *
 * <p>Aiming rescues the near layer -- 172 pixels of error to 46 -- and damages
 * the far one, 13 to 26, because the guess was dragged towards the layer that
 * moved most. That damage is the whole reason this pass exists, and it is
 * visible on the device as motion close to the camera juddering while distant
 * scenery is smooth.
 *
 * <h2>What it does</h2>
 *
 * <p>The unaimed field is estimated as well as the aimed one -- a second matcher
 * call, 0.001 ms -- and every block is scored under both, then keeps the better.
 * The aimed field wins wherever the scene moves with the camera, which is most
 * of it; the unaimed field wins where something moves independently and happens
 * to fall within the plain window. Neither is right everywhere, and the point is
 * that neither has to be.
 *
 * <p>Scored on a sixteen-tap subsample of the block rather than all sixty-four.
 * The question is only which of two candidates is better, not what the cost is,
 * and a quarter of the taps ranks them the same way for a quarter of the work.
 *
 * <p>The scoring is the one in {@link SignMaterial}, at the midpoint between the
 * frames, where two hypotheses are furthest apart -- and it uses the same latched
 * sign, so a field that disagrees with the sign is not accidentally preferred for
 * looking better backwards.
 */
public class ChooseMaterial extends ScreenMaterial {
    public final ChooseUniforms chooseUniforms = new ChooseUniforms();

    public static class ChooseUniforms {
        /** The unaimed field. The aimed one is the base {@code screenTexture}. */
        public final Uniform plainTexture = new Uniform("plainTexture");
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** One luma pixel in texture space: 1 / luma size. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** Which way the field points, latched. See SignMaterial. */
        public final Uniform fieldSign = new Uniform("fieldSign");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture;",
            "uniform sampler2D plainTexture;",
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 motionScale;",
            "uniform float fieldSign;",
            "varying vec2 vUV;",

            // Sixteen taps spread over the block, at the midpoint between the
            // frames. See the class note.
            "float cost(vec2 v) {",
                "vec2 b = v * motionScale * fieldSign;",
                "float total = 0.0;",
                "for (int j = 0; j < 4; j++) {",
                    "for (int i = 0; i < 4; i++) {",
                        "vec2 at = vUV + (vec2(float(i), float(j)) - 1.5)",
                                        "* 2.0 * motionScale;",
                        "total += abs(texture2D(lumaNewerTexture,",
                                              "clamp(at - b * 0.5, 0.0, 1.0)).r",
                                   "- texture2D(lumaOlderTexture,",
                                              "clamp(at + b * 0.5, 0.0, 1.0)).r);",
                    "}",
                "}",
                "return total;",
            "}",

            "void main() {",
                "vec2 aimed = texture2D(screenTexture, vUV).rg;",
                "vec2 plain = texture2D(plainTexture, vUV).rg;",
                // Ties go to the aimed field: it is the one with the reach, and a
                // tie means the two explain the block equally well.
                "vec2 winner = cost(plain) < cost(aimed) ? plain : aimed;",
                "gl_FragColor = vec4(winner, 0.0, 1.0);",
            "}"
        );
    }
}
