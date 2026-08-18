package com.hashtagchow.magehand.ui.webview

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.auth.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the [SheetSession] for the active account.
 *
 * Reads [TokenStore] directly rather than going through
 * `AccountRepository.tokenFor`, because that method returns only the token string
 * and the WebView also needs the expiry: Meteor keeps `Meteor.loginTokenExpires`
 * alongside the token and drops a token whose recorded expiry has passed
 * (docs/design/05-security.md §"WebView SSO"). Same layer, same encrypted store,
 * one extra field — widening the repository interface for one caller would be the
 * worse trade.
 */
@Singleton
class SheetSessionFactory @Inject constructor(
    private val accountRepository: AccountRepository,
    private val tokenStore: TokenStore,
) {

    /**
     * Emits a session whenever the active account (or its token) changes, and
     * `null` when there is no usable account.
     *
     * @param path builds the in-origin path to land on, e.g. `"/character/$id"`.
     */
    fun sessions(path: (serverOrigin: String) -> String): Flow<SheetSession?> =
        accountRepository.activeAccount.map { account ->
            if (account == null) return@map null
            val stored = tokenStore.read(account.id) ?: return@map null
            val origin = account.serverUrl.trimEnd('/')
            SheetSession(
                serverOrigin = origin,
                userId = account.userId,
                token = stored.token,
                tokenExpiresAtEpochMillis = stored.expiresAtEpochMillis,
                targetUrl = origin + path(origin),
            )
        }

    companion object {
        /**
         * DiceCloud's own sheet route. Verified against the served client bundle on
         * 2026-08-17: the router declares `/character/:id` and
         * `/character/:id/:urlName` (docs/verification/WP5.md §3).
         */
        fun characterPath(creatureId: String): String = "/character/$creatureId"

        /**
         * The FAB target (docs/design/04-screens-ux.md §2, "New character").
         *
         * There is **no** `/character/new` route — creation is a dialog on the PWA's
         * own character-list page, so that page is the creator entry point. Verified
         * from the router's full path list, same probe.
         */
        const val CREATOR_PATH: String = "/character-list"
    }
}
