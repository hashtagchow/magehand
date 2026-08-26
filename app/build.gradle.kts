import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No `kotlin.android`: AGP 9 has built-in Kotlin support and hard-fails if the
    // standalone plugin is also applied (https://kotl.in/gradle/agp-built-in-kotlin).
    // The Kotlin version still comes from the catalog, via the compose/serialization
    // plugins on the build classpath.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing config, resolved in this order:
 *
 *  1. environment (`MAGEHAND_KEYSTORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`,
 *     `_KEY_PASSWORD`) — for a machine that does not hold the repo's secrets;
 *  2. `keystore/signing.properties` — the committed default (see keystore/README.md
 *     for the accepted-risk posture that makes committing it a decision, not an
 *     accident);
 *  3. nothing — the release build still assembles, just unsigned, so a fresh clone
 *     without the keystore is not a broken checkout.
 */
val signingProps: Properties? = run {
    val fromEnv = System.getenv("MAGEHAND_KEYSTORE_FILE")
    if (fromEnv != null) {
        Properties().apply {
            setProperty("storeFile", fromEnv)
            setProperty("storePassword", System.getenv("MAGEHAND_KEYSTORE_STORE_PASSWORD") ?: "")
            setProperty("keyAlias", System.getenv("MAGEHAND_KEYSTORE_KEY_ALIAS") ?: "magehand-upload")
            setProperty("keyPassword", System.getenv("MAGEHAND_KEYSTORE_KEY_PASSWORD") ?: "")
        }
    } else {
        val file = rootProject.file("keystore/signing.properties")
        if (file.exists()) Properties().apply { file.inputStream().use(::load) } else null
    }
}

/** Absolute, because `storeFile` in the properties file is relative to `keystore/`. */
val signingKeystoreFile: File? = signingProps?.getProperty("storeFile")?.let { path ->
    File(path).takeIf { it.isAbsolute } ?: rootProject.file("keystore/$path")
}

android {
    namespace = "com.hashtagchow.magehand"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hashtagchow.magehand"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // M3 table-ready exit (docs/design/08-testing-and-release.md §"Versioning"):
        // 0.<milestone>.<patch> until the app is usable at the table, then 1.0.0.
        // versionCode 2 leaves 1 spent on the WP1..WP7 debug sideloads, so a device
        // that has one installed will accept this as an update. 2 shipped to Play as
        // 1.0.0 (first published release); Play rejects any reused versionCode.
        // 3 shipped as 1.0.1. Both are live on Play, so both codes are spent for good —
        // a bump is part of preparing a release, not a step to remember at upload time.
        // 4 / 1.0.2 carries the WP8 pre-release review fixes.
        versionCode = 16
        versionName = "1.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingKeystoreFile?.exists() == true) {
            create("release") {
                storeFile = signingKeystoreFile
                storePassword = signingProps!!.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // v1 is what lets an APK install on API 24-; minSdk is 31, so the
                // modern schemes are the only ones that can matter. v4 needs an
                // .idsig sidecar that only `adb install --incremental` consumes.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix on purpose: 00-DESIGN.md is "sideload first",
            // so the debug APK the table installs must carry the real
            // applicationId (it is also the build WP5/WP7 probe against the live
            // server).
            versionNameSuffix = "-debug"
        }
        release {
            // WP8 turns R8 on. `isShrinkResources` needs `isMinifyEnabled`; together
            // they are what makes the release APK a third the size of the debug one.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9: the Kotlin compiler options live inside `android { }` now.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates WebView contents debugging and the debug-only
        // account seeder (docs/verification/WP5.md §5). AGP 8 needs this opt-in.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Same reason as `:core:data`: a few androidx internals reach android.*
            // stubs (Log, Build) on the way past. No `:app` test asserts on an
            // android.* return value.
            isReturnDefaultValues = true
        }
    }
}

// WP1 disabled Hilt's aggregating task because Hilt 2.58's bundled
// kotlin-metadata-jvm could not read the Kotlin 2.4 @Metadata on some AndroidX
// artifacts. Hilt 2.60.1 reads it, so the default (enabled) is back — which also
// restores KSP incrementality. See docs/verification/WP8.md §1.

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose — versions come from the BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // FR-17's window-size gate. Outside the BOM — see the catalog comment.
    implementation(libs.androidx.compose.material3.adaptive)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Portraits
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // WP6 adds the first `:app` unit tests: the tracker's board→UI mapping and the
    // status-strip derivation. `SavedStateHandle` needs the savedstate artifact, and the
    // ViewModel test drives `viewModelScope` through `Dispatchers.setMain`.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.lifecycle.viewmodel.savedstate)
}
