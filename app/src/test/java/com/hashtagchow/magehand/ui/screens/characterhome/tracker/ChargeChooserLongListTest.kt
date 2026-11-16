package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-59 stage 2's sheet-side delivery puts one action per known form on a sheet, so one charge can
 * carry a dozen consumers. At the smallest height this suite renders (240 dp) the chooser must
 * still let the player reach every consumer — by scrolling — and *Just spend a charge* without
 * scrolling at all: it is fixed below the scrolling list (see `ChargeChooserContent`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h240dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeChooserLongListTest {

    @get:Rule
    val compose = createComposeRule()

    private val intents = mutableListOf<String>()
    private var fontScale = 0f

    private fun show(title: String? = null) {
        val row = ChargeChooserFixtures.manyConsumersRow().let { if (title == null) it else it.copy(label = title) }
        val state = checkNotNull(chargeChooserFor(row, ChargeChooserFixtures.manyConsumersActions()))
        compose.setMageHandContent {
            fontScale = LocalDensity.current.fontScale
            ChargeChooserSheet(
                state = state,
                onUse = { intents += "use ${it.target.propertyId}" },
                onJustSpend = { intents += "spend" },
                onDismiss = {},
            )
        }
    }

    /**
     * @param footerFixed `true` where the fixed-footer layout fits (font scale 1.0 here): *Just
     *   spend a charge* is on screen before any scrolling and after it. `false` at the extreme where
     *   the sheet falls back to scrolling its whole body: it is reached by scrolling instead.
     */
    private fun scrollToAndTapTheLastConsumerThenJustSpend(footerFixed: Boolean = true) {
        val last = ChargeChooserFixtures.manyConsumerId(ChargeChooserFixtures.MANY_CONSUMERS)
        if (footerFixed) compose.onNodeWithTag("tracker:consumer:spend").assertIsDisplayed()
        compose.onNodeWithTag("tracker:consumer:$last").performScrollTo().assertIsDisplayed().performClick()
        val spend = compose.onNodeWithTag("tracker:consumer:spend")
        if (!footerFixed) spend.performScrollTo()
        spend.assertIsDisplayed().performClick()

        assertEquals(listOf("use $last", "spend"), intents)
    }

    @Test
    fun `twelve consumers scroll, the last is reachable, and just spend is always on screen`() {
        show()
        // The first is on screen; the list really is longer than the sheet.
        compose.onNodeWithTag("tracker:consumer:${ChargeChooserFixtures.manyConsumerId(1)}").assertIsDisplayed()
        scrollToAndTapTheLastConsumerThenJustSpend()
    }

    /**
     * At 240 dp and font scale 2.0 even a one-line title leaves the list under one row, so the
     * sheet scrolls its whole body (closing review L): the first consumer is on screen before any
     * scrolling, and every consumer and *Just spend a charge* are reachable by scrolling.
     */
    @Test
    fun `at double the system font scale everything is reachable and the first consumer is visible`() {
        RuntimeEnvironment.setFontScale(2.0f)
        show()
        assertEquals("the system font scale must actually be in effect", 2.0f, fontScale)
        compose.onNodeWithTag("tracker:consumer:${ChargeChooserFixtures.manyConsumerId(1)}").assertIsDisplayed()
        scrollToAndTapTheLastConsumerThenJustSpend(footerFixed = false)
    }

    /**
     * The extreme the closing review found: 240 dp tall, system font scale 2.0 and a resource name
     * long enough to wrap. Even with the title capped at two lines the fixed footer cannot fit
     * beside one row here, so the sheet falls back to scrolling its whole body: the first consumer
     * is on screen BEFORE any scrolling, and *Just spend a charge* is reached by scrolling and is
     * then on screen and tappable — nothing is clipped.
     */
    @Test
    fun `at double font scale with a long resource name a consumer is visible and nothing is clipped`() {
        RuntimeEnvironment.setFontScale(2.0f)
        show(title = LONG_TITLE)
        assertEquals(2.0f, fontScale)

        compose.onNodeWithTag("tracker:consumer:${ChargeChooserFixtures.manyConsumerId(1)}").assertIsDisplayed()
        compose.onNodeWithTag("tracker:consumer:spend").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("spend"), intents)
        // The full name is still spoken, however many lines it draws in.
        compose.onNode(isHeading()).assertContentDescriptionEquals(LONG_TITLE)
    }

    private companion object {
        const val LONG_TITLE =
            "Charges of a Remarkably Long Class Resource Name That Wraps Well Past Two Lines On A Phone"
    }
}
