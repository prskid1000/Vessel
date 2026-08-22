package com.winlator.renderer.material;

/**
 * VESSEL: a frame built between two real ones, rather than past the newer.
 *
 * <h2>Why this can be almost right where extrapolation cannot</h2>
 *
 * <p>Extrapolation is asked an unanswerable question. Given frames N-1 and N it
 * must invent what comes after N, and the information for that does not exist in
 * either input: what is behind an object about to move, what a counter is about
 * to read. No filtering recovers it, because it was never observed. The error can
 * be made small and cannot be made to vanish.
 *
 * <p>Interpolation is asked an answerable one. Both endpoints are known, so a
 * frame between them is a blend of two facts rather than a guess past one. A
 * region uncovered during the interval is invisible in N-1 and plainly visible in
 * N, so the very thing that defeats extrapolation is available here for the
 * asking.
 *
 * <p>The price is latency, and it is structural: N cannot be shown until the
 * frames that belong before it have been. It is not a tuning parameter and it is
 * not recoverable -- it is what buys the accuracy.
 *
 * <h2>The geometry</h2>
 *
 * <p>The field is backward, measured on N: for a block at {@code x} in N, the
 * offset {@code v} to where it came from in N-1. So the point travels from
 * {@code x + v} at time 0 to {@code x} at time 1, and at time {@code t} it is at
 * {@code x + v(1-t)}. Reading that the other way, whatever sits at {@code p} in
 * the interpolated frame came from {@code p - v(1-t)} in N and from
 * {@code p + vt} in N-1.
 *
 * <p>Both are sampled and blended by {@code t}, which is what makes this
 * bilateral rather than a one-sided warp. Near {@code t = 0} the older frame
 * dominates and near {@code t = 1} the newer does, so each source is trusted
 * most where it is most likely to be correct, and a region missing from one is
 * usually present in the other. That is the disocclusion handling; there is no
 * separate hole-filling pass because the second source is the hole filler.
 */
public class InterpolateMaterial extends ScreenMaterial {
    public final Uniforms interpolateUniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform previousTexture = new Uniform("previousTexture");
        public final Uniform motionTexture = new Uniform("motionTexture");
        public final Uniform motionScale = new Uniform("motionScale");
        public final Uniform phase = new Uniform("phase");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",

            // The newer of the two real frames, N.
            "uniform sampler2D screenTexture;",
            // The older one, N-1.
            "uniform sampler2D previousTexture;",
            "uniform sampler2D motionTexture;",
            // One field unit in texture space: 1 / luma size.
            "uniform vec2 motionScale;",
            // Where between the two frames this one sits: 0 is N-1, 1 is N.
            "uniform float phase;",

            "varying vec2 vUV;",

            "void main() {",
                "vec2 motion = texture2D(motionTexture, vUV).rg * motionScale;",

                // See the class comment for the derivation. Clamped rather than
                // wrapped: a vector pointing off the edge has no source, and
                // repeating the border smears where wrapping would fetch the
                // opposite side of the screen -- a much louder wrong answer.
                "vec2 fromNewer = clamp(vUV - motion * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + motion * phase, 0.0, 1.0);",

                "vec3 newer = texture2D(screenTexture, fromNewer).rgb;",
                "vec3 older = texture2D(previousTexture, fromOlder).rgb;",

                "gl_FragColor = vec4(mix(older, newer, phase), 1.0);",
            "}"
        );
    }
}
