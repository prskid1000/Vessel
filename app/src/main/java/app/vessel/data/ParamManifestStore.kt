package app.vessel.data

import android.content.Context
import app.vessel.core.params.ParamManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `assets/params-manifest.json`, read once.
 *
 * The manifest ships in the APK, so a read failure means the build is broken
 * rather than the device is — but the editor still has to say something, so the
 * failure is returned rather than thrown and the screen renders the message
 * instead of a blank list.
 */
@Singleton
class ParamManifestStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    @Volatile
    private var cached: ParamManifest? = null

    suspend fun load(): Result<ParamManifest> {
        cached?.let { return Result.success(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                json.decodeFromString(ParamManifest.serializer(), text).also { cached = it }
            }
        }
    }

    private companion object {
        const val ASSET = "params-manifest.json"
    }
}
