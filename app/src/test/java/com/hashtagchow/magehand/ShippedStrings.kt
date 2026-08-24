package com.hashtagchow.magehand

import java.io.File

/**
 * The resources this module ships, read straight off disk.
 *
 * `:app` has no Robolectric harness, so a copy test cannot ask `Resources` what a string says —
 * it reads the shipping `strings.xml` itself instead. These helpers are the one place that
 * lookup lives, so every such test finds the same file the APK will carry.
 */

/**
 * `app/src/main/res/values/strings.xml`, found by walking up from the working directory.
 *
 * Gradle runs a unit test with the *module* directory as its working directory, but that is
 * a default rather than a promise and an IDE runner may disagree. Walking up until the path
 * resolves makes a test say what it means — "the strings file this module ships" — instead
 * of encoding one runner's convention.
 */
fun stringsXml(): File = walkUpFor(
    "app/src/main/res/values/strings.xml",
    "src/main/res/values/strings.xml",
)

/**
 * The declared value of one `<string>` in the file that ships.
 *
 * Fails rather than returning `null` for a missing name: every caller is asserting what a
 * resource *says*, and a resource that does not exist is a `Resources.NotFoundException` at
 * runtime — a louder failure than the value being wrong, so it earns the louder message.
 */
fun declaredString(name: String): String =
    Regex("""<string name="$name">(.*?)</string>""")
        .find(stringsXml().readText())
        ?.groupValues
        ?.get(1)
        ?: throw AssertionError("no <string name=\"$name\"> in ${stringsXml()}")

/** [fromRoot] resolved by the same walk, for shipping files beside the strings file. */
fun walkUpFor(fromRoot: String, fromModule: String): File {
    // `user.dir` is set by the JVM itself, so the null in getProperty's Java signature cannot
    // happen here — checkNotNull records that instead of silently walking from a default.
    var dir: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
    while (dir != null) {
        File(dir, fromRoot).takeIf { it.isFile }?.let { return it }
        // Also the case where the working directory already *is* `app/`.
        File(dir, fromModule).takeIf { it.isFile }?.let { return it }
        dir = dir.parentFile
    }
    throw AssertionError("could not find $fromRoot from ${System.getProperty("user.dir")}")
}
