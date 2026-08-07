package app.vessel.core.params

import app.vessel.core.ArchProfile
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
 * This file is the whole reason the container editor has no per-setting code in
 * it. The editor renders one composable per [ParamType] and nothing per key, so
 * adding a `BOX64_DYNAREC_*` knob is a data change to the manifest and never a
 * UI change — which is the promise `docs/DESIGN.md` makes about this screen and
 * the one most easily broken by a single `when (key)`.
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
     * including the ones the container's architecture profile will hide.
     *
     * Storing the hidden ones too is deliberate. A Universal container carries
     * correct Box64 values it is not currently showing, so switching a container
     * to Compatibility — or reading its values back for an export bundle — never
     * finds a hole where a setting should be.
     */
    fun defaults(): Map<String, ParamValue> =
        allParams.mapNotNull { spec -> spec.defaultValue()?.let { spec.key to it } }.toMap()
}

@Serializable
data class ParamGroup(
    val id: String,
    val title: String,
    /** One sentence under the group heading. Optional; most groups need none. */
    val help: String? = null,
    /** An advanced group hides behind the same disclosure as an advanced param. */
    val advanced: Boolean = false,
    val params: List<ParamSpec>,
)

/**
 * The five kinds of control the editor knows how to draw.
 *
 * Adding a sixth is the one manifest change that also needs UI, and that is the
 * intended boundary: a new *type* is a new interaction, a new *param* is not.
 */
@Serializable
enum class ParamType {
    @SerialName("enum") ENUM,
    @SerialName("bool") BOOL,
    @SerialName("int") INT,
    @SerialName("multi") MULTI,
    @SerialName("component") COMPONENT,
}

/**
 * One setting.
 *
 * [help] is not optional in spirit even though it is in the schema: the manifest
 * says a setting that cannot be explained in one plain sentence belongs in
 * Diagnostics rather than here, and the editor renders the sentence under every
 * control it draws.
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

    /** [ParamType.COMPONENT]: which `.wcp` type the selector resolves against. */
    val componentType: String? = null,

    /**
     * Which architecture profiles this applies to, by [ArchProfile] name. Absent
     * means all of them — most graphics and system settings are profile-neutral.
     */
    val appliesTo: List<String>? = null,

    /** Behind the "Show advanced" disclosure, collapsed by default. */
    val advanced: Boolean = false,

    /**
     * Bounds that only hold in some configurations. The one live case is
     * `box64.CALLRET`, which Box64 documents as broken at level 2 under
     * WowBox64 — so the ceiling drops to 1 when the 32-bit engine is WowBox64,
     * and the editor says why rather than silently refusing the third step.
     */
    val clamp: List<ParamClamp> = emptyList(),

    /** Ordering hint only; the editor draws groups in manifest order regardless. */
    val dependsOn: String? = null,

    /** The value that earns [warnText]. Compared against the current value as JSON. */
    val warnWhen: JsonElement? = null,
    val warnText: String? = null,
) {
    fun appliesTo(profile: ArchProfile): Boolean =
        appliesTo == null || profile.name in appliesTo

    /** The label for one option, falling back to the wire value. */
    fun label(option: String): String = optionLabels[option] ?: option

    /** The manifest default, narrowed to the shape [type] implies. */
    fun defaultValue(): ParamValue? {
        val raw = default ?: return null
        return when (type) {
            ParamType.BOOL -> raw.jsonPrimitive.booleanOrNull?.let(ParamValue::Flag)
            ParamType.INT -> raw.jsonPrimitive.intOrNull?.let(ParamValue::Count)
            ParamType.ENUM, ParamType.COMPONENT -> ParamValue.Text(raw.jsonPrimitive.content)
            ParamType.MULTI -> ParamValue.Choices(raw.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    /** True when [value] is the one this param warns about. */
    fun warnsAt(value: ParamValue?): Boolean {
        val trigger = warnWhen ?: return false
        val triggerValue = when (type) {
            ParamType.BOOL -> trigger.jsonPrimitive.booleanOrNull?.let(ParamValue::Flag)
            ParamType.INT -> trigger.jsonPrimitive.intOrNull?.let(ParamValue::Count)
            ParamType.ENUM, ParamType.COMPONENT -> ParamValue.Text(trigger.jsonPrimitive.content)
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
    // holding — the user lowered the 32-bit engine to WowBox64 after setting
    // CALLRET to 2 — so it is pulled into range for display as well as on save.
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
