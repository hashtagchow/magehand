package com.hashtagchow.magehand.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.model.Account
import javax.inject.Inject

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
)

/**
 * Screen 6 (docs/design/04-screens-ux.md §6) — WP5 ships the account switcher and
 * sign-out. Per-character accent colour and portrait override are WP8.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        accountRepository.accounts,
        accountRepository.activeAccountId,
    ) { accounts, activeId -> SettingsUiState(accounts, activeId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun switchTo(accountId: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(accountId) }
    }

    /**
     * Local sign-out. docs/design/05-security.md is explicit that DiceCloud's
     * `master` branch exposes no token-revocation API, so this clears the token
     * from this device and nothing else — the UI says so.
     *
     * Dropping the account is also what closes the DDP socket: `DdpConnectionManager`
     * follows `activeAccount`, so there is no second teardown path to forget.
     */
    fun signOut(accountId: String, onNoAccountsLeft: () -> Unit) {
        viewModelScope.launch {
            accountRepository.signOut(accountId)
            if (accountRepository.accounts.first().isEmpty()) onNoAccountsLeft()
        }
    }
}
