package com.winlator.renderer.material;

/**
 * VESSEL: copy a luma image displaced by a constant offset.
 *
 * <p><b>This exists to give the fixed-function matcher a search centre it has no
 * parameter for.</b> {@code glTexEstimateMotionQCOM} takes two textures and
 * nothing else: there is no hint, no predictor, no offset. Its window is
 * therefore always centred on zero, and a scene that moved further than the
 * window cannot be measured at all -- the vectors come back pinned at the edge,
 * reporting the same number whatever the truth was.
 *
 * <p>The window cannot be moved, but the image can. Displacing one of the pair
 * by the motion already known from a coarser pass leaves only the residual
 * between them, and the residual is small enough to sit comfortably inside the
 * window. The matcher then measures that residual at full resolution and full
 * precision, and the true vector is the offset plus what it found.
 *
 * <p>That is the step an ordinary image pyramid takes and that substituting the
 * coarse vector outright skips. The difference is not marginal. Measured on the
 * laptop against an exact ground truth at a 167 px displacement, with the window
 * at 112 px:
 *
 * <pre>
 *   field                          vec err   textured   image rms
 *   fine only (bounded)              150.1      141.4       20.05
 *   coarse vector substituted         92.8       76.3       16.74
 *   coarse offset, fine refine         1.4        0.3        2.62
 *   unbounded full-res search         43.1       19.5       12.71
 * </pre>
 *
 * <p>The refined field beats an unbounded full-resolution search five times
 * over, because it has that search's reach and a fine block's precision at the
 * same time. See {@code tools/frame-bench/pyramid.py}.
 *
 * <p>Sampling is clamped at the edges. The region shifted in from outside has no
 * counterpart in the other image, so the matcher finds nothing there and the
 * blocks concerned fall back to whatever the median filter's neighbours say --
 * which is the correct outcome, since there is genuinely no information about
 * them.
 */
public class ShiftMaterial extends ScreenMaterial {
    public final ShiftUniforms shiftUniforms = new ShiftUniforms();

    public static class ShiftUniforms {
        /** The displacement, in texture units: pixels divided by the image size. */
        public final Uniform offset = new Uniform("offset");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture;",
            "uniform vec2 offset;",
            "varying vec2 vUV;",
            "void main() {",
                "gl_FragColor = texture2D(screenTexture, clamp(vUV + offset, 0.0, 1.0));",
            "}"
        );
    }
}
