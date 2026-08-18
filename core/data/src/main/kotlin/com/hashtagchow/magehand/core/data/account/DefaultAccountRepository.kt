package com.hashtagchow.magehand.core.data.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.api.ApiException
import com.hashtagchow.magehand.core.data.api.DiceCloudApi
import com.hashtagchow.magehand.core.data.auth.StoredToken
import com.hashtagchow.magehand.core.data.auth.TokenStore
import com.hashtagchow.magehand.core.data.auth.WebViewSessionStore
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import com.hashtagchow.magehand.core.data.db.AccountDao
import com.hashtagchow.magehand.core.data.db.AccountEntity
import com.hashtagchow.magehand.core.data.db.ThemePrefDao
import com.hashtagchow.magehand.core.data.db.TrackerPrefDao
import com.hashtagchow.magehand.core.data.db.toDomain
import com.hashtagchow.magehand.core.data.server.ServerUrlResult
import com.hashtagchow.magehand.core.data.server.normalizeServerUrl
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.model.Account
import java.util.UUID

/**
 * The production [AccountRepository].
 *
 * Everything from [snapshotStore] down exists for one method — [signOut]. That is
 * deliberate: sign-out is the only place that knows an account is *ending*, and every
 * per-account store therefore has to be reachable from here or its rows outlive the
 * account (see [signOut] for why "outlive" means "forever").
 *
 * [now] and [newId] are injected rather than called inline so tests get
 * deterministic timestamps and ids without a clock-mocking framework.
 */
class DefaultAccountRepository(
    private val api: DiceCloudApi,
    private val accountDao: AccountDao,
    private val tokenStore: TokenStore,
    private val activeAccountStore: ActiveAccountStore,
    private val webViewSessionStore: WebViewSessionStore,
    private val snapshotStore: SnapshotStore,
    private val characterCache: CharacterCache,
    private val trackerPrefDao: TrackerPrefDao,
    private val themePrefDao: ThemePrefDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AccountRepository {

    override val accounts: Flow<List<Account>> =
        accountDao.observeAll().map { rows -> rows.map(AccountEntity::toDomain) }

    override val activeAccountId: Flow<String?> = activeAccountStore.activeAccountId

    override val activeAccount: Flow<Account?> =
        combine(accounts, activeAccountId) { all, id ->
            // A dangling id (account removed on another screen) reads as "none",
            // never as a crash.
            all.firstOrNull { it.id == id }
        }

    override suspend fun getAccount(accountId: String): Account? =
        accountDao.findById(accountId)?.toDomain()

    override suspend fun addAccount(
        serverUrlInput: String,
        usernameOrEmail: String,
        password: String,
    ): Result<Account> = runApi {
        val origin = when (val normalized = normalizeServerUrl(serverUrlInput)) {
            is ServerUrlResult.Valid -> normalized.origin
            is ServerUrlResult.Invalid -> throw ApiException.InvalidServerUrl(normalized.problem)
        }

        val session = api.login(origin, usernameOrEmail, password)

        persist(
            origin = origin,
            userId = session.userId,
            // Keep whatever identifier the user just used to sign in — it is what
            // they will recognise in the account switcher.
            username = usernameOrEmail,
            token = session.token,
            tokenExpiresAt = session.tokenExpiresAt,
        )
    }

    override suspend fun adoptToken(
        serverUrlInput: String,
        userId: String,
        username: String,
        token: String,
        tokenExpiresAt: Long?,
    ): Result<Account> = runApi {
        val origin = when (val normalized = normalizeServerUrl(serverUrlInput)) {
            is ServerUrlResult.Valid -> normalized.origin
            is ServerUrlResult.Invalid -> throw ApiException.InvalidServerUrl(normalized.problem)
        }
        persist(origin, userId, username, token, tokenExpiresAt)
    }

    /**
     * The shared tail of [addAccount] and [adoptToken]: store the token, upsert the
     * row, select the account. Extracted so the two entry points cannot drift.
     */
    private suspend fun persist(
        origin: String,
        userId: String,
        username: String,
        token: String,
        tokenExpiresAt: Long?,
    ): Account {
        val timestamp = now()
        val existing = accountDao.findByServerAndUser(origin, userId)
        val entity = AccountEntity(
            id = existing?.id ?: newId(),
            serverUrl = origin,
            userId = userId,
            username = username,
            addedAt = existing?.addedAt ?: timestamp,
            lastUsedAt = timestamp,
        )

        // Token first: an account row whose token is missing is a broken account,
        // whereas an orphan token is merely garbage that signOut/clear will reap.
        tokenStore.save(entity.id, StoredToken(token, tokenExpiresAt))
        accountDao.upsert(entity)
        activeAccountStore.setActiveAccountId(entity.id)

        return entity.toDomain()
    }

    override suspend fun reLogin(accountId: String, password: String): Result<Account> = runApi {
        val existing = accountDao.findById(accountId)
            ?: throw ApiException.NotFound("account $accountId")

        val session = api.login(existing.serverUrl, existing.username, password)
        if (session.userId != existing.userId) {
            // Right server, wrong person. Do not touch the stored token.
            throw ApiException.AccountMismatch()
        }

        // Replace, don't add: same account id, so the new token overwrites the old
        // one under the same key and nothing else in the app has to be told.
        tokenStore.save(existing.id, StoredToken(session.token, session.tokenExpiresAt))
        val updated = existing.copy(lastUsedAt = now())
        accountDao.upsert(updated)

        updated.toDomain()
    }

    override suspend fun setActiveAccount(accountId: String) {
        if (accountDao.findById(accountId) == null) return
        accountDao.touch(accountId, now())
        activeAccountStore.setActiveAccountId(accountId)
    }

    /**
     * Deletes one account's local state, in one path.
     *
     * "Every local trace" with one honest exception: [webViewSessionStore] is keyed by
     * **server origin**, not by account, because that is the only key a WebView's
     * `localStorage` has. Two accounts on the same server therefore share one entry, and
     * signing either of them out clears the SSO session for both — the other account is
     * still signed in to the app, it just has to log in again inside the WebView. Nothing
     * leaks (the wrong direction would be the bug), and the alternative — leaving a
     * rejected account's token in the WebView because a sibling account exists — is worse
     * than the re-login. Pre-existing behaviour, stated here rather than fixed.
     *
     * The order is the file's own "token first" argument (see [persist]) carried one step
     * further out: **the `accounts` row is deleted last**. `accounts.id` is a UUID minted
     * per row, so signing out and back in mints a *new* id — which means anything still
     * keyed to the old id once the row is gone is unreachable by construction. No sweep
     * can find it (`SnapshotDao.evictBeyond` and every `deleteForAccount` are
     * per-account, and there is no account left to pass), so a sign-out/sign-in loop
     * would grow the database without bound, ~100 KB of gzipped sheet at a time. Deleting
     * the row last means a crash anywhere above it leaves an account that can still be
     * signed out again.
     *
     * That the cached sheet goes too is also a promise, not a nicety: docs/STORE-RELEASE.md
     * answers Play's "way to request data deletion" question with "sign out", and a
     * sign-out that leaves a full character sheet on disk does not answer it.
     */
    override suspend fun signOut(accountId: String) {
        // Read the row before deleting it: the WebView's copy of the token is
        // origin-scoped, and after `deleteById` there is nothing left to ask.
        val serverOrigin = accountDao.findById(accountId)?.serverUrl

        // Token first, so a crash between the two steps can never leave a token
        // that no account row points at.
        tokenStore.delete(accountId)

        // Then the per-account local stores, while the row that names them still exists.
        snapshotStore.clearAccount(accountId)
        characterCache.clear(accountId)
        trackerPrefDao.deleteForAccount(accountId)
        themePrefDao.deleteForAccount(accountId)

        // The token also rests in the WebView's localStorage, because that is how
        // Meteor SSO works (docs/design/05-security.md §"WebView SSO"). WP5 found it
        // surviving sign-out; clearing it here — inside the one sign-out path, not
        // in a screen that could forget — is WP8's fix. Origin-scoped, so it takes a
        // same-server sibling account's WebView session with it; see the KDoc.
        if (serverOrigin != null) webViewSessionStore.clearFor(serverOrigin)

        accountDao.deleteById(accountId)

        val remaining = accountDao.getAll()
        val currentlyActive = activeAccountStore.activeAccountId.firstOrNull()
        if (currentlyActive == accountId || currentlyActive == null) {
            activeAccountStore.setActiveAccountId(remaining.firstOrNull()?.id)
        }
    }

    override suspend fun tokenFor(accountId: String): String? =
        tokenStore.read(accountId)?.token

    /**
     * Runs [block], converting the typed [ApiException] hierarchy into a failed
     * [Result]. Anything that is *not* an [ApiException] is a programming error
     * and is left to propagate.
     */
    private inline fun <T> runApi(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: ApiException) {
            Result.failure(e)
        }
}
