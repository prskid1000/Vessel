/*
 * VESSEL: GL_QCOM_motion_estimation, which has no Java binding.
 *
 * `android.opengl.GLES20/30/31/32` stop at the core API. This is a vendor
 * extension, so the only way to reach it from the renderer is
 * eglGetProcAddress, and the only place that can call eglGetProcAddress is
 * here.
 *
 * ref and target are R8 luma of the same size, a multiple of the search
 * block; output is RGBA16F sized ref/block, with the motion in pixels from
 * ref to target in R and G, indexed on ref.
 *
 * The sibling GL_QCOM_frame_extrapolation used to be resolved here too. On
 * this device it accepts the call, reports no error and writes an eight-pixel
 * ramp, so nothing calls it and its binding is gone.
 */

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <jni.h>

typedef void (*PFN_glTexEstimateMotionQCOM)(GLuint ref, GLuint target, GLuint output);

static PFN_glTexEstimateMotionQCOM estimate_motion = NULL;

/*
 * Resolve against the current context and say whether it is there. Asked
 * once per EGL context by the caller; eglGetProcAddress is a table lookup.
 */
JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_FrameSynthesizer_resolveMotionEntryPoint(JNIEnv *env, jclass obj) {
    estimate_motion = (PFN_glTexEstimateMotionQCOM)eglGetProcAddress("glTexEstimateMotionQCOM");
    return estimate_motion != NULL ? JNI_TRUE : JNI_FALSE;
}

/* The null check keeps a caller that skipped the resolve from jumping through NULL. */
JNIEXPORT void JNICALL
Java_com_winlator_renderer_FrameSynthesizer_texEstimateMotion(JNIEnv *env, jclass obj, jint ref,
                                                         jint target, jint output) {
    if (estimate_motion == NULL) return;
    estimate_motion((GLuint)ref, (GLuint)target, (GLuint)output);
}
