package com.hashtagchow.magehand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import javax.inject.Inject

/**
 * Where the app opens on a cold start (docs/design/04-screens-ux.md, "Start
 * destination").
 */
enum class StartState {
    /** Still reading the account store. Nothing is drawn but a spinner. */
    RESOLVING,

    /** No account — the login graph. */
    LOGIN,

    /** An account exists — the main graph. */
    MAIN,
}

/**
 * @param initialCreatureId the character to open straight onto, per 04's "last-used
 *   character's Tracker". `null` means "stop at the character list", which is both the
 *   first-run case and the case where the account has never opened a character.
 */
data class StartDestination(
    val state: StartState = StartState.RESOLVING,
    val initialCreatureId: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    accountRepository: AccountRepository,
    characterCache: CharacterCache,
) : ViewModel() {

    /**
     * Resolved **once**, from the first emission, and then frozen.
     *
     * Deliberately not a live mapping of `activeAccount`: `NavHost` builds its
     * graph from `startDestination`, so a value that changed on sign-out would
     * rebuild the graph underneath a navigation that is already in flight. Sign-in
     * and sign-out navigate explicitly; this only answers "where do we begin".
     *
     * WP5 deferred 04's "last-used character's Tracker" because it needs
     * `characters.lastOpenedAt`, which was WP4's schema v2. **WP6 discharges that**: the
     * character cache is Room-backed now, so the last-opened creature id is a real query.
     * It is still resolved once and frozen, for the same reason.
     */
    val startDestination: StateFlow<StartDestination> = flow {
        val account = accountRepository.activeAccount.first()
        emit(
            if (account == null) {
                StartDestination(StartState.LOGIN)
            } else {
                StartDestination(
                    state = StartState.MAIN,
                    initialCreatureId = characterCache.lastOpenedCreatureId(account.id),
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartDestination())
}
