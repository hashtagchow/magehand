package com.hashtagchow.magehand.ui.golden

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.Account
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionDetailSheet
import com.hashtagchow.magehand.ui.screens.characterhome.actions.detailFor
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.screens.dmview.DmCard
import com.hashtagchow.magehand.ui.screens.dmview.DmCardAvailability
import com.hashtagchow.magehand.ui.screens.dmview.DmCardUiState
import com.hashtagchow.magehand.ui.screens.login.CredentialsScreen
import com.hashtagchow.magehand.ui.screens.login.CredentialsViewModel
import com.hashtagchow.magehand.ui.screens.settings.SettingsScreen
import com.hashtagchow.magehand.ui.screens.settings.SettingsViewModel
import com.hashtagchow.magehand.ui.testing.FakeAccounts
import com.hashtagchow.magehand.ui.testing.FakeSettings
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.captureGolden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rest of design 19 decision 7's seed corpus: *"DmCard, ActionDetailSheet, SettingsScreen,
 * CredentialsScreen"*.
 *
 * ### One golden each, and why that is enough here
 *
 * The tracker and the tab row get variant matrices because both have a *history* of wrapping and
 * both are the screens the table stares at. These four are seeds in the literal sense: one picture
 * apiece, so that the next wave's conversion has a baseline to diff against and so that a
 * palette, spacing or Material-version change cannot land silently on any of the app's surfaces.
 * Variants get added where a defect is reported, not pre-emptively — a corpus nobody reviews is a
 * corpus that gets re-recorded without being looked at, which design 19 decision 8 names as the
 * failure mode of the whole layer.
 *
 * ### Store safety (design 19 decision 9)
 *
 * The settings golden shows an account, and its server is **`dicecloud.com`** — the app's own
 * `CredentialsViewModel.DEFAULT_SERVER_URL` — under an obviously invented username. The login
 * golden prints the same host from that same constant. Neither the private table host, nor a
 * token, nor a real account name appears in this corpus.
 *
 * `tools/public-gate.sh` already reaches the goldens: its source-root mode scans **`app/src`**,
 * which contains `app/src/test/snapshots/`. A string cannot hide in a PNG, but a filename can, and
 * the first run of this wave's gate caught four real leaks in the *sources* around them — the
 * party surname the fixture was named after, two of the capture's feature names, and the capture's
 * own filename. The rule binds test code exactly as it binds `main`.
 *
 * ### The two Hilt screens
 *
 * `SettingsScreen` and `CredentialsScreen` default their view model to `hiltViewModel()`. Both are
 * ordinary constructors underneath, so the golden passes a real view model built on the fakes in
 * `ui/testing/Fakes.kt` — no Hilt graph, which is the same rule decision 2 sets for every other
 * test in this wave. `Dispatchers.setMain` is what lets `SettingsViewModel`'s
 * `stateIn(viewModelScope, …)` produce its first value before the frame is captured; without it
 * the golden would be a picture of the empty seed state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a dm dashboard card with its write controls`() {
        compose.captureGolden("DmCard") {
            DmCard(
                card = DmCardUiState(
                    creatureId = Sabriel.CREATURE_ID,
                    name = "Sabriel",
                    monogram = "S",
                    availability = DmCardAvailability.AVAILABLE,
                    hp = HpState(propertyId = "hp", current = 11, max = 17, tempHp = 0),
                    slots = listOf(Sabriel.firstLevel, Sabriel.secondLevel),
                    concentratingOn = "Bless",
                    // The editing-on state, which is the one with something to photograph: a
                    // read-only card is the same card minus every control, and `DmCardRenderTest`
                    // asserts that absence far more precisely than a picture could.
                    showsWriteControls = true,
                    writeControlsEnabled = true,
                    grantedEditing = true,
                ),
                onClick = {},
                onSpend = {},
                onRestore = {},
                onChangeHitPoints = {},
                onToggleCondition = {},
            )
        }
    }

    @Test
    fun `the action detail sheet, on a limited action with a cost`() {
        // Rage: a bonus action, 2 of 3 left, costing one of its own resource. That combination is
        // what puts every one of 17 decision 1's blocks on screen at once — Cost, Uses, and the
        // Use affordance — which is what makes this one picture worth having.
        val detail = toActionsUiState(
            creatureId = Sabriel.CREATURE_ID,
            board = ActionBoard(
                actions = listOf(
                    ActionEntry(
                        propertyId = "a-rage",
                        name = "Rage",
                        type = ActionType.BONUS,
                        cost = ActionCost(
                            attributes = listOf(CostLine("Rage", amount = 1, available = 2)),
                        ),
                        uses = ActionUses(max = 3, used = 1),
                        description = "In a frenzy, you gain advantage on Strength checks and " +
                            "saving throws, and resistance to bludgeoning, piercing and " +
                            "slashing damage.",
                    ),
                ),
            ),
            canWrite = true,
        ).detailFor("a-rage")

        compose.captureGolden("ActionDetailSheet") {
            ActionDetailSheet(state = checkNotNull(detail), onUse = { _, _, _ -> }, onDismiss = {})
        }
    }

    @Test
    fun `the settings screen with one signed-in account`() {
        val viewModel = SettingsViewModel(
            accountRepository = FakeAccounts(
                listOf(
                    Account(
                        id = "acct-1",
                        // Store safety: the public host, never the private table's.
                        serverUrl = "https://dicecloud.com",
                        userId = "user-1",
                        username = "magehand-test",
                        addedAt = 0L,
                        lastUsedAt = 0L,
                    ),
                ),
            ),
            appSettingsStore = FakeSettings(),
        )

        compose.captureGolden("SettingsScreen") {
            SettingsScreen(onBack = {}, onSignedOut = {}, viewModel = viewModel)
        }
    }

    @Test
    fun `the sign-in screen as a first run finds it`() {
        val viewModel = CredentialsViewModel(FakeAccounts())

        compose.captureGolden("CredentialsScreen") {
            CredentialsScreen(onSignedIn = {}, onContinueWithoutAccount = {}, viewModel = viewModel)
        }
    }
}
