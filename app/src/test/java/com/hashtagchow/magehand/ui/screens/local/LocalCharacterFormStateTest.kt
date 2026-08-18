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
    fun `a reset rule is kept while editing but only saved on a resource`() {
        val row = LocalRowFormState(
            kind = LocalRowKind.SLOT,
            label = "1st Level",
            total = "4",
            reset = ResetRule.SHORT_REST,
        )

        // 09 decision 4 gives the reset rule to resources only…
        assertNull(row.toRowForm().reset)
        // …but flipping the kind back must not have lost what the player picked.
        assertEquals(ResetRule.SHORT_REST, row.copy(kind = LocalRowKind.RESOURCE).toRowForm().reset)
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

    @Test
    fun `a freshly created form is not an edit and its rows have no ids yet`() {
        val state = LocalCharacterFormState().copy(
            rows = listOf(LocalRowFormState.new(LocalRowKind.RESOURCE)),
        )

        assertFalse(state.isEditing)
        assertNull(state.toForm().id)
        assertNull(state.toForm().rows.single().id)
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
}
