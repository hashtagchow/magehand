package com.hashtagchow.magehand.core.model

/**
 * A character that lives only on this device, per docs/design/09-local-characters.md.
 *
 * ### Why these are their own types and not a reshaped [CharacterSummary]
 *
 * 09 decision 1: *"a local character is its own thing, not a fake account."* A local
 * character has no `accountId`, no `creatureId`, no owner and no server — it has an id this
 * app minted. Bending [CharacterSummary] around that would mean nullable owner/account
 * fields on the type the DiceCloud selector renders, and every consumer would then have to
 * know which half it was holding. Two types that each mean one thing is cheaper.
 *
 * What these deliberately do **not** carry is anything the tracker already models:
 * countable rows are [TrackedResource] and the board is [TrackerBoard], exactly as for a
 * signed-in character (09 decision 5 — the tracker screen is reused, not forked).
 */
data class LocalCharacter(
    /** Minted by the app (a UUID). Stable for the character's life; never a server id. */
    val id: String,
    /** 09 decision 4: the only required field. */
    val name: String,
    /** `null` when the player did not give one — 1–20 when they did. */
    val level: Int?,
    val abilities: AbilityScores,
    /** Maximum hit points. The tracker's HP row total. */
    val maxHp: Int,
    /**
     * Current hit points — the HP row's remaining value.
     *
     * Not part of the creation form (09 decision 4 captures *max* HP): a new character
     * starts at full, and after that this is play state the tracker writes. It lives on the
     * character rather than in a tracker row because there is exactly one of it per
     * character and the form owns its ceiling — the same relationship `total` and `value`
     * have on every other row, just split across the two things that own them.
     */
    val currentHp: Int,
    val armorClass: Int,
    /**
     * The four coin counts (docs/design/10-inventory.md decision 10).
     *
     * Four integers on the character rather than four item rows, because locally there is no
     * tag machinery to discover them *with*: a server sheet expresses currency as items
     * carrying a `platinum`/`gold`/`silver`/`copper` tag, and reproducing that indirection
     * over a table this app owns outright would be modelling DiceCloud's limitation instead
     * of the money. Defaulted so every existing construction — the form, the repository, the
     * tests — keeps compiling and every pre-FR-8 character reads as broke, which they are.
     */
    val coins: CoinPurse = CoinPurse.EMPTY,
    /**
     * Death-save **marks**, 0..[DeathSaves.MAX] (FR-23,
     * docs/design/15-polish-batch.md decision 13).
     *
     * Two counts on the character rather than a [DeathSaves] for [coins]' reason, one step
     * further: a `DeathSaves` carries the two `creatureProperties` ids the write targets, and a
     * local character has no properties at all — `LocalTrackerBoard` mints synthetic ids for the
     * board and they are not a fact worth storing. What is stored is the only thing a player
     * could lose: the marks.
     *
     * Defaulted so every existing construction keeps compiling and every pre-1.8.0 character
     * reads as un-marked, which they are — there was nowhere to record a death save before this.
     */
    val deathSuccesses: Int = 0,
    val deathFailures: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A local character's money: four counts, no items, no tags.
 *
 * The local half of [Wallet]. They are different types on purpose and for the reason 09
 * decision 1 gives about [LocalCharacter] itself: a [WalletRow] carries a nullable
 * `propertyId` because a server sheet may simply *not have* a gold item, and that nullability
 * is the whole signal driving the insert path. Here the column always exists, so zero means
 * zero and there is nothing to create. Giving this type a nullable id to look like the other
 * one would be inventing an absence that cannot happen.
 */
data class CoinPurse(
    val platinum: Int = 0,
    val gold: Int = 0,
    val silver: Int = 0,
    val copper: Int = 0,
) {
    fun count(coin: CoinKind): Int = when (coin) {
        CoinKind.PLATINUM -> platinum
        CoinKind.GOLD -> gold
        CoinKind.SILVER -> silver
        CoinKind.COPPER -> copper
    }

    /** [count] set to [quantity], floored at zero — you cannot owe silver. */
    fun with(coin: CoinKind, quantity: Int): CoinPurse {
        val next = quantity.coerceAtLeast(0)
        return when (coin) {
            CoinKind.PLATINUM -> copy(platinum = next)
            CoinKind.GOLD -> copy(gold = next)
            CoinKind.SILVER -> copy(silver = next)
            CoinKind.COPPER -> copy(copper = next)
        }
    }

    /** The same "total in gp" line the server path prints, from the same per-coin values. */
    val totalGp: Double get() = CoinKind.entries.sumOf { count(it) * it.valueGp }

    companion object {
        val EMPTY: CoinPurse = CoinPurse()
    }
}

/** The six 5e ability scores. 09 decision 4: 3–30, default 10. */
data class AbilityScores(
    val strength: Int = DEFAULT,
    val dexterity: Int = DEFAULT,
    val constitution: Int = DEFAULT,
    val intelligence: Int = DEFAULT,
    val wisdom: Int = DEFAULT,
    val charisma: Int = DEFAULT,
) {
    /** The six scores in the order every 5e sheet prints them (STR → CHA). */
    val inSheetOrder: List<Pair<Ability, Int>>
        get() = listOf(
            Ability.STR to strength,
            Ability.DEX to dexterity,
            Ability.CON to constitution,
            Ability.INT to intelligence,
            Ability.WIS to wisdom,
            Ability.CHA to charisma,
        )

    fun score(ability: Ability): Int = when (ability) {
        Ability.STR -> strength
        Ability.DEX -> dexterity
        Ability.CON -> constitution
        Ability.INT -> intelligence
        Ability.WIS -> wisdom
        Ability.CHA -> charisma
    }

    /** The modifier for [ability] — see [abilityModifier]. */
    fun modifier(ability: Ability): Int = abilityModifier(score(ability))

    companion object {
        const val DEFAULT: Int = 10
        const val MIN: Int = 3
        const val MAX: Int = 30

        val DEFAULTS: AbilityScores = AbilityScores()
    }
}

/** The six abilities, in sheet order. The names are the ones the reference strip prints. */
enum class Ability {
    STR,
    DEX,
    CON,
    INT,
    WIS,
    CHA,
    ;

    /**
     * The ability spelled out — *"Strength"*, not *"STR"*.
     *
     * The abbreviation is right for the reference strip, where six cells share a row and the
     * label is read next to its own number. It is wrong for a *list*: a dropdown of rolls on
     * a DiceCloud character names them the way that sheet does (an ability attribute's `name`
     * field is the whole word), and a local character's six checks have to read identically
     * or 09 decision 5's "same screen" claim breaks at the one string a player compares.
     *
     * Written out rather than derived from [name] with a title-case helper: there is no rule
     * that turns `INT` into `Intelligence`, and a lookup that pretends otherwise would be a
     * table with extra steps.
     */
    val fullName: String
        get() = when (this) {
            STR -> "Strength"
            DEX -> "Dexterity"
            CON -> "Constitution"
            INT -> "Intelligence"
            WIS -> "Wisdom"
            CHA -> "Charisma"
        }
}

/**
 * The 5e ability modifier: `floor((score − 10) / 2)`.
 *
 * **`floorDiv`, not `/`.** Kotlin's integer division truncates *toward zero*, so a score of
 * 7 would give `(7 − 10) / 2 == −1` where 5e says −2, and every odd score below 10 would be
 * one point too generous. The floor is the rule, and this is the only place it is written.
 */
fun abilityModifier(score: Int): Int = (score - 10).floorDiv(2)

/**
 * What kind of row the player added, per 09 decision 4's three kinds.
 *
 * A narrower enum than [TrackerKind] on purpose: `HIT_POINTS` and `TEMP_HP` are discovered
 * on a DiceCloud sheet, not typed into a form — HP comes from [LocalCharacter.maxHp] and
 * there is no local temp-HP concept in 1.1. Making the table's column this type means an
 * illegal row cannot be stored, rather than being stored and filtered later.
 */
enum class LocalRowKind {
    /** Label + total, rendered as pips. "1st Level" × 4. */
    SLOT,

    /** Label + total + reset rule. Rage, ki, inspiration. */
    RESOURCE,

    /** Label + quantity. No ceiling — see [LocalTrackerRow.total]. */
    ITEM,

    /**
     * Label + optional description + optional uses + optional cost — the row FR-29 adds
     * (docs/design/18-table-pack.md decision 1).
     *
     * The one kind that is **not** a tracker row. It renders on the Actions surface, which is
     * where "a thing you do" belongs, and [trackerKind] returning `null` is what keeps it off
     * the tracker structurally rather than by a filter somebody has to remember.
     */
    ACTION,
    ;

    /**
     * How this row renders on the shared board, or `null` for a row that is **not** a tracker
     * row at all.
     *
     * Nullable as of FR-29, and the nullability is load-bearing rather than a widening: it is
     * what makes `LocalTrackerRow.toTrackedResource` return `null` for an [ACTION], so an action
     * row cannot reach [TrackerBoard] however a caller maps its list. The alternative — mapping
     * ACTION to [TrackerKind.RESOURCE] and filtering it out at each of the four places the board
     * splits rows by kind — is four rules to remember instead of one type that says no.
     */
    val trackerKind: TrackerKind?
        get() = when (this) {
            SLOT -> TrackerKind.SPELL_SLOT
            RESOURCE -> TrackerKind.RESOURCE
            ITEM -> TrackerKind.ITEM
            ACTION -> null
        }

    /** The value stored in `local_tracker_rows.kind`. Lowercase, per 09's own wording. */
    val storedValue: String
        get() = when (this) {
            SLOT -> "slot"
            RESOURCE -> "resource"
            ITEM -> "item"
            ACTION -> "action"
        }

    companion object {
        /** `null` for anything unrecognised — a row we cannot render is a row we drop. */
        fun fromStored(value: String?): LocalRowKind? =
            entries.firstOrNull { it.storedValue == value }
    }
}

/**
 * One user-added tracker row.
 *
 * [reset] reuses [ResetRule] rather than adding a local copy, `null` meaning "none" exactly
 * as it does for a discovered row — 09 decision 7's rest semantics are then literally
 * [RestKind.restores], one implementation for both sources.
 */
data class LocalTrackerRow(
    /** Minted by the app. Plays the part `creatureProperties._id` plays for a server row. */
    val id: String,
    val characterId: String,
    val kind: LocalRowKind,
    val label: String,
    /**
     * The ceiling for [LocalRowKind.SLOT] and [LocalRowKind.RESOURCE].
     *
     * For [LocalRowKind.ITEM] this tracks [current]: an item has no maximum (the server path
     * says the same thing — see [TrackedResource.total]), so the two are kept equal rather
     * than inventing a cap the form never asked for.
     *
     * For [LocalRowKind.ACTION] it is the **uses** total, and `0` means *unlimited* (18 decision
     * 1: uses are optional on an action). Zero rather than a nullable column, because the column
     * is `INTEGER NOT NULL` and shared with three kinds that all mean something by it — and
     * because zero is unreachable as a real answer here: an action limited to zero uses is an
     * action that cannot be used, which the form has no reason to let anybody type. `ActionUses`
     * is `null` for such a row, which is exactly what the server path publishes for an unlimited
     * action (`ActionEngine.usesFor` returns `null` when the row states no `uses`), so the two
     * sources produce the same domain value for the same fact.
     */
    val total: Int,
    val current: Int,
    val reset: ResetRule?,
    /** The player's order, and the *only* ordering mechanism for local rows (09 decision 8). */
    val sortIndex: Int,
    /**
     * Weight of one unit in pounds, or `null` when the player did not give one
     * (docs/design/10-inventory.md decision 10).
     *
     * Nullable for the same reason [InventoryItem.weightLb] is: a form field left blank is
     * not a claim that the thing is weightless. Meaningless on a slot or a resource, and the
     * form does not offer it there — an unused field on a shared row type is cheaper than a
     * second row type, and every consumer already switches on [kind].
     */
    val weightLb: Double? = null,
    /** Value of one unit in gold pieces, or `null` when the player did not give one. */
    val valueGp: Double? = null,
    /** The player's own note about the item, or `null`. */
    val description: String? = null,
    /**
     * Whether the item is worn or wielded (10 decision 10).
     *
     * **A plain flag.** The server path's equip *reparents* the property; there are no folders
     * here to move between, so this is exactly the boolean it looks like — which is also why
     * the local equip has a complete undo where the server's has an honest partial one.
     */
    val equipped: Boolean = false,
    /**
     * What the item **is** (docs/design/13-collapsible-sections-local-gear.md decisions 8–10).
     *
     * The local stand-in for a server sheet's tag taxonomy, and the field that finally gives
     * `LocalInventoryBoard` an input for 11 decision 1's rule — see [CatalogCategory] for why
     * it is a constant rather than a list of tags, and `LocalInventoryBoard` for the rule it
     * feeds.
     *
     * Meaningless on a slot or a resource, and the form does not offer it there — the same
     * arrangement [weightLb] already has, and for the same reason stated on it: one unused
     * field on a shared row type is cheaper than a second row type, and every consumer already
     * switches on [kind].
     *
     * Defaulted to gear so that every row that predates the v5 column — and every fixture built
     * before this field existed — reads as the "never collected" it actually is. Decision 11's
     * upgrade honesty is *not* carried by this default alone: an equipped row stays equippable
     * through the `equipped` disjunct, and an unequipped one through 11 decision 2's override.
     */
    val category: CatalogCategory = CatalogCategory.GEAR,
    /**
     * The row this action spends from, or `null` for a free one (FR-29, 18 decision 1).
     *
     * A reference to **another row of the same character** by id — *"1 × Rage"* — and never to an
     * [LocalRowKind.ACTION]: 18 decision 2 fences cost chaining out of v1 ("a cost row cannot
     * itself be an action"), and `LocalCharacterForm.validate` is where that is enforced, because
     * the fence is a statement about a *pair* of rows and this type only ever knows about one.
     *
     * No foreign key on the column, and that is the deliberate half. `ON DELETE CASCADE` from a
     * row to a row would take an action down with the resource it costs — deleting Rage would
     * silently delete Rage-the-action too, which is not what deleting a resource means. So a cost
     * naming a row that no longer exists is a *live* possibility, and it is handled where it is
     * read: `LocalActionBoard` renders such a line with no available count, which
     * `CostLine.satisfied` treats as permitted (an unresolvable cost is not an evaluated zero).
     * The same asymmetry the server path already has for a cost naming an item the sheet dropped.
     *
     * Meaningless on the other three kinds, and the editor draws the picker on action rows only —
     * the arrangement [weightLb] and [category] already have, for the reason stated on the first
     * of them.
     */
    val costRowId: String? = null,
    /** How many of [costRowId] one use spends. `null` exactly when [costRowId] is. */
    val costAmount: Int? = null,
)

/**
 * The row as the shared tracker renders it, or `null` for a row that is not a tracker row.
 *
 * `null` only for [LocalRowKind.ACTION] (FR-29): an action is a thing you *do*, and it renders on
 * the Actions surface through `LocalActionBoard`. Returning null rather than mapping it to some
 * tracker kind is what makes "an action never appears on the tracker" a property of the type
 * instead of a filter at each of the four places `LocalTrackerBoard` splits rows by kind.
 */
fun LocalTrackerRow.toTrackedResource(): TrackedResource? = TrackedResource(
    propertyId = id,
    kind = kind.trackerKind ?: return null,
    name = label,
    value = current,
    total = if (kind == LocalRowKind.ITEM) current else total,
    reset = reset,
    // Left null rather than parsed out of the label. The server supplies a real
    // `spellSlotLevel`; guessing one from "1st Level" would be inventing data, and the board
    // does not need it here — sortIndex is the player's own, explicit order.
    spellSlotLevel = null,
    sortOrder = sortIndex,
    // 09 decision 4: every local row was typed in by the player, so every one of them is
    // wanted on the tracker. `pinned` is what puts an item in TrackerBoard.pinnedItems,
    // which is the list the consumables section renders; the server path pins selectively
    // because it discovers hundreds of items nobody asked for.
    pinned = kind == LocalRowKind.ITEM,
)
