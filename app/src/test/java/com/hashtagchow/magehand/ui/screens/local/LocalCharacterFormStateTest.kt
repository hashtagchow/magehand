package com.hashtagchow.magehand.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.local.LocalCharacterForm
import com.hashtagchow.magehand.core.data.local.LocalCharacterFormError
import com.hashtagchow.magehand.core.data.local.LocalRowForm
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The creation/edit form's **rendering states** (docs/design/09-local-characters.md decision 4).
 *
 * What is asserted here is the half `:core:data` cannot: that a half-typed screen becomes the
 * right `LocalCharacterForm`, and that every rule the validator can break has a message
 * pointing at the field that broke it. `LocalCharacterFormTest` in `:core:data` owns the rules
 * themselves; this owns which box turns red.
 *
 * The `R.string` ids are compile-time constants, so this needs no Android runtime — asserting
 * *which* resource is chosen is the testable part, and the wording lives in `strings.xml`.
 */
class LocalCharacterFormStateTest {

    private val valid = LocalCharacterFormState(
        name = "Sabriel",
        level = "5",
        maxHp = "38",
        armorClass = "16",
    )

    // --- state → form -------------------------------------------------------

    @Test
    fun `a filled form becomes the character the player typed`() {
        val form = valid
            .copy(abilities = valid.abilities + (Ability.STR to "16") + (Ability.DEX to "7"))
            .toForm()

        assertEquals("Sabriel", form.name)
        assertEquals(5, form.level)
        assertEquals(38, form.maxHp)
        assertEquals(16, form.armorClass)
        assertEquals(16, form.abilities.strength)
        assertEquals(7, form.abilities.dexterity)
        // The four the player did not touch keep the 10 the form opened with.
        assertEquals(AbilityScores.DEFAULT, form.abilities.constitution)
    }

    @Test
    fun `a blank level is not given, which is the one thing that is allowed to be`() {
        assertNull(valid.copy(level = "").toForm().level)
        assertTrue(valid.copy(level = "").errors.isEmpty())
    }

    @Test
    fun `a blank number is an error, never a silently substituted default`() {
        // The whole reason `toFormInt` uses a sentinel below every range rather than 0: a
        // cleared AC box must not save as AC 0, and a cleared max-HP box must not save as 1.
        val cleared = valid.copy(maxHp = "", armorClass = "", showErrors = true)

        assertTrue(cleared.errors.contains(LocalCharacterFormError.MaxHpTooLow))
        assertTrue(cleared.errors.contains(LocalCharacterFormError.ArmorClassOutOfRange))
        assertEquals(R.string.local_error_max_hp, cleared.maxHpErrorRes)
        assertEquals(R.string.local_error_armor_class, cleared.armorClassErrorRes)
    }

    @Test
    fun `a blank ability box is out of range rather than a 10`() {
        val cleared = valid.copy(
            abilities = valid.abilities + (Ability.WIS to ""),
            showErrors = true,
        )

        assertEquals(R.string.local_error_ability, cleared.abilityErrorRes(Ability.WIS))
        // And only that one: an empty box is not a reason to shout at the other five.
        assertNull(cleared.abilityErrorRes(Ability.STR))
    }

    // --- when the messages are allowed on screen ----------------------------

    @Test
    fun `nothing is red before the first save attempt`() {
        val untouched = LocalCharacterFormState()

        // It is genuinely invalid — the name is blank — but the player has not done anything
        // yet, and painting the screen red for opening it is telling them off for arriving.
        assertTrue(untouched.errors.contains(LocalCharacterFormError.NameRequired))
        assertTrue(untouched.visibleErrors.isEmpty())
        assertNull(untouched.nameErrorRes)
    }

    @Test
    fun `after a save attempt the messages are live and clear as they are fixed`() {
        val attempted = LocalCharacterFormState(showErrors = true)
        assertEquals(R.string.local_error_name, attempted.nameErrorRes)

        val fixed = attempted.copy(name = "Sabriel")
        assertNull(fixed.nameErrorRes)
        assertTrue(fixed.errors.isEmpty())
    }

    @Test
    fun `a name of nothing but spaces is still nameless`() {
        assertEquals(
            R.string.local_error_name,
            LocalCharacterFormState(name = "   ", showErrors = true).nameErrorRes,
        )
    }

    @Test
    fun `an out-of-range level points at the level box and nothing else`() {
        val state = valid.copy(level = "21", showErrors = true)

        assertEquals(R.string.local_error_level, state.levelErrorRes)
        assertNull(state.nameErrorRes)
        assertNull(state.maxHpErrorRes)
    }

    // --- rows ---------------------------------------------------------------

    @Test
    fun `a row's messages point at that row and not at its neighbour`() {
        val state = valid.copy(
            rows = listOf(
                LocalRowFormState(kind = LocalRowKind.SLOT, label = "1st Level", total = "4"),
                LocalRowFormState(kind = LocalRowKind.RESOURCE, label = "", total = "2"),
            ),
            showErrors = true,
        )

        assertNull(state.rowLabelErrorRes(0))
        assertEquals(R.string.local_error_row_label, state.rowLabelErrorRes(1))
    }

    @Test
    fun `removing a row moves the messages with it`() {
        // The indices are positional, so this is the case that would silently point every
        // message below a removal at the wrong field.
        val state = valid.copy(
            rows = listOf(
                LocalRowFormState(kind = LocalRowKind.SLOT, label = "1st Level", total = "4"),
                LocalRowFormState(kind = LocalRowKind.RESOURCE, label = "", total = "2"),
            ),
            showErrors = true,
        )
        val afterRemovingTheGoodOne = state.copy(rows = state.rows.drop(1))

        assertEquals(R.string.local_error_row_label, afterRemovingTheGoodOne.rowLabelErrorRes(0))
        assertNull(afterRemovingTheGoodOne.rowLabelErrorRes(1))
    }

    @Test
    fun `a slot with no charges is an error and an item with none is not`() {
        val slot = valid.copy(
            rows = listOf(LocalRowFormState(kind = LocalRowKind.SLOT, label = "1st Level", total = "0")),
            showErrors = true,
        )
        val item = valid.copy(
            rows = listOf(LocalRowFormState(kind = LocalRowKind.ITEM, label = "Potion", total = "0")),
            showErrors = true,
        )

        assertEquals(R.string.local_error_row_total, slot.rowTotalErrorRes(0))
        // "You have run out of potions" is a real state; "a slot with zero charges" is not a
        // row. Two ranges, and therefore two messages.
        assertNull(item.rowTotalErrorRes(0))
    }

    @Test
    fun `an item's out-of-range message is the quantity one, not the total one`() {
        val state = valid.copy(
            rows = listOf(LocalRowFormState(kind = LocalRowKind.ITEM, label = "Potion", total = "")),
            showErrors = true,
        )

        assertEquals(R.string.local_error_row_quantity, state.rowTotalErrorRes(0))
    }

    @Test
    fun `a reset rule is kept while editing but only saved on a resource or an action`() {
        val row = LocalRowFormState(
            kind = LocalRowKind.SLOT,
            label = "1st Level",
            total = "4",
            reset = ResetRule.SHORT_REST,
        )

        // 09 decision 4 gives the reset rule to resources; slots and items never save one…
        assertNull(row.toRowForm().reset)
        // …but flipping the kind back must not have lost what the player picked.
        assertEquals(ResetRule.SHORT_REST, row.copy(kind = LocalRowKind.RESOURCE).toRowForm().reset)
        // H2 [architect ruling]: 18 decision 1 extends the same vocabulary to an action's uses —
        // the reset chips are un-gated on the editor for exactly this reason.
        assertEquals(ResetRule.SHORT_REST, row.copy(kind = LocalRowKind.ACTION).toRowForm().reset)
    }

    @Test
    fun `a category is kept while editing but only saved on an item`() {
        // 13 decision 9's chooser, in exactly the shape the reset rule has above — which is the
        // point of the test being written as its twin. A slot that kept claiming to be a sword
        // would put a tracker row in the inventory's Weapons section.
        val row = LocalRowFormState(
            kind = LocalRowKind.SLOT,
            label = "1st Level",
            total = "4",
            category = CatalogCategory.WEAPON,
        )

        assertEquals(CatalogCategory.GEAR, row.toRowForm().category)
        assertEquals(
            CatalogCategory.WEAPON,
            row.copy(kind = LocalRowKind.ITEM).toRowForm().category,
        )
    }

    @Test
    fun `a row form opens on Gear and round-trips whatever it was given`() {
        assertEquals(CatalogCategory.GEAR, LocalRowFormState.new(LocalRowKind.ITEM).category)
        assertEquals(
            CatalogCategory.ARMOR,
            LocalRowFormState.from(
                LocalRowForm(
                    kind = LocalRowKind.ITEM,
                    label = "Chain Shirt",
                    category = CatalogCategory.ARMOR,
                ),
            ).category,
        )
    }

    @Test
    fun `a row's number field is capped by its own kind's range`() {
        assertEquals(
            LocalCharacterForm.COUNTED_TOTAL_RANGE,
            LocalRowFormState(kind = LocalRowKind.RESOURCE).totalRange,
        )
        assertEquals(
            LocalCharacterForm.ITEM_QUANTITY_RANGE,
            LocalRowFormState(kind = LocalRowKind.ITEM).totalRange,
        )
    }

    // --- the edit case ------------------------------------------------------

    @Test
    fun `reopening a saved character round-trips through the form`() {
        // "The form is the editor" (09 decision 4) is only true if `formFor` → state → form
        // is the identity. Anything lost here would be silently dropped on the next save.
        val stored = LocalCharacterForm(
            id = "local-1",
            name = "Sabriel",
            level = 5,
            abilities = AbilityScores(strength = 16, dexterity = 7, constitution = 14),
            maxHp = 38,
            armorClass = 16,
            rows = listOf(
                LocalRowForm(id = "row-1", kind = LocalRowKind.SLOT, label = "1st Level", total = 4),
                LocalRowForm(
                    id = "row-2",
                    kind = LocalRowKind.RESOURCE,
                    label = "Rage",
                    total = 3,
                    reset = ResetRule.LONG_REST,
                ),
                LocalRowForm(id = "row-3", kind = LocalRowKind.ITEM, label = "Potion", total = 2),
            ),
        )

        assertEquals(stored, LocalCharacterFormState.from(stored).toForm())
    }

    @Test
    fun `an edit keeps the ids so a save updates rather than duplicating`() {
        val stored = LocalCharacterForm(
            id = "local-1",
            name = "Sabriel",
            rows = listOf(LocalRowForm(id = "row-1", kind = LocalRowKind.SLOT, label = "1st", total = 1)),
        )
        val state = LocalCharacterFormState.from(stored)

        assertTrue(state.isEditing)
        assertEquals("local-1", state.toForm().id)
        assertEquals(listOf("row-1"), state.toForm().rows.map { it.id })
    }

    /**
     * FR-29 changed half of what this used to assert, and the half it changed is worth stating.
     *
     * The **character** still has no id until it is saved — `form.id == null` is what tells
     * `LocalCharacterRepository.save` this is a create rather than an edit, and that is unchanged
     * and load-bearing.
     *
     * A freshly added **row** now does have one, minted by [LocalRowFormState.new]. That is not a
     * relaxation: 18 decision 1's cost is a reference from one row of this form to another, and a
     * reference needs a referent — before the change, an action and the resource it spends, added
     * in the same sitting, could not be wired together without a save-and-reopen. See
     * `LocalRowFormState.new` for the argument, and `LocalRowForm.id` for why storage cannot tell
     * the two mints apart.
     */
    @Test
    fun `a freshly created form is not an edit, though its rows already carry ids`() {
        val state = LocalCharacterFormState().copy(
            rows = listOf(LocalRowFormState.new(LocalRowKind.RESOURCE)),
        )

        assertFalse(state.isEditing)
        assertNull(state.toForm().id)
        assertNotNull(
            "FR-29: a new row needs an id a cost can name",
            state.toForm().rows.single().id,
        )
    }

    @Test
    fun `the form opens on the defaults 09 decision 4 names`() {
        val fresh = LocalCharacterFormState()

        assertEquals(List(6) { AbilityScores.DEFAULT.toString() }, Ability.entries.map { fresh.abilities[it] })
        assertEquals(AbilityScores.DEFAULT, fresh.toForm().abilities.strength)
        assertEquals(LocalCharacterForm.DEFAULT_ARMOR_CLASS, fresh.toForm().armorClass)
        // Name is the only required field, and it is the only thing wrong with a fresh form.
        assertEquals(listOf(LocalCharacterFormError.NameRequired), fresh.errors)
        assertNotNull(fresh.copy(showErrors = true).nameErrorRes)
    }

    // --- FR-29: the action row's fields and the cost picker (18 decisions 1-2) ---

    private fun rageAndAction(
        costRowId: String? = "rage",
        costAmount: String = "2",
        description: String = "Advantage on Strength checks.",
    ) = LocalCharacterFormState(id = "local-1").copy(
        rows = listOf(
            LocalRowFormState(id = "rage", kind = LocalRowKind.RESOURCE, label = "Rage", total = "3"),
            LocalRowFormState(
                id = "act",
                kind = LocalRowKind.ACTION,
                label = "Enter Rage",
                total = "2",
                description = description,
                costRowId = costRowId,
                costAmount = costAmount,
            ),
        ),
    )

    /**
     * The picker offers the character's **other** non-action rows, and nothing else.
     *
     * Decision 1's *"lists the character's other rows (slots/resources/items)"* plus decision 2's
     * chaining fence. Not offering an action is the first half of enforcing that fence; the
     * validator refusing one is the second, and both exist because a picker is a suggestion while
     * a validator is a guarantee.
     */
    @Test
    fun `the cost picker offers other rows and never another action`() {
        val state = rageAndAction().let { s ->
            s.copy(
                rows = s.rows + listOf(
                    LocalRowFormState(id = "potion", kind = LocalRowKind.ITEM, label = "Potion", total = "2"),
                    LocalRowFormState(id = "other", kind = LocalRowKind.ACTION, label = "Dodge", total = "0"),
                ),
            )
        }

        val options = state.costOptions(index = 1).map { it.id }

        assertEquals(listOf("rage", "potion"), options)
        assertFalse("decision 2: a cost row cannot itself be an action", "other" in options)
        assertFalse("and an action never costs itself", "act" in options)
    }

    /**
     * FR-29's three fields survive a trip through the kind chooser and back, on the state — and
     * are dropped on the way *out*, for [reset]'s and [category]'s reason exactly.
     *
     * That split is what lets a player flip a row to a slot and back without losing what they had
     * typed, while what reaches the database is only ever what the kind allows.
     */
    @Test
    fun `an action's fields are kept on the state and dropped for every other kind`() {
        val action = rageAndAction().rows[1]

        with(action.toRowForm()) {
            assertEquals("Advantage on Strength checks.", description)
            assertEquals("rage", costRowId)
            assertEquals(2, costAmount)
        }

        with(action.copy(kind = LocalRowKind.RESOURCE).toRowForm()) {
            assertNull(description)
            assertNull(costRowId)
            assertNull(costAmount)
        }
        // …and the state still holds them, so switching back restores what was typed.
        assertEquals("rage", action.copy(kind = LocalRowKind.RESOURCE).costRowId)
    }

    /**
     * The pair moves together: clearing the picker drops the amount whatever the box holds, and a
     * blank amount with a picker set becomes the sentinel so validation catches it.
     *
     * `toFormInt`'s whole design — see its KDoc — is that an empty box fails every range rather
     * than defaulting to something plausible.
     */
    @Test
    fun `no cost row means no amount, and a blank amount is an error rather than a default`() {
        assertNull(rageAndAction(costRowId = null).rows[1].toRowForm().costAmount)

        val blank = rageAndAction(costAmount = "").rows[1].toRowForm()
        assertEquals(INVALID_NUMBER, blank.costAmount)
        assertTrue(
            LocalCharacterFormError.RowCostInvalid(1) in rageAndAction(costAmount = "").toForm().validate(),
        )
    }

    /** The message is only drawn once the player has tried to save — this form's standing rule. */
    @Test
    fun `the cost error is quiet until the first submit`() {
        val bad = rageAndAction(costRowId = "nope")

        assertNull(bad.rowCostErrorRes(1))
        assertNotNull(bad.copy(showErrors = true).rowCostErrorRes(1))
    }

    /** An action's number field is uses, and zero is a legal answer there — see the range. */
    @Test
    fun `an action's total range reaches zero because zero means unlimited`() {
        assertEquals(0, LocalRowFormState(kind = LocalRowKind.ACTION).totalRange.first)
    }

}
