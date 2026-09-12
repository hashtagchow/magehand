package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.hashtagchow.magehand.core.data.local.LocalActionBoard
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.NewLocalRowSpec
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-49's add sheet and local spell detail, through real composition
 * (docs/design/20-local-spells-and-attacks.md decisions 1, 5 and 6).
 *
 * ### What is here that `AddActionFormStateTest` cannot say
 *
 * That test owns the *rules* — the pre-fill, the validation, the per-kind drops — because they are
 * pure and a Compose harness would only make them slower to check. What needs composition is the
 * wiring: that a catalog tap reaches the form at all, that the filter swaps which catalog is
 * listed, that Save dispatches the spec the form built, and that decision 5's block is drawn on a
 * local spell and not drawn on one without a paragraph. Those are the joins, and a join is exactly
 * what a UiState test cannot reach.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddActionSheetRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var added: NewLocalRowSpec? = null

    private fun sheet() = compose.setMageHandContent {
        AddActionSheet(onAdd = { added = it }, onDismiss = {})
    }

    /**
     * Scrolls the Save button into view and taps it.
     *
     * The form is nine fields tall on a spell and the sheet is one `LazyColumn`, so on a 411 dp
     * phone the button is genuinely below the fold — which is a fact about the sheet rather than
     * about the test, and is why every save here goes through this rather than through a bare
     * `performClick`. A tap on a node outside the viewport is not a tap a player could make.
     */
    private fun clickSave() {
        compose.onNodeWithTag("actions:add:custom:save").performScrollTo().performClick()
    }

    // --- decision 6: the catalog is a template ------------------------------

    /**
     * **A catalog tap does not add a row.** It opens the form, pre-filled — the single most
     * re-guessable thing about this sheet, because `AddItemSheet` a tab away adds on tap.
     */
    @Test
    fun `a catalog tap opens the pre-filled form instead of adding`() {
        sheet()

        compose.onNodeWithTag("actions:add:catalog:acid-splash").performClick()

        assertNull("the tap must not have saved anything", added)
        compose.onNodeWithTag("actions:add:custom:name").assertIsDisplayed()
        compose.onNodeWithTag("actions:add:custom:castingTime").assertIsDisplayed()
        // The list is gone, which is the other half of "it opened the form".
        compose.onAllNodesWithTag("actions:add:catalog:acid-splash").assertCountEquals(0)
    }

    /** Save dispatches what the form holds — the catalog entry's own fields, unedited. */
    @Test
    fun `saving a picked spell dispatches its catalog fields`() {
        sheet()

        compose.onNodeWithTag("actions:add:catalog:acid-splash").performClick()
        clickSave()

        with(checkNotNull(added)) {
            assertEquals(LocalRowKind.SPELL, kind)
            assertEquals("Acid Splash", label)
            assertEquals(0, spellLevel)
            assertEquals("acid-splash", catalogId)
            assertEquals("60 feet", range)
        }
    }

    /**
     * An edit between the pick and the save is what gets saved — decision 6's *"every field
     * editable before save"*, end to end through the composition rather than through `copy`.
     */
    @Test
    fun `editing a pre-filled field before saving changes what is dispatched`() {
        sheet()

        compose.onNodeWithTag("actions:add:catalog:acid-splash").performClick()
        compose.onNodeWithTag("actions:add:custom:name").performTextReplacement("Brambles' Splash")
        compose.onNodeWithTag("actions:add:custom:level:3").performClick()
        clickSave()

        with(checkNotNull(added)) {
            assertEquals("Brambles' Splash", label)
            assertEquals(3, spellLevel)
            assertEquals("the provenance survives the edit", "acid-splash", catalogId)
        }
    }

    /** The filter swaps which catalog is listed — decision 6's Spell / Attack segmented choice. */
    @Test
    fun `the attack filter lists weapons instead of spells`() {
        sheet()

        compose.onNodeWithTag("actions:add:catalog:acid-splash").assertIsDisplayed()

        compose.onNodeWithTag("actions:add:filter:attack").performClick()

        compose.onAllNodesWithTag("actions:add:catalog:acid-splash").assertCountEquals(0)
        compose.onNodeWithTag("actions:add:catalog:battleaxe").assertIsDisplayed()
    }

    /** A weapon pick opens the attack form — damage and properties, and no spell scalars. */
    @Test
    fun `a weapon pick opens an attack form with no spell fields`() {
        sheet()

        compose.onNodeWithTag("actions:add:filter:attack").performClick()
        compose.onNodeWithTag("actions:add:catalog:battleaxe").performClick()

        compose.onNodeWithTag("actions:add:custom:damage").assertIsDisplayed()
        compose.onNodeWithTag("actions:add:custom:properties").assertIsDisplayed()
        compose.onAllNodesWithTag("actions:add:custom:castingTime").assertCountEquals(0)
        compose.onAllNodesWithTag("actions:add:custom:level:0").assertCountEquals(0)

        clickSave()
        assertEquals(LocalRowKind.ATTACK, checkNotNull(added).kind)
    }

    /** The search narrows the list without leaving it. */
    @Test
    fun `searching filters the catalog`() {
        sheet()

        compose.onNodeWithTag("actions:add:search").performTextInput("fireball")

        compose.onNodeWithTag("actions:add:catalog:fireball").assertIsDisplayed()
        compose.onAllNodesWithTag("actions:add:catalog:acid-splash").assertCountEquals(0)
    }

    /** A query nothing matches says so rather than showing a blank sheet. */
    @Test
    fun `a query with no matches says so`() {
        sheet()

        compose.onNodeWithTag("actions:add:search").performTextInput("zzzz")

        compose.onNodeWithTag("actions:add:none").assertIsDisplayed()
    }

    /**
     * The **custom** path: an empty form, and a save that is refused until the level is chosen.
     *
     * The refusal is asserted by what did *not* happen — nothing dispatched — because decision 6's
     * validation posture is "turn the messages on and stay put", and a sheet that closed on an
     * invalid save would lose what the player typed.
     */
    @Test
    fun `the custom form refuses to save a spell with no level and then saves one`() {
        sheet()

        compose.onNodeWithTag("actions:add:mode").performClick()
        compose.onNodeWithTag("actions:add:custom:name").performTextInput("Homebrew Bolt")
        clickSave()

        assertNull("no level chosen, so nothing is saved", added)
        compose.onNodeWithTag("actions:add:custom:name").assertIsDisplayed()

        compose.onNodeWithTag("actions:add:custom:level:1").performClick()
        clickSave()

        with(checkNotNull(added)) {
            assertEquals("Homebrew Bolt", label)
            assertEquals(1, spellLevel)
            assertNull("nothing came from a catalog", catalogId)
        }
    }

    // --- decision 1: the empty state's Add ----------------------------------

    /**
     * The empty state offers the Add on a local character and **not** on a DiceCloud one — the
     * nullability of `ActionsScreen.onAdd` being the gate rather than a rule somebody remembers.
     */
    @Test
    fun `the empty actions tab offers an Add only where there is something to add to`() {
        var opened = false
        compose.setMageHandContent {
            ActionsScreen(
                state = toActionsUiState(creatureId = "local-1", board = LocalActionBoard.build(emptyList())),
                onUse = { _, _, _ -> },
                onAdd = { opened = true },
            )
        }

        compose.onNodeWithTag("actions:add:empty").performClick()
        assertTrue(opened)
    }

    @Test
    fun `a DiceCloud character's empty actions tab has no Add`() {
        compose.setMageHandContent {
            ActionsScreen(
                state = toActionsUiState(creatureId = "c-1", board = LocalActionBoard.build(emptyList())),
                onUse = { _, _, _ -> },
            )
        }

        compose.onNodeWithTag("actions:empty").assertIsDisplayed()
        compose.onAllNodesWithTag("actions:add:empty").assertCountEquals(0)
    }

    // --- decision 5: the upcast block ---------------------------------------

    /**
     * The heading and the paragraph are drawn on a local spell that upcasts.
     *
     * Through the **real** `LocalActionBoard` rather than a hand-built `SpellEntry`, so this is a
     * statement about what a row produces rather than about what a fixture claims.
     */
    @Test
    fun `a local spell's detail sheet draws the higher-level block`() {
        compose.setMageHandContent {
            ActionsScreen(
                state = toActionsUiState(
                    creatureId = "local-1",
                    board = LocalActionBoard.build(listOf(fireball)),
                    spellSlots = listOf(slot),
                    canWrite = true,
                    usesAreUndoable = true,
                ),
                onUse = { _, _, _ -> },
                onAdd = {},
            )
        }

        compose.onNodeWithTag("actions:spell:fireball").performClick()

        compose.onNodeWithTag("actions:detail:higher-levels").assertIsDisplayed()
        // 20 decision 9: known, not prepared — so the Cast button exists at all.
        compose.onNodeWithTag("actions:detail:use").assertIsDisplayed()
        compose.onAllNodesWithTag("actions:detail:unusable").assertCountEquals(0)
    }

    /** …and not drawn on one without a paragraph. A heading over nothing is a claim. */
    @Test
    fun `a local spell with no upcast paragraph draws no heading`() {
        compose.setMageHandContent {
            ActionsScreen(
                state = toActionsUiState(
                    creatureId = "local-1",
                    board = LocalActionBoard.build(listOf(fireball.copy(higherLevels = null))),
                    canWrite = true,
                ),
                onUse = { _, _, _ -> },
                onAdd = {},
            )
        }

        compose.onNodeWithTag("actions:spell:fireball").performClick()

        compose.onAllNodesWithTag("actions:detail:higher-levels").assertCountEquals(0)
    }

    private companion object {
        val fireball = LocalTrackerRow(
            id = "fireball",
            characterId = "local-1",
            kind = LocalRowKind.SPELL,
            label = "Fireball",
            total = 0,
            current = 0,
            reset = null,
            sortIndex = 0,
            description = "A bright streak flashes from your pointing finger.",
            spellLevel = 3,
            higherLevels = "The damage increases by 1d6 for each slot level above 3rd.",
            castingTime = "1 action",
            range = "150 feet",
        )

        val slot = TrackedResource(
            propertyId = "slot-3",
            kind = TrackerKind.SPELL_SLOT,
            name = "3 Level",
            value = 2,
            total = 3,
            spellSlotLevel = 3,
        )
    }
}
