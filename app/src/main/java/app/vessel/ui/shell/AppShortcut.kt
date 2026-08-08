package app.vessel.ui.shell

import androidx.compose.runtime.Immutable
import app.vessel.core.PeArchitecture

/**
 * A Windows program, as the shell knows one.
 *
 * **A shortcut, not an installation.** Vessel does not track what is installed
 * inside a prefix — Wine has no reliable inventory and neither does Windows. It
 * tracks the handful of executables the user has pointed at, which is the list
 * anybody actually wants on a home screen.
 *
 * Every field except [name] and [arch] is something the user chose. [name] and
 * [arch] are read off the file: the name from the PE version resource, falling
 * back to the file's own stem, and [arch] from `IMAGE_FILE_HEADER.Machine`. That
 * is deliberate — "nothing here is typed twice" is the whole reason the Add sheet
 * has one field.
 *
 * [executable] is a **guest** path (`C:\Program Files\…\x.exe`), not an Android
 * one. The two are related by [app.vessel.data.ContainerLayout.prefix] plus
 * `drive_c`, and the conversion belongs to whatever launches it, not here.
 */
@Immutable
data class AppShortcut(
    /** Stable across renames. Assigned by the registry, not by the caller. */
    val id: String,
    val containerId: String,
    /** `C:\Program Files\Notepad++\notepad++.exe`. */
    val executable: String,
    val name: String,
    val arch: PeArchitecture = PeArchitecture.UNKNOWN,
    /** As typed, passed through to the guest verbatim. Empty is "none". */
    val args: String = "",
    /** Guest path. Empty means the executable's own folder, which is Wine's default. */
    val workingDir: String = "",
) {
    /**
     * The one letter a tile shows when there is no icon.
     *
     * There is no icon yet — extracting one means parsing the PE resource
     * directory and unpacking a `RT_GROUP_ICON`, which is a real piece of work and
     * not a UI one. A letter in a ringed square is honest about being a
     * placeholder in a way that a generic grey application glyph is not: four
     * identical grey glyphs in a row say "these are apps", four different letters
     * say "these are *these* apps".
     */
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    /**
     * The word in the tile's pill: an architecture, or what interprets this.
     *
     * A `.bat`, a `.msi` and a `.vbs` all wore `unknown` here, which was
     * *truthful* — [PeReader] found no machine field because there is no PE
     * header to hold one — and was still the wrong thing to say. Three tiles
     * sitting in a row all badged `unknown` read as three files Vessel had
     * failed to understand, when in fact it knew exactly what each one was and
     * exactly what would run it.
     *
     * `unknown` survives for the case it was written for: a file whose extension
     * says nothing and whose header said nothing either.
     */
    val badge: String
        get() = if (arch != PeArchitecture.UNKNOWN) {
            arch.label
        } else {
            interpreterFor(executable) ?: PeArchitecture.UNKNOWN.label
        }
}
