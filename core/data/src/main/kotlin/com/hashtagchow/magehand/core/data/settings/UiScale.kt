package com.hashtagchow.magehand.core.data.settings

/**
 * FR-18's whole-UI scale factor (docs/design/14-large-screen-arc.md decisions 1-2, addendum 3).
 *
 * ### Why an enum and not a Float
 *
 * 14 decision 2 is "stepped factors, not a slider", and the reason is the thing a `Float`
 * cannot carry: every value in this list has been *looked at* on a device. A free factor
 * makes 1.37 as reachable as 1.25 and nobody has ever seen the tracker at 1.37 — the strip,
 * the pip rows and the number pad all have layouts that were tuned, not derived. This list is
 * also the whole of the settings control (decision 4, FR-38 ruling 4), so the UI and the
 * storage agree by construction rather than by a range check somebody has to remember to write.
 *
 * ### Why it lives in `:core:data` and not in the UI
 *
 * The store below persists it, so the store's module has to name it. The Compose side of
 * FR-18 (the `LocalDensity` provider) turns [factor] into a `Density`; that arithmetic is
 * `:app`'s, because `androidx.compose.ui.unit.Density` is a Compose type and this module
 * deliberately does not depend on Compose.
 *
 * ### Why the factors go below 1.0, and where they stop
 *
 * 14 decision 1 originally required every factor to be ≥ 1.0, on the argument that a sub-1.0
 * app factor would quietly undo an accessibility setting the user chose in Android's own
 * screen. **Addendum 3 withdraws that clause** (FR-38 ruling 2): the factor is the user's own
 * explicit choice, made in this app's settings under a label that says what it does, and it
 * multiplies the system settings in *both* directions exactly as Android's own "display size"
 * control does. A user who wants more on screen than their system settings give them is asking
 * for the same thing as a user who wants less, and refusing only one of them is a preference
 * dressed up as a safeguard.
 *
 * The floor is **0.7 and no lower**, and the cost is stated rather than hidden: at 0.7 a 48 dp
 * touch target measures about 34 dp, under the accessibility minimum. That is accepted as the
 * same class of deliberate choice as the system's own smallest display size — which is why the
 * steps below 1.0 stop there instead of continuing to 0.5, where the app would be unusable
 * rather than merely dense.
 *
 * @property key the stored string (14 decision 2 says "an app-level string"). Stable — it is
 *   on disk on user devices, so it is renamed only with a migration, never casually.
 * @property factor the multiplier applied to the system density *and* font scale.
 */
enum class UiScale(val key: String, val factor: Float) {
    /**
     * 0.7 — the floor. A 48 dp target measures ~34 dp here; see the KDoc above. Addendum 3 keeps
     * the settings note to its one existing sentence, so this cost is stated in the design and in
     * this file rather than in the UI.
     */
    SMALL_70("70", 0.7f),
    SMALL_80("80", 0.8f),
    SMALL_90("90", 0.9f),

    /** 1.0 — exactly what the system handed us, which is what every build before 1.7.0 did. */
    DEFAULT("default", 1.0f),
    LARGE_110("110", 1.1f),
    LARGE_125("125", 1.25f),
    LARGE_150("150", 1.5f),
    ;

    /**
     * 14 decision 3's `(100 * f).toInt()`, verbatim, for `WebSettings.textZoom`.
     *
     * Kept here rather than at the WebView so every value is pinned in one test next to the
     * factor it comes from: the sheet is a *second* rendering engine, and "the sheet did not
     * follow the rest of the app" is the failure this feature is most likely to ship with.
     *
     * The sub-1.0 steps go through unchanged — `WebSettings.textZoom` accepts 70/80/90 as
     * readily as it accepts 150, so the sheet shrinks with the app rather than staying stranded
     * at full size on a screen where everything else got smaller.
     */
    val textZoom: Int get() = (100 * factor).toInt()

    companion object {
        /**
         * The stored string back to a step, **degrading to [DEFAULT] rather than failing**.
         *
         * Three real inputs land here: `null` (never set), a key from a *newer* build whose
         * step this one does not have (the user downgraded, or Play rolled them back), and a
         * corrupted value. All three mean the same thing to a user — "my app should still
         * open" — and the only safe answer is the scale every previous version rendered at.
         */
        fun fromKey(key: String?): UiScale = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
