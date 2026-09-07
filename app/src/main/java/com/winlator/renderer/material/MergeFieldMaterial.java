package com.winlator.renderer.material;

/**
 * VESSEL: add the coarse prior back onto both residuals, into one packed field.
 *
 * <p>{@link WarpLumaMaterial} displaced each luma frame by the coarse field
 * before the fine matcher ran, so each fine field is the part of the motion
 * the prior missed. The motion is the sum. The forward residual gets the prior
 * added, the backward residual gets it subtracted, and the two land in one
 * RGBA16F texture: forward in RG, backward in BA. Everything downstream reads
 * that one texture.
 *
 * <p>{@code coarseFactor} is the ratio between the coarse grid and the full
 * one, per axis, because both grids are rounded down to whole search blocks
 * and 720 rounds differently from 1280. The extension's sign convention is
 * not applied here: it cancels between the warp and the residual, and is
 * applied once, where the field is read.
 */
public class MergeFieldMaterial extends ScreenMaterial {
    public final MergeUniforms mergeUniforms = new MergeUniforms();

    public static class MergeUniforms {
        /** The backward residual; the forward one is {@code screenTexture}. */
        public final Uniform backTexture = new Uniform("backTexture");
        /** The coarse field, sampled up to the full block grid. */
        public final Uniform coarseTexture = new Uniform("coarseTexture");
        /** Coarse units to full units, per axis. */
        public final Uniform coarseFactor = new Uniform("coarseFactor");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture;",
            "uniform sampler2D backTexture;",
            "uniform sampler2D coarseTexture;",
            "uniform vec2 coarseFactor;",
            "varying vec2 vUV;",
            "void main() {",
                "vec2 coarse = texture2D(coarseTexture, vUV).rg * coarseFactor;",
                "vec2 forward = texture2D(screenTexture, vUV).rg + coarse;",
                "vec2 backward = texture2D(backTexture, vUV).rg - coarse;",
                "gl_FragColor = vec4(forward, backward);",
            "}"
        );
    }
}
