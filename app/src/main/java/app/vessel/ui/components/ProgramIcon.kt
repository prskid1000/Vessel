package app.vessel.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import app.vessel.data.ProgramIcons
import app.vessel.ui.shell.AppShortcut
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * [shortcut]'s own icon once it has been read, null until then and null if it
 * has none.
 *
 * **Reached through an entry point rather than a view model, and that is a
 * deliberate exception.** [VAppTile] is drawn from two places — the container
 * card on home and the launcher over a running desktop — whose view models have
 * nothing else to do with icons. Threading a bitmap through both would put a
 * decode cache into two screens that do not care about one, to save this file
 * from knowing that Hilt exists. The tile asks for its own icon instead.
 *
 * Null while the read is in flight, so the lettered placeholder is what shows
 * first and the icon replaces it. No spinner: a tile that flickers a progress
 * indicator for forty milliseconds is worse than one that changes.
 */
@Composable
fun rememberProgramIcon(shortcut: AppShortcut?): ImageBitmap? {
    // A preview has no Hilt application behind it, and asking would throw.
    if (LocalInspectionMode.current) return null

    val context = LocalContext.current
    val icons = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ProgramIconsEntryPoint::class.java)
            .programIcons()
    }

    // Keyed on the path rather than the id: renaming a shortcut does not change
    // its icon, and repointing one at a different file does. Nullable because
    // the taskbar asks about a window it may not have matched to a shortcut, and
    // a caller that has to branch around this would be calling a composable in
    // one arm of an `if` — legal, and worse than taking a null here.
    var icon by remember(shortcut?.executable) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shortcut?.executable) {
        icon = shortcut?.let { icons.iconFor(it)?.asImageBitmap() }
    }
    return icon
}

/**
 * The icon of a program Wine itself provides, for the start menu's built-in row.
 *
 * Null until read, null without a running container, and null for a program
 * whose icon cannot be extracted — the caller keeps its glyph for all three.
 */
@Composable
fun rememberBuiltInIcon(containerId: String?, program: String): ImageBitmap? {
    if (LocalInspectionMode.current) return null

    val context = LocalContext.current
    val icons = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ProgramIconsEntryPoint::class.java)
            .programIcons()
    }

    var icon by remember(containerId, program) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(containerId, program) {
        icon = containerId
            ?.takeIf { it.isNotBlank() }
            ?.let { icons.iconForBuiltIn(it, program)?.asImageBitmap() }
    }
    return icon
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ProgramIconsEntryPoint {
    fun programIcons(): ProgramIcons
}
