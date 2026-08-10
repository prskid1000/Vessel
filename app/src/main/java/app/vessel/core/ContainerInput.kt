package app.vessel.core

import kotlinx.serialization.Serializable

/**
 * Which input profile a container starts with, and whether it draws the overlay.
 *
 * A typed defaulted field on [ContainerProfile], exactly as [ContainerDiagnostics]
 * is, and for the same reason: it is not the manifest surface. `params` obeys the
 * manifest's law that a setting is one plain sentence to someone who does not know
 * what a translator is; "which of your named binding tables this container starts
 * with" is a pointer at another document, which no manifest key can be.
 *
 * **The profile itself lives in `input-profiles.json` and only its id is here.**
 * That is what makes the two files fail independently — see [InputProfileDocument]
 * — and it is why this holds a nullable [String] rather than the profile.
 *
 * @property profileId null, or the id of a profile in the input-profile document.
 *   A **stale** id — one naming a profile that has since been deleted — resolves
 *   to the built-in default and **the container is not rewritten**: a profile
 *   being deleted out from under a container is ordinary, not corruption, and
 *   the sheet says so in words rather than silently forgetting.
 * @property touchVisible whether the on-screen overlay is drawn for this
 *   container. Off by default, so a container played with a real pad comes up
 *   clean and an untouched container writes nothing new at all.
 */
@Serializable
data class ContainerInput(
    val profileId: String? = null,
    val touchVisible: Boolean = false,
) {
    /** True when the user has chosen nothing, which is what a fresh container is. */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = ContainerInput()
    }
}
