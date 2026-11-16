package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ChargeConsumer
import com.hashtagchow.magehand.core.model.ChargeConsumerKind
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState

/**
 * FR-59 stage 1's synthetic character for the `:app` tests — one charge with two consumers and
 * one charge with none (docs/design/22-charge-consumers.md R8).
 *
 * Every name here is invented for the test and names nobody: a form action that applies one buff,
 * a companion spell that is **not prepared** (so the chooser has a disabled row to draw, with the
 * Actions tab's own reason), and a plain resource whose spend must stay exactly as it was.
 */
internal object ChargeChooserFixtures {

    const val CREATURE_ID = "FakeCreature59xyz"
    const val CHARGE_ROW_ID = "FakeResource-charges"
    const val PLAIN_ROW_ID = "FakeResource-plain"
    const val FORM_ACTION_ID = "FakeAction-form"
    const val COMPANION_SPELL_ID = "FakeSpell-companion"

    val formConsumer = ChargeConsumer(
        actionId = FORM_ACTION_ID,
        name = "Form Action",
        kind = ChargeConsumerKind.ACTION,
        applies = "An applied form buff",
    )

    val companionConsumer = ChargeConsumer(
        actionId = COMPANION_SPELL_ID,
        name = "Companion Spell",
        kind = ChargeConsumerKind.SPELL,
        applies = null,
    )

    /** 2 of 2 by default; `value` lowers it so a restore pip exists. */
    fun chargeRow(value: Int = 2) = TrackedResource(
        propertyId = CHARGE_ROW_ID,
        kind = TrackerKind.RESOURCE,
        name = "Form Charges",
        value = value,
        total = 2,
        reset = ResetRule.SHORT_REST,
        consumers = listOf(formConsumer, companionConsumer),
    )

    val plainRow = TrackedResource(
        propertyId = PLAIN_ROW_ID,
        kind = TrackerKind.RESOURCE,
        name = "Plain Charges",
        value = 3,
        total = 3,
        reset = ResetRule.LONG_REST,
        sortOrder = 1,
    )

    fun board(chargeValue: Int = 2) = TrackerBoard(resources = listOf(chargeRow(chargeValue), plainRow))

    val actionBoard = actionBoard()

    /** @param available what the form action's cost line reads — 0 makes its Use go away. */
    fun actionBoard(available: Int = 2) = ActionBoard(
        actions = listOf(
            ActionEntry(
                propertyId = FORM_ACTION_ID,
                name = "Form Action",
                type = ActionType.ACTION,
                cost = ActionCost(attributes = listOf(CostLine("Form Charges", amount = 1, available = available))),
            ),
        ),
        spells = listOf(
            SpellEntry(
                propertyId = COMPANION_SPELL_ID,
                name = "Companion Spell",
                level = 1,
                prepared = false,
                cost = ActionCost(attributes = listOf(CostLine("Form Charges", amount = 1, available = 2))),
            ),
        ),
    )

    fun actions(
        canWrite: Boolean = true,
        usesInFlight: Set<String> = emptySet(),
        board: ActionBoard = actionBoard,
    ): ActionsUiState = toActionsUiState(
        creatureId = CREATURE_ID,
        board = board,
        usesInFlight = usesInFlight,
        canWrite = canWrite,
    )

    fun tracker(chargeValue: Int = 2, canWrite: Boolean = true): TrackerUiState = toTrackerUiState(
        creatureId = CREATURE_ID,
        board = board(chargeValue),
        connection = com.hashtagchow.magehand.core.model.ConnectionState.LIVE,
        lastSyncedAt = null,
        isShowingSnapshot = false,
        canWrite = canWrite,
    )

    // ---- a long list: FR-59 stage 2's sheet-side delivery, one action per known form ----------

    const val MANY_CONSUMERS = 12

    fun manyConsumerId(n: Int) = "FakeAction-form-%02d".format(n)

    /** Twelve consumers sharing one name prefix, each applying its own buff. */
    fun manyConsumersRow(): PipRowState = tracker().resources.single { it.propertyId == CHARGE_ROW_ID }.copy(
        consumers = (1..MANY_CONSUMERS).map { n ->
            ChargeConsumer(
                actionId = manyConsumerId(n),
                name = "Form Action %02d".format(n),
                kind = ChargeConsumerKind.ACTION,
                applies = "An applied form buff %02d".format(n),
            )
        },
    )

    fun manyConsumersActions(): ActionsUiState = toActionsUiState(
        creatureId = CREATURE_ID,
        board = ActionBoard(
            actions = (1..MANY_CONSUMERS).map { n ->
                ActionEntry(propertyId = manyConsumerId(n), name = "Form Action %02d".format(n), type = ActionType.ACTION)
            },
        ),
        canWrite = true,
    )
}
