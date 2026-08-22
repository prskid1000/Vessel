/*
 * VESSEL: GL_QCOM_frame_extrapolation, which has no Java binding.
 *
 * `android.opengl.GLES20/30/31/32` stop at the core API. This entry point is a
 * vendor extension, so the only way to reach it from the renderer is
 * eglGetProcAddress, and the only place that can call eglGetProcAddress is
 * here.
 *
 * WHAT THE EXTENSION IS. Public, in the Khronos registry as
 * extensions/QCOM/QCOM_frame_extrapolation.txt, and implemented by the vendor
 * GLES driver rather than by us -- which is the whole reason it was chosen over
 * the alternatives. Given two rendered frames in sequence it writes a predicted
 * third:
 *
 *     void glExtrapolateTex2DQCOM(GLuint src1, GLuint src2, GLuint output,
 *                                 GLfloat scaleFactor);
 *
 * src1 is the older frame and src2 the newer. scaleFactor 1.0 projects one full
 * time-delta past src2, 0.5 targets the midpoint between them.
 *
 * **Extrapolation, not interpolation, and that is the point.** Interpolating
 * between two real frames means holding the newer one back until the one after
 * it exists, which costs a full frame of latency by construction. Predicting
 * forward costs none: the real frame is presented the moment it is composited
 * and the synthesised one is drawn from frames the user has already seen.
 * The price is paid in accuracy instead -- a prediction can be wrong, and is
 * most wrong where something was hidden and is now revealed.
 *
 * Measured present on this device before any of this was written: Adreno 829,
 * OpenGL ES 3.2, driver V@0842.36, which reports GL_QCOM_frame_extrapolation
 * and GL_QCOM_motion_estimation. Exposure is per driver, not guaranteed by the
 * extension being registered, so FrameExtrapolator still asks before using it.
 */

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <jni.h>

typedef void (*PFN_glExtrapolateTex2DQCOM)(GLuint src1, GLuint src2, GLuint output,
                                           GLfloat scaleFactor);

static PFN_glExtrapolateTex2DQCOM extrapolate_tex_2d = NULL;

/*
 * Resolve the entry point against the current context, and say whether it is
 * there.
 *
 * Re-resolved on every call rather than cached behind a flag, because the
 * caller only asks once per EGL context and a pointer from a context that has
 * since been destroyed is not something to discover in the middle of a frame.
 * eglGetProcAddress is a lookup in the driver's own table, not a dlsym, so
 * asking again is cheap.
 */
JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_FrameExtrapolator_resolveEntryPoint(JNIEnv *env, jclass obj) {
    extrapolate_tex_2d =
        (PFN_glExtrapolateTex2DQCOM)eglGetProcAddress("glExtrapolateTex2DQCOM");
    return extrapolate_tex_2d != NULL ? JNI_TRUE : JNI_FALSE;
}

/*
 * Write the predicted frame into `output`.
 *
 * The null check is not defensive padding: resolveEntryPoint is what sets the
 * pointer, and a caller that skipped it -- or that ran on a driver without the
 * extension -- must get a frame that is merely stale rather than a jump through
 * NULL on the GL thread.
 */
JNIEXPORT void JNICALL
Java_com_winlator_renderer_FrameExtrapolator_extrapolateTex2D(JNIEnv *env, jclass obj, jint src1,
                                                              jint src2, jint output,
                                                              jfloat scaleFactor) {
    if (extrapolate_tex_2d == NULL) return;
    extrapolate_tex_2d((GLuint)src1, (GLuint)src2, (GLuint)output, (GLfloat)scaleFactor);
}
