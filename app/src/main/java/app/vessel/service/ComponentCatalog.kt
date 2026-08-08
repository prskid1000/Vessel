package app.vessel.service

import android.content.Context
import app.vessel.core.ComponentPackage
import app.vessel.core.ComponentRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the catalogue could tell us, and where it came from. */
sealed interface CatalogResult {

    /** One line for a screen that is about to show an empty list. */
    val summary: String

    data class Loaded(
        val packages: List<ComponentPackage>,
        val rejected: List<ComponentRegistry.RejectedEntry>,
        /**
         * True when the network was not reachable and this is the last registry
         * that was successfully fetched.
         *
         * Surfaced rather than hidden: a cached catalogue can be arbitrarily old
         * and the versions in it may no longer exist as release assets, which
         * turns into a 404 at download time. Better to say "this list is from
         * the last time you were online" than to let that be a mystery later.
         */
        val fromCache: Boolean,
    ) : CatalogResult {
        override val summary: String
            get() = buildString {
                append("${packages.size} component(s) available")
                if (fromCache) append(" (from the last successful refresh)")
                if (rejected.isNotEmpty()) append(", ${rejected.size} refused")
            }
    }

    data class Unavailable(val detail: String) : CatalogResult {
        override val summary get() = detail
    }
}

/**
 * The published component index, fetched and read.
 *
 * Small and separate from [ComponentDownloader] because the two have opposite
 * shapes: this is one short JSON request that should time out quickly and be
 * cached, that is an 88 MB body that must never time out and must resume. One
 * OkHttp client configured for both would be wrong for both.
 *
 * ## It is a 404 today, and that is reported rather than smoothed over
 *
 * `.github/workflows/_component.yml` uploads the `*.wcp` in `dist/` and their `.sha256`
 * sidecars to a rolling `components` release. It never runs
 * `build/gen_registry.py`, so no `contents.json` is published anywhere and
 * [ComponentRegistry.DEFAULT_URL] resolves to nothing. This class therefore
 * answers [CatalogResult.Unavailable] with a sentence naming the missing file,
 * which is the whole difference between "there are no components" and "nobody
 * has published the list yet".
 *
 * The cache is a plain file rather than a DataStore: it holds one document that
 * is replaced wholesale, it has no schema of its own beyond the registry's, and
 * a corrupt copy is fixed by deleting it.
 */
@Singleton
class ComponentCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val cacheFile: File get() = File(context.cacheDir, CACHE_NAME)

    /**
     * Fetch and read the registry, falling back to the last good copy.
     *
     * The fallback is only taken on a *transport* failure. A document that was
     * fetched and is unreadable returns [CatalogResult.Unavailable] with the
     * parser's reason: quietly serving yesterday's list because today's is
     * malformed would hide a broken generator for as long as the cache lasts.
     */
    suspend fun refresh(url: String = ComponentRegistry.DEFAULT_URL): CatalogResult =
        withContext(Dispatchers.IO) {
            val fetched = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    when {
                        response.code == 404 -> return@withContext fallback(
                            "The component registry has not been published yet — there is no " +
                                "contents.json at $url. Components can only be side-loaded until " +
                                "a build publishes one.",
                        )

                        !response.isSuccessful -> return@withContext fallback(
                            "The component registry answered HTTP ${response.code}.",
                        )

                        else -> response.body?.string()
                    }
                }
            } catch (e: IOException) {
                return@withContext fallback(
                    "Could not reach the component registry: ${e.message ?: e.javaClass.simpleName}",
                )
            } ?: return@withContext fallback("The component registry response was empty.")

            when (val parsed = ComponentRegistry.parse(json, fetched)) {
                is ComponentRegistry.Result.Unreadable ->
                    CatalogResult.Unavailable(parsed.summary)

                is ComponentRegistry.Result.Available -> {
                    // Written only after it parsed, so the cache can never hold
                    // a document this build has already proved it cannot read.
                    runCatching { cacheFile.writeText(fetched) }
                    CatalogResult.Loaded(parsed.packages, parsed.rejected, fromCache = false)
                }
            }
        }

    /** The last successfully fetched registry, or null when there has never been one. */
    suspend fun cached(): CatalogResult? = withContext(Dispatchers.IO) { readCache() }

    private fun fallback(why: String): CatalogResult =
        readCache() ?: CatalogResult.Unavailable(why)

    private fun readCache(): CatalogResult.Loaded? {
        val file = cacheFile
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return when (val parsed = ComponentRegistry.parse(json, text)) {
            is ComponentRegistry.Result.Available ->
                CatalogResult.Loaded(parsed.packages, parsed.rejected, fromCache = true)
            // A cache this build cannot read is not a cache. Deleted rather than
            // kept, so the next refresh is not shadowed by it.
            is ComponentRegistry.Result.Unreadable -> {
                file.delete()
                null
            }
        }
    }

    private companion object {
        const val CACHE_NAME = "component-registry.json"
    }
}
