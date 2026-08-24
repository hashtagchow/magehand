package com.hashtagchow.magehand.ui.screens.dmview

import com.hashtagchow.magehand.core.model.CharacterSummary

/**
 * FR-19's entry and membership rules (docs/design/14-large-screen-arc.md decisions 11, 12 and
 * 16), as pure functions.
 *
 * ### Why these are functions with a test rather than `if`s in a composable
 *
 * `PaneSelection.kt`'s argument, unchanged: `:app` has no Compose test harness (see
 * `StartDestinationNavigationTest`), so a rule that lives only inside a `@Composable` can be
 * checked in exactly two ways — on a device, once, by a human, or by reading the source. Every
 * rule below has a consequence a human would not reliably notice: a seventh subscription is
 * invisible until the table's *shared* rate budget runs out (decision 17), and a dashboard that
 * silently forgot a member looks like a DM who mis-tapped.
 */

/**
 * Decision 16's minimum: *"manual multi-select from the visible server characters (min 2, max
 * 6)"*.
 *
 * Two, because one character on a dashboard is the character screen with less on it — the whole
 * proposition of this view is seeing a party at once, and a one-card grid would be a worse way
 * to reach a screen the list already opens with one tap.
 */
const val DM_VIEW_MIN_MEMBERS: Int = 2

/**
 * Decision 16's maximum, and it is a **budget** rather than a layout preference.
 *
 * Decision 17 measured the cost: ~100 KB of variables per creature and one `singleCharacter`
 * subscription each, against a subscription rate limit of 50 per 10 s that the server applies
 * **globally, across every user at the table**. Six is ~12% of that bucket in one burst, which
 * the probe found viable; the number is the point at which "always-live-all-N" stops being a
 * measured claim and becomes a hope.
 *
 * It is enforced in three places on purpose — the picker gesture ([toggleDmMember]), the
 * resolution of what was stored ([resolveDmMembers]), and the view model that opens the
 * sessions — because each of the three is separately reachable: a tap, a hand-edited
 * preferences file, and a future caller.
 */
const val DM_VIEW_MAX_MEMBERS: Int = 6

/**
 * Whether the character list offers the DM view at all (decisions 11 and 12).
 *
 * Two conditions, and neither is negotiable in v1:
 *
 *  1. **At least two server characters are visible.** Decision 16 settled that membership is a
 *     manual multi-select over the `characterList` rows, because no party publication exists
 *     live — so "can this account see a party?" is exactly "does it see two or more creatures?".
 *     Local characters are deliberately not counted: they have no subscription to be live on,
 *     which is the entire content of a DM card.
 *  2. **The window is EXPANDED-width.** Decision 12: *"on smaller widths the entry is absent in
 *     v1"*. Absent rather than disabled — a grid of six condensed cards on a phone is six cards
 *     nobody can read, and a greyed-out button teaches the user that the app has a feature they
 *     cannot have rather than that their window is small.
 *
 * The width half is passed in rather than read here so this stays a pure function: the value
 * comes from `LocalExpandedWidth`, which is a composition local FR-17 established as the app's
 * one width question (see `WindowSizeGate`).
 */
fun canOfferDmView(serverCharacterCount: Int, expandedWidth: Boolean): Boolean =
    expandedWidth && serverCharacterCount >= DM_VIEW_MIN_MEMBERS

/**
 * The stored membership → the creatures the dashboard actually opens (decision 16: *"unknown ids
 * dropped against the live list"*).
 *
 * Three rules, in one place because they interact:
 *
 *  1. **Only creatures this account can currently see.** `DmViewStore` keeps an id it cannot
 *     resolve — creature ids are opaque and the store has no basis for calling one wrong (see
 *     `DmViewCodec`) — so the dropping has to happen here, where the live list is. An id whose
 *     share was revoked, or whose character was deleted, would otherwise open a subscription
 *     that readies with zero documents and render decision 19's "Not available" card forever.
 *  2. **The live list's order**, not the stored set's. `characterList` is name-sorted
 *     (`DefaultCharacterListRepository`), so the grid reads alphabetically and does not reshuffle
 *     when the server replays documents in a different order after a reconnect. The stored value
 *     is a `Set` precisely so there is no tapping order here to prefer.
 *  3. **Capped at [DM_VIEW_MAX_MEMBERS].** A file holding seven ids — hand-edited, or written by
 *     a later build with a bigger cap — must not open seven subscriptions out of a budget the
 *     whole table shares. The extras are dropped from the *end of the live order*, which is
 *     arbitrary but stable: the same seven ids always yield the same six.
 *
 * Returns a `List` because this is the point at which the order becomes real, exactly as
 * `resolvePanes` does.
 */
fun resolveDmMembers(stored: Set<String>, live: List<CharacterSummary>): List<String> =
    live.asSequence()
        .map { it.creatureId }
        .filter { it in stored }
        .take(DM_VIEW_MAX_MEMBERS)
        .toList()

/**
 * The picker gesture: add a character to the table, or take one off.
 *
 * **Ticking a seventh is a no-op**, which is where [DM_VIEW_MAX_MEMBERS] is enforced at the
 * gesture rather than only at resolution. Enforcing it here is what lets the picker render the
 * over-cap rows as *unchecked and still tappable*: the tap does nothing, and the sheet says how
 * many are left, rather than every unticked row going disabled the moment the sixth is chosen —
 * which reads as the list having broken.
 *
 * Unticking is never refused, even below [DM_VIEW_MIN_MEMBERS]. The minimum is a rule about
 * *opening* the dashboard, not about holding a selection: a DM clearing the sheet to start again
 * would otherwise find the last two rows stuck on. [canOpenDmView] is where the minimum lives.
 *
 * Returns the input unchanged when the gesture is a no-op, so a caller can skip a pointless
 * write — the contract `togglePane` and `InventoryLayoutPlan.move` both use.
 */
fun toggleDmMember(current: Set<String>, creatureId: String): Set<String> =
    if (creatureId in current) {
        current - creatureId
    } else {
        if (current.size >= DM_VIEW_MAX_MEMBERS) current else current + creatureId
    }

/**
 * Whether the picker's confirm is live — decision 16's minimum of two, and the only place it is
 * stated.
 *
 * Deliberately not also asserted against the maximum: [toggleDmMember] cannot produce a set over
 * the cap, and re-checking it here would mean a *confirm button* that goes dead for a reason the
 * DM cannot see. If a set over the cap ever did reach here, [resolveDmMembers] trims it, which is
 * the recoverable failure rather than the stuck one.
 */
fun canOpenDmView(selected: Set<String>): Boolean = selected.size >= DM_VIEW_MIN_MEMBERS

/**
 * Decision 12's *"adaptive grid columns on the expanded window"*, as arithmetic over the width
 * the grid has to lay out in.
 *
 * ### Why a function of width and not `GridCells.Adaptive`
 *
 * `Adaptive(minSize)` would do this inside the composable and could not be asserted at all — and
 * the failure it hides is the one that matters on this screen. A condensed card carries an HP
 * bar, a row of slot pips, condition chips and a summary line; below roughly [MIN_CARD_WIDTH_DP]
 * the pips wrap and the card stops being glanceable, which is the entire proposition of the
 * dashboard. Making the threshold a named constant with a test is what stops it drifting into a
 * magic number in a layout call.
 *
 * ### The bounds
 *
 * At least one column, because a grid with none renders nothing — the same "minimum one"
 * `resolvePanes` establishes, for the same reason. At most [DM_VIEW_MAX_MEMBERS] columns, because
 * more columns than there can ever be cards would leave the last row stretched across a gap on a
 * very wide desktop window; the cards keep their size and the grid stays left-packed instead.
 *
 * This is called *below* the width gate — `canOfferDmView` has already established EXPANDED — so
 * the one-column answer is a freeform window being dragged narrow mid-session, not a phone.
 *
 * @param availableWidthDp the width the grid itself gets, in **scaled** dp. FR-18's density scale
 *   therefore does move this: at 150% a 1280 dp window measures ~853 dp, and dropping a column is
 *   the correct response — the cards are physically bigger, so fewer fit.
 */
fun dmGridColumns(availableWidthDp: Int): Int =
    (availableWidthDp / MIN_CARD_WIDTH_DP).coerceIn(1, DM_VIEW_MAX_MEMBERS)

/**
 * The narrowest a condensed card may be drawn (see [dmGridColumns]).
 *
 * 320 dp is not a Material breakpoint, it is a measurement of the content: eight 48 dp pips plus
 * gaps is what `PipRowState.MAX_PIPS` already fits on a 360 dp phone, and the card adds its own
 * padding either side of that. Below it the pip row wraps, which is the moment a card stops
 * answering "how is this character doing?" at a glance.
 */
const val MIN_CARD_WIDTH_DP: Int = 320
