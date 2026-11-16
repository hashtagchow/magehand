package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.UseTarget
import com.hashtagchow.magehand.ui.screens.characterhome.actions.UnusableReason
import com.hashtagchow.magehand.ui.screens.characterhome.actions.detailFor
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.COMPANION_SPELL_ID
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures.FORM_ACTION_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-59 stage 1's chooser state (docs/design/22-charge-consumers.md R8, *"UI state: chooser
 * state from the board; a row without consumers is unchanged"*). Pure — no Compose runtime.
 */
class ChargeChooserStateTest {

    private val tracker = ChargeChooserFixtures.tracker()
    private val chargeRow = tracker.resources.single { it.propertyId == ChargeChooserFixtures.CHARGE_ROW_ID }
    private val plainRow = tracker.resources.single { it.propertyId == ChargeChooserFixtures.PLAIN_ROW_ID }

    @Test
    fun `the board's consumers reach the pip row unchanged`() {
        assertEquals(
            listOf(ChargeChooserFixtures.formConsumer, ChargeChooserFixtures.companionConsumer),
            chargeRow.consumers,
        )
        assertTrue(chargeRow.spendOpensChooser)
    }

    @Test
    fun `a row without consumers has no chooser and is otherwise the row it always was`() {
        assertFalse(plainRow.spendOpensChooser)
        assertNull(chargeChooserFor(plainRow, ChargeChooserFixtures.actions()))
        assertEquals(plainRow, plainRow.copy(consumers = emptyList()))
    }

    @Test
    fun `the chooser is titled with the resource and lists its consumers in order`() {
        val chooser = checkNotNull(chargeChooserFor(chargeRow, ChargeChooserFixtures.actions()))
        assertEquals(ChargeChooserFixtures.CHARGE_ROW_ID, chooser.rowId)
        assertEquals("Form Charges", chooser.title)
        assertEquals(listOf(FORM_ACTION_ID, COMPANION_SPELL_ID), chooser.consumers.map { it.actionId })
        assertEquals("An applied form buff", chooser.consumers[0].applies)
        assertNull(chooser.consumers[1].applies)
    }

    @Test
    fun `a usable consumer carries the Actions tab's own affordance`() {
        val actions = ChargeChooserFixtures.actions()
        val form = checkNotNull(chargeChooserFor(chargeRow, actions)).consumers[0]
        assertTrue(form.enabled)
        assertNull(form.disabledReasonRes)
        // The same value the Actions tab's detail sheet would hand its Use button — not a copy.
        assertEquals(actions.detailFor(FORM_ACTION_ID)?.use, form.use)
        assertTrue(form.use?.target is UseTarget.Action)
    }

    @Test
    fun `a consumer the Actions tab would refuse is disabled with that tab's reason`() {
        val companion = checkNotNull(chargeChooserFor(chargeRow, ChargeChooserFixtures.actions())).consumers[1]
        assertFalse(companion.enabled)
        assertNull(companion.use)
        assertEquals(UnusableReason.UNPREPARED, companion.unusableReason)
        assertEquals(R.string.action_detail_unusable_unprepared, companion.disabledReasonRes)
    }

    @Test
    fun `a use already on the wire disables the row with the Use button's in-flight label`() {
        val form = checkNotNull(
            chargeChooserFor(chargeRow, ChargeChooserFixtures.actions(usesInFlight = setOf(FORM_ACTION_ID))),
        ).consumers[0]
        assertFalse(form.enabled)
        assertEquals(R.string.action_use_in_flight, form.disabledReasonRes)
    }

    @Test
    fun `off LIVE the row is disabled with the Use button's offline label`() {
        val form = checkNotNull(chargeChooserFor(chargeRow, ChargeChooserFixtures.actions(canWrite = false)))
            .consumers[0]
        assertFalse(form.enabled)
        assertEquals(R.string.action_use_offline, form.disabledReasonRes)
    }

    @Test
    fun `a consumer the Actions state has no row for says only that it is not available`() {
        val stranger = chargeRow.copy(
            consumers = listOf(ChargeChooserFixtures.formConsumer.copy(actionId = "FakeAction-missing")),
        )
        val choice = checkNotNull(chargeChooserFor(stranger, ChargeChooserFixtures.actions())).consumers.single()
        assertFalse(choice.resolved)
        assertFalse(choice.enabled)
        assertEquals(R.string.tracker_consumer_unavailable, choice.disabledReasonRes)
    }

    @Test
    fun `a blank applies is treated as absent`() {
        val blank = chargeRow.copy(consumers = listOf(ChargeChooserFixtures.formConsumer.copy(applies = "  ")))
        assertNull(checkNotNull(chargeChooserFor(blank, ChargeChooserFixtures.actions())).consumers.single().applies)
    }

    @Test
    fun `a consumer's use in flight holds the row's own spend and just-spend`() {
        val inFlight = setOf(FORM_ACTION_ID)
        assertTrue(chargeRow.spendBlockedBy(inFlight))
        assertFalse(chargeRow.spendBlockedBy(emptySet()))
        assertFalse(plainRow.spendBlockedBy(inFlight))
        assertFalse(checkNotNull(chargeChooserFor(chargeRow, ChargeChooserFixtures.actions(usesInFlight = inFlight))).justSpendEnabled)
        assertTrue(checkNotNull(chargeChooserFor(chargeRow, ChargeChooserFixtures.actions())).justSpendEnabled)
    }
}
