package app.vessel.core

import app.vessel.core.params.ParamManifest
import app.vessel.core.params.ParamValue
import java.io.File

/**
 * The fixed Wine debug channel set.
 *
 * **Order is load-bearing and this string must not be reformatted.** Wine parses
 * left to right and seeds each newly named channel from `default_flags` as of
 * that moment, so:
 *
 *  - `-all` has to come **first**. A trailing `-all` erases everything before it.
 *  - `warn+module` has to come **after** `err+all`, so the module channel
 *    inherits ERR and ends up ERR|WARN. That WARN tier is the whole reason it is
 *    here: `loader.c` logs *"No implementation for X.Y imported from Z"* at WARN,
 *    which is a DLL that loaded with a missing export and then dies later at a
 *    confusing address. `err+all` misses it because it is WARN; `+loaddll`
 *    misses it because the module loaded fine.
 *  - It is `err+all`, the class form — **not** `+err`. A leading `+` names a
 *    channel, and there is no channel called `err`, `warn` or `fixme`. The
 *    Winlator lineage's `+warn,+err,+fixme` registers three channels that do not
 *    exist and configures nothing at all.
 *
 * See `docs/LOGGING.md`, which is the source of truth for this file.
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
 * Two different reasons, both worth keeping:
 *
 *  - most are *not settings*. `WINEDEBUG` is fixed because a user who chooses
 *    among 521 channels can still get nothing (see `docs/LOGGING.md`), and
 *    `WINEESYNC` is fixed because esync is the only synchronisation mode that
 *    works here — the manifest's own `_fixedNote` explains that ntsync needs a
 *    kernel driver Android does not ship and that fsync's `futex_waitv` probe is
 *    absent from Android's seccomp allowlist, where the default action is
 *    `SECCOMP_RET_TRAP`, so probing it SIGSYS-kills the process.
 *  - `VKD3D_LOG_FILE` is here to guarantee its **absence**. `vkd3d_dbg_init_once`
 *    is an if/else: set the variable and it opens the file *instead of* resolving
 *    `__wine_dbg_output`. Setting it does not add a file, it moves vkd3d's output
 *    off the pipe the session log reads. A manifest key that happened to name it
 *    would silence the D3D12 layer, which is the weakest part of this stack.
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
 * All three fields or none. `AdrenotoolsManager.setDriverById()` in the Winlator
 * lineage falls through **without setting `ADRENOTOOLS_DRIVER_*` and without
 * logging** when a driver id does not resolve, and the system Vulkan driver
 * quietly takes over — everything appears to work, slower and with different
 * bugs. Modelling "no driver" as a null [TurnipDriver] rather than as three
 * possibly-empty strings makes that state impossible to construct by accident,
 * and makes the absence of the variables something a test can assert.
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
 * The environment a session is started with.
 *
 * A pure function of its arguments: no `Context`, no disk, no clock. That is not
 * style. This map is the single place where `docs/LOGGING.md` either is or is
 * not implemented, every one of its failure modes is silent — the wrong
 * `WINEDEBUG` string produces an empty log, not an error — and the only way to
 * hold it to the document is to assert the exact output in a unit test.
 *
 * What it sets, and why each one is not obvious:
 *
 *  - **`WINEDEBUG`** — [WINEDEBUG_CHANNELS], order load-bearing. Note that Wine
 *    will not parse it at all if fd 2 is `/dev/null`: `init_options()` `fstat`s
 *    stderr, recognises the null device and returns before reading the variable.
 *    Setting it correctly here is necessary and not sufficient; the launcher must
 *    hand the process a real pipe. DXVK and vkd3d resolve `__wine_dbg_output`
 *    from ntdll and write through it, so the same redirect would discard the
 *    entire graphics story too.
 *  - **`DXVK_LOG_PATH`** — insurance, not the primary path. Under Wine with no
 *    path set DXVK creates no file and everything rides stderr; its file write is
 *    a separate unconditional block, so setting the path is additive. It earns
 *    its place only for the case where `__wine_dbg_output` fails to resolve.
 *  - **`VKD3D_SHADER_DEBUG`** — a *separate* channel from `VKD3D_DEBUG`, with its
 *    own level. `VKD3D_DEBUG=warn` does not carry shader translation failures.
 *  - **`VKD3D_LOG_FILE`** — never set. See [RESERVED_SESSION_ENV].
 *  - **`TU_DEBUG`** — always includes [TU_DEBUG_STARTUP]. Only Turnip honours it,
 *    so output means Turnip loaded and silence means it did not. No Wine channel
 *    reports which Vulkan driver was selected, and DXVK cannot be trusted for it
 *    in a Winlator-style container, so this is the ground truth.
 *
 * @param profile the container, for its manifest values.
 * @param manifest the param manifest, which is what maps a param key to the
 *   environment variable it becomes. Null means no manifest was readable, in
 *   which case only the fixed variables are produced — an incomplete environment
 *   is better than a guessed one.
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
        // libadrenotools concatenates path and name, so the directory needs its
        // trailing separator or it would look for `…/componentslibvulkan….so`.
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
 * A param with no `env` produces nothing — `display.resolution` and
 * `display.fpsLimit` are consumed by the session surface rather than by a child
 * process, and inventing `DXVK_FRAME_RATE` for the second because it looks like
 * it should exist is exactly the kind of fabrication the manifest is meant to
 * prevent. A key the container has no value for falls back to the manifest
 * default, which [ContainerRepository.draft] has already written for every
 * container it created.
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
 * `docs/LOGGING.md` writes it as `TU_DEBUG=<existing flags>,startup`, and the
 * order is kept — a container that forces `sysmem` gets `sysmem,startup`.
 * Duplicates are collapsed so a manifest that names `startup` itself does not
 * produce it twice.
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
 * Booleans are `1`/`0` rather than `true`/`false`: FEX's config reader takes
 * `FEX_TSOENABLED=1`, and `docs/TUNING.md` records the naming derivation —
 * the enum name is uppercased by the generator before `Config.cpp` prefixes it,
 * so it is `FEX_TSOENABLED` and never `FEX_TSOEnabled`.
 */
internal fun ParamValue.asEnvValue(): String = when (this) {
    is ParamValue.Flag -> if (value) "1" else "0"
    is ParamValue.Count -> value.toString()
    is ParamValue.Text -> value
    is ParamValue.Choices -> values.joinToString(",")
}
