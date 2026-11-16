package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hashtagchow.magehand.core.model.UseTarget
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.CHARGE_ROW_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.COMPANION_SPELL_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.FORM_ACTION_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.PLAIN_ROW_ID
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-59 stage 1's chooser, rendered (docs/design/22-charge-consumers.md R8: *"spend opens the
 * sheet only when consumers exist; each row fires the right intent; restore untouched; spoken
 * labels"*).
 *
 * Every intent is recorded into one list, so each test can assert not only that the right
 * callback fired but that **nothing else** did — the posture R5 is about (a consumer's use must
 * not also spend the pip).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeChooserRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<String>()

    private val actions = TrackerActions(
        onSpend = { intents += "spend $it" },
        onRestore = { intents += "restore $it" },
        onUse = { target, slotId, ritual ->
            val kind = if (target is UseTarget.Spell) "cast" else "use"
            intents += "$kind ${target.propertyId} slot=$slotId ritual=$ritual"
        },
    )

    private var trackerState by mutableStateOf(ChargeChooserFixtures.tracker())
    private var useState by mutableStateOf(ChargeChooserFixtures.actions())

    private fun show(
        chargeValue: Int = 2,
        canWrite: Boolean = true,
        usesInFlight: Set<String> = emptySet(),
    ) {
        trackerState = ChargeChooserFixtures.tracker(chargeValue = chargeValue, canWrite = canWrite)
        useState = ChargeChooserFixtures.actions(canWrite = canWrite, usesInFlight = usesInFlight)
        compose.setMageHandContent {
            TrackerTab(state = trackerState, actions = actions, useState = useState)
        }
    }

    private fun tapSpendOnChargeRow() =
        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:0").performClick()

    @Test
    fun `spending a charge with consumers opens the chooser and writes nothing`() {
        show()
        tapSpendOnChargeRow()

        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertIsDisplayed()
        assertEquals(emptyList<String>(), intents)
    }

    @Test
    fun `spending a charge with no consumers writes as it always did, with no chooser`() {
        show()
        compose.onNodeWithTag("tracker:resource:$PLAIN_ROW_ID:pip:0").performClick()

        compose.onNodeWithTag("tracker:consumers:$PLAIN_ROW_ID").assertDoesNotExist()
        assertEquals(listOf("spend $PLAIN_ROW_ID"), intents)
    }

    @Test
    fun `restoring a charge with consumers is a plain restore`() {
        // 1 of 2: pip 1 is the spent one.
        show(chargeValue = 1)
        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:1").performClick()

        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
        assertEquals(listOf("restore $CHARGE_ROW_ID"), intents)
    }

    @Test
    fun `a read-only board offers no chooser because it offers no spend`() {
        show(canWrite = false)
        tapSpendOnChargeRow()

        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
        assertEquals(emptyList<String>(), intents)
    }

    @Test
    fun `each row speaks one sentence, and a refused consumer says why`() {
        show()
        tapSpendOnChargeRow()

        compose.onNodeWithTag("tracker:consumer:$FORM_ACTION_ID")
            .assertContentDescriptionEquals("Use Form Action, applies An applied form buff")
            .assertIsEnabled()
        compose.onNodeWithTag("tracker:consumer:$COMPANION_SPELL_ID")
            .assertContentDescriptionEquals(
                "Can't use Companion Spell: Not prepared. Prepare it in DiceCloud to use it here.",
            )
            .assertIsNotEnabled()
        compose.onNodeWithTag("tracker:consumer:spend")
            .assertContentDescriptionEquals("Just spend a charge")
            .assertIsEnabled()
    }

    @Test
    fun `just spend a charge is today's spend, once, and closes the chooser`() {
        show()
        tapSpendOnChargeRow()
        compose.onNodeWithTag("tracker:consumer:spend").performClick()

        assertEquals(listOf("spend $CHARGE_ROW_ID"), intents)
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
    }

    @Test
    fun `a consumer row runs the Actions tab's Use through its confirm, and never spends the pip`() {
        show()
        tapSpendOnChargeRow()
        compose.onNodeWithTag("tracker:consumer:$FORM_ACTION_ID").performClick()

        // The chooser closes and the Actions tab's own dialog takes over — nothing is sent yet.
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
        assertEquals(emptyList<String>(), intents)

        compose.onNodeWithTag("actions:use:confirm").performClick()

        assertEquals(listOf("use $FORM_ACTION_ID slot=null ritual=false"), intents)
        compose.onNodeWithTag("actions:use:confirm").assertDoesNotExist()
    }

    @Test
    fun `a disabled consumer row does nothing when tapped`() {
        show()
        tapSpendOnChargeRow()
        compose.onNodeWithTag("tracker:consumer:$COMPANION_SPELL_ID").performClick()

        compose.onNodeWithTag("actions:use:confirm").assertDoesNotExist()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertIsDisplayed()
        assertEquals(emptyList<String>(), intents)
    }

    @Test
    fun `the chooser's title is a heading`() {
        show()
        tapSpendOnChargeRow()
        compose.onNode(isHeading() and hasText("Form Charges")).assertIsDisplayed()
    }

    // ---- addendum 2 item 6: cleared, not hidden ------------------------------------------

    @Test
    fun `a confirm whose Use goes away is cleared, and does not come back with the Use`() {
        show()
        tapSpendOnChargeRow()
        compose.onNodeWithTag("tracker:consumer:$FORM_ACTION_ID").performClick()
        compose.onNodeWithTag("actions:use:confirm").assertIsDisplayed()

        // Another client spends the last charge: the action can no longer pay, its Use is gone.
        useState = ChargeChooserFixtures.actions(board = ChargeChooserFixtures.actionBoard(available = 0))
        compose.waitForIdle()
        compose.onNodeWithTag("actions:use:confirm").assertDoesNotExist()

        // A rest brings the charge back. The dialog must NOT reopen by itself.
        useState = ChargeChooserFixtures.actions()
        compose.waitForIdle()
        compose.onNodeWithTag("actions:use:confirm").assertDoesNotExist()
        assertEquals(emptyList<String>(), intents)
    }

    @Test
    fun `a confirm is cleared when write access goes, and stays closed when it returns`() {
        show()
        tapSpendOnChargeRow()
        compose.onNodeWithTag("tracker:consumer:$FORM_ACTION_ID").performClick()
        compose.onNodeWithTag("actions:use:confirm").assertIsDisplayed()

        trackerState = ChargeChooserFixtures.tracker(canWrite = false)
        compose.waitForIdle()
        compose.onNodeWithTag("actions:use:confirm").assertDoesNotExist()

        trackerState = ChargeChooserFixtures.tracker()
        compose.waitForIdle()
        compose.onNodeWithTag("actions:use:confirm").assertDoesNotExist()
    }

    @Test
    fun `a chooser whose row loses its consumers is cleared, and does not come back with them`() {
        show()
        tapSpendOnChargeRow()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertIsDisplayed()

        trackerState = trackerState.copy(
            resources = trackerState.resources.map { it.copy(consumers = emptyList()) },
        )
        compose.waitForIdle()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()

        trackerState = ChargeChooserFixtures.tracker()
        compose.waitForIdle()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
    }

    @Test
    fun `a chooser whose row disappears is cleared, and does not come back with it`() {
        show()
        tapSpendOnChargeRow()

        trackerState = trackerState.copy(resources = trackerState.resources.filter { it.propertyId != CHARGE_ROW_ID })
        compose.waitForIdle()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()

        trackerState = ChargeChooserFixtures.tracker()
        compose.waitForIdle()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
    }

    @Test
    fun `a chooser is cleared when write access goes, and stays closed when it returns`() {
        show()
        tapSpendOnChargeRow()

        trackerState = ChargeChooserFixtures.tracker(canWrite = false)
        compose.waitForIdle()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()

        trackerState = ChargeChooserFixtures.tracker()
        compose.waitForIdle()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
    }

    // ---- addendum 2 item 7: no second charge while a use is in flight -------------------

    @Test
    fun `while a consumer's use is in flight the row's own spend is held, restore is not`() {
        show(chargeValue = 1, usesInFlight = setOf(FORM_ACTION_ID))

        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:0").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("tracker:consumers:$CHARGE_ROW_ID").assertDoesNotExist()
        compose.onNodeWithTag("tracker:resource:$CHARGE_ROW_ID:pip:1").assertIsEnabled().performClick()

        assertEquals(listOf("restore $CHARGE_ROW_ID"), intents)
    }

    @Test
    fun `just spend a charge is held while a consumer's use is in flight`() {
        show()
        tapSpendOnChargeRow()
        // The use goes out (from another surface, or a confirm that just landed) while open.
        useState = ChargeChooserFixtures.actions(usesInFlight = setOf(FORM_ACTION_ID))
        compose.waitForIdle()

        compose.onNodeWithTag("tracker:consumer:spend").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), intents)
    }

    /**
     * The sheet itself latches *Just spend a charge*. Driven against the bare sheet with a caller
     * that does NOT close it, so the second tap really lands on a live node: through `TrackerTab`
     * the tab's own close usually wins the race, which is exactly why the latch cannot rely on it.
     */
    @Test
    fun `a double tap on just spend a charge spends once`() {
        val row = ChargeChooserFixtures.tracker().resources.single { it.propertyId == CHARGE_ROW_ID }
        val state = checkNotNull(chargeChooserFor(row, ChargeChooserFixtures.actions()))
        compose.setMageHandContent {
            ChargeChooserSheet(state = state, onUse = {}, onJustSpend = { intents += "spend" }, onDismiss = {})
        }
        compose.onNodeWithTag("tracker:consumer:spend").performClick()
        compose.onNodeWithTag("tracker:consumer:spend").assertIsNotEnabled().performClick()

        assertEquals(listOf("spend"), intents)
    }
}
