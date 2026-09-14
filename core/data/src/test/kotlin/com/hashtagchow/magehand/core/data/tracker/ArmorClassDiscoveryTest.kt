package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-50's armour class (docs/design/18-table-pack.md addendum 3, decisions 21–23).
 *
 * ### Sibling of [HitDiceDiscoveryTest], and shaped like it on purpose
 *
 * Both features are a *predicate* over documents the mirror was already publishing — the miss was
 * never on the wire. So the fixtures here carry the shape the live sheet publishes rather than a
 * minimal one: `attributeType: "stat"` (which no other rule in this engine reads), a `baseValue`
 * that is a `_calculation` and is **not** the answer, and the answer on `total`.
 *
 * One assertion runs against the committed capture, and it is the one worth having: a synthetic
 * fixture is written by somebody who already knows the rule, and the capture is the only witness
 * that the rule matches what DiceCloud actually sends. It skips on a public clone
 * ([Fixtures.sabrielSheet] raises a JUnit assumption), which is correct — an assertion about a
 * sheet nobody has is not a failure.
 */
class ArmorClassDiscoveryTest {

    private val creatureId = "c1"

    /**
     * An armour-class attribute as the live sheet publishes one.
     *
     * `baseValue` is present and **wrong on purpose**: it is the `10+dexterity.modifier`
     * calculation, and on the capture it reads 11 while `total` reads 14, because armour, a
     * shield and every effect are folded into the total and not into the base. A reader that
     * reached for the field whose name sounds like a starting point would pass a minimal fixture
     * and be four points light at a table.
     */
    private fun armor(
        id: String = "ac1",
        total: String = "14",
        value: String = "14",
        extra: String = "",
    ) = """{"_id":"$id","type":"attribute","attributeType":"stat","name":"Armor Class",
            "variableName":"armor","order":75,
            "baseValue":{"calculation":"10+dexterity.modifier","type":"_calculation","value":11},
            "total":$total,"value":$value$extra}"""

    private fun hitPoints(current: Int = 30, total: Int = 40) =
        """{"_id":"hp1","type":"attribute","attributeType":"healthBar","variableName":"hitPoints",
            "name":"Hit Points","total":$total,"value":$current}"""

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    // --- decision 21: discovery ----------------------------------------------

    /** The rule, and the `total`-not-`baseValue` half of it. */
    @Test
    fun `an armor attribute is discovered by its variable name`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), armor()))

        assertEquals(14, board.armorClass)
    }

    /**
     * **The capture is the witness.** `variableName: "armor"`, `attributeType: "stat"`,
     * `total: 14` — read off a real sheet rather than off a fixture someone wrote to pass.
     *
     * This is the assertion that would have caught the rule being written against the wrong
     * field, and it is why the FR was scoped as "the data is already in the mirror" rather than
     * as a protocol change.
     */
    @Test
    fun `the committed capture's armor class is discovered`() {
        val board = TrackerEngine.build(Fixtures.sabrielSheet())

        assertEquals(14, board.armorClass)
    }

    /**
     * A sheet with no `armor` attribute reads **`null`**, and the distinction is the whole point.
     *
     * An unarmoured character is AC 10, so no sheet ever means zero: a `0` here could only be
     * this app's word for "not found", printed on the HP block as though it were the character's
     * armour class. `TrackerBoard.armorClass` carries the argument; this is the fixture that
     * fails if a future edit reaches for a `?: 0`.
     */
    @Test
    fun `a sheet with no armor attribute has no armor class`() {
        assertNull(TrackerEngine.build(sheetOf(hitPoints())).armorClass)
    }

    /**
     * The `_calculation` wrapper, which is how DiceCloud publishes a computed field before — and
     * sometimes after — it settles.
     *
     * Not a hypothetical shape: `baseValue` above is one on every real sheet, and `total` arrives
     * as one on properties whose value the server recomputes. `JsonObject.number` resolves it
     * through `value`, which is why this costs the rule no branch of its own.
     */
    @Test
    fun `a calculation-wrapped total is resolved to the number inside it`() {
        val board = TrackerEngine.build(
            sheetOf(hitPoints(), armor(total = """{"calculation":"armor","type":"_calculation","value":18}""")),
        )

        assertEquals(18, board.armorClass)
    }

    /**
     * `value` is the fallback, and only the fallback.
     *
     * The two agree on every sheet seen here, so the order matters only when they cannot: `total`
     * is the computed answer and is read first, exactly as `#discovery.remaining`'s posture
     * requires — never ignore a number the server already stated.
     */
    @Test
    fun `value is read when the property carries no total`() {
        val board = TrackerEngine.build(
            sheetOf(
                hitPoints(),
                """{"_id":"ac1","type":"attribute","attributeType":"stat","name":"Armor Class",
                    "variableName":"armor","value":12}""",
            ),
        )

        assertEquals(12, board.armorClass)
    }

    /**
     * A **stringified** total is a number — NIT-2, first half.
     *
     * `number()` accepts it and every other numeric reader in this engine already does; the shape
     * is in the tolerance set for `hitDiceSize`'s reason (DiceCloud is not uniform about which
     * one a field arrives in) and it costs the rule no branch. Asserted so the tolerance is a
     * fact rather than an accident of the shared reader.
     */
    @Test
    fun `a stringified total is read as a number`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), armor(total = "\"14\"", value = "\"14\"")))

        assertEquals(14, board.armorClass)
    }

    /**
     * **A server-stated `total: 0` renders as AC 0** — NIT-2, second half, and the one input
     * where the exported rule's sentence and this code read differently.
     *
     * `domain/rules.json#discovery.armorClass.absent` says *"`null`, never `0` … a `0` here could
     * only be the client's word for 'not found'"*, and that is true of the **absence** it is
     * about. It is not a rule about a zero the *sheet* published. This test records which was
     * meant: a stated zero is the sheet's word and is rendered, exactly as a stated
     * `constitutionMod: 0` is (18 decision 25's reasoning, one field over). The app does not
     * second-guess a number the server computed, even an implausible one — a homebrew creature or
     * a mid-edit sheet may legitimately say 0, and silently swapping it for "no AC" would hide a
     * real value behind a rule written about a missing key.
     */
    @Test
    fun `a server-stated zero is a value, not an absence`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), armor(total = "0", value = "0")))

        assertEquals(0, board.armorClass)
    }

    /** The blanket rule from 03: `inactive` and `removed` are skipped, here as everywhere. */
    @Test
    fun `an inactive or removed armor attribute is not the character's armor class`() {
        assertNull(
            TrackerEngine.build(sheetOf(hitPoints(), armor(extra = ""","inactive":true"""))).armorClass,
        )
        assertNull(
            TrackerEngine.build(sheetOf(hitPoints(), armor(extra = ""","removed":true"""))).armorClass,
        )
    }

    /**
     * An `inactive` armour attribute **earlier in the list** does not shadow the live one.
     *
     * What this proves, precisely: that [TrackerEngine.armorClass]'s skip predicate runs *inside*
     * the scan rather than after it, so a soft-removed or deactivated property is passed over
     * instead of ending it. It is deliberately first in the fixture so the order is the thing
     * under test.
     *
     * What it does **not** prove is which scan *shape* the engine uses — a first-match-then-read
     * scan would agree here, because `isSkipped()` removes this property from both. The test
     * below is the one that separates them; this one was mis-documented as doing that job.
     *
     * **Correction (fix pass, 2026-09-14).** This KDoc used to say *"the live sheet carries
     * exactly this: a feature grants an alternative AC and is switched off"*. It does not. The
     * committed capture has exactly **one** property with `variableName == "armor"` — the live,
     * active *Armor Class* attribute. The switched-off alternative on that sheet is *Mage Armor*,
     * delivered as a `buff` and an `effect` with no `attributeType` and no `variableName` at all,
     * which no scan of any shape could reach. The honest reason for the scan's shape is
     * mid-recompute shadowing, which is what the engine's own KDoc has said all along.
     */
    @Test
    fun `an inactive armor attribute earlier in the list does not end the scan`() {
        val board = TrackerEngine.build(
            sheetOf(
                hitPoints(),
                armor(id = "ac-inactive", total = "10", value = "10", extra = ""","inactive":true"""),
                armor(),
            ),
        )

        assertEquals(14, board.armorClass)
    }

    /**
     * **The scan reads past an active property it cannot get a number out of** — the case the
     * engine's `firstNotNullOfOrNull` exists for, and the one a first-match-then-read scan gets
     * wrong.
     *
     * ### The shape, and why it is not hypothetical
     *
     * DiceCloud publishes computed fields as a `_calculation` wrapper and writes the answer into
     * the wrapper's `value` on a **debounced** pass (the same window `TrackerEngine.limitedUse`
     * documents at ~2.5 s from insert). Between the two, a perfectly live `armor` attribute
     * carries a `calculation` and no `value`, and `number()` correctly reads it as unknown. A
     * scan that stopped at the first *match* would hand that unknown back as the character's AC —
     * the badge would go blank — while a readable one sat behind it in the same list. On a
     * multi-armour sheet, or during any recompute that touches the first of them, that is a
     * number a player reads at a table.
     *
     * ### What fails if the shape changes
     *
     * Swapping `firstNotNullOfOrNull { … }` for a first-match-then-read scan with the identical
     * skip predicate makes this test — and only this test — go red. Verified by doing exactly
     * that; the review's mutation 7 ran the whole `:core:data` suite against that edit and found
     * nothing, which is why this fixture exists.
     */
    @Test
    fun `an unreadable armor attribute does not shadow a readable one behind it`() {
        val board = TrackerEngine.build(
            sheetOf(
                hitPoints(),
                // Active, matching, mid-recompute: the wrapper is there and the answer is not.
                """{"_id":"ac-pending","type":"attribute","attributeType":"stat",
                    "name":"Armor Class","variableName":"armor",
                    "total":{"calculation":"10+dexterity.modifier","type":"_calculation"}}""",
                armor(),
            ),
        )

        assertEquals(14, board.armorClass)
    }

    /**
     * When **both** read, the first still wins — the scan is "first readable", not "best" or
     * "last".
     *
     * The other half of the rule above, and the half that stops the fix for it from turning into
     * a different bug: a sheet with two live `armor` attributes is answered by the one the server
     * listed first, deterministically, rather than by whichever happens to be largest or last.
     * Nothing here has an opinion about which of two armour classes is *right* — that is the
     * sheet's business — only that the answer does not move between syncs.
     */
    @Test
    fun `the first readable armor attribute wins when both read`() {
        val board = TrackerEngine.build(
            sheetOf(
                hitPoints(),
                armor(id = "ac-first", total = "12", value = "12"),
                armor(id = "ac-second", total = "18", value = "18"),
            ),
        )

        assertEquals(12, board.armorClass)
    }

    // --- decision 22: it is not a row ---------------------------------------

    /**
     * **AC is not a `TrackedResource`, and this is the assertion that says so structurally.**
     *
     * Decision 22's whole argument is that AC has no count and no mutator, so it must not enter
     * any list a spend, a pin, a hide or a tap can reach. `allCountableRows` is that set (BUG-20),
     * and the number 14 must not be findable in it — a future edit that "simplified" AC into a
     * one-of-one resource row would fail here rather than at a table, where its symptom is a pip
     * row the player can tap to lower their own armour class.
     */
    @Test
    fun `armor class is not a countable row`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), armor()))

        assertEquals(14, board.armorClass)
        assertNull(board.allCountableRows.firstOrNull { it.propertyId == "ac1" })
        // And not filed under any of the named lists either, which is the same claim from the
        // other side — `allCountableRows` could in principle be edited to drop a list.
        assertEquals(emptyList<String>(), board.resources.map { it.propertyId })
        assertEquals(emptyList<String>(), board.slots.map { it.propertyId })
        assertEquals(emptyList<String>(), board.hitDice.map { it.propertyId })
    }
}
