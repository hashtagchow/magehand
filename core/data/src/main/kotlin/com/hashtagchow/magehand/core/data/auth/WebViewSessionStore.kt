package com.hashtagchow.magehand.core.data.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The *other* place a resume token rests once the Sheet tab has been opened.
 *
 * DiceCloud is a Meteor PWA and Meteor reads `Meteor.loginToken` out of the
 * origin's `localStorage` at boot, so the SSO mechanism in
 * docs/design/05-security.md §"WebView SSO" has to write the token there in the
 * clear. WP5's device probe found it on disk:
 *
 * ```
 * …/app_webview/Default/Local Storage/leveldb/000003.log
 * ```
 *
 * WP5 flagged that sign-out cleared [TokenStore] and nothing else, so the copy in
 * the WebView outlived the account. This interface is the fix: it is part of
 * `AccountRepository.signOut`, on the same "one teardown path, not two" principle
 * the DDP socket already follows.
 */
interface WebViewSessionStore {

    /**
     * Erase everything the WebView is holding for [serverOrigin] — DOM storage
     * (which is where the token is), cookies, and cached HTTP auth.
     *
     * Implementations must tolerate being called when no WebView has ever been
     * created: signing out without ever opening the Sheet tab is the common case.
     */
    suspend fun clearFor(serverOrigin: String)
}

/**
 * Production [WebViewSessionStore].
 *
 * Every `android.webkit` API used here is main-thread-only, hence the dispatcher
 * hop. Three separate erasures, because they are three separate stores:
 *
 * 1. [WebStorage.deleteOrigin] — the origin-scoped DOM-storage delete, which is
 *    the targeted removal of the injected `Meteor.loginToken`.
 * 2. [WebStorage.deleteAllData] — the belt-and-braces sweep. `deleteOrigin` wants
 *    the origin string in the exact form the WebView recorded it, and a stale
 *    account row can disagree (trailing slash, an origin that has since been
 *    re-normalized). Since the app dedicates its WebView to one account's server
 *    at a time, deleting everything costs nothing and cannot miss.
 * 3. [CookieManager] + [WebViewDatabase] — Meteor does not use a session cookie,
 *    but a self-hosted deployment behind an authenticating proxy might, and
 *    "sign-out leaves no session behind" is the property being bought.
 *
 * Loading a page in the origin to run `localStorage.clear()` was rejected: it
 * needs the network at exactly the moment the user is leaving, and it would put a
 * live WebView on the sign-out path. Deleting the storage the WebView keeps is the
 * same outcome without either.
 */
class AndroidWebViewSessionStore(
    private val context: Context,
) : WebViewSessionStore {

    override suspend fun clearFor(serverOrigin: String) = withContext(Dispatchers.Main) {
        val storage = WebStorage.getInstance()
        storage.deleteOrigin(serverOrigin.trimEnd('/'))
        storage.deleteAllData()

        val cookies = CookieManager.getInstance()
        suspendCancellableCoroutine { continuation ->
            cookies.removeAllCookies { continuation.resume(Unit) }
        }
        cookies.flush()

        // `clearFormData()` is deprecated and a no-op on modern WebView (form data
        // stopped being persisted); only the HTTP-auth credential store is real.
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }
}

/**
 * No-op [WebViewSessionStore] for unit tests and for any build with no WebView.
 * Named rather than anonymous so a test that wants "did sign-out try to clear?"
 * can say so explicitly with its own fake instead of accidentally relying on this.
 */
object NoOpWebViewSessionStore : WebViewSessionStore {
    override suspend fun clearFor(serverOrigin: String) = Unit
}
