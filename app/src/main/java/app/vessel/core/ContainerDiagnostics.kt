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
     * Every row loud enough to be one-session spends its one launch here.
     * Writing this back at launch rather than at teardown is what makes "one
     * session" survive the app being killed mid-run: the row is already spent in
     * the stored document by the time the guest process exists, so the run after
     * a crash gets the ordinary environment.
     *
     * **The row is disarmed, not deleted.** It used to be dropped outright, and
     * that lost the one thing the user actually chose. What it looked like from
     * the outside: arm `seh`, run once, come back to a list with no `seh` in it
     * and nothing anywhere saying why — indistinguishable from the setting never
     * having applied, which is exactly the doubt a diagnostic surface must not
     * create. It cost a real debugging session here, spent wondering whether the
     * channel was broken when it had worked perfectly and tidied up after itself.
     *
     * Dropping to [Loggable.baseline] says the same thing honestly: the row is
     * still there, holding the name that was typed, sitting at its quiet level.
     * Re-arming is the level dropdown rather than re-adding the row and finding
     * the name again — which matters, because one-session rows are the ones
     * somebody is most likely to want twice in a row.
     */
    fun consumed(): ContainerDiagnostics =
        copy(
            rows = rows.map { row ->
                if (row.isOneSession) row.copy(level = loggableFor(row.name, row.turnip).baseline) else row
            },
        )

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
        val existing = rows[index]
        // **The row's own family, and the row's own family kept.** Both halves of
        // this line were wrong and they failed together.
        //
        // It resolved through `loggableFor(name, turnip)`, which knows only
        // `wine` and `turnip`, so a name typed into a row the user had already
        // typed as `vkd3dconfig` or `mesa` was measured against the Wine ladder
        // and got a Wine channel's `addAt` as its level. And it then rebuilt the
        // row with `DiagnosticSetting(name, level, turnip)`, whose `type`
        // defaults to empty — so **typing a name erased the type the user had
        // just chosen**.
        //
        // The two together made the free-text escape hatch inert for every
        // family except the two the old boolean could name. It is invisible from
        // the screen, because the row keeps the name and the level and only the
        // invisible field is lost, and it is invisible from a session, because a
        // driver ignores a flag it does not recognise. `TU_DEBUG=flushall` was
        // set through this path, reached the guest as `TU_DEBUG=0x1`, and cost a
        // device session that read a bisect which had never run.
        val loggable = loggableIn(name, existing.resolvedType)
        return copy(
            rows = rows.mapIndexed { i, row ->
                if (i != index) {
                    row
                } else {
                    // The origin is carried, not recomputed: renaming within a
                    // table must not move the row to another one.
                    DiagnosticSetting(name, loggable.addAt, turnip = row.turnip, type = row.type)
                }
            },
        )
    }

    fun withRowLevel(index: Int, level: String): ContainerDiagnostics = mapRow(index) {
        it.copy(level = level)
    }

    fun withLimits(limits: SessionLogLimits): ContainerDiagnostics = copy(limits = limits)

    // — the environment table, addressed by index for the same reason ----------

    /**
     * The record with the legacy [env] list folded into [rows] as `env`-typed rows.
     *
     * **One list, because they were always one thing.** `EnvSetting(name, value)`
     * is `DiagnosticSetting(name, level, type = "env")` with the fields renamed —
     * the two existed because the row model could only express two families, not
     * because a typed variable is a different kind of object from a Wine channel.
     * The screen showed that as three tables and the question "which of these do
     * I use?" had no good answer.
     *
     * Called on read rather than migrating the stored document, so a container
     * written by an older build keeps working and a downgrade does not lose the
     * rows. Everything written from here on goes to [rows]; [env] only ever
     * shrinks.
     */
    fun normalised(): ContainerDiagnostics =
        if (env.isEmpty()) {
            this
        } else {
            copy(
                rows = rows + env.map { DiagnosticSetting(name = it.name, level = it.value, type = ENV_FAMILY) },
                env = emptyList(),
            )
        }

    /** Add a row in [type], which is what the type column's Add uses. */
    fun withRowAdded(type: String): ContainerDiagnostics =
        normalised().let { it.copy(rows = it.rows + DiagnosticSetting(type = type)) }

    /**
     * Re-type a row, and reset its level for the same reason [withRowNamed] does.
     *
     * A level is a word in one subsystem's vocabulary: `on` means something to a
     * `TU_DEBUG` member and nothing to `VKD3D_DEBUG`, and carrying it across a
     * family change would leave a row displaying a stop its new family does not
     * have. The name goes too — a channel name is not a variable name.
     */
    fun withRowTyped(index: Int, type: String): ContainerDiagnostics =
        mapRow(index) { DiagnosticSetting(type = type.trim()) }

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
        normalised().rows.asSequence()
            .filter { it.resolvedType == ENV_FAMILY }
            .map { it.name.trim() to it.level }
            .filter { (name, _) -> name.isNotEmpty() && name !in RESERVED_SESSION_ENV }
            .toMap()

    /**
     * `VESSEL_TRACE`, parsed — the one row in the env table this layer reads
     * rather than merely forwards.
     *
     * **Why it lives in the env table and not as a field of its own.** The table
     * already exists for exactly this shape of thing: a name and a value that
     * Vessel did not have to anticipate. Giving the trace spec a dedicated field
     * would mean a schema change, a migration for every stored document, and a
     * second surface to build — to gain nothing, because the value is one string
     * and the table renders strings. [KNOWN_ENV] carries its description, so the
     * row is a picker rather than a thing you have to already know about.
     *
     * The last row wins, matching every other list here: the table is ordered and
     * the user's most recent edit is the one they mean.
     *
     * Unlike every other entry in the table this one is **both** expanded here
     * *and* passed through to the guest — see [TRACE_SPEC_ENV] for the two things
     * it means that no environment variable can express.
     */
    fun traceSpec(): TraceSpec =
        normalised().rows.lastOrNull { it.name.trim() == TRACE_SPEC_ENV }
            ?.let { parseTraceSpec(it.level) }
            ?: TraceSpec.EMPTY

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

/**
 * An environment variable Vessel has something to say about.
 *
 * **The declaration the env table is a picker for**, and the reason those six
 * graphics experiments stopped being container settings. They were manifest
 * params, which meant an entry and a build to try one — and the manifest's own
 * law is that a setting must be explainable in one plain sentence to someone who
 * does not know what a translator is. None of them can be. They belong on the
 * Diagnostics screen, whose audience is someone whose session is already
 * strange, and whose table already takes a name and a value.
 *
 * What is kept from the manifest version is the *sentence*: knowing that a
 * variable exists is nearly useless, and [secondary] is the part that was worth
 * carrying over. [values] turns the value field into a picker where the answers
 * are a closed set, and stays empty where they are not.
 */
data class KnownEnv(
    val name: String,
    val secondary: String,
    val values: List<String> = emptyList(),
    val placeholder: String? = null,
    val caution: String? = null,
)

/**
 * What the env table offers before anything is typed.
 *
 * Ordered by how likely one is to answer a question, not alphabetically: the
 * tiling pair first, because the driver's own profile opts Direct3D out of tiled
 * rendering and that is the largest unexamined thing in the stack.
 */
val KNOWN_ENV: List<KnownEnv> = listOf(
    // First, and it is not a graphics experiment: it is the one row that can
    // answer a question without knowing which of five tools prints the answer.
    // Every other entry in this list is a variable somebody read about; this one
    // is the reason they should not have to. See [TraceSpec].
    KnownEnv(
        name = TRACE_SPEC_ENV,
        secondary = traceSpecHelp(),
        placeholder = "graphics:stubs,x86:errors",
        caution = "Check the volume before you launch: the loud stops fill this " +
            "container's whole log budget in under a minute, and the log says which " +
            "source filled it.",
    ),
    KnownEnv(
        name = "TU_AUTOTUNE_ALGO",
        secondary = "How the driver decides between drawing in on-chip tiles and drawing " +
            "straight to memory. It ships a rule that opts Direct3D games out of tiles " +
            "entirely, so this game is almost certainly not tiling. 'bandwidth' is the " +
            "driver's own rule; 'profiled' times both and picks.",
        values = listOf("bandwidth", "profiled", "prefer_gmem", "prefer_sysmem"),
    ),
    KnownEnv(
        name = "TU_AUTOTUNE_FLAGS",
        secondary = "'big_gmem' draws a pass in tiles whenever it has ten or more draws. " +
            "Metro averages about twenty a pass, so this flips most of them.",
        values = listOf("big_gmem"),
    ),
    KnownEnv(
        name = "tu_allow_concurrent_binning",
        secondary = "Lets tile setup run alongside drawing. Off in the driver because it " +
            "costs more than it saves on desktop games, and it means nothing at all " +
            "unless the game is actually tiling.",
        values = listOf("true", "false"),
    ),
    KnownEnv(
        name = "disable_conservative_lrz",
        secondary = "Recovers the early depth rejection the driver gives up on after a " +
            "blended draw.",
        values = listOf("true", "false"),
        caution = "On a game that needs the caution this renders incorrectly rather than " +
            "crashing. Check the picture, not just the frame rate.",
    ),
    KnownEnv(
        name = "MESA_GPU_TRACES",
        secondary = "Writes one record per render pass — tiles or not, why depth rejection " +
            "was dropped, bytes moved — to this container's tmp folder. Costs frame rate " +
            "while it runs, so read the shape and not the fps.",
        values = listOf("print_csv", "print", "print_json"),
    ),
    // **`DXVK_CONFIG` was here and is now the `dxvkconfig` family.** It could
    // stay a free-text environment row only while Vessel sent nothing of its
    // own: a row here *replaces* the variable, and the session now sends
    // [FIXED_DXVK_MAX_SHARED_MEMORY] in it. Anyone who typed a d3d11 option
    // would have quietly handed the guest back a memory report of twice the
    // heap — the exact failure `VKD3D_CONFIG` was declared to stop, one device
    // run after it was not.
    //
    // The three options that used to be named here are still worth trying and
    // are typed as members of that family instead:
    // `d3d11.cachedDynamicResources = a` (this phone shares memory between CPU
    // and GPU, so the memory type DXVK avoids costs nothing here),
    // `d3d11.relaxedBarriers = True`, `d3d11.relaxedGraphicsBarriers = True`.
    // None is proven.
)

/** The declaration for [name], or null when Vessel has nothing to say about it. */
fun knownEnvFor(name: String): KnownEnv? = KNOWN_ENV.firstOrNull { it.name == name.trim() }

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
     * **Superseded by [type], and kept only so a document written before it
     * still parses.** This was the whole discrimination: one bit, meaning
     * "graphics table" or "logging table", because those were the only two
     * lists. [resolvedType] reads it when [type] is empty and nothing writes it
     * any more.
     *
     * Defaulted, so every container document written before this parses.
     */
    val turnip: Boolean = false,
    /**
     * The [DiagnosticFamily.wire] this row belongs to — the *type* column.
     *
     * **Free text on purpose.** A name that matches no declared family is not an
     * error: it composes as a plain environment variable, which is the same
     * escape hatch the free-text table always was, and it is what stops the
     * curated list from being a ceiling. Everything the list *does* contain is
     * offered, so typing is the exception rather than the interface.
     *
     * **Stored rather than derived, and only because an unnamed row has nothing
     * to derive from.** Once a row has a name, [Loggable.family] is authoritative
     * — it is read off the `Emit`, so a row cannot claim one family and be sent
     * through another's variable. This field answers for the moment between
     * "add row" and "type a name", which is exactly the job [turnip] had.
     *
     * Defaulted, so every container document written before this parses.
     */
    val type: String = "",
) {
    /**
     * The family this row belongs to, preferring what the *name* proves.
     *
     * Order matters and it is the safety property: a declared name decides, so a
     * hand-edited document that says `type: "wine"` next to `name: "breadcrumbs"`
     * composes a `VKD3D_CONFIG` member and not a Wine channel called
     * `breadcrumbs` that Wine would ignore in silence. [type] is consulted only
     * when the name proves nothing, and [turnip] only when neither does.
     */
    val resolvedType: String
        get() {
            val declared = LOGGABLES.firstOrNull { it.name == name }?.family
            if (!declared.isNullOrEmpty()) return declared
            if (type.isNotBlank()) return type.trim()
            return if (turnip) "turnip" else "wine"
        }
    /**
     * Whether this row is loud enough to be spent after one launch.
     *
     * A property of the level and not of the thing: `DXVK_LOG_LEVEL` at `warn` is
     * ordinary and at `trace` is a firehose, and the row has to tell them apart.
     */
    val isOneSession: Boolean get() = loggableFor(this).isOneSession(level)

    /**
     * False while the row's gate is unmet, which is when it must contribute
     * nothing.
     *
     * **Resolved through [resolvedType], and it used to be resolved through
     * [turnip] alone.** That older reading knew only two families, so a row typed
     * as anything else — `turnip` for an undeclared `TU_DEBUG` flag,
     * `vkd3dconfig` for a config word — was measured against the *Wine* ladder.
     * `on` is not a Wine level, so [isRealValue] returned false and `inForce`
     * dropped the row before it could compose anything. Silent, and
     * indistinguishable from a flag the driver ignored.
     */
    fun isAllowed(diagnostics: ContainerDiagnostics): Boolean {
        val loggable = loggableFor(this)
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
 * Which subsystem a row belongs to — the *type* column.
 *
 * **This exists because the screen had three tables and the model had two lists,
 * and neither number was a fact about the stack.** A row was a Wine channel or a
 * Turnip flag, discriminated by a boolean called `turnip`; anything else had to
 * go through the free-text environment table, which drops every
 * [RESERVED_SESSION_ENV] name — so a *reserved* variable was reachable only by
 * being declared, and a declared thing could only be one of two families. There
 * was no way to say "vkd3d, breadcrumbs, on" at all, and finding that out cost a
 * device run: `VKD3D_CONFIG=breadcrumbs` was set in the env table, silently
 * dropped, and the guest's own environ still read `nodxr`.
 *
 * A family is **data**, so adding a subsystem is an entry in [FAMILIES] and no
 * change to the composer, the screen, or the document schema. That is the whole
 * point: the previous shape needed a boolean, a partition in the UI, a branch in
 * `loggableFor` and a hardcoded baseline in `diagnosticEnvironment` for every new
 * kind of knob.
 *
 * @param wire what is stored in the container document and what a user types.
 *   It must not change once a document has been written with it.
 * @param shape how a member of this family reaches the environment. This is the
 *   only thing the composer needs, and it is why [Emit] does not have to be
 *   declared per entry — see [emitFor].
 * @param variable the environment variable this family writes, for [Shape.LIST]
 *   where every member shares one. Empty for [Shape.CHANNEL] (they all share
 *   `WINEDEBUG`) and for [Shape.SCALAR] (the row's own name *is* the variable).
 * @param baseMembers what the session already puts in [variable], which a
 *   composed list must not drop. `TU_DEBUG`'s `startup` is the ground truth for
 *   whether Turnip loaded; `VKD3D_CONFIG`'s `nodxr` is a correctness setting.
 *   Getting this wrong is silent — the variable is still set, just missing the
 *   thing nobody was looking at.
 */
data class DiagnosticFamily(
    val wire: String,
    val label: String,
    val shape: Shape,
    val secondary: String,
    val variable: String = "",
    val baseMembers: List<String> = emptyList(),
    /**
     * What joins the members of a [Shape.LIST] variable.
     *
     * Declared rather than assumed, because the assumption was wrong the moment
     * a third list appeared. `TU_DEBUG` and `VKD3D_CONFIG` are comma-joined and
     * the composer hardcoded a comma; `DXVK_CONFIG` holds whole `key = value`
     * lines and splits on `;`
     * (native/dxvk/src/util/config/config.cpp:1690), so a comma there produces
     * one unparseable line rather than two settings — and DXVK's parser skips
     * what it cannot read without saying so, which is a silent loss of both.
     */
    val separator: String = ",",
) {
    enum class Shape {
        /** A channel inside `WINEDEBUG`. The row's name is the channel. */
        CHANNEL,

        /** A variable of its own. The row's name *is* the variable. */
        SCALAR,

        /** One member of a comma-joined variable named by [variable]. */
        LIST,

        /**
         * Any variable at all: the row's name is the variable and its level is
         * the literal value.
         *
         * **The escape hatch, and it is deliberately the weakest shape.** It is
         * subject to `RESERVED_SESSION_ENV` — the reserved names are the paths
         * this app writes and reads and the plumbing a session needs to start,
         * and a container document that could reach them could point Wine
         * outside its own directory. A reserved variable is reachable *only* by
         * being declared, which is what the other shapes are for.
         */
        RAW,
    }
}

/**
 * Every family, and adding one is this list plus nothing.
 *
 * Ordered by how often the question is asked, the same rule `TRACE_TOPICS` uses.
 */
val FAMILIES: List<DiagnosticFamily> = listOf(
    DiagnosticFamily(
        wire = "wine",
        label = "Wine",
        shape = DiagnosticFamily.Shape.CHANNEL,
        secondary = "A debug channel inside WINEDEBUG.",
    ),
    DiagnosticFamily(
        wire = "vkd3d",
        label = "vkd3d",
        shape = DiagnosticFamily.Shape.SCALAR,
        secondary = "The Direct3D 12 translator's own log levels.",
    ),
    // Separate from `vkd3d` because it is a different *shape*, not a different
    // component: VKD3D_CONFIG is a comma-joined set, so a member must be added
    // to what the session already sends rather than replacing it. Sending
    // `breadcrumbs` alone would drop `nodxr` and change what the title runs
    // with while trying to observe it.
    DiagnosticFamily(
        wire = "vkd3dconfig",
        label = "vkd3d config",
        shape = DiagnosticFamily.Shape.LIST,
        secondary = "Behaviour flags, added to the ones Vessel already sends.",
        variable = "VKD3D_CONFIG",
        baseMembers = listOf(FIXED_VKD3D_CONFIG),
    ),
    DiagnosticFamily(
        wire = "dxvk",
        label = "DXVK",
        shape = DiagnosticFamily.Shape.SCALAR,
        secondary = "The Direct3D 9/10/11 translator's log level.",
    ),
    // Separate from `dxvk` for the reason `vkd3dconfig` is separate from `vkd3d`
    // — a different shape, not a different component — and separate from *both*
    // in one further way: its members are whole `key = value` lines rather than
    // single words, and DXVK splits them on `;` rather than `,`.
    //
    // It became a family the day the session started sending a value of its own.
    // Free text in the environment table could not carry
    // [FIXED_DXVK_MAX_SHARED_MEMORY]: a row there *replaces* the variable, so
    // anyone who typed a d3d11 option would have silently handed the guest back
    // the doubled memory report — which is exactly the failure `VKD3D_CONFIG`
    // was declared to stop, one device run later.
    DiagnosticFamily(
        wire = "dxvkconfig",
        label = "DXVK config",
        shape = DiagnosticFamily.Shape.LIST,
        secondary = "Config lines, added to the ones Vessel already sends.",
        variable = "DXVK_CONFIG",
        baseMembers = listOf(FIXED_DXVK_MAX_SHARED_MEMORY),
        separator = ";",
    ),
    DiagnosticFamily(
        wire = "turnip",
        label = "Turnip",
        shape = DiagnosticFamily.Shape.LIST,
        secondary = "Driver flags. Half of these change the frame, not the log.",
        variable = "TU_DEBUG",
        baseMembers = listOf(TU_DEBUG_STARTUP),
    ),
    // The secondary said "Which logger the driver uses, and its severity floor"
    // and had to widen: `MESA_VK_WSI_DEBUG` files itself here by prefix
    // ([familyOfVariable]) and is not a logger — it picks the present path. A
    // family label that describes two of its three members is the kind of quiet
    // inaccuracy this screen exists to remove.
    DiagnosticFamily(
        wire = "mesa",
        label = "Mesa",
        shape = DiagnosticFamily.Shape.SCALAR,
        secondary = "The driver's logger and its severity floor — and how a frame reaches " +
            "the window.",
    ),
    DiagnosticFamily(
        wire = "fex",
        label = "FEX",
        shape = DiagnosticFamily.Shape.SCALAR,
        secondary = "The x86 translator. One switch; it has no level.",
    ),
    // A flag list, not a level — `zink_debug_options` in `zink_screen.c` is a
    // `debug_named_value` table read with the comma-joined flag parser, the same
    // shape as `TU_DEBUG`. Declared SCALAR first and corrected against the
    // source: a scalar would have written `ZINK_DEBUG=validation` over whatever
    // else was set rather than adding to it.
    //
    // No base members: nothing in the session sets `ZINK_DEBUG`, and inventing a
    // baseline for a variable Vessel does not send would be a claim about the
    // environment that is not true.
    DiagnosticFamily(
        wire = "zink",
        label = "Zink",
        shape = DiagnosticFamily.Shape.LIST,
        secondary = "OpenGL over Vulkan. Only reached by a title that asks for GL.",
        variable = "ZINK_DEBUG",
    ),
    // Vessel's own variables, and `VESSEL_TRACE` is why this family is worth
    // declaring: it is the one entry in the free-text table this layer *reads*
    // rather than forwards, which made it a variable pretending to be a setting.
    // Declared, it is a row like any other.
    DiagnosticFamily(
        wire = "vessel",
        label = "Vessel",
        shape = DiagnosticFamily.Shape.SCALAR,
        secondary = "Vessel's own switches, including the one trace vocabulary.",
    ),
    // **Last, and last on purpose.** Every family above names a subsystem whose
    // knobs this build understands; this one is "a variable nobody anticipated",
    // which is the case the six-graphics-experiments-in-an-afternoon note
    // upstream of here exists for. It is also the only shape the reserved set
    // filters, so a row here can never reach a path the app writes.
    DiagnosticFamily(
        wire = "env",
        label = "Environment variable",
        shape = DiagnosticFamily.Shape.RAW,
        secondary = "Any variable, typed by name and value. Reserved names are refused.",
    ),
)

/**
 * The family a plain environment variable belongs to.
 *
 * Named because three things have to agree about it — the row that stores it,
 * `environmentOverrides` which composes it, and the screen's type column — and a
 * literal in three places is how they drift.
 */
const val ENV_FAMILY: String = "env"

/** The family called [wire], or null — which is what makes the column free text. */
fun familyFor(wire: String): DiagnosticFamily? =
    FAMILIES.firstOrNull { it.wire.equals(wire.trim(), ignoreCase = true) }

/**
 * The family a *scalar* variable belongs to, read off its own name.
 *
 * By prefix rather than by a table of variable names, so that a variable nobody
 * has declared yet — `VKD3D_SHADER_DEBUG`'s next sibling, another `FEX_` switch —
 * files itself. The alternative is a second list to forget to update, which is
 * the failure this whole layer replaces.
 *
 * Longest prefix first, because `VKD3D_` must not be read as a shorter match.
 */
private fun familyOfVariable(variable: String): String {
    val name = variable.uppercase()
    val byPrefix = listOf(
        "VKD3D_" to "vkd3d",
        "DXVK_" to "dxvk",
        "MESA_" to "mesa",
        "FEX_" to "fex",
        "TU_" to "turnip",
        "ZINK_" to "zink",
        "VESSEL_" to "vessel",
    )
    return byPrefix.firstOrNull { (prefix, _) -> name.startsWith(prefix) }?.second ?: ""
}

/**
 * The variable each list family already holds, for the composer.
 *
 * Derived from [FAMILIES] rather than passed in, which is the defect this
 * replaces: `diagnosticEnvironment` took one `turnipBaseFlags` list and appended
 * it to **every** list variable it had composed. With `TU_DEBUG` the only list
 * that was invisible; the moment `VKD3D_CONFIG` became one it would have written
 * `breadcrumbs,startup` — Turnip's ground-truth flag into vkd3d's config, where
 * it means nothing, and `nodxr` dropped.
 */
internal fun listBaseMembers(): Map<String, List<String>> =
    FAMILIES.filter { it.shape == DiagnosticFamily.Shape.LIST && it.variable.isNotEmpty() }
        .associate { it.variable to it.baseMembers }

/** What joins each list variable's members, derived the same way and for the same reason. */
internal fun listSeparators(): Map<String, String> =
    FAMILIES.filter { it.shape == DiagnosticFamily.Shape.LIST && it.variable.isNotEmpty() }
        .associate { it.variable to it.separator }

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
 * @param volumes what a stop is known to *cost*, keyed by level, in one clause
 *   that also says how it is known. **The ladder's third defect and the one it
 *   hid best.** A ladder tells you the order of its stops and nothing about the
 *   distance between them, so `seh` at *Everything* read as one notch past
 *   *+ Stubs* and was 191,000 lines; `FEX_SILENTLOG=0` read as a switch and was
 *   49 MB. Two twenty-minute device runs bought nothing because of it. Only
 *   populated where a real number exists — an invented one would be worse than
 *   the silence it replaces, so a stop with no entry shows nothing.
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
    val volumes: Map<String, String> = emptyMap(),
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
     * Which [DiagnosticFamily] this belongs to — the *type* column.
     *
     * **Derived from [emit], never declared**, for the reason [isTurnipFlag]
     * already gives and which generalises exactly: a thing cannot end up filed
     * under one family and sent through another's variable, because the family
     * *is* a reading of how it is sent. A declared `type` would be a second
     * place to be wrong.
     *
     * The match is on the variable a member writes, then on the prefix of a
     * scalar's own name. A scalar whose prefix names no family is `wine` only if
     * it really is a channel; anything else falls through to an empty string,
     * which the screen shows as an untyped row rather than filing it wrongly.
     */
    val family: String
        get() = when (val e = emit) {
            is Emit.WineChannel -> "wine"
            is Emit.ListMember ->
                FAMILIES.firstOrNull { it.variable == e.variable }?.wire ?: ""
            is Emit.Variable -> familyOfVariable(e.variable)
            Emit.Fixed -> familyOfVariable(name)
        }

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

    /** What [level] is known to cost, or null when nobody has measured it. */
    fun volume(level: String): String? = volumes[level]

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
    volumes: Map<WineChannelLevel, String> = emptyMap(),
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
    volumes = volumes.mapKeys { (level, _) -> level.name },
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
        // **Stays at EVERYTHING, and the measurement that looked like a case for
        // lowering it is the reason not to.** Two sessions produced 1 line and 0
        // lines, both at ERR, which `err+all` already covers -- so the term
        // looks free to delete. It is not: `docs/LOGGING.md` counts 58 sites,
        // 55 of them `ERR_(winediag)`, which leaves three that only a higher
        // tier reaches. Removing it would trade three init-time diagnostics --
        // no Vulkan library, broken .NET, missing codecs -- for nothing
        // measurable, because the channel already costs about one line a
        // session.
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
        // **WARNINGS, because that is where every line of it actually is.**
        // Measured: 63 lines in Requiem and 1,977 in Metro, and 100% of them at
        // the WARN tier in both -- nothing above. EVERYTHING was therefore
        // buying the TRACE tier of a channel that is a program's own
        // `OutputDebugString`, which on a chatty engine is unbounded and on
        // these two is empty.
        //
        // It stays on rather than dropping to ERRORS because it is the layer we
        // have least other visibility into: this is what carried Requiem's
        // `[streamline][error]` lines and its XeSS output, and a title's own
        // account of why it failed is often the only one.
        fixed = WineChannelLevel.WARNINGS,
    ),
    // **`DXVK_LOG_PATH=none` is still sent and is deliberately not a row.**
    // `SessionEnvironment.kt:1158` sets it unconditionally from
    // [FIXED_DXVK_LOG_PATH]; this list only decides what the screen shows, so
    // removing the entry hides the row and changes no environment.
    //
    // Off the screen because it is not a thing anyone logs. It keeps the D3D
    // translator's output in this log rather than writing `<exe>_dxgi.log` and
    // `<exe>_d3d11.log` into the game's own folder, which is a decision about
    // where a file goes, not a level anyone would raise or lower. A read-only
    // row that can only ever read `none` spends a line of a small screen saying
    // nothing the reader can act on, and every such line makes the rows that
    // *are* actionable harder to find.
    Loggable(
        name = "FEX_OUTPUTLOG",
        emit = Emit.Fixed,
        levels = listOf(FIXED_FEX_OUTPUTLOG),
        baseline = FIXED_FEX_OUTPUTLOG,
        fixedLevel = FIXED_FEX_OUTPUTLOG,
        // Source/Windows/Common/Logging.cpp:36-49 is the whole Windows logging
        // init and reads SILENTLOG and nothing else. Shown rather than hidden so
        // nobody re-adds it as a control.
        //
        // Re-checked 2026-08-14 against the patched tree and still true:
        // `OutputLog` appears there only inside a comment, and `patches/fex/0007`
        // changed where `Init()` reads `SilentLog` from without making
        // `OutputLog` mean anything.
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
        // Quietest first, as every other row here is ordered, so that moving
        // right always means more output. That puts the default at the left.
        levels = listOf("1", "0"),
        labels = mapOf("1" to "1  silent", "0" to "0  speaks"),
        baseline = FIXED_FEX_SILENTLOG,
        secondary = "Whether the x86 translator may report. Silent hides its own " +
            "configuration mistakes as well as its crashes.",
        caution = "Speaks is a firehose: it logs a pair of lines per unaligned atomic, " +
            "which measured 49 MB in six minutes and drowns everything else in this log.",
        addAt = "0",
        oneSessionFrom = "0",
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

    // **The one row on this surface that changes the frame rather than the log,
    // and the only one whose wrong stop can stop a session drawing at all.**
    //
    // `docs/BANDWIDTH.md` item 7: DRI3 zero-copy present is the single
    // *measured* frame-time win in this stack that is switched off — 0.602 ms
    // against 2.143 ms, mean over 300 frames at 1280x720
    // (`patches/mesa/README.md`). On the software path a present costs a GPU
    // `vkCmdCopyImageToBuffer` of the whole frame plus an `xcb_put_image` of
    // 3.6 MB through a socket; on DRI3 the swapchain image *is* the window's
    // buffer and neither happens.
    //
    // **Why it is a row and not just the constant it defaults to.** Flipping
    // `ZERO_COPY_PRESENT` took a Gradle build, and the failure it guards
    // against is immediate and total: a black window or no swapchain, not a
    // slow one. That is a bad pairing — the way back from a dead session should
    // not be a rebuild. Per container, so a container that breaks is the only
    // one that does, and the way back is this dropdown.
    //
    // **What has and has not been proven, because the caution has to be
    // honest.** Proven separately: `tools/gfx/wsiprobe.c` had Turnip importing
    // this server's dma-buf and binding a `TILING_LINEAR` image at rowPitch
    // 5120, and `tools/gfx/x11present.c` ran a real Vulkan swapchain against
    // this app's own X server on the DRI3 path and produced the table above.
    // Not proven: the same path under a *guest* — winex11 holding the X
    // connection, vkd3d or DXVK driving the swapchain, Turnip reached through
    // win32u's ICD under FEX. That join has never been run, which is why
    // [ZERO_COPY_PRESENT] is still false and this row exists to answer it one
    // container at a time.
    //
    // The 2026-08-10 session that died with this on is *not* evidence about the
    // server as it stands: its cause was found 104 minutes later and was the
    // missing XFIXES extension, which libxcb answers by tearing the connection
    // down client-side before a request is even sent. [ZERO_COPY_PRESENT] has
    // the whole chronology.
    //
    // `levelIsMachine` is off deliberately — the wire values are `sw` and the
    // empty string, and a mono `""` is not a control anybody can read, so the
    // stops are labelled in English instead.
    Loggable(
        name = "MESA_VK_WSI_DEBUG",
        emit = Emit.Variable("MESA_VK_WSI_DEBUG", FIXED_MESA_VK_WSI_DEBUG),
        // Safe stop first, matching every other ladder here: moving right always
        // means asking for more. Here "more" is speed rather than output, and
        // the risk moves with it.
        levels = listOf(WSI_SOFTWARE, WSI_DRI3),
        labels = mapOf(
            WSI_SOFTWARE to "Copy each frame  (sw)",
            WSI_DRI3 to "Zero-copy  (DRI3)",
        ),
        baseline = FIXED_MESA_VK_WSI_DEBUG,
        secondary = "How a finished frame reaches the window. Zero-copy hands the driver " +
            "the window's own buffer instead of copying the whole frame twice. It " +
            "measured 3.5x cheaper on this phone, and it has never been run under a game.",
        caution = "If it does not work the window is black or the session ends at once, " +
            "not slow — there is no in-between. Come back here and pick the copying " +
            "one; nothing else needs undoing.",
        // Added armed, like every other cautioned row: adding this one *is* the
        // deliberate act and the confirmation carries the caution. Turning it
        // off is the dropdown, which is the whole point of it being here.
        addAt = WSI_DRI3,
    ),

    // The one row here that produces a *file* rather than lines in this log, and
    // it is here because every line-producing instrument for the buzz has been
    // spent. Requiem buzzes, Metro does not, and `oss` at trace says the same
    // numbers for both: burst 868, capacity 1736, client buffer 2604; `held`
    // 1870 against 1909; `in_oss` 1106 against 1144; 41% against 40% partial
    // writes; zero xruns in either. Neither end starves, so the samples are
    // already wrong on arrival, and noise, gaps and clipping sound alike in a
    // log and want different fixes. A waveform separates them in one look.
    //
    // `1` and not a path, deliberately: `patches/wine/0047` resolves it against
    // `TMPDIR`, which is the container's own scratch. The directory has a UUID
    // in it, so a text field asking for an absolute path could only ever be
    // filled in wrongly, and a control that cannot be used correctly is worse
    // than no control. A typed path still works for a shell session, which is
    // why the driver accepts one; the screen just does not ask for it.
    Loggable(
        name = "VESSEL_AUDIO_DUMP",
        emit = Emit.Variable("VESSEL_AUDIO_DUMP", Emit.OFF),
        levels = ON_OFF,
        labels = ON_OFF_LABELS,
        baseline = Emit.OFF,
        secondary = "Writes every frame handed to the speaker to a file in the container, " +
            "so a buzz can be looked at instead of counted.",
        caution = "About 350 kB a second, appended for the whole session and never pruned. " +
            "One session, then turn it off.",
        addAt = Emit.ON,
        oneSessionFrom = Emit.ON,
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
    // The only channel that can see the unix half of a Vulkan call. On a Proton
    // 11 base that was where Resident Evil Requiem died:
    //
    //     msvcrt:_wassert (L"!status && \"vkCreateSwapchainKHR\"",
    //                      L"dlls/winevulkan/loader_thunks.c", 3681)
    //
    // That assert is on the NTSTATUS of the unix call rather than on the
    // VkResult — the unix side failed before Vulkan returned anything, so there
    // is no VkResult to read. **This base does not get that far**, so the assert
    // is recorded as the reason the channel is documented rather than as a thing
    // to expect here. `seh` cannot substitute: its ERR tier is already on through
    // `err+all`, and its trace tier measured 191,000 lines of
    // RtlInitializeExtendedContext2 in one session with no dispatch in it.
    wineChannel(
        "vulkan",
        "How Wine found and opened the graphics driver, and every call it forwards.",
        caution = "Above warnings it is one line per Vulkan call, so a drawn frame is thousands.",
        oneSessionFrom = WineChannelLevel.STUBS,
        // **The Stubs entry is a warning that this stop is a no-op, and it cost a
        // device run to learn.** This channel has no FIXME sites: everything
        // above its WARN tier is `TRACE_(vulkan)`, so *+ Stubs* enables nothing
        // that *+ Warnings* did not already have. A twenty-minute session was
        // spent at that stop expecting the unix half of `vkCreateSwapchainKHR`
        // and got no traces at all.
        volumes = mapOf(
            WineChannelLevel.STUBS to
                "nothing beyond warnings — this channel has no stub tier, measured",
            WineChannelLevel.EVERYTHING to
                "thousands of lines a frame — one per forwarded Vulkan call, counted",
        ),
    ),
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
        channel = "futexwho",
        secondary = "Names the thread broadcasting Win32 futex wakes that nobody is waiting for.",
        // Vessel's own channel, not upstream Wine's — patches/wine/0029. `sync`
        // counts wakes and waits but cannot say who is calling, and a program
        // that wakes an address hundreds of thousands of times while 259 waits
        // ever queue is spinning rather than deadlocked. That ratio is what
        // Resident Evil Requiem shows on a black screen, and this is the channel
        // that turns it into a thread id.
        //
        // Cheap by construction: it prints one wake in 8192, and only for wakes
        // that found an empty queue, so an ordinary session pays a counter
        // increment. Left at EVERYTHING because a rate-limited backtrace has no
        // meaningful quieter tier.
        //
        // First session with it on printed nothing at all, which is a result
        // and not a dud: the trace always prints the first futile wake, so zero
        // lines means the storm did not happen. Requiem now blocks with every
        // thread asleep instead of one thread spinning. That run also carried a
        // working shader cache and a different extension set, so nothing here
        // says which change moved it.
        addAt = WineChannelLevel.EVERYTHING,
        oneSessionFrom = WineChannelLevel.EVERYTHING,
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
        volumes = mapOf(
            // Two facts and they point opposite ways, so both are stated. The
            // documented cost is enormous; and relay is compiled out for
            // arm64ec outright — `#if (…) && !defined(__arm64ec__)`,
            // `dlls/ntdll/relay.c:37` — while every PE module here is ARM64X.
            // So this may also print nothing whatsoever, and one session
            // settles which.
            WineChannelLevel.EVERYTHING to
                "hundreds of megabytes in seconds if it works at all — relay is " +
                    "compiled out for arm64ec, and whether an ARM64X module keeps " +
                    "its thunks is unverified here",
        ),
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

    // — VKD3D_CONFIG, the only instrument that can explain a lost device -------
    vkd3dConfigFlag(
        flag = "breadcrumbs",
        secondary = "On device loss, replay the command buffers and name the last " +
            "command the GPU acknowledged.",
        // Not a level and not a volume: it prints nothing at all until the device
        // is already lost, and then it prints once. The cost is per-command
        // instrumentation on every submission, which is why it is not on by
        // default rather than why it is loud.
        caution = "Needs a component built with VKD3D_BREADCRUMBS=1; on a normal " +
            "build the word parses and does nothing.",
    ),
    // **The one flag here that changes what is drawn rather than what is said.**
    //
    // D3D12 requires a placed render target or depth buffer to be initialized by
    // the application before first use, and vkd3d deliberately does not do it —
    // on hardware with DCC/HTILE the initialization clobbers other resources
    // aliased into the same heap (`resource.c:4607`). A title that forgets shows
    // uninitialized memory: flat white where geometry should be, and depth that
    // sorts wrongly against the sky.
    //
    // Upstream ships a table of titles that forget — Lost Judgment, Spider-Man,
    // Miles Morales, Deus Ex: Mankind Divided, FFXVI (`device.c:641-666`) — and
    // Requiem is too new to be in it. That table is the reason this is offered
    // rather than guessed at: it is a known shape of bug with a known fix, and
    // the Adreno objection does not apply, because there is no DCC or HTILE here
    // to clobber.
    vkd3dConfigFlag(
        flag = "force_initial_transition",
        secondary = "Initialise placed render targets and depth buffers, which D3D12 " +
            "says the game must do and some games do not.",
        caution = "Costs a transition per placed resource. Try it when geometry is " +
            "flat white or sorts through walls; it does nothing for a title that " +
            "already initialises correctly.",
    ),
    vkd3dConfigFlag(
        flag = "breadcrumbs_sync",
        secondary = "As above, and stall after every command so the report names the " +
            "exact one rather than a region.",
        caution = "Serialises the GPU. Frame rate is not a measurement while this is on.",
    ),
)

/**
 * A `VKD3D_CONFIG` member — a switch inside a comma-joined set.
 *
 * The same shape as [turnipRenderFlag] and for the same reason: `VKD3D_CONFIG`
 * holds several independent words, so a row must *add* one rather than write the
 * variable. `FIXED_VKD3D_CONFIG` is carried as the family's base member, so
 * turning one of these on composes `breadcrumbs,nodxr` and does not quietly turn
 * DXR back on.
 *
 * **These are the reason the type column exists.** `VKD3D_CONFIG` is in
 * `RESERVED_SESSION_ENV`, so the free-text environment table drops it — a device
 * run was spent discovering that, with the guest's own environ still reading
 * `nodxr`. A reserved variable is reachable only by being declared, and before
 * [FAMILIES] a declared thing could only be a Wine channel or a Turnip flag.
 */
private fun vkd3dConfigFlag(flag: String, secondary: String, caution: String) = Loggable(
    name = flag,
    emit = Emit.ListMember("VKD3D_CONFIG", flag),
    levels = ON_OFF,
    labels = ON_OFF_LABELS,
    baseline = Emit.OFF,
    secondary = "VKD3D_CONFIG — $secondary",
    addAt = Emit.ON,
    caution = caution,
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
    loggableIn(name, if (turnip) "turnip" else "wine")

/** The entry for a row, resolved in its own family. */
fun loggableFor(row: DiagnosticSetting): Loggable = loggableIn(row.name, row.resolvedType)

/**
 * The declared entry for [name], or one synthesised **in the shape [type]
 * implies**.
 *
 * **The shape is the whole point, and getting it wrong is silent.** Every
 * unknown name used to become a Wine channel, so a `TU_DEBUG` flag typed into
 * the wrong table was composed into `WINEDEBUG`, where Wine ignores a channel it
 * does not know and the user concludes the flag did nothing. That is the failure
 * `unknownTurnipFlag` was added for, and it generalises: a `VKD3D_CONFIG` member
 * written as a scalar would overwrite the variable and drop `nodxr`; a scalar
 * written as a list member would compose a comma list nothing reads.
 *
 * An unrecognised [type] is not an error — it is the free-text case, and it
 * lands on [DiagnosticFamily.Shape.RAW], the same plain variable the environment
 * table always was and subject to the same reserved-name refusal.
 */
fun loggableIn(name: String, type: String): Loggable {
    LOGGABLES.firstOrNull { it.name == name }?.let { return it }
    val family = familyFor(type)
    return when (family?.shape) {
        DiagnosticFamily.Shape.CHANNEL, null ->
            if (family == null && type.isNotBlank() && !type.equals("wine", ignoreCase = true)) {
                unknownVariable(name, type)
            } else {
                wineChannel(name, "A Wine debug channel this build has no description for.")
            }
        DiagnosticFamily.Shape.LIST -> unknownListMember(name, family)
        DiagnosticFamily.Shape.SCALAR, DiagnosticFamily.Shape.RAW -> unknownVariable(name, family.wire)
    }
}

/**
 * A member of a comma-joined variable that this build has no description for.
 *
 * Generalised from `unknownTurnipFlag`, which said the same thing about
 * `TU_DEBUG` alone. The caution is the same warning for the same reason: every
 * one of these variables is parsed by a flag table that ignores a word it does
 * not recognise, so a typo and a flag that did nothing are indistinguishable.
 */
private fun unknownListMember(flag: String, family: DiagnosticFamily) = Loggable(
    name = flag,
    emit = Emit.ListMember(family.variable, flag),
    levels = ON_OFF,
    labels = ON_OFF_LABELS,
    baseline = Emit.OFF,
    secondary = "${family.variable} — a flag this build has no description for.",
    addAt = Emit.ON,
    caution = "Vessel does not know this flag. ${family.label} ignores one it does " +
        "not recognise, so a typo looks exactly like a flag that did nothing.",
)

/**
 * A variable of its own that this build has no description for.
 *
 * No ladder, because there is nothing to know about one: the level is whatever
 * the user typed, sent verbatim. That is the honest position for a variable
 * nobody has curated, and it is what the free-text environment table did.
 */
private fun unknownVariable(variable: String, family: String) = Loggable(
    name = variable,
    emit = Emit.Variable(variable, ""),
    levels = emptyList(),
    baseline = "",
    secondary = if (family.isBlank()) "A variable this build has no description for."
    else "$family — a variable this build has no description for.",
    caution = "Vessel does not know this variable, so nothing here can say what it " +
        "costs or whether the value is one it accepts.",
)

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

/**
 * **The game is told it has twice the memory that exists, and this is the half
 * that is imaginary.**
 *
 * Adreno has one memory heap and it is `DEVICE_LOCAL`, so DXVK's loop over the
 * heaps finds nothing to put in `sharedMemory` and then does this
 * (`native/dxvk/src/dxgi/dxgi_adapter.cpp:455`):
 *
 *     // This can happen on integrated GPUs with one memory heap, over-report
 *     // here since some games may be allergic to reporting no shared memory.
 *     if (!sharedMemory)
 *       sharedMemory = deviceMemory;
 *
 * `DXGI_ADAPTER_DESC` then carries the same figure twice, and a title that adds
 * the two — RE Engine's *Max VRAM* readout is `dedicated + shared` — sizes its
 * streaming pools to double the heap. Measured on 2026-08-16 with
 * `hardware.vram = 4`: the log says `Heap 0: 3.98 GiB` and `Budget: 3.48 GiB`,
 * Requiem's own options screen says **7.98 GB**, and the session ends at
 * `vkd3d_allocate_device_memory: Failed to allocate device memory (size
 * 16777216)` — a 16 MB request refused.
 *
 * **128 MB, and zero is not expressible.** `dxgi.maxSharedMemory = 0` is the one
 * value that does the opposite of what it reads like: DXVK gates the cap on
 * `if (options->maxSharedMemory > 0 && ...)` (`:484`), so zero means *do not cap*
 * and leaves the doubled figure exactly as it was. 1 is the smallest value that
 * caps anything, and a byte-exact zero would need a patch to the fallback at
 * `:455`.
 *
 * 128 MB rather than that 1 MB floor, because the comment quoted above records a
 * real hazard and not a hypothetical one: some titles refuse an adapter that
 * reports no shared memory at all, and that failure costs a session to recognise
 * because it looks like a launch problem rather than a memory one. The
 * arithmetic this exists to fix is unaffected — 128 MB is 3% of a 4 GB heap,
 * inside the noise of what the budget moves during a session and far too little
 * to size a streaming pool from — so the margin is bought for nothing.
 *
 * The D3D12 budget API is not touched by this and never was: `QueryVideoMemoryInfo`
 * sums only heaps matching the requested segment (`:287-295`), so `NON_LOCAL`
 * already reports zero here. This corrects the older `DXGI_ADAPTER_DESC` path,
 * which is the one Requiem reads.
 */
const val FIXED_DXVK_SHARED_MEMORY_MB: Int = 128
const val FIXED_DXVK_MAX_SHARED_MEMORY: String =
    "dxgi.maxSharedMemory = $FIXED_DXVK_SHARED_MEMORY_MB"
/**
 * **`fixme`, which is `vkd3d`'s own default — and it used to be `warn`, which
 * was 62% of a session.**
 *
 * Measured on a Resident Evil Requiem session, 3,645 lines: 2,251 of them were
 * vkd3d at WARN, and the whole of that is now known to be noise —
 *
 * | lines | message |
 * |---|---|
 * | 1,146 | `has_extension: Extension "VK_KHR_present_*" is disabled` |
 * |   902 | `DSV format is DXGI_FORMAT_UNKNOWN` |
 * |   199 | `vk_image_memory_barrier_for_initial_transition` |
 * |   106 | `Invalid resource alignment` and its `GetResourceAllocationInfo3` pair |
 *
 * Each was chased and each is benign: the DSV one sets `null_attachment_mask`
 * and builds the pipeline, and the alignment pair is a `GetResourceAllocationInfo3`
 * *query* that native D3D12 refuses identically — vkd3d's own conformance test
 * asserts `SizeInBytes == ~0ull` for those descriptors.
 *
 * **The ladder is inverted relative to Wine's and that is the trap**:
 * `debug.c:38-47` orders it `none < err < info < fixme < warn < trace`, so
 * `fixme` is *quieter* than `warn` while carrying err and info. And
 * `debug.c:97` sets an unset channel to `FIXME`, so `warn` was Vessel choosing
 * to run louder than upstream rather than accepting a default.
 *
 * **What this gives up, stated plainly.** The WARN tier includes
 * `d3d12_device_mark_as_removed: Device … is lost`. That is a real line and it
 * is now absent by default. It is affordable because the *error* tier carries
 * the same event with more information — `vkd3d_wait_for_gpu_timeline_semaphore:
 * … vr -4` is what actually named the device loss — and because a row raises
 * this back to `warn` for one session when the question is a graphics one.
 */
const val FIXED_VKD3D_DEBUG: String = "fixme"

/**
 * `fixme`, for the reason above and one measurement of its own.
 *
 * At `warn` this produced **26,966 lines in six minutes** — 98% of that
 * session's output — as `parse_dxbc: Ignoring DXBC checksum` plus
 * `skip_dword_unknown`, eight lines per DXBC parse across 3,025 shaders. Both
 * sites are WARN in `dxbc.c`, so upstream's `fixme` default is silent for them
 * and Vessel's `warn` was not. See the `shaders` topic in `TraceSpec.kt`, which
 * records the arithmetic that confirmed the mechanism rather than just the grep.
 */
const val FIXED_VKD3D_SHADER_DEBUG: String = "fixme"

/**
 * `VKD3D_CONFIG`, which is a comma-joined set rather than a level.
 *
 * Named because two places have to agree about it and neither can see the other:
 * `sessionEnvironment` writes it, and the `vkd3dconfig` family in [FAMILIES]
 * carries it as a base member so a row that adds `breadcrumbs` composes
 * `breadcrumbs,nodxr` instead of replacing the whole variable. A literal in both
 * would silently drop `nodxr` the first time someone used the family, and the
 * symptom would be a behaviour change while trying to *observe* behaviour.
 *
 * `nodxr` is not a diagnostic: see the assignment for why DXR is off.
 */
const val FIXED_VKD3D_CONFIG: String = "nodxr"
/**
 * Silent, because the translator's debug tier is not a diagnostic — it is the
 * whole log.
 *
 * Measured on the device 2026-08-13, one Resident Evil Requiem session of about
 * six minutes: 49 MB across the head and both tail files, ~508,000 lines, of
 * which **99.9%** were a single pair repeated per instruction —
 *
 *     TF Exception: Code: 80000002 Address: <pc>
 *     TF Handled unaligned atomic: new pc: <pc>
 *
 * every non-FEX source in the same session came to roughly 350 lines. It is
 * `LogMan::Msg::DFmt` at `Source/Windows/ARM64EC/Module.cpp:716`, and `MSG_LEVEL`
 * is a `constexpr = INFO` in `LogManager.h:41`, so the call is compiled in and
 * cannot be built out. The rate limiter was dropping 9,000-18,000 lines at a
 * time and still could not keep up.
 *
 * `SILENTLOG` is the only gate: `Source/Windows/Common/Logging.cpp:36-49`
 * returns before `InstallHandler` when it is set, so nothing is formatted and
 * nothing is written. Left as `0` the same file resolves `__wine_dbg_output`
 * and writes into the pipe this app drains into the session log — so the cost
 * is paid twice, once formatting and once draining.
 *
 * Set to `0` from the row to get it back for one session; that is the right
 * move when FEX itself is the suspect, and only then.
 */
const val FIXED_FEX_SILENTLOG: String = "1"
const val FIXED_FEX_OUTPUTLOG: String = "stderr"

/** Unset: Mesa then picks its Android default, which is logcat. */
const val FIXED_MESA_LOG: String = ""

/**
 * Also unset, and it is a **different variable from [FIXED_MESA_LOG]** — a
 * distinction this project did not have and needed.
 *
 * `MESA_LOG` chooses the *sink* and `MESA_LOG_LEVEL` chooses the *severity
 * floor*; they are parsed by two separate lines of `mesa_log_init_once`
 * (`native/mesa/src/util/log.c:116-117` and `:134-137`). Turning the file logger
 * on without saying anything about the level therefore gets whatever the build
 * defaults to, which in a release build is `MESA_LOG_INFO`
 * (`native/mesa/src/util/log.h:50-53`) — INFO and everything above it, from the
 * whole of Mesa and not only Turnip.
 *
 * Left unset here for the same reason [FIXED_MESA_LOG] is: absent is Mesa's own
 * choice and this layer has no measurement that says otherwise. The `driver`
 * topic sets both together, which is the point of having a topic.
 *
 * Note the accepted words are `error`, `warning`, `info`, `debug` — **`warning`
 * and not `warn`** (`log.c:76-89`). An unrecognised value is not an error: it
 * yields `MESA_NUM_LOG_LEVELS` and `log.c:468-474` quietly resets to the default
 * and warns once, which is exactly the silent no-op this facility exists to
 * remove.
 */
const val FIXED_MESA_LOG_LEVEL: String = ""

/**
 * `MESA_VK_WSI_DEBUG=sw` — the whole-frame CPU copy, and today's default.
 *
 * Named rather than spelled `"sw"` because it is now a *stop on a ladder* as
 * well as a value the session sends, and the two must not drift: an
 * [Emit.Variable] whose baseline disagreed with what `sessionEnvironment` wrote
 * would put this variable in the diagnostics map for a container nobody has
 * touched — and "an untouched record composes nothing" is the one property that
 * whole stage is asserted to have.
 *
 * `sw` and not `sw,linear`: forcing a linear swapchain image removes a blit on
 * paper and measured ~14% *worse* on the mean, because rendering into a linear
 * image makes the GMEM resolve write an untiled, uncompressed layout. The
 * numbers are at the `MESA_VK_WSI_DEBUG` assignment in `SessionEnvironment.kt`,
 * and `docs/BANDWIDTH.md` §1 cites them as the clearest case in this repo of
 * bandwidth arithmetic losing to a tiler measurement. Do not re-derive it.
 */
const val WSI_SOFTWARE: String = "sw"

/**
 * The empty value, which is DRI3 — and empty is *exactly* unset here.
 *
 * `native/mesa/src/vulkan/wsi/wsi_common.c:80` reads the variable through
 * `parse_debug_string` (`native/mesa/src/util/u_debug.c`), whose loop is
 * `for (; n = strcspn(s, ", \n"), *s; …)`. On `""` the body never runs and it
 * returns 0 — bit for bit what it returns for `NULL`. So this is not "an empty
 * flag list that happens to behave like absence"; there is no flag whose
 * absence differs from its not being named.
 *
 * A stop rather than a removal because the diagnostics stage can only *write*
 * keys. See the assignment in `SessionEnvironment.kt` for the two properties
 * that buys, and for the one place empty and unset genuinely differ — Mesa's
 * Android system-property fallback, which fires only on a NULL `getenv`.
 */
const val WSI_DRI3: String = ""

/**
 * What the session actually sends: [ZERO_COPY_PRESENT] read as a value.
 *
 * Derived rather than written down twice. The constant is the *default* and
 * this is the row's baseline; if they ever disagreed, a fresh container would
 * silently carry a diagnostics override nobody asked for.
 *
 * **A getter and not a stored `val`, and the compiler is what says so.** Every
 * other `FIXED_*` here is a `const val`, which is inlined at each use site and
 * so has no initialisation order at all. This one cannot be — Kotlin's
 * compile-time constant evaluator does not fold an `if` — and a plain `val`
 * declared in this section is initialised *after* [LOGGABLES], which reads it.
 * That is `Variable 'FIXED_MESA_VK_WSI_DEBUG' must be initialized`, caught at
 * compile time here; the same shape reached at runtime would have been a null
 * baseline and a row that emitted on every untouched container.
 *
 * Moving the declaration above [LOGGABLES] would also work and is worse: it
 * puts one constant a thousand lines from the eight it belongs with, to encode
 * an ordering nothing in the file states.
 */
val FIXED_MESA_VK_WSI_DEBUG: String
    get() = if (ZERO_COPY_PRESENT) WSI_DRI3 else WSI_SOFTWARE

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
    "debugstr" to listOf("warn+debugstr"),
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
    /**
     * What the *currently selected* stop is known to cost, or null.
     *
     * A property of the row's level rather than of the row, so it changes as the
     * dropdown moves — which is the only way it can answer the question it
     * exists for, which is asked while the dropdown is open. See
     * [Loggable.volumes] for why most stops have nothing here.
     */
    val volume: String?,
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
    /**
     * The [DiagnosticFamily.wire] this row belongs to — column one.
     *
     * **This replaced a boolean called `isTurnipFlag` that decided which of three
     * tables drew the row.** Three tables was never a fact about the stack: it
     * was the row model only being able to express two families plus a free-text
     * escape hatch, and the screen inheriting that shape. One list with a type
     * column says the same thing without asking the reader which table a knob
     * they have never heard of lives in.
     */
    val type: String,
    /** [DiagnosticFamily.label], or the raw wire for a family nobody declared. */
    val typeLabel: String,
    /** False for a fixed row: the declaration decides its family, not the user. */
    val typeEditable: Boolean = true,
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
            volume = loggable.volume(loggable.fixedLevel.orEmpty()),
            // These are sent every session, by definition.
            oneSession = false,
            nameEditable = false,
            levelEditable = false,
            removable = false,
            nameIsInvalid = false,
            type = loggable.family,
            typeLabel = familyFor(loggable.family)?.label ?: loggable.family,
            typeEditable = false,
        )
    }

    // `normalised()` folds any legacy `env` list in as `env`-typed rows, so the
    // screen renders one list and never has to ask which of three it is in.
    val added = diagnostics.normalised().rows.mapIndexed { index, row ->
        val loggable = loggableFor(row)
        DiagnosticRow(
            index = index,
            name = row.name,
            secondary = if (row.name.isEmpty()) null else loggable.secondary,
            caution = if (row.name.isEmpty()) null else loggable.caution,
            levels = loggable.levels,
            levelLabels = loggable.labels,
            level = row.level,
            levelIsMachine = loggable.levelIsMachine,
            volume = if (row.name.isEmpty()) null else loggable.volume(row.level),
            oneSession = row.isOneSession,
            // Always: this row is the user's, so its name is theirs to set even
            // when — especially when — it is still empty.
            nameEditable = true,
            // A gated row keeps its name and its cross and loses its level,
            // which is what tells it apart from a fixed one; that, and the
            // caution saying what is missing.
            levelEditable = row.name.isNotEmpty() && row.isAllowed(diagnostics),
            removable = true,
            // **Only Wine channels are checked against Wine's grammar.**
            //
            // It used to be "not a Turnip flag", which was the same rule with a
            // smaller vocabulary, and the moment there were more families it
            // started lying: `VKD3D_SHADER_DUMP_PATH` in an `env` row was drawn
            // in the error tone for failing a channel-name test that has nothing
            // to do with environment variables. Every family except `wine`
            // names something whose grammar this layer does not know — a driver
            // flag, a comma-set member, a variable nobody has curated — so the
            // honest answer for those is no claim at all, which is what the
            // caution on the row already says.
            nameIsInvalid = row.name.isNotEmpty() &&
                row.resolvedType == "wine" && !isLoggableName(row.name),
            // The stored origin wins for an unnamed row, which has no name to
            // ask; once named the two agree. See `DiagnosticSetting.turnip`.
            type = row.resolvedType,
            typeLabel = familyFor(row.resolvedType)?.label ?: row.resolvedType,
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
 *   the manifest can contribute flags too and this stage adds to them. It applies
 *   to `TU_DEBUG` **only**; every other list family takes its base from
 *   [listBaseMembers], which is derived from [FAMILIES].
 */
fun diagnosticEnvironment(
    diagnostics: ContainerDiagnostics,
    turnipBaseFlags: List<String> = listOf(TU_DEBUG_STARTUP),
    /**
     * What the session already put in `DXVK_CONFIG`, passed in for the same
     * reason [turnipBaseFlags] is: half of it is derived from the container's
     * `hardware.vram` and so cannot be a declared constant. The declared base
     * member is the fallback for a caller with no container to hand.
     */
    dxvkBaseConfig: List<String> = listOf(FIXED_DXVK_MAX_SHARED_MEMORY),
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val emitted = emitted(diagnostics)

    if (emitted.wineTerms.isNotEmpty()) {
        out["WINEDEBUG"] = (WINEDEBUG_CHANNELS.split(",") + emitted.wineTerms).joinToString(",")
    }
    out.putAll(emitted.variables)

    // **The base is per variable, and it used to be per call.** This loop
    // appended `turnipBaseFlags` to *every* list it had composed, which was
    // invisible while `TU_DEBUG` was the only list variable in the stack and
    // wrong the moment it stopped being: a `VKD3D_CONFIG` row would have
    // composed `breadcrumbs,startup` — Turnip's ground-truth flag written into
    // vkd3d's config, where it means nothing, and `nodxr` silently dropped.
    //
    // `TU_DEBUG` and `DXVK_CONFIG` still take their bases from parameters rather
    // than from [FAMILIES], because something outside this file contributes to
    // both and this stage adds to what it finds: the manifest contributes Turnip
    // flags, and `hardware.vram` decides half of the DXVK memory pair. The
    // declared base is the fallback for a caller that passes nothing.
    val bases = listBaseMembers()
    val separators = listSeparators()
    for ((variable, members) in emitted.lists) {
        if (members.isEmpty()) continue
        val base = when (variable) {
            "TU_DEBUG" -> turnipBaseFlags
            "DXVK_CONFIG" -> dxvkBaseConfig
            else -> bases[variable].orEmpty()
        }
        // The added flags first and the base last, so `startup` stays where it
        // is: it is the ground truth for whether Turnip loaded and nothing may
        // displace it. The same ordering serves `nodxr` for the same reason —
        // what the session already chose is not something a diagnostic may move.
        //
        // The separator comes from the family and not from here: `DXVK_CONFIG`
        // splits on `;` and the other two on `,`, and a comma in a DXVK config
        // makes one line that its parser drops in silence.
        out[variable] = (members + base).distinct()
            .joinToString(separators[variable] ?: ",")
    }
    return out
}

/**
 * The trace spec's contribution and then every row's, folded.
 *
 * **The order is the precedence rule and it is free rather than arranged.** The
 * topics go first and the hand-added rows second, so a row wins: a topic is the
 * broad brush and a row is the instrument, and someone who has typed both means
 * the second one. Nothing enforces that — Wine's parser takes the last term
 * (`native/wine/dlls/ntdll/unix/debug.c:135-188`, left to right) and a
 * `LinkedHashMap` takes the last `put`, so writing them in this order *is* the
 * rule.
 *
 * There is still no branch here on what a row is, and now none on what a topic
 * is either: both fold through the same [Emit] strategies.
 */
private fun emitted(diagnostics: ContainerDiagnostics): EmittedEnvironment {
    val out = EmittedEnvironment()
    applyTraceSpec(diagnostics.traceSpec(), out)
    diagnostics.inForce().rows.forEach { row ->
        // **The row, not the row's name.** `loggableFor(String)` resolves in the
        // *wine* family, because that is the only thing a bare name can mean; a
        // row knows its own type and `loggableFor(DiagnosticSetting)` uses it.
        //
        // With the name-only overload an undeclared flag typed as `turnip`
        // became a Wine channel, so `TU_DEBUG=flushall` composed into
        // `WINEDEBUG` — where Wine ignores a channel it has never heard of and
        // the flag looks like it did nothing. That is the exact failure
        // [loggableIn] was written to end, arrived at through the one call site
        // that never got the second argument.
        //
        // It hid because *declared* flags are matched by name before the family
        // is consulted, so every row anybody had tried worked. `nolrz` and
        // `noubwc` reached the driver and were measured; `flushall`, which this
        // build does not declare, silently did not — and a driver flag that does
        // nothing is indistinguishable from a driver flag that changed nothing.
        // One device session was spent reading a bisect that never ran.
        loggableFor(row).emit.apply(row.level, out)
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
fun costWarning(caution: String?, oneSession: Boolean, volume: String? = null): String = buildString {
    if (!caution.isNullOrBlank()) append(caution).append(' ')
    // **The number comes before the prose, when there is one.** "The log will
    // hit its cap sooner" is true of every expensive stop and is therefore not
    // information; "191,000 lines in one session, measured" is the sentence that
    // stops somebody spending twenty minutes on the device to find that out.
    // See [Loggable.volumes].
    if (!volume.isNullOrBlank()) append("Expect ").append(volume).append(". ")
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
