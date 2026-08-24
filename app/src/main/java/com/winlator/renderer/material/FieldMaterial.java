package com.winlator.renderer.material;

/**
 * VESSEL: the two arithmetic passes the refined field needs, in one shader.
 *
 * <p>Both are one line and neither belongs to anything else, so they share a
 * program and are selected by {@code encode}.
 *
 * <h2>encode &gt; 0: pack the field into eight bits so it can be averaged</h2>
 *
 * <p>The refine step needs one vector for the whole frame, and the only cheap way
 * to reduce a texture to one value on this GPU is {@code glGenerateMipmap}
 * followed by a four-byte read of the 1x1 top -- the same trick every diagnostic
 * here uses, for the same reason: reading a full field back stalls the render
 * thread, and a version of this project that did so caused an ANR.
 *
 * <p>Mipmap averaging needs a filterable, renderable format, which means eight
 * bits per channel, which cannot hold a signed vector. So the vector is mapped
 * affinely into the unit range. Affinely matters: the average of the encoded
 * values is the encoding of the average, so the reduction stays exact and only
 * the quantisation is lost.
 *
 * <p>The range is +-128 in the pass's own pixels. That is chosen against the
 * matcher's window rather than picked: a vector cannot exceed the window it was
 * searched in, measured at about 113 pixels on this driver, so 128 covers
 * everything reachable with a little margin and spends none of the eight bits on
 * values that cannot occur. One code step is a pixel, and the guess only has to
 * land the residual inside the window, so a pixel of quantisation costs nothing.
 *
 * <p><b>The mean is used where a median would be better, and that is a real
 * compromise.</b> A mipmap cannot produce a median, and a few blocks with nothing
 * to match in them will drag the mean. It is tolerable only because the guess is
 * a starting point rather than an answer: the fine pass that follows has its
 * whole window available on top of it, so a guess wrong by tens of pixels still
 * leaves the residual well inside range and the final vector still comes back
 * exact. Measured on a two-layer scene where no single vector fits both depths,
 * the refined field still halved the image error.
 *
 * <h2>encode == 0: add the guess back</h2>
 *
 * <p>After the matcher has measured the residual against a displaced image, the
 * true vector is the displacement plus the residual. This adds it, once, before
 * the median filter runs -- so everything downstream sees an ordinary motion
 * field and needs to know nothing about how it was obtained.
 */
public class FieldMaterial extends ScreenMaterial {
    public final FieldUniforms fieldUniforms = new FieldUniforms();

    public static class FieldUniforms {
        /** Non-zero to pack for averaging; zero to add {@code bias}. */
        public final Uniform encode = new Uniform("encode");
        /** The guess to add back, in the field's own units. */
        public final Uniform bias = new Uniform("bias");
    }

    /** Half the encodable range, in the field's own pixels. See the class note. */
    public static final float RANGE = 128f;

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision highp float;",
            "uniform sampler2D screenTexture;",
            "uniform float encode;",
            "uniform vec2 bias;",
            "varying vec2 vUV;",
            "void main() {",
                "vec2 v = texture2D(screenTexture, vUV).rg;",
                "if (encode > 0.0) {",
                    // Clamped, so a vector past the encodable range folds to the
                    // edge rather than wrapping to the opposite direction.
                    "vec2 e = clamp(v / " + (2f * RANGE) + " + 0.5, 0.0, 1.0);",
                    "gl_FragColor = vec4(e, 0.0, 1.0);",
                "} else {",
                    "gl_FragColor = vec4(v + bias, 0.0, 1.0);",
                "}",
            "}"
        );
    }
}
