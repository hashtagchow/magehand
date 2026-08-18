package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
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

        rows.forEachIndexed { index, row ->
            if (row.label.isBlank()) add(LocalCharacterFormError.RowLabelRequired(index))
            if (row.total !in row.kind.totalRange()) {
                add(LocalCharacterFormError.RowTotalOutOfRange(index, row.kind))
            }
        }
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

        fun LocalRowKind.totalRange(): IntRange =
            if (this == LocalRowKind.ITEM) ITEM_QUANTITY_RANGE else COUNTED_TOTAL_RANGE
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
    /** `null` for a row the player just added; the row's id when editing an existing one. */
    val id: String? = null,
    val kind: LocalRowKind,
    val label: String = "",
    val total: Int = 1,
    /** `null` is "none" — 09 decision 4's third reset option. */
    val reset: ResetRule? = null,
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
