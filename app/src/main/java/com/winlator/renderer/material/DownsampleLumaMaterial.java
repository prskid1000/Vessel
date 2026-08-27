package com.winlator.renderer.material;

/**
 * VESSEL: a quarter-size copy of the luma, for the coarse pass of the search.
 *
 * <p><b>Why a smaller copy of the same picture is worth a pass.</b> The hardware
 * block matcher searches a window of about 112 pixels and cannot be told where to
 * look. Measured on Requiem the scene moves 122 pixels between real frames -- past
 * the edge of that window -- and a matcher whose answer lies outside its own
 * search does not return a poor vector, it returns one from wherever inside the
 * window happened to correlate best. That is not noise that a filter can remove;
 * it is an answer to a different question.
 *
 * <p>Run the same matcher on a picture a quarter the size and the same motion is
 * 30 pixels, comfortably inside the window. The field that comes back is coarse --
 * one vector per 32 source pixels -- but it is in range, and it is only ever used
 * as a starting point: {@link WarpLumaMaterial} moves the older frame by it, and
 * the full-resolution pass then measures what is left over, which is small by
 * construction. See {@link MergeFieldMaterial} for how the two are added back
 * together.
 *
 * <p>Four taps rather than sixteen. With {@code GL_LINEAR} on the source, a tap
 * placed one source texel diagonally from the centre of each 4x4 region averages
 * a 2x2 for free, so four of them cover the sixteen texels exactly and evenly.
 * Averaging matters here: a point sample of every fourth pixel would alias, and a
 * matcher fed aliased detail matches the aliasing rather than the scene.
 */
public class DownsampleLumaMaterial extends ScreenMaterial {
    public final DownsampleUniforms downsampleUniforms = new DownsampleUniforms();

    public static class DownsampleUniforms {
        /** One texel of the <em>source</em>, which is four times this target. */
        public final Uniform texelSize = new Uniform("texelSize");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision mediump float;",

            "uniform sampler2D screenTexture;",
            "uniform vec2 texelSize;",
            "varying vec2 vUV;",

            "void main() {",
                "float a = texture2D(screenTexture, vUV + vec2(-texelSize.x, -texelSize.y)).r;",
                "float b = texture2D(screenTexture, vUV + vec2( texelSize.x, -texelSize.y)).r;",
                "float c = texture2D(screenTexture, vUV + vec2(-texelSize.x,  texelSize.y)).r;",
                "float d = texture2D(screenTexture, vUV + vec2( texelSize.x,  texelSize.y)).r;",
                "gl_FragColor = vec4((a + b + c + d) * 0.25, 0.0, 0.0, 1.0);",
            "}"
        );
    }
}
