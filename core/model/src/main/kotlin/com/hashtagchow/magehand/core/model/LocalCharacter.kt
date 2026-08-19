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
    ;

    /** How this row renders on the shared board. */
    val trackerKind: TrackerKind
        get() = when (this) {
            SLOT -> TrackerKind.SPELL_SLOT
            RESOURCE -> TrackerKind.RESOURCE
            ITEM -> TrackerKind.ITEM
        }

    /** The value stored in `local_tracker_rows.kind`. Lowercase, per 09's own wording. */
    val storedValue: String
        get() = when (this) {
            SLOT -> "slot"
            RESOURCE -> "resource"
            ITEM -> "item"
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
)

/** The row as the shared tracker renders it. */
fun LocalTrackerRow.toTrackedResource(): TrackedResource = TrackedResource(
    propertyId = id,
    kind = kind.trackerKind,
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
