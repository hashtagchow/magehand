# R8 rules for :app (WP8 — minification is ON for release).
#
# The starting position is that almost nothing belongs here: OkHttp, Room, Hilt,
# Coil and kotlinx.serialization all ship their own rules inside their artifacts
# and R8 reads them. What is left is the reflection this app does that no library
# can know about, plus one deliberate hardening rule.
#
# Every rule below was kept because removing it broke something observable, or is
# argued for in place. See docs/verification/WP8.md §4.

# ---------------------------------------------------------------------------
# Navigation-Compose type-safe routes
# ---------------------------------------------------------------------------
# `NavHost` serializes/deserializes the @Serializable destination objects in
# ui/navigation/Destinations.kt. kotlinx.serialization's own consumer rules cover
# the generated $$serializer classes, but they key off the annotation surviving,
# and R8 strips annotations from otherwise-unreferenced classes. The `data object`
# destinations have no members at all, which makes them the easiest thing in the
# app for R8 to decide is dead.
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.hashtagchow.magehand.ui.navigation.** { *; }
-keepclassmembers class com.hashtagchow.magehand.ui.navigation.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation resolves route types through `KType`/generic signatures, which only
# survive if the signature attribute does.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ---------------------------------------------------------------------------
# Hardening
# ---------------------------------------------------------------------------
# Strip every android.util.Log call below WARN out of the release build. Not a
# size optimisation — docs/design/05-security.md says the resume token is "never
# logged", and this makes that true by construction for the whole dependency tree
# rather than by review of our own call sites. WARN/ERROR survive, so a real
# failure is still diagnosable from a bug report.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------
# Keep line numbers so a stack trace from a sideloaded beta is worth something,
# and rename the source file to a constant so it leaks nothing.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
