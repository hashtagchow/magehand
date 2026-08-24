package com.hashtagchow.magehand.ui.panes

import com.hashtagchow.magehand.core.data.settings.PaneSurface

/**
 * FR-17's chrome decision, as data (docs/design/14-large-screen-arc.md decisions 5, 6 and 10).
 *
 * A character home screen draws **one** of these: today's tab row, or the pane picker with the
 * chosen surfaces side by side. Modelling it as a sealed type rather than as an `if` inside the
 * composable is what makes decisions 5, 6 and 10 assertable at all — `:app` has no Compose test
 * harness (see `StartDestinationNavigationTest`), so anything that only exists as a branch in a
 * `@Composable` can only be pinned by reading the source.
 *
 * @param T the screen's own tab enum. Generic because the two home screens have two of them —
 *   `CharacterHomeTab` has a Sheet constant and `LocalCharacterHomeTab` does not, which is a
 *   guarantee 09 decision 8 wants kept in the type system rather than in a `filterNot`.
 */
sealed interface CharacterHomeChrome<out T> {

    /**
     * The phone path, structurally unchanged: a `PrimaryTabRow` over one selected tab.
     *
     * Carries the tab rather than deriving it, so that the *pane* branch below provably cannot
     * change it — see [characterHomeChrome].
     */
    data class Tabs<out T>(val selected: T) : CharacterHomeChrome<T>

    /**
     * The EXPANDED-width path: [panes] rendered as equal-weight columns, in fixed display order.
     *
     * A `List` and not a `Set` because this is the point at which the order becomes real — but
     * it is [PaneSurface]'s ordinal order, never selection order, and it is produced only by
     * [resolvePanes], which is the one place that can be wrong about it.
     *
     * Never empty: decision 6's minimum of one pane is established here rather than trusted from
     * the store, so a corrupted or future-versioned `pane_layout` string cannot render a
     * character screen with nothing on it.
     */
    data class Panes(val panes: List<PaneSurface>) : CharacterHomeChrome<Nothing>
}

/**
 * Decision 6's stored set → the columns actually drawn.
 *
 * Three rules, in one place because they interact:
 *
 *  1. **Only surfaces this character has.** A local character has no `SheetSession` (09 decision
 *     8), so `sheet` in its stored set — reachable by hand-editing a preferences file, or by a
 *     character that stopped being a server character, or by a future release that lets a local
 *     character have one — is dropped rather than rendered as an empty column.
 *  2. **Fixed display order**, taken from [available] (which is itself the screen's tab order).
 *     The stored value is a `Set` precisely so there is no selection order here to prefer.
 *  3. **Minimum one.** An empty result — no preference stored, every stored surface unavailable
 *     — falls back to the first available surface, which is decision 8's *"Default: Tracker
 *     only (today's behavior)"*. Expressed as `available.first()` rather than as
 *     `PaneSurface.TRACKER` so it stays true of any screen whose first tab is something else,
 *     and because "the default is the first tab" is the actual rule.
 */
fun resolvePanes(
    stored: Set<PaneSurface>,
    available: List<PaneSurface>,
): List<PaneSurface> {
    require(available.isNotEmpty()) { "a character screen with no surfaces cannot be drawn" }
    val chosen = available.filter { it in stored }
    return chosen.ifEmpty { listOf(available.first()) }
}

/**
 * Decision 6's picker gesture: add a surface, or take one away.
 *
 * **Deselecting the last pane is a no-op**, which is where the minimum of one is enforced —
 * at the gesture, not in the store (see `PaneLayoutStore.setPanes`) and not only in
 * [resolvePanes]. Enforcing it here is what lets the picker render the last remaining pane's
 * control as *checked and still tappable*: the tap simply does nothing, rather than the control
 * going disabled and looking broken, or the tap succeeding and leaving a blank screen.
 *
 * Returns the input unchanged when the gesture is a no-op, so a caller can skip a pointless
 * write — the same contract `InventoryLayoutPlan.move` uses for a bounce off the end of a list.
 *
 * @param current what is on screen now, as [resolvePanes] resolved it — **not** the raw stored
 *   set. Toggling against the raw set would let an unavailable surface stored for this character
 *   count towards the minimum, so the last visible pane could be deselected into a blank screen.
 */
fun togglePane(current: Set<PaneSurface>, surface: PaneSurface): Set<PaneSurface> =
    if (surface in current) {
        if (current.size <= 1) current else current - surface
    } else {
        current + surface
    }

/**
 * Decisions 5 and 10: which chrome a character screen draws, from the two pieces of state that
 * decide it.
 *
 * ### Why this is a function of *both* states rather than a conversion between them
 *
 * Decision 10 requires that crossing the width gate — rotating, unfolding, dragging a
 * multi-window divider — preserves **both** the tab selection and the pane set, in both
 * directions. The mechanism is that neither is derived from the other: [selectedTab] is the
 * screen's `rememberSaveable`, [storedPanes] is a DataStore key, and this function reads both
 * and renders one. Crossing the gate therefore cannot lose anything, because nothing is
 * *converted* on the way across — the unused half is simply not read for as long as the window
 * stays that size.
 *
 * The tempting alternative — "entering pane mode seeds the pane set from the current tab, and
 * leaving it selects the tab of the first pane" — is the design that loses state, and it loses
 * it silently: a rotation would overwrite the arrangement the player had chosen for that
 * character, and the bug report would be "my tablet layout keeps resetting".
 */
fun <T> characterHomeChrome(
    expandedWidth: Boolean,
    selectedTab: T,
    storedPanes: Set<PaneSurface>,
    available: List<PaneSurface>,
): CharacterHomeChrome<T> =
    if (expandedWidth) {
        CharacterHomeChrome.Panes(resolvePanes(storedPanes, available))
    } else {
        CharacterHomeChrome.Tabs(selectedTab)
    }

/**
 * Decision 9: whether the Sheet's WebView should exist right now.
 *
 * The two paths differ, and the difference is the decision:
 *
 *  - **Tabs.** [sheetEverOpened] — sticky-true once the player has visited the Sheet tab. The
 *     instance then outlives every tab switch, because a Meteor boot is ~2 s and a tab switch is
 *     a glance (04 §4, and `rememberSheetWebViewState`'s KDoc). Unchanged from before FR-17.
 *  - **Panes.** Membership of the pane set, checked live: *"its lifecycle is pinned to selection
 *     (deselected = WebView destroyed)"*. Deselecting a pane is a deliberate act on a control
 *     the player had to open, not a glance, and a WebView kept alive for a column that is not on
 *     screen is ~100 MB of renderer holding a live socket for nothing.
 *
 * ### The one honest cost, stated rather than hidden
 *
 * Crossing the gate while the Sheet is not in the pane set destroys the WebView, so rotating
 * back to a phone layout with the Sheet tab selected reloads the page. The *selection* survives
 * — which is what decision 10 promises — but the scroll position and the booted Meteor client do
 * not. The alternative would be keeping a renderer alive across a transition specifically so the
 * player does not notice a reload they may never trigger, which is the wrong trade on the memory
 * budget a tablet running three panes has.
 */
fun sheetWanted(chrome: CharacterHomeChrome<*>, sheetEverOpened: Boolean): Boolean =
    when (chrome) {
        is CharacterHomeChrome.Tabs -> sheetEverOpened
        is CharacterHomeChrome.Panes -> PaneSurface.SHEET in chrome.panes
    }
