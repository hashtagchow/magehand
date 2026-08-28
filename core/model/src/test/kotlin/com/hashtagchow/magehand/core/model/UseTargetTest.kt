package com.hashtagchow.magehand.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-28's client-derived gates, pinned in the module that owns them
 * (docs/design/17-use-action.md decisions 1, 2 and 3).
 *
 * ### Why these live here and not in `ActionEngineTest`
 *
 * The engine's job is *parsing* — turning `resources.attributesConsumed` into [CostLine]s — and
 * `ActionEngineTest` pins that against real JSON shapes. The gates are *arithmetic over the
 * parsed values*, and they are the part a future wave is most likely to "simplify" by reaching
 * for the server field sitting right next to the one being used. Testing them against
 * hand-built entries rather than through a JSON fixture makes each assertion name exactly one
 * rule, with no parsing between the input and the claim.
 */
class UseTargetTest {

    private fun spell(
        prepared: Boolean = true,
        alwaysPrepared: Boolean = false,
        inactive: Boolean = false,
        level: Int = 3,
        ritual: Boolean = false,
        cost: ActionCost = ActionCost.FREE,
        uses: ActionUses? = null,
    ) = SpellEntry(
        propertyId = "spell-1",
        name = "Fireball",
        level = level,
        ritual = ritual,
        prepared = prepared,
        alwaysPrepared = alwaysPrepared,
        inactive = inactive,
        cost = cost,
        uses = uses,
    )

    private fun action(
        inactive: Boolean = false,
        insufficientResources: Boolean = false,
        usesLeft: Int? = null,
        cost: ActionCost = ActionCost.FREE,
        uses: ActionUses? = null,
    ) = ActionEntry(
        propertyId = "action-1",
        name = "Rage",
        type = ActionType.BONUS,
        usesLeft = usesLeft,
        insufficientResources = insufficientResources,
        inactive = inactive,
        cost = cost,
        uses = uses,
    )

    // =======================================================================
    // Decision 2 — the prepared/active gate is STRUCTURAL
    // =======================================================================

    /**
     * The headline: an unprepared spell has **no value of the type a Use needs**.
     *
     * Not "usable is false" — though that too — but `useTarget == null`, which is what makes
     * decision 2's *"ABSENT, not disabled"* a property of the type system rather than a rule in a
     * composable. Every seam that reaches a use in this app takes a [UseTarget]; there is no
     * value of it here to pass.
     */
    @Test
    fun `an unprepared spell has no use target at all`() {
        val entry = spell(prepared = false, alwaysPrepared = false)
        assertFalse(entry.isUsable)
        assertNull("decision 2: ABSENT, not disabled", entry.useTarget)
    }

    /**
     * `alwaysPrepared` is the inverse trap, and it is why the gate reads two fields.
     *
     * A domain or racial spell has `prepared: false` forever — the field means "the player chose
     * this today" and such a spell was never chosen. Gating on `prepared` alone would hide the
     * Use button on every one of them, permanently, which is the failure mode 16 decision 5's
     * badge already had to be written around.
     */
    @Test
    fun `an always-prepared spell is usable though prepared is false`() {
        val entry = spell(prepared = false, alwaysPrepared = true)
        assertTrue(entry.isUsable)
        assertNotNull(entry.useTarget)
    }

    /**
     * `inactive` closes the gate on both kinds of row.
     *
     * Probe U2 found the server casts a switched-off spell and burns the slot, so this is not
     * politeness — it is the only gate there is. Asserted on the action side as well because the
     * two types compute usability separately (an action has no preparation) and a future edit to
     * one is exactly how they drift apart.
     */
    @Test
    fun `a switched-off row offers no use, spell or action`() {
        assertNull(spell(inactive = true).useTarget)
        assertNull(action(inactive = true).useTarget)
    }

    /**
     * Both reasons at once still yields nothing, and — the point — yields nothing *quietly*.
     *
     * 16 decision 5 established that unprepared and inactive can coexist and that both render.
     * The gate does not care which one closed it; there is no partial state where a row is
     * "usable except for one of them".
     */
    @Test
    fun `unprepared and inactive together still offer no use`() {
        assertNull(spell(prepared = false, inactive = true).useTarget)
    }

    // =======================================================================
    // Decision 1 — usability is CLIENT-DERIVED, both directions
    // =======================================================================

    /**
     * THE TRAP, direction one: a stale `insufficientResources: true` does **not** block a use the
     * app can see is funded.
     *
     * This is the assertion somebody deletes while tidying, because it reads like the app
     * ignoring a warning. It is: probe U5 measured the flag on a debounced recompute 4–10 s
     * behind the write, so after a rest restores a resource the flag keeps saying "insufficient"
     * for the whole window — and a Use gated on it is dead for ten seconds on a character who
     * can plainly see the charges are back.
     */
    @Test
    fun `a stale insufficientResources flag does not block a funded use`() {
        val entry = action(
            insufficientResources = true,
            cost = ActionCost(attributes = listOf(CostLine(name = "Rage", amount = 1, available = 3))),
        )
        assertTrue("the client's own arithmetic wins", entry.isUsable)
        assertNotNull(entry.useTarget)
    }

    /**
     * THE TRAP, direction two: a stale `insufficientResources: false` does **not** permit a use
     * the app can see is unfunded.
     *
     * The dangerous-looking direction, and the easier one to get right. Both are needed: a gate
     * that read the server field would be wrong in one direction or the other depending only on
     * which side of the debounce the tap landed.
     */
    @Test
    fun `a stale clear flag does not permit an unfunded use`() {
        val entry = action(
            insufficientResources = false,
            cost = ActionCost(attributes = listOf(CostLine(name = "Rage", amount = 1, available = 0))),
        )
        assertFalse(entry.isUsable)
        assertNull(entry.useTarget)
    }

    /**
     * The same split for charges: `usesLeft` says one is left, `uses.value − usesUsed` says none,
     * and the client's pair wins.
     *
     * This is probe U3's double-spend in miniature. The server's rollup is the field that stayed
     * at 1 through the burst; the counter is the one that moved. Gating on the rollup is what
     * produced three "Spent" log entries from a one-use ability.
     */
    @Test
    fun `an exhausted row is unusable even while usesLeft still reads one`() {
        val entry = action(usesLeft = 1, uses = ActionUses(max = 1, used = 1))
        assertEquals(0, entry.uses?.remaining)
        assertFalse(entry.isUsable)
        assertNull(entry.useTarget)
    }

    /** A row with no `uses` at all is unlimited, not exhausted — the nullability is load-bearing. */
    @Test
    fun `a row with no uses field is unlimited`() {
        assertTrue(action(uses = null).isUsable)
    }

    /** `usesUsed` overrunning `uses` reads as exhausted rather than as a negative remainder. */
    @Test
    fun `remaining never goes negative`() {
        assertEquals(0, ActionUses(max = 2, used = 5).remaining)
        assertTrue(ActionUses(max = 2, used = 5).isExhausted)
    }

    /**
     * An unresolvable cost line is **satisfied**, not refused.
     *
     * A cost naming something the sheet does not carry is one this app could not evaluate, and
     * treating "I don't know" as "no" would make the row permanently unusable here while the
     * official UI casts it happily — with no error and no explanation. Erring the other way costs
     * at most one server call, which `doCastSpell` refuses verbatim and `doAction` swallows.
     */
    @Test
    fun `a cost the sheet cannot be joined to does not block the use`() {
        val entry = action(cost = ActionCost(attributes = listOf(CostLine("Mystery", amount = 2, available = null))))
        assertTrue(entry.isUsable)
    }

    /** An item cost is checked the same way an attribute cost is, and both must pass. */
    @Test
    fun `every cost line has to be satisfied`() {
        val entry = action(
            cost = ActionCost(
                attributes = listOf(CostLine("Ki", amount = 1, available = 5)),
                items = listOf(CostLine("Arrows", amount = 3, available = 2)),
            ),
        )
        assertFalse("one short line is enough to close the gate", entry.isUsable)
    }

    /** [ActionCost.FREE] is the "Free" the detail sheet prints, and it is always satisfiable. */
    @Test
    fun `a free action costs nothing and is always affordable`() {
        assertTrue(ActionCost.FREE.isFree)
        assertTrue(ActionCost.FREE.satisfied)
        assertTrue(ActionCost.FREE.lines.isEmpty())
    }

    // =======================================================================
    // Decision 3 — the slot picker
    // =======================================================================

    private fun slot(id: String, level: Int?, remaining: Int, total: Int = 4) = TrackedResource(
        propertyId = id,
        kind = TrackerKind.SPELL_SLOT,
        name = "Slot $id",
        value = remaining,
        total = total,
        spellSlotLevel = level,
    )

    /**
     * THE TRAP: the picker never offers a **depleted** or a **too-small** slot.
     *
     * Both exclusions in one assertion, on one list, because a test that checked them separately
     * would pass against an implementation that had accidentally made them the same condition.
     * Here the level-1 slot is full and excluded for being small; the level-4 slot is big enough
     * and excluded for being empty; only the level-3 and level-5 slots survive.
     */
    @Test
    fun `the picker offers no depleted and no too-small slot`() {
        val slots = listOf(
            slot("l1", level = 1, remaining = 4),
            slot("l2", level = 2, remaining = 2),
            slot("l3", level = 3, remaining = 1),
            slot("l4", level = 4, remaining = 0),
            slot("l5", level = 5, remaining = 2),
        )

        assertEquals(
            listOf("l3", "l5"),
            spellSlotOptions(slots, spellLevel = 3).map { it.propertyId },
        )
    }

    /**
     * A slot with no level is **dropped**, not sorted last.
     *
     * `TrackerEngine` leaves `spellSlotLevel` null when neither the field nor the name's leading
     * ordinal resolves, and its own ordering puts such a row last because a *list* has to put it
     * somewhere. A picker has no such obligation, and "is this level at least 3" is not a
     * question that can be answered about a slot whose level is unknown. Offering it would be
     * guessing on the player's behalf about which slot they are spending.
     */
    @Test
    fun `a slot with no level is not offered`() {
        val slots = listOf(slot("unknown", level = null, remaining = 3))
        assertTrue(spellSlotOptions(slots, spellLevel = 1).isEmpty())
    }

    /** The cheapest legal slot leads: it is the default, and it is right more often than any other. */
    @Test
    fun `options are ordered cheapest first`() {
        val slots = listOf(
            slot("l5", level = 5, remaining = 1),
            slot("l2", level = 2, remaining = 1),
            slot("l3", level = 3, remaining = 1),
        )
        assertEquals(listOf(2, 3, 5), spellSlotOptions(slots, spellLevel = 2).map { it.level })
    }

    /** The remaining/total pair comes off the tracker row, so the picker and the pips agree. */
    @Test
    fun `an option carries the row's own remaining and total`() {
        val option = spellSlotOptions(listOf(slot("l1", level = 1, remaining = 2, total = 4)), 1).single()
        assertEquals(2, option.remaining)
        assertEquals(4, option.total)
    }

    /** A cantrip needs no slot, which is what stops the picker being drawn for one. */
    @Test
    fun `a cantrip target needs no slot and a leveled one does`() {
        val cantrip = spell(level = 0).useTarget
        assertNotNull(cantrip)
        assertFalse(cantrip!!.needsSlot)
        assertTrue(spell(level = 1).useTarget!!.needsSlot)
    }

    /** The ritual flag rides the target, so the checkbox is offered only where the sheet says so. */
    @Test
    fun `the target carries the sheet's own ritual flag`() {
        assertTrue(spell(ritual = true).useTarget!!.ritual)
        assertFalse(spell(ritual = false).useTarget!!.ritual)
    }

    /** The target carries what the confirm dialog has to print: the name, the cost and the uses. */
    @Test
    fun `the target carries what the confirm dialog needs`() {
        val cost = ActionCost(attributes = listOf(CostLine("Rage", amount = 1, available = 3)))
        val target = action(cost = cost, uses = ActionUses(max = 3, used = 1)).useTarget
        assertNotNull(target)
        assertEquals("Rage", target!!.name)
        assertEquals("action-1", target.propertyId)
        assertEquals(cost, target.cost)
        assertEquals(2, target.uses?.remaining)
    }
}
