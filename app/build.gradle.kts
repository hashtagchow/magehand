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
    // FR-34 layer 2: contributes `recordRoborazziDebug` / `verifyRoborazziDebug` /
    // `compareRoborazziDebug`, which are the same `testDebugUnitTest` run with the
    // capture flags set. Without a flag the golden tests are plain unit tests, so a
    // default `./gradlew test` stays machine-independent.
    alias(libs.plugins.roborazzi)
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
        // 18 / 1.9.1 carries FR-27 (configurable pane/tab order) and BUG-4 (phone tab labels
        // wrapping mid-word). The FR row names the pair, so the bump belongs with the build
        // rather than with the upload — see the comment above.
        // 19 / 1.10.0 carries FR-28 (docs/design/17-use-action.md): the action detail sheet and
        // the Use gesture — the first writes this app makes through DiceCloud's own effect
        // machinery, and the first two DDP methods added since FR-9. A MINOR bump rather than a
        // patch because the surface gains a capability, and because it bumps the vendored
        // contract to schema 6 (two new method vectors, a third rate class, four probe quirks) —
        // WebHand pins on that number. **DEPLOY IS HELD** for the operator's word per the
        // design's own operator scope; the bump belongs with the build regardless, because
        // `exportContract` re-runs at every version bump (16 addendum, L5) and the export in this
        // tree is the one this version produced.
        // 20 / 1.11.0 carries the table pack (docs/design/18-table-pack.md): FR-29 local character
        // actions, FR-30 hit dice, FR-31 the concentration prompt, FR-32 the quest log, plus FR-33's
        // password-reveal toggle as a ride-along. A MINOR bump for 1.10.0's reason — four surfaces
        // gain capabilities — and it carries the second **schema change of the app's life this
        // cycle**: Room v6→v7 (`local_tracker_rows.costRowId`/`costAmount`) and contract schema 7
        // (the `hitDice` discovery rule, which WebHand pins on). Neither is optional to the bump:
        // a device that upgrades runs the migration, and a consumer that re-syncs reads the new
        // rule. **DEPLOY IS HELD** for the operator's word per the design's own operator scope;
        // the bump belongs with the build regardless, for the reason two entries up.
        // 22 / 1.12.1 carries BUG-6 (the `CategoryChooser` spoken sentence, unreachable since
        // 1.4.0) plus FR-34 conversion wave 2 (area Q). A **PATCH** and not a minor, per §Versioning
        // — no surface gains a capability, no schema moves, and the contract is untouched, so
        // `exportContract` has nothing new to say. Stated because every entry above it is a MINOR
        // and the run could otherwise look like a convention rather than a rule: 1.10.0 and 1.11.0
        // are minors because surfaces gained capabilities and the contract schema moved; this
        // release changes what a screen reader can reach and what the suite proves, and neither is
        // a capability. (If the operator would rather this went out as 1.13.0, the only change is
        // this line — nothing in the tree derives from the version but the contract export, which
        // re-runs either way.)
        // 24 / 1.13.1 carries the FR-36 **fix wave**: the post-release review's findings 1–7, 9,
        // 11 and 12 (BUG-8's truncated fractional rider and BUG-9's zero rider both fold no
        // more; the chip label is one function instead of two format strings; an amount-less
        // operation is stated rather than dropped; the detail sheet headlines the verbatim base;
        // the rule is finally pinned against the capture). A **PATCH** for 1.12.1's reason and
        // more plainly: no surface gains a capability, no schema moves, the contract is
        // untouched — this is 1.13.0's one row's data, said correctly.
        // 25 / 1.14.0 carries FR-38: the UI-size setting gains 70/80/90% below Default, and its
        // control becomes a wrapping chip row because seven segments do not fit a phone width
        // (14 addendum 3). A **MINOR** and not a patch, and the boundary is worth naming since
        // the last two entries were both patches for "no surface gained a capability": this one
        // did. The setting could only ever make the app bigger; it can now make it smaller, which
        // is a new thing a user can do rather than a correction to an old one — and it writes
        // stored keys (`"70"`/`"80"`/`"90"`) that no earlier build has ever seen, so a rollback
        // reads a value it must degrade. The contract is still untouched.
        // 26 / 1.14.1 carries FR-39 — the tracker's history action moves off the app bar and into
        // the overflow menu on both home screens — with BUG-7 (the five actions-row badges were
        // `AssistChip`s, so a screen reader never heard "Not enough resources") and BUG-11 (two
        // `MenuAnchorType` deprecation warnings, against the house 0-warning standard) riding
        // along. A **PATCH**, and by the boundary the entry above it drew rather than by habit:
        // 1.14.0 was a minor because a setting could do a new thing, and nothing here can. FR-39
        // moves a door a player already had; BUG-7 makes five labels *audible* that were already
        // drawn, which is 1.12.1's case exactly ("changes what a screen reader can reach ... and
        // neither is a capability"); BUG-11 changes no runtime behaviour at all. No schema moves,
        // no stored key is written that an earlier build cannot read, and the contract is
        // untouched — `exportContract` re-runs at the bump and has nothing new to say.
        // Worth one more line because it is the kind of change a ledger should not let pass
        // silently: FR-39 **reverses** 1.9.1's ruling that history belongs on the bar. The
        // supersession, its date and its reason are in `HomeOverflowMenu`'s KDoc, next to the
        // paragraph it supersedes, which is kept.
        // 27 / 1.14.2 carries BUG-10 and BUG-16 with three ride-alongs. BUG-10: a damage
        // `amount.value` published as a JSON number was read through an `Int` reader, so a
        // wrapped `2.5` rendered *"2 radiant"* — a number the sheet never published, on a live
        // path (two of the capture's seventeen damage rows). It now reads the primitive's own
        // characters, the rule the riders already used. BUG-16: a debug build logged the whole
        // `login` exchange, resume token and all, into a logcat that Maestro copies into every
        // sweep flow's output — the frame log now redacts that exchange at the source, before
        // any sink sees it. Riding along: BUG-12's owed snapshot-README rows and its
        // `ExperimentalRoborazziApi` warning, FR-39's app-bar golden (`HomeAppBar` extracted from
        // the screen's `topBar` as a pure move so a picture could exist at all), and FR-40's
        // two cosmetic tooling LOWs. That golden then caught **BUG-17** — at 320 dp × 150 % the
        // back arrow drew under the "S" of "Short" and the title rendered nothing — and on the
        // operator's word the fix rides here too: **FR-43**, a fit rule, draws Short and Long as
        // icon buttons below 284 dp of measured bar width, on both home screens, which returns
        // ~10–12 dp each to the title (48 dp against a 58 dp floor, ~60 dp in practice) — a
        // small saving, and the whole of the difference between no title and a legible one. A **PATCH**, by the boundary 25 drew and 26 applied: no
        // surface gains a capability. BUG-10 corrects what a row already drew; BUG-16 takes the
        // resume token out of the debug frame log AND, after the pre-release review's M2, takes
        // the frame log itself out of the release build entirely — `DdpClientConfig.logger` and
        // `WriteQueueConfig.logger` are nullable now and a release wires `null`, so no frame is
        // redacted, re-encoded or even concatenated when nobody is listening (the old no-op sink
        // still had to be handed a string); the golden and the README are test material, and the
        // tooling changes nothing about a successful run. FR-43 is a patch by the same test and
        // it is worth saying why, since it is the one item here a player will see: the rest
        // buttons **do the same thing in a smaller coat**. Same two actions, same two tags, same
        // `enabled`, and the strings that were their labels are now their content descriptions,
        // so a screen reader speaks the sentence it always spoke. Nothing new can be done; a
        // collision stopped happening. No schema moves and no stored key is
        // written that an earlier build cannot read. `exportContract` re-runs at the bump and
        // its **discovery vectors are byte-identical** — the only change is the manifest's
        // `sourceCommit`/`generatedOn` stamp. Worth stating in as many words, because BUG-10 is
        // a change to what the app reads off the wire: **a corrected reader is not a schema
        // move.** The contract describes the shapes DiceCloud publishes and which of them the
        // engine acts on; both are exactly what they were. What changed is that the app stopped
        // mis-transcribing one of them.
        versionCode = 27
        versionName = "1.14.2"

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
            // FR-34: Robolectric renders the real `strings.xml`/theme, so the merged
            // resources have to be on the unit-test runtime classpath. Without this the
            // Compose tests fail at `Resources.NotFoundException` before asserting
            // anything.
            isIncludeAndroidResources = true
        }
    }
}

// FR-34 layer 2 (design 19 decision 6). Goldens are committed PNGs, so they live in the
// source tree rather than `build/` — `app/src/test/snapshots/`, next to the tests that
// record them. See that directory's README.md for the record/verify workflow.
//
// BUG-12: the images sit one level further down, in `snapshots/img/`, and the README stays
// in `snapshots/`. Gradle treats this whole directory as the record task's output and caches
// it, so anything inside it is restored on a cache hit as the task last produced it — which
// silently reverted the hand-written README after every `clean` build. The images are the
// task's output; the README is not, so the README must live one level ABOVE this directory.
// Point `outputDir` at a directory that contains nothing but goldens and it cannot happen.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/snapshots/img"))
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

    // FR-34 layer 1 (docs/design/19-ui-test-infrastructure.md): Robolectric gives the
    // JVM an Android runtime, and Compose's `ui-test-junit4` gives it a composition to
    // drive — together they are what lets `setContent` + semantics assertions run inside
    // a plain `./gradlew test`, with no emulator and no `androidTest` source set.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The host activity `createComposeRule()` launches. `debugImplementation` rather than
    // `testImplementation` because it works by contributing an `<activity>` to the
    // *variant's* merged manifest, which is what Robolectric reads; it ships no code, and
    // the release variant never sees it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // FR-34 layer 2: goldens captured from that same composition.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
