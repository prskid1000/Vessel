package app.vessel.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device capability report, read from the device.
 *
 * Nothing in here is a constant. Every value is `Build`, a system property,
 * a file under `/proc` or `/sys`, or a live graphics probe — which is the point:
 * this screen is what a bug report is pasted from, and a report carrying a value
 * the app assumed rather than read is worse than one with a gap in it. Where a
 * read fails the field says so in its own words and takes [ReportTone.Unknown],
 * so a missing value is never mistaken for a zero.
 *
 * The facts chosen are the ones `docs/ARCHITECTURE.md` argues from: the absence
 * of hardware TSO is why the memory-ordering group exists at all, the page size
 * decides whether a Wine tree loads, and `/dev/ntsync` is the reason Wine sync
 * defaults to esync rather than something faster.
 */
@Singleton
class DeviceReportReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gpu: GpuProbe,
) {
    suspend fun read(): DeviceReport = withContext(Dispatchers.IO) {
        val properties = systemProperties()
        val cpuInfo = readText("/proc/cpuinfo")
        val features = cpuFeatures(cpuInfo)

        DeviceReport(
            sections = listOf(
                ReportSection("Device", deviceFields(properties)),
                ReportSection("CPU", cpuFields()),
                ReportSection("CPU features", featureFields(features)),
                ReportSection("Memory", memoryFields()),
                ReportSection("System", systemFields(properties)),
                ReportSection("Graphics", graphicsFields(gpu.probe())),
                ReportSection("Kernel interfaces", nodeFields()),
            ),
        )
    }

    // — Device ---------------------------------------------------------------

    private fun deviceFields(properties: Map<String, String>) = listOf(
        ReportField("model", modelName()),
        ReportField("device", Build.DEVICE),
        ReportField("board", Build.BOARD),
        ReportField("hardware", Build.HARDWARE),
        // SOC_MANUFACTURER and SOC_MODEL landed in API 31, which is this app's
        // floor, but a vendor is free to leave them UNKNOWN — so the property
        // fallback is not redundant.
        ReportField("soc", socName(properties)),
        ReportField("platform", properties["ro.board.platform"] ?: unread("ro.board.platform")),
        ReportField("abis", Build.SUPPORTED_ABIS.joinToString(" ")),
    )

    /**
     * Manufacturer and model, without saying the manufacturer twice.
     *
     * Verified on this device: `MANUFACTURER` is `motorola` and `MODEL` is
     * `motorola signature`, so the naive concatenation reads "motorola motorola
     * signature" — which looks like a formatting bug in the report and invites
     * doubt about every other row on the screen.
     */
    private fun modelName(): String {
        val vendor = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(vendor, ignoreCase = true)) model else "$vendor $model"
    }

    private fun socName(properties: Map<String, String>): String {
        val vendor = Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN }
        val model = Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }
            ?: properties["ro.soc.model"]
        return listOfNotNull(vendor, model).joinToString(" ").ifBlank {
            unread("Build.SOC_MODEL and ro.soc.model")
        }
    }

    // — CPU ------------------------------------------------------------------

    /**
     * One row per core, at the frequency the kernel will actually let it reach.
     *
     * `cpuinfo_max_freq` rather than `scaling_max_freq`: the first is the silicon
     * ceiling and the second is whatever a thermal or governor policy has left of
     * it this second, and a capability report wants the ceiling.
     */
    private fun cpuFields(): List<ReportField> {
        val cores = File("/sys/devices/system/cpu")
            .listFiles { file -> file.isDirectory && file.name.matches(CPU_DIRECTORY) }
            ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
            .orEmpty()

        if (cores.isEmpty()) {
            return listOf(
                ReportField("cores", Runtime.getRuntime().availableProcessors().toString()),
                ReportField(
                    "max freq",
                    unread("/sys/devices/system/cpu/cpu*/cpufreq"),
                    ReportTone.Unknown,
                ),
            )
        }

        val perCore = cores.map { core ->
            val kHz = readText(File(core, "cpufreq/cpuinfo_max_freq").path)
                ?.trim()
                ?.toLongOrNull()
            ReportField(
                label = core.name,
                value = kHz?.let { gigahertz(it) } ?: unread("cpuinfo_max_freq"),
                tone = if (kHz == null) ReportTone.Unknown else ReportTone.Plain,
            )
        }

        return buildList {
            add(ReportField("cores", "${cores.size} online in sysfs"))
            addAll(perCore)
        }
    }

    private fun gigahertz(kHz: Long): String =
        String.format(Locale.US, "%.2f GHz", kHz / 1_000_000.0)

    /** The `Features` line of `/proc/cpuinfo`, which is the kernel's hwcap list. */
    private fun cpuFeatures(cpuInfo: String?): Set<String>? = cpuInfo
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("Features") }
        ?.substringAfter(':')
        ?.trim()
        ?.split(' ')
        ?.filter { it.isNotBlank() }
        ?.toSet()

    /**
     * The five features this product's tuning actually turns on.
     *
     * `sve2`, `i8mm` and `bf16` say what a translated workload can be lowered to;
     * `lrcpc3` is the cheap acquire/release form FEX leans on. The fifth is the
     * one that is not there, and it gets the note: ARM64 has no hwcap string for
     * `FEAT_TSO`, so its absence from this line is consistent with — not proof of
     * — the on-device finding in ARCHITECTURE.md. Either way the memory-ordering
     * group in the container editor exists because of it.
     */
    private fun featureFields(features: Set<String>?): List<ReportField> {
        if (features == null) {
            return listOf(
                ReportField("features", unread("/proc/cpuinfo"), ReportTone.Unknown),
            )
        }
        val highlights = HIGHLIGHTS.map { (name, note) ->
            val present = name in features
            ReportField(
                label = name,
                value = if (present) "present" else "absent",
                tone = if (present) ReportTone.Present else ReportTone.Absent,
                note = note,
            )
        }
        return highlights + ReportField(
            label = "hwcaps",
            value = features.sorted().joinToString(" "),
            note = "Everything the kernel advertises, verbatim.",
        )
    }

    // — Memory ---------------------------------------------------------------

    private fun memoryFields(): List<ReportField> {
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(it)
        }
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()
        return listOf(
            ReportField("total ram", gibibytes(memory.totalMem)),
            ReportField("available", gibibytes(memory.availMem)),
            ReportField(
                label = "page size",
                value = pageSize?.let { "$it bytes" } ?: unread("_SC_PAGESIZE"),
                tone = if (pageSize == null) ReportTone.Unknown else ReportTone.Plain,
                // A 16 KB kernel changes what a Wine tree can map, so it is not
                // trivia — it is the first thing to check when a build that runs
                // elsewhere refuses to load here.
                note = "Decides how a Wine tree's PE images can be mapped.",
            ),
        )
    }

    private fun gibibytes(bytes: Long): String =
        String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0)

    // — System ---------------------------------------------------------------

    private fun systemFields(properties: Map<String, String>) = listOf(
        ReportField("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
        ReportField("security patch", Build.VERSION.SECURITY_PATCH),
        ReportField("build", Build.DISPLAY),
        kernelField(),
        ReportField(
            label = "vulkan hal",
            value = properties["ro.hardware.vulkan"] ?: unread("ro.hardware.vulkan"),
        ),
        storageField(),
    )

    /**
     * Where containers and unpacked components live.
     *
     * This was a row on the Settings screen, which is gone. It is a fact about
     * how this build is assembled rather than a setting — internal app storage is
     * the only place on Android that may hold files the app is allowed to
     * execute, so a `.wcp` cannot be unpacked anywhere else and there was never a
     * choice to offer. Facts about the build belong in the report that gets
     * pasted into bug threads, which is here.
     */
    private fun storageField() = ReportField(
        label = "storage",
        value = context.filesDir.path,
        note = "Containers and unpacked components. Internal app storage, because it is the " +
            "only location Android lets this app execute files from.",
    )

    /**
     * The kernel, from `/proc/version` where that is allowed and from `uname`
     * where it is not.
     *
     * Verified on this device: SELinux denies `untrusted_app` read on
     * `proc_version`, so the full banner — compiler, build host, build date — is
     * simply not available to an app on Android 16. `os.version` is the release
     * field of `uname`, which the runtime already holds, so the version number
     * survives even though the banner does not. The row says which of the two it
     * is showing rather than letting a shorter string pass for the longer one.
     */
    private fun kernelField(): ReportField {
        val banner = readText("/proc/version")?.trim()
        if (banner != null) return ReportField("kernel", banner)

        val release = System.getProperty("os.version")?.takeUnless { it.isBlank() }
        return ReportField(
            label = "kernel",
            value = release ?: unread("/proc/version and os.version"),
            tone = if (release == null) ReportTone.Unknown else ReportTone.Plain,
            note = "uname release. SELinux denies apps read access to /proc/version on this " +
                "build, so the full kernel banner cannot be included in a report.",
        )
    }

    // — Graphics -------------------------------------------------------------

    private fun graphicsFields(system: SystemGpu): List<ReportField> {
        if (system.error != null) {
            return listOf(
                ReportField("renderer", "probe failed: ${system.error}", ReportTone.Unknown),
            )
        }
        return listOf(
            ReportField("renderer", system.renderer ?: unread("GL_RENDERER")),
            ReportField("vendor", system.vendor ?: unread("GL_VENDOR")),
            ReportField("gl version", system.glVersion ?: unread("GL_VERSION")),
            ReportField("egl", system.eglVersion ?: unread("eglInitialize")),
        )
    }

    // — Kernel interfaces ----------------------------------------------------

    private fun nodeFields(): List<ReportField> {
        val kgsl = gpu.kgslNode()
        val ntsync = gpu.ntsyncNode()
        return listOf(
            ReportField(
                label = "/dev/kgsl-3d0",
                value = kgsl.label,
                tone = kgsl.tone(),
                note = "Turnip talks to the GPU through this node.",
            ),
            ReportField(
                label = "/dev/ntsync",
                value = ntsync.label,
                tone = ntsync.tone(),
                note = "Absent on a stock GKI kernel, which is why Wine sync defaults to esync.",
            ),
        )
    }

    private fun DeviceNode.tone() = when (this) {
        DeviceNode.PRESENT -> ReportTone.Present
        DeviceNode.ABSENT -> ReportTone.Absent
        DeviceNode.UNREADABLE -> ReportTone.Unknown
    }

    // — Sources --------------------------------------------------------------

    /**
     * Every system property in one shot.
     *
     * `getprop` with no argument prints the whole table as `[key]: [value]`,
     * which is one process rather than one per key — and it avoids reflecting
     * into `android.os.SystemProperties`, which is not SDK and is blocked often
     * enough that a report built on it would have holes on some builds.
     */
    private fun systemProperties(): Map<String, String> = runCatching {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val table = process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().mapNotNull { line ->
                val match = PROPERTY_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2]
            }.toMap()
        }
        process.waitFor()
        table
    }.getOrDefault(emptyMap())

    private fun readText(path: String): String? =
        runCatching { File(path).takeIf { it.canRead() }?.readText() }.getOrNull()

    private fun unread(source: String) = "could not read $source"

    private companion object {
        val CPU_DIRECTORY = Regex("cpu\\d+")
        val PROPERTY_LINE = Regex("""\[(.+?)]: \[(.*)]""")

        /** The features worth calling out by name, and why each is worth it. */
        val HIGHLIGHTS = listOf(
            "sve2" to "Wide vector unit a translated workload can be lowered onto.",
            "i8mm" to "Integer matrix multiply, used by 8-bit inference paths.",
            "bf16" to "bfloat16 arithmetic.",
            "lrcpc3" to "The cheapest acquire/release form; FEX's ordering barriers use it.",
            "tso" to "No hwcap advertises FEAT_TSO on ARM64, so absence here is " +
                "consistent with — not proof of — the finding in ARCHITECTURE.md that " +
                "Oryon has no hardware TSO. Every memory-ordering setting exists for this.",
        )
    }
}

/** The whole report, in the order it is shown and copied. */
data class DeviceReport(val sections: List<ReportSection>)

data class ReportSection(val title: String, val fields: List<ReportField>)

/**
 * One fact. [note] is the sentence explaining why the fact is on the screen, and
 * only the rows that earn one carry it.
 */
data class ReportField(
    val label: String,
    val value: String,
    val tone: ReportTone = ReportTone.Plain,
    val note: String? = null,
)

/** How a value is coloured: a fact, a capability we have, one we do not, or a gap. */
enum class ReportTone { Plain, Present, Absent, Unknown }

/** The report as pasteable text, which is the whole reason the screen exists. */
fun DeviceReport.asText(): String = buildString {
    sections.forEach { section ->
        appendLine("## ${section.title}")
        section.fields.forEach { field ->
            appendLine("${field.label.padEnd(16)} ${field.value}")
        }
        appendLine()
    }
}.trimEnd()
