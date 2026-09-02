package com.hashtagchow.magehand.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-36's fold and its rider labels, pinned in the module that owns them
 * (docs/design/16-actions-and-feed.md, addendum *"FR-36 fix wave rulings"*).
 *
 * ### Why these live here and not in `ActionEngineTest`
 *
 * Same split as [UseTargetTest]: the engine's job is *parsing* — turning an `amount.effects`
 * array into [DamageRider]s, pinned there against real JSON — and this is the *formatting rule
 * over the parsed values*. The FR-36 review found both halves of that rule wrong in ways a JSON
 * fixture hides: a hard-coded `+` on an already-signed amount, a blank name leaving a trailing
 * separator, `-n` overflowing at [Int.MIN_VALUE]. Each of those is one assertion here, named,
 * with no parsing between the input and the claim.
 *
 * The label cases matter twice over because two surfaces render it — the row's chip and the
 * detail sheet's itemised line — and the review's finding 3 and 7 were both *"the two call sites
 * disagree"*. There is one function now, and this is where it is checked.
 */
class DamageLineTest {

    private fun rider(name: String = "Rider", operation: String = "add", amount: String) =
        DamageRider(name = name, operation = operation, amount = amount)

    // -----------------------------------------------------------------------
    // What folds
    // -----------------------------------------------------------------------

    /** The rule in one line: `add`, a whole integer, not zero. */
    @Test
    fun `only a non-zero whole-integer add folds into the headline`() {
        assertTrue(rider(amount = "3").foldsIntoHeadline)
        assertTrue(rider(amount = "-1").foldsIntoHeadline)

        assertFalse("zero is an identity term and is not said", rider(amount = "0").foldsIntoHeadline)
        assertFalse("a fraction is not a whole number", rider(amount = "1.5").foldsIntoHeadline)
        assertFalse("dice are the chip case FR-36 exists for", rider(amount = "2d6").foldsIntoHeadline)
        assertFalse("no amount, nothing to add", rider(amount = "").foldsIntoHeadline)
        assertFalse(
            "an operation this build has not seen is never combined with the die",
            rider(operation = "mul", amount = "2").foldsIntoHeadline,
        )
    }

    /**
     * Concatenation in server order, never a sum — `d4 + 1 - 1` is what the sheet will roll, and
     * a `d4` here would be this type doing the arithmetic it exists not to do.
     */
    @Test
    fun `of concatenates the folding riders in order and leaves the rest to chip`() {
        val line = DamageLine.of(
            base = "d4",
            damageType = "bludgeoning",
            riders = listOf(
                rider("Enchantment", amount = "1"),
                rider("Ability Modifiers", amount = "-1"),
                rider("Sneak Attack", amount = "2d6"),
            ),
        )

        assertEquals("d4 + 1 - 1", line.amount)
        assertEquals("d4", line.base)
        assertEquals(listOf("Sneak Attack"), line.chips.map { it.name })
    }

    /**
     * The zero rider's **one surface** (architect ruling, 2026-09-02): it is not in the headline
     * and it is not a chip, so the row is exactly the row of a character with no effect at all —
     * and it is still in [DamageLine.riders], where the detail sheet itemises it.
     *
     * Both halves matter. Fold it and every Str-10 weapon reads `d6 + 0`; chip it and every one
     * of them carries *"+0 Ability Modifiers"*, which says the same untrue thing at greater
     * length beside every weapon on the list.
     */
    @Test
    fun `a zero add rider neither folds nor chips but stays for the detail sheet`() {
        val zero = rider("Ability Modifiers", amount = "0")
        val line = DamageLine.of(base = "d6", damageType = "bludgeoning", riders = listOf(zero))

        assertTrue(zero.isZeroAdd)
        assertFalse(zero.foldsIntoHeadline)
        assertEquals("d6", line.amount)
        assertEquals(line.base, line.amount)
        assertEquals(emptyList<DamageRider>(), line.chips)
        assertEquals(listOf(zero), line.riders)
    }

    /**
     * [DamageRider.isZeroAdd] is about `add` and about a *whole* zero — nothing else gets the
     * silent treatment, because nothing else is an identity term this app can recognise.
     */
    @Test
    fun `only an add resolving to a whole zero is silent`() {
        assertTrue(rider(amount = "0").isZeroAdd)
        assertFalse("a non-add operation is always stated", rider(operation = "mul", amount = "0").isZeroAdd)
        assertFalse("0d6 is dice, not a number", rider(amount = "0d6").isZeroAdd)
        assertFalse("no amount is not an amount of zero", rider(amount = "").isZeroAdd)
        assertFalse(rider(amount = "0.0").isZeroAdd)
    }

    /** No riders at all: the 2026-08-24 verbatim ruling, unchanged. */
    @Test
    fun `of with no riders is the verbatim value`() {
        val line = DamageLine.of(base = "2d6", damageType = "fire")

        assertEquals("2d6", line.amount)
        assertEquals(line.base, line.amount)
        assertTrue(line.chips.isEmpty())
    }

    /**
     * A server value that already carries a `+` folds — `toIntOrNull` accepts one — so the term
     * has to strip **both** signs, not just the minus (pre-release review M1). Stripping only
     * `-` printed `d8 + +3`.
     */
    @Test
    fun `an already-plus-signed amount folds without doubling its sign`() {
        assertEquals("d8 + 3", DamageLine.of("d8", "force", listOf(rider(amount = "+3"))).amount)
    }

    /**
     * Review finding 11: the headline prints the amount's **own digits** with a leading `-`
     * moved into the separator. `-Int.MIN_VALUE` is `Int.MIN_VALUE`, so the arithmetic version
     * printed `d8 - -2147483648`.
     */
    @Test
    fun `the magnitude is the amount's own text, so the most negative integer survives`() {
        val line = DamageLine.of("d8", "force", listOf(rider(amount = Int.MIN_VALUE.toString())))

        assertEquals("d8 - 2147483648", line.amount)
    }

    // -----------------------------------------------------------------------
    // What the chip and the detail sheet say
    // -----------------------------------------------------------------------

    /** An unsigned `add` gets a `+`; one the server already signed is printed as it stands. */
    @Test
    fun `an add label takes its sign from the amount and never doubles one`() {
        assertEquals("+2d6 Sneak Attack", rider("Sneak Attack", amount = "2d6").label)
        assertEquals("-1d4 Bane", rider("Bane", amount = "-1d4").label)
        assertEquals("+2d6 Bless", rider("Bless", amount = "+2d6").label)
        assertEquals("+1.5 Half Bonus", rider("Half Bonus", amount = "1.5").label)
    }

    /**
     * Review finding 7: every blank part takes its separator with it. `"+2d6 "` is heard as a
     * dangling word by a screen reader, and `" · mul 2"` is heard as a sentence starting with
     * punctuation.
     */
    @Test
    fun `blank parts never leave a dangling separator`() {
        assertEquals("+2d6", rider(name = "", amount = "2d6").label)
        assertEquals("mul 2", rider(name = "", operation = "mul", amount = "2").label)
        assertEquals("Undead · conditional", rider("Undead", "conditional", amount = "").label)
        assertEquals("Odd · 2", rider("Odd", operation = "", amount = "2").label)
    }

    /**
     * The whole point of finding 4: an operation with no amount still *says* something, and what
     * it says is the operation, in words.
     */
    @Test
    fun `an unknown operation is stated in words beside its name`() {
        assertEquals("Doubled · mul 2", rider("Doubled", "mul", "2").label)
    }

    /**
     * A rider with nothing to say is not drawn (pre-release review M4). `ActionEngine` refuses to
     * build one, and this is the second latch: an empty chip is a drawn border around nothing
     * and an empty string inside the row's merged sentence, heard as an unexplained pause.
     */
    @Test
    fun `a rider whose label comes out blank is not chipped`() {
        val silent = DamageRider(name = "", operation = "", amount = "")
        val line = DamageLine.of("d6", "fire", listOf(silent))

        assertEquals("", silent.label)
        assertEquals(emptyList<DamageRider>(), line.chips)
        assertEquals(listOf(silent), line.riders)
    }
}
