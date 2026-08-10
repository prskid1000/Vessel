package app.vessel.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.vessel.data.CONTAINERS_FILE
import app.vessel.data.SHORTCUTS_FILE
import app.vessel.data.AppShortcutDocument
import app.vessel.data.AppShortcutDocumentSerializer
import app.vessel.data.ContainerDocument
import app.vessel.data.ContainerDocumentSerializer
import app.vessel.data.INPUT_PROFILES_FILE
import app.vessel.data.InputProfileDocument
import app.vessel.data.InputProfileDocumentSerializer
import app.vessel.core.SessionDisplayServer
import app.vessel.data.ContainerPaths
import app.vessel.data.FreeSpace
import app.vessel.display.XServerDisplay
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * One reader for the whole app.
     *
     * `ignoreUnknownKeys` is not laxness: `params-manifest.json` carries
     * `_comment` and `_note` prose addressed to whoever edits it, and a `.wcp`
     * `profile.json` is a format shared with the wider Winlator ecosystem, so a
     * package from another producer must not fail to read because it carries a
     * key this app has never heard of.
     *
     * It is also what makes removing a field from a persisted model safe. A
     * `containers.json` still carrying a since-deleted key would otherwise throw
     * on first read and be reset to an empty list by the corruption handler.
     *
     * `prettyPrint` costs nothing at this size and makes the container document
     * readable over adb when a bug report says the list came back empty.
     */
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * The on-disk layout, rooted at the app's private files directory.
     *
     * Provided rather than `@Inject`-constructed so [ContainerPaths] itself takes
     * a `File` and knows nothing about Android — the layout rules are then
     * testable against a temporary directory, which is the only way the path
     * traversal guard in [app.vessel.data.WcpInstaller] can be exercised at all.
     */
    @Provides
    @Singleton
    fun containerPaths(@ApplicationContext context: Context): ContainerPaths =
        ContainerPaths(context.filesDir)

    /**
     * How much room is left on the filesystem a component is unpacking into.
     *
     * Bound rather than constructed inside [app.vessel.data.WcpInstaller] so a
     * test can answer "400 MB" — there is no way to make a real filesystem small,
     * and refusing a 912 MB extract that will not fit is the behaviour under
     * test.
     */
    @Provides
    @Singleton
    fun freeSpace(): FreeSpace = FreeSpace.Filesystem

    /**
     * The X server a session draws into.
     *
     * [XServerDisplay] is the adapter over the vendored `com.winlator` server and
     * the only file in this project that imports from that package — the seam
     * exists so swapping it is this one line. [SessionDisplayServer.Absent] is
     * still a working configuration and is what to bind here to run a session
     * headless: a Wine failure that happens before any window exists reads far
     * better without a compositor in the way.
     */
    @Provides
    @Singleton
    fun displayServer(server: XServerDisplay): SessionDisplayServer = server

    /**
     * The container document.
     *
     * The corruption handler replaces an unreadable file with an empty document
     * rather than letting the exception reach the home screen: losing the list is
     * bad, and an app that will not open is worse.
     */
    @Provides
    @Singleton
    fun containerStore(
        @ApplicationContext context: Context,
        json: Json,
    ): DataStore<ContainerDocument> = DataStoreFactory.create(
        serializer = ContainerDocumentSerializer(json),
        corruptionHandler = ReplaceFileCorruptionHandler {
            preserveCorruptFile(context, CONTAINERS_FILE, it)
            ContainerDocument()
        },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile(CONTAINERS_FILE) },
    )

    /**
     * The app-shortcut document.
     *
     * Its own file rather than a field on the container document, because the
     * two have different failure modes: losing the tiles should not cost anyone
     * their containers, and the reverse would be much worse. A shortcut is a
     * pointer — every program it named is still in the prefix.
     */
    @Provides
    @Singleton
    fun appShortcutStore(
        @ApplicationContext context: Context,
        json: Json,
    ): DataStore<AppShortcutDocument> = DataStoreFactory.create(
        serializer = AppShortcutDocumentSerializer(json),
        corruptionHandler = ReplaceFileCorruptionHandler {
            preserveCorruptFile(context, SHORTCUTS_FILE, it)
            AppShortcutDocument()
        },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile(SHORTCUTS_FILE) },
    )

    /**
     * The input-profile document.
     *
     * A third file rather than a field on either of the other two, and the
     * argument is the one already made for `shortcuts.json`: separate failure
     * domains. Losing your bindings must not cost you your containers, and the
     * reverse would be much worse. It is also the file an older build never
     * opens — so the profiles survive a downgrade intact even though the *link*
     * from a container to one does not.
     */
    @Provides
    @Singleton
    fun inputProfileStore(
        @ApplicationContext context: Context,
        json: Json,
    ): DataStore<InputProfileDocument> = DataStoreFactory.create(
        serializer = InputProfileDocumentSerializer(json),
        corruptionHandler = ReplaceFileCorruptionHandler {
            preserveCorruptFile(context, INPUT_PROFILES_FILE, it)
            InputProfileDocument()
        },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile(INPUT_PROFILES_FILE) },
    )

    /**
     * Keep the bytes, and say so, before a corruption handler throws them away.
     *
     * **`ReplaceFileCorruptionHandler` deletes the user's data and leaves no
     * evidence at all.** That was the arrangement here: an unreadable
     * `shortcuts.json` became an empty one, silently, and the only way anyone would
     * learn of it is by noticing their programs had gone. It happened on this
     * device on 2026-08-09 — two shortcuts, a 47-byte empty document written while
     * the app sat idle, and nothing anywhere saying why. The cause is still unknown
     * and that is the point: there was nothing left to diagnose it with.
     *
     * So the file is moved aside rather than overwritten, and the event is logged at
     * ERROR with the reason the serializer gave. Replacing is still the right
     * behaviour — an app that cannot open is worse than one that has forgotten your
     * tiles — but it must be recoverable and it must be provable.
     *
     * `.corrupt` and not a timestamped series: one copy is enough to diagnose from,
     * and a directory that grows a file per failure is its own problem. A second
     * failure overwrites the first, which is the right trade — the most recent one
     * is the one somebody is looking into.
     *
     * Best-effort throughout. This runs while DataStore is recovering, and throwing
     * from here would turn a recoverable read into the crash the handler exists to
     * prevent.
     */
    private fun preserveCorruptFile(context: Context, name: String, cause: Throwable) {
        runCatching {
            val file = context.dataStoreFile(name)
            val kept = File(file.parentFile, "$name.corrupt")
            val bytes = if (file.isFile) file.length() else 0L
            if (file.isFile) {
                kept.delete()
                if (!file.renameTo(kept)) file.copyTo(kept, overwrite = true)
            }
            Log.e(
                "VesselData",
                "$name could not be read and has been replaced with an empty one. " +
                    "The previous $bytes bytes are at ${kept.absolutePath}. " +
                    "Cause: ${cause.message}",
                cause,
            )
        }
    }

    /**
     * The provisioner's narrow view of Android storage. See [PrefixDrives].
     */
    @Provides
    @Singleton
    fun prefixDrives(impl: app.vessel.data.AndroidDrives): app.vessel.data.PrefixDrives = impl
}
