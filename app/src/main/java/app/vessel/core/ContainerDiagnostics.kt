package app.vessel.core

import kotlinx.serialization.Serializable

/**
 * What this container's next session is asked to log.
 *
 * **One row shape for everything, and the differences are data.** A Wine debug
 * channel, `DXVK_LOG_LEVEL`, the FEX logging switch, a Turnip flag and a term
 * out of the fixed prefix are all the same record — `(name, level)` —
 * rendered by one composable. What differs between them is declared in
 * [LOGGABLES]: which ladder the level dropdown offers and in whose vocabulary,
 * whether the row can be edited, whether it is spent after one launch, whether it
 * carries a caution, and **how it composes into an environment variable**.
 *
 * That last one is the part that would otherwise become a `when` over a type, so
 * it is polymorphic instead: [Emit] carries the strategy and the composer is a
 * fold with no branch in it. Adding a loggable thing is one entry in [LOGGABLES]
 * and no change to any composable, any `when`, or any test that is not about that
 * entry. **No name declared here appears as a literal anywhere in the UI layer.**
 *
 * **The fixed prefix is shown, not hidden.** Every term Vessel always sends —
 * `-all,err+all`, `warn+module`, `+winediag`, `+loaddll`, `+debugstr`,
 * `DXVK_LOG_PATH=none`, `FEX_OUTPUTLOG=stderr`, `TU_DEBUG=startup` — is a
 * [Loggable] with a [Loggable.fixedLevel], so the screen is a truthful inventory
 * of what is being sent rather than a list of additions over an invisible
 * baseline. Their *behaviour* is unchanged: they are still not settings, still
 * always on, and still unreachable by a manifest param. Only their visibility
 * changed.
 *
 * **A read-only row and an `Off` row are not the same thing and must not look
 * alike.** A read-only row shows its real value — never `Off` — with its controls
 * at the disabled opacity and no remove cross; an `Off` row is a live control the
 * user set to silence, and composes as `-chan`. [fixedRowsNeverReadOff] is the
 * property that keeps the first half of that true, and it is asserted.
 *
 * **A separate schema from `assets/params-manifest.json`, on purpose.** The
 * manifest's law is that a setting must be explainable in one plain sentence to
 * someone who does not know what a translator is (`params-manifest.json:9-12`).
 * `VKD3D_SHADER_DEBUG` is not. Diagnostics has a different audience — someone
 * whose session is already broken — so it gets a different surface, a different
 * schema and different rules. See `docs/DIAGNOSTICS-UI.md` §1.
 */
@Serializable
data class ContainerDiagnostics(
    /**
     * The rows the user added, in the order they added them.
     *
     * A list rather than a map: the rows are a thing the user built, their order
     * is theirs, and it is also the order terms are written in — which matters,
     * because Wine parses left to right. Addressed by index everywhere, because a
     * name is editable and may be empty or duplicated while it is being typed.
     */
    val rows: List<DiagnosticSetting> = emptyList(),
    /**
     * Whole environment variables, typed by name and value.
     *
     * **The escape hatch, and the reason it belongs here rather than in the
     * manifest.** Every graphics experiment so far needed a manifest entry and a
     * build before it could be tried once — six of them in one afternoon, each
     * one a data change to answer a question that took thirty seconds. Turnip,
     * Mesa and DXVK between them expose hundreds of variables and no curated list
     * will ever contain the one somebody reads about next.
     *
     * The manifest keeps the settings that carry a *reason*: a plain sentence
     * about what the thing does and what it costs. That is its stated law and it
     * is worth keeping. This is for the seventh variable, which has no sentence
     * yet because nobody has learned it.
     *
     * **[RESERVED_SESSION_ENV] still wins.** A free-text table could otherwise
     * point `WINEPREFIX` outside the container or move `VESSEL_GFX_STATS`
     * somewhere the app then reads — the reserved set is not a style rule, it is
     * the boundary that keeps a container document from reaching outside its own
     * directory, and it is enforced against this list exactly as against the
     * manifest.
     *
     * Defaulted, so every document written before this parses.
     */
    val env: List<EnvSetting> = emptyList(),
    val limits: SessionLogLimits = SessionLogLimits(),
) {
    /** True when the user has added nothing, which is what a fresh container is. */
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * The record with anything its gate forbids removed.
     *
     * Every read path starts here, so a hand-edited or half-migrated document
     * cannot arrange for a switch whose output nothing can read — see
     * [Loggable.gate].
     */
    fun inForce(): ContainerDiagnostics = copy(rows = rows.filter { it.isAllowed(this) })

    /**
     * The record to store once this session has taken its copy.
     *
     * Every row loud enough to be one-session spends its one launch
     * here and is gone. Writing this back at launch rather than at teardown is
     * what makes "one session" survive the app being killed mid-run: the row is
     * already gone from the stored document by the time the guest process exists,
     * so the run after a crash gets the ordinary environment.
     */
    fun consumed(): ContainerDiagnostics =
        copy(rows = rows.filterNot { it.isOneSession })

    // — edits, all addressed by row index -------------------------------------

    /** Append an empty row, which contributes nothing until it is named. */
    fun withRowAdded(turnip: Boolean = false): ContainerDiagnostics =
        copy(rows = rows + DiagnosticSetting(turnip = turnip))

    fun withRowRemoved(index: Int): ContainerDiagnostics =
        if (index !in rows.indices) this else copy(rows = rows.filterIndexed { i, _ -> i != index })

    /**
     * Rename a row, and reset its level to what that thing wants.
     *
     * Resetting rather than keeping the old value is the only coherent choice: a
     * level is a word in one subsystem's vocabulary, and carrying `+ Warnings`
     * over to `DXVK_LOG_LEVEL` would leave a row holding a value its own ladder
     * does not contain.
     */
    fun withRowNamed(index: Int, name: String): ContainerDiagnostics {
        if (index !in rows.indices) return this
        val loggable = loggableFor(name, rows[index].turnip)
        return copy(
            rows = rows.mapIndexed { i, row ->
                if (i != index) {
                    row
                } else {
                    // The origin is carried, not recomputed: renaming within a
                    // table must not move the row to the other one.
                    DiagnosticSetting(name, loggable.addAt, turnip = row.turnip)
                }
            },
        )
    }

    fun withRowLevel(index: Int, level: String): ContainerDiagnostics = mapRow(index) {
        it.copy(level = level)
    }

    fun withLimits(limits: SessionLogLimits): ContainerDiagnostics = copy(limits = limits)

    // — the environment table, addressed by index for the same reason ----------

    fun withEnvAdded(): ContainerDiagnostics = copy(env = env + EnvSetting())

    fun withEnvRemoved(index: Int): ContainerDiagnostics =
        if (index !in env.indices) this else copy(env = env.filterIndexed { i, _ -> i != index })

    fun withEnvName(index: Int, name: String): ContainerDiagnostics =
        mapEnv(index) { it.copy(name = name) }

    fun withEnvValue(index: Int, value: String): ContainerDiagnostics =
        mapEnv(index) { it.copy(value = value) }

    private fun mapEnv(index: Int, block: (EnvSetting) -> EnvSetting) =
        if (index !in env.indices) this
        else copy(env = env.mapIndexed { i, row -> if (i == index) block(row) else row })

    /**
     * The variables this container adds, with anything it may not set dropped.
     *
     * Composed here rather than in the session layer so that one function is the
     * only place the rule lives, and so a test can assert the rule without
     * building an environment.
     */
    fun environmentOverrides(): Map<String, String> =
        env.asSequence()
            .map { it.name.trim() to it.value }
            .filter { (name, _) -> name.isNotEmpty() && name !in RESERVED_SESSION_ENV }
            .toMap()

    private fun mapRow(index: Int, block: (DiagnosticSetting) -> DiagnosticSetting) =
        if (index !in rows.indices) this
        else copy(rows = rows.mapIndexed { i, row -> if (i == index) block(row) else row })

    /** The current value of [id], as a row or as its declared baseline. */
    internal fun valueOf(id: String): String {
        val declared = LOGGABLES.firstOrNull { it.id == id } ?: return ""
        return rows.lastOrNull { loggableFor(it.name).id == id }?.level ?: declared.baseline
    }

    companion object {
        val DEFAULT: ContainerDiagnostics = ContainerDiagnostics()
    }
}

/**
 * One environment variable the user typed, name and value.
 *
 * No level, no ladder and no declaration: the whole point is that Vessel knows
 * nothing about it. An empty name contributes nothing, which is what a row that
 * has just been added is.
 */
@Serializable
data class EnvSetting(
    val name: String = "",
    val value: String = "",
) {
    /** True for a name this layer owns and will not let a container set. */
    val isReserved: Boolean get() = name.trim() in RESERVED_SESSION_ENV
}

/** One row the user added: what to log, how loudly, and for how long. */
@Serializable
data class DiagnosticSetting(
    /**
     * What is being logged — a declared [Loggable.name], or anything the user
     * typed. Empty on a row that has just been added and not yet named.
     */
    val name: String = "",
    /** A stop from that thing's own ladder. */
    val level: String = "",
    /**
     * Which table added this row.
     *
     * **A row that has just been added has no name**, so nothing about it says
     * which list it belongs in — and `loggableFor("")` synthesises a Wine channel,
     * so a row added from the graphics table appeared under logging. Remembering
     * the origin is the only thing that can answer for an empty row; once named,
     * the name agrees with it because each table's picker offers only its own.
     *
     * Defaulted, so every container document written before this parses.
     */
    val turnip: Boolean = false,
) {
    /**
     * Whether this row is loud enough to be spent after one launch.
     *
     * A property of the level and not of the thing: `DXVK_LOG_LEVEL` at `warn` is
     * ordinary and at `trace` is a firehose, and the row has to tell them apart.
     */
    val isOneSession: Boolean get() = loggableFor(name, turnip).isOneSession(level)

    /** False while the row's gate is unmet, which is when it must contribute nothing. */
    fun isAllowed(diagnostics: ContainerDiagnostics): Boolean {
        val loggable = loggableFor(name, turnip)
        if (!loggable.isRealValue(level)) return false
        val gate = loggable.gate ?: return true
        val gated = LOGGABLES.firstOrNull { it.id == gate } ?: return true
        return diagnostics.valueOf(gate) != gated.baseline
    }
}

// — the declaration -----------------------------------------------------------

/**
 * How one loggable thing turns into environment.
 *
 * Polymorphic rather than a sealed class with a `when` over it, because the whole
 * point of the row model is that adding a thing is a data edit: a `when` in the
 * composer is a second place every new entry would have to be remembered in.
 * There are three strategies and there are only three because there are only
 * three shapes of variable in this stack — a term list, a scalar, and a
 * comma-joined set.
 */
sealed interface Emit {
    fun apply(value: String, out: EmittedEnvironment)

    /**
     * A channel inside `WINEDEBUG`, written as the terms that force exactly
     * [value].
     *
     * **Not one term per stop, and that is a fact about the parser rather than a
     * style.** `add_option` (`native/wine/dlls/ntdll/unix/debug.c:88-123`) creates
     * a channel it has not seen with `flags = (default_flags & ~clear) | set` —
     * `default_flags` *as of that moment* — and modifies one it has seen with the
     * same expression against the existing flags. Two consequences: a lone
     * `fixme+x` on a fresh channel gives ERR|FIXME and **skips WARN**, and a
     * `+`-only term can never *lower* a channel, so `warn+loaddll` cannot quiet a
     * `loaddll` the fixed prefix already set to all four classes. So a stop is
     * written as a `-chan` reset followed by one `class+chan` per class it wants.
     */
    data class WineChannel(val channel: String) : Emit {
        override fun apply(value: String, out: EmittedEnvironment) {
            val level = WineChannelLevel.entries.firstOrNull { it.name == value } ?: return
            out.wineTerms += wineChannelTerms(channel, level)
        }
    }

    /**
     * A scalar variable, written when [value] differs from what the fixed block
     * already sets.
     *
     * [baseline] is that fixed value, so a row sitting where the environment
     * already is contributes nothing — which is what keeps a default record's
     * map empty.
     */
    data class Variable(val variable: String, val baseline: String) : Emit {
        override fun apply(value: String, out: EmittedEnvironment) {
            if (value != baseline) out.variables[variable] = value
        }
    }

    /** One member of a comma-joined list variable, present when [value] is [ON]. */
    data class ListMember(val variable: String, val flag: String) : Emit {
        override fun apply(value: String, out: EmittedEnvironment) {
            if (value == ON) out.lists.getOrPut(variable) { ArrayList() } += flag
        }
    }

    /**
     * Sent by the fixed block, reported here and not composed.
     *
     * A row with this strategy exists so the screen can say what is being sent;
     * writing the same value again would put it in the map for a container nobody
     * has touched.
     */
    data object Fixed : Emit {
        override fun apply(value: String, out: EmittedEnvironment) = Unit
    }

    companion object {
        /** The `on` stop of a two-stop ladder. */
        const val ON: String = "on"
        const val OFF: String = "off"
    }
}

/** The three shapes of variable, being filled in. */
class EmittedEnvironment {
    val wineTerms: MutableList<String> = ArrayList()
    val variables: MutableMap<String, String> = LinkedHashMap()
    val lists: MutableMap<String, MutableList<String>> = LinkedHashMap()
}

/**
 * One thing that can be logged, and everything the screen and the composer need
 * to know about it.
 *
 * @param name column one, and the name a user types to reach it. For a Wine
 *   channel it is the channel; for a variable it is the variable; for a member of
 *   a list variable it is the member — which is the same relationship in all
 *   three cases, a name inside something.
 * @param secondary the line under the name, in the muted tone.
 * @param caution the line under the name in the warning tone. A property of the
 *   thing, shown whenever the row exists — a channel does not stop being
 *   expensive because somebody set it to `Off`.
 * @param levels the ladder, **in that subsystem's own vocabulary and order**.
 *   vkd3d's `none, err, info, fixme, warn, trace` is not normalised to DXVK's.
 * @param labels display names for [levels] where the wire value is not
 *   presentable — an unset variable, or an on/off pair.
 * @param baseline what the environment already holds for this thing. A row at the
 *   baseline contributes nothing.
 * @param fixedLevel non-null makes the row read-only, and is what it displays.
 *   Must never be a stop that reads `Off`; see [fixedRowsNeverReadOff].
 * @param addAt the stop a freshly named row takes — the one that answers the
 *   question the entry poses, which is not always the loudest.
 * @param oneSessionFrom the first entry in [levels] loud enough to be spent after
 *   one launch — that stop and everything after it in the list. Null for a thing
 *   that is bounded at every stop. A property of the *level*, not of the thing:
 *   `DXVK_LOG_LEVEL` at `warn` is ordinary and at `trace` is a firehose.
 * @param gate the [id] of another entry that must be off its baseline before this
 *   one does anything. A gated row is drawn disabled *with* its caution and its
 *   remove cross, which is what distinguishes it from a fixed one.
 * @param levelIsMachine set the value in mono, for a ladder whose stops are a
 *   tool's own words rather than English.
 */
data class Loggable(
    val name: String,
    val emit: Emit,
    val levels: List<String>,
    val baseline: String,
    val secondary: String? = null,
    val caution: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val fixedLevel: String? = null,
    val addAt: String = baseline,
    val oneSessionFrom: String? = null,
    val gate: String? = null,
    val levelIsMachine: Boolean = false,
) {
    /** Stable across renames of the display text; used by [gate] and by tests. */
    val id: String get() = name

    /** Gets a read-only row at the head of the list, saying what is already sent. */
    val isFixed: Boolean get() = fixedLevel != null

    /**
     * Which table this belongs in.
     *
     * **Derived from `emit`, never declared**, so a flag cannot end up in one
     * list and be sent through the other. `TU_DEBUG` is the only variable whose
     * members are not log channels: half of them change how the frame is drawn.
     * Listing "turn off low-resolution depth" under *What to log* described it as
     * a logging choice, which is the one thing it is not.
     */
    val isTurnipFlag: Boolean
        get() = emit is Emit.ListMember && (emit as Emit.ListMember).variable == "TU_DEBUG"

    /**
     * Whether the user may add a row for this.
     *
     * Not the inverse of [isFixed]: the four Wine channels the prefix names are
     * both — shown read-only *and* addable, because adding one is how you quiet
     * or raise something already on. Only a thing with nothing to compose
     * ([Emit.Fixed]) is unaddable, because a row for it could not do anything.
     */
    val isAddable: Boolean get() = emit !is Emit.Fixed

    fun label(level: String): String = labels[level] ?: level

    /** Whether [level] is a stop this thing actually has. */
    fun isRealValue(level: String): Boolean = level in levels

    /** Whether [level] is at or past the stop that makes this a one-launch row. */
    fun isOneSession(level: String): Boolean {
        val from = oneSessionFrom ?: return false
        val at = levels.indexOf(level)
        return at >= 0 && at >= levels.indexOf(from)
    }
}

/** The Wine class ladder as level wire values, for [wineLevels]. */
private val WINE_LEVELS: List<String> = WineChannelLevel.entries.map { it.name }
private val WINE_LEVEL_LABELS: Map<String, String> =
    WineChannelLevel.entries.associate { it.name to it.label }

/** The ladder for a Wine channel, optionally truncated. */
private fun wineLevels(max: WineChannelLevel = WineChannelLevel.EVERYTHING): List<String> =
    WINE_LEVELS.take(max.ordinal + 1)

/** A two-stop ladder, for a switch. */
private val ON_OFF: List<String> = listOf(Emit.OFF, Emit.ON)
private val ON_OFF_LABELS: Map<String, String> = mapOf(Emit.OFF to "Off", Emit.ON to "On")

private fun wineChannel(
    channel: String,
    secondary: String,
    caution: String? = null,
    max: WineChannelLevel = WineChannelLevel.EVERYTHING,
    addAt: WineChannelLevel = WineChannelLevel.WARNINGS,
    fixed: WineChannelLevel? = null,
    oneSessionFrom: WineChannelLevel? = null,
    addable: Boolean = true,
) = Loggable(
    name = channel,
    // A real strategy even when the row is fixed. `fixedLevel` makes the
    // *inventory* row read-only; it must not stop the user adding their own row
    // for the same channel and overriding it, which is the whole reason the
    // parser reads left to right and a later term wins. `addable = false` is for
    // the one pseudo-channel where that is not true — see `all`.
    emit = if (addable) Emit.WineChannel(channel) else Emit.Fixed,
    levels = wineLevels(max),
    labels = WINE_LEVEL_LABELS,
    // Every channel Vessel does not name inherits ERR from `err+all`, which is
    // why `Off` is a real action on a channel nobody has switched on.
    baseline = WineChannelLevel.ERRORS.name,
    secondary = secondary,
    caution = caution,
    fixedLevel = fixed?.name,
    addAt = addAt.name,
    oneSessionFrom = oneSessionFrom?.name,
)

/**
 * Everything that can appear as a row, in the order the screen draws it.
 *
 * The fixed entries come first, because they are what is already happening and
 * the rest of the list is read against them.
 *
 * **Curated, not complete.** `docs/LOGGING.md:222-230` is explicit about why the
 * Winlator lineage's flat list of all 521 Wine channels is worse than nothing:
 * choosing well among 521 requires knowing what each costs, and a user who
 * chooses correctly can still get nothing. The rule here is that every entry
 * answers a question somebody has actually had — and the name column takes typed
 * text, so being conservative costs an expert one keystroke rather than a
 * capability.
 */
val LOGGABLES: List<Loggable> = listOf(
    // — what the fixed prefix already sends, shown and not editable ------------
    wineChannel(
        channel = "all",
        secondary = "Every channel, including ones nothing names.",
        // `-all,err+all` is exactly the ERRORS stop's own term pair, which is
        // what lets one row stand for both terms honestly.
        fixed = WineChannelLevel.ERRORS,
        // **Shown, never addable.** `all` is not a channel, it is the parser's
        // word for `default_flags`, and neither direction is a diagnostic action:
        // `+all` is every class on every channel — a firehose beyond `relay` —
        // and a second `-all` after the prefix erases every term before it,
        // which is the exact hazard `docs/LOGGING.md:51-55` exists to prevent.
        // Leaving it out of the name column is what makes that unreachable
        // rather than merely discouraged.
        addable = false,
    ),
    wineChannel(
        channel = "module",
        secondary = "A library loaded but an entry point inside it is missing.",
        // docs/LOGGING.md:76-95 is an argument about the WARN tier specifically:
        // the three `WARN(` sites in loader.c that say "the DLL loaded but an
        // export is missing" are what `err+all` and `+loaddll` both miss.
        fixed = WineChannelLevel.WARNINGS,
    ),
    wineChannel(
        channel = "winediag",
        secondary = "Wine's report on its own health, and which renderer it chose.",
        fixed = WineChannelLevel.EVERYTHING,
    ),
    wineChannel(
        channel = "loaddll",
        secondary = "Every DLL the program successfully loaded.",
        fixed = WineChannelLevel.EVERYTHING,
    ),
    wineChannel(
        channel = "debugstr",
        secondary = "What the program itself chose to print.",
        fixed = WineChannelLevel.EVERYTHING,
    ),
    Loggable(
        name = "DXVK_LOG_PATH",
        emit = Emit.Fixed,
        levels = listOf(FIXED_DXVK_LOG_PATH),
        baseline = FIXED_DXVK_LOG_PATH,
        fixedLevel = FIXED_DXVK_LOG_PATH,
        secondary = "Keeps the Direct3D translator's output in this log instead of beside it.",
        levelIsMachine = true,
    ),
    Loggable(
        name = "FEX_OUTPUTLOG",
        emit = Emit.Fixed,
        levels = listOf(FIXED_FEX_OUTPUTLOG),
        baseline = FIXED_FEX_OUTPUTLOG,
        fixedLevel = FIXED_FEX_OUTPUTLOG,
        // Source/Windows/Common/Logging.cpp:36-49 is the whole Windows logging
        // init and reads SILENTLOG and nothing else. Shown rather than hidden so
        // nobody re-adds it as a control.
        secondary = "Set as a marker; it does nothing on this platform.",
        levelIsMachine = true,
    ),
    Loggable(
        name = TU_DEBUG_STARTUP,
        emit = Emit.Fixed,
        levels = listOf(Emit.ON),
        labels = ON_OFF_LABELS,
        baseline = Emit.ON,
        fixedLevel = Emit.ON,
        secondary = "TU_DEBUG — the only ground truth that Turnip loaded at all.",
    ),

    // — the translators and the driver, in their own vocabularies --------------
    Loggable(
        name = "DXVK_LOG_LEVEL",
        emit = Emit.Variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL),
        // native/dxvk/src/util/log/log.cpp:146-152, a minimum severity filtered
        // as `level >= m_minLevel`, listed quietest first.
        levels = listOf("none", "error", "warn", "info", "debug", "trace"),
        baseline = FIXED_DXVK_LOG_LEVEL,
        secondary = "Direct3D 9 to 11 translator. info already names why a device was rejected.",
        caution = "debug and trace report per draw call.",
        addAt = "debug",
        oneSessionFrom = "debug",
        levelIsMachine = true,
    ),
    Loggable(
        name = "VKD3D_DEBUG",
        emit = Emit.Variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG),
        // native/vkd3d/libs/vkd3d-common/debug.c:38-47. `info` sits between `err`
        // and `fixme`, so `warn` already carries both. Not normalised to DXVK's.
        levels = listOf("none", "err", "info", "fixme", "warn", "trace"),
        baseline = FIXED_VKD3D_DEBUG,
        secondary = "Direct3D 12 translator's own messages.",
        caution = "trace reports every Direct3D 12 call.",
        addAt = "trace",
        oneSessionFrom = "trace",
        levelIsMachine = true,
    ),
    Loggable(
        name = "VKD3D_SHADER_DEBUG",
        emit = Emit.Variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG),
        levels = listOf("none", "err", "info", "fixme", "warn", "trace"),
        baseline = FIXED_VKD3D_SHADER_DEBUG,
        secondary = "Shader compilation failures, which the row above does not carry.",
        caution = "trace is the per-shader firehose.",
        addAt = "trace",
        oneSessionFrom = "trace",
        levelIsMachine = true,
    ),
    Loggable(
        name = "FEX_SILENTLOG",
        emit = Emit.Variable("FEX_SILENTLOG", FIXED_FEX_SILENTLOG),
        levels = listOf("0", "1"),
        labels = mapOf("0" to "0  speaks", "1" to "1  silent"),
        baseline = FIXED_FEX_SILENTLOG,
        secondary = "Whether the x86 translator may report. Silent hides its own " +
            "configuration mistakes as well as its crashes.",
        addAt = "1",
        levelIsMachine = true,
    ),
    Loggable(
        name = MESA_LOG_VAR,
        emit = Emit.Variable(MESA_LOG_VAR, FIXED_MESA_LOG),
        levels = listOf(FIXED_MESA_LOG, "file"),
        labels = mapOf(FIXED_MESA_LOG to "not set"),
        baseline = FIXED_MESA_LOG,
        // native/mesa/src/util/log.c:118-128 — under Android the default logger
        // is logcat, which this app does not read. `file` adds the file logger
        // and `mesa_log_file` defaults to stderr (log.c:64-74, 145), the pipe the
        // session log reads. Unverified end to end; see docs/LOGGING.md.
        secondary = "Without this the graphics driver writes to the Android system log, " +
            "where Vessel cannot read it.",
        addAt = "file",
        levelIsMachine = true,
    ),

    // — Turnip flags that only *say* something. Gated on the driver logger, ----
    //   because without it their output goes to logcat, which Vessel cannot read.
    turnipLogFlag("perf", "Says why a frame was slow — including why the driver turned off " +
        "low-resolution depth, and where it stopped rendering in tiles."),
    turnipLogFlag(
        "log_skip_gmem_ops",
        "Counts the tile loads and stores the driver managed to skip.",
    ),

    // — Turnip flags that change how the frame is drawn. NOT gated. -------------
    //
    // **These used to require MESA_LOG and that was wrong.** A flag that turns
    // off low-resolution depth changes the rendering whether or not anybody can
    // read a log about it, and the whole point of `nolrz` is the null test: run
    // it, watch the frame rate, and if nothing moves then low-resolution depth
    // was doing nothing for this game and a whole line of work can be dropped.
    // Requiring a logger to perform a measurement that is taken off the frame
    // counter made the cheapest experiment in the project needlessly expensive.
    turnipRenderFlag(
        "nolrz",
        "Turns off low-resolution depth. If the frame rate does not change, this " +
            "GPU's biggest saving was already doing nothing here.",
    ),
    turnipRenderFlag(
        "noubwc",
        "Turns off bandwidth compression. If this costs a lot, compression is " +
            "carrying the frame and is worth protecting.",
    ),
    turnipRenderFlag(
        "gmem",
        "Forces drawing in tiles. The driver ships a rule that opts Direct3D games " +
            "out of tiling; this is how to ask whether that rule is right here.",
    ),
    turnipRenderFlag(
        "sysmem",
        "Forces drawing straight to memory, never in tiles — the opposite of the row above.",
    ),
    turnipRenderFlag("forcebin", "Always splits the screen into tiles, whatever the driver decided."),
    turnipRenderFlag("nobin", "Never splits the screen into tiles."),

    // — Wine channels worth suggesting -----------------------------------------
    wineChannel(
        channel = "d3d",
        secondary = "Wine's own Direct3D, which only runs when a program falls off DXVK.",
        // docs/LOGGING.md:164-170 — wined3d has 659 `ERR(` sites with only 19
        // `once` guards and owns per-draw paths, so its WARN tier is unbounded in
        // a way `module`'s is not. Stubs and traces above it are not a stop worth
        // offering at all.
        caution = "Unbounded above warnings — 659 per-draw error sites.",
        max = WineChannelLevel.WARNINGS,
        addAt = WineChannelLevel.WARNINGS,
        oneSessionFrom = WineChannelLevel.WARNINGS,
    ),
    wineChannel("vulkan", "How Wine found and opened the graphics driver."),
    wineChannel(
        channel = "oss",
        secondary = "The audio driver: what the guest wrote and what the device took.",
        // The channel `patches/wine/0008`'s AAudio driver logs under, kept from
        // the OSS driver it replaced. Its trace tier prints one line per top-up —
        // ten a second, naming the queue depth — which is what tells a stalled
        // *timer* apart from a guest that is not producing frames, and is exactly
        // the question crackling audio asks. Bounded by the period rather than by
        // the workload, so it needs no one-session cap.
    ),
    wineChannel("file", "Every file the program opens, and every one it fails to open."),
    wineChannel("reg", "Every registry key the program reads or writes."),
    wineChannel(
        channel = "heap",
        secondary = "Every heap allocation and free.",
        caution = "One line per allocation; a game makes thousands a frame.",
        oneSessionFrom = WineChannelLevel.WARNINGS,
    ),
    wineChannel(
        channel = "sync",
        secondary = "Every wait, signal and lock the program takes.",
        caution = "Fires on every synchronisation primitive, including idle ones.",
        oneSessionFrom = WineChannelLevel.WARNINGS,
    ),
    wineChannel(
        channel = "msg",
        secondary = "Every window message the program receives.",
        caution = "Includes mouse movement, so it fires whenever the screen is touched.",
        oneSessionFrom = WineChannelLevel.WARNINGS,
    ),
    wineChannel(
        channel = "relay",
        secondary = "Names every call between libraries as it happens.",
        caution = "Hundreds of megabytes in seconds.",
        // Only the trace tier exists: every `_(relay)` site in ntdll is
        // `TRACE_(relay)`, so the lower stops are silence rather than a quieter
        // version of this.
        addAt = WineChannelLevel.EVERYTHING,
        oneSessionFrom = WineChannelLevel.EVERYTHING,
    ),
    wineChannel(
        channel = "seh",
        secondary = "Every exception raised, handled or not — and C++ and .NET are all of them.",
        // ntdll has 7 ERR, 3 WARN and 13 TRACE sites here, and the ERR tier is
        // already on through `err+all`. docs/LOGGING.md:121-134: crashes print
        // regardless of WINEDEBUG.
        caution = "A register dump per exception, and crashes are reported without it.",
        addAt = WineChannelLevel.EVERYTHING,
        oneSessionFrom = WineChannelLevel.EVERYTHING,
    ),
)

/**
 * A `TU_DEBUG` flag whose only effect is output. Gated on the driver logger.
 *
 * TU_DEBUG is a flag list with no severity in it, so each is a switch and not a
 * ladder — and one of these produces nothing a user can read until Mesa's file
 * logger is on, because Turnip writes to logcat and Vessel does not read logcat.
 */
private fun turnipLogFlag(flag: String, secondary: String) = Loggable(
    name = flag,
    emit = Emit.ListMember("TU_DEBUG", flag),
    levels = ON_OFF,
    labels = ON_OFF_LABELS,
    baseline = Emit.OFF,
    secondary = "TU_DEBUG — $secondary",
    addAt = Emit.ON,
    gate = MESA_LOG_VAR,
    caution = "Unavailable until $MESA_LOG_VAR is set — without it these produce output " +
        "the product cannot read.",
)

/**
 * A `TU_DEBUG` flag that changes how the frame is drawn. **Not gated.**
 *
 * The distinction is not cosmetic. A log flag is useless without a logger; one
 * of these is measured on the frame counter, and gating it behind `MESA_LOG`
 * would make the cheapest experiment available — turn off low-resolution depth,
 * see whether anything moves — require a second setting it has no need of.
 *
 * The caution is a different one for the same reason: these change what is
 * rendered, and two of them (`gmem`/`sysmem`, `forcebin`/`nobin`) are opposing
 * pairs that must not be switched on together. Turnip resolves a contradiction
 * silently rather than complaining, so the result would be a measurement of
 * nothing in particular.
 */
private fun turnipRenderFlag(flag: String, secondary: String) = Loggable(
    name = flag,
    emit = Emit.ListMember("TU_DEBUG", flag),
    levels = ON_OFF,
    labels = ON_OFF_LABELS,
    baseline = Emit.OFF,
    secondary = "TU_DEBUG — $secondary",
    addAt = Emit.ON,
    caution = "Changes how frames are drawn, not what is logged. Switch on one at a " +
        "time: the driver resolves a contradictory pair silently.",
)

/** The one variable another entry's [Loggable.gate] names. */
private const val MESA_LOG_VAR = "MESA_LOG"

/**
 * The declared entry for [name], or one synthesised for a Wine channel nobody
 * anticipated.
 *
 * Never null, because the name column takes typed text and the row that results
 * has to draw. An unknown name gets the full Wine ladder, no caution and
 * no one-launch stop — the honest position, since nothing is known about what it
 * costs.
 */
fun loggableFor(name: String, turnip: Boolean = false): Loggable =
    LOGGABLES.firstOrNull { it.name == name }
        ?: if (turnip) {
            unknownTurnipFlag(name)
        } else {
            wineChannel(name, "A Wine debug channel this build has no description for.")
        }

/**
 * A `TU_DEBUG` member this build has no description for, typed by the user.
 *
 * **The graphics table takes typed names for the same reason the logging one
 * does.** Turnip has far more flags than the handful worth curating, they change
 * between Mesa versions, and the alternative to accepting a typed one is a code
 * change to try a flag someone read about ten minutes ago. Synthesising it as a
 * Wine channel — which is what the single-argument [loggableFor] does, and did —
 * would have quietly composed it into `WINEDEBUG`, where Wine would ignore an
 * unknown channel and the user would conclude the flag did nothing.
 *
 * **Not gated on the driver logger, and that is the honest choice.** Nothing here
 * can tell whether an unknown flag reports or renders, and gating it would make
 * a rendering flag unreachable without a logger it has no use for. The caution
 * says what is and is not known instead.
 */
private fun unknownTurnipFlag(flag: String) = Loggable(
    name = flag,
    emit = Emit.ListMember("TU_DEBUG", flag),
    levels = ON_OFF,
    labels = ON_OFF_LABELS,
    baseline = Emit.OFF,
    secondary = "TU_DEBUG — a flag this build has no description for.",
    addAt = Emit.ON,
    caution = "Vessel does not know this flag. Turnip ignores one it does not " +
        "recognise, so a typo looks exactly like a flag that did nothing.",
)

/** The entries the name column offers, which is everything that can compose. */
val ADDABLE_LOGGABLES: List<Loggable> = LOGGABLES.filter { it.isAddable }

/** The addable log channels — everything except the graphics-driver flags. */
val ADDABLE_LOG_LOGGABLES: List<Loggable> = ADDABLE_LOGGABLES.filterNot { it.isTurnipFlag }

/** The addable `TU_DEBUG` members, which are their own table. */
val ADDABLE_TURNIP_LOGGABLES: List<Loggable> = ADDABLE_LOGGABLES.filter { it.isTurnipFlag }

/**
 * Whether [name] is something Wine's parser would read as a channel.
 *
 * The name column takes typed text, so the check lives here rather than in a
 * composable. `parse_options` splits on `,` and reads `+`, `-` and `:` as
 * structure (`native/wine/dlls/ntdll/unix/debug.c:126-145`), so a name containing
 * one is not one channel — pasting `+relay,-heap` would compose `-+relay` as a
 * channel name. The length cap is the more interesting half: `add_option` returns
 * early on `strlen(name) >= sizeof(debug_options[0].name)` and says nothing, so a
 * name one character too long is a row that does exactly nothing.
 *
 * A declared entry is exempt: `DXVK_LOG_LEVEL` is a variable, not a channel, and
 * is never written into `WINEDEBUG`.
 */
fun isLoggableName(name: String): Boolean =
    LOGGABLES.any { it.name == name } ||
        (
            name.isNotEmpty() &&
                name.length <= MAX_CHANNEL_NAME &&
                name.none { it.isWhitespace() || it in CHANNEL_NAME_STRUCTURE }
            )

/**
 * Fourteen: `struct __wine_debug_channel` is `char name[15]`
 * (`native/wine/include/wine/debug.h:56-60`) and `add_option` rejects a name whose
 * length reaches that, so fourteen characters is the longest Wine will register.
 */
private const val MAX_CHANNEL_NAME = 14

private const val CHANNEL_NAME_STRUCTURE = ",+-:"

// Two parser hazards a free-text `WINEDEBUG` field would have to guard, recorded
// because the reason this file does not guard them is a property of the composer
// rather than an oversight.
//
//  - `WINEDEBUG=help` kills the process: `init_options` compares the *whole*
//    variable against "help" and calls `debug_usage()`, which writes a usage
//    block to fd 2 and exit(1) (`debug.c:183-193, 213`). Unreachable from a
//    screen that only appends to a non-empty prefix, and `help` typed as a
//    channel name composes as `-help,err+help,…` and never as the bare word.
//  - A later `-all` erases every term before it, because parsing is left to right
//    and `default_flags` is rewritten in place. [isLoggableName] refuses `-`, so
//    no row can produce one.
//
// **Per-program scoping is the one thing no row can express.** Wine accepts
// `process:class+channel`, matching the token before the `:` against `argv[1]`'s
// basename (`debug.c:140-145`), and `metro.exe:+relay` is the difference between
// a usable log and a full disk. If it is wanted, it belongs as a fourth column on
// the row — a program name written as `$program:` in front of each term — and not
// as a free-text `WINEDEBUG` box, which is what used to carry it.

// — the fixed values, named once so the rows cannot drift from the environment --

/**
 * What `sessionEnvironment` already sets for each thing that has a row.
 *
 * Declared here and consumed there, so the inventory the screen draws and the
 * environment the session gets cannot disagree. `SessionEnvironmentTest` asserts
 * that every fixed row's displayed value is what the session actually carries.
 */
const val FIXED_DXVK_LOG_LEVEL: String = "info"
const val FIXED_DXVK_LOG_PATH: String = "none"
const val FIXED_VKD3D_DEBUG: String = "warn"
const val FIXED_VKD3D_SHADER_DEBUG: String = "warn"
const val FIXED_FEX_SILENTLOG: String = "0"
const val FIXED_FEX_OUTPUTLOG: String = "stderr"

/** Unset: Mesa then picks its Android default, which is logcat. */
const val FIXED_MESA_LOG: String = ""

/**
 * The five Wine rows above, as the exact terms [WINEDEBUG_CHANNELS] is made of.
 *
 * Display cannot be derived from the constant by a generic composer — the fixed
 * prefix writes `warn+module` where a row would write `-module,err+module,
 * warn+module`, which is the same three flags in fewer terms — so the terms are
 * declared beside the rows and `ContainerDiagnosticsTest` asserts they
 * concatenate back to the constant character for character. That is what stops
 * the screen claiming something the session does not send.
 */
val BASELINE_WINE_TERMS: List<Pair<String, List<String>>> = listOf(
    "all" to listOf("-all", "err+all"),
    "module" to listOf("warn+module"),
    "winediag" to listOf("+winediag"),
    "loaddll" to listOf("+loaddll"),
    "debugstr" to listOf("+debugstr"),
)

// — the display list ----------------------------------------------------------

/** One row, resolved: what to draw, and whether each column may be touched. */
data class DiagnosticRow(
    /** The row's index in [ContainerDiagnostics.rows], or -1 for a fixed row. */
    val index: Int,
    val name: String,
    val secondary: String?,
    val caution: String?,
    /** The level ladder's wire values, in this thing's own order. */
    val levels: List<String>,
    val levelLabels: Map<String, String>,
    val level: String,
    val levelIsMachine: Boolean,
    /** True when this row is loud enough to remove itself after one launch. */
    val oneSession: Boolean,
    /**
     * Whether the *name* may be typed or picked.
     *
     * **Separate from [levelEditable], and the defect that separated them:** they
     * were one flag, and a freshly added row has no name yet — so the row came
     * back with its name column disabled and the user could never name it. Add
     * produced a dead row, permanently greyed. The two columns answer different
     * questions — "is this row mine to define" and "is there a level worth
     * choosing right now" — and a row waiting to be named is exactly where they
     * differ.
     */
    val nameEditable: Boolean,
    /** False for a fixed row, for an unnamed one, and for one whose gate is unmet. */
    val levelEditable: Boolean,
    /** False only for a fixed row. A gated row keeps its cross. */
    val removable: Boolean,
    /** True when the name is typed and Wine would not register it. */
    val nameIsInvalid: Boolean,
    /** Which table draws this. See [Loggable.isTurnipFlag]. */
    val isTurnipFlag: Boolean = false,
)

/**
 * The screen's list: what is already being sent, then what the user added.
 *
 * The fixed rows are not in [ContainerDiagnostics.rows] — they are the
 * declaration — so a fresh container's record is still empty and the golden
 * environment is still byte-identical. They are prepended here because the list
 * is an inventory and the inventory starts with what is already true.
 *
 * A user row for something the fixed prefix also names is *not* merged with it:
 * both are drawn, in order, because both are sent and the later one wins. Hiding
 * the fixed row would make the screen lie about the string.
 */
fun diagnosticRows(diagnostics: ContainerDiagnostics): List<DiagnosticRow> {
    val fixed = LOGGABLES.filter { it.isFixed }.map { loggable ->
        DiagnosticRow(
            index = -1,
            name = loggable.name,
            secondary = loggable.secondary,
            caution = loggable.caution,
            levels = listOf(loggable.fixedLevel.orEmpty()),
            levelLabels = loggable.labels,
            level = loggable.fixedLevel.orEmpty(),
            levelIsMachine = loggable.levelIsMachine,
            // These are sent every session, by definition.
            oneSession = false,
            nameEditable = false,
            levelEditable = false,
            removable = false,
            nameIsInvalid = false,
            isTurnipFlag = loggable.isTurnipFlag,
        )
    }

    val added = diagnostics.rows.mapIndexed { index, row ->
        val loggable = loggableFor(row.name, row.turnip)
        DiagnosticRow(
            index = index,
            name = row.name,
            secondary = if (row.name.isEmpty()) null else loggable.secondary,
            caution = if (row.name.isEmpty()) null else loggable.caution,
            levels = loggable.levels,
            levelLabels = loggable.labels,
            level = row.level,
            levelIsMachine = loggable.levelIsMachine,
            oneSession = row.isOneSession,
            // Always: this row is the user's, so its name is theirs to set even
            // when — especially when — it is still empty.
            nameEditable = true,
            // A gated row keeps its name and its cross and loses its level,
            // which is what tells it apart from a fixed one; that, and the
            // caution saying what is missing.
            levelEditable = row.name.isNotEmpty() && row.isAllowed(diagnostics),
            removable = true,
            // Wine's channel grammar does not apply to a driver flag, and
            // checking one against the other would flag every valid TU_DEBUG
            // member that happens to contain an underscore.
            nameIsInvalid = row.name.isNotEmpty() &&
                !row.turnip && !isLoggableName(row.name),
            // The stored origin wins for an unnamed row, which has no name to
            // ask; once named the two agree. See `DiagnosticSetting.turnip`.
            isTurnipFlag = row.turnip || loggable.isTurnipFlag,
        )
    }

    return fixed + added
}

/**
 * No fixed row displays `Off`.
 *
 * The property that keeps "greyed out" from reading as "switched off". A fixed
 * row is disabled because the user may not change it, and it shows its real
 * value; an `Off` row is a live control somebody set to silence, and composes as
 * `-chan`. If a genuinely-absent fixed value ever needs a row, it needs different
 * copy — `not set`, as `MESA_LOG` uses — and not the `Off` stop.
 */
fun fixedRowsNeverReadOff(): Boolean = LOGGABLES.filter { it.isFixed }.none {
    it.label(it.fixedLevel.orEmpty()).equals(WineChannelLevel.OFF.label, ignoreCase = true)
}

// — composition ---------------------------------------------------------------

/**
 * The five stops a Wine channel can run at, and the only ladder here that is a
 * simplification rather than a translation.
 *
 * Wine's model is four independent class bits — `fixme`, `err`, `warn`, `trace`
 * (`native/wine/dlls/ntdll/unix/debug.c:65`) — so a ladder cannot express `err`
 * without `warn`. It is used anyway because the combinations it cannot reach are
 * not ones anybody has asked for; if a real one turns up, the honest answer is a
 * class checkbox set, not a free-text field. `trace` is folded into [EVERYTHING]
 * rather than given a stop of its own: a channel's trace tier is where every
 * firehose lives, and offering it as one more notch is how a ladder makes a
 * 500 MB log look like an increment.
 */
@Serializable
enum class WineChannelLevel(val label: String) {
    /** `-chan` — silenced, below even the `err+all` floor every channel inherits. */
    OFF("Off"),
    ERRORS("Errors"),
    WARNINGS("+ Warnings"),
    STUBS("+ Stubs"),
    EVERYTHING("Everything"),
    ;

    internal val classes: List<String>
        get() = when (this) {
            OFF, EVERYTHING -> emptyList()
            ERRORS -> listOf("err")
            WARNINGS -> listOf("err", "warn")
            STUBS -> listOf("err", "warn", "fixme")
        }
}

/** The terms that force `channel` to exactly [level]. See [Emit.WineChannel]. */
internal fun wineChannelTerms(channel: String, level: WineChannelLevel): List<String> = when (level) {
    WineChannelLevel.OFF -> listOf("-$channel")
    WineChannelLevel.EVERYTHING -> listOf("+$channel")
    else -> listOf("-$channel") + level.classes.map { "$it+$channel" }
}

/**
 * `WINEDEBUG`: the fixed prefix, then whatever the rows add.
 *
 * **Never a value handed over whole.** This starts from [WINEDEBUG_CHANNELS] and
 * appends, the shape `dllOverrides` already uses and for the same stated reason:
 * Wine parses left to right and a later term wins, so a user can add without
 * being able to delete the defaults by accident. An empty record returns the
 * constant byte for byte.
 */
fun composeWineDebug(diagnostics: ContainerDiagnostics): String =
    (WINEDEBUG_CHANNELS.split(",") + emitted(diagnostics).wineTerms).joinToString(",")

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
 *   the manifest can contribute flags too and this stage adds to them.
 */
fun diagnosticEnvironment(
    diagnostics: ContainerDiagnostics,
    turnipBaseFlags: List<String> = listOf(TU_DEBUG_STARTUP),
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val emitted = emitted(diagnostics)

    if (emitted.wineTerms.isNotEmpty()) {
        out["WINEDEBUG"] = (WINEDEBUG_CHANNELS.split(",") + emitted.wineTerms).joinToString(",")
    }
    out.putAll(emitted.variables)
    for ((variable, members) in emitted.lists) {
        if (members.isEmpty()) continue
        // The added flags first and the base last, so `startup` stays where it is:
        // it is the ground truth for whether Turnip loaded and nothing may
        // displace it.
        out[variable] = (members + turnipBaseFlags).distinct().joinToString(",")
    }
    return out
}

/** Every row's contribution, folded. There is no branch here on what a row is. */
private fun emitted(diagnostics: ContainerDiagnostics): EmittedEnvironment {
    val out = EmittedEnvironment()
    diagnostics.inForce().rows.forEach { row ->
        loggableFor(row.name).emit.apply(row.level, out)
    }
    return out
}

/**
 * The copy the confirmation shows before a row that costs something is added.
 *
 * Composed from the entry's own caution rather than written per control, so a new
 * expensive thing gets a correct warning by being added to [LOGGABLES]. It says
 * the concrete things — the log fills, the session slows — and, when the row is
 * one-session, names the mechanism, because naming it is what stops the dialog
 * reading as a scary-sounding thing people learn to dismiss.
 */
fun costWarning(caution: String?, oneSession: Boolean): String = buildString {
    if (!caution.isNullOrBlank()) append(caution).append(' ')
    append("The log will hit its cap sooner and the session will run slower.")
    if (oneSession) {
        append(" It removes itself after the next launch.")
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
 * about 20. **So the byte caps and the rate limit move together or not at all.**
 *
 * **The defaults are the maximum of each ladder**, which is the requirement this
 * work was given, and it is a real cost: [worstCaseBytesPerContainer] is 480 MB
 * against the 80 MB these numbers used to imply. That is too much to spend
 * silently, so the surface shows the container's actual usage beside these
 * controls and carries a *Delete all logs* action.
 *
 * Sessions kept per container is deliberately **not** here. It is a history
 * budget rather than a fidelity budget — a diagnostician compares a bad run
 * against a good one — and ten is what `SessionLogStore` says that takes.
 */
@Serializable
data class SessionLogLimits(
    val headBytes: Long = HEAD_LADDER.last(),
    /**
     * The retained tail *in total*, named for what it holds rather than for the
     * field it sets: the writer keeps two segments and rotates between them, so
     * the retained tail sawtooths between half of this and all of it.
     */
    val tailBytes: Long = TAIL_LADDER.last(),
    val rateLimitLines: Int = RATE_LADDER.last(),
) {
    val tailSegmentBytes: Long get() = tailBytes / 2
    val worstCaseBytesPerSession: Long get() = headBytes + tailBytes
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
