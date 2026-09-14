package com.hashtagchow.magehand.ui.screens.characterhome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.QuestEntry
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState

/**
 * The two chrome gates on [CharacterHomeUiState] — which tabs and panes this character has.
 *
 * ### Why these two lines needed a test of their own (1.19.0 closing pass, NEW-2)
 *
 * `hasActions` is one property forwarding one other, and that is exactly what made it invisible.
 * D2's whole behaviour — *"FR-55 costs a character rows, never the Actions tab"* — lands on it:
 * the reviewer reverted it to `actions.sections.isNotEmpty()` and **the entire `:app` suite
 * passed**, because both sides of the join were well pinned and the join itself was not.
 * `ActionBoard.hasAnyRows` has `ActionEngineTest`; `ActionsUiState.hasRows` has
 * `ActionsUiStateTest`; the wire between them had nothing.
 *
 * That is MEDIUM-4's shape one layer up, and it has the worse failure mode of the two: a one-word
 * regression here silently removes the Actions tab from exactly the character D2 was written for
 * — a full sheet whose every row is switched off — with no red anywhere and nothing on screen to
 * suggest the tab ever existed.
 *
 * Built by hand rather than through the ViewModel deliberately. The claim is about a derivation,
 * `CharacterHomeViewModelTest`'s harness is a coroutine fixture built for flow assembly, and
 * routing a one-line property through it would test the harness.
 */
class CharacterHomeUiStateTest {

    private fun state(board: ActionBoard) =
        CharacterHomeUiState(actions = toActionsUiState(creatureId = "c1", board = board))

    /** D2's headline, at the layer that decides whether the tab is drawn. */
    @Test
    fun `a sheet whose every row is switched off keeps its Actions tab`() {
        val allOff = state(ActionBoard(switchedOffRowCount = 3))

        assertTrue("the tab must survive FR-55's filter", allOff.hasActions)
        assertTrue(
            "…and the surface it opens is genuinely bare, which is the state that earns the " +
                "'Nothing is available right now' line rather than the empty-sheet one",
            allOff.actions.showsNoneAvailable,
        )
    }

    /** A character who truly names no action or spell has no tab, exactly as before FR-55. */
    @Test
    fun `a sheet with no rows at all has no Actions tab`() {
        assertFalse(state(ActionBoard()).hasActions)
    }

    /** And an ordinary board still has one. */
    @Test
    fun `a sheet with a listed row has its Actions tab`() {
        val board = ActionBoard(
            actions = listOf(ActionEntry(propertyId = "a1", name = "Dash", type = ActionType.ACTION)),
        )
        assertTrue(state(board).hasActions)
    }

    /**
     * FR-32 decision 14's gate, asserted beside its neighbour for the reason this file exists:
     * a one-line derivation that decides whether a control is on screen is worth one line of test.
     */
    @Test
    fun `the quest log entry appears only when the character has a quest`() {
        assertFalse(CharacterHomeUiState().hasQuests)
        assertTrue(
            CharacterHomeUiState(quests = listOf(QuestEntry(propertyId = "q1", title = "Find Gundren")))
                .hasQuests,
        )
    }
}
