package com.hashtagchow.magehand.ui.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hashtagchow.magehand.BuildConfig
import com.hashtagchow.magehand.ui.scale.LocalUiScale

/**
 * A configured, token-injected WebView plus the small amount of state the UI needs
 * to know about it.
 *
 * The WebView instance is created by [rememberSheetWebViewState] at the *screen*
 * level, not inside the tab body, because "retained instance" is the whole point
 * (docs/design/04-screens-ux.md §4 and 01-architecture.md's `WebViewSsoController`).
 * Switching to the Tracker tab detaches the view; the instance, its DOM, its
 * scroll position and — crucially — its booted Meteor client survive, so coming
 * back is instant rather than a fresh ~2 s boot.
 */
@Stable
class SheetWebViewState internal constructor(
    val webView: WebView,
    val session: SheetSession,
) {
    /** Which hook seeded localStorage — surfaced so the WP5 probe can record it. */
    var injectionPhase: SsoInjectionPhase by mutableStateOf(SsoInjectionPhase.PENDING)
        internal set

    /**
     * The WebView's history, mirrored into snapshot state by the client's callbacks.
     *
     * Mirrored rather than queried: `WebView.canGoBack()` is an ordinary method call,
     * and a composable that reads one is never invalidated when the answer changes.
     * See [SheetNavigationState].
     */
    val navigation: SheetNavigationState = SheetNavigationState()

    /** The last URL the WebView committed. */
    val currentUrl: String? get() = navigation.currentUrl

    /** Whether the PWA has history to step back through. */
    val canGoBack: Boolean get() = navigation.canGoBack

    fun goBack() = webView.goBack()
}

/**
 * Builds (once) the WebView for [session] and destroys it when the caller leaves
 * the composition.
 *
 * Returns `null` until the session is known — the token is read out of
 * `EncryptedSharedPreferences`, so it arrives one frame late at best.
 *
 * Keyed on the session's identity: switching account or character builds a new
 * WebView rather than trying to re-point the old one, because "last injected
 * token wins" (05 §"WebView hardening") is only a safe rule if the injection
 * happens on a fresh page load.
 */
@Composable
fun rememberSheetWebViewState(session: SheetSession?): SheetWebViewState? {
    if (session == null) return null
    val context = LocalContext.current
    val state = remember(session.serverOrigin, session.targetUrl, session.userId) {
        SheetWebViewState(createHardenedWebView(context), session).also { created ->
            created.webView.webViewClient = DiceCloudSsoWebViewClient(
                session = session,
                openExternally = { uri -> context.openInSystemBrowser(uri) },
                onPhaseChanged = { created.injectionPhase = it },
                onNavigated = { url, canGoBack -> created.navigation.onNavigated(url, canGoBack) },
            )
            created.webView.loadUrl(session.bootstrapUrl)
        }
    }

    // FR-18 decision 3: the Sheet tab follows the app's UI scale. Text only — the PWA lays
    // itself out in CSS pixels this app does not own, so `textZoom` is the whole of what
    // Android exposes here, and the Settings description says exactly that rather than
    // implying the sheet scales like the rest of the app.
    //
    // A `SideEffect` and not part of `createHardenedWebView`: the WebView instance outlives
    // every tab switch by design (that is the point of the `remember` above), so "set once at
    // construction" would leave a sheet the user opened before visiting Settings rendering at
    // the old zoom until the character changed. This re-asserts it after any composition that
    // changed the scale, and is a no-op setter otherwise.
    val scale = LocalUiScale.current
    SideEffect { state.webView.settings.textZoom = scale.textZoom }

    DisposableEffect(state) {
        onDispose {
            (state.webView.parent as? ViewGroup)?.removeView(state.webView)
            state.webView.destroy()
        }
    }
    return state
}

/**
 * Renders [state]'s WebView. Safe to leave and re-enter composition — that is what
 * a tab switch does — because the view instance comes from the caller.
 *
 * Back gesture goes to WebView history first (docs/design/04-screens-ux.md §4);
 * only once the PWA has no history left does back leave the screen.
 */
@Composable
fun SheetWebViewHost(
    state: SheetWebViewState,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = {
            // Re-entering composition re-attaches the *same* instance; it may still
            // be parented to the previous AndroidView's container.
            (state.webView.parent as? ViewGroup)?.removeView(state.webView)
            state.webView
        },
    )

    BackHandler(enabled = state.canGoBack) { state.goBack() }
}

/**
 * The hardening list from docs/design/05-security.md §"WebView hardening", in
 * order, with the two PWA requirements first.
 */
private fun createHardenedWebView(context: Context): WebView = WebView(context).apply {
    with(settings) {
        // The DiceCloud PWA is a Meteor app: without both of these it renders nothing,
        // and localStorage is the SSO mechanism itself.
        javaScriptEnabled = true
        domStorageEnabled = true

        // Nothing in this WebView may reach the device's filesystem or content
        // providers — it renders a remote origin and only ever should.
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false

        // https-only, matching the app's usesCleartextTraffic=false: an https page
        // may not pull in http subresources.
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // No popups: a window we do not own would not carry the SSO client, so it
        // would render a login form and confuse the user.
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)

        mediaPlaybackRequiresUserGesture = true

        // 05 says "safe browsing default"; asserting the default keeps a future
        // manifest change from silently turning it off.
        safeBrowsingEnabled = true

        // The PWA is responsive and scrolls internally (04 §4).
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = false
    }

    // Deliberately absent: addJavascriptInterface(). We inject JS into the page and
    // expose nothing back — 05 §"WebView hardening".

    isVerticalScrollBarEnabled = true

    // chrome://inspect on debug builds only. Never on a build a user installs.
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
}

/**
 * Only ever reached for an `http`/`https` URL — [decideNavigation] blocks every other
 * scheme before it gets here.
 *
 * The catch is [RuntimeException], not `ActivityNotFoundException`, because "no app
 * can open this" is not the only way `ACTION_VIEW` fails: `FileUriExposedException`,
 * `SecurityException` from a target activity's permission, and the transaction-size
 * failure a very long URL can provoke are all unchecked, and every one of them would
 * be a crash triggered by a link in a character sheet the user did not write. Nothing
 * that happens in the system browser is worth taking the app down for.
 */
private fun Context.openInSystemBrowser(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: RuntimeException) {
        Log.w("MageHandSso", "Could not hand a '${uri.scheme}' link to another app", e)
    }
}
