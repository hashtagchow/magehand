package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore

/**
 * An in-memory [SelectedRollStore], for tests about something *else* that happen to construct
 * a collaborator needing one.
 *
 * The persistence claim itself is asserted against a real DataStore on a real file in
 * `SelectedRollStoreTest` — a fake cannot fail "it survives a restart", so it must not be
 * where that claim is checked. What this is for is the sign-out sweep, where the interesting
 * question is *which keys go*, and a map answers that exactly as well as a file does.
 */
class FakeSelectedRollStore : SelectedRollStore {

    private val entries = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Every key currently held, for assertions about what a sweep left behind. */
    val keys: Set<String> get() = entries.value.keys

    override fun selectedRollId(characterKey: String): Flow<String?> =
        entries.map { it[characterKey] }

    override suspend fun setSelectedRollId(characterKey: String, rollId: String?) {
        entries.value = entries.value.toMutableMap().apply {
            if (rollId == null) remove(characterKey) else put(characterKey, rollId)
        }
    }

    override suspend fun deleteForAccount(accountId: String) {
        val prefix = SelectedRollStore.serverKey(accountId, "")
        entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
    }
}
