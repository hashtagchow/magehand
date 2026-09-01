package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.data.characters.CharacterListState
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
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
 * **FR-34 layer 1's click→intent exemplar** (docs/design/19-ui-test-infrastructure.md decision 4):
 * *"a spend tap reaches FakeOpenCharacter as the right intent"*.
 *
 * ### Why the real view model is in the loop here and nowhere else
 *
 * Decision 2 is emphatic that render tests do **not** build a view model — they construct a
 * `UiState` and compose it, because the seam under test is `UiState → Composable`. This test is
 * the one exception the same decision names, and the reason is that its claim spans the whole
 * chain: a finger lands on a pip, `PipRow` decides that a *filled* pip means spend, `TrackerTab`
 * hands the id to `TrackerActions.onSpend`, `CharacterHomeScreen` wires that to
 * `viewModel::spend`, and `CharacterHomeViewModel.withRow` re-resolves the id against the live
 * board before calling `OpenCharacter.spend`. Every link there is covered somewhere except the
 * joins, and a wiring bug — `onSpend` bound to `restore`, or a pip reporting the wrong row's id —
 * lives exactly in a join.
 *
 * `CharacterHomeViewModelTest` proves everything from `spend(propertyId)` inward, exhaustively.
 * This proves the two centimetres of glass in front of it, once, and leaves the rest alone.
 *
 * ### `UnconfinedTestDispatcher`, and why not the `StandardTestDispatcher` next door
 *
 * `CharacterHomeViewModelTest` drives virtual time deliberately — it asserts about coalescing
 * windows and re-subscription, which need a clock you can advance. This test has no timing claim
 * at all; it needs the view model to have *finished opening the character* before a click can
 * mean anything, and `Dispatchers.setMain(UnconfinedTestDispatcher())` gets that by running
 * `viewModelScope` eagerly at construction. A `StandardTestDispatcher` here would need
 * `advanceUntilIdle` interleaved with the Compose rule's own clock, which is two schedulers
 * pretending to be one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackerTabIntentTest {

    @get:Rule
    val compose = createComposeRule()

    private val creatureId = "FakeCreature23456"

    /** A caster's 3-of-4 first-level slots — the capture's own row, as a board the fake serves. */
    private val slot = TrackedResource(
        propertyId = "slot-1st",
        name = "1st Level",
        value = 3,
        total = 4,
        kind = TrackerKind.SPELL_SLOT,
        reset = ResetRule.LONG_REST,
    )

    private val character = FakeOpenCharacter(creatureId = creatureId).apply {
        board.value = TrackerBoard(slots = listOf(slot))
        boardIgnoringHidden.value = board.value
        connectionState.value = ConnectionState.LIVE
        canWrite.value = true
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tapping a filled pip spends that row, and an empty one restores it`() {
        val viewModel = viewModel()

        compose.setMageHandContent {
            val state by viewModel.uiState.collectAsState()
            TrackerTab(
                state = state.tracker,
                // The screen's own wiring, verbatim from `CharacterHomeScreen`. Copying the two
                // bindings under test — rather than only the one — is what makes a swap of them
                // fail here: a test that wired `onSpend` alone would pass with `onRestore`
                // pointing anywhere at all.
                actions = TrackerActions(
                    onSpend = viewModel::spend,
                    onRestore = viewModel::restore,
                ),
            )
        }

        // Pip 0 is one of the three filled ones, so it spends. The tag picks the node — a
        // content-description finder would match all three filled pips — and the description is
        // asserted *on* it, so the sentence a screen-reader user is told and the wire the tap
        // reaches are proven to be the same control rather than two controls that agree today.
        compose.onNodeWithTag("tracker:slot:slot-1st:pip:0")
            .assertContentDescriptionEquals("Spend one 1st Level")
            .performClick()
        assertEquals(listOf("spend slot-1st 1"), character.writes.toList())

        // Pip 3 is the empty one — the same row, the opposite intent, one pip along.
        compose.onNodeWithTag("tracker:slot:slot-1st:pip:3")
            .assertContentDescriptionEquals("Restore one 1st Level")
            .performClick()
        assertEquals(listOf("spend slot-1st 1", "restore slot-1st 1"), character.writes.toList())
    }

    @Test
    fun `a read-only board's pips reach nothing at all`() {
        // 06's rule that writes require LIVE. The controls are *inert*, not merely dimmed, and
        // that is the half a screenshot cannot show: `Pip` passes `enabled = canWrite` to
        // `clickable`, and a disabled clickable still occupies the same 48 dp of screen.
        character.canWrite.value = false
        val viewModel = viewModel()

        compose.setMageHandContent {
            val state by viewModel.uiState.collectAsState()
            TrackerTab(
                state = state.tracker,
                actions = TrackerActions(onSpend = viewModel::spend, onRestore = viewModel::restore),
            )
        }

        compose.onNodeWithTag("tracker:slot:slot-1st:pip:0").performClick()

        assertEquals(emptyList<String>(), character.writes.toList())
    }

    private fun viewModel() = CharacterHomeViewModel(
        savedStateHandle = SavedStateHandle(mapOf("creatureId" to creatureId)),
        characterListRepository = FakeCharacterListRepository(
            CharacterListState(
                characters = listOf(CharacterSummary(creatureId, "Sabriel")),
                connection = ConnectionState.LIVE,
            ),
        ),
        sheetSessionFactory = SheetSessionFactory(StubAccountRepository, StubTokenStore),
        appSettingsStore = FakeAppSettingsStore(showToggles = true),
        selectedRollStore = FakeSelectedRollStore(),
        equippableOverrideStore = FakeEquippableOverrideStore(),
        inventoryLayoutStore = FakeInventoryLayoutStore(),
        paneLayoutStore = FakePaneLayoutStore(),
        openCharacterFactory = FakeOpenCharacterFactory(character),
        connectionManager = RecordingConnectionManager(),
        activityFeedRepository = FakeActivityFeedRepository(),
    )
}
