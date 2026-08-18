package com.hashtagchow.magehand.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pieces of the Sheet WebView that are decidable without a device.
 *
 * [decideNavigation] used to live inline in `shouldOverrideUrlLoading` as
 * "same-origin or `ACTION_VIEW`", which crashed on a `file:` link written into a
 * (shareable) character sheet and bounced the user to a browser when an iframe
 * navigated. [SheetNavigationState] used to be a `WebView.canGoBack()` call read from
 * a skippable composable, which pinned the back handler to its first-composition
 * value of `false`.
 */
class SheetNavigationTest {

    // -----------------------------------------------------------------------
    // decideNavigation
    // -----------------------------------------------------------------------

    @Test
    fun `same-origin main-frame navigation is left to the WebView`() {
        assertEquals(
            NavigationDecision.LOAD,
            decideNavigation(isMainFrame = true, scheme = "https", isSameOrigin = true),
        )
    }

    @Test
    fun `an outbound https link opens in the system browser`() {
        assertEquals(
            NavigationDecision.OPEN_EXTERNAL,
            decideNavigation(isMainFrame = true, scheme = "https", isSameOrigin = false),
        )
    }

    @Test
    fun `plain http is still an external open — the browser, not us, warns about it`() {
        assertEquals(
            NavigationDecision.OPEN_EXTERNAL,
            decideNavigation(isMainFrame = true, scheme = "http", isSameOrigin = false),
        )
    }

    @Test
    fun `scheme matching is case-insensitive, as URI schemes are`() {
        assertEquals(
            NavigationDecision.OPEN_EXTERNAL,
            decideNavigation(isMainFrame = true, scheme = "HTTPS", isSameOrigin = false),
        )
    }

    /**
     * The crash this whole function exists for: `ACTION_VIEW` on a `file:` Uri throws
     * `FileUriExposedException` on API 24+, and sheet content is user-authored and
     * shareable, so the link can come from someone else.
     */
    @Test
    fun `a file link is blocked rather than handed to ACTION_VIEW`() {
        assertEquals(
            NavigationDecision.BLOCK,
            decideNavigation(isMainFrame = true, scheme = "file", isSameOrigin = false),
        )
    }

    @Test
    fun `hostless schemes are blocked, not externalised`() {
        listOf("data", "about", "javascript", "intent", "content", null).forEach { scheme ->
            assertEquals(
                "scheme=$scheme",
                NavigationDecision.BLOCK,
                decideNavigation(isMainFrame = true, scheme = scheme, isSameOrigin = false),
            )
        }
    }

    /**
     * An iframe re-pointing itself must not throw the user out of the page they are
     * reading. The WebView's own hardening decides what the frame may load.
     */
    @Test
    fun `sub-frame navigations are never externalised, whatever the scheme`() {
        listOf("https", "http", "file", "data", null).forEach { scheme ->
            assertEquals(
                "scheme=$scheme",
                NavigationDecision.LOAD,
                decideNavigation(isMainFrame = false, scheme = scheme, isSameOrigin = false),
            )
        }
    }

    // -----------------------------------------------------------------------
    // SheetNavigationState
    // -----------------------------------------------------------------------

    @Test
    fun `history starts empty so back leaves the screen`() {
        val state = SheetNavigationState()
        assertNull(state.currentUrl)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `a navigation event updates both the url and the back enablement`() {
        val state = SheetNavigationState()
        state.onNavigated("https://dicecloud.com/character/abc", canGoBack = true)

        assertEquals("https://dicecloud.com/character/abc", state.currentUrl)
        assertTrue(state.canGoBack)
    }

    @Test
    fun `canGoBack goes back down again when the history is exhausted`() {
        val state = SheetNavigationState()
        state.onNavigated("https://dicecloud.com/a", canGoBack = true)
        state.onNavigated("https://dicecloud.com/", canGoBack = false)

        assertFalse(state.canGoBack)
    }

    /**
     * `WebViewClient` declares its URLs nullable. A null one still carries a usable
     * history flag, so dropping the whole event would re-introduce the stale-enablement
     * bug on exactly the callbacks that report it.
     */
    @Test
    fun `a null url keeps the last known url but still applies canGoBack`() {
        val state = SheetNavigationState()
        state.onNavigated("https://dicecloud.com/a", canGoBack = false)
        state.onNavigated(null, canGoBack = true)

        assertEquals("https://dicecloud.com/a", state.currentUrl)
        assertTrue(state.canGoBack)
    }
}
