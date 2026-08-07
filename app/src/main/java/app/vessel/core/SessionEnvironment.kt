package app.vessel.core

import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import java.io.File

/**
 * The fixed Wine debug channel set.
 *
 * **Order is load-bearing and this string must not be reformatted** — Wine
 * parses left to right and seeds each newly named channel from `default_flags`
 * as of that moment. Note also that `err` is a class, not a channel: `+err`
 * registers a channel that does not exist.
 *
 * `docs/LOGGING.md` is the source of truth for every term here and why it earns
 * its place. Change that document first.
 */
const val WINEDEBUG_CHANNELS: String = "-all,err+all,warn+module,+winediag,+loaddll"

/**
 * The Direct3D and WGL DLLs that must resolve to the shipped native builds.
 *
 * One list, two consumers with deliberately different modes: the session
 * environment sets them `n` (native only), and [PrefixRegistry] seeds the prefix
 * with `native,builtin` so a prefix launched without the environment still
 * prefers the real thing rather than silently falling back to wined3d.
 *
 * `opengl32` is in the list because a Mesa/Zink `opengl32.dll` replaces WGL, not
 * Direct3D — see the `OpenGL` package type in `build/package_wcp.py`.
 */
val D3D_DLL_OVERRIDES: List<String> = listOf(
    "d3d8", "d3d9", "d3d10core", "d3d11", "d3d12", "d3d12core", "dxgi", "opengl32",
)

/** Turnip's own startup channel, and the ground truth for whether it loaded at all. */
const val TU_DEBUG_STARTUP: String = "startup"

/**
 * `TU_DEBUG` values that mean "force nothing".
 *
 * The manifest expresses "leave the driver to decide" as an option value rather
 * than as an absent key, so that the editor can draw it as one choice among
 * several. Passing it through to Turnip would name a flag that does not exist.
 */
val TU_DEBUG_NO_OP: Set<String> = setOf("default", "none", "auto")

/** `DISPLAY` for the app's built-in X server, which Wine's X11 driver connects to. */
const val DEFAULT_DISPLAY: String = ":0"

/**
 * Variables this layer owns outright, which a manifest param may never set.
 *
 * Most are simply not settings — `WINEDEBUG` is fixed per `docs/LOGGING.md`,
 * `WINEESYNC` because esync is the only synchronisation mode that works here
 * (README, Known limitations).
 *
 * `VKD3D_LOG_FILE` is different: it is listed to guarantee its **absence**.
 * `vkd3d_dbg_init_once` is an if/else — set the variable and it opens the file
 * *instead of* resolving `__wine_dbg_output`, so it moves vkd3d's output off the
 * pipe the session log reads rather than copying it.
 */
val RESERVED_SESSION_ENV: Set<String> = setOf(
    "WINEPREFIX",
    "WINEESYNC",
    "WINEDEBUG",
    "WINEDLLOVERRIDES",
    "DISPLAY",
    "DXVK_LOG_LEVEL",
    "DXVK_LOG_PATH",
    "VKD3D_DEBUG",
    "VKD3D_SHADER_DEBUG",
    "VKD3D_LOG_FILE",
    "TU_DEBUG",
    "ADRENOTOOLS_DRIVER_PATH",
    "ADRENOTOOLS_HOOKS_PATH",
    "ADRENOTOOLS_DRIVER_NAME",
)

/**
 * A Turnip driver that is actually installed, with everything libadrenotools
 * needs to load it.
 *
 * All three fields or none. The Winlator lineage's
 * `AdrenotoolsManager.setDriverById()` falls through **without setting
 * `ADRENOTOOLS_DRIVER_*` and without logging** when a driver id does not
 * resolve, and the system Vulkan driver quietly takes over. Modelling "no
 * driver" as a null [TurnipDriver] makes that state impossible to construct by
 * accident.
 */
data class TurnipDriver(
    /** The directory holding the driver `.so` — the installed Turnip component. */
    val driverDir: File,
    /** `libraryName` from the package's `meta.json`, e.g. `libvulkan_freedreno.so`. */
    val libraryName: String,
    /**
     * The APK's own native library directory, where libadrenotools' hook objects
     * live. Supplied by the caller from `applicationInfo.nativeLibraryDir`; there
     * is nothing this layer could derive it from.
     */
    val hooksDir: File,
)

/** The two container directories the environment names. */
data class SessionPaths(
    /** `WINEPREFIX`. */
    val prefix: File,
    /** `DXVK_LOG_PATH`. Insurance only — see below. */
    val logs: File,
)

/**
 * The environment a session is started with — `docs/LOGGING.md` as code.
 *
 * A pure function of its arguments: no `Context`, no disk, no clock. Every
 * failure mode here is silent (the wrong `WINEDEBUG` string produces an empty
 * log, not an error), so the only way to hold it to the document is to assert
 * the exact output in a unit test.
 *
 * Two things the document explains that are easy to undo from here: setting
 * `WINEDEBUG` correctly is necessary but not sufficient, because Wine skips
 * parsing it entirely when fd 2 is `/dev/null`; and `TU_DEBUG` always includes
 * [TU_DEBUG_STARTUP], which is the only ground truth for whether Turnip loaded.
 *
 * @param profile the container, for its manifest values.
 * @param manifest maps a param key to the environment variable it becomes. Null
 *   produces only the fixed variables — an incomplete environment is better than
 *   a guessed one.
 * @param turnip null when no Turnip package is installed, which omits the
 *   `ADRENOTOOLS_*` variables entirely rather than setting them empty.
 */
fun sessionEnvironment(
    profile: ContainerProfile,
    manifest: ParamManifest?,
    paths: SessionPaths,
    turnip: TurnipDriver? = null,
    display: String = DEFAULT_DISPLAY,
): Map<String, String> {
    val environment = LinkedHashMap<String, String>()

    environment["WINEPREFIX"] = paths.prefix.absolutePath
    environment["WINEESYNC"] = "1"
    environment["WINEDEBUG"] = WINEDEBUG_CHANNELS
    environment["WINEDLLOVERRIDES"] = D3D_DLL_OVERRIDES.joinToString(",") + "=n"
    environment["DISPLAY"] = display

    environment["DXVK_LOG_LEVEL"] = "info"
    environment["DXVK_LOG_PATH"] = paths.logs.absolutePath

    environment["VKD3D_DEBUG"] = "warn"
    environment["VKD3D_SHADER_DEBUG"] = "warn"

    environment["TU_DEBUG"] = tuDebugFlags(profile, manifest).joinToString(",")

    if (turnip != null) {
        // libadrenotools concatenates path and name, so without the trailing
        // separator it looks for `…/componentslibvulkan….so`.
        environment["ADRENOTOOLS_DRIVER_PATH"] = turnip.driverDir.absolutePath + File.separator
        environment["ADRENOTOOLS_HOOKS_PATH"] = turnip.hooksDir.absolutePath + File.separator
        environment["ADRENOTOOLS_DRIVER_NAME"] = turnip.libraryName
    }

    for ((key, value) in manifestEnvironment(profile, manifest)) {
        if (key !in RESERVED_SESSION_ENV) environment[key] = value
    }

    return environment
}

/**
 * Every manifest param that declares an `env`, resolved against this container.
 *
 * A param with no `env` produces nothing: `display.resolution` and
 * `display.fpsLimit` are consumed by the session surface, and inventing a
 * `DXVK_FRAME_RATE` for the latter because it looks like it should exist is the
 * fabrication the manifest exists to prevent.
 */
internal fun manifestEnvironment(
    profile: ContainerProfile,
    manifest: ParamManifest?,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (spec in manifest?.allParams.orEmpty()) {
        val name = spec.env ?: continue
        val value = profile.params[spec.key] ?: spec.defaultValue() ?: continue
        out[name] = value.asEnvValue()
    }
    return out
}

/**
 * `TU_DEBUG` as a flag list: whatever the container asks for, then `startup`.
 *
 * Only text and multi-select params contribute. A boolean or an integer cannot
 * name a Turnip flag, so one carrying `env: TU_DEBUG` is ignored rather than
 * rendered as `1`, which would be a flag Turnip does not have.
 */
internal fun tuDebugFlags(profile: ContainerProfile, manifest: ParamManifest?): List<String> {
    val fromContainer = manifest?.allParams.orEmpty()
        .filter { it.env == "TU_DEBUG" }
        .flatMap { spec ->
            when (val value = profile.params[spec.key] ?: spec.defaultValue()) {
                is ParamValue.Text -> listOf(value.value)
                is ParamValue.Choices -> value.values
                else -> emptyList()
            }
        }
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in TU_DEBUG_NO_OP }

    return (fromContainer + TU_DEBUG_STARTUP).distinct()
}

/**
 * One stored value as an environment variable's text.
 *
 * Booleans are `1`/`0` rather than `true`/`false`, because that is what FEX's
 * config reader takes. Note the variable is `FEX_TSOENABLED`, never
 * `FEX_TSOEnabled` — see `docs/ARCHITECTURE.md`.
 */
internal fun ParamValue.asEnvValue(): String = when (this) {
    is ParamValue.Flag -> if (value) "1" else "0"
    is ParamValue.Count -> value.toString()
    is ParamValue.Text -> value
    is ParamValue.Choices -> values.joinToString(",")
}
