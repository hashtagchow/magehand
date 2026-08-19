package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The soft-delete audit, as a test** (docs/design/10-inventory.md decision 3).
 *
 * ### What was audited and what it found
 *
 * DiceCloud does not delete properties, it flags them: a deleted property keeps its document
 * and gains `removed: true` plus a `removedAt`. The 2026-08-19 probe established that those
 * documents **reach the client on both transports** — the REST body carries them (the
 * committed capture holds one) and the subscription can too. So every code path that lists,
 * sums or counts properties has to filter them, and 10 decision 3 made auditing the shipped
 * ones part of this wave rather than a promise about the new ones.
 *
 * The audit's finding: all nine of [TrackerEngine]'s discovery rules already filtered
 * correctly, and **six of the nine were pinned by nothing**. A correct rule with no test is
 * one refactor away from being an incorrect rule, and the failure mode is invisible — a
 * deleted spell slot that still renders looks exactly like a spell slot. This class pins all
 * nine, one synthetic document per rule, so the filter cannot be removed silently.
 *
 * Three of them were already covered elsewhere and are re-pinned here anyway rather than
 * cross-referenced: `TrackerEngineTest` covers items (via the private capture, so it **skips**
 * on a public clone where the fixture is absent), defenses and skill rolls. Pinning all nine
 * in one place with synthetic documents means the rule survives in an environment that has no
 * capture at all, which is the environment this repo is published into.
 *
 * ### Why synthetic documents rather than the capture
 *
 * The capture holds exactly one soft-deleted property and it is an `item`, so it can only
 * ever exercise one of the nine rules. It also is not present in a public clone. Each test
 * below therefore builds the smallest document that would reach the board if the filter were
 * gone — which is also what makes each one a readable statement of what the rule is *for*.
 */
class SoftDeleteFilterTest {

    private fun sheet(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"c1","name":"Test"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    /** The same document twice: once live, once soft-deleted. */
    private fun pair(body: String): Pair<CreatureSheet, CreatureSheet> =
        sheet("{$body}") to sheet("""{$body,"removed":true}""")

    // -----------------------------------------------------------------------
    // The nine discovery rules
    // -----------------------------------------------------------------------

    @Test
    fun `a soft-deleted spell slot is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"attribute","attributeType":"spellSlot",
               "name":"1st Level","total":4,"value":4,"reset":"longRest"""",
        )
        assertEquals(1, TrackerEngine.build(live).slots.size)
        assertTrue(
            "a deleted spell slot still renders as pips the player can spend",
            TrackerEngine.build(deleted).slots.isEmpty(),
        )
    }

    @Test
    fun `a soft-deleted resource is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"attribute","attributeType":"resource",
               "name":"Rage","total":3,"value":3,"reset":"longRest"""",
        )
        assertEquals(1, TrackerEngine.build(live).resources.size)
        assertTrue(TrackerEngine.build(deleted).resources.isEmpty())
    }

    /**
     * HP is the worst of the nine, which is why it gets two assertions.
     *
     * [TrackerEngine.build] picks the HP row with `firstNotNullOfOrNull`, so a soft-deleted
     * hit-points attribute does not merely add a row — it can **shadow the real one**,
     * depending on nothing more than map iteration order. The tracker would then show, and
     * write damage to, a property the player deleted.
     */
    @Test
    fun `a soft-deleted hit points attribute is not discovered and cannot shadow the real one`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"attribute","variableName":"hitPoints",
               "name":"Hit Points","total":20,"value":20""",
        )
        assertEquals(20, TrackerEngine.build(live).hp?.total)
        assertNull("a deleted HP attribute still drives the HP row", TrackerEngine.build(deleted).hp)

        val both = sheet(
            """{"_id":"gone","type":"attribute","variableName":"hitPoints",
                "name":"Hit Points","total":99,"value":99,"removed":true}""",
            """{"_id":"real","type":"attribute","variableName":"hitPoints",
                "name":"Hit Points","total":20,"value":14}""",
        )
        val hp = TrackerEngine.build(both).hp
        assertEquals("the deleted HP row shadowed the live one", "real", hp?.propertyId)
        assertEquals(20, hp?.total)
        assertEquals(14, hp?.value)
    }

    @Test
    fun `a soft-deleted temp HP attribute is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"attribute","variableName":"tempHP",
               "name":"Temp HP","total":5,"value":5""",
        )
        assertEquals(5, TrackerEngine.build(live).tempHp?.total)
        assertNull(TrackerEngine.build(deleted).tempHp)
    }

    @Test
    fun `a soft-deleted item is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"item","name":"Potion","quantity":2""",
        )
        assertEquals(1, TrackerEngine.build(live).allItems.size)
        assertTrue(TrackerEngine.build(deleted).allItems.isEmpty())
    }

    /**
     * The toggle rule is the one that does **not** use the shared `isSkipped()` helper — it
     * checks `removed` by hand, deliberately, because for a toggle `inactive` is the state
     * being rendered rather than a reason to hide it. That makes it the rule most likely to
     * be "tidied" into the helper by someone who has not read the KDoc, which would change
     * its `inactive` behaviour. Both halves are pinned here so that edit fails loudly.
     */
    @Test
    fun `a soft-deleted toggle is not discovered, but an inactive one still is`() {
        val (live, deleted) = pair(""""_id":"p1","type":"toggle","name":"Bless","enabled":true""")
        assertEquals(1, TrackerEngine.build(live).activeToggles.size)
        assertTrue(TrackerEngine.build(deleted).activeToggles.isEmpty())

        val off = sheet(
            """{"_id":"p1","type":"toggle","name":"Bless","enabled":true,"inactive":true}""",
        )
        val row = TrackerEngine.build(off).activeToggles.single()
        assertFalse("an off toggle must stay discoverable — turning it on is how it is used", row.enabled)
    }

    @Test
    fun `a soft-deleted damage multiplier is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"damageMultiplier","name":"Ward","damageTypes":["fire"],"value":0.5""",
        )
        assertEquals(1, TrackerEngine.build(live).defenses.size)
        assertTrue(TrackerEngine.build(deleted).defenses.isEmpty())
    }

    @Test
    fun `a soft-deleted ability check is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"attribute","attributeType":"ability",
               "name":"Strength","total":8,"value":8,"modifier":-1""",
        )
        assertEquals(1, TrackerEngine.build(live).rolls.size)
        assertTrue(TrackerEngine.build(deleted).rolls.isEmpty())
    }

    @Test
    fun `a soft-deleted skill roll is not discovered`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"skill","skillType":"skill","name":"Stealth","value":3""",
        )
        assertEquals(1, TrackerEngine.build(live).rolls.size)
        assertTrue(TrackerEngine.build(deleted).rolls.isEmpty())
    }

    /**
     * The concentration banner is a *lookup*, not a list, and it has its own hand-written
     * copy of the filter rather than the shared helper — a second copy that can drift.
     *
     * Its failure mode is the quietest of the nine: the banner naming a concentration the
     * player ended by deleting the buff.
     */
    @Test
    fun `a soft-deleted concentration source does not drive the banner`() {
        val (live, deleted) = pair(
            """"_id":"p1","type":"buff","name":"Concentration: Bless"""",
        )
        assertEquals("Concentration: Bless", TrackerEngine.build(live).concentratingOn)
        assertNull(TrackerEngine.build(deleted).concentratingOn)
    }

    // -----------------------------------------------------------------------
    // The named filter on CreatureSheet (the accessor this wave added)
    // -----------------------------------------------------------------------

    @Test
    fun `livePropertyList drops soft-deleted documents and propertyList deliberately does not`() {
        val built = sheet(
            """{"_id":"a","type":"item","name":"Kept","quantity":1}""",
            """{"_id":"b","type":"item","name":"Gone","quantity":1,"removed":true}""",
        )

        assertEquals(
            "the raw accessor must stay raw — toSnapshotBody round-trips through it",
            2,
            built.propertyList.size,
        )
        assertEquals(listOf("a"), built.livePropertyList.map { it.string("_id") })
        assertTrue(built.hasLiveProperties)
    }

    @Test
    fun `a sheet holding only soft-deleted documents has no live properties`() {
        val built = sheet("""{"_id":"b","type":"item","name":"Gone","quantity":1,"removed":true}""")

        assertEquals(1, built.propertyList.size)
        assertFalse(
            "a mirror of nothing but deletions must not count as having content — it would " +
                "beat a good cached snapshot and blank the tracker",
            built.hasLiveProperties,
        )
        assertTrue(built.livePropertyList.isEmpty())
        assertTrue("and it must produce an empty board", TrackerEngine.build(built).isEmpty)
    }

    /**
     * `removed` is read as a *literal* `true`, never as truthiness.
     *
     * The field is absent on almost every document, and `"removed": false` appears on some;
     * a reader that treated presence as deletion would empty the board. This is the same
     * contract `JsonObject.isTrue` already states, pinned here because the whole audit rests
     * on it.
     */
    @Test
    fun `absent and false both mean not deleted`() {
        listOf(
            """{"_id":"p1","type":"item","name":"Potion","quantity":1}""",
            """{"_id":"p1","type":"item","name":"Potion","quantity":1,"removed":false}""",
            """{"_id":"p1","type":"item","name":"Potion","quantity":1,"removed":null}""",
        ).forEach { json ->
            assertEquals(json, 1, TrackerEngine.build(sheet(json)).allItems.size)
            assertEquals(json, 1, sheet(json).livePropertyList.size)
        }
    }
}
