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
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.model.Account
import javax.inject.Inject

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
    /**
     * FR-6's switch (docs/design/09-local-characters.md decision 9).
     *
     * Seeded from [AppSettingsStore.DEFAULT_SHOW_TOGGLES] rather than from a literal here, so
     * the switch cannot render "on" for the frame before the first DataStore read lands and
     * then flick off under the user's thumb.
     */
    val showToggles: Boolean = AppSettingsStore.DEFAULT_SHOW_TOGGLES,
)

/**
 * Screen 6 (docs/design/04-screens-ux.md §6) — WP5 ships the account switcher and
 * sign-out. Per-character accent colour and portrait override are WP8. FR-6 adds the
 * app-level "Show toggles on tracker" switch.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val appSettingsStore: AppSettingsStore,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        accountRepository.accounts,
        accountRepository.activeAccountId,
        appSettingsStore.showToggles,
    ) { accounts, activeId, showToggles -> SettingsUiState(accounts, activeId, showToggles) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * FR-6. Writes straight through and lets the flow above bring the new value back, rather
     * than holding a second copy in the UI state: the switch then shows what is *stored*,
     * which is what every tracker in the app is reading.
     */
    fun setShowToggles(value: Boolean) {
        viewModelScope.launch { appSettingsStore.setShowToggles(value) }
    }

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
