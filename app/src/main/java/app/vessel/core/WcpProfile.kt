package app.vessel.core

import kotlinx.serialization.Serializable

/**
 * `profile.json` inside a `.wcp`, exactly as `build/package_wcp.py` writes it.
 *
 * One declaration for the whole app. It was previously duplicated as a private
 * model inside [app.vessel.data.InstalledComponents], which is the shape of bug
 * this project can least afford: a wire format described in two places drifts
 * the first time the packager gains a field, and the half that was not updated
 * fails silently because decoding is lenient.
 *
 * Decoding *is* lenient, deliberately. This format is shared with the wider
 * Winlator ecosystem, so a package from another producer must not fail to read
 * because it carries a key this app has never heard of — see
 * [app.vessel.di.DataModule.json].
 */
@Serializable
data class WcpProfile(
    /** The wire string of a [ComponentType]; anything outside that set is refused. */
    val type: String,
    val versionName: String,
    val versionCode: Int,
    val name: String? = null,
    val description: String? = null,

    /**
     * Every payload path the packager put in the archive, relative to its root
     * and excluding `profile.json` itself.
     *
     * Advisory rather than authoritative: the installer extracts what the tar
     * actually contains and validates each entry on its own merits, because a
     * `.wcp` is downloaded content and a manifest that disagrees with its own
     * archive is exactly what a malicious one would carry.
     */
    val files: List<String> = emptyList(),

    val vessel: WcpVessel? = null,
) {
    /** The known [ComponentType], or null when the packager used a type we cannot load. */
    val componentType: ComponentType? get() = ComponentType.entries.firstOrNull { it.wire == type }
}

/** Vessel's own additions. Other Winlator-family apps ignore this key. */
@Serializable
data class WcpVessel(
    val builtAt: String? = null,
    val formatVersion: Int? = null,
    val provenance: WcpProvenance? = null,
)

/** `write_provenance` in `build/common.sh`. */
@Serializable
data class WcpProvenance(
    val component: String? = null,
    val version: String? = null,
    val target: String? = null,
    val targetDesc: String? = null,
    val sourceRef: String? = null,
    val sourceSha: String? = null,
    val cpuFlags: String? = null,
    val ndk: String? = null,
    val apiLevel: String? = null,
    val builtBy: String? = null,
)
