package app.vessel.data

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the system graphics driver says about itself.
 *
 * Android exposes no public Vulkan query from Java, so this asks GLES on a 1x1
 * pbuffer — the same Adreno userspace driver answers both, and the renderer and
 * version strings are the two facts the Drivers screen needs in order to say
 * what the phone ships versus what Vessel would install over it.
 *
 * Every field is nullable and nothing is guessed. A probe that fails reports
 * that it failed; the screens print the reason rather than a blank.
 */
@Singleton
class GpuProbe @Inject constructor() {

    suspend fun probe(): SystemGpu = withContext(Dispatchers.IO) { probeBlocking() }

    private fun probeBlocking(): SystemGpu {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            return SystemGpu(error = "no EGL display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            return SystemGpu(error = "eglInitialize failed")
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0) ||
            configCount[0] == 0
        ) {
            EGL14.eglTerminate(display)
            return SystemGpu(error = "no ES2 pbuffer config")
        }

        val context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            return SystemGpu(error = "eglCreateContext failed")
        }

        val surface = EGL14.eglCreatePbufferSurface(
            display,
            configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )

        return try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                SystemGpu(error = "eglMakeCurrent failed")
            } else {
                SystemGpu(
                    renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                    vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                    glVersion = GLES20.glGetString(GLES20.GL_VERSION),
                    eglVersion = "${version[0]}.${version[1]}",
                )
            }
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    /**
     * Whether the Adreno kernel node is there.
     *
     * Turnip talks to the GPU through `/dev/kgsl-3d0`, so its absence is the
     * difference between "the driver did not load" and "the driver cannot load
     * on this kernel". `/dev` is world-traversable, so a plain existence check
     * is the honest answer; anything else would need root.
     */
    fun kgslNode(): DeviceNode = DeviceNode.of("/dev/kgsl-3d0")
}

/** The system graphics driver's own strings, or the reason there are none. */
data class SystemGpu(
    val renderer: String? = null,
    val vendor: String? = null,
    val glVersion: String? = null,
    val eglVersion: String? = null,
    val error: String? = null,
)

/** A device node's presence, reported as three states rather than a boolean. */
enum class DeviceNode(val label: String) {
    PRESENT("present"),
    ABSENT("absent"),
    UNREADABLE("cannot be determined from this process");

    companion object {
        fun of(path: String): DeviceNode = try {
            if (File(path).exists()) PRESENT else ABSENT
        } catch (_: SecurityException) {
            UNREADABLE
        }
    }
}
