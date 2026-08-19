package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which roll the tracker's Rolls dropdown is showing, **per character, across restarts**.
 *
 * ### Why this is not a Room table, and not a column on an existing one
 *
 * Three honest-looking homes were considered and each fails on a different fact:
 *
 *  - **`tracker_prefs`** carries pin / hide / reorder, keyed by `propertyId`. A selection is
 *    none of those three, and expressing it as (say) a pin would overload a flag that already
 *    means something on the same id space — a discovered row's pin and "this is the roll I
 *    picked" would be one boolean with two meanings.
 *  - **`theme_prefs`** is the right *shape* (one row per character) and the wrong table: it
 *    holds the accent colour, and a `selectedRollId` column on it would make the name a lie
 *    for the next reader.
 *  - **Either of them, for a local character**, is impossible rather than merely untidy. Both
 *    are keyed by `(accountId, creatureId)`, and a local character has no account — storing a
 *    selection there needs the sentinel account docs/design/09-local-characters.md decision 1
 *    forbids, exactly as `LocalOpenCharacter.accentColor` explains for the accent.
 *
 * So a schema change would have had to be **two** mechanisms (a v4 table for server characters
 * plus something else entirely for local ones) to cover what this one interface covers with
 * neither a migration nor a fake account: the key is the character, whichever kind it is.
 * DataStore is also already the app's pattern for exactly this class of preference — small,
 * flat, not account-shaped (`ActiveAccountStore`, and FR-6's `AppSettingsStore`).
 *
 * ### Why it is not a third key on [AppSettingsStore]
 *
 * That interface's own KDoc says what it is for: preferences that are *"not about an account
 * and not about one character"*. This is about one character. Same file underneath (see
 * `DataModule`), different contract on top.
 *
 * ### Sign-out
 *
 * [deleteForAccount] exists because `DefaultAccountRepository.signOut` is the account's ordinary
 * end, and `accounts.id` is a fresh UUID per sign-in — so anything left keyed to a dead account
 * id is unreachable **forever**, not merely stale. That argument is the repository's own, and
 * this store is subject to it like every other per-account store.
 *
 * `LegacyTokenStorePurge` also ends accounts, and deliberately does not call this: it fires only
 * on pre-`versionCode 2` sideloads, which predate FR-7 entirely, so there is no selection of
 * theirs left to reap.
 *
 * ### Deleting an on-device character
 *
 * `LocalCharacterRepository.delete` clears that character's key by hand, because the local
 * namespace is outside [deleteForAccount]'s reach on purpose (09 decision 10) and a DataStore
 * key is not a row that `ON DELETE CASCADE` can follow.
 */
interface SelectedRollStore {

    /**
     * The remembered [com.hashtagchow.magehand.core.model.RollModifier.id] for one character,
     * or `null` when the player has not picked one — which the tracker renders as a
     * placeholder, not as a guess at which roll they meant.
     *
     * A stored id that no longer names a discovered roll also reads as "nothing selected" —
     * that resolution happens where the board is known (see the UI mapping), not here: this
     * store's job is to remember a string, not to know what a character has.
     */
    fun selectedRollId(characterKey: String): Flow<String?>

    /**
     * `null` removes the key.
     *
     * It is **not** how a player reaches "no selection", and v1 gives them no way to: nothing in
     * the UI passes `null` here. They do not need one — the placeholder state is reachable
     * without any clear affordance, twice over (never having picked, and a stored id the board
     * no longer has), and a "clear" item in a dropdown whose every other entry picks something
     * is a control most players would never look for. The only caller is
     * `LocalCharacterRepository.delete`, reaping the key of a character that no longer exists.
     */
    suspend fun setSelectedRollId(characterKey: String, rollId: String?)

    /** Drops every selection belonging to [accountId]. See the class KDoc. */
    suspend fun deleteForAccount(accountId: String)

    companion object {
        /**
         * The key for a DiceCloud character.
         *
         * Account-scoped, matching `tracker_prefs` and `theme_prefs`: the same creature can be
         * reachable from two accounts, and those two are different rows everywhere else in the
         * app. It is also what makes [deleteForAccount] a prefix match rather than a scan of
         * every character the user has ever opened.
         */
        fun serverKey(accountId: String, creatureId: String): String =
            "$SERVER_PREFIX$accountId:$creatureId"

        /**
         * The key for an on-device character.
         *
         * A different prefix rather than the bare id: both id spaces are unique on their own
         * (Meteor ids and UUIDs), so this is not disambiguation — it is what makes
         * [deleteForAccount]'s prefix match provably unable to reach a local character's
         * selection. Sign-out must not touch local data (09 decision 10), and a namespace is a
         * stronger guarantee of that than a comment.
         */
        fun localKey(characterId: String): String = "$LOCAL_PREFIX$characterId"

        internal const val KEY_PREFIX = "selected_roll:"
        internal const val SERVER_PREFIX = "${KEY_PREFIX}server:"
        internal const val LOCAL_PREFIX = "${KEY_PREFIX}local:"
    }
}

/** Preferences-DataStore backed [SelectedRollStore]. */
class DataStoreSelectedRollStore(
    private val dataStore: DataStore<Preferences>,
) : SelectedRollStore {

    override fun selectedRollId(characterKey: String): Flow<String?> =
        dataStore.data.map { it[key(characterKey)] }

    override suspend fun setSelectedRollId(characterKey: String, rollId: String?) {
        dataStore.edit { prefs ->
            if (rollId == null) prefs.remove(key(characterKey)) else prefs[key(characterKey)] = rollId
        }
    }

    /**
     * Removes by **prefix**, in one edit.
     *
     * There is no index to consult — DataStore is a flat map — so the account's characters are
     * found by the shape of the key rather than by asking a table which creatures the account
     * had. That is the more robust direction: a character the cache had already forgotten is
     * still reaped, and reaping is the whole point.
     *
     * The keys are collected before the removals so nothing mutates the map being walked, and
     * the whole thing is one `edit` block, which DataStore applies atomically.
     */
    override suspend fun deleteForAccount(accountId: String) {
        val prefix = SelectedRollStore.serverKey(accountId, "")
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun key(characterKey: String) = stringPreferencesKey(characterKey)
}
