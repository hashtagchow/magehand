package com.hashtagchow.magehand.ui.testing

import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.core.model.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The two collaborators the *screen-level* composables need, as hand-written doubles.
 *
 * `SettingsScreen` and `CredentialsScreen` take their view model through `hiltViewModel()`, so a
 * test that wants to render them constructs the real view model with fakes and passes it in — no
 * Hilt graph, matching decision 2's rule for everything else. These are the smallest fakes that
 * satisfy the two constructors involved; `CharacterHomeViewModelTest`'s richer set stays where it
 * is, fitted to that class's seams (decision 3 declined a shared `:core:testing` module, and this
 * is the shape that decision takes in practice — narrow doubles beside the tests that need them).
 *
 * ### Store safety
 *
 * [FakeAccounts] seeds `dicecloud.com`. Design 19 decision 9 is unconditional about this: goldens
 * are committed repo files, and the private host, its token and the DM user id must never reach
 * one. `dicecloud.com` is also what the app itself defaults to
 * (`CredentialsViewModel.DEFAULT_SERVER_URL`), so the settings golden shows a real install's
 * appearance rather than a scrubbed one.
 */
class FakeAccounts(seed: List<Account> = emptyList()) : AccountRepository {
    override val accounts = MutableStateFlow(seed)
    override val activeAccountId = MutableStateFlow(seed.firstOrNull()?.id)
    override val activeAccount = MutableStateFlow(seed.firstOrNull())

    override suspend fun getAccount(accountId: String): Account? =
        accounts.value.firstOrNull { it.id == accountId }

    override suspend fun addAccount(
        serverUrlInput: String,
        usernameOrEmail: String,
        password: String,
    ): Result<Account> = error("no test here signs in for real")

    override suspend fun adoptToken(
        serverUrlInput: String,
        userId: String,
        username: String,
        token: String,
        tokenExpiresAt: Long?,
    ): Result<Account> = error("no test here signs in for real")

    override suspend fun reLogin(accountId: String, password: String): Result<Account> =
        error("no test here signs in for real")

    override suspend fun setActiveAccount(accountId: String) {
        activeAccountId.value = accountId
    }

    override suspend fun signOut(accountId: String) = Unit
    override suspend fun tokenFor(accountId: String): String? = null
}

/** FR-6's switch and FR-18's scale, in memory. Writeable, so a rendered toggle can move them. */
class FakeSettings(
    showToggles: Boolean = AppSettingsStore.DEFAULT_SHOW_TOGGLES,
    uiScale: UiScale = UiScale.DEFAULT,
) : AppSettingsStore {
    private val toggles = MutableStateFlow(showToggles)
    private val scale = MutableStateFlow(uiScale)

    override val showToggles: Flow<Boolean> = toggles
    override val uiScale: Flow<UiScale> = scale

    override suspend fun setShowToggles(value: Boolean) {
        toggles.value = value
    }

    override suspend fun setUiScale(value: UiScale) {
        scale.value = value
    }
}
