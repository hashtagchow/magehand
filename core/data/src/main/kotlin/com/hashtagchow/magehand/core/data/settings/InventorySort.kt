package com.hashtagchow.magehand.core.data.settings

/**
 * How the inventory tab orders the rows **inside** a section (FR-35, ledger decisions 1–2).
 *
 * ### What it does not touch
 *
 * The *sections* keep [InventoryLayoutStore]'s order — FR-13's default arrangement as FR-14
 * rearranged it. Decision 1 is explicit that sorting applies within each section and container,
 * and the two facts are stored beside each other rather than folded together for exactly that
 * reason: a section order is a list of opaque keys the UI minted, and this is one closed
 * vocabulary with a documented default. One value would have had to be parameterised over both.
 *
 * The **wallet is exempt** and cannot reach this: its four rows are coin denominations in
 * [CoinKind.inWalletOrder][com.hashtagchow.magehand.core.model.CoinKind.inWalletOrder], not
 * items, and "sort my money alphabetically" is not a request anybody has. That exemption is
 * enforced where the sort is applied (`toInventoryUiState`) rather than here, because this type
 * describes a preference and not a screen.
 *
 * ### Why it lives in `:core:data` and not in the UI
 *
 * [UiScale]'s reason, whole: the store below persists it, so the store's module has to name it.
 * The *comparator* is `:app`'s, because it orders `InventoryRowState`s — a Compose-layer type —
 * and this module deliberately knows nothing about how a row draws.
 */
data class InventorySort(
    val criterion: InventorySortCriterion = InventorySortCriterion.DEFAULT,
    /**
     * Ascending or descending, and it is carried **even while [criterion] is
     * [InventorySortCriterion.DEFAULT]**, where it has no effect.
     *
     * Not collapsed into a nullable direction, and not reset when the criterion goes back to
     * sheet order: a player who sorts by weight descending, flips to sheet order to check
     * something and flips back expects the descending they chose, not an ascending list they
     * have to fix. The direction is a standing preference about *this player*; the criterion is
     * the question they are currently asking.
     */
    val direction: InventorySortDirection = InventorySortDirection.ASCENDING,
) {
    /**
     * True when this is the arrangement a character who has never touched the control gets.
     *
     * Named rather than compared against [DEFAULT] at each call site so that "nothing to store"
     * and "nothing to apply" are the same question asked once. See
     * [InventoryLayoutStore.setSort], which writes nothing in this state.
     */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        /** Sheet order, ascending — FR-35 decision 4's Reset target, and every new character. */
        val DEFAULT: InventorySort = InventorySort()
    }
}

/**
 * What the rows inside a section are ordered by (FR-35 decision 2).
 *
 * ### Why [DEFAULT] is a value here and not the absence of one
 *
 * Because it is a *choice the player can make* — the first radio in the sheet's group, the one
 * they come back to when they want the sheet's own order again. Modelling it as `null` would
 * have made "I have never sorted" and "I chose the sheet's order" the same stored state, which
 * is fine on disk (they render identically) and wrong in the control, where a radio group with
 * nothing selected is a control in a state its value does not have.
 *
 * ### Why there are exactly these four, and no Quantity
 *
 * The three that are not [DEFAULT] are the three facts a row already prints: its name, what the
 * stack weighs, and what the stack is worth. A criterion the row does not show would sort the
 * list by a number the player cannot see, which reads as the app shuffling their gear. Quantity
 * is deliberately not offered for the same reason the `×n` badge is suppressed at one
 * (`InventoryRowState.showsQuantity`): on almost every sheet it is 1 on almost every row, so it
 * is not an ordering, it is a coin toss with a label on it.
 *
 * @property key the stored string. **Stable — it is on disk on user devices.** Renamed only with
 *   a migration, never casually, exactly as [UiScale.key] is.
 */
enum class InventorySortCriterion(val key: String) {
    /**
     * The order the source gave us: DiceCloud's `order` for a sheet, `sortIndex` for a local
     * character. Nothing is re-ordered at all in this state — see the comparator in `:app` —
     * which is what makes "Default renders exactly what every build before FR-35 rendered" a
     * property of the code rather than a claim.
     */
    DEFAULT("default"),
    NAME("name"),
    WEIGHT("weight"),
    VALUE("value"),
    ;

    companion object {
        /**
         * The stored string back to a criterion, **degrading to [DEFAULT] rather than failing**.
         *
         * [UiScale.fromKey]'s three real inputs, unchanged: `null` (never set), a key from a
         * newer build whose criterion this one does not have, and a corrupted value. All three
         * mean "my inventory should still open", and the only safe answer is the order every
         * build before this feature drew.
         */
        fun fromKey(key: String?): InventorySortCriterion =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Which end of [InventorySortCriterion] a section starts at (FR-35 decision 2's second control).
 *
 * A two-value enum rather than a `Boolean ascending`, for the reason the stored form makes
 * obvious: `"asc"` in a preferences dump says what it is and `"true"` does not, and the value
 * has to be read back by a human debugging a layout with `run-as … strings` (see
 * [InventoryLayoutCodec]'s argument for the same choice).
 */
enum class InventorySortDirection(val key: String) {
    ASCENDING("asc"),
    DESCENDING("desc"),
    ;

    /** The other one — what the sheet's toggle selects. */
    val opposite: InventorySortDirection
        get() = if (this == ASCENDING) DESCENDING else ASCENDING

    companion object {
        /**
         * The stored string back to a direction, degrading to [ASCENDING].
         *
         * [InventorySortCriterion.fromKey]'s rule and its reason. Ascending is the degrade target
         * because it is the direction every one of the three criteria reads as "normal" — A to Z,
         * lightest first, cheapest first — so an unreadable value produces a list the player can
         * still recognise rather than one that looks deliberately backwards.
         */
        fun fromKey(key: String?): InventorySortDirection =
            entries.firstOrNull { it.key == key } ?: ASCENDING
    }
}
