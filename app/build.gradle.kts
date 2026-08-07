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

android {
    namespace = "app.vessel"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vessel"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // One shipping ABI. Every native component this app installs is built
        // for a single arm64 target (see build/targets/), so an armeabi-v7a or
        // x86_64 slice could never load anything the app downloads.
        ndk { abiFilters += "arm64-v8a" }

        // Renaming the product is PRODUCT_NAME plus applicationId, and nothing
        // else — no strings.xml to keep in step.
        resValue("string", "app_name", productName)
    }

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
