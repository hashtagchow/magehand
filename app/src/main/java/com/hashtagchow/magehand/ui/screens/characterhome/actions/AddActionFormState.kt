package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CatalogSpell
import com.hashtagchow.magehand.core.model.CatalogWeapon
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.NewLocalRowSpec
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The add sheet's form as the *screen* holds it (FR-49,
 * docs/design/20-local-spells-and-attacks.md decision 6).
 *
 * ### One form, and this type is what makes that literally true
 *
 * Decision 6: *"a catalog pick pre-fills the custom form … so there is one form, one validation
 * and one save"*. [of] is the pre-fill and there is no second constructor for the catalog path —
 * a picked entry and a hand-typed one are the same value of this type by the time anything else
 * looks at them, so a rule applied here is applied to both by construction rather than by
 * somebody remembering to apply it twice.
 *
 * ### Why the numbers are `String`s
 *
 * `LocalCharacterFormState`'s argument, unchanged: a player mid-edit clears the uses box, and that
 * is a real state which is not the number zero and is not "unlimited" either — it is *an empty
 * box*. Keeping the typed characters is what lets the field render what was typed and still say
 * what is wrong instead of silently substituting a value nobody chose.
 *
 * [spellLevel] is the exception and is a real `Int?`, because it is not typed: it is chosen from
 * ten chips, so the only two states are "a level" and "not answered yet", and `null` says the
 * second precisely.
 *
 * ### Pure, and on purpose
 *
 * Every rule below is a `val` on a data class with no Compose in it, so `AddActionFormStateTest`
 * can pin the pre-fill and the validation without a Compose harness — the same reason
 * `LocalCharacterFormState` and `AddItemFormState` are shaped this way.
 */
data class AddActionFormState(
    val kind: LocalRowKind,
    val name: String = "",
    /** `null` until the player picks a chip. Required on a spell — see [spellLevelError]. */
    val spellLevel: Int? = null,
    /** Blank is "unlimited", which is what `0` means in storage — see [toSpec]. */
    val uses: String = "",
    val reset: ResetRule? = null,
    val description: String = "",
    val higherLevels: String = "",
    val castingTime: String = "",
    val range: String = "",
    val components: String = "",
    val duration: String = "",
    val concentration: Boolean = false,
    val ritual: Boolean = false,
    val damage: String = "",
    val properties: String = "",
    /** The catalog entry this was pre-filled from, or `null` for a hand-typed row. Never edited. */
    val catalogId: String? = null,
    /**
     * Whether validation messages are on screen yet.
     *
     * False until the first save attempt, then true — `LocalCharacterFormState`'s posture and
     * `AddItemFormState`'s. Painting the level chips red before the player has looked at them
     * would be telling them off for opening the sheet.
     */
    val showErrors: Boolean = false,
) {
    /**
     * The spec this form saves, or `null` when it is not saveable.
     *
     * Blank text fields become `null` rather than empty strings, so a row created here is
     * indistinguishable from one whose field was never offered — the same normalisation
     * `LocalRowFormState.toRowForm` does at the other form's boundary, and the reason
     * `LocalActionBoard` can treat absent and blank identically.
     *
     * A blank **uses** box is `0`, which is the stored "unlimited" ([LocalTrackerRow.total]'s
     * convention) and the right default for a spell: most spells are cast from slots and have no
     * charges of their own. A box with digits in it is that number, and anything else is
     * unreachable because the field filters to digits.
     */
    fun toSpec(): NewLocalRowSpec? {
        val spec = NewLocalRowSpec(
            kind = kind,
            label = name.trim(),
            spellLevel = spellLevel.takeIf { kind == LocalRowKind.SPELL },
            uses = usesValue,
            // Dropped when there is nothing to reset, for the reason the sheet does not draw the
            // chips then: a reset rule on an unlimited row is a claim about a limit that does not
            // exist. The state keeps what the player picked, so lowering the uses to zero and
            // raising them again does not lose it.
            reset = reset.takeIf { usesValue > 0 },
            description = description.blankToNull(),
            // Dropped off the kind that cannot mean them, exactly as the editor's row form drops
            // `reset` and `category`: a row switched between the two kinds mid-edit keeps what the
            // player typed *on screen*, while what gets saved is only ever what the kind allows.
            higherLevels = higherLevels.blankToNull().takeIf { isSpell },
            castingTime = castingTime.blankToNull().takeIf { isSpell },
            range = range.blankToNull().takeIf { isSpell },
            components = components.blankToNull().takeIf { isSpell },
            duration = duration.blankToNull().takeIf { isSpell },
            concentration = isSpell && concentration,
            ritual = isSpell && ritual,
            damage = damage.blankToNull().takeIf { !isSpell },
            properties = properties.blankToNull().takeIf { !isSpell },
            catalogId = catalogId,
        )
        return spec.takeIf { it.isValid }
    }

    /** Whether the reset chips are drawn at all. See [toSpec] for why they follow the uses. */
    val offersReset: Boolean get() = usesValue > 0

    @get:StringRes
    val nameError: Int?
        get() = R.string.local_error_row_label.takeIf { showErrors && name.isBlank() }

    /**
     * The level chips' message — a spell only, and only about the **missing** case.
     *
     * The out-of-range case cannot be reached: the chips offer 0–9 and nothing else writes this
     * field. `LocalCharacterForm`'s own validator makes the same split and says so.
     */
    @get:StringRes
    val spellLevelError: Int?
        get() = R.string.local_error_row_spell_level.takeIf {
            showErrors && isSpell && spellLevel !in NewLocalRowSpec.SPELL_LEVELS
        }

    @get:StringRes
    val usesError: Int?
        get() = R.string.local_error_row_total.takeIf {
            showErrors && usesValue !in NewLocalRowSpec.USES_RANGE
        }

    private val isSpell: Boolean get() = kind == LocalRowKind.SPELL

    /**
     * The uses as a number: blank is `0` (unlimited), digits are themselves, and **anything that
     * does not fit an `Int` is an error rather than either**.
     *
     * No sentinel for the *blank* case, unlike `LocalCharacterFormState.toFormInt`, and the
     * difference is what the empty box **means**: there, an empty AC box is a field the player
     * cleared and the form must refuse; here, an empty uses box is the ordinary state of the
     * ordinary spell, which casts from slots and has no charges. A sentinel would have made the
     * common case an error.
     *
     * NIT 6 [review, 2026-09-12]: overflow is a *third* case and used to be folded into the first.
     * `"99999999999999"` is all digits, so the field's filter passed it, and `toIntOrNull` returned
     * `null` — which this read as blank, so a row the player asked to have a hundred billion uses
     * saved as **unlimited**, silently, with no error anywhere. It is [INVALID_USES] now, which no
     * range contains, so [usesError] fires; and `AddActionSheet` caps the field at
     * [NewLocalRowSpec.USES_RANGE]'s own width, so reaching it takes a paste rather than typing.
     * Both halves, because the cap is the ergonomics and the sentinel is the guarantee.
     */
    private val usesValue: Int
        get() {
            val typed = uses.trim()
            if (typed.isEmpty()) return 0
            return typed.toIntOrNull() ?: INVALID_USES
        }

    private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        /**
         * A uses box that is neither blank nor a number this app can hold.
         *
         * `Int.MIN_VALUE` for `LocalCharacterFormState`'s `INVALID_NUMBER` reason, restated
         * because this one has a narrower job: it has to fail [NewLocalRowSpec.USES_RANGE], whose
         * floor is `0`, so it cannot be `0` or `-1` and be reliably wrong.
         */
        internal const val INVALID_USES: Int = Int.MIN_VALUE

        /**
         * How many digits the uses box accepts — the width of the range it is validated against.
         *
         * Derived rather than written as `3`, so widening `USES_RANGE` widens the field with it;
         * the local character editor's `NumberField` computes its own `maxDigits` the same way.
         */
        internal val USES_MAX_DIGITS: Int = NewLocalRowSpec.USES_RANGE.last.toString().length

        /**
         * Decision 6's pre-fill, for a spell: every field of the entry, and nothing the row will
         * not keep.
         *
         * The school and the class list are **not** here, matching `NewLocalRowSpec.ofSpell` —
         * they are how a player recognises the entry in the picker, not facts about the row. A
         * future reader adding them should have to delete a line that says so.
         */
        fun of(entry: CatalogSpell): AddActionFormState = AddActionFormState(
            kind = LocalRowKind.SPELL,
            name = entry.name,
            spellLevel = entry.level,
            description = entry.description,
            higherLevels = entry.higherLevels.orEmpty(),
            castingTime = entry.castingTime,
            range = entry.range,
            components = entry.components,
            duration = entry.duration,
            concentration = entry.concentration,
            ritual = entry.ritual,
            catalogId = entry.id,
        )

        /**
         * The pre-fill for a weapon.
         *
         * The mastery is folded into [properties] as a `Mastery: X` term rather than carried
         * separately, which is `NewLocalRowSpec.ofAttack`'s arrangement and is what gives FR-47's
         * badge one editable source. Reached through that function rather than restated, so the
         * two cannot drift.
         */
        fun of(entry: CatalogWeapon): AddActionFormState {
            val spec = NewLocalRowSpec.ofAttack(entry)
            return AddActionFormState(
                kind = LocalRowKind.ATTACK,
                name = spec.label,
                damage = spec.damage.orEmpty(),
                properties = spec.properties.orEmpty(),
                catalogId = spec.catalogId,
            )
        }
    }
}
