package com.hashtagchow.magehand.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.LocalCharacter

/**
 * The read-only reference strip (docs/design/09-local-characters.md decision 6).
 *
 * *"level, the six scores with modifiers, AC — HP is already the tracker's first row"*, and
 * the assertions below are one per clause of that sentence plus the arithmetic, which is the
 * part a player would notice at the table and nowhere else.
 */
class LocalReferenceStateTest {

    private fun character(
        level: Int? = 5,
        abilities: AbilityScores = AbilityScores(),
        armorClass: Int = 16,
    ) = LocalCharacter(
        id = "local-1",
        name = "Sabriel",
        level = level,
        abilities = abilities,
        maxHp = 38,
        currentHp = 24,
        armorClass = armorClass,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `the strip carries the level, six scores and AC`() {
        val state = LocalReferenceState.from(
            character(
                abilities = AbilityScores(
                    strength = 16,
                    dexterity = 14,
                    constitution = 15,
                    intelligence = 8,
                    wisdom = 12,
                    charisma = 10,
                ),
            ),
        )!!

        assertEquals("Level 5", state.level)
        assertEquals("AC 16", state.armorClass)
        assertEquals(6, state.abilities.size)
    }

    @Test
    fun `the scores are in the order every character sheet prints them`() {
        val state = LocalReferenceState.from(character())!!

        assertEquals(
            listOf(Ability.STR, Ability.DEX, Ability.CON, Ability.INT, Ability.WIS, Ability.CHA),
            state.abilities.map { it.ability },
        )
        assertEquals(listOf("STR", "DEX", "CON", "INT", "WIS", "CHA"), state.abilities.map { it.label })
    }

    @Test
    fun `modifiers are signed, including the zero`() {
        val state = LocalReferenceState.from(
            character(abilities = AbilityScores(strength = 16, dexterity = 10)),
        )!!

        assertEquals("+3", state.abilities.first { it.ability == Ability.STR }.modifier)
        // "+0" and not "0": a bare zero on a character sheet reads as a missing value.
        assertEquals("+0", state.abilities.first { it.ability == Ability.DEX }.modifier)
    }

    @Test
    fun `an odd score below ten floors rather than truncating`() {
        // The bug this exists to catch: Kotlin's `/` truncates toward zero, so a naive
        // (7 - 10) / 2 gives -1 where 5e says -2 — and every odd score below 10 would be one
        // point too generous, on the screen a player reads mid-combat.
        val state = LocalReferenceState.from(
            character(abilities = AbilityScores(strength = 7, dexterity = 9, constitution = 3)),
        )!!

        assertEquals("−2", state.abilities.first { it.ability == Ability.STR }.modifier)
        assertEquals("−1", state.abilities.first { it.ability == Ability.DEX }.modifier)
        assertEquals("−4", state.abilities.first { it.ability == Ability.CON }.modifier)
    }

    @Test
    fun `every modifier is formatted the same way, across the whole legal range`() {
        (AbilityScores.MIN..AbilityScores.MAX).forEach { score ->
            val expected = (score - 10).floorDiv(2)
            val rendered = LocalReferenceState.formatModifier(expected)

            assertEquals(
                "score $score rendered as $rendered",
                if (expected < 0) "−${-expected}" else "+$expected",
                rendered,
            )
        }
    }

    @Test
    fun `the score itself is printed alongside the modifier`() {
        val state = LocalReferenceState.from(character(abilities = AbilityScores(strength = 16)))!!

        assertEquals("16", state.abilities.first { it.ability == Ability.STR }.score)
    }

    @Test
    fun `a character with no level has no level cell rather than a blank one`() {
        // 09 decision 4 makes level the one optional field; the strip's answer to "not given"
        // is absence, not "Level null" and not "Level 0".
        assertNull(LocalReferenceState.from(character(level = null))!!.level)
    }

    @Test
    fun `there is no strip until the character has loaded`() {
        // Also the deleted-underneath-an-open-tracker case: the board goes EMPTY at the same
        // moment, so the strip must not survive as a header over nothing.
        assertNull(LocalReferenceState.from(null))
    }

    @Test
    fun `hit points are deliberately absent - they are the tracker's first row`() {
        val state = LocalReferenceState.from(character())!!

        // A second copy of HP in a strip above the HP block would go stale the instant the
        // player took damage. Asserted as a property of the type rather than trusted to a
        // KDoc: the strip has exactly three things on it and none of them is HP.
        assertEquals(
            listOf("Level 5", "AC 16"),
            listOfNotNull(state.level, state.armorClass),
        )
        assertEquals(6, state.abilities.size)
    }
}
