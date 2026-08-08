package app.vessel.data

import android.content.Context
import app.vessel.core.ComponentType
import app.vessel.core.WcpProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One `.wcp` shipped inside the APK, and what its own manifest says it is.
 *
 * [profile] is null when the archive could not be read at all. That is kept as a
 * state rather than dropped from the catalogue, because a bundle with an
 * unreadable package in it is a build mistake and the setup report is where it
 * should surface — silently installing five of six and calling it done is the
 * failure mode this whole path exists to avoid.
 */
data class BundledPackage(
    val source: WcpSource,
    /** The registry's id for the build, which is the file name without `.wcp`. */
    val packageId: String,
    val profile: WcpProfile?,
) {
    val type: ComponentType? get() = profile?.componentType

    /** What the setup checklist calls this row. */
    val label: String
        get() {
            val type = type ?: return packageId
            val version = profile?.versionName
            return if (version.isNullOrBlank()) type.label else "${type.label} $version"
        }
}

/**
 * The component packages carried inside the APK.
 *
 * **The `sideload` flavour ships all six; the `play` flavour ships none.** That
 * is the whole of the difference and it is expressed as assets rather than as
 * code: Play policy forbids executable code outside the package, and these
 * packages are nothing but executable code, so `app/src/play` has no
 * `assets/components` and this class finds an empty list there. Nothing checks a
 * `BuildConfig` flag, because an empty catalogue already produces exactly the
 * right behaviour — no setup, and the download path as the only source.
 *
 * The catalogue is derived from the assets themselves rather than from a list
 * checked in beside them. A generated index would be a second statement of what
 * is in the APK, and the two would disagree the first time a package was added
 * without regenerating it.
 */
@Singleton
class BundledComponents @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ComponentStore,
    private val installer: WcpInstaller,
) {

    /**
     * Every bundled package, largest first.
     *
     * Largest first because Wine is 88 MB of the bundle's 100 and every other
     * component is useless without it: if there is not room for all of it, that
     * is worth discovering in the first thirty seconds rather than after five
     * successful installs. It also puts the long wait at the front, where the
     * progress bar is moving through the part of the work that dominates the
     * total, instead of leaving the bar apparently finished with minutes to run.
     */
    suspend fun catalogue(): List<BundledPackage> =
        AssetWcpSource.listAll(context.assets)
            .sortedByDescending { it.sizeBytes }
            .map { source ->
                BundledPackage(
                    source = source,
                    packageId = source.packageId(),
                    profile = installer.profileOf(source),
                )
            }

    /**
     * Whether the shared store already holds this exact build.
     *
     * Keyed by type *and* `versionCode`, which is the store's own key and the
     * thing a container's `provisioned.json` references — so "already installed"
     * here means the same thing it means everywhere else, and a bundled package
     * whose version has moved on installs alongside the old one rather than over
     * it.
     *
     * Read from the filesystem on every call. A flag in preferences would answer
     * faster and would be wrong the moment a user cleared the app's storage, and
     * the answer decides whether ~1 GB of unpacking happens.
     */
    fun isInstalled(bundled: BundledPackage): Boolean {
        val type = bundled.type ?: return false
        val versionCode = bundled.profile?.versionCode ?: return false
        return store.layout.isInstalled(type, versionCode)
    }
}
