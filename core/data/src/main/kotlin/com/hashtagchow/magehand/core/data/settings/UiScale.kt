package com.hashtagchow.magehand.core.data.settings

/**
 * FR-18's whole-UI scale factor (docs/design/14-large-screen-arc.md decisions 1-2).
 *
 * ### Why an enum and not a Float
 *
 * 14 decision 2 is "stepped factors, not a slider", and the reason is the thing a `Float`
 * cannot carry: every value in this list has been *looked at* on a device. A free factor
 * makes 1.37 as reachable as 1.25 and nobody has ever seen the tracker at 1.37 — the strip,
 * the pip rows and the number pad all have layouts that were tuned, not derived. Four steps
 * is also the whole of the settings control (decision 4), so the UI and the storage agree by
 * construction rather than by a range check somebody has to remember to write.
 *
 * ### Why it lives in `:core:data` and not in the UI
 *
 * The store below persists it, so the store's module has to name it. The Compose side of
 * FR-18 (the `LocalDensity` provider) turns [factor] into a `Density`; that arithmetic is
 * `:app`'s, because `androidx.compose.ui.unit.Density` is a Compose type and this module
 * deliberately does not depend on Compose.
 *
 * ### Why the factors are all ≥ 1.0
 *
 * 14 decision 1: the factor *multiplies* the system's font-size and display-size
 * accessibility settings. A factor below 1.0 would let an app setting quietly undo the
 * setting a user chose in Android's own accessibility screen, which is not a scale control,
 * it is a bug with a slider on it.
 *
 * @property key the stored string (14 decision 2 says "an app-level string"). Stable — it is
 *   on disk on user devices, so it is renamed only with a migration, never casually.
 * @property factor the multiplier applied to the system density *and* font scale.
 */
enum class UiScale(val key: String, val factor: Float) {
    /** 1.0 — exactly what the system handed us, which is what every build before 1.7.0 did. */
    DEFAULT("default", 1.0f),
    LARGE_110("110", 1.1f),
    LARGE_125("125", 1.25f),
    LARGE_150("150", 1.5f),
    ;

    /**
     * 14 decision 3's `(100 * f).toInt()`, verbatim, for `WebSettings.textZoom`.
     *
     * Kept here rather than at the WebView so the four values are pinned in one test next to
     * the four factors they come from: the sheet is a *second* rendering engine, and "the
     * sheet did not follow the rest of the app" is the failure this feature is most likely
     * to ship with.
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
