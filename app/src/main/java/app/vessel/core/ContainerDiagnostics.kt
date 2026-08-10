package app.vessel.core

import kotlinx.serialization.Serializable

/**
 * What a container's next session is asked to say about itself.
 *
 * **A separate schema from `assets/params-manifest.json`, on purpose.** The
 * manifest's law is that nothing is hidden, order is the hierarchy, and a
 * setting that cannot be explained in one plain sentence does not belong in the
 * file at all (`params-manifest.json:9-16`). `VKD3D_SHADER_DEBUG` cannot be
 * explained that way, and that law is what has kept the container sheet at three
 * controls while the environment grew to thirty-odd variables. Diagnostics are
 * not settings — they change nothing about how a program runs, only what the run
 * says about itself, and they are reached only by someone whose session is
 * already broken. A different audience gets a different surface and a different
 * schema; see `docs/DIAGNOSTICS-UI.md` §1.
 *
 * Stored as a typed field on [ContainerProfile] rather than as more keys in its
 * `params` map, for the same reason: `params` *is* the manifest surface, and a
 * diagnostics key living in it would be one refactor away from being rendered by
 * the manifest editor.
 *
 * **Every default here is "what the container already does".** An untouched
 * record produces an empty [diagnosticEnvironment] and a [composeWineDebug]
 * equal to [WINEDEBUG_CHANNELS] character for character, so a fresh container's
 * environment is byte-for-byte the one `SessionEnvironmentTest` pins. That is
 * the whole safety property of this file and it is asserted rather than
 * described.
 */
@Serializable
data class ContainerDiagnostics(
    /**
     * Per-channel Wine levels, keyed by the channel name Wine knows.
     *
     * Sparse: a channel absent from the map is at [WineChannelSpec.defaultLevel],
     * and writing its default back explicitly produces the same environment
     * either way.
     */
    val wineChannels: Map<String, WineChannelLevel> = emptyMap(),
    val dxvkLevel: DxvkLogLevel = DxvkLogLevel.INFO,
    val vkd3dLevel: Vkd3dLogLevel = Vkd3dLogLevel.WARN,
    val vkd3dShaderLevel: Vkd3dLogLevel = Vkd3dLogLevel.WARN,
    /**
     * Whether the x86 translator is allowed to speak — `FEX_SILENTLOG` inverted.
     *
     * On by default because that is what `SessionEnvironment` already sets, and
     * for the reason recorded there: the silent default also hides
     * `FEX_HOSTFEATURES` rejecting a token it does not recognise.
     */
    val fexMessages: Boolean = true,
    /**
     * `MESA_LOG=file`, which is the only thing that puts the graphics driver's
     * output where Vessel can read it. Off by default; see [diagnosticEnvironment].
     */
    val driverMessagesInLog: Boolean = false,
    /** The raw `WINEDEBUG` escape hatch, appended last. See [rawTermsIssue]. */
    val rawTerms: String = "",
    val limits: SessionLogLimits = SessionLogLimits(),
    /**
     * The dangerous controls that are armed for exactly one launch.
     *
     * A set of [DangerousControl.id]s rather than a boolean on each control,
     * because "armed" is a fact about the *next session* and not about the value:
     * `+relay` is a legitimate thing to ask for once and never a thing to leave
     * switched on. [consumed] is called by the launcher at the moment a session's
     * environment is composed, so an app killed mid-run comes back with the
     * control already off rather than armed for a second, unasked-for firehose.
     *
     * `docs/DIAGNOSTICS-UI.md` §7 proposes storing the `startedAt` of the session
     * each control was armed for instead. **This is a deliberate simplification
     * and it holds the same guarantee**: disarming at launch rather than
     * recognising a stale stamp at the next launch gives "in force for exactly
     * one session" without putting a clock inside a pure function, and it cannot
     * leave a document holding a stamp for a session that never happened.
     */
    val armed: Set<String> = emptySet(),
) {
    /** True when nothing is switched on, which is what a fresh container is. */
    val isDefault: Boolean get() = this == DEFAULT

    /** The level [channel] runs at, falling back to what the fixed prefix gives it. */
    fun levelOf(channel: String): WineChannelLevel =
        wineChannels[channel] ?: wineChannelSpec(channel)?.defaultLevel ?: WineChannelLevel.ERRORS

    /**
     * The record with every dangerous value that is *not* armed put back.
     *
     * The setters below keep [armed] and the values in step, so this normally
     * changes nothing. It exists because a hand-edited or half-migrated document
     * must not be able to leave `+relay` permanently on: every read path starts
     * here, so an unarmed firehose is unreachable rather than merely unlikely.
     */
    fun inForce(): ContainerDiagnostics =
        DangerousControl.entries.fold(this) { record, control ->
            if (control.isDangerous(record) && control.id !in record.armed) {
                control.disarm(record)
            } else {
                record
            }
        }

    /**
     * The record to store once this session has taken its copy.
     *
     * Every armed control spends its one launch here: the arm is dropped and the
     * value goes back to the default, so the *next* session gets the ordinary
     * environment whatever happens to this one.
     */
    fun consumed(): ContainerDiagnostics = copy(armed = emptySet()).inForce()

    // — edits, which are the only writers of [armed] --------------------------

    fun withWineChannel(channel: String, level: WineChannelLevel): ContainerDiagnostics {
        val spec = wineChannelSpec(channel) ?: return this
        val next = if (level == spec.defaultLevel) {
            copy(wineChannels = wineChannels - channel)
        } else {
            copy(wineChannels = wineChannels + (channel to level))
        }
        return next.rearmed()
    }

    fun withDxvkLevel(level: DxvkLogLevel): ContainerDiagnostics = copy(dxvkLevel = level).rearmed()

    fun withVkd3dLevel(level: Vkd3dLogLevel): ContainerDiagnostics =
        copy(vkd3dLevel = level).rearmed()

    fun withVkd3dShaderLevel(level: Vkd3dLogLevel): ContainerDiagnostics =
        copy(vkd3dShaderLevel = level).rearmed()

    fun withFexMessages(on: Boolean): ContainerDiagnostics = copy(fexMessages = on)

    fun withDriverMessagesInLog(on: Boolean): ContainerDiagnostics =
        copy(driverMessagesInLog = on)

    fun withRawTerms(text: String): ContainerDiagnostics = copy(rawTerms = text)

    fun withLimits(limits: SessionLogLimits): ContainerDiagnostics = copy(limits = limits)

    /**
     * Re-derive [armed] from the values.
     *
     * A control that has just been set to a dangerous value is armed; one that
     * has been set back is disarmed. Doing it here rather than at each call site
     * is what makes it impossible to add a seventh dangerous control and forget
     * to arm it — the [DangerousControl] entry is the whole declaration.
     */
    private fun rearmed(): ContainerDiagnostics =
        copy(armed = DangerousControl.entries.filter { it.isDangerous(this) }.map { it.id }.toSet())

    companion object {
        val DEFAULT: ContainerDiagnostics = ContainerDiagnostics()
    }
}

/**
 * The five stops a Wine channel can run at, and the only ladder in this file
 * that is a simplification rather than a translation.
 *
 * Wine's model is four independent class bits — `fixme`, `err`, `warn`, `trace`
 * (`native/wine/dlls/ntdll/unix/debug.c:65`) — so a ladder cannot express
 * `err` without `warn`, or `trace` without `fixme`. It is used anyway because
 * the combinations a ladder cannot reach are not ones anybody asks for, and
 * anyone who does need one has [ContainerDiagnostics.rawTerms]. See
 * `docs/DIAGNOSTICS-UI.md` §9, decision 2.
 *
 * [TRACE] is folded into [EVERYTHING] rather than given a stop of its own: a
 * channel's trace tier is where every firehose lives, and offering it as "one
 * more notch" is how a ladder makes a 500 MB log look like an increment.
 */
@Serializable
enum class WineChannelLevel(val label: String) {
    /** `-chan` — silenced, below even the `err+all` floor every channel inherits. */
    OFF("Off"),

    /** ERR only. The floor `err+all` gives every channel Vessel does not name. */
    ERRORS("Errors"),

    WARNINGS("+ Warnings"),
    STUBS("+ Stubs"),
    EVERYTHING("Everything"),
    ;

    /**
     * Wine's class names for this stop, lowest first.
     *
     * Empty for [OFF] and [EVERYTHING], which are written with the bare `-chan`
     * and `+chan` forms instead.
     */
    internal val classes: List<String>
        get() = when (this) {
            OFF, EVERYTHING -> emptyList()
            ERRORS -> listOf("err")
            WARNINGS -> listOf("err", "warn")
            STUBS -> listOf("err", "warn", "fixme")
        }
}

/**
 * One Wine channel worth offering, and the question it answers.
 *
 * **Curated, not complete.** `wine_debug_channels.json` in the Winlator lineage
 * lists 521 channels as a flat picker and `docs/LOGGING.md:222-230` explains why
 * Vessel offers none of them: choosing well among 521 requires knowing what each
 * costs, and a user who chooses correctly can still get nothing. The rule for
 * this list is that every entry answers a question somebody has actually had.
 * Anything else belongs in the raw field.
 *
 * @param defaultLevel what this channel is *already* running at, which is the
 *   value the row shows on a fresh container. For the four channels named in
 *   [WINEDEBUG_CHANNELS] that is what the prefix sets; for everything else it is
 *   [WineChannelLevel.ERRORS], inherited from `err+all` — which is why "off" is a
 *   real action on a channel nobody has switched on.
 * @param maxLevel the top of this channel's ladder. Only `d3d` narrows it.
 * @param caution shown in the danger tone under the row, always, not only when
 *   the dangerous stop is chosen.
 */
data class WineChannelSpec(
    val channel: String,
    val title: String,
    val help: String,
    val defaultLevel: WineChannelLevel,
    val maxLevel: WineChannelLevel = WineChannelLevel.EVERYTHING,
    val caution: String? = null,
) {
    /** The stops this row offers, which is the ladder truncated at [maxLevel]. */
    val levels: List<WineChannelLevel>
        get() = WineChannelLevel.entries.filter { it.ordinal <= maxLevel.ordinal }
}

/**
 * The Wine rows, in the order they are drawn.
 *
 * The four the fixed prefix already names come first, at the levels it already
 * gives them — a diagnostics screen whose first four rows read "Off" would be
 * claiming logging is disabled, which is the opposite of true.
 */
val DIAGNOSTIC_WINE_CHANNELS: List<WineChannelSpec> = listOf(
    WineChannelSpec(
        channel = "module",
        title = "Missing DLLs and exports",
        help = "Says when a library loaded but an entry point inside it is missing, which is " +
            "what later crashes far from the cause.",
        // `warn+module` in the fixed prefix, and `docs/LOGGING.md:76-95` is an
        // argument about the WARN tier specifically: the three `WARN(` sites in
        // loader.c that say "the DLL loaded but an export is missing" are the
        // case `err+all` and `+loaddll` both miss.
        defaultLevel = WineChannelLevel.WARNINGS,
    ),
    WineChannelSpec(
        channel = "loaddll",
        title = "Loaded modules",
        help = "Lists every DLL the program successfully loaded.",
        defaultLevel = WineChannelLevel.EVERYTHING,
    ),
    WineChannelSpec(
        channel = "winediag",
        title = "Wine's own warnings",
        help = "Wine's report on its own health: no Vulkan library, no display driver, " +
            "broken .NET, and which renderer it chose.",
        defaultLevel = WineChannelLevel.EVERYTHING,
    ),
    WineChannelSpec(
        channel = "debugstr",
        title = "The program's own messages",
        help = "What the program itself chose to print — often the only reason a game gives " +
            "for quitting.",
        defaultLevel = WineChannelLevel.EVERYTHING,
    ),
    WineChannelSpec(
        channel = D3D_CHANNEL,
        title = "Graphics through Wine",
        help = "Only relevant when a program falls back to Wine's own Direct3D instead of DXVK.",
        defaultLevel = WineChannelLevel.ERRORS,
        // Capped, and this is the one ladder that is shorter than the others.
        // `docs/LOGGING.md:164-170`: wined3d has 659 `ERR(` sites with only 19
        // `once` guards and owns per-draw paths, so its WARN tier is unbounded in
        // a way `module`'s is not. Stubs and traces above it are not a stop worth
        // drawing — they are the raw field's problem.
        maxLevel = WineChannelLevel.WARNINGS,
        caution = "Warnings here are unbounded: wined3d has 659 error sites on per-draw paths " +
            "with almost no once-only guards.",
    ),
    WineChannelSpec(
        channel = "vulkan",
        title = "Vulkan setup",
        help = "How Wine found and opened the graphics driver.",
        defaultLevel = WineChannelLevel.ERRORS,
    ),
    WineChannelSpec(
        channel = "file",
        title = "File access",
        help = "Every file the program opens, and every one it fails to open.",
        defaultLevel = WineChannelLevel.ERRORS,
    ),
    WineChannelSpec(
        channel = RELAY_CHANNEL,
        title = "Every call between libraries",
        help = "Names every cross-DLL call as it happens. Hundreds of megabytes in seconds.",
        defaultLevel = WineChannelLevel.ERRORS,
    ),
    WineChannelSpec(
        channel = SEH_CHANNEL,
        title = "Every exception raised",
        help = "Every exception the program raises, handled or not — and C++ and .NET " +
            "exceptions are all of them.",
        defaultLevel = WineChannelLevel.ERRORS,
    ),
)

/**
 * The two Wine channels that are switches rather than ladders.
 *
 * **Drawing these as a level would be a lie about what they contain.** `relay`
 * is TRACE and nothing else — every `_(relay)` site in `native/wine/dlls/ntdll`
 * is `TRACE_(relay)` — so its Errors, Warnings and Stubs stops are all silence.
 * `seh` has 7 ERR, 3 WARN and 13 TRACE sites in ntdll, and the ERR tier is
 * already on through `err+all`, so the only stop that changes anything is the
 * TRACE one. Both are therefore drawn as on/off, where on is [EVERYTHING].
 */
const val RELAY_CHANNEL: String = "relay"
const val SEH_CHANNEL: String = "seh"

/** Wine's own Direct3D, which is not DXVK's — the one ladder that is capped. */
const val D3D_CHANNEL: String = "d3d"

fun wineChannelSpec(channel: String): WineChannelSpec? =
    DIAGNOSTIC_WINE_CHANNELS.firstOrNull { it.channel == channel }

/** True for the two channels drawn as a switch rather than as a ladder. */
fun isSwitchChannel(channel: String): Boolean = channel == RELAY_CHANNEL || channel == SEH_CHANNEL

/**
 * `DXVK_LOG_LEVEL`, in DXVK's own words.
 *
 * A floor rather than a set: `log.cpp:48` filters with `if (level >= m_minLevel)`
 * and the enum runs `Trace=0 … None=5` (`log.h:12-19`), so `trace` is the
 * loudest and `none` is silence. The six names and their spelling are
 * `native/dxvk/src/util/log/log.cpp:146-152`, read in that file's own order and
 * not resorted: the wire value is what a user will grep for and what an issue
 * thread will name.
 */
@Serializable
enum class DxvkLogLevel(val wire: String) {
    NONE("none"),
    ERROR("error"),
    WARN("warn"),
    INFO("info"),
    DEBUG("debug"),
    TRACE("trace"),
}

/**
 * `VKD3D_DEBUG` and `VKD3D_SHADER_DEBUG`, in vkd3d's own words **and vkd3d's own
 * order**, which is not DXVK's and must not be normalised to it.
 *
 * `debug_level_names[]` reads, in this order: `none`, `err`, `info`, `fixme`,
 * `warn`, `trace` — `native/vkd3d/libs/vkd3d-common/debug.c:38-47`, with emission
 * `if (vkd3d_dbg_get_level(channel) < level) return`. **`info` sits between
 * `err` and `fixme`**, so `warn` already carries both. Two adjacent six-stop
 * pickers whose stops read differently is correct here; a shared "warn is
 * quieter than info" ladder would be a screen that lies about what it sets.
 */
@Serializable
enum class Vkd3dLogLevel(val wire: String) {
    NONE("none"),
    ERR("err"),
    INFO("info"),
    FIXME("fixme"),
    WARN("warn"),
    TRACE("trace"),
}

/**
 * A control that fills the log in seconds and turns itself off after one launch.
 *
 * The declaration is the whole mechanism: [isDangerous] says when the arm is
 * needed, [disarm] says what the value goes back to, and
 * [ContainerDiagnostics.rearmed] and [ContainerDiagnostics.consumed] do the rest.
 * Adding a seventh entry is the only change needed to make a new stop
 * one-session.
 *
 * [warning] must say the three concrete things — the log fills in seconds, the
 * session is slower, and the setting turns itself off — because naming the
 * mechanism is what stops the confirmation reading as a scary-sounding dialog
 * people learn to dismiss.
 */
enum class DangerousControl(
    val id: String,
    val title: String,
    val warning: String,
) {
    WINE_RELAY(
        id = "wine.relay",
        title = "Every call between libraries",
        warning = "Relay tracing names every call between libraries — hundreds of megabytes in " +
            "seconds, so the log will hit its cap almost immediately and the session will run " +
            "much slower. It switches itself off after the next launch.",
    ) {
        override fun isDangerous(d: ContainerDiagnostics) =
            d.levelOf(RELAY_CHANNEL) != WineChannelLevel.ERRORS

        override fun disarm(d: ContainerDiagnostics) =
            d.copy(wineChannels = d.wineChannels - RELAY_CHANNEL, armed = d.armed - id)
    },

    WINE_SEH(
        id = "wine.seh",
        title = "Every exception raised",
        warning = "This runs for every exception the program raises, handled or not, with a " +
            "register dump each time — and C++ and .NET exceptions are all of them. The log " +
            "will fill in seconds and the session will run much slower. It switches itself off " +
            "after the next launch. Crashes are already reported without it.",
    ) {
        override fun isDangerous(d: ContainerDiagnostics) =
            d.levelOf(SEH_CHANNEL) != WineChannelLevel.ERRORS

        override fun disarm(d: ContainerDiagnostics) =
            d.copy(wineChannels = d.wineChannels - SEH_CHANNEL, armed = d.armed - id)
    },

    WINE_D3D(
        id = "wine.d3d",
        title = "Graphics through Wine, at warnings",
        warning = "wined3d has 659 error sites on per-draw paths and almost no once-only " +
            "guards, so its warnings arrive every frame: the log will hit its cap in seconds " +
            "and the session will run much slower. It switches itself off after the next launch.",
    ) {
        override fun isDangerous(d: ContainerDiagnostics) =
            d.levelOf(D3D_CHANNEL).ordinal > WineChannelLevel.ERRORS.ordinal

        override fun disarm(d: ContainerDiagnostics) =
            d.copy(wineChannels = d.wineChannels - D3D_CHANNEL, armed = d.armed - id)
    },

    DXVK_VERBOSE(
        id = "dxvk",
        title = "DXVK at debug or trace",
        warning = "DXVK's debug and trace tiers report per draw call. The log will hit its cap " +
            "in seconds and the session will run much slower. It goes back to info after the " +
            "next launch.",
    ) {
        override fun isDangerous(d: ContainerDiagnostics) =
            d.dxvkLevel == DxvkLogLevel.DEBUG || d.dxvkLevel == DxvkLogLevel.TRACE

        override fun disarm(d: ContainerDiagnostics) =
            d.copy(dxvkLevel = DxvkLogLevel.INFO, armed = d.armed - id)
    },

    VKD3D_TRACE(
        id = "vkd3d",
        title = "vkd3d at trace",
        warning = "vkd3d's trace tier reports every Direct3D 12 call. The log will hit its cap " +
            "in seconds and the session will run much slower. It goes back to warn after the " +
            "next launch.",
    ) {
        override fun isDangerous(d: ContainerDiagnostics) = d.vkd3dLevel == Vkd3dLogLevel.TRACE

        override fun disarm(d: ContainerDiagnostics) =
            d.copy(vkd3dLevel = Vkd3dLogLevel.WARN, armed = d.armed - id)
    },

    VKD3D_SHADER_TRACE(
        id = "vkd3d.shader",
        title = "vkd3d shader translation at trace",
        warning = "This is the per-shader firehose: every instruction of every shader vkd3d " +
            "translates. The log will hit its cap in seconds and the session will run much " +
            "slower. It goes back to warn after the next launch.",
    ) {
        override fun isDangerous(d: ContainerDiagnostics) =
            d.vkd3dShaderLevel == Vkd3dLogLevel.TRACE

        override fun disarm(d: ContainerDiagnostics) =
            d.copy(vkd3dShaderLevel = Vkd3dLogLevel.WARN, armed = d.armed - id)
    },
    ;

    /** Whether [d]'s current value is the one that needs arming. */
    abstract fun isDangerous(d: ContainerDiagnostics): Boolean

    /** [d] with this control put back to its ordinary value and its arm dropped. */
    abstract fun disarm(d: ContainerDiagnostics): ContainerDiagnostics

    companion object {
        fun of(id: String): DangerousControl? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The three caps a session's log is written under.
 *
 * These were constants in `SessionLogWriter` and are a per-container setting now
 * because the whole point of raising a channel is that the run afterwards is
 * bigger than the caps were chosen for. They are the third of the three layers
 * `docs/LOGGING.md:172-190` prescribes as the answer to `err+all` being unbounded
 * when things go wrong, so moving them is undoing a deliberate design, and the
 * arithmetic is stated rather than implied:
 *
 * **At roughly 120 bytes a line, [rateLimitLines] lines a second fills
 * [headBytes] + [tailBytes] in `(head + tail) / (rate × 120)` seconds.** At the
 * old 2 000 / 5 MB / 3 MB that was about 35 seconds; at the defaults below it is
 * about 20. **So the byte caps and the rate limit move together or not at all** —
 * raising the bytes alone buys a longer window at the same fidelity, and raising
 * the rate alone reaches the cap sooner.
 *
 * **The defaults are the maximum of each ladder**, which is the requirement this
 * work was given, and it is a real cost: [worstCaseBytesPerContainer] is 480 MB
 * against the 80 MB these numbers used to imply. That is too much to spend
 * silently, so the Diagnostics surface shows the container's actual usage beside
 * these controls and carries a *Delete all logs* action. A screen that raises a
 * storage ceiling and does not show the storage is not finished.
 *
 * Sessions kept per container is deliberately **not** here. It is a history
 * budget rather than a fidelity budget — a diagnostician compares a bad run
 * against a good one — and ten is what `SessionLogStore` says that takes.
 */
@Serializable
data class SessionLogLimits(
    /**
     * The head allowance: the init story — module loads, the driver coming up,
     * D3D device creation.
     */
    val headBytes: Long = HEAD_LADDER.last(),
    /**
     * The retained tail *in total*, which is where the crash is.
     *
     * Named for what it holds rather than for the field it sets: the writer keeps
     * two segments and rotates between them, so the retained tail sawtooths
     * between half of this and all of it. Halving here is what keeps that
     * implementation detail out of the label.
     */
    val tailBytes: Long = TAIL_LADDER.last(),
    /** Lines a second the sink writes before it starts counting instead. */
    val rateLimitLines: Int = RATE_LADDER.last(),
) {
    /** One tail segment. Two are retained; see [tailBytes]. */
    val tailSegmentBytes: Long get() = tailBytes / 2

    /** The most one session can occupy on disk. */
    val worstCaseBytesPerSession: Long get() = headBytes + tailBytes

    /** …and the most a container can, at [SESSIONS_KEPT] of them. */
    val worstCaseBytesPerContainer: Long get() = worstCaseBytesPerSession * SESSIONS_KEPT

    companion object {
        /** Ten runs is enough to compare a regression against what worked. */
        const val SESSIONS_KEPT: Int = 10

        private const val MB = 1024L * 1024L

        /** 5 MB is what shipped before this was a setting. */
        val HEAD_LADDER: List<Long> = listOf(5 * MB, 8 * MB, 16 * MB, 32 * MB)

        /** 3 MB is the old two 1536 KB segments. */
        val TAIL_LADDER: List<Long> = listOf(3 * MB, 6 * MB, 12 * MB, 16 * MB)

        /** 2 000 is what shipped before this was a setting. */
        val RATE_LADDER: List<Int> = listOf(2_000, 5_000, 10_000, 20_000)

        /** What the writer ran with before any of this was adjustable. */
        val SHIPPED: SessionLogLimits = SessionLogLimits(
            headBytes = HEAD_LADDER.first(),
            tailBytes = TAIL_LADDER.first(),
            rateLimitLines = RATE_LADDER.first(),
        )
    }
}

// — composition ---------------------------------------------------------------

/**
 * `WINEDEBUG`: the fixed prefix, then whatever Diagnostics adds, then the raw
 * terms.
 *
 * **Never a value handed over whole.** This starts from [WINEDEBUG_CHANNELS] and
 * appends, which is the shape `dllOverrides` already uses and for the same stated
 * reason: Wine parses left to right and a later term wins, so a user can add
 * without being able to delete the defaults by accident.
 *
 * **The per-channel terms are not the obvious one-term-per-stop, and that is a
 * fact about the parser rather than a style.** `add_option`
 * (`native/wine/dlls/ntdll/unix/debug.c:88-123`) creates a channel it has not
 * seen with `flags = (default_flags & ~clear) | set` — `default_flags` *as of
 * that moment* — and modifies one it has seen with the same expression against
 * the existing flags. Two consequences:
 *
 *  - A lone `fixme+x` on a fresh channel gives ERR|FIXME and **skips WARN**,
 *    because inheritance is from `default_flags` and not from the stop below.
 *  - A `+`-only term can never *lower* a channel, so `warn+loaddll` cannot quiet
 *    a `loaddll` the fixed prefix already set to all four classes.
 *
 * So a non-default stop is written as a `-chan` reset followed by one
 * `class+chan` per class it wants: `-d3d,err+d3d,warn+d3d`. That is exact for
 * every channel whether or not the prefix already named it, which the ladder in
 * `docs/DIAGNOSTICS-UI.md` §4 is not — that table is right only for channels the
 * prefix does not mention. The cost is up to four terms on a channel somebody has
 * deliberately changed, and nothing at all on one they have not.
 *
 * A channel at its default contributes **nothing**, so an untouched record
 * returns [WINEDEBUG_CHANNELS] byte for byte.
 */
fun composeWineDebug(diagnostics: ContainerDiagnostics): String {
    val record = diagnostics.inForce()
    val terms = ArrayList<String>()
    terms += WINEDEBUG_CHANNELS.split(",")

    for (spec in DIAGNOSTIC_WINE_CHANNELS) {
        val level = record.levelOf(spec.channel)
        if (level == spec.defaultLevel) continue
        terms += wineChannelTerms(spec.channel, level)
    }

    // Last, so a later term wins and every curated row above can be overridden
    // by someone following specific advice — and so the fixed prefix cannot be
    // deleted by anything typed here.
    if (rawTermsIssue(record.rawTerms)?.blocking != true) {
        terms += record.rawTerms.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    return terms.joinToString(",")
}

/** The terms that force `channel` to exactly [level]. See [composeWineDebug]. */
internal fun wineChannelTerms(channel: String, level: WineChannelLevel): List<String> = when (level) {
    WineChannelLevel.OFF -> listOf("-$channel")
    WineChannelLevel.EVERYTHING -> listOf("+$channel")
    else -> listOf("-$channel") + level.classes.map { "$it+$channel" }
}

/**
 * The environment Diagnostics contributes, which is **empty for an untouched
 * container**.
 *
 * Only differences from the fixed values appear. That is not an optimisation: it
 * is what makes "a fresh container produces exactly today's environment" a
 * property of one line rather than of a careful reading, and it is asserted in
 * `SessionEnvironmentTest`.
 *
 * Every key here is in `DIAGNOSTIC_SESSION_ENV`, and the merge stage that applies
 * this map checks that rather than trusting it — see `SessionEnvironment.kt`.
 *
 * **`TU_DEBUG` is absent on purpose and it is not an oversight.** Mesa picks its
 * logger at init and under Android the default is logcat
 * (`native/mesa/src/util/log.c:118-128`), Vessel reads no logcat, and so every
 * Turnip flag would be a switch whose output the product cannot see. The fix is
 * [ContainerDiagnostics.driverMessagesInLog] below; until a device run confirms
 * it lands Turnip's lines in the session log, offering the flags themselves would
 * be offering a control that does nothing.
 */
fun diagnosticEnvironment(diagnostics: ContainerDiagnostics): Map<String, String> {
    val record = diagnostics.inForce()
    val out = LinkedHashMap<String, String>()

    val wineDebug = composeWineDebug(record)
    if (wineDebug != WINEDEBUG_CHANNELS) out["WINEDEBUG"] = wineDebug

    if (record.dxvkLevel != ContainerDiagnostics.DEFAULT.dxvkLevel) {
        out["DXVK_LOG_LEVEL"] = record.dxvkLevel.wire
    }
    if (record.vkd3dLevel != ContainerDiagnostics.DEFAULT.vkd3dLevel) {
        out["VKD3D_DEBUG"] = record.vkd3dLevel.wire
    }
    if (record.vkd3dShaderLevel != ContainerDiagnostics.DEFAULT.vkd3dShaderLevel) {
        out["VKD3D_SHADER_DEBUG"] = record.vkd3dShaderLevel.wire
    }

    // Inverted: the control asks whether FEX may speak, the variable asks whether
    // it must be quiet. `1` is FEX's own default and is what hides a mistyped
    // FEX_HOSTFEATURES token as well as a crash, which is why Vessel sets `0`.
    if (!record.fexMessages) out["FEX_SILENTLOG"] = "1"

    // `file` adds Mesa's file logger, and `mesa_log_file` defaults to `stderr`
    // (`native/mesa/src/util/log.c:64-74, 145`) — the pipe the session log reads.
    // The line parser is already ready for what arrives: DRIVER_CHANNELS covers
    // `turnip` and any `mesa`/`tu_` prefix.
    //
    // **Unverified end to end.** The mechanism is read out of Mesa's source, but
    // that Turnip's lines actually reach the session log has not been observed on
    // the device; what would settle it is one session with this on and
    // `grep -c 'TU_DEBUG='` over the log returning non-zero.
    if (record.driverMessagesInLog) out["MESA_LOG"] = "file"

    return out
}

/** A problem with the raw field: [blocking] means the terms are not sent at all. */
data class RawTermsIssue(val message: String, val blocking: Boolean)

/**
 * What is wrong with the raw `WINEDEBUG` terms, or null.
 *
 * Two checks, both from the parser:
 *
 *  - **`help` is refused.** `init_options` compares the whole variable against
 *    `"help"` and calls `debug_usage()`, which writes a usage block to fd 2 and
 *    `exit(1)` (`debug.c:183-193, 213`). *That comparison is against the whole
 *    string, so this cannot in fact fire while the terms are appended to a
 *    non-empty prefix* — it is refused anyway, because the field's contract is
 *    "Wine's own terms" and a user who types `help` has asked for the thing that
 *    kills the process, and because the day the prefix becomes composable is not
 *    the day to rediscover this.
 *  - **A leading `-all` is warned about, not refused.** It is legal and
 *    occasionally what someone means, but parsing is left to right and
 *    `default_flags` is rewritten in place, so it erases every term before it —
 *    including the whole fixed prefix.
 */
fun rawTermsIssue(rawTerms: String): RawTermsIssue? {
    val text = rawTerms.trim()
    if (text.isEmpty()) return null

    if (text.equals("help", ignoreCase = true)) {
        return RawTermsIssue(
            "Wine treats WINEDEBUG=help as a request for its usage message and exits before " +
                "the program starts. Name channels instead, like +winmm.",
            blocking = true,
        )
    }

    val terms = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (terms.any { it == "-all" }) {
        return RawTermsIssue(
            "-all here erases every channel above it, including Vessel's own. Wine reads the " +
                "list left to right and the last word about a channel wins.",
            blocking = false,
        )
    }
    return null
}
