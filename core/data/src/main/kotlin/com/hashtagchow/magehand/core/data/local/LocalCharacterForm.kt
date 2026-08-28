package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The creation form's state, per docs/design/09-local-characters.md decision 4.
 *
 * **The form is the editor.** There is no separate sheet editor in 1.1 — editing a character
 * is re-opening this form with its current values (`LocalCharacterRepository.formFor`) and
 * saving it again. That is why [id] is nullable rather than there being two types: `null`
 * means "creating", anything else means "editing that character", and the save path is one
 * transaction either way.
 *
 * ### Why the numeric fields are typed and not strings
 *
 * Text-to-number is a *UI* concern: wave B's field owns the keyboard, the cursor and what an
 * empty box means while the player is mid-edit. Handing this layer `"1"` and `""` would move
 * that state machine into `:core:data` and make "the player cleared the box" and "the player
 * typed something that is not a number" the same value. Typed fields keep this holder a
 * statement about the character, and [level]'s `null` then unambiguously means "not given"
 * — which is the one optional field 09 defines.
 */
data class LocalCharacterForm(
    /** `null` when creating. The character's id when editing. */
    val id: String? = null,
    val name: String = "",
    /** `null` when the player left it blank — the only optional sheet field (09 decision 4). */
    val level: Int? = null,
    val abilities: AbilityScores = AbilityScores.DEFAULTS,
    val maxHp: Int = DEFAULT_MAX_HP,
    val armorClass: Int = DEFAULT_ARMOR_CLASS,
    /** User-added tracker rows, in the order they will render. */
    val rows: List<LocalRowForm> = emptyList(),
) {
    /**
     * Every rule 09 decision 4 states, evaluated together rather than short-circuiting.
     *
     * All of them, not the first one: a form screen highlights every bad field at once, and a
     * validator that stopped at the first would make the player fix them one round trip at a
     * time. Empty means valid.
     */
    fun validate(): List<LocalCharacterFormError> = buildList {
        // "name (required, the only required field)" — blank, not just empty: a name of
        // spaces is a nameless character with a name-shaped value.
        if (name.isBlank()) add(LocalCharacterFormError.NameRequired)

        if (level != null && level !in LEVEL_RANGE) add(LocalCharacterFormError.LevelOutOfRange)

        Ability.entries.forEach { ability ->
            if (abilities.score(ability) !in ABILITY_RANGE) {
                add(LocalCharacterFormError.AbilityOutOfRange(ability))
            }
        }

        // A character with 0 max HP has an HP row that can never be anything but empty.
        if (maxHp < MIN_MAX_HP) add(LocalCharacterFormError.MaxHpTooLow)

        if (armorClass !in ARMOR_CLASS_RANGE) add(LocalCharacterFormError.ArmorClassOutOfRange)

        val rowsById = rows.mapNotNull { row -> row.id?.let { it to row } }.toMap()

        rows.forEachIndexed { index, row ->
            if (row.label.isBlank()) add(LocalCharacterFormError.RowLabelRequired(index))
            if (row.total !in row.kind.totalRange()) {
                add(LocalCharacterFormError.RowTotalOutOfRange(index, row.kind))
            }
            if (!row.costIsValid(rowsById)) add(LocalCharacterFormError.RowCostInvalid(index))
        }
    }

    /**
     * 18 decisions 1 and 2, as one predicate — *"a cost row cannot itself be an action"* plus the
     * two ways a cost can be malformed.
     *
     * A **row-pair** rule, which is why it lives on the form rather than on [LocalRowForm]: it has
     * to look at the row the cost names, and a row does not know about its siblings.
     *
     * Four clauses, and the order is the order they can go wrong in:
     *
     *  1. **No cost** is valid. Decision 1 makes it optional and [ActionCost.FREE] is a real answer.
     *  2. **Half a cost** is not. A row id with no amount, or an amount with no row, is a state
     *     `LocalTrackerRowEntity.toDomain` already normalises away on the way out of storage; the
     *     form refuses to put one *in*, so the normalisation never has to be the thing that saves us.
     *  3. **The amount** must be at least one. A cost of zero is not a cost, and a negative one is
     *     a use that hands the player charges.
     *  4. **The target** must be a row of this character that is not itself an action —
     *     decision 2's v1 fence, and the reason the picker never offers one. Enforced here as well
     *     as in the picker, per this app's habit with anything that reaches the database.
     *
     * A cost naming a row the form has **not saved yet** (`LocalRowForm.id == null`) is rejected by
     * clause 4, and that is correct rather than a gap: an unsaved row has no id for anything to
     * reference, so no cost could have been built against it in the first place — the picker lists
     * stored rows, because those are the only ones that have a name to store.
     */
    private fun LocalRowForm.costIsValid(rowsById: Map<String, LocalRowForm>): Boolean {
        if (costRowId == null && costAmount == null) return true
        if (costRowId == null || costAmount == null) return false
        if (costAmount < COST_AMOUNT_RANGE.first || costAmount > COST_AMOUNT_RANGE.last) return false
        val target = rowsById[costRowId] ?: return false
        return target.kind != LocalRowKind.ACTION
    }

    val isValid: Boolean get() = validate().isEmpty()

    companion object {
        val LEVEL_RANGE: IntRange = 1..20
        val ABILITY_RANGE: IntRange = AbilityScores.MIN..AbilityScores.MAX
        val ARMOR_CLASS_RANGE: IntRange = 0..30

        const val MIN_MAX_HP: Int = 1
        const val DEFAULT_MAX_HP: Int = 1
        const val DEFAULT_ARMOR_CLASS: Int = 10

        /** A slot or resource needs at least one charge to be a row; an item may be empty. */
        val COUNTED_TOTAL_RANGE: IntRange = 1..999
        val ITEM_QUANTITY_RANGE: IntRange = 0..9999

        /**
         * An action's uses, where **zero is the honest "unlimited"** (18 decision 1: uses are
         * optional on an action row).
         *
         * A third range rather than reusing [ITEM_QUANTITY_RANGE], which happens to start at zero
         * for a different reason: an item you have run out of is still an item you own, while an
         * action with no uses is one nothing counts. Two rules that agree on a bound today are
         * still two rules, and the pair of names is what stops a change to one of them silently
         * moving the other. See [LocalTrackerRow.total] for what the stored zero means.
         */
        val ACTION_USES_RANGE: IntRange = 0..999

        /** How many of the cost row one use may spend. At least one, or it is not a cost. */
        val COST_AMOUNT_RANGE: IntRange = 1..999

        fun LocalRowKind.totalRange(): IntRange = when (this) {
            LocalRowKind.ITEM -> ITEM_QUANTITY_RANGE
            LocalRowKind.ACTION -> ACTION_USES_RANGE
            LocalRowKind.SLOT, LocalRowKind.RESOURCE -> COUNTED_TOTAL_RANGE
        }
    }
}

/**
 * One user-added row on the form.
 *
 * [total] carries the item case too — an item's "total" is the quantity the player typed, and
 * keeping one field rather than a second `quantity` is what lets the save path treat the
 * three kinds identically (see `LocalTrackerRow.total` for why the two stay equal for items).
 */
data class LocalRowForm(
    /**
     * The row's id, or `null` for a row that does not have one yet.
     *
     * ### FR-29 changed who mints it, and why that was necessary
     *
     * It used to be `null` for every row the player had just added, with
     * `LocalCharacterRepository.save` minting one at write time. That was fine while nothing on
     * the form referred to a row — and it stopped being fine the moment a cost did: an action
     * added in the same sitting as the resource it spends could not name that resource, because
     * the resource had no name to be given until the form was saved. The player would have had to
     * save, reopen the editor, and only then wire the cost, on the one screen whose whole premise
     * (09 decision 4) is that the form is the editor.
     *
     * So `LocalRowFormState.new` mints the id when the row is added to the form instead. It is a
     * UUID either way — `save` still mints one for any row that arrives without one, which is the
     * path a test or a future importer takes — and the two mints are indistinguishable in storage.
     * See `LocalRowFormState.new` for the argument in full.
     */
    val id: String? = null,
    val kind: LocalRowKind,
    val label: String = "",
    val total: Int = 1,
    /** `null` is "none" — 09 decision 4's third reset option. */
    val reset: ResetRule? = null,
    /**
     * What an item row **is** (13 decision 9's third capture point).
     *
     * Carried on the form rather than left to the row it edits, because the form's save is a
     * whole-row upsert and a field the form does not carry is a field the save cannot preserve —
     * see [LocalCharacterRepository.save]'s "what survives an edit" section, which had to gain a
     * paragraph about exactly that when this arrived.
     *
     * Meaningless for [LocalRowKind.SLOT] and [LocalRowKind.RESOURCE] — the editor draws the
     * chooser on item rows only, and the save path forces it back to gear for the other two,
     * the same way [reset] is dropped for everything but a resource or an action (18 decision 1
     * extends the reset vocabulary to actions' uses). That is what keeps a row that was switched
     * to a slot and back from carrying a stale claim about itself.
     */
    val category: CatalogCategory = CatalogCategory.GEAR,
    /**
     * The action's own prose (18 decision 1's *"description (optional text)"*).
     *
     * **The one field on this form that carries an existing column rather than a new one.** FR-8
     * gave `local_tracker_rows` a nullable `description` for item notes, and the repository has
     * always carried it across an edit *from the stored row* because the form had no field for it.
     * An action's description is a form field, so for an action row the form is authoritative and
     * for an item row the stored value still is — see `LocalCharacterRepository.save`, which is
     * where that fork is written and where it has to be.
     *
     * Meaningless on the other three kinds, and dropped on save exactly as [reset] and [category]
     * are for theirs.
     */
    val description: String? = null,
    /**
     * The row one use spends from, or `null` for a free action (18 decision 1).
     *
     * Carried as the referenced row's id rather than as its index in [LocalCharacterForm.rows]:
     * the list is reorderable and rows are added and removed mid-edit, so an index is a reference
     * that silently re-points at a different row the moment the player drags something. Validated
     * as a pair with [costAmount] — see `LocalCharacterForm.costIsValid`.
     */
    val costRowId: String? = null,
    /** How many of [costRowId] one use spends. `null` exactly when [costRowId] is. */
    val costAmount: Int? = null,
)

/**
 * Why a form is not saveable, one per bad field.
 *
 * Structured rather than pre-formatted, for the same reason [
 * com.hashtagchow.magehand.core.model.TrackerWrite] is: the strings belong in `:app`'s
 * resources, and a localized message assembled here could not be.
 */
sealed interface LocalCharacterFormError {
    data object NameRequired : LocalCharacterFormError
    data object LevelOutOfRange : LocalCharacterFormError
    data class AbilityOutOfRange(val ability: Ability) : LocalCharacterFormError
    data object MaxHpTooLow : LocalCharacterFormError
    data object ArmorClassOutOfRange : LocalCharacterFormError

    /** @param index the row's position in [LocalCharacterForm.rows]. */
    data class RowLabelRequired(val index: Int) : LocalCharacterFormError

    data class RowTotalOutOfRange(val index: Int, val kind: LocalRowKind) : LocalCharacterFormError

    /**
     * The row's cost is not a cost (FR-29, 18 decisions 1 and 2) — see
     * `LocalCharacterForm.costIsValid` for the four ways that can be true.
     *
     * **One error for four clauses**, unlike [RowTotalOutOfRange]'s per-kind split, and the reason
     * is what the player can *do* about it. A bad total is one field to correct and the message
     * names the range. A bad cost is a picker and a number that disagree with each other or with a
     * row that has since been deleted, and every route out of it is the same gesture: re-pick, or
     * clear the cost. Four sentences would be four ways of saying "fix the cost", of which the
     * player would read one.
     */
    data class RowCostInvalid(val index: Int) : LocalCharacterFormError
}

/** What [LocalCharacterRepository.save] did. */
sealed interface LocalSaveResult {
    /** @param id the character's id — freshly minted on a create, unchanged on an edit. */
    data class Saved(val id: String) : LocalSaveResult

    /** Nothing was written. [errors] is [LocalCharacterForm.validate]'s output, never empty. */
    data class Invalid(val errors: List<LocalCharacterFormError>) : LocalSaveResult

    /**
     * Nothing was written: the form names a character ([LocalCharacterForm.id]) that no longer
     * exists.
     *
     * A third result rather than an [Invalid] with an error, because it is not a field the
     * player can fix — no amount of editing makes a deleted character exist again, and
     * `LocalCharacterFormError` is the vocabulary of *"this field is wrong"*, which the form
     * renders next to fields. It is the same condition
     * [LocalCharacterRepository.formFor] already answers with `null`, arriving later: the
     * character was deleted from another entry point, or from a back stack restored after the
     * deletion.
     */
    data object Missing : LocalSaveResult
}
