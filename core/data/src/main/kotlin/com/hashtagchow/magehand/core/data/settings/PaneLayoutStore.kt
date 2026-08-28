package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One of the character screen's surfaces, as a thing that can be *chosen* rather than navigated
 * to (docs/design/14-large-screen-arc.md decision 6).
 *
 * ### Why this closed vocabulary lives in `:core:data` and not in the UI
 *
 * [UiScale]'s reason, whole: the store below persists these, so the store's module has to name
 * them. The UI layer owns the other half — which surface renders what, and which of them a given
 * character even has — and maps its own tab enums onto these constants.
 *
 * ### Declaration order IS display order
 *
 * Decision 6: *"Display order is fixed (Tracker, Inventory, Sheet) regardless of selection order
 * — panes are places, not history."* That rule is enforced by making the *set* the stored value
 * and this enum's ordinal the only ordering anything uses, so there is no selection order
 * anywhere to accidentally leak into the layout. A `List` in the store would have made
 * "selection order is not display order" a thing every reader had to remember; a `Set` makes it
 * unrepresentable.
 *
 * The order matches `CharacterHomeTab`'s for the same reason that enum gives for its own order
 * — Tracker and Inventory are the native surfaces, the Sheet is the WebView fallback behind
 * them — and `PaneSurfaceTest` pins that the two agree, because two enums that must stay in step
 * and only agree by coincidence do not stay in step.
 *
 * @property key the stored token. Stable: it is on disk on user devices.
 */
enum class PaneSurface(val key: String) {
    TRACKER("tracker"),
    INVENTORY("inventory"),

    /**
     * FR-26's Actions surface (docs/design/16-actions-and-feed.md decision 1).
     *
     * **Inserted here, not appended**, and the position is the decision: decision 1 fixes the
     * display order at *"Tracker → Inventory → Actions → Sheet everywhere the order exists"*, and
     * this enum's ordinal IS that order (see the class KDoc). Appending would have put the
     * Actions column to the right of the Sheet's WebView with no other file changing and no test
     * failing on the order itself.
     *
     * Inserting is safe for data already on disk because the codec is keyed on [key], not on the
     * ordinal — `tracker,sheet` written by 1.8.0 decodes to the same two surfaces here. The only
     * thing the ordinal decides is the order they are drawn in, which is exactly what changed on
     * purpose.
     */
    ACTIONS("actions"),
    SHEET("sheet"),
    ;

    companion object {
        /** The token back to a surface, or `null` for one this build has never heard of. */
        fun fromKey(key: String): PaneSurface? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Which panes the player has open on a character's home screen, **per character, across
 * restarts** (docs/design/14-large-screen-arc.md decision 8).
 *
 * ### What it is for
 *
 * On an EXPANDED-width window the tab row becomes a multi-select picker and the chosen surfaces
 * render side by side (decisions 5–7). *Which* surfaces those are is a durable fact about how
 * somebody plays a particular character — "I keep the tracker and the sheet up for my wizard"
 * — so it outlives the screen, the process and the install session, exactly as
 * [InventoryLayoutStore]'s arrangement does.
 *
 * ### Why this is the fourth copy of the same store and not a generalisation of the first three
 *
 * [InventoryLayoutStore]'s KDoc makes the argument at length and it has not changed: the four
 * stores share a *lifecycle*, not a *contract*. A remembered roll is one nullable string, an
 * override set is an unordered set of opaque ids, an inventory layout is an ordered list with
 * two flags per entry, and this is an unordered set over a **closed enum** — which is the
 * difference that matters here, because it is what lets [PaneLayoutCodec] drop a token it does
 * not recognise instead of keeping it. Generalising would mean parameterising over the value
 * type, the empty-is-absent rule, the codec *and* the unknown-token policy, at which point it is
 * four interfaces wearing one name.
 *
 * What the four genuinely share is the two reaping paths, and those are shared the way that
 * helps: same DataStore file (see `DataModule`), same key shape, same two call sites in
 * `DefaultAccountRepository.signOut` and `LocalCharacterRepository.delete`.
 *
 * ### Default, and reset
 *
 * Decision 8: *"Default: Tracker only (today's behavior)"* and *"reset-to-default = delete"*.
 * Both fall out of [InventoryLayoutStore]'s empty-is-absent rule rather than needing a branch:
 * an absent key decodes to the empty set, and the UI layer resolves an empty set to the first
 * available surface. So the default is never written down anywhere — which is the point, because
 * a stored copy of today's default would freeze it into every character that had ever been
 * touched, and a later release that changed the default would change it for nobody.
 *
 * Note that a *user* cannot reach the empty set: the picker enforces a minimum of one pane
 * (decision 6). [clearForCharacter] is therefore the reset/reap path rather than a control the
 * player presses, and the empty case is exercised by `PaneLayoutStoreTest` instead of by the UI.
 */
interface PaneLayoutStore {

    /**
     * This character's chosen panes, or the empty set when they have never chosen — which reads
     * as *use the default*, matching [InventoryLayoutStore.layout] and
     * [EquippableOverrideStore.overrides].
     *
     * A `Set`, so nothing downstream can mistake it for an order. See [PaneSurface].
     */
    fun panes(characterKey: String): Flow<Set<PaneSurface>>

    /**
     * Replaces this character's chosen panes.
     *
     * An empty set **removes the key** rather than storing an empty string, for
     * [InventoryLayoutStore.setLayout]'s reason: a key holding nothing reads identically to no
     * key, and leaving one behind would make a character who customized and reset permanently
     * occupy a slot in a file nothing else prunes.
     *
     * This store does **not** enforce decision 6's minimum of one pane. That rule belongs to the
     * picker, which is where the user gesture that could violate it happens; enforcing it here
     * as well would mean the store silently rewriting a caller's value, and a store that returns
     * something other than what it was given is the kind of thing that gets debugged twice.
     */
    suspend fun setPanes(characterKey: String, panes: Set<PaneSurface>)

    /**
     * Drops this character's pane choice, so the default (Tracker only) draws again.
     *
     * One method for both the reset and the local-delete reap, exactly as
     * [InventoryLayoutStore.clearForCharacter] is, and for the same reason: they are the same
     * write and the same intent.
     */
    suspend fun clearForCharacter(characterKey: String)

    /** Drops every pane choice belonging to [accountId]. See the class KDoc. */
    suspend fun deleteForAccount(accountId: String)

    companion object {
        /**
         * The key for a DiceCloud character — decision 8's `pane_layout:server:<acct>:<creature>`.
         *
         * Account-scoped for [InventoryLayoutStore.serverKey]'s reasons exactly: the same creature
         * is reachable from two accounts and is two rows everywhere else in this app, and the
         * scoping is what makes [deleteForAccount] a prefix match rather than a scan.
         */
        fun serverKey(accountId: String, creatureId: String): String =
            "$SERVER_PREFIX$accountId:$creatureId"

        /**
         * The key for an on-device character — decision 8's `pane_layout:local:<id>`.
         *
         * A separate prefix for the guarantee rather than for disambiguation: it is what makes
         * [deleteForAccount]'s prefix match *provably* unable to reach a local character's panes.
         * Sign-out must not touch local data (09 decision 10).
         */
        fun localKey(characterId: String): String = "$LOCAL_PREFIX$characterId"

        internal const val KEY_PREFIX = "pane_layout:"
        internal const val SERVER_PREFIX = "${KEY_PREFIX}server:"
        internal const val LOCAL_PREFIX = "${KEY_PREFIX}local:"
    }
}

/**
 * The stored format (decision 8: *"comma-joined surface keys"*) — `tracker,sheet`.
 *
 * ### Why a hand-rolled string and not JSON
 *
 * [InventoryLayoutCodec]'s reason, unchanged and if anything stronger here: the value is a
 * handful of short constant tokens, JSON would cost a serializer dependency in `:core:data` to
 * express `["tracker","sheet"]`, and the format stays readable in a preferences dump — which is
 * the only debugging tool a DataStore preference has.
 *
 * ### Unknown tokens are DROPPED, which is the opposite of what the inventory codec does
 *
 * [InventoryLayoutCodec] keeps a key it does not recognise, because there the keys are *opaque*
 * — `container:abc` names a thing on the character's sheet that `:core:data` has never heard of
 * and has no business discarding. Here the vocabulary is closed: every legal token is a
 * [PaneSurface] constant this build compiled in. A token outside it can only be a surface some
 * *later* release added, and the honest degradation is to render the panes this build does have
 * rather than to carry an id nothing can draw.
 *
 * That difference is why this is a second codec rather than a reuse of the first, and it is
 * pinned in `PaneLayoutStoreTest`: a future `pane_layout` of `tracker,dmview` must open on
 * Tracker, not crash, not show an empty column, and not lose Tracker.
 *
 * ### Decoding to a set, and what that costs
 *
 * A duplicate token collapses (a surface cannot be in two columns) and order is discarded on
 * read — see [PaneSurface]. [encode] emits in ordinal order so a round trip is byte-stable and
 * two writers of the same set agree.
 *
 * ### What makes the delimiter safe
 *
 * Every token is a compiled-in constant from [PaneSurface] and none of them contains a comma,
 * so the split cannot cut a token in half. [decode] does not rely on that being true forever:
 * blank segments are dropped, so a malformed string that somehow reached the file degrades to
 * a partial set — and, via the empty case, to the default — rather than to a crash.
 */
internal object PaneLayoutCodec {
    const val SEPARATOR = ","

    fun encode(panes: Set<PaneSurface>): String =
        // Sorted by ordinal rather than by the set's iteration order: a `LinkedHashSet` built by
        // tapping the picker would otherwise write selection order into the file, which is
        // exactly the fact decision 6 says nothing may remember.
        panes.sortedBy { it.ordinal }.joinToString(SEPARATOR) { it.key }

    fun decode(stored: String?): Set<PaneSurface> =
        stored.orEmpty()
            .split(SEPARATOR)
            .mapNotNull { token -> PaneSurface.fromKey(token.trim()) }
            .toSet()
}

/** Preferences-DataStore backed [PaneLayoutStore]. */
class DataStorePaneLayoutStore(
    private val dataStore: DataStore<Preferences>,
) : PaneLayoutStore {

    override fun panes(characterKey: String): Flow<Set<PaneSurface>> =
        dataStore.data.map { PaneLayoutCodec.decode(it[key(characterKey)]) }

    override suspend fun setPanes(characterKey: String, panes: Set<PaneSurface>) {
        val preferenceKey = key(characterKey)
        dataStore.edit { prefs ->
            // Absent, not empty — see the interface. An empty string would decode to an empty
            // set and read identically, which is exactly why it must not be written.
            if (panes.isEmpty()) {
                prefs.remove(preferenceKey)
            } else {
                prefs[preferenceKey] = PaneLayoutCodec.encode(panes)
            }
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        dataStore.edit { it.remove(key(characterKey)) }
    }

    /**
     * Removes by **prefix**, in one edit — `DataStoreInventoryLayoutStore.deleteForAccount`'s
     * mechanism, for its reasons: DataStore is a flat map with no index, so the account's
     * characters are found by the shape of the key rather than by asking a table which creatures
     * the account had. That is the more robust direction — a character the cache had already
     * forgotten is still reaped — and the whole thing is one atomic `edit`.
     */
    override suspend fun deleteForAccount(accountId: String) {
        val prefix = PaneLayoutStore.serverKey(accountId, "")
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun key(characterKey: String) = stringPreferencesKey(characterKey)
}
