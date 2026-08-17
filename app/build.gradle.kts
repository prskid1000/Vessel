import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val productName: String = providers.gradleProperty("PRODUCT_NAME").getOrElse("Vessel")

/**
 * The APK's bill of materials: exactly which `.wcp` builds the `sideload`
 * flavour carries.
 *
 * **Named rather than globbed, and the reason is in `dist/` itself.** That
 * directory accumulates every package the build scripts have ever produced,
 * including superseded ones — `wine-10.13-canoe.wcp` sits beside
 * `wine-11.14-canoe.wcp` — and a wildcard would ship both: 66 MB of APK and
 * 900 MB of first-run unpacking for a Wine no container will ever adopt, because
 * `ComponentStore.adoptLatest` takes the highest `versionCode`.
 *
 * Bumping a component is therefore a one-line edit here, which is the right
 * amount of friction for changing what is inside the package a user installs.
 */
val bundledPackages = listOf(
    // Valve's experimental_11.0, not upstream Wine and not proton_11.0 — see
    // native/pins.env for which 461 commits made that the choice. It packages
    // under the Proton epoch, so its versionCode outranks any plain-Wine build
    // and `wine-11.14-canoe.wcp` beside it in dist/ can never be adopted again.
    "wine-proton-exp-11.0-canoe.wcp",
    "fex-2608-canoe.wcp",
    "dxvk-2.7.1-canoe.wcp",
    "vkd3d-3.0.1-canoe.wcp",
    "zink-26.3.0-devel-9c475fc3-canoe.wcp",
    // The ICD build of Turnip, and not the HAL build beside it in `dist/`. Only
    // the ICD can present to a window: the Android platform loader the HAL has
    // to be driven through keeps the WSI surface layer for itself and knows only
    // surfaces it made for an ANativeWindow, so a swapchain on Wine's X11 window
    // faults in the loader. docs/GRAPHICS.md has the whole story.
    //
    // **The "ICD outranks the HAL anyway" reasoning that used to be here is
    // gone, because it stopped being true.** It read: shipping the HAL too
    // would cost 2 MB for a driver nothing would choose, since `adoptLatest`
    // takes the highest versionCode and the ICD is 260301 against its 260300.
    // That +1 is real (`build/turnip.sh` adds it for the `-icd` suffix) but it
    // is smaller than one Vessel patch revision, which the same expression adds
    // as `2 * rev`. At TURNIP_REVISION=2 the HAL is 260304 and the ICD 260305,
    // so a HAL built one revision ahead of the ICD outranks it and would be
    // adopted silently — reversing this decision with no error anywhere. That
    // nearly happened: revision 2's HAL was built, staged on a device, and only
    // caught by reading this list. Do not rely on the ordering; rely on the name
    // below, which is the whole reason the bill of materials is named and not
    // globbed.
    //
    // `build/turnip.sh` still builds the HAL by default (`VESSEL_TURNIP_ICD=1`
    // selects the ICD); both `GpuProbe` and `patches/wine/0009` handle either
    // shape by asking the file which it is, so installing one by hand works.
    "turnip-26.3.0-devel-9c475fc3-icd-canoe.wcp",
    // Git, Python, Node, PowerShell 7 and the Temurin JDK in one payload, all
    // x86-64 under FEX — see docs/DEVTOOLS.md for why x64 rather than ARM64 (the
    // wheel and prebuilt ecosystems) and why zips rather than installers
    // (TODO #17's open .msi failure). Bundled rather than side-loaded because
    // installing the APK is meant to be the whole of setup, and a developer
    // toolchain that needs a manual pick is one nobody has.
    //
    // **The version in this name is load-bearing and this line has to move with
    // it.** `WcpInstaller.kt:290-305` will not unpack a package whose
    // type+versionCode the store already holds, so a payload that grows without
    // a version bump installs nothing; `native/pins.env` has the note. The
    // corollary is here: dist/ still holds whatever was built before, so leaving
    // the old name in this list ships a package the new one supersedes.
    //
    // 1.1.0 adds PowerShell (~101 MiB of archive) and the JDK (~196 MiB), which
    // is roughly a tripling of the component and makes it by a wide margin the
    // largest thing in the APK. Measured from the artifacts themselves:
    // the .wcp is 360.7 MiB against 1.0.0's 126.2 MiB, and the APK goes from
    // 261 MiB to 495.5 MiB. That is the deliberate trade, and it is felt on
    // every download, every install and every first-run unpack; drop this line
    // to take all of it back.
    //
    // (An earlier revision of this comment said 425 MiB and 566 MiB and called
    // them measured. They were not — the numbers were written before the
    // package existed. Both are now read off `ls -l dist/` and the built APK.)
    "tools-1.1.0-x64.wcp",
)

/** Copies the bill of materials into a generated assets root. */
abstract class BundleComponentsTask : DefaultTask() {

    /** The `.wcp` files named by the bill of materials that actually exist. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packages: ConfigurableFileCollection

    /** Every name asked for, so a missing one can be reported by name. */
    @get:Input
    abstract val expected: ListProperty<String>

    /**
     * An assets root. The packages go in a `components/` directory inside it,
     * because that is the asset path `AssetWcpSource.listAll` reads.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val destination = outputDirectory.get().asFile.resolve("components")
        // Cleared rather than merged, so dropping a name from the bill of
        // materials drops the package from the APK instead of leaving a stale one
        // behind that the first run would then dutifully install.
        destination.deleteRecursively()
        destination.mkdirs()

        val staged = packages.files.filter { it.isFile }
        staged.forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }

        val names = staged.map { it.name }.toSet()
        expected.get().filterNot { it in names }.forEach {
            logger.warn("bundleComponents: dist/$it is missing; it will not be in the APK")
        }
    }
}

/**
 * Stage [bundledPackages] where the `sideload` flavour picks them up as assets.
 *
 * A missing package is a warning and not an error. A fresh clone has no `dist/`
 * at all — the packages are built by the scripts under `build/` in Docker, which
 * is a much longer road than `./gradlew assembleSideloadDebug` — and refusing to
 * build would mean the app could not be compiled without them. The resulting APK
 * simply bundles nothing, which is the state the `play` flavour is permanently in
 * and which the app already handles: the setup dialog does not appear and the
 * download path is the only source. The warning is what stops that being a
 * surprise at install time.
 */
val bundleComponents = tasks.register<BundleComponentsTask>("bundleComponents") {
    description = "Stages the bundled .wcp components into the sideload flavour's assets."
    val dist = rootProject.layout.projectDirectory.dir("dist")
    packages.from(bundledPackages.map { dist.file(it) })
    expected.set(bundledPackages)
    outputDirectory.set(layout.buildDirectory.dir("generated/bundledComponents"))
}

android {
    namespace = "app.vessel"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vessel"
        minSdk = 31
        targetSdk = 36
        versionCode = 10
        versionName = "0.4.6"

        // One shipping ABI. Every native component this app installs is built
        // for a single arm64 target (see build/targets/), so an armeabi-v7a or
        // x86_64 slice could never load anything the app downloads.
        ndk { abiFilters += "arm64-v8a" }

        // Renaming the product is PRODUCT_NAME plus applicationId, and nothing
        // else — no strings.xml to keep in step.
        resValue("string", "app_name", productName)

        // Three native libraries ship in the APK:
        //   libwinlator   the JNI half of the vendored X server (epoll connector,
        //                 wire-format streams, software blitter, AHardwareBuffer
        //                 import) — app/src/main/java/com/winlator/README.md
        //   libadrenotools + its hook objects, which is what lets a Vulkan driver
        //                 in app storage be loaded at all —
        //                 app/src/main/cpp/adrenotools/README.md
        //   libvesselgpu  the Vulkan driver probe behind GpuProbe
        //
        // ANDROID_STL was `none` while libwinlator was the only one and was pure
        // C. c++_shared now, and specifically *shared* rather than static, for
        // two reasons that are both runtime correctness rather than size:
        //
        //  - libadrenotools hands libhook_impl a struct containing std::strings
        //    across an .so boundary, so the two have to share one libc++.
        //  - libadrenotools makes nativeLibraryDir the default library path of
        //    the linker namespace it loads the GPU driver into, and Turnip's
        //    NEEDED list contains libc++_shared.so. With c++_static there is no
        //    such file in that directory, the driver dlopen fails, and the stock
        //    Qualcomm driver answers instead — silently, which is the exact
        //    failure this whole path exists to end.
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Pinned rather than left to AGP's default so a toolchain bump cannot
    // silently change the compiler that builds libwinlator. Gradle downloads
    // this NDK if the machine does not have it.
    ndkVersion = "27.0.12077973"

    // Two channels from one source, as in the reference app: `sideload` may
    // download and install .wcp components itself; `play` cannot, because Play
    // policy forbids shipping executable code outside the package.
    flavorDimensions += "channel"
    productFlavors {
        create("sideload") {
            dimension = "channel"
            buildConfigField("String", "UPDATE_CHANNEL", "\"SIDELOAD\"")
            buildConfigField("boolean", "CAN_INSTALL_COMPONENTS", "true")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("String", "UPDATE_CHANNEL", "\"PLAY\"")
            buildConfigField("boolean", "CAN_INSTALL_COMPONENTS", "false")
        }
    }

    // `sideload` carries the component packages inside the APK, so that
    // installing the APK is the whole of setup: no side-loading, no downloads,
    // no first-run network. `play` carries none and keeps the download path,
    // because Play forbids executable code outside the package and every one of
    // these is executable code.
    //
    // The packages are *copied in by the build* from `dist/` rather than
    // committed under `app/src/sideload/assets/`. They are build outputs — 100 MB
    // of them, regenerated by `build/*.sh` — and `/dist/` is gitignored precisely
    // so they never enter history. Registering the task's output directory as an
    // asset source gives AGP the dependency for free, so `assembleSideloadDebug`
    // syncs them first and a package added to `dist/` needs no edit here.



    androidResources {
        // A .wcp is already xz. Deflating it into the APK saves nothing, costs
        // build time, and - the part that matters - makes the asset unreadable
        // through openFd, which is how AssetWcpSource learns a package's size,
        // and forces the platform to inflate 88 MB just to read the first tar
        // entry. See AssetWcpSource.
        noCompress += "wcp"
    }

    // The release key is never in the repository. It comes from
    // `keystore.properties` at the repo root, or from the environment on a
    // build machine with no checkout-local file. If neither exists the release
    // build still runs and produces an unsigned APK, so a fresh clone can
    // verify R8 and the shrinker without holding a signing key — which is the
    // part of a release build that ordinary changes actually break.
    val keystoreProperties = Properties()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        FileInputStream(file).use { keystoreProperties.load(it) }
    }

    fun secret(key: String, env: String): String? =
        keystoreProperties.getProperty(key) ?: System.getenv(env)

    // Resolved against the repo root, not this module. `file(...)` inside an
    // `android {}` block is relative to app/, so a `storeFile=release.jks`
    // sitting beside keystore.properties would silently not exist and the
    // release would come out unsigned — with no error, because an unsigned
    // release is a legitimate thing to produce. Learned the hard way in the
    // sibling project; encoded here so it is not learned twice.
    val releaseStore = secret("storeFile", "ANDROID_KEYSTORE")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = secret("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = secret("keyPassword", "ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false

            // Sign debug with the release key when one is available. Debug and
            // release carry the same applicationId, so with different keys the
            // only way to swap builds on a phone is to uninstall — taking every
            // container and its multi-gigabyte Wine prefix with it. Falls back
            // to the debug key so a fresh clone still builds.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

        // REQUIRED by libadrenotools, and it is not a preference.
        //
        // With `false` (AGP's default for release since 4.2) the .so files stay
        // compressed inside the APK and are mapped from it directly, so
        // `applicationInfo.nativeLibraryDir` names a directory that does not
        // exist on disk. libadrenotools loads libmain_hook.so and libhook_impl.so
        // from that path by name, and makes it the default library path of the
        // namespace it loads the GPU driver into. Both fail, and the failure mode
        // is the stock Vulkan driver quietly answering — upstream's own header
        // calls this out in capitals.
        //
        // Cost: a larger install footprint, and no page-sharing with the APK for
        // these libraries. Against a 912 MB Wine tree it does not register.
        jniLibs.useLegacyPackaging = true
    }
}

// The sideload flavour carries the component packages inside the APK, so that
// installing the APK is the whole of setup: no side-loading, no downloads, no
// first-run network. The play flavour carries none and keeps the download path,
// because Play forbids executable code outside the package and every one of
// these is executable code.
//
// Wired through the variant API rather than by adding an asset `srcDir`. A
// generated directory handed to `sourceSets.assets.srcDir(...)` carries no task
// dependency, even as a Provider: the build succeeds, the merge runs before the
// generator, and the APK comes out 32 MB with the assets silently absent — which
// is exactly the kind of quiet nothing this feature exists to stop.
// `addGeneratedSourceDirectory` is the supported form and wires ordering for
// every variant of the flavour.
androidComponents {
    onVariants(selector().withFlavor("channel" to "sideload")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            bundleComponents,
            BundleComponentsTask::outputDirectory,
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Decodes the .wcp packages this app installs. See WcpArchive.
    implementation(libs.xz)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
