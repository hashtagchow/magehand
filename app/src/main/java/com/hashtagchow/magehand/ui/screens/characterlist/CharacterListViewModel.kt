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
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.LocalCharacter
import javax.inject.Inject

/**
 * One card in the "On this device" section (docs/design/09-local-characters.md decision 3).
 *
 * A separate type from [CharacterSummary] rather than a conversion into it, for 09 decision
 * 1's reason: a local character has no `creatureId`, no owner and no portrait, and mapping it
 * onto the DiceCloud type would mean inventing three values so that a card could ignore all
 * three. What the list actually needs from it is a name, a subtitle and an id to navigate on.
 *
 * @param subtitle `"Level 5"`, or empty when the player left the level blank — the same
 *   "empty means the line is absent" contract [CharacterSummary.subtitle] has, so both card
 *   composables answer the question the same way.
 */
data class LocalCharacterCardState(
    val id: String,
    val name: String,
    val subtitle: String,
) {
    /** The portrait monogram, matching [CharacterSummary.monogram]'s shape. */
    val monogram: String get() = name.trim().take(1).uppercase()
}

/** `LocalCharacter` → the card. Pure, so `CharacterListUiStateTest` can assert the subtitle. */
fun LocalCharacter.toCardState(): LocalCharacterCardState = LocalCharacterCardState(
    id = id,
    name = name,
    subtitle = level?.let { "Level $it" }.orEmpty(),
)

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
    /** 09 decision 3's "On this device" section. */
    val localCharacters: List<LocalCharacterCardState> = emptyList(),
    /**
     * Whether an account is signed in at all — **`null` until we know**.
     *
     * 09 decision 3 is explicit that "the list screen must render with zero accounts", and
     * almost every rule below turns on this rather than on the character list being empty: a
     * signed-out user has no subscription, so there is nothing to be connecting to, nothing to
     * refresh, and no DiceCloud creator to offer.
     *
     * ### Why this is nullable rather than a `false` default
     *
     * Account resolution is a **disk read**, so there is a real third state and the flow's
     * seed is in it: a plain `false` made "no account is signed in" and "we have not looked
     * yet" the same value, and every rule below then answered the signed-out question about a
     * signed-in user. A signed-in cold start therefore painted "You have no characters" and
     * the local-mode FAB for as long as Room took to answer — worst on the first launch after
     * an upgrade, where that read also runs `MIGRATION_2_3`. Three states cost one `?`; the
     * flash cost the user their character list.
     *
     * In production this is `null` only in [CharacterListViewModel]'s `stateIn` seed. Every
     * emission the combine produces has looked, and says `true` or `false`.
     */
    val hasAccount: Boolean? = null,
) {
    /**
     * True only when there is genuinely nothing to show — not while we are still waiting.
     * Distinguishing the two is what stops "You have no characters" flashing on every cold
     * start.
     *
     * Three clauses, one per way of "still waiting". Local characters count as content, so a
     * signed-out user with one is not looking at an empty list. With **no account**, `source`
     * never leaves `NONE` (nothing subscribes), so the `source == LIVE` clause alone would
     * have made "empty" unreachable and left the spinner below running forever. And an
     * **unresolved** account is not a signed-out one — see [hasAccount].
     */
    val isEmpty: Boolean
        get() = hasAccount != null &&
            characters.isEmpty() &&
            localCharacters.isEmpty() &&
            (source == CharacterListSource.LIVE || !hasAccount)

    /**
     * The spinner. Covers both waits: the account itself is still being read, or an account
     * is signed in and its first page has not arrived. See [isEmpty] for the third state.
     */
    val isLoadingFirstPage: Boolean
        get() = hasAccount == null ||
            (
                hasAccount &&
                    characters.isEmpty() &&
                    localCharacters.isEmpty() &&
                    source == CharacterListSource.NONE &&
                    error == null
                )

    /**
     * Whether the connection strip renders.
     *
     * Signed out, there is no connection: the strip would sit at the top of the screen saying
     * "Connecting…" forever, about a socket that is not being opened. That is a worse lie than
     * saying nothing, and 09 decision 8's reasoning for the tracker's dot is the same
     * reasoning — connection state is meaningless when there is no server. Unresolved is not
     * a connection either: `== true`, not `!= false`.
     */
    val showsConnection: Boolean get() = hasAccount == true

    /** Pull-to-refresh only means something when there is a subscription to refresh. */
    val canRefresh: Boolean get() = hasAccount == true

    /**
     * Whether the FAB renders at all.
     *
     * The FAB's *meaning* depends on [hasAccount] — creator menu when signed in, straight to
     * the local form when signed out — so before the account resolves there is no honest
     * button to draw. Rendering one anyway is what made a signed-in cold start flash the
     * local-mode FAB, and the flash is not cosmetic: it is tappable, and the tap navigates
     * somewhere the user did not ask to go.
     */
    val showsCreateAffordance: Boolean get() = hasAccount != null
}

@HiltViewModel
class CharacterListViewModel @Inject constructor(
    private val characterListRepository: CharacterListRepository,
    accountRepository: AccountRepository,
    localCharacterRepository: LocalCharacterRepository,
) : ViewModel() {

    val uiState: StateFlow<CharacterListUiState> = combine(
        characterListRepository.state,
        accountRepository.activeAccount,
        localCharacterRepository.observeAll(),
    ) { listState, account, local ->
        CharacterListUiState(
            characters = listState.characters,
            connection = listState.connection,
            source = listState.source,
            cachedAt = listState.cachedAt,
            error = listState.error,
            isRefreshing = listState.isRefreshing,
            accountLabel = account?.let { "${it.username} · ${it.serverUrl.removePrefix("https://")}" },
            serverOrigin = account?.serverUrl,
            localCharacters = local.map { it.toCardState() },
            hasAccount = account != null,
        )
        // The seed is the *unresolved* state (`hasAccount = null` by default), not a
        // signed-out one: none of the three flows has emitted yet, so claiming "no account"
        // here would be the screen answering a question it has not asked. See
        // [CharacterListUiState.hasAccount].
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
