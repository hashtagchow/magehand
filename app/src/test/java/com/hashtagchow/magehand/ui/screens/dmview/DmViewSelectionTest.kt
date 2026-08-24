package com.hashtagchow.magehand.ui.screens.dmview

import com.hashtagchow.magehand.core.model.CharacterSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-19's entry and membership rules (docs/design/14-large-screen-arc.md decisions 11, 12 and
 * 16).
 *
 * ### The defect each group is about
 *
 * - **canOfferDmView**: an entry that appears on a phone, where six condensed cards are six cards
 *   nobody can read (decision 12 fences it out in v1) — or on an account with one character,
 *   where the dashboard would be the character screen with less on it.
 * - **resolveDmMembers**: a **seventh subscription**. The cap is a budget against a rate limit
 *   the whole table shares (decision 17), and the store deliberately keeps ids it cannot resolve,
 *   so this function is the only thing between a hand-edited preferences file and a storm.
 * - **toggleDmMember**: the same cap reached by tapping, plus the half that is about feel — a
 *   refused tick has to be a no-op rather than a list that goes half-dead.
 * - **dmGridColumns**: a grid with zero columns, and cards narrow enough that the pip row wraps.
 */
class DmViewSelectionTest {

    private fun summary(id: String, name: String = id) =
        CharacterSummary(creatureId = id, name = name)

    private val party = listOf(
        summary("c1", "Alda"),
        summary("c2", "Bevan"),
        summary("c3", "Corwin"),
        summary("c4", "Dain"),
        summary("c5", "Elin"),
        summary("c6", "Fenn"),
        summary("c7", "Gale"),
    )

    // ---- the entry (decisions 11 and 12) ------------------------------------

    @Test
    fun `the entry needs both an expanded window and a party`() {
        assertTrue(canOfferDmView(serverCharacterCount = 2, expandedWidth = true))
        // Decision 12: "on smaller widths the entry is absent in v1". This is the clause that
        // keeps the whole feature off phones, and it is one boolean — worth its own assertion
        // because deleting it would look like a simplification.
        assertFalse(canOfferDmView(serverCharacterCount = 6, expandedWidth = false))
    }

    @Test
    fun `one character is not a party`() {
        // Decision 16's minimum, applied to the *entry* and not only to the picker's confirm: an
        // affordance that opens a sheet in which nothing can be confirmed is worse than none.
        assertFalse(canOfferDmView(serverCharacterCount = 1, expandedWidth = true))
        assertFalse(canOfferDmView(serverCharacterCount = 0, expandedWidth = true))
    }

    // ---- resolveDmMembers (decisions 16 and 17) -----------------------------

    @Test
    fun `an id the account can no longer see is dropped`() {
        // Decision 16: "unknown ids dropped against the live list". `DmViewStore` keeps the id —
        // creature ids are opaque and the store has no basis for calling one wrong — so this is
        // where a withdrawn share stops opening a subscription that readies with zero documents.
        val resolved = resolveDmMembers(setOf("c1", "gone", "c3"), party)

        assertEquals(listOf("c1", "c3"), resolved)
    }

    @Test
    fun `members render in the live list's order, never in the order they were ticked`() {
        // The live list is name-sorted, so the grid reads alphabetically and does not reshuffle
        // when the server replays documents in a different order after a reconnect. The stored
        // value being a Set is what makes there be no tapping order to prefer.
        val tickedBackwards = linkedSetOf("c3", "c1")

        assertEquals(listOf("c1", "c3"), resolveDmMembers(tickedBackwards, party))
    }

    @Test
    fun `a stored table over the cap opens six subscriptions and not seven`() {
        // The budget defect, and the only one here whose symptom is invisible on the device that
        // causes it: the seventh sub costs a slot out of a 50-per-10-s bucket shared with every
        // player at the table, so the failure surfaces on somebody else's phone.
        val all = party.map { it.creatureId }.toSet()

        val resolved = resolveDmMembers(all, party)

        assertEquals(DM_VIEW_MAX_MEMBERS, resolved.size)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6"), resolved)
    }

    @Test
    fun `resolving is stable, so the same stored table always opens the same six`() {
        // The trim is arbitrary (it takes the head of the live order) but it must not be
        // *arbitrary per call*: a dashboard that dropped a different member on each entry would
        // look like a bug in the store.
        val all = party.map { it.creatureId }.toSet()

        assertEquals(resolveDmMembers(all, party), resolveDmMembers(all, party))
    }

    @Test
    fun `a table whose every member has gone resolves to nothing rather than to everything`() {
        // Fails closed. The screen renders decision 16's "go back and pick again" state; the
        // alternative reading — "no filter matched, so show them all" — would open a dashboard
        // onto characters the DM never chose.
        assertEquals(emptyList<String>(), resolveDmMembers(setOf("gone", "also-gone"), party))
    }

    // ---- toggleDmMember (decision 16) ---------------------------------------

    @Test
    fun `ticking a seventh does nothing, and returns the same set`() {
        val six = party.take(DM_VIEW_MAX_MEMBERS).map { it.creatureId }.toSet()

        val next = toggleDmMember(six, "c7")

        assertEquals(six, next)
        // Identity, not just equality: the caller skips a pointless state write on a no-op, which
        // is `togglePane`'s and `InventoryLayoutPlan.move`'s contract.
        assertSame(six, next)
    }

    @Test
    fun `unticking is never refused, even below the minimum`() {
        // The minimum is a rule about *opening* the dashboard, not about holding a selection. A
        // DM clearing the sheet to start again would otherwise find the last two rows stuck on.
        val two = setOf("c1", "c2")

        assertEquals(setOf("c1"), toggleDmMember(two, "c2"))
        assertEquals(emptySet<String>(), toggleDmMember(setOf("c1"), "c1"))
    }

    @Test
    fun `unticking one makes room for another`() {
        // The pair that makes the cap a *swap* rather than a dead end — and the reason the
        // over-cap rows stay tappable rather than going disabled.
        val six = party.take(DM_VIEW_MAX_MEMBERS).map { it.creatureId }.toSet()

        val swapped = toggleDmMember(toggleDmMember(six, "c1"), "c7")

        assertEquals(DM_VIEW_MAX_MEMBERS, swapped.size)
        assertTrue("c7" in swapped)
        assertFalse("c1" in swapped)
    }

    @Test
    fun `the confirm needs two`() {
        assertFalse(canOpenDmView(emptySet()))
        assertFalse(canOpenDmView(setOf("c1")))
        assertTrue(canOpenDmView(setOf("c1", "c2")))
    }

    // ---- dmGridColumns (decision 12) ----------------------------------------

    @Test
    fun `the grid never has zero columns`() {
        // A freeform window dragged narrower than one card mid-session. Zero columns is not a
        // cramped layout, it is a screen with nothing on it — `resolvePanes`' minimum-of-one, in
        // the other dimension.
        assertEquals(1, dmGridColumns(0))
        assertEquals(1, dmGridColumns(MIN_CARD_WIDTH_DP - 1))
    }

    @Test
    fun `columns are added a whole card at a time`() {
        // Never a partial column: the threshold is a property of the card's content (a pip row
        // that must not wrap), so 1.9 cards' worth of width is one card, not two narrow ones.
        assertEquals(1, dmGridColumns(MIN_CARD_WIDTH_DP))
        assertEquals(1, dmGridColumns(MIN_CARD_WIDTH_DP * 2 - 1))
        assertEquals(2, dmGridColumns(MIN_CARD_WIDTH_DP * 2))
        assertEquals(3, dmGridColumns(MIN_CARD_WIDTH_DP * 3))
    }

    @Test
    fun `a very wide window does not spread six cards across more columns than there are cards`() {
        // On a desktop-sized window the cards keep their size and the grid stays left-packed,
        // rather than the last row being stretched across a gap.
        assertEquals(DM_VIEW_MAX_MEMBERS, dmGridColumns(MIN_CARD_WIDTH_DP * 20))
    }

    @Test
    fun `the EXPANDED breakpoint fits at least two cards`() {
        // The feature's own coherence check. `isExpandedWidth` gates on 840 dp, and a dashboard
        // that opened one column at its own minimum width would be a grid of one — which is the
        // shape decision 16's minimum of two exists to prevent.
        assertTrue(dmGridColumns(EXPANDED_LOWER_BOUND_DP) >= 2)
    }

    private companion object {
        /**
         * `WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND`, written out rather than imported.
         *
         * The `androidx.window` constant is what `isExpandedWidth` gates on, and reading it here
         * would make this test agree with the gate by construction. Writing the number means the
         * assertion is about *this* number fitting two cards — so a future breakpoint change has
         * to be reconciled with the card width by hand, which is the conversation worth having.
         */
        const val EXPANDED_LOWER_BOUND_DP = 840
    }
}
