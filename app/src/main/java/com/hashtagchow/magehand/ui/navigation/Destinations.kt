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

/** The tabs inside [CharacterHome]. Tab state is local, not a nav destination. */
enum class CharacterHomeTab(val titleResId: Int) {
    Tracker(com.hashtagchow.magehand.R.string.tab_tracker),
    Sheet(com.hashtagchow.magehand.R.string.tab_sheet),
}
