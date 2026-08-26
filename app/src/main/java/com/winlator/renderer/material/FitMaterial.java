package com.winlator.renderer.material;

/**
 * VESSEL: ask each block how well its own vector explains its own pixels.
 *
 * <p>{@link InterpolateMaterial} blends the four block vectors around a pixel
 * under a raised-cosine window -- "nothing is discarded and nothing is chosen".
 * That window depends only on where the pixel sits between block centres, so
 * every one of the four is assumed equally likely to be right. Where the field
 * is smooth they nearly are, and the assumption costs nothing. Where an 8x8
 * block spans the edge of near geometry during a camera rotation it contains
 * two motions, the matcher returns one, and the average of four such vectors
 * matches neither surface.
 *
 * <h2>Why this is the textbook fix and not an invention</h2>
 *
 * <p>Orchard and Sullivan's estimation-theoretic account of OBMC (IEEE
 * Transactions on Image Processing, 1994) treats the neighbouring vectors as
 * <em>different plausible hypotheses for true motion</em> whose weights should
 * minimise mean squared prediction error, not as positions in a window. A fixed
 * window is the degenerate case where every hypothesis is equally probable. The
 * multi-hypothesis frame-interpolation literature supplies the reliability
 * measure too, and it is inverse SAD between the two points the vector
 * connects -- which is what this pass computes.
 *
 * <p>An exponential weighting with a tuned temperature was measured first and
 * beat this on the capture it was developed against while losing on the other.
 * Inverse SAD has nothing to tune, and won on both.
 *
 * <h2>What is measured, and why here rather than in the interpolation</h2>
 *
 * <p>Per block: the SAD its own vector produces over its own pixels, and the SAD
 * the frame's dominant vector produces over the same pixels. Both at the field's
 * resolution -- 160x90 on a 720p guest, four orders of magnitude below the frame
 * -- so the whole thing is free beside the pass it informs.
 *
 * <p>It cannot be done in the interpolation. Deciding per pixel was measured and
 * it raises speckle by 9 to 15%: the hypotheses ARE block vectors, so a choice
 * that varies from one pixel to the next is varying on noise finer than the
 * field itself, which is the failure {@link SignMaterial} documents at length.
 * Deciding at block scale keeps the gain and returns speckle to baseline.
 *
 * <h2>The fifth hypothesis, and why it is the one that matters</h2>
 *
 * <p>The four neighbours are neighbours: they mostly agree, and reweighting them
 * alone is worth 0.1%. The frame's dominant motion is the only genuinely
 * different hypothesis available -- during a yaw it is roughly the far surface,
 * which no purely local set can contain -- and it carries the entire measured
 * gain.
 *
 * <p><b>It is estimated from twenty-five samples rather than reduced over the
 * field.</b> The exact mean is the top mip level of the field texture, but the
 * field is RGBA16F and building a mip chain on a half-float render target every
 * real frame needs two extensions and a texture-state dance. Twenty-five
 * bilinear samples cost twenty-five fetches in a pass already running at 160x90.
 * Scored against the exact reduction the difference is 0.0 points at 4x.
 *
 * <p>Written into B and A so the interpolation can read it back with the fit it
 * is already fetching, instead of sampling the field twenty-five times per
 * pixel. Every texel writes the same pair, which also makes the blur below a
 * no-op on those two channels.
 *
 * <h2>The second pass</h2>
 *
 * <p>{@code blurring} runs the same program as a 3x3 mean over the fit map. A
 * per-block number is piecewise constant, so using it directly puts a weight
 * discontinuity on every block boundary -- blockiness by construction. The
 * neighbourhood is the one {@link MedianMaterial} already walks on this same
 * texture, and it takes the residual speckle back to baseline.
 *
 * <h2>What it buys</h2>
 *
 * <pre>
 *                              2x        4x
 *   overall error           -0.7%     -2.0%
 *   parallax blocks         -6.2%     -3.8%
 *   speckle            below base   below base
 * </pre>
 *
 * <p>Measured against the frames the guest itself drew, on two RE9 captures,
 * with a dense optical flow validated on each clip before any number was drawn
 * from it. The ceiling for this class of method -- an oracle choosing perfectly
 * per block, net of the gain that taking a minimum of five numbers produces
 * whether or not any of them knows anything -- is about 7.5% at 4x, so this
 * takes roughly a third of what is available.
 */
public class FitMaterial extends ScreenMaterial {
    public final FitUniforms fitUniforms = new FitUniforms();

    public static class FitUniforms {
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** One field unit in texture space: 1 / luma size. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** One texel of the vector field, for the blur and the sparse grid. */
        public final Uniform texelSize = new Uniform("texelSize");
        /** Which way the field points, settled once. See {@link SignMaterial}. */
        public final Uniform fieldSign = new Uniform("fieldSign");
        /** 0 measures the field; 1 takes a 3x3 mean of a previous result. */
        public final Uniform blurring = new Uniform("blurring");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // The motion field when measuring; this pass's own output when
            // blurring.
            "uniform sampler2D screenTexture;",
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 motionScale;",
            "uniform vec2 texelSize;",
            "uniform float fieldSign;",
            "uniform float blurring;",
            "varying vec2 vUV;",

            // **Scored at the midpoint, because the fit is computed once per
            // real frame and used at every phase.** The two fetches are always
            // separated by exactly v whatever the phase is; only where that
            // pair sits relative to the pixel moves. The midpoint is the centre
            // of that range and is where the measurement was taken.
            "float sad(vec2 b, vec2 at) {",
                "return abs(texture2D(lumaNewerTexture, clamp(at - b * 0.5, 0.0, 1.0)).r",
                         "- texture2D(lumaOlderTexture, clamp(at + b * 0.5, 0.0, 1.0)).r);",
            "}",

            // Four points inside this block rather than one at its centre. A
            // single sample is one pixel's opinion of eight-by-eight of them,
            // and on a block straddling two surfaces the centre pixel belongs
            // to whichever one happens to cover it.
            "float fitOf(vec2 b) {",
                "vec2 q = motionScale * 2.0;",
                "return 0.25 * (sad(b, vUV + vec2(-q.x, -q.y))",
                             "+ sad(b, vUV + vec2( q.x, -q.y))",
                             "+ sad(b, vUV + vec2(-q.x,  q.y))",
                             "+ sad(b, vUV + vec2( q.x,  q.y)));",
            "}",

            "void main() {",
                "if (blurring > 0.5) {",
                    // A 3x3 mean. The dominant vector in BA is the same in every
                    // texel, so averaging leaves it exactly as it was.
                    "vec4 total = vec4(0.0);",
                    "for (int y = -1; y <= 1; y++) {",
                        "for (int x = -1; x <= 1; x++) {",
                            "total += texture2D(screenTexture,",
                                "clamp(vUV + vec2(float(x), float(y)) * texelSize,",
                                      "0.0, 1.0));",
                        "}",
                    "}",
                    "gl_FragColor = total / 9.0;",
                    "return;",
                "}",

                "vec2 own = texture2D(screenTexture, vUV).rg * motionScale * fieldSign;",

                // Twenty-five samples spread over the whole field. See the class
                // comment for why this rather than a mip reduction.
                "vec2 sum = vec2(0.0);",
                "for (int y = 0; y < 5; y++) {",
                    "for (int x = 0; x < 5; x++) {",
                        "vec2 at = (vec2(float(x), float(y)) + 0.5) / 5.0;",
                        "sum += texture2D(screenTexture, at).rg;",
                    "}",
                "}",
                "vec2 dominant = sum / 25.0 * motionScale * fieldSign;",

                "gl_FragColor = vec4(fitOf(own), fitOf(dominant), dominant);",
            "}"
        );
    }
}
