package app.vessel.ui.screens

import androidx.annotation.RawRes
import app.vessel.R

/**
 * What Vessel is made of, and under what terms.
 *
 * **This exists because a licence requires it, and that changes what it may say.**
 * LGPL-2.1 section 6 opens with "You must give prominent notice with each copy of
 * the work that the Library is used in it and that the Library and its use are
 * covered by this License. You must supply a copy of this License." Shipping
 * `license_lgpl_2_1.txt` inside the APK satisfies the second sentence and not the
 * first — a file in a zip is not notice — so this list is the first sentence, and
 * [LicencesScreen] is where it is given.
 *
 * The consequence for anyone editing it: an entry may not be removed because the
 * screen looks long, and a component may not be added to the product without one.
 * The obligation is per copy of the work, and the work is the APK.
 */
data class LicenceEntry(
    /** The component, named as its own authors name it. */
    val title: String,

    /** Who wrote it. Empty for Vessel itself, which the screen says separately. */
    val author: String,

    /** The licence's own short name — `GNU LGPL 2.1`, not `LGPL`. */
    val licence: String,

    /** One line on what it does here, so the list is readable and not a manifest. */
    val role: String,

    /** The text, in the APK. Every entry has one; that is the point. */
    @RawRes val text: Int,

    /** Where the source is, for the entries whose licence asks. */
    val source: String? = null,
)

/**
 * The notice, in the order it matters.
 *
 * The X server is first because it is the entry the obligation is about: it is
 * LGPL code Vessel links, and everything else here either follows from that or is
 * a font.
 */
object Licences {

    /** Where Vessel's own source is. Named in the LGPL notice, so it is one string. */
    const val SOURCE = "https://github.com/prskid1000/Vessel"

    val entries: List<LicenceEntry> = listOf(
        LicenceEntry(
            title = "Winlator X server",
            author = "Bruno Rodrigues and contributors",
            licence = "GNU LGPL 2.1",
            role = "Draws the Windows desktop. Vessel's X server, renderer and " +
                "input plumbing are this project's, carried under com.winlator " +
                "with every local change marked.",
            text = R.raw.license_lgpl_2_1,
            source = "https://github.com/brunodev85/winlator",
        ),
        LicenceEntry(
            title = "Vessel",
            author = "",
            licence = "GNU LGPL 2.1 or later",
            role = "This app. Licensed the same way as the X server it links, " +
                "which is what LGPL 2.1 section 6 asks of a work that combines " +
                "with the Library.",
            text = R.raw.license_lgpl_2_1,
            source = SOURCE,
        ),
        LicenceEntry(
            title = "libadrenotools",
            author = "Billy Laws",
            licence = "BSD 2-Clause",
            role = "Loads the Turnip driver beside Android's own, which is what " +
                "lets a Vulkan driver Vessel ships answer inside the app.",
            text = R.raw.license_bsd_adrenotools,
            source = "https://github.com/bylaws/libadrenotools",
        ),
        LicenceEntry(
            title = "Snapdragon Game Super Resolution",
            author = "Qualcomm Innovation Center, Inc.",
            licence = "BSD 3-Clause",
            role = "Sharpens a game running below the screen's resolution. Its " +
                "fragment shader is carried inside the renderer, which is what " +
                "clause 1 — retain the copyright notice in redistributed source " +
                "— asks be said here.",
            text = R.raw.license_bsd_sgsr,
            source = "https://github.com/SnapdragonStudios/snapdragon-gsr",
        ),
        LicenceEntry(
            title = "Inter",
            author = "Rasmus Andersson",
            licence = "SIL Open Font License 1.1",
            role = "Every word in this interface.",
            text = R.raw.license_ofl_inter,
            source = "https://github.com/rsms/inter",
        ),
        LicenceEntry(
            title = "JetBrains Mono",
            author = "JetBrains",
            licence = "SIL Open Font License 1.1",
            role = "Every number, path and log line.",
            text = R.raw.license_ofl_jetbrains_mono,
            source = "https://github.com/JetBrains/JetBrainsMono",
        ),
    )

    /** The entry a licence route names, or null for an id nothing matches. */
    fun byTitle(title: String): LicenceEntry? = entries.firstOrNull { it.title == title }
}
