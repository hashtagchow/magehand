package com.hashtagchow.magehand.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations, mirroring the nav graph in
 * docs/design/04-screens-ux.md:
 *
 * ```
 * LoginGraph:   Credentials (dicecloud.com default, optional custom server)
 * MainGraph:    CharacterList <-> CharacterHome(creatureId)
 *                                 CharacterHome tabs: [Tracker] [Sheet]
 *               Settings, AccountSwitcher (sheet), TrackerCustomize (sheet)
 * ```
 *
 * The two bottom sheets (AccountSwitcher, TrackerCustomize) are *not* nav
 * destinations — they are Compose bottom sheets owned by their host screens, so
 * they are absent here by design (WP5 / WP6 add them).
 */
sealed interface Destination

/** Nested graph covering sign-in. */
@Serializable
data object LoginGraph : Destination

/** Nested graph covering everything reachable once an account exists. */
@Serializable
data object MainGraph : Destination

/**
 * Screen 1 — username-or-email + password. Signs in to the official
 * dicecloud.com unless the user expands the custom-server field. The password
 * is never persisted; only the returned token is kept.
 */
@Serializable
data object Credentials : Destination

/** Screen 2 — the character list for the signed-in account. */
@Serializable
data object CharacterList : Destination

/** Screens 3 + 4 — one character, with the Tracker and Sheet tabs. */
@Serializable
data class CharacterHome(val creatureId: String) : Destination

/**
 * The character list's "New character" FAB target: the DiceCloud PWA's own
 * creator, rendered in the token-injected WebView (docs/design/04-screens-ux.md §2
 * — "opens the PWA creator in the Sheet tab; native creator is out of v1").
 */
@Serializable
data object CharacterCreator : Destination

/** Screen 6 — settings and account switching. */
@Serializable
data object Settings : Destination

/**
 * FR-19's DM dashboard (docs/design/14-large-screen-arc.md decisions 11-19).
 *
 * ### Why it carries no arguments
 *
 * The obvious signature would be `DmView(val creatureIds: List<String>)` — the membership, passed
 * from the picker that chose it. It is deliberately not that, for two reasons that point the same
 * way.
 *
 * **The set is already persisted.** Decision 16 keys it at `dm_view:server:<acct>`, so passing it
 * on the route would mean two sources of truth for one fact, and the route would be the one that
 * could disagree — a saved back stack restored after process death carries whatever ids were
 * chosen when the entry was created, which is exactly the state a revoked share invalidates.
 * Reading the store on entry means the dashboard opens against what is true now.
 *
 * **A route is a key, and this one would be a long one.** Type-safe nav serializes arguments into
 * the destination's identity, so six 17-character Meteor ids would become part of the string every
 * `rememberSaveable` on the screen is scoped under — and re-picking the same party in a different
 * order would produce a *different* nav entry for the same dashboard.
 *
 * A `data object` rather than a `data class` therefore, matching [CharacterList] and [Settings]:
 * the screen's identity is "the DM view", and which characters are on it is state, not identity.
 */
@Serializable
data object DmView : Destination

/**
 * A local character's tracker (docs/design/09-local-characters.md decisions 5–8).
 *
 * A separate destination from [CharacterHome] rather than a flag on it, for the reason
 * `LocalCharacterHomeViewModel`'s KDoc gives: the two screens share the tracker and nothing
 * else, and a single route would mean one nav entry whose view model builds the DDP object
 * graph for a character that has no account. `characterId` is an app-minted UUID, never a
 * DiceCloud `creatureId` — hence the different parameter name, so the two cannot be passed to
 * each other by mistake.
 */
@Serializable
data class LocalCharacterHome(val characterId: String) : Destination

/**
 * The local character creation/edit form (09 decision 4).
 *
 * One destination for both jobs, because there is one form: `null` is "create", an id is
 * "edit that one". Named *Editor* rather than *Form* to leave the name `LocalCharacterForm`
 * to `:core:data`'s type, which this screen fills in.
 */
@Serializable
data class LocalCharacterEditor(val characterId: String? = null) : Destination

/**
 * The tabs inside [CharacterHome]. Tab state is local, not a nav destination.
 *
 * **Declaration order is the on-screen order** (both `PrimaryTabRow` and the content `when`
 * key off it), so FR-8's Inventory sits between Tracker and Sheet by being written there —
 * docs/design/10-inventory.md decision 1. That placement is not arbitrary: Tracker and
 * Inventory are both native surfaces the player touches mid-turn, and the Sheet tab is the
 * WebView fallback for everything neither of them models. Putting Inventory after Sheet would
 * have filed the app's own screen behind the escape hatch.
 */
enum class CharacterHomeTab(val titleResId: Int) {
    Tracker(com.hashtagchow.magehand.R.string.tab_tracker),
    Inventory(com.hashtagchow.magehand.R.string.tab_inventory),

    /**
     * FR-26 (docs/design/16-actions-and-feed.md decision 1), between Inventory and Sheet.
     *
     * The position matters and is pinned: `PaneSurface`'s ordinals and this enum's must be the
     * same list for decision 6's "fixed display order" to mean anything, and `PaneSelectionTest`
     * asserts the two agree. Unlike the other three, this tab is **discovery-gated** — it is
     * dropped for a character whose sheet has no spells and no actions — which is why
     * `serverPaneSurfaces` became a function rather than staying a constant.
     */
    Actions(com.hashtagchow.magehand.R.string.tab_actions),
    Sheet(com.hashtagchow.magehand.R.string.tab_sheet),
}

/**
 * The tabs inside [LocalCharacterHome] (10 decision 1: "Tracker · Inventory").
 *
 * A separate enum from [CharacterHomeTab] rather than a filtered view of it, for the same
 * reason the two home screens are separate: a local character has no `SheetSession` for a
 * Sheet tab to render, and 09 decision 8 requires that the WebView is never instantiated on
 * this screen. Sharing the enum would make "no Sheet tab" a `filterNot` that a future edit
 * could drop, instead of a type with no such constant in it.
 */
enum class LocalCharacterHomeTab(val titleResId: Int) {
    Tracker(com.hashtagchow.magehand.R.string.tab_tracker),
    Inventory(com.hashtagchow.magehand.R.string.tab_inventory),
}
