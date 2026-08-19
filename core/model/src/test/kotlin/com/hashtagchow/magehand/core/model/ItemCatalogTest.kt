package com.hashtagchow.magehand.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in catalog (docs/design/10-inventory.md decision 6).
 *
 * Not a spot-check of thirty prices — that would be a second copy of the table, wrong in the
 * same places. These pin the **invariants** a catalog has to hold to be usable: stable ids
 * that [NewItemSpec.catalogId] can point at, an order a human can scan, sane defaults on the
 * bundles, and a round trip into a creatable spec that keeps every field.
 */
class ItemCatalogTest {

    private val entries = ItemCatalog.entries

    @Test
    fun `the catalog is the size decision 6 asks for`() {
        // "~30 SRD-common entries". A bound rather than an exact count: the point is that it
        // stays the set a player reaches for mid-session and does not drift toward being a
        // second, worse copy of the SRD equipment chapter. That is what the custom form is for.
        assertTrue("too few entries to replace the custom form: ${entries.size}", entries.size >= 30)
        assertTrue(
            "the catalog is growing into a library it was decided not to be: ${entries.size}",
            entries.size <= 45,
        )
    }

    /**
     * Ids are the contract with [NewItemSpec.catalogId], which is stored and compared across
     * sessions. A duplicate would make `byId` return an arbitrary one of two entries.
     */
    @Test
    fun `every id is unique, stable-looking and never shown to the user`() {
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach {
            assertTrue(
                "an id is a key, not a label — lowercase and hyphenated: ${it.id}",
                it.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")),
            )
        }
    }

    @Test
    fun `every name is unique, so the picker never shows two identical rows`() {
        assertEquals(entries.size, entries.map { it.name }.toSet().size)
        assertTrue(entries.all { it.name.isNotBlank() })
    }

    /**
     * Alphabetical: the player is looking for a *known name*, which is a scan, and any other
     * order makes them read all thirty entries to be sure the one they want is absent.
     */
    @Test
    fun `the entries are in alphabetical order`() {
        assertEquals(entries.map { it.name }.sortedBy { it.lowercase() }, entries.map { it.name })
    }

    @Test
    fun `weights and values are never negative`() {
        entries.forEach {
            assertTrue("${it.name} weighs a negative amount", it.weightLb >= 0.0)
            assertTrue("${it.name} has a negative price", it.valueGp >= 0.0)
        }
    }

    /**
     * `0.0` here is the SRD's own "—" — its claim that the item is too light to track. That is
     * different from [InventoryItem.weightLb]'s `null`, which means nobody weighed it, and the
     * two must not be conflated: a catalog entry always has an answer.
     */
    @Test
    fun `a zero weight is the SRD's own claim, and reaches the sheet as one`() {
        val chalk = ItemCatalog.byId("chalk")!!
        assertEquals(0.0, chalk.weightLb, 0.0)

        val spec = NewItemSpec.of(chalk)
        assertEquals("a stated zero must survive as a zero, not become an absence", 0.0, spec.weightLb!!, 0.0)
    }

    @Test
    fun `bundles default to their bundle size`() {
        // Ammunition and pitons are carried in bundles; making the player tap plus nineteen
        // times for a quiver would be slower than the form this list exists to replace.
        assertEquals(20, ItemCatalog.byId("arrows")!!.defaultQuantity)
        assertEquals(20, ItemCatalog.byId("crossbow-bolts")!!.defaultQuantity)
        assertEquals(10, ItemCatalog.byId("piton")!!.defaultQuantity)
        assertEquals(1, ItemCatalog.byId("torch")!!.defaultQuantity)
    }

    @Test
    fun `every default quantity is at least one`() {
        assertTrue(entries.all { it.defaultQuantity >= 1 })
    }

    @Test
    fun `every entry has tags and a one-line description`() {
        entries.forEach {
            assertTrue("${it.name} has no tags to file it under", it.tags.isNotEmpty())
            assertTrue("${it.name} has no description", it.description.isNotBlank())
            assertTrue(
                "${it.name}'s description is a paragraph, not a line: ${it.description.length} chars",
                it.description.length <= 160,
            )
        }
    }

    /**
     * The one magic item in the list is not tagged `mundane`. A sheet that filters its
     * inventory by tag should see the potion filed where it belongs.
     */
    @Test
    fun `the potion is the only entry not tagged mundane`() {
        val notMundane = entries.filterNot { "mundane" in it.tags }
        assertEquals(listOf("Potion of Healing"), notMundane.map { it.name })
        assertTrue("magic" in notMundane.single().tags)
    }

    @Test
    fun `no catalog tag collides with a coin denomination`() {
        // A catalog entry that read as currency would land in the wallet instead of the
        // inventory, and its quantity would be counted as money.
        entries.forEach {
            assertNull("${it.name} is tagged like a coin: ${it.tags}", CoinKind.fromTags(it.tags))
        }
    }

    @Test
    fun `byId finds every entry and nothing else`() {
        entries.forEach { assertEquals(it, ItemCatalog.byId(it.id)) }
        assertNull(ItemCatalog.byId("no-such-entry"))
        assertNull(ItemCatalog.byId(""))
    }

    @Test
    fun `an entry becomes a spec with every field carried across`() {
        val entry = ItemCatalog.byId("rope-hempen")!!
        val spec = NewItemSpec.of(entry, quantity = 2)

        assertEquals(entry.name, spec.name)
        assertEquals(2, spec.quantity)
        assertEquals(entry.weightLb, spec.weightLb)
        assertEquals(entry.valueGp, spec.valueGp)
        assertEquals(entry.description, spec.description)
        assertEquals(entry.tags, spec.tags)
        assertEquals(entry.id, spec.catalogId)
        assertTrue(spec.isValid)
    }

    @Test
    fun `a spec built from an entry defaults to that entry's quantity`() {
        assertEquals(20, NewItemSpec.of(ItemCatalog.byId("arrows")!!).quantity)
    }

    @Test
    fun `every entry produces a valid spec`() {
        entries.forEach { assertTrue(it.name, NewItemSpec.of(it).isValid) }
    }

    // --- the spec's own rules ----------------------------------------------

    @Test
    fun `a spec with no name or no quantity is not valid`() {
        assertTrue(NewItemSpec(name = "Torch").isValid)
        assertTrue(!NewItemSpec(name = "").isValid)
        assertTrue(!NewItemSpec(name = "   ").isValid)
        assertTrue(!NewItemSpec(name = "Torch", quantity = 0).isValid)
        assertTrue(!NewItemSpec(name = "Torch", quantity = -1).isValid)
    }

    @Test
    fun `a custom spec carries no catalog id`() {
        assertNull(NewItemSpec(name = "A curious key").catalogId)
    }

    @Test
    fun `a coin spec is tagged, priced and weighed per denomination`() {
        val spec = NewItemSpec.ofCoin(CoinKind.SILVER, 57)

        assertEquals("Silver piece", spec.name)
        assertEquals(57, spec.quantity)
        assertEquals(0.1, spec.valueGp)
        assertEquals(0.02, spec.weightLb)
        assertEquals(listOf("silver"), spec.tags)
        assertNull("a coin is not a catalog entry", spec.catalogId)
        assertNotNull(CoinKind.fromTags(spec.tags))
    }
}
