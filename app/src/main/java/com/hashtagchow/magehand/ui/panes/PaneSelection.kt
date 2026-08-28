package com.hashtagchow.magehand.ui.panes

import com.hashtagchow.magehand.core.data.settings.PaneLayoutEntry
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
     * The phone path: a `PrimaryTabRow` over [tabs], one of which is [selected].
     *
     * Carries the selected tab rather than deriving it, so that the *pane* branch below provably
     * cannot change it — see [characterHomeChrome].
     *
     * ### Why the tab *list* is here too, as of FR-27
     *
     * It used to be the screen's own `serverHomeTabs(hasActions)`, iterated directly. FR-27
     * decision 1 makes the tab row's order a per-character preference driven by the same stored
     * list the panes come from, so the order is now something [characterHomeChrome] computes and
     * the screen renders — and putting it in the branch that draws it is what stops a screen
     * accidentally iterating the *unordered* list beside it. The selection indicator is indexed
     * into this list for the same reason it always was: `ordinal` and "position in the drawn row"
     * are two different numbers, and since FR-26 they genuinely differ.
     */
    data class Tabs<out T>(val tabs: List<T>, val selected: T) : CharacterHomeChrome<T>

    /**
     * The EXPANDED-width path: [panes] rendered as equal-weight columns, in the player's order.
     *
     * A `List` because this is the point at which the order becomes real. Before FR-27 that order
     * was [PaneSurface]'s ordinals and nothing else; now it is the character's stored arrangement
     * resolved against those ordinals as the default — but it is still never *selection* order,
     * and it is still produced only by [resolvePaneLayout], which is the one place that can be
     * wrong about it.
     *
     * Never empty: decision 6's minimum of one pane is established in [resolvePaneLayout] rather
     * than trusted from the store, so a corrupted or future-versioned `pane_layout` string cannot
     * render a character screen with nothing on it.
     */
    data class Panes(val panes: List<PaneSurface>) : CharacterHomeChrome<Nothing>
}

/**
 * The stored arrangement → **the** arrangement this screen draws: every surface the character
 * has, in the player's order, each knowing whether it is open as a pane.
 *
 * One function for both chromes, which is FR-27 decision 1's *"ONE mechanism for both surfaces"*
 * made real: the tab row draws `map { it.surface }` and the pane row draws
 * `filter { it.selected }`. Two resolvers would be two places for the order to differ, and a
 * player who arranged their tabs on a phone and found their panes in a different order on the
 * same device would be right to call that broken.
 *
 * Four rules, in one place because they interact:
 *
 *  1. **Only surfaces this character has.** A local character has no `SheetSession` (09 decision
 *     8), so `sheet` in its stored list — reachable by hand-editing a preferences file, or by a
 *     character that stopped being a server character, or by a future release that lets a local
 *     character have one — is dropped rather than rendered as an empty column. It is dropped
 *     **here and not on disk**; see [persist].
 *  2. **The player's order, defaulting to [available]'s.** [weave] puts every surface the stored
 *     list does not mention where [available] says it goes, which is FR-27 decision 3's *"missing
 *     keys append at their DEFAULT position"*. That is what makes a surface the player has never
 *     arranged — a brand-new Actions tab arriving in 1.9.0, or the whole list for a character
 *     nobody has touched — land in the designed order rather than at the end of one.
 *  3. **Selection is carried, not inferred.** A surface with no stored entry is not open: an
 *     absent key is *"no preference"*, and rule 4 turns that into decision 8's Tracker-only
 *     default rather than a screen with four columns on it.
 *  4. **Minimum one.** If nothing survives selected — no preference stored, or every selected
 *     surface unavailable — the **first surface in the resolved order** is opened. Decision 8's
 *     *"Default: Tracker only (today's behavior)"*, phrased as "the first one" rather than as
 *     `PaneSurface.TRACKER` so it stays true of a screen whose first tab is something else. Note
 *     it is the first of the *resolved* order, not of [available]: for every character who has
 *     never reordered these are the same surface, and for one who has, their own first surface is
 *     the more honest answer to "which one do we fall back to".
 *
 * The result is never empty, because [available] is never empty and rule 2 keeps all of it.
 */
fun resolvePaneLayout(
    stored: List<PaneLayoutEntry>,
    available: List<PaneSurface>,
): List<PaneLayoutEntry> {
    require(available.isNotEmpty()) { "a character screen with no surfaces cannot be drawn" }
    val ordered = weave(
        base = stored.map { it.surface }.filter { it in available },
        reference = available,
    )
    val selected = stored.filter { it.selected }.map { it.surface }.toSet()
    val resolved = ordered.map { PaneLayoutEntry(it, selected = it in selected) }
    return if (resolved.any { it.selected }) {
        resolved
    } else {
        // Rule 4, applied by position rather than by name. `mapIndexed` and not
        // `resolved.first().copy(...)` because the list is what is returned, not the entry.
        resolved.mapIndexed { index, entry -> if (index == 0) entry.copy(selected = true) else entry }
    }
}

/**
 * Decision 6's columns: [resolvePaneLayout]'s answer, narrowed to what the pane row draws.
 *
 * Kept as its own named function rather than inlined at the two call sites because "which panes
 * are open" is the question decisions 6, 7 and 9 are all phrased in terms of, and because it is
 * the value `sheetWanted` and `nextStoredPanes` are documented against.
 */
fun resolvePanes(
    stored: List<PaneLayoutEntry>,
    available: List<PaneSurface>,
): List<PaneSurface> = openPanes(resolvePaneLayout(stored, available))

/** The open panes of an already-resolved layout. Never empty — see [resolvePaneLayout] rule 4. */
fun openPanes(resolved: List<PaneLayoutEntry>): List<PaneSurface> =
    resolved.filter { it.selected }.map { it.surface }

/**
 * The screen's tabs in the player's order (FR-27 decision 1).
 *
 * A re-sort of [tabs] rather than a rebuild from [order], because the tab enums are the screen's
 * and `PaneSurfaces` exists precisely so that neither enum has to be derived from the other. The
 * two lists cover the same surfaces — `PaneSelectionTest` pins that for both values of
 * `hasActions` — so nothing lands in the `Int.MAX_VALUE` bucket in practice; it is there so that
 * a future tab without a surface would go to the end of the row instead of crashing or vanishing,
 * which is the same degradation every other rule in this file chooses.
 *
 * `sortedBy` is stable, so any such tabs keep their declared order relative to each other.
 */
fun <T> orderTabs(tabs: List<T>, order: List<PaneSurface>, surfaceOf: (T) -> PaneSurface): List<T> {
    val rank = order.withIndex().associate { (index, surface) -> surface to index }
    return tabs.sortedBy { rank[surfaceOf(it)] ?: Int.MAX_VALUE }
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
 * @param current what is on screen now, as [openPanes] resolved it — **not** the raw stored
 *   arrangement. Toggling against that would let an unavailable surface stored for this character
 *   count towards the minimum, so the last visible pane could be deselected into a blank screen.
 */
fun togglePane(current: Set<PaneSurface>, surface: PaneSurface): Set<PaneSurface> =
    if (surface in current) {
        if (current.size <= 1) current else current - surface
    } else {
        current + surface
    }

/**
 * [togglePane]'s decision, applied to the whole arrangement — what the picker's tap must persist.
 *
 * ### The bug this closes (the L1 lesson)
 *
 * [resolved] is already filtered by [resolvePaneLayout] rule 1: a stored `actions` entry is not
 * in it on a character `serverPaneSurfaces` says has none. A caller that persisted the resolved
 * value directly would be writing an arrangement with `actions` already missing — the first pane
 * tap on that screen would erase the stored preference for good, which is exactly what
 * [PaneSurfaces.serverPaneSurfaces]'s KDoc promises does **not** happen ("their preference is not
 * rewritten"). [persist] is what puts it back, with its position.
 *
 * ### Why the fallback surface gets written down
 *
 * [resolvePaneLayout] rule 4 opens the first surface when nothing stored survives — decision 8's
 * "no preference" case — and that surface is on screen without ever having been written down.
 * Because this works from [resolved] rather than from [stored], the tap that adds a second pane
 * carries the first one into the store with it, instead of computing a delta against an empty
 * arrangement and silently dropping the pane the player could plainly see when they tapped.
 *
 * @param resolved the arrangement on screen, from [resolvePaneLayout] — never the raw stored
 *   list, for the reason above and for [togglePane]'s (its minimum-of-one has to count *visible*
 *   panes, or a stored-but-unavailable surface would prop the count up and the last real pane
 *   could be deselected into a blank screen).
 * @return the arrangement to store, or an **empty list** when the gesture is refused — the
 *   no-op signal [movePane] and `InventoryLayoutPlan.move` use, unambiguous because a real
 *   arrangement always has at least one entry in it.
 */
fun nextStoredPanes(
    resolved: List<PaneLayoutEntry>,
    stored: List<PaneLayoutEntry>,
    surface: PaneSurface,
): List<PaneLayoutEntry> {
    val open = openPanes(resolved).toSet()
    // The rule itself is untouched by FR-27 (decision 2: "the picker's select/deselect behavior
    // unchanged"), so it is still asked of `togglePane` rather than restated here.
    if (togglePane(open, surface) == open) return emptyList()
    if (resolved.none { it.surface == surface }) return emptyList()
    val edited = resolved.map {
        if (it.surface == surface) it.copy(selected = !it.selected) else it
    }
    return persist(edited, stored)
}

/**
 * FR-27 decision 2's reorder gesture: move one surface [delta] places and return the whole
 * arrangement to persist.
 *
 * `InventoryLayoutPlan.move`'s contract exactly, minus the hidden-section arithmetic it does not
 * need — the order sheet lists every surface the character has, so the visible list and the
 * resolved list are the same list and a move is an ordinary shift. Returns an **empty list** for
 * a no-op: a zero delta, a surface this character does not have, or a bounce off either end of
 * the list. A bounce is not a write.
 *
 * `removeAt` then `add` rather than the swap `InventoryLayoutPlan.move` performs, and the
 * difference is deliberate: there is nothing here for a swap to step over (no hidden entries), so
 * a shift is what the arrow visibly does, and a swap would be the surprising one if a future
 * release ever gave this sheet a `delta` bigger than one.
 *
 * The **selection flags travel with the entries** and none of them changes. That is what keeps
 * decision 2's *"the picker's select/deselect behavior unchanged"* true in the presence of a
 * second gesture: a phone player who has never seen a pane can reorder their tab row all day
 * without opening a single pane on the tablet layout of the same character.
 */
fun movePane(
    resolved: List<PaneLayoutEntry>,
    stored: List<PaneLayoutEntry>,
    surface: PaneSurface,
    delta: Int,
): List<PaneLayoutEntry> {
    if (delta == 0) return emptyList()
    val from = resolved.indexOfFirst { it.surface == surface }
    if (from < 0) return emptyList()
    val to = from + delta
    if (to !in resolved.indices) return emptyList()

    val moved = resolved.toMutableList().apply { add(to, removeAt(from)) }
    return persist(moved, stored)
}

/**
 * What to write: [edited] — the surfaces this character has, in their new order — with everything
 * the store remembers about surfaces the character does **not** currently have put back where it
 * had it.
 *
 * `InventoryLayoutPlan.persist`, for its reason and for FR-27 decision 5's: a non-caster
 * reordering their tabs must not erase the `actions` their wizard-self chose, and it must not
 * move it either. [weave] against the stored order is what restores the *position*; the map merge
 * is what restores the *flag*.
 *
 * The window is narrow — you have to open the order sheet on a character whose Actions discovery
 * has not answered, which is every cold open — but the failure is silent and permanent, and the
 * player has no way to know it happened.
 */
private fun persist(
    edited: List<PaneLayoutEntry>,
    stored: List<PaneLayoutEntry>,
): List<PaneLayoutEntry> {
    val ordered = weave(base = edited.map { it.surface }, reference = stored.map { it.surface })
    // `edited` last, so a surface in both wins with its new flag and its new place.
    val bySurface = stored.associateBy { it.surface } + edited.associateBy { it.surface }
    return ordered.mapNotNull { bySurface[it] }
}

/**
 * Every surface of [reference] that is missing from [base], inserted where [reference] puts it —
 * immediately after the nearest earlier reference surface that [base] does have, or at the front
 * when there is none.
 *
 * `InventoryLayoutPlan.weave`, and the same one idea answering the same two questions: *reading*,
 * where the reference is the default order and a surface the player never arranged has to land
 * where the design puts it (FR-27 decision 3); and *writing*, where the reference is the stored
 * order and a surface this character does not have right now has to keep the place it had
 * (decision 5).
 *
 * ### Why this is a second copy and not a shared utility
 *
 * The two operate on different element types for a reason that is the whole difference between
 * the two features: an inventory key is an **opaque string** minted by the sheet, and a surface
 * is a **closed enum** the store can validate. Hoisting six lines into a generic
 * `weave(List<T>, List<T>)` that neither package owns would put the shared thing in a third
 * place, and the next reader of either call site would have to go and find out whether the other
 * caller's needs had bent it. Both copies name the other, which is the part that has to stay
 * true.
 *
 * "Nearest earlier neighbour" rather than "the same index", because the two lists are different
 * lengths and an index means nothing across them. Order matters in the loop: a run of consecutive
 * missing surfaces anchors each one off the previous, which has just been inserted.
 */
private fun weave(base: List<PaneSurface>, reference: List<PaneSurface>): List<PaneSurface> {
    val out = base.distinct().toMutableList()
    reference.forEachIndexed { index, surface ->
        if (surface in out) return@forEachIndexed
        val anchor = reference.take(index).lastOrNull { it in out }
        out.add(if (anchor == null) 0 else out.indexOf(anchor) + 1, surface)
    }
    return out
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
 * screen's `rememberSaveable`, [layout] comes from a DataStore key, and this function reads both
 * and renders one. Crossing the gate therefore cannot lose anything, because nothing is
 * *converted* on the way across — the unused half is simply not read for as long as the window
 * stays that size.
 *
 * The tempting alternative — "entering pane mode seeds the pane set from the current tab, and
 * leaving it selects the tab of the first pane" — is the design that loses state, and it loses
 * it silently: a rotation would overwrite the arrangement the player had chosen for that
 * character, and the bug report would be "my tablet layout keeps resetting".
 *
 * ### What FR-27 added, and why it did not weaken any of that
 *
 * Both branches now read [layout], because decision 1 makes the stored arrangement drive the tab
 * order as well as the pane order. That is not the conversion this function refuses: the two
 * branches read the same *order* out of one value and neither writes it, so a rotation still
 * changes only which of two independent selections is on screen. What is genuinely shared is now
 * shared, and what must stay separate — [selectedTab] versus which panes are open — still is.
 *
 * @param layout the arrangement from [resolvePaneLayout], resolved by the caller. Passed already
 *   resolved rather than as `(stored, available)` so that a screen resolves once and hands the
 *   same value to this, to the pane picker and to the order sheet — three consumers of one order
 *   is the shape that cannot disagree with itself.
 */
fun <T> characterHomeChrome(
    expandedWidth: Boolean,
    selectedTab: T,
    layout: List<PaneLayoutEntry>,
    availableTabs: List<T>,
    surfaceOf: (T) -> PaneSurface,
): CharacterHomeChrome<T> =
    if (expandedWidth) {
        CharacterHomeChrome.Panes(openPanes(layout))
    } else {
        val ordered = orderTabs(availableTabs, layout.map { it.surface }, surfaceOf)
        CharacterHomeChrome.Tabs(ordered, resolveTab(selectedTab, ordered))
    }

/**
 * [resolvePaneLayout]'s rule 1 and rule 4, for the **tab** branch (FR-26, 16 decision 1).
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
 * Falling back to `availableTabs.first()` matches [resolvePaneLayout]'s rule 4 exactly, and for
 * the same reason it is written as "the first available" rather than as a named constant. Since
 * FR-27 the caller passes the tabs **already in the player's order**, so the fallback is the
 * player's leftmost tab rather than always the Tracker — which is the same rule, applied to a
 * row the player has rearranged.
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
