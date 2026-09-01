package com.hashtagchow.magehand

import java.io.File

/**
 * `:app`'s shipping Kotlin, as files — the one copy of the walk four test classes used to carry
 * privately (FR-34, docs/design/19-ui-test-infrastructure.md decision 5).
 *
 * ### Why source-reading tests still exist at all
 *
 * FR-34 gave `:app` a Compose harness, so most of what these scans stood in for is now asserted
 * by *rendering* the thing — a rule about what a composable draws is checked by drawing it. What
 * survives is the class of claim a render can never make, because it is about the code rather
 * than about one composition:
 *
 *  - **absence** — "no screen provides a second `LocalDensity`", "the DM view writes no store".
 *    A render proves one path; only a scan proves there is no other one.
 *  - **placement** — "the tab row is composed under the non-expanded branch", which needs the
 *    whole Hilt-wired screen to render and says nothing a golden could show.
 *
 * `WritePostureTest` and `LocalCharacterHomePostureTest` are the same argument one step stronger
 * (bytecode and reflection rather than text); their KDoc records why they were deliberately not
 * converted.
 *
 * ### The walk
 *
 * Gradle runs a unit test with the *module* directory as its working directory, but that is a
 * default rather than a promise and an IDE runner may disagree — so this walks up until the tree
 * resolves, exactly as [walkUpFor] does for `strings.xml`.
 */
fun mainSourceFiles(): List<File> {
    var dir: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
    while (dir != null) {
        val root = File(dir, "src/main/java/com/hashtagchow/magehand")
        if (root.isDirectory) {
            return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        dir = dir.parentFile
    }
    throw AssertionError("could not find :app sources from ${System.getProperty("user.dir")}")
}

/** One named `:app` source file. Fails loudly rather than returning null — a renamed file is a
 *  broken scan, not a passing one. */
fun mainSourceFile(name: String): File = mainSourceFiles().single { it.name == name }

/**
 * A source file with its **comments stripped**, hoisted out of `DmViewUiStateTest`.
 *
 * Every scan that uses this is asserting what the code *does*, and this house writes KDoc that
 * argues its decisions by naming the alternatives it rejected — `DmViewViewModel`'s toggle
 * explains at length why it is not a `SavedStateHandle`. A scan over raw text would fail on that,
 * which would make the honest documentation the thing the test punishes.
 *
 * Deliberately crude — line comments, and block comments matched non-greedily — because the
 * inputs are this app's own Kotlin files and not arbitrary text. A block-comment opener inside a
 * string literal would confuse it; none exists in those files, and a scan that quietly stopped
 * matching anything would be caught by the positive assertion each caller makes alongside its
 * negative ones.
 *
 * (Kotlin block comments **nest**, which is why this paragraph says "block-comment opener" rather
 * than showing one: a literal one here would open a comment inside this KDoc and the file would
 * stop parsing four lines further down.)
 */
fun File.code(): String = readText()
    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    .lines()
    .filterNot { it.trimStart().startsWith("//") }
    .joinToString("\n")

/**
 * A repo-root-relative file (`gradle/libs.versions.toml`), found by the same walk.
 *
 * Hoisted out of `PasswordVisibilityPostureTest`, which reads the catalog to pin that the
 * password reveal did not quietly add an icon dependency.
 */
fun repoFile(path: String): File {
    var dir: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
    while (dir != null) {
        File(dir, path).takeIf { it.isFile }?.let { return it }
        dir = dir.parentFile
    }
    throw AssertionError("could not find $path from ${System.getProperty("user.dir")}")
}
