package com.hashtagchow.magehand.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * FR-29's local Actions board (docs/design/18-table-pack.md decisions 1–4).
 *
 * ### What "reused, not forked" has to mean at this layer
 *
 * 09 decision 5's claim about the tracker, extended to a third surface: the local path produces
 * the **same** `ActionBoard` the DiceCloud engine produces, so the same `toActionsUiState`, the
 * same sectioning, the same detail sheet and the same `UseTarget` gate all apply with no local
 * branch anywhere above this file. These tests are therefore mostly about the *domain* values —
 * because if those are right, everything above is already tested by FR-26's and FR-28's suites.
 *
 * ### The two structural claims
 *
 * An action row is **not** a tracker row (decision 1's model), and the fence against cost chaining
 * (decision 2) holds at the board even though the form is where it is enforced. Both are asserted
 * here rather than left to the screens.
 */
class LocalActionBoardTest {

    private val characterId = "local-1"

    private fun row(
        id: String,
        kind: LocalRowKind,
        label: String,
        total: Int = 1,
        current: Int = total,
        sortIndex: Int = 0,
        reset: ResetRule? = null,
        description: String? = null,
        costRowId: String? = null,
        costAmount: Int? = null,
        spellLevel: Int? = null,
        higherLevels: String? = null,
        castingTime: String? = null,
        range: String? = null,
        components: String? = null,
        duration: String? = null,
        concentration: Boolean = false,
        ritual: Boolean = false,
        damage: String? = null,
        properties: String? = null,
    ) = LocalTrackerRow(
        id = id,
        characterId = characterId,
        kind = kind,
        label = label,
        total = total,
        current = current,
        reset = reset,
        sortIndex = sortIndex,
        description = description,
        costRowId = costRowId,
        costAmount = costAmount,
        spellLevel = spellLevel,
        higherLevels = higherLevels,
        castingTime = castingTime,
        range = range,
        components = components,
        duration = duration,
        concentration = concentration,
        ritual = ritual,
        damage = damage,
        properties = properties,
    )

    private fun rage(current: Int = 2) =
        row("rage", LocalRowKind.RESOURCE, "Rage", total = 3, current = current, reset = ResetRule.LONG_REST)

    private fun arrows(current: Int = 12) =
        row("arrows", LocalRowKind.ITEM, "Arrows", total = current, current = current, sortIndex = 1)

    // --- decision 1: the model ----------------------------------------------

    @Test
    fun `an action row becomes an entry with its label, description and uses`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    "act",
                    LocalRowKind.ACTION,
                    "Second Wind",
                    total = 2,
                    current = 1,
                    description = "Regain 1d10 + level hit points.",
                    sortIndex = 5,
                ),
            ),
        )

        val entry = board.actions.single()
        assertEquals("act", entry.propertyId)
        assertEquals("Second Wind", entry.name)
        assertEquals("Regain 1d10 + level hit points.", entry.description)
        assertEquals(2, entry.uses?.max)
        assertEquals("local rows store what is LEFT; ActionUses stores what was SPENT", 1, entry.uses?.used)
        assertEquals(1, entry.uses?.remaining)
        assertEquals(5, entry.sortOrder)
    }

    /**
     * `total == 0` is **unlimited**, and the distinction from "exhausted" is the whole point.
     *
     * An `ActionUses(max = 0)` would report `isExhausted`, which would hide the Use button on
     * every unconditional action a player types — the most common kind. `null` is what the server
     * path publishes for an action that states no `uses`, so both sources say the same thing about
     * the same fact.
     */
    @Test
    fun `zero uses means unlimited, not exhausted`() {
        val entry = LocalActionBoard
            .build(listOf(row("act", LocalRowKind.ACTION, "Shove", total = 0, current = 0)))
            .actions.single()

        assertNull(entry.uses)
        assertNull(entry.usesLeft)
        assertNull(entry.usesMax)
        assertTrue("an unlimited action is always usable", entry.isUsable)
        assertNotNull(entry.useTarget)
    }

    /**
     * `usesLeft` / `usesMax` agree with `uses` **by construction** here.
     *
     * On the server path those two are the lagging rollup the list row prints, while `ActionUses`
     * is the synchronous pair the Use gate reads — the split that stops probe U3's double-spend.
     * Locally there is one Room column answering both questions, so agreement is not a coincidence
     * to be maintained but a property of there being one source. Asserted so a future edit that
     * introduced a second source would have to face the question.
     */
    @Test
    fun `the display counts and the gate counts cannot disagree locally`() {
        val entry = LocalActionBoard
            .build(listOf(row("act", LocalRowKind.ACTION, "Rage", total = 3, current = 1)))
            .actions.single()

        assertEquals(entry.uses?.remaining, entry.usesLeft)
        assertEquals(entry.uses?.max, entry.usesMax)
    }

    // --- decision 1: the cost -----------------------------------------------

    @Test
    fun `a cost naming a resource joins against that row's remaining count`() {
        val board = LocalActionBoard.build(
            listOf(
                rage(current = 2),
                row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, costRowId = "rage", costAmount = 1),
            ),
        )

        val cost = board.actions.single().cost
        assertFalse(cost.isFree)
        with(cost.attributes.single()) {
            assertEquals("Rage", name)
            assertEquals(1, amount)
            assertEquals(2, available)
            assertTrue(satisfied)
        }
        assertTrue("an item cost belongs on the other list", cost.items.isEmpty())
    }

    /**
     * An **item** cost lands in `ActionCost.items`, matching the server path's own split.
     *
     * Nothing downstream distinguishes them — `lines` is the concatenation and the UI draws both
     * identically — so this is a naming decision. It is made this way because the server's split
     * is exactly the same distinction (`attributesConsumed` versus `itemsConsumed`), and having
     * the two sources describe one cost differently would be a difference a reader could not
     * recover the reason for.
     */
    @Test
    fun `a cost naming an item lands on the items list`() {
        val board = LocalActionBoard.build(
            listOf(
                arrows(current = 12),
                row("act", LocalRowKind.ACTION, "Volley", total = 0, costRowId = "arrows", costAmount = 3),
            ),
        )

        val cost = board.actions.single().cost
        assertTrue(cost.attributes.isEmpty())
        assertEquals("Arrows", cost.items.single().name)
        assertEquals(3, cost.items.single().amount)
        assertEquals(12, cost.items.single().available)
    }

    @Test
    fun `an action with no cost is free`() {
        val board = LocalActionBoard.build(
            listOf(row("act", LocalRowKind.ACTION, "Dodge", total = 0)),
        )

        assertTrue(board.actions.single().cost.isFree)
    }

    /**
     * An underfunded cost makes the action **unusable**, and unusable means the Use is *absent*.
     *
     * `useTarget` returning null is 17 decision 2's "ABSENT — not disabled" expressed as a type,
     * and it is the gate the whole Use path goes through. The detail sheet then renders the reason
     * instead of a dead button — decision 4's *"Insufficient cost → Use absent with the reason in
     * the sheet (mirror the server surface's honesty)"*.
     */
    @Test
    fun `an underfunded cost removes the use`() {
        val board = LocalActionBoard.build(
            listOf(
                rage(current = 0),
                row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, costRowId = "rage", costAmount = 1),
            ),
        )

        val entry = board.actions.single()
        assertFalse(entry.cost.satisfied)
        assertFalse(entry.isUsable)
        assertNull("no target means no button, which is the gate", entry.useTarget)
    }

    @Test
    fun `an exhausted action removes the use`() {
        val entry = LocalActionBoard
            .build(listOf(row("act", LocalRowKind.ACTION, "Second Wind", total = 1, current = 0)))
            .actions.single()

        assertTrue(entry.uses!!.isExhausted)
        assertNull(entry.useTarget)
    }

    /**
     * A cost naming a row that **no longer exists** is permitted, not refused.
     *
     * `costRowId` carries no `FOREIGN KEY` on purpose — a cascade would delete an action when the
     * resource it spends is deleted, which is not what deleting a resource means — so a dangling
     * reference is a live possibility rather than an impossible state. The line is dropped, which
     * lands the action on the permissive side of `CostLine.satisfied`'s asymmetry: the app has not
     * *evaluated* the cost as zero, it has failed to evaluate it at all, and erring the other way
     * would make the row permanently unusable with no explanation the player could act on.
     */
    @Test
    fun `a cost naming a deleted row is dropped rather than blocking the use`() {
        val board = LocalActionBoard.build(
            listOf(row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, costRowId = "gone", costAmount = 1)),
        )

        val entry = board.actions.single()
        assertTrue(entry.cost.isFree)
        assertTrue(entry.isUsable)
    }

    /** Half a cost is no cost — the same normalisation the entity mapping already applies. */
    @Test
    fun `half a cost is no cost`() {
        val amountOnly = LocalActionBoard
            .build(listOf(row("a", LocalRowKind.ACTION, "X", total = 0, costAmount = 2)))
            .actions.single()
        assertTrue(amountOnly.cost.isFree)

        val rowOnly = LocalActionBoard
            .build(listOf(rage(), row("b", LocalRowKind.ACTION, "Y", total = 0, costRowId = "rage")))
            .actions.single()
        assertTrue(rowOnly.cost.isFree)
    }

    // --- decision 3: grouping and gating ------------------------------------

    /**
     * Decision 3: *"one 'Actions' section (no actionType taxonomy locally)"*.
     *
     * The shared sectioning code groups by `ActionEntry.group`, so producing one section means
     * producing one group — and the group whose header reads "Actions" is [ActionType.ACTION]'s.
     * The honest-looking alternative, a null type, files the row under **Other**, which is the
     * group defined by *not being* one of the four named ones. A local action is not an
     * unclassifiable row; it is the only kind of row this model has.
     */
    @Test
    fun `every local action is in the one Actions group`() {
        val board = LocalActionBoard.build(
            listOf(
                row("a", LocalRowKind.ACTION, "Dodge", total = 0, sortIndex = 0),
                row("b", LocalRowKind.ACTION, "Dash", total = 0, sortIndex = 1),
            ),
        )

        assertEquals(listOf(ActionType.ACTION, ActionType.ACTION), board.actions.map { it.type })
        assertEquals(setOf(ActionGroup.ACTIONS), board.actions.map { it.group }.toSet())
    }

    /** Decision 3's discovery gate is `ActionBoard.isEmpty`, the same one the server surface uses. */
    @Test
    fun `a character with no action rows has an empty board`() {
        val board = LocalActionBoard.build(listOf(rage(), arrows()))

        assertTrue(board.isEmpty)
        assertEquals(0, board.rowCount)
    }

    /** The player's order, then label — `sortIndex`, 09 decision 8's one mechanism. */
    @Test
    fun `actions render in the player's own order`() {
        val board = LocalActionBoard.build(
            listOf(
                row("c", LocalRowKind.ACTION, "Third", total = 0, sortIndex = 2),
                row("a", LocalRowKind.ACTION, "First", total = 0, sortIndex = 0),
                row("b", LocalRowKind.ACTION, "Second", total = 0, sortIndex = 1),
            ),
        )

        assertEquals(listOf("First", "Second", "Third"), board.actions.map { it.name })
    }

    /**
     * No spells, ever — and therefore no upcast picker and no spell-list header.
     *
     * 18 decision 1 gives local characters actions and deliberately not spells. This is what makes
     * `LocalCharacterHomeViewModel.use`'s `UseTarget.Spell` branch unreachable rather than merely
     * unused, and it is why the local Actions surface needs no slot list threaded into it.
     */
    @Test
    fun `a local board carries no spells and no spell lists`() {
        val board = LocalActionBoard.build(
            listOf(rage(), row("act", LocalRowKind.ACTION, "Enter Rage", total = 0)),
        )

        assertTrue(board.spells.isEmpty())
        assertTrue(board.spellLists.isEmpty())
        assertEquals(1, board.rowCount)
    }

    // --- decision 1: an action is not a tracker row --------------------------

    /**
     * The structural half of decision 1: an action row cannot reach the tracker.
     *
     * `LocalRowKind.ACTION` maps to no `TrackerKind`, so `toTrackedResource` returns null and
     * `LocalTrackerBoard` drops it — which means "an action never appears among the slots,
     * resources or items" is a property of the type rather than four filters somebody has to
     * remember. Asserted from the board a player actually sees.
     */
    @Test
    fun `an action row does not appear on the tracker board`() {
        val rows = listOf(
            rage(),
            arrows(),
            row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, sortIndex = 2),
        )

        val tracker = LocalTrackerBoard.build(
            character = com.hashtagchow.magehand.core.model.LocalCharacter(
                id = characterId,
                name = "Brambles",
                level = 3,
                abilities = com.hashtagchow.magehand.core.model.AbilityScores.DEFAULTS,
                maxHp = 30,
                currentHp = 30,
                armorClass = 15,
                createdAt = 1,
                updatedAt = 1,
            ),
            rows = rows,
        )

        assertEquals(listOf("rage"), tracker.resources.map { it.propertyId })
        assertEquals(listOf("arrows"), tracker.allItems.map { it.propertyId })
        assertTrue(tracker.slots.isEmpty())
        assertFalse("act" in (tracker.resources + tracker.allItems + tracker.slots).map { it.propertyId })
        // …and the action IS on the other board, so this is a routing assertion rather than a
        // "the row was dropped" one.
        assertEquals(listOf("act"), LocalActionBoard.build(rows).actions.map { it.propertyId })
    }

    // --- FR-49 (docs/design/20-local-spells-and-attacks.md) ------------------

    /**
     * A SPELL row becomes a `SpellEntry` carrying every scalar the detail sheet draws — including
     * the two fields FR-49 added to that type and the upcast paragraph decision 5 is about.
     *
     * `showsUnpreparedBadge` is the assertion that costs nothing to make and would be expensive to
     * miss: 20 decision 9 says a local spell is *known* by being added, and a board that left
     * `alwaysPrepared` false would badge every one of them "Unprepared" **and** — because
     * `SpellEntry.isUsable` reads the badge — remove the Cast button from all of them.
     */
    @Test
    fun `a spell row becomes a spell entry with its scalars and its upcast paragraph`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    id = "fireball",
                    kind = LocalRowKind.SPELL,
                    label = "Fireball",
                    total = 0,
                    spellLevel = 3,
                    description = "A bright streak flashes from your pointing finger.",
                    higherLevels = "The damage increases by 1d6 for each slot level above 3rd.",
                    castingTime = "1 action",
                    range = "150 feet",
                    components = "V, S, M",
                    duration = "Instantaneous",
                ),
            ),
        )

        with(board.spells.single()) {
            assertEquals("fireball", propertyId)
            assertEquals("Fireball", name)
            assertEquals(3, level)
            assertEquals("1 action", castingTime)
            assertEquals("150 feet", range)
            assertEquals("V, S, M", components)
            assertEquals("Instantaneous", duration)
            assertEquals("The damage increases by 1d6 for each slot level above 3rd.", higherLevels)
            assertFalse("20 decision 9: known, not prepared", showsUnpreparedBadge)
            assertTrue("so the Cast button exists at all", isUsable)
            assertNotNull(useTarget)
            assertNull("an unlimited spell casts from a slot", uses)
        }
        assertTrue("a spell is not an action row", board.actions.isEmpty())
    }

    /**
     * Concentration and ritual reach the chips, and the ritual flag reaches `UseTarget.Spell` —
     * which is what draws the confirm dialog's honest *"cast as a ritual"* checkbox.
     */
    @Test
    fun `a spell's concentration and ritual flags reach the entry and the use target`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    id = "detect",
                    kind = LocalRowKind.SPELL,
                    label = "Detect Magic",
                    total = 0,
                    spellLevel = 1,
                    concentration = true,
                    ritual = true,
                ),
            ),
        )

        with(board.spells.single()) {
            assertTrue(concentration)
            assertTrue(ritual)
            assertTrue(useTarget!!.ritual)
            assertTrue("a leveled spell with no uses needs a slot", useTarget!!.needsSlot)
        }
    }

    /**
     * A spell with **uses** is decision 4's innate casting: it spends its own charge, so
     * `needsSlot` is false and the picker is not drawn.
     *
     * `total == 0` is the unlimited convention these kinds share with ACTION, and it is what the
     * distinction rests on — so this test is also the pin on that convention meaning the same
     * thing for a third kind.
     */
    @Test
    fun `a spell with uses is innate and needs no slot`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    id = "innate",
                    kind = LocalRowKind.SPELL,
                    label = "Misty Step",
                    total = 2,
                    current = 1,
                    spellLevel = 2,
                ),
            ),
        )

        with(board.spells.single()) {
            assertEquals(2, uses?.max)
            assertEquals("one already spent", 1, uses?.remaining)
            assertFalse("it spends its own charge, not a slot", useTarget!!.needsSlot)
        }
    }

    /** An exhausted spell offers no Cast — 17 decision 1's usability rule, unchanged. */
    @Test
    fun `an exhausted innate spell has no use target`() {
        val board = LocalActionBoard.build(
            listOf(
                row("innate", LocalRowKind.SPELL, "Misty Step", total = 1, current = 0, spellLevel = 2),
            ),
        )

        assertTrue(board.spells.single().uses!!.isExhausted)
        assertNull(board.spells.single().useTarget)
    }

    /**
     * An ATTACK row files under **Attacks** and carries its damage and properties as *text*
     * (20 decisions 1 and 8).
     *
     * The three negatives are the decision: no `DamageLine`, no `attackRoll`, and therefore no
     * number this app invented. They are asserted rather than assumed because each of them is a
     * field that exists on the type and is tempting to fill.
     */
    @Test
    fun `an attack row files under Attacks with text facts and no invented numbers`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    id = "longsword",
                    kind = LocalRowKind.ATTACK,
                    label = "Longsword",
                    total = 0,
                    damage = "1d8 / 1d10 slashing",
                    properties = "Versatile (1d10), Mastery: Sap",
                ),
            ),
        )

        with(board.actions.single()) {
            assertEquals(ActionType.ATTACK, type)
            assertEquals(ActionGroup.ATTACKS, group)
            assertEquals("1d8 / 1d10 slashing", damageText)
            assertEquals("Versatile (1d10), Mastery: Sap", properties)
            assertTrue("20 decision 8: no rollups", damage.isEmpty())
            assertNull("20 decision 8: no attack bonus", attackRoll)
        }
        assertTrue("an attack is not a spell", board.spells.isEmpty())
    }

    /**
     * FR-47's badge, lifted out of the property string — and **not** invented when the string has
     * no mastery in it.
     *
     * The three cases are the three the regex has to get right: a mastery bounded by a following
     * comma, a mastery at the end of the list, and no mastery at all. The fourth — `Mastery:` with
     * nothing after it — produces no badge rather than an empty one, matching `ActionEngine`'s own
     * rule that a mastery with no word is not one.
     */
    @Test
    fun `mastery is read out of the property string and never invented`() {
        fun masteryOf(properties: String?) = LocalActionBoard
            .build(listOf(row("w", LocalRowKind.ATTACK, "Weapon", total = 0, properties = properties)))
            .actions
            .single()
            .mastery

        assertEquals("Sap", masteryOf("Versatile (1d10), Mastery: Sap")?.name)
        assertEquals("Nick", masteryOf("Mastery: Nick, Light, Finesse")?.name)
        assertNull(masteryOf("Heavy, Two-Handed"))
        assertNull(masteryOf(null))
        assertNull("a mastery with no word is not one", masteryOf("Heavy, Mastery:  "))
        assertNull(
            "the text half is the sheet's, and the SRD weapon table has none",
            masteryOf("Mastery: Sap")?.text,
        )
    }

    /**
     * An **action** row keeps every FR-49 field absent, whatever the row happens to carry.
     *
     * The columns are shared across six kinds now, so "this kind does not read that column" is the
     * property that keeps them from leaking — a spell's casting time surfacing on a rage would be
     * the kind of defect that renders perfectly and means nothing.
     */
    @Test
    fun `an action row shows no attack facts even if the columns are populated`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    id = "act",
                    kind = LocalRowKind.ACTION,
                    label = "Enter Rage",
                    total = 0,
                    damage = "2d6 bludgeoning",
                    properties = "Mastery: Sap",
                ),
            ),
        )

        with(board.actions.single()) {
            assertEquals(ActionType.ACTION, type)
            assertNull(damageText)
            assertNull(properties)
            assertNull(mastery)
        }
    }

    /**
     * Spells come out **level-ordered**, because `toActionsUiState` groups them with `groupBy` and
     * takes the section order from first encounter.
     *
     * A player adds spells in the order they think of them, so the stored `sortIndex` is nothing
     * like level order — and a board that returned them in it would draw *"Level 3 · Cantrips ·
     * Level 1"* headers. Within a level the player's own order is kept, which is the half that
     * makes `sortIndex` still mean something.
     */
    @Test
    fun `spells come out ordered by level, then by the player's own order`() {
        val board = LocalActionBoard.build(
            listOf(
                row("fireball", LocalRowKind.SPELL, "Fireball", total = 0, sortIndex = 0, spellLevel = 3),
                row("light", LocalRowKind.SPELL, "Light", total = 0, sortIndex = 1, spellLevel = 0),
                row("shield", LocalRowKind.SPELL, "Shield", total = 0, sortIndex = 2, spellLevel = 1),
                row("mage-hand", LocalRowKind.SPELL, "Mage Hand", total = 0, sortIndex = 3, spellLevel = 0),
            ),
        )

        assertEquals(
            listOf("light", "mage-hand", "shield", "fireball"),
            board.spells.map { it.propertyId },
        )
    }

    /**
     * Neither new kind reaches the tracker — the structural half of decision 1, restated for the
     * two kinds that were added after it was written.
     *
     * The routing assertion is the point, as it was for ACTION: the rows are not *dropped*, they
     * are on the other board.
     */
    @Test
    fun `spell and attack rows do not appear on the tracker board`() {
        val rows = listOf(
            rage(),
            row("slot-1", LocalRowKind.SLOT, "1st Level", total = 4, sortIndex = 1, spellLevel = 1),
            row("fireball", LocalRowKind.SPELL, "Fireball", total = 0, sortIndex = 2, spellLevel = 3),
            row("longsword", LocalRowKind.ATTACK, "Longsword", total = 0, sortIndex = 3),
        )

        val tracker = LocalTrackerBoard.build(
            character = com.hashtagchow.magehand.core.model.LocalCharacter(
                id = characterId,
                name = "Brambles",
                level = 3,
                abilities = com.hashtagchow.magehand.core.model.AbilityScores.DEFAULTS,
                maxHp = 30,
                currentHp = 30,
                armorClass = 15,
                createdAt = 1,
                updatedAt = 1,
            ),
            rows = rows,
        )

        assertEquals(listOf("rage"), tracker.resources.map { it.propertyId })
        assertEquals(listOf("slot-1"), tracker.slots.map { it.propertyId })
        assertEquals("decision 3's level reaches the board", 1, tracker.slots.single().spellSlotLevel)
        assertTrue(tracker.allItems.isEmpty())

        val board = LocalActionBoard.build(rows)
        assertEquals(listOf("fireball"), board.spells.map { it.propertyId })
        assertEquals(listOf("longsword"), board.actions.map { it.propertyId })
    }

    /**
     * A cost on a spell or an attack resolves exactly as it does on an action — 20 decision 2
     * allows one (*"a warlock's invocation costing a resource row"*), and the cost machinery is
     * FR-29's, untouched.
     */
    @Test
    fun `a spell may cost another row, and the line joins against it`() {
        val board = LocalActionBoard.build(
            listOf(
                rage(current = 2),
                row(
                    id = "invocation",
                    kind = LocalRowKind.SPELL,
                    label = "Eldritch Smite",
                    total = 0,
                    sortIndex = 1,
                    spellLevel = 1,
                    costRowId = "rage",
                    costAmount = 1,
                ),
            ),
        )

        with(board.spells.single().cost.lines.single()) {
            assertEquals("Rage", name)
            assertEquals(1, amount)
            assertEquals(2, available)
        }
    }

    /** The board still stays empty for a character with nothing on this surface. */
    @Test
    fun `a character with only tracker rows still has an empty action board`() {
        val board = LocalActionBoard.build(listOf(rage(), arrows()))

        assertTrue(board.isEmpty)
        assertTrue(board.spells.isEmpty())
        assertTrue(board.actions.isEmpty())
    }
}
