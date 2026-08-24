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
 * WHAT THE FIRST VERSION GOT WRONG. It asked only for
 * EGL_DISPLAY_PRESENT_TIME_ANDROID and reported nothing when that came back
 * invalid, which on this device it did for every frame of a whole session.
 * That timestamp is the true scanout time and it is the one most often absent:
 * it comes from the hardware composer's present fence, so a surface the
 * compositor routes through GPU composition, or a display pipeline that does not
 * report fences, simply cannot answer it. The spec says plainly that "not all
 * implementations may support all of the above timestamp queries".
 *
 * The spec also provides the question this code was failing to ask --
 * eglGetFrameTimestampSupportedANDROID -- so support is now established once,
 * per timestamp, and the best answer the surface can actually give is used:
 *
 *   1. DISPLAY_PRESENT_TIME       the real thing: scanout began
 *   2. LAST_COMPOSITION_START     when SurfaceFlinger last composited the frame,
 *                                 plus COMPOSITE_TO_PRESENT_LATENCY, which is
 *                                 the platform's own estimate of the gap between
 *                                 those two events
 *   3. FIRST_COMPOSITION_START    the same, for the first composition of it
 *   4. COMPOSITION_LATCH_TIME     when the buffer was picked up; furthest from
 *                                 the panel and the last resort
 *
 * Only the first is a measurement of the display. The rest are measurements of
 * the compositor with a correction applied, and the caller says which one it got
 * rather than letting a number pass for something it is not.
 */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <jni.h>

/*
 * Declared here rather than taken from the NDK headers.
 *
 * <EGL/eglext.h> gates these behind EGL_ANDROID_get_frame_timestamps, which the
 * platform headers tie to an API level the build may target below. The values
 * are from the registry and are stable ABI; a wrong one would fail the support
 * query below and be reported, not silently misread.
 */
#ifndef EGL_TIMESTAMPS_ANDROID
#define EGL_TIMESTAMPS_ANDROID 0x3430
#endif
#ifndef EGL_COMPOSITE_INTERVAL_ANDROID
#define EGL_COMPOSITE_INTERVAL_ANDROID 0x3432
#endif
#ifndef EGL_COMPOSITE_TO_PRESENT_LATENCY_ANDROID
#define EGL_COMPOSITE_TO_PRESENT_LATENCY_ANDROID 0x3433
#endif
#ifndef EGL_COMPOSITION_LATCH_TIME_ANDROID
#define EGL_COMPOSITION_LATCH_TIME_ANDROID 0x3436
#endif
#ifndef EGL_FIRST_COMPOSITION_START_TIME_ANDROID
#define EGL_FIRST_COMPOSITION_START_TIME_ANDROID 0x3437
#endif
#ifndef EGL_LAST_COMPOSITION_START_TIME_ANDROID
#define EGL_LAST_COMPOSITION_START_TIME_ANDROID 0x3438
#endif
#ifndef EGL_DISPLAY_PRESENT_TIME_ANDROID
#define EGL_DISPLAY_PRESENT_TIME_ANDROID 0x343A
#endif

typedef khronos_stime_nanoseconds_t egl_nsecs;

typedef EGLBoolean (*PFN_next_frame_id)(EGLDisplay, EGLSurface, khronos_uint64_t *);
typedef EGLBoolean (*PFN_frame_timestamps)(EGLDisplay, EGLSurface, khronos_uint64_t,
                                           EGLint, const EGLint *, egl_nsecs *);
typedef EGLBoolean (*PFN_timestamp_supported)(EGLDisplay, EGLSurface, EGLint);
typedef EGLBoolean (*PFN_compositor_timing)(EGLDisplay, EGLSurface, EGLint,
                                            const EGLint *, egl_nsecs *);

static PFN_next_frame_id next_frame_id = NULL;
static PFN_frame_timestamps frame_timestamps = NULL;
static PFN_timestamp_supported timestamp_supported = NULL;
static PFN_compositor_timing compositor_timing = NULL;

static EGLBoolean current(EGLDisplay *dpy, EGLSurface *surface) {
    *dpy = eglGetCurrentDisplay();
    *surface = eglGetCurrentSurface(EGL_DRAW);
    return *dpy != EGL_NO_DISPLAY && *surface != EGL_NO_SURFACE;
}

/*
 * Switch collection on and report which timestamps this surface claims.
 *
 * Returns a bitmask over the four candidates, low bit first, in the order the
 * caller knows. Zero means the extension is missing or the surface refused
 * collection outright.
 *
 * **The claim is not to be believed on its own.** Measured on this device: the
 * support query returns true for EGL_DISPLAY_PRESENT_TIME_ANDROID, and then
 * every frame of a whole session comes back EGL_TIMESTAMP_INVALID_ANDROID. So
 * the caller starts with what is claimed and demotes on what arrives; the
 * choice lives in Java because it depends on the answers rather than only on the
 * question. See FrameTimestamps.
 *
 * Collection costs the compositor real per-frame bookkeeping, so this is called
 * once and only when a diagnostic asked.
 */
JNIEXPORT jint JNICALL
Java_com_winlator_renderer_FrameTimestamps_enable(JNIEnv *env, jclass cls) {
    next_frame_id = (PFN_next_frame_id)eglGetProcAddress("eglGetNextFrameIdANDROID");
    frame_timestamps =
        (PFN_frame_timestamps)eglGetProcAddress("eglGetFrameTimestampsANDROID");
    timestamp_supported =
        (PFN_timestamp_supported)eglGetProcAddress("eglGetFrameTimestampSupportedANDROID");
    compositor_timing =
        (PFN_compositor_timing)eglGetProcAddress("eglGetCompositorTimingANDROID");
    if (next_frame_id == NULL || frame_timestamps == NULL) return 0;

    EGLDisplay dpy;
    EGLSurface surface;
    if (!current(&dpy, &surface)) return 0;

    /* Reported by the driver and still refused per surface -- the compositor is
     * entitled to decline, and does on some display paths. */
    if (!eglSurfaceAttrib(dpy, surface, EGL_TIMESTAMPS_ANDROID, EGL_TRUE)) return 0;

    /* Best first. See the header for what each one actually measures. */
    static const EGLint order[] = {
        EGL_DISPLAY_PRESENT_TIME_ANDROID,
        EGL_LAST_COMPOSITION_START_TIME_ANDROID,
        EGL_FIRST_COMPOSITION_START_TIME_ANDROID,
        EGL_COMPOSITION_LATCH_TIME_ANDROID,
    };
    jint mask = 0;
    for (unsigned i = 0; i < sizeof(order) / sizeof(order[0]); i++) {
        /* No support query means an implementation predating it. The spec's own
         * fallback is to ask and see, so everything is offered and the caller
         * finds out from the answers -- which is what it has to do anyway. */
        if (timestamp_supported == NULL || timestamp_supported(dpy, surface, order[i])) {
            mask |= 1 << i;
        }
    }
    return mask;
}

/*
 * The id of the frame the next swap will produce.
 *
 * Called inside the draw, before GLSurfaceView swaps, so the id names the frame
 * being drawn now. Zero means the question could not be asked, which the caller
 * treats as "do not record this one" rather than as an error worth reporting
 * every frame.
 */
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_FrameTimestamps_nextFrameId(JNIEnv *env, jclass cls) {
    if (next_frame_id == NULL) return 0;
    EGLDisplay dpy;
    EGLSurface surface;
    if (!current(&dpy, &surface)) return 0;

    khronos_uint64_t id = 0;
    if (!next_frame_id(dpy, surface, &id)) return 0;
    return (jlong)id;
}

/*
 * When that frame reached the display, by whichever measure the surface offers.
 *
 * Returns a CLOCK_MONOTONIC nanosecond stamp, or the extension's own sentinels
 * unchanged: -2 pending (ask again later), -1 invalid (this surface can never
 * answer for that frame). Zero means the call itself failed.
 */
JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_FrameTimestamps_presentTime(JNIEnv *env, jclass cls,
                                                       jlong frameId, jint token) {
    if (frame_timestamps == NULL || token == 0 || frameId == 0) return 0;
    EGLDisplay dpy;
    EGLSurface surface;
    if (!current(&dpy, &surface)) return 0;

    const EGLint name = (EGLint)token;
    egl_nsecs value = 0;
    if (!frame_timestamps(dpy, surface, (khronos_uint64_t)frameId, 1, &name, &value)) {
        return 0;
    }
    return (jlong)value;
}

/*
 * The compositor's own timing, for turning a composition time into a present
 * time and for knowing the refresh without asking the Display object.
 *
 * Writes {composite-to-present latency, composite interval} in nanoseconds.
 * Either may come back zero, which the caller treats as "no correction known"
 * rather than as a correction of zero.
 */
JNIEXPORT void JNICALL
Java_com_winlator_renderer_FrameTimestamps_compositorTiming(JNIEnv *env, jclass cls,
                                                            jlongArray out) {
    jlong values[2] = {0, 0};
    if (compositor_timing != NULL) {
        EGLDisplay dpy;
        EGLSurface surface;
        if (current(&dpy, &surface)) {
            static const EGLint names[] = {
                EGL_COMPOSITE_TO_PRESENT_LATENCY_ANDROID,
                EGL_COMPOSITE_INTERVAL_ANDROID,
            };
            egl_nsecs got[2] = {0, 0};
            if (compositor_timing(dpy, surface, 2, names, got)) {
                /* The sentinels apply here too: only a positive value is a time. */
                if (got[0] > 0) values[0] = (jlong)got[0];
                if (got[1] > 0) values[1] = (jlong)got[1];
            }
        }
    }
    (*env)->SetLongArrayRegion(env, out, 0, 2, values);
}
