package com.winlator.renderer.material;

/**
 * VESSEL: a frame built between two real ones, rather than past the newer.
 *
 * <h2>Why this can be almost right where extrapolation cannot</h2>
 *
 * <p>Extrapolation is asked an unanswerable question. Given frames N-1 and N it
 * must invent what comes after N, and the information for that is in neither
 * input: what is behind an object about to move, what a counter is about to read.
 * No filtering recovers it, because it was never observed -- which is why a
 * median and a consistency test both failed to fix the picture. They were
 * treating a symptom of the method.
 *
 * <p>Interpolation is asked an answerable one. Both endpoints are known, so a
 * frame between them blends two facts rather than guessing past one. A region
 * uncovered during the interval is invisible in N-1 and plainly visible in N, so
 * the very thing that defeats extrapolation is here for the asking.
 *
 * <p>The price is latency, and it is structural rather than a tuning parameter:
 * N cannot be shown until the frames belonging before it have been.
 *
 * <h2>The geometry</h2>
 *
 * <p>The field is backward and measured on N -- for a block at {@code b} in N,
 * the offset {@code v} to where it came from in N-1, so that point sits at
 * {@code a = b + v} in N-1. Travelling linearly, at phase {@code t} it is at
 * {@code a + (b - a)t = b + v(1 - t)}. Reading that backwards, whatever sits at
 * {@code p} in the interpolated frame is at {@code p - v(1 - t)} in N and at
 * {@code p + vt} in N-1.
 *
 * <p>That the field is backward is measured rather than assumed: the extension's
 * spec never states a direction, and a reconstruction test against an
 * uncompensated baseline of 0.00248 scored 0.00192 one way and 0.00182 the
 * other. The same derivation is what FidelityFX uses, sampling the previous
 * backbuffer at {@code uv + mv} and the current at {@code uv - mv}.
 *
 * <p>Both are sampled and blended by {@code t}, which is what makes this
 * bilateral rather than a one-sided warp. Near {@code t = 0} the older frame
 * dominates and near {@code t = 1} the newer does, so each source is trusted
 * most where it is most likely correct, and a region missing from one is usually
 * present in the other. That is the disocclusion handling, and it is why there is
 * no separate hole-filling pass.
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

            // Below this the two sources agree well enough to trust the blend
            // completely; above the second, not at all. Between them the
            // interpolated frame fades back to the newer one rather than
            // switching, so a region crossing the threshold does not pop.
            "#define AGREE_FULL 0.12",
            "#define AGREE_NONE 0.40",

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
                // repeating the border smears, where wrapping would fetch the
                // opposite side of the screen -- a much louder wrong answer.
                "vec2 fromNewer = clamp(vUV - motion * (1.0 - phase), 0.0, 1.0);",
                "vec2 fromOlder = clamp(vUV + motion * phase, 0.0, 1.0);",

                "vec3 newer = texture2D(screenTexture, fromNewer).rgb;",
                "vec3 older = texture2D(previousTexture, fromOlder).rgb;",

                // **Where the two sources disagree, the correspondence is wrong,
                // and the newer frame is the only thing known to be right.**
                //
                // If the field is correct these two fetches are the same point of
                // the same object seen a moment apart, so they should very nearly
                // match. Large disagreement means they are not the same point at
                // all: the block matched something unrelated, or the region was
                // occluded in one frame, or -- the loudest case -- the scene cut
                // and the two frames have nothing to do with each other, which is
                // when the matcher returns meaningless vectors for the whole
                // picture and blending them smears it.
                //
                // This is per pixel rather than a scene-change detector over the
                // whole frame, and deliberately so. A global detector needs the
                // difference summed on the GPU and read back to the CPU every
                // frame, which is a pipeline stall in the middle of the thing that
                // exists to make frames smoother. Judging locally costs a
                // subtraction, catches the same cut, and additionally catches the
                // single wrong block a global test would average away.
                //
                // Falling back to the newer frame rather than to the blend: a
                // repeated real frame is a moment of judder, and a blend of two
                // unrelated images is a visible tear.
                "float disagreement = length(newer - older);",
                "float confidence = 1.0 - smoothstep(AGREE_FULL, AGREE_NONE, disagreement);",
                "vec3 blended = mix(older, newer, phase);",

                "gl_FragColor = vec4(mix(newer, blended, confidence), 1.0);",
            "}"
        );
    }
}
