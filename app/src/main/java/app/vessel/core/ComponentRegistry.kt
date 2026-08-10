package app.vessel.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `registry/contents.json`, read.
 *
 * The document is written by `build/gen_registry.py` from the `.wcp` files a CI
 * run produced, so every field here has exactly one producer and the shapes
 * below are transcribed from it rather than guessed. Pure — a function over a
 * string — so the whole of the refusal policy is unit-testable without a network
 * or a device.
 *
 * ## Nothing is offered that cannot be checked
 *
 * An entry is turned into a [ComponentPackage] only if all of the following
 * hold, and is [RejectedEntry] with a sentence otherwise:
 *
 *  - its `type` is one [ComponentType] knows (so Box64 packages are refused
 *    here, where the reason can be shown, rather than at install time);
 *  - it carries a URL and a well-formed SHA-256 (see
 *    [ComponentPackage.isDownloadable] for why those two travel together);
 *  - that URL is `https`.
 *
 * The last one is not ceremony. A `.wcp` unpacks into a Wine prefix and its
 * contents are then executed; fetching one over a channel anybody on the path
 * can rewrite is the difference between a digest that proves the publisher's
 * intent and a digest that proves the attacker's. The digest alone does not save
 * it either, because the same channel serves the registry.
 *
 * Rejections are *returned*, not logged and dropped. A component missing from
 * the list with no explanation is the failure this project treats as worse than
 * an error — the user sees five packages where the registry has six and has no
 * way to find out why.
 */
object ComponentRegistry {

    /**
     * The schema this build understands. `gen_registry.py` writes 1.
     *
     * A document declaring a *higher* version is refused rather than read
     * leniently: the field exists precisely so that a future generator can
     * change the meaning of a key, and `ignoreUnknownKeys` would then hide the
     * change instead of surfacing it. A lower version is read, because every
     * version so far is a subset of this one.
     */
    const val SCHEMA_VERSION = 1

    /**
     * Where the published registry lives.
     *
     * The same rolling GitHub release the `.wcp` files are uploaded to by
     * `.github/workflows/_component.yml`, so the index and the artifacts it
     * indexes share an origin and cannot drift onto different hosts.
     *
     * That workflow's *Publish the component index* step downloads the release's
     * own packages back, runs `build/gen_registry.py` over them and uploads the
     * result here, serialised on a `components-release` concurrency group so two
     * component builds finishing at once cannot each publish an index missing
     * the other's package. Until a component build runs on `main` the URL is a
     * 404, and [app.vessel.service.ComponentCatalog] says so in those words
     * rather than showing an empty list.
     */
    const val DEFAULT_URL =
        "https://github.com/prskid1000/Vessel/releases/download/components/contents.json"

    /** What [parse] made of a document. */
    sealed interface Result {

        /** One line, addressed to whoever is looking at an empty component list. */
        val summary: String

        data class Available(
            val schemaVersion: Int,
            val packages: List<ComponentPackage>,
            val rejected: List<RejectedEntry>,
        ) : Result {
            override val summary: String
                get() = buildString {
                    append("${packages.size} component(s)")
                    if (rejected.isNotEmpty()) append(", ${rejected.size} refused")
                }
        }

        /** The bytes were not a registry at all, or were one this build will not read. */
        data class Unreadable(val detail: String) : Result {
            override val summary get() = "The component registry could not be read: $detail"
        }
    }

    /** One entry that will not be offered, and the sentence saying why. */
    data class RejectedEntry(val id: String, val why: String)

    /**
     * Read [text] as a registry document.
     *
     * [json] is the app's single lenient reader — `ignoreUnknownKeys` is
     * deliberate and is what lets a newer generator add a field without
     * breaking older installs, which is a different thing from letting it
     * change the *meaning* of a field (see [SCHEMA_VERSION]).
     */
    fun parse(json: Json, text: String): Result {
        val document = runCatching {
            json.decodeFromString(Document.serializer(), text)
        }.getOrElse {
            return Result.Unreadable(it.message?.take(200) ?: "not valid JSON")
        }

        if (document.schemaVersion > SCHEMA_VERSION) {
            return Result.Unreadable(
                "it declares schemaVersion ${document.schemaVersion} and this build of " +
                    "Vessel reads $SCHEMA_VERSION. Update the app.",
            )
        }

        val packages = mutableListOf<ComponentPackage>()
        val rejected = mutableListOf<RejectedEntry>()
        for (entry in document.components) {
            when (val outcome = entry.toPackage()) {
                is Accepted -> packages += outcome.value
                is Refused -> rejected += RejectedEntry(entry.id.ifBlank { "(unnamed)" }, outcome.why)
            }
        }
        return Result.Available(document.schemaVersion, packages, rejected)
    }

    private sealed interface Outcome
    private class Accepted(val value: ComponentPackage) : Outcome
    private class Refused(val why: String) : Outcome

    private fun Entry.toPackage(): Outcome {
        if (id.isBlank()) return Refused("the entry has no id")

        val componentType = ComponentType.entries.firstOrNull { it.wire == type }
            ?: return Refused("type '$type' is not one this app can load")

        if (url.isNullOrBlank()) return Refused("the registry gives no download URL for it")
        if (!url.startsWith(HTTPS_PREFIX)) {
            return Refused("its download URL is not https, and a .wcp is executed after it is unpacked")
        }
        if (!Sha256.isWellFormed(sha256)) {
            return Refused(
                if (sha256.isNullOrBlank()) {
                    "the registry publishes no SHA-256 for it, so nothing could verify the download"
                } else {
                    "its SHA-256 is not ${Sha256.HEX_LENGTH} hex characters"
                },
            )
        }

        return Accepted(
            ComponentPackage(
                id = id,
                type = componentType,
                name = name.ifBlank { id },
                versionName = versionName,
                versionCode = versionCode,
                sizeBytes = sizeBytes,
                // The registry is the *catalogue*. Whether this device has the
                // package is the store's answer, and merging the two is the
                // caller's job — see ComponentCatalog.
                installed = false,
                target = target ?: UNRECORDED,
                sourceSha = sourceSha ?: UNRECORDED,
                cpuFlags = cpuFlags ?: UNRECORDED,
                sha256 = sha256,
                url = url,
            ),
        )
    }

    private const val HTTPS_PREFIX = "https://"

    /** Matches `InstalledComponents`' wording, so one screen does not say two things. */
    private const val UNRECORDED = "not recorded in the package"

    @Serializable
    @SerialName("registry")
    private data class Document(
        val schemaVersion: Int = 0,
        val generator: String = "",
        val components: List<Entry> = emptyList(),
    )

    /**
     * One `components[]` element.
     *
     * Every field is defaulted, because a registry entry that is missing one is
     * a thing to *refuse with a reason* rather than a parse failure that takes
     * the other five entries down with it.
     */
    @Serializable
    private data class Entry(
        val id: String = "",
        val type: String = "",
        val name: String = "",
        val description: String = "",
        val versionName: String = "",
        val versionCode: Int = 0,
        val sizeBytes: Long = 0,
        val sha256: String? = null,
        val url: String? = null,
        val target: String? = null,
        val sourceSha: String? = null,
        val cpuFlags: String? = null,
    )
}
