package com.hashtagchow.magehand.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.ui.screens.settings.SettingsUiState
import com.hashtagchow.magehand.ui.webview.SheetSession

/**
 * 09 decision 8, as a regression test rather than as a promise:
 *
 * > **No Sheet tab, no connection dot for local characters.** *"The tabs row shows Tracker
 * > only (or the Sheet tab is absent/disabled with no WebView instantiated); connection
 * > status is meaningless locally and must not render."*
 *
 * Both halves are *structural* here, not conditional, and this is what says so. The dot is
 * pinned on the state (`TrackerUiState.hasConnection`, exercised in both directions by
 * `TrackerUiStateTest`); the Sheet tab is pinned by the absence of anything to render — this
 * file asserts that absence, because "nobody added a session field" is exactly the kind of
 * claim that decays into "somebody added a session field".
 */
class LocalCharacterHomePostureTest {

    @Test
    fun `the local home state carries no sheet session, so no WebView can be instantiated`() {
        val sessionFields = LocalCharacterHomeUiState::class.java.declaredFields
            .filter { SheetSession::class.java.isAssignableFrom(it.type) }

        assertTrue(
            "LocalCharacterHomeUiState grew a SheetSession field: ${sessionFields.map { it.name }}. " +
                "09 decision 8 says a local character instantiates no WebView, and the way " +
                "that is guaranteed is that this screen has nothing to hand one.",
            sessionFields.isEmpty(),
        )
    }

    @Test
    fun `the local home state mentions nothing sheet-shaped or webview-shaped at all`() {
        // Broader than the type check above, and deliberately so: a session smuggled in as a
        // String url, or a `showSheetTab` flag, would pass that one.
        val suspicious = LocalCharacterHomeUiState::class.java.declaredFields
            .map { it.name }
            .filter { it.contains("sheet", ignoreCase = true) || it.contains("webview", ignoreCase = true) }

        assertTrue("LocalCharacterHomeUiState names something sheet-shaped: $suspicious", suspicious.isEmpty())
    }

    @Test
    fun `the local home starts with the connection suppressed, before anything has loaded`() {
        // The frame before the character loads is the one a defaulted-to-true field would
        // have got wrong, and it is the frame a cold open spends longest in.
        val fresh = LocalCharacterHomeUiState()

        assertFalse(fresh.tracker.hasConnection)
        assertFalse(fresh.tracker.showConnectionIndicator)
    }

    @Test
    fun `the local home's customize sheet starts reorder-only`() {
        // Same reasoning: 09 decision 8's "ONE mechanism" must hold from the first frame, or
        // the sheet briefly offers hide and pin controls that would write nothing.
        assertTrue(LocalCharacterHomeUiState().customize.reorderOnly)
    }

    // --- FR-6's default, which no other test can assert ----------------------

    @Test
    fun `show_toggles is off by default, which is the operator's stated intent`() {
        // Every other FR-6 test passes the flag explicitly (so that the pre-FR-6 assertions
        // keep meaning what they meant). This is the one place the *default* is pinned, and
        // it is the whole of FR-6's user-visible change on upgrade.
        assertFalse(AppSettingsStore.DEFAULT_SHOW_TOGGLES)
        // And the switch renders off before the first DataStore read lands, rather than
        // rendering on and flicking off under the user's thumb.
        assertFalse(SettingsUiState().showToggles)
    }
}
