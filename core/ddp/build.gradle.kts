import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core:ddp is pure Kotlin/JVM — NO Android dependencies. That is what makes the
// DDP client (the highest-risk component) unit-testable with plain JUnit against
// a fake websocket. See docs/design/01-architecture.md and 02-ddp-and-api.md.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // MAGEHAND_IT gates the live-server integration test (docs/design/08-testing.md).
    // Declaring it as an input means flipping it re-runs the task instead of the
    // previous run being reported UP-TO-DATE.
    inputs.property("magehandIt", providers.environmentVariable("MAGEHAND_IT").orElse(""))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    api(project(":core:model"))

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Coroutines is required by the architecture doc's MongoMirror change-Flow API.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
