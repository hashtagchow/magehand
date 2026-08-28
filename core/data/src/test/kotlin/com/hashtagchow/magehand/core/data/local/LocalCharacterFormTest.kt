package com.hashtagchow.magehand.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.local.LocalCharacterForm.Companion.totalRange
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The creation form's rules, verbatim from docs/design/09-local-characters.md decision 4:
 * name required (the only required field), level 1–20 optional, abilities 3–30 defaulting to
 * 10, max HP at least 1, AC 0–30.
 *
 * Pure JUnit — validation touches nothing but its own input, which is the point of it being a
 * function on the holder rather than a method on a view model.
 */
class LocalCharacterFormTest {

    private fun valid(
        name: String = "Brambles",
        level: Int? = 3,
        abilities: AbilityScores = AbilityScores.DEFAULTS,
        maxHp: Int = 20,
        armorClass: Int = 15,
        rows: List<LocalRowForm> = emptyList(),
    ) = LocalCharacterForm(
        name = name,
        level = level,
        abilities = abilities,
        maxHp = maxHp,
        armorClass = armorClass,
        rows = rows,
    )

    @Test
    fun `a filled-in form is valid`() {
        assertEquals(emptyList<LocalCharacterFormError>(), valid().validate())
        assertTrue(valid().isValid)
    }

    /** 09 decision 4: name is the *only* required field, so everything else may be default. */
    @Test
    fun `a name and nothing else is enough`() {
        val form = LocalCharacterForm(name = "Brambles")

        assertTrue("defaults must be a valid form: ${form.validate()}", form.isValid)
        assertEquals(AbilityScores.DEFAULTS, form.abilities)
        assertEquals(null, form.level)
    }

    @Test
    fun `an empty or blank name is rejected`() {
        assertTrue(LocalCharacterFormError.NameRequired in valid(name = "").validate())
        assertTrue(
            "a name of spaces is a nameless character",
            LocalCharacterFormError.NameRequired in valid(name = "   ").validate(),
        )
    }

    @Test
    fun `level is optional but bounded when given`() {
        assertTrue(valid(level = null).isValid)
        assertTrue(valid(level = 1).isValid)
        assertTrue(valid(level = 20).isValid)

        assertTrue(LocalCharacterFormError.LevelOutOfRange in valid(level = 0).validate())
        assertTrue(LocalCharacterFormError.LevelOutOfRange in valid(level = 21).validate())
        assertTrue(LocalCharacterFormError.LevelOutOfRange in valid(level = -3).validate())
    }

    @Test
    fun `every ability is bounded to three through thirty, and each reports itself`() {
        assertTrue(valid(abilities = AbilityScores(3, 3, 3, 3, 3, 3)).isValid)
        assertTrue(valid(abilities = AbilityScores(30, 30, 30, 30, 30, 30)).isValid)

        val low = valid(abilities = AbilityScores(strength = 2)).validate()
        assertTrue(LocalCharacterFormError.AbilityOutOfRange(Ability.STR) in low)

        val high = valid(abilities = AbilityScores(charisma = 31)).validate()
        assertTrue(LocalCharacterFormError.AbilityOutOfRange(Ability.CHA) in high)
    }

    @Test
    fun `max HP must be at least one`() {
        assertTrue(valid(maxHp = 1).isValid)
        assertTrue(LocalCharacterFormError.MaxHpTooLow in valid(maxHp = 0).validate())
        assertTrue(LocalCharacterFormError.MaxHpTooLow in valid(maxHp = -5).validate())
    }

    @Test
    fun `armour class is bounded to zero through thirty`() {
        assertTrue(valid(armorClass = 0).isValid)
        assertTrue(valid(armorClass = 30).isValid)
        assertTrue(LocalCharacterFormError.ArmorClassOutOfRange in valid(armorClass = -1).validate())
        assertTrue(LocalCharacterFormError.ArmorClassOutOfRange in valid(armorClass = 31).validate())
    }

    /**
     * Every bad field at once, not the first one: a form screen highlights all of them, and a
     * short-circuiting validator would make the player fix them one round trip at a time.
     */
    @Test
    fun `validation reports every problem rather than stopping at the first`() {
        val errors = valid(
            name = "",
            level = 99,
            abilities = AbilityScores(strength = 1),
            maxHp = 0,
            armorClass = 50,
        ).validate()

        assertTrue(LocalCharacterFormError.NameRequired in errors)
        assertTrue(LocalCharacterFormError.LevelOutOfRange in errors)
        assertTrue(LocalCharacterFormError.AbilityOutOfRange(Ability.STR) in errors)
        assertTrue(LocalCharacterFormError.MaxHpTooLow in errors)
        assertTrue(LocalCharacterFormError.ArmorClassOutOfRange in errors)
        assertEquals(5, errors.size)
    }

    // --- rows ---------------------------------------------------------------

    @Test
    fun `a row needs a label`() {
        val errors = valid(
            rows = listOf(LocalRowForm(kind = LocalRowKind.RESOURCE, label = " ", total = 2)),
        ).validate()

        assertTrue(LocalCharacterFormError.RowLabelRequired(0) in errors)
    }

    @Test
    fun `the offending row is identified by its position`() {
        val errors = valid(
            rows = listOf(
                LocalRowForm(kind = LocalRowKind.SLOT, label = "1st Level", total = 4),
                LocalRowForm(kind = LocalRowKind.RESOURCE, label = "", total = 2),
            ),
        ).validate()

        assertTrue(LocalCharacterFormError.RowLabelRequired(1) in errors)
        assertTrue(LocalCharacterFormError.RowLabelRequired(0) !in errors)
    }

    /**
     * A slot or resource with no charges is a row that can never show anything; an item with
     * a quantity of zero is a perfectly ordinary "I am out of potions".
     */
    @Test
    fun `counted rows need at least one charge but an item may be empty`() {
        val emptySlot = valid(
            rows = listOf(LocalRowForm(kind = LocalRowKind.SLOT, label = "1st Level", total = 0)),
        ).validate()
        assertTrue(LocalCharacterFormError.RowTotalOutOfRange(0, LocalRowKind.SLOT) in emptySlot)

        val emptyItem = valid(
            rows = listOf(LocalRowForm(kind = LocalRowKind.ITEM, label = "Potion", total = 0)),
        )
        assertTrue("an item may be empty: ${emptyItem.validate()}", emptyItem.isValid)
    }

    @Test
    fun `a negative quantity is rejected for every kind`() {
        LocalRowKind.entries.forEach { kind ->
            val errors = valid(
                rows = listOf(LocalRowForm(kind = kind, label = "row", total = -1)),
            ).validate()
            assertTrue("$kind accepted a negative total", LocalCharacterFormError.RowTotalOutOfRange(0, kind) in errors)
        }
    }

    @Test
    fun `the three kinds each carry their own reset option`() {
        val form = valid(
            rows = listOf(
                LocalRowForm(kind = LocalRowKind.SLOT, label = "1st Level", total = 4, reset = ResetRule.LONG_REST),
                LocalRowForm(kind = LocalRowKind.RESOURCE, label = "Ki", total = 3, reset = ResetRule.SHORT_REST),
                LocalRowForm(kind = LocalRowKind.ITEM, label = "Potion", total = 2, reset = null),
            ),
        )

        assertTrue(form.isValid)
        assertEquals(
            listOf(ResetRule.LONG_REST, ResetRule.SHORT_REST, null),
            form.rows.map { it.reset },
        )
    }

    /**
     * Two ranges reach zero, and they mean **different things** — which is why they are two
     * constants rather than one shared bound.
     *
     * An item you have run out of is still an item you own. An action whose uses read zero is one
     * nothing counts — 18 decision 1 makes uses optional, and `LocalTrackerRow.total` records that
     * `0` is the storage for "unlimited". Two rules that happen to agree on a bound today are still
     * two rules, and naming them separately is what stops a change to one silently moving the
     * other.
     */
    @Test
    fun `items and actions reach zero, slots and resources do not`() {
        assertEquals(0, LocalRowKind.ITEM.totalRange().first)
        assertEquals(0, LocalRowKind.ACTION.totalRange().first)
        assertEquals(1, LocalRowKind.SLOT.totalRange().first)
        assertEquals(1, LocalRowKind.RESOURCE.totalRange().first)
    }

    // --- FR-29's cost (18 decisions 1 and 2) --------------------------------

    private fun rageAndAction(
        costRowId: String? = "rage",
        costAmount: Int? = 1,
        costTargetKind: LocalRowKind = LocalRowKind.RESOURCE,
    ) = valid(
        rows = listOf(
            LocalRowForm(id = "rage", kind = costTargetKind, label = "Rage", total = 3),
            LocalRowForm(
                id = "act",
                kind = LocalRowKind.ACTION,
                label = "Enter Rage",
                total = 0,
                costRowId = costRowId,
                costAmount = costAmount,
            ),
        ),
    )

    @Test
    fun `an action costing another row is valid`() {
        assertTrue(rageAndAction().isValid)
    }

    @Test
    fun `an action with no cost at all is valid`() {
        assertTrue(rageAndAction(costRowId = null, costAmount = null).isValid)
    }

    /**
     * **Decision 2's v1 fence: a cost row cannot itself be an action.**
     *
     * The picker never offers one, and this is the second half of enforcing it — a picker is a
     * suggestion and a validator is a guarantee. Without it, a chain of actions each costing the
     * next would be storable, and `LocalActionBoard` would render costs whose "available" count
     * was another action's remaining uses: a number that means something entirely different, with
     * no way for a player to see why their Rage says it costs 2 Second Winds.
     */
    @Test
    fun `a cost may not name another action`() {
        val errors = rageAndAction(costTargetKind = LocalRowKind.ACTION).validate()

        assertTrue(LocalCharacterFormError.RowCostInvalid(1) in errors)
    }

    /**
     * The remaining three ways a cost is not a cost. One error covers all four — see
     * [LocalCharacterFormError.RowCostInvalid] for why the message is not split per clause.
     */
    @Test
    fun `half a cost, a zero amount and a missing target are each refused`() {
        assertTrue(
            "an amount with no row",
            LocalCharacterFormError.RowCostInvalid(1) in rageAndAction(costRowId = null).validate(),
        )
        assertTrue(
            "a row with no amount",
            LocalCharacterFormError.RowCostInvalid(1) in rageAndAction(costAmount = null).validate(),
        )
        assertTrue(
            "a cost of nothing is not a cost",
            LocalCharacterFormError.RowCostInvalid(1) in rageAndAction(costAmount = 0).validate(),
        )
        assertTrue(
            "a use that hands the player charges",
            LocalCharacterFormError.RowCostInvalid(1) in rageAndAction(costAmount = -2).validate(),
        )
        assertTrue(
            "a target this character does not have",
            LocalCharacterFormError.RowCostInvalid(1) in rageAndAction(costRowId = "nope").validate(),
        )
    }

    /**
     * Validation reports **every** bad field, not the first — this form's own rule, extended to
     * the new one.
     *
     * A player who typed one bad number and picked one impossible cost should see both, in one
     * round trip, on a screen that highlights fields.
     */
    @Test
    fun `a bad cost and a bad total are both reported`() {
        val errors = valid(
            rows = listOf(
                LocalRowForm(
                    id = "act",
                    kind = LocalRowKind.ACTION,
                    label = "",
                    total = -1,
                    costRowId = "nope",
                    costAmount = 1,
                ),
            ),
        ).validate()

        assertTrue(LocalCharacterFormError.RowLabelRequired(0) in errors)
        assertTrue(LocalCharacterFormError.RowTotalOutOfRange(0, LocalRowKind.ACTION) in errors)
        assertTrue(LocalCharacterFormError.RowCostInvalid(0) in errors)
    }
}
