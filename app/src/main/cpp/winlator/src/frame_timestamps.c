/*
 * VESSEL: when a frame actually reached the panel, from the platform.
 *
 * WHY THIS EXISTS. Every pacing number this project has produced measures the
 * wrong thing. `FrameSynthesizer.notePresented` stamps `System.nanoTime()` on
 * the GL thread at the moment a draw is issued -- which is not when the frame is
 * scanned out, is not even when it is swapped, and is separated from both by a
 * queue whose depth nobody here controls. A whole afternoon went into a "0.2 ms
 * gap between presents" that turned out to be one draw overwriting another
 * before the swap, and the conclusions drawn from every cadence line since have
 * rested on the same foundation.
 *
 * EGL_ANDROID_get_frame_timestamps is the platform answering the question
 * directly. `EGL_DISPLAY_PRESENT_TIME_ANDROID` is the time the display actually
 * put the frame up, measured by the compositor, in the same clock base as
 * CLOCK_MONOTONIC. That is the only number that can say whether two frames
 * shared a scanout.
 *
 * HOW IT IS ASYNCHRONOUS, AND WHY THAT SHAPES THE API. A frame's present time
 * does not exist until the display has presented it, several frames after the
 * swap that produced it. So the caller cannot ask "when did this frame appear"
 * and get an answer in the same breath. It records an id before each swap and
 * polls the old ones later; a poll returns EGL_TIMESTAMP_PENDING_ANDROID until
 * the answer is ready. The Java side owns that bookkeeping -- see
 * FrameTimestamps -- because it is a ring buffer and a loop, which is not worth
 * crossing JNI twice for.
 *
 * WHAT CAN GO WRONG. The extension is optional. Some devices report it and hand
 * back EGL_TIMESTAMP_INVALID_ANDROID for the one field that matters, because the
 * display pipeline cannot see the actual scanout -- notably on virtual displays
 * and some casting paths. Every entry point here answers "no" rather than
 * guessing, and the caller is expected to carry on with its own clock when it
 * gets one.
 */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <jni.h>

/*
 * Declared here rather than taken from the NDK headers.
 *
 * <EGL/eglext.h> only exposes these behind EGL_ANDROID_get_frame_timestamps,
 * which the platform headers gate on an API level the build may target below.
 * The values are from the registry and are stable ABI; a wrong guess would fail
 * the eglSurfaceAttrib below and be reported, not silently misread.
 */
#ifndef EGL_TIMESTAMPS_ANDROID
#define EGL_TIMESTAMPS_ANDROID 0x3430
#endif
#ifndef EGL_DISPLAY_PRESENT_TIME_ANDROID
#define EGL_DISPLAY_PRESENT_TIME_ANDROID 0x343A
#endif

typedef khronos_stime_nanoseconds_t EGLnsecsANDROID_v;

typedef EGLBoolean (*PFN_eglGetNextFrameIdANDROID)(EGLDisplay dpy, EGLSurface surface,
                                                   khronos_uint64_t *frameId);
typedef EGLBoolean (*PFN_eglGetFrameTimestampsANDROID)(EGLDisplay dpy, EGLSurface surface,
                                                       khronos_uint64_t frameId,
                                                       EGLint numTimestamps,
                                                       const EGLint *timestamps,
                                                       EGLnsecsANDROID_v *values);

static PFN_eglGetNextFrameIdANDROID get_next_frame_id = NULL;
static PFN_eglGetFrameTimestampsANDROID get_frame_timestamps = NULL;

/*
 * Ask the driver for the two entry points and switch collection on.
 *
 * Collection is off by default and costs the compositor real bookkeeping per
 * frame, so this is called once, only when a diagnostic asked for it, and the
 * answer decides whether the caller uses these numbers at all.
 *
 * Resolved against the current context like the motion extension is, and for the
 * same reason: the caller asks once per EGL surface, and a pointer belonging to
 * a surface that has since gone is not something to find out mid-frame.
 */
JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_FrameTimestamps_enable(JNIEnv *env, jclass cls) {
    get_next_frame_id =
        (PFN_eglGetNextFrameIdANDROID)eglGetProcAddress("eglGetNextFrameIdANDROID");
    get_frame_timestamps =
        (PFN_eglGetFrameTimestampsANDROID)eglGetProcAddress("eglGetFrameTimestampsANDROID");
    if (get_next_frame_id == NULL || get_frame_timestamps == NULL) return JNI_FALSE;

    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return JNI_FALSE;

    /* Reported by the driver as supported and still refused per surface -- the
     * compositor is entitled to decline, and does on some display paths. */
    if (!eglSurfaceAttrib(dpy, surface, EGL_TIMESTAMPS_ANDROID, EGL_TRUE)) return JNI_FALSE;
    return JNI_TRUE;
}

/*
 * The id of the frame the next swap will produce.
 *
 * Called from inside the draw, before GLSurfaceView swaps, so the id names the
 * frame being drawn now. Zero means the question could not be asked, which the
 * caller treats as "do not record this one" rather than as an error worth
 * reporting every frame.
 */
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_FrameTimestamps_nextFrameId(JNIEnv *env, jclass cls) {
    if (get_next_frame_id == NULL) return 0;
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return 0;

    khronos_uint64_t id = 0;
    if (!get_next_frame_id(dpy, surface, &id)) return 0;
    return (jlong)id;
}

/*
 * When the display actually put that frame up.
 *
 * Returns the CLOCK_MONOTONIC nanosecond stamp, or the extension's own sentinels
 * passed through unchanged: -2 pending (ask again later), -1 invalid (this
 * display can never answer for that frame). Zero means the call itself failed.
 *
 * Only EGL_DISPLAY_PRESENT_TIME_ANDROID is requested. The extension offers ten
 * other stages -- latch time, composition start, reads-done -- and every one of
 * them describes a step on the way rather than the arrival. The question here is
 * whether two frames shared a scanout, and only the presented time answers it.
 */
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_FrameTimestamps_presentTime(JNIEnv *env, jclass cls, jlong frameId) {
    if (get_frame_timestamps == NULL || frameId == 0) return 0;
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return 0;

    const EGLint wanted = EGL_DISPLAY_PRESENT_TIME_ANDROID;
    EGLnsecsANDROID_v value = 0;
    if (!get_frame_timestamps(dpy, surface, (khronos_uint64_t)frameId, 1, &wanted, &value)) {
        return 0;
    }
    return (jlong)value;
}
