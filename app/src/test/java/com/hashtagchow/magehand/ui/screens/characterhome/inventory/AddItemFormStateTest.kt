package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CatalogCategory
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

    // --- FR-10b: the category chooser (13 decision 9) ---------------------------

    @Test
    fun `the form opens on Gear, which is what the catalog does not have`() {
        // The default the decision names, and the honest one for a form whose whole purpose is
        // "the thing the catalog is missing": most such items are neither weapons nor armor, and
        // 11 decision 2's override is there for a player who picks wrong. A default of Weapon
        // would put a claim on the sheet that nobody made.
        assertEquals(CatalogCategory.GEAR, AddItemFormState().category)
        assertEquals(CatalogCategory.GEAR, AddItemFormState(name = "Torch").toSpec()!!.category)
    }

    @Test
    fun `the chooser carries into the spec, which is what the local row is built from`() {
        listOf(CatalogCategory.WEAPON, CatalogCategory.ARMOR, CatalogCategory.GEAR).forEach {
            assertEquals(
                it,
                AddItemFormState(name = "A curious thing", category = it).toSpec()!!.category,
            )
        }
    }

    @Test
    fun `the chooser is never a reason a form is invalid`() {
        // It has no half-typed state — it is on one of three values at every instant — so unlike
        // the five text fields it can never hold a save up. That is why it is not a `String`.
        CatalogCategory.entries.forEach {
            assertTrue(it.name, AddItemFormState(name = "Rope", category = it).isValid)
        }
    }

    // --- M2: the chooser is a local-only control (1.6.0 review) ------------------

    /**
     * The gate itself, on the state rather than in the composable.
     *
     * `:app` has no Compose harness, so "the server form draws no chooser" is only assertable if
     * the *decision* lives somewhere a JVM test can hold still. It does — see
     * [AddItemFormState.isLocal] — and this is the assertion that would fire if a later wave
     * re-added the control to both paths. `InventoryWriteOpTest` holds the other half: the write
     * that a server-side answer would have ridden on drops the field.
     */
    @Test
    fun `a server form offers no category chooser and a local one does`() {
        assertFalse(
            "13 lists server-side category editing as out of scope, and the insert body drops " +
                "the field — a chooser there takes an answer and throws it away",
            AddItemFormState(isLocal = false).offersCategoryChooser,
        )
        assertTrue(AddItemFormState(isLocal = true).offersCategoryChooser)
        assertTrue("the default is the local path, where the category is read", AddItemFormState().offersCategoryChooser)
    }

    /**
     * A server form has one category and it is the default, because nothing can change it.
     *
     * Stated as an assertion rather than left implicit: with no chooser drawn there is no
     * `onChange` that can move this field on the server path, so the spec a server add produces
     * carries `GEAR` — the value `NewItemSpec` already defaults to, which is why the field being
     * dropped downstream costs nothing.
     */
    @Test
    fun `the spec a server form produces carries the untouched default`() {
        val spec = AddItemFormState(name = "A curious thing", isLocal = false).toSpec()

        assertNotNull(spec)
        assertEquals(CatalogCategory.GEAR, spec!!.category)
    }
}
