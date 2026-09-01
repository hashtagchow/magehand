package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.InventorySort

/**
 * An in-memory [InventoryLayoutStore], for tests about something *else* that happen to
 * construct a collaborator needing one.
 *
 * [FakeEquippableOverrideStore]'s argument applies unchanged: the persistence claim — *the
 * player's arrangement survives an app restart* — is asserted against a real DataStore on a real
 * file in `InventoryLayoutStoreTest`, because a fake map cannot fail it and so must not be where
 * it is checked. What this is for is the two reaping paths, where the interesting question is
 * *which keys go*, and a map answers that exactly as well as a file does.
 */
class FakeInventoryLayoutStore : InventoryLayoutStore {

    private val entries = MutableStateFlow<Map<String, List<InventoryLayoutEntry>>>(emptyMap())

    private val sorts = MutableStateFlow<Map<String, InventorySort>>(emptyMap())

    /**
     * Every key currently held, for assertions about what a sweep left behind.
     *
     * The **union** of the two maps since FR-35, because a reap that dropped a character's order
     * and left its sort behind would be exactly the leak these assertions exist to catch, and a
     * `keys` that only looked at one map could not see it.
     */
    val keys: Set<String> get() = entries.value.keys + sorts.value.keys

    override fun layout(characterKey: String): Flow<List<InventoryLayoutEntry>> =
        entries.map { it[characterKey].orEmpty() }

    override suspend fun setLayout(characterKey: String, layout: List<InventoryLayoutEntry>) {
        entries.value = entries.value.toMutableMap().apply {
            // The real store drops the key rather than storing an empty list; a fake that kept
            // one would make `keys` disagree with the thing it stands in for.
            if (layout.isEmpty()) remove(characterKey) else put(characterKey, layout)
        }
    }

    override fun sort(characterKey: String): Flow<InventorySort> =
        sorts.map { it[characterKey] ?: InventorySort.DEFAULT }

    override suspend fun setSort(characterKey: String, sort: InventorySort) {
        sorts.value = sorts.value.toMutableMap().apply {
            // The real store removes both keys rather than storing the default; a fake that kept
            // one would make `keys` disagree with the thing it stands in for.
            if (sort.isDefault) remove(characterKey) else put(characterKey, sort)
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        entries.value = entries.value - characterKey
        sorts.value = sorts.value - characterKey
    }

    override suspend fun deleteForAccount(accountId: String) {
        val prefix = InventoryLayoutStore.serverKey(accountId, "")
        entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
        sorts.value = sorts.value.filterKeys { !it.startsWith(prefix) }
    }
}
