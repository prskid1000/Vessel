package app.vessel.data

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the graphics drivers say about themselves — asked, never assumed.
 *
 * Two questions, and the second is the one this project got wrong for a long
 * time. [probe] asks GLES what the *system* driver is. [vulkanDrivers] asks
 * Vulkan which driver actually answered, both through the platform loader and
 * through libadrenotools with a driver Vessel installed.
 *
 * That second question needs asking because every failure in the custom-driver
 * path is silent. `libvulkan_freedreno.so` exports one symbol, `HMI`, so it can
 * only be reached through Android's Vulkan loader, and only when libadrenotools
 * has hooked `android_dlopen_ext` to point that loader at it. If the hook does
 * not take, the stock Qualcomm blob answers and nothing anywhere says so — a
 * component that is downloaded, installed, listed as present, and inert. So the
 * answer here is read off a real `VkPhysicalDevice`, not inferred from a file
 * being on disk.
 *
 * Every field is nullable and nothing is guessed. A probe that fails reports
 * that it failed; the screens print the reason rather than a blank.
 */
@Singleton
class GpuProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Where libadrenotools and its hook objects live.
     *
     * `applicationInfo.nativeLibraryDir` and nothing else will do: libadrenotools
     * loads `libmain_hook.so` and `libhook_impl.so` from this path by name, and
     * makes it the default library path of the linker namespace the driver is
     * loaded into — which is how the driver's own `libc++_shared.so` resolves.
     * It only names a real directory because `jniLibs.useLegacyPackaging` is on;
     * see `app/build.gradle.kts`.
     */
    val hooksDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    suspend fun probe(): SystemGpu = withContext(Dispatchers.IO) { probeBlocking() }

    /**
     * Which Vulkan driver answers, with and without the installed one.
     *
     * @param driver the installed Turnip package, or null when there is none —
     *   in which case only the system driver is probed and [GpuDrivers.custom]
     *   is null. Resolved by the caller rather than here: this class has no
     *   component store and inventing a path to one would be the fabrication the
     *   rest of this file exists to prevent.
     */
    suspend fun vulkanDrivers(driver: InstalledDriver? = null): GpuDrivers =
        withContext(Dispatchers.IO) {
            val unavailable = libraryError
            if (unavailable != null) {
                val failed = VulkanDriver.failed(VulkanSource.SYSTEM, unavailable)
                return@withContext GpuDrivers(system = failed, custom = null)
            }
            val system = read(VulkanSource.SYSTEM) { nativeProbeSystemVulkan() }
            val custom = driver?.let { probeInstalled(it) }
            GpuDrivers(system = system, custom = custom)
        }

    /**
     * The installed driver, asked the way it can actually be loaded.
     *
     * Two package shapes exist and they need different mechanisms: an ICD is a
     * plain `dlopen`, a HAL can only be reached through libadrenotools. Rather
     * than read the package metadata and decide, this asks the file — exactly
     * what `patches/wine/0009` does on the Wine side, so both answer the same
     * way about the same driver. Falling back only on a failed ICD probe also
     * means a HAL install still reports through the path that suits it.
     *
     * The ICD's failure is discarded when the fallback runs, and deliberately:
     * "this is a HAL build, not an ICD" is not a fault, it is the other case.
     */
    private fun probeInstalled(driver: InstalledDriver): VulkanDriver {
        val library = File(driver.directory, driver.libraryName)
        val icd = read(VulkanSource.ICD) { nativeProbeIcdVulkan(library.absolutePath) }
        if (icd.loaded) return icd
        return read(VulkanSource.ADRENOTOOLS) {
            nativeProbeCustomVulkan(
                hooksDir.absolutePath,
                driver.directory.absolutePath,
                driver.libraryName,
            )
        }
    }

    private inline fun read(source: VulkanSource, probe: () -> Array<String?>?): VulkanDriver =
        runCatching { probe() }
            .fold(
                onSuccess = { VulkanDriver.of(source, it) },
                onFailure = {
                    VulkanDriver.failed(source, it.message ?: it.javaClass.simpleName)
                },
            )

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

    private external fun nativeProbeSystemVulkan(): Array<String?>?

    private external fun nativeProbeIcdVulkan(icdPath: String): Array<String?>?

    private external fun nativeProbeCustomVulkan(
        hooksDir: String,
        driverDir: String,
        driverName: String,
    ): Array<String?>?

    private companion object {
        /**
         * Why `libvesselgpu` could not be loaded, or null when it is there.
         *
         * A load failure is data rather than a crash, because the one screen that
         * would show it is the screen whose job is to explain graphics problems.
         */
        val libraryError: String? = runCatching { System.loadLibrary("vesselgpu") }
            .exceptionOrNull()
            ?.let { "libvesselgpu could not be loaded: ${it.message ?: it.javaClass.simpleName}" }
    }
}

/** An installed GPU driver package, as the two things libadrenotools needs. */
data class InstalledDriver(
    /** The unpacked component directory, e.g. `files/components/Turnip/<version>/`. */
    val directory: File,
    /** `libraryName` from the package's `meta.json`, e.g. `libvulkan_freedreno.so`. */
    val libraryName: String,
)

/** How the Vulkan loader that answered was obtained. */
enum class VulkanSource(val label: String) {
    /** Plain `dlopen("libvulkan.so")` — the phone's own driver. */
    SYSTEM("system"),

    /** `adrenotools_open_libvulkan` with a driver Vessel installed. */
    ADRENOTOOLS("adrenotools"),

    /**
     * A plain `dlopen` of an installed ICD, driven through
     * `vk_icdGetInstanceProcAddr` — the shape Wine uses, and the only one whose
     * window-system integration works. See `docs/GRAPHICS.md`.
     */
    ICD("icd"),
}

/** VkDriverId values worth naming. Kept in step with `vessel_vulkan_driver.h`. */
object VulkanDriverId {
    const val QUALCOMM_PROPRIETARY: Int = 8
    const val MESA_TURNIP: Int = 18
}

/**
 * One driver's answer to `vkGetPhysicalDeviceProperties2`, or why there is none.
 *
 * [loaded] false always comes with an [error]. [isTurnip] is the field callers
 * should branch on: "libadrenotools returned a handle" and "our driver is the
 * one behind it" are different facts, and conflating them is how a stock driver
 * gets reported as a custom one.
 */
data class VulkanDriver(
    val source: VulkanSource,
    val loaded: Boolean,
    val error: String? = null,
    val deviceName: String? = null,
    val driverName: String? = null,
    val driverInfo: String? = null,
    val driverId: Int? = null,
    /** False when the implementation reports no VkPhysicalDeviceDriverProperties. */
    val hasDriverProperties: Boolean = false,
    val apiVersion: String? = null,
    val driverVersion: String? = null,
    val driverVersionRaw: String? = null,
    val vendorId: Int? = null,
    val physicalDevices: Int? = null,
    val isTurnip: Boolean = false,
) {
    /** One line naming the driver, for a list row. */
    val summary: String
        get() = when {
            !loaded -> error ?: "did not load"
            driverName != null && driverName.isNotBlank() ->
                "$driverName · Vulkan ${apiVersion ?: "?"}"
            deviceName != null -> "$deviceName · Vulkan ${apiVersion ?: "?"}"
            else -> "an unnamed driver · Vulkan ${apiVersion ?: "?"}"
        }

    companion object {
        fun failed(source: VulkanSource, reason: String): VulkanDriver =
            VulkanDriver(source = source, loaded = false, error = reason)

        /**
         * Decode the JNI record.
         *
         * The array is a fixed-length contract with `jni_gpu_probe.c`. A short or
         * absent array is reported as a failure rather than throwing, because the
         * only way it happens is a native/Kotlin drift and a crash on the
         * diagnostics screen would be the worst possible place for one.
         */
        fun of(source: VulkanSource, fields: Array<String?>?): VulkanDriver {
            if (fields == null || fields.size < FIELD_COUNT) {
                return failed(
                    source,
                    "the native probe returned ${fields?.size ?: 0} fields, expected $FIELD_COUNT",
                )
            }
            val ok = fields[OK] == "1"
            if (!ok) return failed(source, fields[ERROR].orEmpty().ifBlank { "no reason given" })
            return VulkanDriver(
                source = source,
                loaded = true,
                deviceName = fields[DEVICE_NAME]?.ifBlank { null },
                driverName = fields[DRIVER_NAME]?.ifBlank { null },
                driverInfo = fields[DRIVER_INFO]?.ifBlank { null },
                driverId = fields[DRIVER_ID]?.toIntOrNull(),
                hasDriverProperties = fields[HAS_DRIVER_PROPERTIES] == "1",
                apiVersion = fields[API_VERSION]?.ifBlank { null },
                driverVersion = fields[DRIVER_VERSION]?.ifBlank { null },
                driverVersionRaw = fields[DRIVER_VERSION_RAW]?.ifBlank { null },
                vendorId = fields[VENDOR_ID]?.toIntOrNull(),
                physicalDevices = fields[DEVICE_COUNT]?.toIntOrNull(),
                isTurnip = fields[IS_TURNIP] == "1",
            )
        }

        private const val OK = 0
        private const val ERROR = 2
        private const val DEVICE_NAME = 3
        private const val DRIVER_NAME = 4
        private const val DRIVER_INFO = 5
        private const val DRIVER_ID = 6
        private const val HAS_DRIVER_PROPERTIES = 7
        private const val API_VERSION = 8
        private const val DRIVER_VERSION = 9
        private const val DRIVER_VERSION_RAW = 10
        private const val VENDOR_ID = 11
        private const val DEVICE_COUNT = 12
        private const val IS_TURNIP = 13

        /** Must equal `FIELD_COUNT` in `jni_gpu_probe.c`. */
        const val FIELD_COUNT = 14
    }
}

/**
 * Both answers, and the one sentence that reconciles them.
 *
 * The pair is the point. "Turnip reports Vulkan 1.4.358" means nothing on its
 * own — the stock driver reports 1.4.295 and the two are told apart only by
 * `driverID`. [verdict] is the plain-language version, and it is written to be
 * usable verbatim in a UI, because the alternative is each screen inventing its
 * own wording for "it did not load" and one of them getting it wrong.
 */
data class GpuDrivers(
    val system: VulkanDriver,
    /** Null when no driver package is installed to try. */
    val custom: VulkanDriver?,
) {
    /** True only when Vessel's own driver is what a Vulkan call would reach. */
    val usingVesselDriver: Boolean get() = custom?.isTurnip == true

    val verdict: String
        get() {
            val fallback = system.driverName ?: system.deviceName ?: "the phone's own driver"
            return when {
                custom == null && !system.loaded ->
                    "Vulkan could not be reached at all: ${system.error}"

                custom == null ->
                    "No GPU driver is installed, so Vulkan uses $fallback."

                custom.isTurnip ->
                    "Vessel's driver is in use: ${custom.driverInfo ?: custom.driverName} " +
                        "(Vulkan ${custom.apiVersion}, ${custom.deviceName})."

                custom.loaded ->
                    "The installed driver did NOT load. libadrenotools returned a working " +
                        "Vulkan loader, but driverID ${custom.driverId} " +
                        "(${custom.driverName ?: "unnamed"}) is what answered, so the hook " +
                        "did not take."

                else ->
                    "The installed driver did NOT load — ${custom.error}. " +
                        "Vulkan calls go to $fallback."
            }
        }
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
