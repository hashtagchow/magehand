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

# ---------------------------------------------------------------------------
# Bundled SRD catalogs (FR-49)
# ---------------------------------------------------------------------------
# Belt to the braces of M1 [review, 2026-09-12]. `SpellCatalog.readCatalog` and
# `CatalogProvenance` now name their serializers statically
# (`SpellJson.serializer()` + `ListSerializer`), so no reflective `typeOf` lookup
# is left for R8 to defeat — a stripped or renamed `$serializer` would have
# crashed the Add sheet and Settings' About section in the release variant only,
# with every debug build and every JVM test green.
#
# This rule is kept anyway, in the navigation block's style, because the wire
# shapes are private `@Serializable` data classes whose only references are the
# `serializer()` calls themselves: exactly the shape R8 is most willing to
# decide is dead, and the failure it would cause is release-only.
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.hashtagchow.magehand.core.data.catalog.** { *; }
-keepclassmembers class com.hashtagchow.magehand.core.data.catalog.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
