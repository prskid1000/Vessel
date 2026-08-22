package com.winlator.renderer.material;

/**
 * VESSEL: a frame carried forward along a motion field, by gathering.
 *
 * <p>**Backward warping, and the choice is what removes a whole class of
 * artefact.** Forward warping pushes each source pixel to where it is going,
 * which means two pixels can land on one destination (an overlap needing a
 * priority test and atomics) and no pixel can land on another (a hole needing
 * inpainting). Backward warping asks the opposite question -- for each
 * destination pixel, where did it come from -- so it is one bilinear fetch per
 * output pixel, with no atomics, no collisions and no holes from the warp
 * itself. What it buys instead is smearing where the field is wrong: the shader
 * confidently fetches the wrong pixel rather than admitting it has none.
 *
 * <p>The motion field is sampled with {@code GL_LINEAR} at pixel rate even
 * though it holds one vector per 8x8 block. That bilinear interpolation is a
 * first-order overlapped-block motion compensation and it costs nothing, because
 * the sampler hardware does it -- which is worth stating plainly, since the
 * literature's OBMC is a separate weighted-average pass worth 1-2 dB and this
 * gets most of the same benefit for free. The price is blurring across motion
 * boundaries, where two neighbouring blocks disagree and the interpolation
 * invents a vector between them that describes nothing.
 *
 * <p>{@code motionScale} converts the field's units into texture space and
 * carries the aim: the vectors are in pixels between two real frames, so
 * {@code (t / frameSize)} places the result t of the way along.
 */
public class WarpMaterial extends ScreenMaterial {
    public final Uniforms warpUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform motionTexture = new Uniform("motionTexture");
        public final Uniform motionScale = new Uniform("motionScale");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            "uniform sampler2D screenTexture;",
            "uniform sampler2D motionTexture;",
            // x and y in texture space per unit of the field, already carrying t.
            "uniform vec2 motionScale;",

            "varying vec2 vUV;",

            "void main() {",
                // The field is far lower resolution than the frame; sampling it
                // with GL_LINEAR is what turns per-block vectors into a smooth
                // per-pixel field. See the class comment.
                "vec2 motion = texture2D(motionTexture, vUV).rg;",
                "vec2 source = vUV - motion * motionScale;",
                // Clamped rather than wrapped. A vector pointing off the edge has
                // no source pixel to gather, and repeating the border smears the
                // edge; wrapping would fetch the opposite side of the screen,
                // which is a much louder wrong answer.
                "gl_FragColor = texture2D(screenTexture, clamp(source, 0.0, 1.0));",
            "}"
        );
    }
}
