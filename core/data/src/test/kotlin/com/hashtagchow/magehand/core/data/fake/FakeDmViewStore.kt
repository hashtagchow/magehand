package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.settings.DmViewStore

/**
 * An in-memory [DmViewStore], for tests about something *else* that happen to construct a
 * collaborator needing one.
 *
 * [FakePaneLayoutStore]'s argument applies unchanged: the persistence claim — *the DM's chosen
 * table survives an app restart* — is asserted against a real DataStore on a real file in
 * `DmViewStoreTest`, because a fake map cannot fail it and so must not be where it is checked.
 * What this is for is the sign-out reap, where the interesting question is *which keys go*, and a
 * map answers that exactly as well as a file does.
 */
class FakeDmViewStore : DmViewStore {

    private val entries = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Every key currently held, for assertions about what a sign-out left behind. */
    val keys: Set<String> get() = entries.value.keys

    override fun members(accountKey: String): Flow<Set<String>> =
        entries.map { it[accountKey].orEmpty() }

    override suspend fun setMembers(accountKey: String, members: Set<String>) {
        entries.value = entries.value.toMutableMap().apply {
            // The real store drops the key rather than storing an empty set; a fake that kept
            // one would make `keys` disagree with the thing it stands in for.
            if (members.isEmpty()) remove(accountKey) else put(accountKey, members)
        }
    }

    /**
     * The **exact-key** delete the real store does, deliberately not a prefix filter.
     *
     * A fake that swept by prefix here would pass the very test that is supposed to catch the
     * bug `DataStoreDmViewStore.deleteForAccount` documents — `dm_view:server:acct1` being a
     * prefix of `dm_view:server:acct10`, so one account's sign-out taking another's table with
     * it. The fake has to be wrong in the same shape as the real thing, or it is not standing in
     * for it.
     */
    override suspend fun deleteForAccount(accountId: String) {
        entries.value = entries.value - DmViewStore.serverKey(accountId)
    }
}
