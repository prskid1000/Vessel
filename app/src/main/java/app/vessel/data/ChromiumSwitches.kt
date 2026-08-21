package app.vessel.data

import java.io.File

/**
 * The switch every Chromium-based program needs here, and the only one.
 *
 * Chromium's Windows sandbox is not something Wine implements: it relies on job
 * objects, restricted tokens and an alternate desktop that have no working
 * equivalent, and a renderer that cannot lower its token does not start. WineHQ
 * has documented `--no-sandbox` as the workaround for CEF and Chromium for
 * years, and it is the only reason a Vessel user currently has to know that
 * their editor is a browser.
 *
 * **Deliberately one switch.** An evening was spent testing thirteen others
 * against a blank Electron window -- occlusion, DirectComposition, GPU and
 * software compositing, in-process GPU, DPI scale, profile state, title bar
 * style, Electron version, window size, renderer backgrounding, timer
 * throttling. Every one of them was excluded by measurement, because the window
 * was blank for two reasons that no Chromium switch can reach: a Wine patch
 * lying to V8 about a reservation, and FEX holding a lock across a pipe read.
 * Both are fixed in the stack where they belonged. Adding any of those switches
 * here would have shipped a workaround for a bug that no longer exists and hidden
 * the next one behind it.
 */
private const val NO_SANDBOX = "--no-sandbox"

/**
 * Files that only a Chromium build ships, used to recognise one.
 *
 * Detection is by payload rather than by name. A name list would have to know
 * about `chrome.exe`, `msedge.exe`, `electron.exe`, `Code - Insiders.exe`,
 * `Discord.exe`, `slack.exe` and whatever ships next, and would still be wrong
 * for a renamed binary -- which Electron applications are, by definition, since
 * `electron.exe` is renamed for every app built on it.
 *
 * `icudtl.dat` alone is too weak: other projects embed ICU. Requiring a V8
 * snapshot beside it is what makes this Chromium specifically, because the
 * snapshot is how Chromium and Electron start a JavaScript context.
 */
private const val ICU_DATA = "icudtl.dat"
private val V8_SNAPSHOTS = listOf("v8_context_snapshot.bin", "snapshot_blob.bin")

/**
 * Switches Vessel adds for [executable], given what the user already asked for.
 *
 * Returned rather than written back into the shortcut. A stored argument is the
 * user's, and rewriting the field would mean they open the shortcut and find
 * text they did not type -- then delete it, and wonder why the program stopped
 * working. This composes at launch and leaves the record alone.
 *
 * Nothing is added if the user has already said it. Chromium tolerates a
 * repeated switch, but a command line that says `--no-sandbox --no-sandbox` in
 * the session log invites exactly the wrong question of whoever reads it next.
 */
internal fun chromiumSwitchesFor(executable: File, userArguments: List<String>): List<String> {
    if (!isChromiumApp(executable)) return emptyList()
    if (userArguments.any { it == NO_SANDBOX }) return emptyList()
    return listOf(NO_SANDBOX)
}

/**
 * Whether [executable] is a Chromium build, judged by what sits beside it.
 *
 * Electron keeps its resources in the same directory as the executable, as do
 * Chrome, Edge and CEF hosts. A version-stamped subdirectory -- which is what
 * VS Code uses -- is checked too, one level down, because that is where the
 * payload lands for an installer that keeps the launcher at the top.
 */
private fun isChromiumApp(executable: File): Boolean {
    val home = executable.parentFile ?: return false
    if (hasChromiumPayload(home)) return true
    // One level of version directory, no deeper: a full walk of a program
    // directory is a lot of disk for a question asked on every launch, and a
    // payload two levels down is not a layout Chromium ships.
    return home.listFiles().orEmpty().any { it.isDirectory && hasChromiumPayload(it) }
}

private fun hasChromiumPayload(directory: File): Boolean {
    if (!File(directory, ICU_DATA).isFile) return false
    return V8_SNAPSHOTS.any { File(directory, it).isFile }
}
