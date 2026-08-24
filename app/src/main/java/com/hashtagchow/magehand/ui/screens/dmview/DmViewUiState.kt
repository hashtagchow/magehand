package com.hashtagchow.magehand.ui.screens.dmview

import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState

/**
 * The DM dashboard's whole rendered state (docs/design/14-large-screen-arc.md decisions 12, 14,
 * 17 and 18).
 *
 * @param cards one per resolved member, in the live list's order (see [resolveDmMembers]). Never
 *   more than [DM_VIEW_MAX_MEMBERS].
 * @param connection the account's DDP state, shown once for the whole screen rather than once per
 *   card. All six cards ride one connection (decision 17), so six copies of the same dot would be
 *   six ways to say one thing — and would take the space the cards need.
 * @param editingEnabled decision 14's toggle. **Not persisted, and false on every entry** — see
 *   [DmViewUiState.showsEditingBanner] for what that costs and why it is the right trade.
 * @param canEditAnyCard whether *any* card on this table is editable by this account
 *   (decision 18's client-computed capability). Drives whether the toggle renders at all: a DM
 *   who is a reader on all six sheets has nothing the toggle could switch on, and a control that
 *   provably does nothing is worse than none.
 * @param error a message worth showing above the grid — today only decision 18's refusal. The
 *   grid keeps rendering underneath it, matching the character list's non-fatal error posture.
 */
data class DmViewUiState(
    val cards: List<DmCardUiState> = emptyList(),
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val editingEnabled: Boolean = false,
    val canEditAnyCard: Boolean = false,
    val error: String? = null,
) {
    /**
     * Whether the screen is still assembling itself.
     *
     * The cards list is empty only before the members resolve — after that a card exists per
     * member, each with its own [DmCardAvailability]. So "no cards" is a screen-level wait and
     * "a card that is loading" is a per-character one, and the two must not be drawn the same
     * way: a spinner over a grid that already has five good cards on it would hide the table to
     * report on one member.
     */
    val isLoading: Boolean get() = cards.isEmpty()

    /**
     * Decision 14's *"unmistakable persistent banner while ON"*.
     *
     * A field rather than "the toggle happens to be on", for the reason 04's read-only note is a
     * field: the banner is the **only** thing standing between a DM and an accidental write to
     * somebody else's character sheet, and it must not become derivable-by-coincidence from a
     * control's state. If a future release adds a second way to enable editing, this is the one
     * place that has to learn about it, and a test says so.
     *
     * ### What "not persisted" costs, stated rather than hidden
     *
     * Decision 14 is explicit: *"default OFF every time the view opens (per-session, not
     * persisted: turning it on is a deliberate act each session)"*. The cost is real — a DM who
     * runs the whole session in edit mode taps the toggle again after every backgrounding that
     * clears the view model. That is the trade the decision makes on purpose: the failure it
     * prevents is a dashboard that silently opens *writable* onto five players' sheets because
     * of something the DM did three weeks ago.
     */
    val showsEditingBanner: Boolean get() = editingEnabled

    /**
     * Whether the top bar draws the toggle at all (decision 18's capability gate).
     *
     * Absent rather than disabled, which is decision 14's own posture for the case where the
     * server refuses non-owner writes entirely: *"the toggle ships hidden behind the capability
     * (absent, not present-and-broken)"*. The same argument applies one level down, per table: a
     * DM with no writable sheet on the dashboard is in exactly that situation.
     */
    val showsEditingToggle: Boolean get() = canEditAnyCard

    /**
     * How many cards would actually gain controls if the toggle went on.
     *
     * The toggle's spoken state needs this — "Enable editing, off; 3 of 6 characters can be
     * edited" is a sentence that tells a TalkBack user what the control will do, where "Enable
     * editing, off" alone tells them only that something is off.
     */
    val editableCardCount: Int get() = cards.count { it.couldBeEdited }
}

/**
 * The DM-view entry's multi-select sheet, on the character list (decisions 11 and 16).
 *
 * ### Why the picker lives on the list rather than inside the dashboard
 *
 * Because the set has to be known *before* the subscriptions are opened. Decision 17's binding
 * rule is *"subscribe the set ONCE on entry"* and *"never tear-down/re-subscribe on pane or card
 * interactions"* — a picker inside the dashboard would make changing the table a tear-down and a
 * fresh burst against a rate budget the whole table shares. Choosing on the list means the
 * dashboard opens knowing its members and never changes them; changing the table is a Back and a
 * re-entry, which is one deliberate act rather than an incidental one.
 *
 * @param candidates every server character the account can see, in the list's own order. Local
 *   characters are absent — they have no subscription to be live on (see [canOfferDmView]).
 * @param selected what is ticked right now. Seeded from the stored table so a DM who ran the same
 *   party last session confirms rather than re-picks.
 */
data class DmPickerState(
    val candidates: List<CharacterSummary> = emptyList(),
    val selected: Set<String> = emptySet(),
) {
    /** Decision 16's minimum of two, as the confirm button's enabled state. */
    val canConfirm: Boolean get() = canOpenDmView(selected)

    /**
     * How many more may be ticked. Zero renders as the sheet's "6 is the maximum" line rather
     * than as every unticked row going disabled — see [toggleDmMember] for why.
     */
    val remaining: Int get() = (DM_VIEW_MAX_MEMBERS - selected.size).coerceAtLeast(0)

    /** True when [remaining] is zero, so the sheet can say why further taps do nothing. */
    val isFull: Boolean get() = remaining == 0
}
