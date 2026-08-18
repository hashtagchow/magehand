package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.hashtagchow.magehand.core.data.account.ActiveAccountStore

/** In-memory [ActiveAccountStore] — DataStore needs a filesystem and a Context. */
class FakeActiveAccountStore(initial: String? = null) : ActiveAccountStore {

    private val state = MutableStateFlow(initial)

    override val activeAccountId: Flow<String?> = state

    override suspend fun setActiveAccountId(id: String?) {
        state.value = id
    }

    /** Test-only synchronous read. */
    fun current(): String? = state.value
}
