package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.hashtagchow.magehand.core.data.characters.CharacterListState
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ChargeConsumer
import com.hashtagchow.magehand.core.model.ChargeConsumerKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.CHARGE_ROW_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.CREATURE_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.FORM_ACTION_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerActions
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import com.hashtagchow.magehand.ui.webview.SheetSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-59 stage 1's **write posture** (docs/design/22-charge-consumers.md R5/R8: *"a consumer use
 * sends exactly one frame"*), through the real view model and `TrackerTabIntentTest`'s fake.
 *
 * The claim spans the whole chain — pip → chooser → consumer row → the Actions tab's confirm →
 * `CharacterHomeViewModel.use` → `OpenCharacter.useAction` — so it is pinned here, where every
 * join is real, rather than only at the composable. `FakeOpenCharacter.writes` records every
 * intent the character receives: one `use`, and no `spend` beside it, is the one-frame guarantee
 * at the seam `:app` owns (`DefaultOpenCharacterWriteTest` already pins that one `useAction` is
 * one `doAction` frame).
 *
 * The wiring below is `CharacterHomeScreen`'s own, verbatim for the three bindings under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeChooserIntentTest {

    @get:Rule
    val compose = createComposeRule()

    private val character = FakeOpenCharacter(creatureId = CREATURE_ID).apply {
        board.value = ChargeChooserFixtures.board()
        boardIgnoringHidden.value = board.value
        actions.value = ChargeChooserFixtures.actionBoard
        connectionState.value = ConnectionState.LIVE
        canWrite.value = true
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun show(viewModel: CharacterHomeViewModel) {
        compose.setMageHandContent {
            val state by viewModel.uiState.collectAsState()
            TrackerTab(
                state = state.tracker,
                actions = TrackerActions(
                    onSpend = viewModel::spend,
                    onRestore = viewModel::restore,
                    onUse = viewModel::use,
                ),
                useState = state.actions,
            )
        }
    }

    @Test
    fun `using a consumer from the chooser sends one use and no spend`() {
        show(viewModel())

        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:0").performClick()
        compose.onNodeWithTag("tracker:consumer:$FORM_ACTION_ID").performClick()
        compose.onNodeWithTag("actions:use:confirm").performClick()

        assertEquals(listOf("use $FORM_ACTION_ID"), character.writes.toList())
    }

    @Test
    fun `just spending from the chooser sends today's one spend and no use`() {
        show(viewModel())

        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:0").performClick()
        compose.onNodeWithTag("tracker:consumer:spend").performClick()

        assertEquals(listOf("spend $CHARGE_ROW_ID 1"), character.writes.toList())
    }

    @Test
    fun `dismissing the confirm sends nothing at all`() {
        show(viewModel())

        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:0").performClick()
        compose.onNodeWithTag("tracker:consumer:$FORM_ACTION_ID").performClick()
        compose.onNodeWithTag("actions:use:confirm").assertExists()
        // The dialog's own Cancel — the Actions tab's, unchanged.
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<String>(), character.writes.toList())
    }

    /**
     * Design 22 addendum 2 item 1, at the seam where it bit: a slot-less spell consumer, on a
     * character who HAS a slot to spend, confirms with no picker and sends no `slotId`.
     */
    @Test
    fun `a slot-less spell consumer is cast with no slot even when slots are left`() {
        val slot = TrackedResource(
            propertyId = "FakeSlot-level1", kind = TrackerKind.SPELL_SLOT, name = "1st Level",
            value = 2, total = 2, reset = ResetRule.LONG_REST, spellSlotLevel = 1,
        )
        val slotless = ChargeConsumer("FakeSpell-slotless", "Slotless Spell", ChargeConsumerKind.SPELL, "A slotless buff")
        character.board.value = TrackerBoard(
            slots = listOf(slot),
            resources = listOf(ChargeChooserFixtures.chargeRow().copy(consumers = listOf(slotless))),
        )
        character.boardIgnoringHidden.value = character.board.value
        character.actions.value = ActionBoard(
            spells = listOf(
                SpellEntry(
                    propertyId = "FakeSpell-slotless", name = "Slotless Spell", level = 1, prepared = true,
                    castWithoutSpellSlots = true,
                ),
            ),
        )
        show(viewModel())

        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:0").performClick()
        compose.onNodeWithTag("tracker:consumer:FakeSpell-slotless").performClick()
        compose.onNodeWithTag("actions:use:slot:FakeSlot-level1").assertDoesNotExist()
        compose.onNodeWithTag("actions:use:slots:none").assertDoesNotExist()
        compose.onNodeWithTag("actions:use:confirm").assertIsEnabled().performClick()

        assertEquals(listOf("cast FakeSpell-slotless slot=none ritual=false"), character.writes.toList())
    }

    private fun viewModel() = CharacterHomeViewModel(
        savedStateHandle = SavedStateHandle(mapOf("creatureId" to CREATURE_ID)),
        characterListRepository = FakeCharacterListRepository(
            CharacterListState(
                characters = listOf(CharacterSummary(CREATURE_ID, "Test Druid")),
                connection = ConnectionState.LIVE,
            ),
        ),
        sheetSessionFactory = SheetSessionFactory(StubAccountRepository, StubTokenStore),
        appSettingsStore = FakeAppSettingsStore(showToggles = true, showLimitedUses = true),
        selectedRollStore = FakeSelectedRollStore(),
        equippableOverrideStore = FakeEquippableOverrideStore(),
        inventoryLayoutStore = FakeInventoryLayoutStore(),
        paneLayoutStore = FakePaneLayoutStore(),
        openCharacterFactory = FakeOpenCharacterFactory(character),
        connectionManager = RecordingConnectionManager(),
        activityFeedRepository = FakeActivityFeedRepository(),
    )
}
