package com.hashtagchow.magehand.ui.testing

import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.Wallet
import com.hashtagchow.magehand.core.model.WalletRow
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryUiState
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.toInventoryUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConditionChipState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionStatus
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionTone
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConsumableState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.DefenseRowState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PipRowState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.RollDisplayState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.RollOptionState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.RollPickerState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerUiState

/**
 * The board every FR-34 render test and golden draws — **Sabriel**, the live capture under
 * `docs/fixtures/` (design 08 §"Test data" names the file; this one does not, because the
 * capture's filename is on the store-safety gate's forbidden list).
 *
 * ### Why the rows are written out rather than parsed from the capture
 *
 * The capture is a 1.1 MB DiceCloud document dump, and turning it into a board is `TrackerEngine`'s
 * job — a `:core:data` class with its own exhaustive tests against that same file. A `:app` test
 * that parsed it would be running the engine in order to get to the seam it actually wants
 * (`UiState → Composable`), and a golden would then re-record every time the engine's discovery
 * changed for reasons that had nothing to do with how the tracker draws.
 *
 * So the values are the capture's, transcribed: HP 17/17, 1st Level 3/4, 2nd Level 1/2, Magic
 * Initiate 1/1, Heroic Inspiration 0/1, d6 Hit Dice 3/3, and the single necrotic/radiant
 * resistance row that the capture's two half-multipliers merge into. Every one of those numbers
 * is verifiable against the capture, which is what makes this a fixture rather than an invention.
 *
 * The character's **first name only**. `tools/public-gate.sh` gates the party's surnames and two
 * of the capture's feature names by name — "first names alone are not identifying and are not
 * gated" — and this corpus is committed, so the rule binds the fixture as much as the source.
 *
 * ### Store safety (design 19 decision 9)
 *
 * Nothing here names a server, a token or a DM user id. The goldens are committed repo files, and
 * the only host any of them may show is `dicecloud.com` — which is the app's own
 * `CredentialsViewModel.DEFAULT_SERVER_URL` and appears only on the login golden.
 */
object Sabriel {

    const val CREATURE_ID: String = "FakeCreature23456"

    /**
     * The name the app bar shows — the character's **first name only**, per the class KDoc's
     * store-safety rule.
     *
     * `HomeAppBar_360_100.png` renders it **in full**, so it is a committed pixel and the rule
     * binds it exactly as it binds the source. `HomeAppBar_320_150.png` is the other picture and
     * shows almost none of it: at 320 dp × 150 % the title has ~21 dp even after FR-43's compact
     * rule, and before that rule it had none at all (BUG-17). Two goldens, one name, and only
     * one of them is where you can read it.
     */
    const val NAME: String = "Sabriel"

    /** `1st Level`, the capture's 3-of-4 caster row. The click→intent exemplar spends from it. */
    val firstLevel = PipRowState(
        propertyId = "slot-1st",
        label = "1st Level",
        reset = ResetRule.LONG_REST,
        value = 3,
        total = 4,
        pinned = false,
        kind = TrackerKind.SPELL_SLOT,
    )

    val secondLevel = PipRowState(
        propertyId = "slot-2nd",
        label = "2nd Level",
        reset = ResetRule.LONG_REST,
        value = 1,
        total = 2,
        pinned = false,
        kind = TrackerKind.SPELL_SLOT,
    )

    /** A one-pip long-rest row, so the rest dialog's list has something already full in it. */
    val magicInitiate = PipRowState(
        propertyId = "slot-magic-initiate",
        label = "Magic Initiate",
        reset = ResetRule.LONG_REST,
        value = 1,
        total = 1,
        pinned = false,
        kind = TrackerKind.SPELL_SLOT,
    )

    /**
     * The capture's `Heroic Inspiration`, and it carries **no** reset rule — which is the whole
     * reason FR-20 exists (the 2026-08-21 triage was a player who could not predict what a rest
     * would do to this row). It is therefore the row that must NOT appear in either rest dialog.
     */
    val heroicInspiration = PipRowState(
        propertyId = "res-heroic-inspiration",
        label = "Heroic Inspiration",
        reset = null,
        value = 0,
        total = 1,
        pinned = false,
        kind = TrackerKind.RESOURCE,
    )

    /** A short-rest row, absent from the capture, so the short-rest dialog has a row to list. */
    val secondWind = PipRowState(
        propertyId = "res-second-wind",
        label = "Second Wind",
        reset = ResetRule.SHORT_REST,
        value = 0,
        total = 2,
        pinned = false,
        kind = TrackerKind.RESOURCE,
    )

    /** FR-30's row. `dieSize` is what makes it print *"Hit Dice d6"* rather than its raw name. */
    val hitDice = PipRowState(
        propertyId = "hitdice-d6",
        label = "d6 Hit Dice",
        reset = null,
        value = 3,
        total = 3,
        pinned = false,
        kind = TrackerKind.RESOURCE,
        dieSize = "d6",
    )

    /**
     * The full tracker board, live and writable — what a player sees mid-session.
     *
     * @param canWrite false renders the offline posture: every control dimmed and the read-only
     *   note at the foot of the list.
     */
    fun tracker(
        canWrite: Boolean = true,
        concentratingOn: String? = "Bless",
        status: ConnectionStatus = ConnectionStatus(tone = ConnectionTone.LIVE, syncedAt = "14:52"),
    ) = TrackerUiState(
        creatureId = CREATURE_ID,
        status = status,
        concentratingOn = concentratingOn,
        concentrationToggleId = "toggle-bless",
        hp = HpState(propertyId = "hp", current = 17, max = 17, tempHp = 0),
        // The capture's two half-multipliers, already merged by kind and alphabetised the way
        // `toDefenseRows` merges them — the two source features are named on the store-safety
        // gate's list, and the merge is exactly why the row does not need them.
        defenses = listOf(
            DefenseRowState(kind = DefenseKind.RESISTANT, types = listOf("Necrotic", "Radiant")),
        ),
        rolls = RollPickerState(
            options = listOf(
                RollOptionState("roll-perception", "Perception"),
                RollOptionState("roll-arcana", "Arcana"),
            ),
            selected = RollDisplayState(name = "Perception", modifier = "+7", advantageLabel = null),
        ),
        slots = listOf(firstLevel, secondLevel, magicInitiate),
        resources = listOf(heroicInspiration, secondWind),
        hitDice = listOf(hitDice),
        consumables = listOf(
            ConsumableState(propertyId = "item-potion", name = "Potion of Healing", quantity = 2),
        ),
        conditions = listOf(
            ConditionChipState(propertyId = "toggle-darkvision", name = "Darkvision Switch", enabled = true, canFlip = true),
        ),
        // The drawer the collapse/expand exemplar opens, and the "N inactive" golden shows shut.
        inactiveConditions = listOf(
            ConditionChipState(propertyId = "toggle-incap", name = "Incapacitated", enabled = false, canFlip = true),
            ConditionChipState(propertyId = "toggle-level3", name = "Level 3?", enabled = false, canFlip = true),
        ),
        canWrite = canWrite,
        canUndo = canWrite,
    )

    /**
     * A small inventory board, built through the real [toInventoryUiState] rather than by filling
     * an `InventoryUiState` in by hand.
     *
     * The mapping is a pure function with its own exhaustive test (`InventoryUiStateTest`), so
     * going through it costs nothing and buys the guarantee that matters for a golden: the
     * sections, the weights and the capacity line are the ones the app computes, not the ones a
     * fixture author believed the app computes.
     *
     * `Ration Pack` is deliberately long-ish: it is the narrow-width golden's subject, and the
     * 1.9.1 wrap bug ("Ite/m") only shows on a name that runs out of row.
     */
    fun inventory(
        canWrite: Boolean = true,
        layout: List<InventoryLayoutEntry> = emptyList(),
        /**
         * FR-35's ordering. Defaulted to the sheet's own order, so every render test and golden
         * written before FR-35 draws precisely what it drew before — which is the feature's
         * central claim and the reason none of them needed editing.
         *
         * The Gear section is what makes this fixture useful for it: `Torch` (3 × 1 lb) sits
         * above `Component Pouch` (1 × 2 lb) in sheet order, and every one of the three criteria
         * swaps them — alphabetically, by stack weight, and by value. So a sort that failed to
         * reach the rendered rows is visible in one pair.
         */
        sort: InventorySort = InventorySort.DEFAULT,
    ): InventoryUiState = toInventoryUiState(
        creatureId = CREATURE_ID,
        board = InventoryBoard(
            wallet = Wallet(
                listOf(
                    WalletRow(CoinKind.PLATINUM, 0, "coin-pp"),
                    WalletRow(CoinKind.GOLD, 109, "coin-gp"),
                    WalletRow(CoinKind.SILVER, 4, "coin-sp"),
                    WalletRow(CoinKind.COPPER, 12, "coin-cp"),
                ),
            ),
            equipped = listOf(
                item("eq-quarterstaff", "Quarterstaff", weightLb = 4.0, valueGp = 0.2, equipped = true, group = EquipGroup.WEAPON),
            ),
            containers = listOf(
                InventoryContainer(
                    propertyId = "cont-backpack",
                    name = "Backpack",
                    quantity = 1,
                    weightLb = 5.0,
                    valueGp = 2.0,
                    rollupWeightLb = 12.5,
                    rollupValueGp = 8.0,
                    contents = listOf(item("in-rations", "Ration Pack (1 day)", quantity = 5, weightLb = 2.0)),
                ),
            ),
            carried = listOf(
                item("c-torch", "Torch", quantity = 3, weightLb = 1.0, valueGp = 0.01),
                item("c-component", "Component Pouch", quantity = 1, weightLb = 2.0, valueGp = 25.0),
            ),
            carriedWeightLb = 38.0,
            capacityLb = 120,
        ),
        connection = ConnectionState.LIVE,
        canWrite = canWrite,
        layout = layout,
        sort = sort,
    )

    private fun item(
        id: String,
        name: String,
        quantity: Int = 1,
        weightLb: Double? = 1.0,
        valueGp: Double? = 1.0,
        equipped: Boolean = false,
        group: EquipGroup = EquipGroup.GEAR,
    ) = InventoryItem(
        propertyId = id,
        name = name,
        quantity = quantity,
        weightLb = weightLb,
        valueGp = valueGp,
        description = null,
        equipped = equipped,
        requiresAttunement = null,
        attuned = null,
        isEquippable = true,
        equipGroup = group,
        tags = emptyList(),
        containerId = null,
    )
}
