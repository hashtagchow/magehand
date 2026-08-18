package com.hashtagchow.magehand.ui.screens.characterlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.characters.CharacterListSource
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.LocalCharacter

/**
 * The character list with **zero accounts** (docs/design/09-local-characters.md decision 3).
 *
 * *"Local characters appear below the signed-in account's characters (or alone when signed
 * out — which is the whole point: the list screen must render with zero accounts)."*
 *
 * Every rule here turns on `hasAccount` rather than on the list being empty, and the reason is
 * one specific trap: with no account nothing subscribes, so `source` never leaves `NONE`. The
 * pre-FR-5 `isLoadingFirstPage` would therefore have been true forever and a signed-out user
 * would have looked at a spinner instead of their characters — with `isEmpty` unreachable, so
 * not even the empty state could have rescued them.
 *
 * `hasAccount` is **three-valued**, and the "unresolved" group below is why. Reading the
 * account is a disk read; with a two-valued flag its seed was indistinguishable from "signed
 * out", so every rule here answered the signed-out question about a user who turned out to be
 * signed in. What that looked like was "You have no characters" plus the local-mode FAB, on
 * every signed-in cold start, for as long as Room took — longest on the first launch after an
 * upgrade, where the same read runs `MIGRATION_2_3`.
 */
class CharacterListUiStateTest {

    private fun local(id: String, name: String, level: Int?) = LocalCharacter(
        id = id,
        name = name,
        level = level,
        abilities = AbilityScores(),
        maxHp = 10,
        currentHp = 10,
        armorClass = 12,
        createdAt = 0,
        updatedAt = 0,
    )

    private val account = CharacterSummary(
        creatureId = "FakeCreature23456",
        name = "Elowen Brightmantle",
        alignment = "Chaotic Good",
        gender = "Female",
        picture = null,
        owner = "me",
        isOwnedByMe = true,
    )

    // --- unresolved: we have not looked yet ---------------------------------

    @Test
    fun `the seed is unresolved, not signed out`() {
        // `CharacterListViewModel` seeds `stateIn` with exactly this value, so what the very
        // first frame renders is decided here and nowhere else.
        assertNull(CharacterListUiState().hasAccount)
    }

    @Test
    fun `an unresolved account is a spinner, never the empty state`() {
        val state = CharacterListUiState()

        // The whole fix: "we have not read the account yet" and "there is no account" are
        // different questions, and only the second one has an empty state as its answer.
        assertTrue(state.isLoadingFirstPage)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `an unresolved account offers no create affordance to mis-tap`() {
        val state = CharacterListUiState()

        // The FAB means two different things either side of the answer, so before the answer
        // it means nothing — and a wrong FAB is not merely cosmetic, it navigates.
        assertFalse(state.showsCreateAffordance)
        // Nor a connection strip about a socket nothing has decided to open yet.
        assertFalse(state.showsConnection)
        assertFalse(state.canRefresh)
    }

    // --- signed out ---------------------------------------------------------

    @Test
    fun `a signed-out user with one local character sees it, not a spinner`() {
        val state = CharacterListUiState(
            hasAccount = false,
            source = CharacterListSource.NONE,
            localCharacters = listOf(local("l1", "Sabriel", 5).toCardState()),
        )

        assertFalse(state.isLoadingFirstPage)
        assertFalse(state.isEmpty)
        assertEquals(listOf("Sabriel"), state.localCharacters.map { it.name })
    }

    @Test
    fun `a resolved signed-out user with nothing sees the empty state, not a spinner`() {
        // `hasAccount = false` here means **we looked, and there is no account** — which is
        // the only reading that earns the empty state. Before `hasAccount` was three-valued
        // this test also passed for the seed, and that is precisely what it should not do:
        // it blessed the "You have no characters" flash a signed-in user saw. The unresolved
        // group above now holds the seed to the opposite rule.
        val state = CharacterListUiState(hasAccount = false, source = CharacterListSource.NONE)

        assertTrue(state.isEmpty)
        assertFalse(state.isLoadingFirstPage)
    }

    @Test
    fun `a signed-out list mentions no connection and offers no refresh`() {
        val state = CharacterListUiState(hasAccount = false)

        // There is no socket being opened, so a permanent "Connecting…" strip would be a lie,
        // and pull-to-refresh would spin against nothing.
        assertFalse(state.showsConnection)
        assertFalse(state.canRefresh)
        // …but the create affordance is exactly what 09 decision 3 says a signed-out list is.
        assertTrue(state.showsCreateAffordance)
    }

    // --- signed in ----------------------------------------------------------

    @Test
    fun `a signed-in cold start still shows the spinner it always did`() {
        // The pre-existing rule, unchanged: resolved-signed-in with no page yet is a wait.
        val state = CharacterListUiState(hasAccount = true, source = CharacterListSource.NONE)

        assertTrue(state.isLoadingFirstPage)
        assertFalse(state.isEmpty)
        assertTrue(state.showsConnection)
        assertTrue(state.canRefresh)
        assertTrue(state.showsCreateAffordance)
    }

    @Test
    fun `a signed-in account with no characters but a local one is not loading`() {
        // Local content is content: there is something on screen, so the spinner would be
        // covering rows the user can already use.
        val state = CharacterListUiState(
            hasAccount = true,
            source = CharacterListSource.NONE,
            localCharacters = listOf(local("l1", "Sabriel", 5).toCardState()),
        )

        assertFalse(state.isLoadingFirstPage)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a signed-in empty list is only empty once the subscription has said so`() {
        assertFalse(
            CharacterListUiState(hasAccount = true, source = CharacterListSource.CACHE).isEmpty,
        )
        assertTrue(
            CharacterListUiState(hasAccount = true, source = CharacterListSource.LIVE).isEmpty,
        )
    }

    @Test
    fun `account characters and local ones coexist, neither hiding the other`() {
        val state = CharacterListUiState(
            hasAccount = true,
            source = CharacterListSource.LIVE,
            characters = listOf(account),
            localCharacters = listOf(local("l1", "Sabriel", 5).toCardState()),
        )

        assertFalse(state.isEmpty)
        assertEquals(1, state.characters.size)
        assertEquals(1, state.localCharacters.size)
    }

    // --- the card -----------------------------------------------------------

    @Test
    fun `a level becomes the card's subtitle, in the server cards' style`() {
        assertEquals("Level 5", local("l1", "Sabriel", 5).toCardState().subtitle)
    }

    @Test
    fun `no level means no subtitle line, not an empty one`() {
        // Same contract as CharacterSummary.subtitle: empty means the line is absent.
        assertEquals("", local("l1", "Sabriel", null).toCardState().subtitle)
    }

    @Test
    fun `the monogram is the first letter, upper-cased`() {
        assertEquals("S", local("l1", "sabriel", 1).toCardState().monogram)
        assertEquals("S", local("l2", "  Sabriel", 1).toCardState().monogram)
    }
}
