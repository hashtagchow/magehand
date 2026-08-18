package com.hashtagchow.magehand.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Default Material 3 typography.
 *
 * docs/design/04-screens-ux.md calls for a display face for HP / slot numerals;
 * that font ships with the Tracker work (WP6). Until then the tuned
 * `displayLarge`/`displayMedium` weights below stand in for it.
 */
val MageHandTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

/** Numeral style used by the tracker's big HP / slot counters. */
val TrackerNumeralStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 44.sp,
)
