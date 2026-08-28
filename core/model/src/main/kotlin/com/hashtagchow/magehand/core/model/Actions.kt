package com.hashtagchow.magehand.core.model

/**
 * The domain types the **Actions surface** renders (docs/design/16-actions-and-feed.md, FR-26).
 *
 * Same posture as [TrackerBoard] and [InventoryBoard]: no JSON, no Room, no DiceCloud
 * vocabulary. The discovery rules that produce these live in `:core:data`'s `ActionEngine`.
 *
 * ### This surface is read-only, and the types are what make that true
 *
 * 16 decision 7: *"no new OpenCharacter intents, no WritePostureTest edits, no DDP methods.
 * Read-only surface end to end."* Nothing here carries a write target, an amount to spend or a
 * property path — the rows are things to *look at* at the table. Casting and prepared-toggling
 * are recorded out of scope for v1 with their write paths already probed, so the temptation
 * this file has to resist is a `prepared` setter, and it resists it by having nowhere to put one.
 *
 * ### Server rollups only
 *
 * 16 decision 4 inherits 10 decision 3's grand-total lesson: every number on these rows is one
 * the server already computed. Nothing here re-derives a bonus, sums a dice pool or evaluates a
 * calculation — see [SpellEntry]'s missing hit bonus for the sharpest case of that rule.
 */

/**
 * DiceCloud's `actionType`, whole.
 *
 * ### Why all seven, when the surface draws five groups
 *
 * The wire vocabulary and the display grouping are different questions and this enum answers
 * only the first. Folding `free`/`long`/`event` into "Other" at *parse* time would throw away
 * the fact that a row is a long action rather than a free one — a fact the detail sheet shows —
 * to save a mapping that [ActionGroup] expresses in five lines. [group] is where the fold
 * happens, once, so a reader asking "what does the server call this?" and a reader asking
 * "which header does it sit under?" get different, correct answers.
 *
 * ### There is no `attack` property type
 *
 * 16 decision 2, and it is the thing most likely to be re-guessed: an attack is not its own
 * `type`, it is `actionType: 'attack'` on an ordinary `action` **or on a `spell`**. The live
 * sheet carries spells whose `actionType` is `attack` and actions whose `actionType` is
 * `action`, so neither field predicts the other and both have to be read.
 *
 * @property wire the server's own token. Stable: it is what discovery matches on.
 */
enum class ActionType(val wire: String) {
    ACTION("action"),
    BONUS("bonus"),
    ATTACK("attack"),
    REACTION("reaction"),
    FREE("free"),
    LONG("long"),
    EVENT("event"),
    ;

    /** Which header this row sits under — see [ActionGroup]. */
    val group: ActionGroup
        get() = when (this) {
            ATTACK -> ActionGroup.ATTACKS
            ACTION -> ActionGroup.ACTIONS
            BONUS -> ActionGroup.BONUS
            REACTION -> ActionGroup.REACTIONS
            FREE, LONG, EVENT -> ActionGroup.OTHER
        }

    companion object {
        /**
         * The token back to a type, or `null` for one this build has never heard of.
         *
         * `null` rather than a fallback to [ACTION], deliberately: a row whose `actionType` is
         * a word we do not know is not an action-economy "action", and filing it under that
         * header would be an invented fact. [ActionEntry] carries a null type and
         * [ActionType.group]'s caller puts it under Other, which is where a thing we cannot
         * classify honestly belongs.
         */
        fun fromWire(wire: String?): ActionType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The five section headers the actions list draws, **in display order** (16 decision 3:
 * *"grouped Attacks / Actions / Bonus / Reactions / Other (enum order)"*).
 *
 * Declaration order is display order, exactly as [PaneSurface][ActionGroup]'s siblings do it —
 * so there is no second list anywhere that could disagree about the sequence. Attacks lead
 * because that is what the official UI leads with and what a player reaches for most at a
 * table; Other is last because it is the group defined by not being the others.
 */
enum class ActionGroup {
    ATTACKS,
    ACTIONS,
    BONUS,
    REACTIONS,
    OTHER,
}

/**
 * One `damage` rollup hanging off an action or a spell — *"2d6 slashing"*.
 *
 * ### The dice string is the server's, verbatim
 *
 * [amount] is `amount.value` as DiceCloud computed it. **No client dice math** (16 decision 4):
 * the underlying `amount.calculation` on a real sheet reads things like
 * `(floor((level+1)/6)+1)d8`, and an app that evaluated that expression itself would be a second
 * implementation of the sheet's rules engine — one that disagrees with the sheet the moment a
 * feature contributes anything the expression does not mention. The server has already done this
 * arithmetic and publishes the answer; this row prints it.
 *
 * @property amount the computed dice string, e.g. `"2d6"` or `"1d8+3"`.
 * @property damageType the server's own word — `"slashing"`, `"necrotic"`. Lower-case on the
 *   wire; the UI capitalises for display rather than this type storing a second spelling.
 */
data class DamageLine(
    val amount: String,
    val damageType: String,
)

/**
 * A spell, as the Actions surface draws it (16 decisions 3, 4 and 5).
 *
 * ### THE TRAP: there is no hit bonus on this type, and that is the point
 *
 * 16 decision 4: *"NEVER render a spell hit bonus from `attackRoll.value` (probe trap: it reads 0
 * at rest — the real bonus only resolves in a cast context)"*.
 *
 * A spell document **does** carry an `attackRoll`, and it looks exactly like a weapon's. The
 * difference is in the calculation behind it, and the live sheet shows both sides in one file:
 *
 * ```text
 *   spell  → attackRoll: { calculation: "#spellList.attackRollBonus", value: 0 }
 *   weapon → attackRoll: { calculation: "max(daggerWeapon,simpleMeleeWeapon)", value: 3 }
 * ```
 *
 * The spell's calculation is a **reference into the casting context**, which does not exist while
 * the sheet is merely being read — so the server publishes `value: 0` and will go on publishing
 * `0` forever. A row that rendered it would print *"+0 to hit"* beside a spell whose real bonus
 * is +7, in the app a player is holding *because* they are about to cast it. That is a
 * silent-wrong-answer of exactly the class 10 decision 3's grand-total lesson was written for:
 * plausible, stable, never flagged, and wrong at the only moment it is read.
 *
 * The defence is structural rather than a comment on a field. **This type has no hit-bonus
 * property at all**, so the mistake is not a rule someone has to remember not to break — it is a
 * value with nowhere to be put. [ActionEntry.attackRoll] exists on the weapon side, where the
 * number is real, and the two types being different shapes is what states the rule.
 *
 * What the player gets instead is the **spell list's** `dc` and `abilityMod`, which the server
 * computes honestly at rest — see [SpellListHeader]. That is the number the sheet actually
 * hands us, and it is the one the official UI shows in the same place.
 *
 * @property level `0` for a cantrip. Drives the "Cantrips" / "Level N" headers (decision 3).
 * @property castingTime / [range] the scalar summary line (decision 4). Plain strings as the
 *   server wrote them; absent reads as absent, never as an invented default.
 */
data class SpellEntry(
    val propertyId: String,
    val name: String,
    val level: Int,
    /** `concentration: true` — drives a chip, not a banner. The tracker owns the banner. */
    val concentration: Boolean = false,
    /** `ritual: true` — the second chip. */
    val ritual: Boolean = false,
    /**
     * The `prepared` FIELD, verbatim. See [showsUnpreparedBadge] for why it is not enough alone.
     */
    val prepared: Boolean = false,
    /** The `alwaysPrepared` FIELD — a domain/always-prepared spell needs no preparation. */
    val alwaysPrepared: Boolean = false,
    /**
     * `inactive: true`. Renders the row dimmed and is **not** a preparedness signal —
     * see [showsUnpreparedBadge].
     */
    val inactive: Boolean = false,
    val castingTime: String? = null,
    val range: String? = null,
    /** `description.text`, plain. No markdown rendering in v1 (decision 4, recorded as polish). */
    val description: String? = null,
    /** `summary.text`, plain. */
    val summary: String? = null,
    /** On-hit damage rollups — see [DamageLine] and `ActionEngine`'s descendant walk. */
    val damage: List<DamageLine> = emptyList(),
    val sortOrder: Int = 0,
) {
    /**
     * Decision 5's honesty rule: the unprepared badge derives from the **fields**, never from
     * `inactive`.
     *
     * ### Why `inactive` is the wrong signal, with the case that proves it
     *
     * `inactive` is true for more than one reason, and only one of them is "not prepared". The
     * probe's Animate Dead case is the counter-example: a spell sitting under a **disabled
     * ancestor** reads `inactive: true` while its own `prepared` field is perfectly true. Badge
     * off `inactive` and that spell is labelled "unprepared" — a claim the sheet never made,
     * about the one spell the player deliberately prepared.
     *
     * The inverse fails too: `alwaysPrepared` spells are exactly the ones that need no
     * preparation, so reading `prepared` alone would badge every domain and racial spell on the
     * sheet as unprepared forever.
     *
     * ### The two states coexist, and both show
     *
     * Decision 5 in as many words: *"the two states can coexist and both show"*. A spell can be
     * unprepared **and** under a disabled ancestor, so this flag and [inactive] are independent
     * and the row renders both — dimmed, with a badge. Collapsing them into one "unavailable"
     * state would tell the player the spell is off without telling them which of the two things
     * they would have to change to fix it.
     *
     * No badge is invented for [inactive] on its own (decision 5): a dimmed row with no badge is
     * the honest rendering of "the sheet has switched this off for a reason it did not name".
     */
    val showsUnpreparedBadge: Boolean get() = !prepared && !alwaysPrepared
}

/**
 * An action or an attack, as the Actions surface draws it (16 decisions 3 and 4).
 *
 * @property type `null` when the server sent an `actionType` this build does not know — see
 *   [ActionType.fromWire]. Such a row still renders, under Other.
 */
data class ActionEntry(
    val propertyId: String,
    val name: String,
    val type: ActionType? = null,
    /**
     * `attackRoll.value` — **real here**, unlike on a spell.
     *
     * A weapon's `attackRoll.calculation` resolves against the character's own attributes
     * (`max(daggerWeapon,simpleMeleeWeapon)` on the live sheet), so the server computes a true
     * number at rest and this row prints it as *"+3"*. [SpellEntry] deliberately has no
     * counterpart to this field; its KDoc carries the whole argument, and a spell with
     * `attackRoll.value` 0 beside a weapon with 6 rendering differently is a RULE rather than a
     * coincidence of the data — `ActionEngineTest` names the trap.
     *
     * `null` when the property carries no `attackRoll` at all, which is most non-attack rows.
     */
    val attackRoll: Int? = null,
    /**
     * `usesLeft` — how many are left, when the row is limited. `null` for an unlimited action.
     */
    val usesLeft: Int? = null,
    /** `uses.value` — the maximum, when the row is limited. */
    val usesMax: Int? = null,
    /**
     * `insufficientResources: true` — the server's own verdict that this cannot be used now.
     *
     * Decision 4: the row renders **dimmed with the flag stated**. Stated, not merely dimmed:
     * a greyed row with no words is indistinguishable from the [inactive] case above it, and
     * the two have different fixes (spend/rest versus switch something on).
     */
    val insufficientResources: Boolean = false,
    /** `inactive: true` — dimmed, no badge invented. Same rule as [SpellEntry.inactive]. */
    val inactive: Boolean = false,
    val description: String? = null,
    val summary: String? = null,
    val damage: List<DamageLine> = emptyList(),
    val sortOrder: Int = 0,
) {
    /** Which header this row sits under; an unknown [type] falls to Other. See [ActionGroup]. */
    val group: ActionGroup get() = type?.group ?: ActionGroup.OTHER
}

/**
 * A `spellList` property's header numbers — *"DC 15 · +7"* (16 decision 4).
 *
 * ### This is what the sheet hands us instead of a per-spell hit bonus
 *
 * The whole reason this type exists is [SpellEntry]'s trap. A spell's own attack bonus is not
 * knowable at rest, but the **list's** save DC and ability modifier are — the server computes
 * both honestly and publishes them on the `spellList` document. So the surface shows the numbers
 * that are true rather than the number that would have been in the more convenient place, which
 * is also exactly what DiceCloud's own UI puts at the top of a spell list.
 *
 * @property dc `dc.value`, the save DC.
 * @property abilityMod `abilityMod`, the spellcasting ability modifier.
 */
data class SpellListHeader(
    val propertyId: String,
    val name: String,
    val dc: Int? = null,
    val abilityMod: Int? = null,
    val sortOrder: Int = 0,
)

/**
 * Everything the Actions surface draws for one creature.
 *
 * ### Ordering is settled here, not in the UI
 *
 * `ActionEngine` emits both lists already sorted per 16 decision 3 — actions by group then
 * `order`, spells by `order` then **stable**-sorted by `level` — so the screen renders the list
 * it is given and no second sort exists anywhere to disagree with this one. That is
 * [TrackerBoard]'s rule (`orderRolls`, `orderDefenses`) applied to a third surface.
 *
 * ### The discovery gate reads [isEmpty]
 *
 * 16 decision 1: the tab/pane renders *"only when discovery finds ≥1 spell or action property"*
 * — the one-tab-drop rule the inventory tab already follows. That test is [isEmpty] and it
 * deliberately ignores [spellLists]: a sheet carrying a spell list with no spells in it is a
 * sheet with nothing to act with, and opening an Actions tab onto a lone "DC 15" header would
 * be a surface that exists to show a number about an empty list.
 */
data class ActionBoard(
    /** Sorted per decision 3: `order`, then stable by `level`. Cantrips first. */
    val spells: List<SpellEntry> = emptyList(),
    /** Sorted per decision 3: [ActionGroup] order, then `order` within a group. */
    val actions: List<ActionEntry> = emptyList(),
    /** One per live `spellList` property, in the sheet's own order. */
    val spellLists: List<SpellListHeader> = emptyList(),
) {
    /** See the class KDoc — this is the one-tab-drop gate. */
    val isEmpty: Boolean get() = spells.isEmpty() && actions.isEmpty()

    /**
     * How many rows the combined list holds — decision 6's search threshold reads this.
     *
     * Spells plus actions, and not the spell-list headers, for [isEmpty]'s reason: the filter
     * exists to find a row you can act with, and a header is not one.
     */
    val rowCount: Int get() = spells.size + actions.size

    companion object {
        val EMPTY = ActionBoard()
    }
}

/**
 * One entry in the **DiceCloud activity feed** (16 decisions 8–11, FR-25).
 *
 * ### What this is a feed OF, stated because the name would otherwise mislead
 *
 * Decision 9: this is a *DiceCloud activity* feed — rests, casts, checks and dice rolls made in
 * DiceCloud's own UI. **MageHand's own writes produce no entries.** Probe L3: `damage` and
 * `adjustQuantity` never log server-side, so a player who spends a slot in this app and then
 * opens this panel sees nothing new, and that is the server's behaviour rather than a bug in
 * the panel. The empty state says what the panel shows for exactly this reason — a feed that
 * silently omits the actions you just took reads as broken unless it tells you whose actions it
 * carries.
 *
 * Self-inserting our own writes via `creatureLogs.methods.insert` is **deferred and recorded**
 * (decision 9): it would double-journal for anyone using both clients, and spend the 5-per-5s
 * write lane on every tap.
 *
 * ### Attribution is creature-level only
 *
 * Decision 11. There is no actor field anywhere in the data — a `creatureLogs` document says
 * *what happened to which creature*, never *who pressed it*. So an entry is labelled with the
 * creature's name and nothing else, and the panel does not guess at a player. Rendering "*so-and-so*
 * cast Fireball" from a document that names no user would be an invention, and at a table where
 * two people share a sheet it would be a wrong one.
 *
 * @property creatureId the creature this entry belongs to. Load-bearing: the DM feed merges
 *   entries from several creatures and this is what keeps each one labelled — see
 *   `DmViewViewModel`'s cross-creature note.
 * @property creatureName the creature's display name, straight off the log document. `null`
 *   when the document carried none — a blank or absent `creatureName` field, not the empty
 *   string, matching [dateMillis]'s own "absent is `null`, never a placeholder value" rule. The
 *   UI resolves it to a labelled fallback string; the engine does not invent one, so a JVM test
 *   can pin the absence itself rather than a particular English sentence.
 * @property lines the `content[]` array flattened to `name` + `value` text pairs.
 * @property dateMillis the server's timestamp, for the newest-first merge and the relative
 *   time. `null` when the document carried none, which sorts such an entry last rather than
 *   letting it claim the top of the feed with an invented "now".
 */
data class FeedEntry(
    val logId: String,
    val creatureId: String,
    val creatureName: String?,
    val lines: List<FeedLine> = emptyList(),
    val dateMillis: Long? = null,
)

/**
 * One `content[]` element of a [FeedEntry] — a named line of the logged action.
 *
 * @property name the content block's own heading, e.g. the name of the spell that was cast.
 * @property value the block's text. Markdown is **stripped to plain text** in v1 (decision 11);
 *   a value long enough to dominate the panel is clipped with expand-on-tap by the UI rather
 *   than truncated here, so the full text survives to the detail view.
 */
data class FeedLine(
    val name: String? = null,
    val value: String? = null,
)
