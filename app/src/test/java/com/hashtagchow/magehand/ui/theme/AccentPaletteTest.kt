package com.hashtagchow.magehand.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-character accent (04 §Theming, §6).
 *
 * The interesting assertion is the last one: a user-chosen colour is arbitrary, so every
 * derived on-colour has to clear a contrast floor for *any* input, not just for the eight
 * presets. Testing that is why the palette is integer arithmetic rather than Compose
 * `Color` — see `AccentPalette`'s KDoc.
 */
class AccentPaletteTest {

    @Test
    fun `six-digit hex parses to an opaque colour`() {
        assertEquals(0xFF7E57C2.toInt(), AccentPalette.parse("#7E57C2"))
        assertEquals(0xFF7E57C2.toInt(), AccentPalette.parse("7E57C2"))
    }

    @Test
    fun `an eight-digit value is forced opaque, so nothing can bleed through a fill`() {
        assertEquals(0xFF7E57C2.toInt(), AccentPalette.parse("#007E57C2"))
    }

    @Test
    fun `junk in the column is null, not a crash and not black`() {
        assertNull(AccentPalette.parse(null))
        assertNull(AccentPalette.parse(""))
        assertNull(AccentPalette.parse("#12345"))
        assertNull(AccentPalette.parse("chartreuse"))
    }

    @Test
    fun `hex round-trips`() {
        AccentPalette.PRESETS.forEach { preset ->
            val parsed = AccentPalette.parse(preset.hex)
            assertNotNull(preset.hex, parsed)
            assertEquals(preset.hex.uppercase(), AccentPalette.toHex(parsed!!))
        }
    }

    @Test
    fun `luminance is ordered black to white`() {
        assertTrue(AccentPalette.luminance(0xFF000000.toInt()) < 0.01)
        assertTrue(AccentPalette.luminance(0xFFFFFFFF.toInt()) > 0.99)
    }

    @Test
    fun `contrast of black on white is the WCAG maximum`() {
        assertEquals(
            21.0,
            AccentPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            0.05,
        )
    }

    @Test
    fun `a blend of zero and one returns its endpoints`() {
        val a = 0xFF102030.toInt()
        val b = 0xFFA0B0C0.toInt()
        assertEquals(a, AccentPalette.blend(a, b, 0f))
        assertEquals(b, AccentPalette.blend(a, b, 1f))
    }

    @Test
    fun `a pale background takes dark text and a dark one takes light text`() {
        assertTrue(
            AccentPalette.luminance(AccentPalette.onColorFor(0xFFF5F5F5.toInt())) < 0.1,
        )
        assertTrue(
            AccentPalette.luminance(AccentPalette.onColorFor(0xFF101010.toInt())) > 0.5,
        )
    }

    @Test
    fun `every preset clears the contrast floor on every derived pair, light and dark`() {
        AccentPalette.PRESETS.forEach { preset ->
            val accent = AccentPalette.parse(preset.hex)!!
            listOf(true, false).forEach { dark ->
                AccentPalette.roles(accent, dark).pairs.forEach { (fill, on) ->
                    val ratio = AccentPalette.contrastRatio(fill, on)
                    assertTrue(
                        "${preset.name} dark=$dark: ${AccentPalette.toHex(fill)} on " +
                            "${AccentPalette.toHex(on)} is only %.2f:1".format(ratio),
                        ratio >= AccentPalette.MIN_CONTRAST_RATIO,
                    )
                }
            }
        }
    }

    /**
     * The pathological inputs a colour picker cannot stop a user reaching if it is ever
     * widened past the presets: pure white, pure black, and the most saturated primaries.
     */
    @Test
    fun `extreme accents still clear the contrast floor`() {
        val extremes = listOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(),
        )
        extremes.forEach { accent ->
            listOf(true, false).forEach { dark ->
                AccentPalette.roles(accent, dark).pairs.forEach { (fill, on) ->
                    val ratio = AccentPalette.contrastRatio(fill, on)
                    assertTrue(
                        "accent ${AccentPalette.toHex(accent)} dark=$dark: only %.2f:1".format(ratio),
                        ratio >= AccentPalette.MIN_CONTRAST_RATIO,
                    )
                }
            }
        }
    }
}
