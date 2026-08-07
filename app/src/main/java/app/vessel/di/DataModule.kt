package app.vessel.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.vessel.data.CONTAINERS_FILE
import app.vessel.data.ContainerDocument
import app.vessel.data.ContainerDocumentSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
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
     * It is also what makes removing a field from a persisted model safe.
     * `ContainerProfile.archProfile` is gone — Box64 went with it and there is
     * one kind of container now — and every `containers.json` written before
     * that still carries the key. With this off, opening the app on such a
     * device would throw on the first read and be reset to an empty list by the
     * corruption handler; with it on, the container simply loads without it.
     *
     * `prettyPrint` costs nothing at this size and makes the container document
     * something a person can read over adb when a bug report says the list came
     * back empty.
     */
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

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
        corruptionHandler = ReplaceFileCorruptionHandler { ContainerDocument() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile(CONTAINERS_FILE) },
    )
}
