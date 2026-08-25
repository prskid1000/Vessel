package com.winlator.renderer.material;

/**
 * VESSEL: give a block back its own vector where the neighbourhood was wrong.
 *
 * <h2>The artefact this exists for</h2>
 *
 * <p>Moving the desktop cursor quickly leaves four to six copies of it trailing
 * behind. The cursor is composited into the guest frame before capture -- {@code
 * GLRenderer.renderCursor} runs inside the composite -- so it goes through the
 * matcher and the warp like everything else.
 *
 * <p>{@link MedianMaterial} is what erases it, and it does so by working exactly
 * as designed. Its own reasoning is that "a single 8x8 island pointing somewhere
 * else is the search losing a tie, not an object moving on its own". A cursor is
 * about 32x32 -- sixteen blocks, 0.4% of the field -- on a static desktop where
 * every neighbour correctly reports zero. It <em>is</em> a single island pointing
 * somewhere else, and it is outnumbered eight to one, ten times over. Each pass
 * strips the outermost ring of the cluster, so a 4x4 island is gone in two.
 *
 * <p>Measured on a small bright square crossing a flat background by a known
 * distance, where the object's true vector is known exactly:
 *
 * <pre>
 *   object crosses 160 px, occupying 16 of 3600 blocks (0.4%)
 *     field                        obj vec err  image rms  ghost ink
 *     as the matcher returned it        215.1      10.69      20.73
 *     after 10 median passes            160.0      12.70     155.51
 *     no compensation (blend)               -      12.70     155.51
 * </pre>
 *
 * <p>The last two rows are identical to the digit. A vector error of exactly the
 * displacement means the vector was driven to exactly zero, and with the motion
 * gone the interpolation degenerates into {@code mix(older, newer, phase)} -- a
 * cross-fade holding the object at its old position and its new one at once,
 * with nothing in between. That is the trail.
 *
 * <p>It also explains why nothing derived from the motion field ever predicted
 * where the picture was worst: by the time anything downstream reads the field,
 * the evidence has already been deleted.
 *
 * <h2>Why this is not the confidence test that was removed</h2>
 *
 * <p>{@link InterpolateMaterial} records that scoring candidate vectors and
 * falling back where a test failed was built and taken out, because every "is
 * the winner good?" test passed while the picture was wrong -- the winner really
 * was good, it was simply one of thousands of equally good answers in a
 * repetitive corridor.
 *
 * <p>This is a different question with a decisive answer. It is not a search
 * among ambiguous candidates; it is a two-way comparison between one block's own
 * measurement and what its neighbours overwrote it with. Where an object
 * genuinely moved, its own vector explains its own pixels and the consensus
 * explains them terribly, and the gap is enormous rather than marginal -- the
 * cursor's blocks pass a margin of thirty-two. Where nothing moved differently
 * the two agree and the consensus stands untouched.
 *
 * <h2>The margin is a plateau, not a fitted constant</h2>
 *
 * <p>The requirement is two-sided: restore the object without undoing the
 * waviness suppression the filter exists for, which takes edge waviness from
 * 9.54 px to 0.013 and is the reason ten passes are run at all. Swept against
 * both scenes at once:
 *
 * <pre>
 *   margin  restored%  waviness  ghost 48  ghost 96  ghost 160
 *   none      0.00       0.013      70.36     88.17     155.51
 *   3         0.42       1.093       7.79      1.53      20.73
 *   12        0.06       0.013       7.79      1.53      20.73
 *   16        0.03       0.013       7.79      1.53      20.73
 *   24        0.02       0.013       7.79      1.53      20.73
 *   32        0.02       0.013       7.79      1.53      20.73
 *   48        0.02       0.013      24.64     20.79      32.75
 *   96        0.02       0.013      51.12     64.11     107.35
 * </pre>
 *
 * <p>Everything from 12 to 32 gives the same answer, and it is the best answer
 * available: waviness unchanged at 0.013, ghosting down by up to fifty-eight
 * times. Below 12 the guard starts restoring edge blocks and waviness returns;
 * above 48 it stops recognising the object. Twenty sits in the middle of a
 * plateau nearly three times wide, with room on both sides.
 *
 * <p>An earlier attempt muted flat blocks' votes instead, on the reasoning that
 * a featureless block has not measured anything and should not outvote a block
 * that has. It fixed the cursor and destroyed the edges -- waviness 0.013 to
 * 7.450, because more than half the blocks in that scene are flat and muting
 * them disables the filter across most of the frame. RMS <em>improved</em> while
 * that happened, which is the fourth time in this directory that an average has
 * pointed the wrong way.
 *
 * <p>It touches 0.02% of blocks: about two in three and a half thousand.
 */
public class GuardMaterial extends ScreenMaterial {
    public final GuardUniforms guardUniforms = new GuardUniforms();

    /**
     * How much better a block's own vector must explain its own pixels before it
     * is taken back from the neighbourhood. See the class comment: anything from
     * 12 to 32 measures identically, and this sits in the middle.
     */
    public static final float MARGIN = 20.0f;

    public static class GuardUniforms {
        /** The field as the matcher produced it, before any median pass. */
        public final Uniform originalTexture = new Uniform("originalTexture");
        public final Uniform lumaNewerTexture = new Uniform("lumaNewerTexture");
        public final Uniform lumaOlderTexture = new Uniform("lumaOlderTexture");
        /** One luma pixel, for turning a vector into a texture offset. */
        public final Uniform motionScale = new Uniform("motionScale");
        /** Which way the field points, settled once. See SignMaterial. */
        public final Uniform fieldSign = new Uniform("fieldSign");
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            // The field is RGBA16F and holds signed pixel counts well outside
            // [-1, 1], so mediump would quantise the vectors being compared.
            "precision highp float;",

            "uniform sampler2D screenTexture;",       // the filtered field
            "uniform sampler2D originalTexture;",     // the matcher's own
            "uniform sampler2D lumaNewerTexture;",
            "uniform sampler2D lumaOlderTexture;",
            "uniform vec2 motionScale;",
            "uniform float fieldSign;",
            "varying vec2 vUV;",

            // How badly a vector explains this block, scored the way the frame
            // will actually be built: bilaterally, at the midpoint, which is
            // where a synthesised frame sits and where two hypotheses are
            // furthest apart. Four taps rather than one, because a single texel
            // at the block centre is a sample of noise -- the block is 8x8 and
            // the taps sit at its quarter points.
            "float cost(vec2 v) {",
                "vec2 h = v * 0.5;",
                "float total = 0.0;",
                "for (int i = 0; i < 4; i++) {",
                    "vec2 o = vec2(i == 0 || i == 2 ? -2.0 : 2.0,",
                                  "i < 2 ? -2.0 : 2.0) * motionScale;",
                    "total += abs(texture2D(lumaNewerTexture,",
                                           "clamp(vUV + o - h, 0.0, 1.0)).r",
                                "- texture2D(lumaOlderTexture,",
                                           "clamp(vUV + o + h, 0.0, 1.0)).r);",
                "}",
                "return total * 0.25;",
            "}",

            "void main() {",
                "vec4 kept = texture2D(screenTexture, vUV);",
                "vec2 own = texture2D(originalTexture, vUV).rg;",

                // Both in texture space, and both through the same sign the
                // interpolation will use -- comparing them under a different
                // convention would score two different questions.
                "vec2 scale = motionScale * fieldSign;",
                "float costOwn = cost(own * scale);",
                "float costKept = cost(kept.rg * scale);",

                // Strictly better by the whole margin, so a tie or a marginal
                // win leaves the neighbourhood's answer in place. This is what
                // keeps it off the 99.98% of blocks that were never in dispute.
                // **Blue says "motion is proven here", and InterpolateMaterial
                // reads it.** Restoring the vector is only half the repair: the
                // interpolation then pulls its result back towards an
                // UNCOMPENSATED cross-fade wherever staying put explains the
                // pixel as well as moving does -- the subtitle fix -- and for a
                // small object on a flat background that test cannot tell the
                // two apart. Measured on the cursor scene, the guarded field
                // warps to 0.76 of trailing ink on its own and to 64.67 once
                // that pull-back runs, which is most of the way back to doing
                // nothing at all.
                //
                // Where this pass has fired there is positive evidence that
                // motion explains the block twenty times better than the
                // alternative, so pulling towards stillness is wrong by
                // construction. The flag says so rather than making the
                // interpolation guess again from the same ambiguous numbers.
                "if (costOwn * MARGIN_VALUE < costKept) {",
                    "gl_FragColor = vec4(own, 1.0, 1.0);",
                    "return;",
                "}",
                "gl_FragColor = vec4(kept.rg, 0.0, 1.0);",
            "}"
        ).replace("MARGIN_VALUE", String.format(java.util.Locale.ROOT, "%.1f", MARGIN));
    }
}
