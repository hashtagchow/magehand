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
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
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
    localCharacterRepository: LocalCharacterRepository,
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
            when {
                account != null -> StartDestination(
                    state = StartState.MAIN,
                    initialCreatureId = characterCache.lastOpenedCreatureId(account.id),
                )

                // FR-5 (docs/design/09-local-characters.md decision 3). Without this, a
                // player who created a local character while signed out would be sent to the
                // login screen on every cold start, with their character reachable only by
                // pressing "continue without an account" again — the app would look like it
                // had lost it. No `initialCreatureId`: 04's "open the last-used character"
                // reads `characters.lastOpenedAt`, which is account-keyed and has no local
                // equivalent (a local `lastOpenedAt` is a schema change, and 09 makes none).
                localCharacterRepository.count() > 0 -> StartDestination(StartState.MAIN)

                else -> StartDestination(StartState.LOGIN)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartDestination())
}
