package com.hashtagchow.magehand.core.data.account

import kotlinx.coroutines.flow.Flow
import com.hashtagchow.magehand.core.data.api.ApiException
import com.hashtagchow.magehand.core.model.Account

/**
 * The app's single entry point for "who is signed in".
 *
 * Every mutating call returns [Result]; a failure always carries an
 * [ApiException] so callers can branch on wrong-password versus wrong-server
 * without string matching.
 */
interface AccountRepository {

    /** All accounts, most recently used first. Re-emits on every add/remove/select. */
    val accounts: Flow<List<Account>>

    /** The selected account's id, or `null` when none is selected. */
    val activeAccountId: Flow<String?>

    /** The selected account, or `null` when none is selected (or it was just removed). */
    val activeAccount: Flow<Account?>

    suspend fun getAccount(accountId: String): Account?

    /**
     * Sign in and persist the account.
     *
     * Normalizes [serverUrlInput], calls `/api/login`, stores the resume token in
     * the encrypted store, writes the `accounts` row and makes the account active.
     * Signing in again as the same user on the same server updates that account in
     * place (new token, same local id) rather than creating a duplicate.
     *
     * [password] is used for exactly one request and never persisted.
     */
    suspend fun addAccount(
        serverUrlInput: String,
        usernameOrEmail: String,
        password: String,
    ): Result<Account>

    /**
     * Add (or update) an account from an **already-minted resume token**, with no
     * password anywhere in the call.
     *
     * Same post-conditions as [addAccount] — token in the encrypted store, row in
     * `accounts`, account made active, re-adoption updates in place — minus the
     * `/api/login` round trip.
     *
     * Why it exists: WP5's device probe has to sign the app in on an emulator, and
     * this repo holds a dev **token**, never a password (docs/design/07-build-plan.md
     * rules out live password logins). The only caller in the tree is the
     * debug-only seeder `com.hashtagchow.magehand.debug.DebugSeedActivity`, which
     * is compiled into `debug` builds only — see docs/verification/WP5.md §5.
     *
     * The token is **not** validated here: there is no cheap "whoami" on the REST
     * surface, and a bad token fails loudly anyway — the DDP connection parks in
     * [com.hashtagchow.magehand.core.model.ConnectionState.AUTH_FAILED] and the
     * character list stays empty. [userId] and [username] are therefore
     * caller-supplied; nothing security-relevant hangs off them, because ownership
     * badges compare against the *live* `DdpClient.userId`, not this row.
     *
     * Fails with [ApiException.InvalidServerUrl] if [serverUrlInput] does not
     * normalize — the https-only rule applies here exactly as it does to login.
     */
    suspend fun adoptToken(
        serverUrlInput: String,
        userId: String,
        username: String,
        token: String,
        tokenExpiresAt: Long? = null,
    ): Result<Account>

    /**
     * Re-authenticate an existing account whose token expired or was evicted, and
     * replace the stored token. Fails with [ApiException.AccountMismatch] if the
     * credentials belong to a different Meteor user, so a typo can never silently
     * repoint an account row at someone else's character list.
     */
    suspend fun reLogin(accountId: String, password: String): Result<Account>

    /** Selects [accountId] and bumps its `lastUsedAt`. No-op if it doesn't exist. */
    suspend fun setActiveAccount(accountId: String)

    /**
     * Forget an account: delete its token, then its row. If it was active, the
     * next most-recently-used account becomes active (or none).
     *
     * Note this is a *local* sign-out; the resume token stays valid server-side
     * (docs/design/05-security.md — no public revocation API on `master`).
     */
    suspend fun signOut(accountId: String)

    /** The resume token for [accountId], or `null`. Callers must not log or persist it. */
    suspend fun tokenFor(accountId: String): String?
}
