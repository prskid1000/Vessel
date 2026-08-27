package com.winlator.renderer.material;

/**
 * VESSEL: add the coarse field back onto the residual the warp left behind.
 *
 * <p>{@link WarpLumaMaterial} displaced one luma frame by the coarse field before
 * the full-resolution matcher ran, so what that matcher returned is the part of
 * the motion the coarse pass missed. The motion itself is the sum, and this is the
 * pass that forms it. Everything downstream -- the median filter, the sign probe,
 * {@link InterpolateMaterial} -- reads the result and neither knows nor needs to
 * know that it was measured in two stages.
 *
 * <p><b>{@code coarseFactor} carries the scale and the direction at once.</b> The
 * coarse field is in coarse luma pixels and the residual is in full ones, so the
 * coarse term is multiplied by the ratio between the two -- four, for a quarter
 * -size pass. It is a vec2 because the two ratios need not be equal: both grids
 * are rounded down to a whole number of search blocks, and 720 rounds differently
 * from 1280. The sign is which way the warp went: positive for the forward field,
 * whose warp moved the older frame forward by the prior, and negative for the
 * backward field, whose warp moved the newer frame back by it. The extension's
 * own sign convention is not applied here and must not be; it cancels between the
 * warp and the residual, and is applied once, downstream, where it always was.
 */
public class MergeFieldMaterial extends ScreenMaterial {
    public final MergeUniforms mergeUniforms = new MergeUniforms();

    public static class MergeUniforms {
        /** The coarse field, sampled up to the full block grid. */
        public final Uniform coarseTexture = new Uniform("coarseTexture");
        /** The ratio between the two grids, signed by the warp's direction. */
        public final Uniform coarseFactor = new Uniform("coarseFactor");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            // Signed pixel counts outside [-1, 1], as everywhere the field is
            // touched.
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform sampler2D coarseTexture;",
            "uniform vec2 coarseFactor;",
            "varying vec2 vUV;",

            "void main() {",
                "vec2 coarse = texture2D(coarseTexture, vUV).rg * coarseFactor;",
                "vec2 residual = texture2D(screenTexture, vUV).rg;",
                "gl_FragColor = vec4(coarse + residual, 0.0, 1.0);",
            "}"
        );
    }
}
