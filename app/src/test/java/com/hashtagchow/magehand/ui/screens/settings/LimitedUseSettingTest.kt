package com.hashtagchow.magehand.ui.screens.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.ui.testing.FakeAccounts
import com.hashtagchow.magehand.ui.testing.FakeSettings
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-44 R3's switch, rendered — *"Show limited-use abilities on tracker"*, **default ON**.
 *
 * ### Why the default is asserted here rather than only on the store
 *
 * R3's default is the ruling's own emphasis (*"the table asked for the rows, the switch is for
 * people who did not"*), and it is asymmetric with FR-6's switch two rows up, which ships off.
 * Two adjacent controls with opposite defaults is exactly the pair somebody "makes consistent"
 * later, so the value is pinned where a reader of the screen would look for it and again at
 * [AppSettingsStore.DEFAULT_SHOW_LIMITED_USES], which is the constant both the store and
 * `toTrackerUiState` seed from.
 *
 * ### And why the write-through is asserted through the store
 *
 * The switch holds no state of its own — it renders `uiState`, which is the DataStore flow — so
 * a control that appeared to work while writing nothing would photograph perfectly and pass any
 * assertion made on the node alone. `FakeSettings` is writeable for that reason; the tap is
 * checked at the far end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LimitedUseSettingTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = FakeSettings()

    private val switch get() = compose.onNodeWithTag("settings:show-limited-uses")

    private fun render() {
        compose.setMageHandContent {
            SettingsScreen(
                onBack = {},
                onSignedOut = {},
                viewModel = SettingsViewModel(
                    accountRepository = FakeAccounts(),
                    appSettingsStore = settings,
                ),
            )
        }
    }

    /** The ruling's default, on the screen. */
    @Test
    fun `the switch ships on`() {
        assertTrue("R3: default ON", AppSettingsStore.DEFAULT_SHOW_LIMITED_USES)

        render()

        switch.assertIsOn()
    }

    /** R3's string, as the ruling wrote it, plus the one-line note it asks for. */
    @Test
    fun `the row says what it is and what a limited use is`() {
        render()

        compose.onNodeWithText("Show limited-use abilities on tracker").assertExists()
        compose.onNodeWithText("Actions and spells with a use count, like 2 per long rest.")
            .assertExists()
    }

    /**
     * A tap writes through and the control follows the *store*, not a local remembered value —
     * FR-6's shape, and the property that makes "the switch shows what every tracker is reading"
     * true rather than hopeful.
     */
    @Test
    fun `a tap writes through to the store and the switch follows`() {
        render()

        switch.performClick()
        compose.waitForIdle()

        assertEquals(false, runBlocking { settings.showLimitedUses.first() })
        switch.assertIsOff()

        switch.performClick()
        compose.waitForIdle()

        assertEquals(true, runBlocking { settings.showLimitedUses.first() })
        switch.assertIsOn()
    }
}
