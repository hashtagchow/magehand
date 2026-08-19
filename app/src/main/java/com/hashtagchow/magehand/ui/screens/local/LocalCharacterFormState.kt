package com.hashtagchow.magehand.ui.screens.local

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.local.LocalCharacterForm
import com.hashtagchow.magehand.core.data.local.LocalCharacterForm.Companion.totalRange
import com.hashtagchow.magehand.core.data.local.LocalCharacterFormError
import com.hashtagchow.magehand.core.data.local.LocalRowForm
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The creation/edit form as the *screen* holds it (docs/design/09-local-characters.md
 * decision 4).
 *
 * ### Why every number here is a `String`
 *
 * [LocalCharacterForm]'s own KDoc says it: text-to-number is a UI concern, and this is the UI.
 * A player mid-edit clears the AC box; that is a real state, it is not the number zero, and it
 * is not "AC is null" either — it is *an empty box*. Keeping the typed characters is what lets
 * the field render what was typed, keep the cursor where it was, and still say "AC must be
 * between 0 and 30" instead of silently substituting a value nobody chose.
 *
 * The conversion happens once, in [toForm], and the sentinel it uses for an unparseable field
 * is deliberately far outside every range: an empty box therefore *fails validation* rather
 * than defaulting, which is the whole point of not defaulting.
 *
 * Input is filtered to digits at the field (see `LocalCharacterEditorScreen`), so in practice
 * blank is the only way to reach the sentinel — but the sentinel is what makes that a
 * property of the state rather than a property of the keyboard.
 *
 * ### Why this is a separate type from the one it builds
 *
 * `:core:data` owns the rule; `:app` owns the editing. Merging them would put a half-typed
 * `""` into a type whose job is to be a statement about a character, and would move the
 * "which field is red" question into a module with no `R` class to answer it with.
 */
data class LocalCharacterFormState(
    /** `null` when creating; the character's id when editing. Never edited by the screen. */
    val id: String? = null,
    val name: String = "",
    /** Blank means "not given" — 09 decision 4's one optional field. */
    val level: String = "",
    val abilities: Map<Ability, String> = Ability.entries.associateWith { DEFAULT_ABILITY },
    val maxHp: String = LocalCharacterForm.DEFAULT_MAX_HP.toString(),
    val armorClass: String = LocalCharacterForm.DEFAULT_ARMOR_CLASS.toString(),
    val rows: List<LocalRowFormState> = emptyList(),
    /**
     * Whether validation messages are on screen yet.
     *
     * False until the first save attempt, then true for good — the same posture
     * `CredentialsScreen` takes. Painting six fields red before the player has typed anything
     * would be telling them off for opening the screen; going quiet again once they *have*
     * been shown would hide the one field still wrong.
     */
    val showErrors: Boolean = false,
    /** True while the edit case is still reading `formFor(id)`. */
    val isLoading: Boolean = false,
    /** Set once the save landed, so the screen can navigate away exactly once. */
    val savedId: String? = null,
    /** Set when the id no longer names a character — the screen backs out. */
    val isMissing: Boolean = false,
) {
    val isEditing: Boolean get() = id != null

    /** What [toForm] would be judged as. Empty when the form is saveable. */
    val errors: List<LocalCharacterFormError> get() = toForm().validate()

    /**
     * The errors the screen is allowed to *draw* right now.
     *
     * Split from [errors] so the save path asks the honest question ("is this valid?") and the
     * fields ask the presentational one ("should I be red?") without either having to remember
     * [showErrors].
     */
    val visibleErrors: List<LocalCharacterFormError>
        get() = if (showErrors) errors else emptyList()

    /** The state as `:core:data`'s type. The single conversion point; see the class KDoc. */
    fun toForm(): LocalCharacterForm = LocalCharacterForm(
        id = id,
        name = name,
        // Blank is "not given" and stays `null`; anything else that will not parse becomes the
        // sentinel, so "12x" is out of range rather than quietly absent.
        level = if (level.isBlank()) null else level.toFormInt(),
        abilities = AbilityScores(
            strength = ability(Ability.STR),
            dexterity = ability(Ability.DEX),
            constitution = ability(Ability.CON),
            intelligence = ability(Ability.INT),
            wisdom = ability(Ability.WIS),
            charisma = ability(Ability.CHA),
        ),
        maxHp = maxHp.toFormInt(),
        armorClass = armorClass.toFormInt(),
        rows = rows.map { it.toRowForm() },
    )

    // --- per-field error lookup, for the inline messages -------------------------

    @get:StringRes
    val nameErrorRes: Int?
        get() = R.string.local_error_name.takeIf {
            visibleErrors.contains(LocalCharacterFormError.NameRequired)
        }

    @get:StringRes
    val levelErrorRes: Int?
        get() = R.string.local_error_level.takeIf {
            visibleErrors.contains(LocalCharacterFormError.LevelOutOfRange)
        }

    @StringRes
    fun abilityErrorRes(ability: Ability): Int? =
        R.string.local_error_ability.takeIf {
            visibleErrors.contains(LocalCharacterFormError.AbilityOutOfRange(ability))
        }

    @get:StringRes
    val maxHpErrorRes: Int?
        get() = R.string.local_error_max_hp.takeIf {
            visibleErrors.contains(LocalCharacterFormError.MaxHpTooLow)
        }

    @get:StringRes
    val armorClassErrorRes: Int?
        get() = R.string.local_error_armor_class.takeIf {
            visibleErrors.contains(LocalCharacterFormError.ArmorClassOutOfRange)
        }

    @StringRes
    fun rowLabelErrorRes(index: Int): Int? =
        R.string.local_error_row_label.takeIf {
            visibleErrors.contains(LocalCharacterFormError.RowLabelRequired(index))
        }

    /**
     * The row's total/quantity message.
     *
     * Two strings, because the two ranges are genuinely different rules and not a shared one
     * with different numbers: a slot or resource with zero charges is not a row, while an item
     * you have run out of very much is (09 decision 4 / `LocalCharacterForm`'s two ranges).
     */
    @StringRes
    fun rowTotalErrorRes(index: Int): Int? {
        val kind = rows.getOrNull(index)?.kind ?: return null
        if (!visibleErrors.contains(LocalCharacterFormError.RowTotalOutOfRange(index, kind))) {
            return null
        }
        return if (kind == LocalRowKind.ITEM) {
            R.string.local_error_row_quantity
        } else {
            R.string.local_error_row_total
        }
    }

    private fun ability(ability: Ability): Int =
        abilities[ability]?.toFormInt() ?: AbilityScores.DEFAULT

    companion object {
        val DEFAULT_ABILITY: String = AbilityScores.DEFAULT.toString()

        /** Built from an existing character, for the edit case (`formFor(id)`). */
        fun from(form: LocalCharacterForm): LocalCharacterFormState = LocalCharacterFormState(
            id = form.id,
            name = form.name,
            level = form.level?.toString().orEmpty(),
            abilities = Ability.entries.associateWith { form.abilities.score(it).toString() },
            maxHp = form.maxHp.toString(),
            armorClass = form.armorClass.toString(),
            rows = form.rows.map(LocalRowFormState::from),
        )
    }
}

/** One editable row. Same string-fields reasoning as [LocalCharacterFormState]. */
data class LocalRowFormState(
    /** `null` for a row the player just added; the stored id when editing. */
    val id: String? = null,
    val kind: LocalRowKind = LocalRowKind.RESOURCE,
    val label: String = "",
    val total: String = "1",
    /** `null` is "none". Only meaningful for [LocalRowKind.RESOURCE] — see [toRowForm]. */
    val reset: ResetRule? = null,
    /**
     * What the item is (FR-10b, 13 decision 9). Only meaningful for [LocalRowKind.ITEM] — see
     * [toRowForm], which drops it for the other two exactly as it drops [reset].
     */
    val category: CatalogCategory = CatalogCategory.GEAR,
) {
    fun toRowForm(): LocalRowForm = LocalRowForm(
        id = id,
        kind = kind,
        label = label,
        total = total.toFormInt(),
        // 09 decision 4 gives the reset rule to resources only. Dropping it *here* rather than
        // clearing it when the kind changes means switching a resource to a slot and back does
        // not lose the rule the player picked, while what gets saved is still only ever what
        // the kind allows.
        reset = reset.takeIf { kind == LocalRowKind.RESOURCE },
        // The same shape for the same reason: a row switched to a slot and back keeps the
        // category the player picked on screen, while a slot never saves one. The repository
        // forces it back to gear as well — a rule enforced at the state *and* at the write, per
        // this app's habit with anything that reaches the database.
        category = if (kind == LocalRowKind.ITEM) category else CatalogCategory.GEAR,
    )

    /** The valid range for this row's number, so the field can cap what it accepts. */
    val totalRange: IntRange get() = kind.totalRange()

    companion object {
        fun from(row: LocalRowForm): LocalRowFormState = LocalRowFormState(
            id = row.id,
            kind = row.kind,
            label = row.label,
            total = row.total.toString(),
            reset = row.reset,
            category = row.category,
        )

        /** A freshly added row of [kind], with 09 decision 4's starting values. */
        fun new(kind: LocalRowKind): LocalRowFormState = LocalRowFormState(
            kind = kind,
            total = if (kind == LocalRowKind.ITEM) "1" else "1",
        )
    }
}

/**
 * A typed number, or a value no range contains.
 *
 * `Int.MIN_VALUE` and not `0` or `-1`: the sentinel has to fail **every** range in
 * [LocalCharacterForm], and an item's quantity range starts at 0 while an ability's starts at
 * 3. Only a value below all of them makes "the box is empty" reliably an error rather than
 * accidentally a legal value for one field and not another.
 */
internal fun String.toFormInt(): Int = trim().toIntOrNull() ?: INVALID_NUMBER

internal const val INVALID_NUMBER: Int = Int.MIN_VALUE
