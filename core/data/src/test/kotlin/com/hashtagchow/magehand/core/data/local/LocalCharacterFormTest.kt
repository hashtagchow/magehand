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

    @Test
    fun `the item range is the only one that reaches zero`() {
        assertEquals(0, LocalRowKind.ITEM.totalRange().first)
        assertEquals(1, LocalRowKind.SLOT.totalRange().first)
        assertEquals(1, LocalRowKind.RESOURCE.totalRange().first)
    }
}
