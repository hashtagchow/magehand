package com.hashtagchow.magehand.core.model

/**
 * The domain types the **Actions surface** renders (docs/design/16-actions-and-feed.md, FR-26).
 *
 * Same posture as [TrackerBoard] and [InventoryBoard]: no JSON, no Room, no DiceCloud
 * vocabulary. The discovery rules that produce these live in `:core:data`'s `ActionEngine`.
 *
 * ### The surface WAS read-only; FR-28 gives it exactly one gesture
 *
 * 16 decision 7 was *"no new OpenCharacter intents, no WritePostureTest edits, no DDP methods.
 * Read-only surface end to end."* docs/design/17-use-action.md supersedes that for one gesture
 * and one only: **Use**. Nothing here gained a write target, an amount to spend or a property
 * path — the rows are still things to *look at*, and the single thing a player can now press is
 * expressed as [UseTarget], a type that **cannot be constructed for a row the app has decided is
 * not usable**. Prepared-toggling remains out of scope with its write path probed, so the
 * temptation this file still has to resist is a `prepared` setter, and it still resists it by
 * having nowhere to put one.
 *
 * ### Server rollups only — with one FR-28 exception, argued
 *
 * 16 decision 4 inherits 10 decision 3's grand-total lesson: every number on these rows is one
 * the server already computed. Nothing here re-derives a bonus, sums a dice pool or evaluates a
 * calculation — see [SpellEntry]'s missing hit bonus for the sharpest case of that rule.
 *
 * 17 decision 1 carves out the **usability** verdict, and it carves it out in the *opposite*
 * direction to everything above: `usesLeft` and `insufficientResources` are rollups the server
 * publishes and this app must NOT gate on, because probe U5 measured them lagging 4–10 s behind
 * a debounced recompute. The rule generalises rather than reverses — *use the field the server
 * writes synchronously, never the one it writes eventually* — and here the synchronous fields
 * are `usesUsed`, an attribute's `value` and an item's `quantity`. [ActionCost] and [ActionUses]
 * carry that reading; [UseTarget] carries its consequence.
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
 * One `damage` rollup hanging off an action or a spell — *"2d6 slashing"*, *"d8 + 3 piercing"*.
 *
 * ### The dice string is the server's, verbatim — and so is every modifier on it
 *
 * [base] is `amount.value` as DiceCloud computed it. **No client dice math** (16 decision 4):
 * the underlying `amount.calculation` on a real sheet reads things like
 * `(floor((level+1)/6)+1)d8`, and an app that evaluated that expression itself would be a second
 * implementation of the sheet's rules engine — one that disagrees with the sheet the moment a
 * feature contributes anything the expression does not mention. The server has already done this
 * arithmetic and publishes the answer; this row prints it.
 *
 * ### FR-36: the modifiers were always there, beside the value
 *
 * A damage property's `amount` carries a second server rollup next to `value`: an `effects`
 * array — every effect on the sheet whose target tags match this damage row, each with its own
 * `amount.value` already resolved against the character. On a Rogue's finesse Rapier that is
 * *Finesse Modifiers* (`add`, `3`) and *Sneak Attack* (`add`, `"2d6"`). The attack roll folds its
 * effects into `attackRoll.value`; the damage roll does **not** — `value` is the bare die and the
 * effects ride alongside — which is why the official card and this app both read `1d8` for a hit
 * that DiceCloud rolls as `1d8 + 3 + 2d6`.
 *
 * [amount] is the headline: [base] with every [DamageRider.foldsIntoHeadline] rider appended in
 * server order — `d8 + 3`, `d6 - 1`. That is concatenation of server-resolved numbers, not
 * arithmetic: two numeric riders print as `d4 + 1 - 1`, never as `d4`, because summing them would
 * be the first line of the rules engine this type exists not to be. Riders that do not fold
 * ([chips]) are dice strings and any operation other than `add`; the UI renders those as a
 * labelled chip — *"+2d6 Sneak Attack"* — because Sneak Attack is once per turn at the table
 * even though the sheet adds it to every roll, and a headline of `d8 + 3 + 2d6` would overstate
 * a normal hit. [riders] is the full list, for the detail sheet to itemise so the fold is
 * auditable.
 *
 * @property amount the headline dice string — [base] plus the folded riders, e.g. `"d8 + 3"`.
 * @property damageType the server's own word — `"slashing"`, `"necrotic"`. Lower-case on the
 *   wire; the UI capitalises for display rather than this type storing a second spelling.
 * @property base `amount.value` verbatim, e.g. `"d8"`. Equals [amount] when nothing folds.
 * @property riders every effect the server attached, in server order, folded or not.
 */
data class DamageLine(
    val amount: String,
    val damageType: String,
    val base: String = amount,
    val riders: List<DamageRider> = emptyList(),
) {
    /** The riders the headline does not carry — rendered beside it, each under its own name. */
    val chips: List<DamageRider> get() = riders.filterNot { it.foldsIntoHeadline }
}

/**
 * One server-resolved effect on a [DamageLine] — *Finesse Modifiers +3*, *Sneak Attack +2d6*.
 *
 * @property name the effect's own name on the sheet; may be blank on an unnamed effect.
 * @property operation DiceCloud's word — `"add"` on every effect in the live capture. Anything
 *   else is rendered as text and never combined with the die.
 * @property amount the effect's `amount.value` as text — `"3"`, `"-1"`, `"2d6"`.
 */
data class DamageRider(
    val name: String,
    val operation: String,
    val amount: String,
) {
    /**
     * A plain integer `add` is part of the headline (`d8 + 3`); a dice string or any other
     * operation is a chip. The integer test is the whole rule — it is what separates *"this is
     * what the roll gets"* from *"this is what the roll gets when the rider applies"*.
     */
    val foldsIntoHeadline: Boolean get() = operation == OPERATION_ADD && amount.toIntOrNull() != null

    companion object {
        const val OPERATION_ADD = "add"
    }
}

/**
 * One line of what a Use will spend — *"Rage: 1"*, *"Arrows: 2"* (17 decision 1's **Cost**).
 *
 * ### Why [available] is the sheet's own number and not the server's verdict
 *
 * DiceCloud publishes an `available` rollup beside each consumed resource, and a row carrying
 * `insufficientResources: true` looks like exactly the answer this type wants. Probe U5 says it is
 * not: both are recomputed on a **debounced** pass that trails the write by 4–10 s, so between
 * tapping Use and that pass landing the sheet says a resource is exhausted that the player can
 * see is not — or, worse, says one is available that a use half a second ago emptied.
 *
 * So [available] is read off the property the cost *names*: an attribute's own `value`, an item's
 * own `quantity`. Those are written synchronously by `doAction`, arrive on the fast path in
 * ~0.1–0.35 s, and are the same numbers the tracker and the inventory tab are already rendering
 * — which means the Use button and the pip row beside it can never disagree about whether there
 * is a charge left. `ActionEngine.costFor` is where the join happens.
 *
 * @property name what to call it on screen: the attribute's or item's own `name`, as the sheet
 *   spells it. Never the `variableName` — `deathSaveFails` is not a thing to show a player.
 * @property amount how many this use costs. The server's computed `quantity.value`.
 * @property available what the sheet currently holds, or `null` when the cost names something
 *   this sheet does not carry — see [satisfied] for why that is not a refusal.
 */
data class CostLine(
    val name: String,
    val amount: Int,
    val available: Int? = null,
) {
    /**
     * Whether this line, alone, permits a use.
     *
     * **An unresolvable cost is satisfied**, and that is a deliberate asymmetry. A cost naming a
     * variable or an item id the sheet does not carry is one this app cannot evaluate — not one it
     * has evaluated as zero. Treating it as a refusal would make a row permanently unusable in
     * this app while the official UI casts it happily, which is the *silent* half of a
     * silent-wrong-answer: the player gets no error, just a button that is never available and
     * never says why. Erring the other way costs at most one refused server call, which
     * `doCastSpell` reports verbatim and `doAction` swallows — see 17 decision 6.
     */
    val satisfied: Boolean get() = available == null || available >= amount
}

/**
 * Everything one Use will spend (17 decision 1's **Cost**: *"each `resources.attributesConsumed`
 * (name + amount) and `itemsConsumed` (name + count); 'Free' when none"*).
 *
 * Two lists rather than one, because the two halves are joined against **different** things — an
 * attribute by `variableName`, an item by `itemId` — and a single list would have to carry a
 * discriminator so that `ActionEngine` could tell them apart again. They render identically; that
 * is the UI's business, not this type's.
 */
data class ActionCost(
    /** `resources.attributesConsumed` — rage charges, ki points, a class resource. */
    val attributes: List<CostLine> = emptyList(),
    /** `resources.itemsConsumed` — arrows, a material component with a price. */
    val items: List<CostLine> = emptyList(),
) {
    /** Nothing is spent. The detail sheet says "Free"; the confirm dialog drops its cost lines. */
    val isFree: Boolean get() = attributes.isEmpty() && items.isEmpty()

    /** Every line has enough behind it. See [CostLine.satisfied] for the unresolvable case. */
    val satisfied: Boolean get() = attributes.all { it.satisfied } && items.all { it.satisfied }

    /** Both halves, in the order the sheet lists them. For the dialog and the detail sheet. */
    val lines: List<CostLine> get() = attributes + items

    companion object {
        val FREE = ActionCost()
    }
}

/**
 * A limited row's uses (17 decision 1's **Uses**: *"`uses.value − usesUsed`, shown as 'N of M uses
 * left'"*).
 *
 * ### THE TRAP: this is NOT `usesLeft`
 *
 * The server publishes `usesLeft`, it means precisely `remaining`, and reading it is wrong.
 * `usesLeft` is a **rollup** on the debounced recompute (probe U5, 4–10 s); `usesUsed` is a
 * counter `doAction` increments in the same write. So the two disagree for most of a round after
 * every use, and the direction of the disagreement is the dangerous one: `usesLeft` still reads
 * `1` on a feature the player has just spent, so a Use gated on it stays enabled and a second tap
 * spends a charge that is not there. Probe U3's burst produced three "Spent" log lines from a
 * one-use ability for exactly this reason.
 *
 * [ActionEntry.usesLeft] still exists and still renders on the *list row*, because 16 decision 4
 * put it there and a number that is right within ten seconds is fine for a row being scrolled
 * past. It must not reach a decision, and the split between these two types is what states that:
 * the rollup is a display field on the entry, and the gate is here.
 *
 * @property max `uses.value` — the computed maximum.
 * @property used `usesUsed` — the synchronous counter.
 */
data class ActionUses(
    val max: Int,
    val used: Int,
) {
    /** Never negative: a sheet whose `usesUsed` overran its `uses` is exhausted, not owed. */
    val remaining: Int get() = (max - used).coerceAtLeast(0)

    val isExhausted: Boolean get() = remaining <= 0
}

/**
 * A spell slot the upcast picker may offer (17 decision 3).
 *
 * @property propertyId the slot property's `_id` — what `doCastSpell` sends as `slotId`.
 * @property level the slot's own level, which is what a player is choosing between.
 * @property remaining how many of this level are left. Always `> 0` — see [spellSlotOptions].
 * @property total the row's maximum, so the picker can say "2 of 3 left" rather than a bare count.
 */
data class SpellSlotOption(
    val propertyId: String,
    val level: Int,
    val remaining: Int,
    val total: Int,
)

/**
 * The slots a spell of [spellLevel] may be cast with — 17 decision 3, whole and client-derived.
 *
 * > *"a slot picker lists slots with `spellSlotLevel ≥ spell.level` and remaining > 0
 * > (client-derived), passing the chosen `slotId`"*
 *
 * ### Two exclusions, and the second one is the trap
 *
 * **Too small** is the obvious one: a level-3 spell cannot be cast from a level-1 slot, and
 * offering one produces a server refusal for a choice the app could see was impossible.
 *
 * **Depleted** is the one that needs saying, because the tempting source for "how many are left"
 * is the same class of rollup [ActionUses] refuses. It is not: [TrackedResource.value] is the
 * tracker's own remaining count, built from the property's `total` and `damage` — the pair every
 * pip in this app is already drawn from — so the picker and the slot row cannot disagree, and a
 * slot the player just spent disappears from the picker on the same frame the pip empties.
 *
 * A slot with **no level** ([TrackedResource.spellSlotLevel] is `null`, which `TrackerEngine`
 * leaves when neither the field nor the name's leading ordinal resolves) is **dropped**, not
 * offered last. The engine's own ordering sorts such a row last because a list has to put it
 * somewhere; a picker has no such obligation, and `spellSlotLevel ≥ spell.level` is not a question
 * that can be answered about a slot whose level is unknown. Offering it would be guessing on the
 * player's behalf about which slot they are spending.
 *
 * Ordered by level ascending, so the **cheapest legal slot leads** — the one a player wanting no
 * upcast reaches for. That is the picker's default answer, and it is first because it is right
 * more often than any other, not because it is smallest.
 */
fun spellSlotOptions(slots: List<TrackedResource>, spellLevel: Int): List<SpellSlotOption> =
    slots
        .mapNotNull { slot ->
            val level = slot.spellSlotLevel ?: return@mapNotNull null
            if (level < spellLevel) return@mapNotNull null
            if (slot.value <= 0) return@mapNotNull null
            SpellSlotOption(
                propertyId = slot.propertyId,
                level = level,
                remaining = slot.value,
                total = slot.total,
            )
        }
        .sortedWith(compareBy({ it.level }, { it.propertyId }))

/**
 * What a Use will call, for a row the app has decided **is** usable (17 decisions 2, 3 and 6).
 *
 * ### This type is the prepared/active gate, structurally
 *
 * 17 decision 2: *"Use is ABSENT — not disabled — on: spells with `!prepared && !alwaysPrepared`,
 * and any row with `inactive: true`."* Probe U2 is why it has to be the app's gate at all — the
 * server casts an unprepared spell, casts a switched-off one, and **burns the slot doing it**.
 * There is no server-side refusal to lean on.
 *
 * A gate that is a rule someone has to remember is a gate that is eventually forgotten, so this is
 * a *shape* instead: [SpellEntry.useTarget] and [ActionEntry.useTarget] return `null` for a row
 * that fails the gate, and every Use path in the app — the detail sheet's button, the confirm
 * dialog, the view model's intent — is reached only through a non-null value of this type. There
 * is no seam that takes a bare property id, so there is no seam an unprepared spell can be pushed
 * through. That is [SpellEntry]'s own missing-hit-bonus argument applied to a write instead of to
 * a number: *a value with nowhere to be put*.
 *
 * ### Why it still carries the id
 *
 * `doAction`/`doCastSpell` take a property id and nothing else can identify a row, so the id is
 * on the type. The guarantee is not that the id is unforgeable — it is that a caller cannot obtain
 * one **from this file** for a row that failed the gate, and `:core:data` re-resolves it against
 * the live board before writing anyway (17 decision 6's *"validate ids against the live board
 * before calling"*; a bogus id is an opaque 500, probe U3). Two gates, the same way
 * `OpenCharacter.removeItem` has two.
 */
sealed interface UseTarget {

    /** The property this use names. */
    val propertyId: String

    /** The row's name, for the confirm dialog and the journal entry. */
    val name: String

    /** What it will spend, for the dialog's cost lines. */
    val cost: ActionCost

    /** Uses before the tap, or `null` for an unlimited row. The dialog prints "left after". */
    val uses: ActionUses?

    /** `creatureProperties.doAction` — an action, a bonus action, an attack, a reaction. */
    data class Action(
        override val propertyId: String,
        override val name: String,
        override val cost: ActionCost,
        override val uses: ActionUses?,
    ) : UseTarget

    /**
     * `creatureProperties.doCastSpell` — a spell.
     *
     * @property level `0` for a cantrip, which is what makes [needsSlot] answerable here rather
     *   than at three call sites.
     * @property ritual whether the sheet marks this spell as a ritual — the checkbox 17 decision 3
     *   asks to be *"honest about not consuming a slot"*. Offered only when this is true.
     */
    data class Spell(
        override val propertyId: String,
        override val name: String,
        override val cost: ActionCost,
        override val uses: ActionUses?,
        val level: Int,
        val ritual: Boolean,
    ) : UseTarget {
        /**
         * Cantrips skip the picker (17 decision 3). Not because a cantrip has no slot it *could*
         * take — it is that spending one on a cantrip is never what a player meant, and a picker
         * whose only honest option is "none" is a dialog that exists to be dismissed.
         */
        val needsSlot: Boolean get() = level > 0
    }
}

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
    /** 17 decision 1's **Cost**. [ActionCost.FREE] when the spell consumes nothing. */
    val cost: ActionCost = ActionCost.FREE,
    /** 17 decision 1's **Uses**, or `null` when the spell is not use-limited. */
    val uses: ActionUses? = null,
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

    /**
     * 17 decision 1's **Usability**, for a spell — client-derived, every clause.
     *
     * > *"usable iff (uses remain OR uses absent) AND every consumed attribute's live `value` ≥
     * > amount AND every consumed item's `quantity` ≥ count AND the row is prepared/active per
     * > decision 2"*
     *
     * The clause that is **not** here is the point: nothing on this line reads a server verdict.
     * A spell the sheet has not yet recomputed is usable if the app's own arithmetic says the
     * charges are there, and a spell whose stale rollup says "insufficient" is *still* usable if
     * they are. See [ActionUses] for the measurement behind that, and [ActionEntry.isUsable],
     * where the same rule has an extra rollup to ignore.
     */
    val isUsable: Boolean
        get() = !inactive &&
            !showsUnpreparedBadge &&
            (uses?.isExhausted != true) &&
            cost.satisfied

    /**
     * The Use call for this spell, or `null` when there is not one — see [UseTarget].
     *
     * `null` is what decision 2's *"ABSENT — not disabled"* means in code. A caller holding a
     * `SpellEntry` cannot get a use out of an unprepared or switched-off one, whatever it does
     * with the fields.
     */
    val useTarget: UseTarget.Spell?
        get() = if (!isUsable) {
            null
        } else {
            UseTarget.Spell(
                propertyId = propertyId,
                name = name,
                cost = cost,
                uses = uses,
                level = level,
                ritual = ritual,
            )
        }
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
     *
     * **A rollup, and a display field only.** It lags the write by 4–10 s (probe U5), so it may
     * not reach a decision — [uses] is what the Use gate reads. See [ActionUses] for the whole
     * argument and for the double-spend it prevents.
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
     *
     * **It does not gate the Use button.** 17 decision 1: this is a rollup on the same 4–10 s
     * debounce as [usesLeft], so it says "insufficient" through the whole window after a rest
     * restores the resource, and says nothing for the whole window after a use empties it. It is
     * a slow confirmation of the app's own arithmetic, which is [isUsable]. The two disagreeing
     * on screen — a dimmed row with a live Use button, or an undimmed one without — is the
     * *correct* rendering during that window, not a bug to reconcile.
     */
    val insufficientResources: Boolean = false,
    /** `inactive: true` — dimmed, no badge invented. Same rule as [SpellEntry.inactive]. */
    val inactive: Boolean = false,
    val description: String? = null,
    val summary: String? = null,
    val damage: List<DamageLine> = emptyList(),
    /** 17 decision 1's **Cost**. [ActionCost.FREE] when the action consumes nothing. */
    val cost: ActionCost = ActionCost.FREE,
    /** 17 decision 1's **Uses**, or `null` when the action is not use-limited. */
    val uses: ActionUses? = null,
    val sortOrder: Int = 0,
) {
    /** Which header this row sits under; an unknown [type] falls to Other. See [ActionGroup]. */
    val group: ActionGroup get() = type?.group ?: ActionGroup.OTHER

    /**
     * 17 decision 1's **Usability**, for an action.
     *
     * [SpellEntry.isUsable] minus the prepared clause — an action has no preparation — and with
     * one more field deliberately unread: [insufficientResources]. That field is the server
     * saying the exact thing this property computes, and it is *still* not consulted, because it
     * says it late. The two rules a reader should take from this line and its twin:
     *
     * - a stale `insufficientResources: true` does **not** block a use the app can see is funded;
     * - a stale `insufficientResources: false` does **not** permit one the app can see is not.
     *
     * Both directions matter and only the second looks dangerous, which is why the first is the
     * one that gets deleted by somebody tidying up. `ActionEngineTest` pins both.
     */
    val isUsable: Boolean
        get() = !inactive && (uses?.isExhausted != true) && cost.satisfied

    /** The Use call for this action, or `null` when there is not one — see [UseTarget]. */
    val useTarget: UseTarget.Action?
        get() = if (!isUsable) {
            null
        } else {
            UseTarget.Action(propertyId = propertyId, name = name, cost = cost, uses = uses)
        }
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
 * Decision 9: this is a *DiceCloud activity* feed — rests, casts, checks and dice rolls the
 * **server** logged, whichever client asked for them. 16 decision 9 stated the sharper version,
 * *"MageHand's own writes produce no entries"*, and probe L3 is why: `damage` and
 * `adjustQuantity` never log server-side, so a player who spends a slot in this app and then
 * opens this panel sees nothing new. That is the server's behaviour rather than a bug in the
 * panel, and it is why the empty state names DiceCloud — a feed that silently omits the actions
 * you just took reads as broken unless it says whose actions it carries.
 *
 * ### FR-28 corrects half of that, and corrects it in the right direction
 *
 * 17 decision 8: *"the FR-25 feed now shows MageHand's own uses (the server logs
 * doAction/doCastSpell — design 16 decision 9's deferral resolves itself the right way)"*. A Use
 * goes through the server's own machinery, so the server writes the log entry itself and this
 * panel carries it with **no code in this app at all** — which is exactly the outcome decision 9
 * deferred `creatureLogs.methods.insert` in the hope of: no double-journalling, no write lane
 * spent per tap, and one entry per action rather than one per client that saw it.
 *
 * So the rule is no longer "MageHand's writes never appear"; it is *"what the server logged
 * appears"*, and the app's tracker edits are simply not among the things it logs. The empty
 * state's copy was corrected in the same wave — see `dm_feed_empty_hint`, which had become a
 * claim the app disproves the first time anybody presses Use.
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
