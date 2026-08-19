package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ItemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-item flow (docs/design/10-inventory.md decision 6): catalog filtering, and the
 * custom form's validation.
 *
 * The assertion that matters most is the one about **blank versus zero**: an optional field
 * left empty must reach `NewItemSpec` as `null`, because a null field is *omitted* from the
 * insert body while a `0.0` is a claim that the item is weightless or worthless. Nothing on
 * screen distinguishes the two, so nothing but a test can.
 */
class AddItemFormStateTest {

    // --- catalog search ---------------------------------------------------------

    @Test
    fun `a blank query is the whole catalog, in the catalog's own order`() {
        assertEquals(ItemCatalog.entries, catalogMatches(""))
        assertEquals(ItemCatalog.entries, catalogMatches("   "))
    }

    @Test
    fun `search is case-insensitive over names`() {
        val matches = catalogMatches("TORCH")

        assertEquals(listOf("torch"), matches.map { it.id })
    }

    @Test
    fun `a partial name matches, so the player need not know the SRD's spelling`() {
        // The SRD calls them "Rope, Hempen (50 feet)" and "Rope, Silk (50 feet)".
        assertEquals(listOf("rope-hempen", "rope-silk"), catalogMatches("rope").map { it.id }.sorted())
    }

    @Test
    fun `tags are searched too, which is the only way ammo finds arrows`() {
        val matches = catalogMatches("ammunition").map { it.id }.sorted()

        assertEquals(listOf("arrows", "crossbow-bolts"), matches)
        // Neither name contains the word, so a name-only filter would find nothing.
        assertTrue(matches.isNotEmpty())
        assertTrue(ItemCatalog.entries.none { it.name.contains("ammunition", ignoreCase = true) })
    }

    @Test
    fun `nothing matching is an empty list, which is the sheet's cue to offer the custom form`() {
        assertTrue(catalogMatches("vorpal").isEmpty())
    }

    // --- custom form validation ---------------------------------------------------

    @Test
    fun `a fresh form is invalid but shows nothing, because nobody has tried to save yet`() {
        val form = AddItemFormState()

        assertFalse(form.isValid)
        assertTrue(form.nameIsInvalid)
        assertNull(form.nameError)
        assertEquals("1", form.quantity)
    }

    @Test
    fun `errors appear only once showErrors is set`() {
        val form = AddItemFormState(showErrors = true)

        assertEquals(R.string.inventory_error_name, form.nameError)
    }

    @Test
    fun `a name is required and whitespace is not a name`() {
        assertTrue(AddItemFormState(name = "   ").nameIsInvalid)
        assertFalse(AddItemFormState(name = "Torch").nameIsInvalid)
    }

    @Test
    fun `quantity must be a whole number inside the range`() {
        assertTrue(AddItemFormState(name = "x", quantity = "").quantityIsInvalid)
        assertTrue(AddItemFormState(name = "x", quantity = "0").quantityIsInvalid)
        assertTrue(AddItemFormState(name = "x", quantity = "abc").quantityIsInvalid)
        assertTrue(
            AddItemFormState(name = "x", quantity = "${AddItemFormState.QUANTITY_RANGE.last + 1}")
                .quantityIsInvalid,
        )
        assertFalse(AddItemFormState(name = "x", quantity = "20").quantityIsInvalid)
    }

    @Test
    fun `the quantity ceiling clears a realistic purse of copper`() {
        // 12,000 cp is an ordinary amount of money, and the bound exists to catch a slipped
        // keypress rather than to second-guess the player.
        assertFalse(AddItemFormState(name = "x", quantity = "12000").quantityIsInvalid)
    }

    @Test
    fun `a blank optional field is valid - that is what optional means`() {
        val form = AddItemFormState(name = "Letter", weight = "", value = "")

        assertFalse(form.weightIsInvalid)
        assertFalse(form.valueIsInvalid)
        assertTrue(form.isValid)
    }

    @Test
    fun `text that is not a number is invalid only when the box is not empty`() {
        assertTrue(AddItemFormState(name = "x", weight = ".").weightIsInvalid)
        assertTrue(AddItemFormState(name = "x", value = "..").valueIsInvalid)
    }

    @Test
    fun `an absurd weight or price is refused before it reaches the sheet`() {
        assertTrue(
            AddItemFormState(name = "x", weight = "${AddItemFormState.MAX_WEIGHT_LB + 1}")
                .weightIsInvalid,
        )
        assertTrue(
            AddItemFormState(name = "x", value = "${AddItemFormState.MAX_VALUE_GP + 1}")
                .valueIsInvalid,
        )
    }

    // --- form to spec ---------------------------------------------------------------

    @Test
    fun `an invalid form produces no spec at all, so it cannot be saved past the check`() {
        assertNull(AddItemFormState().toSpec())
        assertNull(AddItemFormState(name = "x", quantity = "0").toSpec())
    }

    @Test
    fun `blank optional fields become null, never zero`() {
        val spec = AddItemFormState(name = "Letter from the duke", quantity = "1").toSpec()

        assertNotNull(spec)
        assertNull(spec!!.weightLb)
        assertNull(spec.valueGp)
        assertNull(spec.description)
    }

    @Test
    fun `a filled form carries every field through, trimmed`() {
        val spec = AddItemFormState(
            name = "  Silvered dagger  ",
            quantity = "2",
            weight = "1",
            value = "110.5",
            description = "  Cold iron hilt.  ",
        ).toSpec()

        assertNotNull(spec)
        assertEquals("Silvered dagger", spec!!.name)
        assertEquals(2, spec.quantity)
        assertEquals(1.0, spec.weightLb!!, 0.0001)
        assertEquals(110.5, spec.valueGp!!, 0.0001)
        assertEquals("Cold iron hilt.", spec.description)
    }

    @Test
    fun `a typed item carries no catalogId and no tags`() {
        // `catalogId` exists to tell "picked Torch from the list" from "typed the word
        // torch"; claiming one here would make the second look like the first. The app's
        // `adventuring gear` tag is likewise a claim only the curated catalog can make.
        val spec = AddItemFormState(name = "Torch", quantity = "1").toSpec()

        assertNull(spec!!.catalogId)
        assertTrue(spec.tags.isEmpty())
    }

    @Test
    fun `a zero weight the player actually typed is kept, because that is a real claim`() {
        val spec = AddItemFormState(name = "Chalk", quantity = "1", weight = "0").toSpec()

        assertEquals(0.0, spec!!.weightLb!!, 0.0001)
    }
}
