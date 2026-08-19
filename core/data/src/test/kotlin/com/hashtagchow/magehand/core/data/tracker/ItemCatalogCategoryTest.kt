package com.hashtagchow.magehand.core.data.tracker

import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.ItemCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog's categories, checked against the **server's** tag taxonomy
 * (docs/design/13-collapsible-sections-local-gear.md decision 7).
 *
 * ### Why this test is in `:core:data` and not beside `ItemCatalogTest`
 *
 * Because it is the only place both halves are visible. [ItemCatalog] lives in `:core:model`,
 * where a catalog entry is written; [InventoryEngine]'s `WEAPON_TAGS` and `ARMOR_TAGS` live here,
 * where a DiceCloud sheet is read. Decision 7's requirement — *"a catalog weapon and a
 * server-discovered weapon mean the same thing"* — is a statement **about the pair**, and a test
 * that could only see one of them would be asserting a convention rather than checking it.
 *
 * ### Why it is not a list of thirty expected categories
 *
 * `ItemCatalogTest` states the reason for the prices and it applies here: a spot-check table is a
 * second copy of the data, wrong in the same places, and it would have to be edited by whoever
 * mis-classified an entry. What is checked instead is **agreement between two independent
 * statements of the same fact** — what the entry says it is, and what its tags say it is — which
 * no single edit can satisfy by accident.
 */
class ItemCatalogCategoryTest {

    /** The taxonomy, applied the way `InventoryEngine.equipGroup` applies it — weapon first. */
    private fun categoryFromTags(tags: List<String>): CatalogCategory {
        val normalized = tags.map { it.trim().lowercase() }
        return when {
            normalized.any { it in InventoryEngine.WEAPON_TAGS } -> CatalogCategory.WEAPON
            normalized.any { it in InventoryEngine.ARMOR_TAGS } -> CatalogCategory.ARMOR
            else -> CatalogCategory.GEAR
        }
    }

    @Test
    fun `every entry's category is the one its own tags imply`() {
        ItemCatalog.entries.forEach { entry ->
            assertEquals(
                "${entry.name} is filed as ${entry.category} but its tags say otherwise: ${entry.tags}",
                categoryFromTags(entry.tags),
                entry.category,
            )
        }
    }

    /**
     * The other direction, and the one that will actually fire.
     *
     * The catalog is the SRD's *Adventuring Gear* table, so every entry is gear today and the
     * test above passes trivially — a new entry that was mis-classified as gear would pass it too,
     * as long as its tags were also gear-ish. What catches a real mistake is asserting the
     * **shape of the list**: it carries no weapon or armor tag, so the day someone adds a
     * longsword with `martial weapon` on it, this fails and points at the category they have to
     * set. See `ItemCatalog`'s "Every entry is GEAR today" section, which is what this pins.
     *
     * If a weapon is deliberately added, the fix is to delete this test and keep the one above —
     * not to widen it.
     */
    @Test
    fun `the catalog carries no weapon or armor tag, so every entry is gear`() {
        val classified = ItemCatalog.entries.filterNot { it.category == CatalogCategory.GEAR }

        assertTrue(
            "the catalog has gained a weapon or a suit of armor: " +
                "${classified.map { it.name }} — see this test's KDoc",
            classified.isEmpty(),
        )
        assertTrue(
            "an entry carries an equippable tag but is filed as gear",
            ItemCatalog.entries.none { entry ->
                entry.tags.any { it.trim().lowercase() in InventoryEngine.EQUIPPABLE_TAGS }
            },
        )
    }

    /**
     * The two vocabularies that reach the database and the section list.
     *
     * `storedValue` is written into `local_tracker_rows.category`, and the column's `DEFAULT` is
     * a compile-time constant that cannot reference the enum (see [LocalTrackerRowEntity]) — so
     * the two are declared twice and asserted equal here, which is the check that would otherwise
     * be a comment nobody runs.
     */
    @Test
    fun `the stored vocabulary is lowercase, round-trips, and matches the column default`() {
        CatalogCategory.entries.forEach {
            assertEquals(it.storedValue, it.storedValue.lowercase())
            assertEquals(it, CatalogCategory.fromStored(it.storedValue))
        }
        assertEquals(
            "the v5 column default and the enum's own spelling must be one string",
            CatalogCategory.GEAR.storedValue,
            LocalTrackerRowEntity.CATEGORY_GEAR,
        )
    }

    /**
     * Anything unrecognised is gear, never null and never a dropped row.
     *
     * The deliberate difference from `LocalRowKind.fromStored`, argued on the companion: an
     * unknown `kind` is a row that cannot be rendered, an unknown `category` is a row that renders
     * perfectly well. Refusing it would delete a player's item to protect a classification.
     */
    @Test
    fun `an unknown stored category reads as gear rather than as nothing`() {
        listOf(null, "", "GEAR", "shield", "wand").forEach {
            assertEquals(it.toString(), CatalogCategory.GEAR, CatalogCategory.fromStored(it))
        }
    }

    /** One-for-one with [EquipGroup], which is what makes the local and server sections agree. */
    @Test
    fun `every category maps onto its own equip group and they cover each other`() {
        assertEquals(EquipGroup.WEAPON, CatalogCategory.WEAPON.equipGroup)
        assertEquals(EquipGroup.ARMOR, CatalogCategory.ARMOR.equipGroup)
        assertEquals(EquipGroup.GEAR, CatalogCategory.GEAR.equipGroup)
        assertEquals(
            "a new EquipGroup with no category to reach it would be a section nothing local " +
                "could ever land in",
            EquipGroup.entries.toSet(),
            CatalogCategory.entries.map { it.equipGroup }.toSet(),
        )
    }
}
