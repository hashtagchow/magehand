package com.hashtagchow.magehand.ui.screens.characterlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.characters.CharacterListSource
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState
import javax.inject.Inject

data class CharacterListUiState(
    val characters: List<CharacterSummary> = emptyList(),
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val source: CharacterListSource = CharacterListSource.NONE,
    val cachedAt: Long? = null,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    /** `"DungeonMaster · dicecloud.com"` — the top-bar subtitle. */
    val accountLabel: String? = null,
    /** The active account's origin; the FAB and "open in DiceCloud" need it. */
    val serverOrigin: String? = null,
) {
    /**
     * True only when the subscription has genuinely reported an empty list — not
     * while we are still waiting for it. Distinguishing the two is what stops
     * "You have no characters" flashing on every cold start.
     */
    val isEmpty: Boolean get() = characters.isEmpty() && source == CharacterListSource.LIVE

    val isLoadingFirstPage: Boolean
        get() = characters.isEmpty() && source == CharacterListSource.NONE && error == null
}

@HiltViewModel
class CharacterListViewModel @Inject constructor(
    private val characterListRepository: CharacterListRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    val uiState: StateFlow<CharacterListUiState> = combine(
        characterListRepository.state,
        accountRepository.activeAccount,
    ) { listState, account ->
        CharacterListUiState(
            characters = listState.characters,
            connection = listState.connection,
            source = listState.source,
            cachedAt = listState.cachedAt,
            error = listState.error,
            isRefreshing = listState.isRefreshing,
            accountLabel = account?.let { "${it.username} · ${it.serverUrl.removePrefix("https://")}" },
            serverOrigin = account?.serverUrl,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CharacterListUiState())

    /** Pull-to-refresh (docs/design/04-screens-ux.md §2). */
    fun refresh() = characterListRepository.refresh()

    private companion object {
        /**
         * Outlives a configuration change, so rotating the device does not drop the
         * `characterList` subscription and pay for a fresh one.
         */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
