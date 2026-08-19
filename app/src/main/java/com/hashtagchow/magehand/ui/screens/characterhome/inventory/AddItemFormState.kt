package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CatalogItem
import com.hashtagchow.magehand.core.model.ItemCatalog
import com.hashtagchow.magehand.core.model.NewItemSpec

/**
 * The add-item flow's two halves as state (docs/design/10-inventory.md decision 6): a
 * **filter** over the built-in catalog, and a **custom form** for everything the catalog does
 * not carry.
 *
 * Both are pure, and both are here rather than inside the composable for the reason
 * `LocalCharacterFormState` gives: a half-typed field is a real state that a test can hold
 * still, and "which box is red" is a decision, not a rendering detail.
 */

/**
 * The catalog rows matching [query].
 *
 * ### Why the search is client-side, and why it is not the SRD library
 *
 * There is nothing to go and fetch. 10 decision 6 records the SRD library path as
 * deliberately out of scope because `searchLibraryNodes` cannot find items at all — SRD gear
 * is stored as `reference` nodes carrying no top-level name, so a name search returns nothing
 * for every item in the library. What this searches is [ItemCatalog.entries], thirty-odd
 * entries already in the app, so the whole filter is a `contains` over a list that fits in a
 * cache line. A round trip would be slower and would find less.
 *
 * ### Why tags are searched as well as names
 *
 * Because the SRD's own names are not what a player types. "Arrows (20)" and "Crossbow Bolts
 * (20)" are both *ammunition* and neither contains the word; "rope" finds both ropes by name
 * but "ammo" would find nothing without this. Matching the tag is what makes the search
 * answer the question the player actually asked, and the tags are the app's own (see
 * [ItemCatalog]), so nothing here depends on a sheet's spelling.
 *
 * A blank query returns everything, in the catalog's own alphabetical order — the picker
 * opens as a browsable list, not as an empty box waiting to be earned.
 */
fun catalogMatches(query: String): List<CatalogItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return ItemCatalog.entries
    return ItemCatalog.entries.filter { entry ->
        entry.name.contains(needle, ignoreCase = true) ||
            entry.tags.any { it.contains(needle, ignoreCase = true) }
    }
}

/**
 * The custom-item form (10 decision 6: "name, quantity, weight?, value?, description?").
 *
 * ### Why every number is a `String`
 *
 * The same reason `LocalCharacterFormState` gives, and it is worth restating because the
 * optional fields make it sharper here: a blank weight box is **not** the number zero. Zero
 * is a claim that the item is weightless; blank is the player declining to say, and
 * [NewItemSpec] carries that distinction all the way to the wire, where a null field is
 * *omitted* from the insert body rather than sent as `0`. Storing these as `Double?` and
 * parsing at the keystroke would collapse "" and "0" into the same state on the way past.
 *
 * @param showErrors false until the first save attempt, then true for good — the posture
 *   `LocalCharacterFormState` and `CredentialsScreen` both take. Painting a blank form red
 *   is telling the player off for opening it.
 */
data class AddItemFormState(
    val name: String = "",
    val quantity: String = DEFAULT_QUANTITY.toString(),
    val weight: String = "",
    val value: String = "",
    val description: String = "",
    val showErrors: Boolean = false,
) {
    /** The parsed quantity, or `null` when the box does not hold a usable whole number. */
    private val parsedQuantity: Int? get() = quantity.trim().toIntOrNull()

    /** `null` for a blank box (the player declined to say) — see the class KDoc. */
    private val parsedWeight: Double? get() = weight.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private val parsedValue: Double? get() = value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    val nameIsInvalid: Boolean get() = name.isBlank()

    val quantityIsInvalid: Boolean get() = parsedQuantity == null || parsedQuantity !in QUANTITY_RANGE

    /**
     * Invalid means "there is text in the box and it is not a usable number".
     *
     * A **blank box is valid** — that is what optional means — so this asks two questions in
     * one: did the parse fail on non-empty text, or did it land outside the bound? The bound
     * exists because DiceCloud stores what it is given: a mistyped "1000000" weight would
     * ride onto the sheet and make the capacity line nonsense in DiceCloud's own UI too.
     */
    val weightIsInvalid: Boolean
        get() {
            if (weight.isBlank()) return false
            val parsed = parsedWeight ?: return true
            return parsed !in ZERO..MAX_WEIGHT_LB
        }

    val valueIsInvalid: Boolean
        get() {
            if (value.isBlank()) return false
            val parsed = parsedValue ?: return true
            return parsed !in ZERO..MAX_VALUE_GP
        }

    /** Whether Save would produce anything. Asked by the button; [visibleXError] by the fields. */
    val isValid: Boolean
        get() = !nameIsInvalid && !quantityIsInvalid && !weightIsInvalid && !valueIsInvalid

    @get:StringRes
    val nameError: Int?
        get() = R.string.inventory_error_name.takeIf { showErrors && nameIsInvalid }

    @get:StringRes
    val quantityError: Int?
        get() = R.string.inventory_error_quantity.takeIf { showErrors && quantityIsInvalid }

    @get:StringRes
    val weightError: Int?
        get() = R.string.inventory_error_weight.takeIf { showErrors && weightIsInvalid }

    @get:StringRes
    val valueError: Int?
        get() = R.string.inventory_error_value.takeIf { showErrors && valueIsInvalid }

    /**
     * The spec this form describes, or `null` when it does not describe one yet.
     *
     * `null` rather than a best-effort spec, so an invalid form cannot be saved by a caller
     * that forgot to check [isValid] — the two answers cannot drift apart because there is
     * only one of them.
     *
     * No `catalogId`: this is the typed path by definition, and that field exists precisely
     * to tell "picked Torch from the list" from "typed the word torch" (see [NewItemSpec]).
     * No tags either — the app's `adventuring gear` tag is a claim the catalog can make about
     * its own curated entries and this form cannot make about arbitrary text.
     */
    fun toSpec(): NewItemSpec? {
        if (!isValid) return null
        return NewItemSpec(
            name = name.trim(),
            quantity = parsedQuantity ?: return null,
            weightLb = parsedWeight,
            valueGp = parsedValue,
            description = description.trim().takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        /** One, like every "add a thing" form in the app. Bundles come from the catalog. */
        const val DEFAULT_QUANTITY: Int = 1

        /**
         * The accepted quantity range.
         *
         * The ceiling is not arithmetic caution — it is the coin case. A player with 12,000
         * copper is ordinary, so the bound has to clear that comfortably while still catching
         * a slipped keypress that would otherwise ride onto the sheet.
         */
        val QUANTITY_RANGE: IntRange = 1..999_999

        /** Heavier than any single object a character carries; a typo, not an item. */
        const val MAX_WEIGHT_LB: Double = 10_000.0

        /** Priced past every item in the SRD, including the ones nobody buys. */
        const val MAX_VALUE_GP: Double = 1_000_000.0

        private const val ZERO: Double = 0.0
    }
}
