package com.hashtagchow.magehand.ui.webview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the Sheet WebView should do with a navigation it was asked to perform.
 *
 * Split out of [DiceCloudSsoWebViewClient] as a pure function of three inputs so the
 * rule is a `./gradlew test` assertion rather than something only an emulator can
 * check — the inputs (`WebResourceRequest.isForMainFrame`, `Uri.scheme`, "is this
 * our origin") are all the decision ever depended on.
 */
enum class NavigationDecision {
    /** Let the WebView do whatever it was going to do. */
    LOAD,

    /** Hand the URL to the system browser and consume the navigation. */
    OPEN_EXTERNAL,

    /** Consume the navigation and do nothing at all. */
    BLOCK,
}

/**
 * docs/design/05-security.md §2: the WebView is dedicated to the account's server
 * origin, and anything else leaves for the system browser — the injected token must
 * never travel to a host we did not choose.
 *
 * Three refinements the original one-liner did not make, each of them a real bug:
 *
 * 1. **Sub-frame navigations are never externalised.** `shouldOverrideUrlLoading`
 *    fires for iframes too, and DiceCloud sheet content can embed one. Bouncing the
 *    user out to a browser because an ad frame or an embedded video re-pointed
 *    itself is a page the user never asked to leave; [LOAD] leaves the frame to the
 *    WebView, which applies its own (already hardened) rules.
 * 2. **Only `http`/`https` may leave.** Every other scheme is [BLOCK]ed. A sheet note
 *    is user-authored and shareable on DiceCloud, so `file:///…` in an `<a href>` is
 *    attacker-reachable — and handing a `file:` Uri to `ACTION_VIEW` throws
 *    `FileUriExposedException` on API 24+, i.e. a crash from a link in someone else's
 *    character sheet. `intent:`, `javascript:`, `data:` and `about:` are blocked for
 *    the same reason in reverse: they name no host, so [isSameOrigin] can only ever
 *    answer "no" for them, and none of them is something the user asked to open in
 *    another app.
 * 3. **Same-origin is checked before the scheme**, so the ordinary case stays one
 *    comparison.
 *
 * @param isMainFrame `WebResourceRequest.isForMainFrame`.
 * @param scheme the target URI's scheme, or `null` for a URI that has none.
 * @param isSameOrigin whether the target is the session's own origin.
 */
fun decideNavigation(
    isMainFrame: Boolean,
    scheme: String?,
    isSameOrigin: Boolean,
): NavigationDecision = when {
    !isMainFrame -> NavigationDecision.LOAD
    isSameOrigin -> NavigationDecision.LOAD
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) ->
        NavigationDecision.OPEN_EXTERNAL

    else -> NavigationDecision.BLOCK
}

/**
 * The Sheet WebView's navigation history, as **snapshot state**.
 *
 * This exists as its own object rather than as two properties on
 * [SheetWebViewState] because it is the half that has no `WebView` in it, and so the
 * half a plain JUnit test can construct.
 *
 * Why snapshot-backed at all: `BackHandler(enabled = …)` is read inside a skippable
 * composable, so an `enabled` computed by *calling* `WebView.canGoBack()` is
 * evaluated once, at first composition — when the PWA has no history — and the
 * composable is never invalidated afterwards, because nothing it read ever changed.
 * The handler therefore stays disabled forever and Back pops the whole screen
 * instead of stepping the sheet back a page (docs/design/04-screens-ux.md §4 asks
 * for the opposite). Writing the answer into a `MutableState` from the WebView's own
 * history callbacks is what makes the recomposition happen.
 */
@Stable
class SheetNavigationState {

    /** The last URL the WebView committed. Surfaced so the WP5 probe can record it. */
    var currentUrl: String? by mutableStateOf(null)
        private set

    /** Whether the PWA has somewhere to go back to. Drives the back handler's enablement. */
    var canGoBack: Boolean by mutableStateOf(false)
        private set

    /**
     * Records one history event.
     *
     * [url] is `null`-tolerant because `WebViewClient`'s callbacks declare it
     * nullable; a null URL still carries a usable [canGoBack], so the two are
     * updated independently rather than the event being dropped.
     */
    fun onNavigated(url: String?, canGoBack: Boolean) {
        if (url != null) currentUrl = url
        this.canGoBack = canGoBack
    }
}
