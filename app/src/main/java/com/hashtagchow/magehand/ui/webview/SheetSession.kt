package com.hashtagchow.magehand.ui.webview

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * Everything the Sheet tab's WebView needs to come up already signed in
 * (docs/design/05-security.md §"WebView SSO").
 *
 * [toString] redacts the token: this object ends up in Compose state, and Compose
 * tooling, crash reporters and `Log.d(TAG, "$state")` all call `toString()`.
 *
 * @param serverOrigin normalized https origin — the WebView is dedicated to it,
 *   and anything else is handed to the system browser.
 * @param targetUrl the page to end up on, always inside [serverOrigin].
 */
@Immutable
class SheetSession(
    val serverOrigin: String,
    val userId: String,
    val token: String,
    val tokenExpiresAtEpochMillis: Long?,
    val targetUrl: String,
) {
    /**
     * The page loaded first, purely so that the token-setting JavaScript executes
     * *inside* [serverOrigin] — localStorage is origin-scoped, so there is no way
     * to seed it from `about:blank` (docs/design/05-security.md §1).
     */
    val bootstrapUrl: String get() = serverOrigin.trimEnd('/') + "/"

    /**
     * Meteor reads this back with `new Date(value)` (verified in the served client
     * bundle, docs/verification/WP5.md §3), so an ISO-8601 instant is exactly what
     * it wants. `null` means "server did not say" — the key is then removed rather
     * than guessed, and Meteor treats an absent expiry as "do not pre-expire".
     */
    val tokenExpiresIso: String? get() = tokenExpiresAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() }

    override fun toString(): String =
        "SheetSession(origin=$serverOrigin, userId=$userId, token=<redacted>, target=$targetUrl)"
}
