package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * FR-30's hit dice (docs/design/18-table-pack.md decisions 17–20, the addendum).
 *
 * ### Why the fixtures are real-shaped rather than minimal
 *
 * Probe H4's finding is that the documents *were already in the mirror* and were being filtered
 * out at discovery — `attributeType: 'hitDice'` matched neither the `spellSlot` rule nor the
 * `resource` one, so they fell out with no row and no error. That is a failure a minimal fixture
 * cannot reproduce, because a minimal fixture is written by somebody who already knows the answer.
 * So every property here carries the shape the probe recorded: the `attributeType`, a
 * `hitDiceSize` of `"d8"`, `value = total − damage`, and **no `reset` field at all**.
 *
 * Synthetic rather than the committed capture, for [ConcentrationPromptTest]'s reason: the capture
 * is absent from a public clone and every assertion against it skips there. The capture also
 * predates the feature and carries no hit-dice property to assert on.
 */
class HitDiceDiscoveryTest {

    private val creatureId = "c1"

    /**
     * A hit-dice attribute exactly as the probe recorded one.
     *
     * `damage` and `value` both present and consistent, because the engine reads `value` when it
     * is there and falls back to `total − damage` when it is not — a fixture that supplied only
     * one of them would leave the other path untested and would not be what the wire sends.
     */
    private fun hitDice(
        id: String,
        size: String = "d8",
        total: Int = 5,
        damage: Int = 2,
        order: Int = 10,
        extra: String = "",
    ) = """{"_id":"$id","type":"attribute","attributeType":"hitDice","name":"Hit Dice",
            "variableName":"hitDice$size","hitDiceSize":"$size","total":$total,
            "damage":$damage,"value":${total - damage},"order":$order$extra}"""

    private fun hitPoints(current: Int = 30, total: Int = 40) =
        """{"_id":"hp1","type":"attribute","attributeType":"healthBar","variableName":"hitPoints",
            "name":"Hit Points","total":$total,"value":$current}"""

    private fun resource(id: String, name: String) =
        """{"_id":"$id","type":"attribute","attributeType":"resource","variableName":"$name",
            "name":"$name","total":3,"value":3,"reset":"longRest","order":20}"""

    private fun spellSlot(id: String) =
        """{"_id":"$id","type":"attribute","attributeType":"spellSlot","name":"1st Level",
            "spellSlotLevel":1,"total":4,"value":4,"reset":"longRest","order":30}"""

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    // --- decision 17: discovery ---------------------------------------------

    /**
     * The one-line predicate unblock, asserted from the shape the probe found.
     *
     * Everything on the row comes from the property: the remaining count is `total − damage` (via
     * the published `value`), the die size is `hitDiceSize`, and the reset rule is **absent** — see
     * the dedicated test below for why that last one is a fact rather than a default.
     */
    @Test
    fun `a hitDice attribute is discovered as its own kind`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), hitDice("hd8")))

        val row = board.hitDice.single()
        assertEquals("hd8", row.propertyId)
        assertEquals(TrackerKind.HIT_DICE, row.kind)
        assertEquals(5, row.total)
        assertEquals("value = total − damage, like every other countable row", 3, row.value)
        assertEquals("d8", row.dieSize)
    }

    /** One row per die size — a multiclass character, which is the case the feature is for. */
    @Test
    fun `each die size is its own row`() {
        val board = TrackerEngine.build(
            sheetOf(
                hitPoints(),
                hitDice("hd10", size = "d10", total = 3, damage = 0, order = 11),
                hitDice("hd6", size = "d6", total = 2, damage = 1, order = 10),
            ),
        )

        assertEquals(2, board.hitDice.size)
        // The sheet's own `order`, then name — `NATURAL_ORDER`, the rule resources already use.
        // Not "largest die first": that would be this app imposing a convention the sheet did not
        // express, and the server's own order is the only ordering the data carries.
        assertEquals(listOf("hd6", "hd10"), board.hitDice.map { it.propertyId })
        assertEquals(listOf("d6", "d10"), board.hitDice.map { it.dieSize })
        assertEquals(listOf(1, 3), board.hitDice.map { it.value })
    }

    /**
     * Decision 17: *"NO reset field — by design: the server's own rest machinery bypasses reset"*.
     *
     * `null` here is read off a property that genuinely carries none, and it is load-bearing
     * rather than cosmetic: decision 19 says the server restores half the dice on a long rest
     * **itself** and the app predicts nothing, so a hit-dice row must never reach the rest
     * dialog's reset-rule-driven restore list. This is the first of the two guards; the second is
     * that `TrackerBoard.hitDice` is not one of the lists that dialog reads.
     *
     * L-batch [architect ruling]: the fixture carries an explicit `"reset":"shortRest"`, unlike
     * every other call to [hitDice] in this file. Without one, `hitDice()`'s own JSON never had a
     * `reset` key to begin with — `assertNull` would pass whether or not `TrackerEngine` drops the
     * field, which proves nothing about the guard. A property that *has* one and is still read
     * back `null` is the only fixture that can fail if a future change starts reading it.
     */
    @Test
    fun `a hit-dice row carries no reset rule, even when the property has one`() {
        val board = TrackerEngine.build(
            sheetOf(hitPoints(), hitDice("hd8", extra = ""","reset":"shortRest"""")),
        )

        assertNull(board.hitDice.single().reset)
    }

    /**
     * The rows land in their **own** list and nowhere else.
     *
     * The half worth asserting is the negative one: a hit-dice property must not appear among the
     * resources, which is where a `resource`-predicate widened to accept it would have put it —
     * and where it would then have been one `reset` field away from the rest dialog.
     */
    @Test
    fun `hit dice are not resources and not slots`() {
        val board = TrackerEngine.build(
            sheetOf(hitPoints(), hitDice("hd8"), resource("res1", "Rage"), spellSlot("slot1")),
        )

        assertEquals(listOf("hd8"), board.hitDice.map { it.propertyId })
        assertEquals(listOf("res1"), board.resources.map { it.propertyId })
        assertEquals(listOf("slot1"), board.slots.map { it.propertyId })
        assertTrue(board.allItems.isEmpty())
    }

    /** `inactive` and `removed` are skipped, like every rule but the toggle one. */
    @Test
    fun `an inactive or removed hit-dice property is dropped`() {
        assertTrue(
            TrackerEngine.build(sheetOf(hitPoints(), hitDice("hd8", extra = ""","inactive":true"""))).hitDice.isEmpty(),
        )
        assertTrue(
            TrackerEngine.build(sheetOf(hitPoints(), hitDice("hd8", extra = ""","removed":true"""))).hitDice.isEmpty(),
        )
    }

    /**
     * A character below the level that grants a die has `total == 0`, and there is nothing to
     * render — the same exclusion `spellSlot` applies to slot levels not yet reached.
     */
    @Test
    fun `a hit-dice row with nothing in it is dropped`() {
        val board = TrackerEngine.build(
            sheetOf(hitPoints(), hitDice("hd12", size = "d12", total = 0, damage = 0)),
        )

        assertTrue(board.hitDice.isEmpty())
    }

    // --- the label's raw material ------------------------------------------

    /**
     * `hitDiceSize` is carried verbatim, and the three shapes DiceCloud is not uniform about are
     * all tolerated.
     *
     * The live sheet publishes `"d8"`. A bare number and a `_calculation` wrapper cost four lines
     * to accept and remove a whole class of "renders on my sheet and not on yours" — the same
     * tolerance every other numeric reader in `CreatureSheet` already has.
     *
     * The `d` is **prepended only when missing**, never parsed and re-rendered: a homebrew `"d3"`
     * survives, and so does anything else the sheet's author typed.
     */
    @Test
    fun `the die size is read from a string, a number or a calculation wrapper`() {
        fun sizeOf(field: String): String? = TrackerEngine
            .build(
                sheetOf(
                    hitPoints(),
                    """{"_id":"hd","type":"attribute","attributeType":"hitDice","name":"Hit Dice",
                        "total":3,"value":3,$field}""",
                ),
            )
            .hitDice.single().dieSize

        assertEquals("d8", sizeOf(""""hitDiceSize":"d8""""))
        assertEquals("a bare number gains the d", "d8", sizeOf(""""hitDiceSize":8"""))
        assertEquals(
            "d10",
            sizeOf(""""hitDiceSize":{"calculation":"hitDiceSize","value":"d10"}"""),
        )
        assertEquals("homebrew survives unnormalised", "d3", sizeOf(""""hitDiceSize":"d3""""))
    }

    /**
     * A row with **no readable die size still renders**, under the sheet's own name.
     *
     * Dropping it would mean losing a resource the player can spend over a *label*, which is the
     * wrong thing to be strict about — `DamageDefense`'s tolerance towards free-text damage types,
     * applied to one more field. The UI's fallback is `TrackedResource.name`, which is why the
     * name is asserted here as well as the null.
     */
    @Test
    fun `a hit-dice row with no size keeps its sheet name`() {
        val board = TrackerEngine.build(
            sheetOf(
                hitPoints(),
                """{"_id":"hd","type":"attribute","attributeType":"hitDice","name":"Hit Dice",
                    "total":3,"value":3}""",
            ),
        )

        val row = board.hitDice.single()
        assertNull(row.dieSize)
        assertEquals("Hit Dice", row.name)
    }

    // --- the override layer, deliberately not applied ------------------------

    /**
     * The customize sheet cannot reach these rows, so the override layer is not applied to them.
     *
     * `TrackerCustomizeState` builds its sections from slots, resources, items and toggles, so
     * there is no control anywhere that can pin, hide or reorder a hit-dice row. Applying the
     * layer would therefore mean a stored preference — from a hand-edited file, or a future build
     * that did offer the control and was then downgraded — could hide the row with nothing on
     * screen able to bring it back. That is `TrackerBoard.deathSaves`' argument, one row-type over.
     */
    @Test
    fun `a stored hide override cannot make a hit-dice row disappear`() {
        val board = TrackerEngine.build(
            sheet = sheetOf(hitPoints(), hitDice("hd8")),
            overrides = listOf(TrackerOverride(propertyId = "hd8", hidden = true)),
        )

        assertEquals(listOf("hd8"), board.hitDice.map { it.propertyId })
    }

    // --- decision 18: the write is the existing one --------------------------

    /**
     * Decision 18: *"spend is the EXISTING damage increment … ZERO new intents"*.
     *
     * The op a hit-dice spend builds is byte-identical in shape to a spell-slot spend's —
     * `creatureProperties.damage` with `operation: increment` and `value: +1` — which is what
     * makes the claim "no new intents, `WritePostureTest` untouched" true rather than merely
     * intended. Asserted through `WriteOp.spend`, the factory both paths go through, so a future
     * change that routed hit dice to `adjustQuantity` (the item branch) fails here.
     */
    @Test
    fun `spending a hit die builds the same damage increment a slot spend does`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), hitDice("hd8"), spellSlot("slot1")))

        val die = WriteOp.spend(board.hitDice.single())
        val slot = WriteOp.spend(board.slots.single())

        assertEquals("creatureProperties.damage", die.method)
        assertEquals(slot.method, die.method)
        assertEquals("hd8", die.targetId)
        // The same rate class, too — the fast `damage` lane rather than the 1 s default.
        assertEquals(slot.minSpacingMillis, die.minSpacingMillis)
        // And it is reversible in the ordinary way: a spend's inverse is a restore.
        assertEquals("creatureProperties.damage", die.inverse?.method)
    }

    /**
     * A restore is the same call with the other sign, which is what makes the pips' empty half
     * work — and what a player uses after the server's own long-rest restoration lands wrong, or
     * after a mis-tap.
     */
    @Test
    fun `restoring a hit die is the inverse call`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), hitDice("hd8")))

        val restore = WriteOp.restore(board.hitDice.single())

        assertEquals("creatureProperties.damage", restore.method)
        assertEquals(1, restore.magnitude)
    }
}
