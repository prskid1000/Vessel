package com.winlator.renderer.material;

/**
 * VESSEL: move one luma frame most of the way onto the other, before measuring.
 *
 * <p>This is what turns a fixed search window into a moving one. {@link
 * DownsampleLumaMaterial} explains why the coarse pass exists; this is the step
 * that spends its answer. The older luma is displaced by the coarse field, so the
 * content it holds lands roughly where the newer frame has it, and the
 * full-resolution matcher that runs next is then measuring a <em>residual</em> --
 * whatever the coarse pass got wrong -- rather than the whole motion. The residual
 * is small by construction, so it fits in the window no matter how fast the scene
 * is moving.
 *
 * <p>The estimator takes no search-centre hint, which is the whole reason for
 * doing it this way: pre-warping the input is how a fixed-window black box is
 * given a starting point. It is the standard construction for coarse-to-fine
 * search and the only one available here.
 *
 * <p><b>{@code direction} runs both ways and both are needed.</b> Forward, this
 * warps the older frame onto the newer one so the forward field can be refined.
 * Backward ({@code -1}), it warps the newer frame onto the older one so the
 * <em>backward</em> field can be refined by the same trick -- and the backward
 * field is what {@link InterpolateMaterial} checks the forward one against to find
 * out which source frame can actually see a given pixel. The same coarse field
 * serves both because a prior only has to be close: whatever it gets wrong, in
 * either direction, is exactly what the residual pass measures.
 *
 * <p>Clamped rather than wrapped at the edges, for the reason every fetch in this
 * pipeline is: content pulled from the opposite side of the frame is not a
 * degraded answer, it is a different part of the scene.
 */
public class WarpLumaMaterial extends ScreenMaterial {
    public final WarpUniforms warpUniforms = new WarpUniforms();

    public static class WarpUniforms {
        /** The coarse field, one vector per coarse block. */
        public final Uniform motionTexture = new Uniform("motionTexture");
        /**
         * Raw coarse-field units to texture space, sign included.
         *
         * <p>{@code fieldSign / coarseSize}: the field is in coarse luma pixels,
         * so dividing by the coarse size is what puts it in UV, and the sign is
         * the one {@code SignMaterial} latched for the extension's convention.
         */
        public final Uniform warpScale = new Uniform("warpScale");
        /** {@code +1} to move the older frame forward, {@code -1} the newer back. */
        public final Uniform direction = new Uniform("direction");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            // The field holds signed pixel counts well outside [-1, 1], and a
            // mediump displacement would quantise the very prior this exists to
            // apply.
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform sampler2D motionTexture;",
            "uniform vec2 warpScale;",
            "uniform float direction;",
            "varying vec2 vUV;",

            "void main() {",
                // Sampled with the coarse field's own filter, which is LINEAR
                // here and NEAREST everywhere else in this pipeline. The rule
                // elsewhere is that a blend between two blocks invents a vector
                // no block voted for, and at a motion boundary that describes
                // nothing in the scene. It does not apply to a prior: one coarse
                // block covers 32 source pixels, a nearest-sampled warp would
                // tear the picture into 32-pixel steps that the residual matcher
                // would then have to explain, and anything the smoothing gets
                // wrong is measured and corrected by that same pass.
                "vec2 v = texture2D(motionTexture, vUV).rg * warpScale * direction;",
                "gl_FragColor = vec4(texture2D(screenTexture,",
                                    "clamp(vUV + v, 0.0, 1.0)).r, 0.0, 0.0, 1.0);",
            "}"
        );
    }
}
