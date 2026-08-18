package com.hashtagchow.magehand.ui.webview

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Which hook actually managed to seed localStorage. Reported for the record. */
enum class SsoInjectionPhase {
    /** Nothing has run yet. */
    PENDING,

    /** docs/design/05-security.md's primary strategy: injected at `onPageStarted`. */
    PAGE_STARTED,

    /** 05's documented fallback: the `onPageStarted` write did not stick; injected at `onPageFinished`. */
    PAGE_FINISHED,

    /** Neither hook could write to localStorage. The PWA will show its own login form. */
    FAILED,
}

/**
 * The WebView client that makes the Sheet tab single-sign-on, implementing
 * docs/design/05-security.md §"WebView SSO (token injection)".
 *
 * The mechanism, exactly: Meteor reads `Meteor.loginToken`,
 * `Meteor.loginTokenExpires` and `Meteor.userId` out of the origin's localStorage
 * while booting. So the WebView loads `<origin>/` once, writes those three keys,
 * and only then `location.replace()`s to the real target — the replace is what
 * makes Meteor boot a second time and find the token, and using `replace` keeps
 * the bootstrap page out of the WebView's back stack.
 *
 * Two details this class treats as load-bearing:
 *
 * 1. **The injection is verified, not assumed.** `onPageStarted` can fire before
 *    the new document is committed, in which case the JavaScript would run in the
 *    *previous* document and the write would land on the wrong origin — or throw.
 *    The script therefore reads the key back and returns `"ok"` only if it stuck,
 *    and `location.replace` is issued from that callback. If it did not stick,
 *    `onPageFinished` (where the document is certainly committed) retries — which
 *    is precisely 05's "if a race is observed on slow devices" fallback, made
 *    automatic instead of a code change.
 * 2. **Exactly one navigation.** A `stage` field means the replace can be issued
 *    once and only once; without it the retry path can trivially become a reload
 *    loop against a live server.
 *
 * Nothing here logs the token, and nothing calls `addJavascriptInterface` — the JS
 * bridge is one-directional by design (05 §"WebView hardening").
 */
class DiceCloudSsoWebViewClient(
    private val session: SheetSession,
    private val openExternally: (Uri) -> Unit,
    private val onPhaseChanged: (SsoInjectionPhase) -> Unit = {},
    private val onPageLoaded: (String) -> Unit = {},
) : WebViewClient() {

    private enum class Stage { INJECTING, NAVIGATING, READY }

    private var stage = Stage.INJECTING
    private var injectionInFlight = false
    private var retries = 0

    private val originUri: Uri = Uri.parse(session.serverOrigin)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (stage == Stage.INJECTING && isBootstrap(url)) inject(view, SsoInjectionPhase.PAGE_STARTED)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        when {
            stage == Stage.INJECTING && isBootstrap(url) -> inject(view, SsoInjectionPhase.PAGE_FINISHED)
            stage == Stage.NAVIGATING && !isBootstrap(url) -> stage = Stage.READY
        }
        url?.let(onPageLoaded)
    }

    /**
     * docs/design/05-security.md §2: the WebView is dedicated to the account's
     * server origin, and anything else leaves for the system browser. That covers
     * the PWA's outbound links (Patreon, the docs site, HeroForge portraits opened
     * full-size) and, more importantly, any redirect to a host we did not choose —
     * the injected token must never travel to one.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (isSameOrigin(url)) return false
        openExternally(url)
        return true
    }

    private fun inject(view: WebView, phase: SsoInjectionPhase) {
        if (stage != Stage.INJECTING || injectionInFlight) return
        injectionInFlight = true
        view.evaluateJavascript(injectionScript()) { rawResult ->
            injectionInFlight = false
            val result = rawResult?.trim('"')
            if (result == OK) {
                stage = Stage.NAVIGATING
                onPhaseChanged(phase)
                Log.i(TAG, "SSO: localStorage seeded at $phase; replacing location")
                view.evaluateJavascript("location.replace(${JSONObject.quote(session.targetUrl)});", null)
            } else {
                Log.w(TAG, "SSO: seeding at $phase did not take (result=$result)")
                if (phase == SsoInjectionPhase.PAGE_FINISHED && retries < MAX_RETRIES) {
                    // The document is loaded; a short re-post is enough for the
                    // occasional "storage not ready yet" case.
                    retries++
                    view.postDelayed({ inject(view, SsoInjectionPhase.PAGE_FINISHED) }, RETRY_DELAY_MILLIS)
                } else if (phase == SsoInjectionPhase.PAGE_FINISHED) {
                    onPhaseChanged(SsoInjectionPhase.FAILED)
                }
            }
        }
    }

    /**
     * Writes the three Meteor keys and *reads one back* so the caller learns
     * whether it actually landed in the right origin. Values go through
     * [JSONObject.quote], so a token containing a quote or a backslash cannot
     * break out of the string literal.
     */
    private fun injectionScript(): String {
        val token = JSONObject.quote(session.token)
        val userId = JSONObject.quote(session.userId)
        val expires = session.tokenExpiresIso
        val expiresStatement = if (expires != null) {
            "s.setItem(\"Meteor.loginTokenExpires\", ${JSONObject.quote(expires)});"
        } else {
            // Better no expiry than a stale one from a previous account: Meteor
            // drops a token whose recorded expiry has passed.
            "s.removeItem(\"Meteor.loginTokenExpires\");"
        }
        return """
            (function () {
              try {
                var s = window.localStorage;
                s.setItem("Meteor.loginToken", $token);
                $expiresStatement
                s.setItem("Meteor.userId", $userId);
                return s.getItem("Meteor.loginToken") === $token ? "$OK" : "not-stored";
              } catch (e) {
                return "error:" + e;
              }
            })();
        """.trimIndent()
    }

    private fun isBootstrap(url: String?): Boolean =
        url != null && (url == session.bootstrapUrl || url == session.serverOrigin)

    private fun isSameOrigin(url: Uri): Boolean =
        url.scheme.equals("https", ignoreCase = true) &&
            url.host.equals(originUri.host, ignoreCase = true) &&
            url.port == originUri.port

    private companion object {
        const val TAG = "MageHandSso"
        const val OK = "ok"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MILLIS = 250L
    }
}
