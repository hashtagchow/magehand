package com.hashtagchow.magehand.ui.screens.characterlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.characters.CharacterListSource
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.data.settings.DmViewStore
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.ui.screens.dmview.DM_VIEW_MIN_MEMBERS
import com.hashtagchow.magehand.ui.screens.dmview.DmPickerState
import com.hashtagchow.magehand.ui.screens.dmview.resolveDmMembers
// Aliased for `CharacterHomeViewModel.togglePane`'s reason: this view model's own
// `toggleDmMember` is the *stateful* gesture, the imported one is the pure rule it applies.
// Same name in two layers is right; shadowing it silently is not.
import com.hashtagchow.magehand.ui.screens.dmview.toggleDmMember as nextDmMembers
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

    /**
     * Whether this account has enough live-subscribable characters for a DM view
     * (docs/design/14-large-screen-arc.md decisions 11 and 16).
     *
     * **Half** of the entry rule — the width half is `LocalExpandedWidth`, a composition local,
     * and `canOfferDmView` is where the two are joined. Split that way for FR-17's convention:
     * the state layer answers what it can measure, and the one width question in the app is asked
     * once, in the composable, from the local `WindowSizeGate` publishes.
     *
     * Server characters only. Local ones have no subscription to be live on, which is the entire
     * content of a DM card — counting them would offer a dashboard that opened onto "Not
     * available" (decision 19) for every row.
     */
    val hasDmViewCandidates: Boolean get() = characters.size >= DM_VIEW_MIN_MEMBERS
}

@HiltViewModel
class CharacterListViewModel @Inject constructor(
    private val characterListRepository: CharacterListRepository,
    private val accountRepository: AccountRepository,
    private val dmViewStore: DmViewStore,
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

    // --- FR-19's entry point (14 decisions 11 and 16) --------------------------------

    /**
     * The multi-select sheet's state, or `null` when the sheet is closed.
     *
     * A separate `StateFlow` from [uiState] rather than a field on it, for
     * `CharacterHomeViewModel.panes`'s reason: the sheet is transient chrome, and folding it into
     * the state every character card recomposes against would make ticking a checkbox invalidate
     * the whole list — including on the emissions a `characterList` sync causes.
     */
    private val _dmPicker = MutableStateFlow<DmPickerState?>(null)
    val dmPicker: StateFlow<DmPickerState?> = _dmPicker.asStateFlow()

    /**
     * Opens the picker, seeded from the **stored** table (decision 16).
     *
     * Seeded rather than blank so a DM running the same party next session confirms instead of
     * re-picking six people. The seed is resolved against the live list first — an id whose share
     * was withdrawn must not come back ticked, because the DM would then confirm a table with a
     * member that opens straight into decision 19's "Not available".
     */
    fun openDmPicker() {
        viewModelScope.launch {
            val accountId = accountRepository.activeAccount.filterNotNull().first().id
            val stored = dmViewStore.members(DmViewStore.serverKey(accountId)).first()
            val live = uiState.value.characters
            _dmPicker.value = DmPickerState(
                candidates = live,
                selected = resolveDmMembers(stored, live).toSet(),
            )
        }
    }

    fun dismissDmPicker() {
        _dmPicker.value = null
    }

    /**
     * A row in the picker was ticked or unticked.
     *
     * The rule — including decision 16's maximum of six, and the fact that a refused tick is a
     * *no-op* rather than a disabled row — is `toggleDmMember`'s, applied here and nowhere else.
     */
    fun toggleDmMember(creatureId: String) {
        val current = _dmPicker.value ?: return
        _dmPicker.value = current.copy(selected = nextDmMembers(current.selected, creatureId))
    }

    /**
     * The picker's confirm: persist the table, then let the caller navigate.
     *
     * ### Why the write is awaited before the callback
     *
     * Because `DmViewViewModel` reads the store **once, on entry** (decision 17), so a navigation
     * that raced the DataStore write would open the dashboard against the *previous* table — or,
     * on a first run, against nothing at all. Awaiting is what makes "the set is settled before
     * anything subscribes" true rather than usually true.
     *
     * A selection below the minimum is refused here as well as at the button, for `writable`'s
     * reason: the button's `enabled` is a frame of UI state, and this is the rule.
     */
    fun confirmDmSelection(onOpen: () -> Unit) {
        val picker = _dmPicker.value ?: return
        if (!picker.canConfirm) return
        viewModelScope.launch {
            val accountId = accountRepository.activeAccount.filterNotNull().first().id
            dmViewStore.setMembers(DmViewStore.serverKey(accountId), picker.selected)
            _dmPicker.value = null
            onOpen()
        }
    }

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
