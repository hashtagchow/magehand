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
    }

/**
 * The surfaces a DiceCloud character has, in display order — decision 6's "server: Tracker /
 * Inventory / Sheet".
 *
 * Derived from `CharacterHomeTab.entries` rather than written out, so the pane picker and the
 * tab row can never disagree about what exists or in what order: adding a tab adds a pane, which
 * is the behaviour that keeps the phone and tablet paths one feature instead of two.
 */
val serverPaneSurfaces: List<PaneSurface> = CharacterHomeTab.entries.map { it.surface }

/** The surfaces an on-device character has — decision 6's "local: Tracker / Inventory". */
val localPaneSurfaces: List<PaneSurface> = LocalCharacterHomeTab.entries.map { it.surface }

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
        PaneSurface.SHEET -> CharacterHomeTab.Sheet.titleResId
    }
