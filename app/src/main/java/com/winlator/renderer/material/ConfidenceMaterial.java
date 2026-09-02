package com.winlator.renderer.material;

/**
 * VESSEL: one texel that says whether this interval can be interpolated at all.
 *
 * <p>Every shipping frame-rate converter has a whole-frame guard, and this
 * pipeline had none. The device's own dumps (2026-09-02, {@code
 * tools/frame-bench/dump.py}) show what that costs: at a camera cut, on the
 * main menu, and on a flat wall with a single object crossing it, the field is
 * incoherent and the synthesised frame is torn in any design -- the cornice
 * doubled, the beam streaked, the room from before the cut bleeding into the
 * one after. Three of four presented frames were those. A real frame, shown
 * on arrival, is the better output for that interval, and the pipeline already
 * knows how to do that: it is what it does whenever it has no valid field.
 *
 * <p>Two measurements, both over the coarse level so they are ready before
 * any full-resolution work is spent:
 *
 * <ul>
 * <li><b>Agreement</b> -- the fraction of coarse blocks within a tolerance of
 *     the field's trimmed mean, the tolerance growing with the motion over a
 *     floor of one coarse block. On the dumps: clean pans 75 and 87%, the flat
 *     wall 10 and 11%, cuts and menus 1 to 7%.
 * <li><b>Frame difference</b> -- mean absolute luma difference between the two
 *     coarse frames. A hard cut with a flash read 39% agreement, which the
 *     first test alone would pass, and 74 levels of difference, which nothing
 *     that is motion can produce.
 * </ul>
 *
 * <p>Output, RGBA8 so the one-texel readback is the same {@code
 * GL_UNSIGNED_BYTE} read the sign probe has done all along: R agreement,
 * G the trimmed mean's magnitude in full-resolution pixels over 1024, B the
 * frame difference in levels over 255. The thresholds live in
 * {@code FrameSynthesizer}, next to the counter that says how often they fired.
 */
public class ConfidenceMaterial extends ScreenMaterial {
    public final ConfidenceUniforms confidenceUniforms = new ConfidenceUniforms();

    public static class ConfidenceUniforms {
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** The coarse grid's size in texels. */
        public final Uniform coarseSize = new Uniform("coarseSize");
        /** Coarse units to full-resolution units, per axis. */
        public final Uniform coarseFactor = new Uniform("coarseFactor");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // The coarse field.
            "uniform sampler2D screenTexture;",
            // The coarse luma pair.
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 coarseSize;",
            "uniform vec2 coarseFactor;",
            "varying vec2 vUV;",

            // GLSL ES 1.00 wants constant loop bounds; 128x128 covers a coarse
            // grid for any guest this pipeline will see, and the early exit
            // keeps the cost at the grid's real size.
            "#define LIMIT 128",
            "#define TAPS 24",

            "void main() {",
                "vec2 texel = 1.0 / coarseSize;",
                "vec2 sum = vec2(0.0);",
                "float n = 0.0;",
                "for (int y = 0; y < LIMIT; y++) {",
                    "if (float(y) >= coarseSize.y) break;",
                    "for (int x = 0; x < LIMIT; x++) {",
                        "if (float(x) >= coarseSize.x) break;",
                        "sum += texture2D(screenTexture, (vec2(float(x), float(y)) + 0.5) * texel).rg;",
                        "n += 1.0;",
                    "}",
                "}",
                "vec2 mean = sum / max(n, 1.0);",
                "float tol = max(4.0, 0.2 * length(mean));",
                "vec2 keep = vec2(0.0);",
                "float kept = 0.0;",
                "for (int y = 0; y < LIMIT; y++) {",
                    "if (float(y) >= coarseSize.y) break;",
                    "for (int x = 0; x < LIMIT; x++) {",
                        "if (float(x) >= coarseSize.x) break;",
                        "vec2 v = texture2D(screenTexture, (vec2(float(x), float(y)) + 0.5) * texel).rg;",
                        "if (length(v - mean) <= tol) { keep += v; kept += 1.0; }",
                    "}",
                "}",
                "vec2 centre = kept > 0.0 ? keep / kept : mean;",
                // Agreement is measured about the trimmed centre, not the raw
                // mean an outlier can drag.
                "float agree = 0.0;",
                "for (int y = 0; y < LIMIT; y++) {",
                    "if (float(y) >= coarseSize.y) break;",
                    "for (int x = 0; x < LIMIT; x++) {",
                        "if (float(x) >= coarseSize.x) break;",
                        "vec2 v = texture2D(screenTexture, (vec2(float(x), float(y)) + 0.5) * texel).rg;",
                        "if (length(v - centre) <= tol) agree += 1.0;",
                    "}",
                "}",
                // Mean luma difference between the two coarse frames on a
                // TAPS x TAPS lattice; the sampler averages each tap's
                // neighbourhood, which is all a cut detector needs.
                "float diff = 0.0;",
                "for (int j = 0; j < TAPS; j++) {",
                    "for (int i = 0; i < TAPS; i++) {",
                        "vec2 p = (vec2(float(i), float(j)) + 0.5) / float(TAPS);",
                        "diff += abs(texture2D(lumaNewerTexture, p).r - texture2D(lumaOlderTexture, p).r);",
                    "}",
                "}",
                "diff /= float(TAPS * TAPS);",
                "gl_FragColor = vec4(agree / max(n, 1.0),",
                                    "clamp(length(centre * coarseFactor) / 1024.0, 0.0, 1.0),",
                                    "diff, 1.0);",
            "}"
        );
    }
}
