package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.hashtagchow.magehand.core.model.DamageRider
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionType

/**
 * FR-26's discovery rules (docs/design/16-actions-and-feed.md decisions 2–5).
 *
 * Two kinds of case, deliberately:
 *
 *  - **Synthetic**, for the rules whose failure mode is silent. The damage walk and the
 *    attack-roll trap are both "renders something plausible and wrong", so they are pinned
 *    against subtrees built here where the expected answer is written down beside the input.
 *  - **Against the live capture**, for the claims this wave made *about the server* — those are
 *    only worth anything if they are checked against what the server actually sent. Those tests
 *    derive every identifier rather than declaring one, so no real id or name enters the source
 *    (`tools/public-gate.sh`, and the FR-4 regression that gate exists for).
 */
class ActionEngineTest {

    // -----------------------------------------------------------------------
    // Synthetic sheet builders
    // -----------------------------------------------------------------------

    private fun sheetOf(vararg properties: JsonObject): CreatureSheet =
        CreatureSheet(properties.associateBy { it.string("_id")!! })

    private fun prop(
        id: String,
        type: String,
        parent: String? = null,
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", type)
        if (parent != null) {
            put("parent", buildJsonObject { put("id", parent); put("collection", "creatureProperties") })
        }
        build()
    }

    /** A `_calculation` wrapper — `{calculation, value}` — as every numeric field arrives. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.calc(
        key: String,
        calculation: String,
        value: Int,
    ) = put(key, buildJsonObject { put("calculation", calculation); put("value", value) })

    private fun kotlinx.serialization.json.JsonObjectBuilder.calcText(
        key: String,
        calculation: String,
        value: String,
    ) = put(key, buildJsonObject { put("calculation", calculation); put("value", value) })

    // -----------------------------------------------------------------------
    // DECISION 4 — THE SPELL attackRoll TRAP
    // -----------------------------------------------------------------------

    /**
     * **The trap, named.**
     *
     * 16 decision 4: *"NEVER render a spell hit bonus from `attackRoll.value` (probe trap: it
     * reads 0 at rest — the real bonus only resolves in a cast context)"*.
     *
     * This test builds the exact confusing pair: a **spell** carrying `attackRoll.value 0` and a
     * **weapon** carrying `attackRoll.value 6`, on one sheet, with identical field names. The two
     * render differently, and the difference is a **RULE about the property type**, not a
     * consequence of the numbers:
     *
     *  - the weapon's 6 is published and true, so [ActionEntry.attackRoll] carries it;
     *  - the spell's 0 is a reference (`#spellList.attackRollBonus`) that has not resolved, so
     *    `SpellEntry` **has no field for it at all**.
     *
     * The second half is what makes this permanent. If `SpellEntry` ever grows an `attackRoll`,
     * this file stops compiling — the guarantee is in the type, and this test is the note saying
     * why the type is shaped that way. Swapping the numbers would not change the outcome: a spell
     * publishing `7` would still not be rendered, because the rule is about which document it is.
     */
    @Test
    fun `a spell's attackRoll is never rendered and a weapon's always is - THE TRAP`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("spell1", "spell") {
                    put("name", "Trap Spell")
                    put("level", 1)
                    // The exact live shape: a reference into the casting context, value 0 at rest.
                    calc("attackRoll", "#spellList.attackRollBonus", 0)
                },
                prop("weapon1", "action") {
                    put("name", "Trap Weapon")
                    put("actionType", "attack")
                    calc("attackRoll", "max(daggerWeapon,simpleMeleeWeapon)", 6)
                },
            ),
        )

        val weapon = board.actions.single()
        assertEquals("a weapon's published bonus is real and is shown", 6, weapon.attackRoll)

        // And the spell. There is deliberately nothing to assert a value against — the property
        // does not exist. What CAN be asserted is that the engine did not smuggle the 0 anywhere
        // a renderer could reach it, which is the whole content of the rule.
        val spell = board.spells.single()
        assertEquals("Trap Spell", spell.name)
        assertEquals(
            "a spell row carries only what the server computed honestly at rest; if this list " +
                "ever gains a hit bonus, read SpellEntry's KDoc before believing it",
            listOf<Any?>(1, false, false, null, null),
            listOf(spell.level, spell.concentration, spell.ritual, spell.castingTime, spell.range),
        )
    }

    /**
     * The trap is not an artefact of the synthetic fixture — **every** spell on the real sheet
     * that carries an `attackRoll` publishes `0`, and at least one action publishes non-zero.
     *
     * Derived, never declared: no spell name and no property id appears here.
     */
    @Test
    fun `on the live capture every spell attackRoll reads zero and some action's does not`() {
        val properties = Fixtures.sabrielSheet().livePropertyList

        val spellRolls = properties
            .filter { it.string("type") == "spell" && it["attackRoll"] != null }
            .mapNotNull { it.number("attackRoll") }
        val actionRolls = properties
            .filter { it.string("type") == "action" && it["attackRoll"] != null }
            .mapNotNull { it.number("attackRoll") }

        assertTrue("the capture must carry spells with an attackRoll for this to mean anything",
            spellRolls.isNotEmpty())
        assertEquals(
            "every spell's attackRoll reads 0 at rest — this is the trap, measured",
            emptyList<Int>(),
            spellRolls.filter { it != 0 },
        )
        assertTrue(
            "and an action's does not, which is why the two types render differently",
            actionRolls.any { it != 0 },
        )
    }

    // -----------------------------------------------------------------------
    // DECISION 4 — the damage descendant walk
    // -----------------------------------------------------------------------

    /**
     * The **Greataxe shape**: a weapon whose `damage` hangs *directly* off the action, with no
     * branch in between.
     *
     * This is the case a literal reading of decision 4 — "branch(hit) → damage" as a two-step
     * path — renders as **no damage at all**, and it is not the rare shape: on the live capture
     * 9 of 17 damage properties are direct children, including three of the five weapon attacks.
     * See `ActionEngine.damageFor` for the measurement.
     */
    @Test
    fun `damage hanging directly off an attack is found - the Greataxe shape`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("axe", "action") {
                    put("name", "Greataxe")
                    put("actionType", "attack")
                    calc("attackRoll", "strength.modifier + proficiencyBonus", 5)
                },
                prop("axeDmg", "damage", parent = "axe") {
                    calcText("amount", "1d12 + strength.modifier", "1d12 + 3")
                    put("damageType", "slashing")
                },
            ),
        )

        val axe = board.actions.single()
        assertEquals(1, axe.damage.size)
        assertEquals("1d12 + 3", axe.damage.single().amount)
        assertEquals("slashing", axe.damage.single().damageType)
    }

    /** The other real shape: `damage` under a `branch(branchType: 'hit')`. Both must work. */
    @Test
    fun `damage under a hit branch is found`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("bow", "action") {
                    put("name", "Longbow")
                    put("actionType", "attack")
                },
                prop("hit", "branch", parent = "bow") { put("branchType", "hit") },
                prop("bowDmg", "damage", parent = "hit") {
                    calcText("amount", "1d8", "d8")
                    put("damageType", "piercing")
                },
            ),
        )

        assertEquals(listOf("d8"), board.actions.single().damage.map { it.amount })
    }

    /**
     * The two shapes on **one** row, plus the three branch kinds that must not contribute.
     *
     * `failedSave`, `successfulSave` and `if` are conditional riders, not on-hit damage. No
     * `damage` property in the live capture hangs under any of them, so excluding them costs
     * nothing today — this test is what keeps a future save-rider from silently being read as
     * the attack's damage. All three of `ActionEngine`'s named non-`hit` kinds get their own
     * rider here (not just two of the three), so this is a claim about every kind the capture
     * names, not a sample of it.
     */
    @Test
    fun `only hit branches contribute and direct children still do`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("blade", "action") {
                    put("name", "Flame Blade")
                    put("actionType", "attack")
                },
                prop("direct", "damage", parent = "blade") {
                    calcText("amount", "1d8", "d8"); put("damageType", "slashing")
                    put("order", 1)
                },
                prop("hit", "branch", parent = "blade") { put("branchType", "hit") },
                prop("hitDmg", "damage", parent = "hit") {
                    calcText("amount", "2d6", "2d6"); put("damageType", "fire")
                    put("order", 2)
                },
                prop("save", "branch", parent = "blade") { put("branchType", "failedSave") },
                prop("saveDmg", "damage", parent = "save") {
                    calcText("amount", "8d6", "8d6"); put("damageType", "necrotic")
                },
                prop("successSave", "branch", parent = "blade") { put("branchType", "successfulSave") },
                prop("successSaveDmg", "damage", parent = "successSave") {
                    calcText("amount", "4d6", "4d6"); put("damageType", "radiant")
                },
                prop("cond", "branch", parent = "blade") { put("branchType", "if") },
                prop("condDmg", "damage", parent = "cond") {
                    calcText("amount", "1d4", "d4"); put("damageType", "cold")
                },
            ),
        )

        assertEquals(
            "the direct child and the hit branch's child, in `order`; the failedSave, " +
                "successfulSave and `if` riders are excluded by decision 4's named branch kind",
            listOf("d8" to "slashing", "2d6" to "fire"),
            board.actions.single().damage.map { it.amount to it.damageType },
        )
    }

    // -----------------------------------------------------------------------
    // MAX_DAMAGE_DEPTH — the cycle guard (untested before now)
    // -----------------------------------------------------------------------

    /**
     * The documented depth cutoff, exercised directly: a hit-branch chain six levels deep, one
     * level past `MAX_DAMAGE_DEPTH` (4).
     *
     * `walk` is re-entered once per hit branch, so `damage` hanging off the FOURTH nested branch
     * is still collected — that call runs at `depth == 4`, and the guard only trips on
     * `depth > 4` — while `damage` one level deeper (the fifth branch) is silently dropped: the
     * call that would have processed it is `walk(_, 5)`, which returns before looking at a
     * single child. Both a `damage` row hanging directly off the fifth branch AND a sixth,
     * nested one level further still, prove the same thing: once the guard trips, NOTHING below
     * it is ever collected, however much of the sheet is still down there.
     */
    @Test
    fun `damage past MAX_DAMAGE_DEPTH is dropped, damage at the cutoff is kept`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("root", "action") { put("name", "Deep Chain"); put("actionType", "attack") },
                prop("h1", "branch", parent = "root") { put("branchType", "hit") },
                prop("h2", "branch", parent = "h1") { put("branchType", "hit") },
                prop("h3", "branch", parent = "h2") { put("branchType", "hit") },
                prop("h4", "branch", parent = "h3") { put("branchType", "hit") },
                prop("h5", "branch", parent = "h4") { put("branchType", "hit") },
                prop("h6", "branch", parent = "h5") { put("branchType", "hit") },
                // depth 4 — the walk call that finds this is `walk(h4, 4)`, still <= the guard.
                prop("atCutoff", "damage", parent = "h4") {
                    calcText("amount", "1d4", "d4"); put("damageType", "kept"); put("order", 1)
                },
                // depth 5 — found by `walk(h5, 5)`, which returns before this is ever seen.
                prop("oneOver", "damage", parent = "h5") {
                    calcText("amount", "2d4", "2d4"); put("damageType", "dropped-1")
                },
                // depth 6 — even further past the cutoff; still nothing, not a deeper drop.
                prop("twoOver", "damage", parent = "h6") {
                    calcText("amount", "3d4", "3d4"); put("damageType", "dropped-2")
                },
            ),
        )

        assertEquals(
            "only the damage found at exactly the guard's own depth survives",
            listOf("d4" to "kept"),
            board.actions.single().damage.map { it.amount to it.damageType },
        )
    }

    /**
     * The guard's OTHER job — named in its own KDoc — is a cyclic `parent` chain in malformed
     * data. A literal graph cycle cannot be *reached* by this walk: every property carries a
     * single `parent` reference, so the properties `walk` can ever visit from a real action are
     * exactly its descendants, and a node cannot be its own descendant without two different
     * `parent` values for the same id — which one `JsonObject` cannot hold, and `CreatureSheet`'s
     * id-keyed map would collapse to one entry even if the fixture tried. So this is not a test
     * that recursion is bounded (the depth test above is); it is a test that a mutually
     * self-referential PAIR sitting anywhere in the sheet — `cycleA` and `cycleB` below, each
     * naming the other as `parent` — is simply inert data a real action's walk never reaches,
     * rather than something that has to be filtered out or defended against at the call site.
     * `ActionEngine.build` must still return promptly and compute the REAL action's damage
     * correctly with this pair present, which is the honest form "does not hang" takes here.
     */
    @Test
    fun `a mutually self-referential parent pair elsewhere in the sheet does not hang the build`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("real", "action") {
                    put("name", "Real"); put("actionType", "attack")
                },
                prop("realDmg", "damage", parent = "real") {
                    calcText("amount", "1d6", "1d6"); put("damageType", "slashing")
                },
                prop("cycleA", "branch", parent = "cycleB") { put("branchType", "hit") },
                prop("cycleB", "branch", parent = "cycleA") { put("branchType", "hit") },
            ),
        )

        assertEquals(
            "the cyclic pair is unreachable and the real action's own damage is unaffected",
            listOf("1d6" to "slashing"),
            board.actions.single { it.name == "Real" }.damage.map { it.amount to it.damageType },
        )
        // Neither cycle member is a `spell` or `action`, so the pair contributes no row of its
        // own either — the guard's job here is purely "does not hang", not "is hidden".
        assertEquals(1, board.actions.size)
    }

    /** A `damage` row with no computed amount is dropped, not rendered as a bare damage type. */
    @Test
    fun `damage with no amount is dropped`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Nameless"); put("actionType", "action") },
                prop("d", "damage", parent = "a") { put("damageType", "force") },
            ),
        )
        assertEquals(emptyList<Any>(), board.actions.single().damage)
    }

    /**
     * **RECORDED FINDING**: `amount.value` is not uniformly resolved at rest.
     *
     * All four shapes below are real on the live capture. This test does not assert that the
     * rendering is *good* — two of these are visibly less than the whole answer — it asserts that
     * the engine passes the server's value through unchanged, which is decision 4's rule and the
     * only alternative to client dice math. `ActionEngine.damageFor` carries the argument, and
     * the wave report raises it for the architect.
     */
    @Test
    fun `the server's amount value is rendered verbatim including its partial forms`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Shapes"); put("actionType", "action") },
                prop("d1", "damage", parent = "a") {
                    calcText("amount", "2d4 + 2", "2d4 + 2"); put("damageType", "healing")
                    put("order", 1)
                },
                prop("d2", "damage", parent = "a") {
                    // The leading 1 is elided by the server. Harmless — same notation.
                    calcText("amount", "1d6", "d6"); put("damageType", "bludgeoning")
                    put("order", 2)
                },
                prop("d3", "damage", parent = "a") {
                    // The dice COUNT has not resolved: a scaling cantrip reads "d8", not "2d8".
                    calcText("amount", "(floor((level+1)/6)+1)d8", "d8"); put("damageType", "necrotic")
                    put("order", 3)
                },
                prop("d4", "damage", parent = "a") {
                    // An unresolved symbol reaches the row as its own name.
                    calcText("amount", "magicMissileDamage", "magicMissileDamage")
                    put("damageType", "force")
                    put("order", 4)
                },
            ),
        )

        assertEquals(
            listOf("2d4 + 2", "d6", "d8", "magicMissileDamage"),
            board.actions.single().damage.map { it.amount },
        )
    }

    // -----------------------------------------------------------------------
    // FR-36 — the riders beside `amount.value`
    // -----------------------------------------------------------------------

    /** `{name, operation, amount: {value}}` — one entry of a damage `amount.effects` array. */
    private fun effect(name: String, operation: String, value: kotlinx.serialization.json.JsonPrimitive) =
        buildJsonObject {
            put("_id", "e-$name")
            put("name", name)
            put("operation", operation)
            put("amount", buildJsonObject { put("value", value) })
        }

    /** A damage `amount` with its `effects`, as the capture delivers it. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.calcWithEffects(
        calculation: String,
        value: String,
        vararg effects: JsonObject,
    ) = put(
        "amount",
        buildJsonObject {
            put("calculation", calculation)
            put("value", value)
            put("effects", kotlinx.serialization.json.JsonArray(effects.toList()))
        },
    )

    /**
     * **The Rapier shape, exactly as captured.** `value` is `d8`; the effects the server attached
     * are *Finesse Modifiers* (`add`, `3`) and *Sneak Attack* (`add`, `"2d6"`). The numeric rider
     * folds into the headline; the dice rider is a chip; both are listed on the line.
     */
    @Test
    fun `a numeric add rider folds into the headline and a dice rider becomes a chip`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("rapier", "action") { put("name", "Rapier"); put("actionType", "attack") },
                prop("hit", "branch", parent = "rapier") { put("branchType", "hit") },
                prop("dmg", "damage", parent = "hit") {
                    calcWithEffects(
                        "1d8", "d8",
                        effect("Finesse Modifiers", "add", JsonPrimitive(3)),
                        effect("Sneak Attack", "add", JsonPrimitive("2d6")),
                    )
                    put("damageType", "piercing")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d8 + 3", line.amount)
        assertEquals("d8", line.base)
        assertEquals("piercing", line.damageType)
        assertEquals(
            listOf(
                DamageRider("Finesse Modifiers", "add", "3"),
                DamageRider("Sneak Attack", "add", "2d6"),
            ),
            line.riders,
        )
        assertEquals(listOf(DamageRider("Sneak Attack", "add", "2d6")), line.chips)
    }

    /**
     * Two numeric riders are concatenated in server order, never summed: a −1 Strength and a +1
     * enchantment read `d4 + 1 - 1`, which is what the server will roll and not a number this
     * engine worked out. A negative rider prints with a minus and its magnitude, not `+ -1`.
     */
    @Test
    fun `numeric riders concatenate in server order with their sign and are never summed`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("club", "action") { put("name", "Club"); put("actionType", "attack") },
                prop("dmg", "damage", parent = "club") {
                    calcWithEffects(
                        "1d4", "d4",
                        effect("Enchantment", "add", JsonPrimitive(1)),
                        effect("Strength Modifiers", "add", JsonPrimitive(-1)),
                    )
                    put("damageType", "bludgeoning")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d4 + 1 - 1", line.amount)
        assertEquals(emptyList<DamageRider>(), line.chips)
    }

    /**
     * An operation the app has not seen is a chip, stated in words and never combined with the
     * die — even when its amount is a plain integer. `add` is the only operation on any damage
     * effect in the capture (32 of 32); this is the guard for the first one that is not.
     */
    @Test
    fun `a non-add operation is never folded even with a numeric amount`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Odd"); put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects("1d6", "d6", effect("Doubled", "mul", JsonPrimitive(2)))
                    put("damageType", "fire")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d6", line.amount)
        assertEquals(listOf(DamageRider("Doubled", "mul", "2")), line.chips)
    }

    /**
     * No `effects` leaves the line exactly as the 2026-08-24 verbatim ruling had it —
     * `amount == base`, no riders — and **an `add` with an unresolved amount is dropped**, which
     * is the only drop this engine performs.
     *
     * The second half is the pin review finding 4 re-aimed. It used to read as *"an effect with
     * no amount is dropped"*, full stop, which made the bug look like the specification: an
     * `add` with nothing to add contributes nothing and its absence says nothing false, but a
     * `conditional` with no amount is a fact about the row, and dropping that one asserted the
     * damage is unconditional. See the test below for the surviving case.
     */
    @Test
    fun `no effects means the line is the verbatim value and an add with an unresolved amount is dropped`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Plain"); put("actionType", "attack") },
                prop("d1", "damage", parent = "a") {
                    calcText("amount", "2d6", "2d6"); put("damageType", "fire"); put("order", 1)
                },
                prop("d2", "damage", parent = "a") {
                    calcWithEffects("1d6", "d6", buildJsonObject { put("name", "Unresolved"); put("operation", "add") })
                    put("damageType", "cold"); put("order", 2)
                },
            ),
        )

        val lines = board.actions.single().damage
        assertEquals(listOf("2d6", "d6"), lines.map { it.amount })
        assertEquals(lines.map { it.base }, lines.map { it.amount })
        assertEquals(emptyList<DamageRider>(), lines.flatMap { it.riders })
    }

    /**
     * An amount-less **non-`add`** operation survives and chips as *name · operation* (review
     * finding 4).
     *
     * `{operation: "conditional", text: "undead"}` on a damage amount is the server saying this
     * damage applies only sometimes. Rendered as nothing, the row claims the opposite — and it
     * claims it silently, on the row a player reads mid-turn. The chip has no number in it
     * because the server sent none; it has the operation, in words, which is 16's *"an unknown
     * operation is stated in words and never combined"* applied to the case where there is
     * nothing to combine.
     */
    @Test
    fun `an amount-less non-add operation survives as a chip stated in words`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Conditional"); put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects(
                        "1d8", "d8",
                        buildJsonObject {
                            put("name", "Undead")
                            put("operation", "conditional")
                            put("text", "undead")
                        },
                    )
                    put("damageType", "radiant")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d8", line.amount)
        assertEquals(listOf(DamageRider("Undead", "conditional", "")), line.riders)
        assertEquals(line.riders, line.chips)
        assertEquals("Undead · conditional", line.chips.single().label)
    }

    /**
     * **A zero rider does not fold** (review finding 2, BUG-9, architect-approved amendment).
     *
     * DiceCloud's stock ability-modifier effects attach to every weapon row and resolve to the
     * raw modifier, so a character with a 10 in the governing ability publishes `add 0` on every
     * attack they own. Folded, that prints `d6 + 0` on the whole weapon list — the capture
     * missed it by a single ability point. The rider is still on the line, so the detail sheet
     * shows the sheet's own effect; it is simply not said in the headline, which is an omission
     * and not an arithmetic step.
     *
     * And it is **not a chip either** (architect ruling, 2026-09-02): the detail sheet's itemised
     * list is a zero rider's one surface, because a *"+0 Ability Modifiers"* chip beside every
     * weapon the character owns is the same untruth as `d6 + 0`, said at greater length.
     */
    @Test
    fun `a zero rider is not folded and stays on the line for the detail sheet`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Unarmed") ; put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects(
                        "1d6", "d6",
                        effect("Ability Modifiers", "add", JsonPrimitive(0)),
                    )
                    put("damageType", "bludgeoning")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d6", line.amount)
        assertEquals(line.base, line.amount)
        assertEquals(listOf(DamageRider("Ability Modifiers", "add", "0")), line.riders)
        assertEquals(
            "a zero rider is the one rider with no presence on the row at all",
            emptyList<DamageRider>(),
            line.chips,
        )
    }

    /**
     * **A fractional rider arrives whole and chips** (review finding 1, BUG-8).
     *
     * The first cut read the rider through `text()` → `number()`, an `Int` reader whose
     * `toDoubleOrNull()?.toInt()` truncates toward zero: `1.5` became `"1"` and then *folded*,
     * so the row printed `d8 + 1` for a bonus the sheet says is one and a half — a number the
     * server never published, which is the one thing 16 decision 4 forbids. Read as the
     * primitive's own content it stays `"1.5"`, fails `toIntOrNull()`, and chips with the true
     * value beside its name. `-2.5` likewise, sign intact.
     */
    @Test
    fun `a fractional rider keeps the server's own text and chips instead of folding`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Half") ; put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects(
                        "1d8", "d8",
                        effect("Half Bonus", "add", JsonPrimitive(1.5)),
                        effect("Half Penalty", "add", JsonPrimitive(-2.5)),
                    )
                    put("damageType", "piercing")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d8", line.amount)
        assertEquals(
            listOf(
                DamageRider("Half Bonus", "add", "1.5"),
                DamageRider("Half Penalty", "add", "-2.5"),
            ),
            line.riders,
        )
        assertEquals(listOf("+1.5 Half Bonus", "-2.5 Half Penalty"), line.chips.map { it.label })
    }

    /**
     * **An effect that states nothing is dropped** (pre-release review M4).
     *
     * The finding-4 rule — *drop only an `add` with no amount* — kept `{"_id": "e1"}` as a rider
     * whose every field is blank, and the row drew it: an empty bordered chip, and an empty
     * string inside the merged sentence that TalkBack reads as an unexplained pause. A
     * `conditional` with no amount is a fact worth stating; an effect naming no operation and
     * resolving to no amount is not a fact at all, and a name on its own is not one either — it
     * says a thing exists without saying what it does to this roll.
     */
    @Test
    fun `an effect with no operation and no amount is dropped, named or not`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Silent"); put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects(
                        "1d6", "d6",
                        buildJsonObject { put("_id", "e1") },
                        buildJsonObject { put("name", "Foo") },
                    )
                    put("damageType", "fire")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d6", line.amount)
        assertEquals(emptyList<DamageRider>(), line.riders)
        assertEquals(emptyList<DamageRider>(), line.chips)
    }

    /**
     * Padding around the server's number is padding, not meaning (review L1). `" 3"` is the
     * sheet saying three; a rider that chipped instead of folding because of a leading space
     * would be this app reporting a formatting artefact as a fact about the roll. Only the
     * whitespace goes — the characters between are the server's.
     */
    @Test
    fun `a padded amount is trimmed and still folds`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Padded"); put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects("1d8", "d8", effect("Spaced", "add", JsonPrimitive(" 3 ")))
                    put("damageType", "piercing")
                },
            ),
        )

        val line = board.actions.single().damage.single()
        assertEquals("d8 + 3", line.amount)
        assertEquals(listOf(DamageRider("Spaced", "add", "3")), line.riders)
    }

    /**
     * **`riderAmount`'s shapes, one row each** (review L3). The reader is four lines long and
     * every one of them is load-bearing, so each is named here rather than left to the two
     * happy-path tests above.
     *
     * The last row is a **deliberate behaviour change from 1.13.0** and the one worth arguing
     * about: `text()` preferred a wrapper's `text` over its `value`, so `{"amount": {"text":
     * "3"}}` used to resolve. `text` on a `_calculation` is the *un-substituted source*, and
     * folding a source expression into a damage headline prints a formula at a player. `value`
     * and only `value` — recorded in BUG-8's ledger cell.
     */
    @Test
    fun `rider amounts are read from the value primitive alone`() {
        fun riderFor(amount: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<DamageRider> = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Shapes"); put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects(
                        "1d8", "d8",
                        buildJsonObject {
                            put("name", "Shape")
                            put("operation", "add")
                            amount()
                        },
                    )
                    put("damageType", "force")
                },
            ),
        ).actions.single().damage.single().riders

        assertEquals(
            "a bare primitive is a primitive — no wrapper required",
            listOf(DamageRider("Shape", "add", "3")),
            riderFor { put("amount", JsonPrimitive(3)) },
        )
        assertEquals(
            "a null value did not resolve, and an add with nothing to add is dropped",
            emptyList<DamageRider>(),
            riderFor { put("amount", buildJsonObject { put("value", JsonNull) }) },
        )
        assertEquals(
            "an empty string did not resolve either",
            emptyList<DamageRider>(),
            riderFor { put("amount", buildJsonObject { put("value", "") }) },
        )
        assertEquals(
            "text is the un-substituted source, never the answer — 1.13.0 read it, this does not",
            emptyList<DamageRider>(),
            riderFor { put("amount", buildJsonObject { put("text", "3") }) },
        )
    }

    /**
     * The magnitude printed in the headline is the rider's **own text**, not an `Int` negated
     * (review finding 11): `-n` on `Int.MIN_VALUE` is `Int.MIN_VALUE` again, so the row read
     * `d8 - -2147483648`. Absurd as a modifier and unreachable from a real sheet — which is
     * exactly why it is pinned here rather than trusted to never happen.
     */
    @Test
    fun `the most negative integer prints its own digits rather than overflowing`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Absurd") ; put("actionType", "attack") },
                prop("dmg", "damage", parent = "a") {
                    calcWithEffects(
                        "1d8", "d8",
                        effect("Overflow", "add", JsonPrimitive(Int.MIN_VALUE)),
                    )
                    put("damageType", "force")
                },
            ),
        )

        assertEquals("d8 - 2147483648", board.actions.single().damage.single().amount)
    }

    // -----------------------------------------------------------------------
    // DECISION 5 — prepared/inactive honesty
    // -----------------------------------------------------------------------

    /**
     * The badge comes from the **fields**, and `inactive` is a separate, coexisting state.
     *
     * The third case is the probe's Animate Dead shape: a spell that IS prepared but sits under a
     * disabled ancestor, so `inactive` is true. Badging off `inactive` would label the one spell
     * the player deliberately prepared as unprepared.
     */
    @Test
    fun `the unprepared badge reads the fields and never inactive`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("s1", "spell") { put("name", "Unprepared"); put("level", 1) },
                prop("s2", "spell") {
                    put("name", "Prepared"); put("level", 1); put("prepared", true)
                },
                prop("s3", "spell") {
                    put("name", "Always"); put("level", 1); put("alwaysPrepared", true)
                },
                prop("s4", "spell") {
                    // Prepared AND inactive — the Animate Dead case.
                    put("name", "Disabled Ancestor"); put("level", 1)
                    put("prepared", true); put("inactive", true)
                },
                prop("s5", "spell") {
                    // Both states at once. Decision 5: they coexist and both show.
                    put("name", "Both"); put("level", 1); put("inactive", true)
                },
            ),
        )
        val byName = board.spells.associateBy { it.name }

        assertTrue(byName.getValue("Unprepared").showsUnpreparedBadge)
        assertFalse(byName.getValue("Prepared").showsUnpreparedBadge)
        assertFalse("alwaysPrepared needs no preparation", byName.getValue("Always").showsUnpreparedBadge)
        assertFalse(
            "THE CASE: inactive must not be read as unprepared",
            byName.getValue("Disabled Ancestor").showsUnpreparedBadge,
        )
        assertTrue(byName.getValue("Disabled Ancestor").inactive)

        val both = byName.getValue("Both")
        assertTrue("the two states coexist", both.showsUnpreparedBadge && both.inactive)
    }

    // -----------------------------------------------------------------------
    // DECISION 2 — discovery filtering
    // -----------------------------------------------------------------------

    /** `removed` is filtered; `inactive` is **not** — it is a state the row renders. */
    @Test
    fun `removed is dropped and inactive is kept`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("live", "spell") { put("name", "Live"); put("level", 0) },
                prop("gone", "spell") { put("name", "Gone"); put("level", 0); put("removed", true) },
                prop("off", "action") {
                    put("name", "Off"); put("actionType", "action"); put("inactive", true)
                },
            ),
        )

        assertEquals(listOf("Live"), board.spells.map { it.name })
        assertEquals(listOf("Off"), board.actions.map { it.name })
        assertTrue(board.actions.single().inactive)
    }

    /** There is no `type: 'attack'`; an attack is `actionType` on an action **or a spell**. */
    @Test
    fun `an attack is an actionType and can sit on either type`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") { put("name", "Weapon"); put("actionType", "attack") },
                prop("s", "spell") {
                    put("name", "Attack Spell"); put("level", 1); put("actionType", "attack")
                },
            ),
        )

        assertEquals(ActionType.ATTACK, board.actions.single().type)
        // The spell is a spell — its actionType does not move it into the actions list.
        assertEquals(listOf("Attack Spell"), board.spells.map { it.name })
    }

    /** An unknown `actionType` renders under Other rather than being dropped or mis-filed. */
    @Test
    fun `an unknown actionType falls to Other and is not invented as an action`() {
        val board = ActionEngine.build(
            sheetOf(prop("x", "action") { put("name", "Future"); put("actionType", "teleport") }),
        )
        val row = board.actions.single()
        assertNull("we do not guess at a type we have never seen", row.type)
        assertEquals(ActionGroup.OTHER, row.group)
    }

    // -----------------------------------------------------------------------
    // DECISION 3 — grouping and order
    // -----------------------------------------------------------------------

    @Test
    fun `actions are grouped in enum order then by the sheet's own order`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("r", "action") { put("name", "React"); put("actionType", "reaction"); put("order", 1) },
                prop("f", "action") { put("name", "Free"); put("actionType", "free"); put("order", 2) },
                prop("a2", "action") { put("name", "Act B"); put("actionType", "action"); put("order", 9) },
                prop("a1", "action") { put("name", "Act A"); put("actionType", "action"); put("order", 3) },
                prop("b", "action") { put("name", "Bonus"); put("actionType", "bonus"); put("order", 4) },
                prop("at", "action") { put("name", "Attack"); put("actionType", "attack"); put("order", 8) },
            ),
        )

        assertEquals(
            "Attacks / Actions / Bonus / Reactions / Other, then `order` inside each",
            listOf("Attack", "Act A", "Act B", "Bonus", "React", "Free"),
            board.actions.map { it.name },
        )
    }

    /**
     * Spells sort by `order`, then **stably** by level — so within a level the sheet's own
     * sequence survives.
     */
    @Test
    fun `spells sort by level with the sheet's order preserved inside each level`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("s1", "spell") { put("name", "L1 second"); put("level", 1); put("order", 20) },
                prop("s2", "spell") { put("name", "Cantrip"); put("level", 0); put("order", 30) },
                prop("s3", "spell") { put("name", "L1 first"); put("level", 1); put("order", 10) },
                prop("s4", "spell") { put("name", "L3"); put("level", 3); put("order", 5) },
            ),
        )

        assertEquals(
            listOf("Cantrip", "L1 first", "L1 second", "L3"),
            board.spells.map { it.name },
        )
    }

    /** A spell with no `level` is a cantrip — that is what DiceCloud means by omitting it. */
    @Test
    fun `a missing level reads as a cantrip`() {
        val board = ActionEngine.build(sheetOf(prop("s", "spell") { put("name", "C") }))
        assertEquals(0, board.spells.single().level)
    }

    // -----------------------------------------------------------------------
    // DECISION 4 — the spell list header, and the rest of the row content
    // -----------------------------------------------------------------------

    @Test
    fun `a spell list publishes its DC and ability modifier and neither is defaulted`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("l1", "spellList") {
                    put("name", "Cleric"); calc("dc", "8 + proficiencyBonus", 15); put("abilityMod", 4)
                },
                prop("l2", "spellList") { put("name", "No numbers") },
            ),
        )
        val byName = board.spellLists.associateBy { it.name }

        assertEquals(15, byName.getValue("Cleric").dc)
        assertEquals(4, byName.getValue("Cleric").abilityMod)
        assertNull("an absent DC is absent, never 0 or 10", byName.getValue("No numbers").dc)
        assertNull(byName.getValue("No numbers").abilityMod)
    }

    @Test
    fun `an action's uses and insufficientResources come straight from the server`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") {
                    put("name", "Limited"); put("actionType", "action")
                    put("usesLeft", 1); calc("uses", "3", 3)
                    put("insufficientResources", true)
                },
            ),
        )
        val row = board.actions.single()
        assertEquals(1, row.usesLeft)
        assertEquals(3, row.usesMax)
        assertTrue(row.insufficientResources)
    }

    // -----------------------------------------------------------------------
    // FR-28 — COST AND USES (docs/design/17-use-action.md decision 1)
    // -----------------------------------------------------------------------

    /**
     * The cost lines are joined against the **live properties**, not against the entry's own
     * `available` rollup.
     *
     * The fixture makes the difference visible on purpose: the entry claims `available: 9` and
     * the attribute the sheet actually carries reads `2`. Probe U5 says the rollup is the one on
     * the 4–10 s debounce, so the engine takes the property's own `value` and the assertion is
     * `2`. An implementation that read the convenient field sitting inside the entry would return
     * 9 and this test names why that is wrong.
     */
    @Test
    fun `an attribute cost is joined to the sheet's own value, not the entry's available`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("attr", "attribute") {
                    put("name", "Rage")
                    put("variableName", "rage")
                    put("value", 2)
                },
                prop("a", "action") {
                    put("name", "Rage"); put("actionType", "bonus")
                    put(
                        "resources",
                        buildJsonObject {
                            put(
                                "attributesConsumed",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("variableName", "rage")
                                            put("statName", "rage")
                                            calc("quantity", "1", 1)
                                            // The stale rollup. Deliberately wrong.
                                            put("available", 9)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )

        val line = board.actions.single().cost.attributes.single()
        assertEquals("the attribute's display name, never its variableName", "Rage", line.name)
        assertEquals(1, line.amount)
        assertEquals("the sheet's own value, not the entry's `available`", 2, line.available)
        assertTrue(line.satisfied)
    }

    /** An item cost joins by `itemId` against the item's own `quantity`, the same way. */
    @Test
    fun `an item cost is joined to the item's own quantity`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("arrows", "item") { put("name", "Arrows"); put("quantity", 2) },
                prop("a", "action") {
                    put("name", "Volley"); put("actionType", "action")
                    put(
                        "resources",
                        buildJsonObject {
                            put(
                                "itemsConsumed",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("itemId", "arrows")
                                            calc("quantity", "3", 3)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )

        val line = board.actions.single().cost.items.single()
        assertEquals("Arrows", line.name)
        assertEquals(3, line.amount)
        assertEquals(2, line.available)
        assertFalse("two arrows do not cover a cost of three", line.satisfied)
        assertFalse(board.actions.single().isUsable)
    }

    /**
     * A consumed item the sheet has not been told the identity of.
     *
     * The entry exists with a `tag` and no `itemId` until the player picks one, which is a real
     * shape and not a malformed document. There is no property to join to, so `available` is
     * `null` and the line is *satisfied* — see `CostLine.satisfied` for why unresolvable is not
     * refused. The name falls back to the tag rather than the line being dropped, because a tag
     * is at least something a player can recognise.
     */
    @Test
    fun `an unbound item cost names its tag and does not block the use`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") {
                    put("name", "Component"); put("actionType", "action")
                    put(
                        "resources",
                        buildJsonObject {
                            put(
                                "itemsConsumed",
                                buildJsonArray {
                                    add(buildJsonObject { put("tag", "arrow"); calc("quantity", "1", 1) })
                                },
                            )
                        },
                    )
                },
            ),
        )

        val line = board.actions.single().cost.items.single()
        assertEquals("arrow", line.name)
        assertNull(line.available)
        assertTrue(line.satisfied)
        assertTrue(board.actions.single().isUsable)
    }

    /** No `resources` block at all is [ActionCost.FREE] — the detail sheet's "Free". */
    @Test
    fun `an action with no resources block costs nothing`() {
        val board = ActionEngine.build(
            sheetOf(prop("a", "action") { put("name", "Dash"); put("actionType", "action") }),
        )
        assertTrue(board.actions.single().cost.isFree)
    }

    /**
     * THE USES TRAP, at the parse boundary: `usesUsed` is read and `usesLeft` is not.
     *
     * The fixture states them in contradiction — `usesLeft: 3` beside `uses: 3, usesUsed: 3` —
     * because that contradiction is what a real sheet publishes for the 4–10 s after a use.
     * `ActionEntry.usesLeft` keeps the rollup, because 16 decision 4 puts it on the list row and
     * a number that is right within ten seconds is fine for a row being scrolled past.
     * `ActionEntry.uses` carries the pair, and it is the pair the gate reads.
     */
    @Test
    fun `uses are derived from usesUsed while usesLeft keeps the server's rollup`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") {
                    put("name", "Second Wind"); put("actionType", "bonus")
                    calc("uses", "3", 3)
                    put("usesUsed", 3)
                    // The lagging rollup, still claiming three are left.
                    put("usesLeft", 3)
                },
            ),
        )

        val row = board.actions.single()
        assertEquals("the row still shows what the server published", 3, row.usesLeft)
        assertEquals(0, row.uses?.remaining)
        assertFalse("the gate reads the pair, not the rollup", row.isUsable)
        assertNull(row.useTarget)
    }

    /** A limited row nobody has used yet carries no `usesUsed`, which reads as zero. */
    @Test
    fun `an absent usesUsed reads as none used`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("a", "action") {
                    put("name", "Fresh"); put("actionType", "action"); calc("uses", "2", 2)
                },
            ),
        )
        assertEquals(2, board.actions.single().uses?.remaining)
        assertEquals(2, board.actions.single().uses?.max)
    }

    /** No `uses` field at all means unlimited, not a zero-use row that can never be pressed. */
    @Test
    fun `an action with no uses field is unlimited`() {
        val board = ActionEngine.build(
            sheetOf(prop("a", "action") { put("name", "Attack"); put("actionType", "attack") }),
        )
        assertNull(board.actions.single().uses)
        assertTrue(board.actions.single().isUsable)
    }

    /** Spells carry cost and uses on the same terms — the two row types must not drift. */
    @Test
    fun `a spell carries cost and uses too`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("attr", "attribute") { put("name", "Ki"); put("variableName", "ki"); put("value", 4) },
                prop("s", "spell") {
                    put("name", "Guidance"); put("level", 0); put("prepared", true)
                    calc("uses", "2", 2); put("usesUsed", 1)
                    put(
                        "resources",
                        buildJsonObject {
                            put(
                                "attributesConsumed",
                                buildJsonArray {
                                    add(buildJsonObject { put("variableName", "ki"); calc("quantity", "2", 2) })
                                },
                            )
                        },
                    )
                },
            ),
        )

        val spell = board.spells.single()
        assertEquals(1, spell.uses?.remaining)
        assertEquals("Ki", spell.cost.attributes.single().name)
        assertEquals(4, spell.cost.attributes.single().available)
        assertTrue(spell.isUsable)
    }

    /**
     * Decision 2 at the engine boundary: a spell the sheet has switched off yields no use target,
     * whatever its cost and charges say.
     *
     * Pinned here as well as in `UseTargetTest` because this is the path a real sheet takes, and
     * the two could only agree by accident if the engine ever started computing usability itself.
     */
    @Test
    fun `an unprepared spell built from a sheet offers no use`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("s", "spell") { put("name", "Bless"); put("level", 1); put("prepared", false) },
                prop("t", "spell") { put("name", "Shield"); put("level", 1); put("prepared", true); put("inactive", true) },
            ),
        )
        assertTrue("neither row offers a use", board.spells.all { it.useTarget == null })
    }

    /** Scalars and wrapper objects both read; a blank field is absent, not an empty string. */
    @Test
    fun `plain strings and text wrappers both resolve and blank reads as absent`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("s", "spell") {
                    put("name", "Mixed"); put("level", 2)
                    put("castingTime", "action")
                    put("range", "")
                    put("description", buildJsonObject { put("text", "rendered"); put("value", "raw") })
                },
            ),
        )
        val spell = board.spells.single()
        assertEquals("action", spell.castingTime)
        assertNull("a blank field is absent rather than an empty line on screen", spell.range)
        assertEquals("`text` wins over `value`: it is the rendered form", "rendered", spell.description)
    }

    // -----------------------------------------------------------------------
    // DECISION 1 — the discovery gate, and DECISION 9's honest empty
    // -----------------------------------------------------------------------

    /**
     * The one-tab-drop gate. A sheet with a spell **list** but no spells is empty — opening an
     * Actions tab onto a lone "DC 15" header would be a surface about an empty list.
     */
    @Test
    fun `the board is empty for a sheet with nothing to act with`() {
        assertTrue(ActionEngine.build(sheetOf()).isEmpty)
        assertTrue(ActionEngine.build(CreatureSheet.EMPTY).isEmpty)

        val listOnly = ActionEngine.build(
            sheetOf(prop("l", "spellList") { put("name", "Empty list"); calc("dc", "8", 13) }),
        )
        assertTrue("a spell list with no spells is still nothing to act with", listOnly.isEmpty)
        assertEquals(1, listOnly.spellLists.size)

        assertFalse(
            ActionEngine.build(sheetOf(prop("s", "spell") { put("name", "One") })).isEmpty,
        )
    }

    /** [ActionBoard.rowCount] drives decision 6's search threshold and excludes list headers. */
    @Test
    fun `rowCount counts spells and actions and not spell list headers`() {
        val board = ActionEngine.build(
            sheetOf(
                prop("s", "spell") { put("name", "S") },
                prop("a", "action") { put("name", "A"); put("actionType", "action") },
                prop("l", "spellList") { put("name", "L") },
            ),
        )
        assertEquals(2, board.rowCount)
    }

    // -----------------------------------------------------------------------
    // Against the live capture
    // -----------------------------------------------------------------------

    /**
     * The capture's real counts, derived. This is the test that would catch the engine silently
     * finding nothing — the class of failure a suite of synthetic cases cannot see.
     */
    @Test
    fun `the live capture yields the spells actions and lists it contains`() {
        val properties = Fixtures.sabrielSheet().livePropertyList
        val board = ActionEngine.build(Fixtures.sabrielSheet())

        fun liveCount(type: String) = properties.count { it.string("type") == type }

        assertEquals("every live spell becomes a row", liveCount("spell"), board.spells.size)
        assertEquals("every live action becomes a row", liveCount("action"), board.actions.size)
        assertEquals(liveCount("spellList"), board.spellLists.size)
        assertFalse(board.isEmpty)
    }

    /**
     * The damage walk against the capture: **both** shapes are present, and the engine finds a
     * damage line for every `damage` property that hangs off an action or a spell either way.
     *
     * This is the measurement `ActionEngine.damageFor`'s KDoc quotes, kept executable so that a
     * re-recorded capture with a different mix still proves the same thing.
     */
    @Test
    fun `both damage shapes occur in the capture and both are walked`() {
        val properties = Fixtures.sabrielSheet().livePropertyList
        val byId = properties.associateBy { it.string("_id") }
        fun parentOf(p: JsonObject) = byId[(p["parent"] as? JsonObject)?.string("id")]

        val damages = properties.filter { it.string("type") == "damage" }
        val direct = damages.count { parentOf(it)?.string("type") != "branch" }
        val viaHit = damages.count {
            val parent = parentOf(it)
            parent?.string("type") == "branch" && parent.string("branchType") == "hit"
        }

        assertTrue("the capture must carry the direct shape for this to mean anything", direct > 0)
        assertTrue("and the hit-branch shape", viaHit > 0)

        val board = ActionEngine.build(Fixtures.sabrielSheet())
        val rendered = board.actions.sumOf { it.damage.size } + board.spells.sumOf { it.damage.size }
        assertEquals(
            "every damage property reachable by the walk is rendered; a literal " +
                "'branch(hit) only' reading would have lost the $direct direct ones",
            direct + viaHit,
            rendered,
        )
    }

    /**
     * **FR-36 against the capture** (review finding 6): every effect-bearing damage row on the
     * real sheet, folded from the server's own signed numbers.
     *
     * The wave shipped with only synthetic rider tests, and the review's verdict on that was
     * blunt: a `headlineOf` regression changed no assertion against real data. This is the
     * missing half. It derives the expectation from the raw capture — each damage property's
     * `amount.value` and the `amount.value` of each of its `add` effects, read as the text the
     * server sent — and compares it with what the engine rendered, so the only way it passes is
     * if every number printed on a real row is a number the sheet published, in the order the
     * sheet published it.
     *
     * The count assertion is the other half of finding 6: an effect-less re-capture makes this
     * test fail loudly rather than pass vacuously.
     *
     * Derived, never declared — no property id, no feature name, no character name here.
     */
    @Test
    fun `every effect-bearing damage row in the capture folds the server's own signed values`() {
        val properties = Fixtures.sabrielSheet().livePropertyList

        fun effectsOf(property: JsonObject): List<JsonObject> =
            ((property["amount"] as? JsonObject)?.get("effects") as? kotlinx.serialization.json.JsonArray)
                ?.filterIsInstance<JsonObject>()
                .orEmpty()

        /** The effect's `amount.value` as the server's characters — the reader FR-36's fix uses. */
        fun amountTextOf(effect: JsonObject): String? =
            ((effect["amount"] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

        val effectBearing = properties.filter {
            it.string("type") == "damage" && effectsOf(it).isNotEmpty()
        }
        assertTrue(
            "the capture must carry damage rows with effects or this test proves nothing — " +
                "a re-capture without them is a signal, not a pass",
            effectBearing.isNotEmpty(),
        )

        // What the server said, assembled from the raw documents: the verbatim value, then one
        // signed term per non-zero whole-integer `add`, in array order. Everything else chips.
        //
        // `base` follows production's own rule for the field — a string `value` if there is one,
        // otherwise a wrapped number stringified (review L2). Every effect-bearing row in the
        // 2026-08-17 capture is a dice string, but two of its seventeen damage rows publish a
        // NUMERIC `amount.value`, so a re-capture where one of those grows an effect would
        // otherwise fail here with a diff about the base rather than about the fold — a red that
        // points at the wrong thing. (That the engine reads a numeric base through an `Int`
        // reader, and so truncates `2.5`, is BUG-10 and not this test's claim.)
        val expected = effectBearing.map { property ->
            val amount = property["amount"] as? JsonObject
            val base = amount?.string("value") ?: amount?.number("value")?.toString().orEmpty()
            val terms = effectsOf(property)
                .filter { it.string("operation") == "add" }
                .mapNotNull { amountTextOf(it) }
                .filter { text -> text.toIntOrNull().let { it != null && it != 0 } }
                .map { if (it.startsWith("-")) "- ${it.substring(1)}" else "+ $it" }
            (listOf(base) + terms).joinToString(" ")
        }.sorted()

        val board = ActionEngine.build(Fixtures.sabrielSheet())
        val rendered = (board.actions.flatMap { it.damage } + board.spells.flatMap { it.damage })
            .filter { it.riders.isNotEmpty() }

        assertEquals(
            "every effect-bearing damage property reaches a rendered line carrying its riders",
            effectBearing.size,
            rendered.size,
        )
        // `.sorted()` on both sides deliberately: this asserts the MULTISET of headlines, not
        // which row got which. Pairing a rendered line back to its source property would mean
        // re-deriving the walk the engine just performed, and the claim under test is about the
        // numbers, not about the ordering the other capture tests already pin.
        assertEquals(
            "each headline is the server's value followed by the server's own signed integers",
            expected,
            rendered.map { it.amount }.sorted(),
        )
        rendered.forEach { line ->
            assertTrue(
                "a headline never loses or rewrites the verbatim value it was built from",
                line.amount == line.base || line.amount.startsWith("${line.base} "),
            )
        }
    }

    /** The engine is pure: same sheet, same board. */
    @Test
    fun `the engine is deterministic`() {
        assertEquals(
            ActionEngine.build(Fixtures.sabrielSheet()),
            ActionEngine.build(Fixtures.sabrielSheet()),
        )
    }

    /** Mirror and snapshot produce the same board — the seam `CreatureSheet` exists for. */
    @Test
    fun `the mirror and the snapshot agree`() {
        val fromMirror = CreatureSheet.fromMirror(Fixtures.sabrielMirror(), Fixtures.SABRIEL_ID)
        assertNotNull(fromMirror.creature)
        assertEquals(
            ActionEngine.build(Fixtures.sabrielSheet()).actions.map { it.name },
            ActionEngine.build(fromMirror).actions.map { it.name },
        )
    }
}
