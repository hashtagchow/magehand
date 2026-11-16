package com.hashtagchow.magehand.ui.screens.dmview

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-59 stage 1 R6: **the DM card is out of scope**, so it keeps today's behaviour even for a
 * resource row that carries consumers.
 *
 * Today's behaviour on the card is that a resource is a read-only line — the card's write controls
 * are HP and spell slots only (`CardWriteControls`), and its resources go through `ResourceLine`,
 * which has no control. The card reads the same `PipRowState` the tracker does, so the row
 * arriving here really does carry its consumers (asserted first); the claim is that nothing on the
 * card grew a spend, a chooser, or a use because of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DmCardChargeConsumerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a charge with consumers is still a read-only line on the DM card, with no chooser`() {
        val row = ChargeChooserFixtures.tracker().resources
            .single { it.propertyId == ChargeChooserFixtures.CHARGE_ROW_ID }
        assertTrue("the row must carry consumers or this proves nothing", row.consumers.isNotEmpty())

        val spends = mutableListOf<String>()
        compose.setMageHandContent {
            DmCard(
                card = DmCardUiState(
                    creatureId = ChargeChooserFixtures.CREATURE_ID,
                    name = "Test Character",
                    monogram = "T",
                    availability = DmCardAvailability.AVAILABLE,
                    hp = HpState(propertyId = "hp", current = 10, max = 10, tempHp = 0),
                    resources = listOf(row),
                    showsWriteControls = true,
                    writeControlsEnabled = true,
                    permissionDenied = false,
                    grantedEditing = true,
                ),
                onClick = {},
                onSpend = { spends += it },
                onRestore = {},
                onChangeHitPoints = {},
                onToggleCondition = {},
                onTurnOffBuff = {},
            )
        }

        compose.onNodeWithContentDescription("Spend one Form Charges").assertDoesNotExist()
        compose.onNodeWithTag("tracker:consumers:${ChargeChooserFixtures.CHARGE_ROW_ID}").assertDoesNotExist()
        compose.onNodeWithTag("tracker:consumer:spend").assertDoesNotExist()
        assertEquals(emptyList<String>(), spends)
    }
}
