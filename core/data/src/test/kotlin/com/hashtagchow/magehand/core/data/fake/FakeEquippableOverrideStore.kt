package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore

/**
 * An in-memory [EquippableOverrideStore], for tests about something *else* that happen to
 * construct a collaborator needing one.
 *
 * [FakeSelectedRollStore]'s argument applies unchanged: the persistence claim is asserted
 * against a real DataStore on a real file in `EquippableOverrideStoreTest`, because a fake map
 * cannot fail "it survives a restart" and so must not be where that claim is checked. What this
 * is for is the two reaping paths, where the interesting question is *which keys go* — and a
 * map answers that exactly as well as a file does.
 */
class FakeEquippableOverrideStore : EquippableOverrideStore {

    private val entries = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Every key currently held, for assertions about what a sweep left behind. */
    val keys: Set<String> get() = entries.value.keys

    override fun overrides(characterKey: String): Flow<Set<String>> =
        entries.map { it[characterKey].orEmpty() }

    override suspend fun setOverridden(
        characterKey: String,
        propertyId: String,
        overridden: Boolean,
    ) {
        entries.value = entries.value.toMutableMap().apply {
            val next = this[characterKey].orEmpty().let {
                if (overridden) it + propertyId else it - propertyId
            }
            // The real store drops the key rather than storing an empty set; a fake that kept
            // one would make `keys` disagree with the thing it stands in for.
            if (next.isEmpty()) remove(characterKey) else put(characterKey, next)
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        entries.value = entries.value - characterKey
    }

    override suspend fun deleteForAccount(accountId: String) {
        val prefix = EquippableOverrideStore.serverKey(accountId, "")
        entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
    }
}
