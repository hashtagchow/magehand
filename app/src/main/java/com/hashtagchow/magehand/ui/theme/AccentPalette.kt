package com.hashtagchow.magehand.ui.theme

/**
 * Per-character accent colour → Material colour roles (docs/design/04-screens-ux.md
 * §Theming: "per-character **accent color** seeds the scheme (stored in `theme_prefs`)").
 *
 * ### Why this is plain ARGB integers and not Compose `Color`
 *
 * So it can be unit-tested. The whole point of a colour that a user picks is that it can
 * be picked badly — a near-white accent on a light scheme, a near-black one on a dark
 * scheme — and the failure mode is unreadable text, which no screenshot review reliably
 * catches. `AccentPaletteTest` asserts a WCAG contrast floor on every derived on-colour,
 * for every preset and for the pathological ends of the range. That test needs arithmetic,
 * not a Compose runtime.
 *
 * ### Why it is not a full HCT tonal palette
 *
 * Material's real scheme generation lives in `material-color-utilities`, which Compose
 * Material 3 does not expose as public API (the only public seeded scheme is
 * `dynamicColorScheme`, which reads the *wallpaper*, not an app value — and 04 turns
 * dynamic colour off). Pulling in the library to generate 13 tones for one accent is not
 * a WP6-shaped change. Instead the accent seeds `primary`/`secondary`/`tertiary` and their
 * containers by tonal blending against black/white, and every other role stays the app's
 * hand-built scheme. Documented as a deviation in docs/verification/WP6.md.
 */
object AccentPalette {

    /** The swatches the customize sheet offers. Names are what the picker announces. */
    val PRESETS: List<AccentPreset> = listOf(
        AccentPreset("Arcane", "#7E57C2"),
        AccentPreset("Ember", "#C2410C"),
        AccentPreset("Verdant", "#2E7D32"),
        AccentPreset("Tidal", "#0277BD"),
        AccentPreset("Gilded", "#B8860B"),
        AccentPreset("Blood", "#B3123C"),
        AccentPreset("Slate", "#4A5568"),
        AccentPreset("Bloom", "#C2185B"),
    )

    /**
     * `"#RRGGBB"` (or `"#AARRGGBB"`) → opaque ARGB, or `null` if it is not a colour.
     *
     * Tolerant on input because the value comes out of a database column that 03 does not
     * constrain; strict about the result, which is always fully opaque — a translucent
     * `primary` would let the surface bleed through every button.
     */
    fun parse(hex: String?): Int? {
        val cleaned = hex?.trim()?.removePrefix("#") ?: return null
        if (cleaned.length != 6 && cleaned.length != 8) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return (value.toInt() or ALPHA_MASK)
    }

    /** ARGB → `"#RRGGBB"`, the form `theme_prefs.accentColor` stores. */
    fun toHex(argb: Int): String = "#%06X".format(argb and RGB_MASK)

    /**
     * WCAG relative luminance. Used for the contrast floor rather than the cheap
     * `0.299r + 0.587g + 0.114b` approximation, because the sRGB gamma step is precisely
     * what makes mid-tone accents (the ones users pick) come out on the right side.
     */
    fun luminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(argb.red) +
            0.7152 * channel(argb.green) +
            0.0722 * channel(argb.blue)
    }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Linear blend in sRGB space. `t = 0` → [from], `t = 1` → [to]. */
    fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * f).toInt().coerceIn(0, 255)
        return argb(
            mix(from.red, to.red),
            mix(from.green, to.green),
            mix(from.blue, to.blue),
        )
    }

    /**
     * Black or white text for [background], whichever wins on contrast.
     *
     * Not a luminance threshold: a fixed cut-off gets mid-tones like `#7E57C2` wrong in
     * one direction or the other depending on where you put it. Comparing the two actual
     * ratios is both simpler and correct by construction.
     */
    fun onColorFor(background: Int): Int =
        if (contrastRatio(background, NEAR_BLACK) >= contrastRatio(background, WHITE)) {
            NEAR_BLACK
        } else {
            WHITE
        }

    /**
     * Derives the accent-driven roles.
     *
     * The tonal moves are deliberately asymmetric. On a dark scheme an accent is lifted
     * towards white so it separates from the surface; on a light scheme it is deepened
     * slightly, because a saturated mid-tone that reads fine as a chip fails as a button
     * fill under white text. Containers go the other way — far towards the scheme's own
     * background — so they stay backgrounds rather than becoming a second primary.
     */
    fun roles(accent: Int, dark: Boolean): AccentRoles {
        val primary = if (dark) blend(accent, WHITE, 0.34f) else blend(accent, NEAR_BLACK, 0.10f)
        val primaryContainer =
            if (dark) blend(accent, NEAR_BLACK, 0.66f) else blend(accent, WHITE, 0.80f)
        val secondary = if (dark) blend(primary, GREY, 0.40f) else blend(primary, GREY, 0.34f)
        val secondaryContainer =
            if (dark) blend(secondary, NEAR_BLACK, 0.62f) else blend(secondary, WHITE, 0.78f)
        val tertiary = if (dark) blend(accent, WHITE, 0.52f) else blend(accent, NEAR_BLACK, 0.30f)
        val tertiaryContainer =
            if (dark) blend(tertiary, NEAR_BLACK, 0.68f) else blend(tertiary, WHITE, 0.84f)

        return AccentRoles(
            primary = primary,
            onPrimary = onColorFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColorFor(primaryContainer),
            secondary = secondary,
            onSecondary = onColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColorFor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = onColorFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColorFor(tertiaryContainer),
        )
    }

    /** The floor `AccentPaletteTest` holds every derived pair to: WCAG AA for large text. */
    const val MIN_CONTRAST_RATIO: Double = 3.0

    private const val ALPHA_MASK = 0xFF000000.toInt()
    private const val RGB_MASK = 0x00FFFFFF

    /** Not pure black: pure black on a coloured fill reads as a hole punched in it. */
    private const val NEAR_BLACK = 0xFF10131A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFF8A8F98.toInt()

    private fun argb(r: Int, g: Int, b: Int): Int =
        ALPHA_MASK or (r shl 16) or (g shl 8) or b

    private val Int.red: Int get() = (this shr 16) and 0xFF
    private val Int.green: Int get() = (this shr 8) and 0xFF
    private val Int.blue: Int get() = this and 0xFF
}

/** A named swatch in the customize sheet's colour picker (04 §6). */
data class AccentPreset(val name: String, val hex: String)

/** The colour roles [AccentPalette.roles] derives, as opaque ARGB. */
data class AccentRoles(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
) {
    /** Every (fill, on-fill) pair, for the contrast assertion. */
    val pairs: List<Pair<Int, Int>>
        get() = listOf(
            primary to onPrimary,
            primaryContainer to onPrimaryContainer,
            secondary to onSecondary,
            secondaryContainer to onSecondaryContainer,
            tertiary to onTertiary,
            tertiaryContainer to onTertiaryContainer,
        )
}
