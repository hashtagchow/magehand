package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which items the player has told this app *are* equippable, **per character, across
 * restarts** (docs/design/11-inventory-polish.md decision 2).
 *
 * ### What it is for
 *
 * 11 decision 1's rule has one known residual, and it is a false negative: a hand-made item
 * with no SRD tags is equippable while it is equipped (the rule's `equipped` disjunct) and
 * stops being equippable the moment the player takes it off. "A Small Knife" on the reference
 * sheet is exactly that item. Something has to give the control back, and decision 2 chose the
 * player over a guess — a name heuristic that recognised "knife" would also have to explain
 * itself about "A Little Bag of Sand", two rows further down the same sheet.
 *
 * So this is a **per-item, player-set exception**, and it only ever adds: there is no
 * "not equippable" override, because the rule's false *positives* were zero on both probed
 * sheets and a switch that could take a control away is a way to break a working sheet.
 *
 * ### Why a DataStore string set and not a schema change
 *
 * Decision 2 says "NO schema change", and the reasons are `SelectedRollStore`'s, whole: the
 * key has to be *the character* — a local one has no account, and 09 decision 1 forbids the
 * sentinel account a per-account table would need — and this is a preference about how the app
 * renders a sheet, not a fact about the sheet. It shares that store's file (see `DataModule`)
 * and its two key namespaces, deliberately, so that the two reaping paths below are the same
 * two paths and not a second pair to keep in step.
 *
 * A `stringSet` rather than one boolean key per item because a character's overrides are read
 * as a group — the inventory tab needs all of them on every rebuild — and because a set has
 * exactly one key to reap per character, which is what makes both paths below a one-liner.
 * An empty set is stored as an *absent* key, never as `emptySet()`: see [setOverridden].
 *
 * ### Sign-out
 *
 * [deleteForAccount] exists for the reason `DefaultAccountRepository.signOut` states for every
 * other per-account store: `accounts.id` is a fresh UUID per sign-in, so anything left keyed to
 * a dead account id is unreachable **forever** rather than merely stale.
 *
 * ### Deleting an on-device character
 *
 * `LocalCharacterRepository.delete` clears that character's key by hand, because the local
 * namespace is outside [deleteForAccount]'s reach on purpose (09 decision 10) and a DataStore
 * key is not a row that `ON DELETE CASCADE` can follow. Same argument, same two call sites, as
 * [SelectedRollStore] — which is why this interface deliberately mirrors its shape rather than
 * inventing a tidier one of its own.
 */
interface EquippableOverrideStore {

    /**
     * The `creatureProperties._id`s this character has overridden, or an empty set.
     *
     * Empty rather than `null` for "nothing overridden", because the two are the same thing to
     * every caller: the UI unions this with the engine's own answer, and a nullable set would
     * make every call site write `?: emptySet()` to reach the same place.
     *
     * Ids in here that no longer name an item on the sheet are simply never matched. They are
     * **not** swept: this store does not know what a character has (that is the board's job,
     * and it changes with every sync), and an id whose item comes back — an undo, a re-sync
     * after a dropped socket — should find its override still there rather than have been
     * tidied away while the socket was down.
     */
    fun overrides(characterKey: String): Flow<Set<String>>

    /**
     * Adds or removes one item's override.
     *
     * Read-modify-write inside a single `edit`, which DataStore applies atomically — so two
     * fast taps on two different items cannot lose one of them, which a naive
     * `overrides().first()` followed by a write would.
     *
     * Removing the last entry **removes the key** rather than storing an empty set. A key
     * holding nothing is indistinguishable from no key to every reader, and leaving one behind
     * would make a character who turned an override on and off again permanently occupy a slot
     * in a file that has no other way of being tidied.
     */
    suspend fun setOverridden(characterKey: String, propertyId: String, overridden: Boolean)

    /**
     * Drops every override for one character.
     *
     * The local-deletion path's call, and the twin of `SelectedRollStore.setSelectedRollId(key,
     * null)`. Named rather than expressed as "set the whole set to empty", because the caller's
     * intent is *reaping a dead character's key*, not editing a live character's set to nothing
     * — and those two want different behaviour if this interface ever grows a listener.
     */
    suspend fun clearForCharacter(characterKey: String)

    /** Drops every override belonging to [accountId]. See the class KDoc. */
    suspend fun deleteForAccount(accountId: String)

    companion object {
        /**
         * The key for a DiceCloud character.
         *
         * Account-scoped for [SelectedRollStore.serverKey]'s reasons exactly: the same creature
         * is reachable from two accounts and is two rows everywhere else in this app, and the
         * scoping is what makes [deleteForAccount] a prefix match rather than a scan.
         */
        fun serverKey(accountId: String, creatureId: String): String =
            "$SERVER_PREFIX$accountId:$creatureId"

        /**
         * The key for an on-device character.
         *
         * A separate prefix rather than the bare id, and for the guarantee rather than for
         * disambiguation: it is what makes [deleteForAccount]'s prefix match *provably* unable
         * to reach a local character's overrides. Sign-out must not touch local data (09
         * decision 10), and a namespace states that more strongly than a comment.
         */
        fun localKey(characterId: String): String = "$LOCAL_PREFIX$characterId"

        internal const val KEY_PREFIX = "equippable_override:"
        internal const val SERVER_PREFIX = "${KEY_PREFIX}server:"
        internal const val LOCAL_PREFIX = "${KEY_PREFIX}local:"
    }
}

/** Preferences-DataStore backed [EquippableOverrideStore]. */
class DataStoreEquippableOverrideStore(
    private val dataStore: DataStore<Preferences>,
) : EquippableOverrideStore {

    override fun overrides(characterKey: String): Flow<Set<String>> =
        dataStore.data.map { it[key(characterKey)].orEmpty() }

    override suspend fun setOverridden(
        characterKey: String,
        propertyId: String,
        overridden: Boolean,
    ) {
        val preferenceKey = key(characterKey)
        dataStore.edit { prefs ->
            val current = prefs[preferenceKey].orEmpty()
            val next = if (overridden) current + propertyId else current - propertyId
            // A stored empty set and an absent key read identically; only one of them takes up
            // room in a file nothing else prunes. See [setOverridden]'s KDoc.
            if (next.isEmpty()) prefs.remove(preferenceKey) else prefs[preferenceKey] = next
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        dataStore.edit { it.remove(key(characterKey)) }
    }

    /**
     * Removes by **prefix**, in one edit — `DataStoreSelectedRollStore.deleteForAccount`'s
     * mechanism, for its reasons.
     *
     * There is no index to consult, DataStore being a flat map, so the account's characters are
     * found by the shape of the key rather than by asking a table which creatures the account
     * had. That is the more robust direction: a character the cache had already forgotten is
     * still reaped, and reaping is the whole point. The keys are collected before the removals
     * so nothing mutates the map being walked, and the whole thing is one atomic `edit`.
     */
    override suspend fun deleteForAccount(accountId: String) {
        val prefix = EquippableOverrideStore.serverKey(accountId, "")
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(stringSetPreferencesKey(it.name)) }
        }
    }

    private fun key(characterKey: String) = stringSetPreferencesKey(characterKey)
}
