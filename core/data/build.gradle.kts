import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // See :app — AGP 9 supplies Kotlin itself.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.hashtagchow.magehand.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            // Room's generated code and a few androidx internals touch android.*
            // stubs (Log, Build) that would otherwise throw "not mocked". No WP3
            // test asserts on an android.* return value.
            isReturnDefaultValues = true
        }
    }
}

// Room schema export location. WP3 owns schema v1 — the `accounts` table only;
// WP4 adds characters/snapshots/tracker_prefs/theme_prefs as v2 plus a migration.
// The exported JSON is committed: it is the input to every future migration test.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:ddp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (preferences) — for non-secret app/tracker prefs
    implementation(libs.androidx.datastore.preferences)

    // No androidx.security:security-crypto — WP8 retired it (deprecated in 1.1.0
    // with no replacement). Token encryption is AndroidKeyStore + AES-GCM directly:
    // core/data/.../auth/KeystoreTokenStore.kt.

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Login error paths are proven against a local MockWebServer — never against the
    // live server with real passwords (docs/design/07-build-plan.md, WP3).
    testImplementation(libs.okhttp.mockwebserver)
    // Every androidx.room.Room builder in the Android artifact requires a Context,
    // so Robolectric is what makes real-SQLite DAO tests possible inside a plain
    // `./gradlew test` run — no emulator. See docs/verification/WP3.md.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.tls)
}

// The live integration probe is opt-in. A Gradle *daemon* freezes its environment
// at startup, so `MAGEHAND_IT=1 ./gradlew ...` alone is not reliable — the
// `-PmagehandIt=1` property is the deterministic form and is what
// docs/verification/WP3.md records.
val integrationTestsEnabled: Boolean =
    System.getenv("MAGEHAND_IT") == "1" || providers.gradleProperty("magehandIt").orNull == "1"

tasks.withType<Test>().configureEach {
    // Forwarded explicitly: test JVMs do not inherit the shell environment.
    if (integrationTestsEnabled) environment("MAGEHAND_IT", "1")
    // Lets the live probe read the dev token from docs/dicecloud-api.md — one copy
    // of the secret in the repo, not two.
    systemProperty("magehand.repoRoot", rootDir.absolutePath)
    if (integrationTestsEnabled) {
        // A cached "up-to-date" result would silently skip the live probe.
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}
