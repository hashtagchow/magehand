package com.hashtagchow.magehand.ui.panes

import androidx.annotation.StringRes
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.navigation.LocalCharacterHomeTab

/**
 * The bridge between the app's two tab enums and the persisted [PaneSurface] vocabulary
 * (docs/design/14-large-screen-arc.md decision 6).
 *
 * ### Why a mapping and not one enum
 *
 * [PaneSurface] has to live in `:core:data` — the store persists it — and `:core:data` cannot
 * see `:app`'s navigation types. Going the other way and persisting `CharacterHomeTab` is worse
 * still: it would put a UI enum's `@StringRes` ordinals into a file on user devices, and a local
 * character's surfaces would have to be stored as a *different* enum, so the store would need
 * two codecs for one preference.
 *
 * The cost is a mapping that could drift, and it is paid down twice: both directions are
 * exhaustive `when`s over enums, so adding a constant to either side is a **compile error**
 * rather than a surface that silently never appears; and `PaneSelectionTest` pins that the three
 * declaration orders agree, because decision 6's fixed display order is only "fixed" if
 * `PaneSurface`'s ordinals and the tab rows' orders are the same list.
 */
val CharacterHomeTab.surface: PaneSurface
    get() = when (this) {
        CharacterHomeTab.Tracker -> PaneSurface.TRACKER
        CharacterHomeTab.Inventory -> PaneSurface.INVENTORY
        CharacterHomeTab.Actions -> PaneSurface.ACTIONS
        CharacterHomeTab.Sheet -> PaneSurface.SHEET
    }

/**
 * The same bridge for an on-device character, which has no Sheet (09 decision 8).
 *
 * That absence is why this is a second `when` rather than a shared one: `LocalCharacterHomeTab`
 * has no `Sheet` constant, so this function *cannot* return [PaneSurface.SHEET] — the guarantee
 * lives in the type, exactly as `LocalCharacterHomeTab`'s own KDoc asks.
 */
val LocalCharacterHomeTab.surface: PaneSurface
    get() = when (this) {
        LocalCharacterHomeTab.Tracker -> PaneSurface.TRACKER
        LocalCharacterHomeTab.Inventory -> PaneSurface.INVENTORY
        LocalCharacterHomeTab.Actions -> PaneSurface.ACTIONS
    }

/**
 * Every surface a DiceCloud character *can* have, in display order — decision 6's "server:
 * Tracker / Inventory / Sheet", plus FR-26's Actions.
 *
 * Derived from `CharacterHomeTab.entries` rather than written out, so the pane picker and the
 * tab row can never disagree about what exists or in what order: adding a tab adds a pane, which
 * is the behaviour that keeps the phone and tablet paths one feature instead of two.
 *
 * **Not what a screen passes to `resolvePanes`** — see [serverPaneSurfaces], which applies
 * FR-26's discovery gate on top of this. This list is the vocabulary; that one is the answer for
 * a particular character.
 */
val allServerPaneSurfaces: List<PaneSurface> = CharacterHomeTab.entries.map { it.surface }

/**
 * The surfaces **this** character has — [allServerPaneSurfaces] with FR-26's discovery gate
 * applied (docs/design/16-actions-and-feed.md decision 1).
 *
 * ### Why availability became a function
 *
 * Decision 1: *"The tab/pane renders only when discovery finds ≥1 spell or action property (the
 * one-tab-drop rule)"*. Until FR-26 every surface a server character could have, it had — so
 * `available` was a compile-time constant and `resolvePanes`' first rule ("only surfaces this
 * character has") only ever fired for the *local* screen's missing Sheet. Actions is the first
 * surface whose existence depends on the character's data, so the constant becomes a function
 * and that rule starts doing work on the server path too.
 *
 * Everything downstream is then free, which is why the gate is expressed here rather than as a
 * new branch anywhere:
 *
 *  - the picker draws no Actions segment (it iterates this list);
 *  - a stored `actions` token is filtered out of the drawn columns (`resolvePanes` rule 1), so a
 *    player who kept the pane open on a spellcaster and then opened a fighter does not get a
 *    blank column — and their preference is **not rewritten**, so it comes back on the caster;
 *  - if that leaves nothing, `resolvePanes` rule 3 falls back to the Tracker.
 *
 * @param hasActions whether discovery found anything — `ActionBoard.isEmpty` inverted. False
 *   while a character is still loading, which is the honest default: a tab that appeared a beat
 *   after the screen would be worse than one that appears with the data. See
 *   `CharacterHomeViewModel.hasActions` for why that transition is debounced by the flow rather
 *   than by a timer.
 */
fun serverPaneSurfaces(hasActions: Boolean): List<PaneSurface> =
    if (hasActions) allServerPaneSurfaces else allServerPaneSurfaces - PaneSurface.ACTIONS

/**
 * The tabs the row draws for this character — the tab-side twin of [serverPaneSurfaces].
 *
 * Two lists rather than one mapped from the other, because the two chrome branches consume
 * different types and the mapping is not injective in the direction that would help: a
 * `PaneSurface` maps back to a tab only for a *server* character, and `PaneSurfaces`' whole point
 * is that the two enums stay separate. `PaneSelectionTest` pins that this list and
 * [serverPaneSurfaces] agree for both values of [hasActions], which is the property that actually
 * matters — a tab present with its pane absent, or the reverse, is the bug this would otherwise
 * introduce.
 */
fun serverHomeTabs(hasActions: Boolean): List<CharacterHomeTab> =
    if (hasActions) CharacterHomeTab.entries else CharacterHomeTab.entries - CharacterHomeTab.Actions

/**
 * Every surface an on-device character *can* have, in display order — decision 6's
 * "local: Tracker / Inventory", plus FR-29's Actions.
 *
 * Derived from `LocalCharacterHomeTab.entries` for [allServerPaneSurfaces]' reason, and still with
 * no Sheet in it because that enum still has no such constant.
 *
 * **Not what the screen passes to `resolvePanes`** — see [localPaneSurfaces], which applies
 * FR-29's discovery gate on top of this.
 */
val allLocalPaneSurfaces: List<PaneSurface> = LocalCharacterHomeTab.entries.map { it.surface }

/**
 * The surfaces **this** on-device character has — [allLocalPaneSurfaces] with FR-29's discovery
 * gate applied (docs/design/18-table-pack.md decision 3).
 *
 * The local twin of [serverPaneSurfaces], and deliberately the same shape rather than a shared
 * generic one: the two operate on different `PaneSurface` subsets and the server's also has a
 * Sheet to keep. Decision 3 asks for the *"same discovery-gating rule"* as FR-26, and this is what
 * "same" looks like from here — the tab and the pane both appear when the character has at least
 * one action row, and everything downstream (the picker's segments, `resolvePanes`' filtering of a
 * stored `actions` token, the fall back to Tracker when that leaves nothing) is free, exactly as
 * it was for the server path.
 *
 * @param hasActions whether the character has any [com.hashtagchow.magehand.core.model.ActionEntry]
 *   at all — `ActionBoard.isEmpty` inverted. False while the character is still loading, which is
 *   the honest default for [serverPaneSurfaces]' stated reason.
 */
fun localPaneSurfaces(hasActions: Boolean): List<PaneSurface> =
    if (hasActions) allLocalPaneSurfaces else allLocalPaneSurfaces - PaneSurface.ACTIONS

/**
 * The tabs the row draws for this on-device character — the tab-side twin of [localPaneSurfaces],
 * exactly as [serverHomeTabs] is of [serverPaneSurfaces] and for the reason stated there.
 */
fun localHomeTabs(hasActions: Boolean): List<LocalCharacterHomeTab> =
    if (hasActions) {
        LocalCharacterHomeTab.entries
    } else {
        LocalCharacterHomeTab.entries - LocalCharacterHomeTab.Actions
    }

/**
 * The picker's label for a surface — the *same* strings the tab row uses.
 *
 * Deliberately not a second set of resources. The pane picker and the tab row are two controls
 * over the same three places, and a tablet that called them something else would be a second
 * vocabulary for a user who moves between a phone and a tablet with the same character open.
 */
@get:StringRes
val PaneSurface.titleResId: Int
    get() = when (this) {
        PaneSurface.TRACKER -> CharacterHomeTab.Tracker.titleResId
        PaneSurface.INVENTORY -> CharacterHomeTab.Inventory.titleResId
        PaneSurface.ACTIONS -> CharacterHomeTab.Actions.titleResId
        PaneSurface.SHEET -> CharacterHomeTab.Sheet.titleResId
    }
