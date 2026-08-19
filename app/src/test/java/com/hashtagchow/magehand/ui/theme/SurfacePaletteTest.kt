package com.hashtagchow.magehand.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-2 and BUG-3, as regression tests rather than as a screenshot someone remembers taking.
 *
 * ### Why these can be pinned at all
 *
 * Neither defect is a crash and neither is visible in a diff — both are *"this is technically
 * on screen and nobody can see it"*, which is the failure mode no test catches by accident.
 * What can be asserted is the arithmetic underneath: a container that is not separated from
 * its page by some measurable amount will not be visible whatever else is true, and a
 * disabled tint below Material's own baseline will not be either.
 *
 * ### What these tests do NOT prove
 *
 * That the result *looks* right. A ratio clears a floor; a person decides whether a menu
 * reads as a menu. Both fixes are on the device sweep's list for exactly that reason. What
 * these pin is that a future palette edit cannot silently undo the fix — which is what
 * actually happened here, in the sense that the defect existed from WP1 because nobody ever
 * stated these roles at all.
 */
class SurfacePaletteTest {

    /**
     * The separation floor, as a WCAG contrast ratio between a container and the page it
     * floats over.
     *
     * A ratio and not a channel delta, because contrast ratio is the metric that already
     * accounts for sRGB gamma — the same reason `AccentPalette.luminance` does the gamma step
     * rather than the cheap luma approximation. The number is low by text standards and that
     * is correct: this is *surface* separation, not legibility of glyphs on it, and a menu
     * that stood 4.5:1 off the page would be a white card in a dark app.
     *
     * Material's own dark baseline sits at about 1.14 against this app's surface, which is
     * the value that shipped and could not be seen. The floor is set above it deliberately.
     */
    private val minSeparation = 1.20

    private fun ratio(a: Color, b: Color): Double =
        AccentPalette.contrastRatio(a.toArgb(), b.toArgb())

    private fun luminance(color: Color): Double = AccentPalette.luminance(color.toArgb())

    // --- BUG-2: the surface-container ramp -------------------------------------------

    @Test
    fun `a dark container is separated from the dark page by more than Material's baseline`() {
        val separation = ratio(DarkSurfaceContainer, DarkSurface)

        assertTrue(
            "the dark surfaceContainer is $separation off the page — menus drawn on it will " +
                "read as text floating on nothing, which is BUG-2",
            separation >= minSeparation,
        )
        // The value that shipped and could not be seen, for the record.
        val materialBaseline = ratio(Color(0xFF211F26), DarkSurface)
        assertTrue(separation > materialBaseline)
    }

    @Test
    fun `a light container is separated from the light page`() {
        assertTrue(ratio(LightSurfaceContainer, LightSurface) >= 1.15)
    }

    @Test
    fun `containers rise on dark and fall on light`() {
        // The direction is half the fix: a dark container has to be *lighter* than its page
        // (elevation reads as more light), and a light one darker. Getting this backwards
        // produces a ratio that clears the floor above while looking like a hole in the page.
        assertTrue(luminance(DarkSurfaceContainer) > luminance(DarkSurface))
        assertTrue(luminance(LightSurfaceContainer) < luminance(LightSurface))
    }

    @Test
    fun `each ramp is monotonic, so a higher container is never darker than a lower one`() {
        val dark = listOf(
            DarkSurfaceContainerLowest,
            DarkSurfaceContainerLow,
            DarkSurfaceContainer,
            DarkSurfaceContainerHigh,
            DarkSurfaceContainerHighest,
        ).map(::luminance)
        assertTrue("dark ramp is not ascending: $dark", dark == dark.sorted())

        val light = listOf(
            LightSurfaceContainerLowest,
            LightSurfaceContainerLow,
            LightSurfaceContainer,
            LightSurfaceContainerHigh,
            LightSurfaceContainerHighest,
        ).map(::luminance)
        // Light's ramp descends: "higher" means more elevated, which on a light scheme means
        // further from white.
        assertTrue("light ramp is not descending: $light", light == light.sortedDescending())
    }

    @Test
    fun `the darkest dark container is still not pure black`() {
        // Pure black on an OLED panel makes an elevated surface indistinguishable from the
        // bezel, which is a different way to lose the same edge.
        assertTrue(luminance(DarkSurfaceContainerLowest) > 0.0)
    }

    // --- LOW-7: the legibility floor on every container role ---------------------------

    /**
     * WCAG AA for body text, and the reason this is a *different* number from [minSeparation].
     *
     * That one is surface-against-surface — whether a card's edge can be seen — and 4.5 there
     * would mean a white menu in a dark app. This one is glyphs against the thing they are
     * drawn on, which is the case WCAG's 4.5:1 was actually written for. Two floors because
     * they are two questions; one number for both would have to be wrong about one of them.
     */
    private val minLegibility = 4.5

    /**
     * Every role a container is painted with, paired with the `onSurface` drawn over it.
     *
     * Named as a list rather than asserted role by role, because the failure this guards is a
     * palette edit that darkens **one** step of the ramp — and a test naming five roles by hand
     * is a test that stops covering the sixth the day one is added. A new role added to the
     * ramp has to be added here, which is a visible omission in a diff rather than a silent gap.
     *
     * `surface` and `surfaceVariant` ride along: they are not "container" roles by Material's
     * naming, and text lands on them constantly — the tab's own meta lines and section weights
     * are `onSurfaceVariant` over one of them.
     */
    private fun containerRoles(dark: Boolean): List<Pair<String, Color>> = if (dark) {
        listOf(
            "surface" to DarkSurface,
            "surfaceVariant" to DarkSurfaceVariant,
            "surfaceContainerLowest" to DarkSurfaceContainerLowest,
            "surfaceContainerLow" to DarkSurfaceContainerLow,
            "surfaceContainer" to DarkSurfaceContainer,
            "surfaceContainerHigh" to DarkSurfaceContainerHigh,
            "surfaceContainerHighest" to DarkSurfaceContainerHighest,
        )
    } else {
        listOf(
            "surface" to LightSurface,
            "surfaceVariant" to LightSurfaceVariant,
            "surfaceContainerLowest" to LightSurfaceContainerLowest,
            "surfaceContainerLow" to LightSurfaceContainerLow,
            "surfaceContainer" to LightSurfaceContainer,
            "surfaceContainerHigh" to LightSurfaceContainerHigh,
            "surfaceContainerHighest" to LightSurfaceContainerHighest,
        )
    }

    /**
     * The ratios are **computed, not tabulated**.
     *
     * A test carrying the expected numbers would pass by being edited whenever the palette
     * moved, which is precisely the moment it needs to fail. What is asserted is the *property*
     * — text on any of these is legible — and the number in the failure message is whatever the
     * palette actually produces, so a regression reports its own size.
     */
    @Test
    fun `onSurface clears the legibility floor on every dark container role`() {
        containerRoles(dark = true).forEach { (name, container) ->
            val contrast = ratio(DarkOnSurface, container)
            assertTrue(
                "dark onSurface over $name is $contrast:1, below the $minLegibility floor — " +
                    "text on it is not readable",
                contrast >= minLegibility,
            )
        }
    }

    @Test
    fun `onSurface clears the legibility floor on every light container role`() {
        containerRoles(dark = false).forEach { (name, container) ->
            val contrast = ratio(LightOnSurface, container)
            assertTrue(
                "light onSurface over $name is $contrast:1, below the $minLegibility floor — " +
                    "text on it is not readable",
                contrast >= minLegibility,
            )
        }
    }

    @Test
    fun `the floor covers every role in the ramp, in both schemes`() {
        // The list above is hand-written, so this is what stops it quietly shrinking: seven
        // roles per scheme, and both schemes state the same seven. A palette that grew an
        // eighth role and forgot to list it fails here rather than going untested.
        assertTrue(containerRoles(dark = true).size == 7)
        assertTrue(
            containerRoles(dark = true).map { it.first } ==
                containerRoles(dark = false).map { it.first },
        )
    }

    // --- BUG-3: the disabled icon tint -------------------------------------------------

    @Test
    fun `a disabled icon is drawn above Material's baseline opacity`() {
        assertTrue(
            "BUG-3's fix is the raised alpha; dropping back to Material's baseline restores " +
                "the invisible chevrons",
            DisabledContent.ICON_ALPHA > DisabledContent.MATERIAL_ALPHA,
        )
    }

    @Test
    fun `a disabled icon is still visibly disabled`() {
        // The other half of the compromise: much past this and a dead control looks live,
        // and the player taps an arrow that does nothing.
        assertTrue(DisabledContent.ICON_ALPHA <= 0.6f)
    }

    @Test
    fun `Material's baseline is recorded as the number it actually is`() {
        // Named so the comparison above is against a fact rather than a guess.
        assertTrue(DisabledContent.MATERIAL_ALPHA == 0.38f)
    }
}
