package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion
import com.hashtagchow.magehand.core.data.settings.InventorySortDirection
import com.hashtagchow.magehand.core.model.InventoryItem

/**
 * FR-35's ordering, as one pure function over one section's items.
 *
 * Pure and separate from both the composable and the view model, for [InventoryLayoutPlan]'s
 * reason exactly: the awkward part of a sorting feature is the *comparator*, and it is the part
 * no screenshot and no manual pass would catch getting subtly wrong — a tie-break that is not
 * stable makes a list reshuffle on every sync, and nobody reports that as a bug, they report that
 * the app "keeps moving my stuff".
 *
 * ### Where it is applied, and where it is not (decision 1)
 *
 * **Within a section, and only within a section.** `toInventoryUiState` calls this once per
 * section and once per container, on the list of items that section is about to draw. The
 * *sections themselves* keep [InventoryLayoutPlan]'s order — FR-13's default as FR-14 rearranged
 * it — because that order is a thing the player arranged by hand and a sort criterion has nothing
 * to say about it.
 *
 * The **wallet is exempt**, and it is exempt by construction rather than by a branch: its four
 * rows are built by `Wallet.toUiState`, which never comes through here. Coins are denominations
 * in a fixed order, not items, and there is no reading of "sort by value descending" that should
 * put copper above platinum.
 *
 * Gear's folded-in rows (12 decision 3's invariant) are sorted **together with** Gear's own,
 * because by the time this runs they are one list. That is the right answer rather than an
 * accident: the reason `toInventoryUiState` appends folded rows after Gear's own is that the
 * player's untouched section should not reshuffle when an unrelated one is folded — and once the
 * player has asked for an explicit order, they have asked for it over everything in the section.
 */
object InventorySortPlan {

    /**
     * [items] in the order [sort] asks for.
     *
     * Returns the list **unchanged and identical** under [InventorySortCriterion.DEFAULT] — not
     * sorted by `sortOrder`, not copied, not touched. That is what makes *"Default renders exactly
     * what every build before FR-35 rendered"* a property of the code rather than a claim about
     * a comparator agreeing with the source: the two boards produce their lists in the order they
     * mean (DiceCloud's `order`, a local character's `sortIndex` then label), and re-deriving that
     * here would be a second opinion about an order already decided — the same mistake
     * `Wallet.toUiState`'s KDoc refuses to make.
     */
    fun sorted(items: List<InventoryItem>, sort: InventorySort): List<InventoryItem> =
        if (sort.criterion == InventorySortCriterion.DEFAULT) items else items.sortedWith(comparator(sort))

    /**
     * The comparator for a non-default [sort].
     *
     * ### The three keys, in order: criterion, then name, then sheet order
     *
     * Decision 2's tie-break, and each link earns its place. The **criterion** is what was asked
     * for. **Name** breaks its ties because a section of six torches at 1 lb each has to have
     * *some* order and alphabetical is the one a reader can predict. **Sheet order** breaks what
     * is left, and it is what makes the result *stable*: two items with the same weight and the
     * same name (a sheet is allowed to carry "Torch" twice) would otherwise be free to swap places
     * on every rebuild of the board, which happens on every sync.
     *
     * Kotlin's `sortedWith` is a stable sort, so the last key is belt and braces — and it is here
     * anyway, because stability guarantees only that *equal elements keep their input order*, and
     * the input order is exactly the thing a future refactor of the boards is entitled to change.
     *
     * ### The direction applies to the criterion alone
     *
     * "Weight, descending" means *heaviest first*, and then — among things that weigh the same —
     * A to Z, because a player who asked for the heaviest items to be at the top did not thereby
     * ask for the alphabet to run backwards inside each weight. This is the ordinary behaviour of
     * every file manager's column sort, and the alternative (reversing the whole comparator)
     * produces a list whose tie-breaks look scrambled for a reason nothing on screen explains.
     *
     * [InventorySortCriterion.NAME] descending is the case where this is invisible: the primary
     * key and the first tie-break are the same field, so the names run Z to A and only the
     * sheet-order tail stays ascending.
     *
     * ### The numbers are the ones the row prints
     *
     * `totalWeightLb` and `totalValueGp` — the **stack**, not the unit. A row already shows the
     * stack's weight (`InventoryRowState.stackWeightLabel`), so sorting by anything else would
     * order the list by a number the player cannot see: twenty arrows at 0.05 lb would sit above
     * a 4 lb quarterstaff on a list that plainly reads "1 lb" and "4 lb".
     *
     * Both model properties already read an absent measurement as zero
     * (`InventoryItem.totalWeightLb`, `totalValueGp`), which is decision 2's *"absent weight/value
     * sorts as 0"* obtained from the arithmetic that was already there rather than from a second
     * null-handling rule. Note what that means and what it does not: an item the sheet gave no
     * weight sorts *with* the weightless, at the light end of an ascending list. It is not a claim
     * that the item is weightless — the row still prints an em dash, and 11 decision 6 keeps that
     * distinction where it is visible — it is that there is no other number available to sort by,
     * and dropping such items to the bottom regardless of direction would be inventing a third
     * ordering rule for the sheets that happen to be sloppy.
     */
    private fun comparator(sort: InventorySort): Comparator<InventoryItem> {
        val primary: Comparator<InventoryItem> = when (sort.criterion) {
            // Unreachable — `sorted` returns early — and expressed as the identity rather than as
            // an `error()` so that a future caller reaching this with DEFAULT gets the unsorted
            // list it meant, not a crash on the inventory tab.
            InventorySortCriterion.DEFAULT -> Comparator { _, _ -> 0 }
            InventorySortCriterion.NAME -> byName
            InventorySortCriterion.WEIGHT -> compareBy { it.totalWeightLb }
            InventorySortCriterion.VALUE -> compareBy { it.totalValueGp }
        }
        val directed =
            if (sort.direction == InventorySortDirection.DESCENDING) primary.reversed() else primary
        // The two tie-breaks are appended *after* the reversal, so they stay ascending. See above.
        return directed.then(byName).thenBy { it.sortOrder }
    }

    /**
     * Names compared **case-insensitively**, and deliberately not through a `Collator`.
     *
     * Case-insensitive because a sheet is hand-typed: "arrows" filed after "Torch" because a lower
     * case `a` sorts above every capital in code-point order is a list the player would read as
     * broken. Not a locale `Collator` for `formatAmount`'s reason — this app's one ordering rule
     * should be the same on every device, and a comparator that changed with the system locale
     * would make a golden and an emulator probe disagree about a list neither of them changed.
     */
    private val byName: Comparator<InventoryItem> =
        Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
}
