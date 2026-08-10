package com.winlator.renderer.material;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.renderer.GLRenderer;

/**
 * VESSEL: Snapdragon Game Super Resolution 1.0, as the compositor's blit.
 *
 * <p>Not a Winlator file. The whole of this class is Vessel's, and the fragment
 * shader inside it is Qualcomm's — see {@link #SGSR_FRAGMENT_BODY} for the
 * provenance and the exact adaptations. It lives under {@code com.winlator}
 * rather than {@code app.vessel} because it is a {@link ShaderMaterial}, and the
 * renderer that picks it is vendored; putting it anywhere else would make the
 * vendored tree import Vessel's, which
 * {@code app/src/main/java/com/winlator/README.md} says never happens.
 *
 * <p><b>Why it exists.</b> The compositor's only upscale was {@code GL_LINEAR}
 * ({@link com.winlator.renderer.Texture}), so running the guest below the
 * screen's resolution meant accepting a soft picture. SGSR is a single-pass
 * edge-directed spatial upscale written for Adreno, and it is structurally a
 * drop-in for the existing blit: one fragment shader, one extra uniform, no
 * history, no extra target.
 *
 * <p><b>It is off unless it helps</b>, on two independent gates, because a
 * needless shader pass on a phone is a cost with no picture to pay for it:
 *
 * <ul>
 *   <li>{@link #isSupported()} — SGSR needs {@code textureGather}, which is not
 *       in GLSL ES 3.00. The context asks for ES 3 and Adreno answers with 3.2,
 *       but that is a fact about this device and not a guarantee, so it is
 *       measured out of {@code GL_SHADING_LANGUAGE_VERSION} once per context
 *       rather than assumed. Below ES 3.10 the renderer keeps the bilinear
 *       material and nothing else changes.</li>
 *   <li>The caller's magnification test — see
 *       {@code GLRenderer.renderWindowDrawable}. At 1:1 or on a downscale the
 *       bilinear material is used, so this shader is never even bound.</li>
 * </ul>
 */
public class SGSRMaterial extends ShaderMaterial {
    public final Uniforms uniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform xform = new Uniform("xform");
        public final Uniform viewSize = new Uniform("viewSize");
        public final Uniform flipY = new Uniform("flipY");
        /** SGSR's own sampler name, kept so the shader body stays upstream's. */
        public final Uniform texture = new Uniform("ps0");
        /** {@code (1/srcW, 1/srcH, srcW, srcH)} — SGSR's whole notion of scale. */
        public final Uniform viewportInfo = new Uniform("ViewportInfo[0]");
    }

    /**
     * VESSEL: whether this context can run SGSR at all.
     *
     * <p>{@code textureGather} arrived in GLSL ES 3.10; SGSR's own file says
     * {@code #version 300 es}, which is a version that does not have the function
     * it calls. Rather than add {@code #extension GL_EXT_gpu_shader5} and hope,
     * the shading-language version is read back and the shader is compiled at the
     * version the driver actually offers. A driver that answers below 3.10 gets
     * the bilinear path and no compile attempt — {@code compileShaders} throws on
     * failure, and a black desktop is a much worse outcome than a soft one.
     *
     * <p>Cached per EGL context for the same reason every other cache in this
     * package is: leaving the desktop and coming back builds a new one.
     */
    public static boolean isSupported() {
        int current = GLRenderer.contextGeneration();
        if (supportedGeneration != current) {
            supportedGeneration = current;
            supported = !isDisabled() && shadingLanguageAtLeast310();
        }
        return supported;
    }

    /**
     * VESSEL: {@code adb shell setprop log.tag.VesselNoSGSR DEBUG} turns it off.
     *
     * <p>An A/B of an upscaler cannot be done by eye and cannot be done across
     * two builds without the two builds differing in other ways as well. This is
     * the switch that makes "bilinear versus SGSR, same session, same scene" a
     * thing anyone can measure later, and it is the same idiom the window-tree
     * dump already uses ({@code log.tag.VesselWindows}) rather than a new
     * mechanism.
     *
     * <p>Not a setting, deliberately. It has no screen, it is read once per EGL
     * context alongside the capability probe, and leaving the desktop and coming
     * back is what re-reads it — so it costs nothing per frame and cannot be left
     * switched on by a user who does not know what it did.
     */
    private static boolean isDisabled() {
        return Log.isLoggable(DISABLE_TAG, Log.DEBUG);
    }

    /** Off unless someone is measuring. See {@link #isDisabled()}. */
    public static final String DISABLE_TAG = "VesselNoSGSR";

    private static int supportedGeneration = -1;
    private static boolean supported = false;

    /** The reported {@code #version} to compile at, e.g. {@code 320}. */
    private static int shadingLanguageVersion = 0;

    private static boolean shadingLanguageAtLeast310() {
        // "OpenGL ES GLSL ES 3.20" — the two numbers after the last space.
        String version = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION);
        if (version == null) return false;
        int major = 0;
        int minor = 0;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c < '0' || c > '9') continue;
            int j = i;
            while (j < version.length() && version.charAt(j) >= '0' && version.charAt(j) <= '9') j++;
            if (j < version.length() && version.charAt(j) == '.') {
                major = Integer.parseInt(version.substring(i, j));
                int k = j + 1;
                while (k < version.length() && version.charAt(k) >= '0' && version.charAt(k) <= '9') k++;
                minor = Integer.parseInt(version.substring(j + 1, k));
                break;
            }
            i = j;
        }
        // GLSL ES writes 3.20 for what a #version directive spells 320.
        if (minor < 10) minor *= 10;
        shadingLanguageVersion = major * 100 + minor;
        return shadingLanguageVersion >= 310;
    }

    private static String versionDirective() {
        // Compile at what the driver reports rather than at a guess. 310 is the
        // floor isSupported() enforces; anything higher is still valid ES.
        int v = shadingLanguageVersion >= 310 ? shadingLanguageVersion : 310;
        return "#version " + v + " es";
    }

    /**
     * VESSEL: Vessel's vertex stage, feeding Qualcomm's fragment stage.
     *
     * <p>Upstream ships no GLSL vertex shader — the sample drives SGSR from a
     * full-screen triangle in its own engine — so this is
     * {@link WindowMaterial}'s vertex shader translated to ES 3.x {@code in}/
     * {@code out} and widened to the {@code vec4} varying the fragment stage
     * declares. It must stay behaviourally identical to the bilinear path's, or
     * turning SGSR on would move the window as well as sharpen it.
     *
     * <p><b>{@code position} is pinned to attribute location 0 on purpose.</b>
     * {@link com.winlator.renderer.VertexAttribute} resolves its location once
     * per EGL context and reuses it across every program it is bound to — the
     * cursor and window materials already share it and get away with it because
     * a lone attribute lands at 0 in both. Adding a third program that only
     * probably lands at 0 would make that an assumption instead of a fact, and
     * the failure would be vertices read from the wrong array rather than
     * anything that looks like a shader bug.
     */
    @Override
    protected String getVertexShader() {
        return String.join("\n",
            versionDirective(),
            "uniform float xform[6];",
            "uniform vec2 viewSize;",
            "uniform bool flipY;",

            "layout(location = 0) in vec2 position;",
            "out highp vec4 in_TEXCOORD0;",

            "void main() {",
                "in_TEXCOORD0 = vec4(position.x, flipY ? (1.0 - position.y) : position.y, 0.0, 0.0);",
                "vec2 transformedPos = applyXForm(position, xform);",
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return versionDirective() + "\n" + SGSR_FRAGMENT_BODY;
    }

    /**
     * VESSEL: Snapdragon Game Super Resolution 1.0, verbatim but for the four
     * changes listed below.
     *
     * <pre>
     * Upstream  https://github.com/SnapdragonStudios/snapdragon-gsr
     * File      sgsr/v1/include/glsl/sgsr1_shader_mobile.frag
     * Licence   BSD-3-Clause — Copyright (c) 2023, Qualcomm Innovation Center, Inc.
     *           Full text ships as res/raw/license_bsd_sgsr.txt and is listed on
     *           the Licences screen. See docs/LICENSING.md.
     * </pre>
     *
     * <p><b>Not Apache-2.0</b>, which is what this was requested as. The
     * repository's {@code LICENSE} carries
     * {@code SPDX-License-Identifier: BSD-3-Clause}, and clause 1 — "redistributions
     * of source code must retain the above copyright notice" — is why the header
     * below is reproduced rather than summarised.
     *
     * <p><b>What was changed, and why each change was forced:</b>
     *
     * <ol>
     *   <li>The {@code #version} line is supplied by {@link #versionDirective()}
     *       instead of being {@code 300 es}. Upstream's own file calls
     *       {@code textureGather}, which GLSL ES did not have until 3.10, so the
     *       shader as published does not compile against the version it declares
     *       unless the driver quietly allows it.</li>
     *   <li>{@code textureGather(ps0, coord, mode)} became
     *       {@code textureGather(ps0, coord, OperationMode)}. The spec requires
     *       that component argument to be a constant expression, and
     *       {@code mode} is a local variable. Same value, and the only reason
     *       upstream works is constant folding nothing guarantees.</li>
     *   <li>The {@code layout(location=...)} qualifiers on the fragment input and
     *       output are dropped. On a fragment <em>input</em> that qualifier needs
     *       ES 3.1 with separate shader objects; matching by name is what this
     *       program does anyway, since it is linked as one program.</li>
     *   <li>The {@code UseUniformBlock} branch is removed rather than left
     *       {@code #if}-ed out. It names Vulkan descriptor sets
     *       ({@code layout(set=0, binding=0)}), which is not GLSL ES at all.</li>
     * </ol>
     *
     * <p>{@code OperationMode 1} is upstream's own default: RGBA in, luminance
     * carried in the green channel, which is the mode for an ordinary colour
     * window. {@code EdgeThreshold} and {@code EdgeSharpness} are untouched at
     * upstream's {@code 8.0/255.0} and {@code 2.0} — tuning them is a picture
     * decision nobody here has measured, and a changed constant would make "this
     * is SGSR" a weaker claim than it needs to be.
     */
    private static final String SGSR_FRAGMENT_BODY = String.join("\n",
        "//============================================================================================================",
        "//",
        "//",
        "//                  Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.",
        "//                              SPDX-License-Identifier: BSD-3-Clause",
        "//",
        "//============================================================================================================",
        "",
        "precision mediump float;",
        "precision highp int;",
        "",
        "#define OperationMode 1",
        "#define EdgeThreshold 8.0/255.0",
        "#define EdgeSharpness 2.0",
        "",
        "uniform highp vec4 ViewportInfo[1];",
        "uniform mediump sampler2D ps0;",
        "",
        "in highp vec4 in_TEXCOORD0;",
        "out vec4 out_Target0;",
        "",
        "float fastLanczos2(float x)",
        "{",
        "\tfloat wA = x-4.0;",
        "\tfloat wB = x*wA-wA;",
        "\twA *= wA;",
        "\treturn wB*wA;",
        "}",
        "vec2 weightY(float dx, float dy,float c, float std)",
        "{",
        "\tfloat x = ((dx*dx)+(dy* dy))* 0.55 + clamp(abs(c)*std, 0.0, 1.0);",
        "\tfloat w = fastLanczos2(x);",
        "\treturn vec2(w, w * c);",
        "}",
        "",
        "void main()",
        "{",
        "\tint mode = OperationMode;",
        "\tfloat edgeThreshold = EdgeThreshold;",
        "\tfloat edgeSharpness = EdgeSharpness;",
        "",
        "\tvec4 color;",
        "\tif(mode == 1)",
        "\t\tcolor.xyz = textureLod(ps0,in_TEXCOORD0.xy,0.0).xyz;",
        "\telse",
        "\t\tcolor.xyzw = textureLod(ps0,in_TEXCOORD0.xy,0.0).xyzw;",
        "",
        "\thighp float xCenter;",
        "\txCenter = abs(in_TEXCOORD0.x+-0.5);",
        "\thighp float yCenter;",
        "\tyCenter = abs(in_TEXCOORD0.y+-0.5);",
        "",
        "\t//todo: config the SR region based on needs",
        "\t//if ( mode!=4 && xCenter*xCenter+yCenter*yCenter<=0.4 * 0.4)",
        "\tif ( mode!=4)",
        "\t{",
        "\t\thighp vec2 imgCoord = ((in_TEXCOORD0.xy*ViewportInfo[0].zw)+vec2(-0.5,0.5));",
        "\t\thighp vec2 imgCoordPixel = floor(imgCoord);",
        "\t\thighp vec2 coord = (imgCoordPixel*ViewportInfo[0].xy);",
        "\t\tvec2 pl = (imgCoord+(-imgCoordPixel));",
        "\t\tvec4  left = textureGather(ps0,coord, OperationMode);",
        "",
        "\t\tfloat edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y)  + abs(color[mode] - left.z) ;",
        "\t\tif(edgeVote > edgeThreshold)",
        "\t\t{",
        "\t\t\tcoord.x += ViewportInfo[0].x;",
        "",
        "\t\t\tvec4 right = textureGather(ps0,coord + highp vec2(ViewportInfo[0].x, 0.0), OperationMode);",
        "\t\t\tvec4 upDown;",
        "\t\t\tupDown.xy = textureGather(ps0,coord + highp vec2(0.0, -ViewportInfo[0].y),OperationMode).wz;",
        "\t\t\tupDown.zw  = textureGather(ps0,coord+ highp vec2(0.0, ViewportInfo[0].y), OperationMode).yx;",
        "",
        "\t\t\tfloat mean = (left.y+left.z+right.x+right.w)*0.25;",
        "\t\t\tleft = left - vec4(mean);",
        "\t\t\tright = right - vec4(mean);",
        "\t\t\tupDown = upDown - vec4(mean);",
        "\t\t\tcolor.w =color[mode] - mean;",
        "",
        "\t\t\tfloat sum = (((((abs(left.x)+abs(left.y))+abs(left.z))+abs(left.w))+(((abs(right.x)+abs(right.y))+abs(right.z))+abs(right.w)))+(((abs(upDown.x)+abs(upDown.y))+abs(upDown.z))+abs(upDown.w)));",
        "\t\t\tfloat std = 2.181818/sum;",
        "",
        "\t\t\tvec2 aWY = weightY(pl.x, pl.y+1.0, upDown.x,std);",
        "\t\t\taWY += weightY(pl.x-1.0, pl.y+1.0, upDown.y,std);",
        "\t\t\taWY += weightY(pl.x-1.0, pl.y-2.0, upDown.z,std);",
        "\t\t\taWY += weightY(pl.x, pl.y-2.0, upDown.w,std);",
        "\t\t\taWY += weightY(pl.x+1.0, pl.y-1.0, left.x,std);",
        "\t\t\taWY += weightY(pl.x, pl.y-1.0, left.y,std);",
        "\t\t\taWY += weightY(pl.x, pl.y, left.z,std);",
        "\t\t\taWY += weightY(pl.x+1.0, pl.y, left.w,std);",
        "\t\t\taWY += weightY(pl.x-1.0, pl.y-1.0, right.x,std);",
        "\t\t\taWY += weightY(pl.x-2.0, pl.y-1.0, right.y,std);",
        "\t\t\taWY += weightY(pl.x-2.0, pl.y, right.z,std);",
        "\t\t\taWY += weightY(pl.x-1.0, pl.y, right.w,std);",
        "",
        "\t\t\tfloat finalY = aWY.y/aWY.x;",
        "",
        "\t\t\tfloat maxY = max(max(left.y,left.z),max(right.x,right.w));",
        "\t\t\tfloat minY = min(min(left.y,left.z),min(right.x,right.w));",
        "\t\t\tfinalY = clamp(edgeSharpness*finalY, minY, maxY);",
        "",
        "\t\t\tfloat deltaY = finalY -color.w;",
        "",
        "\t\t\t//smooth high contrast input",
        "\t\t\tdeltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);",
        "",
        "\t\t\tcolor.x = clamp((color.x+deltaY),0.0,1.0);",
        "\t\t\tcolor.y = clamp((color.y+deltaY),0.0,1.0);",
        "\t\t\tcolor.z = clamp((color.z+deltaY),0.0,1.0);",
        "\t\t}",
        "\t}",
        "",
        "\tcolor.w = 1.0;  //assume alpha channel is not used",
        "\tout_Target0.xyzw = color;",
        "}"
    );

    /**
     * VESSEL: {@code ViewportInfo[0] = (1/srcW, 1/srcH, srcW, srcH)}.
     *
     * <p>The source is the guest window's own texture, so these are its texel
     * dimensions and not the desktop's. SGSR derives every tap offset from them;
     * getting them from the destination instead would sample the wrong
     * neighbourhood and look like a soft blur with ringing.
     *
     * <p>Named {@code ViewportInfo[0]} and not {@code ViewportInfo}, because a
     * one-element uniform array's location is queried by its indexed name. It
     * goes through {@link ShaderMaterial#setUniformVec4} so it shares the
     * per-program location cache every other uniform here uses.
     */
    public void setSourceSize(float width, float height) {
        setUniformVec4(uniforms.viewportInfo, 1.0f / width, 1.0f / height, width, height);
    }
}
