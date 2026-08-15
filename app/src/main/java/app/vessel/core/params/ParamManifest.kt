package app.vessel.core.params

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `assets/params-manifest.json`, as Kotlin.
 *
 * The editor renders one composable per [ParamType] and nothing per key, so
 * adding a `FEX_*` or `TU_*` knob is a data change and never a UI change. A
 * single `when (key)` anywhere downstream breaks that.
 *
 * Unknown keys are ignored when this is decoded, because the manifest carries
 * `_comment` and `_note` prose that is addressed to whoever edits it rather than
 * to the app.
 */
@Serializable
data class ParamManifest(
    val schemaVersion: Int,
    val groups: List<ParamGroup>,
) {
    /** Every param in declaration order, for lookups that do not care about grouping. */
    val allParams: List<ParamSpec> get() = groups.flatMap { it.params }

    fun spec(key: String): ParamSpec? = allParams.firstOrNull { it.key == key }

    /**
     * The starting values for a new container: every param's manifest default,
     * always all of them, so reading a container's values back never finds a
     * hole where a setting should be.
     */
    fun defaults(): Map<String, ParamValue> =
        allParams.mapNotNull { spec -> spec.defaultValue()?.let { spec.key to it } }.toMap()
}

/**
 * One section of the editor.
 *
 * Groups are named for what a setting *affects* — Display, Graphics, Rendering,
 * Compatibility — never for the subsystem that implements it, which would group
 * a FEX barrier flag with a Wine synchronisation mode on the grounds that both
 * are "system".
 *
 * Nothing is hidden, so declaration order *is* the hierarchy: the groups a user
 * opens the screen for come first, the ones that are correct until something
 * breaks come last.
 */
@Serializable
data class ParamGroup(
    val id: String,
    val title: String,
    /** One sentence under the group heading. Optional; most groups need none. */
    val help: String? = null,
    val params: List<ParamSpec>,
)

/**
 * The six kinds of control the editor knows how to draw.
 *
 * Adding a sixth is the one manifest change that also needs UI, and that is the
 * intended boundary: a new *type* is a new interaction, a new *param* is not.
 */
@Serializable
enum class ParamType {
    @SerialName("enum") ENUM,

    /**
     * Free text, for the one kind of setting whose valid values cannot be
     * enumerated: a DLL override list names DLLs we have never heard of.
     *
     * Everything else in this file is a closed set on purpose — a control the
     * user cannot get wrong. This type is the deliberate exception, not a
     * loophole to reach for when writing options is tedious.
     */
    @SerialName("text") TEXT,

    /**
     * A closed list the user may also type past: the presets are the answer
     * almost every time, and the field accepts anything the setting's parser
     * accepts.
     *
     * Added for `display.resolution`. Its option list is 19 sizes and cannot be
     * complete — `parseGeometry` has always accepted any `WxH`, so the enum was
     * narrowing the product below what the code supports, and resolution is the
     * single biggest performance dial on this phone. That is the bar for
     * reaching for this type: **the underlying setting genuinely accepts values
     * that cannot be enumerated, and the presets are a convenience rather than
     * the permitted set.** It is not [TEXT] with a menu bolted on, and it is not
     * an excuse to stop writing options.
     */
    @SerialName("enumOrText") ENUM_OR_TEXT,

    @SerialName("bool") BOOL,
    @SerialName("int") INT,
    @SerialName("multi") MULTI,
    @SerialName("component") COMPONENT,
}

/**
 * One setting.
 *
 * [help] is not optional in spirit even though it is in the schema: the manifest
 * says a setting that cannot be explained in one plain sentence does not belong
 * in the file at all, and the editor renders the sentence under every control it
 * draws.
 */
@Serializable
data class ParamSpec(
    val key: String,
    val title: String,
    val help: String? = null,
    val type: ParamType,

    /** [ParamType.ENUM] and [ParamType.MULTI]: the wire values, in display order. */
    val options: List<String> = emptyList(),
    /** Human labels for [options], where the wire value is not presentable. */
    val optionLabels: Map<String, String> = emptyMap(),

    /** [ParamType.INT] bounds. Absent means unbounded, which nothing currently is. */
    val min: Int? = null,
    val max: Int? = null,

    /**
     * The manifest default, still as JSON: its Kotlin shape depends on [type],
     * so it is narrowed by [defaultValue] rather than at decode time.
     */
    @SerialName("default") val default: JsonElement? = null,

    /** The environment variable this becomes when a container is launched. */
    val env: String? = null,

    /**
     * The environment variable this param *appends a term to*, rather than
     * becoming.
     *
     * **Why the schema has this rather than the composer having a second
     * constant.** [env] copies a value into a variable, which is the whole
     * design: adding a knob is a data change. `WINEDLLOVERRIDES` never fitted,
     * because its value is built — a fixed list Vessel requires, then whatever
     * the container adds — so it was composed in code against a hardcoded key,
     * and the note beside that code said the schema would need the feature if a
     * second such param ever appeared. The OpenGL driver toggle is the second,
     * so this is the feature rather than the second constant.
     *
     * Terms are appended in manifest order, after whatever the composer starts
     * with, and joined by [appendSeparator]. Order is precedence for
     * `WINEDLLOVERRIDES` — Wine reads it left to right and a later term wins —
     * so a specific instruction placed after a general one is how a user
     * overrides a default rather than a way to break it.
     *
     * For a [ParamType.BOOL] the term is [appendValue] when the flag is set and
     * nothing when it is not; for a text param the term is the value itself.
     */
    val appendTo: String? = null,

    /** [ParamType.BOOL] with [appendTo]: the term contributed when true. */
    val appendValue: String? = null,

    /** What joins terms in [appendTo]. Semicolon, as `WINEDLLOVERRIDES` wants. */
    val appendSeparator: String = ";",

    /**
     * [ParamType.TEXT]: the greyed example shown in an empty field.
     *
     * Was hardcoded to `name=native,builtin` in the editor, which is the shape of
     * a *DLL override* — so the DXVK options field advertised a syntax DXVK does
     * not accept, in the one place a user would look to learn the syntax. A
     * placeholder that lies is worse than none.
     */
    val placeholder: String? = null,


    /** [ParamType.COMPONENT]: which `.wcp` type the selector resolves against. */
    val componentType: String? = null,

    /**
     * Bounds that only hold in some configurations: a ceiling that drops when
     * another param takes a particular value, with the reason shown next to the
     * control rather than the step silently refusing to move.
     *
     * No manifest entry declares one today. The mechanism stays because it is
     * generic, and the alternative is a `when (key)` in the editor the first
     * time a real conditional bound turns up.
     */
    val clamp: List<ParamClamp> = emptyList(),

    /** Ordering hint only; the editor draws groups in manifest order regardless. */
    val dependsOn: String? = null,

    /**
     * Folds this param, and the ones declared beside it carrying the same string,
     * into one collapsed block titled by it.
     *
     * **A group is what a setting belongs to; a section is how much of it a
     * reader has to walk past.** The two are different questions and this is the
     * second, which is why it is a param field rather than a second level of
     * [ParamGroup]: the upscaler's four constants belong to Display exactly as
     * much as Resolution does — they are simply four controls whose right value
     * is the default, and which are inert unless a container is being magnified.
     * Four such controls on the sheet push Resolution, the one dial that matters
     * on this phone, off the first screen.
     *
     * Only a *consecutive* run collapses together, so the manifest's order stays
     * the sheet's order and a section cannot silently gather params declared
     * pages apart.
     */
    val section: String? = null,

    /** The value that earns [warnText]. Compared against the current value as JSON. */
    val warnWhen: JsonElement? = null,
    val warnText: String? = null,
) {
    /** The label for one option, falling back to the wire value. */
    fun label(option: String): String = optionLabels[option] ?: option

    /** The manifest default, narrowed to the shape [type] implies. */
    fun defaultValue(): ParamValue? {
        val raw = default ?: return null
        return when (type) {
            ParamType.BOOL -> raw.jsonPrimitive.booleanOrNull?.let(ParamValue::Flag)
            ParamType.INT -> raw.jsonPrimitive.intOrNull?.let(ParamValue::Count)
            ParamType.ENUM, ParamType.ENUM_OR_TEXT, ParamType.TEXT, ParamType.COMPONENT ->
                ParamValue.Text(raw.jsonPrimitive.content)
            ParamType.MULTI -> ParamValue.Choices(raw.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    /** True when [value] is the one this param warns about. */
    fun warnsAt(value: ParamValue?): Boolean {
        val trigger = warnWhen ?: return false
        val triggerValue = when (type) {
            ParamType.BOOL -> trigger.jsonPrimitive.booleanOrNull?.let(ParamValue::Flag)
            ParamType.INT -> trigger.jsonPrimitive.intOrNull?.let(ParamValue::Count)
            ParamType.ENUM, ParamType.ENUM_OR_TEXT, ParamType.TEXT, ParamType.COMPONENT ->
                ParamValue.Text(trigger.jsonPrimitive.content)
            ParamType.MULTI -> ParamValue.Choices(trigger.jsonArray.map { it.jsonPrimitive.content })
        }
        return triggerValue != null && triggerValue == value
    }
}

/**
 * A conditional bound.
 *
 * [condition] is read as "every one of these params currently holds this value",
 * compared on the value's printed form so a `2` and a `"2"` in the manifest mean
 * the same thing. [reason] is shown next to the control whenever the clamp is
 * active — a ceiling with no explanation reads as a bug in the editor.
 */
@Serializable
data class ParamClamp(
    @SerialName("when") val condition: Map<String, String> = emptyMap(),
    val min: Int? = null,
    val max: Int? = null,
    val reason: String,
)

/**
 * One stored setting value.
 *
 * Four shapes rather than a string for everything, so a container document
 * round-trips without the reader having to consult the manifest to know whether
 * `"2"` was a number or an option name. The discriminator is `type`, which is
 * kotlinx-serialization's default and is what the on-disk JSON carries.
 */
@Serializable
sealed interface ParamValue {
    @Serializable @SerialName("bool")
    data class Flag(val value: Boolean) : ParamValue

    @Serializable @SerialName("int")
    data class Count(val value: Int) : ParamValue

    /** [ParamType.ENUM] and [ParamType.COMPONENT] — an option name or a selector. */
    @Serializable @SerialName("text")
    data class Text(val value: String) : ParamValue

    @Serializable @SerialName("set")
    data class Choices(val values: List<String>) : ParamValue

    /** The form a [ParamClamp.condition] is matched against. */
    fun asCondition(): String = when (this) {
        is Flag -> value.toString()
        is Count -> value.toString()
        is Text -> value
        is Choices -> values.joinToString(",")
    }
}

/**
 * A param resolved against one container: the spec, its current value, and the
 * bounds and warnings that hold right now.
 *
 * The editor screen draws exactly this and reaches for nothing else, which is
 * what keeps the clamp rule in one place instead of in the control that happens
 * to be affected by it.
 */
data class ResolvedParam(
    val spec: ParamSpec,
    val value: ParamValue,
    val min: Int?,
    val max: Int?,
    /** Non-null when a [ParamClamp] is narrowing [min] or [max] right now. */
    val clampReason: String?,
    /** Non-null when the current value is the one [ParamSpec.warnText] is about. */
    val warning: String?,
)

/**
 * Apply every clamp whose condition currently holds.
 *
 * Clamps narrow, never widen: two clamps active at once leave the tightest
 * bound of the two, so adding a manifest entry can only ever make a range safer.
 */
fun ParamSpec.activeClamp(values: Map<String, ParamValue>): ParamClamp? =
    clamp.firstOrNull { rule ->
        rule.condition.isNotEmpty() &&
            rule.condition.all { (key, expected) -> values[key]?.asCondition() == expected }
    }

/** [spec] against [values], with clamps and warnings already worked out. */
fun ParamSpec.resolve(values: Map<String, ParamValue>): ResolvedParam? {
    val value = values[key] ?: defaultValue() ?: return null
    val rule = activeClamp(values)
    val low = listOfNotNull(min, rule?.min).maxOrNull()
    val high = listOfNotNull(max, rule?.max).minOrNull()

    // The stored value can sit outside a clamp that has only just started
    // holding, because the param it depends on was changed afterwards — so it is
    // pulled into range for display as well as on save.
    val clamped = if (value is ParamValue.Count && (low != null || high != null)) {
        ParamValue.Count(value.value.coerceIn(low ?: Int.MIN_VALUE, high ?: Int.MAX_VALUE))
    } else {
        value
    }

    return ResolvedParam(
        spec = this,
        value = clamped,
        min = low,
        max = high,
        clampReason = rule?.takeIf { it.min != null || it.max != null }?.reason,
        warning = warnText?.takeIf { warnsAt(clamped) },
    )
}
