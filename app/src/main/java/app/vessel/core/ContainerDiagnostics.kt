package app.vessel.core

import kotlinx.serialization.Serializable

/**
 * What a container's next session is asked to say **in addition to** what it
 * already says.
 *
 * **The baseline is invisible and is not a setting.** [WINEDEBUG_CHANNELS] —
 * `-all,err+all,warn+module,+winediag,+loaddll,+debugstr` — is always on, is
 * never drawn as a row, and cannot be reached from this screen except by naming
 * one of its channels deliberately. It is the configuration `docs/LOGGING.md`
 * argues for, it is correct for a session that is behaving, and a screen that
 * drew it as seven pre-populated dropdowns would be inviting people to break it
 * while telling them nothing they did not already have.
 *
 * So an empty record is the whole of a fresh container: no rows, no environment,
 * nothing switched on. **Everything here is an addition**, which is also what
 * makes a per-row remove button meaningful — every row is something the user put
 * there.
 *
 * **A separate schema from `assets/params-manifest.json`, on purpose.** The
 * manifest's law is that a setting must be explainable in one plain sentence to
 * someone who does not know what a translator is (`params-manifest.json:9-12`).
 * `VKD3D_SHADER_DEBUG` is not, and that law is what has kept the container sheet
 * at three controls while the environment grew to thirty-odd variables.
 * Diagnostics has a different audience — someone whose session is already broken,
 * who has been told what to switch on — so it gets a different surface, a
 * different schema and different rules. See `docs/DIAGNOSTICS-UI.md` §1.
 *
 * **Nothing in here is keyed by a hardcoded name.** The Wine channels, the
 * translator levels and the two logger switches are all declared as data —
 * [WINE_CHANNEL_CATALOGUE], [SUBSYSTEM_LEVELS], [SUBSYSTEM_FLAGS] — and rendered
 * generically, so adding a channel or a level is an edit to a list and never a
 * new composable or a new `when` branch.
 */
@Serializable
data class ContainerDiagnostics(
    /**
     * The Wine channels the user has added, in the order they added them.
     *
     * A list rather than a map: the rows are a thing the user built, and their
     * order is theirs. It is also the order the terms are written in, which
     * matters — Wine parses left to right.
     */
    val wineChannels: List<WineChannelSetting> = emptyList(),
    /**
     * Per-subsystem levels, keyed by [SubsystemLevel.id]. Sparse: an absent id is
     * at [SubsystemLevel.default], which is what the fixed environment already
     * sets, so an absent id contributes nothing.
     */
    val subsystemLevels: Map<String, String> = emptyMap(),
    /** Per-subsystem switches, keyed by [SubsystemFlag.id]. Sparse, same rule. */
    val subsystemFlags: Map<String, Boolean> = emptyMap(),
    /**
     * Turnip flags, from [TURNIP_FLAGS].
     *
     * Cleared by [inForce] whenever [DRIVER_LOG_FLAG] is off, because without it
     * the driver writes to the Android system log and every one of these is a
     * switch whose output the product cannot read.
     */
    val turnipFlags: List<String> = emptyList(),
    val limits: SessionLogLimits = SessionLogLimits(),
    /**
     * The controls armed for exactly one launch, by [oneSessionId].
     *
     * A set rather than a per-control boolean, because "armed" is a fact about
     * the *next session* and not about the value: `+relay` is a legitimate thing
     * to ask for once and never a thing to leave switched on. [consumed] is
     * called by the launcher at the moment a session's environment is composed,
     * so an app killed mid-run comes back with the control already off rather
     * than armed for a second, unasked-for firehose.
     *
     * `docs/DIAGNOSTICS-UI.md` §7 proposes storing the `startedAt` of the session
     * each control was armed for. **This is a deliberate simplification holding
     * the same guarantee**: disarming at launch rather than recognising a stale
     * stamp at the next launch gives "in force for exactly one session" without
     * putting a clock inside a pure function, and it cannot leave a document
     * holding a stamp for a session that never happened.
     */
    val armed: Set<String> = emptySet(),
) {
    /** True when nothing has been added, which is what a fresh container is. */
    val isDefault: Boolean get() = this == DEFAULT

    fun levelOf(spec: SubsystemLevel): String = subsystemLevels[spec.id] ?: spec.default

    fun flagOf(spec: SubsystemFlag): Boolean = subsystemFlags[spec.id] ?: spec.default

    /** The channels already on screen, so the picker can offer the rest. */
    val addedChannels: Set<String> get() = wineChannels.map { it.channel }.toSet()

    /**
     * The record with every one-session value that is *not* armed put back.
     *
     * The setters below keep [armed] and the values in step, so this normally
     * changes nothing. It exists because a hand-edited or half-migrated document
     * must not be able to leave `+relay` permanently on: every read path starts
     * here, so an unarmed firehose is unreachable rather than merely unlikely.
     *
     * It is also where the Turnip gate is enforced, for the same reason — a flag
     * whose output nothing can read must not be reachable by editing a file.
     */
    fun inForce(): ContainerDiagnostics {
        val channels = wineChannels.filterNot { it.isOneSession && it.armId !in armed }
        val levels = subsystemLevels.filterNot { (id, value) ->
            val spec = subsystemLevel(id) ?: return@filterNot true
            spec.isOneSession(value) && spec.armId !in armed
        }
        val turnip = if (flagOf(DRIVER_LOG_FLAG)) turnipFlags else emptyList()
        return copy(wineChannels = channels, subsystemLevels = levels, turnipFlags = turnip)
    }

    /**
     * The record to store once this session has taken its copy.
     *
     * Every armed control spends its one launch here: the arm is dropped and the
     * value goes with it, so the *next* session gets the ordinary environment
     * whatever happens to this one.
     */
    fun consumed(): ContainerDiagnostics = copy(armed = emptySet()).inForce()

    // — edits, which are the only writers of [armed] --------------------------

    /** Add a channel at the level its catalogue entry suggests, or do nothing. */
    fun withChannelAdded(channel: String): ContainerDiagnostics {
        val name = channel.trim()
        if (!isChannelName(name) || name in addedChannels) return this
        val info = wineChannelInfo(name)
        return copy(
            wineChannels = wineChannels + WineChannelSetting(name, info.addAt),
        ).rearmed()
    }

    fun withChannelLevel(channel: String, level: WineChannelLevel): ContainerDiagnostics = copy(
        wineChannels = wineChannels.map {
            if (it.channel == channel) it.copy(level = level) else it
        },
    ).rearmed()

    /**
     * Take a row away.
     *
     * The channel goes back to whatever the invisible baseline gives it, which
     * for the four channels [WINEDEBUG_CHANNELS] names is their fixed value and
     * for everything else is the `err+all` floor. **Removing is not silencing** —
     * that is what the `Off` stop is for, and the distinction is real: `Off`
     * writes `-chan` and stops even errors.
     */
    fun withChannelRemoved(channel: String): ContainerDiagnostics =
        copy(wineChannels = wineChannels.filterNot { it.channel == channel }).rearmed()

    fun withSubsystemLevel(id: String, value: String): ContainerDiagnostics {
        val spec = subsystemLevel(id) ?: return this
        val next = if (value == spec.default) subsystemLevels - id else subsystemLevels + (id to value)
        return copy(subsystemLevels = next).rearmed()
    }

    fun withSubsystemFlag(id: String, on: Boolean): ContainerDiagnostics {
        val spec = subsystemFlag(id) ?: return this
        val next = if (on == spec.default) subsystemFlags - id else subsystemFlags + (id to on)
        return copy(subsystemFlags = next).inForce()
    }

    fun withTurnipFlag(flag: String, on: Boolean): ContainerDiagnostics = copy(
        // Catalogue order, not click order: the value becomes a comma-joined
        // string, and a stable order makes two containers with the same flags
        // compare equal.
        turnipFlags = TURNIP_FLAGS.map { it.flag }
            .filter { if (it == flag) on else it in turnipFlags },
    )

    fun withLimits(limits: SessionLogLimits): ContainerDiagnostics = copy(limits = limits)

    /**
     * Re-derive [armed] from the values.
     *
     * Doing it here rather than at each call site is what makes it impossible to
     * add a loud channel to the catalogue and forget to arm it: the
     * `oneSessionAt` field on the entry is the whole declaration.
     */
    private fun rearmed(): ContainerDiagnostics = copy(armed = oneSessionIds())

    /** Every control whose current value is loud enough to need arming. */
    fun oneSessionIds(): Set<String> = buildSet {
        wineChannels.filter { it.isOneSession }.forEach { add(it.armId) }
        SUBSYSTEM_LEVELS.filter { it.isOneSession(levelOf(it)) }.forEach { add(it.armId) }
    }

    companion object {
        val DEFAULT: ContainerDiagnostics = ContainerDiagnostics()
    }
}

/** One Wine channel the user has added, and the level they set it to. */
@Serializable
data class WineChannelSetting(val channel: String, val level: WineChannelLevel) {
    val info: WineChannelInfo get() = wineChannelInfo(channel)

    /** The id [ContainerDiagnostics.armed] holds while this row is armed. */
    val armId: String get() = oneSessionId("wine", channel)

    /** Whether this row is currently loud enough to be spent after one launch. */
    val isOneSession: Boolean
        get() = info.oneSessionAt?.let { level.ordinal >= it.ordinal } == true
}

/** `wine:relay`, `level:dxvk` — a stable key for one armable control. */
fun oneSessionId(kind: String, name: String): String = "$kind:$name"

/**
 * The five stops a Wine channel can run at, and the only ladder in this file
 * that is a simplification rather than a translation.
 *
 * Wine's model is four independent class bits — `fixme`, `err`, `warn`, `trace`
 * (`native/wine/dlls/ntdll/unix/debug.c:65`) — so a ladder cannot express `err`
 * without `warn`, or `trace` without `fixme`. It is used anyway because the
 * combinations it cannot reach are not ones anybody has asked for — and there is
 * now no escape hatch behind it, so that is a claim this ladder has to keep
 * rather than one the raw field used to cover. See `docs/DIAGNOSTICS-UI.md` §9,
 * decision 2; if a real `err`-without-`warn` case turns up, the honest answer is
 * a sixth stop or a class checkbox set, not a text field.
 *
 * `trace` is folded into [EVERYTHING] rather than given a stop of its own: a
 * channel's trace tier is where every firehose lives, and offering it as "one
 * more notch" is how a ladder makes a 500 MB log look like an increment.
 */
@Serializable
enum class WineChannelLevel(val label: String) {
    /** `-chan` — silenced, below even the `err+all` floor every channel inherits. */
    OFF("Off"),

    /** ERR only, which is the floor `err+all` already gives every channel. */
    ERRORS("Errors"),

    WARNINGS("+ Warnings"),
    STUBS("+ Stubs"),
    EVERYTHING("Everything"),
    ;

    /**
     * Wine's class names for this stop, lowest first. Empty for [OFF] and
     * [EVERYTHING], which are written with the bare `-chan` and `+chan` forms.
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
 * One entry in the channel catalogue: everything the UI needs to draw a channel
 * it has never heard of.
 *
 * The catalogue is **a convenience, not the permitted set** — the picker accepts
 * any name Wine would — so every field here has a defensible fallback and
 * [wineChannelInfo] returns a synthesised entry for anything unknown.
 *
 * @param summary one line, for the picker. The row itself shows only the channel
 *   name: the audience has been told to "turn on `module`", and by the time a row
 *   exists they have already read this.
 * @param caution a property of the channel, shown on its row in the warning tone
 *   whenever present. Data rather than an `if (name == …)` in the composable.
 * @param maxLevel the top of this channel's ladder. Only `d3d` narrows it.
 * @param addAt the level the picker adds it at — the stop that answers the
 *   question the summary poses, which is not always the loudest one.
 * @param oneSessionAt the level at and above which this channel is spent after
 *   one launch. Null for channels that are bounded at every stop.
 */
data class WineChannelInfo(
    val channel: String,
    val summary: String,
    val caution: String? = null,
    val maxLevel: WineChannelLevel = WineChannelLevel.EVERYTHING,
    val addAt: WineChannelLevel = WineChannelLevel.WARNINGS,
    val oneSessionAt: WineChannelLevel? = null,
) {
    /** The stops this row offers, which is the ladder truncated at [maxLevel]. */
    val levels: List<WineChannelLevel>
        get() = WineChannelLevel.entries.filter { it.ordinal <= maxLevel.ordinal }
}

/**
 * The channels worth suggesting, with the question each answers.
 *
 * **Not a channel picker over all 521.** `docs/LOGGING.md:222-230` is explicit
 * about why the Winlator lineage's flat list of every channel Wine has is worse
 * than nothing: choosing well among 521 requires knowing what each costs, and a
 * user who chooses correctly can still get nothing. The rule here is that every
 * entry answers a question somebody has actually had — and because the picker
 * also takes a typed name, being conservative in this list costs an expert one
 * extra keystroke rather than a capability.
 *
 * The first four are the channels the invisible baseline already sets. They are
 * listed so that someone who wants to *change* one — quiet `loaddll`, raise
 * `module` to stubs — can find it, not because they need switching on.
 */
val WINE_CHANNEL_CATALOGUE: List<WineChannelInfo> = listOf(
    WineChannelInfo(
        channel = "module",
        summary = "Says when a library loaded but an entry point inside it is missing, which " +
            "is what later crashes far from the cause.",
        // The whole argument in docs/LOGGING.md:76-95 is about the WARN tier
        // specifically: the three `WARN(` sites in loader.c that say "the DLL
        // loaded but an export is missing" are what `err+all` and `+loaddll`
        // both miss. Already on at this level; here so it can be raised.
        addAt = WineChannelLevel.WARNINGS,
    ),
    WineChannelInfo(
        channel = "loaddll",
        summary = "Every DLL the program successfully loaded. Already on; add it here to quiet " +
            "it down.",
        addAt = WineChannelLevel.EVERYTHING,
    ),
    WineChannelInfo(
        channel = "winediag",
        summary = "Wine's report on its own health: no Vulkan library, no display driver, " +
            "broken .NET, and which renderer it chose. Already on.",
        addAt = WineChannelLevel.EVERYTHING,
    ),
    WineChannelInfo(
        channel = "debugstr",
        summary = "What the program itself chose to print — often the only reason a game gives " +
            "for quitting. Already on.",
        addAt = WineChannelLevel.EVERYTHING,
    ),
    WineChannelInfo(
        channel = "d3d",
        summary = "Wine's own Direct3D, which only runs when a program falls back off DXVK.",
        // docs/LOGGING.md:164-170 — wined3d has 659 `ERR(` sites with only 19
        // `once` guards and owns per-draw paths, so its WARN tier is unbounded
        // in a way `module`'s is not. Stubs and traces above it are not a stop
        // worth drawing; they are the raw field's problem.
        caution = "Unbounded above warnings — 659 per-draw error sites.",
        maxLevel = WineChannelLevel.WARNINGS,
        addAt = WineChannelLevel.ERRORS,
        oneSessionAt = WineChannelLevel.WARNINGS,
    ),
    WineChannelInfo(
        channel = "vulkan",
        summary = "How Wine found and opened the graphics driver.",
    ),
    WineChannelInfo(
        channel = "file",
        summary = "Every file the program opens, and every one it fails to open.",
    ),
    WineChannelInfo(
        channel = "reg",
        summary = "Every registry key the program reads or writes.",
    ),
    WineChannelInfo(
        channel = "heap",
        summary = "Every heap allocation and free.",
        caution = "One line per allocation; a game makes thousands a frame.",
        oneSessionAt = WineChannelLevel.WARNINGS,
    ),
    WineChannelInfo(
        channel = "sync",
        summary = "Every wait, signal and lock the program takes.",
        caution = "Fires on every synchronisation primitive, including the idle ones.",
        oneSessionAt = WineChannelLevel.WARNINGS,
    ),
    WineChannelInfo(
        channel = "msg",
        summary = "Every window message the program receives.",
        caution = "Includes mouse movement, so it fires whenever the screen is touched.",
        oneSessionAt = WineChannelLevel.WARNINGS,
    ),
    WineChannelInfo(
        channel = "relay",
        summary = "Names every call between libraries as it happens.",
        caution = "Hundreds of megabytes in seconds.",
        // Only the trace tier exists: every `_(relay)` site in ntdll is
        // `TRACE_(relay)`, so the lower stops are silence rather than a
        // quieter version of this.
        addAt = WineChannelLevel.EVERYTHING,
        oneSessionAt = WineChannelLevel.EVERYTHING,
    ),
    WineChannelInfo(
        channel = "seh",
        summary = "Every exception the program raises, handled or not — and C++ and .NET " +
            "exceptions are all of them.",
        // ntdll has 7 ERR, 3 WARN and 13 TRACE sites on this channel, and the
        // ERR tier is already on through `err+all`; the trace tier is the one
        // that changes anything, and it is a register dump per exception.
        // docs/LOGGING.md:121-134: crashes print regardless of WINEDEBUG.
        caution = "A register dump per exception, and crashes are already reported without it.",
        addAt = WineChannelLevel.EVERYTHING,
        oneSessionAt = WineChannelLevel.EVERYTHING,
    ),
)

/**
 * The catalogue entry for [channel], or one synthesised for a name nobody
 * anticipated.
 *
 * Never null, because the picker accepts a typed name and the row that results
 * has to draw. An unknown channel gets the full ladder, no caution and no
 * one-session arm — the honest position, since nothing is known about what it
 * costs. It is added at warnings for the same reason the ladder exists: that is
 * the stop that carries information without carrying a trace tier.
 */
fun wineChannelInfo(channel: String): WineChannelInfo =
    WINE_CHANNEL_CATALOGUE.firstOrNull { it.channel == channel }
        ?: WineChannelInfo(channel = channel, summary = "A Wine debug channel this build does " +
            "not have a description for.")

/**
 * A translator's own level control: its variable, its words, and its order.
 *
 * Declared rather than written out three times, so a fourth translator is a list
 * entry. What is *not* shared is the vocabulary: [options] is each tool's own
 * list in each tool's own order, read out of its source, and the UI prints those
 * words in mono without renaming them.
 *
 * @param default the value the fixed environment already sets, so an entry equal
 *   to it contributes nothing.
 * @param oneSessionFrom the first [options] entry that is loud enough to be spent
 *   after one launch; that entry and everything after it in the list.
 */
data class SubsystemLevel(
    val id: String,
    val variable: String,
    val title: String,
    val help: String,
    val options: List<String>,
    val default: String,
    val oneSessionFrom: String? = null,
) {
    val armId: String get() = oneSessionId("level", id)

    fun isOneSession(value: String): Boolean {
        val from = oneSessionFrom ?: return false
        return options.indexOf(value) >= options.indexOf(from)
    }
}

/**
 * The three translator levels, each in its own vocabulary.
 *
 * **DXVK's order and vkd3d's order are different and are not normalised.**
 * DXVK's `DXVK_LOG_LEVEL` is a minimum severity over
 * `trace, debug, info, warn, error, none` (`native/dxvk/src/util/log/log.cpp:146-152`,
 * filtered as `level >= m_minLevel`), listed here quietest-first. vkd3d's is
 * `none, err, info, fixme, warn, trace`
 * (`native/vkd3d/libs/vkd3d-common/debug.c:38-47`, emission
 * `if (get_level(channel) < level) return`) — **`info` sits between `err` and
 * `fixme`**, so `warn` already carries both. Two adjacent six-stop pickers whose
 * stops read differently is correct; a shared "warn is quieter than info" ladder
 * would be a screen that lies about what it sets.
 *
 * vkd3d is two independent channels with two variables, not one with a mode.
 */
val SUBSYSTEM_LEVELS: List<SubsystemLevel> = listOf(
    SubsystemLevel(
        id = "dxvk",
        variable = "DXVK_LOG_LEVEL",
        title = "Direct3D 9 to 11 translator",
        help = "info already names the reason a device was rejected, which is the usual question.",
        options = listOf("none", "error", "warn", "info", "debug", "trace"),
        default = "info",
        // Both report per draw call.
        oneSessionFrom = "debug",
    ),
    SubsystemLevel(
        id = "vkd3d",
        variable = "VKD3D_DEBUG",
        title = "Direct3D 12 translator",
        help = "The Direct3D 12 translator's own messages.",
        options = listOf("none", "err", "info", "fixme", "warn", "trace"),
        default = "warn",
        oneSessionFrom = "trace",
    ),
    SubsystemLevel(
        id = "vkd3d.shader",
        variable = "VKD3D_SHADER_DEBUG",
        title = "Direct3D 12 shader translation",
        help = "Shader compilation failures, which the row above does not carry — it is a " +
            "separate channel with its own level.",
        options = listOf("none", "err", "info", "fixme", "warn", "trace"),
        default = "warn",
        // docs/LOGGING.md:163 — the per-shader firehose.
        oneSessionFrom = "trace",
    ),
)

fun subsystemLevel(id: String): SubsystemLevel? = SUBSYSTEM_LEVELS.firstOrNull { it.id == id }

/**
 * A logger switch: one boolean, one variable, and what each state writes.
 *
 * [whenOn] and [whenOff] are nullable because the interesting state is usually
 * only one of them — the other is what the fixed environment already sets, and
 * writing it again would be a value in the map that changes nothing.
 *
 * @param hint the variable, shown in mono on the right of the row. It is the
 *   name an issue thread will use.
 */
data class SubsystemFlag(
    val id: String,
    val title: String,
    val hint: String,
    val help: String? = null,
    val default: Boolean,
    val variable: String,
    val whenOn: String? = null,
    val whenOff: String? = null,
) {
    /** What this switch contributes at [on], or null for "nothing to say". */
    fun valueAt(on: Boolean): String? = if (on) whenOn else whenOff
}

/** `FEX_SILENTLOG` inverted — the control asks whether FEX may speak. */
val FEX_MESSAGES_FLAG: SubsystemFlag = SubsystemFlag(
    id = "fex",
    title = "FEX messages",
    hint = "x86 translator",
    help = "Off hides mistakes in the translator's own configuration as well as its crashes, " +
        "which is why Vessel leaves it on.",
    default = true,
    variable = "FEX_SILENTLOG",
    // On is FEX's non-default and is what the fixed environment already sets, so
    // only the off state has anything to write.
    whenOff = "1",
)

/**
 * `MESA_LOG=file`, which is the only thing that puts the graphics driver's output
 * where Vessel can read it.
 *
 * Mesa picks its logger at init and under Android the default is logcat
 * (`native/mesa/src/util/log.c:118-128`, `__android_log_write` at `:388`), which
 * this app does not read. `file` adds the file logger and `mesa_log_file`
 * defaults to `stderr` (`log.c:64-74, 145`) — the pipe the session log reads.
 *
 * **Unverified end to end.** The mechanism is read out of Mesa's source; that
 * Turnip's lines actually arrive in the session log has not been observed on the
 * device. What would settle it is one session with this on and
 * `grep -c 'TU_DEBUG='` over the log returning non-zero. Until then it is the
 * gate on [TURNIP_FLAGS] rather than a thing they can be used without.
 */
val DRIVER_LOG_FLAG: SubsystemFlag = SubsystemFlag(
    id = "mesaLog",
    title = "Driver messages in the log",
    hint = "MESA_LOG",
    help = "Without this the graphics driver writes to the Android system log, where Vessel " +
        "cannot read it — including the one line that proves Turnip loaded.",
    default = false,
    variable = "MESA_LOG",
    whenOn = "file",
)

val SUBSYSTEM_FLAGS: List<SubsystemFlag> = listOf(FEX_MESSAGES_FLAG, DRIVER_LOG_FLAG)

fun subsystemFlag(id: String): SubsystemFlag? = SUBSYSTEM_FLAGS.firstOrNull { it.id == id }

/**
 * One Turnip flag worth offering, out of the 42 in
 * `native/mesa/src/freedreno/vulkan/tu_util.cc:21-61`.
 *
 * `TU_DEBUG` is a flag list and not a level — there is no severity anywhere in
 * that table — so this is a multi-select and drawing it as a ladder would be a
 * lie. The rest of the 42 are Mesa-developer flags and belong to the raw field,
 * not to a curated list.
 *
 * `startup` is not here: it is appended unconditionally by [tuDebugFlags] and is
 * the only ground truth for whether Turnip loaded, so it is not a choice.
 */
data class TurnipFlag(val flag: String, val summary: String)

val TURNIP_FLAGS: List<TurnipFlag> = listOf(
    TurnipFlag("perf", "Says why a frame was slow."),
    TurnipFlag("nolrz", "Turns off low-resolution depth, to see whether it is the cause."),
    TurnipFlag("noubwc", "Turns off bandwidth compression, to see whether it is the cause."),
)

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
 * `WINEDEBUG`: the invisible baseline, then whatever has been added.
 *
 * **Never a value handed over whole.** This starts from [WINEDEBUG_CHANNELS] and
 * appends, which is the shape `dllOverrides` already uses and for the same stated
 * reason: Wine parses left to right and a later term wins, so a user can add
 * without being able to delete the defaults by accident.
 *
 * **The per-channel terms are not one term per stop, and that is a fact about the
 * parser rather than a style.** `add_option`
 * (`native/wine/dlls/ntdll/unix/debug.c:88-123`) creates a channel it has not
 * seen with `flags = (default_flags & ~clear) | set` — `default_flags` *as of
 * that moment* — and modifies one it has seen with the same expression against
 * the existing flags. Two consequences:
 *
 *  - A lone `fixme+x` on a fresh channel gives ERR|FIXME and **skips WARN**,
 *    because inheritance is from `default_flags` and not from the stop below.
 *  - A `+`-only term can never *lower* a channel, so `warn+loaddll` cannot quiet
 *    a `loaddll` the baseline already set to all four classes.
 *
 * So a stop is written as a `-chan` reset followed by one `class+chan` per class
 * it wants: `-d3d,err+d3d,warn+d3d`. That is exact for every channel whether or
 * not the baseline already named it, which the ladder in
 * `docs/DIAGNOSTICS-UI.md` §4 is not — that table is right only for channels the
 * baseline does not mention.
 *
 * An empty record returns [WINEDEBUG_CHANNELS] byte for byte.
 *
 * **Per-program scoping is the one thing this cannot express, and it is a gap
 * rather than a decision against it.** Wine's parser accepts
 * `process:class+channel` and matches the token before the `:` against
 * `argv[1]`'s basename, case-insensitively (`debug.c:140-145`), so
 * `metro.exe:+relay` is a legal way to point a firehose at one executable — the
 * difference between a usable log and a full disk. A free-text `WINEDEBUG` field
 * used to sit at the bottom of the Diagnostics screen and could express it; it
 * was removed because it duplicated the *Add a channel* dialog's own free-text
 * field for everything else it could do. Silencing something the baseline
 * switched on survives as the `Off` stop, which composes as `-chan`.
 *
 * **If scoping is wanted back, it belongs on the channel row — a `program`
 * alongside [WineChannelSetting.level], written as `$program:` in front of each
 * term — and not as a second free-text box.** A row that says which executable it
 * applies to is a thing the user can see and remove; a text field that happens to
 * contain a colon is not.
 */
fun composeWineDebug(diagnostics: ContainerDiagnostics): String {
    val record = diagnostics.inForce()
    val terms = ArrayList<String>()
    terms += WINEDEBUG_CHANNELS.split(",")

    record.wineChannels.forEach { terms += wineChannelTerms(it.channel, it.level) }

    return terms.joinToString(",")
}

/**
 * Whether [name] is something Wine's parser would read as a channel.
 *
 * The *Add a channel* dialog takes a typed name and is now the only way into this
 * list that the catalogue does not control, so the check lives here rather than
 * in the composable. `parse_options` splits on `,` and reads `+`, `-` and `:` as
 * structure (`native/wine/dlls/ntdll/unix/debug.c:126-145`), so a name containing
 * one of those is not one channel — it is several terms in a trench coat, and
 * pasting `+relay,-heap` into the field would compose `-+relay` and `-heap` as
 * channel names.
 *
 * The length cap is the same one Wine applies and is the more interesting half:
 * `add_option` returns early on `strlen(name) >= sizeof(debug_options[0].name)`
 * and says nothing, so a name one character too long is a row on this screen that
 * does exactly nothing. Refusing it here is the difference between "that is not a
 * channel" and a switch that silently is not there.
 */
fun isChannelName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= MAX_CHANNEL_NAME &&
        name.none { it.isWhitespace() || it in CHANNEL_NAME_STRUCTURE }

/**
 * Fourteen: `struct __wine_debug_channel` is `char name[15]`
 * (`native/wine/include/wine/debug.h:56-60`) and `add_option` rejects a name
 * whose length reaches that, so fourteen characters is the longest Wine will
 * register.
 */
private const val MAX_CHANNEL_NAME = 14

private const val CHANNEL_NAME_STRUCTURE = ",+-:"

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
 * @param turnipBaseFlags what the rest of the environment has already composed
 *   for `TU_DEBUG`, ending in `startup`. Passed in rather than rebuilt, because
 *   the manifest can contribute flags too and this stage must add to them rather
 *   than replace them.
 */
fun diagnosticEnvironment(
    diagnostics: ContainerDiagnostics,
    turnipBaseFlags: List<String> = listOf(TU_DEBUG_STARTUP),
): Map<String, String> {
    val record = diagnostics.inForce()
    val out = LinkedHashMap<String, String>()

    val wineDebug = composeWineDebug(record)
    if (wineDebug != WINEDEBUG_CHANNELS) out["WINEDEBUG"] = wineDebug

    for (spec in SUBSYSTEM_LEVELS) {
        val value = record.levelOf(spec)
        if (value != spec.default) out[spec.variable] = value
    }

    for (spec in SUBSYSTEM_FLAGS) {
        val on = record.flagOf(spec)
        if (on == spec.default) continue
        spec.valueAt(on)?.let { out[spec.variable] = it }
    }

    if (record.turnipFlags.isNotEmpty()) {
        // Ahead of `startup`, which stays last: the flag list is read left to
        // right and `startup` is the one term nothing may displace.
        out["TU_DEBUG"] = (record.turnipFlags + turnipBaseFlags).distinct().joinToString(",")
    }

    return out
}

/**
 * The copy the confirmation shows before a one-session control is armed.
 *
 * Composed from the catalogue entry rather than written per control, so a new
 * loud channel gets a correct warning by being added to a list. It says the three
 * concrete things — the log fills in seconds, the session gets slower, and the
 * setting turns itself off — because naming the mechanism is what stops the
 * dialog reading as a scary-sounding thing people learn to dismiss.
 */
fun oneSessionWarning(detail: String?): String = buildString {
    if (!detail.isNullOrBlank()) append(detail).append(' ')
    append("The log will hit its cap in seconds and the session will run much slower. ")
    append("It switches itself off after the next launch.")
}

// Two parser hazards a free-text `WINEDEBUG` field has to guard, recorded here
// because the reason this file no longer guards them is a property of the
// composer rather than an oversight — and a future free-text field would need
// both back.
//
//  - `WINEDEBUG=help` kills the process. `init_options` compares the *whole*
//    variable against "help" and calls `debug_usage()`, which writes a usage
//    block to fd 2 and exit(1) (`debug.c:183-193, 213`). Unreachable from a
//    screen that only ever appends to a non-empty baseline; and `help` typed as
//    a channel name is legal, composing as `-help,err+help,…` and never as the
//    bare word.
//  - A later `-all` erases every term before it, because parsing is left to
//    right and `default_flags` is rewritten in place. `isChannelName` refuses
//    `-`, so no row can produce one.
