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
 * [togglePane]'s decision, replayed against the character's real, unfiltered [stored] set
 * instead of against [current] — what a caller must persist, and the reason it is a second
 * function rather than a caller writing [togglePane]'s own return value.
 *
 * ### The bug this closes
 *
 * [current] is already filtered by [resolvePanes] rule 1: a stored `actions` token does not
 * appear in it on a character `serverPaneSurfaces` says has none. A caller that persisted
 * `togglePane(current, surface)` directly was therefore writing a value that started from a
 * set with `actions` already missing — the first pane tap on that screen erased the stored
 * preference for good, which is exactly what [PaneSurfaces.serverPaneSurfaces]'s KDoc promises
 * does **not** happen ("their preference is not rewritten").
 *
 * ### Why the baseline is `stored + current`, not `stored` alone
 *
 * [current] is not always a subset of [stored]: [resolvePanes] rule 3 falls back to a single
 * default surface (`available.first()`) when nothing stored survives the filter — decision 8's
 * "no preference" case — and that fallback surface is on screen without ever having been
 * written down. Toggling against bare [stored] there would compute a delta relative to an EMPTY
 * set and persist only the tapped surface, silently dropping the default surface the player
 * could plainly see when they tapped. Folding [current] into the baseline covers both
 * provenances at once — a stored surface [current] does not show (kept, filtered), and a shown
 * surface [stored] never recorded (adopted, defaulted) — with the SAME union.
 *
 * ### The fix
 *
 * [surface] is added to or removed from `stored + current` using the SAME direction [togglePane]
 * chose for [current] — added if it was not on screen, removed if it was. A surface [stored]
 * carries that [current] never did (because it is unavailable right now) is never named by the
 * gesture and is therefore never touched.
 *
 * @return `null` when [togglePane] refuses the gesture (its minimum-of-one), so the caller
 *   skips the write exactly as it would have against the old, single-set contract.
 */
fun nextStoredPanes(
    current: Set<PaneSurface>,
    stored: Set<PaneSurface>,
    surface: PaneSurface,
): Set<PaneSurface>? {
    if (togglePane(current, surface) == current) return null
    val baseline = stored + current
    return if (surface in current) baseline - surface else baseline + surface
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
    availableTabs: List<T>,
): CharacterHomeChrome<T> =
    if (expandedWidth) {
        CharacterHomeChrome.Panes(resolvePanes(storedPanes, available))
    } else {
        CharacterHomeChrome.Tabs(resolveTab(selectedTab, availableTabs))
    }

/**
 * [resolvePanes]' rule 1 and rule 3, for the **tab** branch (FR-26, 16 decision 1).
 *
 * ### Why the tab side needed this and never did before
 *
 * Until FR-26 every tab a screen declared, it drew — so a `rememberSaveable` selection could not
 * name a tab that was not there. Actions is discovery-gated, which opens three ways for exactly
 * that to happen, none of them exotic:
 *
 *  - the player is on the Actions tab of a spellcaster, backs out and opens a fighter;
 *  - a live edit removes the last spell from the open sheet;
 *  - the screen restores from process death with `Actions` saved, before the board has loaded —
 *    which is *every* cold restore onto that tab, because `hasActions` is false until discovery
 *    answers.
 *
 * Without this the selection would point at a tab the row does not draw: no tab appears
 * selected, and the content `when` renders a surface the player cannot navigate away from
 * because its tab is gone.
 *
 * Falling back to `availableTabs.first()` — the Tracker — matches `resolvePanes`' rule 3 exactly,
 * and for the same reason it is written as "the first available" rather than as a named constant.
 *
 * ### It does not write anything back
 *
 * The caller's `rememberSaveable` keeps holding `Actions`. That is deliberate and mirrors the
 * pane side's *"a stored `actions` token is filtered, not rewritten"*: the third case above is a
 * **transient** — discovery answers a frame later and the tab returns — and a resolver that
 * corrected the saved value would turn every cold restore onto the Actions tab into a silent
 * bounce to the Tracker. Resolving on read costs nothing and is reversible; writing on read is
 * neither.
 */
fun <T> resolveTab(selected: T, availableTabs: List<T>): T {
    require(availableTabs.isNotEmpty()) { "a character screen with no tabs cannot be drawn" }
    return if (selected in availableTabs) selected else availableTabs.first()
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
