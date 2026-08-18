package com.hashtagchow.magehand.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 5e ability modifier rule (docs/design/09-local-characters.md decision 6's reference
 * strip renders these; wave B does the rendering, this is the arithmetic).
 *
 * The whole reason this has a test at all is the negative half of the table. `(score − 10) / 2`
 * with Kotlin's integer division truncates *toward zero*, so every odd score below 10 comes
 * out one point too generous — a 7 reads −1 where 5e says −2. That is a bug that looks right
 * on every score a quick manual check would pick (10, 12, 16 all pass either way), so the
 * cases below are chosen to fail loudly if the `floorDiv` is ever "simplified" back.
 */
class AbilityModifierTest {

    /** The official 5e table, both ends of the 3–30 range this app accepts. */
    private val official = mapOf(
        1 to -5,
        2 to -4,
        3 to -4,
        4 to -3,
        5 to -3,
        6 to -2,
        7 to -2,
        8 to -1,
        9 to -1,
        10 to 0,
        11 to 0,
        12 to 1,
        13 to 1,
        14 to 2,
        15 to 2,
        16 to 3,
        17 to 3,
        18 to 4,
        19 to 4,
        20 to 5,
        21 to 5,
        24 to 7,
        30 to 10,
    )

    @Test
    fun `every score in the 5e table maps to its published modifier`() {
        official.forEach { (score, expected) ->
            assertEquals("score $score", expected, abilityModifier(score))
        }
    }

    @Test
    fun `odd scores below ten floor rather than truncate`() {
        // The exact cases naive integer division gets wrong.
        listOf(3 to -4, 5 to -3, 7 to -2, 9 to -1).forEach { (score, expected) ->
            assertEquals(
                "score $score truncated instead of flooring — use floorDiv",
                expected,
                abilityModifier(score),
            )
        }
    }

    @Test
    fun `the modifier never decreases as the score rises`() {
        (AbilityScores.MIN..AbilityScores.MAX)
            .map(::abilityModifier)
            .zipWithNext()
            .forEach { (lower, higher) ->
                assertEquals(true, higher >= lower)
            }
    }

    @Test
    fun `scores default to ten, which is a modifier of zero`() {
        val defaults = AbilityScores.DEFAULTS
        Ability.entries.forEach { ability ->
            assertEquals(AbilityScores.DEFAULT, defaults.score(ability))
            assertEquals(0, defaults.modifier(ability))
        }
    }

    @Test
    fun `each ability reads its own score and modifier`() {
        val scores = AbilityScores(
            strength = 8,
            dexterity = 14,
            constitution = 15,
            intelligence = 20,
            wisdom = 3,
            charisma = 11,
        )

        assertEquals(listOf(8, 14, 15, 20, 3, 11), scores.inSheetOrder.map { it.second })
        assertEquals(
            listOf(Ability.STR, Ability.DEX, Ability.CON, Ability.INT, Ability.WIS, Ability.CHA),
            scores.inSheetOrder.map { it.first },
        )
        assertEquals(listOf(-1, 2, 2, 5, -4, 0), Ability.entries.map { scores.modifier(it) })
    }
}
