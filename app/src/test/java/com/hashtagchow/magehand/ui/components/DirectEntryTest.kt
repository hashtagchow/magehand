package com.hashtagchow.magehand.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.hashtagchow.magehand.declaredString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-22's field rule and its key space (docs/design/15-polish-batch.md decisions 5–8).
 *
 * `clampEntry` is the whole of decision 7 as a pure function, which is why it is a function at
 * all: *"out-of-range input clamps with the field showing the clamped value before send (no
 * silent adjustment after)"* is a claim about what the text says between keystrokes, and a
 * composable test would have to stand up a Compose runtime to ask.
 */
class DirectEntryTest {

    private fun typed(text: String) = TextFieldValue(text, TextRange(text.length))

    // --- decision 7: the clamp shows itself ---------------------------------

    @Test
    fun `a value within range is left exactly as typed`() {
        val next = clampEntry(typed("14"), max = 20)
        assertEquals("14", next.text)
    }

    @Test
    fun `a value past the ceiling is rewritten before it can be sent`() {
        // The point of the whole rule: the player sees 20, not 99 followed by a row that
        // silently disagrees with them afterwards.
        assertEquals("20", clampEntry(typed("99"), max = 20).text)
    }

    @Test
    fun `a row with no ceiling takes any number`() {
        // Quantities and coins (decision 7). 4-digit cap aside, nothing bounds them above.
        assertEquals("9999", clampEntry(typed("9999"), max = null).text)
    }

    @Test
    fun `the field is capped at four digits, ceiling or not`() {
        // `HpNumberPadDialog`'s own cap, for its own reason: an unbounded field overflows the
        // display long before it overflows Int.
        assertEquals("1234", clampEntry(typed("123456"), max = null).text)
    }

    @Test
    fun `a number too long to be an Int clamps rather than throwing`() {
        assertEquals("20", clampEntry(typed("99999999999"), max = 20).text)
    }

    @Test
    fun `non-digits are dropped rather than rejecting the whole edit`() {
        // A numeric IME still offers `-`, `.` and `,`; a paste can carry a unit.
        assertEquals("12", clampEntry(typed("12 gp"), max = null).text)
        assertEquals("12", clampEntry(typed("-12"), max = null).text)
        assertEquals("125", clampEntry(typed("1.25"), max = null).text)
    }

    @Test
    fun `an emptied field stays empty`() {
        // The state after clearing. Snapping it to "0" would make the next digit append to a
        // zero the player did not type.
        val next = clampEntry(typed(""), max = 20)
        assertEquals("", next.text)
        assertNull("Set is disabled here", next.text.toIntOrNull())
    }

    @Test
    fun `zero is a value, not an empty field`() {
        assertEquals("0", clampEntry(typed("0"), max = 20).text)
    }

    @Test
    fun `the caret follows a rewritten value to the end`() {
        // Otherwise a clamp that shortened the text would leave the cursor past its end.
        val next = clampEntry(typed("99"), max = 7)
        assertEquals("7", next.text)
        assertEquals(TextRange(1), next.selection)
    }

    // --- the key space ------------------------------------------------------

    @Test
    fun `keys are prefixed so a kind survives the trip through rememberSaveable`() {
        // A slot's `_id` and a consumable's live in one namespace, and which intent the Set
        // button reaches depends on the kind — so the kind cannot be inferred from the id.
        assertTrue(DirectEntryKeys.resource("abc").startsWith(DirectEntryKeys.RESOURCE_PREFIX))
        assertTrue(DirectEntryKeys.item("abc").startsWith(DirectEntryKeys.ITEM_PREFIX))
        assertEquals(setOf("row:abc", "item:abc"), setOf(DirectEntryKeys.resource("abc"), DirectEntryKeys.item("abc")))
    }

    // --- decision 8's spoken sentences --------------------------------------

    @Test
    fun `the spoken affordance says the value can be typed into`() {
        // Decision 8's house sentence, in both shapes. Pinned against the shipping resource
        // rather than restated, so a copy edit that dropped the affordance fails here.
        assertTrue(declaredString("direct_entry_spoken_of").endsWith("tap to enter a number"))
        assertTrue(declaredString("direct_entry_spoken").endsWith("tap to enter a number"))
    }

    @Test
    fun `the ceiling-less sentence carries no total placeholder`() {
        // A quantity has no maximum, so a sentence that said "3 of 3" would be inventing one.
        assertTrue(declaredString("direct_entry_spoken").contains("%2\$d"))
        assertTrue(!declaredString("direct_entry_spoken").contains("%3\$d"))
    }
}
