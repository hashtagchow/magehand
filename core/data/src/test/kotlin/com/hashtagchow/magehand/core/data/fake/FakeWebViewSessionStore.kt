package com.hashtagchow.magehand.core.data.fake

import com.hashtagchow.magehand.core.data.auth.WebViewSessionStore

/**
 * Records the origins sign-out asked the WebView layer to forget
 * (docs/design/05-security.md §"WebView SSO" — the localStorage residue).
 */
class FakeWebViewSessionStore : WebViewSessionStore {

    val clearedOrigins: MutableList<String> = mutableListOf()

    override suspend fun clearFor(serverOrigin: String) {
        clearedOrigins += serverOrigin
    }
}
