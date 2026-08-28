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

    /**
     * FR-29's cost message (18 decisions 1 and 2), one string for the four ways a cost can be
     * wrong — see [LocalCharacterFormError.RowCostInvalid] for why it is one and not four.
     */
    @StringRes
    fun rowCostErrorRes(index: Int): Int? =
        R.string.local_error_row_cost.takeIf {
            visibleErrors.contains(LocalCharacterFormError.RowCostInvalid(index))
        }

    /**
     * The rows the cost picker at [index] may offer — 18 decision 1's *"cost picker lists the
     * character's **other** rows (slots/resources/items)"*, with decision 2's chaining fence
     * applied.
     *
     * Three exclusions, and each one is a rule rather than a tidy-up:
     *
     *  - **The row itself**, which is what "other" means. An action that costs itself is a use
     *    that spends its own charge twice.
     *  - **Every other action** — decision 2's v1 fence, *"a cost row cannot itself be an
     *    action"*. Not offering one is the first half of enforcing it; `LocalCharacterForm.
     *    validate` refusing one is the second, and both exist because a picker is a suggestion
     *    while a validator is a guarantee.
     *  - **Rows with no id**, which after FR-29 means rows built by something other than
     *    [LocalRowFormState.new] — nothing in the editor produces one, and a chip pointing at a
     *    row that cannot be named would save a cost the validator then rejects.
     *
     * Blank-labelled rows are kept, deliberately: a row the player has not named yet is a row they
     * are still typing, and dropping it from the picker mid-keystroke would make the list flicker.
     * The chip renders the blank label, which is honest about what they have so far.
     *
     * Pure and on the state so `LocalCharacterFormStateTest` can pin the fence without a Compose
     * runtime — `:app` has no Compose test harness, so a rule that only exists inside a composable
     * is a rule that can only be checked by reading it.
     */
    fun costOptions(index: Int): List<LocalRowFormState> {
        val row = rows.getOrNull(index) ?: return emptyList()
        return rows.filter { it.id != null && it.id != row.id && it.kind != LocalRowKind.ACTION }
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
    /**
     * `null` is "none". Meaningful for [LocalRowKind.RESOURCE] and, per 18 decision 1's reused
     * reset vocabulary, [LocalRowKind.ACTION] — see [toRowForm].
     */
    val reset: ResetRule? = null,
    /**
     * What the item is (FR-10b, 13 decision 9). Only meaningful for [LocalRowKind.ITEM] — see
     * [toRowForm], which drops it for the other two exactly as it drops [reset].
     */
    val category: CatalogCategory = CatalogCategory.GEAR,
    /**
     * The action's prose (FR-29, 18 decision 1). Only meaningful for [LocalRowKind.ACTION] — see
     * [toRowForm], which drops it for the other three exactly as it drops [reset].
     *
     * A `String` and not a `String?`, unlike the [LocalRowForm] field it feeds: this is what a text
     * field holds, and an empty box is `""`. The blank-to-null normalisation happens once, at the
     * boundary, which is this class's whole reason for existing.
     */
    val description: String = "",
    /**
     * The row one use spends from, or `null` for a free action (18 decision 1).
     *
     * `null` is a real, chosen state here rather than an absence — the picker's first chip is "No
     * cost" — which is why it is nullable where [costAmount] is a possibly-empty string: the two
     * fields answer different questions ("is there a cost?" and "how much?").
     */
    val costRowId: String? = null,
    /**
     * How many of [costRowId] one use spends, as typed.
     *
     * Defaulted to `"1"` rather than blank, because one is the answer for almost every cost a
     * player will type and an empty box on a chip they just tapped reads as an error they caused.
     * Ignored entirely while [costRowId] is `null` — see [toRowForm].
     */
    val costAmount: String = "1",
) {
    fun toRowForm(): LocalRowForm = LocalRowForm(
        id = id,
        kind = kind,
        label = label,
        total = total.toFormInt(),
        // 09 decision 4 gives the reset rule to resources; 18 decision 1 extends the same
        // vocabulary to actions' uses. Dropping it *here* rather than clearing it when the kind
        // changes means switching a resource (or action) to a slot and back does not lose the
        // rule the player picked, while what gets saved is still only ever what the kind allows.
        reset = reset.takeIf { kind == LocalRowKind.RESOURCE || kind == LocalRowKind.ACTION },
        // The same shape for the same reason: a row switched to a slot and back keeps the
        // category the player picked on screen, while a slot never saves one. The repository
        // forces it back to gear as well — a rule enforced at the state *and* at the write, per
        // this app's habit with anything that reaches the database.
        category = if (kind == LocalRowKind.ITEM) category else CatalogCategory.GEAR,
        // FR-29's three, dropped off every other kind for `reset`'s and `category`'s reason and by
        // the same shape: a row switched to an action and back keeps what the player typed *on
        // screen*, while what gets saved is only ever what the kind allows. The repository forces
        // the same fence a second time, per this app's habit with anything that reaches the
        // database.
        description = description.takeIf { kind == LocalRowKind.ACTION },
        costRowId = costRowId?.takeIf { kind == LocalRowKind.ACTION },
        // The pair moves together: no cost row means no amount, whatever the box happens to hold.
        // A blank box *with* a cost row becomes the sentinel and fails validation, which is the
        // whole point of `toFormInt` — see its KDoc.
        costAmount = if (kind == LocalRowKind.ACTION && costRowId != null) costAmount.toFormInt() else null,
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
            description = row.description.orEmpty(),
            costRowId = row.costRowId,
            // Falls back to the default rather than to a blank box for an action with no cost:
            // tapping a cost chip should land on "1", not on an empty field the player then has
            // to fill in before the form will save.
            costAmount = row.costAmount?.toString() ?: "1",
        )

        /**
         * A freshly added row of [kind], with 09 decision 4's starting values **and an id**.
         *
         * ### Why the id is minted here rather than at save time
         *
         * FR-29's cost is a reference from one row of this form to another (18 decision 1), and a
         * reference needs something to point at. Before this the id was minted by
         * `LocalCharacterRepository.save`, so a row the player had just added had none until they
         * saved — which meant an action and the resource it spends, added in the same sitting,
         * could not be wired together without a save-and-reopen round trip on the one screen whose
         * premise is that the form *is* the editor (09 decision 4).
         *
         * A UUID, for `LocalOpenCharacter.newRowId`'s reason unchanged: it has to be unique
         * against rows this form cannot see, and it must never look like a Meteor id. `save` still
         * mints one for any row that arrives without one, so nothing about the repository's
         * contract changed — this is a second, earlier place the same kind of id can come from,
         * and storage cannot tell them apart.
         *
         * The cost of a non-deterministic factory is real and is paid where it is cheap: the id is
         * the *only* non-deterministic field, nothing asserts on it, and any test that wants a
         * fixed one builds the state with the constructor.
         */
        fun new(kind: LocalRowKind): LocalRowFormState = LocalRowFormState(
            id = java.util.UUID.randomUUID().toString(),
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
