package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design 22 addendum 2 item 1 (review H1), on the **Actions tab**, where the defect has been since
 * 1.10.0: a spell the sheet marks `castWithoutSpellSlots` asks for no slot — no picker, Confirm
 * never blocked on "no slots", and the `slotId` the Use carries is `null` (so the frame carries
 * none: `WriteOp.castSpell` omits an absent slot). Ritual behaviour is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlotlessSpellUseTest {

    @get:Rule
    val compose = createComposeRule()

    private val slot = TrackedResource(
        propertyId = "FakeSlot-level3", kind = TrackerKind.SPELL_SLOT, name = "3rd Level",
        value = 1, total = 2, reset = ResetRule.LONG_REST, spellSlotLevel = 3,
    )

    private fun detail(slotless: Boolean, ritual: Boolean = false, slots: List<TrackedResource> = listOf(slot)) =
        checkNotNull(
            toActionsUiState(
                creatureId = "FakeCreature-slotless",
                board = ActionBoard(
                    spells = listOf(
                        SpellEntry(
                            propertyId = "FakeSpell-a", name = "Test Spell", level = 3, prepared = true,
                            ritual = ritual, castWithoutSpellSlots = slotless,
                        ),
                    ),
                ),
                spellSlots = slots,
                canWrite = true,
            ).detailFor("FakeSpell-a"),
        )

    @Test
    fun `the affordance of a slot-less spell has no picker and never blocks Confirm`() {
        val use = checkNotNull(detail(slotless = true, slots = emptyList()).use)
        assertFalse(use.showsSlotPicker)
        assertTrue(use.slots.isEmpty())
        assertFalse(use.confirmDisabled(ritual = false))
        assertEquals(null, use.spend(ritual = false, slotId = null).slot)

        // The ordinary spell is unchanged: picker shown, Confirm blocked with nothing to pick.
        val ordinary = checkNotNull(detail(slotless = false, slots = emptyList()).use)
        assertTrue(ordinary.showsSlotPicker)
        assertTrue(ordinary.confirmDisabled(ritual = false))
    }

    @Test
    fun `ritual interplay is unchanged by the flag`() {
        assertTrue(checkNotNull(detail(slotless = true, ritual = true).use).showsRitual)
        assertTrue(checkNotNull(detail(slotless = false, ritual = true).use).showsRitual)
    }

    @Test
    fun `a slot-less spell is confirmed with no slot, even with a slot left to spend`() {
        val sent = mutableListOf<String>()
        val state = detail(slotless = true)
        compose.setMageHandContent {
            ActionDetailSheet(
                state = state,
                onUse = { target, slotId, ritual -> sent += "${target.propertyId} slot=$slotId ritual=$ritual" },
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("actions:detail:use").performClick()
        compose.onNodeWithTag("actions:use:slot:${slot.propertyId}").assertDoesNotExist()
        compose.onNodeWithTag("actions:use:confirm").assertIsEnabled().performClick()

        assertEquals(listOf("FakeSpell-a slot=null ritual=false"), sent)
    }
}
