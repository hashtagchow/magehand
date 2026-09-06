package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * FR-44 R1 — limited-use abilities discovered by shape (ledgered 2026-09-06).
 *
 * ### The fixtures carry the wire's shape, not a convenient one
 *
 * [HitDiceDiscoveryTest]'s argument applies twice over here, because the field that decides
 * everything arrives wrapped: a live sheet publishes `uses` as DiceCloud's `_calculation` object
 * (`{"calculation":"proficiencyBonus", …, "value":2}`), never as a bare number. A fixture written
 * with `"uses":2` would pass against a reader that cannot see a real sheet at all — which is
 * precisely the class of miss FR-30's probe H4 found on the other side of the same engine. So the
 * wrapper is here, and [barePlainUses] exists to prove the tolerant reader still handles the
 * simpler shape rather than to stand in for the real one.
 *
 * `usesLeft` is in every fixture and is asserted **against**: it is the field whose name a reader
 * recognises, and 17 decision 1 records that it lags a debounced server recompute by 4–10 s. The
 * fixtures deliberately publish a *stale* `usesLeft` so that a reader that took the easy field
 * fails here instead of at a table.
 */
class LimitedUseDiscoveryTest {

    private val creatureId = "c1"

    /**
     * An `action`/`spell` property carrying a use count, in the shape the live party sheets do.
     *
     * @param usesUsed `null` omits the key — the never-used case, which is four of the five real
     *   rows the FR-44 triage probe found.
     * @param staleUsesLeft what the server's own derived field says. Deliberately allowed to
     *   disagree with `uses − usesUsed`; see the class KDoc.
     */
    @Suppress("LongParameterList")
    private fun limitedUse(
        id: String,
        type: String = "action",
        name: String = "Second Wind",
        uses: Int? = 2,
        usesUsed: Int? = 1,
        reset: String? = "longRest",
        order: Int = 10,
        staleUsesLeft: Int = 99,
        extra: String = "",
    ): String {
        val usesJson = uses?.let {
            ""","uses":{"type":"_calculation","calculation":"$it","value":$it}"""
        }.orEmpty()
        val usedJson = usesUsed?.let { ""","usesUsed":$it""" }.orEmpty()
        val resetJson = reset?.let { ""","reset":"$it"""" }.orEmpty()
        return """{"_id":"$id","type":"$type","name":"$name","actionType":"action",
            "usesLeft":$staleUsesLeft$usesJson$usedJson$resetJson,"order":$order$extra}"""
    }

    /** The same row with `uses` as a bare number — the tolerated shape, not the observed one. */
    private fun barePlainUses(id: String) =
        """{"_id":"$id","type":"action","name":"Bare","actionType":"action","uses":4,
            "usesUsed":1,"reset":"shortRest","order":50}"""

    private fun hitPoints() =
        """{"_id":"hp1","type":"attribute","attributeType":"healthBar","variableName":"hitPoints",
            "name":"Hit Points","total":40,"value":30}"""

    private fun resource(id: String, name: String) =
        """{"_id":"$id","type":"attribute","attributeType":"resource","variableName":"$name",
            "name":"$name","total":3,"value":3,"reset":"longRest","order":20}"""

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    // --- R1: discovery ------------------------------------------------------

    /**
     * The headline case, with every field on the row coming from the property.
     *
     * The `value` assertion is the one that matters: `2 − 1 = 1`, computed, and **not** the `99`
     * the fixture's `usesLeft` claims. A reader that took the server's derived field would pass
     * every other assertion in this class.
     */
    @Test
    fun `an action with a use count is discovered as its own kind`() {
        val board = TrackerEngine.build(sheetOf(hitPoints(), limitedUse("lu1")))

        val row = board.limitedUses.single()
        assertEquals("lu1", row.propertyId)
        assertEquals(TrackerKind.LIMITED_USE, row.kind)
        assertEquals("Second Wind", row.name)
        assertEquals("uses − usesUsed, not the server's lagging usesLeft", 1, row.value)
        assertEquals(2, row.total)
        assertEquals(ResetRule.LONG_REST, row.reset)
        assertEquals(10, row.sortOrder)
    }

    /**
     * **A `spell` row, which is the case the FR was actually raised for.**
     *
     * a Stars druid's *Guiding Bolt (Star Map)* is 2 per long rest and costs no slot, and it is typed
     * `spell`. An implementation matching `action` alone passes the test above and misses the row
     * the operator asked about — so this is not a variation on the previous case, it is the case.
     */
    @Test
    fun `a spell with a use count is discovered too`() {
        val board = TrackerEngine.build(
            sheetOf(limitedUse("lu-spell", type = "spell", name = "Guiding Bolt (Star Map)", usesUsed = 0)),
        )

        val row = board.limitedUses.single()
        assertEquals("lu-spell", row.propertyId)
        assertEquals(TrackerKind.LIMITED_USE, row.kind)
        assertEquals(2, row.value)
    }

    /**
     * An absent `usesUsed` is **zero**, not unknown — the reading `ActionEngine.usesFor` has used
     * since FR-28, restated here because the tracker row and the Actions tab's *"N of M uses
     * left"* are two renderings of one number and a player will have both on screen.
     */
    @Test
    fun `a never-used ability reads full`() {
        val board = TrackerEngine.build(sheetOf(limitedUse("lu1", uses = 3, usesUsed = null)))

        assertEquals(3, board.limitedUses.single().value)
        assertEquals(3, board.limitedUses.single().total)
    }

    /** An ability with no counter is not a row: there is nothing to put pips on. */
    @Test
    fun `an action with no uses is not a row`() {
        val board = TrackerEngine.build(sheetOf(limitedUse("dash", uses = null, usesUsed = null)))

        assertTrue(board.limitedUses.isEmpty())
    }

    /**
     * **`uses` present but computing to zero is not a row.**
     *
     * `uses` is a calculation — *Guiding Bolt (Star Map)* is `proficiencyBonus`, *Weal* is
     * `max(1, wisdom.modifier)` — so a character below the level that grants an ability publishes
     * a real property whose count evaluates to nothing. A predicate keyed on the field's
     * *presence* draws a pip row with no pips: unreadable, unspendable, and indistinguishable at a
     * glance from an ability that is merely exhausted.
     */
    @Test
    fun `an ability whose uses compute to zero is not a row`() {
        val board = TrackerEngine.build(
            sheetOf(
                limitedUse("lu-zero", uses = 0, usesUsed = null),
                limitedUse("lu-negative", uses = -1, usesUsed = null),
                limitedUse("lu-live"),
            ),
        )

        assertEquals(listOf("lu-live"), board.limitedUses.map { it.propertyId })
    }

    /**
     * **`usesUsed` greater than `uses` floors at zero rather than going negative.**
     *
     * A state the server can publish and this app cannot: `uses` is a calculation, so an ability
     * whose count *shrinks* — a lost level, an edited formula, a dropped proficiency bonus — keeps
     * whatever `usesUsed` it already had. A 3-use ability spent twice and recomputed to 1
     * publishes `uses: 1, usesUsed: 2`.
     *
     * Unfloored, that is a row reading −1 with a negative pip count to draw, and the write built
     * from it is worse than the render: `WriteOp.adjust` derives `usesUsed = total − remaining`,
     * so the first restore tap on a −1 row sends `usesUsed = 1 − 0 = 1` and the row *still* reads
     * 0 — a control that visibly does nothing. Floored, the row reads "0 of 1" (spent out, which
     * is the truthful reading of a counter past its own maximum) and the restore tap writes
     * `usesUsed: 0` and works.
     */
    @Test
    fun `a counter past its own maximum reads zero, and its restore tap works`() {
        val row = TrackerEngine.build(sheetOf(limitedUse("lu1", uses = 1, usesUsed = 2)))
            .limitedUses.single()

        assertEquals(0, row.value)
        assertEquals(1, row.total)

        val restore = WriteOp.restore(row) as WriteOp.SetUsesUsed
        assertEquals("the tap must move the counter to a value the row can read", 0, restore.value)
    }

    /**
     * `inactive` and `removed`, which are both real rather than theoretical here.
     *
     * the live druid sheet carries four limited-use rows that are `inactive` until the levels that grant them,
     * and FR-44's own R2 probe left a soft-removed `action` on the Test Dummy — a permanent
     * negative fixture for this predicate on the one creature the repo may write to.
     *
     * This makes tracker discovery **stricter than the Actions tab**, deliberately: 16 decision 2
     * keeps `inactive` rows and badges them, which is right for a list of everything a character
     * has and wrong for a strip of pips whose whole purpose is "spend this".
     */
    @Test
    fun `inactive and removed rows are skipped`() {
        val board = TrackerEngine.build(
            sheetOf(
                limitedUse("lu-inactive", extra = ""","inactive":true"""),
                limitedUse("lu-removed", type = "spell", extra = ""","removed":true"""),
                limitedUse("lu-live"),
            ),
        )

        assertEquals(listOf("lu-live"), board.limitedUses.map { it.propertyId })
    }

    /** A row with no `reset` still renders; it simply never joins a rest dialog's restore list. */
    @Test
    fun `a row with no reset rule is kept, with a null reset`() {
        val board = TrackerEngine.build(sheetOf(limitedUse("lu1", reset = null)))

        assertNull(board.limitedUses.single().reset)
    }

    /** The `_calculation` wrapper is the wire's shape; a bare number is tolerated beside it. */
    @Test
    fun `a bare numeric uses is read as well as the calculation wrapper`() {
        val board = TrackerEngine.build(sheetOf(barePlainUses("lu-bare")))

        assertEquals(3, board.limitedUses.single().value)
        assertEquals(4, board.limitedUses.single().total)
    }

    /**
     * A limited-use row is in **its own list**, and nothing about it leaks into the attribute
     * lists — which is the structural half of `TrackerKind.LIMITED_USE`'s argument. A row that
     * reached `resources` would be spent with `creatureProperties.damage` against a property that
     * has no `damage` field.
     */
    @Test
    fun `limited uses are their own list and never resources`() {
        val board = TrackerEngine.build(
            sheetOf(hitPoints(), resource("res1", "Rage"), limitedUse("lu1")),
        )

        assertEquals(listOf("res1"), board.resources.map { it.propertyId })
        assertEquals(listOf("lu1"), board.limitedUses.map { it.propertyId })
        assertTrue(board.slots.isEmpty())
        assertTrue(board.hitDice.isEmpty())
    }

    /**
     * Ordered by the sheet's own `order`, then the name — `NATURAL_ORDER`, the same comparator
     * the resources section uses, so two rows sharing an `order` cannot swap places between syncs.
     */
    @Test
    fun `rows keep the sheet's order`() {
        val board = TrackerEngine.build(
            sheetOf(
                limitedUse("c", name = "Cosmic Omen", order = 30),
                limitedUse("a", name = "Arcane Recovery", order = 10),
                limitedUse("b", name = "Bardic Inspiration", order = 20),
            ),
        )

        assertEquals(listOf("a", "b", "c"), board.limitedUses.map { it.propertyId })
    }

    /**
     * **Not override-filtered**, exactly as hit dice are not (`TrackerBoard.limitedUses`).
     *
     * The customize sheet builds its sections from slots, resources, items and toggles, so no
     * control anywhere can set an override on one of these rows. Honouring one would let a stale
     * preference — set when the property was something else, or by a future wave — hide a row with
     * nothing on screen able to bring it back. FR-44's switch (R3) is the control, and it hides
     * the section.
     */
    @Test
    fun `a stale hidden override cannot remove a limited-use row`() {
        val board = TrackerEngine.build(
            sheetOf(limitedUse("lu1")),
            listOf(TrackerOverride(propertyId = "lu1", hidden = true)),
        )

        assertEquals(1, board.limitedUses.size)
    }

    // --- R2: the row the write is built from --------------------------------

    /**
     * Discovery and the write, pinned together end to end — the round trip that matters, in
     * `InventoryWriteLiveIntegrationTest`'s words: *"a write this app makes must produce a
     * document this app's own discovery can read back"*, here in the other direction.
     *
     * Spending the discovered row must produce an `update` on `usesUsed`, not a `damage`
     * increment. Nothing but the row's `kind` decides that, so this is the assertion that fails if
     * a future refactor files these rows anywhere else.
     */
    @Test
    fun `spending a discovered row sends update on usesUsed`() {
        val row = TrackerEngine.build(sheetOf(limitedUse("lu1"))).limitedUses.single()

        val op = WriteOp.spend(row)

        assertEquals(WriteOp.METHOD_UPDATE, op.method)
        assertTrue(op is WriteOp.SetUsesUsed)
        // The row read 1 of 2 left, so one more spent is `usesUsed: 2`.
        assertEquals(2, (op as WriteOp.SetUsesUsed).value)
        assertEquals(1, op.previous)
    }

    /**
     * The engine's field names are the write's field names.
     *
     * `TrackerEngine` reads `usesUsed` and `WriteOp` writes it, and the two are separate constants
     * in separate modules' vocabularies. A typo in either is a silent no-op — `update` would
     * cheerfully `$set` a field nothing reads — so the pair is asserted rather than trusted.
     */
    @Test
    fun `the read and write field names agree`() {
        assertEquals(TrackerEngine.FIELD_USES_USED, WriteOp.PATH_USES_USED)
    }
}
