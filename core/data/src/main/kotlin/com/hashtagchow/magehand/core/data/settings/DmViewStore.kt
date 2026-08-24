package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which characters the DM has put on the dashboard, **per account, across restarts**
 * (docs/design/14-large-screen-arc.md decisions 11 and 16).
 *
 * ### What it is for, and why the key is per *account* rather than per character
 *
 * The other four stores in this package answer "how does this player like *this character*
 * drawn?". This one answers a different question — *"who is at my table?"* — and that is a
 * fact about the **account**, not about any one creature in the set. Decision 16 names the key
 * shape for exactly that reason: `dm_view:server:<acct>`, one row per account, holding an
 * ordered-by-nothing set of creature ids.
 *
 * That difference is the whole reason this is a fifth store and not a fifth *use* of
 * [PaneLayoutStore]. Every rule below is [PaneLayoutStore]'s, applied to a key that has one
 * fewer component in it — which also means the sign-out reap is an exact-key delete rather than
 * a prefix sweep, and there is no local namespace to protect, because a character with no
 * server cannot be live-subscribed and therefore cannot be on this dashboard.
 *
 * ### Unknown ids are KEPT here, which is the opposite of `PaneLayoutCodec`
 *
 * [PaneLayoutCodec] drops a token it does not recognise because its vocabulary is closed —
 * every legal value is a `PaneSurface` constant the build compiled in. Creature ids are
 * **opaque**, exactly as `InventoryLayoutCodec`'s keys are: `:core:data` has never heard of any
 * particular one and has no basis for calling one wrong. So the codec keeps whatever it reads,
 * and the *dropping* — decision 16's *"unknown ids dropped against the live list"* — happens
 * one layer up, where the live `characterList` is, which is the only place that knows whether an
 * id still names a character this account can see.
 *
 * The distinction matters for a real case: a character that is briefly absent from the list
 * (a cold open before the subscription lands, a revoked-then-restored share) must not have its
 * membership silently erased from disk by having been unresolvable for one frame. Keeping the
 * id and filtering the *render* is what makes that recoverable.
 *
 * ### Default, and reset
 *
 * An absent key decodes to the empty set, which reads as *"the DM has never chosen"* — the
 * dashboard then has nothing to open with and the entry point runs its own multi-select
 * instead. Nothing writes a default down, for [PaneLayoutStore]'s reason: a stored copy of
 * today's default would freeze it into the account.
 *
 * The minimum of two and the maximum of six (decision 16) are **not** enforced here, matching
 * [PaneLayoutStore.setPanes]'s posture: they are rules about the *gesture*, they belong where
 * the user makes it, and a store that silently returns something other than what it was given
 * is the kind of thing that gets debugged twice.
 */
interface DmViewStore {

    /**
     * This account's chosen dashboard members, or the empty set when they have never chosen.
     *
     * A `Set`, so nothing downstream can mistake it for an order — the dashboard's grid orders
     * by the live list (which is name-sorted), not by the order the DM ticked the boxes, for
     * `PaneSurface`'s reason: *places, not history*.
     */
    fun members(accountKey: String): Flow<Set<String>>

    /**
     * Replaces this account's chosen members.
     *
     * An empty set **removes the key** rather than storing an empty string, for
     * [InventoryLayoutStore.setLayout]'s reason: a key holding nothing reads identically to no
     * key, and leaving one behind would make an account that opened the DM view once and then
     * cleared it permanently occupy a slot in a file nothing else prunes.
     */
    suspend fun setMembers(accountKey: String, members: Set<String>)

    /**
     * Drops this account's dashboard membership.
     *
     * One method for both the reset and the sign-out reap, exactly as
     * [PaneLayoutStore.clearForCharacter] is — and here they are the same *key*, not merely the
     * same write, which is why there is no separate `deleteForAccount`. See [serverKey].
     */
    suspend fun deleteForAccount(accountId: String)

    companion object {
        /**
         * Decision 16's `dm_view:server:<acct>`.
         *
         * Account-scoped like every other key in this package, and here that is not merely
         * convention: the *value* is a list of creature ids, and the same creature reached from
         * two accounts is two rows everywhere else in this app. Two DMs sharing a device would
         * otherwise share a table.
         *
         * The `server:` component is redundant today — there is no local DM view and decision 12
         * fences one out — and it is kept anyway so the key sorts and reads beside
         * `pane_layout:server:…` in a preferences dump, which is the only debugging tool a
         * DataStore preference has.
         */
        fun serverKey(accountId: String): String = "$SERVER_PREFIX$accountId"

        internal const val KEY_PREFIX = "dm_view:"
        internal const val SERVER_PREFIX = "${KEY_PREFIX}server:"
    }
}

/**
 * The stored format — `abc123,def456`, comma-joined creature ids.
 *
 * ### Why a hand-rolled string and not JSON
 *
 * [PaneLayoutCodec]'s reason, unchanged: at most six short opaque tokens, JSON would cost a
 * serializer dependency in `:core:data` to express `["abc","def"]`, and the format stays
 * readable in a preferences dump.
 *
 * ### What makes the delimiter safe
 *
 * Meteor ids are drawn from an unreserved 17-character alphabet and contain no comma, so the
 * split cannot cut one in half. [decode] does not rely on that being true forever: blank
 * segments are dropped, so a malformed string that somehow reached the file degrades to a
 * partial set — and, via the empty case, to "never chosen" — rather than to a crash or to a
 * member whose id is the empty string.
 *
 * ### Order is discarded on read, and [encode] sorts
 *
 * A `Set` on both sides, and encoded in a stable order, so a round trip is byte-stable and two
 * writers of the same membership agree. Sorted lexicographically rather than by the set's
 * iteration order for [PaneLayoutCodec.encode]'s reason: a `LinkedHashSet` built by ticking
 * checkboxes would otherwise write selection order into the file, and the dashboard's order is
 * the live list's, never the DM's tapping history.
 */
internal object DmViewCodec {
    const val SEPARATOR = ","

    fun encode(members: Set<String>): String =
        members.sorted().joinToString(SEPARATOR)

    fun decode(stored: String?): Set<String> =
        stored.orEmpty()
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}

/** Preferences-DataStore backed [DmViewStore]. */
class DataStoreDmViewStore(
    private val dataStore: DataStore<Preferences>,
) : DmViewStore {

    override fun members(accountKey: String): Flow<Set<String>> =
        dataStore.data.map { DmViewCodec.decode(it[key(accountKey)]) }

    override suspend fun setMembers(accountKey: String, members: Set<String>) {
        val preferenceKey = key(accountKey)
        dataStore.edit { prefs ->
            // Absent, not empty — see the interface. An empty string would decode to an empty
            // set and read identically, which is exactly why it must not be written.
            if (members.isEmpty()) {
                prefs.remove(preferenceKey)
            } else {
                prefs[preferenceKey] = DmViewCodec.encode(members)
            }
        }
    }

    /**
     * An **exact-key** delete, and that is the one place this store's mechanism differs from
     * its four siblings.
     *
     * They sweep by prefix because their keys carry a creature component and DataStore is a flat
     * map with no index, so an account's characters can only be found by the shape of the key.
     * Here the account *is* the whole key, so the sweep would be a scan looking for exactly one
     * row — and, worse, `dm_view:server:acct1` is a prefix of `dm_view:server:acct10`, so a
     * prefix match would reap a second account's table along with the one signing out.
     */
    override suspend fun deleteForAccount(accountId: String) {
        dataStore.edit { it.remove(key(DmViewStore.serverKey(accountId))) }
    }

    private fun key(accountKey: String) = stringPreferencesKey(accountKey)
}
