package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.settings.PaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneSurface

/**
 * An in-memory [PaneLayoutStore], for tests about something *else* that happen to construct a
 * collaborator needing one.
 *
 * [FakeInventoryLayoutStore]'s argument applies unchanged: the persistence claim — *the player's
 * chosen panes survive an app restart* — is asserted against a real DataStore on a real file in
 * `PaneLayoutStoreTest`, because a fake map cannot fail it and so must not be where it is
 * checked. What this is for is the two reaping paths, where the interesting question is *which
 * keys go*, and a map answers that exactly as well as a file does.
 */
class FakePaneLayoutStore : PaneLayoutStore {

    private val entries = MutableStateFlow<Map<String, Set<PaneSurface>>>(emptyMap())

    /** Every key currently held, for assertions about what a sweep left behind. */
    val keys: Set<String> get() = entries.value.keys

    override fun panes(characterKey: String): Flow<Set<PaneSurface>> =
        entries.map { it[characterKey].orEmpty() }

    override suspend fun setPanes(characterKey: String, panes: Set<PaneSurface>) {
        entries.value = entries.value.toMutableMap().apply {
            // The real store drops the key rather than storing an empty set; a fake that kept
            // one would make `keys` disagree with the thing it stands in for.
            if (panes.isEmpty()) remove(characterKey) else put(characterKey, panes)
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        entries.value = entries.value - characterKey
    }

    override suspend fun deleteForAccount(accountId: String) {
        val prefix = PaneLayoutStore.serverKey(accountId, "")
        entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
    }
}
