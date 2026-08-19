package com.hashtagchow.magehand.ui.theme

import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * How this app draws a control that is switched off — **BUG-3's fix**.
 *
 * ### The defect
 *
 * Material 3 draws disabled content at 38% of `onSurface`, and the customize sheet's reorder
 * chevrons are disabled on the first and last row of every section by design. On the light
 * scheme a 38% dark glyph on a pale sheet is a clear grey. On the dark scheme it is a 38%
 * *pale* glyph over `surfaceContainerLow` — the two are already close, and the composite came
 * out barely distinguishable from the sheet behind it. The result was a row whose arrows
 * appeared to be missing rather than unavailable, which reads as a rendering fault rather
 * than as a boundary.
 *
 * ### Why the alpha rises and the colour does not change
 *
 * Material's guidance is that disabled content is the same colour at reduced opacity, so the
 * control keeps its shape and its position and only loses emphasis. Swapping in a different
 * hue — an "outlined disabled tint" — would fix the visibility by breaking that: an arrow
 * that changes colour when it stops working looks like a *different control*, and the eye has
 * to re-read the row to find out what happened. Raising the opacity keeps Material's model
 * and moves the one number that was wrong for this palette.
 *
 * [ICON_ALPHA] is a compromise with a floor and a ceiling, and both matter. Below Material's
 * 38% the glyph disappears on dark; much above ~60% it stops reading as disabled at all and
 * the player taps an arrow that does nothing. `SurfacePaletteTest` pins that it stays inside
 * that band and above Material's baseline.
 */
object DisabledContent {

    /** Material 3's own disabled-content opacity. The baseline this app measures against. */
    const val MATERIAL_ALPHA: Float = 0.38f

    /**
     * What this app uses for a disabled **icon** control.
     *
     * Icons only. Disabled *text* keeps [MATERIAL_ALPHA]: a label is a run of many strokes
     * whose shape survives being faint, while an arrowhead is three strokes and stops being a
     * shape at all. The tracker's hand-drawn pips and stepper glyphs stay at the baseline for
     * the same reason plus one more — each of them sits beside a live sibling, so "this one is
     * off" is legible from the pair rather than from the glyph alone.
     */
    const val ICON_ALPHA: Float = 0.55f
}

/**
 * [IconButtonDefaults.iconButtonColors] with BUG-3's disabled tint.
 *
 * Every icon button in this app that can be disabled should use this rather than the default,
 * so the fix is one call away instead of one palette edit away. The enabled colours are
 * untouched — passing only the disabled slot leaves `contentColor` at `LocalContentColor`,
 * which is what lets these buttons keep inheriting the accent inside a themed subtree.
 */
@Composable
fun mageHandIconButtonColors(): IconButtonColors = IconButtonDefaults.iconButtonColors(
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContent.ICON_ALPHA),
)
